package com.agustin.sumi.feature.reader

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderPosition
import com.agustin.sumi.domain.model.ReaderProgress
import com.agustin.sumi.domain.model.ReaderSession
import com.agustin.sumi.domain.model.positionIn
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderPageRenderer
import com.agustin.sumi.domain.repository.ReaderProgressRepository
import com.agustin.sumi.domain.repository.ReaderRepository
import com.agustin.sumi.domain.repository.ReaderResult
import com.agustin.sumi.domain.repository.ReaderViewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ReaderBook(val title: LibraryTitle, val treeGrant: LibraryGrantReference? = null)

internal sealed interface ReaderRecoverySelection {
    data class Document(val source: CbzSourceReference) : ReaderRecoverySelection
    data class Tree(val source: CbzSourceReference, val offersPersistentRead: Boolean) : ReaderRecoverySelection
}

internal enum class ReaderCleanup { REPLACEMENT, BACKGROUND, CANCEL, CLOSE }

internal sealed interface ReaderState {
    data object Closed : ReaderState
    data class Opening(val bookId: LibraryEntryId) : ReaderState
    data class Loading(val bookId: LibraryEntryId, val position: ReaderPosition) : ReaderState
    data class Available(
        val bookId: LibraryEntryId,
        val title: LibraryTitle,
        val position: ReaderPosition,
        val pageCount: Int,
        val direction: ReaderDirection,
        val frame: Long,
        val positionRestoredToFirst: Boolean,
        val progressError: ReaderError? = null,
    ) : ReaderState
    data class Failed(
        val bookId: LibraryEntryId,
        val error: ReaderError,
        val canReselectDocument: Boolean = error == ReaderError.ACCESS_DENIED || error == ReaderError.SOURCE_UNAVAILABLE,
        val canReselectTree: Boolean = false,
    ) : ReaderState
    data class Cleaning(val reason: ReaderCleanup) : ReaderState
    data class Suspended(val bookId: LibraryEntryId) : ReaderState
}

/** Commands and state publication are confined to the owning screen's main thread. */
internal class ReaderViewModel(
    private val savedState: SavedStateHandle,
    private val repository: ReaderRepository,
    private val progress: ReaderProgressRepository,
    private val renderer: ReaderPageRenderer,
    private val findBook: suspend (LibraryEntryId) -> ReaderResult<ReaderBook>,
    private val recoverBook: suspend (LibraryEntryId, ReaderBook, ReaderRecoverySelection) -> ReaderResult<Unit>,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutableState = MutableStateFlow<ReaderState>(ReaderState.Closed)
    val state = mutableState.asStateFlow()
    private val requests = MutableStateFlow(Request(0, Action.Idle))
    private var desired: BookRequest? = null
    private var foreground = false
    private var live: ActiveBook? = null
    private var latestWrite: Job? = null
    private var pickingFor: ReaderState.Failed? = null
    private var returnedSelection: ReaderRecoverySelection? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                requests.collectLatest { request -> execute(request) }
            } finally {
                withContext(NonCancellable) {
                    closeResources()
                    latestWrite?.join()
                    mutableState.value = ReaderState.Closed
                }
            }
        }
    }

    fun bindTo(owner: LifecycleOwner, changingConfigurations: () -> Boolean = { false }): () -> Unit {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = enterForeground()
            override fun onStop(owner: LifecycleOwner) {
                if (!changingConfigurations()) enterBackground()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
            }
        }
        owner.lifecycle.addObserver(observer)
        return { owner.lifecycle.removeObserver(observer) }
    }

    fun restore(viewport: ReaderViewport) {
        savedState.get<Long>(BOOK_ID)?.let { open(LibraryEntryId(it), viewport) }
    }

    fun open(bookId: LibraryEntryId, viewport: ReaderViewport) {
        val previous = desired
        val target = BookRequest(bookId, viewport)
        if (previous == target) return
        desired = target
        savedState[BOOK_ID] = bookId.value
        val recoveryInProgress = requests.value.action as? Action.Recover
        if (pickingFor?.bookId == bookId ||
            (recoveryInProgress?.target?.id == bookId && state.value !is ReaderState.Failed &&
                state.value !is ReaderState.Available && state.value !is ReaderState.Loading)) return
        if (!foreground) {
            if (state.value !is ReaderState.Cleaning) mutableState.value = ReaderState.Suspended(bookId)
            return
        }
        val active = live
        if (previous?.id == bookId && active?.session?.bookId == bookId) requestPage(target, active.position)
        else requestOpen(target)
    }

    fun enterForeground() {
        if (foreground) return
        foreground = true
        returnedSelection?.let {
            finishRecoveryPicker(it)
            return
        }
        if (pickingFor != null) return
        desired?.let(::requestOpen)
    }

    fun enterBackground() {
        if (!foreground) return
        foreground = false
        desired?.let { stop(ReaderCleanup.BACKGROUND) }
    }

    fun cancel() {
        if (desired != null) stop(ReaderCleanup.CANCEL)
    }

    fun close() {
        if (desired == null) return
        desired = null
        pickingFor = null
        returnedSelection = null
        savedState.remove<Long>(BOOK_ID)
        stop(ReaderCleanup.CLOSE)
    }

    fun retry() {
        val target = desired ?: return
        if (!foreground) return
        when (state.value) {
            is ReaderState.Failed -> live?.let { requestPage(target, it.position) } ?: requestOpen(target)
            is ReaderState.Suspended -> requestOpen(target)
            else -> Unit
        }
    }

    fun recover(selection: ReaderRecoverySelection?) {
        val failed = state.value as? ReaderState.Failed ?: return
        val target = desired ?: return
        if (selection == null || !foreground || !failed.canReselectDocument) return
        if (selection is ReaderRecoverySelection.Tree && (!failed.canReselectTree || !selection.offersPersistentRead)) return
        enqueue(Action.Recover(target, selection), ReaderState.Opening(target.id))
    }

    fun beginRecoveryPicker(tree: Boolean): Boolean {
        val failed = state.value as? ReaderState.Failed ?: return false
        if (pickingFor != null || !foreground ||
            !(if (tree) failed.canReselectTree else failed.canReselectDocument)) return false
        pickingFor = failed
        return true
    }

    // Activity results can arrive before the reader's lifecycle observer enters the foreground.
    fun finishRecoveryPicker(selection: ReaderRecoverySelection?) {
        val failed = pickingFor ?: return
        val target = desired?.takeIf { it.id == failed.bookId } ?: return
        if (selection != null && !foreground) {
            returnedSelection = selection
            return
        }
        pickingFor = null
        returnedSelection = null
        if (!foreground) return
        if (selection == null) {
            if (state.value != failed) requestOpen(target)
            return
        }
        if (selection is ReaderRecoverySelection.Tree && !selection.offersPersistentRead) {
            requestOpen(target)
            return
        }
        enqueue(Action.Recover(target, selection), ReaderState.Opening(target.id))
    }

    fun navigate(forward: Boolean) {
        val active = live ?: return
        val target = desired ?: return
        if (!foreground || target.id != active.session.bookId || state.value is ReaderState.Opening ||
            (state.value is ReaderState.Cleaning && requests.value.action !is Action.Page) || state.value is ReaderState.Suspended
        ) return
        val position = if (forward) active.session.next(active.position) else active.session.previous(active.position)
        position?.let {
            active.position = it
            requestPage(target, it)
        }
    }

    /** Called once the host actually presents this exact frame. */
    fun confirmDisplayed(frame: Long) {
        val available = state.value as? ReaderState.Available ?: return
        val active = live ?: return
        if (!foreground || available.frame != frame || available.bookId != active.session.bookId) return
        active.confirmed = available.position.pageId
        saveProgress(active, available)
    }

    fun selectDirection(direction: ReaderDirection) {
        val available = state.value as? ReaderState.Available ?: return
        val active = live ?: return
        if (!foreground || direction == active.direction) return
        active.direction = direction
        val updated = available.copy(direction = direction)
        mutableState.value = updated
        saveProgress(active, updated)
    }

    private fun saveProgress(active: ActiveBook, available: ReaderState.Available) {
        val value = ReaderProgress(active.confirmed, active.direction)
        if (value == active.lastSubmitted) return
        active.lastSubmitted = value
        val receipt = progress.confirm(active.session.bookId, value)
        val write = scope.launch(start = CoroutineStart.LAZY) {
            // A confirmation already presented to the user can finish even if its screen is disposed.
            val result = withContext(NonCancellable) { receipt.save() }
            if (live !== active || latestWrite !== currentCoroutineContext()[Job]) return@launch
            val current = state.value as? ReaderState.Available
            if (result is ReaderResult.Failure) active.lastSubmitted = null
            if (current?.frame == available.frame) {
                mutableState.value = current.copy(progressError = (result as? ReaderResult.Failure)?.error)
            }
        }
        latestWrite = write
        write.start()
    }

    private fun requestOpen(target: BookRequest) = enqueue(
        Action.Open(target), if (live != null) ReaderState.Cleaning(ReaderCleanup.REPLACEMENT) else ReaderState.Opening(target.id),
    )

    private fun requestPage(target: BookRequest, position: ReaderPosition) =
        enqueue(Action.Page(target, position), ReaderState.Loading(target.id, position))

    private fun stop(reason: ReaderCleanup) = enqueue(Action.Stop, ReaderState.Cleaning(reason))

    private fun enqueue(action: Action, next: ReaderState) {
        val current = state.value
        mutableState.value = when {
            next is ReaderState.Cleaning -> next
            current is ReaderState.Opening || current is ReaderState.Loading || current is ReaderState.Cleaning ->
                ReaderState.Cleaning(ReaderCleanup.REPLACEMENT)
            else -> next
        }
        requests.value = Request(requests.value.id + 1, action)
    }

    private suspend fun execute(request: Request) {
        when (val action = request.action) {
            Action.Idle -> Unit
            is Action.Open -> openBook(request.id, action.target)
            is Action.Page -> {
                val active = live?.takeIf { it.session.bookId == action.target.id }
                if (active == null) openBook(request.id, action.target)
                else showPage(request.id, action.target, active, action.position, false)
            }
            Action.Stop -> {
                closeResources()
                latestWrite?.join()
                publish(request.id, pickingFor ?: desired?.let { ReaderState.Suspended(it.id) } ?: ReaderState.Closed)
            }
            is Action.Recover -> recoverAndOpen(request.id, action.target, action.selection)
        }
    }

    private suspend fun openBook(id: Long, target: BookRequest) {
        closeResources()
        publish(id, ReaderState.Opening(target.id))
        latestWrite?.join()
        val book = when (val result = findBook(target.id)) {
            is ReaderResult.Success -> result.value
            is ReaderResult.Failure -> return publish(id, ReaderState.Failed(target.id, result.error))
        }
        val saved = when (val result = progress.load(target.id)) {
            is ReaderResult.Success -> result.value
            is ReaderResult.Failure -> return fail(id, target.id, book, result.error)
        }
        val session = when (val result = repository.openSession(target.id, saved.direction)) {
            is ReaderResult.Success -> result.value
            is ReaderResult.Failure -> return fail(id, target.id, book, result.error)
        }
        val position = saved.positionIn(session)
        val active = ActiveBook(book, session, position, saved)
        live = active
        showPage(id, target, active, position, saved.lastConfirmedPageId?.let { it != position.pageId } == true)
    }

    private suspend fun recoverAndOpen(id: Long, target: BookRequest, selection: ReaderRecoverySelection) {
        closeResources()
        publish(id, ReaderState.Opening(target.id))
        val book = when (val result = findBook(target.id)) {
            is ReaderResult.Success -> result.value
            is ReaderResult.Failure -> return publish(id, ReaderState.Failed(target.id, result.error))
        }
        when (val result = recoverBook(target.id, book, selection)) {
            is ReaderResult.Success -> openBook(id, desired?.takeIf { it.id == target.id } ?: target)
            is ReaderResult.Failure -> publish(id, ReaderState.Failed(
                target.id, result.error,
                canReselectDocument = result.error != ReaderError.ENTRY_NOT_FOUND,
                canReselectTree = result.error != ReaderError.ENTRY_NOT_FOUND && book.treeGrant != null,
            ))
        }
    }

    private suspend fun showPage(id: Long, target: BookRequest, active: ActiveBook, position: ReaderPosition, reset: Boolean) {
        publish(id, ReaderState.Loading(target.id, position))
        when (val result = renderer.load(active.session, position.pageId, target.viewport)) {
            is ReaderResult.Success -> publish(id, ReaderState.Available(
                target.id, active.book.title, position, active.session.pages.size, active.direction, id, reset,
            ))
            is ReaderResult.Failure -> {
                if (result.error != ReaderError.CORRUPT_PAGE && result.error != ReaderError.RESOURCE_LIMIT_EXCEEDED) closeResources()
                fail(id, target.id, active.book, result.error)
            }
        }
    }

    private suspend fun closeResources() = withContext(NonCancellable) {
        renderer.clear()
        val active = live
        live = null
        active?.let { repository.closeSession(it.session) }
        Unit
    }

    private suspend fun fail(id: Long, bookId: LibraryEntryId, book: ReaderBook, error: ReaderError) =
        publish(id, ReaderState.Failed(bookId, error, canReselectTree = book.treeGrant != null &&
            (error == ReaderError.ACCESS_DENIED || error == ReaderError.SOURCE_UNAVAILABLE)))

    private suspend fun publish(id: Long, next: ReaderState) {
        currentCoroutineContext().ensureActive()
        if (requests.value.id == id) mutableState.value = next
    }

    private data class BookRequest(val id: LibraryEntryId, val viewport: ReaderViewport)
    private data class Request(val id: Long, val action: Action)
    private sealed interface Action {
        data object Idle : Action
        data class Open(val target: BookRequest) : Action
        data class Page(val target: BookRequest, val position: ReaderPosition) : Action
        data class Recover(val target: BookRequest, val selection: ReaderRecoverySelection) : Action
        data object Stop : Action
    }

    private class ActiveBook(val book: ReaderBook, val session: ReaderSession, var position: ReaderPosition, progress: ReaderProgress) {
        var direction = progress.direction
        var confirmed: CbzPageId? = progress.lastConfirmedPageId
        var lastSubmitted: ReaderProgress? = progress
    }

    private companion object {
        const val BOOK_ID = "readerBookId"
    }
}

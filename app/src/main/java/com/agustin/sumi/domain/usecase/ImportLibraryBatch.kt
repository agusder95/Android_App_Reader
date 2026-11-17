package com.agustin.sumi.domain.usecase

import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryTreeSource
import com.agustin.sumi.domain.repository.TreeDiscovery
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal const val BATCH_QUEUE_CAPACITY = 32
internal const val BATCH_CANDIDATE_LIMIT = 1000

internal enum class LibraryBatchStatus { RUNNING, CANCELLING, COMPLETED, CANCELLED, FAILED }

internal data class LibraryBatchProgress(
    val totalCandidates: Int?,
    val discovered: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val invalidIdentities: Int = 0,
    val storageFailures: Int = 0,
    val archiveErrors: Map<CbzImportError, Int> = emptyMap(),
    val omittedKnown: Int = 0,
    val limitReached: Boolean = false,
    val discoveryFinished: Boolean = false,
    val discoveryFailed: Boolean = false,
    val skippedNodes: Int = 0,
    val branchesIncomplete: Boolean = false,
    val status: LibraryBatchStatus = LibraryBatchStatus.RUNNING,
) {
    val failed: Int get() = invalidIdentities + storageFailures + archiveErrors.values.sum()
    val processed: Int get() = imported + duplicates + failed
    val pendingKnown: Int
        get() = maxOf(discovered, totalCandidates?.coerceAtMost(BATCH_CANDIDATE_LIMIT) ?: 0) - processed
    val discoveryIncomplete: Boolean
        get() = discoveryFailed || branchesIncomplete || (totalCandidates == null && !discoveryFinished)
}

internal class LibraryImportBatch(
    val progress: StateFlow<LibraryBatchProgress>,
    private val cancelWork: () -> Unit,
) {
    fun cancel() = cancelWork()
}

/** Streams one batch at a time through the same per-document import used for single selections. */
internal class ImportLibraryBatch(private val import: ImportIntoLibrary) {
    private val activeBatch = Mutex()

    /** The producer is collected once. A fixed selection may supply its known size up front. */
    fun start(
        scope: CoroutineScope,
        candidates: Flow<CbzSourceReference>,
        fallback: LibraryTitle,
        totalCandidates: Int? = null,
    ): LibraryImportBatch? = startBatch(scope, fallback, totalCandidates) { _, consume -> consume(candidates) }

    fun startTree(
        scope: CoroutineScope,
        tree: CbzSourceReference,
        source: LibraryTreeSource,
        fallback: LibraryTitle,
    ): LibraryImportBatch? = startBatch(scope, fallback, null) { state, consume ->
        source.withTree(tree) { events ->
            consume(events.mapNotNull { event ->
                when (event) {
                    is TreeDiscovery.Candidate -> event.source
                    is TreeDiscovery.Omitted -> {
                        state.update {
                            it.copy(
                                skippedNodes = it.skippedNodes + 1,
                                limitReached = it.limitReached || event.limited,
                                branchesIncomplete = it.branchesIncomplete || event.incomplete,
                            )
                        }
                        null
                    }
                }
            })
        }
    }

    private fun startBatch(
        scope: CoroutineScope,
        fallback: LibraryTitle,
        totalCandidates: Int?,
        withCandidates: suspend (
            MutableStateFlow<LibraryBatchProgress>,
            suspend (Flow<CbzSourceReference>) -> Unit,
        ) -> Unit,
    ): LibraryImportBatch? {
        require(totalCandidates == null || totalCandidates >= 0)
        if (!activeBatch.tryLock()) return null
        val knownExcess = ((totalCandidates ?: 0) - BATCH_CANDIDATE_LIMIT).coerceAtLeast(0)
        val state = MutableStateFlow(
            LibraryBatchProgress(totalCandidates, omittedKnown = knownExcess, limitReached = knownExcess > 0),
        )
        val queue = Channel<CbzSourceReference>(BATCH_QUEUE_CAPACITY)
        val job = scope.launch(Dispatchers.IO) {
            try {
                withCandidates(state) { process(it, fallback, state, queue) }
            } catch (_: IOException) {
                ensureActive()
                state.update { it.copy(discoveryFailed = true) }
            }
        }
        job.invokeOnCompletion { failure ->
            queue.cancel()
            activeBatch.unlock()
            val status = when (failure) {
                null -> LibraryBatchStatus.COMPLETED
                is CancellationException -> LibraryBatchStatus.CANCELLED
                else -> LibraryBatchStatus.FAILED
            }
            state.update { it.copy(status = status) }
        }
        return LibraryImportBatch(state.asStateFlow()) {
            state.update {
                if (it.status == LibraryBatchStatus.RUNNING) it.copy(status = LibraryBatchStatus.CANCELLING) else it
            }
            job.cancel()
            queue.cancel()
        }
    }

    private suspend fun process(
        candidates: Flow<CbzSourceReference>,
        fallback: LibraryTitle,
        state: MutableStateFlow<LibraryBatchProgress>,
        queue: Channel<CbzSourceReference>,
    ) = coroutineScope {
        launch {
            var truncated = false
            try {
                candidates.takeWhile {
                    ensureActive()
                    truncated = state.value.discovered == BATCH_CANDIDATE_LIMIT
                    if (truncated) {
                        state.update { it.copy(limitReached = true, omittedKnown = maxOf(it.omittedKnown, 1)) }
                    }
                    !truncated
                }.collect { source ->
                    state.update { it.copy(discovered = it.discovered + 1) }
                    queue.send(source)
                }
                state.update { it.copy(discoveryFinished = !truncated) }
            } catch (_: IOException) {
                ensureActive()
                state.update { it.copy(discoveryFailed = true) }
            } finally {
                queue.close()
            }
        }
        try {
            for (source in queue) {
                ensureActive()
                val result = import(source, fallback) {
                    state.update { it.copy(imported = it.imported + 1) }
                }
                if (result !is ImportIntoLibrary.Result.Saved) {
                    state.update { progress -> progress.record(result) }
                }
            }
        } finally {
            queue.cancel()
        }
    }

    private fun LibraryBatchProgress.record(result: ImportIntoLibrary.Result): LibraryBatchProgress = when (result) {
        ImportIntoLibrary.Result.Duplicate -> copy(duplicates = duplicates + 1)
        ImportIntoLibrary.Result.InvalidIdentity -> copy(invalidIdentities = invalidIdentities + 1)
        ImportIntoLibrary.Result.StorageFailure -> copy(storageFailures = storageFailures + 1)
        is ImportIntoLibrary.Result.Rejected -> copy(
            archiveErrors = archiveErrors + (result.error to ((archiveErrors[result.error] ?: 0) + 1)),
        )
        else -> error("Unexpected import result: ${result::class.simpleName}")
    }
}

package com.agustin.sumi.feature.reader

import androidx.lifecycle.SavedStateHandle
import com.agustin.sumi.domain.model.CbzPageDescriptor
import com.agustin.sumi.domain.model.CbzPageFormat
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderOptions
import com.agustin.sumi.domain.model.ReaderPage
import com.agustin.sumi.domain.model.ReaderProgress
import com.agustin.sumi.domain.model.ReaderSession
import com.agustin.sumi.domain.model.ReaderSessionId
import com.agustin.sumi.domain.repository.DecodedReaderPage
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderPageInput
import com.agustin.sumi.domain.repository.ReaderPageRenderer
import com.agustin.sumi.domain.repository.ReaderProgressConfirmation
import com.agustin.sumi.domain.repository.ReaderProgressRepository
import com.agustin.sumi.domain.repository.ReaderRepository
import com.agustin.sumi.domain.repository.ReaderResult
import com.agustin.sumi.domain.repository.ReaderViewport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewModelTest {
    @Test
    fun ignoresLateFramesAndCapturesProgressOnlyForDisplayedCurrentPage() = runBlocking {
        withReader { fixture, model, _ ->
            val blocked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.render = { page ->
                if (page == pages[1]) {
                    blocked.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                ReaderResult.Success(Unit)
            }
            try {
                model.enterForeground()
                model.open(bookId, viewport)
                val first = model.await<ReaderState.Available>()
                assertTrue(fixture.confirmed.isEmpty())
                model.confirmDisplayed(first.frame)
                model.navigate(forward = true)
                withTimeout(5_000) { blocked.await() }
                model.navigate(forward = true)
                assertEquals(ReaderState.Cleaning(ReaderCleanup.REPLACEMENT), model.state.value)
                model.confirmDisplayed(first.frame)
                release.complete(Unit)
                val latest = model.await<ReaderState.Available>()
                assertEquals(pages[2], latest.position.pageId)
                model.confirmDisplayed(latest.frame)
                model.confirmDisplayed(latest.frame)
                model.selectDirection(ReaderDirection.RTL)
                assertEquals(listOf(ReaderProgress(pages[0]), ReaderProgress(pages[2]), ReaderProgress(pages[2], ReaderDirection.RTL)), fixture.confirmed)
                model.close()
                model.await<ReaderState.Closed>()
                assertEquals(ReaderProgress(pages[2], ReaderDirection.RTL), fixture.saved)
                assertEquals(1, fixture.closed)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun closeWaitsForABlockedOpeningAndClosesAnySessionReturnedAfterCancellation() = runBlocking {
        withReader { fixture, model, handle ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeOpen = {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            try {
                model.enterForeground()
                model.open(bookId, viewport)
                withTimeout(5_000) { started.await() }
                model.close()
                assertEquals(ReaderState.Cleaning(ReaderCleanup.CLOSE), model.state.value)
                assertEquals(0, fixture.closed)
                release.complete(Unit)
                model.await<ReaderState.Closed>()
                assertTrue(fixture.rendered.isEmpty())
                assertEquals(1, fixture.closed)
                assertNull(handle.get<Long>("readerBookId"))
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun foregroundReopensFromConfirmedPositionAndMissingPositionFallsBack() = runBlocking {
        withReader { fixture, model, handle ->
            fixture.saved = ReaderProgress(pages[1], ReaderDirection.RTL)
            model.open(bookId, viewport)
            assertEquals(ReaderState.Suspended(bookId), model.state.value)
            model.enterForeground()
            assertEquals(pages[1], model.await<ReaderState.Available>().position.pageId)
            model.enterForeground()
            assertEquals(1, fixture.opened)
            model.enterBackground()
            model.await<ReaderState.Suspended>()
            assertEquals(1, fixture.closed)
            fixture.availablePages = listOf(pages[0])
            model.enterForeground()
            val resumed = model.await<ReaderState.Available>()
            assertEquals(ReaderDirection.RTL, resumed.direction)
            assertTrue(resumed.positionRestoredToFirst)
            assertEquals(pages[0], resumed.position.pageId)
            assertEquals(bookId.value, handle.get<Long>("readerBookId"))
            assertEquals(2, fixture.opened)
            assertTrue(fixture.confirmed.isEmpty())
        }
    }

    @Test
    fun permissionAndMissingSourceOfferReselectionWhileTransientErrorRetries() = runBlocking {
        withReader { fixture, model, _ ->
            fixture.openError = ReaderError.IO_FAILURE
            model.enterForeground()
            model.open(bookId, viewport)
            val transient = model.await<ReaderState.Failed>()
            assertEquals(ReaderError.IO_FAILURE, transient.error)
            assertFalse(transient.canReselectDocument)

            fixture.openError = ReaderError.SOURCE_UNAVAILABLE
            model.retry()
            val missing = model.await<ReaderState.Failed>()
            assertTrue(missing.canReselectDocument)
            assertTrue(missing.canReselectTree)
            model.recover(null)
            assertEquals(missing, model.state.value)

            fixture.openError = ReaderError.ACCESS_DENIED
            model.retry()
            assertEquals(ReaderError.ACCESS_DENIED, model.await<ReaderState.Failed>().error)
            val replacement = ReaderRecoverySelection.Tree(CbzSourceReference("tree"), true)
            model.recover(replacement)
            assertEquals(bookId, model.await<ReaderState.Available>().bookId)
            assertEquals(listOf(replacement), fixture.recoveries)
        }
    }

    private suspend fun CoroutineScope.withReader(test: suspend (Fixture, ReaderViewModel, SavedStateHandle) -> Unit) {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val fixture = Fixture()
        val handle = SavedStateHandle()
        val model = ReaderViewModel(handle, fixture, fixture, fixture, { id ->
            if (id == bookId) ReaderResult.Success(ReaderBook(LibraryTitle("Fixture"), LibraryGrantReference("tree")))
            else ReaderResult.Failure(ReaderError.ENTRY_NOT_FOUND)
        }, { _, _, selection ->
            fixture.recoveries.add(selection)
            fixture.openError = null
            ReaderResult.Success(Unit)
        }, scope)
        try {
            test(fixture, model, handle)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    private class Fixture : ReaderRepository, ReaderProgressRepository, ReaderPageRenderer {
        var saved = ReaderProgress()
        var opened = 0
        var closed = 0
        var openError: ReaderError? = null
        var availablePages = pages
        var beforeOpen: suspend () -> Unit = {}
        var render: suspend (CbzPageId) -> ReaderResult<Unit> = { ReaderResult.Success(Unit) }
        val confirmed = mutableListOf<ReaderProgress>()
        val rendered = mutableListOf<CbzPageId>()
        val recoveries = mutableListOf<ReaderRecoverySelection>()

        override suspend fun openSession(bookId: LibraryEntryId, direction: ReaderDirection): ReaderResult<ReaderSession> {
            beforeOpen()
            openError?.let { return ReaderResult.Failure(it) }
            val id = ReaderSessionId("session-${++opened}")
            return ReaderResult.Success(ReaderSession(id, bookId, availablePages.mapIndexed { index, page ->
                ReaderPage(CbzPageDescriptor(page, CbzPageFormat.PNG), index)
            }, ReaderOptions(direction)))
        }

        override suspend fun closeSession(session: ReaderSession) { closed++ }

        override suspend fun <T> readPage(
            session: ReaderSession, pageId: CbzPageId, consume: suspend (ReaderPageInput) -> T,
        ): ReaderResult<DecodedReaderPage<T>> = error("Rendering is simulated")

        override suspend fun load(session: ReaderSession, pageId: CbzPageId, viewport: ReaderViewport): ReaderResult<Unit> {
            rendered.add(pageId)
            return render(pageId)
        }

        override fun clear() = Unit

        override suspend fun load(bookId: LibraryEntryId): ReaderResult<ReaderProgress> = ReaderResult.Success(saved)

        override fun confirm(bookId: LibraryEntryId, progress: ReaderProgress): ReaderProgressConfirmation {
            confirmed.add(progress)
            val order = confirmed.size
            return ReaderProgressConfirmation {
                if (order != confirmed.size) ReaderResult.Failure(ReaderError.STALE_PROGRESS)
                else {
                    saved = progress
                    ReaderResult.Success(Unit)
                }
            }
        }
    }

    private suspend inline fun <reified T : ReaderState> ReaderViewModel.await(): T = withTimeout(5_000) {
        state.filterIsInstance<T>().first()
    }

    private companion object {
        val bookId = LibraryEntryId(41)
        val viewport = ReaderViewport(300, 400)
        val pages = listOf(CbzPageId("a"), CbzPageId("b"), CbzPageId("c"))
    }
}

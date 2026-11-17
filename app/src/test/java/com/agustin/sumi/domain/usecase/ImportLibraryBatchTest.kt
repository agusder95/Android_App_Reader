package com.agustin.sumi.domain.usecase

import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.CbzImportRepository
import com.agustin.sumi.domain.repository.LibraryDocumentMetadata
import com.agustin.sumi.domain.repository.LibraryDocumentMetadataSource
import com.agustin.sumi.domain.repository.LibraryRepository
import com.agustin.sumi.domain.repository.LibraryWriteResult
import com.agustin.sumi.domain.repository.LibraryTreeSource
import com.agustin.sumi.domain.repository.TreeDiscovery
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportLibraryBatchTest {
    private val fallback = LibraryTitle("Sin título")

    @Test
    fun fullQueueSuspendsProducerAndOnlyOneBatchCanHoldTheSlot() = runBlocking<Unit> {
        val sent = AtomicInteger()
        val validating = AtomicInteger()
        val firstValidation = CompletableDeferred<Unit>()
        val full = CompletableDeferred<Unit>()
        val batchImport = coordinator {
            validating.incrementAndGet()
            firstValidation.complete(Unit)
            awaitCancellation()
        }
        val input = flow {
            repeat(60) {
                val sentBeforeOffer = sent.get()
                if (sentBeforeOffer == BATCH_QUEUE_CAPACITY + 1) full.complete(Unit)
                emit(reference(it))
                sent.incrementAndGet()
            }
        }
        val run = checkNotNull(batchImport.start(this, input, fallback, totalCandidates = 60))
        try {
            withTimeout(5_000) {
                full.await()
                firstValidation.await()
            }
            run.await { it.discovered == BATCH_QUEUE_CAPACITY + 2 }
            assertEquals(33, sent.get())
            assertEquals(1, validating.get())
            assertNull(batchImport.start(this, emptyFlow(), fallback))
            run.cancel()
            val result = run.await { it.status == LibraryBatchStatus.CANCELLED }
            assertEquals(60, result.pendingKnown)
            assertEquals(0, result.processed)
            assertFalse(result.discoveryIncomplete)
            val next = checkNotNull(batchImport.start(this, emptyFlow(), fallback, totalCandidates = 0))
            next.await { it.status == LibraryBatchStatus.COMPLETED }
        } finally {
            run.cancel()
        }
    }

    @Test
    fun limitCountsCandidatesBeforeDuplicatesAndDistinguishesExactBoundary() = runBlocking {
        val imported = AtomicInteger()
        val write: (LibrarySource) -> LibraryWriteResult = {
            if (imported.getAndIncrement() == 0) LibraryWriteResult.SUCCESS else LibraryWriteResult.DUPLICATE_DOCUMENT
        }
        val coordinator = coordinator(write = write)
        fun input(count: Int) = flow { repeat(count) { emit(reference(0)) } }

        val exact = checkNotNull(coordinator.start(this, input(1000), fallback))
            .await { it.status == LibraryBatchStatus.COMPLETED }
        assertEquals(1000, exact.processed)
        assertEquals(1, exact.imported)
        assertEquals(999, exact.duplicates)
        assertFalse(exact.limitReached)
        assertFalse(exact.discoveryIncomplete)

        val overflow = checkNotNull(coordinator.start(this, input(1002), fallback))
            .await { it.status == LibraryBatchStatus.COMPLETED }
        assertEquals(1000, overflow.processed)
        assertEquals(1, overflow.omittedKnown)
        assertTrue(overflow.limitReached)
        assertTrue(overflow.discoveryIncomplete)
        assertEquals(2000, imported.get())
    }

    @Test
    fun reportsTypedFailuresAndFinishesAlreadyDiscoveredCandidates() = runBlocking {
        val coordinator = coordinator(
            write = { if (it.reference == reference(2)) LibraryWriteResult.STORAGE_FAILURE else LibraryWriteResult.SUCCESS },
            archive = { if (it == reference(1)) CbzImportResult.Failure(CbzImportError.CORRUPT_ARCHIVE) else valid(it) },
        )
        val partial = flow {
            repeat(4) { emit(reference(it)) }
            throw IOException("fixture discovery failure")
        }
        val result = checkNotNull(coordinator.start(this, partial, fallback))
            .await { it.status == LibraryBatchStatus.COMPLETED }
        assertEquals(4, result.processed)
        assertEquals(1, result.imported)
        assertEquals(3, result.failed)
        assertEquals(1, result.invalidIdentities)
        assertEquals(1, result.storageFailures)
        assertEquals(mapOf(CbzImportError.CORRUPT_ARCHIVE to 1), result.archiveErrors)
        assertTrue(result.discoveryIncomplete)
        assertEquals(0, result.pendingKnown)
    }

    @Test
    fun cancelledWorkerCountsCommitReceiptEvenIfNormalResultIsNeverDelivered() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val library = TestLibrary { LibraryWriteResult.SUCCESS }
        val gatedLibrary = object : LibraryRepository by library {
            override suspend fun insertForImport(
                source: LibrarySource,
                title: LibraryTitle,
                addedAtEpochMillis: Long,
                coverPageId: CbzPageId?,
                onCommitted: () -> Unit,
            ): LibraryWriteResult {
                onCommitted()
                committed.complete(Unit)
                awaitCancellation()
            }
        }
        val batch = checkNotNull(coordinator(library = gatedLibrary).start(this, flowOf(reference(0)), fallback, 1))
        withTimeout(5_000) { committed.await() }
        batch.cancel()
        val result = batch.await { it.status == LibraryBatchStatus.CANCELLED }
        assertEquals(1, result.imported)
        assertEquals(0, result.pendingKnown)
    }

    @Test
    fun treeProgressCountsKnownCandidatesAndSummarizesOmissions() = runBlocking {
        val treeSource = object : LibraryTreeSource {
            override suspend fun <T> withTree(
                tree: CbzSourceReference,
                consume: suspend (kotlinx.coroutines.flow.Flow<TreeDiscovery>) -> T,
            ): T = consume(flowOf(
                TreeDiscovery.Candidate(reference(0)),
                TreeDiscovery.Omitted(incomplete = false),
                TreeDiscovery.Omitted(incomplete = true, limited = true),
                TreeDiscovery.Candidate(reference(1)),
            ))
        }
        val result = checkNotNull(coordinator().startTree(this, reference(99), treeSource, fallback))
            .await { it.status == LibraryBatchStatus.COMPLETED }
        assertEquals(2, result.imported)
        assertEquals(2, result.skippedNodes)
        assertTrue(result.limitReached)
        assertTrue(result.discoveryIncomplete)
        assertTrue(result.discoveryFinished)
        assertEquals(0, result.pendingKnown)
    }

    private fun reference(id: Int) = CbzSourceReference("doc-$id")
    private fun valid(source: CbzSourceReference) = CbzImportResult.Success(source, emptyList())

    private fun coordinator(
        write: (LibrarySource) -> LibraryWriteResult = { LibraryWriteResult.SUCCESS },
        library: LibraryRepository = TestLibrary(write),
        archive: suspend (CbzSourceReference) -> CbzImportResult = ::valid,
    ) = ImportLibraryBatch(
        ImportIntoLibrary(
            newImporter = { object : CbzImportRepository {
                override suspend fun importCbz(source: CbzSourceReference) = archive(source)
            } },
            documents = LibraryDocumentMetadataSource {
                if (it == reference(3)) null else LibraryDocumentMetadata(LibraryDocumentIdentity("test", it.value), null)
            },
            library = library,
        ),
    )

    private suspend fun LibraryImportBatch.await(predicate: (LibraryBatchProgress) -> Boolean): LibraryBatchProgress =
        withTimeout(10_000) { progress.first(predicate) }

    private class TestLibrary(private val write: (LibrarySource) -> LibraryWriteResult) : LibraryRepository {
        override fun observeEntries(query: LibraryQuery) = emptyFlow<List<LibraryEntry>>()
        override suspend fun insert(source: LibrarySource, title: LibraryTitle, addedAtEpochMillis: Long, coverPageId: CbzPageId?) =
            write(source)
        override suspend fun replaceSource(id: LibraryEntryId, source: LibrarySource, coverPageId: CbzPageId?) =
            error("Unexpected replacement")
        override suspend fun remove(id: LibraryEntryId) = error("Unexpected removal")
    }
}

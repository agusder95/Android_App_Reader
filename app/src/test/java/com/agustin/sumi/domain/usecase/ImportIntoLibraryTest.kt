package com.agustin.sumi.domain.usecase

import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzPageDescriptor
import com.agustin.sumi.domain.model.CbzPageFormat
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportIntoLibraryTest {
    private val source = CbzSourceReference("reference")
    private val fallback = LibraryTitle("Sin título")
    private val metadata = LibraryDocumentMetadata(LibraryDocumentIdentity("provider", "doc"), "Álbum.CBZ")
    private val firstPage = CbzPageId("first-page")
    private val valid = CbzImportResult.Success(source, listOf(CbzPageDescriptor(firstPage, CbzPageFormat.JPEG)))

    @Test
    fun publishesSuccessOnlyAfterWriteAndPassesValidatedMetadata() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<LibraryWriteResult>()
        val repository = recordingLibrary {
            writing.complete(Unit)
            commit.await()
        }
        val result = async { useCase(repository) { valid }(source, fallback) }
        writing.await()
        assertFalse(result.isCompleted)
        assertEquals(
            InsertedBook(LibrarySource(metadata.identity, source), LibraryTitle("Álbum"), 42L, firstPage),
            repository.writes.single(),
        )
        commit.complete(LibraryWriteResult.SUCCESS)
        assertEquals(ImportIntoLibrary.Result.Saved(LibraryTitle("Álbum"), 1), result.await())
    }

    @Test
    fun invalidArchiveDoesNotReachPersistence() = runBlocking {
        val repository = recordingLibrary()
        val rejected = CbzImportResult.Failure(CbzImportError.CORRUPT_ARCHIVE)
        assertEquals(ImportIntoLibrary.Result.Rejected(rejected.error), useCase(repository) { rejected }(source, fallback))
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun missingIdentityDoesNotStartArchiveValidation() = runBlocking {
        val importer = useCase(recordingLibrary(), null) { error("Invalid identity reached the importer") }
        assertEquals(ImportIntoLibrary.Result.InvalidIdentity, importer(source, fallback))
    }

    @Test
    fun cancelledValidationCannotWriteEvenWhenImporterReturnsLate() = runBlocking {
        val repository = recordingLibrary()
        val reading = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val operation = useCase(repository) {
            withContext(NonCancellable) {
                reading.complete(Unit)
                unblock.await()
                valid
            }
        }
        val result = async { operation(source, fallback) }
        reading.await()
        result.cancel()
        unblock.complete(Unit)
        result.join()
        assertTrue(result.isCancelled)
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun cancelledReselectionCannotReplaceTheExistingEntry() = runBlocking {
        val repository = recordingLibrary()
        val reading = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val operation = useCase(repository) {
            withContext(NonCancellable) {
                reading.complete(Unit)
                unblock.await()
                valid
            }
        }
        val result = async { operation.replace(LibraryEntryId(7), source) }
        reading.await()
        result.cancel()
        unblock.complete(Unit)
        result.join()
        assertTrue(result.isCancelled)
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun duplicateAndWriteFailureHaveDistinctNonSuccessResults() = runBlocking {
        val duplicate = useCase(recordingLibrary { LibraryWriteResult.DUPLICATE_DOCUMENT }) { valid }
        val failed = useCase(recordingLibrary { LibraryWriteResult.STORAGE_FAILURE }) { valid }
        assertEquals(ImportIntoLibrary.Result.Duplicate, duplicate(source, fallback))
        assertEquals(ImportIntoLibrary.Result.StorageFailure, failed(source, fallback))
    }

    private fun useCase(
        library: LibraryRepository,
        document: LibraryDocumentMetadata? = metadata,
        import: suspend () -> CbzImportResult,
    ) = ImportIntoLibrary(
        newImporter = { object : CbzImportRepository {
            override suspend fun importCbz(source: CbzSourceReference) = import()
        } },
        documents = LibraryDocumentMetadataSource { document },
        library = library,
        nowMillis = { 42L },
    )

    private fun recordingLibrary(write: suspend () -> LibraryWriteResult = { LibraryWriteResult.SUCCESS }) =
        RecordingLibrary(write)

    private data class InsertedBook(
        val source: LibrarySource,
        val title: LibraryTitle,
        val addedAt: Long,
        val cover: CbzPageId?,
    )

    private class RecordingLibrary(private val write: suspend () -> LibraryWriteResult) : LibraryRepository {
        val writes = mutableListOf<InsertedBook>()

        override fun observeEntries(query: LibraryQuery) = emptyFlow<List<LibraryEntry>>()

        override suspend fun insert(source: LibrarySource, title: LibraryTitle, addedAtEpochMillis: Long, coverPageId: CbzPageId?): LibraryWriteResult {
            writes += InsertedBook(source, title, addedAtEpochMillis, coverPageId)
            return write()
        }

        override suspend fun replaceSource(id: LibraryEntryId, source: LibrarySource, coverPageId: CbzPageId?) =
            error("Unexpected replacement")

        override suspend fun remove(id: LibraryEntryId) = error("Unexpected removal")
    }
}

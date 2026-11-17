package com.agustin.sumi.data.local.document

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.data.local.database.LibraryDatabase
import com.agustin.sumi.data.repository.LocalCbzImportRepository
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryDocumentMetadata
import com.agustin.sumi.domain.repository.LibraryAccessStatus
import com.agustin.sumi.domain.repository.LibraryDocumentMetadataSource
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryImportIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fallback = LibraryTitle("Sin título")

    @Test
    fun concurrentImportsAndNewDatabaseSessionKeepOneDurableEntry() = runBlocking {
        val name = "library-import-${UUID.randomUUID()}.db"
        val source = prepare("cbz-regular")
        try {
            val id = withDatabase(open(name)) { library ->
                val import = importer(library)
                val outcomes = listOf(async { import(source, fallback) }, async { import(source, fallback) }).awaitAll()
                assertEquals(1, outcomes.count { it is ImportIntoLibrary.Result.Saved })
                assertEquals(1, outcomes.count { it == ImportIntoLibrary.Result.Duplicate })
                val entry = library.observeEntries(LibraryQuery()).first().single()
                assertEquals(source, entry.source.reference)
                assertEquals("cbz-regular", entry.title.value)
                entry.id
            }
            withDatabase(open(name)) { library ->
                val row = library.observeEntries(LibraryQuery()).first().single()
                assertEquals(id, row.id)
                assertEquals(ImportIntoLibrary.Result.Duplicate, importer(library)(source, fallback))
                assertEquals(id, library.observeEntries(LibraryQuery()).first().single().id)
                assertTrue(LocalCbzImportRepository(SafDocumentSource(context)).importCbz(row.source.reference) is CbzImportResult.Success)
            }
        } finally {
            context.deleteDatabase(name)
            grant("revoke", "cbz-regular")
        }
    }

    @Test
    fun invalidArchiveLeavesDatabaseEmpty() = runBlocking {
        val source = prepare("cbz-corrupt")
        try {
            withDatabase(inMemory()) { library ->
                assertTrue(importer(library)(source, fallback) is ImportIntoLibrary.Result.Rejected)
                assertTrue(library.observeEntries(LibraryQuery()).first().isEmpty())
            }
        } finally {
            grant("revoke", "cbz-corrupt")
        }
    }

    @Test
    fun failedRoomWriteReturnsFailureAndCanBeRetried() = runBlocking {
        val source = prepare("cbz-regular")
        val invalidDirectory = File.createTempFile("library-blocker-", ".tmp", context.cacheDir)
        val path = File(invalidDirectory, "library.db")
        try {
            withDatabase(open(path.absolutePath)) { library ->
                assertEquals(ImportIntoLibrary.Result.StorageFailure, importer(library)(source, fallback))
                assertTrue(!path.exists())
            }
            withDatabase(inMemory()) { library ->
                assertTrue(importer(library)(source, fallback) is ImportIntoLibrary.Result.Saved)
                assertEquals(source, library.observeEntries(LibraryQuery()).first().single().source.reference)
            }
        } finally {
            invalidDirectory.delete()
            grant("revoke", "cbz-regular")
        }
    }

    @Test
    fun accessDistinguishesTransientFailureMissingDocumentAndRevokedGrant() = runBlocking {
        val source = prepare("cbz-regular")
        val documents = SafDocumentSource(context)
        val access = SafLibraryDocumentAccess(documents)
        try {
            documents.persistReadPermission(source)
            assertEquals(LibraryAccessStatus.AVAILABLE, access.check(source))
            grant("temporary-failure", "cbz-regular")
            assertEquals(LibraryAccessStatus.TEMPORARY_FAILURE, access.check(source))
            grant("restore", "cbz-regular")
            assertEquals(LibraryAccessStatus.AVAILABLE, access.check(source))
            grant("missing", "cbz-regular")
            assertEquals(LibraryAccessStatus.MISSING, access.check(source))
            grant("revoke", "cbz-regular")
            assertEquals(LibraryAccessStatus.PERMISSION_REVOKED, access.check(source))
        } finally {
            grant("restore", "cbz-regular")
            grant("revoke", "cbz-regular")
        }
    }

    @Test
    fun replacementRejectsConflictsAndInvalidArchivesBeforeRecoveringTheSameEntry() = runBlocking {
        val originalSource = prepare("cbz-regular")
        val occupied = prepare("cbz-pipe")
        val invalid = prepare("cbz-corrupt")
        val replacement = prepare("cbz-gated")
        grant("reset", "cbz-gated")
        grant("release", "cbz-gated")
        try {
            withDatabase(inMemory()) { library ->
                val import = importer(library)
                assertTrue(import(originalSource, fallback) is ImportIntoLibrary.Result.Saved)
                assertTrue(import(occupied, fallback) is ImportIntoLibrary.Result.Saved)
                val query = LibraryQuery()
                val rows = library.observeEntries(query).first()
                val original = rows.single { it.source.reference == originalSource }
                grant("revoke", "cbz-regular")
                assertEquals(ImportIntoLibrary.Result.Duplicate, import.replace(original.id, occupied))
                assertTrue(import.replace(original.id, invalid) is ImportIntoLibrary.Result.Rejected)
                assertEquals(rows, library.observeEntries(query).first())
                assertEquals(ImportIntoLibrary.Result.Recovered, import.replace(original.id, replacement))
                val updated = library.observeEntries(query).first()
                val recovered = updated.single { it.id == original.id }
                assertEquals(replacement, recovered.source.reference)
                assertEquals(original.title, recovered.title)
                assertEquals(original.addedAtEpochMillis, recovered.addedAtEpochMillis)
                assertEquals(rows.single { it.id != original.id }, updated.single { it.id != original.id })
            }
        } finally {
            for (id in listOf("cbz-regular", "cbz-pipe", "cbz-corrupt", "cbz-gated")) grant("revoke", id)
        }
    }

    private fun importer(library: RoomLibraryRepository) = ImportIntoLibrary(
        newImporter = { LocalCbzImportRepository(SafDocumentSource(context)) },
        documents = LibraryDocumentMetadataSource { source ->
            val id = checkNotNull(Uri.parse(source.value).lastPathSegment)
            LibraryDocumentMetadata(LibraryDocumentIdentity(SafTestProvider.AUTHORITY, id), "$id.cbz")
        },
        library = library,
    )

    private fun open(name: String) = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()

    private fun inMemory() = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()

    private inline fun <T> withDatabase(database: LibraryDatabase, block: (RoomLibraryRepository) -> T): T = try {
        block(RoomLibraryRepository(database.libraryDao()))
    } finally {
        database.close()
    }

    private fun prepare(id: String): CbzSourceReference {
        grant("revoke", id)
        grant("persistable", id)
        return CbzSourceReference("content://${SafTestProvider.AUTHORITY}/$id")
    }

    private fun grant(method: String, id: String) {
        context.contentResolver.call(Uri.parse("content://${SafTestProvider.AUTHORITY}/$id"), method, id, null)
    }
}

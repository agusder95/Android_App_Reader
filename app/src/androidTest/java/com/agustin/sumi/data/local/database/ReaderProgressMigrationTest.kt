package com.agustin.sumi.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.data.repository.RoomReaderProgressRepository
import com.agustin.sumi.domain.model.CbzPageDescriptor
import com.agustin.sumi.domain.model.CbzPageFormat
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderPage
import com.agustin.sumi.domain.model.ReaderProgress
import com.agustin.sumi.domain.model.ReaderSession
import com.agustin.sumi.domain.model.ReaderSessionId
import com.agustin.sumi.domain.model.positionIn
import com.agustin.sumi.domain.repository.LibraryWriteResult
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderResult
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderProgressMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val direct = LibraryEntryId(17)
    private val tree = LibraryEntryId(48)
    private val first = CbzPageId("first")
    private val old = CbzPageId("old-page")

    @get:Rule
    val schema = MigrationTestHelper(instrumentation, LibraryDatabase::class.java)

    @Test
    fun migratesRealV1AndKeepsIndependentProgressAfterReopeningAndReselection() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("readerProgressPhase")
        val fixtureId = if (phase == null) UUID.randomUUID().toString() else {
            require(phase == "prepare" || phase == "reopen")
            UUID.fromString(checkNotNull(arguments.getString("readerProgressFixture"))).toString()
        }
        val name = "reader-migration-$fixtureId.db"
        var keepFixture = false
        try {
            if (phase != "reopen") {
                createV1(name)
                writeMigratedProgress(name)
            }
            if (phase == "prepare") keepFixture = true else verifyReopenedProgress(name)
        } finally {
            if (!keepFixture) context.deleteDatabase(name)
        }
        Unit
    }

    private suspend fun writeMigratedProgress(name: String) = withDatabase(name) { database ->
        val library = RoomLibraryRepository(database.libraryDao())
        val progress = RoomReaderProgressRepository(database.readerProgressDao())
        val directEntry = checkNotNull(library.findById(direct))
        val treeEntry = checkNotNull(library.findById(tree))
        assertEquals("Álbum directo", directEntry.title.value)
        assertEquals("content://provider/document/direct", directEntry.source.reference.value)
        assertEquals("content://provider/tree/root/document/child", treeEntry.source.reference.value)
        assertEquals("child", treeEntry.source.identity.documentId)
        assertEquals("content://provider/tree/root", treeEntry.source.grantReference?.value)
        assertEquals(CbzPageId("cover"), directEntry.coverPageId)
        assertTrue(library.usesGrant(directEntry.source.reference.value))
        assertTrue(library.usesGrant(checkNotNull(treeEntry.source.grantReference).value))
        assertEquals(ReaderResult.Success(ReaderProgress()), progress.load(direct))
        assertEquals(ReaderResult.Success(ReaderProgress()), progress.load(tree))
        assertEquals(ReaderResult.Success(Unit), progress.confirm(direct, ReaderProgress(first)).save())
        assertEquals(ReaderResult.Success(Unit), progress.confirm(tree, ReaderProgress(old, ReaderDirection.RTL)).save())
    }

    private suspend fun verifyReopenedProgress(name: String) = withDatabase(name) { database ->
        val library = RoomLibraryRepository(database.libraryDao())
        val progress = RoomReaderProgressRepository(database.readerProgressDao())
        assertEquals(ReaderResult.Success(ReaderProgress(first)), progress.load(direct))
        val previous = checkNotNull(library.findById(tree))
        val replacement = LibrarySource(
            LibraryDocumentIdentity("provider", "child"),
            CbzSourceReference("content://provider/tree/new-root/document/child"),
            LibraryGrantReference("content://provider/tree/new-root"),
        )
        assertEquals(LibraryWriteResult.SUCCESS, library.replaceSource(tree, replacement, first))
        assertEquals(previous.copy(source = replacement, coverPageId = first), library.findById(tree))
        val saved = progress.load(tree) as ReaderResult.Success
        assertEquals(ReaderProgress(old, ReaderDirection.RTL), saved.value)
        val current = ReaderSession(
            ReaderSessionId("reselected"), tree,
            listOf(ReaderPage(CbzPageDescriptor(first, CbzPageFormat.PNG), 0)),
        )
        assertEquals(current.firstPosition, saved.value.positionIn(current))
        assertEquals(ReaderResult.Success(ReaderProgress(old, ReaderDirection.RTL)), progress.load(tree))
    }

    @Test
    fun validatesExportedSchemaAndRejectsLateConfirmationsWithoutRedundantUpdates() = runBlocking {
        withV1 { name ->
            schema.runMigrationsAndValidate(name, 2, true, LibraryDatabase.MIGRATION_1_2).close()
            withDatabase(name) { database ->
                val progress = RoomReaderProgressRepository(database.readerProgressDao())
                val delayed = progress.confirm(tree, ReaderProgress(old, ReaderDirection.RTL))
                val current = ReaderProgress(first)
                assertEquals(ReaderResult.Success(Unit), progress.confirm(tree, current).save())
                assertEquals(ReaderResult.Failure(ReaderError.STALE_PROGRESS), delayed.save())

                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER reject_update BEFORE UPDATE OF lastReadPageId, readingDirection ON library_entries " +
                        "WHEN OLD.id = 48 BEGIN SELECT RAISE(ABORT, 'unexpected write'); END",
                )
                val superseded = progress.confirm(tree, ReaderProgress(old))
                assertEquals(ReaderResult.Success(Unit), progress.confirm(tree, current).save())
                assertEquals(ReaderResult.Failure(ReaderError.STALE_PROGRESS), superseded.save())
                assertEquals(ReaderResult.Success(current), progress.load(tree))
                assertEquals(ReaderResult.Success(Unit), progress.confirm(direct, ReaderProgress(old)).save())
                assertEquals(ReaderResult.Failure(ReaderError.ENTRY_NOT_FOUND), progress.confirm(LibraryEntryId(-1), current).save())
            }
        }
    }

    @Test
    fun invalidationAfterUpdateRollsBackTheProgressTransaction() = runBlocking {
        withV1 { name ->
            withDatabase(name) { database ->
                val dao = database.readerProgressDao()
                val changedDuringWrite = IllegalStateException("Fixture invalidated the confirmation")
                var checks = 0
                val failure = runCatching {
                    dao.saveIfCurrent(tree.value, old.value, "RTL") {
                        if (++checks == 2) throw changedDuringWrite
                    }
                }.exceptionOrNull()
                assertEquals(changedDuringWrite, failure)
                assertEquals(ReaderProgressRow(null, "LTR"), dao.find(tree.value))
            }
        }
    }

    private suspend fun withDatabase(name: String, test: suspend (LibraryDatabase) -> Unit) {
        val database = LibraryDatabase.open(context, name)
        try {
            test(database)
        } finally {
            database.close()
        }
    }

    private suspend fun withV1(test: suspend (String) -> Unit) {
        val name = "reader-migration-${UUID.randomUUID()}.db"
        try {
            createV1(name)
            test(name)
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createV1(name: String) {
        schema.createDatabase(name, 1).use { database ->
            val insert = "INSERT INTO library_entries " +
                "(id, authority, documentId, sourceReference, grantReference, title, searchKey, titleOrderKey, " +
                "addedAtEpochMillis, coverPageId) VALUES (?, 'provider', ?, ?, ?, ?, ?, ?, ?, 'cover')"
            database.execSQL(insert, arrayOf(17, "direct", "content://provider/document/direct", null,
                "Álbum directo", "álbum directo", "álbum directo".toByteArray(Charsets.UTF_16BE), 42L))
            database.execSQL(insert, arrayOf(48, "child", "content://provider/tree/root/document/child",
                "content://provider/tree/root", "Manga", "manga", "manga".toByteArray(Charsets.UTF_16BE), 43L))
        }
    }
}

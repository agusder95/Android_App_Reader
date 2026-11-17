package com.agustin.sumi.data.local.document

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.data.local.cover.CoverCache
import com.agustin.sumi.data.local.cover.SafLibraryCoverSource
import com.agustin.sumi.data.local.cover.ThumbnailDecoder
import com.agustin.sumi.data.local.database.LibraryDatabase
import com.agustin.sumi.data.repository.LocalCbzImportRepository
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryDocumentMetadata
import com.agustin.sumi.domain.repository.LibraryRemovalResult
import com.agustin.sumi.domain.repository.LibraryWriteResult
import com.agustin.sumi.domain.usecase.CbzWorkGate
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRemovalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val documents = SafDocumentSource(context)
    private val uri = Uri.parse("content://${SafTestProvider.AUTHORITY}/cover-direct")
    private val source = CbzSourceReference(uri.toString())
    private val title = LibraryTitle("Fixture")

    @Test
    fun durableRemovalCleansTheCoverAndReimportPreservesTheOriginal() = runBlocking {
        fixture {
            val original = insert()
            assertNotNull(covers().load(original.id))
            assertTrue(cache.retainedBytes > 0)
            assertNotNull(documents.persistedReadTime(source))
            assertEquals(LibraryRemovalResult.REMOVED, maintenance.remove(original.id))
            assertEquals(0, cache.retainedBytes)
            assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
            assertNull(documents.persistedReadTime(source))

            reopen()
            assertTrue(rows().isEmpty())
            assertNull(covers().load(original.id))
            command("persistable")
            val restored = insert()
            assertNotEquals(original.id, restored.id)
            val retained = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertArrayEquals(archive, retained)
        }
    }

    @Test
    fun sharedGrantSurvivesUntilTheLastBookIsRemoved() = runBlocking {
        fixture {
            val first = insert()
            val shared = LibrarySource(
                LibraryDocumentIdentity(SafTestProvider.AUTHORITY, "another-document"),
                CbzSourceReference("content://${SafTestProvider.AUTHORITY}/another-document"),
                LibraryGrantReference(source.value),
            )
            assertEquals(LibraryWriteResult.SUCCESS, library.insert(shared, title, 0, null))
            val second = rows().single { it.id != first.id }
            assertEquals(LibraryRemovalResult.REMOVED, maintenance.remove(first.id))
            reopen()
            assertEquals(listOf(second), rows())
            documents.verifyAvailable(source)
            assertEquals(LibraryRemovalResult.REMOVED, maintenance.remove(second.id))
            assertNull(documents.persistedReadTime(source))
        }
    }

    @Test
    fun removalKeepsPreexistingAndUnverifiedGrants() = runBlocking {
        fixture {
            documents.persistReadPermission(source)
            val time = documents.persistedReadTime(source)
            val preexisting = insert()
            assertTrue(registry.read().isEmpty())
            assertEquals(LibraryRemovalResult.REMOVED, maintenance.remove(preexisting.id))
            assertEquals(time, documents.persistedReadTime(source))

            val unverified = insert()
            registry.write(mapOf(source to checkNotNull(time) - 1))
            assertEquals(LibraryRemovalResult.REMOVED, maintenance.remove(unverified.id))
            assertEquals(time, documents.persistedReadTime(source))
            assertTrue(registry.read().isEmpty())
        }
    }

    @Test
    fun deletionWaitsForActiveCoverAndDiscardsItsLateResult() = runBlocking {
        fixture {
            val entry = insert()
            val entered = CompletableDeferred<Unit>()
            val unblock = CountDownLatch(1)
            val cover = async {
                covers(ThumbnailDecoder {
                    entered.complete(Unit)
                    check(unblock.await(10, TimeUnit.SECONDS))
                }).load(entry.id)
            }
            try {
                withTimeout(10_000) { entered.await() }
                val delete = async(start = CoroutineStart.UNDISPATCHED) { maintenance.remove(entry.id) }
                assertTrue(maintenance.isRemoving(entry.id))
                assertFalse(delete.isCompleted)
                assertNotNull(documents.persistedReadTime(source))
                assertNotNull(library.findById(entry.id))
                unblock.countDown()
                assertNull(cover.await())
                assertEquals(LibraryRemovalResult.REMOVED, delete.await())
                assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
                assertNull(documents.persistedReadTime(source))
                assertNull(covers().load(entry.id))
            } finally {
                unblock.countDown()
            }
        }
    }

    @Test
    fun cancellingQueuedRemovalPreservesEntryGrantAndCover() = runBlocking {
        fixture {
            val entry = insert()
            assertNotNull(covers().load(entry.id))
            val unblock = CompletableDeferred<Unit>()
            val active = async(start = CoroutineStart.UNDISPATCHED) { work.run { unblock.await() } }
            try {
                val delete = async(start = CoroutineStart.UNDISPATCHED) { maintenance.remove(entry.id) }
                delete.cancelAndJoin()
                assertFalse(maintenance.isRemoving(entry.id))
                assertEquals(listOf(entry), rows())
                assertNotNull(documents.persistedReadTime(source))
                assertTrue(cacheDirectory.listFiles().orEmpty().single().isFile)
            } finally {
                unblock.complete(Unit)
                active.await()
            }
        }
    }

    @Test
    fun restartReconcilesInterruptedCleanupWithoutRemovingUnrelatedFiles() = runBlocking {
        fixture {
            val entry = insert()
            assertNotNull(covers().load(entry.id))
            val foreign = File(cacheDirectory, "keep.txt").apply { writeText("fixture") }
            // Simulate interruption after Room commits and before companion resources are cleaned.
            assertEquals(LibraryWriteResult.SUCCESS, library.remove(entry.id))
            assertNotNull(documents.persistedReadTime(source))
            reopen()
            maintenance.reconcile()
            assertTrue(rows().isEmpty())
            assertEquals(listOf(foreign), cacheDirectory.listFiles().orEmpty().toList())
            assertNull(documents.persistedReadTime(source))
            assertTrue(registry.read().isEmpty())
        }
    }

    @Test
    fun failedRoomDeleteLeavesTheEntryCoverAndGrant() = runBlocking {
        fixture {
            val entry = insert()
            assertNotNull(covers().load(entry.id))
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_delete BEFORE DELETE ON library_entries BEGIN SELECT RAISE(ABORT, 'test'); END",
            )
            assertEquals(LibraryRemovalResult.STORAGE_FAILURE, maintenance.remove(entry.id))
            assertEquals(listOf(entry), rows())
            assertTrue(cacheDirectory.listFiles().orEmpty().single().isFile)
            assertNotNull(documents.persistedReadTime(source))
        }
    }

    @Test
    fun cacheStorageFailureLeavesDeletionDurableAndCleanupCanRetry() = runBlocking {
        fixture {
            val entry = insert()
            assertNotNull(covers().load(entry.id))
            val parked = File(directory, "parked-cache")
            assertTrue(cacheDirectory.renameTo(parked))
            cacheDirectory.writeText("fixture causing a list failure")

            assertEquals(LibraryRemovalResult.CLEANUP_PENDING, maintenance.remove(entry.id))
            assertTrue(rows().isEmpty())
            assertEquals(0, cache.retainedBytes)
            assertNull(documents.persistedReadTime(source))
            assertTrue(cacheDirectory.delete())
            assertTrue(parked.renameTo(cacheDirectory))
            assertTrue(cacheDirectory.listFiles().orEmpty().single().isFile)

            reopen()
            maintenance.reconcile()
            assertTrue(rows().isEmpty())
            assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun failedArchiveValidationReleasesOnlyItsNewUnusedGrant() = runBlocking {
        fixture {
            setArchive(byteArrayOf(1, 2, 3))
            assertTrue(importer()(source, title) is ImportIntoLibrary.Result.Rejected)
            assertTrue(rows().isEmpty())
            assertNull(documents.persistedReadTime(source))
            assertTrue(registry.read().isEmpty())
        }
    }

    private suspend fun fixture(test: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        try {
            command("revoke")
            command("persistable")
            setArchive(fixture.archive)
            fixture.test()
        } finally {
            fixture.database.close()
            context.deleteDatabase(fixture.name)
            fixture.directory.deleteRecursively()
            command("revoke")
        }
    }

    private inner class Fixture {
        val name = "library-removal-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, "removal-${UUID.randomUUID()}").apply { check(mkdir()) }
        val cacheDirectory = File(directory, "covers")
        val registry = LibraryGrantRegistry(File(directory, "grants.json"))
        val archive = fixtureArchive()
        var database = open()
        var library = RoomLibraryRepository(database.libraryDao())
        var work = CbzWorkGate()
        var cache = CoverCache(cacheDirectory)
        var maintenance = SafLibraryMaintenance(library, documents, work, cache, registry)

        fun reopen() {
            database.close()
            database = open()
            library = RoomLibraryRepository(database.libraryDao())
            work = CbzWorkGate()
            cache = CoverCache(cacheDirectory)
            maintenance = SafLibraryMaintenance(library, documents, work, cache, registry)
        }

        fun covers(decoder: ThumbnailDecoder = ThumbnailDecoder()) =
            SafLibraryCoverSource(library::findById, documents, work, cache, decoder, maintenance::isRemoving)

        fun importer() = ImportIntoLibrary(
            newImporter = { LocalCbzImportRepository(documents) },
            documents = { LibraryDocumentMetadata(LibraryDocumentIdentity(SafTestProvider.AUTHORITY, "cover-direct"), null) },
            library = library,
            work = work,
            withAccess = maintenance::withImportAccess,
        )

        suspend fun insert(): LibraryEntry {
            assertTrue(importer()(source, title) is ImportIntoLibrary.Result.Saved)
            return rows().single()
        }

        suspend fun rows() = library.observeEntries(LibraryQuery()).first()

        private fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
    }

    private fun fixtureArchive(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        try {
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("cover.png"))
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                zip.closeEntry()
            }
        } finally {
            bitmap.recycle()
        }
        return output.toByteArray()
    }

    private fun setArchive(archive: ByteArray) = command("set-cover", Bundle().apply { putByteArray("archive", archive) })

    private fun command(method: String, extras: Bundle? = null) =
        context.contentResolver.call(uri, method, "cover-direct", extras)
}

package com.agustin.sumi.data.local.document

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.data.local.cover.CoverCache
import com.agustin.sumi.data.local.cover.SafLibraryCoverSource
import com.agustin.sumi.data.local.cover.ThumbnailDecoder
import com.agustin.sumi.data.local.database.LibraryDatabase
import com.agustin.sumi.data.repository.LocalCbzImportRepository
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.usecase.CbzWorkGate
import com.agustin.sumi.domain.repository.LibraryDocumentMetadata
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import com.agustin.sumi.domain.usecase.ImportLibraryBatch
import com.agustin.sumi.domain.usecase.LibraryBatchStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryCoverTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val documents = SafDocumentSource(context)

    @Suppress("DEPRECATION")
    @Test
    fun androidDecodesJpegPngAndWebpWithinBounds() {
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
            val data = image(2400, 1200, format)
            var pixels = 0
            var nativeSample = 0L
            val before = Debug.getNativeHeapAllocatedSize()
            val decoder = ThumbnailDecoder {
                pixels = maxOf(pixels, it)
                nativeSample = Debug.getNativeHeapAllocatedSize()
            }
            val bitmap = checkNotNull(decoder.decode({ ByteArrayInputStream(data) }, {}))
            try {
                assertEquals(512, bitmap.width)
                assertEquals(256, bitmap.height)
                assertTrue(pixels in 1..(5 * 1024 * 1024))
                Log.i("SumiCoverTest", "$format bitmapPeakBytes=$pixels nativeBefore=$before nativeAtScale=$nativeSample")
            } finally {
                bitmap.recycle()
            }
        }
        val small = image(73, 121)
        val bitmap = checkNotNull(ThumbnailDecoder().decode({ ByteArrayInputStream(small) }, {}))
        assertEquals(73, bitmap.width)
        assertEquals(121, bitmap.height)
        bitmap.recycle()
    }

    @Test
    fun diskQuotaEvictsOldestAndPressureTrimsMemory() = withFixtureDirectory { directory ->
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val limit = (image(128, 128).size * 3L) / 2
        val cache = CoverCache(directory, diskLimit = limit, memoryLimit = bitmap.allocationByteCount * 2)
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        try {
            cache.write(first, bitmap) {}
            assertTrue(File(directory, "$first.png").setLastModified(1_000))
            cache.write(second, bitmap) {}
            assertFalse(File(directory, "$first.png").exists())
            assertTrue(File(directory, "$second.png").exists())
            val diskBytes = directory.listFiles().orEmpty().sumOf(File::length)
            assertTrue(diskBytes in 1..limit)
            assertNotNull(CoverCache(directory, diskLimit = limit).read(second))
            assertEquals(131072, cache.retainedBytes)
            cache.trim()
            assertEquals(0, cache.retainedBytes)
            Log.i("SumiCoverTest", "diskBytes=$diskBytes diskLimit=$limit retainedBeforeTrim=131072 retainedAfterTrim=${cache.retainedBytes}")
            File(directory, "$first.part").writeText("interrupted")
            val unrelated = File(directory, "unrelated")
            unrelated.writeText("keep")
            cache.recover()
            assertFalse(File(directory, "$first.part").exists())
            assertEquals("keep", unrelated.readText())
            val cancelKey = "c".repeat(64)
            val cancellation = runCatching {
                cache.write(cancelKey, bitmap) {
                    if (File(directory, "$cancelKey.part").length() > 0) throw CancellationException()
                }
            }
            assertTrue(cancellation.exceptionOrNull() is CancellationException)
            assertFalse(File(directory, "$cancelKey.part").exists())
            assertFalse(File(directory, "$cancelKey.png").exists())
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun productionRamBudgetRetainsAtMost32MiBAndShrinksTo8MiB() = withFixtureDirectory { directory ->
        val cache = CoverCache(directory, diskLimit = 0)
        val bitmaps = mutableListOf<Bitmap>()
        try {
            repeat(34) { index ->
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                bitmaps.add(bitmap)
                cache.write(index.toString(16).padStart(64, '0'), bitmap) {}
            }
            assertEquals(32 * 1024 * 1024, cache.retainedBytes)
            cache.trim()
            assertEquals(8 * 1024 * 1024, cache.retainedBytes)
            Log.i("SumiCoverTest", "productionRamFullBytes=33554432 productionRamTrimmedBytes=${cache.retainedBytes}")
        } finally {
            bitmaps.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun directAndTemporarySelectFirstNaturalPageRegenerateAndRespectRevocation() = runBlocking {
        withFixtureDirectory { directory ->
            val ordered = archive("page10.png" to image(80, 100), "page2.png" to image(1200, 600))
            val direct = prepare("cover-direct", ordered)
            val pipe = prepare("cover-pipe", ordered)
            val rows = mapOf(direct.id to direct, LibraryEntryId(2) to pipe.copy(id = LibraryEntryId(2)))
            val work = CbzWorkGate()
            val store = CoverCache(directory)
            val covers = SafLibraryCoverSource({ rows[it] }, documents, work, store)
            try {
                for (id in rows.keys) {
                    val bitmap = checkNotNull(covers.load(id))
                    assertEquals(512, bitmap.width)
                    assertEquals(256, bitmap.height)
                }
                val files = directory.listFiles().orEmpty()
                assertEquals(2, files.size)
                files.forEach { assertTrue(it.delete()) }
                val cold = SafLibraryCoverSource({ rows[it] }, documents, work, CoverCache(directory))
                assertNotNull(cold.load(direct.id))
                assertEquals(1, directory.listFiles().orEmpty().size)
                command("revoke", "cover-direct")
                assertNull(cold.load(direct.id))
                command("persistable", "cover-direct")
                documents.persistReadPermission(direct.source.reference)
                val invalid = archive("page1.jpg" to byteArrayOf(-1, -40, -1, 0))
                setArchive("cover-direct", invalid)
                assertNull(cold.load(direct.id))
                assertEquals(1, directory.listFiles().orEmpty().size)
            } finally {
                command("revoke", "cover-direct")
                command("revoke", "cover-pipe")
            }
        }
    }

    @Test
    fun cancellingBlockedCoverHoldsSharedSlotUntilTemporaryIsClosed() = runBlocking {
        withFixtureDirectory { directory ->
            val entry = prepare("cbz-blocked")
            val other = prepare("cover-direct", archive("page2.png" to image(60, 70)))
            command("reset", "cbz-blocked")
            val work = CbzWorkGate()
            val covers = SafLibraryCoverSource({ entry }, documents, work, CoverCache(directory))
            val operation = async { covers.load(entry.id) }
            try {
                val temporaries = File(context.cacheDir, "cbz-import")
                withTimeout(5_000) {
                    while (temporaries.listFiles().orEmpty().none { it.name.endsWith(".cbz.part") && it.length() == 12L }) delay(25)
                }
                val acquired = CompletableDeferred<Unit>()
                val queued = async {
                    work.run {
                        acquired.complete(Unit)
                        LocalCbzImportRepository(documents).importCbz(other.source.reference)
                    }
                }
                operation.cancel()
                delay(100)
                assertFalse(acquired.isCompleted)
                command("release", "cbz-blocked")
                val result = withTimeout(5_000) {
                    operation.join()
                    queued.await()
                }
                assertTrue(result is CbzImportResult.Success)
                assertTrue(operation.isCancelled)
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                assertFalse(File(context.cacheDir, "cbz-import").listFiles().orEmpty().any { it.name.endsWith(".cbz.part") })
            } finally {
                command("release", "cbz-blocked")
                operation.cancel()
                command("revoke", "cbz-blocked")
                command("revoke", "cover-direct")
            }
        }
    }

    @Test
    fun existingArchiveTemporaryCountsAgainstAggregateBudget() = withFixtureDirectory { directory ->
        val orphan = File(directory, "import-00000000-0000-0000-0000-000000000001.cbz.part")
        orphan.writeBytes(ByteArray(7))
        val store = TemporaryCbzStore(directory, totalBudget = 10, availableBytes = { 10_000 }) {}
        val limited = runCatching {
            store.withCopy(ByteArrayInputStream(ByteArray(4)), {}) { _, _ -> error("Unexpected completion") }
        }
        assertEquals(CbzImportError.RESOURCE_LIMIT_EXCEEDED, (limited.exceptionOrNull() as DocumentAccessException).error)
        assertEquals(listOf(orphan), directory.listFiles()?.toList())
        store.recoverOrphans()
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        val production = TemporaryCbzStore(directory, availableBytes = { Long.MAX_VALUE }) {}
        val twoGiB = 2L * 1024 * 1024 * 1024
        val overBudget = runCatching {
            production.withCopy(ByteArrayInputStream(byteArrayOf(1)), {}, expectedLength = twoGiB + 1) { _, _ ->
                error("Unexpected copy over 2 GiB")
            }
        }
        assertEquals(CbzImportError.RESOURCE_LIMIT_EXCEEDED, (overBudget.exceptionOrNull() as DocumentAccessException).error)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellingBatchDiscardsBlockedAndQueuedImportsBeforeReleasingTheSlotToCover() = runBlocking {
        withFixtureDirectory { directory ->
            val first = offer("cbz-regular")
            val blocked = offer("cbz-blocked")
            val queued = offer("cbz-pipe")
            val cover = prepare("cover-pipe", archive("page1.png" to image(60, 70)))
            command("reset", "cbz-blocked")
            command("reset", "cbz-pipe")
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
            try {
                val library = RoomLibraryRepository(database.libraryDao())
                val work = CbzWorkGate()
                val cache = CoverCache(directory)
                val maintenance = SafLibraryMaintenance(
                    library, documents, work, cache, LibraryGrantRegistry(File(directory, "grants.json")),
                )
                val importer = ImportIntoLibrary(
                    newImporter = { LocalCbzImportRepository(documents) },
                    documents = { source ->
                        LibraryDocumentMetadata(LibraryDocumentIdentity(SafTestProvider.AUTHORITY, source.value), null)
                    },
                    library = library,
                    work = work,
                    withAccess = maintenance::withImportAccess,
                )
                val coordinator = ImportLibraryBatch(importer)
                val batch = checkNotNull(coordinator.start(this, flowOf(first, blocked, queued), LibraryTitle("Fixture"), 3))
                val temporaries = File(context.cacheDir, "cbz-import")
                fun parts() = temporaries.listFiles().orEmpty().filter { it.name.endsWith(".cbz.part") }
                try {
                    withTimeout(8_000) {
                        batch.progress.first { it.imported == 1 && it.discovered == 3 }
                        while (parts().singleOrNull()?.length() != 12L) delay(25)
                    }
                    val coverStarted = CompletableDeferred<Unit>()
                    val covers = SafLibraryCoverSource(
                        findEntry = {
                            assertTrue(parts().isEmpty())
                            coverStarted.complete(Unit)
                            cover
                        },
                        documents = documents,
                        work = work,
                        cache = cache,
                    )
                    val next = async(start = CoroutineStart.UNDISPATCHED) { covers.load(cover.id) }
                    batch.cancel()
                    assertEquals(LibraryBatchStatus.CANCELLING, batch.progress.value.status)
                    assertFalse(coverStarted.isCompleted)
                    assertEquals(1, parts().size)
                    assertNull(coordinator.start(this, flowOf(queued), LibraryTitle("Fixture")))
                    command("release", "cbz-blocked")
                    withTimeout(8_000) {
                        val result = batch.progress.first { it.status == LibraryBatchStatus.CANCELLED }
                        assertEquals(1, result.imported)
                        assertEquals(2, result.pendingKnown)
                        assertNotNull(next.await())
                        while (command("closed", "cbz-blocked")?.getBoolean("closed") != true) delay(25)
                    }
                    assertTrue(parts().isEmpty())
                    assertEquals(first, library.observeEntries(LibraryQuery()).first().single().source.reference)
                    assertEquals(0, command("count", "cbz-pipe")?.getInt("count"))
                    assertNull(documents.persistedReadTime(blocked))
                } finally {
                    batch.cancel()
                    command("release", "cbz-blocked")
                    withTimeout(8_000) { batch.progress.first { it.status == LibraryBatchStatus.CANCELLED } }
                    for (name in listOf("cbz-regular", "cbz-blocked", "cbz-pipe", "cover-pipe")) command("revoke", name)
                }
            } finally {
                database.close()
            }
        }
    }

    private fun offer(name: String): CbzSourceReference {
        command("revoke", name)
        command("persistable", name)
        return CbzSourceReference(uri(name).toString())
    }

    private suspend fun prepare(name: String, bytes: ByteArray? = null): LibraryEntry {
        if (bytes != null) setArchive(name, bytes)
        command("revoke", name)
        command("persistable", name)
        val source = CbzSourceReference(uri(name).toString())
        documents.persistReadPermission(source)
        return LibraryEntry(LibraryEntryId(1), LibrarySource(LibraryDocumentIdentity(SafTestProvider.AUTHORITY, name), source), LibraryTitle(name), 0)
    }

    private fun setArchive(name: String, bytes: ByteArray) {
        command("set-cover", name, Bundle().apply { putByteArray("archive", bytes) })
    }

    private fun command(method: String, name: String, extras: Bundle? = null): Bundle? =
        context.contentResolver.call(uri(name), method, name, extras)

    private fun uri(name: String): Uri = Uri.parse("content://${SafTestProvider.AUTHORITY}/$name")

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().apply { check(bitmap.compress(format, 90, this)) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun archive(vararg pages: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in pages) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private inline fun withFixtureDirectory(block: (File) -> Unit) {
        val directory = File(context.cacheDir, "covers-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

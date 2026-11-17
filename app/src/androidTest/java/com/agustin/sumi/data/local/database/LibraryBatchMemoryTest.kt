package com.agustin.sumi.data.local.database

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.data.local.archive.CbzArchiveReader
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.CbzImportRepository
import com.agustin.sumi.domain.repository.LibraryDocumentMetadata
import com.agustin.sumi.domain.usecase.BATCH_CANDIDATE_LIMIT
import com.agustin.sumi.domain.usecase.BATCH_QUEUE_CAPACITY
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import com.agustin.sumi.domain.usecase.ImportLibraryBatch
import com.agustin.sumi.domain.usecase.LibraryBatchStatus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryBatchMemoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime = Runtime.getRuntime()

    @Test
    fun measuresRetainedHeapAtFullQueueWithRealArchiveDescriptorsAndRoomWindow() = runBlocking {
        val large = createArchive(5_000)
        val ordinary = createArchive(12)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val library = RoomLibraryRepository(database.libraryDao())
            for (index in 1..21) {
                val ref = CbzSourceReference("content://seed/$index")
                library.insert(LibrarySource(identity(ref), ref), LibraryTitle("Fixture $index"), 0L, null)
            }
            val window = MutableStateFlow<List<LibraryEntry>>(emptyList())
            val watcher = launch {
                library.observeEntries(LibraryQuery(limit = 21)).collect { window.value = it }
            }
            val release = CompletableDeferred<Unit>()
            val firstDescriptors = CompletableDeferred<Int>()
            val validations = AtomicInteger()
            val archive = CbzArchiveReader()
            val workflow = ImportIntoLibrary(
                newImporter = { object : CbzImportRepository {
                    override suspend fun importCbz(source: CbzSourceReference): CbzImportResult {
                        val first = validations.getAndIncrement() == 0
                        val pages = archive.readPages(if (first) large else ordinary) {}
                        if (first) {
                            firstDescriptors.complete(pages.size)
                            release.await()
                        }
                        return CbzImportResult.Success(source, pages)
                    }
                } },
                documents = { LibraryDocumentMetadata(identity(it), "Synthetic title") },
                library = library,
            )
            withTimeout(10_000) { window.first { it.size == 21 } }
            val baseline = usedAfterGc()
            val candidates = flow {
                repeat(BATCH_CANDIDATE_LIMIT) {
                    emit(CbzSourceReference("content://fixture/document/${"a".repeat(230)}-$it"))
                }
            }
            val batch = checkNotNull(ImportLibraryBatch(workflow).start(
                this, candidates, LibraryTitle("Fixture"), BATCH_CANDIDATE_LIMIT,
            ))
            try {
                withTimeout(15_000) {
                    assertEquals(5_000, firstDescriptors.await())
                    batch.progress.first { it.discovered == BATCH_QUEUE_CAPACITY + 2 }
                }
                val full = usedAfterGc()
                release.complete(Unit)
                val summary = withTimeout(60_000) { batch.progress.first { it.status == LibraryBatchStatus.COMPLETED } }
                val after = usedAfterGc()
                assertEquals(1_000, validations.get())
                assertEquals(1_000, summary.imported)
                assertEquals(0, summary.pendingKnown)
                assertEquals(21, window.value.size)
                val peakObservedDelta = maxOf(0L, full - baseline, after - baseline)
                Log.i(
                    "SumiBatchMemory",
                    "javaHeapBytes baseline=$baseline fullQueue=$full postBatch=$after " +
                        "sampledDelta=$peakObservedDelta candidates=1000 descriptorsAtFullQueue=5000 roomWindow=21",
                )
                assertTrue("Sampled Java heap delta: $peakObservedDelta", peakObservedDelta < 16L * 1024 * 1024)
            } finally {
                batch.cancel()
                release.complete(Unit)
                withTimeout(10_000) {
                    batch.progress.first { it.status in setOf(
                        LibraryBatchStatus.COMPLETED, LibraryBatchStatus.CANCELLED, LibraryBatchStatus.FAILED,
                    ) }
                }
                watcher.cancelAndJoin()
            }
        } finally {
            database.close()
            large.delete()
            ordinary.delete()
        }
    }

    private fun usedAfterGc(): Long {
        runtime.gc()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun identity(ref: CbzSourceReference) = LibraryDocumentIdentity("fixture", ref.value)

    private fun createArchive(pages: Int): File {
        val file = File.createTempFile("library-batch-pages-", ".cbz", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(pages) { index ->
                zip.putNextEntry(ZipEntry("page-$index.jpg"))
                zip.write(byteArrayOf(-1, -40, -1, 0))
                zip.closeEntry()
            }
        }
        return file
    }
}

package com.agustin.sumi.data.local

import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.data.local.cover.CoverCache
import com.agustin.sumi.data.local.reader.NativeReaderRenderer
import com.agustin.sumi.data.local.reader.READER_BITMAP_CEILING
import com.agustin.sumi.data.local.reader.READER_BITMAP_TARGET
import com.agustin.sumi.domain.repository.ReaderViewport
import com.agustin.sumi.domain.model.CbzPageDescriptor
import com.agustin.sumi.domain.model.CbzPageFormat
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderPage
import com.agustin.sumi.domain.model.ReaderSession
import com.agustin.sumi.domain.model.ReaderSessionId
import com.agustin.sumi.domain.model.ReaderZoom
import com.agustin.sumi.domain.repository.DecodedReaderPage
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderPageInput
import com.agustin.sumi.domain.repository.ReaderRepository
import com.agustin.sumi.domain.repository.ReaderResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeReaderRendererTest {
    @Test
    @Suppress("DEPRECATION")
    fun decodesJpegPngAndWebpWithProportionalZoomAndNoPreload() = runBlocking {
        val formats = listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)
        val repo = Pages(formats.map { image(800, 400, it) })
        val renderer = NativeReaderRenderer(repo) { 0 }
        var prior: Bitmap? = null
        try {
            for (page in repo.session.pages) {
                assertEquals(ReaderResult.Success(Unit), renderer.load(repo.session, page.id, small))
                prior?.let { assertTrue(it.isRecycled) }
                renderer.withFrame(small, ReaderZoom(3f)) { bitmap, size ->
                    assertEquals(200, bitmap.width)
                    assertEquals(100, bitmap.height)
                    assertEquals(600f, size.width)
                    assertEquals(300f, size.height)
                    prior = bitmap // Test observes disposal; production drawing cannot retain it.
                } ?: error("Missing frame")
                assertEquals(repo.session.pages.take(page.index + 1).flatMap { listOf(it.id, it.id) }, repo.calls)
            }
        } finally {
            renderer.clear()
        }
        assertTrue(checkNotNull(prior).isRecycled)
        assertEquals(0L, renderer.memoryUsage().readerBytes)
    }

    @Test
    fun measuresLargePageWithRealCoverCacheAtCapacity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = CoverCache(File(context.cacheDir, "reader-measurement"), diskLimit = 0)
        repeat(32) { index -> cache.write("fixture-$index", Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)) {} }
        val repo = Pages(listOf(image(4096, 3072, Bitmap.CompressFormat.PNG)))
        val renderer = NativeReaderRenderer(repo, cache::retainedBytes)
        val large = ReaderViewport(4096, 3072)
        try {
            assertEquals(ReaderResult.Success(Unit), renderer.load(repo.session, repo.session.pages.first().id, large))
            renderer.withFrame(large, ReaderZoom.FIT_PAGE) { bitmap, _ ->
                assertEquals(2048, bitmap.width)
                assertEquals(1536, bitmap.height)
            } ?: error("Missing frame")
            val memory = renderer.memoryUsage()
            assertEquals(32L * 1024 * 1024, memory.coverBytes)
            assertEquals(12L * 1024 * 1024, memory.peakReaderBytes)
            assertEquals(44L * 1024 * 1024, memory.peakCombinedBytes)
            assertTrue(memory.peakReaderBytes <= READER_BITMAP_TARGET && memory.peakCombinedBytes <= READER_BITMAP_CEILING)
            println("Reader API ${Build.VERSION.SDK_INT}: $memory")
        } finally {
            renderer.clear()
            cache.trim()
        }
        assertEquals(0L, renderer.memoryUsage().readerBytes)
    }

    @Test
    fun dropsMalformedPageAndCandidateRejectedByRepository() = runBlocking {
        val repo = Pages(listOf(byteArrayOf(-1, -40, -1), image(800, 400, Bitmap.CompressFormat.PNG)))
        val renderer = NativeReaderRenderer(repo) { 0 }
        assertEquals(ReaderResult.Failure(ReaderError.CORRUPT_PAGE), renderer.load(repo.session, repo.session.pages[0].id, small))
        repo.afterRead = { call -> if (call == 3) ReaderError.CORRUPT_PAGE else null }
        assertEquals(ReaderResult.Failure(ReaderError.CORRUPT_PAGE), renderer.load(repo.session, repo.session.pages[1].id, small))
        assertTrue(renderer.memoryUsage().peakReaderBytes > 0)
        assertEquals(0L, renderer.memoryUsage().readerBytes)
        assertNull(renderer.withFrame(small, ReaderZoom.FIT_PAGE) { _, _ -> Unit })
    }

    @Test
    fun rapidChangeCancelsUnverifiedFrameAndKeepsOnlyLatestPage() = runBlocking {
        val repo = Pages(listOf(image(800, 400, Bitmap.CompressFormat.PNG), image(400, 800, Bitmap.CompressFormat.PNG)))
        val renderer = NativeReaderRenderer(repo) { 0 }
        val decoding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repo.afterRead = { call ->
            if (call == 2) {
                decoding.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            null
        }
        val old = async { renderer.load(repo.session, repo.session.pages[0].id, small) }
        try {
            withTimeout(5_000) { decoding.await() }
            assertNull(renderer.withFrame(small, ReaderZoom.FIT_PAGE) { _, _ -> Unit })
            val latest = async(start = CoroutineStart.UNDISPATCHED) { renderer.load(repo.session, repo.session.pages[1].id, small) }
            assertEquals(2, repo.calls.size)
            release.complete(Unit)
            assertEquals(ReaderResult.Success(Unit), latest.await())
            old.join()
            assertTrue(old.isCancelled)
            assertEquals(100, renderer.withFrame(small, ReaderZoom.FIT_PAGE) { bitmap, _ -> bitmap.width })
        } finally {
            release.complete(Unit)
            renderer.clear()
        }
    }

    private class Pages(private val images: List<ByteArray>) : ReaderRepository {
        val session = ReaderSession(ReaderSessionId("fixtures"), LibraryEntryId(1), images.indices.map {
            ReaderPage(CbzPageDescriptor(CbzPageId("$it"), CbzPageFormat.PNG), it)
        })
        val calls = mutableListOf<CbzPageId>()
        var afterRead: suspend (Int) -> ReaderError? = { null }

        override suspend fun openSession(bookId: LibraryEntryId, direction: ReaderDirection) = ReaderResult.Success(session)
        override suspend fun closeSession(session: ReaderSession) = Unit

        override suspend fun <T> readPage(
            session: ReaderSession, pageId: CbzPageId, consume: suspend (ReaderPageInput) -> T,
        ): ReaderResult<DecodedReaderPage<T>> {
            calls.add(pageId)
            val index = pageId.value.toInt()
            val stream = ByteArrayInputStream(images[index])
            val content = consume(object : ReaderPageInput {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = stream.read(buffer, offset, length)
            })
            val failure = afterRead(calls.size)
            if (failure != null) return ReaderResult.Failure(failure)
            return ReaderResult.Success(DecodedReaderPage(session.pages[index].descriptor, content))
        }
    }

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { check(bitmap.compress(format, 90, it)) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val small = ReaderViewport(200, 200)
    }
}

package com.agustin.sumi.data.local.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.ReaderSession
import com.agustin.sumi.domain.model.ReaderZoom
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderPageInput
import com.agustin.sumi.domain.repository.ReaderPageRenderer
import com.agustin.sumi.domain.repository.ReaderRepository
import com.agustin.sumi.domain.repository.ReaderResult
import com.agustin.sumi.domain.repository.ReaderViewport
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ReaderDrawingSize(val width: Float, val height: Float)

internal data class ReaderMemoryUsage(
    val readerBytes: Long,
    val coverBytes: Long,
    val peakReaderBytes: Long,
    val peakCombinedBytes: Long,
)

/** Owns the sole visible bitmap and any candidate awaiting ZIP integrity checks. */
internal class NativeReaderRenderer(
    private val repository: ReaderRepository,
    private val coverBytes: () -> Int,
) : ReaderPageRenderer {
    private val lock = Any()
    private val decodeSlot = Mutex()
    private var request: Job? = null
    private var frame: Bitmap? = null
    private var candidateBytes = 0L
    private var peakReader = 0L
    private var peakCombined = 0L

    override suspend fun load(
        session: ReaderSession,
        pageId: CbzPageId,
        viewport: ReaderViewport,
    ): ReaderResult<Unit> = coroutineScope {
        val job = currentCoroutineContext().job
        job.ensureActive()
        val previous = synchronized(lock) {
            request.also {
                request = job
                frame?.recycle()
                frame = null
            }
        }
        previous?.cancel()
        try {
            decodeSlot.withLock { decodePage(session, pageId, viewport, job) }
        } finally {
            synchronized(lock) { if (request === job) request = null }
        }
    }

    /** Borrows the frame for a synchronous draw. The callback must not retain the bitmap. */
    fun <T> withFrame(viewport: ReaderViewport, zoom: ReaderZoom, draw: (Bitmap, ReaderDrawingSize) -> T): T? =
        synchronized(lock) {
            frame?.let { bitmap ->
                val fit = minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
                val scale = fit * zoom.factor
                draw(bitmap, ReaderDrawingSize(bitmap.width * scale, bitmap.height * scale))
            }
        }

    override fun clear() {
        val previous = synchronized(lock) {
            request.also {
                request = null
                frame?.recycle()
                frame = null
            }
        }
        previous?.cancel()
    }

    fun memoryUsage(): ReaderMemoryUsage = synchronized(lock) { measureMemory() }

    private suspend fun decodePage(
        session: ReaderSession, pageId: CbzPageId, viewport: ReaderViewport, job: Job,
    ): ReaderResult<Unit> {
        var pending: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                when (val result = repository.readPage(session, pageId) { input ->
                    BitmapFactory.decodeStream(input.asStream(job), null, bounds)
                }) {
                    is ReaderResult.Failure -> return@withContext result
                    is ReaderResult.Success -> Unit
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext ReaderResult.Failure(ReaderError.CORRUPT_PAGE)
                }
                when (val result = repository.readPage(session, pageId) { input ->
                    val limit = bitmapLimit()
                    val sample = readerSampleSize(bounds.outWidth, bounds.outHeight, viewport.width, viewport.height, limit)
                        ?: throw DecodeLimit()
                    job.ensureActive()
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inScaled = false
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bitmap = BitmapFactory.decodeStream(input.asStream(job), null, options)
                    pending = bitmap
                    if (bitmap != null) {
                        synchronized(lock) {
                            candidateBytes = bitmap.allocationByteCount.toLong()
                            measureMemory()
                        }
                        if (bitmap.allocationByteCount.toLong() > limit) throw DecodeLimit()
                    }
                    job.ensureActive()
                }) {
                    is ReaderResult.Failure -> result
                    is ReaderResult.Success -> synchronized(lock) {
                        job.ensureActive()
                        if (request !== job) throw kotlinx.coroutines.CancellationException("Reader frame replaced")
                        val bitmap = pending ?: return@synchronized ReaderResult.Failure(ReaderError.CORRUPT_PAGE)
                        if (bitmap.allocationByteCount.toLong() > bitmapLimit()) throw DecodeLimit()
                        frame = bitmap
                        pending = null
                        candidateBytes = 0
                        measureMemory()
                        ReaderResult.Success(Unit)
                    }
                }
            }
        } catch (_: DecodeLimit) {
            job.ensureActive()
            return ReaderResult.Failure(ReaderError.RESOURCE_LIMIT_EXCEEDED)
        } catch (_: OutOfMemoryError) {
            job.ensureActive()
            return ReaderResult.Failure(ReaderError.RESOURCE_LIMIT_EXCEEDED)
        } finally {
            synchronized(lock) {
                pending?.recycle()
                candidateBytes = 0
                // A coroutine can be cancelled while dispatching the completed result to its caller.
                if (!job.isActive && request === job) {
                    frame?.recycle()
                    frame = null
                }
            }
        }
    }

    private fun bitmapLimit() = minOf(READER_BITMAP_TARGET, READER_BITMAP_CEILING - coverBytes().coerceAtLeast(0))

    private fun measureMemory(): ReaderMemoryUsage {
        val reader = (frame?.allocationByteCount?.toLong() ?: 0) + candidateBytes
        val covers = coverBytes().coerceAtLeast(0).toLong()
        peakReader = maxOf(peakReader, reader)
        peakCombined = maxOf(peakCombined, reader + covers)
        return ReaderMemoryUsage(reader, covers, peakReader, peakCombined)
    }

    private class DecodeLimit : RuntimeException()
}

private fun ReaderPageInput.asStream(job: Job): InputStream = object : InputStream() {
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        job.ensureActive()
        return this@asStream.read(buffer, offset, length).also { job.ensureActive() }
    }
}

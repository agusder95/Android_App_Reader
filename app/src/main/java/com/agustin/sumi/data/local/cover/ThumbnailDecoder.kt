package com.agustin.sumi.data.local.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.InputStream
import kotlin.math.roundToInt

internal const val COVER_EDGE = 512

internal class ThumbnailDecoder(private val onAllocation: (Int) -> Unit = {}) {
    fun decode(open: () -> InputStream, check: () -> Unit): Bitmap? {
        check()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) return null
        val longest = maxOf(width, height)
        var sample = 1
        while ((longest.toLong() + sample - 1) / sample > MAX_SAMPLE_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        check()
        var decoded: Bitmap? = null
        try {
            open().use { input ->
                decoded = BitmapFactory.decodeStream(input, null, options) ?: return null
                // Complete the bounded ZIP stream so its CRC and expanded length are verified.
                val buffer = ByteArray(16 * 1024)
                while (input.read(buffer) >= 0) check()
            }
            val bitmap = checkNotNull(decoded)
            check()
            if (maxOf(bitmap.width, bitmap.height) > MAX_SAMPLE_EDGE) return null
            val scale = minOf(1.0, COVER_EDGE.toDouble() / longest)
            val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
            if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
                onAllocation(bitmap.allocationByteCount)
                decoded = null
                return bitmap
            }
            val thumbnail = bitmap.scale(targetWidth, targetHeight)
            onAllocation(bitmap.allocationByteCount + thumbnail.allocationByteCount)
            return thumbnail
        } finally {
            decoded?.recycle()
        }
    }

    private companion object {
        const val MAX_SAMPLE_EDGE = 1024
        const val MAX_SOURCE_PIXELS = 100_000_000L
    }
}

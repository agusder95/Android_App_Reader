package com.agustin.sumi.data.local.reader

internal const val READER_BITMAP_TARGET = 32L * 1024 * 1024
internal const val READER_BITMAP_CEILING = 64L * 1024 * 1024

internal fun readerSampleSize(
    width: Int,
    height: Int,
    targetWidth: Int,
    targetHeight: Int,
    availableBytes: Long,
): Int? {
    if (width <= 0 || height <= 0 || availableBytes < 64) return null
    val fit = minOf(1.0, targetWidth.toDouble() / width, targetHeight.toDouble() / height)
    var sample = 1
    // Keep enough pixels to fit the viewport until the memory cap requires a smaller sample.
    while (sample < (1 shl 30) && 1.0 / (sample * 2L) >= fit) sample *= 2
    while (true) {
        val columns = (width.toLong() + sample - 1) / sample
        val rows = (height.toLong() + sample - 1) / sample
        // Allow an 8-byte pixel and row padding even when ARGB_8888 is requested.
        val rowBytes = ((columns * 8 + 63) / 64) * 64
        if (rowBytes <= availableBytes / rows) return sample
        if (sample == (1 shl 30)) return null
        sample *= 2
    }
}

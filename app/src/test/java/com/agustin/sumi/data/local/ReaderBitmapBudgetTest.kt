package com.agustin.sumi.data.local

import com.agustin.sumi.data.local.reader.READER_BITMAP_TARGET
import com.agustin.sumi.data.local.reader.readerSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderBitmapBudgetTest {
    @Test fun choosesSampleFromProportionalFit() {
        assertEquals(4, readerSampleSize(4000, 2000, 1000, 1000, READER_BITMAP_TARGET))
        assertEquals(1, readerSampleSize(200, 400, 1080, 1920, READER_BITMAP_TARGET))
    }

    @Test fun usesCoarserSampleUnderPressureWithoutIntegerOverflow() {
        assertEquals(4, readerSampleSize(4096, 3072, 4096, 3072, 8L * 1024 * 1024))
        assertEquals(1 shl 30, readerSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 10, 10, 128))
        assertNull(readerSampleSize(4096, 3072, 4096, 3072, 63))
    }
}

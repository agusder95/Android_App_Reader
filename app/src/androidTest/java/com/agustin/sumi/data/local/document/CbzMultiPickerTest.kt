package com.agustin.sumi.data.local.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CbzMultiPickerTest {
    @Test
    fun multiplePickerImportsValidFilesAndRetainsPartialResults() {
        CbzPickerChecks(InstrumentationRegistry.getInstrumentation()).runMultiple()
    }
}

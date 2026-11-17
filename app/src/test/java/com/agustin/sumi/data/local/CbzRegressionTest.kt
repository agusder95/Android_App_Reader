package com.agustin.sumi.data.local

import com.agustin.sumi.data.local.archive.main as runArchiveChecks
import com.agustin.sumi.data.local.document.main as runTemporaryStoreChecks
import org.junit.Test

class CbzRegressionTest {
    @Test
    fun archiveChecks() = runArchiveChecks()

    @Test
    fun temporaryStoreChecks() = runTemporaryStoreChecks()
}

package com.agustin.sumi.data.local.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CbzImportRegressionTest {
    @Test
    fun documentAccess() = runBlocking {
        val checks = SafDocumentChecks(InstrumentationRegistry.getInstrumentation().targetContext)
        checks.verifyAccess()
        // Also clear the persisted fixture. The legacy restart phase still tests a new process.
        checks.verifyAfterRestart()
        Unit
    }

    @Test
    fun repository() = runBlocking {
        CbzImportRepositoryChecks(InstrumentationRegistry.getInstrumentation().targetContext).run()
        Unit
    }
}

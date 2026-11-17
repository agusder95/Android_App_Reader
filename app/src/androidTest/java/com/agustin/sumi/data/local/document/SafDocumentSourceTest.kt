package com.agustin.sumi.data.local.document

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzSourceReference
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking

class SafDocumentSourceTest : AndroidJUnitRunner() {
    private var phase: String? = null

    override fun onCreate(arguments: Bundle?) {
        phase = arguments?.getString("phase")
        super.onCreate(arguments)
    }

    override fun onStart() {
        if (phase == null) {
            super.onStart()
            return
        }
        val result = Bundle()
        try {
            val checks = SafDocumentChecks(targetContext)
            val cases = runBlocking {
                when (phase) {
                    "reopen" -> checks.verifyAfterRestart()
                    "repository" -> CbzImportRepositoryChecks(targetContext).run()
                    "picker" -> CbzPickerChecks(this@SafDocumentSourceTest).run()
                    "recovery-picker" -> CbzPickerChecks(this@SafDocumentSourceTest).runRecovery()
                    "tree-prepare" -> TreeLibraryChecks(targetContext).prepareProcessRestart()
                    "tree-reopen" -> TreeLibraryChecks(targetContext).verifyProcessRestart()
                    "interrupt-copy" -> CbzImportRepositoryChecks(targetContext).prepareInterruptedCopy()
                    "recover-copy" -> CbzImportRepositoryChecks(targetContext).verifyInterruptedCopyRecovery()
                    else -> checks.verifyAccess()
                }
            }
            result.putString("stream", "\nPASS ${cases.joinToString("\nPASS ")}\n${cases.size} tests passed\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("stream", "\n${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

internal class SafDocumentChecks(private val context: Context) {
    suspend fun verifyAccess(): List<String> {
        val source = SafDocumentSource(context)
        val regular = reference("regular")
        val pipe = reference("pipe")
        val missing = reference("missing")
        val transient = reference("transient")
        val cases = mutableListOf<String>()
        command("revoke", "regular")
        command("persistable", "regular")
        check(source.persistReadPermission(regular))
        check(!source.persistReadPermission(regular))
        verifyDirect(source, regular)
        cases += "persists a read grant and reads the same seekable descriptor directly"

        command("revoke", "pipe")
        command("persistable", "pipe")
        check(source.persistReadPermission(pipe))
        var copy: File? = null
        source.withDocument(pipe) { document ->
            check(document is OpenedCbzDocument.Temporary)
            check(document.length == SafTestProvider.BYTES.size.toLong())
            check(document.file.readBytes().contentEquals(SafTestProvider.BYTES))
            copy = document.file
        }
        check(copy?.exists() == false)
        command("revoke", "pipe")
        expectError(CbzImportError.ACCESS_DENIED) { source.withDocument(pipe) { error("Revoked") } }
        cases += "copies a sequential descriptor and removes the temporary before returning"
        cases += "reports revoked read permission"

        command("revoke", "transient")
        command("transient", "transient")
        expectError(CbzImportError.PERSISTENT_PERMISSION_UNAVAILABLE) {
            source.persistReadPermission(transient)
        }
        cases += "reports a grant that cannot be persisted"

        command("revoke", "missing")
        command("persistable", "missing")
        check(source.persistReadPermission(missing))
        expectError(CbzImportError.SOURCE_UNAVAILABLE) { source.withDocument(missing) { error("Missing") } }
        source.releaseReadPermission(missing)
        cases += "reports a missing document despite the persisted grant"

        val directory = File(context.cacheDir, "cbz-import")
        check(directory.isDirectory)
        File(directory, ORPHAN).writeText("fixture")
        File(directory, UNRELATED).writeText("keep")
        return cases
    }

    suspend fun verifyAfterRestart(): List<String> {
        val source = SafDocumentSource(context)
        val directory = File(context.cacheDir, "cbz-import")
        val orphan = File(directory, ORPHAN)
        val unrelated = File(directory, UNRELATED)
        check(orphan.isFile && unrelated.isFile)
        val regular = reference("regular")
        try {
            verifyDirect(source, regular)
            check(!orphan.exists() && unrelated.readText() == "keep")
            return listOf(
                "reopens the original using the persisted grant in a new app process",
                "removes only its orphan before reopening a document",
            )
        } finally {
            unrelated.delete()
            source.releaseReadPermission(regular)
        }
    }

    private suspend fun verifyDirect(source: SafDocumentSource, reference: CbzSourceReference) {
        source.withDocument(reference) { document ->
            check(document is OpenedCbzDocument.Direct)
            check(document.length == SafTestProvider.BYTES.size.toLong())
            val lastByte = ByteBuffer.allocate(1)
            check(document.channel.read(lastByte, document.length - 1) == 1)
            check(lastByte.array().single() == SafTestProvider.BYTES.last())
        }
    }

    private fun reference(name: String) = CbzSourceReference("content://${SafTestProvider.AUTHORITY}/$name")

    private fun command(method: String, name: String) {
        context.contentResolver.call(Uri.parse("content://${SafTestProvider.AUTHORITY}"), method, name, null)
    }

    private suspend fun expectError(error: CbzImportError, block: suspend () -> Unit) {
        val thrown = runCatching { block() }.exceptionOrNull()
        check(thrown is DocumentAccessException && thrown.error == error)
    }

    private companion object {
        const val ORPHAN = "import-01234567-89ab-cdef-0123-456789abcdef.cbz.part"
        const val UNRELATED = "foreign-fixture"
    }
}

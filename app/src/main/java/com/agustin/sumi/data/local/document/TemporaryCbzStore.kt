package com.agustin.sumi.data.local.document

import com.agustin.sumi.domain.model.CbzImportError
import java.io.File
import java.io.InputStream
import java.util.UUID

internal const val MAX_CBZ_BYTES = 2L * 1024 * 1024 * 1024

internal class TemporaryCbzStore(
    private val directory: File,
    private val maxBytes: Long = MAX_CBZ_BYTES,
    private val totalBudget: Long = MAX_CBZ_BYTES,
    private val availableBytes: (File) -> Long,
    private val onCleanupFailure: () -> Unit,
) {
    fun recoverOrphans() {
        synchronized(activeFiles) {
            if (!directory.exists()) return
            val files = directory.listFiles()
                ?: throw DocumentAccessException(CbzImportError.IO_FAILURE)
            for (file in files) {
                if (file.isFile && temporaryName.matches(file.name) && file !in activeFiles) {
                    deleteTemporary(file)
                }
            }
        }
    }

    /**
     * Closes [input] before passing the temporary to [read].
     * [read] must close any handles it opens before returning.
     */
    fun <T> withCopy(
        input: InputStream,
        checkCancellation: () -> Unit,
        expectedLength: Long? = null,
        read: (File, Long) -> T,
    ): T {
        var temporary: File? = null
        try {
            val written = input.use {
                checkCancellation()
                val otherBytes = ownedBytes()
                val remainingBudget = totalBudget - otherBytes
                if (remainingBudget <= 0 || (expectedLength != null && expectedLength > remainingBudget)) {
                    throw DocumentAccessException(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
                }
                val file = createTemporary(expectedLength)
                temporary = file
                copyInto(input, file, minOf(maxBytes, remainingBudget), checkCancellation)
            }
            checkCancellation()
            return read(checkNotNull(temporary), written).also { checkCancellation() }
        } finally {
            temporary?.let { file ->
                synchronized(activeFiles) {
                    activeFiles.remove(file)
                    deleteTemporary(file)
                }
            }
        }
    }

    private fun createTemporary(expectedLength: Long?): File =
        synchronized(activeFiles) {
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw DocumentAccessException(CbzImportError.IO_FAILURE)
            }
            val available = availableBytes(directory)
            if (available <= 0 || (expectedLength != null && expectedLength > available)) {
                throw DocumentAccessException(CbzImportError.INSUFFICIENT_SPACE)
            }
            File(directory, "import-${UUID.randomUUID()}.cbz.part").also {
                if (!it.createNewFile()) throw DocumentAccessException(CbzImportError.IO_FAILURE)
                activeFiles.add(it)
            }
        }

    private fun ownedBytes(): Long = synchronized(activeFiles) {
        directory.listFiles().orEmpty().filter { it.isFile && temporaryName.matches(it.name) }.sumOf(File::length)
    }

    private fun copyInto(input: InputStream, file: File, limit: Long, checkCancellation: () -> Unit): Long {
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        file.outputStream().use { output ->
            while (true) {
                checkCancellation()
                val request = minOf(buffer.size.toLong(), limit - written + 1).toInt()
                val count = input.read(buffer, 0, request)
                checkCancellation()
                if (count < 0) break
                if (count > limit - written) {
                    throw DocumentAccessException(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
                }
                if (count > availableBytes(directory)) {
                    throw DocumentAccessException(CbzImportError.INSUFFICIENT_SPACE)
                }
                output.write(buffer, 0, count)
                written += count
            }
        }
        return written
    }

    private fun deleteTemporary(file: File) {
        if (!file.delete() && file.exists()) onCleanupFailure()
    }

    private companion object {
        val activeFiles = mutableSetOf<File>()
        val temporaryName = Regex("import-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.cbz\\.part")
    }
}

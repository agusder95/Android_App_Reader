package com.agustin.sumi.data.local.archive

import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzPageFormat
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.repository.ReaderPageInput
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32

internal data class IndexedCbzEntry(val id: CbzPageId, val structure: ZipStructure.Entry)

/** Entry payload bytes fetched and signature bytes produced, excluding ZIP metadata. */
internal data class CbzPageProbe(val id: CbzPageId, val expandedBytes: Int, val packedBytesRead: Long)

internal class CbzArchiveIndex(val length: Long, val entries: List<IndexedCbzEntry>) {
    /** Compares the data used for page identity and reopening, without reading image payloads. */
    fun matches(other: CbzArchiveIndex): Boolean =
        length == other.length && entries.size == other.entries.size && entries.indices.all { index ->
            val current = entries[index]
            val previous = other.entries[index]
            current.id == previous.id && current.structure.matches(previous.structure)
        }

    private fun ZipStructure.Entry.matches(other: ZipStructure.Entry): Boolean =
        directory == other.directory && method == other.method && flags == other.flags &&
            crc == other.crc && packed == other.packed && expanded == other.expanded &&
            dataOffset == other.dataOffset
}

/** Single entry scoped to the caller's channel. Success requires [verifyComplete]. */
internal class VerifiedCbzPage(
    private val input: InputStream,
    private val entry: ZipStructure.Entry,
    private val checkCancellation: () -> Unit,
) : ReaderPageInput, Closeable {
    private val crc = CRC32()
    private var expanded = 0L
    private var complete = false
    private var closed = false
    private var readFailure: IOException? = null

    private val prefix = ByteArray(ZIP_ENTRY_PREFIX_BYTES)
    private val prefixSize = readPrefix()
    private var prefixCursor = 0
    val format: CbzPageFormat = identifyCbzPageFormat(prefix.copyOf(prefixSize))
        ?: zipFailure(CbzImportError.NO_SUPPORTED_IMAGES)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        ensureOpen()
        if (length == 0) return 0
        if (prefixCursor == prefixSize) return readEntry(buffer, offset, length)
        val count = minOf(length, prefixSize - prefixCursor)
        prefix.copyInto(buffer, offset, prefixCursor, prefixCursor + count)
        prefixCursor += count
        return count
    }

    fun verifyComplete() {
        ensureOpen()
        if (complete) return
        val buffer = ByteArray(64 * 1024)
        while (readEntry(buffer, 0, buffer.size) >= 0) {
            // Check the whole entry even if the consumer only needed its header.
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }

    private fun readPrefix(): Int {
        var count = 0
        while (count < prefix.size) {
            val read = readEntry(prefix, count, prefix.size - count)
            if (read < 0) break
            count += read
        }
        return count
    }

    private fun ensureOpen() {
        checkCancellation()
        if (closed) throw IOException("CBZ page input is closed")
        readFailure?.let { throw it }
    }

    private fun readEntry(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureOpen()
        if (complete) return -1
        return try {
            val remaining = entry.expanded - expanded
            val count = input.read(buffer, offset, minOf(length.toLong(), remaining + 1).toInt())
            checkCancellation()
            if (count < 0) {
                requireZip(remaining == 0L && crc.value == entry.crc)
                complete = true
            } else {
                requireZip(count > 0 && count <= remaining)
                expanded += count
                crc.update(buffer, offset, count)
            }
            count
        } catch (failure: IOException) {
            readFailure = failure
            throw failure
        }
    }
}

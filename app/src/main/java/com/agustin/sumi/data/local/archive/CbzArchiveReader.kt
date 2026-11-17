package com.agustin.sumi.data.local.archive

import com.agustin.sumi.data.local.document.MAX_CBZ_BYTES
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzPageDescriptor
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal const val ZIP_ENTRY_PREFIX_BYTES = 12

internal data class ZipValidationLimits(
    val archiveBytes: Long = MAX_CBZ_BYTES,
    val entries: Int = 5000,
    val imageBytes: Long = 100L * 1024 * 1024,
    val expandedBytes: Long = 4L * 1024 * 1024 * 1024,
)

internal class ValidatedZipEntry(
    val name: ZipEntryName,
    val directory: Boolean,
    val prefix: ByteArray,
    val structure: ZipStructure.Entry,
)

internal data class FirstCbzPage(
    val descriptor: CbzPageDescriptor,
    val crc: Long,
    val size: Long,
    val open: () -> InputStream,
)

/**
 * Validates an already opened, seekable source. The caller owns the channel and runs
 * validation on its I/O context. Entry names can be read from that same source.
 */
internal class CbzArchiveReader(private val limits: ZipValidationLimits = ZipValidationLimits()) {
    /** Parses ZIP records without opening a decoder for any entry. */
    fun index(channel: FileChannel, check: () -> Unit): CbzArchiveIndex {
        val length = channel.size()
        val directory = readStructure(channel, length, check)
        var remaining = limits.expandedBytes
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = ArrayList<IndexedCbzEntry>(directory.entries.size)
        forEachOrderedZipEntry(
            channel, directory.entries, ZipStructure.Entry::name, ZipStructure.Entry::directory, check,
        ) { entry, path ->
            if (entry.expanded > remaining) zipFailure(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
            remaining -= entry.expanded
            entries.add(IndexedCbzEntry(digest.pageId(path), entry))
        }
        check()
        requireZip(channel.size() == length)
        return CbzArchiveIndex(length, entries)
    }

    fun openPage(channel: FileChannel, entry: IndexedCbzEntry, check: () -> Unit): VerifiedCbzPage {
        requireZip(!entry.structure.directory)
        if (entry.structure.expanded > limits.imageBytes) zipFailure(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
        val input = openStreamingEntry(channel, entry.structure, check)
        try {
            requireZip(input.nextEntry != null)
            return VerifiedCbzPage(input, entry.structure, check)
        } catch (failure: Throwable) {
            input.close()
            throw failure
        }
    }

    /** Probes signatures using bounded output windows, without driving the decoder to EOF. */
    fun identifyPages(
        channel: FileChannel,
        index: CbzArchiveIndex,
        check: () -> Unit,
        onProbe: ((CbzPageProbe) -> Unit)? = null,
    ): List<CbzPageDescriptor> {
        val pages = ArrayList<CbzPageDescriptor>()
        val compressed = ByteArray(8192)
        for ((id, entry) in index.entries) {
            check()
            if (entry.directory || entry.expanded == 0L) continue
            val prefix = ByteArray(minOf(entry.expanded, ZIP_ENTRY_PREFIX_BYTES.toLong()).toInt())
            val packedRead = if (entry.method == ZIP_STORED) {
                channel.readZipFully(entry.dataOffset, ByteBuffer.wrap(prefix))
                prefix.size.toLong()
            } else {
                inflatePrefix(channel, entry, prefix, compressed, check)
            }
            check()
            onProbe?.invoke(CbzPageProbe(id, prefix.size, packedRead))
            identifyCbzPageFormat(prefix)?.let { pages.add(CbzPageDescriptor(id, it)) }
        }
        check()
        requireZip(channel.size() == index.length)
        if (pages.isEmpty()) zipFailure(CbzImportError.NO_SUPPORTED_IMAGES)
        return pages
    }

    private fun inflatePrefix(
        channel: FileChannel,
        entry: ZipStructure.Entry,
        prefix: ByteArray,
        compressed: ByteArray,
        check: () -> Unit,
    ): Long {
        val inflater = Inflater(true)
        var expanded = 0
        var packed = 0L
        try {
            while (expanded < prefix.size) {
                check()
                if (inflater.needsInput() && packed < entry.packed) {
                    val count = minOf(compressed.size.toLong(), entry.packed - packed).toInt()
                    channel.readZipFully(entry.dataOffset + packed, ByteBuffer.wrap(compressed, 0, count))
                    packed += count
                    inflater.setInput(compressed, 0, count)
                }
                val produced = inflater.inflate(prefix, expanded, prefix.size - expanded)
                expanded += produced
                if (inflater.finished()) {
                    requireZip(expanded.toLong() == entry.expanded && inflater.bytesRead == entry.packed)
                    break
                }
                requireZip(produced > 0 || (inflater.needsInput() && packed < entry.packed))
            }
        } catch (_: DataFormatException) {
            zipFailure(CbzImportError.CORRUPT_ARCHIVE)
        } finally {
            inflater.end()
        }
        return packed
    }

    fun readPages(channel: FileChannel, checkCancellation: () -> Unit): List<CbzPageDescriptor> =
        readPages(channel, null, checkCancellation)

    fun readPages(file: File, checkCancellation: () -> Unit): List<CbzPageDescriptor> = file.inputStream().use {
        readPages(it.channel, file, checkCancellation)
    }

    fun <T> withFirstPage(channel: FileChannel, check: () -> Unit, read: (FirstCbzPage) -> T): T {
        val validated = validate(channel, { identifyCbzPageFormat(it) != null }, check)
        var first: FirstCbzPage? = null
        collectCbzPages(channel, validated, check) { entry, descriptor ->
            val structure = entry.structure
            first = FirstCbzPage(descriptor, structure.crc, structure.expanded) {
                val input = openStreamingEntry(channel, structure, check)
                try {
                    requireZip(input.nextEntry != null)
                    BoundedPageStream(input, structure.expanded, check)
                } catch (error: Throwable) {
                    input.close()
                    throw error
                }
            }
        }
        return read(checkNotNull(first))
    }

    private fun readPages(
        channel: FileChannel,
        privateCopy: File?,
        checkCancellation: () -> Unit,
    ): List<CbzPageDescriptor> {
        val entries = validate(channel, privateCopy, { identifyCbzPageFormat(it) != null }, checkCancellation)
        return collectCbzPages(channel, entries, checkCancellation)
    }

    fun validate(
        channel: FileChannel,
        isImage: (ByteArray) -> Boolean,
        checkCancellation: () -> Unit,
    ): List<ValidatedZipEntry> = validate(channel, null, isImage, checkCancellation)

    /** The file must be the private, operation-owned copy created by the document adapter. */
    fun validate(
        file: File,
        isImage: (ByteArray) -> Boolean,
        checkCancellation: () -> Unit,
    ): List<ValidatedZipEntry> = file.inputStream().use {
        validate(it.channel, file, isImage, checkCancellation)
    }

    private fun validate(
        channel: FileChannel,
        privateCopy: File?,
        isImage: (ByteArray) -> Boolean,
        checkCancellation: () -> Unit,
    ): List<ValidatedZipEntry> {
        val length = channel.size()
        val directory = readStructure(channel, length, checkCancellation)
        val scan = EntryScan(limits, isImage, checkCancellation)
        val results = try {
            val indexed = privateCopy?.let { openBoundedIndex(it, directory) }
            indexed?.use { zip ->
                val indexedEntries = compatibleEntries(zip, channel, directory, checkCancellation)
                indexedEntries?.mapIndexed { index, entry ->
                    val input = zip.getInputStream(entry) ?: zipFailure(CbzImportError.CORRUPT_ARCHIVE)
                    input.use { scan.read(directory.entries[index], it) }
                }
            } ?: directory.entries.map { entry ->
                checkCancellation()
                openStreamingEntry(channel, entry, checkCancellation).use { input ->
                    requireZip(input.nextEntry != null)
                    scan.read(entry, input)
                }
            }
        } catch (_: EOFException) {
            checkCancellation()
            zipFailure(CbzImportError.CORRUPT_ARCHIVE)
        } catch (_: ZipException) {
            checkCancellation()
            zipFailure(CbzImportError.CORRUPT_ARCHIVE)
        }
        checkCancellation()
        requireZip(channel.size() == length)
        return results
    }

    private fun readStructure(channel: FileChannel, length: Long, check: () -> Unit): ZipStructure.Directory {
        check()
        if (length == 0L) zipFailure(CbzImportError.EMPTY_FILE)
        if (length > limits.archiveBytes) zipFailure(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
        val directory = ZipStructure(channel, length, limits.entries, check).read()
        if (directory.entries.isEmpty()) zipFailure(CbzImportError.EMPTY_ARCHIVE)
        return directory
    }

    private fun openBoundedIndex(file: File, directory: ZipStructure.Directory): ZipFile? {
        // ZipFile can allocate the whole central directory and does not expose the
        // number of compressed bytes consumed by its DEFLATE stream.
        // It also finds its own EOCD, which can differ when the comment contains a signature.
        if (!directory.unambiguousEnd || directory.size > MAX_INDEXED_DIRECTORY_BYTES ||
            directory.entries.any { it.method != ZIP_STORED || !it.zipFileNameCompatible }
        ) {
            return null
        }
        return try {
            ZipFile(file)
        } catch (_: ZipException) {
            // Some platform readers reject valid legacy name encodings or ZIP64 variants.
            null
        }
    }

    private fun compatibleEntries(
        zip: ZipFile,
        channel: FileChannel,
        directory: ZipStructure.Directory,
        checkCancellation: () -> Unit,
    ): List<ZipEntry>? {
        if (zip.size() != directory.entries.size) return null
        val enumeration = zip.entries()
        val names = HashSet<String>()
        return directory.entries.map { entry ->
            checkCancellation()
            if (!enumeration.hasMoreElements()) return null
            val candidate = enumeration.nextElement()
            if (candidate.method != ZIP_STORED || candidate.size != entry.expanded ||
                candidate.compressedSize != entry.packed || candidate.crc != entry.crc ||
                !entry.name.readBytes(channel).contentEquals(candidate.name.toByteArray(Charsets.UTF_8)) ||
                !names.add(candidate.name)
            ) {
                return null
            }
            candidate
        }
    }

    private fun openStreamingEntry(
        channel: FileChannel,
        entry: ZipStructure.Entry,
        checkCancellation: () -> Unit,
    ): ZipInputStream {
        // Present only the preflighted payload to the platform decoder. A normalized
        // local header supplies the verified CEN sizes and CRC without a descriptor.
        val wide = entry.expanded >= ZIP_UINT_MAX || entry.packed >= ZIP_UINT_MAX
        val extraLength = if (wide) 20 else 0
        val header = ByteBuffer.allocate(31 + extraLength).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(ZIP_LOCAL_SIGNATURE.toInt())
        header.putShort(if (wide) 45 else 20)
        header.putShort(0)
        header.putShort(entry.method.toShort())
        header.putInt(0)
        header.putInt(entry.crc.toInt())
        header.putInt(if (wide) -1 else entry.packed.toInt())
        header.putInt(if (wide) -1 else entry.expanded.toInt())
        header.putShort(1)
        header.putShort(extraLength.toShort())
        header.put('x'.code.toByte())
        if (wide) {
            header.putShort(1)
            header.putShort(16)
            header.putLong(entry.expanded)
            header.putLong(entry.packed)
        }
        val payload = ZipPayloadInputStream(channel, entry.dataOffset, entry.packed, checkCancellation)
        // Avoid a positioned file read for each small read made by the ZIP decoder.
        val bufferedPayload = BufferedInputStream(payload, entry.packed.coerceIn(512, 64 * 1024).toInt())
        return ZipInputStream(
            SequenceInputStream(
                ByteArrayInputStream(header.array()),
                bufferedPayload,
            ),
        )
    }

    private companion object {
        // Implementation threshold for the fast path, not an archive acceptance limit.
        const val MAX_INDEXED_DIRECTORY_BYTES = 1024L * 1024
    }
}

private class EntryScan(
    private val limits: ZipValidationLimits,
    private val isImage: (ByteArray) -> Boolean,
    private val checkCancellation: () -> Unit,
) {
    private val buffer = ByteArray(64 * 1024)
    private var total = 0L

    fun read(entry: ZipStructure.Entry, input: InputStream): ValidatedZipEntry {
        if (entry.expanded > limits.expandedBytes - total) {
            zipFailure(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
        }
        val crc = CRC32()
        var expanded = 0L
        var image = false

        fun readBlock(offset: Int, requested: Int): Int {
            checkCancellation()
            val entryRemaining = if (image) limits.imageBytes - expanded else Long.MAX_VALUE
            val remaining = minOf(limits.expandedBytes - total, entryRemaining)
            val size = if (remaining < requested) remaining.toInt() + 1 else requested
            val count = input.read(buffer, offset, size)
            checkCancellation()
            if (count < 0) return count
            requireZip(count > 0)
            if (count > remaining) zipFailure(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
            expanded += count
            total += count
            crc.update(buffer, offset, count)
            return count
        }

        var prefixSize = 0
        var reachedEnd = false
        while (prefixSize < ZIP_ENTRY_PREFIX_BYTES) {
            val count = readBlock(prefixSize, ZIP_ENTRY_PREFIX_BYTES - prefixSize)
            if (count < 0) {
                reachedEnd = true
                break
            }
            prefixSize += count
        }
        val prefix = buffer.copyOf(prefixSize)
        image = !entry.directory && isImage(prefix.copyOf())
        if (image && (expanded > limits.imageBytes || entry.expanded > limits.imageBytes)) {
            zipFailure(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
        }
        if (!reachedEnd) {
            while (readBlock(0, buffer.size) >= 0) {
                // Bytes are counted and checksummed as each block arrives.
            }
        }
        requireZip(expanded == entry.expanded && crc.value == entry.crc)
        return ValidatedZipEntry(entry.name, entry.directory, prefix, entry)
    }
}

private class BoundedPageStream(
    private val input: InputStream,
    private var remaining: Long,
    private val check: () -> Unit,
) : InputStream() {
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        check()
        val count = input.read(bytes, offset, minOf(length.toLong(), remaining + 1).toInt())
        check()
        if (length == 0) return count
        if (count < 0) requireZip(remaining == 0L)
        if (count > remaining) zipFailure(CbzImportError.CORRUPT_ARCHIVE)
        if (count > 0) remaining -= count
        return count
    }

    override fun close() = input.close()
}

private class ZipPayloadInputStream(
    private val channel: FileChannel,
    private var offset: Long,
    private var remaining: Long,
    private val checkCancellation: () -> Unit,
) : InputStream() {
    private val singleByte = ByteArray(1)

    override fun read(): Int =
        if (read(singleByte, 0, 1) < 0) -1 else singleByte[0].toInt() and 0xff

    override fun read(bytes: ByteArray, start: Int, length: Int): Int {
        checkCancellation()
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = minOf(length.toLong(), remaining).toInt()
        channel.readZipFully(offset, ByteBuffer.wrap(bytes, start, count))
        checkCancellation()
        offset += count
        remaining -= count
        return count
    }
}

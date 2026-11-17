package com.agustin.sumi.data.local.archive

import com.agustin.sumi.domain.model.CbzImportError
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException

internal class ZipValidationException(val error: CbzImportError) : IOException(error.name)

internal const val ZIP_STORED = 0
internal const val ZIP_DEFLATED = 8
internal const val ZIP_LOCAL_SIGNATURE = 0x04034b50L
internal const val ZIP_UINT_MAX = 0xffffffffL

internal data class ZipEntryName(val offset: Long, val length: Int, val utf8: Boolean) {
    fun readBytes(channel: FileChannel): ByteArray = channel.readZipBytes(offset, length).array()
}

internal class ZipStructure(
    private val channel: FileChannel,
    private val length: Long,
    private val maxEntries: Int,
    private val checkCancellation: () -> Unit,
) {
    class Directory(val size: Long, val entries: List<Entry>, val unambiguousEnd: Boolean)

    class Entry(
        val name: ZipEntryName,
        val directory: Boolean,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val packed: Long,
        val expanded: Long,
        val localOffset: Long,
        val zipFileNameCompatible: Boolean,
    ) {
        var dataOffset: Long = 0
    }

    private class End(val offset: Long, val size: Long, val count: Long)
    private class Sizes(val expanded: Long, val packed: Long, val offset: Long, val disk: Long)

    fun read(): Directory {
        checkCancellation()
        val tailSize = minOf(length, (END_SIZE + USHORT_MAX).toLong()).toInt()
        val tailStart = length - tailSize
        val tail = channel.readZipBytes(tailStart, tailSize)
        val lastIndex = tailSize - END_SIZE
        var failure: ZipValidationException? = null

        // A coherent earlier record owns the remaining bytes as its comment.
        for (index in 0..lastIndex) {
            if (!tail.endsArchiveAt(index)) continue
            checkCancellation()
            val end = try {
                readEnd(tail, index, tailStart + index)
            } catch (error: ZipValidationException) {
                if (failure == null) failure = error
                continue
            }
            val entries = try {
                readDirectory(end)
            } catch (error: ZipValidationException) {
                // A coherent directory's policy errors still reject the archive.
                if (error.error != CbzImportError.CORRUPT_ARCHIVE) throw error
                failure = error
                continue
            }
            val unambiguousEnd = ((index + 1)..(tailSize - 4)).none { tail.u32(it) == END_SIGNATURE }
            return Directory(end.size, entries, unambiguousEnd)
        }

        failure?.let { throw it }
        val first = if (length >= 4) channel.readZipBytes(0, 4).u32(0) else 0L
        zipFailure(
            if (first == ZIP_LOCAL_SIGNATURE || first == END_SIGNATURE || first == ZIP64_END_SIGNATURE) {
                CbzImportError.CORRUPT_ARCHIVE
            } else {
                CbzImportError.INVALID_ARCHIVE
            },
        )
    }

    private fun readDirectory(end: End): List<Entry> {
        if (end.size != 0L) {
            requireZip(end.size >= CENTRAL_HEADER_SIZE)
            requireZip(channel.readZipBytes(end.offset, 4).u32(0) == CENTRAL_SIGNATURE)
        }
        if (end.count > maxEntries) zipFailure(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
        requireZip(end.count <= end.size / CENTRAL_HEADER_SIZE)
        val directoryEnd = end.offset + end.size
        val entries = ArrayList<Entry>()
        val utf8 = Charsets.UTF_8.newDecoder()
        var cursor = end.offset
        while (cursor < directoryEnd) {
            checkCancellation()
            if (entries.size == maxEntries) zipFailure(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
            requireZipRange(cursor, CENTRAL_HEADER_SIZE.toLong(), directoryEnd)
            val header = channel.readZipBytes(cursor, CENTRAL_HEADER_SIZE)
            requireZip(header.u32(0) == CENTRAL_SIGNATURE)
            val flags = header.u16(8)
            val method = header.u16(10)
            checkMethodAndFlags(method, flags)
            val nameLength = header.u16(28)
            val extraLength = header.u16(30)
            val commentLength = header.u16(32)
            val nameOffset = cursor + CENTRAL_HEADER_SIZE
            requireZipRange(nameOffset, (nameLength + extraLength + commentLength).toLong(), directoryEnd)
            val name = ZipEntryName(nameOffset, nameLength, flags and UTF8 != 0)
            val nameBytes = name.readBytes(channel)
            checkName(nameBytes)
            if (name.utf8) {
                try {
                    utf8.decode(ByteBuffer.wrap(nameBytes))
                } catch (_: CharacterCodingException) {
                    zipFailure(CbzImportError.CORRUPT_ARCHIVE)
                }
            }
            val extra = channel.readZipBytes(nameOffset + nameLength, extraLength)
            val sizes = resolveSizes(header.u32(24), header.u32(20), header.u32(42), header.u16(34), extra)
            if (sizes.disk != 0L) zipFailure(CbzImportError.UNSUPPORTED_ARCHIVE)
            val directory = nameBytes.last() == '/'.code.toByte()
            if (directory) requireZip(sizes.expanded == 0L)
            if (method == ZIP_STORED) requireZip(sizes.packed == sizes.expanded)
            entries.add(
                Entry(
                    name, directory, method, flags, header.u32(16), sizes.packed, sizes.expanded,
                    sizes.offset, name.utf8 || nameBytes.all { it >= 0 },
                ),
            )
            cursor = nameOffset + nameLength + extraLength + commentLength
        }
        requireZip(entries.size.toLong() == end.count)
        checkLocalRecords(entries, end.offset)
        return entries
    }

    private fun ByteBuffer.endsArchiveAt(index: Int): Boolean =
        u32(index) == END_SIGNATURE && index + END_SIZE + u16(index + 20) == limit()

    private fun readEnd(tail: ByteBuffer, index: Int, endOffset: Long): End {
        val disk = tail.u16(index + 4)
        val centralDisk = tail.u16(index + 6)
        if (disk !in SINGLE_DISK_FIELDS || centralDisk !in SINGLE_DISK_FIELDS) {
            zipFailure(CbzImportError.UNSUPPORTED_ARCHIVE)
        }
        val diskCount = tail.u16(index + 8).toLong()
        val count = tail.u16(index + 10).toLong()
        val size = tail.u32(index + 12)
        val offset = tail.u32(index + 16)
        val zip64Required = disk == USHORT_MAX || centralDisk == USHORT_MAX ||
            diskCount == USHORT_MAX.toLong() || count == USHORT_MAX.toLong() ||
            size == ZIP_UINT_MAX || offset == ZIP_UINT_MAX

        if (!zip64Required && offset + size == endOffset) {
            requireZip(diskCount == count)
            return End(offset, size, count)
        }

        val locatorOffset = endOffset - ZIP64_LOCATOR_SIZE
        requireZip(locatorOffset >= 0)
        val locator = channel.readZipBytes(locatorOffset, ZIP64_LOCATOR_SIZE)
        requireZip(locator.u32(0) == ZIP64_LOCATOR_SIGNATURE)
        if (locator.u32(4) != 0L || locator.u32(16) != 1L) zipFailure(CbzImportError.UNSUPPORTED_ARCHIVE)
        val recordOffset = locator.u64(8)
        requireZipRange(recordOffset, ZIP64_END_SIZE.toLong(), locatorOffset)
        val record = channel.readZipBytes(recordOffset, ZIP64_END_SIZE)
        requireZip(record.u32(0) == ZIP64_END_SIGNATURE)
        requireZip(record.u64(4) == locatorOffset - recordOffset - 12)
        if (record.u32(16) != 0L || record.u32(20) != 0L) zipFailure(CbzImportError.UNSUPPORTED_ARCHIVE)
        val zip64Count = record.u64(32)
        val zip64Size = record.u64(40)
        val zip64Offset = record.u64(48)
        requireZip(record.u64(24) == zip64Count)
        requireZip(diskCount == USHORT_MAX.toLong() || diskCount == zip64Count)
        requireZip(count == USHORT_MAX.toLong() || count == zip64Count)
        requireZip(size == ZIP_UINT_MAX || size == zip64Size)
        requireZip(offset == ZIP_UINT_MAX || offset == zip64Offset)
        requireZipRange(zip64Offset, zip64Size, recordOffset)
        requireZip(zip64Offset + zip64Size == recordOffset)
        return End(zip64Offset, zip64Size, zip64Count)
    }

    private fun checkLocalRecords(entries: List<Entry>, centralStart: Long) {
        // The local area must be accounted for exactly, regardless of central directory order.
        val byOffset = entries.sortedBy { it.localOffset }
        requireZip((byOffset.firstOrNull()?.localOffset ?: centralStart) == 0L)
        byOffset.forEachIndexed { index, entry ->
            checkCancellation()
            val next = byOffset.getOrNull(index + 1)?.localOffset ?: centralStart
            requireZip(next <= centralStart)
            val offset = entry.localOffset
            requireZipRange(offset, LOCAL_HEADER_SIZE.toLong(), next)
            val local = channel.readZipBytes(offset, LOCAL_HEADER_SIZE)
            requireZip(local.u32(0) == ZIP_LOCAL_SIGNATURE)
            requireZip(local.u16(6) == entry.flags && local.u16(8) == entry.method)
            val nameLength = local.u16(26)
            val extraLength = local.u16(28)
            val nameOffset = offset + LOCAL_HEADER_SIZE
            requireZipRange(nameOffset, (nameLength + extraLength).toLong(), next)
            requireZip(nameLength == entry.name.length)
            val name = channel.readZipBytes(nameOffset, nameLength).array()
            requireZip(name.contentEquals(entry.name.readBytes(channel)))
            val extra = channel.readZipBytes(nameOffset + nameLength, extraLength)
            val sizes = resolveSizes(local.u32(22), local.u32(18), 0, 0, extra)
            val descriptor = entry.flags and DESCRIPTOR != 0
            requireZip(local.u32(14) == entry.crc || (descriptor && local.u32(14) == 0L))
            requireZip(sizes.packed == entry.packed || (descriptor && sizes.packed == 0L))
            requireZip(sizes.expanded == entry.expanded || (descriptor && sizes.expanded == 0L))
            entry.dataOffset = nameOffset + nameLength + extraLength
            requireZipRange(entry.dataOffset, entry.packed, next)
            val dataEnd = entry.dataOffset + entry.packed
            if (descriptor) checkDescriptor(entry, dataEnd, next) else requireZip(dataEnd == next)
        }
    }

    private fun checkDescriptor(entry: Entry, start: Long, end: Long) {
        val length = end - start
        requireZip(length == 12L || length == 16L || length == 20L || length == 24L)
        val record = channel.readZipBytes(start, length.toInt())
        val signed = length == 16L || length == 24L
        val wide = length >= 20
        requireZip(wide || (entry.packed < ZIP_UINT_MAX && entry.expanded < ZIP_UINT_MAX))
        if (signed) requireZip(record.u32(0) == DESCRIPTOR_SIGNATURE)
        val crcOffset = if (signed) 4 else 0
        requireZip(record.u32(crcOffset) == entry.crc)
        val packed = if (wide) record.u64(crcOffset + 4) else record.u32(crcOffset + 4)
        val expanded = if (wide) record.u64(crcOffset + 12) else record.u32(crcOffset + 8)
        requireZip(packed == entry.packed && expanded == entry.expanded)
    }

    private fun resolveSizes(expanded: Long, packed: Long, offset: Long, disk: Int, extra: ByteBuffer): Sizes {
        var zip64: ByteBuffer? = null
        var cursor = 0
        while (cursor < extra.limit()) {
            checkCancellation()
            requireZip(extra.limit() - cursor >= 4)
            val size = extra.u16(cursor + 2)
            requireZip(size <= extra.limit() - cursor - 4)
            if (extra.u16(cursor) == ZIP64_EXTRA) {
                requireZip(zip64 == null && size > 0)
                extra.position(cursor + 4)
                zip64 = extra.slice().order(ByteOrder.LITTLE_ENDIAN)
                zip64.limit(size)
            }
            cursor += size + 4
        }
        fun wide(field: Long): Long {
            if (field != ZIP_UINT_MAX) return field
            val data = zip64 ?: zipFailure(CbzImportError.CORRUPT_ARCHIVE)
            requireZip(data.remaining() >= 8)
            val value = data.u64(data.position())
            data.position(data.position() + 8)
            return value
        }
        val expandedSize = wide(expanded)
        val packedSize = wide(packed)
        val localOffset = wide(offset)
        val diskNumber = if (disk == USHORT_MAX) {
            val data = zip64 ?: zipFailure(CbzImportError.CORRUPT_ARCHIVE)
            requireZip(data.remaining() >= 4)
            val value = data.u32(data.position())
            data.position(data.position() + 4)
            value
        } else {
            disk.toLong()
        }
        requireZip(zip64?.hasRemaining() != true)
        return Sizes(expandedSize, packedSize, localOffset, diskNumber)
    }

    private fun checkMethodAndFlags(method: Int, flags: Int) {
        // Standard methods and their descriptor, UTF-8 and DEFLATE option bits.
        val supported = UTF8 or DESCRIPTOR or (if (method == ZIP_DEFLATED) 0x06 else 0)
        if ((method != ZIP_STORED && method != ZIP_DEFLATED) || flags and supported.inv() != 0) {
            zipFailure(CbzImportError.UNSUPPORTED_ARCHIVE)
        }
    }

    private fun checkName(name: ByteArray) {
        requireZip(name.isNotEmpty())
        val slash = '/'.code.toByte()
        val dot = '.'.code.toByte()
        val initial = name[0].toInt()
        val drive = name.size >= 2 && name[1] == ':'.code.toByte() &&
            (initial in 'a'.code..'z'.code || initial in 'A'.code..'Z'.code)
        if (name[0] == slash || drive || name.any { it == 0.toByte() || it == '\\'.code.toByte() }) {
            zipFailure(CbzImportError.UNSAFE_ENTRY_PATH)
        }
        var segment = 0
        for (index in 0..name.size) {
            if (index < name.size && name[index] != slash) continue
            if (index - segment == 2 && name[segment] == dot && name[segment + 1] == dot) {
                zipFailure(CbzImportError.UNSAFE_ENTRY_PATH)
            }
            segment = index + 1
        }
    }

    private companion object {
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val END_SIGNATURE = 0x06054b50L
        const val DESCRIPTOR_SIGNATURE = 0x08074b50L
        const val ZIP64_END_SIGNATURE = 0x06064b50L
        const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        const val ZIP64_EXTRA = 1
        const val END_SIZE = 22
        const val ZIP64_END_SIZE = 56
        const val ZIP64_LOCATOR_SIZE = 20
        const val CENTRAL_HEADER_SIZE = 46
        const val LOCAL_HEADER_SIZE = 30
        const val USHORT_MAX = 65535
        const val DESCRIPTOR = 1 shl 3
        const val UTF8 = 1 shl 11
        val SINGLE_DISK_FIELDS = setOf(0, USHORT_MAX)
    }
}

internal fun FileChannel.readZipBytes(offset: Long, length: Int): ByteBuffer {
    val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
    readZipFully(offset, buffer)
    buffer.rewind()
    return buffer
}

internal fun FileChannel.readZipFully(offset: Long, buffer: ByteBuffer) {
    var cursor = offset
    while (buffer.hasRemaining()) {
        val count = read(buffer, cursor)
        requireZip(count > 0)
        cursor += count
    }
}

private fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and 0xffff
private fun ByteBuffer.u32(index: Int): Long = getInt(index).toLong() and ZIP_UINT_MAX
private fun ByteBuffer.u64(index: Int): Long = getLong(index).also { requireZip(it >= 0) }

private fun requireZipRange(offset: Long, size: Long, end: Long) {
    requireZip(offset >= 0 && offset <= end && size >= 0 && size <= end - offset)
}

internal fun requireZip(condition: Boolean) {
    if (!condition) zipFailure(CbzImportError.CORRUPT_ARCHIVE)
}

internal fun zipFailure(error: CbzImportError): Nothing = throw ZipValidationException(error)

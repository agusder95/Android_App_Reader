package com.agustin.sumi.data.local.archive

import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzPageDescriptor
import com.agustin.sumi.domain.model.CbzPageFormat
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.CbzPagePathOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

private val jpegSignature = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val riffSignature = "RIFF".encodeToByteArray()
private val webpSignature = "WEBP".encodeToByteArray()

internal fun identifyCbzPageFormat(prefix: ByteArray): CbzPageFormat? = when {
    prefix.matchesAt(jpegSignature) -> CbzPageFormat.JPEG
    prefix.matchesAt(pngSignature) -> CbzPageFormat.PNG
    prefix.matchesAt(riffSignature) && prefix.matchesAt(webpSignature, 8) -> CbzPageFormat.WEBP
    else -> null
}

private fun ByteArray.matchesAt(signature: ByteArray, offset: Int = 0): Boolean =
    size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }

internal fun collectCbzPages(
    channel: FileChannel,
    entries: List<ValidatedZipEntry>,
    checkCancellation: () -> Unit,
    onFirstPage: ((ValidatedZipEntry, CbzPageDescriptor) -> Unit)? = null,
): List<CbzPageDescriptor> {
    val paths = EntryPaths(channel)
    val ordered = entries.sortedWith { left, right ->
        checkCancellation()
        CbzPagePathOrder.compare(paths.get(left.name), paths.get(right.name))
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val pages = ArrayList<CbzPageDescriptor>()
    var previousPath: String? = null
    for (entry in ordered) {
        checkCancellation()
        val path = paths.get(entry.name)
        if (path.isEmpty() && !entry.directory) zipFailure(CbzImportError.UNSAFE_ENTRY_PATH)
        if (path == previousPath) zipFailure(CbzImportError.DUPLICATE_ENTRY_PATH)
        previousPath = path
        if (entry.directory) continue
        val format = identifyCbzPageFormat(entry.prefix) ?: continue
        val id = CbzPageId(digest.digest(path.encodeToByteArray()).toHexString())
        val page = CbzPageDescriptor(id, format)
        if (pages.isEmpty()) onFirstPage?.invoke(entry, page)
        pages.add(page)
    }
    checkCancellation()
    if (pages.isEmpty()) zipFailure(CbzImportError.NO_SUPPORTED_IMAGES)
    return pages
}

private class EntryPaths(private val channel: FileChannel) {
    // Retain only a small working set of decoded names during sorting.
    private val cache = LinkedHashMap<ZipEntryName, String>(32, 0.75f, true)

    fun get(name: ZipEntryName): String = cache.getOrPut(name) {
        if (cache.size == 32) cache.remove(cache.keys.first())
        val bytes = name.readBytes(channel)
        val path = normalizePath(if (name.utf8) bytes.decodeToString() else decodeCp437(bytes))
        if (path.length >= 2 && path[1] == ':' && (path[0] in 'a'..'z' || path[0] in 'A'..'Z')) {
            zipFailure(CbzImportError.UNSAFE_ENTRY_PATH)
        }
        path
    }

    private fun normalizePath(path: String): String = buildString(path.length) {
        var start = 0
        while (start < path.length) {
            val slash = path.indexOf('/', start)
            val end = if (slash < 0) path.length else slash
            if (end > start && (end - start != 1 || path[start] != '.')) {
                if (isNotEmpty()) append('/')
                append(path, start, end)
            }
            start = end + 1
        }
    }
}

// ZIP's legacy filename encoding maps the upper half of the byte range to CP437.
private const val CP437_HIGH =
    "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒ" +
    "áíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
    "└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
    "αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u00a0"

private fun decodeCp437(bytes: ByteArray): String = buildString(bytes.size) {
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        append(if (value < 128) value.toChar() else CP437_HIGH[value - 128])
    }
}

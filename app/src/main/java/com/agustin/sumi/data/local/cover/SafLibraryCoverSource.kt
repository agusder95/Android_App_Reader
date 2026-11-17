package com.agustin.sumi.data.local.cover

import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import com.agustin.sumi.data.local.archive.CbzArchiveReader
import com.agustin.sumi.data.local.archive.ZipValidationException
import com.agustin.sumi.data.local.document.OpenedCbzDocument
import com.agustin.sumi.data.local.document.SafDocumentSource
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.repository.LibraryCoverSource
import com.agustin.sumi.domain.usecase.CbzWorkGate
import java.io.IOException
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class SafLibraryCoverSource(
    private val findEntry: suspend (LibraryEntryId) -> LibraryEntry?,
    private val documents: SafDocumentSource,
    private val work: CbzWorkGate,
    private val cache: CoverCache,
    private val decoder: ThumbnailDecoder = ThumbnailDecoder(),
    private val isRemoving: (LibraryEntryId) -> Boolean = { false },
) : LibraryCoverSource<Bitmap> {
    private val archives = CbzArchiveReader()
    private var recovered = false

    override suspend fun load(id: LibraryEntryId): Bitmap? = work.run {
        withContext(Dispatchers.IO) {
            try {
                if (!recovered) {
                    cache.recover()
                    recovered = true
                }
                if (isRemoving(id)) return@withContext null
                val entry = findEntry(id) ?: return@withContext null
                val context = currentCoroutineContext()
                val candidate = prepare(entry, context::ensureActive) ?: return@withContext null
                if (isRemoving(id) || findEntry(id) != entry) return@withContext null
                context.ensureActive()
                if (candidate.generated) cache.write(candidate.key, candidate.bitmap, context::ensureActive)
                candidate.bitmap.takeUnless { isRemoving(id) }
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                null
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                null
            }
        }
    }

    private suspend fun prepare(entry: LibraryEntry, check: () -> Unit): Candidate? {
        val source = entry.source.reference
        val direct = documents.withDocument(source) { opened ->
            when (opened) {
                is OpenedCbzDocument.Direct -> try {
                    Prepared(read(entry, opened.channel, check))
                } catch (failure: IOException) {
                    if (failure is ZipValidationException) throw failure
                    null
                }
                is OpenedCbzDocument.Temporary -> Prepared(opened.file.inputStream().use { read(entry, it.channel, check) })
            }
        }
        if (direct != null) return direct.candidate
        check()
        return documents.withDocument(source, allowDirectAccess = false) { opened ->
            kotlin.check(opened is OpenedCbzDocument.Temporary)
            opened.file.inputStream().use { read(entry, it.channel, check) }
        }
    }

    private fun read(entry: LibraryEntry, channel: FileChannel, check: () -> Unit): Candidate? =
        archives.withFirstPage(channel, check) { page ->
            val fingerprint = "${entry.id.value}\u0000${entry.source.reference.value}\u0000" +
                "${page.descriptor.id.value}:${page.crc}:${page.size}:$COVER_EDGE"
            val digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.encodeToByteArray()).toHexString()
            val key = "${entry.id.value}-$digest"
            val cached = cache.read(key)
            if (cached != null) Candidate(key, cached, false)
            else decoder.decode(page.open, check)?.let { Candidate(key, it, true) }
        }

    private data class Candidate(val key: String, val bitmap: Bitmap, val generated: Boolean)
    private data class Prepared(val candidate: Candidate?)
}

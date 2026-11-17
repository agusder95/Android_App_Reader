package com.agustin.sumi.data.repository

import android.database.sqlite.SQLiteException
import com.agustin.sumi.data.local.archive.CbzArchiveIndex
import com.agustin.sumi.data.local.archive.CbzArchiveReader
import com.agustin.sumi.data.local.archive.ZipValidationException
import com.agustin.sumi.data.local.document.DocumentAccessException
import com.agustin.sumi.data.local.document.DocumentRevision
import com.agustin.sumi.data.local.document.OpenedCbzDocument
import com.agustin.sumi.data.local.document.SafDocumentSource
import com.agustin.sumi.data.local.document.TemporaryCbzStore
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzPageDescriptor
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderOptions
import com.agustin.sumi.domain.model.ReaderPage
import com.agustin.sumi.domain.model.ReaderSession
import com.agustin.sumi.domain.model.ReaderSessionId
import com.agustin.sumi.domain.repository.DecodedReaderPage
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderPageInput
import com.agustin.sumi.domain.repository.ReaderRepository
import com.agustin.sumi.domain.repository.ReaderResult
import com.agustin.sumi.domain.usecase.CbzWorkGate
import java.io.EOFException
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

internal class LocalReaderRepository(
    private val findEntry: suspend (LibraryEntryId) -> LibraryEntry?,
    private val documents: SafDocumentSource,
    private val work: CbzWorkGate,
    private val archives: CbzArchiveReader = CbzArchiveReader(),
) : ReaderRepository {
    private val sessions = ConcurrentHashMap<ReaderSessionId, SessionState>()

    override suspend fun openSession(bookId: LibraryEntryId, direction: ReaderDirection): ReaderResult<ReaderSession> =
        result {
            work.run {
                val caller = currentCoroutineContext()
                val source = (findEntry(bookId) ?: reject(ReaderError.ENTRY_NOT_FOUND)).source
                val prepared = retryDirectFailure { allowDirect ->
                    documents.prepareReaderDocument(source.reference, allowDirect) { document ->
                        document.withChannel { channel ->
                            preflight(document) {
                                val index = archives.index(channel, caller::ensureActive)
                                IndexedPages(index, archives.identifyPages(channel, index, caller::ensureActive))
                            }
                        }
                    }
                }
                try {
                    val revision = if (prepared.copy != null) documents.readerRevision(source.reference) else null
                    if (revision?.size?.let { it != prepared.value.index.length } == true) {
                        reject(ReaderError.INVALID_ARCHIVE)
                    }
                    val session = ReaderSession(
                        ReaderSessionId(UUID.randomUUID().toString()), bookId,
                        Collections.unmodifiableList(prepared.value.pages.mapIndexed { index, page -> ReaderPage(page, index) }),
                        ReaderOptions(direction),
                    )
                    caller.ensureActive()
                    sessions[session.id] = SessionState(session, source, prepared.value.index, prepared.copy, revision)
                    session
                } catch (error: Throwable) {
                    withContext(NonCancellable + Dispatchers.IO) { prepared.copy?.close() }
                    throw error
                }
            }
        }

    override suspend fun closeSession(session: ReaderSession) {
        val state = sessions[session.id]?.takeIf { it.session == session } ?: return
        val active = state.invalidate()
        withContext(NonCancellable + Dispatchers.IO) {
            active?.join()
            state.copy?.close()
            sessions.remove(session.id, state)
        }
    }

    override suspend fun <T> readPage(
        session: ReaderSession,
        pageId: CbzPageId,
        consume: suspend (ReaderPageInput) -> T,
    ): ReaderResult<DecodedReaderPage<T>> = result {
        val state = sessions[session.id]?.takeIf { it.session == session && it.isOpen() }
            ?: reject(ReaderError.PAGE_NOT_FOUND)
        val descriptor = state.session.pages.firstOrNull { it.id == pageId }?.descriptor
            ?: reject(ReaderError.PAGE_NOT_FOUND)
        work.run {
            withContext(Dispatchers.IO) {
                val caller = currentCoroutineContext()
                state.beginRead(caller.job)
                try {
                    val current = findEntry(session.bookId)?.source ?: reject(ReaderError.ENTRY_NOT_FOUND)
                    if (current != state.source) reject(ReaderError.SOURCE_UNAVAILABLE)
                    val copy = state.copy
                    if (copy != null) {
                        documents.verifyAvailable(current.reference)
                        val revision = documents.readerRevision(current.reference)
                        if (state.revision?.differsFrom(revision) == true ||
                            revision.size?.let { it != state.index.length } == true
                        ) reject(ReaderError.INVALID_ARCHIVE)
                        copy.file.inputStream().use { input ->
                            readVerified(input.channel, state.index, descriptor, caller::ensureActive, consume)
                        }
                    } else {
                        retryDirectFailure { allowDirect ->
                            documents.withDocument(current.reference, allowDirect) { document ->
                                document.withChannel { channel ->
                                    val currentIndex = preflight(document) { archives.index(channel, caller::ensureActive) }
                                    if (!state.index.matches(currentIndex)) reject(ReaderError.INVALID_ARCHIVE)
                                    readVerified(channel, currentIndex, descriptor, caller::ensureActive, consume)
                                }
                            }
                        }
                    }.also { caller.ensureActive() }
                } finally {
                    state.endRead()
                }
            }
        }.also { state.ensureOpen() }
    }.also { outcome ->
        if (outcome is ReaderResult.Failure && outcome.error in SESSION_INVALIDATING_ERRORS) closeSession(session)
    }

    private suspend fun <T> readVerified(
        channel: FileChannel,
        index: CbzArchiveIndex,
        descriptor: CbzPageDescriptor,
        check: () -> Unit,
        consume: suspend (ReaderPageInput) -> T,
    ): DecodedReaderPage<T> = zipResult(ReaderError.CORRUPT_PAGE) {
        val entry = index.entries.firstOrNull { it.id == descriptor.id } ?: reject(ReaderError.PAGE_NOT_FOUND)
        archives.openPage(channel, entry, check).use { page ->
            if (page.format != descriptor.format) reject(ReaderError.CORRUPT_PAGE)
            val content = consume(page)
            page.verifyComplete()
            if (channel.size() != index.length) reject(ReaderError.INVALID_ARCHIVE)
            check()
            DecodedReaderPage(descriptor, content)
        }
    }

    private inline fun <T> preflight(document: OpenedCbzDocument, block: () -> T): T =
        zipResult(ReaderError.INVALID_ARCHIVE) {
            try {
                block()
            } catch (error: IOException) {
                if (document is OpenedCbzDocument.Direct && error !is ZipValidationException &&
                    error !is ZipException && error !is EOFException
                ) throw DirectReadUnavailable()
                throw error
            }
        }

    private inline fun <T> retryDirectFailure(block: (Boolean) -> T): T = try {
        block(true)
    } catch (_: DirectReadUnavailable) {
        block(false)
    }

    private inline fun <T> OpenedCbzDocument.withChannel(read: (FileChannel) -> T): T = when (this) {
        is OpenedCbzDocument.Direct -> read(channel)
        is OpenedCbzDocument.Temporary -> file.inputStream().use { read(it.channel) }
    }

    private suspend inline fun <T> result(block: () -> T): ReaderResult<T> = try {
        ReaderResult.Success(block())
    } catch (error: ReaderFailure) {
        currentCoroutineContext().ensureActive()
        ReaderResult.Failure(error.reason)
    } catch (error: DocumentAccessException) {
        currentCoroutineContext().ensureActive()
        ReaderResult.Failure(error.error.readerError(ReaderError.IO_FAILURE))
    } catch (_: SQLiteException) {
        currentCoroutineContext().ensureActive()
        ReaderResult.Failure(ReaderError.STORAGE_FAILURE)
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        ReaderResult.Failure(ReaderError.IO_FAILURE)
    }

    private inline fun <T> zipResult(invalid: ReaderError, block: () -> T): T = try {
        block()
    } catch (error: ZipValidationException) {
        reject(error.error.readerError(invalid))
    } catch (_: ZipException) {
        reject(invalid)
    } catch (_: EOFException) {
        reject(invalid)
    }

    private fun CbzImportError.readerError(invalid: ReaderError): ReaderError = when (this) {
        CbzImportError.ACCESS_DENIED, CbzImportError.PERSISTENT_PERMISSION_UNAVAILABLE -> ReaderError.ACCESS_DENIED
        CbzImportError.SOURCE_UNAVAILABLE -> ReaderError.SOURCE_UNAVAILABLE
        CbzImportError.RESOURCE_LIMIT_EXCEEDED -> ReaderError.RESOURCE_LIMIT_EXCEEDED
        CbzImportError.INSUFFICIENT_SPACE -> ReaderError.INSUFFICIENT_SPACE
        CbzImportError.IO_FAILURE -> ReaderError.IO_FAILURE
        else -> invalid
    }

    private data class IndexedPages(val index: CbzArchiveIndex, val pages: List<CbzPageDescriptor>)

    private class SessionState(
        val session: ReaderSession,
        val source: LibrarySource,
        val index: CbzArchiveIndex,
        val copy: TemporaryCbzStore.Copy?,
        val revision: DocumentRevision?,
    ) {
        private var open = true
        private var active: Job? = null

        @Synchronized fun isOpen() = open

        @Synchronized fun ensureOpen() {
            if (!open) throw CancellationException("Reader session closed")
        }

        @Synchronized fun beginRead(job: Job) {
            ensureOpen()
            active = job
        }

        @Synchronized fun endRead() {
            active = null
        }

        fun invalidate(): Job? {
            val job = synchronized(this) {
                open = false
                active
            }
            job?.cancel(CancellationException("Reader session closed"))
            return job
        }
    }

    private class ReaderFailure(val reason: ReaderError) : RuntimeException()
    private class DirectReadUnavailable : RuntimeException()
    private fun reject(reason: ReaderError): Nothing = throw ReaderFailure(reason)

    private companion object {
        val SESSION_INVALIDATING_ERRORS = setOf(
            ReaderError.ENTRY_NOT_FOUND, ReaderError.SOURCE_UNAVAILABLE,
            ReaderError.ACCESS_DENIED, ReaderError.INVALID_ARCHIVE,
        )
    }
}

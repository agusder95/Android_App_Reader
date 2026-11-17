package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.CbzPageDescriptor
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderProgress
import com.agustin.sumi.domain.model.ReaderSession

/** Expected reader failures stay free of Android, URI, and ZIP implementation details. */
enum class ReaderError {
    ENTRY_NOT_FOUND,
    SOURCE_UNAVAILABLE,
    ACCESS_DENIED,
    INVALID_ARCHIVE,
    CORRUPT_PAGE,
    PAGE_NOT_FOUND,
    RESOURCE_LIMIT_EXCEEDED,
    INSUFFICIENT_SPACE,
    IO_FAILURE,
    STORAGE_FAILURE,
    STALE_PROGRESS,
}

sealed interface ReaderResult<out T> {
    data class Success<T>(val value: T) : ReaderResult<T>
    data class Failure(val error: ReaderError) : ReaderResult<Nothing>
}

/** Bounded page-entry input. The repository owns and closes it within [ReaderRepository.readPage]. */
interface ReaderPageInput {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

data class DecodedReaderPage<T>(
    val descriptor: CbzPageDescriptor,
    val content: T,
)

interface ReaderRepository {
    /** Reopens and indexes the current source for the library entry. */
    suspend fun openSession(
        bookId: LibraryEntryId,
        direction: ReaderDirection,
    ): ReaderResult<ReaderSession>

    /** Closes resources owned by this successfully opened session; safe to call repeatedly. */
    suspend fun closeSession(session: ReaderSession)

    /**
     * Provides only the requested entry to [consume]. The implementation owns its input,
     * completes integrity checks before returning success, and propagates cancellation.
     * It must not require the consumer to buffer the entire expanded page.
     */
    suspend fun <T> readPage(
        session: ReaderSession,
        pageId: CbzPageId,
        consume: suspend (ReaderPageInput) -> T,
    ): ReaderResult<DecodedReaderPage<T>>
}

interface ReaderProgressRepository {
    /** Returns default progress (first page, LTR) when this book has no saved progress. */
    suspend fun load(bookId: LibraryEntryId): ReaderResult<ReaderProgress>

    /** Capture in confirmation order, before dispatching the asynchronous write. */
    fun confirm(bookId: LibraryEntryId, progress: ReaderProgress): ReaderProgressConfirmation
}

fun interface ReaderProgressConfirmation {
    /** Persists only if this confirmation has not been superseded for its book. */
    suspend fun save(): ReaderResult<Unit>
}


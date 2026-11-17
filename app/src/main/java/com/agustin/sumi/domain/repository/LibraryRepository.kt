package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import kotlinx.coroutines.flow.Flow

enum class LibraryWriteResult {
    SUCCESS,
    DUPLICATE_DOCUMENT,
    ENTRY_NOT_FOUND,
    STORAGE_FAILURE,
}

interface LibraryRepository {
    /** Observes the requested window of matching entries in query order. */
    fun observeEntries(query: LibraryQuery): Flow<List<LibraryEntry>>

    /** Inserts a validated source with a new ID or reports an existing document identity. */
    suspend fun insert(
        source: LibrarySource,
        title: LibraryTitle,
        addedAtEpochMillis: Long,
        coverPageId: CbzPageId?,
    ): LibraryWriteResult

    /** Reports a confirmed import even if cancellation arrives as the write completes. */
    suspend fun insertForImport(
        source: LibrarySource,
        title: LibraryTitle,
        addedAtEpochMillis: Long,
        coverPageId: CbzPageId?,
        onCommitted: () -> Unit,
    ): LibraryWriteResult {
        val result = insert(source, title, addedAtEpochMillis, coverPageId)
        if (result == LibraryWriteResult.SUCCESS) onCommitted()
        return result
    }

    /** Replaces a validated source while preserving the entry ID and original date. */
    suspend fun replaceSource(
        id: LibraryEntryId,
        source: LibrarySource,
        coverPageId: CbzPageId?,
    ): LibraryWriteResult

    /** Removes the entry without deleting the original document. */
    suspend fun remove(id: LibraryEntryId): LibraryWriteResult
}

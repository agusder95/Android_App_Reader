package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.LibraryEntryId

internal fun interface LibraryEntryRemoval {
    suspend fun remove(id: LibraryEntryId): LibraryRemovalResult
}

internal enum class LibraryRemovalResult {
    REMOVED,
    CLEANUP_PENDING,
    ENTRY_NOT_FOUND,
    STORAGE_FAILURE,
}

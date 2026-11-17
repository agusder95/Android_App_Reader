package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.LibraryEntryId

internal fun interface LibraryCoverSource<out T> {
    suspend fun load(id: LibraryEntryId): T?
}

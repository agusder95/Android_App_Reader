package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.LibrarySortOrder

internal interface LibrarySortPreference {
    suspend fun read(): LibrarySortOrder
    suspend fun save(order: LibrarySortOrder): Boolean
}

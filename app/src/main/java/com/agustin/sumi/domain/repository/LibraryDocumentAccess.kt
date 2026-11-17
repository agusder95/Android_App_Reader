package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.CbzSourceReference

internal enum class LibraryAccessStatus {
    AVAILABLE,
    MISSING,
    PERMISSION_REVOKED,
    TEMPORARY_FAILURE,
}

internal fun interface LibraryDocumentAccess {
    suspend fun check(source: CbzSourceReference): LibraryAccessStatus
}

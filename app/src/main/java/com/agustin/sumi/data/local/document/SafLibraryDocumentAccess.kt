package com.agustin.sumi.data.local.document

import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.repository.LibraryAccessStatus
import com.agustin.sumi.domain.repository.LibraryDocumentAccess
import com.agustin.sumi.domain.usecase.CbzWorkGate

internal class SafLibraryDocumentAccess(
    private val documents: SafDocumentSource,
    private val work: CbzWorkGate = CbzWorkGate(),
) : LibraryDocumentAccess {
    override suspend fun check(source: CbzSourceReference): LibraryAccessStatus = work.run { checkAccess(source) }

    private suspend fun checkAccess(source: CbzSourceReference): LibraryAccessStatus = try {
        documents.verifyAvailable(source)
        LibraryAccessStatus.AVAILABLE
    } catch (failure: DocumentAccessException) {
        when (failure.error) {
            CbzImportError.ACCESS_DENIED -> LibraryAccessStatus.PERMISSION_REVOKED
            CbzImportError.SOURCE_UNAVAILABLE -> LibraryAccessStatus.MISSING
            else -> LibraryAccessStatus.TEMPORARY_FAILURE
        }
    }
}

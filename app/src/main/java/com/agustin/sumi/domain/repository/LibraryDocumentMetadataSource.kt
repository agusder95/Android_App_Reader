package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity

internal data class LibraryDocumentMetadata(
    val identity: LibraryDocumentIdentity,
    val displayName: String?,
)

internal fun interface LibraryDocumentMetadataSource {
    /** Returns null if the document identity cannot be established. */
    suspend fun resolve(source: CbzSourceReference): LibraryDocumentMetadata?
}

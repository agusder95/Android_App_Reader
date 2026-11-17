package com.agustin.sumi.domain.model

@JvmInline
value class LibraryEntryId(val value: Long)

/** Document identity is independent of the reference used to reopen it. */
data class LibraryDocumentIdentity(val authority: String, val documentId: String) {
    init {
        require(authority.isNotBlank() && documentId.isNotEmpty())
    }

    override fun toString(): String = "LibraryDocumentIdentity"
}

data class LibrarySource(
    val identity: LibraryDocumentIdentity,
    val reference: CbzSourceReference,
    // A null grant reference means that the document has its own grant.
    val grantReference: LibraryGrantReference? = null,
)

@JvmInline
value class LibraryGrantReference(val value: String) {
    override fun toString(): String = "LibraryGrantReference"
}

enum class LibraryAvailability {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
}

data class LibraryEntry(
    val id: LibraryEntryId,
    val source: LibrarySource,
    val title: LibraryTitle,
    val addedAtEpochMillis: Long,
    val availability: LibraryAvailability = LibraryAvailability.UNKNOWN,
    val coverPageId: CbzPageId? = null,
)

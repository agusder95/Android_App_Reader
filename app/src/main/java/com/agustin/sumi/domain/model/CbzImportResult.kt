package com.agustin.sumi.domain.model

sealed interface CbzImportResult {
    /** Complete snapshot with one descriptor per admitted page, in reading order. */
    data class Success(
        val source: CbzSourceReference,
        val pages: List<CbzPageDescriptor>,
    ) : CbzImportResult

    data class Failure(val error: CbzImportError) : CbzImportResult
}

enum class CbzImportError {
    // Select a document again when access to the original cannot be retained.
    PERSISTENT_PERMISSION_UNAVAILABLE,
    ACCESS_DENIED,
    SOURCE_UNAVAILABLE,

    EMPTY_FILE,
    EMPTY_ARCHIVE,
    INVALID_ARCHIVE,
    CORRUPT_ARCHIVE,
    NO_SUPPORTED_IMAGES,
    UNSUPPORTED_ARCHIVE,
    UNSAFE_ENTRY_PATH,
    DUPLICATE_ENTRY_PATH,
    RESOURCE_LIMIT_EXCEEDED,
    INSUFFICIENT_SPACE,
    IO_FAILURE,
}

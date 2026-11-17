package com.agustin.sumi.domain.model

/**
 * Identifies a page within its source, including when pages in different folders
 * share a file name. The value is opaque to consumers and excluded from diagnostics.
 */
@JvmInline
value class CbzPageId(val value: String) {
    override fun toString(): String = "CbzPageId"
}

enum class CbzPageFormat {
    JPEG,
    PNG,
    WEBP,
}

data class CbzPageDescriptor(
    val id: CbzPageId,
    val format: CbzPageFormat,
)

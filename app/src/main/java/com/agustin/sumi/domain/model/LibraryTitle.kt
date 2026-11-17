package com.agustin.sumi.domain.model

import java.text.Normalizer
import java.util.Locale

@JvmInline
value class LibraryTitle(val value: String) {
    init {
        require(value.isNotBlank())
    }

    val sortKey: String get() = normalizeLibraryTitle(value)

    override fun toString(): String = "LibraryTitle"

    companion object {
        fun fromDisplayName(displayName: String?, fallback: LibraryTitle): LibraryTitle {
            val name = displayName.orEmpty().trim()
            val extension = name.substringAfterLast('.', missingDelimiterValue = "")
            val title = when (extension.lowercase(Locale.ROOT)) {
                "cbz", "zip" -> name.substringBeforeLast('.').trimEnd()
                else -> name
            }
            return if (title.isBlank()) fallback else LibraryTitle(title)
        }
    }
}

internal fun normalizeLibraryTitle(value: String): String {
    val canonical = Normalizer.normalize(value, Normalizer.Form.NFC)
    return Normalizer.normalize(canonical.lowercase(Locale.ROOT), Normalizer.Form.NFC)
}

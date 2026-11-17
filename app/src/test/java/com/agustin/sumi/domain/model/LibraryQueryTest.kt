package com.agustin.sumi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {
    private val fallback = LibraryTitle("Sin título")

    @Test
    fun displayNameLosesOnlyTheFinalArchiveSuffix() {
        assertEquals("El Viaje.cbz", LibraryTitle.fromDisplayName(" El Viaje.cbz.ZIP ", fallback).value)
        assertEquals("El Viaje", LibraryTitle.fromDisplayName("El Viaje.CbZ", fallback).value)
        assertEquals("El Viaje.pdf", LibraryTitle.fromDisplayName("El Viaje.pdf", fallback).value)
    }

    @Test
    fun missingOrEmptyDisplayNameUsesTheSuppliedFallback() {
        for (name in listOf(null, "", "  ", ".cbz", " .ZIP ")) {
            assertEquals(fallback, LibraryTitle.fromDisplayName(name, fallback))
        }
    }

    @Test
    fun searchMatchesCanonicalUnicodeAndDifferentCapitalization() {
        val title = LibraryTitle("EL VIAJE DE INÉS")
        assertTrue(LibraryQuery("ine\u0301s").matches(title))
        assertTrue(LibraryQuery("INÉS").matches(title))
        assertEquals("el viaje de inés", title.sortKey)
    }

    @Test
    fun searchPreservesAccentsAndTreatsSpecialCharactersLiterally() {
        assertFalse(LibraryQuery("cafe").matches(LibraryTitle("Café")))
        assertTrue(LibraryQuery("%_\\").matches(LibraryTitle("Volumen %_\\ 1")))
        assertFalse(LibraryQuery("%_\\").matches(LibraryTitle("Volumen 123")))
        assertTrue(LibraryQuery().matches(LibraryTitle("Café")))
    }

    @Test
    fun titleSortUsesCaseInsensitiveKeysAndAnAscendingIdTieBreakInBothDirections() {
        val entries = listOf(entry(8, "a\u0301lbum"), entry(3, "ÁLBUM"), entry(5, "Zeta"), entry(1, "Alba"))

        assertEquals(listOf(1L, 5L, 3L, 8L), sortedIds(entries, LibrarySortOrder.TITLE_ASCENDING))
        assertEquals(listOf(3L, 8L, 5L, 1L), sortedIds(entries, LibrarySortOrder.TITLE_DESCENDING))
    }

    @Test
    fun recentSortUsesDescendingDateAndAscendingIdForEqualDates() {
        val entries = listOf(entry(9, "A", 10), entry(3, "Z", 10), entry(5, "B", 20))
        assertEquals(listOf(5L, 3L, 9L), entries.sortedWith(LibraryQuery().comparator).map { it.id.value })
    }

    @Test
    fun documentIdentityDependsOnBothOpaqueComponentsInsteadOfAccessReference() {
        val identity = LibraryDocumentIdentity("provider", "Volume:1")
        val direct = LibrarySource(identity, CbzSourceReference("direct"))
        val tree = LibrarySource(LibraryDocumentIdentity("provider", "Volume:1"), CbzSourceReference("tree"))

        assertEquals(direct.identity, tree.identity)
        assertNotEquals(direct.reference, tree.reference)
        assertNotEquals(identity, LibraryDocumentIdentity("other", "Volume:1"))
        assertNotEquals(identity, LibraryDocumentIdentity("provider", "volume:1"))
        assertNotEquals(identity, LibraryDocumentIdentity("provider", "Volume:1 "))
    }

    @Test
    fun internalEntryIdAndDocumentIdentityRemainIndependent() {
        val original = entry(12, "Volumen")
        val replacement = original.copy(source = entry(7, "Volumen").source)
        assertEquals(original.id, replacement.id)
        assertNotEquals(original.source.identity, replacement.source.identity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyProviderAuthorityCannotIdentifyADocument() {
        LibraryDocumentIdentity("", "volume")
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyDocumentIdCannotIdentifyADocument() {
        LibraryDocumentIdentity("provider", "")
    }

    private fun entry(id: Long, title: String, addedAt: Long = 0): LibraryEntry = LibraryEntry(
        id = LibraryEntryId(id),
        source = LibrarySource(LibraryDocumentIdentity("provider", "doc-$id"), CbzSourceReference("ref-$id")),
        title = LibraryTitle(title),
        addedAtEpochMillis = addedAt,
    )

    private fun sortedIds(entries: List<LibraryEntry>, order: LibrarySortOrder): List<Long> =
        entries.sortedWith(LibraryQuery(sortOrder = order).comparator).map { it.id.value }
}

package com.agustin.sumi.domain.model

enum class LibrarySortOrder {
    TITLE_ASCENDING,
    TITLE_DESCENDING,
    RECENTLY_ADDED,
}

class LibraryQuery(
    searchText: String = "",
    val sortOrder: LibrarySortOrder = LibrarySortOrder.RECENTLY_ADDED,
    val offset: Int = 0,
    val limit: Int = 100,
) {
    init {
        require(offset >= 0 && limit in 1..100)
    }

    val searchKey: String = normalizeLibraryTitle(searchText)

    fun matches(title: LibraryTitle): Boolean = title.sortKey.contains(searchKey)

    val comparator: Comparator<LibraryEntry> = when (sortOrder) {
        LibrarySortOrder.TITLE_ASCENDING -> compareBy<LibraryEntry> { it.title.sortKey }
        LibrarySortOrder.TITLE_DESCENDING -> compareByDescending<LibraryEntry> { it.title.sortKey }
        LibrarySortOrder.RECENTLY_ADDED -> compareByDescending<LibraryEntry> { it.addedAtEpochMillis }
    }.thenBy { it.id.value }
}

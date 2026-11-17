package com.agustin.sumi.domain.model

/** Reading order remains the natural order of the CBZ page descriptors. */
enum class ReaderDirection {
    LTR,
    RTL,
}

enum class ReaderSwipeDirection {
    LEFT,
    RIGHT,
}

@JvmInline
value class ReaderSessionId(val value: String) {
    init {
        require(value.isNotBlank())
    }

    override fun toString(): String = "ReaderSessionId"
}

@JvmInline
value class ReaderZoom(val factor: Float) {
    init {
        require(factor.isFinite() && factor in MIN_FACTOR..MAX_FACTOR)
    }

    companion object {
        const val MIN_FACTOR = 1f
        const val MAX_FACTOR = 3f

        val FIT_PAGE = ReaderZoom(MIN_FACTOR)
    }
}

data class ReaderOptions(
    val direction: ReaderDirection = ReaderDirection.LTR,
    val zoom: ReaderZoom = ReaderZoom.FIT_PAGE,
)

data class ReaderPage(
    val descriptor: CbzPageDescriptor,
    val index: Int,
) {
    init {
        require(index >= 0)
    }

    val id: CbzPageId
        get() = descriptor.id
}

data class ReaderPosition(
    val pageId: CbzPageId,
    val index: Int,
) {
    init {
        require(index >= 0)
    }
}

/** A validated, ordered metadata snapshot for one library entry. */
data class ReaderSession(
    val id: ReaderSessionId,
    val bookId: LibraryEntryId,
    val pages: List<ReaderPage>,
    val options: ReaderOptions = ReaderOptions(),
) {
    init {
        require(pages.isNotEmpty())
        require(pages.map(ReaderPage::index) == pages.indices.toList())
        require(pages.map(ReaderPage::id).distinct().size == pages.size)
    }

    val firstPosition: ReaderPosition
        get() = pages.first().position

    val lastPosition: ReaderPosition
        get() = pages.last().position

    /** Restores a saved page when it still exists, otherwise starts at the first page. */
    fun restorePosition(pageId: CbzPageId?): ReaderPosition =
        pages.firstOrNull { it.id == pageId }?.position ?: firstPosition

    fun next(position: ReaderPosition): ReaderPosition? =
        pageAt(position)?.let { pages.getOrNull(it.index + 1)?.position }

    fun previous(position: ReaderPosition): ReaderPosition? =
        pageAt(position)?.let { pages.getOrNull(it.index - 1)?.position }

    /** A left swipe advances in LTR; a right swipe advances in RTL. */
    fun onSwipe(position: ReaderPosition, swipe: ReaderSwipeDirection): ReaderPosition? =
        when (options.direction) {
            ReaderDirection.LTR -> when (swipe) {
                ReaderSwipeDirection.LEFT -> next(position)
                ReaderSwipeDirection.RIGHT -> previous(position)
            }

            ReaderDirection.RTL -> when (swipe) {
                ReaderSwipeDirection.RIGHT -> next(position)
                ReaderSwipeDirection.LEFT -> previous(position)
            }
        }

    private fun pageAt(position: ReaderPosition): ReaderPage? =
        pages.getOrNull(position.index)?.takeIf { it.id == position.pageId }
}

private val ReaderPage.position: ReaderPosition
    get() = ReaderPosition(pageId = id, index = index)

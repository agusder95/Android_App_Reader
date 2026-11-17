package com.agustin.sumi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSessionTest {
    @Test
    fun restorePositionUsesSavedPageWhenPresent() {
        val session = session()

        assertEquals(session.pages[1].let { ReaderPosition(it.id, it.index) }, session.restorePosition(pageId(2)))
    }

    @Test
    fun restorePositionFallsBackToFirstPageWhenProgressIsMissingOrStale() {
        val session = session()

        assertEquals(session.firstPosition, session.restorePosition(null))
        assertEquals(session.firstPosition, session.restorePosition(CbzPageId("missing")))
    }

    @Test
    fun nextAndPreviousFollowNaturalPageOrderAndStopAtBoundaries() {
        val session = session()

        assertNull(session.previous(session.firstPosition))
        assertEquals(session.pages[1].let { ReaderPosition(it.id, it.index) }, session.next(session.firstPosition))
        assertEquals(session.pages[1].let { ReaderPosition(it.id, it.index) }, session.previous(session.lastPosition))
        assertNull(session.next(session.lastPosition))
    }

    @Test
    fun invalidPositionDoesNotNavigate() {
        val session = session()

        assertNull(session.next(ReaderPosition(pageId(2), 2)))
        assertNull(session.previous(ReaderPosition(pageId(2), 0)))
    }

    @Test
    fun swipeDirectionMapsToNaturalNavigationForLtrAndRtl() {
        val ltr = session(ReaderDirection.LTR)
        val rtl = session(ReaderDirection.RTL)

        assertEquals(ltr.next(ltr.firstPosition), ltr.onSwipe(ltr.firstPosition, ReaderSwipeDirection.LEFT))
        assertNull(ltr.onSwipe(ltr.firstPosition, ReaderSwipeDirection.RIGHT))
        assertEquals(rtl.next(rtl.firstPosition), rtl.onSwipe(rtl.firstPosition, ReaderSwipeDirection.RIGHT))
        assertNull(rtl.onSwipe(rtl.firstPosition, ReaderSwipeDirection.LEFT))
    }

    @Test
    fun zoomAcceptsRangeFromOneToThree() {
        assertEquals(1f, ReaderZoom.FIT_PAGE.factor)
        assertEquals(3f, ReaderZoom(3f).factor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zoomRejectsValuesAboveThree() {
        ReaderZoom(3.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zoomRejectsNonFiniteValues() {
        ReaderZoom(Float.NaN)
    }

    private fun session(direction: ReaderDirection = ReaderDirection.LTR): ReaderSession =
        ReaderSession(
            id = ReaderSessionId("test-session"),
            bookId = LibraryEntryId(7),
            pages = (1..3).mapIndexed { index, id ->
                ReaderPage(
                    descriptor = CbzPageDescriptor(CbzPageId("page-$id"), CbzPageFormat.PNG),
                    index = index,
                )
            },
            options = ReaderOptions(direction = direction),
        )

    private fun pageId(id: Int) = CbzPageId("page-$id")
}

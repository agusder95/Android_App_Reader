package com.agustin.sumi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderProgressTest {
    @Test
    fun defaultsToFirstPageAndLtr() {
        val progress = ReaderProgress()

        assertNull(progress.lastConfirmedPageId)
        assertEquals(ReaderDirection.LTR, progress.direction)
    }

    @Test
    fun savedPageRestoresAgainstCurrentSessionAndStalePageFallsBack() {
        val session = ReaderSession(
            id = ReaderSessionId("progress-session"),
            bookId = LibraryEntryId(4),
            pages = listOf(
                ReaderPage(CbzPageDescriptor(CbzPageId("page-1"), CbzPageFormat.JPEG), 0),
                ReaderPage(CbzPageDescriptor(CbzPageId("page-2"), CbzPageFormat.JPEG), 1),
            ),
        )

        assertEquals(
            ReaderPosition(CbzPageId("page-2"), 1),
            ReaderProgress(CbzPageId("page-2")).positionIn(session),
        )
        assertEquals(session.firstPosition, ReaderProgress(CbzPageId("removed-page")).positionIn(session))
    }
}

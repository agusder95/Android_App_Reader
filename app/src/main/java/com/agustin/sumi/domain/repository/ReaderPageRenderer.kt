package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.ReaderSession

internal data class ReaderViewport(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}

internal interface ReaderPageRenderer {
    suspend fun load(session: ReaderSession, pageId: CbzPageId, viewport: ReaderViewport): ReaderResult<Unit>
    fun clear()
}

package com.agustin.sumi.domain.model

/** Last successfully displayed page and per-book reading direction. */
data class ReaderProgress(
    val lastConfirmedPageId: CbzPageId? = null,
    val direction: ReaderDirection = ReaderDirection.LTR,
)

/** Resolves saved progress against the current session, falling back when its page disappeared. */
fun ReaderProgress.positionIn(session: ReaderSession): ReaderPosition =
    session.restorePosition(lastConfirmedPageId)

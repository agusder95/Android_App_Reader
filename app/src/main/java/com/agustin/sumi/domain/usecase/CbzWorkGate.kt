package com.agustin.sumi.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An operation holds the slot until validation and any private CBZ copy have been closed. */
internal class CbzWorkGate {
    private val slot = Mutex()

    suspend fun <T> run(operation: suspend () -> T): T = slot.withLock { operation() }
}

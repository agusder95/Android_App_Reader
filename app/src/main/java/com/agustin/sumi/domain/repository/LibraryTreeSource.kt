package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.CbzSourceReference
import kotlinx.coroutines.flow.Flow

internal sealed interface TreeDiscovery {
    data class Candidate(val source: CbzSourceReference) : TreeDiscovery
    data class Omitted(val incomplete: Boolean, val limited: Boolean = false) : TreeDiscovery
}

/** Keeps the selected tree accessible until its consumer has finished using the candidates. */
internal interface LibraryTreeSource {
    suspend fun <T> withTree(
        tree: CbzSourceReference,
        consume: suspend (Flow<TreeDiscovery>) -> T,
    ): T
}

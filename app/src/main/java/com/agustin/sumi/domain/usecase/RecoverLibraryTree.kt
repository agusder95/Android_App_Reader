package com.agustin.sumi.domain.usecase

import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.repository.LibraryTreeSource
import com.agustin.sumi.domain.repository.TreeDiscovery
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class TreeRecoveryResult(val recovered: Int, val remaining: Int?, val incomplete: Boolean)

internal class RecoverLibraryTree(
    private val trees: LibraryTreeSource,
    private val import: ImportIntoLibrary,
    private val countEntries: suspend (LibraryGrantReference) -> Int,
    private val findEntry: suspend (LibraryGrantReference, LibraryDocumentIdentity) -> LibraryEntryId?,
) {
    suspend operator fun invoke(original: LibraryGrantReference, selected: CbzSourceReference): TreeRecoveryResult =
        withContext(Dispatchers.IO) {
            val recovered = mutableSetOf<LibraryEntryId>()
            var incomplete = false
            val total = try {
                countEntries(original)
            } catch (_: IOException) {
                return@withContext TreeRecoveryResult(0, null, true)
            }
            if (total > 0) try {
                trees.withTree(selected) { events ->
                    events.collect { event ->
                        ensureActive()
                        when (event) {
                            is TreeDiscovery.Omitted -> incomplete = incomplete || event.incomplete
                            is TreeDiscovery.Candidate -> {
                                var target: LibraryEntryId? = null
                                val result = import.replaceMatching(event.source) { identity ->
                                    findEntry(original, identity)?.takeUnless(recovered::contains).also { target = it }
                                }
                                if (result == ImportIntoLibrary.Result.Recovered) recovered.add(requireNotNull(target))
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                ensureActive()
                incomplete = true
            }
            TreeRecoveryResult(recovered.size, (total - recovered.size).coerceAtLeast(0), incomplete)
        }
}

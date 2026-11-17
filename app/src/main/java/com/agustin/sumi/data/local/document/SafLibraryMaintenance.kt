package com.agustin.sumi.data.local.document

import android.database.sqlite.SQLiteException
import android.util.Log
import com.agustin.sumi.data.local.cover.CoverCache
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.repository.LibraryEntryRemoval
import com.agustin.sumi.domain.repository.LibraryRemovalResult
import com.agustin.sumi.domain.repository.LibraryWriteResult
import com.agustin.sumi.domain.usecase.CbzWorkGate
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class SafLibraryMaintenance(
    private val library: RoomLibraryRepository,
    private val documents: SafDocumentSource,
    private val work: CbzWorkGate,
    private val cache: CoverCache,
    private val registry: LibraryGrantRegistry,
) : LibraryEntryRemoval {
    private val removing = mutableMapOf<LibraryEntryId, Int>()
    // All lease transitions and release checks run inside the shared work slot.
    private val treeLeases = mutableMapOf<CbzSourceReference, Int>()

    suspend fun <T> withTreeGrant(tree: CbzSourceReference, operation: suspend () -> T): T {
        var leased = false
        try {
            work.run {
                withContext(Dispatchers.IO + NonCancellable) {
                    acquire(tree)
                    treeLeases[tree] = (treeLeases[tree] ?: 0) + 1
                    leased = true
                }
            }
            return operation()
        } finally {
            if (leased) withContext(Dispatchers.IO + NonCancellable) {
                work.run {
                    val remaining = treeLeases.getValue(tree) - 1
                    if (remaining == 0) treeLeases.remove(tree) else treeLeases[tree] = remaining
                    attemptCleanup { releaseUnused(setOf(tree)) }
                }
            }
        }
    }

    fun isRemoving(id: LibraryEntryId): Boolean = synchronized(removing) { id in removing }

    override suspend fun remove(id: LibraryEntryId): LibraryRemovalResult {
        synchronized(removing) { removing[id] = (removing[id] ?: 0) + 1 }
        try {
            return work.run {
                withContext(Dispatchers.IO) {
                    ensureActive()
                    // Finish cleanup once a delete may have committed.
                    withContext(NonCancellable) { removeEntry(id) }
                }
            }
        } finally {
            synchronized(removing) {
                val remaining = removing.getValue(id) - 1
                if (remaining == 0) removing.remove(id) else removing[id] = remaining
            }
        }
    }

    private suspend fun removeEntry(id: LibraryEntryId): LibraryRemovalResult {
        val entry = try {
            library.findById(id) ?: return LibraryRemovalResult.ENTRY_NOT_FOUND
        } catch (_: SQLiteException) {
            return LibraryRemovalResult.STORAGE_FAILURE
        }
        when (library.remove(id)) {
            LibraryWriteResult.SUCCESS -> Unit
            LibraryWriteResult.ENTRY_NOT_FOUND -> return LibraryRemovalResult.ENTRY_NOT_FOUND
            else -> return LibraryRemovalResult.STORAGE_FAILURE
        }
        val cacheClean = cache.remove(id)
        val grantsClean = attemptCleanup { releaseUnused(setOf(entry.source.grant())) }
        return if (cacheClean && grantsClean) LibraryRemovalResult.REMOVED else LibraryRemovalResult.CLEANUP_PENDING
    }

    // Called inside ImportIntoLibrary's work slot, through confirmation of the Room write.
    suspend fun withImportAccess(
        source: CbzSourceReference,
        replacedId: LibraryEntryId?,
        operation: suspend () -> ImportIntoLibrary.Result,
    ): ImportIntoLibrary.Result = withContext(Dispatchers.IO) {
        val grant = source.treeGrantReference() ?: source
        val previous = try {
            replacedId?.let { library.findById(it)?.source?.grant() ?: return@withContext ImportIntoLibrary.Result.EntryNotFound }
        } catch (_: SQLiteException) {
            return@withContext ImportIntoLibrary.Result.StorageFailure
        }
        try {
            ensureActive()
            withContext(NonCancellable) { acquire(grant) }
            ensureActive()
            val result = operation()
            if (replacedId != null && grant != source && result == ImportIntoLibrary.Result.Recovered) {
                withContext(NonCancellable) { cache.remove(replacedId) }
            }
            result
        } catch (failure: DocumentAccessException) {
            ImportIntoLibrary.Result.Rejected(failure.error)
        } catch (_: IOException) {
            ImportIntoLibrary.Result.StorageFailure
        } finally {
            withContext(NonCancellable) {
                attemptCleanup { releaseUnused(setOfNotNull(grant, previous)) }
            }
        }
    }

    suspend fun reconcile() = work.run {
        withContext(Dispatchers.IO) {
            attemptCleanup { cache.reconcile { library.findById(it) != null } }
            attemptCleanup { releaseUnused() }
        }
    }

    private suspend fun acquire(source: CbzSourceReference) {
        var time: Long? = null
        try {
            if (documents.persistReadPermission(source)) {
                time = documents.persistedReadTime(source)?.takeIf { it > 0 }
                    ?: throw IOException("New read grant has no persisted timestamp")
                val grants = registry.read()
                grants[source] = time
                registry.write(grants)
            }
        } catch (failure: IOException) {
            // If recording the acquisition fails, release only with positive evidence and no book dependency.
            if (time != null) attemptCleanup {
                if (!library.usesGrant(source.value) && documents.persistedReadTime(source) == time) {
                    documents.releaseReadPermission(source)
                }
            }
            throw failure
        }
    }

    private suspend fun releaseUnused(only: Set<CbzSourceReference>? = null) {
        val grants = registry.read()
        var changed = false
        var releaseFailed = false
        val entries = grants.iterator()
        while (entries.hasNext()) {
            val (source, time) = entries.next()
            if (only != null && source !in only) continue
            if (source in treeLeases) continue
            if (documents.persistedReadTime(source) == time) {
                if (library.usesGrant(source.value)) continue
                try {
                    documents.releaseReadPermission(source)
                } catch (_: DocumentAccessException) {
                    releaseFailed = true
                    continue
                }
            }
            // Missing or changed grants are no longer proven to belong to the library.
            entries.remove()
            changed = true
        }
        if (changed) registry.write(grants)
        if (releaseFailed) throw IOException("A library grant still needs cleanup")
    }

    private suspend fun attemptCleanup(operation: suspend () -> Unit): Boolean = try {
        operation()
        true
    } catch (_: IOException) {
        Log.w(TAG, "Library cleanup will be retried")
        false
    } catch (_: SQLiteException) {
        Log.w(TAG, "Library cleanup could not verify persisted entries")
        false
    }

    private fun LibrarySource.grant() = CbzSourceReference(grantReference?.value ?: reference.value)

    private companion object {
        const val TAG = "SafLibraryMaintenance"
    }
}

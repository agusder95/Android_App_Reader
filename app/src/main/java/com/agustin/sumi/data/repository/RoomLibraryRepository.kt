package com.agustin.sumi.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import com.agustin.sumi.data.local.database.LibraryDao
import com.agustin.sumi.data.local.database.LibraryEntryEntity
import com.agustin.sumi.data.local.database.toEntity
import com.agustin.sumi.data.local.document.treeGrantReference
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySortOrder
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryRepository
import com.agustin.sumi.domain.repository.LibraryWriteResult
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class RoomLibraryRepository(private val dao: LibraryDao) : LibraryRepository {
    suspend fun findById(id: LibraryEntryId): LibraryEntry? = dao.findById(id.value)?.toDomain()

    suspend fun findTreeEntry(grant: LibraryGrantReference, identity: LibraryDocumentIdentity): LibraryEntry? = readTree {
        dao.findTreeEntry(grant.value, identity.authority, identity.documentId)?.toDomain()
    }

    suspend fun countTreeEntries(grant: LibraryGrantReference): Int = readTree { dao.countTreeEntries(grant.value) }

    private inline fun <T> readTree(operation: () -> T): T = try {
        operation()
    } catch (failure: SQLiteException) {
        throw IOException("Cannot read library tree entries", failure)
    }

    suspend fun usesGrant(reference: String): Boolean = dao.usesGrant(reference)

    override fun observeEntries(query: LibraryQuery): Flow<List<LibraryEntry>> {
        val (searchKey, offset, limit) = Triple(query.searchKey, query.offset, query.limit)
        val rows = when (query.sortOrder) {
            LibrarySortOrder.TITLE_ASCENDING -> dao.observeTitleAscending(searchKey, offset, limit)
            LibrarySortOrder.TITLE_DESCENDING -> dao.observeTitleDescending(searchKey, offset, limit)
            LibrarySortOrder.RECENTLY_ADDED -> dao.observeRecentlyAdded(searchKey, offset, limit)
        }
        return rows.map { it.map(LibraryEntryEntity::toDomain) }
    }

    override suspend fun insert(
        source: LibrarySource,
        title: LibraryTitle,
        addedAtEpochMillis: Long,
        coverPageId: CbzPageId?,
    ): LibraryWriteResult = write {
        dao.insert(source.withTreeGrant().toEntity(title, addedAtEpochMillis, coverPageId))
        LibraryWriteResult.SUCCESS
    }

    override suspend fun insertForImport(
        source: LibrarySource,
        title: LibraryTitle,
        addedAtEpochMillis: Long,
        coverPageId: CbzPageId?,
        onCommitted: () -> Unit,
    ): LibraryWriteResult {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val entry = source.withTreeGrant().toEntity(title, addedAtEpochMillis, coverPageId)
        // Check the caller inside the transaction and preserve the receipt once Room commits.
        return withContext(NonCancellable) {
            val result = write {
                dao.insertWhileActive(entry, caller::ensureActive)
                LibraryWriteResult.SUCCESS
            }
            if (result == LibraryWriteResult.SUCCESS) onCommitted()
            result
        }
    }

    override suspend fun replaceSource(
        id: LibraryEntryId,
        source: LibrarySource,
        coverPageId: CbzPageId?,
    ): LibraryWriteResult = write {
        val rows = dao.replaceSource(
            id = id.value,
            authority = source.identity.authority,
            documentId = source.identity.documentId,
            sourceReference = source.reference.value,
            grantReference = source.withTreeGrant().grantReference?.value,
            coverPageId = coverPageId?.value,
        )
        resultForRows(rows)
    }

    override suspend fun remove(id: LibraryEntryId): LibraryWriteResult = write {
        resultForRows(dao.delete(id.value))
    }

    private fun LibrarySource.withTreeGrant(): LibrarySource {
        val grant = reference.treeGrantReference() ?: return this
        return copy(grantReference = LibraryGrantReference(grant.value))
    }

    private fun resultForRows(rows: Int): LibraryWriteResult =
        if (rows == 0) LibraryWriteResult.ENTRY_NOT_FOUND else LibraryWriteResult.SUCCESS

    private inline fun write(operation: () -> LibraryWriteResult): LibraryWriteResult = try {
        operation()
    } catch (_: SQLiteConstraintException) {
        LibraryWriteResult.DUPLICATE_DOCUMENT
    } catch (_: SQLiteException) {
        LibraryWriteResult.STORAGE_FAILURE
    }
}

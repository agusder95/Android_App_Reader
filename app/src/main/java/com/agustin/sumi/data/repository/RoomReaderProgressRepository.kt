package com.agustin.sumi.data.repository

import android.database.sqlite.SQLiteException
import com.agustin.sumi.data.local.database.ReaderProgressDao
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderProgress
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderProgressConfirmation
import com.agustin.sumi.domain.repository.ReaderProgressRepository
import com.agustin.sumi.domain.repository.ReaderResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RoomReaderProgressRepository(private val dao: ReaderProgressDao) : ReaderProgressRepository {
    private val latest = ConcurrentHashMap<LibraryEntryId, Any>()

    override suspend fun load(bookId: LibraryEntryId): ReaderResult<ReaderProgress> = storageResult {
        val row = dao.find(bookId.value) ?: return@storageResult ReaderResult.Failure(ReaderError.ENTRY_NOT_FOUND)
        ReaderResult.Success(ReaderProgress(row.lastReadPageId?.let(::CbzPageId), ReaderDirection.valueOf(row.readingDirection)))
    }

    override fun confirm(bookId: LibraryEntryId, progress: ReaderProgress): ReaderProgressConfirmation {
        val ticket = Any()
        latest[bookId] = ticket
        return ReaderProgressConfirmation {
            try {
                storageResult {
                    val caller = currentCoroutineContext()
                    val found = dao.saveIfCurrent(bookId.value, progress.lastConfirmedPageId?.value, progress.direction.name) {
                        caller.ensureActive()
                        if (latest[bookId] !== ticket) throw SupersededConfirmation()
                    }
                    if (found) ReaderResult.Success(Unit) else ReaderResult.Failure(ReaderError.ENTRY_NOT_FOUND)
                }
            } finally {
                latest.remove(bookId, ticket)
            }
        }
    }

    private suspend inline fun <T> storageResult(operation: () -> ReaderResult<T>): ReaderResult<T> = try {
        operation()
    } catch (_: SupersededConfirmation) {
        currentCoroutineContext().ensureActive()
        ReaderResult.Failure(ReaderError.STALE_PROGRESS)
    } catch (_: SQLiteException) {
        currentCoroutineContext().ensureActive()
        ReaderResult.Failure(ReaderError.STORAGE_FAILURE)
    }

    private class SupersededConfirmation : RuntimeException()
}

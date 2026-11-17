package com.agustin.sumi.data.local.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

internal data class ReaderProgressRow(val lastReadPageId: String?, val readingDirection: String)

@Dao
internal interface ReaderProgressDao {
    @Query("SELECT lastReadPageId, readingDirection FROM library_entries WHERE id = :id")
    suspend fun find(id: Long): ReaderProgressRow?

    @Query(
        """
        UPDATE library_entries SET lastReadPageId = :pageId, readingDirection = :direction
        WHERE id = :id AND (lastReadPageId IS NOT :pageId OR readingDirection != :direction)
        """,
    )
    suspend fun updateIfChanged(id: Long, pageId: String?, direction: String): Int

    @Transaction
    suspend fun saveIfCurrent(id: Long, pageId: String?, direction: String, checkCurrent: () -> Unit): Boolean {
        checkCurrent()
        val exists = updateIfChanged(id, pageId, direction) != 0 || find(id) != null
        checkCurrent()
        return exists
    }
}

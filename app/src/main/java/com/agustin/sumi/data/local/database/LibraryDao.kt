package com.agustin.sumi.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val MATCHING_ENTRIES = "SELECT * FROM library_entries WHERE instr(searchKey, :searchKey) > 0 "

@Dao
internal interface LibraryDao {
    @Query("SELECT * FROM library_entries WHERE id = :id")
    suspend fun findById(id: Long): LibraryEntryEntity?

    @Query("SELECT * FROM library_entries WHERE grantReference = :grant AND authority = :authority AND documentId = :documentId")
    suspend fun findTreeEntry(grant: String, authority: String, documentId: String): LibraryEntryEntity?

    @Query("SELECT COUNT(*) FROM library_entries WHERE grantReference = :grant")
    suspend fun countTreeEntries(grant: String): Int

    @Query(MATCHING_ENTRIES + "ORDER BY titleOrderKey ASC, id ASC LIMIT :limit OFFSET :offset")
    fun observeTitleAscending(searchKey: String, offset: Int, limit: Int): Flow<List<LibraryEntryEntity>>

    @Query(MATCHING_ENTRIES + "ORDER BY titleOrderKey DESC, id ASC LIMIT :limit OFFSET :offset")
    fun observeTitleDescending(searchKey: String, offset: Int, limit: Int): Flow<List<LibraryEntryEntity>>

    @Query(MATCHING_ENTRIES + "ORDER BY addedAtEpochMillis DESC, id ASC LIMIT :limit OFFSET :offset")
    fun observeRecentlyAdded(searchKey: String, offset: Int, limit: Int): Flow<List<LibraryEntryEntity>>

    @Insert
    suspend fun insert(entry: LibraryEntryEntity)

    @Transaction
    suspend fun insertWhileActive(entry: LibraryEntryEntity, checkActive: () -> Unit) {
        checkActive()
        insert(entry)
        checkActive()
    }

    @Query(
        """
        UPDATE library_entries
        SET authority = :authority, documentId = :documentId, sourceReference = :sourceReference,
            grantReference = :grantReference, coverPageId = :coverPageId
        WHERE id = :id
        """,
    )
    suspend fun replaceSource(
        id: Long,
        authority: String,
        documentId: String,
        sourceReference: String,
        grantReference: String?,
        coverPageId: String?,
    ): Int

    @Query("DELETE FROM library_entries WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM library_entries
        WHERE grantReference = :grant OR (grantReference IS NULL AND sourceReference = :grant))
        """,
    )
    suspend fun usesGrant(grant: String): Boolean
}

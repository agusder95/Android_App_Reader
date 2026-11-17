package com.agustin.sumi.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle

@Entity(
    tableName = "library_entries",
    indices = [Index(value = ["authority", "documentId"], unique = true)],
)
internal data class LibraryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val authority: String,
    val documentId: String,
    val sourceReference: String,
    val grantReference: String?,
    val title: String,
    val searchKey: String,
    val titleOrderKey: ByteArray,
    val addedAtEpochMillis: Long,
    val coverPageId: String?,
    val lastReadPageId: String? = null,
    @ColumnInfo(defaultValue = "'LTR'") val readingDirection: String = "LTR",
) {
    fun toDomain(): LibraryEntry = LibraryEntry(
        id = LibraryEntryId(id),
        source = LibrarySource(
            identity = LibraryDocumentIdentity(authority, documentId),
            reference = CbzSourceReference(sourceReference),
            grantReference = grantReference?.let(::LibraryGrantReference),
        ),
        title = LibraryTitle(title),
        addedAtEpochMillis = addedAtEpochMillis,
        coverPageId = coverPageId?.let(::CbzPageId),
    )

    override fun toString(): String = "LibraryEntryEntity(id=$id)"
}

internal fun LibrarySource.toEntity(
    title: LibraryTitle,
    addedAtEpochMillis: Long,
    coverPageId: CbzPageId?,
): LibraryEntryEntity {
    val titleKey = title.sortKey
    return LibraryEntryEntity(
        authority = identity.authority,
        documentId = identity.documentId,
        sourceReference = reference.value,
        grantReference = grantReference?.value,
        title = title.value,
        searchKey = titleKey,
        // Big-endian UTF-16 bytes preserve the domain's String order under SQLite BLOB comparison.
        titleOrderKey = titleKey.toByteArray(Charsets.UTF_16BE),
        addedAtEpochMillis = addedAtEpochMillis,
        coverPageId = coverPageId?.value,
    )
}

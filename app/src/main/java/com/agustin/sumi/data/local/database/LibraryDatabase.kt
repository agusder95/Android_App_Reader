package com.agustin.sumi.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [LibraryEntryEntity::class], version = 1, exportSchema = true)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

package com.agustin.sumi.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LibraryEntryEntity::class], version = 2, exportSchema = true)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun readerProgressDao(): ReaderProgressDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library_entries ADD COLUMN lastReadPageId TEXT")
                db.execSQL("ALTER TABLE library_entries ADD COLUMN readingDirection TEXT NOT NULL DEFAULT 'LTR'")
            }
        }

        fun open(context: Context, name: String): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

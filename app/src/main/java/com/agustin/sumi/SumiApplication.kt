package com.agustin.sumi

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.room.Room
import com.agustin.sumi.data.local.database.LibraryDatabase
import com.agustin.sumi.data.local.cover.CoverCache
import com.agustin.sumi.data.local.cover.SafLibraryCoverSource
import com.agustin.sumi.data.local.document.SafDocumentSource
import com.agustin.sumi.data.local.document.LibraryGrantRegistry
import com.agustin.sumi.data.local.document.SafLibraryDocumentAccess
import com.agustin.sumi.data.local.document.SafLibraryDocumentMetadataSource
import com.agustin.sumi.data.local.document.SafLibraryMaintenance
import com.agustin.sumi.data.local.document.SafLibraryTreeSource
import com.agustin.sumi.data.local.preferences.LocalLibrarySortPreference
import com.agustin.sumi.data.repository.LocalCbzImportRepository
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import com.agustin.sumi.domain.usecase.ImportLibraryBatch
import com.agustin.sumi.domain.usecase.CbzWorkGate
import com.agustin.sumi.domain.usecase.RecoverLibraryTree
import java.io.File

class SumiApplication : Application() {
    internal val librarySortPreference by lazy {
        LocalLibrarySortPreference(getSharedPreferences("library_preferences", MODE_PRIVATE))
    }

    internal val libraryRepository by lazy {
        val database = Room.databaseBuilder(this, LibraryDatabase::class.java, "sumi-library.db").build()
        RoomLibraryRepository(database.libraryDao())
    }

    private val documents by lazy { SafDocumentSource(this) }
    private val work = CbzWorkGate()
    private val covers = lazy { CoverCache(File(cacheDir, "library-covers")) }

    internal val libraryMaintenance by lazy {
        SafLibraryMaintenance(
            libraryRepository, documents, work, covers.value,
            LibraryGrantRegistry(File(noBackupFilesDir, "library-read-grants.json")),
        )
    }

    internal val libraryCoverSource by lazy {
        SafLibraryCoverSource(
            libraryRepository::findById, documents, work, covers.value, isRemoving = libraryMaintenance::isRemoving,
        )
    }

    internal val libraryDocumentAccess by lazy { SafLibraryDocumentAccess(documents, work) }

    internal val importIntoLibrary by lazy {
        ImportIntoLibrary(
            newImporter = { LocalCbzImportRepository(documents) },
            documents = SafLibraryDocumentMetadataSource(this),
            library = libraryRepository,
            work = work,
            withAccess = libraryMaintenance::withImportAccess,
        )
    }

    internal val importLibraryBatch by lazy { ImportLibraryBatch(importIntoLibrary) }

    internal val libraryTrees by lazy { SafLibraryTreeSource(contentResolver, libraryMaintenance) }

    internal val recoverLibraryTree by lazy {
        RecoverLibraryTree(libraryTrees, importIntoLibrary, libraryRepository::countTreeEntries) { grant, identity ->
            libraryRepository.findTreeEntry(grant, identity)?.id
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && covers.isInitialized()) covers.value.trim()
    }

    internal fun restoreCoverBudget() {
        if (covers.isInitialized()) covers.value.restoreMemoryBudget()
    }
}

package com.agustin.sumi

import android.app.Application
import android.content.ComponentCallbacks2
import android.database.sqlite.SQLiteException
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import com.agustin.sumi.data.local.reader.NativeReaderRenderer
import com.agustin.sumi.data.repository.LocalCbzImportRepository
import com.agustin.sumi.data.repository.LocalReaderRepository
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.data.repository.RoomReaderProgressRepository
import com.agustin.sumi.domain.repository.ReaderProgressRepository
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderRepository
import com.agustin.sumi.domain.repository.ReaderResult
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import com.agustin.sumi.domain.usecase.ImportLibraryBatch
import com.agustin.sumi.domain.usecase.CbzWorkGate
import com.agustin.sumi.domain.usecase.RecoverLibraryTree
import com.agustin.sumi.feature.reader.ReaderBook
import com.agustin.sumi.feature.reader.ReaderRecoverySelection
import com.agustin.sumi.feature.reader.ReaderViewModel
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SumiApplication : Application() {
    internal val librarySortPreference by lazy {
        LocalLibrarySortPreference(getSharedPreferences("library_preferences", MODE_PRIVATE))
    }

    private val database by lazy { LibraryDatabase.open(this, "sumi-library.db") }

    internal val libraryRepository by lazy { RoomLibraryRepository(database.libraryDao()) }

    internal val readerProgressRepository: ReaderProgressRepository by lazy {
        RoomReaderProgressRepository(database.readerProgressDao())
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

    internal val readerRepository: ReaderRepository by lazy {
        LocalReaderRepository(libraryRepository::findById, documents, work)
    }

    internal val readerRenderer by lazy { NativeReaderRenderer(readerRepository) { covers.value.retainedBytes } }

    internal val readerViewModelFactory = viewModelFactory {
        initializer {
            ReaderViewModel(createSavedStateHandle(), readerRepository, readerProgressRepository, readerRenderer,
                ::findReaderBook, ::recoverReaderBook)
        }
    }

    private suspend fun findReaderBook(id: LibraryEntryId): ReaderResult<ReaderBook> = try {
        libraryRepository.findById(id)?.let { ReaderResult.Success(ReaderBook(it.title, it.source.grantReference)) }
            ?: ReaderResult.Failure(ReaderError.ENTRY_NOT_FOUND)
    } catch (_: SQLiteException) {
        currentCoroutineContext().ensureActive()
        ReaderResult.Failure(ReaderError.STORAGE_FAILURE)
    }

    private suspend fun recoverReaderBook(
        id: LibraryEntryId, book: ReaderBook, selection: ReaderRecoverySelection,
    ): ReaderResult<Unit> = when (selection) {
        is ReaderRecoverySelection.Document -> when (val result = importIntoLibrary.replace(id, selection.source)) {
            ImportIntoLibrary.Result.Recovered -> ReaderResult.Success(Unit)
            ImportIntoLibrary.Result.EntryNotFound -> ReaderResult.Failure(ReaderError.ENTRY_NOT_FOUND)
            ImportIntoLibrary.Result.StorageFailure -> ReaderResult.Failure(ReaderError.STORAGE_FAILURE)
            is ImportIntoLibrary.Result.Rejected -> ReaderResult.Failure(when (result.error) {
                CbzImportError.ACCESS_DENIED, CbzImportError.PERSISTENT_PERMISSION_UNAVAILABLE -> ReaderError.ACCESS_DENIED
                CbzImportError.SOURCE_UNAVAILABLE -> ReaderError.SOURCE_UNAVAILABLE
                CbzImportError.IO_FAILURE -> ReaderError.IO_FAILURE
                CbzImportError.INSUFFICIENT_SPACE -> ReaderError.INSUFFICIENT_SPACE
                CbzImportError.RESOURCE_LIMIT_EXCEEDED -> ReaderError.RESOURCE_LIMIT_EXCEEDED
                else -> ReaderError.INVALID_ARCHIVE
            })
            else -> ReaderResult.Failure(ReaderError.SOURCE_UNAVAILABLE)
        }
        is ReaderRecoverySelection.Tree -> {
            val grant = book.treeGrant
            if (grant != null && selection.offersPersistentRead && recoverLibraryTree(grant, selection.source).recovered > 0) {
                ReaderResult.Success(Unit)
            } else ReaderResult.Failure(ReaderError.SOURCE_UNAVAILABLE)
        }
    }

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

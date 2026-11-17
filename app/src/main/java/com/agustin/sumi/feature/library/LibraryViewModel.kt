package com.agustin.sumi.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryTreeSource
import com.agustin.sumi.domain.usecase.ImportLibraryBatch
import com.agustin.sumi.domain.usecase.LibraryBatchProgress
import com.agustin.sumi.domain.usecase.LibraryBatchStatus
import com.agustin.sumi.domain.usecase.LibraryImportBatch
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal sealed interface LibraryUiState {
    data object Idle : LibraryUiState
    data object Importing : LibraryUiState
    data object PickerUnavailable : LibraryUiState
    data object InvalidIdentity : LibraryUiState
    data object Duplicate : LibraryUiState
    data object StorageFailure : LibraryUiState
    data class Imported(val title: LibraryTitle, val pageCount: Int) : LibraryUiState
    data class Failed(val error: CbzImportError) : LibraryUiState
    data class BatchImporting(val progress: LibraryBatchProgress) : LibraryUiState
    data class BatchFinished(val progress: LibraryBatchProgress) : LibraryUiState
}

internal class LibraryViewModel(
    private val importIntoLibrary: ImportIntoLibrary,
    private val importLibraryBatch: ImportLibraryBatch,
    private val untitled: LibraryTitle,
    private val trees: LibraryTreeSource? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow<LibraryUiState>(LibraryUiState.Idle)
    val state = mutableState.asStateFlow()
    private var activeBatch: LibraryImportBatch? = null

    fun selectDocument(source: CbzSourceReference?) {
        val previous = mutableState.value
        if (source == null || previous == LibraryUiState.Importing || previous is LibraryUiState.BatchImporting) return
        mutableState.value = LibraryUiState.Importing
        viewModelScope.launch {
            try {
                mutableState.value = when (val result = importIntoLibrary(source, untitled)) {
                    is ImportIntoLibrary.Result.Saved -> LibraryUiState.Imported(result.title, result.pageCount)
                    is ImportIntoLibrary.Result.Rejected -> LibraryUiState.Failed(result.error)
                    ImportIntoLibrary.Result.InvalidIdentity -> LibraryUiState.InvalidIdentity
                    ImportIntoLibrary.Result.Duplicate -> LibraryUiState.Duplicate
                    ImportIntoLibrary.Result.StorageFailure -> LibraryUiState.StorageFailure
                    ImportIntoLibrary.Result.Recovered, ImportIntoLibrary.Result.EntryNotFound ->
                        error("Unexpected recovery result for a new import")
                }
            } finally {
                if (mutableState.value == LibraryUiState.Importing) mutableState.value = previous
            }
        }
    }

    fun selectDocuments(sources: List<CbzSourceReference>?) {
        if (sources.isNullOrEmpty() || mutableState.value == LibraryUiState.Importing || mutableState.value is LibraryUiState.BatchImporting) return
        observeBatch(importLibraryBatch.start(
            scope = viewModelScope,
            candidates = sources.asFlow(),
            fallback = untitled,
            totalCandidates = sources.size,
        ))
    }

    fun selectTree(tree: CbzSourceReference?) {
        if (tree == null || trees == null || mutableState.value == LibraryUiState.Importing ||
            mutableState.value is LibraryUiState.BatchImporting
        ) return
        observeBatch(importLibraryBatch.startTree(viewModelScope, tree, trees, untitled))
    }

    private fun observeBatch(batch: LibraryImportBatch?) {
        if (batch == null) return
        activeBatch = batch
        mutableState.value = LibraryUiState.BatchImporting(batch.progress.value)
        viewModelScope.launch {
            val completed = batch.progress.first {
                when (it.status) {
                    LibraryBatchStatus.RUNNING, LibraryBatchStatus.CANCELLING -> {
                        mutableState.value = LibraryUiState.BatchImporting(it)
                        false
                    }
                    LibraryBatchStatus.COMPLETED, LibraryBatchStatus.CANCELLED, LibraryBatchStatus.FAILED -> true
                }
            }
            activeBatch = null
            mutableState.value = LibraryUiState.BatchFinished(completed)
        }
    }

    fun cancelBatch() {
        activeBatch?.cancel()
    }

    fun pickerUnavailable() {
        if (mutableState.value != LibraryUiState.Importing && mutableState.value !is LibraryUiState.BatchImporting) {
            mutableState.value = LibraryUiState.PickerUnavailable
        }
    }

    fun treePermissionUnavailable() {
        if (mutableState.value != LibraryUiState.Importing && mutableState.value !is LibraryUiState.BatchImporting) {
            mutableState.value = LibraryUiState.Failed(CbzImportError.PERSISTENT_PERMISSION_UNAVAILABLE)
        }
    }
}

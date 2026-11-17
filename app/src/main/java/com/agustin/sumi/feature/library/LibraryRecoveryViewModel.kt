package com.agustin.sumi.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySortOrder
import com.agustin.sumi.domain.repository.LibraryAccessStatus
import com.agustin.sumi.domain.repository.LibraryDocumentAccess
import com.agustin.sumi.domain.repository.LibraryEntryRemoval
import com.agustin.sumi.domain.repository.LibraryRemovalResult
import com.agustin.sumi.domain.repository.LibraryRepository
import com.agustin.sumi.domain.repository.LibrarySortPreference
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import com.agustin.sumi.domain.usecase.RecoverLibraryTree
import com.agustin.sumi.domain.usecase.TreeRecoveryResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CheckedLibraryEntry(val entry: LibraryEntry, val access: LibraryAccessStatus? = null)

internal data class LibraryRecoveryState(
    val entries: List<CheckedLibraryEntry> = emptyList(),
    val offset: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = true,
    val searchText: String = "",
    val order: LibrarySortOrder = LibrarySortOrder.RECENTLY_ADDED,
    val savingOrder: Boolean = false,
    val orderSaveFailed: Boolean = false,
    val loadFailed: Boolean = false,
    val replacing: Boolean = false,
    val pickerUnavailable: Boolean = false,
    val result: ImportIntoLibrary.Result? = null,
    val removing: Boolean = false,
    val removalResult: LibraryRemovalResult? = null,
    val treeResult: TreeRecoveryResult? = null,
)

internal class LibraryRecoveryViewModel(
    private val library: LibraryRepository,
    private val access: LibraryDocumentAccess,
    private val import: ImportIntoLibrary,
    private val removal: LibraryEntryRemoval,
    private val sortPreference: LibrarySortPreference,
    private val recoverTree: RecoverLibraryTree? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryRecoveryState())
    val state = mutableState.asStateFlow()
    private var checking: Job? = null
    private var pendingSelection: LibraryEntryId? = null
    private var pendingTree: LibraryGrantReference? = null
    private val selecting get() = pendingSelection != null || pendingTree != null
    private var ready = false

    init {
        viewModelScope.launch {
            val order = sortPreference.read()
            mutableState.update { it.copy(order = order) }
            ready = true
            load(0)
        }
    }

    fun refresh() {
        if (ready && !selecting && !state.value.replacing && !state.value.removing) load(state.value.offset)
    }

    fun search(text: String) {
        if (!ready || text == state.value.searchText) return
        mutableState.update { it.copy(searchText = text) }
        load(0, reset = true)
    }

    fun selectOrder(order: LibrarySortOrder) {
        if (!ready || state.value.savingOrder || state.value.order == order) return
        mutableState.update { it.copy(savingOrder = true, orderSaveFailed = false) }
        viewModelScope.launch {
            val saved = sortPreference.save(order)
            mutableState.update {
                it.copy(order = if (saved) order else it.order, savingOrder = false, orderSaveFailed = !saved)
            }
            if (saved) load(0, reset = true)
        }
    }

    fun changePage(forward: Boolean) {
        val current = state.value
        if (selecting || current.replacing || current.removing) return
        if (forward && current.hasNext) load(current.offset + PAGE_SIZE)
        if (!forward && current.offset > 0) load(current.offset - PAGE_SIZE)
    }

    fun beginReselection(id: LibraryEntryId): Boolean {
        val current = state.value
        if (selecting || current.replacing || current.removing) return false
        val status = current.entries.firstOrNull { it.entry.id == id }?.access
        if (status != LibraryAccessStatus.MISSING && status != LibraryAccessStatus.PERMISSION_REVOKED) return false
        pendingSelection = id
        return true
    }

    fun pickerUnavailable() {
        pendingSelection = null
        pendingTree = null
        mutableState.update { it.copy(pickerUnavailable = true, result = null, treeResult = null) }
    }

    fun beginTreeReselection(id: LibraryEntryId): Boolean {
        val current = state.value
        if (recoverTree == null || selecting || current.replacing || current.removing) return false
        val item = current.entries.firstOrNull { it.entry.id == id } ?: return false
        if (item.access != LibraryAccessStatus.MISSING && item.access != LibraryAccessStatus.PERMISSION_REVOKED) return false
        pendingTree = item.entry.source.grantReference ?: return false
        return true
    }

    fun reselectTree(selection: LibraryTreeSelection?) {
        val original = pendingTree ?: return
        pendingTree = null
        if (selection == null || recoverTree == null) return
        mutableState.update {
            it.copy(replacing = true, pickerUnavailable = false, result = null, removalResult = null, treeResult = null)
        }
        viewModelScope.launch {
            try {
                val result = if (selection.offersPersistentRead) recoverTree(original, selection.source)
                else TreeRecoveryResult(0, null, true)
                mutableState.update { it.copy(treeResult = result) }
            } finally {
                mutableState.update { it.copy(replacing = false) }
            }
            refresh()
        }
    }

    fun reselect(source: CbzSourceReference?) {
        val id = pendingSelection ?: return
        pendingSelection = null
        if (source == null) return
        mutableState.update { it.copy(replacing = true, pickerUnavailable = false, result = null, removalResult = null, treeResult = null) }
        viewModelScope.launch {
            try {
                val result = import.replace(id, source)
                mutableState.update { it.copy(result = result) }
            } finally {
                mutableState.update { it.copy(replacing = false) }
            }
            if (state.value.result == ImportIntoLibrary.Result.Recovered) refresh()
        }
    }

    fun remove(id: LibraryEntryId) {
        val current = state.value
        if (selecting || current.replacing || current.removing) return
        if (current.entries.none { it.entry.id == id }) return
        mutableState.update { it.copy(removing = true, removalResult = null, result = null, pickerUnavailable = false, treeResult = null) }
        viewModelScope.launch {
            try {
                val result = removal.remove(id)
                mutableState.update { it.copy(removalResult = result) }
            } finally {
                mutableState.update { it.copy(removing = false) }
            }
        }
    }

    private fun load(offset: Int, reset: Boolean = false) {
        checking?.cancel()
        val filters = state.value
        mutableState.update {
            it.copy(entries = if (reset) emptyList() else it.entries, loading = reset || it.entries.isEmpty(), loadFailed = false)
        }
        checking = viewModelScope.launch {
            library.observeEntries(LibraryQuery(filters.searchText, filters.order, offset, PAGE_SIZE + 1))
                .distinctUntilChanged()
                .catch { mutableState.update { it.copy(loadFailed = true, loading = false) } }
                .collectLatest { rows ->
                    if (rows.isEmpty() && offset > 0) {
                        load(offset - PAGE_SIZE)
                        return@collectLatest
                    }
                    val page = rows.take(PAGE_SIZE)
                    mutableState.update {
                        it.copy(
                            entries = page.map(::CheckedLibraryEntry),
                            offset = offset,
                            hasNext = rows.size > PAGE_SIZE,
                            loading = false,
                        )
                    }
                    for ((index, entry) in page.withIndex()) {
                        val result = access.check(entry.source.reference)
                        mutableState.update {
                            val updated = it.entries.toMutableList()
                            updated[index] = CheckedLibraryEntry(entry, result)
                            it.copy(entries = updated)
                        }
                    }
                }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

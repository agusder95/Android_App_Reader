package com.agustin.sumi.feature.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.agustin.sumi.R
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibrarySortOrder
import com.agustin.sumi.domain.repository.LibraryAccessStatus
import com.agustin.sumi.domain.repository.LibraryCoverSource
import com.agustin.sumi.domain.repository.LibraryRemovalResult
import com.agustin.sumi.domain.usecase.ImportIntoLibrary

@Composable
internal fun LibraryRecoveryPanel(
    state: LibraryRecoveryState,
    covers: LibraryCoverSource<Bitmap>,
    enabled: Boolean,
    onSearch: (String) -> Unit,
    onOrder: (LibrarySortOrder) -> Unit,
    onRetry: () -> Unit,
    onPage: (Boolean) -> Unit,
    onReselect: (LibraryEntryId) -> Unit,
    onRemove: (LibraryEntryId) -> Unit,
    modifier: Modifier = Modifier,
    onReselectTree: (LibraryEntryId) -> Unit = {},
    importControls: @Composable () -> Unit,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.offset, state.order, state.searchText) { gridState.scrollToItem(0) }
    var confirmationId by rememberSaveable { mutableStateOf<Long?>(null) }
    val confirmation = state.entries.firstOrNull { it.entry.id.value == confirmationId }?.entry
    val feedback = when {
        state.removing -> R.string.library_remove_loading
        state.removalResult != null -> state.removalResult.messageResource()
        state.replacing -> R.string.library_recovery_loading
        state.pickerUnavailable -> R.string.cbz_picker_unavailable
        else -> state.result?.recoveryMessage()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        modifier = modifier.safeDrawingPadding().imePadding(),
        state = gridState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                importControls()
                Text(
                    stringResource(R.string.library_saved_documents),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = state.searchText,
                    onValueChange = onSearch,
                    label = { Text(stringResource(R.string.library_search)) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                )
                SortControl(state, enabled, onOrder)
                val status = when {
                    state.loadFailed -> R.string.library_recovery_read_failure
                    state.loading -> R.string.library_loading
                    state.entries.isEmpty() && state.searchText.isNotEmpty() -> R.string.library_no_matches
                    state.entries.isEmpty() -> R.string.library_empty
                    else -> null
                }
                for (message in listOfNotNull(feedback, status, R.string.library_sort_failure.takeIf { state.orderSaveFailed })) {
                    Text(stringResource(message), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                state.treeResult?.let { result ->
                    val message = result.remaining?.let {
                        stringResource(R.string.library_tree_recovery_result, result.recovered, it)
                    } ?: stringResource(R.string.library_tree_recovery_unavailable)
                    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (result.remaining != null && result.incomplete) {
                        Text(stringResource(R.string.library_tree_incomplete))
                    }
                }
                if (state.loadFailed) RecoveryAction(R.string.library_recovery_retry, enabled, onRetry)
                if (status == R.string.library_no_matches) {
                    RecoveryAction(R.string.library_search_clear, enabled) { onSearch("") }
                }
                if (state.offset > 0 || state.hasNext) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.offset > 0) RecoveryAction(R.string.library_recovery_previous, enabled) { onPage(false) }
                        if (state.hasNext) RecoveryAction(R.string.library_recovery_next, enabled) { onPage(true) }
                    }
                }
            }
        }
        items(state.entries, key = { it.entry.id.value }) { item ->
            val id = item.entry.id.value
            val visible by remember(gridState, id) {
                derivedStateOf { gridState.layoutInfo.visibleItemsInfo.any { it.key == id } }
            }
            Card(Modifier.fillMaxWidth()) {
                key(item.entry, item.access, visible) { CoverPreview(item, covers, visible) }
                Column(Modifier.padding(8.dp)) {
                    Text(item.entry.title.value, style = MaterialTheme.typography.titleMedium)
                    when (item.access) {
                        LibraryAccessStatus.MISSING, LibraryAccessStatus.PERMISSION_REVOKED -> {
                            val reason = if (item.access == LibraryAccessStatus.MISSING) {
                                R.string.library_recovery_missing
                            } else {
                                R.string.library_recovery_permission
                            }
                            Text(stringResource(reason))
                            TextButton(onClick = { onReselect(item.entry.id) }, modifier = Modifier.heightIn(min = 48.dp), enabled = enabled) {
                                Text(stringResource(R.string.library_recovery_select, item.entry.title.value))
                            }
                            if (item.entry.source.grantReference != null) {
                                TextButton(onClick = { onReselectTree(item.entry.id) }, modifier = Modifier.heightIn(min = 48.dp), enabled = enabled) {
                                    Text(stringResource(R.string.library_tree_recovery_select, item.entry.title.value))
                                }
                            }
                        }
                        LibraryAccessStatus.TEMPORARY_FAILURE -> {
                            Text(stringResource(R.string.library_recovery_temporary))
                            RecoveryAction(R.string.library_recovery_retry, enabled, onRetry)
                        }
                        LibraryAccessStatus.AVAILABLE, null -> Unit
                    }
                    TextButton(
                        onClick = { confirmationId = item.entry.id.value },
                        modifier = Modifier.heightIn(min = 48.dp),
                        enabled = enabled,
                    ) {
                        Text(stringResource(R.string.library_remove_action, item.entry.title.value))
                    }
                }
            }
        }
    }
    if (confirmation != null) {
        AlertDialog(
            onDismissRequest = { confirmationId = null },
            title = { Text(stringResource(R.string.library_remove_title, confirmation.title.value)) },
            text = { Text(stringResource(R.string.library_remove_explanation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmationId = null
                        onRemove(confirmation.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = enabled,
                ) { Text(stringResource(R.string.library_remove_confirm)) }
            },
            dismissButton = {
                RecoveryAction(R.string.library_remove_cancel, true) { confirmationId = null }
            },
        )
    }
}

@Composable
private fun CoverPreview(item: CheckedLibraryEntry, covers: LibraryCoverSource<Bitmap>, visible: Boolean) {
    var loading by remember { mutableStateOf(item.access == null || item.access == LibraryAccessStatus.AVAILABLE) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(covers) {
        if (visible && item.access == LibraryAccessStatus.AVAILABLE) {
            thumbnail = covers.load(item.entry.id)
            loading = false
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.8f),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val cover = thumbnail
            if (cover != null) {
                Image(
                    bitmap = cover.asImageBitmap(),
                    contentDescription = stringResource(R.string.library_cover_description, item.entry.title.value),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    stringResource(if (loading) R.string.library_cover_loading else R.string.library_cover_unavailable),
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun SortControl(state: LibraryRecoveryState, enabled: Boolean, onOrder: (LibrarySortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled && !state.savingOrder,
            modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.Button },
        ) { Text(stringResource(R.string.library_sort, stringResource(state.order.label()))) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (order in LibrarySortOrder.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(order.label())) },
                    onClick = {
                        expanded = false
                        onOrder(order)
                    },
                    modifier = Modifier.semantics { selected = state.order == order },
                )
            }
        }
    }
}

private fun LibrarySortOrder.label(): Int = when (this) {
    LibrarySortOrder.TITLE_ASCENDING -> R.string.library_sort_ascending
    LibrarySortOrder.TITLE_DESCENDING -> R.string.library_sort_descending
    LibrarySortOrder.RECENTLY_ADDED -> R.string.library_sort_recent
}
@Composable
private fun RecoveryAction(label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), enabled = enabled) {
        Text(stringResource(label))
    }
}

private fun ImportIntoLibrary.Result.recoveryMessage(): Int? = when (this) {
    ImportIntoLibrary.Result.Recovered -> R.string.library_recovery_done
    ImportIntoLibrary.Result.Duplicate -> R.string.library_recovery_conflict
    ImportIntoLibrary.Result.EntryNotFound -> R.string.library_recovery_entry_missing
    ImportIntoLibrary.Result.InvalidIdentity -> R.string.library_import_invalid_identity
    ImportIntoLibrary.Result.StorageFailure -> R.string.library_import_storage_failure
    is ImportIntoLibrary.Result.Rejected -> error.messageResource()
    is ImportIntoLibrary.Result.Saved -> null
}

private fun LibraryRemovalResult.messageResource(): Int = when (this) {
    LibraryRemovalResult.REMOVED -> R.string.library_remove_done
    LibraryRemovalResult.CLEANUP_PENDING -> R.string.library_remove_cleanup_pending
    LibraryRemovalResult.ENTRY_NOT_FOUND -> R.string.library_recovery_entry_missing
    LibraryRemovalResult.STORAGE_FAILURE -> R.string.library_remove_failure
}

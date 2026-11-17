package com.agustin.sumi.feature.library

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.agustin.sumi.R
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.repository.LibraryCoverSource
import com.agustin.sumi.domain.usecase.BATCH_CANDIDATE_LIMIT
import com.agustin.sumi.domain.usecase.LibraryBatchProgress
import com.agustin.sumi.domain.usecase.LibraryBatchStatus

@Composable
internal fun LibraryRoute(viewModel: LibraryViewModel, recovery: LibraryRecoveryViewModel, covers: LibraryCoverSource<Bitmap>) {
    val state by viewModel.state.collectAsState()
    val recoveryState by recovery.state.collectAsState()
    val picker = rememberLauncherForActivityResult(OpenCbzDocument) { uri ->
        viewModel.selectDocument(uri?.let { CbzSourceReference(it.toString()) })
    }
    val multiPicker = rememberLauncherForActivityResult(OpenCbzDocuments) { uris ->
        viewModel.selectDocuments(uris?.map { CbzSourceReference(it.toString()) })
    }
    val replacementPicker = rememberLauncherForActivityResult(OpenCbzDocument) { uri ->
        recovery.reselect(uri?.let { CbzSourceReference(it.toString()) })
    }
    val treePicker = rememberLauncherForActivityResult(OpenLibraryTree) { selection ->
        if (selection != null) {
            if (selection.offersPersistentRead) viewModel.selectTree(selection.source)
            else viewModel.treePermissionUnavailable()
        }
    }
    val replacementTreePicker = rememberLauncherForActivityResult(OpenLibraryTree, recovery::reselectTree)
    val enabled = state !is LibraryUiState.BatchImporting && state != LibraryUiState.Importing &&
        !recoveryState.replacing && !recoveryState.removing
    LibraryRecoveryPanel(
        recoveryState,
        covers,
        enabled = enabled,
        onSearch = recovery::search,
        onOrder = recovery::selectOrder,
        onRetry = recovery::refresh,
        onPage = recovery::changePage,
        onRemove = recovery::remove,
        onReselectTree = { id ->
            if (recovery.beginTreeReselection(id)) {
                try {
                    replacementTreePicker.launch(Unit)
                } catch (_: ActivityNotFoundException) {
                    recovery.pickerUnavailable()
                }
            }
        },
        onReselect = { id ->
            if (recovery.beginReselection(id)) {
                try {
                    replacementPicker.launch(Unit)
                } catch (_: ActivityNotFoundException) {
                    recovery.pickerUnavailable()
                }
            }
        },
    ) {
        ImportControls(state, enabled, onSelectDocument = {
            try {
                picker.launch(Unit)
            } catch (_: ActivityNotFoundException) {
                viewModel.pickerUnavailable()
            }
        }, onSelectDocuments = {
            try {
                multiPicker.launch(Unit)
            } catch (_: ActivityNotFoundException) {
                viewModel.pickerUnavailable()
            }
        }, onSelectTree = {
            try {
                treePicker.launch(Unit)
            } catch (_: ActivityNotFoundException) {
                viewModel.pickerUnavailable()
            }
        }, onCancelBatch = viewModel::cancelBatch)
    }
}

@Composable
internal fun ImportControls(
    state: LibraryUiState,
    enabled: Boolean,
    onSelectDocument: () -> Unit,
    onSelectDocuments: () -> Unit,
    onSelectTree: () -> Unit,
    onCancelBatch: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state == LibraryUiState.Importing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                text = when (state) {
                    LibraryUiState.Idle -> stringResource(R.string.cbz_import_prompt)
                    LibraryUiState.Importing -> stringResource(R.string.cbz_import_loading)
                    LibraryUiState.PickerUnavailable -> stringResource(R.string.cbz_picker_unavailable)
                    LibraryUiState.InvalidIdentity -> stringResource(R.string.library_import_invalid_identity)
                    LibraryUiState.Duplicate -> stringResource(R.string.library_import_duplicate)
                    LibraryUiState.StorageFailure -> stringResource(R.string.library_import_storage_failure)
                    is LibraryUiState.Imported -> pluralStringResource(
                        R.plurals.cbz_import_success,
                        state.pageCount,
                        state.pageCount,
                    )
                    is LibraryUiState.Failed -> stringResource(state.error.messageResource())
                    is LibraryUiState.BatchImporting -> batchStatusText(state.progress)
                    is LibraryUiState.BatchFinished -> batchSummary(state.progress)
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = when (state) {
                    is LibraryUiState.Failed,
                    LibraryUiState.PickerUnavailable,
                    LibraryUiState.InvalidIdentity,
                    LibraryUiState.StorageFailure,
                    -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (state is LibraryUiState.BatchImporting) BatchIndicator(state.progress)
        if (state is LibraryUiState.BatchFinished && state.progress.totalCandidates == null &&
            state.progress.discoveryIncomplete && !state.progress.discoveryFailed
        ) {
            Text(
                stringResource(R.string.library_tree_incomplete),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (state is LibraryUiState.BatchFinished) BatchDetails(state.progress)
        if (state is LibraryUiState.Imported) {
            Text(state.title.value, modifier = Modifier.padding(bottom = 16.dp))
        }
        Button(
            onClick = onSelectDocument,
            modifier = Modifier.heightIn(min = 48.dp),
            enabled = enabled,
        ) {
            val label = when (state) {
                LibraryUiState.Idle, LibraryUiState.Importing, LibraryUiState.PickerUnavailable -> R.string.cbz_import_action
                else -> R.string.cbz_import_choose_another
            }
            Text(stringResource(label))
        }
        androidx.compose.material3.TextButton(
            onClick = onSelectDocuments,
            modifier = Modifier.heightIn(min = 48.dp),
            enabled = enabled,
        ) {
            Text(stringResource(R.string.cbz_import_multiple_action))
        }
        androidx.compose.material3.TextButton(
            onClick = onSelectTree,
            modifier = Modifier.heightIn(min = 48.dp),
            enabled = enabled,
        ) {
            Text(stringResource(R.string.library_import_tree))
        }
        if (state is LibraryUiState.BatchImporting) {
            OutlinedButton(
                onClick = onCancelBatch,
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = state.progress.status == LibraryBatchStatus.RUNNING,
            ) {
                Text(stringResource(R.string.cbz_batch_cancel))
            }
        }
    }
}

@Composable
private fun batchStatusText(progress: LibraryBatchProgress): String = when {
    progress.status == LibraryBatchStatus.CANCELLING -> stringResource(R.string.cbz_batch_cancelling)
    progress.discovered == 0 -> stringResource(R.string.cbz_batch_starting)
    else -> batchCounts(progress)
}

@Composable
private fun batchCounts(progress: LibraryBatchProgress): String {
    val total = progress.totalCandidates?.coerceAtMost(BATCH_CANDIDATE_LIMIT)
    return if (total == null) {
        stringResource(R.string.cbz_batch_folder_progress, progress.processed)
    } else stringResource(R.string.cbz_batch_known_progress, progress.processed, total)
}

@Composable
private fun BatchIndicator(progress: LibraryBatchProgress) {
    val total = progress.totalCandidates?.coerceIn(1, BATCH_CANDIDATE_LIMIT)
    if (progress.status == LibraryBatchStatus.CANCELLING) Text(batchCounts(progress))
    if (total == null || progress.status == LibraryBatchStatus.CANCELLING) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(
            progress = { (progress.processed.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun batchSummary(progress: LibraryBatchProgress): String {
    val message = when {
        progress.status == LibraryBatchStatus.CANCELLED -> R.string.cbz_batch_cancelled_summary
        progress.discoveryFailed && progress.discovered == 0 -> return stringResource(R.string.library_tree_open_failure)
        progress.status == LibraryBatchStatus.FAILED -> R.string.cbz_batch_failed_summary
        progress.totalCandidates == null && progress.discovered == 0 && !progress.discoveryIncomplete ->
            return stringResource(R.string.cbz_batch_empty_folder)
        else -> R.string.cbz_batch_summary
    }
    return stringResource(message, progress.imported, progress.duplicates, progress.failed, progress.pendingKnown)
}

@Composable
private fun BatchDetails(progress: LibraryBatchProgress) {
    if (progress.omittedKnown + progress.skippedNodes > 0) {
        Text(
            stringResource(R.string.cbz_batch_omissions, progress.omittedKnown + progress.skippedNodes),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (progress.limitReached) Text(stringResource(R.string.cbz_batch_limit))
    if (progress.discoveryFailed) Text(stringResource(R.string.cbz_batch_discovery_failed))
    BatchError(R.string.library_import_invalid_identity, progress.invalidIdentities)
    BatchError(R.string.library_import_storage_failure, progress.storageFailures)
    for ((message, errors) in progress.archiveErrors.entries.groupBy { it.key.messageResource() }) {
        BatchError(message, errors.sumOf { it.value })
    }
}

@Composable
private fun BatchError(message: Int, count: Int) {
    if (count > 0) Text(stringResource(R.string.cbz_batch_error_details, count, stringResource(message)))
}

internal fun CbzImportError.messageResource(): Int = when (this) {
    CbzImportError.PERSISTENT_PERMISSION_UNAVAILABLE,
    CbzImportError.ACCESS_DENIED,
    CbzImportError.SOURCE_UNAVAILABLE,
    -> R.string.cbz_import_error_access
    CbzImportError.EMPTY_FILE, CbzImportError.EMPTY_ARCHIVE -> R.string.cbz_import_error_empty
    CbzImportError.NO_SUPPORTED_IMAGES -> R.string.cbz_import_error_no_images
    CbzImportError.RESOURCE_LIMIT_EXCEEDED -> R.string.cbz_import_error_limit
    CbzImportError.INSUFFICIENT_SPACE -> R.string.cbz_import_error_space
    CbzImportError.IO_FAILURE -> R.string.cbz_import_error_read
    CbzImportError.INVALID_ARCHIVE,
    CbzImportError.CORRUPT_ARCHIVE,
    CbzImportError.UNSUPPORTED_ARCHIVE,
    CbzImportError.UNSAFE_ENTRY_PATH,
    CbzImportError.DUPLICATE_ENTRY_PATH,
    -> R.string.cbz_import_error_invalid
}

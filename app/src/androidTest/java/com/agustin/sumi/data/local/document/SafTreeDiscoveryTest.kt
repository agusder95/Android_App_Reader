package com.agustin.sumi.data.local.document

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryAccessStatus
import com.agustin.sumi.domain.repository.LibraryTreeSource
import com.agustin.sumi.domain.repository.TreeDiscovery
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import com.agustin.sumi.domain.usecase.LibraryBatchStatus
import com.agustin.sumi.domain.usecase.LibraryImportBatch
import com.agustin.sumi.feature.library.OpenLibraryTree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafTreeDiscoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = TreeLibraryChecks(context)
    private val title = LibraryTitle("Fixture")

    @Test
    fun folderPickerRequiresAnActualSelectionAndPreservesReadFlags() {
        val intent = OpenLibraryTree.createIntent(context, Unit)
        check(intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE && intent.type == null)
        check(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        val root = tree(PickerFixtureProvider.SCAN_ROOT)
        val result = Intent().setData(Uri.parse(root.value)).setFlags(intent.flags)
        check(OpenLibraryTree.parseResult(Activity.RESULT_CANCELED, result) == null)
        check(OpenLibraryTree.parseResult(Activity.RESULT_OK, Intent()) == null)
        val selected = checkNotNull(OpenLibraryTree.parseResult(Activity.RESULT_OK, result))
        check(selected.source == root && selected.offersPersistentRead)
        result.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        check(OpenLibraryTree.parseResult(Activity.RESULT_OK, result)?.offersPersistentRead == false)
    }

    @Test
    fun streamsAccessibleCandidatesAndSkipsUnsafeBranches() = runBlocking {
        withFixture("scan-tree-test") {
            val root = tree(PickerFixtureProvider.SCAN_ROOT)
            fixture.offered("picker-transient", PickerFixtureProvider.SCAN_ROOT)
            val denied = checkNotNull(batches.startTree(this@runBlocking, root, trees, title)).finished()
            check(denied.discoveryFailed && denied.imported == 0 && entries().isEmpty())
            fixture.offered("picker-grant", PickerFixtureProvider.SCAN_ROOT)
            val progress = checkNotNull(batches.startTree(this@runBlocking, root, trees, title)).finished()
            check(progress.imported == 2 && progress.duplicates == 1 && progress.failed == 2) { progress }
            check(progress.skippedNodes == 6 && progress.limitReached && progress.discoveryIncomplete) { progress }
            check(entries().map { it.source.identity.documentId }.toSet() ==
                setOf(PickerFixtureProvider.TREE_CBZ, PickerFixtureProvider.NESTED))
            check(entries().all { it.source.grantReference?.value == root.value })
            for (entry in entries()) {
                check(access.check(entry.source.reference) == LibraryAccessStatus.AVAILABLE)
            }
        }
    }

    @Test
    fun limitsRowsAndCandidatesBeforeDeduplication() = runBlocking {
        withFixture("scan-limits-test") {
            for ((rootId, expected) in listOf(
                PickerFixtureProvider.NODE_ROOT to 1, PickerFixtureProvider.CANDIDATE_ROOT to 1000,
            )) {
                fixture.offered("picker-grant", rootId)
                val root = tree(rootId)
                var count = 0
                var truncated = false
                withContext(Dispatchers.IO) {
                    trees.withTree(root) { events ->
                        events.collect { event ->
                            when (event) {
                                is TreeDiscovery.Candidate -> count++
                                is TreeDiscovery.Omitted -> truncated = truncated || event.limited
                            }
                        }
                    }
                }
                check(count == expected && truncated)
                check(documents.persistedReadTime(root) == null)
            }
        }
    }

    @Test
    fun reselectionReconnectsOnlyExistingIdentitiesFromAffectedTree() = runBlocking {
        withFixture("scan-recovery-test") {
            fixture.offered("picker-grant")
            check(import(first) is ImportIntoLibrary.Result.Saved)
            check(import(second) is ImportIntoLibrary.Result.Saved)
            val originals = entries().associateBy { it.source.identity }
            fixture.offered("picker-revoke")
            check(access.check(first) == LibraryAccessStatus.PERMISSION_REVOKED)
            fixture.offered("picker-grant", PickerFixtureProvider.RECOVERY_ROOT)
            val replacement = tree(PickerFixtureProvider.RECOVERY_ROOT)
            val result = recover(LibraryGrantReference(tree.toString()), replacement)
            check(result.recovered == 1 && result.remaining == 1 && !result.incomplete) { result }
            check(entries().size == 2)
            for (entry in entries()) {
                val original = originals.getValue(entry.source.identity)
                check(entry.id == original.id && entry.title == original.title && entry.addedAtEpochMillis == original.addedAtEpochMillis)
                if (entry.source.identity.documentId == PickerFixtureProvider.TREE_CBZ) {
                    check(entry.source.grantReference?.value == replacement.value)
                    check(access.check(entry.source.reference) == LibraryAccessStatus.AVAILABLE)
                } else {
                    check(entry == original)
                    check(access.check(entry.source.reference) == LibraryAccessStatus.PERMISSION_REVOKED)
                }
            }
        }
    }

    @Test
    fun cancellationReleasesDiscoveryLeaseAndPreservesConfirmedImport() = runBlocking {
        withFixture("scan-cancel-test") {
            fixture.offered("picker-grant")
            val blocked = CompletableDeferred<Unit>()
            val gate = object : LibraryTreeSource {
                override suspend fun <T> withTree(tree: CbzSourceReference, consume: suspend (Flow<TreeDiscovery>) -> T): T =
                    trees.withTree(tree) { events ->
                        consume(flow {
                            events.collect {
                                emit(it)
                                blocked.complete(Unit)
                                awaitCancellation()
                            }
                        })
                    }
            }
            val batch = checkNotNull(batches.startTree(this@runBlocking, grant, gate, title))
            withTimeout(10_000) {
                blocked.await()
                batch.progress.first { it.imported == 1 }
                batch.cancel()
                check(batch.finished().status == LibraryBatchStatus.CANCELLED)
            }
            val imported = entries().single()
            check(imported.source.reference == first)
            check(documents.persistedReadTime(grant) != null)
            val secondBatch = checkNotNull(batches.startTree(this@runBlocking, grant, gate, title))
            withTimeout(10_000) {
                secondBatch.progress.first { it.duplicates == 1 }
                maintenance.remove(imported.id)
                check(entries().isEmpty())
                check(documents.persistedReadTime(grant) != null)
                secondBatch.cancel()
                check(secondBatch.finished().status == LibraryBatchStatus.CANCELLED)
            }
            check(documents.persistedReadTime(grant) == null)
        }
    }

    private suspend fun withFixture(name: String, block: suspend TreeLibraryChecks.LibraryFixture.() -> Unit) {
        fixture.clean(name)
        try {
            fixture.withLibrary(name, block)
        } finally {
            fixture.clean(name)
        }
    }

    private suspend fun LibraryImportBatch.finished() = withTimeout(30_000) {
        progress.first { it.status != LibraryBatchStatus.RUNNING && it.status != LibraryBatchStatus.CANCELLING }
    }

    private fun tree(root: String) =
        CbzSourceReference(DocumentsContract.buildTreeDocumentUri(PickerFixtureProvider.AUTHORITY, root).toString())
}

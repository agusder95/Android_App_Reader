package com.agustin.sumi.feature.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.room.Room
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.MainActivity
import com.agustin.sumi.R
import com.agustin.sumi.core.ui.theme.SumiTheme
import com.agustin.sumi.data.local.database.LibraryDatabase
import com.agustin.sumi.data.local.preferences.LocalLibrarySortPreference
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySortOrder
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryAccessStatus
import com.agustin.sumi.domain.repository.LibraryCoverSource
import com.agustin.sumi.domain.repository.CbzImportRepository
import com.agustin.sumi.domain.repository.LibraryDocumentMetadata
import com.agustin.sumi.domain.repository.LibraryTreeSource
import com.agustin.sumi.domain.repository.TreeDiscovery
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import com.agustin.sumi.domain.usecase.ImportLibraryBatch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryGridTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun gridAdaptsAtTwoWidthsAndRequestsVisibleCovers() {
        val requests = ConcurrentLinkedQueue<LibraryEntryId>()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val covers = LibraryCoverSource {
            requests.add(it)
            bitmap
        }
        val width = mutableIntStateOf(300)
        val rows = (1L..20L).map { checked(it) }
        withScreen({
            LibraryRecoveryPanel(
                LibraryRecoveryState(entries = rows, loading = false), covers, true,
                {}, {}, {}, {}, {}, {},
                modifier = Modifier.width(width.intValue.dp).fillMaxHeight(),
            ) {}
        }) {
            val narrow = bounds(awaitNode(context.getString(R.string.library_cover_description, "Libro 1")))
            instrumentation.runOnMainSync { width.intValue = 390 }
            val second = bounds(awaitNode(context.getString(R.string.library_cover_description, "Libro 2")) {
                bounds(it).left > narrow.left
            })
            val wide = bounds(awaitNode(context.getString(R.string.library_cover_description, "Libro 1")))
            assertTrue("300 dp=$narrow, 390 dp=$wide", narrow.width() > wide.width())
            assertEquals(wide.top, second.top)
            assertTrue(second.left > wide.left)
            assertTrue(rows.last().entry.id !in requests)
        }
    }

    @Test
    fun statusMessagesAndActionsAreExposedToAccessibility() {
        val state = mutableStateOf(LibraryRecoveryState())
        var retries = 0
        val covers = LibraryCoverSource<Bitmap> { null }
        withScreen({
            LibraryRecoveryPanel(
                state.value, covers, true,
                onSearch = { state.value = state.value.copy(searchText = it) },
                onOrder = { state.value = state.value.copy(order = it) },
                onRetry = { retries++ },
                onPage = {},
                onReselect = {},
                onRemove = {},
                modifier = Modifier.fillMaxHeight(),
            ) {}
        }) {
            awaitNode(context.getString(R.string.library_loading))
            instrumentation.runOnMainSync { state.value = LibraryRecoveryState(loading = false) }
            awaitNode(context.getString(R.string.library_empty))
            instrumentation.runOnMainSync { state.value = state.value.copy(searchText = "ausente") }
            awaitNode(context.getString(R.string.library_no_matches))
            click(R.string.library_search_clear)
            awaitNode(context.getString(R.string.library_empty))
            instrumentation.runOnMainSync { state.value = state.value.copy(loadFailed = true) }
            awaitNode(context.getString(R.string.library_recovery_read_failure))
            click(R.string.library_recovery_retry)
            instrumentation.waitForIdleSync()
            assertEquals(1, retries)
            val order = context.getString(R.string.library_sort, context.getString(R.string.library_sort_recent))
            val orderButton = clickable(awaitNode(order))
            assertTrue(orderButton.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
            assertTrue(bounds(orderButton).height() >= (context.resources.displayMetrics.density * 48).toInt())
            assertTrue(orderButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            click(R.string.library_sort_descending)
            awaitNode(context.getString(R.string.library_sort, context.getString(R.string.library_sort_descending)))
        }
    }

    @Test
    fun orderPreferenceSurvivesRecreatingTheAdapter() = runBlocking {
        val name = "test-library-order-${UUID.randomUUID()}"
        fun preference() = LocalLibrarySortPreference(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        try {
            assertEquals(LibrarySortOrder.RECENTLY_ADDED, preference().read())
            for (order in LibrarySortOrder.entries) {
                assertTrue(preference().save(order))
                assertEquals(order, preference().read())
            }
        } finally {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        Unit
    }

    @Test
    fun batchShowsLiveCountsAndWaitsForBlockedReadBeforeAnnouncingCancellation() {
        val first = CbzSourceReference("first")
        val blocked = CbzSourceReference("blocked")
        val queued = CbzSourceReference("queued")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = ConcurrentLinkedQueue<CbzSourceReference>()
        withImportDatabase { database ->
            val repository = RoomLibraryRepository(database.libraryDao())
            val viewModel = importViewModel(repository) { source ->
                reads.add(source)
                if (source == blocked) {
                    started.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
                CbzImportResult.Success(source, emptyList())
            }
            withImportScreen(viewModel) {
                try {
                    for (label in listOf(R.string.cbz_import_action, R.string.cbz_import_multiple_action, R.string.library_import_tree)) {
                        clickable(awaitNode(context.getString(label)))
                    }
                    instrumentation.runOnMainSync { viewModel.selectDocuments(listOf(first, blocked, queued)) }
                    assertTrue(started.await(8, TimeUnit.SECONDS))
                    awaitNode(context.getString(R.string.cbz_batch_known_progress, 1, 3))
                    click(R.string.cbz_batch_cancel)
                    awaitNode(context.getString(R.string.cbz_batch_cancelling))
                    awaitNode(context.getString(R.string.cbz_batch_known_progress, 1, 3))
                    val active = viewModel.state.value as LibraryUiState.BatchImporting
                    assertEquals(1, active.progress.imported)
                    assertEquals(2, active.progress.pendingKnown)
                    val cancelLabel = awaitNode(context.getString(R.string.cbz_batch_cancel))
                    assertTrue(generateSequence(cancelLabel) { it.parent }.none { it.isEnabled && it.isClickable })
                    release.countDown()
                    awaitNode(context.getString(R.string.cbz_batch_cancelled_summary, 1, 0, 0, 2))
                    assertEquals(listOf(first, blocked), reads.toList())
                    val entries = runBlocking { repository.observeEntries(LibraryQuery()).first() }
                    assertEquals(first, entries.single().source.reference)
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun treeShowsStartThenProcessedCountBeforeCompletionAndReportsDiscoveryFailure() {
        val begin = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val folder = CbzSourceReference("folder")
        val inaccessible = CbzSourceReference("inaccessible")
        val trees = object : LibraryTreeSource {
            override suspend fun <T> withTree(
                tree: CbzSourceReference,
                consume: suspend (Flow<TreeDiscovery>) -> T,
            ): T {
                if (tree == inaccessible) throw IOException("Fixture folder unavailable")
                return consume(flow {
                    begin.await()
                    emit(TreeDiscovery.Candidate(CbzSourceReference("child")))
                    finish.await()
                })
            }
        }
        withImportDatabase { database ->
            val viewModel = importViewModel(RoomLibraryRepository(database.libraryDao()), trees) {
                CbzImportResult.Success(it, emptyList())
            }
            withImportScreen(viewModel) {
                try {
                    instrumentation.runOnMainSync { viewModel.selectTree(folder) }
                    awaitNode(context.getString(R.string.cbz_batch_starting))
                    begin.complete(Unit)
                    awaitNode(context.getString(R.string.cbz_batch_folder_progress, 1))
                    val active = viewModel.state.value as LibraryUiState.BatchImporting
                    assertTrue(active.progress.totalCandidates == null)
                    assertFalse(active.progress.discoveryFinished)
                    finish.complete(Unit)
                    awaitNode(context.getString(R.string.cbz_batch_summary, 1, 0, 0, 0))
                    instrumentation.runOnMainSync { viewModel.selectTree(inaccessible) }
                    awaitNode(context.getString(R.string.library_tree_open_failure))
                } finally {
                    begin.complete(Unit)
                    finish.complete(Unit)
                    instrumentation.runOnMainSync(viewModel::cancelBatch)
                }
            }
        }
    }

    private fun importViewModel(
        repository: RoomLibraryRepository,
        trees: LibraryTreeSource? = null,
        read: suspend (CbzSourceReference) -> CbzImportResult,
    ): LibraryViewModel {
        val importer = ImportIntoLibrary(
            newImporter = { object : CbzImportRepository {
                override suspend fun importCbz(source: CbzSourceReference) = read(source)
            } },
            documents = { LibraryDocumentMetadata(LibraryDocumentIdentity("test", it.value), null) },
            library = repository,
        )
        return LibraryViewModel(importer, ImportLibraryBatch(importer), LibraryTitle("Fixture"), trees)
    }

    private fun withImportScreen(viewModel: LibraryViewModel, test: () -> Unit) = withScreen({
        val state = viewModel.state.collectAsState().value
        ImportControls(state, state !is LibraryUiState.BatchImporting, {}, {}, {}, viewModel::cancelBatch)
    }, test)

    private fun withImportDatabase(test: (LibraryDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            test(database)
        } finally {
            database.close()
        }
    }

    private fun checked(id: Long) = CheckedLibraryEntry(
        LibraryEntry(
            id = LibraryEntryId(id),
            source = LibrarySource(LibraryDocumentIdentity("provider", "$id"), CbzSourceReference("provider/$id")),
            title = LibraryTitle("Libro $id"),
            addedAtEpochMillis = id,
        ),
        LibraryAccessStatus.AVAILABLE,
    )

    private fun withScreen(content: @androidx.compose.runtime.Composable () -> Unit, test: () -> Unit) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent { SumiTheme { Box(Modifier.fillMaxSize()) { content() } } }
            }
            test()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun click(label: Int) {
        assertTrue(clickable(awaitNode(context.getString(label))).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        checkNotNull(generateSequence(node) { it.parent }.firstOrNull { it.isEnabled && it.isClickable })

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun awaitNode(label: String, matches: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo {
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.uptimeMillis() + 8_000
        do {
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::add)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                val labelled = node.text?.toString() == label || node.contentDescription?.toString() == label
                if (node.isVisibleToUser && labelled && matches(node)) {
                    return node
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
            }
            SystemClock.sleep(75)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Missing visible accessibility node: $label")
    }
}

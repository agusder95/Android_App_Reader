package com.agustin.sumi.data.local.document

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.agustin.sumi.MainActivity
import com.agustin.sumi.R
import com.agustin.sumi.SumiApplication
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryAccessStatus
import com.agustin.sumi.domain.usecase.LibraryBatchStatus
import com.agustin.sumi.feature.library.LibraryUiState
import com.agustin.sumi.feature.library.LibraryViewModel
import com.agustin.sumi.feature.library.OpenCbzDocument
import com.agustin.sumi.feature.library.OpenCbzDocuments
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal class CbzPickerChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    fun run(): List<String> {
        verifyContract()
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val import = context.getString(R.string.cbz_import_action)
            val chooseAnother = context.getString(R.string.cbz_import_choose_another)
            val duplicate = context.getString(R.string.library_import_duplicate)
            val action = clickable(awaitNode(import))
            val bounds = Rect()
            action.getBoundsInScreen(bounds)
            val minimumTarget = (48 * context.resources.displayMetrics.density).toInt()
            check(bounds.height() >= minimumTarget && bounds.width() >= minimumTarget)
            check(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            openFixtureRoot()

            val generic = findNode(PickerFixtureProvider.GENERIC)
            val genericStatus = when {
                generic == null -> "hidden"
                !generic.isEnabled || clickableOrNull(generic) == null -> "disabled"
                else -> "selectable"
            }
            val cbzBefore = savedFixture(PickerFixtureProvider.CBZ)
            click(PickerFixtureProvider.CBZ)
            val cbzResult = if (cbzBefore == null) imported(1) else duplicate
            awaitNode(cbzResult)
            val savedCbz = checkNotNull(savedFixture(PickerFixtureProvider.CBZ))
            check(savedCbz.title.value == "one")
            if (cbzBefore != null) check(savedCbz.id == cbzBefore.id)

            click(chooseAnother)
            openFixtureRoot()
            check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitNode(cbzResult)
            check(savedFixture(PickerFixtureProvider.CBZ)?.id == savedCbz.id)

            click(chooseAnother)
            openFixtureRoot()
            val zipBefore = savedFixture(PickerFixtureProvider.ZIP)
            click(PickerFixtureProvider.ZIP)
            awaitNode(if (zipBefore == null) imported(2) else duplicate)
            val savedZip = checkNotNull(savedFixture(PickerFixtureProvider.ZIP))
            check(savedZip.title.value == "two" && savedZip.id != savedCbz.id)

            click(chooseAnother)
            openFixtureRoot()
            click(PickerFixtureProvider.UNAVAILABLE)
            awaitNode(context.getString(R.string.cbz_import_error_access))
            check(savedFixture(PickerFixtureProvider.UNAVAILABLE) == null)
            click(chooseAnother)
            openFixtureRoot()
            click(PickerFixtureProvider.CBZ)
            awaitNode(duplicate)
            check(savedFixture(PickerFixtureProvider.CBZ)?.id == savedCbz.id)

            return listOf(
                "ACTION_OPEN_DOCUMENT configures four exact MIME types and read access",
                "accessible import action opens the system picker and imports a CBZ offline",
                "cancelling the system picker preserves the previous result and allows reopening",
                "a second document offered as ZIP imports offline",
                "unavailable document shows a recoverable error and reselection succeeds",
                "generic MIME application/octet-stream is $genericStatus in the system picker",
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun runMultiple(): List<String> {
        val contract = verifyMultipleContract()
        clearFixtureEntries()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            click(context.getString(R.string.cbz_import_multiple_action))
            openFixtureRoot()
            click(PickerFixtureProvider.CBZ)
            awaitBatchSummary()
            val imported = checkNotNull(savedFixture(PickerFixtureProvider.CBZ))
            check(context.contentResolver.persistedUriPermissions.any {
                it.isReadPermission && it.uri == Uri.parse(imported.source.reference.value)
            })

            click(context.getString(R.string.cbz_import_multiple_action))
            openFixtureRoot()
            check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitBatchSummary()
            check(savedFixture(PickerFixtureProvider.CBZ)?.id == imported.id)

            val sumi = context.applicationContext as SumiApplication
            val entries = runBlocking { sumi.libraryRepository.observeEntries(LibraryQuery("")).first() }
                .filter { it.source.identity.authority == PICKER_AUTHORITY }
            check(entries.map { it.source.identity.documentId }.toSet() == setOf(PickerFixtureProvider.CBZ))

            val viewModel = LibraryViewModel(
                sumi.importIntoLibrary,
                sumi.importLibraryBatch,
                LibraryTitle(context.getString(R.string.library_untitled)),
            )
            viewModel.selectDocuments(contract.multipleSelection.map { CbzSourceReference(it.toString()) })
            val repeatedSummary = runBlocking {
                viewModel.state.first { it is LibraryUiState.BatchFinished } as LibraryUiState.BatchFinished
            }.progress
            check(repeatedSummary.status == LibraryBatchStatus.COMPLETED)
            check(repeatedSummary.imported == 0 && repeatedSummary.duplicates == 2 && repeatedSummary.failed == 1)
            return contract.cases + listOf(
                "system ACTION_OPEN_DOCUMENT multi-select accepts a single data URI and persists its read grant",
                "cancelling the multi-select picker preserves the prior batch result and library entry",
                "the selected-URI batch path reports repeated identities and an inaccessible URI independently",
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun runRecovery(): List<String> {
        val original = checkNotNull(savedFixture(PickerFixtureProvider.CBZ))
        val occupied = checkNotNull(savedFixture(PickerFixtureProvider.ZIP))
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(original.source.reference.value),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val reselect = context.getString(R.string.library_recovery_select, original.title.value)
            awaitNode(context.getString(R.string.library_recovery_permission))
            click(reselect)
            openFixtureRoot()
            check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitNode(reselect)
            check(savedFixture(PickerFixtureProvider.CBZ) == original)

            click(reselect)
            openFixtureRoot()
            click(PickerFixtureProvider.ZIP)
            awaitNode(context.getString(R.string.library_recovery_conflict))
            check(savedFixture(PickerFixtureProvider.CBZ) == original)
            check(savedFixture(PickerFixtureProvider.ZIP) == occupied)

            click(reselect)
            openFixtureRoot()
            click(PickerFixtureProvider.CBZ)
            awaitNode(context.getString(R.string.library_recovery_done))
            check(savedFixture(PickerFixtureProvider.CBZ) == original)
            val access = (context.applicationContext as SumiApplication).libraryDocumentAccess
            check(runBlocking { access.check(original.source.reference) } == LibraryAccessStatus.AVAILABLE)

            return listOf(
                "revoked grant offers individual reselection and cancelling preserves the record",
                "selecting an occupied document shows a conflict and preserves both records",
                "reselecting the original document restores access and keeps its identity",
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun verifyContract() {
        val intent = OpenCbzDocument.createIntent(context, Unit)
        check(intent.action == Intent.ACTION_OPEN_DOCUMENT && intent.hasCategory(Intent.CATEGORY_OPENABLE))
        check(intent.type == "*/*" && !intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        check(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toSet() == setOf(
            "application/vnd.comicbook+zip", "application/zip", "application/x-cbz", "application/x-zip-compressed",
        ))
        check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        check(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        check(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        val data = Intent().setData(Uri.parse("content://com.agustin.sumi.test.picker/document/one.cbz"))
        check(OpenCbzDocument.parseResult(Activity.RESULT_OK, data) == data.data)
        check(OpenCbzDocument.parseResult(Activity.RESULT_CANCELED, data) == null)
    }

    private fun verifyMultipleContract(): MultipleContractResult {
        val intent = OpenCbzDocuments.createIntent(context, Unit)
        check(intent.action == Intent.ACTION_OPEN_DOCUMENT && intent.hasCategory(Intent.CATEGORY_OPENABLE))
        check(intent.type == "*/*" && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        check(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toSet() == setOf(
            "application/vnd.comicbook+zip", "application/zip", "application/x-cbz", "application/x-zip-compressed",
        ))
        check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        check(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        val first = Uri.parse("content://$PICKER_AUTHORITY/document/one.cbz")
        val second = Uri.parse("content://$PICKER_AUTHORITY/document/two.zip")
        val multiple = Intent()
        multiple.data = first
        multiple.clipData = ClipData(ClipDescription("selection", arrayOf("application/octet-stream")), ClipData.Item(first)).apply {
            addItem(ClipData.Item(second))
            addItem(ClipData.Item(first))
        }
        val parsedMultiple = checkNotNull(OpenCbzDocuments.parseResult(Activity.RESULT_OK, multiple))
        check(parsedMultiple == listOf(first, second, first))
        check(OpenCbzDocuments.parseResult(Activity.RESULT_OK, Intent().setData(second)) == listOf(second))
        check(OpenCbzDocuments.parseResult(Activity.RESULT_CANCELED, multiple) == null)
        return MultipleContractResult(listOf(
            "multiple picker preserves SPEC-002 MIME filters and requests read plus persistable access",
            "ClipData duplicates are retained for batch deduplication and overlapping data is not double-counted",
            "multiple picker accepts a single data URI and treats cancellation as no selection",
        ), parsedMultiple)
    }

    private fun clearFixtureEntries() = runBlocking {
        val sumi = context.applicationContext as SumiApplication
        val entries = sumi.libraryRepository.observeEntries(LibraryQuery("")).first()
            .filter { it.source.identity.authority == PICKER_AUTHORITY }
        entries.forEach { sumi.libraryMaintenance.remove(it.id) }
    }

    private fun awaitBatchSummary() {
        val deadline = SystemClock.uptimeMillis() + 12_000
        do {
            nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.startsWith("Lote terminado") == true }
                ?.let { return }
            SystemClock.sleep(75)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Batch summary not shown. Visible nodes: ${nodes().mapNotNull { it.text ?: it.contentDescription }.toList()}")
    }

    private fun imported(pages: Int): String =
        context.resources.getQuantityString(R.plurals.cbz_import_success, pages, pages)

    private fun savedFixture(documentId: String): LibraryEntry? = runBlocking {
        val library = (context.applicationContext as SumiApplication).libraryRepository
        library.observeEntries(LibraryQuery(documentId.substringBeforeLast('.'))).first().singleOrNull {
            it.source.identity.authority == "com.agustin.sumi.test.picker" &&
                it.source.identity.documentId == documentId
        }
    }

    private fun openFixtureRoot() {
        if (awaitNodeOrNull(PickerFixtureProvider.CBZ, 1500) != null) return
        val roots = awaitNodeOrNull(PickerFixtureProvider.TITLE, 1000)
        if (roots == null) click("Show roots")
        click(PickerFixtureProvider.TITLE)
        awaitNode(PickerFixtureProvider.CBZ)
    }

    private fun click(label: String) {
        check(clickable(awaitNode(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        checkNotNull(clickableOrNull(node)) { "No accessible click action for ${node.text ?: node.contentDescription}" }

    private fun clickableOrNull(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }

    private fun awaitNode(label: String): AccessibilityNodeInfo =
        checkNotNull(awaitNodeOrNull(label, 8000)) {
            "Missing '$label'. Visible nodes: ${nodes().mapNotNull { it.text ?: it.contentDescription }.toList()}"
        }

    private fun awaitNodeOrNull(label: String, timeoutMillis: Long): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            findNode(label)?.let { return it }
            SystemClock.sleep(75)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun findNode(label: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
    }

    private fun nodes(): Sequence<AccessibilityNodeInfo> = sequence {
        val root = automation.rootInActiveWindow ?: return@sequence
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
    }

    private data class MultipleContractResult(val cases: List<String>, val multipleSelection: List<Uri>)

    private companion object {
        const val PICKER_AUTHORITY = "com.agustin.sumi.test.picker"
    }
}

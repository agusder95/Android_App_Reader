package com.agustin.sumi.data.local.document

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.MainActivity
import com.agustin.sumi.R
import com.agustin.sumi.SumiApplication
import com.agustin.sumi.data.local.archive.pageId
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySortOrder
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.ReaderResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderLibraryFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as SumiApplication
    private val documents = SafDocumentSource(context)
    private val entries = mutableListOf<LibraryEntryId>()

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun importsFromSystemDownloadsReadsRotatesResumesAndRemoves() = runBlocking<Unit> {
        val name = "sumi-t08-reader-${SystemClock.uptimeMillis()}.zip"
        val file = checkNotNull(context.contentResolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            },
        ))
        var entryId: LibraryEntryId? = null
        try {
            context.contentResolver.openOutputStream(file).use { output ->
                checkNotNull(output).write(makeArchive())
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                click(label(R.string.cbz_import_action))
                awaitPickerDocument(name, "Downloads")
                click(name)
                val title = name.substringBeforeLast('.')
                val open = label(R.string.library_open_reader, title)
                awaitNode(open)
                val entry = app.libraryRepository.observeEntries(LibraryQuery(searchText = title)).first()
                    .single { it.title.value == title }
                entryId = entry.id
                assertTrue("Expected a system storage provider",
                    entry.source.identity.authority != PickerFixtureProvider.AUTHORITY)
                println("T08 SAF system provider: ${entry.source.identity.authority}")
                click(open)
                awaitNode(label(R.string.reader_page_position, 1, 2))
                awaitNode(label(R.string.reader_direction_ltr))
                click(label(R.string.reader_direction_ltr))
                awaitNode(label(R.string.reader_direction_rtl))
                click(label(R.string.reader_next))
                awaitNode(label(R.string.reader_page_position, 2, 2))
                awaitNode(label(R.string.reader_end))
                await { progressPage(entry.id) == pageId("page2.png") }
                repeat(8) { click(label(R.string.reader_zoom_in)) }
                awaitNode(label(R.string.reader_zoom_value, 3f))
                click(label(R.string.reader_zoom_reset))
                awaitNode(label(R.string.reader_zoom_value, 1f))
                rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                awaitNode(label(R.string.reader_page_position, 2, 2))
                rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                awaitNode(label(R.string.reader_page_position, 2, 2))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                click(open)
                awaitNode(label(R.string.reader_page_position, 2, 2))
                awaitNode(label(R.string.reader_direction_rtl))
                click(label(R.string.reader_close))
                click(label(R.string.library_remove_action, title))
                click(label(R.string.library_remove_confirm))
                await { runBlocking { app.libraryRepository.findById(entry.id) == null } }
                context.contentResolver.openInputStream(file).use { original ->
                    assertEquals('P'.code, checkNotNull(original).read()) // ZIP signature
                }
            }
        } finally {
            entryId?.let { app.libraryRepository.remove(it) }
            context.contentResolver.delete(file, null, null)
        }
    }

    private fun rotate(scenario: ActivityScenario<MainActivity>, request: Int, expected: Int) {
        scenario.onActivity { it.requestedOrientation = request }
        await {
            var orientation = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity { orientation = it.resources.configuration.orientation }
            orientation == expected
        }
    }

    @Test
    fun libraryReturnsToSearchOrderAndScrollAndResumesEachBookIndependently() = runBlocking<Unit> {
        val pipe = prepareArchive("cover-pipe")
        val direct = prepareArchive("cover-direct")
        val previousOrder = app.librarySortPreference.read()
        try {
            app.librarySortPreference.save(LibrarySortOrder.TITLE_ASCENDING)
            val first = insert("T07 Z", "flow-pipe", pipe)
            insert("T07 Y", "flow-direct", direct)
            for (i in 1..8) insert("T07 A $i", "flow-filler-$i", direct)
            ActivityScenario.launch(MainActivity::class.java).use {
                val ascending = label(R.string.library_sort, label(R.string.library_sort_ascending))
                awaitNode(ascending)
                val field = awaitNode(label(R.string.library_search)).let { node ->
                    if (node.isEditable) node else nodes().first { it.isEditable }
                }
                val text = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "T07")
                }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
                awaitNode("T07")
                click(ascending)
                click(label(R.string.library_sort_descending))
                awaitNode(label(R.string.library_sort, label(R.string.library_sort_descending)))
                val firstAction = label(R.string.library_open_reader, "T07 Z")
                val initial = bounds(awaitNode(firstAction)).top
                swipeUp()
                await { waitForNode(firstAction, 100)?.let { bounds(it).top < initial - 100 } == true }
                val before = stableTop(firstAction)
                click(firstAction)
                awaitNode(label(R.string.reader_page_position, 1, 2))
                click(label(R.string.reader_next))
                awaitNode(label(R.string.reader_page_position, 2, 2))
                await { progressPage(first) == pageId("page2.png") && temporaryExists() }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                assertEquals(before, stableTop(firstAction))
                await { !temporaryExists() }

                click(label(R.string.library_open_reader, "T07 Y"))
                awaitNode(label(R.string.reader_page_position, 1, 2))
                click(label(R.string.reader_close))
                click(firstAction)
                awaitNode(label(R.string.reader_page_position, 2, 2))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitNode(firstAction)
                // The header can be farther away after variable grid measurements settle.
                repeat(3) {
                    if (waitForNode(label(R.string.library_search), 500) == null) swipeUp(reverse = true)
                }
                awaitNode("T07")
                awaitNode(label(R.string.library_sort, label(R.string.library_sort_descending)))
            }
        } finally {
            entries.forEach { app.libraryRepository.remove(it) }
            app.librarySortPreference.save(previousOrder)
            command("revoke", "cover-pipe")
            command("revoke", "cover-direct")
        }
    }

    @Test
    fun systemReselectionRestoresAccessAndHandlesAnEntryRemovedWhilePicking() = runBlocking {
        val name = PickerFixtureProvider.READER_CBZ
        val reference = CbzSourceReference(DocumentsContract.buildDocumentUri(PickerFixtureProvider.AUTHORITY, name).toString())
        command("picker-revoke", name)
        try {
            val id = insert("T07 Recovery", name, reference, PickerFixtureProvider.AUTHORITY)
            ActivityScenario.launch(MainActivity::class.java).use {
                val action = label(R.string.library_open_reader, "T07 Recovery")
                click(action)
                awaitNode(label(R.string.reader_permission_revoked))
                chooseRecoveryFile()
                awaitNode(label(R.string.reader_page_position, 1, 1))
                await { progressPage(id) == pageId("page1.png") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                command("picker-revoke", name)
                click(action)
                awaitNode(label(R.string.reader_permission_revoked))
                click(label(R.string.reader_reselect_document))
                awaitPickerFile()
                app.libraryRepository.remove(id)
                click(name)
                awaitNode(label(R.string.reader_entry_missing))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitNode(label(R.string.library_saved_documents))
                await { nodes().none { it.text?.toString() == action } }
            }
        } finally {
            entries.forEach { app.libraryRepository.remove(it) }
            command("picker-revoke", name)
        }
    }

    private fun chooseRecoveryFile() {
        click(label(R.string.reader_reselect_document))
        awaitPickerFile()
        click(PickerFixtureProvider.READER_CBZ)
    }

    private fun awaitPickerFile() {
        awaitPickerDocument(PickerFixtureProvider.READER_CBZ, PickerFixtureProvider.TITLE)
    }

    private fun awaitPickerDocument(name: String, root: String) {
        if (waitForNode(name, 2_000) != null) return
        val showRoots = waitForNode("Show roots", 500)
        if (showRoots != null) click("Show roots")
        click(root)
        awaitNode(name)
    }

    private suspend fun insert(
        title: String,
        identity: String,
        source: CbzSourceReference,
        authority: String = SafTestProvider.AUTHORITY,
    ): LibraryEntryId {
        app.libraryRepository.insert(LibrarySource(LibraryDocumentIdentity(authority, identity), source),
            LibraryTitle(title), System.currentTimeMillis(), null)
        return app.libraryRepository.observeEntries(LibraryQuery(searchText = title)).first()
            .single { it.title.value == title }.id.also(entries::add)
    }

    private fun progressPage(id: LibraryEntryId) = runBlocking {
        (app.readerProgressRepository.load(id) as? ReaderResult.Success)?.value?.lastConfirmedPageId
    }

    private fun pageId(path: String) = MessageDigest.getInstance("SHA-256").pageId(path)

    private suspend fun prepareArchive(name: String): CbzSourceReference {
        command("revoke", name)
        command("set-cover", name, Bundle().apply { putByteArray("archive", makeArchive()) })
        command("persistable", name)
        return CbzSourceReference("content://${SafTestProvider.AUTHORITY}/$name").also {
            assertTrue(documents.persistReadPermission(it))
        }
    }

    private fun makeArchive(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        try {
            val archive = ByteArrayOutputStream()
            ZipOutputStream(archive).use { zip ->
                for (page in 1..2) {
                    zip.putNextEntry(ZipEntry("page$page.png"))
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                    zip.closeEntry()
                }
            }
            return archive.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun command(method: String, id: String, extras: Bundle? = null) {
        context.contentResolver.call(Uri.parse("content://${SafTestProvider.AUTHORITY}"), method, id, extras)
    }

    private fun temporaryExists() = File(context.cacheDir, "cbz-import")
        .listFiles().orEmpty().any { it.name.endsWith(".cbz.part") }

    private fun label(id: Int, vararg values: Any) = context.getString(id, *values)

    private fun click(text: String) {
        val node = awaitNode(text)
        val target = generateSequence(node) { it.parent }.first { it.isClickable && it.isEnabled }
        assertTrue("Click $text", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitNode(text: String): AccessibilityNodeInfo =
        checkNotNull(waitForNode(text, 10_000)) { "Missing accessible node: $text" }

    private fun waitForNode(text: String, timeout: Long): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + timeout
        do {
            nodes().firstOrNull { it.isVisibleToUser &&
                (it.text?.toString() == text || it.contentDescription?.toString() == text) }?.let { return it }
            SystemClock.sleep(60)
        } while (SystemClock.uptimeMillis() < end)
        return null
    }

    private fun nodes(): Sequence<AccessibilityNodeInfo> = sequence {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            yield(node)
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
    }

    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < end) { "Reader storage did not reach the expected state" }
            SystemClock.sleep(60)
        }
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun stableTop(text: String): Int {
        var lastTop = bounds(awaitNode(text)).top
        var unchangedSince = SystemClock.uptimeMillis()
        await {
            val top = bounds(awaitNode(text)).top
            if (top != lastTop) {
                lastTop = top
                unchangedSince = SystemClock.uptimeMillis()
            }
            SystemClock.uptimeMillis() - unchangedSince >= 350
        }
        return lastTop
    }

    private fun swipeUp(reverse: Boolean = false) {
        val screen = context.resources.displayMetrics
        val down = SystemClock.uptimeMillis()
        val x = screen.widthPixels * .5f
        val y = screen.heightPixels * if (reverse) .25f else .82f
        val distance = screen.heightPixels * if (reverse) .5f else -.5f
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y + distance * fraction, 0)
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (i in 1..12) {
            SystemClock.sleep(30)
            send(MotionEvent.ACTION_MOVE, i / 12f)
        }
        SystemClock.sleep(180)
        send(MotionEvent.ACTION_UP, 1f)
        instrumentation.waitForIdleSync()
    }
}

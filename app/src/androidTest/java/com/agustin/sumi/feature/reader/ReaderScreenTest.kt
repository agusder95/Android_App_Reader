package com.agustin.sumi.feature.reader

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.MainActivity
import com.agustin.sumi.R
import com.agustin.sumi.core.ui.theme.SumiTheme
import com.agustin.sumi.domain.model.CbzPageDescriptor
import com.agustin.sumi.domain.model.CbzPageFormat
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderOptions
import com.agustin.sumi.domain.model.ReaderPage
import com.agustin.sumi.domain.model.ReaderPosition
import com.agustin.sumi.domain.model.ReaderProgress
import com.agustin.sumi.domain.model.ReaderSession
import com.agustin.sumi.domain.model.ReaderSessionId
import com.agustin.sumi.domain.repository.DecodedReaderPage
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderPageInput
import com.agustin.sumi.domain.repository.ReaderPageRenderer
import com.agustin.sumi.domain.repository.ReaderProgressConfirmation
import com.agustin.sumi.domain.repository.ReaderProgressRepository
import com.agustin.sumi.domain.repository.ReaderRepository
import com.agustin.sumi.domain.repository.ReaderResult
import com.agustin.sumi.domain.repository.ReaderViewport
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val bookId = LibraryEntryId(937L)

    @Test
    fun accessibleControlsNavigateRespectBoundariesAndConfirmOnlyDrawnFrames() {
        val repo = FakeReaderRepository()
        val progress = FakeProgress()
        val renderer = FakeRenderer()
        val model = viewModel(repo, progress, renderer)
        withScreen(model, renderer) {
            val first = awaitNode(context.getString(R.string.reader_page_position, 1, 2))
            val nextTarget = generateSequence(awaitNode(context.getString(R.string.reader_next))) { it.parent }
                .first { it.isClickable }
            val density = context.resources.displayMetrics.density
            assertTrue("button target is at least 48dp", bounds(nextTarget).height() / density >= 48f)
            awaitCondition { progress.confirmed?.lastConfirmedPageId == CbzPageId("page-1") }
            click(R.string.reader_next)
            awaitNode(context.getString(R.string.reader_page_position, 2, 2))
            awaitCondition { progress.confirmed?.lastConfirmedPageId == CbzPageId("page-2") }
            assertEquals(CbzPageId("page-2"), progress.confirmed?.lastConfirmedPageId)
            assertTrue(first.isVisibleToUser)
            click(R.string.reader_previous)
            awaitNode(context.getString(R.string.reader_page_position, 1, 2))
            awaitCondition { progress.confirmed?.lastConfirmedPageId == CbzPageId("page-1") }
            click(R.string.reader_direction_ltr)
            awaitNode(context.getString(R.string.reader_direction_rtl))
            click(R.string.reader_next)
            awaitNode(context.getString(R.string.reader_page_position, 2, 2))
            awaitNode(context.getString(R.string.reader_end))
        }
    }

    @Test
    fun progressIsNotConfirmedUntilTheCanvasDrawsThePage() {
        val progress = FakeProgress()
        val renderer = FakeRenderer().apply { drawEnabled.value = false }
        val model = viewModel(FakeReaderRepository(), progress, renderer)
        withScreen(model, renderer) {
            awaitNode(context.getString(R.string.reader_page_position, 1, 2))
            SystemClock.sleep(250)
            assertNull("an available but not drawn page is not confirmed", progress.confirmed)
            instrumentation.runOnMainSync { renderer.drawEnabled.value = true }
            awaitCondition { progress.confirmed?.lastConfirmedPageId == CbzPageId("page-1") }
        }
    }

    @Test
    fun zoomControlsStayWithinThreeTimesAndResetAfterPageChange() {
        val renderer = FakeRenderer()
        val model = viewModel(FakeReaderRepository(), FakeProgress(), renderer)
        withScreen(model, renderer) {
            awaitNode(context.getString(R.string.reader_page_position, 1, 2))
            repeat(8) { click(R.string.reader_zoom_in) }
            awaitNode(context.getString(R.string.reader_zoom_value, 3f))
            assertTrue(renderer.frames.any { it.zoom.factor == 3f })
            click(R.string.reader_next)
            awaitNode(context.getString(R.string.reader_page_position, 2, 2))
            awaitNode(context.getString(R.string.reader_zoom_value, 1f))
        }
    }

    @Test
    fun doubleTapZoomAndAccessibleNavigationPreserveNaturalOrderInLtrAndRtl() {
        val renderer = FakeRenderer()
        val model = viewModel(FakeReaderRepository(), FakeProgress(), renderer)
        withScreen(model, renderer) {
            awaitNode(context.getString(R.string.reader_canvas_description, 1, 2))
            tapTwice(R.string.reader_canvas_description, 1)
            awaitNode(context.getString(R.string.reader_zoom_value, 2f))
            tapTwice(R.string.reader_canvas_description, 1)
            awaitNode(context.getString(R.string.reader_zoom_value, 1f))
            swipePage(1, left = true)
            awaitNode(context.getString(R.string.reader_page_position, 2, 2))
            swipePage(2, left = false)
            awaitNode(context.getString(R.string.reader_page_position, 1, 2))
            click(R.string.reader_direction_ltr)
            awaitNode(context.getString(R.string.reader_direction_rtl))
            swipePage(1, left = false)
            awaitNode(context.getString(R.string.reader_page_position, 2, 2))
            swipePage(2, left = true)
            awaitNode(context.getString(R.string.reader_page_position, 1, 2))
        }
    }

    @Test
    fun pinchZoomIsBoundedAndCanBeResetAccessibly() {
        val renderer = FakeRenderer()
        val model = viewModel(FakeReaderRepository(), FakeProgress(), renderer)
        withScreen(model, renderer) {
            val canvas = bounds(awaitNode(context.getString(R.string.reader_canvas_description, 1, 2)))
            awaitZoom(renderer, 1f)
            val pageLoads = renderer.loadCount

            twoFingerGesture(canvas, .25f, .5f)
            awaitZoom(renderer, 2f)
            twoFingerGesture(canvas, .16f, .64f)
            awaitZoom(renderer, 3f)
            twoFingerGesture(canvas, .64f, .16f)
            awaitZoom(renderer, 1f)

            // Both pointers move left together, then the remaining finger continues left.
            twoFingerGesture(canvas, .25f, .25f, translate = true)
            awaitZoom(renderer, 1f)
            awaitNode(context.getString(R.string.reader_page_position, 1, 2))
            assertEquals(pageLoads, renderer.loadCount)

            twoFingerGesture(canvas, .25f, .5f)
            awaitZoom(renderer, 2f)
            click(R.string.reader_zoom_reset)
            awaitZoom(renderer, 1f)
            twoFingerGesture(canvas, .25f, .5f)
            awaitZoom(renderer, 2f)
            click(R.string.reader_next)
            awaitNode(context.getString(R.string.reader_page_position, 2, 2))
            awaitZoom(renderer, 1f)
        }
    }

    @Test
    fun missingSourceShowsRecoveryAndRetryActions() {
        val renderer = FakeRenderer()
        val model = viewModel(FakeReaderRepository(ReaderError.SOURCE_UNAVAILABLE), FakeProgress(), renderer)
        var reselectionRequested = false
        withScreen(model, renderer, onReselect = { reselectionRequested = true }) {
            awaitNode(context.getString(R.string.reader_source_missing))
            click(R.string.reader_reselect_document)
            assertTrue(reselectionRequested)
        }
    }

    @Test
    fun showsLoadingRecoveryErrorAndEndAtBothWindowWidths() {
        for (width in listOf(320, 600)) {
            val root = { Modifier.width(width.dp).fillMaxHeight() }
            val renderer = FakeRenderer()
            val gate = CompletableDeferred<Unit>()
            val loading = viewModel(FakeReaderRepository(gate = gate), FakeProgress(), renderer)
            try {
                withScreen(loading, renderer, rootModifier = root) {
                    awaitNode(context.getString(R.string.reader_loading))
                    gate.complete(Unit)
                    awaitNode(context.getString(R.string.reader_canvas_description, 1, 2))
                    click(R.string.reader_next)
                    awaitNode(context.getString(R.string.reader_end))
                    awaitCondition { renderer.frames.isNotEmpty() }
                    println("T08 requested width ${width}dp, viewport ${renderer.frames.last().viewport.width}px")
                }
            } finally {
                gate.complete(Unit)
            }
            for ((error, message) in listOf(
                ReaderError.IO_FAILURE to R.string.reader_error,
                ReaderError.SOURCE_UNAVAILABLE to R.string.reader_source_missing,
            )) {
                val failed = viewModel(FakeReaderRepository(error), FakeProgress(), renderer)
                withScreen(failed, renderer, rootModifier = root) {
                    awaitNode(context.getString(message))
                    val retry = generateSequence(awaitNode(context.getString(R.string.reader_retry))) { it.parent }
                        .first { it.isClickable && it.isEnabled }
                    assertTrue(bounds(retry).height() / context.resources.displayMetrics.density >= 48f)
                    if (error == ReaderError.SOURCE_UNAVAILABLE) {
                        awaitNode(context.getString(R.string.reader_reselect_document))
                    }
                }
            }
        }
    }

    @Test
    fun recompositionAtTwoWidthsKeepsSessionAndAccessiblePage() {
        val repo = FakeReaderRepository()
        val renderer = FakeRenderer()
        val model = viewModel(repo, FakeProgress(), renderer)
        val width = mutableIntStateOf(320)
        withScreen(model, renderer, rootModifier = { Modifier.width(width.intValue.dp).fillMaxHeight() }) {
            bounds(awaitNode(context.getString(R.string.reader_page_position, 1, 2)))
            val narrowWidth = renderer.frames.last().viewport.width
            instrumentation.runOnMainSync { width.intValue = 600 }
            awaitCondition { renderer.frames.last().viewport.width > narrowWidth }
            assertTrue("recomposition does not create another session", repo.openCount == 1)
        }
    }

    @Test
    fun screenRestoresAcrossActivityConfigurationRecreation() {
        val repo = FakeReaderRepository()
        val renderer = FakeRenderer()
        val model = viewModel(repo, FakeProgress(), renderer)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { installReaderContent(it, model, renderer) }
            awaitNode(context.getString(R.string.reader_page_position, 1, 2))
            scenario.recreate()
            scenario.onActivity { installReaderContent(it, model, renderer) }
            awaitNode(context.getString(R.string.reader_page_position, 1, 2))
            assertEquals("configuration recreation reuses the reader session", 1, repo.openCount)
        } finally {
            scenario.close()
        }
    }

    private fun viewModel(repo: FakeReaderRepository, progress: FakeProgress, renderer: FakeRenderer) = ReaderViewModel(
        SavedStateHandle(), repo, progress, renderer,
        findBook = { ReaderResult.Success(ReaderBook(LibraryTitle("Fixture Manga"))) },
        recoverBook = { _, _, _ -> ReaderResult.Failure(ReaderError.SOURCE_UNAVAILABLE) },
    )

    private fun withScreen(
        model: ReaderViewModel,
        renderer: FakeRenderer,
        onReselect: () -> Unit = {},
        rootModifier: () -> Modifier = { Modifier.fillMaxSize() },
        test: () -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                installReaderContent(activity, model, renderer, onReselect, rootModifier)
            }
            test()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun installReaderContent(
        activity: MainActivity,
        model: ReaderViewModel,
        renderer: FakeRenderer,
        onReselect: () -> Unit = {},
        rootModifier: () -> Modifier = { Modifier.fillMaxSize() },
    ) {
        activity.setContent {
            SumiTheme {
                Box(rootModifier()) {
                    ReaderScreen(bookId, model, renderer::draw, {}, onReselect, onReselect, Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun click(id: Int) {
        val label = context.getString(id)
        val node = awaitNode(label)
        val action = generateSequence(node) { it.parent }.first { it.isEnabled && it.isClickable }
        assertTrue("$label is actionable", action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun tapTwice(label: Int, page: Int) {
        val node = awaitNode(context.getString(label, page, 2))
        val rect = bounds(node)
        val x = rect.exactCenterX()
        val y = rect.exactCenterY()
        repeat(2) {
            val down = SystemClock.uptimeMillis()
            inject(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
            inject(MotionEvent.obtain(down, down + 30, MotionEvent.ACTION_UP, x, y, 0))
            SystemClock.sleep(80)
        }
    }

    private fun swipePage(page: Int, left: Boolean) {
        val rect = bounds(awaitNode(context.getString(R.string.reader_canvas_description, page, 2)))
        val down = SystemClock.uptimeMillis()
        val distance = rect.width() * if (left) -.3f else .3f
        val startX = rect.exactCenterX() - distance / 2f
        fun send(action: Int, fraction: Float) {
            inject(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                startX + distance * fraction, rect.exactCenterY(), 0))
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..4) {
            SystemClock.sleep(25)
            send(MotionEvent.ACTION_MOVE, step / 4f)
        }
        send(MotionEvent.ACTION_UP, 1f)
    }

    private fun twoFingerGesture(rect: Rect, fromSpan: Float, toSpan: Float, translate: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun send(action: Int, count: Int, span: Float, translation: Float = 0f) {
            val coordinates = Array(count) { index ->
                MotionEvent.PointerCoords().apply {
                    val side = if (index == 0) -1f else 1f
                    x = rect.exactCenterX() + rect.width() * (translation + side * span / 2f)
                    y = rect.exactCenterY()
                    pressure = 1f
                    size = 1f
                }
            }
            inject(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties,
                coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0))
        }
        val pointerIndex = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        val translation = if (translate) -.15f else 0f
        send(MotionEvent.ACTION_DOWN, 1, fromSpan)
        send(MotionEvent.ACTION_POINTER_DOWN or pointerIndex, 2, fromSpan)
        for (step in 1..6) {
            val fraction = step / 6f
            SystemClock.sleep(30)
            send(MotionEvent.ACTION_MOVE, 2, fromSpan + (toSpan - fromSpan) * fraction, translation * fraction)
        }
        send(MotionEvent.ACTION_POINTER_UP or pointerIndex, 2, toSpan, translation)
        if (translate) send(MotionEvent.ACTION_MOVE, 1, toSpan, -.30f)
        send(MotionEvent.ACTION_UP, 1, toSpan, if (translate) -.30f else 0f)
        instrumentation.waitForIdleSync()
    }

    private fun inject(event: MotionEvent) {
        try {
            assertTrue("Android rejected touch action ${event.actionMasked}",
                instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun awaitZoom(renderer: FakeRenderer, factor: Float) {
        awaitNode(context.getString(R.string.reader_zoom_value, factor))
        awaitCondition {
            val actual = renderer.frames.lastOrNull()?.zoom?.factor
            actual != null && kotlin.math.abs(actual - factor) < .005f
        }
    }

    private fun awaitNode(label: String): AccessibilityNodeInfo = await {
        findNode(label) ?: error("Missing accessible node: $label")
    }

    private fun findNode(label: String): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
        return null
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        await { check(predicate()) { "Reader state did not reach the expected value" } }
    }

    private inline fun <T> await(block: () -> T): T {
        val end = SystemClock.uptimeMillis() + 8_000
        var failure: Throwable? = null
        while (SystemClock.uptimeMillis() < end) {
            try { return block() } catch (error: Throwable) { failure = error; SystemClock.sleep(75) }
        }
        throw AssertionError("Timed out waiting for reader UI", failure)
    }

    private class FakeReaderRepository(
        private val failure: ReaderError? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ReaderRepository {
        var openCount = 0
        private val pages = (1..2).map { index ->
            ReaderPage(CbzPageDescriptor(CbzPageId("page-$index"), CbzPageFormat.PNG), index - 1)
        }
        override suspend fun openSession(bookId: LibraryEntryId, direction: ReaderDirection): ReaderResult<ReaderSession> {
            openCount++
            gate?.await()
            failure?.let { return ReaderResult.Failure(it) }
            return ReaderResult.Success(ReaderSession(ReaderSessionId("test-session"), bookId, pages, ReaderOptions(direction)))
        }
        override suspend fun closeSession(session: ReaderSession) = Unit
        override suspend fun <T> readPage(session: ReaderSession, pageId: CbzPageId,
            consume: suspend (ReaderPageInput) -> T): ReaderResult<DecodedReaderPage<T>> = error("Unused in screen test")
    }

    private class FakeProgress : ReaderProgressRepository {
        @Volatile var confirmed: ReaderProgress? = null
        override suspend fun load(bookId: LibraryEntryId) = ReaderResult.Success(confirmed ?: ReaderProgress())
        override fun confirm(bookId: LibraryEntryId, progress: ReaderProgress) = ReaderProgressConfirmation {
            confirmed = progress
            ReaderResult.Success(Unit)
        }
    }

    private class FakeRenderer : ReaderPageRenderer {
        val frames = CopyOnWriteArrayList<Frame>()
        val drawEnabled = mutableStateOf(true)
        @Volatile var loadCount = 0
        override suspend fun load(session: ReaderSession, pageId: CbzPageId, viewport: ReaderViewport): ReaderResult<Unit> {
            loadCount++
            return ReaderResult.Success(Unit)
        }
        override fun clear() = Unit
        fun draw(viewport: ReaderViewport, zoom: com.agustin.sumi.domain.model.ReaderZoom,
            pan: androidx.compose.ui.geometry.Offset, scope: DrawScope): Boolean {
            frames += Frame(viewport, zoom)
            if (!drawEnabled.value) return false
            scope.drawRect(Color.White)
            return viewport.width > 0 && viewport.height > 0 && pan.x.isFinite()
        }
        data class Frame(val viewport: ReaderViewport, val zoom: com.agustin.sumi.domain.model.ReaderZoom)
    }
}

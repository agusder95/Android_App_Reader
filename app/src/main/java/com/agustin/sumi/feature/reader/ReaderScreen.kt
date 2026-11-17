package com.agustin.sumi.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agustin.sumi.R
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderZoom
import com.agustin.sumi.domain.repository.ReaderViewport
import kotlinx.coroutines.channels.Channel
import android.app.Activity

/** Drawing is borrowed synchronously from the renderer; this feature never retains a bitmap. */
internal typealias ReaderPageDraw = (ReaderViewport, ReaderZoom, Offset, DrawScope) -> Boolean

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ReaderScreen(
    bookId: LibraryEntryId,
    viewModel: ReaderViewModel,
    drawPage: ReaderPageDraw,
    onClose: () -> Unit,
    onReselectDocument: () -> Unit,
    onReselectTree: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val state by viewModel.state.collectAsState()
    var zoom by remember(bookId) { mutableFloatStateOf(1f) }
    var pan by remember(bookId) { mutableStateOf(Offset.Zero) }
    var lastPageId by remember(bookId) { mutableStateOf<CbzPageId?>(null) }
    fun setZoom(factor: Float) {
        zoom = factor.coerceIn(ReaderZoom.MIN_FACTOR, ReaderZoom.MAX_FACTOR)
        if (zoom == ReaderZoom.MIN_FACTOR) pan = Offset.Zero
    }
    val displayedFrames = remember { Channel<Long>(Channel.CONFLATED) }
    val closeReader = remember(viewModel, onClose) { { viewModel.close(); onClose() } }

    DisposableEffect(owner, viewModel) {
        val unbind = viewModel.bindTo(owner) { (context as? Activity)?.isChangingConfigurations == true }
        onDispose { unbind() }
    }
    LaunchedEffect(displayedFrames) {
        for (frame in displayedFrames) {
            withFrameNanos { }
            viewModel.confirmDisplayed(frame)
        }
    }
    LaunchedEffect(bookId, (state as? ReaderState.Available)?.position?.pageId) {
        val pageId = (state as? ReaderState.Available)?.position?.pageId
        if (pageId != null && pageId != lastPageId) {
            lastPageId = pageId
            zoom = 1f
            pan = Offset.Zero
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text((state as? ReaderState.Available)?.title?.value ?: stringResource(R.string.reader_title))
                        (state as? ReaderState.Available)?.let {
                            Text(stringResource(R.string.reader_page_position, it.position.index + 1, it.pageCount),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                navigationIcon = {
                    val closeLabel = stringResource(R.string.reader_close)
                    IconButton(onClick = closeReader, modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = closeLabel }) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                actions = {
                    val available = state as? ReaderState.Available
                    if (available != null) TextButton(
                        onClick = { viewModel.selectDirection(if (available.direction == ReaderDirection.LTR) ReaderDirection.RTL else ReaderDirection.LTR) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        content = {
                            val label = stringResource(if (available.direction == ReaderDirection.LTR) R.string.reader_direction_ltr else R.string.reader_direction_rtl)
                            Text(if (available.direction == ReaderDirection.LTR) "LTR" else "RTL",
                                modifier = Modifier.semantics { contentDescription = label }, maxLines = 1)
                        },
                    )
                },
            )
        },
        bottomBar = {
            val available = state as? ReaderState.Available
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                if (available != null) Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    ReaderAction(stringResource(R.string.reader_previous), "‹", enabled = available.position.index > 0, modifier = Modifier.weight(1f)) {
                        viewModel.navigate(false)
                    }
                    ReaderAction(stringResource(R.string.reader_zoom_out), "−", enabled = zoom > 1f, modifier = Modifier.weight(1f)) { setZoom(zoom - .25f) }
                    val zoomLabel = stringResource(R.string.reader_zoom_value, zoom)
                    Text(zoomLabel, modifier = Modifier.weight(1f).semantics { contentDescription = zoomLabel },
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.labelSmall)
                    ReaderAction(stringResource(R.string.reader_zoom_in), "+", enabled = zoom < 3f, modifier = Modifier.weight(1f)) { setZoom(zoom + .25f) }
                    ReaderAction(stringResource(R.string.reader_zoom_reset), "1:1", enabled = zoom != 1f, modifier = Modifier.weight(1f)) { setZoom(1f) }
                    ReaderAction(stringResource(R.string.reader_next), "›", enabled = available.position.index < available.pageCount - 1, modifier = Modifier.weight(1f)) {
                        viewModel.navigate(true)
                    }
                }
                else Spacer(Modifier.fillMaxWidth().height(56.dp))
                val endLabel = if (available != null && available.position.index == available.pageCount - 1) stringResource(R.string.reader_end) else ""
                Text(endLabel, Modifier.fillMaxWidth().height(20.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val viewport = ReaderViewport(
                with(density) { maxWidth.roundToPx() }.coerceAtLeast(1),
                with(density) { maxHeight.roundToPx() }.coerceAtLeast(1),
            )
            LaunchedEffect(bookId, viewport) { viewModel.open(bookId, viewport) }
            ReaderContent(
                state = state,
                drawPage = drawPage,
                placeholderColor = placeholderColor,
                displayedFrames = displayedFrames,
                zoom = zoom,
                pan = pan,
                onZoomPan = { factor, delta ->
                    setZoom(zoom * factor)
                    if (zoom > 1f) {
                        pan = Offset((pan.x + delta.x).coerceIn(-1000f, 1000f), (pan.y + delta.y).coerceIn(-1000f, 1000f))
                    }
                },
                onSwipe = { left ->
                    val rtl = (state as? ReaderState.Available)?.direction == ReaderDirection.RTL
                    viewModel.navigate(if (rtl) !left else left)
                },
                touchSlop = viewConfiguration.touchSlop,
                onDoubleTap = { setZoom(if (zoom > 1f) 1f else 2f) },
                onRetry = viewModel::retry,
                onReselectDocument = onReselectDocument,
                onReselectTree = onReselectTree,
                onClose = closeReader,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ReaderAction(label: String, visual: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
        Text(visual, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun ReaderContent(
    state: ReaderState,
    drawPage: ReaderPageDraw,
    placeholderColor: Color,
    displayedFrames: Channel<Long>,
    zoom: Float,
    pan: Offset,
    onZoomPan: (Float, Offset) -> Unit,
    onSwipe: (Boolean) -> Unit,
    touchSlop: Float,
    onDoubleTap: () -> Unit,
    onRetry: () -> Unit,
    onReselectDocument: () -> Unit,
    onReselectTree: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestZoom = rememberUpdatedState(zoom)
    val latestOnZoomPan = rememberUpdatedState(onZoomPan)
    val latestOnSwipe = rememberUpdatedState(onSwipe)
    val latestOnDoubleTap = rememberUpdatedState(onDoubleTap)
    val pageDescription = (state as? ReaderState.Available)?.let {
        stringResource(R.string.reader_canvas_description, it.position.index + 1, it.pageCount)
    }
    when (state) {
        is ReaderState.Available -> Canvas(
            modifier.fillMaxSize().background(Color.Black).clipToBounds()
                .pointerInput(state.frame) {
                    var lastTapAt: Long? = null
                    var lastTapPosition = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var event = awaitPointerEvent()
                        var previousDistance: Float? = null
                        var previousCenter: Offset? = null
                        var usedMultiplePointers = false
                        var moved = false
                        var displacement = Offset.Zero
                        while (event.changes.any { it.pressed }) {
                            if (event.changes.any { it.isConsumed }) break
                            val pointers = event.changes.filter { it.pressed }
                            if (pointers.size >= 2) {
                                usedMultiplePointers = true
                                val center = (pointers[0].position + pointers[1].position) / 2f
                                val span = distance(pointers[0].position, pointers[1].position)
                                val oldSpan = previousDistance
                                val oldCenter = previousCenter
                                if (oldSpan != null && oldCenter != null && oldSpan > 0f) {
                                    latestOnZoomPan.value(span / oldSpan, center - oldCenter)
                                }
                                previousDistance = span
                                previousCenter = center
                                event.changes.forEach { it.consume() }
                            } else if (pointers.size == 1) {
                                previousDistance = null
                                previousCenter = null
                                val change = pointers.first()
                                val delta = change.position - change.previousPosition
                                displacement = change.position - down.position
                                moved = moved || displacement.getDistance() > touchSlop
                                if (latestZoom.value > 1f && (moved || usedMultiplePointers)) {
                                    latestOnZoomPan.value(1f, delta)
                                    change.consume()
                                }
                            }
                            event = awaitPointerEvent()
                        }
                        val up = event.changes.singleOrNull()
                        if (usedMultiplePointers || up?.changedToUp() != true) {
                            lastTapAt = null
                        } else if (moved) {
                            lastTapAt = null
                            if (latestZoom.value <= 1f && kotlin.math.abs(displacement.x) > touchSlop &&
                                kotlin.math.abs(displacement.x) > kotlin.math.abs(displacement.y)) {
                                latestOnSwipe.value(displacement.x < 0f)
                            }
                        } else {
                            val previousTap = lastTapAt
                            if (previousTap != null && up.uptimeMillis - previousTap < 350L &&
                                (down.position - lastTapPosition).getDistance() < touchSlop * 2f) {
                                latestOnDoubleTap.value()
                                lastTapAt = null
                            } else {
                                lastTapAt = up.uptimeMillis
                                lastTapPosition = down.position
                            }
                        }
                    }
                }
                .semantics { contentDescription = pageDescription.orEmpty() },
        ) {
            val viewport = ReaderViewport(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            drawIntoCanvas {
                val drawn = drawPage(viewport, ReaderZoom(zoom), pan, this)
                if (drawn) displayedFrames.trySend(state.frame)
                // The renderer's borrowed Android bitmap is drawn synchronously by drawPage.
                if (!drawn) drawRect(placeholderColor)
            }
        }
        is ReaderState.Opening, is ReaderState.Loading -> ReaderMessage(stringResource(R.string.reader_loading), true, modifier)
        is ReaderState.Cleaning -> ReaderMessage(stringResource(R.string.reader_cleaning), true, modifier)
        is ReaderState.Suspended -> ReaderMessage(stringResource(R.string.reader_suspended), true, modifier)
        ReaderState.Closed -> ReaderMessage(stringResource(R.string.reader_closed), false, modifier, onClose = onClose)
        is ReaderState.Failed -> {
            val message = when (state.error) {
                com.agustin.sumi.domain.repository.ReaderError.ENTRY_NOT_FOUND -> stringResource(R.string.reader_entry_missing)
                com.agustin.sumi.domain.repository.ReaderError.ACCESS_DENIED -> stringResource(R.string.reader_permission_revoked)
                com.agustin.sumi.domain.repository.ReaderError.SOURCE_UNAVAILABLE -> stringResource(R.string.reader_source_missing)
                else -> stringResource(R.string.reader_error)
            }
            ReaderMessage(message, false, modifier, onRetry = onRetry,
                onReselectDocument = if (state.canReselectDocument) onReselectDocument else null,
                onReselectTree = if (state.canReselectTree) onReselectTree else null, onClose = onClose)
        }
    }
}

private fun distance(first: Offset, second: Offset): Float =
    kotlin.math.hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()).toFloat()

@Composable
private fun ReaderMessage(
    message: String,
    loading: Boolean,
    modifier: Modifier,
    onRetry: (() -> Unit)? = null,
    onReselectDocument: (() -> Unit)? = null,
    onReselectTree: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) CircularProgressIndicator(Modifier.semantics { contentDescription = message })
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
        onRetry?.let { TextButton(it, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_retry)) } }
        onReselectDocument?.let { TextButton(it, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_reselect_document)) } }
        onReselectTree?.let { TextButton(it, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_reselect_tree)) } }
        onClose?.let { TextButton(it, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_close)) } }
    }
}

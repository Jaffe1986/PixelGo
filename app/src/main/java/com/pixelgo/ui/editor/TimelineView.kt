package com.pixelgo.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelgo.data.audio.Peaks
import com.pixelgo.editor.FramePosition
import com.pixelgo.editor.Timing
import com.pixelgo.model.AudioClip
import com.pixelgo.model.Frame
import com.pixelgo.model.SoundTrack
import com.pixelgo.ui.common.ConfirmDialog
import com.pixelgo.ui.common.TextInputDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val RangeTint = Color(0x553DD6A0)
private val TrackColors = listOf(Color(0xFF3D8BFF), Color(0xFFFF8A3D), Color(0xFFB45CFF), Color(0xFF2EC4B6), Color(0xFFFF5C8A))

private const val RULER_DP = 22
private const val FRAMES_DP = 64
private const val ROW_DP = 40
private const val ADD_ROW_DP = 28
private const val MAX_VISIBLE_ROWS = 3

/**
 * The animation timeline. Each frame's width is proportional to how long it's held.
 *
 * - Compact: one row of frames with play / add / duplicate / expand.
 * - Expanded: a toolbar, a time ruler (drag it to scrub), the frames, and the sound tracks.
 * - Frames: tap = select (or pick a range in range mode), long-press = frame menu.
 * - Sound: tap a clip = its options, tap a track name = track menu, long-press a clip and
 *   drag = move it (edges: trim), drag up/down = scroll the tracks.
 * - Anywhere: drag sideways = scroll (with fling), pinch = zoom.
 *
 * Only what's on screen is drawn, so thousands of frames and long sounds stay fast.
 */
@Composable
fun TimelineView(vm: EditorViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val dp = LocalDensity.current.density
    var zoom by remember { mutableFloatStateOf(44f * dp) } // screen px per tick
    var scroll by remember { mutableFloatStateOf(0f) }
    var vScroll by remember { mutableFloatStateOf(0f) }
    var viewWidth by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showRecord by remember { mutableStateOf(false) }
    var holdTarget by remember { mutableIntStateOf(-2) } // -2 none, -1 range/current, >=0 frame
    var confirmDelete by remember { mutableStateOf(false) }
    var menuFrame by remember { mutableIntStateOf(-1) }
    var menuX by remember { mutableFloatStateOf(0f) }
    var trackMenu by remember { mutableLongStateOf(-1L) }
    var trackMenuY by remember { mutableFloatStateOf(0f) }
    var renameTrack by remember { mutableLongStateOf(-1L) }
    var volumeTrack by remember { mutableLongStateOf(-1L) }
    var deleteTrack by remember { mutableLongStateOf(-1L) }
    val thumbs = remember { mutableStateMapOf<Long, ImageBitmap>() }
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }

    vm.structureTick
    val timing = vm.timing() ?: return
    val frames = vm.frames()
    val tracks = vm.tracks()
    val clips = vm.clips()
    val fps = vm.fps
    val animTicks = timing.totalTicks
    val audioEndMs = clips.maxOfOrNull { it.endMs } ?: 0L
    val total = max(animTicks, ceil(audioEndMs * fps / 1000.0).toLong())
    val minZoom = 2f * dp
    val maxZoom = 140f * dp
    val endPad = 48f * dp

    // Reads live values (gesture handlers outlive this composition).
    fun liveTotal(): Long {
        val t = vm.timing()?.totalTicks ?: animTicks
        val end = vm.clips().maxOfOrNull { it.endMs } ?: 0L
        return max(t, ceil(end * vm.fps / 1000.0).toLong())
    }

    fun clampScroll(v: Float): Float = v.coerceIn(0f, max(0f, liveTotal() * zoom - viewWidth + endPad))

    val f0 = timing.frameAtTick((scroll / zoom).toLong())
    val f1 = timing.frameAtTick(((scroll + viewWidth) / zoom).toLong())

    // Load thumbnails for what's visible (cached in the view model, rendered off the main thread).
    LaunchedEffect(f0, f1, vm.structureTick) {
        val keep = HashSet<Long>()
        for (fi in max(0, f0 - 60)..min(frames.lastIndex, f1 + 60)) keep += frames[fi].uid
        thumbs.keys.retainAll(keep)
        for (fi in f0..min(f1, frames.lastIndex)) {
            val uid = frames[fi].uid
            vm.frameThumb(uid)?.let { thumbs[uid] = it }
        }
    }

    // Keep the current frame on screen; while playing, keep the playhead on screen instead (it
    // runs on along a longer sound while the animation repeats).
    val follow = if (vm.isPlaying) vm.playIndex else vm.frameIndex
    LaunchedEffect(vm.frameIndex, vm.frameCount, viewWidth, vm.isPlaying) {
        val fi = vm.frameIndex
        if (vm.isPlaying || viewWidth == 0 || fi !in 0 until timing.frameCount) return@LaunchedEffect
        val x0 = timing.startTick(fi) * zoom
        val x1 = timing.endTick(fi) * zoom
        if (x0 < scroll || x1 > scroll + viewWidth) scroll = clampScroll(x0 - viewWidth * 0.3f)
    }
    LaunchedEffect(vm.isPlaying, viewWidth) {
        if (!vm.isPlaying || viewWidth == 0) return@LaunchedEffect
        snapshotFlow { vm.playTick }.collect { t ->
            val x = t * zoom
            if (x < scroll || x > scroll + viewWidth * 0.9f) scroll = clampScroll(x - viewWidth * 0.1f)
        }
    }

    val currentExpanded by rememberUpdatedState(expanded)
    val visibleRows = min(tracks.size, MAX_VISIBLE_ROWS)

    fun xToMs(x: Float): Long = (((x + scroll) / zoom) * 1000.0 / vm.fps).toLong()
    fun msToX(ms: Long): Float = (ms * vm.fps / 1000f) * zoom - scroll

    fun frameAtX(x: Float): Int? {
        val t = vm.timing() ?: return null
        val tick = ((x + scroll) / zoom).toLong()
        if (x + scroll < 0 || tick >= t.totalTicks) return null
        return t.frameAtTick(tick)
    }

    /** What's under a point of the track canvas. */
    fun hit(x: Float, y: Float): Hit {
        val rulerH = if (currentExpanded) RULER_DP * dp else 0f
        if (y < rulerH) return Hit.Ruler
        if (!currentExpanded) return Hit.Frames
        val audioTop = rulerH + FRAMES_DP * dp
        if (y < audioTop) return Hit.Frames
        val ts = vm.tracks()
        val rowsH = min(ts.size, MAX_VISIBLE_ROWS) * ROW_DP * dp
        if (y >= audioTop + rowsH) return Hit.AddTrack
        val row = floor((y - audioTop + vScroll) / (ROW_DP * dp)).toInt()
        val track = ts.getOrNull(row) ?: return Hit.AddTrack
        if (x < 72 * dp) return Hit.TrackLabel(track, y)
        val ms = xToMs(x)
        val clip = vm.clips().lastOrNull { it.trackId == track.id && ms >= it.startMs && ms < it.endMs }
            ?: return Hit.TrackEmpty(track)
        val edge = 14 * dp
        val part = when {
            x - msToX(clip.startMs) < edge -> ClipPart.Start
            msToX(clip.endMs) - x < edge -> ClipPart.End
            else -> ClipPart.Body
        }
        return Hit.OnClip(clip, part)
    }

    /** Snaps a time to the nearest frame start when within a few pixels. */
    fun snapMs(ms: Long): Long {
        val t = vm.timing() ?: return ms
        val tick = (ms * vm.fps / 1000.0)
        val fi = t.frameAtTick(tick.roundToInt().toLong())
        for (cand in listOf(t.startTick(fi), t.endTick(fi))) {
            val candMs = cand * 1000L / vm.fps
            if (abs(msToX(candMs) - msToX(ms)) < 6 * dp) return candMs
        }
        return ms
    }

    val track: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .onSizeChanged { viewWidth = it.width }
                .pointerInput(vm) {
                    var flingJob: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        val startHit = hit(down.position.x, down.position.y)
                        val slop = viewConfiguration.touchSlop
                        // 1 = tap, 2 = drag, 3 = pinch, null = long press
                        val kind = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var k = 0
                            while (k == 0) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                k = when {
                                    ev.changes.count { it.pressed } >= 2 -> 3
                                    c == null || !c.pressed -> 1
                                    (c.position - down.position).getDistance() > slop -> 2
                                    else -> 0
                                }
                            }
                            k
                        }
                        when {
                            kind == 1 -> when (startHit) {
                                is Hit.Ruler, is Hit.Frames -> frameAtX(down.position.x)?.let { fi ->
                                    if (vm.rangeMode && startHit is Hit.Frames) vm.tapFrameInRange(fi) else vm.selectFrame(fi)
                                }
                                is Hit.OnClip -> {
                                    vm.selectedTrackId = startHit.clip.trackId
                                    vm.selectedClipId = startHit.clip.id
                                }
                                is Hit.TrackLabel -> {
                                    vm.selectedTrackId = startHit.track.id
                                    trackMenu = startHit.track.id
                                    trackMenuY = startHit.y
                                }
                                is Hit.TrackEmpty -> vm.selectedTrackId = startHit.track.id
                                Hit.AddTrack -> vm.addTrack()
                            }
                            kind == null && startHit is Hit.OnClip -> {
                                // Long-press on a clip: drag to move (or trim at its edges).
                                val original = startHit.clip
                                val assetMs = vm.assetDurationMs(original.assetId)
                                vm.selectedTrackId = original.trackId
                                var moved = false
                                do {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (c.pressed) {
                                        val dMs = xToMs(c.position.x) - xToMs(down.position.x)
                                        val updated = when (startHit.part) {
                                            ClipPart.Start -> {
                                                val newIn = (original.inMs + dMs).coerceIn(0L, original.outMs - 50)
                                                original.copy(inMs = newIn, startMs = (original.startMs + (newIn - original.inMs)).coerceAtLeast(0))
                                            }
                                            ClipPart.End -> original.copy(
                                                outMs = (original.outMs + dMs).coerceIn(original.inMs + 50, assetMs),
                                            )
                                            ClipPart.Body -> {
                                                val target = hit(c.position.x, c.position.y)
                                                val trackId = when (target) {
                                                    is Hit.OnClip -> target.clip.trackId
                                                    is Hit.TrackEmpty -> target.track.id
                                                    is Hit.TrackLabel -> target.track.id
                                                    else -> original.trackId
                                                }
                                                original.copy(startMs = snapMs((original.startMs + dMs).coerceAtLeast(0)), trackId = trackId)
                                            }
                                        }
                                        moved = true
                                        vm.moveClipLive(updated)
                                    }
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                                if (moved) vm.finishClipDrag(original) else vm.selectedClipId = original.id
                            }
                            kind == null -> {
                                when (startHit) {
                                    is Hit.Frames -> frameAtX(down.position.x)?.let {
                                        menuFrame = it
                                        menuX = down.position.x
                                    }
                                    is Hit.TrackLabel -> {
                                        trackMenu = startHit.track.id
                                        trackMenuY = startHit.y
                                    }
                                    else -> Unit
                                }
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                            else -> {
                                val velocity = VelocityTracker()
                                val scrubbing = startHit is Hit.Ruler && kind == 2
                                val inAudio = startHit !is Hit.Ruler && startHit !is Hit.Frames
                                var vertical: Boolean? = null
                                var pinching = false
                                do {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        pinching = true
                                        val z = ev.calculateZoom()
                                        val cx = ev.calculateCentroid().x
                                        val newZoom = (zoom * z).coerceIn(minZoom, maxZoom)
                                        val tickAtFinger = (cx + scroll) / zoom
                                        zoom = newZoom
                                        scroll = clampScroll(tickAtFinger * newZoom - cx)
                                    } else if (pressed.size == 1 && !pinching) {
                                        val c = pressed[0]
                                        val dx = c.position.x - c.previousPosition.x
                                        val dy = c.position.y - c.previousPosition.y
                                        if (vertical == null) {
                                            val tdx = c.position.x - down.position.x
                                            val tdy = c.position.y - down.position.y
                                            vertical = inAudio && abs(tdy) > abs(tdx)
                                        }
                                        when {
                                            scrubbing -> frameAtX(c.position.x.coerceAtLeast(0f))?.let { vm.selectFrame(it) }
                                            vertical == true -> {
                                                val maxV = max(0f, (vm.tracks().size - MAX_VISIBLE_ROWS) * ROW_DP * dp)
                                                vScroll = (vScroll - dy).coerceIn(0f, maxV)
                                            }
                                            else -> {
                                                scroll = clampScroll(scroll - dx)
                                                velocity.addPosition(c.uptimeMillis, c.position)
                                            }
                                        }
                                    }
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                                if (!scrubbing && !pinching && vertical != true && kind == 2) {
                                    val v = -velocity.calculateVelocity().x
                                    flingJob = scope.launch {
                                        AnimationState(scroll, v).animateDecay(exponentialDecay()) {
                                            val clamped = clampScroll(value)
                                            scroll = clamped
                                            if (clamped != value) cancelAnimation()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            val colors = TimelineColors(
                background = MaterialTheme.colorScheme.surfaceContainerLow,
                cell = Color(0x26FFFFFF),
                primary = MaterialTheme.colorScheme.primary,
                ruler = MaterialTheme.colorScheme.surfaceContainerHigh,
                text = MaterialTheme.colorScheme.onSurface,
            )
            Canvas(Modifier.fillMaxSize()) {
                vm.renderTick // redraw when the live frame changes
                drawTimeline(
                    vm = vm, timing = timing, frames = frames, thumbs = thumbs,
                    zoom = zoom, scroll = scroll, f0 = f0, f1 = f1,
                    expanded = expanded, colors = colors, measurer = textMeasurer,
                )
                if (expanded) {
                    drawAudio(
                        vm = vm, tracks = tracks, clips = clips, zoom = zoom, scroll = scroll,
                        vScroll = vScroll, top = (RULER_DP + FRAMES_DP) * density, colors = colors,
                        measurer = textMeasurer, animTicks = animTicks, totalTicks = total,
                    )
                }
            }
            // Long-press menu for a frame, anchored where the finger was.
            Box(Modifier.offset { IntOffset(menuX.roundToInt(), 0) }) {
                FrameMenu(
                    vm = vm,
                    index = menuFrame,
                    onDismiss = { menuFrame = -1 },
                    onHold = { holdTarget = it },
                    onDelete = { vm.selectFrame(it); confirmDelete = true },
                )
            }
            // Menu for a sound track.
            Box(Modifier.offset { IntOffset((8 * dp).roundToInt(), trackMenuY.roundToInt()) }) {
                val t = tracks.firstOrNull { it.id == trackMenu }
                DropdownMenu(expanded = t != null, onDismissRequest = { trackMenu = -1L }) {
                    if (t != null) {
                        Text(t.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        DropdownMenuItem(text = { Text(if (t.muted) "Unmute" else "Mute") }, onClick = {
                            trackMenu = -1L
                            vm.changeTrack(t.copy(muted = !t.muted))
                        })
                        DropdownMenuItem(text = { Text("Volume… (${(t.volume * 100).roundToInt()}%)") }, onClick = { trackMenu = -1L; volumeTrack = t.id })
                        DropdownMenuItem(text = { Text("Rename…") }, onClick = { trackMenu = -1L; renameTrack = t.id })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Record on this track") }, onClick = {
                            trackMenu = -1L
                            vm.selectedTrackId = t.id
                            showRecord = true
                        })
                        DropdownMenuItem(text = { Text("Import sound here") }, onClick = {
                            trackMenu = -1L
                            vm.selectedTrackId = t.id
                            importLauncher.launch(arrayOf("audio/*", "video/*"))
                        })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Delete track") }, onClick = { trackMenu = -1L; deleteTrack = t.id })
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(vm)
                val ms = if (vm.isPlaying) Timing.ticksToMs(vm.playTick, fps)
                else Timing.ticksToMs(timing.startTick(follow.coerceIn(0, timing.frameCount - 1)), fps)
                Column(Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(Timing.formatMs(ms), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text("${follow + 1} / ${vm.frameCount}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                SmallIconButton(Icons.Filled.ViewWeek, "Select frames", selected = vm.rangeMode, onClick = vm::toggleRangeMode)
                SmallIconButton(Icons.Filled.Add, "Add blank frame", onClick = { vm.addFrame(FramePosition.AFTER) })
                SmallIconButton(
                    Icons.Filled.LibraryAdd,
                    if (vm.duplicateToEnd) "Duplicate frame to end" else "Duplicate frame",
                    onClick = { vm.duplicateFrame() },
                )
                SmallIconButton(Icons.Filled.Mic, "Record sound", onClick = { showRecord = true })
                SmallIconButton(Icons.Filled.MusicNote, "Import sound", onClick = { importLauncher.launch(arrayOf("audio/*", "video/*")) })
                SmallIconButton(Icons.Filled.Tune, "Animation settings", onClick = { showSettings = true })
                SmallIconButton(Icons.Filled.ExpandMore, "Smaller timeline", onClick = { expanded = false })
            }
            if (vm.rangeMode) RangeBar(vm, onHold = { holdTarget = -1 }, onDelete = { confirmDelete = true })
            val audioDp = visibleRows * ROW_DP + ADD_ROW_DP
            track(Modifier.fillMaxWidth().height((RULER_DP + FRAMES_DP + audioDp + 4).dp))
        } else {
            if (vm.rangeMode) RangeBar(vm, onHold = { holdTarget = -1 }, onDelete = { confirmDelete = true })
            Row(
                Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(vm)
                track(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(8.dp)))
                SmallIconButton(Icons.Filled.Add, "Add blank frame", onClick = { vm.addFrame(FramePosition.AFTER) })
                SmallIconButton(
                    Icons.Filled.LibraryAdd,
                    if (vm.duplicateToEnd) "Duplicate frame to end" else "Duplicate frame",
                    onClick = { vm.duplicateFrame() },
                )
                SmallIconButton(Icons.Filled.ExpandLess, "Bigger timeline (with sound)", onClick = { expanded = true })
            }
        }
    }

    if (showSettings) AnimationSettingsDialog(vm, onDismiss = { showSettings = false })
    if (showRecord) RecordSheet(vm, onDismiss = { showRecord = false })
    vm.selectedClipId?.let { id ->
        if (clips.any { it.id == id }) ClipSheet(vm, id, onDismiss = { vm.selectedClipId = null })
    }
    if (holdTarget > -2) {
        val target = holdTarget
        val initial = if (target >= 0) frames.getOrNull(target)?.hold ?: 1
        else frames.getOrNull(if (vm.hasRange) vm.rangeStart else vm.frameIndex)?.hold ?: 1
        HoldDialog(
            initial = initial,
            fps = vm.fps,
            title = if (target < 0 && vm.hasRange) "Hold for ${vm.rangeCount} frames" else "Frame hold",
            onDismiss = { holdTarget = -2 },
            onConfirm = {
                holdTarget = -2
                if (target >= 0) vm.setFrameHold(target, it) else vm.setHolds(it)
            },
        )
    }
    if (confirmDelete) {
        val what = if (vm.hasRange) "${vm.rangeCount} frames" else "Frame ${vm.frameIndex + 1}"
        ConfirmDialog(
            "Delete frames?", "$what will be removed (you can undo this).", "Delete",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            vm.deleteFrames()
        }
    }
    tracks.firstOrNull { it.id == renameTrack }?.let { t ->
        TextInputDialog("Rename track", t.name, onDismiss = { renameTrack = -1L }) {
            renameTrack = -1L
            vm.changeTrack(t.copy(name = it))
        }
    }
    tracks.firstOrNull { it.id == volumeTrack }?.let { t ->
        VolumeDialog("${t.name} volume", t.volume, onDismiss = { volumeTrack = -1L }) {
            volumeTrack = -1L
            vm.changeTrack(t.copy(volume = it))
        }
    }
    tracks.firstOrNull { it.id == deleteTrack }?.let { t ->
        ConfirmDialog(
            "Delete track?", "\"${t.name}\" and its sounds will be removed (you can undo this).", "Delete",
            onDismiss = { deleteTrack = -1L },
        ) {
            deleteTrack = -1L
            vm.deleteTrack(t.id)
        }
    }
}

private sealed interface Hit {
    data object Ruler : Hit
    data object Frames : Hit
    data object AddTrack : Hit
    data class TrackLabel(val track: SoundTrack, val y: Float) : Hit
    data class TrackEmpty(val track: SoundTrack) : Hit
    data class OnClip(val clip: AudioClip, val part: ClipPart) : Hit
}

private enum class ClipPart { Start, Body, End }

private class TimelineColors(
    val background: Color,
    val cell: Color,
    val primary: Color,
    val ruler: Color,
    val text: Color,
)

private fun DrawScope.drawTimeline(
    vm: EditorViewModel,
    timing: Timing,
    frames: List<Frame>,
    thumbs: Map<Long, ImageBitmap>,
    zoom: Float,
    scroll: Float,
    f0: Int,
    f1: Int,
    expanded: Boolean,
    colors: TimelineColors,
    measurer: TextMeasurer,
) {
    val dp = density
    drawRect(colors.background)
    val rulerH = if (expanded) RULER_DP * dp else 0f
    val top = rulerH + 3 * dp
    val bottom = (if (expanded) rulerH + FRAMES_DP * dp else size.height) - 3 * dp
    val cellH = bottom - top
    val gap = 1.5f * dp
    val labelStyle = TextStyle(color = Color.White, fontSize = 9.sp)
    val badgeStyle = TextStyle(color = Color.Black, fontSize = 9.sp)
    val playing = vm.isPlaying
    val current = if (playing) vm.playIndex else vm.frameIndex
    val last = min(f1, frames.lastIndex)

    for (fi in f0..last) {
        val uid = frames[fi].uid
        val hold = frames[fi].hold
        val x0 = timing.startTick(fi) * zoom - scroll + gap
        val w = hold * zoom - 2 * gap
        if (w <= 0f) continue
        val inRange = vm.hasRange && fi in vm.rangeStart..vm.rangeEnd
        drawRoundRect(
            if (inRange) RangeTint else colors.cell,
            Offset(x0, top), Size(w, cellH), CornerRadius(4 * dp),
        )
        // Thumbnail, fitted to the cell keeping the canvas aspect ratio.
        val img = if (fi == vm.frameIndex && !playing) vm.image else thumbs[uid]
        if (img != null && w >= 12 * dp) {
            val pad = 2 * dp
            val s = min((w - 2 * pad) / vm.width, (cellH - 2 * pad) / vm.height)
            val tw = vm.width * s
            val th = vm.height * s
            val tx = x0 + (w - tw) / 2
            val ty = top + (cellH - th) / 2
            drawRect(Color(0xFFBDBDBD), Offset(tx, ty), Size(tw, th))
            drawImage(
                img,
                dstOffset = IntOffset(tx.roundToInt(), ty.roundToInt()),
                dstSize = IntSize(max(1, tw.roundToInt()), max(1, th.roundToInt())),
                filterQuality = FilterQuality.None,
            )
        }
        if (w >= 16 * dp) {
            val label = measurer.measure("${fi + 1}", labelStyle)
            drawRoundRect(
                Color(0xAA000000),
                Offset(x0 + 2 * dp, bottom - label.size.height - 2 * dp),
                Size(label.size.width + 4 * dp, label.size.height.toFloat()),
                CornerRadius(3 * dp),
            )
            drawText(label, topLeft = Offset(x0 + 4 * dp, bottom - label.size.height - 2 * dp))
        }
        if (hold > 1 && w >= 30 * dp) {
            val badge = measurer.measure("×$hold", badgeStyle)
            val bx = x0 + w - badge.size.width - 6 * dp
            drawRoundRect(
                colors.primary, Offset(bx, top + 2 * dp),
                Size(badge.size.width + 4 * dp, badge.size.height.toFloat()), CornerRadius(3 * dp),
            )
            drawText(badge, topLeft = Offset(bx + 2 * dp, top + 2 * dp))
        }
        if (fi == current) {
            drawRoundRect(
                colors.primary, Offset(x0, top), Size(w, cellH), CornerRadius(4 * dp),
                style = Stroke(2.5f * dp),
            )
        }
    }

    if (expanded) {
        drawRect(colors.ruler, Offset.Zero, Size(size.width, rulerH))
        // Pick a label step that leaves ~64dp between labels.
        val fps = vm.fps
        val candidates = doubleArrayOf(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 300.0, 600.0)
        val minTicks = (64 * dp) / zoom
        val stepSec = candidates.firstOrNull { it * fps >= minTicks } ?: 3600.0
        val stepTicks = max(1.0, stepSec * fps)
        var k = ceil((scroll / zoom) / stepTicks).toLong()
        val rulerStyle = TextStyle(color = colors.text, fontSize = 9.sp)
        while (true) {
            val tick = k * stepTicks
            val x = (tick * zoom - scroll).toFloat()
            if (x > size.width) break
            drawLine(colors.text.copy(alpha = 0.5f), Offset(x, rulerH - 6 * dp), Offset(x, rulerH), 1 * dp)
            val secs = tick / fps
            val text = if (secs >= 60) "%d:%02d".format((secs / 60).toInt(), (secs % 60).toInt())
            else if (stepSec < 1) "%.2fs".format(secs) else "%.0fs".format(secs)
            drawText(measurer.measure(text, rulerStyle), topLeft = Offset(x + 2 * dp, 2 * dp))
            k++
        }
    }
}

/** Sound tracks: label chips, clips with waveforms, the add-track row and the playhead. */
private fun DrawScope.drawAudio(
    vm: EditorViewModel,
    tracks: List<SoundTrack>,
    clips: List<AudioClip>,
    zoom: Float,
    scroll: Float,
    vScroll: Float,
    top: Float,
    colors: TimelineColors,
    measurer: TextMeasurer,
    animTicks: Long,
    totalTicks: Long,
) {
    val dp = density
    val rowH = ROW_DP * dp
    val rowsH = min(tracks.size, MAX_VISIBLE_ROWS) * rowH
    val fps = vm.fps
    fun msToX(ms: Long) = (ms * fps / 1000f) * zoom - scroll
    val nameStyle = TextStyle(color = Color.White, fontSize = 9.sp)
    val labelStyle = TextStyle(color = colors.text, fontSize = 10.sp)

    clipRect(0f, top, size.width, top + rowsH) {
        tracks.forEachIndexed { ti, t ->
            val y0 = top + ti * rowH - vScroll
            if (y0 + rowH < top || y0 > top + rowsH) return@forEachIndexed
            val selected = vm.selectedTrackId == t.id
            drawRect(
                if (selected) colors.primary.copy(alpha = 0.10f) else if (ti % 2 == 0) Color(0x14FFFFFF) else Color(0x08FFFFFF),
                Offset(0f, y0), Size(size.width, rowH),
            )
            val color = TrackColors[ti % TrackColors.size].let { if (t.muted) it.copy(alpha = 0.35f) else it }
            for (c in clips) {
                if (c.trackId != t.id) continue
                val x0 = msToX(c.startMs)
                val x1 = msToX(c.endMs)
                if (x1 < 0 || x0 > size.width) continue
                val cy0 = y0 + 3 * dp
                val ch = rowH - 6 * dp
                drawRoundRect(color.copy(alpha = color.alpha * 0.45f), Offset(x0, cy0), Size(x1 - x0, ch), CornerRadius(4 * dp))
                // Waveform for the visible part of the clip.
                val peaks = vm.peaks(c.assetId)
                if (peaks != null && peaks.isNotEmpty()) {
                    val step = 2 * dp
                    var x = max(x0, 0f)
                    val end = min(x1, size.width)
                    val mid = cy0 + ch / 2
                    val msPerPx = 1000f / fps / zoom
                    while (x < end) {
                        val srcMs = c.inMs + ((x - x0) * msPerPx).toLong()
                        val lvl = Peaks.level(peaks, srcMs, srcMs + (step * msPerPx).toLong() + 1) * c.volume
                        val half = (lvl.coerceAtMost(1f) * ch / 2).coerceAtLeast(0.5f * dp)
                        drawLine(color, Offset(x, mid - half), Offset(x, mid + half), 1.2f * dp)
                        x += step
                    }
                }
                if (x1 - x0 > 40 * dp) {
                    val name = measurer.measure(vm.assetName(c.assetId), nameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1)
                    drawText(name, topLeft = Offset(max(x0, 0f) + 4 * dp, cy0 + 1 * dp))
                }
                if (vm.selectedClipId == c.id) {
                    drawRoundRect(Color.White, Offset(x0, cy0), Size(x1 - x0, ch), CornerRadius(4 * dp), style = Stroke(2 * dp))
                }
            }
            // Track name chip (fixed at the left edge).
            val label = measurer.measure((if (t.muted) "🔇 " else "") + t.name.take(10), labelStyle)
            drawRoundRect(
                Color(0xCC1E1E24), Offset(2 * dp, y0 + 4 * dp),
                Size(min(label.size.width + 10 * dp, 70 * dp), rowH - 8 * dp), CornerRadius(6 * dp),
            )
            clipRect(2 * dp, y0, 70 * dp, y0 + rowH) {
                drawText(label, topLeft = Offset(7 * dp, y0 + (rowH - label.size.height) / 2))
            }
        }
    }
    // Where the animation ends: beyond it, it repeats (loop to sound) or the sound is cut.
    val endX = animTicks * zoom - scroll
    if (totalTicks > animTicks && endX < size.width) {
        val shade = if (vm.loopToAudio) Color(0x1A3DD6A0) else Color(0x66000000)
        drawRect(shade, Offset(max(endX, 0f), top - FRAMES_DP * dp), Size(size.width - max(endX, 0f), FRAMES_DP * dp + rowsH))
        drawLine(colors.text.copy(alpha = 0.6f), Offset(endX, top - FRAMES_DP * dp), Offset(endX, top + rowsH), 1.5f * dp)
        val text = if (vm.loopToAudio) "↻ animation repeats" else "sound after here is cut (turn on looping)"
        drawText(measurer.measure(text, TextStyle(color = colors.text, fontSize = 9.sp)), topLeft = Offset(max(endX, 0f) + 4 * dp, top - FRAMES_DP * dp + 2 * dp))
    }
    // Add-track row
    val addTop = top + rowsH
    drawRect(Color(0x10FFFFFF), Offset(0f, addTop), Size(size.width, ADD_ROW_DP * dp))
    drawText(
        measurer.measure(if (tracks.isEmpty()) "＋ Add a sound track (or use 🎤 / ♫ above)" else "＋ Add track", labelStyle),
        topLeft = Offset(8 * dp, addTop + 6 * dp),
    )
    // Playhead through everything: where the sound is while playing, else the current frame.
    val timing = vm.timing()
    val headTick = when {
        vm.isPlaying -> vm.playTick
        timing != null && vm.frameIndex in 0 until timing.frameCount -> timing.startTick(vm.frameIndex)
        else -> null
    }
    if (headTick != null) {
        val px = headTick * zoom - scroll
        drawLine(colors.primary, Offset(px, 0f), Offset(px, size.height), 2 * dp)
    }
}

@Composable
private fun PlayButton(vm: EditorViewModel) {
    SmallIconButton(
        if (vm.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
        if (vm.isPlaying) "Stop" else "Play",
        enabled = vm.frameCount > 1 || vm.clips().isNotEmpty(),
        onClick = vm::togglePlay,
    )
}

/** Long-press menu for one frame. */
@Composable
private fun FrameMenu(
    vm: EditorViewModel,
    index: Int,
    onDismiss: () -> Unit,
    onHold: (Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val frames = vm.frames()
    DropdownMenu(expanded = index in frames.indices, onDismissRequest = onDismiss) {
        if (index !in frames.indices) return@DropdownMenu
        val hasClip = vm.frameClipInfo != null
        fun item(text: String, enabled: Boolean = true, action: () -> Unit) = Pair(text, enabled to action)
        val items = listOf(
            item("Select frames from here") { vm.startRangeAt(index); vm.selectFrame(index) },
            null,
            item("Insert blank before") { vm.selectFrame(index); vm.addFrame(FramePosition.BEFORE) },
            item("Insert blank after") { vm.selectFrame(index); vm.addFrame(FramePosition.AFTER) },
            item("Duplicate after") { vm.selectFrame(index); vm.duplicateFrame(toEnd = false) },
            item("Duplicate to end") { vm.selectFrame(index); vm.duplicateFrame(toEnd = true) },
            null,
            item("Copy frame") { vm.clearRange(); vm.selectFrame(index); vm.copyFrames() },
            item("Paste before", hasClip) { vm.clearRange(); vm.selectFrame(index); vm.pasteFrames(FramePosition.BEFORE) },
            item("Paste after", hasClip) { vm.clearRange(); vm.selectFrame(index); vm.pasteFrames(FramePosition.AFTER) },
            null,
            item("Hold… (now ${frames[index].hold}×)") { onHold(index) },
            item("Move left", index > 0) { vm.moveFrame(index, index - 1) },
            item("Move right", index < frames.lastIndex) { vm.moveFrame(index, index + 1) },
            item("Delete", frames.size > 1) { vm.clearRange(); onDelete(index) },
        )
        items.forEach { entry ->
            if (entry == null) {
                HorizontalDivider()
            } else {
                val (text, rest) = entry
                val (enabled, action) = rest
                DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = { onDismiss(); action() })
            }
        }
    }
}

/** Actions for a selected run of frames. */
@Composable
private fun RangeBar(vm: EditorViewModel, onHold: () -> Unit, onDelete: () -> Unit) {
    var pasteMenu by remember { mutableStateOf(false) }
    var dupMenu by remember { mutableStateOf(false) }
    val hasClip = vm.frameClipInfo != null
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            if (vm.hasRange) "Frames ${vm.rangeStart + 1}–${vm.rangeEnd + 1}\n(${vm.rangeCount})" else "Tap frames",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(end = 4.dp),
        )
        val sel = vm.hasRange
        RangeButton(Icons.Filled.ContentCopy, "Copy", sel) { vm.copyFrames() }
        RangeButton(Icons.Filled.ContentCut, "Cut", sel) { vm.copyFrames(cut = true) }
        Box {
            RangeButton(Icons.Filled.ContentPaste, "Paste", hasClip) { pasteMenu = true }
            DropdownMenu(expanded = pasteMenu, onDismissRequest = { pasteMenu = false }) {
                vm.frameClipInfo?.let {
                    Text(
                        "${it.frames} frame${if (it.frames == 1) "" else "s"} on clipboard",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                DropdownMenuItem(text = { Text("Before selection") }, onClick = { pasteMenu = false; vm.pasteFrames(FramePosition.BEFORE) })
                DropdownMenuItem(text = { Text("After selection") }, onClick = { pasteMenu = false; vm.pasteFrames(FramePosition.AFTER) })
                DropdownMenuItem(text = { Text("At the end") }, onClick = { pasteMenu = false; vm.pasteFrames(FramePosition.END) })
            }
        }
        Box {
            RangeButton(Icons.Filled.LibraryAdd, "Duplicate", sel) { dupMenu = true }
            DropdownMenu(expanded = dupMenu, onDismissRequest = { dupMenu = false }) {
                DropdownMenuItem(text = { Text("After selection") }, onClick = { dupMenu = false; vm.duplicateFrames(toEnd = false) })
                DropdownMenuItem(text = { Text("At the end") }, onClick = { dupMenu = false; vm.duplicateFrames(toEnd = true) })
            }
        }
        RangeButton(Icons.Filled.SwapHoriz, "Reverse", sel && vm.rangeCount > 1) { vm.reverseFrames() }
        RangeButton(Icons.Filled.Timer, "Hold", sel) { onHold() }
        RangeButton(Icons.AutoMirrored.Filled.ArrowBack, "Earlier", sel && vm.rangeStart > 0) { vm.moveFrames(-1) }
        RangeButton(Icons.AutoMirrored.Filled.ArrowForward, "Later", sel && vm.rangeEnd < vm.frameCount - 1) { vm.moveFrames(1) }
        RangeButton(Icons.Filled.Delete, "Delete", sel && vm.rangeCount < vm.frameCount) { onDelete() }
        RangeButton(Icons.Filled.Close, "Done", true) { vm.toggleRangeMode() }
    }
}

@Composable
private fun RangeButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(20.dp))
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun SmallIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = if (selected) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) { Icon(icon, label, Modifier.size(22.dp)) }
}

/** 0–200% volume. */
@Composable
fun VolumeDialog(title: String, initial: Float, onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("${(v * 100).roundToInt()}%")
                Slider(value = v, onValueChange = { v = it }, valueRange = 0f..2f)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(v) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

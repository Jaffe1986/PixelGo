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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelgo.data.audio.Peaks
import com.pixelgo.editor.ClipGain
import com.pixelgo.editor.FramePosition
import com.pixelgo.editor.Timing
import com.pixelgo.model.AUDIO_RATE
import com.pixelgo.model.AudioClip
import com.pixelgo.model.FRAMES_PER_MS
import com.pixelgo.model.Frame
import com.pixelgo.model.GainPoint
import com.pixelgo.model.SoundTrack
import com.pixelgo.ui.common.ActionBar
import com.pixelgo.ui.common.BarAction
import com.pixelgo.ui.common.Choice
import com.pixelgo.ui.common.OnionSkinIcon
import com.pixelgo.ui.common.PgIconButton
import com.pixelgo.ui.common.PgTextButton
import com.pixelgo.ui.common.PrimaryButton
import com.pixelgo.ui.common.RoundIconButton
import com.pixelgo.ui.common.SliderRow
import com.pixelgo.ui.common.TextInputDialog
import com.pixelgo.ui.common.drawCheckerboard
import com.pixelgo.ui.theme.Pg
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val RULER_DP = 24
private const val FRAMES_DP = 64
private const val ROW_DP = 40
private const val ADD_ROW_DP = 36
private const val MAX_VISIBLE_ROWS = 3
/** Sound mode's track header column. */
private const val HEADER_DP = 80

/**
 * The animation timeline. Each frame's width is proportional to how long it's held.
 *
 * - Compact: one row of frames with play, onion skin, add and duplicate.
 * - Expanded: a toolbar, a time ruler (drag it to scrub), the frames, and the sound tracks.
 * - The handle on top switches between them (tap, or drag up / down).
 * - Frames: tap = select (or pick a range in range mode), long-press = frame menu.
 * - Sound: tap a clip = its options, tap a track name = track menu, long-press a clip and
 *   drag = move it (edges: trim), drag up/down = scroll the tracks.
 * - Anywhere: drag sideways = scroll (with fling), pinch = zoom.
 *
 * Only what's on screen is drawn, so thousands of frames and long sounds stay fast.
 */
@Composable
fun TimelineView(vm: EditorViewModel, modifier: Modifier = Modifier) {
    var expandedSetting by rememberSaveable { mutableStateOf(false) }
    // Sound mode always shows the full timeline (it fills the screen).
    val expanded = expandedSetting || vm.soundMode
    val dp = LocalDensity.current.density
    var zoom by remember { mutableFloatStateOf(44f * dp) } // screen px per tick
    var scroll by remember { mutableFloatStateOf(0f) }
    var vScroll by remember { mutableFloatStateOf(0f) }
    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }
    var showVolume by remember { mutableStateOf(false) }
    var effectsFor by remember { mutableStateOf<Long?>(null) } // a clip id, or SELECTION
    var cleanupFor by remember { mutableStateOf<Long?>(null) } // a clip id, or SELECTION
    var showPunch by remember { mutableStateOf(false) }
    var showRecord by remember { mutableStateOf(false) }
    var holdTarget by remember { mutableIntStateOf(-2) } // -2 none, -1 range/current, >=0 frame
    var menuFrame by remember { mutableIntStateOf(-1) }
    var menuX by remember { mutableFloatStateOf(0f) }
    var trackMenu by remember { mutableLongStateOf(-1L) }
    var trackMenuY by remember { mutableFloatStateOf(0f) }
    var renameTrack by remember { mutableLongStateOf(-1L) }
    var volumeTrack by remember { mutableLongStateOf(-1L) }
    val thumbs = remember { mutableStateMapOf<Long, ImageBitmap>() }
    val textMeasurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    // The frame start a dragged clip last snapped to (one tick per snap, not per move).
    val lastSnap = remember { longArrayOf(-1L) }
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
    // Sound mode zooms in to about 4 screen pixels per sample.
    val maxZoom = if (vm.soundMode) 4f * AUDIO_RATE / fps else 140f * dp
    val endPad = 48f * dp

    // Track rows: in sound mode they share the free height (never shorter than the chosen track
    // height); read live, gestures outlive compositions.
    fun rowH(): Float {
        if (!vm.soundMode) return ROW_DP * dp
        val fill = (viewHeight - (RULER_DP + FRAMES_DP + ADD_ROW_DP) * dp) / max(1, vm.tracks().size)
        return max(vm.trackHeightDp * dp, fill)
    }
    fun rowsAreaH(): Float {
        val all = vm.tracks().size * rowH()
        return if (vm.soundMode) min(all, max(0f, viewHeight - (RULER_DP + FRAMES_DP + ADD_ROW_DP) * dp))
        else min(vm.tracks().size, MAX_VISIBLE_ROWS) * rowH()
    }
    fun maxVScroll(): Float = max(0f, vm.tracks().size * rowH() - rowsAreaH())

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
    LaunchedEffect(vm.soundMode, vm.trackHeightDp, tracks.size, viewHeight) { vScroll = vScroll.coerceIn(0f, maxVScroll()) }

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
        val rowsH = rowsAreaH()
        if (y >= audioTop + rowsH) return Hit.AddTrack
        val row = floor((y - audioTop + vScroll) / rowH()).toInt()
        val track = ts.getOrNull(row) ?: return Hit.AddTrack
        // The name chip (top-left of the row); in tall rows the space under it is track area.
        val rowTop = audioTop + row * rowH() - vScroll
        if (!vm.soundMode && x < 72 * dp && y < rowTop + 4 * dp + min(rowH() - 8 * dp, 32 * dp) + 4 * dp) return Hit.TrackLabel(track, y)
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

    // ---- Sound mode helpers (live values; gesture handlers outlive this composition) ----------

    fun audioTop(): Float = (RULER_DP + FRAMES_DP) * dp

    /** Top of the clip box in track row [ti], and its height. */
    fun clipBox(ti: Int): Pair<Float, Float> = (audioTop() + ti * rowH() - vScroll + 3 * dp) to (rowH() - 6 * dp)

    /** Snaps to frame starts and to clip edges within a few pixels. */
    fun snapSoundMs(ms: Long): Long {
        val edge = vm.clips().flatMap { listOf(it.startMs, it.endMs) }
            .minByOrNull { abs(msToX(it) - msToX(ms)) }
        if (edge != null && abs(msToX(edge) - msToX(ms)) < 6 * dp) return edge
        return snapMs(ms)
    }

    /** Which row (track index) is at height [y], clamped to the tracks. */
    fun rowAt(y: Float): Int = floor((y - audioTop() + vScroll) / rowH()).toInt().coerceIn(0, max(0, vm.tracks().size - 1))

    /** Something in sound mode that a finger grabs directly: fade handles, volume-line points, selection edges. */
    fun soundTarget(x: Float, y: Float): SoundTarget? {
        if (!vm.soundMode || y < audioTop() || y >= audioTop() + rowsAreaH()) return null
        val grab = 16 * dp
        val ti = rowAt(y)
        val track = vm.tracks().getOrNull(ti) ?: return null
        val (cy0, ch) = clipBox(ti)
        vm.soundSel?.let { s ->
            if (track.id in s.tracks) {
                if (abs(x - msToX(s.fromMs)) < grab) return SoundTarget.SelEdge(true)
                if (abs(x - msToX(s.toMs)) < grab) return SoundTarget.SelEdge(false)
            }
        }
        for (c in vm.clips().filter { it.trackId == track.id }) {
            if (x < msToX(c.startMs) - grab || x > msToX(c.endMs) + grab) continue
            when (vm.soundTool) {
                SoundTool.Move -> if (y < cy0 + 22 * dp) {
                    if (abs(x - msToX(c.startMs + c.fadeInMs)) < grab) return SoundTarget.Fade(c, true)
                    if (abs(x - msToX(c.endMs - c.fadeOutMs)) < grab) return SoundTarget.Fade(c, false)
                }
                SoundTool.Volume -> for (pt in c.envelope) {
                    val tMs = c.startMs + (pt.atMs - c.inMs)
                    if (tMs < c.startMs || tMs > c.endMs) continue
                    if (abs(x - msToX(tMs)) < grab && abs(y - gainToY(pt.gain, cy0, ch)) < grab) return SoundTarget.Point(c, pt)
                }
                SoundTool.Select -> Unit
            }
        }
        return null
    }

    val track: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .onSizeChanged {
                    viewWidth = it.width
                    viewHeight = it.height
                }
                .semantics {
                    contentDescription = "Timeline, frame ${vm.frameIndex + 1} of ${vm.frameCount}" +
                        if (tracks.isNotEmpty()) ", ${tracks.size} sound track${if (tracks.size == 1) "" else "s"}" else ""
                    customActions = listOf(
                        CustomAccessibilityAction("Previous frame") { if (vm.frameIndex > 0) vm.selectFrame(vm.frameIndex - 1); true },
                        CustomAccessibilityAction("Next frame") { if (vm.frameIndex < vm.frameCount - 1) vm.selectFrame(vm.frameIndex + 1); true },
                    )
                }
                .pointerInput(vm) {
                    var flingJob: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        val startHit = hit(down.position.x, down.position.y)
                        val target = soundTarget(down.position.x, down.position.y)
                        val sound = vm.soundMode
                        val tool = vm.soundTool
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
                            // ---- Sound mode: taps and direct drags ------------------------------
                            kind == 1 && sound && startHit !is Hit.TrackLabel && startHit != Hit.AddTrack -> {
                                val x = down.position.x
                                when (startHit) {
                                    is Hit.Ruler -> vm.setCursor(snapSoundMs(xToMs(x)))
                                    is Hit.Frames -> frameAtX(x)?.let { fi ->
                                        if (vm.rangeMode) vm.tapFrameInRange(fi) else vm.selectFrame(fi)
                                    }
                                    is Hit.OnClip -> {
                                        vm.selectedTrackId = startHit.clip.trackId
                                        when (tool) {
                                            SoundTool.Move -> {
                                                vm.setCursor(xToMs(x))
                                                vm.selectedClipId = startHit.clip.id
                                            }
                                            SoundTool.Volume -> vm.addVolumePoint(startHit.clip.id, xToMs(x))
                                            SoundTool.Select -> {
                                                vm.soundSel = null
                                                vm.setCursor(snapSoundMs(xToMs(x)))
                                            }
                                        }
                                    }
                                    is Hit.TrackEmpty -> {
                                        vm.selectedTrackId = startHit.track.id
                                        vm.soundSel = null
                                        vm.setCursor(snapSoundMs(xToMs(x)))
                                    }
                                    else -> Unit
                                }
                            }
                            kind == null && target is SoundTarget.Point -> {
                                // Long-press a volume point: remove it.
                                vm.removeVolumePoint(target.clip.id, target.point.atMs)
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                            kind == 2 && target != null -> {
                                // Drag a fade handle, a volume point or a selection edge.
                                val selAtStart = vm.soundSel
                                var moved = false
                                do {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (c.pressed) {
                                        val ms = xToMs(c.position.x)
                                        when (target) {
                                            is SoundTarget.Fade -> {
                                                val o = target.clip
                                                vm.moveClipLive(
                                                    if (target.fadeIn) o.copy(fadeInMs = (ms - o.startMs).coerceIn(0L, o.durationMs - o.fadeOutMs))
                                                    else o.copy(fadeOutMs = (o.endMs - ms).coerceIn(0L, o.durationMs - o.fadeInMs)),
                                                )
                                            }
                                            is SoundTarget.Point -> {
                                                val o = target.clip
                                                val ti = vm.tracks().indexOfFirst { it.id == o.trackId }
                                                val (cy0, ch) = clipBox(ti)
                                                val others = o.envelope.filter { it != target.point }
                                                val lo = (others.filter { it.atMs < target.point.atMs }.maxOfOrNull { it.atMs } ?: (o.inMs - 1)) + 1
                                                val hi = (others.filter { it.atMs > target.point.atMs }.minOfOrNull { it.atMs } ?: (o.outMs + 1)) - 1
                                                val at = o.sourceMs(ms).coerceIn(lo, max(lo, hi))
                                                vm.moveClipLive(o.copy(envelope = (others + GainPoint(at, yToGain(c.position.y, cy0, ch))).sortedBy { it.atMs }))
                                            }
                                            is SoundTarget.SelEdge -> selAtStart?.let { s ->
                                                val snapped = snapSoundMs(ms)
                                                vm.soundSel = if (target.start) s.copy(fromMs = min(snapped, s.toMs - 1))
                                                else s.copy(toMs = max(snapped, s.fromMs + 1))
                                            }
                                        }
                                        moved = true
                                    }
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                                if (moved) when (target) {
                                    is SoundTarget.Fade -> vm.finishClipDrag(target.clip)
                                    is SoundTarget.Point -> vm.finishClipDrag(target.clip)
                                    is SoundTarget.SelEdge -> Unit
                                }
                            }
                            kind == 2 && sound && tool == SoundTool.Select && (startHit is Hit.OnClip || startHit is Hit.TrackEmpty) -> {
                                // Select tool: drag across tracks to select a section.
                                val fromMs = snapSoundMs(xToMs(down.position.x))
                                val r0 = rowAt(down.position.y)
                                do {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (c.pressed) {
                                        val ms = snapSoundMs(xToMs(c.position.x)).coerceAtLeast(0)
                                        val r1 = rowAt(c.position.y)
                                        val ts = vm.tracks()
                                        val ids = (min(r0, r1)..max(r0, r1)).mapNotNull { ts.getOrNull(it)?.id }.toSet()
                                        vm.soundSel = if (ms != fromMs && ids.isNotEmpty()) SoundSelection(ids, min(fromMs, ms), max(fromMs, ms)) else null
                                    }
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
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
                            kind == null && startHit is Hit.OnClip && (!sound || tool == SoundTool.Move) -> {
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
                                                val raw = (original.startMs + dMs).coerceAtLeast(0)
                                                val snapped = snapMs(raw)
                                                if (snapped != raw && snapped != lastSnap[0]) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                lastSnap[0] = if (snapped != raw) snapped else -1L
                                                original.copy(startMs = snapped, trackId = trackId)
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
                                var pinchHeight: Boolean? = null // sound mode: fingers one above the other
                                do {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        pinching = true
                                        val p0 = pressed[0]
                                        val p1 = pressed[1]
                                        if (pinchHeight == null) {
                                            pinchHeight = vm.soundMode && inAudio &&
                                                abs(p0.position.y - p1.position.y) > abs(p0.position.x - p1.position.x)
                                        }
                                        if (pinchHeight == true) {
                                            // Taller / shorter tracks.
                                            val before = abs(p0.previousPosition.y - p1.previousPosition.y)
                                            val now = abs(p0.position.y - p1.position.y)
                                            if (before > 8 * dp) vm.setTrackHeight(rowH() / dp * now / before)
                                        } else {
                                            val z = ev.calculateZoom()
                                            val cx = ev.calculateCentroid().x
                                            val newZoom = (zoom * z).coerceIn(minZoom, maxZoom)
                                            val tickAtFinger = (cx + scroll) / zoom
                                            zoom = newZoom
                                            scroll = clampScroll(tickAtFinger * newZoom - cx)
                                        }
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
                                            vertical == true -> vScroll = (vScroll - dy).coerceIn(0f, maxVScroll())
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
                background = Pg.Background,
                cell = Pg.Surface3,
                primary = Pg.Accent,
                ruler = Pg.Surface1,
                text = Pg.Text2,
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
                        vScroll = vScroll, top = (RULER_DP + FRAMES_DP) * density, rowH = rowH(), rowsH = rowsAreaH(),
                        colors = colors, measurer = textMeasurer, animTicks = animTicks, totalTicks = total,
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
                    onDelete = { vm.selectFrame(it); vm.deleteFrames() },
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
                        DropdownMenuItem(text = { Text("Delete track", color = Pg.Danger) }, onClick = { trackMenu = -1L; vm.deleteTrack(t.id) })
                    }
                }
            }
        }
    }

    /**
     * Sound mode: the track names in a fixed column beside the lanes (so they never cover a
     * waveform). Tap one for its menu. Rows line up with the canvas rows and scroll with them.
     */
    val trackHeaders: @Composable (Modifier) -> Unit = { modifier ->
        val rowDp = (rowH() / dp).dp
        val scrollDp = (vScroll / dp).dp
        Box(modifier.background(Pg.Surface1).clipToBounds()) {
            Text(
                "Frames", style = Pg.Mark, color = Pg.Text2,
                modifier = Modifier.padding(start = 10.dp, top = (RULER_DP + FRAMES_DP / 2 - 7).dp),
            )
            Column(Modifier.fillMaxWidth().offset(y = (RULER_DP + FRAMES_DP).dp - scrollDp)) {
                tracks.forEachIndexed { ti, t ->
                    val color = Pg.Tracks[ti % Pg.Tracks.size]
                    val selected = vm.selectedTrackId == t.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(rowDp)
                            .background(if (selected) Pg.AccentWeak else Color.Transparent)
                            .clickable(onClickLabel = "Track options", role = Role.Button) {
                                vm.selectedTrackId = t.id
                                trackMenu = t.id
                                trackMenuY = (RULER_DP + FRAMES_DP) * dp + ti * rowH() - vScroll + rowH() / 2
                            }
                            .semantics(mergeDescendants = true) {
                                contentDescription = t.name + if (t.muted) ", muted" else ""
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.padding(vertical = 6.dp).width(3.dp).fillMaxHeight()
                                .background(if (t.muted) color.copy(alpha = 0.35f) else color, RoundedCornerShape(2.dp)),
                        )
                        Column(Modifier.padding(start = 8.dp, end = 4.dp)) {
                            Text(
                                t.name, style = MaterialTheme.typography.labelMedium,
                                color = if (t.muted) Pg.Text3 else Pg.Text1,
                                textDecoration = if (t.muted) TextDecoration.LineThrough else null,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            if (t.muted) Icon(Icons.AutoMirrored.Filled.VolumeOff, null, tint = Pg.Text3, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (vm.soundMode) {
            SoundModeHeader(
                vm,
                onRecord = { showRecord = true },
                onImport = { importLauncher.launch(arrayOf("audio/*", "video/*")) },
                canPaste = vm.canPasteSound,
            )
            SoundToolsRow(vm, onZoom = { factor ->
                // Zoom around the cursor (kept where it is on screen).
                val anchor = msToX(vm.soundCursorMs()).coerceIn(0f, viewWidth.toFloat())
                val tickAt = (anchor + scroll) / zoom
                val nz = (zoom * factor).coerceIn(minZoom, maxZoom)
                zoom = nz
                scroll = clampScroll(tickAt * nz - anchor)
            }, onTrackHeight = { f -> vm.setTrackHeight(rowH() / dp * f) })
            if (vm.soundSel != null) {
                SoundSelectionBar(
                    vm, onVolume = { showVolume = true }, onEffects = { effectsFor = SELECTION }, onCleanup = { cleanupFor = SELECTION },
                    onReRecord = { showPunch = true }, canPaste = vm.canPasteSound,
                )
            }
            if (vm.rangeMode) RangeBar(vm, onHold = { holdTarget = -1 })
            Row(Modifier.fillMaxWidth().weight(1f)) {
                trackHeaders(Modifier.width(HEADER_DP.dp).fillMaxHeight())
                track(Modifier.weight(1f).fillMaxHeight())
            }
        } else if (expanded) {
            TimelineHandle(expanded = true) { expandedSetting = it }
            Row(
                Modifier.fillMaxWidth().height(Pg.BarHeight).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(vm)
                val ms = if (vm.isPlaying) Timing.ticksToMs(vm.playTick, fps)
                else Timing.ticksToMs(timing.startTick(follow.coerceIn(0, timing.frameCount - 1)), fps)
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(Timing.formatMs(ms), style = Pg.Data, maxLines = 1)
                    Text("${follow + 1} / ${vm.frameCount}", style = Pg.Mark, color = Pg.Text2, maxLines = 1)
                }
                OnionToggle(vm)
                PgIconButton(Icons.Outlined.ViewWeek, "Select frames", vm::toggleRangeMode, checked = vm.rangeMode)
                PgIconButton(Icons.Filled.Add, "Add blank frame", { vm.addFrame(FramePosition.AFTER) })
                PgIconButton(Icons.Outlined.LibraryAdd, duplicateLabel(vm), { vm.duplicateFrame() })
                PgIconButton(Icons.Filled.GraphicEq, "Sound mode", { vm.enterSoundMode(true) })
            }
            if (vm.rangeMode) RangeBar(vm, onHold = { holdTarget = -1 })
            val audioDp = visibleRows * ROW_DP + ADD_ROW_DP
            track(Modifier.fillMaxWidth().height((RULER_DP + FRAMES_DP + audioDp + 4).dp))
        } else {
            if (vm.rangeMode) RangeBar(vm, onHold = { holdTarget = -1 })
            TimelineHandle(expanded = false) { expandedSetting = it }
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(vm)
                track(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(6.dp)))
                OnionToggle(vm)
                PgIconButton(Icons.Filled.Add, "Add blank frame", { vm.addFrame(FramePosition.AFTER) })
                PgIconButton(Icons.Outlined.LibraryAdd, duplicateLabel(vm), { vm.duplicateFrame() })
            }
        }
    }

    if (showVolume) {
        VolumeDialog("Selection volume", 1f, onDismiss = { showVolume = false }) {
            showVolume = false
            vm.setSelectionVolume(it)
        }
    }

    if (showRecord) RecordSheet(vm, onDismiss = { showRecord = false })
    if (showPunch) RecordSheet(vm, onDismiss = { showPunch = false }, punchIn = true)
    effectsFor?.let { target -> EffectsSheet(vm, if (target == SELECTION) null else target, onDismiss = { effectsFor = null }) }
    cleanupFor?.let { target -> CleanupSheet(vm, if (target == SELECTION) null else target, onDismiss = { cleanupFor = null }) }
    vm.selectedClipId?.let { id ->
        if (clips.any { it.id == id }) {
            ClipSheet(vm, id, onDismiss = { vm.selectedClipId = null }, onEffects = {
                vm.selectedClipId = null
                effectsFor = id
            }, onCleanup = {
                vm.selectedClipId = null
                cleanupFor = id
            })
        }
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

/** [effectsFor] value meaning "the selected section". */
private const val SELECTION = -1L

/** Things a finger grabs directly in sound mode. */
private sealed interface SoundTarget {
    data class Fade(val clip: AudioClip, val fadeIn: Boolean) : SoundTarget
    data class Point(val clip: AudioClip, val point: GainPoint) : SoundTarget
    data class SelEdge(val start: Boolean) : SoundTarget
}

/** Volume line height: gain 0 at the bottom of the clip box, 2 (+6 dB) at the top. */
private fun gainToY(g: Float, top: Float, h: Float) = top + h - (g / 2f).coerceIn(0f, 1f) * h
private fun yToGain(y: Float, top: Float, h: Float) = (2f * (top + h - y) / h).coerceIn(0f, 2f)

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
    val labelStyle = Pg.Mark.copy(color = Pg.Text1)
    val badgeStyle = Pg.Mark.copy(color = Pg.Text1)
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
        drawRoundRect(colors.cell, Offset(x0, top), Size(w, cellH), CornerRadius(2 * dp))
        // Thumbnail, fitted to the cell keeping the canvas aspect ratio.
        val img = if (fi == vm.frameIndex && !playing) vm.image else thumbs[uid]
        if (img != null && w >= 12 * dp) {
            val pad = 2 * dp
            val s = min((w - 2 * pad) / vm.width, (cellH - 2 * pad) / vm.height)
            val tw = vm.width * s
            val th = vm.height * s
            val tx = x0 + (w - tw) / 2
            val ty = top + (cellH - th) / 2
            drawCheckerboard(Rect(Offset(tx, ty), Size(tw, th)), max(2 * dp, min(tw, th) / 8))
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
                Color(0xAA000000), Offset(bx, top + 2 * dp),
                Size(badge.size.width + 4 * dp, badge.size.height.toFloat()), CornerRadius(3 * dp),
            )
            drawText(badge, topLeft = Offset(bx + 2 * dp, top + 2 * dp))
        }
        if (inRange) {
            // Frames in the selected range: a yellow wash with a thin outline.
            drawRoundRect(Pg.AccentWeak, Offset(x0, top), Size(w, cellH), CornerRadius(2 * dp))
            drawRoundRect(colors.primary.copy(alpha = 0.6f), Offset(x0, top), Size(w, cellH), CornerRadius(2 * dp), style = Stroke(1.5f * dp))
        }
        if (fi == current) {
            drawRoundRect(
                colors.primary, Offset(x0, top), Size(w, cellH), CornerRadius(2 * dp),
                style = Stroke(2f * dp),
            )
        }
    }

    if (expanded) {
        drawRect(colors.ruler, Offset.Zero, Size(size.width, rulerH))
        // Pick a label step that leaves ~64dp between labels.
        val fps = vm.fps
        val candidates = doubleArrayOf(
            0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 300.0, 600.0,
        )
        val minTicks = (64 * dp) / zoom
        val stepSec = candidates.firstOrNull { it * fps >= minTicks } ?: 3600.0
        val stepTicks = stepSec * fps // may be less than a frame when zoomed in on sound
        var k = ceil((scroll / zoom) / stepTicks).toLong()
        val rulerStyle = Pg.Mark.copy(color = colors.text)
        while (true) {
            val tick = k * stepTicks
            val x = (tick * zoom - scroll).toFloat()
            if (x > size.width) break
            drawLine(colors.text.copy(alpha = 0.5f), Offset(x, rulerH - 6 * dp), Offset(x, rulerH), 1 * dp)
            val secs = tick / fps
            val text = if (secs >= 60) "%d:%02d".format((secs / 60).toInt(), (secs % 60).toInt())
            else if (stepSec < 0.01) "%.3fs".format(secs)
            else if (stepSec < 1) "%.2fs".format(secs) else "%.0fs".format(secs)
            drawText(measurer.measure(text, rulerStyle), topLeft = Offset(x + 2 * dp, 2 * dp))
            k++
        }
    }
}

/**
 * Sound tracks: label chips, clips with waveforms, the add-track row and the playhead. In sound
 * mode also fades (shading + handles), volume lines, the selection and the cursor; zoomed in
 * closer than the 10 ms peaks, the waveform comes from the samples themselves.
 */
private fun DrawScope.drawAudio(
    vm: EditorViewModel,
    tracks: List<SoundTrack>,
    clips: List<AudioClip>,
    zoom: Float,
    scroll: Float,
    vScroll: Float,
    top: Float,
    rowH: Float,
    rowsH: Float,
    colors: TimelineColors,
    measurer: TextMeasurer,
    animTicks: Long,
    totalTicks: Long,
) {
    val dp = density
    val fps = vm.fps
    val sound = vm.soundMode
    val tool = vm.soundTool
    fun msToX(ms: Long) = (ms * fps / 1000f) * zoom - scroll
    val msPerPx = 1000.0 / fps / zoom
    val nameStyle = Pg.Mark.copy(color = Pg.Text1)
    val labelStyle = TextStyle(color = Pg.Text1, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val gains = ClipGain.all(tracks, clips, withTrackVolume = false)
    val scale = if (sound) vm.waveScale.toFloat() else 1f
    val mm = FloatArray(2)
    vm.waveTick // redraw when zoomed-in samples have loaded

    clipRect(0f, top, size.width, top + rowsH) {
        tracks.forEachIndexed { ti, t ->
            val y0 = top + ti * rowH - vScroll
            if (y0 + rowH < top || y0 > top + rowsH) return@forEachIndexed
            val selected = vm.selectedTrackId == t.id
            drawRect(
                if (selected) colors.primary.copy(alpha = 0.08f) else if (ti % 2 == 0) Color(0x14FFFFFF) else Color(0x08FFFFFF),
                Offset(0f, y0), Size(size.width, rowH),
            )
            val color = Pg.Tracks[ti % Pg.Tracks.size].let { if (t.muted) it.copy(alpha = 0.35f) else it }
            for (c in clips) {
                if (c.trackId != t.id) continue
                val x0 = msToX(c.startMs)
                val x1 = msToX(c.endMs)
                if (x1 < 0 || x0 > size.width) continue
                val cy0 = y0 + 3 * dp
                val ch = rowH - 6 * dp
                val mid = cy0 + ch / 2
                val half = ch / 2
                drawRoundRect(color.copy(alpha = color.alpha * 0.45f), Offset(x0, cy0), Size(x1 - x0, ch), CornerRadius(4 * dp))
                val g = gains[c.id]
                fun gainAt(offMs: Double) = (g?.atOffsetMs(offMs) ?: c.volume) * scale
                fun column(x: Float, lo: Float, hi: Float, width: Float) {
                    val yTop = mid - hi.coerceIn(-1f, 1f) * half
                    val yBot = max(mid - lo.coerceIn(-1f, 1f) * half, yTop + 0.8f * dp)
                    drawLine(color, Offset(x, yTop), Offset(x, yBot), width)
                }
                val startX = max(x0, 0f)
                val endX = min(x1, size.width)
                val stepPx = 2 * dp
                if (!sound || msPerPx * stepPx >= 20) {
                    // 10 ms peaks.
                    val peaks = vm.peaks(c.assetId)
                    if (peaks != null && peaks.isNotEmpty()) {
                        var x = startX
                        while (x < endX) {
                            val off = (x - x0) * msPerPx
                            val src = c.inMs + off.toLong()
                            Peaks.minMax(peaks, src, src + (stepPx * msPerPx).toLong() + 1, mm)
                            val gn = gainAt(off)
                            column(x, mm[0] * gn, mm[1] * gn, 1.2f * dp)
                            x += stepPx
                        }
                    }
                } else {
                    val framesPerPx = msPerPx * FRAMES_PER_MS
                    val inF = c.inMs * FRAMES_PER_MS
                    if (framesPerPx > 1.5) {
                        // The lowest / highest sample under each pixel column.
                        var x = floor(startX)
                        while (x < endX) {
                            val off = (x - x0) * msPerPx
                            val f0 = inF + (off * FRAMES_PER_MS).toLong()
                            if (vm.waveforms.minMax(c.assetId, f0, f0 + ceil(framesPerPx).toLong(), mm)) {
                                val gn = gainAt(off)
                                column(x, mm[0] * gn, mm[1] * gn, 1f)
                            }
                            x += 1f
                        }
                    } else {
                        // Each sample is wider than a pixel: draw the line through the samples.
                        val pxPerFrame = (1.0 / framesPerPx).toFloat()
                        val first = max(inF, inF + ((startX - x0) * framesPerPx).toLong() - 1)
                        val last = min(c.outMs * FRAMES_PER_MS - 1, inF + ((endX - x0) * framesPerPx).toLong() + 1)
                        var prev: Offset? = null
                        for (f in first..last) {
                            val v = vm.waveforms.sample(c.assetId, f)
                            if (v == null) {
                                prev = null
                                continue
                            }
                            val off = (f - inF).toDouble() / FRAMES_PER_MS
                            val p = Offset(x0 + (f - inF) * pxPerFrame, mid - (v * gainAt(off)).coerceIn(-1f, 1f) * half)
                            prev?.let { drawLine(color, it, p, 1.5f * dp) }
                            if (pxPerFrame > 6 * dp) drawCircle(color, 2 * dp, p)
                            prev = p
                        }
                    }
                }
                if (sound) {
                    // Fades: shaded corners, with handles to drag (Move tool).
                    val shade = Color(0x66000000)
                    val fi = msToX(c.startMs + c.fadeInMs)
                    val fo = msToX(c.endMs - c.fadeOutMs)
                    if (c.fadeInMs > 0) drawFadeShade(x0, fi, cy0, ch, rising = true, shade)
                    if (c.fadeOutMs > 0) drawFadeShade(fo, x1, cy0, ch, rising = false, shade)
                    if (tool == SoundTool.Move && ch >= 30 * dp) {
                        for (hx in listOf(fi, fo)) {
                            drawRect(Color.White, Offset(hx - 4 * dp, cy0), Size(8 * dp, 8 * dp))
                            drawRect(Color.Black, Offset(hx - 4 * dp, cy0), Size(8 * dp, 8 * dp), style = Stroke(1 * dp))
                        }
                    }
                    // Volume line (always shown once it's used; editable with the Volume tool).
                    if (c.envelope.isNotEmpty() || tool == SoundTool.Volume) {
                        val line = ClipGain(c.copy(volume = 1f, fadeInMs = 0, fadeOutMs = 0))
                        val lineColor = if (tool == SoundTool.Volume) Color.White else Color.White.copy(alpha = 0.6f)
                        var px = startX
                        var py = gainToY(line.atOffsetMs((startX - x0) * msPerPx), cy0, ch)
                        var x = startX + 4 * dp
                        while (true) {
                            val xx = min(x, endX)
                            val gy = gainToY(line.atOffsetMs((xx - x0) * msPerPx), cy0, ch)
                            drawLine(lineColor, Offset(px, py), Offset(xx, gy), 1.5f * dp)
                            px = xx
                            py = gy
                            if (xx >= endX) break
                            x += 4 * dp
                        }
                        if (tool == SoundTool.Volume) {
                            for (pt in c.envelope) {
                                val tMs = c.startMs + (pt.atMs - c.inMs)
                                if (tMs < c.startMs || tMs > c.endMs) continue
                                val p = Offset(msToX(tMs), gainToY(pt.gain, cy0, ch))
                                drawCircle(Color.White, 5 * dp, p)
                                drawCircle(color, 3 * dp, p)
                            }
                        }
                    }
                }
                if (x1 - x0 > 40 * dp) {
                    val name = measurer.measure(vm.assetName(c.assetId), nameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1)
                    drawText(name, topLeft = Offset(max(x0, 0f) + 4 * dp, cy0 + 1 * dp))
                }
                if (vm.selectedClipId == c.id) {
                    drawRoundRect(Pg.Accent, Offset(x0, cy0), Size(x1 - x0, ch), CornerRadius(4 * dp), style = Stroke(2 * dp))
                }
            }
            // Track name chip (fixed at the left edge).
            // Outside sound mode the name sits on a chip at the left of the row (sound mode has a
            // header column instead). Muted tracks: the name greyed out and struck through.
            if (sound) return@forEachIndexed
            val label = measurer.measure(
                t.name.take(10),
                if (t.muted) labelStyle.copy(color = Pg.Text3, textDecoration = TextDecoration.LineThrough) else labelStyle,
            )
            val chipH = min(rowH - 8 * dp, 32 * dp)
            drawRoundRect(
                Pg.Surface1.copy(alpha = 0.88f), Offset(2 * dp, y0 + 4 * dp),
                Size(min(label.size.width + 10 * dp, 70 * dp), chipH), CornerRadius(6 * dp),
            )
            clipRect(2 * dp, y0, 70 * dp, y0 + rowH) {
                drawText(label, topLeft = Offset(7 * dp, y0 + 4 * dp + (chipH - label.size.height) / 2))
            }
        }
        // The selected section.
        vm.soundSel?.let { s ->
            val sx0 = msToX(s.fromMs)
            val sx1 = msToX(s.toMs)
            tracks.forEachIndexed { ti, t ->
                if (t.id !in s.tracks) return@forEachIndexed
                val y0 = top + ti * rowH - vScroll
                drawRect(Pg.AccentWeak, Offset(sx0, y0), Size(sx1 - sx0, rowH))
                for (ex in listOf(sx0, sx1)) {
                    drawLine(Pg.Accent, Offset(ex, y0), Offset(ex, y0 + rowH), 2 * dp)
                    drawCircle(Pg.Accent, 6 * dp, Offset(ex, y0 + rowH / 2))
                }
            }
        }
    }
    // Where the animation ends: beyond it, it repeats (loop to sound) or the sound is cut.
    val endX = animTicks * zoom - scroll
    if (totalTicks > animTicks && endX < size.width) {
        val shade = if (vm.loopToAudio) Color(0x0DFFFFFF) else Color(0x66000000)
        drawRect(shade, Offset(max(endX, 0f), top - FRAMES_DP * dp), Size(size.width - max(endX, 0f), FRAMES_DP * dp + rowsH))
        drawLine(colors.text.copy(alpha = 0.6f), Offset(endX, top - FRAMES_DP * dp), Offset(endX, top + rowsH), 1.5f * dp)
        val text = if (vm.loopToAudio) "animation repeats" else "sound after here is cut (turn on looping)"
        drawText(measurer.measure(text, Pg.Mark.copy(color = colors.text)), topLeft = Offset(max(endX, 0f) + 4 * dp, top - FRAMES_DP * dp + 2 * dp))
    }
    // Add-track row
    val addTop = top + rowsH
    drawRect(Color(0x10FFFFFF), Offset(0f, addTop), Size(size.width, ADD_ROW_DP * dp))
    drawText(
        measurer.measure(if (tracks.isEmpty()) "+  Add a sound track" else "+  Add track", labelStyle.copy(color = Pg.Text2)),
        topLeft = Offset(12 * dp, addTop + 9 * dp),
    )
    // Playhead through everything: where the sound is while playing, else the cursor / current frame.
    val timing = vm.timing()
    val headX: Float? = when {
        vm.isPlaying -> vm.playTick * zoom - scroll
        sound -> msToX(vm.soundCursorMs())
        timing != null && vm.frameIndex in 0 until timing.frameCount -> timing.startTick(vm.frameIndex) * zoom - scroll
        else -> null
    }
    // It stops at the last row (sound mode leaves empty space below the tracks).
    if (headX != null) drawLine(Pg.Live, Offset(headX, 0f), Offset(headX, min(size.height, addTop + ADD_ROW_DP * dp)), 2 * dp)
}

/** A fade drawn as a shaded triangle over the clip (the part that's quieter). */
private fun DrawScope.drawFadeShade(xa: Float, xb: Float, top: Float, h: Float, rising: Boolean, color: Color) {
    if (xb - xa < 1f) return
    val path = androidx.compose.ui.graphics.Path().apply {
        if (rising) {
            moveTo(xa, top)
            lineTo(xb, top)
            lineTo(xa, top + h)
        } else {
            moveTo(xa, top)
            lineTo(xb, top)
            lineTo(xb, top + h)
        }
        close()
    }
    drawPath(path, color)
    drawLine(
        Color.White.copy(alpha = 0.7f),
        if (rising) Offset(xa, top + h) else Offset(xa, top),
        if (rising) Offset(xb, top) else Offset(xb, top + h),
        1f * density,
    )
}

@Composable
internal fun PlayButton(vm: EditorViewModel) {
    RoundIconButton(
        if (vm.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
        if (vm.isPlaying) "Stop" else "Play",
        onClick = vm::togglePlay,
        enabled = vm.frameCount > 1 || vm.clips().isNotEmpty(),
    )
}

@Composable
private fun OnionToggle(vm: EditorViewModel) {
    PgIconButton(OnionSkinIcon, "Onion skin", vm::toggleOnionSkin, checked = vm.onionSkin)
}

private fun duplicateLabel(vm: EditorViewModel) = if (vm.duplicateToEnd) "Duplicate frame to end" else "Duplicate frame"

/**
 * The grab handle on top of the timeline: tap it, or drag up for the full timeline with sound
 * and down for a single row of frames.
 */
@Composable
private fun TimelineHandle(expanded: Boolean, onExpand: (Boolean) -> Unit) {
    val dp = LocalDensity.current.density
    val setExpanded by rememberUpdatedState(onExpand)
    var drag by remember { mutableFloatStateOf(0f) }
    val label = if (expanded) "Smaller timeline" else "Bigger timeline (with sound)"
    Box(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .semantics { contentDescription = label }
            .clickable(onClickLabel = label, role = Role.Button) { setExpanded(!expanded) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag < -24 * dp) setExpanded(true) else if (drag > 24 * dp) setExpanded(false)
                    },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Pg.Text3))
    }
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
        val deleteLabel = "Delete"
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
            item(deleteLabel, frames.size > 1) { vm.clearRange(); onDelete(index) },
        )
        items.forEach { entry ->
            if (entry == null) {
                HorizontalDivider()
            } else {
                val (text, rest) = entry
                val (enabled, action) = rest
                DropdownMenuItem(
                    text = { Text(text, color = if (text == deleteLabel && enabled) Pg.Danger else Color.Unspecified) },
                    enabled = enabled,
                    onClick = { onDismiss(); action() },
                )
            }
        }
    }
}

/** Actions for a selected run of frames: the common ones, then More. Delete offers Undo. */
@Composable
private fun RangeBar(vm: EditorViewModel, onHold: () -> Unit) {
    val sel = vm.hasRange
    val n = vm.rangeCount
    val hasClip = vm.frameClipInfo != null
    ActionBar(
        lead = when {
            !sel -> "Tap frames"
            n == 1 -> "Frame ${vm.rangeStart + 1}"
            else -> "$n frames"
        },
        onClose = vm::toggleRangeMode,
        closeLabel = "Done selecting frames",
        actions = listOf(
            BarAction("Copy", Icons.Filled.ContentCopy, enabled = sel) { vm.copyFrames() },
            BarAction("Cut", Icons.Filled.ContentCut, enabled = sel) { vm.copyFrames(cut = true) },
            BarAction(
                "Paste", Icons.Filled.ContentPaste, enabled = hasClip,
                choices = listOf(
                    Choice("Paste before selection") { vm.pasteFrames(FramePosition.BEFORE) },
                    Choice("Paste after selection") { vm.pasteFrames(FramePosition.AFTER) },
                    Choice("Paste at the end") { vm.pasteFrames(FramePosition.END) },
                ),
            ),
            BarAction("Delete", Icons.Filled.Delete, enabled = sel && n < vm.frameCount, destructive = true) { vm.deleteFrames() },
            BarAction(
                "Duplicate", Icons.Filled.LibraryAdd, enabled = sel,
                choices = listOf(
                    Choice("Duplicate after selection") { vm.duplicateFrames(toEnd = false) },
                    Choice("Duplicate at the end") { vm.duplicateFrames(toEnd = true) },
                ),
            ),
            BarAction("Reverse", Icons.Filled.SwapHoriz, enabled = sel && n > 1) { vm.reverseFrames() },
            BarAction("Hold…", Icons.Filled.Timer, enabled = sel) { onHold() },
            BarAction("Move earlier", Icons.AutoMirrored.Filled.ArrowBack, enabled = sel && vm.rangeStart > 0) { vm.moveFrames(-1) },
            BarAction("Move later", Icons.AutoMirrored.Filled.ArrowForward, enabled = sel && vm.rangeEnd < vm.frameCount - 1) { vm.moveFrames(1) },
        ),
    )
}

/** 0–200% volume. */
@Composable
fun VolumeDialog(title: String, initial: Float, onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            SliderRow(
                "Volume", "${(v * 100).roundToInt()}%", v,
                onValueChange = { v = (it * 20).roundToInt() / 20f }, valueRange = 0f..2f, step = 0.05f,
            )
        },
        confirmButton = { PrimaryButton("OK", onClick = { onConfirm(v) }) },
        dismissButton = { PgTextButton("Cancel", onClick = onDismiss) },
    )
}

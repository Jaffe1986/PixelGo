package com.pixelgo.ui.editor

import android.graphics.Bitmap
import android.util.LruCache
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelgo.PixelGoApp
import com.pixelgo.data.DiskCelStore
import com.pixelgo.data.ExportImage
import com.pixelgo.data.GifEncoder
import com.pixelgo.data.MetaSerializer
import com.pixelgo.data.PngExporter
import com.pixelgo.data.SaveSnapshot
import androidx.compose.runtime.mutableLongStateOf
import com.pixelgo.data.audio.AudioDecoder
import com.pixelgo.data.audio.AudioRecorder
import com.pixelgo.data.audio.AudioSources
import com.pixelgo.data.audio.PcmFile
import com.pixelgo.data.audio.Peaks
import com.pixelgo.editor.AudioMixer
import com.pixelgo.editor.CountIn
import com.pixelgo.editor.Names
import com.pixelgo.model.AudioAsset
import com.pixelgo.model.FRAMES_PER_MS
import com.pixelgo.data.video.VideoExporter
import com.pixelgo.data.video.VideoOutput
import com.pixelgo.data.video.VideoSettings
import com.pixelgo.editor.AnimationSnapshot
import com.pixelgo.editor.BulkJob
import com.pixelgo.export.ExportJobs
import com.pixelgo.editor.CelKey
import com.pixelgo.editor.CelStore
import com.pixelgo.editor.ColorMath
import com.pixelgo.editor.DirtyCel
import com.pixelgo.editor.EditorEngine
import com.pixelgo.editor.FramePosition
import com.pixelgo.editor.SelectionMath
import com.pixelgo.editor.Timing
import com.pixelgo.model.Frame
import com.pixelgo.model.MAX_FPS
import com.pixelgo.model.MIN_FPS
import com.pixelgo.model.PixelProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class Tool { Pencil, Eraser, Fill, Eyedropper, Select }

enum class SelectMode { Rect, Paint }

enum class LoadState { Loading, Ready, Failed }

enum class RecordState { Idle, CountIn, Recording }

/** Zoom / pan state. [scale] is screen pixels per art pixel; [offset] is the canvas top-left on screen. */
class Viewport {
    var scale by mutableFloatStateOf(0f)
    var offset by mutableStateOf(Offset.Zero)
    private var fitScale = 1f
    private var fittedFor: Pair<Int, Int>? = null

    fun ensureFit(viewW: Int, viewH: Int, artW: Int, artH: Int) {
        if (fittedFor == viewW to viewH) return
        fittedFor = viewW to viewH
        fit(viewW, viewH, artW, artH)
    }

    fun fit(viewW: Int, viewH: Int, artW: Int, artH: Int) {
        fitScale = min(viewW.toFloat() / artW, viewH.toFloat() / artH) * 0.92f
        scale = fitScale
        offset = Offset((viewW - artW * scale) / 2f, (viewH - artH * scale) / 2f)
    }

    fun refit(artW: Int, artH: Int) {
        val (w, h) = fittedFor ?: return
        fit(w, h, artW, artH)
    }

    /** Zoom by [zoom] around [centroid], then pan by [pan]. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val minScale = fitScale * 0.5f
        val maxScale = max(fitScale * 64f, 120f)
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        val ratio = newScale / scale
        offset = centroid - (centroid - offset) * ratio + pan
        scale = newScale
    }
}

/** Frames to export: [count] frames, [render] returns frame i flattened (call off the main thread). */
class FrameSource(val count: Int, val render: (Int) -> IntArray)

class EditorViewModel(private val app: PixelGoApp, private val projectId: String) : ViewModel() {

    var loadState by mutableStateOf(LoadState.Loading)
        private set

    /** Set once the project has loaded. */
    var engine: EditorEngine? = null
        private set
    private var store: DiskCelStore? = null
    private val project: PixelProject? get() = engine?.project

    var width = 1
        private set
    var height = 1
        private set

    private var bitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    var image: ImageBitmap = bitmap.asImageBitmap()
        private set

    /** Bumped whenever [image] changes, so the canvas redraws. */
    var renderTick by mutableIntStateOf(0)
        private set

    /** Bumped whenever layers, frames or project info change, so lists refresh. */
    var structureTick by mutableIntStateOf(0)
        private set

    val viewport = Viewport()

    private var toolState by mutableStateOf(Tool.Pencil)

    /** Current tool. Leaving the Select tool drops any floating cells in place. */
    var tool: Tool
        get() = toolState
        set(value) {
            if (value != Tool.Select) commitFloating()
            toolState = value
        }

    var selectMode by mutableStateOf(SelectMode.Rect)
    var hasSelection by mutableStateOf(false)
        private set
    var isFloating by mutableStateOf(false)
        private set

    /** Outline of the selection in cell-edge coordinates, groups of (x0,y0,x1,y1). */
    var selectionEdges = IntArray(0)
        private set

    /** Image + outline of the floating cells, drawn at engine.floating's position. */
    var floatingImage: ImageBitmap? = null
        private set
    var floatingEdges = IntArray(0)
        private set

    var color by mutableIntStateOf(0xFF000000.toInt())
    var mirrorX by mutableStateOf(false)
        private set
    var mirrorY by mutableStateOf(false)
        private set
    var showGrid by mutableStateOf(true)
    var guideVertical by mutableStateOf(false)
    var guideHorizontal by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var projectName by mutableStateOf("")
        private set

    /** Non-null while a long job runs (editing is blocked); shown in an overlay. */
    var busyLabel by mutableStateOf<String?>(null)
        private set
    var busyProgress by mutableFloatStateOf(0f)
        private set

    // ---- Animation state ---------------------------------------------------------------
    var frameIndex by mutableIntStateOf(0)
        private set
    var frameCount by mutableIntStateOf(1)
        private set
    var fps by mutableIntStateOf(8)
        private set
    var onionSkin by mutableStateOf(false)
        private set

    /** Faint previous / next frame images for onion skinning (null when off or not present). */
    var onionPrev: ImageBitmap? = null
        private set
    var onionNext: ImageBitmap? = null
        private set

    var isPlaying by mutableStateOf(false)
        private set
    var playIndex by mutableIntStateOf(0)
        private set

    /**
     * Where playback is on the timeline, in ticks from the start. Unlike [playIndex], this keeps
     * going while the animation repeats under a longer sound.
     */
    var playTick by mutableLongStateOf(0L)
        private set

    /** The frame image shown while playing. */
    var playImage: ImageBitmap? = null
        private set
    private var playJob: Job? = null
    private var ignoreGesture = false

    /** Small frame thumbnails for the timeline, keyed by frame uid + content versions. */
    private val thumbCache = LruCache<String, ImageBitmap>(400)

    private var unsaved = false
    private var pendingX = 0
    private var pendingY = 0

    init {
        viewModelScope.launch {
            val opened = withContext(app.projects.dispatcher) { app.projects.open(projectId) }
            if (opened == null) {
                loadState = LoadState.Failed
                return@launch
            }
            val p = opened.project
            store = opened.store
            width = p.width
            height = p.height
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            image = bitmap.asImageBitmap()
            engine = EditorEngine(p, opened.store)
            projectName = p.name
            fps = p.fps
            syncBitmap()
            syncFrames()
            loadState = LoadState.Ready
        }
    }

    /** True while this project's video export runs (editing is locked until it ends). */
    val isExporting: Boolean get() = ExportJobs.isExporting(projectId)

    val isBusy: Boolean get() = busyLabel != null || isExporting

    /** Encoder settings preview for the export dialog (size, duration, bitrate). */
    fun videoPreview(settings: VideoSettings): VideoExporter? {
        val e = engine ?: return null
        val st = store ?: return null
        val p = e.project
        // A mixer without sources is enough to know whether there is any sound.
        val mixer = AudioMixer(p.tracks, p.clips) { null }
        return VideoExporter(AnimationSnapshot.of(p, st), settings, mixer, p.totalMs())
    }

    /**
     * Exports the animation as an MP4/MOV video in the background (see ExportJobs). Editing is
     * locked while it runs; it keeps going if the user leaves the app.
     */
    fun startVideoExport(settings: VideoSettings) {
        val e = engine ?: return
        val st = store ?: return
        if (isBusy || ExportJobs.isRunning) return
        stopPlaying()
        e.cancelStroke()
        commitFloating()
        unsaved = true
        save()
        val p = e.project
        val snap = AnimationSnapshot.of(p, st)
        val tracks = p.tracks.toList()
        val clips = p.clips.toList()
        val totalMs = p.totalMs()
        val audioDir = app.projects.audioDir(projectId)
        val name = projectName
        val ctx: PixelGoApp = app
        viewModelScope.launch {
            withContext(app.projects.dispatcher) {} // let the save finish first
            ExportJobs.start(
                ctx, projectId,
                if (settings.mov) "Exporting MOV video…" else "Exporting MP4 video…",
                if (settings.mov) "video/quicktime" else "video/mp4",
            ) { progress, cancelled ->
                val target = VideoOutput.create(ctx, name, settings.mov)
                try {
                    AudioSources(audioDir).use { sources ->
                        val mixer = AudioMixer(tracks, clips) { sources.get(it) }
                        VideoExporter(snap, settings, mixer, totalMs).run(target.pfd.fileDescriptor, progress, cancelled)
                    }
                    if (settings.mov) VideoOutput.patchToMov(target)
                    VideoOutput.finish(ctx, target)
                    target.displayPath to target.uri
                } catch (t: Throwable) {
                    VideoOutput.discard(ctx, target)
                    throw t
                }
            }
        }
    }

    // ---- Frames --------------------------------------------------------------------------

    fun frames(): List<Frame> = project?.frames.orEmpty()

    fun selectFrame(i: Int) {
        val e = engine ?: return
        if (isBusy || i == e.project.currentFrameIndex) return
        stopPlaying()
        e.cancelStroke()
        commitFloating()
        e.selectFrame(i)
        syncBitmap()
        syncFrames()
        updateOnion()
        structureTick++
    }

    fun addFrame(position: FramePosition = FramePosition.AFTER) = frameOp { it.addFrame(position) }

    /** Duplicates the current frame; by default it goes where the user's setting says. */
    fun duplicateFrame(toEnd: Boolean = app.settings.duplicateToEnd) = frameOp { it.duplicateFrame(toEnd) }

    fun deleteFrame() = frameOp { it.deleteFrame() }
    fun moveFrame(from: Int, to: Int) = frameOp { it.moveFrame(from, to) }
    fun setFrameHold(i: Int, hold: Int) = frameOp { it.setFrameHold(i, hold) }

    val duplicateToEnd: Boolean get() = app.settings.duplicateToEnd
    fun updateDuplicateToEnd(value: Boolean) = app.settings.updateDuplicateToEnd(value)

    fun timing(): Timing? = project?.timing

    // ---- Frame ranges ------------------------------------------------------------------

    /** Range mode: taps on the timeline pick a run of frames instead of one frame. */
    var rangeMode by mutableStateOf(false)
        private set
    var rangeStart by mutableIntStateOf(-1)
        private set
    var rangeEnd by mutableIntStateOf(-1)
        private set
    private var rangeAnchor = -1

    val hasRange: Boolean get() = rangeStart >= 0
    val rangeCount: Int get() = if (hasRange) rangeEnd - rangeStart + 1 else 0

    /** Frames copied (in any project) and ready to paste. */
    val frameClipInfo get() = app.frameClipInfo

    fun toggleRangeMode() {
        rangeMode = !rangeMode
        if (rangeMode) startRangeAt(frameIndex) else clearRange()
    }

    /** Enters range mode with a one-frame range at [i]. */
    fun startRangeAt(i: Int) {
        rangeMode = true
        rangeAnchor = i
        setRange(i, i)
    }

    /** In range mode, a tap extends the range from the first tapped frame to this one. */
    fun tapFrameInRange(i: Int) {
        if (rangeAnchor < 0) startRangeAt(i) else setRange(min(rangeAnchor, i), max(rangeAnchor, i))
        selectFrame(i)
    }

    fun clearRange() {
        rangeStart = -1
        rangeEnd = -1
        rangeAnchor = -1
    }

    private fun setRange(a: Int, b: Int) {
        rangeStart = a
        rangeEnd = b
    }

    /** (first index, count) of the selected range, or of the current frame. */
    private fun target(): Pair<Int, Int> = if (hasRange) rangeStart to rangeCount else frameIndex to 1

    /** Copies (or cuts) the range / current frame to the frame clipboard. */
    fun copyFrames(cut: Boolean = false) {
        val e = engine ?: return
        if (isBusy) return
        stopPlaying()
        e.cancelStroke()
        commitFloating()
        val (from, count) = target()
        val src = e.frameRange(from, count) ?: return
        val clipboard = app.frameClipboard
        runBulk(
            BulkJob(
                if (cut) "Cutting frames…" else "Copying frames…",
                work = { progress -> clipboard.write(src, progress) },
                done = {
                    app.frameClipInfo = clipboard.info()
                    if (cut) {
                        if (e.deleteFrames(from, count)) clearRange() else toast("An animation needs at least one frame")
                    }
                    toast("${if (cut) "Cut" else "Copied"} $count frame${if (count == 1) "" else "s"}")
                },
            ),
            inline = count * layerCount <= 32,
        )
    }

    /** Pastes the frame clipboard before/after the range (or current frame), or at the end. */
    fun pasteFrames(position: FramePosition) {
        val e = engine ?: return
        if (isBusy) return
        val clip = app.frameClipboard.open() ?: run {
            app.frameClipInfo = null
            return
        }
        stopPlaying()
        e.cancelStroke()
        commitFloating()
        val (from, count) = target()
        val index = when (position) {
            FramePosition.BEFORE -> from
            FramePosition.AFTER -> from + count
            FramePosition.END -> frameCount
        }
        clearRange()
        runBulk(e.pasteFramesJob(clip, index), inline = clip.holds.size * clip.layerNames.size <= 32)
    }

    fun duplicateFrames(toEnd: Boolean) {
        val e = engine ?: return
        if (isBusy) return
        stopPlaying()
        commitFloating()
        val (from, count) = target()
        runBulk(e.duplicateFramesJob(from, count, toEnd), inline = count * layerCount <= 32)
    }

    fun reverseFrames() {
        val (from, count) = target()
        frameOp { it.reverseFrames(from, count) }
    }

    fun deleteFrames() {
        val (from, count) = target()
        frameOp {
            if (!it.deleteFrames(from, count)) toast("An animation needs at least one frame")
        }
        clearRange()
    }

    /** Moves the range (or current frame) left/right by [delta] frames. */
    fun moveFrames(delta: Int) {
        val (from, count) = target()
        var newStart = from
        frameOp { newStart = it.moveFrames(from, count, delta) }
        if (hasRange) setRange(newStart, newStart + count - 1)
        if (rangeAnchor >= 0) rangeAnchor += newStart - from
    }

    fun setHolds(hold: Int) {
        val (from, count) = target()
        frameOp { it.setHolds(from, count, hold) }
    }

    private inline fun frameOp(op: (EditorEngine) -> Unit) {
        val e = engine ?: return
        if (isBusy) return
        stopPlaying()
        e.cancelStroke()
        commitFloating()
        op(e)
        changed(structure = true)
    }

    fun changeFps(value: Int) {
        val p = project ?: return
        p.fps = value.coerceIn(MIN_FPS, MAX_FPS)
        fps = p.fps
        changed()
        if (isPlaying) {
            stopPlaying()
            startPlaying()
        }
    }

    fun toggleOnionSkin() {
        onionSkin = !onionSkin
        updateOnion()
    }

    private fun syncFrames() {
        val p = project ?: return
        frameIndex = p.currentFrameIndex
        frameCount = p.frames.size
        if (hasRange && rangeEnd >= frameCount) clearRange()
    }

    private fun updateOnion() {
        val p = project
        val st = store
        if (p == null || st == null || !onionSkin || p.frames.size < 2) {
            onionPrev = null
            onionNext = null
        } else {
            val f = p.currentFrameIndex
            onionPrev = if (f > 0) bitmapOf(ColorMath.compositeFrame(p, st, p.frames[f - 1].uid)) else null
            onionNext = if (f < p.frames.lastIndex) bitmapOf(ColorMath.compositeFrame(p, st, p.frames[f + 1].uid)) else null
        }
        renderTick++
    }

    /**
     * Small thumbnail of a frame for the timeline (rendered off the main thread, cached until
     * that frame's pixels or the layer setup change).
     */
    suspend fun frameThumb(frameUid: Long): ImageBitmap? {
        val p = project ?: return null
        val st = store ?: return null
        val key = "$frameUid:${st.frameVersion(frameUid)}:${p.layersVersion}"
        thumbCache.get(key)?.let { return it }
        val layerIds = p.visibleLayerIds()
        val w = width
        val h = height
        val img = withContext(Dispatchers.Default) {
            thumbBitmap(ColorMath.compositeFrame(layerIds, st, frameUid), w, h)
        }
        thumbCache.put(key, img)
        return img
    }

    /** Current-frame pixels of one layer (for the layers list). */
    fun layerThumb(layerId: Long): ImageBitmap? {
        val p = project ?: return null
        val px = store?.read(layerId, p.currentFrameUid) ?: return null
        return bitmapOf(px)
    }

    private fun bitmapOf(px: IntArray): ImageBitmap =
        Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

    fun togglePlay() {
        if (isPlaying) stopPlaying() else startPlaying()
    }

    private var player: PreviewPlayer? = null
    private var playSources: AudioSources? = null

    /**
     * Plays the animation with its sound. The frame shown follows what is being heard.
     * [fromMs] starts at that time without looping (voice-over recording); otherwise playback
     * starts at the current frame and loops over the whole piece.
     */
    /**
     * Plays from the current frame (looping), or once from [fromMs] (voice-over). [preRoll] is the
     * recording count-in, played before the start.
     */
    private fun startPlaying(fromMs: Long? = null, preRoll: ShortArray? = null) {
        val e = engine ?: return
        val st = store ?: return
        val p = e.project
        val hasSound = p.audioEndMs() > 0
        if (isBusy || (p.frames.size < 2 && !hasSound && fromMs == null)) return
        e.cancelStroke()
        commitFloating()
        // Snapshot what playback needs, so it never touches the live frame list.
        val uids = p.frameUids()
        val timing = p.timing
        val fpsNow = p.fps
        val layerIds = p.visibleLayerIds()
        val animTicks = timing.totalTicks
        val totalMs = p.totalMs()
        val startMs = fromMs ?: (timing.startTick(p.currentFrameIndex) * 1000L / fpsNow)
        val sources = AudioSources(app.projects.audioDir(projectId))
        val mixer = AudioMixer(p.tracks, p.clips) { sources.get(it) }
        val pl = try {
            PreviewPlayer(mixer, startMs * FRAMES_PER_MS, totalMs * FRAMES_PER_MS, loop = fromMs == null, preRoll = preRoll)
                .also { it.start() }
        } catch (t: Exception) {
            sources.close()
            toast("Can't play sound: ${t.message}")
            return
        }
        player = pl
        playSources = sources
        val clock: PlaybackClock = AudioPlaybackClock(pl, fpsNow)
        playTick = startMs * fpsNow / 1000
        isPlaying = true
        playJob = viewModelScope.launch {
            suspend fun render(i: Int): ImageBitmap = withContext(Dispatchers.Default) {
                bitmapOf(ColorMath.compositeFrame(layerIds, st, uids[i]))
            }
            // Frames rendered ahead of time (all access happens on the main thread).
            val ahead = HashMap<Int, ImageBitmap>()
            var shown = -1
            while (isActive) {
                // The playhead follows the sound; the animation repeats under longer sound
                // ("loop until the sound ends").
                val now = clock.nowTicks()
                playTick = now
                val tick = now % animTicks
                val fi = timing.frameAtTick(tick)
                if (fi != shown) {
                    val img = ahead.remove(fi) ?: render(fi)
                    playImage = img
                    playIndex = fi
                    shown = fi
                    renderTick++
                    // Prepare the next two frames while this one is on screen.
                    val next = listOf((fi + 1) % uids.size, (fi + 2) % uids.size)
                    ahead.keys.retainAll(next.toSet())
                    for (n in next) {
                        if (n !in ahead && n != fi) launch { ahead[n] = render(n) }
                    }
                }
                val ticksLeft = timing.endTick(fi) - tick
                delay((ticksLeft * 1000L / fpsNow).coerceIn(1L, 30L))
            }
        }
    }

    fun stopPlaying() {
        playJob?.cancel()
        playJob = null
        player?.stop()
        player = null
        playSources?.close()
        playSources = null
        if (isPlaying) {
            isPlaying = false
            playImage = null
            renderTick++
        }
    }

    /** All frames flattened (visible layers), rendered on demand (call render off the main thread). */
    fun exportFrameSource(): FrameSource {
        val p = project ?: return FrameSource(0) { IntArray(0) }
        val st = store ?: return FrameSource(0) { IntArray(0) }
        val uids = p.frameUids()
        val layerIds = p.visibleLayerIds()
        return FrameSource(uids.size) { i -> ColorMath.compositeFrame(layerIds, st, uids[i]) }
    }

    /** GIF delay per frame in 1/100 s (rounding error carried forward). */
    fun frameDelaysCs(): IntArray {
        val p = project ?: return IntArray(0)
        return GifEncoder.delaysCs(IntArray(p.frames.size) { p.frames[it].hold }, p.fps)
    }

    fun toggleMirrorX() {
        mirrorX = !mirrorX
        engine?.mirrorX = mirrorX
    }

    fun toggleMirrorY() {
        mirrorY = !mirrorY
        engine?.mirrorY = mirrorY
    }

    // ---- Touch input (art pixel coordinates) -------------------------------------------

    // Select-tool gesture state
    private var selSnapshot: BooleanArray? = null
    private var rectStartX = 0
    private var rectStartY = 0
    private var grabDX = 0
    private var grabDY = 0
    private var floatStartX = 0
    private var floatStartY = 0

    fun onPress(x: Int, y: Int) {
        val e = engine ?: return
        // No drawing while recording (it would also stop a voice-over's playback).
        if (recordState != RecordState.Idle) {
            ignoreGesture = true
            return
        }
        // Touching the canvas while playing just stops playback; nothing happens while busy.
        ignoreGesture = isPlaying || isBusy
        if (isPlaying) {
            stopPlaying()
            return
        }
        if (ignoreGesture) return
        pendingX = x
        pendingY = y
        when (tool) {
            Tool.Pencil -> e.beginStroke(x, y, color)
            Tool.Eraser -> e.beginStroke(x, y, 0)
            Tool.Fill, Tool.Eyedropper -> Unit
            Tool.Select -> {
                val f = e.floating
                if (f != null) {
                    // Drag the floating cells from wherever the finger lands.
                    grabDX = x - f.x
                    grabDY = y - f.y
                    floatStartX = f.x
                    floatStartY = f.y
                } else {
                    selSnapshot = e.selection?.copyOf()
                    if (selectMode == SelectMode.Rect) {
                        rectStartX = x
                        rectStartY = y
                        e.selectRect(x, y, x, y)
                    } else {
                        e.beginSelectPaint(x, y)
                    }
                    selectionChanged()
                }
            }
        }
        syncBitmap()
    }

    fun onMove(x: Int, y: Int) {
        val e = engine ?: return
        if (ignoreGesture) return
        pendingX = x
        pendingY = y
        if (tool == Tool.Select) {
            if (e.floating != null) {
                e.moveFloatingTo(x - grabDX, y - grabDY)
                renderTick++
            } else {
                if (selectMode == SelectMode.Rect) e.selectRect(rectStartX, rectStartY, x, y)
                else e.selectPaintTo(x, y)
                selectionChanged()
            }
            return
        }
        if (e.isStroking) {
            e.strokeTo(x, y)
            syncBitmap()
        }
    }

    fun onRelease() {
        val e = engine ?: return
        if (ignoreGesture) {
            ignoreGesture = false
            return
        }
        val changed = when (tool) {
            Tool.Pencil, Tool.Eraser -> e.endStroke()
            Tool.Fill -> e.fill(pendingX, pendingY, color)
            Tool.Eyedropper -> {
                e.pick(pendingX, pendingY)?.let {
                    color = it
                    app.palettes.addRecent(it)
                    tool = Tool.Pencil
                }
                false
            }
            Tool.Select -> false
        }
        if (changed) changed()
    }

    /** A second finger touched down: undo whatever the first finger started. */
    fun onCancel() {
        val e = engine ?: return
        if (ignoreGesture) {
            ignoreGesture = false
            return
        }
        if (tool == Tool.Select) {
            if (e.floating != null) {
                e.moveFloatingTo(floatStartX, floatStartY)
                renderTick++
            } else {
                e.restoreSelection(selSnapshot)
                selectionChanged()
            }
            return
        }
        e.cancelStroke()
        syncBitmap()
    }

    // ---- Selection commands ------------------------------------------------------------

    fun selectAll() {
        engine?.selectAll()
        selectionChanged()
    }

    fun deselect() {
        engine?.clearSelection()
        selectionChanged()
    }

    fun copySelection() {
        val clip = engine?.copySelection() ?: return
        PixelClipboard.clip = clip
        toast("Copied ${clip.cellCount} cells")
    }

    fun cutSelection() {
        val clip = engine?.cutSelection() ?: return
        PixelClipboard.clip = clip
        changed()
        toast("Cut ${clip.cellCount} cells")
    }

    fun deleteSelection() {
        if (engine?.deleteSelection() == true) changed()
    }

    fun moveSelection() {
        val e = engine ?: return
        if (e.beginMove()) startFloating()
    }

    fun paste() {
        val e = engine ?: return
        val clip = PixelClipboard.clip ?: return
        commitFloating()
        if (e.beginPaste(clip)) {
            toolState = Tool.Select
            startFloating()
        }
    }

    /** Drops floating cells where they are (✓). */
    fun commitFloating() {
        val e = engine ?: return
        if (e.floating == null) return
        val changedPixels = e.commitFloating()
        endFloating()
        if (changedPixels) changed() else syncBitmap()
    }

    /** Puts floating cells back where they came from (✕). */
    fun cancelFloating() {
        val e = engine ?: return
        if (e.floating == null) return
        e.cancelFloating()
        endFloating()
        syncBitmap()
    }

    private fun startFloating() {
        val f = engine?.floating ?: return
        val c = f.clip
        val px = IntArray(c.pixels.size) { if (c.mask[it]) c.pixels[it] else 0 }
        floatingImage = Bitmap.createBitmap(px, c.width, c.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        floatingEdges = SelectionMath.edges(c.mask, c.width, c.height)
        isFloating = true
        selectionChanged()
        syncBitmap()
    }

    private fun endFloating() {
        floatingImage = null
        floatingEdges = IntArray(0)
        isFloating = false
        selectionChanged()
    }

    private fun selectionChanged() {
        val e = engine ?: return
        selectionEdges = e.selection?.let { SelectionMath.edges(it, width, height) } ?: IntArray(0)
        hasSelection = e.hasSelection
        renderTick++
    }

    private fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()

    // ---- Commands ------------------------------------------------------------------------

    fun undo() {
        if (isBusy) return
        stopPlaying()
        // While cells are floating, undo means "put them back".
        if (engine?.floating != null) {
            cancelFloating()
            return
        }
        if (engine?.undo() == true) changed(structure = true)
    }

    fun redo() {
        if (isBusy) return
        stopPlaying()
        if (engine?.floating != null) return
        if (engine?.redo() == true) changed(structure = true)
    }

    fun selectLayer(i: Int) {
        if (isBusy) return
        commitFloating()
        engine?.selectLayer(i)
        structureTick++
    }

    fun addLayer() = layerOp { it.addLayer() }
    fun duplicateLayer() = runBulk(engine?.let { commitFloating(); it.duplicateLayerJob() })
    fun deleteLayer() = layerOp { it.deleteLayer() }
    fun mergeDown() = runBulk(engine?.let { commitFloating(); it.mergeDownJob() })
    fun moveLayer(from: Int, to: Int) = layerOp { it.moveLayer(from, to) }
    fun setLayerVisible(i: Int, v: Boolean) = layerOp { it.setLayerVisible(i, v) }
    fun renameLayer(i: Int, name: String) = layerOp { it.renameLayer(i, name) }

    val canAddLayer: Boolean get() = engine?.canAddLayer == true

    private inline fun layerOp(op: (EditorEngine) -> Unit) {
        val e = engine ?: return
        if (isBusy) return
        stopPlaying()
        commitFloating()
        op(e)
        changed(structure = true)
    }

    /**
     * Runs a whole-animation job (merge, duplicate layer…) off the main thread with a progress
     * overlay. Editing and saving pause until it finishes.
     */
    private fun runBulk(job: BulkJob?, inline: Boolean = false) {
        if (job == null || isBusy) return
        stopPlaying()
        if (inline) {
            // Small enough to do right away without an overlay.
            try {
                job.runWork {}
                job.finish()
                changed(structure = true)
            } catch (e: Exception) {
                toast("Something went wrong: ${e.message}")
            }
            return
        }
        busyLabel = job.label
        busyProgress = 0f
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { job.runWork { busyProgress = it } }
                job.finish()
                busyLabel = null
                changed(structure = true)
            } catch (e: Exception) {
                busyLabel = null
                toast("Something went wrong: ${e.message}")
            }
        }
    }

    fun renameProject(name: String) {
        project?.name = name
        projectName = name
        unsaved = true
        save()
    }

    fun pickColor(c: Int) {
        color = c
        app.palettes.addRecent(c)
        if (tool == Tool.Eraser || tool == Tool.Eyedropper) tool = Tool.Pencil
    }

    /** Flattened visible layers of the current frame, for export. */
    fun exportPixels(): IntArray = engine?.composite?.copyOf() ?: IntArray(width * height)

    val layerCount: Int get() = project?.layers?.size ?: 0
    val hiddenLayerCount: Int get() = project?.layers?.count { !it.visible } ?: 0

    /** Each layer of the current frame (bottom to top) with export file names. */
    fun exportLayers(scale: Int, includeHidden: Boolean): List<ExportImage> {
        val p = project ?: return emptyList()
        val st = store ?: return emptyList()
        val layers = p.layers.filter { includeHidden || it.visible }
        val names = PngExporter.layerFileNames(layers.map { it.name }, scale)
        return layers.mapIndexed { i, l ->
            ExportImage(names[i], st.read(l.id, p.currentFrameUid)?.copyOf() ?: IntArray(p.size))
        }
    }

    private var autosaveJob: Job? = null

    private fun changed(structure: Boolean = false) {
        unsaved = true
        scheduleAutosave()
        canUndo = engine?.history?.canUndo == true
        canRedo = engine?.history?.canRedo == true
        syncBitmap()
        syncFrames()
        if (structure) {
            updateOnion()
            structureTick++
        }
    }

    /** Autosave a couple of seconds after the last edit, so a crash or forced close loses little. */
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(2000)
            if (engine?.isStroking == true || isBusy) scheduleAutosave() else save()
        }
    }

    private fun syncBitmap() {
        val e = engine ?: return
        var any = false
        e.consumeDirty(
            onFull = {
                bitmap.setPixels(it, 0, width, 0, 0, width, height)
                any = true
            },
            onPixel = { i, c ->
                bitmap.setPixel(i % width, i / width, c)
                any = true
            },
        )
        if (any) renderTick++
    }

    /**
     * Captures everything that changed (on this thread, where edits happen) and hands it to
     * the repository's IO thread. Only changed cels are written.
     */
    fun save() {
        val e = engine ?: return
        val st = store ?: return
        if (!unsaved) return
        if (isBusy) {
            scheduleAutosave()
            return
        }
        unsaved = false
        val p = e.project
        val dirty = st.snapshotDirty().toMutableList()
        // If cells are mid-move, save them where they came from (as if the move was cancelled)
        // without disturbing what's on screen.
        val f = e.floating
        val lifted = f?.lifted
        if (f != null && lifted != null) {
            val key = CelKey(f.layerId, f.frameUid)
            val at = dirty.indexOfFirst { it.key == key }
            if (at >= 0) {
                val base = dirty[at].pixels?.copyOf() ?: IntArray(p.size)
                lifted.forEach { (i, v) -> base[i] = v }
                dirty[at] = DirtyCel(key, if (CelStore.isEmpty(base)) null else base, dirty[at].version)
            }
        }
        val keepLayers = HashSet<Long>()
        val keepFrames = HashSet<Long>()
        e.collectRefs(keepLayers, keepFrames)
        // Sounds: keep what clips (or undo history) use; forget the rest.
        val keepAssets = HashSet<Long>()
        e.collectAssetRefs(keepAssets)
        if (recordingAssetId >= 0) keepAssets += recordingAssetId // file still being written
        p.assets.removeAll { it.id !in keepAssets && it.id != recordingAssetId }
        app.projects.save(
            SaveSnapshot(
                projectId = projectId,
                meta = MetaSerializer.toBytes(p, System.currentTimeMillis()),
                store = st,
                dirty = dirty,
                keepLayers = keepLayers,
                keepFrames = keepFrames,
                layerBound = p.nextLayerId,
                frameBound = p.nextFrameUid,
                thumbLayerIds = p.visibleLayerIds(),
                thumbFrameUid = p.frames.first().uid,
                width = p.width,
                height = p.height,
                keepAssets = keepAssets,
                assetBound = p.nextAssetId,
            )
        )
    }

    override fun onCleared() {
        stopRecording(keep = true)
        stopPlaying()
        engine?.cancelStroke()
        save()
    }

    /** The app went to the background: stop sound and recording (the mic stops working anyway). */
    fun onBackgrounded() {
        if (recordState != RecordState.Idle) stopRecording(keep = true)
        stopPlaying()
        save()
    }

    // ---- Audio ---------------------------------------------------------------------------

    /** Track that imports / recordings go to (null = make a new one). */
    var selectedTrackId by mutableStateOf<Long?>(null)

    /** Clip whose options sheet is open. */
    var selectedClipId by mutableStateOf<Long?>(null)

    /** Waveform peaks per asset (loaded lazily). */
    private val peaksCache = androidx.compose.runtime.mutableStateMapOf<Long, ByteArray>()
    private val peaksLoading = HashSet<Long>()

    var recordState by mutableStateOf(RecordState.Idle)
        private set
    var countIn by mutableIntStateOf(0)
        private set
    var recordLevel by mutableFloatStateOf(0f)
        private set
    var recordElapsedMs by mutableLongStateOf(0L)
        private set

    private var recorder: AudioRecorder? = null
    private var recordJob: Job? = null
    private var recordingAssetId = -1L
    private var recordTrack: Long? = null
    private var recordStartMs = 0L

    /** What the recording is lined up with: the voice-over playback, or the count-in on its own. */
    private var recordRef: PreviewPlayer? = null
    private var countInPlayer: PreviewPlayer? = null

    /**
     * The track a new recording should go on: the selected track, unless it already has sound
     * from the current frame on (then a new track, so the recording doesn't land on top of it).
     */
    fun defaultRecordTrack(): Long? {
        val t = selectedTrackId ?: return null
        val from = currentFrameMs()
        return if (clips().any { it.trackId == t && it.endMs > from }) null else t
    }

    fun tracks() = project?.tracks.orEmpty()
    fun clips() = project?.clips.orEmpty()
    fun assetName(id: Long) = project?.asset(id)?.name ?: "Sound"
    fun assetDurationMs(id: Long) = project?.asset(id)?.durationMs ?: 0L
    val loopToAudio: Boolean get() = project?.loopToAudio == true
    fun totalMs(): Long = project?.totalMs() ?: 0L
    fun animationMs(): Long = project?.animationMs() ?: 0L

    /** Timeline time (ms) where the current frame starts. */
    fun currentFrameMs(): Long {
        val p = project ?: return 0
        return p.timing.startTick(p.currentFrameIndex) * 1000L / p.fps
    }

    private fun audioDir() = app.projects.audioDir(projectId)
    private fun pcmFile(assetId: Long) = java.io.File(audioDir(), "$assetId.pcm")
    private fun peaksFile(assetId: Long) = java.io.File(audioDir(), "$assetId.peaks")

    /** Waveform peaks of an asset, or null while they load. */
    fun peaks(assetId: Long): ByteArray? {
        peaksCache[assetId]?.let { return it }
        if (peaksLoading.add(assetId)) {
            viewModelScope.launch {
                val p = withContext(Dispatchers.IO) { Peaks.read(peaksFile(assetId)) }
                peaksCache[assetId] = p
                peaksLoading.remove(assetId)
            }
        }
        return null
    }

    private fun freeSpaceMb(): Long = android.os.StatFs(app.filesDir.path).availableBytes / (1024 * 1024)

    /** Decodes a sound file (picked by the user) into the project and places it at the current frame. */
    fun importAudio(uri: android.net.Uri) {
        val e = engine ?: return
        if (isBusy) return
        if (freeSpaceMb() < 100) {
            toast("Not enough free space on the phone to import sound")
            return
        }
        stopPlaying()
        commitFloating()
        val assetId = e.newAssetId()
        val startMs = currentFrameMs()
        val track = selectedTrackId
        busyLabel = "Importing sound…"
        busyProgress = 0f
        viewModelScope.launch {
            try {
                val d = withContext(Dispatchers.IO) {
                    AudioDecoder.decode(app, uri, pcmFile(assetId), peaksFile(assetId), { busyProgress = it }, { false })
                }
                busyLabel = null
                e.addAsset(AudioAsset(assetId, d.name, d.durationMs, d.channels))
                e.addClip(track, "Music", assetId, startMs)?.let { selectedTrackId = it.trackId }
                changed(structure = true)
                if (d.durationMs > animationMs() && !loopToAudio && frameCount <= 1) setLoopToAudio(true)
            } catch (t: Throwable) {
                busyLabel = null
                toast("Couldn't import that sound: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Records from the microphone. Voice-over plays the animation (with existing sound) from
     * the current frame while recording; otherwise it just records, starting at the current
     * frame. Needs the RECORD_AUDIO permission.
     *
     * The microphone starts first, so it also hears the count-in ticks; [stopRecording] uses them
     * to line the recording up exactly (see [CountIn]).
     */
    fun startRecording(voiceOver: Boolean, withCountIn: Boolean, trackId: Long?) {
        val e = engine ?: return
        if (isBusy || recordState != RecordState.Idle) return
        if (freeSpaceMb() < 50) {
            toast("Not enough free space on the phone to record")
            return
        }
        stopPlaying()
        e.cancelStroke()
        commitFloating()
        recordTrack = trackId
        recordStartMs = currentFrameMs()
        recordingAssetId = e.newAssetId()
        recordElapsedMs = 0
        recordJob = viewModelScope.launch {
            val rec = AudioRecorder(pcmFile(recordingAssetId), peaksFile(recordingAssetId))
            try {
                withContext(Dispatchers.IO) { rec.start() }
            } catch (t: Throwable) {
                recordState = RecordState.Idle
                toast(t.message ?: "Couldn't start recording")
                return@launch
            }
            recorder = rec
            val preRoll = if (withCountIn) CountIn.sound() else null
            val ref: PreviewPlayer? = when {
                voiceOver -> {
                    startPlaying(fromMs = recordStartMs, preRoll = preRoll)
                    player
                }
                preRoll != null -> try {
                    PreviewPlayer(AudioMixer(emptyList(), emptyList()) { null }, 0, 0, loop = false, preRoll = preRoll)
                        .also { it.start(); countInPlayer = it }
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
            recordRef = ref
            val countInFrames = ref?.preRollFrames ?: 0L
            recordState = if (countInFrames > 0) RecordState.CountIn else RecordState.Recording
            countIn = 3
            var t0 = System.nanoTime()
            while (isActive) {
                if (recordState == RecordState.CountIn) {
                    // Follow what is heard: 3, 2, 1 with the ticks, recording from the 4th beat.
                    val heard = ref?.heardFrames() ?: countInFrames
                    if (heard >= countInFrames) {
                        recordState = RecordState.Recording
                        t0 = System.nanoTime()
                    } else {
                        countIn = when {
                            heard < CountIn.TICK_MS[1] * FRAMES_PER_MS -> 3
                            heard < CountIn.TICK_MS[2] * FRAMES_PER_MS -> 2
                            else -> 1
                        }
                    }
                } else {
                    recordElapsedMs = (System.nanoTime() - t0) / 1_000_000
                }
                recordLevel = rec.level
                delay(50)
            }
        }
    }

    /** Stops recording; with [keep], the recording becomes a clip (one undo step). */
    fun stopRecording(keep: Boolean = true) {
        val e = engine
        recordJob?.cancel()
        recordJob = null
        val rec = recorder
        recorder = null
        val ref = recordRef
        recordRef = null
        // Read what lining up needs before the players are released.
        val refFirstNanos = ref?.firstFrameNanos()
        val countInFrames = ref?.preRollFrames ?: 0L
        val outType = ref?.outputDeviceType() ?: 0
        stopPlaying()
        countInPlayer?.stop()
        countInPlayer = null
        if (rec == null || e == null) {
            recordState = RecordState.Idle
            return
        }
        val durationMs = rec.stop()
        recordState = RecordState.Idle
        recordLevel = 0f
        val assetId = recordingAssetId
        recordingAssetId = -1L
        // How far into the recording the start frame was heard; the count-in before it is cut off.
        val startInRec = if (ref == null) 0.0 else lineUp(rec, assetId, refFirstNanos, countInFrames, "$outType-${rec.inputDeviceType}")
        val inMs = startInRec.roundToLong().coerceAtLeast(0)
        val startMs = recordStartMs + (-startInRec).roundToLong().coerceAtLeast(0)
        if (!keep || durationMs - inMs < 150) {
            pcmFile(assetId).delete()
            peaksFile(assetId).delete()
            if (keep) toast("Recording too short")
            return
        }
        val name = Names.numbered("Recording", project?.assets.orEmpty().map { it.name })
        e.addAsset(AudioAsset(assetId, name, durationMs, 1))
        e.addClip(recordTrack, "Voice", assetId, startMs, inMs)?.let { selectedTrackId = it.trackId }
        changed(structure = true)
    }

    /**
     * How far into the recording (ms) the start frame was heard. Exact when the microphone heard
     * the count-in ticks; otherwise the audio timestamps, corrected by what the count-in measured
     * last time on the same speaker/microphone [route] (phones' timestamps can be ~0.1 s off).
     */
    private fun lineUp(rec: AudioRecorder, assetId: Long, refFirstNanos: Long?, countInFrames: Long, route: String): Double {
        val countInMs = countInFrames.toDouble() / FRAMES_PER_MS
        // When the first played sample was heard, in ms after the first recorded one.
        val tsLead = if (refFirstNanos != null && rec.timestamped) (refFirstNanos - rec.firstFrameNanos) / 1e6 else null
        if (countInFrames > 0) {
            val heardAt = try { findCountIn(assetId, tsLead) } catch (_: Exception) { null }
            if (heardAt != null && (tsLead == null || abs(heardAt - tsLead) < 400)) {
                if (tsLead != null) app.settings.setRecordCorrectionMs(route, (tsLead - heardAt).toFloat())
                return heardAt + countInMs
            }
        }
        val correction = app.settings.recordCorrectionMs(route)
        if (correction == null) toast("Couldn't measure the timing (count-in off, or headphones). Nudge the clip if it's off.")
        val lead = if (tsLead != null) tsLead - (correction ?: 0f) else 0.0
        return lead + countInMs
    }

    /** Where (ms into the recording) the count-in's first sample was heard, from its ticks. */
    private fun findCountIn(assetId: Long, tsLead: Double?): Double? {
        PcmFile(pcmFile(assetId)).use { p ->
            // Look only until just after the last tick, before the voice-over playback can be heard.
            val endMs = (tsLead ?: 300.0).coerceIn(-200.0, 1500.0) + CountIn.TICK_MS.last() + 600
            val n = minOf(p.frames, (endMs * FRAMES_PER_MS).toLong()).toInt()
            if (n <= 0) return null
            val st = FloatArray(n * 2)
            p.readStereo(0, n, st)
            val mono = FloatArray(n) { st[2 * it] }
            return CountIn.locate(mono, n)?.let { it / FRAMES_PER_MS }
        }
    }

    // Clip / track edits (each is one undo step)

    fun moveClipLive(c: com.pixelgo.model.AudioClip) {
        engine?.setClipLive(c)
        structureTick++
    }

    fun finishClipDrag(original: com.pixelgo.model.AudioClip) {
        engine?.commitClip(original)
        changed(structure = true)
    }

    fun changeClip(c: com.pixelgo.model.AudioClip) = audioOp { it.changeClip(c) }
    fun deleteClip(id: Long) = audioOp { it.deleteClip(id); selectedClipId = null }
    fun duplicateClip(id: Long) = audioOp { it.duplicateClip(id) }
    fun splitClipAtCurrentFrame(id: Long) = audioOp {
        if (!it.splitClip(id, currentFrameMs())) toast("Move to a frame inside the clip to split it")
    }

    fun addTrack() = audioOp { selectedTrackId = it.addTrack("Track ${tracks().size + 1}").id }
    fun changeTrack(t: com.pixelgo.model.SoundTrack) = audioOp { it.changeTrack(t) }
    fun deleteTrack(id: Long) = audioOp {
        it.deleteTrack(id)
        if (selectedTrackId == id) selectedTrackId = null
    }

    fun setLoopToAudio(value: Boolean) {
        val p = project ?: return
        if (p.loopToAudio == value) return
        p.loopToAudio = value
        changed(structure = true)
    }

    private inline fun audioOp(op: (EditorEngine) -> Unit) {
        val e = engine ?: return
        if (isBusy) return
        stopPlaying()
        op(e)
        changed(structure = true)
    }

    companion object {
        /** Nearest-neighbour downscale to at most 64 px on the long side (timeline thumbnails). */
        fun thumbBitmap(px: IntArray, w: Int, h: Int): ImageBitmap {
            val step = maxOf(1, (maxOf(w, h) + 63) / 64)
            val tw = (w + step - 1) / step
            val th = (h + step - 1) / step
            val out = if (step == 1) px else IntArray(tw * th) { k ->
                px[min(h - 1, (k / tw) * step) * w + min(w - 1, (k % tw) * step)]
            }
            return Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}

package com.pixelgo.ui.editor

import android.graphics.Bitmap
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.pixelgo.PixelGoApp
import com.pixelgo.data.ExportImage
import com.pixelgo.data.PngExporter
import android.widget.Toast
import com.pixelgo.editor.EditorEngine
import com.pixelgo.editor.ColorMath
import com.pixelgo.editor.SelectionMath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Tool { Pencil, Eraser, Fill, Eyedropper, Select }

enum class SelectMode { Rect, Paint }

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

class EditorViewModel(private val app: PixelGoApp, projectId: String) : ViewModel() {
    private val project = app.projects.load(projectId)
    val engine: EditorEngine? = project?.let { EditorEngine(it) }

    val width = project?.width ?: 1
    val height = project?.height ?: 1

    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()

    /** Bumped whenever [image] changes, so the canvas redraws. */
    var renderTick by mutableIntStateOf(0)
        private set

    /** Bumped whenever layers or project info change, so lists refresh. */
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
    var projectName by mutableStateOf(project?.name ?: "")
        private set

    // ---- Animation state ---------------------------------------------------------------
    var frameIndex by mutableIntStateOf(project?.currentFrame ?: 0)
        private set
    var frameCount by mutableIntStateOf(project?.frames?.size ?: 1)
        private set
    var fps by mutableIntStateOf(project?.fps ?: 8)
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
    var playImages: List<ImageBitmap> = emptyList()
        private set
    private var playJob: Job? = null
    private var ignoreGesture = false

    private var unsaved = false
    private var pendingX = 0
    private var pendingY = 0

    init {
        syncBitmap()
    }

    // ---- Frames --------------------------------------------------------------------------

    fun frames() = project?.frames.orEmpty()

    fun selectFrame(i: Int) {
        val e = engine ?: return
        if (i == e.project.currentFrame) return
        stopPlaying()
        e.cancelStroke()
        commitFloating()
        e.selectFrame(i)
        syncBitmap()
        syncFrames()
        updateOnion()
        structureTick++
    }

    fun addFrame() = frameOp { it.addFrame() }
    fun duplicateFrame() = frameOp { it.duplicateFrame() }
    fun deleteFrame() = frameOp { it.deleteFrame() }
    fun moveFrame(from: Int, to: Int) = frameOp { it.moveFrame(from, to) }
    fun setFrameHold(i: Int, hold: Int) = frameOp { it.setFrameHold(i, hold) }

    val canAddFrame get() = engine?.canAddFrame == true

    private inline fun frameOp(op: (EditorEngine) -> Unit) {
        val e = engine ?: return
        stopPlaying()
        e.cancelStroke()
        commitFloating()
        op(e)
        changed(structure = true)
    }

    fun changeFps(value: Int) {
        val p = project ?: return
        p.fps = value.coerceIn(1, 30)
        fps = p.fps
        changed()
        if (isPlaying) { stopPlaying(); startPlaying() }
    }

    fun toggleOnionSkin() {
        onionSkin = !onionSkin
        updateOnion()
    }

    private fun syncFrames() {
        val p = project ?: return
        frameIndex = p.currentFrame
        frameCount = p.frames.size
    }

    private fun updateOnion() {
        val p = project
        if (p == null || !onionSkin || p.frames.size < 2) {
            onionPrev = null; onionNext = null
        } else {
            val f = p.currentFrame
            onionPrev = if (f > 0) bitmapOf(ColorMath.compositeFrame(p, f - 1)) else null
            onionNext = if (f < p.frames.lastIndex) bitmapOf(ColorMath.compositeFrame(p, f + 1)) else null
        }
        renderTick++
    }

    /** Composite image of any frame (for the timeline thumbnails). */
    fun frameImage(i: Int): ImageBitmap? {
        val p = project ?: return null
        if (i !in p.frames.indices) return null
        return bitmapOf(ColorMath.compositeFrame(p, i))
    }

    private fun bitmapOf(px: IntArray): ImageBitmap =
        Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

    fun togglePlay() {
        if (isPlaying) stopPlaying() else startPlaying()
    }

    private fun startPlaying() {
        val p = project ?: return
        if (p.frames.size < 2) return
        engine?.cancelStroke()
        commitFloating()
        playImages = p.frames.indices.map { bitmapOf(ColorMath.compositeFrame(p, it)) }
        isPlaying = true
        playJob = viewModelScope.launch {
            var i = p.currentFrame
            while (true) {
                playIndex = i
                delay(p.frames[i].hold * 1000L / p.fps)
                i = (i + 1) % p.frames.size
            }
        }
    }

    fun stopPlaying() {
        playJob?.cancel()
        playJob = null
        if (isPlaying) {
            isPlaying = false
            playImages = emptyList()
            renderTick++
        }
    }

    /** Every frame flattened (visible layers), in order. */
    fun exportFrames(): List<IntArray> {
        val p = project ?: return emptyList()
        return p.frames.indices.map { ColorMath.compositeFrame(p, it) }
    }

    /** GIF delay per frame in 1/100 s. */
    fun frameDelaysCs(): List<Int> {
        val p = project ?: return emptyList()
        return p.frames.map { (it.hold * 100.0 / p.fps).roundToInt() }
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
        // Touching the canvas while playing just stops playback.
        ignoreGesture = isPlaying
        if (isPlaying) { stopPlaying(); return }
        pendingX = x; pendingY = y
        when (tool) {
            Tool.Pencil -> e.beginStroke(x, y, color)
            Tool.Eraser -> e.beginStroke(x, y, 0)
            Tool.Fill, Tool.Eyedropper -> Unit
            Tool.Select -> {
                val f = e.floating
                if (f != null) {
                    // Drag the floating cells from wherever the finger lands.
                    grabDX = x - f.x; grabDY = y - f.y
                    floatStartX = f.x; floatStartY = f.y
                } else {
                    selSnapshot = e.selection?.copyOf()
                    if (selectMode == SelectMode.Rect) {
                        rectStartX = x; rectStartY = y
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
        pendingX = x; pendingY = y
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
        if (ignoreGesture) { ignoreGesture = false; return }
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
        if (ignoreGesture) { ignoreGesture = false; return }
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

    fun selectAll() { engine?.selectAll(); selectionChanged() }
    fun deselect() { engine?.clearSelection(); selectionChanged() }

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
        stopPlaying()
        // While cells are floating, undo means "put them back".
        if (engine?.floating != null) { cancelFloating(); return }
        if (engine?.undo() == true) changed(structure = true)
    }

    fun redo() {
        stopPlaying()
        if (engine?.floating != null) return
        if (engine?.redo() == true) changed(structure = true)
    }

    fun selectLayer(i: Int) { commitFloating(); engine?.selectLayer(i); structureTick++ }
    fun addLayer() = layerOp { it.addLayer() }
    fun duplicateLayer() = layerOp { it.duplicateLayer() }
    fun deleteLayer() = layerOp { it.deleteLayer() }
    fun mergeDown() = layerOp { it.mergeDown() }
    fun moveLayer(from: Int, to: Int) = layerOp { it.moveLayer(from, to) }
    fun setLayerVisible(i: Int, v: Boolean) = layerOp { it.setLayerVisible(i, v) }
    fun renameLayer(i: Int, name: String) = layerOp { it.renameLayer(i, name) }

    private inline fun layerOp(op: (EditorEngine) -> Unit) {
        val e = engine ?: return
        commitFloating()
        op(e)
        changed(structure = true)
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

    /** Flattened visible layers, for export. */
    fun exportPixels(): IntArray = engine?.composite?.copyOf() ?: IntArray(width * height)

    val layerCount: Int get() = engine?.project?.layers?.size ?: 0
    val hiddenLayerCount: Int get() = engine?.project?.layers?.count { !it.visible } ?: 0

    /** Copies of each layer (bottom to top) with export file names, for per-layer export. */
    fun exportLayers(scale: Int, includeHidden: Boolean): List<ExportImage> {
        val layers = engine?.project?.layers?.filter { includeHidden || it.visible } ?: return emptyList()
        val names = PngExporter.layerFileNames(layers.map { it.name }, scale)
        return layers.mapIndexed { i, l -> ExportImage(names[i], l.pixels.copyOf()) }
    }

    private var autosaveJob: Job? = null

    private fun changed(structure: Boolean = false) {
        unsaved = true
        // Autosave a couple of seconds after the last edit, so a crash or forced close loses little.
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(2000)
            if (engine?.isStroking != true) save()
        }
        canUndo = engine?.history?.canUndo == true
        canRedo = engine?.history?.canRedo == true
        syncBitmap()
        syncFrames()
        if (structure) {
            updateOnion()
            structureTick++
        }
    }

    private fun syncBitmap() {
        val e = engine ?: return
        var any = false
        e.consumeDirty(
            onFull = { bitmap.setPixels(it, 0, width, 0, 0, width, height); any = true },
            onPixel = { i, c -> bitmap.setPixel(i % width, i / width, c); any = true },
        )
        if (any) renderTick++
    }

    fun save() {
        val p = project ?: return
        if (!unsaved) return
        unsaved = false
        // If cells are mid-move, save them where they came from (as if the move was cancelled)
        // without disturbing what's on screen.
        val f = engine?.floating
        val lifted = f?.lifted
        if (f != null && lifted != null) {
            val copy = p.deepCopy()
            copy.layers.getOrNull(copy.indexOfLayer(f.layerId))?.let { l ->
                lifted.forEach { (i, v) -> l.pixels[i] = v }
            }
            app.projects.saveAsync(copy)
        } else {
            app.projects.saveAsync(p)
        }
    }

    override fun onCleared() {
        stopPlaying()
        engine?.cancelStroke()
        save()
    }
}

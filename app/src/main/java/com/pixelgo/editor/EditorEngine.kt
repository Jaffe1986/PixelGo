package com.pixelgo.editor

import com.pixelgo.model.MAX_FRAMES
import com.pixelgo.model.MAX_LAYERS
import com.pixelgo.model.MAX_TOTAL_CELLS
import com.pixelgo.model.PixelProject

/**
 * All editing logic, independent of Android so it can be unit tested.
 * Keeps an up-to-date [composite] of the visible layers and tracks which pixels changed
 * since the last [consumeDirty] so the UI can update its bitmap cheaply.
 */
class EditorEngine(val project: PixelProject) {
    val width = project.width
    val height = project.height
    val history = History()

    /** Mirror left <-> right. */
    var mirrorX = false

    /** Mirror top <-> bottom. */
    var mirrorY = false

    val composite: IntArray = ColorMath.compositeAll(project)

    private val dirty = IntList(256)
    private var fullDirty = true

    // Active stroke state.
    private var strokeBefore: LinkedHashMap<Int, Int>? = null
    private var strokeLayerId = -1L
    private var strokeColor = 0
    private var lastX = 0
    private var lastY = 0

    val isStroking get() = strokeBefore != null

    // ---- Drawing -------------------------------------------------------------------------

    fun beginStroke(x: Int, y: Int, color: Int) {
        val layer = project.activeLayer ?: return
        strokeBefore = LinkedHashMap()
        strokeLayerId = layer.id
        strokeColor = color
        lastX = x
        lastY = y
        plot(x, y)
    }

    fun strokeTo(x: Int, y: Int) {
        if (strokeBefore == null || (x == lastX && y == lastY)) return
        Raster.line(lastX, lastY, x, y) { px, py -> plot(px, py) }
        lastX = x
        lastY = y
    }

    /** Commits the stroke to history. Returns true if any pixel changed. */
    fun endStroke(): Boolean {
        val before = strokeBefore ?: return false
        strokeBefore = null
        if (before.isEmpty()) return false
        val layer = project.layers[project.indexOfLayer(strokeLayerId)]
        val indices = before.keys.toIntArray()
        val b = IntArray(indices.size) { before.getValue(indices[it]) }
        val a = IntArray(indices.size) { layer.pixels[indices[it]] }
        history.push(PixelEdit(strokeLayerId, project.currentFrame, indices, b, a))
        return true
    }

    /** Reverts the in-progress stroke without recording it (e.g. a second finger touched down). */
    fun cancelStroke() {
        val before = strokeBefore ?: return
        strokeBefore = null
        val idx = project.indexOfLayer(strokeLayerId)
        if (idx < 0) return
        val px = project.layers[idx].pixels
        for ((i, old) in before) {
            px[i] = old
            markDirty(i)
        }
    }

    private fun plot(x: Int, y: Int) {
        val before = strokeBefore ?: return
        val idx = project.indexOfLayer(strokeLayerId)
        if (idx < 0) return
        val px = project.layers[idx].pixels
        Raster.forEachMirrored(x, y, width, height, mirrorX, mirrorY) { mx, my ->
            if (mx in 0 until width && my in 0 until height) {
                val i = my * width + mx
                val old = px[i]
                if (old != strokeColor) {
                    if (!before.containsKey(i)) before[i] = old
                    px[i] = strokeColor
                    markDirty(i)
                }
            }
        }
    }

    /** Flood fill (honours symmetry). Returns true if anything changed. */
    fun fill(x: Int, y: Int, color: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        val layer = project.activeLayer ?: return false
        val px = layer.pixels
        val before = LinkedHashMap<Int, Int>()
        Raster.forEachMirrored(x, y, width, height, mirrorX, mirrorY) { mx, my ->
            val target = px[my * width + mx]
            val changed = Raster.floodFill(px, width, height, mx, my, color)
            for (i in changed) {
                if (!before.containsKey(i)) before[i] = target
                markDirty(i)
            }
        }
        if (before.isEmpty()) return false
        val indices = before.keys.toIntArray()
        history.push(
            PixelEdit(
                layer.id, project.currentFrame, indices,
                IntArray(indices.size) { before.getValue(indices[it]) },
                IntArray(indices.size) { px[indices[it]] },
            )
        )
        return true
    }

    /** Color shown on screen at (x,y), or null if outside the canvas. */
    fun pick(x: Int, y: Int): Int? =
        if (x in 0 until width && y in 0 until height) composite[y * width + x] else null

    // ---- Selection -----------------------------------------------------------------------

    /** Selected cells (true = selected), or null when nothing is selected. */
    var selection: BooleanArray? = null
        private set

    /** Cells currently floating (being moved or pasted), or null. */
    var floating: Floating? = null
        private set

    private var selectPaintAdding = true
    private var selLastX = 0
    private var selLastY = 0

    val hasSelection get() = selection?.any { it } == true

    fun clearSelection() {
        selection = null
    }

    fun selectAll() {
        selection = BooleanArray(width * height) { true }
    }

    /** Replaces the selection with the rectangle spanning the two corner cells (clamped). */
    fun selectRect(x0: Int, y0: Int, x1: Int, y1: Int) {
        val l = minOf(x0, x1).coerceIn(0, width - 1)
        val r = maxOf(x0, x1).coerceIn(0, width - 1)
        val t = minOf(y0, y1).coerceIn(0, height - 1)
        val b = maxOf(y0, y1).coerceIn(0, height - 1)
        val m = BooleanArray(width * height)
        for (y in t..b) for (x in l..r) m[y * width + x] = true
        selection = m
    }

    /** Starts painting the selection. Starting on a selected cell removes cells instead. */
    fun beginSelectPaint(x: Int, y: Int) {
        val m = selection ?: BooleanArray(width * height).also { selection = it }
        selectPaintAdding = !(x in 0 until width && y in 0 until height && m[y * width + x])
        selLastX = x
        selLastY = y
        paintSelectCell(x, y)
    }

    fun selectPaintTo(x: Int, y: Int) {
        Raster.line(selLastX, selLastY, x, y) { px, py -> paintSelectCell(px, py) }
        selLastX = x
        selLastY = y
    }

    private fun paintSelectCell(x: Int, y: Int) {
        val m = selection ?: return
        if (x in 0 until width && y in 0 until height) m[y * width + x] = selectPaintAdding
    }

    /** Restores a selection snapshot (used when a gesture is cancelled). */
    fun restoreSelection(snapshot: BooleanArray?) {
        selection = snapshot?.copyOf()
    }

    /** Copies the selected cells of the active layer. */
    fun copySelection(): Clip? {
        val m = selection ?: return null
        val layer = project.activeLayer ?: return null
        val b = SelectionMath.bounds(m, width, height) ?: return null
        val cw = b[2] - b[0]
        val ch = b[3] - b[1]
        val px = IntArray(cw * ch)
        val mask = BooleanArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val src = (b[1] + y) * width + b[0] + x
            if (m[src]) {
                mask[y * cw + x] = true
                px[y * cw + x] = layer.pixels[src]
            }
        }
        return Clip(cw, ch, px, mask, b[0], b[1])
    }

    /** Clears the selected cells on the active layer (undoable). */
    fun deleteSelection(): Boolean {
        val m = selection ?: return false
        val layer = project.activeLayer ?: return false
        val px = layer.pixels
        val idx = IntList()
        for (i in m.indices) if (m[i] && px[i] != 0) idx.add(i)
        if (idx.size == 0) return false
        val indices = idx.toArray()
        val before = IntArray(indices.size) { px[indices[it]] }
        for (i in indices) {
            px[i] = 0
            markDirty(i)
        }
        history.push(PixelEdit(layer.id, project.currentFrame, indices, before, IntArray(indices.size)))
        return true
    }

    /** Copy + delete. */
    fun cutSelection(): Clip? {
        val clip = copySelection() ?: return null
        deleteSelection()
        return clip
    }

    /** Lifts the selected cells off the active layer so they can be dragged. */
    fun beginMove(): Boolean {
        if (floating != null) return false
        val clip = copySelection() ?: return false
        val layer = project.activeLayer ?: return false
        val m = selection ?: return false
        val lifted = LinkedHashMap<Int, Int>()
        for (i in m.indices) {
            if (m[i]) {
                lifted[i] = layer.pixels[i]
                if (layer.pixels[i] != 0) {
                    layer.pixels[i] = 0
                    markDirty(i)
                }
            }
        }
        floating = Floating(clip, clip.srcX, clip.srcY, layer.id, lifted, m.copyOf())
        selection = null
        return true
    }

    /** Starts placing [clip] on the active layer, at its original spot if it fits. */
    fun beginPaste(clip: Clip): Boolean {
        if (floating != null) return false
        val layer = project.activeLayer ?: return false
        val x = clip.srcX.coerceIn(0, maxOf(0, width - clip.width))
        val y = clip.srcY.coerceIn(0, maxOf(0, height - clip.height))
        floating = Floating(clip, x, y, layer.id, null, selection?.copyOf())
        selection = null
        return true
    }

    fun moveFloatingTo(x: Int, y: Int) {
        val f = floating ?: return
        // Keep at least one cell of the clip over the canvas.
        f.x = x.coerceIn(1 - f.clip.width, width - 1)
        f.y = y.coerceIn(1 - f.clip.height, height - 1)
    }

    /**
     * Drops the floating cells onto their layer as one undoable step. Fully transparent
     * cells don't overwrite what's underneath. The dropped cells become the new selection.
     */
    fun commitFloating(): Boolean {
        val f = floating ?: return false
        floating = null
        val li = project.indexOfLayer(f.layerId)
        if (li < 0) return false
        val px = project.layers[li].pixels
        val before = LinkedHashMap<Int, Int>(f.lifted ?: emptyMap())
        val newSel = BooleanArray(width * height)
        val c = f.clip
        for (cy in 0 until c.height) for (cx in 0 until c.width) {
            val k = cy * c.width + cx
            if (!c.mask[k]) continue
            val x = f.x + cx
            val y = f.y + cy
            if (x !in 0 until width || y !in 0 until height) continue
            val i = y * width + x
            newSel[i] = true
            val v = c.pixels[k]
            if (v == 0) continue
            if (!before.containsKey(i)) before[i] = px[i]
            px[i] = v
            markDirty(i)
        }
        selection = newSel
        val changed = before.entries.filter { it.value != px[it.key] }
        if (changed.isEmpty()) return false
        val indices = IntArray(changed.size) { changed[it].key }
        history.push(
            PixelEdit(
                f.layerId, project.currentFrame, indices,
                IntArray(indices.size) { changed[it].value },
                IntArray(indices.size) { px[indices[it]] },
            )
        )
        return true
    }

    /** Puts everything back as it was before the move/paste started. */
    fun cancelFloating() {
        val f = floating ?: return
        floating = null
        val li = project.indexOfLayer(f.layerId)
        if (li >= 0) {
            val px = project.layers[li].pixels
            f.lifted?.forEach { (i, v) ->
                if (px[i] != v) {
                    px[i] = v
                    markDirty(i)
                }
            }
        }
        selection = f.previousSelection
    }

    // ---- Layers --------------------------------------------------------------------------

    val canAddLayer
        get() = project.layers.size < MAX_LAYERS &&
            project.totalCells(layerCount = project.layers.size + 1) <= MAX_TOTAL_CELLS

    fun selectLayer(index: Int) {
        if (index in project.layers.indices) project.activeLayerIndex = index
    }

    fun addLayer() {
        if (!canAddLayer) return
        val layer = project.newLayer("Layer ${project.nextLayerId}")
        val index = project.activeLayerIndex + 1
        project.layers.add(index, layer)
        project.activeLayerIndex = index
        history.push(AddLayerEdit(index, layer))
        fullDirty = true
    }

    fun duplicateLayer() {
        if (!canAddLayer) return
        val src = project.activeLayer ?: return
        val copy = src.deepCopy(project.nextLayerId++, "${src.name} copy")
        val index = project.activeLayerIndex + 1
        project.layers.add(index, copy)
        project.activeLayerIndex = index
        history.push(AddLayerEdit(index, copy))
        fullDirty = true
    }

    fun deleteLayer() {
        if (project.layers.size <= 1) return
        val index = project.activeLayerIndex
        val layer = project.layers.removeAt(index)
        project.activeLayerIndex = index.coerceAtMost(project.layers.lastIndex)
        history.push(DeleteLayerEdit(index, layer))
        fullDirty = true
    }

    fun mergeDown() {
        val index = project.activeLayerIndex
        if (index <= 0) return
        val upper = project.layers[index]
        val lower = project.layers[index - 1]
        // Merge every frame, not just the one on screen.
        val before = lower.cels.map { it.copyOf() }
        for (f in lower.cels.indices) {
            val lo = lower.cels[f]
            val up = upper.cels[f]
            for (i in lo.indices) lo[i] = ColorMath.blend(lo[i], up[i])
        }
        project.layers.removeAt(index)
        project.activeLayerIndex = index - 1
        history.push(MergeDownEdit(index, upper, before, lower.cels.map { it.copyOf() }))
        fullDirty = true
    }

    fun moveLayer(from: Int, to: Int) {
        if (from == to || from !in project.layers.indices || to !in project.layers.indices) return
        MoveLayerEdit.move(project, from, to)
        history.push(MoveLayerEdit(from, to))
        fullDirty = true
    }

    fun setLayerVisible(index: Int, visible: Boolean) {
        val l = project.layers.getOrNull(index) ?: return
        if (l.visible == visible) return
        history.push(LayerPropsEdit(l.id, l.name, l.name, l.visible, visible))
        l.visible = visible
        fullDirty = true
    }

    fun renameLayer(index: Int, name: String) {
        val l = project.layers.getOrNull(index) ?: return
        if (l.name == name || name.isBlank()) return
        history.push(LayerPropsEdit(l.id, l.name, name, l.visible, l.visible))
        l.name = name
    }

    // ---- Frames --------------------------------------------------------------------------

    val frameCount get() = project.frames.size

    val canAddFrame
        get() = project.frames.size < MAX_FRAMES &&
            project.totalCells(frameCount = project.frames.size + 1) <= MAX_TOTAL_CELLS

    fun selectFrame(index: Int) {
        if (index !in project.frames.indices || index == project.currentFrame) return
        project.currentFrame = index
        fullDirty = true
    }

    /** Adds a blank frame after the current one. */
    fun addFrame() = insertFrame(copyCurrent = false)

    /** Adds a copy of the current frame after it. */
    fun duplicateFrame() = insertFrame(copyCurrent = true)

    private fun insertFrame(copyCurrent: Boolean) {
        if (!canAddFrame) return
        val cur = project.currentFrame
        val index = cur + 1
        val frame = com.pixelgo.model.Frame(if (copyCurrent) project.frames[cur].hold else 1)
        val cels = project.layers.associate {
            it.id to if (copyCurrent) it.cels[cur].copyOf() else IntArray(project.size)
        }
        FrameOps.insert(project, index, frame, cels)
        history.push(AddFrameEdit(index, frame, cels))
        fullDirty = true
    }

    fun deleteFrame() {
        if (project.frames.size <= 1) return
        val index = project.currentFrame
        val frame = project.frames[index]
        val cels = FrameOps.celsAt(project, index)
        FrameOps.remove(project, index)
        history.push(DeleteFrameEdit(index, frame, cels))
        fullDirty = true
    }

    fun moveFrame(from: Int, to: Int) {
        if (from == to || from !in project.frames.indices || to !in project.frames.indices) return
        FrameOps.move(project, from, to)
        history.push(MoveFrameEdit(from, to))
        fullDirty = true
    }

    fun setFrameHold(index: Int, hold: Int) {
        val f = project.frames.getOrNull(index) ?: return
        val h = hold.coerceIn(1, 99)
        if (f.hold == h) return
        history.push(FrameHoldEdit(index, f.hold, h))
        f.hold = h
    }

    // ---- History -------------------------------------------------------------------------

    fun undo(): Boolean = afterHistory { history.undo(project) }
    fun redo(): Boolean = afterHistory { history.redo(project) }

    private inline fun afterHistory(step: () -> Edit?): Boolean {
        val frameBefore = project.currentFrame
        val e = step() ?: return false
        if (e is PixelEdit && project.currentFrame == frameBefore) {
            e.changedIndices.forEach { markDirty(it) }
        } else {
            fullDirty = true
        }
        return true
    }

    // ---- Composite tracking --------------------------------------------------------------

    private fun markDirty(i: Int) {
        composite[i] = ColorMath.compositeAt(project, i)
        if (!fullDirty) dirty.add(i)
    }

    /**
     * Hands the pending changes to [onFull] (whole composite changed) or [onPixel] for each
     * changed index, then clears them.
     */
    inline fun consumeDirty(onFull: (IntArray) -> Unit, onPixel: (Int, Int) -> Unit) {
        if (takeFullDirty()) {
            onFull(composite)
        } else {
            val list = takeDirtyList()
            for (k in 0 until list.size) {
                val i = list[k]
                onPixel(i, composite[i])
            }
            list.clear()
        }
    }

    @PublishedApi
    internal fun takeFullDirty(): Boolean {
        if (!fullDirty) return false
        ColorMath.compositeAll(project, composite)
        fullDirty = false
        dirty.clear()
        return true
    }

    @PublishedApi
    internal fun takeDirtyList(): IntList = dirty

    fun markAllDirty() {
        fullDirty = true
    }
}

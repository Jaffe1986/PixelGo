package com.pixelgo.editor

import com.pixelgo.model.AudioAsset
import com.pixelgo.model.AudioClip
import com.pixelgo.model.Frame
import com.pixelgo.model.Layer
import com.pixelgo.model.SoundTrack
import com.pixelgo.model.MAX_HOLD
import com.pixelgo.model.MAX_LAYERS
import com.pixelgo.model.PixelProject

/** Where a new frame goes, relative to the current one. */
enum class FramePosition { BEFORE, AFTER, END }

/**
 * Work that touches every frame of a layer (or many frames), too slow for the UI thread.
 * Call [runWork] on a background thread, then [finish] on the UI thread. Nothing else may
 * edit the project in between.
 */
class BulkJob(
    val label: String,
    private val work: (progress: (Float) -> Unit) -> Unit,
    private val done: () -> Unit,
) {
    fun runWork(progress: (Float) -> Unit) = work(progress)
    fun finish() = done()
}

/**
 * All editing logic, independent of Android so it can be unit tested.
 *
 * Pixels live in [store] (one cel per layer per frame). The engine keeps an up-to-date
 * [composite] of the current frame's visible layers and tracks which pixels changed since the
 * last [consumeDirty] so the UI can update its bitmap cheaply.
 */
class EditorEngine(val project: PixelProject, val store: CelStore) {
    val width = project.width
    val height = project.height
    val history = History()

    /** Mirror left <-> right. */
    var mirrorX = false

    /** Mirror top <-> bottom. */
    var mirrorY = false

    val composite = IntArray(project.size)

    /** Cels of the visible layers on the current frame, bottom to top (null = empty). */
    private var visCels: Array<IntArray?> = emptyArray()

    private val dirty = IntList(256)
    private var fullDirty = true

    // Active stroke state.
    private var strokeBefore: LinkedHashMap<Int, Int>? = null
    private var strokeLayerId = -1L
    private var strokeFrameUid = -1L
    private var strokeColor = 0
    private var lastX = 0
    private var lastY = 0

    val isStroking get() = strokeBefore != null

    init {
        refreshCels()
    }

    val currentFrameUid: Long get() = project.currentFrameUid

    // ---- Cel access ----------------------------------------------------------------------

    /** Re-reads the current frame's cels (after a frame change or layer structure change). */
    private fun refreshCels(full: Boolean = true) {
        val f = project.currentFrameUid
        val visible = project.layers.filter { it.visible }
        visCels = Array(visible.size) { store.read(visible[it].id, f) }
        store.pin(project.layers.map { CelKey(it.id, f) })
        if (full) fullDirty = true
    }

    private fun readCel(layerId: Long, frameUid: Long = project.currentFrameUid): IntArray? =
        store.read(layerId, frameUid)

    /** The current frame's cel for [layerId], ready to be modified. */
    private fun celForEdit(layerId: Long): IntArray {
        val px = store.edit(layerId, project.currentFrameUid)
        // Keep the composite's cel list pointing at this array (it may have just been created).
        var pos = 0
        for (l in project.layers) {
            if (l.id == layerId) {
                if (l.visible && visCels.getOrNull(pos) !== px) visCels[pos] = px
                break
            }
            if (l.visible) pos++
        }
        return px
    }

    // ---- Drawing -------------------------------------------------------------------------

    fun beginStroke(x: Int, y: Int, color: Int) {
        val layer = project.activeLayer ?: return
        strokeBefore = LinkedHashMap()
        strokeLayerId = layer.id
        strokeFrameUid = project.currentFrameUid
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
        val px = readCel(strokeLayerId, strokeFrameUid) ?: return false
        val indices = before.keys.toIntArray()
        val b = IntArray(indices.size) { before.getValue(indices[it]) }
        val a = IntArray(indices.size) { px[indices[it]] }
        history.push(PixelEdit(strokeLayerId, strokeFrameUid, indices, b, a))
        return true
    }

    /** Reverts the in-progress stroke without recording it (e.g. a second finger touched down). */
    fun cancelStroke() {
        val before = strokeBefore ?: return
        strokeBefore = null
        if (before.isEmpty() || project.indexOfLayer(strokeLayerId) < 0) return
        if (strokeFrameUid != project.currentFrameUid) return
        val px = celForEdit(strokeLayerId)
        for ((i, old) in before) {
            px[i] = old
            markDirty(i)
        }
    }

    private fun plot(x: Int, y: Int) {
        val before = strokeBefore ?: return
        if (strokeFrameUid != project.currentFrameUid || project.indexOfLayer(strokeLayerId) < 0) return
        Raster.forEachMirrored(x, y, width, height, mirrorX, mirrorY) { mx, my ->
            if (mx in 0 until width && my in 0 until height) {
                val i = my * width + mx
                val old = readCel(strokeLayerId)?.get(i) ?: 0
                if (old != strokeColor) {
                    val px = celForEdit(strokeLayerId)
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
        val before = LinkedHashMap<Int, Int>()
        Raster.forEachMirrored(x, y, width, height, mirrorX, mirrorY) { mx, my ->
            val target = readCel(layer.id)?.get(my * width + mx) ?: 0
            if (target != color) {
                val px = celForEdit(layer.id)
                val changed = Raster.floodFill(px, width, height, mx, my, color)
                for (i in changed) {
                    if (!before.containsKey(i)) before[i] = target
                    markDirty(i)
                }
            }
        }
        if (before.isEmpty()) return false
        val px = readCel(layer.id) ?: return false
        val indices = before.keys.toIntArray()
        history.push(
            PixelEdit(
                layer.id, project.currentFrameUid, indices,
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
        val src = readCel(layer.id)
        val cw = b[2] - b[0]
        val ch = b[3] - b[1]
        val px = IntArray(cw * ch)
        val mask = BooleanArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val i = (b[1] + y) * width + b[0] + x
            if (m[i]) {
                mask[y * cw + x] = true
                px[y * cw + x] = src?.get(i) ?: 0
            }
        }
        return Clip(cw, ch, px, mask, b[0], b[1])
    }

    /** Clears the selected cells on the active layer (undoable). */
    fun deleteSelection(): Boolean {
        val m = selection ?: return false
        val layer = project.activeLayer ?: return false
        val src = readCel(layer.id) ?: return false
        val idx = IntList()
        for (i in m.indices) if (m[i] && src[i] != 0) idx.add(i)
        if (idx.size == 0) return false
        val indices = idx.toArray()
        val before = IntArray(indices.size) { src[indices[it]] }
        val px = celForEdit(layer.id)
        for (i in indices) {
            px[i] = 0
            markDirty(i)
        }
        history.push(PixelEdit(layer.id, project.currentFrameUid, indices, before, IntArray(indices.size)))
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
        val src = readCel(layer.id)
        val lifted = LinkedHashMap<Int, Int>()
        var px: IntArray? = null
        for (i in m.indices) {
            if (!m[i]) continue
            val v = src?.get(i) ?: 0
            lifted[i] = v
            if (v != 0) {
                val cel = px ?: celForEdit(layer.id).also { px = it }
                cel[i] = 0
                markDirty(i)
            }
        }
        floating = Floating(clip, clip.srcX, clip.srcY, layer.id, project.currentFrameUid, lifted, m.copyOf())
        selection = null
        return true
    }

    /** Starts placing [clip] on the active layer, at its original spot if it fits. */
    fun beginPaste(clip: Clip): Boolean {
        if (floating != null) return false
        val layer = project.activeLayer ?: return false
        val x = clip.srcX.coerceIn(0, maxOf(0, width - clip.width))
        val y = clip.srcY.coerceIn(0, maxOf(0, height - clip.height))
        floating = Floating(clip, x, y, layer.id, project.currentFrameUid, null, selection?.copyOf())
        selection = null
        return true
    }

    fun moveFloatingTo(x: Int, y: Int) {
        val f = floating ?: return
        // Keep at least one cell of the clip over the canvas.
        f.x = x.coerceIn(1 - f.clip.width, width - 1)
        f.y = y.coerceIn(1 - f.clip.height, height - 1)
    }

    private fun floatingCel(f: Floating): IntArray =
        if (f.frameUid == project.currentFrameUid) celForEdit(f.layerId) else store.edit(f.layerId, f.frameUid)

    /**
     * Drops the floating cells onto their layer as one undoable step. Fully transparent
     * cells don't overwrite what's underneath. The dropped cells become the new selection.
     */
    fun commitFloating(): Boolean {
        val f = floating ?: return false
        floating = null
        if (project.indexOfLayer(f.layerId) < 0 || project.indexOfFrame(f.frameUid) < 0) return false
        val onScreen = f.frameUid == project.currentFrameUid
        val px = floatingCel(f)
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
            if (onScreen) markDirty(i)
        }
        selection = newSel
        val changed = before.entries.filter { it.value != px[it.key] }
        if (changed.isEmpty()) return false
        val indices = IntArray(changed.size) { changed[it].key }
        history.push(
            PixelEdit(
                f.layerId, f.frameUid, indices,
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
        val lifted = f.lifted
        if (lifted != null && project.indexOfLayer(f.layerId) >= 0 && project.indexOfFrame(f.frameUid) >= 0) {
            val onScreen = f.frameUid == project.currentFrameUid
            val px = floatingCel(f)
            lifted.forEach { (i, v) ->
                if (px[i] != v) {
                    px[i] = v
                    if (onScreen) markDirty(i)
                }
            }
        }
        selection = f.previousSelection
    }

    /** Finishes any stroke / floating cells before a structural change. */
    private fun settle() {
        cancelStroke()
        commitFloating()
    }

    // ---- Layers --------------------------------------------------------------------------

    val canAddLayer get() = project.layers.size < MAX_LAYERS

    fun selectLayer(index: Int) {
        if (index in project.layers.indices) project.activeLayerIndex = index
    }

    fun addLayer() {
        if (!canAddLayer) return
        settle()
        val layer = project.newLayer("Layer ${project.nextLayerId}")
        val index = project.activeLayerIndex + 1
        project.layers.add(index, layer)
        project.activeLayerIndex = index
        project.touchLayers()
        history.push(AddLayerEdit(index, layer))
        refreshCels()
    }

    /** Copies the active layer on every frame (background job). */
    fun duplicateLayerJob(): BulkJob? {
        if (!canAddLayer) return null
        settle()
        val src = project.activeLayer ?: return null
        val copy = Layer(project.nextLayerId++, "${src.name} copy", src.visible)
        val uids = project.frameUids()
        val index = project.activeLayerIndex + 1
        return BulkJob(
            "Duplicating layer…",
            work = { progress ->
                uids.forEachIndexed { k, f ->
                    store.copy(CelKey(src.id, f), CelKey(copy.id, f))
                    progress((k + 1f) / uids.size)
                }
            },
            done = {
                project.layers.add(index, copy)
                project.activeLayerIndex = index
                project.touchLayers()
                history.push(AddLayerEdit(index, copy))
                refreshCels()
            },
        )
    }

    fun deleteLayer() {
        if (project.layers.size <= 1) return
        settle()
        val index = project.activeLayerIndex
        val layer = project.layers.removeAt(index)
        project.activeLayerIndex = index.coerceAtMost(project.layers.lastIndex)
        project.touchLayers()
        history.push(DeleteLayerEdit(index, layer))
        refreshCels()
    }

    /**
     * Merges the active layer into the one below on every frame (background job). The result
     * is a new layer, so undo simply restores the two originals.
     */
    fun mergeDownJob(): BulkJob? {
        val index = project.activeLayerIndex
        if (index <= 0) return null
        settle()
        val upper = project.layers[index]
        val lower = project.layers[index - 1]
        val merged = Layer(project.nextLayerId++, lower.name, lower.visible)
        val uids = project.frameUids()
        return BulkJob(
            "Merging layers…",
            work = { progress ->
                uids.forEachIndexed { k, f ->
                    val lo = store.read(lower.id, f)
                    val up = store.read(upper.id, f)
                    if (lo != null || up != null) {
                        val out = lo?.copyOf() ?: IntArray(store.celSize)
                        if (up != null) for (i in out.indices) out[i] = ColorMath.blend(out[i], up[i])
                        store.put(CelKey(merged.id, f), out)
                    }
                    progress((k + 1f) / uids.size)
                }
            },
            done = {
                val edit = MergeDownEdit(index - 1, lower, upper, merged)
                edit.redo(project, store)
                history.push(edit)
                refreshCels()
            },
        )
    }

    fun moveLayer(from: Int, to: Int) {
        if (from == to || from !in project.layers.indices || to !in project.layers.indices) return
        settle()
        MoveLayerEdit.move(project, from, to)
        history.push(MoveLayerEdit(from, to))
        refreshCels()
    }

    fun setLayerVisible(index: Int, visible: Boolean) {
        val l = project.layers.getOrNull(index) ?: return
        if (l.visible == visible) return
        history.push(LayerPropsEdit(l.id, l.name, l.name, l.visible, visible))
        l.visible = visible
        project.touchLayers()
        refreshCels()
    }

    fun renameLayer(index: Int, name: String) {
        val l = project.layers.getOrNull(index) ?: return
        if (l.name == name || name.isBlank()) return
        history.push(LayerPropsEdit(l.id, l.name, name, l.visible, l.visible))
        l.name = name
    }

    // ---- Frames --------------------------------------------------------------------------

    val frameCount get() = project.frames.size

    fun selectFrame(index: Int) {
        if (index !in project.frames.indices) return
        val uid = project.frames[index].uid
        if (uid == project.currentFrameUid) return
        settle()
        project.currentFrameUid = uid
        refreshCels()
    }

    private fun indexFor(position: FramePosition): Int = when (position) {
        FramePosition.BEFORE -> project.currentFrameIndex
        FramePosition.AFTER -> project.currentFrameIndex + 1
        FramePosition.END -> project.frames.size
    }

    /** Adds a blank frame and makes it current. */
    fun addFrame(position: FramePosition = FramePosition.AFTER) {
        settle()
        val index = indexFor(position)
        val frame = project.newFrame()
        project.insertFrames(index, listOf(frame))
        project.currentFrameUid = frame.uid
        history.push(InsertFramesEdit(index, listOf(frame)))
        refreshCels()
    }

    /** Copies the current frame (all layers) after it, or to the end, and makes it current. */
    fun duplicateFrame(toEnd: Boolean = false) {
        settle()
        val src = project.frames[project.currentFrameIndex]
        val frame = project.newFrame(src.hold)
        for (l in project.layers) store.copy(CelKey(l.id, src.uid), CelKey(l.id, frame.uid))
        val index = indexFor(if (toEnd) FramePosition.END else FramePosition.AFTER)
        project.insertFrames(index, listOf(frame))
        project.currentFrameUid = frame.uid
        history.push(InsertFramesEdit(index, listOf(frame)))
        refreshCels()
    }

    fun deleteFrame() {
        if (project.frames.size <= 1) return
        settle()
        val index = project.currentFrameIndex
        val removed = project.removeFrames(index, 1)
        project.currentFrameUid = project.frames[index.coerceAtMost(project.frames.lastIndex)].uid
        history.push(DeleteFramesEdit(index, removed))
        refreshCels()
    }

    fun moveFrame(from: Int, to: Int) {
        if (from == to || from !in project.frames.indices || to !in project.frames.indices) return
        settle()
        project.moveFrames(from, 1, to)
        history.push(MoveFramesEdit(from, 1, to))
        refreshCels()
    }

    fun setFrameHold(index: Int, hold: Int) {
        val f = project.frames.getOrNull(index) ?: return
        val h = hold.coerceIn(1, MAX_HOLD)
        if (f.hold == h) return
        history.push(SetHoldsEdit(longArrayOf(f.uid), intArrayOf(f.hold), intArrayOf(h)))
        project.setHold(f, h)
    }

    // ---- Frame ranges --------------------------------------------------------------------

    private fun validRange(from: Int, count: Int) =
        count > 0 && from >= 0 && from + count <= project.frames.size

    /** Deletes [count] frames starting at [from] (at least one frame always stays). */
    fun deleteFrames(from: Int, count: Int): Boolean {
        if (!validRange(from, count) || count >= project.frames.size) return false
        settle()
        val removed = project.removeFrames(from, count)
        project.currentFrameUid = project.frames[from.coerceAtMost(project.frames.lastIndex)].uid
        history.push(DeleteFramesEdit(from, removed))
        refreshCels()
        return true
    }

    fun reverseFrames(from: Int, count: Int) {
        if (!validRange(from, count) || count < 2) return
        settle()
        project.reverseFrames(from, count)
        history.push(ReverseFramesEdit(from, count))
        refreshCels()
    }

    /** Moves a range by [delta] frames (negative = earlier). Returns the range's new start. */
    fun moveFrames(from: Int, count: Int, delta: Int): Int {
        if (!validRange(from, count)) return from
        val to = (from + delta).coerceIn(0, project.frames.size - count)
        if (to == from) return from
        settle()
        project.moveFrames(from, count, to)
        history.push(MoveFramesEdit(from, count, to))
        refreshCels()
        return to
    }

    /** Sets the same hold on every frame of a range (one undo step). */
    fun setHolds(from: Int, count: Int, hold: Int) {
        if (!validRange(from, count)) return
        val h = hold.coerceIn(1, MAX_HOLD)
        val frames = project.frames.subList(from, from + count).filter { it.hold != h }
        if (frames.isEmpty()) return
        history.push(
            SetHoldsEdit(
                LongArray(frames.size) { frames[it].uid },
                IntArray(frames.size) { frames[it].hold },
                IntArray(frames.size) { h },
            )
        )
        frames.forEach { project.setHold(it, h) }
    }

    /** Copies a range of frames (every layer) after the range, or to the end (background job). */
    fun duplicateFramesJob(from: Int, count: Int, toEnd: Boolean): BulkJob? {
        if (!validRange(from, count)) return null
        settle()
        val src = project.frames.subList(from, from + count).toList()
        val copies = src.map { project.newFrame(it.hold) }
        val layerIds = project.layers.map { it.id }
        val index = if (toEnd) project.frames.size else from + count
        return BulkJob(
            "Duplicating frames…",
            work = { progress ->
                src.forEachIndexed { k, f ->
                    for (l in layerIds) store.copy(CelKey(l, f.uid), CelKey(l, copies[k].uid))
                    progress((k + 1f) / src.size)
                }
            },
            done = {
                project.insertFrames(index, copies)
                project.currentFrameUid = copies.first().uid
                history.push(InsertFramesEdit(index, copies))
                refreshCels()
            },
        )
    }

    /** A read-only view of a range of frames, for copying to the frame clipboard. */
    fun frameRange(from: Int, count: Int): FrameClipSource? {
        if (!validRange(from, count)) return null
        val uids = LongArray(count) { project.frames[from + it].uid }
        val holds = IntArray(count) { project.frames[from + it].hold }
        val layerIds = project.layers.map { it.id }
        val names = project.layers.map { it.name }
        return object : FrameClipSource {
            override val width = this@EditorEngine.width
            override val height = this@EditorEngine.height
            override val layerNames = names
            override val holds = holds
            override fun cel(layer: Int, frame: Int): IntArray? = store.read(layerIds[layer], uids[frame])
        }
    }

    /**
     * Inserts the clipboard's frames at [index] (background job). Clipboard layers map onto
     * this project's layers by position from the bottom; missing layers are added (beyond the
     * layer limit, extra layers are blended into the top one). A different canvas size is
     * centered (cropped or padded). Everything is one undo step.
     */
    fun pasteFramesJob(clip: FrameClipSource, index: Int): BulkJob? {
        if (clip.holds.isEmpty() || clip.layerNames.isEmpty()) return null
        settle()
        val at = index.coerceIn(0, project.frames.size)
        val newFrames = clip.holds.map { project.newFrame(it) }
        val added = ArrayList<Layer>()
        val targets = LongArray(clip.layerNames.size) { li ->
            when {
                li < project.layers.size -> project.layers[li].id
                project.layers.size + added.size < MAX_LAYERS ->
                    Layer(project.nextLayerId++, clip.layerNames[li], true).also { added += it }.id
                else -> added.lastOrNull()?.id ?: project.layers.last().id
            }
        }
        return BulkJob(
            "Pasting frames…",
            work = { progress ->
                for (fi in newFrames.indices) {
                    val out = HashMap<Long, IntArray>()
                    for (li in clip.layerNames.indices) {
                        val src = clip.cel(li, fi) ?: continue
                        val px = fitCel(src, clip.width, clip.height)
                        val prev = out[targets[li]]
                        if (prev == null) {
                            out[targets[li]] = px
                        } else {
                            for (i in prev.indices) prev[i] = ColorMath.blend(prev[i], px[i])
                        }
                    }
                    out.forEach { (layerId, px) -> store.put(CelKey(layerId, newFrames[fi].uid), px) }
                    progress((fi + 1f) / newFrames.size)
                }
            },
            done = {
                val edits = ArrayList<Edit>()
                for (l in added) {
                    val i = project.layers.size
                    project.layers.add(l)
                    edits += AddLayerEdit(i, l)
                }
                if (added.isNotEmpty()) project.touchLayers()
                project.insertFrames(at, newFrames)
                project.currentFrameUid = newFrames.first().uid
                edits += InsertFramesEdit(at, newFrames)
                history.push(if (edits.size == 1) edits[0] else CompoundEdit(edits))
                refreshCels()
            },
        )
    }

    /** Centers a [sw]×[sh] cel on this canvas (cropping or padding as needed). */
    private fun fitCel(src: IntArray, sw: Int, sh: Int): IntArray {
        if (sw == width && sh == height) return src.copyOf()
        val out = IntArray(width * height)
        val ox = (width - sw) / 2
        val oy = (height - sh) / 2
        for (y in 0 until sh) {
            val ty = y + oy
            if (ty !in 0 until height) continue
            for (x in 0 until sw) {
                val tx = x + ox
                if (tx in 0 until width) out[ty * width + tx] = src[y * sw + x]
            }
        }
        return out
    }

    // ---- Audio ---------------------------------------------------------------------------

    /** Reserves an id for an audio file about to be written (import / recording). */
    fun newAssetId(): Long = project.nextAssetId++

    /** Registers a finished audio file with the project (not an undo step by itself). */
    fun addAsset(asset: AudioAsset) {
        if (project.asset(asset.id) == null) project.assets += asset
    }

    fun addTrack(name: String): SoundTrack {
        val t = SoundTrack(project.nextTrackId++, name)
        val i = project.tracks.size
        project.tracks.add(t)
        project.touchAudio()
        history.push(AddTrackEdit(i, t))
        return t
    }

    /**
     * Places a whole asset at [startMs] on track [trackId], or on a new track called
     * [newTrackName] when [trackId] is null/unknown. One undo step.
     */
    /**
     * Places a sound on a track (a new track named [newTrackName] if [trackId] is null or gone).
     * [inMs] skips the start of the sound (e.g. a recording's count-in). Returns null if nothing
     * of the sound would be left.
     */
    fun addClip(trackId: Long?, newTrackName: String, assetId: Long, startMs: Long, inMs: Long = 0): AudioClip? {
        val asset = project.asset(assetId) ?: return null
        if (inMs < 0 || inMs >= asset.durationMs) return null
        val edits = ArrayList<Edit>()
        val tid = trackId?.takeIf { project.indexOfTrack(it) >= 0 } ?: run {
            val t = SoundTrack(project.nextTrackId++, Names.unique(newTrackName, project.tracks.map { it.name }))
            val i = project.tracks.size
            project.tracks.add(t)
            edits += AddTrackEdit(i, t)
            t.id
        }
        val clip = AudioClip(project.nextClipId++, tid, assetId, startMs.coerceAtLeast(0), inMs, asset.durationMs)
        project.clips.add(clip)
        edits += AddClipEdit(clip)
        project.touchAudio()
        history.push(if (edits.size == 1) edits[0] else CompoundEdit(edits))
        return clip
    }

    fun clip(id: Long): AudioClip? = project.clips.getOrNull(project.indexOfClip(id))

    /** Replaces a clip WITHOUT an undo step (live dragging); finish with [commitClip]. */
    fun setClipLive(c: AudioClip) {
        val i = project.indexOfClip(c.id)
        if (i >= 0) {
            project.clips[i] = c
            project.touchAudio()
        }
    }

    /** Records the change from [original] to the clip's current state as one undo step. */
    fun commitClip(original: AudioClip) {
        val now = clip(original.id) ?: return
        if (now != original) history.push(ChangeClipEdit(original, now))
    }

    /** Moves / trims / re-tracks / re-volumes a clip in one undo step. */
    fun changeClip(new: AudioClip) {
        val i = project.indexOfClip(new.id)
        if (i < 0) return
        val old = project.clips[i]
        if (old == new) return
        project.clips[i] = new
        project.touchAudio()
        history.push(ChangeClipEdit(old, new))
    }

    fun deleteClip(id: Long) {
        val i = project.indexOfClip(id)
        if (i < 0) return
        val c = project.clips.removeAt(i)
        project.touchAudio()
        history.push(RemoveClipEdit(i, c))
    }

    /** Cuts a clip in two at [atMs] (timeline time). */
    fun splitClip(id: Long, atMs: Long): Boolean {
        val i = project.indexOfClip(id)
        if (i < 0) return false
        val c = project.clips[i]
        if (atMs <= c.startMs || atMs >= c.endMs) return false
        val cut = c.inMs + (atMs - c.startMs)
        val left = c.copy(outMs = cut)
        val right = c.copy(id = project.nextClipId++, startMs = atMs, inMs = cut)
        project.clips[i] = left
        project.clips.add(right)
        project.touchAudio()
        history.push(CompoundEdit(listOf(ChangeClipEdit(c, left), AddClipEdit(right))))
        return true
    }

    /** Copies a clip to right after itself on the same track. */
    fun duplicateClip(id: Long): AudioClip? {
        val c = clip(id) ?: return null
        val copy = c.copy(id = project.nextClipId++, startMs = c.endMs)
        project.clips.add(copy)
        project.touchAudio()
        history.push(AddClipEdit(copy))
        return copy
    }

    /** Renames / mutes / changes the volume of a track in one undo step. */
    fun changeTrack(new: SoundTrack) {
        val i = project.indexOfTrack(new.id)
        if (i < 0) return
        val old = project.tracks[i]
        if (old == new) return
        project.tracks[i] = new
        project.touchAudio()
        history.push(ChangeTrackEdit(old, new))
    }

    /** Removes a track and every clip on it (one undo step). */
    fun deleteTrack(id: Long) {
        val i = project.indexOfTrack(id)
        if (i < 0) return
        val t = project.tracks[i]
        val removed = project.clips.withIndex().filter { it.value.trackId == id }.map { it.index to it.value }
        project.clips.removeAll { it.trackId == id }
        project.tracks.removeAt(i)
        project.touchAudio()
        history.push(RemoveTrackEdit(i, t, removed))
    }

    /** Audio assets the project or its undo history refers to (for saving). */
    fun collectAssetRefs(out: MutableSet<Long>) {
        project.clips.forEach { out += it.assetId }
        history.collectAssetRefs(out)
    }

    // ---- History -------------------------------------------------------------------------

    /** Undo. While cells are floating, undo means "put them back" instead. */
    fun undo(): Boolean {
        if (floating != null) {
            cancelFloating()
            return true
        }
        return afterHistory { history.undo(project, store) }
    }

    fun redo(): Boolean {
        if (floating != null) return false
        return afterHistory { history.redo(project, store) }
    }

    private inline fun afterHistory(step: () -> Edit?): Boolean {
        cancelStroke()
        val frameBefore = project.currentFrameUid
        val layersBefore = project.layersVersion
        val framesBefore = project.framesVersion
        val e = step() ?: return false
        val sameView = project.currentFrameUid == frameBefore && project.layersVersion == layersBefore
        when {
            e is PixelEdit && sameView -> {
                refreshCels(full = false) // the cel may have just been created
                e.changedIndices.forEach { markDirty(it) }
            }
            // Audio / hold changes don't touch what's on the canvas.
            e !is PixelEdit && sameView && project.framesVersion == framesBefore -> Unit
            else -> refreshCels()
        }
        return true
    }

    /** Every layer id / frame uid the project or its undo history refers to (for saving). */
    fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {
        project.layers.forEach { layers += it.id }
        project.frames.forEach { frames += it.uid }
        history.collectRefs(layers, frames)
    }

    // ---- Composite tracking --------------------------------------------------------------

    private fun markDirty(i: Int) {
        composite[i] = ColorMath.compositePixel(visCels, i)
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
        ColorMath.compositeCels(visCels, composite)
        fullDirty = false
        dirty.clear()
        return true
    }

    @PublishedApi
    internal fun takeDirtyList(): IntList = dirty

    fun markAllDirty() {
        fullDirty = true
    }

    /** Frame the user sees (for callers that need the Frame object). */
    val currentFrame: Frame get() = project.frames[project.currentFrameIndex]
}

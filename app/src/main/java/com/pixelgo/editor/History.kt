package com.pixelgo.editor

import com.pixelgo.model.Frame
import com.pixelgo.model.Layer
import com.pixelgo.model.PixelProject

/** A reversible change to a project. */
sealed interface Edit {
    fun undo(p: PixelProject)
    fun redo(p: PixelProject)
}

/** Pixel changes on a single layer + frame (one stroke, fill, cut, move...). */
class PixelEdit(
    private val layerId: Long,
    val frame: Int,
    private val indices: IntArray,
    private val before: IntArray,
    private val after: IntArray,
) : Edit {
    val changedIndices: IntArray get() = indices

    override fun undo(p: PixelProject) = apply(p, before)
    override fun redo(p: PixelProject) = apply(p, after)

    private fun apply(p: PixelProject, values: IntArray) {
        val idx = p.indexOfLayer(layerId)
        if (idx < 0 || frame !in p.frames.indices) return
        val px = p.layers[idx].cels[frame]
        for (k in indices.indices) px[indices[k]] = values[k]
        p.activeLayerIndex = idx
        p.currentFrame = frame // show the user what changed
    }
}

class AddLayerEdit(private val index: Int, private val layer: Layer) : Edit {
    override fun undo(p: PixelProject) {
        p.layers.removeAt(index)
        p.activeLayerIndex = (index - 1).coerceIn(0, p.layers.lastIndex)
    }

    override fun redo(p: PixelProject) {
        p.layers.add(index, layer)
        p.activeLayerIndex = index
    }
}

class DeleteLayerEdit(private val index: Int, private val layer: Layer) : Edit {
    override fun undo(p: PixelProject) {
        p.layers.add(index, layer)
        p.activeLayerIndex = index
    }

    override fun redo(p: PixelProject) {
        p.layers.removeAt(index)
        p.activeLayerIndex = index.coerceAtMost(p.layers.lastIndex).coerceAtLeast(0)
    }
}

class MoveLayerEdit(private val from: Int, private val to: Int) : Edit {
    override fun undo(p: PixelProject) = move(p, to, from)
    override fun redo(p: PixelProject) = move(p, from, to)

    companion object {
        fun move(p: PixelProject, from: Int, to: Int) {
            val active = p.layers[p.activeLayerIndex]
            val l = p.layers.removeAt(from)
            p.layers.add(to, l)
            p.activeLayerIndex = p.layers.indexOf(active)
        }
    }
}

/** Merge down across all frames. [lowerBefore]/[lowerAfter] hold one cel per frame. */
class MergeDownEdit(
    private val upperIndex: Int,
    private val upper: Layer,
    private val lowerBefore: List<IntArray>,
    private val lowerAfter: List<IntArray>,
) : Edit {
    override fun undo(p: PixelProject) {
        val lower = p.layers[upperIndex - 1]
        lowerBefore.forEachIndexed { f, cel -> cel.copyInto(lower.cels[f]) }
        p.layers.add(upperIndex, upper)
        p.activeLayerIndex = upperIndex
    }

    override fun redo(p: PixelProject) {
        p.layers.removeAt(upperIndex)
        val lower = p.layers[upperIndex - 1]
        lowerAfter.forEachIndexed { f, cel -> cel.copyInto(lower.cels[f]) }
        p.activeLayerIndex = upperIndex - 1
    }
}

class LayerPropsEdit(
    private val layerId: Long,
    private val oldName: String,
    private val newName: String,
    private val oldVisible: Boolean,
    private val newVisible: Boolean,
) : Edit {
    override fun undo(p: PixelProject) = set(p, oldName, oldVisible)
    override fun redo(p: PixelProject) = set(p, newName, newVisible)

    private fun set(p: PixelProject, name: String, visible: Boolean) {
        val l = p.layers.getOrNull(p.indexOfLayer(layerId)) ?: return
        l.name = name
        l.visible = visible
    }
}

// ---- Frames ------------------------------------------------------------------------------

/** Inserting a frame at [index]; [cels] maps layer id -> that layer's cel for the frame. */
class AddFrameEdit(
    private val index: Int,
    private val frame: Frame,
    private val cels: Map<Long, IntArray>,
) : Edit {
    override fun undo(p: PixelProject) = FrameOps.remove(p, index)
    override fun redo(p: PixelProject) = FrameOps.insert(p, index, frame, cels)
}

class DeleteFrameEdit(
    private val index: Int,
    private val frame: Frame,
    private val cels: Map<Long, IntArray>,
) : Edit {
    override fun undo(p: PixelProject) = FrameOps.insert(p, index, frame, cels)
    override fun redo(p: PixelProject) = FrameOps.remove(p, index)
}

class MoveFrameEdit(private val from: Int, private val to: Int) : Edit {
    override fun undo(p: PixelProject) = FrameOps.move(p, to, from)
    override fun redo(p: PixelProject) = FrameOps.move(p, from, to)
}

class FrameHoldEdit(private val index: Int, private val old: Int, private val new: Int) : Edit {
    override fun undo(p: PixelProject) { p.frames[index].hold = old; p.currentFrame = index }
    override fun redo(p: PixelProject) { p.frames[index].hold = new; p.currentFrame = index }
}

object FrameOps {
    fun insert(p: PixelProject, index: Int, frame: Frame, cels: Map<Long, IntArray>) {
        p.frames.add(index, frame)
        for (l in p.layers) l.cels.add(index, cels[l.id] ?: IntArray(p.size))
        p.currentFrame = index
    }

    fun remove(p: PixelProject, index: Int) {
        p.frames.removeAt(index)
        for (l in p.layers) l.cels.removeAt(index)
        p.currentFrame = index.coerceAtMost(p.frames.lastIndex)
    }

    fun move(p: PixelProject, from: Int, to: Int) {
        p.frames.add(to, p.frames.removeAt(from))
        for (l in p.layers) l.cels.add(to, l.cels.removeAt(from))
        p.currentFrame = to
    }

    fun celsAt(p: PixelProject, index: Int): Map<Long, IntArray> =
        p.layers.associate { it.id to it.cels[index] }
}

/** Undo / redo stacks with a size cap. */
class History(private val limit: Int = 100) {
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Records an edit that has already been applied. */
    fun push(edit: Edit) {
        undoStack.addLast(edit)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(p: PixelProject): Edit? {
        val e = undoStack.removeLastOrNull() ?: return null
        e.undo(p)
        redoStack.addLast(e)
        return e
    }

    fun redo(p: PixelProject): Edit? {
        val e = redoStack.removeLastOrNull() ?: return null
        e.redo(p)
        undoStack.addLast(e)
        return e
    }
}

package com.pixelgo.editor

import com.pixelgo.model.AudioClip
import com.pixelgo.model.Frame
import com.pixelgo.model.Layer
import com.pixelgo.model.PixelProject
import com.pixelgo.model.SoundTrack

/**
 * A reversible change to a project. Edits are pushed AFTER the change has been applied.
 *
 * Pixel data of deleted layers/frames is not copied into edits: their cels stay in the
 * [CelStore] while any undo/redo entry still refers to them ([collectRefs]); saving only
 * removes cels nothing refers to any more.
 */
sealed interface Edit {
    fun undo(p: PixelProject, s: CelStore)
    fun redo(p: PixelProject, s: CelStore)

    /** Adds the layer ids / frame uids this edit may bring back. */
    fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {}

    /** Adds the audio asset ids this edit may bring back. */
    fun collectAssetRefs(assets: MutableSet<Long>) {}
}

/** Pixel changes on one cel (one stroke, fill, cut, move...). */
class PixelEdit(
    val layerId: Long,
    val frameUid: Long,
    private val indices: IntArray,
    private val before: IntArray,
    private val after: IntArray,
) : Edit {
    val changedIndices: IntArray get() = indices

    override fun undo(p: PixelProject, s: CelStore) = apply(p, s, before)
    override fun redo(p: PixelProject, s: CelStore) = apply(p, s, after)

    private fun apply(p: PixelProject, s: CelStore, values: IntArray) {
        val li = p.indexOfLayer(layerId)
        if (li < 0 || p.indexOfFrame(frameUid) < 0) return
        val px = s.edit(layerId, frameUid)
        for (k in indices.indices) px[indices[k]] = values[k]
        // Show the user what changed.
        p.activeLayerIndex = li
        p.currentFrameUid = frameUid
    }

    override fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {
        layers += layerId
        frames += frameUid
    }
}

// ---- Layers ---------------------------------------------------------------------------------

class AddLayerEdit(private val index: Int, private val layer: Layer) : Edit {
    override fun undo(p: PixelProject, s: CelStore) {
        p.layers.removeAt(index)
        p.activeLayerIndex = (index - 1).coerceIn(0, p.layers.lastIndex)
        p.touchLayers()
    }

    override fun redo(p: PixelProject, s: CelStore) {
        p.layers.add(index, layer)
        p.activeLayerIndex = index
        p.touchLayers()
    }

    override fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {
        layers += layer.id
    }
}

class DeleteLayerEdit(private val index: Int, private val layer: Layer) : Edit {
    override fun undo(p: PixelProject, s: CelStore) {
        p.layers.add(index, layer)
        p.activeLayerIndex = index
        p.touchLayers()
    }

    override fun redo(p: PixelProject, s: CelStore) {
        p.layers.removeAt(index)
        p.activeLayerIndex = index.coerceAtMost(p.layers.lastIndex).coerceAtLeast(0)
        p.touchLayers()
    }

    override fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {
        layers += layer.id
    }
}

class MoveLayerEdit(private val from: Int, private val to: Int) : Edit {
    override fun undo(p: PixelProject, s: CelStore) = move(p, to, from)
    override fun redo(p: PixelProject, s: CelStore) = move(p, from, to)

    companion object {
        fun move(p: PixelProject, from: Int, to: Int) {
            val active = p.layers[p.activeLayerIndex]
            val l = p.layers.removeAt(from)
            p.layers.add(to, l)
            p.activeLayerIndex = p.layers.indexOf(active)
            p.touchLayers()
        }
    }
}

/**
 * Merge down = [lower] and [upper] are replaced by a brand-new layer [merged] (its cels were
 * written by the merge job). Undo just swaps the originals back, so no pixel copies are kept.
 */
class MergeDownEdit(
    private val lowerIndex: Int,
    private val lower: Layer,
    private val upper: Layer,
    private val merged: Layer,
) : Edit {
    override fun undo(p: PixelProject, s: CelStore) {
        p.layers.removeAt(lowerIndex)
        p.layers.add(lowerIndex, lower)
        p.layers.add(lowerIndex + 1, upper)
        p.activeLayerIndex = lowerIndex + 1
        p.touchLayers()
    }

    override fun redo(p: PixelProject, s: CelStore) {
        p.layers.removeAt(lowerIndex + 1)
        p.layers.removeAt(lowerIndex)
        p.layers.add(lowerIndex, merged)
        p.activeLayerIndex = lowerIndex
        p.touchLayers()
    }

    override fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {
        layers += lower.id
        layers += upper.id
        layers += merged.id
    }
}

class LayerPropsEdit(
    private val layerId: Long,
    private val oldName: String,
    private val newName: String,
    private val oldVisible: Boolean,
    private val newVisible: Boolean,
) : Edit {
    override fun undo(p: PixelProject, s: CelStore) = set(p, oldName, oldVisible)
    override fun redo(p: PixelProject, s: CelStore) = set(p, newName, newVisible)

    private fun set(p: PixelProject, name: String, visible: Boolean) {
        val l = p.layers.getOrNull(p.indexOfLayer(layerId)) ?: return
        l.name = name
        if (l.visible != visible) {
            l.visible = visible
            p.touchLayers()
        }
    }

    override fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {
        layers += layerId
    }
}

// ---- Frames ---------------------------------------------------------------------------------

/** [frames] were inserted at [index] (their cels already exist in the store). */
class InsertFramesEdit(private val index: Int, private val frames: List<Frame>) : Edit {
    override fun undo(p: PixelProject, s: CelStore) {
        p.removeFrames(index, frames.size)
        p.currentFrameUid = p.frames[(index - 1).coerceIn(0, p.frames.lastIndex)].uid
    }

    override fun redo(p: PixelProject, s: CelStore) {
        p.insertFrames(index, frames)
        p.currentFrameUid = frames.first().uid
    }

    override fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {
        this.frames.forEach { frames += it.uid }
    }
}

/** [frames] were removed from [index]. Their cels stay in the store for undo. */
class DeleteFramesEdit(private val index: Int, private val frames: List<Frame>) : Edit {
    override fun undo(p: PixelProject, s: CelStore) {
        p.insertFrames(index, frames)
        p.currentFrameUid = frames.first().uid
    }

    override fun redo(p: PixelProject, s: CelStore) {
        p.removeFrames(index, frames.size)
        p.currentFrameUid = p.frames[index.coerceAtMost(p.frames.lastIndex)].uid
    }

    override fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {
        this.frames.forEach { frames += it.uid }
    }
}

/** [count] frames moved from [from] so the first sits at [to] (index after removal). */
class MoveFramesEdit(private val from: Int, private val count: Int, private val to: Int) : Edit {
    override fun undo(p: PixelProject, s: CelStore) = p.moveFrames(to, count, from)
    override fun redo(p: PixelProject, s: CelStore) = p.moveFrames(from, count, to)
}

class ReverseFramesEdit(private val from: Int, private val count: Int) : Edit {
    override fun undo(p: PixelProject, s: CelStore) = p.reverseFrames(from, count)
    override fun redo(p: PixelProject, s: CelStore) = p.reverseFrames(from, count)
}

class SetHoldsEdit(
    private val uids: LongArray,
    private val old: IntArray,
    private val new: IntArray,
) : Edit {
    override fun undo(p: PixelProject, s: CelStore) = apply(p, old)
    override fun redo(p: PixelProject, s: CelStore) = apply(p, new)

    private fun apply(p: PixelProject, holds: IntArray) {
        uids.forEachIndexed { k, uid ->
            val i = p.indexOfFrame(uid)
            if (i >= 0) p.setHold(p.frames[i], holds[k])
        }
        if (uids.isNotEmpty() && p.indexOfFrame(uids[0]) >= 0) p.currentFrameUid = uids[0]
    }
}

/** Several edits that undo/redo as one step. */
class CompoundEdit(private val edits: List<Edit>) : Edit {
    override fun undo(p: PixelProject, s: CelStore) = edits.asReversed().forEach { it.undo(p, s) }
    override fun redo(p: PixelProject, s: CelStore) = edits.forEach { it.redo(p, s) }
    override fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) =
        edits.forEach { it.collectRefs(layers, frames) }
    override fun collectAssetRefs(assets: MutableSet<Long>) = edits.forEach { it.collectAssetRefs(assets) }
}

// ---- Audio ----------------------------------------------------------------------------------

class AddClipEdit(private val clip: AudioClip) : Edit {
    override fun undo(p: PixelProject, s: CelStore) {
        p.clips.removeAll { it.id == clip.id }
        p.touchAudio()
    }

    override fun redo(p: PixelProject, s: CelStore) {
        p.clips.add(clip)
        p.touchAudio()
    }

    override fun collectAssetRefs(assets: MutableSet<Long>) {
        assets += clip.assetId
    }
}

class RemoveClipEdit(private val index: Int, private val clip: AudioClip) : Edit {
    override fun undo(p: PixelProject, s: CelStore) {
        p.clips.add(index.coerceIn(0, p.clips.size), clip)
        p.touchAudio()
    }

    override fun redo(p: PixelProject, s: CelStore) {
        p.clips.removeAll { it.id == clip.id }
        p.touchAudio()
    }

    override fun collectAssetRefs(assets: MutableSet<Long>) {
        assets += clip.assetId
    }
}

/** A clip moved, trimmed, re-tracked or changed volume. */
class ChangeClipEdit(private val old: AudioClip, private val new: AudioClip) : Edit {
    override fun undo(p: PixelProject, s: CelStore) = put(p, old)
    override fun redo(p: PixelProject, s: CelStore) = put(p, new)

    private fun put(p: PixelProject, c: AudioClip) {
        val i = p.indexOfClip(c.id)
        if (i >= 0) p.clips[i] = c
        p.touchAudio()
    }

    override fun collectAssetRefs(assets: MutableSet<Long>) {
        assets += old.assetId
    }
}

class AddTrackEdit(private val index: Int, private val track: SoundTrack) : Edit {
    override fun undo(p: PixelProject, s: CelStore) {
        p.tracks.removeAll { it.id == track.id }
        p.touchAudio()
    }

    override fun redo(p: PixelProject, s: CelStore) {
        p.tracks.add(index.coerceIn(0, p.tracks.size), track)
        p.touchAudio()
    }
}

/** A track and all its clips were removed. */
class RemoveTrackEdit(
    private val index: Int,
    private val track: SoundTrack,
    private val clips: List<Pair<Int, AudioClip>>,
) : Edit {
    override fun undo(p: PixelProject, s: CelStore) {
        p.tracks.add(index.coerceIn(0, p.tracks.size), track)
        clips.forEach { (i, c) -> p.clips.add(i.coerceIn(0, p.clips.size), c) }
        p.touchAudio()
    }

    override fun redo(p: PixelProject, s: CelStore) {
        p.tracks.removeAll { it.id == track.id }
        p.clips.removeAll { it.trackId == track.id }
        p.touchAudio()
    }

    override fun collectAssetRefs(assets: MutableSet<Long>) {
        clips.forEach { assets += it.second.assetId }
    }
}

class ChangeTrackEdit(private val old: SoundTrack, private val new: SoundTrack) : Edit {
    override fun undo(p: PixelProject, s: CelStore) = put(p, old)
    override fun redo(p: PixelProject, s: CelStore) = put(p, new)

    private fun put(p: PixelProject, t: SoundTrack) {
        val i = p.indexOfTrack(t.id)
        if (i >= 0) p.tracks[i] = t
        p.touchAudio()
    }
}

/** Undo / redo stacks with a size cap. Main thread only. */
class History(private val limit: Int = 100) {
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Edits collected since [beginGroup], recorded as one step by [endGroup]. */
    private var group: MutableList<Edit>? = null

    /** From now on, edits are collected into one undo step (until [endGroup] / [cancelGroup]). */
    fun beginGroup() {
        if (group == null) group = ArrayList()
    }

    fun endGroup() {
        val g = group ?: return
        group = null
        if (g.isNotEmpty()) push(if (g.size == 1) g[0] else CompoundEdit(g))
    }

    /** Takes back everything done since [beginGroup] and forgets it. */
    fun cancelGroup(p: PixelProject, s: CelStore) {
        val g = group ?: return
        group = null
        g.asReversed().forEach { it.undo(p, s) }
    }

    /** Records an edit that has already been applied. */
    fun push(edit: Edit) {
        group?.let {
            it += edit
            return
        }
        undoStack.addLast(edit)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(p: PixelProject, s: CelStore): Edit? {
        val e = undoStack.removeLastOrNull() ?: return null
        e.undo(p, s)
        redoStack.addLast(e)
        return e
    }

    fun redo(p: PixelProject, s: CelStore): Edit? {
        val e = redoStack.removeLastOrNull() ?: return null
        e.redo(p, s)
        undoStack.addLast(e)
        return e
    }

    /** Every layer id / frame uid that undo or redo could bring back. */
    fun collectRefs(layers: MutableSet<Long>, frames: MutableSet<Long>) {
        undoStack.forEach { it.collectRefs(layers, frames) }
        redoStack.forEach { it.collectRefs(layers, frames) }
        group?.forEach { it.collectRefs(layers, frames) }
    }

    /** Every audio asset id that undo or redo could bring back. */
    fun collectAssetRefs(assets: MutableSet<Long>) {
        undoStack.forEach { it.collectAssetRefs(assets) }
        redoStack.forEach { it.collectAssetRefs(assets) }
        group?.forEach { it.collectAssetRefs(assets) }
    }
}

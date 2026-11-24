package com.pixelgo.editor

import com.pixelgo.model.PixelProject

/**
 * An immutable copy of everything needed to render an animation (frame order, holds, fps,
 * visible layers) plus the store holding the pixels. Background work (export, playback)
 * uses this instead of the live project, which the UI may change at any time.
 */
class AnimationSnapshot(
    val width: Int,
    val height: Int,
    val fps: Int,
    val frameUids: LongArray,
    val holds: IntArray,
    val layerIds: LongArray,
    val store: CelStore,
) {
    val frameCount: Int get() = frameUids.size
    val timing: Timing by lazy { Timing(holds) }

    /** Frame [i] flattened (visible layers, transparent background). */
    fun render(i: Int): IntArray = ColorMath.compositeFrame(layerIds, store, frameUids[i], IntArray(width * height))

    companion object {
        fun of(p: PixelProject, store: CelStore) = AnimationSnapshot(
            p.width, p.height, p.fps, p.frameUids(),
            IntArray(p.frames.size) { p.frames[it].hold }, p.visibleLayerIds(), store,
        )
    }
}

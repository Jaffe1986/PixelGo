package com.pixelgo.editor

/**
 * A run of copied frames: [layerNames] bottom to top, one [holds] entry per frame, and each
 * cel readable on demand (null = empty). Used for the cross-project frame clipboard.
 * [cel] may do disk IO: call it off the main thread.
 */
interface FrameClipSource {
    val width: Int
    val height: Int
    val layerNames: List<String>
    val holds: IntArray
    fun cel(layer: Int, frame: Int): IntArray?
}

package com.pixelgo.model

/** Maximum width/height of a canvas, in art pixels. */
const val MAX_CANVAS_SIZE = 256

/** Maximum number of layers in one project. */
const val MAX_LAYERS = 32

/** Maximum number of animation frames in one project. */
const val MAX_FRAMES = 128

/** Cap on total stored pixels (layers × frames × width × height) to keep memory sane (~96 MB). */
const val MAX_TOTAL_CELLS = 24_000_000L

/** Shared pointer to the frame being viewed/edited; every layer of a project uses the same one. */
class FrameCursor(var index: Int = 0)

/**
 * One layer. It has one pixel buffer ("cel") per animation frame; [pixels] is the cel for the
 * current frame. Each entry is a non-premultiplied ARGB color; 0 means fully transparent.
 * [id] is stable for the lifetime of the project so undo history can refer to layers safely.
 */
class Layer(
    val id: Long,
    var name: String,
    var visible: Boolean,
    val cels: MutableList<IntArray>,
    private val cursor: FrameCursor,
) {
    /** Pixels of this layer on the current frame. */
    val pixels: IntArray get() = cels[cursor.index]

    fun deepCopy(newId: Long = id, newName: String = name, newCursor: FrameCursor = cursor) =
        Layer(newId, newName, visible, cels.mapTo(mutableListOf()) { it.copyOf() }, newCursor)
}

/** Per-frame animation settings. [hold] = how many ticks (1/fps) the frame stays on screen. */
class Frame(var hold: Int = 1)

/**
 * A pixel-art project. [layers] is ordered bottom (index 0) to top (last index).
 * Every layer has exactly [frames].size cels.
 */
class PixelProject(
    val id: String,
    var name: String,
    val width: Int,
    val height: Int,
    val cursor: FrameCursor,
    val layers: MutableList<Layer>,
    val frames: MutableList<Frame>,
    var activeLayerIndex: Int = 0,
    var nextLayerId: Long = (layers.maxOfOrNull { it.id } ?: 0L) + 1,
    var fps: Int = 8,
) {
    val size: Int get() = width * height

    val activeLayer: Layer? get() = layers.getOrNull(activeLayerIndex)

    /** Frame being viewed/edited. */
    var currentFrame: Int
        get() = cursor.index
        set(value) {
            cursor.index = value.coerceIn(0, frames.lastIndex)
        }

    fun indexOfLayer(id: Long): Int = layers.indexOfFirst { it.id == id }

    /** A new blank layer with a cel for every frame (not yet added to [layers]). */
    fun newLayer(name: String): Layer =
        Layer(nextLayerId++, name, true, MutableList(frames.size) { IntArray(size) }, cursor)

    fun totalCells(layerCount: Int = layers.size, frameCount: Int = frames.size): Long =
        layerCount.toLong() * frameCount * size

    fun deepCopy(newId: String = id, newName: String = name): PixelProject {
        val c = FrameCursor(cursor.index)
        return PixelProject(
            newId, newName, width, height, c,
            layers.mapTo(mutableListOf()) { it.deepCopy(newCursor = c) },
            frames.mapTo(mutableListOf()) { Frame(it.hold) },
            activeLayerIndex, nextLayerId, fps,
        )
    }

    companion object {
        fun create(id: String, name: String, width: Int, height: Int): PixelProject {
            val p = PixelProject(id, name, width, height, FrameCursor(), mutableListOf(), mutableListOf(Frame()), 0, 1)
            p.layers.add(p.newLayer("Layer 1"))
            return p
        }
    }
}

/** A named, user-editable list of colors (ARGB ints). */
data class Palette(
    val id: String,
    val name: String,
    val colors: List<Int>,
)

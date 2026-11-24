package com.pixelgo.editor

/**
 * Copied cells. [mask] marks which cells are part of the selection (it need not be a
 * rectangle). [srcX]/[srcY] is where the top-left came from, used as the default paste spot.
 */
class Clip(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val mask: BooleanArray,
    val srcX: Int,
    val srcY: Int,
) {
    val cellCount: Int get() = mask.count { it }
}

/**
 * Cells floating above the canvas while being moved or pasted, on layer [layerId] of frame
 * [frameUid]. [lifted] holds the original values of cells cleared by a move (null for a paste).
 */
class Floating(
    val clip: Clip,
    var x: Int,
    var y: Int,
    val layerId: Long,
    val frameUid: Long,
    val lifted: Map<Int, Int>?,
    val previousSelection: BooleanArray?,
)

object SelectionMath {
    /** Bounding box (left, top, right exclusive, bottom exclusive) of the set cells, or null. */
    fun bounds(mask: BooleanArray, w: Int, h: Int): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (mask[y * w + x]) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    /**
     * Outline of the selected region as line segments in cell-edge coordinates:
     * groups of (x0, y0, x1, y1). Adjacent edges along a row/column are merged.
     */
    fun edges(mask: BooleanArray, w: Int, h: Int): IntArray {
        val out = IntList(64)
        fun at(x: Int, y: Int) = x in 0 until w && y in 0 until h && mask[y * w + x]
        // Horizontal edges on line y (between row y-1 and row y)
        for (y in 0..h) {
            var runStart = -1
            for (x in 0..w) {
                val edge = x < w && at(x, y - 1) != at(x, y)
                if (edge && runStart < 0) runStart = x
                if (!edge && runStart >= 0) {
                    out.add(runStart); out.add(y); out.add(x); out.add(y)
                    runStart = -1
                }
            }
        }
        // Vertical edges on line x (between column x-1 and column x)
        for (x in 0..w) {
            var runStart = -1
            for (y in 0..h) {
                val edge = y < h && at(x - 1, y) != at(x, y)
                if (edge && runStart < 0) runStart = y
                if (!edge && runStart >= 0) {
                    out.add(x); out.add(runStart); out.add(x); out.add(y)
                    runStart = -1
                }
            }
        }
        return out.toArray()
    }
}

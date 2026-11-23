package com.pixelgo.editor

import kotlin.math.abs

object Raster {
    /** Bresenham line from (x0,y0) to (x1,y1) inclusive; calls [plot] for every point. */
    inline fun line(x0: Int, y0: Int, x1: Int, y1: Int, plot: (Int, Int) -> Unit) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            plot(x, y)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /**
     * Calls [action] for (x,y) and its mirror images. [mirrorX] mirrors left/right (across the
     * vertical center line); [mirrorY] mirrors top/bottom. Duplicate points are skipped.
     */
    inline fun forEachMirrored(
        x: Int, y: Int, width: Int, height: Int,
        mirrorX: Boolean, mirrorY: Boolean,
        action: (Int, Int) -> Unit,
    ) {
        action(x, y)
        val mx = width - 1 - x
        val my = height - 1 - y
        if (mirrorX && mx != x) action(mx, y)
        if (mirrorY && my != y) action(x, my)
        if (mirrorX && mirrorY && mx != x && my != y) action(mx, my)
    }

    /**
     * Scanline flood fill of the 4-connected region containing (x,y) with [newColor].
     * Returns the indices that changed (all of which previously held the seed color).
     */
    fun floodFill(pixels: IntArray, width: Int, height: Int, x: Int, y: Int, newColor: Int): IntArray {
        if (x !in 0 until width || y !in 0 until height) return IntArray(0)
        val target = pixels[y * width + x]
        if (target == newColor) return IntArray(0)
        val changed = IntList()
        val stack = IntList()
        stack.add(y * width + x)
        while (stack.size > 0) {
            val idx = stack.removeLast()
            val py = idx / width
            var lx = idx % width
            if (pixels[py * width + lx] != target) continue
            while (lx > 0 && pixels[py * width + lx - 1] == target) lx--
            var rx = lx
            var spanUp = false
            var spanDown = false
            while (rx < width && pixels[py * width + rx] == target) {
                val i = py * width + rx
                pixels[i] = newColor
                changed.add(i)
                if (py > 0) {
                    val up = pixels[i - width] == target
                    if (up && !spanUp) {
                        stack.add(i - width); spanUp = true
                    } else if (!up) spanUp = false
                }
                if (py < height - 1) {
                    val down = pixels[i + width] == target
                    if (down && !spanDown) {
                        stack.add(i + width); spanDown = true
                    } else if (!down) spanDown = false
                }
                rx++
            }
        }
        return changed.toArray()
    }

    /** Nearest-neighbour integer upscale. Alpha is preserved exactly. */
    fun scaleNearest(src: IntArray, width: Int, height: Int, factor: Int): IntArray {
        if (factor == 1) return src.copyOf()
        val ow = width * factor
        val out = IntArray(ow * height * factor)
        val row = IntArray(ow)
        for (y in 0 until height) {
            scaledRow(src, width, y, factor, row)
            for (r in 0 until factor) {
                System.arraycopy(row, 0, out, (y * factor + r) * ow, ow)
            }
        }
        return out
    }

    /** Writes source row [y] repeated horizontally [factor] times into [out] (size width*factor). */
    fun scaledRow(src: IntArray, width: Int, y: Int, factor: Int, out: IntArray) {
        for (x in 0 until width) {
            out.fill(src[y * width + x], x * factor, x * factor + factor)
        }
    }
}

/** Minimal growable int list (avoids boxing). */
class IntList(capacity: Int = 64) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
    fun removeLast(): Int = data[--size]
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
}

package com.pixelgo.editor

import com.pixelgo.model.PixelProject

object ColorMath {
    /** Source-over blend of two non-premultiplied ARGB colors. */
    fun blend(dst: Int, src: Int): Int {
        val sa = src ushr 24
        if (sa == 255) return src
        if (sa == 0) return dst
        val da = dst ushr 24
        if (da == 0) return src
        // Weights scaled by 255 to stay in integer math.
        val srcW = sa * 255
        val dstW = da * (255 - sa)
        val total = srcW + dstW
        val r = (((src shr 16) and 0xFF) * srcW + ((dst shr 16) and 0xFF) * dstW) / total
        val g = (((src shr 8) and 0xFF) * srcW + ((dst shr 8) and 0xFF) * dstW) / total
        val b = ((src and 0xFF) * srcW + (dst and 0xFF) * dstW) / total
        val a = (total + 127) / 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Composite color of pixel [i] across all visible layers. */
    fun compositeAt(project: PixelProject, i: Int): Int {
        var c = 0
        for (layer in project.layers) {
            if (layer.visible) c = blend(c, layer.pixels[i])
        }
        return c
    }

    /** Composite of the current frame. */
    fun compositeAll(project: PixelProject, out: IntArray = IntArray(project.size)): IntArray =
        compositeFrame(project, project.currentFrame, out)

    /** Composite of visible layers on [frame]. */
    fun compositeFrame(project: PixelProject, frame: Int, out: IntArray = IntArray(project.size)): IntArray {
        out.fill(0)
        for (layer in project.layers) {
            if (!layer.visible) continue
            val px = layer.cels[frame]
            for (i in out.indices) {
                val s = px[i]
                if (s != 0) out[i] = blend(out[i], s)
            }
        }
        return out
    }

    fun toHex(color: Int): String = "#%08X".format(color)

    /** Parses #RRGGBB or #AARRGGBB (leading # optional). Returns null if invalid. */
    fun parseHex(text: String): Int? {
        val s = text.trim().removePrefix("#")
        if (s.length != 6 && s.length != 8) return null
        val v = s.toLongOrNull(16) ?: return null
        return if (s.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }
}

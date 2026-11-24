package com.pixelgo.data.video

import com.pixelgo.editor.ColorMath

/**
 * RGB → YUV with BT.709 coefficients, limited ("TV") range — what players assume for HD video.
 * Integer weights (×256). Each colour row sums to exactly 0 so whites/greys stay neutral.
 */
object Yuv709 {
    fun y(r: Int, g: Int, b: Int): Int = ((47 * r + 157 * g + 16 * b + 128) shr 8) + 16
    fun u(r: Int, g: Int, b: Int): Int = ((-26 * r - 86 * g + 112 * b + 128) shr 8) + 128
    fun v(r: Int, g: Int, b: Int): Int = ((112 * r - 102 * g - 10 * b + 128) shr 8) + 128
}

/**
 * One art frame converted to ready-made video rows. Because the scale and offsets are even,
 * each output row is a copy of one of these rows, so filling the encoder's buffers is mostly
 * bulk copies.
 *
 * @param pixels the art frame (ARGB, may be transparent); flattened onto [background]
 */
class YuvFrame(pixels: IntArray, artW: Int, artH: Int, background: Int, val layout: VideoLayout) {
    private val w = layout.width
    private val cw = layout.width / 2

    private val bgY: Byte
    private val bgU: Byte
    private val bgV: Byte

    /** One luma row per art row (full output width, background at the sides). */
    val yRows: Array<ByteArray>
    val uRows: Array<ByteArray>
    val vRows: Array<ByteArray>
    val bgYRow: ByteArray
    val bgURow: ByteArray
    val bgVRow: ByteArray

    init {
        val bg = background or (0xFF shl 24)
        val br = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bb = bg and 0xFF
        bgY = Yuv709.y(br, bgG, bb).toByte()
        bgU = Yuv709.u(br, bgG, bb).toByte()
        bgV = Yuv709.v(br, bgG, bb).toByte()
        bgYRow = ByteArray(w) { bgY }
        bgURow = ByteArray(cw) { bgU }
        bgVRow = ByteArray(cw) { bgV }
        val s = layout.scale
        val half = s / 2
        yRows = Array(artH) { bgYRow.copyOf() }
        uRows = Array(artH) { bgURow.copyOf() }
        vRows = Array(artH) { bgVRow.copyOf() }
        for (ay in 0 until artH) {
            val yr = yRows[ay]
            val ur = uRows[ay]
            val vr = vRows[ay]
            for (ax in 0 until artW) {
                val c = ColorMath.blend(bg, pixels[ay * artW + ax])
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val x0 = layout.offsetX + ax * s
                yr.fill(Yuv709.y(r, g, b).toByte(), x0, x0 + s)
                val c0 = x0 / 2
                ur.fill(Yuv709.u(r, g, b).toByte(), c0, c0 + half)
                vr.fill(Yuv709.v(r, g, b).toByte(), c0, c0 + half)
            }
        }
    }

    /** Luma row for output row [y]. */
    fun yRow(y: Int): ByteArray {
        val ay = (y - layout.offsetY)
        return if (ay < 0 || ay >= yRows.size * layout.scale) bgYRow else yRows[ay / layout.scale]
    }

    /** Chroma rows (U, V) for chroma row [cy] (= output rows 2cy and 2cy+1). */
    fun uRow(cy: Int): ByteArray {
        val ay = 2 * cy - layout.offsetY
        return if (ay < 0 || ay >= uRows.size * layout.scale) bgURow else uRows[ay / layout.scale]
    }

    fun vRow(cy: Int): ByteArray {
        val ay = 2 * cy - layout.offsetY
        return if (ay < 0 || ay >= vRows.size * layout.scale) bgVRow else vRows[ay / layout.scale]
    }
}

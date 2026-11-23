package com.pixelgo.data

import java.io.OutputStream

/**
 * Minimal animated-GIF (GIF89a) writer for pixel art.
 *
 * - One global palette of up to 255 colors + 1 transparent index.
 * - Pixels with alpha < 128 become transparent; others are drawn fully opaque
 *   (GIF has no partial transparency).
 * - If the art has more than 255 distinct colors, the 255 most common are kept and the rest
 *   map to the nearest of those.
 * - Frames are scaled up with nearest-neighbour by [scale] and loop forever.
 */
object GifEncoder {

    /**
     * @param frames one ARGB array (width*height) per frame, at 1x
     * @param delaysCs per-frame display time in 1/100 s
     */
    fun encode(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        scale: Int,
        delaysCs: List<Int>,
        out: OutputStream,
    ) {
        require(frames.isNotEmpty() && frames.size == delaysCs.size)
        val palette = buildPalette(frames)
        val transparent = palette.size          // index after the real colors
        var bits = 1
        while ((1 shl bits) < palette.size + 1) bits++
        val tableSize = 1 shl bits
        val ow = width * scale
        val oh = height * scale

        // Header + logical screen descriptor + global color table
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        le16(out, ow); le16(out, oh)
        out.write(0x80 or (7 shl 4) or (bits - 1)) // global table, 8-bit color res, table size
        out.write(transparent)                     // background color index
        out.write(0)                               // pixel aspect ratio
        for (i in 0 until tableSize) {
            val c = if (i < palette.size) palette[i] else 0
            out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF)
        }
        // Loop forever (NETSCAPE2.0 application extension)
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))

        val lookup = HashMap<Int, Int>(palette.size * 2)
        palette.forEachIndexed { i, c -> lookup[c] = i }
        val small = ByteArray(width * height)
        val big = ByteArray(ow * oh)

        frames.forEachIndexed { fi, px ->
            // Index at 1x
            for (i in px.indices) {
                val c = px[i]
                small[i] = if ((c ushr 24) < 128) transparent.toByte()
                else (lookup.getOrPut(c and 0xFFFFFF) { nearest(palette, c) }).toByte()
            }
            // Scale indices
            for (y in 0 until oh) {
                val sy = y / scale
                for (x in 0 until ow) big[y * ow + x] = small[sy * width + x / scale]
            }
            // Graphic control extension: disposal 2 (restore to background) + transparency
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, ((2 shl 2) or 1).toByte()))
            le16(out, delaysCs[fi].coerceIn(2, 65535))
            out.write(transparent)
            out.write(0)
            // Image descriptor (no local table)
            out.write(0x2C)
            le16(out, 0); le16(out, 0); le16(out, ow); le16(out, oh)
            out.write(0)
            val minCode = maxOf(2, bits)
            out.write(minCode)
            lzw(big, minCode, out)
        }
        out.write(0x3B)
        out.flush()
    }

    /** Opaque RGB colors (alpha >= 128), most frequent first, at most 255. */
    fun buildPalette(frames: List<IntArray>): IntArray {
        val counts = HashMap<Int, Int>()
        for (px in frames) for (c in px) {
            if ((c ushr 24) >= 128) {
                val rgb = c and 0xFFFFFF
                counts[rgb] = (counts[rgb] ?: 0) + 1
            }
        }
        val sorted = counts.entries.sortedByDescending { it.value }.map { it.key }
        return if (sorted.isEmpty()) intArrayOf(0) else sorted.take(255).toIntArray()
    }

    private fun nearest(palette: IntArray, c: Int): Int {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        var best = 0; var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val p = palette[i]
            val dr = ((p shr 16) and 0xFF) - r
            val dg = ((p shr 8) and 0xFF) - g
            val db = (p and 0xFF) - b
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun le16(out: OutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }

    /** GIF-flavoured variable-width LZW, written as 255-byte sub-blocks. */
    private fun lzw(indices: ByteArray, minCodeSize: Int, out: OutputStream) {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var maxCode = eoi
        val dict = HashMap<Int, Int>(4096)

        val block = ByteArray(255)
        var blockLen = 0
        var bitBuf = 0L
        var bitCount = 0

        fun emitByte(b: Int) {
            block[blockLen++] = b.toByte()
            if (blockLen == 255) {
                out.write(255); out.write(block, 0, 255); blockLen = 0
            }
        }

        fun writeCode(code: Int) {
            bitBuf = bitBuf or (code.toLong() shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                emitByte((bitBuf and 0xFF).toInt())
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        writeCode(clear)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val code = dict[key]
            if (code != null) {
                prefix = code
            } else {
                writeCode(prefix)
                maxCode++
                dict[key] = maxCode
                if (maxCode >= (1 shl codeSize)) codeSize++
                if (maxCode == 4095) {
                    writeCode(clear)
                    dict.clear()
                    codeSize = minCodeSize + 1
                    maxCode = eoi
                }
                prefix = k
            }
        }
        writeCode(prefix)
        writeCode(eoi)
        if (bitCount > 0) emitByte((bitBuf and 0xFF).toInt())
        if (blockLen > 0) {
            out.write(blockLen); out.write(block, 0, blockLen)
        }
        out.write(0) // block terminator
    }
}

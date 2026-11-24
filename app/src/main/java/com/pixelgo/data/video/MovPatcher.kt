package com.pixelgo.data.video

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Turns an MP4 written by MediaMuxer into a QuickTime (.mov) file. Both formats use the same
 * box structure and the same H.264/AAC streams; what identifies QuickTime is the `ftyp` box.
 * The brands inside it are rewritten in place (same size, so nothing else in the file moves).
 */
object MovPatcher {
    private val QT = byteArrayOf('q'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(), ' '.code.toByte())

    /** Size of the leading ftyp box, read from its first 8 bytes. */
    fun ftypSize(first8: ByteArray): Int {
        val b = ByteBuffer.wrap(first8)
        val size = b.int
        val type = String(first8, 4, 4, Charsets.US_ASCII)
        if (type != "ftyp" || size < 16 || size > 4096 || size % 4 != 0) throw IOException("No ftyp box at the start")
        return size
    }

    /** Rewrites a whole ftyp box (header included) for QuickTime. */
    fun patchFtyp(box: ByteArray): ByteArray {
        ftypSize(box.copyOf(8))
        val out = box.copyOf()
        QT.copyInto(out, 8)                                  // major brand
        ByteBuffer.wrap(out).putInt(12, 0x00000200)          // minor version (QuickTime convention)
        var i = 16
        while (i + 4 <= out.size) {                          // every compatible brand
            QT.copyInto(out, i)
            i += 4
        }
        return out
    }

    /** Patches a file through a read/write channel (used in tests). */
    fun patch(ch: FileChannel) {
        val head = ByteBuffer.allocate(8)
        if (ch.read(head, 0) != 8) throw IOException("File too short")
        val size = ftypSize(head.array())
        val box = ByteBuffer.allocate(size)
        ch.read(box, 0)
        ch.write(ByteBuffer.wrap(patchFtyp(box.array())), 0)
        ch.force(false)
    }

    /** Major brand of a file (for checks). */
    fun majorBrand(ch: FileChannel): String {
        val b = ByteBuffer.allocate(12)
        ch.read(b, 0)
        return String(b.array(), 8, 4, Charsets.US_ASCII)
    }
}

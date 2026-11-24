package com.pixelgo

import com.pixelgo.data.video.MovPatcher
import com.pixelgo.data.video.VideoLayout
import com.pixelgo.data.video.VideoLayout.Companion.padTo
import com.pixelgo.data.video.VideoShape
import com.pixelgo.data.video.VideoSize
import com.pixelgo.data.video.Yuv709
import com.pixelgo.data.video.YuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class VideoTest {

    private fun checkEven(l: VideoLayout) {
        assertEquals(0, l.width % 2); assertEquals(0, l.height % 2)
        assertEquals(0, l.scale % 2); assertEquals(0, l.offsetX % 2); assertEquals(0, l.offsetY % 2)
    }

    @Test fun canvasShapeFillsTheChosenSize() {
        val l = VideoLayout.compute(32, 32, VideoSize.P1080, VideoShape.CANVAS)
        assertEquals(32, l.scale)              // 32 × 32 = 1024: largest even scale under 1080
        assertEquals(1024, l.width); assertEquals(1024, l.height)
        assertEquals(0, l.offsetX)
        checkEven(l)
    }

    @Test fun wideShapePadsWithBackground() {
        val l = VideoLayout.compute(16, 32, VideoSize.P1080, VideoShape.WIDE)
        assertEquals(1920, l.width); assertEquals(1080, l.height)
        assertEquals(32, l.scale)              // 32 px tall × 32 = 1024 ≤ 1080, even
        assertEquals((1920 - 16 * 32) / 2, l.offsetX)
        checkEven(l)
    }

    @Test fun oddScaleIsRoundedDownToEven() {
        val l = VideoLayout.compute(256, 256, VideoSize.P720, VideoShape.CANVAS)
        assertEquals(2, l.scale)               // 720/256 = 2
        val l480 = VideoLayout.compute(256, 256, VideoSize.P480, VideoShape.CANVAS)
        assertEquals(2, l480.scale)            // 480/256 = 1 -> at least 2 (video grows to 512)
        assertEquals(512, l480.width)
        checkEven(l480)
        val odd = VideoLayout.compute(100, 100, VideoSize.P720, VideoShape.SQUARE)
        assertEquals(6, odd.scale)             // 720/100 = 7 -> 6
        checkEven(odd)
    }

    @Test fun paddingKeepsArtCentered() {
        val l = VideoLayout.compute(16, 32, VideoSize.P1080, VideoShape.WIDE).padTo(1920, 1088)
        assertEquals(1088, l.height)
        assertEquals(16 * 32, l.contentWidth)
        checkEven(l)
    }

    @Test fun yuvOfKnownColors() {
        // BT.709 limited range: white = (235,128,128), black = (16,128,128)
        assertEquals(235, Yuv709.y(255, 255, 255)); assertEquals(128, Yuv709.u(255, 255, 255)); assertEquals(128, Yuv709.v(255, 255, 255))
        assertEquals(16, Yuv709.y(0, 0, 0))
        // every grey is exactly neutral (no colour cast)
        for (v in 0..255) {
            assertEquals(128, Yuv709.u(v, v, v)); assertEquals(128, Yuv709.v(v, v, v))
        }
        // pure red: Y≈63, U≈102, V≈240
        assertTrue(Yuv709.y(255, 0, 0) in 62..64)
        assertTrue(Yuv709.u(255, 0, 0) in 101..103)
        assertTrue(Yuv709.v(255, 0, 0) in 239..241)
    }

    @Test fun yuvFrameKeepsPixelEdgesOnChromaBlocks() {
        val red = 0xFFFF0000.toInt()
        val art = intArrayOf(red, 0, 0, red)          // 2x2: red, clear / clear, red
        val l = VideoLayout(8, 8, 2, 2, 2)            // scale 2, 2px border
        val f = YuvFrame(art, 2, 2, 0xFF000000.toInt(), l)
        val ry = Yuv709.y(255, 0, 0).toByte()
        val by = Yuv709.y(0, 0, 0).toByte()
        // Luma row 2 (first art row): border, red red, black black, border
        assertEquals(listOf(by, by, ry, ry, by, by, by, by), f.yRow(2).toList())
        assertEquals(f.yRow(2).toList(), f.yRow(3).toList())
        assertEquals(List(8) { by }, f.yRow(0).toList())     // top border is background
        // Chroma row 1 (= luma rows 2-3): one chroma sample per art pixel, exactly its colour
        val ru = Yuv709.u(255, 0, 0).toByte()
        val bu = Yuv709.u(0, 0, 0).toByte()
        assertEquals(listOf(bu, ru, bu, bu), f.uRow(1).toList())
        assertEquals(listOf(bu, bu, ru, bu), f.uRow(2).toList())
    }

    @Test fun movPatchRewritesBrands() {
        val file = File.createTempFile("pixelgo", ".mp4")
        try {
            // A minimal ftyp box: size 24, "ftyp", major "isom", minor 512, compatible "isom" "mp41"
            val box = ByteBuffer.allocate(24 + 8)
            box.putInt(24); box.put("ftyp".toByteArray()); box.put("isom".toByteArray()); box.putInt(512)
            box.put("isom".toByteArray()); box.put("mp41".toByteArray())
            box.putInt(8); box.put("free".toByteArray()) // the next box must be untouched
            file.writeBytes(box.array())
            RandomAccessFile(file, "rw").use { raf ->
                MovPatcher.patch(raf.channel)
                assertEquals("qt  ", MovPatcher.majorBrand(raf.channel))
            }
            val bytes = file.readBytes()
            assertEquals("qt  qt  ", String(bytes, 16, 8, Charsets.US_ASCII))
            assertEquals("free", String(bytes, 28, 4, Charsets.US_ASCII))
            assertEquals(32, bytes.size)
        } finally {
            file.delete()
        }
    }
}

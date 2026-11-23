package com.pixelgo

import com.pixelgo.data.GifEncoder
import com.pixelgo.data.ProjectSerializer
import com.pixelgo.data.sheetGrid
import com.pixelgo.editor.ColorMath
import com.pixelgo.editor.EditorEngine
import com.pixelgo.model.PixelProject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

class AnimationTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun engine(w: Int = 4, h: Int = 4) = EditorEngine(PixelProject.create("t", "Anim", w, h))
    private fun EditorEngine.dot(x: Int, y: Int, c: Int) { beginStroke(x, y, c); endStroke() }
    private fun EditorEngine.cel(frame: Int, layer: Int = 0) = project.layers[layer].cels[frame]

    @Test fun drawingOnlyAffectsCurrentFrame() {
        val e = engine()
        e.dot(0, 0, red)
        e.addFrame()                      // blank frame 2, now current
        assertEquals(1, e.project.currentFrame)
        assertEquals(0, e.project.layers[0].pixels[0])
        e.dot(1, 0, blue)
        assertEquals(red, e.cel(0)[0]); assertEquals(0, e.cel(0)[1])
        assertEquals(0, e.cel(1)[0]); assertEquals(blue, e.cel(1)[1])
    }

    @Test fun duplicateFrameCopiesAndIsIndependent() {
        val e = engine()
        e.dot(0, 0, red)
        e.duplicateFrame()
        assertEquals(red, e.cel(1)[0])
        e.dot(0, 0, blue)                 // edit the copy
        assertEquals(red, e.cel(0)[0])
        assertEquals(blue, e.cel(1)[0])
    }

    @Test fun undoOfEditOnOtherFrameJumpsBackToIt() {
        val e = engine()
        e.dot(0, 0, red)                  // frame 0
        e.addFrame()                      // frame 1
        e.selectFrame(1)
        e.undo()                          // undo add-frame
        assertEquals(1, e.project.frames.size)
        e.undo()                          // undo the dot on frame 0
        assertEquals(0, e.cel(0)[0])
        assertEquals(0, e.project.currentFrame)
    }

    @Test fun deleteAndMoveFramesWithUndo() {
        val e = engine()
        e.dot(0, 0, red)                  // f0 red
        e.addFrame(); e.dot(0, 0, blue)   // f1 blue
        e.moveFrame(1, 0)                 // [blue, red]
        assertEquals(blue, e.cel(0)[0]); assertEquals(red, e.cel(1)[0])
        e.selectFrame(0)
        e.deleteFrame()                   // [red]
        assertEquals(1, e.project.frames.size); assertEquals(red, e.cel(0)[0])
        e.undo()                          // [blue, red]
        assertEquals(2, e.project.frames.size); assertEquals(blue, e.cel(0)[0])
        e.undo()                          // [red, blue]
        assertEquals(red, e.cel(0)[0]); assertEquals(blue, e.cel(1)[0])
    }

    @Test fun newLayerGetsACelForEveryFrame() {
        val e = engine()
        e.addFrame(); e.addFrame()
        e.addLayer()
        assertEquals(3, e.project.layers[1].cels.size)
    }

    @Test fun mergeDownMergesAllFrames() {
        val e = engine()
        e.addFrame()
        e.selectFrame(0); e.dot(0, 0, red)
        e.addLayer()
        e.selectFrame(1); e.dot(1, 0, blue)
        e.mergeDown()
        assertEquals(red, e.cel(0)[0])
        assertEquals(blue, e.cel(1)[1])
        e.undo()
        assertEquals(0, e.cel(1, 0)[1])
        assertEquals(blue, e.cel(1, 1)[1])
    }

    @Test fun holdIsUndoable() {
        val e = engine()
        e.setFrameHold(0, 3)
        assertEquals(3, e.project.frames[0].hold)
        e.undo()
        assertEquals(1, e.project.frames[0].hold)
    }

    @Test fun animatedProjectRoundTrip() {
        val e = engine(3, 2)
        e.dot(0, 0, red)
        e.addFrame(); e.dot(2, 1, blue)
        e.setFrameHold(1, 2)
        e.project.fps = 12
        val bytes = ByteArrayOutputStream().also { ProjectSerializer.write(e.project, 5L, it) }.toByteArray()
        val header = ProjectSerializer.readHeader(ByteArrayInputStream(bytes))
        assertEquals(2, header.frameCount)
        val p = ProjectSerializer.read("t", ByteArrayInputStream(bytes))
        assertEquals(2, p.frames.size)
        assertEquals(12, p.fps)
        assertEquals(2, p.frames[1].hold)
        assertEquals(1, p.currentFrame)
        assertArrayEquals(e.cel(0), p.layers[0].cels[0])
        assertArrayEquals(e.cel(1), p.layers[0].cels[1])
        assertEquals(blue, p.layers[0].pixels[5]) // pixels follows the current frame
    }

    @Test fun readsOldSingleFrameFiles() {
        // Hand-written v1 file: 2x1, one layer.
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x5058474F); writeInt(1); writeUTF("Old"); writeInt(2); writeInt(1); writeLong(9L); flush()
        }
        val z = DeflaterOutputStream(out)
        DataOutputStream(z).apply {
            writeInt(0); writeLong(2L); writeInt(1)
            writeLong(1L); writeUTF("Layer 1"); writeBoolean(true)
            write(ByteBuffer.allocate(8).putInt(red).putInt(0).array())
            flush()
        }
        z.finish()
        val p = ProjectSerializer.read("o", ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, p.frames.size)
        assertEquals(8, p.fps)
        assertEquals(red, p.layers[0].pixels[0])
    }

    @Test fun gifDecodesWithCorrectFramesSizeAndTransparency() {
        val w = 4; val h = 3; val scale = 2
        val f1 = IntArray(w * h).also { it[0] = red }
        val f2 = IntArray(w * h).also { it[w * h - 1] = blue; it[1] = 0x40FFFFFF } // low alpha -> clear
        val bytes = ByteArrayOutputStream().also {
            GifEncoder.encode(listOf(f1, f2), w, h, scale, listOf(12, 25), it)
        }.toByteArray()
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        assertEquals(2, reader.getNumImages(true))
        val img1 = reader.read(0)
        assertEquals(w * scale, img1.width); assertEquals(h * scale, img1.height)
        assertEquals(red, img1.getRGB(0, 0)); assertEquals(red, img1.getRGB(1, 1))
        assertEquals(0, img1.getRGB(2, 0) ushr 24)       // transparent
        val img2 = reader.read(1)
        assertEquals(blue, img2.getRGB(w * scale - 1, h * scale - 1))
        assertEquals(0, img2.getRGB(2, 0) ushr 24)
    }

    @Test fun gifHandlesLargeNoisyImagesAndManyColors() {
        // Big enough to force LZW code-size growth and dictionary resets, with >255 colors.
        val w = 128; val h = 128
        val rnd = java.util.Random(1)
        val frame = IntArray(w * h) { 0xFF000000.toInt() or rnd.nextInt(0x1000000) }
        val bytes = ByteArrayOutputStream().also {
            GifEncoder.encode(listOf(frame), w, h, 1, listOf(10), it)
        }.toByteArray()
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(w, img.width)
        // Every decoded pixel must be one of the palette colors (no corrupt stream)
        val palette = GifEncoder.buildPalette(listOf(frame)).map { it or 0xFF000000.toInt() }.toSet()
        for (y in 0 until h) for (x in 0 until w) assertTrue(img.getRGB(x, y) in palette)
        // Colors that made the palette decode exactly
        val exact = (0 until w * h).first { (frame[it] and 0xFFFFFF) in GifEncoder.buildPalette(listOf(frame)).toSet() }
        assertEquals(frame[exact], img.getRGB(exact % w, exact / w))
    }

    @Test fun gifPaletteKeepsExactColorsForPixelArt() {
        val w = 16; val h = 16
        val frame = IntArray(w * h) { if (it % 3 == 0) red else if (it % 3 == 1) blue else 0 }
        val bytes = ByteArrayOutputStream().also { GifEncoder.encode(listOf(frame), w, h, 1, listOf(10), it) }.toByteArray()
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        for (i in 0 until w * h) {
            val got = img.getRGB(i % w, i / w)
            if (frame[i] == 0) assertEquals(0, got ushr 24) else assertEquals(frame[i], got)
        }
    }

    @Test fun spriteSheetGrid() {
        assertEquals(4 to 1, sheetGrid(4, 256))
        assertEquals(6 to 5, sheetGrid(30, 512)) // 30*512 > 4096 -> ceil(sqrt(30)) = 6 cols
    }

    @Test fun compositeFrameUsesThatFrame() {
        val e = engine()
        e.dot(0, 0, red)
        e.addFrame(); e.dot(0, 0, blue)
        assertEquals(red, ColorMath.compositeFrame(e.project, 0)[0])
        assertEquals(blue, ColorMath.compositeFrame(e.project, 1)[0])
    }
}

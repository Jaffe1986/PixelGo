package com.pixelgo

import com.pixelgo.data.GifEncoder
import com.pixelgo.data.LegacyPxg
import com.pixelgo.data.MetaSerializer
import com.pixelgo.data.sheetGrid
import com.pixelgo.editor.ColorMath
import com.pixelgo.editor.EditorEngine
import com.pixelgo.editor.FramePosition
import com.pixelgo.model.MAX_HOLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val green = 0xFF00FF00.toInt()

    private fun engine(w: Int = 4, h: Int = 4) = testEngine(w, h)

    /** Pixel [i] of layer [layer] on frame [frame] (indices), 0 if the cel is empty. */
    private fun EditorEngine.at(frame: Int, i: Int, layer: Int = 0): Int =
        store.read(project.layers[layer].id, project.frames[frame].uid)?.get(i) ?: 0

    @Test fun drawingOnlyAffectsCurrentFrame() {
        val e = engine()
        e.dot(0, 0, red)
        e.addFrame()                      // blank frame 2, now current
        assertEquals(1, e.project.currentFrameIndex)
        assertEquals(0, e.px(0, 0))
        e.dot(1, 0, blue)
        assertEquals(red, e.at(0, 0)); assertEquals(0, e.at(0, 1))
        assertEquals(0, e.at(1, 0)); assertEquals(blue, e.at(1, 1))
    }

    @Test fun duplicateFrameCopiesAndIsIndependent() {
        val e = engine()
        e.dot(0, 0, red)
        e.duplicateFrame()
        assertEquals(red, e.at(1, 0))
        e.dot(0, 0, blue)                 // edit the copy
        assertEquals(red, e.at(0, 0))
        assertEquals(blue, e.at(1, 0))
    }

    @Test fun duplicateToEndAppendsAfterLastFrame() {
        val e = engine()
        e.dot(0, 0, red)                  // A
        e.addFrame(); e.dot(0, 0, blue)   // B
        e.addFrame(); e.dot(0, 0, green)  // C
        e.selectFrame(0)
        e.duplicateFrame(toEnd = true)    // A B C A'
        assertEquals(4, e.project.frames.size)
        assertEquals(3, e.project.currentFrameIndex)
        assertEquals(red, e.at(3, 0))
        assertEquals(blue, e.at(1, 0))
        e.undo()
        assertEquals(3, e.project.frames.size)
    }

    @Test fun insertBeforeAndAfter() {
        val e = engine()
        e.dot(0, 0, red)                  // A
        e.addFrame(FramePosition.BEFORE)  // _ A
        assertEquals(0, e.project.currentFrameIndex)
        assertEquals(red, e.at(1, 0))
        e.selectFrame(1)
        e.addFrame(FramePosition.AFTER)   // _ A _
        assertEquals(2, e.project.currentFrameIndex)
        assertEquals(3, e.project.frames.size)
        e.addFrame(FramePosition.END)
        assertEquals(3, e.project.currentFrameIndex)
    }

    @Test fun undoOfEditOnOtherFrameJumpsBackToIt() {
        val e = engine()
        e.dot(0, 0, red)                  // frame 0
        e.addFrame()                      // frame 1 (current)
        e.undo()                          // undo add-frame
        assertEquals(1, e.project.frames.size)
        e.undo()                          // undo the dot on frame 0
        assertEquals(0, e.at(0, 0))
        assertEquals(0, e.project.currentFrameIndex)
    }

    @Test fun currentFrameFollowsUidWhenFramesMove() {
        val e = engine()
        e.dot(0, 0, red)                  // f0 red
        e.addFrame(); e.dot(0, 0, blue)   // f1 blue (current)
        val blueUid = e.project.currentFrameUid
        e.moveFrame(1, 0)                 // [blue, red]
        assertEquals(blueUid, e.project.currentFrameUid)
        assertEquals(0, e.project.currentFrameIndex)
        assertEquals(blue, e.px(0, 0))
    }

    @Test fun deleteAndMoveFramesWithUndo() {
        val e = engine()
        e.dot(0, 0, red)                  // f0 red
        e.addFrame(); e.dot(0, 0, blue)   // f1 blue
        e.moveFrame(1, 0)                 // [blue, red]
        assertEquals(blue, e.at(0, 0)); assertEquals(red, e.at(1, 0))
        e.selectFrame(0)
        e.deleteFrame()                   // [red]
        assertEquals(1, e.project.frames.size); assertEquals(red, e.at(0, 0))
        e.undo()                          // [blue, red] - deleted frame's pixels still there
        assertEquals(2, e.project.frames.size); assertEquals(blue, e.at(0, 0))
        e.undo()                          // [red, blue]
        assertEquals(red, e.at(0, 0)); assertEquals(blue, e.at(1, 0))
    }

    @Test fun deletedFrameStaysReferencedByHistory() {
        val e = engine()
        e.addFrame()
        val uid = e.project.currentFrameUid
        e.deleteFrame()
        val frames = HashSet<Long>()
        e.collectRefs(HashSet(), frames)
        assertTrue(uid in frames)
    }

    @Test fun newLayerIsEmptyOnEveryFrameAndDrawable() {
        val e = engine()
        e.addFrame(); e.addFrame()
        e.addLayer()
        for (f in 0..2) assertNull(e.store.read(e.project.layers[1].id, e.project.frames[f].uid))
        e.dot(2, 0, red)
        assertEquals(red, e.at(2, 2, layer = 1))
    }

    @Test fun mergeDownMergesAllFrames() {
        val e = engine()
        e.addFrame()
        e.selectFrame(0); e.dot(0, 0, red)
        e.addLayer()
        e.selectFrame(1); e.dot(1, 0, blue)
        e.mergeDownJob()!!.let { it.runWork {}; it.finish() }
        assertEquals(red, e.at(0, 0))
        assertEquals(blue, e.at(1, 1))
        e.undo()
        assertEquals(0, e.at(1, 1, layer = 0))
        assertEquals(blue, e.at(1, 1, layer = 1))
    }

    @Test fun duplicateLayerCopiesEveryFrame() {
        val e = engine()
        e.dot(0, 0, red)
        e.addFrame(); e.dot(1, 0, blue)
        e.duplicateLayerJob()!!.let { it.runWork {}; it.finish() }
        assertEquals(2, e.project.layers.size)
        assertEquals(red, e.at(0, 0, layer = 1))
        assertEquals(blue, e.at(1, 1, layer = 1))
        e.undo()
        assertEquals(1, e.project.layers.size)
    }

    @Test fun holdIsUndoableAndCanBeLong() {
        val e = engine()
        e.setFrameHold(0, 3)
        assertEquals(3, e.project.frames[0].hold)
        e.undo()
        assertEquals(1, e.project.frames[0].hold)
        e.setFrameHold(0, 5000)
        assertEquals(MAX_HOLD, e.project.frames[0].hold)
        assertEquals(MAX_HOLD.toLong(), e.project.timing.totalTicks)
    }

    @Test fun animatedProjectMetaRoundTrip() {
        val e = engine(3, 2)
        e.dot(0, 0, red)
        e.addFrame(); e.dot(2, 1, blue)
        e.setFrameHold(1, 2)
        e.project.fps = 60
        val bytes = ByteArrayOutputStream().also { MetaSerializer.write(e.project, 5L, it) }.toByteArray()
        val header = MetaSerializer.readHeader(ByteArrayInputStream(bytes))
        assertEquals(2, header.frameCount)
        assertEquals(3L, header.totalTicks)
        assertEquals(50L, header.durationMs) // 3 ticks at 60 fps
        val p = MetaSerializer.read("t", ByteArrayInputStream(bytes))
        assertEquals(2, p.frames.size)
        assertEquals(60, p.fps)
        assertEquals(2, p.frames[1].hold)
        assertEquals(e.project.currentFrameUid, p.currentFrameUid)
        assertEquals(e.project.frames.map { it.uid }, p.frames.map { it.uid })
        assertEquals(e.project.nextFrameUid, p.nextFrameUid)
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
        val p = LegacyPxg.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, p.holds.size)
        assertEquals(8, p.fps)
        assertEquals(red, p.layers[0].cels[0][0])
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
        val palette = GifEncoder.buildPalette(listOf(frame)).map { it or 0xFF000000.toInt() }.toSet()
        for (y in 0 until h) for (x in 0 until w) assertTrue(img.getRGB(x, y) in palette)
    }

    @Test fun gifStreamsFromProviderWithoutAList() {
        val w = 2; val h = 2
        var calls = 0
        val bytes = ByteArrayOutputStream().also {
            GifEncoder.encode(3, { i -> calls++; IntArray(w * h) { if (it == i) red else 0 } }, w, h, 1, intArrayOf(5, 5, 5), it)
        }.toByteArray()
        assertEquals(6, calls) // palette pass + encode pass
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        assertEquals(3, reader.getNumImages(true))
    }

    @Test fun gifDelaysCarryRoundingSoTotalIsExact() {
        val d = GifEncoder.delaysCs(intArrayOf(1, 1, 1, 1), 8) // 12.5 cs each
        assertEquals(50, d.sum())
        assertTrue(d.all { it in 12..13 })
        val fast = GifEncoder.delaysCs(IntArray(6) { 1 }, 60)  // 1.67 cs: raised to the 2 cs minimum
        assertTrue(fast.all { it >= 2 })
    }

    @Test fun spriteSheetGrid() {
        assertEquals(4 to 1, sheetGrid(4, 256))
        assertEquals(6 to 5, sheetGrid(30, 512)) // 30*512 > 4096 -> ceil(sqrt(30)) = 6 cols
    }

    @Test fun compositeFrameUsesThatFrame() {
        val e = engine()
        e.dot(0, 0, red)
        e.addFrame(); e.dot(0, 0, blue)
        assertEquals(red, ColorMath.compositeFrame(e.project, e.store, e.project.frames[0].uid)[0])
        assertEquals(blue, ColorMath.compositeFrame(e.project, e.store, e.project.frames[1].uid)[0])
    }
}

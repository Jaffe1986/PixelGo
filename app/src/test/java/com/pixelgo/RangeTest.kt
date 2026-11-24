package com.pixelgo

import com.pixelgo.data.FrameClipboard
import com.pixelgo.editor.EditorEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RangeTest {
    private val c1 = 0xFF000001.toInt()
    private val c2 = 0xFF000002.toInt()
    private val c3 = 0xFF000003.toInt()
    private val c4 = 0xFF000004.toInt()
    private val red = 0xFFFF0000.toInt()
    private lateinit var tmp: File

    @Before fun setUp() {
        tmp = Files.createTempDirectory("pixelgo-range").toFile()
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    /** 4 frames whose pixel 0 is c1..c4. */
    private fun fourFrames(): EditorEngine {
        val e = testEngine(2, 2)
        e.dot(0, 0, c1)
        for (c in listOf(c2, c3, c4)) {
            e.addFrame()
            e.dot(0, 0, c)
        }
        return e
    }

    private fun EditorEngine.order(): List<Int> =
        project.frames.map { store.read(project.layers[0].id, it.uid)?.get(0) ?: 0 }

    private fun EditorEngine.run(job: com.pixelgo.editor.BulkJob?) {
        job!!.runWork {}
        job.finish()
    }

    @Test fun reverseRangeAndUndo() {
        val e = fourFrames()
        e.reverseFrames(1, 3)
        assertEquals(listOf(c1, c4, c3, c2), e.order())
        e.undo()
        assertEquals(listOf(c1, c2, c3, c4), e.order())
    }

    @Test fun moveRangeLaterAndEarlier() {
        val e = fourFrames()
        assertEquals(2, e.moveFrames(0, 2, 2))      // [c3 c4 c1 c2]
        assertEquals(listOf(c3, c4, c1, c2), e.order())
        assertEquals(2, e.moveFrames(2, 2, 5))      // clamped: already at the end
        e.undo()
        assertEquals(listOf(c1, c2, c3, c4), e.order())
    }

    @Test fun deleteRangeKeepsAtLeastOneFrame() {
        val e = fourFrames()
        assertFalse(e.deleteFrames(0, 4))
        assertTrue(e.deleteFrames(1, 2))
        assertEquals(listOf(c1, c4), e.order())
        e.undo()
        assertEquals(listOf(c1, c2, c3, c4), e.order())
    }

    @Test fun duplicateRangeAfterAndToEnd() {
        val e = fourFrames()
        e.run(e.duplicateFramesJob(1, 2, toEnd = false))
        assertEquals(listOf(c1, c2, c3, c2, c3, c4), e.order())
        e.run(e.duplicateFramesJob(0, 1, toEnd = true))
        assertEquals(listOf(c1, c2, c3, c2, c3, c4, c1), e.order())
        e.undo(); e.undo()
        assertEquals(listOf(c1, c2, c3, c4), e.order())
    }

    @Test fun setHoldsOnRangeIsOneUndo() {
        val e = fourFrames()
        e.setHolds(1, 2, 6)
        assertEquals(listOf(1, 6, 6, 1), e.project.frames.map { it.hold })
        assertEquals(14L, e.project.timing.totalTicks)
        e.undo()
        assertEquals(listOf(1, 1, 1, 1), e.project.frames.map { it.hold })
    }

    @Test fun clipboardRoundTripAcrossProjects() {
        val src = fourFrames()
        src.setHolds(2, 1, 3)
        val board = FrameClipboard(File(tmp, "clip"))
        assertNull(board.info())
        board.write(src.frameRange(1, 2)!!)            // c2, c3(hold 3)
        assertEquals(2, board.info()!!.frames)

        val dst = testEngine(2, 2)                     // a different project
        dst.run(dst.pasteFramesJob(board.open()!!, 1))   // paste after its only frame
        assertEquals(listOf(0, c2, c3), dst.order())
        assertEquals(listOf(1, 1, 3), dst.project.frames.map { it.hold })
        assertEquals(1, dst.project.currentFrameIndex) // first pasted frame is current
        dst.undo()
        assertEquals(1, dst.project.frames.size)
    }

    @Test fun pasteAddsMissingLayersAndCentersOtherSizes() {
        // Source: 4x2 canvas, two layers.
        val src = testEngine(4, 2)
        src.dot(0, 0, red)                             // bottom layer, top-left
        src.addLayer()
        src.dot(3, 1, c1)                              // top layer, bottom-right
        src.renameLayer(1, "Ink")
        val board = FrameClipboard(File(tmp, "clip"))
        board.write(src.frameRange(0, 1)!!)

        // Target: 2x2 canvas with one layer -> "Ink" layer is added, content centered/cropped.
        val dst = testEngine(2, 2)
        dst.run(dst.pasteFramesJob(board.open()!!, 1))
        assertEquals(2, dst.project.layers.size)
        assertEquals("Ink", dst.project.layers[1].name)
        val pasted = dst.project.frames[1].uid
        // Source x=1..2 lands on target x=0..1 (offset -1): red at x=0 is cropped away,
        // c1 at (3,1) is cropped away too; so both cels are empty.
        assertNull(dst.store.read(dst.project.layers[0].id, pasted))
        dst.undo()                                     // removes the frame AND the added layer
        assertEquals(1, dst.project.layers.size)
        assertEquals(1, dst.project.frames.size)
    }

    @Test fun pasteIntoLargerCanvasPads() {
        val src = testEngine(2, 2)
        src.dot(0, 0, red)
        val board = FrameClipboard(File(tmp, "clip"))
        board.write(src.frameRange(0, 1)!!)
        val dst = testEngine(4, 4)
        dst.run(dst.pasteFramesJob(board.open()!!, 0))
        val px = dst.store.read(dst.project.layers[0].id, dst.project.frames[0].uid)
        assertNotNull(px)
        assertEquals(red, px!![1 * 4 + 1])            // (0,0) centered to (1,1)
    }

    @Test fun halfWrittenClipboardIsEmpty() {
        val board = FrameClipboard(File(tmp, "clip"))
        board.write(fourFrames().frameRange(0, 2)!!)
        File(tmp, "clip/clip.meta").delete()           // e.g. the system cleared part of the cache
        assertNull(board.info())
        assertNull(board.open())
    }
}

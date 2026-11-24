package com.pixelgo

import com.pixelgo.data.MetaSerializer
import com.pixelgo.data.PaletteSerializer
import com.pixelgo.editor.ColorMath
import com.pixelgo.editor.EditorEngine
import com.pixelgo.editor.MemoryCelStore
import com.pixelgo.editor.Raster
import com.pixelgo.model.Palette
import com.pixelgo.model.PixelProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Engine on an in-memory cel store. */
fun testEngine(w: Int = 8, h: Int = 8) = EditorEngine(PixelProject.create("t", "Test", w, h), MemoryCelStore(w * h))

/** Pixel of a layer (index) on the current frame; 0 when the cel is empty. */
fun EditorEngine.px(x: Int, y: Int, layer: Int = project.activeLayerIndex): Int =
    store.read(project.layers[layer].id, project.currentFrameUid)?.get(y * width + x) ?: 0

fun EditorEngine.dot(x: Int, y: Int, c: Int) {
    beginStroke(x, y, c)
    endStroke()
}

class EngineTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun engine(w: Int = 8, h: Int = 8) = testEngine(w, h)

    @Test fun mirrorPointsEvenWidth() {
        val pts = mutableListOf<Pair<Int, Int>>()
        Raster.forEachMirrored(1, 2, 8, 8, mirrorX = true, mirrorY = true) { x, y -> pts += x to y }
        assertEquals(setOf(1 to 2, 6 to 2, 1 to 5, 6 to 5), pts.toSet())
        assertEquals(4, pts.size)
    }

    @Test fun mirrorPointsOnCenterOfOddWidthDeduplicates() {
        val pts = mutableListOf<Pair<Int, Int>>()
        Raster.forEachMirrored(2, 2, 5, 5, mirrorX = true, mirrorY = true) { x, y -> pts += x to y }
        assertEquals(listOf(2 to 2), pts)
    }

    @Test fun strokeWithHorizontalSymmetryPaintsBothSides() {
        val e = engine()
        e.mirrorX = true
        e.dot(0, 3, red)
        assertEquals(red, e.px(0, 3))
        assertEquals(red, e.px(7, 3))
        assertEquals(0, e.px(0, 4))
    }

    @Test fun strokeWithVerticalSymmetryPaintsTopAndBottom() {
        val e = engine()
        e.mirrorY = true
        e.dot(2, 0, red)
        assertEquals(red, e.px(2, 7))
        assertEquals(0, e.px(5, 0))
    }

    @Test fun lineHasNoGaps() {
        val pts = mutableListOf<Pair<Int, Int>>()
        Raster.line(0, 0, 7, 3) { x, y -> pts += x to y }
        assertEquals(8, pts.size)
        for (k in 1 until pts.size) {
            val (ax, ay) = pts[k - 1]
            val (bx, by) = pts[k]
            assertTrue(kotlin.math.abs(ax - bx) <= 1 && kotlin.math.abs(ay - by) <= 1)
        }
        assertEquals(7 to 3, pts.last())
    }

    @Test fun floodFillStopsAtBoundary() {
        val w = 5
        val px = IntArray(25)
        for (y in 0 until 5) px[y * w + 2] = blue // vertical wall at x=2
        val changed = Raster.floodFill(px, w, 5, 0, 0, red)
        assertEquals(10, changed.size)
        assertEquals(red, px[1])
        assertEquals(0, px[3])
        assertEquals(blue, px[2])
    }

    @Test fun undoRedoStroke() {
        val e = engine()
        e.beginStroke(0, 0, red)
        e.strokeTo(3, 0)
        assertTrue(e.endStroke())
        assertEquals(red, e.px(3, 0))
        e.undo()
        assertEquals(0, e.px(3, 0))
        assertEquals(0, e.composite[3])
        e.redo()
        assertEquals(red, e.px(3, 0))
    }

    @Test fun cancelStrokeLeavesNoPixelsAndNoHistory() {
        val e = engine()
        e.beginStroke(1, 1, red)
        e.strokeTo(4, 1)
        e.cancelStroke()
        assertEquals(0, e.px(1, 1))
        assertFalse(e.history.canUndo)
    }

    @Test fun layerOrderChangesComposite() {
        val e = engine()
        e.dot(0, 0, red)          // Layer 1 (bottom)
        e.addLayer()
        e.dot(0, 0, blue)         // Layer 2 (top)
        e.consumeDirty({}, { _, _ -> })
        assertEquals(blue, e.composite[0])
        e.moveLayer(1, 0)         // blue layer to bottom
        e.consumeDirty({}, { _, _ -> })
        assertEquals(red, e.composite[0])
        e.undo()
        e.consumeDirty({}, { _, _ -> })
        assertEquals(blue, e.composite[0])
    }

    @Test fun hiddenLayerExcludedAndEraserMakesTransparent() {
        val e = engine()
        e.dot(0, 0, red)
        e.setLayerVisible(0, false)
        e.consumeDirty({}, { _, _ -> })
        assertEquals(0, e.composite[0])
        e.setLayerVisible(0, true)
        e.dot(0, 0, 0) // eraser
        e.consumeDirty({}, { _, _ -> })
        assertEquals(0, e.composite[0])
    }

    @Test fun mergeDownAndUndo() {
        val e = engine()
        e.dot(0, 0, red)
        e.addLayer()
        e.dot(1, 0, blue)
        val lowerId = e.project.layers[0].id
        val upperId = e.project.layers[1].id
        e.mergeDownJob()!!.let { it.runWork {}; it.finish() }
        assertEquals(1, e.project.layers.size)
        // The result is a NEW layer, so undo needs no pixel copies.
        val mergedId = e.project.layers[0].id
        assertTrue(mergedId != lowerId && mergedId != upperId)
        val refs = HashSet<Long>()
        e.collectRefs(refs, HashSet())
        assertTrue(refs.containsAll(listOf(lowerId, upperId, mergedId)))
        assertEquals(blue, e.px(1, 0, 0))
        e.undo()
        assertEquals(2, e.project.layers.size)
        assertEquals(0, e.px(1, 0, 0))
        assertEquals(blue, e.px(1, 0, 1))
        assertEquals(1, e.project.activeLayerIndex)
        e.redo()
        assertEquals(1, e.project.layers.size)
        assertEquals(blue, e.px(1, 0, 0))
        assertEquals(red, e.px(0, 0, 0))
        e.undo()
        assertEquals(2, e.project.layers.size)
        assertEquals(red, e.px(0, 0, 0))
    }

    @Test fun semiTransparentBlend() {
        val halfWhite = 0x80FFFFFF.toInt()
        assertEquals(halfWhite, ColorMath.blend(0, halfWhite))
        val c = ColorMath.blend(0xFF000000.toInt(), halfWhite)
        assertEquals(0xFF, c ushr 24)
        assertEquals(128, c and 0xFF)
    }

    @Test fun hexParsing() {
        assertEquals(0xFF112233.toInt(), ColorMath.parseHex("#112233"))
        assertEquals(0x80112233.toInt(), ColorMath.parseHex("80112233"))
        assertEquals(null, ColorMath.parseHex("#12"))
    }

    @Test fun scalePreservesAlpha() {
        val src = intArrayOf(0, 0x80FF0000.toInt(), blue, red)
        val out = Raster.scaleNearest(src, 2, 2, 3)
        assertEquals(36, out.size)
        assertEquals(0, out[0])
        assertEquals(0x80FF0000.toInt(), out[3])
        assertEquals(0x80FF0000.toInt(), out[2 * 6 + 5])
        assertEquals(red, out[35])
    }

    @Test fun projectMetaRoundTrip() {
        val e = engine(3, 2)
        e.dot(1, 1, 0x40ABCDEF)
        e.addLayer()
        e.renameLayer(1, "Top")
        e.setLayerVisible(1, false)
        val bytes = ByteArrayOutputStream().also { MetaSerializer.write(e.project, 123L, it) }.toByteArray()
        val header = MetaSerializer.readHeader(ByteArrayInputStream(bytes))
        assertEquals(3, header.width)
        assertEquals(123L, header.modified)
        assertEquals(1, header.frameCount)
        val p = MetaSerializer.read("t", ByteArrayInputStream(bytes))
        assertEquals(2, p.layers.size)
        assertEquals("Top", p.layers[1].name)
        assertFalse(p.layers[1].visible)
        assertEquals(e.project.layers.map { it.id }, p.layers.map { it.id })
        assertEquals(e.project.nextLayerId, p.nextLayerId)
        assertEquals(e.project.activeLayerIndex, p.activeLayerIndex)
    }

    // ---- Selection ---------------------------------------------------------------------

    @Test fun copyThenPasteAtNewSpotIsOneUndoStep() {
        val e = engine()
        e.dot(1, 1, red); e.dot(2, 1, blue)
        e.selectRect(1, 1, 2, 1)
        val clip = e.copySelection()!!
        assertEquals(2, clip.width); assertEquals(1, clip.height)
        assertTrue(e.beginPaste(clip))
        e.moveFloatingTo(4, 5)
        assertTrue(e.commitFloating())
        assertEquals(red, e.px(4, 5)); assertEquals(blue, e.px(5, 5))
        assertEquals(red, e.px(1, 1)) // original untouched
        e.undo()
        assertEquals(0, e.px(4, 5)); assertEquals(0, e.px(5, 5))
        assertEquals(red, e.px(1, 1))
    }

    @Test fun cutClearsAndUndoRestores() {
        val e = engine()
        e.dot(3, 3, red)
        e.selectRect(2, 2, 4, 4)
        val clip = e.cutSelection()!!
        assertEquals(9, clip.cellCount)
        assertEquals(0, e.px(3, 3))
        e.undo()
        assertEquals(red, e.px(3, 3))
    }

    @Test fun moveCommitsAsSingleUndoAndSelectionFollows() {
        val e = engine()
        e.dot(0, 0, red)
        e.selectRect(0, 0, 0, 0)
        assertTrue(e.beginMove())
        assertEquals(0, e.px(0, 0)) // lifted
        e.moveFloatingTo(6, 6)
        assertTrue(e.commitFloating())
        assertEquals(0, e.px(0, 0)); assertEquals(red, e.px(6, 6))
        assertTrue(e.selection!![6 * 8 + 6])
        e.undo()
        assertEquals(red, e.px(0, 0)); assertEquals(0, e.px(6, 6))
    }

    @Test fun cancelMovePutsCellsBack() {
        val e = engine()
        e.dot(2, 2, red)
        e.selectRect(2, 2, 2, 2)
        e.beginMove()
        e.moveFloatingTo(5, 5)
        e.cancelFloating()
        assertEquals(red, e.px(2, 2)); assertEquals(0, e.px(5, 5))
        assertTrue(e.selection!![2 * 8 + 2])
        e.undo() // the only history entry is the original dot, not the cancelled move
        assertEquals(0, e.px(2, 2))
        assertFalse(e.history.canUndo)
    }

    @Test fun transparentCellsInClipDoNotOverwrite() {
        val e = engine()
        e.dot(5, 0, blue)
        e.selectRect(0, 0, 1, 0)        // (0,0)=transparent, (1,0)=transparent
        e.dot(1, 0, red)
        val clip = e.copySelection()!!  // [transparent, red]
        e.beginPaste(clip)
        e.moveFloatingTo(4, 0)          // transparent lands on (4,0), red on (5,0)
        e.commitFloating()
        assertEquals(0, e.px(4, 0))
        assertEquals(red, e.px(5, 0))
    }

    @Test fun paintSelectAddsThenRemoves() {
        val e = engine()
        e.beginSelectPaint(0, 0)
        e.selectPaintTo(3, 0)
        assertTrue((0..3).all { e.selection!![it] })
        e.beginSelectPaint(1, 0) // starts on a selected cell -> removes
        e.selectPaintTo(2, 0)
        assertTrue(e.selection!![0]); assertFalse(e.selection!![1]); assertFalse(e.selection!![2]); assertTrue(e.selection!![3])
    }

    @Test fun selectionOutline() {
        val single = BooleanArray(9).also { it[4] = true }
        assertEquals(4 * 4, com.pixelgo.editor.SelectionMath.edges(single, 3, 3).size)
        val row = BooleanArray(9) { it < 3 }  // top row: merged into 4 segments
        assertEquals(4 * 4, com.pixelgo.editor.SelectionMath.edges(row, 3, 3).size)
    }

    @Test fun layerFileNamesAreNumberedBottomToTopAndUnique() {
        val names = com.pixelgo.data.PngExporter.layerFileNames(listOf("Back/ground", "Body", "Body"), 4)
        assertEquals(listOf("01_Background_4x", "02_Body_4x", "03_Body_4x"), names)
        val many = com.pixelgo.data.PngExporter.layerFileNames(List(12) { "" }, 1)
        assertEquals("01_layer_1x", many[0])
        assertEquals("12_layer_1x", many[11])
    }

    @Test fun paletteRoundTrip() {
        val list = listOf(Palette("a", "One", listOf(red, 0)), Palette("b", "Two", emptyList()))
        val bytes = ByteArrayOutputStream().also { PaletteSerializer.write(list, it) }.toByteArray()
        assertEquals(list, PaletteSerializer.read(ByteArrayInputStream(bytes)))
    }
}

package com.pixelgo

import com.pixelgo.data.DiskCelStore
import com.pixelgo.data.LegacyLayer
import com.pixelgo.data.LegacyProject
import com.pixelgo.data.LegacyPxg
import com.pixelgo.data.MetaSerializer
import com.pixelgo.data.Migration
import com.pixelgo.editor.CelKey
import com.pixelgo.editor.EditorEngine
import com.pixelgo.editor.Timing
import com.pixelgo.model.PixelProject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private lateinit var tmp: File

    @Before fun setUp() {
        tmp = Files.createTempDirectory("pixelgo").toFile()
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun celsDir() = File(tmp, "cels")

    /** Saves the way the app does: snapshot (UI thread) → write → mark clean. */
    private fun DiskCelStore.saveAll() {
        val snap = snapshotDirty()
        writeSnapshot(snap)
        markClean(snap)
    }

    @Test fun putReadAndEmptyCelsHaveNoFile() {
        val s = DiskCelStore(celsDir(), 4)
        s.put(CelKey(1, 1), intArrayOf(red, 0, 0, blue))
        assertArrayEquals(intArrayOf(red, 0, 0, blue), s.read(1, 1))
        assertTrue(File(celsDir(), "1_1.cel").exists())
        s.put(CelKey(1, 1), IntArray(4)) // all transparent = empty
        assertNull(s.read(1, 1))
        assertFalse(File(celsDir(), "1_1.cel").exists())
    }

    @Test fun editsSurviveSaveAndReopen() {
        val s = DiskCelStore(celsDir(), 4)
        s.edit(7, 9)[2] = red
        assertEquals(1, s.dirtyCount())
        s.saveAll()
        assertEquals(0, s.dirtyCount())
        val reopened = DiskCelStore(celsDir(), 4)
        assertEquals(red, reopened.read(7, 9)!![2])
    }

    @Test fun erasingACelToNothingDeletesItsFile() {
        val s = DiskCelStore(celsDir(), 4)
        s.edit(1, 1)[0] = red
        s.saveAll()
        assertTrue(File(celsDir(), "1_1.cel").exists())
        s.edit(1, 1)[0] = 0
        s.saveAll()
        assertFalse(File(celsDir(), "1_1.cel").exists())
        assertNull(DiskCelStore(celsDir(), 4).read(1, 1))
    }

    @Test fun editDuringSaveKeepsCelDirty() {
        val s = DiskCelStore(celsDir(), 4)
        s.edit(1, 1)[0] = red
        val snap = s.snapshotDirty()
        s.edit(1, 1)[1] = blue          // edited while the save was "in flight"
        s.writeSnapshot(snap)
        s.markClean(snap)
        assertEquals(1, s.dirtyCount()) // still needs saving
        s.saveAll()
        assertEquals(blue, DiskCelStore(celsDir(), 4).read(1, 1)!![1])
    }

    @Test fun cacheStaysSmallButNeverDropsUnsavedEdits() {
        // Tiny budget: 64 cels of 4 ints.
        val s = DiskCelStore(celsDir(), 4, cacheBytes = 64L * 16)
        s.edit(1, 1)[0] = red                       // dirty, never saved
        for (f in 2L..500L) s.put(CelKey(1, f), intArrayOf(blue, 0, 0, 0))
        for (f in 2L..500L) assertEquals(blue, s.read(1, f)!![0]) // forces lots of eviction
        assertEquals(red, s.read(1, 1)!![0])         // dirty cel was kept in memory
        val snap = s.snapshotDirty()
        assertEquals(1, snap.size)
        assertEquals(red, snap[0].pixels!![0])
    }

    @Test fun copyFromMemoryAndFromDisk() {
        val s = DiskCelStore(celsDir(), 4)
        s.edit(1, 1)[0] = red                       // only in memory (dirty)
        s.copy(CelKey(1, 1), CelKey(1, 2))
        s.put(CelKey(2, 1), intArrayOf(0, blue, 0, 0)) // only on disk
        s.copy(CelKey(2, 1), CelKey(2, 2))
        s.copy(CelKey(9, 9), CelKey(2, 3))             // empty source = empty copy
        val reopened = DiskCelStore(celsDir(), 4)
        assertEquals(red, reopened.read(1, 2)!![0])
        assertEquals(blue, reopened.read(2, 2)!![1])
        assertNull(reopened.read(2, 3))
    }

    @Test fun garbageCollectionKeepsReferencedAndNewCels() {
        val s = DiskCelStore(celsDir(), 4)
        val px = intArrayOf(red, 0, 0, 0)
        s.put(CelKey(1, 1), px)  // referenced
        s.put(CelKey(1, 2), px)  // frame 2 no longer referenced
        s.put(CelKey(3, 1), px)  // layer 3 no longer referenced
        s.put(CelKey(1, 10), px) // created after the references were collected (>= bound)
        val removed = s.collectGarbage(setOf(1L), setOf(1L), layerBound = 5, frameBound = 10)
        assertEquals(2, removed)
        assertEquals(setOf(CelKey(1, 1), CelKey(1, 10)), s.keysOnDisk())
    }

    @Test fun deletedFrameCanBeUndoneAfterASave() {
        val p = PixelProject.create("t", "T", 2, 2)
        val s = DiskCelStore(celsDir(), 4)
        val e = EditorEngine(p, s)
        e.addFrame()
        e.dot(0, 0, red)                 // on frame 2
        e.deleteFrame()
        // Save with references from the project AND its undo history.
        val layers = HashSet<Long>(); val frames = HashSet<Long>()
        e.collectRefs(layers, frames)
        s.saveAll()
        s.collectGarbage(layers, frames, p.nextLayerId, p.nextFrameUid)
        e.undo()                         // frame 2 comes back with its pixels
        assertEquals(red, e.px(0, 0))
    }

    @Test fun legacyProjectMigratesExactly() {
        val w = 3; val h = 2
        val c0 = IntArray(w * h).also { it[0] = red }
        val c1 = IntArray(w * h).also { it[5] = blue }
        val empty = IntArray(w * h)
        val legacy = LegacyProject(
            name = "Old one", width = w, height = h, modified = 1234L, fps = 12,
            holds = intArrayOf(1, 3), currentFrame = 1, activeLayer = 1, nextLayerId = 7,
            layers = listOf(
                LegacyLayer(3, "Bottom", true, listOf(c0, empty)),
                LegacyLayer(5, "Top", false, listOf(empty, c1)),
            ),
        )
        val pxg = File(tmp, "abc.pxg")
        pxg.outputStream().use { LegacyPxg.write(legacy, it) }
        val folder = File(tmp, "abc")
        Migration.migrate(pxg, folder, "abc")

        assertTrue(pxg.exists()) // original untouched
        val p = File(folder, Migration.META_FILE).inputStream().use { MetaSerializer.read("abc", it) }
        assertEquals("Old one", p.name)
        assertEquals(listOf(3L, 5L), p.layers.map { it.id })
        assertFalse(p.layers[1].visible)
        assertEquals(listOf(1, 3), p.frames.map { it.hold })
        assertEquals(12, p.fps)
        assertEquals(1, p.currentFrameIndex)
        assertEquals(1, p.activeLayerIndex)
        assertTrue(p.nextLayerId >= 7)
        val header = File(folder, Migration.META_FILE).inputStream().use { MetaSerializer.readHeader(it) }
        assertEquals(1234L, header.modified) // gallery order preserved
        val s = DiskCelStore(File(folder, Migration.CELS_DIR), w * h)
        assertArrayEquals(c0, s.read(3, p.frames[0].uid))
        assertArrayEquals(c1, s.read(5, p.frames[1].uid))
        assertNull(s.read(3, p.frames[1].uid)) // empty cels are not stored
        assertEquals(2, s.keysOnDisk().size)
    }

    @Test fun timingLookups() {
        val t = Timing(intArrayOf(2, 1, 3)) // ticks: [0,2) [2,3) [3,6)
        assertEquals(6L, t.totalTicks)
        assertEquals(0, t.frameAtTick(0)); assertEquals(0, t.frameAtTick(1))
        assertEquals(1, t.frameAtTick(2))
        assertEquals(2, t.frameAtTick(3)); assertEquals(2, t.frameAtTick(5))
        assertEquals(2, t.frameAtTick(99)); assertEquals(0, t.frameAtTick(-5))
        assertEquals(3L, t.startTick(2)); assertEquals(6L, t.endTick(2))
        assertEquals(500L, Timing.ticksToMs(30, 60))
        assertEquals(30L, Timing.msToTicks(500, 60))
        assertEquals("1:05.50", Timing.formatMs(65_500))
    }

    @Test fun tenThousandFramesStayFast() {
        val e = testEngine(4, 4)
        e.dot(0, 0, red)
        val start = System.nanoTime()
        repeat(9_999) { e.duplicateFrame(toEnd = true) }
        e.setFrameHold(5_000, 999)
        val t = e.project.timing
        assertEquals(10_000, e.project.frames.size)
        assertEquals(9_999L + 999L, t.totalTicks)
        assertEquals(5_000, t.frameAtTick(t.startTick(5_000) + 500))
        assertNotNull(e.store.read(e.project.layers[0].id, e.project.frames[9_999].uid))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 20_000)
    }
}

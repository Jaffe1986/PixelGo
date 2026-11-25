package com.pixelgo

import com.pixelgo.data.MetaSerializer
import com.pixelgo.editor.AudioMixer
import com.pixelgo.editor.ClipGain
import com.pixelgo.editor.DECLICK_MS
import com.pixelgo.editor.EditorEngine
import com.pixelgo.editor.MemoryPcm
import com.pixelgo.model.AudioAsset
import com.pixelgo.model.AudioClip
import com.pixelgo.model.GainPoint
import com.pixelgo.model.SoundTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Fades, volume lines, crossfades and the section edits (cut / close gap / duplicate…). */
class SoundEditTest {

    /** 10 s of a constant level 16 000 (about 0.49), so gains are easy to read off. */
    private val flat = MemoryPcm(ShortArray(480_000) { 16_000 }, 1)
    private val level = 16_000 / 32_768f

    private fun mixAt(clips: List<AudioClip>, ms: Double, tracks: List<SoundTrack> = listOf(SoundTrack(1, "A"))): Float {
        val out = ShortArray(2)
        AudioMixer(tracks, clips) { flat }.mix((ms * 48).toLong(), 1, out)
        return out[0] / 32_767f
    }

    private fun gainAt(clips: List<AudioClip>, ms: Double) = mixAt(clips, ms) / level

    /** An engine with a 10 s sound ("asset 1") and two tracks (1 and 2). */
    private fun engine(): EditorEngine {
        val e = testEngine()
        val id = e.newAssetId()
        e.addAsset(AudioAsset(id, "Song", 10_000, 1))
        e.addTrack("A")
        e.addTrack("B")
        return e
    }

    private fun EditorEngine.put(track: Long, startMs: Long, inMs: Long, outMs: Long): AudioClip {
        val c = addClip(track, "x", 1, startMs, inMs)!!
        changeClip(c.copy(outMs = outMs))
        return clip(c.id)!!
    }

    private fun EditorEngine.snapshot() = project.clips.sortedWith(compareBy({ it.trackId }, { it.startMs })).map {
        listOf(it.trackId, it.startMs, it.inMs, it.outMs, it.fadeInMs, it.fadeOutMs)
    }

    /** Undo puts everything back, redo repeats it exactly. */
    private fun EditorEngine.checkUndoRedo(before: Any, after: Any) {
        undo()
        assertEquals(before, snapshot())
        redo()
        assertEquals(after, snapshot())
    }

    // ---- Levels -----------------------------------------------------------------------------

    @Test fun fadesFollowAnSCurve() {
        val c = AudioClip(1, 1, 1, startMs = 0, inMs = 0, outMs = 1_000, fadeInMs = 100, fadeOutMs = 200)
        assertEquals(0f, gainAt(listOf(c), 0.0), 0.01f)
        assertEquals(0.5f, gainAt(listOf(c), 50.0), 0.02f)           // half way up the S-curve
        assertEquals(1f, gainAt(listOf(c), 500.0), 0.01f)
        assertEquals(0.5f, gainAt(listOf(c), 900.0), 0.02f)          // half way through the fade-out
        assertEquals(0f, gainAt(listOf(c), 999.9), 0.01f)
    }

    @Test fun fadesLongerThanTheClipAreShortenedInProportion() {
        val c = AudioClip(1, 1, 1, 0, 0, 1_000, fadeInMs = 1_500, fadeOutMs = 500) // 2 s of fades in 1 s
        val g = ClipGain(c)
        assertEquals(1f, g.atOffsetMs(750.0), 0.01f)                   // fade-in now ends at 750 ms
        assertEquals(0.5f, g.atOffsetMs(875.0), 0.02f)                 // fade-out over the last 250 ms
    }

    @Test fun overlappingClipsCrossfadeWithEqualPower() {
        val a = AudioClip(1, 1, 1, startMs = 0, inMs = 0, outMs = 1_000)
        val b = AudioClip(2, 1, 1, startMs = 800, inMs = 0, outMs = 1_000) // overlaps a for 200 ms
        val gains = ClipGain.all(listOf(SoundTrack(1, "A")), listOf(a, b))
        val mid = sin(PI / 4).toFloat()
        assertEquals(mid, gains.getValue(1).atOffsetMs(900.0), 0.01f)
        assertEquals(mid, gains.getValue(2).atOffsetMs(100.0), 0.01f)
        assertEquals(1f, gains.getValue(1).atOffsetMs(700.0), 0.001f)     // before the overlap: untouched
        // Smooth: no jump where the crossfade starts.
        assertEquals(gainAt(listOf(a, b), 799.9), gainAt(listOf(a, b), 800.1), 0.02f)
        // Clips on different tracks never crossfade.
        val other = ClipGain.all(listOf(SoundTrack(1, "A"), SoundTrack(2, "B")), listOf(a, b.copy(trackId = 2)))
        assertEquals(1f, other.getValue(1).atOffsetMs(900.0), 0.001f)
    }

    @Test fun volumeLineFollowsTheSoundNotTheClip() {
        // Volume line: full at 0, silent at 2 s of the SOUND.
        val c = AudioClip(1, 1, 1, startMs = 0, inMs = 0, outMs = 2_000, envelope = listOf(GainPoint(0, 1f), GainPoint(2_000, 0f)))
        assertEquals(0.5f, gainAt(listOf(c), 1_000.0), 0.01f)
        // Trim 500 ms off the start and move the clip: the same sound moment keeps its level.
        val trimmed = c.copy(startMs = 3_000, inMs = 500)
        assertEquals(0.5f, gainAt(listOf(trimmed), 3_500.0), 0.01f)  // sound time 1 000 ms
    }

    @Test fun levelsSurviveSavingAndOldFilesStillLoad() {
        val e = engine()
        val c = e.put(1, 0, 0, 3_000)
        e.changeClip(c.copy(fadeInMs = 120, fadeOutMs = 340, envelope = listOf(GainPoint(500, 0.25f), GainPoint(900, 2f))))
        val plain = e.put(2, 0, 0, 1_000)
        val bytes = ByteArrayOutputStream().also { MetaSerializer.write(e.project, 1L, it) }.toByteArray()
        val p = MetaSerializer.read("t", ByteArrayInputStream(bytes))
        assertEquals(e.project.clips, p.clips)
        assertEquals(plain, p.clips.first { it.id == plain.id })   // no levels: plain defaults
    }

    // ---- Section edits ------------------------------------------------------------------------

    @Test fun deleteLeavesAGapWithSoftEdges() {
        val e = engine()
        e.put(1, 0, 0, 3_000)
        val before = e.snapshot()
        assertTrue(e.deleteSection(setOf(1L), 1_000, 2_000))
        assertEquals(
            listOf(listOf(1L, 0L, 0L, 1_000L, 0L, DECLICK_MS), listOf(1L, 2_000L, 2_000L, 3_000L, DECLICK_MS, 0L)),
            e.snapshot(),
        )
        assertEquals(0f, mixAt(e.project.clips, 1_500.0), 0f)            // silence in the gap
        e.checkUndoRedo(before, e.snapshot())
    }

    @Test fun closeGapPullsLaterSoundLeftOnTheChosenTracksOnly() {
        val e = engine()
        e.put(1, 0, 0, 3_000)
        e.put(1, 5_000, 0, 1_000)
        e.put(2, 5_000, 0, 1_000)                                       // another track: stays put
        val before = e.snapshot()
        assertTrue(e.deleteSection(setOf(1L), 1_000, 2_000, closeGap = true))
        val after = e.snapshot()
        assertEquals(listOf(0L, 1_000L, 4_000L), after.filter { it[0] == 1L }.map { it[1] }) // starts on track 1
        assertEquals(listOf(5_000L), after.filter { it[0] == 2L }.map { it[1] })
        e.checkUndoRedo(before, after)
    }

    @Test fun insertSilenceSplitsAndMovesLaterSoundRight() {
        val e = engine()
        e.put(1, 0, 0, 2_000)
        val before = e.snapshot()
        assertTrue(e.insertSilence(setOf(1L), 500, 1_000))
        assertEquals(
            listOf(listOf(1L, 0L, 0L, 500L, 0L, DECLICK_MS), listOf(1L, 1_500L, 500L, 2_000L, DECLICK_MS, 0L)),
            e.snapshot(),
        )
        e.checkUndoRedo(before, e.snapshot())
    }

    @Test fun duplicateCopiesASectionOverWhatFollows() {
        val e = engine()
        e.put(1, 0, 0, 4_000)                                           // sound 0..4 s
        val before = e.snapshot()
        val added = e.duplicateSection(setOf(1L), 1_000, 2_000)
        assertEquals(1, added.size)
        val copy = added[0]
        assertEquals(2_000L, copy.startMs)
        assertEquals(1_000L, copy.inMs)                                // plays the section's sound again
        assertEquals(2_000L, copy.outMs)
        // What used to play at 2..3 s is gone; 3..4 s is still there and nothing moved.
        val starts = e.snapshot().map { it[1] to it[2] }
        assertEquals(listOf(0L to 0L, 2_000L to 1_000L, 3_000L to 3_000L), starts)
        e.checkUndoRedo(before, e.snapshot())
    }

    @Test fun splitAtCutsEveryChosenTrackAndKeepsFadesOnTheOuterEdges() {
        val e = engine()
        val a = e.put(1, 0, 0, 2_000)
        e.changeClip(a.copy(fadeInMs = 100, fadeOutMs = 100))
        e.put(2, 0, 0, 2_000)
        assertTrue(e.splitAt(setOf(1L, 2L), 1_000))
        assertEquals(4, e.project.clips.size)
        val t1 = e.project.clips.filter { it.trackId == 1L }.sortedBy { it.startMs }
        assertEquals(listOf(100L, 0L), t1.map { it.fadeInMs })
        assertEquals(listOf(0L, 100L), t1.map { it.fadeOutMs })
        e.undo()
        assertEquals(2, e.project.clips.size)
    }

    @Test fun processedSoundKeepsLevelsAndTheWholeActionIsOneUndoStep() {
        val e = engine()
        val c = e.put(1, 1_000, 500, 2_500)                              // plays sound 0.5..2.5 s at 1..3 s
        e.changeClip(c.copy(fadeInMs = 100, fadeOutMs = 300, envelope = listOf(GainPoint(1_000, 0.5f))))
        val original = e.clip(c.id)!!
        // Effects on the section 1..2 s at half speed: split it off, then swap in the new sound.
        e.beginGroup()
        e.splitSection(setOf(1L), 1_000, 2_000)
        val piece = e.clipsInside(setOf(1L), 1_000, 2_000).single()
        val id = e.newAssetId()
        e.addAsset(AudioAsset(id, "Song (edited)", 2_000, 1))
        val done = e.useProcessedSound(piece.id, id, 2_000, timeScale = 2.0, reversed = false)!!
        e.endGroup()
        assertEquals(listOf(0L, 2_000L), listOf(done.inMs, done.outMs))
        assertEquals(200L, done.fadeInMs)                                  // 100 ms, twice as long
        assertEquals(0L, done.fadeOutMs)                                   // the cut edge had none
        // The volume line point (sound time 1 000 ms = 500 ms into the piece) is now 1 000 ms in.
        assertEquals(0.5f, done.envelope.first { it.atMs == 1_000L }.gain, 0.001f)
        e.undo()                                                           // split + swap together
        assertEquals(listOf(original), e.project.clips)
        // A group that is cancelled (processing failed) leaves nothing behind.
        e.beginGroup()
        e.deleteSection(setOf(1L), 1_200, 1_300)
        e.cancelGroup()
        assertEquals(listOf(original), e.project.clips)
    }

    @Test fun reversedSoundMirrorsFadesAndVolumeLine() {
        val e = engine()
        val c = e.put(1, 0, 0, 1_000)
        e.changeClip(c.copy(fadeInMs = 50, fadeOutMs = 200, envelope = listOf(GainPoint(250, 2f))))
        val id = e.newAssetId()
        e.addAsset(AudioAsset(id, "rev", 1_000, 1))
        val r = e.useProcessedSound(c.id, id, 1_000, timeScale = 1.0, reversed = true)!!
        assertEquals(200L, r.fadeInMs)
        assertEquals(50L, r.fadeOutMs)
        assertEquals(2f, r.envelope.first { it.atMs == 750L }.gain, 0.001f)
    }

    @Test fun fadeAtAClipStartBecomesItsFadeElsewhereItsVolumeLine() {
        val e = engine()
        val c = e.put(1, 1_000, 0, 4_000)                                 // plays 1..5 s
        assertTrue(e.scaleSection(setOf(1L), 1_000, 1_500, 0f, 1f))      // fade in from its start
        assertEquals(500L, e.clip(c.id)!!.fadeInMs)
        assertTrue(e.scaleSection(setOf(1L), 2_000, 3_000, 0.5f, 0.5f))  // half volume in the middle
        val now = e.clip(c.id)!!
        assertTrue(now.envelope.isNotEmpty())
        assertEquals(0.5f, gainAt(e.project.clips, 2_500.0), 0.01f)
        assertEquals(1f, gainAt(e.project.clips, 1_800.0), 0.01f)       // untouched before…
        assertEquals(1f, gainAt(e.project.clips, 3_500.0), 0.01f)       // …and after
        // The edges ramp over a few ms instead of jumping.
        assertTrue(abs(gainAt(e.project.clips, 1_998.0) - 1f) > 0.05f || abs(gainAt(e.project.clips, 1_997.0) - 0.5f) > 0.05f)
        e.undo()
        assertTrue(e.clip(c.id)!!.envelope.isEmpty())
        e.redo()
        assertEquals(now, e.clip(c.id))
    }
}

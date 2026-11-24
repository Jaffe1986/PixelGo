package com.pixelgo

import com.pixelgo.data.MetaSerializer
import com.pixelgo.data.audio.PcmFile
import com.pixelgo.data.audio.PcmWriter
import com.pixelgo.data.audio.Peaks
import com.pixelgo.editor.AudioMixer
import com.pixelgo.editor.CountIn
import com.pixelgo.editor.Names
import com.pixelgo.editor.MemoryPcm
import com.pixelgo.editor.Resampler
import com.pixelgo.model.AudioAsset
import com.pixelgo.model.AudioClip
import com.pixelgo.model.SoundTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTest {

    private fun sine(freq: Double, rate: Int, frames: Int, channels: Int = 1) =
        FloatArray(frames * channels) { i -> sin(2 * PI * freq * (i / channels) / rate).toFloat() * 0.8f }

    private fun resample(input: FloatArray, inRate: Int, outRate: Int, channels: Int = 1): FloatArray {
        val r = Resampler(inRate, outRate, channels)
        val out = ArrayList<Float>()
        val collect: (FloatArray, Int) -> Unit = { buf, n -> for (i in 0 until n * channels) out += buf[i] }
        // feed in uneven chunks, like a decoder does
        var i = 0
        var chunk = 777
        while (i < input.size / channels) {
            val n = minOf(chunk, input.size / channels - i)
            r.process(input.copyOfRange(i * channels, (i + n) * channels), n, collect)
            i += n
            chunk = if (chunk == 777) 1500 else 777
        }
        r.flush(collect)
        return out.toFloatArray()
    }

    private fun zeroCrossings(x: FloatArray, from: Int, to: Int) =
        (from + 1 until to).count { (x[it - 1] < 0) != (x[it] < 0) }

    private fun rms(x: FloatArray, from: Int, to: Int) =
        sqrt((from until to).sumOf { (x[it] * x[it]).toDouble() } / (to - from))

    @Test fun upsample44100To48000KeepsPitchLengthAndLevel() {
        val out = resample(sine(1000.0, 44_100, 44_100), 44_100, 48_000)
        assertTrue("length ${out.size}", abs(out.size - 48_000) <= 1)
        // 1000 Hz = 2000 zero crossings per second; check a middle half-second
        val zc = zeroCrossings(out, 12_000, 36_000)
        assertTrue("crossings $zc", abs(zc - 1000) <= 2)
        val peak = (12_000 until 36_000).maxOf { abs(out[it]) }
        assertTrue("peak $peak", peak in 0.76f..0.84f)
    }

    @Test fun downsampleRemovesTonesTooHighForTheNewRate() {
        val high = resample(sine(30_000.0, 96_000, 96_000), 96_000, 48_000)
        assertTrue("aliasing rms ${rms(high, 2_000, 46_000)}", rms(high, 2_000, 46_000) < 0.02)
        val ok = resample(sine(1000.0, 96_000, 96_000), 96_000, 48_000)
        assertTrue(rms(ok, 2_000, 46_000) > 0.5)
    }

    @Test fun firstOutputSampleLinesUpWithFirstInput() {
        // An impulse at frame 0 should come out (mostly) at frame 0.
        val input = FloatArray(4_410).also { it[0] = 1f }
        val out = resample(input, 44_100, 48_000)
        val maxAt = out.indices.maxByOrNull { abs(out[it]) }!!
        assertEquals(0, maxAt)
    }

    @Test fun sameRateIsPassthrough() {
        val input = sine(440.0, 48_000, 1_000, channels = 2)
        assertTrue(resample(input, 48_000, 48_000, 2).contentEquals(input))
    }

    @Test fun mixerAppliesStartTrimVolumeAndMute() {
        val one = MemoryPcm(ShortArray(48_000) { 10_000 }, 1)     // 1 s of constant level
        val tracks = listOf(SoundTrack(1, "A"), SoundTrack(2, "B", muted = true))
        val clips = listOf(
            AudioClip(1, 1, 7, startMs = 100, inMs = 0, outMs = 200, volume = 0.5f), // 100..300 ms
            AudioClip(2, 2, 7, startMs = 0, inMs = 0, outMs = 1_000),                 // muted
        )
        val mixer = AudioMixer(tracks, clips) { one }
        assertEquals(300L * 48, mixer.endFrame)
        val out = ShortArray(400 * 48 * 2)
        mixer.mix(0, 400 * 48, out)
        assertEquals(0, out[2 * (50 * 48)].toInt())                // before the clip
        assertTrue(abs(out[2 * (150 * 48)] - 5_000) <= 2)         // half volume
        assertEquals(out[2 * (150 * 48)], out[2 * (150 * 48) + 1]) // mono -> both sides
        assertEquals(0, out[2 * (350 * 48)].toInt())               // after the trimmed end
    }

    @Test fun mixerTrimStartPlaysFromInsideTheSound() {
        val ramp = MemoryPcm(ShortArray(48_000) { (it / 48).toShort() }, 1) // value = ms
        val mixer = AudioMixer(listOf(SoundTrack(1, "A")), listOf(AudioClip(1, 1, 1, 0, inMs = 500, outMs = 600))) { ramp }
        val out = ShortArray(2)
        mixer.mix(10 * 48, 1, out)                                // 10 ms into the clip
        assertTrue(abs(out[0] - 510) <= 1)
    }

    @Test fun mixerClipsInsteadOfWrapping() {
        val loud = MemoryPcm(ShortArray(4_800) { 30_000 }, 1)
        val clips = listOf(AudioClip(1, 1, 1, 0, 0, 100), AudioClip(2, 1, 1, 0, 0, 100))
        val out = ShortArray(2)
        AudioMixer(listOf(SoundTrack(1, "A")), clips) { loud }.mix(0, 1, out)
        assertEquals(32_767, out[0].toInt())
    }

    @Test fun pcmFileRoundTripWithPeaks() {
        val dir = Files.createTempDirectory("pcm").toFile()
        try {
            val f = File(dir, "1.pcm")
            val p = File(dir, "1.peaks")
            PcmWriter(f, p, 2).use { w ->
                w.write(sine(440.0, 48_000, 48_000, 2), 96_000)
            }
            PcmFile(f).use { pcm ->
                assertEquals(48_000L, pcm.frames)
                assertEquals(2, pcm.channels)
                val buf = FloatArray(200)
                pcm.readStereo(100, 100, buf)
                val expect = sin(2 * PI * 440 * 100 / 48_000).toFloat() * 0.8f
                assertTrue(abs(buf[0] - expect) < 0.001f)
                pcm.readStereo(47_990, 100, buf)                   // past the end = silence
                assertEquals(0f, buf[30])
            }
            val peaks = Peaks.read(p)
            assertEquals(200, peaks.size)                          // 100 × (min, max)
            assertTrue(Peaks.level(peaks, 0, 1000) in 0.75f..0.82f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun audioEditsUndoRedo() {
        val e = testEngine()
        val id = e.newAssetId()
        e.addAsset(AudioAsset(id, "Song", 2_000, 2))
        val clip = e.addClip(null, "Music", id, 500)!!
        assertEquals(1, e.project.tracks.size)
        assertEquals(2_500L, e.project.audioEndMs())
        assertTrue(e.splitClip(clip.id, 1_000))
        assertEquals(2, e.project.clips.size)
        assertEquals(listOf(500L, 1_000L), e.project.clips.map { it.startMs }.sorted())
        e.undo()                                                    // un-split
        assertEquals(1, e.project.clips.size)
        assertEquals(2_000L, e.project.clips[0].durationMs)
        e.deleteTrack(e.project.tracks[0].id)
        assertTrue(e.project.clips.isEmpty())
        val refs = HashSet<Long>()
        e.collectAssetRefs(refs)
        assertTrue(id in refs)                                      // still needed for undo
        e.undo()                                                    // track + clip come back
        assertEquals(1, e.project.clips.size)
        e.undo()                                                    // remove the clip + its new track
        assertTrue(e.project.clips.isEmpty())
        assertTrue(e.project.tracks.isEmpty())
        e.redo()
        assertEquals(1, e.project.clips.size)
    }

    @Test fun clipCanSkipTheStartOfItsSound() {
        val e = testEngine()
        val id = e.newAssetId()
        e.addAsset(AudioAsset(id, "Recording", 5_000, 1))
        assertEquals(null, e.addClip(null, "Voice", id, 0, inMs = 5_000))   // nothing left
        assertTrue(e.project.tracks.isEmpty())                               // and no stray track
        val c = e.addClip(null, "Voice", id, 250, inMs = 3_300)!!
        assertEquals(250L, c.startMs)
        assertEquals(3_300L, c.inMs)
        assertEquals(1_700L, c.durationMs)
        assertEquals(1_950L, e.project.audioEndMs())
        e.undo()
        assertTrue(e.project.clips.isEmpty())
        e.redo()
        assertEquals(c, e.clip(c.id))
    }

    @Test fun defaultNamesDontRepeat() {
        assertEquals("Voice", Names.unique("Voice", listOf("Music")))
        assertEquals("Voice 2", Names.unique("Voice", listOf("Voice")))
        assertEquals("Voice 3", Names.unique("Voice", listOf("Voice", "Voice 2")))
        assertEquals("Recording 1", Names.numbered("Recording", listOf("Song")))
        // After "Recording 2" was deleted, the next is still 4, not a second "Recording 3".
        assertEquals("Recording 4", Names.numbered("Recording", listOf("Song", "Recording 1", "Recording 3")))
        val e = testEngine()
        val id = e.newAssetId()
        e.addAsset(AudioAsset(id, "Take", 1_000, 1))
        e.addClip(null, "Voice", id, 0)
        e.addClip(null, "Voice", id, 0)
        assertEquals(listOf("Voice", "Voice 2"), e.project.tracks.map { it.name })
    }

    @Test fun countInIsFoundInARecording() {
        // A "recording": room noise, then the count-in heard 12 345 frames in, quieter and with an echo.
        val ticks = CountIn.sound()
        val lead = 12_345
        val n = lead + ticks.size / 2 + 48_000
        val rnd = java.util.Random(7)
        val rec = FloatArray(n) { (rnd.nextGaussian() * 0.003).toFloat() }
        for (i in 0 until ticks.size / 2) {
            rec[lead + i] += ticks[2 * i] / 32768f * 0.2f
            if (lead + i + 480 < n) rec[lead + i + 480] += ticks[2 * i] / 32768f * 0.05f
        }
        // Within 1 ms (48 frames): an echo shifts the measured onset slightly (here ~0.3 ms).
        assertEquals(lead.toDouble(), CountIn.locate(rec, n)!!, 48.0)
        // It may start before the recording's first sample is captured... (negative lead)
        val early = rec.copyOfRange(lead + 4_000, n)
        assertEquals(-4_000.0, CountIn.locate(early, early.size)!!, 48.0)
        // ...but noise alone, or a single loud beep, is not a count-in.
        val noise = FloatArray(n) { (rnd.nextGaussian() * 0.003).toFloat() }
        assertEquals(null, CountIn.locate(noise, n))
        for (i in 0 until 4_800) noise[60_000 + i] += (0.4 * sin(2 * PI * 1000 * i / 48_000)).toFloat()
        assertEquals(null, CountIn.locate(noise, n))
    }

    @Test fun clipMoveIsOneUndoStepEvenWhenDraggedLive() {
        val e = testEngine()
        val id = e.newAssetId()
        e.addAsset(AudioAsset(id, "Beep", 100, 1))
        val c = e.addClip(null, "FX", id, 0)!!
        for (ms in 10L..300L step 10) e.setClipLive(c.copy(startMs = ms))
        e.commitClip(c)
        assertEquals(300L, e.clip(c.id)!!.startMs)
        e.undo()
        assertEquals(0L, e.clip(c.id)!!.startMs)
    }

    @Test fun loopToAudioExtendsTotalLength() {
        val e = testEngine()                                        // 1 frame at 8 fps = 125 ms
        val id = e.newAssetId()
        e.addAsset(AudioAsset(id, "Song", 3_000, 2))
        e.addClip(null, "Music", id, 0)
        assertEquals(125L, e.project.totalMs())
        e.project.loopToAudio = true
        assertEquals(3_000L, e.project.totalMs())
        e.changeTrack(e.project.tracks[0].copy(muted = true))       // muted audio doesn't count
        assertEquals(125L, e.project.totalMs())
    }

    @Test fun audioSurvivesMetaRoundTrip() {
        val e = testEngine()
        val id = e.newAssetId()
        e.addAsset(AudioAsset(id, "Voice 1", 1_234, 1))
        val c = e.addClip(null, "Voice", id, 250)!!
        e.changeClip(c.copy(volume = 0.7f, inMs = 10))
        e.project.loopToAudio = true
        val bytes = ByteArrayOutputStream().also { MetaSerializer.write(e.project, 1L, it) }.toByteArray()
        val p = MetaSerializer.read("t", ByteArrayInputStream(bytes))
        assertTrue(p.loopToAudio)
        assertEquals(e.project.tracks, p.tracks)
        assertEquals(e.project.clips, p.clips)
        assertEquals(e.project.assets, p.assets)
        assertTrue(p.nextClipId > c.id && p.nextAssetId > id)
        assertFalse(p.tracks.first().muted)
    }
}

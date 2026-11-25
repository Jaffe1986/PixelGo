package com.pixelgo

import com.pixelgo.data.audio.PcmFile
import com.pixelgo.data.audio.PcmWriter
import com.pixelgo.data.audio.SoundRender
import com.pixelgo.editor.MemoryPcm
import com.pixelgo.editor.audiofx.EffectSettings
import com.pixelgo.editor.audiofx.Effects
import com.pixelgo.editor.audiofx.Equalizer
import com.pixelgo.editor.audiofx.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** The sound effects: EQ, noise gate, speed / pitch, reverse. */
class EffectsTest {
    private val rate = 48_000

    private fun sine(freq: Double, frames: Int, amp: Double = 0.5) =
        FloatArray(frames) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    /** Runs mono [x] through [stages] in uneven chunks, like the renderer does. */
    private fun run(stages: List<Stage>, x: FloatArray): FloatArray {
        val out = ArrayList<Float>()
        fun feed(i: Int, d: FloatArray, n: Int) {
            if (i == stages.size) for (k in 0 until n) out += d[k] else stages[i].process(d, n) { o, m -> feed(i + 1, o, m) }
        }
        var pos = 0
        var chunk = 777
        while (pos < x.size) {
            val n = minOf(chunk, x.size - pos)
            feed(0, x.copyOfRange(pos, pos + n), n)
            pos += n
            chunk = if (chunk == 777) 1500 else 777
        }
        for (i in stages.indices) stages[i].flush { o, m -> feed(i + 1, o, m) }
        return out.toFloatArray()
    }

    private fun rmsDb(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return 10 * log10(s / (to - from) + 1e-20)
    }

    private fun frequency(x: FloatArray, from: Int, to: Int): Double {
        var n = 0
        for (i in from + 1 until to) if ((x[i - 1] < 0) != (x[i] < 0)) n++
        return n / 2.0 / ((to - from).toDouble() / rate)
    }

    private fun eqDb(eq: Equalizer, freq: Double) = eq.filters.sumOf { it.responseDb(freq) }

    @Test fun lowCutAndThreeBandEq() {
        val cut = Equalizer(1, 200, 0f, 0f, 0f)
        assertTrue(eqDb(cut, 50.0) < -30)                        // rumble gone
        assertEquals(0.0, eqDb(cut, 1_000.0), 0.5)                // voice untouched
        val eq = Equalizer(1, 0, 6f, 6f, -6f)
        assertEquals(6.0, eqDb(eq, 40.0), 0.7)                     // bass shelf
        assertEquals(6.0, eqDb(eq, 1_000.0), 0.8)                 // mid peak (shelves barely reach)
        assertEquals(-6.0, eqDb(eq, 16_000.0), 0.7)               // treble shelf
        // A real signal agrees with the formula.
        val out = run(Effects.chain(EffectSettings(lowCutHz = 200), 1), sine(50.0, rate))
        assertTrue(rmsDb(out, rate / 2, rate) < rmsDb(sine(50.0, rate), rate / 2, rate) - 30)
    }

    @Test fun noiseGateSilencesNoiseButKeepsWordsInPlace() {
        val rnd = Random(1)
        val x = FloatArray(2 * rate) { (rnd.nextGaussian() * 0.003).toFloat() }     // about -50 dB hiss
        val burst = sine(440.0, rate / 2)
        for (i in burst.indices) x[rate / 2 + i] += burst[i]                    // a "word" 0.5..1.0 s
        val out = run(Effects.chain(EffectSettings(gateDb = -30f), 1), x)
        assertEquals(x.size, out.size)
        assertTrue(rmsDb(out, (1.5 * rate).toInt(), (1.9 * rate).toInt()) < -80)  // hiss gone half a second later
        assertTrue(rmsDb(out, (1.2 * rate).toInt(), (1.3 * rate).toInt()) < -60)  // and mostly gone after 0.2 s
        assertEquals(rmsDb(x, (0.6 * rate).toInt(), (0.9 * rate).toInt()), rmsDb(out, (0.6 * rate).toInt(), (0.9 * rate).toInt()), 0.3)
        // Not shifted: the burst starts where it did (the look-ahead is compensated).
        val first = out.indexOfFirst { kotlin.math.abs(it) > 0.1f }
        assertEquals(rate / 2.0, first.toDouble(), 48.0)
    }

    @Test fun slowerKeepsPitch() {
        val out = run(Effects.chain(EffectSettings(speed = 0.5f), 1), sine(1_000.0, rate))
        assertEquals(2.0 * rate, out.size.toDouble(), 2.0)
        assertEquals(1_000.0, frequency(out, rate / 2, 3 * rate / 2), 10.0)
        // Steady level: no warbling dips in the middle.
        val a = rmsDb(out, rate / 2, rate)
        val b = rmsDb(out, rate, 3 * rate / 2)
        assertEquals(a, b, 1.0)
        assertEquals(rmsDb(sine(1_000.0, rate), 0, rate), a, 1.5)
    }

    @Test fun pitchUpAnOctaveKeepsLength() {
        val out = run(Effects.chain(EffectSettings(pitch = 12f), 1), sine(500.0, rate))
        assertEquals(rate.toDouble(), out.size.toDouble(), 48.0)  // within 1 ms
        assertEquals(1_000.0, frequency(out, rate / 4, 3 * rate / 4), 15.0)
    }

    @Test fun speedAndPitchChangesKeepSoundsOnTheBeat() {
        // A 1 kHz burst starting at 0.5 s, after silence.
        val x = FloatArray(2 * rate)
        val b = sine(1_000.0, rate / 5)
        for (i in b.indices) x[rate / 2 + i] = b[i]
        fun onsetMs(y: FloatArray) = y.indexOfFirst { kotlin.math.abs(it) > 0.2f } * 1000.0 / rate
        // Within 12 ms (under a frame at 60 fps): stretching copies each 40 ms window at normal
        // speed, so a sudden start shows up a little early in the overlapping windows.
        assertEquals(1_000.0, onsetMs(run(Effects.chain(EffectSettings(speed = 0.5f), 1), x)), 12.0)
        assertEquals(500.0, onsetMs(run(Effects.chain(EffectSettings(pitch = 12f), 1), x)), 12.0)
        assertEquals(500.0, onsetMs(run(Effects.chain(EffectSettings(pitch = -7f), 1), x)), 12.0)
        assertEquals(250.0, onsetMs(run(Effects.chain(EffectSettings(speed = 2f), 1), x)), 12.0)
    }

    @Test fun stretchedSoundStartsAtFullLevel() {
        val out = run(Effects.chain(EffectSettings(speed = 0.5f), 1), sine(1_000.0, rate))
        // The first 20 ms isn't faded in by the overlap-add.
        assertEquals(rmsDb(out, rate / 2, rate), rmsDb(out, 0, rate / 50), 1.5)
    }

    @Test fun tapeStyleSpeedChangesPitchToo() {
        val out = run(Effects.chain(EffectSettings(speed = 2f, tapeStyle = true), 1), sine(500.0, rate))
        assertEquals(rate / 2.0, out.size.toDouble(), 2.0)
        assertEquals(1_000.0, frequency(out, rate / 8, 3 * rate / 8), 10.0)
    }

    @Test fun renderReversesAndKeepsTheChannelCount() {
        val ramp = MemoryPcm(ShortArray(4_800 * 2) { (if (it % 2 == 0) it / 2 else -(it / 2)).toShort() }, 2)
        val dir = Files.createTempDirectory("fx").toFile()
        val writer = PcmWriter(java.io.File(dir, "r.pcm"), java.io.File(dir, "r.peaks"), 2)
        SoundRender.process(ramp, 100, 1_100, reverse = true, stages = emptyList(), writer = writer)
        writer.close()
        PcmFile(java.io.File(dir, "r.pcm")).use { p ->
            assertEquals(1_000L, p.frames)
            val buf = FloatArray(2 * 1_000)
            p.readStereo(0, 1_000, buf)
            assertEquals(1_099f, buf[0] * 32768f, 1.5f)              // last frame first
            assertEquals(-1_099f, buf[1] * 32768f, 1.5f)            // sides kept apart
            assertEquals(100f, buf[2 * 999] * 32768f, 1.5f)
        }
        dir.deleteRecursively()
    }

    @Test fun neutralSettingsDoNothing() {
        assertTrue(EffectSettings().isNeutral)
        assertTrue(Effects.chain(EffectSettings(), 2).isEmpty())
        // RMS is untouched by an empty chain.
        val x = sine(300.0, 4_800)
        assertEquals(rmsDb(x, 0, x.size), rmsDb(run(emptyList(), x), 0, x.size), 1e-9)
        assertEquals(sqrt(0.125), Math.sqrt(x.sumOf { it.toDouble() * it } / x.size), 0.01)
    }
}

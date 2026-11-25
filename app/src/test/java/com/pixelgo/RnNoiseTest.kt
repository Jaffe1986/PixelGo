package com.pixelgo

import com.pixelgo.editor.audiofx.Stage
import com.pixelgo.editor.denoise.DenoiseStage
import com.pixelgo.editor.denoise.RnnModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The AI noise removal (Kotlin port of RNNoise) against ffmpeg's arnndn filter running the same
 * weights: resources/rnnoise/noisy.wav and clean.wav, made with tools/rnnoise_reference.py.
 * (ffmpeg's port skips RNNoise's 1/960 FFT scaling and its 16-bit input scale, so the reference
 * is made with the input scaled by 32768/960 and the output scaled back: then it's the original.)
 */
class RnNoiseTest {
    private val rate = 48_000
    private val model = RnnModel(File("src/main/assets/rnnoise.bin").readBytes())

    /** Mono WAV, 16-bit or 32-bit float. */
    private fun wav(name: String): FloatArray {
        val bytes = javaClass.getResourceAsStream("/rnnoise/$name")!!.readBytes()
        fun le(at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val float = ByteBuffer.wrap(bytes, 20, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() == 3
        var i = 12
        while (String(bytes, i, 4, Charsets.US_ASCII) != "data") i += 8 + le(i + 4)
        val size = le(i + 4)
        val b = ByteBuffer.wrap(bytes, i + 8, size).order(ByteOrder.LITTLE_ENDIAN)
        return if (float) FloatArray(size / 4) { b.float } else FloatArray(size / 2) { b.short / 32768f }
    }

    private fun run(stage: Stage, x: FloatArray, channels: Int = 1): FloatArray {
        val out = ArrayList<Float>()
        var pos = 0
        while (pos < x.size) {
            val n = minOf(1_000 * channels, x.size - pos)
            stage.process(x.copyOfRange(pos, pos + n), n / channels) { o, m -> for (k in 0 until m * channels) out += o[k] }
            pos += n
        }
        stage.flush { o, m -> for (k in 0 until m * channels) out += o[k] }
        return out.toFloatArray()
    }

    private fun rmsDb(x: FloatArray, fromS: Double, toS: Double): Double {
        val a = (fromS * rate).toInt()
        val b = (toS * rate).toInt()
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return 10 * log10(s / (b - a) + 1e-20)
    }

    @Test fun matchesTheReferenceImplementation() {
        val noisy = wav("noisy.wav")
        val ref = wav("clean.wav")
        val out = run(DenoiseStage(1, 1f, model), noisy)
        assertEquals(noisy.size, out.size)                       // same length: no delay added
        // ffmpeg keeps RNNoise's 10 ms delay; ours takes it out. Compare with both alignments.
        fun residualDb(lag: Int): Double {
            var sig = 0.0
            var err = 0.0
            for (t in (0.1 * rate).toInt() until (1.9 * rate).toInt()) {
                val r = ref[t + lag].toDouble()
                sig += r * r
                err += (out[t] - r) * (out[t] - r)
            }
            return 10 * log10(err / sig)
        }
        val best = minOf(residualDb(0), residualDb(480))
        assertTrue("difference from the reference: %.1f dB".format(best), best < -60)
    }

    @Test fun removesNoiseAndKeepsTheVoice() {
        val noisy = wav("noisy.wav")
        val out = run(DenoiseStage(1, 1f, model), noisy)
        assertTrue(rmsDb(out, 1.5, 1.95) < rmsDb(noisy, 1.5, 1.95) - 20)      // noise on its own: much quieter
        assertEquals(rmsDb(noisy, 0.6, 1.2), rmsDb(out, 0.6, 1.2), 1.5)       // the voice: about the same
    }

    @Test fun lowerStrengthRemovesLess() {
        val noisy = wav("noisy.wav")
        val full = rmsDb(run(DenoiseStage(1, 1f, model), noisy), 1.5, 1.95)
        val half = rmsDb(run(DenoiseStage(1, 0.5f, model), noisy), 1.5, 1.95)
        val before = rmsDb(noisy, 1.5, 1.95)
        assertTrue(half > full + 3)                              // gentler
        assertTrue(half >= before - 21)                          // at most about -20 dB
        assertTrue(half < before - 6)                            // but still clearly cleaner
    }

    @Test fun stereoChannelsAreCleanedSeparately() {
        val noisy = wav("noisy.wav")
        val stereo = FloatArray(noisy.size * 2) { if (it % 2 == 0) noisy[it / 2] else 0f }
        val out = run(DenoiseStage(2, 1f, model), stereo, channels = 2)
        assertEquals(stereo.size, out.size)
        val right = FloatArray(noisy.size) { out[2 * it + 1] }
        assertTrue(rmsDb(right, 0.1, 1.9) < -80)                  // silence stays silence
    }

    @Test fun fftMatchesAPlainDft() {
        val n = 960
        val rnd = Random(3)
        val re = DoubleArray(n) { rnd.nextGaussian() }
        val im = DoubleArray(n) { rnd.nextGaussian() }
        val oRe = DoubleArray(n)
        val oIm = DoubleArray(n)
        com.pixelgo.editor.denoise.Fft(n).forward(re, im, oRe, oIm)
        for (k in listOf(0, 1, 7, 240, 479, 480, 959)) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2 * PI * k * t / n
                sr += re[t] * cos(a) - im[t] * sin(a)
                si += re[t] * sin(a) + im[t] * cos(a)
            }
            assertEquals(sr, oRe[k], 1e-9 * sqrt(n.toDouble()) * 10)
            assertEquals(si, oIm[k], 1e-9 * sqrt(n.toDouble()) * 10)
        }
    }
}

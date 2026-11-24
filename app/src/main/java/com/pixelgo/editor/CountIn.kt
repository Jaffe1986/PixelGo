package com.pixelgo.editor

import com.pixelgo.model.AUDIO_RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 3-2-1 count-in before a recording: three short ticks, like a metronome, with the start
 * on the fourth beat. It also lines the recording up exactly.
 *
 * - **Why:** phones' audio timestamps can be off by about 0.1 s (measured: 75–98 ms on a Galaxy
 *   S25 Ultra), which is too much for lip sync.
 * - **How:** the microphone starts before the count-in, so the ticks it hears show which
 *   recorded sample was captured when each tick was played, sample-accurately.
 * - **Headphones:** the mic can't hear the ticks there, so [locate] returns null and the
 *   caller falls back to timestamps plus a correction it measured earlier.
 */
object CountIn {
    /** From the first written sample to the start frame (the fourth beat). */
    const val LENGTH_MS = 3200L

    /** When the three ticks start, in ms from the first written sample. */
    val TICK_MS = longArrayOf(200, 1200, 2200)

    private const val TICK_FRAMES = 80 * AUDIO_RATE / 1000
    private const val FADE = 96 // 2 ms
    private const val FREQ = 1000.0
    private const val WINDOW = 96 // 2 ms envelope
    private const val MIN_GAP = AUDIO_RATE / 5 // onsets at least 200 ms apart
    private const val SPACING_TOLERANCE = AUDIO_RATE / 500 // 2 ms

    /** The count-in sound: 48 kHz interleaved stereo, [LENGTH_MS] long. */
    fun sound(): ShortArray {
        val frames = (LENGTH_MS * AUDIO_RATE / 1000).toInt()
        val out = ShortArray(frames * 2)
        for (t in TICK_MS) {
            val start = (t * AUDIO_RATE / 1000).toInt()
            for (k in 0 until TICK_FRAMES) {
                val fade = minOf(1.0, k / FADE.toDouble(), (TICK_FRAMES - k) / FADE.toDouble())
                val env = 0.5 - 0.5 * cos(PI * fade)
                val v = (0.5 * env * sin(2 * PI * FREQ * k / AUDIO_RATE) * 32767).roundToInt().toShort()
                out[2 * (start + k)] = v
                out[2 * (start + k) + 1] = v
            }
        }
        return out
    }

    /** Where the ticks start in the played sound, measured the same way as in a recording. */
    private val reference: DoubleArray by lazy {
        val s = sound()
        onsets(FloatArray(s.size / 2) { s[2 * it] / 32768f }, s.size / 2)
    }

    /**
     * Start times (fractional frames) of 1 kHz tones in the first [count] samples of [x]
     * (mono, 48 kHz): where a 2 ms envelope of the 1 kHz component rises through half its
     * typical tone level. Phase-independent.
     */
    fun onsets(x: FloatArray, count: Int): DoubleArray {
        val n = minOf(count, x.size)
        if (n < WINDOW * 2) return DoubleArray(0)
        val env = DoubleArray(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var sr = 0.0
        var si = 0.0
        for (i in 0 until n) {
            val ph = 2 * PI * FREQ * i / AUDIO_RATE
            re[i] = x[i] * cos(ph)
            im[i] = x[i] * sin(ph)
            sr += re[i]
            si += im[i]
            if (i >= WINDOW) {
                sr -= re[i - WINDOW]
                si -= im[i - WINDOW]
            }
            env[i] = 2 * sqrt(sr * sr + si * si) / WINDOW
        }
        val sorted = env.sortedArray()
        // The ticks fill a few % of the window, so the 99th percentile sits on a tick.
        val threshold = maxOf(sorted[(n * 0.99).toInt()] / 2, sorted[n / 2] * 4, 0.002)
        val out = ArrayList<Double>()
        var last = -MIN_GAP
        for (i in 1 until n) {
            if (env[i] >= threshold && env[i - 1] < threshold && i - last >= MIN_GAP) {
                out += i - 1 + (threshold - env[i - 1]) / (env[i] - env[i - 1])
                last = i
            }
        }
        return out.toDoubleArray()
    }

    /**
     * Finds the count-in in the first [count] samples of a recording (mono, 48 kHz). Returns
     * how many frames into the recording the first played sample was heard (it may be
     * negative), or null if the three ticks aren't there, evenly spaced.
     */
    fun locate(recording: FloatArray, count: Int): Double? {
        val found = onsets(recording, count)
        val ref = reference
        if (ref.size != TICK_MS.size) return null
        val gap1 = ref[1] - ref[0]
        val gap2 = ref[2] - ref[1]
        // The first run of three onsets spaced like the ticks (the ticks come before anything else).
        for (a in found.indices) {
            val b = found.indexOfFirst { abs(it - found[a] - gap1) <= SPACING_TOLERANCE }
            if (b < 0) continue
            val c = found.indexOfFirst { abs(it - found[b] - gap2) <= SPACING_TOLERANCE }
            if (c < 0) continue
            return ((found[a] - ref[0]) + (found[b] - ref[1]) + (found[c] - ref[2])) / 3
        }
        return null
    }
}

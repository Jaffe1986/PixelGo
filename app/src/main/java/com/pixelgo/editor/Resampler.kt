package com.pixelgo.editor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming sample-rate converter (e.g. 44.1 kHz → 48 kHz) using a windowed-sinc low-pass
 * filter (Blackman window, 32 taps, 512 precomputed phases). Quality is far better than
 * linear interpolation, which audibly aliases music. Interleaved float input/output.
 */
class Resampler(
    private val inRate: Int,
    private val outRate: Int,
    private val channels: Int,
    private val halfTaps: Int = 16,
) {
    private val step = inRate.toDouble() / outRate
    private val phases = 512
    private val taps = 2 * halfTaps
    private val kernel: Array<FloatArray>

    // Input history (interleaved) and read position, in input frames.
    private var buf = FloatArray(4096 * channels)
    private var bufFrames = 0
    private var pos = 0.0

    // Totals, so the output length matches the input duration exactly.
    private var inTotal = 0L
    private var outTotal = 0L
    private var flushing = false

    init {
        val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.95
        kernel = Array(phases + 1) { p ->
            val frac = p.toDouble() / phases
            FloatArray(taps) { k ->
                // tap k sits at input offset (k - halfTaps + 1) from floor(pos)
                val t = (k - halfTaps + 1) - frac
                val x = cutoff * t
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val w = t / halfTaps
                val window = if (w <= -1.0 || w >= 1.0) 0.0 else 0.42 + 0.5 * cos(PI * w) + 0.08 * cos(2 * PI * w)
                (cutoff * sinc * window).toFloat()
            }
        }
        // Lead-in silence so the first output sample lines up with input frame 0.
        append(FloatArray(halfTaps * channels), halfTaps)
        pos = halfTaps.toDouble()
    }

    val passthrough: Boolean get() = inRate == outRate

    /** Feeds [frames] input frames; [emit] receives (interleaved samples, frame count). */
    fun process(input: FloatArray, frames: Int, emit: (FloatArray, Int) -> Unit) {
        if (passthrough) {
            emit(input, frames)
            return
        }
        inTotal += frames
        append(input, frames)
        produce(emit)
    }

    /** Pushes out the remaining samples at the end of the stream. */
    fun flush(emit: (FloatArray, Int) -> Unit) {
        if (passthrough) return
        flushing = true
        append(FloatArray(taps * channels), taps)
        produce(emit)
    }

    /** Output frames that correspond to all input so far. */
    private fun expectedOut(): Long = (inTotal * outRate + inRate - 1) / inRate

    private fun append(input: FloatArray, frames: Int) {
        val need = (bufFrames + frames) * channels
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
        System.arraycopy(input, 0, buf, bufFrames * channels, frames * channels)
        bufFrames += frames
    }

    private fun produce(emit: (FloatArray, Int) -> Unit) {
        val out = FloatArray(1024 * channels)
        var n = 0
        while (true) {
            val base = floor(pos).toInt()
            if (base + halfTaps >= bufFrames) break
            if (flushing && outTotal >= expectedOut()) break
            val frac = pos - base
            val row = kernel[(frac * phases + 0.5).toInt()]
            val first = base - halfTaps + 1
            for (c in 0 until channels) {
                var acc = 0f
                var idx = first * channels + c
                for (k in 0 until taps) {
                    acc += buf[idx] * row[k]
                    idx += channels
                }
                out[n * channels + c] = acc
            }
            n++
            outTotal++
            if (n == 1024) {
                emit(out, n)
                n = 0
            }
            pos += step
        }
        if (n > 0) emit(out, n)
        // Drop input we no longer need (keep halfTaps of history before the read position).
        val keepFrom = floor(pos).toInt() - halfTaps + 1
        if (keepFrom > 0) {
            System.arraycopy(buf, keepFrom * channels, buf, 0, (bufFrames - keepFrom) * channels)
            bufFrames -= keepFrom
            pos -= keepFrom
        }
    }
}

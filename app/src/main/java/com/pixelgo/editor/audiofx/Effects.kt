package com.pixelgo.editor.audiofx

import com.pixelgo.editor.Resampler
import com.pixelgo.model.AUDIO_RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One step of a sound-processing chain: interleaved float frames go in, frames come out
 * (possibly a different number, e.g. when changing speed). Inputs are copied, never kept.
 */
interface Stage {
    fun process(input: FloatArray, frames: Int, emit: (FloatArray, Int) -> Unit)
    fun flush(emit: (FloatArray, Int) -> Unit) {}
}

/** What the Effects sheet asks for; neutral values do nothing. */
data class EffectSettings(
    val reverse: Boolean = false,
    /** Playback speed, 0.5..2. */
    val speed: Float = 1f,
    /** Pitch change in semitones, -12..12 (ignored in [tapeStyle]). */
    val pitch: Float = 0f,
    /** Speed also changes pitch, like a tape (plain resampling). */
    val tapeStyle: Boolean = false,
    /** High-pass corner (Hz), 0 = off. */
    val lowCutHz: Int = 0,
    val bassDb: Float = 0f,
    val midDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** Noise gate threshold (dBFS), null = off. */
    val gateDb: Float? = null,
    /** AI noise removal strength 0..1, 0 = off. */
    val denoise: Float = 0f,
) {
    val isNeutral: Boolean
        get() = !reverse && speed == 1f && (pitch == 0f || tapeStyle) && lowCutHz == 0 &&
            bassDb == 0f && midDb == 0f && trebleDb == 0f && gateDb == null && denoise == 0f

    /** New length / old length. */
    val timeScale: Double get() = 1.0 / speed
}

object Effects {
    /**
     * The chain for [s] on [channels]-channel sound: noise removal ([denoiser]), gate, EQ, then
     * time and pitch. (Reversing happens while reading.)
     */
    fun chain(s: EffectSettings, channels: Int, denoiser: ((channels: Int, strength: Float) -> Stage)? = null): List<Stage> {
        val out = ArrayList<Stage>()
        if (s.denoise > 0f && denoiser != null) out += denoiser(channels, s.denoise)
        s.gateDb?.let { out += NoiseGate(channels, it) }
        if (s.lowCutHz > 0 || s.bassDb != 0f || s.midDb != 0f || s.trebleDb != 0f) {
            out += Equalizer(channels, s.lowCutHz, s.bassDb, s.midDb, s.trebleDb)
        }
        if (s.tapeStyle) {
            if (s.speed != 1f) out += ResampleStage(channels, s.speed.toDouble())
        } else {
            // Stretch the length while keeping pitch, then resample to shift the pitch
            // (which also brings the length to 1 / speed).
            val p = 2.0.pow(s.pitch / 12.0)
            val stretch = p / s.speed
            if (abs(stretch - 1) > 1e-4) out += TimeStretch(channels, stretch)
            if (abs(p - 1) > 1e-4) out += ResampleStage(channels, p)
        }
        return out
    }
}

/** Plays the sound [factor] times faster (pitch goes up with it), by resampling. */
class ResampleStage(channels: Int, factor: Double) : Stage {
    private val r = Resampler((AUDIO_RATE * factor).roundToInt(), AUDIO_RATE, channels)
    override fun process(input: FloatArray, frames: Int, emit: (FloatArray, Int) -> Unit) = r.process(input, frames, emit)
    override fun flush(emit: (FloatArray, Int) -> Unit) = r.flush(emit)
}

/** A second-order filter (RBJ "Audio EQ Cookbook"), one state per channel. */
class Biquad(private val channels: Int, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
    private val b0 = b0 / a0
    private val b1 = b1 / a0
    private val b2 = b2 / a0
    private val a1 = a1 / a0
    private val a2 = a2 / a0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    fun run(buf: FloatArray, frames: Int) {
        for (f in 0 until frames) for (c in 0 until channels) {
            val i = f * channels + c
            val x = buf[i].toDouble()
            val y = b0 * x + z1[c]
            z1[c] = b1 * x - a1 * y + z2[c]
            z2[c] = b2 * x - a2 * y
            buf[i] = y.toFloat()
        }
    }

    /** Gain (dB) at [freq] Hz, for tests. */
    fun responseDb(freq: Double): Double {
        val w = 2 * PI * freq / AUDIO_RATE
        fun mag(c0: Double, c1: Double, c2: Double): Double {
            val re = c0 + c1 * cos(w) + c2 * cos(2 * w)
            val im = -c1 * sin(w) - c2 * sin(2 * w)
            return sqrt(re * re + im * im)
        }
        return 20 * kotlin.math.log10(mag(b0, b1, b2) / mag(1.0, a1, a2))
    }

    companion object {
        private fun w0(f: Double) = 2 * PI * f / AUDIO_RATE

        fun highPass(channels: Int, f: Double, q: Double = 0.7071): Biquad {
            val w = w0(f)
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return Biquad(channels, (1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun peaking(channels: Int, f: Double, db: Double, q: Double = 0.8): Biquad {
            val a = 10.0.pow(db / 40)
            val w = w0(f)
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return Biquad(channels, 1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun lowShelf(channels: Int, f: Double, db: Double): Biquad {
            val a = 10.0.pow(db / 40)
            val w = w0(f)
            val c = cos(w)
            val alpha = sin(w) / 2 * sqrt(2.0)
            val sa = 2 * sqrt(a) * alpha
            return Biquad(
                channels,
                a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa,
            )
        }

        fun highShelf(channels: Int, f: Double, db: Double): Biquad {
            val a = 10.0.pow(db / 40)
            val w = w0(f)
            val c = cos(w)
            val alpha = sin(w) / 2 * sqrt(2.0)
            val sa = 2 * sqrt(a) * alpha
            return Biquad(
                channels,
                a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa,
            )
        }
    }
}

/** Low-cut (removes rumble) plus a three-band EQ: bass shelf 150 Hz, mid peak 1 kHz, treble shelf 5 kHz. */
class Equalizer(private val channels: Int, lowCutHz: Int, bassDb: Float, midDb: Float, trebleDb: Float) : Stage {
    val filters: List<Biquad> = buildList {
        if (lowCutHz > 0) {
            // Two in a row: a steep (24 dB/octave) cut.
            add(Biquad.highPass(channels, lowCutHz.toDouble()))
            add(Biquad.highPass(channels, lowCutHz.toDouble()))
        }
        if (bassDb != 0f) add(Biquad.lowShelf(channels, 150.0, bassDb.toDouble()))
        if (midDb != 0f) add(Biquad.peaking(channels, 1000.0, midDb.toDouble()))
        if (trebleDb != 0f) add(Biquad.highShelf(channels, 5000.0, trebleDb.toDouble()))
    }
    private var buf = FloatArray(0)

    override fun process(input: FloatArray, frames: Int, emit: (FloatArray, Int) -> Unit) {
        val len = frames * channels
        if (buf.size < len) buf = FloatArray(len)
        System.arraycopy(input, 0, buf, 0, len)
        for (f in filters) f.run(buf, frames)
        emit(buf, frames)
    }
}

/**
 * Silences the sound between louder parts (breathing, room hum between words). Looks 5 ms
 * ahead so the starts of words aren't clipped; opens in 2 ms, stays open 60 ms, then closes
 * (40 ms time constant).
 */
class NoiseGate(private val channels: Int, thresholdDb: Float) : Stage {
    private val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()
    private val delay = AUDIO_RATE * 5 / 1000
    private val ring = FloatArray(delay * channels)
    private var ringPos = 0
    private var skipped = 0 // the first [delay] output frames are the look-ahead's silence
    private var env = 0f
    private var hold = 0
    private var gain = 0f
    private val holdFrames = AUDIO_RATE * 60 / 1000
    private val release = (1.0 - 1.0 / (AUDIO_RATE * 0.02)).toFloat() // detector: 20 ms
    private val openCoef = (1.0 / (AUDIO_RATE * 0.002)).toFloat()
    private val closeCoef = (1.0 / (AUDIO_RATE * 0.04)).toFloat()
    private var out = FloatArray(0)

    override fun process(input: FloatArray, frames: Int, emit: (FloatArray, Int) -> Unit) = run(input, frames, emit)

    override fun flush(emit: (FloatArray, Int) -> Unit) = run(FloatArray(delay * channels), delay, emit)

    private fun run(input: FloatArray, frames: Int, emit: (FloatArray, Int) -> Unit) {
        if (out.size < frames * channels) out = FloatArray(frames * channels)
        var n = 0
        for (f in 0 until frames) {
            var level = 0f
            for (c in 0 until channels) level = max(level, abs(input[f * channels + c]))
            env = max(level, env * release)
            val open = if (env > threshold) {
                hold = holdFrames
                true
            } else if (hold > 0) {
                hold--
                true
            } else {
                false
            }
            gain += ((if (open) 1f else 0f) - gain) * (if (open) openCoef else closeCoef)
            for (c in 0 until channels) {
                val delayed = ring[ringPos * channels + c]
                ring[ringPos * channels + c] = input[f * channels + c]
                if (skipped >= delay) out[n * channels + c] = delayed * gain
            }
            ringPos = (ringPos + 1) % delay
            if (skipped < delay) skipped++ else n++
        }
        if (n > 0) emit(out, n)
    }
}

/**
 * Changes the length of a sound by [stretch] (new / old) without changing its pitch, with WSOLA:
 * 40 ms windows are overlap-added every 20 ms, each taken from where it best continues the
 * previous one (searching ±7.5 ms around its nominal place).
 */
class TimeStretch(private val channels: Int, private val stretch: Double) : Stage {
    private val n = AUDIO_RATE * 40 / 1000          // window
    private val hs = n / 2                         // output hop
    private val ha = hs / stretch                  // input hop
    private val tol = AUDIO_RATE * 75 / 10_000     // search ±7.5 ms
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }

    // Window k's centre (output k*hs + n/2) must come from input (k*hs + n/2) / stretch, so its
    // start is k*ha plus this offset; otherwise everything would shift by n/2 * (stretch - 1).
    private val centre = n / (2 * stretch) - n / 2.0

    private var input = FloatArray(maxOf(1 shl 16, 4 * n * channels)) // buffered input (interleaved)
    private var inStart = -2L * n                  // absolute frame of input[0]: silence before the sound
    private var inFrames = 2 * n                   // frames buffered
    private var totalIn = 0L
    private var k = -1L                            // next output window (-1: only fills in the start)
    private var prevPos = 0L
    private val acc = FloatArray(n * channels)     // overlap-add accumulator, starting at output k*hs
    private var emitted = 0L
    private var out = FloatArray(hs * channels)

    override fun process(input: FloatArray, frames: Int, emit: (FloatArray, Int) -> Unit) {
        append(input, frames)
        totalIn += frames
        produce(final = false, emit)
    }

    override fun flush(emit: (FloatArray, Int) -> Unit) {
        // Pad with silence so the last windows can be read, then trim to the exact length.
        append(FloatArray((n + 2 * tol) * channels), n + 2 * tol)
        produce(final = true, emit)
        val target = (totalIn * stretch).roundToLong()
        // The rest of the accumulator.
        var left = (target - emitted).coerceAtLeast(0)
        var i = 0
        while (left > 0) {
            val m = minOf(left, hs.toLong()).toInt()
            for (j in 0 until m * channels) out[j] = if (i + j < acc.size) acc[i + j] else 0f
            emit(out, m)
            emitted += m
            left -= m
            i += m * channels
        }
    }

    private fun append(src: FloatArray, frames: Int) {
        if ((inFrames + frames) * channels > input.size) {
            input = input.copyOf(maxOf(input.size * 2, (inFrames + frames) * channels))
        }
        System.arraycopy(src, 0, input, inFrames * channels, frames * channels)
        inFrames += frames
    }

    private fun mono(abs: Long): Float {
        val i = ((abs - inStart) * channels).toInt()
        var s = 0f
        for (c in 0 until channels) s += input[i + c]
        return s
    }

    private fun produce(final: Boolean, emit: (FloatArray, Int) -> Unit) {
        val target = (totalIn * stretch).roundToLong()
        while (true) {
            if (final && emitted >= target) return
            val nominal = kotlin.math.floor(k * ha + centre).toLong()
            if (!final && nominal >= totalIn) return
            if (final && nominal >= totalIn + n) return
            val natural = prevPos + hs
            val lo = if (k < 0) nominal else maxOf(inStart, nominal - tol)
            val hi = if (k < 0) nominal else nominal + tol
            // Need input up to hi + n and natural + hs.
            if (maxOf(hi + n, natural + hs) > inStart + inFrames) return
            var best = nominal
            if (k >= 0) {
                fun score(cand: Long): Double {
                    var sc = 0.0
                    var j = 0
                    while (j < hs) {
                        sc += mono(cand + j) * mono(natural + j)
                        j += 2
                    }
                    return sc
                }
                // Stay at the nominal place unless another clearly continues better (after
                // silence every candidate scores 0, and the timing must not drift).
                var bestScore = score(nominal) + 1e-9
                var cand = lo
                while (cand <= hi) {
                    val sc = score(cand)
                    if (sc > bestScore) {
                        bestScore = sc
                        best = cand
                    }
                    cand += 2
                }
            }
            // Overlap-add the window at [best].
            val base = ((best - inStart) * channels).toInt()
            for (j in 0 until n) for (c in 0 until channels) acc[j * channels + c] += window[j] * input[base + j * channels + c]
            // The first half is final now (window -1's lies before the start: dropped).
            if (k >= 0) {
                val m = if (final) minOf(hs.toLong(), (target - emitted).coerceAtLeast(0)).toInt() else hs
                System.arraycopy(acc, 0, out, 0, m * channels)
                if (m > 0) emit(out, m)
                emitted += m
            }
            System.arraycopy(acc, hs * channels, acc, 0, hs * channels)
            java.util.Arrays.fill(acc, hs * channels, n * channels, 0f)
            prevPos = best
            k++
            // Drop input nobody will read again.
            val keepFrom = minOf(kotlin.math.floor(k * ha + centre).toLong() - tol, prevPos + hs) - 8
            val drop = (keepFrom - inStart).coerceAtLeast(0).toInt().coerceAtMost(inFrames)
            if (drop > 4096) {
                System.arraycopy(input, drop * channels, input, 0, (inFrames - drop) * channels)
                inFrames -= drop
                inStart += drop
            }
        }
    }

    private fun Double.roundToLong() = kotlin.math.round(this).toLong()
}

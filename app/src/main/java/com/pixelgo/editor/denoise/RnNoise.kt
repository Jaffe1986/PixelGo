package com.pixelgo.editor.denoise

import com.pixelgo.editor.audiofx.Stage
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * AI background-noise removal: a Kotlin port of RNNoise v0.1.1 (xiph/rnnoise), a small
 * recurrent neural network that estimates, every 10 ms, how much of each of 22 frequency
 * bands is noise.
 *
 * RNNoise: Copyright (c) 2017 Mozilla, (c) 2007-2017 Jean-Marc Valin, (c) 2005-2017 Xiph.Org
 * Foundation, (c) 2003-2004 Mark Borgerding. BSD 3-clause licence: see
 * assets/RNNOISE-LICENSE.txt. The weights are RNNoise's built-in model (tools/rnnoise_weights.py).
 *
 * This follows src/denoise.c, rnn.c and pitch.c of that release closely (floating-point build),
 * so its output matches ffmpeg's arnndn filter with the same weights (RnNoiseTest).
 */

private const val FRAME = 480            // 10 ms at 48 kHz
private const val WINDOW = 2 * FRAME
private const val FREQ = FRAME + 1
private const val PITCH_MIN = 60
private const val PITCH_MAX = 768
private const val PITCH_FRAME = 960
private const val PITCH_BUF = PITCH_MAX + PITCH_FRAME
private const val BANDS = 22
private const val CEPS_MEM = 8
private const val DELTA_CEPS = 6
private const val FEATURES = BANDS + 3 * DELTA_CEPS + 2
private const val WEIGHTS_SCALE = 1f / 256
private const val TANH = 0
private const val SIGMOID = 1

private val EBAND = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 34, 40, 48, 60, 78, 100)

/** The network's weights (rnnoise.bin, made by tools/rnnoise_weights.py). */
class RnnModel(bytes: ByteArray) {
    class Layer(
        val inputs: Int, val neurons: Int, val activation: Int,
        val input: FloatArray, val recurrent: FloatArray?, val bias: FloatArray,
    )

    val inputDense: Layer
    val vadGru: Layer
    val noiseGru: Layer
    val denoiseGru: Layer
    val denoiseOutput: Layer
    val vadOutput: Layer

    init {
        val b = ByteBuffer.wrap(bytes)
        require(b.int == 0x524E4E31) { "Not an RNNoise model" } // "RNN1"
        fun layer(): Layer {
            val inputs = b.int
            val neurons = b.int
            val activation = b.int
            val gru = b.int == 1
            val gates = if (gru) 3 else 1
            fun read(n: Int) = FloatArray(n) { b.get().toFloat() }
            val input = read(inputs * neurons * gates)
            val recurrent = if (gru) read(neurons * neurons * 3) else null
            return Layer(inputs, neurons, activation, input, recurrent, read(neurons * gates))
        }
        inputDense = layer()
        vadGru = layer()
        noiseGru = layer()
        denoiseGru = layer()
        denoiseOutput = layer()
        vadOutput = layer()
    }
}

/** tanh approximation exactly as RNNoise's (a table of tanh(0.04 i) plus a correction). */
private val TANSIG = FloatArray(201) { (Math.round(tanh(0.04 * it) * 1e6) / 1e6).toFloat() }

private fun tansig(x0: Float): Float {
    if (!(x0 < 8)) return 1f
    if (!(x0 > -8)) return -1f
    if (x0.isNaN()) return 0f
    var x = x0
    var sign = 1f
    if (x < 0) {
        x = -x
        sign = -1f
    }
    val i = floor(0.5f + 25 * x).toInt()
    x -= 0.04f * i
    val y = TANSIG[i]
    val dy = 1 - y * y
    return sign * (y + x * dy * (1 - y * x))
}

private fun sigmoid(x: Float) = 0.5f + 0.5f * tansig(0.5f * x)

private fun activate(a: Int, x: Float) = when (a) {
    SIGMOID -> sigmoid(x)
    TANH -> tansig(x)
    else -> if (x < 0) 0f else x
}

private fun dense(l: RnnModel.Layer, out: FloatArray, input: FloatArray) {
    val n = l.neurons
    for (i in 0 until n) {
        var sum = l.bias[i]
        for (j in 0 until l.inputs) sum += l.input[j * n + i] * input[j]
        out[i] = activate(l.activation, WEIGHTS_SCALE * sum)
    }
}

private fun gru(l: RnnModel.Layer, state: FloatArray, input: FloatArray, z: FloatArray, r: FloatArray, h: FloatArray) {
    val n = l.neurons
    val m = l.inputs
    val stride = 3 * n
    val rec = l.recurrent!!
    for (i in 0 until n) {
        var sum = l.bias[i]
        for (j in 0 until m) sum += l.input[j * stride + i] * input[j]
        for (j in 0 until n) sum += rec[j * stride + i] * state[j]
        z[i] = sigmoid(WEIGHTS_SCALE * sum)
    }
    for (i in 0 until n) {
        var sum = l.bias[n + i]
        for (j in 0 until m) sum += l.input[n + j * stride + i] * input[j]
        for (j in 0 until n) sum += rec[n + j * stride + i] * state[j]
        r[i] = sigmoid(WEIGHTS_SCALE * sum)
    }
    for (i in 0 until n) {
        var sum = l.bias[2 * n + i]
        for (j in 0 until m) sum += l.input[2 * n + j * stride + i] * input[j]
        for (j in 0 until n) sum += rec[2 * n + j * stride + i] * state[j] * r[j]
        h[i] = z[i] * state[i] + (1 - z[i]) * activate(l.activation, WEIGHTS_SCALE * sum)
    }
    System.arraycopy(h, 0, state, 0, n)
}

/** A mixed-radix complex FFT (unscaled forward transform) for any size made of 2, 3, 4 and 5. */
internal class Fft(private val n: Int) {
    private val factors: IntArray
    private val cosT = DoubleArray(n) { cos(-2 * PI * it / n) }
    private val sinT = DoubleArray(n) { sin(-2 * PI * it / n) }
    private val tr = DoubleArray(5)
    private val ti = DoubleArray(5)

    init {
        val f = ArrayList<Int>()
        var m = n
        for (p in intArrayOf(4, 2, 3, 5)) while (m % p == 0) { f += p; m /= p }
        require(m == 1) { "FFT size $n" }
        factors = f.toIntArray()
    }

    /** out = sum_k in[k] e^(-2 pi i j k / n). */
    fun forward(inRe: DoubleArray, inIm: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) =
        rec(inRe, inIm, 0, 1, n, outRe, outIm, 0, 0)

    private fun rec(inRe: DoubleArray, inIm: DoubleArray, inOff: Int, stride: Int, len: Int,
                    outRe: DoubleArray, outIm: DoubleArray, outOff: Int, fi: Int) {
        if (len == 1) {
            outRe[outOff] = inRe[inOff]
            outIm[outOff] = inIm[inOff]
            return
        }
        val p = factors[fi]
        val m = len / p
        for (q in 0 until p) rec(inRe, inIm, inOff + q * stride, stride * p, m, outRe, outIm, outOff + q * m, fi + 1)
        val step = n / len
        for (k in 0 until m) {
            for (q in 0 until p) {
                tr[q] = outRe[outOff + q * m + k]
                ti[q] = outIm[outOff + q * m + k]
            }
            for (s in 0 until p) {
                val j = k + s * m
                var re = 0.0
                var im = 0.0
                for (q in 0 until p) {
                    val t = ((q * j) % len) * step
                    re += tr[q] * cosT[t] - ti[q] * sinT[t]
                    im += tr[q] * sinT[t] + ti[q] * cosT[t]
                }
                outRe[outOff + j] = re
                outIm[outOff + j] = im
            }
        }
    }
}

/** One channel's RNNoise state. [gainFloor] (0..1) limits how far any band is turned down. */
class RnNoise(private val model: RnnModel, private val gainFloor: Float = 0f) {
    private val analysisMem = FloatArray(FRAME)
    private val cepstralMem = Array(CEPS_MEM) { FloatArray(BANDS) }
    private var memId = 0
    private val synthesisMem = FloatArray(FRAME)
    private val pitchBuf = FloatArray(PITCH_BUF)
    private var lastGain = 0f
    private var lastPeriod = 0
    private val memHp = FloatArray(2)
    private val lastG = FloatArray(BANDS)
    private val vadState = FloatArray(model.vadGru.neurons)
    private val noiseState = FloatArray(model.noiseGru.neurons)
    private val denoiseState = FloatArray(model.denoiseGru.neurons)

    // Scratch.
    private val xRe = FloatArray(FREQ)
    private val xIm = FloatArray(FREQ)
    private val pRe = FloatArray(FREQ)
    private val pIm = FloatArray(FREQ)
    private val ex = FloatArray(BANDS)
    private val ep = FloatArray(BANDS)
    private val exp = FloatArray(BANDS)
    private val features = FloatArray(FEATURES)
    private val g = FloatArray(BANDS)
    private val gf = FloatArray(FREQ)
    private val hp = FloatArray(FRAME)
    private val win = FloatArray(WINDOW)
    private val denseOut = FloatArray(128)
    private val noiseIn = FloatArray(128 * 3)
    private val denoiseIn = FloatArray(128 * 3)
    private val z = FloatArray(128)
    private val r = FloatArray(128)
    private val h = FloatArray(128)
    private val vad = FloatArray(1)
    private val fRe = DoubleArray(WINDOW)
    private val fIm = DoubleArray(WINDOW)
    private val oRe = DoubleArray(WINDOW)
    private val oIm = DoubleArray(WINDOW)
    private val lp = FloatArray(PITCH_BUF shr 1)

    /** Cleans one 10 ms frame (480 samples, 16-bit scale) into [out]; returns the voice probability. */
    fun process(input: FloatArray, out: FloatArray): Float {
        biquad(hp, memHp, input)
        val silence = frameFeatures(hp)
        var vadProb = 0f
        if (!silence) {
            rnn()
            vadProb = vad[0]
            if (gainFloor > 0f) for (i in 0 until BANDS) g[i] = max(g[i], gainFloor)
            pitchFilter()
            for (i in 0 until BANDS) {
                g[i] = max(g[i], 0.6f * lastG[i])
                lastG[i] = g[i]
            }
            interpBandGain(gf, g)
            for (i in 0 until FREQ) {
                xRe[i] *= gf[i]
                xIm[i] *= gf[i]
            }
        }
        synthesis(out)
        return vadProb
    }

    private fun biquad(y: FloatArray, mem: FloatArray, x: FloatArray) {
        val b0 = -2f
        val b1 = 1f
        val a0 = -1.99599f
        val a1 = 0.99600f
        for (i in 0 until FRAME) {
            val xi = x[i]
            val yi = x[i] + mem[0]
            mem[0] = (mem[1] + (b0 * xi.toDouble() - a0 * yi.toDouble())).toFloat()
            mem[1] = (b1 * xi.toDouble() - a1 * yi.toDouble()).toFloat()
            y[i] = yi
        }
    }

    private fun applyWindow(x: FloatArray) {
        for (i in 0 until FRAME) {
            x[i] *= HALF_WINDOW[i]
            x[WINDOW - 1 - i] *= HALF_WINDOW[i]
        }
    }

    /** X = FFT(x) / N, bins 0..FRAME. */
    private fun forward(x: FloatArray, re: FloatArray, im: FloatArray) {
        for (i in 0 until WINDOW) {
            fRe[i] = x[i].toDouble()
            fIm[i] = 0.0
        }
        FFT.forward(fRe, fIm, oRe, oIm)
        for (i in 0 until FREQ) {
            re[i] = (oRe[i] / WINDOW).toFloat()
            im[i] = (oIm[i] / WINDOW).toFloat()
        }
    }

    /** The inverse of [forward] (via the forward FFT of the mirrored spectrum, as RNNoise does). */
    private fun inverse(re: FloatArray, im: FloatArray, out: FloatArray) {
        for (i in 0 until FREQ) {
            fRe[i] = re[i].toDouble()
            fIm[i] = im[i].toDouble()
        }
        for (i in FREQ until WINDOW) {
            fRe[i] = fRe[WINDOW - i]
            fIm[i] = -fIm[WINDOW - i]
        }
        FFT.forward(fRe, fIm, oRe, oIm)
        out[0] = oRe[0].toFloat()
        for (i in 1 until WINDOW) out[i] = oRe[WINDOW - i].toFloat()
    }

    private fun bandEnergy(e: FloatArray, re: FloatArray, im: FloatArray) {
        java.util.Arrays.fill(e, 0f)
        for (i in 0 until BANDS - 1) {
            val size = (EBAND[i + 1] - EBAND[i]) shl 2
            for (j in 0 until size) {
                val frac = j.toFloat() / size
                val k = (EBAND[i] shl 2) + j
                val t = re[k] * re[k] + im[k] * im[k]
                e[i] += (1 - frac) * t
                e[i + 1] += frac * t
            }
        }
        e[0] *= 2
        e[BANDS - 1] *= 2
    }

    private fun bandCorr(e: FloatArray) {
        java.util.Arrays.fill(e, 0f)
        for (i in 0 until BANDS - 1) {
            val size = (EBAND[i + 1] - EBAND[i]) shl 2
            for (j in 0 until size) {
                val frac = j.toFloat() / size
                val k = (EBAND[i] shl 2) + j
                val t = xRe[k] * pRe[k] + xIm[k] * pIm[k]
                e[i] += (1 - frac) * t
                e[i + 1] += frac * t
            }
        }
        e[0] *= 2
        e[BANDS - 1] *= 2
    }

    /** Spreads band values over the bins; bins above the last band stay 0 (as in RNNoise). */
    private fun interpBandGain(out: FloatArray, band: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        for (i in 0 until BANDS - 1) {
            val size = (EBAND[i + 1] - EBAND[i]) shl 2
            for (j in 0 until size) {
                val frac = j.toFloat() / size
                out[(EBAND[i] shl 2) + j] = (1 - frac) * band[i] + frac * band[i + 1]
            }
        }
    }

    private fun dct(out: FloatArray, input: FloatArray) {
        for (i in 0 until BANDS) {
            var sum = 0f
            for (j in 0 until BANDS) sum += input[j] * DCT[j * BANDS + i]
            out[i] = (sum * sqrt(2.0 / 22)).toFloat()
        }
    }

    private fun frameFeatures(input: FloatArray): Boolean {
        // Analysis: window [previous frame, this frame].
        System.arraycopy(analysisMem, 0, win, 0, FRAME)
        System.arraycopy(input, 0, win, FRAME, FRAME)
        System.arraycopy(input, 0, analysisMem, 0, FRAME)
        applyWindow(win)
        forward(win, xRe, xIm)
        bandEnergy(ex, xRe, xIm)
        // Pitch.
        System.arraycopy(pitchBuf, FRAME, pitchBuf, 0, PITCH_BUF - FRAME)
        System.arraycopy(input, 0, pitchBuf, PITCH_BUF - FRAME, FRAME)
        Pitch.downsample(pitchBuf, lp, PITCH_BUF)
        var pitchIndex = Pitch.search(lp, PITCH_MAX shr 1, lp, PITCH_FRAME, PITCH_MAX - 3 * PITCH_MIN)
        pitchIndex = PITCH_MAX - pitchIndex
        val period = IntArray(1)
        period[0] = pitchIndex
        val gain = Pitch.removeDoubling(lp, PITCH_MAX, PITCH_MIN, PITCH_FRAME, period, lastPeriod, lastGain)
        pitchIndex = period[0]
        lastPeriod = pitchIndex
        lastGain = gain
        for (i in 0 until WINDOW) win[i] = pitchBuf[PITCH_BUF - WINDOW - pitchIndex + i]
        applyWindow(win)
        forward(win, pRe, pIm)
        bandEnergy(ep, pRe, pIm)
        bandCorr(exp)
        for (i in 0 until BANDS) exp[i] = (exp[i] / sqrt(0.001 + ex[i] * ep[i])).toFloat()
        val tmp = FloatArray(BANDS)
        dct(tmp, exp)
        for (i in 0 until DELTA_CEPS) features[BANDS + 2 * DELTA_CEPS + i] = tmp[i]
        features[BANDS + 2 * DELTA_CEPS] -= 1.3f
        features[BANDS + 2 * DELTA_CEPS + 1] -= 0.9f
        features[BANDS + 3 * DELTA_CEPS] = 0.01f * (pitchIndex - 300)
        val ly = FloatArray(BANDS)
        var logMax = -2f
        var follow = -2f
        var e = 0f
        for (i in 0 until BANDS) {
            ly[i] = log10(1e-2 + ex[i]).toFloat()
            ly[i] = max(logMax - 7, max(follow - 1.5f, ly[i]))
            logMax = max(logMax, ly[i])
            follow = max(follow - 1.5f, ly[i])
            e += ex[i]
        }
        if (e < 0.04f) {
            // No sound: leave the state alone.
            java.util.Arrays.fill(features, 0f)
            return true
        }
        dct(features, ly)
        features[0] -= 12
        features[1] -= 4
        val c0 = cepstralMem[memId]
        val c1 = cepstralMem[if (memId < 1) CEPS_MEM + memId - 1 else memId - 1]
        val c2 = cepstralMem[if (memId < 2) CEPS_MEM + memId - 2 else memId - 2]
        for (i in 0 until BANDS) c0[i] = features[i]
        memId++
        for (i in 0 until DELTA_CEPS) {
            features[i] = c0[i] + c1[i] + c2[i]
            features[BANDS + i] = c0[i] - c2[i]
            features[BANDS + DELTA_CEPS + i] = c0[i] - 2 * c1[i] + c2[i]
        }
        if (memId == CEPS_MEM) memId = 0
        var variability = 0f
        for (i in 0 until CEPS_MEM) {
            var minDist = 1e15f
            for (j in 0 until CEPS_MEM) {
                var dist = 0f
                for (k in 0 until BANDS) {
                    val t = cepstralMem[i][k] - cepstralMem[j][k]
                    dist += t * t
                }
                if (j != i) minDist = min(minDist, dist)
            }
            variability += minDist
        }
        features[BANDS + 3 * DELTA_CEPS + 1] = variability / CEPS_MEM - 2.1f
        return false
    }

    private fun rnn() {
        val m = model
        dense(m.inputDense, denseOut, features)
        gru(m.vadGru, vadState, denseOut, z, r, h)
        dense(m.vadOutput, vad, vadState)
        val nd = m.inputDense.neurons
        val nv = m.vadGru.neurons
        val nn = m.noiseGru.neurons
        System.arraycopy(denseOut, 0, noiseIn, 0, nd)
        System.arraycopy(vadState, 0, noiseIn, nd, nv)
        System.arraycopy(features, 0, noiseIn, nd + nv, FEATURES)
        gru(m.noiseGru, noiseState, noiseIn, z, r, h)
        System.arraycopy(vadState, 0, denoiseIn, 0, nv)
        System.arraycopy(noiseState, 0, denoiseIn, nv, nn)
        System.arraycopy(features, 0, denoiseIn, nv + nn, FEATURES)
        gru(m.denoiseGru, denoiseState, denoiseIn, z, r, h)
        dense(m.denoiseOutput, g, denoiseState)
    }

    private fun pitchFilter() {
        val rr = FloatArray(BANDS)
        val rf = FloatArray(FREQ)
        for (i in 0 until BANDS) {
            var v = if (exp[i] > g[i]) 1f
            else ((exp[i] * exp[i]) * (1 - g[i] * g[i]) / (0.001 + g[i] * g[i] * (1 - exp[i] * exp[i]))).toFloat()
            v = sqrt(min(1f, max(0f, v)))
            rr[i] = (v * sqrt(ex[i] / (1e-8 + ep[i]))).toFloat()
        }
        interpBandGain(rf, rr)
        for (i in 0 until FREQ) {
            xRe[i] += rf[i] * pRe[i]
            xIm[i] += rf[i] * pIm[i]
        }
        val newE = FloatArray(BANDS)
        bandEnergy(newE, xRe, xIm)
        val norm = FloatArray(BANDS)
        for (i in 0 until BANDS) norm[i] = sqrt(ex[i] / (1e-8 + newE[i])).toFloat()
        val normf = FloatArray(FREQ)
        interpBandGain(normf, norm)
        for (i in 0 until FREQ) {
            xRe[i] *= normf[i]
            xIm[i] *= normf[i]
        }
    }

    private fun synthesis(out: FloatArray) {
        inverse(xRe, xIm, win)
        applyWindow(win)
        for (i in 0 until FRAME) out[i] = win[i] + synthesisMem[i]
        System.arraycopy(win, FRAME, synthesisMem, 0, FRAME)
    }

    companion object {
        private val FFT = Fft(WINDOW)
        private val HALF_WINDOW = FloatArray(FRAME) {
            val s = sin(0.5 * PI * (it + 0.5) / FRAME)
            sin(0.5 * PI * s * s).toFloat()
        }
        private val DCT = FloatArray(BANDS * BANDS).also { t ->
            for (i in 0 until BANDS) for (j in 0 until BANDS) {
                t[i * BANDS + j] = (cos((i + 0.5) * j * PI / BANDS) * if (j == 0) sqrt(0.5) else 1.0).toFloat()
            }
        }
    }
}

/** Pitch analysis from RNNoise's pitch.c / celt_lpc.c (floating-point build). */
internal object Pitch {
    private fun innerProd(x: FloatArray, xo: Int, y: FloatArray, yo: Int, n: Int): Float {
        var s = 0f
        for (i in 0 until n) s += x[xo + i] * y[yo + i]
        return s
    }

    private fun xcorr(x: FloatArray, xo: Int, y: FloatArray, yo: Int, out: FloatArray, len: Int, maxPitch: Int) {
        for (i in 0 until maxPitch) out[i] = innerProd(x, xo, y, yo + i, len)
    }

    /** Halves the rate of [x] ([len] samples) into [lp], then whitens it with a 4th-order LPC. */
    fun downsample(x: FloatArray, lp: FloatArray, len: Int) {
        val half = len shr 1
        for (i in 1 until half) lp[i] = 0.5f * (0.5f * (x[2 * i - 1] + x[2 * i + 1]) + x[2 * i])
        lp[0] = 0.5f * (0.5f * x[1] + x[0])
        val ac = FloatArray(5)
        for (k in 0..4) {
            var d = 0f
            for (i in k until half) d += lp[i] * lp[i - k]
            ac[k] = d
        }
        ac[0] *= 1.0001f
        for (i in 1..4) ac[i] -= ac[i] * (0.008f * i) * (0.008f * i)
        val lpc = lpc(ac, 4)
        var tmp = 1f
        for (i in 0 until 4) {
            tmp *= 0.9f
            lpc[i] *= tmp
        }
        val c1 = 0.8f
        val lpc2 = floatArrayOf(lpc[0] + 0.8f, lpc[1] + c1 * lpc[0], lpc[2] + c1 * lpc[1], lpc[3] + c1 * lpc[2], c1 * lpc[3])
        // 5-tap FIR in place.
        var m0 = 0f
        var m1 = 0f
        var m2 = 0f
        var m3 = 0f
        var m4 = 0f
        for (i in 0 until half) {
            val xi = lp[i]
            val sum = xi + lpc2[0] * m0 + lpc2[1] * m1 + lpc2[2] * m2 + lpc2[3] * m3 + lpc2[4] * m4
            m4 = m3
            m3 = m2
            m2 = m1
            m1 = m0
            m0 = xi
            lp[i] = sum
        }
    }

    private fun lpc(ac: FloatArray, p: Int): FloatArray {
        val lpc = FloatArray(p)
        var error = ac[0]
        if (ac[0] != 0f) {
            for (i in 0 until p) {
                var rr = 0f
                for (j in 0 until i) rr += lpc[j] * ac[i - j]
                rr += ac[i + 1]
                val r = -rr / error
                lpc[i] = r
                for (j in 0 until ((i + 1) shr 1)) {
                    val t1 = lpc[j]
                    val t2 = lpc[i - 1 - j]
                    lpc[j] = t1 + r * t2
                    lpc[i - 1 - j] = t2 + r * t1
                }
                error -= r * r * error
                if (error < 0.001f * ac[0]) break
            }
        }
        return lpc
    }

    private fun findBestPitch(xcorr: FloatArray, y: FloatArray, yo: Int, len: Int, maxPitch: Int, best: IntArray) {
        var syy = 1f
        val bestNum = floatArrayOf(-1f, -1f)
        val bestDen = floatArrayOf(0f, 0f)
        best[0] = 0
        best[1] = 1
        for (j in 0 until len) syy += y[yo + j] * y[yo + j]
        for (i in 0 until maxPitch) {
            if (xcorr[i] > 0) {
                var x16 = xcorr[i]
                x16 *= 1e-12f
                val num = x16 * x16
                if (num * bestDen[1] > bestNum[1] * syy) {
                    if (num * bestDen[0] > bestNum[0] * syy) {
                        bestNum[1] = bestNum[0]
                        bestDen[1] = bestDen[0]
                        best[1] = best[0]
                        bestNum[0] = num
                        bestDen[0] = syy
                        best[0] = i
                    } else {
                        bestNum[1] = num
                        bestDen[1] = syy
                        best[1] = i
                    }
                }
            }
            syy += y[yo + i + len] * y[yo + i + len] - y[yo + i] * y[yo + i]
            syy = max(1f, syy)
        }
    }

    /** Coarse-to-fine pitch search; x is at [xo] in [x], y at 0 in [y]. Returns the lag. */
    fun search(x: FloatArray, xo: Int, y: FloatArray, len: Int, maxPitch: Int): Int {
        val lag = len + maxPitch
        val x4 = FloatArray(len shr 2) { x[xo + 2 * it] }
        val y4 = FloatArray(lag shr 2) { y[2 * it] }
        val xc = FloatArray(maxPitch shr 1)
        xcorr(x4, 0, y4, 0, xc, len shr 2, maxPitch shr 2)
        val best = IntArray(2)
        findBestPitch(xc, y4, 0, len shr 2, maxPitch shr 2, best)
        for (i in 0 until (maxPitch shr 1)) {
            xc[i] = 0f
            if (abs(i - 2 * best[0]) > 2 && abs(i - 2 * best[1]) > 2) continue
            xc[i] = max(-1f, innerProd(x, xo, y, i, len shr 1))
        }
        findBestPitch(xc, y, 0, len shr 1, maxPitch shr 1, best)
        val offset = if (best[0] > 0 && best[0] < (maxPitch shr 1) - 1) {
            val a = xc[best[0] - 1]
            val b = xc[best[0]]
            val c = xc[best[0] + 1]
            when {
                (c - a) > 0.7f * (b - a) -> 1
                (a - c) > 0.7f * (b - c) -> -1
                else -> 0
            }
        } else 0
        return 2 * best[0] - offset
    }

    private val SECOND_CHECK = intArrayOf(0, 0, 3, 2, 3, 2, 5, 2, 3, 2, 3, 2, 5, 2, 3, 2)

    private fun pitchGain(xy: Float, xx: Float, yy: Float) = (xy / sqrt(1.0 + xx * yy)).toFloat()

    /** Checks whether the pitch found is a multiple of the real one; updates [t0] and returns the gain. */
    fun removeDoubling(x0: FloatArray, maxPeriod0: Int, minPeriod0: Int, n0: Int, t0: IntArray, prevPeriod0: Int, prevGain: Float): Float {
        val maxPeriod = maxPeriod0 / 2
        val minPeriod = minPeriod0 / 2
        var tz = t0[0] / 2
        val prevPeriod = prevPeriod0 / 2
        val n = n0 / 2
        val xo = maxPeriod // x points at x0[maxPeriod]
        if (tz >= maxPeriod) tz = maxPeriod - 1
        var t = tz
        val tStart = tz
        fun at(i: Int) = x0[xo + i]
        var xx = 0f
        var xy = 0f
        for (i in 0 until n) {
            xx += at(i) * at(i)
            xy += at(i) * at(i - tStart)
        }
        val yyLookup = FloatArray(maxPeriod + 1)
        yyLookup[0] = xx
        var yy = xx
        for (i in 1..maxPeriod) {
            yy = yy + at(-i) * at(-i) - at(n - i) * at(n - i)
            yyLookup[i] = max(0f, yy)
        }
        yy = yyLookup[tStart]
        var bestXy = xy
        var bestYy = yy
        val g0 = pitchGain(xy, xx, yy)
        var g = g0
        for (k in 2..15) {
            val t1 = (2 * tStart + k) / (2 * k)
            if (t1 < minPeriod) break
            val t1b = if (k == 2) {
                if (t1 + tStart > maxPeriod) tStart else tStart + t1
            } else {
                (2 * SECOND_CHECK[k] * tStart + k) / (2 * k)
            }
            var xy1 = 0f
            var xy2 = 0f
            for (i in 0 until n) {
                xy1 += at(i) * at(i - t1)
                xy2 += at(i) * at(i - t1b)
            }
            val xyk = 0.5f * (xy1 + xy2)
            val yyk = 0.5f * (yyLookup[t1] + yyLookup[t1b])
            val g1 = pitchGain(xyk, xx, yyk)
            val cont = when {
                abs(t1 - prevPeriod) <= 1 -> prevGain
                abs(t1 - prevPeriod) <= 2 && 5 * k * k < tStart -> 0.5f * prevGain
                else -> 0f
            }
            var thresh = max(0.3f, 0.7f * g0 - cont)
            if (t1 < 3 * minPeriod) thresh = max(0.4f, 0.85f * g0 - cont)
            else if (t1 < 2 * minPeriod) thresh = max(0.5f, 0.9f * g0 - cont)
            if (g1 > thresh) {
                bestXy = xyk
                bestYy = yyk
                t = t1
                g = g1
            }
        }
        bestXy = max(0f, bestXy)
        var pg = if (bestYy <= bestXy) 1f else bestXy / (bestYy + 1)
        val xc = FloatArray(3)
        for (k in 0 until 3) {
            var s = 0f
            for (i in 0 until n) s += at(i) * at(i - (t + k - 1))
            xc[k] = s
        }
        val offset = when {
            (xc[2] - xc[0]) > 0.7f * (xc[1] - xc[0]) -> 1
            (xc[0] - xc[2]) > 0.7f * (xc[1] - xc[2]) -> -1
            else -> 0
        }
        if (pg > g) pg = g
        var res = 2 * t + offset
        if (res < minPeriod0) res = minPeriod0
        t0[0] = res
        return pg
    }
}

/**
 * Noise removal as a processing stage: one [RnNoise] per channel, 10 ms frames, samples on
 * RNNoise's 16-bit scale. RNNoise's own 10 ms delay is taken out, so nothing shifts.
 * [strength] 1 = full removal; lower values limit how far bands are turned down
 * (0.5 = at most -20 dB).
 */
class DenoiseStage(private val channels: Int, strength: Float, model: RnnModel) : Stage {
    private val floor = if (strength >= 0.999f) 0f else 10.0.pow(-strength * 40.0 / 20.0).toFloat()
    private val states = Array(channels) { RnNoise(model, floor) }
    private val inBuf = Array(channels) { FloatArray(FRAME) }
    private val outBuf = Array(channels) { FloatArray(FRAME) }
    private var fill = 0
    private var totalIn = 0L
    private var emitted = 0L
    private var skip = FRAME            // RNNoise's own 10 ms delay: its first output is silence
    private var limit = Long.MAX_VALUE  // set when flushing: exactly as long as the input
    private val out = FloatArray(FRAME * channels)

    override fun process(input: FloatArray, frames: Int, emit: (FloatArray, Int) -> Unit) {
        totalIn += frames
        for (f in 0 until frames) {
            for (c in 0 until channels) inBuf[c][fill] = input[f * channels + c] * 32768f
            if (++fill == FRAME) runFrame(emit)
        }
    }

    override fun flush(emit: (FloatArray, Int) -> Unit) {
        // Finish the last frame with silence, and more silence to push out the delayed 10 ms.
        limit = totalIn
        while (emitted < totalIn) {
            for (c in 0 until channels) java.util.Arrays.fill(inBuf[c], fill, FRAME, 0f)
            fill = FRAME
            runFrame(emit)
        }
    }

    private fun runFrame(emit: (FloatArray, Int) -> Unit) {
        for (c in 0 until channels) states[c].process(inBuf[c], outBuf[c])
        fill = 0
        val start = minOf(skip, FRAME)
        skip -= start
        val n = minOf((FRAME - start).toLong(), limit - emitted).toInt()
        if (n <= 0) return
        for (i in 0 until n) for (c in 0 until channels) out[i * channels + c] = outBuf[c][start + i] / 32768f
        emit(out, n)
        emitted += n
    }
}

package com.pixelgo.data.audio

import com.pixelgo.editor.PcmSource
import com.pixelgo.model.AUDIO_RATE
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The project's stored sound format: `audio/<assetId>.pcm`.
 * 16-byte header (int magic "PCM1", int sampleRate, int channels, int reserved, big-endian)
 * followed by 16-bit little-endian interleaved samples (mono or stereo, 48 kHz).
 * Plain PCM is quick to seek and mix; at 48 kHz it uses ~5.8 MB per minute (mono) or ~11.5 MB
 * (stereo).
 */
object PcmFormat {
    const val MAGIC = 0x50434D31 // "PCM1"
    const val HEADER = 16

    /** One waveform peak per this many frames (10 ms). */
    const val FRAMES_PER_PEAK = AUDIO_RATE / 100
}

/**
 * Writes a PCM file and its waveform peaks (`.peaks`: one signed (min, max) byte pair per
 * 10 ms) at the same time. Pure JVM.
 */
class PcmWriter(private val file: File, private val peaksFile: File, val channels: Int) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val peaks = java.io.ByteArrayOutputStream()
    private var frames = 0L
    private var peakFrames = 0
    private var pMin = 0f
    private var pMax = 0f
    private val bytes = ByteArray(8192)

    init {
        val h = ByteBuffer.allocate(PcmFormat.HEADER)
        h.putInt(PcmFormat.MAGIC).putInt(AUDIO_RATE).putInt(channels).putInt(0)
        out.write(h.array())
    }

    val framesWritten: Long get() = frames

    /** Writes [count] interleaved float samples (-1..1); count must be a multiple of channels. */
    fun write(samples: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            val chunk = min(count - i, bytes.size / 2)
            for (k in 0 until chunk) {
                val v = (samples[i + k] * 32767f).coerceIn(-32768f, 32767f).toInt()
                bytes[2 * k] = (v and 0xFF).toByte()
                bytes[2 * k + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes, 0, chunk * 2)
            i += chunk
        }
        // Peaks of the (mono) mix
        var s = 0
        while (s < count) {
            var m = 0f
            for (c in 0 until channels) m += samples[s + c]
            m /= channels
            if (m < pMin) pMin = m
            if (m > pMax) pMax = m
            peakFrames++
            if (peakFrames == PcmFormat.FRAMES_PER_PEAK) flushPeak()
            s += channels
        }
        frames += count / channels
    }

    private fun flushPeak() {
        peaks.write((pMin.coerceIn(-1f, 1f) * 127).toInt())
        peaks.write((pMax.coerceIn(-1f, 1f) * 127).toInt())
        pMin = 0f
        pMax = 0f
        peakFrames = 0
    }

    override fun close() {
        if (peakFrames > 0) flushPeak()
        out.close()
        peaksFile.writeBytes(peaks.toByteArray())
    }

    val durationMs: Long get() = frames * 1000L / AUDIO_RATE
}

/** Reads a stored PCM file from any position. Thread-confined (open one per reader). */
class PcmFile(file: File) : PcmSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val channels: Int
    override val frames: Long
    private var buf = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    init {
        val h = ByteBuffer.allocate(PcmFormat.HEADER)
        raf.channel.read(h, 0)
        h.flip()
        if (h.int != PcmFormat.MAGIC) throw IOException("Not a PixelGo audio file")
        val rate = h.int
        if (rate != AUDIO_RATE) throw IOException("Unexpected sample rate $rate")
        channels = h.int
        frames = (raf.length() - PcmFormat.HEADER) / (2L * channels)
    }

    override fun readStereo(start: Long, count: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, count * 2, 0f)
        val from = max(0L, start)
        val to = min(frames, start + count)
        if (from >= to) return
        val n = (to - from).toInt()
        val bytes = n * 2 * channels
        if (buf.capacity() < bytes) buf = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.clear()
        buf.limit(bytes)
        var pos = PcmFormat.HEADER + from * 2 * channels
        while (buf.hasRemaining()) {
            val r = raf.channel.read(buf, pos)
            if (r <= 0) break
            pos += r
        }
        buf.flip()
        val sb = buf.asShortBuffer()
        val o = ((from - start) * 2).toInt()
        for (i in 0 until min(n, sb.remaining() / channels)) {
            val l = sb.get(i * channels) / 32768f
            val r = if (channels > 1) sb.get(i * channels + 1) / 32768f else l
            out[o + 2 * i] = l
            out[o + 2 * i + 1] = r
        }
    }

    override fun close() = raf.close()
}

/** Waveform peaks: pairs of signed bytes (min, max) per 10 ms. */
object Peaks {
    fun read(file: File): ByteArray = if (file.exists()) file.readBytes() else ByteArray(0)

    /** Largest absolute level (0..1) between two times, for drawing one waveform column. */
    fun level(peaks: ByteArray, fromMs: Long, toMs: Long): Float {
        val a = (fromMs / 10).toInt().coerceAtLeast(0)
        val b = max(a + 1, (toMs / 10).toInt()).coerceAtMost(peaks.size / 2)
        var m = 0
        for (i in a until b) {
            m = max(m, max(abs(peaks[2 * i].toInt()), abs(peaks[2 * i + 1].toInt())))
        }
        return m / 127f
    }
}

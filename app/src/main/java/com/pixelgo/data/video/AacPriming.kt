package com.pixelgo.data.video

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.pixelgo.model.AUDIO_RATE
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AAC encoders start their output with a short "priming" delay (2048 samples = 43 ms on
 * `c2.android.aac.encoder`), and Android doesn't report how long it is. Left alone, exported
 * sound plays that much behind the picture.
 *
 * It is measured once per app run by encoding a short test tone and decoding it again:
 * - [delayFrames]: the delay in 48 kHz frames.
 * - [editList]: whether MediaMuxer writes an MP4 edit list when the sound's timestamps start
 *   below zero. If so, players skip the priming and nothing is lost. If not, the exporter drops
 *   the first [delayFrames] of the mix instead, which keeps sync in every player.
 */
class AacPriming(val delayFrames: Int, val editList: Boolean) {

    override fun toString() = "AacPriming(delayFrames=$delayFrames, editList=$editList)"

    companion object {
        private const val TAG = "PixelGo"
        private const val ONSET = 4800   // the test tone starts 100 ms in
        private const val LENGTH = 14400 // 300 ms of test input
        private const val TIMEOUT_NS = 5_000_000_000L

        /** No compensation (no sound, or the measurement failed). */
        val NONE = AacPriming(0, false)

        @Volatile
        private var measured: AacPriming? = null

        /**
         * This phone's priming. Takes a fraction of a second the first time (then cached), so
         * call it off the UI thread. Returns [NONE] if it can't be measured.
         */
        fun get(): AacPriming = measured ?: synchronized(this) {
            measured ?: try {
                measure().also { measured = it }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't measure the AAC encoder delay", e)
                NONE
            }
        }

        private class Packet(val data: ByteArray, val ptsUs: Long, val flags: Int)

        private fun measure(): AacPriming {
            // A 1 kHz tone from ONSET, with a 2 ms fade-in (an abrupt start smears a little in
            // AAC and skews the measurement by a few samples).
            val input = ShortArray(LENGTH * 2) { i ->
                val t = i / 2 - ONSET
                if (t < 0) {
                    0
                } else {
                    val fade = if (t < 96) 0.5 - 0.5 * cos(PI * t / 96) else 1.0
                    (0.5 * fade * sin(2 * PI * 1000.0 * t / AUDIO_RATE) * 32767).roundToInt().toShort()
                }
            }
            val (packets, format) = encode(input)
            val decoded = decode(packets, format)
            val delay = (onset(decoded) - onset(FloatArray(LENGTH) { input[2 * it] / 32768f })).roundToInt()
            if (delay !in 0..8192) throw IOException("Implausible AAC delay $delay")
            val result = AacPriming(delay, delay > 0 && muxerWritesEditList(packets, format, delay))
            Log.i(TAG, "AAC encoder: $result")
            return result
        }

        private fun encode(input: ShortArray): Pair<List<Packet>, MediaFormat> {
            val codec = MediaCodec.createEncoderByType(VideoExporter.AUDIO_MIME)
            val packets = ArrayList<Packet>()
            var format: MediaFormat? = null
            try {
                codec.configure(VideoExporter.aacFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val total = input.size / 2
                var fed = 0
                var inputDone = false
                val deadline = System.nanoTime() + TIMEOUT_NS
                while (true) {
                    if (System.nanoTime() > deadline) throw IOException("AAC encoder timed out")
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val pts = fed * 1_000_000L / AUDIO_RATE
                            if (fed >= total) {
                                codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val buf = codec.getInputBuffer(idx)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buf.clear()
                                val n = minOf(buf.capacity() / 4, total - fed)
                                buf.asShortBuffer().put(input, fed * 2, n * 2)
                                codec.queueInputBuffer(idx, 0, n * 4, pts, 0)
                                fed += n
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 10_000)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = codec.outputFormat
                    if (out >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val buf = codec.getOutputBuffer(out)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            packets += Packet(ByteArray(info.size).also { buf.get(it) }, info.presentationTimeUs, info.flags)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
            return packets to (format ?: throw IOException("AAC encoder reported no format"))
        }

        /** Decodes the packets as-is (no delay trimming); returns the left channel. */
        private fun decode(packets: List<Packet>, encoderFormat: MediaFormat): FloatArray {
            val format = MediaFormat.createAudioFormat(VideoExporter.AUDIO_MIME, AUDIO_RATE, 2)
            format.setByteBuffer("csd-0", encoderFormat.getByteBuffer("csd-0"))
            val codec = MediaCodec.createDecoderByType(VideoExporter.AUDIO_MIME)
            var out = FloatArray(LENGTH * 2)
            var count = 0
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var next = 0
                var inputDone = false
                var channels = 2
                var float = false
                val deadline = System.nanoTime() + TIMEOUT_NS
                while (true) {
                    if (System.nanoTime() > deadline) throw IOException("AAC decoder timed out")
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            if (next == packets.size) {
                                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val p = packets[next++]
                                codec.getInputBuffer(idx)!!.apply { clear(); put(p.data) }
                                codec.queueInputBuffer(idx, 0, p.data.size, p.ptsUs, 0)
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val s = buf.slice().order(ByteOrder.nativeOrder())
                            val bytes = if (float) 4 else 2
                            val frames = info.size / (bytes * channels)
                            if (count + frames > out.size) out = out.copyOf(maxOf(out.size * 2, count + frames))
                            for (f in 0 until frames) {
                                val p = f * channels * bytes
                                out[count++] = if (float) s.getFloat(p) else s.getShort(p) / 32768f
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
            return out.copyOf(count)
        }

        /** Where (in frames) the 2 ms loudness envelope first reaches half its peak. */
        private fun onset(x: FloatArray): Double {
            val w = 96
            val env = DoubleArray(x.size)
            var sum = 0.0
            for (i in x.indices) {
                sum += x[i].toDouble() * x[i]
                if (i >= w) sum -= x[i - w].toDouble() * x[i - w]
                env[i] = sqrt(maxOf(0.0, sum) / w)
            }
            val half = env.max() / 2
            val i = (1 until env.size).first { env[it] >= half }
            return i - 1 + (half - env[i - 1]) / (env[i] - env[i - 1])
        }

        /** Muxes the packets with timestamps starting at -delay and checks the file reports that delay. */
        private fun muxerWritesEditList(packets: List<Packet>, format: MediaFormat, delay: Int): Boolean {
            val file = File.createTempFile("aac-probe", ".m4a") // the app's cache folder
            try {
                val shiftUs = delay * 1_000_000L / AUDIO_RATE
                val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val track = muxer.addTrack(format)
                    muxer.start()
                    val info = MediaCodec.BufferInfo()
                    for (p in packets) {
                        info.set(0, p.data.size, p.ptsUs - shiftUs, p.flags)
                        muxer.writeSampleData(track, ByteBuffer.wrap(p.data), info)
                    }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(file.path)
                    val f = ex.getTrackFormat(0)
                    return f.containsKey("encoder-delay") && abs(f.getInteger("encoder-delay") - delay) <= 1
                } finally {
                    ex.release()
                }
            } catch (e: Exception) {
                Log.i(TAG, "MediaMuxer can't write an AAC edit list here: $e")
                return false
            } finally {
                file.delete()
            }
        }
    }
}

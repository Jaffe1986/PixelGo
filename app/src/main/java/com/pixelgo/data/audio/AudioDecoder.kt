package com.pixelgo.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.pixelgo.data.video.ExportCancelled
import com.pixelgo.editor.Resampler
import com.pixelgo.model.AUDIO_RATE
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Result of importing a sound file. */
class DecodedAudio(val name: String, val durationMs: Long, val channels: Int)

/**
 * Converts any sound the phone can decode (MP3, AAC/M4A, WAV, OGG, FLAC, Opus, the audio of
 * video files…) into the project's PCM format: 48 kHz, 16-bit, mono or stereo, plus waveform
 * peaks. Runs on a background thread; uses only Android's built-in decoders.
 */
object AudioDecoder {

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n.substringBeforeLast('.')
            }
        }
        return "Sound"
    }

    fun decode(
        context: Context,
        uri: Uri,
        pcm: File,
        peaks: File,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("That file has no sound PixelGo can read")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(mime)
        // The extractor's format may carry "encoder-delay" / "encoder-padding" (MP3 LAME tags,
        // MP4 edit lists). The codec trims those itself, so its output needs no extra trimming;
        // trimming again would make the sound start early (MediaDeviceTest checks this).
        codec.configure(format, null, null, 0)
        codec.start()

        // Output layout (known once the decoder reports its output format).
        var inRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else AUDIO_RATE
        var inChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var writer: PcmWriter? = null
        var resampler: Resampler? = null
        var outChannels = 0

        fun setup() {
            outChannels = if (inChannels == 1) 1 else 2
            writer = PcmWriter(pcm, peaks, outChannels)
            resampler = Resampler(inRate, AUDIO_RATE, outChannels)
        }

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var floats = FloatArray(0)
        try {
            while (!outputDone) {
                if (cancelled()) throw ExportCancelled()
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) progress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 0.99f))
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        // Normally reported once, before any audio; never restart a file mid-way.
                        if (writer == null) setup()
                    }
                    out >= 0 -> {
                        if (writer == null) setup()
                        val buf = codec.getOutputBuffer(out)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val frames = info.size / (bytesPerSample(encoding) * inChannels)
                            if (floats.size < frames * outChannels) floats = FloatArray(frames * outChannels)
                            convert(buf.slice().order(ByteOrder.nativeOrder()), encoding, inChannels, outChannels, frames, floats)
                            if (frames > 0) resampler!!.process(floats, frames) { o, n -> writer!!.write(o, n * outChannels) }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val w = writer ?: throw IOException("No sound decoded")
            resampler!!.flush { o, n -> w.write(o, n * outChannels) }
            w.close()
            progress(1f)
            return DecodedAudio(displayName(context, uri), w.durationMs, outChannels)
        } catch (t: Throwable) {
            try { writer?.close() } catch (_: Exception) {}
            pcm.delete()
            peaks.delete()
            throw t
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
    }

    private fun bytesPerSample(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        else -> 2
    }

    /**
     * Converts [frames] frames of a decoder output buffer (16-bit, float, 8-bit or 24/32-bit
     * PCM) to interleaved floats in [out] with [outCh] channels (mono stays mono; anything
     * else becomes stereo, with surround channels folded in).
     */
    private fun convert(b: ByteBuffer, encoding: Int, inCh: Int, outCh: Int, frames: Int, out: FloatArray) {
        val bytesPerSample = bytesPerSample(encoding)
        fun sample(i: Int): Float {
            val p = i * bytesPerSample
            return when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> ((b.get(p).toInt() and 0xFF) - 128) / 128f
                AudioFormat.ENCODING_PCM_FLOAT -> b.getFloat(p)
                AudioFormat.ENCODING_PCM_32BIT -> b.getInt(p) / 2147483648f
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val v = (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p + 2).toInt() shl 16)
                    v / 8388608f
                }
                else -> b.getShort(p) / 32768f
            }
        }
        for (f in 0 until frames) {
            val base = f * inCh
            if (outCh == 1) {
                out[f] = sample(base)
            } else if (inCh == 2) {
                out[2 * f] = sample(base)
                out[2 * f + 1] = sample(base + 1)
            } else {
                // Surround → stereo: L/R + centre and surrounds at -3 dB, LFE dropped.
                var l = sample(base)
                var r = sample(base + 1)
                if (inCh > 2) { val c = sample(base + 2) * 0.707f; l += c; r += c }
                if (inCh > 4) { l += sample(base + 4) * 0.707f }
                if (inCh > 5) { r += sample(base + 5) * 0.707f }
                if (inCh > 6) { l += sample(base + 6) * 0.707f }
                if (inCh > 7) { r += sample(base + 7) * 0.707f }
                out[2 * f] = (l * 0.6f).coerceIn(-1f, 1f)
                out[2 * f + 1] = (r * 0.6f).coerceIn(-1f, 1f)
            }
        }
    }
}

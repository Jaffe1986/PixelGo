package com.pixelgo.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import com.pixelgo.model.AUDIO_RATE
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

/**
 * Records the microphone (48 kHz mono) straight into the project's PCM format. Remembers when
 * the first recorded sample was captured (System.nanoTime clock) so voice-overs can be lined
 * up with the animation that was playing.
 */
class AudioRecorder(private val pcm: File, private val peaks: File) {
    /** Recent peak level 0..1, for a level meter. */
    @Volatile
    var level = 0f
        private set

    /** System.nanoTime() of the first captured sample (best estimate). */
    @Volatile
    var firstFrameNanos = 0L
        private set

    /** True once [firstFrameNanos] comes from the audio system's timestamp (not a guess). */
    @Volatile
    var timestamped = false
        private set

    /** The input device type (AudioDeviceInfo.TYPE_…), or 0 if unknown. */
    @Volatile
    var inputDeviceType = 0
        private set

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var writer: PcmWriter? = null

    val durationMs: Long get() = writer?.durationMs ?: 0L

    /** Needs the RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(min * 2, AUDIO_RATE / 5 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("The microphone isn't available")
        }
        val w = PcmWriter(pcm, peaks, 1)
        record = rec
        writer = w
        firstFrameNanos = System.nanoTime()
        rec.startRecording()
        running = true
        thread = Thread({
            val buf = ShortArray(2048)
            val f = FloatArray(2048)
            val ts = AudioTimestamp()
            var haveTimestamp = false
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (!haveTimestamp && rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    // Time of frame 0 = time of a known frame minus its distance from frame 0.
                    firstFrameNanos = ts.nanoTime - ts.framePosition * 1_000_000_000L / AUDIO_RATE
                    haveTimestamp = true
                    timestamped = true
                    inputDeviceType = try { rec.routedDevice?.type ?: 0 } catch (_: Exception) { 0 }
                }
                var peak = 0f
                for (i in 0 until n) {
                    f[i] = buf[i] / 32768f
                    peak = max(peak, abs(f[i]))
                }
                level = peak
                w.write(f, n)
            }
        }, "PixelGo-record").apply { start() }
    }

    /** Stops and finishes the file. Returns the recorded length in ms. */
    fun stop(): Long {
        running = false
        thread?.join(2000)
        thread = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
        writer?.close()
        return durationMs
    }

    /** Stops and throws the recording away. */
    fun discard() {
        stop()
        pcm.delete()
        peaks.delete()
    }
}

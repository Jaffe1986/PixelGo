package com.pixelgo.ui.editor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.pixelgo.editor.AudioMixer
import com.pixelgo.model.AUDIO_RATE

/**
 * Plays the project's sound while the animation previews, and acts as the playback clock:
 * the frame on screen is chosen from what is being HEARD, so picture and sound stay in sync.
 * The audio output is always fed (silence when there are no clips) so the clock is steady.
 *
 * Timeline positions are in audio frames (48 kHz). Playback runs from [startFrame] and, when
 * [loop] is on, wraps from [endFrame] back to 0. An optional [preRoll] (the recording count-in,
 * 48 kHz interleaved stereo) plays first; meanwhile the clock stays on [startFrame].
 */
class PreviewPlayer(
    private val mixer: AudioMixer,
    private val startFrame: Long,
    private val endFrame: Long,
    private val loop: Boolean,
    private val preRoll: ShortArray? = null,
) {
    /** Length of [preRoll] in frames; the timeline starts after it. */
    val preRollFrames: Long = (preRoll?.size ?: 0) / 2L

    private val track: AudioTrack
    @Volatile
    private var running = false
    private var thread: Thread? = null

    // Which timeline frame each written frame belongs to: (firstWrittenFrame, timelineFrame).
    private val segments = ArrayList<LongArray>()
    private val lock = Any()

    // Last good output timestamp, interpolated with the system clock between updates.
    private val ts = AudioTimestamp()
    private var tsFrame = -1L
    private var tsNanos = 0L
    private var lastTsCheck = 0L

    init {
        val min = AudioTrack.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, AUDIO_RATE / 10 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start() {
        running = true
        synchronized(lock) { segments += longArrayOf(preRollFrames, startFrame) }
        thread = Thread({ feed() }, "PixelGo-preview").apply { start() }
        track.play()
    }

    private fun feed() {
        val chunk = 1024
        val out = ShortArray(chunk * 2)
        preRoll?.let { pr ->
            var i = 0
            while (running && i < pr.size) {
                val w = track.write(pr, i, minOf(chunk * 2, pr.size - i))
                if (w < 0) return
                i += w
            }
        }
        var t = startFrame
        var written = preRollFrames
        while (running) {
            var n = chunk
            if (t >= endFrame) {
                if (loop) {
                    t = 0
                    synchronized(lock) { segments += longArrayOf(written, 0L) }
                    continue
                }
                java.util.Arrays.fill(out, 0.toShort()) // past the end: keep the clock ticking
            } else {
                if (t + n > endFrame) n = (endFrame - t).toInt()
                mixer.mix(t, n, out)
            }
            val w = track.write(out, 0, n * 2)
            if (w < 0) break
            written += w / 2
            t += w / 2
        }
    }

    /** Frames that have actually come out of the speaker so far. */
    private fun playedFrames(): Long {
        val now = System.nanoTime()
        if (now - lastTsCheck > 50_000_000L) {
            lastTsCheck = now
            if (track.getTimestamp(ts)) {
                tsFrame = ts.framePosition
                tsNanos = ts.nanoTime
            }
        }
        return if (tsFrame >= 0) {
            tsFrame + (now - tsNanos) * AUDIO_RATE / 1_000_000_000L
        } else {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
    }

    /** Frames heard so far, counting the pre-roll (for the count-in display). */
    fun heardFrames(): Long = playedFrames().coerceAtLeast(0)

    /** The output device type (AudioDeviceInfo.TYPE_…), or 0 if unknown. Call before [stop]. */
    fun outputDeviceType(): Int = try { track.routedDevice?.type ?: 0 } catch (_: Exception) { 0 }

    /** Timeline frame (48 kHz) being heard right now. */
    fun currentTimelineFrame(): Long {
        val played = playedFrames().coerceAtLeast(0)
        if (played < preRollFrames) return startFrame
        synchronized(lock) {
            val seg = segments.lastOrNull { it[0] <= played } ?: segments.first()
            return seg[1] + (played - seg[0])
        }
    }

    /**
     * System.nanoTime() at which the first written frame was (or will be) heard, or null if
     * the output hasn't reported a timestamp yet. Used to line up voice-over recordings.
     */
    fun firstFrameNanos(): Long? {
        playedFrames()
        return if (tsFrame >= 0) tsNanos - tsFrame * 1_000_000_000L / AUDIO_RATE else null
    }

    fun stop() {
        running = false
        try {
            track.pause()
            track.flush()
        } catch (_: Exception) {}
        thread?.join(1000)
        thread = null
        track.release()
    }
}

/** Playback clock driven by what's being heard. */
class AudioPlaybackClock(private val player: PreviewPlayer, private val fps: Int) : PlaybackClock {
    override fun nowTicks(): Long = player.currentTimelineFrame() * fps / com.pixelgo.model.AUDIO_RATE
}

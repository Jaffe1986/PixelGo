package com.pixelgo.editor

import com.pixelgo.model.AudioClip
import com.pixelgo.model.FRAMES_PER_MS
import com.pixelgo.model.SoundTrack

/** 48 kHz audio that can be read from any position (an imported or recorded sound). */
interface PcmSource {
    val channels: Int
    val frames: Long

    /**
     * Reads [count] frames from [start] as interleaved STEREO floats (-1..1) into [out]
     * (mono is duplicated to both sides). Frames outside the sound are silence.
     */
    fun readStereo(start: Long, count: Int, out: FloatArray)
}

/** In-memory sound (tests). [data] is interleaved 16-bit samples. */
class MemoryPcm(private val data: ShortArray, override val channels: Int) : PcmSource {
    override val frames: Long get() = (data.size / channels).toLong()

    override fun readStereo(start: Long, count: Int, out: FloatArray) {
        for (i in 0 until count) {
            val f = start + i
            if (f < 0 || f >= frames) {
                out[2 * i] = 0f
                out[2 * i + 1] = 0f
                continue
            }
            val base = (f * channels).toInt()
            val l = data[base] / 32768f
            val r = if (channels > 1) data[base + 1] / 32768f else l
            out[2 * i] = l
            out[2 * i + 1] = r
        }
    }
}

/**
 * Mixes the project's clips (with clip volume × track volume, muted tracks skipped, trims
 * applied) into 48 kHz stereo 16-bit. Used for both preview playback and video export.
 * Takes snapshots of the track/clip lists, so the UI may keep editing meanwhile.
 */
class AudioMixer(
    tracks: List<SoundTrack>,
    clips: List<AudioClip>,
    private val sources: (assetId: Long) -> PcmSource?,
) {
    private val trackById = tracks.associateBy { it.id }
    private val clips = clips.toList()
    private var acc = FloatArray(0)
    private var tmp = FloatArray(0)

    /** End of the last audible clip, in frames. */
    val endFrame: Long = this.clips.filter { trackById[it.trackId]?.muted == false }
        .maxOfOrNull { it.endMs * FRAMES_PER_MS } ?: 0L

    val isSilent: Boolean get() = endFrame == 0L

    /** Mixes timeline frames [start, start + count) into [out] (interleaved stereo). */
    fun mix(start: Long, count: Int, out: ShortArray) {
        if (acc.size < count * 2) {
            acc = FloatArray(count * 2)
            tmp = FloatArray(count * 2)
        }
        java.util.Arrays.fill(acc, 0, count * 2, 0f)
        val end = start + count
        for (c in clips) {
            val track = trackById[c.trackId] ?: continue
            if (track.muted) continue
            val gain = c.volume * track.volume
            if (gain <= 0f) continue
            val cs = c.startMs * FRAMES_PER_MS
            val ce = cs + c.durationMs * FRAMES_PER_MS
            val from = maxOf(start, cs)
            val to = minOf(end, ce)
            if (from >= to) continue
            val src = sources(c.assetId) ?: continue
            val n = (to - from).toInt()
            src.readStereo(c.inMs * FRAMES_PER_MS + (from - cs), n, tmp)
            val off = ((from - start) * 2).toInt()
            for (i in 0 until n * 2) acc[off + i] += tmp[i] * gain
        }
        for (i in 0 until count * 2) {
            val v = acc[i] * 32767f
            out[i] = when {
                v > 32767f -> 32767
                v < -32768f -> -32768
                else -> v.toInt().toShort()
            }
        }
    }
}

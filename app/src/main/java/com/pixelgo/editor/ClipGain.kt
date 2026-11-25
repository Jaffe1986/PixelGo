package com.pixelgo.editor

import com.pixelgo.model.AudioClip
import com.pixelgo.model.FRAMES_PER_MS
import com.pixelgo.model.SoundTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * How loud a clip is at each moment:
 * clip volume × track volume × volume line × fade-in/out × crossfades.
 *
 * - User fades use a smooth S-curve.
 * - Where a clip starts inside the previous clip on the same track, the two crossfade (equal
 *   power) over the overlap.
 * - Used per sample by [AudioMixer] and per pixel column for drawing waveforms.
 */
class ClipGain(
    clip: AudioClip,
    trackVolume: Float = 1f,
    /** Overlap (ms) with the clip before this one on the track: fade in over it. */
    xfadeInMs: Long = 0,
    /** Overlap (ms) with the clip after this one: fade out over it. */
    xfadeOutMs: Long = 0,
) {
    /** Volume before fades and the volume line. */
    val base: Float = clip.volume * trackVolume

    private val durF = max(1L, clip.durationMs * FRAMES_PER_MS)
    private val fadeInF: Long
    private val fadeOutF: Long
    private val xInF = minOf(xfadeInMs * FRAMES_PER_MS, durF)
    private val xOutF = minOf(xfadeOutMs * FRAMES_PER_MS, durF)
    private val inF = clip.inMs * FRAMES_PER_MS
    private val envAt = LongArray(clip.envelope.size) { clip.envelope[it].atMs * FRAMES_PER_MS }
    private val envGain = FloatArray(clip.envelope.size) { clip.envelope[it].gain }

    init {
        // Fades longer than the clip together are shortened in proportion.
        val fi = max(0L, clip.fadeInMs) * FRAMES_PER_MS
        val fo = max(0L, clip.fadeOutMs) * FRAMES_PER_MS
        if (fi + fo > durF) {
            fadeInF = durF * fi / (fi + fo)
            fadeOutF = durF - fadeInF
        } else {
            fadeInF = fi
            fadeOutF = fo
        }
    }

    /** True when the gain is [base] everywhere (the fast path while mixing). */
    val isFlat: Boolean = fadeInF == 0L && fadeOutF == 0L && xInF == 0L && xOutF == 0L && envAt.isEmpty()

    /** Gains for [n] frames starting [offset] frames into the clip, into [out]. */
    fun fill(offset: Long, n: Int, out: FloatArray) {
        for (k in 0 until n) out[k] = edgeGain(offset + k)
        if (envAt.isEmpty()) return
        // Walk the volume line alongside the samples.
        val s0 = inF + offset
        var j = upper(s0) - 1 // last point at or before s0, or -1
        for (k in 0 until n) {
            val s = s0 + k
            while (j + 1 < envAt.size && envAt[j + 1] <= s) j++
            out[k] *= envelopeAt(j, s.toDouble())
        }
    }

    /** Gain [offsetMs] into the clip (for drawing). */
    fun atOffsetMs(offsetMs: Double): Float {
        val pos = (offsetMs * FRAMES_PER_MS).toLong().coerceIn(0, durF - 1)
        var g = edgeGain(pos)
        if (envAt.isNotEmpty()) {
            val s = inF + offsetMs * FRAMES_PER_MS
            g *= envelopeAt(upper(s.toLong()) - 1, s)
        }
        return g
    }

    private fun edgeGain(pos: Long): Float {
        var g = base
        val fromEnd = durF - pos
        if (pos < fadeInF) g *= sCurve(pos.toDouble() / fadeInF)
        if (fromEnd < fadeOutF) g *= sCurve(fromEnd.toDouble() / fadeOutF)
        if (pos < xInF) g *= sin(PI / 2 * pos / xInF).toFloat()
        if (fromEnd < xOutF) g *= sin(PI / 2 * fromEnd / xOutF).toFloat()
        return g
    }

    /** Volume line value at source frame [s], where [j] is the last point at or before it. */
    private fun envelopeAt(j: Int, s: Double): Float = when {
        j < 0 -> envGain[0]
        j >= envAt.size - 1 -> envGain[envAt.size - 1]
        else -> {
            val a = envAt[j]
            val b = envAt[j + 1]
            if (b == a) envGain[j + 1] else (envGain[j] + (envGain[j + 1] - envGain[j]) * ((s - a) / (b - a))).toFloat()
        }
    }

    /** Index of the first point after source frame [s]. */
    private fun upper(s: Long): Int {
        var lo = 0
        var hi = envAt.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (envAt[mid] <= s) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private fun sCurve(x: Double): Float = (0.5 - 0.5 * cos(PI * x.coerceIn(0.0, 1.0))).toFloat()

        /**
         * Gains for all [clips], with crossfades where a clip starts inside the previous clip on
         * the same track (and ends after it). Clips stacked exactly on top of each other just mix.
         */
        fun all(tracks: List<SoundTrack>, clips: List<AudioClip>, withTrackVolume: Boolean = true): Map<Long, ClipGain> {
            val trackVolume = tracks.associate { it.id to it.volume }
            val xIn = HashMap<Long, Long>()
            val xOut = HashMap<Long, Long>()
            for ((_, list) in clips.groupBy { it.trackId }) {
                val sorted = list.sortedWith(compareBy({ it.startMs }, { it.id }))
                for (i in sorted.indices) {
                    val a = sorted[i]
                    for (j in i + 1 until sorted.size) {
                        val b = sorted[j]
                        if (b.startMs >= a.endMs) break
                        if (b.startMs > a.startMs && b.endMs > a.endMs) {
                            val overlap = a.endMs - b.startMs
                            xOut[a.id] = max(xOut[a.id] ?: 0L, overlap)
                            xIn[b.id] = max(xIn[b.id] ?: 0L, overlap)
                        }
                    }
                }
            }
            return clips.associate {
                it.id to ClipGain(
                    it, if (withTrackVolume) trackVolume[it.trackId] ?: 1f else 1f,
                    xIn[it.id] ?: 0L, xOut[it.id] ?: 0L,
                )
            }
        }
    }
}

package com.pixelgo.model

/** Sample rate of every stored audio file and of all mixing / export. */
const val AUDIO_RATE = 48_000

/** Audio frames (samples per channel) per millisecond. */
const val FRAMES_PER_MS = AUDIO_RATE / 1000

/** A row in the audio part of the timeline (e.g. "Music", "Voice"). */
data class SoundTrack(
    val id: Long,
    val name: String,
    val muted: Boolean = false,
    val volume: Float = 1f,
)

/** Highest clip volume (+12 dB), e.g. after normalizing a quiet recording. */
const val MAX_CLIP_VOLUME = 4f

/**
 * A point on a clip's volume line: [atMs] is a position in the SOUND (asset time, like
 * [AudioClip.inMs]), so moving or trimming the clip never shifts it; [gain] is 0..[MAX_CLIP_VOLUME].
 */
data class GainPoint(val atMs: Long, val gain: Float)

/**
 * A piece of an [AudioAsset] placed on a track. Times are in MILLISECONDS on the timeline, so
 * changing the animation's fps never shifts the sound. [inMs]..[outMs] is the part of the
 * asset that plays (trim); it starts playing at [startMs].
 *
 * Levels (applied while mixing, nothing is rendered): [volume], a fade-in over the first
 * [fadeInMs] and a fade-out over the last [fadeOutMs], and a volume line ([envelope], sorted by
 * [GainPoint.atMs]; empty = flat). Where two clips overlap on a track they crossfade.
 */
data class AudioClip(
    val id: Long,
    val trackId: Long,
    val assetId: Long,
    val startMs: Long,
    val inMs: Long,
    val outMs: Long,
    val volume: Float = 1f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val envelope: List<GainPoint> = emptyList(),
) {
    val durationMs: Long get() = outMs - inMs
    val endMs: Long get() = startMs + durationMs

    /** Has fades or a volume line (saved in their own section of the project file). */
    val hasLevels: Boolean get() = fadeInMs > 0 || fadeOutMs > 0 || envelope.isNotEmpty()

    /** Position in the sound (asset ms) that plays at timeline time [timelineMs]. */
    fun sourceMs(timelineMs: Long): Long = inMs + (timelineMs - startMs)
}

/** A sound stored with the project (imported or recorded), as 48 kHz 16-bit PCM. */
data class AudioAsset(
    val id: Long,
    val name: String,
    val durationMs: Long,
    val channels: Int,
)

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

/**
 * A piece of an [AudioAsset] placed on a track. Times are in MILLISECONDS on the timeline, so
 * changing the animation's fps never shifts the sound. [inMs]..[outMs] is the part of the
 * asset that plays (trim); it starts playing at [startMs].
 */
data class AudioClip(
    val id: Long,
    val trackId: Long,
    val assetId: Long,
    val startMs: Long,
    val inMs: Long,
    val outMs: Long,
    val volume: Float = 1f,
) {
    val durationMs: Long get() = outMs - inMs
    val endMs: Long get() = startMs + durationMs
}

/** A sound stored with the project (imported or recorded), as 48 kHz 16-bit PCM. */
data class AudioAsset(
    val id: Long,
    val name: String,
    val durationMs: Long,
    val channels: Int,
)

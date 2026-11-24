package com.pixelgo.ui.editor

/**
 * Where playback is in time, in ticks (1/fps s) since the animation start. Playback asks the
 * clock which frame to show, so a later audio-driven clock can replace the system clock
 * without changing the playback loop.
 */
interface PlaybackClock {
    fun nowTicks(): Long
}

/** Wall-clock playback starting at [startTick]. */
class SystemPlaybackClock(private val fps: Int, private val startTick: Long) : PlaybackClock {
    private val t0 = System.nanoTime()
    override fun nowTicks(): Long = startTick + (System.nanoTime() - t0) * fps / 1_000_000_000L
}

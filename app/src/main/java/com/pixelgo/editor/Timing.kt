package com.pixelgo.editor

/**
 * Animation timing in "ticks" (1 tick = 1/fps second). Frame i covers ticks
 * [startTick(i), endTick(i)). All values are Long: thousands of frames × long holds × ms
 * would overflow Int.
 */
class Timing(holds: IntArray) {
    private val starts = LongArray(holds.size + 1)

    init {
        for (i in holds.indices) starts[i + 1] = starts[i] + holds[i]
    }

    val frameCount: Int = holds.size

    val totalTicks: Long get() = starts[frameCount]

    fun startTick(frame: Int): Long = starts[frame]

    fun endTick(frame: Int): Long = starts[frame + 1]

    /** The frame on screen at [tick]. Ticks outside the animation are clamped to it. */
    fun frameAtTick(tick: Long): Int {
        if (frameCount == 0) return 0
        val t = tick.coerceIn(0, totalTicks - 1)
        // Largest i with starts[i] <= t
        var lo = 0
        var hi = frameCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        fun ticksToMs(ticks: Long, fps: Int): Long = ticks * 1000L / fps
        fun ticksToUs(ticks: Long, fps: Int): Long = ticks * 1_000_000L / fps
        fun msToTicks(ms: Long, fps: Int): Long = ms * fps / 1000L

        /** "m:ss.cc" for timeline labels. */
        fun formatMs(ms: Long): String {
            val total = ms.coerceAtLeast(0)
            val m = total / 60_000
            val s = (total / 1000) % 60
            val cs = (total / 10) % 100
            return "%d:%02d.%02d".format(m, s, cs)
        }
    }
}

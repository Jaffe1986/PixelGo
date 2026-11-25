package com.pixelgo.ui.editor

import com.pixelgo.data.audio.PcmFile
import com.pixelgo.model.AUDIO_RATE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The actual samples of sounds, for drawing waveforms when zoomed in closer than the 10 ms
 * peaks allow. Keeps the most recently drawn 1-second blocks (mono: the average of both sides)
 * and loads missing ones in the background; [onLoaded] asks for a redraw.
 * All calls happen on the main thread.
 */
class WaveformCache(
    private val scope: CoroutineScope,
    private val fileFor: (assetId: Long) -> File,
    private val onLoaded: () -> Unit,
) {
    private val blocks = object : LinkedHashMap<Long, ShortArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ShortArray>?) = size > MAX_BLOCKS
    }
    private val loading = HashSet<Long>()

    private fun key(assetId: Long, block: Long) = (assetId shl 24) or block

    /** Block [block] of [assetId], or null while it loads (it is requested). */
    private fun block(assetId: Long, block: Long): ShortArray? {
        val k = key(assetId, block)
        blocks[k]?.let { return it }
        if (loading.add(k)) {
            scope.launch {
                val data = withContext(Dispatchers.IO) {
                    try {
                        PcmFile(fileFor(assetId)).use { p ->
                            val start = block * AUDIO_RATE
                            val n = minOf(AUDIO_RATE.toLong(), p.frames - start).toInt().coerceAtLeast(0)
                            val st = FloatArray(n * 2)
                            p.readStereo(start, n, st)
                            ShortArray(n) { ((st[2 * it] + st[2 * it + 1]) * 0.5f * 32767f).toInt().toShort() }
                        }
                    } catch (_: Exception) {
                        ShortArray(0)
                    }
                }
                blocks[k] = data
                loading.remove(k)
                onLoaded()
            }
        }
        return null
    }

    /** One sample (-1..1) at source frame [frame], or null while loading / past the end. */
    fun sample(assetId: Long, frame: Long): Float? {
        if (frame < 0) return null
        val b = block(assetId, frame / AUDIO_RATE) ?: return null
        val i = (frame % AUDIO_RATE).toInt()
        return if (i < b.size) b[i] / 32768f else 0f
    }

    /**
     * Lowest and highest sample (-1..1) in source frames [from, to) into [out] (min, max).
     * Returns false if part of it is still loading.
     */
    fun minMax(assetId: Long, from: Long, to: Long, out: FloatArray): Boolean {
        var lo = 0
        var hi = 0
        var f = maxOf(0L, from)
        while (f < to) {
            val bi = f / AUDIO_RATE
            val b = block(assetId, bi) ?: return false
            val end = minOf(to, (bi + 1) * AUDIO_RATE)
            var i = (f - bi * AUDIO_RATE).toInt()
            val last = minOf(b.size, (end - bi * AUDIO_RATE).toInt())
            while (i < last) {
                val v = b[i].toInt()
                if (v < lo) lo = v
                if (v > hi) hi = v
                i++
            }
            f = end
        }
        out[0] = lo / 32768f
        out[1] = hi / 32768f
        return true
    }

    /** Forget a sound (its file changed or was removed). */
    fun forget(assetId: Long) {
        blocks.keys.removeAll { (it shr 24) == assetId }
    }

    private companion object {
        const val MAX_BLOCKS = 40 // about 4 MB
    }
}

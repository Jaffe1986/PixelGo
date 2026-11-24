package com.pixelgo.editor

/** Identifies one cel: a layer's pixels on one frame. */
data class CelKey(val layerId: Long, val frameUid: Long)

/**
 * A changed cel captured for saving. [pixels] is a private copy (null = fully transparent,
 * which means "delete the file"). [version] lets the store tell whether the cel changed again
 * while it was being written.
 */
class DirtyCel(val key: CelKey, val pixels: IntArray?, val version: Long)

/**
 * Where pixel data lives. Each cel is width*height ARGB ints; a missing cel is fully
 * transparent. Implementations must be thread-safe: the UI thread edits while background
 * threads read for thumbnails, playback and export.
 */
interface CelStore {
    val celSize: Int

    /** Pixels for reading, or null if the cel is empty. Callers must not modify the array. */
    fun read(layerId: Long, frameUid: Long): IntArray?

    /**
     * Pixels for editing (created blank if needed). Marks the cel changed; call it every time
     * before writing into the array so saves pick up the change.
     */
    fun edit(layerId: Long, frameUid: Long): IntArray

    /**
     * Stores [pixels] for [key] directly (null or all-transparent = empty). Used by bulk
     * operations so large jobs don't accumulate in memory. The array is not kept.
     */
    fun put(key: CelKey, pixels: IntArray?)

    /** Makes [dst] a copy of [src] (empty if [src] is empty). */
    fun copy(src: CelKey, dst: CelKey)

    /** Increases whenever any cel of the frame changes (used to refresh thumbnails). */
    fun frameVersion(frameUid: Long): Long

    /** Cels that should stay in memory (the frame being edited). */
    fun pin(keys: Collection<CelKey>) {}

    companion object {
        fun isEmpty(px: IntArray?): Boolean {
            if (px == null) return true
            for (v in px) if (v != 0) return false
            return true
        }
    }
}

/** Simple in-memory store, used by unit tests. */
class MemoryCelStore(override val celSize: Int) : CelStore {
    private val cels = HashMap<CelKey, IntArray>()
    private val versions = HashMap<Long, Long>()

    @Synchronized
    override fun read(layerId: Long, frameUid: Long): IntArray? = cels[CelKey(layerId, frameUid)]

    @Synchronized
    override fun edit(layerId: Long, frameUid: Long): IntArray {
        bump(frameUid)
        return cels.getOrPut(CelKey(layerId, frameUid)) { IntArray(celSize) }
    }

    @Synchronized
    override fun put(key: CelKey, pixels: IntArray?) {
        if (CelStore.isEmpty(pixels)) cels.remove(key) else cels[key] = pixels!!.copyOf()
        bump(key.frameUid)
    }

    @Synchronized
    override fun copy(src: CelKey, dst: CelKey) = put(dst, cels[src])

    @Synchronized
    override fun frameVersion(frameUid: Long): Long = versions[frameUid] ?: 0L

    @Synchronized
    fun keys(): Set<CelKey> = cels.keys.toSet()

    private fun bump(frameUid: Long) {
        versions[frameUid] = (versions[frameUid] ?: 0L) + 1
    }
}

package com.pixelgo.data

import com.pixelgo.editor.CelKey
import com.pixelgo.editor.CelStore
import com.pixelgo.editor.DirtyCel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Cels stored as one small compressed file each (`<layerId>_<frameUid>.cel` in [dir]), with an
 * LRU cache of decoded pixels. Empty (fully transparent) cels have no file. Pure JVM (no
 * Android) so it can be unit-tested.
 *
 * Memory rules:
 * - Clean cels are evicted least-recently-used once the cache is over budget.
 * - Dirty (edited, not yet saved) and pinned (current frame) cels are never evicted.
 *   Saving flushes dirty cels: [snapshotDirty] (UI thread) → [writeSnapshot] (IO thread) →
 *   [markClean], which only clears a cel if it wasn't edited again meanwhile.
 * - Bulk operations ([put], [copy]) write straight to disk and keep nothing in memory.
 *
 * Every public method is thread-safe.
 */
class DiskCelStore(
    private val dir: File,
    override val celSize: Int,
    cacheBytes: Long = 48L shl 20,
) : CelStore {
    private val maxCached: Int = maxOf(64L, cacheBytes / (celSize * 4L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private val onDisk = HashSet<CelKey>()
    private val cache = LinkedHashMap<CelKey, IntArray>(64, 0.75f, true) // access order = LRU
    private val dirty = HashMap<CelKey, Long>()
    private val frameVersions = HashMap<Long, Long>()
    private var version = 0L
    private var pinned: Set<CelKey> = emptySet()

    init {
        dir.mkdirs()
        dir.listFiles()?.forEach { f ->
            when {
                f.name.endsWith(".tmp") -> f.delete()
                f.name.endsWith(".cel") -> parseKey(f.name)?.let { onDisk += it }
            }
        }
    }

    // ---- CelStore ------------------------------------------------------------------------

    @Synchronized
    override fun read(layerId: Long, frameUid: Long): IntArray? {
        val key = CelKey(layerId, frameUid)
        cache[key]?.let { return it }
        if (key !in onDisk) return null
        val px = load(key)
        if (px == null) {
            onDisk.remove(key)
            return null
        }
        cache[key] = px
        evict()
        return px
    }

    @Synchronized
    override fun edit(layerId: Long, frameUid: Long): IntArray {
        val key = CelKey(layerId, frameUid)
        val px = cache[key]
            ?: ((if (key in onDisk) load(key) else null) ?: IntArray(celSize)).also { cache[key] = it }
        dirty[key] = ++version
        bump(frameUid)
        evict()
        return px
    }

    @Synchronized
    override fun put(key: CelKey, pixels: IntArray?) {
        cache.remove(key)
        dirty.remove(key)
        writeFileLocked(key, if (CelStore.isEmpty(pixels)) null else pixels)
        bump(key.frameUid)
    }

    @Synchronized
    override fun copy(src: CelKey, dst: CelKey) {
        val cached = cache[src]
        cache.remove(dst)
        dirty.remove(dst)
        when {
            cached != null -> writeFileLocked(dst, if (CelStore.isEmpty(cached)) null else cached)
            src in onDisk -> {
                val tmp = tmpFor(dst)
                fileFor(src).copyTo(tmp, overwrite = true)
                commitTmp(tmp, fileFor(dst))
                onDisk += dst
            }
            else -> writeFileLocked(dst, null)
        }
        bump(dst.frameUid)
    }

    @Synchronized
    override fun frameVersion(frameUid: Long): Long = frameVersions[frameUid] ?: 0L

    @Synchronized
    override fun pin(keys: Collection<CelKey>) {
        pinned = keys.toHashSet()
        evict()
    }

    // ---- Saving --------------------------------------------------------------------------

    /** Private copies of every changed cel. Call on the UI thread (where edits happen). */
    @Synchronized
    fun snapshotDirty(): List<DirtyCel> = dirty.map { (key, v) ->
        val px = cache[key]
        DirtyCel(key, if (px == null || CelStore.isEmpty(px)) null else px.copyOf(), v)
    }

    /** Writes a snapshot to disk. Call on the IO thread. Empty cels delete their file. */
    fun writeSnapshot(cels: List<DirtyCel>) {
        for (c in cels) {
            val px = c.pixels
            if (px == null) {
                synchronized(this) {
                    fileFor(c.key).delete()
                    onDisk.remove(c.key)
                }
            } else {
                val tmp = tmpFor(c.key)
                writeCelFile(tmp, px) // slow part, outside the lock
                synchronized(this) {
                    commitTmp(tmp, fileFor(c.key))
                    onDisk += c.key
                }
            }
        }
    }

    /** Clears the dirty flag of cels that weren't edited again since [snapshotDirty]. */
    @Synchronized
    fun markClean(cels: List<DirtyCel>) {
        for (c in cels) if (dirty[c.key] == c.version) dirty.remove(c.key)
        evict()
    }

    @Synchronized
    fun dirtyCount(): Int = dirty.size

    /**
     * Deletes cels whose layer or frame is no longer referenced. Ids at or above the bounds
     * were created after the references were collected, so they are always kept.
     */
    fun collectGarbage(keepLayers: Set<Long>, keepFrames: Set<Long>, layerBound: Long, frameBound: Long): Int {
        fun keep(k: CelKey) = k.layerId >= layerBound || k.frameUid >= frameBound ||
            (k.layerId in keepLayers && k.frameUid in keepFrames)
        val doomed = synchronized(this) { (onDisk + dirty.keys).filterNot(::keep) }
        for (k in doomed) {
            synchronized(this) {
                fileFor(k).delete()
                onDisk.remove(k)
                cache.remove(k)
                dirty.remove(k)
            }
        }
        return doomed.size
    }

    @Synchronized
    fun keysOnDisk(): Set<CelKey> = onDisk.toSet()

    // ---- Files ---------------------------------------------------------------------------

    private fun fileFor(key: CelKey) = File(dir, "${key.layerId}_${key.frameUid}.cel")
    private fun tmpFor(key: CelKey) = File(dir, "${key.layerId}_${key.frameUid}.cel.tmp")

    private fun writeFileLocked(key: CelKey, pixels: IntArray?) {
        if (pixels == null) {
            fileFor(key).delete()
            onDisk.remove(key)
            return
        }
        val tmp = tmpFor(key)
        writeCelFile(tmp, pixels)
        commitTmp(tmp, fileFor(key))
        onDisk += key
    }

    private fun commitTmp(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not write ${target.name}")
        }
    }

    private fun load(key: CelKey): IntArray? = readCelFile(fileFor(key), celSize)

    private fun bump(frameUid: Long) {
        frameVersions[frameUid] = (frameVersions[frameUid] ?: 0L) + 1
    }

    private fun evict() {
        if (cache.size <= maxCached) return
        val it = cache.entries.iterator()
        while (cache.size > maxCached && it.hasNext()) {
            val k = it.next().key
            if (k !in dirty && k !in pinned) it.remove()
        }
    }

    companion object {
        private const val MAGIC = 0x43454C31 // "CEL1"

        fun parseKey(name: String): CelKey? {
            val base = name.removeSuffix(".cel")
            val us = base.indexOf('_')
            if (us <= 0) return null
            val l = base.substring(0, us).toLongOrNull() ?: return null
            val f = base.substring(us + 1).toLongOrNull() ?: return null
            return CelKey(l, f)
        }

        /** Reads a cel file; null if it is missing, damaged, or not [size] pixels. */
        fun readCelFile(file: File, size: Int): IntArray? = try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { d ->
                if (d.readInt() != MAGIC) return null
                val n = d.readInt()
                if (n != size) return null
                val bytes = ByteArray(n * 4)
                DataInputStream(InflaterInputStream(d)).readFully(bytes)
                IntArray(n).also { ByteBuffer.wrap(bytes).asIntBuffer().get(it) }
            }
        } catch (e: IOException) {
            null
        }

        fun writeCelFile(file: File, pixels: IntArray) {
            val bytes = ByteBuffer.allocate(pixels.size * 4).also { it.asIntBuffer().put(pixels) }.array()
            val deflater = Deflater(Deflater.BEST_SPEED)
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { d ->
                    d.writeInt(MAGIC)
                    d.writeInt(pixels.size)
                    d.flush()
                    val z = DeflaterOutputStream(d, deflater)
                    z.write(bytes)
                    z.finish()
                    z.flush()
                }
            } finally {
                deflater.end()
            }
        }
    }
}

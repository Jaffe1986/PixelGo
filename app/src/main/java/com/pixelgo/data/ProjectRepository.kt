package com.pixelgo.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.pixelgo.editor.ColorMath
import com.pixelgo.editor.DirtyCel
import com.pixelgo.model.PixelProject
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class ProjectSummary(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val modified: Long,
    val frameCount: Int,
    val durationMs: Long,
    val thumbnail: File,
)

/** A project opened for editing: its metadata plus the store holding its pixels. */
class OpenedProject(val project: PixelProject, val store: DiskCelStore)

/**
 * Everything needed to save, captured on the UI thread so the IO thread never touches live
 * editor objects (see docs/ARCHITECTURE.md, "Saving").
 */
class SaveSnapshot(
    val projectId: String,
    val meta: ByteArray,
    val store: DiskCelStore,
    val dirty: List<DirtyCel>,
    val keepLayers: Set<Long>,
    val keepFrames: Set<Long>,
    val layerBound: Long,
    val frameBound: Long,
    val thumbLayerIds: LongArray,
    val thumbFrameUid: Long,
    val width: Int,
    val height: Int,
    val keepAssets: Set<Long> = emptySet(),
    val assetBound: Long = Long.MAX_VALUE,
)

/**
 * Projects live in app-private storage (nothing leaves the phone), one folder each:
 * `files/projects/<id>/project.meta`, `cels/`, `thumb.png`.
 *
 * All disk work runs on one background thread, so anything queued on [dispatcher] sees the
 * latest save. Old single-file projects (`<id>.pxg`) are migrated on that thread at startup,
 * before anything else runs; the original is kept as `<id>.pxg.bak`.
 */
class ProjectRepository(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()

    init {
        executor.execute { migrateLegacy() }
    }

    private fun folder(id: String) = File(root, id)
    private fun metaFile(id: String) = File(folder(id), Migration.META_FILE)
    private fun celDir(id: String) = File(folder(id), Migration.CELS_DIR)
    private fun thumbFile(id: String) = File(folder(id), "thumb.png")

    /** Where a project's sounds live (`<assetId>.pcm` + `<assetId>.peaks`). */
    fun audioDir(id: String) = File(folder(id), "audio").apply { mkdirs() }

    /** Deletes sound files whose asset id isn't kept (ids at or above [bound] are new: kept). */
    private fun collectAudio(id: String, keep: Set<Long>, bound: Long) {
        audioDir(id).listFiles()?.forEach { f ->
            val assetId = f.name.substringBefore('.').toLongOrNull() ?: return@forEach
            if (assetId < bound && assetId !in keep) f.delete()
        }
    }

    // ---- Migration -----------------------------------------------------------------------

    private fun migrateLegacy() {
        val legacy = root.listFiles { f -> f.isFile && f.name.endsWith(".pxg") }.orEmpty()
        for (pxg in legacy) {
            val id = pxg.name.removeSuffix(".pxg")
            try {
                Migration.migrate(pxg, folder(id), id)
                val oldThumb = File(root, "$id.png")
                if (oldThumb.exists() && !oldThumb.renameTo(thumbFile(id))) oldThumb.delete()
                if (!pxg.renameTo(File(root, "$id.pxg.bak"))) Log.w(TAG, "Could not rename $pxg")
                Log.i(TAG, "Migrated $id to the folder format")
            } catch (e: Exception) {
                Log.e(TAG, "Migration of $id failed; original left untouched", e)
            }
        }
    }

    // ---- Listing / opening -----------------------------------------------------------------

    /** Call on [dispatcher]. */
    fun list(): List<ProjectSummary> =
        root.listFiles { f -> f.isDirectory && File(f, Migration.META_FILE).exists() }.orEmpty().mapNotNull { dir ->
            try {
                val h = File(dir, Migration.META_FILE).inputStream().buffered().use { MetaSerializer.readHeader(it) }
                ProjectSummary(dir.name, h.name, h.width, h.height, h.modified, h.frameCount, h.durationMs, thumbFile(dir.name))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unreadable project ${dir.name}", e)
                null
            }
        }.sortedByDescending { it.modified }

    /** Opens a project for editing. Call on [dispatcher]. */
    fun open(id: String): OpenedProject? = try {
        val p = metaFile(id).inputStream().buffered().use { MetaSerializer.read(id, it) }
        val store = DiskCelStore(celDir(id), p.size)
        // Nothing but the saved project can refer to cels at this point (undo history is
        // gone), so remove anything left over from an interrupted session.
        store.collectGarbage(
            p.layers.map { it.id }.toSet(), p.frames.map { it.uid }.toSet(),
            Long.MAX_VALUE, Long.MAX_VALUE,
        )
        // Same for sounds: keep only those a clip uses.
        val used = p.clips.map { it.assetId }.toSet()
        p.assets.removeAll { it.id !in used }
        collectAudio(id, used, Long.MAX_VALUE)
        OpenedProject(p, store)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open $id", e)
        null
    }

    /** Creates an empty project and returns its id. Call on [dispatcher]. */
    fun create(name: String, width: Int, height: Int): String {
        val id = UUID.randomUUID().toString()
        val p = PixelProject.create(id, name, width, height)
        celDir(id).mkdirs()
        writeMeta(id, MetaSerializer.toBytes(p, System.currentTimeMillis()))
        writeThumb(id, IntArray(p.size), width, height)
        return id
    }

    // ---- Saving --------------------------------------------------------------------------

    /** Queues a save. Order: cels → meta → remove unreferenced cels → thumbnail. */
    fun save(snap: SaveSnapshot) = executor.execute {
        try {
            snap.store.writeSnapshot(snap.dirty)
            writeMeta(snap.projectId, snap.meta)
            snap.store.collectGarbage(snap.keepLayers, snap.keepFrames, snap.layerBound, snap.frameBound)
            collectAudio(snap.projectId, snap.keepAssets, snap.assetBound)
            val thumb = ColorMath.compositeFrame(snap.thumbLayerIds, snap.store, snap.thumbFrameUid)
            writeThumb(snap.projectId, thumb, snap.width, snap.height)
            snap.store.markClean(snap.dirty)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ${snap.projectId}", e)
        }
    }

    // ---- Gallery operations (call on [dispatcher]) -----------------------------------------

    fun rename(id: String, newName: String) {
        val p = metaFile(id).inputStream().buffered().use { MetaSerializer.read(id, it) }
        p.name = newName
        writeMeta(id, MetaSerializer.toBytes(p, System.currentTimeMillis()))
    }

    fun duplicate(id: String) {
        val newId = UUID.randomUUID().toString()
        val work = File(root, "$newId.copying")
        folder(id).copyRecursively(work, overwrite = true)
        if (!work.renameTo(folder(newId))) {
            work.deleteRecursively()
            return
        }
        val p = metaFile(newId).inputStream().buffered().use { MetaSerializer.read(newId, it) }
        p.name = "${p.name} copy"
        writeMeta(newId, MetaSerializer.toBytes(p, System.currentTimeMillis()))
    }

    fun delete(id: String) {
        folder(id).deleteRecursively()
        File(root, "$id.pxg.bak").delete()
        File(root, "$id.pxg").delete()
        File(root, "$id.png").delete()
    }

    // ---- Files ---------------------------------------------------------------------------

    private fun writeMeta(id: String, bytes: ByteArray) {
        val target = metaFile(id)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun writeThumb(id: String, pixels: IntArray, w: Int, h: Int) {
        val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val target = thumbFile(id)
        val tmp = File(target.parentFile, "thumb.png.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        private const val TAG = "ProjectRepository"
    }
}

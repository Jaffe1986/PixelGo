package com.pixelgo.data

import com.pixelgo.editor.CelKey
import com.pixelgo.editor.CelStore
import com.pixelgo.model.Frame
import com.pixelgo.model.Layer
import com.pixelgo.model.PixelProject
import java.io.File
import java.io.IOException

/**
 * Converts an old single-file `.pxg` project into the folder format (v3). Pure JVM so it can
 * be unit-tested. The original file is never modified here; the caller renames it to
 * `.pxg.bak` only after this returns successfully.
 */
object Migration {
    const val META_FILE = "project.meta"
    const val CELS_DIR = "cels"

    /**
     * Writes [folder] (meta + cels) from [pxg], re-reads everything and compares it with the
     * original before putting the folder in place. Throws if anything doesn't match.
     */
    fun migrate(pxg: File, folder: File, id: String) {
        val lp = pxg.inputStream().buffered().use { LegacyPxg.read(it) }
        val work = File(folder.parentFile, folder.name + ".migrating")
        work.deleteRecursively()
        work.mkdirs()

        val size = lp.width * lp.height
        val frames = lp.holds.mapIndexed { i, h -> Frame((i + 1).toLong(), h) }
        val store = DiskCelStore(File(work, CELS_DIR), size)
        for (layer in lp.layers) {
            layer.cels.forEachIndexed { fi, cel ->
                if (!CelStore.isEmpty(cel)) store.put(CelKey(layer.id, frames[fi].uid), cel)
            }
        }
        val project = PixelProject(
            id = id, name = lp.name, width = lp.width, height = lp.height,
            layers = lp.layers.map { Layer(it.id, it.name, it.visible) },
            frames = frames,
            activeLayerIndex = lp.activeLayer,
            currentFrameUid = frames[lp.currentFrame].uid,
            nextLayerId = maxOf(lp.nextLayerId, lp.layers.maxOf { it.id } + 1),
            nextFrameUid = frames.size + 1L,
            fps = lp.fps,
        )
        File(work, META_FILE).outputStream().buffered().use { MetaSerializer.write(project, lp.modified, it) }

        // Verify by reading everything back through fresh objects.
        val back = File(work, META_FILE).inputStream().buffered().use { MetaSerializer.read(id, it) }
        if (back.layers.size != lp.layers.size || back.frames.size != lp.holds.size) {
            throw IOException("Migration check failed: structure differs")
        }
        val check = DiskCelStore(File(work, CELS_DIR), size)
        for (layer in lp.layers) {
            layer.cels.forEachIndexed { fi, cel ->
                val got = check.read(layer.id, back.frames[fi].uid)
                val same = if (CelStore.isEmpty(cel)) got == null else got != null && got.contentEquals(cel)
                if (!same) throw IOException("Migration check failed: layer ${layer.id} frame $fi differs")
            }
        }

        folder.deleteRecursively()
        if (!work.renameTo(folder)) throw IOException("Could not move migrated project into place")
    }
}

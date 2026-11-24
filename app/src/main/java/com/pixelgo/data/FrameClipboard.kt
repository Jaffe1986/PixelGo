package com.pixelgo.data

import com.pixelgo.editor.CelStore
import com.pixelgo.editor.FrameClipSource
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** What's on the frame clipboard (for enabling Paste and showing a summary). */
data class FrameClipInfo(val frames: Int, val width: Int, val height: Int, val layers: Int)

/**
 * Frames copied from any project, kept on disk (in the app's cache folder) so large ranges
 * never sit in memory and you can paste into a different project. Pure JVM for tests.
 *
 * The manifest (`clip.meta`) is written last and removed first, so a half-written or
 * partly-cleared clipboard is simply treated as empty.
 */
class FrameClipboard(private val dir: File) {
    private val manifest = File(dir, "clip.meta")

    private fun celFile(layer: Int, frame: Int) = File(dir, "${layer}_$frame.cel")

    /** Replaces the clipboard with [src]. Call off the main thread. */
    fun write(src: FrameClipSource, progress: (Float) -> Unit = {}) {
        manifest.delete()
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val frames = src.holds.size
        for (f in 0 until frames) {
            for (l in src.layerNames.indices) {
                val px = src.cel(l, f)
                if (!CelStore.isEmpty(px)) DiskCelStore.writeCelFile(celFile(l, f), px!!)
            }
            progress((f + 1f) / frames)
        }
        val tmp = File(dir, "clip.meta.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(src.width)
            d.writeInt(src.height)
            d.writeInt(src.layerNames.size)
            src.layerNames.forEach { d.writeUTF(it) }
            d.writeInt(frames)
            src.holds.forEach { d.writeInt(it) }
        }
        if (!tmp.renameTo(manifest)) throw IOException("Could not finish the clipboard")
    }

    fun info(): FrameClipInfo? = readManifest()?.let { FrameClipInfo(it.holds.size, it.width, it.height, it.layerNames.size) }

    /** The clipboard contents, or null if empty or damaged. Cels load lazily from disk. */
    fun open(): FrameClipSource? {
        val m = readManifest() ?: return null
        return object : FrameClipSource {
            override val width = m.width
            override val height = m.height
            override val layerNames = m.layerNames
            override val holds = m.holds
            override fun cel(layer: Int, frame: Int): IntArray? =
                DiskCelStore.readCelFile(celFile(layer, frame), m.width * m.height)
        }
    }

    private class Manifest(val width: Int, val height: Int, val layerNames: List<String>, val holds: IntArray)

    private fun readManifest(): Manifest? = try {
        if (!manifest.exists()) null else DataInputStream(manifest.inputStream().buffered()).use { d ->
            if (d.readInt() != MAGIC) return null
            val w = d.readInt()
            val h = d.readInt()
            val names = List(d.readInt()) { d.readUTF() }
            val holds = IntArray(d.readInt()) { d.readInt() }
            if (w <= 0 || h <= 0 || names.isEmpty() || holds.isEmpty()) null else Manifest(w, h, names, holds)
        }
    } catch (e: IOException) {
        null
    }

    companion object {
        private const val MAGIC = 0x46434C50 // "FCLP"
    }
}

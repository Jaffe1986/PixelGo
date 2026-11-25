package com.pixelgo.data.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** One track's sound on the clipboard (48 kHz stereo PCM + peaks). */
class SoundClipTrack(val name: String, val pcm: File, val peaks: File, val durationMs: Long)

/** What's on the sound clipboard. */
class SoundClipInfo(val tracks: List<SoundClipTrack>) {
    val lengthMs: Long get() = tracks.maxOfOrNull { it.durationMs } ?: 0L
}

/**
 * Sound copied in any project, pasted in any project: one rendered PCM file per copied track
 * (its volumes and fades applied), in the app's cache. Like the frame clipboard, the manifest
 * is written last and removed first, so a half-written clipboard is never pasted. Pure JVM.
 */
class SoundClipboard(private val dir: File) {
    private val manifest = File(dir, "sound.meta")

    /** Replaces the clipboard; [render] writes track [index]'s sound into the writer. */
    fun write(names: List<String>, render: (index: Int, writer: PcmWriter) -> Unit) {
        manifest.delete()
        dir.deleteRecursively()
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not use the clipboard folder")
        val lengths = names.indices.map { i ->
            val w = PcmWriter(File(dir, "$i.pcm"), File(dir, "$i.peaks"), 2)
            try {
                render(i, w)
            } finally {
                w.close()
            }
            w.durationMs
        }
        val tmp = File(dir, "sound.meta.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(names.size)
            names.forEachIndexed { i, n ->
                d.writeUTF(n)
                d.writeLong(lengths[i])
            }
        }
        if (!tmp.renameTo(manifest)) throw IOException("Could not finish the clipboard")
    }

    fun info(): SoundClipInfo? = try {
        if (!manifest.exists()) null else DataInputStream(manifest.inputStream().buffered()).use { d ->
            if (d.readInt() != MAGIC) return null
            val tracks = List(d.readInt()) { i ->
                SoundClipTrack(d.readUTF(), File(dir, "$i.pcm"), File(dir, "$i.peaks"), d.readLong())
            }
            if (tracks.all { it.pcm.exists() }) SoundClipInfo(tracks) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAGIC = 0x53434C50 // "SCLP"
    }
}

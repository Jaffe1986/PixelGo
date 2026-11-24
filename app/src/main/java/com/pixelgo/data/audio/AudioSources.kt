package com.pixelgo.data.audio

import com.pixelgo.editor.PcmSource
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Opens a project's sound files on demand for one mixing session (preview or export) and
 * closes them all at the end. One instance per thread that mixes.
 */
class AudioSources(private val dir: File) : Closeable {
    private val open = HashMap<Long, PcmFile?>()

    @Synchronized
    fun get(assetId: Long): PcmSource? = open.getOrPut(assetId) {
        val f = File(dir, "$assetId.pcm")
        if (!f.exists()) null else try {
            PcmFile(f)
        } catch (e: IOException) {
            null
        }
    }

    @Synchronized
    override fun close() {
        open.values.forEach { it?.close() }
        open.clear()
    }
}

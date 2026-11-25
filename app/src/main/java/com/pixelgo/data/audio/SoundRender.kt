package com.pixelgo.data.audio

import com.pixelgo.data.video.ExportCancelled
import com.pixelgo.editor.AudioMixer
import com.pixelgo.editor.PcmSource
import com.pixelgo.editor.audiofx.Stage

/**
 * Renders sound into a new PCM file (with peaks), streaming, so any length works. Pure JVM.
 */
object SoundRender {
    private const val CHUNK = 4_800 // 100 ms

    /**
     * Frames [fromFrame, toFrame) of [src], read backwards if [reverse], through [stages] into
     * [writer] (same channel count as [src], 1 or 2). Returns the frames written.
     */
    fun process(
        src: PcmSource,
        fromFrame: Long,
        toFrame: Long,
        reverse: Boolean,
        stages: List<Stage>,
        writer: PcmWriter,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Long {
        val ch = writer.channels
        val stereo = FloatArray(CHUNK * 2)
        val buf = FloatArray(CHUNK * ch)
        fun feed(i: Int, data: FloatArray, n: Int) {
            if (n <= 0) return
            if (i == stages.size) writer.write(data, n * ch) else stages[i].process(data, n) { o, m -> feed(i + 1, o, m) }
        }
        val total = (toFrame - fromFrame).coerceAtLeast(0)
        var done = 0L
        while (done < total) {
            if (cancelled()) throw ExportCancelled()
            val n = minOf(CHUNK.toLong(), total - done).toInt()
            val start = if (reverse) toFrame - done - n else fromFrame + done
            src.readStereo(start, n, stereo)
            for (f in 0 until n) {
                val from = if (reverse) n - 1 - f else f // reversed: last frame first
                if (ch == 1) buf[f] = stereo[2 * from] else {
                    buf[2 * f] = stereo[2 * from]
                    buf[2 * f + 1] = stereo[2 * from + 1]
                }
            }
            feed(0, buf, n)
            done += n
            progress(done.toFloat() / total)
        }
        for (i in stages.indices) stages[i].flush { o, m -> feed(i + 1, o, m) }
        return writer.framesWritten
    }

    /** Mixes timeline frames [fromFrame, toFrame) of [mixer] (stereo) into [writer] (stereo). */
    fun mix(mixer: AudioMixer, fromFrame: Long, toFrame: Long, writer: PcmWriter, cancelled: () -> Boolean = { false }): Long {
        val shorts = ShortArray(CHUNK * 2)
        val floats = FloatArray(CHUNK * 2)
        var f = fromFrame
        while (f < toFrame) {
            if (cancelled()) throw ExportCancelled()
            val n = minOf(CHUNK.toLong(), toFrame - f).toInt()
            mixer.mix(f, n, shorts)
            for (i in 0 until n * 2) floats[i] = shorts[i] / 32768f
            writer.write(floats, n * 2)
            f += n
        }
        return writer.framesWritten
    }
}

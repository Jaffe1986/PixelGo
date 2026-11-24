package com.pixelgo.data.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.pixelgo.data.video.VideoLayout.Companion.padTo
import com.pixelgo.data.video.VideoLayout.Companion.roundUp16
import com.pixelgo.editor.AnimationSnapshot
import com.pixelgo.editor.AudioMixer
import com.pixelgo.model.AUDIO_RATE
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** What the user chose in the export dialog. */
class VideoSettings(
    val size: VideoSize,
    val shape: VideoShape,
    val background: Int,
    val mov: Boolean,
)

class ExportCancelled : RuntimeException("Cancelled")

/**
 * Encodes an animation (and its sound) to H.264 + AAC in an MP4 container with Android's
 * built-in MediaCodec / MediaMuxer (no extra libraries).
 *
 * - Constant frame rate: the project fps times a whole number (so at least 10 fps); a frame
 *   held N ticks is simply repeated, which compresses to almost nothing.
 * - Frames are rendered one at a time from the cel store, so length is unlimited.
 * - [totalMs] may be longer than one pass of the animation ("loop until the sound ends"):
 *   the animation then repeats.
 * - Colour: BT.709 limited range, signalled in the stream.
 * - Sound is shifted to cancel the AAC encoder's start-up delay ([AacPriming]), so it plays in
 *   sync. [priming] overrides this phone's measurement (tests).
 */
class VideoExporter(
    private val anim: AnimationSnapshot,
    private val settings: VideoSettings,
    private val audio: AudioMixer? = null,
    totalMs: Long = anim.timing.totalTicks * 1000L / anim.fps,
    private val priming: AacPriming? = null,
) {
    /** How many output frames per animation tick. */
    val repeat: Int = ceil(MIN_OUTPUT_FPS.toDouble() / anim.fps).toInt().coerceAtLeast(1)
    val outFps: Int = anim.fps * repeat

    val durationMs: Long = totalMs
    private val animTicks: Long = anim.timing.totalTicks
    private val videoFrames: Long = ceil(totalMs * outFps / 1000.0).toLong().coerceAtLeast(1)
    private val audioFrames: Long = totalMs * AUDIO_RATE / 1000L
    private val hasAudio: Boolean = audio != null && !audio.isSilent

    /** The layout the encoder will actually use (checked against its capabilities). */
    val layout: VideoLayout by lazy { chooseLayout() }

    val bitrate: Int by lazy {
        (layout.width.toLong() * layout.height * outFps / 8).coerceIn(1_500_000L, 40_000_000L).toInt() +
            if (hasAudio) AUDIO_BITRATE else 0
    }

    private fun encoderInfo(mime: String): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    private fun chooseLayout(): VideoLayout {
        val caps = encoderInfo(MIME)?.getCapabilitiesForType(MIME)?.videoCapabilities
        fun ok(l: VideoLayout) = caps == null || caps.areSizeAndRateSupported(l.width, l.height, outFps.toDouble())
        // Try the chosen size, then smaller ones.
        val sizes = VideoSize.entries.filter { it.ordinal <= settings.size.ordinal }.reversed()
        for (size in sizes) {
            val l = VideoLayout.compute(anim.width, anim.height, size, settings.shape)
            if (ok(l)) return l
            val padded = l.padTo(roundUp16(l.width), roundUp16(l.height))
            if (ok(padded)) return padded
        }
        throw IOException("This phone's video encoder doesn't support that size")
    }

    /** One encoder plus its place in the file. */
    private class Stream(val codec: MediaCodec) {
        var track = -1
        var formatReady = false
        var inputDone = false
        var outputDone = false
        val pending = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>() // before the muxer starts
    }

    /**
     * Writes the video to [fd] (opened read/write). [progress] gets 0..1;
     * [isCancelled] is polled and stops the export with [ExportCancelled].
     */
    fun run(fd: FileDescriptor, progress: (Float) -> Unit, isCancelled: () -> Boolean) {
        val l = layout
        // Cancel the AAC priming: with an edit list, shift the sound's timestamps back so players
        // skip it (nothing lost); otherwise start the mix that far in (the first ~43 ms is dropped).
        // Measured before the export's own codecs are created.
        val prime = if (hasAudio) priming ?: AacPriming.get() else AacPriming.NONE
        val audioShiftUs = if (prime.editList) prime.delayFrames * 1_000_000L / AUDIO_RATE else 0L
        val audioSkip = if (prime.editList || audioFrames <= 2L * prime.delayFrames) 0L else prime.delayFrames.toLong()
        val vInfo = encoderInfo(MIME) ?: throw IOException("No H.264 encoder on this phone")
        val vFormat = MediaFormat.createVideoFormat(MIME, l.width, l.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate - if (hasAudio) AUDIO_BITRATE else 0)
            setInteger(MediaFormat.KEY_FRAME_RATE, outFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val video = Stream(MediaCodec.createByCodecName(vInfo.name))
        val sound: Stream? = if (hasAudio) {
            Stream(MediaCodec.createEncoderByType(AUDIO_MIME)).also {
                it.codec.configure(aacFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } else null
        val streams = listOfNotNull(video, sound)
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            video.codec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            streams.forEach { it.codec.start() }
            val info = MediaCodec.BufferInfo()
            val timing = anim.timing
            var vn = 0L        // next video frame
            var an = 0L        // next audio frame (48 kHz)
            var shownArt = -1
            var yuv: YuvFrame? = null
            val pcm = ShortArray(16 * 1024 / 2)

            fun startMuxerIfReady() {
                if (muxerStarted || streams.any { !it.formatReady }) return
                muxer.start()
                muxerStarted = true
                for (s in streams) {
                    for ((bytes, bi) in s.pending) muxer.writeSampleData(s.track, ByteBuffer.wrap(bytes), bi)
                    s.pending.clear()
                }
            }

            fun drain(s: Stream, timeoutUs: Long) {
                while (!s.outputDone) {
                    val out = s.codec.dequeueOutputBuffer(info, timeoutUs)
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        s.track = muxer.addTrack(s.codec.outputFormat)
                        s.formatReady = true
                        startMuxerIfReady()
                        continue
                    }
                    if (out < 0) continue
                    val buf = s.codec.getOutputBuffer(out)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) {
                        // Only the file's timestamps move; the encoder always sees times from 0.
                        if (s === sound) info.presentationTimeUs -= audioShiftUs
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (muxerStarted) {
                            muxer.writeSampleData(s.track, buf, info)
                        } else {
                            val copy = ByteArray(info.size).also { buf.get(it) }
                            val bi = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                            s.pending += copy to bi
                        }
                    }
                    s.codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) s.outputDone = true
                }
            }

            while (streams.any { !it.outputDone }) {
                if (isCancelled()) throw ExportCancelled()
                // Feed whichever stream is behind in time, so the muxer interleaves nicely.
                val vTimeUs = vn * 1_000_000L / outFps
                val aTimeUs = an * 1_000_000L / AUDIO_RATE
                val feedAudio = sound != null && !sound.inputDone && (video.inputDone || aTimeUs <= vTimeUs)
                if (feedAudio) {
                    val idx = sound.codec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        if (an + audioSkip >= audioFrames) {
                            sound.codec.queueInputBuffer(idx, 0, 0, aTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sound.inputDone = true
                        } else {
                            val inBuf = sound.codec.getInputBuffer(idx)!!.order(ByteOrder.LITTLE_ENDIAN)
                            val frames = minOf((inBuf.capacity() / 4).toLong(), audioFrames - audioSkip - an, (pcm.size / 2).toLong()).toInt()
                            audio!!.mix(an + audioSkip, frames, pcm)
                            inBuf.clear()
                            inBuf.asShortBuffer().put(pcm, 0, frames * 2)
                            sound.codec.queueInputBuffer(idx, 0, frames * 4, aTimeUs, 0)
                            an += frames
                        }
                    }
                } else if (!video.inputDone) {
                    val idx = video.codec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        if (vn >= videoFrames) {
                            video.codec.queueInputBuffer(idx, 0, 0, vTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            video.inputDone = true
                        } else {
                            val tick = (vn / repeat) % animTicks
                            val fi = timing.frameAtTick(tick)
                            if (fi != shownArt || yuv == null) {
                                yuv = YuvFrame(anim.render(fi), anim.width, anim.height, settings.background, l)
                                shownArt = fi
                            }
                            val image = video.codec.getInputImage(idx)
                                ?: throw IOException("This phone's video encoder doesn't accept image input")
                            fill(image, yuv, l)
                            video.codec.queueInputBuffer(idx, 0, l.width * l.height * 3 / 2, vTimeUs, 0)
                            vn++
                            progress(vn.toFloat() / videoFrames)
                        }
                    }
                }
                val allIn = streams.all { it.inputDone }
                for (s in streams) drain(s, if (allIn) 10_000 else 0)
            }
        } finally {
            for (s in streams) {
                try { s.codec.stop() } catch (_: Exception) {}
                s.codec.release()
            }
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }

    /** Copies one frame into the encoder's input image, honouring its strides. */
    private fun fill(img: Image, f: YuvFrame, l: VideoLayout) {
        val planes = img.planes
        val yPlane = planes[0]
        val yBuf = yPlane.buffer
        val yStride = yPlane.rowStride
        for (y in 0 until l.height) {
            yBuf.position(y * yStride)
            yBuf.put(f.yRow(y), 0, l.width)
        }
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uStride = uPlane.rowStride
        val vStride = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        val cw = l.width / 2
        for (cy in 0 until l.height / 2) {
            val ur = f.uRow(cy)
            val vr = f.vRow(cy)
            if (uPix == 1) {
                uBuf.position(cy * uStride)
                uBuf.put(ur, 0, cw)
            } else {
                val base = cy * uStride
                for (cx in 0 until cw) uBuf.put(base + cx * uPix, ur[cx])
            }
            if (vPix == 1) {
                vBuf.position(cy * vStride)
                vBuf.put(vr, 0, cw)
            } else {
                val base = cy * vStride
                for (cx in 0 until cw) vBuf.put(base + cx * vPix, vr[cx])
            }
        }
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val AUDIO_BITRATE = 192_000
        const val MIN_OUTPUT_FPS = 10

        /** The AAC settings for export (also used to measure [AacPriming]). */
        fun aacFormat(): MediaFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
    }
}

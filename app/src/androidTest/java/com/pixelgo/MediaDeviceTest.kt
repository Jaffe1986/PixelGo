package com.pixelgo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.pixelgo.data.audio.AudioDecoder
import com.pixelgo.data.audio.PcmFile
import com.pixelgo.data.video.AacPriming
import com.pixelgo.data.video.MovPatcher
import com.pixelgo.data.video.VideoExporter
import com.pixelgo.data.video.VideoSettings
import com.pixelgo.data.video.VideoShape
import com.pixelgo.data.video.VideoSize
import com.pixelgo.editor.AnimationSnapshot
import com.pixelgo.editor.AudioMixer
import com.pixelgo.editor.MemoryCelStore
import com.pixelgo.editor.MemoryPcm
import com.pixelgo.model.AUDIO_RATE
import com.pixelgo.model.AudioClip
import com.pixelgo.model.SoundTrack
import com.pixelgo.ui.editor.PreviewPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device checks for the code that runs on Android's media codecs and audio output, which
 * the JVM tests can't reach: sound import (decoding + resampling), video export with sound,
 * and the preview playback clock. Nothing here touches the screen or the user's projects;
 * files go to the app's `cache/devtest` folder and are deleted afterwards.
 *
 * The test sounds (`assets/tone.*`) are the reference signal of [toneSample], encoded with
 * ffmpeg: `-c:a libmp3lame -b:a 128k` (44.1 kHz), `-c:a aac -b:a 128k` (48 kHz),
 * `-c:a libopus -b:a 96k` (48 kHz) and `-c:a flac` (44.1 kHz).
 *
 * Run (NEVER `connectedAndroidTest`: it uninstalls the app afterwards, deleting its projects):
 * ```
 * ./gradlew assembleDebug assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w com.pixelgo.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s PixelGoTest     # the measurements
 * adb uninstall com.pixelgo.test  # the test package only
 * ```
 * Add `-e keep true` to keep the exported videos in `cache/devtest` (pull them with
 * `adb exec-out run-as com.pixelgo cat cache/devtest/export.mp4 > export.mp4`).
 */
class MediaDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext
    private val dir = File(ctx.cacheDir, "devtest").also { it.mkdirs() }
    private val keep = InstrumentationRegistry.getArguments().getString("keep") == "true"

    @After
    fun tearDown() {
        if (!keep) dir.deleteRecursively()
    }

    // ---- Sound import ----------------------------------------------------------------------

    @Test
    fun wavAt44kHz() {
        val wav = File(dir, "tone44.wav").also { writeWav(it, 44100, tone(44100)) }
        checkSound("WAV 44.1 kHz", decode(wav), edgeToleranceMs = 0.2, lengthToleranceMs = 1)
    }

    @Test
    fun flacAt44kHz() = checkAsset("tone.flac", edgeToleranceMs = 0.2, lengthToleranceMs = 1)

    @Test
    fun mp3() = checkAsset("tone.mp3", edgeToleranceMs = 2.0, lengthToleranceMs = 30)

    @Test
    fun aacInM4a() = checkAsset("tone.m4a", edgeToleranceMs = 2.0, lengthToleranceMs = 30)

    @Test
    fun opusInOgg() = checkAsset("tone.ogg", edgeToleranceMs = 2.0, lengthToleranceMs = 30)

    // ---- Video export ------------------------------------------------------------------------

    /**
     * 10 frames at 10 fps (frame i = grey 20 + 20 i) with the reference tone, exported 1.75 s
     * long, so the animation repeats under the sound. Checks the tracks, which frame shows
     * when, that the sound is in sync, and that the MOV variant still opens.
     */
    @Test
    fun videoWithSound() {
        val anim = greyAnimation(fps = 10, holds = IntArray(10) { 1 })
        val exporter = VideoExporter(anim, settings(VideoSize.P480), toneMixer(), totalMs = 1750)
        val file = export(exporter, "export.mp4")
        log("AAC priming on this phone: ${AacPriming.get()}")

        val formats = trackFormats(file)
        val video = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val audio = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        log(
            "Export: %dx%d at %d fps, video %d ms, audio %d ms, AAC encoder %s".format(
                Locale.US, exporter.layout.width, exporter.layout.height, exporter.outFps,
                video.getLong(MediaFormat.KEY_DURATION) / 1000, audio.getLong(MediaFormat.KEY_DURATION) / 1000,
                encoderName(MediaFormat.MIMETYPE_AUDIO_AAC),
            )
        )
        assertEquals(exporter.layout.width, video.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(exporter.layout.height, video.getInteger(MediaFormat.KEY_HEIGHT))

        // Frame n is shown from n × 100 ms; after 1 s the animation starts again.
        checkFrames(file, listOf(0L to 0, 300L to 3, 900L to 9, 1000L to 0, 1500L to 5))

        // The sound must line up with the picture.
        checkSound("Exported MP4 sound", decode(file), edgeToleranceMs = 2.0, lengthToleranceMs = 30)

        // MOV = the same file with a QuickTime brand; it must still open.
        val mov = File(dir, "export.mov")
        file.copyTo(mov, overwrite = true)
        RandomAccessFile(mov, "rw").use { MovPatcher.patch(it.channel) }
        RandomAccessFile(mov, "r").use { assertEquals("qt  ", MovPatcher.majorBrand(it.channel)) }
        assertEquals(2, trackFormats(mov).size)
        checkFrames(mov, listOf(500L to 5))
    }

    /** Phones whose muxer can't write an edit list drop the priming from the mix instead. */
    @Test
    fun videoWithSoundWithoutEditList() {
        val measured = AacPriming.get()
        assertTrue("priming not measured", measured.delayFrames > 0)
        val anim = greyAnimation(fps = 10, holds = IntArray(10) { 1 })
        val exporter = VideoExporter(
            anim, settings(VideoSize.P480), toneMixer(), totalMs = 1750,
            priming = AacPriming(measured.delayFrames, editList = false),
        )
        val file = export(exporter, "no_edit_list.mp4")
        checkSound("Exported MP4 sound (no edit list)", decode(file), edgeToleranceMs = 2.0, lengthToleranceMs = 30)
    }

    /** Slow animation with holds: 4 fps is output at 12 fps (each tick shown 3 times). */
    @Test
    fun holdsAtLowFps() {
        val anim = greyAnimation(fps = 4, holds = intArrayOf(1, 2, 1))
        val exporter = VideoExporter(anim, settings(VideoSize.P480))
        assertEquals(12, exporter.outFps)
        val file = export(exporter, "holds.mp4")
        assertEquals(1, trackFormats(file).size) // no sound, no audio track
        // Frame 0: 0–250 ms, frame 1: 250–750 ms, frame 2: 750–1000 ms (output frames every 83.3 ms).
        checkFrames(file, listOf(166L to 0, 250L to 1, 666L to 1, 750L to 2, 916L to 2))
    }

    /** The largest size: whatever layout the encoder accepts must actually encode. */
    @Test
    fun largestSize() {
        val anim = greyAnimation(fps = 10, holds = IntArray(5) { 1 })
        val exporter = VideoExporter(anim, VideoSettings(VideoSize.P2160, VideoShape.WIDE, 0xFF000000.toInt(), mov = false))
        val start = System.nanoTime()
        val file = export(exporter, "large.mp4")
        log(
            "4K 16:9 export: chose %dx%d (scale %d), 5 frames in %d ms, %d KB".format(
                Locale.US, exporter.layout.width, exporter.layout.height, exporter.layout.scale,
                (System.nanoTime() - start) / 1_000_000, file.length() / 1024,
            )
        )
        checkFrames(file, listOf(200L to 2))
    }

    // ---- Preview clock -------------------------------------------------------------------------

    /** The audio-driven clock must run at real time, never jump back noticeably, and report timestamps. */
    @Test
    fun previewClock() {
        val player = PreviewPlayer(AudioMixer(emptyList(), emptyList()) { null }, 0, AUDIO_RATE * 10L, loop = false)
        val started = System.nanoTime()
        player.start()
        try {
            Thread.sleep(400)
            val f1 = player.currentTimelineFrame()
            val t1 = System.nanoTime()
            var last = f1
            var worstBack = 0L
            repeat(40) {
                Thread.sleep(25)
                val f = player.currentTimelineFrame()
                worstBack = maxOf(worstBack, last - f)
                last = f
            }
            val f2 = player.currentTimelineFrame()
            val t2 = System.nanoTime()
            val rate = (f2 - f1) * 1e9 / (t2 - t1)
            val first = player.firstFrameNanos()
            log(
                "Preview clock: %.0f frames/s, worst step back %.1f ms, first sound heard %s".format(
                    Locale.US, rate, worstBack * 1000.0 / AUDIO_RATE,
                    if (first != null) "%d ms after start()".format((first - started) / 1_000_000) else "unknown (no timestamps)",
                )
            )
            assertEquals(AUDIO_RATE.toDouble(), rate, AUDIO_RATE * 0.03)
            assertTrue("clock stepped back ${worstBack * 1000 / AUDIO_RATE} ms", worstBack * 1000 / AUDIO_RATE < 10)
            assertNotNull("no output timestamps (voice-over alignment falls back)", first)
        } finally {
            player.stop()
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    /** One sample of the reference signal: a 0.5–1.5 s burst (2 ms ramps), amplitude 0.5. */
    private fun toneSample(t: Double, freq: Double): Double {
        val a = 0.5
        val b = 1.5
        val ramp = 0.002
        val env = when {
            t < a || t >= b -> 0.0
            t < a + ramp -> 0.5 - 0.5 * cos(PI * (t - a) / ramp)
            t > b - ramp -> 0.5 - 0.5 * cos(PI * (b - t) / ramp)
            else -> 1.0
        }
        return 0.5 * env * sin(2 * PI * freq * (t - 0.5))
    }

    /** The reference signal, 1.75 s of interleaved stereo: left 1 kHz, right 500 Hz. */
    private fun tone(rate: Int): ShortArray {
        val n = (1.75 * rate).roundToInt()
        return ShortArray(n * 2) { i ->
            val t = (i / 2) / rate.toDouble()
            (toneSample(t, if (i % 2 == 0) 1000.0 else 500.0) * 32767).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun writeWav(file: File, rate: Int, data: ShortArray) {
        val b = ByteBuffer.allocate(44 + data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4)
            .putShort(4).putShort(16)
        b.put("data".toByteArray()).putInt(data.size * 2)
        data.forEach { b.putShort(it) }
        file.writeBytes(b.array())
    }

    private class Decoded(val left: FloatArray, val right: FloatArray, val channels: Int, val durationMs: Long)

    /** Imports [file] exactly like the app does, then reads the stored PCM back. */
    private fun decode(file: File): Decoded {
        val pcm = File(dir, file.name + ".pcm")
        val peaks = File(dir, file.name + ".peaks")
        val d = AudioDecoder.decode(ctx, Uri.fromFile(file), pcm, peaks, {}, { false })
        PcmFile(pcm).use { p ->
            val n = p.frames.toInt()
            assertEquals("peaks size", (n + 479) / 480 * 2L, peaks.length())
            val st = FloatArray(n * 2)
            p.readStereo(0, n, st)
            return Decoded(FloatArray(n) { st[2 * it] }, FloatArray(n) { st[2 * it + 1] }, d.channels, d.durationMs)
        }
    }

    private fun checkAsset(name: String, edgeToleranceMs: Double, lengthToleranceMs: Long) {
        val f = File(dir, name)
        instr.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        checkSound(name, decode(f), edgeToleranceMs, lengthToleranceMs)
    }

    /** 2 ms RMS envelope (window ending at each sample). */
    private fun envelope(x: FloatArray): DoubleArray {
        val w = 96
        val env = DoubleArray(x.size)
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i].toDouble() * x[i]
            if (i >= w) sum -= x[i - w].toDouble() * x[i - w]
            env[i] = sqrt(maxOf(0.0, sum) / w)
        }
        return env
    }

    /** Times (ms) where the envelope first rises through, and last falls through, half its level. */
    private fun edgesMs(x: FloatArray): Pair<Double, Double> {
        val env = envelope(x)
        val steady = (33_600 until 62_400).map { env[it] }.average() // 0.7–1.3 s
        val half = steady / 2
        val up = (1 until env.size).first { env[it] >= half && env[it - 1] < half }
        val down = (1 until env.size).last { env[it] < half && env[it - 1] >= half }
        fun at(i: Int) = (i - 1 + (half - env[i - 1]) / (env[i] - env[i - 1])) * 1000.0 / AUDIO_RATE
        return at(up) to at(down)
    }

    /** Frequency from zero crossings between 0.7 and 1.3 s. */
    private fun frequency(x: FloatArray): Double {
        var n = 0
        for (i in 33_601 until 62_400) if ((x[i - 1] < 0) != (x[i] < 0)) n++
        return n / 2.0 / 0.6
    }

    private val reference: Pair<Double, Double> by lazy {
        val t = tone(AUDIO_RATE)
        edgesMs(FloatArray(t.size / 2) { t[2 * it] / 32768f })
    }

    private fun checkSound(name: String, d: Decoded, edgeToleranceMs: Double, lengthToleranceMs: Long) {
        assertTrue("$name: only ${d.durationMs} ms decoded", d.left.size > 62_400)
        val (up, down) = edgesMs(d.left)
        val (refUp, refDown) = reference
        val fl = frequency(d.left)
        val fr = frequency(d.right)
        val msg = "%s: start %+.2f ms, end %+.2f ms, length %d ms, %d ch, left %.1f Hz, right %.1f Hz".format(
            Locale.US, name, up - refUp, down - refDown, d.durationMs, d.channels, fl, fr,
        )
        log(msg)
        assertEquals(msg, 2, d.channels)
        assertEquals(msg, 1000.0, fl, 10.0)
        assertEquals(msg, 500.0, fr, 5.0)
        assertEquals(msg, 0.0, up - refUp, edgeToleranceMs)
        assertEquals(msg, 0.0, down - refDown, edgeToleranceMs)
        assertEquals(msg, 1750.0, d.durationMs.toDouble(), lengthToleranceMs.toDouble())
    }

    /** A 16×16 animation where frame i is a flat grey of level 20 + 20 i. */
    private fun greyAnimation(fps: Int, holds: IntArray): AnimationSnapshot {
        val store = MemoryCelStore(16 * 16)
        val uids = LongArray(holds.size) { it + 1L }
        for (i in holds.indices) {
            val g = 20 + 20 * i
            store.edit(1L, uids[i]).fill((0xFF shl 24) or (g shl 16) or (g shl 8) or g)
        }
        return AnimationSnapshot(16, 16, fps, uids, holds, longArrayOf(1L), store)
    }

    /** One track playing the reference tone from 0 ms. */
    private fun toneMixer(): AudioMixer {
        val sound = MemoryPcm(tone(AUDIO_RATE), 2)
        return AudioMixer(
            listOf(SoundTrack(1, "Music")),
            listOf(AudioClip(id = 1, trackId = 1, assetId = 7, startMs = 0, inMs = 0, outMs = 1750)),
        ) { if (it == 7L) sound else null }
    }

    private fun settings(size: VideoSize) = VideoSettings(size, VideoShape.CANVAS, 0xFFFFFFFF.toInt(), mov = false)

    private fun export(exporter: VideoExporter, name: String): File {
        val file = File(dir, name)
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).use { exporter.run(it.fileDescriptor, {}, { false }) }
        assertTrue("$name is empty", file.length() > 0)
        return file
    }

    private fun trackFormats(file: File): List<MediaFormat> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            return (0 until ex.trackCount).map { ex.getTrackFormat(it) }
        } finally {
            ex.release()
        }
    }

    /** Checks that at each time (ms) the video shows the given grey frame. */
    private fun checkFrames(file: File, expected: List<Pair<Long, Int>>) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            for ((ms, frame) in expected) {
                val bmp = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull("${file.name}: no picture at $ms ms", bmp)
                val px = bmp!!.getPixel(bmp.width / 2, bmp.height / 2)
                val want = 20 + 20 * frame
                val got = intArrayOf((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
                assertTrue(
                    "${file.name} at $ms ms: expected grey $want (frame $frame), got ${got.toList()}",
                    got.all { kotlin.math.abs(it - want) <= 6 },
                )
                bmp.recycle()
            }
        } finally {
            r.release()
        }
    }

    /** The encoder the exporter gets for [mime] (it uses createEncoderByType too). */
    private fun encoderName(mime: String): String {
        val codec = MediaCodec.createEncoderByType(mime)
        try {
            return codec.name
        } finally {
            codec.release()
        }
    }

    companion object {
        private const val TAG = "PixelGoTest"

        @BeforeClass
        @JvmStatic
        fun clean() {
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "devtest").deleteRecursively()
        }
    }
}

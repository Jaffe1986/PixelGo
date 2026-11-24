package com.pixelgo.data

import com.pixelgo.model.AudioAsset
import com.pixelgo.model.AudioClip
import com.pixelgo.model.Frame
import com.pixelgo.model.Layer
import com.pixelgo.model.SoundTrack
import com.pixelgo.model.MAX_CANVAS_SIZE
import com.pixelgo.model.MAX_LAYERS
import com.pixelgo.model.PixelProject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Gallery info, readable without inflating the body. */
data class MetaHeader(
    val name: String,
    val width: Int,
    val height: Int,
    val modified: Long,
    val frameCount: Int,
    val fps: Int,
    val totalTicks: Long,
) {
    val durationMs: Long get() = totalTicks * 1000L / fps.coerceAtLeast(1)
}

/**
 * `project.meta` (format v3). Pixels are NOT in here; they are cel files next to it.
 *
 * Header (uncompressed, big-endian):
 *   int magic "PXG3", int version, UTF name, int width, int height, long modified,
 *   int frameCount, int fps, long totalTicks
 * Body (deflate): tagged sections `int tag, int length, bytes`, ended by tag 0. Readers skip
 * unknown tags, so new sections (e.g. audio) can be added without a format migration.
 *   1 STATE : int activeLayer, long currentFrameUid, long nextLayerId, long nextFrameUid, int fps
 *   2 LAYERS: int n, n × (long id, UTF name, boolean visible)
 *   3 FRAMES: int n, n × (long uid, int hold)
 *   4 AUDIO : boolean loopToAudio, long nextTrackId, long nextClipId, long nextAssetId,
 *             int n, n × track (long id, UTF name, boolean muted, float volume),
 *             int n, n × asset (long id, UTF name, long durationMs, int channels),
 *             int n, n × clip  (long id, long trackId, long assetId, long startMs, long inMs,
 *                               long outMs, float volume)
 */
object MetaSerializer {
    const val MAGIC = 0x50584733 // "PXG3"
    const val VERSION = 3
    private const val TAG_END = 0
    private const val TAG_STATE = 1
    private const val TAG_LAYERS = 2
    private const val TAG_FRAMES = 3
    private const val TAG_AUDIO = 4

    fun toBytes(p: PixelProject, modified: Long): ByteArray =
        ByteArrayOutputStream().also { write(p, modified, it) }.toByteArray()

    fun write(p: PixelProject, modified: Long, out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeUTF(p.name)
        d.writeInt(p.width)
        d.writeInt(p.height)
        d.writeLong(modified)
        d.writeInt(p.frames.size)
        d.writeInt(p.fps)
        d.writeLong(p.timing.totalTicks)
        d.flush()
        val z = DeflaterOutputStream(out)
        val body = DataOutputStream(z)
        section(body, TAG_STATE) {
            writeInt(p.activeLayerIndex)
            writeLong(p.currentFrameUid)
            writeLong(p.nextLayerId)
            writeLong(p.nextFrameUid)
            writeInt(p.fps)
        }
        section(body, TAG_LAYERS) {
            writeInt(p.layers.size)
            p.layers.forEach {
                writeLong(it.id)
                writeUTF(it.name)
                writeBoolean(it.visible)
            }
        }
        section(body, TAG_FRAMES) {
            writeInt(p.frames.size)
            p.frames.forEach {
                writeLong(it.uid)
                writeInt(it.hold)
            }
        }
        section(body, TAG_AUDIO) {
            writeBoolean(p.loopToAudio)
            writeLong(p.nextTrackId)
            writeLong(p.nextClipId)
            writeLong(p.nextAssetId)
            writeInt(p.tracks.size)
            p.tracks.forEach {
                writeLong(it.id)
                writeUTF(it.name)
                writeBoolean(it.muted)
                writeFloat(it.volume)
            }
            writeInt(p.assets.size)
            p.assets.forEach {
                writeLong(it.id)
                writeUTF(it.name.take(100))
                writeLong(it.durationMs)
                writeInt(it.channels)
            }
            writeInt(p.clips.size)
            p.clips.forEach {
                writeLong(it.id)
                writeLong(it.trackId)
                writeLong(it.assetId)
                writeLong(it.startMs)
                writeLong(it.inMs)
                writeLong(it.outMs)
                writeFloat(it.volume)
            }
        }
        body.writeInt(TAG_END)
        body.flush()
        z.finish()
        out.flush()
    }

    private inline fun section(out: DataOutputStream, tag: Int, block: DataOutputStream.() -> Unit) {
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).apply(block).flush()
        out.writeInt(tag)
        out.writeInt(buf.size())
        buf.writeTo(out)
    }

    fun readHeader(input: InputStream): MetaHeader {
        val d = DataInputStream(input)
        if (d.readInt() != MAGIC) throw IOException("Not a PixelGo project")
        val version = d.readInt()
        if (version != VERSION) throw IOException("Unsupported project version $version")
        val name = d.readUTF()
        val w = d.readInt()
        val h = d.readInt()
        if (w !in 1..MAX_CANVAS_SIZE || h !in 1..MAX_CANVAS_SIZE) throw IOException("Bad size")
        val modified = d.readLong()
        val frames = d.readInt()
        val fps = d.readInt()
        val ticks = d.readLong()
        if (frames < 1) throw IOException("Bad frame count")
        return MetaHeader(name, w, h, modified, frames, fps, ticks)
    }

    fun read(id: String, input: InputStream): PixelProject {
        val header = readHeader(input)
        val body = DataInputStream(InflaterInputStream(input))
        var active = 0
        var currentUid = -1L
        var nextLayerId = 0L
        var nextFrameUid = 0L
        var fps = header.fps
        var layers: List<Layer>? = null
        var frames: List<Frame>? = null
        var audio: ((PixelProject) -> Unit)? = null
        while (true) {
            val tag = body.readInt()
            if (tag == TAG_END) break
            val bytes = ByteArray(body.readInt())
            body.readFully(bytes)
            val s = DataInputStream(ByteArrayInputStream(bytes))
            when (tag) {
                TAG_STATE -> {
                    active = s.readInt()
                    currentUid = s.readLong()
                    nextLayerId = s.readLong()
                    nextFrameUid = s.readLong()
                    fps = s.readInt()
                }
                TAG_LAYERS -> layers = List(s.readInt()) { Layer(s.readLong(), s.readUTF(), s.readBoolean()) }
                TAG_FRAMES -> frames = List(s.readInt()) { Frame(s.readLong(), s.readInt()) }
                TAG_AUDIO -> {
                    val loop = s.readBoolean()
                    val nt = s.readLong()
                    val nc = s.readLong()
                    val na = s.readLong()
                    val tracks = List(s.readInt()) { SoundTrack(s.readLong(), s.readUTF(), s.readBoolean(), s.readFloat()) }
                    val assets = List(s.readInt()) { AudioAsset(s.readLong(), s.readUTF(), s.readLong(), s.readInt()) }
                    val clips = List(s.readInt()) {
                        AudioClip(s.readLong(), s.readLong(), s.readLong(), s.readLong(), s.readLong(), s.readLong(), s.readFloat())
                    }
                    audio = { p ->
                        p.loopToAudio = loop
                        p.tracks += tracks
                        p.assets += assets
                        p.clips += clips
                        p.nextTrackId = maxOf(nt, (tracks.maxOfOrNull { it.id } ?: 0L) + 1)
                        p.nextClipId = maxOf(nc, (clips.maxOfOrNull { it.id } ?: 0L) + 1)
                        p.nextAssetId = maxOf(na, (assets.maxOfOrNull { it.id } ?: 0L) + 1)
                    }
                }
                else -> Unit // a newer section this version doesn't know: skip
            }
        }
        val l = layers ?: throw IOException("No layers")
        val f = frames ?: throw IOException("No frames")
        if (l.isEmpty() || l.size > MAX_LAYERS || f.isEmpty()) throw IOException("Bad layers/frames")
        val p = PixelProject(
            id = id, name = header.name, width = header.width, height = header.height,
            layers = l, frames = f,
            activeLayerIndex = active.coerceIn(0, l.lastIndex),
            currentFrameUid = f.first().uid,
            nextLayerId = maxOf(nextLayerId, l.maxOf { it.id } + 1),
            nextFrameUid = maxOf(nextFrameUid, f.maxOf { it.uid } + 1),
            fps = fps,
        )
        p.currentFrameUid = currentUid // falls back to the first frame if unknown
        audio?.invoke(p)
        return p
    }
}

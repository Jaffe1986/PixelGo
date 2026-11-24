package com.pixelgo.data

import com.pixelgo.model.MAX_CANVAS_SIZE
import com.pixelgo.model.MAX_LAYERS
import com.pixelgo.model.Palette
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** A project in the old single-file `.pxg` format (everything in memory). */
class LegacyLayer(val id: Long, val name: String, val visible: Boolean, val cels: List<IntArray>)

class LegacyProject(
    val name: String,
    val width: Int,
    val height: Int,
    val modified: Long,
    val fps: Int,
    val holds: IntArray,
    val currentFrame: Int,
    val activeLayer: Int,
    val nextLayerId: Long,
    val layers: List<LegacyLayer>,
)

/**
 * Old `.pxg` format (v1 = still image, v2 = animation). Only read when migrating to the
 * folder format (v3, see [MetaSerializer]); [write] exists to build test fixtures.
 *
 * Header: int magic "PXGO", int version, UTF name, int width, int height, long modified,
 *         [v2] int frameCount
 * Body (deflate):
 *   v1: int activeLayer, long nextLayerId, int layerCount,
 *       layerCount × [long id, UTF name, boolean visible, int[w*h]]
 *   v2: int activeLayer, long nextLayerId, int fps, int currentFrame, frameCount × int hold,
 *       int layerCount, layerCount × [long id, UTF name, boolean visible, frameCount × int[w*h]]
 */
object LegacyPxg {
    const val MAGIC = 0x5058474F // "PXGO"
    const val LEGACY_MAX_FRAMES = 128

    fun read(input: InputStream): LegacyProject {
        val d = DataInputStream(input)
        if (d.readInt() != MAGIC) throw IOException("Not a PixelGo project")
        val version = d.readInt()
        if (version !in 1..2) throw IOException("Unsupported project version $version")
        val name = d.readUTF()
        val w = d.readInt()
        val h = d.readInt()
        if (w !in 1..MAX_CANVAS_SIZE || h !in 1..MAX_CANVAS_SIZE) throw IOException("Bad size")
        val modified = d.readLong()
        val frameCount = if (version >= 2) d.readInt() else 1
        if (frameCount !in 1..LEGACY_MAX_FRAMES) throw IOException("Bad frame count")

        val body = DataInputStream(InflaterInputStream(input))
        val active = body.readInt()
        val nextId = body.readLong()
        var fps = 8
        var current = 0
        val holds = if (version >= 2) {
            fps = body.readInt().coerceIn(1, 60)
            current = body.readInt()
            IntArray(frameCount) { body.readInt().coerceAtLeast(1) }
        } else {
            intArrayOf(1)
        }
        val count = body.readInt()
        if (count !in 1..MAX_LAYERS) throw IOException("Bad layer count")
        val size = w * h
        val bytes = ByteArray(size * 4)
        val layers = List(count) {
            val lid = body.readLong()
            val lname = body.readUTF()
            val visible = body.readBoolean()
            val cels = List(frameCount) {
                body.readFully(bytes)
                IntArray(size).also { px -> ByteBuffer.wrap(bytes).asIntBuffer().get(px) }
            }
            LegacyLayer(lid, lname, visible, cels)
        }
        return LegacyProject(
            name, w, h, modified, fps, holds,
            current.coerceIn(0, frameCount - 1), active.coerceIn(0, count - 1), nextId, layers,
        )
    }

    /** Writes the v2 format (for tests). */
    fun write(p: LegacyProject, out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(2)
        d.writeUTF(p.name)
        d.writeInt(p.width)
        d.writeInt(p.height)
        d.writeLong(p.modified)
        d.writeInt(p.holds.size)
        d.flush()
        val z = DeflaterOutputStream(out)
        val body = DataOutputStream(z)
        body.writeInt(p.activeLayer)
        body.writeLong(p.nextLayerId)
        body.writeInt(p.fps)
        body.writeInt(p.currentFrame)
        p.holds.forEach { body.writeInt(it) }
        body.writeInt(p.layers.size)
        val buf = ByteBuffer.allocate(p.width * p.height * 4)
        for (layer in p.layers) {
            body.writeLong(layer.id)
            body.writeUTF(layer.name)
            body.writeBoolean(layer.visible)
            for (cel in layer.cels) {
                buf.clear()
                buf.asIntBuffer().put(cel)
                body.write(buf.array())
            }
        }
        body.flush()
        z.finish()
        out.flush()
    }
}

object PaletteSerializer {
    private const val MAGIC = 0x50414C31 // "PAL1"

    fun write(palettes: List<Palette>, out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(palettes.size)
        for (p in palettes) {
            d.writeUTF(p.id)
            d.writeUTF(p.name)
            d.writeInt(p.colors.size)
            p.colors.forEach { d.writeInt(it) }
        }
        d.flush()
    }

    fun read(input: InputStream): List<Palette> {
        val d = DataInputStream(input)
        if (d.readInt() != MAGIC) throw IOException("Not a palette file")
        return List(d.readInt()) {
            val id = d.readUTF()
            val name = d.readUTF()
            val colors = List(d.readInt()) { d.readInt() }
            Palette(id, name, colors)
        }
    }
}

package com.pixelgo.data

import com.pixelgo.model.Frame
import com.pixelgo.model.FrameCursor
import com.pixelgo.model.Layer
import com.pixelgo.model.MAX_CANVAS_SIZE
import com.pixelgo.model.MAX_FRAMES
import com.pixelgo.model.MAX_LAYERS
import com.pixelgo.model.Palette
import com.pixelgo.model.PixelProject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Info shown in the gallery, readable without decompressing the pixel data. */
data class ProjectHeader(
    val name: String,
    val width: Int,
    val height: Int,
    val modified: Long,
    val frameCount: Int,
    val version: Int,
)

/**
 * .pxg file format.
 *
 * Header (uncompressed):
 *   int magic, int version, UTF name, int width, int height, long modified
 *   [v2+] int frameCount
 *
 * Body (deflate):
 *   v1: int activeLayer, long nextLayerId, int layerCount,
 *       layerCount x [ long id, UTF name, boolean visible, int[w*h] pixels ]
 *   v2: int activeLayer, long nextLayerId, int fps, int currentFrame,
 *       frameCount x [ int hold ],
 *       int layerCount, layerCount x [ long id, UTF name, boolean visible, frameCount x int[w*h] ]
 */
object ProjectSerializer {
    private const val MAGIC = 0x5058474F // "PXGO"
    private const val VERSION = 2

    fun write(project: PixelProject, modified: Long, out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeUTF(project.name)
        d.writeInt(project.width)
        d.writeInt(project.height)
        d.writeLong(modified)
        d.writeInt(project.frames.size)
        d.flush()
        val z = DeflaterOutputStream(out)
        val body = DataOutputStream(z)
        body.writeInt(project.activeLayerIndex)
        body.writeLong(project.nextLayerId)
        body.writeInt(project.fps)
        body.writeInt(project.currentFrame)
        project.frames.forEach { body.writeInt(it.hold) }
        body.writeInt(project.layers.size)
        val buf = ByteBuffer.allocate(project.size * 4)
        for (layer in project.layers) {
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

    fun readHeader(input: InputStream): ProjectHeader {
        val d = DataInputStream(input)
        if (d.readInt() != MAGIC) throw IOException("Not a PixelGo project")
        val version = d.readInt()
        if (version !in 1..VERSION) throw IOException("Unsupported project version $version")
        val name = d.readUTF()
        val w = d.readInt()
        val h = d.readInt()
        if (w !in 1..MAX_CANVAS_SIZE || h !in 1..MAX_CANVAS_SIZE) throw IOException("Bad size")
        val modified = d.readLong()
        val frames = if (version >= 2) d.readInt() else 1
        if (frames !in 1..MAX_FRAMES) throw IOException("Bad frame count")
        return ProjectHeader(name, w, h, modified, frames, version)
    }

    fun read(id: String, input: InputStream): PixelProject {
        val header = readHeader(input)
        val body = DataInputStream(InflaterInputStream(input))
        val active = body.readInt()
        val nextId = body.readLong()
        var fps = 8
        var current = 0
        val frames = if (header.version >= 2) {
            fps = body.readInt().coerceIn(1, 60)
            current = body.readInt()
            MutableList(header.frameCount) { Frame(body.readInt().coerceIn(1, 99)) }
        } else {
            mutableListOf(Frame())
        }
        val count = body.readInt()
        if (count !in 1..MAX_LAYERS) throw IOException("Bad layer count")
        val size = header.width * header.height
        val bytes = ByteArray(size * 4)
        val cursor = FrameCursor()
        val layers = MutableList(count) {
            val lid = body.readLong()
            val lname = body.readUTF()
            val visible = body.readBoolean()
            val cels = MutableList(frames.size) {
                body.readFully(bytes)
                IntArray(size).also { px -> ByteBuffer.wrap(bytes).asIntBuffer().get(px) }
            }
            Layer(lid, lname, visible, cels, cursor)
        }
        val p = PixelProject(
            id, header.name, header.width, header.height, cursor, layers, frames,
            active.coerceIn(0, count - 1), nextId, fps,
        )
        p.currentFrame = current
        return p
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

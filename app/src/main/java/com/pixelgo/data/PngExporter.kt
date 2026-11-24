package com.pixelgo.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.pixelgo.editor.Raster
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One image to export: a file name (without extension) and its pixels. */
class ExportImage(val name: String, val pixels: IntArray)

/** A file ready to be written: name with extension, MIME type, and how to write its bytes. */
class ExportFile(val fileName: String, val mime: String, val write: (OutputStream) -> Unit)

/** Sprite sheet grid for [frameCount] frames: (columns, rows). One row unless that gets too wide. */
fun sheetGrid(frameCount: Int, frameWidthPx: Int, maxWidthPx: Int = 4096): Pair<Int, Int> {
    val cols = if (frameCount * frameWidthPx <= maxWidthPx) frameCount
    else kotlin.math.ceil(kotlin.math.sqrt(frameCount.toDouble())).toInt()
    val rows = (frameCount + cols - 1) / cols
    return cols to rows
}

/** Writes transparent PNGs / GIFs, scaled up with nearest-neighbour so pixels stay crisp. */
object PngExporter {

    private fun safe(name: String, fallback: String) =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { fallback }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun fileName(projectName: String, scale: Int): String =
        "${safe(projectName, "pixelgo")}_${scale}x_${stamp()}"

    /**
     * Numbered file names, in list order (01 = first), so they sort correctly.
     * Used for layers (bottom-to-top) and frames.
     */
    fun layerFileNames(names: List<String>, scale: Int): List<String> {
        val digits = maxOf(2, names.size.toString().length)
        return names.mapIndexed { i, n ->
            "${(i + 1).toString().padStart(digits, '0')}_${safe(n, "layer")}_${scale}x"
        }
    }

    // ---- File builders -----------------------------------------------------------------

    fun pngFile(img: ExportImage, w: Int, h: Int, scale: Int) =
        ExportFile(img.name + ".png", "image/png") { writePng(img.pixels, w, h, scale, it) }

    /** PNG whose pixels are produced only when the file is written (off the main thread). */
    fun pngFile(name: String, w: Int, h: Int, scale: Int, pixels: () -> IntArray) =
        ExportFile("$name.png", "image/png") { writePng(pixels(), w, h, scale, it) }

    /** Sprite sheet; [frame] renders frame i (called off the main thread while writing). */
    fun sheetFile(name: String, frameCount: Int, frame: (Int) -> IntArray, w: Int, h: Int, scale: Int) =
        ExportFile("$name.png", "image/png") { writeSheet(frameCount, frame, w, h, scale, it) }

    /** Animated GIF; [frame] renders frame i (called off the main thread while writing). */
    fun gifFile(
        name: String,
        frameCount: Int,
        frame: (Int) -> IntArray,
        delaysCs: IntArray,
        w: Int,
        h: Int,
        scale: Int,
        progress: (Float) -> Unit = {},
    ) = ExportFile("$name.gif", "image/gif") {
        GifEncoder.encode(frameCount, frame, w, h, scale, delaysCs, it, progress)
    }

    private fun writePng(pixels: IntArray, w: Int, h: Int, scale: Int, out: OutputStream) {
        val ow = w * scale
        val bmp = Bitmap.createBitmap(ow, h * scale, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        val row = IntArray(ow)
        for (y in 0 until h) {
            Raster.scaledRow(pixels, w, y, scale, row)
            for (r in 0 until scale) bmp.setPixels(row, 0, ow, 0, y * scale + r, ow, 1)
        }
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }

    private fun writeSheet(frameCount: Int, frame: (Int) -> IntArray, w: Int, h: Int, scale: Int, out: OutputStream) {
        val fw = w * scale
        val fh = h * scale
        val (cols, rows) = sheetGrid(frameCount, fw)
        val bmp = Bitmap.createBitmap(cols * fw, rows * fh, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        val row = IntArray(fw)
        for (f in 0 until frameCount) {
            val px = frame(f)
            val ox = (f % cols) * fw
            val oy = (f / cols) * fh
            for (y in 0 until h) {
                Raster.scaledRow(px, w, y, scale, row)
                for (r in 0 until scale) bmp.setPixels(row, 0, fw, ox, oy + y * scale + r, fw, 1)
            }
        }
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }

    // ---- Destinations ------------------------------------------------------------------

    /** Android 8/9 need WRITE_EXTERNAL_STORAGE to save into Pictures. */
    val needsLegacyPermission get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * Saves [files] into Pictures/PixelGo. One file goes straight in; several go into their
     * own sub-folder named after the project + [kind]. Returns a user-facing location.
     */
    fun saveToPictures(context: Context, projectName: String, kind: String, files: List<ExportFile>): String {
        val folder = if (files.size == 1) "PixelGo"
        else "PixelGo/${safe(projectName, "pixelgo")}_${kind}_${stamp()}"
        files.forEach { saveFile(context, folder, it) }
        return if (files.size == 1) "Pictures/$folder/${files[0].fileName}" else "Pictures/$folder"
    }

    private fun saveFile(context: Context, folder: String, file: ExportFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, file.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$folder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create image")
            try {
                resolver.openOutputStream(uri)!!.use { s -> s.buffered().let { b -> file.write(b); b.flush() } }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), folder)
            dir.mkdirs()
            val f = File(dir, file.fileName)
            f.outputStream().buffered().use { file.write(it) }
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(file.mime), null)
        }
    }

    /** Writes the files to the app cache and returns a share intent for them. */
    fun shareIntent(context: Context, files: List<ExportFile>): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val uris = ArrayList<Uri>()
        for (file in files) {
            val f = File(dir, file.fileName)
            f.outputStream().buffered().use { file.write(it) }
            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }
        val mime = files.map { it.mime }.distinct().singleOrNull() ?: "image/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.apply {
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share")
    }
}

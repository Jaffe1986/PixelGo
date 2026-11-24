package com.pixelgo.data.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A video file being written to Movies/PixelGo. On Android 10+ it is a MediaStore entry that
 * stays hidden ("pending") until [finish]; on Android 8/9 it is a plain file.
 */
class VideoTarget(
    val uri: Uri?,
    val file: File?,
    val pfd: ParcelFileDescriptor,
    val displayPath: String,
    val mime: String,
)

object VideoOutput {
    fun create(context: Context, projectName: String, mov: Boolean): VideoTarget {
        val safe = projectName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "pixelgo" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (mov) "mov" else "mp4"
        val mime = if (mov) "video/quicktime" else "video/mp4"
        val name = "${safe}_$stamp.$ext"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/PixelGo")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the video file")
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Could not open the video file")
            VideoTarget(uri, null, pfd, "Movies/PixelGo/$name", mime)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PixelGo")
            dir.mkdirs()
            val f = File(dir, name)
            val pfd = ParcelFileDescriptor.open(
                f,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
            VideoTarget(null, f, pfd, "Movies/PixelGo/$name", mime)
        }
    }

    /** Rewrites the file's brand so it is a QuickTime .mov. */
    fun patchToMov(target: VideoTarget) {
        // Positional reads/writes on the same descriptor; these streams don't own (close) it.
        val read = FileInputStream(target.pfd.fileDescriptor).channel
        val write = FileOutputStream(target.pfd.fileDescriptor).channel
        val head = ByteBuffer.allocate(8)
        read.read(head, 0)
        val size = MovPatcher.ftypSize(head.array())
        val box = ByteBuffer.allocate(size)
        read.read(box, 0)
        write.write(ByteBuffer.wrap(MovPatcher.patchFtyp(box.array())), 0)
        write.force(false)
    }

    /** Makes the finished video visible in the gallery. */
    fun finish(context: Context, target: VideoTarget) {
        target.pfd.close()
        if (target.uri != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(target.uri, values, null, null)
        } else if (target.file != null) {
            MediaScannerConnection.scanFile(context, arrayOf(target.file.absolutePath), arrayOf(target.mime), null)
        }
    }

    /** Removes a cancelled / failed video. */
    fun discard(context: Context, target: VideoTarget) {
        try { target.pfd.close() } catch (_: Exception) {}
        if (target.uri != null) context.contentResolver.delete(target.uri, null, null)
        target.file?.delete()
    }
}

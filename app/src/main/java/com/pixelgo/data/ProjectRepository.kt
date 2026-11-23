package com.pixelgo.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.pixelgo.editor.ColorMath
import com.pixelgo.model.PixelProject
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class ProjectSummary(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val modified: Long,
    val frameCount: Int,
    val thumbnail: File,
)

/**
 * Stores projects as files in app-private storage (nothing leaves the phone).
 * All disk writes go through a single background thread, so reads queued on [dispatcher]
 * always see the latest save.
 */
class ProjectRepository(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()

    private fun projectFile(id: String) = File(dir, "$id.pxg")
    private fun thumbFile(id: String) = File(dir, "$id.png")

    /** Call on [dispatcher]. */
    fun list(): List<ProjectSummary> =
        dir.listFiles { f -> f.extension == "pxg" }.orEmpty().mapNotNull { f ->
            try {
                val h = f.inputStream().buffered().use { ProjectSerializer.readHeader(it) }
                val id = f.nameWithoutExtension
                ProjectSummary(id, h.name, h.width, h.height, h.modified, h.frameCount, thumbFile(id))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unreadable project ${f.name}", e)
                null
            }
        }.sortedByDescending { it.modified }

    /** Loads synchronously; projects are small (at most a few MB). */
    fun load(id: String): PixelProject? = try {
        projectFile(id).inputStream().buffered().use { ProjectSerializer.read(id, it) }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load $id", e)
        null
    }

    fun create(name: String, width: Int, height: Int): PixelProject {
        val p = PixelProject.create(UUID.randomUUID().toString(), name, width, height)
        writeNow(p)
        return p
    }

    /** Saves a private copy of [project] in the background. */
    fun saveAsync(project: PixelProject) {
        val copy = project.deepCopy()
        executor.execute { writeNow(copy) }
    }

    /** Call on [dispatcher]. */
    fun rename(id: String, newName: String) {
        val p = load(id) ?: return
        p.name = newName
        writeNow(p)
    }

    /** Call on [dispatcher]. */
    fun duplicate(id: String) {
        val p = load(id) ?: return
        writeNow(p.deepCopy(UUID.randomUUID().toString(), "${p.name} copy"))
    }

    /** Call on [dispatcher]. */
    fun delete(id: String) {
        projectFile(id).delete()
        thumbFile(id).delete()
    }

    private fun writeNow(p: PixelProject) {
        try {
            val target = projectFile(p.id)
            val tmp = File(dir, "${p.id}.tmp")
            tmp.outputStream().buffered().use { ProjectSerializer.write(p, System.currentTimeMillis(), it) }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            val pixels = ColorMath.compositeFrame(p, 0) // gallery shows the first frame
            val bmp = Bitmap.createBitmap(pixels, p.width, p.height, Bitmap.Config.ARGB_8888)
            thumbFile(p.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ${p.id}", e)
        }
    }

    companion object {
        private const val TAG = "ProjectRepository"
    }
}

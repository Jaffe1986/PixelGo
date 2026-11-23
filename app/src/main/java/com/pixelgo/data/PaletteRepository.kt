package com.pixelgo.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pixelgo.model.Palette
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Custom color palettes, saved to a small file in app-private storage. */
class PaletteRepository(context: Context) {
    private val file = File(context.filesDir, "palettes.bin")
    private val prefs = context.getSharedPreferences("pixelgo", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()

    var palettes by mutableStateOf(load())
        private set

    var activeId by mutableStateOf(prefs.getString(KEY_ACTIVE, null) ?: palettes.first().id)
        private set

    /** Recently used colors (most recent first), kept for this session only. */
    var recentColors by mutableStateOf(listOf<Int>())
        private set

    val active: Palette get() = palettes.firstOrNull { it.id == activeId } ?: palettes.first()

    fun setActive(id: String) {
        activeId = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun addRecent(color: Int) {
        recentColors = (listOf(color) + recentColors.filter { it != color }).take(12)
    }

    fun create(name: String): Palette {
        val p = Palette(UUID.randomUUID().toString(), name, emptyList())
        update(palettes + p)
        setActive(p.id)
        return p
    }

    fun duplicate(id: String) {
        val src = palettes.firstOrNull { it.id == id } ?: return
        update(palettes + src.copy(id = UUID.randomUUID().toString(), name = "${src.name} copy"))
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name) }

    fun delete(id: String) {
        val remaining = palettes.filter { it.id != id }
        update(remaining.ifEmpty { listOf(defaultPalette()) })
        if (activeId == id) setActive(palettes.first().id)
    }

    fun addColor(id: String, color: Int) = edit(id) { it.copy(colors = it.colors + color) }

    fun setColor(id: String, index: Int, color: Int) = edit(id) {
        it.copy(colors = it.colors.toMutableList().also { c -> c[index] = color })
    }

    fun removeColor(id: String, index: Int) = edit(id) {
        it.copy(colors = it.colors.toMutableList().also { c -> c.removeAt(index) })
    }

    fun moveColor(id: String, from: Int, to: Int) = edit(id) {
        if (to !in it.colors.indices) return@edit it
        it.copy(colors = it.colors.toMutableList().also { c -> c.add(to, c.removeAt(from)) })
    }

    private inline fun edit(id: String, change: (Palette) -> Palette) {
        update(palettes.map { if (it.id == id) change(it) else it })
    }

    private fun update(newList: List<Palette>) {
        palettes = newList
        val snapshot = newList
        executor.execute {
            try {
                val tmp = File(file.parentFile, "palettes.tmp")
                tmp.outputStream().buffered().use { PaletteSerializer.write(snapshot, it) }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                Log.e("PaletteRepository", "Failed to save palettes", e)
            }
        }
    }

    private fun load(): List<Palette> {
        val loaded = try {
            if (file.exists()) file.inputStream().buffered().use { PaletteSerializer.read(it) } else null
        } catch (e: Exception) {
            Log.e("PaletteRepository", "Failed to read palettes", e)
            null
        }
        return loaded?.ifEmpty { null } ?: listOf(defaultPalette())
    }

    companion object {
        private const val KEY_ACTIVE = "active_palette"

        fun defaultPalette() = Palette(
            "default", "Default",
            listOf(
                0xFF000000, 0xFF3A3A3A, 0xFF7A7A7A, 0xFFBDBDBD, 0xFFFFFFFF,
                0xFF7B1E1E, 0xFFC0392B, 0xFFE74C3C, 0xFFF39C12, 0xFFF7DC6F,
                0xFF1E8449, 0xFF58D68D, 0xFF17A589, 0xFF1F4E99, 0xFF3498DB,
                0xFF85C1E9, 0xFF6C3483, 0xFFBB8FCE, 0xFFF1948A, 0xFFFAD7B5,
                0xFFE0AC69, 0xFFA0522D, 0xFF6E3B1E, 0xFF2B2D42,
            ).map { it.toInt() },
        )
    }
}

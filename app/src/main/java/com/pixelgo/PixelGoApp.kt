package com.pixelgo

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pixelgo.data.AppSettings
import com.pixelgo.data.FrameClipInfo
import com.pixelgo.data.FrameClipboard
import com.pixelgo.data.PaletteRepository
import com.pixelgo.data.ProjectRepository
import java.io.File

class PixelGoApp : Application() {
    lateinit var projects: ProjectRepository
        private set
    lateinit var palettes: PaletteRepository
        private set
    lateinit var settings: AppSettings
        private set

    /** Frames copied in any project; paste works in every project. */
    lateinit var frameClipboard: FrameClipboard
        private set

    /** What's on [frameClipboard] (null = nothing), observed by the UI. */
    var frameClipInfo by mutableStateOf<FrameClipInfo?>(null)

    override fun onCreate() {
        super.onCreate()
        projects = ProjectRepository(this)
        palettes = PaletteRepository(this)
        settings = AppSettings(this)
        frameClipboard = FrameClipboard(File(cacheDir, "frame_clipboard"))
        frameClipInfo = frameClipboard.info()
    }
}

package com.pixelgo

import android.app.Application
import com.pixelgo.data.PaletteRepository
import com.pixelgo.data.ProjectRepository

class PixelGoApp : Application() {
    lateinit var projects: ProjectRepository
        private set
    lateinit var palettes: PaletteRepository
        private set

    override fun onCreate() {
        super.onCreate()
        projects = ProjectRepository(this)
        palettes = PaletteRepository(this)
    }
}

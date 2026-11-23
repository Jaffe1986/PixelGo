package com.pixelgo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pixelgo.ui.editor.EditorScreen
import com.pixelgo.ui.gallery.GalleryScreen
import com.pixelgo.ui.palettes.PaletteScreen
import com.pixelgo.ui.theme.PixelGoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            PixelGoTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "gallery") {
                    composable("gallery") {
                        GalleryScreen(
                            onOpen = { id -> nav.navigate("editor/$id") },
                            onPalettes = { nav.navigate("palettes") },
                        )
                    }
                    composable("editor/{id}") { entry ->
                        EditorScreen(
                            projectId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onPalettes = { nav.navigate("palettes") },
                        )
                    }
                    composable("palettes") {
                        PaletteScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}

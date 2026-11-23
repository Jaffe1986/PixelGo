package com.pixelgo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFFFFD166),
    onPrimary = Color(0xFF2B2D42),
    primaryContainer = Color(0xFFFFD166),
    onPrimaryContainer = Color(0xFF2B2D42),
    secondary = Color(0xFF06D6A0),
    onSecondary = Color(0xFF10251E),
    tertiary = Color(0xFFEF476F),
    background = Color(0xFF1E1E24),
    surface = Color(0xFF26262E),
    surfaceContainer = Color(0xFF2C2C35),
    surfaceContainerHigh = Color(0xFF33333D),
    surfaceContainerLow = Color(0xFF23232A),
    onBackground = Color(0xFFEDEDF2),
    onSurface = Color(0xFFEDEDF2),
)

@Composable
fun PixelGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

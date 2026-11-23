package com.pixelgo.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pixelgo.editor.Clip

/** App-wide clipboard for copied cells, so you can paste into a different project. */
object PixelClipboard {
    var clip by mutableStateOf<Clip?>(null)
}

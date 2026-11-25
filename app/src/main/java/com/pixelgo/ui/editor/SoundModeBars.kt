package com.pixelgo.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Sound mode, first row: leave, the frame on screen, play, time, record / import / settings. */
@Composable
fun SoundModeHeader(vm: EditorViewModel, onRecord: () -> Unit, onImport: () -> Unit, onSettings: () -> Unit, canPaste: Boolean) {
    vm.renderTick
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallIconButton(Icons.Filled.CloseFullscreen, "Leave sound mode", onClick = { vm.enterSoundMode(false) })
        // The frame on screen, to line sound up with the picture.
        val img = (if (vm.isPlaying) vm.playImage else null) ?: vm.image
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFBDBDBD))) {
            if (img != null) {
                Image(
                    img, "Frame on screen", Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
                )
            }
        }
        PlayButton(vm)
        val ms = if (vm.isPlaying) vm.playTick * 1000 / vm.fps else vm.soundCursorMs()
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(formatMsFine(ms), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text("frame ${(if (vm.isPlaying) vm.playIndex else vm.frameIndex) + 1} / ${vm.frameCount}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (canPaste) SmallIconButton(Icons.Filled.ContentPaste, "Paste sound at the cursor", onClick = vm::pasteSound)
        SmallIconButton(Icons.Filled.Mic, "Record sound", onClick = onRecord)
        SmallIconButton(Icons.Filled.MusicNote, "Import sound", onClick = onImport)
        SmallIconButton(Icons.Filled.Tune, "Animation settings", onClick = onSettings)
    }
}

/** Sound mode, second row: what dragging does, zoom, waveform size, track height, split. */
@Composable
fun SoundToolsRow(vm: EditorViewModel, onZoom: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolChip(vm, SoundTool.Move, Icons.Filled.PanTool, "Move")
        ToolChip(vm, SoundTool.Select, Icons.Filled.HighlightAlt, "Select")
        ToolChip(vm, SoundTool.Volume, Icons.Filled.LinearScale, "Volume")
        SmallIconButton(Icons.Filled.ZoomOut, "Zoom out", size = 36.dp, onClick = { onZoom(0.5f) })
        SmallIconButton(Icons.Filled.ZoomIn, "Zoom in", size = 36.dp, onClick = { onZoom(2f) })
        // Waveform size and track height (tracks can also be pinched taller).
        Box {
            var open by remember { mutableStateOf(false) }
            SmallIconButton(Icons.Filled.Visibility, "View: waveform size, track height", size = 36.dp, onClick = { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Text("Waveform size", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                Row(Modifier.padding(horizontal = 8.dp)) {
                    for (s in listOf(1, 2, 4, 8, 16)) {
                        FilterChip(
                            selected = vm.waveScale == s, onClick = { vm.setWaveScale(s) },
                            label = { Text("×$s", fontSize = 12.sp) }, modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("Taller tracks") },
                    leadingIcon = { Icon(Icons.Filled.UnfoldMore, null) },
                    onClick = { vm.setTrackHeight(vm.trackHeightDp * 1.4f) },
                )
                DropdownMenuItem(
                    text = { Text("Shorter tracks") },
                    leadingIcon = { Icon(Icons.Filled.UnfoldLess, null) },
                    onClick = { vm.setTrackHeight(vm.trackHeightDp / 1.4f) },
                )
            }
        }
    }
}

@Composable
private fun ToolChip(vm: EditorViewModel, tool: SoundTool, icon: ImageVector, label: String) {
    FilterChip(
        selected = vm.soundTool == tool,
        onClick = {
            vm.soundTool = tool
            if (tool != SoundTool.Select) vm.soundSel = null
        },
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
    )
}

/** Actions for the selected section of sound. */
@Composable
fun SoundSelectionBar(
    vm: EditorViewModel, onVolume: () -> Unit, onEffects: () -> Unit, onCleanup: () -> Unit, onReRecord: () -> Unit,
    canPaste: Boolean,
) {
    val s = vm.soundSel ?: return
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.secondaryContainer)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%.2f s".format(Locale.US, (s.toMs - s.fromMs) / 1000.0),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp).width(52.dp),
        )
        SelAction(Icons.Filled.ContentCut, "Cut") { vm.copySelection(cut = true) }
        SelAction(Icons.Filled.ContentCopy, "Copy") { vm.copySelection(cut = false) }
        if (canPaste) SelAction(Icons.Filled.ContentPaste, "Paste") { vm.pasteSound() }
        SelAction(Icons.Filled.Delete, "Delete") { vm.deleteSelection(closeGap = false) }
        SelAction(Icons.Filled.Compress, "Close gap") { vm.deleteSelection(closeGap = true) }
        SelAction(Icons.AutoMirrored.Filled.CallSplit, "Split") { vm.splitAtCursor() }
        SelAction(Icons.Filled.LibraryAdd, "Duplicate") { vm.duplicateSelection() }
        SelAction(Icons.Filled.SpaceBar, "Insert silence") { vm.insertSilence() }
        SelAction(Icons.AutoMirrored.Filled.VolumeUp, "Fade in") { vm.fadeSelection(fadeIn = true) }
        SelAction(Icons.AutoMirrored.Filled.VolumeUp, "Fade out") { vm.fadeSelection(fadeIn = false) }
        SelAction(Icons.Filled.Tune, "Volume…", onVolume)
        SelAction(Icons.Filled.AutoFixHigh, "Effects…", onEffects)
        SelAction(Icons.Filled.AutoAwesome, "Clean up noise…", onCleanup)
        SelAction(Icons.Filled.Mic, "Re-record", onReRecord)
        SelAction(Icons.Filled.Close, "Clear") { vm.soundSel = null }
    }
}

@Composable
private fun SelAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(" $label", fontSize = 13.sp, maxLines = 1)
    }
}

/** Time with milliseconds, e.g. 0:01.234. */
fun formatMsFine(ms: Long): String {
    val m = ms / 60_000
    val s = (ms / 1000) % 60
    return "%d:%02d.%03d".format(Locale.US, m, s, ms % 1000)
}

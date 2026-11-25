package com.pixelgo.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pixelgo.ui.common.ActionBar
import com.pixelgo.ui.common.BarAction
import com.pixelgo.ui.common.PgIconButton
import com.pixelgo.ui.common.Segmented
import com.pixelgo.ui.common.drawCheckerboard
import com.pixelgo.ui.theme.Pg
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Sound mode, first row: leave, play, where you are (time and frame), then paste / record / import. */
@Composable
fun SoundModeHeader(vm: EditorViewModel, onRecord: () -> Unit, onImport: () -> Unit, canPaste: Boolean) {
    vm.renderTick
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PgIconButton(Icons.Filled.CloseFullscreen, "Leave sound mode", { vm.enterSoundMode(false) })
        PlayButton(vm)
        val ms = if (vm.isPlaying) vm.playTick * 1000 / vm.fps else vm.soundCursorMs()
        val frame = (if (vm.isPlaying) vm.playIndex else vm.frameIndex) + 1
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(formatMsFine(ms), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1)
            Text("frame $frame of ${vm.frameCount}", style = Pg.Mark, color = Pg.Text2, maxLines = 1)
        }
        // The frame on screen, to line sound up with the picture.
        val img = (if (vm.isPlaying) vm.playImage else null) ?: vm.image
        val shape = RoundedCornerShape(3.dp)
        Canvas(Modifier.size(40.dp).clip(shape).semantics { contentDescription = "Frame $frame" }) {
            val s = min(size.width / vm.width, size.height / vm.height)
            val w = vm.width * s
            val h = vm.height * s
            val rect = Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
            drawCheckerboard(rect, 4.dp.toPx())
            if (img != null) {
                drawImage(
                    img,
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.None,
                )
            }
        }
        if (canPaste) PgIconButton(Icons.Filled.ContentPaste, "Paste sound at the cursor", vm::pasteSound)
        PgIconButton(Icons.Filled.Mic, "Record sound", onRecord, tint = Pg.Live)
        PgIconButton(Icons.Filled.MusicNote, "Import sound", onImport)
    }
}

/** Sound mode, second row: what dragging does, zoom, and the view (waveform size, track height). */
@Composable
fun SoundToolsRow(vm: EditorViewModel, onZoom: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Pg.BarHeight).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segmented(
            SoundTool.entries, vm.soundTool,
            onSelect = {
                vm.soundTool = it
                if (it != SoundTool.Select) vm.soundSel = null
            },
            label = { it.name },
            modifier = Modifier.width(204.dp),
        )
        Spacer(Modifier.weight(1f))
        PgIconButton(Icons.Filled.ZoomOut, "Zoom out", { onZoom(0.5f) }, iconSize = 22.dp)
        PgIconButton(Icons.Filled.ZoomIn, "Zoom in", { onZoom(2f) }, iconSize = 22.dp)
        Box {
            var open by remember { mutableStateOf(false) }
            PgIconButton(Icons.Outlined.Visibility, "View: waveform size, track height", { open = true }, iconSize = 22.dp)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Text(
                    "Waveform size", style = MaterialTheme.typography.labelMedium, color = Pg.Text2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    for (s in listOf(1, 2, 4, 8, 16)) {
                        FilterChip(
                            selected = vm.waveScale == s, onClick = { vm.setWaveScale(s) },
                            label = { Text("×$s") }, modifier = Modifier.padding(end = 4.dp),
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

/**
 * Actions for the selected section of sound: the everyday ones, then More with the rest in
 * three groups (Edit, Levels, Process). Delete offers Undo.
 */
@Composable
fun SoundSelectionBar(
    vm: EditorViewModel, onVolume: () -> Unit, onEffects: () -> Unit, onCleanup: () -> Unit, onReRecord: () -> Unit,
    canPaste: Boolean,
) {
    val s = vm.soundSel ?: return
    ActionBar(
        lead = "%.2f s".format(Locale.US, (s.toMs - s.fromMs) / 1000.0),
        onClose = { vm.soundSel = null },
        closeLabel = "Clear selection",
        actions = listOf(
            BarAction("Cut", Icons.Filled.ContentCut) { vm.copySelection(cut = true) },
            BarAction("Copy", Icons.Filled.ContentCopy) { vm.copySelection(cut = false) },
            BarAction("Paste", Icons.Filled.ContentPaste, enabled = canPaste) { vm.pasteSound() },
            BarAction("Delete", Icons.Filled.Delete, destructive = true) { vm.deleteSelection(closeGap = false) },
            BarAction("Close gap", Icons.Filled.Compress, group = "Edit") { vm.deleteSelection(closeGap = true) },
            BarAction("Split at the edges", Icons.AutoMirrored.Filled.CallSplit, group = "Edit") { vm.splitAtCursor() },
            BarAction("Duplicate", Icons.Filled.LibraryAdd, group = "Edit") { vm.duplicateSelection() },
            BarAction("Insert silence", Icons.Filled.SpaceBar, group = "Edit") { vm.insertSilence() },
            BarAction("Fade in", Icons.AutoMirrored.Filled.TrendingUp, group = "Levels") { vm.fadeSelection(fadeIn = true) },
            BarAction("Fade out", Icons.AutoMirrored.Filled.TrendingDown, group = "Levels") { vm.fadeSelection(fadeIn = false) },
            BarAction("Volume…", Icons.AutoMirrored.Filled.VolumeUp, group = "Levels", onClick = onVolume),
            BarAction("Effects…", Icons.Filled.AutoFixHigh, group = "Process", onClick = onEffects),
            BarAction("Clean up noise (AI)…", Icons.Filled.AutoAwesome, group = "Process", onClick = onCleanup),
            BarAction("Re-record", Icons.Filled.Mic, group = "Process", onClick = onReRecord),
        ),
    )
}

/** Time with milliseconds, e.g. 0:01.234. */
fun formatMsFine(ms: Long): String {
    val m = ms / 60_000
    val s = (ms / 1000) % 60
    return "%d:%02d.%03d".format(Locale.US, m, s, ms % 1000)
}

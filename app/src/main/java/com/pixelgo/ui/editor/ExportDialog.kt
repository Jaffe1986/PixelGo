package com.pixelgo.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.pixelgo.ui.common.Hint
import com.pixelgo.ui.common.PgTextButton
import com.pixelgo.ui.common.PrimaryButton
import com.pixelgo.ui.common.SecondaryButton
import com.pixelgo.ui.common.SectionLabel
import com.pixelgo.ui.common.SwitchRow
import com.pixelgo.ui.theme.Pg
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pixelgo.data.ExportFile
import com.pixelgo.data.ExportImage
import com.pixelgo.data.PngExporter
import com.pixelgo.data.sheetGrid
import com.pixelgo.data.video.VideoSettings
import com.pixelgo.data.video.VideoShape
import com.pixelgo.data.video.VideoSize
import com.pixelgo.editor.Timing
import com.pixelgo.ui.common.ColorPickerDialog
import com.pixelgo.ui.common.ColorSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Scales = listOf(1, 2, 4, 8, 16)

private enum class ExportMode(val label: String, val kind: String, val animated: Boolean, val video: Boolean = false) {
    Image("Single image", "image", false),
    Layers("Each layer", "layers", false),
    Gif("Animated GIF", "gif", true),
    Sheet("Sprite sheet", "sheet", true),
    Frames("Each frame", "frames", true),
    Mp4("Video MP4", "video", true, video = true),
    Mov("Video MOV", "video", true, video = true),
}

/** Biggest image side we allow (keeps memory in check). */
private const val MAX_SIDE = 8192

@Composable
fun ExportDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animated = vm.frameCount > 1
    val hasSound = vm.clips().isNotEmpty()
    var scale by remember { mutableIntStateOf(if (vm.width <= 64) 8 else 4) }
    var mode by remember { mutableStateOf(if (animated) ExportMode.Gif else ExportMode.Image) }
    var includeHidden by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Video options
    var videoSize by remember { mutableStateOf(VideoSize.P1080) }
    var videoShape by remember { mutableStateOf(VideoShape.CANVAS) }
    var background by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var pickBackground by remember { mutableStateOf(false) }

    val hidden = vm.hiddenLayerCount
    val fw = vm.width * scale
    val fh = vm.height * scale
    val (cols, rows) = sheetGrid(vm.frameCount, fw)

    val fileCount = when (mode) {
        ExportMode.Layers -> vm.layerCount - if (includeHidden) 0 else hidden
        ExportMode.Frames -> vm.frameCount
        else -> 1
    }
    val outW = if (mode == ExportMode.Sheet) cols * fw else fw
    val outH = if (mode == ExportMode.Sheet) rows * fh else fh
    val tooBig = !mode.video && (outW > MAX_SIDE || outH > MAX_SIDE)

    val videoSettings = VideoSettings(videoSize, videoShape, background, mov = mode == ExportMode.Mov)
    val preview = remember(mode, videoSize, videoShape, vm.frameCount, vm.fps, vm.structureTick, vm.loopToAudio) {
        if (mode.video) runCatching { vm.videoPreview(videoSettings)?.let { it to it.layout } }.getOrNull() else null
    }

    val description = when (mode) {
        ExportMode.Image ->
            if (animated) "The current frame (${vm.frameIndex + 1}), all visible layers combined."
            else "All visible layers combined. Transparent background."
        ExportMode.Layers ->
            "One transparent PNG per layer" + (if (animated) " of the current frame" else "") +
                ", numbered bottom (01) to top."
        ExportMode.Gif ->
            "Loops forever at ${vm.fps} fps (held frames last longer). GIF can't do partial " +
                "transparency, so semi-transparent pixels become solid or clear."
        ExportMode.Sheet ->
            "All ${vm.frameCount} frames in one transparent PNG, " +
                (if (rows == 1) "left to right." else "in a $cols × $rows grid (left to right, top to bottom).")
        ExportMode.Frames -> "One transparent PNG per frame, numbered in order."
        ExportMode.Mp4 -> "H.264 video that plays almost anywhere" + (if (hasSound) ", with your sound (AAC)" else "") +
            ". Saved to Movies/PixelGo. Video can't be transparent, so pick a background colour."
        ExportMode.Mov -> "QuickTime video (H.264" + (if (hasSound) " + AAC sound" else "") +
            ") for Apple devices and video editors. Saved to Movies/PixelGo. Video can't be transparent, so pick a background colour."
    }

    /**
     * Single images are copied now (on the main thread). Animation frames are rendered one at
     * a time while the files are written, so any number of frames fits in memory; editing is
     * blocked while the export runs (the dialog stays open).
     */
    fun files(): List<ExportFile> {
        val w = vm.width
        val h = vm.height
        return when (mode) {
            ExportMode.Image -> listOf(
                PngExporter.pngFile(ExportImage(PngExporter.fileName(vm.projectName, scale), vm.exportPixels()), w, h, scale)
            )
            ExportMode.Layers -> vm.exportLayers(scale, includeHidden).map { PngExporter.pngFile(it, w, h, scale) }
            ExportMode.Gif -> {
                val src = vm.exportFrameSource()
                listOf(
                    PngExporter.gifFile(
                        PngExporter.fileName(vm.projectName, scale), src.count, src.render,
                        vm.frameDelaysCs(), w, h, scale,
                    )
                )
            }
            ExportMode.Sheet -> {
                val src = vm.exportFrameSource()
                listOf(PngExporter.sheetFile(PngExporter.fileName(vm.projectName + " sheet", scale), src.count, src.render, w, h, scale))
            }
            ExportMode.Frames -> {
                val src = vm.exportFrameSource()
                val names = PngExporter.layerFileNames(List(src.count) { "frame" }, scale)
                List(src.count) { i -> PngExporter.pngFile(names[i], w, h, scale) { src.render(i) } }
            }
            ExportMode.Mp4, ExportMode.Mov -> emptyList()
        }
    }

    fun save() {
        if (mode.video) {
            vm.startVideoExport(videoSettings)
            onDismiss()
            return
        }
        busy = true
        val list = files()
        val kind = mode.kind
        scope.launch {
            val msg = try {
                val where = withContext(Dispatchers.IO) {
                    PngExporter.saveToPictures(context, vm.projectName, kind, list)
                }
                if (list.size == 1) "Saved to $where" else "Saved ${list.size} files to $where"
            } catch (e: Throwable) {
                "Export failed: ${e.message}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            busy = false
            onDismiss()
        }
    }

    fun share() {
        busy = true
        val list = files()
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { PngExporter.shareIntent(context, list) }
                context.startActivity(intent)
            } catch (e: Throwable) {
                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
            onDismiss()
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else Toast.makeText(context, "Storage permission is needed to save", Toast.LENGTH_LONG).show()
    }
    // Progress notifications for long exports; the export works either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { save() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (busy) "Exporting…" else "Export") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("What to export")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportMode.entries.filter { animated || !it.animated || (it.video && hasSound) }.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
                Hint(description)
                if (mode == ExportMode.Layers && hidden > 0) {
                    SwitchRow("Include hidden layers ($hidden)", includeHidden, { includeHidden = it })
                }
                if (mode.video) {
                    SectionLabel("Size")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VideoSize.entries.forEach { s ->
                            FilterChip(selected = videoSize == s, onClick = { videoSize = s }, label = { Text(s.label) })
                        }
                    }
                    SectionLabel("Shape")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VideoShape.entries.forEach { s ->
                            FilterChip(selected = videoShape == s, onClick = { videoShape = s }, label = { Text(s.label) })
                        }
                    }
                    if (hasSound) {
                        SwitchRow("Loop the animation until the sound ends", vm.loopToAudio, { vm.setLoopToAudio(it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Background")
                        Spacer(Modifier.width(12.dp))
                        ColorSwatch(background, size = 28.dp, label = "Background color")
                        Spacer(Modifier.weight(1f))
                        PgTextButton("Change", onClick = { pickBackground = true })
                    }
                    val p = preview
                    if (p == null) {
                        Text(
                            "This phone can't encode that size; try a smaller one.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        val (exporter, layout) = p
                        // The encoder's target bitrate: pixel art usually comes out far smaller (~1/10).
                        val mb = exporter.bitrate.toLong() * exporter.durationMs / 8 / 1_000_000_000.0
                        Text(
                            "${layout.width} × ${layout.height} px  ·  ${Timing.formatMs(exporter.durationMs)}  ·  " +
                                "up to ${if (mb < 1) "%.1f".format(mb) else "%.0f".format(mb)} MB",
                            style = Pg.Data,
                        )
                        Text(
                            "Pixels are scaled ${layout.scale}× and stay sharp. Long videos keep exporting " +
                                "if you leave the app; you'll get a notification when it's done.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    SectionLabel("Scale")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Scales.forEach { s ->
                            FilterChip(selected = scale == s, onClick = { scale = s }, label = { Text("${s}×") })
                        }
                    }
                    Text(
                        buildString {
                            if (fileCount > 1) append("$fileCount files, ")
                            append("$outW × $outH px")
                            if (fileCount > 1) append(" each")
                        },
                        style = Pg.Data,
                    )
                }
                if (tooBig) {
                    Text(
                        "Too large — pick a smaller scale (max $MAX_SIDE px per side).",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            val enabled = !busy && fileCount > 0 && !tooBig && (!mode.video || preview != null)
            PrimaryButton(if (mode.video) "Export video" else if (PngExporter.needsLegacyPermission) "Save" else "Save to Pictures", enabled = enabled, onClick = {
                when {
                    PngExporter.needsLegacyPermission &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    mode.video && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> save()
                }
            })
        },
        dismissButton = {
            // Two buttons fit on one line: images can be shared instead; video asks to cancel.
            if (mode.video) PgTextButton("Cancel", onClick = onDismiss, enabled = !busy)
            else SecondaryButton("Share", onClick = ::share, enabled = !busy && fileCount > 0 && !tooBig)
        },
    )

    if (pickBackground) {
        ColorPickerDialog(
            initial = background,
            recent = emptyList(),
            onDismiss = { pickBackground = false },
            onConfirm = {
                pickBackground = false
                background = it or (0xFF shl 24) // video backgrounds are always opaque
            },
        )
    }
}

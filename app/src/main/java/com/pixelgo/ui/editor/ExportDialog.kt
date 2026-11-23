package com.pixelgo.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Scales = listOf(1, 2, 4, 8, 16)

private enum class ExportMode(val label: String, val kind: String, val animated: Boolean) {
    Image("Single image", "image", false),
    Layers("Each layer", "layers", false),
    Gif("Animated GIF", "gif", true),
    Sheet("Sprite sheet", "sheet", true),
    Frames("Each frame", "frames", true),
}

/** Biggest image side we allow (keeps memory in check). */
private const val MAX_SIDE = 8192

@Composable
fun ExportDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animated = vm.frameCount > 1
    var scale by remember { mutableIntStateOf(if (vm.width <= 64) 8 else 4) }
    var mode by remember { mutableStateOf(if (animated) ExportMode.Gif else ExportMode.Image) }
    var includeHidden by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

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
    val tooBig = outW > MAX_SIDE || outH > MAX_SIDE

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
    }

    /** Snapshot the pixels now (on the main thread) so later edits don't race the export. */
    fun files(): List<ExportFile> {
        val w = vm.width
        val h = vm.height
        return when (mode) {
            ExportMode.Image -> listOf(
                PngExporter.pngFile(ExportImage(PngExporter.fileName(vm.projectName, scale), vm.exportPixels()), w, h, scale)
            )
            ExportMode.Layers -> vm.exportLayers(scale, includeHidden).map { PngExporter.pngFile(it, w, h, scale) }
            ExportMode.Gif -> listOf(
                PngExporter.gifFile(PngExporter.fileName(vm.projectName, scale), vm.exportFrames(), vm.frameDelaysCs(), w, h, scale)
            )
            ExportMode.Sheet -> listOf(
                PngExporter.sheetFile(PngExporter.fileName(vm.projectName + " sheet", scale), vm.exportFrames(), w, h, scale)
            )
            ExportMode.Frames -> {
                val frames = vm.exportFrames()
                val names = PngExporter.layerFileNames(List(frames.size) { "frame" }, scale)
                frames.mapIndexed { i, px -> PngExporter.pngFile(ExportImage(names[i], px), w, h, scale) }
            }
        }
    }

    fun save() {
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

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else Toast.makeText(context, "Storage permission is needed to save", Toast.LENGTH_LONG).show()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (busy) "Exporting…" else "Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What to export", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportMode.entries.filter { animated || !it.animated }.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
                Text(description, style = MaterialTheme.typography.bodySmall)
                if (mode == ExportMode.Layers && hidden > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { includeHidden = !includeHidden },
                    ) {
                        Checkbox(checked = includeHidden, onCheckedChange = { includeHidden = it })
                        Text("Include hidden layers ($hidden)")
                    }
                }
                Text("Scale", style = MaterialTheme.typography.labelLarge)
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
                    style = MaterialTheme.typography.bodyMedium,
                )
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
            TextButton(enabled = !busy && fileCount > 0 && !tooBig, onClick = {
                if (PngExporter.needsLegacyPermission &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    save()
                }
            }) { Text("Save to Pictures") }
        },
        dismissButton = {
            TextButton(enabled = !busy && fileCount > 0 && !tooBig, onClick = ::share) { Text("Share") }
        },
    )
}

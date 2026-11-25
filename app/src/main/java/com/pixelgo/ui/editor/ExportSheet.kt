package com.pixelgo.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pixelgo.data.ExportFile
import com.pixelgo.data.ExportImage
import com.pixelgo.data.PngExporter
import com.pixelgo.data.sheetGrid
import com.pixelgo.data.video.VideoLayout
import com.pixelgo.data.video.VideoSettings
import com.pixelgo.data.video.VideoShape
import com.pixelgo.data.video.VideoSize
import com.pixelgo.editor.Timing
import com.pixelgo.ui.common.ColorPickerSheet
import com.pixelgo.ui.common.ColorSwatch
import com.pixelgo.ui.common.Hint
import com.pixelgo.ui.common.PgSheet
import com.pixelgo.ui.common.PgTextButton
import com.pixelgo.ui.common.PrimaryButton
import com.pixelgo.ui.common.SecondaryButton
import com.pixelgo.ui.common.SectionLabel
import com.pixelgo.ui.common.Segmented
import com.pixelgo.ui.common.SwitchRow
import com.pixelgo.ui.common.drawCheckerboard
import com.pixelgo.ui.theme.Pg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

private val Scales = listOf(1, 2, 4, 8, 16)

/** What to make. Video covers both MP4 and MOV (chosen inside its options). */
private enum class ExportFormat(val label: String, val icon: ImageVector, val animated: Boolean) {
    Image("Image", Icons.Outlined.Image, false),
    Layers("Layers", Icons.Outlined.Layers, false),
    Gif("GIF", Icons.Outlined.GifBox, true),
    Sheet("Sprite sheet", Icons.Outlined.GridView, true),
    Frames("Frames", Icons.Outlined.BurstMode, true),
    Video("Video", Icons.Outlined.Movie, true),
}

/** Biggest image side we allow (keeps memory in check). */
private const val MAX_SIDE = 8192

/**
 * Export as a sheet: a preview at the real output shape, the formats as tiles, only the chosen
 * format's options, the result in one line, and one main button.
 */
@Composable
fun ExportSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animated = vm.frameCount > 1
    val hasSound = vm.clips().isNotEmpty()
    var scale by remember { mutableIntStateOf(if (vm.width <= 64) 8 else 4) }
    var format by remember { mutableStateOf(if (animated) ExportFormat.Gif else ExportFormat.Image) }
    var mov by remember { mutableStateOf(false) }
    var includeHidden by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Video options
    var videoSize by remember { mutableStateOf(VideoSize.P1080) }
    var videoShape by remember { mutableStateOf(VideoShape.CANVAS) }
    var background by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var pickBackground by remember { mutableStateOf(false) }

    val video = format == ExportFormat.Video
    val hidden = vm.hiddenLayerCount
    val fw = vm.width * scale
    val fh = vm.height * scale
    val (cols, rows) = sheetGrid(vm.frameCount, fw)

    val fileCount = when (format) {
        ExportFormat.Layers -> vm.layerCount - if (includeHidden) 0 else hidden
        ExportFormat.Frames -> vm.frameCount
        else -> 1
    }
    val outW = if (format == ExportFormat.Sheet) cols * fw else fw
    val outH = if (format == ExportFormat.Sheet) rows * fh else fh
    val tooBig = !video && (outW > MAX_SIDE || outH > MAX_SIDE)

    val videoSettings = VideoSettings(videoSize, videoShape, background, mov = mov)
    val preview = remember(format, mov, videoSize, videoShape, vm.frameCount, vm.fps, vm.structureTick, vm.loopToAudio) {
        if (video) runCatching { vm.videoPreview(videoSettings) }.getOrNull() else null
    }
    val animMs = (vm.timing()?.totalTicks ?: 0L) * 1000L / vm.fps

    val description = when (format) {
        ExportFormat.Image ->
            if (animated) "The current frame (${vm.frameIndex + 1}), all visible layers, transparent background."
            else "All visible layers combined, transparent background."
        ExportFormat.Layers -> "One transparent PNG per layer" + (if (animated) " of the current frame" else "") + ", numbered bottom (01) to top."
        ExportFormat.Gif -> "Loops forever at ${vm.fps} fps. GIF has no partial transparency: half-transparent pixels become solid or clear."
        ExportFormat.Sheet -> "All ${vm.frameCount} frames in one transparent PNG, " +
            (if (rows == 1) "left to right." else "in a $cols × $rows grid.")
        ExportFormat.Frames -> "One transparent PNG per frame, numbered in order."
        ExportFormat.Video ->
            (if (mov) "QuickTime (H.264) for Apple devices and video editors" else "H.264 video that plays almost anywhere") +
                (if (hasSound) ", with your sound" else "") + ". Video can't be transparent, so it has a background color."
    }

    val summary = when (format) {
        ExportFormat.Image -> "PNG  ·  $outW × $outH px"
        ExportFormat.Layers -> "$fileCount PNG files  ·  $outW × $outH px each"
        ExportFormat.Gif -> "GIF  ·  $outW × $outH px  ·  ${vm.frameCount} frames  ·  ${Timing.formatMs(animMs)}"
        ExportFormat.Sheet -> "PNG  ·  $outW × $outH px  ($cols × $rows frames)"
        ExportFormat.Frames -> "$fileCount PNG files  ·  $outW × $outH px each"
        ExportFormat.Video -> preview?.let { p ->
            // The encoder's target bitrate: pixel art usually comes out far smaller (~1/10).
            val mb = p.bitrate.toLong() * p.durationMs / 8 / 1_000_000_000.0
            "${if (mov) "MOV" else "MP4"}  ·  ${p.layout.width} × ${p.layout.height}  ·  ${Timing.formatMs(p.durationMs)}  ·  " +
                "up to ${if (mb < 1) "%.1f".format(Locale.US, mb) else "%.0f".format(Locale.US, mb)} MB"
        } ?: "This phone can't make a video that size. Try a smaller one."
    }

    /**
     * Single images are copied now (on the main thread). Animation frames are rendered one at
     * a time while the files are written, so any number of frames fits in memory.
     */
    fun files(): List<ExportFile> {
        val w = vm.width
        val h = vm.height
        return when (format) {
            ExportFormat.Image -> listOf(
                PngExporter.pngFile(ExportImage(PngExporter.fileName(vm.projectName, scale), vm.exportPixels()), w, h, scale)
            )
            ExportFormat.Layers -> vm.exportLayers(scale, includeHidden).map { PngExporter.pngFile(it, w, h, scale) }
            ExportFormat.Gif -> {
                val src = vm.exportFrameSource()
                listOf(
                    PngExporter.gifFile(
                        PngExporter.fileName(vm.projectName, scale), src.count, src.render,
                        vm.frameDelaysCs(), w, h, scale,
                    )
                )
            }
            ExportFormat.Sheet -> {
                val src = vm.exportFrameSource()
                listOf(PngExporter.sheetFile(PngExporter.fileName(vm.projectName + " sheet", scale), src.count, src.render, w, h, scale))
            }
            ExportFormat.Frames -> {
                val src = vm.exportFrameSource()
                val names = PngExporter.layerFileNames(List(src.count) { "frame" }, scale)
                List(src.count) { i -> PngExporter.pngFile(names[i], w, h, scale) { src.render(i) } }
            }
            ExportFormat.Video -> emptyList()
        }
    }

    fun save() {
        if (video) {
            vm.startVideoExport(videoSettings)
            onDismiss()
            return
        }
        busy = true
        val list = files()
        val kind = when (format) {
            ExportFormat.Image -> "image"
            ExportFormat.Layers -> "layers"
            ExportFormat.Gif -> "gif"
            ExportFormat.Sheet -> "sheet"
            else -> "frames"
        }
        scope.launch {
            val msg = try {
                val where = withContext(Dispatchers.IO) { PngExporter.saveToPictures(context, vm.projectName, kind, list) }
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
    val canSave = !busy && fileCount > 0 && !tooBig && (!video || preview != null)

    PgSheet(
        title = "Export",
        subtitle = vm.projectName + if (animated) "  ·  ${vm.frameCount} frames  ·  ${vm.fps} fps" else "",
        onDismiss = { if (!busy) onDismiss() },
        footer = {
            PgTextButton("Cancel", onClick = onDismiss, enabled = !busy)
            if (!video) SecondaryButton("Share", onClick = ::share, enabled = canSave, icon = Icons.Outlined.Share)
            PrimaryButton(
                when {
                    busy -> "Saving…"
                    video -> "Export video"
                    PngExporter.needsLegacyPermission -> "Save"
                    else -> "Save to Pictures"
                },
                enabled = canSave,
                onClick = {
                    when {
                        PngExporter.needsLegacyPermission &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        video && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> save()
                    }
                },
            )
        },
    ) {
        ExportPreview(
            vm, format, video, preview?.layout, background,
            label = if (video) preview?.layout?.let { "${it.width} × ${it.height}" } else "$outW × $outH",
            cols = cols, rows = rows,
        )

        FormatTiles(
            ExportFormat.entries.filter { animated || !it.animated || (it == ExportFormat.Video && hasSound) },
            format,
        ) { format = it }
        Hint(description)

        if (video) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("File type")
                Segmented(listOf(false, true), mov, { mov = it }, label = { if (it) "MOV" else "MP4" }, modifier = Modifier.fillMaxWidth())
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Size")
                Segmented(VideoSize.entries, videoSize, { videoSize = it }, label = { it.label }, modifier = Modifier.fillMaxWidth())
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Shape")
                Segmented(VideoShape.entries, videoShape, { videoShape = it }, label = { it.label }, modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Background", Modifier.weight(1f))
                ColorSwatch(background, size = 28.dp, label = "Background color")
                PgTextButton("Change", onClick = { pickBackground = true })
            }
            if (hasSound) SwitchRow("Loop the animation until the sound ends", vm.loopToAudio, { vm.setLoopToAudio(it) })
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Scale")
                Segmented(Scales, scale, { scale = it }, label = { "$it×" }, modifier = Modifier.fillMaxWidth())
            }
            if (format == ExportFormat.Layers && hidden > 0) {
                SwitchRow("Include hidden layers ($hidden)", includeHidden, { includeHidden = it })
            }
        }

        Text(
            if (tooBig) "Too large: pick a smaller scale (at most $MAX_SIDE px a side)." else summary,
            style = Pg.Data,
            color = if (tooBig || (video && preview == null)) Pg.Danger else Pg.Text2,
        )
        if (video) Hint("Long videos keep exporting if you leave the app; a notification says when it's done.")
    }

    if (pickBackground) {
        ColorPickerSheet(
            initial = background,
            recent = emptyList(),
            title = "Background",
            onDismiss = { pickBackground = false },
            onConfirm = {
                pickBackground = false
                background = it or (0xFF shl 24) // video backgrounds are always opaque
            },
        )
    }
}

/** The formats as a grid of tiles; the chosen one is outlined in yellow. */
@Composable
private fun FormatTiles(formats: List<ExportFormat>, selected: ExportFormat, onSelect: (ExportFormat) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        formats.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { f ->
                    val on = f == selected
                    val fg = if (on) Pg.Accent else Pg.Text1
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) Pg.AccentWeak else Pg.Surface3)
                            .then(if (on) Modifier.border(2.dp, Pg.Accent, RoundedCornerShape(8.dp)) else Modifier)
                            .selectable(selected = on, onClick = { onSelect(f) }, role = Role.RadioButton)
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(f.icon, null, tint = fg, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(f.label, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                // Keep tiles the same width on a short last row.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * What the file will look like: the current frame at the output's shape. Images sit on a
 * checkerboard (they keep transparency); video gets its background and padding; a sprite sheet
 * shows its grid.
 */
@Composable
private fun ExportPreview(
    vm: EditorViewModel,
    format: ExportFormat,
    video: Boolean,
    layout: VideoLayout?,
    background: Int,
    label: String?,
    cols: Int,
    rows: Int,
) {
    vm.renderTick
    val img = vm.image
    Box(
        Modifier.fillMaxWidth().height(188.dp).clip(RoundedCornerShape(8.dp)).background(Pg.Canvas),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            // Output size in "art pixels" (so the preview keeps the true shape).
            val (ow, oh) = when {
                video && layout != null -> layout.width.toFloat() to layout.height.toFloat()
                format == ExportFormat.Sheet -> (vm.width * cols).toFloat() to (vm.height * rows).toFloat()
                else -> vm.width.toFloat() to vm.height.toFloat()
            }
            var s = min(size.width / ow, size.height / oh)
            if (!video && s >= 1f) s = floor(s) // whole pixels, like the export
            val w = ow * s
            val h = oh * s
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2
            val outRect = Rect(Offset(left, top), Size(w, h))

            fun drawArt(x: Float, y: Float, aw: Float, ah: Float) {
                drawImage(
                    img,
                    dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                    dstSize = IntSize(aw.roundToInt().coerceAtLeast(1), ah.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.None,
                )
            }

            if (video && layout != null) {
                drawRect(Color(background), outRect.topLeft, outRect.size)
                val k = w / layout.width
                drawArt(left + layout.offsetX * k, top + layout.offsetY * k, layout.contentWidth * k, layout.contentHeight * k)
            } else if (format == ExportFormat.Sheet) {
                val cw = w / cols
                val ch = h / rows
                for (r in 0 until rows) for (c in 0 until cols) {
                    if (r * cols + c >= vm.frameCount) continue
                    val cell = Rect(Offset(left + c * cw, top + r * ch), Size(cw, ch))
                    drawCheckerboard(cell, maxOf(2f, cw / 8))
                    drawRect(Color.Black.copy(alpha = 0.25f), cell.topLeft, cell.size, style = Stroke(1f))
                }
                drawArt(left, top, cw, ch)
            } else {
                drawCheckerboard(outRect, maxOf(2f, min(w, h) / 16))
                drawArt(left, top, w, h)
            }
        }
        if (label != null) {
            Text(
                label, style = Pg.Data, color = Pg.Text1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

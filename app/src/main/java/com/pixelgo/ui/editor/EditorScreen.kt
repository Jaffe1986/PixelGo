package com.pixelgo.ui.editor

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pixelgo.PixelGoApp
import com.pixelgo.export.ExportJobs
import com.pixelgo.export.ExportState
import com.pixelgo.ui.common.ColorPickerSheet
import com.pixelgo.ui.common.DockButton
import com.pixelgo.ui.common.EraserIcon
import com.pixelgo.ui.common.PgIconButton
import com.pixelgo.ui.common.PgSnackbarHost
import com.pixelgo.ui.common.PrimaryButton
import com.pixelgo.ui.common.SecondaryButton
import com.pixelgo.ui.common.TextInputDialog
import com.pixelgo.ui.common.ToolButton
import com.pixelgo.ui.common.drawCheckerboard
import com.pixelgo.ui.common.mirrorIcon
import com.pixelgo.ui.theme.Pg
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit, onPalettes: () -> Unit) {
    val app = LocalContext.current.applicationContext as PixelGoApp
    val vm: EditorViewModel = viewModel(
        key = projectId,
        factory = viewModelFactory { initializer { EditorViewModel(app, projectId) } },
    )

    when (vm.loadState) {
        LoadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        LoadState.Failed -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This project could not be opened.")
                    PrimaryButton("Back", onClick = onBack, modifier = Modifier.padding(top = 12.dp))
                }
            }
            return
        }
        LoadState.Ready -> Unit
    }
    if (vm.engine == null) return

    val leave = { vm.save(); onBack() }
    // In sound mode, Back leaves sound mode first.
    BackHandler(onBack = { if (vm.soundMode) vm.enterSoundMode(false) else leave() })
    // Leaving the app: stop sound/recording (Android silences the mic in the background) and save.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackgrounded() }

    var showLayers by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    // Sideways: tools and colors move to rails left and right of the canvas.
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp

    Scaffold(
        containerColor = Pg.Background,
        topBar = {
            EditorTopBar(
                vm, landscape,
                onBack = leave,
                onExport = { showExport = true },
                onLayers = { showLayers = true },
                onSettings = { showSettings = true },
                onRename = { renaming = true },
                onPalettes = onPalettes,
            )
        },
        snackbarHost = { if (vm.soundMode) PgSnackbarHost(vm.snackbar) },
    ) { padding ->
        if (vm.soundMode) {
            // Sound mode: the timeline fills the screen (the frame on screen shows in its header).
            Surface(Modifier.fillMaxSize().padding(padding), color = Pg.Surface1) {
                Column(Modifier.fillMaxSize()) { TimelineView(vm, Modifier.weight(1f)) }
            }
            return@Scaffold
        }
        val pickColor = { showPicker = true }
        if (landscape) {
            // The rails run the full height (sideways there's little of it); the timeline sits
            // between them, under the canvas.
            Row(Modifier.fillMaxSize().padding(padding)) {
                ToolRail(vm, Modifier.fillMaxHeight().width(64.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CanvasArea(vm, Modifier.weight(1f).fillMaxWidth())
                    if (vm.tool == Tool.Select) SelectionBar(vm)
                    Surface(color = Pg.Surface1, modifier = Modifier.animateContentSize(tween(180))) { TimelineView(vm) }
                }
                Surface(color = Pg.Surface1, modifier = Modifier.fillMaxHeight().width(92.dp)) {
                    PaletteRail(vm, app.palettes, onEditCurrentColor = pickColor, onManagePalettes = onPalettes)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                CanvasArea(vm, Modifier.weight(1f).fillMaxWidth())
                if (vm.tool == Tool.Select) SelectionBar(vm)
                Surface(color = Pg.Surface1) {
                    Column(Modifier.animateContentSize(tween(180))) {
                        TimelineView(vm)
                        HorizontalDivider(color = Pg.Outline)
                        PaletteStrip(vm, app.palettes, onEditCurrentColor = pickColor, onManagePalettes = onPalettes)
                        ToolDock(vm, onLayers = { showLayers = true })
                    }
                }
            }
        }
    }

    // A video export of this project: progress while it runs, then the result.
    val export = ExportJobs.state
    if (export != null && export.projectId == projectId) {
        ExportOverlay(export)
    }

    // Long jobs (merging, duplicating layers…) block editing with a progress overlay.
    vm.busyLabel?.let { label ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xAA000000))
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = Pg.Surface2) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { vm.busyProgress },
                        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(0.6f),
                    )
                }
            }
        }
    }

    if (showLayers) LayersSheet(vm, onDismiss = { showLayers = false })
    if (showPicker) {
        ColorPickerSheet(
            initial = vm.color,
            recent = app.palettes.recentColors,
            onDismiss = { showPicker = false },
            onConfirm = { showPicker = false; vm.pickColor(it) },
            onAddToPalette = { c ->
                showPicker = false
                app.palettes.addColor(app.palettes.active.id, c)
                vm.pickColor(c)
            },
        )
    }
    if (showExport) ExportSheet(vm, onDismiss = { showExport = false })
    if (showSettings) AnimationSettingsDialog(vm, onDismiss = { showSettings = false })
    if (renaming) {
        TextInputDialog("Rename project", vm.projectName, onDismiss = { renaming = false }) {
            renaming = false
            vm.renameProject(it)
        }
    }
}

/**
 * The editor's top bar: the project and what you're on (layer, frame), then Undo, Redo, Export
 * and a menu. Sideways it also holds Mirror and Layers, which have no dock to live in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    vm: EditorViewModel,
    landscape: Boolean,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onLayers: () -> Unit,
    onSettings: () -> Unit,
    onRename: () -> Unit,
    onPalettes: () -> Unit,
) {
    vm.structureTick
    var menu by remember { mutableStateOf(false) }
    val layer = vm.engine?.project?.activeLayer
    val subtitle = if (vm.soundMode) "Sound" else buildString {
        append(layer?.name.orEmpty())
        if (layer?.visible == false) append(" (hidden)")
        if (vm.frameCount > 1) append("  ·  Frame ${vm.frameIndex + 1} of ${vm.frameCount}")
    }
    val titleStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp)
    TopAppBar(
        title = {
            if (landscape) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.projectName, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        subtitle, style = MaterialTheme.typography.bodySmall, color = Pg.Text2, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp),
                    )
                }
            } else {
                Column {
                    Text(vm.projectName, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Pg.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = { PgIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
        actions = {
            if (landscape && !vm.soundMode) {
                MirrorButton(vm, Modifier.width(52.dp), showLabel = false)
                LayersButton(vm, onLayers, Modifier.width(52.dp), showLabel = false)
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp), color = Pg.Outline)
            }
            PgIconButton(Icons.AutoMirrored.Filled.Undo, "Undo", vm::undo, enabled = vm.canUndo || vm.isFloating)
            PgIconButton(Icons.AutoMirrored.Filled.Redo, "Redo", vm::redo, enabled = vm.canRedo && !vm.isFloating)
            PgIconButton(Icons.Filled.IosShare, "Export", onExport)
            Box {
                PgIconButton(Icons.Filled.MoreVert, "More options", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!vm.soundMode) {
                        Text(
                            "View", style = MaterialTheme.typography.labelMedium, color = Pg.Text2,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        CheckItem("Pixel grid", vm.showGrid) { vm.showGrid = !vm.showGrid }
                        CheckItem("Vertical center guide", vm.guideVertical) { vm.guideVertical = !vm.guideVertical }
                        CheckItem("Horizontal center guide", vm.guideHorizontal) { vm.guideHorizontal = !vm.guideHorizontal }
                        DropdownMenuItem(
                            text = { Text("Fit to screen") },
                            leadingIcon = { Box(Modifier.size(24.dp)) },
                            onClick = { menu = false; vm.viewport.refit(vm.width, vm.height) },
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text("Animation settings…") }, onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text("Rename project…") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Manage palettes…") }, onClick = { menu = false; onPalettes() })
                }
            }
        },
        expandedHeight = if (landscape) Pg.TopBarHeightCompact else Pg.TopBarHeight,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Pg.Surface1, titleContentColor = Pg.Text1,
            navigationIconContentColor = Pg.Text1, actionIconContentColor = Pg.Text1,
        ),
    )
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { if (checked) Icon(Icons.Filled.Check, null) else Box(Modifier.size(24.dp)) },
        onClick = onToggle,
    )
}

/** The canvas, with the Undo snackbar floating at its bottom edge (clear of the tools). */
@Composable
private fun CanvasArea(vm: EditorViewModel, modifier: Modifier) {
    Box(modifier) {
        PixelCanvas(vm)
        PgSnackbarHost(vm.snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private class ToolDef(val tool: Tool, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val Tools = listOf(
    ToolDef(Tool.Pencil, "Pencil", Icons.Outlined.Edit, Icons.Filled.Edit),
    ToolDef(Tool.Eraser, "Eraser", EraserIcon, EraserIcon),
    ToolDef(Tool.Fill, "Fill", Icons.Outlined.FormatColorFill, Icons.Filled.FormatColorFill),
    ToolDef(Tool.Eyedropper, "Pick", Icons.Outlined.Colorize, Icons.Filled.Colorize),
    ToolDef(Tool.Select, "Select", Icons.Outlined.HighlightAlt, Icons.Filled.HighlightAlt),
)

/** Portrait: the five tools as one group, then Mirror and Layers (which aren't tools). */
@Composable
private fun ToolDock(vm: EditorViewModel, onLayers: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Pg.DockHeight).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Tools.forEach { t ->
                ToolButton(
                    t.icon, t.label, vm.tool == t.tool, onClick = { vm.tool = t.tool },
                    modifier = Modifier.weight(1f), selectedIcon = t.selectedIcon,
                )
            }
        }
        VerticalDivider(Modifier.height(32.dp).padding(horizontal = 6.dp), color = Pg.Outline)
        MirrorButton(vm, Modifier.width(56.dp))
        LayersButton(vm, onLayers, Modifier.width(64.dp))
    }
}

/** Landscape: the tools down the left side, icons only. */
@Composable
private fun ToolRail(vm: EditorViewModel, modifier: Modifier) {
    Surface(color = Pg.Surface1, modifier = modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Tools.forEach { t ->
                ToolButton(
                    t.icon, t.label, vm.tool == t.tool, onClick = { vm.tool = t.tool },
                    modifier = Modifier.fillMaxWidth(), selectedIcon = t.selectedIcon, showLabel = false,
                )
            }
        }
    }
}

/**
 * Mirror drawing in one control. Tap steps Off → left-right → top-bottom → both; long-press
 * lists them. The icon shows which axes are on.
 */
@Composable
private fun MirrorButton(vm: EditorViewModel, modifier: Modifier, showLabel: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    val x = vm.mirrorX
    val y = vm.mirrorY
    val options = listOf(
        "Off" to (false to false),
        "Left and right" to (true to false),
        "Top and bottom" to (false to true),
        "Both" to (true to true),
    )
    val current = options.indexOfFirst { it.second == (x to y) }.coerceAtLeast(0)
    Box(modifier) {
        DockButton(
            "Mirror",
            onClick = {
                val (nx, ny) = options[(current + 1) % options.size].second
                vm.setMirror(nx, ny)
            },
            modifier = Modifier.fillMaxWidth(),
            on = x || y,
            onLongClick = { menu = true },
            onLongClickLabel = "Choose mirror axes",
            stateText = options[current].first,
            showLabel = showLabel,
        ) { tint -> Icon(mirrorIcon(x, y), null, tint = tint, modifier = Modifier.size(24.dp)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            options.forEachIndexed { i, (label, xy) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(mirrorIcon(xy.first, xy.second), null) },
                    trailingIcon = { if (i == current) Icon(Icons.Filled.Check, null) },
                    onClick = { menu = false; vm.setMirror(xy.first, xy.second) },
                )
            }
        }
    }
}

/** Opens the layers sheet; shows the active layer (its pixels on this frame, and its name). */
@Composable
private fun LayersButton(vm: EditorViewModel, onClick: () -> Unit, modifier: Modifier, showLabel: Boolean = true) {
    vm.structureTick
    val layer = vm.engine?.project?.activeLayer
    val thumb = remember(layer?.id, vm.editTick, vm.structureTick, vm.frameIndex) { layer?.let { vm.layerThumb(it.id) } }
    DockButton(
        label = layer?.name ?: "Layers",
        onClick = onClick,
        modifier = modifier,
        stateText = "Layers, ${vm.layerCount} in all",
        showLabel = showLabel,
    ) { tint ->
        val shape = RoundedCornerShape(3.dp)
        Canvas(Modifier.size(24.dp).clip(shape).border(1.dp, tint.copy(alpha = 0.7f), shape)) {
            val s = min(size.width / vm.width, size.height / vm.height)
            val w = vm.width * s
            val h = vm.height * s
            val rect = Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
            drawCheckerboard(rect, 3.dp.toPx())
            if (thumb != null) {
                drawImage(
                    thumb,
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.None,
                )
            }
        }
    }
}

/** Blocks the editor while a video export runs, then shows where it was saved. */
@Composable
private fun ExportOverlay(s: ExportState) {
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = Pg.Surface2) {
            Column(Modifier.padding(24.dp).fillMaxWidth(0.85f), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    !s.done -> {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
                        Text("${(s.progress * 100).toInt()}%", style = Pg.Data, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "You can switch apps; the export keeps going.",
                            style = MaterialTheme.typography.bodySmall, color = Pg.Text2,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        SecondaryButton("Cancel export", onClick = { ExportJobs.cancel() }, modifier = Modifier.padding(top = 16.dp))
                    }
                    s.error != null -> {
                        Text("Export didn't finish", style = MaterialTheme.typography.titleMedium)
                        Text(s.error, modifier = Modifier.padding(top = 8.dp))
                        PrimaryButton("OK", onClick = { ExportJobs.dismiss() }, modifier = Modifier.padding(top = 16.dp))
                    }
                    else -> {
                        Text("Video saved", style = MaterialTheme.typography.titleMedium)
                        Text(
                            s.resultPath ?: "", style = MaterialTheme.typography.bodySmall, color = Pg.Text2,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.resultUri?.let { uri ->
                                SecondaryButton("Share", onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = s.mime
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share video"))
                                })
                            }
                            PrimaryButton("Done", onClick = { ExportJobs.dismiss() })
                        }
                    }
                }
            }
        }
    }
}

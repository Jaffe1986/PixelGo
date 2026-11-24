package com.pixelgo.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import android.content.Intent
import com.pixelgo.export.ExportJobs
import com.pixelgo.export.ExportState
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pixelgo.PixelGoApp
import com.pixelgo.ui.common.ColorPickerDialog
import com.pixelgo.ui.common.EraserIcon
import com.pixelgo.ui.common.TextInputDialog

@OptIn(ExperimentalMaterial3Api::class)
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
                    Button(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Back") }
                }
            }
            return
        }
        LoadState.Ready -> Unit
    }
    val engine = vm.engine ?: return

    val leave = { vm.save(); onBack() }
    BackHandler(onBack = leave)
    // Leaving the app: stop sound/recording (Android silences the mic in the background) and save.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackgrounded() }

    var menu by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.projectName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo || vm.isFloating) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo && !vm.isFloating) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = { vm.showGrid = !vm.showGrid }) {
                        Icon(if (vm.showGrid) Icons.Filled.GridOn else Icons.Filled.GridOff, "Toggle grid")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            CheckItem("Vertical center guide", vm.guideVertical) { vm.guideVertical = !vm.guideVertical }
                            CheckItem("Horizontal center guide", vm.guideHorizontal) { vm.guideHorizontal = !vm.guideHorizontal }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Fit to screen") }, onClick = {
                                menu = false; vm.viewport.refit(vm.width, vm.height)
                            })
                            DropdownMenuItem(text = { Text("Export…") }, onClick = { menu = false; showExport = true })
                            DropdownMenuItem(text = { Text("Rename project…") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Manage palettes…") }, onClick = { menu = false; onPalettes() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PixelCanvas(vm)
                vm.structureTick
                val layer = engine.project.activeLayer
                if (layer != null) {
                    Text(
                        "${layer.name}${if (!layer.visible) " (hidden)" else ""}" +
                            if (vm.frameCount > 1) "  ·  Frame ${vm.frameIndex + 1}/${vm.frameCount}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x99000000))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    if (vm.tool == Tool.Select) {
                        SelectionBar(vm)
                        HorizontalDivider()
                    }
                    TimelineView(vm)
                    HorizontalDivider()
                    PaletteStrip(vm, app.palettes, onEditCurrentColor = { showPicker = true }, onManagePalettes = onPalettes)
                    HorizontalDivider()
                    ToolBar(vm, onLayers = { showLayers = true })
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
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
        ColorPickerDialog(
            initial = vm.color,
            recent = app.palettes.recentColors,
            onDismiss = { showPicker = false },
            onConfirm = { showPicker = false; vm.pickColor(it) },
        )
    }
    if (showExport) ExportDialog(vm, onDismiss = { showExport = false })
    if (renaming) {
        TextInputDialog("Rename project", vm.projectName, onDismiss = { renaming = false }) {
            renaming = false
            vm.renameProject(it)
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
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp).fillMaxWidth(0.85f), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    !s.done -> {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
                        Text("${(s.progress * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "You can switch apps; the export keeps going.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(onClick = { ExportJobs.cancel() }, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel export") }
                    }
                    s.error != null -> {
                        Text("Export didn't finish", style = MaterialTheme.typography.titleMedium)
                        Text(s.error, modifier = Modifier.padding(top = 8.dp))
                        TextButton(onClick = { ExportJobs.dismiss() }, modifier = Modifier.padding(top = 8.dp)) { Text("OK") }
                    }
                    else -> {
                        Text("Video saved", style = MaterialTheme.typography.titleMedium)
                        Text(s.resultPath ?: "", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.padding(top = 8.dp)) {
                            s.resultUri?.let { uri ->
                                TextButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = s.mime
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share video"))
                                }) { Text("Share") }
                            }
                            TextButton(onClick = { ExportJobs.dismiss() }) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { if (checked) Icon(Icons.Filled.Check, null) else Box(Modifier.size(24.dp)) },
        onClick = onToggle,
    )
}

@Composable
private fun ToolBar(vm: EditorViewModel, onLayers: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(Icons.Filled.Edit, "Pencil", vm.tool == Tool.Pencil) { vm.tool = Tool.Pencil }
        ToolButton(EraserIcon, "Eraser", vm.tool == Tool.Eraser) { vm.tool = Tool.Eraser }
        ToolButton(Icons.Filled.FormatColorFill, "Fill", vm.tool == Tool.Fill) { vm.tool = Tool.Fill }
        ToolButton(Icons.Filled.Colorize, "Pick", vm.tool == Tool.Eyedropper) { vm.tool = Tool.Eyedropper }
        ToolButton(Icons.Filled.HighlightAlt, "Select", vm.tool == Tool.Select) { vm.tool = Tool.Select }
        ToolButton(Icons.Filled.Flip, "Mirror ↔", vm.mirrorX, onClick = vm::toggleMirrorX)
        ToolButton(Icons.Filled.Flip, "Mirror ↕", vm.mirrorY, rotate = 90f, onClick = vm::toggleMirrorY)
        ToolButton(Icons.Filled.Layers, "Layers", false, onClick = onLayers)
    }
}

@Composable
private fun RowScope.ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    rotate: Float = 0f,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(24.dp).rotate(rotate))
        Text(label, color = fg, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

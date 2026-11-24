package com.pixelgo.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pixelgo.PixelGoApp
import com.pixelgo.data.ProjectSummary
import com.pixelgo.export.ExportJobs
import com.pixelgo.model.MAX_CANVAS_SIZE
import com.pixelgo.ui.common.ConfirmDialog
import com.pixelgo.ui.common.TextInputDialog
import com.pixelgo.ui.common.drawCheckerboard
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onOpen: (String) -> Unit, onPalettes: () -> Unit) {
    val app = LocalContext.current.applicationContext as PixelGoApp
    val repo = app.projects
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectSummary>?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    LifecycleResumeEffect(Unit) {
        refreshKey++
        onPauseOrDispose { }
    }
    LaunchedEffect(refreshKey) {
        projects = withContext(repo.dispatcher) { repo.list() }
    }

    fun runThenRefresh(block: () -> Unit) {
        scope.launch {
            withContext(repo.dispatcher) { block() }
            refreshKey++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PixelGo") },
                actions = {
                    IconButton(onClick = onPalettes) { Icon(Icons.Filled.Palette, "Palettes") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New") },
            )
        },
    ) { padding ->
        val list = projects
        if (list != null && list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No projects yet.\nTap New to start drawing.", style = MaterialTheme.typography.bodyLarge)
            }
        } else if (list != null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(list, key = { it.id }) { p ->
                    ProjectCard(
                        p,
                        onClick = { onOpen(p.id) },
                        onRename = { renaming = p },
                        onDuplicate = { runThenRefresh { repo.duplicate(p.id) } },
                        onDelete = { deleting = p },
                    )
                }
            }
        }
    }

    if (showNew) {
        NewProjectDialog(
            onDismiss = { showNew = false },
            onCreate = { name, w, h ->
                showNew = false
                scope.launch {
                    val id = withContext(repo.dispatcher) { repo.create(name, w, h) }
                    onOpen(id)
                }
            },
        )
    }
    renaming?.let { p ->
        TextInputDialog("Rename project", p.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            runThenRefresh { repo.rename(p.id, name) }
        }
    }
    deleting?.let { p ->
        ConfirmDialog(
            "Delete project?", "\"${p.name}\" will be permanently deleted.", "Delete",
            onDismiss = { deleting = null },
        ) {
            deleting = null
            runThenRefresh { repo.delete(p.id) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    p: ProjectSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, p.thumbnail, p.modified) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            BitmapFactory.decodeFile(p.thumbnail.absolutePath)?.asImageBitmap()
        }
    }
    Card(Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val s = min(size.width / p.width, size.height / p.height)
                val w = p.width * s
                val h = p.height * s
                val rect = Rect(Offset((size.width - w) / 2, (size.height - h) / 2), androidx.compose.ui.geometry.Size(w, h))
                drawCheckerboard(rect, 8.dp.toPx())
                thumb?.let {
                    drawImage(
                        it,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // Not while a video of this project is being exported (it reads the project's files).
                val free = !ExportJobs.isExporting(p.id)
                DropdownMenuItem(text = { Text("Rename") }, enabled = free, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, enabled = free, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete") }, enabled = free, onClick = { menu = false; onDelete() })
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            ExportJobs.state?.takeIf { it.projectId == p.id && !it.done }?.let {
                Text(
                    "Exporting video… ${(it.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "${p.width} × ${p.height}" +
                    if (p.frameCount > 1) "  ·  ${p.frameCount} frames  ·  ${"%.1f".format(p.durationMs / 1000.0)} s" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("Untitled") }
    var w by remember { mutableStateOf("32") }
    var h by remember { mutableStateOf("32") }
    val wi = w.toIntOrNull()
    val hi = h.toIntOrNull()
    val valid = name.isNotBlank() && wi != null && hi != null && wi in 1..MAX_CANVAS_SIZE && hi in 1..MAX_CANVAS_SIZE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        w, { w = it.filter(Char::isDigit).take(3) }, label = { Text("Width") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        h, { h = it.filter(Char::isDigit).take(3) }, label = { Text("Height") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "1 – $MAX_CANVAS_SIZE pixels each side",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(16, 32, 64, 128, 256).forEach { s ->
                        FilterChip(
                            selected = wi == s && hi == s,
                            onClick = { w = "$s"; h = "$s" },
                            label = { Text("$s") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onCreate(name.trim(), wi!!, hi!!) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

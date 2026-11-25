package com.pixelgo.ui.gallery

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pixelgo.PixelGoApp
import com.pixelgo.data.ProjectSummary
import com.pixelgo.editor.Names
import com.pixelgo.export.ExportJobs
import com.pixelgo.model.MAX_CANVAS_SIZE
import com.pixelgo.ui.common.CheckerQuietDark
import com.pixelgo.ui.common.CheckerQuietLight
import com.pixelgo.ui.common.ConfirmDialog
import com.pixelgo.ui.common.Hint
import com.pixelgo.ui.common.PgIconButton
import com.pixelgo.ui.common.PgSheet
import com.pixelgo.ui.common.PgTextButton
import com.pixelgo.ui.common.PrimaryButton
import com.pixelgo.ui.common.SectionLabel
import com.pixelgo.ui.common.TextInputDialog
import com.pixelgo.ui.common.drawCheckerboard
import com.pixelgo.ui.theme.Pg
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onOpen: (String) -> Unit, onPalettes: () -> Unit) {
    val app = LocalContext.current.applicationContext as PixelGoApp
    val repo = app.projects
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectSummary>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
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
        containerColor = Pg.Background,
        topBar = {
            TopAppBar(
                title = { Text("PixelGo") },
                actions = { PgIconButton(Icons.Outlined.Palette, "Palettes", onPalettes) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Pg.Background),
            )
        },
        floatingActionButton = {
            if (projects?.isNotEmpty() == true) {
                ExtendedFloatingActionButton(
                    onClick = { showNew = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New project") },
                )
            }
        },
    ) { padding ->
        val list = projects
        if (list != null && list.isEmpty()) {
            EmptyGallery(Modifier.fillMaxSize().padding(padding)) { showNew = true }
        } else if (list != null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
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
        NewProjectSheet(
            defaultName = Names.numbered("Sprite", projects.orEmpty().map { it.name }),
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
            onDismiss = { deleting = null }, destructive = true,
        ) {
            deleting = null
            runThenRefresh { repo.delete(p.id) }
        }
    }
}

/** No projects yet: one clear way to start. */
@Composable
private fun EmptyGallery(modifier: Modifier, onNew: () -> Unit) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.GridOn, null, tint = Pg.Text3, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("No projects yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Hint("Start a sprite, an animation or a tileset.")
        Spacer(Modifier.height(24.dp))
        PrimaryButton("New project", onClick = onNew, icon = Icons.Filled.Add)
    }
}

private fun editedLabel(modified: Long): String =
    "Edited " + DateUtils.getRelativeTimeSpanString(modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString().replaceFirstChar { it.lowercase(Locale.getDefault()) }

/** A project: its art filling the tile, the name, size and length, when it was edited, and a menu. */
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
    Card(
        Modifier.combinedClickable(onClickLabel = "Open", onLongClickLabel = "Project options", onClick = onClick, onLongClick = { menu = true }),
        colors = CardDefaults.cardColors(containerColor = Pg.Surface2),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Pg.Canvas)) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                // Whole-pixel scaling when the art is smaller than the tile, like the editor.
                var s = min(size.width / p.width, size.height / p.height)
                if (s >= 1f) s = floor(s)
                val w = p.width * s
                val h = p.height * s
                val rect = Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
                drawCheckerboard(rect, maxOf(2f, min(w, h) / 8), light = CheckerQuietLight, dark = CheckerQuietDark)
                thumb?.let {
                    drawImage(
                        it,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 10.dp)) {
                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                val export = ExportJobs.state?.takeIf { it.projectId == p.id && !it.done }
                Text(
                    if (export != null) "Exporting video… ${(export.progress * 100).toInt()}%"
                    else "${p.width} × ${p.height}" + if (p.frameCount > 1) "  ·  ${p.frameCount} frames" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (export != null) Pg.Accent else Pg.Text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    editedLabel(p.modified), style = MaterialTheme.typography.bodySmall, color = Pg.Text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // The same options as long-press, visible.
            Box {
                PgIconButton(Icons.Filled.MoreVert, "Options for ${p.name}", { menu = true }, iconSize = 20.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    // Not while a video of this project is being exported (it reads the project's files).
                    val free = !ExportJobs.isExporting(p.id)
                    DropdownMenuItem(text = { Text("Rename") }, enabled = free, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, enabled = free, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete", color = Pg.Danger) }, enabled = free, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private val Presets = listOf(16, 32, 64, 128, 256)

/** Name, then a size: the common squares as tiles, or any width and height. */
@Composable
private fun NewProjectSheet(defaultName: String, onDismiss: () -> Unit, onCreate: (String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var w by remember { mutableStateOf("32") }
    var h by remember { mutableStateOf("32") }
    val wi = w.toIntOrNull()
    val hi = h.toIntOrNull()
    val valid = name.isNotBlank() && wi != null && hi != null && wi in 1..MAX_CANVAS_SIZE && hi in 1..MAX_CANVAS_SIZE

    PgSheet(
        title = "New project",
        onDismiss = onDismiss,
        footer = {
            PgTextButton("Cancel", onClick = onDismiss)
            PrimaryButton("Create", onClick = { onCreate(name.trim(), wi!!, hi!!) }, enabled = valid)
        },
    ) {
        OutlinedTextField(
            name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel("Size")
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Presets.forEach { s ->
                val on = wi == s && hi == s
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) Pg.AccentWeak else Pg.Surface3)
                        .then(if (on) Modifier.border(2.dp, Pg.Accent, RoundedCornerShape(8.dp)) else Modifier)
                        .selectable(selected = on, onClick = { w = "$s"; h = "$s" }, role = Role.RadioButton)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // A square that grows with the size, so the tiles read at a glance.
                    val box = (10 + Presets.indexOf(s) * 5).dp
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(box).border(1.5.dp, if (on) Pg.Accent else Pg.Text2, RoundedCornerShape(2.dp)))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("$s", style = Pg.Data, color = if (on) Pg.Accent else Pg.Text1, textAlign = TextAlign.Center)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Hint("Any size from 1 to $MAX_CANVAS_SIZE pixels a side. Pixels stay sharp at every export size.")
    }
}

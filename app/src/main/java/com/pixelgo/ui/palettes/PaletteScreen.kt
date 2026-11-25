package com.pixelgo.ui.palettes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.pixelgo.PixelGoApp
import com.pixelgo.model.Palette
import com.pixelgo.ui.common.ColorPickerSheet
import com.pixelgo.ui.common.ColorSwatch
import com.pixelgo.ui.common.ConfirmDialog
import com.pixelgo.ui.common.Hint
import com.pixelgo.ui.common.PgIconButton
import com.pixelgo.ui.common.SecondaryButton
import com.pixelgo.ui.common.TextInputDialog
import com.pixelgo.ui.theme.Pg

/** What the color picker is editing. index == null means "add a new color". */
private data class ColorTarget(val paletteId: String, val index: Int?, val initial: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteScreen(onBack: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as PixelGoApp).palettes
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Palette?>(null) }
    var deleting by remember { mutableStateOf<Palette?>(null) }
    var picking by remember { mutableStateOf<ColorTarget?>(null) }

    Scaffold(
        containerColor = Pg.Background,
        topBar = {
            TopAppBar(
                title = { Text("Palettes") },
                navigationIcon = { PgIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Pg.Surface1),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New palette") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Hint("Tap a color to change it. Long-press and drag to move it.") }
            items(repo.palettes, key = { it.id }) { p ->
                PaletteCard(
                    p,
                    active = p.id == repo.activeId,
                    onUse = { repo.setActive(p.id) },
                    onRename = { renaming = p },
                    onDuplicate = { repo.duplicate(p.id) },
                    onDelete = { deleting = p },
                    onColorClick = { i -> picking = ColorTarget(p.id, i, p.colors[i]) },
                    onMove = { from, to -> repo.moveColor(p.id, from, to) },
                    onAddColor = { picking = ColorTarget(p.id, null, p.colors.lastOrNull() ?: 0xFF000000.toInt()) },
                )
            }
        }
    }

    if (creating) {
        TextInputDialog("New palette", "My palette", confirmLabel = "Create", onDismiss = { creating = false }) {
            creating = false
            repo.create(it)
        }
    }
    renaming?.let { p ->
        TextInputDialog("Rename palette", p.name, onDismiss = { renaming = null }) {
            renaming = null
            repo.rename(p.id, it)
        }
    }
    deleting?.let { p ->
        ConfirmDialog("Delete palette?", "\"${p.name}\" will be permanently deleted.", "Delete", onDismiss = { deleting = null }, destructive = true) {
            deleting = null
            repo.delete(p.id)
        }
    }
    picking?.let { t ->
        ColorPickerSheet(
            initial = t.initial,
            recent = repo.recentColors,
            title = if (t.index == null) "New color" else "Edit color",
            confirmLabel = if (t.index == null) "Add" else "Save",
            onDismiss = { picking = null },
            onConfirm = { c ->
                picking = null
                if (t.index == null) repo.addColor(t.paletteId, c) else repo.setColor(t.paletteId, t.index, c)
            },
            onDelete = t.index?.let { i -> { picking = null; repo.removeColor(t.paletteId, i) } },
        )
    }
}

@Composable
private fun PaletteCard(
    p: Palette,
    active: Boolean,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onColorClick: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onAddColor: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(
        border = if (active) BorderStroke(2.dp, Pg.Accent) else BorderStroke(1.dp, Pg.Outline),
        colors = CardDefaults.outlinedCardColors(containerColor = Pg.Surface2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${p.colors.size} colors" + if (active) "  ·  in use" else "",
                        style = MaterialTheme.typography.bodySmall, color = Pg.Text2,
                    )
                }
                if (!active) SecondaryButton("Use", onClick = onUse)
                Box {
                    PgIconButton(Icons.Filled.MoreVert, "Options for ${p.name}", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete", color = Pg.Danger) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            ReorderableSwatches(p.colors, onColorClick, onMove, onAddColor, Modifier.padding(top = 12.dp, end = 12.dp))
        }
    }
}

/**
 * The palette's colors. Tap one to edit it; long-press and drag it onto another place to move
 * it there (TalkBack: "Move left" / "Move right" actions).
 */
@Composable
private fun ReorderableSwatches(
    colors: List<Int>,
    onClick: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var dragging by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val currentColors by rememberUpdatedState(colors)
    val move by rememberUpdatedState(onMove)
    val click by rememberUpdatedState(onClick)
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        colors.forEachIndexed { i, c ->
            val lifted = dragging == i
            ColorSwatch(
                c,
                Modifier
                    .onGloballyPositioned { bounds[i] = it.boundsInParent() }
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        if (lifted) {
                            translationX = offset.x
                            translationY = offset.y
                            scaleX = 1.15f
                            scaleY = 1.15f
                            shadowElevation = 12f
                        }
                    }
                    .semantics {
                        customActions = listOfNotNull(
                            if (i > 0) CustomAccessibilityAction("Move left") { move(i, i - 1); true } else null,
                            if (i < currentColors.lastIndex) CustomAccessibilityAction("Move right") { move(i, i + 1); true } else null,
                        )
                    }
                    .pointerInput(i) { detectTapGestures(onLongPress = {}, onTap = { click(i) }) }
                    .pointerInput(i) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = i; offset = Offset.Zero },
                            onDragEnd = {
                                val from = dragging
                                val start = bounds[from]
                                if (start != null) {
                                    val center = start.center + offset
                                    val target = bounds.filterKeys { it <= currentColors.lastIndex }
                                        .minByOrNull { (it.value.center - center).getDistance() }?.key
                                    if (target != null && target != from) move(from, target)
                                }
                                dragging = -1
                                offset = Offset.Zero
                            },
                            onDragCancel = { dragging = -1; offset = Offset.Zero },
                        ) { change, amount ->
                            change.consume()
                            offset += amount
                        }
                    },
                size = 40.dp,
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Pg.Surface3)
                .clickable(onClickLabel = "Add color", onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, "Add color", tint = Pg.Text1) }
    }
}

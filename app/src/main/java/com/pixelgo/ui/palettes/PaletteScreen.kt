package com.pixelgo.ui.palettes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pixelgo.PixelGoApp
import com.pixelgo.data.PaletteRepository
import com.pixelgo.editor.ColorMath
import com.pixelgo.model.Palette
import com.pixelgo.ui.common.ColorPickerDialog
import com.pixelgo.ui.common.ColorSwatch
import com.pixelgo.ui.common.ConfirmDialog
import com.pixelgo.ui.common.PgTextButton
import com.pixelgo.ui.common.SecondaryButton
import com.pixelgo.ui.theme.Pg
import com.pixelgo.ui.common.TextInputDialog

/** What the color picker is currently editing. index == null means "add new color". */
private data class ColorTarget(val paletteId: String, val index: Int?, val initial: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteScreen(onBack: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as PixelGoApp).palettes
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Palette?>(null) }
    var deleting by remember { mutableStateOf<Palette?>(null) }
    var picking by remember { mutableStateOf<ColorTarget?>(null) }
    var colorOptions by remember { mutableStateOf<Pair<Palette, Int>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Palettes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
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
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(repo.palettes, key = { it.id }) { p ->
                PaletteCard(
                    p,
                    active = p.id == repo.activeId,
                    onUse = { repo.setActive(p.id) },
                    onRename = { renaming = p },
                    onDuplicate = { repo.duplicate(p.id) },
                    onDelete = { deleting = p },
                    onColorClick = { i -> colorOptions = p to i },
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
    colorOptions?.let { (p, i) ->
        ColorOptionsDialog(
            repo, p, i,
            onDismiss = { colorOptions = null },
            onEdit = { colorOptions = null; picking = ColorTarget(p.id, i, p.colors[i]) },
        )
    }
    picking?.let { t ->
        ColorPickerDialog(
            initial = t.initial,
            recent = repo.recentColors,
            onDismiss = { picking = null },
            onConfirm = { c ->
                picking = null
                if (t.index == null) repo.addColor(t.paletteId, c) else repo.setColor(t.paletteId, t.index, c)
            },
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
    onAddColor: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    OutlinedCard(
        border = border ?: CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.outlinedCardColors(containerColor = Pg.Surface2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${p.colors.size} colors" + if (active) " · in use" else "",
                        style = MaterialTheme.typography.bodySmall, color = Pg.Text2,
                    )
                }
                if (!active) SecondaryButton("Use", onClick = onUse)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Palette options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                p.colors.forEachIndexed { i, c ->
                    ColorSwatch(c, Modifier.clickable { onColorClick(i) }, size = 38.dp)
                }
                Card(
                    onClick = onAddColor,
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, "Add color")
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorOptionsDialog(
    repo: PaletteRepository,
    p: Palette,
    index: Int,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val color = p.colors.getOrNull(index) ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { ColorSwatch(color, size = 48.dp) },
        title = { Text(ColorMath.toHex(color)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onEdit, label = { Text("Edit") })
                AssistChip(
                    enabled = index > 0,
                    onClick = { repo.moveColor(p.id, index, index - 1); onDismiss() },
                    label = { Text("Move left") },
                )
                AssistChip(
                    enabled = index < p.colors.lastIndex,
                    onClick = { repo.moveColor(p.id, index, index + 1); onDismiss() },
                    label = { Text("Move right") },
                )
                AssistChip(onClick = { repo.removeColor(p.id, index); onDismiss() }, label = { Text("Delete") })
            }
        },
        confirmButton = { PgTextButton("Close", onClick = onDismiss) },
    )
}

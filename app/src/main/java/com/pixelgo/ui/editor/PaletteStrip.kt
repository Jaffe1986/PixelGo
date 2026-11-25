package com.pixelgo.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelgo.data.PaletteRepository
import com.pixelgo.editor.ColorMath
import com.pixelgo.ui.common.ColorSwatch
import com.pixelgo.ui.common.PgIconButton
import com.pixelgo.ui.theme.Pg

/** Portrait: the current color, then the active palette's swatches, then palette and add. */
@Composable
fun PaletteStrip(
    vm: EditorViewModel,
    palettes: PaletteRepository,
    onEditCurrentColor: () -> Unit,
    onManagePalettes: () -> Unit,
) {
    val palette = palettes.active
    Row(
        Modifier.fillMaxWidth().height(Pg.ColorRowHeight).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CurrentColor(vm, onEditCurrentColor)
        VerticalDivider(Modifier.height(28.dp).padding(horizontal = 10.dp), color = Pg.Outline)
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(palette.colors) { i, c -> PaletteSwatch(vm, palettes, i, c) }
        }
        PaletteMenuButton(palettes, onManagePalettes)
        PgIconButton(Icons.Filled.Add, "Add current color to palette", { palettes.addColor(palette.id, vm.color) }, iconSize = 22.dp)
    }
}

/** Landscape: the same, as a column to the right of the canvas. */
@Composable
fun PaletteRail(
    vm: EditorViewModel,
    palettes: PaletteRepository,
    onEditCurrentColor: () -> Unit,
    onManagePalettes: () -> Unit,
) {
    val palette = palettes.active
    Column(Modifier.fillMaxSize().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CurrentColor(vm, onEditCurrentColor)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = Pg.Outline)
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(palette.colors) { i, c ->
                Box(contentAlignment = Alignment.Center) { PaletteSwatch(vm, palettes, i, c) }
            }
        }
        Row {
            PaletteMenuButton(palettes, onManagePalettes)
            PgIconButton(Icons.Filled.Add, "Add current color to palette", { palettes.addColor(palette.id, vm.color) }, iconSize = 22.dp)
        }
    }
}

/** The color you're drawing with; tap to change it. */
@Composable
private fun CurrentColor(vm: EditorViewModel, onEdit: () -> Unit) {
    ColorSwatch(
        vm.color,
        Modifier.clickable(onClickLabel = "Change color", onClick = onEdit),
        size = Pg.CurrentColor,
        label = "Current color ${ColorMath.toHex(vm.color)}",
    )
}

/** One palette color: tap to use it, long-press to remove it from the palette. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteSwatch(vm: EditorViewModel, palettes: PaletteRepository, index: Int, color: Int) {
    val palette = palettes.active
    var menu by remember { mutableStateOf(false) }
    Box {
        ColorSwatch(
            color,
            Modifier.combinedClickable(
                onClickLabel = "Use color",
                onLongClickLabel = "Color options",
                onLongClick = { menu = true },
                onClick = { vm.pickColor(color) },
            ),
            selected = color == vm.color && vm.tool != Tool.Eraser,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Remove from \"${palette.name}\"") },
                onClick = { menu = false; palettes.removeColor(palette.id, index) },
            )
        }
    }
}

/** Switch palettes, or manage them. */
@Composable
private fun PaletteMenuButton(palettes: PaletteRepository, onManagePalettes: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val palette = palettes.active
    Box {
        PgIconButton(Icons.Outlined.Palette, "Choose palette (${palette.name})", { menu = true }, iconSize = 22.dp)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            palettes.palettes.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    leadingIcon = { if (p.id == palette.id) Icon(Icons.Filled.Check, null) else Box(Modifier.padding(12.dp)) },
                    onClick = { palettes.setActive(p.id); menu = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Manage palettes…") }, onClick = { menu = false; onManagePalettes() })
        }
    }
}

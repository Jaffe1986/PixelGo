package com.pixelgo.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelgo.data.PaletteRepository
import com.pixelgo.ui.common.ColorSwatch

/** Current color + the active palette's swatches. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaletteStrip(
    vm: EditorViewModel,
    palettes: PaletteRepository,
    onEditCurrentColor: () -> Unit,
    onManagePalettes: () -> Unit,
) {
    val palette = palettes.active
    var paletteMenu by remember { mutableStateOf(false) }
    var swatchMenu by remember { mutableIntStateOf(-1) }

    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorSwatch(vm.color, Modifier.clickable(onClick = onEditCurrentColor), size = 46.dp)
        Box {
            IconButton(onClick = { paletteMenu = true }) { Icon(Icons.Filled.Palette, "Choose palette") }
            DropdownMenu(expanded = paletteMenu, onDismissRequest = { paletteMenu = false }) {
                palettes.palettes.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        leadingIcon = { if (p.id == palette.id) Icon(Icons.Filled.Check, null) },
                        onClick = { palettes.setActive(p.id); paletteMenu = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Manage palettes…") }, onClick = { paletteMenu = false; onManagePalettes() })
            }
        }
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(palette.colors) { i, c ->
                Box {
                    ColorSwatch(
                        c,
                        Modifier.combinedClickable(onClick = { vm.pickColor(c) }, onLongClick = { swatchMenu = i }),
                        selected = c == vm.color && vm.tool != Tool.Eraser,
                    )
                    DropdownMenu(expanded = swatchMenu == i, onDismissRequest = { swatchMenu = -1 }) {
                        DropdownMenuItem(
                            text = { Text("Remove from \"${palette.name}\"") },
                            onClick = { swatchMenu = -1; palettes.removeColor(palette.id, i) },
                        )
                    }
                }
            }
        }
        IconButton(onClick = { palettes.addColor(palette.id, vm.color) }) {
            Icon(Icons.Filled.Add, "Add current color to palette")
        }
    }
}

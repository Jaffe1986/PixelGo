package com.pixelgo.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Actions for the Select tool, shown above the palette strip. */
@Composable
fun SelectionBar(vm: EditorViewModel) {
    if (vm.isFloating) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Drag to position",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = vm::cancelFloating) {
                Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = vm::commitFloating) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Done")
            }
        }
        return
    }

    val sel = vm.hasSelection
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BarButton(Icons.Filled.Crop, "Box", selected = vm.selectMode == SelectMode.Rect) {
            vm.selectMode = SelectMode.Rect
        }
        BarButton(Icons.Filled.Brush, "Paint", selected = vm.selectMode == SelectMode.Paint) {
            vm.selectMode = SelectMode.Paint
        }
        VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
        BarButton(Icons.Filled.ContentCopy, "Copy", enabled = sel, onClick = vm::copySelection)
        BarButton(Icons.Filled.ContentCut, "Cut", enabled = sel, onClick = vm::cutSelection)
        BarButton(Icons.Filled.OpenWith, "Move", enabled = sel, onClick = vm::moveSelection)
        BarButton(Icons.Filled.ContentPaste, "Paste", enabled = PixelClipboard.clip != null, onClick = vm::paste)
        BarButton(Icons.Filled.Delete, "Delete", enabled = sel, onClick = vm::deleteSelection)
        VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
        BarButton(Icons.Filled.SelectAll, "All", onClick = vm::selectAll)
        BarButton(Icons.Filled.Deselect, "None", enabled = sel, onClick = vm::deselect)
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
        Text(label, color = fg, fontSize = 10.sp, maxLines = 1)
    }
}

package com.pixelgo.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelgo.ui.common.ActionBar
import com.pixelgo.ui.common.BarAction
import com.pixelgo.ui.common.PrimaryButton
import com.pixelgo.ui.common.SecondaryButton
import com.pixelgo.ui.common.Segmented
import com.pixelgo.ui.theme.Pg

/** Actions for the Select tool, shown above the timeline. */
@Composable
fun SelectionBar(vm: EditorViewModel) {
    if (vm.isFloating) {
        // Moved or pasted pixels float until they're dropped (Done) or put back (Cancel).
        Row(
            Modifier.fillMaxWidth().height(Pg.ActionBarHeight).background(Pg.Surface2).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Drag to position", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            SecondaryButton("Cancel", onClick = vm::cancelFloating, icon = Icons.Filled.Close)
            PrimaryButton("Done", onClick = vm::commitFloating, icon = Icons.Filled.Check)
        }
        return
    }

    val sel = vm.hasSelection
    ActionBar(
        start = {
            Segmented(
                listOf(SelectMode.Rect, SelectMode.Paint), vm.selectMode, { vm.selectMode = it },
                label = { if (it == SelectMode.Rect) "Box" else "Paint" },
                modifier = Modifier.padding(start = 4.dp, end = 4.dp).width(112.dp),
            )
        },
        actions = listOf(
            BarAction("Move", Icons.Filled.OpenWith, enabled = sel, onClick = vm::moveSelection),
            BarAction("Copy", Icons.Filled.ContentCopy, enabled = sel, onClick = vm::copySelection),
            BarAction("Paste", Icons.Filled.ContentPaste, enabled = PixelClipboard.clip != null, onClick = vm::paste),
            BarAction("Delete", Icons.Filled.Delete, enabled = sel, destructive = true, onClick = vm::deleteSelection),
            BarAction("Cut", Icons.Filled.ContentCut, enabled = sel, onClick = vm::cutSelection),
            BarAction("Select all", Icons.Filled.SelectAll, onClick = vm::selectAll),
            BarAction("Select none", Icons.Filled.Deselect, enabled = sel, onClick = vm::deselect),
        ),
    )
}

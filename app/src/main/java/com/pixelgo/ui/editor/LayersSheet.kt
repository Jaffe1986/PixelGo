package com.pixelgo.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.pixelgo.model.Layer
import com.pixelgo.ui.common.ConfirmDialog
import com.pixelgo.ui.common.TextInputDialog
import com.pixelgo.ui.common.drawCheckerboard
import kotlin.math.min
import kotlin.math.roundToInt

private val RowHeight = 60.dp

/**
 * Layers list, top layer first (the order they stack on screen). Drag the handle to reorder,
 * or use the arrows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val engine = vm.engine ?: return
    vm.structureTick // re-read layers when they change
    val project = engine.project
    val layers = project.layers
    val n = layers.size
    val active = project.activeLayerIndex

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmMerge by remember { mutableStateOf(false) }
    var dragId by remember { mutableLongStateOf(-1L) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::addLayer, enabled = engine.canAddLayer) { Icon(Icons.Filled.Add, "Add layer") }
                IconButton(onClick = vm::duplicateLayer, enabled = engine.canAddLayer) { Icon(Icons.Filled.ContentCopy, "Duplicate layer") }
                IconButton(onClick = { confirmMerge = true }, enabled = active > 0) { Icon(Icons.AutoMirrored.Filled.MergeType, "Merge down") }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.DriveFileRenameOutline, "Rename layer") }
                IconButton(onClick = { confirmDelete = true }, enabled = n > 1) { Icon(Icons.Filled.Delete, "Delete layer") }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Top of the list is drawn on top. Drag ≡ to reorder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            }
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                for (display in 0 until n) {
                    val index = n - 1 - display
                    val layer = layers[index]
                    key(layer.id) {
                        val dragging = dragId == layer.id
                        LayerRow(
                            vm = vm,
                            layer = layer,
                            selected = index == active,
                            canMoveUp = index < n - 1,
                            canMoveDown = index > 0,
                            onSelect = { vm.selectLayer(project.indexOfLayer(layer.id)) },
                            onToggleVisible = { vm.setLayerVisible(project.indexOfLayer(layer.id), !layer.visible) },
                            onMoveUp = { val i = project.indexOfLayer(layer.id); vm.moveLayer(i, i + 1) },
                            onMoveDown = { val i = project.indexOfLayer(layer.id); vm.moveLayer(i, i - 1) },
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (dragging) dragOffset else 0f
                                    shadowElevation = if (dragging) 12f else 0f
                                },
                            handleModifier = Modifier.pointerInput(layer.id) {
                                detectDragGestures(
                                    onDragStart = { dragId = layer.id; dragOffset = 0f },
                                    onDragEnd = {
                                        val from = project.indexOfLayer(layer.id)
                                        val count = project.layers.size
                                        val steps = (dragOffset / rowPx).roundToInt()
                                        val toDisplay = (count - 1 - from + steps).coerceIn(0, count - 1)
                                        dragId = -1L
                                        dragOffset = 0f
                                        if (from >= 0) vm.moveLayer(from, count - 1 - toDisplay)
                                    },
                                    onDragCancel = { dragId = -1L; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        val layer = project.activeLayer
        if (layer != null) {
            TextInputDialog("Rename layer", layer.name, onDismiss = { renaming = false }) {
                renaming = false
                vm.renameLayer(project.activeLayerIndex, it)
            }
        }
    }
    if (confirmMerge) {
        val upper = project.activeLayer
        val lower = layers.getOrNull(project.activeLayerIndex - 1)
        if (upper != null && lower != null) {
            ConfirmDialog(
                "Merge layers?",
                "\"${upper.name}\" will be merged down into \"${lower.name}\" and become one layer. " +
                    "You can undo this with ↶.",
                "Merge",
                onDismiss = { confirmMerge = false },
            ) {
                confirmMerge = false
                vm.mergeDown()
            }
        } else {
            confirmMerge = false
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete layer?", "\"${project.activeLayer?.name}\" will be removed (you can undo this).", "Delete",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            vm.deleteLayer()
        }
    }
}

@Composable
private fun LayerRow(
    vm: EditorViewModel,
    layer: Layer,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier,
    handleModifier: Modifier,
) {
    val thumb = remember(layer, vm.renderTick, vm.structureTick) {
        Bitmap.createBitmap(layer.pixels, vm.width, vm.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(RowHeight)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.DragHandle, "Drag to reorder", handleModifier.size(40.dp).padding(8.dp))
        Canvas(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp))) {
            val s = min(size.width / vm.width, size.height / vm.height)
            val w = vm.width * s
            val h = vm.height * s
            val rect = Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
            drawCheckerboard(rect, 5.dp.toPx())
            drawImage(
                thumb,
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.None,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            layer.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = if (layer.visible) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        IconButton(onClick = onToggleVisible) {
            Icon(
                if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                if (layer.visible) "Hide layer" else "Show layer",
            )
        }
        Column(verticalArrangement = Arrangement.Center) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, "Move down")
            }
        }
    }
}

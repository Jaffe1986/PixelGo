package com.pixelgo.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelgo.ui.common.ConfirmDialog
import com.pixelgo.ui.common.drawCheckerboard
import kotlin.math.min
import kotlin.math.roundToInt

private val Cell = 46.dp

/** Animation timeline: play, frame thumbnails, add/duplicate, onion skin, speed. */
@Composable
fun Timeline(vm: EditorViewModel) {
    vm.structureTick // refresh when frames change
    val frames = vm.frames()
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuFor by remember { mutableIntStateOf(-1) }

    LaunchedEffect(vm.frameIndex, vm.frameCount) {
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (vm.frameIndex !in visible.drop(1).dropLast(1)) listState.animateScrollToItem(maxOf(0, vm.frameIndex - 1))
    }

    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallIconButton(
            if (vm.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            if (vm.isPlaying) "Stop" else "Play",
            enabled = vm.frameCount > 1,
            onClick = vm::togglePlay,
        )
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Read the frame count / structure state *inside* the lazy content so the list
            // re-reads it: the frames list itself is mutated in place and never changes identity.
            val count = vm.frameCount
            vm.structureTick
            items(count) { i ->
                vm.structureTick
                if (i >= frames.size) return@items
                Box {
                    FrameCell(vm, i, frames[i].hold, onLongPress = { menuFor = i })
                    DropdownMenu(expanded = menuFor == i, onDismissRequest = { menuFor = -1 }) {
                        DropdownMenuItem(
                            text = { Text("Duplicate") }, enabled = vm.canAddFrame,
                            onClick = { menuFor = -1; vm.selectFrame(i); vm.duplicateFrame() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move left") }, enabled = i > 0,
                            onClick = { menuFor = -1; vm.moveFrame(i, i - 1) },
                        )
                        DropdownMenuItem(
                            text = { Text("Move right") }, enabled = i < frames.lastIndex,
                            onClick = { menuFor = -1; vm.moveFrame(i, i + 1) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") }, enabled = frames.size > 1,
                            onClick = { menuFor = -1; vm.selectFrame(i); confirmDelete = true },
                        )
                        HorizontalDivider()
                        for (h in 1..4) {
                            DropdownMenuItem(
                                text = { Text(if (h == 1) "Normal length" else "Hold ×$h") },
                                leadingIcon = { if (frames[i].hold == h) Icon(Icons.Filled.Check, null) },
                                onClick = { menuFor = -1; vm.setFrameHold(i, h) },
                            )
                        }
                    }
                }
            }
        }
        SmallIconButton(Icons.Filled.Add, "Add blank frame", enabled = vm.canAddFrame, onClick = vm::addFrame)
        SmallIconButton(Icons.Filled.ContentCopy, "Duplicate frame", enabled = vm.canAddFrame, onClick = vm::duplicateFrame)
        SmallIconButton(
            Icons.Filled.AutoAwesomeMotion, "Onion skin",
            selected = vm.onionSkin, enabled = vm.frameCount > 1, onClick = vm::toggleOnionSkin,
        )
        TextButton(
            onClick = { showSettings = true },
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) { Text("${vm.fps}fps", fontSize = 12.sp) }
    }

    if (showSettings) AnimationSettingsDialog(vm, onDismiss = { showSettings = false })
    if (confirmDelete) {
        ConfirmDialog(
            "Delete frame?", "Frame ${vm.frameIndex + 1} will be removed (you can undo this).", "Delete",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            vm.deleteFrame()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrameCell(vm: EditorViewModel, index: Int, hold: Int, onLongPress: () -> Unit) {
    val current = index == vm.frameIndex
    val highlighted = if (vm.isPlaying) index == vm.playIndex else current
    // The current frame draws the live editing bitmap; others use a cached composite.
    val cached: ImageBitmap? = remember(index, vm.structureTick, current) {
        if (current) null else vm.frameImage(index)
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(Cell)
            .clip(shape)
            .border(
                if (highlighted) 3.dp else 1.dp,
                if (highlighted) MaterialTheme.colorScheme.primary else Color(0x55FFFFFF),
                shape,
            )
            .combinedClickable(onClick = { vm.selectFrame(index) }, onLongClick = onLongPress)
    ) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            vm.renderTick
            val img = if (current) vm.image else cached
            val s = min(size.width / vm.width, size.height / vm.height)
            val w = vm.width * s
            val h = vm.height * s
            val rect = Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
            drawCheckerboard(rect, 4.dp.toPx())
            if (img != null) {
                drawImage(
                    img,
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.None,
                )
            }
        }
        Text(
            "${index + 1}",
            fontSize = 9.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(3.dp)
                .background(Color(0xAA000000), RoundedCornerShape(3.dp))
                .padding(horizontal = 3.dp),
        )
        if (hold > 1) {
            Text(
                "×$hold",
                fontSize = 9.sp,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = if (selected) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) { Icon(icon, label, Modifier.size(22.dp)) }
}

@Composable
private fun AnimationSettingsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var fps by remember { mutableFloatStateOf(vm.fps.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Animation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Speed: ${fps.roundToInt()} frames per second", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = fps,
                    onValueChange = { fps = it },
                    onValueChangeFinished = { vm.changeFps(fps.roundToInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
                Text(
                    "Each frame shows for ${(1000f / fps.roundToInt()).roundToInt()} ms. " +
                        "Long-press a frame to make it hold longer (×2, ×3, ×4).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Onion skin")
                        Text(
                            "Show the previous frame (red) and next frame (blue) faintly while drawing.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = vm.onionSkin, onCheckedChange = { vm.toggleOnionSkin() })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.changeFps(fps.roundToInt()); onDismiss() }) { Text("Done") }
        },
    )
}

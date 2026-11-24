package com.pixelgo.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pixelgo.model.MAX_FPS
import com.pixelgo.model.MAX_HOLD
import com.pixelgo.model.MIN_FPS
import kotlin.math.roundToInt

/** How long a frame stays on screen, in ticks (1 tick = 1/fps second). */
@Composable
fun HoldDialog(
    initial: Int,
    fps: Int,
    title: String = "Frame hold",
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1..MAX_HOLD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How many ticks the frame stays on screen (1 tick = 1/$fps s).")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(3) },
                    label = { Text("Hold (1–$MAX_HOLD)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 4, 6, 8, 12, 24).forEach { h ->
                        FilterChip(selected = value == h, onClick = { text = "$h" }, label = { Text("$h") })
                    }
                }
                if (value != null && valid) {
                    Text("= ${value * 1000L / fps} ms", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { if (value != null) onConfirm(value) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AnimationSettingsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
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
                    valueRange = MIN_FPS.toFloat()..MAX_FPS.toFloat(),
                    steps = MAX_FPS - MIN_FPS - 1,
                )
                Text(
                    "Each frame shows for ${(1000f / fps.roundToInt()).roundToInt()} ms. " +
                        "Long-press a frame to hold it longer.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SettingSwitch(
                    "Duplicates go to the end",
                    "The duplicate button adds the copy after the last frame instead of right after the current one.",
                    vm.duplicateToEnd,
                ) { vm.updateDuplicateToEnd(it) }
                SettingSwitch(
                    "Onion skin",
                    "Show the previous frame (red) and next frame (blue) faintly while drawing.",
                    vm.onionSkin,
                ) { vm.toggleOnionSkin() }
                SettingSwitch(
                    "Loop animation until the sound ends",
                    "When the sound is longer than the animation, keep repeating the animation " +
                        "(in playback and video export) instead of stopping.",
                    vm.loopToAudio,
                ) { vm.setLoopToAudio(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.changeFps(fps.roundToInt()); onDismiss() }) { Text("Done") }
        },
    )
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

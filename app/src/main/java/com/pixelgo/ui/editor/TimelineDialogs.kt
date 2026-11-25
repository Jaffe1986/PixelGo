package com.pixelgo.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pixelgo.model.MAX_FPS
import com.pixelgo.model.MAX_HOLD
import com.pixelgo.model.MIN_FPS
import com.pixelgo.ui.common.Hint
import com.pixelgo.ui.common.PgTextButton
import com.pixelgo.ui.common.PrimaryButton
import com.pixelgo.ui.common.SliderRow
import com.pixelgo.ui.common.SwitchRow
import com.pixelgo.ui.theme.Pg
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
                    Text("= ${value * 1000L / fps} ms", style = Pg.Data, color = Pg.Text2)
                }
            }
        },
        confirmButton = {
            PrimaryButton("OK", onClick = { if (value != null) onConfirm(value) }, enabled = valid)
        },
        dismissButton = { PgTextButton("Cancel", onClick = onDismiss) },
    )
}

/** Speed, where duplicates go, and looping under a longer sound. (Onion skin is on the timeline.) */
@Composable
fun AnimationSettingsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var fps by remember { mutableFloatStateOf(vm.fps.toFloat()) }
    AlertDialog(
        onDismissRequest = { vm.changeFps(fps.roundToInt()); onDismiss() },
        title = { Text("Animation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    SliderRow(
                        "Speed", "${fps.roundToInt()} fps", fps,
                        onValueChange = { fps = it.roundToInt().toFloat() },
                        valueRange = MIN_FPS.toFloat()..MAX_FPS.toFloat(),
                        onValueChangeFinished = { vm.changeFps(fps.roundToInt()) },
                        step = 1f,
                    )
                    Hint("Each frame shows for ${(1000f / fps.roundToInt()).roundToInt()} ms. To hold one longer, use Hold.")
                }
                SwitchRow(
                    "Duplicates go to the end", vm.duplicateToEnd, { vm.updateDuplicateToEnd(it) },
                    detail = "The duplicate button adds the copy after the last frame instead of right after the current one.",
                )
                SwitchRow(
                    "Loop the animation until the sound ends", vm.loopToAudio, { vm.setLoopToAudio(it) },
                    detail = "When the sound is longer than the animation, keep repeating the animation " +
                        "(in playback and video export) instead of stopping.",
                )
            }
        },
        confirmButton = {
            PrimaryButton("Done", onClick = { vm.changeFps(fps.roundToInt()); onDismiss() })
        },
    )
}

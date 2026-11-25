package com.pixelgo.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelgo.editor.audiofx.EffectSettings
import com.pixelgo.ui.common.Hint
import com.pixelgo.ui.common.PgSheet
import com.pixelgo.ui.common.PgTextButton
import com.pixelgo.ui.common.PrimaryButton
import com.pixelgo.ui.common.SecondaryButton
import com.pixelgo.ui.common.SectionLabel
import com.pixelgo.ui.common.SliderRow
import com.pixelgo.ui.common.SwitchRow
import java.util.Locale
import kotlin.math.roundToInt

/** What a sheet works on: one clip's name, or the selected section's length. */
private fun targetName(vm: EditorViewModel, clipId: Long?): String =
    if (clipId != null) vm.clips().firstOrNull { it.id == clipId }?.let { vm.assetName(it.assetId) } ?: "Clip"
    else vm.soundSel?.let { "Selection (%.2f s)".format(Locale.US, (it.toMs - it.fromMs) / 1000.0) } ?: "Selection"

/**
 * Effects for one clip ([clipId]) or, when it's null, the selected section. Applying renders a
 * new sound (one undo step); the original stays until it's no longer needed for undo.
 */
@Composable
fun EffectsSheet(vm: EditorViewModel, clipId: Long?, onDismiss: () -> Unit) {
    var s by remember { mutableStateOf(EffectSettings()) }
    PgSheet(
        title = "Effects",
        subtitle = targetName(vm, clipId),
        onDismiss = onDismiss,
        footer = {
            PgTextButton("Cancel", onClick = onDismiss)
            PrimaryButton("Apply", onClick = { vm.applyEffects(clipId, s); onDismiss() }, enabled = !s.isNeutral)
        },
    ) {
        SwitchRow("Reverse", s.reverse, { s = s.copy(reverse = it) })

        SectionLabel("Speed and pitch")
        SliderRow(
            "Speed", "${(s.speed * 100).roundToInt()}%", s.speed,
            onValueChange = { s = s.copy(speed = (it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2f, step = 0.05f,
        )
        SwitchRow("Tape style", s.tapeStyle, { s = s.copy(tapeStyle = it) }, detail = "Speed changes the pitch too, like a tape.")
        if (!s.tapeStyle) {
            SliderRow(
                "Pitch", "%+d semitones".format(s.pitch.roundToInt()), s.pitch,
                onValueChange = { s = s.copy(pitch = it.roundToInt().toFloat()) }, valueRange = -12f..12f, step = 1f,
            )
        }
        Hint("Changing speed changes the length; the sound after it doesn't move.")

        SectionLabel("Tone")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Low cut (removes rumble and wind)", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (hz in listOf(0, 80, 120, 200)) {
                    FilterChip(
                        selected = s.lowCutHz == hz, onClick = { s = s.copy(lowCutHz = hz) },
                        label = { Text(if (hz == 0) "Off" else "$hz Hz") },
                    )
                }
            }
        }
        DbSlider("Bass", s.bassDb) { s = s.copy(bassDb = it) }
        DbSlider("Mid", s.midDb) { s = s.copy(midDb = it) }
        DbSlider("Treble", s.trebleDb) { s = s.copy(trebleDb = it) }

        SwitchRow(
            "Noise gate", s.gateDb != null, { s = s.copy(gateDb = if (it) -40f else null) },
            detail = "Silences the quiet parts between words.",
        )
        s.gateDb?.let { g ->
            SliderRow(
                "Silence below", "${g.roundToInt()} dB", g,
                onValueChange = { s = s.copy(gateDb = it.roundToInt().toFloat()) }, valueRange = -70f..-20f, step = 1f,
            )
        }
    }
}

@Composable
private fun DbSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    SliderRow(
        label, "%+d dB".format(value.roundToInt()), value,
        onValueChange = { onChange(it.roundToInt().toFloat()) }, valueRange = -12f..12f, step = 1f,
    )
}

/**
 * AI noise removal (RNNoise, on the phone) for one clip ([clipId]) or the selected section.
 * Before / After play up to 5 s so the strength can be judged; Apply makes a new sound.
 */
@Composable
fun CleanupSheet(vm: EditorViewModel, clipId: Long?, onDismiss: () -> Unit) {
    var strength by remember { mutableFloatStateOf(0.8f) }
    DisposableEffect(Unit) { onDispose { vm.stopCleanupPreview() } }
    val playing = vm.cleanupPreview
    PgSheet(
        title = "Clean up noise (AI)",
        subtitle = targetName(vm, clipId),
        onDismiss = onDismiss,
        footer = {
            PgTextButton("Cancel", onClick = onDismiss)
            PrimaryButton("Apply", onClick = {
                vm.stopCleanupPreview()
                vm.applyEffects(clipId, EffectSettings(denoise = strength))
                onDismiss()
            })
        },
    ) {
        Text(
            "Removes background noise such as fans, hum, traffic and hiss, and keeps voices. " +
                "It runs on this phone; nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SliderRow(
            "Strength", "${(strength * 100).roundToInt()}%", strength,
            onValueChange = { strength = (it * 20).roundToInt() / 20f },
            valueRange = 0.1f..1f,
            onValueChangeFinished = { if (vm.cleanupPreview == true) vm.previewCleanup(clipId, strength, cleaned = true) },
            step = 0.05f,
        )
        Hint("Lower keeps a little of the background, which can sound more natural.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                if (playing == false) "Stop" else "Before",
                onClick = { if (playing == false) vm.stopCleanupPreview() else vm.previewCleanup(clipId, strength, cleaned = false) },
                modifier = Modifier.weight(1f),
                icon = if (playing == false) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            )
            SecondaryButton(
                when {
                    vm.cleanupPreparing -> "Working…"
                    playing == true -> "Stop"
                    else -> "After"
                },
                onClick = { if (playing == true) vm.stopCleanupPreview() else vm.previewCleanup(clipId, strength, cleaned = true) },
                modifier = Modifier.weight(1f),
                icon = if (playing == true) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            )
        }
        Hint("Before and After play up to 5 s from the cursor (or the start).")
    }
}

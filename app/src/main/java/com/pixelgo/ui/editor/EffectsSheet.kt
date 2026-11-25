package com.pixelgo.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelgo.editor.audiofx.EffectSettings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Effects for one clip ([clipId]) or, when it's null, the selected section. Applying renders a
 * new sound (one undo step); the original stays until it's no longer needed for undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsSheet(vm: EditorViewModel, clipId: Long?, onDismiss: () -> Unit) {
    var s by remember { mutableStateOf(EffectSettings()) }
    val what = if (clipId != null) vm.clips().firstOrNull { it.id == clipId }?.let { vm.assetName(it.assetId) } ?: "Clip"
    else vm.soundSel?.let { "Selection (%.2f s)".format(Locale.US, (it.toMs - it.fromMs) / 1000.0) } ?: "Selection"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Effects", style = MaterialTheme.typography.titleLarge)
            Text(what, style = MaterialTheme.typography.bodyMedium)

            SwitchRow("Reverse", s.reverse) { s = s.copy(reverse = it) }

            Text("Speed ${(s.speed * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value = s.speed, onValueChange = { s = s.copy(speed = (it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2f)
            SwitchRow("Tape style (speed changes pitch too)", s.tapeStyle) { s = s.copy(tapeStyle = it) }
            if (!s.tapeStyle) {
                Text("Pitch ${"%+d".format(s.pitch.roundToInt())} semitones", style = MaterialTheme.typography.labelLarge)
                Slider(value = s.pitch, onValueChange = { s = s.copy(pitch = it.roundToInt().toFloat()) }, valueRange = -12f..12f, steps = 23)
            }

            Text("Low cut (removes rumble and wind)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (hz in listOf(0, 80, 120, 200)) {
                    FilterChip(
                        selected = s.lowCutHz == hz, onClick = { s = s.copy(lowCutHz = hz) },
                        label = { Text(if (hz == 0) "Off" else "$hz Hz") },
                    )
                }
            }
            DbSlider("Bass", s.bassDb) { s = s.copy(bassDb = it) }
            DbSlider("Mid", s.midDb) { s = s.copy(midDb = it) }
            DbSlider("Treble", s.trebleDb) { s = s.copy(trebleDb = it) }

            SwitchRow("Noise gate (silence between words)", s.gateDb != null) { s = s.copy(gateDb = if (it) -40f else null) }
            s.gateDb?.let { g ->
                Text("Silence below ${g.roundToInt()} dB", style = MaterialTheme.typography.labelLarge)
                Slider(value = g, onValueChange = { s = s.copy(gateDb = it.roundToInt().toFloat()) }, valueRange = -70f..-20f)
            }

            Text(
                "Changing speed changes the length; the sound after it doesn't move.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { vm.applyEffects(clipId, s); onDismiss() }, enabled = !s.isNeutral) { Text("Apply") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DbSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Text("$label ${"%+d".format(value.roundToInt())} dB", style = MaterialTheme.typography.labelLarge)
    Slider(value = value, onValueChange = { onChange(it.roundToInt().toFloat()) }, valueRange = -12f..12f, steps = 23)
}

/**
 * AI noise removal (RNNoise, on the phone) for one clip ([clipId]) or the selected section.
 * Before / After play up to 5 s so the strength can be judged; Apply makes a new sound.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupSheet(vm: EditorViewModel, clipId: Long?, onDismiss: () -> Unit) {
    var strength by remember { mutableFloatStateOf(0.8f) }
    val what = if (clipId != null) vm.clips().firstOrNull { it.id == clipId }?.let { vm.assetName(it.assetId) } ?: "Clip"
    else vm.soundSel?.let { "Selection (%.2f s)".format(Locale.US, (it.toMs - it.fromMs) / 1000.0) } ?: "Selection"
    DisposableEffect(Unit) { onDispose { vm.stopCleanupPreview() } }
    val playing = vm.cleanupPreview
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Clean up noise (AI)", style = MaterialTheme.typography.titleLarge)
            Text(what, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Removes background noise such as fans, hum, traffic and hiss, and keeps voices. " +
                    "It runs on this phone; nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Strength ${(strength * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = strength,
                onValueChange = { strength = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { if (vm.cleanupPreview == true) vm.previewCleanup(clipId, strength, cleaned = true) },
                valueRange = 0.1f..1f,
            )
            Text(
                "Lower keeps a little of the background, which can sound more natural.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { if (playing == false) vm.stopCleanupPreview() else vm.previewCleanup(clipId, strength, cleaned = false) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (playing == false) "Stop" else "▶ Before") }
                OutlinedButton(
                    onClick = { if (playing == true) vm.stopCleanupPreview() else vm.previewCleanup(clipId, strength, cleaned = true) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (vm.cleanupPreparing) "Working…" else if (playing == true) "Stop" else "▶ After") }
            }
            Text(
                "Before and After play up to 5 s from the cursor (or the start).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    vm.stopCleanupPreview()
                    vm.applyEffects(clipId, EffectSettings(denoise = strength))
                    onDismiss()
                }) { Text("Apply") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

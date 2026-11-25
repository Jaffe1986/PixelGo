package com.pixelgo.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pixelgo.editor.Timing
import com.pixelgo.model.MAX_CLIP_VOLUME
import com.pixelgo.ui.common.Hint
import com.pixelgo.ui.common.PgIconButton
import com.pixelgo.ui.common.PgSheet
import com.pixelgo.ui.common.PrimaryButton
import com.pixelgo.ui.common.SecondaryButton
import com.pixelgo.ui.common.SectionLabel
import com.pixelgo.ui.common.SliderRow
import com.pixelgo.ui.common.SwitchRow
import com.pixelgo.ui.theme.Pg
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Record from the microphone: voice-over (with the animation playing) or straight here. */
@Composable
fun RecordSheet(vm: EditorViewModel, onDismiss: () -> Unit, punchIn: Boolean = false) {
    val context = LocalContext.current
    var voiceOver by remember { mutableStateOf(vm.frameCount > 1) }
    var countIn by remember { mutableStateOf(true) }
    var trackId by remember { mutableStateOf(vm.defaultRecordTrack()) }
    val start = { if (punchIn) vm.startPunchIn(countIn) else vm.startRecording(voiceOver, countIn, trackId) }
    // Re-recording closes by itself once the take is in place.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(vm.recordState) {
        if (vm.recordState != RecordState.Idle) started = true
        else if (punchIn && started) onDismiss()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else Toast.makeText(context, "PixelGo needs the microphone to record", Toast.LENGTH_LONG).show()
    }
    val record = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val title = when (vm.recordState) {
        RecordState.Idle -> if (punchIn) "Re-record the selection" else "Record sound"
        RecordState.CountIn -> "Get ready"
        RecordState.Recording -> "Recording"
    }
    PgSheet(
        title = title,
        onDismiss = {
            if (vm.recordState != RecordState.Idle) vm.stopRecording(keep = true)
            onDismiss()
        },
        scroll = false,
    ) {
        when (vm.recordState) {
            RecordState.Idle -> if (punchIn) {
                val sel = vm.soundSel
                Text(
                    if (sel == null) "Select a section first." else
                        "Plays from 2 s before the selected ${"%.2f".format(java.util.Locale.US, (sel.toMs - sel.fromMs) / 1000.0)} s " +
                            "while you record, stops a second after it, and replaces just that part.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SwitchRow("3-2-1 count-in", countIn, { countIn = it }, detail = "Also lines the take up exactly.")
                RecordButton("Re-record", enabled = sel != null, onClick = record)
            } else {
                SectionLabel("Track")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.tracks().forEach { t ->
                        FilterChip(selected = trackId == t.id, onClick = { trackId = t.id }, label = { Text(t.name) })
                    }
                    FilterChip(selected = trackId == null, onClick = { trackId = null }, label = { Text("New track") })
                }
                Column(Modifier.selectableGroup()) {
                    ModeRow(
                        selected = voiceOver, title = "Voice-over",
                        detail = "Plays the animation (and its sound) from the current frame while you record, so you can speak in time.",
                    ) { voiceOver = true }
                    ModeRow(
                        selected = !voiceOver, title = "Record here",
                        detail = "Just records. The sound starts at the current frame (${Timing.formatMs(vm.currentFrameMs())}).",
                    ) { voiceOver = false }
                }
                SwitchRow(
                    "3-2-1 count-in", countIn, { countIn = it },
                    detail = if (voiceOver) {
                        "Without headphones it lines the recording up exactly. With headphones the recording stays clean; check its timing afterwards."
                    } else null,
                )
                RecordButton("Record", onClick = record)
            }
            RecordState.CountIn -> {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("${vm.countIn}", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Pg.Accent)
                }
            }
            RecordState.Recording -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Pg.Live))
                    Spacer(Modifier.width(8.dp))
                    Text(Timing.formatMs(vm.recordElapsedMs), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
                }
                LinearProgressIndicator(
                    progress = { vm.recordLevel },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = Pg.Success,
                    trackColor = Pg.Surface3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("Discard", onClick = { vm.stopRecording(keep = false) }, modifier = Modifier.weight(1f))
                    PrimaryButton(
                        "Stop", onClick = { vm.stopRecording(keep = true); onDismiss() },
                        modifier = Modifier.weight(1f), icon = Icons.Filled.Stop,
                    )
                }
            }
        }
    }
}

/** The big red button that starts recording. */
@Composable
private fun RecordButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Pg.Live, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(detail)
        }
    }
}

/** Options for one sound clip. Every change is one undo step. */
@Composable
fun ClipSheet(vm: EditorViewModel, clipId: Long, onDismiss: () -> Unit, onEffects: () -> Unit, onCleanup: () -> Unit) {
    vm.structureTick
    val clip = vm.clips().firstOrNull { it.id == clipId } ?: return
    var volume by remember(clipId, clip.volume) { mutableFloatStateOf(clip.volume) }
    val maxFade = minOf(5_000L, clip.durationMs).toFloat()
    var fadeIn by remember(clipId, clip.fadeInMs) { mutableFloatStateOf(clip.fadeInMs.toFloat().coerceAtMost(maxFade)) }
    var fadeOut by remember(clipId, clip.fadeOutMs) { mutableFloatStateOf(clip.fadeOutMs.toFloat().coerceAtMost(maxFade)) }
    val trimmed = clip.inMs > 0 || clip.outMs < vm.assetDurationMs(clip.assetId)
    PgSheet(
        title = vm.assetName(clip.assetId),
        subtitle = "Starts at ${Timing.formatMs(clip.startMs)}  ·  plays ${Timing.formatMs(clip.durationMs)}" + if (trimmed) "  (trimmed)" else "",
        onDismiss = onDismiss,
        actions = {
            PgIconButton(Icons.Outlined.Delete, "Delete clip", { vm.deleteClip(clip.id); onDismiss() }, tint = Pg.Danger)
        },
    ) {
        SliderRow(
            "Volume", "${(volume * 100).roundToInt()}%", volume,
            onValueChange = { volume = (it * 20).roundToInt() / 20f },
            valueRange = 0f..MAX_CLIP_VOLUME,
            onValueChangeFinished = { vm.changeClip(clip.copy(volume = volume)) },
            step = 0.05f,
        )
        SecondaryButton("Normalize", onClick = { vm.normalizeClip(clip.id) })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SliderRow(
                "Fade in", "${fadeIn.roundToInt()} ms", fadeIn, { fadeIn = it }, 0f..maxFade,
                modifier = Modifier.weight(1f),
                onValueChangeFinished = { vm.changeClip(clip.copy(fadeInMs = fadeIn.roundToLong())) },
            )
            SliderRow(
                "Fade out", "${fadeOut.roundToInt()} ms", fadeOut, { fadeOut = it }, 0f..maxFade,
                modifier = Modifier.weight(1f),
                onValueChangeFinished = { vm.changeClip(clip.copy(fadeOutMs = fadeOut.roundToLong())) },
            )
        }
        Column {
            SectionLabel("Nudge")
            Hint("Line the sound up with the picture.")
        }
        val frameMs = 1000L / vm.fps
        // Two even rows: whole frames, then fine steps.
        listOf(listOf(-frameMs to "−1 frame", frameMs to "+1 frame"), listOf(-10L to "−10 ms", 10L to "+10 ms")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (d, label) ->
                    SecondaryButton(
                        label,
                        onClick = { vm.changeClip(clip.copy(startMs = (clip.startMs + d).coerceAtLeast(0))) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SectionLabel("Edit")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Start at the cursor", onClick = { vm.changeClip(clip.copy(startMs = vm.soundCursorMs())) })
            SecondaryButton("Split at the cursor", onClick = { vm.splitClipAtCurrentFrame(clip.id) })
            SecondaryButton("Duplicate", onClick = { vm.duplicateClip(clip.id) })
        }
        SectionLabel("Process")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Effects…", onClick = onEffects, icon = Icons.Filled.AutoFixHigh)
            SecondaryButton("Clean up noise (AI)…", onClick = onCleanup, icon = Icons.Filled.AutoAwesome)
        }
        Hint("To move a clip, long-press it on the timeline and drag (drag its edges to trim).")
    }
}

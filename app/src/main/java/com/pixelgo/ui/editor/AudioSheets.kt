package com.pixelgo.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pixelgo.editor.Timing
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.pixelgo.model.MAX_CLIP_VOLUME
import kotlin.math.roundToLong

/** Record from the microphone: voice-over (with the animation playing) or straight here. */
@OptIn(ExperimentalMaterial3Api::class)
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
    ModalBottomSheet(
        onDismissRequest = {
            if (vm.recordState != RecordState.Idle) vm.stopRecording(keep = true)
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (vm.recordState) {
                RecordState.Idle -> if (punchIn) {
                    val sel = vm.soundSel
                    Text("Re-record the selection", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (sel == null) "Select a section first." else
                            "Plays from 2 s before the selected ${"%.2f".format(java.util.Locale.US, (sel.toMs - sel.fromMs) / 1000.0)} s " +
                                "while you record, stops a second after it, and replaces just that part.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("3-2-1 count-in (also lines the take up exactly)", modifier = Modifier.weight(1f))
                        Switch(checked = countIn, onCheckedChange = { countIn = it })
                    }
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                            else permission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = sel != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("● Re-record", fontSize = 18.sp) }
                } else {
                    Text("Record sound", style = MaterialTheme.typography.titleLarge)
                    Text("Track", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.tracks().forEach { t ->
                            FilterChip(selected = trackId == t.id, onClick = { trackId = t.id }, label = { Text(t.name) })
                        }
                        FilterChip(selected = trackId == null, onClick = { trackId = null }, label = { Text("New track") })
                    }
                    ModeRow(
                        selected = voiceOver, title = "Voice-over",
                        detail = "Plays the animation (and its sound) from the current frame while you record, so you can speak in time.",
                    ) { voiceOver = true }
                    ModeRow(
                        selected = !voiceOver, title = "Record here",
                        detail = "Just records. The sound starts at the current frame (${Timing.formatMs(vm.currentFrameMs())}).",
                    ) { voiceOver = false }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("3-2-1 count-in", modifier = Modifier.weight(1f))
                        Switch(checked = countIn, onCheckedChange = { countIn = it })
                    }
                    if (voiceOver) {
                        Text(
                            "Without headphones, the count-in lines your recording up exactly (but the microphone " +
                                "also hears the playback). With headphones the recording stays clean; check its timing " +
                                "afterwards.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                            else permission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("● Record", fontSize = 18.sp) }
                }
                RecordState.CountIn -> {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text("${vm.countIn}", fontSize = 72.sp, fontWeight = FontWeight.Bold)
                    }
                }
                RecordState.Recording -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFE53935)))
                        Spacer(Modifier.width(8.dp))
                        Text("Recording  ${Timing.formatMs(vm.recordElapsedMs)}", style = MaterialTheme.typography.titleMedium)
                    }
                    LinearProgressIndicator(progress = { vm.recordLevel }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { vm.stopRecording(keep = false) }, modifier = Modifier.weight(1f)) { Text("Discard") }
                        Button(onClick = { vm.stopRecording(keep = true); onDismiss() }, modifier = Modifier.weight(1f)) { Text("■ Stop") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Options for one sound clip. Every change is one undo step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipSheet(vm: EditorViewModel, clipId: Long, onDismiss: () -> Unit, onEffects: () -> Unit, onCleanup: () -> Unit) {
    vm.structureTick
    val clip = vm.clips().firstOrNull { it.id == clipId } ?: return
    var volume by remember(clipId, clip.volume) { mutableFloatStateOf(clip.volume) }
    val maxFade = minOf(5_000L, clip.durationMs).toFloat()
    var fadeIn by remember(clipId, clip.fadeInMs) { mutableFloatStateOf(clip.fadeInMs.toFloat().coerceAtMost(maxFade)) }
    var fadeOut by remember(clipId, clip.fadeOutMs) { mutableFloatStateOf(clip.fadeOutMs.toFloat().coerceAtMost(maxFade)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(vm.assetName(clip.assetId), style = MaterialTheme.typography.titleLarge)
            Text(
                "Starts at ${Timing.formatMs(clip.startMs)}  ·  plays ${Timing.formatMs(clip.durationMs)}" +
                    if (clip.inMs > 0 || clip.outMs < vm.assetDurationMs(clip.assetId)) "  (trimmed)" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume ${(volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.normalizeClip(clip.id) }) { Text("Normalize") }
            }
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { vm.changeClip(clip.copy(volume = volume)) },
                valueRange = 0f..MAX_CLIP_VOLUME,
            )
            Text("Fade in ${fadeIn.roundToInt()} ms  ·  fade out ${fadeOut.roundToInt()} ms", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Slider(
                    value = fadeIn, onValueChange = { fadeIn = it }, valueRange = 0f..maxFade, modifier = Modifier.weight(1f),
                    onValueChangeFinished = { vm.changeClip(clip.copy(fadeInMs = fadeIn.roundToLong())) },
                )
                Slider(
                    value = fadeOut, onValueChange = { fadeOut = it }, valueRange = 0f..maxFade, modifier = Modifier.weight(1f),
                    onValueChangeFinished = { vm.changeClip(clip.copy(fadeOutMs = fadeOut.roundToLong())) },
                )
            }
            Text("Nudge (line it up with the picture)", style = MaterialTheme.typography.labelLarge)
            val frameMs = 1000L / vm.fps
            // Two even rows: whole frames, then fine steps.
            listOf(listOf(-frameMs to "−1 frame", frameMs to "+1 frame"), listOf(-10L to "−10 ms", 10L to "+10 ms")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (d, label) ->
                        OutlinedButton(
                            onClick = { vm.changeClip(clip.copy(startMs = (clip.startMs + d).coerceAtLeast(0))) },
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { vm.changeClip(clip.copy(startMs = vm.soundCursorMs())) }) { Text("Start at the cursor") }
                TextButton(onClick = { vm.splitClipAtCurrentFrame(clip.id) }) { Text("Split at the cursor") }
                TextButton(onClick = onEffects) { Text("Effects…") }
                TextButton(onClick = onCleanup) { Text("Clean up noise (AI)…") }
                TextButton(onClick = { vm.duplicateClip(clip.id) }) { Text("Duplicate") }
                TextButton(onClick = { vm.deleteClip(clip.id); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            Text(
                "Tip: long-press a clip on the timeline and drag to move it (or drag its edges to trim).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

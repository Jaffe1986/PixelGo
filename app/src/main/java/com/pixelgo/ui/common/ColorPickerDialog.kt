package com.pixelgo.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.pixelgo.ui.theme.Pg
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pixelgo.editor.ColorMath
import android.graphics.Color as AColor

/**
 * HSV + alpha color picker. Alpha is fully supported so colors can be partly or fully
 * transparent.
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    recent: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val hsv0 = remember { FloatArray(3).also { AColor.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    var alpha by remember { mutableIntStateOf(initial ushr 24) }

    fun current(): Int = AColor.HSVToColor(alpha, floatArrayOf(hue, sat, value))

    var hexText by remember { mutableStateOf(ColorMath.toHex(initial)) }
    fun syncHex() { hexText = ColorMath.toHex(current()) }

    fun setFromColor(c: Int) {
        val hsv = FloatArray(3)
        AColor.colorToHSV(c, hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]; alpha = c ushr 24
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Pg.Surface2) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Pick color", style = MaterialTheme.typography.titleLarge)

                // Saturation / value square
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            trackPosition { p, size ->
                                sat = (p.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                                syncHex()
                            }
                        }
                ) {
                    val hueColor = Color(AColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val c = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.Black, 10.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 8.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                }

                // Hue bar
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .pointerInput(Unit) {
                            trackPosition { p, size ->
                                hue = (p.x / size.width).coerceIn(0f, 1f) * 360f
                                syncHex()
                            }
                        }
                ) {
                    val stops = (0..6).map { Color(AColor.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f))) }
                    drawRect(Brush.horizontalGradient(stops))
                    val x = hue / 360f * size.width
                    drawRect(Color.White, Offset(x - 3.dp.toPx(), 0f), size.copy(width = 6.dp.toPx()), style = Stroke(2.dp.toPx()))
                }

                // Alpha bar
                Text("Opacity ${(alpha * 100 / 255)}%", style = Pg.Data, color = Pg.Text2)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .pointerInput(Unit) {
                            trackPosition { p, size ->
                                alpha = ((p.x / size.width).coerceIn(0f, 1f) * 255f).toInt()
                                syncHex()
                            }
                        }
                ) {
                    drawCheckerboard(Rect(Offset.Zero, size), size.height / 2f)
                    val opaque = AColor.HSVToColor(floatArrayOf(hue, sat, value))
                    drawRect(Brush.horizontalGradient(listOf(Color(opaque and 0x00FFFFFF), Color(opaque))))
                    val x = alpha / 255f * size.width
                    drawRect(Color.White, Offset(x - 3.dp.toPx(), 0f), size.copy(width = 6.dp.toPx()), style = Stroke(2.dp.toPx()))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorSwatch(initial, size = 44.dp, label = "Old color")
                    Spacer(Modifier.width(4.dp))
                    ColorSwatch(current(), size = 44.dp, label = "New color")
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { t ->
                            hexText = t.take(9)
                            ColorMath.parseHex(t)?.let { setFromColor(it) }
                        },
                        label = { Text("Hex (AARRGGBB)") },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (recent.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.labelMedium, color = Pg.Text2)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(recent) { c ->
                            ColorSwatch(c, Modifier.clickable { setFromColor(c); syncHex() }, size = 32.dp)
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    PgTextButton("Cancel", onClick = onDismiss)
                    PrimaryButton("Use color", onClick = { onConfirm(current()) })
                }
            }
        }
    }
}

/** Reports the pointer position on press and while dragging. */
private suspend fun PointerInputScope.trackPosition(onPosition: (Offset, IntSize) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onPosition(down.position, size)
        down.consume()
        drag(down.id) { change ->
            onPosition(change.position, size)
            change.consume()
        }
    }
}

package com.pixelgo.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

val CheckerLight = Color(0xFFE6E6E6)
val CheckerDark = Color(0xFFB4B4B4)

/**
 * Draws a transparency checkerboard inside [rect], only over the part that is within [clip].
 * Cells are aligned to the rect's top-left corner.
 */
fun DrawScope.drawCheckerboard(rect: Rect, cell: Float, clip: Rect = Rect(Offset.Zero, size)) {
    val visible = rect.intersect(clip)
    if (visible.isEmpty) return
    drawRect(CheckerLight, visible.topLeft, visible.size)
    val c0 = max(0, floor((visible.left - rect.left) / cell).toInt())
    val c1 = ceil((visible.right - rect.left) / cell).toInt()
    val r0 = max(0, floor((visible.top - rect.top) / cell).toInt())
    val r1 = ceil((visible.bottom - rect.top) / cell).toInt()
    for (r in r0 until r1) {
        val top = rect.top + r * cell
        for (c in c0 until c1) {
            if ((r + c) % 2 == 0) continue
            val left = rect.left + c * cell
            val l = max(left, visible.left)
            val t = max(top, visible.top)
            val rr = min(left + cell, visible.right)
            val b = min(top + cell, visible.bottom)
            if (rr > l && b > t) drawRect(CheckerDark, Offset(l, t), Size(rr - l, b - t))
        }
    }
}

/** A color square that shows a checkerboard underneath so alpha is visible. */
@Composable
fun ColorSwatch(
    color: Int,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else Color(0x55FFFFFF),
                shape,
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCheckerboard(Rect(Offset.Zero, this.size), this.size.width / 4f)
            drawRect(Color(color))
        }
    }
}

/** Simple single-field text dialog (rename etc). */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Eraser icon (not in the Material icon set). */
val EraserIcon: ImageVector by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M16.24,3.56L21.19,8.5C21.97,9.29 21.97,10.55 21.19,11.34L12,20.53" +
                "C10.44,22.09 7.91,22.09 6.34,20.53L2.81,17C2.03,16.21 2.03,14.95 2.81,14.16" +
                "L13.41,3.56C14.2,2.78 15.46,2.78 16.24,3.56Z" +
                "M4.22,15.58L7.76,19.11C8.54,19.9 9.8,19.9 10.59,19.11L14.12,15.58L9.17,10.63L4.22,15.58Z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

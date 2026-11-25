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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import com.pixelgo.editor.ColorMath
import com.pixelgo.ui.theme.Pg
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

/** A quiet checkerboard for thumbnails on dark cards (transparent pixels without the glare). */
val CheckerQuietLight = Color(0xFF3A3A3A)
val CheckerQuietDark = Color(0xFF313131)

/**
 * Draws a transparency checkerboard inside [rect], only over the part that is within [clip].
 * Cells are aligned to the rect's top-left corner.
 */
fun DrawScope.drawCheckerboard(
    rect: Rect,
    cell: Float,
    clip: Rect = Rect(Offset.Zero, size),
    light: Color = CheckerLight,
    dark: Color = CheckerDark,
) {
    val visible = rect.intersect(clip)
    if (visible.isEmpty) return
    drawRect(light, visible.topLeft, visible.size)
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
            if (rr > l && b > t) drawRect(dark, Offset(l, t), Size(rr - l, b - t))
        }
    }
}

/**
 * A color square that shows a checkerboard underneath so alpha is visible. The current color
 * gets a yellow ring with a small gap. [label] is what TalkBack reads (default: the hex code).
 */
@Composable
fun ColorSwatch(
    color: Int,
    modifier: Modifier = Modifier,
    size: Dp = Pg.Swatch,
    selected: Boolean = false,
    label: String? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = label ?: ColorMath.toHex(color)
                this.selected = selected
            }
            .then(if (selected) Modifier.border(2.dp, Pg.Accent, RoundedCornerShape(8.dp)).padding(4.dp) else Modifier)
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
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
        confirmButton = { PrimaryButton(confirmLabel, onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) },
        dismissButton = { PgTextButton("Cancel", onClick = onDismiss) },
    )
}

/**
 * Asks before doing something. Only for actions that can't be undone (or where the owner chose
 * to ask, like merging layers); undoable actions act at once and offer Undo instead.
 * [destructive] makes the confirm button red.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (destructive) DangerButton(confirmLabel, onClick = onConfirm) else PrimaryButton(confirmLabel, onClick = onConfirm)
        },
        dismissButton = { PgTextButton("Cancel", onClick = onDismiss) },
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

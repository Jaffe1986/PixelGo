package com.pixelgo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixelgo.ui.theme.Pg

// Shared components of PixelGo's design system (docs/DESIGN.md). Screens build from these
// instead of styling Material components one by one.

private val ControlShape = RoundedCornerShape(6.dp)

// ---- Buttons ------------------------------------------------------------------------------

/** The one main action of a surface: filled yellow. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = ControlShape, contentPadding = ButtonPadding) {
        ButtonLabel(text, icon)
    }
}

/** A secondary action next to the primary one: a quiet grey tone. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = ControlShape, contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Pg.Surface3, contentColor = Pg.Text1,
            disabledContainerColor = Pg.Surface3.copy(alpha = 0.5f), disabledContentColor = Pg.Text3,
        ),
    ) { ButtonLabel(text, icon) }
}

/** Confirms something that can't be undone (deleting a project): filled red. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, modifier = modifier, shape = ControlShape, contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(containerColor = Pg.Danger, contentColor = Pg.OnAccent),
    ) { ButtonLabel(text, null) }
}

/** A plain text action. Neutral, or red for destructive actions. */
@Composable
fun PgTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = ControlShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) Pg.Danger else Pg.Text1, disabledContentColor = Pg.Text3,
        ),
    ) { Text(text) }
}

private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
private fun RowScope.ButtonLabel(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
    }
    Text(text, maxLines = 1)
}

// ---- Icon buttons ---------------------------------------------------------------------------

/**
 * A 48dp icon button. With [checked] it's a toggle, tinted yellow while on. [tint] overrides the
 * icon color (e.g. red for record).
 */
@Composable
fun PgIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean? = null,
    tint: Color = Color.Unspecified,
    iconSize: Dp = 24.dp,
) {
    if (checked == null) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Icon(icon, label, Modifier.size(iconSize), tint = if (tint.isSpecified && enabled) tint else LocalContentColor.current)
        }
    } else {
        IconToggleButton(
            checked = checked, onCheckedChange = { onClick() }, modifier = modifier, enabled = enabled,
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = Pg.AccentWeak, checkedContentColor = Pg.Accent,
            ),
        ) { Icon(icon, label, Modifier.size(iconSize)) }
    }
}

/** A round, filled icon button for transport (play / stop). */
@Composable
fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledIconButton(
        onClick = onClick, enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Pg.Surface3, contentColor = Pg.Text1,
            disabledContainerColor = Pg.Surface3.copy(alpha = 0.5f), disabledContentColor = Pg.Text3,
        ),
    ) { Icon(icon, label, Modifier.size(22.dp)) }
}

// ---- Tools ------------------------------------------------------------------------------------

/**
 * A drawing tool: icon over label. Tools are one choice, so the current one is filled yellow.
 * Without [showLabel] (the landscape rail) the label becomes the description.
 */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedIcon: ImageVector = icon,
    showLabel: Boolean = true,
) {
    val bg = if (selected) Pg.Accent else Color.Transparent
    val fg = if (selected) Pg.OnAccent else Pg.Text2
    Column(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .then(if (showLabel) Modifier else Modifier.semantics { contentDescription = label })
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) selectedIcon else icon, null, tint = fg, modifier = Modifier.size(24.dp))
        if (showLabel) {
            Text(label, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A button in the tool dock that isn't a tool (Mirror, Layers). [on] shows a state that's
 * switched on (yellow tint); [stateText] is read out by TalkBack.
 */
@Composable
fun DockButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    stateText: String? = null,
    showLabel: Boolean = true,
    icon: @Composable (tint: Color) -> Unit,
) {
    val bg = if (on) Pg.AccentWeak else Color.Transparent
    val fg = if (on) Pg.Accent else Pg.Text1
    Column(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .combinedClickable(onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, role = Role.Button, onClick = onClick)
            .semantics {
                if (stateText != null) stateDescription = stateText
                if (!showLabel) contentDescription = label
            }
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(fg)
        if (showLabel) {
            Text(label, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Context bars -----------------------------------------------------------------------------

/** One choice in an action's menu (e.g. "Paste after selection"). Labels read on their own. */
class Choice(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * An action in a context bar. With [choices] it opens a small menu. [group] is the section of
 * the More menu it goes under when it doesn't fit.
 */
class BarAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val group: String? = null,
    val choices: List<Choice> = emptyList(),
    val onClick: () -> Unit = {},
)

/**
 * A context bar: an optional close button and [lead] (what's selected), then labelled actions.
 * Past [maxVisible] (More included) the rest go into a More menu, grouped by [BarAction.group].
 */
@Composable
fun ActionBar(
    actions: List<BarAction>,
    modifier: Modifier = Modifier,
    lead: String? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String = "Done",
    maxVisible: Int = 5,
    start: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(Pg.ActionBarHeight).background(Pg.Surface2).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClose != null) PgIconButton(Icons.Filled.Close, closeLabel, onClose)
        if (lead != null) {
            Text(
                lead, style = Pg.Data, color = Pg.Text1, maxLines = 2,
                modifier = Modifier.padding(start = if (onClose == null) 12.dp else 0.dp, end = 8.dp).widthIn(max = 84.dp),
            )
        }
        start?.invoke(this)
        val visible = if (actions.size <= maxVisible) actions else actions.take(maxVisible - 1)
        val rest = actions.drop(visible.size)
        visible.forEach { ActionButton(it, Modifier.weight(1f)) }
        if (rest.isNotEmpty()) MoreButton(rest, Modifier.weight(1f))
    }
}

@Composable
private fun ActionButton(a: BarAction, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        BarButtonFace(a.icon, a.label, a.enabled, a.destructive) {
            if (a.choices.isNotEmpty()) open = true else a.onClick()
        }
        if (a.choices.isNotEmpty()) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                a.choices.forEach { c ->
                    DropdownMenuItem(text = { Text(c.label) }, enabled = c.enabled, onClick = { open = false; c.onClick() })
                }
            }
        }
    }
}

@Composable
private fun MoreButton(rest: List<BarAction>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        BarButtonFace(Icons.Filled.MoreHoriz, "More", enabled = true, destructive = false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            rest.forEachIndexed { i, a ->
                val group = a.group
                if (group != null && group != rest.getOrNull(i - 1)?.group) {
                    if (i > 0) HorizontalDivider()
                    Text(
                        group, style = MaterialTheme.typography.labelMedium, color = Pg.Text2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                val color = if (a.destructive) Pg.Danger else Pg.Text1
                val items = a.choices.ifEmpty { listOf(Choice(a.label, a.enabled, a.onClick)) }
                items.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.label) },
                        leadingIcon = { Icon(a.icon, null, Modifier.size(20.dp)) },
                        enabled = c.enabled && a.enabled,
                        colors = MenuDefaults.itemColors(textColor = color, leadingIconColor = color),
                        onClick = { open = false; c.onClick() },
                    )
                }
            }
        }
    }
}

@Composable
private fun BarButtonFace(icon: ImageVector, label: String, enabled: Boolean, destructive: Boolean, onClick: () -> Unit) {
    val color = when {
        !enabled -> Pg.Text3
        destructive -> Pg.Danger
        else -> Pg.Text1
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Choices ----------------------------------------------------------------------------------

/**
 * One of a few modes, side by side; the chosen one is filled yellow. Give it a width (the
 * options share it equally).
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(Pg.Surface3).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            val c = if (on) Pg.OnAccent else Pg.Text2
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) Pg.Accent else Color.Transparent)
                    .selectable(selected = on, onClick = { onSelect(o) }, role = Role.RadioButton)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    Icon(icon(o), null, tint = c, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(label(o), color = c, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }
        }
    }
}

// ---- Sheets -----------------------------------------------------------------------------------

/**
 * A bottom sheet for editing something on screen: title (and [subtitle]), optional header
 * [actions], scrolling content with 16dp padding, and an optional [footer] that stays visible
 * (put the primary button last, so it sits bottom-right).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PgSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Pg.Surface2,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Pg.Text2, maxLines = 2)
                    }
                }
                actions?.invoke(this)
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            if (footer != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** A section heading inside a sheet or dialog. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

/** Secondary explanation under a control: one line where possible. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Pg.Text2, modifier = modifier)
}

// ---- Values -------------------------------------------------------------------------------------

/** A slider in PixelGo's style: neutral track, yellow handle, no tick dots (snap in [onValueChange]). */
@Composable
fun PgSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        valueRange = valueRange, onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = Pg.Accent,
            activeTrackColor = Pg.Text1,
            inactiveTrackColor = Pg.Surface3,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}

/**
 * A labelled slider with its value shown in the data style. With [step], − and + buttons move
 * one step (and finish the change).
 */
@Composable
fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    step: Float? = null,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = Pg.Data, color = Pg.Text2)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step != null) {
                PgIconButton(Icons.Filled.Remove, "Decrease $label", enabled = enabled && value > valueRange.start, iconSize = 20.dp, onClick = {
                    onValueChange((value - step).coerceIn(valueRange))
                    onValueChangeFinished?.invoke()
                })
            }
            PgSlider(value, onValueChange, Modifier.weight(1f), valueRange, onValueChangeFinished, enabled)
            if (step != null) {
                PgIconButton(Icons.Filled.Add, "Increase $label", enabled = enabled && value < valueRange.endInclusive, iconSize = 20.dp, onClick = {
                    onValueChange((value + step).coerceIn(valueRange))
                    onValueChangeFinished?.invoke()
                })
            }
        }
    }
}

/** A setting that's on or off; the whole row toggles it. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Pg.Text1 else Pg.Text3)
            if (detail != null) Hint(detail)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

// ---- Feedback -------------------------------------------------------------------------------------

/** Snackbars in PixelGo's style (dark, with a yellow action such as Undo). */
@Composable
fun PgSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            data, shape = RoundedCornerShape(8.dp),
            containerColor = Pg.Snackbar, contentColor = Pg.Text1, actionColor = Pg.Accent,
        )
    }
}

// ---- Icons ----------------------------------------------------------------------------------------

/** Mirror drawing: which axes are on. Off shows hollow arrows on a dashed axis. */
fun mirrorIcon(x: Boolean, y: Boolean): ImageVector = when {
    x && y -> MirrorBoth
    y -> MirrorUpDown
    x -> MirrorLeftRight
    else -> MirrorOff
}

private fun mirrorVector(name: String, vertical: Boolean, horizontal: Boolean, filled: Boolean): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        val stroke = SolidColor(Color.Black)
        val fill = if (filled) SolidColor(Color.Black) else null
        fun dashes(vert: Boolean) = path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            for (a in listOf(2f, 7.5f, 13f, 18.5f)) {
                if (vert) { moveTo(12f, a); lineTo(12f, a + 3f) } else { moveTo(a, 12f); lineTo(a + 3f, 12f) }
            }
        }
        fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) =
            path(fill = fill, stroke = stroke, strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(ax, ay); lineTo(bx, by); lineTo(cx, cy); close()
            }
        if (vertical && horizontal) {
            dashes(true); dashes(false)
            tri(8.5f, 9f, 4.5f, 12f, 8.5f, 15f); tri(15.5f, 9f, 19.5f, 12f, 15.5f, 15f)
            tri(9f, 8.5f, 12f, 4.5f, 15f, 8.5f); tri(9f, 15.5f, 12f, 19.5f, 15f, 15.5f)
        } else if (vertical) {
            dashes(true)
            tri(9.5f, 7f, 4f, 12f, 9.5f, 17f); tri(14.5f, 7f, 20f, 12f, 14.5f, 17f)
        } else {
            dashes(false)
            tri(7f, 9.5f, 12f, 4f, 17f, 9.5f); tri(7f, 14.5f, 12f, 20f, 17f, 14.5f)
        }
    }.build()

private val MirrorOff by lazy { mirrorVector("MirrorOff", vertical = true, horizontal = false, filled = false) }
private val MirrorLeftRight by lazy { mirrorVector("MirrorLeftRight", vertical = true, horizontal = false, filled = true) }
private val MirrorUpDown by lazy { mirrorVector("MirrorUpDown", vertical = false, horizontal = true, filled = true) }
private val MirrorBoth by lazy { mirrorVector("MirrorBoth", vertical = true, horizontal = true, filled = true) }

/** Onion skin: the previous drawing (dashed) behind the current one. */
val OnionSkinIcon: ImageVector by lazy {
    ImageVector.Builder("OnionSkin", 24.dp, 24.dp, 24f, 24f).apply {
        val stroke = SolidColor(Color.Black)
        // The earlier frame: a dashed circle, drawn as short arcs.
        path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            val cx = 9f
            val cy = 12f
            val r = 6f
            for (k in 0 until 10) {
                val a0 = k * 36f
                val a1 = a0 + 20f
                fun px(a: Float) = cx + r * kotlin.math.cos(Math.toRadians(a.toDouble())).toFloat()
                fun py(a: Float) = cy + r * kotlin.math.sin(Math.toRadians(a.toDouble())).toFloat()
                moveTo(px(a0), py(a0))
                arcTo(r, r, 0f, false, true, px(a1), py(a1))
            }
        }
        // The current frame: solid.
        path(stroke = stroke, strokeLineWidth = 1.8f) {
            moveTo(21f, 12f)
            arcTo(6f, 6f, 0f, true, true, 9f, 12f)
            arcTo(6f, 6f, 0f, true, true, 21f, 12f)
            close()
        }
    }.build()
}

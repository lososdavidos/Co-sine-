package app.sine.ui.phase

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.util.Locale

/** What a pill is for, which is what colours it. */
enum class PillKind {
    /** The one action a screen is for: Sine. */
    Primary,
    /** Everything else you press: Lift. */
    Secondary,
    /** Opens the server's work, such as the review queue: Cosine. */
    Server,
    /** Removes or deletes, naming what and how many: red. */
    Fault,
    /** A secondary pill whose action removes something, among others in a row. */
    SecondaryFault,
    /** Floats over content or artwork: the back pill, the account circle. */
    Floating,
    /** Text only: Cancel, Clear, Disconnect. */
    Quiet,
    /** Text only, red: Delete or Leave among quiet actions. */
    QuietFault,
    /** Outlined: filters and small actions above a list. */
    Outline,
}

/**
 * How an icon button says its name when it's held: Sine puts it in the shelf.
 * A screen reader hears it as the button's description either way.
 */
val LocalSayName = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The light an icon takes on a pill of this kind. */
fun PillKind.iconTone(): IconTone = when (this) {
    PillKind.Primary -> IconTone.OnSine
    PillKind.Server -> IconTone.OnCosine
    PillKind.Fault -> IconTone.OnFault
    PillKind.SecondaryFault, PillKind.QuietFault -> IconTone.Fault
    PillKind.Quiet -> IconTone.Dim
    else -> IconTone.Light
}

/**
 * Something you press. Transport is a pill too, wider than tall: a round
 * button would read as a person. With an icon and no [text] it's an icon
 * button: [contentDescription] is its name, said when it's held.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Pill(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PillKind = PillKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    width: Dp? = null,
    count: String? = null,
    contentDescription: String? = null,
    /** A second action, two taps away; a single tap then waits a moment to be sure. */
    onDoubleClick: (() -> Unit)? = null,
) {
    val background = when (kind) {
        PillKind.Primary -> PhaseColor.Sine
        PillKind.Secondary, PillKind.SecondaryFault -> PhaseColor.Lift
        PillKind.Server -> PhaseColor.Cosine
        PillKind.Fault -> PhaseColor.Fault
        PillKind.Floating -> PhaseColor.Float
        PillKind.Quiet, PillKind.QuietFault, PillKind.Outline -> Color.Transparent
    }
    val ink = when (kind) {
        PillKind.Primary, PillKind.Fault -> PhaseColor.Ink
        PillKind.Server -> PhaseColor.OnCosine
        PillKind.SecondaryFault, PillKind.QuietFault -> PhaseColor.Fault
        PillKind.Quiet -> PhaseColor.Text2
        else -> PhaseColor.Text1
    }
    val strong = kind == PillKind.Primary || kind == PillKind.Server || kind == PillKind.Fault || kind == PillKind.SecondaryFault
    val textSize = if (height < 44.dp) 13.sp else if (height < 48.dp) 14.sp else 15.sp
    val iconOnly = icon != null && text == null && count == null
    val sayName = LocalSayName.current
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .height(height)
            .then(
                when {
                    width != null -> Modifier.width(width)
                    iconOnly -> Modifier.width(height + 8.dp)
                    else -> Modifier.defaultMinSize(minWidth = height)
                },
            )
            .clip(PhaseShape.Pill)
            .background(background)
            .then(if (kind == PillKind.Outline) Modifier.border(1.dp, PhaseColor.LineFloat, PhaseShape.Pill) else Modifier)
            .combinedClickable(
                enabled = enabled, role = Role.Button, onClick = onClick, onDoubleClick = onDoubleClick,
                onLongClickLabel = if (text == null && contentDescription != null) "Say its name" else null,
                onLongClick = if (text == null && contentDescription != null) ({ sayName(contentDescription) }) else null,
            )
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(
                horizontal = when {
                    width != null || iconOnly -> 0.dp
                    text == null -> 14.dp
                    icon != null -> 16.dp
                    else -> 18.dp
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            val iconSize = when {
                text != null -> if (height >= 56.dp) 22.dp else 18.dp
                height >= 56.dp -> 26.dp
                height >= 44.dp -> 22.dp
                else -> 20.dp
            }
            PhaseIcon(icon, null, size = iconSize, tone = kind.iconTone())
        }
        if (text != null) {
            Text(
                text, color = ink, maxLines = 1,
                style = PhaseType.Button.copy(fontSize = textSize, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium),
            )
        }
        if (count != null) Text(count, color = ink, style = PhaseType.Data.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Kinds, sources, sections: small mono capitals. */
@Composable
fun PhaseLabel(text: String, modifier: Modifier = Modifier, color: Color = PhaseColor.Text3) {
    Text(
        text.uppercase(Locale.ROOT), modifier, color = color, style = PhaseType.Label,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

enum class TagKind { Neutral, Server, Fault }

/**
 * One word on a row, or a mark in its place. A filled one asks you to look; a
 * neutral one only describes. With an [icon] and no [text], [contentDescription] names it.
 */
@Composable
fun Tag(
    text: String?,
    modifier: Modifier = Modifier,
    kind: TagKind = TagKind.Neutral,
    icon: ImageVector? = null,
    contentDescription: String? = null,
) {
    val filled = kind != TagKind.Neutral
    Row(
        modifier
            .then(
                when (kind) {
                    // A mark alone needs no frame.
                    TagKind.Neutral -> if (text == null) Modifier else Modifier.border(1.dp, PhaseColor.LineStrong, PhaseShape.Square)
                    TagKind.Server -> Modifier.background(PhaseColor.Cosine, PhaseShape.Square)
                    TagKind.Fault -> Modifier.background(PhaseColor.Fault, PhaseShape.Square)
                },
            )
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = if (text == null) 2.dp else 6.dp, vertical = if (text == null) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            PhaseIcon(
                icon, null, size = if (text == null) 18.dp else 14.dp,
                tone = when (kind) {
                    TagKind.Neutral -> IconTone.Dim
                    TagKind.Server -> IconTone.OnCosine
                    TagKind.Fault -> IconTone.OnFault
                },
            )
        }
        if (text != null) {
            Text(
                text.uppercase(Locale.ROOT),
                color = when (kind) {
                    TagKind.Neutral -> PhaseColor.Text2
                    TagKind.Server -> PhaseColor.OnCosine
                    TagKind.Fault -> PhaseColor.Ink
                },
                style = PhaseType.Label.copy(fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

/** Which signal a line of status starts with, if any. */
enum class Signal { None, Server, Fault }

/** A sentence of status. Cosine's dot for work in progress or waiting on the server, red for something that stopped. */
@Composable
fun StatusLine(text: String, modifier: Modifier = Modifier, signal: Signal = Signal.None, color: Color = PhaseColor.Text1) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (signal != Signal.None) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .background(if (signal == Signal.Server) PhaseColor.Cosine else PhaseColor.Fault, RoundedCornerShape(2.dp)),
            )
        }
        Text(text, color = color, style = PhaseType.Small.copy(fontSize = 14.sp, lineHeight = 20.sp))
    }
}

/**
 * Where you are among a few equal places. Location, not selection, so it's
 * the light pill, never a signal. With [icons], each place is its icon and
 * the one you're in is named beside it.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector>? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(PhaseShape.Pill)
            .background(PhaseColor.Raised)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(PhaseShape.Pill)
                    .background(if (on) PhaseColor.Text1 else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .then(if (icons != null && !on) Modifier.semantics { contentDescription = label } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val icon = icons?.getOrNull(i)
                    if (icon != null) PhaseIcon(icon, null, size = 20.dp, tone = if (on) IconTone.OnLight else IconTone.Dim)
                    if (icon == null || on) {
                        Text(
                            label, maxLines = 1, color = if (on) PhaseColor.Ink else PhaseColor.Text2,
                            style = PhaseType.Button.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A filter or a choice among settings: outlined, the light pill when on. With
 * an [icon] and no [text], [contentDescription] names it, and holding it says so.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Chip(
    text: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
) {
    val sayName = LocalSayName.current
    Row(
        modifier
            .height(40.dp)
            .then(if (text == null) Modifier.width(52.dp) else Modifier)
            .clip(PhaseShape.Pill)
            .background(if (selected) PhaseColor.Text1 else Color.Transparent)
            .border(1.dp, if (selected) PhaseColor.Text1 else PhaseColor.LineFloat, PhaseShape.Pill)
            .then(
                if (text == null && contentDescription != null) {
                    Modifier
                        .combinedClickable(
                            role = Role.Checkbox, onClick = onClick,
                            onLongClick = { sayName(contentDescription) }, onLongClickLabel = "Say its name",
                        )
                        .semantics {
                            this.contentDescription = contentDescription
                            toggleableState = ToggleableState(selected)
                        }
                } else Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() }),
            )
            .padding(horizontal = if (text == null) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) PhaseIcon(icon, null, size = if (text == null) 20.dp else 18.dp, tone = if (selected) IconTone.OnLight else IconTone.Dim)
        if (text != null) {
            Text(
                text, maxLines = 1, color = if (selected) PhaseColor.Ink else PhaseColor.Text2,
                style = PhaseType.Button.copy(fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            )
        }
    }
}

/** A box to tick, square because it's read: Sine when ticked, since ticking is selecting. */
@Composable
fun Tick(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .clip(PhaseShape.Square)
            .background(if (checked) PhaseColor.Sine else Color.Transparent)
            .border(1.5.dp, if (checked) PhaseColor.Sine else PhaseColor.LineStrong, PhaseShape.Square),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) PhaseIcon(PhaseIcons.Check, null, size = 16.dp, tone = IconTone.OnSine)
    }
}

/** A field to type into: a slab, its label in mono above it. */
@Composable
fun PhaseField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    onClear: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) PhaseLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { (label ?: placeholder)?.let { contentDescription = it } },
            enabled = enabled,
            singleLine = singleLine,
            textStyle = PhaseType.Body.copy(fontSize = 16.sp, color = PhaseColor.Text1),
            cursorBrush = SolidColor(PhaseColor.Sine),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            decorationBox = { inner ->
                Row(
                    Modifier
                        .heightIn(min = 52.dp)
                        .clip(PhaseShape.Slab)
                        .background(PhaseColor.Raised)
                        .border(1.dp, PhaseColor.LineFloat, PhaseShape.Slab)
                        .padding(start = if (leading != null) 14.dp else 18.dp, end = if (onClear != null) 4.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leading != null) {
                        PhaseIcon(leading, null, size = 18.dp, tone = IconTone.Faint)
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, color = PhaseColor.Text3, style = PhaseType.Body.copy(fontSize = 16.sp), maxLines = 1)
                        }
                        inner()
                    }
                    if (onClear != null && value.isNotEmpty()) {
                        Pill(null, onClear, kind = PillKind.Quiet, icon = PhaseIcons.Close, height = 44.dp, width = 44.dp, contentDescription = "Clear")
                    }
                }
            },
        )
    }
}

/** What's playing, in a row: three bars of Sine. */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, color: Color = PhaseColor.Sine) {
    Row(
        modifier.height(14.dp).semantics { contentDescription = "Playing" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (h in listOf(8, 14, 5)) Box(Modifier.width(4.dp).height(h.dp).background(color))
    }
}

/**
 * How wide a list's column of row numbers is: its widest number in
 * [PhaseType.Data], and never narrower than two digits or [PlayingBars], so
 * titles stay in line past 99 and past 999.
 */
@Composable
fun numberColumn(largest: Int): Dp {
    val digits = maxOf(2, largest.toString().length)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(digits, density) {
        with(density) { measurer.measure("0".repeat(digits), PhaseType.Data).size.width.toDp() }.coerceAtLeast(22.dp)
    }
}

/** A row's number, zero-padded to two digits, in a column [numberColumn] made wide enough. */
@Composable
fun RowNumber(number: Int, width: Dp, modifier: Modifier = Modifier, color: Color = PhaseColor.Text3) {
    Text(number.toString().padStart(2, '0'), modifier.width(width), color = color, style = PhaseType.Data, maxLines = 1, softWrap = false)
}

/** A mark, not a plane: being on the phone is the common case (§6.8). */
@Composable
fun OnPhoneMark(modifier: Modifier = Modifier) {
    PhaseIcon(PhaseIcons.OnPhone, "On this phone", modifier, size = 16.dp, tone = IconTone.Faint)
}

/** A person with no picture: their initial in a circle. */
@Composable
fun Initial(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, color: Color = PhaseColor.Lift) {
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase(Locale.ROOT), color = PhaseColor.Text1,
            style = PhaseType.Name.copy(fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.4f).sp),
        )
    }
}

/** Several people on one thing: circles overlapping, the owner first. */
@Composable
fun People(names: List<String>, modifier: Modifier = Modifier, size: Dp = 24.dp, ring: Color = PhaseColor.Ground) {
    if (names.isEmpty()) return
    val step = size - size / 4
    Box(modifier.width(step * (names.size - 1) + size).height(size)) {
        names.forEachIndexed { i, name ->
            Initial(
                name, Modifier.offset(x = step * i).border(2.dp, ring, CircleShape), size = size,
            )
        }
    }
}

/**
 * A single subject's ambience (§6.7): its cover, blurred, behind a scrim that
 * never changes, fading to the ground. A tiny copy of the cover drawn large
 * is the blur, so it costs nothing per frame and looks the same on every
 * Android version; where the system can blur, it smooths it further.
 */
@Composable
fun Ambience(model: Any?, modifier: Modifier = Modifier, fadeTo: Color = PhaseColor.Ground, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier) {
        if (model != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(model).size(24).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.matchParentSize().blur(32.dp),
            )
            Box(Modifier.matchParentSize().background(PhaseColor.Scrim))
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(0.55f to fadeTo.copy(alpha = 0f), 1f to fadeTo)),
            )
        }
        content()
    }
}

/** A raised slab holding a group of things: a setting, an invitation, a file. */
@Composable
fun Slab(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(PhaseShape.Slab).background(PhaseColor.Raised).padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** A row's tappable surface: inset 8 from the screen's edge, so a selected row's fill keeps a margin. */
fun Modifier.rowSurface(selected: Boolean): Modifier =
    this
        .fillMaxWidth()
        .padding(horizontal = 8.dp)
        .clip(PhaseShape.Square)
        .background(if (selected) PhaseColor.Sine else Color.Transparent)

/** The colour of a row's text: ink when selected, dimmed when it can't play. */
fun rowInk(selected: Boolean, dimmed: Boolean, secondary: Boolean = false): Color = when {
    selected -> PhaseColor.Ink
    dimmed -> PhaseColor.Text3
    secondary -> PhaseColor.Text2
    else -> PhaseColor.Text1
}

/** A section's name over a list: mono label, generous space above. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, end: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 6.dp)) {
        PhaseLabel(text, Modifier.weight(1f))
        if (end != null) PhaseLabel(end)
    }
}

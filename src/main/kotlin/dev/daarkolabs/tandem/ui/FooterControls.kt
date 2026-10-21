package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import dev.daarkolabs.tandem.chat.ComposerModel
import dev.daarkolabs.tandem.chat.Modes
import dev.daarkolabs.tandem.chat.shownModel
import dev.daarkolabs.tandem.protocol.PermissionMode
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator
import kotlin.math.max

/** What the model control reads while nothing is known about the model yet. */
private const val NO_MODEL_LABEL: String = "Model"

/** What the mode control reads before the first `system/init` or a pick. */
private const val NO_MODE_LABEL: String = "Mode"

/** The efforts the effort submenu lists while the CLI has not listed its models yet, for a known effort (spec 5, edge 1). */
private val EFFORTS_BEFORE_MODELS: List<String> = listOf("low", "medium", "high", "max")

/** The footer's model control: [model] in the secondary tier, then [effort] in the dim tier when there is one. */
internal data class ModelLabel(val model: String, val effort: String?)

/**
 * What the model control reads (spec 2.3, `<Model>` and `<Effort>`): the shown model's display name ([shownModel]), else the model the
 * CLI reports, else "Model"; and the effort, unless there is none or the effort submenu would not list it ([effortMenu]: a shown model
 * without effort levels).
 */
internal fun modelLabel(composer: ComposerModel): ModelLabel {
    val shown = shownModel(composer.models, composer.requestedModel, composer.activeModel, composer.modelSwitchPending)
    val effort = composer.effort?.takeIf { effortMenu(composer) != null }?.let(Modes::effortLabel)
    return ModelLabel(shown?.displayName ?: composer.activeModel ?: NO_MODEL_LABEL, effort)
}

/** What the mode control reads: the session's mode by its footer label, or "Mode" while there is none. */
internal fun modeLabel(composer: ComposerModel): String = composer.permissionMode?.let(Modes::label) ?: NO_MODE_LABEL

/**
 * An item of the model menu: [value] is what `set_model` sends (null: no pick, the CLI's default), [isDefault] whether it carries the
 * `Default` chip, [checked] whether it is the shown model, and [digit] the key that picks it (none on the checked one).
 */
internal data class ModelItem(val value: String?, val label: String, val isDefault: Boolean, val checked: Boolean, val digit: Int?)

/**
 * The model menu (spec 2.3): every model of the CLI's list but its `default` entry, in its order. The entry that resolves to the default
 * model carries the chip; picking it sends no model while that model runs or nothing runs yet, and its value while another model
 * runs, so that the pick is never ignored. The shown model is checked in place of its digit; the first nine items have digits. A
 * requested model that no entry stands for is listed last, by its value. Empty while the CLI has not listed its models.
 */
internal fun modelMenu(composer: ComposerModel): List<ModelItem> {
    if (composer.models.isEmpty()) return emptyList()
    val shown = shownModel(composer.models, composer.requestedModel, composer.activeModel, composer.modelSwitchPending)
    val default = composer.defaultModel
    val defaultRuns = composer.activeModel == null || composer.activeModel == default
    val entries = composer.models.filter { it.value != "default" }
    val items = entries.map { option ->
        val isDefault = default != null && option.resolvedModel == default
        Triple(if (isDefault && defaultRuns) null else option.value, option.displayName, isDefault) to (option == shown)
    }
    val requested = composer.requestedModel
    val extra = if (requested != null && entries.none { it.value == requested }) listOf(Triple(requested, requested, false) to false) else emptyList()
    return (items + extra).mapIndexed { index, (item, checked) ->
        val (value, label, isDefault) = item
        ModelItem(value, label, isDefault, checked, digit = (index + 1).takeIf { it <= 9 && !checked })
    }
}

/** An item of the mode menu: the [mode], its [label] and [description], whether it is the session's ([checked]) and its [digit]. */
internal data class ModeItem(val mode: PermissionMode, val label: String, val description: String?, val checked: Boolean, val digit: Int?)

/** The mode menu (spec 2.3): the modes [Modes.offered] lists for the session's mode and the shown model, the session's one checked. */
internal fun modeMenu(composer: ComposerModel): List<ModeItem> =
    Modes.offered(composer.permissionMode, composer.autoSupported).map { mode ->
        ModeItem(mode, Modes.label(mode), Modes.description(mode), checked = mode == composer.permissionMode, digit = Modes.digit(mode))
    }

/** An item of the effort submenu: a [level], its [label], whether it is the session's ([checked]), and whether it can be picked. */
internal data class EffortItem(val level: String, val label: String, val checked: Boolean, val enabled: Boolean)

/** The effort submenu: its [trailing] text, the current level's label or "Default", and its [items]. */
internal data class EffortMenu(val trailing: String, val items: List<EffortItem>)

/**
 * The effort submenu (spec 2.3), or null when the model menu has none: the shown model's levels, the current one checked. While the
 * CLI has not listed its models, a known effort lists low, medium, high and max (edge 1). A current level the shown model does not
 * list comes last, checked and disabled: the CLI decides at the next turn.
 */
internal fun effortMenu(composer: ComposerModel): EffortMenu? {
    val effort = composer.effort
    val levels = when {
        composer.effortLevels.isNotEmpty() -> composer.effortLevels
        composer.models.isEmpty() && effort != null -> EFFORTS_BEFORE_MODELS
        else -> return null
    }
    val items = levels.map { EffortItem(it, Modes.effortLabel(it), checked = it == effort, enabled = true) }
    val unlisted = if (effort != null && effort !in levels) listOf(EffortItem(effort, Modes.effortLabel(effort), checked = true, enabled = false)) else emptyList()
    return EffortMenu(effort?.let(Modes::effortLabel) ?: "Default", items + unlisted)
}

/** The item a digit key picks: the one whose own digit it is, never the one at that position (Review Focus 2). */
internal fun <T> byDigit(items: List<T>, digit: Int, digitOf: (T) -> Int?): T? = items.firstOrNull { digitOf(it) == digit }

/**
 * Where a footer menu goes (spec 2.3, "Menu placement"): its bottom [gapPx] above the [anchor]'s top, its left edge on the anchor's,
 * or its right edge on the anchor's when [alignEnd], clamped into the [window].
 */
internal fun aboveAnchor(anchor: IntRect, window: IntSize, popup: IntSize, alignEnd: Boolean, gapPx: Int): IntOffset {
    val x = (if (alignEnd) anchor.right - popup.width else anchor.left).coerceIn(0, max(0, window.width - popup.width))
    val y = (anchor.top - gapPx - popup.height).coerceAtLeast(0)
    return IntOffset(x, y)
}

/** Places a popup above its anchor ([aboveAnchor]). */
internal class AboveAnchor(val alignEnd: Boolean, val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        aboveAnchor(anchorBounds, windowSize, popupContentSize, alignEnd, gapPx)
}

/** The digit 1 to 9 of a key pressed, from the number row or the keypad; null for any other key or event. */
private fun digitOf(event: KeyEvent): Int? {
    if (event.type != KeyEventType.KeyDown) return null
    return when (event.key) {
        Key.One, Key.NumPad1 -> 1
        Key.Two, Key.NumPad2 -> 2
        Key.Three, Key.NumPad3 -> 3
        Key.Four, Key.NumPad4 -> 4
        Key.Five, Key.NumPad5 -> 5
        Key.Six, Key.NumPad6 -> 6
        Key.Seven, Key.NumPad7 -> 7
        Key.Eight, Key.NumPad8 -> 8
        Key.Nine, Key.NumPad9 -> 9
        else -> null
    }
}

/**
 * The footer's mode control (spec 2.3): the session's mode as plain text in the secondary tier, which opens the mode menu above it,
 * left-aligned. A pick other than the checked mode calls [onPick].
 */
@Composable
internal fun ModeControl(composer: ComposerModel, onPick: (PermissionMode) -> Unit) {
    val palette = chatPalette()
    val items = remember(composer.permissionMode, composer.autoSupported) { modeMenu(composer) }
    val currentOnPick by rememberUpdatedState(onPick)
    fun pick(item: ModeItem) {
        if (!item.checked) currentOnPick(item.mode)
    }
    FooterControl(
        description = "Permission mode",
        alignEnd = false,
        digitPick = { digit -> byDigit(items, digit, ModeItem::digit)?.let(::pick) != null },
        label = { Text(modeLabel(composer), style = smallTextStyle(), color = palette.secondary, maxLines = 1) },
    ) { close ->
        passiveItem { Text("Mode", style = smallTextStyle(), color = palette.dim) }
        for (item in items) {
            selectableItem(selected = false, onClick = { close(); pick(item) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(item.label)
                        item.description?.let { Text(it, style = smallTextStyle(), color = palette.dim) }
                    }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(24.dp))
                    if (item.checked) Text("✓", color = palette.accent)
                    item.digit?.let {
                        Spacer(Modifier.width(6.dp))
                        Text("$it", color = palette.dim)
                    }
                }
            }
        }
    }
}

/**
 * The footer's model · effort control (spec 2.3): the shown model in the secondary tier and the effort in the dim tier ([modelLabel]),
 * which opens the model menu above it, right-aligned: the models ([modelMenu]), then the effort submenu ([effortMenu]). A model pick
 * other than the checked one calls [onPickModel] with what `set_model` sends; an effort pick other than the current one calls
 * [onPickEffort].
 */
@Composable
internal fun ModelControl(
    composer: ComposerModel,
    modifier: Modifier = Modifier,
    onPickModel: (String?) -> Unit,
    onPickEffort: (String) -> Unit,
) {
    val palette = chatPalette()
    val models = remember(composer) { modelMenu(composer) }
    val efforts = remember(composer) { effortMenu(composer) }
    val label = remember(composer) { modelLabel(composer) }
    val currentOnPickModel by rememberUpdatedState(onPickModel)
    val currentOnPickEffort by rememberUpdatedState(onPickEffort)
    fun pick(item: ModelItem) {
        if (!item.checked) currentOnPickModel(item.value)
    }
    val style = smallTextStyle()
    val text = modelText(label, style, palette)
    FooterControl(
        description = "Model",
        alignEnd = true,
        modifier = modifier,
        opens = models.isNotEmpty() || efforts != null,
        digitPick = { digit -> byDigit(models, digit, ModelItem::digit)?.let(::pick) != null },
        label = { Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    ) { close ->
        for (item in models) {
            selectableItem(selected = false, onClick = { close(); pick(item) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label)
                    if (item.isDefault) {
                        Spacer(Modifier.width(6.dp))
                        DefaultChip(palette)
                    }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(24.dp))
                    if (item.checked) Text("✓", color = palette.accent) else item.digit?.let { Text("$it", color = palette.dim) }
                }
            }
        }
        if (efforts != null) {
            if (models.isNotEmpty()) separator()
            submenu(
                submenu = {
                    for (effort in efforts.items) {
                        selectableItem(
                            selected = false,
                            enabled = effort.enabled,
                            onClick = {
                                close()
                                if (!effort.checked) currentOnPickEffort(effort.level)
                            },
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(effort.label)
                                Spacer(Modifier.weight(1f))
                                Spacer(Modifier.width(24.dp))
                                if (effort.checked) Text("✓", color = palette.accent)
                            }
                        }
                    }
                },
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Effort")
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(24.dp))
                    Text(efforts.trailing, color = palette.dim)
                }
            }
        }
    }
}

/**
 * The model label as one text: the model in the secondary tier, a space widened to about 9 dp, and the effort in the dim tier
 * (spec 2.3, desktop-02's 19 px at 2x between `Sonnet 5.5` and `Medium`).
 */
@Composable
private fun modelText(label: ModelLabel, style: TextStyle, palette: ChatPalette): AnnotatedString {
    val effort = label.effort ?: return AnnotatedString(label.model, SpanStyle(color = palette.secondary))
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widen = remember(style, density) {
        // A space's advance: what it adds between two letters.
        val space = measurer.measure("a a", style).size.width - measurer.measure("aa", style).size.width
        with(density) { (9.dp.toPx() - space).coerceAtLeast(0f).toSp() }
    }
    return buildAnnotatedString {
        withStyle(SpanStyle(color = palette.secondary)) { append(label.model) }
        withStyle(SpanStyle(letterSpacing = widen)) { append(' ') }
        withStyle(SpanStyle(color = palette.dim)) { append(effort) }
    }
}

/** The chip on the model menu's item for the default model (spec 2.3): `Default` on the bubble fill, smaller than the item. */
@Composable
private fun DefaultChip(palette: ChatPalette) {
    val small = smallTextStyle()
    Box(Modifier.background(palette.bubbleFill, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text("Default", style = small.copy(fontSize = (small.fontSize.value - 1).sp), color = palette.secondary, maxLines = 1)
    }
}

/**
 * One footer control (spec 2.3): [label] on a pill that fills while hovered or while its menu is open and is outlined while focused,
 * semantically a drop-down named [description]. A click, Enter or Space opens its menu above it (left- or right-aligned by
 * [alignEnd]), unless it has nothing to list ([opens]); [menu] fills it and closes it with the function it is given. A digit picks
 * through [digitPick], which says whether it picked: in the menu, and on the control itself while the menu is open, in case the
 * open menu does not get the key (open item 13).
 */
@Composable
private fun FooterControl(
    description: String,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
    opens: Boolean = true,
    digitPick: (Int) -> Boolean,
    label: @Composable () -> Unit,
    menu: MenuScope.(close: () -> Unit) -> Unit,
) {
    val palette = chatPalette()
    val colors = JewelTheme.globalColors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    var open by remember { mutableStateOf(false) }
    val close = { open = false }
    fun picked(event: KeyEvent): Boolean {
        val digit = digitOf(event) ?: return false
        if (digitPick(digit)) open = false
        return true
    }
    val shape = RoundedCornerShape(6.dp)
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(
        modifier
            .semantics(mergeDescendants = true) {
                role = Role.DropdownList
                contentDescription = description
            }
            .onPreviewKeyEvent { event ->
                when {
                    open && picked(event) -> true
                    !open && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar) -> {
                        open = opens
                        true
                    }
                    open && event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                        open = false
                        true
                    }
                    else -> false
                }
            }
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.DropdownList) { open = !open && opens }
            .then(if (hovered || open) Modifier.background(palette.bubbleFill, shape) else Modifier)
            .then(if (focused) Modifier.border(1.dp, colors.outlines.focused, shape) else Modifier)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        label()
        if (open) {
            PopupMenu(
                onDismissRequest = { mode ->
                    // A click on the control itself closes the menu through the control (its click toggles it).
                    if (mode == InputMode.Touch && hovered) {
                        false
                    } else {
                        open = false
                        true
                    }
                },
                popupPositionProvider = remember(alignEnd, gap) { AboveAnchor(alignEnd, gap) },
                modifier = Modifier.onPreviewKeyEvent { event -> picked(event) },
            ) { menu(close) }
        }
    }
}

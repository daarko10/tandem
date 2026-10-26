package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.daarkolabs.tandem.chat.ContextFormat
import dev.daarkolabs.tandem.chat.DEFAULT_MODEL_ENTRY
import dev.daarkolabs.tandem.chat.Modes
import dev.daarkolabs.tandem.chat.modelDisplayName
import dev.daarkolabs.tandem.chat.shownModel
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ContextUsage
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.copyWithSize
import org.jetbrains.jewel.ui.component.separator
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.max

/** What the model control reads while nothing is known about the model yet. */
private const val NO_MODEL_LABEL: String = "Model"

/** What the mode control reads before the first `system/init` or a pick. */
private const val NO_MODE_LABEL: String = "Mode"

/** The footer's model control: [model] in the secondary tier, then [effort] in the dim tier when there is one. */
internal data class ModelLabel(val model: String, val effort: String?)

/**
 * What the model control reads (spec 2.3, `<Model>` and `<Effort>`): the shown model's display name ([shownModel]), else the model the
 * CLI reports, else the model picked (a session started with a pick, before its first `system/init` and the CLI's model list; the
 * CLI's own `default` entry is no pick), each by its name in the list ([modelDisplayName]: an alias's for a model of its family,
 * the id itself for one the list does not name), else "Model"; and the effort, unless there is none or the effort page would not
 * list it ([effortMenu]: a shown model without effort levels; an alias's levels are unknown, so it keeps the effort).
 */
internal fun modelLabel(composer: ComposerModel): ModelLabel {
    val shown = shownModel(composer.models, composer.requestedModel, composer.activeModel, composer.modelSwitchPending)
    val effort = composer.effort?.takeIf { effortMenu(composer) != null }?.let(Modes::effortLabel)
    val picked = composer.requestedModel?.takeIf { it != DEFAULT_MODEL_ENTRY }
    val model = shown?.displayName ?: (composer.activeModel ?: picked)?.let { modelDisplayName(it, composer.models) } ?: NO_MODEL_LABEL
    return ModelLabel(model, effort)
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
 * runs, so that the pick is never ignored. A `default` entry that resolves to no model (the CLI's aliases,
 * [dev.daarkolabs.tandem.session.CLI_MODEL_ALIASES]) has no entry to badge, so it is listed in its place, by its name: picking it
 * sends no model, and it is checked while no model is picked. The shown model is checked in place of its digit; the first nine items
 * have digits. A requested model that no entry stands for is listed last, by its value. Empty only for a composer without models,
 * which [dev.daarkolabs.tandem.chat.composerModel] never gives: it falls back to the models another process listed, then to the
 * CLI's aliases.
 */
internal fun modelMenu(composer: ComposerModel): List<ModelItem> {
    if (composer.models.isEmpty()) return emptyList()
    val shown = shownModel(composer.models, composer.requestedModel, composer.activeModel, composer.modelSwitchPending)
    val default = composer.defaultModel
    val defaultRuns = composer.activeModel == null || composer.activeModel == default
    // The new-session popup can pick the CLI's own `default` entry: that one is the Default-badged item, not an item of its own.
    val requested = composer.requestedModel?.takeIf { it != DEFAULT_MODEL_ENTRY }
    val entries = composer.models.filter { it.value != DEFAULT_MODEL_ENTRY }
    val items = composer.models.mapNotNull { option ->
        when {
            option.value != DEFAULT_MODEL_ENTRY -> {
                val isDefault = default != null && option.resolvedModel == default
                Triple(if (isDefault && defaultRuns) null else option.value, option.displayName, isDefault) to (option == shown)
            }
            option.resolvedModel == null -> Triple(null, option.displayName, false) to (requested == null)
            else -> null
        }
    }
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

/** An item of the effort page: a [level], its [label], whether it is the session's ([checked]), and whether it can be picked. */
internal data class EffortItem(val level: String, val label: String, val checked: Boolean, val enabled: Boolean)

/** The effort page: the `Effort` row's [trailing] text, the current level's label or "Default", and its [items]. */
internal data class EffortMenu(val trailing: String, val items: List<EffortItem>)

/**
 * The effort page (spec 2.3), or null when the model menu has none: the shown model's levels, the current one checked. While no
 * entry stands for the model (the list is the CLI's aliases, which name no model, or the model it runs is none of the list's), or the
 * shown entry is an alias (no `resolvedModel`: its levels are unknown, not absent), a known effort lists low, medium, high and max
 * (edge 1): only a shown model without effort levels hides the page (edge 15). A current level the shown model does not list comes
 * last, checked and disabled: the CLI decides at the next turn.
 */
internal fun effortMenu(composer: ComposerModel): EffortMenu? {
    val effort = composer.effort
    val shown = shownModel(composer.models, composer.requestedModel, composer.activeModel, composer.modelSwitchPending)
    val levels = when {
        composer.effortLevels.isNotEmpty() -> composer.effortLevels
        (shown == null || shown.resolvedModel == null) && effort != null -> EFFORT_LEVELS
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
 * or its right edge on the anchor's when [alignEnd], clamped into the [window]. The anchor is the layout the popup is placed in, so a
 * control places its popup in a box whose bounds are the pill's, not inside the pill's padding (see `FooterControl`).
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

/**
 * The digit 1 to 9 that an AWT key event stands for, from the number row or the keypad, without Shift, Ctrl, Alt or Meta; null for
 * any other key. A typed event has it as its character, a pressed or released one as its key code.
 */
internal fun digitOf(event: AwtKeyEvent): Int? {
    if (event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown) return null
    if (event.id == AwtKeyEvent.KEY_TYPED) return event.keyChar.takeIf { it in '1'..'9' }?.digitToInt()
    return when (event.keyCode) {
        in AwtKeyEvent.VK_1..AwtKeyEvent.VK_9 -> event.keyCode - AwtKeyEvent.VK_0
        in AwtKeyEvent.VK_NUMPAD1..AwtKeyEvent.VK_NUMPAD9 -> event.keyCode - AwtKeyEvent.VK_NUMPAD0
        else -> null
    }
}

/**
 * Whether an open footer menu takes a key from [component], the AWT component the key event names (the focus owner): only one in
 * [panel], the tool window's own Compose panel ([LocalComponent]), which holds the menu as a layer of its scene, not as a window of
 * its own (open item 13). A key typed in the editor, the terminal, another tool window, a dialog or an IDE popup names a component
 * outside the panel, so the menu leaves it alone: it never picks a model or a mode from a keystroke meant for something else.
 */
internal fun takesKey(component: Component?, panel: JComponent): Boolean = component != null && SwingUtilities.isDescendingFrom(component, panel)

/**
 * Whether the AWT focus has left [panel] for [owner], the new focus owner a `focusOwner` change names, which closes an open footer
 * menu: the menu gets no click from Swing components outside the panel, so it would stay open under the editor otherwise. No owner
 * (the IDE window lost the focus) is not a move: the menu stays, and no key reaches it meanwhile.
 *
 * Only a real move counts: [owner] must be [actualOwner], the focus owner the `KeyboardFocusManager` reports, which it sets before it
 * fires the change. On macOS, Compose also sends every `focusOwner` listener a change of its own whenever a Compose node takes the
 * focus (for VoiceOver: skiko's `NativeAccessibleFocusHelper.requestMacOSFocusOnAccessible` in IDE 2026.1, Compose's own
 * `androidx.compose.ui.platform.a11y.AccessibleFocusHelper` in 2026.2), naming a parentless `ComposeAccessible` while the focus stays
 * where it was. A menu item takes the focus on hover and on the arrow keys, so that event alone closed the menus at the first item the
 * pointer crossed.
 */
internal fun focusLeft(owner: Component?, actualOwner: Component?, panel: JComponent): Boolean =
    owner != null && owner === actualOwner && !takesKey(owner, panel)

/**
 * The `focusOwner` listener an open footer menu registers: calls [close] when the focus has left [panel] ([focusLeft]), with
 * [focusOwner] the focus owner as the `KeyboardFocusManager` reports it when the change arrives.
 */
internal fun focusLeftListener(panel: JComponent, focusOwner: () -> Component?, close: () -> Unit): PropertyChangeListener =
    PropertyChangeListener { change -> if (focusLeft(change.newValue as? Component, focusOwner(), panel)) close() }

/**
 * A footer control's pill (spec 2.3): [pillSurface], then 7 dp by 3 dp inside. The context ring takes the same surface as a 23 dp
 * square instead ([ContextRing]), and places its popover beside the pill, not inside it, as `FooterControl` does.
 */
@Composable
internal fun Modifier.footerPill(filled: Boolean, focused: Boolean): Modifier =
    pillSurface(filled, focused).padding(horizontal = 7.dp, vertical = 3.dp)

/** A pill's surface (spec 2.3): the bubble fill at a 6 dp radius when [filled] (hovered, or its menu open), a 1 dp focus outline when [focused]. */
@Composable
private fun Modifier.pillSurface(filled: Boolean, focused: Boolean): Modifier {
    val shape = RoundedCornerShape(6.dp)
    return this
        .then(if (filled) Modifier.background(chatPalette().bubbleFill, shape) else Modifier)
        .then(if (focused) Modifier.border(1.dp, JewelTheme.globalColors.outlines.focused, shape) else Modifier)
}

/**
 * The footer's context ring (spec 2.3), the last control after a 5 dp gap: how full the context window is, as an accent arc over a
 * dim track, with the tooltip `Context <value>` ([ContextFormat.value]). A click, Enter or Space opens its popover above it,
 * right-aligned: with [popover], the context window's breakdown ([ContextPopover], desktop-parity spec 3.1), else `Context window`
 * and the value. It composes nothing, gap included, while the tokens or the window are unknown
 * ([ContextFormat.fraction] is null: before the first `result`, and on a reloaded transcript). It alone collects [context], so a
 * `message_start` recomposes the ring and nothing else of the footer.
 */
@Composable
internal fun ContextRing(context: StateFlow<ContextUsage?>, popover: ContextPopoverHost? = null) {
    val usage by context.collectAsState()
    val current = usage ?: return
    val fraction = ContextFormat.fraction(current) ?: return
    val value = ContextFormat.value(current) ?: return
    Spacer(Modifier.width(5.dp))
    WithTooltip("Context $value") { RingControl(fraction, value, popover) }
}

/**
 * The ring and its popover: a 23 dp pill, filled while hovered or open and outlined while focused, around a 14 dp ring with a 2 dp
 * stroke, the arc clockwise from the top over [fraction] of the turn. As a footer control's menu, the popover closes when the AWT
 * focus really leaves the tool window's panel ([focusLeftListener]), which it gets no click from.
 */
@Composable
private fun RingControl(fraction: Float, value: String, popover: ContextPopoverHost?) {
    val palette = chatPalette()
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    var open by remember { mutableStateOf(false) }
    // Esc closed the popover: the focus goes back to the ring (desktop-parity spec 3.5).
    var refocus by remember { mutableStateOf(false) }
    val ringFocus = remember { FocusRequester() }
    LaunchedEffect(refocus) {
        if (refocus) {
            ringFocus.requestFocus()
            refocus = false
        }
    }
    val panel = LocalComponent.current
    if (open) {
        DisposableEffect(panel) {
            val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            // focusLeftListener, never a check of the new value alone: macOS sends a focus change on hover that moves nothing (focusLeft).
            val focusListener = focusLeftListener(panel, focusManager::getFocusOwner) { open = false }
            focusManager.addPropertyChangeListener("focusOwner", focusListener)
            onDispose { focusManager.removePropertyChangeListener("focusOwner", focusListener) }
        }
    }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(
        Modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Context window"
                stateDescription = value
            }
            .focusRequester(ringFocus)
            .onPreviewKeyEvent { event ->
                when {
                    !open && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar) -> {
                        open = true
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
            .clickable(interactionSource = source, indication = null, role = Role.Button) { open = !open },
    ) {
        Box(Modifier.size(23.dp).pillSurface(filled = hovered || open, focused = focused), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(14.dp)) {
                val stroke = Stroke(width = 2.dp.toPx())
                // Inset by half the stroke, so that the ink stays inside the 14 dp.
                val inset = Offset(stroke.width / 2, stroke.width / 2)
                val arc = Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(palette.dim, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = inset, size = arc, style = stroke)
                drawArc(palette.accent, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false, topLeft = inset, size = arc, style = stroke)
            }
        }
        if (open && popover != null) {
            // One controller per open popover: its requests stop when the popover closes (desktop-parity spec 3.1, 4.4).
            val scope = rememberCoroutineScope()
            val controller = remember(popover.open) { popover.open(scope) }
            DisposableEffect(controller) { onDispose { controller.close() } }
            val currentHovered by rememberUpdatedState(hovered)
            ContextPopover(popover, controller, gap, anchorHovered = { currentHovered }) { byKeyboard ->
                open = false
                refocus = byKeyboard
            }
        } else if (open) {
            PopupMenu(
                onDismissRequest = { mode ->
                    // A click on the ring itself closes the popover through the ring (its click toggles it).
                    if (mode == InputMode.Touch && hovered) {
                        false
                    } else {
                        open = false
                        true
                    }
                },
                popupPositionProvider = remember(gap) { AboveAnchor(alignEnd = true, gapPx = gap) },
            ) {
                passiveItem {
                    Row(Modifier.widthIn(min = 280.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                        Text("Context window", style = smallTextStyle(), color = palette.dim)
                        Spacer(Modifier.weight(1f))
                        Text(value, style = smallTextStyle(), color = palette.dim)
                    }
                }
            }
        }
    }
}

/**
 * The footer's mode control (spec 2.3): the session's mode as plain text in the secondary tier, which opens the mode menu above it,
 * left-aligned. A pick other than the checked mode calls [onPick]. A [FooterMenu.MODE] in [menuRequest] opens the menu too
 * (`/permissions`, `/mode`: MCP spec 3.4).
 */
@Composable
internal fun ModeControl(composer: ComposerModel, onPick: (PermissionMode) -> Unit, menuRequest: MutableState<FooterMenu?>? = null) {
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
        request = menuRequest,
        requestedAs = FooterMenu.MODE,
    ) { menu ->
        passiveItem { Text("Mode", style = smallTextStyle(), color = palette.dim) }
        for (item in items) {
            selectableItem(selected = false, onClick = { menu.close(); pick(item) }) {
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
 * which opens the model menu above it, right-aligned: the models ([modelMenu]), then the `Effort` row, which turns the menu to its
 * effort page ([effortMenu]): `‹ Effort`, which leads back, then the levels. A model pick other than the checked one calls
 * [onPickModel] with what `set_model` sends; an effort pick other than the current one calls [onPickEffort]. A [FooterMenu.MODEL] in
 * [menuRequest] opens the menu too (`/model`: MCP spec 3.4).
 */
@Composable
internal fun ModelControl(
    composer: ComposerModel,
    onPickModel: (String?) -> Unit,
    onPickEffort: (String) -> Unit,
    menuRequest: MutableState<FooterMenu?>? = null,
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
        opens = models.isNotEmpty() || efforts != null,
        pages = if (efforts != null) 2 else 1,
        digitPick = { digit -> byDigit(models, digit, ModelItem::digit)?.let(::pick) != null },
        label = { Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        request = menuRequest,
        requestedAs = FooterMenu.MODEL,
    ) { menu ->
        if (efforts != null && menu.page == EFFORT_PAGE) {
            effortPage(efforts, palette, menu) { level -> currentOnPickEffort(level) }
        } else {
            for (item in models) {
                selectableItem(selected = false, onClick = { menu.close(); pick(item) }) {
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
                // Tandem's own row, not Jewel's `submenu`: that one opens on hover, and this last row is the first one a pointer
                // moving up from the control crosses on its way to a model (open item 13).
                selectableItem(selected = false, onClick = { menu.show(EFFORT_PAGE) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Effort")
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(24.dp))
                        Text(efforts.trailing, color = palette.dim)
                        Spacer(Modifier.width(6.dp))
                        Text("›", color = palette.dim)
                    }
                }
            }
        }
    }
}

/** The model menu's second page, the effort levels. */
private const val EFFORT_PAGE: Int = 1

/**
 * The model menu's effort page (spec 2.3): `‹ Effort`, which leads back to the models, a separator, then the levels, the current one
 * checked. A pick other than the current level closes the menu and calls [onPick] with it.
 */
private fun MenuScope.effortPage(efforts: EffortMenu, palette: ChatPalette, menu: FooterMenuHandle, onPick: (String) -> Unit) {
    selectableItem(selected = false, onClick = { menu.show(FIRST_PAGE) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = palette.dim)
            Spacer(Modifier.width(6.dp))
            Text("Effort")
        }
    }
    separator()
    for (effort in efforts.items) {
        selectableItem(
            selected = false,
            enabled = effort.enabled,
            onClick = {
                menu.close()
                if (!effort.checked) onPick(effort.level)
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
        Text("Default", style = small.copyWithSize((small.fontSize.value - 1).sp), color = palette.secondary, maxLines = 1)
    }
}

/** The page a footer menu opens on, the one its digits pick from. */
private const val FIRST_PAGE: Int = 0

/**
 * What a footer menu's items get: the [page] the menu shows, [close], which closes the menu, and [show], which turns it to another
 * page in place, the menu staying open.
 */
internal class FooterMenuHandle(val page: Int, val close: () -> Unit, val show: (Int) -> Unit)

/** A footer control whose menu `/model`, `/permissions` or `/mode` asks to open ([ChatUiState.menuRequest], MCP spec 4.4). */
internal enum class FooterMenu { MODE, MODEL }

/**
 * The page the arrow key of an AWT key press turns to from [page], or null for any other key or a press with Shift, Ctrl, Alt or
 * Meta: → the next page, ← the one before, as Jewel's submenus open and close on them.
 */
internal fun pageTurn(event: AwtKeyEvent, page: Int): Int? {
    if (event.id != AwtKeyEvent.KEY_PRESSED || event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown) return null
    return when (event.keyCode) {
        AwtKeyEvent.VK_RIGHT -> page + 1
        AwtKeyEvent.VK_LEFT -> page - 1
        else -> null
    }
}

/**
 * One footer control (spec 2.3): [label] on a pill that fills while hovered or while its menu is open and is outlined while focused,
 * semantically a drop-down named [description]. A click, Enter or Space opens its menu above it (left- or right-aligned by
 * [alignEnd]) on its first page, unless it has nothing to list ([opens]); [menu] fills the page it is given and closes the menu, or
 * turns it to another of its [pages], with the [FooterMenuHandle] it is given. While the first page is open a digit picks through
 * [digitPick], which says whether it picked, and the menu closes when it did; → and ← turn the pages. A [request] equal to
 * [requestedAs] (`/model`, `/permissions`, `/mode`) opens the menu as a click would and clears the request; with nothing to list
 * ([opens] false) the control takes the focus instead, so the command visibly lands somewhere (MCP spec 11 item 6).
 *
 * The digits and the arrows are taken from AWT's key dispatch while the menu is open, not from a Compose key handler (open item 13):
 * in the IDE the menu is a focusable popup layer of the tool window's own Compose scene (the IDE leaves Jewel's `JBPopupRenderer`
 * off), which takes the keys with no Compose node focused in it, so neither a handler on the menu nor one on the control gets them.
 * Only keys in the tool window's own panel are taken ([takesKey]), and the menu closes when the AWT focus really leaves that panel
 * ([focusLeftListener]): the layer gets no click from Swing components outside it, so a menu left open under the editor would
 * otherwise take the digits and arrows typed there. The focus change Compose sends on macOS when a menu item takes the Compose focus
 * (on hover, or an arrow key) moves nothing, and leaves the menu open ([focusLeft]).
 *
 * The menu is placed in the control's outer box, which has no padding of its own, so that [AboveAnchor] measures from the pill's
 * edges: placed inside the pill's padding, it sat 5 dp above the pill and 7 dp inside its right edge.
 */
@Composable
private fun FooterControl(
    description: String,
    alignEnd: Boolean,
    opens: Boolean = true,
    pages: Int = 1,
    digitPick: (Int) -> Boolean,
    label: @Composable () -> Unit,
    request: MutableState<FooterMenu?>? = null,
    requestedAs: FooterMenu? = null,
    menu: MenuScope.(FooterMenuHandle) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(FIRST_PAGE) }
    // Jewel asks to close the menu after every item's click; a page turn holds it open through that one request.
    val turning = remember { mutableStateOf(false) }
    val currentPages by rememberUpdatedState(pages)
    val currentDigitPick by rememberUpdatedState(digitPick)
    fun openMenu() {
        page = FIRST_PAGE
        turning.value = false
        open = opens
    }
    val close = { open = false }
    // `/model`, `/permissions` or `/mode` asked for this control's menu (MCP spec 4.4): it opens as a click would, and the request
    // is cleared, so the same command opens it again next time. A menu with nothing to list does not open; the control takes the
    // focus instead (MCP spec 11 item 6), rather than the command vanishing without a trace.
    val focusRequester = remember { FocusRequester() }
    val requested = request?.value
    LaunchedEffect(requested) {
        if (requested != null && requested == requestedAs) {
            if (opens) openMenu() else focusRequester.requestFocus()
            request.value = null
        }
    }
    val show = { next: Int ->
        page = next
        turning.value = true
    }
    val panel = LocalComponent.current
    if (open) {
        DisposableEffect(panel) {
            // Every event of a digit key in the panel is taken while the menu is open, so that its typed character lands nowhere; the
            // press picks. An arrow's press is taken when it turns a page. A key in any other component is left alone.
            val dispatcher = KeyEventDispatcher { event ->
                if (!takesKey(event.component, panel)) return@KeyEventDispatcher false
                val digit = digitOf(event)
                if (digit != null) {
                    if (event.id == AwtKeyEvent.KEY_PRESSED && page == FIRST_PAGE && currentDigitPick(digit)) open = false
                    return@KeyEventDispatcher true
                }
                val next = pageTurn(event, page)?.takeIf { it in 0 until currentPages } ?: return@KeyEventDispatcher false
                page = next
                true
            }
            val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            // focusLeftListener, never a check of the new value alone: macOS sends a focus change on hover that moves nothing (focusLeft).
            val focusListener = focusLeftListener(panel, focusManager::getFocusOwner) { open = false }
            focusManager.addKeyEventDispatcher(dispatcher)
            focusManager.addPropertyChangeListener("focusOwner", focusListener)
            onDispose {
                focusManager.removeKeyEventDispatcher(dispatcher)
                focusManager.removePropertyChangeListener("focusOwner", focusListener)
            }
        }
    }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(
        Modifier
            .semantics(mergeDescendants = true) {
                role = Role.DropdownList
                contentDescription = description
            }
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                when {
                    !open && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar) -> {
                        openMenu()
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
            .clickable(interactionSource = source, indication = null, role = Role.DropdownList) { if (open) open = false else openMenu() },
    ) {
        Box(Modifier.footerPill(filled = hovered || open, focused = focused)) { label() }
        if (open) {
            PopupMenu(
                onDismissRequest = { mode ->
                    when {
                        // A page turn: the item's click asks to close, the menu stays on its new page.
                        turning.value -> {
                            turning.value = false
                            false
                        }
                        // A click on the control itself closes the menu through the control (its click toggles it).
                        mode == InputMode.Touch && hovered -> false
                        else -> {
                            open = false
                            true
                        }
                    }
                },
                popupPositionProvider = remember(alignEnd, gap) { AboveAnchor(alignEnd, gap) },
            ) { menu(FooterMenuHandle(page, close, show)) }
        }
    }
}

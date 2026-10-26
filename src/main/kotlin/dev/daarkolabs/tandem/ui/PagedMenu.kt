package dev.daarkolabs.tandem.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.JComponent

// The paging and key handling of a menu that turns pages in place (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 3.3,
// 3.5): the footer's model menu (its Effort page) and the composer's `+` menu. Not Jewel's hover `submenu`, which opens under a pointer
// crossing it on its way to another row (polish open item 13).

/**
 * Whether a paged menu is [open] and which [page] it shows. Used on the EDT only. [openAt] opens it on a page, [close] closes it, and
 * [show] turns it to another page in place from an item's click: Jewel asks to close the menu after every item's click, and [dismiss]
 * holds it open through that one request.
 */
internal class PagedMenuState {
    private val openState = mutableStateOf(false)
    private val pageState = mutableIntStateOf(0)

    // A page turn from a click: Jewel's close request that follows the click is refused once.
    private var holding: Boolean = false

    val open: Boolean get() = openState.value
    val page: Int get() = pageState.intValue

    fun openAt(page: Int = 0) {
        pageState.intValue = page
        holding = false
        openState.value = true
    }

    fun close() {
        holding = false
        openState.value = false
    }

    /** Turns the menu to [page] from an item's click, holding it open through the close request Jewel makes after the click. */
    fun show(page: Int) {
        pageState.intValue = page
        holding = true
    }

    /**
     * Keeps the menu open on its page through the next close request (a switch's click, spec 17.2): Jewel asks to close the menu
     * after every item's click; the request after that one closes it as before.
     */
    fun hold() {
        holding = true
    }

    /** Turns the menu to [page] from a key: no click follows, so no close request is held back. */
    internal fun turnTo(page: Int) {
        pageState.intValue = page
    }

    /** Whether a click item's [show] holds the next close request back. */
    internal val holdsOpen: Boolean get() = holding

    internal fun releaseHold() {
        holding = false
    }
}

/**
 * A paged menu's `onDismissRequest` (Jewel's `PopupMenu`): a page turn ([PagedMenuState.show]) refuses the request that follows its
 * click and the menu stays on its new page; a click on the menu's own anchor ([anchorHovered]) is left to the anchor, whose click
 * toggles the menu; anything else closes it. Says whether the menu closed.
 */
internal fun PagedMenuState.dismiss(mode: InputMode, anchorHovered: Boolean): Boolean = when {
    holdsOpen -> {
        releaseHold()
        false
    }
    mode == InputMode.Touch && anchorHovered -> false
    else -> {
        close()
        true
    }
}

/**
 * The AWT key dispatcher of an open paged menu (polish open item 13): only keys in [panel], the tool window's own Compose panel, are
 * taken ([takesKey]). With [digitPick], every event of a digit key is taken, so its typed character lands nowhere, and its press on
 * the first page picks through [digitPick], which says whether it picked: the menu then closes. An arrow's press that [turn] maps to
 * a page (from the menu's page) turns the menu there and is taken. Anything else is left alone.
 */
internal fun pagedMenuDispatcher(
    state: PagedMenuState,
    panel: JComponent,
    turn: (AwtKeyEvent, Int) -> Int?,
    digitPick: ((Int) -> Boolean)?,
): KeyEventDispatcher = KeyEventDispatcher { event ->
    if (!state.open || !takesKey(event.component, panel)) return@KeyEventDispatcher false
    if (digitPick != null) {
        val digit = digitOf(event)
        if (digit != null) {
            if (event.id == AwtKeyEvent.KEY_PRESSED && state.page == 0 && digitPick(digit)) state.close()
            return@KeyEventDispatcher true
        }
    }
    val next = turn(event, state.page) ?: return@KeyEventDispatcher false
    state.turnTo(next)
    true
}

/**
 * While [state] is open: its AWT key dispatcher ([pagedMenuDispatcher]: digits on the first page through [digitPick], page turns
 * through [turn], keys of [panel] only) and the focus listener that closes it when the focus really leaves [panel]
 * ([focusLeftListener]). The menu is a focusable popup layer of the tool window's own Compose scene, which takes the keys with no
 * Compose node focused in it, so neither a handler on the menu nor one on its anchor gets them; and the layer gets no click from
 * Swing components outside the panel, so a menu left open under the editor would otherwise take the keys typed there.
 */
@Composable
internal fun PagedMenuKeys(state: PagedMenuState, panel: JComponent, turn: (AwtKeyEvent, Int) -> Int?, digitPick: ((Int) -> Boolean)? = null) {
    if (!state.open) return
    val currentTurn by rememberUpdatedState(turn)
    val currentDigitPick by rememberUpdatedState(digitPick)
    val digits = digitPick != null
    DisposableEffect(state, panel, digits) {
        val pick: ((Int) -> Boolean)? = if (digits) { digit -> currentDigitPick?.invoke(digit) ?: false } else null
        val dispatcher = pagedMenuDispatcher(state, panel, { event, page -> currentTurn(event, page) }, pick)
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        // focusLeftListener, never a check of the new value alone: macOS sends a focus change on hover that moves nothing (focusLeft).
        val focusListener = focusLeftListener(panel, focusManager::getFocusOwner) { state.close() }
        focusManager.addKeyEventDispatcher(dispatcher)
        focusManager.addPropertyChangeListener("focusOwner", focusListener)
        onDispose {
            focusManager.removeKeyEventDispatcher(dispatcher)
            focusManager.removePropertyChangeListener("focusOwner", focusListener)
        }
    }
}

/** The `+` menu's first page (spec 3.3, 17.1). */
internal const val PLUS_MAIN: Int = 0

/** The `+` menu's Plugins page (spec 3.3, 17.3). */
internal const val PLUS_PLUGINS: Int = 1

/** The `+` menu's Connectors page (spec 17.2, D15). */
internal const val PLUS_CONNECTORS: Int = 2

/** The `+` menu's page of the first plugin; plugin `i` has `PLUS_FIRST_PLUGIN + i`. */
internal const val PLUS_FIRST_PLUGIN: Int = 3

/**
 * The page an AWT key press turns the `+` menu to from [page] (spec 3.5), or null: ← goes back one page (the Plugins and Connectors
 * pages to the first, a plugin's page to the Plugins page); → is not bound on any page, since the first page has two `›` rows and
 * Jewel's menu does not tell the AWT key dispatch which row is selected; ← on the first page, any other key, a release or a press
 * with Shift, Ctrl, Alt or Meta turns nothing.
 */
internal fun plusMenuTurn(event: AwtKeyEvent, page: Int): Int? {
    if (event.id != AwtKeyEvent.KEY_PRESSED || event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown) return null
    if (event.keyCode != AwtKeyEvent.VK_LEFT) return null
    return when {
        page == PLUS_PLUGINS || page == PLUS_CONNECTORS -> PLUS_MAIN
        page >= PLUS_FIRST_PLUGIN -> PLUS_PLUGINS
        else -> null
    }
}

/**
 * The `+` menu's MCP opener (spec 3.3, 17.2): the menu closes, then the MCP panel opens as a user action ([ChatUiState.openMcpPanel],
 * the opener the header button and `/mcp` use), so a session without a process starts; never `mcpPanelOpen` set directly.
 */
internal fun PagedMenuState.openMcpFromMenu(ui: ChatUiState) {
    close()
    ui.openMcpPanel()
}

/**
 * The `+` menu's width bounds for a chat column [chatColumn] wide (spec 3.3, Size): at most `min(360 dp, chat column − 16 dp)` and at
 * least 240 dp, or that maximum when it is smaller.
 */
internal fun plusMenuWidth(chatColumn: Dp): Pair<Dp, Dp> {
    val max = minOf(PLUS_MENU_MAX_WIDTH, chatColumn - 16.dp).coerceAtLeast(0.dp)
    return minOf(PLUS_MENU_MIN_WIDTH, max) to max
}

/**
 * Whether the `+` menu's `Add files or photos` row draws its shortcut at its end (spec 17.1): only on a menu whose maximum width
 * ([plusMenuWidth]'s second) is at least 300 dp, since Jewel never ellipsises that text and a narrower menu would leave the label no
 * room; narrower, the shortcut goes in the row's tooltip.
 */
internal fun plusShortcutShown(maxWidth: Dp): Boolean = maxWidth >= PLUS_SHORTCUT_MIN_WIDTH

private val PLUS_SHORTCUT_MIN_WIDTH = 300.dp
private val PLUS_MENU_MAX_WIDTH = 360.dp
private val PLUS_MENU_MIN_WIDTH = 240.dp

package dev.daarkolabs.tandem.ui.cards

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.intellij.openapi.util.SystemInfo

/** What a key typed on a card does (spec 4.18): its default answer, its Cmd/Ctrl+Enter answer, or its Esc answer. */
enum class CardKey { ENTER, CMD_ENTER, ESCAPE }

/**
 * What [event] does on a card ([cardKey] with its modifiers read from the event): Shift or Alt held count as one, as do Cmd and
 * Ctrl. [hasCmdEnter]: the card offers a Cmd/Ctrl+Enter answer. [textFieldFocused]: a text field on the card has the focus.
 */
fun cardKey(event: KeyEvent, hasCmdEnter: Boolean, textFieldFocused: Boolean): CardKey? =
    cardKey(
        event.key,
        event.type,
        event.isShiftPressed || event.isAltPressed,
        event.isMetaPressed || event.isCtrlPressed,
        hasCmdEnter,
        textFieldFocused,
    )

/**
 * What [key] going [type] does on a card, with Shift or Alt ([shift]) or Cmd or Ctrl ([cmdOrCtrl]) held (spec 4.18); null leaves
 * the key to what has the focus. Only a key press counts. Enter alone is [CardKey.ENTER], except while a text field on the card has
 * the focus ([textFieldFocused]), which takes it itself; Shift+Enter does nothing. Cmd/Ctrl+Enter is [CardKey.CMD_ENTER] when the
 * card offers it ([hasCmdEnter]), a text field focused or not. Esc is [CardKey.ESCAPE].
 */
internal fun cardKey(key: Key, type: KeyEventType, shift: Boolean, cmdOrCtrl: Boolean, hasCmdEnter: Boolean, textFieldFocused: Boolean): CardKey? {
    if (type != KeyEventType.KeyDown) return null
    return when (key) {
        Key.Enter, Key.NumPadEnter -> when {
            cmdOrCtrl -> if (hasCmdEnter) CardKey.CMD_ENTER else null
            shift || textFieldFocused -> null
            else -> CardKey.ENTER
        }
        Key.Escape -> CardKey.ESCAPE
        else -> null
    }
}

/** The key cap of a card's Cmd/Ctrl+Enter answer: `⌘↩` on macOS ([mac]), `Ctrl↩` elsewhere (spec 4.12, 4.20). */
internal fun cmdEnterCap(mac: Boolean = SystemInfo.isMac): String = if (mac) "⌘↩" else "Ctrl↩"

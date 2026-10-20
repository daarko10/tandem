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
internal fun cardKey(
    key: Key,
    type: KeyEventType,
    shift: Boolean,
    cmdOrCtrl: Boolean,
    hasCmdEnter: Boolean,
    textFieldFocused: Boolean,
): CardKey? {
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

/**
 * Whether a card's button takes [key] for itself, so that the button's own key click (Enter and Space click a focused button) never
 * runs: Enter in any form, since the card root maps Enter to the card's default answer and Shift+Enter does nothing (spec 4.18); and
 * Space on the card's default button ([primary]), whose key is Enter, so that a space typed just as the card took the focus does
 * not answer it. Space still clicks the other buttons, which Tab reaches.
 */
internal fun cardButtonSwallows(key: Key, primary: Boolean): Boolean = when (key) {
    Key.Enter, Key.NumPadEnter -> true
    Key.Spacebar -> primary
    else -> false
}

/** The key cap of a card's Cmd/Ctrl+Enter answer: `⌘↩` on macOS ([mac]), `Ctrl↩` elsewhere (spec 4.12, 4.20). */
internal fun cmdEnterCap(mac: Boolean = SystemInfo.isMac): String = if (mac) "⌘↩" else "Ctrl↩"

/** What a key typed on the question card does (spec 4.13, 4.18), before [cardKey] sees it. */
sealed interface QuestionKey {
    /** Picks or toggles the option at [index] of the active question (the `Other` row is the index after the last option). */
    data class Pick(val index: Int) : QuestionKey

    /** Shows the previous question. */
    data object PrevTab : QuestionKey

    /** Shows the next question. */
    data object NextTab : QuestionKey

    /** Submits the answers (Enter in the `Other` field). */
    data object Submit : QuestionKey
}

/**
 * What [event] does on the question card ([questionKey] with its modifiers read from the event): Cmd and Ctrl count as one.
 * [otherFieldFocused]: the `Other` field has the focus. [stripFocused]: the question tabs have it.
 */
fun questionKey(event: KeyEvent, otherFieldFocused: Boolean, stripFocused: Boolean): QuestionKey? =
    questionKey(event.key, event.type, event.isShiftPressed, event.isMetaPressed || event.isCtrlPressed, otherFieldFocused, stripFocused)

/**
 * What [key] going [type] does on the question card (spec 4.13, 4.18); null leaves it to [cardKey] and what has the focus. Only a
 * key press counts. `1`–`9`, on the main row or the number pad, pick option `n - 1` unless the `Other` field has the focus
 * ([otherFieldFocused]), where they are typed. `←`/`→` switch questions only while the tabs have the focus ([stripFocused]). Enter
 * in the `Other` field, without [shift] or [ctrlOrMeta], submits (the field's own Shift+Enter stays a newline).
 */
internal fun questionKey(
    key: Key,
    type: KeyEventType,
    shift: Boolean,
    ctrlOrMeta: Boolean,
    otherFieldFocused: Boolean,
    stripFocused: Boolean,
): QuestionKey? {
    if (type != KeyEventType.KeyDown) return null
    digit(key)?.let { n -> return if (otherFieldFocused || n == 0) null else QuestionKey.Pick(n - 1) }
    return when (key) {
        Key.DirectionLeft -> QuestionKey.PrevTab.takeIf { stripFocused }
        Key.DirectionRight -> QuestionKey.NextTab.takeIf { stripFocused }
        Key.Enter, Key.NumPadEnter -> QuestionKey.Submit.takeIf { otherFieldFocused && !shift && !ctrlOrMeta }
        else -> null
    }
}

private val digitKeys: List<Key> = listOf(
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
)

private val numPadKeys: List<Key> = listOf(
    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
)

/** The digit [key] types, on the main row or the number pad; null for any other key. */
private fun digit(key: Key): Int? = digitKeys.indexOf(key).takeIf { it >= 0 } ?: numPadKeys.indexOf(key).takeIf { it >= 0 }

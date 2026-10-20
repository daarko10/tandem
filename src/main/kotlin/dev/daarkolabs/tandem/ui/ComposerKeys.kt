package dev.daarkolabs.tandem.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/** What a key typed in the composer does (spec 4.15, 4.18). */
internal enum class ComposerAction {
    /** Sends the prompt, when it is not blank. */
    Send,

    /** Stops the running turn. */
    Stop,

    /** The open card's Esc action: Deny, Cancel or Keep planning. */
    CardEscape,
}

/**
 * What the composer does with [key] going [type], with Shift ([shift]), Alt ([alt]) and Ctrl or Cmd ([ctrlOrMeta]) held, while a
 * turn runs ([busy]) and while a card waits for an answer ([cardOpen]); null leaves the key to the text area.
 *
 * Enter alone sends, and does nothing while a turn runs (the Stop button stands in for Send). Shift+Enter is a newline, and
 * Alt+Enter and Ctrl/Cmd+Enter do nothing yet. Esc goes to the open card first, then stops a running turn.
 */
internal fun composerKey(
    key: Key,
    type: KeyEventType,
    shift: Boolean,
    alt: Boolean,
    ctrlOrMeta: Boolean,
    busy: Boolean,
    cardOpen: Boolean,
): ComposerAction? {
    if (type != KeyEventType.KeyDown) return null
    return when (key) {
        Key.Enter, Key.NumPadEnter -> if (!shift && !alt && !ctrlOrMeta && !busy) ComposerAction.Send else null
        Key.Escape -> when {
            cardOpen -> ComposerAction.CardEscape
            busy -> ComposerAction.Stop
            else -> null
        }
        else -> null
    }
}

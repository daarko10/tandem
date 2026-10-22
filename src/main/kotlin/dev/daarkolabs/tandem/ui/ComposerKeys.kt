package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.daarkolabs.tandem.chat.ScrollEvent

/** What a key typed in the composer does (spec 4.15, 4.18). */
internal enum class ComposerAction {
    /** Sends the prompt, when it is not blank. */
    Send,

    /** Stops the running turn. */
    Stop,

    /** The open card's Esc action: Deny, Cancel or Keep planning. */
    CardEscape,

    /** Reads the images on the clipboard and attaches them (polish spec 2.7). */
    PasteImage,

    /** Removes the last attached image: Backspace in an empty composer (polish spec 2.7). */
    RemoveLastImage,

    /** Closes the MCP panel: Esc while it is open, before any other rule (MCP spec 3.2, 4.4). */
    ClosePanel,

    /** Moves the slash palette's selection up one row (MCP spec 3.4). */
    PaletteUp,

    /** Moves the slash palette's selection down one row. */
    PaletteDown,

    /** Picks the palette's selected row: Enter, whatever a turn or a card is doing; `pick` decides what that does. */
    PalettePick,

    /** Puts the selected row's `/<name> ` in the composer without sending: Tab. */
    PaletteInsert,

    /** Closes the palette, the text staying: Esc. */
    PaletteClose,
}

/**
 * What the composer does with [key] going [type], with Shift ([shift]), Alt ([alt]) and Ctrl or Cmd ([ctrlOrMeta]) held, while a
 * turn runs or a prompt is still being encoded ([busy]) and while a card waits for an answer ([cardOpen]); null leaves the key to the
 * text area.
 *
 * Enter alone sends, and does nothing while a turn runs (the Stop button stands in for Send) or a card is open (until it is answered,
 * spec 4.15), whether or not the reducer still counts the turn as running. Shift+Enter is a newline, which the
 * composer types itself ([composerFallback]), and Alt+Enter and Ctrl/Cmd+Enter do nothing yet. Esc goes to the open card first, then
 * stops a running turn.
 *
 * Ctrl/Cmd+V pastes images, busy or not, when [clipboardHasImage] says the clipboard holds a file list or an image without text
 * ([ComposerImages.clipboardHasImage]); it is asked for that key only, and otherwise the text area pastes. Backspace removes the last
 * attached image while the draft is empty ([draftEmpty]) and an image is attached ([hasAttachments]) (polish spec 2.7).
 *
 * While the MCP panel is open ([panelOpen]), Esc closes it before every other rule, so it never stops a turn (MCP spec 3.2, 4.4).
 * Then, while the slash palette is open ([paletteOpen]), Up and Down move its selection, Enter picks, Tab inserts and Esc closes it,
 * each without Shift, Alt or Ctrl/Cmd but Esc, before the card's and the turn's rules: Enter never sends through the palette, and
 * Shift+Enter stays a newline (MCP spec 3.4, 4.4). Call sites pass the parameters from [draftEmpty] on by name.
 */
internal fun composerKey(
    key: Key,
    type: KeyEventType,
    shift: Boolean,
    alt: Boolean,
    ctrlOrMeta: Boolean,
    busy: Boolean,
    cardOpen: Boolean,
    draftEmpty: Boolean = false,
    hasAttachments: Boolean = false,
    paletteOpen: Boolean = false,
    panelOpen: Boolean = false,
    clipboardHasImage: () -> Boolean = { false },
): ComposerAction? {
    if (type != KeyEventType.KeyDown) return null
    if (key == Key.Escape && panelOpen) return ComposerAction.ClosePanel
    if (paletteOpen) paletteKey(key, modified = shift || alt || ctrlOrMeta)?.let { return it }
    return when (key) {
        Key.Enter, Key.NumPadEnter -> if (!shift && !alt && !ctrlOrMeta && !busy && !cardOpen) ComposerAction.Send else null
        Key.Escape -> when {
            cardOpen -> ComposerAction.CardEscape
            busy -> ComposerAction.Stop
            else -> null
        }
        Key.V -> if (ctrlOrMeta && !shift && !alt && clipboardHasImage()) ComposerAction.PasteImage else null
        Key.Backspace -> if (draftEmpty && hasAttachments) ComposerAction.RemoveLastImage else null
        else -> null
    }
}

/** The open palette's action for [key], with a modifier held or not ([modified]); null leaves the key to the other rules. */
private fun paletteKey(key: Key, modified: Boolean): ComposerAction? = when (key) {
    Key.Escape -> ComposerAction.PaletteClose
    Key.DirectionUp -> if (modified) null else ComposerAction.PaletteUp
    Key.DirectionDown -> if (modified) null else ComposerAction.PaletteDown
    Key.Enter, Key.NumPadEnter -> if (modified) null else ComposerAction.PalettePick
    Key.Tab -> if (modified) null else ComposerAction.PaletteInsert
    else -> null
}

/**
 * Whether Enter or the Send button sends: some text, or an attached image with blank text, and no prompt still being encoded
 * ([sending]: the composer counts as busy until the last one captured has gone out, polish spec 2.7 "Send order" and edge 10).
 */
internal fun canSend(text: String, attachmentCount: Int, sending: Boolean): Boolean =
    !sending && (text.isNotBlank() || attachmentCount > 0)

/**
 * Enter or the Send button: sends [ui]'s draft and attachments through [sender] when [canSend] lets it, checked against the
 * sender's own flag, not the last composition's, so a click or an Enter during an encode sends nothing and leaves the draft and the
 * attachments as they are (polish spec 2.7). On the EDT: the prompt is captured and cleared at once, and the sender encodes its
 * images off it. Returns whether it sent, so that the caller scrolls the list to its bottom.
 */
internal fun sendDraft(ui: ChatUiState, sender: ComposerSender): Boolean {
    val text = ui.draft.text.toString().trim()
    if (!canSend(text, ui.attachments.size, sender.sending.value)) return false
    val images = ui.attachments.map { CapturedImage(it.mediaType, it.bytes) }
    ui.draft.clearText()
    ui.attachments.clear()
    ui.composerNotice.value = null
    sender.submit(CapturedPrompt(text, images))
    ui.follow.value = ui.follow.value.on(ScrollEvent.Sent)
    return true
}

/** What the composer does itself with an Enter that [composerKey] leaves to the text area (spec 4.15). */
internal enum class ComposerFallback {
    /** Types a newline ([insertNewline]): Shift+Enter, which the text area would drop. */
    Newline,

    /** Swallows the key: a plain Enter while a turn runs or a card is open, which the text area would otherwise type as a newline. */
    Swallow,
}

/**
 * What the composer does with a [key] going [type] that [composerKey] returned null for, with Shift ([shift]), Alt ([alt]) and
 * Ctrl or Cmd ([ctrlOrMeta]) held, while a turn runs ([busy]) and while a card is open ([cardOpen]): Shift+Enter is a
 * [ComposerFallback.Newline], a plain Enter while busy or while a card is open is [ComposerFallback.Swallow]ed, so that it does
 * nothing at all; null leaves the key to the text area.
 */
internal fun composerFallback(
    key: Key,
    type: KeyEventType,
    shift: Boolean,
    alt: Boolean,
    ctrlOrMeta: Boolean,
    busy: Boolean,
    cardOpen: Boolean,
): ComposerFallback? {
    if (type != KeyEventType.KeyDown || alt || ctrlOrMeta || (key != Key.Enter && key != Key.NumPadEnter)) return null
    return when {
        shift -> ComposerFallback.Newline
        busy || cardOpen -> ComposerFallback.Swallow
        else -> null
    }
}

/**
 * Puts a newline in place of [state]'s selection, with the cursor right after it: Shift+Enter in the composer (spec 4.15). The text
 * area does not do it itself: Compose maps Enter to a newline only with no modifier held, and drops the typed `\n`.
 */
internal fun insertNewline(state: TextFieldState) {
    state.edit {
        val start = selection.min
        replace(start, selection.max, "\n")
        placeCursorAfterCharAt(start)
    }
}

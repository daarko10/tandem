package dev.daarkolabs.tandem.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.daarkolabs.tandem.chat.ScrollEvent

/** What a key typed in the focused transcript does (spec 4.16, 4.18). */
internal enum class ScrollAction {
    /** Up by a page: the list's height less 40 dp. */
    PAGE_UP,

    /** Down by a page. */
    PAGE_DOWN,

    /** To the first row. */
    HOME,

    /** To the last row. */
    END,

    /** Gives the composer the focus. */
    FOCUS_COMPOSER,
}

/**
 * What the transcript does with [key] going [type]; [ctrlOrMeta] says whether Ctrl or Cmd is held, which changes nothing: End and
 * Ctrl/Cmd+End both go to the bottom. Null for any other key, and for a key going up.
 */
@Suppress("UNUSED_PARAMETER")
internal fun scrollKey(key: Key, type: KeyEventType, ctrlOrMeta: Boolean): ScrollAction? {
    if (type != KeyEventType.KeyDown) return null
    return when (key) {
        Key.PageUp -> ScrollAction.PAGE_UP
        Key.PageDown -> ScrollAction.PAGE_DOWN
        Key.MoveHome -> ScrollAction.HOME
        Key.MoveEnd -> ScrollAction.END
        Key.Escape -> ScrollAction.FOCUS_COMPOSER
        else -> null
    }
}

/**
 * What the action tells [dev.daarkolabs.tandem.chat.FollowState] once it scrolled: Page Up and Home pause following, End jumps to
 * the bottom and follows, Page Down follows again when it landed on the bottom ([atBottomAfter]). Null when it says nothing.
 */
internal fun ScrollAction.followEvent(atBottomAfter: Boolean): ScrollEvent? = when (this) {
    ScrollAction.PAGE_UP, ScrollAction.HOME -> ScrollEvent.WheelUp
    ScrollAction.END -> ScrollEvent.Jump
    ScrollAction.PAGE_DOWN -> if (atBottomAfter) ScrollEvent.ReachedBottom else null
    ScrollAction.FOCUS_COMPOSER -> null
}

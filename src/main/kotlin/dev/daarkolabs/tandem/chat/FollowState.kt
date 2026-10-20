package dev.daarkolabs.tandem.chat

/**
 * Whether the chat list follows its bottom (spec 4.16): an intent, not a position. A user scroll up pauses it; reaching the bottom
 * again, a jump to the bottom or a send resumes it. While paused, [unseen] counts the rows appended since.
 */
data class FollowState(val following: Boolean = true, val unseen: Int = 0) {
    /** The state after [event]; an event that does not apply to this state changes nothing. */
    fun on(event: ScrollEvent): FollowState = when (event) {
        ScrollEvent.WheelUp, ScrollEvent.ThumbDragUp -> if (following) FollowState(following = false, unseen = 0) else this
        ScrollEvent.ReachedBottom, ScrollEvent.Jump -> if (following) this else FollowState()
        ScrollEvent.Sent -> FollowState()
        is ScrollEvent.Appended -> if (following || event.rows <= 0) this else copy(unseen = unseen + event.rows)
        // While following the list pins its bottom again; a paused reader is never moved.
        ScrollEvent.Resized -> this
    }

    /** The jump-to-bottom button's text, `↓ 3 new` or `↓`; null while following (no button). */
    val buttonText: String?
        get() = if (following) null else if (unseen > 0) "↓ $unseen new" else "↓"
}

/** What happened to the chat list, as [FollowState.on] takes it (spec 4.16). */
sealed interface ScrollEvent {
    /** The user scrolled up: wheel, drag, `Home`, `PageUp`. */
    data object WheelUp : ScrollEvent

    /** The user dragged the scrollbar's thumb up. */
    data object ThumbDragUp : ScrollEvent

    /** A user scroll down (or a thumb drag) ended on the bottom. */
    data object ReachedBottom : ScrollEvent

    /** [rows] rows were added, or the last one grew. */
    data class Appended(val rows: Int) : ScrollEvent

    /** The user sent a prompt. */
    data object Sent : ScrollEvent

    /** The jump-to-bottom button, `End` or `Ctrl/Cmd+End`. */
    data object Jump : ScrollEvent

    /** The list's height changed: a card docked or closed, the composer grew or shrank. */
    data object Resized : ScrollEvent
}

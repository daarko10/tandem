package dev.daarkolabs.tandem.chat

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Follow-the-bottom as intent (spec 4.16): one test per row of its table. */
class FollowStateTest {
    @Test
    fun `following and WheelUp pauses`() {
        assertEquals(FollowState(false, 0), FollowState().on(ScrollEvent.WheelUp))
        assertEquals(FollowState(false, 2), FollowState(false, 2).on(ScrollEvent.WheelUp))
    }

    @Test
    fun `following and Appended stays following`() {
        assertEquals(FollowState(), FollowState().on(ScrollEvent.Appended(3)))
    }

    @Test
    fun `paused and Appended counts unseen`() {
        assertEquals(FollowState(false, 5), FollowState(false, 2).on(ScrollEvent.Appended(3)))
    }

    @Test
    fun `paused and Jump resumes`() {
        assertEquals(FollowState(true, 0), FollowState(false, 4).on(ScrollEvent.Jump))
    }

    @Test
    fun `Sent always follows`() {
        assertEquals(FollowState(true, 0), FollowState(false, 4).on(ScrollEvent.Sent))
        assertEquals(FollowState(), FollowState().on(ScrollEvent.Sent))
    }

    @Test
    fun `a wheel down that lands on the bottom resumes`() {
        assertEquals(FollowState(true, 0), FollowState(false, 3).on(ScrollEvent.ReachedBottom))
    }

    @Test
    fun `a thumb drag up pauses like a wheel up`() {
        assertEquals(FollowState(false, 0), FollowState().on(ScrollEvent.ThumbDragUp))
        assertEquals(FollowState(false, 1), FollowState(false, 1).on(ScrollEvent.ThumbDragUp))
    }

    @Test
    fun `a resize while following pins, while paused does nothing`() {
        assertEquals(FollowState(), FollowState().on(ScrollEvent.Resized))
        assertEquals(FollowState(false, 2), FollowState(false, 2).on(ScrollEvent.Resized))
    }

    @Test
    fun `button text counts unseen`() {
        assertNull(FollowState().buttonText)
        assertEquals("↓", FollowState(false, 0).buttonText)
        assertEquals("↓ 3 new", FollowState(false, 3).buttonText)
    }

    @Test
    fun `events in the wrong state change nothing`() {
        assertEquals(FollowState(), FollowState().on(ScrollEvent.ReachedBottom))
        assertEquals(FollowState(), FollowState().on(ScrollEvent.Jump))
        assertEquals(FollowState(false, 2), FollowState(false, 2).on(ScrollEvent.Appended(0)))
        assertEquals(FollowState(false, 2), FollowState(false, 2).on(ScrollEvent.Appended(-1)))
    }
}

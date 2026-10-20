package dev.daarkolabs.tandem.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.daarkolabs.tandem.chat.FollowState
import dev.daarkolabs.tandem.chat.ScrollEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.awt.event.KeyEvent as AwtKeyEvent

/** What a key typed in the focused transcript does to the list and to following its bottom (spec 4.16, 4.18). */
class ScrollKeysTest {
    private val home = key(AwtKeyEvent.VK_HOME)
    private val end = key(AwtKeyEvent.VK_END)
    private val pageUp = key(AwtKeyEvent.VK_PAGE_UP)
    private val pageDown = key(AwtKeyEvent.VK_PAGE_DOWN)
    private val escape = key(AwtKeyEvent.VK_ESCAPE)

    @Test
    fun `Home and PageUp pause, End resumes`() {
        assertEquals(FollowState(false, 0), FollowState().on(scrollKey(home, KeyEventType.KeyDown, false)!!.followEvent(false)!!))
        assertEquals(FollowState(false, 0), FollowState().on(scrollKey(pageUp, KeyEventType.KeyDown, false)!!.followEvent(false)!!))
        assertEquals(FollowState(true, 0), FollowState(false, 2).on(scrollKey(end, KeyEventType.KeyDown, false)!!.followEvent(false)!!))
        assertEquals(ScrollAction.END, scrollKey(end, KeyEventType.KeyDown, true))
        assertEquals(ScrollEvent.ReachedBottom, scrollKey(pageDown, KeyEventType.KeyDown, false)!!.followEvent(true))
        assertNull(scrollKey(pageDown, KeyEventType.KeyDown, false)!!.followEvent(false))
        assertNull(scrollKey(home, KeyEventType.KeyUp, false))
        assertNull(scrollKey(key(AwtKeyEvent.VK_A), KeyEventType.KeyDown, false))
    }

    @Test
    fun `Esc in the transcript focuses the composer`() {
        assertEquals(ScrollAction.FOCUS_COMPOSER, scrollKey(escape, KeyEventType.KeyDown, false))
        assertNull(ScrollAction.FOCUS_COMPOSER.followEvent(false))
        assertNull(scrollKey(escape, KeyEventType.KeyUp, false))
    }

    private fun key(code: Int): Key = Key(code, AwtKeyEvent.KEY_LOCATION_STANDARD)
}

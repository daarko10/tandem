package dev.daarkolabs.tandem.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.awt.event.KeyEvent as AwtKeyEvent

/** What a key typed in the composer does (spec 4.15, 4.18). */
class ComposerKeysTest {
    private val enter = key(AwtKeyEvent.VK_ENTER)
    private val escape = key(AwtKeyEvent.VK_ESCAPE)

    private fun composer(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrlOrMeta: Boolean = false,
        busy: Boolean = false,
        cardOpen: Boolean = false,
    ): ComposerAction? = composerKey(key, type, shift, alt, ctrlOrMeta, busy, cardOpen)

    @Test
    fun `Enter sends`() {
        assertEquals(
            ComposerAction.Send,
            composerKey(enter, KeyEventType.KeyDown, shift = false, alt = false, ctrlOrMeta = false, busy = false, cardOpen = false),
        )
        assertEquals(ComposerAction.Send, composer(key(AwtKeyEvent.VK_ENTER, numPad = true)))
        assertNull(composer(enter, type = KeyEventType.KeyUp))
    }

    @Test
    fun `Enter does nothing while busy`() {
        assertNull(composer(enter, busy = true))
        assertNull(composer(enter, busy = true, cardOpen = true))
    }

    @Test
    fun `Cmd-Enter does nothing`() {
        assertNull(composer(enter, ctrlOrMeta = true))
        assertNull(composer(enter, alt = true))
    }

    @Test
    fun `Shift+Enter is a newline`() {
        assertNull(composer(enter, shift = true))
    }

    @Test
    fun `Esc stops while busy`() {
        assertEquals(ComposerAction.Stop, composer(escape, busy = true, cardOpen = false))
        assertNull(composer(escape, busy = false, cardOpen = false))
        assertNull(composer(escape, type = KeyEventType.KeyUp, busy = true))
    }

    @Test
    fun `Esc with a card open routes to the card`() {
        assertEquals(ComposerAction.CardEscape, composer(escape, busy = true, cardOpen = true))
        assertEquals(ComposerAction.CardEscape, composer(escape, busy = false, cardOpen = true))
    }

    private fun key(code: Int, numPad: Boolean = false): Key =
        Key(code, if (numPad) AwtKeyEvent.KEY_LOCATION_NUMPAD else AwtKeyEvent.KEY_LOCATION_STANDARD)
}

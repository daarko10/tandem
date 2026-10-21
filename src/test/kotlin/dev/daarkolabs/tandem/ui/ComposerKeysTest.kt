package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
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
    fun `Enter does nothing while a card is open`() {
        // The reducer keeps busy while a request waits; the card rule holds on its own should one ever outlive it (spec 4.15).
        assertNull(composer(enter, cardOpen = true))
        assertNull(composer(key(AwtKeyEvent.VK_ENTER, numPad = true), cardOpen = true))
        assertEquals(ComposerFallback.Swallow, fallback(enter, cardOpen = true))
        assertEquals(ComposerFallback.Newline, fallback(enter, shift = true, cardOpen = true))
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
    fun `the composer types Shift+Enter as a newline and swallows Enter while busy`() {
        assertEquals(ComposerFallback.Newline, fallback(enter, shift = true))
        assertEquals(ComposerFallback.Newline, fallback(enter, shift = true, busy = true))
        assertEquals(ComposerFallback.Newline, fallback(key(AwtKeyEvent.VK_ENTER, numPad = true), shift = true))
        assertEquals(ComposerFallback.Swallow, fallback(enter, busy = true))
        assertEquals(ComposerFallback.Swallow, fallback(key(AwtKeyEvent.VK_ENTER, numPad = true), busy = true))
        // A plain Enter while idle sends (composerKey); Alt or Ctrl/Cmd held, a key release and other keys go to the text area.
        assertNull(fallback(enter))
        assertNull(fallback(enter, alt = true, busy = true))
        assertNull(fallback(enter, ctrlOrMeta = true, busy = true))
        assertNull(fallback(enter, shift = true, ctrlOrMeta = true))
        assertNull(fallback(enter, type = KeyEventType.KeyUp, shift = true))
        assertNull(fallback(enter, type = KeyEventType.KeyUp, busy = true))
        assertNull(fallback(escape, shift = true, busy = true))
    }

    @Test
    fun `the newline replaces the selection and the cursor follows it`() {
        val atCursor = TextFieldState("ab", TextRange(1))
        insertNewline(atCursor)
        assertEquals("a\nb", atCursor.text.toString())
        assertEquals(TextRange(2), atCursor.selection)

        val overSelection = TextFieldState("abcd", TextRange(1, 3))
        insertNewline(overSelection)
        assertEquals("a\nd", overSelection.text.toString())
        assertEquals(TextRange(2), overSelection.selection)
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

    private fun fallback(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrlOrMeta: Boolean = false,
        busy: Boolean = false,
        cardOpen: Boolean = false,
    ): ComposerFallback? = composerFallback(key, type, shift, alt, ctrlOrMeta, busy, cardOpen)

    private fun key(code: Int, numPad: Boolean = false): Key =
        Key(code, if (numPad) AwtKeyEvent.KEY_LOCATION_NUMPAD else AwtKeyEvent.KEY_LOCATION_STANDARD)
}

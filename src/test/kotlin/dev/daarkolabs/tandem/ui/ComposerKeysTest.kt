package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/** What a key typed in the composer does (spec 4.15, 4.18). */
class ComposerKeysTest {
    private val enter = key(AwtKeyEvent.VK_ENTER)
    private val escape = key(AwtKeyEvent.VK_ESCAPE)
    private val v = key(AwtKeyEvent.VK_V)
    private val backspace = key(AwtKeyEvent.VK_BACK_SPACE)
    private val up = key(AwtKeyEvent.VK_UP)
    private val down = key(AwtKeyEvent.VK_DOWN)
    private val tab = key(AwtKeyEvent.VK_TAB)

    private fun composer(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrlOrMeta: Boolean = false,
        busy: Boolean = false,
        cardOpen: Boolean = false,
        draftEmpty: Boolean = false,
        hasAttachments: Boolean = false,
        paletteOpen: Boolean = false,
        panelOpen: Boolean = false,
        clipboardHasImage: () -> Boolean = { false },
    ): ComposerAction? = composerKey(
        key,
        type,
        shift,
        alt,
        ctrlOrMeta,
        busy,
        cardOpen,
        draftEmpty = draftEmpty,
        hasAttachments = hasAttachments,
        paletteOpen = paletteOpen,
        panelOpen = panelOpen,
        clipboardHasImage = clipboardHasImage,
    )

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

    @Test
    fun `Esc with the panel open closes it, never stops`() {
        assertEquals(ComposerAction.ClosePanel, composer(escape, busy = true, panelOpen = true))
        assertEquals(ComposerAction.ClosePanel, composer(escape, busy = true, cardOpen = true, panelOpen = true))
        assertEquals(ComposerAction.ClosePanel, composer(escape, panelOpen = true))
        assertNull(composer(escape, type = KeyEventType.KeyUp, busy = true, panelOpen = true))
        // Only Esc: Enter still sends, Backspace still removes an image.
        assertEquals(ComposerAction.Send, composer(enter, panelOpen = true))
        assertEquals(ComposerAction.RemoveLastImage, composer(backspace, draftEmpty = true, hasAttachments = true, panelOpen = true))
    }

    @Test
    fun `calls without the new argument behave as before`() {
        assertEquals(
            ComposerAction.Stop,
            composerKey(escape, KeyEventType.KeyDown, shift = false, alt = false, ctrlOrMeta = false, busy = true, cardOpen = false),
        )
        assertEquals(
            ComposerAction.CardEscape,
            composerKey(escape, KeyEventType.KeyDown, shift = false, alt = false, ctrlOrMeta = false, busy = true, cardOpen = true),
        )
        // The trailing parameters by name, clipboardHasImage as a trailing lambda: it still compiles and still pastes.
        assertEquals(
            ComposerAction.PasteImage,
            composerKey(v, KeyEventType.KeyDown, shift = false, alt = false, ctrlOrMeta = true, busy = false, cardOpen = false, draftEmpty = true) { true },
        )
        assertNull(composerKey(escape, KeyEventType.KeyDown, shift = false, alt = false, ctrlOrMeta = false, busy = false, cardOpen = false))
    }

    // spec: palette keys map to the palette actions
    @Test
    fun `palette keys map to the palette actions`() {
        assertEquals(ComposerAction.PaletteUp, composer(up, paletteOpen = true))
        assertEquals(ComposerAction.PaletteDown, composer(down, paletteOpen = true))
        assertEquals(ComposerAction.PalettePick, composer(enter, paletteOpen = true))
        assertEquals(ComposerAction.PalettePick, composer(key(AwtKeyEvent.VK_ENTER, numPad = true), paletteOpen = true))
        assertEquals(ComposerAction.PaletteInsert, composer(tab, paletteOpen = true))
        assertEquals(ComposerAction.PaletteClose, composer(escape, paletteOpen = true))
        // While a turn runs the palette still takes its keys (MCP spec 3.4): pick decides what a busy Enter does, Esc never stops.
        assertEquals(ComposerAction.PalettePick, composer(enter, busy = true, paletteOpen = true))
        assertEquals(ComposerAction.PaletteInsert, composer(tab, busy = true, paletteOpen = true))
        assertEquals(ComposerAction.PaletteClose, composer(escape, busy = true, paletteOpen = true))
        // Key releases, Shift+Tab (focus traversal) and the arrows with a modifier stay the text area's.
        assertNull(composer(enter, type = KeyEventType.KeyUp, paletteOpen = true))
        assertNull(composer(tab, shift = true, paletteOpen = true))
        assertNull(composer(up, shift = true, paletteOpen = true))
        assertNull(composer(down, ctrlOrMeta = true, paletteOpen = true))
        // Closed, the arrows and Tab are the text area's.
        assertNull(composer(up))
        assertNull(composer(down))
        assertNull(composer(tab))
    }

    // spec: Enter sends when the palette is closed
    @Test
    fun `Enter sends when the palette is closed`() {
        assertEquals(ComposerAction.Send, composer(enter, paletteOpen = false))
        assertNull(composer(escape, paletteOpen = false))
    }

    // spec: Esc goes to the palette before the card
    @Test
    fun `Esc goes to the palette before the card`() {
        assertEquals(ComposerAction.PaletteClose, composer(escape, cardOpen = true, paletteOpen = true))
        assertEquals(ComposerAction.PaletteClose, composer(escape, busy = true, cardOpen = true, paletteOpen = true))
        assertEquals(ComposerAction.CardEscape, composer(escape, cardOpen = true, paletteOpen = false))
    }

    // spec: Shift+Enter with the palette open is still a newline
    @Test
    fun `Shift+Enter with the palette open is still a newline`() {
        assertNull(composer(enter, shift = true, paletteOpen = true))
        assertEquals(ComposerFallback.Newline, fallback(enter, shift = true))
        assertNull(composer(enter, alt = true, paletteOpen = true))
        assertNull(composer(enter, ctrlOrMeta = true, paletteOpen = true))
    }

    // spec: Enter with a card and the palette open picks
    @Test
    fun `Enter with a card and the palette open picks`() {
        assertEquals(ComposerAction.PalettePick, composer(enter, cardOpen = true, paletteOpen = true))
        assertEquals(ComposerAction.PalettePick, composer(enter, busy = true, cardOpen = true, paletteOpen = true))
    }

    // spec: Esc with the panel and the palette open closes the panel
    @Test
    fun `Esc with the panel and the palette open closes the panel`() {
        assertEquals(ComposerAction.ClosePanel, composer(escape, paletteOpen = true, panelOpen = true))
        assertEquals(ComposerAction.ClosePanel, composer(escape, busy = true, cardOpen = true, paletteOpen = true, panelOpen = true))
        // Only Esc goes to the panel first: the other palette keys still reach the palette.
        assertEquals(ComposerAction.PalettePick, composer(enter, paletteOpen = true, panelOpen = true))
    }

    // spec: a call passing clipboardHasImage as a trailing lambda still compiles
    @Test
    fun `a call passing clipboardHasImage as a trailing lambda still compiles`() {
        assertEquals(
            ComposerAction.Send,
            composerKey(enter, KeyEventType.KeyDown, false, false, false, busy = false, cardOpen = false, draftEmpty = true, hasAttachments = false) { false },
        )
    }

    @Test
    fun `Enter sends with an attachment and blank text`() {
        assertTrue(canSend("  ", 1, sending = false))
        assertFalse(canSend("  ", 0, sending = false))
        assertTrue(canSend("x", 0, sending = false))
        assertFalse(canSend("", 0, sending = false))
    }

    @Test
    fun `nothing sends while the last prompt is still being encoded, by Enter or by the Send button`() {
        // Polish spec 2.7 "Send order" and edge 10: the composer counts as busy while sending; both ways in go through canSend.
        assertFalse(canSend("x", 0, sending = true))
        assertFalse(canSend("  ", 1, sending = true))
        assertTrue(canSend("x", 0, sending = false))
    }

    @Test
    fun `a send while the last prompt is still encoding submits nothing and keeps the draft and the attachments`() = runBlocking {
        // Polish spec 2.7 "Send order" and edge 10: Enter and the Send button both go through sendDraft, which reads the sender's
        // own flag, so a click that races the encode sends nothing.
        val scope = CoroutineScope(SupervisorJob())
        val release = CountDownLatch(1)
        try {
            val encoding = CountDownLatch(1)
            val encode: (ByteArray) -> String = {
                encoding.countDown()
                release.await(TIMEOUT, TimeUnit.MILLISECONDS)
                Base64.getEncoder().encodeToString(it)
            }
            val sent: MutableList<String> = Collections.synchronizedList(ArrayList())
            val sender = ComposerSender(scope, { text, _ -> sent += text }, {}, encode)
            val ui = ChatUiState()
            ui.draft.setTextAndPlaceCursorAtEnd(" first ")
            ui.attachments += image("a")
            assertTrue(sendDraft(ui, sender))
            assertEquals("", ui.draft.text.toString())
            assertTrue(ui.attachments.isEmpty())
            assertTrue(encoding.await(TIMEOUT, TimeUnit.MILLISECONDS))

            ui.draft.setTextAndPlaceCursorAtEnd("second")
            ui.attachments += image("b")
            assertFalse(sendDraft(ui, sender))
            assertEquals("second", ui.draft.text.toString())
            assertEquals(listOf("b"), ui.attachments.map { it.id })

            release.countDown()
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(listOf("first"), sent)
            // Once the first has gone out, the kept draft sends.
            assertTrue(sendDraft(ui, sender))
            assertEquals("", ui.draft.text.toString())
            assertTrue(ui.attachments.isEmpty())
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(listOf("first", "second"), sent)
        } finally {
            // A failed assertion above must not leave the encoder blocked on a Dispatchers.Default thread: cancel cannot interrupt it.
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `Cmd-V with an image on the clipboard is a paste, with text it is left to the text area`() {
        assertEquals(ComposerAction.PasteImage, composer(v, ctrlOrMeta = true, clipboardHasImage = { true }))
        assertEquals(ComposerAction.PasteImage, composer(v, ctrlOrMeta = true, busy = true, clipboardHasImage = { true }))
        assertNull(composer(v, ctrlOrMeta = true, clipboardHasImage = { false }))
        assertNull(composer(v, ctrlOrMeta = true, shift = true, clipboardHasImage = { true }))
        assertNull(composer(v, ctrlOrMeta = true, alt = true, clipboardHasImage = { true }))
        assertNull(composer(v, clipboardHasImage = { true }))
        assertNull(composer(v, type = KeyEventType.KeyUp, ctrlOrMeta = true, clipboardHasImage = { true }))
        // The clipboard is asked only for Ctrl/Cmd+V: no other key queries its flavours.
        var asked = 0
        composer(enter, clipboardHasImage = { asked++; true })
        composer(v, clipboardHasImage = { asked++; true })
        assertEquals(0, asked)
    }

    @Test
    fun `Backspace in an empty composer removes the last image`() {
        assertEquals(ComposerAction.RemoveLastImage, composer(backspace, draftEmpty = true, hasAttachments = true))
        assertNull(composer(backspace, draftEmpty = false, hasAttachments = true))
        assertNull(composer(backspace, draftEmpty = true, hasAttachments = false))
        assertNull(composer(backspace, type = KeyEventType.KeyUp, draftEmpty = true, hasAttachments = true))
    }

    @Test
    fun `Enter does nothing while a prompt is being encoded`() {
        // PromptBox passes busy || sending as busy (polish spec 2.7).
        assertNull(composer(enter, busy = true, hasAttachments = true))
        assertEquals(ComposerFallback.Swallow, fallback(enter, busy = true))
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

    private fun image(id: String): PendingImage = PendingImage(id, "$id.png", "image/png", byteArrayOf(1, 2, 3), null)

    private companion object {
        const val TIMEOUT = 30_000L
    }
}

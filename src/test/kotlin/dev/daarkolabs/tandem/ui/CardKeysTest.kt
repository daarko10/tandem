package dev.daarkolabs.tandem.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.daarkolabs.tandem.ui.cards.CardKey
import dev.daarkolabs.tandem.ui.cards.QuestionKey
import dev.daarkolabs.tandem.ui.cards.cardButtonSwallows
import dev.daarkolabs.tandem.ui.cards.cardKey
import dev.daarkolabs.tandem.ui.cards.cmdEnterCap
import dev.daarkolabs.tandem.ui.cards.questionKey
import dev.daarkolabs.tandem.ui.cards.questionSwallows
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/** What a key typed on a card does (spec 4.18). */
class CardKeysTest {
    private fun key(code: Int, numPad: Boolean = false): Key =
        Key(code, if (numPad) AwtKeyEvent.KEY_LOCATION_NUMPAD else AwtKeyEvent.KEY_LOCATION_STANDARD)

    private fun press(
        code: Int,
        cmd: Boolean = false,
        shift: Boolean = false,
        hasCmdEnter: Boolean = false,
        textField: Boolean = false,
        numPad: Boolean = false,
    ): CardKey? = cardKey(key(code, numPad), KeyEventType.KeyDown, shift, cmd, hasCmdEnter, textField)

    private fun q(
        code: Int,
        other: Boolean = false,
        strip: Boolean = false,
        shift: Boolean = false,
        cmd: Boolean = false,
        numPad: Boolean = false,
    ): QuestionKey? = questionKey(key(code, numPad), KeyEventType.KeyDown, shift, cmd, other, strip)

    @Test
    fun `Enter, Cmd-Enter when offered, Esc`() {
        assertEquals(CardKey.ENTER, press(AwtKeyEvent.VK_ENTER))
        assertEquals(CardKey.ENTER, press(AwtKeyEvent.VK_ENTER, numPad = true))
        assertEquals(CardKey.CMD_ENTER, press(AwtKeyEvent.VK_ENTER, cmd = true, hasCmdEnter = true))
        assertNull(press(AwtKeyEvent.VK_ENTER, cmd = true))
        // 4.18: Shift+Enter does nothing on a card.
        assertNull(press(AwtKeyEvent.VK_ENTER, shift = true))
        assertEquals(CardKey.ESCAPE, press(AwtKeyEvent.VK_ESCAPE))
        assertNull(press(AwtKeyEvent.VK_A))
        assertNull(cardKey(key(AwtKeyEvent.VK_ENTER), KeyEventType.KeyUp, false, false, false, false))
    }

    @Test
    fun `Enter is not returned while a text field has focus`() {
        assertNull(press(AwtKeyEvent.VK_ENTER, textField = true))
        assertEquals(CardKey.ESCAPE, press(AwtKeyEvent.VK_ESCAPE, textField = true))
        assertEquals(CardKey.CMD_ENTER, press(AwtKeyEvent.VK_ENTER, cmd = true, hasCmdEnter = true, textField = true))
    }

    @Test
    fun `a card button never clicks on Enter, and the default one never on Space`() {
        // A space typed in the composer just as the card took the focus must not answer it; Enter is the card's to map (4.18).
        assertTrue(cardButtonSwallows(key(AwtKeyEvent.VK_SPACE), primary = true))
        assertFalse(cardButtonSwallows(key(AwtKeyEvent.VK_SPACE), primary = false))
        for (primary in listOf(true, false)) {
            assertTrue(cardButtonSwallows(key(AwtKeyEvent.VK_ENTER), primary))
            assertTrue(cardButtonSwallows(key(AwtKeyEvent.VK_ENTER, numPad = true), primary))
            assertFalse(cardButtonSwallows(key(AwtKeyEvent.VK_ESCAPE), primary))
            assertFalse(cardButtonSwallows(key(AwtKeyEvent.VK_TAB), primary))
            assertFalse(cardButtonSwallows(key(AwtKeyEvent.VK_A), primary))
        }
    }

    @Test
    fun `the Cmd-Enter cap names the platform's key`() {
        assertEquals("⌘↩", cmdEnterCap(mac = true))
        assertEquals("Ctrl↩", cmdEnterCap(mac = false))
    }

    @Test
    fun `digits pick options unless the Other field has focus`() {
        assertEquals(QuestionKey.Pick(0), q(AwtKeyEvent.VK_1))
        assertEquals(QuestionKey.Pick(8), q(AwtKeyEvent.VK_9))
        assertEquals(QuestionKey.Pick(1), q(AwtKeyEvent.VK_NUMPAD2, numPad = true))
        assertNull(q(AwtKeyEvent.VK_0))
        assertNull(q(AwtKeyEvent.VK_1, other = true))
        assertEquals(QuestionKey.Submit, q(AwtKeyEvent.VK_ENTER, other = true))
        assertNull(q(AwtKeyEvent.VK_ENTER, other = true, shift = true))
        assertNull(q(AwtKeyEvent.VK_ENTER, other = true, cmd = true))
        assertNull(q(AwtKeyEvent.VK_ENTER))
    }

    @Test
    fun `arrows switch tabs only when the strip has focus`() {
        assertEquals(QuestionKey.PrevTab, q(AwtKeyEvent.VK_LEFT, strip = true))
        assertEquals(QuestionKey.NextTab, q(AwtKeyEvent.VK_RIGHT, strip = true))
        assertNull(q(AwtKeyEvent.VK_LEFT))
        assertNull(q(AwtKeyEvent.VK_RIGHT, other = true))
    }

    @Test
    fun `the card swallows a picked digit's character and an arrow's release on the tabs`() {
        // The character typed by the key that picked a row (the Other row's digit would land in the field it focused); only that one.
        assertTrue(questionSwallows(Key.Unknown, KeyEventType.Unknown, afterPick = true, stripFocused = false))
        assertFalse(questionSwallows(Key.Unknown, KeyEventType.Unknown, afterPick = false, stripFocused = false))
        assertFalse(questionSwallows(key(AwtKeyEvent.VK_3), KeyEventType.KeyDown, afterPick = true, stripFocused = false))
        assertFalse(questionSwallows(key(AwtKeyEvent.VK_3), KeyEventType.KeyUp, afterPick = true, stripFocused = false))
        // Jewel's tabs step on an arrow's release, after the press switched the question: one tab per press (4.13, 4.18).
        assertTrue(questionSwallows(key(AwtKeyEvent.VK_LEFT), KeyEventType.KeyUp, afterPick = false, stripFocused = true))
        assertTrue(questionSwallows(key(AwtKeyEvent.VK_RIGHT), KeyEventType.KeyUp, afterPick = false, stripFocused = true))
        assertFalse(questionSwallows(key(AwtKeyEvent.VK_RIGHT), KeyEventType.KeyUp, afterPick = false, stripFocused = false))
        assertFalse(questionSwallows(key(AwtKeyEvent.VK_UP), KeyEventType.KeyUp, afterPick = false, stripFocused = true))
        // The press is questionKey's to map.
        assertFalse(questionSwallows(key(AwtKeyEvent.VK_LEFT), KeyEventType.KeyDown, afterPick = false, stripFocused = true))
    }
}

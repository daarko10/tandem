package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.ImageRef
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.ui.rows.BubbleClamp
import dev.daarkolabs.tandem.ui.rows.bubbleClamp
import dev.daarkolabs.tandem.ui.rows.showsTextBox
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the user's bubble clamps (spec 4.3, Review Focus 6): the count is the laid-out lines, not the newlines. */
class UserBubbleTest {
    @Test
    fun `prompts over eight laid-out lines clamp`() {
        assertEquals(BubbleClamp.NONE, bubbleClamp(8, false))
        assertEquals(BubbleClamp.CLAMPED, bubbleClamp(9, false))
        assertEquals(BubbleClamp.OPEN, bubbleClamp(9, true))
    }

    @Test
    fun `an images-only message has no text box`() {
        val images = persistentListOf(ImageRef("u1#0", "image/png", 0))
        assertFalse(showsTextBox(UserRow("u1", "", false, images = images)))
        assertFalse(showsTextBox(UserRow("u1", "  ", false, images = images)))
        assertTrue(showsTextBox(UserRow("u1", "hi", false, images = images)))
    }
}

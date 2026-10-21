package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.ui.rows.BubbleClamp
import dev.daarkolabs.tandem.ui.rows.bubbleClamp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** When the user's bubble clamps (spec 4.3, Review Focus 6): the count is the laid-out lines, not the newlines. */
class UserBubbleTest {
    @Test
    fun `prompts over eight laid-out lines clamp`() {
        assertEquals(BubbleClamp.NONE, bubbleClamp(8, false))
        assertEquals(BubbleClamp.CLAMPED, bubbleClamp(9, false))
        assertEquals(BubbleClamp.OPEN, bubbleClamp(9, true))
    }
}

package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.ImageRef
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.session.ContextBlocks
import dev.daarkolabs.tandem.session.ContextChipKind
import dev.daarkolabs.tandem.ui.rows.BubbleClamp
import dev.daarkolabs.tandem.ui.rows.BubbleSegment
import dev.daarkolabs.tandem.ui.rows.bubbleClamp
import dev.daarkolabs.tandem.ui.rows.bubbleSegments
import dev.daarkolabs.tandem.ui.rows.ChipLook
import dev.daarkolabs.tandem.ui.rows.chipLook
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

    // spec: an existing @path is drawn as a chip and a missing one as text
    @Test
    fun `an existing @path is drawn as a chip and a missing one as text`() {
        val known = setOf("hello.txt", "src/A.kt")
        assertEquals(
            listOf(
                BubbleSegment("What does ", mention = false),
                BubbleSegment("@hello.txt", mention = true),
                BubbleSegment(" say, and @gone.txt, and ", mention = false),
                BubbleSegment("@src/A.kt", mention = true),
                BubbleSegment("?", mention = false),
            ),
            bubbleSegments("What does @hello.txt say, and @gone.txt, and @src/A.kt?", known),
        )
        // A line suffix names its file; an `@` inside a word is text.
        assertEquals(
            listOf(BubbleSegment("What is on ", false), BubbleSegment("@hello.txt#L2", true), BubbleSegment("?", false)),
            bubbleSegments("What is on @hello.txt#L2?", known),
        )
        assertEquals(listOf(BubbleSegment("mail a@hello.txt", false)), bubbleSegments("mail a@hello.txt", known))
        assertEquals(listOf(BubbleSegment("@hello.txt", true)), bubbleSegments("@hello.txt", known))
        assertEquals(listOf(BubbleSegment("", false)), bubbleSegments("", known))
    }

    // spec: a selection chip is drawn
    @Test
    fun `a selection chip is drawn`() {
        // The chip a sent or reloaded selection block gives back is drawn with its label and the file's path as its tooltip.
        val chip = ContextBlocks.chipOf("The user's selection in src/A.kt, lines 3-5:\n```\nx\n```")!!
        val row = UserRow("u1", "why?", false, chips = persistentListOf(chip))
        assertEquals(listOf(ChipLook("A.kt:3-5", "src/A.kt")), row.chips.map(::chipLook))
        assertEquals(ContextChipKind.SELECTION, chip.kind)
        assertTrue(showsTextBox(row))
    }
}

package dev.daarkolabs.tandem.session

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The unsaved file's context block and its chip (spec 3.5): one form for the block sent, the bubble's chip and the reload's. */
class ContextBlocksTest {
    @Test
    fun `an unsaved file's block counts its lines and keeps the buffer`() {
        val block = ContextBlocks.unsavedBlock("src/A.kt", "one\ntwo")
        assertEquals("src/A.kt as open in the IDE, with unsaved changes (2 lines):", block.firstLine)
        assertEquals("one\ntwo", block.body)
        assertEquals(0, ContextBlocks.lineCount(""))
        assertEquals(1, ContextBlocks.lineCount("a\n"))
        assertEquals(2, ContextBlocks.lineCount("a\nb"))
    }

    @Test
    fun `a buffer over 64 KB is cut and says how many lines it had`() {
        val line = "é".repeat(99) + "\n" // 199 bytes in UTF-8
        val text = line.repeat(400) // 79 600 bytes, 400 lines
        val block = ContextBlocks.unsavedBlock("big.txt", text)
        val (kept, note) = block.body.split("\n… truncated: ").let { it[0] to it[1] }
        assertEquals("400 lines in all", note)
        assertTrue(kept.toByteArray().size <= ContextBlocks.MAX_BODY_BYTES)
        assertTrue(text.startsWith(kept))
        // A surrogate pair counts four bytes and is never split.
        val emoji = "😀".repeat(ContextBlocks.MAX_BODY_BYTES / 4 + 1)
        val cut = ContextBlocks.unsavedBlock("e.txt", emoji).body.substringBefore("\n…")
        assertEquals(ContextBlocks.MAX_BODY_BYTES, cut.toByteArray().size)
        assertEquals("😀".repeat(ContextBlocks.MAX_BODY_BYTES / 4), cut)
    }

    @Test
    fun `a sent block reads back as its chip, and leading blocks split from the typed text`() {
        val chip = ContextChip(ContextChipKind.UNSAVED_FILE, "A.kt (unsaved)", "src/A.kt")
        assertEquals(chip, ContextBlocks.chipOf("src/A.kt as open in the IDE, with unsaved changes (12 lines):\n```\nx\n```"))
        assertNull(ContextBlocks.chipOf("What does src/A.kt as open in the IDE say?"))
        val block = "src/A.kt as open in the IDE, with unsaved changes (1 lines):\n```\nx\n```"
        assertEquals(listOf(chip) to listOf("fix src/A.kt"), ContextBlocks.split(listOf(block, "fix src/A.kt")))
        // Only leading blocks: one after the typed text is text.
        assertEquals(emptyList<ContextChip>() to listOf("fix", block), ContextBlocks.split(listOf("fix", block)))
        assertEquals("A.kt", ContextBlocks.fileName("src\\A.kt"))
        assertEquals("dir", ContextBlocks.fileName("a/dir/"))
    }

    @Test
    fun `a selection's block names its lines and reads back as its chip`() {
        val block = ContextBlocks.selectionBlock("src/A.kt", 3, 5, "a\nb\nc")
        assertEquals("The user's selection in src/A.kt, lines 3-5:", block.firstLine)
        assertEquals("a\nb\nc", block.body)
        val chip = ContextChip(ContextChipKind.SELECTION, "A.kt:3-5", "src/A.kt")
        assertEquals(chip, ContextBlocks.selectionChip("src/A.kt", 3, 5))
        assertEquals(chip, ContextBlocks.chipOf("The user's selection in src/A.kt, lines 3-5:\n```\na\n```"))
        // A path holding ", lines" keeps it; numbers past Int, or no numbers, are text.
        assertEquals("x, lines 1-2/B.kt", ContextBlocks.chipOf("The user's selection in x, lines 1-2/B.kt, lines 7-7:")!!.path)
        assertNull(ContextBlocks.chipOf("The user's selection in a.kt, lines 99999999999-1:"))
        assertNull(ContextBlocks.chipOf("The user's selection in a.kt, lines 1-99999999999:"))
        assertNull(ContextBlocks.chipOf("The user's selection in a.kt, lines a-b:"))
        // Bounded as an unsaved buffer is.
        val big = "x".repeat(ContextBlocks.MAX_BODY_BYTES + 10)
        assertTrue(ContextBlocks.selectionBlock("a.kt", 1, 1, big).body.endsWith("\n… truncated: 1 lines in all"))
    }
}

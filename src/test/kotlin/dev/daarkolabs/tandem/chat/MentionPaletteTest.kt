package dev.daarkolabs.tandem.chat

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `@` palette's pure part (spec 3.5, 9.4): when it opens, what it lists, and what a pick puts in the draft. */
class MentionPaletteTest {
    // spec: @ at the start and after a space opens
    @Test
    fun `@ at the start and after a space opens`() {
        assertEquals(MentionQuery(0, ""), mentionQuery("@", 1))
        assertEquals(MentionQuery(0, "hel"), mentionQuery("@hel", 4))
        assertEquals(MentionQuery(6, "src/F"), mentionQuery("about @src/F", 12))
        assertEquals(MentionQuery(4, "x"), mentionQuery("one\n@x", 6))
        // The caret inside the mention: what precedes it.
        assertEquals(MentionQuery(0, "he"), mentionQuery("@hello", 3))
    }

    // spec: @ inside a word does not open
    @Test
    fun `@ inside a word does not open`() {
        assertNull(mentionQuery("a@b", 3))
        assertNull(mentionQuery("mail me at me@example.com", 25))
        // Once a space follows the mention, it is done.
        assertNull(mentionQuery("@hello.txt ", 11))
        assertNull(mentionQuery("plain", 5))
        assertNull(mentionQuery("", 0))
        // A caret outside the draft (a selection) opens nothing.
        assertNull(mentionQuery("@a", -1))
        assertNull(mentionQuery("@a", 3))
    }

    // spec: the filter keeps matching paths
    @Test
    fun `the filter keeps matching paths`() {
        val candidates = listOf("README.md", "hello.txt", "src/main/kotlin/Hello.kt", "docs/help.md")
        val palette = mentionPalette(MentionQuery(0, "hel"), candidates, openFirst = emptyList())
        assertEquals(listOf("hello.txt", "src/main/kotlin/Hello.kt", "docs/help.md"), palette.rows)
        assertEquals(0, palette.selected)
        // The characters in order, case ignored; an empty text keeps them all.
        assertTrue(matchesMention("src/main/kotlin/Foo.kt", "smfoo"))
        assertFalse(matchesMention("src/main/kotlin/Foo.kt", "oof"))
        assertEquals(candidates, mentionPalette(MentionQuery(0, ""), candidates, emptyList()).rows)
        // The open editors' files first, each path once.
        val opened = mentionPalette(MentionQuery(0, "hel"), candidates, openFirst = listOf("docs/help.md", "README.md"))
        assertEquals(listOf("docs/help.md", "hello.txt", "src/main/kotlin/Hello.kt"), opened.rows)
    }

    // spec: insertion adds the trailing space
    @Test
    fun `insertion adds the trailing space`() {
        assertEquals("@hello.txt " to 11, insertMention("@hel", MentionQuery(0, "hel"), "hello.txt"))
        // A space already after the typed text is kept, not doubled; the caret goes after it.
        assertEquals("see @src/A.kt now" to 14, insertMention("see @A now", MentionQuery(4, "A"), "src/A.kt"))
        assertNull(mentionQuery("@hello.txt ", 11), "the palette stays closed after the pick")
    }

    // spec: Esc closes until the draft changes
    @Test
    fun `Esc closes until the draft changes`() {
        assertEquals(MentionQuery(0, "he"), visibleMention("@he", 3, dismissedFor = null))
        assertNull(visibleMention("@he", 3, dismissedFor = "@he"))
        assertEquals(MentionQuery(0, "hel"), visibleMention("@hel", 4, dismissedFor = "@he"))
    }

    // spec: 40 rows at most
    @Test
    fun `40 rows at most`() {
        val candidates = (1..100).map { "file$it.txt" }
        val palette = mentionPalette(MentionQuery(0, "file"), candidates, openFirst = listOf("file99.txt"))
        assertEquals(MENTION_ROWS, palette.rows.size)
        assertEquals(40, palette.rows.size)
        assertEquals("file99.txt", palette.rows.first())
    }

    @Test
    fun `the selection moves and wraps, and a stale one reads as the first row`() {
        val query = MentionQuery(0, "a")
        val palette = mentionPalette(query, listOf("a1", "a2", "a3"), emptyList())
        assertEquals(1, palette.moved(1).selected)
        assertEquals(2, palette.moved(-1).selected)
        assertEquals("a1", palette.pick)
        assertEquals(MentionPalette(query, emptyList(), 0), MentionPalette(query, emptyList(), 0).moved(1))
        assertNull(MentionPalette(query, emptyList(), 0).pick)
        assertEquals(2, shownMention(query, listOf("a1", "a2", "a3"), query to 2)?.selected)
        assertEquals(0, shownMention(query, listOf("a1", "a2", "a3"), MentionQuery(0, "") to 2)?.selected)
        assertEquals(1, shownMention(query, listOf("a1", "a2"), query to 5)?.selected)
        assertNull(shownMention(null, listOf("a1"), null))
        assertNull(shownMention(MentionQuery(0, "zz"), listOf("a1"), null), "no row: Enter sends")
    }

    @Test
    fun `mentions are found at word starts only`() {
        assertEquals(
            listOf(MentionSpan(5, 15, "hello.txt"), MentionSpan(20, 25, "b.kt")),
            mentionsIn("read @hello.txt and @b.kt, a@c @"),
        )
        // Trailing punctuation ends the path; a line suffix stays with it.
        assertEquals(listOf(MentionSpan(8, 21, "hello.txt#L2")), mentionsIn("What is @hello.txt#L2?"))
        assertEquals(emptyList(), mentionsIn("no mentions here"))
    }
}

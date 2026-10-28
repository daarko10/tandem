package dev.daarkolabs.tandem.session.edits

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure core of an edit (spec 4.3, 9.3): exact matching, applying, the numbered snippet and the unified diff. */
class EditMatcherTest {
    @Test
    fun `exact match`() {
        val text = "alpha\nline two\n"
        val match = EditMatcher.match(text, "alpha", replaceAll = false)
        assertEquals(Match.Found(listOf(0 until 5)), match)
        assertEquals("beta\nline two\n", EditMatcher.apply(text, match as Match.Found, "beta"))
        assertEquals(Match.NotFound, EditMatcher.match(text, "Alpha", replaceAll = false))
        assertEquals(Match.NotFound, EditMatcher.match(text, "", replaceAll = false))
        // The applied range in the new text, for the snippet.
        assertEquals(listOf(0 until 4), EditMatcher.appliedRanges(match, "beta"))
    }

    @Test
    fun `replace_all finds every occurrence`() {
        val text = "a.b a.b\na.b"
        val match = EditMatcher.match(text, "a.b", replaceAll = true)
        assertEquals(Match.Found(listOf(0 until 3, 4 until 7, 8 until 11)), match)
        val applied = EditMatcher.apply(text, match as Match.Found, "xy")
        assertEquals("xy xy\nxy", applied)
        assertEquals(listOf(0 until 2, 3 until 5, 6 until 8), EditMatcher.appliedRanges(match, "xy"))
        // Occurrences do not overlap: "aa" in "aaa" is one place, as String.replace counts it.
        assertEquals(Match.Found(listOf(0 until 2)), EditMatcher.match("aaa", "aa", replaceAll = true))
        // One occurrence with replace_all is a plain match.
        assertEquals(Match.Found(listOf(2 until 3)), EditMatcher.match("a b", "b", replaceAll = true))
        assertEquals(Match.NotFound, EditMatcher.match("a b", "c", replaceAll = true))
        // An empty new_string deletes; its applied ranges are empty and sit where the text was.
        val deleted = EditMatcher.match("x-y-z", "-", replaceAll = true) as Match.Found
        assertEquals("xyz", EditMatcher.apply("x-y-z", deleted, ""))
        assertEquals(listOf(1, 2), EditMatcher.appliedRanges(deleted, "").map { it.first })
        assertTrue(EditMatcher.appliedRanges(deleted, "").all { it.isEmpty() })
    }

    @Test
    fun `ambiguity reports the count`() {
        assertEquals(Match.Ambiguous(3), EditMatcher.match("x = 1\nx = 1\nx = 1\n", "x = 1", replaceAll = false))
    }

    @Test
    fun `CRLF text matches on the document's newlines`() {
        // A Document holds \n only (the IDE restores the file's separator on save); the model may send \r\n.
        val text = "fun a() {\n    x()\n}\n"
        val match = EditMatcher.match(text, "{\r\n    x()\r\n}", replaceAll = false)
        assertEquals(Match.Found(listOf(8 until 19)), match)
        assertEquals("fun a() {\n    y()\n}\n", EditMatcher.apply(text, match as Match.Found, "{\r\n    y()\r\n}"))
        assertEquals(listOf(8 until 19), EditMatcher.appliedRanges(match, "{\r\n    y()\r\n}"))
        // A lone \r is a line break too.
        assertEquals(Match.Found(listOf(0 until 3)), EditMatcher.match("a\nb\n", "a\rb", replaceAll = false))
    }

    @Test
    fun `the snippet numbers the file's start and end`() {
        val text = (1..20).joinToString("\n") { "line $it" } + "\n"
        val start = text.indexOf("line 1\n")
        val atStart = EditMatcher.snippet(text, listOf(start until start + 6))
        assertEquals((1..5).joinToString("\n") { "${it.toString().padStart(6)}\tline $it" }, atStart)
        val end = text.indexOf("line 20")
        val atEnd = EditMatcher.snippet(text, listOf(end until end + 7))
        assertEquals((16..20).joinToString("\n") { "${it.toString().padStart(6)}\tline $it" }, atEnd)
        // Two distant ranges are two windows; close ones merge into one.
        val l10 = text.indexOf("line 10")
        val two = EditMatcher.snippet(text, listOf(start until start + 6, l10 until l10 + 7), context = 1)
        assertEquals("     1\tline 1\n     2\tline 2\n…\n     9\tline 9\n    10\tline 10\n    11\tline 11", two)
        val l3 = text.indexOf("line 3")
        val merged = EditMatcher.snippet(text, listOf(l3 until l3 + 6, start until start + 6), context = 1)
        assertEquals((1..4).joinToString("\n") { "${it.toString().padStart(6)}\tline $it" }, merged)
        // An empty range (a deletion) still shows its line; a range at the very end of the text shows the last line.
        val l2 = text.indexOf("line 2")
        assertEquals("     2\tline 2", EditMatcher.snippet(text, listOf(l2 until l2), context = 0))
        assertEquals("    20\tline 20", EditMatcher.snippet(text, listOf(text.length until text.length), context = 0))
        assertEquals("", EditMatcher.snippet("", listOf(0 until 0)))
        assertEquals("", EditMatcher.snippet(text, emptyList()))
    }

    @Test
    fun `numbered is cat -n`() {
        assertEquals("     1\tbeta\n     2\tline two", EditMatcher.numbered("beta\nline two\n"))
        assertEquals("     1\tbeta\n     2\tline two", EditMatcher.numbered("beta\nline two"))
        assertEquals("     1\t\n     2\tx", EditMatcher.numbered("\nx"))
        assertEquals("", EditMatcher.numbered(""))
    }

    @Test
    fun `the unified diff on an insertion, a deletion and a change`() {
        val before = "a\nb\nc\nd\ne\nf\ng\nh\n"
        val inserted = "a\nb\nc\nd\nNEW\ne\nf\ng\nh\n"
        assertEquals(
            "--- proposal\n+++ applied\n@@ -2,6 +2,7 @@\n b\n c\n d\n+NEW\n e\n f\n g",
            EditMatcher.unifiedDiff(before, inserted, "proposal", "applied"),
        )
        val deleted = "a\nc\nd\ne\nf\ng\nh\n"
        assertEquals(
            "--- x\n+++ y\n@@ -1,5 +1,4 @@\n a\n-b\n c\n d\n e",
            EditMatcher.unifiedDiff(before, deleted, "x", "y"),
        )
        // The recorded hand change of ide-edit (beta became beta!), in difflib's layout.
        assertEquals(
            "--- proposal\n+++ applied\n@@ -1,2 +1,2 @@\n-beta\n+beta!\n line two",
            EditMatcher.unifiedDiff("beta\nline two\n", "beta!\nline two\n", "proposal", "applied"),
        )
        // Two changes far apart are two hunks; a one-line side is written without its length, an empty side as start,0.
        val far = (1..20).joinToString("\n") { "l$it" }
        val changedFar = (1..20).joinToString("\n") { if (it == 2 || it == 19) "L$it" else "l$it" }
        assertEquals(
            "--- a\n+++ b\n@@ -1,5 +1,5 @@\n l1\n-l2\n+L2\n l3\n l4\n l5\n@@ -16,5 +16,5 @@\n l16\n l17\n l18\n-l19\n+L19\n l20",
            EditMatcher.unifiedDiff(far, changedFar, "a", "b"),
        )
        assertEquals("--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y", EditMatcher.unifiedDiff("x", "y", "a", "b"))
        assertEquals("--- a\n+++ b\n@@ -0,0 +1 @@\n+y", EditMatcher.unifiedDiff("", "y\n", "a", "b"))
        assertEquals("--- a\n+++ b\n@@ -1,2 +0,0 @@\n-x\n-y", EditMatcher.unifiedDiff("x\ny", "", "a", "b"))
        assertEquals("", EditMatcher.unifiedDiff(before, before, "a", "b"))
    }

    @Test
    fun `the 400-line cut`() {
        val a = (1..1000).joinToString("\n") { "old $it" }
        val b = (1..1000).joinToString("\n") { "new $it" }
        val diff = EditMatcher.unifiedDiff(a, b, "a", "b")
        val lines = diff.split('\n')
        assertEquals(400, lines.size)
        // 2 headers + 1 hunk header + 2000 changed lines = 2003 lines; 399 kept, 1604 cut.
        assertEquals("… 1604 more lines", lines.last())
        assertEquals("@@ -1,1000 +1,1000 @@", lines[2])
        assertEquals(10, EditMatcher.unifiedDiff(a, b, "a", "b", maxLines = 10).split('\n').size)
        // At the bound exactly nothing is cut.
        val small = EditMatcher.unifiedDiff("x", "y", "a", "b", maxLines = 5)
        assertEquals(5, small.split('\n').size)
        assertTrue(small.endsWith("+y"))
    }

    @Test
    fun `past 20 000 input lines the diff is the whole-file block`() {
        val a = (1..10_001).joinToString("\n") { "same $it" }
        val b = a + "\nextra"
        val diff = EditMatcher.unifiedDiff(a, b, "a", "b", maxLines = 5)
        // No common prefix is looked for: every old line is removed and every new one added, in one hunk.
        assertEquals("--- a\n+++ b\n@@ -1,10001 +1,10002 @@\n-same 1\n… 20002 more lines", diff)
        assertEquals(10_002 to 10_001, EditMatcher.lineChanges(a, b))
    }

    @Test
    fun `an edit script longer than Myers' bound is one block between the common ends`() {
        // 3000 alternating changes exceed the bound on the edit distance: the middle becomes one block of removals and additions.
        val a = (1..3000).joinToString("\n") { if (it % 2 == 0) "a$it" else "k$it" }
        val b = (1..3000).joinToString("\n") { if (it % 2 == 0) "b$it" else "k$it" }
        val (added, removed) = EditMatcher.lineChanges("head\n$a\ntail", "head\n$b\ntail")
        // head and k1 are the common start, tail the common end: the 2999 lines between are removed and added whole.
        assertEquals(2999 to 2999, added to removed)
        val diff = EditMatcher.unifiedDiff("head\n$a\ntail", "head\n$b\ntail", "a", "b", maxLines = 4)
        assertEquals("--- a\n+++ b\n@@ -1,3002 +1,3002 @@\n… 6001 more lines", diff)
    }

    @Test
    fun `lineChanges counts added and removed lines`() {
        assertEquals(1 to 0, EditMatcher.lineChanges("a\nb\n", "a\nx\nb\n"))
        assertEquals(1 to 1, EditMatcher.lineChanges("a\nb\n", "a\nc\n"))
        assertEquals(0 to 0, EditMatcher.lineChanges("a", "a"))
        assertEquals(2 to 0, EditMatcher.lineChanges("", "a\nb"))
    }
}

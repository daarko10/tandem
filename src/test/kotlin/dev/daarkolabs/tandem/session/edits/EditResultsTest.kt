package dev.daarkolabs.tandem.session.edits

import dev.daarkolabs.tandem.protocol.ToolReply
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every text of spec 4.3's table, verbatim, and its bounds: whole file ≤ 2000 lines and ≤ 64 KB, a result ≤ 100 KB. */
class EditResultsTest {
    private val path = "/p/a.kt"
    private val file = "beta\nline two\n"
    private val beta = listOf(0 until 4)
    private val whole = "The file now reads (lines 1-2 of 2):\n     1\tbeta\n     2\tline two"

    private fun ok(text: String) = ToolReply(text, isError = false)

    private fun error(text: String) = ToolReply(text, isError = true)

    private fun bytes(text: String): Int = text.encodeToByteArray().size

    @Test
    fun `accepted, unchanged proposal`() {
        assertEquals(
            ok("Applied the edit to /p/a.kt and saved the file.\n$whole"),
            EditResults.accepted(path, file, beta, proposal = null, applied = null, mode = AcceptMode.USER),
        )
    }

    @Test
    fun `accepted, proposal changed by the user`() {
        assertEquals(
            ok(
                "Applied to /p/a.kt and saved the file. The user changed your proposal in the diff before accepting it.\n" +
                    "What changed between your proposal and what was applied:\n" +
                    "--- proposal\n+++ applied\n@@ -1,2 +1,2 @@\n-beta\n+beta!\n line two\n" +
                    "The file now reads (lines 1-2 of 2):\n     1\tbeta!\n     2\tline two",
            ),
            EditResults.accepted(path, "beta!\nline two\n", listOf(0 until 5), "beta\nline two\n", "beta!\nline two\n", AcceptMode.USER_CHANGED),
        )
    }

    @Test
    fun `auto-accepted by the mode and with Accept all`() {
        assertEquals(
            ok("Applied the edit to /p/a.kt and saved the file (auto-accepted by the user's mode).\n$whole"),
            EditResults.accepted(path, file, beta, null, null, AcceptMode.AUTO_MODE),
        )
        assertEquals(
            ok("Applied the edit to /p/a.kt and saved the file (accepted with Accept all).\n$whole"),
            EditResults.accepted(path, file, beta, null, null, AcceptMode.ACCEPT_ALL),
        )
    }

    @Test
    fun `an emptied file is said so`() {
        assertEquals(
            ok("Applied the edit to /p/a.kt and saved the file.\nThe file is now empty."),
            EditResults.accepted(path, "", listOf(0 until 0), null, null, AcceptMode.USER),
        )
    }

    @Test
    fun `write_file, new file`() {
        assertEquals(ok("Created /p/new.txt (1 lines) and saved it."), EditResults.created("/p/new.txt", "new file\n", changed = false))
        assertEquals(
            ok(
                "Created /p/new.txt (2 lines) and saved it and the user changed your proposal before accepting it; the file now reads:\n" +
                    "     1\tnew file\n     2\tby hand",
            ),
            EditResults.created("/p/new.txt", "new file\nby hand\n", changed = true),
        )
    }

    @Test
    fun `write_file, existing file`() {
        assertEquals(
            ok("Replaced the content of /p/a.kt (2 lines; +1 −1 against the previous content) and saved it."),
            EditResults.replaced(path, file, "alpha\nline two\n", changed = false),
        )
        assertEquals(
            ok(
                "Replaced the content of /p/a.kt (3 lines; +2 −1 against the previous content) and saved it and the user changed " +
                    "your proposal before accepting it; the file now reads:\n     1\tbeta\n     2\tline two\n     3\tmine",
            ),
            EditResults.replaced(path, "beta\nline two\nmine\n", "alpha\nline two\n", changed = true),
        )
    }

    @Test
    fun `reject, with and without a reason`() {
        assertEquals(error("The user rejected this edit in the IDE; nothing was changed."), EditResults.rejected(null))
        assertEquals(error("The user rejected this edit in the IDE; nothing was changed."), EditResults.rejected("  "))
        assertEquals(
            error("The user rejected this edit in the IDE; nothing was changed.\nThe user says: not now"),
            EditResults.rejected(" not now "),
        )
    }

    @Test
    fun `the file changed while the diff was open`() {
        assertEquals(
            error(
                "The file changed while the diff was open; nothing was applied. /p/a.kt as open in the IDE now reads:\n" +
                    "     1\tbeta\n     2\tline two",
            ),
            EditResults.fileChanged(path, file),
        )
    }

    @Test
    fun `old_string not found`() {
        assertEquals(
            error(
                "old_string was not found in /p/a.kt. The file as open in the IDE (unsaved changes included) reads:\n" +
                    "     1\tbeta\n     2\tline two",
            ),
            EditResults.notFound(path, file),
        )
    }

    @Test
    fun `old_string not found in a long file quotes the first 200 lines`() {
        val long = (1..2001).joinToString("\n") { "l$it" }
        val reply = EditResults.notFound(path, long)
        assertTrue(reply.isError)
        assertTrue(reply.text.contains("\n   200\tl200\n… 2001 lines in all; Read the file for the rest"))
        assertTrue(reply.text.endsWith("… 2001 lines in all; Read the file for the rest"))
        assertFalse(reply.text.contains("\tl201"))
        // The same bounds for the file-changed error.
        assertTrue(EditResults.fileChanged(path, long).text.endsWith("… 2001 lines in all; Read the file for the rest"))
    }

    @Test
    fun `old_string found more than once without replace_all`() {
        assertEquals(
            error("old_string matches 3 places in /p/a.kt. Make it unique with more context, or pass replace_all: true."),
            EditResults.ambiguous(path, 3),
        )
    }

    @Test
    fun `old_string equals new_string`() {
        assertEquals(error("old_string and new_string are the same; nothing to change."), EditResults.same())
    }

    @Test
    fun `the file does not exist`() {
        assertEquals(error("/p/a.kt does not exist. Use write_file to create it."), EditResults.missing(path))
    }

    @Test
    fun `a directory`() {
        assertEquals(error("/p is a directory."), EditResults.directory("/p"))
    }

    @Test
    fun `outside the project and its added folders`() {
        assertEquals(
            error("/etc/hosts is outside the project and its added folders; Tandem edits files there only."),
            EditResults.outside("/etc/hosts"),
        )
    }

    @Test
    fun `binary`() {
        assertEquals(error("/p/a.png is a binary file; Tandem edits text files only."), EditResults.binary("/p/a.png"))
    }

    @Test
    fun `too large for the IDE`() {
        assertEquals(error("/p/big.txt is too large for the IDE to open as text (25 MB)."), EditResults.tooLarge("/p/big.txt", "25 MB"))
    }

    @Test
    fun `read-only and not made writable`() {
        assertEquals(error("/p/a.kt is read-only and could not be made writable in the IDE."), EditResults.readOnly(path))
    }

    @Test
    fun `the IDE refused the write, or the save to disk failed`() {
        assertEquals(error("The IDE could not apply the edit to /p/a.kt: disk full."), EditResults.refused(path, "disk full"))
        assertEquals(error("The IDE could not apply the edit to /p/a.kt: disk full."), EditResults.refused(path, "disk full."))
        assertEquals(
            error("The edit is in the IDE's buffer but could not be saved to /p/a.kt: access denied."),
            EditResults.unsaved(path, "access denied"),
        )
    }

    @Test
    fun `plan mode`() {
        assertEquals(error("The session is in plan mode: Tandem applies no edits until the plan is approved."), EditResults.planMode())
    }

    @Test
    fun `malformed arguments`() {
        assertEquals(error("edit_file needs file_path, old_string and new_string as strings."), EditResults.malformed("edit_file"))
        assertEquals(error("write_file needs file_path and content as strings."), EditResults.malformed("write_file"))
    }

    @Test
    fun `no text says Claude Code`() {
        val texts = listOf(
            EditResults.accepted(path, file, beta, "x", file, AcceptMode.USER_CHANGED),
            EditResults.accepted(path, file, beta, null, null, AcceptMode.AUTO_MODE),
            EditResults.created(path, file, true),
            EditResults.rejected("r"),
            EditResults.planMode(),
        ).map { it.text }
        assertTrue(texts.none { it.contains("Claude Code") })
    }

    @Test
    fun `a file over 2000 lines sends the edited region and Read for the rest`() {
        val long = (1..2001).joinToString("\n") { "l$it" } + "\n"
        val at = long.indexOf("l1000\n")
        val region = (996..1004).joinToString("\n") { "${it.toString().padStart(6)}\tl$it" }
        assertEquals(
            ok(
                "Applied the edit to /p/a.kt and saved the file. The file has 2001 lines; the edited region (line numbers as in the file):\n" +
                    "$region\nRead /p/a.kt for the rest.",
            ),
            EditResults.accepted(path, long, listOf(at until at + 5), null, null, AcceptMode.USER),
        )
        assertEquals(
            ok(
                "Applied the edit to /p/a.kt and saved the file (accepted with Accept all). The file has 2001 lines; the edited " +
                    "region (line numbers as in the file):\n$region\nRead /p/a.kt for the rest.",
            ),
            EditResults.accepted(path, long, listOf(at until at + 5), null, null, AcceptMode.ACCEPT_ALL),
        )
        val changed = EditResults.accepted(path, long, listOf(at until at + 5), long.replace("l1000\n", "x\n"), long, AcceptMode.USER_CHANGED)
        assertTrue(
            changed.text.startsWith(
                "Applied to /p/a.kt and saved the file. The user changed your proposal in the diff before accepting it.\n" +
                    "What changed between your proposal and what was applied:\n--- proposal\n+++ applied\n@@ -997,7 +997,7 @@\n",
            ),
        )
        assertTrue(changed.text.endsWith("\n l1003\nThe file has 2001 lines; the edited region:\n$region\nRead /p/a.kt for the rest."))
        // A replaced or created file past the bound quotes its first 200 lines when the user changed it.
        val created = EditResults.created(path, long, changed = true).text
        assertTrue(created.endsWith("\n   200\tl200\n… 2001 lines in all; Read the file for the rest"))
        assertEquals(ok("Created /p/a.kt (2001 lines) and saved it."), EditResults.created(path, long, changed = false))
    }

    @Test
    fun `a 64 KB file sends the region`() {
        // 1000 lines of 66 bytes: under 2000 lines, over 64 KB.
        val big = (1..1000).joinToString("\n") { "x".repeat(60) + it.toString().padStart(5, '0') }
        assertTrue(bytes(big) > 64 * 1024)
        val at = big.indexOf("00500")
        val reply = EditResults.accepted(path, big, listOf(at until at + 5), null, null, AcceptMode.USER)
        assertTrue(reply.text.startsWith("Applied the edit to /p/a.kt and saved the file. The file has 1000 lines; the edited region"))
        assertTrue(reply.text.endsWith("\nRead /p/a.kt for the rest."))
        assertTrue(reply.text.contains("   500\t"))
        // Just under the bound the whole file goes.
        val fits = (1..900).joinToString("\n") { "x".repeat(60) + it.toString().padStart(5, '0') }
        assertTrue(bytes(fits) <= 64 * 1024)
        assertTrue(EditResults.accepted(path, fits, listOf(0 until 1), null, null, AcceptMode.USER).text.contains("(lines 1-900 of 900)"))
        // An edit with no ranges past the bound quotes the file's start.
        val headOnly = EditResults.accepted(path, big, emptyList(), null, null, AcceptMode.USER).text
        assertTrue(headOnly.contains("     1\t") && headOnly.endsWith("\nRead /p/a.kt for the rest."))
    }

    @Test
    fun `a result never exceeds 100 KB`() {
        val cap = 100 * 1024
        // The whole file (about 61 KB numbered) fits; a 400-line diff of long lines does not: the diff block shrinks first.
        val proposal = (1..900).joinToString("\n") { "p".repeat(300) + it.toString().padStart(5, '0') }
        val applied = (1..900).joinToString("\n") { "a".repeat(60) + it.toString().padStart(5, '0') }
        val first = EditResults.accepted(path, applied, listOf(0 until 1), proposal, applied, AcceptMode.USER_CHANGED).text
        assertTrue(bytes(first) <= cap, "${bytes(first)}")
        assertTrue(first.contains("more lines\nThe file now reads (lines 1-900 of 900):\n"))
        assertTrue(first.endsWith("   900\t" + "a".repeat(60) + "00900"), "the file block is whole")
        // Lines too long for any region: the diff goes first, then the file block shrinks; the closing line stays.
        val huge = (1..10).joinToString("\n") { "y".repeat(30_000) }
        val second = EditResults.accepted(path, huge, listOf(0 until 1), huge.replaceFirst("y", "z"), huge, AcceptMode.USER_CHANGED).text
        assertTrue(bytes(second) <= cap, "${bytes(second)}")
        assertTrue(second.contains("What changed between your proposal and what was applied:\n… "))
        assertTrue(second.endsWith("more lines\nRead /p/a.kt for the rest."))
        // A single line too long for the bound is cut inside the line.
        val oneLine = EditResults.rejected("r".repeat(200_000)).text
        assertTrue(bytes(oneLine) <= cap)
        assertTrue(oneLine.startsWith("The user rejected this edit in the IDE; nothing was changed.\nThe user says: rrr"))
        assertTrue(oneLine.endsWith("… cut to stay under 100 KB"))
        // Multi-byte text is measured in UTF-8 bytes and never split inside a character.
        val wide = EditResults.notFound(path, "é😀".repeat(60_000)).text
        assertTrue(bytes(wide) <= cap)
        assertEquals(wide, wide.encodeToByteArray().decodeToString())
    }
}

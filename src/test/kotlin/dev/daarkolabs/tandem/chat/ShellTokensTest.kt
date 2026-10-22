package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.chat.ShellTokenKind.ARGUMENT
import dev.daarkolabs.tandem.chat.ShellTokenKind.COMMAND
import dev.daarkolabs.tandem.chat.ShellTokenKind.COMMENT
import dev.daarkolabs.tandem.chat.ShellTokenKind.OPERATOR
import dev.daarkolabs.tandem.chat.ShellTokenKind.PLAIN
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Bash body's token rules (polish spec 2.4). Adjacent PLAIN text is one token (a blank run, a newline, a `\`+newline
 * continuation and a heredoc body that touch merge), so no two PLAIN tokens are adjacent. [kinds] leaves out the whitespace-only
 * PLAIN tokens; a PLAIN token holding anything else (a continuation, a heredoc body) stays.
 */
class ShellTokensTest {
    private fun kinds(command: String): List<Pair<ShellTokenKind, String>> =
        ShellTokens.tokenize(command)
            .filterNot { it.kind == PLAIN && command.substring(it.range).isBlank() }
            .map { it.kind to command.substring(it.range) }

    @Test
    fun `the first word of each simple command is the command`() {
        assertEquals(
            listOf(COMMAND to "a", OPERATOR to "|", COMMAND to "b", OPERATOR to "&&", COMMAND to "c", OPERATOR to ";", COMMAND to "d"),
            kinds("a | b && c; d"),
        )
        assertEquals(listOf(COMMAND to "sudo", COMMAND to "apt"), kinds("sudo apt"))
        assertEquals(listOf(ARGUMENT to "FOO=1", COMMAND to "make"), kinds("FOO=1 make"))
    }

    @Test
    fun `arguments and operators`() {
        assertEquals(
            listOf(
                COMMAND to "grep", ARGUMENT to "-rniE", ARGUMENT to "\"a|b\"", ARGUMENT to "--include='*.js'", ARGUMENT to ".",
                OPERATOR to "2>", ARGUMENT to "/dev/null", OPERATOR to "|", COMMAND to "head", ARGUMENT to "-30",
            ),
            kinds("grep -rniE \"a|b\" --include='*.js' . 2>/dev/null | head -30"),
        )
    }

    @Test
    fun `an unbalanced quote runs to the end`() {
        assertEquals(listOf(COMMAND to "echo", ARGUMENT to "\"a | b"), kinds("echo \"a | b"))
        assertEquals(emptyList(), ShellTokens.tokenize(""))
        assertEquals(listOf(ShellToken(OPERATOR, 0..1)), ShellTokens.tokenize("||"))
    }

    @Test
    fun `a newline separates commands and a backslash continues one`() {
        assertEquals(listOf(COMMAND to "ls", COMMAND to "cd", ARGUMENT to "x"), kinds("ls\ncd x"))
        assertEquals(listOf(COMMAND to "ls", PLAIN to " \\\n", ARGUMENT to "-la"), kinds("ls \\\n-la"))
    }

    @Test
    fun `a heredoc body is plain`() {
        assertEquals(
            listOf(
                COMMAND to "cat", OPERATOR to "<<", ARGUMENT to "'EOF'", OPERATOR to ">", ARGUMENT to "f",
                PLAIN to "\nls -la\nEOF\n", COMMAND to "echo", ARGUMENT to "done",
            ),
            kinds("cat <<'EOF' > f\nls -la\nEOF\necho done"),
        )
    }

    @Test
    fun `command substitution opens a nested command`() {
        assertEquals(
            listOf(
                COMMAND to "cd", OPERATOR to "$(", COMMAND to "git", ARGUMENT to "rev-parse", ARGUMENT to "HEAD", OPERATOR to ")",
            ),
            kinds("cd \$(git rev-parse HEAD)"),
        )
        assertEquals(
            listOf(COMMAND to "git", ARGUMENT to "commit", ARGUMENT to "-m", ARGUMENT to "\"\$(cat <<'EOF'\nx\nEOF\n)\""),
            kinds("git commit -m \"\$(cat <<'EOF'\nx\nEOF\n)\""),
        )
    }

    @Test
    fun `braces, parameter expansion and escapes stay in their word`() {
        assertEquals(listOf(COMMAND to "echo", ARGUMENT to "\${HOME}/x"), kinds("echo \${HOME}/x"))
        assertEquals(
            listOf(
                COMMAND to "find", ARGUMENT to ".", ARGUMENT to "-name", ARGUMENT to "'*.md'", ARGUMENT to "-exec", ARGUMENT to "grep",
                ARGUMENT to "x", ARGUMENT to "{}", ARGUMENT to "\\;",
            ),
            kinds("find . -name '*.md' -exec grep x {} \\;"),
        )
        assertEquals(listOf(COMMAND to "cp", ARGUMENT to "a.{js,ts}", ARGUMENT to "b"), kinds("cp a.{js,ts} b"))
        assertEquals(listOf(ARGUMENT to "arr=(a b)"), kinds("arr=(a b)"))
        assertEquals(listOf(OPERATOR to "{", COMMAND to "a", OPERATOR to ";", OPERATOR to "}"), kinds("{ a; }"))
        assertEquals(listOf(OPERATOR to "(", COMMAND to "cd", ARGUMENT to "x", OPERATOR to ")"), kinds("(cd x)"))
    }

    @Test
    fun `a comment runs to the end of its line`() {
        assertEquals(listOf(COMMAND to "ls", COMMENT to "# list", COMMAND to "pwd"), kinds("ls # list\npwd"))
    }

    @Test
    fun `redirections, nestings and reserved words`() {
        assertEquals(
            listOf(
                COMMAND to "npm", ARGUMENT to "test", OPERATOR to "2>&1", OPERATOR to "|&", COMMAND to "tail", OPERATOR to ">>",
                ARGUMENT to "log",
            ),
            kinds("npm test 2>&1 |& tail >>log"),
        )
        assertEquals(
            listOf(
                COMMAND to "a", OPERATOR to "&>", ARGUMENT to "x", OPERATOR to "&", COMMAND to "b", OPERATOR to ">&2", OPERATOR to "<<<",
                ARGUMENT to "y",
            ),
            kinds("a &> x & b >&2 <<< y"),
        )
        assertEquals(
            listOf(COMMAND to "echo", OPERATOR to "`", COMMAND to "date", OPERATOR to "`", ARGUMENT to "x"),
            kinds("echo `date` x"),
        )
        assertEquals(
            listOf(COMMAND to "diff", OPERATOR to "<(", COMMAND to "ls", ARGUMENT to "a", OPERATOR to ")", ARGUMENT to "b"),
            kinds("diff <(ls a) b"),
        )
        assertEquals(
            listOf(ARGUMENT to "FOO=", OPERATOR to "$(", COMMAND to "pwd", OPERATOR to ")", ARGUMENT to "/bin", COMMAND to "make"),
            kinds("FOO=\$(pwd)/bin make"),
        )
        assertEquals(listOf(COMMAND to "echo", ARGUMENT to "\$((n+1))"), kinds("echo \$((n+1))"))
        assertEquals(
            listOf(
                COMMAND to "for", ARGUMENT to "f", ARGUMENT to "in", ARGUMENT to "*.kt", OPERATOR to ";", COMMAND to "do",
                COMMAND to "echo", ARGUMENT to "\$f", OPERATOR to ";", COMMAND to "done",
            ),
            kinds("for f in *.kt; do echo \$f; done"),
        )
        assertEquals(listOf(COMMAND to "sudo", ARGUMENT to "-E", COMMAND to "apt", ARGUMENT to "-y"), kinds("sudo -E apt -y"))
        assertEquals(
            listOf(COMMAND to "cat", OPERATOR to "<<-", ARGUMENT to "END", PLAIN to "\n\tbody\n\tEND\n", COMMAND to "ls"),
            kinds("cat <<-END\n\tbody\n\tEND\nls"),
        )
        // An unterminated heredoc and an unbalanced expansion run to the end.
        assertEquals(listOf(COMMAND to "cat", OPERATOR to "<<", ARGUMENT to "EOF", PLAIN to "\nls\nmore"), kinds("cat <<EOF\nls\nmore"))
        assertEquals(listOf(COMMAND to "echo", ARGUMENT to "\${a b"), kinds("echo \${a b"))
        // A `)` or `}` with nothing open stays in its word.
        assertEquals(listOf(COMMAND to "a)", ARGUMENT to "}"), kinds("a) }"))
    }

    @Test
    fun `tokens cover the command without gaps`() {
        val commands = listOf(
            "a | b && c; d", "sudo apt", "FOO=1 make", "grep -rniE \"a|b\" --include='*.js' . 2>/dev/null | head -30", "echo \"a | b",
            "", "||", "ls\ncd x", "ls \\\n-la", "cat <<'EOF' > f\nls -la\nEOF\necho done", "cd \$(git rev-parse HEAD)",
            "git commit -m \"\$(cat <<'EOF'\nx\nEOF\n)\"", "echo \${HOME}/x", "find . -name '*.md' -exec grep x {} \\;", "cp a.{js,ts} b",
            "arr=(a b)", "{ a; }", "(cd x)", "ls # list\npwd",
        )
        for (command in commands) assertCovers(command)
        val word = "echo " + "a".repeat(50_000)
        val heredoc = "cat <<EOF\n" + "a ".repeat(25_000) + "\nEOF\necho done"
        val started = System.nanoTime()
        assertCovers(word)
        assertCovers(heredoc)
        // A guard against a quadratic scan, not a benchmark.
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "tokenizing 100,000 characters took over 2 s")
    }

    private fun assertCovers(command: String) {
        val tokens = ShellTokens.tokenize(command)
        var next = 0
        tokens.forEach { token ->
            assertEquals(next, token.range.first, command)
            assertFalse(token.range.isEmpty(), command)
            next = token.range.last + 1
        }
        assertEquals(command.length, next, command)
        tokens.zipWithNext().forEach { (a, b) -> assertFalse(a.kind == PLAIN && b.kind == PLAIN, command) }
    }
}

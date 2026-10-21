package dev.daarkolabs.tandem.chat

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** What a stretch of a shell command is, for its colour in the Bash body (polish spec 2.4). */
enum class ShellTokenKind { COMMAND, ARGUMENT, OPERATOR, COMMENT, PLAIN }

/** One stretch of a command, [range] its characters. */
data class ShellToken(val kind: ShellTokenKind, val range: IntRange)

/**
 * Splits a shell command into the tokens the Bash body colours (polish spec 2.4): the first word of each simple command, the other
 * words, the operators, comments, and plain text (whitespace, `\`+newline continuations, heredoc bodies). Pure; it runs where the
 * body is built, off the EDT, once per body.
 */
object ShellTokens {
    /** [command]'s tokens in order, covering it without gaps; adjacent plain text is one token. `""` has none. */
    fun tokenize(command: String): ImmutableList<ShellToken> = Scanner(command).run()
}

/** Words after which the next word is a command again (the spec's list). */
private val PREFIXES: Set<String> = setOf("sudo", "env", "time", "nohup", "xargs", "exec", "command")

/** Reserved words a simple command follows (`if x; then y; fi`, `for …; do y; done`), so its first word stays the command. */
private val KEYWORDS: Set<String> = setOf("if", "then", "else", "elif", "do", "while", "until", "!")

/** An open nesting: its opener and whether a command may follow its closer ([Scanner.atCommand] then). */
private class Nest(val opener: Char, val restore: Boolean)

/** The opener of a subshell; [SUBSTITUTION] stands for `$(`, `<(` and `>(`, [BACKTICK] for a backtick and [GROUP] for `{`. */
private const val SUBSHELL = '('
private const val SUBSTITUTION = '$'
private const val BACKTICK = '`'
private const val GROUP = '{'

/** One left-to-right scan over [s]; every step emits at least one character, so the scan is linear. */
private class Scanner(private val s: String) {
    private val n = s.length
    private val tokens = ArrayList<ShellToken>()

    /** The next word is the first word of a simple command. */
    private var atCommand = true

    /** The command word before was a [PREFIXES] word: flags after it keep [atCommand]. */
    private var afterPrefix = false

    /** The previous token was a word or the closer of a substitution, with nothing between it and the next character. */
    private var glued = false

    /** The next word is a redirection's target. */
    private var expectTarget = false

    /** After `<<` (false) or `<<-` (true): the next word is a heredoc's delimiter. */
    private var heredocNext: Boolean? = null

    /** Heredocs whose bodies start after the current line: delimiter, leading tabs allowed. */
    private val heredocs = ArrayList<Pair<String, Boolean>>()
    private val nests = ArrayList<Nest>()

    fun run(): ImmutableList<ShellToken> {
        var i = 0
        while (i < n) i = step(i)
        return tokens.toImmutableList()
    }

    private fun step(i: Int): Int {
        val c = s[i]
        val next = if (i + 1 < n) s[i + 1] else NUL
        return when {
            c == '\n' -> newline(i)
            c.isWhitespace() -> blanks(i)
            c == '\\' && next == '\n' -> plain(i, i + 2)
            c == '#' && !glued -> comment(i)
            c == '|' -> separator(i, if (next == '|' || next == '&') 2 else 1)
            c == '&' && next == '&' -> separator(i, 2)
            c == '&' && next == '>' -> redirect(i, i + 1)
            c == '&' -> separator(i, 1)
            c == ';' -> separator(i, if (next == ';') 2 else 1)
            c == '<' || c == '>' -> redirect(i, i)
            c in '0'..'9' && !glued && digitsBeforeRedirect(i) -> redirect(i, digitsEnd(i))
            c == '`' -> if (nests.lastOrNull()?.opener == BACKTICK) close(i) else open(i, 1, BACKTICK)
            c == '$' && next == '(' && (i + 2 >= n || s[i + 2] != '(') -> open(i, 2, SUBSTITUTION)
            c == ')' && closesParen() -> close(i)
            c == '(' && atCommand && !glued -> open(i, 1, SUBSHELL)
            c == '{' && atCommand && !glued && endsWord(i + 1, braceOnly = true) -> open(i, 1, GROUP)
            c == '}' && atCommand && !glued && nests.lastOrNull()?.opener == GROUP && endsWord(i + 1, braceOnly = false) -> close(i)
            else -> word(i)
        }
    }

    private fun emit(kind: ShellTokenKind, start: Int, end: Int) {
        val last = tokens.lastOrNull()
        if (kind == ShellTokenKind.PLAIN && last?.kind == ShellTokenKind.PLAIN) {
            tokens[tokens.size - 1] = ShellToken(kind, last.range.first until end)
        } else {
            tokens += ShellToken(kind, start until end)
        }
    }

    private fun plain(start: Int, end: Int): Int {
        emit(ShellTokenKind.PLAIN, start, end)
        glued = false
        return end
    }

    private fun blanks(i: Int): Int {
        var j = i
        while (j < n && s[j] != '\n' && s[j].isWhitespace()) j++
        return plain(i, j)
    }

    /** A newline ends the simple command; the bodies of the heredocs opened on its line follow it. */
    private fun newline(i: Int): Int {
        plain(i, i + 1)
        endCommand()
        var j = i + 1
        for ((delimiter, tabs) in heredocs) j = heredocEnd(j, delimiter, tabs)
        heredocs.clear()
        return if (j > i + 1) plain(i + 1, j) else j
    }

    /** Where the heredoc body starting at [from] ends: after its [delimiter] line, or at the end of the command. */
    private fun heredocEnd(from: Int, delimiter: String, tabs: Boolean): Int {
        var line = from
        while (line < n) {
            val end = s.indexOf('\n', line).let { if (it < 0) n else it }
            var start = line
            if (tabs) while (start < end && s[start] == '\t') start++
            val next = if (end < n) end + 1 else n
            if (end - start == delimiter.length && s.regionMatches(start, delimiter, 0, delimiter.length)) return next
            line = next
        }
        return n
    }

    private fun comment(i: Int): Int {
        val end = s.indexOf('\n', i).let { if (it < 0) n else it }
        emit(ShellTokenKind.COMMENT, i, end)
        return end
    }

    private fun endCommand() {
        atCommand = true
        afterPrefix = false
        expectTarget = false
        heredocNext = null
        glued = false
    }

    private fun separator(i: Int, length: Int): Int {
        emit(ShellTokenKind.OPERATOR, i, i + length)
        endCommand()
        return i + length
    }

    private fun digitsEnd(i: Int): Int {
        var j = i
        while (j < n && s[j] in '0'..'9') j++
        return j
    }

    private fun digitsBeforeRedirect(i: Int): Boolean = digitsEnd(i).let { it < n && (s[it] == '<' || s[it] == '>') }

    /**
     * A redirection starting at [start] whose `<` or `>` is at [at]: `<`, `<<`, `<<-`, `<<<`, `<>`, `<&n`, `>`, `>>`, `>|`, `>&n`,
     * after a descriptor (`2>`, `2>&1`) or `&` (`&>`, `&>>`). `<(` and `>(` open a process substitution instead.
     */
    private fun redirect(start: Int, at: Int): Int {
        val c = s[at]
        if (start == at && at + 1 < n && s[at + 1] == '(') return open(start, 2, SUBSTITUTION)
        var j = at + 1
        var target = true
        when {
            c == '<' && s.startsWith("<<", at) && !s.startsWith("<<<", at) -> {
                j = at + 2
                val tabs = j < n && s[j] == '-'
                if (tabs) j++
                emit(ShellTokenKind.OPERATOR, start, j)
                glued = false
                expectTarget = false
                heredocNext = tabs
                return j
            }
            c == '<' && s.startsWith("<<<", at) -> j = at + 3
            j < n && (s[j] == '>' && c == '>' || s[j] == '|' && c == '>' || s[j] == '>' && c == '<') -> j++
            j < n && s[j] == '&' -> {
                j++
                val fd = j
                while (j < n && (s[j] in '0'..'9' || s[j] == '-')) j++
                target = j == fd
            }
        }
        emit(ShellTokenKind.OPERATOR, start, j)
        glued = false
        expectTarget = target
        heredocNext = null
        return j
    }

    private fun open(i: Int, length: Int, opener: Char): Int {
        emit(ShellTokenKind.OPERATOR, i, i + length)
        // Glued to a word before it (`FOO=$(pwd)`, `a$(b)`), the substitution belongs to that word, and a command may follow it
        // exactly when one could follow the word.
        val restore = (opener == SUBSTITUTION || opener == BACKTICK) && glued && atCommand
        nests += Nest(opener, restore)
        atCommand = true
        afterPrefix = false
        expectTarget = false
        heredocNext = null
        glued = false
        return i + length
    }

    private fun close(i: Int): Int {
        emit(ShellTokenKind.OPERATOR, i, i + 1)
        val nest = nests.removeAt(nests.size - 1)
        atCommand = nest.restore
        afterPrefix = false
        expectTarget = false
        heredocNext = null
        glued = nest.opener == SUBSTITUTION || nest.opener == BACKTICK
        return i + 1
    }

    private fun closesParen(): Boolean = nests.lastOrNull()?.opener.let { it == SUBSHELL || it == SUBSTITUTION }

    /** Whether the character at [at] ends a word: whitespace or the end, or for a `}` also an operator. */
    private fun endsWord(at: Int, braceOnly: Boolean): Boolean {
        if (at >= n || s[at].isWhitespace()) return true
        return !braceOnly && s[at] in "|&;<>)`"
    }

    /** A word starting at [i]: it runs over quotes, escapes, `${…}`, `$((…))` and `NAME=(…)` to whitespace or an operator. */
    private fun word(i: Int): Int {
        val assignment = assignmentEnd(i)
        var j = i
        while (j < n) {
            val c = s[j]
            val next = if (j + 1 < n) s[j + 1] else NUL
            j = when {
                c.isWhitespace() || c in "|&;<>`" -> break
                c == ')' && closesParen() -> break
                c == '\\' -> if (next == '\n') break else minOf(j + 2, n)
                c == '\'' -> s.indexOf('\'', j + 1).let { if (it < 0) n else it + 1 }
                c == '"' -> skipDouble(j + 1)
                c == '$' && next == '{' -> skipNested(j + 1, '{', '}')
                c == '$' && next == '(' && j + 2 < n && s[j + 2] == '(' -> skipNested(j + 1, '(', ')')
                c == '$' && next == '(' -> break
                c == '(' && j == assignment -> skipNested(j, '(', ')')
                else -> j + 1
            }
        }
        if (j == i) j = i + 1
        emit(classify(i, j, assignment >= 0), i, j)
        glued = true
        return j
    }

    private fun classify(start: Int, end: Int, assignment: Boolean): ShellTokenKind {
        val delimiter = heredocNext
        return when {
            delimiter != null -> {
                heredocs += unquote(start, end) to delimiter
                heredocNext = null
                ShellTokenKind.ARGUMENT
            }
            expectTarget -> {
                expectTarget = false
                ShellTokenKind.ARGUMENT
            }
            glued || !atCommand || assignment -> ShellTokenKind.ARGUMENT
            afterPrefix && s[start] == '-' -> ShellTokenKind.ARGUMENT
            else -> {
                val word = s.substring(start, end)
                afterPrefix = word in PREFIXES
                atCommand = afterPrefix || word in KEYWORDS
                ShellTokenKind.COMMAND
            }
        }
    }

    /** The index after `NAME=` (or `NAME+=`) when the word at [i] starts with one, else -1. */
    private fun assignmentEnd(i: Int): Int {
        if (i >= n || !s[i].isNameStart()) return -1
        var j = i + 1
        while (j < n && (s[j].isNameStart() || s[j] in '0'..'9')) j++
        if (j < n && s[j] == '+') j++
        return if (j < n && s[j] == '=') j + 1 else -1
    }

    /** After a double quote's opening at [from]: the index after its closing quote, `\"` escaped; an unbalanced quote runs to the end. */
    private fun skipDouble(from: Int): Int {
        var j = from
        while (j < n) {
            when (s[j]) {
                '\\' -> j += 2
                '"' -> return j + 1
                else -> j++
            }
        }
        return n
    }

    /** From the [open] at [from]: the index after its matching [close], quotes skipped; unbalanced, the end. */
    private fun skipNested(from: Int, open: Char, close: Char): Int {
        var depth = 0
        var j = from
        while (j < n) {
            val c = s[j]
            j = when (c) {
                '\\' -> j + 2
                '\'' -> s.indexOf('\'', j + 1).let { if (it < 0) n else it + 1 }
                '"' -> skipDouble(j + 1)
                open -> { depth++; j + 1 }
                close -> { depth--; if (depth == 0) return j + 1 else j + 1 }
                else -> j + 1
            }
        }
        return n
    }

    /** A heredoc delimiter word without its quotes and backslashes: `'EOF'`, `"EOF"` and `\EOF` all end at `EOF`. */
    private fun unquote(start: Int, end: Int): String = buildString {
        for (k in start until end) if (s[k] != '\'' && s[k] != '"' && s[k] != '\\') append(s[k])
    }

    private companion object {
        const val NUL: Char = '\u0000'
    }
}

/** A letter or `_` of a shell variable name (ASCII only, as the shell reads them). */
private fun Char.isNameStart(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this == '_'

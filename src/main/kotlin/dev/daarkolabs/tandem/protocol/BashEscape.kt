package dev.daarkolabs.tandem.protocol

/**
 * The tags of a shell escape (`! command`): what the terminal writes for one, and what Tandem sends for one (docs/protocol.md 3,
 * 11.4; bash probes 1). The command goes in [input]; its output in [output], stdout and stderr apart; [context] is both on one
 * line, as the CLI merges two quiet sends (`transcripts/bash-mode.jsonl:5`).
 */
object BashEscape {
    const val INPUT_OPEN: String = "<bash-input>"
    const val INPUT_CLOSE: String = "</bash-input>"
    const val STDOUT_OPEN: String = "<bash-stdout>"
    const val STDOUT_CLOSE: String = "</bash-stdout>"
    const val STDERR_OPEN: String = "<bash-stderr>"
    const val STDERR_CLOSE: String = "</bash-stderr>"

    /** The command as frame 1 of bash probes 1 carries it. */
    fun input(command: String): String = INPUT_OPEN + command + INPUT_CLOSE

    /** The output as frame 2 of bash probes 1 carries it. */
    fun output(stdout: String, stderr: String): String = STDOUT_OPEN + stdout + STDOUT_CLOSE + STDERR_OPEN + stderr + STDERR_CLOSE

    /** The command and its output on one line, a line break between them: the merged line of `transcripts/bash-mode.jsonl:5`. */
    fun context(command: String, stdout: String, stderr: String): String = input(command) + "\n" + output(stdout, stderr)

    /**
     * The parts of a user line's [text] that starts with one of the tags (leading whitespace aside); null for any other text.
     *
     * The command runs from [INPUT_OPEN] to the first [INPUT_CLOSE], as written (a leading space stays: bash probes 2.1). Stdout runs
     * from the first [STDOUT_OPEN] after the command to the *last* [STDOUT_CLOSE]; stderr from the first [STDERR_OPEN] after that
     * (after the command when there is no stdout) to the *last* [STDERR_CLOSE]. So an output that itself holds a closing tag, or a
     * `<bash-stderr>…</bash-stderr>` pair (a `cat` of a transcript), reloads whole and stays stdout. A part whose tags are missing is
     * null.
     */
    fun parse(text: String): BashLine? {
        val line = text.trim()
        if (!line.startsWith(INPUT_OPEN) && !line.startsWith(STDOUT_OPEN) && !line.startsWith(STDERR_OPEN)) return null
        var from = 0
        var command: String? = null
        if (line.startsWith(INPUT_OPEN)) {
            val close = line.indexOf(INPUT_CLOSE, INPUT_OPEN.length)
            if (close >= 0) {
                command = line.substring(INPUT_OPEN.length, close)
                from = close + INPUT_CLOSE.length
            }
        }
        var stdout: String? = null
        val stdoutOpen = line.indexOf(STDOUT_OPEN, from)
        if (stdoutOpen >= 0) {
            val start = stdoutOpen + STDOUT_OPEN.length
            val close = line.lastIndexOf(STDOUT_CLOSE)
            if (close >= start) {
                stdout = line.substring(start, close)
                from = close + STDOUT_CLOSE.length
            }
        }
        var stderr: String? = null
        val stderrOpen = line.indexOf(STDERR_OPEN, from)
        if (stderrOpen >= 0) {
            val start = stderrOpen + STDERR_OPEN.length
            val close = line.lastIndexOf(STDERR_CLOSE)
            if (close >= start) stderr = line.substring(start, close)
        }
        return BashLine(command, stdout, stderr)
    }
}

/** A shell escape's line, as [BashEscape.parse] reads it: each part null when the line does not have it. */
data class BashLine(val command: String?, val stdout: String?, val stderr: String?) {
    /**
     * What a reloaded row shows: [stdout] (`""` when null), then a non-empty [stderr] on a line of its own (a line break only when
     * stdout is not empty and does not end with one). Tandem's own frames keep stderr empty; a CLI that fills it is still shown.
     */
    fun shown(): String {
        val out = stdout.orEmpty()
        val err = stderr.orEmpty()
        if (err.isEmpty()) return out
        return if (out.isEmpty() || out.endsWith("\n")) out + err else out + "\n" + err
    }
}

package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.int
import dev.daarkolabs.tandem.protocol.long
import dev.daarkolabs.tandem.protocol.obj
import dev.daarkolabs.tandem.protocol.string
import dev.daarkolabs.tandem.session.TaskInfo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The one-line labels of tool rows (spec 4.6.2): pure string rules over a call's name, input, state and result. */
object ToolLabels {
    internal const val NAME_MAX = 48
    private const val COMMAND_MAX = 60

    /** A call's labels in each tense; [denied] replaces `<base> · Denied` where a tool has its own wording. */
    private class Forms(val running: RowLabel, val done: RowLabel, val failed: RowLabel, val base: RowLabel, val denied: RowLabel? = null)

    /**
     * The label of a call of [kind] named [name] in [state]. [input] is null while it streams; [result] is its `tool_use_result`, or
     * its text when it has none; [cwd] makes paths relative. A hidden call (a `ToolSearch` that did not fail) gets an empty text.
     * The input is never parsed from a fragment: without it the label names the action alone.
     */
    fun label(kind: ToolKind, name: String, input: JsonObject?, state: ToolState, result: JsonElement?, cwd: String?): RowLabel {
        // The CLI's registry error: the tool never ran, so no tool-specific failure describes it.
        if (state == ToolState.FAILED && isUnavailable(result)) return plain("Failed to use ${displayName(name)}")
        if (kind == ToolKind.TOOL_SEARCH) return plain(if (state == ToolState.FAILED) "Failed to load tools" else "")
        if (state == ToolState.PENDING || state == ToolState.WAITING && input == null) return plain(pendingVerb(kind) + "…")
        val forms = forms(kind, name, input, result, cwd)
        return when (state) {
            ToolState.PENDING, ToolState.RUNNING, ToolState.WAITING -> forms.running
            ToolState.DONE -> forms.done
            ToolState.FAILED -> forms.failed
            ToolState.DENIED -> forms.denied ?: forms.base.suffixed(" · Denied")
            ToolState.INTERRUPTED -> forms.base.suffixed(" · Interrupted")
        }
    }

    /** An Agent call's sub-line from its result: `Done (2 tool uses · 21.8k tokens · 5.8s)`; null when a total is missing. */
    fun agentProgress(result: JsonElement?): String? {
        val totals = result as? JsonObject ?: return null
        val uses = totals.int("totalToolUseCount") ?: return null
        val tokens = totals.long("totalTokens") ?: return null
        val duration = totals.long("totalDurationMs") ?: return null
        return "Done (${progress(uses, tokens, duration)})"
    }

    /** A running subagent's sub-line from its latest progress: `1 tool use · 21.5k tokens · 2.0s`; null until all three are known. */
    fun agentProgress(task: TaskInfo): String? {
        val uses = task.toolUses ?: return null
        val tokens = task.totalTokens ?: return null
        val duration = task.durationMs ?: return null
        return progress(uses, tokens, duration)
    }

    private fun progress(uses: Int, tokens: Long, duration: Long): String =
        "$uses tool use${if (uses == 1) "" else "s"} · ${formatTokens(tokens)} tokens · ${formatDuration(duration)}"

    /** [s] cut to [max] characters with `…` in the middle, so both ends of a path or command stay readable. */
    fun middleTruncate(s: String, max: Int): String {
        if (s.length <= max) return s
        if (max < 2) return s.take(max)
        val keep = max - 1
        val head = (keep + 1) / 2
        return s.take(head) + "…" + s.takeLast(keep - head)
    }

    /** [path] relative to [cwd] when strictly under it; equal to it, outside it or without a cwd: its file name. */
    fun relativePath(path: String, cwd: String?): String {
        if (!cwd.isNullOrEmpty()) {
            val base = cwd.trimEnd('/', '\\')
            val under = path.length > base.length + 1 && path.startsWith(base) && (path[base.length] == '/' || path[base.length] == '\\')
            if (under) path.substring(base.length + 1).trimEnd('/', '\\').takeIf { it.isNotEmpty() }?.let { return it }
        }
        return basename(path)
    }

    private fun basename(path: String): String =
        path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

    /** The host of [url]: no scheme, no credentials, no path, query or fragment (`https://docs.example.com/a?q=1` → `docs.example.com`). */
    fun hostOf(url: String): String {
        val withoutScheme = url.trim().let { u -> u.indexOf("://").let { if (it >= 0) u.substring(it + 3) else u } }
        return withoutScheme.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@').ifEmpty { url }
    }

    private val ansi = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B\\[[0-?]*[ -/]*[@-~]|\u001B[@-_]")

    /** [s] without terminal escape sequences (colours, cursor moves, titles). */
    fun stripAnsi(s: String): String = if ('\u001B' in s) ansi.replace(s, "") else s

    private val reminder = Regex("<system-reminder>.*?(?:</system-reminder>|$)", RegexOption.DOT_MATCHES_ALL)

    /** [s] without the CLI's `<system-reminder>` blocks (an unclosed one runs to the end), and the space they leave at the end. */
    fun stripSystemReminders(s: String): String = if ("<system-reminder>" in s) reminder.replace(s, "").trimEnd() else s

    private fun forms(kind: ToolKind, name: String, input: JsonObject?, result: JsonElement?, cwd: String?): Forms = when (kind) {
        ToolKind.READ -> {
            val file = fileName(input, cwd)
            Forms(phrase("Reading", file), phrase("Read", file, tail = readRange(input, result)), phrase("Failed to read", file), phrase("Read", file))
        }
        ToolKind.EDIT -> fileForms("Editing", "Edited", "Failed to edit", "Edit", fileName(input, cwd))
        ToolKind.CREATE -> fileName(input, cwd).let { file ->
            Forms(
                phrase("Creating", file),
                phrase(if (ToolBodies.isWriteUpdate(result)) "Edited" else "Created", file),
                phrase("Failed to write", file),
                phrase("Write", file),
            )
        }
        ToolKind.SHELL -> shellForms(input)
        ToolKind.SEARCH -> pattern(input).let { p ->
            Forms(phrase("Searching for", p), phrase("Searched for", p, tail = matches(result)), phrase("Failed to search for", p), phrase("Search for", p))
        }
        ToolKind.FIND -> pattern(input).let { p ->
            Forms(phrase("Finding files", p), plain(found(result)), plain("Failed to find files"), phrase("Find files", p))
        }
        ToolKind.WEB_FETCH -> input?.string("url")?.let { middleTruncate(hostOf(it), NAME_MAX) }.let { host ->
            fileForms("Fetching", "Fetched", "Failed to fetch", "Fetch", host)
        }
        ToolKind.WEB_SEARCH -> input?.string("query")?.let { "\"" + middleTruncate(oneLine(it), COMMAND_MAX) + "\"" }.let { q ->
            Forms(phrase("Searching the web for", q), phrase("Searched the web for", q), plain("Failed to search the web"), phrase("Search the web for", q))
        }
        ToolKind.AGENT -> input?.string("description")?.let(::words)?.takeIf { it.isNotBlank() }.let { d ->
            Forms(
                phrase("Running an agent", d, mono = false, separator = ": "),
                phrase("Ran an agent", d, mono = false, separator = ": "),
                phrase("Failed to run an agent", d, mono = false, separator = ": "),
                phrase("Run an agent", d, mono = false, separator = ": "),
            )
        }
        ToolKind.QUESTION ->
            Forms(plain("Asking a question"), plain("Questions"), plain("Failed to ask a question"), plain("Ask a question"), plain("Question cancelled"))
        ToolKind.PLAN ->
            Forms(plain("Proposing a plan"), plain("Plan approved"), plain("Failed to exit plan mode"), plain("Exit plan mode"), plain("Plan sent back"))
        ToolKind.PLAN_ENTER ->
            Forms(plain("Entering plan mode"), plain("Entered plan mode"), plain("Failed to enter plan mode"), plain("Enter plan mode"))
        ToolKind.TASKS -> Forms(plain("Updating tasks"), plain("Updated tasks"), plain("Failed to update tasks"), plain("Update tasks"))
        ToolKind.MCP -> mcpName(name).let { tool ->
            Forms(plain("Using $tool"), plain("Used $tool"), plain("Failed to use $tool"), plain("Use $tool"))
        }
        // TOOL_SEARCH never gets here (label hides it or names its failure).
        ToolKind.TOOL_SEARCH, ToolKind.OTHER -> displayName(name).let { tool ->
            Forms(plain("Using $tool"), plain("Used $tool"), plain("Failed to use $tool"), plain("Use $tool"))
        }
    }

    private fun fileForms(running: String, done: String, failed: String, base: String, obj: String?): Forms =
        Forms(phrase(running, obj), phrase(done, obj), phrase(failed, obj), phrase(base, obj))

    /** Bash and PowerShell: the description when the call has one (spec 4.6.2), else the command. */
    private fun shellForms(input: JsonObject?): Forms {
        val command = input?.string("command")?.let { middleTruncate(oneLine(it), COMMAND_MAX) }
        val description = input?.string("description")?.let(::words)?.takeIf { it.isNotBlank() }
            ?: return Forms(phrase("Running", command), phrase("Ran", command), phrase("Failed to run", command), phrase("Run", command))
        return Forms(
            plain(conjugate(description)),
            plain("Ran " + withoutRunVerb(description)),
            plain("Failed to " + lowercaseFirst(description)),
            plain(description),
        )
    }

    /** `Ran <desc>` reads badly as `Ran Run …`: a leading `Run`, `Running` or `Execute` is dropped. */
    private fun withoutRunVerb(description: String): String {
        val verb = listOf("Run ", "Running ", "Execute ").firstOrNull { description.startsWith(it, ignoreCase = true) }
        return if (verb != null && description.length > verb.length) description.substring(verb.length) else description
    }

    /** The first letter lowercased, unless the first word is an acronym (`NPM`). */
    private fun lowercaseFirst(s: String): String =
        if (s.length > 1 && s[1].isUpperCase()) s else s.replaceFirstChar { it.lowercaseChar() }

    private fun pendingVerb(kind: ToolKind): String = when (kind) {
        ToolKind.READ -> "Reading"
        ToolKind.EDIT -> "Editing"
        ToolKind.CREATE -> "Creating"
        ToolKind.SHELL, ToolKind.AGENT -> "Running"
        ToolKind.SEARCH, ToolKind.WEB_SEARCH -> "Searching"
        ToolKind.FIND -> "Finding"
        ToolKind.WEB_FETCH -> "Fetching"
        ToolKind.QUESTION -> "Asking"
        ToolKind.PLAN -> "Proposing"
        ToolKind.PLAN_ENTER -> "Entering"
        ToolKind.TASKS -> "Updating"
        ToolKind.MCP, ToolKind.OTHER, ToolKind.TOOL_SEARCH -> "Using"
    }

    /** The file a call names, relative to [cwd] and middle-truncated. */
    private fun fileName(input: JsonObject?, cwd: String?): String? = filePath(input)?.let { middleTruncate(relativePath(it, cwd), NAME_MAX) }

    /** The untruncated path a file tool names: `file_path`, a notebook's `notebook_path`, or `path`. */
    internal fun filePath(input: JsonObject?): String? =
        input?.let { it.string("file_path") ?: it.string("notebook_path") ?: it.string("path") }

    /** A read's line range, `:<start>-<end>`, when it asked for part of the file and the result says which lines came back. */
    private fun readRange(input: JsonObject?, result: JsonElement?): String {
        val asked = input != null && (input["offset"].isSet() || input["limit"].isSet())
        val file = (result as? JsonObject)?.obj("file") ?: return ""
        val start = file.int("startLine") ?: return ""
        val count = file.int("numLines") ?: return ""
        return if (asked && count > 0) ":$start-${start + count - 1}" else ""
    }

    private fun JsonElement?.isSet(): Boolean = this != null && this != JsonNull

    private fun pattern(input: JsonObject?): String? = input?.string("pattern")?.let { middleTruncate(oneLine(it), COMMAND_MAX) }

    private fun matches(result: JsonElement?): String {
        val count = (result as? JsonObject)?.int("numLines") ?: return ""
        return when {
            count <= 0 -> ""
            count == 1 -> " · 1 match"
            else -> " · $count matches"
        }
    }

    private fun found(result: JsonElement?): String {
        val files = result as? JsonObject
        return when (val count = files?.int("numFiles") ?: (files?.get("filenames") as? JsonArray)?.size) {
            null -> "Found files"
            0 -> "Found no files"
            1 -> "Found 1 file"
            else -> "Found $count files"
        }
    }

    /** `mcp__<server>__<tool>` as `<server>: <tool>`, underscores as spaces; the `mcp__` prefix never shows. */
    private fun mcpName(name: String): String {
        val parts = name.removePrefix("mcp__").split("__", limit = 2)
        val server = parts[0].replace('_', ' ')
        return if (parts.size < 2) server else "$server: ${parts[1].replace('_', ' ')}"
    }

    /** Whether [result] is the CLI's `No such tool available` error. */
    internal fun isUnavailable(result: JsonElement?): Boolean =
        errorMessage(result)?.startsWith("No such tool available") == true

    /** An error result's message: its string without the `<tool_use_error>` tags and a leading `Error: `. */
    private fun errorMessage(result: JsonElement?): String? {
        val text = (result as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return ToolBodies.withoutErrorTags(text).trim().removePrefix("Error: ")
    }

    /** A command on one line: line breaks become `; `. */
    internal fun oneLine(s: String): String = s.trim().replace(Regex("\\s*\\r?\\n\\s*"), "; ")

    /** A description on one line: line breaks become spaces. */
    private fun words(s: String): String = s.trim().replace(Regex("\\s*\\r?\\n\\s*"), " ")

    private fun plain(text: String): RowLabel = RowLabel(text, persistentListOf())

    /** `<verb><separator><obj><tail>`, with [obj] drawn in the editor font when [mono]; just `<verb><tail>` without one. */
    private fun phrase(verb: String, obj: String?, mono: Boolean = true, separator: String = " ", tail: String = ""): RowLabel {
        if (obj.isNullOrEmpty()) return plain(verb + tail)
        val start = verb.length + separator.length
        return RowLabel(verb + separator + obj + tail, if (mono) persistentListOf(start until start + obj.length) else persistentListOf())
    }

    private fun RowLabel.suffixed(suffix: String): RowLabel = copy(text = text + suffix)
}

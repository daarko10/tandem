package dev.daarkolabs.tandem.chat

import java.util.Locale

// The chat's number and word formats, shared by the labels and the working indicator.

/** A token count: `312`, `1.2k`, `1.5m`. */
internal fun formatTokens(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 999_950 -> String.format(Locale.ROOT, "%.1fk", n / 1_000.0)
    else -> String.format(Locale.ROOT, "%.1fm", n / 1_000_000.0)
}

/** Whole seconds: `4s` under a minute, `43m 11s` under an hour, `1h 1m` from an hour. */
internal fun formatElapsed(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    }
}

/** A subagent's duration: one decimal under ten seconds (`5.8s`, `2.0s`), then as [formatElapsed] (`12s`). */
internal fun formatDuration(ms: Long): String =
    if (ms < 10_000) String.format(Locale.ROOT, "%.1fs", ms.coerceAtLeast(0) / 1_000.0) else formatElapsed(ms)

/** A tool's wire name for people: CamelCase split into words (`NotebookEdit` → `Notebook Edit`), underscores as spaces. */
internal fun displayName(toolName: String): String {
    val out = StringBuilder()
    toolName.forEachIndexed { i, c ->
        if (c == '_') {
            out.append(' ')
            return@forEachIndexed
        }
        val previous = toolName.getOrNull(i - 1)
        val next = toolName.getOrNull(i + 1)
        val boundary = c.isUpperCase() && previous != null && previous != '_' &&
            (previous.isLowerCase() || previous.isDigit() || (previous.isUpperCase() && next != null && next.isLowerCase()))
        if (boundary) out.append(' ')
        out.append(c)
    }
    return out.toString().trim()
}

/** Present participles for the first word of a shell command's description (spec 4.6.2); other words stay as they are. */
private val participles: Map<String, String> = mapOf(
    "add" to "adding", "archive" to "archiving", "build" to "building", "change" to "changing", "check" to "checking",
    "clean" to "cleaning", "clone" to "cloning", "commit" to "committing", "compare" to "comparing", "compile" to "compiling",
    "copy" to "copying", "count" to "counting", "create" to "creating", "delete" to "deleting", "display" to "displaying",
    "download" to "downloading", "echo" to "echoing", "execute" to "executing", "extract" to "extracting", "fetch" to "fetching",
    "find" to "finding", "format" to "formatting", "generate" to "generating", "get" to "getting", "inspect" to "inspecting",
    "install" to "installing", "kill" to "killing", "lint" to "linting", "list" to "listing", "look" to "looking", "make" to "making",
    "measure" to "measuring", "move" to "moving", "open" to "opening", "print" to "printing", "pull" to "pulling", "push" to "pushing",
    "read" to "reading", "remove" to "removing", "rename" to "renaming", "restart" to "restarting", "run" to "running",
    "search" to "searching", "set" to "setting", "show" to "showing", "sleep" to "sleeping", "start" to "starting", "stop" to "stopping",
    "test" to "testing", "update" to "updating", "verify" to "verifying", "view" to "viewing", "wait" to "waiting", "write" to "writing",
)

/** [description] with its first word in the present participle when the verb table knows it (`Create x` → `Creating x`). */
internal fun conjugate(description: String): String {
    val word = description.substringBefore(' ')
    val participle = participles[word.lowercase(Locale.ROOT)] ?: return description
    val cased = if (word.first().isUpperCase()) participle.replaceFirstChar { it.uppercaseChar() } else participle
    return cased + description.substring(word.length)
}

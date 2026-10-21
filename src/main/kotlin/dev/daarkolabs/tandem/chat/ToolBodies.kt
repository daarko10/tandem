package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.array
import dev.daarkolabs.tandem.protocol.int
import dev.daarkolabs.tandem.protocol.obj
import dev.daarkolabs.tandem.protocol.objects
import dev.daarkolabs.tandem.protocol.string
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The bodies of tool rows (spec 4.7) and their diff stats, built once from a call's input and result: plain strings and lists,
 * at most [MAX_LINES] lines and [MAX_CHARS] characters. When a result lacks the field a rule reads, the body falls back to the
 * result's text, so nothing is dropped.
 */
object ToolBodies {
    private const val MAX_LINES = 400
    private const val MAX_CHARS = 50_000
    private const val NO_OUTPUT = "(Bash completed with no output)"

    private val pretty = Json { prettyPrint = true }

    /**
     * The body of a call of [kind] with [input], its result's raw [content] (a string or blocks), its `tool_use_result` [result]
     * (or its text when it has none) and whether the result [isError].
     */
    fun body(kind: ToolKind, input: JsonObject?, content: JsonElement?, result: JsonElement?, isError: Boolean): ToolBody {
        val text = contentText(content) ?: (result as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (isError) return errorBody(kind, input, text, result)
        val fields = result as? JsonObject
        return when (kind) {
            ToolKind.READ -> image(content, fields) ?: fields?.obj("file")?.let(::numbered) ?: mono(ToolLabels.stripSystemReminders(text))
            ToolKind.EDIT -> diff(input, fields) ?: mono(text)
            ToolKind.CREATE -> if (isWriteUpdate(fields)) diff(input, fields) ?: mono(text) else input?.string("content")?.let(::mono) ?: mono(text)
            ToolKind.SHELL -> shell(input, text, fields)
            ToolKind.SEARCH -> fields?.string("content")?.let(::mono) ?: fields?.filenames()?.let(::monoLines) ?: mono(text)
            ToolKind.FIND -> fields?.filenames()?.let(::monoLines) ?: mono(text)
            ToolKind.WEB_FETCH -> fields?.string("result")?.let { page ->
                val status = listOfNotNull(fields.int("code")?.toString(), fields.string("codeText")).joinToString(" ")
                markdown(if (status.isEmpty()) page else "$page\n\n$status")
            } ?: mono(text)
            ToolKind.WEB_SEARCH -> webSearch(fields) ?: mono(text)
            ToolKind.AGENT -> agentText(fields)?.let(::markdown) ?: ToolBody.None
            ToolKind.QUESTION -> answers(input, fields) ?: mono(text)
            ToolKind.PLAN -> (fields?.string("plan") ?: input?.string("plan"))?.let(::markdown) ?: mono(text)
            ToolKind.PLAN_ENTER, ToolKind.TOOL_SEARCH -> mono(text)
            ToolKind.TASKS, ToolKind.MCP, ToolKind.OTHER -> json(input, fields, text)
        }
    }

    /**
     * The body so far of a live call (a `RUNNING` or `WAITING` one, polish spec 2.1) of [kind] with its complete [input]: a shell call's
     * command with no output yet, the input JSON of an MCP, task or unknown tool, nothing for every other kind (a read or a search
     * has nothing to show before its result, an Agent's nested rows are its body, a question or a plan has its card). Nothing while the
     * input still streams ([input] null): no body is built from partial JSON.
     */
    fun pending(kind: ToolKind, input: JsonObject?): ToolBody {
        if (input == null) return ToolBody.None
        return when (kind) {
            ToolKind.SHELL -> ToolBody.Command(cut(input.string("command").orEmpty()), persistentListOf(), 0, firstLineError = false)
            ToolKind.MCP, ToolKind.TASKS, ToolKind.OTHER -> json(input, null, "")
            ToolKind.READ, ToolKind.FIND, ToolKind.SEARCH, ToolKind.WEB_FETCH, ToolKind.WEB_SEARCH, ToolKind.AGENT, ToolKind.QUESTION,
            ToolKind.PLAN, ToolKind.PLAN_ENTER, ToolKind.TOOL_SEARCH, ToolKind.EDIT, ToolKind.CREATE,
            -> ToolBody.None
        }
    }

    /** `+A -B` of an edit (from `structuredPatch`) or of a write: an update's patch, else the lines it created. Null for other tools. */
    fun stats(kind: ToolKind, input: JsonObject?, result: JsonElement?): DiffStats? = when (kind) {
        ToolKind.EDIT -> patchStats(result as? JsonObject)
        ToolKind.CREATE -> if (isWriteUpdate(result)) {
            patchStats(result as JsonObject)
        } else {
            DiffStats(contentLines(input?.string("content").orEmpty()).size, 0)
        }
        else -> null
    }

    /** Whether a `Write` result updated an existing file: `type: "update"` with a non-empty `structuredPatch`. */
    internal fun isWriteUpdate(result: JsonElement?): Boolean {
        val fields = result as? JsonObject ?: return false
        return fields.string("type") == "update" && !fields.array("structuredPatch").isNullOrEmpty()
    }

    /** [text] as lines in the editor font, its first line marked when it is an [error]; nothing when it is empty. */
    internal fun plain(text: String, error: Boolean): ToolBody {
        val (lines, total) = clamp(text)
        return if (total == 0) ToolBody.None else ToolBody.Mono(lines, total, firstLineError = error)
    }

    /** [text] without the CLI's `<tool_use_error>` tags. */
    internal fun withoutErrorTags(text: String): String = text.replace("<tool_use_error>", "").replace("</tool_use_error>", "")

    /** A failed or denied call: the error text, its first line marked unless it is the user's denial; a shell call keeps its command. */
    private fun errorBody(kind: ToolKind, input: JsonObject?, text: String, result: JsonElement?): ToolBody {
        val message = withoutErrorTags(text).trim().ifEmpty {
            (result as? JsonPrimitive)?.takeIf { it.isString }?.content?.removePrefix("Error: ").orEmpty()
        }
        val denial = message in setOf("The user denied this command.", "cancelled", "The user denied this request.")
        if (kind == ToolKind.SHELL && !denial) {
            val (lines, total) = clamp(ToolLabels.stripAnsi(message))
            return ToolBody.Command(cut(input?.string("command").orEmpty()), lines, total, firstLineError = true)
        }
        val (lines, total) = clamp(message)
        return ToolBody.Mono(lines, total, firstLineError = !denial && lines.isNotEmpty())
    }

    /**
     * A shell call's command, then its output with escapes stripped; a command that printed nothing keeps its command with no output
     * (polish spec 2.1), so an opened running call keeps its body when the result lands.
     */
    private fun shell(input: JsonObject?, text: String, fields: JsonObject?): ToolBody {
        val command = cut(input?.string("command").orEmpty())
        val stdout = fields?.string("stdout").orEmpty()
        val output = stdout.ifEmpty { text.takeIf { it.trim() != NO_OUTPUT }.orEmpty() }
        val printed = output.ifBlank { fields?.string("stderr").orEmpty() }
        if (printed.isBlank()) return ToolBody.Command(command, persistentListOf(), 0, firstLineError = false)
        val (lines, total) = clamp(ToolLabels.stripAnsi(printed))
        return ToolBody.Command(command, lines, total, firstLineError = false)
    }

    /** A read file's lines, numbered from its `startLine`, without system reminders; null without `content`. */
    private fun numbered(file: JsonObject): ToolBody? {
        val content = file.string("content") ?: return null
        val start = file.int("startLine") ?: 1
        val lines = contentLines(ToolLabels.stripSystemReminders(content))
        val width = (start + lines.size - 1).toString().length
        return monoLines(lines.mapIndexed { i, line -> (start + i).toString().padStart(width) + "  " + line })
    }

    /** An image result: its size from `file.dimensions`, its type from the content block; the data itself is never kept. */
    private fun image(content: JsonElement?, fields: JsonObject?): ToolBody? {
        val block = (content as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull { it.string("type") == "image" }
        val file = fields?.obj("file")
        if (block == null && fields?.string("type") != "image") return null
        val dimensions = file?.obj("dimensions")
        return ToolBody.Image(
            width = dimensions?.let { it.int("originalWidth") ?: it.int("displayWidth") },
            height = dimensions?.let { it.int("originalHeight") ?: it.int("displayHeight") },
            mediaType = block?.obj("source")?.string("media_type") ?: file?.string("type") ?: "image",
        )
    }

    /** An edit's hunks from `structuredPatch`; null when the result has none (an older CLI). */
    private fun diff(input: JsonObject?, fields: JsonObject?): ToolBody? {
        val patch = fields?.objects("structuredPatch")?.takeIf { it.isNotEmpty() } ?: return null
        var budget = MAX_LINES
        var truncated = false
        val hunks = patch.mapNotNull { hunk ->
            if (budget <= 0) {
                truncated = true
                return@mapNotNull null
            }
            val all = hunk.lines()
            val kept = all.take(budget)
            if (kept.size < all.size) truncated = true
            budget -= kept.size
            DiffHunk(
                hunk.int("oldStart") ?: 0,
                hunk.int("newStart") ?: 0,
                kept.map { line ->
                    when {
                        line.startsWith("+") -> DiffLine(DiffKind.ADDED, line.substring(1))
                        line.startsWith("-") -> DiffLine(DiffKind.REMOVED, line.substring(1))
                        else -> DiffLine(DiffKind.CONTEXT, line.removePrefix(" "))
                    }
                }.toImmutableList(),
            )
        }
        val path = ToolLabels.filePath(input) ?: fields.string("filePath").orEmpty()
        return ToolBody.Diff(path, hunks.toImmutableList(), truncated)
    }

    private fun JsonObject.lines(): List<String> =
        array("lines").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

    private fun patchStats(fields: JsonObject?): DiffStats? {
        val patch = fields?.array("structuredPatch") ?: return null
        val lines = patch.filterIsInstance<JsonObject>().flatMap { it.lines() }
        return DiffStats(lines.count { it.startsWith("+") }, lines.count { it.startsWith("-") })
    }

    private fun JsonObject.filenames(): List<String>? =
        array("filenames")?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }?.takeIf { it.isNotEmpty() }

    /** A web search: the links of its `results[]` objects, then its text results as Markdown. */
    private fun webSearch(fields: JsonObject?): ToolBody? {
        val results = fields?.array("results") ?: return null
        val links = results.filterIsInstance<JsonObject>().flatMap { it.objects("content") }.mapNotNull { link ->
            val url = link.string("url") ?: return@mapNotNull null
            Link(link.string("title")?.takeIf { it.isNotBlank() } ?: url, url)
        }
        val summary = results.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.joinToString("\n\n")
        return ToolBody.Links(links.toImmutableList(), cut(summary))
    }

    /** An agent's final text: the text blocks of its result's `content`, joined by blank lines. */
    private fun agentText(fields: JsonObject?): String? =
        fields?.objects("content")?.filter { it.string("type") == "text" }?.mapNotNull { it.string("text") }
            ?.takeIf { it.isNotEmpty() }?.joinToString("\n\n")

    /**
     * A question's answers: `<header>: <answer>` per question, the header from the request's `input.questions` (the question text
     * when it is blank), multi-select answers joined with `, `; a free-text reply as `Reply`. Null when the result has neither.
     */
    private fun answers(input: JsonObject?, fields: JsonObject?): ToolBody? {
        val answers = fields?.obj("answers")
        val questions = input?.objects("questions")?.takeIf { it.isNotEmpty() } ?: fields?.objects("questions").orEmpty()
        val headers = questions.mapNotNull { q -> q.string("question")?.let { it to q.string("header") } }.toMap()
        val pairs = answers.orEmpty().mapNotNull { (question, answer) ->
            val picked = when (answer) {
                is JsonPrimitive -> answer.content
                is JsonArray -> answer.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", ")
                else -> return@mapNotNull null
            }
            headers[question]?.takeIf { it.isNotBlank() }.let { (it ?: question) to picked }
        }.toMutableList()
        fields?.string("response")?.let { pairs += "Reply" to it }
        return if (pairs.isEmpty()) null else ToolBody.Pairs(pairs.toImmutableList())
    }

    /** A tool's input and output as pretty JSON; the output is the result's text when it is not an object. */
    private fun json(input: JsonObject?, fields: JsonObject?, text: String): ToolBody = ToolBody.Json(
        input = cut(input?.let { pretty.encodeToString(JsonObject.serializer(), it) } ?: "{}"),
        output = fields?.let { cut(pretty.encodeToString(JsonObject.serializer(), it)) } ?: text.takeIf { it.isNotEmpty() }?.let(::cut),
    )

    /** A result's raw content as text: the string, or its text blocks joined by line breaks; null for other shapes. */
    private fun contentText(content: JsonElement?): String? = when (content) {
        is JsonPrimitive -> content.takeIf { it.isString }?.content
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.string("type") == "text" }?.string("text") }
            .takeIf { it.isNotEmpty() }?.joinToString("\n")
        else -> null
    }

    private fun markdown(text: String): ToolBody = ToolBody.Markdown(cut(text), null)

    private fun mono(text: String): ToolBody {
        val (lines, total) = clamp(text)
        return if (total == 0) ToolBody.None else ToolBody.Mono(lines, total)
    }

    private fun monoLines(lines: List<String>): ToolBody = mono(lines.joinToString("\n"))

    /**
     * [text] as at most [MAX_LINES] lines, with the line count of the whole text. Text over [MAX_CHARS] characters is cut there and
     * ends with `… (cut, N more characters)`.
     */
    private fun clamp(text: String): Pair<ImmutableList<String>, Int> {
        val all = contentLines(text)
        if (all.isEmpty()) return persistentListOf<String>() to 0
        val kept = all.take(MAX_LINES)
        val keptText = kept.joinToString("\n")
        if (keptText.length <= MAX_CHARS) return kept.toImmutableList() to all.size
        val lines = contentLines(keptText.substring(0, MAX_CHARS)).take(MAX_LINES - 1) + "… (cut, ${keptText.length - MAX_CHARS} more characters)"
        return lines.toImmutableList() to all.size
    }

    /** [text] cut at [MAX_CHARS] characters with the cut line. */
    private fun cut(text: String): String =
        if (text.length <= MAX_CHARS) text else text.substring(0, MAX_CHARS) + "\n… (cut, ${text.length - MAX_CHARS} more characters)"

    /** The lines of [text]; a final line break closes the last line and adds none (`"a\nb\n"` is two lines, `""` none). */
    internal fun contentLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.removeSuffix("\n").split("\n").map { it.removeSuffix("\r") }
    }
}

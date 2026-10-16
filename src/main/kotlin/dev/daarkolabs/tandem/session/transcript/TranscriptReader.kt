package dev.daarkolabs.tandem.session.transcript

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContentBlock
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.boolean
import dev.daarkolabs.tandem.protocol.string
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import dev.daarkolabs.tandem.session.settled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path
import java.util.BitSet
import kotlin.io.path.bufferedReader
import kotlin.io.path.name

private val LOG = logger<TranscriptReader>()

/** A first prompt that stands in for the title is cut to this many characters. */
private const val PROMPT_TITLE_LENGTH: Int = 80

/** How the CLI's interrupt marker starts. The reducer shows a user line that starts with it as "Stopped.". */
private const val INTERRUPT_MARKER: String = "[Request interrupted"

// The CLI wraps the user lines it writes itself in these tags and, like Tandem, tells them apart by how the text starts.
private const val COMMAND_NAME_TAG: String = "<command-name>"
private const val COMMAND_MESSAGE_TAG: String = "<command-message>"
private const val BASH_INPUT_TAG: String = "<bash-input>"

/** What a slash command or a shell escape printed: never something the user typed. */
private val OUTPUT_TAGS: List<String> = listOf("<local-command-stdout>", "<local-command-stderr>", "<bash-stdout>", "<bash-stderr>")

private val COMMAND_NAME: Regex = Regex("<command-name>([^<]*)</command-name>")
private val COMMAND_ARGS: Regex = Regex("<command-args>(.*?)</command-args>", RegexOption.DOT_MATCHES_ALL)
private val BASH_INPUT: Regex = Regex("<bash-input>(.*?)</bash-input>", RegexOption.DOT_MATCHES_ALL)

/** What a transcript says about its session, short of the history itself. */
data class TranscriptMeta(
    /** The transcript's file name without `.jsonl`. */
    val sessionId: String,
    /** The last `custom-title` line's title: set by the user, or by the host's `rename_session`. */
    val customTitle: String?,
    /** The last `ai-title` or `summary` line's text, whichever comes last. */
    val generatedTitle: String?,
    /** The first prompt of the current history, slash commands aside: its first line, trimmed and cut to 80 characters. */
    val firstPrompt: String?,
    /** `message.model` of the last assistant line in the current history; lines the CLI wrote itself (model `<synthetic>`) aside. */
    val lastModel: String?,
    /** `effort` of the last assistant line in the current history that has one: a line without `effort` keeps the one before. */
    val lastEffort: String?,
    /** `permissionMode` of the last user line in the current history that names a known mode; compact summaries do not count. */
    val lastPermissionMode: PermissionMode?,
    /** `cwd` of the first line that has one. */
    val cwd: String?,
    /** Every line read, skipped ones included. */
    val lineCount: Int,
) {
    /** [customTitle], else [generatedTitle], else [firstPrompt], else [sessionId]. */
    val title: String
        get() = customTitle ?: generatedTitle ?: firstPrompt ?: sessionId
}

/** A transcript loaded as chat history. Its [state] is settled (no turn is in progress) and has the session id from the file name. */
data class LoadedTranscript(val state: ChatState, val meta: TranscriptMeta)

/**
 * Reads the CLI's own transcript of a session, `<session id>.jsonl`, through [Ndjson.decode] and [reduce].
 *
 * A transcript is only ever appended to, so it keeps the turns a rewind abandoned. The current history is the `parentUuid`
 * chain that ends at the last user or assistant line; when that chain starts at a compaction, the lines before the
 * compaction add their own chain, found the same way. So the file is read twice: the first pass finds the chains, the
 * second reads the lines on them as the live stream would have shown them.
 *
 * Prompts (`user` text), tool results (`user` `tool_result` blocks) and `assistant` lines make the history: an interrupt
 * marker becomes the reducer's "Stopped." notice, a slash command `/name args`, a shell escape `!command`. `custom-title`,
 * `ai-title` and `summary` lines make the title, wherever they are. `isMeta`, `isSidechain` and `isCompactSummary` lines, the
 * output of commands and shell escapes, every other type, and lines that are not a JSON object (such as a last line the CLI
 * is still writing) are skipped. Bad input never throws.
 */
object TranscriptReader {
    /**
     * The history of the transcript at [path], with its [TranscriptMeta]. Reads on [Dispatchers.IO] and stops between lines
     * once cancelled. A file that cannot be opened throws its [IOException]. An [IOException] after that ends the read: in the
     * first pass with an empty history, in the second with what was parsed before it.
     */
    suspend fun read(path: Path): LoadedTranscript = withContext(Dispatchers.IO) {
        // kotlin.io.path's bufferedReader decodes with an InputStreamReader, which replaces a malformed byte instead of
        // throwing, so a bad byte costs at most its own line. (Files.newBufferedReader would end the read there.)
        readFrom({ path.bufferedReader() }, sessionIdOf(path))
    }

    /**
     * Only the [TranscriptMeta] of the transcript at [path], read the same way as [read]. Blocking: call it off the EDT. A file
     * that cannot be opened gives a meta with nothing but the id, which is then also the title.
     */
    fun meta(path: Path): TranscriptMeta {
        val sessionId = sessionIdOf(path)
        return try {
            readTwice({ path.bufferedReader() }, sessionId, withHistory = false) {}.second
        } catch (e: IOException) {
            LOG.debug { "transcript $sessionId not readable (${e.javaClass.simpleName})" }
            emptyMeta(sessionId)
        }
    }

    /** [read] from what [open] opens, once per pass. Internal so tests can pass readers that fail or never end. */
    internal suspend fun readFrom(open: () -> BufferedReader, sessionId: String): LoadedTranscript {
        val context = currentCoroutineContext()
        val (history, meta) = readTwice(open, sessionId, withHistory = true) { context.ensureActive() }
        val state = history.settled()
        return LoadedTranscript(
            state.copy(
                sessionId = meta.sessionId,
                activeModel = meta.lastModel ?: state.activeModel,
                permissionMode = meta.lastPermissionMode ?: state.permissionMode,
                effort = meta.lastEffort ?: state.effort,
            ),
            meta,
        )
    }

    private fun sessionIdOf(path: Path): String = path.name.removeSuffix(".jsonl")
}

private fun emptyMeta(sessionId: String, lineCount: Int = 0): TranscriptMeta =
    TranscriptMeta(sessionId, null, null, null, null, null, null, null, lineCount)

/**
 * Both passes over what [open] opens, [beforeLine] running before every line of each; the second reads only the lines the first
 * placed, so lines appended in between wait for the next read. An [IOException] opening the first pass is thrown; one later in
 * it gives an empty history and the number of lines seen. One in the second pass, opening included, keeps what was scanned.
 */
private fun readTwice(
    open: () -> BufferedReader,
    sessionId: String,
    withHistory: Boolean,
    beforeLine: () -> Unit,
): Pair<ChatState, TranscriptMeta> {
    val chains = Chains(sessionId)
    if (!open().use { chains.consume(it, beforeLine) }) return ChatState() to emptyMeta(sessionId, chains.lineCount)
    val scan = Scan(sessionId, withHistory, chains.abandoned(), limit = chains.lineCount)
    try {
        open().use { scan.consume(it, beforeLine) }
    } catch (e: IOException) {
        LOG.debug { "transcript $sessionId not reopened (${e.javaClass.simpleName})" }
    }
    return scan.state to scan.meta()
}

/**
 * The first pass: for each line, only what places it in the conversation. A line is conversational when it is a `user` or
 * `assistant` line that is neither sidechain nor meta; [abandoned] names the conversational lines off the current history.
 */
private class Chains(private val sessionId: String) {
    private val uuids = ArrayList<String?>()
    private val parents = ArrayList<String?>()
    private val conversational = BitSet()
    private val compactionRoots = BitSet()

    val lineCount: Int
        get() = uuids.size

    /** Takes every line of [reader], running [beforeLine] before each. False when an [IOException] cut it short. */
    fun consume(reader: BufferedReader, beforeLine: () -> Unit): Boolean = try {
        while (true) {
            beforeLine()
            accept(reader.readLine() ?: break)
        }
        true
    } catch (e: IOException) {
        LOG.debug { "transcript $sessionId: first pass stopped after $lineCount lines (${e.javaClass.simpleName})" }
        false
    }

    private fun accept(line: String) {
        val index = lineCount
        val raw = parseObject(line)
        uuids += raw?.string("uuid")
        parents += raw?.string("parentUuid")
        if (raw == null) return
        val type = raw.string("type")
        if ((type == "user" || type == "assistant") && !raw.isSet("isSidechain") && !raw.isSet("isMeta")) conversational.set(index)
        val compaction = type == "user" && raw.isSet("isCompactSummary") || type == "system" && raw.string("subtype") == "compact_boundary"
        if (compaction) compactionRoots.set(index)
    }

    /**
     * Walks `parentUuid` from the last conversational line to the root of its chain. When that root is a compaction (a compact
     * summary or a `compact_boundary` line), the last conversational line before it starts the next walk. Conversational
     * lines that no walk reaches are abandoned, except those without a uuid.
     */
    fun abandoned(): BitSet {
        val byUuid = HashMap<String, Int>()
        uuids.forEachIndexed { index, uuid -> if (uuid != null) byUuid[uuid] = index }
        val onChain = BitSet()
        var below = lineCount
        while (true) {
            val leaf = conversational.previousSetBit(below - 1)
            if (leaf < 0) break
            var line = leaf
            while (true) {
                onChain.set(line)
                val parent = parents[line]?.let(byUuid::get)
                if (parent == null || onChain[parent]) break // the root, or a loop in a broken file
                line = parent
            }
            if (!compactionRoots[line]) break
            below = minOf(line, leaf) // lower on every walk, so even a broken file ends the loop
        }
        val abandoned = BitSet()
        var line = conversational.nextSetBit(0)
        while (line >= 0) {
            if (uuids[line] != null && !onChain[line]) abandoned.set(line)
            line = conversational.nextSetBit(line + 1)
        }
        return abandoned
    }
}

/**
 * The second pass, over the first [limit] lines: collects the [TranscriptMeta] and, [withHistory], reduces the history into
 * [state]. [abandoned] lines are counted and otherwise left out.
 */
private class Scan(val sessionId: String, private val withHistory: Boolean, private val abandoned: BitSet, private val limit: Int) {
    var state: ChatState = ChatState()
        private set

    private var customTitle: String? = null
    private var generatedTitle: String? = null
    private var firstPrompt: String? = null
    private var lastModel: String? = null
    private var lastEffort: String? = null
    private var lastPermissionMode: PermissionMode? = null
    private var cwd: String? = null
    private var lineCount: Int = 0

    /** Takes the lines of [reader], running [beforeLine] before each. An [IOException] ends it with the lines taken so far. */
    fun consume(reader: BufferedReader, beforeLine: () -> Unit) {
        try {
            while (lineCount < limit) {
                beforeLine()
                val line = reader.readLine() ?: break
                accept(line)
            }
        } catch (e: IOException) {
            LOG.debug { "transcript $sessionId: second pass stopped after $lineCount lines (${e.javaClass.simpleName})" }
        }
    }

    fun meta(): TranscriptMeta =
        TranscriptMeta(sessionId, customTitle, generatedTitle, firstPrompt, lastModel, lastEffort, lastPermissionMode, cwd, lineCount)

    private fun accept(line: String) {
        val index = lineCount++
        if (abandoned[index]) return
        val message = Ndjson.decode(line)
        val raw = message.raw ?: return // not a JSON object, e.g. a line the CLI is still writing
        if (cwd == null) cwd = raw.string("cwd")?.takeIf { it.isNotEmpty() }
        when (raw.string("type")) {
            // isMeta and isCompactSummary lines are the CLI's own context, isSidechain lines a subagent's: none is the user's.
            "user" -> if (!raw.isSet("isMeta") && !raw.isSet("isSidechain") && !raw.isSet("isCompactSummary")) userLine(message, raw)
            "assistant" -> if (!raw.isSet("isSidechain")) assistantLine(message, raw)
            "custom-title" -> raw.titleText("customTitle")?.let { customTitle = it }
            "ai-title" -> raw.titleText("aiTitle")?.let { generatedTitle = it }
            "summary" -> raw.titleText("summary")?.let { generatedTitle = it }
            // Every other type (attachment, queue-operation, cost-state, system, ...) carries no history.
        }
    }

    private fun userLine(message: CliMessage, raw: JsonObject) {
        PermissionMode.fromWire(raw.string("permissionMode"))?.let { lastPermissionMode = it }
        val user = message as? CliMessage.User ?: return
        val text = user.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        // Tool results and an interrupt marker go through the reducer, as they do live; once per line, since it handles both.
        // The API puts a user message's tool_result blocks first and any text after them, so a prompt comes after its results.
        val interrupted = text.startsWith(INTERRUPT_MARKER)
        if (interrupted || user.content.any { it is ContentBlock.ToolResult }) emit(SessionEvent.Cli(user))
        if (interrupted) return
        val typed = typedText(text) ?: return
        if (firstPrompt == null && typed.isPrompt) firstPrompt = promptTitle(typed.text)
        emit(SessionEvent.UserSent(user.uuid ?: "line-$lineCount", typed.text))
    }

    private fun assistantLine(message: CliMessage, raw: JsonObject) {
        // A line the CLI wrote itself, such as an API error, names a model like "<synthetic>": not the session's model.
        (message as? CliMessage.Assistant)?.model?.takeUnless { it.startsWith("<") }?.let { lastModel = it }
        raw.string("effort")?.let { lastEffort = it }
        emit(SessionEvent.Cli(message))
    }

    private fun emit(event: SessionEvent) {
        if (withHistory) state = reduce(state, event)
    }
}

/** [line] as a JSON object, or null when it is not one (such as a line the CLI is still writing). */
private fun parseObject(line: String): JsonObject? = try {
    Json.parseToJsonElement(line) as? JsonObject
} catch (_: IllegalArgumentException) { // SerializationException: not JSON
    null
}

private fun JsonObject.isSet(flag: String): Boolean = boolean(flag) == true

/** The text of a title line; a blank one does not count. */
private fun JsonObject.titleText(key: String): String? = string(key)?.takeIf { it.isNotBlank() }

/** A user line's text as the history shows it. Only a [isPrompt] text can stand in for the title. */
private class Typed(val text: String, val isPrompt: Boolean)

/**
 * What the user typed, from a user line's [text], told apart the way the CLI tells its own lines apart: by how the text starts.
 * A slash command (`<command-name>` or `<command-message>` first) shows as `/name args` but is not a prompt for the title; a
 * shell escape (`<bash-input>`) shows as `!command`. Their output and blank text give null. Any other text is a prompt as it
 * stands, even one that quotes such a tag further on.
 */
private fun typedText(text: String): Typed? {
    val start = text.trim()
    return when {
        start.isEmpty() || OUTPUT_TAGS.any { start.startsWith(it) } -> null
        start.startsWith(COMMAND_NAME_TAG) || start.startsWith(COMMAND_MESSAGE_TAG) ->
            commandOf(start)?.let { Typed(it, isPrompt = false) }
        start.startsWith(BASH_INPUT_TAG) ->
            BASH_INPUT.find(start)?.groupValues?.get(1)?.trim()?.ifEmpty { null }?.let { Typed("!$it", isPrompt = true) }
        else -> Typed(text, isPrompt = true)
    }
}

/** `/name args` from a slash command's tags, or just the name without arguments; null without a name. */
private fun commandOf(text: String): String? {
    val name = COMMAND_NAME.find(text)?.groupValues?.get(1)?.trim().orEmpty()
    if (name.isEmpty()) return null
    val args = COMMAND_ARGS.find(text)?.groupValues?.get(1)?.trim().orEmpty()
    return if (args.isEmpty()) name else "$name $args"
}

/** The first line of [text], trimmed and cut to [PROMPT_TITLE_LENGTH] characters, never between the halves of a surrogate pair. */
private fun promptTitle(text: String): String {
    val line = text.trim().lineSequence().first().trim()
    if (line.length <= PROMPT_TITLE_LENGTH) return line
    val end = if (line[PROMPT_TITLE_LENGTH - 1].isHighSurrogate()) PROMPT_TITLE_LENGTH - 1 else PROMPT_TITLE_LENGTH
    return line.substring(0, end).trimEnd()
}

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
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.name

private val LOG = logger<TranscriptReader>()

/** A first prompt that stands in for the title is cut to this many characters. */
private const val PROMPT_TITLE_LENGTH: Int = 80

/** How the CLI's interrupt marker starts. The reducer shows a user line that starts with it as "Stopped.". */
private const val INTERRUPT_MARKER: String = "[Request interrupted"

/** A slash command as the CLI records it: `<command-name>/model</command-name>`, then `<command-args>opus</command-args>`. */
private val COMMAND_NAME: Regex = Regex("<command-name>(.*?)</command-name>", RegexOption.DOT_MATCHES_ALL)
private val COMMAND_ARGS: Regex = Regex("<command-args>(.*?)</command-args>", RegexOption.DOT_MATCHES_ALL)

/** What a transcript says about its session, short of the history itself. */
data class TranscriptMeta(
    /** The transcript's file name without `.jsonl`. */
    val sessionId: String,
    /** The last `custom-title` line's title: set by the user, or by the host's `rename_session`. */
    val customTitle: String?,
    /** The last `ai-title` or `summary` line's text, whichever comes last. */
    val generatedTitle: String?,
    /** The first thing the user typed (a slash command as `/name args`): its first line, trimmed and cut to 80 characters. */
    val firstPrompt: String?,
    /** `message.model` of the last assistant line, except the CLI's own lines whose model starts with `<` (`<synthetic>`). */
    val lastModel: String?,
    /** `effort` of the last assistant line (the recorded `system/init` has no effort). */
    val lastEffort: String?,
    /** `permissionMode` of the last user line (neither meta nor sidechain) that names a known mode. */
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
 * Prompts (`user` text), tool results (`user` `tool_result` blocks) and `assistant` lines make the history, shown the way the
 * live stream shows them: an interrupt marker becomes the reducer's "Stopped." notice, a slash command its name and arguments.
 * `custom-title`, `ai-title` and `summary` lines make the title. `isMeta`, `isSidechain` and `isCompactSummary` lines, a
 * command's local output, every other type, and lines that are not a JSON object (such as a last line the CLI is still
 * writing) are skipped. Bad input never throws.
 */
object TranscriptReader {
    /**
     * The history of the transcript at [path], with its [TranscriptMeta]. Reads on [Dispatchers.IO] and stops between lines
     * once cancelled. A file that cannot be opened throws its [IOException]; an [IOException] after that ends the read with
     * what was parsed before it.
     */
    suspend fun read(path: Path): LoadedTranscript = withContext(Dispatchers.IO) {
        // kotlin.io.path's bufferedReader decodes with an InputStreamReader, which replaces a malformed byte instead of
        // throwing, so a bad byte costs at most its own line. (Files.newBufferedReader would end the read there.)
        path.bufferedReader().use { readFrom(it, sessionIdOf(path)) }
    }

    /**
     * Only the [TranscriptMeta] of the transcript at [path], with the same skipping rules as [read]. Blocking: call it off the
     * EDT. A file that cannot be opened gives a meta with nothing but the id, which is then also the title.
     */
    fun meta(path: Path): TranscriptMeta {
        val scan = Scan(sessionIdOf(path), withHistory = false)
        try {
            path.bufferedReader().use { reader -> scan.consume(reader) {} }
        } catch (e: IOException) {
            LOG.debug { "transcript ${scan.sessionId} not readable (${e.javaClass.simpleName})" }
        }
        return scan.meta()
    }

    /** [read] from an open [reader], which the caller closes. Internal so tests can pass a reader that fails or never ends. */
    internal suspend fun readFrom(reader: BufferedReader, sessionId: String): LoadedTranscript {
        val context = currentCoroutineContext()
        val scan = Scan(sessionId, withHistory = true)
        scan.consume(reader) { context.ensureActive() }
        val meta = scan.meta()
        val state = scan.state.settled()
        return LoadedTranscript(
            state.copy(
                sessionId = meta.sessionId,
                permissionMode = meta.lastPermissionMode ?: state.permissionMode,
                effort = meta.lastEffort ?: state.effort,
            ),
            meta,
        )
    }

    private fun sessionIdOf(path: Path): String = path.name.removeSuffix(".jsonl")
}

/** One pass over a transcript: collects its [TranscriptMeta] and, [withHistory], reduces its history into [state]. */
private class Scan(val sessionId: String, private val withHistory: Boolean) {
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

    /** Takes every line of [reader], running [beforeLine] before each. An [IOException] ends it with the lines taken so far. */
    fun consume(reader: BufferedReader, beforeLine: () -> Unit) {
        try {
            while (true) {
                beforeLine()
                val line = reader.readLine() ?: break
                accept(line)
            }
        } catch (e: IOException) {
            LOG.debug { "transcript $sessionId: reading stopped after $lineCount lines (${e.javaClass.simpleName})" }
        }
    }

    fun meta(): TranscriptMeta =
        TranscriptMeta(sessionId, customTitle, generatedTitle, firstPrompt, lastModel, lastEffort, lastPermissionMode, cwd, lineCount)

    private fun accept(line: String) {
        lineCount++
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
        val prompt = promptOf(text) ?: return
        if (firstPrompt == null) firstPrompt = promptTitle(prompt)
        emit(SessionEvent.UserSent(user.uuid ?: "line-$lineCount", prompt))
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

private fun JsonObject.isSet(flag: String): Boolean = boolean(flag) == true

/** The text of a title line; a blank one does not count. */
private fun JsonObject.titleText(key: String): String? = string(key)?.takeIf { it.isNotBlank() }

/**
 * What the user typed, from a user line's [text]: a slash command as its name and arguments (`/model opus`), null for blank
 * text or for a command's local output (`<local-command-stdout>...</local-command-stdout>`), else the text itself.
 */
private fun promptOf(text: String): String? {
    COMMAND_NAME.find(text)?.let { name ->
        val args = COMMAND_ARGS.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return listOf(name.groupValues[1].trim(), args).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }
    val trimmed = text.trim()
    val localOutput = trimmed.startsWith("<local-command-stdout>") && trimmed.endsWith("</local-command-stdout>")
    return if (trimmed.isEmpty() || localOutput) null else text
}

/** The first line of [text], trimmed and cut to [PROMPT_TITLE_LENGTH] characters, never between the halves of a surrogate pair. */
private fun promptTitle(text: String): String {
    val line = text.trim().lineSequence().first().trim()
    if (line.length <= PROMPT_TITLE_LENGTH) return line
    val end = if (line[PROMPT_TITLE_LENGTH - 1].isHighSurrogate()) PROMPT_TITLE_LENGTH - 1 else PROMPT_TITLE_LENGTH
    return line.substring(0, end).trimEnd()
}

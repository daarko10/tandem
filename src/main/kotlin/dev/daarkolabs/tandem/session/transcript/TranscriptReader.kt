package dev.daarkolabs.tandem.session.transcript

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContentBlock
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.obj
import dev.daarkolabs.tandem.protocol.string
import dev.daarkolabs.tandem.protocol.timestampMillis
import dev.daarkolabs.tandem.protocol.unwrapLocalCommandOutput
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.UserImage
import dev.daarkolabs.tandem.session.isInterrupt
import dev.daarkolabs.tandem.session.reduce
import dev.daarkolabs.tandem.session.settled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.BitSet
import kotlin.io.path.bufferedReader
import kotlin.io.path.name

private val LOG = logger<TranscriptReader>()

/** A first prompt that stands in for the title is cut to this many characters. */
private const val PROMPT_TITLE_LENGTH: Int = 80

/** The title of a first prompt of images only. */
private const val IMAGE_PROMPT_TITLE: String = "[Image #1]"

// The CLI wraps the user lines it writes itself in these tags and, like Tandem, tells them apart by how the text starts.
private const val COMMAND_NAME_TAG: String = "<command-name>"
private const val COMMAND_MESSAGE_TAG: String = "<command-message>"
private const val BASH_INPUT_TAG: String = "<bash-input>"

/** Text the CLI or its harness writes in the user's place: command and shell output, background-task notifications. */
private val GENERATED_TAGS: List<String> =
    listOf("<local-command-stdout>", "<local-command-stderr>", "<bash-stdout>", "<bash-stderr>", "<task-notification>")

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
    /**
     * The first prompt of the current history, slash commands aside: its first line, trimmed and cut to 80 characters, or
     * `[Image #1]` for a prompt of images only.
     */
    val firstPrompt: String?,
    /** `message.model` of the last assistant line in the current history; lines the CLI wrote itself (model `<synthetic>`) aside. */
    val lastModel: String?,
    /** `effort` of the last assistant line in the current history that has one: a line without `effort` keeps the one before. */
    val lastEffort: String?,
    /** `permissionMode` of the last user line in the current history that names a known mode; compact summaries do not count. */
    val lastPermissionMode: PermissionMode?,
    /** `cwd` of the first line that has one, leaving out the lines the history leaves out (turns a rewind abandoned). */
    val cwd: String?,
    /** Every line read, skipped ones included. */
    val lineCount: Int,
) {
    /** [customTitle], else [generatedTitle], else [firstPrompt], else [sessionId]. */
    val title: String
        get() = customTitle ?: generatedTitle ?: firstPrompt ?: sessionId
}

/** A transcript loaded as chat history. Its [state] is settled (no turn is in progress) and has the session id from the file name. */
data class LoadedTranscript(
    val state: ChatState,
    val meta: TranscriptMeta,
    /**
     * The class of the [IOException] that ended the read before the end of the file, null when it was read to the end. The
     * history is then empty, if the read ended in its first pass, or what came before it.
     */
    val readError: String? = null,
)

/**
 * Reads the CLI's own transcript of a session, `<session id>.jsonl`, through [Ndjson.decode] and [reduce].
 *
 * A transcript is only ever appended to, so it keeps the turns a rewind abandoned. The file is read twice: the first pass
 * ([TranscriptChain]) finds the current history, the `parentUuid` chain through the transcript with the lines parallel tool
 * calls branch off it; the second reads the lines on it as the live stream would have shown them. [meta] reads it once, keeping
 * what each line says about the meta until the first pass has placed it.
 *
 * Prompts (`user` text and images), tool results (`user` `tool_result` blocks) and `assistant` lines make the history: an interrupt
 * marker becomes the reducer's "Stopped." notice, a slash command `/name args`, a shell escape `!command`, and a line of images
 * only (or a generated tag with images) a prompt with no text. A `system` `local_command` line is a local command's output, the
 * notice the stream shows for it, or the command itself as typed (see [Scan]). Each goes to the reducer stamped with its line's
 * `timestamp` (0 when it has none), and [TranscriptMeta.cwd] becomes the state's `cwd`. `custom-title`, `ai-title` and `summary`
 * lines make the title, wherever they are. `isMeta`, `isSidechain` and `isCompactSummary` lines, the output of commands and shell
 * escapes in user lines, background-task notifications and other generated tags unless they carry images, every other type, and
 * lines that are not a JSON object (such as a last line the CLI is still writing) are skipped. Bad input never throws.
 */
object TranscriptReader {
    /**
     * The history of the transcript at [path], with its [TranscriptMeta]. Reads on [Dispatchers.IO] and stops between lines
     * once cancelled. A file that cannot be opened throws its [IOException]. An [IOException] after that ends the read: in the
     * first pass with an empty history, in the second with what was parsed before it; [LoadedTranscript.readError] says so.
     */
    suspend fun read(path: Path): LoadedTranscript = withContext(Dispatchers.IO) {
        // kotlin.io.path's bufferedReader decodes with an InputStreamReader, which replaces a malformed byte instead of
        // throwing, so a bad byte costs at most its own line. (Files.newBufferedReader would end the read there.)
        readFrom({ path.bufferedReader() }, sessionIdOf(path))
    }

    /**
     * Only the [TranscriptMeta] of the transcript at [path], read the same way as [read]. Blocking: call it off the EDT. A file
     * that cannot be opened gives a meta with nothing but the id, which is then also the title; one that is gone (deleted since it
     * was listed, say) throws its [NoSuchFileException], since it is no session any more.
     */
    fun meta(path: Path): TranscriptMeta {
        val sessionId = sessionIdOf(path)
        return try {
            metaFrom({ path.bufferedReader() }, sessionId)
        } catch (e: NoSuchFileException) {
            throw e
        } catch (e: IOException) {
            LOG.debug { "transcript $sessionId not readable (${e.javaClass.simpleName})" }
            emptyMeta(sessionId)
        }
    }

    /**
     * [meta], read when the transcript at [path] was [from] bytes long, with the title lines (`custom-title`, `ai-title`,
     * `summary`) appended since: what [meta] gives for the whole file now, as far as the title goes, for a transcript the CLI has only
     * appended to, without reading it all again. Everything else stays as [meta] has it. Null when only a full read can tell: the
     * first [from] bytes do not end a line (a line was still being written), or the file is no longer that long. Blocking: call it
     * off the EDT. Throws the [IOException] of a file that cannot be read.
     */
    fun withAppendedTitles(path: Path, from: Long, meta: TranscriptMeta): TranscriptMeta? {
        if (from <= 0) return null
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val last = ByteBuffer.allocate(1)
            if (channel.read(last, from - 1) != 1 || last.get(0) != '\n'.code.toByte()) return null
            var titled = meta
            // Like bufferedReader, this decodes with an InputStreamReader, which replaces a malformed byte instead of throwing.
            Channels.newInputStream(channel.position(from)).bufferedReader().lineSequence().forEach { titled = titled.withTitleOf(it) }
            return titled
        }
    }

    /**
     * [meta] from what [open] opens, in one pass: each line is parsed once, and what it says about the meta is kept until the
     * chain ([TranscriptChain]) says whether it is on the current history. Internal so tests can count how often it opens.
     */
    internal fun metaFrom(open: () -> BufferedReader, sessionId: String): TranscriptMeta {
        val chain = TranscriptChain()
        val facts = ArrayList<Pair<Int, Facts>>()
        var cwdFound = false
        open().use { reader ->
            reader.feed(Int.MAX_VALUE, {}) { line ->
                val index = chain.lineCount
                val message = Ndjson.decode(line)
                chain.add(message.raw)
                val raw = message.raw ?: return@feed
                var lineFacts = factsOf(message, raw)
                // Only user and assistant lines can be off the history: once another line gave the cwd, no later line counts for it.
                if (cwdFound) lineFacts = lineFacts.copy(cwd = null)
                if (lineFacts.cwd != null && raw.string("type") != "user" && raw.string("type") != "assistant") cwdFound = true
                if (lineFacts != NO_FACTS) facts += index to lineFacts
            }
        }?.let { e ->
            LOG.debug { "transcript $sessionId: meta read stopped after ${chain.lineCount} lines (${e.javaClass.simpleName})" }
            return emptyMeta(sessionId, chain.lineCount)
        }
        val abandoned = chain.abandoned()
        val meta = MetaBuilder(sessionId)
        for ((index, lineFacts) in facts) if (!abandoned[index]) meta.take(lineFacts)
        return meta.build(chain.lineCount)
    }

    /** [read] from what [open] opens, once per pass. Internal so tests can pass readers that fail or never end. */
    internal suspend fun readFrom(open: () -> BufferedReader, sessionId: String): LoadedTranscript {
        val context = currentCoroutineContext()
        val passes = readTwice(open, sessionId) { context.ensureActive() }
        val meta = passes.meta
        val state = passes.history.settled()
        return LoadedTranscript(
            state.copy(
                sessionId = meta.sessionId,
                // The live source is system/init, which a transcript does not keep: its lines' own cwd stands in.
                cwd = meta.cwd ?: state.cwd,
                activeModel = meta.lastModel ?: state.activeModel,
                permissionMode = meta.lastPermissionMode ?: state.permissionMode,
                effort = meta.lastEffort ?: state.effort,
            ),
            meta,
            passes.failure?.javaClass?.simpleName,
        )
    }

    private fun sessionIdOf(path: Path): String = path.name.removeSuffix(".jsonl")
}

private fun emptyMeta(sessionId: String, lineCount: Int = 0): TranscriptMeta =
    TranscriptMeta(sessionId, null, null, null, null, null, null, null, lineCount)

/** How each kind of title line names its type; a line that has none of these cannot be a title line, and is not parsed. */
private val TITLE_TYPES: List<String> = listOf("\"custom-title\"", "\"ai-title\"", "\"summary\"")

/** This meta with the title of [line], if it is a title line, taken as [Scan] takes it. */
private fun TranscriptMeta.withTitleOf(line: String): TranscriptMeta {
    if (TITLE_TYPES.none { it in line }) return this
    val title = Ndjson.decode(line).raw?.let(::titleOf) ?: return this
    return if (title.custom) copy(customTitle = title.text) else copy(generatedTitle = title.text)
}

/** A title line's title: the [custom] one (`custom-title`), or a generated one (`ai-title`, `summary`). */
private class Title(val text: String, val custom: Boolean)

/** The title [raw] gives if it is a title line; null for any other line, and for a blank title. */
private fun titleOf(raw: JsonObject): Title? =
    when (raw.string("type")) {
        "custom-title" -> raw.titleText("customTitle")?.let { Title(it, custom = true) }
        "ai-title" -> raw.titleText("aiTitle")?.let { Title(it, custom = false) }
        "summary" -> raw.titleText("summary")?.let { Title(it, custom = false) }
        else -> null
    }

/** What [readTwice] read: the [history], the [meta], and the [IOException] that ended the reading early, if one did. */
private class Passes(val history: ChatState, val meta: TranscriptMeta, val failure: IOException?)

/**
 * Both passes over what [open] opens, [beforeLine] running before every line of each; the second reads only the lines the first
 * placed, so lines appended in between wait for the next read. An [IOException] opening the first pass is thrown; one later in
 * it gives an empty history and the number of lines seen. One in the second pass, opening included, keeps what was scanned.
 */
private fun readTwice(open: () -> BufferedReader, sessionId: String, beforeLine: () -> Unit): Passes {
    val chain = TranscriptChain()
    open().use { it.feed(Int.MAX_VALUE, beforeLine, chain::add) }?.let { e ->
        LOG.debug { "transcript $sessionId: first pass stopped after ${chain.lineCount} lines (${e.javaClass.simpleName})" }
        return Passes(ChatState(), emptyMeta(sessionId, chain.lineCount), e)
    }
    val scan = Scan(sessionId, chain.abandoned())
    val failure = try {
        open().use { it.feed(chain.lineCount, beforeLine, scan::accept) }?.also { e ->
            LOG.debug { "transcript $sessionId: second pass stopped after ${scan.lineCount} lines (${e.javaClass.simpleName})" }
        }
    } catch (e: IOException) {
        LOG.debug { "transcript $sessionId not reopened (${e.javaClass.simpleName})" }
        e
    }
    return Passes(scan.state, scan.meta(), failure)
}

/**
 * Hands [onLine] the lines of this reader, at most [limit] of them, running [beforeLine] before each. Returns the
 * [IOException] that ended the reading early, or null.
 */
private fun BufferedReader.feed(limit: Int, beforeLine: () -> Unit, onLine: (String) -> Unit): IOException? {
    try {
        repeat(limit) {
            beforeLine()
            onLine(readLine() ?: return null)
        }
    } catch (e: IOException) {
        return e
    }
    return null
}

/**
 * What one line adds to the [TranscriptMeta], if it is not left out: null adds nothing. A `user` line counts unless it is meta,
 * sidechain or a compact summary, an `assistant` line unless it is sidechain, and title lines wherever they are.
 */
private data class Facts(
    val cwd: String? = null,
    val title: Title? = null,
    val permissionMode: PermissionMode? = null,
    /** A typed prompt's title. */
    val prompt: String? = null,
    /** The model of an assistant line the model wrote, not the CLI (`<synthetic>`). */
    val model: String? = null,
    val effort: String? = null,
)

private val NO_FACTS: Facts = Facts()

private fun factsOf(message: CliMessage, raw: JsonObject): Facts {
    val cwd = raw.string("cwd")?.takeIf { it.isNotEmpty() }
    return when (raw.string("type")) {
        // isMeta and isCompactSummary lines are the CLI's own context, isSidechain lines a subagent's: none is the user's.
        "user" -> if (raw.isSet("isMeta") || raw.isSet("isSidechain") || raw.isSet("isCompactSummary")) {
            Facts(cwd)
        } else {
            // The ids of the images do not matter here, only whether there are any.
            val shown = (message as? CliMessage.User)?.let { userLineOf(it, it.uuid.orEmpty()) }
            val prompt = shown?.typed?.takeIf { it.isPrompt }?.let { promptTitle(it.text) }
                ?: IMAGE_PROMPT_TITLE.takeIf { shown?.images?.isNotEmpty() == true }
            Facts(cwd, permissionMode = PermissionMode.fromWire(raw.string("permissionMode")), prompt = prompt)
        }
        "assistant" -> if (raw.isSet("isSidechain")) {
            Facts(cwd)
        } else {
            // A line the CLI wrote itself, such as an API error, names a model like "<synthetic>": not the session's model.
            Facts(cwd, model = (message as? CliMessage.Assistant)?.model?.takeUnless { it.startsWith("<") }, effort = raw.string("effort"))
        }
        // Title lines (custom-title, ai-title, summary) give the title. Every other type (attachment, queue-operation,
        // cost-state, system, ...) carries no history.
        else -> Facts(cwd, title = titleOf(raw))
    }
}

/** The [TranscriptMeta] of the lines [take]n, in file order. */
private class MetaBuilder(private val sessionId: String) {
    private var customTitle: String? = null
    private var generatedTitle: String? = null
    private var firstPrompt: String? = null
    private var lastModel: String? = null
    private var lastEffort: String? = null
    private var lastPermissionMode: PermissionMode? = null
    private var cwd: String? = null

    fun take(facts: Facts) {
        if (cwd == null) cwd = facts.cwd
        facts.title?.let { if (it.custom) customTitle = it.text else generatedTitle = it.text }
        facts.permissionMode?.let { lastPermissionMode = it }
        if (firstPrompt == null) firstPrompt = facts.prompt
        facts.model?.let { lastModel = it }
        facts.effort?.let { lastEffort = it }
    }

    fun build(lineCount: Int): TranscriptMeta =
        TranscriptMeta(sessionId, customTitle, generatedTitle, firstPrompt, lastModel, lastEffort, lastPermissionMode, cwd, lineCount)
}

/**
 * A user line as Tandem shows it: an interrupt marker ([interrupted]), else what the user typed, if anything ([typed]), and the
 * images sent with it ([images]).
 */
private class UserLine(val interrupted: Boolean, val typed: Typed?, val images: List<UserImage>)

/** [user] as Tandem shows it; its images get the ids `<uuid>#<index>`, as the live send gives them. */
private fun userLineOf(user: CliMessage.User, uuid: String): UserLine {
    val text = user.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
    // The reducer's own test, so that a loaded line shows as the live one did.
    val interrupted = user.isInterrupt()
    val typed = if (interrupted) null else typedText(text)
    // Images make a prompt of their own, but not beside an interrupt marker or tool results with nothing typed: no prompt either.
    val images = if (interrupted || (typed == null && user.content.any { it is ContentBlock.ToolResult })) {
        emptyList()
    } else {
        user.content.filterIsInstance<ContentBlock.Image>().mapIndexed { i, b -> UserImage("$uuid#$i", b.mediaType, b.data) }
    }
    return UserLine(interrupted, typed, images)
}

/**
 * The second pass: collects the [TranscriptMeta] and reduces the history into [state], one [accept]ed line at a time. [abandoned]
 * lines ([TranscriptChain.abandoned]) are counted and otherwise left out.
 */
private class Scan(sessionId: String, private val abandoned: BitSet) {
    var state: ChatState = ChatState()
        private set

    private val meta = MetaBuilder(sessionId)
    var lineCount: Int = 0
        private set

    fun meta(): TranscriptMeta = meta.build(lineCount)

    fun accept(line: String) {
        val index = lineCount++
        if (abandoned[index]) return
        val message = Ndjson.decode(line)
        val raw = message.raw ?: return // not a JSON object, e.g. a line the CLI is still writing
        meta.take(factsOf(message, raw))
        when (raw.string("type")) {
            "user" -> if (!raw.isSet("isMeta") && !raw.isSet("isSidechain") && !raw.isSet("isCompactSummary")) userLine(message)
            "assistant" -> if (!raw.isSet("isSidechain")) {
                state = reduce(state, SessionEvent.Cli(message, atMillis = (message as? CliMessage.Assistant)?.timestampMillis ?: 0L))
            }
            "system" -> if (raw.string("subtype") == "local_command" && !raw.isSet("isMeta") && !raw.isSet("isSidechain")) localCommand(raw)
        }
    }

    /**
     * A `local_command` line. Its output (`content` wrapped in `<local-command-stdout>` or `<local-command-stderr>`, or a line that
     * names the command it ran, `commandRun`, or its outcome, `commandOutcome`) goes to the reducer as the stream's local-command
     * line does, so the notice is the same. Bare `content` with a `submitUuid` is the command as typed (`/help`, which has no
     * `<command-name>` line), a prompt. Any other, and one without a string `content`, is skipped.
     */
    private fun localCommand(raw: JsonObject) {
        val content = raw.string("content") ?: return
        val output = unwrapLocalCommandOutput(content)
        val run = raw.obj("commandRun")
        if (output != content || run != null || raw.obj("commandOutcome") != null) {
            state = reduce(state, SessionEvent.LocalCommandOutput(output, run?.string("command")))
        } else if (raw.string("submitUuid") != null) {
            state = reduce(state, SessionEvent.UserSent(raw.string("uuid") ?: "line-$lineCount", content, timestampMillis(raw) ?: 0L))
        }
    }

    private fun userLine(message: CliMessage) {
        val user = message as? CliMessage.User ?: return
        val uuid = user.uuid ?: "line-$lineCount"
        val shown = userLineOf(user, uuid)
        // Tool results and an interrupt marker go through the reducer, as they do live; once per line, since it handles both.
        // The API puts a user message's tool_result blocks first and any text after them, so a prompt comes after its results.
        // Both are stamped with the line's own timestamp, 0 (unknown) when it has none, as an older CLI writes it.
        val atMillis = user.timestampMillis ?: 0L
        if (shown.interrupted || user.content.any { it is ContentBlock.ToolResult }) state = reduce(state, SessionEvent.Cli(user, atMillis))
        // A line with images is a prompt even without typed text (images only), as it was when sent.
        if (shown.typed == null && shown.images.isEmpty()) return
        state = reduce(state, SessionEvent.UserSent(uuid, shown.typed?.text ?: "", atMillis, shown.images))
    }
}

/** The text of a title line; a blank one does not count. */
private fun JsonObject.titleText(key: String): String? = string(key)?.takeIf { it.isNotBlank() }

/** A user line's text as the history shows it. Only a [isPrompt] text can stand in for the title. */
private class Typed(val text: String, val isPrompt: Boolean)

/**
 * What the user typed, from a user line's [text], told apart the way the CLI tells its own lines apart: by how the text starts.
 * A slash command (`<command-name>` or `<command-message>` first) shows as `/name args` but is not a prompt for the title; a
 * shell escape (`<bash-input>`) shows as `!command`. Their output, background-task notifications and blank text give null.
 * Any other text is a prompt as it stands, even one that quotes such a tag further on.
 */
private fun typedText(text: String): Typed? {
    val start = text.trim()
    return when {
        start.isEmpty() || GENERATED_TAGS.any { start.startsWith(it) } -> null
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
internal fun promptTitle(text: String): String {
    val line = text.trim().lineSequence().first().trim()
    if (line.length <= PROMPT_TITLE_LENGTH) return line
    val end = if (line[PROMPT_TITLE_LENGTH - 1].isHighSurrogate()) PROMPT_TITLE_LENGTH - 1 else PROMPT_TITLE_LENGTH
    return line.substring(0, end).trimEnd()
}

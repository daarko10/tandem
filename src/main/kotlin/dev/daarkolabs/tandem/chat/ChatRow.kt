package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.EditReview
import dev.daarkolabs.tandem.session.NoticeKind
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.TurnEnd
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * One item of the chat list (spec 5.3). [key] is unique in the list and stays the same while the thing the row shows exists (one
 * exception: a call that fails, is denied or interrupted leaves its group, and the calls after it move to a row keyed by their first
 * call). [gap] is the space above the row, picked from the row before it. Rows hold only strings, numbers, flags, enums and
 * immutable lists, never JSON, so drawing one parses nothing.
 */
sealed interface ChatRow {
    val key: String
    val gap: RowGap
}

/** The space above a row, in dp (spec 4.1): consecutive tool and thinking rows sit 30 dp apart, a 24 dp row plus [ROW]. */
enum class RowGap(val dp: Int) {
    NONE(0),
    ROW(6),
    SECTION(16),
    AFTER_BUBBLE(18),
    BEFORE_TEXT(20),
    TURN(24),
}

/**
 * What the user sent, `u:<entry id>`. [mono]: a slash command or a `!` shell line, drawn in the editor font. [images]: the images
 * sent with it, in order, by id only (polish spec 2.7); an images-only prompt has a blank [text]. [caption]: the line under the
 * bubble, [FOLDED_CAPTION] for a prompt the CLI took into the running turn (desktop-parity spec 12.1); null for a prompt that
 * started its own turn.
 */
data class UserRow(
    override val key: String,
    val text: String,
    val mono: Boolean,
    override val gap: RowGap = RowGap.TURN,
    val images: ImmutableList<ImageRef> = persistentListOf(),
    val caption: String? = null,
) : ChatRow

/**
 * A message sent while Claude works that the CLI has not started yet (desktop-parity spec 12.1), `q:<uuid>`: a dimmed bubble with its
 * [text] and [images] after the working row, and its [caption] ([queuedCaption]). [withdrawable]: it has Edit and Cancel (spec 12.3);
 * [withdrawing]: a withdraw is out, and both are disabled. Once started, the message is a [UserRow] under the user row's own key.
 */
data class QueuedRow(
    override val key: String,
    val text: String,
    val images: ImmutableList<ImageRef>,
    val caption: String,
    val withdrawable: Boolean,
    val withdrawing: Boolean,
    override val gap: RowGap = RowGap.AFTER_BUBBLE,
) : ChatRow

/**
 * A shell escape (`! command`, MCP spec 12.1), `sh:<entry id>`, in place of a user bubble: its [command], its [output] with colour
 * escapes stripped and cut like a tool body (at most 400 lines; [totalLines] counts them all), whether it still [running] (its Stop),
 * and the line under it, when there is something to say: [status] (`exit 2`, `Stopped after 3.0s`, …), an error when [statusError].
 */
data class ShellRow(
    override val key: String,
    val command: String,
    val output: ImmutableList<String>,
    val totalLines: Int,
    val running: Boolean,
    val status: String?,
    val statusError: Boolean,
    override val gap: RowGap = RowGap.TURN,
) : ChatRow

/** One image of a [UserRow]: the id of its [dev.daarkolabs.tandem.session.UserImage], its media type and its [index] in the message. */
data class ImageRef(val id: String, val mediaType: String, val index: Int)

/**
 * One text block of a reply, `t:<entry id>:<block index>`. [actions] is set on the last text block of each ended turn's reply, which
 * the hover row goes under (polish spec 2.6).
 */
data class TextRow(
    override val key: String,
    val markdown: String,
    val streaming: Boolean,
    override val gap: RowGap,
    val actions: MessageActions? = null,
) : ChatRow

/**
 * What the hover row under a reply acts on (polish spec 2.6): the reply's entry ([entryId]), its [markdown] (the message's non-blank
 * text blocks joined by a blank line, the narration messages before it left out) for Copy, when it came ([atMillis], null when
 * unknown) for its time, and where `Fork from here` cuts ([forkAt], desktop-parity spec 13.1: the uuid of the latest complete line of
 * its turn's last main-thread message that has one; null when none has, so the turn cannot be forked).
 */
data class MessageActions(val entryId: String, val markdown: String, val atMillis: Long?, val forkAt: String? = null)

/**
 * Whether a reply's `Fork from here` can fork (desktop-parity spec 13.1). A fork made while a later turn runs knows only the turns up
 * to its cut (D8's `fork-at-busy`), so a running turn does not hold the button back.
 */
enum class ForkState {
    /** The reply has a fork point: `Fork from here`. */
    ENABLED,

    /** The reply has no fork point: `This reply cannot be forked: the CLI sent no id for it`. */
    UNAVAILABLE,
}

/** The fork state of a reply whose fork point is [forkAt]. */
fun forkState(forkAt: String?): ForkState = if (forkAt == null) ForkState.UNAVAILABLE else ForkState.ENABLED

/** One thinking block of a main-thread reply, `th:<entry id>:<block index>`; it opens only when [summary] is not blank. */
data class ThinkingRow(
    override val key: String,
    val label: String,
    val summary: String,
    val streaming: Boolean,
    override val gap: RowGap,
) : ChatRow

/** One tool call, or a group of them (from two calls on), `tool:<first call id>`. [stats] sum the calls' diff stats. */
data class ToolRow(
    override val key: String,
    val calls: ImmutableList<ToolCall>,
    val label: RowLabel,
    val state: ToolState,
    val stats: DiffStats?,
    override val gap: RowGap,
) : ChatRow

/**
 * A notice of the chat (retries, fallbacks, errors, a local command's output), `n:<entry id>`. On a [NoticeKind.LOCAL_COMMAND]
 * notice, [command] is the command it answers (its accessibility name `Reply to /<command>`, `Command output` when null) and [link]
 * the action that replaces the CLI's terminal hint (`/mcp`'s [MCP_TERMINAL_HINT], removed from [text]). A `/usage` notice of the
 * stream carries its card ([usage], drawn in place of [text]) and the uuid of the CLI's line ([sourceUuid], which its `Retry` names);
 * `/context`'s notice is drawn as Markdown ([markdown], spec 3.1).
 */
data class NoticeRow(
    override val key: String,
    val level: NoticeLevel,
    val text: String,
    override val gap: RowGap = RowGap.SECTION,
    val kind: NoticeKind = NoticeKind.GENERIC,
    val command: String? = null,
    val link: NoticeLink? = null,
    val usage: UsageCardModel? = null,
    val markdown: Boolean = false,
    val sourceUuid: String? = null,
) : ChatRow

/** An action at the end of a notice, with its label. */
enum class NoticeLink(val label: String) { OPEN_MCP("Open the MCP panel") }

/** The sentence `/mcp`'s local answer ends with (`mcp-slash:5`); a notice with [NoticeLink.OPEN_MCP] drops it, by exact match. */
const val MCP_TERMINAL_HINT: String = " Use `/mcp` in the terminal for details."

/** How the latest turn ended, `e:<entry id>` (or `e:<notice id>` for an interrupt marker without a result). */
data class TurnEndRow(override val key: String, val end: TurnEnd, val text: String, override val gap: RowGap = RowGap.SECTION) : ChatRow

/**
 * The working indicator while a turn runs; at most one, always last. When [step] is a running subagent's progress, [expandedStep] is
 * the step without it, shown while that subagent's Agent row is open (its nested rows then name the step, spec 4.9); else null.
 */
data class WorkingRow(
    override val key: String = "working",
    val turnUuid: String,
    val startedAtMillis: Long,
    val step: String,
    val outputTokens: Int,
    val waiting: Boolean,
    override val gap: RowGap = RowGap.SECTION,
    val expandedStep: String? = null,
) : ChatRow

/** A row's one line of text; each of [spans] (start and end index, inclusive) is drawn in the editor font. */
data class RowLabel(val text: String, val spans: ImmutableList<IntRange>)

/**
 * One tool call as a row shows it: its own [label] in its own tense, its diff [stats] once done, and its [body]: the one its result
 * built; else, while it runs or waits, its body so far ([ToolBodies.pending], polish spec 2.1); else [ToolBody.None], which a call
 * whose input still streams always has. [children] and [progress] belong to an Agent call: the subagent's rows and its sub-line.
 * [subject] is what a group sentence names: the path relative to the session's directory (reads, edits, writes) or the one-line
 * command (shell). A Tandem edit (spec 3.4) carries its [review] while the user reviews it in the IDE, the [tag] after its label
 * (`with your changes`, `auto-accepted`) and its row [action] (`Show diff` while its diff is open).
 */
data class ToolCall(
    val id: String,
    val name: String,
    val kind: ToolKind,
    val state: ToolState,
    val label: RowLabel,
    val stats: DiffStats?,
    val body: ToolBody,
    val children: ImmutableList<ChatRow>,
    val progress: String?,
    val subject: String? = null,
    val review: EditReview? = null,
    val tag: String? = null,
    val action: RowAction? = null,
)

/** What a tool row offers beside its label (spec 3.4). */
sealed interface RowAction {
    /** `Show diff`: focuses the open diff of Tandem's edit [toolUseId]. */
    data class ShowDiff(val toolUseId: String) : RowAction
}

/** The tag after an edit's label once the user accepted it with changes of their own (spec 3.4). */
const val TAG_WITH_YOUR_CHANGES: String = "with your changes"

/** The tag after an edit's label when it applied without a diff (spec 3.3). */
const val TAG_AUTO_ACCEPTED: String = "auto-accepted"

/** What a tool does, which picks its labels, its body and its family in a group (spec 4.6.2). */
enum class ToolKind { READ, FIND, SEARCH, SHELL, EDIT, CREATE, WEB_FETCH, WEB_SEARCH, AGENT, QUESTION, PLAN, PLAN_ENTER, TASKS, TOOL_SEARCH, MCP, OTHER }

/** Where a tool call is (spec 4.6.3). */
enum class ToolState { PENDING, RUNNING, WAITING, DONE, FAILED, DENIED, INTERRUPTED }

/** Lines added and removed; the zero side is shown too. */
data class DiffStats(val added: Int, val removed: Int)

/** What a tool row shows when it is opened (spec 4.7): pre-built text, at most 400 lines and 50,000 characters. */
sealed interface ToolBody {
    /** Nothing to open: the call's input still streams, it shows nothing before its result, or the result is empty. */
    data object None : ToolBody

    /** Lines in the editor font: a file read, file names, matches, written content, an error. */
    data class Mono(val lines: ImmutableList<String>, val totalLines: Int, val firstLineError: Boolean = false) : ToolBody

    /**
     * A shell command, then its output. [tokens] colour the command (polish spec 2.4); they are computed with the body, where it is
     * built, off the EDT, never in composition. A command cut at 50,000 characters gets its tokens from [ToolBodies], which colours
     * the kept part as shell and its cut line as one PLAIN token.
     */
    data class Command(
        val command: String,
        val output: ImmutableList<String>,
        val totalLines: Int,
        val firstLineError: Boolean,
        val tokens: ImmutableList<ShellToken> = ShellTokens.tokenize(command),
    ) : ToolBody

    /** Markdown to parse on first expansion: a fetched page's answer, a plan, an agent's final text. */
    data class Markdown(val markdown: String, val title: String?) : ToolBody

    /** A web search: its result links, then its summary. */
    data class Links(val links: ImmutableList<Link>, val markdown: String) : ToolBody

    /** Question and answer pairs. */
    data class Pairs(val pairs: ImmutableList<Pair<String, String>>) : ToolBody

    /** A tool's input and output as pretty JSON (MCP, tasks, unknown tools). */
    data class Json(val input: String, val output: String?) : ToolBody

    /** An edit's hunks, from its `structuredPatch`; [truncated] when lines were left out. */
    data class Diff(val path: String, val hunks: ImmutableList<DiffHunk>, val truncated: Boolean) : ToolBody

    /** An image result, drawn as `[image W×H png]`; its data never reaches a row. */
    data class Image(val width: Int?, val height: Int?, val mediaType: String) : ToolBody
}

data class DiffHunk(val oldStart: Int, val newStart: Int, val lines: ImmutableList<DiffLine>)

data class DiffLine(val kind: DiffKind, val text: String)

enum class DiffKind { CONTEXT, ADDED, REMOVED }

data class Link(val title: String, val url: String)

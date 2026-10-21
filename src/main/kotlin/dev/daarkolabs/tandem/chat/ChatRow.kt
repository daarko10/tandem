package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.TurnEnd
import kotlinx.collections.immutable.ImmutableList

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

/** What the user sent, `u:<entry id>`. [mono]: a slash command or a `!` shell line, drawn in the editor font. */
data class UserRow(override val key: String, val text: String, val mono: Boolean, override val gap: RowGap = RowGap.TURN) : ChatRow

/** One text block of a reply, `t:<entry id>:<block index>`. */
data class TextRow(override val key: String, val markdown: String, val streaming: Boolean, override val gap: RowGap) : ChatRow

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

/** A notice of the chat (retries, fallbacks, errors), `n:<entry id>`. */
data class NoticeRow(override val key: String, val level: NoticeLevel, val text: String, override val gap: RowGap = RowGap.SECTION) : ChatRow

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
 * command (shell).
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
)

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

    /** A shell command, then its output. */
    data class Command(val command: String, val output: ImmutableList<String>, val totalLines: Int, val firstLineError: Boolean) : ToolBody

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

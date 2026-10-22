package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.PermissionDenial
import dev.daarkolabs.tandem.protocol.PermissionMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Everything the chat UI renders for one session. Produced only by [reduce]. */
data class ChatState(
    /** The history, oldest first. [reduce] keeps it in a persistent list, so that an event never copies a long history. */
    val entries: List<ChatEntry> = emptyList(),
    val sessionId: String? = null,
    /** What the user picked; null means the CLI's default. */
    val requestedModel: String? = null,
    /** What the CLI reports it is actually using (`system/init`, assistant messages, fallbacks). */
    val activeModel: String? = null,
    /** Shown prominently while set: the CLI is not running the model the user picked. */
    val modelWarning: String? = null,
    /** The models the CLI offers, in its order, from the `models` of its `initialize` response; empty until it answers. */
    val models: List<ModelOption> = emptyList(),
    val permissionMode: PermissionMode? = null,
    /**
     * The `set_permission_mode` requests the CLI has not answered yet, by request id: [permissionMode] shows the mode each asked
     * for at once, and goes back to the mode before it if the CLI rejects it. Empty once a state is [settled].
     */
    val permissionModeChanges: Map<String, PermissionModeChange> = emptyMap(),
    /**
     * What Tandem passed with `--effort`, picked since (`apply_flag_settings`), or what a loaded transcript recorded: the recorded
     * `system/init` carries no effort.
     */
    val effort: String? = null,
    /**
     * The `apply_flag_settings` effort requests the CLI has not answered yet, by request id, in the order they were sent: [effort]
     * shows the level each asked for at once, and goes back to the effort before it if the CLI answers with an error. Empty once
     * [settled].
     */
    val effortChanges: Map<String, EffortChange> = emptyMap(),
    /**
     * A model was picked (`set_model`) and no `system/init` has confirmed it yet: until one does, the footer shows the
     * [requestedModel] rather than the [activeModel] the turn still running reports. A non-local refusal fallback clears it too.
     */
    val modelSwitchPending: Boolean = false,
    /**
     * The `set_model` requests the CLI has not answered yet, by request id, in the order they were sent: [requestedModel] shows the
     * model each asked for at once, and goes back to what was shown before it if the CLI answers with an error. Empty once [settled].
     */
    val modelChanges: Map<String, ModelChange> = emptyMap(),
    val claudeCodeVersion: String? = null,
    val busy: Boolean = false,
    val pendingPermissions: List<PendingPermission> = emptyList(),
    /** How the last turn ended, from its `result` line; null before the first. */
    val lastTurnEnd: TurnEnd? = null,
    /**
     * How many turns have ended: with a result, or with a process that ended during one (failed, or, when Tandem itself ended it,
     * interrupted). A permission answered outside a turn ends none. Only grows while a process runs, so that whoever follows it sees
     * every turn end, which [busy] alone, a state, may hide. Each [ChatEntry.TurnEnded] takes its id from it (`end-<n>`).
     */
    val turnsEnded: Int = 0,
    /** Whether the CLI marked the turn in progress as interrupted ("[Request interrupted by user]"); cleared when a turn starts or ends. */
    val turnInterrupted: Boolean = false,
    val ended: Boolean = false,
    /** Monotonic id source for notices; entry ids must stay unique even after retractions shrink [entries]. */
    val noticeCount: Int = 0,
    /**
     * A cache of each entry's position in [entries] by id (the last position, should an id repeat), so that [reduce] finds an
     * entry without scanning a long history. [reduce] keeps it in a persistent map and in step with [entries]: an append adds
     * the new position, a replacement keeps the id at its position, and a removal rebuilds it. A state built by hand may leave
     * it empty: [reduce] checks every position it reads, scans [entries] while the index is empty or out of step, and
     * re-indexes them on its first append. It tells "out of step" only by the last entry, so whoever changes [entries] other
     * than by appending through [reduce] (inserting, removing or moving entries while the last one stays) must reset this to
     * empty, or an entry may not be found. It takes part in equality: two states with the same entries, one indexed and one
     * not, are not equal.
     */
    val entryIndex: Map<String, Int> = emptyMap(),
    /** The working directory the CLI reports in `system/init`. */
    val cwd: String? = null,
    /** The turn in progress: set when the user sends, cleared when its `result` comes or the process ends. */
    val turn: TurnProgress? = null,
    /** The subagents the CLI started, by task id; background commands (`local_bash` tasks) are not kept. */
    val tasks: Map<String, TaskInfo> = emptyMap(),
    /**
     * How full the context window is, for the footer's ring: null until a main-thread `message_start` or a `result` says anything,
     * and on a reloaded transcript, whose lines' usage is not decoded.
     */
    val context: ContextUsage? = null,
)

/**
 * How full the context window is: [tokens] is the input of the main thread's latest `message_start` (plain, cache-creation and
 * cache-read), [window] the active model's context window from the latest `result`, else the largest it lists. Either may be
 * unknown: a `result` before any main-thread `message_start` knows the window only, and the ring is drawn only when both are known.
 */
data class ContextUsage(val tokens: Long?, val window: Long?)

/**
 * The turn in progress, as the working indicator shows it. [outputTokens] sums the turn's `message_delta` output tokens, and
 * [thinkingEstimate] is the CLI's running estimate for the thinking block that streams now (0 once its message's count is in).
 * [lastToolUseId] and [toolCount] follow the main thread's tool calls. [lastError] is the turn's latest API error, as
 * `API Error: <status> <error>`.
 */
data class TurnProgress(
    val userUuid: String,
    val startedAtMillis: Long,
    val outputTokens: Int,
    val thinkingEstimate: Int,
    val lastToolUseId: String?,
    val toolCount: Int,
    val lastError: String? = null,
)

/** A subagent the CLI runs for an `Agent` tool call ([toolUseId]), from its `task_*` lines: what it does now and its totals. */
data class TaskInfo(
    val taskId: String,
    val toolUseId: String?,
    val description: String?,
    val subagentType: String?,
    val status: String?,
    val lastToolName: String?,
    val totalTokens: Long?,
    val toolUses: Int?,
    val durationMs: Long?,
    val summary: String?,
)

/** How a turn ended, as its `result` line says. */
enum class TurnEnd {
    /** It ran to its end. */
    COMPLETED,

    /** It was interrupted (Stop): the result's terminal reason is `aborted_streaming`, or the CLI marked the turn as interrupted. */
    INTERRUPTED,

    /** It failed: any other result that is an error, or the process exited with an error during the turn. */
    FAILED,
}

/** A `set_permission_mode` request still to be answered: the mode it asks for, and the mode shown before it. */
data class PermissionModeChange(val requested: PermissionMode, val previous: PermissionMode?)

/** An `apply_flag_settings` effort request still to be answered: the level it asks for, and the effort shown before it. */
data class EffortChange(val requested: String, val previous: String?)

/**
 * A `set_model` request still to be answered: the model it asks for (null: the CLI's default), and what was shown before it: the
 * model picked then, whether that pick still waited for its `system/init` ([ChatState.modelSwitchPending]), and the model warning.
 */
data class ModelChange(val requested: String?, val previous: String?, val previousPending: Boolean, val previousWarning: String?)

/**
 * A model the CLI offers: [value] is what `--model` and `set_model` take, [displayName] and [description] are for people.
 * [resolvedModel] is the model id it stands for (what `system/init` reports), [supportsAutoMode] whether the `auto` permission mode
 * is offered with it, and [effortLevels] the efforts it takes, in the CLI's order (empty without `supportsEffort`).
 */
data class ModelOption(
    val value: String,
    val displayName: String,
    val description: String?,
    val resolvedModel: String? = null,
    val supportsAutoMode: Boolean = false,
    val effortLevels: List<String> = emptyList(),
)

/**
 * One image the user sent with a prompt: [id] is `<entry uuid>#<index>`, the same live and on reload; [base64] is its bytes as the
 * wire carries them. Kept on the entry only: rows never copy the data.
 */
data class UserImage(val id: String, val mediaType: String, val base64: String)

sealed interface ChatEntry {
    val id: String

    /** What the user sent; [sentAtMillis] is when, on the host's clock. [images] are its attachments, in the order sent. */
    data class User(
        override val id: String,
        val text: String,
        val sentAtMillis: Long? = null,
        val images: List<UserImage> = emptyList(),
    ) : ChatEntry

    /**
     * One API message. While [streaming], [blocks] are drafts built from stream deltas; each complete
     * `assistant` line replaces its draft block. [uuids] are the wire uuids of the complete lines (for retraction). [atMillis] is
     * when it came: the host's clock at its main-thread `message_start`, else at its first complete line (a reloaded transcript's
     * line timestamp); null when unknown (a line stamped 0, polish spec edge 21).
     */
    data class Assistant(
        override val id: String,
        val messageId: String,
        val parentToolUseId: String?,
        val model: String?,
        val blocks: List<AssistantBlock>,
        val completedBlocks: Int,
        val streaming: Boolean,
        val uuids: Set<String>,
        /** Set on a subagent's message: its task id, its type and the task's description. */
        val agentId: String? = null,
        val subagentType: String? = null,
        val taskDescription: String? = null,
        val atMillis: Long? = null,
    ) : ChatEntry

    /**
     * The result of tool call [toolUseId]. [content] is the block's raw content (a string, or blocks such as images); [result] is the
     * tool's own view of it (`tool_use_result`: an object, or an `Error: ...` string), kept only when the line carried this one
     * result. [decision] says how its permission was decided. [parentToolUseId] and [agentId] are set inside a subagent.
     */
    data class ToolResult(
        override val id: String,
        val toolUseId: String,
        val text: String,
        val isError: Boolean,
        val content: JsonElement? = null,
        val result: JsonElement? = null,
        val decision: PermissionDecision? = null,
        val parentToolUseId: String? = null,
        val agentId: String? = null,
        val atMillis: Long? = null,
    ) : ChatEntry

    /** Something the user must see that is not model output: retries, fallbacks, errors, stops. */
    data class Notice(override val id: String, val level: NoticeLevel, val text: String, val kind: NoticeKind = NoticeKind.GENERIC) : ChatEntry

    /**
     * The end of a turn: how it ended, how long it took and its output tokens, and the calls denied in it. [error] is set on a
     * [TurnEnd.FAILED] turn only: its API error, else the result's error text or what the process said as it ended. Its id is
     * `end-<n>`, [ChatState.turnsEnded] once it is counted.
     */
    data class TurnEnded(
        override val id: String,
        val end: TurnEnd,
        val durationMs: Long?,
        val outputTokens: Int?,
        val denials: List<PermissionDenial>,
        val error: String? = null,
    ) : ChatEntry
}

enum class NoticeLevel { INFO, WARNING, ERROR }

/** What a notice stands for: [INTERRUPTED] is the "Stopped." of an interrupted turn, which the chat shows as the turn's end. */
enum class NoticeKind { GENERIC, INTERRUPTED }

/** How a tool call's permission was decided ([decision], [source], [reasonType]), or why it did not run ([nonExecutionKind]). */
data class PermissionDecision(val decision: String, val source: String?, val reasonType: String?, val nonExecutionKind: String?)

sealed interface AssistantBlock {
    data class Text(val text: String) : AssistantBlock

    /**
     * [estimatedTokens] is the CLI's running estimate while it streams. [durationMs] comes with the complete line, or from the host's
     * clock ([startedAtMillis] to [completedAtMillis]) when the line has none. [completedAtMillis] is null while it streams.
     */
    data class Thinking(
        val text: String,
        val estimatedTokens: Int? = null,
        val durationMs: Long? = null,
        val startedAtMillis: Long? = null,
        val completedAtMillis: Long? = null,
    ) : AssistantBlock

    /** [input] is the final input once complete; [partialInput] accumulates `input_json_delta` while streaming. */
    data class ToolUse(val id: String, val name: String, val input: JsonObject?, val partialInput: String) : AssistantBlock

    data class Other(val type: String) : AssistantBlock
}

data class PendingPermission(
    val requestId: String,
    val toolName: String,
    val input: JsonObject,
    val toolUseId: String?,
    val description: String?,
    val suggestions: List<JsonObject>,
    val displayName: String? = null,
    /** The tool needs the user's answer (AskUserQuestion, ExitPlanMode), not only a permission. */
    val requiresUserInteraction: Boolean = false,
    val decisionReasonType: String? = null,
    /** Set when a subagent asks: its task id. */
    val agentId: String? = null,
    val blockedPath: String? = null,
)

/**
 * This state with no turn in progress: not busy, not ended, no pending permission and no reply still streaming.
 * A transcript loaded by `TranscriptReader` comes back settled.
 */
internal fun ChatState.settled(): ChatState = copy(
    entries = entries.map { if (it is ChatEntry.Assistant && it.streaming) it.copy(streaming = false) else it },
    busy = false,
    pendingPermissions = emptyList(),
    permissionModeChanges = emptyMap(),
    effortChanges = emptyMap(),
    modelChanges = emptyMap(),
    ended = false,
    turnInterrupted = false,
    turn = null,
)

package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.ContextBreakdown
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.PermissionDenial
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.PluginInfo
import dev.daarkolabs.tandem.protocol.SessionUsage
import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.protocol.UsageHistory
import kotlinx.collections.immutable.toPersistentList
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
     * The modes the CLI refused to switch to (`set_permission_mode` answered with an error) while this process runs: the mode cycle
     * skips them. A mode leaves when the CLI reports it (`system/status`, `system/init`); the set is cleared when the process ends.
     */
    val rejectedPermissionModes: Set<PermissionMode> = emptySet(),
    /**
     * The mode the process was started with (`--permission-mode`) until its first `system/init`, which is compared with it: the chat
     * says so when the CLI started in another mode (hard rule 5). A pick on the live process before that init clears it, since the
     * CLI's answer to that pick reports a refusal itself.
     */
    val launchPermissionMode: PermissionMode? = null,
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
    /** The session's MCP servers, as the latest `system/init` and `mcp_status` answer report them. */
    val mcp: McpState = McpState(),
    /** The slash commands the CLI offers, in its order, from the `commands` of its `initialize` answer; empty until it answers. */
    val commands: List<SlashCommand> = emptyList(),
    /** The command names of the latest `system/init` (`slash_commands`), for the palette while [commands] is empty. */
    val slashCommandNames: List<String> = emptyList(),
    /** The commands the latest `system/init` says work only in a terminal (`terminal_slash_commands`). */
    val terminalCommands: Set<String> = emptySet(),
    /** The id of the [ChatEntry.Shell] whose command runs now; null when none runs. */
    val runningShell: String? = null,
    /**
     * The uuids of the frames sent with `shouldQuery: false` (a shell escape's) whose zero-turn `result` is still to come, in the order
     * sent. Empty once the process that owed them ends, and once [settled].
     */
    val quietSends: Set<String> = emptySet(),
    /** The context window by category, from the latest `get_context_usage` answer; null until one came. Kept by [settled]. */
    val contextBreakdown: ContextBreakdown? = null,
    /** The plugins the latest `system/init` lists (`plugins`); absent means none. */
    val plugins: List<PluginInfo> = emptyList(),
    /** The folders the latest `system/init` lists besides the working directory (`additional_directories`); absent means none. */
    val additionalDirectories: List<String> = emptyList(),
    /** The skills the latest `system/init` lists (`skills`); absent means none. */
    val skills: List<String> = emptyList(),
    /**
     * The messages sent while Claude worked that have not started yet, in send order: waiting in the CLI's queue, or held by Tandem
     * behind a shell escape (spec 12.1, 12.4). Not chat entries: the CLI's lifecycle lines decide when each becomes one.
     */
    val pendingSends: List<PendingSend> = emptyList(),
    /** Waiting messages that came back without running (a withdraw, Stop, the process's end), for the composer to take back. */
    val returnedSends: List<ReturnedSend> = emptyList(),
    /**
     * The `capabilities` of the live process's latest `system/init`: null until one came (the CLI is still starting) and once the
     * process ended or the chat was [settled]; empty for an init that lists none.
     */
    val cliCapabilities: Set<String>? = null,
    /** Each Tandem edit's review, by tool use id ([SessionEvent.EditPhase], from the IDE's `TandemEdits`; spec 3.4, 4.2). */
    val edits: Map<String, EditReview> = emptyMap(),
    /** `Accept all` was pressed in this turn: its later edits apply without a diff. Reset by the turn's `result` (spec 3.3). */
    val acceptAll: Boolean = false,
)

/** Where a Tandem edit's review stands: waiting behind another diff, its diff open, or decided. */
enum class EditPhase { QUEUED, OPEN, DONE }

/** How a decided Tandem edit came out (spec 3.4): accepted as proposed or with the user's changes, auto-accepted, rejected, stopped. */
enum class EditOutcome { ACCEPTED, ACCEPTED_CHANGED, AUTO, REJECTED, STOPPED }

/** The review of one Tandem edit of [file] (its name as the row shows it): its [phase], and once [EditPhase.DONE] its [outcome]. */
data class EditReview(val file: String, val phase: EditPhase, val outcome: EditOutcome? = null)

/** What a [ContextChip] stands for: the editor's selection, or a file with unsaved changes sent as a text block (spec 3.5). */
enum class ContextChipKind { SELECTION, UNSAVED_FILE }

/** A context block a prompt carried, as its bubble shows it: its [kind], its [label] and the file's [path]. */
data class ContextChip(val kind: ContextChipKind, val label: String, val path: String)

/**
 * A message sent while Claude worked, not started yet: its [text] and [images] are Tandem's own copy (the CLI never echoes it),
 * [later] whether it was sent with `"priority":"later"`. [restoreOnWithdraw]: while [PendingStage.WITHDRAWING], whether it goes
 * back to the composer (Edit) or is dropped (Cancel). [typed] is the draft as the user typed it (`@src/A.kt`, where [text] holds the
 * bare path of a dirty mention, spec 3.5): what goes back to the composer, so that the next send swaps against the buffer as it is then.
 */
data class PendingSend(
    val uuid: String,
    val text: String,
    val images: List<UserImage>,
    val sentAtMillis: Long,
    val later: Boolean,
    val stage: PendingStage,
    val restoreOnWithdraw: Boolean = false,
    /** The context blocks sent before its text, as chips (spec 3.5); its entry takes them once it starts. */
    val context: List<ContextChip> = emptyList(),
    val typed: String = text,
)

/** Where a [PendingSend] stands. */
enum class PendingStage {
    /** Held by Tandem behind a shell escape's frames, not written yet. */
    HELD,

    /** Written, and waiting in the CLI's queue. */
    QUEUED,

    /** A `cancel_async_message` for it is out. */
    WITHDRAWING,
}

/** A message that came back without running, for the composer to put back ahead of the draft: its [text] as typed. */
data class ReturnedSend(val uuid: String, val text: String, val images: List<UserImage>)

/** How many characters of a message's first line a notice quotes (spec 12.5). */
private const val FIRST_LINE_LENGTH: Int = 60

/** [text]'s first line, cut at 60 characters with `…` (spec 12.5): how a notice names a message. */
internal fun firstLine(text: String): String {
    val line = text.lineSequence().first()
    return if (line.length <= FIRST_LINE_LENGTH) line else line.take(FIRST_LINE_LENGTH) + "…"
}

/** The notice of a message Stop or a stop gave back (spec 12.3, 12.5). */
internal fun notSentText(text: String): String = "Not sent: \"${firstLine(text)}\". It is back in the composer."

/** The notice of a message the process's end gave back (spec 12.5). */
internal fun endedBeforeReadingText(text: String): String =
    "Not sent: \"${firstLine(text)}\": the CLI ended before reading it. It is back in the composer."

/** The notice of a refused withdraw (spec 12.3). */
internal fun notWithdrawnText(text: String): String = "Claude had already taken \"${firstLine(text)}\"; it was not withdrawn."

/**
 * The session's MCP servers: [servers] in the CLI's order; [detailed] once an `mcp_status` answer gave them (errors, config, tools),
 * not only `system/init`'s thin list; [initCount] counts the `system/init` lines seen.
 */
data class McpState(val servers: List<McpServerInfo> = emptyList(), val detailed: Boolean = false, val initCount: Int = 0)

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
    /** The CLI answered the turn's prompt itself (a local command such as `/mcp`): its `result` ends it without a footer. */
    val localCommand: Boolean = false,
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
 * The CLI's own model aliases, as `--model` and `set_model` take them: what a session offers while no process has ever listed its
 * models ([SessionManager.models]). Without a [ModelOption.resolvedModel]: which model an alias stands for is the CLI's to say.
 */
val CLI_MODEL_ALIASES: List<ModelOption> = listOf(
    ModelOption("default", "Default", null),
    ModelOption("opus", "Opus", null),
    ModelOption("fable", "Fable", null),
    ModelOption("sonnet", "Sonnet", null),
    ModelOption("haiku", "Haiku", null),
)

/**
 * One image the user sent with a prompt: [id] is `<entry uuid>#<index>`, the same live and on reload; [base64] is its bytes as the
 * wire carries them. Kept on the entry only: rows never copy the data.
 */
data class UserImage(val id: String, val mediaType: String, val base64: String)

sealed interface ChatEntry {
    val id: String

    /**
     * What the user sent; [sentAtMillis] is when, on the host's clock. [images] are its attachments, in the order sent. [folded]: sent
     * while Claude worked and taken into the running turn (spec 12.1).
     */
    data class User(
        override val id: String,
        val text: String,
        val sentAtMillis: Long? = null,
        val images: List<UserImage> = emptyList(),
        val folded: Boolean = false,
        /** The context blocks sent before its text (the selection, an unsaved file), as chips (spec 3.5). */
        val context: List<ContextChip> = emptyList(),
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
        /**
         * The uuid of its latest complete line, where a fork at this message cuts (`--resume-session-at`, spec 13.1); null for a
         * message drafted from stream deltas only.
         */
        val lastUuid: String? = null,
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
        /**
         * Why the call did not run, from its `tool_result_meta` entry, with or without a [decision]: `user-rejected` on the CLI's own
         * result for a call Stop withdrew (`ide-edit-stop:44`), which carries no decision.
         */
        val nonExecutionKind: String? = null,
    ) : ChatEntry

    /**
     * Something the user must see that is not model output: retries, fallbacks, errors, stops, and what a local command printed
     * ([NoticeKind.LOCAL_COMMAND]). On a local command's notice, [command] is the command the user typed, from the prompt before it
     * (else [runCommand]), and [runCommand] the command the CLI says it ran (`local_command_run.command`), which decides its link.
     */
    data class Notice(
        override val id: String,
        val level: NoticeLevel,
        val text: String,
        val kind: NoticeKind = NoticeKind.GENERIC,
        val command: String? = null,
        val runCommand: String? = null,
        /** On a usage notice of the stream: the `uuid` of the CLI's line, which keys its [usage] card's answers. */
        val sourceUuid: String? = null,
        /** On a usage notice of the stream ([isUsageLine]): the card the `get_usage` answers fill; null on any other notice. */
        val usage: UsageCard? = null,
    ) : ChatEntry

    /**
     * A shell escape (`! command`), in place of a prompt: the [command] as typed after `!`, its [output] so far (stdout and stderr
     * merged), whether it still [running], how it ended ([end]; null while it runs and on a reloaded one, whose transcript keeps no
     * end) and how long it ran. [sent]: its frames went to the CLI.
     */
    data class Shell(
        override val id: String,
        val command: String,
        val output: String,
        val running: Boolean,
        val end: ShellEnd? = null,
        val durationMs: Long? = null,
        val startedAtMillis: Long? = null,
        val sent: Boolean = false,
    ) : ChatEntry

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

/** The `/usage` card of a usage notice: this session's numbers (`get_usage` with `skip_behaviors`) and the machine's history. */
data class UsageCard(val session: UsagePartState<SessionUsage>, val history: UsagePartState<UsageHistory?>)

/** Where one part of a [UsageCard] stands: its request runs, it answered (a null history: the CLI did not report one), or it failed. */
sealed interface UsagePartState<out T> {
    data object Loading : UsagePartState<Nothing>

    data class Loaded<T>(val value: T) : UsagePartState<T>

    data class Failed(val error: ControlError) : UsagePartState<Nothing>
}

/** A part of a [UsageCard]: [SESSION] (the request without the scan) or [HISTORY] (the full request). */
enum class UsagePart { SESSION, HISTORY }

/**
 * What a notice stands for: [INTERRUPTED] is the "Stopped." of an interrupted turn, which the chat shows as the turn's end;
 * [LOCAL_COMMAND] is the output of a command the CLI answered itself, such as `/mcp`, in place of a reply.
 */
enum class NoticeKind { GENERIC, INTERRUPTED, LOCAL_COMMAND }

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
 * This state with no turn in progress: not busy, not ended, no pending permission and no reply still streaming. No quiet send waits
 * for its result either: the process that owed it is gone, and the next one starts from this. A shell escape stays as it is (a
 * command runs on in Tandem's own process). A usage card's part still loading can no longer be answered: it reads
 * [McpError.Ended]. The context breakdown stays. A message still waiting goes back to the composer with an INFO notice (spec 12.5), and
 * no process's capabilities count any more. A transcript loaded by `TranscriptReader` comes back settled.
 */
internal fun ChatState.settled(): ChatState = returnWaiting(NoticeLevel.INFO, ::notSentText).withNothingInProgress()

/** [settled] once nothing waits any more: every field below is read from this state, the returned messages' notices included. */
private fun ChatState.withNothingInProgress(): ChatState = copy(
    entries = entries.map {
        when {
            it is ChatEntry.Assistant && it.streaming -> it.copy(streaming = false)
            it is ChatEntry.Notice -> it.withUsageEnded()
            else -> it
        }
    },
    busy = false,
    pendingPermissions = emptyList(),
    permissionModeChanges = emptyMap(),
    rejectedPermissionModes = emptySet(),
    launchPermissionMode = null,
    effortChanges = emptyMap(),
    modelChanges = emptyMap(),
    ended = false,
    turnInterrupted = false,
    turn = null,
    quietSends = emptySet(),
    cliCapabilities = null,
    acceptAll = false,
)

/**
 * The history a fork starts from (spec 12.5): [settled] without the waiting and returned messages, so no `Not sent` notice is
 * added: a waiting message stays the original's and still runs there.
 */
internal fun ChatState.forkHistory(): ChatState = copy(pendingSends = emptyList(), returnedSends = emptyList()).settled()

/**
 * The history a fork at [uuid] starts from (spec 13.1): [forkHistory] up to the first main-thread [ChatEntry.Assistant] whose
 * [ChatEntry.Assistant.uuids] hold [uuid], and on to the next [ChatEntry.User] or [ChatEntry.Shell] entry after it, so that its
 * whole turn and the turn's footer stay. The ring waits for the fork's first turn ([ChatState.context] null), and only the tasks and
 * edit reviews of calls still shown stay. Null when no main-thread assistant entry holds [uuid] (a subagent's line, or none of this history's).
 */
internal fun ChatState.forkedAt(uuid: String): ChatState? {
    val history = forkHistory()
    val entries = history.entries
    val at = entries.indexOfFirst { it is ChatEntry.Assistant && it.parentToolUseId == null && uuid in it.uuids }
    if (at < 0) return null
    val next = (at + 1 until entries.size).firstOrNull { entries[it] is ChatEntry.User || entries[it] is ChatEntry.Shell }
    val kept = entries.subList(0, next ?: entries.size).toPersistentList()
    val calls = kept.asSequence().filterIsInstance<ChatEntry.Assistant>().flatMap { it.blocks }
        .filterIsInstance<AssistantBlock.ToolUse>().mapTo(HashSet()) { it.id }
    return history.copy(
        entries = kept,
        entryIndex = emptyMap(),
        context = null,
        tasks = history.tasks.filterValues { it.toolUseId in calls },
        // Edit reviews go with their calls' entries.
        edits = history.edits.filterKeys { it in calls },
    )
}

/** This notice with each part of its usage card that is still loading turned [McpError.Ended]; the same instance when none is. */
internal fun ChatEntry.Notice.withUsageEnded(): ChatEntry.Notice {
    val card = usage ?: return this
    if (card.session != UsagePartState.Loading && card.history != UsagePartState.Loading) return this
    return copy(usage = UsageCard(card.session.endedIfLoading(), card.history.endedIfLoading()))
}

private fun <T> UsagePartState<T>.endedIfLoading(): UsagePartState<T> =
    if (this == UsagePartState.Loading) UsagePartState.Failed(McpError.Ended) else this

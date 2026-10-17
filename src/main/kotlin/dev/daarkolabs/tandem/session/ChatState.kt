package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.PermissionMode
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
    /** What Tandem passed with `--effort`, or what a loaded transcript recorded: the recorded `system/init` carries no effort. */
    val effort: String? = null,
    val claudeCodeVersion: String? = null,
    val busy: Boolean = false,
    val pendingPermissions: List<PendingPermission> = emptyList(),
    val ended: Boolean = false,
    /** Monotonic id source for notices; entry ids must stay unique even after retractions shrink [entries]. */
    val noticeCount: Int = 0,
    /**
     * A cache of each entry's position in [entries] by id (the last position, should an id repeat), so that [reduce] finds an
     * entry without scanning a long history. [reduce] keeps it in a persistent map and in step with [entries]: an append adds
     * the new position, a replacement keeps the id at its position, and a removal rebuilds it. A state built by hand may leave
     * it empty: [reduce] checks every position it reads, scans [entries] while the index is empty or out of step, and
     * re-indexes them on its first append.
     */
    val entryIndex: Map<String, Int> = emptyMap(),
)

/** A `set_permission_mode` request still to be answered: the mode it asks for, and the mode shown before it. */
data class PermissionModeChange(val requested: PermissionMode, val previous: PermissionMode?)

/** A model the CLI offers: [value] is what `--model` and `set_model` take, [displayName] and [description] are for people. */
data class ModelOption(val value: String, val displayName: String, val description: String?)

sealed interface ChatEntry {
    val id: String

    data class User(override val id: String, val text: String) : ChatEntry

    /**
     * One API message. While [streaming], [blocks] are drafts built from stream deltas; each complete
     * `assistant` line replaces its draft block. [uuids] are the wire uuids of the complete lines (for retraction).
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
    ) : ChatEntry

    data class ToolResult(override val id: String, val toolUseId: String, val text: String, val isError: Boolean) : ChatEntry

    /** Something the user must see that is not model output: retries, fallbacks, errors, stops. */
    data class Notice(override val id: String, val level: NoticeLevel, val text: String) : ChatEntry
}

enum class NoticeLevel { INFO, WARNING, ERROR }

sealed interface AssistantBlock {
    data class Text(val text: String) : AssistantBlock

    data class Thinking(val text: String) : AssistantBlock

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
    ended = false,
)

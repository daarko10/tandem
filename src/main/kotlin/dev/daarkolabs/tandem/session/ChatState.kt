package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.PermissionMode
import kotlinx.serialization.json.JsonObject

/** Everything the chat UI renders for one session. Produced only by [reduce]. */
data class ChatState(
    val entries: List<ChatEntry> = emptyList(),
    val sessionId: String? = null,
    /** What the user picked; null means the CLI's default. */
    val requestedModel: String? = null,
    /** What the CLI reports it is actually using (`system/init`, assistant messages, fallbacks). */
    val activeModel: String? = null,
    /** Shown prominently while set: the CLI is not running the model the user picked. */
    val modelWarning: String? = null,
    val permissionMode: PermissionMode? = null,
    val claudeCodeVersion: String? = null,
    val busy: Boolean = false,
    val pendingPermissions: List<PendingPermission> = emptyList(),
    val ended: Boolean = false,
)

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

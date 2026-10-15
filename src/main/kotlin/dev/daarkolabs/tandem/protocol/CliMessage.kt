package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One line of the CLI's stream-json stdout. See docs/protocol.md.
 *
 * Only the fields Tandem acts on are typed; everything else stays reachable through [raw].
 * Anything that is not recognised, or lacks the fields its type requires, is [Unknown].
 */
sealed interface CliMessage {
    /** The decoded line, or null for [Unknown] lines that were not a JSON object. */
    val raw: JsonObject?

    /** `system/init`: emitted at the start of every turn. [model] must be checked against the user's pick. */
    data class SystemInit(
        val sessionId: String,
        val model: String,
        val cwd: String?,
        val permissionMode: PermissionMode?,
        val claudeCodeVersion: String?,
        val tools: List<String>,
        val capabilities: List<String>,
        override val raw: JsonObject,
    ) : CliMessage

    /** `system/status`: `requesting`, `compacting` or null (idle). */
    data class SystemStatus(val status: String?, val permissionMode: PermissionMode?, override val raw: JsonObject) : CliMessage

    /** `system/api_retry`: the CLI is retrying an API call. Must be visible to the user. */
    data class ApiRetry(
        val attempt: Int?,
        val maxRetries: Int?,
        val retryDelayMs: Long?,
        val errorStatus: Int?,
        val error: String?,
        override val raw: JsonObject,
    ) : CliMessage

    /** `system/model_refusal_fallback`: the turn was retried on [fallbackModel]. */
    data class ModelRefusalFallback(
        val originalModel: String,
        val fallbackModel: String,
        /** `session` (session model swapped) or `local` (one side answer only). Absent means `session`. */
        val scope: String,
        val retractedMessageUuids: List<String>,
        val content: String?,
        override val raw: JsonObject,
    ) : CliMessage

    /** `system/model_refusal_no_fallback`: the model refused and nothing retried. */
    data class ModelRefusalNoFallback(val originalModel: String?, val content: String?, override val raw: JsonObject) : CliMessage

    /** A complete assistant content block (one line per block; consecutive lines share [messageId]). */
    data class Assistant(
        val uuid: String?,
        val messageId: String?,
        val model: String?,
        val parentToolUseId: String?,
        val content: List<ContentBlock>,
        /** Set on synthetic error messages, e.g. `authentication_failed`, `model_not_found`. */
        val error: String?,
        val supersedes: List<String>,
        val aborted: Boolean,
        override val raw: JsonObject,
    ) : CliMessage

    /** Tool results, replays of host sends, and CLI-generated user messages. */
    data class User(
        val uuid: String?,
        val parentToolUseId: String?,
        val content: List<ContentBlock>,
        val isReplay: Boolean,
        val isSynthetic: Boolean,
        override val raw: JsonObject,
    ) : CliMessage

    /** A raw API stream event (with `--include-partial-messages`). */
    data class StreamEvent(
        val event: dev.daarkolabs.tandem.protocol.StreamEvent,
        val parentToolUseId: String?,
        val apiMessageId: String?,
        override val raw: JsonObject,
    ) : CliMessage

    /** End of a turn. */
    data class Result(
        val subtype: String,
        val isError: Boolean,
        val result: String?,
        val stopReason: String?,
        val terminalReason: String?,
        val sessionId: String?,
        val totalCostUsd: Double?,
        val errors: List<String>,
        val modelUsage: Set<String>,
        override val raw: JsonObject,
    ) : CliMessage

    /** The CLI asks the host something. Every request needs exactly one response. */
    data class ControlRequest(val requestId: String, val request: InboundRequest, override val raw: JsonObject) : CliMessage

    /** The CLI answers one of our requests. */
    data class ControlResponse(
        val requestId: String,
        val isSuccess: Boolean,
        val payload: JsonObject?,
        val error: String?,
        override val raw: JsonObject,
    ) : CliMessage

    /** The CLI withdraws one of its own pending requests: close the prompt, send nothing. */
    data class ControlCancelRequest(val requestId: String, override val raw: JsonObject) : CliMessage

    data class KeepAlive(override val raw: JsonObject) : CliMessage

    /** Anything else. [type] is the line's `type` when it had one. Never an error. */
    data class Unknown(val line: String, val type: String?, val subtype: String?, override val raw: JsonObject?) : CliMessage
}

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock

    data class Thinking(val thinking: String) : ContentBlock

    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock

    /** [content] is a string or an array of blocks, depending on the tool. */
    data class ToolResult(val toolUseId: String, val content: JsonElement?, val isError: Boolean) : ContentBlock

    data class Other(val type: String, val raw: JsonObject) : ContentBlock
}

/** The `event` of a `stream_event` line. */
sealed interface StreamEvent {
    data class MessageStart(val messageId: String, val model: String?) : StreamEvent

    data class ContentBlockStart(val index: Int, val block: ContentBlock) : StreamEvent

    data class ContentBlockDelta(val index: Int, val delta: Delta) : StreamEvent

    data class ContentBlockStop(val index: Int) : StreamEvent

    data class MessageDelta(val stopReason: String?) : StreamEvent

    data object MessageStop : StreamEvent

    data class Other(val type: String?, val raw: JsonObject?) : StreamEvent
}

sealed interface Delta {
    data class Text(val text: String) : Delta

    data class Thinking(val thinking: String) : Delta

    data class InputJson(val partialJson: String) : Delta

    data class Other(val type: String?) : Delta
}

/** Requests the CLI sends to the host. */
sealed interface InboundRequest {
    val subtype: String

    data class CanUseTool(
        val toolName: String,
        val input: JsonObject,
        val toolUseId: String?,
        /** `PermissionUpdate` objects for "always allow" choices, passed back as `updatedPermissions`. */
        val permissionSuggestions: List<JsonObject>,
        val blockedPath: String?,
        val title: String?,
        val displayName: String?,
        val description: String?,
    ) : InboundRequest {
        override val subtype: String get() = "can_use_tool"
    }

    /** Unsupported request: answer with [HostMessages.error] so the CLI never hangs. */
    data class Other(override val subtype: String, val request: JsonObject) : InboundRequest
}

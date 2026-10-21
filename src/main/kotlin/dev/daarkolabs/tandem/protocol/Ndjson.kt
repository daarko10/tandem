package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Newline-delimited JSON codec for the CLI's stream-json protocol. Decoding never throws. */
object Ndjson {
    private val json: Json = Json { isLenient = false }

    /** One frame for stdin: compact JSON plus `\n`. */
    fun encode(message: JsonObject): String = message.toString() + "\n"

    /** Decodes one stdout line. Anything unrecognised becomes [CliMessage.Unknown]. */
    fun decode(line: String): CliMessage {
        val element: JsonElement = try {
            json.parseToJsonElement(line)
        } catch (_: IllegalArgumentException) { // SerializationException: not JSON
            return CliMessage.Unknown(line, type = null, subtype = null, raw = null)
        }
        val obj = element as? JsonObject ?: return CliMessage.Unknown(line, type = null, subtype = null, raw = null)
        return decodeObject(obj) ?: CliMessage.Unknown(line, obj.string("type"), obj.string("subtype"), obj)
    }

    private fun decodeObject(o: JsonObject): CliMessage? = when (o.string("type")) {
        "system" -> decodeSystem(o)
        "assistant" -> decodeAssistant(o)
        "user" -> decodeUser(o)
        "stream_event" -> decodeStreamEvent(o)
        "result" -> decodeResult(o)
        "control_request" -> decodeControlRequest(o)
        "control_response" -> decodeControlResponse(o)
        "control_cancel_request" -> o.string("request_id")?.let { CliMessage.ControlCancelRequest(it, o) }
        "keep_alive" -> CliMessage.KeepAlive(o)
        else -> null
    }

    private fun decodeSystem(o: JsonObject): CliMessage? = when (o.string("subtype")) {
        "init" -> o.string("model")?.let { model ->
            CliMessage.SystemInit(
                sessionId = o.string("session_id").orEmpty(),
                model = model,
                cwd = o.string("cwd"),
                permissionMode = PermissionMode.fromWire(o.string("permissionMode")),
                claudeCodeVersion = o.string("claude_code_version"),
                tools = o.strings("tools"),
                capabilities = o.strings("capabilities"),
                raw = o,
            )
        }
        "status" -> CliMessage.SystemStatus(o.string("status"), PermissionMode.fromWire(o.string("permissionMode")), o)
        "api_retry" -> CliMessage.ApiRetry(
            attempt = o.int("attempt"),
            maxRetries = o.int("max_retries"),
            retryDelayMs = o.long("retry_delay_ms"),
            errorStatus = o.int("error_status"),
            error = o.string("error"),
            raw = o,
        )
        "model_refusal_fallback" -> {
            val original = o.string("original_model")
            val fallback = o.string("fallback_model")
            if (original == null || fallback == null) {
                null
            } else {
                CliMessage.ModelRefusalFallback(
                    originalModel = original,
                    fallbackModel = fallback,
                    scope = o.string("scope") ?: "session",
                    retractedMessageUuids = o.strings("retracted_message_uuids"),
                    content = o.string("content"),
                    raw = o,
                )
            }
        }
        "model_refusal_no_fallback" -> CliMessage.ModelRefusalNoFallback(o.string("original_model"), o.string("content"), o)
        else -> null
    }

    private fun decodeAssistant(o: JsonObject): CliMessage? {
        val message = o.obj("message") ?: return null
        return CliMessage.Assistant(
            uuid = o.string("uuid"),
            messageId = message.string("id"),
            model = message.string("model"),
            parentToolUseId = o.string("parent_tool_use_id"),
            content = decodeContent(message["content"]),
            error = o.string("error"),
            supersedes = o.strings("supersedes"),
            aborted = o.boolean("aborted") == true,
            raw = o,
        )
    }

    private fun decodeUser(o: JsonObject): CliMessage? {
        val message = o.obj("message") ?: return null
        return CliMessage.User(
            uuid = o.string("uuid"),
            parentToolUseId = o.string("parent_tool_use_id"),
            content = decodeContent(message["content"]),
            isReplay = o.boolean("isReplay") == true,
            isSynthetic = o.boolean("isSynthetic") == true,
            raw = o,
        )
    }

    private fun decodeContent(content: JsonElement?): List<ContentBlock> = when (content) {
        is JsonPrimitive -> if (content.isString) listOf(ContentBlock.Text(content.content)) else emptyList()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.let(::decodeBlock) }
        else -> emptyList()
    }

    private fun decodeBlock(b: JsonObject): ContentBlock? {
        val type = b.string("type") ?: return null
        return when (type) {
            "text" -> ContentBlock.Text(b.string("text").orEmpty())
            "thinking" -> ContentBlock.Thinking(b.string("thinking").orEmpty())
            "tool_use" -> {
                val id = b.string("id")
                val name = b.string("name")
                if (id == null || name == null) ContentBlock.Other(type, b) else ContentBlock.ToolUse(id, name, b.obj("input") ?: JsonObject(emptyMap()))
            }
            "tool_result" -> b.string("tool_use_id")?.let { ContentBlock.ToolResult(it, b["content"], b.boolean("is_error") == true) }
                ?: ContentBlock.Other(type, b)
            else -> ContentBlock.Other(type, b)
        }
    }

    private fun decodeStreamEvent(o: JsonObject): CliMessage? {
        val event = o.obj("event") ?: return null
        return CliMessage.StreamEvent(decodeEvent(event), o.string("parent_tool_use_id"), o.string("api_message_id"), o)
    }

    private fun decodeEvent(e: JsonObject): StreamEvent {
        val type = e.string("type")
        val index = e.int("index")
        val other = StreamEvent.Other(type, e)
        return when (type) {
            "message_start" -> e.obj("message")?.let { m -> m.string("id")?.let { StreamEvent.MessageStart(it, m.string("model")) } } ?: other
            "content_block_start" -> {
                val block = e.obj("content_block")?.let(::decodeBlock)
                if (index == null || block == null) other else StreamEvent.ContentBlockStart(index, block)
            }
            "content_block_delta" -> {
                val delta = e.obj("delta")
                if (index == null || delta == null) other else decodeDelta(delta)?.let { StreamEvent.ContentBlockDelta(index, it) } ?: other
            }
            "content_block_stop" -> index?.let { StreamEvent.ContentBlockStop(it) } ?: other
            "message_delta" -> StreamEvent.MessageDelta(e.obj("delta")?.string("stop_reason"))
            "message_stop" -> StreamEvent.MessageStop
            else -> other
        }
    }

    /** Null for a known delta type with a missing payload; unknown delta types stay visible as [Delta.Other]. */
    private fun decodeDelta(d: JsonObject): Delta? = when (val type = d.string("type")) {
        "text_delta" -> d.string("text")?.let { Delta.Text(it) }
        "thinking_delta" -> d.string("thinking")?.let { Delta.Thinking(it) }
        "input_json_delta" -> d.string("partial_json")?.let { Delta.InputJson(it) }
        else -> if (type == null) null else Delta.Other(type)
    }

    private fun decodeResult(o: JsonObject): CliMessage? {
        val subtype = o.string("subtype") ?: return null
        return CliMessage.Result(
            subtype = subtype,
            isError = o.boolean("is_error") == true,
            result = o.string("result"),
            stopReason = o.string("stop_reason"),
            terminalReason = o.string("terminal_reason"),
            sessionId = o.string("session_id"),
            totalCostUsd = o.double("total_cost_usd"),
            errors = o.strings("errors"),
            modelUsage = o.obj("modelUsage")?.keys.orEmpty(),
            raw = o,
        )
    }

    private fun decodeControlRequest(o: JsonObject): CliMessage? {
        val requestId = o.string("request_id") ?: return null
        val request = o.obj("request") ?: return null
        val subtype = request.string("subtype") ?: return null
        return CliMessage.ControlRequest(requestId, decodeInboundRequest(subtype, request), o)
    }

    private fun decodeInboundRequest(subtype: String, r: JsonObject): InboundRequest {
        if (subtype == "can_use_tool") {
            val toolName = r.string("tool_name")
            val input = r.obj("input")
            if (toolName != null && input != null) {
                return InboundRequest.CanUseTool(
                    toolName = toolName,
                    input = input,
                    toolUseId = r.string("tool_use_id"),
                    permissionSuggestions = r.objects("permission_suggestions"),
                    blockedPath = r.string("blocked_path"),
                    title = r.string("title"),
                    displayName = r.string("display_name"),
                    description = r.string("description"),
                )
            }
        }
        return InboundRequest.Other(subtype, r)
    }

    private fun decodeControlResponse(o: JsonObject): CliMessage? {
        val response = o.obj("response") ?: return null
        val requestId = response.string("request_id") ?: return null
        return CliMessage.ControlResponse(
            requestId = requestId,
            isSuccess = response.string("subtype") == "success",
            payload = response.obj("response"),
            error = response.string("error"),
            raw = o,
        )
    }
}

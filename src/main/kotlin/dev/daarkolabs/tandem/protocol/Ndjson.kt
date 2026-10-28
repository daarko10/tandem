package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DateTimeException
import java.time.Instant

/**
 * Newline-delimited JSON codec for the CLI's stream-json protocol. Decoding never throws.
 *
 * It also decodes the lines of the CLI's on-disk transcripts, which name some fields in camelCase (`toolUseResult`,
 * `thinkingDurationMs`, ...): where the two differ, both names are read, the stream-json one first.
 */
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
        "command_lifecycle" -> decodeCommandLifecycle(o)
        else -> null
    }

    /** A missing or non-string `command_uuid` or `state` leaves the line [CliMessage.Unknown]. */
    private fun decodeCommandLifecycle(o: JsonObject): CliMessage? {
        val commandUuid = o.string("command_uuid") ?: return null
        val state = o.string("state") ?: return null
        return CliMessage.CommandLifecycle(commandUuid, state, o)
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
                mcpServers = o.objects("mcp_servers").mapNotNull(::mcpServerSummary),
                slashCommands = o.strings("slash_commands"),
                terminalSlashCommands = o.strings("terminal_slash_commands"),
                plugins = o.objects("plugins").mapNotNull(::pluginInfo),
                additionalDirectories = o.strings("additional_directories"),
                skills = o.strings("skills"),
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
        "thinking_tokens" -> o.int("estimated_tokens")?.let { tokens ->
            CliMessage.ThinkingTokens(tokens, o.int("estimated_tokens_delta"), o.string("user_message_uuid"), o)
        }
        "task_started" -> o.string("task_id")?.let { taskId ->
            CliMessage.TaskStarted(
                taskId = taskId,
                toolUseId = o.string("tool_use_id"),
                description = o.string("description"),
                subagentType = o.string("subagent_type"),
                taskType = o.string("task_type"),
                isBackgrounded = o.boolean("is_backgrounded") == true,
                raw = o,
            )
        }
        "task_progress" -> o.string("task_id")?.let { taskId ->
            CliMessage.TaskProgress(
                taskId = taskId,
                toolUseId = o.string("tool_use_id"),
                description = o.string("description"),
                usage = taskUsage(o.obj("usage")),
                lastToolName = o.string("last_tool_name"),
                raw = o,
            )
        }
        "task_updated" -> o.string("task_id")?.let { CliMessage.TaskUpdated(it, o.obj("patch")?.string("status"), o) }
        "task_notification" -> o.string("task_id")?.let { taskId ->
            CliMessage.TaskNotification(
                taskId = taskId,
                toolUseId = o.string("tool_use_id"),
                status = o.string("status"),
                summary = o.string("summary"),
                usage = taskUsage(o.obj("usage")),
                raw = o,
            )
        }
        else -> null
    }

    private fun taskUsage(usage: JsonObject?): TaskUsage? =
        usage?.let { TaskUsage(totalTokens = it.long("total_tokens"), toolUses = it.int("tool_uses"), durationMs = it.long("duration_ms")) }

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
            apiErrorStatus = o.int("api_error_status") ?: o.int("apiErrorStatus"),
            thinkingDurationMs = o.long("thinking_duration_ms") ?: o.long("thinkingDurationMs"),
            thinkingDisplay = o.string("thinking_display") ?: o.string("thinkingDisplay"),
            agentId = o.string("agent_id") ?: o.string("agentId"),
            subagentType = o.string("subagent_type") ?: o.string("subagentType"),
            taskDescription = o.string("task_description") ?: o.string("taskDescription"),
            timestampMillis = timestampMillis(o),
            localCommand = localCommand(o),
            toolUseMeta = toolUseMeta(o),
            raw = o,
        )
    }

    private fun decodeUser(o: JsonObject): CliMessage? {
        val message = o.obj("message") ?: return null
        val content = decodeContent(message["content"])
        return CliMessage.User(
            uuid = o.string("uuid"),
            parentToolUseId = o.string("parent_tool_use_id"),
            content = content,
            isReplay = o.boolean("isReplay") == true,
            isSynthetic = o.boolean("isSynthetic") == true,
            toolUseResult = listOf("tool_use_result", "toolUseResult").firstNotNullOfOrNull { key -> o[key]?.takeUnless { it is JsonNull } },
            toolResultMeta = toolResultMeta(o, content),
            agentId = o.string("agent_id") ?: o.string("agentId"),
            subagentType = o.string("subagent_type") ?: o.string("subagentType"),
            taskDescription = o.string("task_description") ?: o.string("taskDescription"),
            timestampMillis = timestampMillis(o),
            raw = o,
        )
    }

    /**
     * Stream-json lists `tool_result_meta` entries by tool use id; an on-disk line instead carries one top-level
     * `permissionDecision` and `toolDenialKind`, which apply to each of its `tool_result` blocks.
     */
    private fun toolResultMeta(o: JsonObject, content: List<ContentBlock>): List<ToolResultMeta> {
        o.array("tool_result_meta")?.let { entries ->
            return entries.mapNotNull { entry ->
                val e = entry as? JsonObject ?: return@mapNotNull null
                e.string("id")?.let { id -> toolResultMeta(id, e.obj("permission_decision"), e.string("non_execution_kind")) }
            }
        }
        val decision = o.obj("permissionDecision")
        val denialKind = o.string("toolDenialKind")
        if (decision == null && denialKind == null) return emptyList()
        return content.filterIsInstance<ContentBlock.ToolResult>().map { toolResultMeta(it.toolUseId, decision, denialKind) }
    }

    private fun toolResultMeta(id: String, decision: JsonObject?, nonExecutionKind: String?): ToolResultMeta = ToolResultMeta(
        id = id,
        decision = decision?.string("decision"),
        source = decision?.string("source"),
        reasonType = decision?.let { it.string("reason_type") ?: it.string("reasonType") },
        nonExecutionKind = nonExecutionKind,
    )

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
            "image" -> imageOf(b) ?: ContentBlock.Other(type, b)
            else -> ContentBlock.Other(type, b)
        }
    }

    /** An image block with a base64 source, a media type and data; null for any other source (a URL, a file id, missing fields). */
    private fun imageOf(b: JsonObject): ContentBlock.Image? {
        val source = b.obj("source")?.takeIf { it.string("type") == "base64" } ?: return null
        val mediaType = source.string("media_type") ?: return null
        val data = source.string("data") ?: return null
        return ContentBlock.Image(mediaType, data)
    }

    private fun decodeStreamEvent(o: JsonObject): CliMessage? {
        val event = o.obj("event") ?: return null
        return CliMessage.StreamEvent(decodeEvent(event, o), o.string("parent_tool_use_id"), o.string("api_message_id"), o)
    }

    /** [line] is the `stream_event` line around [e]: it carries `thinking_display`. */
    private fun decodeEvent(e: JsonObject, line: JsonObject): StreamEvent {
        val type = e.string("type")
        val index = e.int("index")
        val other = StreamEvent.Other(type, e)
        return when (type) {
            "message_start" -> e.obj("message")?.let { m ->
                m.string("id")?.let { StreamEvent.MessageStart(it, m.string("model"), line.string("thinking_display"), inputTokens(m.obj("usage"))) }
            } ?: other
            "content_block_start" -> {
                val block = e.obj("content_block")?.let(::decodeBlock)
                if (index == null || block == null) other else StreamEvent.ContentBlockStart(index, block)
            }
            "content_block_delta" -> {
                val delta = e.obj("delta")
                if (index == null || delta == null) other else decodeDelta(delta)?.let { StreamEvent.ContentBlockDelta(index, it) } ?: other
            }
            "content_block_stop" -> index?.let { StreamEvent.ContentBlockStop(it) } ?: other
            "message_delta" -> {
                val usage = e.obj("usage")
                StreamEvent.MessageDelta(
                    stopReason = e.obj("delta")?.string("stop_reason"),
                    outputTokens = usage?.int("output_tokens"),
                    thinkingTokens = usage?.obj("output_tokens_details")?.int("thinking_tokens"),
                )
            }
            "message_stop" -> StreamEvent.MessageStop
            else -> other
        }
    }

    /** All the input tokens of a request: plain, cache-creation and cache-read; null when [usage] has none of them. */
    private fun inputTokens(usage: JsonObject?): Long? {
        val parts = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens").mapNotNull { usage?.long(it) }
        return if (parts.isEmpty()) null else parts.sum()
    }

    /** Null for a known delta type with a missing payload; unknown delta types stay visible as [Delta.Other]. */
    private fun decodeDelta(d: JsonObject): Delta? = when (val type = d.string("type")) {
        "text_delta" -> d.string("text")?.let { Delta.Text(it) }
        "thinking_delta" -> d.string("thinking")?.let { Delta.Thinking(it, d.int("estimated_tokens")) }
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
            durationMs = o.long("duration_ms"),
            durationApiMs = o.long("duration_api_ms"),
            outputTokens = o.obj("usage")?.int("output_tokens"),
            numTurns = o.int("num_turns"),
            permissionDenials = o.objects("permission_denials").mapNotNull { d ->
                d.string("tool_name")?.let { PermissionDenial(it, d.string("tool_use_id")) }
            },
            contextWindows = o.obj("modelUsage")?.mapNotNull { (model, usage) ->
                usage.asObject()?.long("contextWindow")?.let { model to it }
            }?.toMap().orEmpty(),
            localCommand = o.string("local_command"),
            userMessageUuids = o.strings("user_message_uuids"),
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
                    requiresUserInteraction = r.boolean("requires_user_interaction") == true,
                    decisionReasonType = r.string("decision_reason_type"),
                    agentId = r.string("agent_id"),
                    mcpServer = mcpServerRef(r),
                    serverPolicyAskOnly = r.boolean("server_policy_ask_only") == true,
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

/** The line's ISO-8601 `timestamp` in epoch milliseconds; null when absent or unreadable. */
internal fun timestampMillis(o: JsonObject): Long? {
    val text = o.string("timestamp") ?: return null
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) { // an instant too far out for epoch milliseconds
        null
    }
}

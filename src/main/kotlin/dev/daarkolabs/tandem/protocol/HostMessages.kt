package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One image attachment of a prompt: its [mediaType] (`image/png`, ...) and its bytes as [base64], as the wire carries them. */
data class ImageBlock(val mediaType: String, val base64: String)

/** Frames the host writes to the CLI's stdin. Shapes follow docs/protocol.md sections 3 and 4. */
object HostMessages {
    /**
     * A prompt: a text block for [text] unless it is blank, then one base64 image block per entry of [images], in order
     * (docs/protocol.md 3). [uuid] comes back as `user_message_uuids` so turns can be correlated.
     */
    fun userMessage(text: String, images: List<ImageBlock>, uuid: String): JsonObject = buildJsonObject {
        put("type", "user")
        put("session_id", "")
        putJsonObject("message") {
            put("role", "user")
            putJsonArray("content") {
                if (text.isNotBlank()) {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
                for (image in images) {
                    addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", image.mediaType)
                            put("data", image.base64)
                        }
                    }
                }
            }
        }
        put("parent_tool_use_id", JsonNull)
        put("uuid", uuid)
    }

    /**
     * A shell escape's command, frame 1 of bash probes 1: content a string, `client_composed` (no `@path` expansion or slash
     * dispatch) and `shouldQuery: false`, so it only appends; the CLI answers it with a zero-turn `result` naming [uuid].
     */
    fun bashInput(command: String, uuid: String): JsonObject = bashFrame(BashEscape.input(command), uuid, query = false)

    /**
     * A shell escape's output, frame 2 of bash probes 1: with [query], the key `shouldQuery` is left out and the send starts a turn
     * that answers it (`bash-mode-respond`); without, `shouldQuery: false` only appends it.
     */
    fun bashOutput(stdout: String, stderr: String, uuid: String, query: Boolean): JsonObject =
        bashFrame(BashEscape.output(stdout, stderr), uuid, query)

    /**
     * A shell escape for the context only, in one frame: the merged content of `transcripts/bash-mode.jsonl:5`, `shouldQuery: false`.
     * Unrecorded as one host frame (the probe sent two quiet frames, which the CLI merged into this content).
     */
    fun bashContext(command: String, stdout: String, stderr: String, uuid: String): JsonObject =
        bashFrame(BashEscape.context(command, stdout, stderr), uuid, query = false)

    /** `forwardSubagentText` makes the CLI forward a subagent's text replies as assistant lines with an `agent_id`. */
    fun initialize(requestId: String): JsonObject = request(requestId, "initialize") {
        put("hooks", JsonNull)
        put("forwardSubagentText", true)
    }

    fun interrupt(requestId: String): JsonObject = request(requestId, "interrupt") {}

    /** [model] null resets to the session default. Confirm the switch from the next `system/init`. */
    fun setModel(requestId: String, model: String?): JsonObject = request(requestId, "set_model") { put("model", model) }

    fun setPermissionMode(requestId: String, mode: PermissionMode): JsonObject =
        request(requestId, "set_permission_mode") { put("mode", mode.wire) }

    /**
     * Changes the session's effort from its next turn (`apply_flag_settings`, docs/protocol.md 4.4): [effortLevel] goes into the
     * session's flag settings. The success has no `response` field.
     */
    fun applyFlagSettings(requestId: String, effortLevel: String): JsonObject = request(requestId, "apply_flag_settings") {
        putJsonObject("settings") { put("effortLevel", effortLevel) }
    }

    /** Asks the CLI to give the session a custom title. */
    fun renameSession(requestId: String, title: String): JsonObject = request(requestId, "rename_session") {
        put("title", title)
        put("source", "host")
    }

    /** Asks for every MCP server's state, config and tools (`mcp_status`). */
    fun mcpStatus(requestId: String): JsonObject = request(requestId, "mcp_status") {}

    /** Disables or enables the MCP server [serverName], named exactly as `mcp_status` reports it. The success has no `response`. */
    fun mcpToggle(requestId: String, serverName: String, enabled: Boolean): JsonObject = request(requestId, "mcp_toggle") {
        put("serverName", serverName)
        put("enabled", enabled)
    }

    /** Reconnects the MCP server [serverName]; a failure answers with the CLI's error text. */
    fun mcpReconnect(requestId: String, serverName: String): JsonObject = request(requestId, "mcp_reconnect") {
        put("serverName", serverName)
    }

    /** Starts the MCP server [serverName]'s sign-in: the answer carries the `authUrl` the host opens itself. No `redirectUri`. */
    fun mcpAuthenticate(requestId: String, serverName: String): JsonObject = request(requestId, "mcp_authenticate") {
        put("serverName", serverName)
    }

    /** Allows a `can_use_tool` request. Always echo the (possibly edited) input. */
    fun allow(requestId: String, toolUseId: String?, updatedInput: JsonObject, updatedPermissions: List<JsonObject> = emptyList()): JsonObject =
        success(requestId) {
            put("behavior", "allow")
            put("updatedInput", updatedInput)
            if (updatedPermissions.isNotEmpty()) put("updatedPermissions", buildJsonArray { updatedPermissions.forEach { add(it) } })
            toolUseId?.let { put("toolUseID", it) }
        }

    /** Denies a `can_use_tool` request. [interrupt] also aborts the turn. */
    fun deny(requestId: String, toolUseId: String?, message: String, interrupt: Boolean = false): JsonObject = success(requestId) {
        put("behavior", "deny")
        put("message", message)
        if (interrupt) put("interrupt", JsonPrimitive(true))
        toolUseId?.let { put("toolUseID", it) }
    }

    /** Error answer for an inbound request Tandem does not support. */
    fun error(requestId: String, message: String): JsonObject = buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") {
            put("subtype", "error")
            put("request_id", requestId)
            put("error", message)
        }
    }

    /** A `user` frame whose content is the string [content], in the keys' order of bash probes 1. */
    private fun bashFrame(content: String, uuid: String, query: Boolean): JsonObject = buildJsonObject {
        put("type", "user")
        put("session_id", "")
        put("parent_tool_use_id", JsonNull)
        put("uuid", uuid)
        putJsonObject("message") {
            put("role", "user")
            put("content", content)
        }
        put("client_composed", true)
        if (!query) put("shouldQuery", false)
    }

    private fun request(requestId: String, subtype: String, fields: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        putJsonObject("request") {
            put("subtype", subtype)
            fields()
        }
    }

    private fun success(requestId: String, body: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") {
            put("subtype", "success")
            put("request_id", requestId)
            putJsonObject("response", body)
        }
    }
}

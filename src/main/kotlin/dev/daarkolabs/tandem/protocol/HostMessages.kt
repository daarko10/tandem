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

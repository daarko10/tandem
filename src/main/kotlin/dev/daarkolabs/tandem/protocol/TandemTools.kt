package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What a Tandem tool answers the model: [text] as one text block, an error when [isError] (spec 4.3's table). */
data class ToolReply(val text: String, val isError: Boolean)

/** A text block a prompt carries before its own text (spec 3.5): one text block `<firstLine>\n```\n<body>\n```` */
data class ContextBlock(val firstLine: String, val body: String)

/**
 * Tandem's own MCP server, `tandem`, as the CLI reaches it over the control channel (`initialize.sdkMcpServers`, `mcp_message`):
 * the answers of spec 4.1's table. Pure: the edit itself is the host's ([answer]'s `call`).
 */
object TandemTools {
    const val SERVER_NAME: String = "tandem"
    const val EDIT_FILE: String = "edit_file"
    const val WRITE_FILE: String = "write_file"

    /** The protocol version answered when the CLI's `initialize` names none: the one it asked for in every recording. */
    private const val DEFAULT_PROTOCOL_VERSION = "2025-11-25"
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601
    private const val INVALID_PARAMS = -32602
    private const val TOOL_USE_ID_KEY = "claudecode/toolUseId"

    /** The two tools as `tools/list` lists them (spec 4.1); the wire is `src/test/resources/tandem-tools.json`, byte for byte. */
    val toolList: JsonArray = buildJsonArray {
        addJsonObject {
            put("name", EDIT_FILE)
            put(
                "description",
                "Replace one occurrence of old_string with new_string in the file at file_path (every occurrence with replace_all). " +
                    "The match is exact and runs against the file as open in the IDE, unsaved changes included. The user reviews the " +
                    "change in a diff and may alter it before it lands; the result tells you what was applied.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("file_path") {
                        put("type", "string")
                        put("description", "Absolute path, inside the project or an added folder")
                    }
                    putJsonObject("old_string") { put("type", "string") }
                    putJsonObject("new_string") { put("type", "string") }
                    putJsonObject("replace_all") {
                        put("type", "boolean")
                        put("default", false)
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("file_path"))
                    add(JsonPrimitive("old_string"))
                    add(JsonPrimitive("new_string"))
                }
            }
        }
        addJsonObject {
            put("name", WRITE_FILE)
            put(
                "description",
                "Create the file at file_path with content, or replace an existing file's content. The user reviews it in a diff and " +
                    "may alter it before it lands; the result tells you what was applied.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("file_path") { put("type", "string") }
                    putJsonObject("content") { put("type", "string") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("file_path"))
                    add(JsonPrimitive("content"))
                }
            }
        }
    }

    /** Each tool's required string arguments and the text a call without them gets (spec 4.3, malformed arguments). */
    private val required: Map<String, Pair<List<String>, String>> = mapOf(
        EDIT_FILE to (listOf("file_path", "old_string", "new_string") to "edit_file needs file_path, old_string and new_string as strings."),
        WRITE_FILE to (listOf("file_path", "content") to "write_file needs file_path and content as strings."),
    )

    /**
     * The reply to the JSON-RPC [message] of an `mcp_message` for `tandem`, as a thunk the caller runs where it likes (a
     * `tools/call` in its own job, so the reader keeps reading). Every message gets exactly one reply, except a `tools/call` whose
     * [call] returns null (the CLI withdrew it: the thunk returns null and nothing is sent). [call] gets the tool's name, its
     * validated arguments, `_meta["claudecode/toolUseId"]` and the request's id; `notifications/cancelled` hands its
     * `params.requestId` (a number or a string) to [cancel] at once, before the thunk runs. [version] is the plugin's.
     */
    fun answer(
        message: JsonObject,
        version: String,
        call: suspend (name: String, arguments: JsonObject, toolUseId: String?, id: JsonElement?) -> ToolReply?,
        cancel: (JsonElement) -> Unit,
    ): suspend () -> JsonObject? {
        val request = jsonRpcRequest(message)
            ?: return constant(jsonRpcError(message["id"], INVALID_REQUEST, "Invalid request"))
        val id = request.id
        if (id == null) {
            if (request.method == "notifications/cancelled") {
                request.params?.get("requestId")?.takeIf(::isRequestId)?.let(cancel)
            }
            return constant(jsonRpcNotificationReply())
        }
        return when (request.method) {
            "initialize" -> constant(jsonRpcResult(id, initializeResult(request.params, version)))
            "ping" -> constant(jsonRpcResult(id, JsonObject(emptyMap())))
            "tools/list" -> constant(jsonRpcResult(id, buildJsonObject { put("tools", toolList) }))
            "tools/call" -> toolsCall(id, request.params, call)
            else -> constant(jsonRpcError(id, METHOD_NOT_FOUND, "Method not found: ${request.method}"))
        }
    }

    private fun toolsCall(
        id: JsonElement,
        params: JsonObject?,
        call: suspend (name: String, arguments: JsonObject, toolUseId: String?, id: JsonElement?) -> ToolReply?,
    ): suspend () -> JsonObject? {
        val name = params?.string("name")
        val tool = name?.let(required::get)
        if (params == null || name == null || tool == null) {
            return constant(jsonRpcError(id, INVALID_PARAMS, "Unknown tool: ${name.orEmpty()}"))
        }
        val (fields, malformed) = tool
        val arguments = params.obj("arguments")
        if (arguments == null || fields.any { arguments.string(it) == null }) {
            return constant(jsonRpcResult(id, toolResult(ToolReply(malformed, isError = true))))
        }
        val toolUseId = params.obj("_meta")?.string(TOOL_USE_ID_KEY)
        return { call(name, arguments, toolUseId, id)?.let { jsonRpcResult(id, toolResult(it)) } }
    }

    private fun initializeResult(params: JsonObject?, version: String): JsonObject = buildJsonObject {
        put("protocolVersion", params?.string("protocolVersion") ?: DEFAULT_PROTOCOL_VERSION)
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") {
            put("name", SERVER_NAME)
            put("version", version)
        }
    }

    /** `{"content":[{"type":"text","text":…}]}`, with `"isError":true` first for an error (spec 4.1). */
    private fun toolResult(reply: ToolReply): JsonObject = buildJsonObject {
        if (reply.isError) put("isError", true)
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", reply.text)
            }
        }
    }

    private fun constant(reply: JsonObject): suspend () -> JsonObject? = { reply }
}

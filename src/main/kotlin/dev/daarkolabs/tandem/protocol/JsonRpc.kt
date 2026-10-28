package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One JSON-RPC message the CLI carries in an `mcp_message` (spec 4.1): [method], [id] as the request's own number or string
 * (null on a notification; a string id is never turned into anything else) and [params] when they are an object.
 */
data class JsonRpcRequest(val method: String, val id: JsonElement?, val params: JsonObject?)

/** [message] as a [JsonRpcRequest], or null when its `method` is not a string or its `id` is neither a number, a string nor absent. */
fun jsonRpcRequest(message: JsonObject): JsonRpcRequest? {
    val method = message.string("method") ?: return null
    val id = message["id"]
    if (id != null && !isRequestId(id)) return null
    return JsonRpcRequest(method, id, message.obj("params"))
}

/** True for a JSON-RPC id Tandem keeps: a string or a number, never `null`, a boolean, an array or an object. */
internal fun isRequestId(id: JsonElement): Boolean =
    id is JsonPrimitive && (id.isString || (id.content != "null" && id.content != "true" && id.content != "false"))

/** `{"jsonrpc":"2.0","id":<id or 0>,"result":…}`, the [id] echoed verbatim. */
fun jsonRpcResult(id: JsonElement?, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonPrimitive(0))
    put("result", result)
}

/** `{"jsonrpc":"2.0","id":<id or 0>,"error":{"code":…,"message":…}}`, the [id] echoed verbatim. */
fun jsonRpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonPrimitive(0))
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}

/** A notification's reply, as the Agent SDK answers it (keys in its order): `{"jsonrpc":"2.0","result":{},"id":0}`. */
internal fun jsonRpcNotificationReply(): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("result", JsonObject(emptyMap()))
    put("id", 0)
}

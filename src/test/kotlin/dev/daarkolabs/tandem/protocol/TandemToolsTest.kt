package dev.daarkolabs.tandem.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tandem's own MCP server's JSON-RPC answers (spec 4.1's table). */
class TandemToolsTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private class Recorder(val reply: ToolReply? = ToolReply("done", isError = false)) {
        val calls: MutableList<List<Any?>> = mutableListOf()
        val cancelled: MutableList<JsonElement> = mutableListOf()

        suspend fun call(name: String, arguments: JsonObject, toolUseId: String?, id: JsonElement?): ToolReply? {
            calls += listOf(name, arguments, toolUseId, id)
            return reply
        }
    }

    private fun answer(message: String, recorder: Recorder = Recorder()): JsonObject? = runBlocking {
        withTimeout(10_000) { TandemTools.answer(json(message), "0.4.0", recorder::call, { recorder.cancelled += it })() }
    }

    @Test
    fun `every method of the table`() {
        // initialize echoes protocolVersion and names the server.
        assertEquals(
            json(
                """{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},""" +
                    """"serverInfo":{"name":"tandem","version":"0.4.0"}}}""",
            ),
            answer("""{"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{}},"jsonrpc":"2.0","id":0}"""),
        )
        // Without a protocolVersion, the one the CLI asked for in the recording.
        assertEquals(
            JsonPrimitive("2025-11-25"),
            answer("""{"method":"initialize","jsonrpc":"2.0","id":0}""")!!["result"]!!.jsonObject["protocolVersion"],
        )
        // Any notification: the Agent SDK's empty result, byte for byte.
        assertEquals(
            """{"jsonrpc":"2.0","result":{},"id":0}""",
            answer("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").toString(),
        )
        assertEquals("""{"jsonrpc":"2.0","result":{},"id":0}""", answer("""{"jsonrpc":"2.0","method":"notifications/whatever"}""").toString())
        // ping
        assertEquals("""{"jsonrpc":"2.0","id":5,"result":{}}""", answer("""{"jsonrpc":"2.0","id":5,"method":"ping"}""").toString())
        // tools/list
        val list = assertNotNull(answer("""{"method":"tools/list","jsonrpc":"2.0","id":1}"""))
        assertEquals(JsonPrimitive(1), list["id"])
        assertEquals(TandemTools.toolList, list["result"]!!.jsonObject["tools"])
        assertEquals(listOf("edit_file", "write_file"), TandemTools.toolList.map { it.jsonObject["name"].toString().trim('"') })
        // anything else
        assertEquals(
            """{"jsonrpc":"2.0","id":4,"error":{"code":-32601,"message":"Method not found: resources/list"}}""",
            answer("""{"jsonrpc":"2.0","id":4,"method":"resources/list"}""").toString(),
        )
        // an unknown tool
        val recorder = Recorder()
        assertEquals(
            """{"jsonrpc":"2.0","id":6,"error":{"code":-32602,"message":"Unknown tool: delete_file"}}""",
            answer("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"delete_file","arguments":{}}}""", recorder).toString(),
        )
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `a tools call reaches the host with its tool use id and the reply is the MCP content`() {
        val recorder = Recorder(ToolReply("Applied.", isError = false))
        val message = """{"method":"tools/call","params":{"name":"edit_file","arguments":{"file_path":"/p/a.txt","old_string":"a",""" +
            """"new_string":"b"},"_meta":{"claudecode/toolUseId":"toolu_1","progressToken":2}},"jsonrpc":"2.0","id":2}"""
        assertEquals(
            """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"Applied."}]}}""",
            answer(message, recorder).toString(),
        )
        val call = recorder.calls.single()
        assertEquals("edit_file", call[0])
        assertEquals(json("""{"file_path":"/p/a.txt","old_string":"a","new_string":"b"}"""), call[1])
        assertEquals("toolu_1", call[2])
        assertEquals(JsonPrimitive(2), call[3])

        val error = Recorder(ToolReply("The user rejected this edit in the IDE; nothing was changed.", isError = true))
        val write = """{"method":"tools/call","params":{"name":"write_file","arguments":{"file_path":"/p/n.txt","content":"x"}},"jsonrpc":"2.0","id":3}"""
        assertEquals(
            """{"jsonrpc":"2.0","id":3,"result":{"isError":true,"content":[{"type":"text","text":""" +
                """"The user rejected this edit in the IDE; nothing was changed."}]}}""",
            answer(write, error).toString(),
        )
        assertNull(error.calls.single()[2], "no _meta, no tool use id")
    }

    @Test
    fun `a withdrawn call gets no reply`() {
        val message = """{"method":"tools/call","params":{"name":"write_file","arguments":{"file_path":"/p/n.txt","content":"x"}},"jsonrpc":"2.0","id":3}"""
        assertNull(answer(message, Recorder(reply = null)))
    }

    @Test
    fun `malformed arguments of each tool get its own text`() {
        for ((arguments, text) in listOf(
            """{"file_path":"/p/a.txt","old_string":"a"}""" to "edit_file needs file_path, old_string and new_string as strings.",
            """{"file_path":"/p/a.txt","old_string":"a","new_string":3}""" to "edit_file needs file_path, old_string and new_string as strings.",
        )) {
            val recorder = Recorder()
            val reply = answer("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"edit_file","arguments":$arguments}}""", recorder)
            assertEquals(
                """{"jsonrpc":"2.0","id":1,"result":{"isError":true,"content":[{"type":"text","text":"$text"}]}}""",
                reply.toString(),
            )
            assertTrue(recorder.calls.isEmpty())
        }
        val write = answer("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"write_file","arguments":{"file_path":"/p/a.txt"}}}""")
        assertEquals(
            """{"jsonrpc":"2.0","id":1,"result":{"isError":true,"content":[{"type":"text","text":"write_file needs file_path and content as strings."}]}}""",
            write.toString(),
        )
    }

    @Test
    fun `notifications cancelled hands the request id over and is acknowledged`() {
        val recorder = Recorder()
        assertEquals(
            """{"jsonrpc":"2.0","result":{},"id":0}""",
            answer("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2,"reason":"AbortError: remote-cancel"}}""", recorder)
                .toString(),
        )
        assertEquals(listOf<JsonElement>(JsonPrimitive(2)), recorder.cancelled)
        // A requestId that is neither a number nor a string cancels nothing, and is still acknowledged.
        val none = Recorder()
        assertEquals(
            """{"jsonrpc":"2.0","result":{},"id":0}""",
            answer("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":{"x":1}}}""", none).toString(),
        )
        answer("""{"jsonrpc":"2.0","method":"notifications/cancelled"}""", none)
        assertTrue(none.cancelled.isEmpty())
    }

    @Test
    fun `tools list is byte-stable`() {
        assertEquals(File("src/test/resources/tandem-tools.json").readText().trim(), Json.encodeToString(JsonArray.serializer(), TandemTools.toolList))
    }
}

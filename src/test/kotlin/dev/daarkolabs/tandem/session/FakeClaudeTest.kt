package dev.daarkolabs.tandem.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fake CLI's own rules for Tandem's edit tools (spec 9.6): `src/test/resources/fake-claude.py` run over pipes, with this test
 * as the host, answering what a host answers. Every read is bounded.
 */
class FakeClaudeTest {
    private val fakeClaude: String = System.getProperty("tandem.claudePath") ?: "src/test/resources/fake-claude.py"

    @TempDir
    lateinit var dir: Path

    private val fakes = mutableListOf<Fake>()

    @AfterEach
    fun stop() {
        fakes.forEach { it.close() }
    }

    // spec: the fake substitutes <path> with its working directory
    @Test
    fun `the fake substitutes path with its working directory`() {
        val fake = start("ide-edit")
        val lines = handshake(fake).toMutableList()
        fake.prompt()
        lines += fake.readUntil({ answerAsHost(it) }) { it.type == "result" }
        fake.prompt()
        lines += fake.readUntil({ answerAsHost(it) }) { it.type == "result" }

        val real = dir.toRealPath().toString()
        val leftovers = lines.filter { "<path>" in it.toString() }
        assertTrue(leftovers.isEmpty(), "every frame names the directory: $leftovers")
        val inits = lines.filter { it.type == "system" && it.string("subtype") == "init" }
        assertEquals(listOf(real, real), inits.map { it.string("cwd") })
        val calls = lines.filter { it.rpcMethod() == "tools/call" }.map { it.rpc()["params"]!!.jsonObject["arguments"]!!.jsonObject.string("file_path") }
        assertEquals(listOf("$real/hello.txt", "$real/new.txt"), calls)
        val asked = lines.filter { it.subtype() == "can_use_tool" }.map { it.request()["input"]!!.jsonObject.string("file_path") }
        assertEquals(listOf("$real/hello.txt", "$real/new.txt"), asked)
    }

    // spec: interrupt withdraws an unanswered call
    @Test
    fun `interrupt withdraws an unanswered call`() {
        val fake = start("ide-edit-stop")
        handshake(fake)
        fake.prompt()
        val call = fake.readUntil({ answerAsHost(it) }) { it.rpcMethod() == "tools/call" }.last()
        fake.send("""{"type":"control_request","request_id":"stop-1","request":{"subtype":"interrupt"}}""")

        // Nothing more is sent: the five lines come without a reply to the call.
        val withdrawal = fake.readUntil({}) { it.type == "result" }.filter { it.type != "stream_event" }
        assertEquals(5, withdrawal.size, withdrawal.joinToString("\n"))
        val (cancelled, receipt, rejected, interrupted, result) = withdrawal
        assertEquals("notifications/cancelled", cancelled.rpcMethod())
        assertEquals(call.rpc()["id"], cancelled.rpc()["params"]!!.jsonObject["requestId"])
        assertEquals("control_response", receipt.type)
        val answered = receipt["response"]!!.jsonObject
        assertEquals("stop-1", answered.string("request_id"))
        assertEquals(Json.parseToJsonElement("""{"still_queued":[]}"""), answered["response"])
        val block = rejected.toolResult()
        assertEquals(JsonPrimitive(true), block["is_error"])
        assertEquals(call.toolUseId(), block.string("tool_use_id"))
        assertEquals("user-rejected", rejected["tool_result_meta"]!!.jsonArray.single().jsonObject.string("non_execution_kind"))
        assertEquals(
            "[Request interrupted by user for tool use]",
            interrupted["message"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject.string("text"),
        )
        assertEquals("error_during_execution", result.string("subtype"))
        assertFalse(result.toString().contains("<path>"))
    }

    // spec: a tools/call result carries the host's reply
    @Test
    fun `a tools call result carries the host's reply`() {
        val fake = start("ide-edit")
        handshake(fake)
        fake.prompt()
        val applied = "Applied here, by the host."
        val first = fake.readUntil({ frame -> answerAsHost(frame, reply(applied, isError = false)) }) { it.isToolResult() }.last()
        val firstBlock = first.toolResult()
        assertEquals(listOf(applied), firstBlock["content"]!!.jsonArray.map { it.jsonObject.string("text") })
        assertFalse("is_error" in firstBlock, "a success carries no is_error")
        assertEquals(firstBlock["content"], first["tool_use_result"])
        fake.readUntil({ answerAsHost(it) }) { it.type == "result" }

        fake.prompt()
        val refused = "Refused here, by the host."
        val second = fake.readUntil({ frame -> answerAsHost(frame, reply(refused, isError = true)) }) { it.isToolResult() }.last()
        val secondBlock = second.toolResult()
        assertEquals(JsonPrimitive(refused), secondBlock["content"])
        assertEquals(JsonPrimitive(true), secondBlock["is_error"])
        assertEquals(JsonPrimitive("Error: $refused"), second["tool_use_result"])
    }

    // spec 9.6: TANDEM_FAKE_EDIT_CANCEL_MS does the same without an interrupt (not a spec-named test)
    @Test
    fun `an unanswered call is withdrawn after TANDEM_FAKE_EDIT_CANCEL_MS without a receipt`() {
        val fake = start("ide-edit-stop", mapOf("TANDEM_FAKE_EDIT_CANCEL_MS" to "1"))
        handshake(fake)
        fake.prompt()
        fake.readUntil({ answerAsHost(it) }) { it.rpcMethod() == "tools/call" }
        val withdrawal = fake.readUntil({}) { it.type == "result" }.filter { it.type != "stream_event" }
        assertEquals(
            listOf("control_request", "user", "user", "result"),
            withdrawal.map { it.type },
            withdrawal.joinToString("\n"),
        )
        assertEquals("notifications/cancelled", withdrawal[0].rpcMethod())
    }

    // spec 9.7: the fake logs what the disk held when the host's reply to a call arrived (not a spec-named test)
    @Test
    fun `the reply to a call is followed in the log by the file's text on disk`() {
        val log = dir.resolve("sent.ndjson")
        dir.resolve("hello.txt").toFile().writeText("on disk at the reply\n")
        val fake = start("ide-edit", mapOf("TANDEM_FAKE_LOG" to log.toString()))
        handshake(fake)
        fake.prompt()
        val call = fake.readUntil({ answerAsHost(it) }) { it.rpcMethod() == "tools/call" }.last()
        answerAsHost(call)
        fake.readUntil({ answerAsHost(it) }) { it.type == "result" }

        val logged = log.toFile().readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        val reply = logged.indexOfFirst { it.type == "control_response" && it["response"]!!.jsonObject.string("request_id") == call.string("request_id") }
        assertTrue(reply >= 0, logged.joinToString("\n"))
        val disk = logged[reply + 1]
        assertEquals("__disk__", disk.type)
        assertEquals(call.toolUseId(), disk.string("tool_use_id"))
        assertEquals("on disk at the reply\n", disk.string("text"))
    }

    // spec 9.6: TANDEM_FAKE_RESULT_GATE_FILE holds a turn's result until the file exists (not a spec-named test)
    @Test
    fun `the result waits for TANDEM_FAKE_RESULT_GATE_FILE`() {
        val gate = dir.resolve("result.gate")
        val fake = start("plain-reply", mapOf("TANDEM_FAKE_RESULT_GATE_FILE" to gate.toString()))
        fake.prompt()
        fake.readUntil({}) { it.type == "system" && it.string("subtype") == "post_turn_summary" }
        assertEquals(null, fake.poll(1), "no result while the gate file is missing")
        gate.toFile().writeText("")
        fake.readUntil({}) { it.type == "result" }
    }

    private fun start(fixture: String, env: Map<String, String> = emptyMap()): Fake = Fake(fakeClaude, dir, fixture, env).also { fakes += it }

    /** What a host answers at once: every `mcp_message` with [toolReply] for a `tools/call`, every `can_use_tool` with allow. */
    private fun answerAsHost(frame: JsonObject, toolReply: (JsonElement?) -> JsonObject = reply("done", isError = false)) {
        if (frame.type != "control_request") return
        val requestId = frame.string("request_id")
        val answer = when (frame.subtype()) {
            "mcp_message" -> {
                val rpc = frame.rpc()
                val reply = when (rpc.string("method")) {
                    "tools/call" -> toolReply(rpc["id"])
                    else -> buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("result", JsonObject(emptyMap()))
                        put("id", rpc["id"] ?: JsonPrimitive(0))
                    }
                }
                buildJsonObject { put("mcp_response", reply) }
            }
            "can_use_tool" -> buildJsonObject {
                put("behavior", "allow")
                put("updatedInput", frame.request()["input"]!!)
            }
            else -> return
        }
        val response = buildJsonObject {
            put("type", "control_response")
            put(
                "response",
                buildJsonObject {
                    put("subtype", "success")
                    put("request_id", requestId)
                    put("response", answer)
                },
            )
        }
        fakes.last().send(response.toString())
    }

    /** The handshake before the first prompt, answered: up to the `tools/list`, the last of the CLI's three `mcp_message`s. */
    private fun handshake(fake: Fake): List<JsonObject> =
        fake.readUntil({ answerAsHost(it) }) { it.rpcMethod() == "tools/list" }.also { answerAsHost(it.last()) }

    private fun reply(text: String, isError: Boolean): (JsonElement?) -> JsonObject = { id ->
        buildJsonObject {
            put("jsonrpc", "2.0")
            put(
                "result",
                buildJsonObject {
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                    put("isError", isError)
                },
            )
            put("id", id ?: JsonPrimitive(0))
        }
    }

    /** The fake running [fixture] in [dir], its stdout read on a thread into a queue that [next] polls with a bound. */
    private class Fake(fakeClaude: String, dir: Path, fixture: String, env: Map<String, String>) : AutoCloseable {
        private val process: Process = ProcessBuilder(fakeClaude).directory(dir.toFile()).apply {
            environment()["TANDEM_FAKE_FIXTURE"] = fixture
            environment()["TANDEM_FAKE_SESSION_ID"] = "11111111-1111-4111-8111-111111111111"
            environment().remove("TANDEM_FAKE_FIXTURES")
            environment().remove("TANDEM_FAKE_LOG")
            environment().putAll(env)
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()
        private val stdin: BufferedWriter = process.outputStream.bufferedWriter()
        private val out = LinkedBlockingQueue<String>()

        init {
            thread(isDaemon = true, name = "fake-claude stdout") { process.inputStream.bufferedReader().forEachLine { out.put(it) } }
            send("""{"type":"control_request","request_id":"host-init","request":{"subtype":"initialize","sdkMcpServers":["tandem"]}}""")
        }

        fun send(frame: String) {
            stdin.write(frame)
            stdin.newLine()
            stdin.flush()
        }

        /** The next line within [seconds], or null when none came. */
        fun poll(seconds: Long): JsonObject? = out.poll(seconds, TimeUnit.SECONDS)?.let { Json.parseToJsonElement(it).jsonObject }

        fun next(): JsonObject {
            val line = assertNotNull(out.poll(10, TimeUnit.SECONDS), "the fake wrote nothing within 10 s")
            return Json.parseToJsonElement(line).jsonObject
        }

        /** Reads lines, handing each to [onLine], until one passes [done]; returns them all, that one last. Fails after 200 lines. */
        fun readUntil(onLine: (JsonObject) -> Unit, done: (JsonObject) -> Boolean): List<JsonObject> {
            val lines = mutableListOf<JsonObject>()
            repeat(MAX_LINES) {
                val line = next()
                lines += line
                if (done(line)) return lines
                onLine(line)
            }
            fail("no matching line in $MAX_LINES lines")
        }

        fun prompt() {
            send("""{"type":"user","uuid":"${java.util.UUID.randomUUID()}","message":{"role":"user","content":[{"type":"text","text":"go"}]}}""")
        }

        override fun close() {
            runCatching { stdin.close() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }

        private companion object {
            const val MAX_LINES = 200
        }
    }
}

private val JsonObject.type: String? get() = (this["type"] as? JsonPrimitive)?.content

private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

private fun JsonObject.request(): JsonObject = this["request"]?.jsonObject ?: JsonObject(emptyMap())

private fun JsonObject.subtype(): String? = (request()["subtype"] as? JsonPrimitive)?.content

private fun JsonObject.rpc(): JsonObject = request()["message"]?.jsonObject ?: JsonObject(emptyMap())

private fun JsonObject.rpcMethod(): String? = if (subtype() == "mcp_message") (rpc()["method"] as? JsonPrimitive)?.content else null

private fun JsonObject.toolUseId(): String = rpc()["params"]!!.jsonObject["_meta"]!!.jsonObject.string("claudecode/toolUseId")

/** A `user` line carrying a tool's result, not a `ToolSearch` answer (a `tool_reference`). */
private fun JsonObject.isToolResult(): Boolean {
    if (type != "user") return false
    val block = (this["message"]?.jsonObject?.get("content") as? JsonArray)?.map { it.jsonObject }?.singleOrNull { it.type == "tool_result" } ?: return false
    return (block["content"] as? JsonArray)?.none { (it as? JsonObject)?.type == "tool_reference" } ?: true
}

private fun JsonObject.toolResult(): JsonObject =
    this["message"]!!.jsonObject["content"]!!.jsonArray.map { it.jsonObject }.single { it.type == "tool_result" }

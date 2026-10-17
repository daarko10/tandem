package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixtureDecodeTest {
    @Test
    fun `every fixture line decodes and core types are never Unknown`() {
        val core = setOf("assistant", "user", "result", "stream_event", "control_request", "control_response")
        for (name in Fixtures.names()) {
            for (line in Fixtures.lines(name)) {
                val message = Ndjson.decode(line)
                if (message is CliMessage.Unknown) {
                    assertTrue(message.type !in core, "$name: core type decoded as Unknown: ${message.type}")
                }
            }
        }
    }

    @Test
    fun `plain reply has init, streamed text, complete assistant before content_block_stop, and a result`() {
        val messages = Fixtures.decode("plain-reply")

        val init = messages.filterIsInstance<CliMessage.SystemInit>().single()
        assertEquals("claude-sonnet-5-5", init.model)
        assertEquals("2.1.300", init.claudeCodeVersion)

        val deltas = messages.filterIsInstance<CliMessage.StreamEvent>().map { it.event }
            .filterIsInstance<StreamEvent.ContentBlockDelta>().map { it.delta }
        assertEquals(listOf<Delta>(Delta.Text("pong")), deltas)

        val assistantIndex = messages.indexOfFirst { it is CliMessage.Assistant }
        val stopIndex = messages.indexOfFirst { it is CliMessage.StreamEvent && it.event is StreamEvent.ContentBlockStop }
        assertTrue(assistantIndex in 0 until stopIndex, "the fixture shows the complete message before content_block_stop")

        val assistant = messages[assistantIndex] as CliMessage.Assistant
        assertEquals(listOf<ContentBlock>(ContentBlock.Text("pong")), assistant.content)
        val start = messages.firstNotNullOf { (it as? CliMessage.StreamEvent)?.event as? StreamEvent.MessageStart }
        assertEquals(start.messageId, assistant.messageId)

        val result = messages.filterIsInstance<CliMessage.Result>().single()
        assertEquals("success", result.subtype)
        assertEquals(false, result.isError)
        assertEquals("pong", result.result)
    }

    @Test
    fun `stream frames carry the api message id of their message_start`() {
        val events = Fixtures.decode("plain-reply").filterIsInstance<CliMessage.StreamEvent>()
        val start = events.firstNotNullOf { it.event as? StreamEvent.MessageStart }
        assertTrue(events.all { it.apiMessageId == start.messageId })
    }

    @Test
    fun `permission fixture asks for each Bash call and the answers show up as tool results`() {
        val messages = Fixtures.decode("permission")

        val asks = messages.filterIsInstance<CliMessage.ControlRequest>().map { it.request }
        assertEquals(2, asks.size)
        val toolUses = messages.filterIsInstance<CliMessage.Assistant>().flatMap { it.content }.filterIsInstance<ContentBlock.ToolUse>()
        asks.zip(toolUses).forEach { (ask, toolUse) ->
            val canUseTool = assertIs<InboundRequest.CanUseTool>(ask)
            assertEquals("Bash", canUseTool.toolName)
            assertEquals(toolUse.id, canUseTool.toolUseId)
            assertEquals(toolUse.input, canUseTool.input)
            assertTrue(canUseTool.permissionSuggestions.isNotEmpty())
        }

        val results = messages.filterIsInstance<CliMessage.User>().flatMap { it.content }.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(listOf(false, true), results.map { it.isError })
    }

    @Test
    fun `interrupt fixture acknowledges the interrupt and ends with an error result`() {
        val messages = Fixtures.decode("interrupt")

        val receipt = messages.filterIsInstance<CliMessage.ControlResponse>().last()
        assertTrue(receipt.isSuccess)
        assertTrue(receipt.payload?.containsKey("still_queued") == true)

        val result = messages.filterIsInstance<CliMessage.Result>().single()
        assertEquals("error_during_execution", result.subtype)
        assertTrue(result.isError)
        assertEquals("aborted_streaming", result.terminalReason)
    }

    @Test
    fun `model switch fixture reports the new model in the next init and assistant message`() {
        val messages = Fixtures.decode("model-switch")

        assertEquals(listOf("claude-sonnet-5-5", "claude-haiku-5-5"), messages.filterIsInstance<CliMessage.SystemInit>().map { it.model })
        assertEquals(listOf("claude-sonnet-5-5", "claude-haiku-5-5"), messages.filterIsInstance<CliMessage.Assistant>().map { it.model })

        val setModelAck = messages.filterIsInstance<CliMessage.ControlResponse>().last()
        assertTrue(setModelAck.isSuccess)
        assertNull(setModelAck.payload, "set_model succeeds without a payload")
    }

    @Test
    fun `permission mode fixture shows how the CLI acknowledges set_permission_mode`() {
        val messages = Fixtures.decode("permission-mode")

        val responses = messages.filterIsInstance<CliMessage.ControlResponse>()
        assertEquals(3, responses.count { it.isSuccess }) // initialize + two acks
        assertEquals(
            listOf(PermissionMode.DEFAULT, PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS),
            messages.filterIsInstance<CliMessage.SystemInit>().map { it.permissionMode },
        )

        // Each ack echoes the mode, and a system/status line with that mode comes right after it.
        val acks = responses.drop(1)
        assertEquals(listOf("plan", "acceptEdits").map { mode -> buildJsonObject { put("mode", mode) } }, acks.map { it.payload })
        for ((ack, mode) in acks.zip(listOf(PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS))) {
            val status = assertIs<CliMessage.SystemStatus>(messages[messages.indexOf(ack) + 1])
            assertEquals(mode, status.permissionMode)
        }
    }

    @Test
    fun `fork fixture resumes under a new session id`() {
        val ids = Fixtures.decode("fork").filterIsInstance<CliMessage.SystemInit>().map { it.sessionId }

        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `transcript fixtures decode line by line without throwing`() {
        for (name in listOf("history-reply", "history-permission", "history-interrupt", "fork", "fork-origin")) {
            val lines = Fixtures.transcript(name).readLines().filter { it.isNotBlank() }
            assertTrue(lines.isNotEmpty(), "$name: empty transcript")
            lines.forEach(Ndjson::decode)
        }
    }

    @Test
    fun `the original session's transcript keeps the title the host's rename_session gave it`() {
        val titles = Fixtures.transcript("fork-origin").readLines().filter { it.isNotBlank() }.map(Ndjson::decode)
            .filterIsInstance<CliMessage.Unknown>().filter { it.type == "custom-title" }
            .map { it.raw?.get("customTitle")?.jsonPrimitive?.content }
        assertEquals(listOf("Renamed by host"), titles)
    }

    @Test
    fun `undocumented types the fixtures show decode to Unknown with their type`() {
        val unknownTypes = Fixtures.names().flatMap(Fixtures::decode).filterIsInstance<CliMessage.Unknown>().map { it.type }.toSet()
        assertTrue("autocompact_state" in unknownTypes)
        assertTrue("command_lifecycle" in unknownTypes)
    }
}

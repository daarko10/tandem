package dev.daarkolabs.tandem.protocol

import org.junit.Test
import kotlin.io.path.readLines
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The CLI is an external process that changes weekly: decoding must never throw. */
class RobustnessTest {
    @Test
    fun `garbage and non-object lines decode to Unknown`() {
        for (line in listOf("", "not json", "{", "[]", "42", "null", "\"text\"", "{\"type\":7}", "{\"no_type\":true}")) {
            val message = Ndjson.decode(line)
            assertIs<CliMessage.Unknown>(message, line)
            assertEquals(line, message.line)
        }
    }

    @Test
    fun `unknown types and subtypes decode to Unknown`() {
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"brand_new","x":1}"""))
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"system","subtype":"brand_new"}"""))
        val request = Ndjson.decode("""{"type":"control_request","request_id":"r1","request":{"subtype":"brand_new"}}""")
        assertIs<InboundRequest.Other>(assertIs<CliMessage.ControlRequest>(request).request)
    }

    @Test
    fun `known types with missing or mistyped fields still decode`() {
        val assistant = Ndjson.decode("""{"type":"assistant","message":{"content":[{"type":"text"},{"type":"mystery"},7]}}""")
        assertIs<CliMessage.Assistant>(assistant)
        assertEquals(listOf("", "mystery"), assistant.content.map { (it as? ContentBlock.Text)?.text ?: (it as ContentBlock.Other).type })

        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"system","subtype":"init","model":42}"""))
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"control_request","request":{"subtype":"interrupt"}}"""))
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"stream_event"}"""))
        val delta = Ndjson.decode("""{"type":"stream_event","event":{"type":"content_block_delta","index":"x","delta":{"type":"new_delta"}}}""")
        assertIs<StreamEvent.Other>(assertIs<CliMessage.StreamEvent>(delta).event)
    }

    @Test
    fun `new optional fields read as null or false when absent or mistyped`() {
        val user = assertIs<CliMessage.User>(
            Ndjson.decode(
                """{"type":"user","message":{"content":[]},"tool_use_result":null,"tool_result_meta":[{"permission_decision":{}},""" +
                    """{"id":7},5,{"id":"t1","permission_decision":"x","non_execution_kind":3}],"agent_id":5,"subagent_type":[],""" +
                    """"task_description":{},"timestamp":"yesterday"}""",
            ),
        )
        assertNull(user.toolUseResult)
        assertEquals(listOf(ToolResultMeta("t1", decision = null, source = null, reasonType = null, nonExecutionKind = null)), user.toolResultMeta)
        assertEquals(listOf(null, null, null, null), listOf(user.agentId, user.subagentType, user.taskDescription, user.timestampMillis))

        // On disk, one permissionDecision applies to every tool_result block of the line; a line without one has no meta.
        val disk = assertIs<CliMessage.User>(
            Ndjson.decode(
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"a"},{"type":"text","text":"x"},""" +
                    """{"type":"tool_result","tool_use_id":"b"}]},"permissionDecision":{"decision":"accept","reasonType":"mode"}}""",
            ),
        )
        assertEquals(listOf("a", "b"), disk.toolResultMeta.map { it.id })
        assertTrue(disk.toolResultMeta.all { it.decision == "accept" && it.reasonType == "mode" && it.nonExecutionKind == null })
        val plain = assertIs<CliMessage.User>(Ndjson.decode("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"a"}]}}"""))
        assertTrue(plain.toolResultMeta.isEmpty())

        val assistant = assertIs<CliMessage.Assistant>(
            Ndjson.decode("""{"type":"assistant","message":{"content":[]},"api_error_status":"404","thinking_duration_ms":"1","agent_id":1}"""),
        )
        assertEquals(listOf(null, null, null), listOf(assistant.apiErrorStatus, assistant.thinkingDurationMs, assistant.agentId))

        val ask = assertIs<InboundRequest.CanUseTool>(
            assertIs<CliMessage.ControlRequest>(
                Ndjson.decode(
                    """{"type":"control_request","request_id":"r","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{},""" +
                        """"requires_user_interaction":"yes","decision_reason_type":1}}""",
                ),
            ).request,
        )
        assertEquals(false, ask.requiresUserInteraction)
        assertNull(ask.decisionReasonType)

        val started = assertIs<CliMessage.TaskStarted>(Ndjson.decode("""{"type":"system","subtype":"task_started","task_id":"t","is_backgrounded":"no"}"""))
        assertEquals(false, started.isBackgrounded)
        val progress = assertIs<CliMessage.TaskProgress>(Ndjson.decode("""{"type":"system","subtype":"task_progress","task_id":"t","usage":{"tool_uses":"1"}}"""))
        assertEquals(TaskUsage(totalTokens = null, toolUses = null, durationMs = null), progress.usage)
        assertNull(assertIs<CliMessage.TaskUpdated>(Ndjson.decode("""{"type":"system","subtype":"task_updated","task_id":"t","patch":7}""")).status)
        for (subtype in listOf("task_started", "task_progress", "task_updated", "task_notification")) {
            assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"system","subtype":"$subtype","task_id":3}"""), subtype)
        }
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"system","subtype":"thinking_tokens","estimated_tokens":"1"}"""))

        val start = Ndjson.decode("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m","usage":{"cache_read_input_tokens":5}}}}""")
        assertEquals(StreamEvent.MessageStart("m", model = null, thinkingDisplay = null, inputTokens = 5L), assertIs<CliMessage.StreamEvent>(start).event)
        val bare = Ndjson.decode("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m"}},"thinking_display":3}""")
        assertEquals(StreamEvent.MessageStart("m", model = null, thinkingDisplay = null, inputTokens = null), assertIs<CliMessage.StreamEvent>(bare).event)

        val result = assertIs<CliMessage.Result>(
            Ndjson.decode("""{"type":"result","subtype":"success","permission_denials":[{"tool_use_id":"x"},5,{"tool_name":"Bash"}],"usage":7}"""),
        )
        assertEquals(listOf(PermissionDenial("Bash", toolUseId = null)), result.permissionDenials)
        assertNull(result.outputTokens)
    }

    @Test
    fun `truncated and mutated fixture lines never throw`() {
        val random = Random(42)
        val transcripts = Fixtures.transcriptNames()
        assertTrue(transcripts.isNotEmpty(), "no transcript fixtures")
        val lines = Fixtures.names().flatMap(Fixtures::lines) +
            transcripts.flatMap { name -> Fixtures.transcript(name).readLines().filter { it.isNotBlank() } }
        for (line in lines) {
            Ndjson.decode(line)
            repeat(20) {
                val cut = line.substring(0, random.nextInt(line.length + 1))
                Ndjson.decode(cut)
                val chars = line.toCharArray()
                chars[random.nextInt(chars.size)] = "{}[]\",:0an".random(random)
                Ndjson.decode(String(chars))
            }
        }
    }
}

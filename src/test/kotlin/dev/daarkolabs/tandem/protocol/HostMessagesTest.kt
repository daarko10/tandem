package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exact wire shapes from docs/protocol.md sections 3 and 4. */
class HostMessagesTest {
    private fun wire(message: kotlinx.serialization.json.JsonObject): String = Ndjson.encode(message).trimEnd('\n')

    @Test
    fun `encode ends every frame with a single newline`() {
        val line = Ndjson.encode(HostMessages.interrupt("a1"))
        assertTrue(line.endsWith("\n") && line.count { it == '\n' } == 1)
    }

    @Test
    fun `user text message`() {
        assertEquals(
            """{"type":"user","session_id":"","message":{"role":"user","content":[{"type":"text","text":"ping"}]},""" +
                """"parent_tool_use_id":null,"uuid":"u-1"}""",
            wire(HostMessages.userMessage("ping", emptyList(), uuid = "u-1")),
        )
    }

    @Test
    fun `user message with text and an image block`() {
        assertEquals(
            """{"type":"user","session_id":"","message":{"role":"user","content":[{"type":"text","text":"What is in this screenshot?"},""" +
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0"}}]},"parent_tool_use_id":null,"uuid":"u-2"}""",
            wire(HostMessages.userMessage("What is in this screenshot?", listOf(ImageBlock("image/png", "iVBORw0")), "u-2")),
        )
    }

    @Test
    fun `user message with images only has no text block`() {
        val message = HostMessages.userMessage("  ", listOf(ImageBlock("image/jpeg", "/9j/")), "u-3")
        val content = message.getValue("message").jsonObject.getValue("content").jsonArray
        assertEquals(
            listOf("""{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"/9j/"}}"""),
            content.map { it.toString() },
        )
    }

    @Test
    fun `initialize forwards subagent text`() {
        assertEquals(
            """{"type":"control_request","request_id":"a0","request":{"subtype":"initialize","hooks":null,"forwardSubagentText":true}}""",
            wire(HostMessages.initialize("a0")),
        )
    }

    @Test
    fun `control requests`() {
        assertEquals("""{"type":"control_request","request_id":"a1","request":{"subtype":"interrupt"}}""", wire(HostMessages.interrupt("a1")))
        assertEquals(
            """{"type":"control_request","request_id":"a2","request":{"subtype":"set_model","model":"claude-opus-5-5"}}""",
            wire(HostMessages.setModel("a2", "claude-opus-5-5")),
        )
        assertEquals(
            """{"type":"control_request","request_id":"a3","request":{"subtype":"set_model","model":null}}""",
            wire(HostMessages.setModel("a3", null)),
        )
        assertEquals(
            """{"type":"control_request","request_id":"a4","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}""",
            wire(HostMessages.setPermissionMode("a4", PermissionMode.ACCEPT_EDITS)),
        )
        assertEquals(
            """{"type":"control_request","request_id":"a5","request":{"subtype":"rename_session","title":"Alpha","source":"host"}}""",
            wire(HostMessages.renameSession("a5", "Alpha")),
        )
    }

    @Test
    fun `apply_flag_settings carries the effort level`() {
        assertEquals(
            """{"type":"control_request","request_id":"a6","request":{"subtype":"apply_flag_settings","settings":{"effortLevel":"high"}}}""",
            wire(HostMessages.applyFlagSettings("a6", "high")),
        )
    }

    @Test
    fun `the parity requests match the probe frames`() {
        assertEquals("""{"type":"control_request","request_id":"c1","request":{"subtype":"get_context_usage"}}""", wire(HostMessages.getContextUsage("c1")))
        assertEquals(
            """{"type":"control_request","request_id":"u1","request":{"subtype":"get_usage","skip_behaviors":true}}""",
            wire(HostMessages.getUsage("u1", skipBehaviors = true)),
        )
        assertEquals(
            """{"type":"control_request","request_id":"u2","request":{"subtype":"get_usage"}}""",
            wire(HostMessages.getUsage("u2", skipBehaviors = false)),
        )
    }

    @Test
    fun `the four mcp requests match the probe frames`() {
        assertEquals("""{"type":"control_request","request_id":"m1","request":{"subtype":"mcp_status"}}""", wire(HostMessages.mcpStatus("m1")))
        assertEquals(
            """{"type":"control_request","request_id":"m2","request":{"subtype":"mcp_toggle","serverName":"echo","enabled":false}}""",
            wire(HostMessages.mcpToggle("m2", "echo", enabled = false)),
        )
        assertEquals(
            """{"type":"control_request","request_id":"m3","request":{"subtype":"mcp_reconnect","serverName":"broken"}}""",
            wire(HostMessages.mcpReconnect("m3", "broken")),
        )
        assertEquals(
            """{"type":"control_request","request_id":"m4","request":{"subtype":"mcp_authenticate","serverName":"authy"}}""",
            wire(HostMessages.mcpAuthenticate("m4", "authy")),
        )
    }

    @Test
    fun `can_use_tool answers`() {
        val input = buildJsonObject { put("command", "ls") }
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"p1","response":""" +
                """{"behavior":"allow","updatedInput":{"command":"ls"},"toolUseID":"t1"}}}""",
            wire(HostMessages.allow("p1", toolUseId = "t1", updatedInput = input)),
        )
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"p2","response":""" +
                """{"behavior":"deny","message":"No","interrupt":true,"toolUseID":"t2"}}}""",
            wire(HostMessages.deny("p2", toolUseId = "t2", message = "No", interrupt = true)),
        )
        assertEquals(
            """{"type":"control_response","response":{"subtype":"error","request_id":"p3","error":"Unsupported request"}}""",
            wire(HostMessages.error("p3", "Unsupported request")),
        )
    }

    @Test
    fun `encoded host messages decode back as the CLI would see them`() {
        val allow = HostMessages.allow("p1", toolUseId = "t1", updatedInput = buildJsonObject { put("x", JsonPrimitive(1)) })
        val decoded = Ndjson.decode(Ndjson.encode(allow))
        assertEquals("p1", (decoded as CliMessage.ControlResponse).requestId)
    }

    @Test
    fun `permission modes use the CLI wire names`() {
        assertEquals(
            listOf("default", "acceptEdits", "plan", "bypassPermissions", "dontAsk", "auto"),
            PermissionMode.entries.map { it.wire },
        )
        assertEquals(PermissionMode.PLAN, PermissionMode.fromWire("plan"))
        assertEquals(null, PermissionMode.fromWire("nope"))
    }

    @Test
    fun `the bash escape frames match the probe's`() {
        // Bash probes 1, frames 1 and 2 (uuid replaced): compared as parsed objects, key order is not significant.
        val input = """{"type":"user","session_id":"","parent_tool_use_id":null,"uuid":"u1","message":{"role":"user",""" +
            """"content":"<bash-input>echo bash-mode-probe && printf err >&2</bash-input>"},"client_composed":true,"shouldQuery":false}"""
        val output = """{"type":"user","session_id":"","parent_tool_use_id":null,"uuid":"u2","message":{"role":"user",""" +
            """"content":"<bash-stdout>bash-mode-probe\nerr</bash-stdout><bash-stderr></bash-stderr>"},"client_composed":true,"shouldQuery":false}"""
        assertEquals(Json.parseToJsonElement(input), HostMessages.bashInput("echo bash-mode-probe && printf err >&2", "u1"))
        assertEquals(Json.parseToJsonElement(output), HostMessages.bashOutput("bash-mode-probe\nerr", "", "u2", query = false))

        val respond = HostMessages.bashOutput("bash-mode-probe\nerr", "", "u2", query = true)
        assertFalse("shouldQuery" in respond)
        assertEquals(JsonPrimitive(true), respond["client_composed"])

        val recorded = Json.parseToJsonElement(Fixtures.transcript("bash-mode").readLines()[4]).jsonObject
        val context = HostMessages.bashContext("echo bash-mode-probe && printf err >&2", "bash-mode-probe\nerr", "", "u3")
        assertEquals(recorded["message"]!!.jsonObject["content"], context["message"]!!.jsonObject["content"])
        // The one frame Tandem sends with replies off, as bash-mode-context recorded it (2.1.308).
        val sent = Json.parseToJsonElement(Fixtures.transcript("bash-mode-context").readLines()[2]).jsonObject
        assertEquals(sent["message"]!!.jsonObject["content"], context["message"]!!.jsonObject["content"])
        assertEquals(JsonPrimitive(false), context["shouldQuery"])
        assertEquals(JsonPrimitive("u3"), context["uuid"])
    }

    @Test
    fun `queue frames`() {
        val plain = """{"type":"user","session_id":"","message":{"role":"user","content":[{"type":"text","text":"ping"}]},""" +
            """"parent_tool_use_id":null,"uuid":"u-1"}"""
        assertEquals(plain, wire(HostMessages.userMessage("ping", emptyList(), uuid = "u-1")))
        assertEquals(
            plain.dropLast(1) + ""","priority":"later"}""",
            wire(HostMessages.userMessage("ping", emptyList(), uuid = "u-1", later = true)),
        )
        assertEquals("""{"type":"control_request","request_id":"a1","request":{"subtype":"interrupt"}}""", wire(HostMessages.interrupt("a1")))
        assertEquals(
            """{"type":"control_request","request_id":"a1","request":{"subtype":"interrupt","cancel_queued":true}}""",
            wire(HostMessages.interrupt("a1", cancelQueued = true)),
        )
        assertEquals(
            """{"type":"control_request","request_id":"c1","request":{"subtype":"cancel_async_message","message_uuid":"u-1"}}""",
            wire(HostMessages.cancelAsyncMessage("c1", "u-1")),
        )
    }
}

package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
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
            wire(HostMessages.userText("ping", uuid = "u-1")),
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
}

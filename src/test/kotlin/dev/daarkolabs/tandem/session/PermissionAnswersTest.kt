package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.HostMessages
import dev.daarkolabs.tandem.protocol.Ndjson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The answers the chat's cards send ([PermissionAnswers]), from the requests the fixtures recorded. */
class PermissionAnswersTest {
    /** The `can_use_tool` request on line [line] (1-based) of fixture [name], as the reducer makes it a pending permission. */
    private fun request(name: String, line: Int): PendingPermission =
        reduce(ChatState(), SessionEvent.Cli(Ndjson.decode(Fixtures.lines(name)[line - 1]))).pendingPermissions.single()

    /** The request's `input` exactly as the fixture line has it. */
    private fun recordedInput(name: String, line: Int): Map<String, JsonElement> =
        Json.parseToJsonElement(Fixtures.lines(name)[line - 1]).jsonObject["request"]!!.jsonObject["input"]!!.jsonObject

    @Test
    fun `questionAnswers keeps the questions and adds the answers`() {
        val p = request("ask-single", 22)
        val answer = PermissionAnswers.questionAnswers(p, mapOf("Which colour?" to "Blue"))
        val expected = buildJsonObject {
            put("questions", recordedInput("ask-single", 22).getValue("questions"))
            putJsonObject("answers") { put("Which colour?", "Blue") }
        }
        assertEquals(expected, answer.updatedInput)
        assertTrue(answer.updatedPermissions.isEmpty())
        // The frame of spec 5.2, key order included.
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-27>","response":{"behavior":"allow",""" +
                """"updatedInput":{"questions":[{"question":"Which colour?","header":"Colour","options":[{"label":"Red","description":"Red"},""" +
                """{"label":"Blue","description":"Blue"}],"multiSelect":false}],"answers":{"Which colour?":"Blue"}},"toolUseID":"<id-12>"}}}""" + "\n",
            Ndjson.encode(HostMessages.allow(p.requestId, p.toolUseId, answer.updatedInput, answer.updatedPermissions)),
        )
    }

    @Test
    fun `approvePlan adds setMode only when starting to code`() {
        val p = request("plan-approve", 88)
        val coding = PermissionAnswers.approvePlan(p, startCoding = true)
        val setMode = buildJsonObject {
            put("type", "setMode")
            put("mode", "acceptEdits")
            put("destination", "session")
        }
        assertEquals(listOf(setMode), coding.updatedPermissions)
        assertSame(p.input, coding.updatedInput)
        val planning = PermissionAnswers.approvePlan(p, startCoding = false)
        assertTrue(planning.updatedPermissions.isEmpty())
        assertSame(p.input, planning.updatedInput)
    }

    @Test
    fun `keepPlanning sends the feedback verbatim and falls back when blank`() {
        assertEquals(PermissionAnswer.Deny("also add a date"), PermissionAnswers.keepPlanning("also add a date"))
        assertEquals(PermissionAnswer.Deny("No, keep planning"), PermissionAnswers.keepPlanning("  "))
    }

    @Test
    fun `allowForSession echoes addRules only`() {
        val p = request("misc-state-action", 21)
        assertEquals(listOf("addRules", "addDirectories", "setMode"), p.suggestions.map { it["type"]!!.jsonPrimitive.content })
        val answer = PermissionAnswers.allowForSession(p)
        val update = answer.updatedPermissions.single()
        assertEquals("addRules", update["type"]!!.jsonPrimitive.content)
        assertEquals("session", update["destination"]!!.jsonPrimitive.content)
        assertSame(p.input, answer.updatedInput)
    }

    @Test
    fun `the fixed answers`() {
        val p = request("misc-state-action", 21)
        assertEquals(PermissionAnswer.Allow(p.input), PermissionAnswers.allowOnce(p))
        assertEquals(PermissionAnswer.Deny("The user denied this request."), PermissionAnswers.deny())
        assertEquals(PermissionAnswer.Deny("cancelled"), PermissionAnswers.cancelQuestion())
    }
}

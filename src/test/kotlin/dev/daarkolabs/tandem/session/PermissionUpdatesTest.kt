package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/** "Always allow for this session" echoes only the `addRules` suggestions ([sessionPermissionUpdates]). */
class PermissionUpdatesTest {
    private val tmp: Path = File(System.getProperty("java.io.tmpdir")).toPath()
    private val newSessionId = "11111111-1111-4111-8111-111111111111"
    private val fakeClaude: String = System.getProperty("tandem.claudePath") ?: "src/test/resources/fake-claude.py"

    /** The suggestions of misc-state-action.ndjson:21, one of each kind: addRules, addDirectories and setMode. */
    private val threeKinds: List<JsonObject> = Json.parseToJsonElement(Fixtures.lines("misc-state-action")[20])
        .jsonObject["request"]!!.jsonObject["permission_suggestions"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `keeps only the addRules entry, for this session`() {
        assertEquals(listOf("addRules", "addDirectories", "setMode"), threeKinds.map { it["type"]!!.jsonPrimitive.content })
        val update = sessionPermissionUpdates(threeKinds).single()
        assertEquals("addRules", update["type"]!!.jsonPrimitive.content)
        assertEquals("session", update["destination"]!!.jsonPrimitive.content)
        assertEquals(threeKinds[0]["rules"], update["rules"])
        assertEquals(threeKinds[0]["behavior"], update["behavior"])
    }

    @Test
    fun `an empty list yields an empty list`() {
        assertEquals(emptyList(), sessionPermissionUpdates(emptyList()))
    }

    @Test
    fun `unknown kinds and entries without a type are dropped`() {
        val unknown = buildJsonObject { put("type", "somethingNew"); put("destination", "session") }
        val untyped = buildJsonObject { put("destination", "session") }
        val numeric = buildJsonObject { put("type", 3) }
        assertEquals(emptyList(), sessionPermissionUpdates(listOf(unknown, untyped, numeric)))
    }

    @Test
    fun `allowing for the session writes no setMode or addDirectories entry`() = runBlocking {
        val log = File.createTempFile("tandem-fake", ".ndjson").apply { deleteOnExit() }
        val launcher = DefaultProcessLauncher { System.getenv() + mapOf("TANDEM_FAKE_LOG" to log.path, "TANDEM_FAKE_FIXTURE" to "permission") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig(fakeClaude, tmp, sessionId = newSessionId), launcher)
        session.start()
        session.sendUserMessage("Create the fixture file")
        val pending = withTimeout(10_000) { session.state.first { it.pendingPermissions.isNotEmpty() } }.pendingPermissions.single()
        session.allow(pending, always = true)
        val response = withTimeout(10_000) {
            var found: JsonObject? = null
            while (found == null) {
                delay(20)
                found = frames(log).firstOrNull { it["type"]?.jsonPrimitive?.content == "control_response" }
            }
            found
        }
        session.close()

        val body = response["response"]!!.jsonObject["response"]!!.jsonObject
        assertEquals("allow", body["behavior"]!!.jsonPrimitive.content)
        val updated = body["updatedPermissions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("addRules"), updated.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("session", updated.single()["destination"]!!.jsonPrimitive.content)
    }

    /** The complete lines of the fake's log; the last piece is empty, or a line the fake is still writing. */
    private fun frames(log: File): List<JsonObject> =
        log.readText().split('\n').dropLast(1).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
}

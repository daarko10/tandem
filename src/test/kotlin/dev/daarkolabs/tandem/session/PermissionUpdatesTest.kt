package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals

/** "Always allow for this session" echoes only the `addRules` suggestions ([sessionPermissionUpdates]). */
class PermissionUpdatesTest {
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
}

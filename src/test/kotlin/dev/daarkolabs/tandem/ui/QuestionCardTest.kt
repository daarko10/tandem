package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.Cards
import dev.daarkolabs.tandem.chat.PermissionCard
import dev.daarkolabs.tandem.ui.cards.permissionTitle
import dev.daarkolabs.tandem.ui.cards.picksOf
import dev.daarkolabs.tandem.ui.cards.tabLabel
import dev.daarkolabs.tandem.ui.cards.togglePick
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the question card picks, sends and shows (spec 4.13). */
class QuestionCardTest {
    /** Which colour? Red, Blue. */
    private val single = Cards.question(pendingAt("ask-single", 22))!!

    /** Which toppings would you like? Cheese, Olives, Ham; multi-select. */
    private val multi = Cards.question(pendingAt("ask-multi", 27))!!

    private val two = single.copy(questions = (single.questions + multi.questions).toImmutableList())

    @Test
    fun `picks are complete only when every question has an answer`() {
        assertNull(picksOf(two, emptyMap(), emptyMap()))
        // A blank Other is no answer.
        assertNull(picksOf(two, mapOf(0 to setOf(1)), mapOf(1 to "  ")))
        // An empty multi-select pick is no answer.
        assertNull(picksOf(two, mapOf(0 to setOf(1), 1 to emptySet()), emptyMap()))
        assertEquals(
            mapOf("Which colour?" to "Blue", "Which toppings would you like?" to "Cheese, Ham"),
            Cards.answers(two, picksOf(two, mapOf(0 to setOf(1), 1 to setOf(2, 0)), emptyMap())!!),
        )
        // Other wins over a pick.
        assertEquals(
            mapOf("Which colour?" to "Green"),
            Cards.answers(single, picksOf(single, mapOf(0 to setOf(0)), mapOf(0 to " Green "))!!),
        )
    }

    @Test
    fun `a digit past the last option changes nothing`() {
        val colour = single.questions[0]
        val toppings = multi.questions[0]
        assertEquals(setOf(1), togglePick(colour, setOf(1), 4))
        assertEquals(setOf(0), togglePick(colour, setOf(1), 0))
        assertEquals(setOf(0, 2), togglePick(toppings, setOf(0), 2))
        assertEquals(setOf(2), togglePick(toppings, setOf(0, 2), 0))
        // The Other row's index (digit 3 on a two-option question) never changes the option picks.
        assertEquals(setOf(1), togglePick(colour, setOf(1), 2))
    }

    @Test
    fun `an unanswered tab carries the marker`() {
        assertEquals("Colour ·", tabLabel("Colour", false))
        assertEquals("Colour", tabLabel("Colour", true))
    }

    @Test
    fun `a malformed question input renders the generic permission card`() {
        val state = stateAt("ask-single", 22)
        val p = state.pendingPermissions.single()
        for (input in listOf(
            JsonObject(p.input - "questions"),
            buildJsonObject { put("questions", "nope") },
            buildJsonObject { putJsonArray("questions") {} },
        )) {
            val card = Cards.pending(state.copy(pendingPermissions = listOf(p.copy(input = input)))).first
            assertTrue(card is PermissionCard, input.toString())
            assertEquals("Allow Claude to Use AskUserQuestion?", permissionTitle(card))
        }
    }
}

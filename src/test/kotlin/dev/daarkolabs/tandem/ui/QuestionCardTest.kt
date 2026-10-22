package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.Cards
import dev.daarkolabs.tandem.chat.Option
import dev.daarkolabs.tandem.chat.PermissionCard
import dev.daarkolabs.tandem.chat.Question
import dev.daarkolabs.tandem.ui.cards.QuestionPrimary.Next
import dev.daarkolabs.tandem.ui.cards.QuestionPrimary.Submit
import dev.daarkolabs.tandem.ui.cards.permissionTitle
import dev.daarkolabs.tandem.ui.cards.picksOf
import dev.daarkolabs.tandem.ui.cards.questionPrimary
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
    fun `Next goes to the next unanswered question after the shown one and wraps`() {
        assertEquals(Next(1), questionPrimary(listOf(false, false), 0))
        assertEquals(Next(1), questionPrimary(listOf(true, false), 0))
        assertEquals(Next(2), questionPrimary(listOf(false, false, false), 1))
        assertEquals(Next(0), questionPrimary(listOf(false, true, false), 2))
        assertEquals(Next(0), questionPrimary(listOf(false, true, true), 2))
        // Skips an answered question forward.
        assertEquals(Next(2), questionPrimary(listOf(true, true, false), 0))
        // Wraps past an answered first question.
        assertEquals(Next(1), questionPrimary(listOf(true, false, true), 2))
        assertEquals("Next", Next(1).label)
    }

    @Test
    fun `Submit answers is offered once every other question is answered and enabled once all are`() {
        assertEquals(Submit(false), questionPrimary(listOf(false, true), 0))
        assertEquals(Submit(true), questionPrimary(listOf(true, true), 0))
        assertEquals(Submit(false), questionPrimary(listOf(true, false), 1))
        assertEquals(Submit(false), questionPrimary(listOf(true, false, true), 1))
        // One question: never Next.
        assertEquals(Submit(false), questionPrimary(listOf(false), 0))
        assertEquals(Submit(true), questionPrimary(listOf(true), 0))
        assertEquals("Submit answers", Submit(true).label)
    }

    @Test
    fun `a two-question request yields two questions in input order`() {
        val questions = Cards.question(pendingAt("ask-two", 33))!!.questions
        assertEquals(
            listOf(
                Question("Which colour?", "Colour", false, listOf(Option("Red", "Red"), Option("Blue", "Blue")).toImmutableList()),
                Question(
                    "Which toppings would you like?",
                    "Toppings",
                    true,
                    listOf(Option("Cheese", "Cheese"), Option("Olives", "Olives"), Option("Ham", "Ham")).toImmutableList(),
                ),
            ),
            questions,
        )
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

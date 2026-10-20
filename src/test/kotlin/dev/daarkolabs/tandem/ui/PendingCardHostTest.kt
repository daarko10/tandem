package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.Cards
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.PermissionAnswer
import dev.daarkolabs.tandem.session.PermissionAnswers
import dev.daarkolabs.tandem.ui.cards.agentLine
import dev.daarkolabs.tandem.ui.cards.answerFor
import dev.daarkolabs.tandem.ui.cards.escapeAnswer
import dev.daarkolabs.tandem.ui.cards.genericCard
import dev.daarkolabs.tandem.ui.cards.moreWaitingText
import dev.daarkolabs.tandem.ui.cards.permissionTitle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the docked card says and answers (spec 4.12, 5.2). */
class PendingCardHostTest {
    @Test
    fun `a request that is gone yields no answer`() {
        // The touch request (<request_id-26>) is pending; <request_id-63> (line 52) is not.
        val state = stateAt("permission", 21)
        assertEquals(PermissionAnswer.Deny("The user denied this request."), answerFor(state, "<request_id-26>") { PermissionAnswers.deny() })
        assertNull(answerFor(state, "<request_id-63>") { PermissionAnswers.deny() })
        assertNull(answerFor(ChatState(), "<request_id-26>") { PermissionAnswers.deny() })
    }

    @Test
    fun `escape answers per card`() {
        val p = pendingAt("permission", 21)
        assertEquals(PermissionAnswer.Deny("The user denied this request."), escapeAnswer(Cards.permission(p, null), "")(p))
        val q = pendingAt("ask-single", 22)
        assertEquals(PermissionAnswer.Deny("cancelled"), escapeAnswer(Cards.question(q)!!, "")(q))
        val plan = stateAt("plan-approve", 88)
        val pp = plan.pendingPermissions.single()
        assertEquals(PermissionAnswer.Deny("also add a date"), escapeAnswer(Cards.plan(pp, plan), "also add a date")(pp))
        assertEquals(PermissionAnswer.Deny("No, keep planning"), escapeAnswer(Cards.plan(pp, plan), "  ")(pp))
    }

    @Test
    fun `titles and the waiting count`() {
        assertEquals("Allow Claude to Run touch tandem-fixture.txt?", permissionTitle(Cards.permission(pendingAt("permission", 21), null)))
        val agent = pendingAt("permission", 21).copy(
            toolName = "Agent",
            input = buildJsonObject {
                put("description", "Count lines")
                put("prompt", "Count the lines of seed.txt")
            },
        )
        // T6: ("Run an agent", "").
        assertEquals("Allow Claude to Run an agent?", permissionTitle(Cards.permission(agent, null)))
        assertEquals("From the agent \"Count lines in seed.txt\"", agentLine(Cards.pending(stateAt("agent-sync", 43)).first!!))
        assertNull(agentLine(Cards.permission(pendingAt("permission", 21), null)))
        assertEquals("Allow Claude to Use AskUserQuestion?", permissionTitle(genericCard(Cards.question(pendingAt("ask-single", 22))!!)))
        val plan = stateAt("plan-approve", 88)
        assertEquals("Allow Claude to Use ExitPlanMode?", permissionTitle(genericCard(Cards.plan(plan.pendingPermissions.single(), plan))))
        assertNull(moreWaitingText(0))
        assertEquals("1 more waiting", moreWaitingText(1))
        assertEquals("3 more waiting", moreWaitingText(3))
    }
}

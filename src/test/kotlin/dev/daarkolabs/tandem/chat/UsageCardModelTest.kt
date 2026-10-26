package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContentBlock
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.SessionUsage
import dev.daarkolabs.tandem.protocol.UsageAnswer
import dev.daarkolabs.tandem.protocol.UsageBehavior
import dev.daarkolabs.tandem.protocol.UsageHistory
import dev.daarkolabs.tandem.protocol.UsageTip
import dev.daarkolabs.tandem.protocol.UsageWindow
import dev.daarkolabs.tandem.session.McpError
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.UsageCard
import dev.daarkolabs.tandem.session.UsagePartState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The /usage card's model (spec 3.2, 4.3, 16) from `parity-usage:16` (no scan), `:17` (with the scan) and `:22` (the CLI's text). */
class UsageCardModelTest {
    private val models: List<ModelOption> = listOf(
        ModelOption("default", "Default (recommended)", null, "claude-fable-5-1"),
        ModelOption("sonnet", "Sonnet 5.5", null, "claude-sonnet-5-5"),
        ModelOption("haiku", "Haiku 5.5", null, "claude-haiku-5-5"),
    )
    private val u16: UsageAnswer = usageAt(16)
    private val u17: UsageAnswer = usageAt(17)
    private val text22: String =
        (fixtureLine("parity-usage", 22) as CliMessage.Assistant).content.filterIsInstance<ContentBlock.Text>().single().text
    private val session: SessionUsage = u16.session!!
    private val history: UsageHistory = u17.history!!

    private fun usageAt(line: Int): UsageAnswer = ControlPayloads.usage((fixtureLine("parity-usage", line) as CliMessage.ControlResponse).payload)!!

    private fun card(
        session: UsagePartState<SessionUsage> = UsagePartState.Loaded(this.session),
        history: UsagePartState<UsageHistory?> = UsagePartState.Loaded(this.history),
        active: String? = "claude-sonnet-5-5",
        text: String = text22,
        models: List<ModelOption> = this.models,
    ): UsageCardModel = usageCard(UsageCard(session, history), text, models, active)

    private fun windows(model: UsageCardModel = card()): Pair<UsageWindowModel?, UsageWindowModel?> =
        assertIs<UsageSection.Shown<Pair<UsageWindowModel?, UsageWindowModel?>>>(model.history).value

    private fun facts(vararg pairs: Pair<String, String>): List<UsageFact> = pairs.map { UsageFact(it.first, it.second) }

    @Test
    fun `the session facts read cost, API and wall time`() {
        val model = card(history = UsagePartState.Loading)
        assertEquals<Any?>(UsageSection.Shown(facts("Est. cost" to "$0.15", "API" to "4s", "Wall" to "7s")), model.facts)
    }

    @Test
    fun `formatCost rounds to cents and marks a sliver`() {
        assertEquals("$0.00", formatCost(0.0))
        assertEquals("<$0.01", formatCost(0.004))
        assertEquals("$0.01", formatCost(0.005))
        assertEquals("$0.15", formatCost(0.1523063))
        assertEquals("$0.00", formatCost(-1.0))
        assertEquals("$0.00", formatCost(Double.NaN))
    }

    @Test
    fun `costShare marks a sliver and the whole`() {
        assertEquals(">99% of cost", costShare(0.152182, 0.1523063)!!.second)
        assertEquals("<1% of cost", costShare(0.0001243, 0.1523063)!!.second)
        assertEquals(0.5f to "50% of cost", costShare(0.05, 0.1))
        assertEquals(0f to "0% of cost", costShare(0.0, 0.15))
        assertNull(costShare(null, 0.15))
        assertNull(costShare(0.1, null))
        assertNull(costShare(0.1, 0.0))
        assertNull(costShare(Double.NaN, 0.15))
    }

    @Test
    fun `web searches show only above zero`() {
        // A mutated copy of :16: the sonnet model made three web searches.
        val searched = session.copy(models = session.models.map { (id, tokens) -> id to if (id == "claude-sonnet-5-5") tokens.copy(webSearches = 3) else tokens })
        val sonnet = card(session = UsagePartState.Loaded(searched)).models.first()
        assertEquals(UsageFact("Web searches", "3"), sonnet.rows.last())
        assertTrue(card().models.flatMap { it.rows }.none { it.label == "Web searches" })
    }

    @Test
    fun `the prompt cache line comes from the CLI's text`() {
        assertEquals(
            "1 request · 0% of input tokens from cache · no misses · warm (1h TTL, last activity 5s ago)",
            promptCacheLine(text22),
        )
        assertEquals(promptCacheLine(text22), card().promptCache)
        assertNull(promptCacheLine("Total cost: $0.15"))
    }

    @Test
    fun `the active model comes first with the footer's label`() {
        val model = card()
        assertEquals<List<Triple<String, String?, List<UsageFact>>>>(
            listOf(
                Triple("Sonnet 5.5", "$0.15", facts("Input" to "2", "Output" to "3", "Cache read" to "0", "Cache write" to "38.0k")),
                Triple("Haiku 5.5", "<$0.01", facts("Input" to "1.2k", "Output" to "14", "Cache read" to "0", "Cache write" to "0")),
            ),
            model.models.map { Triple(it.label, it.cost, it.rows) },
        )
        assertEquals(listOf(">99% of cost", "<1% of cost"), model.models.map { it.costShareText })
        assertEquals(listOf("Haiku 5.5", "Sonnet 5.5"), card(active = null).models.map { it.label })
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5"), card(active = null, models = emptyList()).models.map { it.label })
        // The default entry resolves to claude-fable-5-1 but never labels it.
        val fable = session.copy(models = listOf("claude-fable-5-1" to session.models[0].second))
        assertEquals("claude-fable-5-1", card(session = UsagePartState.Loaded(fable), active = null).models.single().label)
    }

    @Test
    fun `behaviours use Tandem's words, else the CLI's recommendation title, else are hidden`() {
        val (day, week) = windows()
        assertEquals(
            listOf(
                BehaviorLine("100%", "came from subagent-heavy sessions", true),
                BehaviorLine("100%", "Stop sessions you no longer need", false),
                BehaviorLine("80%", "Save workflows for big tasks", false),
                BehaviorLine("65%", "came from sessions with a long context", true),
                BehaviorLine("11%", "came from sessions with large tool results", true),
            ),
            day!!.behaviors,
        )
        assertTrue(BehaviorLine("19%", "ran at max effort", true) in week!!.behaviors)
        val parallel = history.day!!.copy(behaviors = history.day!!.behaviors + UsageBehavior("high_parallel", 50))
        val (withParallel, _) = windows(card(history = UsagePartState.Loaded(UsageHistory(parallel, null))))
        assertEquals(day.behaviors, withParallel!!.behaviors)
    }

    @Test
    fun `shares sort by percent with their kind`() {
        val (day, week) = windows()
        assertEquals(
            listOf(
                ShareRow("workflow-subagent", "Subagent", 0.8f, "80%"),
                ShareRow("server-a", "MCP", 0.02f, "2%"),
                ShareRow("server-b", "MCP", 0.02f, "2%"),
            ),
            day!!.shares,
        )
        assertTrue(ShareRow("plugin-a", "Plugin", 0.01f, "1%") in week!!.shares)
    }

    @Test
    fun `the caveat names the window, its sessions and its requests`() {
        val (day, week) = windows()
        val tail = "approximate, overlapping measures · this machine only, excludes claude.ai"
        assertEquals("Last 24 hours · 23 sessions · 8.3k requests · $tail", day!!.caveat)
        assertEquals("Last 7 days · 88 sessions · 19.7k requests · $tail", week!!.caveat)
        val noSessions = history.day!!.copy(sessionCount = null)
        assertEquals("Last 24 hours · 8.3k requests · $tail", windows(card(history = UsagePartState.Loaded(UsageHistory(noSessions, null)))).first!!.caveat)
        val neither = noSessions.copy(requestCount = null)
        assertEquals("Last 24 hours · $tail", windows(card(history = UsagePartState.Loaded(UsageHistory(neither, null)))).first!!.caveat)
    }

    @Test
    fun `every tip of the window in the CLI's order`() {
        val (day, week) = windows()
        assertEquals(
            listOf(
                "Give simple subagents a cheaper model",
                "Stop sessions you no longer need",
                "Save workflows for big tasks",
                "Start fresh when you switch tasks",
                "Shorten the output of \"sed\"",
            ),
            day!!.tips.map { it.title },
        )
        assertEquals(UsageTip("subagent_heavy", "Give simple subagents a cheaper model", null), day.tips[0])
        assertEquals("some ran 8 hours or more", day.tips[1].detail)
        assertEquals("Save max effort for hard tasks", week!!.tips.last().title)
        assertEquals("Tip", tipsTitle(1))
        assertEquals("Tips", tipsTitle(5))
        assertEquals("Show 2 more", tipsToggle(5, open = false))
        assertEquals("Show fewer", tipsToggle(5, open = true))
        assertNull(tipsToggle(3, open = false))
    }

    @Test
    fun `history null says the CLI did not report it`() {
        assertEquals<Any?>(UsageSection.Note("The CLI did not report what is using your limits."), card(history = UsagePartState.Loaded(null)).history)
    }

    @Test
    fun `a missing or empty window is marked empty`() {
        val weekOnly = windows(card(history = UsagePartState.Loaded(UsageHistory(day = null, week = history.week))))
        assertNull(weekOnly.first)
        assertEquals(windows().second, weekOnly.second)
        val empty = UsageWindow(3, 10, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertNull(windows(card(history = UsagePartState.Loaded(UsageHistory(empty, null)))).first)
        val unlabelled = empty.copy(behaviors = listOf(UsageBehavior("high_parallel", 40)))
        assertNull(windows(card(history = UsagePartState.Loaded(UsageHistory(unlabelled, null)))).first)
    }

    @Test
    fun `absent facts, models and tips are left out`() {
        val bare = card(session = UsagePartState.Loaded(SessionUsage(null, 4192, null, emptyList())))
        assertEquals<Any?>(UsageSection.Shown(facts("API" to "4s")), bare.facts)
        assertTrue(bare.models.isEmpty())
        assertEquals<Any?>(UsageSection.Shown(emptyList<UsageFact>()), card(session = UsagePartState.Loaded(SessionUsage(null, null, null, emptyList()))).facts)
        val noTips = history.day!!.copy(tips = emptyList())
        assertTrue(windows(card(history = UsagePartState.Loaded(UsageHistory(noTips, null)))).first!!.tips.isEmpty())
    }

    @Test
    fun `loading and error sections`() {
        val loading = card(session = UsagePartState.Loading, history = UsagePartState.Loading)
        assertEquals<Any?>(UsageSection.Loading, loading.facts)
        assertEquals<Any?>(UsageSection.Loading, loading.history)
        assertTrue(loading.models.isEmpty())
        fun failed(error: McpError): UsageSection<*> = card(session = UsagePartState.Failed(error)).facts
        assertEquals<Any?>(UsageSection.Error("Could not load usage: boom", true), failed(McpError.Rejected("boom")))
        assertEquals<Any?>(UsageSection.Error("The CLI did not answer within 15 s.", true), failed(McpError.TimedOut(15)))
        assertEquals<Any?>(UsageSection.Error("The session has ended.", false), failed(McpError.Ended))
        assertEquals<Any?>(UsageSection.Error("The session has ended.", false), failed(McpError.NotRunning))
        fun history(error: McpError): UsageSection<*> = card(history = UsagePartState.Failed(error)).history
        assertEquals<Any?>(UsageSection.Error("Could not read the usage history: x", true), history(McpError.Rejected("x")))
        assertEquals<Any?>(UsageSection.Error("The CLI did not answer within 60 s.", true), history(McpError.TimedOut(60)))
        assertEquals<Any?>(UsageSection.Error("The session has ended.", false), history(McpError.Ended))
        assertEquals<Any?>(UsageSection.Error("The session has ended.", false), history(McpError.NotRunning))
    }

    @Test
    fun `the CLI text opens when the session part failed or has lines the card does not draw`() {
        assertEquals(text22, card().cliText)
        assertFalse(card().cliTextOpen)
        assertFalse(card(session = UsagePartState.Loading).cliTextOpen)
        assertTrue(card(session = UsagePartState.Failed(McpError.Ended)).cliTextOpen)
        assertFalse(undrawnCliLines(text22))
        // Hand-made, unrecorded: a subscriber's plan row (docs).
        val plan = text22 + "\nPlan window · 12% used"
        assertTrue(undrawnCliLines(plan))
        assertTrue(card(text = plan).cliTextOpen)
        // An indented line outside the model block is not a model line.
        assertTrue(undrawnCliLines("Total cost: $0.15\n  stray"))
        assertFalse(undrawnCliLines("\n  Total cost: $0.15\n"))
    }
}

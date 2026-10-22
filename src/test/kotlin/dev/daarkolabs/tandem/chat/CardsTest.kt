package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.TaskInfo
import dev.daarkolabs.tandem.session.reduce
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The cards a pending request docks above the composer (spec 4.12–4.14). */
class CardsTest {
    private val cwd = "/p"

    /** The pending permission of the `can_use_tool` line [line] of fixture [name]. */
    private fun permissionAt(name: String, line: Int): PendingPermission =
        reduce(ChatState(), SessionEvent.Cli(fixtureLine(name, line))).pendingPermissions.single()

    private fun request(tool: String, input: JsonObject, id: String = "r-$tool", description: String? = null): PendingPermission =
        PendingPermission(id, tool, input, "t-$tool", description, emptyList())

    private fun title(p: PendingPermission): Pair<String, String> = Cards.permission(p, cwd).let { it.verb to it.objectText }

    private fun payload(p: PendingPermission): Payload = Cards.permission(p, cwd).payload

    /** The state after line [through] of fixture [name], replayed as [replay] does, with each line first changed by [patch]. */
    private fun stateAt(name: String, through: Int, patch: (line: Int, text: String) -> String = { _, text -> text }): ChatState {
        var state = ChatState()
        Fixtures.lines(name).take(through).forEachIndexed { i, raw ->
            val message = Ndjson.decode(patch(i + 1, raw))
            if (message is CliMessage.SystemInit) state = reduce(state, SessionEvent.UserSent("u${i + 1}", PROMPT, atMillis = i * 100L))
            state = reduce(state, SessionEvent.Cli(message, atMillis = i * 100L))
        }
        return state
    }

    private val planPath = "/home/u/.claude/plans/x.md"

    /** `plan-disallowed` through its ExitPlanMode request (line 173), with line 102's Write aimed at a plans directory. */
    private fun patchedDraft(): ChatState = stateAt("plan-disallowed", 173) { line, text ->
        if (line == 102) text.replace("\"file_path\":\"<path>\"", "\"file_path\":\"$planPath\"") else text
    }

    @Test
    fun `pending picks the first request and counts the rest`() {
        val first = request("Bash", buildJsonObject { put("command", "ls") }, id = "r1")
        val second = request("Bash", buildJsonObject { put("command", "pwd") }, id = "r2")
        val state = ChatState(cwd = cwd, pendingPermissions = listOf(first, second))
        assertEquals(Cards.permission(first, cwd) to 1, Cards.pending(state))
        assertEquals(null to 0, Cards.pending(ChatState()))
    }

    @Test
    fun `permission titles per tool`() {
        val bash = permissionAt("tools-bash", 20)
        assertEquals("Run" to bash.input["command"]!!.jsonPrimitive.content, title(bash))
        val fetch = permissionAt("misc-web", 31)
        assertEquals("Fetch" to ToolLabels.middleTruncate(fetch.input["url"]!!.jsonPrimitive.content, 80), title(fetch))
        val search = permissionAt("misc-web", 74)
        assertEquals("Search the web for" to "\"IntelliJ Platform Gradle Plugin 2.19\"", title(search))
        val file = buildJsonObject { put("file_path", "/p/src/a.kt") }
        assertEquals("Read" to "src/a.kt", title(request("Read", file)))
        assertEquals("Edit" to "src/a.kt", title(request("Edit", file)))
        assertEquals("Edit" to "src/a.kt", title(request("mcp__tandem__edit_file", file)))
        assertEquals("Edit" to "n.ipynb", title(request("NotebookEdit", buildJsonObject { put("notebook_path", "/p/n.ipynb") })))
        assertEquals("Create" to "src/a.kt", title(request("Write", file)))
        assertEquals("Run" to "an agent", title(request("Agent", buildJsonObject { put("description", "d") })))
        assertEquals("Run" to "an agent", title(request("Task", buildJsonObject { put("description", "d") })))
        assertEquals("Use" to "github: list issues", title(request("mcp__github__list_issues", JsonObject(emptyMap()))))
        assertEquals("Use" to "Some New Tool", title(request("SomeNewTool", JsonObject(emptyMap()))))
        assertEquals("Use" to "ExitPlanMode", title(request("ExitPlanMode", JsonObject(emptyMap()))))
        val long = "https://example.com/" + "a".repeat(200)
        assertEquals("Fetch" to ToolLabels.middleTruncate(long, 80), title(request("WebFetch", buildJsonObject { put("url", long) })))
        // A request without the field its title names still has an object.
        assertEquals("Run" to "Bash", title(request("Bash", JsonObject(emptyMap()))))
        assertEquals("Read" to "Read", title(request("Read", JsonObject(emptyMap()))))
        assertEquals("Create empty fixture file", Cards.permission(permissionAt("permission", 21), cwd).description)
    }

    @Test
    fun `always allow only with addRules`() {
        assertTrue(Cards.permission(permissionAt("permission", 21), cwd).canAlwaysAllow)
        assertEquals(false, Cards.permission(permissionAt("ask-single", 22), cwd).canAlwaysAllow)
    }

    @Test
    fun `payload per tool`() {
        val bash = permissionAt("tools-bash", 20)
        assertEquals(Payload.Mono(bash.input["command"]!!.jsonPrimitive.content), payload(bash))
        assertEquals(Payload.Mono("/p/a.kt"), payload(request("Read", buildJsonObject { put("file_path", "/p/a.kt") })))
        val edit = buildJsonObject {
            put("file_path", "/p/a.kt")
            put("old_string", "a\nb")
            put("new_string", "c")
        }
        assertEquals(Payload.OldNew("/p/a.kt", "a\nb", "c"), payload(request("Edit", edit)))
        val write = buildJsonObject {
            put("file_path", "/p/a.kt")
            put("content", "hello\n")
        }
        assertEquals(Payload.Mono("hello\n"), payload(request("Write", write)))
        val fetch = permissionAt("misc-web", 31)
        assertEquals(Payload.Mono("https://example.com\nWhat is the title of this page?"), payload(fetch))
        assertEquals(Payload.Mono("IntelliJ Platform Gradle Plugin 2.19"), payload(permissionAt("misc-web", 74)))
        val agent = buildJsonObject {
            put("description", "Count lines")
            put("prompt", "Count the lines")
        }
        assertEquals(Payload.Mono("Count lines\nCount the lines"), payload(request("Agent", agent)))
        assertEquals(Payload.Mono("open bugs"), payload(request("mcp__github__search", buildJsonObject { put("query", "open bugs") })))
        val odd = buildJsonObject {
            put("n", 3)
            put("flag", true)
        }
        assertEquals(Payload.Json("{\n    \"n\": 3,\n    \"flag\": true\n}"), payload(request("SomeNewTool", odd)))
        assertEquals(Payload.None, payload(request("SomeNewTool", JsonObject(emptyMap()))))
        // A tool's own rule without its field falls back to the first string input.
        assertEquals(Payload.Mono("/p/a.kt"), payload(request("Edit", buildJsonObject { put("file_path", "/p/a.kt") })))
        assertEquals(Payload.Mono("x"), payload(request("Bash", buildJsonObject { put("description", "x") })))
    }

    @Test
    fun `agent description from agent_id`() {
        val state = stateAt("agent-sync", 43)
        val pending = state.pendingPermissions.single()
        assertNotNull(pending.agentId)
        val card = assertNotNull(Cards.pending(state).first)
        assertEquals("Count lines in seed.txt", card.agentDescription)
        assertNull(Cards.permission(pending, state.cwd).agentDescription)
        // Without the task, or without an agent, no line.
        assertNull(Cards.pending(state.copy(tasks = emptyMap())).first!!.agentDescription)
        assertNull(Cards.pending(state.copy(pendingPermissions = listOf(pending.copy(agentId = null)))).first!!.agentDescription)
        val elsewhere = TaskInfo(pending.agentId, "missing", null, null, null, null, null, null, null, null)
        assertNull(Cards.pending(state.copy(tasks = mapOf(pending.agentId to elsewhere))).first!!.agentDescription)
    }

    @Test
    fun `question card from ask-single`() {
        val p = permissionAt("ask-single", 22)
        val card = assertNotNull(Cards.question(p))
        assertEquals(
            persistentListOf(Question("Which colour?", "Colour", false, persistentListOf(Option("Red", "Red"), Option("Blue", "Blue")))),
            card.questions,
        )
        assertEquals(p.requestId, card.requestId)
        assertEquals(p.toolUseId, card.toolUseId)
        assertNull(card.agentDescription)
        assertEquals(card, Cards.pending(ChatState(pendingPermissions = listOf(p))).first)
    }

    @Test
    fun `question card from ask-multi is multi-select`() {
        val question = assertNotNull(Cards.question(permissionAt("ask-multi", 27))).questions.single()
        assertTrue(question.multiSelect)
        assertEquals(listOf("Cheese", "Olives", "Ham"), question.options.map { it.label })
    }

    @Test
    fun `question card with two questions keeps both`() {
        val input = buildJsonObject {
            putJsonArray("questions") {
                addJsonObject {
                    put("question", "First?")
                    put("header", "One")
                    putJsonArray("options") { addJsonObject { put("label", "A") } }
                }
                addJsonObject {
                    put("question", "Second?")
                    put("multiSelect", true)
                    putJsonArray("options") {
                        addJsonObject {
                            put("label", "B")
                            put("description", "bee")
                        }
                    }
                }
            }
        }
        val questions = assertNotNull(Cards.question(request("AskUserQuestion", input))).questions
        assertEquals(2, questions.size)
        assertEquals(listOf("First?", "Second?"), questions.map { it.text })
        assertEquals(Question("First?", "One", false, persistentListOf(Option("A", null))), questions[0])
        // A question without a header takes its text as the tab label (spec 4.6.2).
        assertEquals(Question("Second?", "Second?", true, persistentListOf(Option("B", "bee"))), questions[1])
    }

    @Test
    fun `a malformed question input falls back to the permission card`() {
        val p = permissionAt("ask-single", 22).copy(input = buildJsonObject { put("questions", "x") })
        assertNull(Cards.question(p))
        val card = assertIs<PermissionCard>(Cards.pending(ChatState(pendingPermissions = listOf(p))).first)
        assertEquals("Use" to "AskUserQuestion", card.verb to card.objectText)
        for (bad in listOf(
            JsonObject(emptyMap()),
            buildJsonObject { putJsonArray("questions") {} },
            buildJsonObject { putJsonArray("questions") { add("x") } },
            buildJsonObject { putJsonArray("questions") { addJsonObject { put("header", "h") } } },
            buildJsonObject {
                putJsonArray("questions") {
                    addJsonObject {
                        put("question", "q")
                        put("options", "x")
                    }
                }
            },
            buildJsonObject {
                putJsonArray("questions") {
                    addJsonObject {
                        put("question", "q")
                        putJsonArray("options") { addJsonObject { put("description", "no label") } }
                    }
                }
            },
        )) {
            assertNull(Cards.question(p.copy(input = bad)), bad.toString())
        }
    }

    @Test
    fun `answers join multi-select labels in option order`() {
        val card = assertNotNull(Cards.question(permissionAt("ask-multi", 27)))
        assertEquals(mapOf("Which toppings would you like?" to "Cheese, Ham"), Cards.answers(card, mapOf(0 to Pick.Options(listOf(2, 0)))))
        // Nothing picked, or a pick out of range, answers nothing.
        assertEquals(emptyMap(), Cards.answers(card, mapOf(0 to Pick.Options(emptyList()))))
        assertEquals(emptyMap(), Cards.answers(card, mapOf(0 to Pick.Options(listOf(7)), 3 to Pick.Options(listOf(0)))))
    }

    @Test
    fun `answers use the Other text`() {
        val card = assertNotNull(Cards.question(permissionAt("ask-single", 22)))
        assertEquals(mapOf("Which colour?" to "Green"), Cards.answers(card, mapOf(0 to Pick.Other(" Green "))))
        assertEquals(emptyMap(), Cards.answers(card, mapOf(0 to Pick.Other("  "))))
        assertEquals(mapOf("Which colour?" to "Blue"), Cards.answers(card, mapOf(0 to Pick.Options(listOf(1)))))
    }

    @Test
    fun `plan from the request input`() {
        val state = stateAt("plan-approve", 88)
        val p = state.pendingPermissions.single()
        val (plan, source) = Cards.planText(state, p)
        assertEquals(PlanSource.EXIT_PLAN_INPUT, source)
        assertEquals(p.input["plan"]!!.jsonPrimitive.content, plan)
        val card = assertIs<PlanCard>(Cards.pending(state).first)
        assertEquals(PlanSource.EXIT_PLAN_INPUT, card.source)
        assertEquals("<path>", card.planFilePath)
        assertEquals(plan, card.plan)
    }

    @Test
    fun `plan from the failed plan-file write`() {
        val state = patchedDraft()
        val p = state.pendingPermissions.single()
        assertEquals("ExitPlanMode", p.toolName)
        val written = (Ndjson.decode(Fixtures.lines("plan-disallowed")[101]) as CliMessage.Assistant).content
            .filterIsInstance<dev.daarkolabs.tandem.protocol.ContentBlock.ToolUse>().single().input["content"]!!.jsonPrimitive.content
        assertEquals(written to PlanSource.PLAN_FILE_DRAFT, Cards.planText(state, p))
        val card = Cards.plan(p, state)
        assertEquals(planPath, card.planFilePath)
        assertEquals("From Claude's draft of x.md (the plan file was not saved)", Cards.sourceLine(card))
    }

    @Test
    fun `a write outside a plans directory is not the plan`() {
        val state = stateAt("plan-disallowed", 173)
        val p = state.pendingPermissions.single()
        assertEquals(null to PlanSource.NONE, Cards.planText(state, p))
        assertNull(Cards.plan(p, state).planFilePath)
    }

    /** A turn holding [blocks] in one assistant message, with a pending ExitPlanMode request for its `ExitPlanMode` call. */
    private fun planTurn(vararg blocks: AssistantBlock): Pair<ChatState, PendingPermission> {
        val p = PendingPermission("r1", "ExitPlanMode", JsonObject(emptyMap()), "x1", null, emptyList(), requiresUserInteraction = true)
        val entries = listOf(
            ChatEntry.User("u0", "earlier"),
            ChatEntry.Assistant("a0", "a0", null, null, listOf(AssistantBlock.Text("old text")), 1, false, emptySet()),
            ChatEntry.User("u1", "plan it"),
            ChatEntry.Assistant("a1", "a1", null, null, blocks.toList(), blocks.size, false, emptySet()),
        )
        return ChatState(entries = entries, pendingPermissions = listOf(p)) to p
    }

    private val exitPlan = AssistantBlock.ToolUse("x1", "ExitPlanMode", JsonObject(emptyMap()), "")

    @Test
    fun `plan from the last assistant text`() {
        val (state, p) = planTurn(AssistantBlock.Text("# Plan\n- a"), exitPlan)
        assertEquals("# Plan\n- a" to PlanSource.ASSISTANT_TEXT, Cards.planText(state, p))
        // A text after the ExitPlanMode call, or a subagent's, is not the plan.
        val (after, q) = planTurn(AssistantBlock.Text("# Plan\n- a"), exitPlan, AssistantBlock.Text("later"))
        assertEquals("# Plan\n- a" to PlanSource.ASSISTANT_TEXT, Cards.planText(after, q))
        val sub = ChatEntry.Assistant("s1", "s1", "agent", null, listOf(AssistantBlock.Text("sub")), 1, false, emptySet())
        val (plain, r) = planTurn(exitPlan)
        val withSub = plain.copy(entries = plain.entries.take(3) + sub + plain.entries.drop(3))
        assertEquals(null to PlanSource.NONE, Cards.planText(withSub, r))
    }

    @Test
    fun `plan none`() {
        val (state, p) = planTurn(exitPlan)
        assertEquals(null to PlanSource.NONE, Cards.planText(state, p))
        // A request whose call is not in the state yet looks at the whole last turn.
        val early = state.copy(entries = state.entries.dropLast(1))
        assertEquals(null to PlanSource.NONE, Cards.planText(early, p))
    }

    @Test
    fun `plan card says where the plan came from`() {
        val approve = stateAt("plan-approve", 88)
        val fromFile = Cards.plan(approve.pendingPermissions.single(), approve)
        assertEquals("From the plan file <path>", Cards.sourceLine(fromFile))
        assertEquals("From the plan Claude sent", Cards.sourceLine(fromFile.copy(planFilePath = null)))
        assertEquals("From Claude's draft of x.md (the plan file was not saved)", Cards.sourceLine(Cards.plan(patchedDraft().pendingPermissions.single(), patchedDraft())))
        val (text, p) = planTurn(AssistantBlock.Text("# Plan"), exitPlan)
        assertEquals("From Claude's last message (no plan file)", Cards.sourceLine(Cards.plan(p, text)))
        val (none, q) = planTurn(exitPlan)
        assertEquals("Claude sent no plan text.", Cards.sourceLine(Cards.plan(q, none)))
        // A draft without its file is not a card Cards.plan builds: no line names a file it does not know.
        assertFailsWith<IllegalStateException> { Cards.sourceLine(fromFile.copy(source = PlanSource.PLAN_FILE_DRAFT, planFilePath = null)) }
    }

    @Test
    fun `open plan file is offered only with a plan file path`() {
        val approve = stateAt("plan-approve", 88)
        val card = Cards.plan(approve.pendingPermissions.single(), approve)
        assertTrue(card.source == PlanSource.EXIT_PLAN_INPUT && card.planFilePath != null)
        val draft = patchedDraft()
        val drafted = Cards.plan(draft.pendingPermissions.single(), draft)
        assertTrue(drafted.source == PlanSource.PLAN_FILE_DRAFT && drafted.planFilePath == planPath)
        val unpatched = stateAt("plan-disallowed", 173)
        assertNull(Cards.plan(unpatched.pendingPermissions.single(), unpatched).planFilePath)
        // A blank planFilePath is none.
        val blank = approve.pendingPermissions.single().let { it.copy(input = JsonObject(it.input + ("planFilePath" to JsonPrimitive(" ")))) }
        assertNull(Cards.plan(blank, approve).planFilePath)
    }

    @Test
    fun `an equal card keeps its instance across builds`() {
        val builder = ChatRowsBuilder(RowOptions(showThinking = true))
        val state = stateAt("permission", 21)
        val first = assertNotNull(builder.build(state).card)
        assertSame(first, builder.build(state.copy(busy = !state.busy)).card)
        assertEquals(0, builder.build(state).moreWaiting)
    }
}

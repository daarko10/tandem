package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.TaskInfo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The row labels of spec 4.6.2, from the fixtures' tool calls and results. */
class ToolLabelsTest {
    private val cwd = "<path>"

    private fun label(block: AssistantBlock.ToolUse, state: ToolState, result: ChatEntry.ToolResult? = null, cwd: String? = this.cwd): RowLabel =
        ToolLabels.label(ToolKinds.of(block.name), block.name, block.input, state, result?.let(::labelResult), cwd)

    private fun input(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun bash(description: String?, command: String = "ls -la"): JsonObject =
        buildJsonObject {
            put("command", command)
            if (description != null) put("description", description)
        }

    @Test
    fun `read rows name the file and the range`() {
        val read = toolUseAt("tools-read", 17)
        assertEquals("Reading <path>", label(read, ToolState.RUNNING).text)
        val done = label(read, ToolState.DONE, toolResultAt("tools-read", 21))
        assertEquals("Read <path>", done.text)
        assertEquals(persistentListOf(5..10), done.spans)
        val range = label(toolUseAt("tools-read", 34), ToolState.DONE, toolResultAt("tools-read", 38))
        assertEquals("Read <path>:10-14", range.text)
        assertEquals(persistentListOf(5..10), range.spans)
    }

    @Test
    fun `names are relative to cwd and middle-truncated`() {
        assertEquals("b/c.kt", ToolLabels.relativePath("/a/b/c.kt", "/a"))
        assertEquals("a", ToolLabels.relativePath("/a", "/a"))
        assertEquals("y.kt", ToolLabels.relativePath("/x/y.kt", "/a"))
        assertEquals("b/c.kt", ToolLabels.relativePath("/a/b/c.kt", "/a/"))
        val long = "a".repeat(24) + "b" + "c".repeat(24)
        val truncated = ToolLabels.middleTruncate(long, 48)
        assertEquals(48, truncated.length)
        assertTrue("…" in truncated)
        assertEquals("short", ToolLabels.middleTruncate("short", 48))
        // A long name in a label is truncated and its span still covers it.
        val path = "/a/" + "d".repeat(60) + ".kt"
        val read = ToolLabels.label(ToolKind.READ, "Read", input("file_path" to path), ToolState.RUNNING, null, "/a")
        assertEquals(48, read.text.removePrefix("Reading ").length)
        assertEquals(persistentListOf(8..55), read.spans)
    }

    @Test
    fun `bash rows show the description and fall back to the command`() {
        val bash = toolUseAt("permission", 19)
        assertEquals("Creating empty fixture file", label(bash, ToolState.RUNNING).text)
        assertEquals("Ran Create empty fixture file", label(bash, ToolState.DONE, toolResultAt("permission", 25)).text)
        assertEquals("Running ls -la", ToolLabels.label(ToolKind.SHELL, "Bash", bash(null), ToolState.RUNNING, null, cwd).text)
        val ran = ToolLabels.label(ToolKind.SHELL, "Bash", bash(null), ToolState.DONE, JsonPrimitive(""), cwd)
        assertEquals("Ran ls -la", ran.text)
        assertEquals(persistentListOf(4..9), ran.spans)
    }

    @Test
    fun `a leading Run is dropped after Ran`() {
        assertEquals("Ran echo to stdout and stderr", label(toolUseAt("tools-bash", 18), ToolState.DONE).text)
        assertEquals("Ran the tests", ToolLabels.label(ToolKind.SHELL, "Bash", bash("Running the tests"), ToolState.DONE, null, cwd).text)
        assertEquals("Ran build", ToolLabels.label(ToolKind.SHELL, "Bash", bash("Execute build"), ToolState.DONE, null, cwd).text)
    }

    @Test
    fun `bash failure uses the description lowercased`() {
        val failed = label(toolUseAt("tools-bash", 34), ToolState.FAILED, toolResultAt("tools-bash", 39))
        assertEquals("Failed to run failing command with exit code 3", failed.text)
        assertEquals("Failed to run ls -la", ToolLabels.label(ToolKind.SHELL, "Bash", bash(null), ToolState.FAILED, JsonPrimitive("Error: x"), cwd).text)
    }

    @Test
    fun `write create counts the content lines`() {
        val write = toolUseAt("tools-edit", 57)
        val result = toolResultAt("tools-edit", 61)
        assertEquals("Created <path>", label(write, ToolState.DONE, result).text)
        assertEquals(DiffStats(2, 0), ToolBodies.stats(ToolKind.CREATE, write.input, labelResult(result)))
        assertEquals(DiffStats(1, 0), ToolBodies.stats(ToolKind.CREATE, input("file_path" to "<path>", "content" to "x"), labelResult(result)))
    }

    @Test
    fun `write update is an edit`() {
        val r = buildJsonObject {
            put("type", "update")
            putJsonArray("structuredPatch") {
                add(
                    buildJsonObject {
                        put("oldStart", 1)
                        put("newStart", 1)
                        putJsonArray("lines") {
                            add(JsonPrimitive("-a"))
                            add(JsonPrimitive("+b"))
                            add(JsonPrimitive("+c"))
                        }
                    },
                )
            }
        }
        val input = input("file_path" to "<path>", "content" to "b\nc\n")
        assertEquals("Edited <path>", ToolLabels.label(ToolKind.CREATE, "Write", input, ToolState.DONE, r, "<path>").text)
        assertEquals(DiffStats(2, 1), ToolBodies.stats(ToolKind.CREATE, input, r))
    }

    @Test
    fun `edit stats come from structuredPatch`() {
        val edit = toolUseAt("tools-edit", 40)
        val result = toolResultAt("tools-edit", 44)
        assertEquals("Edited <path>", label(edit, ToolState.DONE, result).text)
        assertEquals(DiffStats(1, 1), ToolBodies.stats(ToolKind.EDIT, edit.input, labelResult(result)))
        assertEquals("Editing <path>", label(edit, ToolState.RUNNING).text)
        assertEquals("Failed to edit <path>", label(edit, ToolState.FAILED, null).text)
        assertEquals("Edit <path> · Denied", label(edit, ToolState.DENIED).text)
    }

    @Test
    fun `grep and glob rows count matches and files`() {
        assertEquals("Searched for seven · 1 match", label(toolUseAt("tools-read", 58), ToolState.DONE, toolResultAt("tools-read", 62)).text)
        assertEquals("Found 1 file", label(toolUseAt("tools-read", 46), ToolState.DONE, toolResultAt("tools-read", 50)).text)
        val none = buildJsonObject { put("numFiles", 0) }
        assertEquals("Found no files", ToolLabels.label(ToolKind.FIND, "Glob", input("pattern" to "*.kt"), ToolState.DONE, none, cwd).text)
        val three = buildJsonObject { put("numFiles", 3) }
        assertEquals("Found 3 files", ToolLabels.label(ToolKind.FIND, "Glob", input("pattern" to "*.kt"), ToolState.DONE, three, cwd).text)
        val matches = buildJsonObject { put("numLines", 4) }
        assertEquals("Searched for x · 4 matches", ToolLabels.label(ToolKind.SEARCH, "Grep", input("pattern" to "x"), ToolState.DONE, matches, cwd).text)
        assertEquals("Searched for x", ToolLabels.label(ToolKind.SEARCH, "Grep", input("pattern" to "x"), ToolState.DONE, buildJsonObject { put("numLines", 0) }, cwd).text)
        assertEquals("Searching for x", ToolLabels.label(ToolKind.SEARCH, "Grep", input("pattern" to "x"), ToolState.RUNNING, null, cwd).text)
        assertEquals("Finding files *.kt", ToolLabels.label(ToolKind.FIND, "Glob", input("pattern" to "*.kt"), ToolState.RUNNING, null, cwd).text)
        assertEquals("Failed to find files", ToolLabels.label(ToolKind.FIND, "Glob", input("pattern" to "*.kt"), ToolState.FAILED, JsonPrimitive("Error: x"), cwd).text)
        assertEquals("Failed to search for x", ToolLabels.label(ToolKind.SEARCH, "Grep", input("pattern" to "x"), ToolState.FAILED, null, cwd).text)
        assertEquals("Find files *.kt · Interrupted", ToolLabels.label(ToolKind.FIND, "Glob", input("pattern" to "*.kt"), ToolState.INTERRUPTED, null, cwd).text)
        assertEquals("Search for x · Denied", ToolLabels.label(ToolKind.SEARCH, "Grep", input("pattern" to "x"), ToolState.DENIED, null, cwd).text)
    }

    @Test
    fun `web rows name host and query`() {
        val fetch = toolUseAt("misc-web", 29)
        val host = ToolLabels.hostOf((fetch.input!!["url"] as JsonPrimitive).content)
        assertEquals("example.com", host)
        assertEquals("Fetched $host", label(fetch, ToolState.DONE, toolResultAt("misc-web", 34)).text)
        assertEquals("Fetching $host", label(fetch, ToolState.RUNNING).text)
        assertEquals("Failed to fetch $host", label(fetch, ToolState.FAILED).text)
        val search = toolUseAt("misc-web", 73)
        val query = (search.input!!["query"] as JsonPrimitive).content
        val done = label(search, ToolState.DONE, toolResultAt("misc-web", 78))
        assertEquals("Searched the web for \"$query\"", done.text)
        assertEquals(persistentListOf(21 until done.text.length), done.spans)
        assertEquals("Searching the web for \"$query\"", label(search, ToolState.RUNNING).text)
        assertEquals("Failed to search the web", label(search, ToolState.FAILED).text)
        assertEquals("Search the web for \"$query\" · Interrupted", label(search, ToolState.INTERRUPTED).text)
    }

    @Test
    fun `agent rows carry the Done sub-line`() {
        val result = toolResultAt("agent-sync", 50)
        assertEquals("Done (2 tool uses · 21.8k tokens · 5.8s)", ToolLabels.agentProgress(labelResult(result)))
        val agent = toolUseAt("agent-sync", 35)
        assertEquals("Ran an agent: Count lines in seed.txt", label(agent, ToolState.DONE, result).text)
        assertEquals("Running an agent: Count lines in seed.txt", label(agent, ToolState.RUNNING).text)
        assertEquals("Failed to run an agent: Count lines in seed.txt", label(agent, ToolState.FAILED).text)
        assertEquals("Run an agent: Count lines in seed.txt · Interrupted", label(agent, ToolState.INTERRUPTED).text)
        var seen: TaskInfo? = null
        replay("agent-sync") { line, state, _ -> if (line == 41) seen = state.tasks.values.single() }
        val task = seen!!
        assertEquals("1 tool use · 21.5k tokens · 2.0s", ToolLabels.agentProgress(task))
        assertNull(ToolLabels.agentProgress(task.copy(toolUses = null)))
        assertEquals("2 tool uses · 21.5k tokens · 2.0s", ToolLabels.agentProgress(task.copy(toolUses = 2)))
        assertNull(ToolLabels.agentProgress(JsonPrimitive("Error: x")))
        assertNull(ToolLabels.agentProgress(buildJsonObject { put("totalTokens", 1) }))
        assertEquals("Done (1 tool use · 5 tokens · 1.0s)", ToolLabels.agentProgress(buildJsonObject {
            put("totalToolUseCount", 1)
            put("totalTokens", 5)
            put("totalDurationMs", 1000)
        }))
    }

    @Test
    fun `tool search is hidden unless it failed`() {
        val input = input("query" to "select:WebFetch")
        assertEquals("", ToolLabels.label(ToolKind.TOOL_SEARCH, "ToolSearch", null, ToolState.PENDING, null, cwd).text)
        for (s in listOf(ToolState.RUNNING, ToolState.DONE)) {
            assertEquals("", ToolLabels.label(ToolKind.TOOL_SEARCH, "ToolSearch", input, s, null, cwd).text)
        }
        assertEquals("Failed to load tools", ToolLabels.label(ToolKind.TOOL_SEARCH, "ToolSearch", input, ToolState.FAILED, JsonPrimitive("Error: x"), cwd).text)
    }

    @Test
    fun `mcp rows name server and tool`() {
        val name = "mcp__github__list_issues"
        assertEquals(ToolKind.MCP, ToolKinds.of(name))
        assertEquals("Using github: list issues", ToolLabels.label(ToolKind.MCP, name, JsonObject(emptyMap()), ToolState.RUNNING, null, cwd).text)
        assertEquals("Used github: list issues", ToolLabels.label(ToolKind.MCP, name, JsonObject(emptyMap()), ToolState.DONE, null, cwd).text)
        assertEquals("Failed to use github: list issues", ToolLabels.label(ToolKind.MCP, name, JsonObject(emptyMap()), ToolState.FAILED, null, cwd).text)
        assertEquals("Use github: list issues · Denied", ToolLabels.label(ToolKind.MCP, name, JsonObject(emptyMap()), ToolState.DENIED, null, cwd).text)
        val playwright = "mcp__plugin_playwright_playwright__browser_evaluate"
        assertEquals("Using plugin playwright playwright: browser evaluate", ToolLabels.label(ToolKind.MCP, playwright, JsonObject(emptyMap()), ToolState.RUNNING, null, cwd).text)
        assertEquals(ToolKind.EDIT, ToolKinds.of("mcp__tandem__edit_file"))
    }

    @Test
    fun `unknown tools split camel case`() {
        assertEquals(ToolKind.EDIT, ToolKinds.of("NotebookEdit"))
        assertEquals("Using Monitor", ToolLabels.label(ToolKinds.of("Monitor"), "Monitor", JsonObject(emptyMap()), ToolState.RUNNING, null, cwd).text)
        assertEquals("Used Some New Tool", ToolLabels.label(ToolKinds.of("SomeNewTool"), "SomeNewTool", JsonObject(emptyMap()), ToolState.DONE, null, cwd).text)
        assertEquals("Failed to use Some New Tool", ToolLabels.label(ToolKind.OTHER, "SomeNewTool", JsonObject(emptyMap()), ToolState.FAILED, null, cwd).text)
        assertEquals("Use Some New Tool · Interrupted", ToolLabels.label(ToolKind.OTHER, "SomeNewTool", JsonObject(emptyMap()), ToolState.INTERRUPTED, null, cwd).text)
        assertEquals("Notebook Edit", displayName("NotebookEdit"))
        assertEquals("LSP", displayName("LSP"))
        assertEquals("HTTP Request", displayName("HTTPRequest"))
        assertEquals("Task Stop", displayName("TaskStop"))
    }

    @Test
    fun `denied rows use the base form`() {
        assertEquals("Remove fixture file · Denied", label(toolUseAt("permission", 50), ToolState.DENIED, toolResultAt("permission", 56)).text)
        assertEquals("Run ls -la · Denied", ToolLabels.label(ToolKind.SHELL, "Bash", bash(null), ToolState.DENIED, null, cwd).text)
    }

    @Test
    fun `interrupted rows use the base form`() {
        assertEquals("Read <path> · Interrupted", label(toolUseAt("tools-read", 17), ToolState.INTERRUPTED).text)
    }

    @Test
    fun `hostOf strips scheme and path`() {
        assertEquals("docs.example.com", ToolLabels.hostOf("https://docs.example.com/a/b?q=1"))
        assertEquals("example.com", ToolLabels.hostOf("example.com/x"))
        assertEquals("example.com:8080", ToolLabels.hostOf("http://user@example.com:8080#top"))
    }

    @Test
    fun `commands are middle-truncated at 60`() {
        val command = "x".repeat(30) + "y" + "z".repeat(30)
        assertEquals(61, command.length)
        val label = ToolLabels.label(ToolKind.SHELL, "Bash", bash(null, command), ToolState.RUNNING, null, cwd)
        val shown = label.text.removePrefix("Running ")
        assertEquals(60, shown.length)
        assertTrue("…" in shown)
        assertEquals(persistentListOf(8 until label.text.length), label.spans)
        assertEquals("Running a; b", ToolLabels.label(ToolKind.SHELL, "Bash", bash(null, "a\nb"), ToolState.RUNNING, null, cwd).text)
    }

    @Test
    fun `an unavailable tool reads Failed to use`() {
        assertEquals("Failed to use Glob", label(toolUseAt("tools-read-unavailable", 48), ToolState.FAILED, toolResultAt("tools-read-unavailable", 49)).text)
        assertEquals("Failed to use Write", label(toolUseAt("plan-disallowed", 102), ToolState.FAILED, toolResultAt("plan-disallowed", 103)).text)
        // From disk the error may carry its tags and no tool_use_result.
        val tagged = JsonPrimitive("<tool_use_error>Error: No such tool available: Glob.</tool_use_error>")
        assertEquals("Failed to use Glob", ToolLabels.label(ToolKind.FIND, "Glob", input("pattern" to "*"), ToolState.FAILED, tagged, cwd).text)
    }

    @Test
    fun `a path equal to cwd keeps its basename`() {
        assertEquals("Read <path>", ToolLabels.label(ToolKind.READ, "Read", input("file_path" to "<path>"), ToolState.DONE, null, "<path>").text)
        assertEquals("c.kt", ToolLabels.relativePath("/a/b/c.kt", null))
        assertEquals("c.kt", ToolLabels.relativePath("C:\\a\\b\\c.kt", "D:\\x"))
        assertEquals("b\\c.kt", ToolLabels.relativePath("C:\\a\\b\\c.kt", "C:\\a"))
    }

    @Test
    fun `enter plan mode is a visible row`() {
        val block = AssistantBlock.ToolUse("p1", "EnterPlanMode", JsonObject(emptyMap()), "")
        assertEquals(ToolKind.PLAN_ENTER, ToolKinds.of("EnterPlanMode"))
        assertEquals("Entering plan mode", label(block, ToolState.RUNNING).text)
        assertEquals("Entered plan mode", label(block, ToolState.DONE).text)
        assertEquals("Failed to enter plan mode", label(block, ToolState.FAILED).text)
        assertEquals("Enter plan mode · Denied", label(block, ToolState.DENIED).text)
    }

    @Test
    fun `write rows read Creating while running and Created or Edited from the result`() {
        val write = toolUseAt("tools-edit", 57)
        assertEquals("Creating <path>", label(write, ToolState.RUNNING).text)
        val created = toolResultAt("tools-edit", 61)
        assertEquals("Created <path>", label(write, ToolState.DONE, created).text)
        assertEquals(DiffStats(2, 0), ToolBodies.stats(ToolKind.CREATE, write.input, labelResult(created)))
        val update = buildJsonObject {
            put("type", "update")
            putJsonArray("structuredPatch") {
                add(
                    buildJsonObject {
                        put("oldStart", 1)
                        put("newStart", 1)
                        put("lines", buildJsonArray { add(JsonPrimitive("-a")); add(JsonPrimitive("+b")) })
                    },
                )
            }
        }
        assertEquals("Edited <path>", ToolLabels.label(ToolKind.CREATE, "Write", write.input, ToolState.DONE, update, cwd).text)
        assertEquals(DiffStats(1, 1), ToolBodies.stats(ToolKind.CREATE, write.input, update))
        assertEquals("Failed to write <path>", label(write, ToolState.FAILED).text)
        assertEquals("Write <path> · Interrupted", label(write, ToolState.INTERRUPTED).text)
    }

    @Test
    fun `question rows read Questions with header lines`() {
        val ask = AssistantBlock.ToolUse("<id-12>", "AskUserQuestion", requestInput("ask-single", 22), "")
        assertEquals("Questions", label(ask, ToolState.DONE, toolResultAt("ask-single", 26)).text)
        assertEquals("Asking a question", label(ask, ToolState.RUNNING).text)
        assertEquals("Question cancelled", label(ask, ToolState.DENIED, toolResultAt("ask-deny", 24)).text)
        assertEquals("Failed to ask a question", label(ask, ToolState.FAILED).text)
        assertEquals("Ask a question · Interrupted", label(ask, ToolState.INTERRUPTED).text)
        val body = ToolBodies.body(ToolKind.QUESTION, ask.input, null, labelResult(toolResultAt("ask-single", 26)), isError = false)
        assertEquals(ToolBody.Pairs(persistentListOf("Colour" to "Blue")), body)
    }

    @Test
    fun `plan rows read Proposing, approved and sent back`() {
        val plan = AssistantBlock.ToolUse("p", "ExitPlanMode", buildJsonObject { put("plan", "# P") }, "")
        assertEquals("Proposing a plan", label(plan, ToolState.RUNNING).text)
        assertEquals("Plan approved", label(plan, ToolState.DONE).text)
        assertEquals("Plan sent back", label(plan, ToolState.DENIED).text)
        assertEquals("Failed to exit plan mode", label(plan, ToolState.FAILED).text)
        assertEquals("Exit plan mode · Interrupted", label(plan, ToolState.INTERRUPTED).text)
    }

    @Test
    fun `task tools read Updating tasks`() {
        val task = AssistantBlock.ToolUse("t", "TaskCreate", JsonObject(emptyMap()), "")
        assertEquals(ToolKind.TASKS, ToolKinds.of("TaskUpdate"))
        assertEquals("Updating tasks", label(task, ToolState.RUNNING).text)
        assertEquals("Updated tasks", label(task, ToolState.DONE).text)
        assertEquals("Failed to update tasks", label(task, ToolState.FAILED).text)
        assertEquals("Update tasks · Interrupted", label(task, ToolState.INTERRUPTED).text)
    }

    @Test
    fun `a call interrupted mid-input labels from the name alone`() {
        val block = AssistantBlock.ToolUse("t1", "Read", input = null, partialInput = "{\"file_pa")
        assertEquals("Read · Interrupted", label(block, ToolState.INTERRUPTED).text)
        assertEquals("Run · Interrupted", ToolLabels.label(ToolKind.SHELL, "Bash", null, ToolState.INTERRUPTED, null, cwd).text)
        assertEquals("Run an agent · Interrupted", ToolLabels.label(ToolKind.AGENT, "Agent", null, ToolState.INTERRUPTED, null, cwd).text)
    }

    @Test
    fun `a blank description falls back to the command`() {
        assertEquals("Running ls -la", ToolLabels.label(ToolKind.SHELL, "Bash", bash(""), ToolState.RUNNING, null, cwd).text)
        assertEquals("Ran ls -la", ToolLabels.label(ToolKind.SHELL, "Bash", bash("  "), ToolState.DONE, null, cwd).text)
    }

    @Test
    fun `pending rows show the verb alone`() {
        assertEquals(RowLabel("Reading…", persistentListOf()), ToolLabels.label(ToolKind.READ, "Read", null, ToolState.PENDING, null, cwd))
        assertEquals("Running…", ToolLabels.label(ToolKind.SHELL, "Bash", null, ToolState.PENDING, null, cwd).text)
        assertEquals("Running…", ToolLabels.label(ToolKind.SHELL, "Bash", null, ToolState.WAITING, null, cwd).text)
        val verbs = mapOf(
            ToolKind.EDIT to "Editing…", ToolKind.CREATE to "Creating…", ToolKind.AGENT to "Running…", ToolKind.SEARCH to "Searching…",
            ToolKind.WEB_SEARCH to "Searching…", ToolKind.FIND to "Finding…", ToolKind.WEB_FETCH to "Fetching…", ToolKind.QUESTION to "Asking…",
            ToolKind.PLAN to "Proposing…", ToolKind.PLAN_ENTER to "Entering…", ToolKind.TASKS to "Updating…", ToolKind.MCP to "Using…",
            ToolKind.OTHER to "Using…",
        )
        verbs.forEach { (kind, verb) -> assertEquals(verb, ToolLabels.label(kind, "X", null, ToolState.PENDING, null, cwd).text, kind.name) }
    }

    @Test
    fun `ansi and system reminders are stripped from text`() {
        assertEquals("red", ToolLabels.stripAnsi("\u001b[31mred\u001b[0m"))
        assertEquals("title", ToolLabels.stripAnsi("\u001b]0;x\u0007title"))
        assertEquals("a\nb", ToolLabels.stripSystemReminders("a\nb\n<system-reminder>\nx\n</system-reminder>\n"))
        assertEquals("a", ToolLabels.stripSystemReminders("a<system-reminder>never closed"))
    }

    @Test
    fun `descriptions conjugate their first verb`() {
        assertEquals("Running the tests", conjugate("Run the tests"))
        assertEquals("checking out", conjugate("check out"))
        assertEquals("Frobnicate it", conjugate("Frobnicate it"))
        assertEquals("", conjugate(""))
    }

    @Test
    fun `reading a range needs offset or limit and a line count`() {
        val result = buildJsonObject { putJsonObject("file") { put("startLine", 3); put("numLines", 0) } }
        val input = buildJsonObject { put("file_path", "/a/b.kt"); put("offset", 3) }
        assertEquals("Read b.kt", ToolLabels.label(ToolKind.READ, "Read", input, ToolState.DONE, result, "/x").text)
    }
}

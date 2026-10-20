package dev.daarkolabs.tandem.chat

import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The expanded bodies of spec 4.7, built from the fixtures' results. */
class ToolBodiesTest {
    private fun body(name: String, call: Int, result: Int): ToolBody {
        val block = toolUseAt(name, call)
        val r = toolResultAt(name, result)
        return ToolBodies.body(ToolKinds.of(block.name), block.input, r.content, labelResult(r), r.isError)
    }

    @Test
    fun `read body has line numbers from startLine`() {
        val read = assertIs<ToolBody.Mono>(body("tools-read", 34, 38))
        assertTrue(read.lines.first().startsWith("10"), read.lines.first())
        assertTrue(read.lines.first().endsWith("line 10 juliet"))
        assertEquals(5, read.lines.size)
        assertEquals(5, read.totalLines)
        assertFalse(read.firstLineError)
    }

    @Test
    fun `bash body starts with the command and keeps the exit code line`() {
        val block = toolUseAt("tools-bash", 34)
        val bash = assertIs<ToolBody.Command>(body("tools-bash", 34, 39))
        assertEquals((block.input!!["command"] as JsonPrimitive).content, bash.command)
        assertTrue(bash.output.first().startsWith("Exit code 3"))
        assertTrue(bash.firstLineError)
        val okay = assertIs<ToolBody.Command>(body("tools-bash", 18, 23))
        assertEquals(listOf("out-line", "err-line"), okay.output)
        assertFalse(okay.firstLineError)
    }

    @Test
    fun `bash no output gives no body`() {
        val r = toolResultAt("misc-state-action", 25)
        val input = buildJsonObject { put("command", "touch x") }
        assertEquals(ToolBody.None, ToolBodies.body(ToolKind.SHELL, input, r.content, labelResult(r), r.isError))
    }

    @Test
    fun `system reminders are stripped`() {
        val result = buildJsonObject {
            putJsonObject("file") {
                put("content", "alpha\nbeta\n<system-reminder>\nx\n</system-reminder>")
                put("startLine", 1)
            }
        }
        val read = assertIs<ToolBody.Mono>(ToolBodies.body(ToolKind.READ, null, JsonPrimitive("ignored"), result, isError = false))
        assertTrue(read.lines.none { "system-reminder" in it || "x" in it }, read.lines.toString())
        assertEquals(2, read.lines.size)
    }

    @Test
    fun `ansi is stripped`() {
        val result = buildJsonObject { put("stdout", "\u001b[31mred\u001b[0m") }
        val bash = assertIs<ToolBody.Command>(ToolBodies.body(ToolKind.SHELL, buildJsonObject { put("command", "x") }, null, result, isError = false))
        assertEquals(listOf("red"), bash.output)
    }

    @Test
    fun `bodies clamp at 20 and cap at 400 with the total`() {
        val text = (1..500).joinToString("\n") { "l$it" }
        val mono = assertIs<ToolBody.Mono>(ToolBodies.body(ToolKind.OTHER, null, JsonPrimitive(text), JsonPrimitive(text), isError = true))
        assertEquals(400, mono.lines.size)
        assertEquals(500, mono.totalLines)
        val bash = assertIs<ToolBody.Command>(
            ToolBodies.body(ToolKind.SHELL, buildJsonObject { put("command", "seq") }, null, buildJsonObject { put("stdout", text) }, isError = false),
        )
        assertEquals(400, bash.output.size)
        assertEquals(500, bash.totalLines)
    }

    @Test
    fun `image result reads image WxH`() {
        assertEquals(ToolBody.Image(8, 8, "image/png"), body("misc-image", 22, 26))
    }

    @Test
    fun `web search body lists links`() {
        val links = assertIs<ToolBody.Links>(body("misc-web", 73, 78))
        assertTrue(links.links.isNotEmpty())
        assertTrue(links.links.all { it.title.isNotBlank() && it.url.startsWith("https://") })
        assertTrue(links.markdown.startsWith("I couldn't find"))
    }

    @Test
    fun `web fetch body is the result with its status`() {
        val fetch = assertIs<ToolBody.Markdown>(body("misc-web", 29, 34))
        assertTrue(fetch.markdown.startsWith("The title of this page is"))
        assertTrue(fetch.markdown.endsWith("200 OK"), fetch.markdown)
    }

    @Test
    fun `answers body pairs headers with picks`() {
        val r = toolResultAt("ask-single", 26)
        val input = requestInput("ask-single", 22)
        assertEquals(ToolBody.Pairs(persistentListOf("Colour" to "Blue")), ToolBodies.body(ToolKind.QUESTION, input, r.content, labelResult(r), false))
        val questions = input["questions"]!!.jsonArray
        val blank = JsonObject(
            input + ("questions" to buildJsonArray { add(JsonObject(questions[0].jsonObject + ("header" to JsonPrimitive("")))) }),
        )
        assertEquals(ToolBody.Pairs(persistentListOf("Which colour?" to "Blue")), ToolBodies.body(ToolKind.QUESTION, blank, r.content, labelResult(r), false))
        val response = toolResultAt("ask-response", 24)
        assertEquals(
            ToolBody.Pairs(persistentListOf("Reply" to "free text reply")),
            ToolBodies.body(ToolKind.QUESTION, input, response.content, labelResult(response), false),
        )
        val multi = toolResultAt("ask-multi-array", 30)
        val pairs = assertIs<ToolBody.Pairs>(ToolBodies.body(ToolKind.QUESTION, null, multi.content, labelResult(multi), false))
        assertEquals(listOf("Toppings" to "Cheese, Ham"), pairs.pairs)
    }

    @Test
    fun `cancelled question body`() {
        val r = toolResultAt("ask-deny", 24)
        val mono = assertIs<ToolBody.Mono>(ToolBodies.body(ToolKind.QUESTION, requestInput("ask-single", 22), r.content, labelResult(r), r.isError))
        assertEquals(listOf("cancelled"), mono.lines)
        assertFalse(mono.firstLineError)
    }

    @Test
    fun `diff body from structuredPatch`() {
        val diff = assertIs<ToolBody.Diff>(body("tools-edit", 40, 44))
        assertEquals(1, diff.hunks.size)
        assertEquals(1, diff.hunks[0].lines.count { it.kind == DiffKind.REMOVED })
        assertEquals(1, diff.hunks[0].lines.count { it.kind == DiffKind.ADDED })
        assertEquals(DiffLine(DiffKind.REMOVED, "line 07 golf"), diff.hunks[0].lines.first { it.kind == DiffKind.REMOVED })
        assertEquals(4, diff.hunks[0].oldStart)
        assertEquals("<path>", diff.path)
        assertFalse(diff.truncated)
    }

    @Test
    fun `a result without the expected field falls back to the content text`() {
        val grep = ToolBodies.body(ToolKind.SEARCH, buildJsonObject { put("pattern", "x") }, JsonPrimitive("a\nb"), buildJsonObject { put("mode", "count") }, false)
        assertEquals(ToolBody.Mono(persistentListOf("a", "b"), 2), grep)
        val read = ToolBodies.body(ToolKind.READ, null, JsonPrimitive("pdf text"), buildJsonObject { put("type", "pdf") }, false)
        assertEquals(ToolBody.Mono(persistentListOf("pdf text"), 1), read)
        val edit = ToolBodies.body(ToolKind.EDIT, null, JsonPrimitive("updated"), buildJsonObject { put("filePath", "a") }, false)
        assertEquals(ToolBody.Mono(persistentListOf("updated"), 1), edit)
        assertNull(ToolBodies.stats(ToolKind.EDIT, null, buildJsonObject { put("filePath", "a") }))
    }

    @Test
    fun `a body over 50000 characters ends with the cut line`() {
        val text = "y".repeat(60_000)
        val mono = assertIs<ToolBody.Mono>(ToolBodies.body(ToolKind.OTHER, null, JsonPrimitive(text), JsonPrimitive(text), isError = true))
        assertTrue(Regex("… \\(cut, \\d+ more characters\\)").matches(mono.lines.last()), mono.lines.last())
        assertEquals("… (cut, 10000 more characters)", mono.lines.last())
        assertTrue(mono.lines.sumOf { it.length } < 51_000)
    }

    @Test
    fun `grep glob and write bodies list their lines`() {
        val grep = assertIs<ToolBody.Mono>(body("tools-read", 58, 62))
        assertEquals(listOf("seed.txt:29:line 29 seven"), grep.lines)
        val glob = assertIs<ToolBody.Mono>(body("tools-read", 46, 50))
        assertEquals(listOf("seed.txt"), glob.lines)
        val write = assertIs<ToolBody.Mono>(body("tools-edit", 57, 61))
        assertEquals(listOf("hello", "world"), write.lines)
    }

    @Test
    fun `json bodies show input and output`() {
        val input = buildJsonObject { put("a", 1) }
        val json = assertIs<ToolBody.Json>(ToolBodies.body(ToolKind.MCP, input, JsonPrimitive("done"), buildJsonObject { put("ok", true) }, false))
        assertTrue("\"a\": 1" in json.input, json.input)
        assertTrue("\"ok\": true" in json.output!!, json.output)
        val text = assertIs<ToolBody.Json>(ToolBodies.body(ToolKind.TASKS, input, JsonPrimitive("done"), JsonPrimitive("done"), false))
        assertEquals("done", text.output)
    }

    @Test
    fun `plan, agent and enter plan bodies`() {
        val plan = ToolBodies.body(ToolKind.PLAN, buildJsonObject { put("plan", "# P") }, JsonPrimitive("ok"), buildJsonObject { put("plan", "# Q") }, false)
        assertEquals(ToolBody.Markdown("# Q", null), plan)
        val agent = toolResultAt("agent-sync", 50)
        assertEquals(
            ToolBody.Markdown("7 lines. The last line ends with a trailing newline.", null),
            ToolBodies.body(ToolKind.AGENT, null, agent.content, labelResult(agent), false),
        )
        assertEquals(ToolBody.Mono(persistentListOf("Entered."), 1), ToolBodies.body(ToolKind.PLAN_ENTER, null, JsonPrimitive("Entered."), JsonPrimitive("Entered."), false))
        val failed = assertIs<ToolBody.Mono>(body("tools-read-unavailable", 48, 49))
        assertTrue(failed.lines.first().startsWith("Error: No such tool available: Glob"), failed.lines.first())
        assertTrue(failed.firstLineError)
    }

    @Test
    fun `stats count patch lines and created content`() {
        val patch = buildJsonObject {
            putJsonArray("structuredPatch") {
                add(buildJsonObject { putJsonArray("lines") { add(JsonPrimitive("+a")); add(JsonPrimitive(" b")); add(JsonPrimitive("-c")) } })
            }
        }
        assertEquals(DiffStats(1, 1), ToolBodies.stats(ToolKind.EDIT, null, patch))
        assertEquals(DiffStats(0, 0), ToolBodies.stats(ToolKind.CREATE, buildJsonObject { put("content", "") }, null))
        assertNull(ToolBodies.stats(ToolKind.READ, null, patch))
    }
}

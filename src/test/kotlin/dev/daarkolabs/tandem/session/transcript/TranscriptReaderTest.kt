package dev.daarkolabs.tandem.session.transcript

import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import dev.daarkolabs.tandem.session.settled
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.bufferedWriter
import kotlin.io.path.fileSize
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptReaderTest {
    private fun ChatState.userTexts(): List<String> = entries.filterIsInstance<ChatEntry.User>().map { it.text }

    private fun ChatState.assistantTexts(): List<String> =
        entries.filterIsInstance<ChatEntry.Assistant>().flatMap { it.blocks }.filterIsInstance<AssistantBlock.Text>().map { it.text }

    /** The entries as short strings, so one assertion can state a whole history. */
    private fun ChatState.shape(): List<String> = entries.map { entry ->
        when (entry) {
            is ChatEntry.User -> "user: ${entry.text}"
            is ChatEntry.Assistant -> "assistant: " + entry.blocks.joinToString { block ->
                when (block) {
                    is AssistantBlock.Text -> block.text
                    is AssistantBlock.ToolUse -> "tool ${block.name}"
                    is AssistantBlock.Thinking -> "thinking"
                    is AssistantBlock.Other -> block.type
                }
            }
            is ChatEntry.ToolResult -> "result: ${entry.text}" + if (entry.isError) " (error)" else ""
            is ChatEntry.Notice -> "${entry.level}: ${entry.text}"
        }
    }

    private fun Path.writeLines(vararg lines: String) = writeText(lines.joinToString("\n", postfix = "\n"))

    private fun emptyMeta(id: String, cwd: String? = null, lineCount: Int = 0) =
        TranscriptMeta(id, null, null, null, null, null, null, cwd, lineCount)

    @Test
    fun `loads the same conversation as the stream fixture`() = runBlocking<Unit> {
        val streamed = Fixtures.decode("history-permission").fold(ChatState()) { s, m -> reduce(s, SessionEvent.Cli(m)) }
        val loaded = TranscriptReader.read(Fixtures.transcript("history-permission"))
        assertEquals(
            streamed.entries.filterIsInstance<ChatEntry.Assistant>().map { it.blocks },
            loaded.state.entries.filterIsInstance<ChatEntry.Assistant>().map { it.blocks },
        )
        assertEquals(
            streamed.entries.filterIsInstance<ChatEntry.ToolResult>().map { it.text to it.isError },
            loaded.state.entries.filterIsInstance<ChatEntry.ToolResult>().map { it.text to it.isError },
        )
        assertEquals(2, loaded.state.entries.count { it is ChatEntry.User }) // the prompts the driver typed
        assertFalse(loaded.state.busy)
        assertEquals("claude-sonnet-5-5", loaded.meta.lastModel)
        // history-reply was recorded with --effort high, which its assistant lines carry as "effort":"high".
        assertEquals("high", TranscriptReader.meta(Fixtures.transcript("history-reply")).lastEffort)
    }

    @Test
    fun `title precedence`(@TempDir dir: Path) {
        val f = dir.resolve("11111111-1111-4111-8111-111111111111.jsonl")
        f.writeText("""{"type":"user","uuid":"u1","permissionMode":"plan","message":{"role":"user","content":"first   prompt\nsecond line"}}""" + "\n")
        assertEquals("first   prompt", TranscriptReader.meta(f).title)
        assertEquals(PermissionMode.PLAN, TranscriptReader.meta(f).lastPermissionMode)
        f.appendText("""{"type":"ai-title","aiTitle":"Generated"}""" + "\n")
        assertEquals("Generated", TranscriptReader.meta(f).title)
        f.appendText("""{"type":"summary","summary":"Later summary"}""" + "\n")
        assertEquals("Later summary", TranscriptReader.meta(f).title)
        f.appendText("""{"type":"custom-title","customTitle":"Mine"}""" + "\n")
        assertEquals("Mine", TranscriptReader.meta(f).title)
        f.appendText("""{"type":"ai-title","aiTitle":"Newer generated"}""" + "\n")
        assertEquals("Mine", TranscriptReader.meta(f).title)
    }

    @Test
    fun `a blank title line does not count`(@TempDir dir: Path) {
        val f = dir.resolve("11111111-1111-4111-8111-111111111112.jsonl")
        f.writeLines(
            """{"type":"custom-title","customTitle":"Mine"}""",
            """{"type":"custom-title","customTitle":"  "}""",
            """{"type":"ai-title","aiTitle":""}""",
        )
        val meta = TranscriptReader.meta(f)
        assertEquals("Mine", meta.title)
        assertNull(meta.generatedTitle)
    }

    @Test
    fun `empty transcript falls back to the id and an 81-char prompt is trimmed to 80`(@TempDir dir: Path) = runBlocking<Unit> {
        val id = "22222222-2222-4222-8222-222222222222"
        val f = dir.resolve("$id.jsonl")
        f.writeText("")
        val empty = TranscriptReader.read(f)
        assertEquals(emptyMeta(id), empty.meta)
        assertEquals(id, empty.meta.title)
        assertEquals(ChatState(sessionId = id), empty.state)
        assertEquals(empty.meta, TranscriptReader.meta(f))

        // Leading blank lines and surrounding spaces go, and only the first line is kept, cut to 80 characters.
        val prompt = "a".repeat(81)
        f.writeLines("""{"type":"user","uuid":"u1","message":{"role":"user","content":"\n  $prompt  \nsecond line"}}""")
        assertEquals("a".repeat(80), TranscriptReader.meta(f).title)
        assertEquals("a".repeat(80), TranscriptReader.meta(f).firstPrompt)
        // The history keeps the prompt whole.
        assertEquals(listOf("\n  $prompt  \nsecond line"), TranscriptReader.read(f).state.userTexts())

        // A character outside the basic plane (two UTF-16 units) is never cut in half.
        f.writeLines("""{"type":"user","uuid":"u1","message":{"role":"user","content":"${"a".repeat(79)}\uD83D\uDE00"}}""")
        assertEquals("a".repeat(79), TranscriptReader.meta(f).title)
    }

    @Test
    fun `meta and sidechain lines are skipped`(@TempDir dir: Path) = runBlocking<Unit> {
        val id = "33333333-3333-4333-8333-333333333333"
        val f = dir.resolve("$id.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"m1","isMeta":true,"permissionMode":"plan","cwd":"/work","message":{"role":"user","content":"Injected by the CLI"}}""",
            """{"type":"user","uuid":"s1","isSidechain":true,"permissionMode":"plan","message":{"role":"user","content":[{"type":"text","text":"subagent task"},{"type":"tool_result","tool_use_id":"toolu_s","content":"subagent result"}]}}""",
            """{"type":"assistant","uuid":"s2","isSidechain":true,"effort":"low","message":{"id":"msg_s","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"subagent reply"}]}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(emptyList(), loaded.state.entries)
        // The cwd still comes from the first line that has one, skipped or not.
        assertEquals(emptyMeta(id, cwd = "/work", lineCount = 3), loaded.meta)
        assertEquals(id, loaded.meta.title)
        assertNull(loaded.state.permissionMode)
        assertNull(loaded.state.effort)
        assertEquals(loaded.meta, TranscriptReader.meta(f))
    }

    @Test
    fun `unknown types, non-object and malformed lines are skipped`(@TempDir dir: Path) = runBlocking<Unit> {
        val id = "44444444-4444-4444-8444-444444444444"
        val f = dir.resolve("$id.jsonl")
        f.writeText(
            listOf(
                "not json",
                "[1]",
                """{"type":"attachment"}""",
                "",
                """{"type":"future-type","message":{"role":"user","content":"not a prompt"}}""",
                // Cut off, as when the CLI is still writing the last line.
                """{"type":"assistant","uuid":"a1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"po""",
            ).joinToString("\n"),
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(emptyList(), loaded.state.entries)
        assertEquals(emptyMeta(id, lineCount = 6), loaded.meta)
        assertEquals(loaded.meta, TranscriptReader.meta(f))
    }

    @Test
    fun `a stray invalid byte does not cut the history short`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("44444444-4444-4444-8444-444444444445.jsonl")
        // 0xC3 starts a two-byte UTF-8 sequence, but a quote follows it.
        val broken = """{"type":"user","uuid":"u1","message":{"role":"user","content":"caf""".toByteArray() +
            0xC3.toByte() + "\"}}\n".toByteArray()
        val next = """{"type":"user","uuid":"u2","parentUuid":"u1","message":{"role":"user","content":"after"}}""".toByteArray() +
            "\n".toByteArray()
        f.writeBytes(broken + next)
        assertEquals(listOf("caf\uFFFD", "after"), TranscriptReader.read(f).state.userTexts())
    }

    @Test
    fun `a transcript that ends mid-turn loads settled`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("55555555-5555-4555-8555-555555555555.jsonl")
        // The process stopped right after asking for a tool: a tool_use with no result after it.
        f.writeLines(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"List the files"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Listing them."},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}]}}""",
        )
        val state = TranscriptReader.read(f).state
        assertFalse(state.busy)
        assertFalse(state.ended)
        assertTrue(state.pendingPermissions.isEmpty())
        assertTrue(state.entries.filterIsInstance<ChatEntry.Assistant>().none { it.streaming })
        assertIs<AssistantBlock.ToolUse>(state.entries.filterIsInstance<ChatEntry.Assistant>().last().blocks.last())
    }

    @Test
    fun `a prompt that shares a line with a tool result comes after the result`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("66666666-6666-4666-8666-666666666666.jsonl")
        // The API puts the tool_result blocks of a user message first and any text after them.
        f.writeLines(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"file.txt","is_error":false},{"type":"text","text":"also check"},{"type":"text","text":"the docs"}]}}""",
        )
        assertEquals(
            listOf(ChatEntry.ToolResult("result-toolu_1", "toolu_1", "file.txt", false), ChatEntry.User("u1", "also check\nthe docs")),
            TranscriptReader.read(f).state.entries,
        )
    }

    @Test
    fun `an interrupt marker loads as the Stopped notice and is never a prompt`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("12121212-1212-4121-8121-121212121212.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"Write a long story"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Once upon"}]}}""",
            """{"type":"user","uuid":"u2","parentUuid":"a1","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}""",
            """{"type":"user","uuid":"u3","parentUuid":"u2","message":{"role":"user","content":"List the files instead"}}""",
            """{"type":"assistant","uuid":"a3","parentUuid":"u3","message":{"id":"msg_2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}]}}""",
            // A tool result and the marker on one line are delivered once, so the result is not shown twice.
            """{"type":"user","uuid":"u4","parentUuid":"a3","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"Interrupted.","is_error":true},{"type":"text","text":"[Request interrupted by user for tool use]"}]}}""",
        )
        assertEquals(
            listOf(
                "user: Write a long story",
                "assistant: Once upon",
                "INFO: Stopped.",
                "user: List the files instead",
                "assistant: tool Bash",
                "result: Interrupted. (error)",
                "INFO: Stopped.",
            ),
            TranscriptReader.read(f).state.shape(),
        )

        // The marker never stands in for the title either.
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"[Request interrupted by user]"}}""",
            """{"type":"user","uuid":"u2","parentUuid":"u1","message":{"role":"user","content":"The real first prompt"}}""",
        )
        assertEquals("The real first prompt", TranscriptReader.meta(f).firstPrompt)
    }

    @Test
    fun `a recorded interrupt loads as the Stopped notice and not as a prompt`() = runBlocking<Unit> {
        // CLI 2.1.301 cut the reply off after its first text delta and wrote its marker the way the test above does:
        // a user line whose content is [{"type":"text","text":"[Request interrupted by user]"}], parented to the reply.
        val loaded = TranscriptReader.read(Fixtures.transcript("history-interrupt"))
        assertEquals(
            listOf("user: Count from 1 to 300, one number per line, nothing else.", "assistant: 1", "INFO: Stopped."),
            loaded.state.shape(),
        )
        assertFalse(loaded.state.busy)
        assertEquals("Count from 1 to 300, one number per line, nothing else.", loaded.meta.firstPrompt)
    }

    @Test
    fun `a compact summary is skipped entirely`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("13131313-1313-4131-8131-131313131313.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"c1","isCompactSummary":true,"isVisibleInTranscriptOnly":true,"permissionMode":"plan","message":{"role":"user","content":"This session is being continued from a previous conversation that ran out of context."}}""",
            """{"type":"user","uuid":"u1","parentUuid":"c1","message":{"role":"user","content":"Now run the tests"}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: Now run the tests"), loaded.state.shape())
        assertEquals("Now run the tests", loaded.meta.firstPrompt)
        assertNull(loaded.meta.lastPermissionMode)
    }

    @Test
    fun `a slash command loads as the command and its local output is skipped`(@TempDir dir: Path) = runBlocking<Unit> {
        val id = "14141414-1414-4141-8141-141414141414"
        val f = dir.resolve("$id.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"m1","parentUuid":null,"isMeta":true,"message":{"role":"user","content":"<local-command-caveat>Caveat: generated by local commands.</local-command-caveat>"}}""",
            """{"type":"user","uuid":"c1","parentUuid":"m1","message":{"role":"user","content":"<command-name>/model</command-name>\n            <command-message>model</command-message>\n            <command-args>opus</command-args>"}}""",
            """{"type":"user","uuid":"o1","parentUuid":"c1","message":{"role":"user","content":"<local-command-stdout>Set model to opus</local-command-stdout>"}}""",
            """{"type":"user","uuid":"c2","parentUuid":"o1","message":{"role":"user","content":[{"type":"text","text":"<command-name>/clear</command-name>\n<command-message>clear</command-message>\n<command-args></command-args>"}]}}""",
            """{"type":"user","uuid":"o2","parentUuid":"c2","message":{"role":"user","content":"<local-command-stdout></local-command-stdout>"}}""",
            // The CLI also writes the message tag first, and counts a text that starts with either tag as a command.
            """{"type":"user","uuid":"c3","parentUuid":"o2","message":{"role":"user","content":"<command-message>review</command-message>\n<command-name>/review</command-name>\n<command-args>src/</command-args>"}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: /model opus", "user: /clear", "user: /review src/"), loaded.state.shape())
        // A command does not stand in for the title.
        assertNull(loaded.meta.firstPrompt)
        assertEquals(id, loaded.meta.title)

        // The first prompt is the first text that is not a command.
        f.writeLines(
            """{"type":"user","uuid":"c1","parentUuid":null,"message":{"role":"user","content":"<command-name>/clear</command-name>\n<command-message>clear</command-message>\n<command-args></command-args>"}}""",
            """{"type":"user","uuid":"q1","parentUuid":"c1","message":{"role":"user","content":"real question"}}""",
        )
        val next = TranscriptReader.read(f)
        assertEquals(listOf("user: /clear", "user: real question"), next.state.shape())
        assertEquals("real question", next.meta.firstPrompt)
        assertEquals("real question", next.meta.title)
    }

    @Test
    fun `a synthetic assistant line changes neither the last model nor the last effort`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("15151515-1515-4151-8151-151515151515.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hi"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","effort":"high","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"hello"}]}}""",
            """{"type":"user","uuid":"u2","parentUuid":"a1","message":{"role":"user","content":"again"}}""",
            // Written by the CLI itself, e.g. for an API error: a model that is not one, and no effort.
            """{"type":"assistant","uuid":"a2","parentUuid":"u2","isApiErrorMessage":true,"message":{"id":"msg_2","role":"assistant","model":"<synthetic>","content":[{"type":"text","text":"API Error: 500"}]}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals("claude-sonnet-5-5", loaded.meta.lastModel)
        assertEquals("high", loaded.meta.lastEffort)
        assertEquals("high", loaded.state.effort)
        // The model the session shows is the last real one too.
        assertEquals("claude-sonnet-5-5", loaded.state.activeModel)
        // Still part of the history, as it is live.
        assertEquals(listOf("hello", "API Error: 500"), loaded.state.assistantTexts())
    }

    @Test
    fun `a rewind leaves the abandoned turns out`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("16161616-1616-4161-8161-161616161616.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"user","uuid":"u2","parentUuid":"a1","permissionMode":"plan","message":{"role":"user","content":"abandoned"}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"u2","effort":"low","message":{"id":"msg_2","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"tool_use","id":"t9","name":"Bash","input":{"command":"ls"}}]}}""",
            """{"type":"user","uuid":"r9","parentUuid":"a2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t9","content":"abandoned result"}]}}""",
            // Rewound to a1 in the terminal: the next prompt continues from there, and u2, a2 and r9 stay in the file.
            """{"type":"user","uuid":"u3","parentUuid":"a1","message":{"role":"user","content":"second"}}""",
            // A side branch with a message id of its own stays out, although its parent is kept.
            """{"type":"assistant","uuid":"a3x","parentUuid":"u3","message":{"id":"msg_3x","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"cut short"}]}}""",
            """{"type":"assistant","uuid":"a3","parentUuid":"u3","message":{"id":"msg_3","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"two"}]}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: first", "assistant: one", "user: second", "assistant: two"), loaded.state.shape())
        assertEquals(8, loaded.meta.lineCount)
        // Nothing of the abandoned turn counts, not even its permission mode or effort.
        assertNull(loaded.meta.lastPermissionMode)
        assertNull(loaded.meta.lastEffort)
        assertEquals(loaded.meta, TranscriptReader.meta(f))
    }

    @Test
    fun `each part before a compaction keeps its own current chain`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("17171717-1717-4171-8171-171717171717.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"user","uuid":"ux","parentUuid":"a1","message":{"role":"user","content":"abandoned"}}""",
            """{"type":"user","uuid":"uy","parentUuid":"a1","message":{"role":"user","content":"first again"}}""",
            // The compaction: a boundary that roots a new chain, then the summary the CLI hands the model.
            """{"type":"system","subtype":"compact_boundary","uuid":"b1","parentUuid":null,"logicalParentUuid":"uy","content":"Conversation compacted"}""",
            """{"type":"user","uuid":"s1","parentUuid":"b1","isCompactSummary":true,"message":{"role":"user","content":"This session is being continued from a previous conversation."}}""",
            """{"type":"user","uuid":"u2","parentUuid":"s1","message":{"role":"user","content":"second"}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"u2","message":{"id":"msg_2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"two"}]}}""",
            // A compact summary can root the new chain itself.
            """{"type":"user","uuid":"s2","parentUuid":null,"isCompactSummary":true,"message":{"role":"user","content":"This session is being continued again."}}""",
            """{"type":"user","uuid":"u3","parentUuid":"s2","message":{"role":"user","content":"third"}}""",
        )
        assertEquals(
            listOf("user: first", "assistant: one", "user: first again", "user: second", "assistant: two", "user: third"),
            TranscriptReader.read(f).state.shape(),
        )
    }

    @Test
    fun `parallel tool calls off the chain stay in the history`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("21212121-2121-4212-8212-212121212121.jsonl")
        // Two tool calls in one message: each block has its own line, and the results branch off the blocks they answer.
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"Read both files"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"a.txt"}}]}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"a1","message":{"id":"m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"b.txt"}}]}}""",
            """{"type":"user","uuid":"r2","parentUuid":"a2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"bee"}]}}""",
            """{"type":"user","uuid":"r1","parentUuid":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ay"}]}}""",
            """{"type":"assistant","uuid":"a3","parentUuid":"r1","message":{"id":"m2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Both read."}]}}""",
        )
        assertEquals(
            listOf("user: Read both files", "assistant: tool Read, tool Read", "result: bee", "result: ay", "assistant: Both read."),
            TranscriptReader.read(f).state.shape(),
        )
    }

    @Test
    fun `the chain runs through attachment and system lines`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("23232323-2323-4232-8232-232323232323.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"attachment","uuid":"x1","parentUuid":"u1","attachment":{"type":"date"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"x1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"system","subtype":"informational","uuid":"y1","parentUuid":"a1","content":"Noted"}""",
            """{"type":"user","uuid":"u2","parentUuid":"y1","message":{"role":"user","content":"second"}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"u2","message":{"id":"msg_2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"two"}]}}""",
        )
        assertEquals(listOf("user: first", "assistant: one", "user: second", "assistant: two"), TranscriptReader.read(f).state.shape())
    }

    @Test
    fun `a conversational line without a uuid stays in the history`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("24242424-2424-4242-8242-242424242424.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"user","message":{"role":"user","content":"no uuid"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
        )
        // No chain can reach it, so the walk keeps it rather than drop it; its entry id falls back to the line number.
        val entries = TranscriptReader.read(f).state.entries
        assertEquals(listOf("u1", "line-2", "msg_1"), entries.map { it.id })
    }

    @Test
    fun `a prompt that quotes a CLI tag further on stays a prompt`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("18181818-1818-4181-8181-181818181818.jsonl")
        val prompt = "Explain <command-name>/model</command-name> and <bash-stdout> in transcripts"
        f.writeLines("""{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"$prompt"}}""")
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: $prompt"), loaded.state.shape())
        assertEquals(prompt, loaded.meta.firstPrompt)
    }

    @Test
    fun `a shell escape loads as its command, and output and notifications are skipped`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("19191919-1919-4191-8191-191919191919.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"b1","parentUuid":null,"message":{"role":"user","content":"<bash-input>ls -la</bash-input>"}}""",
            """{"type":"user","uuid":"b2","parentUuid":"b1","message":{"role":"user","content":"<bash-stdout>notes.txt</bash-stdout><bash-stderr></bash-stderr>"}}""",
            """{"type":"user","uuid":"b3","parentUuid":"b2","message":{"role":"user","content":"<bash-stderr>Command failed: cat nothing.txt</bash-stderr>"}}""",
            """{"type":"user","uuid":"c1","parentUuid":"b3","message":{"role":"user","content":"<local-command-stderr>Unknown command: /nope</local-command-stderr>"}}""",
            """{"type":"user","uuid":"n1","parentUuid":"c1","message":{"role":"user","content":"<task-notification>\n<task-id>bg-1</task-id>\n<status>completed</status>\n</task-notification>"}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: !ls -la"), loaded.state.shape())
        assertEquals("!ls -la", loaded.meta.firstPrompt)
    }

    @Test
    fun `a loaded history followed by a live turn keeps ids unique and toggles busy`() = runBlocking<Unit> {
        var state = TranscriptReader.read(Fixtures.transcript("history-reply")).state
        assertEquals(listOf("Reply with the single word: pong"), state.userTexts())
        assertEquals(listOf("pong"), state.assistantTexts())
        state = reduce(state, SessionEvent.UserSent("live-u1", "again"))
        assertTrue(state.busy)
        state = reduce(state, SessionEvent.Cli(Ndjson.decode("""{"type":"assistant","uuid":"live-a1","message":{"id":"live-m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"pong again"}]}}""")))
        state = reduce(state, SessionEvent.Cli(Ndjson.decode("""{"type":"result","subtype":"success","is_error":false,"session_id":"x"}""")))
        assertFalse(state.busy)
        assertEquals(state.entries.size, state.entries.map { it.id }.toSet().size)
        assertEquals(listOf("pong", "pong again"), state.assistantTexts())
    }

    @Test
    fun `recorded transcripts give their title, last model, effort, permission mode and folder`() = runBlocking<Unit> {
        val reply = TranscriptReader.read(Fixtures.transcript("history-reply"))
        assertEquals(
            TranscriptMeta(
                sessionId = "history-reply", // the fixture's file name stands in for <session id>.jsonl
                customTitle = null,
                generatedTitle = "Pong",
                firstPrompt = "Reply with the single word: pong",
                lastModel = "claude-sonnet-5-5",
                lastEffort = "high",
                lastPermissionMode = PermissionMode.DEFAULT,
                cwd = "<path>", // as redacted by the recorder
                lineCount = 23,
            ),
            reply.meta,
        )
        assertEquals("Pong", reply.meta.title)
        // A resumed session knows its id before init arrives: the transcript's file name.
        assertEquals("history-reply", reply.state.sessionId)
        assertEquals(reply.meta, TranscriptReader.meta(Fixtures.transcript("history-reply")))
        // A reopened session shows the effort and permission mode its transcript last recorded.
        assertEquals("high", reply.state.effort)
        assertEquals(PermissionMode.DEFAULT, reply.state.permissionMode)

        val permission = TranscriptReader.meta(Fixtures.transcript("history-permission"))
        assertEquals("Touch tandem-fixture.txt file", permission.title)
        // The first prompt as recorded is 82 characters long.
        assertEquals("Use the Bash tool to run exactly: touch tandem-fixture.txt. Then reply with: don", permission.firstPrompt)
        assertEquals(35, permission.lineCount)

        // The host renamed the original session; its custom-title beats the generated one, and the fork carries it over.
        val origin = TranscriptReader.meta(Fixtures.transcript("fork-origin"))
        assertEquals("Renamed by host", origin.customTitle)
        assertEquals("Alpha reply", origin.generatedTitle)
        assertEquals("Renamed by host", origin.title)
        val fork = TranscriptReader.read(Fixtures.transcript("fork"))
        assertEquals("Renamed by host", fork.meta.title)
        assertEquals(listOf("Reply with the single word: alpha", "Reply with the single word: beta"), fork.state.userTexts())
        assertEquals(listOf("alpha", "beta"), fork.state.assistantTexts())
    }

    @Test
    fun `reads a large transcript in linear time`(@TempDir dir: Path) = runBlocking<Unit> {
        // Long sessions are what the sidebar is for: 4,000 turns of lines shaped like the CLI's, about 2.8 MB.
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val f = dir.resolve("$id.jsonl")
        f.bufferedWriter().use { out ->
            for (n in 1..4_000) {
                out.write("""{"parentUuid":"a-${n - 1}","isSidechain":false,"type":"user","message":{"role":"user","content":"prompt $n"},"uuid":"u-$n","timestamp":"2026-10-16T12:00:00.000Z","permissionMode":"default","cwd":"/work/app","sessionId":"$id"}""" + "\n")
                out.write("""{"parentUuid":"u-$n","isSidechain":false,"message":{"model":"claude-sonnet-5-5","id":"m-$n","type":"message","role":"assistant","content":[{"type":"text","text":"reply $n"}],"stop_reason":"end_turn","usage":{"input_tokens":2,"output_tokens":3}},"requestId":"req-$n","type":"assistant","uuid":"a-$n","timestamp":"2026-10-16T12:00:01.000Z","effort":"medium","cwd":"/work/app","sessionId":"$id"}""" + "\n")
            }
            out.write("""{"type":"custom-title","customTitle":"Long session","sessionId":"$id"}""" + "\n")
        }
        assertTrue(f.fileSize() in 2_000_000L..3_000_000L, "generated ${f.fileSize()} bytes")

        val loaded: LoadedTranscript
        val millis = measureTimeMillis { loaded = TranscriptReader.read(f) }
        assertEquals(8_000, loaded.state.entries.size)
        assertEquals(listOf("u-4000", "m-4000"), loaded.state.entries.takeLast(2).map { it.id })
        assertEquals("Long session", loaded.meta.title)
        assertTrue(millis < 5_000, "reading took $millis ms")
    }

    @Test
    fun `a file that cannot be opened makes read throw and meta fall back to the id`(@TempDir dir: Path) {
        val id = "77777777-7777-4777-8777-777777777777"
        val missing = dir.resolve("$id.jsonl")
        assertFailsWith<IOException> { runBlocking { TranscriptReader.read(missing) } }
        val meta = TranscriptReader.meta(missing)
        assertEquals(emptyMeta(id), meta)
        assertEquals(id, meta.title)
    }

    @Test
    fun `an IOException in the first pass shows nothing, in the second what was parsed`() = runBlocking<Unit> {
        val id = "88888888-8888-4888-8888-888888888888"
        val lines = listOf(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hello"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"hi"}]}}""",
            """{"type":"user","uuid":"u2","parentUuid":"a1","message":{"role":"user","content":"and then?"}}""",
        )

        fun reader(failAfter: Int = Int.MAX_VALUE) = object : BufferedReader(StringReader(lines.joinToString("\n"))) {
            private var calls = 0

            override fun readLine(): String? = if (++calls > failAfter) throw IOException("device gone") else super.readLine()
        }

        // In the first pass: nothing to show, and the meta only says how far reading got.
        val first = TranscriptReader.readFrom({ reader(failAfter = 2) }, id)
        assertEquals(emptyList(), first.state.entries)
        assertEquals(emptyMeta(id, lineCount = 2), first.meta)

        // In the second pass: what was parsed before it.
        var opened = 0
        val second = TranscriptReader.readFrom({ if (++opened == 1) reader() else reader(failAfter = 2) }, id)
        assertEquals(listOf("u1", "msg_1"), second.state.entries.map { it.id })
        assertFalse(second.state.busy)
        assertEquals(2, second.meta.lineCount)
        assertEquals("claude-sonnet-5-5", second.meta.lastModel)

        // The file cannot be opened again for the second pass: nothing was parsed.
        opened = 0
        val reopened = TranscriptReader.readFrom({ if (++opened == 1) reader() else throw IOException("gone") }, id)
        assertEquals(emptyList(), reopened.state.entries)
        assertEquals(emptyMeta(id), reopened.meta)
    }

    @Test
    fun `lines appended between the two passes wait for the next read`() = runBlocking<Unit> {
        val first = """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hello"}}"""
        val appended = """{"type":"user","uuid":"u2","parentUuid":"u1","message":{"role":"user","content":"written meanwhile"}}"""
        var opened = 0
        val loaded = TranscriptReader.readFrom(
            { BufferedReader(StringReader(if (++opened == 1) first else "$first\n$appended")) },
            "20202020-2020-4202-8202-202020202020",
        )
        // The second pass reads only the lines the first pass placed.
        assertEquals(listOf("user: hello"), loaded.state.shape())
        assertEquals(1, loaded.meta.lineCount)
    }

    @Test
    fun `read checks for cancellation before every line`() = runBlocking<Unit> {
        var linesRead = 0
        val reading = launch {
            val self = coroutineContext.job
            val endless = object : BufferedReader(StringReader("")) {
                override fun readLine(): String? {
                    linesRead++
                    if (linesRead == 2) self.cancel()
                    // Ends after 100 lines, so a reader that ignored cancellation fails the test instead of hanging it.
                    return if (linesRead > 100) null else """{"type":"queue-operation"}"""
                }
            }
            TranscriptReader.readFrom({ endless }, "99999999-9999-4999-8999-999999999999")
        }
        reading.join()
        assertTrue(reading.isCancelled)
        assertEquals(2, linesRead)
    }

    @Test
    fun `settled ends a turn in progress`() {
        val draft = ChatEntry.Assistant(
            id = "msg_1",
            messageId = "msg_1",
            parentToolUseId = null,
            model = "claude-sonnet-5-5",
            blocks = listOf(AssistantBlock.Text("Hal")),
            completedBlocks = 0,
            streaming = true,
            uuids = emptySet(),
        )
        val ask = PendingPermission("req_1", "Bash", JsonObject(emptyMap()), "toolu_1", null, emptyList())
        val running = ChatState(
            entries = listOf(ChatEntry.User("u1", "hi"), draft),
            activeModel = "claude-sonnet-5-5",
            busy = true,
            pendingPermissions = listOf(ask),
            ended = true,
        )
        assertEquals(
            running.copy(
                entries = listOf(ChatEntry.User("u1", "hi"), draft.copy(streaming = false)),
                busy = false,
                pendingPermissions = emptyList(),
                ended = false,
            ),
            running.settled(),
        )
    }
}

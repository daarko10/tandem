package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.Logger
import dev.daarkolabs.tandem.protocol.BashEscape
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.ImageBlock
import dev.daarkolabs.tandem.protocol.McpConfig
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.session.transcript.LoadedTranscript
import dev.daarkolabs.tandem.session.transcript.SessionIndex
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import dev.daarkolabs.tandem.session.transcript.TranscriptFiles
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [SessionManager] against the fake CLI (`src/test/resources/fake-claude.py`): every session runs a real child process that
 * replays a recorded fixture, and the fake's log shows how each process was started and every frame the host wrote to it.
 */
class SessionManagerTest {
    @TempDir
    lateinit var dir: Path

    private val fakeClaude: String = System.getProperty("tandem.claudePath") ?: "src/test/resources/fake-claude.py"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val ownProcesses = OwnFakeCliProcesses()

    /** A session with a recorded transcript (`history-reply`, whose own title is "Pong"). */
    private val seeded = "44444444-4444-4444-8444-444444444444"

    /** Another one, for tests that need two. */
    private val other = "55555555-5555-4555-8555-555555555555"

    private val log: Path get() = dir.resolve("fake-cli.log")
    private val folder: Path get() = dir.resolve("transcripts")

    @BeforeEach
    fun createFolder() {
        folder.createDirectories()
    }

    @AfterEach
    fun cancelScope(): Unit = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
        // Cancelling the manager's scope ends every process it started.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (ownFakeProcesses().isNotEmpty() && System.nanoTime() < deadline) delay(20)
        val leaked = ownFakeProcesses()
        leaked.forEach { it.destroyForcibly() } // so that the next test starts clean
        assertEquals(emptyList(), leaked.map { it.pid() }, "fake CLI processes outlived the manager's scope")
    }

    private fun manager(
        fixtures: String = "plain-reply",
        delayMs: Int? = null,
        binary: String? = fakeClaude,
        lookup: () -> String? = { binary },
        clock: () -> Long = System::currentTimeMillis,
        read: suspend (Path) -> LoadedTranscript = TranscriptReader::read,
        appendTitle: (Path, String, String, Instant) -> Unit = TranscriptFiles::appendCustomTitle,
        deleteFiles: (Path) -> Unit = TranscriptFiles::delete,
        maxLive: Int = 4,
        /** More variables for every process, such as `TANDEM_FAKE_SESSION_ID`. */
        environment: Map<String, String> = emptyMap(),
        mcpAuthEvery: Duration = 2.seconds,
        mcpAuthTimeout: suspend () -> Unit = { delay(3.minutes) },
        respondToShell: () -> Boolean = { true },
        shell: ShellRunner = ShellRunner(environment = { mapOf("SHELL" to "/bin/sh") }),
        hostTools: HostTools? = null,
    ): SessionManager {
        val fake = buildMap {
            put("TANDEM_FAKE_LOG", log.toString())
            put("TANDEM_FAKE_FIXTURES", fixtures)
            delayMs?.let { put("TANDEM_FAKE_DELAY_MS", it.toString()) }
            putAll(environment)
        }
        val launcher = DefaultProcessLauncher { System.getenv() + fake }
        val index = SessionIndex({ listOf(folder) }, "/project")
        return SessionManager(
            scope,
            index,
            launcher,
            lookup,
            dir,
            maxLive = { maxLive },
            clock = clock,
            readTranscript = read,
            appendTitle = appendTitle,
            deleteFiles = deleteFiles,
            mcpAuthEvery = mcpAuthEvery,
            mcpAuthTimeout = mcpAuthTimeout,
            respondToShell = respondToShell,
            shell = shell,
            hostTools = hostTools,
            hostVersion = "0.4.0",
        )
    }

    private fun test(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { withTimeout(30_000, block) }

    /** Writes the [fixture] transcript (by default `history-reply`) as session [id]'s, through [edit]. */
    private fun seed(id: String, fixture: String = "history-reply", edit: (String) -> String = { it }) {
        folder.resolve("$id.jsonl").writeText(edit(Fixtures.transcript(fixture).readText()))
    }

    /** The complete lines of the fake's log: a `__spawn__` line per process, then each frame the host wrote to that process. */
    private fun frames(): List<JsonObject> {
        if (!log.exists()) return emptyList()
        // The last piece is empty, or a line the fake is still writing.
        return log.readText().split('\n').dropLast(1).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    }

    /** The arguments of every process started, in order. */
    private fun spawns(): List<List<String>> =
        frames().filter { it.type() == "__spawn__" }.map { spawn -> spawn["argv"]!!.jsonArray.map { it.jsonPrimitive.content } }

    /** The prompts the host wrote, in order. */
    private fun prompts(): List<String> = frames().filter { it.type() == "user" }.map {
        it["message"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
    }

    private fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.content

    /** The subtype of a control request the host wrote. */
    private fun JsonObject.subtype(): String? = this["request"]?.jsonObject?.get("subtype")?.jsonPrimitive?.content

    private fun List<String>.valueOf(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    private suspend fun awaitLog(condition: () -> Boolean) {
        while (!condition()) delay(20)
    }

    private suspend fun <T> StateFlow<T>.await(value: T): T = first { it == value }

    private fun ChatState.replyText(): String =
        entries.filterIsInstance<ChatEntry.Assistant>().last().blocks.filterIsInstance<AssistantBlock.Text>().joinToString("") { it.text }

    private fun ChatState.prompts(): List<String> = entries.filterIsInstance<ChatEntry.User>().map { it.text }

    private fun ownFakeProcesses(): List<ProcessHandle> = ownProcesses.list()

    @Test
    fun `create spawns with --session-id and the options`() = test {
        val manager = manager()
        val handle = manager.create(NewSessionOptions(model = "opus", effort = "high", permissionMode = PermissionMode.ACCEPT_EDITS))
        assertEquals(handle.id, UUID.fromString(handle.id).toString(), "a new session's id is a lower-case UUID")
        assertEquals("high", handle.state.value.effort)
        assertEquals(listOf(handle), manager.sessions.value)
        assertNull(manager.selected.value, "the caller selects a new session")

        awaitLog { spawns().isNotEmpty() }
        val argv = spawns().single()
        assertEquals(handle.id, argv.valueOf("--session-id"))
        assertFalse("--resume" in argv)
        assertEquals("opus", argv.valueOf("--model"))
        assertEquals("high", argv.valueOf("--effort"))
        assertEquals("acceptEdits", argv.valueOf("--permission-mode"))
    }

    @Test
    fun `open loads the history before the process answers`() = test {
        seed(seeded)
        val manager = manager()
        val handle = manager.open(seeded)
        val state = handle.state.value
        assertEquals(listOf("Reply with the single word: pong"), state.prompts())
        assertEquals("pong", state.replyText())
        assertNull(state.claudeCodeVersion, "nothing from the process yet: the fake answers only after a prompt")

        awaitLog { spawns().isNotEmpty() }
        val argv = spawns().single()
        assertEquals(seeded, argv.valueOf("--resume"))
        assertFalse("--session-id" in argv)
        assertSame(handle, manager.open(seeded), "an open session is opened once")
        assertEquals(listOf(handle), manager.sessions.value)
    }

    @Test
    fun `a resumed session starts without --model, so the CLI runs the session's own`() = test {
        seed(seeded) // its assistant line ran claude-sonnet-5-5 and carries it as requestedModel too
        val manager = manager()
        val handle = manager.open(seeded)
        awaitLog { spawns().isNotEmpty() }
        val argv = spawns().single()
        assertEquals(seeded, argv.valueOf("--resume"))
        // Measured with 2.1.301 (docs/protocol.md 8): `--resume` without `--model` runs the session's last model, not the default.
        assertFalse("--model" in argv, argv.toString())
        assertNull(handle.state.value.requestedModel, "nothing was picked")
        assertEquals("claude-sonnet-5-5", handle.state.value.activeModel)
    }

    @Test
    fun `a session without a process offers the models the last process listed, else the remembered ones`() = test {
        seed(seeded)
        val manager = manager(fixtures = "agent-sync")
        val remembered = listOf(ModelOption("opus", "Opus", null, "claude-opus-5-5"))
        manager.seedModels(remembered)
        assertEquals(remembered, manager.models.value, "until a process lists them, the list an earlier IDE run remembered")

        // Restored with the project: no process, so no initialize response of its own.
        val resumed = manager.open(seeded, spawn = false)
        assertEquals(SessionStatus.STOPPED, resumed.status.value)
        assertEquals(emptyList(), resumed.state.value.models)
        assertEquals(remembered, manager.models.value)

        // Another session's process answers initialize (agent-sync's line 2) without a prompt: its list is every session's now.
        val live = manager.create(NewSessionOptions())
        val listed = live.state.first { it.models.isNotEmpty() }.models
        assertEquals(listed, manager.models.first { it == listed })
        assertEquals("Opus 5.5", listed.first { it.resolvedModel == "claude-opus-5-5" && it.value != "default" }.displayName)
        assertEquals(emptyList(), resumed.state.value.models, "the resumed session's own list stays its process's")
        assertEquals(1, spawns().size, "no process was started to read the list")

        manager.seedModels(remembered)
        assertEquals(listed, manager.models.value, "a remembered list never replaces one a process gave")
    }

    @Test
    fun `a session without a process offers the commands the last process offered, else the remembered ones`() = test {
        seed(seeded)
        val manager = manager(fixtures = "mcp-slash")
        val remembered = CommandCatalogue(listOf(SlashCommand("hello", "Hi (project)")), terminal = setOf("doctor", "old"))
        manager.seedCommands(remembered)
        assertEquals(remembered, manager.commands.value, "until a process offers them, the catalogue an earlier IDE run remembered")

        // Restored with the project: no process, so no initialize answer of its own.
        val resumed = manager.open(seeded, spawn = false)
        assertEquals(emptyList(), resumed.state.value.commands)

        // Another session's process answers initialize (mcp-slash:1) without a prompt: its commands are every session's now.
        val live = manager.create(NewSessionOptions())
        val offered = live.state.first { it.commands.isNotEmpty() }.commands
        assertEquals(73, offered.size)
        assertEquals(CommandCatalogue(offered, remembered.terminal), manager.commands.first { it.commands == offered })
        assertEquals(1, spawns().size, "no process was started to read them")

        // Its first init (with its first prompt) names the terminal-only ones, which replace the remembered names.
        manager.send(live.id, "/usage")
        val terminal = live.state.first { it.terminalCommands.isNotEmpty() }.terminalCommands
        assertEquals(setOf("doctor", "color", "focus", "reload-plugins"), terminal)
        assertEquals(terminal, manager.commands.first { it.terminal == terminal }.terminal)
        assertEquals(emptyList(), resumed.state.value.commands, "the resumed session's own catalogue stays its process's")

        manager.seedCommands(remembered)
        assertEquals(CommandCatalogue(offered, terminal), manager.commands.value, "a remembered catalogue never replaces one a process gave")
    }

    @Test
    fun `a transcript that cannot be read opens with a warning in place of the history`() = test {
        seed(seeded)
        // Run as root, a test cannot make a file unreadable, so the reader fails in its place.
        val manager = manager(read = { throw AccessDeniedException(it.toString()) })
        val handle = manager.open(seeded, spawn = false)
        val notice = handle.state.value.entries.single() as ChatEntry.Notice
        assertEquals(NoticeLevel.WARNING, notice.level)
        assertEquals("The session's history could not be read: AccessDeniedException", notice.text)
    }

    @Test
    fun `a transcript that fails while it is read opens with what was read and a warning`() = test {
        seed(seeded)
        val text = folder.resolve("$seeded.jsonl").readText()

        // Reads the transcript through readers that fail after their fifth line in pass [failing] (1 or 2) of the reader.
        fun failingIn(failing: Int): suspend (Path) -> LoadedTranscript = {
            var opened = 0
            TranscriptReader.readFrom(
                {
                    val failAfter = if (++opened == failing) 5 else Int.MAX_VALUE
                    object : BufferedReader(StringReader(text)) {
                        private var lines = 0

                        override fun readLine(): String? = if (++lines > failAfter) throw IOException("device gone") else super.readLine()
                    }
                },
                seeded,
            )
        }

        // The second pass had read the prompt (the transcript's third line) when it failed: that much shows, then the warning.
        val partly = manager(read = failingIn(2)).open(seeded, spawn = false).state.value
        assertEquals(listOf("Reply with the single word: pong"), partly.prompts())
        val warning = partly.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.WARNING, warning.level)
        assertEquals("The session's history was read only partly: IOException", warning.text)

        // A failure in the first pass leaves nothing to show but the warning.
        val nothing = manager(read = failingIn(1)).open(seeded, spawn = false).state.value
        val notice = nothing.entries.single() as ChatEntry.Notice
        assertEquals(NoticeLevel.WARNING, notice.level)
        assertEquals("The session's history could not be read: IOException", notice.text)
    }

    @Test
    fun `two quick prompts to a stopped session start one process`() = test {
        seed(seeded)
        val manager = manager()
        val handle = manager.open(seeded, spawn = false)
        assertEquals(SessionStatus.STOPPED, handle.status.value)
        assertSame(handle, manager.open(seeded))
        // A bounded wait for a wrong start to show in the log: no signal can say that no process will start.
        delay(300)
        assertEquals(emptyList(), spawns(), "an open session starts with its next prompt, not when it is opened again")

        manager.send(seeded, "Reply with the single word: pong")
        manager.send(seeded, "And once more")
        awaitLog { prompts().size == 2 }
        assertEquals(listOf("Reply with the single word: pong", "And once more"), prompts())
        // By the time the first prompt is answered, a second process would have been started.
        handle.state.first { s -> !s.busy && s.entries.filterIsInstance<ChatEntry.Assistant>().size == 2 }
        assertEquals(listOf<String?>(seeded), spawns().map { it.valueOf("--resume") })
        val history = "Reply with the single word: pong" // the seeded transcript's prompt
        // The second prompt reached the process while the first one's turn ran: it waits in the CLI's queue (spec 12.1), which the
        // fake never runs, until its lifecycle lines make it an entry.
        assertEquals(listOf(history, "Reply with the single word: pong"), handle.state.value.prompts())
        assertEquals(listOf("And once more"), handle.state.value.pendingSends.map { it.text })
    }

    @Test
    fun `send with images queues them with the prompt for a stopped session`() = test {
        seed(seeded)
        val manager = manager()
        val handle = manager.open(seeded, spawn = false)
        manager.send(handle.id, "see", listOf(ImageBlock("image/png", "AAAA")))
        awaitLog { frames().any { it.type() == "user" } }
        val content = frames().first { it.type() == "user" }["message"]!!.jsonObject["content"]!!.jsonArray
        assertEquals(
            listOf(
                """{"type":"text","text":"see"}""",
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}""",
            ),
            content.map { it.toString() },
        )
    }

    // The JVM takes no brackets in a method name: the spec's name is the display name.
    @Test
    @DisplayName("the live title after an images-only send is [Image #1]")
    fun `the live title after an images-only send is Image #1`() = test {
        val manager = manager()
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "", listOf(ImageBlock("image/png", "AAAA")))
        handle.title.await("[Image #1]")
        awaitLog { frames().any { it.type() == "user" } }
        val content = frames().first { it.type() == "user" }["message"]!!.jsonObject["content"]!!.jsonArray
        assertEquals(listOf("image"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content }, "no text block")
    }

    @Test
    fun `a session stopped before its first prompt starts again with --session-id`() = test {
        val manager = manager()
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.stop(handle.id)
        assertEquals(emptyList(), ownFakeProcesses(), "stop returns once the process is gone")

        manager.send(handle.id, "Reply with the single word: pong")
        handle.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant } }
        // The CLI writes the transcript with the first prompt, so there is nothing to resume yet.
        assertEquals(listOf<String?>(handle.id, handle.id), spawns().map { it.valueOf("--session-id") })
        assertTrue(spawns().none { "--resume" in it })
    }

    @Test
    fun `status follows the process`() = test {
        seed(seeded)
        // The fake waits this long before each permission request, so that the running turn can be seen.
        val manager = manager(fixtures = "permission", delayMs = 1_000)
        val handle = manager.open(seeded)
        handle.status.await(SessionStatus.IDLE) // a process is attached, and the fake waits for a prompt
        manager.send(seeded, "Create the fixture file")
        handle.status.await(SessionStatus.RUNNING)
        handle.status.await(SessionStatus.WAITING)
        manager.allow(seeded, handle.state.value.pendingPermissions.single().requestId, always = false)
        handle.status.await(SessionStatus.IDLE) // the turn's result
        manager.send(seeded, "Create it once more") // the fixture's second turn asks again
        handle.status.await(SessionStatus.WAITING)
        manager.stop(seeded)
        handle.status.await(SessionStatus.STOPPED)
        // Stopped in the middle of a turn: nothing in the chat stays running or waits for an answer.
        val stopped = handle.state.value
        assertFalse(stopped.busy)
        assertEquals(emptyList(), stopped.pendingPermissions)
        assertTrue(stopped.entries.none { it is ChatEntry.Assistant && it.streaming })
    }

    @Test
    fun `a failed spawn leaves the row stopped and the next prompt tries again`() = test {
        seed(seeded)
        // The fake ends with exit code 2 when it has no fixture of that name.
        val manager = manager(fixtures = "missing,plain-reply")
        val handle = manager.open(seeded, spawn = false)

        manager.send(seeded, "Reply with the single word: pong")
        val failed = handle.state.first { it.ended }
        val notice = failed.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertTrue("code 2" in notice.text, notice.text)
        handle.status.await(SessionStatus.STOPPED)
        assertEquals(listOf(handle), manager.sessions.value)

        manager.send(seeded, "Reply with the single word: pong")
        val answered = handle.state.first { s -> !s.busy && s.entries.lastOrNull { it !is ChatEntry.TurnEnded } is ChatEntry.Assistant }
        assertEquals("pong", answered.replyText())
        handle.status.await(SessionStatus.IDLE) // the new process starts from the settled chat, not from the ended one
        assertEquals(2, spawns().size)
    }

    @Test
    fun `picks on a stopped session apply at the next spawn`() = test {
        val manager = manager()
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.setModel(handle.id, "haiku") // a live process gets a control request
        awaitLog { frames().any { it.subtype() == "set_model" } }
        manager.stop(handle.id)

        manager.setModel(handle.id, "opus")
        manager.setPermissionMode(handle.id, PermissionMode.PLAN)
        assertEquals("opus", handle.state.value.requestedModel)
        assertEquals(PermissionMode.PLAN, handle.state.value.permissionMode)
        manager.send(handle.id, "Reply with the single word: pong")
        awaitLog { spawns().size == 2 }
        val argv = spawns().last()
        assertEquals("opus", argv.valueOf("--model"))
        assertEquals("plan", argv.valueOf("--permission-mode"))
        // The picks made while stopped went into the flags, not to a process.
        assertEquals(1, frames().count { it.subtype() == "set_model" })
        assertEquals(0, frames().count { it.subtype() == "set_permission_mode" })
    }

    /** A stand-in for `Modes.cycleFrom` (D13b): Manual → Accept edits → Plan → Manual, no mode yet counting as Manual. */
    private val step: (ChatState) -> PermissionMode = {
        when (it.permissionMode) {
            PermissionMode.ACCEPT_EDITS -> PermissionMode.PLAN
            PermissionMode.PLAN -> PermissionMode.DEFAULT
            else -> PermissionMode.ACCEPT_EDITS
        }
    }

    /** The modes of the `set_permission_mode` frames the host wrote, in order. */
    private fun modeFrames(): List<String> = frames().filter { it.subtype() == "set_permission_mode" }
        .map { it["request"]!!.jsonObject["mode"]!!.jsonPrimitive.content }

    @Test
    fun `two cyclePermissionMode calls with no reply between them send acceptEdits then plan`() = test {
        val manager = manager() // plain-reply answers no control request
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        while (handle.liveSession() == null) delay(20)

        assertEquals(PermissionMode.ACCEPT_EDITS, manager.cyclePermissionMode(handle.id, step))
        assertEquals(PermissionMode.PLAN, manager.cyclePermissionMode(handle.id, step))
        awaitLog { modeFrames().size == 2 }
        assertEquals(listOf("acceptEdits", "plan"), modeFrames())
        // The process's own state holds both picks at once; the handle's follows it through its mirror, a coroutine, so it is awaited.
        assertEquals(PermissionMode.PLAN, handle.liveSession()!!.state.value.permissionMode)
        handle.state.first { it.permissionMode == PermissionMode.PLAN }
    }

    @Test
    fun `a stopped session's cycle reaches the next spawn's --permission-mode`() = test {
        val manager = manager()
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.stop(handle.id)

        assertEquals(PermissionMode.ACCEPT_EDITS, manager.cyclePermissionMode(handle.id, step))
        assertEquals(PermissionMode.PLAN, manager.cyclePermissionMode(handle.id, step))
        manager.send(handle.id, "Reply with the single word: pong")
        awaitLog { spawns().size == 2 }
        assertEquals("plan", spawns().last().valueOf("--permission-mode"))
        assertEquals(emptyList(), modeFrames(), "the picks made while stopped went into the flag")
    }

    @Test
    fun `cyclePermissionMode on an unknown id gives null`() = test {
        val manager = manager()
        assertNull(manager.cyclePermissionMode("no-such-session", step))
    }

    @Test
    fun `a cycle while a turn runs writes its frame at once`() = test {
        // The turn waits on its permission request, and the fake reads (and logs) host frames while it waits for the answer: a
        // frame in its log reached the process before the turn's result, which the fake writes only after that answer.
        val manager = manager(fixtures = "permission")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Create the fixture file")
        handle.status.await(SessionStatus.WAITING)

        assertEquals(PermissionMode.ACCEPT_EDITS, manager.cyclePermissionMode(handle.id, step))
        awaitLog { modeFrames().isNotEmpty() }
        assertEquals(listOf("acceptEdits"), modeFrames())
        val state = handle.state.value
        assertTrue(state.busy, "the turn is still open")
        assertEquals(0, state.turnsEnded)
        manager.stop(handle.id)
    }

    @Test
    fun `an effort pick on a stopped session sets the effort only`() = test {
        val manager = manager()
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.stop(handle.id)

        manager.setEffort(handle.id, "high")
        assertEquals("high", handle.state.value.effort)
        assertEquals(0, frames().count { it.subtype() == "apply_flag_settings" })
        manager.send(handle.id, "Reply with the single word: pong")
        awaitLog { spawns().size == 2 }
        assertEquals("high", spawns()[1].valueOf("--effort"))
    }

    @Test
    fun `a respawn keeps auto`() = test {
        val manager = manager(fixtures = "permission-mode-auto,plain-reply")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Reply with the single word: one")
        handle.state.first { it.turnsEnded == 1 }
        manager.setPermissionMode(handle.id, PermissionMode.AUTO)
        handle.state.first { it.permissionMode == PermissionMode.AUTO && it.permissionModeChanges.isEmpty() }
        manager.stop(handle.id)

        manager.send(handle.id, "again")
        awaitLog { spawns().size == 2 }
        assertEquals("auto", spawns()[1].valueOf("--permission-mode"))
        assertTrue(handle.state.value.entries.none { it is ChatEntry.Notice && "not supported" in it.text })
        manager.close(handle.id)
    }

    @Test
    fun `a switch the CLI rejects goes back to the mode before it, as recorded`() = test {
        // The fake answers the switch with the recorded rejection of bypassPermissions.
        val manager = manager(fixtures = "permission-mode-rejected")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Reply with the single word: one")
        handle.state.first { it.turnsEnded == 1 }
        manager.setPermissionMode(handle.id, PermissionMode.BYPASS_PERMISSIONS)
        val rejected = handle.state.first { s -> s.entries.any { it is ChatEntry.Notice && it.level == NoticeLevel.ERROR } }
        assertEquals(PermissionMode.DEFAULT, rejected.permissionMode)
        assertTrue(
            rejected.entries.filterIsInstance<ChatEntry.Notice>().single { it.level == NoticeLevel.ERROR }.text
                .startsWith("The CLI rejected the switch to permission mode bypassPermissions (Cannot set permission mode"),
        )
        manager.send(handle.id, "Reply with the single word: two")
        handle.state.first { it.turnsEnded == 2 } // the session goes on
        manager.close(handle.id)
    }

    @Test
    fun `a recorded permission mode Tandem does not offer starts as default with a notice`() = test {
        seed(seeded) { it.replace("\"permissionMode\":\"default\"", "\"permissionMode\":\"bypassPermissions\"") }
        val manager = manager()
        val handle = manager.open(seeded)
        awaitLog { spawns().isNotEmpty() }
        assertEquals("default", spawns().single().valueOf("--permission-mode"))
        val state = handle.state.value
        assertEquals(PermissionMode.DEFAULT, state.permissionMode)
        val notice = state.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.INFO, notice.level)
        assertEquals("Permission mode bypassPermissions is not supported yet; using default.", notice.text)
    }

    @Test
    fun `close stops the process and drops the session`() = test {
        seed(seeded)
        seed(other)
        val manager = manager()
        val created = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        val opened = manager.open(seeded, spawn = false)
        val last = manager.open(other, spawn = false)
        manager.select(created.id)

        manager.close(created.id)
        assertEquals(emptyList(), ownFakeProcesses(), "close returns once the process is gone")
        assertEquals(listOf(opened, last), manager.sessions.value)
        assertEquals(seeded, manager.selected.value, "the closed session was selected, so the next one is")

        manager.select(other)
        manager.close(other)
        assertEquals(seeded, manager.selected.value, "the last one was selected, so the one before it is")
        manager.close(seeded)
        assertEquals(emptyList(), manager.sessions.value)
        assertNull(manager.selected.value)
    }

    @Test
    fun `a missing binary shows the install notice`() = test {
        // The first lookup waits until the prompt below is queued, so that the prompt is sent while the session starts.
        val queued = CountDownLatch(1)
        val manager = manager(lookup = { queued.await(10, TimeUnit.SECONDS); null })
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Reply with the single word: pong")
        queued.countDown()
        manager.stop(handle.id) // comes after both starts, so both have run
        // One notice: the first start dropped the prompt, which leaves the prompt's own start nothing to do.
        val notice = handle.state.value.entries.single() as ChatEntry.Notice
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertTrue("not found" in notice.text, notice.text)
        assertEquals(SessionStatus.STOPPED, handle.status.value)

        // A later prompt looks for the binary again, and says so again; no prompt is shown as sent.
        manager.send(handle.id, "Reply with the single word: pong")
        handle.state.first { s -> s.entries.count { it is ChatEntry.Notice } == 2 }
        assertEquals(emptyList(), handle.state.value.prompts())
        assertEquals(SessionStatus.STOPPED, handle.status.value)
        assertFalse(log.exists(), "no process was started")
    }

    @Test
    fun `a shell escape whose claude is not found says its output was not sent`() = test {
        unix()
        val manager = manager(lookup = { null })
        val handle = manager.create(NewSessionOptions())
        manager.runShell(handle.id, "echo hi")
        val done = handle.state.first { s -> s.entries.lastOrNull().let { it is ChatEntry.Notice && it.level == NoticeLevel.WARNING } }
        val (install, unsent) = done.entries.takeLast(2).map { it as ChatEntry.Notice }
        assertEquals(NoticeLevel.ERROR, install.level)
        assertTrue("not found" in install.text, install.text)
        assertEquals("The output of ! echo hi was not sent: the CLI ended first.", unsent.text)
        assertEquals(ShellEnd.Exited(0), done.shell().end)
        assertFalse(log.exists(), "no process was started")
    }

    @Test
    fun `attention is raised for a background session and cleared on select`() = test {
        val manager = manager(fixtures = "permission,interrupt")
        val asking = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 } // this process replays the first fixture
        val working = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 2 }
        manager.select(working.id)

        manager.send(asking.id, "Create the fixture file")
        asking.status.await(SessionStatus.WAITING)
        asking.attention.await(true)
        assertFalse(working.attention.value)
        manager.select(asking.id)
        assertFalse(asking.attention.value)

        // The selected session asking again needs no badge: the user is looking at it.
        manager.allow(asking.id, asking.state.value.pendingPermissions.single().requestId, always = false)
        asking.status.await(SessionStatus.IDLE)
        manager.send(asking.id, "Create it once more") // the fixture's second turn asks again
        asking.status.await(SessionStatus.WAITING)
        delay(300) // time for a wrong badge to show
        assertFalse(asking.attention.value)

        // Now in the background, a turn that goes from running to idle raises the other one's badge.
        manager.send(working.id, "Count to a hundred")
        working.status.await(SessionStatus.RUNNING) // the interrupt fixture streams, then waits for the interrupt
        manager.interrupt(working.id)
        working.status.await(SessionStatus.IDLE)
        working.attention.await(true)
        manager.select(working.id)
        assertFalse(working.attention.value)
    }

    @Test
    fun `stopping a running background session raises no attention`() = test {
        val manager = manager(fixtures = "interrupt,plain-reply")
        val background = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 } // this process replays the interrupt fixture
        val foreground = manager.create(NewSessionOptions())
        manager.select(foreground.id)
        manager.send(background.id, "Count to a hundred")
        background.status.await(SessionStatus.RUNNING) // the fixture streams, then waits for an interrupt

        val seen = Collections.synchronizedList(ArrayList<SessionStatus>())
        val recording = launch(start = CoroutineStart.UNDISPATCHED) { background.status.collect { seen.add(it) } }
        manager.stop(background.id)
        background.status.await(SessionStatus.STOPPED)
        delay(300) // time for a wrong badge to show
        recording.cancel()
        assertEquals(listOf(SessionStatus.RUNNING, SessionStatus.STOPPED), seen.toList(), "never idle on the way to stopped")
        assertFalse(background.attention.value)
    }

    @Test
    fun `a session opened again while it closes starts once its old process is gone`() = test {
        seed(seeded)
        // While the fake waits before its permission request it reads no input, so closing it takes about 2 s.
        val manager = manager(fixtures = "permission", delayMs = 5_000)
        manager.open(seeded)
        manager.send(seeded, "Create the fixture file")
        awaitLog { prompts().isNotEmpty() }
        val old = ownFakeProcesses().single()

        val closing = launch(start = CoroutineStart.UNDISPATCHED) { manager.close(seeded) }
        val reopened = manager.open(seeded)
        assertFalse(old.isAlive, "two processes on one transcript")
        closing.join()
        assertEquals(listOf(reopened), manager.sessions.value)
        awaitLog { spawns().size == 2 }
        assertEquals(1, ownFakeProcesses().size)
    }

    @Test
    fun `summaries list a session without a transcript first and titles come from the index, else the first prompt`() = test {
        seed(seeded)
        val manager = manager(clock = { 7_000L })
        val opened = manager.open(seeded, spawn = false)
        manager.refreshIndex()
        opened.title.await("Pong")

        val created = manager.create(NewSessionOptions())
        assertEquals(created.id, created.title.value, "no transcript and no prompt yet")
        manager.send(created.id, "  Write a haiku\nabout the sea")
        created.title.await("Write a haiku")
        val summaries = manager.summaries.first { it.firstOrNull()?.title == "Write a haiku" }
        assertEquals(SessionSummary(created.id, "Write a haiku", 7_000, "Write a haiku", file = null), summaries.first())
        assertEquals(listOf(created.id, seeded), summaries.map { it.id })
        assertEquals(folder.resolve("$seeded.jsonl"), summaries.last().file)
    }

    @Test
    fun `a transcript written into the folder shows up in the summaries`() = test {
        seed(seeded)
        val manager = manager()
        assertEquals("Pong", manager.summaries.first { it.isNotEmpty() }.single().title) // the first listing
        seed(other)
        // From the folder's change, well before the 30 s poll that stands in where the folders cannot be watched.
        val listed = withTimeout(10_000) { manager.summaries.first { summaries -> summaries.any { it.id == other } } }
        assertEquals(setOf(seeded, other), listed.map { it.id }.toSet())
    }

    @Test
    fun `a cancelled close still ends the process before the session can start again`() = test {
        seed(seeded)
        // While the fake waits before its permission request it reads no input, so closing it takes about 2 s.
        val manager = manager(fixtures = "permission", delayMs = 5_000)
        manager.open(seeded)
        manager.send(seeded, "Create the fixture file")
        awaitLog { prompts().isNotEmpty() }
        val old = ownFakeProcesses().single()

        val closing = launch(start = CoroutineStart.UNDISPATCHED) { manager.close(seeded) }
        closing.cancel() // the caller gives up; the close goes on
        val reopened = manager.open(seeded)
        assertFalse(old.isAlive, "two processes on one transcript")
        assertEquals(listOf(reopened), manager.sessions.value)
    }

    @Test
    fun `the cap stops the least recently used idle session only`() = test {
        // The spawn order picks the fixtures: b streams and then waits for an interrupt; a, c and d reply.
        val manager = manager(fixtures = "plain-reply,interrupt,plain-reply", maxLive = 2)
        val a = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        val b = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 2 }

        val c = manager.create(NewSessionOptions()) // two run already: a, used least recently, stops
        awaitLog { spawns().size == 3 }
        a.status.await(SessionStatus.STOPPED)
        assertEquals(2, ownFakeProcesses().size, "a's process ended before c's started")

        manager.send(b.id, "Count to a hundred")
        b.status.await(SessionStatus.RUNNING) // the fixture streams, then waits for an interrupt
        val d = manager.create(NewSessionOptions()) // b is in a turn, so c, the only idle one, stops
        awaitLog { spawns().size == 4 }
        c.status.await(SessionStatus.STOPPED)
        assertEquals(SessionStatus.RUNNING, b.status.value)
        assertEquals(2, ownFakeProcesses().size, "b's and d's")
        assertTrue(frames().none { it.subtype() == "interrupt" }, "b's turn was not interrupted")
        assertEquals(listOf(a, b, c, d), manager.sessions.value, "a stopped session keeps its row")
        assertTrue(d.state.value.entries.none { it is ChatEntry.Notice }, "there was room")
    }

    @Test
    fun `the cap notice appears when nothing is idle`() = test {
        val manager = manager(fixtures = "interrupt,plain-reply", maxLive = 1)
        val busy = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.send(busy.id, "Count to a hundred")
        busy.status.await(SessionStatus.RUNNING)

        val extra = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 2 }
        val notice = extra.state.value.entries.single() as ChatEntry.Notice
        assertEquals(NoticeLevel.INFO, notice.level)
        assertTrue("Max live sessions" in notice.text, notice.text)
        assertEquals(SessionStatus.RUNNING, busy.status.value, "a session in a turn is never stopped")
        assertEquals(2, ownFakeProcesses().size)
    }

    @Test
    fun `a session opened when nothing is idle stays stopped until its first prompt starts it over the cap`() = test {
        seed(seeded)
        val manager = manager(fixtures = "interrupt,plain-reply", maxLive = 1)
        val busy = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.send(busy.id, "Count to a hundred")
        busy.status.await(SessionStatus.RUNNING) // the fixture streams, then waits for an interrupt

        val opened = manager.open(seeded)
        delay(300) // time for a wrong start to show in the log
        assertEquals(1, spawns().size, "opening a session never starts a process over the cap")
        assertEquals(SessionStatus.STOPPED, opened.status.value)
        assertEquals(listOf("Reply with the single word: pong"), opened.state.value.prompts(), "its history")
        assertTrue(opened.state.value.entries.none { it is ChatEntry.Notice }, "no notice: nothing was started")

        // The user's prompt is what the user asked for: it starts the process over the cap, and the chat says so.
        manager.send(seeded, "Reply with the single word: pong")
        awaitLog { spawns().size == 2 }
        assertEquals(seeded, spawns().last().valueOf("--resume"))
        val started = opened.state.first { s -> s.entries.any { it is ChatEntry.Notice } }
        val notice = started.entries.filterIsInstance<ChatEntry.Notice>().single()
        assertEquals(NoticeLevel.INFO, notice.level)
        assertEquals("Max live sessions exceeded", notice.text)
        assertEquals(SessionStatus.RUNNING, busy.status.value, "a session in a turn is never stopped")
        opened.state.first { s -> !s.busy && s.entries.count { it is ChatEntry.Assistant } == 2 } // and the prompt is answered
    }

    @Test
    fun `rename sends rename_session when live and appends a custom-title line when stopped`() = test {
        seed(seeded)
        seed(other)
        val liveTranscript = folder.resolve("$seeded.jsonl").readText()
        val stoppedTranscript = folder.resolve("$other.jsonl")
        val lines = stoppedTranscript.readLines().size
        val manager = manager(clock = { Instant.parse("2026-10-16T12:34:56.789Z").toEpochMilli() })
        val live = manager.open(seeded)
        // A process that has reported its first system/init is told at once; one that has not, with that init (tests below).
        manager.send(seeded, "Reply with the single word: pong")
        live.state.first { s -> !s.busy && s.entries.count { it is ChatEntry.Assistant } == 2 }
        manager.refreshIndex() // lists both, as "Pong"

        manager.rename(seeded, "Alpha")
        live.title.await("Alpha")
        // The list shows it too, before the index does: here it never does, since the fake writes no custom-title line.
        manager.summaries.first { summaries -> summaries.any { it.id == seeded && it.title == "Alpha" } }
        awaitLog { frames().any { it.subtype() == "rename_session" } }
        val request = frames().single { it.subtype() == "rename_session" }["request"]
        assertEquals(Json.parseToJsonElement("""{"subtype":"rename_session","title":"Alpha","source":"host"}"""), request)
        assertEquals(liveTranscript, folder.resolve("$seeded.jsonl").readText(), "the CLI writes a live session's title itself")

        val stopped = manager.open(other, spawn = false)
        manager.rename(other, " \t") // blank: ignored
        manager.rename(other, "Beta")
        val written = stoppedTranscript.readLines()
        assertEquals(lines + 1, written.size, "one line, for the title that is not blank")
        val line = Json.parseToJsonElement(written.last()).jsonObject
        assertEquals(listOf("type", "customTitle", "sessionId", "uuid", "timestamp"), line.keys.toList())
        assertEquals("custom-title", line["type"]!!.jsonPrimitive.content)
        assertEquals("Beta", line["customTitle"]!!.jsonPrimitive.content)
        assertEquals(other, line["sessionId"]!!.jsonPrimitive.content)
        val uuid = line["uuid"]!!.jsonPrimitive.content
        assertEquals(uuid, UUID.fromString(uuid).toString())
        assertEquals("2026-10-16T12:34:56.789Z", line["timestamp"]!!.jsonPrimitive.content)
        stopped.title.await("Beta")
        assertEquals("Beta", stopped.title.value)
        assertEquals(1, frames().count { it.subtype() == "rename_session" })
        // rename had the index read the transcript again, so it lists "Beta" and its own title counts from then on: a later
        // rename elsewhere, say in a terminal, shows. (Had the index not listed "Beta" first, "Beta" would stay.) The handle takes
        // the listing in its own coroutine: wait until it has, so that the next listing is not taken in the same step.
        awaitLog { !stopped.titleOverridden }
        stoppedTranscript.appendText(written.last().replace("\"Beta\"", "\"Gamma\"") + "\n")
        manager.refreshIndex()
        stopped.title.await("Gamma")
    }

    @Test
    fun `a rename before the first system-init is sent with that init`() = test {
        val manager = manager()
        val created = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 } // a process, which reports no system/init before its first prompt
        manager.rename(created.id, "Epsilon")
        created.title.await("Epsilon") // shown at once

        manager.send(created.id, "Reply with the single word: pong")
        created.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant } }
        awaitLog { frames().any { it.subtype() == "rename_session" } }
        val sent = frames()
        val rename = sent.indexOfFirst { it.subtype() == "rename_session" }
        assertTrue(sent.indexOfFirst { it.type() == "user" } < rename, "sent once the first prompt's init arrived")
        assertEquals("Epsilon", sent[rename]["request"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(1, sent.count { it.subtype() == "rename_session" })
        assertEquals("Epsilon", created.title.value)
    }

    @Test
    fun `a resumed session renamed before its first system-init gets the line now and tells the CLI with the init`() = test {
        seed(seeded)
        val transcript = folder.resolve("$seeded.jsonl")
        val lines = transcript.readLines().size
        val manager = manager()
        val live = manager.open(seeded)
        awaitLog { spawns().size == 1 } // a process, which reports no system/init before its first prompt
        manager.rename(seeded, "Zeta")
        live.title.await("Zeta")
        // No prompt may ever come, so Tandem writes the line itself, as for a session without a process.
        val written = transcript.readLines()
        assertEquals(lines + 1, written.size)
        assertEquals("Zeta", Json.parseToJsonElement(written.last()).jsonObject["customTitle"]!!.jsonPrimitive.content)

        // The CLI, which read the old title when it started, is told the new one with its first system/init.
        manager.send(seeded, "Reply with the single word: pong")
        awaitLog { frames().any { it.subtype() == "rename_session" } }
        val sent = frames()
        val rename = sent.indexOfFirst { it.subtype() == "rename_session" }
        assertTrue(sent.indexOfFirst { it.type() == "user" } < rename, "sent once the first prompt's init arrived")
        assertEquals("Zeta", sent[rename]["request"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        live.state.first { s -> !s.busy && s.entries.count { it is ChatEntry.Assistant } == 2 }
        assertEquals(1, frames().count { it.subtype() == "rename_session" })
    }

    @Test
    fun `a stopped session without a transcript keeps a new title until its next process reports its init`() = test {
        val manager = manager()
        val created = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.stop(created.id) // before its first prompt, so the CLI has written no transcript

        manager.rename(created.id, "Delta")
        created.title.await("Delta")
        assertFalse(folder.resolve("${created.id}.jsonl").exists(), "no transcript is made for the title")
        assertEquals(0, frames().count { it.subtype() == "rename_session" })
        manager.summaries.first { summaries -> summaries.any { it.id == created.id && it.title == "Delta" } }

        // The next process is told with its first system/init, as for a rename before that init: the title is not lost.
        manager.send(created.id, "Reply with the single word: pong")
        created.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant } }
        awaitLog { frames().any { it.subtype() == "rename_session" } }
        assertEquals(listOf("Delta"), renameTitles())
    }

    /** The titles of the `rename_session` requests the host wrote, in order. */
    private fun renameTitles(): List<String> =
        frames().filter { it.subtype() == "rename_session" }.map { it["request"]!!.jsonObject["title"]!!.jsonPrimitive.content }

    @Test
    fun `a fork renamed while stopped before its first prompt tells the CLI the new title, not the fork's`() = test {
        seed(seeded)
        val manager = manager()
        val fork = assertNotNull(manager.fork(seeded))
        assertEquals("Pong (fork)", fork.title.value)
        awaitLog { spawns().size == 1 }
        manager.stop(fork.id) // before a prompt: no system/init (as when the cap stops an idle fork)
        manager.rename(fork.id, "Mine")
        fork.title.await("Mine")
        manager.send(fork.id, "Reply with the single word: pong")
        fork.state.first { s -> !s.busy && s.entries.count { it is ChatEntry.Assistant } == 2 }
        awaitLog { frames().any { it.subtype() == "rename_session" } }
        assertEquals(listOf("Mine"), renameTitles())
    }

    @Test
    fun `a resumed session renamed before its init, stopped and renamed again tells the CLI the last title`() = test {
        seed(seeded)
        val manager = manager()
        val live = manager.open(seeded)
        awaitLog { spawns().size == 1 }
        manager.rename(seeded, "Zeta") // deferred: Tandem appends Zeta, the CLI is to be told with the init
        manager.stop(seeded)
        manager.rename(seeded, "Eta") // no process: Tandem appends Eta
        live.title.await("Eta")
        manager.send(seeded, "Reply with the single word: pong")
        live.state.first { s -> !s.busy && s.entries.count { it is ChatEntry.Assistant } == 2 }
        awaitLog { frames().any { it.subtype() == "rename_session" } }
        assertEquals(listOf("Eta"), renameTitles())
    }

    @Test
    fun `a new session renamed before its init, stopped and renamed again tells the CLI the last title`() = test {
        val manager = manager()
        val created = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.rename(created.id, "Alpha") // deferred
        manager.stop(created.id)
        manager.rename(created.id, "Beta") // no process, no transcript
        created.title.await("Beta")
        manager.send(created.id, "Reply with the single word: pong")
        created.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant } }
        awaitLog { frames().any { it.subtype() == "rename_session" } }
        assertEquals(listOf("Beta"), renameTitles())
    }

    @Test
    fun `a title line that cannot be written takes the new title back`() = test {
        seed(seeded)
        // Run as root, a test cannot make a file read-only, so the append fails in its place.
        val manager = manager(appendTitle = { file, _, _, _ -> throw AccessDeniedException(file.toString()) })
        val stopped = manager.open(seeded, spawn = false)
        manager.refreshIndex()
        stopped.title.await("Pong")

        assertFailsWith<AccessDeniedException> { manager.rename(seeded, "Eta") }
        val shown = withTimeoutOrNull(5_000) { stopped.title.first { it == "Pong" } }
        assertEquals("Pong", shown, "the title from before the rename is back")
    }

    @Test
    fun `fork resumes with --fork-session under a new id and is named after the original`() = test {
        seed(seeded)
        val transcript = folder.resolve("$seeded.jsonl").readText()
        val manager = manager()
        val original = manager.open(seeded, spawn = false)
        manager.refreshIndex()
        original.title.await("Pong")
        val history = original.state.value

        val fork = assertNotNull(manager.fork(seeded))
        assertNotEquals(seeded, fork.id)
        assertEquals(fork.id, UUID.fromString(fork.id).toString(), "a fork's id is a new lower-case UUID")
        assertEquals(listOf(original, fork), manager.sessions.value)
        assertEquals(fork.id, manager.selected.value)
        assertEquals("Pong (fork)", fork.title.value)
        assertEquals(history.entries, fork.state.value.entries, "the fork starts from the original's history")
        awaitLog { spawns().isNotEmpty() }
        val argv = spawns().single()
        val forked = listOf("--resume", seeded, "--fork-session", "--session-id", fork.id)
        assertEquals(forked, argv.subList(argv.indexOf("--resume"), argv.size))

        // A fork's first system/init comes with its first prompt, and the CLI is told the title then.
        manager.send(fork.id, "Reply with the single word: pong")
        awaitLog { frames().any { it.subtype() == "rename_session" } }
        val sent = frames()
        val rename = sent.indexOfFirst { it.subtype() == "rename_session" }
        assertTrue(sent.indexOfFirst { it.type() == "user" } < rename, "renamed once the first prompt's init arrived")
        val title = sent[rename]["request"]!!.jsonObject["title"]!!.jsonPrimitive.content
        assertTrue(title.endsWith("(fork)"), title)
        assertEquals("Pong (fork)", title)
        val answered = fork.state.first { s -> !s.busy && s.entries.count { it is ChatEntry.Assistant } == 2 }
        assertEquals(fork.id, answered.sessionId, "the CLI reports the id the fork was given")
        assertEquals(listOf(original, fork), manager.sessions.value)
        assertEquals(fork.id, manager.selected.value)

        // The original is as it was: history, title, no process, transcript.
        assertEquals(history, original.state.value)
        assertEquals("Pong", original.title.value)
        assertEquals(SessionStatus.STOPPED, original.status.value)
        assertEquals(transcript, folder.resolve("$seeded.jsonl").readText())
        assertEquals(1, spawns().size)
    }

    @Test
    fun `a fork stopped before its first prompt forks again until it has a transcript`() = test {
        seed(seeded)
        val manager = manager()
        val fork = assertNotNull(manager.fork(seeded)) // not open: the history and the title come from the transcript
        assertEquals("Pong (fork)", fork.title.value)
        awaitLog { spawns().size == 1 }
        manager.stop(fork.id) // before a prompt: no system/init, and no transcript of its own

        manager.send(fork.id, "Reply with the single word: pong")
        awaitLog { frames().any { it.subtype() == "rename_session" } }
        val forked = listOf("--resume", seeded, "--fork-session", "--session-id", fork.id)
        assertEquals(listOf(forked, forked), spawns().map { it.subList(it.indexOf("--resume"), it.size) })
        assertEquals(1, frames().count { it.subtype() == "rename_session" }, "told once, by the process that reported its init")

        // Once the CLI has written the fork's transcript, the fork resumes as itself.
        fork.state.first { s -> !s.busy && s.entries.count { it is ChatEntry.Assistant } == 2 }
        seed(fork.id)
        manager.stop(fork.id)
        manager.send(fork.id, "And once more")
        awaitLog { spawns().size == 3 }
        val last = spawns().last()
        assertEquals(fork.id, last.valueOf("--resume"))
        assertFalse("--fork-session" in last)
        assertFalse("--session-id" in last)
    }

    @Test
    fun `fork gives null for a session without a transcript`() = test {
        val manager = manager()
        val created = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        assertNull(manager.fork(created.id), "no prompt yet, so nothing to fork")
        assertNull(manager.fork("not-a-session-id"))
        assertEquals(listOf(created), manager.sessions.value)
        assertNull(manager.selected.value)
        assertEquals(1, spawns().size)
    }

    /** The arguments of the process started for session [id] (its `--session-id`), once there is one, from `--resume` on. */
    private suspend fun forkTail(id: String): List<String> {
        awaitLog { spawns().any { it.valueOf("--session-id") == id } }
        val argv = spawns().single { it.valueOf("--session-id") == id }
        return argv.subList(argv.indexOf("--resume"), argv.size)
    }

    // spec: fork at a reply resumes with --resume-session-at and starts from the history up to that turn
    @Test
    fun `fork at a reply resumes with --resume-session-at and starts from the history up to that turn`() = test {
        seed(seeded, fixture = "fork-at-origin")
        val transcript = folder.resolve("$seeded.jsonl").readBytes()
        val manager = manager()
        val original = manager.open(seeded, spawn = false)
        manager.refreshIndex()
        original.title.await("Remember word ALPHA")
        val history = original.state.value
        assertEquals(3, history.prompts().size)
        val x = assertNotNull(history.entries.filterIsInstance<ChatEntry.Assistant>().first().lastUuid)
        assertEquals("<uuid-14>", x)

        val fork = assertNotNull(manager.fork(seeded, x))
        val tail = listOf("--resume", seeded, "--fork-session", "--session-id", fork.id, "--resume-session-at", "<uuid-14>")
        assertEquals(tail, forkTail(fork.id))
        assertEquals(listOf("Remember the word ALPHA. Reply with exactly one word: OK"), fork.state.value.prompts())
        assertEquals("Remember word ALPHA (fork)", fork.title.value)
        assertEquals(fork.id, manager.selected.value)
        assertEquals(listOf(original, fork), manager.sessions.value)
        assertSame(history, original.state.value, "the original's chat is untouched")
        assertEquals("Remember word ALPHA", original.title.value)
        assertContentEquals(transcript, folder.resolve("$seeded.jsonl").readBytes())

        // Until the fork has a transcript of its own, every process forks at the same line.
        manager.stop(fork.id)
        manager.send(fork.id, "Which words do you remember?")
        awaitLog { spawns().size == 2 }
        assertEquals(tail, spawns().last().let { it.subList(it.indexOf("--resume"), it.size) })
        fork.state.first { s -> !s.busy && s.prompts().size == 2 && s.entries.last() is ChatEntry.TurnEnded }

        // Once it has, it resumes as itself.
        seed(fork.id)
        manager.stop(fork.id)
        manager.send(fork.id, "And once more")
        awaitLog { spawns().size == 3 }
        val last = spawns().last()
        assertEquals(fork.id, last.valueOf("--resume"))
        assertFalse("--fork-session" in last)
        assertFalse("--resume-session-at" in last)
        assertContentEquals(transcript, folder.resolve("$seeded.jsonl").readBytes())
    }

    // spec: fork at a uuid not in the history gives null and starts nothing
    @Test
    fun `fork at a uuid not in the history gives null and starts nothing`() = test {
        seed(seeded, fixture = "fork-at-origin")
        val manager = manager()
        val original = manager.open(seeded, spawn = false)
        assertNull(manager.fork(seeded, "no-such-uuid"))
        assertEquals(ForkResult.Refused.NOT_IN_HISTORY, manager.forkResult(seeded, "no-such-uuid"))
        assertEquals(listOf(original), manager.sessions.value)
        assertNull(manager.selected.value)

        val created = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        assertEquals(ForkResult.Refused.NO_TRANSCRIPT, manager.forkResult(created.id))
        assertEquals(ForkResult.Refused.NO_TRANSCRIPT, manager.forkResult(created.id, "<uuid-14>"))
        assertEquals(ForkResult.Refused.NO_TRANSCRIPT, manager.forkResult("not-a-session-id"))
        assertEquals(listOf(original, created), manager.sessions.value)
        assertNull(manager.selected.value)
        assertEquals(1, spawns().size, "only the new session's process started")
    }

    // spec: a fork without a transcript forks its original at the same line
    @Test
    fun `a fork without a transcript forks its original at the same line`() = test {
        seed(seeded, fixture = "fork-at-origin")
        val manager = manager(maxLive = 8)
        val f = assertNotNull(manager.fork(seeded)) // no prompt: no transcript of its own
        val g = assertNotNull(manager.fork(f.id, "<uuid-14>"))
        assertEquals(listOf("--resume", seeded, "--fork-session", "--session-id", g.id, "--resume-session-at", "<uuid-14>"), forkTail(g.id))
        assertEquals(listOf("Remember the word ALPHA. Reply with exactly one word: OK"), g.state.value.prompts())

        val h = assertNotNull(manager.fork(seeded, "<uuid-14>"))
        val i = assertNotNull(manager.fork(h.id))
        assertEquals(listOf("--resume", seeded, "--fork-session", "--session-id", i.id, "--resume-session-at", "<uuid-14>"), forkTail(i.id))
        assertEquals(h.state.value.entries, i.state.value.entries)
        assertEquals(listOf("--resume", seeded, "--fork-session", "--session-id", f.id), forkTail(f.id))
    }

    @Test
    fun `a resume that comes back with another id becomes a new row with a warning`() = test {
        seed(seeded)
        // Review Focus 5: the fake reports this id, whatever it was started with.
        val manager = manager(environment = mapOf("TANDEM_FAKE_SESSION_ID" to other))
        val resumed = manager.open(seeded, spawn = false)
        val history = resumed.state.value
        manager.select(seeded)
        manager.send(seeded, "Reply with the single word: pong")

        val taken = manager.sessions.first { it.size == 2 }.last()
        assertEquals(other, taken.id)
        manager.selected.await(other) // the old row was selected, so the new one is
        val state = taken.state.first { s -> !s.busy && s.entries.count { it is ChatEntry.Assistant } == 2 }
        val notice = state.entries.filterIsInstance<ChatEntry.Notice>().single()
        assertEquals(NoticeLevel.WARNING, notice.level)
        assertEquals("Resumed as a new session $other", notice.text)
        val pong = "Reply with the single word: pong"
        assertEquals(listOf(pong, pong), state.prompts(), "the history, then the prompt the process took")
        taken.status.await(SessionStatus.IDLE) // it has the process

        // The old row is not relabelled: same id, its history without the prompt, no process.
        resumed.status.await(SessionStatus.STOPPED)
        assertEquals(seeded, resumed.id)
        assertEquals(history.entries, resumed.state.value.entries)
        assertEquals(listOf(resumed, taken), manager.sessions.value)
        assertEquals(1, spawns().size, "the process was taken over, not started again")
        assertEquals(1, ownFakeProcesses().size)
    }

    @Test
    fun `a resume that comes back as a session open in another row stops that process`() = test {
        seed(seeded)
        seed(other)
        val manager = manager(environment = mapOf("TANDEM_FAKE_SESSION_ID" to other))
        val resumed = manager.open(seeded, spawn = false)
        val open = manager.open(other, spawn = false)
        manager.select(seeded)
        manager.send(seeded, "Reply with the single word: pong")

        // One session never has two rows or two processes: the process is stopped, and the row it was started for says why.
        val notice = resumed.state.first { s -> s.entries.last() is ChatEntry.Notice }.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.WARNING, notice.level)
        assertEquals("Resumed as session $other, which is open already, so that process was stopped.", notice.text)
        assertEquals(listOf(resumed, open), manager.sessions.value)
        assertEquals(seeded, manager.selected.value)
        resumed.status.await(SessionStatus.STOPPED)
        assertEquals(SessionStatus.STOPPED, open.status.value, "it never had a process")
        assertEquals(emptyList(), ownFakeProcesses())
    }

    @Test
    fun `killAllNow kills every process at once`() = test {
        val manager = manager()
        val handles = List(2) { manager.create(NewSessionOptions()) }
        // Both processes are up and known to their sessions.
        while (handles.any { it.liveSession()?.process == null }) delay(20)
        val processes = handles.map { it.liveSession()!!.process!! }

        // What a JVM shutdown hook does: it cannot wait for coroutines, so the processes go while the manager's scope still runs.
        manager.killAllNow()
        while (processes.any { it.isAlive }) delay(20)
        handles.forEach { it.status.await(SessionStatus.STOPPED) }
        manager.killAllNow() // once more: nothing left to kill, and no failure
    }

    @Test
    fun `killAllNow also kills the process of a session being closed`() = test {
        // The fake sleeps this long before its permission request and reads nothing meanwhile: closing it takes about 2 s, since
        // ClaudeSession waits that long for it to end by itself before it terminates it (SIGTERM, exit code 143).
        val manager = manager(fixtures = "permission", delayMs = 30_000)
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Create the fixture file")
        awaitLog { prompts().isNotEmpty() } // it has the prompt, and sleeps
        val process = handle.liveSession()!!.process!!
        val closing = launch { manager.close(handle.id) }
        manager.sessions.first { it.isEmpty() } // only the close still knows the session now

        manager.killAllNow()
        assertEquals(137, runInterruptible(Dispatchers.IO) { process.waitFor() }, "killed (SIGKILL), not ended by the close")
        closing.join()
    }

    @Test
    fun `delete removes the transcript and its sidecar directory`() = test {
        seed(seeded)
        seed(other)
        val sidecar = folder.resolve(seeded).resolve("subagents").createDirectories().resolve("agent-x.jsonl")
        sidecar.writeText("{}\n")
        val manager = manager()
        manager.open(seeded) // with a process, which delete stops first
        awaitLog { spawns().size == 1 }
        val kept = manager.open(other, spawn = false)
        manager.select(seeded)

        manager.delete(seeded)
        assertFalse(folder.resolve("$seeded.jsonl").exists())
        assertFalse(sidecar.exists())
        assertFalse(folder.resolve(seeded).exists())
        assertTrue(folder.resolve("$other.jsonl").exists())
        assertEquals(listOf(kept), manager.sessions.value)
        assertEquals(other, manager.selected.value, "the deleted session was selected, so the next one is")
        assertEquals(emptyList(), ownFakeProcesses(), "delete returns once the process is gone")
        manager.summaries.first { summaries -> summaries.map { it.id } == listOf(other) }
    }

    @Test
    fun `open waits for the delete of a session that has no row`() = test {
        seed(seeded)
        val deleting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = manager(
            deleteFiles = { file ->
                deleting.countDown()
                release.await(10, TimeUnit.SECONDS)
                TranscriptFiles.delete(file)
            },
        )
        val delete = launch(Dispatchers.Default) { manager.delete(seeded) }
        runInterruptible(Dispatchers.IO) { deleting.await(10, TimeUnit.SECONDS) }
        val opening = launch(Dispatchers.Default) { manager.open(seeded, spawn = false) }
        delay(300)
        assertFalse(opening.isCompleted, "open waits until the files are gone")
        release.countDown()
        delete.join()
        opening.join()
        val opened = manager.sessions.value.single()
        assertTrue(opened.state.value.entries.isEmpty(), "the deleted history is not loaded")
    }

    @Test
    fun `a fork whose original was deleted before it had a transcript says so instead of starting`() = test {
        seed(seeded)
        val manager = manager()
        val fork = assertNotNull(manager.fork(seeded))
        awaitLog { spawns().size == 1 }
        manager.stop(fork.id) // before a prompt: the fork has no transcript of its own
        manager.delete(seeded)

        manager.send(fork.id, "Reply with the single word: pong")
        val ended = fork.state.first { it.ended }
        val notice = ended.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertEquals(SessionManager.FORK_ORIGINAL_GONE_TEXT, notice.text)
        assertEquals(1, spawns().size, "no process is started to fail on the missing original")
        assertEquals(SessionStatus.STOPPED, fork.status.value)
    }

    @Test
    fun `rename and delete of a session that has no row work on its files alone`() = test {
        seed(seeded)
        val transcript = folder.resolve("$seeded.jsonl")
        val manager = manager()
        manager.rename(seeded, "Omega")
        assertEquals("Omega", Json.parseToJsonElement(transcript.readLines().last()).jsonObject["customTitle"]!!.jsonPrimitive.content)
        manager.summaries.first { summaries -> summaries.any { it.id == seeded && it.title == "Omega" } }
        manager.delete(seeded)
        assertFalse(transcript.exists())
        assertEquals(emptyList(), manager.sessions.value, "neither opened a row")
        assertEquals(emptyList(), spawns(), "nor started a process")
        manager.summaries.first { it.isEmpty() }
    }

    @Test
    fun `delete of an open session without a transcript closes it`() = test {
        val manager = manager()
        val created = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 } // no prompt yet, so the CLI has written no transcript
        manager.select(created.id)
        manager.delete(created.id)
        assertEquals(emptyList(), manager.sessions.value)
        assertNull(manager.selected.value)
        assertEquals(emptyList(), ownFakeProcesses(), "delete returns once the process is gone")
    }

    @Test
    fun `stopping an idle session to make room gives up at once while its spawn lock is held`() = test {
        val manager = manager()
        val created = manager.create(NewSessionOptions())
        created.status.await(SessionStatus.IDLE)
        created.spawnLock.lock() // as a start or a stop of the session does
        try {
            assertFalse(created.stopIfIdle(), "does not wait for the lock")
            assertNotNull(created.liveSession())
        } finally {
            created.spawnLock.unlock()
        }
        assertTrue(created.stopIfIdle())
        created.status.await(SessionStatus.STOPPED)
    }

    @Test
    fun `starts that compete for room under the cap take turns`() = test {
        val manager = manager(maxLive = 2)
        // Created in one go: their starts make room one after the other, so the third stops an idle one first.
        val created = List(3) { manager.create(NewSessionOptions()) }
        // Each start holds its session's spawn lock from create on: once none is held, the three starts, and the stop that made
        // room, are over. Not a count of spawns: the stopped session may have been stopped before its process launched.
        awaitLog { created.none { it.spawnLock.isLocked } }
        awaitLog { created.count { it.status.value == SessionStatus.STOPPED } == 1 } // polled: a status change alone re-checks it
        val live = created.filter { it.liveSession() != null }
        assertEquals(2, live.size)
        awaitLog { live.all { handle -> spawns().any { argv -> argv.zipWithNext().contains("--session-id" to handle.id) } } }
        assertTrue(spawns().size in 2..3, "one process per start at most")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (ownFakeProcesses().size > 2 && System.nanoTime() < deadline) delay(20)
        assertEquals(2, ownFakeProcesses().size)
        assertEquals(2, created.count { it.liveSession() != null })
        assertTrue(created.all { handle -> handle.state.value.entries.none { it is ChatEntry.Notice } }, "there was room for each")
    }

    @Test
    fun `deny answers the permission request the CLI is waiting on`() = test {
        seed(seeded)
        val manager = manager(fixtures = "permission")
        val handle = manager.open(seeded)
        handle.status.await(SessionStatus.IDLE)
        manager.send(seeded, "Create the fixture file")
        handle.status.await(SessionStatus.WAITING)
        val request = handle.state.value.pendingPermissions.single().requestId
        manager.deny(seeded, request)
        awaitLog {
            frames().any { frame ->
                val response = frame["response"]?.jsonObject
                frame.type() == "control_response" && response?.get("request_id")?.jsonPrimitive?.content == request &&
                    response["response"]?.jsonObject?.get("behavior")?.jsonPrimitive?.content == "deny"
            }
        }
        // The frame reaches the fake's log before the reducer has applied the answer: wait for the state, do not read it once.
        handle.state.first { it.pendingPermissions.isEmpty() }
    }

    @Test
    fun `where the folders cannot be watched the list is read anyway`() = test {
        seed(seeded)
        val calls = AtomicInteger()
        // The watcher asks first, and gives up on a folder it cannot look up; every listing after that finds the folder.
        val index = SessionIndex({ if (calls.getAndIncrement() == 0) throw InvalidPathException("", "not watched") else listOf(folder) }, "/project")
        SessionManager(scope, index, DefaultProcessLauncher(), { fakeClaude }, dir, maxLive = { 4 })
            .summaries.first { summaries -> summaries.map { it.id } == listOf(seeded) }
    }

    @Test
    fun `an open does not keep a history read before a delete that came and went meanwhile`() = test {
        seed(seeded)
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = manager(
            read = { file ->
                val loaded = TranscriptReader.read(file) // read in full before the delete starts
                read.countDown()
                runInterruptible(Dispatchers.IO) { release.await(10, TimeUnit.SECONDS) }
                loaded
            },
        )
        val opening = launch(Dispatchers.Default) { manager.open(seeded, spawn = false) }
        runInterruptible(Dispatchers.IO) { read.await(10, TimeUnit.SECONDS) }
        manager.delete(seeded) // no row yet: it starts and ends while the open waits
        assertFalse(folder.resolve("$seeded.jsonl").exists())
        release.countDown()
        opening.join()
        assertEquals(emptyList(), manager.sessions.value.single().state.value.entries, "the deleted history is not shown")
    }

    @Test
    fun `a turn that goes from waiting for a permission straight to idle asks for attention`() = test {
        // The status is a state, which conflates: the RUNNING between an answered request and the end of the turn may never be
        // seen. Here there is none: the turn's result comes while the request is still pending.
        val process = ScriptedProcess(ProcessBuilder("sh", "-c", "cat > /dev/null").start())
        val index = SessionIndex({ listOf(folder) }, "/project")
        val manager = SessionManager(scope, index, { _, _ -> process }, { "claude" }, dir, maxLive = { 4 })
        val handle = manager.create(NewSessionOptions())
        try {
            handle.status.first { it == SessionStatus.IDLE }
            process.script.println(Fixtures.lines("permission").first { "\"can_use_tool\"" in it })
            handle.status.first { it == SessionStatus.WAITING }
            // The user looks at the request, which clears the attention it asked for, then at no session.
            manager.select(handle.id)
            manager.select(null)
            assertFalse(handle.attention.value)
            process.script.println(Fixtures.lines("plain-reply").first { "\"type\":\"result\"" in it })
            handle.status.first { it == SessionStatus.IDLE }
            assertEquals(true, withTimeoutOrNull(5_000) { handle.attention.first { it } }, "the end of the turn asks for attention")
        } finally {
            manager.close(handle.id)
        }
    }

    /** A manager whose one process is [process], and that process's session, started and idle, with no session selected. */
    private suspend fun scripted(process: ScriptedProcess): Pair<SessionManager, SessionHandle> {
        val index = SessionIndex({ listOf(folder) }, "/project")
        val manager = SessionManager(scope, index, { _, _ -> process }, { "claude" }, dir, maxLive = { 4 })
        val handle = manager.create(NewSessionOptions())
        handle.status.first { it == SessionStatus.IDLE }
        return manager to handle
    }

    @Test
    fun `a permission answered outside a turn does not ask for attention as a finished turn`() = test {
        val process = ScriptedProcess(ProcessBuilder("sh", "-c", "cat > /dev/null").start())
        val (manager, handle) = scripted(process)
        try {
            // A request while no turn runs: it asks for attention as a request, and the user looks at it.
            process.script.println(Fixtures.lines("permission").first { "\"can_use_tool\"" in it })
            handle.status.first { it == SessionStatus.WAITING }
            // The manager follows the status in a coroutine of its own: looking before it has seen WAITING would race its attention.
            handle.attention.first { it }
            manager.select(handle.id)
            manager.select(null)
            manager.allow(handle.id, handle.state.value.pendingPermissions.single().requestId, always = false)
            handle.status.first { it == SessionStatus.IDLE }
            assertNull(withTimeoutOrNull(1_000) { handle.attention.first { it } }, "no turn ended")
        } finally {
            manager.close(handle.id)
        }
    }

    @Test
    fun `a process that fails during a turn asks for attention, and a Stop does not`() = test {
        // The process exits with code 3 once the test lets it, in the middle of the turn.
        val gate = dir.resolve("exit-now")
        val failing = ScriptedProcess(ProcessBuilder("sh", "-c", "while [ ! -e '$gate' ]; do sleep 0.05; done; exit 3").start())
        val (manager, handle) = scripted(failing)
        try {
            manager.send(handle.id, "Count to a hundred")
            handle.status.first { it == SessionStatus.RUNNING }
            gate.writeText("")
            failing.waitFor()
            failing.script.close() // its output ends with it
            handle.status.first { it == SessionStatus.STOPPED }
            assertEquals(true, withTimeoutOrNull(5_000) { handle.attention.first { it } }, "the failed turn asks for attention")
            assertEquals(TurnEnd.FAILED, handle.state.value.lastTurnEnd)
        } finally {
            manager.close(handle.id)
        }

        val stopped = ScriptedProcess(ProcessBuilder("sh", "-c", "cat > /dev/null").start())
        val (other, second) = scripted(stopped)
        try {
            other.send(second.id, "Count to a hundred")
            second.status.first { it == SessionStatus.RUNNING }
            other.stop(second.id)
            second.status.first { it == SessionStatus.STOPPED }
            assertNull(withTimeoutOrNull(1_000) { second.attention.first { it } }, "the user stopped it")
        } finally {
            other.close(second.id)
        }
    }

    @Test
    fun `the notice without a claude says it is the claude CLI that Tandem works with, not Tandem itself`() {
        assertEquals(
            "The claude CLI was not found. Tandem works with Claude Code: install it from https://code.claude.com, sign in once " +
                "with `claude` in a terminal, then reopen this tool window.",
            SessionManager.NO_CLI_TEXT,
        )
    }

    @Test
    fun `a turn that ends while a resumed-as-new session is taken over asks for attention`() = test {
        val process = ScriptedProcess(ProcessBuilder("sh", "-c", "cat > /dev/null").start())
        val (manager, handle) = scripted(process)
        val resumedAs = UUID.randomUUID().toString()
        // Between the new row taking the process and the manager adding it, the turn in progress ends.
        manager.beforeTakenOverAdded = { taken ->
            process.script.println(Fixtures.lines("plain-reply").first { "\"type\":\"result\"" in it })
            taken.state.first { it.turnsEnded == 1 }
        }
        try {
            // The process reports another session than the one it was started for: a new row takes it over.
            process.script.println(Fixtures.lines("plain-reply").first { "\"subtype\":\"init\"" in it }.replace("<session_id-2>", resumedAs))
            val taken = withTimeout(5_000) { manager.sessions.first { open -> open.any { it.id == resumedAs } } }.single { it.id == resumedAs }
            assertEquals(true, withTimeoutOrNull(5_000) { taken.attention.first { it } }, "the turn that ended meanwhile asks for attention")
        } finally {
            manager.sessions.value.forEach { manager.close(it.id) }
        }
    }

    /** The `response` object of each `control_response` the host wrote, in order. */
    private fun answers(): List<JsonObject> = frames().filter { it.type() == "control_response" }.map { it["response"]!!.jsonObject }

    /** The host's answer to request [requestId]: the inner `response` of its single `control_response`. */
    private fun answerTo(requestId: String): JsonObject =
        answers().single { it["request_id"]?.jsonPrimitive?.content == requestId }["response"]!!.jsonObject

    /** Waits until the fake asks permission request [requestId], and returns it. */
    private suspend fun SessionHandle.awaitRequest(requestId: String): PendingPermission =
        state.first { s -> s.pendingPermissions.any { it.requestId == requestId } }.pendingPermissions.single { it.requestId == requestId }

    private suspend fun SessionHandle.awaitTurns(count: Int): ChatState = state.first { it.turnsEnded == count }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `spawn passes thinking display summarized and initialize forwards subagent text`() = test {
        val manager = manager()
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Reply with the single word: pong")
        assertEquals(TurnEnd.COMPLETED, handle.awaitTurns(1).lastTurnEnd)

        val argv = spawns().single()
        val flag = argv.indexOf("--thinking-display")
        assertTrue(flag > 0, argv.toString())
        assertEquals("summarized", argv[flag + 1])
        assertEquals("--include-partial-messages", argv[flag - 1])
        val requests = frames().filter { it.type() == "control_request" }
        // No set_max_thinking_tokens: the fake answers the host's requests in recorded order.
        assertEquals(listOf("initialize"), requests.map { it.subtype() })
        assertEquals(true, requests.single()["request"]!!.jsonObject["forwardSubagentText"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `answering a question sends the picked label`() = test {
        val manager = manager(fixtures = "ask-single")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Ask me which colour I like")
        val p = handle.awaitRequest("<request_id-27>")
        manager.answer(handle.id, p.requestId, PermissionAnswers.questionAnswers(p, mapOf("Which colour?" to "Blue")))
        assertEquals(TurnEnd.COMPLETED, handle.awaitTurns(1).lastTurnEnd)

        val response = answerTo("<request_id-27>")
        assertEquals("allow", response.string("behavior"))
        assertEquals("Blue", response["updatedInput"]!!.jsonObject["answers"]!!.jsonObject.string("Which colour?"))
        assertEquals(p.input["questions"], response["updatedInput"]!!.jsonObject["questions"])
        assertEquals("<id-12>", response.string("toolUseID"))
    }

    @Test
    fun `answering a multi-select joins the labels`() = test {
        val manager = manager(fixtures = "ask-multi")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Ask me which toppings I like")
        val p = handle.awaitRequest("<request_id-32>")
        val question = "Which toppings would you like?"
        manager.answer(handle.id, p.requestId, PermissionAnswers.questionAnswers(p, mapOf(question to "Cheese, Ham")))
        assertEquals(TurnEnd.COMPLETED, handle.awaitTurns(1).lastTurnEnd)

        val answers = answerTo("<request_id-32>")["updatedInput"]!!.jsonObject["answers"]!!.jsonObject
        assertEquals("Cheese, Ham", answers.string(question))
    }

    @Test
    fun `cancelling a question denies with cancelled and the turn goes on`() = test {
        val manager = manager(fixtures = "ask-deny")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Ask me which colour I like")
        val p = handle.awaitRequest("<request_id-27>")
        manager.answer(handle.id, p.requestId, PermissionAnswers.cancelQuestion())
        assertEquals(TurnEnd.COMPLETED, handle.awaitTurns(1).lastTurnEnd)

        val response = answerTo("<request_id-27>")
        assertEquals("deny", response.string("behavior"))
        assertEquals("cancelled", response.string("message"))
        assertEquals("<id-12>", response.string("toolUseID"))
    }

    @Test
    fun `approving a plan to start coding sends setMode and the mode follows the status line`() = test {
        val manager = manager(fixtures = "plan-approve")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Plan a changelog")
        val p = handle.awaitRequest("<request_id-97>")
        manager.answer(handle.id, p.requestId, PermissionAnswers.approvePlan(p, startCoding = true))
        val first = handle.awaitTurns(1)
        assertEquals(TurnEnd.COMPLETED, first.lastTurnEnd)
        assertEquals(PermissionMode.ACCEPT_EDITS, first.permissionMode)

        val response = answerTo("<request_id-97>")
        assertEquals("allow", response.string("behavior"))
        val setMode = buildJsonObject {
            put("type", "setMode")
            put("mode", "acceptEdits")
            put("destination", "session")
        }
        assertEquals(setMode, response["updatedPermissions"]!!.jsonArray[0])
        assertEquals(p.input, response["updatedInput"])
        assertEquals("<id-92>", response.string("toolUseID"))

        // The fixture's second turn starts in the mode the approval switched to.
        manager.send(handle.id, "Go on")
        val second = handle.awaitTurns(2)
        assertEquals(TurnEnd.COMPLETED, second.lastTurnEnd)
        assertEquals(PermissionMode.ACCEPT_EDITS, second.permissionMode)
    }

    @Test
    fun `approving a plan keeps the mode without permissions`() = test {
        val manager = manager(fixtures = "plan-disallowed")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Plan a changelog")
        val plan = handle.awaitRequest("<request_id-182>")
        assertEquals("ExitPlanMode", plan.toolName)
        manager.answer(handle.id, plan.requestId, PermissionAnswers.approvePlan(plan, startCoding = false))
        // The next request (Bash, line 213) comes after the status line at 177.
        val bash = handle.awaitRequest("<request_id-225>")
        assertEquals(PermissionMode.DEFAULT, handle.state.value.permissionMode)

        val response = answerTo("<request_id-182>")
        assertEquals("allow", response.string("behavior"))
        assertFalse("updatedPermissions" in response, response.toString())
        assertEquals(JsonObject(emptyMap()), response["updatedInput"])
        assertEquals("<id-178>", response.string("toolUseID"))

        manager.answer(handle.id, bash.requestId, PermissionAnswers.allowOnce(bash))
        val end = handle.awaitTurns(1)
        assertEquals(TurnEnd.COMPLETED, end.lastTurnEnd)
        assertEquals(PermissionMode.DEFAULT, end.permissionMode)
    }

    @Test
    fun `keeping planning denies with the feedback`() = test {
        val manager = manager(fixtures = "plan-deny")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Plan a changelog")
        val first = handle.awaitRequest("<request_id-88>")
        manager.answer(handle.id, first.requestId, PermissionAnswers.keepPlanning("also add a date"))
        // A second pending permission arrives: the plan, asked again.
        val second = handle.awaitRequest("<request_id-156>")
        assertEquals("ExitPlanMode", second.toolName)
        manager.answer(handle.id, second.requestId, PermissionAnswers.approvePlan(second, startCoding = false))
        val write = handle.awaitRequest("<request_id-191>")
        manager.answer(handle.id, write.requestId, PermissionAnswers.allowOnce(write))
        assertEquals(TurnEnd.COMPLETED, handle.awaitTurns(1).lastTurnEnd)

        val response = answerTo("<request_id-88>")
        assertEquals("deny", response.string("behavior"))
        assertEquals("also add a date", response.string("message"))
        assertEquals("<id-83>", response.string("toolUseID"))
    }

    @Test
    fun `lastFrameAt advances on every line`() = test {
        val manager = manager()
        val handle = manager.create(NewSessionOptions())
        // The fixture's first lines come as the process starts, before any prompt.
        val atStart = handle.lastFrameAtMillis.first { it > 0L }
        val seen = Collections.synchronizedList(mutableListOf<Long>())
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { handle.lastFrameAtMillis.collect { seen.add(it) } }
        while (System.currentTimeMillis() <= atStart) delay(1)
        val beforePrompt = System.currentTimeMillis()
        manager.send(handle.id, "Reply with the single word: pong")
        handle.awaitTurns(1)
        val process = handle.liveSession()!!
        // The handle follows the process's own value, which its last line set.
        val last = handle.lastFrameAtMillis.first { it == process.lastFrameAtMillis.value }
        collector.cancel()

        val values = synchronized(seen) { seen.toList() }
        assertEquals(values.sorted(), values, "never goes back")
        assertTrue(values.distinct().size > 1, values.toString())
        assertTrue(last >= beforePrompt, "$last < $beforePrompt")
    }

    @Test
    fun `answering a permission that is no longer pending writes no frame`() = test {
        val manager = manager(fixtures = "permission")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Create the fixture file")
        val p = handle.awaitRequest("<request_id-26>")
        manager.answer(handle.id, p.requestId, PermissionAnswers.allowOnce(p))
        manager.answer(handle.id, p.requestId, PermissionAnswers.allowOnce(p))
        assertEquals(TurnEnd.COMPLETED, handle.awaitTurns(1).lastTurnEnd)
        manager.stop(handle.id) // once the process is gone, the log holds every frame the host wrote to it

        assertEquals(1, answers().count { it["request_id"]?.jsonPrimitive?.content == p.requestId })
    }

    @Test
    fun `allowing for the session writes only addRules`() = test {
        val manager = manager(fixtures = "permission")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Create the fixture file")
        val p = handle.awaitRequest("<request_id-26>")
        manager.allow(handle.id, p.requestId, always = true)
        assertEquals(TurnEnd.COMPLETED, handle.awaitTurns(1).lastTurnEnd)

        val response = answerTo("<request_id-26>")
        assertEquals("allow", response.string("behavior"))
        val updated = response["updatedPermissions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("addRules"), updated.map { it.string("type") })
        assertEquals("session", updated.single().string("destination"))
    }

    // MCP controls (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 4.2, 9.2). The fake answers every mcp_* request
    // inline; on `mcp-status` its first mcp_status gets the pending payload of `:2` (its rule (c)), which mcpSession() uses up.

    /** The sign-in link of `mcp-status:40`. */
    private val authUrl: String =
        Json.parseToJsonElement(Fixtures.lines("mcp-status")[39]).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject
            .string("authUrl")!!

    /** A new session of this manager, once its process is attached. */
    // --- Desktop parity (spec 4.2, 9.2): the context breakdown and the usage card through the fake's rules (h)-(j) ---

    private fun ChatState.usageNotice(): ChatEntry.Notice? = entries.filterIsInstance<ChatEntry.Notice>().lastOrNull { it.usage != null }

    /** A session of [manager] that ran its first prompt to the end. */
    private suspend fun SessionManager.afterFirstTurn(): SessionHandle {
        val handle = started()
        send(handle.id, "one")
        handle.state.first { it.turnsEnded == 1 && !it.busy }
        return handle
    }

    /** The usage card of [handle] once neither part is loading. */
    private suspend fun loadedCard(handle: SessionHandle): ChatEntry.Notice = handle.state.first { state ->
        val card = state.usageNotice()?.usage
        card != null && card.session != UsagePartState.Loading && card.history != UsagePartState.Loading
    }.usageNotice()!!

    @Test
    fun `contextBreakdown coalesces a second call`() = test {
        val manager = manager(fixtures = "parity-context", delayMs = 300)
        val handle = manager.afterFirstTurn()
        val answers = List(3) { async { manager.contextBreakdown(handle.id) } }.awaitAll()
        val breakdowns = answers.map { (it as? McpResult.Ok)?.value ?: fail("expected an answer, got $it") }
        assertEquals(1, breakdowns.distinct().size, "every caller gets the same breakdown")
        assertEquals(10L, breakdowns.first().categories.single { it.id == "messages" }.tokens, "rule (h) serves parity-context:2")
        assertTrue(requests("get_context_usage").size <= 2, "one run and one shared follow-up: ${requests("get_context_usage").size}")
        assertEquals(breakdowns.first(), handle.state.value.contextBreakdown)
    }

    @Test
    fun `NotRunning without a process`() = test {
        val manager = manager()
        val handle = manager.started()
        manager.stop(handle.id)
        assertEquals(McpResult.Failed(McpError.NotRunning), manager.contextBreakdown(handle.id))
        assertEquals(McpResult.Failed(McpError.NotRunning), manager.contextBreakdown("no-such-session"))
        assertEquals(emptyList(), requests("get_context_usage"))
    }

    @Test
    fun `a CLI without get_context_usage answers its error`() = test {
        val manager = manager(fixtures = "plain-reply")
        val handle = manager.started()
        assertEquals(
            McpResult.Failed(McpError.Rejected("Unsupported control request subtype: get_context_usage")),
            manager.contextBreakdown(handle.id),
        )
    }

    @Test
    fun `a usage prompt fills its card from the CLI's answers`() = test {
        val manager = manager(fixtures = "parity-usage")
        val handle = manager.afterFirstTurn()
        manager.send(handle.id, "/usage")
        val card = loadedCard(handle).usage!!
        val session = (card.session as UsagePartState.Loaded).value
        assertEquals(0.1523063, session.totalCostUsd)
        val history = (card.history as UsagePartState.Loaded).value
        assertEquals(23, history?.day?.sessionCount)
        val usage = requests("get_usage").map { it["request"]!!.jsonObject }
        assertEquals(2, usage.size)
        assertEquals(true, usage[0]["skip_behaviors"]?.jsonPrimitive?.boolean)
        assertFalse("skip_behaviors" in usage[1], usage[1].toString())
    }

    @Test
    fun `retryUsage on a stopped session ends the part`() = test {
        val manager = manager(fixtures = "parity-usage")
        val handle = manager.afterFirstTurn()
        manager.send(handle.id, "/usage")
        val notice = loadedCard(handle)
        manager.stop(handle.id)
        val sent = requests("get_usage").size

        manager.retryUsage(handle.id, notice.sourceUuid!!, UsagePart.SESSION)
        val card = handle.state.value.entries.filterIsInstance<ChatEntry.Notice>().single { it.id == notice.id }.usage!!
        assertEquals(UsagePartState.Failed(McpError.NotRunning), card.session)
        assertEquals(notice.usage!!.history, card.history)
        assertEquals(sent, requests("get_usage").size, "no frame")
        assertEquals(SessionStatus.STOPPED, handle.status.value)
    }

    private suspend fun SessionManager.started(): SessionHandle {
        val handle = create(NewSessionOptions())
        handle.status.first { it == SessionStatus.IDLE }
        return handle
    }

    /**
     * A manager on the `mcp-status` fixture and a session of it that has listed its servers once, which uses up the fake's
     * pending answer (rule (c)).
     */
    private suspend fun mcpSession(
        environment: Map<String, String> = emptyMap(),
        every: Duration = 2.seconds,
        timeout: suspend () -> Unit = { delay(3.minutes) },
    ): Pair<SessionManager, SessionHandle> {
        val manager = manager(fixtures = "mcp-status", environment = environment, mcpAuthEvery = every, mcpAuthTimeout = timeout)
        val handle = manager.started()
        manager.refreshMcp(handle.id).ok()
        return manager to handle
    }

    private fun <T> McpResult<T>.ok(): T = when (this) {
        is McpResult.Ok -> value
        is McpResult.Failed -> fail("expected an answer, got $this")
    }

    /** The control requests of [subtype] the host wrote, in order. */
    private fun requests(subtype: String): List<JsonObject> = frames().filter { it.type() == "control_request" && it.subtype() == subtype }

    /** The frames the host wrote after its first control request of [subtype]. */
    private fun framesAfter(subtype: String): List<JsonObject> = frames().dropWhile { it.subtype() != subtype }.drop(1)

    private fun ChatState.notices(level: NoticeLevel): List<String> =
        entries.filterIsInstance<ChatEntry.Notice>().filter { it.level == level }.map { it.text }

    private suspend fun SessionHandle.awaitOutcome(server: String): McpAuthOutcome =
        mcpAuth.first { it[server]?.outcome != null }[server]!!.outcome!!

    @Test
    fun `the MCP panel's start resumes a session without a process, sends no prompt, and its servers become the project's known ones`() = test {
        seed(seeded)
        val manager = manager(fixtures = "mcp-status")
        val resumed = manager.open(seeded, spawn = false)
        assertEquals(SessionStatus.STOPPED, resumed.status.value)
        assertEquals(McpResult.Failed(McpError.NotRunning), manager.refreshMcp(seeded))
        assertEquals(emptyList(), manager.knownMcpServers.value, "no process has listed any yet")

        assertEquals(McpStart.Running, manager.startForMcp(seeded, overCap = false))
        val listed = manager.refreshMcp(seeded).ok()
        assertEquals(listed, resumed.mcpServers.first { it.detailed }.servers)
        assertEquals(McpStart.Running, manager.startForMcp(seeded, overCap = false), "a running session is not started again")

        val argv = spawns().single()
        assertEquals(seeded, argv.valueOf("--resume"), "resumed with its usual arguments")
        assertFalse("--session-id" in argv, argv.toString())
        assertTrue(frames().none { it.type() == "user" }, "no prompt is sent")
        assertEquals(listed.map { it.lastKnown() }, manager.knownMcpServers.value)
        assertEquals(listed.map { it.name }, manager.knownMcpServers.value.map { it.name })
    }

    @Test
    fun `the known servers keep only what the panel shows`() {
        val server = McpServerInfo(
            "remote",
            McpStatus.CONNECTED,
            "connected",
            error = "boom",
            config = McpConfig("http", command = "npx", args = listOf("--token", "s3cret"), url = "https://user:pw@mcp.example.com:8443/k3y?token=t"),
        )
        val known = server.lastKnown()
        assertEquals(McpConfig("http", command = "npx", args = emptyList(), url = "https://mcp.example.com:8443"), known.config)
        assertEquals(server.copy(config = known.config), known, "everything else as the panel shows it")
        assertNull(McpServerInfo("bare", McpStatus.FAILED, "failed").lastKnown().config)
    }

    @Test
    fun `the MCP panel's start stops an idle session to make room`() = test {
        seed(seeded)
        val manager = manager(fixtures = "mcp-status", maxLive = 1)
        val idle = manager.started()
        manager.open(seeded, spawn = false)

        assertEquals(McpStart.Running, manager.startForMcp(seeded, overCap = false))
        idle.status.await(SessionStatus.STOPPED)
        awaitLog { spawns().size == 2 }
        assertEquals(seeded, spawns().last().valueOf("--resume"))
    }

    @Test
    fun `the MCP panel's start never stops a session in a turn, and goes over the cap only when asked`() = test {
        seed(seeded)
        val manager = manager(fixtures = "permission,mcp-status", maxLive = 1)
        val busy = manager.create(NewSessionOptions())
        manager.send(busy.id, "Create the fixture file")
        busy.awaitRequest("<request_id-26>")
        val resumed = manager.open(seeded, spawn = false)

        assertEquals(McpStart.NoRoom(1), manager.startForMcp(seeded, overCap = false))
        assertEquals(1, spawns().size, "nothing started")
        assertEquals(SessionStatus.STOPPED, resumed.status.value)
        assertTrue(resumed.state.value.notices(NoticeLevel.INFO).isEmpty(), "nothing in the chat")

        // Start session: the user asked for it, so it starts over the cap, as a prompt would, and the chat says so.
        assertEquals(McpStart.Running, manager.startForMcp(seeded, overCap = true))
        awaitLog { spawns().size == 2 }
        assertEquals(seeded, spawns().last().valueOf("--resume"))
        assertEquals(listOf("Max live sessions exceeded"), resumed.state.value.notices(NoticeLevel.INFO))
        assertEquals(SessionStatus.WAITING, busy.status.value, "the session in a turn runs on")
        manager.refreshMcp(seeded).ok()
    }

    @Test
    fun `the MCP panel's start says why no process could start`() = test {
        seed(seeded)
        val manager = manager(binary = null)
        val resumed = manager.open(seeded, spawn = false)
        assertEquals(McpStart.Failed(SessionManager.NO_CLI_TEXT), manager.startForMcp(seeded, overCap = false))
        assertEquals(SessionStatus.STOPPED, resumed.status.value)
        assertEquals(McpStart.Failed(SessionManager.CLOSED_TEXT), manager.startForMcp("not-open", overCap = false))
    }

    @Test
    fun `a process that cannot launch ends the MCP panel's start with the chat's reason`() = test {
        seed(seeded)
        val manager = manager(binary = dir.resolve("no-such-claude").toString())
        val resumed = manager.open(seeded, spawn = false)
        // The launch fails in the process's own coroutine: the start attaches it, and its end follows.
        assertEquals(McpStart.Running, manager.startForMcp(seeded, overCap = false))
        val ended = resumed.state.first { it.ended }
        assertTrue(ended.notices(NoticeLevel.ERROR).single().startsWith("Could not start `"), ended.notices(NoticeLevel.ERROR).toString())
        val listed = manager.refreshMcp(seeded)
        assertTrue(listed == McpResult.Failed(McpError.NotRunning) || listed == McpResult.Failed(McpError.Ended), listed.toString())
    }

    @Test
    fun `a new process starts without the servers of the one before`() = test {
        val (manager, handle) = mcpSession()
        assertTrue(handle.state.value.mcp.servers.isNotEmpty())
        manager.stop(handle.id)
        assertTrue(handle.state.value.mcp.servers.isNotEmpty(), "a stopped session keeps its last list, which the badge does not count")

        assertEquals(McpStart.Running, manager.startForMcp(handle.id, overCap = false))
        assertEquals(McpState(), handle.state.value.mcp, "its servers come with its own init or mcp_status")
    }

    @Test
    fun `opening the servers sends mcp_status and the handle publishes the rows`() = test {
        val manager = manager(fixtures = "mcp-status")
        val handle = manager.started()

        val listed = manager.refreshMcp(handle.id).ok()

        assertEquals(listOf("echo", "broken", "authy"), listed.map { it.name })
        assertEquals(1, requests("mcp_status").size)
        assertEquals(emptyList(), prompts(), "the request goes out before any prompt")
        val published = handle.mcpServers.first { it.detailed }
        assertEquals(listOf("echo", "broken", "authy"), published.servers.map { it.name })
    }

    @Test
    fun `toggle sends mcp_toggle and a following refreshMcp shows disabled`() = test {
        val (manager, handle) = mcpSession()

        assertEquals(McpResult.Ok(Unit), manager.toggleMcp(handle.id, "echo", enabled = false))

        val toggle = requests("mcp_toggle").single()["request"]!!.jsonObject
        assertEquals("echo", toggle.string("serverName"))
        assertEquals(false, toggle["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(McpStatus.DISABLED, manager.refreshMcp(handle.id).ok().single { it.name == "echo" }.status)
        handle.mcpServers.first { mcp -> mcp.servers.any { it.name == "echo" && it.status == McpStatus.DISABLED } }
    }

    // spec: toggleMcp and reconnectMcp refuse Tandem's own server without a request
    @Test
    fun `toggleMcp and reconnectMcp refuse Tandem's own server without a request`() = test {
        val (manager, handle) = mcpSession()
        val refused = McpResult.Failed(McpError.Rejected("Tandem's own server cannot be changed"))

        assertEquals(refused, manager.toggleMcp(handle.id, "tandem", enabled = false))
        assertEquals(refused, manager.reconnectMcp(handle.id, "tandem"))
        // Ordered behind both: had either sent a request, the fake's log would hold it before this one's answer.
        manager.refreshMcp(handle.id).ok()
        assertEquals(emptyList(), requests("mcp_toggle"))
        assertEquals(emptyList(), requests("mcp_reconnect"))
    }

    @Test
    fun `editEvent shows an edit's review and Accept all in the chat`() = test {
        val tools = object : HostTools {
            override suspend fun call(call: ToolCall) = error("not called")
            override fun cancel(session: SessionId, requestId: JsonElement) {}
            override fun cancelAll(session: SessionId) {}
        }
        val manager = manager(hostTools = tools)
        val handle = manager.started()
        assertSame(tools, handle.hostTools)
        val review = EditReview("a.kt", EditPhase.OPEN)

        manager.editEvent(handle.id, SessionEvent.EditPhase("t1", review))
        manager.editEvent(handle.id, SessionEvent.AcceptAll(true))
        manager.editEvent("no-such-session", SessionEvent.AcceptAll(true))

        val state = handle.state.first { it.acceptAll }
        assertEquals(mapOf("t1" to review), state.edits)
    }

    @Test
    fun `toggle of an unknown server returns Server not found`() = test {
        val (manager, handle) = mcpSession()
        assertEquals(McpResult.Failed(McpError.Rejected("Server not found: nosuch")), manager.toggleMcp(handle.id, "nosuch", enabled = false))
    }

    @Test
    fun `reconnect of a failed server returns the CLI's error`() = test {
        val (manager, handle) = mcpSession()
        assertEquals(McpResult.Failed(McpError.Rejected("Connection closed")), manager.reconnectMcp(handle.id, "broken"))
        assertEquals("broken", requests("mcp_reconnect").single()["request"]!!.jsonObject.string("serverName"))
    }

    // spec: reconnect of a needs-auth server returns Server status: needs-auth
    @Test
    fun `reconnect of a needs-auth server returns Server status - needs-auth`() = test {
        val (manager, handle) = mcpSession()
        assertEquals(McpResult.Failed(McpError.Rejected("Server status: needs-auth")), manager.reconnectMcp(handle.id, "authy"))
    }

    @Test
    fun `startMcpAuth opens the auth url and the poll sees the server connect`() = test {
        val (manager, handle) = mcpSession(environment = mapOf("TANDEM_FAKE_AUTH_DONE_MS" to "500"), every = 50.milliseconds)
        val opened = Collections.synchronizedList(mutableListOf<String>())

        val start = manager.startMcpAuth(handle.id, "authy") { opened.add(it) }.ok()

        assertEquals(authUrl, start.authUrl)
        assertEquals(listOf(authUrl), opened.toList())
        assertNotNull(handle.mcpAuth.value["authy"], "the wait is published as soon as the browser is asked")
        assertEquals(McpAuthOutcome.Connected, handle.awaitOutcome("authy"))
        val chat = handle.state.first { it.notices(NoticeLevel.INFO).isNotEmpty() }
        assertEquals(listOf("Signed in to authy."), chat.notices(NoticeLevel.INFO))
        assertEquals(listOf(authUrl), opened.toList(), "the browser opens once")
        assertEquals(emptySet(), handle.mcpLoginHinted.value)
    }

    @Test
    fun `a sign-in link that does not open posts the error without the terminal hint`() = test {
        val (manager, handle) = mcpSession(every = 50.milliseconds)

        val result = manager.startMcpAuth(handle.id, "authy") { throw java.io.IOException("no browser") }

        assertEquals(McpResult.Failed(McpError.Rejected("no browser", linkFailed = true)), result)
        val chat = handle.state.first { it.notices(NoticeLevel.ERROR).isNotEmpty() }
        assertEquals(listOf("Sign-in to authy failed: no browser"), chat.notices(NoticeLevel.ERROR))
        assertEquals(emptySet(), handle.mcpLoginHinted.value, "mcp_authenticate itself succeeded (spec 4.2)")
        assertEquals(emptyMap(), handle.mcpAuth.value)
    }

    @Test
    fun `the sign-in poll times out while the server still needs auth`() = test {
        // The time limit runs out once the fake has read the poll's second mcp_status, however fast the machine is.
        val asked = { framesAfter("mcp_authenticate").count { it.subtype() == "mcp_status" } }
        val (manager, handle) = mcpSession(every = 50.milliseconds, timeout = { awaitLog { asked() >= 2 } })

        manager.startMcpAuth(handle.id, "authy") {}.ok()

        assertEquals(McpAuthOutcome.StillNeedsAuth, handle.awaitOutcome("authy"))
        val chat = handle.state.first { it.notices(NoticeLevel.WARNING).isNotEmpty() }
        assertEquals(listOf("Sign-in to authy did not complete within 3 minutes."), chat.notices(NoticeLevel.WARNING))
        assertTrue(asked() >= 2, "the poll asked more than once")
    }

    // MCP spec 3.3 steps 3 and 5: a pending server (reconnecting after the callback, unrecorded) keeps the wait going, and every
    // outcome but Cancel and the session's end has a chat notice.
    @Test
    fun `a sign-in waits through pending and says when the server changed or went`() {
        fun listed(status: McpStatus, error: String? = null) = listOf(McpServerInfo("authy", status, status.wire, error = error))
        assertNull(signInOutcome(listed(McpStatus.NEEDS_AUTH), "authy"))
        assertNull(signInOutcome(listed(McpStatus.PENDING), "authy"), "pending goes on waiting")
        assertEquals(McpAuthOutcome.Connected, signInOutcome(listed(McpStatus.CONNECTED), "authy"), "then connected ends it")
        assertEquals(McpAuthOutcome.Removed, signInOutcome(emptyList(), "authy"))
        assertEquals(McpAuthOutcome.Changed(McpStatus.FAILED, "boom"), signInOutcome(listed(McpStatus.FAILED, "boom"), "authy"))

        assertEquals(NoticeLevel.WARNING to "Sign-in to authy ended: authy is no longer configured.", signInNotice("authy", McpAuthOutcome.Removed))
        assertEquals(
            NoticeLevel.WARNING to "Sign-in to authy ended: the server is now disabled.",
            signInNotice("authy", McpAuthOutcome.Changed(McpStatus.DISABLED, null)),
        )
        assertEquals(
            NoticeLevel.WARNING to "Sign-in to authy ended: the server is now in an unknown state.",
            signInNotice("authy", McpAuthOutcome.Changed(McpStatus.OTHER, null)),
        )
        assertEquals(NoticeLevel.ERROR to "Sign-in to authy failed: boom", signInNotice("authy", McpAuthOutcome.Changed(McpStatus.FAILED, "boom")))
        assertNull(signInNotice("authy", McpAuthOutcome.Failed(McpError.Ended)), "the chat shows the session's end already")
    }

    @Test
    fun `an mcp_status error answer is Rejected with the CLI's text`() = test {
        val manager = manager(fixtures = "plain-reply")
        val handle = manager.started()
        assertEquals(McpResult.Failed(McpError.Rejected("Unknown subtype")), manager.refreshMcp(handle.id))
    }

    @Test
    fun `startMcpAuth answered with Unknown subtype opens nothing and posts the error notice`() = test {
        val manager = manager(fixtures = "plain-reply")
        val handle = manager.started()
        val opened = Collections.synchronizedList(mutableListOf<String>())

        val result = manager.startMcpAuth(handle.id, "authy") { opened.add(it) }

        assertEquals(McpResult.Failed(McpError.Rejected("Unknown subtype")), result)
        val chat = handle.state.first { it.notices(NoticeLevel.ERROR).isNotEmpty() }
        assertEquals(listOf("Sign-in to authy failed: Unknown subtype"), chat.notices(NoticeLevel.ERROR))
        assertEquals(setOf("authy"), handle.mcpLoginHinted.value)
        assertEquals(emptyMap(), handle.mcpAuth.value)
        assertEquals(emptyList(), opened.toList())
    }

    @Test
    fun `toggle and reconnect answered with Unknown subtype are Rejected with the CLI's text`() = test {
        val manager = manager(fixtures = "plain-reply")
        val handle = manager.started()
        val unknown = McpResult.Failed(McpError.Rejected("Unknown subtype"))
        assertEquals(unknown, manager.toggleMcp(handle.id, "echo", enabled = false))
        assertEquals(unknown, manager.reconnectMcp(handle.id, "echo"))
    }

    @Test
    fun `startMcpAuth without an authUrl refreshes once`() = test {
        val (manager, handle) = mcpSession(every = 50.milliseconds)
        val opened = Collections.synchronizedList(mutableListOf<String>())

        val start = manager.startMcpAuth(handle.id, "echo") { opened.add(it) }.ok()

        assertNull(start.authUrl)
        assertEquals(McpAuthOutcome.Connected, handle.awaitOutcome("echo"))
        val chat = handle.state.first { it.notices(NoticeLevel.INFO).isNotEmpty() }
        assertEquals(listOf("Signed in to echo."), chat.notices(NoticeLevel.INFO))
        delay(300) // a poll every 50 ms would have asked again by now
        assertEquals(1, framesAfter("mcp_authenticate").count { it.subtype() == "mcp_status" })
        assertEquals(emptyList(), opened.toList())
    }

    @Test
    fun `the sign-in steps survive the caller's cancellation before and after the answer`() = test {
        val environment = mapOf("TANDEM_FAKE_AUTH_DONE_MS" to "300", "TANDEM_FAKE_EXTRA_NEEDS_AUTH" to "authy2")
        val (manager, handle) = mcpSession(environment = environment, every = 50.milliseconds)
        val opened = Collections.synchronizedList(mutableListOf<String>())

        // Cancelled while mcp_authenticate is in flight.
        val before = launch(start = CoroutineStart.UNDISPATCHED) { manager.startMcpAuth(handle.id, "authy") { opened.add(it) } }
        before.cancel()
        assertEquals(McpAuthOutcome.Connected, handle.awaitOutcome("authy"))
        assertEquals(listOf(authUrl), opened.toList())

        // Cancelled once the answer is in, while the browser is being asked.
        val asked = CompletableDeferred<String>()
        val browserDone = CompletableDeferred<Unit>()
        val after = launch {
            manager.startMcpAuth(handle.id, "authy2") { url ->
                asked.complete(url)
                browserDone.await()
            }
        }
        assertEquals(authUrl, asked.await())
        after.cancelAndJoin()
        browserDone.complete(Unit)
        assertEquals(McpAuthOutcome.Connected, handle.awaitOutcome("authy2"))
        val chat = handle.state.first { it.notices(NoticeLevel.INFO).size == 2 }
        assertEquals(listOf("Signed in to authy.", "Signed in to authy2."), chat.notices(NoticeLevel.INFO))
    }

    @Test
    fun `two sign-in waits run side by side`() = test {
        val environment = mapOf("TANDEM_FAKE_EXTRA_NEEDS_AUTH" to "authy2", "TANDEM_FAKE_AUTH_DONE_MS" to "500")
        val (manager, handle) = mcpSession(environment = environment, every = 50.milliseconds)

        manager.startMcpAuth(handle.id, "authy") {}.ok()
        manager.startMcpAuth(handle.id, "authy2") {}.ok()

        assertEquals(setOf("authy", "authy2"), handle.mcpAuth.value.keys)
        val settled = handle.mcpAuth.first { waits -> waits.size == 2 && waits.values.all { it.outcome != null } }
        assertEquals(mapOf("authy" to McpAuthOutcome.Connected, "authy2" to McpAuthOutcome.Connected), settled.mapValues { it.value.outcome })
        val chat = handle.state.first { it.notices(NoticeLevel.INFO).size == 2 }
        assertEquals(setOf("Signed in to authy.", "Signed in to authy2."), chat.notices(NoticeLevel.INFO).toSet())

        // A second run: cancelling one wait leaves the other one running.
        val second = manager.started()
        manager.refreshMcp(second.id).ok()
        manager.startMcpAuth(second.id, "authy") {}.ok()
        manager.startMcpAuth(second.id, "authy2") {}.ok()
        manager.cancelMcpAuth(second.id, "authy")
        assertEquals(setOf("authy2"), second.mcpAuth.value.keys)
        assertEquals(McpAuthOutcome.Connected, second.awaitOutcome("authy2"))
        assertNull(second.mcpAuth.value["authy"])
        val secondChat = second.state.first { it.notices(NoticeLevel.INFO).isNotEmpty() }
        delay(200) // a cancelled wait would have ended by now too
        assertEquals(listOf("Signed in to authy2."), second.state.value.notices(NoticeLevel.INFO), secondChat.toString())
    }

    @Test
    fun `a stopped session answers NotRunning`() = test {
        val manager = manager(fixtures = "mcp-status")
        val handle = manager.started()
        manager.stop(handle.id)
        val errorsBefore = handle.state.value.notices(NoticeLevel.ERROR)
        val opened = Collections.synchronizedList(mutableListOf<String>())
        val notRunning = McpResult.Failed(McpError.NotRunning)

        assertEquals(notRunning, manager.refreshMcp(handle.id))
        assertEquals(notRunning, manager.toggleMcp(handle.id, "echo", enabled = false))
        assertEquals(notRunning, manager.reconnectMcp(handle.id, "broken"))
        assertEquals(notRunning, manager.startMcpAuth(handle.id, "authy") { opened.add(it) })
        assertEquals(notRunning, manager.refreshMcp("no-such-session"))
        manager.cancelMcpAuth(handle.id, "authy")
        manager.cancelMcpAuth("no-such-session", "authy")

        assertEquals(emptyList(), frames().filter { it.subtype()?.startsWith("mcp_") == true })
        assertEquals(emptyList(), opened.toList())
        assertEquals(emptyMap(), handle.mcpAuth.value)
        assertEquals(emptySet(), handle.mcpLoginHinted.value)
        assertEquals(errorsBefore, handle.state.value.notices(NoticeLevel.ERROR), "no notice for a session that is not running")
    }

    @Test
    fun `two actions run one after the other`() = test {
        // A process whose input the test reads, and whose answers the test writes.
        val written = dir.resolve("host-frames.log")
        val process = ScriptedProcess(ProcessBuilder("sh", "-c", "cat > '$written'").start())
        val (manager, handle) = scripted(process)
        fun mcpRequests(): List<JsonObject> =
            if (!written.exists()) emptyList()
            else written.readText().split('\n').dropLast(1).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
                .filter { it.type() == "control_request" && it.subtype()?.startsWith("mcp_") == true }
        fun JsonObject.requestId(): String = string("request_id")!!
        try {
            val toggle = async { manager.toggleMcp(handle.id, "echo", enabled = false) }
            val reconnect = async { manager.reconnectMcp(handle.id, "broken") }
            awaitLog { mcpRequests().isNotEmpty() }
            delay(300)
            val first = mcpRequests().single()
            assertEquals("mcp_toggle", first.subtype(), "the second request waits for the first one's answer")

            process.script.println("""{"type":"control_response","response":{"subtype":"success","request_id":"${first.requestId()}"}}""")
            awaitLog { mcpRequests().size == 2 }
            val second = mcpRequests()[1]
            assertEquals("mcp_reconnect", second.subtype())
            process.script.println(
                """{"type":"control_response","response":{"subtype":"error","request_id":"${second.requestId()}","error":"Connection closed"}}""",
            )

            assertEquals(McpResult.Ok(Unit), toggle.await())
            assertEquals(McpResult.Failed(McpError.Rejected("Connection closed")), reconnect.await())
        } finally {
            manager.close(handle.id)
        }
    }

    @Test
    fun `a toggle followed by its refresh completes while a poll runs`() = test {
        val (manager, handle) = mcpSession(every = 50.milliseconds) // authy stays needs-auth: the poll goes on
        manager.startMcpAuth(handle.id, "authy") {}.ok()
        awaitLog { framesAfter("mcp_authenticate").count { it.subtype() == "mcp_status" } >= 2 }

        assertEquals(McpResult.Ok(Unit), manager.toggleMcp(handle.id, "echo", enabled = false))
        assertEquals(McpStatus.DISABLED, manager.refreshMcp(handle.id).ok().single { it.name == "echo" }.status)

        assertNull(handle.mcpAuth.value.getValue("authy").outcome, "the poll still runs")
        manager.cancelMcpAuth(handle.id, "authy")
        assertEquals(emptyMap(), handle.mcpAuth.value)
    }

    @Test
    fun `three refreshes at once share one follow-up run`() = test {
        val (manager, handle) = mcpSession()
        val before = requests("mcp_status").size

        val results = List(3) { async(start = CoroutineStart.UNDISPATCHED) { manager.refreshMcp(handle.id) } }.awaitAll()

        results.forEach { assertEquals(listOf("echo", "broken", "authy"), it.ok().map { server -> server.name }) }
        val sent = requests("mcp_status").size - before
        assertTrue(sent in 1..2, "$sent mcp_status requests for three refreshes at once")
    }

    // Bash mode (spec 12.8): a real /bin/sh runs each command, the fake CLI takes the frames.

    private fun unix() = assumeTrue(!System.getProperty("os.name").lowercase().contains("windows"))

    /** The `user` frames the host wrote, in order. */
    private fun userFrames(): List<JsonObject> = frames().filter { it.type() == "user" }

    private fun JsonObject.content(): String = this["message"]!!.jsonObject["content"]!!.jsonPrimitive.content

    private fun ChatState.shell(): ChatEntry.Shell = entries.filterIsInstance<ChatEntry.Shell>().single()

    /** The `sleep` processes among this test JVM's descendants. */
    private fun sleeps(): List<ProcessHandle> = ProcessHandle.current().descendants()
        .filter { it.isAlive && it.info().command().orElse("").substringAfterLast('/') == "sleep" }.toList()

    private val earlierSleeps: Set<Long> = sleeps().map { it.pid() }.toSet()

    private fun ownSleeps(): List<Long> = sleeps().map { it.pid() }.filter { it !in earlierSleeps }

    /** Waits for this test's `sleep`s to start, and returns them, for [assertGone]. */
    private suspend fun awaitSleeps(): List<ProcessHandle> {
        while (true) {
            sleeps().filter { it.pid() !in earlierSleeps }.takeIf { it.isNotEmpty() }?.let { return it }
            delay(20)
        }
    }

    @Test
    fun `a shell escape runs, shows its output and sends the two frames`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.create(NewSessionOptions())
        manager.runShell(handle.id, "echo hi")
        // Before any index listing; awaited, since a process that attached first holds the title its mirror then copies.
        withTimeout(2_000) { handle.title.first { it == "! echo hi" } }
        val done = handle.state.first { it.turnsEnded == 1 && !it.busy }
        val shell = done.shell()
        assertEquals(ShellEnd.Exited(0), shell.end)
        assertEquals("hi\n", shell.output)
        assertTrue(shell.sent)
        assertEquals(2, userFrames().size)
        val (input, output) = userFrames()
        assertEquals("<bash-input>echo hi</bash-input>", input.content())
        assertEquals(true, input["client_composed"]!!.jsonPrimitive.boolean)
        assertEquals(false, input["shouldQuery"]!!.jsonPrimitive.boolean)
        assertEquals("<bash-stdout>hi\n</bash-stdout><bash-stderr></bash-stderr>", output.content())
        assertEquals(true, output["client_composed"]!!.jsonPrimitive.boolean)
        assertFalse("shouldQuery" in output)
        assertTrue(done.replyText().contains("The probe ran"))
        assertEquals(1, done.entries.count { it is ChatEntry.TurnEnded })
        // The quiet result left the quiet sends and ended no turn; that it named the first frame's uuid (the fake's rule (g)), rather
        // than ending the oldest quiet send without uuids, is `the quiet result names the command frame (rule (g))`'s check.
        assertTrue(done.quietSends.isEmpty())
        assertEquals("! echo hi", handle.title.value)
    }

    @Test
    fun `the quiet result names the command frame (rule (g))`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.create(NewSessionOptions())
        while (handle.liveSession()?.process == null) delay(20)
        // An older quiet send that no frame carries: a result without uuids would end it (the oldest), one that names the command
        // frame's uuid ends that one and leaves this.
        handle.liveSession()!!.event(SessionEvent.ShellSent("decoy", listOf("decoy"), query = null, atMillis = 0))
        manager.runShell(handle.id, "echo hi")
        val done = handle.state.first { it.turnsEnded == 1 && !it.busy }
        assertEquals(setOf("decoy"), done.quietSends)
        assertEquals(1, done.entries.count { it is ChatEntry.TurnEnded })
        assertEquals(2, userFrames().size)
    }

    @Test
    fun `a command that prints nothing sends empty stdout`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.create(NewSessionOptions())
        manager.runShell(handle.id, "true")
        awaitLog { userFrames().size == 2 }
        assertEquals("<bash-input>true</bash-input>", userFrames()[0].content())
        assertEquals("<bash-stdout></bash-stdout><bash-stderr></bash-stderr>", userFrames()[1].content())
        assertEquals("", handle.state.first { s -> s.entries.any { it is ChatEntry.Shell && it.sent } }.shell().output)
    }

    @Test
    fun `with replies off a shell escape sends one context frame`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-context", respondToShell = { false })
        val handle = manager.create(NewSessionOptions())
        manager.runShell(handle.id, "echo hi")
        val quiet = handle.state.first { s -> s.entries.any { it is ChatEntry.Shell && it.sent } && s.quietSends.isEmpty() }
        val frame = userFrames().single()
        assertEquals(BashEscape.context("echo hi", "hi\n", ""), frame.content())
        assertEquals(false, frame["shouldQuery"]!!.jsonPrimitive.boolean)
        assertTrue(quiet.entries.none { it is ChatEntry.TurnEnded })
        assertFalse(quiet.busy)

        manager.send(handle.id, "Reply with only the text the command printed to stdout")
        val replied = handle.state.first { it.turnsEnded == 1 && !it.busy }
        assertEquals("bash-mode-probe", replied.replyText())
    }

    @Test
    fun `stopShell kills the command and the frames say so`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.create(NewSessionOptions())
        manager.runShell(handle.id, "sleep 30 & wait")
        handle.state.first { it.runningShell != null }
        val sleeps = awaitSleeps()
        val started = System.currentTimeMillis()
        manager.stopShell(handle.id)
        val stopped = withTimeout(2_000) { handle.state.first { s -> s.entries.any { it is ChatEntry.Shell && it.end != null } } }
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertEquals(ShellEnd.Stopped, stopped.shell().end)
        awaitLog { userFrames().size == 2 }
        assertTrue(
            Regex("^<bash-stdout>Stopped after \\d+\\.\\ds</bash-stdout><bash-stderr></bash-stderr>$").matches(userFrames()[1].content()),
            userFrames()[1].content(),
        )
        assertGone(sleeps)
    }

    @Test
    fun `a shell escape on a stopped session queues its frames and starts a process`() = test {
        unix()
        seed(seeded)
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.open(seeded, spawn = false)
        assertNull(handle.liveSession())
        manager.runShell(handle.id, "echo hi")
        handle.state.first { s -> s.turnsEnded > 0 && !s.busy && s.entries.any { it is ChatEntry.Shell && it.sent } }
        assertEquals(1, spawns().size)
        val logged = frames()
        assertEquals(listOf("__spawn__", "control_request", "user", "user"), logged.map { it.type() })
        assertEquals("initialize", logged[1].subtype())
        assertEquals("<bash-input>echo hi</bash-input>", logged[2].content())
    }

    @Test
    fun `a shell escape that cannot start sends nothing`() = test {
        unix()
        val launcher = ShellLauncher { _, _, _ ->
            throw IOException("Cannot run program", IOException("error=2, No such file or directory"))
        }
        val runner = ShellRunner(launcher, environment = { mapOf("SHELL" to "/opt/none/zsh") })
        val manager = manager(fixtures = "bash-mode-respond", shell = runner)
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.runShell(handle.id, "echo hi")
        val ended = handle.state.first { s -> s.entries.any { it is ChatEntry.Shell && it.end != null } }
        assertEquals(ShellEnd.NotStarted("/opt/none/zsh", "No such file or directory"), ended.shell().end)
        assertNull(ended.runningShell)
        delay(300)
        assertEquals(emptyList(), userFrames())
    }

    @Test
    fun `a shell escape whose output cannot be read ends Failed and sends nothing`() = test {
        unix()
        val launcher = ShellLauncher { argv, directory, env ->
            val real = ProcessBuilder(argv).directory(directory.toFile()).apply { environment().putAll(env) }.start()
            object : Process() {
                override fun getOutputStream() = real.outputStream
                override fun getInputStream() = object : InputStream() {
                    override fun read(): Int = throw IOException("boom")
                    override fun available(): Int = throw IOException("boom")
                }
                override fun getErrorStream() = real.errorStream
                override fun waitFor(): Int = real.waitFor()
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = real.waitFor(timeout, unit)
                override fun exitValue(): Int = real.exitValue()
                override fun isAlive(): Boolean = real.isAlive
                override fun pid(): Long = real.pid()
                override fun toHandle(): ProcessHandle = real.toHandle()
                override fun descendants() = real.descendants()
                override fun destroy() = real.destroy()
                override fun destroyForcibly(): Process = real.destroyForcibly()
            }
        }
        val manager = manager(fixtures = "bash-mode-respond", shell = ShellRunner(launcher, environment = { mapOf("SHELL" to "/bin/sh") }))
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.runShell(handle.id, "sleep 1")
        val ended = handle.state.first { s -> s.entries.any { it is ChatEntry.Shell && it.end != null } }
        assertEquals(ShellEnd.Failed("boom"), ended.shell().end)
        assertNull(ended.runningShell)
        delay(300)
        assertEquals(emptyList(), userFrames())
    }

    @Test
    fun `a second shell escape while one runs is ignored`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.create(NewSessionOptions())
        manager.runShell(handle.id, "sleep 30")
        manager.runShell(handle.id, "echo again")
        handle.state.first { it.runningShell != null }
        delay(200)
        assertEquals(listOf("sleep 30"), handle.state.value.entries.filterIsInstance<ChatEntry.Shell>().map { it.command })
        manager.stopShell(handle.id)
        handle.state.first { s -> s.entries.any { it is ChatEntry.Shell && it.end != null } }
        assertEquals(1, handle.state.value.entries.count { it is ChatEntry.Shell })
    }

    @Test
    fun `closing the session kills a running command`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.runShell(handle.id, "sleep 30 & wait")
        val sleeps = awaitSleeps()
        val started = System.currentTimeMillis()
        manager.close(handle.id)
        assertTrue(System.currentTimeMillis() - started < 2_000, "close returns at once")
        assertEquals(ShellEnd.Stopped, handle.state.value.shell().end)
        assertEquals(emptyList(), userFrames())
        assertGone(sleeps)
    }

    @Test
    fun `a project close sends no frame for a running command`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        while (handle.liveSession()?.process == null) delay(20)
        manager.runShell(handle.id, "sleep 30 & wait")
        val sleeps = awaitSleeps()
        scope.coroutineContext.job.cancelAndJoin()
        // The handle's mirror went with the scope: the process's own state has the end.
        assertEquals(ShellEnd.Stopped, handle.liveSession()!!.state.value.shell().end)
        // ShellSent is reduced as the escape is handed to the process, whether or not its bytes reach the CLI: the guard held.
        assertFalse(handle.liveSession()!!.state.value.shell().sent)
        delay(300)
        assertEquals(emptyList(), userFrames())
        assertGone(sleeps)
    }

    @Test
    fun `stopping the session says a held shell escape's output was not sent`() = test {
        unix()
        // The prompt's reply streams for seconds, so the escape is held behind its turn when the session stops.
        val manager = manager(environment = mapOf("TANDEM_FAKE_LINE_DELAY_MS" to "1000"))
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Reply with the single word: pong")
        handle.state.first { it.busy }
        manager.runShell(handle.id, "echo hi")
        withTimeout(5_000) {
            while (handle.shellJob == null) delay(20)
            handle.shellJob!!.join() // its frames were handed to the process, which holds them behind the turn
        }
        assertTrue(handle.state.value.busy, "the prompt's turn is still in progress")
        manager.stop(handle.id)
        val state = handle.state.value
        assertEquals("The output of ! echo hi was not sent: the CLI ended first.", (state.entries.last() as ChatEntry.Notice).text)
        assertFalse(state.shell().sent)
        assertEquals(1, userFrames().size, "only the prompt")
    }

    @Test
    fun `the timed-out command sends the kill note`() = test {
        unix()
        val manager = manager(
            fixtures = "bash-mode-respond",
            shell = ShellRunner(environment = { mapOf("SHELL" to "/bin/sh") }, timeoutMillis = 200),
        )
        val handle = manager.create(NewSessionOptions())
        manager.runShell(handle.id, "sleep 5")
        val ended = handle.state.first { s -> s.entries.any { it is ChatEntry.Shell && it.end != null } }
        assertEquals(ShellEnd.TimedOut, ended.shell().end)
        awaitLog { userFrames().size == 2 }
        assertEquals("<bash-stdout>Killed after 10 minutes</bash-stdout><bash-stderr></bash-stderr>", userFrames()[1].content())
    }

    @Test
    fun `the command and its output are never logged`() = test {
        unix()
        val records = Collections.synchronizedList(ArrayList<String>())
        val previous = Logger.getFactory()
        Logger.setFactory(Logger.Factory { category -> CapturingLogger(category, records) })
        try {
            val manager = manager(fixtures = "bash-mode-respond")
            val handle = manager.create(NewSessionOptions())
            manager.runShell(handle.id, "echo tandem-log-marker")
            val done = handle.state.first { it.turnsEnded == 1 && !it.busy }
            assertEquals("tandem-log-marker\n", done.shell().output)
            manager.close(handle.id)
        } finally {
            Logger.setFactory(previous)
        }
        assertTrue(records.isNotEmpty(), "the capturing factory saw the session's records")
        assertEquals(emptyList(), records.filter { "tandem-log-marker" in it })
    }

    /** Keeps every record's message and throwable text. */
    private class CapturingLogger(private val category: String, private val records: MutableList<String>) : Logger() {
        override fun isDebugEnabled(): Boolean = true
        override fun debug(message: String?, t: Throwable?) = keep(message, t)
        override fun info(message: String?, t: Throwable?) = keep(message, t)
        override fun warn(message: String?, t: Throwable?) = keep(message, t)
        override fun error(message: String?, t: Throwable?, vararg details: String?) = keep(message + details.joinToString(), t)

        private fun keep(message: String?, t: Throwable?) {
            records += "$category: $message ${t?.stackTraceToString().orEmpty()}"
        }
    }

    @Test
    fun `a command stopped at once still ends Stopped`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.create(NewSessionOptions())
        manager.runShell(handle.id, "sleep 30")
        manager.stopShell(handle.id)
        val ended = withTimeout(2_000) {
            handle.state.first { s -> s.entries.any { it is ChatEntry.Shell && it.end != null } && s.runningShell == null }
        }
        assertEquals(ShellEnd.Stopped, ended.shell().end)
        assertNull(ended.runningShell)
        assertFalse(handle.shellJob!!.isActive)
        assertEquals(emptyList(), ownSleeps())
    }

    @Test
    fun `killAllNow ends a running command Stopped and sends nothing`() = test {
        unix()
        val manager = manager(fixtures = "bash-mode-respond")
        val handle = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        manager.runShell(handle.id, "sleep 30 & wait")
        val sleeps = awaitSleeps()
        manager.killAllNow()
        val ended = withTimeout(2_000) { handle.state.first { s -> s.entries.any { it is ChatEntry.Shell && it.end != null } } }
        assertEquals(ShellEnd.Stopped, ended.shell().end)
        assertGone(sleeps)
        delay(500) // a process started now would have logged its spawn by then
        assertEquals(emptyList(), userFrames(), "no frame queued for a process")
        assertEquals(1, spawns().size, "no claude started during the IDE's exit")
    }

    // --- Sending while Claude works (spec 12, 9.7; the fake's rule (m)) ---

    /** Whether the state shows the first Bash call's `tool_use` (`queue-later:14`, `queue-cancel:18`). */
    private fun ChatState.hasToolCall(): Boolean =
        entries.any { e -> e is ChatEntry.Assistant && e.blocks.any { it is AssistantBlock.ToolUse && it.input != null } }

    @Test
    fun `send passes later through to the process`() = test {
        val manager = manager()
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "hi")
        handle.state.first { it.turnsEnded == 1 }
        manager.send(handle.id, "more", later = true)
        awaitLog { userFrames().size == 2 }
        val (first, second) = userFrames()
        assertFalse("priority" in first)
        assertEquals("later", second["priority"]?.jsonPrimitive?.content)
    }

    @Test
    fun `withdraw and takeReturned reach the live process, or the stopped chat`() = test {
        val manager = manager(fixtures = "queue-cancel")
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Run the command")
        handle.state.first { it.hasToolCall() }
        manager.send(handle.id, "Reply with only the word KIWI.", later = true)
        val waiting = handle.state.first { it.pendingSends.isNotEmpty() }.pendingSends.single()
        assertEquals(PendingStage.QUEUED, waiting.stage)

        manager.withdraw(handle.id, waiting.uuid, restore = true)
        awaitLog { frames().any { it.subtype() == "cancel_async_message" } }
        val request = frames().single { it.subtype() == "cancel_async_message" }
        assertEquals(waiting.uuid, request["request"]!!.jsonObject["message_uuid"]!!.jsonPrimitive.content)
        val returned = handle.state.first { it.returnedSends.isNotEmpty() }
        assertEquals(listOf(ReturnedSend(waiting.uuid, "Reply with only the word KIWI.", emptyList())), returned.returnedSends)
        manager.takeReturned(handle.id, listOf(waiting.uuid))
        handle.state.first { it.returnedSends.isEmpty() }

        // The second turn, with a later message waiting when the session is stopped: settled() gives it back.
        handle.state.first { it.turnsEnded == 1 }
        manager.send(handle.id, "Run the second command")
        handle.state.first { s -> s.entries.count { e -> e is ChatEntry.Assistant && e.blocks.any { it is AssistantBlock.ToolUse && it.input != null } } == 2 }
        manager.send(handle.id, "Reply with only the word MANGO.", later = true)
        val mango = handle.state.first { it.pendingSends.isNotEmpty() }.pendingSends.single()
        manager.stop(handle.id)
        val stopped = handle.status.await(SessionStatus.STOPPED).let { handle.state.value }
        assertEquals(listOf(mango.uuid), stopped.returnedSends.map { it.uuid })
        val before = frames().size
        manager.withdraw(handle.id, mango.uuid, restore = true)
        assertEquals(stopped, handle.state.value, "no process: nothing to withdraw")
        manager.takeReturned(handle.id, listOf(mango.uuid))
        assertTrue(handle.state.value.returnedSends.isEmpty())
        assertEquals(before, frames().size)
        assertEquals(1, spawns().size)
    }

    @Test
    fun `no attention while a message waits, attention after the queued turn ends`() = test {
        // The second turn streams slowly enough to look between the two results.
        val manager = manager(fixtures = "queue-later", environment = mapOf("TANDEM_FAKE_LINE_DELAY_MS" to "150"))
        val handle = manager.create(NewSessionOptions())
        manager.send(handle.id, "Run it")
        handle.state.first { it.hasToolCall() }
        manager.send(handle.id, "Also say the word KIWI at the end.", later = true)
        handle.state.first { it.turnsEnded == 1 }
        delay(300)
        assertEquals(1, handle.state.value.turnsEnded, "the queued turn still runs")
        assertFalse(handle.attention.value, "a message waited when the first turn ended")
        handle.state.first { it.turnsEnded == 2 }
        handle.attention.await(true)
    }

    @Test
    fun `a whole fork while a message waits leaves it with the original`() = test {
        seed(seeded)
        // The fake holds the turn for a while once it has read F (rule (m)'s knob), so F still waits when the fork is made.
        val manager = manager(fixtures = "queue-later", environment = mapOf("TANDEM_FAKE_QUEUED_DELAY_MS" to "5000"))
        val original = manager.open(seeded)
        manager.send(seeded, "Run it")
        original.state.first { it.hasToolCall() }
        manager.send(seeded, "Also say the word KIWI at the end.", later = true)
        val waiting = original.state.first { it.pendingSends.isNotEmpty() }.pendingSends.single()
        assertEquals(PendingStage.QUEUED, waiting.stage)

        val fork = assertNotNull(manager.fork(seeded))
        val forked = fork.state.value
        assertTrue(forked.pendingSends.isEmpty())
        assertTrue(forked.returnedSends.isEmpty())
        assertTrue(forked.entries.none { it is ChatEntry.Notice && it.text.startsWith("Not sent") })
        assertEquals(listOf(waiting.uuid), original.state.value.pendingSends.map { it.uuid })
    }

    // spec: a process that ends by itself raises attention unless selected
    @Test
    fun `a process that ends by itself raises attention unless selected`() = test {
        // Each fake exits 1.5 s after its last line: time to look at a finished turn before its process ends by itself.
        val manager = manager(environment = mapOf("TANDEM_FAKE_EXIT_AT_END" to "1500"))
        val a = manager.create(NewSessionOptions())
        val b = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 2 }
        manager.select(a.id)
        manager.send(a.id, "hi")
        manager.send(b.id, "hi")
        b.attention.await(true) // its turn ended while another was selected
        manager.select(b.id)
        manager.select(a.id)
        assertFalse(b.attention.value)

        b.status.await(SessionStatus.STOPPED)
        b.attention.await(true)
        b.selfEnds.await(1)
        a.status.await(SessionStatus.STOPPED)
        a.selfEnds.await(1)
        delay(300) // time for a wrong badge to show
        assertFalse(a.attention.value, "the selected session needs no badge")
        assertEquals(1, b.selfEnds.value)
    }

    // spec: the stops Tandem makes raise none
    @Test
    fun `the stops Tandem makes raise none`() = test {
        val manager = manager(maxLive = 1)
        val stopped = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        stopped.status.await(SessionStatus.IDLE)
        manager.select(null)
        stopped.clearAttention()
        manager.stop(stopped.id)
        stopped.status.await(SessionStatus.STOPPED)
        delay(300) // time for a wrong badge to show
        assertFalse(stopped.attention.value)
        assertEquals(0, stopped.selfEnds.value)

        // The cap's stopIfIdle: a second session started at a cap of one stops the idle first.
        val evicted = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 2 }
        evicted.status.await(SessionStatus.IDLE)
        manager.select(null)
        val second = manager.create(NewSessionOptions())
        evicted.status.await(SessionStatus.STOPPED)
        awaitLog { spawns().size == 3 }
        delay(300)
        assertFalse(evicted.attention.value)
        assertEquals(0, evicted.selfEnds.value)

        manager.close(second.id)
        assertEquals(0, second.selfEnds.value)
    }

    /** Folders made for the folder tests, outside the project (`dir`): removed after each test. */
    private val outside = ArrayList<Path>()

    @AfterEach
    fun removeOutsideFolders() {
        outside.forEach { it.toFile().deleteRecursively() }
    }

    /** A new empty folder outside the project, by its real path (macOS: `/private/var/...`). */
    private fun outsideFolder(): String =
        Files.createTempDirectory("tandem-folder-").toRealPath().also { outside.add(it) }.toString()

    private fun ChatState.warnings(text: String): Int = notices(NoticeLevel.WARNING).count { it == text }

    /** A new session of [this] whose process runs and is idle, before any prompt. */
    private suspend fun SessionManager.idle(): SessionHandle {
        val handle = create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        handle.status.await(SessionStatus.IDLE)
        return handle
    }

    // spec: adding a folder stops an idle process and the next spawn carries it
    @Test
    fun `adding a folder stops an idle process and the next spawn carries it`() = test {
        val extra = outsideFolder()
        val manager = manager()
        val handle = manager.idle()
        manager.addFolder(handle.id, extra)
        assertEquals(listOf(AddedFolder(extra, FolderState.PENDING)), handle.folders.value)
        handle.status.await(SessionStatus.STOPPED)

        manager.send(handle.id, "hi")
        awaitLog { spawns().size == 2 }
        assertEquals(listOf("--session-id", handle.id, "--add-dir", extra), spawns().last().takeLast(4))
        handle.state.first { it.turnsEnded == 1 && !it.busy }
        handle.folders.await(listOf(AddedFolder(extra, FolderState.ACTIVE)))
        assertEquals(0, handle.selfEnds.value, "the folder's stop is Tandem's own (spec 18.1)")
    }

    // spec: adding during a turn stops after the turn
    @Test
    fun `adding during a turn stops after the turn`() = test {
        val extra = outsideFolder()
        val manager = manager(environment = mapOf("TANDEM_FAKE_LINE_DELAY_MS" to "200"))
        val handle = manager.idle()
        manager.send(handle.id, "Reply with the single word: pong")
        handle.status.await(SessionStatus.RUNNING)
        manager.addFolder(handle.id, extra)
        delay(300)
        assertEquals(SessionStatus.RUNNING, handle.status.value, "no stop during the turn")
        handle.state.first { it.turnsEnded == 1 }
        handle.status.await(SessionStatus.STOPPED)
        assertEquals(1, spawns().size)
    }

    // spec: a folder missing from init warns once
    @Test
    fun `a folder missing from init warns once`() = test {
        val extra = outsideFolder()
        // Two turns in one process: parity-context replays two inits.
        val manager = manager(fixtures = "parity-context", environment = mapOf("TANDEM_FAKE_SKIP_ADD_DIR" to extra))
        val handle = manager.idle()
        manager.addFolder(handle.id, extra)
        handle.status.await(SessionStatus.STOPPED)
        manager.send(handle.id, "one")
        handle.state.first { it.turnsEnded == 1 && !it.busy }
        handle.folders.await(listOf(AddedFolder(extra, FolderState.MISSING)))
        manager.send(handle.id, "two")
        handle.state.first { it.mcp.initCount == 2 && !it.busy } // the second turn is a local command's
        delay(500) // the second init's check runs on IO
        assertEquals(listOf(AddedFolder(extra, FolderState.MISSING)), handle.folders.value)
        assertEquals(1, handle.state.value.warnings("The CLI did not add the folder $extra."))
        assertEquals(2, spawns().size)
    }

    // spec: a vanished folder is dropped with a notice
    @Test
    fun `a vanished folder is dropped with a notice`() = test {
        val extra = outsideFolder()
        val manager = manager()
        val handle = manager.idle()
        manager.addFolder(handle.id, extra)
        handle.status.await(SessionStatus.STOPPED)
        Path.of(extra).toFile().deleteRecursively()

        manager.send(handle.id, "hi")
        awaitLog { spawns().size == 2 }
        assertFalse("--add-dir" in spawns().last(), spawns().last().toString())
        assertEquals(emptyList(), handle.folders.value)
        assertEquals(1, handle.state.value.warnings("The folder $extra no longer exists, so it was not added."))
    }

    // spec: a fork copies the folders
    @Test
    fun `a fork copies the folders`() = test {
        seed(seeded)
        val extra = outsideFolder()
        val manager = manager()
        manager.open(seeded, spawn = false)
        manager.addFolder(seeded, extra) // no process: nothing to stop
        val fork = assertNotNull(manager.fork(seeded))
        assertEquals(listOf(AddedFolder(extra, FolderState.PENDING)), fork.folders.value)
        assertEquals(listOf("--resume", seeded, "--fork-session", "--session-id", fork.id, "--add-dir", extra), forkTail(fork.id))
        assertEquals(emptyList(), manager.create(NewSessionOptions()).folders.value, "a new session starts with none")
    }

    // spec: a fork at a reply passes its parent's folders after the fork tail
    @Test
    fun `a fork at a reply passes its parent's folders after the fork tail`() = test {
        seed(seeded, fixture = "fork-at-origin")
        val extra = outsideFolder()
        val manager = manager()
        manager.open(seeded, spawn = false)
        manager.addFolder(seeded, extra)
        val fork = assertNotNull(manager.fork(seeded, "<uuid-14>"))
        assertEquals(listOf(AddedFolder(extra, FolderState.PENDING)), fork.folders.value)
        assertEquals(listOf("--resume-session-at", "<uuid-14>", "--add-dir", extra), forkTail(fork.id).takeLast(4))
    }

    // spec: removing a folder respawns without it
    @Test
    fun `removing a folder respawns without it`() = test {
        val extra = outsideFolder()
        val manager = manager()
        val handle = manager.idle()
        manager.addFolder(handle.id, extra)
        handle.status.await(SessionStatus.STOPPED)
        manager.send(handle.id, "one")
        handle.folders.await(listOf(AddedFolder(extra, FolderState.ACTIVE)))
        handle.status.await(SessionStatus.IDLE)

        manager.removeFolder(handle.id, extra)
        assertEquals(emptyList(), handle.folders.value)
        handle.status.await(SessionStatus.STOPPED)
        manager.send(handle.id, "two")
        awaitLog { spawns().size == 3 }
        assertFalse("--add-dir" in spawns().last(), spawns().last().toString())
        handle.state.first { it.turnsEnded == 2 && !it.busy }
        delay(300)
        assertTrue(handle.state.value.notices(NoticeLevel.WARNING).none { "removed folder" in it })
    }

    // spec: a removed folder the CLI still lists warns
    @Test
    fun `a removed folder the CLI still lists warns`() = test {
        val extra = outsideFolder()
        val kept = "The CLI still lists the removed folder $extra."
        // The third process has two turns: parity-context replays two inits.
        val manager = manager(
            fixtures = "plain-reply,plain-reply,parity-context",
            environment = mapOf("TANDEM_FAKE_KEEP_ADD_DIR" to extra),
        )
        val handle = manager.idle()
        manager.send(handle.id, "one")
        handle.state.first { it.turnsEnded == 1 && !it.busy && it.additionalDirectories == listOf(extra) }
        delay(300)
        assertEquals(0, handle.state.value.warnings(kept), "never added: no notice")

        manager.addFolder(handle.id, extra)
        handle.status.await(SessionStatus.STOPPED)
        manager.send(handle.id, "two")
        handle.state.first { it.turnsEnded == 2 && !it.busy }
        handle.folders.await(listOf(AddedFolder(extra, FolderState.ACTIVE)))
        assertEquals(0, handle.state.value.warnings(kept))

        manager.removeFolder(handle.id, extra)
        handle.status.await(SessionStatus.STOPPED)
        manager.send(handle.id, "three")
        awaitLog { spawns().size == 3 }
        assertFalse("--add-dir" in spawns().last(), spawns().last().toString())
        handle.state.first { it.warnings(kept) == 1 }
        handle.state.first { it.turnsEnded == 3 && !it.busy }
        manager.send(handle.id, "four")
        handle.state.first { it.mcp.initCount == 2 && !it.busy } // the second turn is a local command's
        delay(500) // the second init's check runs on IO
        assertEquals(1, handle.state.value.warnings(kept), "once per folder per spawn")
        assertEquals(3, spawns().size)
    }

    // spec: removing a folder before the stop leaves the process running
    @Test
    fun `removing a folder before the stop leaves the process running`() = test {
        val extra = outsideFolder()
        val manager = manager(environment = mapOf("TANDEM_FAKE_LINE_DELAY_MS" to "200"))
        val handle = manager.idle()
        manager.send(handle.id, "Reply with the single word: pong")
        handle.status.await(SessionStatus.RUNNING)
        manager.addFolder(handle.id, extra)
        manager.removeFolder(handle.id, extra)
        handle.state.first { it.turnsEnded == 1 && !it.busy }
        delay(500)
        assertEquals(SessionStatus.IDLE, handle.status.value, "nothing differs, so nothing stops")
        manager.send(handle.id, "again")
        delay(300)
        assertEquals(1, spawns().size, "the next send goes to the same process")
    }

    // review: a folder added while the session's spawn is under way still reaches the CLI
    @Test
    fun `a folder added while the spawn is under way reaches the CLI`() = test {
        val extra = outsideFolder()
        // At most one live session, and a stop that takes a second: the second session's spawn reads its folders, then waits
        // for room while the first one's process exits.
        val manager = manager(maxLive = 1, environment = mapOf("TANDEM_FAKE_EXIT_DELAY_MS" to "1000"))
        val first = manager.idle()
        val second = manager.create(NewSessionOptions())
        first.status.await(SessionStatus.STOPPED) // let go to make room: the second spawn has read its folders already
        manager.addFolder(second.id, extra)
        assertEquals(1, spawns().size, "the second spawn still waits for room")

        // That spawn goes on without the folder. Its process is stopped at once (it may be gone before it even logs its spawn),
        // or, should the prompt reach it first, after its turn: either way the folder reaches the CLI.
        withTimeout(10_000) {
            manager.send(second.id, "one")
            second.state.first { it.turnsEnded == 1 && !it.busy }
            if ("--add-dir" !in spawns().last()) {
                // The prompt reached the process spawned without the folder: it is stopped after its turn.
                second.status.await(SessionStatus.STOPPED)
                manager.send(second.id, "two")
                second.state.first { it.turnsEnded == 2 && !it.busy }
            }
            assertEquals(listOf("--add-dir", extra), spawns().last().takeLast(2))
            second.folders.await(listOf(AddedFolder(extra, FolderState.ACTIVE)))
        }
    }

    // review: a resume that comes back as another session takes its folders to the new row
    @Test
    fun `a session taken over keeps the folders its process runs with`() = test {
        seed(seeded)
        val extra = outsideFolder()
        val manager = manager(environment = mapOf("TANDEM_FAKE_SESSION_ID" to other))
        val resumed = manager.open(seeded, spawn = false)
        manager.addFolder(seeded, extra)
        manager.send(seeded, "Reply with the single word: pong")

        val taken = manager.sessions.first { it.size == 2 }.last()
        assertEquals(other, taken.id)
        assertEquals(listOf("--add-dir", extra), spawns().single().takeLast(2))
        taken.folders.await(listOf(AddedFolder(extra, FolderState.ACTIVE)))
        taken.status.await(SessionStatus.IDLE)
        delay(300)
        assertEquals(listOf(AddedFolder(extra, FolderState.ACTIVE)), taken.folders.value)
        assertEquals(1, spawns().size, "the row that took the process over does not stop it for its folders")
        assertEquals(listOf(extra), resumed.folders.value.map { it.path }, "the old row keeps its own")
    }
}

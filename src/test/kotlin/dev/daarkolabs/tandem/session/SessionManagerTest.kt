package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.transcript.LoadedTranscript
import dev.daarkolabs.tandem.session.transcript.SessionIndex
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import dev.daarkolabs.tandem.session.transcript.TranscriptFiles
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SessionManager] against the fake CLI (`src/test/resources/fake-claude.py`): every session runs a real child process that
 * replays a recorded fixture, and the fake's log shows how each process was started and every frame the host wrote to it.
 */
class SessionManagerTest {
    @TempDir
    lateinit var dir: Path

    private val fakeClaude: String = System.getProperty("tandem.claudePath") ?: "src/test/resources/fake-claude.py"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fake CLI processes that were already running before this test; another test's leftovers are not this test's to judge. */
    private val earlier: Set<Long> = fakeProcesses().map { it.pid() }.toSet()

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
        maxLive: Int = 4,
        /** More variables for every process, such as `TANDEM_FAKE_SESSION_ID`. */
        environment: Map<String, String> = emptyMap(),
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
        )
    }

    private fun test(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { withTimeout(30_000, block) }

    /** Writes the `history-reply` transcript as session [id]'s, through [edit]. */
    private fun seed(id: String, edit: (String) -> String = { it }) {
        folder.resolve("$id.jsonl").writeText(edit(Fixtures.transcript("history-reply").readText()))
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

    private fun fakeProcesses(): List<ProcessHandle> =
        ProcessHandle.current().descendants().filter { handle ->
            handle.isAlive && handle.info().arguments().orElse(emptyArray()).any { it.endsWith("fake-claude.py") }
        }.toList()

    private fun ownFakeProcesses(): List<ProcessHandle> = fakeProcesses().filter { it.pid() !in earlier }

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
        delay(300) // time for a wrong start to show in the log
        assertEquals(emptyList(), spawns(), "an open session starts with its next prompt, not when it is opened again")

        manager.send(seeded, "Reply with the single word: pong")
        manager.send(seeded, "And once more")
        awaitLog { prompts().size == 2 }
        assertEquals(listOf("Reply with the single word: pong", "And once more"), prompts())
        // By the time the first prompt is answered, a second process would have been started.
        handle.state.first { s -> !s.busy && s.entries.filterIsInstance<ChatEntry.Assistant>().size == 2 }
        assertEquals(listOf<String?>(seeded), spawns().map { it.valueOf("--resume") })
        val history = "Reply with the single word: pong" // the seeded transcript's prompt
        assertEquals(listOf(history, "Reply with the single word: pong", "And once more"), handle.state.value.prompts())
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
        val answered = handle.state.first { s -> !s.busy && s.entries.last() is ChatEntry.Assistant }
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
        // rename elsewhere, say in a terminal, shows. (Had the index not listed "Beta" first, "Beta" would stay.)
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
}

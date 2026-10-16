package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.transcript.SessionIndex
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        clock: () -> Long = System::currentTimeMillis,
    ): SessionManager {
        val fake = buildMap {
            put("TANDEM_FAKE_LOG", log.toString())
            put("TANDEM_FAKE_FIXTURES", fixtures)
            delayMs?.let { put("TANDEM_FAKE_DELAY_MS", it.toString()) }
        }
        val launcher = DefaultProcessLauncher { System.getenv() + fake }
        val index = SessionIndex({ listOf(folder) }, "/project")
        return SessionManager(scope, index, launcher, { binary }, dir, maxLive = { 4 }, clock = clock)
    }

    private fun test(block: suspend () -> Unit): Unit = runBlocking { withTimeout(30_000) { block() } }

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
    fun `two quick prompts to a stopped session start one process`() = test {
        seed(seeded)
        val manager = manager()
        val handle = manager.open(seeded, spawn = false)
        assertEquals(SessionStatus.STOPPED, handle.status.value)

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
        val handle = manager.open(seeded, spawn = false)
        assertEquals(SessionStatus.STOPPED, handle.status.value)

        manager.open(seeded)
        handle.status.await(SessionStatus.IDLE) // the process is up and waits for a prompt
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
        val handle = manager.open(seeded, spawn = false)
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, handle.state.value.permissionMode)

        manager.open(seeded)
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
        val manager = manager()
        val created = manager.create(NewSessionOptions())
        awaitLog { spawns().size == 1 }
        val opened = manager.open(seeded, spawn = false)
        manager.select(created.id)

        manager.close(created.id)
        assertEquals(emptyList(), ownFakeProcesses(), "close returns once the process is gone")
        assertEquals(listOf(opened), manager.sessions.value)
        assertEquals(seeded, manager.selected.value, "the closed session was selected, so the next one is")

        manager.close(seeded)
        assertEquals(emptyList(), manager.sessions.value)
        assertNull(manager.selected.value)
    }

    @Test
    fun `a missing binary shows the install notice`() = test {
        val manager = manager(binary = null)
        val handle = manager.create(NewSessionOptions())
        val state = handle.state.first { it.entries.isNotEmpty() }
        val notice = state.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertTrue("not found" in notice.text, notice.text)
        assertEquals(SessionStatus.STOPPED, handle.status.value)

        // The next prompt looks for the binary again, and says so again; the prompt is not shown as sent.
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
        val other = "55555555-5555-4555-8555-555555555555"
        seed(other)
        // From the folder's change, well before the 30 s poll that stands in where the folders cannot be watched.
        val listed = withTimeout(10_000) { manager.summaries.first { summaries -> summaries.any { it.id == other } } }
        assertEquals(setOf(seeded, other), listed.map { it.id }.toSet())
    }
}

package dev.daarkolabs.tandem.ide

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.session.transcript.TranscriptFolder
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [TandemSessions] inside a real (headless) project, against `src/test/resources/fake-claude.py`, which the test task passes as
 * `tandem.claudePath`. Transcripts come from a config directory of the test's own (the `tandem.configDir` test hook), never the
 * user's.
 */
@TestApplication
class TandemSessionsTest {
    companion object {
        private val tempDir = tempPathFixture()

        /** A session with a recorded transcript (`history-reply`, whose own title is "Pong"). */
        private const val SEEDED = "66666666-6666-4666-8666-666666666666"

        /** A session the workspace remembers, whose transcript is gone. */
        private const val GONE = "77777777-7777-4777-8777-777777777777"

        /** Another session with a recorded transcript. */
        private const val OTHER = "88888888-8888-4888-8888-888888888888"

        /** And another. */
        private const val THIRD = "99999999-9999-4999-8999-999999999999"

        /** A session with a transcript that fails to open in a way nothing expects. */
        private const val BROKEN = "55555555-5555-4555-8555-555555555555"
    }

    /** Fake CLI processes that were already running before this test; another test's leftovers are not this test's to judge. */
    private val earlier: Set<Long> = fakeCliProcesses().map { it.pid() }.toSet()

    private lateinit var configDir: Path

    @BeforeEach
    fun useOwnConfigDir() {
        configDir = Files.createTempDirectory(tempDir.get(), "config")
        System.setProperty("tandem.configDir", configDir.toString())
    }

    @AfterEach
    fun forgetConfigDir() {
        System.clearProperty("tandem.configDir")
    }

    /** Live processes running the fake CLI, children of this test JVM. */
    private fun fakeCliProcesses(): List<ProcessHandle> =
        ProcessHandle.current().descendants().filter { handle ->
            handle.isAlive && handle.info().arguments().orElse(emptyArray()).any { it.endsWith("fake-claude.py") }
        }.toList()

    private fun ownFakeProcesses(): List<ProcessHandle> = fakeCliProcesses().filter { it.pid() !in earlier }

    /** Closing the project must end every process; the timeout of the enclosing test bounds the wait. */
    private suspend fun awaitNoOwnFakeCli() {
        while (ownFakeProcesses().isNotEmpty()) delay(20)
    }

    /** Writes the `history-reply` transcript as session [id]'s, in the CLI's folder for the project in [dir]. */
    private fun seed(dir: Path, id: String) {
        // The CLI keys the folder on its physical working directory.
        val folder = configDir.resolve("projects").resolve(TranscriptFolder.key(dir.toRealPath().toString())).createDirectories()
        Fixtures.transcript("history-reply").copyTo(folder.resolve("$id.jsonl"))
    }

    private fun ChatState.prompts(): List<String> = entries.filterIsInstance<ChatEntry.User>().map { it.text }

    private fun ChatState.replyText(): String =
        entries.filterIsInstance<ChatEntry.Assistant>().last().blocks.filterIsInstance<AssistantBlock.Text>().joinToString("") { it.text }

    @Test
    fun `a created session streams the reply into its state`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("reply").createDirectories()
        val (state, remembered) = openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            val manager = sessions.manager
            val handle = manager.create(NewSessionOptions())
            manager.select(handle.id)
            manager.send(handle.id, "Reply with the single word: pong")
            val state = handle.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant && !it.streaming } }
            // The workspace follows the open sessions and the selection, so that the next IDE run opens them again.
            val workspace = TandemWorkspaceState.getInstance(project).state
            while (workspace.openSessionIds != listOf(handle.id) || workspace.selectedSessionId != handle.id) delay(20)
            state to handle.id
        }
        assertEquals(listOf("Reply with the single word: pong"), state.prompts())
        assertEquals("pong", state.replyText())
        assertEquals("claude-sonnet-5-5", state.activeModel)
        assertEquals(remembered, state.sessionId)
        awaitNoOwnFakeCli()
    }

    @Test
    fun `closing the project stops every live session`() = timeoutRunBlocking(60.seconds) {
        val dir = tempDir.get().resolve("close").createDirectories()
        val started = openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            repeat(2) { sessions.manager.create(NewSessionOptions()) }
            var mine = emptyList<ProcessHandle>()
            while (mine.size < 2) {
                mine = ownFakeProcesses()
                delay(20)
            }
            mine
        }
        // useProjectAsync has closed the project, which cancels the service's scope: that must end both processes.
        while (started.any { it.isAlive }) delay(20)
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `open sessions are restored from workspace state without spawning`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("restore").createDirectories()
        seed(dir, SEEDED)
        val log = configDir.resolve("fake-cli.log")
        val (restored, processes) = openProjectAsync(dir).useProjectAsync { project ->
            TandemWorkspaceState.getInstance(project).state.apply {
                openSessionIds = mutableListOf(SEEDED)
                selectedSessionId = SEEDED
            }
            val sessions = TandemSessions.get(project)
            sessions.extraEnvironment = mapOf("TANDEM_FAKE_LOG" to log.toString())
            sessions.ensureStarted()
            sessions.manager.selected.first { it == SEEDED } // the last step of restoring
            delay(500) // time for a process that must not start to show up
            sessions.manager.sessions.value.single() to ownFakeProcesses()
        }
        assertEquals(SEEDED, restored.id)
        assertEquals(SessionStatus.STOPPED, restored.status.value)
        assertEquals(listOf("Reply with the single word: pong"), restored.state.value.prompts())
        assertEquals("pong", restored.state.value.replyText())
        assertEquals(emptyList(), processes.map { it.pid() })
        assertFalse(log.exists() && "__spawn__" in log.readText(), "no process was started")
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `a remembered session whose transcript is gone is left out`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("gone").createDirectories()
        seed(dir, SEEDED)
        val (open, selected) = openProjectAsync(dir).useProjectAsync { project ->
            val workspace = TandemWorkspaceState.getInstance(project).state
            workspace.openSessionIds = mutableListOf(GONE, SEEDED)
            workspace.selectedSessionId = GONE
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            // The selected session is gone, so the first one restored is selected in its place.
            sessions.manager.selected.first { it == SEEDED }
            // And the workspace forgets the one that is gone.
            while (workspace.openSessionIds != listOf(SEEDED)) delay(20)
            sessions.manager.sessions.value.map { it.id } to workspace.selectedSessionId
        }
        assertEquals(listOf(SEEDED), open)
        assertEquals(SEEDED, selected)
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `the selected session is restored first, then the others in their saved order`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("order").createDirectories()
        seed(dir, SEEDED)
        seed(dir, OTHER)
        seed(dir, THIRD)
        val (open, selected) = openProjectAsync(dir).useProjectAsync { project ->
            TandemWorkspaceState.getInstance(project).state.apply {
                openSessionIds = mutableListOf(THIRD, SEEDED, OTHER) // not in the order of their ids
                selectedSessionId = SEEDED // the second one: it shows before the others' transcripts are read
            }
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            sessions.manager.selected.first { it == SEEDED }
            while (sessions.manager.sessions.value.size < 3) delay(20) // the others follow
            sessions.manager.sessions.value.map { it.id } to sessions.manager.selected.value
        }
        assertEquals(listOf(SEEDED, THIRD, OTHER), open)
        assertEquals(SEEDED, selected)
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `a session that fails to open is left out and the others are restored`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("broken").createDirectories()
        seed(dir, BROKEN)
        seed(dir, SEEDED)
        seed(dir, OTHER)
        val (open, remembered) = openProjectAsync(dir).useProjectAsync { project ->
            val workspace = TandemWorkspaceState.getInstance(project).state
            workspace.openSessionIds = mutableListOf(BROKEN, SEEDED, OTHER)
            workspace.selectedSessionId = OTHER
            val sessions = TandemSessions.get(project)
            // Not a transcript that cannot be read, which opens with a warning in its chat (SessionManagerTest), but a failure
            // nothing expects, such as a bug in the reader.
            sessions.readTranscript = { path -> if (path.name.startsWith(BROKEN)) error("a bug") else TranscriptReader.read(path) }
            sessions.ensureStarted()
            sessions.manager.selected.first { it == OTHER }
            // The workspace follows the open sessions from then on, so it forgets the one left out.
            while (workspace.openSessionIds != listOf(OTHER, SEEDED)) delay(20)
            sessions.manager.sessions.value.map { it.id } to workspace.selectedSessionId
        }
        assertEquals(listOf(OTHER, SEEDED), open)
        assertEquals(OTHER, remembered)
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `the shutdown hook stays until every session's process has ended`() = timeoutRunBlocking(60.seconds) {
        val dir = tempDir.get().resolve("hook").createDirectories()
        val log = configDir.resolve("fake-cli.log")
        val (sessions, process) = openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            // The fake sleeps this long before its permission request and reads nothing meanwhile, so once its input is closed,
            // ending it takes about 2 s: ClaudeSession waits that long before it terminates the process.
            sessions.extraEnvironment = mapOf(
                "TANDEM_FAKE_LOG" to log.toString(),
                "TANDEM_FAKE_FIXTURES" to "permission",
                "TANDEM_FAKE_DELAY_MS" to "30000",
            )
            sessions.ensureStarted()
            assertTrue(sessions.shutdownHookRegistered)
            val handle = sessions.manager.create(NewSessionOptions())
            sessions.manager.send(handle.id, "Create the fixture file")
            while (!(log.exists() && log.readLines().any { "\"type\":\"user\"" in it })) delay(20) // it has the prompt, and sleeps
            sessions to ownFakeProcesses().single()
        }
        // The project is closed and the service's scope cancelled, but the platform does not wait for the process to end.
        assertTrue(process.isAlive, "the process is still being ended")
        assertTrue(sessions.shutdownHookRegistered, "the hook must stay while a process is still being ended")
        while (process.isAlive) delay(20)
        // Once the last process has ended, the scope completes, and the hook goes.
        while (sessions.shutdownHookRegistered) delay(20)
    }
}

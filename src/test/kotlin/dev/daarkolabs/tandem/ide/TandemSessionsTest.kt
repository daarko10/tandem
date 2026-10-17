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
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}

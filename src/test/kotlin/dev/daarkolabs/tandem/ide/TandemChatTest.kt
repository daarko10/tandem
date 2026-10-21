package dev.daarkolabs.tandem.ide

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [TandemChat] inside a real (headless) project, against `src/test/resources/fake-claude.py`,
 * which the test task passes as `tandem.claudePath`.
 */
@TestApplication
class TandemChatTest {
    companion object {
        private val tempDir = tempPathFixture()
    }

    /** Live processes running the fake CLI, children of this test JVM. */
    private fun fakeCliProcesses(): List<ProcessHandle> =
        ProcessHandle.current().descendants().filter { handle ->
            handle.isAlive && handle.info().arguments().orElse(emptyArray()).any { it.endsWith("fake-claude.py") }
        }.toList()

    @Test
    fun `chat starts the CLI from the project service and streams the reply into state`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("reply-test").createDirectories()
        val state = openProjectAsync(dir).useProjectAsync { project ->
            val chat = TandemChat.get(project)
            chat.start()
            chat.send("Reply with the single word: pong")
            // Sent right after start(), before the session exists: it must be queued, not dropped.
            chat.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant && !it.streaming } }
        }
        assertEquals(listOf("Reply with the single word: pong"), state.entries.filterIsInstance<ChatEntry.User>().map { it.text })
        val reply = state.entries.filterIsInstance<ChatEntry.Assistant>().single().blocks.single() as AssistantBlock.Text
        assertEquals("pong", reply.text)
        assertEquals("claude-sonnet-5-5", state.activeModel)
        awaitNoFakeCli()
    }

    @Test
    fun `closing the project stops the CLI process`() = timeoutRunBlocking(60.seconds) {
        val before = fakeCliProcesses().map { it.pid() }.toSet()
        val dir = tempDir.get().resolve("close-test").createDirectories()

        val started = openProjectAsync(dir).useProjectAsync { project ->
            val chat = TandemChat.get(project)
            chat.start()
            // Wait until the fake CLI is running; remember it before the project closes.
            var mine = emptyList<ProcessHandle>()
            while (mine.isEmpty()) {
                mine = fakeCliProcesses().filter { it.pid() !in before }
                delay(20)
            }
            mine
        }

        // useProjectAsync has closed the project: its service scope is cancelled, which must end the CLI.
        while (started.any { it.isAlive }) delay(20)
        assertTrue(started.none { it.isAlive })
    }

    /** Closing the project must end the CLI; the timeout of the enclosing test bounds the wait. */
    private suspend fun awaitNoFakeCli() {
        while (fakeCliProcesses().isNotEmpty()) delay(20)
    }
}

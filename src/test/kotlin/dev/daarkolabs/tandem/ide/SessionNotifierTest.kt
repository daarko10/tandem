package dev.daarkolabs.tandem.ide

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * [SessionNotifier], started by [TandemSessions.ensureStarted], against the fake CLI: each process replays the next fixture of
 * `TANDEM_FAKE_FIXTURES`, in the order the processes start. Headless, the Tandem tool window is never visible.
 */
@TestApplication
class SessionNotifierTest {
    companion object {
        private val tempDir = tempPathFixture()
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

    private fun fakeCliProcesses(): List<ProcessHandle> =
        ProcessHandle.current().descendants().filter { handle ->
            handle.isAlive && handle.info().arguments().orElse(emptyArray()).any { it.endsWith("fake-claude.py") }
        }.toList()

    /** What a test sees: the sessions, the notifications posted to the project so far, and how many processes have started. */
    private class Run(val manager: SessionManager, val posted: List<Notification>, private val log: Path) {
        /** Waits until the [count]th process has started, so that the next one replays the next fixture. */
        suspend fun awaitSpawns(count: Int) {
            while ((if (log.exists()) log.readLines().count { it.startsWith("{\"type\":\"__spawn__\"") } else 0) < count) delay(20)
        }

        /** Waits for a notification, then a little longer, so that one that should not come has time to. */
        suspend fun notifications(): List<Notification> {
            while (posted.isEmpty()) delay(20)
            delay(300)
            return posted.toList()
        }
    }

    /**
     * Opens a project whose processes replay [fixtures], starts its sessions and runs [block] while the project's notifications are
     * collected; returns once the project is closed and every process it started has ended.
     */
    private suspend fun <T> withSessions(name: String, fixtures: String, block: suspend Run.() -> T): T {
        val dir = tempDir.get().resolve(name).createDirectories()
        val log = configDir.resolve("fake-cli.log")
        val result = openProjectAsync(dir).useProjectAsync { project ->
            val posted = Collections.synchronizedList(ArrayList<Notification>())
            val listening = Disposer.newDisposable()
            try {
                project.messageBus.connect(listening).subscribe(
                    Notifications.TOPIC,
                    object : Notifications {
                        override fun notify(notification: Notification) {
                            posted.add(notification)
                        }
                    },
                )
                val sessions = TandemSessions.get(project)
                sessions.extraEnvironment = mapOf("TANDEM_FAKE_LOG" to log.toString(), "TANDEM_FAKE_FIXTURES" to fixtures)
                sessions.ensureStarted()
                sessions.ensureStarted() // once is enough: a second call starts nothing, so nothing is notified twice
                Run(sessions.manager, posted, log).block()
            } finally {
                Disposer.dispose(listening)
            }
        }
        while (fakeCliProcesses().any { it.pid() !in earlier }) delay(20)
        return result
    }

    @Test
    fun `a background permission request posts a warning notification`() = timeoutRunBlocking(60.seconds) {
        val (notifications, title) = withSessions("background-permission", fixtures = "plain-reply,permission") {
            val a = manager.create(NewSessionOptions())
            manager.select(a.id)
            awaitSpawns(1) // A replays plain-reply
            val b = manager.create(NewSessionOptions())
            awaitSpawns(2) // B replays permission
            manager.send(b.id, "Create the <fixture> file & check it")
            b.status.first { it == SessionStatus.WAITING }
            notifications() to b.title.value
        }
        val warning = notifications.single() // none for A, which is selected and has nothing to say
        assertEquals(NotificationType.WARNING, warning.type)
        assertEquals("Create the <fixture> file & check it", title)
        // Notifications render HTML: the title is escaped, so that it shows as the plain text it is.
        assertEquals("Create the &lt;fixture&gt; file &amp; check it needs your permission", warning.title)
        assertEquals(listOf("Open"), warning.actions.map { it.templateText })
    }

    @Test
    fun `a background turn that ends posts an information notification`() = timeoutRunBlocking(60.seconds) {
        val (notifications, title) = withSessions("background-finished", fixtures = "plain-reply,interrupt") {
            val a = manager.create(NewSessionOptions())
            manager.select(a.id)
            awaitSpawns(1)
            val b = manager.create(NewSessionOptions())
            awaitSpawns(2) // B replays interrupt: it streams, then waits for the interrupt
            manager.send(b.id, "Count to a hundred")
            b.status.first { it == SessionStatus.RUNNING }
            manager.interrupt(b.id)
            b.status.first { it == SessionStatus.IDLE }
            notifications() to b.title.value
        }
        val finished = notifications.single()
        assertEquals(NotificationType.INFORMATION, finished.type)
        assertEquals("$title finished", finished.title)
    }

    @Test
    fun `the selected session notifies while the tool window is hidden`() = timeoutRunBlocking(60.seconds) {
        val (notifications, title) = withSessions("selected-hidden", fixtures = "permission") {
            val b = manager.create(NewSessionOptions())
            manager.select(b.id)
            awaitSpawns(1)
            manager.send(b.id, "Create the fixture file")
            b.status.first { it == SessionStatus.WAITING }
            notifications() to b.title.value
        }
        val warning = notifications.single()
        assertEquals(NotificationType.WARNING, warning.type)
        assertEquals("$title needs your permission", warning.title)
    }
}

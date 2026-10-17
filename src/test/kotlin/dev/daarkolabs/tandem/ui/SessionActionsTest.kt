package dev.daarkolabs.tandem.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import dev.daarkolabs.tandem.TandemProjectScope
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.session.transcript.TranscriptFolder
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/** [SessionActions] against a real (headless) project's sessions and the fake CLI. */
@TestApplication
class SessionActionsTest {
    companion object {
        private val tempDir = tempPathFixture()

        /** A session with a recorded transcript. */
        private const val SEEDED = "66666666-6666-4666-8666-666666666666"
    }

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

    @Test
    fun `a session still being opened does not take the selection from one the user picked after it`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("requested").createDirectories()
        seed(dir)
        openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            val reading = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            sessions.readTranscript = { path ->
                reading.complete(Unit)
                gate.await()
                TranscriptReader.read(path)
            }
            sessions.ensureStarted()
            val manager = sessions.manager
            val actions = SessionActions(project, manager, TandemProjectScope.get(project).scope)

            actions.show(SEEDED)
            reading.await()
            val later = actions.create(NewSessionOptions())
            gate.complete(Unit)
            val seeded = manager.sessions.first { open -> open.any { it.id == SEEDED } }.single { it.id == SEEDED }
            seeded.status.first { it == SessionStatus.IDLE } // its process is up: the open is over
            delay(500) // time for a selection that must not happen
            assertEquals(later, manager.selected.value)

            // Shown again once it is open, it is selected at once.
            actions.show(SEEDED)
            assertEquals(SEEDED, manager.selected.value)
        }
    }

    @Test
    fun `a rename or delete that fails to write is shown as an error notification, in plain text`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("failures").createDirectories()
        seed(dir)
        val shown = openProjectAsync(dir).useProjectAsync { project ->
            val notifications = ArrayList<Notification>()
            project.messageBus.connect(project).subscribe(
                Notifications.TOPIC,
                object : Notifications {
                    override fun notify(notification: Notification) {
                        synchronized(notifications) { notifications += notification }
                    }
                },
            )
            val sessions = TandemSessions.get(project)
            sessions.appendTitle = { _, _, _, _ -> throw IOException("disk <full>") }
            sessions.deleteTranscript = { throw AccessDeniedException("no <way>") }
            sessions.ensureStarted()
            val manager = sessions.manager
            manager.refreshIndex()
            val actions = SessionActions(project, manager, TandemProjectScope.get(project).scope)

            actions.rename(SEEDED, "<b>New</b>")
            assertFalse(actions.delete(SEEDED, "Pong"))
            while (synchronized(notifications) { notifications.size } < 2) delay(20)
            synchronized(notifications) { notifications.map { Triple(it.type, it.title, it.content) }.sortedBy { it.second } }
        }
        assertEquals(
            listOf(
                Triple(NotificationType.ERROR, "Could not delete “Pong”", "AccessDeniedException: no &lt;way&gt;"),
                Triple(NotificationType.ERROR, "Could not rename the session to “&lt;b&gt;New&lt;/b&gt;”", "IOException: disk &lt;full&gt;"),
            ),
            shown,
        )
    }

    /** Writes the `history-reply` transcript as session [SEEDED]'s, in the CLI's folder for the project in [dir]. */
    private fun seed(dir: Path) {
        val folder = configDir.resolve("projects").resolve(TranscriptFolder.key(dir.toRealPath().toString())).createDirectories()
        Fixtures.transcript("history-reply").copyTo(folder.resolve("$SEEDED.jsonl"))
    }
}

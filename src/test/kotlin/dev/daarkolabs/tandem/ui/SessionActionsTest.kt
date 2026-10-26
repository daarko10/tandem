package dev.daarkolabs.tandem.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import dev.daarkolabs.tandem.TandemProjectScope
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ForkResult
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.OwnFakeCliProcesses
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.session.transcript.TranscriptFolder
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [SessionActions] against a real (headless) project's sessions and the fake CLI. */
@TestApplication
class SessionActionsTest {
    companion object {
        private val tempDir = tempPathFixture()

        /** A session with a recorded transcript. */
        private const val SEEDED = "66666666-6666-4666-8666-666666666666"
    }

    private val ownProcesses = OwnFakeCliProcesses()

    private lateinit var configDir: Path

    /** Closing the project must end every process the test started; the timeout of the enclosing test bounds the wait. */
    private suspend fun awaitNoOwnFakeCli() {
        while (ownProcesses.list().isNotEmpty()) delay(20)
    }

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

            val opening = assertNotNull(actions.show(SEEDED), "a session that is not open is opened first")
            reading.await()
            val later = actions.create(NewSessionOptions())
            gate.complete(Unit)
            opening.join() // the open, and the selection it may make, are over
            assertTrue(manager.sessions.value.any { it.id == SEEDED }, "opened")
            assertEquals(later, manager.selected.value)

            // Shown again once it is open, it is selected at once.
            assertNull(actions.show(SEEDED))
            assertEquals(SEEDED, manager.selected.value)
        }
        awaitNoOwnFakeCli()
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
        awaitNoOwnFakeCli()
        assertEquals(
            listOf(
                Triple(NotificationType.ERROR, "Could not delete “Pong”", "AccessDeniedException: no &lt;way&gt;"),
                Triple(NotificationType.ERROR, "Could not rename the session to “&lt;b&gt;New&lt;/b&gt;”", "IOException: disk &lt;full&gt;"),
            ),
            shown,
        )
    }

    @Test
    fun `Copy resume command puts the command that resumes the session in a terminal on the clipboard`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("copy").createDirectories()
        val copied = openProjectAsync(dir).useProjectAsync { project ->
            val actions = SessionActions(project, TandemSessions.get(project).manager, TandemProjectScope.get(project).scope)
            withContext(Dispatchers.EDT) {
                actions.copyResumeCommand(SEEDED)
                CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
            }
        }
        assertEquals("claude --resume $SEEDED", copied)
    }

    @Test
    fun `fork selects the fork, and a session without a transcript gets the notice in its chat`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("fork").createDirectories()
        seed(dir)
        openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            val manager = sessions.manager
            val actions = SessionActions(project, manager, TandemProjectScope.get(project).scope)
            assertNotNull(actions.show(SEEDED)).join()

            actions.fork(SEEDED)
            while (manager.sessions.value.size < 2) delay(20)
            val fork = manager.sessions.value.single { it.forkOf == SEEDED }
            // The fork is listed a moment before it is selected: two flows, one lock.
            while (manager.selected.value != fork.id) delay(20)
            assertEquals(fork.id, manager.selected.value)

            // A session with no prompt yet has no transcript: nothing to fork, and its chat says so.
            val fresh = actions.create(NewSessionOptions())
            actions.fork(fresh)
            val handle = manager.sessions.value.single { it.id == fresh }
            val notice = ChatEntry.Notice("", NoticeLevel.WARNING, "This session has no transcript to fork yet")
            fun shown(): Boolean = (handle.state.value.entries.lastOrNull() as? ChatEntry.Notice)?.copy(id = "") == notice
            while (!shown()) delay(20)
            assertEquals(3, manager.sessions.value.size)
        }
        awaitNoOwnFakeCli()
    }

    @Test
    fun `fork at a reply calls the manager with the point, and each refusal gets its own notice`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("fork-at").createDirectories()
        seed(dir)
        openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            val manager = sessions.manager
            val actions = SessionActions(project, manager, TandemProjectScope.get(project).scope)
            assertNotNull(actions.show(SEEDED)).join()
            val original = manager.sessions.value.single { it.id == SEEDED }
            fun notices(): List<String> = original.state.value.entries.filterIsInstance<ChatEntry.Notice>().map { it.text }

            // The reply's point reaches the manager: a fork at it starts, with no notice.
            actions.fork(SEEDED, "<uuid-10>")
            while (manager.sessions.value.size < 2) delay(20)
            val fork = manager.sessions.value.single { it.forkOf == SEEDED }
            assertEquals("<uuid-10>", fork.forkAt)
            while (manager.selected.value != fork.id) delay(20)

            // A point not in the history: nothing forks, and the original's chat says why.
            actions.fork(SEEDED, "no-such-uuid")
            while ("This reply cannot be forked: the CLI sent no id for it" !in notices()) delay(20)

            // A session with no prompt yet: nothing to fork at any point.
            val fresh = actions.create(NewSessionOptions())
            actions.fork(fresh, "<uuid-10>")
            val handle = manager.sessions.value.single { it.id == fresh }
            fun freshNotices(): List<String> = handle.state.value.entries.filterIsInstance<ChatEntry.Notice>().map { it.text }
            while ("This session has no transcript to fork yet" !in freshNotices()) delay(20)
            assertEquals(3, manager.sessions.value.size)
            assertEquals(listOf("This reply cannot be forked: the CLI sent no id for it"), notices())
        }
        awaitNoOwnFakeCli()
    }

    @Test
    fun `each fork result has its own words`() {
        assertEquals("This session has no transcript to fork yet", forkRefusal(ForkResult.Refused.NO_TRANSCRIPT))
        assertEquals("This reply cannot be forked: the CLI sent no id for it", forkRefusal(ForkResult.Refused.NOT_IN_HISTORY))
        assertEquals("Wait for the reply to finish", forkRefusal(ForkResult.Refused.BUSY))
    }

    // spec: closing the selected session selects the next tab, else the previous, else none, before the close
    @Test
    fun `closing the selected session selects the next tab, else the previous, else none, before the close`() = timeoutRunBlocking(60.seconds) {
        val dir = tempDir.get().resolve("close-tabs").createDirectories()
        openProjectAsync(dir).useProjectAsync { project ->
            val manager = TandemSessions.get(project).also { it.ensureStarted() }.manager
            var tabs: List<String> = emptyList()
            val actions = SessionActions(project, manager, TandemProjectScope.get(project).scope, tabs = { tabs })
            val (a, b, c) = List(3) { actions.create(NewSessionOptions()) }
            val seen = recordSelections(manager)
            try {
                manager.select(b)
                tabs = listOf(a, b, c)
                closeOn(manager, b) { actions.close(b) }
                assertTrue(c to true in seen, "c was selected while b was still open: $seen")
                tabs = listOf(a, c)
                closeOn(manager, c) { actions.close(c) }
                assertTrue(a to true in seen, "a was selected while c was still open: $seen")
                tabs = listOf(a)
                closeOn(manager, a) { actions.close(a) }
                assertNull(manager.selected.value)
            } finally {
                seen.stop()
            }
        }
        awaitNoOwnFakeCli()
    }

    // spec: deleting the selected session selects the next tab, else the previous, else none, before the delete
    @Test
    fun `deleting the selected session selects the next tab, else the previous, else none, before the delete`() = timeoutRunBlocking(60.seconds) {
        val dir = tempDir.get().resolve("delete-tabs").createDirectories()
        val (a, b, c) = listOf(
            "77777777-7777-4777-8777-777777777771",
            "77777777-7777-4777-8777-777777777772",
            "77777777-7777-4777-8777-777777777773",
        )
        listOf(a, b, c).forEach { seed(dir, it) }
        openProjectAsync(dir).useProjectAsync { project ->
            val manager = TandemSessions.get(project).also { it.ensureStarted() }.manager
            var tabs: List<String> = emptyList()
            val actions = SessionActions(project, manager, TandemProjectScope.get(project).scope, tabs = { tabs })
            listOf(a, b, c).forEach { manager.open(it) }
            val seen = recordSelections(manager)
            try {
                manager.select(b)
                tabs = listOf(a, b, c)
                assertTrue(withContext(Dispatchers.EDT) { actions.delete(b, "Pong") })
                assertTrue(c to true in seen, "c was selected while b was still open: $seen")
                tabs = listOf(a, c)
                assertTrue(withContext(Dispatchers.EDT) { actions.delete(c, "Pong") })
                assertTrue(a to true in seen, "a was selected while c was still open: $seen")
                tabs = listOf(a)
                assertTrue(withContext(Dispatchers.EDT) { actions.delete(a, "Pong") })
                assertNull(manager.selected.value)
                assertTrue(manager.sessions.value.isEmpty())
            } finally {
                seen.stop()
            }
        }
        awaitNoOwnFakeCli()
    }

    // spec: closing every tab in one go selects none, never a tab-less open session
    @Test
    fun `closing every tab in one go selects none, never a tab-less open session`() = timeoutRunBlocking(60.seconds) {
        val dir = tempDir.get().resolve("close-all").createDirectories()
        openProjectAsync(dir).useProjectAsync { project ->
            val manager = TandemSessions.get(project).also { it.ensureStarted() }.manager
            val actions = SessionActions(project, manager, TandemProjectScope.get(project).scope, tabs = { manager.tabIds() })
            val s0 = actions.create(NewSessionOptions())
            val (a, b, c) = List(3) { actions.create(NewSessionOptions()) }
            val stopped = manager.sessions.value.single { it.id == s0 }
            stopped.status.first { it != SessionStatus.STOPPED } // its process is up
            manager.stop(s0)
            stopped.status.first { it == SessionStatus.STOPPED }
            manager.select(b)
            while (manager.tabIds() != listOf(a, b, c)) delay(20) // a, b and c live; s0 stopped, not selected: no tab
            val seen = recordSelections(manager)
            try {
                withContext(Dispatchers.EDT) {
                    // As the veto of removeAllContents calls them: back to back, without yielding.
                    actions.close(a)
                    actions.close(b)
                    actions.close(c)
                }
                while (manager.sessions.value.map { it.id } != listOf(s0)) delay(20)
                assertNull(manager.selected.value)
                assertTrue(seen.none { it.first == s0 }, "a tab-less session was selected: $seen")
            } finally {
                seen.stop()
            }
        }
        awaitNoOwnFakeCli()
    }

    // spec: Close Other Tabs on b keeps b selected
    @Test
    fun `Close Other Tabs on b keeps b selected`() = timeoutRunBlocking(60.seconds) {
        val dir = tempDir.get().resolve("close-others").createDirectories()
        openProjectAsync(dir).useProjectAsync { project ->
            val manager = TandemSessions.get(project).also { it.ensureStarted() }.manager
            val actions = SessionActions(project, manager, TandemProjectScope.get(project).scope, tabs = { manager.tabIds() })
            val (a, b, c) = List(3) { actions.create(NewSessionOptions()) }
            manager.select(b)
            val seen = recordSelections(manager)
            try {
                withContext(Dispatchers.EDT) {
                    actions.close(a)
                    actions.close(c)
                }
                while (manager.sessions.value.map { it.id } != listOf(b)) delay(20)
                assertEquals(b, manager.selected.value)
                assertTrue(seen.all { it.first == b }, "the selection moved: $seen")
            } finally {
                seen.stop()
            }
        }
        awaitNoOwnFakeCli()
    }

    /** Records every change of [manager]'s selection from now on ([Selections]). */
    private fun CoroutineScope.recordSelections(manager: SessionManager): Selections {
        val seen = Selections()
        // Unconfined: the collector runs inside select() itself, so it sees the open sessions as they were at that moment.
        seen.job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            var before = manager.selected.value
            manager.selected.collect { now ->
                if (now != before) seen.add(now to (before != null && manager.sessions.value.any { it.id == before }))
                before = now
            }
        }
        return seen
    }

    /** Each selection made, with whether the session selected before it was still open then. */
    private class Selections : MutableList<Pair<String?, Boolean>> by Collections.synchronizedList(ArrayList()) {
        var job: Job? = null

        fun stop() {
            job?.cancel()
        }
    }

    /** Runs [close] on the EDT and waits until session [id] is no longer open. */
    private suspend fun closeOn(manager: SessionManager, id: String, close: () -> Unit) {
        withContext(Dispatchers.EDT) { close() }
        while (manager.sessions.value.any { it.id == id }) delay(20)
    }

    /** Writes the `history-reply` transcript as session [id]'s, in the CLI's folder for the project in [dir]. */
    private fun seed(dir: Path, id: String = SEEDED) {
        val folder = configDir.resolve("projects").resolve(TranscriptFolder.key(dir.toRealPath().toString())).createDirectories()
        Fixtures.transcript("history-reply").copyTo(folder.resolve("$id.jsonl"))
    }
}

package dev.daarkolabs.tandem.ide

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.OwnFakeCliProcesses
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.session.TurnEnd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    private val ownProcesses = OwnFakeCliProcesses()

    @JvmField
    @RegisterExtension
    val ownConfigDir: OwnConfigDir = OwnConfigDir()

    private val configDir: Path get() = ownConfigDir.path

    /**
     * What a test sees: the sessions, the notifications posted to the project so far, and how many processes have started. With a
     * late notifier, [startNotifier] starts it.
     */
    private class Run(
        val manager: SessionManager,
        val posted: List<Notification>,
        private val log: Path,
        val startNotifier: () -> Unit = {},
    ) {
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
     *
     * With [hold] or [visible], the notifier is not the one [TandemSessions.ensureStarted] starts but one of the test's own, which
     * runs [hold] before each post, ahead of its check on the EDT, and asks [visible] whether the tool window shows. With [lateNotifier], nothing is started: the test's own notifier starts
     * when the test calls [Run.startNotifier].
     */
    private suspend fun <T> withSessions(
        name: String,
        fixtures: String,
        hold: (suspend () -> Unit)? = null,
        visible: (() -> Boolean)? = null,
        lateNotifier: Boolean = false,
        block: suspend Run.() -> T,
    ): T {
        val dir = tempDir.get().resolve(name).createDirectories()
        val log = configDir.resolve("fake-cli.log")
        val result = openProjectAsync(dir).useProjectAsync { project ->
            val posted = Collections.synchronizedList(ArrayList<Notification>())
            val listening = Disposer.newDisposable()
            val context = currentCoroutineContext()
            val notifying = CoroutineScope(context + SupervisorJob(context.job))
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
                when {
                    lateNotifier -> {}
                    hold == null && visible == null -> {
                        sessions.ensureStarted()
                        sessions.ensureStarted() // once is enough: a second call starts nothing, so nothing is notified twice
                    }
                    else -> SessionNotifier(project, sessions.manager, notifying).apply {
                        hold?.let { beforePosting = it }
                        visible?.let { toolWindowVisible = it }
                    }.start()
                }
                Run(sessions.manager, posted, log) { SessionNotifier(project, sessions.manager, notifying).start() }.block()
            } finally {
                notifying.cancel()
                Disposer.dispose(listening)
            }
        }
        while (ownProcesses.list().isNotEmpty()) delay(20)
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
        val stopped = notifications.single()
        assertEquals(NotificationType.INFORMATION, stopped.type)
        assertEquals("$title stopped", stopped.title, "the turn was interrupted")
    }

    @Test
    fun `a background turn that completes is announced as finished`() = timeoutRunBlocking(60.seconds) {
        val (notifications, title) = withSessions("background-completed", fixtures = "plain-reply,plain-reply") {
            val a = manager.create(NewSessionOptions())
            manager.select(a.id)
            awaitSpawns(1)
            val b = manager.create(NewSessionOptions())
            awaitSpawns(2)
            manager.send(b.id, "Reply with the single word: pong")
            notifications() to b.title.value
        }
        val finished = notifications.single()
        assertEquals(NotificationType.INFORMATION, finished.type)
        assertEquals("$title finished", finished.title)
    }

    @Test
    fun `a finished turn is told by how it ended`() {
        val finished = SessionNotifier.Event.FINISHED
        assertEquals("Pong finished", finished.title("Pong", TurnEnd.COMPLETED))
        assertEquals("Pong stopped", finished.title("Pong", TurnEnd.INTERRUPTED))
        assertEquals("Pong ended with an error", finished.title("Pong", TurnEnd.FAILED))
        assertEquals("Pong finished", finished.title("Pong", null), "no result kept: a turn that ended")
        assertEquals("Pong needs your permission", SessionNotifier.Event.PERMISSION.title("Pong", TurnEnd.FAILED))
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

    @Test
    fun `a selected session that already waits when the notifier starts is announced once`() = timeoutRunBlocking(60.seconds) {
        val notifications = withSessions("waiting-before-start", fixtures = "permission", lateNotifier = true) {
            val b = manager.create(NewSessionOptions())
            manager.select(b.id)
            awaitSpawns(1)
            manager.send(b.id, "Create the fixture file")
            b.status.first { it == SessionStatus.WAITING }
            // The notifier starts following B only now, as when it is slow to start: B's request is not news to it, but it stands.
            startNotifier()
            withTimeoutOrNull(10_000) { notifications() } ?: posted.toList()
        }
        val warning = notifications.single()
        assertEquals(NotificationType.WARNING, warning.type)
        assertEquals("Create the fixture file needs your permission", warning.title)
    }

    @Test
    fun `a background session that already asks for attention when the notifier starts is announced once`() =
        timeoutRunBlocking(60.seconds) {
            val notifications = withSessions("attention-before-start", fixtures = "plain-reply,permission", lateNotifier = true) {
                val a = manager.create(NewSessionOptions())
                manager.select(a.id)
                awaitSpawns(1)
                val b = manager.create(NewSessionOptions())
                awaitSpawns(2)
                manager.send(b.id, "Create the fixture file")
                b.attention.first { it }
                startNotifier()
                withTimeoutOrNull(10_000) { notifications() } ?: posted.toList()
            }
            val warning = notifications.single()
            assertEquals(NotificationType.WARNING, warning.type)
            assertEquals("Create the fixture file needs your permission", warning.title)
        }

    @Test
    fun `a permission request answered before its notification is posted is not announced`() = timeoutRunBlocking(60.seconds) {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hold: suspend () -> Unit = {
            held.complete(Unit)
            release.await()
        }
        val notifications = withSessions("answered-meanwhile", fixtures = "plain-reply,permission", hold) {
            val a = manager.create(NewSessionOptions())
            manager.select(a.id)
            awaitSpawns(1)
            val b = manager.create(NewSessionOptions())
            awaitSpawns(2)
            manager.send(b.id, "Create the fixture file")
            held.await() // the notifier saw B ask, and is about to post, as after waiting for a modal dialog
            manager.allow(b.id, b.state.value.pendingPermissions.single().requestId, always = false)
            b.status.first { it != SessionStatus.WAITING }
            release.complete(Unit)
            b.status.first { it == SessionStatus.IDLE } // the turn goes on to its end
            delay(300) // time for a wrong notification
            posted.toList()
        }
        // The request was answered: announcing it would be wrong. That the turn finished may be announced.
        assertTrue(notifications.none { it.type == NotificationType.WARNING }, notifications.joinToString { it.title })
    }

    @Test
    fun `a turn that ends after a notification found nothing to announce is announced`() = timeoutRunBlocking(60.seconds) {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hold: suspend () -> Unit = {
            held.complete(Unit)
            release.await()
        }
        val notifications = withSessions("finished-later", fixtures = "plain-reply,permission,interrupt", hold) {
            val a = manager.create(NewSessionOptions())
            manager.select(a.id)
            awaitSpawns(1)
            val b = manager.create(NewSessionOptions())
            awaitSpawns(2) // B's first process replays permission
            manager.send(b.id, "Create the fixture file")
            held.await() // the notifier saw B ask, and is about to post, as after waiting for a modal dialog
            // Meanwhile B starts again, with a turn that runs until it is interrupted: the request is gone, and B is busy.
            manager.stop(b.id)
            manager.send(b.id, "Count to a hundred")
            awaitSpawns(3) // B's second process replays interrupt: it streams, then waits for the interrupt
            b.status.first { it == SessionStatus.RUNNING }
            release.complete(Unit)
            delay(1_000) // the held post finds B running, with nothing to announce
            assertEquals(emptyList(), posted.map { it.title })
            // B has asked for attention all along, and nobody has looked: the end of its turn is announced.
            manager.interrupt(b.id)
            b.status.first { it == SessionStatus.IDLE }
            withTimeoutOrNull(10_000) { while (posted.isEmpty()) delay(20) }
            delay(300) // time for a wrong second notification
            posted.toList()
        }
        val stopped = notifications.single()
        assertEquals(NotificationType.INFORMATION, stopped.type)
        assertTrue(stopped.title.endsWith(" stopped"), stopped.title) // the turn was interrupted
    }

    @Test
    fun `a request the user has looked at before its notification is posted is not announced`() = timeoutRunBlocking(60.seconds) {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hold: suspend () -> Unit = {
            held.complete(Unit)
            release.await()
        }
        val notifications = withSessions("looked-meanwhile", fixtures = "plain-reply,permission", hold) {
            val a = manager.create(NewSessionOptions())
            manager.select(a.id)
            awaitSpawns(1)
            val b = manager.create(NewSessionOptions())
            awaitSpawns(2)
            manager.send(b.id, "Create the fixture file")
            held.await()
            // The user looks at B, which clears its attention, and goes back to A: headless the tool window is never visible, so B
            // staying selected would make it a selected session asking with the window hidden, which is announced.
            manager.select(b.id)
            manager.select(a.id)
            release.complete(Unit)
            delay(300) // time for a wrong notification
            posted.toList()
        }
        assertEquals(emptyList(), notifications.map { it.title })
    }

    @Test
    fun `the selected session is never announced while the tool window shows`() = timeoutRunBlocking(60.seconds) {
        val notifications = withSessions("selected-visible", fixtures = "permission,permission", visible = { true }) {
            val a = manager.create(NewSessionOptions())
            manager.select(a.id)
            awaitSpawns(1)
            manager.send(a.id, "Create the selected file")
            a.status.first { it == SessionStatus.WAITING }
            val b = manager.create(NewSessionOptions())
            awaitSpawns(2)
            manager.send(b.id, "Create the background file")
            b.status.first { it == SessionStatus.WAITING }
            notifications() // B's, which comes after A's would have
        }
        // A asks while the user looks at it; B, which is not selected, is announced all the same.
        assertEquals(listOf("Create the background file needs your permission"), notifications.map { it.title })
    }

    @Test
    fun `the notification's Open selects the session`() = timeoutRunBlocking(60.seconds) {
        val (selected, expired) = withSessions("open-action", fixtures = "plain-reply,permission") {
            val a = manager.create(NewSessionOptions())
            manager.select(a.id)
            awaitSpawns(1)
            val b = manager.create(NewSessionOptions())
            awaitSpawns(2)
            manager.send(b.id, "Create the fixture file")
            val warning = notifications().single()
            withContext(Dispatchers.EDT) { Notification.fire(warning, warning.actions.single(), null) }
            listOf(manager.selected.value == b.id, b.attention.value) to warning.isExpired
        }
        assertEquals(listOf(true, false), selected, "B is selected, and no longer asks for attention")
        assertTrue(expired, "Open expires the notification")
    }

    @Test
    fun `a turn whose running status was conflated away between a permission request and its end is a finished turn`() {
        assertEquals(SessionNotifier.Event.FINISHED, SessionNotifier.eventOf(SessionStatus.WAITING, SessionStatus.IDLE))
        assertEquals(SessionNotifier.Event.FINISHED, SessionNotifier.eventOf(SessionStatus.RUNNING, SessionStatus.IDLE))
        assertEquals(SessionNotifier.Event.PERMISSION, SessionNotifier.eventOf(SessionStatus.RUNNING, SessionStatus.WAITING))
        assertEquals(SessionNotifier.Event.PERMISSION, SessionNotifier.eventOf(null, SessionStatus.WAITING))
        assertEquals(null, SessionNotifier.eventOf(null, SessionStatus.IDLE), "a session followed from now on finished nothing")
        assertEquals(null, SessionNotifier.eventOf(SessionStatus.STOPPED, SessionStatus.IDLE), "a process that started")
        assertEquals(null, SessionNotifier.eventOf(SessionStatus.WAITING, SessionStatus.STOPPED), "stopped, not finished")
        assertEquals(null, SessionNotifier.eventOf(SessionStatus.IDLE, SessionStatus.IDLE))
    }
}

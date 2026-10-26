package dev.daarkolabs.tandem.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.ide.TandemSettings
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.OwnFakeCliProcesses
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** New Session (desktop-parity spec 18.2, 9.8): its registration, its choices and what it performs. */
@TestApplication
class NewSessionActionTest {
    companion object {
        private val tempDir = tempPathFixture()
    }

    private val ownProcesses = OwnFakeCliProcesses()

    @BeforeEach
    fun useOwnConfigDir() {
        System.setProperty("tandem.configDir", Files.createTempDirectory(tempDir.get(), "config").toString())
    }

    @AfterEach
    fun forgetConfigDir() {
        System.clearProperty("tandem.configDir")
    }

    private suspend fun awaitNoOwnFakeCli() {
        while (ownProcesses.list().isNotEmpty()) delay(20)
    }

    private fun event(project: Project): AnActionEvent = AnActionEvent.createEvent(
        NewSessionAction(),
        SimpleDataContext.getProjectContext(project),
        Presentation(),
        ActionPlaces.KEYBOARD_SHORTCUT,
        ActionUiKind.NONE,
        null,
    )

    // spec: the action is registered without a default shortcut
    @Test
    fun `the action is registered without a default shortcut`() {
        assertIs<NewSessionAction>(ActionManager.getInstance().getAction(NEW_SESSION_ACTION_ID))
        val keymap = KeymapManager.getInstance().getKeymap("\$default")!!
        assertTrue(keymap.getShortcuts(NEW_SESSION_ACTION_ID).isEmpty())
    }

    // spec: a new session takes the remembered choices
    @Test
    fun `a new session takes the remembered choices`() = timeoutRunBlocking(30.seconds) {
        openProjectAsync(tempDir.get().resolve("choices").createDirectories()).useProjectAsync { project ->
            val last = TandemWorkspaceState.getInstance(project).state
            last.lastModel = "sonnet"
            last.lastEffort = "high"
            last.lastPermissionMode = "plan"
            assertEquals(NewSessionOptions("sonnet", "high", PermissionMode.PLAN), TandemWindowService.get(project).newSessionOptions())
        }
    }

    // spec: performing the action activates the tool window, creates a session, selects it and asks for its focus
    @Test
    fun `performing the action activates the tool window, creates a session, selects it and asks for its focus`() = timeoutRunBlocking(30.seconds) {
        openProjectAsync(tempDir.get().resolve("perform").createDirectories()).useProjectAsync { project ->
            val sessions = TandemSessions.get(project).also { it.ensureStarted() }
            val manager = sessions.manager
            val window = TandemWindowService.get(project)
            val held = ArrayList<() -> Unit>()
            window.activateWindow = { then -> held += then }
            val action = NewSessionAction()
            withContext(Dispatchers.EDT) {
                val e = event(project)
                action.update(e)
                assertTrue(e.presentation.isEnabledAndVisible)
                action.actionPerformed(e)
            }
            assertEquals(1, held.size, "the tool window was activated once")
            val created = manager.sessions.value.single()
            assertEquals(created.id, manager.selected.value)
            assertTrue(window.focusRequests.replayCache.isEmpty(), "the focus is asked for only once the window is active")
            withContext(Dispatchers.EDT) { held.single()() }
            assertEquals(created.id, window.focusRequests.first())
            window.takeFocusRequest()
            assertTrue(window.focusRequests.replayCache.isEmpty())
        }
        awaitNoOwnFakeCli()
    }

    // spec: at the cap a new session's tab replaces the idle one's
    @Test
    fun `at the cap a new session's tab replaces the idle one's`() = timeoutRunBlocking(60.seconds) {
        val settings = TandemSettings.getInstance().state
        val cap = settings.maxLiveSessions
        settings.maxLiveSessions = 1
        try {
            openProjectAsync(tempDir.get().resolve("cap").createDirectories()).useProjectAsync { project ->
                val manager = TandemSessions.get(project).also { it.ensureStarted() }.manager
                val window = TandemWindowService.get(project)
                window.activateWindow = { then -> then() }
                val first = withContext(Dispatchers.EDT) { window.actions.create(NewSessionOptions()) }
                val a = manager.sessions.value.single { it.id == first }
                manager.send(a.id, "hi")
                a.state.first { it.turnsEnded == 1 }
                a.status.first { it == SessionStatus.IDLE }

                val b = withContext(Dispatchers.EDT) { window.newSession() }
                a.status.first { it == SessionStatus.STOPPED }
                while (manager.tabIds() != listOf(b)) delay(20)
                assertTrue(manager.sessions.value.any { it.id == a.id }, "A keeps its row")
                assertFalse(a.attention.value)
            }
        } finally {
            settings.maxLiveSessions = cap
        }
        awaitNoOwnFakeCli()
    }
}

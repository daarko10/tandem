package dev.daarkolabs.tandem.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.daarkolabs.tandem.TandemProjectScope
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.SessionId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The id of the Tandem tool window in `plugin.xml`. */
internal const val TOOL_WINDOW_ID: String = "Tandem"

/**
 * The Tandem tool window's state outside its composition (desktop-parity spec 18.4, deviation 25): the one [SessionActions] its
 * sidebar, header and session tabs share, the composer focus requests that the tabs and the `New Session` action make
 * ([focusRequests], which `TandemToolWindow` takes), and [newSession] for that action.
 */
@Service(Service.Level.PROJECT)
internal class TandemWindowService(private val project: Project) {
    private val manager = TandemSessions.get(project).manager

    val actions: SessionActions = SessionActions(project, manager, TandemProjectScope.get(project).scope, tabs = manager::tabIds)

    // The last request stays until the tool window takes it, so one made before its composition collects is not lost.
    private val mutableFocusRequests = MutableSharedFlow<SessionId>(replay = 1, extraBufferCapacity = 1)

    /** The sessions whose composer should take the focus, the last one kept until taken ([takeFocusRequest]). */
    val focusRequests: SharedFlow<SessionId> = mutableFocusRequests.asSharedFlow()

    /** Asks the tool window to give session [id]'s composer the focus. */
    fun requestFocus(id: SessionId) {
        mutableFocusRequests.tryEmit(id)
    }

    /** Forgets the request the tool window has just taken, so it is not replayed to a later collector. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun takeFocusRequest() {
        mutableFocusRequests.resetReplayCache()
    }

    /** The options a session started without the popup gets: the project's last picks, as the popup preselects them. */
    fun newSessionOptions(): NewSessionOptions {
        val last = TandemWorkspaceState.getInstance(project).state
        return lastPicks(last.lastModel, last.lastEffort, last.lastPermissionMode)
    }

    /** Shows and activates the Tandem tool window, then runs [then]; a test replaces it (headless, no tool window activates). */
    internal var activateWindow: (then: () -> Unit) -> Unit = { then ->
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (window != null) window.activate { then() } else then()
    }

    /**
     * Starts a session with [newSessionOptions] and selects it, then shows the tool window and gives the new session's composer the
     * focus (spec 18.2). Returns its id.
     */
    fun newSession(): SessionId {
        val id = actions.create(newSessionOptions())
        activateWindow { requestFocus(id) }
        return id
    }

    companion object {
        fun get(project: Project): TandemWindowService = project.service()
    }
}

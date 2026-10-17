package dev.daarkolabs.tandem.ide

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * What Tandem remembers about a project between IDE runs, in its workspace file (never shared): the sessions that were open, the one
 * selected, the sidebar, and the last choices for a new session. [TandemSessions] keeps the sessions and the selection up to date.
 */
@Service(Service.Level.PROJECT)
@State(name = "TandemWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TandemWorkspaceState : SimplePersistentStateComponent<TandemWorkspaceState.State>(TandemWorkspaceState.State()) {
    /** What is stored. */
    class State : BaseState() {
        /** The ids of the open sessions, in the order they were opened. */
        var openSessionIds: MutableList<String> by list()

        /** The id of the selected session, if one was. */
        var selectedSessionId: String? by string()

        /** Whether the user collapsed the session sidebar. */
        var sidebarCollapsed: Boolean by property(false)

        /** The model last picked for a new session; null for the CLI's default. */
        var lastModel: String? by string()

        /** The effort last picked for a new session; null for the CLI's default. */
        var lastEffort: String? by string()

        /** The permission mode last picked for a new session, as the CLI names it (`default`, `acceptEdits`, `plan`). */
        var lastPermissionMode: String? by string()
    }

    companion object {
        fun getInstance(project: Project): TandemWorkspaceState = project.service()
    }
}

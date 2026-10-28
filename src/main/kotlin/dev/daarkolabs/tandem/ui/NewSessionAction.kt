package dev.daarkolabs.tandem.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** The id of [NewSessionAction] in `plugin.xml` (desktop-parity spec 18.2). */
const val NEW_SESSION_ACTION_ID: String = "Tandem.NewSession"

/**
 * New Session (desktop-parity spec 18.2): the `+` after the session tabs, also in Find Action and the Keymap, with no default
 * shortcut. Starts a session with the project's last model, effort and permission mode, without the popup, selects it and gives
 * its composer the focus, showing the Tandem tool window first.
 */
class NewSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        TandemWindowService.get(project).newSession()
    }
}

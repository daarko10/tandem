package dev.daarkolabs.tandem.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import dev.daarkolabs.tandem.TandemProjectScope
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.ui.dev.RenderingSpike
import org.jetbrains.jewel.bridge.addComposeTab

/** Creates the Tandem tool window: the project's sessions, rendered with Jewel (Compose). */
internal class TandemToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (RenderingSpike.enabled) {
            val presenter = ChatRowsPresenter(TandemProjectScope.get(project).scope, RenderingSpike.transcript.entries)
            toolWindow.addComposeTab("Chat", focusOnClickInside = true) {
                ChatPanel(project, presenter.rows, emptyText = "Rendering spike starting.")
            }
            return
        }
        val sessions = TandemSessions.get(project)
        toolWindow.addComposeTab("Chat", focusOnClickInside = true) {
            TandemToolWindow(project, sessions)
        }
        sessions.ensureStarted()
    }
}

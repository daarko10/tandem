package dev.daarkolabs.tandem.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import dev.daarkolabs.tandem.TandemProjectScope
import dev.daarkolabs.tandem.ide.TandemChat
import dev.daarkolabs.tandem.ui.dev.RenderingSpike
import kotlinx.coroutines.flow.map
import org.jetbrains.jewel.bridge.addComposeTab

/** Creates the Tandem tool window. The chat UI is rendered with Jewel (Compose). */
internal class TandemToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val scope = TandemProjectScope.get(project).scope
        if (RenderingSpike.enabled) {
            val presenter = ChatRowsPresenter(scope, RenderingSpike.transcript.entries)
            toolWindow.addComposeTab("Chat", focusOnClickInside = true) {
                ChatPanel(project, presenter.rows, emptyText = "Rendering spike starting.")
            }
            return
        }
        val chat = TandemChat.get(project)
        val presenter = ChatRowsPresenter(scope, chat.state.map { it.toTranscript() })
        toolWindow.addComposeTab("Chat", focusOnClickInside = true) {
            ChatScreen(project, chat, presenter.rows)
        }
        chat.start()
    }
}

package dev.daarkolabs.tandem.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import dev.daarkolabs.tandem.TandemProjectScope
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.ui.dev.RenderingSpike
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.enableNewSwingCompositing

/** What the tool window shows with no session tab (desktop-parity spec 18.2), also as its empty text. */
internal const val NO_SESSION_HINT: String = "Create a session with + or open the sidebar."

/**
 * Creates the Tandem tool window: the project's sessions, rendered with Jewel (Compose), one header tab per live or selected
 * session (desktop-parity spec 18), every tab showing the same panel.
 */
internal class TandemToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project): Boolean = true

    @OptIn(ExperimentalJewelApi::class, ExperimentalComposeUiApi::class)
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (RenderingSpike.enabled) {
            val presenter = ChatPresenter(
                TandemProjectScope.get(project).scope,
                RenderingSpike.transcript.state,
                MutableStateFlow(RowOptions(showThinking = true)),
            )
            val ui = ChatUiState().also { it.onExpand = presenter::expandBody }
            toolWindow.addComposeTab("Chat", focusOnClickInside = true) {
                ChatPanel(project, presenter, ui, emptyText = "Rendering spike starting.", lastFrameAt = MutableStateFlow(0L), onFocusComposer = {})
            }
            return
        }
        val sessions = TandemSessions.get(project)
        val window = TandemWindowService.get(project)
        // As addComposeTab does before it builds its panel (spec 18.3).
        enableNewSwingCompositing()
        var compose: ComposePanel? = null
        // Every platform tab switch takes the shared panel out and puts it back: it must keep its composition (spec 18.3).
        val panel = JewelComposePanel(focusOnClickInside = true, config = {
            isDisposeOnRemove = false
            compose = this
        }) { TandemToolWindow(project, sessions) }
        val controller = SessionTabsController(
            toolWindow.contentManager,
            panel,
            managerTabsSource(sessions.manager),
            window.actions,
            window::requestFocus,
            TandemProjectScope.get(project).scope,
        )
        Disposer.register(toolWindow.disposable) {
            controller.close()
            compose?.dispose()
        }
        (toolWindow as? ToolWindowEx)?.let { ex ->
            ActionManager.getInstance().getAction(NEW_SESSION_ACTION_ID)?.let { ex.setTabActions(it) }
            // The second guard: a tool window whose content manager empties keeps showing with its own empty text (spec 18.2).
            ex.emptyText?.text = NO_SESSION_HINT
        }
        sessions.ensureStarted()
    }
}

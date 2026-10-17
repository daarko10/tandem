package dev.daarkolabs.tandem.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<SessionNotifier>()

/** The tool window the chats are in, as plugin.xml registers it. */
private const val TOOL_WINDOW_ID: String = "Tandem"

/** The notification group plugin.xml registers. */
private const val GROUP_ID: String = "Tandem"

/**
 * Posts an IDE notification, in the "Tandem" group, when a session asks for permission or finishes a turn while the user does not see
 * it: the session is not the selected one, or the Tandem tool window is hidden. A permission request is a warning, "<title> needs your
 * permission"; a finished turn is information, "<title> finished". Its "Open" action selects the session and shows the tool window.
 * The selected session never gets one while the tool window is visible. Titles are shown as plain text.
 *
 * A session that is not selected is followed through its [SessionHandle.attention], which the manager raises for exactly these events
 * and clears when the session is selected: one notification until the user has looked. The selected session raises no attention, so
 * its status is followed instead.
 *
 * Runs in [scope], from [start] until the scope ends.
 */
class SessionNotifier(private val project: Project, private val manager: SessionManager, private val scope: CoroutineScope) {
    /** Follows every session that is open or opened later. Call once. */
    fun start() {
        scope.launch(CoroutineName("SessionNotifier")) {
            // By handle, not by id: a session closed and opened again is a new handle, followed afresh.
            val followed = HashMap<SessionHandle, Job>()
            manager.sessions.collect { open ->
                followed.keys.filter { it !in open }.forEach { followed.remove(it)?.cancel() }
                open.filter { it !in followed }.forEach { handle -> followed[handle] = launch { follow(handle) } }
            }
        }
    }

    /** Notifies about [handle]'s permission requests and finished turns, until it is closed. */
    private suspend fun follow(handle: SessionHandle): Unit = coroutineScope {
        launch {
            var raised = false
            handle.attention.collect { attention ->
                if (attention && !raised) {
                    // The manager raises it for a permission request, or for a turn that ended.
                    notify(handle, if (handle.status.value == SessionStatus.WAITING) Event.PERMISSION else Event.FINISHED)
                }
                raised = attention
            }
        }
        launch {
            var previous = handle.status.value
            handle.status.collect { status ->
                val event = eventOf(previous, status)
                previous = status
                if (event != null && manager.selected.value == handle.id) notify(handle, event)
            }
        }
    }

    /**
     * Posts [event] for [handle], unless the user is looking at it: it is selected and the tool window is visible.
     *
     * On the EDT, where the platform also closes the project, after cancelling this scope: so nothing is ever posted to a project
     * being closed. Like a notification the platform delivers from another thread, it waits until no modal dialog is open.
     */
    private suspend fun notify(handle: SessionHandle, event: Event) {
        val posted = withContext(Dispatchers.EDT) {
            if (project.isDisposed || (manager.selected.value == handle.id && toolWindowVisible())) return@withContext false
            NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
                // Notifications render HTML; a title is the user's or the model's text, so it is escaped.
                .createNotification(StringUtil.escapeXmlEntities(event.title(handle.title.value)), "", event.type)
                .addAction(NotificationAction.createSimpleExpiring("Open") { open(handle.id) })
                .notify(project)
            true
        }
        if (posted) LOG.info("session ${handle.id}: notified, ${event.name.lowercase()}")
    }

    /** Whether the Tandem tool window shows. On the EDT. */
    private fun toolWindowVisible(): Boolean = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.isVisible == true

    /** The notification's "Open", on the EDT: selects session [id] and shows the tool window. */
    private fun open(id: SessionId) {
        if (project.isDisposed) return
        manager.select(id)
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    /** What a notification tells. */
    private enum class Event(val type: NotificationType, private val what: String) {
        PERMISSION(NotificationType.WARNING, "needs your permission"),
        FINISHED(NotificationType.INFORMATION, "finished"),
        ;

        fun title(session: String): String = "$session $what"
    }

    /** The event a change of status from [previous] to [status] is, as the manager raises attention for it; null for none. */
    private fun eventOf(previous: SessionStatus, status: SessionStatus): Event? = when {
        status == previous -> null
        status == SessionStatus.WAITING -> Event.PERMISSION
        previous == SessionStatus.RUNNING && status == SessionStatus.IDLE -> Event.FINISHED
        else -> null
    }
}

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
import dev.daarkolabs.tandem.session.turnEnded
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly

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
 * its status is followed instead. What a notification says is decided on the EDT just before it is posted, so that one that had to
 * wait (for a modal dialog, say) never announces what no longer stands: a permission request already answered, or one the user has
 * looked at. When there is nothing to announce then (a new turn runs), the session's next change of status, while it still asks for
 * attention, is announced in its place: the end of that turn, say.
 *
 * Runs in [scope], from [start] until the scope ends.
 */
class SessionNotifier(private val project: Project, private val manager: SessionManager, private val scope: CoroutineScope) {
    /** Runs before each post, ahead of the EDT check: tests hold a post here to change the session meanwhile. */
    @Volatile
    @set:TestOnly
    internal var beforePosting: suspend () -> Unit = {}

    /** Whether the Tandem tool window shows, asked on the EDT: headless, it never does, so tests put their own answer here. */
    @Volatile
    @set:TestOnly
    internal var toolWindowVisible: () -> Boolean = {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.isVisible == true
    }

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
            // Whether the attention asked for has been announced; until it is, every change of status is another chance.
            var announced = false
            combine(handle.attention, handle.status) { attention, _ -> attention }.collect { attention ->
                if (!attention) {
                    announced = false
                } else if (!announced) {
                    announced = notify(handle) { dueForAttention(handle) }
                }
            }
        }
        launch {
            // Null until the first status: the one the session has when it is followed counts as a change, so that a request it
            // already waits on is announced, whenever this starts following it.
            var previous: SessionStatus? = null
            handle.status.collect { status ->
                val event = eventOf(previous, status)
                previous = status
                if (event != null && manager.selected.value == handle.id) notify(handle) { event.takeIf { stillDue(handle, it) } }
            }
        }
    }

    /**
     * Posts the event [due] gives for [handle], if it gives one, unless the user is looking at the session: it is selected and the
     * tool window is visible. [due] runs on the EDT, right before the post. True if a notification was posted.
     *
     * On the EDT, where the platform also closes the project, after cancelling this scope: so nothing is ever posted to a project
     * being closed. Like a notification the platform delivers from another thread, it waits until no modal dialog is open.
     */
    private suspend fun notify(handle: SessionHandle, due: () -> Event?): Boolean {
        beforePosting()
        val posted = withContext(Dispatchers.EDT) {
            if (project.isDisposed || (manager.selected.value == handle.id && toolWindowVisible())) return@withContext null
            val event = due() ?: return@withContext null
            NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
                // Notifications render HTML; a title is the user's or the model's text, so it is escaped.
                .createNotification(StringUtil.escapeXmlEntities(event.title(handle.title.value)), "", event.type)
                .addAction(NotificationAction.createSimpleExpiring("Open") { open(handle.id) })
                .notify(project)
            event
        }
        if (posted != null) LOG.info("session ${handle.id}: notified, ${posted.name.lowercase()}")
        return posted != null
    }

    /**
     * What to announce for [handle], which asks for attention: the permission request it still waits on, or the turn that ended. Null
     * once the user has looked at it, and while a turn runs (the request was answered meanwhile, or a new turn began).
     */
    private fun dueForAttention(handle: SessionHandle): Event? {
        if (!handle.attention.value) return null
        return when (handle.status.value) {
            SessionStatus.WAITING -> Event.PERMISSION
            SessionStatus.IDLE, SessionStatus.STOPPED -> Event.FINISHED
            SessionStatus.RUNNING -> null
        }
    }

    /** Whether [event] of the selected [handle] still stands: a permission request only while it is not answered. */
    private fun stillDue(handle: SessionHandle, event: Event): Boolean =
        when (event) {
            Event.PERMISSION -> handle.status.value == SessionStatus.WAITING
            Event.FINISHED -> true
        }

    /** The notification's "Open", on the EDT: selects session [id] and shows the tool window. */
    private fun open(id: SessionId) {
        if (project.isDisposed) return
        manager.select(id)
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    /** What a notification tells. */
    internal enum class Event(val type: NotificationType, private val what: String) {
        PERMISSION(NotificationType.WARNING, "needs your permission"),
        FINISHED(NotificationType.INFORMATION, "finished"),
        ;

        fun title(session: String): String = "$session $what"
    }

    internal companion object {
        /**
         * The event a change of status from [previous] to [status] is, as the manager raises attention for it ([turnEnded]); null
         * for none. From no [previous] status (the session is followed from now on), only a permission request it waits on is one.
         */
        internal fun eventOf(previous: SessionStatus?, status: SessionStatus): Event? = when {
            status == previous -> null
            status == SessionStatus.WAITING -> Event.PERMISSION
            turnEnded(previous, status) -> Event.FINISHED
            else -> null
        }
    }
}

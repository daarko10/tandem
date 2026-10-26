package dev.daarkolabs.tandem.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import dev.daarkolabs.tandem.chat.TabSource
import dev.daarkolabs.tandem.chat.sessionTabs
import dev.daarkolabs.tandem.chat.tabNeighbour
import dev.daarkolabs.tandem.session.ForkResult
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private val LOG = logger<SessionActions>()

/** The notification group plugin.xml registers. */
private const val NOTIFICATION_GROUP: String = "Tandem"

/** What a session tab asks of the sessions (desktop-parity spec 18.2): [SessionActions] gives it, a test records it. */
internal interface TabActions {
    fun show(id: SessionId): Job?

    fun close(id: SessionId)
}

/**
 * What the tool window asks of the session [manager], from the EDT. Nothing here blocks: the manager's calls that suspend run in the
 * project's [scope], [delete] suspends until the session is gone and the others return at once. A rename or delete that fails is
 * shown as an error notification, in plain text.
 *
 * Closing or deleting the selected session first selects its tab's neighbour among [tabs] (the ids of the tool window's session
 * tabs), skipping the tabs whose own close or delete is under way, so that several closes in one go (Close All Tabs, Close Other
 * Tabs) never select a session that is itself closing or has no tab (spec 18.2).
 */
internal class SessionActions(
    private val project: Project,
    private val manager: SessionManager,
    private val scope: CoroutineScope,
    private val tabs: () -> List<SessionId> = { emptyList() },
) : TabActions {
    /** The sessions whose close or delete was launched and has not returned (spec 18.2, 18.4). */
    private val closing: MutableSet<SessionId> = ConcurrentHashMap.newKeySet()

    /** The session the user asked to see last, so that one still being opened never takes the selection from a later choice. */
    @Volatile
    private var requested: SessionId? = null

    /**
     * Selects session [id]; one that is not open is opened first, with its history and, if the cap allows, a process: the job that
     * does it is returned, null for an open session, selected at once.
     */
    override fun show(id: SessionId): Job? {
        requested = id
        if (manager.sessions.value.any { it.id == id }) {
            manager.select(id)
            return null
        }
        return scope.launch {
            manager.open(id)
            if (requested == id) manager.select(id)
        }
    }

    /** Starts a new session with [options] and selects it; returns its id. */
    fun create(options: NewSessionOptions): SessionId {
        val handle = manager.create(options)
        requested = handle.id
        manager.select(handle.id)
        return handle.id
    }

    /** Renames session [id] to [title]. */
    fun rename(id: SessionId, title: String) {
        scope.launch {
            try {
                manager.rename(id, title)
            } catch (e: IOException) {
                LOG.warn("session $id not renamed (${e.javaClass.simpleName})")
                notifyFailure("Could not rename the session to “$title”", e)
            }
        }
    }

    /**
     * Forks session [id], whole or, with [at], at that reply line (desktop-parity spec 13.1); the fork is selected. When nothing forks,
     * [id]'s chat says why (hard rule 5): no transcript yet (no prompt sent), a fork point not in its history, or a turn running.
     */
    fun fork(id: SessionId, at: String? = null) {
        requested = null
        scope.launch {
            val refusal = forkRefusal(manager.forkResult(id, at)) ?: return@launch
            manager.sessions.value.firstOrNull { it.id == id }?.notice(NoticeLevel.WARNING, refusal)
        }
    }

    /** Deletes session [id], titled [title]: true once it is gone, false if it could not be (the user is told why). */
    suspend fun delete(id: SessionId, title: String): Boolean {
        val deleted = CompletableDeferred<Boolean>()
        leaveTab(id)
        // In the project's scope, so that the delete goes on when the row that asked for it leaves the screen.
        scope.launch {
            try {
                manager.delete(id)
                deleted.complete(true)
            } catch (e: IOException) {
                LOG.warn("session $id not deleted (${e.javaClass.simpleName})")
                notifyFailure("Could not delete “$title”", e)
            } finally {
                closing.remove(id)
                deleted.complete(false)
            }
        }
        return deleted.await()
    }

    /** Closes session [id]: its process ends, its transcript stays. */
    override fun close(id: SessionId) {
        leaveTab(id)
        scope.launch {
            try {
                manager.close(id)
            } finally {
                closing.remove(id)
            }
        }
    }

    /**
     * Marks [id] closing and, when it is the selected session, selects its tab's neighbour first, among the tabs not closing
     * (spec 18.2): the manager's own neighbour rule then finds nothing selected.
     */
    private fun leaveTab(id: SessionId) {
        closing.add(id)
        if (manager.selected.value == id) manager.select(tabNeighbour(tabs(), id, closing))
    }

    /** Puts the command that resumes session [id] in a terminal on the clipboard. */
    fun copyResumeCommand(id: SessionId) {
        CopyPasteManager.getInstance().setContents(StringSelection("claude --resume $id"))
    }

    private fun notifyFailure(what: String, e: IOException) {
        val reason = listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": ")
        // Notifications render HTML; titles and messages are plain text.
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(StringUtil.escapeXmlEntities(what), StringUtil.escapeXmlEntities(reason), NotificationType.ERROR)
            .notify(project)
    }
}

/** What the chat says when a fork did not start (desktop-parity spec 13.2, hard rule 5: the real reason); null when it started. */
internal fun forkRefusal(result: ForkResult): String? = when (result) {
    is ForkResult.Started -> null
    ForkResult.Refused.NO_TRANSCRIPT -> "This session has no transcript to fork yet"
    ForkResult.Refused.NOT_IN_HISTORY -> "This reply cannot be forked: the CLI sent no id for it"
    ForkResult.Refused.BUSY -> "Wait for the reply to finish"
}

/** The ids of the session tabs, from the open sessions' current title, status and attention and the selection (spec 18.4). */
internal fun SessionManager.tabIds(): List<SessionId> =
    sessionTabs(sessions.value.map { TabSource(it.id, it.title.value, it.status.value, it.attention.value) }, selected.value).map { it.id }

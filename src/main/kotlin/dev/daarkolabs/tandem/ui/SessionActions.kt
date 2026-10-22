package dev.daarkolabs.tandem.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
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

private val LOG = logger<SessionActions>()

/** The notification group plugin.xml registers. */
private const val NOTIFICATION_GROUP: String = "Tandem"

/**
 * What the tool window asks of the session [manager], from the EDT. Nothing here blocks: the manager's calls that suspend run in the
 * project's [scope], [delete] suspends until the session is gone and the others return at once. A rename or delete that fails is
 * shown as an error notification, in plain text.
 */
internal class SessionActions(private val project: Project, private val manager: SessionManager, private val scope: CoroutineScope) {
    /** The session the user asked to see last, so that one still being opened never takes the selection from a later choice. */
    @Volatile
    private var requested: SessionId? = null

    /**
     * Selects session [id]; one that is not open is opened first, with its history and, if the cap allows, a process: the job that
     * does it is returned, null for an open session, selected at once.
     */
    fun show(id: SessionId): Job? {
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
     * Forks session [id]; the fork is selected. A session with no transcript yet (no prompt sent) has nothing to fork: its chat says
     * so (polish spec 2.6).
     */
    fun fork(id: SessionId) {
        requested = null
        scope.launch {
            if (manager.fork(id) == null) {
                manager.sessions.value.firstOrNull { it.id == id }?.notice(NoticeLevel.WARNING, "This session has no transcript to fork yet")
            }
        }
    }

    /** Deletes session [id], titled [title]: true once it is gone, false if it could not be (the user is told why). */
    suspend fun delete(id: SessionId, title: String): Boolean {
        val deleted = CompletableDeferred<Boolean>()
        // In the project's scope, so that the delete goes on when the row that asked for it leaves the screen.
        scope.launch {
            try {
                manager.delete(id)
                deleted.complete(true)
            } catch (e: IOException) {
                LOG.warn("session $id not deleted (${e.javaClass.simpleName})")
                notifyFailure("Could not delete “$title”", e)
            } finally {
                deleted.complete(false)
            }
        }
        return deleted.await()
    }

    /** Closes session [id]: its process ends, its transcript stays. */
    fun close(id: SessionId) {
        scope.launch { manager.close(id) }
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

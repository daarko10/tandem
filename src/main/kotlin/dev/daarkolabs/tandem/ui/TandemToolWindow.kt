package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import dev.daarkolabs.tandem.TandemProjectScope
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import java.awt.datatransfer.StringSelection
import java.io.IOException

private val LOG = logger<SessionActions>()

/** The sidebar shows only in a tool window at least this wide. */
private val MIN_WIDTH_FOR_SIDEBAR: Dp = 500.dp

private val SIDEBAR_WIDTH: Dp = 220.dp

/** The notification group plugin.xml registers. */
private const val NOTIFICATION_GROUP: String = "Tandem"

/**
 * The Tandem tool window: the session sidebar ([SessionSidebar]) beside the selected session's header ([SessionHeader]) and chat
 * ([ChatPane]), or, with nothing selected, a hint.
 *
 * The sidebar is docked beside the chat unless the user collapsed it (remembered in
 * [TandemWorkspaceState.State.sidebarCollapsed]) or the window is narrower than 500 dp. In a window that wide the header's toggle
 * docks and collapses it. In a narrower one the toggle opens the same sidebar over the chat instead, with the filter focused: it
 * closes when the user picks or creates a session, presses the toggle again, presses Escape or clicks the dimmed chat. The
 * header's "+" starts a session at any width.
 *
 * Every open session keeps a [ChatRowsPresenter] from the first time it is shown until it is closed, so that switching back to it
 * does not parse its transcript again.
 */
@Composable
internal fun TandemToolWindow(project: Project, sessions: TandemSessions) {
    val manager = sessions.manager
    val actions = remember(project) { SessionActions(project, manager, TandemProjectScope.get(project).scope) }
    val workspace = remember(project) { TandemWorkspaceState.getInstance(project) }
    val open by manager.sessions.collectAsState()
    val selectedId by manager.selected.collectAsState()
    val selected = open.firstOrNull { it.id == selectedId }
    val presenters = rememberChatRowsPresenters(open)
    var collapsed by remember { mutableStateOf(workspace.state.sidebarCollapsed) }
    // The sidebar over the chat, in a window too narrow to dock it.
    var drawerOpen by remember { mutableStateOf(false) }
    val toggleFocus = remember { FocusRequester() }
    // The session whose title is being edited in the header; it ends as soon as another session is selected.
    var renaming by remember { mutableStateOf<SessionId?>(null) }
    LaunchedEffect(selectedId) { if (renaming != selectedId) renaming = null }

    BoxWithConstraints(
        Modifier.fillMaxSize().onKeyEvent { event ->
            // Escape that nothing inside took (the title field takes its own) closes the sidebar over the chat.
            if (!drawerOpen || event.type != KeyEventType.KeyDown || event.key != Key.Escape) return@onKeyEvent false
            drawerOpen = false
            toggleFocus.requestFocus()
            true
        },
    ) {
        val wide = maxWidth >= MIN_WIDTH_FOR_SIDEBAR
        val docked = wide && !collapsed
        val drawerShown = drawerOpen && !wide
        LaunchedEffect(wide) { if (wide) drawerOpen = false }
        val sidebar: @Composable (Modifier, Boolean) -> Unit = { modifier, overChat ->
            SessionSidebar(
                manager,
                selectedId,
                actions,
                newSessionDefaults = { workspace.newSessionOptions() },
                onRename = { id ->
                    actions.show(id)
                    renaming = id
                },
                modifier = modifier,
                onPicked = { drawerOpen = false },
                focusFilter = overChat,
            )
        }
        Row(Modifier.fillMaxSize()) {
            if (docked) {
                sidebar(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight(), false)
                Divider(Orientation.Vertical, Modifier.fillMaxHeight())
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SessionHeader(
                    selected,
                    sidebarShown = docked || drawerShown,
                    onToggleSidebar = {
                        if (wide) {
                            collapsed = !collapsed
                            workspace.state.sidebarCollapsed = collapsed
                        } else {
                            drawerOpen = !drawerOpen
                        }
                    },
                    newSessionDefaults = { workspace.newSessionOptions() },
                    onCreateSession = { options ->
                        actions.create(options)
                        drawerOpen = false
                    },
                    renaming = selected != null && renaming == selected.id,
                    onRenamingChange = { editing -> renaming = if (editing) selected?.id else null },
                    onRename = { title -> selected?.let { actions.rename(it.id, title) } },
                    toggleFocus = toggleFocus,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (selected == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Create a session with + or open the sidebar.")
                        }
                    } else {
                        // Composed afresh for each session, so that what is typed in one session's prompt box never shows in another's.
                        key(selected.id) {
                            val rows = remember(selected) { presenters.rows(selected) }
                            ChatPane(project, selected, manager, rows)
                        }
                    }
                    if (drawerShown) {
                        Row(Modifier.matchParentSize()) {
                            Row(
                                Modifier.fillMaxHeight()
                                    .background(JewelTheme.globalColors.panelBackground)
                                    // Takes the pointer where the sidebar has nothing to click, so that the chat under it gets none.
                                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                            ) {
                                sidebar(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight(), true)
                                Divider(Orientation.Vertical, Modifier.fillMaxHeight())
                            }
                            // The rest of the chat, dimmed: a click on it closes the sidebar.
                            val scrim = Color.Black.copy(alpha = if (JewelTheme.isDark) 0.4f else 0.2f)
                            Box(
                                Modifier.weight(1f).fillMaxHeight().background(scrim)
                                    .pointerInput(Unit) { detectTapGestures { drawerOpen = false } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What a new session starts with: the last choices for the project. */
private fun TandemWorkspaceState.newSessionOptions(): NewSessionOptions =
    NewSessionOptions(state.lastModel, state.lastEffort, PermissionMode.fromWire(state.lastPermissionMode))

/** The presenters of the [open] sessions, which stop when their session closes or the tool window's content goes away. */
@Composable
private fun rememberChatRowsPresenters(open: List<SessionHandle>): ChatRowsPresenters {
    val scope = rememberCoroutineScope()
    val presenters = remember(scope) { ChatRowsPresenters(scope) }
    LaunchedEffect(presenters, open) { presenters.retain(open) }
    return presenters
}

/**
 * One [ChatRowsPresenter] per open session, by id, made the first time the session is shown. Each runs in a job of its own in
 * [scope], so that it stops alone when its session closes ([retain]). Used on the EDT only.
 */
private class ChatRowsPresenters(private val scope: CoroutineScope) {
    private class Entry(val handle: SessionHandle, val job: Job, val rows: StateFlow<List<ChatRow>>)

    private val entries = HashMap<SessionId, Entry>()

    /** The rows of [handle]'s chat; a session closed and opened again is a new handle, and gets a new presenter. */
    fun rows(handle: SessionHandle): StateFlow<List<ChatRow>> {
        entries[handle.id]?.takeIf { it.handle === handle }?.let { return it.rows }
        entries.remove(handle.id)?.job?.cancel()
        val job = SupervisorJob(scope.coroutineContext.job)
        val presenter = ChatRowsPresenter(scope + job, handle.state.map { it.toTranscript() })
        entries[handle.id] = Entry(handle, job, presenter.rows)
        return presenter.rows
    }

    /** Stops the presenters of the sessions that are not [open] any more. */
    fun retain(open: List<SessionHandle>) {
        val current = open.associateBy { it.id }
        val gone = entries.filterValues { current[it.handle.id] !== it.handle }
        gone.forEach { (id, entry) ->
            entry.job.cancel()
            entries.remove(id)
        }
    }
}

/**
 * What the tool window asks of the session [manager], from the EDT. Nothing here blocks: the manager's calls that suspend run in the
 * project's [scope], [delete] suspends until the session is gone and the others return at once. A rename or delete that fails is
 * shown as an error notification, in plain text.
 */
internal class SessionActions(private val project: Project, private val manager: SessionManager, private val scope: CoroutineScope) {
    /** The session the user asked to see last, so that one still being opened never takes the selection from a later choice. */
    @Volatile
    private var requested: SessionId? = null

    /** Selects session [id]; one that is not open is opened first, with its history and, if the cap allows, a process. */
    fun show(id: SessionId) {
        requested = id
        if (manager.sessions.value.any { it.id == id }) {
            manager.select(id)
            return
        }
        scope.launch {
            manager.open(id)
            if (requested == id) manager.select(id)
        }
    }

    /** Starts a new session with [options] and selects it. */
    fun create(options: NewSessionOptions) {
        val handle = manager.create(options)
        requested = handle.id
        manager.select(handle.id)
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

    /** Forks session [id]; the fork is selected. */
    fun fork(id: SessionId) {
        requested = null
        scope.launch { manager.fork(id) }
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

@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.TandemProjectScope
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.ui.rows.ProvideRowMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import java.awt.Component
import javax.swing.SwingUtilities

/** The sidebar shows only in a tool window at least this wide. */
private val MIN_WIDTH_FOR_SIDEBAR: Dp = 500.dp

private val SIDEBAR_WIDTH: Dp = 220.dp

/**
 * The Tandem tool window: the session sidebar ([SessionSidebar]) beside the selected session's header ([SessionHeader]) and chat
 * ([ChatPane]), or, with nothing selected, a hint.
 *
 * The sidebar is docked beside the chat unless the user collapsed it (remembered in
 * [TandemWorkspaceState.State.sidebarCollapsed]) or the window is narrower than 500 dp. In a window that wide the header's toggle
 * docks and collapses it. In a narrower one the toggle opens the same sidebar over the chat instead, with the filter focused: it
 * closes when the user picks or creates a session, presses the toggle again, presses Escape (the toggle then has the focus) or clicks
 * the dimmed chat. The header's "+" starts a session at any width. A session picked in the sidebar or created gets the focus in its
 * prompt box.
 *
 * Every open session keeps a [ChatPresenter] and a [ChatUiState] from the first time it is shown until it is closed, so that
 * switching back to it does not parse its transcript again and finds its rows opened as it left them.
 *
 * A card (spec 4.12) takes the focus when it opens, and gives it back to the composer when it closes, only while the chat holds the
 * focus ([focusInChat]): a request that arrives or ends while the user is in the editor, the title field, the filter or the
 * sidebar leaves the focus there, so that an Enter meant for them never answers it.
 */
@Composable
internal fun TandemToolWindow(project: Project, sessions: TandemSessions) {
    val manager = sessions.manager
    val actions = remember(project) { SessionActions(project, manager, TandemProjectScope.get(project).scope) }
    val workspace = remember(project) { TandemWorkspaceState.getInstance(project) }
    val newSession = remember(workspace, manager) { NewSessionChoices(workspace) { manager.knownModels().ifEmpty { workspace.knownModels() } } }
    val open by manager.sessions.collectAsState()
    val selectedId by manager.selected.collectAsState()
    val selected = open.firstOrNull { it.id == selectedId }
    val presenters = rememberChatPresenters(manager, open, sessions.rowOptions)
    val rowOptions by sessions.rowOptions.collectAsState()
    var collapsed by remember { mutableStateOf(workspace.state.sidebarCollapsed) }
    // The sidebar over the chat, in a window too narrow to dock it.
    var drawerOpen by remember { mutableStateOf(false) }
    val toggleFocus = remember { FocusRequester() }
    val rowMenus = remember { RowMenus() }
    // The header's "+" and the sidebar over the chat's "New session": Tab goes from one to the other past the dimmed chat.
    val newSessionFocus = remember { FocusRequester() }
    val drawerEntryFocus = remember { FocusRequester() }
    // The session whose title is being edited in the header; it ends as soon as another session is selected.
    var renaming by remember { mutableStateOf<SessionId?>(null) }
    // The session whose prompt box takes the focus when it shows: the one last picked or created. One still being opened when
    // another is selected (a fork, say) does not take it any more, nor once the user has moved to the filter or the title field.
    var promptFocusFor by remember { mutableStateOf<SessionId?>(null) }
    // Something in the tool window has the focus (rootFocused), something in the selected session's chat has it (chatFocused).
    var rootFocused by remember { mutableStateOf(false) }
    var chatFocused by remember { mutableStateOf(false) }
    val panel = LocalComponent.current
    val chatHoldsFocus: () -> Boolean = remember(panel) { { focusInChat(awtFocusIn(panel), rootFocused, chatFocused) } }
    LaunchedEffect(selectedId) {
        if (renaming != selectedId) renaming = null
        if (promptFocusFor != selectedId) promptFocusFor = null
    }
    val sessionShown: (SessionId) -> Unit = { id ->
        drawerOpen = false
        promptFocusFor = id
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onFocusChanged { rootFocused = it.hasFocus }
            // The context menu key opens the focused row's menu, and does nothing elsewhere.
            .pointerInput(rowMenus) { interceptContextClicks(rowMenus::contextMenuKey) }
            .onKeyEvent { event ->
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
                newSession = newSession,
                onRename = { id ->
                    actions.show(id)
                    renaming = id
                    promptFocusFor = null
                },
                modifier = modifier,
                onPicked = { drawerOpen = false },
                onSessionShown = sessionShown,
                focusFilter = overChat,
                onFilterFocused = { promptFocusFor = null },
                rowMenus = rowMenus,
                newSessionFocus = if (overChat) drawerEntryFocus else null,
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
                    composer = remember(selected, presenters) { selected?.let { presenters.presenter(it).composer } },
                    sidebarShown = docked || drawerShown,
                    onToggleSidebar = {
                        if (wide) {
                            collapsed = !collapsed
                            workspace.state.sidebarCollapsed = collapsed
                        } else {
                            drawerOpen = !drawerOpen
                        }
                    },
                    newSession = newSession,
                    onCreateSession = { options -> sessionShown(actions.create(options)) },
                    renaming = selected != null && renaming == selected.id,
                    onRenamingChange = { editing ->
                        renaming = if (editing) selected?.id else null
                        if (editing) promptFocusFor = null
                    },
                    onRename = { title -> selected?.let { actions.rename(it.id, title) } },
                    toggleFocus = toggleFocus,
                    newSessionFocus = newSessionFocus,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Under the sidebar over the chat, the dimmed chat takes no focus: Tab and Shift+Tab pass it by.
                    val chatFocus = Modifier.focusProperties {
                        onEnter = {
                            if (drawerShown) {
                                val to = if (requestedFocusDirection == FocusDirection.Previous) newSessionFocus else drawerEntryFocus
                                to.requestFocus()
                            }
                        }
                    }.focusGroup()
                    if (selected == null) {
                        Box(Modifier.fillMaxSize().then(chatFocus), contentAlignment = Alignment.Center) {
                            Text("Create a session with + or open the sidebar.")
                        }
                    } else {
                        // Each session's chat is composed in its own key, so that its remembered panel state (the pin's bookkeeping,
                        // focus requesters) never leaks into another session's; the draft lives in the session's ChatUiState.
                        key(selected.id) {
                            val presenter = remember(selected) { presenters.presenter(selected) }
                            val ui = remember(selected.id) { presenters.ui(selected.id) }
                            val sender = remember(selected) { presenters.sender(selected) }
                            // When the session's card closes (answered, cancelled, the turn ended), the focus goes back to the
                            // composer (spec 4.12) while the chat holds it; a card that follows another keeps it on the card.
                            LaunchedEffect(presenter) {
                                var open = false
                                presenter.chat.map { it.card != null }.distinctUntilChanged().collect { now ->
                                    if (open && !now && chatHoldsFocus()) promptFocusFor = selected.id
                                    open = now
                                }
                            }
                            Box(Modifier.onFocusChanged { chatFocused = it.hasFocus }.then(chatFocus)) {
                                ProvideRowMotion(rowOptions) {
                                    ChatPane(
                                        project,
                                        selected,
                                        manager,
                                        workspace,
                                        presenter,
                                        ui,
                                        actions,
                                        sender,
                                        focusPrompt = promptFocusFor == selected.id,
                                        onPromptFocused = { promptFocusFor = null },
                                        onFocusComposer = { promptFocusFor = selected.id },
                                        cardTakesFocus = chatHoldsFocus,
                                    )
                                }
                            }
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

/**
 * Whether the chat holds the focus, for a card that opens or closes (spec 4.12): the AWT focus is in the tool window ([inPanel]),
 * and in it the chat has the focus ([chatFocused]) or nothing has ([rootFocused] false: a card that has just closed took the focus
 * with it). False while the user is in the editor or another tool window, or on the header or the sidebar.
 */
internal fun focusInChat(inPanel: Boolean, rootFocused: Boolean, chatFocused: Boolean): Boolean =
    inPanel && (chatFocused || !rootFocused)

/**
 * Whether the AWT focus is in [panel], or returns there when its window is next focused ([java.awt.Window.getMostRecentFocusOwner]),
 * so that a window in the background still counts.
 */
private fun awtFocusIn(panel: Component): Boolean {
    val owner = SwingUtilities.getWindowAncestor(panel)?.mostRecentFocusOwner ?: return false
    return SwingUtilities.isDescendingFrom(owner, panel)
}

/**
 * The models the CLI offers, as the selected session's process listed them in its `initialize` response, else another open
 * session's: every session runs the same `claude`. None until a process has answered; the popup then offers the list a session
 * reported last, in this IDE run or an earlier one ([TandemWorkspaceState.knownModels]).
 */
private fun SessionManager.knownModels(): List<ModelOption> {
    val open = sessions.value
    val first = open.firstOrNull { it.id == selected.value }
    return (listOfNotNull(first) + open).firstNotNullOfOrNull { handle -> handle.state.value.models.ifEmpty { null } }.orEmpty()
}

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import dev.daarkolabs.tandem.chat.Attachments
import dev.daarkolabs.tandem.chat.MENTION_ROWS
import dev.daarkolabs.tandem.chat.MentionPalette
import dev.daarkolabs.tandem.chat.MentionQuery
import dev.daarkolabs.tandem.chat.Modes
import dev.daarkolabs.tandem.chat.PaletteItem
import dev.daarkolabs.tandem.chat.PaletteOutcome
import dev.daarkolabs.tandem.chat.PluginCommand
import dev.daarkolabs.tandem.chat.PluginEntry
import dev.daarkolabs.tandem.chat.ScrollEvent
import dev.daarkolabs.tandem.chat.SlashIntercept
import dev.daarkolabs.tandem.chat.SlashPalette
import dev.daarkolabs.tandem.chat.StopTarget
import dev.daarkolabs.tandem.chat.busyRefusal
import dev.daarkolabs.tandem.chat.committedDraft
import dev.daarkolabs.tandem.chat.composerPlaceholder
import dev.daarkolabs.tandem.chat.displayPath
import dev.daarkolabs.tandem.chat.intercepts
import dev.daarkolabs.tandem.chat.isSlashCommand
import dev.daarkolabs.tandem.chat.mentionPalette
import dev.daarkolabs.tandem.chat.mentionQuery
import dev.daarkolabs.tandem.chat.moved
import dev.daarkolabs.tandem.chat.paletteFor
import dev.daarkolabs.tandem.chat.pick
import dev.daarkolabs.tandem.chat.sendNowTooltip
import dev.daarkolabs.tandem.chat.sendsLater
import dev.daarkolabs.tandem.chat.shellCommand
import dev.daarkolabs.tandem.chat.shownMention
import dev.daarkolabs.tandem.chat.slashIntercept
import dev.daarkolabs.tandem.chat.stopTarget
import dev.daarkolabs.tandem.chat.visibleMention
import dev.daarkolabs.tandem.chat.withCommand
import dev.daarkolabs.tandem.ide.CONNECTORS_URL
import dev.daarkolabs.tandem.ide.FolderCheck
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.ide.checkFolder
import dev.daarkolabs.tandem.ide.context.EditorSelection
import dev.daarkolabs.tandem.ide.context.ProjectFiles
import dev.daarkolabs.tandem.ide.context.Selection
import dev.daarkolabs.tandem.ide.laterOnEdt
import dev.daarkolabs.tandem.session.AddedFolder
import dev.daarkolabs.tandem.session.FolderState
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.actionSystem.provideData
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InlineWarningBanner
import org.jetbrains.jewel.ui.component.LocalMenuController
import org.jetbrains.jewel.ui.component.MenuItemState
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.menuStyle
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The bottom of a session's chat (spec 4.15, polish spec 2.3): the composer, a text area in a filled rounded box with `+` at its left
 * and Send (`↵`), or Stop while a turn runs, at its right, the attached images above them, and below it the footer: the session's
 * permission mode at the start ([ModeControl]), its model and effort ([ModelControl]), each plain text that opens its menu, and the
 * context ring at the end ([ContextRing]). Everything shown comes from [presenter]'s composer model, which changes only when one of
 * its own fields does, never on a streamed delta; the ring alone collects [presenter]'s context, which changes on a `message_start`.
 *
 * The prompt being typed is [ui]'s draft, with [ui]'s attachments, so both survive switching sessions. Images come from a paste, a
 * drop on the box or the `+` menu's file chooser in [project] (polish spec 2.7); one that cannot be attached leaves its notice above
 * the box. Enter sends unless the text is blank and nothing is attached or the last prompt is still being encoded; while Claude works
 * it sends at once, and Ctrl/Cmd+Enter queues for after the turn, when the CLI takes messages while it works, else the notice says
 * why (desktop-parity spec 12.1, 12.2); a busy slash command or draft with images goes `later`. Shift+Enter is a newline; Esc goes
 * to the open card ([onCardEscape]), else stops a running turn. A prompt goes out through [sender], which encodes its images off the
 * EDT; a send makes the list follow its bottom again. Messages that come back without running go back in the composer, ahead of the
 * draft ([restoreReturned]). A pick goes to
 * [manager], which switches a running process at once and starts a stopped session with it; it becomes the project's last pick in
 * [workspace] too, which a new session starts from. A session whose process ended takes prompts too: the next one starts it again.
 * With [focus] the prompt takes the focus, and then [onFocused] is called.
 *
 * A draft that is one slash token opens the slash palette above the box ([SlashPaletteView], MCP spec 3.4): Up and Down move its
 * selection, Enter picks, Tab inserts, Esc closes it until the draft changes, and a pick only executes what `pick` decides. Every
 * send, palette or not, first runs the interception table (`slashIntercept`): `/mcp` opens the MCP panel, `/model` the model menu,
 * `/permissions` and `/mode` the mode menu ([ChatUiState.menuRequest]), `/model <name>` picks that model; each clears the draft,
 * sends nothing and leaves the attachments for the next prompt.
 *
 * Before that table, a draft that is a shell escape (`! command`, MCP spec 12.1) runs in the login shell through [manager] instead of
 * being sent: the draft is cleared, the attachments are left for the next prompt and the list follows its bottom again; while Claude
 * works or another `!` runs it is not run and the draft stays (spec 12.4). While the command runs the composer is busy, and Stop (the
 * button or Esc) stops the command; otherwise it stops the turn ([stopTarget]).
 */
@Composable
internal fun PromptBar(
    project: Project,
    handle: SessionHandle,
    manager: SessionManager,
    workspace: TandemWorkspaceState,
    presenter: ChatPresenter,
    ui: ChatUiState,
    sender: ComposerSender,
    focus: Boolean = false,
    onFocused: () -> Unit = {},
    onCardEscape: () -> Unit = {},
    paneHeight: () -> Dp? = { null },
) {
    val composer by presenter.composer.collectAsState()
    val sending by sender.sending.collectAsState()
    val scope = rememberCoroutineScope()
    // Shift+Tab's mode cycle (desktop-parity spec 14.1): the session's next mode, read and picked under its lock from its own state
    // with the known models the footer reads too, then remembered for new sessions as a menu pick is.
    val currentHandle by rememberUpdatedState(handle)
    val modes = remember(manager, workspace) {
        ComposerModes {
            manager.cyclePermissionMode(currentHandle.id) { state -> Modes.cycleFrom(state, manager.models.value) }
                ?.also { workspace.state.lastPermissionMode = it.wire }
        }
    }
    val focusManager = LocalFocusManager.current
    val scrollToEnd: () -> Unit = {
        scope.launch {
            val last = ui.listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) ui.listState.animateScrollToItem(last)
        }
    }
    val sendNow = { later: Boolean, command: Boolean ->
        if (sendDraft(ui, sender, later, command)) scrollToEnd()
    }
    // The interception table (MCP spec 3.4): Tandem's own commands act here and clear the draft, sending nothing and leaving the
    // attachment strip as it is; anything else is today's send, attachments included, [later] or not (desktop-parity spec 12.1).
    // [command]: the draft is a slash command the CLI runs, which goes without the selection's block (spec 3.5).
    val runIntercept = { intercept: SlashIntercept, later: Boolean, command: Boolean ->
        when (intercept) {
            is SlashIntercept.Send -> sendNow(later, command)
            SlashIntercept.OpenMcp -> ui.openMcpPanel()
            SlashIntercept.PickModel -> ui.menuRequest.value = FooterMenu.MODEL
            SlashIntercept.PickPermissionMode -> ui.menuRequest.value = FooterMenu.MODE
            is SlashIntercept.SetModel -> {
                manager.setModel(handle.id, intercept.model)
                workspace.state.lastModel = intercept.model
            }
        }
        if (intercept !is SlashIntercept.Send) ui.draft.clearText()
    }
    // The send path, Enter or Send, palette or not (a pasted `/mcp` behaves the same): a shell escape runs first (MCP spec 12.1), but
    // not while Claude works or another `!` runs: the draft stays and the notice says why (desktop-parity spec 12.4); else the table
    // runs on the trimmed draft before the attachments are encoded. While busy, the queue key ([queueKey], Ctrl/Cmd+Enter), a slash
    // command and a draft with images go `later` (spec 12.1, D8's recorded outcomes).
    val send: (queueKey: Boolean) -> Unit = { queueKey ->
        val text = ui.draft.text.toString()
        val command = shellCommand(text)
        when {
            command != null && (composer.busy || composer.shellRunning) -> ui.composerNotice.value = busyRefusal(composer, shell = true)
            command != null -> {
                manager.runShell(handle.id, command)
                ui.draft.clearText()
                ui.follow.value = ui.follow.value.on(ScrollEvent.Sent)
                scrollToEnd()
            }
            else -> {
                val slash = isSlashCommand(text, composer)
                val later = sendsLater(slash, ui.attachments.size, composer.busy, queueKey)
                runIntercept(slashIntercept(text.trim(), composer.models), later, slash)
            }
        }
    }
    // An Enter that cannot send now says why (spec 12.2), unless there is nothing to send.
    val refuse = {
        val text = ui.draft.text.toString()
        if (text.isNotBlank() || ui.attachments.isNotEmpty()) {
            busyRefusal(composer, shell = shellCommand(text) != null, cardOpen = composer.cardOpen)?.let { ui.composerNotice.value = it }
        }
    }
    // Messages that came back without running (a withdraw's Edit, Stop, the process's end: spec 12.3, 12.5) go back in the composer
    // once each, ahead of the draft; the session forgets them before their images, decoded off the EDT, are attached.
    LaunchedEffect(handle.id, presenter) {
        restoreReturnedFrom(ui, presenter.composer.map { it.returned }, take = { manager.takeReturned(handle.id, it) }) { i, _, bytes ->
            attach(ui, "Image ${i + 1}", bytes)
        }
    }
    // A shell escape that runs makes the composer busy, as a turn does (MCP spec 12.1).
    val busy = composer.busy || composer.shellRunning
    // The palette (MCP spec 3.4, 4.4, 7): read where it is used, so a key handler always sees the current text's palette, never a
    // frame-old filter. It filters on the committed text only, and shows only while the text area has the focus.
    val inputFocused = remember { mutableStateOf(false) }
    val paletteOf = { focused: Boolean ->
        paletteFor(
            committedText(ui),
            focused,
            ui.paletteDismissedFor.value,
            ui.paletteSelection.value,
            composer.commands,
            composer.slashCommandNames,
            composer.terminalCommands,
            composer.pluginPrefixes,
        )
    }
    val palette = remember(ui, presenter) { derivedStateOf { paletteOf(inputFocused.value) } }
    // Esc keeps the palette closed until the draft changes.
    LaunchedEffect(ui) { snapshotFlow { committedText(ui) }.collect(ui::draftChanged) }
    // The editor's selection (spec 3.5): the project's flow, read off the EDT by the service, mirrored into this session's composer,
    // which shows it as a chip unless `×` dropped it.
    LaunchedEffect(ui, project) { EditorSelection.getInstance(project).current.collect(ui::selectionChanged) }
    // The `@` palette (spec 3.5): the mention under the caret, over the files the last lookup ranked, while the text area has the
    // focus. The lookup runs 100 ms after the mention's text last changed, off the EDT (ProjectFiles reads in a readAction).
    val projectFiles = remember(project) { ProjectFiles.getInstance(project) }
    val mentionOf = { focused: Boolean ->
        val query = visibleMention(committedText(ui), caretOf(ui), ui.mention.dismissedFor.value)
        if (focused) shownMention(query, ui.mention.ranked.value, ui.mention.selection.value) else null
    }
    val mention = remember(ui, presenter) { derivedStateOf { mentionOf(inputFocused.value) } }
    LaunchedEffect(ui, projectFiles) {
        snapshotFlow { mentionQuery(committedText(ui), caretOf(ui))?.text }
            .distinctUntilChanged()
            .collectLatest { text -> if (text != null) ui.mention.ranked.value = lookUpMention(projectFiles, text) }
    }
    val pickMentionAt = { shown: MentionPalette?, index: Int? ->
        if (shown != null && pickMention(ui.draft, index?.let { shown.copy(selected = it) } ?: shown)) ui.mention.selection.value = null
    }
    // A pick's outcome (MCP spec 4.3): the composer only executes it.
    val execute = { outcome: PaletteOutcome ->
        when (outcome) {
            is PaletteOutcome.Intercept -> {
                // A row's own name never reads as text to send; should one, the send path gets that text, not the typed prefix.
                (outcome.intercept as? SlashIntercept.Send)?.let { ui.draft.setTextAndPlaceCursorAtEnd(it.text) }
                runIntercept(outcome.intercept, false, true)
            }
            is PaletteOutcome.Insert -> ui.draft.setTextAndPlaceCursorAtEnd(outcome.text)
            is PaletteOutcome.Send -> {
                ui.draft.setTextAndPlaceCursorAtEnd(outcome.text)
                send(outcome.later)
            }
            PaletteOutcome.Ignored -> Unit
        }
    }
    // A key picks from the palette on screen; a click on a row picks that row, from the palette its click was made on even should
    // the text area have lost the focus to the press.
    val pickFrom = { shown: SlashPalette?, item: PaletteItem?, insert: Boolean ->
        if (shown != null) {
            val index = item?.let(shown.items::indexOf)?.takeIf { it >= 0 } ?: shown.selected
            val outcome = pick(shown.copy(selected = index), insert, busy = composer.busy || sending, composer.models, midTurn = composer.midTurnSends)
            // A busy pick that does nothing says why (spec 12.2): the CLI is still starting, or takes no message while it works.
            if (outcome == PaletteOutcome.Ignored && !insert && composer.busy) busyRefusal(composer)?.let { ui.composerNotice.value = it }
            execute(outcome)
        }
    }
    // The ring's popover (desktop-parity spec 3.1): one controller per open popover, over the session's flows mapped off the EDT, in
    // the popover's own scope (the ring cancels it on close). Its two buttons send `/context` and `/usage` as prompts; the draft and
    // the attachments stay as they are.
    val openPopover = remember(handle, manager, presenter, project) {
        { popoverScope: CoroutineScope ->
            contextPopoverController(
                popoverScope, handle.id, handle.state, presenter.context, handle.status, manager, System.getProperty("user.home"), project.basePath,
            )
        }
    }
    // The popover's buttons are disabled while a turn runs and while a captured prompt is still being encoded, as the composer is
    // busy then: a `/context` sent meanwhile would reach the CLI ahead of that prompt. Combined off the EDT, in a scope of its own
    // under the bar's, ended when the bar leaves or shows another session, so one collector of each flow runs at a time.
    val busyScope = remember(presenter, sender) {
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
    }
    DisposableEffect(busyScope) { onDispose { busyScope.cancel() } }
    val popoverBusy = remember(busyScope) {
        combine(presenter.busy, sender.sending) { turn, send -> turn || send }
            .stateIn(busyScope, SharingStarted.Eagerly, presenter.busy.value || sender.sending.value)
    }
    val sendFromPopover = { text: String ->
        manager.send(handle.id, text)
        ui.follow.value = ui.follow.value.on(ScrollEvent.Sent)
        scrollToEnd()
    }
    // At most 60 % of the tool window's height (spec 3.1); before the pane is measured, a height that fits any tool window.
    // Providers, read only by the open popover: a resize recomposes the popover, not the prompt bar.
    val popover = remember(openPopover, paneHeight) {
        ContextPopoverHost(
            open = openPopover,
            busy = popoverBusy,
            maxHeight = { paneHeight()?.let { it * POPOVER_HEIGHT_SHARE } ?: POPOVER_FALLBACK_HEIGHT },
            onDetailed = { sendFromPopover("/context") },
            onUsage = { sendFromPopover("/usage") },
        )
    }
    val density = LocalDensity.current
    val columnWidth = remember { mutableStateOf<Dp?>(null) }
    val columnWidthOf: () -> Dp? = remember(columnWidth) { { columnWidth.value } }
    val attachFiles: (List<Path>) -> Unit = { paths ->
        if (paths.isNotEmpty()) scope.launch { ComposerImages.readFiles(paths).forEach { (name, bytes) -> attach(ui, name, bytes) } }
    }
    // `Tandem.AddFilesOrPhotos` (spec 17.4): the `+` row's own path, offered to the chat pane while this bar is composed.
    val currentAttachFiles by rememberUpdatedState(attachFiles)
    DisposableEffect(handle.id) {
        ui.attach = ComposerAttach { ComposerImages.chooseImagesLater(project, onChosen = { currentAttachFiles(it) }) }
        onDispose { ui.attach = null }
    }
    // The `+` menu's Connectors page (spec 17.2): the MCP panel's controller, built as McpPanelHost builds it but started on open.
    val sessions = remember(project) { project.service<TandemSessions>() }
    val opener = sessions.browserOpener
    // `Add folder` (spec 3.4): the chooser, modal, in an EDT event of its own after the menu's click, never in the click or in [scope];
    // only the chosen path enters a coroutine, for the checks on IO and the add. [closed] runs once the chooser has closed, chosen or not.
    val addFolder: (closed: () -> Unit) -> Unit = { closed ->
        val later = laterOnEdt(project)
        sessions.folderChooser.chooseLater(
            project,
            onChosen = { chosen ->
                scope.launch {
                    val check = withContext(Dispatchers.IO) {
                        checkFolder(chosen, sessions.projectDirectory, handle.folders.value.map { folder -> folder.path })
                    }
                    when (check) {
                        is FolderCheck.Refused -> ui.composerNotice.value = check.message
                        is FolderCheck.Add -> manager.addFolder(handle.id, check.realPath)
                    }
                }
            },
            launcher = { task ->
                later(
                    Runnable {
                        try {
                            task.run()
                        } finally {
                            closed()
                        }
                    },
                )
            },
        )
    }
    val connectorController: (CoroutineScope) -> McpPanelController = remember(handle, manager, opener) {
        { pageScope ->
            connectorPageController(
                handle.id, handle.mcpServers, handle.mcpAuth, handle.mcpLoginHinted, handle.status, manager, opener, pageScope,
                endReason = { endNotice(handle.state.value) },
            )
        }
    }
    // In the session's scope, which only its close cancels: the menu's own scope ends with the menu, before the page opens.
    val browseConnectors: () -> Unit = remember(handle, opener) { { handle.scope.launch { opener.open(CONNECTORS_URL) } } }
    // The box and the footer share the chat column's width less 16 dp each side, so the footer measures from the box's edges.
    Column(Modifier.chatColumn().onSizeChanged { columnWidth.value = with(density) { it.width.toDp() } }.padding(horizontal = 16.dp)) {
        ui.composerNotice.value?.let { notice ->
            InlineWarningBanner(
                text = notice,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                iconActions = { iconAction(AllIconsKeys.General.Close, "Close", null) { ui.composerNotice.value = null } },
            )
        }
        PromptBox(
            ui,
            busy,
            sending,
            composer.cardOpen,
            midTurn = composer.midTurnSends,
            // A `!` alone makes the composer busy, but its prompts wait behind the escape's frames (spec 12.4): Enter sends.
            shellOnly = !composer.busy && composer.shellRunning,
            placeholder = composerPlaceholder(composer, SystemInfo.isMac),
            focus = focus,
            onFocused,
            inputFocused = inputFocused,
            palette = { palette.value },
            mention = { mention.value },
            onMentionPick = { index -> pickMentionAt(mentionOf(true), index) },
            interceptable = { intercepts(ui.draft.text.toString(), composer.models) },
            onPalettePick = { item -> pickFrom(paletteOf(true), item, false) },
            modes = modes,
            plugins = composer.plugins,
            pluginsKnown = composer.pluginsKnown,
            columnWidth = columnWidthOf,
            onAdd = { ComposerImages.chooseImagesLater(project, onChosen = attachFiles) },
            onAddFolder = addFolder,
            folders = handle.folders,
            onRemoveFolder = { path -> manager.removeFolder(handle.id, path) },
            onDrop = attachFiles,
            connectorController = connectorController,
            onBrowseConnectors = browseConnectors,
            onAction = { action ->
                when (action) {
                    ComposerAction.Send -> send(false)
                    ComposerAction.QueueLater -> send(true)
                    ComposerAction.BusyRefused -> refuse()
                    ComposerAction.Stop -> when (stopTarget(composer.shellRunning)) {
                        StopTarget.Shell -> manager.stopShell(handle.id)
                        StopTarget.Turn -> manager.interrupt(handle.id)
                    }
                    ComposerAction.CardEscape -> onCardEscape()
                    ComposerAction.PasteImage -> scope.launch { paste(ui) }
                    ComposerAction.RemoveLastImage -> ui.attachments.removeLastOrNull()
                    ComposerAction.ClosePanel -> ui.mcpPanelOpen.value = false
                    // The `@` palette's keys first, when it is the one open: Enter and Tab both insert the mention (spec 3.5).
                    ComposerAction.PaletteUp -> mention.value?.let { ui.mention.selection.value = it.query to it.moved(-1).selected }
                        ?: palette.value?.let { ui.paletteSelection.value = it.query to it.moved(-1).selected }
                    ComposerAction.PaletteDown -> mention.value?.let { ui.mention.selection.value = it.query to it.moved(1).selected }
                        ?: palette.value?.let { ui.paletteSelection.value = it.query to it.moved(1).selected }
                    ComposerAction.PalettePick -> mention.value?.let { pickMentionAt(it, null) } ?: pickFrom(palette.value, null, false)
                    ComposerAction.PaletteInsert -> mention.value?.let { pickMentionAt(it, null) } ?: pickFrom(palette.value, null, true)
                    ComposerAction.PaletteClose -> if (mention.value != null) {
                        ui.mention.dismissedFor.value = committedText(ui)
                    } else {
                        palette.value?.let { ui.paletteDismissedFor.value = it.query }
                    }
                    ComposerAction.FocusNext -> focusManager.moveFocus(FocusDirection.Next)
                }
            },
        )
        // Spec 2.3: the pills' edges 9 dp inside the box at the start and 10 dp at the end, 10 dp under it, 12 dp above the bottom.
        Row(
            Modifier.fillMaxWidth().padding(start = 9.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeControl(
                composer,
                onPick = { mode ->
                    manager.setPermissionMode(handle.id, mode)
                    workspace.state.lastPermissionMode = mode.wire
                },
                menuRequest = ui.menuRequest,
                openMenus = ui.openMenus,
            )
            // Mode at the start, model and effort at the end; on a narrow pane the model label ellipsises, nothing wraps.
            Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterEnd) {
                ModelControl(
                    composer,
                    onPickModel = { model ->
                        manager.setModel(handle.id, model)
                        workspace.state.lastModel = model
                    },
                    onPickEffort = { level ->
                        manager.setEffort(handle.id, level)
                        workspace.state.lastEffort = level
                    },
                    menuRequest = ui.menuRequest,
                    openMenus = ui.openMenus,
                )
            }
            CompositionLocalProvider(LocalChatColumnWidth provides columnWidthOf) { ContextRing(presenter.context, popover, ui.openMenus) }
        }
    }
}

/**
 * The text area, `+` and Send or Stop in one filled rounded box, as wide as the footer under it, with the attached images above them
 * ([AttachmentStrip]): Stop while [busy], with Send left of it while the CLI takes messages while it works ([midTurn]) and the draft
 * can be sent. Keys go through [composerKey] with [busy] or [sending] (a prompt still being encoded), [cardOpen], [midTurn],
 * [shellOnly] (only a `!` runs), whether the slash palette is open ([palette], read at each key, so never a frame-old one) and whether the draft is one
 * of Tandem's own commands ([interceptable], which Enter runs even while busy), and Send is disabled
 * while [sending]; [onAction] does what they return. `+` opens the paged menu ([AddButton], [PlusMenu]): `Add files or photos` calls
 * [onAdd], `Slash commands` puts `/` in an empty draft and focuses the text area, `Connectors` turns to its page, whose controller
 * comes from [connectorController] and whose `Browse connectors` calls [onBrowseConnectors] (spec 17.2), and the Plugins
 * pages list [plugins] (whether the list is known yet is [pluginsKnown]); the menu's width comes from the chat column's width
 * ([columnWidth], [plusMenuWidth]) and its height from the room above the box. Files dropped on the box go to [onDrop], and the box draws
 * its focused border while a drag of files hovers it. With [focus] the text area takes the focus, and then [onFocused] is called;
 * whether it has the focus is [inputFocused]. The palette ([SlashPaletteView]) opens above the box at its left edge, never wider than
 * it, and a click on one of its rows goes to [onPalettePick]; while it is open on a lone `/` the text area reads `/Type to filter`
 * ([FilterHint]); empty, it reads [placeholder]. It takes those fields, not the whole composer model, so that a change to another one
 * leaves it alone.
 */
@Composable
private fun PromptBox(
    ui: ChatUiState,
    busy: Boolean,
    sending: Boolean,
    cardOpen: Boolean,
    midTurn: Boolean,
    shellOnly: Boolean,
    placeholder: String,
    focus: Boolean,
    onFocused: () -> Unit,
    inputFocused: MutableState<Boolean>,
    palette: () -> SlashPalette?,
    mention: () -> MentionPalette?,
    onMentionPick: (Int) -> Unit,
    interceptable: () -> Boolean,
    onPalettePick: (PaletteItem) -> Unit,
    modes: ComposerModes,
    plugins: ImmutableList<PluginEntry>,
    pluginsKnown: Boolean,
    columnWidth: () -> Dp?,
    onAdd: () -> Unit,
    onAddFolder: (closed: () -> Unit) -> Unit,
    folders: StateFlow<List<AddedFolder>>,
    onRemoveFolder: (String) -> Unit,
    onDrop: (List<Path>) -> Unit,
    onAction: (ComposerAction) -> Unit,
    connectorController: (CoroutineScope) -> McpPanelController,
    onBrowseConnectors: () -> Unit,
) {
    val inputFocus = remember { FocusRequester() }
    val focused = inputFocused.value
    var dragging by remember { mutableStateOf(false) }
    // The box's width and its top in the window, for the palette above it (MCP spec 3.4: a position provider cannot size a popup).
    var boxWidth by remember { mutableIntStateOf(0) }
    var boxTop by remember { mutableIntStateOf(0) }
    // Asks the text area for the focus once the `+` menu, which held it, has gone.
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocus) { if (refocus > 0) inputFocus.requestFocus() }
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnAdd by rememberUpdatedState(onAdd)
    val currentOnAddFolder by rememberUpdatedState(onAddFolder)
    val currentOnBrowse by rememberUpdatedState(onBrowseConnectors)
    val currentOnAction by rememberUpdatedState(onAction)
    // Derived, so that the box recomposes when the hint comes or goes, not at every keystroke.
    val currentPalette by rememberUpdatedState(palette)
    val filterHint by remember(ui) { derivedStateOf { filterHintShown(ui.draft.text, currentPalette()) } }
    // Derived, so that the busy Send comes and goes with the draft, not at every keystroke.
    val currentSending by rememberUpdatedState(sending)
    val sendable by remember(ui) { derivedStateOf { canSend(ui.draft.text.toString(), ui.attachments.size, currentSending) } }
    val imagePaste = remember { ImagePasteProvider { currentOnAction(ComposerAction.PasteImage) } }
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                dragging = true
            }

            override fun onExited(event: DragAndDropEvent) {
                dragging = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                dragging = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                // The file list is readable only while the drop runs; the files themselves are read on IO afterwards.
                val paths = ComposerImages.droppedPaths(event.awtTransferable)
                currentOnDrop(paths)
                return paths.isNotEmpty()
            }
        }
    }
    LaunchedEffect(focus) {
        if (focus) {
            inputFocus.requestFocus()
            onFocused()
        }
    }
    val colors = JewelTheme.globalColors
    val shape = RoundedCornerShape(12.dp)
    // Spec 2.3: a filled box, 44 dp tall empty, radius 12 dp. 4 dp above, so that with the list's 12 dp bottom padding the last
    // row sits 16 dp above the box (spec 4.1).
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp)
            .onSizeChanged { boxWidth = it.width }
            .onGloballyPositioned { boxTop = it.positionInWindow().y.roundToInt() }
            .heightIn(min = 44.dp)
            .background(chatPalette().bubbleFill, shape)
            .border(1.dp, if (focused || dragging) colors.borders.focused else colors.borders.normal, shape)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    runCatching { event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)
                },
                target = dropTarget,
            ),
    ) {
        val density = LocalDensity.current
        // The text's ink starts about 42 dp inside the box (desktop-02): 8 dp, the 24 dp `+`, 5 dp, then the undecorated TextArea's
        // own content padding (5 dp across, 4 dp down); 8 + 4 above and below the text, so 184 dp holds 8 lines of 20 dp. The `+`
        // and the send glyph stay on the last line as the text grows.
        ComposerBoxLayout(
            start = 8.dp,
            add = {
                AddButton(
                    Modifier.padding(end = 5.dp, bottom = 10.dp),
                    plugins,
                    pluginsKnown,
                    width = plusMenuWidth(columnWidth() ?: UNMEASURED_COLUMN),
                    // The room above the box, as the palette measures it (MCP spec 14); none before the box is placed.
                    maxHeight = with(density) { (boxTop - 8.dp.roundToPx()).takeIf { it > 0 }?.toDp() },
                    // Keyed on the state alone: [onAdd] is a new lambda at every recomposition of PromptBar, and is read when picked.
                    actions = remember(ui) {
                        PlusMenuActions(
                            onAddFiles = { currentOnAdd() },
                            // The text area takes the focus once the chooser has closed (spec 3.5).
                            onAddFolder = { currentOnAddFolder { refocus++ } },
                            onSlashCommands = {
                                ui.openPalette()
                                refocus++
                            },
                            onBrowseConnectors = { currentOnBrowse() },
                            // The MCP panel, opened as a user action, after the menu closed (spec 17.2).
                            onManageConnectors = { menu -> menu.openMcpFromMenu(ui) },
                            onCommand = { command ->
                                ui.draft.setTextAndPlaceCursorAtEnd(withCommand(ui.draft.text.toString(), command.command))
                                refocus++
                            },
                        )
                    },
                    openMenus = ui.openMenus,
                    connectorController = connectorController,
                )
            },
            // The session's added folders, at the top of the box, above the images (spec 3.4), then the attached images,
            // across the box's inner width (polish spec 2.7).
            strips = {
                Column(Modifier.fillMaxWidth()) {
                    FolderStrip(folders, onRemoveFolder)
                    ui.chipSelection?.let { SelectionChip(it, ui::removeSelection) }
                    if (ui.attachments.isNotEmpty()) AttachmentStrip(ui.attachments)
                }
            },
            input = {
                Row(Modifier.heightIn(max = 184.dp).padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
                    // The text area, and over it `Type to filter` after a lone `/` while the palette is open (MCP spec 14): its own
                    // placeholder shows only while it is empty.
                    Box(Modifier.weight(1f)) {
                        TextArea(
                            ui.draft,
                            Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 184.dp).padding(vertical = 8.dp)
                                .focusRequester(inputFocus)
                                .onFocusChanged { inputFocused.value = it.hasFocus }
                                // In the IDE, Ctrl/Cmd+V is its Paste action, which takes the key before the handler below sees it:
                                // while the clipboard holds images, the focused composer's paste provider takes the paste from the
                                // Compose text paste (polish spec 2.7). Asked for only by a paste, so the clipboard's flavours are not
                                // queried on every action update.
                                .provideData {
                                    lazy(PlatformDataKeys.PASTE_PROVIDER.name) {
                                        imagePaste.takeIf { ComposerImages.clipboardHasImage { CopyPasteManager.getInstance().contents?.transferDataFlavors.orEmpty().toList() } }
                                    }
                                    // Shift+Tab's mode cycle, only while no footer menu, popover or `+` menu is open (desktop-parity
                                    // spec 7, 14.1).
                                    composerModesFor(modes, ui.openMenus.intValue)?.let { set(COMPOSER_MODES.name, it) }
                                }
                                .onPreviewKeyEvent { event ->
                                    composerKeyEvent(event, busy || sending, cardOpen, midTurn, shellOnly, ui, palette() != null || mention() != null, interceptable, onAction)
                                },
                            lineLimits = TextFieldLineLimits.MultiLine(1, 8),
                            undecorated = true,
                            placeholder = { Text(placeholder, color = colors.text.info) },
                        )
                        if (filterHint) FilterHint(top = 8.dp)
                    }
                    // Centred on the last line: 12 dp padding + half a 20 dp line − half the 24 dp button.
                    val buttonModifier = Modifier.padding(start = 8.dp, bottom = 10.dp).size(24.dp)
                    if (busy) {
                        // Send now beside Stop while Claude works (desktop-parity spec 12.1), once the draft can be sent; a click acts
                        // as Enter.
                        if (midTurn && !cardOpen && sendable) {
                            WithTooltip(sendNowTooltip(SystemInfo.isMac)) {
                                IconButton(onClick = { onAction(ComposerAction.Send) }, modifier = buttonModifier.semantics { contentDescription = "Send" }) {
                                    Text("↵", style = JewelTheme.editorTextStyle, color = colors.text.info)
                                }
                            }
                        }
                        IconButton(
                            onClick = { onAction(ComposerAction.Stop) },
                            modifier = buttonModifier.semantics { contentDescription = "Stop" },
                        ) {
                            Icon(AllIconsKeys.Actions.Suspend, contentDescription = null, tint = colors.text.info)
                        }
                    } else {
                        // Disabled, and dimmed, while the last prompt is still being encoded: the composer counts as busy (polish
                        // spec 2.7). sendDraft checks the sender's own flag again, so a click that races the encode still sends nothing.
                        IconButton(
                            onClick = { onAction(ComposerAction.Send) },
                            enabled = !sending,
                            modifier = buttonModifier.semantics { contentDescription = "Send" },
                        ) {
                            Text("↵", style = JewelTheme.editorTextStyle, color = if (sending) colors.text.disabled else colors.text.info)
                        }
                    }
                }
            },
        )
        // Placed in the box, so that the popup is anchored to the box's own edges. The `@` palette, when open, in place of the slash one.
        val shownMention = mention()
        val shown = palette()
        if (shownMention != null && boxWidth > 0) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            MentionPaletteView(shownMention, boxWidthPx = boxWidth, maxHeightPx = boxTop - gap) { index ->
                onMentionPick(index)
                inputFocus.requestFocus()
            }
        } else if (shown != null && boxWidth > 0) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            SlashPaletteView(shown, boxWidthPx = boxWidth, maxHeightPx = boxTop - gap) { item ->
                onPalettePick(item)
                inputFocus.requestFocus()
            }
        }
    }
}

/**
 * The Paste action's provider while the clipboard holds images and the composer has the focus: it pastes them ([onPaste]) instead of
 * text. The IDE binds Ctrl/Cmd+V to its Paste action, which asks the focused component for a [PasteProvider] before the key reaches
 * Compose; with no images on the clipboard the composer offers none, and the Compose text paste runs as before.
 */
private class ImagePasteProvider(private val onPaste: () -> Unit) : PasteProvider {
    override fun performPaste(dataContext: DataContext) {
        onPaste()
    }

    override fun isPastePossible(dataContext: DataContext): Boolean = true

    override fun isPasteEnabled(dataContext: DataContext): Boolean = true

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Hands [event] to [composerKey] and runs what it returns, else to [composerFallback]: a newline in [ui]'s draft ([insertNewline]),
 * which the text area would not type, or nothing at all (spec 4.15). Every other key goes to the text area. With the slash palette
 * open ([paletteOpen]) its keys come first (MCP spec 3.4); a pick that does nothing still takes the key, so a busy Enter types no
 * newline. With the palette closed, a busy Enter on one of Tandem's own commands ([interceptable]) still runs it. Ctrl/Cmd+V asks
 * the clipboard for its flavours only (polish spec 2.7): the data is read later, off the EDT.
 */
private fun composerKeyEvent(
    event: KeyEvent,
    busy: Boolean,
    cardOpen: Boolean,
    midTurn: Boolean,
    shellOnly: Boolean,
    ui: ChatUiState,
    paletteOpen: Boolean,
    interceptable: () -> Boolean,
    onAction: (ComposerAction) -> Unit,
): Boolean {
    val draft = ui.draft
    val ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed
    val action = composerKey(
        event.key,
        event.type,
        event.isShiftPressed,
        event.isAltPressed,
        ctrlOrMeta,
        busy = busy,
        cardOpen = cardOpen,
        midTurn = midTurn,
        shellOnly = shellOnly,
        draftEmpty = draft.text.isEmpty(),
        hasAttachments = ui.attachments.isNotEmpty(),
        paletteOpen = paletteOpen,
        panelOpen = ui.mcpPanelOpen.value,
        interceptable = interceptable,
        clipboardHasImage = { ComposerImages.clipboardHasImage { CopyPasteManager.getInstance().contents?.transferDataFlavors.orEmpty().toList() } },
    )
    if (action != null) {
        onAction(action)
        return true
    }
    val fallback = composerFallback(
        event.key,
        event.type,
        event.isShiftPressed,
        event.isAltPressed,
        ctrlOrMeta,
        busy = busy,
        cardOpen = cardOpen,
    )
    return when (fallback) {
        ComposerFallback.Newline -> {
            insertNewline(draft)
            true
        }
        ComposerFallback.Swallow -> true
        null -> false
    }
}

/**
 * The `+` at the start of the composer box (polish spec 2.3, 2.7; desktop-parity spec 3.3, 3.5, 17.1, 17.3): a 24 dp icon button in
 * the secondary tier, described as `Add`, whose paged menu ([PlusMenu]) opens above it, left-aligned, on a click, Enter or Space. A
 * click on the `+` while the menu is open closes it, as a footer control's does. The menu's keys beyond Jewel's own (← back one page)
 * come from AWT's key dispatch ([PagedMenuKeys], [plusMenuTurn]). A close by a key (Esc, or Enter on a row that does not hand the
 * focus on) gives the focus back to the `+`; `Slash commands`, a plugin's command and `Manage connectors` hand it to the text area
 * or the MCP panel instead. While open the menu counts in [openMenus]; on its Connectors page it holds one controller from
 * [connectorController] ([rememberConnectorsPage]). The menu is [width] wide at most and at least ([plusMenuWidth]) and at most [maxHeight] high.
 *
 * [modifier] (the button's padding in the row) goes on an outer box; the menu is placed in the inner, unpadded one, so that
 * [AboveAnchor] measures from the button's own edges, as `FooterControl` places its menu.
 */
@Composable
private fun AddButton(
    modifier: Modifier,
    plugins: ImmutableList<PluginEntry>,
    pluginsKnown: Boolean,
    width: Pair<Dp, Dp>,
    maxHeight: Dp?,
    actions: PlusMenuActions,
    openMenus: MutableIntState,
    connectorController: (CoroutineScope) -> McpPanelController,
) {
    val state = remember { PagedMenuState() }
    // Counted among the composer's open menus while open, so the composer's action keys stand down (spec 7, 17.4).
    val open = state.open
    CountOpenMenu(open, openMenus)
    // Read as the menu opens, so a rebinding shows at the next opening and an unbound action shows nothing (spec 17.4).
    val addFilesShortcut = remember(open) { if (open) addFilesKeybinding() else null }
    // One controller per open Connectors page, closed when the page leaves or the menu closes (spec 17.2).
    val connectors = if (open && state.page == PLUS_CONNECTORS) rememberConnectorsPage(connectorController) else null
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val addFocus = remember { FocusRequester() }
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocus) { if (refocus > 0) addFocus.requestFocus() }
    // Set by a row that hands the focus on (the text area, the MCP panel), so its close does not bring it back to the `+`.
    val handsFocus = remember { mutableStateOf(false) }
    PagedMenuKeys(state, LocalComponent.current, ::plusMenuTurn)
    val handing = remember(actions) {
        PlusMenuActions(
            onAddFiles = actions.onAddFiles,
            onAddFolder = {
                handsFocus.value = true
                actions.onAddFolder()
            },
            onSlashCommands = {
                handsFocus.value = true
                actions.onSlashCommands()
            },
            onBrowseConnectors = actions.onBrowseConnectors,
            onManageConnectors = { menu ->
                handsFocus.value = true
                actions.onManageConnectors(menu)
            },
            onCommand = { command ->
                handsFocus.value = true
                actions.onCommand(command)
            },
        )
    }
    Box(modifier) {
        Box {
            IconButton(
                onClick = { if (state.open) state.close() else state.openAt(PLUS_MAIN) },
                modifier = Modifier.size(24.dp)
                    .focusRequester(addFocus)
                    .onPreviewKeyEvent { event ->
                        when {
                            !state.open && event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar) -> {
                                handsFocus.value = false
                                state.openAt(PLUS_MAIN)
                                true
                            }
                            state.open && event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                                state.close()
                                refocus++
                                true
                            }
                            else -> false
                        }
                    }
                    .hoverable(source)
                    .semantics { contentDescription = "Add" },
                interactionSource = source,
            ) {
                Icon(AllIconsKeys.General.Add, contentDescription = null, tint = chatPalette().secondary)
            }
            if (state.open) {
                PlusMenu(
                    state,
                    plugins,
                    pluginsKnown,
                    width,
                    maxHeight,
                    gap,
                    onDismissRequest = { mode ->
                        // A page turn holds the menu open through the close request after its click; a click on the `+` itself
                        // closes the menu through the button (its click toggles it).
                        val closed = state.dismiss(mode, hovered)
                        if (closed) {
                            if (mode == InputMode.Keyboard && !handsFocus.value) refocus++
                            handsFocus.value = false
                        }
                        closed
                    },
                    actions = handing,
                    connectors = connectors,
                    addFilesShortcut = addFilesShortcut,
                )
            }
        }
    }
}

/**
 * What the `+` menu's rows do: [onAddFiles] the image chooser (after the menu closed), [onAddFolder] the folder chooser (after the
 * menu closed; the text area takes the focus once it has closed), [onSlashCommands] `/` and the palette,
 * [onBrowseConnectors] claude.ai's connectors page in the browser (after the menu closed), [onManageConnectors] the MCP panel (through
 * [openMcpFromMenu], which closes the menu first), and [onCommand] a plugin's command into the draft.
 */
internal class PlusMenuActions(
    val onAddFiles: () -> Unit,
    val onAddFolder: () -> Unit,
    val onSlashCommands: () -> Unit,
    val onBrowseConnectors: () -> Unit,
    val onManageConnectors: (PagedMenuState) -> Unit,
    val onCommand: (PluginCommand) -> Unit,
)

/**
 * The `+` menu (desktop-parity spec 3.3, 17.1, 17.3), [gapPx] above its anchor and left-aligned, on [state]'s page, every page
 * between [width]'s bounds and at most [maxHeight] high. Each page is one passive item ([pagedRows], spec open item 6): its `‹` row
 * and its closing line stay put while its rows scroll ([ScrolledRows]) under the cap, the keyboard's selection
 * brought into view. Page [PLUS_MAIN]: `Add files or photos` (with [addFilesShortcut], spec 17.1), `Add folder`, `Slash commands`, `Connectors ›`
 * and `Plugins ›`, each with its icon. Page [PLUS_CONNECTORS]: [connectors] ([connectorsPage]).
 * Page [PLUS_PLUGINS]: `‹ Plugins`, one row per plugin of [plugins] (or the line that says why there is none, [pluginsKnown]), the
 * `/plugin` hint. Page [PLUS_FIRST_PLUGIN] + i: `‹ <label>`, then its skills and its commands under their headings; a pick closes the
 * menu and calls [PlusMenuActions.onCommand]. Names and descriptions are config text: plain [Text].
 */
@Composable
internal fun PlusMenu(
    state: PagedMenuState,
    plugins: ImmutableList<PluginEntry>,
    pluginsKnown: Boolean,
    width: Pair<Dp, Dp>,
    maxHeight: Dp?,
    gapPx: Int,
    onDismissRequest: (InputMode) -> Boolean,
    actions: PlusMenuActions,
    connectors: ConnectorsPage? = null,
    addFilesShortcut: Set<String>? = null,
) {
    val palette = chatPalette()
    val small = smallTextStyle()
    val page = state.page
    val plugin = if (page >= PLUS_FIRST_PLUGIN) plugins.getOrNull(page - PLUS_FIRST_PLUGIN) else null
    // The page's own column is capped at the menu's cap less the menu's padding and border, so that the menu never scrolls itself.
    val menu = JewelTheme.menuStyle.metrics
    val pageCap = maxHeight?.let {
        (it - menu.contentPadding.calculateTopPadding() - menu.contentPadding.calculateBottomPadding() - menu.borderWidth * 2).coerceAtLeast(0.dp)
    }
    PopupMenu(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = remember(gapPx) { AboveAnchor(alignEnd = false, gapPx = gapPx) },
        modifier = Modifier.widthIn(min = width.first, max = width.second)
            .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
            .testTag("plus-menu"),
    ) {
        when {
            plugin != null -> pluginPage(state, page, pageCap, plugin, palette, small, actions)
            // A plugin's page whose plugin left the list (a new init) shows the Plugins page.
            page == PLUS_PLUGINS || page >= PLUS_FIRST_PLUGIN -> pluginsPage(state, page, pageCap, plugins, pluginsKnown, palette, small)
            page == PLUS_CONNECTORS && connectors != null -> connectorsPage(
                connectors.model,
                pageCap,
                connectors.noteError,
                onBack = { state.show(PLUS_MAIN) },
                onBrowse = {
                    state.close()
                    actions.onBrowseConnectors()
                },
                onManage = { actions.onManageConnectors(state) },
                onToggle = { row -> onConnectorRow(row, state, connectors.run) },
            )
            else -> mainPage(state, page, pageCap, palette, actions, addFilesShortcut, plusShortcutShown(width.second))
        }
    }
}

/**
 * A `+` menu page (spec 3.3 Size, open item 6) as one passive item at most [cap] high: [header] (the `‹` row) and a separator above,
 * [rows] in a vertical scroll ([ScrolledRows]) that takes the room left, and [footer] (the closing line) under a separator below. Jewel's
 * own menu scrolls every item in one column, so a `‹` row given to it as an item would scroll away with the rows; here the rows are
 * Jewel's own menu items ([PageRow]), which the menu's Up and Down reach through the focus order, and the focused one is brought
 * into view inside the inner scroll. The scroll starts at the top of each [page].
 */
internal fun MenuScope.pagedRows(
    page: Int,
    cap: Dp?,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    passiveItem {
        key(page) {
            Column(if (cap != null) Modifier.heightIn(max = cap) else Modifier) {
                if (header != null) {
                    header()
                    PageSeparator()
                }
                ScrolledRows(Modifier.weight(1f, fill = false), rows)
                if (footer != null) {
                    PageSeparator()
                    footer()
                }
            }
        }
    }
}

/**
 * A page's rows in a vertical scroll with Jewel's scrollbar over their end edge, as the plan card's is. Its own layout, because Jewel's
 * `VerticallyScrollableContainer` measures its scrollbar at a fixed infinite height when asked for its intrinsic size, which Jewel's
 * menu does (`width(IntrinsicSize.Max)`); here the rows alone answer for the intrinsic sizes, and the scrollbar takes the rows' height.
 */
@Composable
private fun ScrolledRows(modifier: Modifier, rows: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Layout(
        content = {
            Column(Modifier.verticalScroll(scroll), content = rows)
            VerticalScrollbar(scroll)
        },
        modifier = modifier,
        measurePolicy = ScrolledRowsPolicy,
    )
}

/** [ScrolledRows]' layout: the rows as they measure, the scrollbar at their end and their height; the rows' intrinsic sizes. */
private object ScrolledRowsPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val rows = measurables[0].measure(constraints)
        val bar = measurables[1].measure(Constraints.fixedHeight(rows.height))
        return layout(rows.width, rows.height) {
            rows.place(0, 0)
            bar.place(rows.width - bar.width, 0)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables[0].minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables[0].minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables[0].maxIntrinsicHeight(width)
}

/**
 * A selectable row of a [pagedRows] page, drawn and run as Jewel's own menu item is (its `MenuItemBase` is internal to Jewel): the
 * item's colours, padding, minimum height, rounded selection and icon slot from the menu's style; focusable, so the menu's Up and Down
 * reach it through the focus order and the inner scroll brings it into view; a hover moves the focus to it; a click runs [onClick] and
 * then asks the menu to close, as Jewel's items do, so a page turn's [PagedMenuState.show] holds it open as before.
 */
@Composable
internal fun PageRow(
    onClick: () -> Unit,
    iconKey: IconKey? = null,
    enabled: Boolean = true,
    keybinding: Set<String>? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val style = JewelTheme.menuStyle
    val metrics = style.metrics.itemMetrics
    val colors = style.colors.itemColors
    val controller = LocalMenuController.current
    val inputModes = LocalInputModeManager.current
    val source = remember { MutableInteractionSource() }
    val focus = remember { FocusRequester() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(hovered) { if (hovered) focus.requestFocus() }
    val state = MenuItemState.of(selected = false, enabled = enabled, focused = focused, hovered = hovered, pressed = pressed)
    val background by colors.backgroundFor(state)
    val contentColor by colors.contentFor(state)
    val keybindingColor by colors.keybindingTintFor(state)
    Box(
        Modifier.focusRequester(focus)
            .selectable(selected = false, enabled = enabled, interactionSource = source, indication = null) {
                onClick()
                controller.closeAll(inputModes.inputMode, true)
            }
            .then(modifier)
            .fillMaxWidth(),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor, LocalTextStyle provides LocalTextStyle.current.copy(color = contentColor)) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = metrics.minHeight)
                    .drawBehind {
                        val outer = metrics.outerPadding
                        val left = outer.calculateLeftPadding(layoutDirection).toPx()
                        val top = outer.calculateTopPadding().toPx()
                        val corner = metrics.selectionCornerSize.toPx(size, this)
                        drawRoundRect(
                            background,
                            topLeft = Offset(left, top),
                            size = Size(size.width - left - outer.calculateRightPadding(layoutDirection).toPx(), size.height - top - outer.calculateBottomPadding().toPx()),
                            cornerRadius = CornerRadius(corner, corner),
                        )
                    }
                    .padding(metrics.contentPadding),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (iconKey != null) Icon(iconKey, contentDescription = null, modifier = Modifier.size(metrics.iconSize))
                Box(Modifier.weight(1f)) { content() }
                // Jewel's own item draws its shortcut so: after the content, never ellipsised, dim (spec 17.1).
                if (!keybinding.isNullOrEmpty()) {
                    Text(
                        keybinding.joinToString(" "),
                        color = keybindingColor,
                        maxLines = 1,
                        modifier = Modifier.padding(metrics.keybindingsPadding),
                    )
                }
            }
        }
    }
}

/** A separator inside a [pagedRows] page, drawn as Jewel's menu separator is: its height, padding, thickness and colour. */
@Composable
internal fun PageSeparator() {
    val style = JewelTheme.menuStyle
    val metrics = style.metrics.itemMetrics
    Box(Modifier.height(metrics.separatorHeight), contentAlignment = Alignment.Center) {
        Divider(
            Orientation.Horizontal,
            Modifier.fillMaxWidth().padding(metrics.separatorPadding),
            color = style.colors.itemColors.separator,
            thickness = metrics.separatorThickness,
        )
    }
}

/**
 * The `+` menu's first page (spec 3.3, 17.1): five rows in the desktop's order, each with its icon. `Add files or photos` shows
 * [shortcut] at its end when [shortcutShown] ([plusShortcutShown]), else in its tooltip; nothing when the action is unbound.
 */
private fun MenuScope.mainPage(
    state: PagedMenuState,
    page: Int,
    cap: Dp?,
    palette: ChatPalette,
    actions: PlusMenuActions,
    shortcut: Set<String>?,
    shortcutShown: Boolean,
) {
    pagedRows(page, cap) {
        PageRow(
            iconKey = AllIconsKeys.Actions.Attach,
            keybinding = shortcut.takeIf { shortcutShown },
            onClick = {
                state.close()
                actions.onAddFiles()
            },
        ) {
            val tip = shortcut?.takeUnless { shortcutShown }?.joinToString(" ")
            if (tip != null) WithTooltip("Add files or photos ($tip)") { MenuLabel("Add files or photos") } else MenuLabel("Add files or photos")
        }
        PageRow(iconKey = AllIconsKeys.Nodes.Folder, onClick = {
            state.close()
            actions.onAddFolder()
        }) { MenuLabel("Add folder") }
        PageRow(iconKey = AllIconsKeys.Nodes.Console, onClick = {
            state.close()
            actions.onSlashCommands()
        }) { MenuLabel("Slash commands") }
        PageRow(iconKey = AllIconsKeys.Webreferences.Server, onClick = { state.show(PLUS_CONNECTORS) }) {
            MenuLabel("Connectors") { Text("›", color = palette.dim) }
        }
        PageRow(iconKey = AllIconsKeys.Nodes.Plugin, onClick = { state.show(PLUS_PLUGINS) }) {
            MenuLabel("Plugins") { Text("›", color = palette.dim) }
        }
    }
}

/**
 * The Plugins page (spec 3.3, 17.3): `‹ Plugins`, one row per plugin, `<label>` with `v<version>` and `›`, or `No commands or skills`
 * for one with neither (that row opens nothing), then the `/plugin` hint.
 */
private fun MenuScope.pluginsPage(
    state: PagedMenuState,
    page: Int,
    cap: Dp?,
    plugins: ImmutableList<PluginEntry>,
    known: Boolean,
    palette: ChatPalette,
    small: TextStyle,
) {
    pagedRows(
        page,
        cap,
        header = { BackRow("Plugins", palette) { state.show(PLUS_MAIN) } },
        footer = { DimLine("Add or manage plugins with /plugin in a terminal.", palette, small) },
    ) {
        when {
            !known -> DimLine("Plugins show after the first prompt.", palette, small)
            plugins.isEmpty() -> DimLine("No plugins in this session.", palette, small)
            else -> plugins.forEachIndexed { index, plugin ->
                val opens = plugin.skills.isNotEmpty() || plugin.commands.isNotEmpty()
                // A plugin with neither opens nothing: its click turns to the page the menu is on, holding it open.
                PageRow(onClick = { state.show(if (opens) PLUS_FIRST_PLUGIN + index else PLUS_PLUGINS) }) {
                    WithTooltip(plugin.source?.let { "${plugin.name} · $it" } ?: plugin.name) {
                        MenuLabel(plugin.label, Modifier.semantics { contentDescription = "${plugin.label} plugin" }) {
                            plugin.version?.let { Text("v$it", color = palette.dim, maxLines = 1) }
                            if (plugin.version != null) Spacer(Modifier.width(6.dp))
                            if (opens) {
                                Text("›", color = palette.dim)
                            } else {
                                Text("No commands or skills", style = small, color = palette.dim, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One plugin's page (spec 3.3, 17.3): `‹ <label>`, then `Skills` and its rows, then `Commands` and its rows, each only when not empty. */
private fun MenuScope.pluginPage(
    state: PagedMenuState,
    page: Int,
    cap: Dp?,
    plugin: PluginEntry,
    palette: ChatPalette,
    small: TextStyle,
    actions: PlusMenuActions,
) {
    pagedRows(page, cap, header = { BackRow(plugin.label, palette) { state.show(PLUS_PLUGINS) } }) {
        for ((title, rows) in listOf("Skills" to plugin.skills, "Commands" to plugin.commands)) {
            if (rows.isEmpty()) continue
            Text(title, style = small, color = palette.dim, modifier = PASSIVE_INSET.semantics { heading() })
            for (command in rows) {
                PageRow(onClick = {
                    state.close()
                    actions.onCommand(command)
                }) { CommandRow(command, palette, small) }
            }
        }
    }
}

/** A plugin's skill or command (spec 3.3, 3.7): its name, under it its description in the footer size and the dim tier, two lines at most. */
@Composable
private fun CommandRow(command: PluginCommand, palette: ChatPalette, small: TextStyle) {
    val row = @Composable {
        Column(Modifier.fillMaxWidth().semantics { contentDescription = command.command }) {
            Text(command.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (command.description.isNotBlank()) {
                Text(command.description, style = small, color = palette.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (command.tooltip.isNotBlank()) WithTooltip(command.tooltip) { row() } else row()
}

/** A page's first row, `‹ <title>`, above the page's scroll, which turns the menu back with [back]. */
@Composable
internal fun BackRow(title: String, palette: ChatPalette, back: () -> Unit) {
    PageRow(onClick = back) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = palette.dim)) { append("‹") }
                append(" ")
                append(title)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row's label, one line, ellipsised in the room [end] leaves at the row's end (spec 3.3, Size). */
@Composable
internal fun MenuLabel(label: String, modifier: Modifier = Modifier, end: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        end()
    }
}

/** A passive dim line of a menu page. */
@Composable
private fun DimLine(text: String, palette: ChatPalette, small: TextStyle) {
    Text(text, style = small, color = palette.dim, modifier = PASSIVE_INSET)
}

/**
 * The composer box's content (spec 3.5): [strips] across the top, then [add] and [input] side by side along the bottom, [start] in
 * from the box's edge, as a Row aligned to the bottom would place them, [input] taking the width [add] leaves. [add] is placed
 * first and [strips] second: Compose's Tab order follows the order in which siblings are placed, so it runs `+`, the strips' `×`
 * buttons, then the text area, although the strips sit above the `+`.
 */
@Composable
private fun ComposerBoxLayout(start: Dp, add: @Composable () -> Unit, strips: @Composable () -> Unit, input: @Composable () -> Unit) {
    Layout(contents = listOf(add, strips, input), modifier = Modifier.fillMaxWidth()) { (adds, stripRows, inputs), constraints ->
        val left = start.roundToPx()
        val width = constraints.maxWidth
        val placedAdd = adds.map { it.measure(Constraints()) }
        val addWidth = placedAdd.maxOfOrNull { it.width } ?: 0
        val placedStrips = stripRows.map { it.measure(Constraints(maxWidth = width)) }
        val stripsHeight = placedStrips.sumOf { it.height }
        val inputWidth = (width - left - addWidth).coerceAtLeast(0)
        val placedInput = inputs.map { it.measure(Constraints(minWidth = inputWidth, maxWidth = inputWidth)) }
        val rowHeight = (placedAdd + placedInput).maxOfOrNull { it.height } ?: 0
        val bottom = stripsHeight + rowHeight
        layout(constraints.constrainWidth(width), constraints.constrainHeight(bottom)) {
            placedAdd.forEach { it.place(left, bottom - it.height) }
            var top = 0
            placedStrips.forEach {
                it.place(0, top)
                top += it.height
            }
            placedInput.forEach { it.place(left + addWidth, bottom - it.height) }
        }
    }
}

/**
 * The session's added folders (spec 3.4, 3.5, 3.7), collected from [folders], in a row that wraps: one pill per folder with a folder
 * icon and its name, described as `Folder <name>` with its state as the state description, its full path (the home as `~`) and its
 * state in the tooltip, in the error colour when the CLI did not add it; a 16 dp `×` (`Remove folder <name>`) calls [onRemove].
 * Nothing while there is no folder.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderStrip(folders: StateFlow<List<AddedFolder>>, onRemove: (String) -> Unit) {
    val added by folders.collectAsState()
    if (added.isEmpty()) return
    val home = remember { System.getProperty("user.home") }
    val shape = RoundedCornerShape(6.dp)
    val colors = JewelTheme.globalColors
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        added.forEach { folder ->
            key(folder.path) {
                val name = folderName(folder.path)
                val state = folderStateText(folder.state)
                val tint = if (folder.state == FolderState.MISSING) colors.text.error else colors.text.normal
                Row(
                    Modifier.border(1.dp, colors.borders.normal, shape).padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WithTooltip(tooltip = {
                        Column {
                            Text(displayPath(folder.path, home, null))
                            Text(state)
                        }
                    }) {
                        Row(
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "Folder $name"
                                stateDescription = state
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AllIconsKeys.Nodes.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(name, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        }
                    }
                    IconButton(
                        onClick = { onRemove(folder.path) },
                        modifier = Modifier.padding(start = 2.dp).size(16.dp).semantics { contentDescription = "Remove folder $name" },
                    ) {
                        Icon(AllIconsKeys.General.Close, contentDescription = null)
                    }
                }
            }
        }
    }
}

/** A folder's name: the last segment of its [path] (a root has none: the path itself). */
private fun folderName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

/** A folder pill's state line (spec 3.4). */
private fun folderStateText(state: FolderState): String = when (state) {
    FolderState.PENDING -> "Applies with the next prompt."
    FolderState.ACTIVE -> "Added to this session."
    FolderState.MISSING -> "The CLI did not add this folder."
}

/**
 * The editor's selection that goes with the next message (spec 3.5): a small bordered chip reading `<name>:<start>-<end>`, described
 * as that label with the file's path as its tooltip, and a 16 dp `×` (`Remove the selection from the message`) that drops it
 * ([onRemove]) until the next selection.
 */
@Composable
private fun SelectionChip(selection: Selection, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.testTag("selection-chip").border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WithTooltip(selection.path) {
                Text(
                    selection.label,
                    Modifier.semantics { contentDescription = selection.label },
                    style = smallTextStyle(),
                    color = chatPalette().secondary,
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).testTag("selection-chip-remove")
                    .semantics { contentDescription = "Remove the selection from the message" },
            ) {
                Icon(AllIconsKeys.General.Close, contentDescription = null)
            }
        }
    }
}

/**
 * The images attached to the prompt being typed, in a row that wraps (polish spec 2.7): each a 56 dp thumbnail, rounded and bordered,
 * described as `[Image #N]` and with it as its tooltip, with a 16 dp `×` at its top-right that removes it (`Remove image N`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentStrip(attachments: SnapshotStateList<PendingImage>) {
    val shape = RoundedCornerShape(6.dp)
    val border = JewelTheme.globalColors.borders.normal
    FlowRow(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEachIndexed { index, image ->
            key(image.id) {
                val label = Attachments.label(index)
                Box(Modifier.size(56.dp)) {
                    WithTooltip(label) {
                        val thumbnail = image.thumbnail
                        if (thumbnail != null) {
                            Image(
                                thumbnail,
                                contentDescription = label,
                                modifier = Modifier.size(56.dp).clip(shape).border(1.dp, border, shape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                Modifier.size(56.dp).border(1.dp, border, shape).semantics { contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("#${index + 1}", style = monoTextStyle(), color = chatPalette().secondary)
                            }
                        }
                    }
                    IconButton(
                        onClick = { attachments.remove(image) },
                        modifier = Modifier.align(Alignment.TopEnd).size(16.dp).semantics { contentDescription = "Remove image ${index + 1}" },
                    ) {
                        Icon(AllIconsKeys.General.Close, contentDescription = null)
                    }
                }
            }
        }
    }
}

/**
 * Pastes what Ctrl/Cmd+V found on the clipboard (polish spec 2.7): its images, read off the EDT, each attached; or, for a file list
 * without an image, the clipboard's text at the caret, as the text area would have pasted it.
 */
private suspend fun paste(ui: ChatUiState) {
    val read = ComposerImages.readClipboardImages()
    if (read.isNotEmpty()) {
        read.forEach { (name, bytes) -> attach(ui, name, bytes) }
        return
    }
    val text = ComposerImages.clipboardText() ?: return
    ui.draft.edit {
        val start = selection.min
        replace(start, selection.max, text)
        selection = TextRange(start + text.length)
    }
}

/**
 * Attaches the image [name] ([bytes], null when it could not be read) to the prompt being typed, with its thumbnail decoded off the
 * EDT, or puts why it cannot be attached in [ui]'s notice ([Attachments.check]). On the EDT.
 */
private suspend fun attach(ui: ChatUiState, name: String, bytes: ByteArray?) {
    if (bytes == null) {
        ui.composerNotice.value = "$name could not be read"
        return
    }
    fun refusal(): String? = Attachments.check(name, bytes, ui.attachments.size, ui.attachments.sumOf { Attachments.encodedSize(it.bytes.size) })
    refusal()?.let {
        ui.composerNotice.value = it
        return
    }
    val mediaType = Attachments.mediaType(bytes) ?: return
    val thumbnail = withContext(Dispatchers.Default) { decodeImageBytes(bytes) }
    // Checked again: another paste or drop may have attached images while this one decoded.
    refusal()?.let {
        ui.composerNotice.value = it
        return
    }
    ui.attachments += PendingImage(UUID.randomUUID().toString(), name, mediaType, bytes, thumbnail)
}

/**
 * [ui]'s draft without the input method's composition in progress (MCP spec 7, Palette and IME): what the palette filters on, so a
 * composition never moves its selection.
 */
private fun committedText(ui: ChatUiState): String =
    committedDraft(ui.draft.text.toString(), ui.draft.composition?.let { it.min until it.max })

/** The caret in [ui]'s draft, or -1 while text is selected (no mention opens then). */
private fun caretOf(ui: ChatUiState): Int = ui.draft.selection.takeIf { it.collapsed }?.end ?: -1

/** How long the `@` palette waits after the mention's text last changed before it looks the files up (spec 3.5). */
private const val MENTION_DEBOUNCE_MS: Long = 100

/**
 * The files for the mention text [text], best first and the open editors' files first, at most [MENTION_ROWS]: after the debounce,
 * from [files] off the EDT. A lookup that fails lists nothing and is logged by its class only.
 */
private suspend fun lookUpMention(files: ProjectFiles, text: String): List<String> {
    delay(MENTION_DEBOUNCE_MS)
    return try {
        withContext(Dispatchers.Default) { mentionPalette(MentionQuery(0, text), files.matching(text, MENTION_ROWS), files.openFirst).rows }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger<ProjectFiles>().warn("the @ palette's lookup failed: ${e.javaClass.name}")
        emptyList()
    }
}

/** The chat column's width before the prompt bar measured it: the `+` menu takes its widest bounds. */
private val UNMEASURED_COLUMN: Dp = 376.dp

/** The popover's share of the chat pane's height (desktop-parity spec 3.1). */
private const val POPOVER_HEIGHT_SHARE: Float = 0.6f

/** The popover's height cap before the pane is measured. */
private val POPOVER_FALLBACK_HEIGHT = 400.dp

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.chat.Attachments
import dev.daarkolabs.tandem.chat.PaletteItem
import dev.daarkolabs.tandem.chat.PaletteOutcome
import dev.daarkolabs.tandem.chat.PluginCommand
import dev.daarkolabs.tandem.chat.PluginEntry
import dev.daarkolabs.tandem.chat.ScrollEvent
import dev.daarkolabs.tandem.chat.SlashIntercept
import dev.daarkolabs.tandem.chat.SlashPalette
import dev.daarkolabs.tandem.chat.StopTarget
import dev.daarkolabs.tandem.chat.committedDraft
import dev.daarkolabs.tandem.chat.intercepts
import dev.daarkolabs.tandem.chat.moved
import dev.daarkolabs.tandem.chat.paletteFor
import dev.daarkolabs.tandem.chat.pick
import dev.daarkolabs.tandem.chat.shellCommand
import dev.daarkolabs.tandem.chat.slashIntercept
import dev.daarkolabs.tandem.chat.stopTarget
import dev.daarkolabs.tandem.chat.withCommand
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.actionSystem.provideData
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InlineWarningBanner
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys
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
 * the box. Enter sends unless the text is blank and nothing is attached, and does nothing while a turn runs or the last prompt is
 * still being encoded; Shift+Enter is a newline; Esc goes to the open card ([onCardEscape]), else stops a running turn. A prompt goes
 * out through [sender], which encodes its images off the EDT; a send makes the list follow its bottom again. A pick goes to
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
 * being sent: the draft is cleared, the attachments are left for the next prompt and the list follows its bottom again. While the
 * command runs the composer is busy, and Stop (the button or Esc) stops the command; otherwise it stops the turn ([stopTarget]).
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
    val scrollToEnd: () -> Unit = {
        scope.launch {
            val last = ui.listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) ui.listState.animateScrollToItem(last)
        }
    }
    val sendNow = {
        if (sendDraft(ui, sender)) scrollToEnd()
    }
    // The interception table (MCP spec 3.4): Tandem's own commands act here and clear the draft, sending nothing and leaving the
    // attachment strip as it is; anything else is today's send, attachments included.
    val runIntercept = { intercept: SlashIntercept ->
        when (intercept) {
            is SlashIntercept.Send -> sendNow()
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
    // The send path, Enter or Send, palette or not (a pasted `/mcp` behaves the same): a shell escape runs first (MCP spec 12.1); else
    // the table runs on the trimmed draft before the attachments are encoded.
    val send: () -> Unit = {
        val text = ui.draft.text.toString()
        val command = shellCommand(text)
        if (command != null) {
            manager.runShell(handle.id, command)
            ui.draft.clearText()
            ui.follow.value = ui.follow.value.on(ScrollEvent.Sent)
            scrollToEnd()
        } else {
            runIntercept(slashIntercept(text.trim(), composer.models))
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
    // A pick's outcome (MCP spec 4.3): the composer only executes it.
    val execute = { outcome: PaletteOutcome ->
        when (outcome) {
            is PaletteOutcome.Intercept -> {
                // A row's own name never reads as text to send; should one, the send path gets that text, not the typed prefix.
                (outcome.intercept as? SlashIntercept.Send)?.let { ui.draft.setTextAndPlaceCursorAtEnd(it.text) }
                runIntercept(outcome.intercept)
            }
            is PaletteOutcome.Insert -> ui.draft.setTextAndPlaceCursorAtEnd(outcome.text)
            is PaletteOutcome.Send -> {
                ui.draft.setTextAndPlaceCursorAtEnd(outcome.text)
                send()
            }
            PaletteOutcome.Ignored -> Unit
        }
    }
    // A key picks from the palette on screen; a click on a row picks that row, from the palette its click was made on even should
    // the text area have lost the focus to the press.
    val pickFrom = { shown: SlashPalette?, item: PaletteItem?, insert: Boolean ->
        if (shown != null) {
            val index = item?.let(shown.items::indexOf)?.takeIf { it >= 0 } ?: shown.selected
            execute(pick(shown.copy(selected = index), insert, busy = busy || sending, composer.models))
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
            focus,
            onFocused,
            inputFocused = inputFocused,
            palette = { palette.value },
            interceptable = { intercepts(ui.draft.text.toString(), composer.models) },
            onPalettePick = { item -> pickFrom(paletteOf(true), item, false) },
            plugins = composer.plugins,
            pluginsKnown = composer.pluginsKnown,
            columnWidth = columnWidthOf,
            onAdd = { ComposerImages.chooseImagesLater(project, onChosen = attachFiles) },
            onDrop = attachFiles,
            onAction = { action ->
                when (action) {
                    ComposerAction.Send -> send()
                    ComposerAction.Stop -> when (stopTarget(composer.shellRunning)) {
                        StopTarget.Shell -> manager.stopShell(handle.id)
                        StopTarget.Turn -> manager.interrupt(handle.id)
                    }
                    ComposerAction.CardEscape -> onCardEscape()
                    ComposerAction.PasteImage -> scope.launch { paste(ui) }
                    ComposerAction.RemoveLastImage -> ui.attachments.removeLastOrNull()
                    ComposerAction.ClosePanel -> ui.mcpPanelOpen.value = false
                    ComposerAction.PaletteUp -> palette.value?.let { ui.paletteSelection.value = it.query to it.moved(-1).selected }
                    ComposerAction.PaletteDown -> palette.value?.let { ui.paletteSelection.value = it.query to it.moved(1).selected }
                    ComposerAction.PalettePick -> pickFrom(palette.value, null, false)
                    ComposerAction.PaletteInsert -> pickFrom(palette.value, null, true)
                    ComposerAction.PaletteClose -> palette.value?.let { ui.paletteDismissedFor.value = it.query }
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
                )
            }
            CompositionLocalProvider(LocalChatColumnWidth provides columnWidthOf) { ContextRing(presenter.context, popover) }
        }
    }
}

/**
 * The text area, `+` and Send or Stop in one filled rounded box, as wide as the footer under it, with the attached images above them
 * ([AttachmentStrip]): Stop while [busy]. Keys go through [composerKey] with [busy] or [sending] (a prompt still being encoded),
 * [cardOpen], whether the slash palette is open ([palette], read at each key, so never a frame-old one) and whether the draft is one
 * of Tandem's own commands ([interceptable], which Enter runs even while busy), and Send is disabled
 * while [sending]; [onAction] does what they return. `+` opens a menu whose `Add files or photos` calls [onAdd] and whose
 * `Slash commands` puts `/` in an empty draft and focuses the text area; files dropped on the box go to [onDrop], and the box draws
 * its focused border while a drag of files hovers it. With [focus] the text area takes the focus, and then [onFocused] is called;
 * whether it has the focus is [inputFocused]. The palette ([SlashPaletteView]) opens above the box at its left edge, never wider than
 * it, and a click on one of its rows goes to [onPalettePick]; while it is open on a lone `/` the text area reads `/Type to filter`
 * ([FilterHint]). It takes those fields, not the whole composer model, so that a change to another one leaves it alone.
 */
@Composable
private fun PromptBox(
    ui: ChatUiState,
    busy: Boolean,
    sending: Boolean,
    cardOpen: Boolean,
    focus: Boolean,
    onFocused: () -> Unit,
    inputFocused: MutableState<Boolean>,
    palette: () -> SlashPalette?,
    interceptable: () -> Boolean,
    onPalettePick: (PaletteItem) -> Unit,
    plugins: ImmutableList<PluginEntry>,
    pluginsKnown: Boolean,
    columnWidth: () -> Dp?,
    onAdd: () -> Unit,
    onDrop: (List<Path>) -> Unit,
    onAction: (ComposerAction) -> Unit,
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
    val currentOnAction by rememberUpdatedState(onAction)
    // Derived, so that the box recomposes when the hint comes or goes, not at every keystroke.
    val currentPalette by rememberUpdatedState(palette)
    val filterHint by remember(ui) { derivedStateOf { filterHintShown(ui.draft.text, currentPalette()) } }
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
        // The attached images, above the row, across the box's inner width (polish spec 2.7).
        if (ui.attachments.isNotEmpty()) AttachmentStrip(ui.attachments)
        Row(
            // The text's ink starts about 42 dp inside the box (desktop-02): 8 dp, the 24 dp `+`, 5 dp, then the undecorated
            // TextArea's own content padding (5 dp across, 4 dp down); 8 + 4 above and below the text, so 184 dp holds 8 lines of
            // 20 dp. The `+` and the send glyph stay on the last line as the text grows.
            Modifier.fillMaxWidth().heightIn(max = 184.dp).padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val density = LocalDensity.current
            AddButton(
                Modifier.padding(end = 5.dp, bottom = 10.dp),
                plugins,
                pluginsKnown,
                width = plusMenuWidth(columnWidth() ?: UNMEASURED_COLUMN),
                // The room above the box, as the palette measures it (MCP spec 14); none before the box is placed.
                maxHeight = with(density) { (boxTop - 8.dp.roundToPx()).takeIf { it > 0 }?.toDp() },
                actions = remember(ui, onAdd) {
                    PlusMenuActions(
                        onAddFiles = onAdd,
                        onSlashCommands = {
                            ui.openPalette()
                            refocus++
                        },
                        // Until the Connectors page (D15): the MCP panel, opened as a user action.
                        onConnectors = { menu -> menu.openMcpFromMenu(ui) },
                        onCommand = { command ->
                            ui.draft.setTextAndPlaceCursorAtEnd(withCommand(ui.draft.text.toString(), command.command))
                            refocus++
                        },
                    )
                },
            )
            // The text area, and over it `Type to filter` after a lone `/` while the palette is open (MCP spec 14): its own
            // placeholder shows only while it is empty.
            Box(Modifier.weight(1f)) {
                TextArea(
                    ui.draft,
                    Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 184.dp).padding(vertical = 8.dp)
                        .focusRequester(inputFocus)
                        .onFocusChanged { inputFocused.value = it.hasFocus }
                        // In the IDE, Ctrl/Cmd+V is its Paste action, which takes the key before the handler below sees it: while
                        // the clipboard holds images, the focused composer's paste provider takes the paste from the Compose text paste
                        // (polish spec 2.7). Asked for only by a paste, so the clipboard's flavours are not queried on every action
                        // update.
                        .provideData {
                            lazy(PlatformDataKeys.PASTE_PROVIDER.name) {
                                imagePaste.takeIf { ComposerImages.clipboardHasImage { CopyPasteManager.getInstance().contents?.transferDataFlavors.orEmpty().toList() } }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            composerKeyEvent(event, busy || sending, cardOpen, ui, palette() != null, interceptable, onAction)
                        },
                    lineLimits = TextFieldLineLimits.MultiLine(1, 8),
                    undecorated = true,
                    placeholder = { Text("Type / for commands", color = colors.text.info) },
                )
                if (filterHint) FilterHint(top = 8.dp)
            }
            // Centred on the last line: 12 dp padding + half a 20 dp line − half the 24 dp button.
            val buttonModifier = Modifier.padding(start = 8.dp, bottom = 10.dp).size(24.dp)
            if (busy) {
                IconButton(onClick = { onAction(ComposerAction.Stop) }, modifier = buttonModifier.semantics { contentDescription = "Stop" }) {
                    Icon(AllIconsKeys.Actions.Suspend, contentDescription = null, tint = colors.text.info)
                }
            } else {
                // Disabled, and dimmed, while the last prompt is still being encoded: the composer counts as busy (polish spec 2.7).
                // sendDraft checks the sender's own flag again, so a click that races the encode still sends nothing.
                IconButton(
                    onClick = { onAction(ComposerAction.Send) },
                    enabled = !sending,
                    modifier = buttonModifier.semantics { contentDescription = "Send" },
                ) {
                    Text("↵", style = JewelTheme.editorTextStyle, color = if (sending) colors.text.disabled else colors.text.info)
                }
            }
        }
        // Placed in the box, so that the popup is anchored to the box's own edges.
        val shown = palette()
        if (shown != null && boxWidth > 0) {
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
 * focus on) gives the focus back to the `+`; `Slash commands`, a plugin's command and `Connectors` hand it to the text area or the
 * MCP panel instead. The menu is [width] wide at most and at least ([plusMenuWidth]) and at most [maxHeight] high.
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
) {
    val state = remember { PagedMenuState() }
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
            onSlashCommands = {
                handsFocus.value = true
                actions.onSlashCommands()
            },
            onConnectors = {
                handsFocus.value = true
                actions.onConnectors(state)
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
                )
            }
        }
    }
}

/**
 * What the `+` menu's rows do: [onAddFiles] the image chooser (after the menu closed), [onSlashCommands] `/` and the palette,
 * [onConnectors] the MCP panel (until the Connectors page, D15, through [openMcpFromMenu]), and [onCommand] a plugin's command into
 * the draft.
 */
internal class PlusMenuActions(
    val onAddFiles: () -> Unit,
    val onSlashCommands: () -> Unit,
    val onConnectors: (PagedMenuState) -> Unit,
    val onCommand: (PluginCommand) -> Unit,
)

/**
 * The `+` menu (desktop-parity spec 3.3, 17.1, 17.3), [gapPx] above its anchor and left-aligned, on [state]'s page, every page
 * between [width]'s bounds and at most [maxHeight] high (Jewel's menu scrolls its own column under the cap, passed as the menu's
 * modifier, which `MenuContent` applies before its `width(IntrinsicSize.Max)` and around its `verticalScroll` column; a focused row is
 * brought into view as the keys move the selection). Page [PLUS_MAIN]: `Add files or photos`, `Slash commands`, `Connectors ›` and
 * `Plugins ›`, each with its icon. Page [PLUS_PLUGINS]: `‹ Plugins`, one row per plugin of [plugins] (or the line that says why there
 * is none, [pluginsKnown]), the `/plugin` hint. Page [PLUS_FIRST_PLUGIN] + i: `‹ <label>`, then its skills and its commands under their
 * headings; a pick closes the menu and calls [PlusMenuActions.onCommand]. Names and descriptions are config text: plain [Text].
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
) {
    val palette = chatPalette()
    val small = smallTextStyle()
    val page = state.page
    val plugin = if (page >= PLUS_FIRST_PLUGIN) plugins.getOrNull(page - PLUS_FIRST_PLUGIN) else null
    PopupMenu(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = remember(gapPx) { AboveAnchor(alignEnd = false, gapPx = gapPx) },
        modifier = Modifier.widthIn(min = width.first, max = width.second)
            .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
            .testTag("plus-menu"),
    ) {
        when {
            plugin != null -> pluginPage(state, plugin, palette, small, actions)
            // A plugin's page whose plugin left the list (a new init) shows the Plugins page.
            page == PLUS_PLUGINS || page >= PLUS_FIRST_PLUGIN -> pluginsPage(state, plugins, pluginsKnown, palette, small)
            else -> mainPage(state, palette, actions)
        }
    }
}

/** The `+` menu's first page (spec 3.3, 17.1): four rows in the desktop's order, each with its icon. */
private fun MenuScope.mainPage(state: PagedMenuState, palette: ChatPalette, actions: PlusMenuActions) {
    selectableItem(selected = false, iconKey = AllIconsKeys.Actions.Attach, onClick = {
        state.close()
        actions.onAddFiles()
    }) { MenuLabel("Add files or photos") }
    selectableItem(selected = false, iconKey = AllIconsKeys.Nodes.Console, onClick = {
        state.close()
        actions.onSlashCommands()
    }) { MenuLabel("Slash commands") }
    selectableItem(selected = false, iconKey = AllIconsKeys.Webreferences.Server, onClick = { actions.onConnectors(state) }) {
        MenuLabel("Connectors") { Text("›", color = palette.dim) }
    }
    selectableItem(selected = false, iconKey = AllIconsKeys.Nodes.Plugin, onClick = { state.show(PLUS_PLUGINS) }) {
        MenuLabel("Plugins") { Text("›", color = palette.dim) }
    }
}

/**
 * The Plugins page (spec 3.3, 17.3): `‹ Plugins`, one row per plugin, `<label>` with `v<version>` and `›`, or `No commands or skills`
 * for one with neither (that row opens nothing), then the `/plugin` hint.
 */
private fun MenuScope.pluginsPage(state: PagedMenuState, plugins: ImmutableList<PluginEntry>, known: Boolean, palette: ChatPalette, small: TextStyle) {
    backItem("Plugins", palette) { state.show(PLUS_MAIN) }
    separator()
    when {
        !known -> passiveItem { DimLine("Plugins show after the first prompt.", palette, small) }
        plugins.isEmpty() -> passiveItem { DimLine("No plugins in this session.", palette, small) }
        else -> plugins.forEachIndexed { index, plugin ->
            val opens = plugin.skills.isNotEmpty() || plugin.commands.isNotEmpty()
            // A plugin with neither opens nothing: its click turns to the page the menu is on, holding it open.
            selectableItem(selected = false, onClick = { state.show(if (opens) PLUS_FIRST_PLUGIN + index else PLUS_PLUGINS) }) {
                WithTooltip(plugin.source?.let { "${plugin.name} · $it" } ?: plugin.name) {
                    MenuLabel(plugin.label, Modifier.semantics { contentDescription = "${plugin.label} plugin" }) {
                        plugin.version?.let { Text("v$it", color = palette.dim, maxLines = 1) }
                        if (opens) {
                            if (plugin.version != null) Spacer(Modifier.width(6.dp))
                            Text("›", color = palette.dim)
                        } else {
                            if (plugin.version != null) Spacer(Modifier.width(6.dp))
                            Text("No commands or skills", style = small, color = palette.dim, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    separator()
    passiveItem { DimLine("Add or manage plugins with /plugin in a terminal.", palette, small) }
}

/** One plugin's page (spec 3.3, 17.3): `‹ <label>`, then `Skills` and its rows, then `Commands` and its rows, each only when not empty. */
private fun MenuScope.pluginPage(state: PagedMenuState, plugin: PluginEntry, palette: ChatPalette, small: TextStyle, actions: PlusMenuActions) {
    backItem(plugin.label, palette) { state.show(PLUS_PLUGINS) }
    separator()
    for ((title, rows) in listOf("Skills" to plugin.skills, "Commands" to plugin.commands)) {
        if (rows.isEmpty()) continue
        passiveItem {
            Text(title, style = small, color = palette.dim, modifier = PASSIVE_INSET.semantics { heading() })
        }
        for (command in rows) {
            selectableItem(selected = false, onClick = {
                state.close()
                actions.onCommand(command)
            }) { CommandRow(command, palette, small) }
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

/** A page's first row, `‹ <title>`, which turns the menu back with [back]. */
private fun MenuScope.backItem(title: String, palette: ChatPalette, back: () -> Unit) {
    selectableItem(selected = false, onClick = back) {
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
private fun MenuLabel(label: String, modifier: Modifier = Modifier, end: @Composable RowScope.() -> Unit = {}) {
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

/** The chat column's width before the prompt bar measured it: the `+` menu takes its widest bounds. */
private val UNMEASURED_COLUMN: Dp = 376.dp

/** The popover's share of the chat pane's height (desktop-parity spec 3.1). */
private const val POPOVER_HEIGHT_SHARE: Float = 0.6f

/** The popover's height cap before the pane is measured. */
private val POPOVER_FALLBACK_HEIGHT = 400.dp

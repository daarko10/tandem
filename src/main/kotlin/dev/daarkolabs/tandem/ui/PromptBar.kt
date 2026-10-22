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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.chat.Attachments
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.actionSystem.provideData
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InlineWarningBanner
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import java.util.UUID

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
) {
    val composer by presenter.composer.collectAsState()
    val sending by sender.sending.collectAsState()
    val scope = rememberCoroutineScope()
    val send = {
        if (sendDraft(ui, sender)) {
            scope.launch {
                val last = ui.listState.layoutInfo.totalItemsCount - 1
                if (last >= 0) ui.listState.animateScrollToItem(last)
            }
        }
    }
    val attachFiles: (List<Path>) -> Unit = { paths ->
        if (paths.isNotEmpty()) scope.launch { ComposerImages.readFiles(paths).forEach { (name, bytes) -> attach(ui, name, bytes) } }
    }
    // The box and the footer share the chat column's width less 16 dp each side, so the footer measures from the box's edges.
    Column(Modifier.chatColumn().padding(horizontal = 16.dp)) {
        ui.composerNotice.value?.let { notice ->
            InlineWarningBanner(
                text = notice,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                iconActions = { iconAction(AllIconsKeys.General.Close, "Close", null) { ui.composerNotice.value = null } },
            )
        }
        PromptBox(
            ui,
            composer.busy,
            sending,
            composer.cardOpen,
            focus,
            onFocused,
            onAdd = { scope.launch { attachFiles(ComposerImages.chooseImages(project)) } },
            onDrop = attachFiles,
            onAction = { action ->
                when (action) {
                    ComposerAction.Send -> send()
                    ComposerAction.Stop -> manager.interrupt(handle.id)
                    ComposerAction.CardEscape -> onCardEscape()
                    ComposerAction.PasteImage -> scope.launch { paste(ui) }
                    ComposerAction.RemoveLastImage -> ui.attachments.removeLastOrNull()
                    ComposerAction.ClosePanel -> ui.mcpPanelOpen.value = false
                }
            },
        )
        // Spec 2.3: the pills' edges 9 dp inside the box at the start and 10 dp at the end, 10 dp under it, 12 dp above the bottom.
        Row(
            Modifier.fillMaxWidth().padding(start = 9.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeControl(composer) { mode ->
                manager.setPermissionMode(handle.id, mode)
                workspace.state.lastPermissionMode = mode.wire
            }
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
                )
            }
            ContextRing(presenter.context)
        }
    }
}

/**
 * The text area, `+` and Send or Stop in one filled rounded box, as wide as the footer under it, with the attached images above them
 * ([AttachmentStrip]): Stop while [busy]. Keys go through [composerKey] with [busy] or [sending] (a prompt still being encoded) and
 * [cardOpen], and Send is disabled while [sending]; [onAction] does what they return. `+` opens a menu whose `Add files or photos`
 * calls [onAdd]; files dropped on the box go to [onDrop], and the box draws its focused border while a drag of files hovers it. With
 * [focus] the text area takes the focus, and then [onFocused] is called. It takes those fields, not the whole composer model, so
 * that a change to another one leaves it alone.
 */
@Composable
private fun PromptBox(
    ui: ChatUiState,
    busy: Boolean,
    sending: Boolean,
    cardOpen: Boolean,
    focus: Boolean,
    onFocused: () -> Unit,
    onAdd: () -> Unit,
    onDrop: (List<Path>) -> Unit,
    onAction: (ComposerAction) -> Unit,
) {
    val inputFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnAction by rememberUpdatedState(onAction)
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
            AddButton(Modifier.padding(end = 5.dp, bottom = 10.dp), onAdd)
            TextArea(
                ui.draft,
                Modifier.weight(1f).heightIn(min = 44.dp, max = 184.dp).padding(vertical = 8.dp)
                    .focusRequester(inputFocus)
                    .onFocusChanged { focused = it.hasFocus }
                    // In the IDE, Ctrl/Cmd+V is its Paste action, which takes the key before the handler below sees it: while the
                    // clipboard holds images, the focused composer's paste provider takes the paste from the Compose text paste (polish
                    // spec 2.7). Asked for only by a paste, so the clipboard's flavours are not queried on every action update.
                    .provideData {
                        lazy(PlatformDataKeys.PASTE_PROVIDER.name) {
                            imagePaste.takeIf { ComposerImages.clipboardHasImage { CopyPasteManager.getInstance().contents?.transferDataFlavors.orEmpty().toList() } }
                        }
                    }
                    .onPreviewKeyEvent { event -> composerKeyEvent(event, busy || sending, cardOpen, ui, onAction) },
                lineLimits = TextFieldLineLimits.MultiLine(1, 8),
                undecorated = true,
                placeholder = { Text("Describe a task or ask a question", color = colors.text.info) },
            )
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
 * which the text area would not type, or nothing at all (spec 4.15). Every other key goes to the text area. Ctrl/Cmd+V asks the
 * clipboard for its flavours only (polish spec 2.7): the data is read later, off the EDT.
 */
private fun composerKeyEvent(event: KeyEvent, busy: Boolean, cardOpen: Boolean, ui: ChatUiState, onAction: (ComposerAction) -> Unit): Boolean {
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
        panelOpen = ui.mcpPanelOpen.value,
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
 * The `+` at the start of the composer box (polish spec 2.3, 2.7): a 24 dp icon button in the secondary tier, described as `Add`,
 * whose menu opens above it, left-aligned, with one item, `Add files or photos`, which calls [onAdd] once the menu has closed. A click
 * on the `+` while the menu is open closes it, as a footer control's does.
 *
 * [modifier] (the button's padding in the row) goes on an outer box; the menu is placed in the inner, unpadded one, so that
 * [AboveAnchor] measures from the button's own edges, as `FooterControl` places its menu.
 */
@Composable
private fun AddButton(modifier: Modifier, onAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(modifier) {
        Box {
            IconButton(
                onClick = { open = !open },
                modifier = Modifier.size(24.dp).hoverable(source).semantics { contentDescription = "Add" },
                interactionSource = source,
            ) {
                Icon(AllIconsKeys.General.Add, contentDescription = null, tint = chatPalette().secondary)
            }
            if (open) {
                PopupMenu(
                    onDismissRequest = { mode ->
                        // A click on the `+` itself closes the menu through the button (its click toggles it).
                        if (mode == InputMode.Touch && hovered) {
                            false
                        } else {
                            open = false
                            true
                        }
                    },
                    popupPositionProvider = remember(gap) { AboveAnchor(alignEnd = false, gapPx = gap) },
                ) {
                    selectableItem(
                        selected = false,
                        iconKey = AllIconsKeys.Actions.Attach,
                        onClick = {
                            open = false
                            onAdd()
                        },
                    ) {
                        Text("Add files or photos")
                    }
                }
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

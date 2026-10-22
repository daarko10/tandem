package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.McpAction
import dev.daarkolabs.tandem.chat.McpPanelModel
import dev.daarkolabs.tandem.chat.McpRowModel
import dev.daarkolabs.tandem.ide.BrowserOpener
import dev.daarkolabs.tandem.session.McpAuthWait
import dev.daarkolabs.tandem.session.McpControls
import dev.daarkolabs.tandem.session.SessionHandle
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// The MCP servers panel (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 3.2, 3.3, 3.7): a sheet over the chat.

/**
 * The MCP panel of [handle]'s session over its chat, while it is open: the drawer's scrim over the whole chat box (a click on it
 * closes the panel) and the sheet anchored at its top, the chat column's width and at most 60 % of its height. One
 * [McpPanelController] per open panel, closed with it: its requests go to [controls], a sign-in's link to [opener]. [onClose]
 * closes the panel (Esc in it, Close, a click on the scrim); [firstFocus] is its first enabled action button, else Close.
 */
@Composable
internal fun McpPanelHost(
    handle: SessionHandle,
    controls: McpControls,
    opener: BrowserOpener,
    onClose: () -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(handle) {
        McpPanelController(handle.id, handle.mcpServers, handle.mcpAuth, handle.mcpLoginHinted, handle.status, controls, opener, scope)
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val currentOnClose by rememberUpdatedState(onClose)
    BoxWithConstraints(modifier) {
        val scrim = Color.Black.copy(alpha = if (JewelTheme.isDark) 0.4f else 0.2f)
        Box(Modifier.fillMaxSize().background(scrim).pointerInput(Unit) { detectTapGestures { currentOnClose() } })
        McpSheet(
            controller,
            onClose,
            firstFocus,
            Modifier.align(Alignment.TopCenter).chatColumn().heightIn(max = maxHeight * 0.6f),
        )
    }
}

/**
 * The sheet itself: the title with Refresh servers and Close, the title area's error with Retry, then the servers by group, each
 * row with its status, detail, error and actions (spec 3.2). Opening it puts the focus on [firstFocus]: the first enabled action
 * button, else Close. Esc anywhere in it calls [onClose].
 */
@Composable
internal fun McpSheet(controller: McpPanelController, onClose: () -> Unit, firstFocus: FocusRequester, modifier: Modifier = Modifier) {
    val model by controller.rows.collectAsState()
    val busy by controller.busy.collectAsState()
    val message by controller.message.collectAsState()
    val refreshing by controller.refreshing.collectAsState()
    val titleNote by controller.titleNote.collectAsState()
    val waits by controller.auth.collectAsState()
    val rowErrors by controller.rowErrors.collectAsState()
    val retries by controller.retryActions.collectAsState()
    val currentOnClose by rememberUpdatedState(onClose)
    val firstKey = firstActionRow(model, busy, waits)
    val shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
    Column(
        modifier
            .background(JewelTheme.globalColors.panelBackground, shape)
            .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
            .testTag("mcp-panel")
            .semantics { contentDescription = "MCP servers panel" }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return@onPreviewKeyEvent false
                currentOnClose()
                true
            }
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "MCP servers · ${model.connected} of ${model.total} connected" + (if (refreshing) " · Refreshing…" else "") + titleNote.orEmpty(),
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            WithTooltip("Refresh servers") {
                IconButton(onClick = controller::refresh) { Icon(AllIconsKeys.Actions.Refresh, contentDescription = "Refresh servers") }
            }
            WithTooltip("Close") {
                IconButton(onClick = onClose, modifier = if (firstKey == null) Modifier.focusRequester(firstFocus) else Modifier) {
                    Icon(AllIconsKeys.General.Close, contentDescription = "Close")
                }
            }
        }
        message?.let { text ->
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, color = JewelTheme.globalColors.text.error, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = controller::refresh, modifier = Modifier.semantics { contentDescription = "Retry" }) { Text("Retry") }
            }
        }
        val info = JewelTheme.globalColors.text.info
        if (model.stopped) {
            Text("The session is not running. Send a prompt to start it, then manage its servers.", color = info)
        } else if (model.total == 0 && !refreshing) {
            Text("No MCP servers are configured for this session.", color = info)
        }
        if (model.groups.isNotEmpty()) {
            val listState = rememberLazyListState()
            VerticallyScrollableContainer(listState, Modifier.weight(1f, fill = false)) {
                LazyColumn(state = listState, modifier = Modifier.padding(end = 12.dp)) {
                    model.groups.forEach { group ->
                        item(key = "group:${group.source.name}", contentType = "group") {
                            Text(
                                group.source.title,
                                color = info,
                                style = smallTextStyle(),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                            )
                        }
                        items(group.rows, key = { it.key }, contentType = { "row" }) { row ->
                            McpServerRow(
                                row,
                                controller,
                                busy = busy,
                                wait = waits[row.name],
                                error = rowErrors[row.name],
                                retry = retries[row.name],
                                stopped = model.stopped,
                                focus = if (row.key == firstKey) firstFocus else null,
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // After the first frame, once the list has laid its first rows out.
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
}

/** The key of the row whose first action button takes the focus when the sheet opens; null when none has an enabled one. */
private fun firstActionRow(model: McpPanelModel, busy: McpBusy?, waits: Map<String, McpAuthWait>): String? {
    if (model.stopped || busy != null) return null
    return model.groups.asSequence().flatMap { it.rows }
        .firstOrNull { row ->
            val wait = waits[row.name]
            // A waiting row's one button is its Cancel.
            if (wait != null && wait.outcome == null) true else row.actions.isNotEmpty()
        }
        ?.key
}

/**
 * One server (spec 3.2): its name, status, tools and version; its url or command, or `Tandem's own server`; its error; its actions,
 * or the caption of the request that runs on it, or its sign-in wait with Cancel. Under it, the wait's caption and the row's error
 * with Retry. To assistive technology one node, `<name>, <label>`, beside its buttons.
 */
@Composable
private fun McpServerRow(
    row: McpRowModel,
    controller: McpPanelController,
    busy: McpBusy?,
    wait: McpAuthWait?,
    error: String?,
    retry: McpAction?,
    stopped: Boolean,
    focus: FocusRequester?,
) {
    val info = JewelTheme.globalColors.text.info
    val errorColor = JewelTheme.globalColors.text.error
    val waiting = wait != null && wait.outcome == null
    Column(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp)
            .then(if (stopped) Modifier.alpha(0.5f) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "${row.name}, ${row.label}" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f, fill = false)) {
                        // Config text, drawn verbatim on one line; the full name in the tooltip.
                        WithTooltip(row.name) { Text(row.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Text(listOfNotNull(row.label, row.tools, row.version).joinToString(" · "), color = info, maxLines = 1)
                    row.rawSource?.let { SourcePill(it) }
                }
                if (row.readOnly) {
                    Text("Tandem's own server", color = info, style = smallTextStyle(), maxLines = 1)
                } else {
                    row.detail?.let { Text(it, color = info, style = monoTextStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                row.error?.let { text ->
                    WithTooltip(text) { Text(text, color = errorColor, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (!stopped) {
                when {
                    busy?.name == row.name -> Text(busy.caption, color = info)
                    waiting -> OutlinedButton(
                        onClick = { controller.cancelAuth(row.name) },
                        modifier = described("Cancel ${row.name}", focus),
                    ) { Text("Cancel") }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.actions.forEachIndexed { i, action ->
                            OutlinedButton(
                                onClick = { controller.run(action, row.name) },
                                modifier = described("${action.label} ${row.name}", focus.takeIf { i == 0 }),
                                enabled = busy == null,
                            ) { Text(action.label) }
                        }
                    }
                }
            }
        }
        wait?.let { WaitCaption(it) }
        if (error != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error, color = errorColor, modifier = Modifier.weight(1f))
                if (retry != null && !stopped) {
                    OutlinedButton(
                        onClick = { controller.run(retry, row.name) },
                        modifier = described("Retry ${row.name}", null),
                        enabled = busy == null,
                    ) { Text("Retry") }
                }
            }
        }
    }
}

/** A button's modifier: [description] to assistive technology (`<Action> <name>`, spec 3.7), and [focus] when it has one. */
private fun described(description: String, focus: FocusRequester?): Modifier =
    (if (focus != null) Modifier.focusRequester(focus) else Modifier).semantics { contentDescription = description }

/** A sign-in wait's caption ([authCaption]), its elapsed time ticking each second while the wait runs. */
@Composable
private fun WaitCaption(wait: McpAuthWait) {
    var now by remember(wait) { mutableLongStateOf(System.currentTimeMillis()) }
    if (wait.outcome == null) {
        LaunchedEffect(wait) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val caption = authCaption(wait, now) ?: return
    Text(caption.text, color = if (caption.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.info)
}

/** An `Other` row's wire source, as a plain text pill (base 4.2 pill style, not a focusable `Chip`). */
@Composable
private fun SourcePill(source: String) {
    Text(
        source,
        color = JewelTheme.globalColors.text.info,
        style = smallTextStyle(),
        maxLines = 1,
        modifier = Modifier.background(chatPalette().bubbleFill, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

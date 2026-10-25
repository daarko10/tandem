@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.chat.NoticeRow
import dev.daarkolabs.tandem.chat.ScrollEvent
import dev.daarkolabs.tandem.chat.ShellRow
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.ThinkingRow
import dev.daarkolabs.tandem.chat.ToolKind
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.ToolState
import dev.daarkolabs.tandem.chat.TurnEndRow
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.chat.WorkingRow
import dev.daarkolabs.tandem.ui.dev.RecompositionCounter
import dev.daarkolabs.tandem.ui.dev.RenderingSpike
import dev.daarkolabs.tandem.ui.rows.NoticeItem
import dev.daarkolabs.tandem.ui.rows.ReplyHost
import dev.daarkolabs.tandem.ui.rows.ShellRowItem
import dev.daarkolabs.tandem.ui.rows.TextRowItem
import dev.daarkolabs.tandem.ui.rows.ThinkingRowItem
import dev.daarkolabs.tandem.ui.rows.ToolRowItem
import dev.daarkolabs.tandem.ui.rows.TurnEndItem
import dev.daarkolabs.tandem.ui.rows.UserBubble
import dev.daarkolabs.tandem.ui.rows.WorkingRowItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.styling.ButtonMetrics
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle

/** How much less than the list's height Page Up and Page Down scroll, so a line of the last page stays in view (spec 4.16). */
private val PAGE_OVERLAP = 40.dp

/** The states of an Agent call whose subagent still runs. */
private val AGENT_LIVE: Set<ToolState> = setOf(ToolState.RUNNING, ToolState.WAITING)

/**
 * The chat transcript (spec 4.1, 4.16, 6.0): the presenter's rows in a list keyed by row key, inside the IDE's scrollbar, each row
 * capped to the chat column. Draws ready rows only: building and parsing happen in [ChatPresenter]; what a row remembers on screen,
 * and whether the list follows its bottom, is in [ui]. [lastFrameAt] is when the session's process last wrote a line, for the
 * working row.
 *
 * Following is intent, not position ([dev.daarkolabs.tandem.chat.FollowState]): a user scroll up (wheel, drag, the scrollbar's
 * thumb, Page Up, Home) pauses it, and while it follows, new rows, a growing last row and a change of the list's height keep the
 * bottom in view. A user scroll down that reaches the bottom, End, the `↓` button and a send resume it. The focused transcript
 * scrolls with Page Up, Page Down, Home and End; Esc there gives the composer the focus ([onFocusComposer]). With [reply], the ended
 * turns' replies get their hover rows (polish spec 2.6). A local `/mcp` notice's link opens the MCP panel with [onOpenMcp]; a running
 * shell escape's `Stop command` calls [onStop].
 */
@Composable
internal fun ChatPanel(
    project: Project,
    presenter: ChatPresenter,
    ui: ChatUiState,
    emptyText: String,
    lastFrameAt: StateFlow<Long>,
    onFocusComposer: () -> Unit,
    reply: ReplyHost? = null,
    onOpenMcp: () -> Unit = {},
    onStop: () -> Unit = {},
) {
    val chat by presenter.chat.collectAsState()
    val rows = chat.rows
    val listState = ui.listState
    val scope = rememberCoroutineScope()

    // Only a user's wheel or drag reaches the connection: programmatic scrolls (the pin, the keys, the button) bypass it.
    val connection = remember(ui) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Content moving down (available.y > 0) shows what is above: a scroll up. The sign is open item 6 (runIde).
                if (isUser(source) && available.y > 0f) ui.follow.value = ui.follow.value.on(ScrollEvent.WheelUp)
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // After the delta is applied, so the notch that lands on the bottom resumes at once; 0 px threshold.
                if (isUser(source) && (consumed.y < 0f || available.y < 0f) && !listState.canScrollForward) {
                    ui.follow.value = ui.follow.value.on(ScrollEvent.ReachedBottom)
                }
                return Offset.Zero
            }
        }
    }

    // The scrollbar's thumb drives the list through scrollBy and scrollToItem, never through the connection: its drags come from
    // the scrollbar's own interaction source.
    val thumb = remember { MutableInteractionSource() }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(thumb, ui) {
        thumb.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> dragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    if (!listState.canScrollForward) ui.follow.value = ui.follow.value.on(ScrollEvent.ReachedBottom)
                }
            }
        }
    }
    LaunchedEffect(dragging, ui) {
        if (dragging) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect {
                if (listState.lastScrolledBackward) ui.follow.value = ui.follow.value.on(ScrollEvent.ThumbDragUp)
            }
        }
    }

    // Pins the bottom while following when the session is shown, when a row model changed (a streamed delta grows the last row
    // without adding one) and when the list's height changed (a card docking, the composer growing), but not when only an opened
    // body's Markdown arrived: row expand/collapse never scrolls (spec 4.16). While paused it counts the rows added since the
    // session's list last saw them ([ChatUiState.seen], by key), including those added while another session was shown.
    var viewportHeight by remember { mutableIntStateOf(0) }
    DisposableEffect(ui) {
        onDispose { ui.hidden() }
    }
    LaunchedEffect(chat, viewportHeight) {
        val change = ui.seen(chat.rows, viewportHeight)
        if (ui.follow.value.following) {
            if (change.pin && chat.rows.isNotEmpty()) listState.requestScrollToItem(chat.rows.lastIndex, Int.MAX_VALUE)
        } else if (change.appended != null) {
            ui.follow.value = ui.follow.value.on(ScrollEvent.Appended(change.appended))
        }
    }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = JewelTheme.globalColors.text.info)
        }
        return
    }
    // Whether a running Agent row is open: the working row then leaves the subagent's step to the nested rows (spec 4.9).
    val agentExpanded = rows.any { r ->
        val call = (r.row as? ToolRow)?.calls?.singleOrNull()
        call != null && call.kind == ToolKind.AGENT && call.state in AGENT_LIVE && ui.expanded(r.row.key).value
    }
    val density = LocalDensity.current
    val currentOnFocusComposer by rememberUpdatedState(onFocusComposer)
    val listFocus = remember { FocusRequester() }
    TandemMarkdownStyling(project) {
        VerticallyScrollableContainer(listState, Modifier.fillMaxSize(), scrollbarInteractionSource = thumb) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .nestedScroll(connection)
                    .onSizeChanged { viewportHeight = it.height }
                    .semantics { contentDescription = "Transcript" }
                    .focusRequester(listFocus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val action = scrollKey(event.key, event.type)
                            ?: return@onPreviewKeyEvent false
                        if (action == ScrollAction.FOCUS_COMPOSER) {
                            currentOnFocusComposer()
                        } else {
                            val page = listState.layoutInfo.viewportSize.height - with(density) { PAGE_OVERLAP.toPx() }
                            applyScrollAction(action, listState, page.coerceAtLeast(0f), ui, scope)
                        }
                        true
                    }
                    // A click anywhere in the transcript focuses it (focusable alone is reached by Tab only); the Initial pass
                    // leaves the rows' own clicks working.
                    .pointerInput(listFocus) {
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            listFocus.requestFocus()
                        }
                    },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                items(rows, key = { it.row.key }, contentType = { it.row::class }) { RowItem(it, ui, lastFrameAt, agentExpanded, reply, onOpenMcp, onStop) }
            }
            val buttonText = ui.follow.value.buttonText
            if (buttonText != null && listState.canScrollForward) {
                JumpToBottom(buttonText, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                    scope.launch {
                        val last = listState.layoutInfo.totalItemsCount - 1
                        if (last >= 0) listState.animateScrollToItem(last)
                        ui.follow.value = ui.follow.value.on(ScrollEvent.Jump)
                        if (last >= 0) listState.requestScrollToItem(last, Int.MAX_VALUE)
                    }
                }
            }
        }
    }
}

/**
 * Whether a scroll came from the user's wheel or drag rather than from code. In this Compose `Wheel` and `Drag` are deprecated
 * aliases of `UserInput` (spec 6's `|| source == Wheel` is the same test), so `UserInput` alone covers both.
 */
private fun isUser(source: NestedScrollSource): Boolean = source == NestedScrollSource.UserInput

/**
 * Scrolls [listState] as [action] says, by [page] pixels for Page Up and Page Down, and tells [ui]'s follow state: Page Up and Home
 * pause before they move (so a pin cannot pull the list back), End resumes and animates to the bottom like the `↓` pill (spec 4.16
 * Jump), Page Down resumes when it landed on the bottom.
 */
private fun applyScrollAction(action: ScrollAction, listState: LazyListState, page: Float, ui: ChatUiState, scope: CoroutineScope) {
    fun follow(atBottomAfter: Boolean) {
        action.followEvent(atBottomAfter)?.let { ui.follow.value = ui.follow.value.on(it) }
    }
    val last = listState.layoutInfo.totalItemsCount - 1
    when (action) {
        ScrollAction.PAGE_UP -> {
            follow(false)
            scope.launch { listState.animateScrollBy(-page) }
        }
        ScrollAction.HOME -> {
            follow(false)
            scope.launch { listState.scrollToItem(0) }
        }
        ScrollAction.PAGE_DOWN -> scope.launch {
            listState.animateScrollBy(page)
            follow(!listState.canScrollForward)
        }
        ScrollAction.END -> {
            follow(true)
            if (last >= 0) {
                scope.launch {
                    listState.animateScrollToItem(last)
                    listState.requestScrollToItem(last, Int.MAX_VALUE)
                }
            }
        }
        ScrollAction.FOCUS_COMPOSER -> Unit
    }
}

/** The `↓` pill (spec 4.16): `↓ 3 new` with the rows appended while paused, `↓` with none; it jumps to the bottom and follows. */
@Composable
private fun JumpToBottom(text: String, modifier: Modifier, onClick: () -> Unit) {
    val base = JewelTheme.outlinedButtonStyle
    // The theme's outlined button with fully rounded ends.
    val pill = remember(base) {
        val metrics = base.metrics
        ButtonStyle(
            base.colors,
            ButtonMetrics(CornerSize(50), metrics.padding, metrics.minSize, metrics.borderWidth, metrics.focusOutlineExpand),
            base.focusOutlineAlignment,
        )
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(28.dp).testTag("jump-to-bottom").semantics { contentDescription = "Jump to bottom" },
        style = pill,
    ) {
        Text(text)
    }
}

/**
 * One row of the list, by its kind. [lastFrameAt] and [agentExpanded] (a running Agent row is open) are for the working row only,
 * [reply] for a text row's hover row, [onOpenMcp] for a notice's `Open the MCP panel` link, [onStop] for a shell row's `Stop command`.
 */
@Composable
internal fun RowItem(
    rendered: RenderedRow,
    ui: ChatUiState,
    lastFrameAt: StateFlow<Long>,
    agentExpanded: Boolean = false,
    reply: ReplyHost? = null,
    onOpenMcp: () -> Unit = {},
    onStop: () -> Unit = {},
) {
    if (RenderingSpike.enabled) SideEffect { RecompositionCounter.hit(rendered.row.key) }
    when (val row = rendered.row) {
        is UserRow -> UserBubble(rendered)
        is ShellRow -> ShellRowItem(row, ui, onStop)
        is TextRow -> TextRowItem(rendered, reply)
        is ToolRow -> ToolRowItem(rendered, ui)
        is NoticeRow -> NoticeItem(row, onOpenMcp)
        is TurnEndRow -> TurnEndItem(row)
        is ThinkingRow -> ThinkingRowItem(row, ui)
        is WorkingRow -> WorkingRowItem(row, lastFrameAt, agentExpanded)
    }
}

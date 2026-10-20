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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.key.KeyEvent
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
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.ThinkingRow
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.TurnEndRow
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.chat.WorkingRow
import dev.daarkolabs.tandem.ui.dev.RecompositionCounter
import dev.daarkolabs.tandem.ui.dev.RenderingSpike
import dev.daarkolabs.tandem.ui.rows.NoticeItem
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
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/** How much less than the list's height Page Up and Page Down scroll, so a line of the last page stays in view (spec 4.16). */
private val PAGE_OVERLAP = 40.dp

/**
 * The chat transcript (spec 4.1, 4.16, 6.0): the presenter's rows in a list keyed by row key, inside the IDE's scrollbar, each row
 * capped to the chat column. Draws ready rows only: building and parsing happen in [ChatPresenter]; what a row remembers on screen,
 * and whether the list follows its bottom, is in [ui]. [lastFrameAt] is when the session's process last wrote a line, for the
 * working row.
 *
 * Following is intent, not position ([dev.daarkolabs.tandem.chat.FollowState]): a user scroll up (wheel, drag, the scrollbar's
 * thumb, Page Up, Home) pauses it, and while it follows, new rows, a growing last row and a change of the list's height keep the
 * bottom in view. A user scroll down that reaches the bottom, End, the `↓` button and a send resume it. The focused transcript
 * scrolls with Page Up, Page Down, Home and End; Esc there gives the composer the focus ([onFocusComposer]).
 */
@Composable
internal fun ChatPanel(
    project: Project,
    presenter: ChatPresenter,
    ui: ChatUiState,
    emptyText: String,
    lastFrameAt: StateFlow<Long>,
    onFocusComposer: () -> Unit,
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

    // Pins the bottom while following, on every emission (a streamed delta grows the last row without adding one) and on every
    // change of the list's height (a card docking, the composer growing). While paused it counts the rows added since, by key, so
    // grouping (a lone call joining a group shrinks the list) never miscounts. The first emission the panel sees only records the
    // keys: a session shown again while paused has nothing new.
    val previousKeys = remember(ui) { HashSet<String>() }
    var previousLast by remember(ui) { mutableStateOf<RenderedRow?>(null) }
    var seen by remember(ui) { mutableStateOf(false) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(chat, viewportHeight) {
        val added = chat.rows.count { it.row.key !in previousKeys }
        val tailChanged = chat.rows.lastOrNull() !== previousLast
        previousKeys.clear()
        chat.rows.forEach { previousKeys += it.row.key }
        previousLast = chat.rows.lastOrNull()
        val first = !seen
        seen = true
        if (ui.follow.value.following) {
            if (chat.rows.isNotEmpty()) listState.requestScrollToItem(chat.rows.lastIndex, Int.MAX_VALUE)
        } else if (!first && (added > 0 || tailChanged)) {
            ui.follow.value = ui.follow.value.on(ScrollEvent.Appended(added))
        }
    }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = JewelTheme.globalColors.text.info)
        }
        return
    }
    val density = LocalDensity.current
    val currentOnFocusComposer by rememberUpdatedState(onFocusComposer)
    val listFocus = remember { FocusRequester() }
    ProvideMarkdownStyling(project) {
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
                        val action = scrollKey(event.key, event.type, event.isCtrlPressed || event.isMetaPressed)
                            ?: return@onPreviewKeyEvent false
                        if (action == ScrollAction.FOCUS_COMPOSER) {
                            currentOnFocusComposer()
                        } else {
                            val page = listState.layoutInfo.viewportSize.height - with(density) { PAGE_OVERLAP.toPx() }
                            scrollBy(action, listState, page.coerceAtLeast(0f), ui, scope)
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
                items(rows, key = { it.row.key }, contentType = { it.row::class }) { RowItem(it, ui, lastFrameAt) }
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

/** Whether a scroll came from the user's wheel or drag rather than from code. */
private fun isUser(source: NestedScrollSource): Boolean = source == NestedScrollSource.UserInput || source == NestedScrollSource.Wheel

/**
 * Scrolls [listState] as [action] says, by [page] pixels for Page Up and Page Down, and tells [ui]'s follow state: Page Up and Home
 * pause before they move (so a pin cannot pull the list back), End resumes, Page Down resumes when it landed on the bottom.
 */
private fun scrollBy(action: ScrollAction, listState: LazyListState, page: Float, ui: ChatUiState, scope: CoroutineScope) {
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
            if (last >= 0) scope.launch { listState.scrollToItem(last, Int.MAX_VALUE) }
        }
        ScrollAction.FOCUS_COMPOSER -> Unit
    }
}

/** The `↓` pill (spec 4.16): `↓ 3 new` with the rows appended while paused, `↓` with none; it jumps to the bottom and follows. */
@Composable
private fun JumpToBottom(text: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(28.dp).testTag("jump-to-bottom").semantics { contentDescription = "Jump to bottom" },
    ) {
        Text(text)
    }
}

/** One row of the list, by its kind. [lastFrameAt] is for the working row only. */
@Composable
internal fun RowItem(rendered: RenderedRow, ui: ChatUiState, lastFrameAt: StateFlow<Long>) {
    if (RenderingSpike.enabled) SideEffect { RecompositionCounter.hit(rendered.row.key) }
    when (val row = rendered.row) {
        is UserRow -> UserBubble(row)
        is TextRow -> TextRowItem(rendered)
        is ToolRow -> ToolRowItem(rendered, ui)
        is NoticeRow -> NoticeItem(row)
        is TurnEndRow -> TurnEndItem(row)
        is ThinkingRow -> ThinkingRowItem(row, ui)
        is WorkingRow -> WorkingRowItem(row, lastFrameAt)
    }
}

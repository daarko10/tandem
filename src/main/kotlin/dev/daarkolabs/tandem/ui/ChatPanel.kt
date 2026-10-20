@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.chat.NoticeRow
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
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * The chat transcript (spec 4.1, 6.0): the presenter's rows in a list keyed by row key, inside the IDE's scrollbar, each row capped
 * to the chat column. Draws ready rows only: building and parsing happen in [ChatPresenter]; what a row remembers on screen is in
 * [ui]. [lastFrameAt] is when the session's process last wrote a line, for the working row.
 */
@Composable
internal fun ChatPanel(project: Project, presenter: ChatPresenter, ui: ChatUiState, emptyText: String, lastFrameAt: StateFlow<Long>) {
    val chat by presenter.chat.collectAsState()
    val rows = chat.rows

    // A stand-in until T9's intent-based autoscroll (spec 4.16): scrolls to the bottom when the panel is shown, when rows are added
    // and when one of the last two rows changes (a reply streaming above the working row). Keyed on row models only, so filling an
    // opened body's Markdown (expandBody) or a change of the card never moves the list.
    val tail = rows.takeLast(2).map { it.row }
    LaunchedEffect(rows.size, tail) {
        if (rows.isNotEmpty()) ui.listState.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
    }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = JewelTheme.globalColors.text.info)
        }
        return
    }
    ProvideMarkdownStyling(project) {
        VerticallyScrollableContainer(ui.listState, Modifier.fillMaxSize()) {
            LazyColumn(
                state = ui.listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                items(rows, key = { it.row.key }, contentType = { it.row::class }) { RowItem(it, ui, lastFrameAt) }
            }
        }
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

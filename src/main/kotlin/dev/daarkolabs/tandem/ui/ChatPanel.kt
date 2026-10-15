@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.Text

/** The chat transcript. Renders ready [ChatRow]s only; parsing happens in [ChatRowsPresenter]. */
@Composable
internal fun ChatPanel(project: Project, rows: StateFlow<List<ChatRow>>, emptyText: String) {
    val items by rows.collectAsState()
    val listState = rememberLazyListState()

    // Follow the tail while it grows.
    val tail = items.lastOrNull()
    LaunchedEffect(items.size, tail?.markdown?.length) {
        if (items.isNotEmpty()) listState.scrollToItem(items.lastIndex, Int.MAX_VALUE)
    }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emptyText) }
        return
    }
    ProvideMarkdownStyling(project) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }, contentType = { it.role }) { row -> MessageRow(row) }
        }
    }
}

@Composable
private fun MessageRow(row: ChatRow) {
    val shape = RoundedCornerShape(6.dp)
    val modifier = when (row.role) {
        ChatRole.USER -> Modifier.background(JewelTheme.globalColors.borders.disabled, shape).padding(8.dp)
        ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.NOTICE -> Modifier
    }
    Column(Modifier.fillMaxWidth().then(modifier)) {
        val label = when (row.role) {
            ChatRole.USER -> "You"
            ChatRole.ASSISTANT -> if (row.streaming) "Claude ▍" else "Claude"
            ChatRole.TOOL -> "Tool result"
            ChatRole.NOTICE -> "Tandem"
        }
        Text(label, fontWeight = FontWeight.Bold, color = JewelTheme.globalColors.text.info)
        Markdown(row.blocks, row.markdown, modifier = Modifier.fillMaxWidth(), selectable = true)
    }
}

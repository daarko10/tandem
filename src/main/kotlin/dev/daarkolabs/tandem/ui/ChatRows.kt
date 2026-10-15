@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor

/** A chat message as the UI needs it: Markdown text plus the blocks parsed from it. */
data class ChatRow(val id: String, val role: ChatRole, val markdown: String, val blocks: List<MarkdownBlock>, val streaming: Boolean)

enum class ChatRole { USER, ASSISTANT, TOOL, NOTICE }

/** Plain transcript entry, before Markdown parsing. */
data class TranscriptEntry(val id: String, val role: ChatRole, val markdown: String, val streaming: Boolean)

/**
 * Parses transcript snapshots into [ChatRow]s on a background dispatcher, so the EDT only lays out
 * ready blocks. Finished messages are parsed once and cached; only the streaming tail is re-parsed,
 * and intermediate snapshots are dropped when parsing falls behind.
 */
class ChatRowsPresenter(scope: CoroutineScope, transcript: Flow<List<TranscriptEntry>>) {
    private val processor = MarkdownProcessor(listOf(AutolinkProcessorExtension))
    private val cache = HashMap<String, ChatRow>()
    private val _rows = MutableStateFlow<List<ChatRow>>(emptyList())
    val rows: StateFlow<List<ChatRow>> = _rows.asStateFlow()

    init {
        scope.launch(Dispatchers.Default) {
            transcript.conflate().collect { entries -> _rows.value = entries.map(::toRow) }
        }
    }

    private fun toRow(entry: TranscriptEntry): ChatRow {
        val cached = cache[entry.id]
        if (cached != null && cached.markdown == entry.markdown && cached.streaming == entry.streaming) return cached
        val row = ChatRow(entry.id, entry.role, entry.markdown, processor.processMarkdownDocument(entry.markdown), entry.streaming)
        cache[entry.id] = row
        return row
    }
}

@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.ChatRow
import dev.daarkolabs.tandem.chat.ChatRowsBuilder
import dev.daarkolabs.tandem.chat.ComposerModel
import dev.daarkolabs.tandem.chat.PendingCard
import dev.daarkolabs.tandem.chat.PlanCard
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.ToolBody
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.composerModel
import dev.daarkolabs.tandem.session.ChatState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor

/**
 * A chat row as the list draws it (spec 5.6): the [row], the parsed Markdown of a [TextRow] in [blocks], and in [bodies] the parsed
 * Markdown of the opened bodies of a [ToolRow] ([ToolBody.Markdown], [ToolBody.Links]), by the row's key for a lone call or by the
 * sub-row key `c:<call id>` for a call of a group.
 */
internal data class RenderedRow(
    val row: ChatRow,
    val blocks: ImmutableList<MarkdownBlock>?,
    val bodies: ImmutableMap<String, ImmutableList<MarkdownBlock>> = persistentMapOf(),
)

/** The chat list's rows, the pending request's card, how many more requests wait, and a plan card's plan, parsed. */
internal data class RenderedChat(
    val rows: ImmutableList<RenderedRow>,
    val card: PendingCard?,
    val moreWaiting: Int,
    val planBlocks: ImmutableList<MarkdownBlock>?,
)

/**
 * Turns a session's [state] into [chat], ready to draw, and into the composer's model, off the EDT (spec 5.6, 9): rows come from a
 * [ChatRowsBuilder] on [Dispatchers.Default], intermediate states are dropped when building falls behind, and Markdown is parsed
 * there too, once per row key and text, then kept until the row goes away. A tool body's Markdown is parsed only once the row is
 * opened, on [expandBody]. A new [options] value takes a new builder and keeps the parsed Markdown.
 *
 * [parse] replaces the Markdown parser: tests watch where and how often it runs.
 */
internal class ChatPresenter(
    scope: CoroutineScope,
    state: StateFlow<ChatState>,
    options: StateFlow<RowOptions>,
    parse: ((String) -> ImmutableList<MarkdownBlock>)? = null,
) {
    // One processor with the autolink extension, so bare URLs stay links (spec 4.0, 4.4).
    private val processor = MarkdownProcessor(listOf(AutolinkProcessorExtension))
    private val parseMarkdown: (String) -> ImmutableList<MarkdownBlock> = parse ?: { processor.processMarkdownDocument(it).toImmutableList() }

    /** The keys of the bodies opened so far: their Markdown is parsed while their row exists. */
    private val requested = MutableStateFlow<PersistentSet<String>>(persistentSetOf())

    private val rendered = MutableStateFlow(RenderedChat(persistentListOf(), null, 0, null))

    val chat: StateFlow<RenderedChat> = rendered.asStateFlow()

    /** What the composer shows; it changes only when one of its own fields does, not on every streamed delta. */
    val composer: StateFlow<ComposerModel> = state.map(::composerModel).distinctUntilChanged().flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, composerModel(state.value))

    // Touched by the rendering coroutine only.
    private var builder: ChatRowsBuilder? = null
    private var builderOptions: RowOptions? = null
    private val parsed = HashMap<String, Parsed>()
    private var previous: Map<String, RenderedRow> = emptyMap()

    private class Parsed(val markdown: String, val blocks: ImmutableList<MarkdownBlock>)

    private class Input(val state: ChatState, val options: RowOptions, val requested: Set<String>)

    init {
        scope.launch(Dispatchers.Default) {
            combine(state, options, requested, ::Input).conflate().collect { rendered.value = render(it) }
        }
    }

    /**
     * Asks for the parsed Markdown of the body keyed [key] (a row key, or a sub-row key `c:<call id>`); the next [chat] has it in the
     * row's [RenderedRow.bodies]. Never waits and never parses on the calling thread; asking again for an opened body does nothing.
     */
    fun expandBody(key: String) {
        requested.update { it.add(key) }
    }

    private fun render(input: Input): RenderedChat {
        val rows = (builder?.takeIf { builderOptions == input.options } ?: ChatRowsBuilder(input.options).also {
            builder = it
            builderOptions = input.options
        }).build(input.state)
        val used = HashSet<String>()
        val out = rows.rows.map { renderRow(it, input.requested, used) }.toImmutableList()
        val card = rows.card
        val planBlocks = (card as? PlanCard)?.plan?.let { blocks("plan:${card.requestId}", it, used) }
        parsed.keys.retainAll(used)
        previous = out.associateBy { it.row.key }
        return RenderedChat(out, card, rows.moreWaiting, planBlocks)
    }

    /** [row] with its Markdown; the previous instance when nothing it shows changed, so that its item is skipped. */
    private fun renderRow(row: ChatRow, requested: Set<String>, used: MutableSet<String>): RenderedRow {
        val old = previous[row.key]
        return when (row) {
            is TextRow -> {
                if (old != null && old.row == row) {
                    used += row.key
                    old
                } else {
                    RenderedRow(row, blocks(row.key, row.markdown, used))
                }
            }
            is ToolRow -> {
                val bodies = bodies(row, requested, used)
                if (old != null && old.row == row && old.bodies == bodies) old else RenderedRow(row, null, bodies)
            }
            else -> if (old != null && old.row == row) old else RenderedRow(row, null)
        }
    }

    /** The parsed bodies of [row]'s opened calls: the row's own for a lone call, each call's `c:<call id>` in a group. */
    private fun bodies(row: ToolRow, requested: Set<String>, used: MutableSet<String>): ImmutableMap<String, ImmutableList<MarkdownBlock>> {
        if (requested.isEmpty()) return persistentMapOf()
        var bodies = persistentMapOf<String, ImmutableList<MarkdownBlock>>()
        row.calls.forEach { call ->
            val key = if (row.calls.size == 1) row.key else "c:${call.id}"
            val markdown = markdownOf(call.body)
            if (markdown != null && key in requested) bodies = bodies.put(key, blocks(key, markdown, used))
        }
        return bodies
    }

    /** [markdown] parsed, from the cache when the row keyed [key] had the same text last time. */
    private fun blocks(key: String, markdown: String, used: MutableSet<String>): ImmutableList<MarkdownBlock> {
        used += key
        parsed[key]?.takeIf { it.markdown == markdown }?.let { return it.blocks }
        val blocks = parseMarkdown(markdown)
        parsed[key] = Parsed(markdown, blocks)
        return blocks
    }

    private fun markdownOf(body: ToolBody): String? = when (body) {
        is ToolBody.Markdown -> body.markdown
        is ToolBody.Links -> body.markdown
        else -> null
    }
}

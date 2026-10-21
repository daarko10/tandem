@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.chat.ChatRow
import dev.daarkolabs.tandem.chat.ChatRows
import dev.daarkolabs.tandem.chat.ChatRowsBuilder
import dev.daarkolabs.tandem.chat.ComposerModel
import dev.daarkolabs.tandem.chat.NoticeRow
import dev.daarkolabs.tandem.chat.PendingCard
import dev.daarkolabs.tandem.chat.PlanCard
import dev.daarkolabs.tandem.chat.RowGap
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.ToolBody
import dev.daarkolabs.tandem.chat.ToolKind
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.composerModel
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.NoticeLevel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.MarkdownBlock

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
 * opened, on [expandBody]: one collector merges those requests with the states, so none is dropped. A new [options] value takes a
 * new builder and keeps the parsed Markdown. A state that fails to render shows the last rows that rendered with an ERROR notice
 * after them that names the exception's class (hard rule 5), and is logged once (that class, never row text); the next state
 * renders again, with a new builder, and takes the notice away when it succeeds.
 *
 * [parse] replaces the Markdown parser: tests watch where and how often it runs. [buildRows] replaces the call to the builder and
 * [logFailure] the logging of a failed render: tests make a state fail.
 */
internal class ChatPresenter(
    scope: CoroutineScope,
    state: StateFlow<ChatState>,
    options: StateFlow<RowOptions>,
    parse: ((String) -> ImmutableList<MarkdownBlock>)? = null,
    private val buildRows: (ChatRowsBuilder, ChatState) -> ChatRows = ChatRowsBuilder::build,
    private val logFailure: (Throwable) -> Unit = { LOG.error(it) },
) {
    // One processor per presenter: bare URLs stay links, GFM tables and strikethrough parse (spec 4.0, 4.4).
    private val processor = tandemMarkdownProcessor()
    private val parseMarkdown: (String) -> ImmutableList<MarkdownBlock> = parse ?: { processor.processMarkdownDocument(it).toImmutableList() }

    /** Body keys from [expandBody], read by the rendering coroutine only. */
    private val expandRequests = Channel<String>(Channel.UNLIMITED)

    private val rendered = MutableStateFlow(RenderedChat(persistentListOf(), null, 0, null))

    val chat: StateFlow<RenderedChat> = rendered.asStateFlow()

    /** What the composer shows; it changes only when one of its own fields does, not on every streamed delta. */
    val composer: StateFlow<ComposerModel> = state.map(::composerModel).distinctUntilChanged().flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, composerModel(state.value))

    // Touched by the rendering coroutine only.
    /**
     * The keys of the bodies opened so far: their Markdown is parsed while their row exists, and they go with it. An opened Agent
     * row's key also has its subagent's text rows and Markdown bodies parsed, as they arrive.
     */
    private val requested = HashSet<String>()
    private var builder: ChatRowsBuilder? = null
    private var builderOptions: RowOptions? = null
    private val parsed = HashMap<String, Parsed>()
    private var failed = false

    /** The last chat that rendered, and its rows: an unchanged row keeps its rendered row ([render]). */
    private var good: RenderedChat = rendered.value
    private var goodRows: PersistentList<RenderedRow> = persistentListOf()

    private class Parsed(val markdown: String, val blocks: ImmutableList<MarkdownBlock>)

    private class Input(val state: ChatState, val options: RowOptions)

    init {
        scope.launch(Dispatchers.Default) {
            // 5.6's merge of the states and the requests, as a select: states are conflated while a render runs, requests are not.
            // `select` prefers its first clause, so the requests come first: they are rare and each is taken once, so they never
            // starve the states, while a stream that always has a new state ready would starve a request placed second.
            val inputs = combine(state, options, ::Input).conflate().produceIn(this)
            var input = inputs.receive()
            var stale = true
            while (true) {
                if (stale) renderSafely(input)
                stale = select {
                    expandRequests.onReceive { key -> requested.add(key) }
                    inputs.onReceive {
                        input = it
                        true
                    }
                }
            }
        }
    }

    /**
     * Asks for the parsed Markdown of the body keyed [key] (a row key, or a sub-row key `c:<call id>`); the next [chat] has it in the
     * row's [RenderedRow.bodies]. Never waits and never parses on the calling thread; asking again for an opened body does nothing.
     */
    fun expandBody(key: String) {
        expandRequests.trySend(key)
    }

    /**
     * [render] into [chat]; a failure shows the last rows that rendered and the [failure] notice, and is logged once until a render
     * succeeds again.
     */
    private fun renderSafely(input: Input) {
        try {
            good = render(input)
            rendered.value = good
            failed = false
        } catch (e: Throwable) {
            // An Error is caught too (a StackOverflowError from the recursive Markdown parser on deeply nested input), so one bad
            // state cannot end the collector; cancellation, the platform's control flow and the VM's own failures pass through.
            if (e is CancellationException || e is ControlFlowException || (e is VirtualMachineError && e !is StackOverflowError)) throw e
            // The builder may have stopped halfway through its caches: the next state starts from a new one.
            builder = null
            rendered.value = failure(e)
            if (!failed) {
                failed = true
                logFailure(RenderFailure(e, input.state.entries.size))
            }
        }
    }

    /**
     * The last chat that rendered with an ERROR notice after its rows, so that a chat that stops updating says so (hard rule 5). It
     * names [e]'s class, never its message, which may quote row text.
     */
    private fun failure(e: Throwable): RenderedChat {
        val text = "Tandem could not build the chat rows (${e.javaClass.name}), so the latest messages may be missing. See the IDE log."
        val notice = NoticeRow(RENDER_FAILURE_KEY, NoticeLevel.ERROR, text, if (goodRows.isEmpty()) RowGap.NONE else RowGap.SECTION)
        return good.copy(rows = goodRows.add(RenderedRow(notice, null)))
    }

    /**
     * The rows with their Markdown (spec 5.6). A parsed key stays cached, and is attached again on every render, while it names a
     * row, a group's sub-row `c:<call id>` or a subagent's row (at any depth) of the latest rows, or the open plan card's plan; the
     * rest of the cache goes, and so do the opened keys whose rows went.
     */
    private fun render(input: Input): RenderedChat {
        val rows = buildRows(
            builder?.takeIf { builderOptions == input.options } ?: ChatRowsBuilder(input.options).also {
                builder = it
                builderOptions = input.options
            },
            input.state,
        )
        val previous = goodRows.associateBy { it.row.key }
        val live = HashSet<String>()
        val out = rows.rows.mapTo(persistentListOf<RenderedRow>().builder()) { renderRow(it, previous, live) }.build()
        requested.retainAll(live)
        val card = rows.card
        val planBlocks = (card as? PlanCard)?.plan?.let { plan ->
            val key = "plan:${card.requestId}"
            live += key
            blocks(key, plan)
        }
        parsed.keys.retainAll(live)
        goodRows = out
        return RenderedChat(out, card, rows.moreWaiting, planBlocks)
    }

    /**
     * [row] with its Markdown, its keys added to [live]; the [previous] instance of its key when nothing it shows changed, so that its
     * item is skipped.
     */
    private fun renderRow(row: ChatRow, previous: Map<String, RenderedRow>, live: MutableSet<String>): RenderedRow {
        val old = previous[row.key]
        live += row.key
        return when (row) {
            is TextRow -> if (old != null && old.row == row) old else RenderedRow(row, blocks(row.key, row.markdown))
            is ToolRow -> {
                var bodies = persistentMapOf<String, ImmutableList<MarkdownBlock>>()
                toolBodies(row, parse = false, live) { key, blocks -> bodies = bodies.put(key, blocks) }
                if (old != null && old.row == row && old.bodies == bodies) old else RenderedRow(row, null, bodies)
            }
            else -> if (old != null && old.row == row) old else RenderedRow(row, null)
        }
    }

    /**
     * Hands [put] the parsed Markdown of [row]'s calls, by the row's key for a lone call and by `c:<call id>` in a group: parsed when
     * the key was opened or [parse] says so (the row is a child of an opened Agent row), else from the cache when it holds the same
     * text. An Agent call's subagent rows follow, parsed when the Agent's key was opened. Adds every key to [live].
     */
    private fun toolBodies(row: ToolRow, parse: Boolean, live: MutableSet<String>, put: (String, ImmutableList<MarkdownBlock>) -> Unit) {
        row.calls.forEach { call ->
            val key = if (row.calls.size == 1) row.key else "c:${call.id}"
            live += key
            val opened = parse || key in requested
            markdownOf(call.body)?.let { markdown -> body(key, markdown, opened)?.let { put(key, it) } }
            if (call.kind == ToolKind.AGENT) {
                // The subagent's rows, parsed once this call's own key is opened: its text and its rows' Markdown bodies.
                val agentOpened = key in requested
                call.children.forEach { child ->
                    live += child.key
                    when (child) {
                        is TextRow -> body(child.key, child.markdown, agentOpened)?.let { put(child.key, it) }
                        is ToolRow -> toolBodies(child, agentOpened, live, put)
                        else -> Unit
                    }
                }
            }
        }
    }

    /** [markdown] of the body keyed [key]: parsed (or from the cache) when [parse], else from the cache only when it is the same text. */
    private fun body(key: String, markdown: String, parse: Boolean): ImmutableList<MarkdownBlock>? =
        if (parse) blocks(key, markdown) else parsed[key]?.takeIf { it.markdown == markdown }?.blocks

    /** [markdown] parsed, from the cache when the row keyed [key] had the same text last time. */
    private fun blocks(key: String, markdown: String): ImmutableList<MarkdownBlock> {
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

    private companion object {
        val LOG: Logger = logger<ChatPresenter>()
    }
}

/** The key of the notice a failed render shows after the last rows that rendered ([ChatPresenter]). */
internal const val RENDER_FAILURE_KEY: String = "n:render-failure"

/**
 * A render failure as logged: [cause]'s class and stack, never its message, which may quote row text (CLAUDE.md conventions), and
 * how many entries the session had.
 */
private class RenderFailure(cause: Throwable, entries: Int) :
    RuntimeException("Chat rows failed to render: ${cause.javaClass.name} (entries=$entries)") {
    init {
        stackTrace = cause.stackTrace
    }
}

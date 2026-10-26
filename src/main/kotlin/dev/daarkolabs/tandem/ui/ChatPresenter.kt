@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.chat.composerModel
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.CommandCatalogue
import dev.daarkolabs.tandem.session.ContextUsage
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.UserImage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.Base64
import kotlin.math.roundToInt

/**
 * A chat row as the list draws it (spec 5.6): the [row], the parsed Markdown of a [TextRow] in [blocks], and in [bodies] the parsed
 * Markdown of the opened bodies of a [ToolRow] ([ToolBody.Markdown], [ToolBody.Links]), by the row's key for a lone call or by the
 * sub-row key `c:<call id>` for a call of a group. [images] holds the decoded thumbnails of a [UserRow]'s images, by image id, once
 * decoded (polish spec 2.7, 3.4).
 */
internal data class RenderedRow(
    val row: ChatRow,
    val blocks: ImmutableList<MarkdownBlock>?,
    val bodies: ImmutableMap<String, ImmutableList<MarkdownBlock>> = persistentMapOf(),
    val images: ImmutableMap<String, ImageBitmap> = persistentMapOf(),
)

/**
 * The chat list's rows, the pending request's card, how many more requests wait, a plan card's plan, parsed, and the entry id of the
 * last ended turn's reply ([ChatRows.latestReply]).
 */
internal data class RenderedChat(
    val rows: ImmutableList<RenderedRow>,
    val card: PendingCard?,
    val moreWaiting: Int,
    val planBlocks: ImmutableList<MarkdownBlock>?,
    val latestReply: String? = null,
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
 * A user's images are decoded asynchronously (polish spec 3.4): a render never decodes. Each image of a [UserRow] without a cached
 * thumbnail gets a child coroutine on [Dispatchers.Default], two at a time, which [decode]s the [UserImage] of that id found among the
 * state's prompts; the result goes back to the rendering coroutine, which caches it by id and renders the row again with it. Until
 * then the row renders without it, and the bubble draws a placeholder. An image that cannot be decoded (data redacted to `<base64>`)
 * stays without a thumbnail and is not decoded again. A cached thumbnail goes when its row does.
 *
 * The composer's models are the session's own, else [models], the ones another process listed ([composerModel]); its commands the
 * session's own, else [commands], the ones another process offered.
 *
 * [parse] replaces the Markdown parser: tests watch where and how often it runs. [buildRows] replaces the call to the builder and
 * [logFailure] the logging of a failed render: tests make a state fail. [decode] replaces [decodeUserImage]: tests count and gate it.
 */
internal class ChatPresenter(
    private val scope: CoroutineScope,
    state: StateFlow<ChatState>,
    options: StateFlow<RowOptions>,
    parse: ((String) -> ImmutableList<MarkdownBlock>)? = null,
    private val buildRows: (ChatRowsBuilder, ChatState) -> ChatRows = ChatRowsBuilder::build,
    private val logFailure: (Throwable) -> Unit = { LOG.error(it) },
    decode: ((UserImage) -> ImageBitmap?)? = null,
    models: StateFlow<List<ModelOption>> = MutableStateFlow(emptyList()),
    commands: StateFlow<CommandCatalogue> = MutableStateFlow(CommandCatalogue()),
) {
    // One processor per presenter: bare URLs stay links, GFM tables and strikethrough parse (spec 4.0, 4.4).
    private val processor = tandemMarkdownProcessor()
    private val parseMarkdown: (String) -> ImmutableList<MarkdownBlock> = parse ?: { processor.processMarkdownDocument(it).toImmutableList() }

    /** Body keys from [expandBody], read by the rendering coroutine only. */
    private val expandRequests = Channel<String>(Channel.UNLIMITED)

    private val decodeImage: (UserImage) -> ImageBitmap? = decode ?: ::decodeUserImage

    /** Thumbnails decoded by the image coroutines, by image id (null: not decodable), read by the rendering coroutine only. */
    private val decodedImages = Channel<Pair<String, ImageBitmap?>>(Channel.UNLIMITED)

    private val rendered = MutableStateFlow(RenderedChat(persistentListOf(), null, 0, null))

    val chat: StateFlow<RenderedChat> = rendered.asStateFlow()

    /** What the composer shows; it changes only when one of its own fields does, not on every streamed delta. */
    val composer: StateFlow<ComposerModel> = combine(state, models, commands, ::composerModel)
        .distinctUntilChanged().flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, composerModel(state.value, models.value, commands.value))

    /**
     * How full the context window is, for the footer's ring (polish spec 2.3): it changes on a main-thread `message_start` and a
     * `result`, and only [ContextRing] collects it, so nothing else recomposes for it.
     */
    val context: StateFlow<ContextUsage?> = state.map { it.context }.distinctUntilChanged().flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, state.value.context)

    /**
     * The entry id of the last ended turn's reply, the one `Fork from here` forks (polish spec 2.6). Only the hover rows read it, so a
     * turn's start or end recomposes them and never a text row.
     */
    val latestReply: StateFlow<String?> = chat.map { it.latestReply }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /** Whether a turn runs, for the hover rows' fork state (polish spec 2.6). */
    val busy: StateFlow<Boolean> = composer.map { it.busy }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, composer.value.busy)

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

    /** Decoded thumbnails by image id, null for an image that could not be decoded; and the ids being decoded. */
    private val thumbnails = HashMap<String, ImageBitmap?>()
    private val decoding = HashSet<String>()

    /** The state being rendered, where an image coroutine finds its [UserImage]. */
    private var rendering: ChatState? = null

    /** The last chat that rendered, and its rows: the rows the builder keeps as the same instances keep theirs ([render]). */
    private var good: RenderedChat = rendered.value
    private var goodRows: PersistentList<RenderedRow> = persistentListOf()

    /** Whether a body was opened, or a thumbnail decoded, since the last full pass, and how many renders ago that pass was ([render]). */
    private var requestsChanged = false
    private var sinceFull = 0

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
                    expandRequests.onReceive { key -> requested.add(key).also { if (it) requestsChanged = true } }
                    decodedImages.onReceive { (id, bitmap) ->
                        decoding -= id
                        thumbnails[id] = bitmap
                        // Its row may be anywhere: the next render is a full pass, which renders it with the thumbnail.
                        if (bitmap != null) requestsChanged = true
                        bitmap != null
                    }
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
     * row, a group's sub-row `c:<call id>` or a subagent's row (at any depth) of the latest rows, or the open plan card's plan.
     *
     * A streamed delta changes only the last rows: the builder hands back the rows before them as the same instances, and those keep
     * their rendered rows unvisited, so a render costs the rows that changed. A full pass renders every row and then drops the rest
     * of the cache, and the opened keys whose rows went: after a new builder, after a body was opened (its row may be anywhere), when
     * the list got shorter, and every [FULL_EVERY] renders.
     */
    private fun render(input: Input): RenderedChat {
        val kept = builder?.takeIf { builderOptions == input.options }
        rendering = input.state
        val rows = buildRows(
            kept ?: ChatRowsBuilder(input.options).also {
                builder = it
                builderOptions = input.options
            },
            input.state,
        )
        val chatRows = rows.rows
        val before = goodRows
        val full = kept == null || requestsChanged || chatRows.size < before.size || ++sinceFull >= FULL_EVERY
        var same = 0
        if (!full) {
            val common = minOf(chatRows.size, before.size)
            while (same < common && chatRows[same] === before[same].row) same++
        }
        val previous = HashMap<String, RenderedRow>()
        for (i in same until before.size) previous[before[i].row.key] = before[i]
        val live = HashSet<String>()
        val liveImages = HashSet<String>()
        val out = (if (same == 0) persistentListOf() else before).mutate { list ->
            for (i in list.lastIndex downTo same) list.removeAt(i)
            for (i in same until chatRows.size) list += renderRow(chatRows[i], previous, live, liveImages)
        }
        val card = rows.card
        val planBlocks = (card as? PlanCard)?.plan?.let { plan ->
            val key = "plan:${card.requestId}"
            live += key
            blocks(key, plan)
        }
        if (full) {
            requested.retainAll(live)
            parsed.keys.retainAll(live)
            thumbnails.keys.retainAll(liveImages)
            requestsChanged = false
            sinceFull = 0
        }
        goodRows = out
        return RenderedChat(out, card, rows.moreWaiting, planBlocks, rows.latestReply)
    }

    /**
     * [row] with its Markdown, its keys added to [live]; the [previous] instance of its key when nothing it shows changed, so that its
     * item is skipped.
     */
    private fun renderRow(row: ChatRow, previous: Map<String, RenderedRow>, live: MutableSet<String>, liveImages: MutableSet<String>): RenderedRow {
        val old = previous[row.key]
        live += row.key
        return when (row) {
            is TextRow -> if (old != null && old.row == row) old else RenderedRow(row, blocks(row.key, row.markdown))
            // `/context`'s report reads as Markdown (desktop-parity spec 3.1); every other notice stays plain text.
            is NoticeRow -> if (old != null && old.row == row) old else RenderedRow(row, if (row.markdown) blocks(row.key, row.text) else null)
            is UserRow -> {
                val images = thumbnailsOf(row, liveImages)
                if (old != null && old.row == row && old.images == images) old else RenderedRow(row, null, images = images)
            }
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

    /**
     * The thumbnails of [row]'s images decoded so far, by id, their ids added to [liveImages]; starts decoding each image that has
     * none and is not being decoded (polish spec 3.4).
     */
    private fun thumbnailsOf(row: UserRow, liveImages: MutableSet<String>): ImmutableMap<String, ImageBitmap> {
        if (row.images.isEmpty()) return persistentMapOf()
        var images = persistentMapOf<String, ImageBitmap>()
        row.images.forEach { ref ->
            liveImages += ref.id
            when {
                ref.id in thumbnails -> thumbnails[ref.id]?.let { images = images.put(ref.id, it) }
                decoding.add(ref.id) -> startDecoding(ref.id)
            }
        }
        return images
    }

    /** Decodes the image [id] of the state being rendered in a child coroutine, and hands the result to the rendering coroutine. */
    private fun startDecoding(id: String) {
        val image = rendering?.entries?.firstNotNullOfOrNull { entry -> (entry as? ChatEntry.User)?.images?.firstOrNull { it.id == id } }
        if (image == null) {
            decoding -= id
            thumbnails[id] = null
            return
        }
        scope.launch(IMAGE_DISPATCHER) {
            val bitmap = try {
                decodeImage(image)
            } catch (e: Exception) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.debug("${image.mediaType} image ${image.id.substringAfterLast('#')} not decoded: ${e.javaClass.name}")
                null
            }
            decodedImages.send(id to bitmap)
        }
    }

    private fun markdownOf(body: ToolBody): String? = when (body) {
        is ToolBody.Markdown -> body.markdown
        is ToolBody.Links -> body.markdown
        else -> null
    }

    private companion object {
        val LOG: Logger = logger<ChatPresenter>()

        /** Renders between two full passes ([render]). */
        const val FULL_EVERY = 256

        /** Where thumbnails are decoded: off the EDT, two at a time, so a chat of many images leaves the other cores alone. */
        val IMAGE_DISPATCHER: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)
    }
}

/** A thumbnail's longest side, at most, in pixels: twice the bubble's 240 dp at 1x, enough for 2x screens. */
private const val THUMBNAIL_PX = 480

/**
 * [image] decoded and scaled to a longest side of at most 480 px ([decodeImageBytes]); null when it cannot be (data redacted to
 * `<base64>`, a type Skia does not read), which is logged at debug by its media type and index, never its data.
 */
internal fun decodeUserImage(image: UserImage): ImageBitmap? = runCatching { decodeImageBytes(Base64.getDecoder().decode(image.base64)) }
    .onFailure { IMAGE_LOG.debug("${image.mediaType} image ${image.id.substringAfterLast('#')} not decoded: ${it.javaClass.name}") }
    .getOrNull()

/**
 * The most pixels an image may claim to be decoded for its thumbnail: 50 million, room for an 8K screenshot (33 million) and a
 * 48-megapixel photo. A decode holds width x height x 4 bytes (N32) whatever the thumbnail's size, so the cap bounds each one at
 * 200 MB: the chat decodes its thumbnails two at a time ([ChatPresenter]), the composer one per pasted or dropped image. The
 * attachment checks cap the file, not its canvas: a uniform 30,000 x 30,000 PNG fits in a few MB and would decode to 3.6 GB.
 */
internal const val MAX_IMAGE_PIXELS: Long = 50_000_000L

/**
 * [bytes] (PNG, JPEG, GIF or WebP) decoded with Skia and scaled to a longest side of at most 480 px; null when they cannot be, or when
 * the header claims more than [MAX_IMAGE_PIXELS]: `Image.makeFromEncoded` reads only the header, so that is checked before any decode.
 */
internal fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).use { image ->
        if (image.width.toLong() * image.height > MAX_IMAGE_PIXELS) {
            IMAGE_LOG.debug("image not decoded: ${image.width} x ${image.height} pixels is over the cap")
            return@use null
        }
        val longest = maxOf(image.width, image.height)
        if (longest <= THUMBNAIL_PX) return@use image.toComposeImageBitmap()
        val scale = THUMBNAIL_PX.toFloat() / longest
        val width = (image.width * scale).roundToInt().coerceAtLeast(1)
        val height = (image.height * scale).roundToInt().coerceAtLeast(1)
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()), Paint().apply { isAntiAlias = true })
            surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
        }
    }
}.getOrNull()

private val IMAGE_LOG: Logger = logger<ChatPresenter>()

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

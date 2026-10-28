package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.mentionsIn
import dev.daarkolabs.tandem.protocol.ContextBlock
import dev.daarkolabs.tandem.protocol.ImageBlock
import dev.daarkolabs.tandem.session.ContextBlocks
import dev.daarkolabs.tandem.session.ContextChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

/** One image of a captured prompt: its media type and its raw bytes, encoded only by the [ComposerSender]. */
internal class CapturedImage(val mediaType: String, val bytes: ByteArray)

/**
 * A prompt as the composer captured it on Enter: its text and its attachments, in order; [later] to send it with
 * `"priority":"later"` (desktop-parity spec 12.1).
 */
internal class CapturedPrompt(val text: String, val images: List<CapturedImage>, val later: Boolean = false)

/**
 * A prompt ready for `manager.send`: its [text], its encoded [images], its `later` flag, and the [context] blocks sent before its
 * text, which its bubble shows as [chips] (spec 3.5).
 */
internal data class OutgoingPrompt(
    val text: String,
    val images: List<ImageBlock>,
    val later: Boolean = false,
    val context: List<ContextBlock> = emptyList(),
    val chips: List<ContextChip> = emptyList(),
)

/**
 * One session's sender of prompts (polish spec 2.7, 3.4): the composer captures a prompt on the EDT ([submit]) and this encodes its
 * images as base64 on [Dispatchers.Default], then hands it to [send] (`manager.send`) with its `later` flag, one prompt at a time and
 * in the order captured. On the way, each `@path` mention whose file has unsaved changes in the IDE ([unsaved] gives its buffer, off
 * the EDT) is swapped for the bare path and a context block with the buffer goes first ([swapDirtyMentions], spec 3.5). [sending] is true from a capture until [send] has returned for every prompt captured so far: the composer counts as busy
 * meanwhile, so a second Enter during a long encode does nothing, as during a turn.
 *
 * It runs in [scope] (the session's job in [ChatPresenters]) until that is cancelled, when the session closes. A prompt not yet sent
 * then, queued or still encoding, is not dropped silently: [dropped] gets `This prompt was not sent before the session closed` for
 * each, as does a prompt captured after it stopped.
 */
internal class ComposerSender(
    scope: CoroutineScope,
    private val send: (OutgoingPrompt) -> Unit,
    private val dropped: (String) -> Unit,
    private val encode: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) },
    private val unsaved: suspend (path: String) -> String? = { null },
) {
    private val prompts = Channel<CapturedPrompt>(Channel.UNLIMITED)
    private val mutableSending = MutableStateFlow(false)
    private val lock = Any()

    /** Prompts captured and not yet sent or dropped; guarded by [lock], with [mutableSending] set in the same step. */
    private var pending = 0

    /** Whether a captured prompt has not been handed to `manager.send` yet. */
    val sending: StateFlow<Boolean> = mutableSending.asStateFlow()

    init {
        val job = scope.launch {
            var inFlight: CapturedPrompt? = null
            try {
                for (prompt in prompts) {
                    inFlight = prompt
                    // A prompt taken without suspending does not see a cancellation by itself.
                    currentCoroutineContext().ensureActive()
                    // withContext throws on its return to a cancelled job, so a prompt whose session closed while it was encoding
                    // reaches the drop below, never send.
                    val (blocks, swap) = withContext(Dispatchers.Default) {
                        prompt.images.map { ImageBlock(it.mediaType, encode(it.bytes)) } to swapDirtyMentions(prompt.text)
                    }
                    inFlight = null
                    try {
                        val (text, context) = swap
                        val chips = context.mapNotNull { ContextBlocks.chipOf(it.firstLine) }
                        send(OutgoingPrompt(text, blocks, prompt.later, context, chips))
                    } finally {
                        done()
                    }
                }
            } finally {
                prompts.close()
                inFlight?.let { drop() }
                drain()
            }
        }
        // A scope cancelled before the coroutine started never runs its body: the prompts captured meanwhile are dropped here.
        job.invokeOnCompletion {
            prompts.close()
            drain()
        }
    }

    /**
     * [text] with each `@path` whose file has unsaved changes ([unsaved]) written as the bare path, so that the CLI does not read the
     * stale disk, and one block per such file, in mention order, to go before the text: `<path> as open in the IDE, with unsaved
     * changes (N lines):` and the buffer (spec 3.5). A clean or unknown mention stays as typed. Off the EDT.
     */
    suspend fun swapDirtyMentions(text: String): Pair<String, List<ContextBlock>> {
        val spans = mentionsIn(text)
        if (spans.isEmpty()) return text to emptyList()
        val buffers = LinkedHashMap<String, String?>()
        for (span in spans) if (span.path !in buffers) buffers[span.path] = unsaved(span.path)
        val dirty = buffers.filterValues { it != null }
        if (dirty.isEmpty()) return text to emptyList()
        val out = StringBuilder(text.length)
        var at = 0
        for (span in spans) {
            if (span.path !in dirty) continue
            out.append(text, at, span.start)
            at = span.start + 1
        }
        out.append(text, at, text.length)
        return out.toString() to dirty.map { (path, buffer) -> ContextBlocks.unsavedBlock(path, buffer!!) }
    }

    /** Queues [prompt] for sending; on the EDT, at the capture. */
    fun submit(prompt: CapturedPrompt) {
        synchronized(lock) {
            pending++
            mutableSending.value = true
        }
        if (prompts.trySend(prompt).isFailure) drop()
    }

    private fun drain() {
        while (prompts.tryReceive().isSuccess) drop()
    }

    private fun drop() {
        dropped(NOT_SENT)
        done()
    }

    private fun done() {
        synchronized(lock) {
            pending--
            mutableSending.value = pending > 0
        }
    }

    private companion object {
        const val NOT_SENT = "This prompt was not sent before the session closed"
    }
}

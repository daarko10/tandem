package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.protocol.ImageBlock
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
 * One session's sender of prompts (polish spec 2.7, 3.4): the composer captures a prompt on the EDT ([submit]) and this encodes its
 * images as base64 on [Dispatchers.Default], then hands it to [send] (`manager.send`) with its `later` flag, one prompt at a time and
 * in the order captured. [sending] is true from a capture until [send] has returned for every prompt captured so far: the composer counts as busy
 * meanwhile, so a second Enter during a long encode does nothing, as during a turn.
 *
 * It runs in [scope] (the session's job in [ChatPresenters]) until that is cancelled, when the session closes. A prompt not yet sent
 * then, queued or still encoding, is not dropped silently: [dropped] gets `This prompt was not sent before the session closed` for
 * each, as does a prompt captured after it stopped.
 */
internal class ComposerSender(
    scope: CoroutineScope,
    private val send: (String, List<ImageBlock>, Boolean) -> Unit,
    private val dropped: (String) -> Unit,
    private val encode: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) },
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
                    val blocks = withContext(Dispatchers.Default) { prompt.images.map { ImageBlock(it.mediaType, encode(it.bytes)) } }
                    inFlight = null
                    try {
                        send(prompt.text, blocks, prompt.later)
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

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.daarkolabs.tandem.protocol.ContextBlock
import dev.daarkolabs.tandem.protocol.HostMessages
import dev.daarkolabs.tandem.protocol.ImageBlock
import dev.daarkolabs.tandem.session.ContextChip
import dev.daarkolabs.tandem.session.ContextChipKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A session's sender: prompts captured on the EDT are encoded off it and sent in order (polish spec 2.7, 3.4). */
class ComposerSenderTest {
    private class Sent(val text: String, val images: List<ImageBlock>, val later: Boolean = false)

    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun `prompts are sent in order`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sent: MutableList<Sent> = Collections.synchronizedList(ArrayList())
            val dropped: MutableList<String> = Collections.synchronizedList(ArrayList())
            val calls = AtomicInteger()
            val encode: (ByteArray) -> String = {
                // The first prompt's image takes longer: the second must still be sent after it.
                if (calls.getAndIncrement() == 0) Thread.sleep(50)
                Base64.getEncoder().encodeToString(it)
            }
            val sender = ComposerSender(scope, { sent += Sent(it.text, it.images) }, { dropped += it }, encode)
            sender.submit(CapturedPrompt("a", listOf(CapturedImage("image/png", bytes))))
            assertTrue(sender.sending.value, "sending from the capture on")
            sender.submit(CapturedPrompt("b", listOf(CapturedImage("image/png", bytes))))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(listOf("a", "b"), sent.map { it.text })
            assertEquals(listOf(ImageBlock("image/png", encode(bytes))), sent[1].images)
            assertEquals(emptyList(), dropped)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `later travels with its prompt and the order holds across encodes`() = runBlocking {
        // spec 12.1, 12.7: Ctrl/Cmd+Enter's flag reaches manager.send with its own prompt, after the prompts captured before it
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sent: MutableList<Sent> = Collections.synchronizedList(ArrayList())
            val calls = AtomicInteger()
            val encode: (ByteArray) -> String = {
                if (calls.getAndIncrement() == 0) Thread.sleep(50)
                Base64.getEncoder().encodeToString(it)
            }
            val sender = ComposerSender(scope, { sent += Sent(it.text, it.images, it.later) }, {}, encode)
            sender.submit(CapturedPrompt("A", listOf(CapturedImage("image/png", bytes))))
            sender.submit(CapturedPrompt("B", emptyList(), later = true))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(listOf("A" to false, "B" to true), sent.map { it.text to it.later })
            assertEquals(1, sent[0].images.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sending is true from the capture until manager send returns`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val inSend = CountDownLatch(1)
            val release = CountDownLatch(1)
            val sender = ComposerSender(scope, {
                inSend.countDown()
                release.await(TIMEOUT, TimeUnit.MILLISECONDS)
            }, {})
            sender.submit(CapturedPrompt("a", emptyList()))
            assertTrue(sender.sending.value)
            assertTrue(inSend.await(TIMEOUT, TimeUnit.MILLISECONDS))
            assertTrue(sender.sending.value, "still sending while manager.send runs")
            release.countDown()
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a prompt not sent when the sender stops leaves a notice`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val entered = CountDownLatch(1)
        val latch = CountDownLatch(1)
        val sent: MutableList<String> = Collections.synchronizedList(ArrayList())
        val dropped: MutableList<String> = Collections.synchronizedList(ArrayList())
        val droppedOnce = CountDownLatch(1)
        // Encoding runs inside withContext(Dispatchers.Default), which cancellation cannot interrupt: it ends when the latch opens.
        val encode: (ByteArray) -> String = {
            entered.countDown()
            latch.await(TIMEOUT, TimeUnit.MILLISECONDS)
            "x"
        }
        val sender = ComposerSender(scope, { sent += it.text }, {
            dropped += it
            droppedOnce.countDown()
        }, encode)
        sender.submit(CapturedPrompt("a", listOf(CapturedImage("image/png", bytes))))
        assertTrue(entered.await(TIMEOUT, TimeUnit.MILLISECONDS))
        scope.cancel()
        latch.countDown()
        assertTrue(droppedOnce.await(TIMEOUT, TimeUnit.MILLISECONDS))
        assertEquals(listOf("This prompt was not sent before the session closed"), dropped)
        assertEquals(emptyList(), sent)
        withTimeout(TIMEOUT) { sender.sending.first { !it } }
        // A prompt captured after the sender stopped is not lost silently either.
        sender.submit(CapturedPrompt("b", emptyList()))
        assertEquals(2, dropped.size)
        assertEquals(emptyList(), sent)
    }

    // spec: a dirty mention is sent as a bare path with its block first
    @Test
    fun `a dirty mention is sent as a bare path with its block first`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sent: MutableList<OutgoingPrompt> = Collections.synchronizedList(ArrayList())
            val buffers = mapOf("src/A.kt" to "x\ny\n")
            val sender = ComposerSender(scope, { sent += it }, {}, unsaved = { buffers[it] })
            sender.submit(CapturedPrompt("fix @src/A.kt, then @src/A.kt and @b.kt", emptyList()))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            val prompt = sent.single()
            // The dirty file loses its `@` everywhere (the CLI would read the stale disk); the clean one keeps it.
            assertEquals("fix src/A.kt, then src/A.kt and @b.kt", prompt.text)
            assertEquals(listOf(ContextBlock("src/A.kt as open in the IDE, with unsaved changes (2 lines):", "x\ny\n")), prompt.context)
            assertEquals(listOf(ContextChip(ContextChipKind.UNSAVED_FILE, "A.kt (unsaved)", "src/A.kt")), prompt.chips)
            // On the wire the block goes before the prompt's own text.
            val content = HostMessages.userMessage(prompt.text, prompt.images, "u", context = prompt.context)["message"]!!
                .jsonObject["content"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
            assertEquals(listOf("src/A.kt as open in the IDE, with unsaved changes (2 lines):\n```\nx\ny\n\n```", prompt.text), content)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a dirty mention with a line suffix looks up its file and keeps the range`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
            val sender = ComposerSender(scope, {}, {}, unsaved = { asked += it; mapOf("src/A.kt" to "x\ny\n")[it] })
            val (text, context) = sender.swapDirtyMentions("look at @src/A.kt#L2 and @src/A.kt#L5-7")
            assertEquals("look at src/A.kt#L2 and src/A.kt#L5-7", text)
            assertEquals(listOf(ContextBlock("src/A.kt as open in the IDE, with unsaved changes (2 lines):", "x\ny\n")), context)
            assertEquals(listOf("src/A.kt"), asked, "the file is looked up once, without its range")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failing buffer lookup sends the mention as typed and the sender goes on`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sent: MutableList<OutgoingPrompt> = Collections.synchronizedList(ArrayList())
            val dropped: MutableList<String> = Collections.synchronizedList(ArrayList())
            val sender = ComposerSender(scope, { sent += it }, { dropped += it }, unsaved = { error("boom") })
            sender.submit(CapturedPrompt("read @a.kt", emptyList()))
            sender.submit(CapturedPrompt("then @b.kt", emptyList()))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(listOf("read @a.kt", "then @b.kt"), sent.map { it.text })
            assertEquals(emptyList(), dropped)
        } finally {
            scope.cancel()
        }
    }

    // spec: a clean mention goes out as typed
    @Test
    fun `a clean mention goes out as typed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
            val sent: MutableList<OutgoingPrompt> = Collections.synchronizedList(ArrayList())
            val sender = ComposerSender(scope, { sent += it }, {}, unsaved = { asked += it; null })
            sender.submit(CapturedPrompt("What does @hello.txt say? Mail a@b.c", emptyList()))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(OutgoingPrompt("What does @hello.txt say? Mail a@b.c", emptyList()), sent.single())
            // Only the mention was looked up, once.
            assertEquals(listOf("hello.txt"), asked)
            assertEquals("no mention" to emptyList(), sender.swapDirtyMentions("no mention"))
        } finally {
            scope.cancel()
        }
    }

    // spec: a context-only message is refused as an empty draft
    @Test
    fun `a context-only message is refused as an empty draft`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sent: MutableList<OutgoingPrompt> = Collections.synchronizedList(ArrayList())
            val sender = ComposerSender(scope, { sent += it }, {}, unsaved = { "buffer" })
            val ui = ChatUiState()
            ui.draft.setTextAndPlaceCursorAtEnd("  ")
            assertFalse(sendDraft(ui, sender), "a blank draft sends nothing, whatever context there is")
            assertFalse(sender.sending.value)
            assertEquals(emptyList(), sent)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT = 30_000L
    }
}

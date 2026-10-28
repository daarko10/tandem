package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.daarkolabs.tandem.ide.context.Selection
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
            // The draft as typed travels with it: a withdrawn send puts the `@` back (ReducerTest).
            assertEquals("fix @src/A.kt, then @src/A.kt and @b.kt", prompt.typed)
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
            // The user is told each time that the CLI read the disk (hard rule 5); the file is named, not the path.
            assertEquals(
                listOf(
                    "Could not read the unsaved changes of a.kt; Claude reads the file from the disk",
                    "Could not read the unsaved changes of b.kt; Claude reads the file from the disk",
                ),
                dropped,
            )
            assertEquals("Could not read the unsaved changes of A.kt; Claude reads the file from the disk", ComposerSender.lookupFailedText("src/A.kt"))
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
    // In B1 a context block can only come from a mention inside the text, so a blank draft has none: this checks that the composer's
    // empty-draft rule stands in front of the sender. The real context-only case (the selection chip with a blank draft) comes in B2.
    @Test
    fun `a context-only message is refused as an empty draft`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sent: MutableList<OutgoingPrompt> = Collections.synchronizedList(ArrayList())
            val sender = ComposerSender(scope, { sent += it }, {})
            val ui = ChatUiState()
            ui.draft.setTextAndPlaceCursorAtEnd("  ")
            assertFalse(sendDraft(ui, sender), "a blank draft sends nothing, whatever context there is")
            assertFalse(sender.sending.value)
            assertEquals(emptyList(), sent)
        } finally {
            scope.cancel()
        }
    }

    // spec: the selection block goes first with its first line
    @Test
    fun `the selection block goes first with its first line`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sent: MutableList<OutgoingPrompt> = Collections.synchronizedList(ArrayList())
            val sender = ComposerSender(scope, { sent += it }, {}, unsaved = { if (it == "src/B.kt") "dirty\n" else null })
            val selection = Selection("src/A.kt", "A.kt", 2, 3, "two\nthree")
            sender.submit(CapturedPrompt("why @src/B.kt?", emptyList(), selection = selection))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            val prompt = sent.single()
            // The selection's block first, then the unsaved buffer's, then the prompt; a chip for each, in that order.
            assertEquals(
                listOf(
                    ContextBlock("The user's selection in src/A.kt, lines 2-3:", "two\nthree"),
                    ContextBlock("src/B.kt as open in the IDE, with unsaved changes (1 lines):", "dirty\n"),
                ),
                prompt.context,
            )
            assertEquals(
                listOf(ContextChip(ContextChipKind.SELECTION, "A.kt:2-3", "src/A.kt"), ContextChip(ContextChipKind.UNSAVED_FILE, "B.kt (unsaved)", "src/B.kt")),
                prompt.chips,
            )
            assertEquals(selection.label, prompt.chips.first().label, "the bubble's chip reads as the composer's did")
            val content = HostMessages.userMessage(prompt.text, prompt.images, "u", context = prompt.context)["message"]!!
                .jsonObject["content"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
            assertEquals("The user's selection in src/A.kt, lines 2-3:\n```\ntwo\nthree\n```", content.first())
            assertEquals("why src/B.kt?", content.last())
        } finally {
            scope.cancel()
        }
    }

    // spec: no block goes out without the chip
    @Test
    fun `no block goes out without the chip`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sent: MutableList<OutgoingPrompt> = Collections.synchronizedList(ArrayList())
            val sender = ComposerSender(scope, { sent += it }, {})
            val ui = ChatUiState()
            ui.selectionChanged(Selection("src/A.kt", "A.kt", 1, 1, "secret"))
            ui.removeSelection()
            ui.draft.setTextAndPlaceCursorAtEnd("hello")
            assertTrue(sendDraft(ui, sender))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(OutgoingPrompt("hello", emptyList()), sent.single(), "no selection block, no chip")
            // With no selection at all, none either; with the chip, one.
            ui.selectionChanged(null)
            ui.draft.setTextAndPlaceCursorAtEnd("again")
            assertTrue(sendDraft(ui, sender))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(emptyList(), sent.last().context)
            ui.selectionChanged(Selection("src/A.kt", "A.kt", 4, 4, "shown"))
            ui.draft.setTextAndPlaceCursorAtEnd("with it")
            assertTrue(sendDraft(ui, sender))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(listOf("The user's selection in src/A.kt, lines 4-4:"), sent.last().context.map { it.firstLine })
            // A chip with a blank draft is a context-only message: refused as an empty draft (spec 3.5).
            ui.draft.setTextAndPlaceCursorAtEnd(" ")
            assertFalse(sendDraft(ui, sender))
            assertEquals(3, sent.size)
        } finally {
            scope.cancel()
        }
    }

    // review: a slash command goes as plain text (docs/protocol.md 4), never behind the selection's block
    @Test
    fun `a slash command goes without the selection block`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sent: MutableList<OutgoingPrompt> = Collections.synchronizedList(ArrayList())
            val sender = ComposerSender(scope, { sent += it }, {})
            val ui = ChatUiState()
            ui.selectionChanged(Selection("src/A.kt", "A.kt", 1, 2, "a\nb"))
            ui.draft.setTextAndPlaceCursorAtEnd("/compact")
            assertTrue(sendDraft(ui, sender, command = true))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(OutgoingPrompt("/compact", emptyList()), sent.single(), "the command alone, no block, no chip")
            // The chip stays for the next prompt, which carries it.
            assertEquals("A.kt:1-2", ui.chipSelection?.label)
            ui.draft.setTextAndPlaceCursorAtEnd("explain")
            assertTrue(sendDraft(ui, sender))
            withTimeout(TIMEOUT) { sender.sending.first { !it } }
            assertEquals(listOf("The user's selection in src/A.kt, lines 1-2:"), sent.last().context.map { it.firstLine })
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT = 30_000L
    }
}

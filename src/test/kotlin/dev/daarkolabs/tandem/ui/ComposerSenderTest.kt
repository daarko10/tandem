package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.protocol.ImageBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A session's sender: prompts captured on the EDT are encoded off it and sent in order (polish spec 2.7, 3.4). */
class ComposerSenderTest {
    private class Sent(val text: String, val images: List<ImageBlock>)

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
            val sender = ComposerSender(scope, { text, images -> sent += Sent(text, images) }, { dropped += it }, encode)
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
    fun `sending is true from the capture until manager send returns`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val inSend = CountDownLatch(1)
            val release = CountDownLatch(1)
            val sender = ComposerSender(scope, { _, _ ->
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
        val sender = ComposerSender(scope, { text, _ -> sent += text }, {
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

    private companion object {
        const val TIMEOUT = 30_000L
    }
}

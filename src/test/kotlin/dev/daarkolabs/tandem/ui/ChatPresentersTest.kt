package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.SessionHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tool window's presenters: each session's sender lives while its session does (polish spec 3.4). */
class ChatPresentersTest {
    @Test
    fun `a session's sender stops when the session closes`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val entered = CountDownLatch(1)
            val latch = CountDownLatch(1)
            val sent: MutableList<String> = Collections.synchronizedList(ArrayList())
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)), send = { _, text, _ ->
                entered.countDown()
                latch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                sent += text
            })
            // As SessionSpawner.takeOver makes one: no process, so a notice goes straight into the chat.
            val handle = SessionHandle("a", scope, ChatState(), MutableStateFlow(emptyList()), 0L)
            presenters.sender(handle).submit(CapturedPrompt("a", emptyList()))
            presenters.sender(handle).submit(CapturedPrompt("b", emptyList()))
            assertTrue(entered.await(TIMEOUT, TimeUnit.MILLISECONDS), "a is inside send")

            presenters.retain(emptyList())
            latch.countDown()

            val notices = runBlocking {
                withTimeout(TIMEOUT) {
                    handle.state.map { state -> state.entries.filterIsInstance<ChatEntry.Notice>() }.first { it.isNotEmpty() }
                }
            }
            assertEquals(listOf(NoticeLevel.WARNING to "This prompt was not sent before the session closed"), notices.map { it.level to it.text })
            // a was already inside send when the session closed: it went out; only b is dropped.
            runBlocking { withTimeout(TIMEOUT) { while (sent.isEmpty()) delay(10) } }
            assertEquals(listOf("a"), sent)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT = 30_000L
    }
}

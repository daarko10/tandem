package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.chat.replay
import dev.daarkolabs.tandem.session.ChatState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatPresenterTest {
    @Test
    fun `expanding a markdown body parses it once off the EDT and re-emits the row`() = runBlocking {
        val processor = MarkdownProcessor(listOf(AutolinkProcessorExtension))
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val calls = AtomicInteger()
        val parse: (String) -> ImmutableList<MarkdownBlock> = { text ->
            threads += Thread.currentThread().name
            calls.incrementAndGet()
            processor.processMarkdownDocument(text).toImmutableList()
        }
        val state = MutableStateFlow(ChatState())
        state.value = replay("misc-web").first
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenter = ChatPresenter(scope, state, MutableStateFlow(RowOptions(showThinking = true)), parse)
            // The WebFetch call of misc-web:29, a lone row whose body is the fetched page's answer as Markdown.
            val key = "tool:<id-28>"
            withTimeout(TIMEOUT) { presenter.chat.first { chat -> chat.rows.any { it.row.key == key } } }
            val before = calls.get()

            // The click handler runs on the EDT; the parse must not.
            SwingUtilities.invokeAndWait { presenter.expandBody(key) }
            val chat = withTimeout(TIMEOUT) { presenter.chat.first { c -> c.rows.first { it.row.key == key }.bodies[key] != null } }
            val blocks = assertNotNull(chat.rows.first { it.row.key == key }.bodies[key])
            assertEquals(before + 1, calls.get())
            assertTrue(threads.none { it.startsWith("AWT-EventQueue") }, threads.toString())

            SwingUtilities.invokeAndWait { presenter.expandBody(key) }
            delay(SETTLE_MS)
            assertEquals(before + 1, calls.get())
            assertSame(blocks, presenter.chat.value.rows.first { it.row.key == key }.bodies[key])
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT = 30_000L

        /** Long enough for a second parse to have run and been emitted, had there been one. */
        const val SETTLE_MS = 500L
    }
}

package dev.daarkolabs.tandem.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.daarkolabs.tandem.chat.ChatRowsBuilder
import dev.daarkolabs.tandem.chat.ForkState
import dev.daarkolabs.tandem.chat.NoticeRow
import dev.daarkolabs.tandem.chat.PROMPT
import dev.daarkolabs.tandem.chat.PlanCard
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.ToolState
import dev.daarkolabs.tandem.chat.TurnEndRow
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.chat.WorkingRow
import dev.daarkolabs.tandem.chat.forkState
import dev.daarkolabs.tandem.chat.replay
import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContentBlock
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.UserImage
import dev.daarkolabs.tandem.session.reduce
import dev.daarkolabs.tandem.ui.rows.shownWorkingRow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatPresenterTest {
    init {
        // Decoding loads Skia, whose JVM-wide "Reference Cleaner" thread starts with its first object and lives as long as the JVM
        // (TandemPluginTest). Started here, before the platform's thread-leak check takes its snapshot for the test, it is no leak of
        // whichever test decodes first.
        checkNotNull(decodeUserImage(UserImage("warm-up#0", "image/png", fixturePng())))
    }

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

    @OptIn(ExperimentalJewelApi::class)
    @Test
    fun `a plan card's markdown is parsed off the EDT`() = runBlocking {
        val state = MutableStateFlow(ChatState())
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenter = ChatPresenter(scope, state, MutableStateFlow(RowOptions(showThinking = true)), parse = { text ->
                threads += Thread.currentThread().name
                MarkdownProcessor().processMarkdownDocument(text).toImmutableList()
            })
            // The ExitPlanMode request of plan-approve:88, keyed `plan:<request_id-97>`.
            state.value = stateAt("plan-approve", 88)
            val chat = withTimeout(TIMEOUT) { presenter.chat.first { it.planBlocks != null } }
            assertTrue(assertNotNull(chat.planBlocks).first() is MarkdownBlock.Heading)
            assertTrue(threads.isNotEmpty() && threads.none { it.startsWith("AWT-EventQueue") }, threads.toString())

            // The tool_result at line 91 leaves the request pending; only its answer, a cancel or the result drop it.
            state.value = reduce(state.value, SessionEvent.PermissionAnswered("<request_id-97>"))
            assertNull(withTimeout(TIMEOUT) { presenter.chat.first { it.card == null } }.planBlocks)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `expanding an agent row parses its nested text`() = runBlocking {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val seen: MutableList<String> = Collections.synchronizedList(ArrayList())
        val parse: (String) -> ImmutableList<MarkdownBlock> = { text ->
            threads += Thread.currentThread().name
            seen += text
            MarkdownProcessor(listOf(AutolinkProcessorExtension)).processMarkdownDocument(text).toImmutableList()
        }
        val agentKey = "tool:<id-12>"
        val scope = CoroutineScope(SupervisorJob())
        try {
            // agent-forwarded: through line 37 the Agent runs with one child tool row; line 38 forwards the subagent's text.
            val forwarded = mutableListOf<ChatState>()
            replay("agent-forwarded") { _, s, _ -> forwarded += s }
            val state = MutableStateFlow(forwarded[37 - 1])
            val presenter = ChatPresenter(scope, state, MutableStateFlow(RowOptions(showThinking = true)), parse)
            val emissions: MutableList<RenderedChat> = Collections.synchronizedList(ArrayList())
            val collector = scope.launch(Dispatchers.Default) { presenter.chat.toList(emissions) }
            withTimeout(TIMEOUT) { presenter.chat.first { chat -> chat.rows.any { it.row.key == agentKey } } }
            SwingUtilities.invokeAndWait { presenter.expandBody(agentKey) }
            state.value = forwarded[38 - 1]
            val opened = withTimeout(TIMEOUT) {
                presenter.chat.first { chat ->
                    val agent = chat.rows.first { it.row.key == agentKey }
                    val child = childText(agent)
                    child != null && agent.bodies[child] != null
                }
            }
            val openedAgent = opened.rows.first { it.row.key == agentKey }
            val childKey = assertNotNull(childText(openedAgent))
            val blocks = assertNotNull(openedAgent.bodies[childKey])
            assertTrue(threads.isNotEmpty() && threads.none { it.startsWith("AWT-EventQueue") }, threads.toString())

            for (line in 39..forwarded.size) {
                state.value = forwarded[line - 1]
                delay(PUSH_MS)
            }
            withTimeout(TIMEOUT) { presenter.chat.first { chat -> chat.rows.any { it.row is TurnEndRow } } }
            delay(PUSH_MS)
            collector.cancel()
            val later = emissions.toList().dropWhile { it !== opened }
            assertTrue(later.size > 1, "the pushes after line 38 emitted")
            later.forEach { chat -> assertSame(blocks, chat.rows.first { it.row.key == agentKey }.bodies[childKey]) }

            // agent-sync: through line 45 the Agent runs with no body; its summary (:49) and its result (:50) carry the same text.
            val sync = mutableListOf<ChatState>()
            replay("agent-sync") { _, s, _ -> sync += s }
            val syncState = MutableStateFlow(sync[45 - 1])
            val syncPresenter = ChatPresenter(scope, syncState, MutableStateFlow(RowOptions(showThinking = true)), parse)
            withTimeout(TIMEOUT) { syncPresenter.chat.first { chat -> chat.rows.any { it.row.key == agentKey } } }
            SwingUtilities.invokeAndWait { syncPresenter.expandBody(agentKey) }
            seen.clear()
            for (line in 46..sync.size) {
                syncState.value = sync[line - 1]
                delay(PUSH_MS)
            }
            val final = withTimeout(TIMEOUT) {
                syncPresenter.chat.first { chat -> chat.rows.any { it.row is TurnEndRow } && chat.rows.first { it.row.key == agentKey }.bodies[agentKey] != null }
            }
            assertNotNull(final.rows.first { it.row.key == agentKey }.bodies[agentKey])
            assertEquals(1, seen.count { it == "7 lines. The last line ends with a trailing newline." }, seen.toString())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a plan card's markdown stays cached while the card is open`() = runBlocking {
        val seen: MutableList<String> = Collections.synchronizedList(ArrayList())
        val scope = CoroutineScope(SupervisorJob())
        try {
            val states = mutableListOf<ChatState>()
            replay("plan-approve") { _, s, _ -> states += s }
            val state = MutableStateFlow(states[88 - 1])
            val presenter = ChatPresenter(scope, state, MutableStateFlow(RowOptions(showThinking = true)), parse = { text ->
                seen += text
                MarkdownProcessor().processMarkdownDocument(text).toImmutableList()
            })
            val opened = withTimeout(TIMEOUT) { presenter.chat.first { it.planBlocks != null } }
            val first = assertNotNull(opened.planBlocks)
            val plan = assertIs<PlanCard>(opened.card).plan
            // The status line (permissionMode becomes acceptEdits), then the ExitPlanMode result: the bare replay keeps the card open.
            state.value = states[90 - 1]
            withTimeout(TIMEOUT) { presenter.chat.first { it.planBlocks != null } }
            state.value = states[91 - 1]
            val done = withTimeout(TIMEOUT) {
                presenter.chat.first { chat ->
                    chat.rows.any { (it.row as? ToolRow)?.let { row -> row.label.text == "Plan approved" && row.state == ToolState.DONE } == true }
                }
            }
            assertSame(first, done.planBlocks)
            assertEquals(1, seen.count { it == plan }, "the plan was parsed once")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a state that fails to build shows a notice instead of stale rows, and the next one that builds takes it away`() = runBlocking {
        val failing = AtomicBoolean(false)
        val logged: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
        val state = MutableStateFlow(replay("plain-reply").first)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenter = ChatPresenter(
                scope,
                state,
                MutableStateFlow(RowOptions(showThinking = true)),
                buildRows = { builder, s -> if (failing.get()) throw IllegalStateException("row text") else builder.build(s) },
                logFailure = { logged += it },
            )
            val good = withTimeout(TIMEOUT) { presenter.chat.first { it.rows.isNotEmpty() } }

            // Every state fails now: the chat says so after the last rows that built, rather than silently staying as it was.
            failing.set(true)
            state.value = reduce(state.value, SessionEvent.UserSent("u8", "again"))
            val failed = withTimeout(TIMEOUT) { presenter.chat.first { it.rows.lastOrNull()?.row?.key == RENDER_FAILURE_KEY } }
            val notice = assertIs<NoticeRow>(failed.rows.last().row)
            assertEquals(NoticeLevel.ERROR, notice.level)
            assertTrue(IllegalStateException::class.java.name in notice.text, notice.text)
            assertFalse("row text" in notice.text, notice.text)
            assertEquals(good.rows, failed.rows.dropLast(1))
            // A later state is tried again, and still says so; the failure is logged once, by its class only.
            state.value = reduce(state.value, SessionEvent.UserSent("u9", "and again"))
            delay(SETTLE_MS)
            assertEquals(RENDER_FAILURE_KEY, presenter.chat.value.rows.last().row.key)
            assertEquals(1, logged.size, logged.toString())
            assertFalse("row text" in logged.single().message.orEmpty())

            // The next state that builds takes the notice away and shows every row it holds.
            failing.set(false)
            state.value = reduce(state.value, SessionEvent.UserSent("u10", "once more"))
            val back = withTimeout(TIMEOUT) { presenter.chat.first { chat -> chat.rows.any { it.row.key == "u:u10" } } }
            assertTrue(back.rows.none { it.row.key == RENDER_FAILURE_KEY })
            assertEquals(ChatRowsBuilder(RowOptions(showThinking = true)).build(state.value).rows, back.rows.map { it.row })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `rows the builder kept stay the same rendered rows, and the rest match a fresh build`() = runBlocking {
        val options = RowOptions(showThinking = true)
        val first = replay("tools-read").first
        val state = MutableStateFlow(first)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenter = ChatPresenter(scope, state, MutableStateFlow(options))
            val before = withTimeout(TIMEOUT) { presenter.chat.first { it.rows.isNotEmpty() } }
            // A new prompt: the footer goes, a user row and the working row follow, and the rows above stay as they were.
            state.value = reduce(first, SessionEvent.UserSent("u9", "again"))
            val after = withTimeout(TIMEOUT) { presenter.chat.first { chat -> chat.rows.any { it.row.key == "u:u9" } } }
            assertEquals(ChatRowsBuilder(options).build(state.value).rows, after.rows.map { it.row })
            val kept = before.rows.indexOfFirst { it.row is TurnEndRow }
            assertTrue(kept > 0, before.rows.toString())
            for (i in 0 until kept) assertSame(before.rows[i], after.rows[i])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a turn's start sets the latest reply's fork to WAIT, and its end moves ENABLED to the new reply and NOT_LATEST to the earlier one, without the earlier TextRow changing`() = runBlocking {
        val states = HashMap<Int, ChatState>()
        val (final, _) = replay("permission-mode") { line, state, _ -> states[line] = state }
        fun replyId(text: String): String = final.entries.filterIsInstance<ChatEntry.Assistant>()
            .single { entry -> entry.blocks.any { it is AssistantBlock.Text && it.text == text } }.id
        val id1 = replyId("one")
        val id2 = replyId("two")
        // After the first result; after the second prompt, sent before line 21 as replay sends it; after the second result.
        val firstEnded = states.getValue(15)
        val secondSent = reduce(states.getValue(20), SessionEvent.UserSent("u21", PROMPT, atMillis = 2_000L))
        val secondEnded = states.getValue(38)
        val state = MutableStateFlow(firstEnded)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenter = ChatPresenter(scope, state, MutableStateFlow(RowOptions(showThinking = true)))
            fun replyRow(chat: RenderedChat): RenderedRow? = chat.rows.firstOrNull { (it.row as? TextRow)?.actions?.entryId == id1 }

            assertEquals(id1, withTimeout(TIMEOUT) { presenter.latestReply.first { it != null } })
            assertFalse(presenter.busy.value)
            assertEquals(ForkState.ENABLED, forkState(id1, presenter.latestReply.value, presenter.busy.value))
            val first = assertNotNull(replyRow(withTimeout(TIMEOUT) { presenter.chat.first { replyRow(it) != null } }))

            state.value = secondSent
            withTimeout(TIMEOUT) { presenter.busy.first { it } }
            val running = withTimeout(TIMEOUT) { presenter.chat.first { chat -> chat.rows.any { it.row.key == "u:u21" } } }
            assertEquals(id1, presenter.latestReply.value)
            assertEquals(ForkState.WAIT, forkState(id1, presenter.latestReply.value, presenter.busy.value))
            assertSame(first, replyRow(running))

            state.value = secondEnded
            assertEquals(id2, withTimeout(TIMEOUT) { presenter.latestReply.first { it == id2 } })
            withTimeout(TIMEOUT) { presenter.busy.first { !it } }
            assertEquals(ForkState.ENABLED, forkState(id2, presenter.latestReply.value, presenter.busy.value))
            assertEquals(ForkState.NOT_LATEST, forkState(id1, presenter.latestReply.value, presenter.busy.value))
            assertSame(first, replyRow(presenter.chat.value))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an expanded agent row returns the working row to the turn's verb`() {
        val row = WorkingRow(turnUuid = "u1", startedAtMillis = 0L, step = "Running x", outputTokens = 0, waiting = false, expandedStep = "Pondering")
        assertEquals(row.expandedStep, shownWorkingRow(row, true).step)
        assertSame(row, shownWorkingRow(row, false))
        val plain = row.copy(expandedStep = null)
        assertSame(plain, shownWorkingRow(plain, true))
    }

    @Test
    fun `the presenter decodes a user image once off the EDT and caches it by id`() = runBlocking {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val calls = AtomicInteger()
        val decode: (UserImage) -> ImageBitmap? = {
            threads += Thread.currentThread().name
            calls.incrementAndGet()
            decodeUserImage(it)
        }
        val user = ChatEntry.User("u1", "What colour?", images = listOf(UserImage("u1#0", "image/png", fixturePng())))
        val state = MutableStateFlow(ChatState(entries = listOf(user)))
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenter = ChatPresenter(scope, state, MutableStateFlow(RowOptions(showThinking = true)), decode = decode)
            val first = withTimeout(TIMEOUT) { presenter.chat.first { it.rows.firstOrNull()?.images?.isNotEmpty() == true } }
            val bitmap = assertNotNull(first.rows[0].images["u1#0"])
            assertEquals(2, bitmap.width)
            assertEquals(2, bitmap.height)

            state.value = state.value.copy(entries = state.value.entries + ChatEntry.Notice("n1", NoticeLevel.INFO, "noted"))
            val after = withTimeout(TIMEOUT) { presenter.chat.first { chat -> chat.rows.any { it.row is NoticeRow } } }
            assertSame(bitmap, after.rows[0].images["u1#0"])
            delay(SETTLE_MS)
            assertEquals(1, calls.get())
            assertTrue(threads.none { it.startsWith("AWT-EventQueue") }, threads.toString())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a user row renders before its thumbnails and gains them once decoded`() = runBlocking {
        val latch = CountDownLatch(1)
        val decode: (UserImage) -> ImageBitmap? = {
            latch.await(TIMEOUT, TimeUnit.MILLISECONDS)
            decodeUserImage(it)
        }
        val user = ChatEntry.User("u1", "", images = listOf(UserImage("u1#0", "image/png", fixturePng())))
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenter = ChatPresenter(scope, MutableStateFlow(ChatState(entries = listOf(user))), MutableStateFlow(RowOptions(showThinking = true)), decode = decode)
            val before = withTimeout(TIMEOUT) { presenter.chat.first { it.rows.isNotEmpty() } }
            assertIs<UserRow>(before.rows[0].row)
            assertTrue(before.rows[0].images.isEmpty())
            latch.countDown()
            val after = withTimeout(TIMEOUT) { presenter.chat.first { it.rows.firstOrNull()?.images?.get("u1#0") != null } }
            assertEquals(before.rows[0].row, after.rows[0].row)
        } finally {
            latch.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `a redacted image yields no bitmap and no exception`() = runBlocking {
        assertNull(decodeUserImage(UserImage("u1#0", "image/png", "<base64>")))
        val user = ChatEntry.User("u1", "", images = listOf(UserImage("u1#0", "image/png", "<base64>")))
        val state = MutableStateFlow(ChatState(entries = listOf(user)))
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenter = ChatPresenter(scope, state, MutableStateFlow(RowOptions(showThinking = true)), logFailure = { throw AssertionError("render failed", it) })
            withTimeout(TIMEOUT) { presenter.chat.first { it.rows.isNotEmpty() } }
            // Another state after the decode had time to fail: still no bitmap, no failure row.
            delay(SETTLE_MS)
            state.value = state.value.copy(entries = state.value.entries + ChatEntry.Notice("n1", NoticeLevel.INFO, "noted"))
            val chat = withTimeout(TIMEOUT) { presenter.chat.first { c -> c.rows.any { it.row is NoticeRow } } }
            assertTrue(chat.rows[0].images.isEmpty())
            assertTrue(chat.rows.none { it.row.key == RENDER_FAILURE_KEY })
        } finally {
            scope.cancel()
        }
    }

    /** The 2 x 2 PNG the `user-image` fixture sent, as its transcript line holds it. */
    private fun fixturePng(): String = Fixtures.transcript("user-image").readLines().filter { it.isNotBlank() }.map(Ndjson::decode)
        .filterIsInstance<CliMessage.User>().first().content.filterIsInstance<ContentBlock.Image>().single().data

    /** The key of the Agent row's forwarded text row, once it has one. */
    private fun childText(agent: RenderedRow): String? =
        (agent.row as ToolRow).calls.single().children.filterIsInstance<TextRow>().firstOrNull()?.key

    private companion object {
        const val TIMEOUT = 30_000L

        /** Long enough for a second parse to have run and been emitted, had there been one. */
        const val SETTLE_MS = 500L

        /** Between two pushed states, so the presenter renders most of them rather than only the last. */
        const val PUSH_MS = 20L
    }
}

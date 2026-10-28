@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import dev.daarkolabs.tandem.chat.ChatRow
import dev.daarkolabs.tandem.chat.RowGap
import dev.daarkolabs.tandem.chat.RowLabel
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.ToolState
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.chat.paletteFor
import dev.daarkolabs.tandem.session.ReturnedSend
import dev.daarkolabs.tandem.session.UserImage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatUiStateTest {
    @Test
    fun expansionIsKeptPerSessionKey() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)), MutableStateFlow(emptyList()))
            val first = presenters.ui("a").expanded("tool:1")
            first.value = true

            presenters.ui("b")
            val again = presenters.ui("a").expanded("tool:1")

            assertSame(first, again)
            assertTrue(again.value)
            assertFalse(presenters.ui("b").expanded("tool:1").value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun draftIsKeptPerSessionKey() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)), MutableStateFlow(emptyList()))
            val first = presenters.ui("a").draft
            first.edit { append("hello") }

            presenters.ui("b")
            val again = presenters.ui("a").draft

            assertSame(first, again)
            assertEquals("hello", again.text.toString())
            assertEquals("", presenters.ui("b").draft.text.toString())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun appendedRowsAreCountedByKeyAcrossShows() {
        val ui = ChatUiState()
        val user = rendered(UserRow("u:1", "hi", mono = false))
        val reply = rendered(TextRow("t:1:0", "he", streaming = true, gap = RowGap.SECTION))

        // The first emission only records: nothing was appended since a list that never saw the session.
        assertNull(ui.seen(listOf(user, reply), HEIGHT).appended)
        // The same rows again (a resize): nothing changed.
        assertNull(ui.seen(listOf(user, reply), HEIGHT + 10).appended)
        // A streamed delta grows the last row without adding one.
        val grown = rendered(TextRow("t:1:0", "hello", streaming = true, gap = RowGap.SECTION))
        assertEquals(0, ui.seen(listOf(user, grown), HEIGHT).appended)
        // Rows added while the panel was not shown count on the next emission it sees, since the state outlives the panel.
        ui.hidden()
        val added = listOf(user, grown, rendered(UserRow("u:2", "more", mono = false)), rendered(UserRow("u:3", "again", mono = false)))
        assertEquals(2, ui.seen(added, HEIGHT).appended)
        // A row replaced by another key (a lone call joining a group) counts once, though the list got shorter.
        assertEquals(1, ui.seen(listOf(user, rendered(UserRow("g:1", "group", mono = false))), HEIGHT).appended)
    }

    @Test
    fun pinsWhenShownWhenARowChangesAndWhenTheHeightChanges() {
        val ui = ChatUiState()
        val user = rendered(UserRow("u:1", "hi", mono = false))
        val reply = rendered(TextRow("t:1:0", "he", streaming = true, gap = RowGap.SECTION))

        // The first emission the list sees pins, and the same emission again does not.
        assertTrue(ui.seen(listOf(user, reply), HEIGHT).pin)
        assertFalse(ui.seen(listOf(user, reply), HEIGHT).pin)
        // A card docking or the composer growing changes the list's height (Resized).
        assertTrue(ui.seen(listOf(user, reply), HEIGHT - 120).pin)
        // A streamed delta grows the last row.
        val grown = rendered(TextRow("t:1:0", "hello", streaming = true, gap = RowGap.SECTION))
        assertTrue(ui.seen(listOf(user, grown), HEIGHT - 120).pin)
        // A row above the last one changes (a tool call finishes above the working row).
        assertTrue(ui.seen(listOf(rendered(UserRow("u:1", "hi!", mono = false)), grown), HEIGHT - 120).pin)
        // The session shown again pins, though nothing changed while it was hidden.
        ui.hidden()
        assertTrue(ui.seen(listOf(rendered(UserRow("u:1", "hi!", mono = false)), grown), HEIGHT - 120).pin)
    }

    @Test
    fun aBodyArrivingForAnOpenedRowIsNoChange() {
        val ui = ChatUiState()
        val call = ToolRow(
            key = "tool:1",
            calls = persistentListOf(),
            label = RowLabel("Fetched example.org", persistentListOf()),
            state = ToolState.DONE,
            stats = null,
            gap = RowGap.SECTION,
        )
        val closed = RenderedRow(call, blocks = null)
        ui.seen(listOf(closed), HEIGHT)
        ui.seen(listOf(closed), HEIGHT)

        // Opening the row asks for its body's Markdown (ChatPresenter.expandBody): the next emission has an equal row model in a
        // new RenderedRow with the parsed body. That is neither a pin while following nor an appended row while paused (spec 4.16:
        // row expand/collapse never scrolls).
        val opened = RenderedRow(call.copy(), blocks = null, bodies = persistentMapOf("tool:1" to persistentListOf<MarkdownBlock>()))
        val change = ui.seen(listOf(opened), HEIGHT)

        assertFalse(change.pin)
        assertNull(change.appended)
    }

    @Test
    fun `the panel state is kept per session key`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)), MutableStateFlow(emptyList()))
            presenters.ui("a").mcpPanelOpen.value = true

            assertFalse(presenters.ui("b").mcpPanelOpen.value)
            assertTrue(presenters.ui("a").mcpPanelOpen.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `only a panel a user action opened starts its session, and only once`() {
        val ui = ChatUiState()
        assertFalse(ui.takeMcpStartOnOpen(), "nothing opened it")

        ui.openMcpPanel()
        assertTrue(ui.mcpPanelOpen.value)
        assertTrue(ui.takeMcpStartOnOpen())
        assertFalse(ui.takeMcpStartOnOpen(), "the panel shown again when its session is selected again starts nothing")

        ui.openMcpPanel() // `/mcp` while it is open: no new panel is built, so nothing is left to start a later one
        assertFalse(ui.takeMcpStartOnOpen())
        ui.mcpPanelOpen.value = false
        ui.openMcpPanel()
        assertTrue(ui.takeMcpStartOnOpen())
    }

    // spec: palette state is kept per session key
    @Test
    fun `palette state is kept per session key`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)), MutableStateFlow(emptyList()))
            presenters.ui("a").paletteSelection.value = "/m" to 2
            presenters.ui("a").paletteDismissedFor.value = "/m"
            presenters.ui("a").menuRequest.value = FooterMenu.MODEL

            assertEquals("" to 0, presenters.ui("b").paletteSelection.value)
            assertNull(presenters.ui("b").paletteDismissedFor.value)
            assertNull(presenters.ui("b").menuRequest.value)
            assertEquals("/m" to 2, presenters.ui("a").paletteSelection.value)
            assertEquals("/m", presenters.ui("a").paletteDismissedFor.value)
            assertEquals(FooterMenu.MODEL, presenters.ui("a").menuRequest.value)
        } finally {
            scope.cancel()
        }
    }

    // spec: Esc keeps the palette closed until the draft changes
    @Test
    fun `Esc keeps the palette closed until the draft changes`() {
        val ui = ChatUiState()
        fun palette(draft: String) =
            paletteFor(draft, focused = true, ui.paletteDismissedFor.value, ui.paletteSelection.value, emptyList(), emptyList(), emptySet())
        assertNotNull(palette("/m"))

        // Esc on "/m": PromptBar stores the draft it was pressed on.
        ui.paletteDismissedFor.value = "/m"
        assertNull(palette("/m"))
        // A changed draft shows it again, whatever the stored one.
        assertNotNull(palette("/mc"))
        assertNotNull(palette("/"))
    }

    // spec: Esc keeps the palette closed until the draft changes
    @Test
    fun `a dismissal ends on another draft and survives a late notice of its own`() {
        val ui = ChatUiState()
        ui.paletteDismissedFor.value = "/m"

        // The notice of the keystroke Esc was queued behind arrives after it: the same draft, the palette stays closed.
        ui.draftChanged("/m")
        assertEquals("/m", ui.paletteDismissedFor.value)
        // A real change ends the dismissal, and going back to the dismissed draft does not restore it.
        ui.draftChanged("/mc")
        assertNull(ui.paletteDismissedFor.value)
        ui.draftChanged("/m")
        assertNull(ui.paletteDismissedFor.value)
    }

    @Test
    fun `the Slash commands row puts a slash in an empty draft only`() {
        val ui = ChatUiState()
        ui.openPalette()
        assertEquals("/", ui.draft.text.toString())
        assertEquals(TextRange(1), ui.draft.selection)

        ui.draft.setTextAndPlaceCursorAtEnd("hello")
        ui.openPalette()
        assertEquals("hello", ui.draft.text.toString())
    }

    @Test
    fun `an opened lone call that joins a group stays open as its sub-row`() {
        val ui = ChatUiState()
        ui.toggle("tool:1", listOf("c:1"), opensWith = "c:1")
        assertTrue(ui.expanded("tool:1").value)
        assertTrue(ui.expanded("c:1").value)
        val clamp = ui.clampOpen("c:1", "body")
        clamp.value = true

        // The row is now a group under the same key: its box and its sub-row read the same states, which nothing else changed.
        assertTrue(ui.expanded("tool:1").value)
        assertTrue(ui.expanded("c:1").value)
        assertSame(clamp, ui.clampOpen("c:1", "body"))
        assertTrue(ui.clampOpen("c:1", "body").value)
    }

    @Test
    fun `collapsing a row resets its show more`() {
        val ui = ChatUiState()
        ui.toggle("tool:1", listOf("c:1"), opensWith = "c:1")
        ui.clampOpen("c:1", "body").value = true

        ui.toggle("tool:1", listOf("c:1"))

        assertFalse(ui.expanded("tool:1").value)
        assertFalse(ui.clampOpen("c:1", "body").value)
    }

    @Test
    fun `collapsing a group resets its sub-rows' show more`() {
        val ui = ChatUiState()
        ui.toggle("tool:1", listOf("c:1", "c:2"))
        assertTrue(ui.expanded("tool:1").value)
        ui.expanded("c:2").value = true
        ui.clampOpen("c:1", "body").value = true
        ui.clampOpen("c:2", "command").value = true

        ui.toggle("tool:1", listOf("c:1", "c:2"))

        assertFalse(ui.expanded("tool:1").value)
        assertFalse(ui.clampOpen("c:1", "body").value)
        assertFalse(ui.clampOpen("c:2", "command").value)
        assertTrue(ui.expanded("c:2").value)
    }

    @Test
    fun `show more and show less flip one clamp`() {
        val ui = ChatUiState()
        val body = ui.clampOpen("c:1", "body")
        val output = ui.clampOpen("c:1", "output")
        assertNotSame(body, output)

        body.value = true
        assertFalse(output.value)
        body.value = false
        output.value = true
        assertFalse(body.value)
        assertTrue(ui.clampOpen("c:1", "output").value)
    }

    private fun rendered(row: ChatRow): RenderedRow = RenderedRow(row, blocks = null)

    private companion object {
        const val HEIGHT = 600
    }

    @Test
    fun `restoreReturned puts the texts ahead of the draft and re-attaches the images once`() {
        // spec 12.3, 12.5; Review Focus 6: a message that came back is never lost
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val ui = ChatUiState()
        ui.draft.setTextAndPlaceCursorAtEnd("draft")
        val attached = ArrayList<Pair<String, ByteArray>>()
        val returned = listOf(
            ReturnedSend("a", "first", emptyList()),
            ReturnedSend("b", "second", listOf(UserImage("b#0", "image/png", Base64.getEncoder().encodeToString(png)))),
        )
        val taken = ArrayList<List<String>>()
        runBlocking { withTimeout(10_000) { restoreReturned(ui, returned, { taken += it }) { _, type, bytes -> attached += type to bytes } } }
        assertEquals("first\n\nsecond\n\ndraft", ui.draft.text.toString())
        assertEquals(listOf(listOf("a", "b")), taken)
        assertEquals(TextRange(ui.draft.text.length), ui.draft.selection)
        assertEquals(1, attached.size)
        assertEquals("image/png", attached.single().first)
        assertTrue(png.contentEquals(attached.single().second))

        // An empty draft takes the texts alone, with no blank line after them.
        val empty = ChatUiState()
        runBlocking { withTimeout(10_000) { restoreReturned(empty, listOf(ReturnedSend("c", "only", emptyList())), {}) { _, _, _ -> error("no image") } } }
        assertEquals("only", empty.draft.text.toString())
    }

    @Test
    fun `a message returned while another is being restored is restored once, and so is the first`() {
        // Review Focus 6: the session's list lags behind (mapped off the EDT), so it lists A again after A was taken, then B after B was
        // taken; neither goes back twice, and each image is attached once.
        val ui = ChatUiState()
        val a = ReturnedSend("a", "first", listOf(image("a#0")))
        val b = ReturnedSend("b", "second", listOf(image("b#0")))
        val list = MutableStateFlow(listOf(a))
        val taken = ArrayList<List<String>>()
        val attached = ArrayList<Int>()
        val inFirstAttach = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runBlocking {
            withTimeout(10_000) {
                val job = launch {
                    restoreReturnedFrom(ui, list, { taken += it }) { _, _, bytes ->
                        if (attached.isEmpty()) {
                            inFirstAttach.complete(Unit)
                            release.await()
                        }
                        attached += bytes.size
                    }
                }
                inFirstAttach.await()
                assertEquals(listOf(listOf("a")), taken, "taken before its images are attached")
                list.value = listOf(a, b)
                release.complete(Unit)
                while (taken.size < 2) yield()
                list.value = listOf(b)
                while (attached.size < 2) yield()
                list.value = emptyList()
                yield()
                job.cancel()
            }
        }
        assertEquals("second\n\nfirst", ui.draft.text.toString())
        assertEquals(listOf(listOf("a"), listOf("b")), taken)
        assertEquals(2, attached.size)
        assertTrue(ui.restoredUuids.isEmpty(), "uuids the list no longer names are forgotten")
    }

    @Test
    fun `a restore cut short by a session switch is not restored again when the session shows again`() {
        // Review Focus 6: the effect is cancelled while an image is being attached; the session took the message already, and its
        // images are still attached. A list that still names it (the take not seen yet) restores nothing more.
        val ui = ChatUiState()
        val a = ReturnedSend("a", "first", listOf(image("a#0"), image("a#1")))
        val list = MutableStateFlow(listOf(a))
        val taken = ArrayList<List<String>>()
        val attached = ArrayList<Int>()
        val inFirstAttach = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runBlocking {
            withTimeout(10_000) {
                val job = launch {
                    restoreReturnedFrom(ui, list, { taken += it }) { i, _, _ ->
                        if (i == 0) {
                            inFirstAttach.complete(Unit)
                            release.await()
                        }
                        attached += i
                    }
                }
                inFirstAttach.await()
                job.cancel()
                release.complete(Unit)
                job.join()
                val again = launch { restoreReturnedFrom(ui, list, { taken += it }) { i, _, _ -> attached += i } }
                yield()
                again.cancel()
            }
        }
        assertEquals("first", ui.draft.text.toString())
        assertEquals(listOf(listOf("a")), taken)
        assertEquals(listOf(0, 1), attached)
    }

    private fun image(uuid: String): UserImage =
        UserImage(uuid, "image/png", Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1)))
}

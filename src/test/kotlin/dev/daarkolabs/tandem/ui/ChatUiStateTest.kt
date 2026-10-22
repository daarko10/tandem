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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.junit.jupiter.api.Test
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
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)))
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
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)))
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
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)))
            presenters.ui("a").mcpPanelOpen.value = true

            assertFalse(presenters.ui("b").mcpPanelOpen.value)
            assertTrue(presenters.ui("a").mcpPanelOpen.value)
        } finally {
            scope.cancel()
        }
    }

    // spec: palette state is kept per session key
    @Test
    fun `palette state is kept per session key`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)))
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
}

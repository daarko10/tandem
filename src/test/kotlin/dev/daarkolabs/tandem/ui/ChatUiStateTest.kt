@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.ChatRow
import dev.daarkolabs.tandem.chat.RowGap
import dev.daarkolabs.tandem.chat.RowLabel
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.ToolState
import dev.daarkolabs.tandem.chat.UserRow
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

    private fun rendered(row: ChatRow): RenderedRow = RenderedRow(row, blocks = null)

    private companion object {
        const val HEIGHT = 600
    }
}

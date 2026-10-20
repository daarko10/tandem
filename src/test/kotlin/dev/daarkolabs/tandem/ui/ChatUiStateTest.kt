@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.ChatRow
import dev.daarkolabs.tandem.chat.RowGap
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.UserRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
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
        assertNull(ui.appendedSinceSeen(listOf(user, reply)))
        // The same rows again (a resize): nothing changed.
        assertNull(ui.appendedSinceSeen(listOf(user, reply)))
        // A streamed delta grows the last row without adding one.
        val grown = rendered(TextRow("t:1:0", "hello", streaming = true, gap = RowGap.SECTION))
        assertEquals(0, ui.appendedSinceSeen(listOf(user, grown)))
        // Rows added while the panel was not shown count on the next emission it sees, since the state outlives the panel.
        val added = listOf(user, grown, rendered(UserRow("u:2", "more", mono = false)), rendered(UserRow("u:3", "again", mono = false)))
        assertEquals(2, ui.appendedSinceSeen(added))
        // A row replaced by another key (a lone call joining a group) counts once, though the list got shorter.
        assertEquals(1, ui.appendedSinceSeen(listOf(user, rendered(UserRow("g:1", "group", mono = false)))))
    }

    private fun rendered(row: ChatRow): RenderedRow = RenderedRow(row, blocks = null)
}

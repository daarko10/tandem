package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.ui.rows.actionRowAlpha
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The hover row under a reply (polish spec 2.6): shown while hovered or focused, its 24 dp kept while hidden. */
class MessageActionsRowTest {
    @Test
    fun `the action row is opaque while hovered or focused and transparent otherwise`() {
        assertEquals(1f, actionRowAlpha(hovered = true, focused = false))
        assertEquals(1f, actionRowAlpha(hovered = false, focused = true))
        assertEquals(0f, actionRowAlpha(hovered = false, focused = false))
    }
}

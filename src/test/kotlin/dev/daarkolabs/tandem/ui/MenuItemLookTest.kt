package dev.daarkolabs.tandem.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A menu row's look by its states, in Jewel's precedence: Jewel's own `MenuItemState` is internal from 2026.3 on. */
class MenuItemLookTest {
    @Test
    fun `disabled wins, then pressed, hovered, focused`() {
        assertEquals(MenuItemLook.DISABLED, menuItemLook(enabled = false, pressed = true, hovered = true, focused = true))
        assertEquals(MenuItemLook.PRESSED, menuItemLook(enabled = true, pressed = true, hovered = true, focused = true))
        assertEquals(MenuItemLook.HOVERED, menuItemLook(enabled = true, pressed = false, hovered = true, focused = true))
        assertEquals(MenuItemLook.FOCUSED, menuItemLook(enabled = true, pressed = false, hovered = false, focused = true))
        assertEquals(MenuItemLook.PLAIN, menuItemLook(enabled = true, pressed = false, hovered = false, focused = false))
    }
}

package dev.daarkolabs.tandem.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.protocol.PermissionMode
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Cycle Permission Mode (desktop-parity spec 14.1, 9.7): its registration, its enablement and what it performs. */
@TestApplication
class CyclePermissionModeActionTest {
    private fun event(context: DataContext): AnActionEvent =
        AnActionEvent.createEvent(CyclePermissionModeAction(), context, Presentation(), ActionPlaces.KEYBOARD_SHORTCUT, ActionUiKind.NONE, null)

    private fun withModes(modes: ComposerModes): DataContext = SimpleDataContext.getSimpleContext(COMPOSER_MODES, modes)

    // spec: the action is registered with Shift+Tab in the default and macOS keymaps
    @Test
    fun `the action is registered with Shift+Tab in the default and macOS keymaps`() {
        assertIs<CyclePermissionModeAction>(ActionManager.getInstance().getAction(CYCLE_MODE_ACTION_ID))
        val shiftTab = KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), null)
        for (name in listOf("\$default", "Mac OS X 10.5+")) {
            val keymap = assertNotNull(KeymapManager.getInstance().getKeymap(name), name)
            assertTrue(shiftTab in keymap.getShortcuts(CYCLE_MODE_ACTION_ID).toList(), name)
        }
    }

    // spec: the action is enabled only with the composer's modes
    @Test
    fun `the action is enabled only with the composer's modes`() {
        val action = CyclePermissionModeAction()
        val without = event(SimpleDataContext.EMPTY_CONTEXT)
        action.update(without)
        assertFalse(without.presentation.isEnabledAndVisible)
        val with = event(withModes { PermissionMode.PLAN })
        action.update(with)
        assertTrue(with.presentation.isEnabled)
        assertTrue(with.presentation.isVisible)
    }

    // spec: performing the action cycles once and survives a null
    @Test
    fun `performing the action cycles once and survives a null`() {
        val action = CyclePermissionModeAction()
        var calls = 0
        action.actionPerformed(event(withModes { calls++; PermissionMode.ACCEPT_EDITS }))
        assertEquals(1, calls)
        var nullCalls = 0
        action.actionPerformed(event(withModes { nullCalls++; null }))
        assertEquals(1, nullCalls)
        // No modes in the context: nothing happens.
        action.actionPerformed(event(SimpleDataContext.EMPTY_CONTEXT))
    }

    // spec: the composer offers no modes while a menu is open
    @Test
    fun `the composer offers no modes while a menu is open`() {
        val modes = ComposerModes { PermissionMode.PLAN }
        assertSame(modes, composerModesFor(modes, 0))
        assertNull(composerModesFor(modes, 1))
        assertNull(composerModesFor(modes, 2))
    }
}

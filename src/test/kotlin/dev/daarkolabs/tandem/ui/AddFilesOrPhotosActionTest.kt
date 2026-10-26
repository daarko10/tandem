package dev.daarkolabs.tandem.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The `Add Files or Photos` action and its shortcut (desktop-parity spec 17.1, 17.4, 9.8). */
@TestApplication
class AddFilesOrPhotosActionTest {
    private class FakeAttach : ComposerAttach {
        var calls: Int = 0

        override fun attach() {
            calls++
        }
    }

    private fun event(context: DataContext): AnActionEvent =
        AnActionEvent.createEvent(context, Presentation(), ActionPlaces.KEYBOARD_SHORTCUT, com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)

    private fun shortcut(menu: Int): KeyboardShortcut =
        KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_O, menu or InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK), null)

    // spec: the action is registered with Ctrl+Alt+Shift+O, which no other action of the three keymaps uses
    @Test
    fun `the action is registered with Ctrl+Alt+Shift+O, which no other action of the three keymaps uses`() {
        assertIs<AddFilesOrPhotosAction>(ActionManager.getInstance().getAction(ADD_FILES_ACTION_ID))
        val keymaps = KeymapManager.getInstance()
        for ((name, menu) in listOf(
            "\$default" to InputEvent.CTRL_DOWN_MASK,
            "Mac OS X 10.5+" to InputEvent.META_DOWN_MASK,
            "Mac OS X" to InputEvent.META_DOWN_MASK,
        )) {
            val keymap = keymaps.getKeymap(name) ?: error("no keymap $name")
            val expected = shortcut(menu)
            assertTrue(expected in keymap.getShortcuts(ADD_FILES_ACTION_ID).toList(), "$name: ${keymap.getShortcuts(ADD_FILES_ACTION_ID).toList()}")
            assertEquals(listOf(ADD_FILES_ACTION_ID), keymap.getActionIdList(expected), name)
        }
    }

    // spec: the action is enabled only with the composer's attach
    @Test
    fun `the action is enabled only with the composer's attach`() {
        val action = AddFilesOrPhotosAction()
        val without = event(SimpleDataContext.builder().build())
        action.update(without)
        assertFalse(without.presentation.isEnabledAndVisible)
        val with = event(SimpleDataContext.builder().add(COMPOSER_ATTACH, FakeAttach()).build())
        action.update(with)
        assertTrue(with.presentation.isEnabled)
        assertTrue(with.presentation.isVisible)
    }

    // spec: performing the action attaches once
    @Test
    fun `performing the action attaches once`() {
        val attach = FakeAttach()
        AddFilesOrPhotosAction().actionPerformed(event(SimpleDataContext.builder().add(COMPOSER_ATTACH, attach).build()))
        assertEquals(1, attach.calls)
    }

    // spec: the chat pane offers no attach while a menu is open (17.4)
    @Test
    fun `the chat pane offers no attach while a menu is open`() {
        val attach = FakeAttach()
        assertSame(attach, composerAttachFor(attach, 0))
        assertNull(composerAttachFor(attach, 1))
        assertNull(composerAttachFor(null, 0))
    }

    // spec: the plus row's keybinding follows the keymap (17.4)
    @Test
    fun `the plus row's keybinding follows the keymap`() {
        val text = KeymapUtil.getFirstKeyboardShortcutText(ADD_FILES_ACTION_ID)
        assertTrue(text.isNotBlank())
        assertEquals(setOf(text), addFilesKeybinding())
        val keymap = KeymapManager.getInstance().activeKeymap
        val original = keymap.getShortcuts(ADD_FILES_ACTION_ID).toList()
        try {
            keymap.removeAllActionShortcuts(ADD_FILES_ACTION_ID)
            assertNull(addFilesKeybinding(), "an unbound action shows nothing")
            val other = KeyboardShortcut(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
                null,
            )
            keymap.addShortcut(ADD_FILES_ACTION_ID, other)
            assertEquals(setOf(KeymapUtil.getShortcutText(other)), addFilesKeybinding())
        } finally {
            keymap.removeAllActionShortcuts(ADD_FILES_ACTION_ID)
            original.forEach { keymap.addShortcut(ADD_FILES_ACTION_ID, it) }
        }
    }
}

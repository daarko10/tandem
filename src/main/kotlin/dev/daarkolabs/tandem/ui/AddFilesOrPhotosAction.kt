package dev.daarkolabs.tandem.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction

// A shortcut for the `+` menu's `Add files or photos` (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 17.1, 17.4).

private val LOG = logger<AddFilesOrPhotosAction>()

/** The action's id in `plugin.xml`, where its default shortcut is Ctrl+Alt+Shift+O (⌘⌥⇧O in the macOS keymaps). */
const val ADD_FILES_ACTION_ID: String = "Tandem.AddFilesOrPhotos"

/** The composer's image chooser, as the chat pane offers it to [AddFilesOrPhotosAction]. */
fun interface ComposerAttach {
    fun attach()
}

/** Provided by the selected session's chat pane while the focus is in it and none of the composer's menus is open. */
val COMPOSER_ATTACH: DataKey<ComposerAttach> = DataKey.create("dev.daarkolabs.tandem.composerAttach")

/**
 * `Add Files or Photos`: opens the composer's image chooser, as the `+` menu's row does. Enabled only where the chat pane offers it
 * ([COMPOSER_ATTACH]), so never in the editor or the sidebar.
 */
class AddFilesOrPhotosAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(COMPOSER_ATTACH) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val attach = e.getData(COMPOSER_ATTACH) ?: return
        LOG.debug(ADD_FILES_ACTION_ID)
        attach.attach()
    }
}

/** What the chat pane offers as [COMPOSER_ATTACH]: [attach] while none of the composer's menus is open ([openMenus] is 0), else null. */
internal fun composerAttachFor(attach: ComposerAttach?, openMenus: Int): ComposerAttach? = attach?.takeIf { openMenus == 0 }

/** The `+` menu row's shortcut text, the action's first keyboard shortcut in the active keymap; null when it has none. */
internal fun addFilesKeybinding(): Set<String>? =
    KeymapUtil.getFirstKeyboardShortcutText(ADD_FILES_ACTION_ID).takeIf { it.isNotBlank() }?.let { setOf(it) }

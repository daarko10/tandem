package dev.daarkolabs.tandem.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.ui.accessibility.AccessibleAnnouncerUtil
import dev.daarkolabs.tandem.chat.Modes
import dev.daarkolabs.tandem.protocol.PermissionMode
import javax.accessibility.Accessible

/** The id of [CyclePermissionModeAction] in `plugin.xml` (desktop-parity spec 14.1). */
const val CYCLE_MODE_ACTION_ID: String = "Tandem.CyclePermissionMode"

/** What the focused composer offers the mode cycle: [cycle] picks the session's next permission mode and returns it, null when none. */
fun interface ComposerModes {
    fun cycle(): PermissionMode?
}

/** The composer's [ComposerModes], in the data context while its text area has the focus and no menu is open ([composerModesFor]). */
val COMPOSER_MODES: DataKey<ComposerModes> = DataKey.create("dev.daarkolabs.tandem.composerModes")

/** What the text area provides as [COMPOSER_MODES]: [modes] while none of the footer menus, the popover or the `+` menu is open. */
internal fun composerModesFor(modes: ComposerModes, openMenus: Int): ComposerModes? = modes.takeIf { openMenus == 0 }

/**
 * Cycle Permission Mode (desktop-parity spec 14.1), Shift+Tab by default: switches the focused Tandem chat to its next permission
 * mode. Enabled only where the composer provides [COMPOSER_MODES], so everywhere else the IDE's own Shift+Tab actions and focus
 * traversal run as before. Performed on the EDT, it only takes the session's lock and queues a frame (hard rule 1); a screen reader
 * hears the new mode, since the change shows in the footer, outside the focused text area.
 */
class CyclePermissionModeAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(COMPOSER_MODES) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val mode = e.getData(COMPOSER_MODES)?.cycle() ?: return
        LOG.debug("permission mode cycle picked ${mode.wire}")
        val component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) as? Accessible ?: return
        if (AccessibleAnnouncerUtil.isAnnouncingAvailable()) {
            AccessibleAnnouncerUtil.announce(component, "Permission mode ${Modes.label(mode)}", true)
        }
    }

    private companion object {
        val LOG = logger<CyclePermissionModeAction>()
    }
}

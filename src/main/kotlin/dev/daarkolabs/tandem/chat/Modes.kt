package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.OFFERED_MODES
import java.util.Locale

/**
 * The permission modes the footer's mode menu offers, with their labels, descriptions and digits, and the effort labels (spec 2.3).
 * The modes are [OFFERED_MODES], the ones a process may start in; the new-session popup labels them the same way.
 */
object Modes {
    /**
     * The modes the mode menu lists: [OFFERED_MODES] in order, without `auto` unless the shown model supports it ([autoSupported])
     * or the session runs it, then a [current] mode Tandem does not offer (bypassPermissions, dontAsk), checked.
     */
    fun offered(current: PermissionMode?, autoSupported: Boolean): List<PermissionMode> {
        val offered = OFFERED_MODES.filter { it != PermissionMode.AUTO || autoSupported || current == PermissionMode.AUTO }
        return if (current == null || current in offered) offered else offered + current
    }

    /** The modes Shift+Tab cycles through, in the CLI's order, not the menu's (desktop-parity spec 14.1). */
    val CYCLE: List<PermissionMode> = listOf(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN, PermissionMode.AUTO)

    /**
     * The mode after [current] in [CYCLE] (desktop-parity spec 14.1): walks it from [current]'s slot, wrapping, and returns the first
     * allowed mode, [PermissionMode.AUTO] only with [autoSupported] and never one of [rejected]; else Manual. A mode of the cycle starts
     * at its own slot, bypassPermissions at Plan's, dontAsk at Auto's and no mode yet at Manual's (the CLI's own start), so the first
     * press asks for Accept edits. Never bypassPermissions or dontAsk.
     */
    fun next(current: PermissionMode?, autoSupported: Boolean, rejected: Set<PermissionMode> = emptySet()): PermissionMode {
        val start = CYCLE.indexOf(
            when (current) {
                null -> PermissionMode.DEFAULT
                PermissionMode.BYPASS_PERMISSIONS -> PermissionMode.PLAN
                PermissionMode.DONT_ASK -> PermissionMode.AUTO
                else -> current
            },
        )
        for (step in 1..CYCLE.size) {
            val mode = CYCLE[(start + step) % CYCLE.size]
            if ((mode != PermissionMode.AUTO || autoSupported) && mode !in rejected) return mode
        }
        return PermissionMode.DEFAULT
    }

    /**
     * The mode Shift+Tab picks next for [state]: [next] from its mode, with `auto` when the shown model supports it, read as the footer
     * reads it ([composerModel] with the [known] models), so the cycle offers the modes the mode menu lists, and its refused modes.
     */
    fun cycleFrom(state: ChatState, known: List<ModelOption> = emptyList()): PermissionMode =
        next(state.permissionMode, autoSupported(state, known), state.rejectedPermissionModes)

    /** What the footer and the popup call [mode]. */
    fun label(mode: PermissionMode): String = when (mode) {
        PermissionMode.AUTO -> "Auto"
        PermissionMode.DEFAULT -> "Manual"
        PermissionMode.ACCEPT_EDITS -> "Accept edits"
        PermissionMode.PLAN -> "Plan"
        PermissionMode.BYPASS_PERMISSIONS -> "Bypass permissions"
        PermissionMode.DONT_ASK -> "Don't ask"
    }

    /** The line under [mode]'s label in the menu; none for the modes Tandem does not offer. */
    fun description(mode: PermissionMode): String? = when (mode) {
        PermissionMode.AUTO -> "Claude handles permission decisions"
        PermissionMode.DEFAULT -> "Always ask before making changes"
        PermissionMode.ACCEPT_EDITS -> "Automatically accept all file edits"
        PermissionMode.PLAN -> "Create a plan before making changes"
        PermissionMode.BYPASS_PERMISSIONS, PermissionMode.DONT_ASK -> null
    }

    /** The digit that picks [mode] in the open menu: it belongs to the mode, so Plan is 4 whether or not Auto is listed. */
    fun digit(mode: PermissionMode): Int? = when (mode) {
        PermissionMode.AUTO -> 1
        PermissionMode.DEFAULT -> 2
        PermissionMode.ACCEPT_EDITS -> 3
        PermissionMode.PLAN -> 4
        PermissionMode.BYPASS_PERMISSIONS, PermissionMode.DONT_ASK -> null
    }

    /** What the footer calls effort [level]: `xhigh` is "Extra high"; an unknown level is shown with its first letter upper-cased. */
    fun effortLabel(level: String): String = when (level) {
        "low" -> "Low"
        "medium" -> "Medium"
        "high" -> "High"
        "xhigh" -> "Extra high"
        "max" -> "Max"
        else -> level.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}

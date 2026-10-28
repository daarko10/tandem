package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.session.CLI_MODEL_ALIASES
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.CommandCatalogue
import dev.daarkolabs.tandem.session.ContextUsage
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.ReturnedSend
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import java.util.Locale

/** The `value` of the CLI's own entry for its default model in an `initialize` model list. */
internal const val DEFAULT_MODEL_ENTRY: String = "default"

/**
 * What the composer and its footer show (spec 4.15, polish spec 2.3), taken from the state so that it changes only when one of its own
 * fields does, not on every streamed delta. [cardOpen]: a request waits for its answer. [defaultModel] is the model the CLI's `default`
 * entry resolves to; [effortLevels] and [autoSupported] are those of the shown model ([shownModel]); [modelSwitchPending] mirrors
 * [ChatState.modelSwitchPending]. [commands], [slashCommandNames] and [terminalCommands] feed the slash palette ([slashPalette]).
 * [shellRunning]: a shell escape runs (MCP spec 12.1); the composer is busy meanwhile, and Stop stops the command ([stopTarget]).
 * [plugins] are the `+` menu's Plugins pages ([pluginMenu]); [pluginsKnown]: an init came, or the catalogue names a plugin's entry;
 * [skills] the latest init's skills, null before an init; [pluginPrefixes] the session's plugins for the palette ([pluginPrefixes]).
 * [busy] counts a message waiting in the CLI's queue too (desktop-parity spec 12.1). [midTurnSends]: the live process takes messages
 * while it works (its init listed both queue capabilities); [capabilitiesKnown]: an init of the live process came (deviation 16).
 * [returned] are the messages that came back without running, for the composer to take back (spec 12.3, 12.5). [acceptAll]: `Accept
 * all` was pressed in a diff of this turn ([ChatState.acceptAll]), which the mode control's hint says (IDE-edits spec 3.4).
 */
data class ComposerModel(
    val busy: Boolean,
    val ended: Boolean,
    val cardOpen: Boolean,
    val models: ImmutableList<ModelOption>,
    val requestedModel: String?,
    val activeModel: String?,
    val modelWarning: String?,
    val permissionMode: PermissionMode?,
    val effort: String?,
    val claudeCodeVersion: String?,
    val defaultModel: String? = null,
    val effortLevels: ImmutableList<String> = persistentListOf(),
    val autoSupported: Boolean = false,
    val modelSwitchPending: Boolean = false,
    val commands: ImmutableList<SlashCommand> = persistentListOf(),
    val slashCommandNames: ImmutableList<String> = persistentListOf(),
    val terminalCommands: ImmutableSet<String> = persistentSetOf(),
    val shellRunning: Boolean = false,
    val plugins: ImmutableList<PluginEntry> = persistentListOf(),
    val pluginsKnown: Boolean = false,
    val skills: ImmutableList<String>? = null,
    val pluginPrefixes: ImmutableSet<String> = persistentSetOf(),
    val midTurnSends: Boolean = false,
    val capabilitiesKnown: Boolean = false,
    val returned: ImmutableList<ReturnedSend> = persistentListOf(),
    val acceptAll: Boolean = false,
)

/** The `system/init` capabilities that let the composer send while Claude works (desktop-parity spec 12.1's gate). */
private val MID_TURN_CAPABILITIES: List<String> = listOf("msg_lifecycle_v1", "interrupt_cancel_queued_v1")

/**
 * The composer's model of [state]. Its models are the session's own ([ChatState.models]), else, while its process has not listed
 * any (it has none, as a restored session before its first prompt), the [known] ones another process listed
 * ([dev.daarkolabs.tandem.session.SessionManager.models]), else, before any process has, the CLI's own aliases ([CLI_MODEL_ALIASES]).
 * Its commands, and the terminal-only names the palette leaves out, are the session's own likewise, else those of [knownCommands]
 * ([dev.daarkolabs.tandem.session.SessionManager.commands]); the session's own replace them once its process offers them. The plugins
 * come from those commands until the session's first `system/init`, then from its `plugins` (spec 3.3, 17.3, 17.6).
 */
fun composerModel(
    state: ChatState,
    known: List<ModelOption> = emptyList(),
    knownCommands: CommandCatalogue = CommandCatalogue(),
): ComposerModel {
    val models = composerModels(state, known)
    val shown = shownModel(models, state.requestedModel, state.activeModel, state.modelSwitchPending)
    val commands = state.commands.ifEmpty { knownCommands.commands }
    val initSeen = state.mcp.initCount > 0
    val initPlugins = state.plugins.takeIf { initSeen }
    val skills = state.skills.takeIf { initSeen }
    val plugins = pluginMenu(initPlugins, commands, state.slashCommandNames, skills)
    return ComposerModel(
        busy = state.busy || state.pendingSends.isNotEmpty(),
        ended = state.ended,
        cardOpen = state.pendingPermissions.isNotEmpty(),
        models = models.toImmutableList(),
        requestedModel = state.requestedModel,
        activeModel = state.activeModel,
        modelWarning = state.modelWarning,
        permissionMode = state.permissionMode,
        effort = state.effort,
        claudeCodeVersion = state.claudeCodeVersion,
        defaultModel = models.firstOrNull { it.value == DEFAULT_MODEL_ENTRY }?.resolvedModel,
        effortLevels = shown?.effortLevels.orEmpty().toImmutableList(),
        autoSupported = shown?.supportsAutoMode == true,
        modelSwitchPending = state.modelSwitchPending,
        commands = commands.toImmutableList(),
        slashCommandNames = state.slashCommandNames.toImmutableList(),
        terminalCommands = state.terminalCommands.ifEmpty { knownCommands.terminal }.toImmutableSet(),
        shellRunning = state.runningShell != null,
        plugins = plugins,
        pluginsKnown = initSeen || plugins.isNotEmpty(),
        skills = skills?.toImmutableList(),
        pluginPrefixes = pluginPrefixes(initPlugins, commands).toImmutableSet(),
        midTurnSends = state.cliCapabilities?.containsAll(MID_TURN_CAPABILITIES) == true && !state.ended,
        capabilitiesKnown = state.cliCapabilities != null,
        returned = state.returnedSends.toImmutableList(),
        acceptAll = state.acceptAll,
    )
}

/** The models the composer offers for [state]: its own, else the [known] ones, else the CLI's own aliases ([composerModel]). */
private fun composerModels(state: ChatState, known: List<ModelOption>): List<ModelOption> =
    state.models.ifEmpty { known }.ifEmpty { CLI_MODEL_ALIASES }

/** Whether the model the footer shows for [state] supports the `auto` mode, as [ComposerModel.autoSupported] reads it. */
internal fun autoSupported(state: ChatState, known: List<ModelOption> = emptyList()): Boolean =
    shownModel(composerModels(state, known), state.requestedModel, state.activeModel, state.modelSwitchPending)?.supportsAutoMode == true

/** A draft that is a shell escape: `!` as its first non-blank character, then a command that is not blank. */
private val SHELL_ESCAPE = Regex("^\\s*!\\s*\\S")

/**
 * The command of [draft] when it is a shell escape (MCP spec 12.1, `^\s*!\s*\S`): the text after its `!` with the whitespace around
 * it removed and the newlines inside it kept; else null. A bare `!` is no shell escape: it goes to the CLI as text.
 */
fun shellCommand(draft: String): String? {
    if (!SHELL_ESCAPE.containsMatchIn(draft)) return null
    return draft.substringAfter('!').trim().ifEmpty { null }
}

/** What the composer's Stop (its button or Esc) stops (MCP spec 12.3). */
enum class StopTarget {
    /** The shell escape that runs. */
    Shell,

    /** The turn that runs, as before shell escapes. */
    Turn,
}

/** [StopTarget.Shell] while a shell escape runs ([shellRunning]), else [StopTarget.Turn]. */
fun stopTarget(shellRunning: Boolean): StopTarget = if (shellRunning) StopTarget.Shell else StopTarget.Turn

/**
 * The entry of [models] that the footer shows, checks and takes the effort levels and `auto` support from (spec 2.3, `<Model>`):
 * 1. while a pick waits for its `system/init` ([switchPending]), the [requested] entry, by value; with none requested (the
 *    Default-badged pick, sent as `set_model null`), the entry the `default` entry resolves to;
 * 2. else, once the CLI has reported the [active] model, the entry other than `default` that resolves to it (its `resolvedModel`, or its
 *    value without one): of several, the requested one, else the first;
 * 3. before that, the requested entry, else the entry the `default` entry resolves to.
 * Null when no entry stands for it (4: the footer then shows the wire id). A [requested] `default` (the new-session popup lists the
 * CLI's own `default` entry) counts as no request: the entry it resolves to stands for it.
 */
internal fun shownModel(models: List<ModelOption>, requested: String?, active: String?, switchPending: Boolean): ModelOption? {
    val choices = models.filter { it.value != DEFAULT_MODEL_ENTRY }
    val picked = requested?.takeIf { it != DEFAULT_MODEL_ENTRY }
    fun requestedOrDefault(): ModelOption? = if (picked != null) {
        choices.firstOrNull { it.value == picked }
    } else {
        val resolved = models.firstOrNull { it.value == DEFAULT_MODEL_ENTRY }?.resolvedModel ?: return null
        choices.firstOrNull { it.resolvesTo() == resolved }
    }
    return when {
        switchPending || active == null -> requestedOrDefault()
        else -> {
            val running = choices.filter { it.resolvesTo() == active }
            running.firstOrNull { it.value == picked } ?: running.firstOrNull()
        }
    }
}

/** The model id this entry stands for. */
private fun ModelOption.resolvesTo(): String = resolvedModel ?: value

/**
 * What the footer calls model [id], a wire id such as `claude-opus-5-5` or a value such as `opus`, when no entry is shown for it:
 * the display name of the entry of [models] other than `default` that stands for it (its `resolvedModel`) or is it (its value); else of an
 * entry that names no model, an alias such as [CLI_MODEL_ALIASES]' `opus`, that is one of the id's words (its family); else [id].
 */
fun modelDisplayName(id: String, models: List<ModelOption>): String {
    val choices = models.filter { it.value != DEFAULT_MODEL_ENTRY }
    choices.firstOrNull { it.resolvesTo() == id || it.value == id }?.let { return it.displayName }
    val words = id.split('-')
    return choices.firstOrNull { it.resolvedModel == null && it.value in words }?.displayName ?: id
}

/**
 * The context ring's text and arc (polish spec 2.3): `<used> / <window> (<pct>%)`, as the tooltip and the popover show it, and the
 * share of the window in use. Both are null while the tokens or the window are unknown, and the ring is then not drawn.
 */
object ContextFormat {
    /** `75.6k / 1M (8%)`: the tokens by [formatTokens], the window by [formatWindow], the share rounded to a whole percent. */
    fun value(usage: ContextUsage): String? {
        val tokens = usage.tokens ?: return null
        val window = usage.window ?: return null
        val pct = if (window == 0L) 0L else Math.round(100.0 * tokens / window)
        return "${formatTokens(tokens)} / ${formatWindow(window)} ($pct%)"
    }

    /** The share of the window in use, from 0 to 1 (a count past the window fills the ring); null without both parts or with no window. */
    fun fraction(usage: ContextUsage): Float? {
        val tokens = usage.tokens ?: return null
        val window = usage.window?.takeIf { it != 0L } ?: return null
        return (tokens.toFloat() / window).coerceIn(0f, 1f)
    }

    /**
     * The share of [max] that [tokens] are, to one decimal (`0.3%`, `0.0%` for a sliver), as the CLI's own `/context` table prints it
     * (spec 3.1); `—` without a window.
     */
    fun percentOf(tokens: Long, max: Long): String =
        if (max <= 0) "—" else String.format(Locale.ROOT, "%.1f%%", 100.0 * tokens / max)

    /** `200k` under a million; `1M`, `1.5M` from one, with one decimal only when it is not zero (where [formatTokens] gives `1.0m`). */
    fun formatWindow(window: Long): String =
        if (window < 1_000_000) "${window / 1_000}k" else String.format(Locale.ROOT, "%.1f", window / 1_000_000.0).removeSuffix(".0") + "M"
}

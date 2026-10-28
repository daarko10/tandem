package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.PluginInfo
import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.protocol.builtin

// Where a catalogue entry comes from (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 17.3, 17.6; probes 6.4): the one parse
// that the `/` palette and the `+` menu's Plugins pages share, so they never disagree about what a plugin is. Pure.

/**
 * Where an entry comes from: its [scope], the [plugin] it belongs to (a [CommandScope.PLUGIN] entry only), the plugin's [displayName]
 * as the CLI wrote it before the description (null without one), and the [description] without the source's tag.
 */
data class CommandSource(val scope: CommandScope, val plugin: String?, val displayName: String?, val description: String)

private const val DYNAMIC_WORKFLOW_SUFFIX: String = " (dynamic workflow)"
private const val PLUGIN_SUFFIX: String = " (plugin)"
private const val CLAUDE_AI_SUFFIX: String = " (claude.ai sync)"
private const val MCP_PREFIX: String = "mcp__"

/** The settings sources' exact suffixes and their scopes, in the order they are checked. */
private val SUFFIXES: List<Pair<String, CommandScope>> = listOf(
    " (project)" to CommandScope.PROJECT,
    " (project, gitignored)" to CommandScope.PROJECT,
    " (user)" to CommandScope.USER,
    CLAUDE_AI_SUFFIX to CommandScope.CLAUDE_AI,
    " (managed)" to CommandScope.MANAGED,
    " (cli flag)" to CommandScope.CLI_FLAG,
)

/**
 * The session's plugin names (spec 17.3, 17.6): with an init ([plugins] not null), its plugins that are not built-in; before one, the
 * prefixes `<p>` of the catalogue's entries `<p>:<rest>` (a non-empty rest) at least one of which has a parenthesised description
 * prefix, never [SYNCED_SKILLS_NAMESPACE]; in the init's or the catalogue's order.
 */
fun pluginPrefixes(plugins: List<PluginInfo>?, commands: List<SlashCommand>): Set<String> {
    if (plugins != null) return plugins.filterNot { it.builtin }.mapTo(LinkedHashSet()) { it.name }
    val prefixes = LinkedHashSet<String>()
    for (command in commands) {
        val prefix = pluginPrefix(command.name) ?: continue
        if (prefix != SYNCED_SKILLS_NAMESPACE && parenthesisedPrefix(command.description) != null) prefixes += prefix
    }
    return prefixes
}

/**
 * Where entry [name] comes from (probes 6.4's order, spec 17.6): [builtin] first (only a trailing ` (dynamic workflow)` is stripped);
 * then a name `<p>:<rest>` with `<p>` in [plugins] (its leading `(<display name>) ` stripped, else a trailing ` (plugin)` or
 * ` (claude.ai sync)`); then a settings source's exact suffix, stripped; then a name in the claude.ai-synced namespace; then an MCP
 * prompt's `mcp__<server>__<prompt>`; else [CommandScope.OTHER] with the description unchanged.
 */
fun commandSource(name: String, description: String, builtin: Boolean, plugins: Set<String>): CommandSource {
    if (builtin) return CommandSource(CommandScope.BUILTIN, null, null, description.removeSuffix(DYNAMIC_WORKFLOW_SUFFIX))
    val prefix = pluginPrefix(name)
    if (prefix != null && prefix in plugins) {
        val display = parenthesisedPrefix(description)
        val text = if (display != null) {
            description.substring(display.length + 2).removePrefix(" ")
        } else {
            description.removeSuffix(PLUGIN_SUFFIX).removeSuffix(CLAUDE_AI_SUFFIX)
        }
        return CommandSource(CommandScope.PLUGIN, prefix, display, text)
    }
    SUFFIXES.firstOrNull { description.endsWith(it.first) }?.let { (suffix, scope) ->
        return CommandSource(scope, null, null, description.removeSuffix(suffix))
    }
    val scope = when {
        name.startsWith("$SYNCED_SKILLS_NAMESPACE:") -> CommandScope.CLAUDE_AI
        isMcpPrompt(name) -> CommandScope.MCP
        else -> CommandScope.OTHER
    }
    return CommandSource(scope, null, null, description)
}

/** The `<p>` of a name `<p>:<rest>` whose prefix and rest are not empty; null for any other name. */
internal fun pluginPrefix(name: String): String? {
    val colon = name.indexOf(':')
    return if (colon <= 0 || colon == name.lastIndex) null else name.substring(0, colon)
}

/** The text between a leading `(` and the first `)` of [description]; null without both. */
internal fun parenthesisedPrefix(description: String): String? {
    if (!description.startsWith("(")) return null
    val close = description.indexOf(')')
    return if (close < 0) null else description.substring(1, close)
}

/** `mcp__<server>__<prompt>`, both parts not empty. */
private fun isMcpPrompt(name: String): Boolean {
    if (!name.startsWith(MCP_PREFIX)) return false
    val rest = name.substring(MCP_PREFIX.length)
    val separator = rest.indexOf("__")
    return separator > 0 && separator + 2 < rest.length
}

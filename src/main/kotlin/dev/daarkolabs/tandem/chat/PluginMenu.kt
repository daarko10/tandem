package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.PluginInfo
import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.protocol.builtin
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

// The `+` menu's Plugins pages (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 3.3, 17.3, 17.5): pure, built off the EDT.

/** The CLI's constant namespace of claude.ai-synced skills: a colon with no plugin behind it, so never a plugin (probes 6.1). */
const val SYNCED_SKILLS_NAMESPACE: String = "anthropic-skills"

/** Whether a plugin's row is a skill (the init's `skills` list it, or before an init its bare-name alias) or a command. */
enum class PluginItemKind { SKILL, COMMAND }

/**
 * One row of a plugin's page: [name] after the colon, the [command] a pick inserts (`/<plugin>:<name>`), the [description] without its
 * source tag, and the [tooltip] (the description, then the argument hint on its own line).
 */
data class PluginCommand(val name: String, val command: String, val description: String, val tooltip: String, val kind: PluginItemKind)

/**
 * One plugin of the session: [key] its name made unique in the menu (`kit`, `kit#2`), [name] as the CLI names it, [label] its display
 * name when its entries agree on one, else its name; [version] and [source] the init's (null before an init); its [skills] and
 * [commands] in the catalogue's order.
 */
data class PluginEntry(
    val key: String,
    val name: String,
    val label: String,
    val version: String?,
    val source: String?,
    val skills: ImmutableList<PluginCommand>,
    val commands: ImmutableList<PluginCommand>,
)

/**
 * The plugins of the `+` menu (spec 3.3, 17.3). With an init ([plugins] not null), its plugins that are not built-in, in its order;
 * before one, one entry per plugin prefix of the catalogue ([pluginPrefixes]). A plugin's rows are the catalogue's entries ([commands])
 * named `<plugin>:<rest>`, else, while the catalogue is empty, the init's [names] so named (no description then). A row is a skill when
 * [skills] (the init's; null before an init) lists its full name, or before an init when its aliases hold its bare name.
 */
fun pluginMenu(plugins: List<PluginInfo>?, commands: List<SlashCommand>, names: List<String>, skills: List<String>?): ImmutableList<PluginEntry> {
    val prefixes = pluginPrefixes(plugins, commands)
    val catalogue = commands.ifEmpty { names.map { SlashCommand(it) } }
    val listed = plugins?.filterNot { it.builtin } ?: prefixes.map { PluginInfo(it, null, null, null) }
    val skillNames = skills?.toHashSet()
    val keys = HashSet<String>()
    return listed.map { plugin ->
        val sources = catalogue.mapNotNull { command ->
            if (pluginPrefix(command.name) != plugin.name) return@mapNotNull null
            command to commandSource(command.name, command.description, command.builtin, prefixes)
        }
        val rows = sources.map { (command, source) -> row(plugin.name, command, source, skillNames) }
        val displayNames = sources.mapNotNullTo(HashSet()) { it.second.displayName }
        PluginEntry(
            key = uniqueKey(plugin.name, keys),
            name = plugin.name,
            label = displayNames.singleOrNull() ?: plugin.name,
            version = plugin.version,
            source = plugin.source,
            skills = rows.filter { it.kind == PluginItemKind.SKILL }.toImmutableList(),
            commands = rows.filter { it.kind == PluginItemKind.COMMAND }.toImmutableList(),
        )
    }.toImmutableList()
}

/** What a pick puts in the composer (spec 3.3): [command] and a space at the start of [draft], whatever was typed after it. */
fun withCommand(draft: String, command: String): String = "$command " + draft.trimStart()

private fun row(plugin: String, command: SlashCommand, source: CommandSource, skills: Set<String>?): PluginCommand {
    val rest = command.name.substring(plugin.length + 1)
    val skill = if (skills != null) command.name in skills else rest in command.aliases
    return PluginCommand(
        name = rest,
        command = "/${command.name}",
        description = source.description,
        tooltip = listOf(source.description, command.argumentHint).filter { it.isNotBlank() }.joinToString("\n"),
        kind = if (skill) PluginItemKind.SKILL else PluginItemKind.COMMAND,
    )
}

/** [base], or `base#2`, `base#3`… when an earlier entry took it. */
private fun uniqueKey(base: String, keys: MutableSet<String>): String {
    var key = base
    var n = 1
    while (!keys.add(key)) key = "$base#${++n}"
    return key
}

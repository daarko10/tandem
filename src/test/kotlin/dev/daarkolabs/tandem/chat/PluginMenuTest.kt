package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.PluginInfo
import dev.daarkolabs.tandem.protocol.SlashCommand
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `+` menu's Plugins pages (spec 3.3, 17.3, 17.5) from `parity-add-dir:1` (the catalogue) and `:5` (the init). The entries marked
 * "probes 6.1" are built here in the shapes probes 6.1 quotes, with made-up names: the probe's run is not a fixture.
 */
class PluginMenuTest {
    private val init5: CliMessage.SystemInit = fixtureLine("parity-add-dir", 5) as CliMessage.SystemInit
    private val catalogue: List<SlashCommand> =
        ControlPayloads.initialize((fixtureLine("parity-add-dir", 1) as CliMessage.ControlResponse).payload)!!.commands
    private val greet = PluginCommand(
        "greet",
        "/probe-kit:greet",
        "Greets the user by name in one short sentence.",
        "Greets the user by name in one short sentence.",
        PluginItemKind.SKILL,
    )
    private val hello =
        PluginCommand("hello", "/probe-kit:hello", "Says hello from a plugin command", "Says hello from a plugin command", PluginItemKind.COMMAND)

    private fun plugin(name: String, source: String? = "$name@inline"): PluginInfo = PluginInfo(name, "/plugins/$name", source, null)

    @Test
    fun `built-in plugins are left out`() {
        val entries = pluginMenu(init5.plugins, catalogue, init5.slashCommands, init5.skills)
        assertEquals(listOf("probe-kit"), entries.map { it.name })
        val entry = entries.single()
        assertEquals("0.1.0", entry.version)
        assertEquals("probe-kit@inline", entry.source)
        assertEquals("probe-kit", entry.label)
        assertEquals("probe-kit", entry.key)
    }

    @Test
    fun `a plugin lists its skills and commands with the prefix stripped`() {
        val entry = pluginMenu(init5.plugins, catalogue, init5.slashCommands, init5.skills).single()
        assertEquals(listOf(greet), entry.skills)
        assertEquals(listOf(hello), entry.commands)
    }

    // spec: skills come from init.skills, else from the bare-name alias
    @Test
    fun `skills come from init dot skills, else from the bare-name alias`() {
        val fromInit = pluginMenu(init5.plugins, catalogue, init5.slashCommands, init5.skills).single()
        assertEquals(listOf(greet) to listOf(hello), fromInit.skills to fromInit.commands)
        val fromAlias = pluginMenu(null, catalogue, emptyList(), null).single()
        assertEquals(listOf(greet) to listOf(hello), fromAlias.skills to fromAlias.commands)
        val none = pluginMenu(init5.plugins, catalogue, init5.slashCommands, emptyList()).single()
        assertTrue(none.skills.isEmpty())
        assertEquals(listOf("hello", "greet"), none.commands.map { it.name })
        assertTrue(none.commands.all { it.kind == PluginItemKind.COMMAND })
    }

    @Test
    fun `without the catalogue the init's names stand in`() {
        val entry = pluginMenu(init5.plugins, emptyList(), init5.slashCommands, init5.skills).single()
        assertEquals(listOf(PluginCommand("greet", "/probe-kit:greet", "", "", PluginItemKind.SKILL)), entry.skills)
        assertEquals(listOf(PluginCommand("hello", "/probe-kit:hello", "", "", PluginItemKind.COMMAND)), entry.commands)
    }

    @Test
    fun `a plugin whose name prefixes another's lists only its own commands`() {
        val commands = listOf(SlashCommand("kit:a"), SlashCommand("kit-pro:b"), SlashCommand("kit:"), SlashCommand("kitb"))
        val entries = pluginMenu(listOf(plugin("kit"), plugin("kit-pro")), commands, emptyList(), emptyList())
        assertEquals(listOf("a"), entries[0].commands.map { it.name })
        assertEquals(listOf("b"), entries[1].commands.map { it.name })
    }

    @Test
    fun `before an init the catalogue's plugin prefixes stand in`() {
        assertEquals(
            listOf(PluginEntry("probe-kit", "probe-kit", "probe-kit", null, null, persistentListOf(greet), persistentListOf(hello))),
            pluginMenu(null, catalogue, emptyList(), null),
        )
        assertTrue(pluginMenu(null, emptyList(), emptyList(), null).isEmpty())
    }

    @Test
    fun `before an init a prefix counts only with a parenthesised description, and anthropic-skills never`() {
        // probes 6.1's shapes, made-up names.
        val commands = listOf(
            SlashCommand("anthropic-skills:skill-y", "(anthropic-skills) Does y"),
            SlashCommand("team:z", "Does z (project)"),
            SlashCommand("team:w", "(team w"),
            SlashCommand("plugin-a:x", "(Plugin-A) Does x"),
        )
        assertEquals(listOf("plugin-a"), pluginMenu(null, commands, emptyList(), null).map { it.name })
    }

    @Test
    fun `the display name labels a plugin only when its entries agree`() {
        // probes 6.1's shapes, made-up names.
        val agree = listOf(SlashCommand("plugin-a:one", "(Plugin-A) One"), SlashCommand("plugin-a:two", "(Plugin-A) Two"))
        val entry = pluginMenu(null, agree, emptyList(), null).single()
        assertEquals("Plugin-A", entry.label)
        assertEquals(listOf("One", "Two"), entry.commands.map { it.description })
        val disagree = listOf(SlashCommand("plugin-a:one", "(Plugin-A) One"), SlashCommand("plugin-a:two", "(PluginA) Two"))
        assertEquals("plugin-a", pluginMenu(null, disagree, emptyList(), null).single().label)
        assertEquals("lonely", pluginMenu(listOf(plugin("lonely")), agree, emptyList(), emptyList()).single().label)
    }

    @Test
    fun `a description that is only the prefix leaves none`() {
        val bare = pluginMenu(null, listOf(SlashCommand("plugin-a:x", "(Plugin-A)")), emptyList(), null).single().commands.single()
        assertEquals("" to "", bare.description to bare.tooltip)
        val hinted = SlashCommand("plugin-a:x", "(Plugin-A) Does x", argumentHint = "<name>")
        assertEquals("Does x\n<name>", pluginMenu(null, listOf(hinted), emptyList(), null).single().commands.single().tooltip)
    }

    @Test
    fun `a plugin with neither kind has empty lists`() {
        val entry = pluginMenu(listOf(plugin("hooks-only")), catalogue, emptyList(), emptyList()).single()
        assertEquals("hooks-only@inline", entry.source)
        assertTrue(entry.skills.isEmpty())
        assertTrue(entry.commands.isEmpty())
    }

    @Test
    fun `two init plugins with one name give two entries with the same rows`() {
        val entries = pluginMenu(listOf(plugin("kit", "kit@a"), plugin("kit", "kit@b")), listOf(SlashCommand("kit:x")), emptyList(), emptyList())
        assertEquals(listOf("kit", "kit#2"), entries.map { it.key })
        assertEquals(listOf("kit@a", "kit@b"), entries.map { it.source })
        assertEquals(listOf(listOf("x"), listOf("x")), entries.map { e -> e.commands.map { it.name } })
    }

    @Test
    fun `withCommand puts the command first`() {
        assertEquals("/probe-kit:greet ", withCommand("", "/probe-kit:greet"))
        assertEquals("/probe-kit:greet Ada ", withCommand("  Ada ", "/probe-kit:greet"))
    }
}

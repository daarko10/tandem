package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.session.ModelOption
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The slash palette (spec 3.4, 4.3, 7): its catalogue is the initialize answer of `mcp-slash:1`, its terminal names those of
 * `mcp-slash:4`. The `user` and `plugin` commands are synthetic (unrecorded).
 */
class SlashPaletteTest {
    private val catalogue: List<SlashCommand> =
        ControlPayloads.initialize((fixtureLine("mcp-slash", 1) as CliMessage.ControlResponse).payload)!!.commands
    private val init: CliMessage.SystemInit = fixtureLine("mcp-slash", 4) as CliMessage.SystemInit
    private val terminal: Set<String> = init.terminalSlashCommands.toSet()
    private val models: List<ModelOption> = listOf(ModelOption("opus", "Opus 5.5", null), ModelOption("sonnet", "Sonnet 5.5", null))

    private fun palette(draft: String, selected: Int = 0): SlashPalette = assertNotNull(slashPalette(draft, catalogue, emptyList(), terminal, selected))

    private fun SlashPalette.names(): List<String> = items.map { it.name }

    private fun SlashPalette.item(name: String): PaletteItem = items.single { it.name == name }

    private fun stored(draft: String, selection: Pair<String, Int>, focused: Boolean = true, dismissedFor: String? = null): SlashPalette? =
        paletteFor(draft, focused, dismissedFor, selection, catalogue, emptyList(), terminal)

    @Test
    fun `closed unless the draft is one slash token`() {
        for (draft in listOf("", "hi", "/mcp x", "/mcp ", " /mcp", "/a\nb", "//x y", "mcp/")) {
            assertNull(slashPalette(draft, catalogue, emptyList(), terminal), draft)
        }
        val all = palette("/")
        assertEquals("/", all.query)
        assertEquals(70, all.items.size)
        assertEquals("/mcp", palette("/mcp").query)
        assertNotNull(slashPalette("//", catalogue, emptyList(), terminal))
    }

    @Test
    fun `ranks exact, prefix, alias with via and substring`() {
        val rev = palette("/rev")
        assertEquals("review", rev.items[0].name)
        assertNull(rev.items[0].via)
        val codeReview = rev.items.indexOfFirst { it.name == "code-review" }
        assertEquals("review", rev.items[codeReview].via)
        val substrings = listOf("security-review", "ultrareview").map { name -> rev.items.indexOfFirst { it.name == name } }
        assertTrue(substrings.all { it > codeReview }, rev.names().toString())
        assertTrue(rev.items.filter { it.name != "code-review" }.all { it.via == null })

        // Every rank, catalogue order within one.
        val synthetic = listOf(
            SlashCommand("xab", "substring"),
            SlashCommand("abx", "prefix"),
            SlashCommand("zz", "alias", aliases = listOf("other", "abc")),
            SlashCommand("ab", "exact"),
            SlashCommand("yab", "substring two"),
            SlashCommand("abz", "prefix two", aliases = listOf("ab")),
        )
        val ranked = assertNotNull(slashPalette("/ab", synthetic, emptyList(), emptySet()))
        assertEquals(listOf("ab", "abx", "abz", "zz", "xab", "yab"), ranked.names())
        assertEquals(listOf(null, null, null, "abc", null, null), ranked.items.map { it.via })
        // An empty query lists every row in catalogue order, then Tandem's appended rows.
        val empty = assertNotNull(slashPalette("/", synthetic, emptyList(), emptySet()))
        assertEquals(synthetic.map { it.name } + listOf("mcp", "model", "permissions", "mode"), empty.names())
        assertNull(empty.footer)
    }

    @Test
    fun `an exact name beats an alias`() {
        val review = palette("/review")
        assertEquals("review", review.items[0].name)
        assertEquals(CommandScope.PROJECT, review.items[0].scope)
        assertNull(review.items[0].via)
        assertEquals("code-review", review.items[1].name)
        assertEquals("review", review.items[1].via)
    }

    @Test
    fun `terminal names and remote-workflow are hidden`() {
        val names = palette("/").names()
        for (hidden in listOf("doctor", "color", "focus", "reload-plugins", "__remote-workflow")) assertFalse(hidden in names, hidden)
        assertEquals(names.size, names.toSet().size)
        assertTrue("workflow-launch-exec" in names)
        // Typed in full it is not listed either: it is still sent as text.
        assertTrue(palette("/__remote-workflow").items.isEmpty())
        assertEquals(PaletteOutcome.Send("/__remote-workflow"), pick(palette("/__remote-workflow"), insert = false, busy = false, models))
        // Absent terminal names hide nothing.
        assertTrue("doctor" in assertNotNull(slashPalette("/", catalogue, emptyList(), emptySet())).names())
    }

    @Test
    fun `project, user and plugin labels`() {
        val hello = palette("/hello").item("hello")
        assertEquals(CommandScope.PROJECT, hello.scope)
        assertEquals("Says hello from a project command", hello.description)
        val usage = palette("/usage").item("usage")
        assertEquals(CommandScope.BUILTIN, usage.scope)
        assertEquals("Show session cost, plan usage, and what's contributing to your limits", usage.description)

        val synthetic = listOf(
            SlashCommand("mine", "Mine (user)"),
            SlashCommand("plug:do", "Do it"),
            SlashCommand("ours", "Ours (project)"),
            SlashCommand("plain", "Plain (team)"),
        )
        val items = assertNotNull(slashPalette("/", synthetic, emptyList(), emptySet())).items
        assertEquals(
            listOf(
                Triple("mine", CommandScope.USER, "Mine"),
                Triple("plug:do", CommandScope.PLUGIN, "Do it"),
                Triple("ours", CommandScope.PROJECT, "Ours"),
                Triple("plain", CommandScope.BUILTIN, "Plain (team)"),
            ),
            items.take(4).map { Triple(it.name, it.scope, it.description) },
        )
        assertTrue(items.drop(4).all { it.scope == CommandScope.BUILTIN })
    }

    @Test
    fun `selection is clamped`() {
        val mc = palette("/mc", selected = 99)
        assertEquals(listOf("mcp", "ide-diagnostics-mcp"), mc.names())
        assertEquals(1, mc.selected)
        assertEquals(0, palette("/mc", selected = -3).selected)
        assertEquals(0, mc.moved(-10).selected)
        assertEquals(1, mc.moved(10).selected)
        assertEquals(0, mc.moved(-1).selected)
        assertEquals(1, mc.moved(-1).moved(1).selected)
        assertEquals(mc.items, mc.moved(-1).items)
        val none = palette("/nosuch", selected = 4)
        assertEquals(0, none.selected)
        assertEquals(0, none.moved(1).selected)
        assertEquals(0, none.moved(-1).selected)
    }

    @Test
    fun `a hint with a placeholder inserts and a bracketed or empty one sends`() {
        val all = palette("/")
        assertFalse(all.item("compact").sendsAtOnce)
        assertTrue(all.item("clear").sendsAtOnce)
        assertTrue(all.item("usage").sendsAtOnce)
        // A placeholder inside every bracket group sends; one outside any inserts.
        assertTrue(all.item("code-review").sendsAtOnce)
        assertTrue(all.item("design-sync").sendsAtOnce)
        assertFalse(all.item("effort").sendsAtOnce)
        assertFalse(all.item("batch").sendsAtOnce)
        assertFalse(all.item("auto-mode-setup").sendsAtOnce)

        fun item(hint: String) = PaletteItem("x", "", hint, CommandScope.BUILTIN, via = null, intercepted = false)
        assertTrue(item("[a [<b>] c]").sendsAtOnce)
        assertFalse(item("[a [b] c] <d>").sendsAtOnce)
        assertFalse(item("] <d>").sendsAtOnce)
        assertTrue(item("key=value").sendsAtOnce)
        assertEquals("/x ", item("<y>").insertion())
    }

    @Test
    fun `Tab always inserts`() {
        for (busy in listOf(false, true)) {
            assertEquals(PaletteOutcome.Insert("/usage "), pick(palette("/us"), insert = true, busy = busy, models))
            assertEquals(PaletteOutcome.Insert("/compact "), pick(palette("/compact"), insert = true, busy = busy, models))
            assertEquals(PaletteOutcome.Insert("/mcp "), pick(palette("/mcp"), insert = true, busy = busy, models))
            assertEquals(PaletteOutcome.Ignored, pick(palette("/nosuch"), insert = true, busy = busy, models))
        }
    }

    @Test
    fun `busy picks are ignored except interception and Tab`() {
        assertEquals(PaletteOutcome.Ignored, pick(palette("/usage"), insert = false, busy = true, models))
        assertEquals(PaletteOutcome.Ignored, pick(palette("/compact"), insert = false, busy = true, models))
        assertEquals(PaletteOutcome.Ignored, pick(palette("/nosuch"), insert = false, busy = true, models))
        assertEquals(PaletteOutcome.Intercept(SlashIntercept.OpenMcp), pick(palette("/mcp"), insert = false, busy = true, models))
        assertEquals(PaletteOutcome.Intercept(SlashIntercept.PickModel), pick(palette("/model"), insert = false, busy = true, models))
        assertEquals(
            PaletteOutcome.Intercept(SlashIntercept.PickPermissionMode),
            pick(palette("/permissions"), insert = false, busy = true, models),
        )
        assertEquals(PaletteOutcome.Insert("/usage "), pick(palette("/usage"), insert = true, busy = true, models))
    }

    @Test
    fun `Enter sends the row's name, not the typed prefix`() {
        val us = palette("/us")
        assertEquals("usage", us.items[us.selected].name)
        assertEquals(PaletteOutcome.Send("/usage"), pick(us, insert = false, busy = false, models))
        assertEquals(PaletteOutcome.Send("/clear"), pick(palette("/clear"), insert = false, busy = false, models))
    }

    @Test
    fun `Enter inserts compact with a trailing space`() {
        assertEquals(PaletteOutcome.Insert("/compact "), pick(palette("/compact"), insert = false, busy = false, models))
    }

    @Test
    fun `no match sends the draft and reads No matching command`() {
        val help = palette("/help")
        assertEquals("/help", help.query)
        assertTrue(help.items.isEmpty())
        assertEquals("No matching command", help.footer)
        assertEquals(0, help.selected)
        assertEquals(PaletteOutcome.Send("/help"), pick(assertNotNull(slashPalette("/help", catalogue, emptyList(), terminal)), insert = false, busy = false, models))
        assertNull(palette("/he").footer)
    }

    @Test
    fun `a selection stored for another draft reads as 0`() {
        assertEquals(0, assertNotNull(stored("/mc", selection = "/m" to 2)).selected)
        val kept = assertNotNull(stored("/mc", selection = "/mc" to 2))
        assertEquals(minOf(2, kept.items.lastIndex), kept.selected)
        assertEquals(1, assertNotNull(stored("/mc", selection = "/mc" to 1)).selected)
        assertNull(stored("/mc", selection = "/mc" to 1, dismissedFor = "/mc"))
        assertNotNull(stored("/mcp", selection = "/mc" to 1, dismissedFor = "/mc"))
        assertNull(stored("/mc", selection = "/mc" to 1, focused = false))
        assertNull(stored("/mc x", selection = "/mc x" to 0))
    }

    @Test
    fun `picking mcp or model acts at once whatever the hint`() {
        val all = palette("/")
        val mcp = all.item("mcp")
        val model = all.item("model")
        // The catalogue's own entries have hints (`[reconnect|enable|disable [<server>|all]]`, `<model>`): Tandem's row replaces them.
        assertEquals("[reconnect|enable|disable [<server>|all]]", catalogue.single { it.name == "mcp" }.argumentHint)
        assertEquals("<model>", catalogue.single { it.name == "model" }.argumentHint)
        assertEquals(PaletteItem("mcp", "Manage MCP servers", "", CommandScope.BUILTIN, via = null, intercepted = true), mcp)
        assertEquals(PaletteItem("model", "Pick the model", "", CommandScope.BUILTIN, via = null, intercepted = true), model)
        assertTrue(mcp.sendsAtOnce)
        assertTrue(model.sendsAtOnce)
        // In place: the catalogue's position, not appended.
        assertEquals(catalogue.filter { it.name !in terminal && !it.name.startsWith("__") }.indexOfFirst { it.name == "mcp" }, all.names().indexOf("mcp"))
        assertEquals(PaletteOutcome.Intercept(SlashIntercept.OpenMcp), pick(palette("/mcp"), insert = false, busy = false, models))
        assertEquals(PaletteOutcome.Intercept(SlashIntercept.PickModel), pick(palette("/model"), insert = false, busy = false, models))
        assertEquals(setOf("mcp", "model", "permissions", "mode"), all.items.filter { it.intercepted }.map { it.name }.toSet())
        assertEquals(4, all.items.count { it.intercepted })
    }

    // spec: the catalogue plus "per" lists /permissions
    @Test
    fun `the catalogue plus per lists permissions`() {
        val per = palette("/per")
        assertEquals(listOf("permissions", "fewer-permission-prompts"), per.names())
        assertEquals(PaletteItem("permissions", "Pick the permission mode", "", CommandScope.BUILTIN, via = null, intercepted = true), per.items[0])
        assertEquals(PaletteOutcome.Intercept(SlashIntercept.PickPermissionMode), pick(per, insert = false, busy = false, models))
        // Neither permissions nor mode is in the catalogue: both are appended, in that order.
        assertEquals(listOf("permissions", "mode"), palette("/").names().takeLast(2))
    }

    @Test
    fun `a catalogue that lists permissions shows Tandem's row`() {
        val commands = listOf(SlashCommand("permissions", "Their own words", "<x>", builtin = true), SlashCommand("usage", "Usage"))
        val all = assertNotNull(slashPalette("/", commands, emptyList(), emptySet()))
        assertEquals(listOf("permissions", "usage", "mcp", "model", "mode"), all.names())
        val permissions = all.item("permissions")
        assertEquals("Pick the permission mode", permissions.description)
        assertEquals("", permissions.argumentHint)
        assertTrue(permissions.intercepted)
        // A repeated name is listed once.
        val repeated = listOf(SlashCommand("mcp", "a"), SlashCommand("mcp", "b"), SlashCommand("x"), SlashCommand("x", "again"))
        val twice = assertNotNull(slashPalette("/", repeated, emptyList(), emptySet()))
        assertEquals(listOf("mcp", "x", "model", "permissions", "mode"), twice.names())
        assertEquals("", twice.item("x").description)
    }

    // spec: /mode + Enter gives PickPermissionMode
    @Test
    fun `mode plus Enter gives PickPermissionMode`() {
        val mode = palette("/mode")
        assertEquals("mode", mode.items[0].name)
        assertEquals("model", mode.items[1].name)
        assertEquals(PaletteOutcome.Intercept(SlashIntercept.PickPermissionMode), pick(mode, insert = false, busy = false, models))
        assertEquals("Pick the permission mode", mode.items[0].description)
    }

    @Test
    fun `the fallback palette lists the four rows and the init names`() {
        val names = init.slashCommands
        val fallback = assertNotNull(slashPalette("/", emptyList(), names, terminal))
        assertEquals("Commands load when the session starts", fallback.footer)
        val listed = fallback.names()
        assertEquals(listOf("mcp", "model", "permissions", "mode"), listed.take(4))
        assertTrue(fallback.items.take(4).all { it.intercepted })
        assertFalse("doctor" in listed)
        assertFalse("__remote-workflow" in listed)
        assertEquals(1, listed.count { it == "mcp" })
        assertEquals(1, listed.count { it == "model" })
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(70, listed.size)
        val hello = fallback.item("hello")
        assertEquals(PaletteItem("hello", "", "", CommandScope.BUILTIN, via = null, intercepted = false), hello)
        assertEquals(CommandScope.PLUGIN, assertNotNull(slashPalette("/", emptyList(), listOf("plug:do"), emptySet())).item("plug:do").scope)
        // Filtering works the same way; no match still says the commands load later (the command may exist).
        val mo = assertNotNull(slashPalette("/mo", emptyList(), names, terminal))
        assertEquals(listOf("model", "mode"), mo.names().take(2))
        assertEquals("Commands load when the session starts", assertNotNull(slashPalette("/nosuch", emptyList(), names, terminal)).footer)
        // No names yet: Tandem's four alone.
        assertEquals(listOf("mcp", "model", "permissions", "mode"), assertNotNull(slashPalette("/", emptyList(), emptyList(), emptySet())).names())
    }

    @Test
    fun `before any process offered commands the palette says they load, also when nothing matches`() {
        val none = assertNotNull(slashPalette("/", emptyList(), emptyList(), emptySet()))
        assertEquals(listOf("mcp", "model", "permissions", "mode"), none.names())
        assertEquals("Commands load when the session starts", none.footer)
        // The command may well exist: the footer says why it is not listed, not that there is none.
        val review = assertNotNull(slashPalette("/review", emptyList(), emptyList(), emptySet()))
        assertTrue(review.items.isEmpty())
        assertEquals("Commands load when the session starts", review.footer)
    }

    @Test
    fun `a terminal name Tandem intercepts still lists`() {
        val withMcp = terminal + setOf("mcp", "model", "permissions", "mode")
        val all = assertNotNull(slashPalette("/", catalogue, emptyList(), withMcp))
        for (name in listOf("mcp", "model", "permissions", "mode")) assertEquals(1, all.names().count { it == name }, name)
        assertTrue(all.item("mcp").intercepted)
        val fallback = assertNotNull(slashPalette("/", emptyList(), init.slashCommands, withMcp))
        for (name in listOf("mcp", "model", "permissions", "mode")) assertEquals(1, fallback.names().count { it == name }, name)
    }

    @Test
    fun `a composition in progress does not move the selection`() {
        assertEquals("/m", committedDraft("/mc", 2..2))
        assertEquals("/m", committedDraft("/m", null))
        assertEquals("/mc", committedDraft("/mc", IntRange.EMPTY))
        assertEquals("/", committedDraft("/mc", 1..5))
        val kept = assertNotNull(stored(committedDraft("/mc", 2..2), selection = "/m" to 2))
        assertEquals(minOf(2, kept.items.lastIndex), kept.selected)
        assertEquals(2, kept.selected)
        assertEquals("/m", kept.query)
    }
}

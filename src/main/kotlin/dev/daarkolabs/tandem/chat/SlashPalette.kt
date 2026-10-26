package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.session.ModelOption
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

// The composer's `/` palette, what a pick does and what a sent command does
// (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 3.4, 4.3, 7): pure, cheap enough for every keystroke on the EDT.

/** Where a command comes from ([commandSource], spec 17.6), for the row's label: nothing for a built-in or [OTHER]. */
enum class CommandScope { BUILTIN, PROJECT, USER, PLUGIN, CLAUDE_AI, MANAGED, CLI_FLAG, MCP, OTHER }

/**
 * One row of the palette: `/<name>`, its [argumentHint] and [description] (scope suffix stripped). [via] is the alias the query
 * matched, when only an alias did. [intercepted]: one of the commands Tandem answers itself (`mcp`, `model`, `permissions`, `mode`).
 */
data class PaletteItem(
    val name: String,
    val description: String,
    val argumentHint: String,
    val scope: CommandScope,
    val via: String?,
    val intercepted: Boolean,
)

/**
 * The open palette: [query] is the draft as typed, slash included; [footer] reads `No matching command` when [items] is empty, and
 * `Commands load when the session starts` while no process has offered any.
 */
data class SlashPalette(val query: String, val items: ImmutableList<PaletteItem>, val selected: Int, val footer: String?)

private val PALETTE_DRAFT: Regex = Regex("^/\\S*$")
private const val NO_MATCH: String = "No matching command"
private const val COMMANDS_LOADING: String = "Commands load when the session starts"
private const val INTERNAL_PREFIX: String = "__"

/** Tandem's own rows, which replace the catalogue's entries of the same name and are appended when it lacks them. */
private val TANDEM_ROWS: List<PaletteItem> = listOf(
    "mcp" to "Manage MCP servers",
    "model" to "Pick the model",
    "permissions" to "Pick the permission mode",
    "mode" to "Pick the permission mode",
).map { (name, description) -> PaletteItem(name, description, "", CommandScope.BUILTIN, via = null, intercepted = true) }

/** The names of Tandem's own rows, which are slash commands whatever the catalogue holds. */
internal val TANDEM_NAMES: Set<String> = TANDEM_ROWS.mapTo(HashSet()) { it.name }

/** A catalogue row and the aliases it also matches by. */
private class Entry(val item: PaletteItem, val aliases: List<String>)

/**
 * The palette for [draft], or null unless it is one slash token (`^/\S*$`). The catalogue is [commands] (the initialize answer)
 * without the [terminal] names (never one of Tandem's four) and the `__` names, with Tandem's four rows in place or appended; with no
 * [commands] yet, Tandem's four rows and [fallbackNames] (`init.slash_commands`) under the same filters, and a footer saying the
 * commands load later, also when no row matches (the command may well exist). Rows rank exact name, name prefix, alias prefix,
 * substring, in catalogue order within a rank. [selected] is clamped into the rows. Each row's scope and description are
 * [commandSource]'s, with [plugins] the session's plugins ([pluginPrefixes]).
 */
fun slashPalette(
    draft: String,
    commands: List<SlashCommand>,
    fallbackNames: List<String>,
    terminal: Set<String>,
    selected: Int = 0,
    plugins: Set<String> = emptySet(),
): SlashPalette? {
    if (!PALETTE_DRAFT.matches(draft)) return null
    val fallback = commands.isEmpty()
    val catalogue = if (fallback) fallbackCatalogue(fallbackNames, terminal, plugins) else catalogue(commands, terminal, plugins)
    val items = ranked(draft.drop(1), catalogue)
    val footer = when {
        fallback -> COMMANDS_LOADING
        items.isEmpty() -> NO_MATCH
        else -> null
    }
    return SlashPalette(draft, items, clamp(selected, items), footer)
}

/** This palette with its selection moved by [delta], clamped (no wrap). */
fun SlashPalette.moved(delta: Int): SlashPalette = copy(selected = clamp(selected + delta, items))

/**
 * The palette the composer shows (spec 4.4): null while the text area has no focus ([focused]) or after Esc on this very draft
 * ([dismissedFor]); else [slashPalette] with the stored [selection], which counts only for the draft it was made on (a mismatch reads
 * as 0, so an Enter right after a keystroke never picks by an index into the previous filter). [plugins] are the session's plugins
 * ([ComposerModel.pluginPrefixes]), which label its plugins' entries.
 */
fun paletteFor(
    draft: String,
    focused: Boolean,
    dismissedFor: String?,
    selection: Pair<String, Int>,
    commands: List<SlashCommand>,
    fallbackNames: List<String>,
    terminal: Set<String>,
    plugins: Set<String> = emptySet(),
): SlashPalette? {
    if (!focused || dismissedFor == draft) return null
    return slashPalette(draft, commands, fallbackNames, terminal, selected = if (selection.first == draft) selection.second else 0, plugins = plugins)
}

/** [text] without the input method's [composition] in progress (spec 7, Palette and IME): the palette filters on committed text. */
fun committedDraft(text: String, composition: IntRange?): String {
    if (composition == null || composition.isEmpty()) return text
    val start = composition.first.coerceIn(0, text.length)
    val end = (composition.last + 1).coerceIn(start, text.length)
    return text.removeRange(start, end)
}

/** What Tab puts in the composer, and Enter on a row that waits for its argument: `/<name> `, with the space. */
fun PaletteItem.insertion(): String = "/$name "

/**
 * Enter sends this row at once: Tandem intercepts it, or its hint has no `<…>` placeholder outside every `[...]` group (a text test:
 * `compact`'s optional placeholder inserts on purpose).
 */
val PaletteItem.sendsAtOnce: Boolean
    get() = intercepted || '<' !in outsideBrackets(argumentHint)

/** What sending [text] does (spec 3.4's table, on the trimmed text). */
sealed interface SlashIntercept {
    data object OpenMcp : SlashIntercept

    data object PickModel : SlashIntercept

    /** `/model <name>` names one of the CLI's models: pick it as the footer's menu would. */
    data class SetModel(val model: String) : SlashIntercept

    data object PickPermissionMode : SlashIntercept

    /** Not Tandem's: [text] goes to the CLI as typed. */
    data class Send(val text: String) : SlashIntercept
}

/**
 * The interception table of spec 3.4 on [text], trimmed: `/mcp` opens the panel, `/model` the model menu, `/model <name>` picks the
 * model of [models] whose value or display name is the whole of `<name>` (ignoring case), `/permissions` and `/mode` open the
 * permission-mode menu; anything else, `/mcp <args>` included, is sent as typed. The composer passes [ComposerModel.models], which
 * falls back to the CLI's aliases ([dev.daarkolabs.tandem.session.CLI_MODEL_ALIASES]), so a session that has not listed its models
 * still intercepts `/model <alias>`.
 */
fun slashIntercept(text: String, models: List<ModelOption>): SlashIntercept {
    val trimmed = text.trim()
    return when (trimmed) {
        "/mcp" -> SlashIntercept.OpenMcp
        "/model" -> SlashIntercept.PickModel
        "/permissions", "/mode" -> SlashIntercept.PickPermissionMode
        else -> {
            val rest = trimmed.removePrefix("/model")
            if (rest.length == trimmed.length || !rest.first().isWhitespace()) return SlashIntercept.Send(text)
            val name = rest.trim()
            val option = models.firstOrNull { it.value.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }
            option?.let { SlashIntercept.SetModel(it.value) } ?: SlashIntercept.Send(text)
        }
    }
}

/**
 * Whether sending [draft] runs one of Tandem's own commands of [slashIntercept]'s table instead of going to the CLI: a plain Enter
 * runs it while a turn runs or a card is open too, as a palette pick of its row does (spec 3.4).
 */
fun intercepts(draft: String, models: List<ModelOption>): Boolean = slashIntercept(draft.trim(), models) !is SlashIntercept.Send

/** What a palette pick does; the composer only executes it. */
sealed interface PaletteOutcome {
    data class Intercept(val intercept: SlashIntercept) : PaletteOutcome

    data class Insert(val text: String) : PaletteOutcome

    /** Sends [text]; [later] with `"priority":"later"` (a busy pick, desktop-parity spec 12.1). */
    data class Send(val text: String, val later: Boolean = false) : PaletteOutcome

    /** Nothing happens (not `Nothing`, which would shadow `kotlin.Nothing`). */
    data object Ignored : PaletteOutcome
}

/**
 * Picks the selected row of [palette] (spec 4.3): with [insert] (Tab), `/<name> ` on any row, busy or not; else an intercepted row
 * acts at once, busy or not; a row waiting for its argument inserts `/<name> `, any other sends `/<name>` (the row's name, not the
 * typed prefix). With no row, Enter sends the draft as typed and Tab does nothing. While [busy], a pick that would send sends it
 * `later` when the process takes messages while it works ([midTurn]: D8's after-the-turn outcome for `queue-later-command`,
 * desktop-parity spec 12.1), else does nothing.
 */
fun pick(palette: SlashPalette, insert: Boolean, busy: Boolean, models: List<ModelOption>, midTurn: Boolean = false): PaletteOutcome {
    val item = palette.items.getOrNull(clamp(palette.selected, palette.items))
    return when {
        item == null && insert -> PaletteOutcome.Ignored
        item != null && insert -> PaletteOutcome.Insert(item.insertion())
        item != null && item.intercepted -> PaletteOutcome.Intercept(slashIntercept("/${item.name}", models))
        busy && !midTurn -> PaletteOutcome.Ignored
        item == null -> PaletteOutcome.Send(palette.query, later = busy)
        !item.sendsAtOnce -> PaletteOutcome.Insert(item.insertion())
        else -> PaletteOutcome.Send("/${item.name}", later = busy)
    }
}

private fun clamp(selected: Int, items: List<PaletteItem>): Int = selected.coerceIn(0, maxOf(0, items.lastIndex))

/** [hint] without its `[...]` groups, nested ones counted (an unmatched `]` is dropped). */
private fun outsideBrackets(hint: String): String = buildString {
    var depth = 0
    for (c in hint) {
        when {
            c == '[' -> depth++
            c == ']' -> depth = maxOf(0, depth - 1)
            depth == 0 -> append(c)
        }
    }
}

/** Whether [name] is listed: not internal, and not a terminal-only command unless Tandem answers it. */
private fun listed(name: String, terminal: Set<String>): Boolean =
    !name.startsWith(INTERNAL_PREFIX) && (name !in terminal || name in TANDEM_NAMES)

private fun catalogue(commands: List<SlashCommand>, terminal: Set<String>, plugins: Set<String>): List<Entry> {
    val seen = HashSet<String>()
    val entries = ArrayList<Entry>(commands.size + TANDEM_ROWS.size)
    for (command in commands) {
        if (!listed(command.name, terminal) || !seen.add(command.name)) continue
        val tandem = TANDEM_ROWS.firstOrNull { it.name == command.name }
        entries += Entry(tandem ?: itemOf(command, plugins), command.aliases)
    }
    for (row in TANDEM_ROWS) if (seen.add(row.name)) entries += Entry(row, emptyList())
    return entries
}

private fun fallbackCatalogue(names: List<String>, terminal: Set<String>, plugins: Set<String>): List<Entry> {
    val seen = HashSet(TANDEM_NAMES)
    val entries = TANDEM_ROWS.mapTo(ArrayList()) { Entry(it, emptyList()) }
    for (name in names) {
        if (!listed(name, terminal) || !seen.add(name)) continue
        // A name alone: the plugin, claude.ai and MCP rules of [commandSource] apply, else it has no label.
        val scope = commandSource(name, "", builtin = false, plugins).scope
        entries += Entry(PaletteItem(name, "", "", scope, via = null, intercepted = false), emptyList())
    }
    return entries
}

private fun itemOf(command: SlashCommand, plugins: Set<String>): PaletteItem {
    val source = commandSource(command.name, command.description, command.builtin, plugins)
    return PaletteItem(command.name, source.description, command.argumentHint, source.scope, via = null, intercepted = false)
}

/** The rows of [catalogue] that match [query], best rank first, catalogue order within a rank; every row for an empty query. */
private fun ranked(query: String, catalogue: List<Entry>): ImmutableList<PaletteItem> {
    if (query.isEmpty()) return catalogue.map { it.item }.toImmutableList()
    val ranks = Array(4) { ArrayList<PaletteItem>() }
    for (entry in catalogue) {
        val name = entry.item.name
        when {
            name == query -> ranks[0] += entry.item
            name.startsWith(query) -> ranks[1] += entry.item
            else -> {
                val alias = entry.aliases.firstOrNull { it.startsWith(query) }
                when {
                    alias != null -> ranks[2] += entry.item.copy(via = alias)
                    query in name -> ranks[3] += entry.item
                }
            }
        }
    }
    return ranks.flatMap { it }.toImmutableList()
}

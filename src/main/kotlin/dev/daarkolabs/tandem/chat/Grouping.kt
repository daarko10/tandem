package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.EditOutcome
import dev.daarkolabs.tandem.session.EditPhase
import kotlinx.collections.immutable.toImmutableList

/**
 * The grouping rules of spec 4.6.4: which consecutive tool calls share one row, and that row's sentence, diff stats and state.
 * [fold] takes one run of visible main-thread calls, which the builder has already split at text, thinking, user messages and notices
 * and stripped of hidden `ToolSearch` calls.
 */
object Grouping {
    /** A done group of reads names its files while it has at most this many distinct names; more are counted. */
    const val MAX_NAMES = 2

    /** Shell commands that count as reads, file finds and searches by their first word (spec 4.6.2, open item 15). */
    private val shellReads: Set<String> = setOf("cat", "head", "tail")
    private val shellFinds: Set<String> = setOf("ls", "find", "tree")
    private val shellSearches: Set<String> = setOf("grep", "rg", "ag")

    /** Tools that are always a row of their own (rule 2). */
    private val loneKinds: Set<ToolKind> = setOf(ToolKind.AGENT, ToolKind.QUESTION, ToolKind.PLAN, ToolKind.PLAN_ENTER)

    /** Calls that did not end well, never folded into a count (rule 3). */
    private val loneStates: Set<ToolState> = setOf(ToolState.FAILED, ToolState.DENIED, ToolState.INTERRUPTED)

    private val liveStates: Set<ToolState> = setOf(ToolState.PENDING, ToolState.RUNNING, ToolState.WAITING)

    /**
     * A clause of a sentence: its verb in each tense and its noun for one call and for [many] (null: never counted, `found files`).
     * [byName]: counts distinct file names instead of calls.
     */
    private enum class Family(val done: String, val running: String, val one: String, val many: String?, val byName: Boolean) {
        READ("read", "reading", "a file", "files", byName = true),
        FIND("found", "finding", "files", null, byName = false),
        SEARCH("searched for", "searching for", "a pattern", "patterns", byName = false),
        SHELL("ran", "running", "a command", "commands", byName = false),
        EDIT("edited", "editing", "a file", "files", byName = true),
        CREATE("created", "creating", "a file", "files", byName = true),
        FETCH("fetched", "fetching", "a page", "pages", byName = false),
        WEB("searched", "searching", "the web", null, byName = false),
        TOOL("used", "using", "a tool", "tools", byName = false),
    }

    /**
     * [calls] split into rows (rules 2–4): a call of a tool that is always alone, or one that failed, was denied or was interrupted,
     * or a Tandem edit in review (spec 3.4), is a row of its own, and the calls between such rows form one group each. A single
     * call is a list of one.
     */
    fun fold(calls: List<ToolCall>): List<List<ToolCall>> {
        val rows = ArrayList<List<ToolCall>>()
        var group = ArrayList<ToolCall>()
        for (call in calls) {
            if (call.kind in loneKinds || call.state in loneStates || call.inReview()) {
                if (group.isNotEmpty()) rows += group
                group = ArrayList()
                rows += listOf(call)
            } else {
                group += call
            }
        }
        if (group.isNotEmpty()) rows += group
        return rows
    }

    /**
     * The one line of a group (rules 5 and 7): a clause per family in the order the families first occur, the first capitalised. A
     * running group puts the clauses with a running call in the present participle and ends with `…`. Only a done group of reads
     * names its files, up to [MAX_NAMES] distinct names (`Read a.kt, b.kt`); a single created file is named once done.
     */
    fun sentence(calls: List<ToolCall>): RowLabel {
        val running = calls.any { it.state in liveStates }
        val families = LinkedHashMap<Family, MutableList<ToolCall>>()
        for (call in calls) family(call)?.let { families.getOrPut(it, ::ArrayList) += call }
        if (families.isEmpty()) families[Family.TOOL] = calls.toMutableList()

        val text = StringBuilder()
        val spans = ArrayList<IntRange>()
        fun name(subject: String) {
            val shown = ToolLabels.middleTruncate(subject, ToolLabels.NAME_MAX)
            spans += text.length until text.length + shown.length
            text.append(shown)
        }

        val readsOnly = !running && calls.all { it.kind == ToolKind.READ && it.subject != null }
        val readNames = if (readsOnly) calls.mapNotNull { it.subject }.distinct() else emptyList()
        if (readsOnly && readNames.size <= MAX_NAMES) {
            text.append(Family.READ.done).append(' ')
            readNames.forEachIndexed { i, subject ->
                if (i > 0) text.append(", ")
                name(subject)
            }
        } else {
            families.entries.forEachIndexed { i, (family, members) ->
                if (i > 0) text.append(", ")
                val live = members.any { it.state in liveStates }
                text.append(if (live) family.running else family.done).append(' ')
                val count = count(family, members)
                val named = members.firstOrNull()?.subject
                when {
                    family == Family.CREATE && !live && count == 1 && named != null -> name(named)
                    family.many == null || count == 1 -> text.append(family.one)
                    else -> text.append(count).append(' ').append(family.many)
                }
            }
        }
        if (running) text.append('…')
        return RowLabel(text.toString().replaceFirstChar { it.uppercaseChar() }, spans.toImmutableList())
    }

    /** The group's diff stats (rule 6): the sum over its edits and writes that have any; none while a call runs or when none has stats. */
    fun stats(calls: List<ToolCall>): DiffStats? {
        if (calls.any { it.state in liveStates }) return null
        val counted = calls.filter { it.kind == ToolKind.EDIT || it.kind == ToolKind.CREATE }.mapNotNull { it.stats }
        if (counted.isEmpty()) return null
        return DiffStats(counted.sumOf { it.added }, counted.sumOf { it.removed })
    }

    /** The group's state (rule 8): waiting if a call waits, else running if one runs, else done. A single call keeps its own. */
    fun state(calls: List<ToolCall>): ToolState = when {
        calls.size == 1 -> calls.single().state
        calls.any { it.state == ToolState.WAITING } -> ToolState.WAITING
        calls.any { it.state == ToolState.PENDING || it.state == ToolState.RUNNING } -> ToolState.RUNNING
        else -> ToolState.DONE
    }

    /** The family a call counts in; null for a call that is never counted (a tool search, entering plan mode). */
    private fun family(call: ToolCall): Family? = when (call.kind) {
        ToolKind.READ -> Family.READ
        ToolKind.FIND -> Family.FIND
        ToolKind.SEARCH -> Family.SEARCH
        ToolKind.SHELL -> when (call.subject.orEmpty().substringBefore(' ')) {
            in shellReads -> Family.READ
            in shellFinds -> Family.FIND
            in shellSearches -> Family.SEARCH
            else -> Family.SHELL
        }
        ToolKind.EDIT -> Family.EDIT
        ToolKind.CREATE -> Family.CREATE
        ToolKind.WEB_FETCH -> Family.FETCH
        ToolKind.WEB_SEARCH -> Family.WEB
        ToolKind.TOOL_SEARCH, ToolKind.PLAN_ENTER -> null
        ToolKind.AGENT, ToolKind.QUESTION, ToolKind.PLAN, ToolKind.TASKS, ToolKind.MCP, ToolKind.OTHER -> Family.TOOL
    }

    /** How many [members] a clause of [family] counts: distinct file names for a file family (a shell read counts per call), else calls. */
    private fun count(family: Family, members: List<ToolCall>): Int =
        if (!family.byName) {
            members.size
        } else {
            members.distinctBy { if (it.kind != ToolKind.SHELL && it.subject != null) "name:${it.subject}" else "call:${it.id}" }.size
        }

    /**
     * A Tandem edit whose row speaks for its review (spec 3.4), so it keeps a row of its own: one that waits for its diff or is in review
     * (with `Show diff`), one the user decided that still waits for its result (`Edited …` / `Rejected …`), and one Claude stopped waiting
     * for (`Claude stopped waiting for this edit.`).
     */
    private fun ToolCall.inReview(): Boolean {
        val review = review ?: return false
        return review.phase == EditPhase.QUEUED || review.phase == EditPhase.OPEN || state in liveStates ||
            review.outcome == EditOutcome.STOPPED
    }
}

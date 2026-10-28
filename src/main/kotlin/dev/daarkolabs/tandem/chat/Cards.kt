package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.array
import dev.daarkolabs.tandem.protocol.boolean
import dev.daarkolabs.tandem.protocol.string
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.sessionPermissionUpdates
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A pending request the chat docks above the composer as a card (spec 4.12–4.14): the permission card, the question card or the
 * plan card. [agentDescription] is set when a subagent asks: its task's description.
 */
sealed interface PendingCard {
    val requestId: String
    val toolUseId: String?
    val agentDescription: String?
}

/**
 * The permission card (spec 4.12): `Allow Claude to <verb> <objectText>?`, the request's [description], what the call would do
 * ([payload]) and whether it may be allowed for the whole session ([canAlwaysAllow], only with an `addRules` suggestion).
 */
data class PermissionCard(
    override val requestId: String,
    override val toolUseId: String?,
    override val agentDescription: String?,
    val verb: String,
    val objectText: String,
    val description: String?,
    val payload: Payload,
    val canAlwaysAllow: Boolean,
) : PendingCard

/** What a permission card shows in its mono box. */
sealed interface Payload {
    /** Text as it is: a command, a path, written content, a URL and its prompt. */
    data class Mono(val text: String) : Payload

    /** An edit: the file, then the text it replaces and the new text. */
    data class OldNew(val path: String, val old: String, val new: String) : Payload

    /** The input as pretty JSON, the last resort for a tool whose input holds none of the known strings. */
    data class Json(val text: String) : Payload

    /** Nothing to show: the input is empty. */
    data object None : Payload
}

/** The question card (spec 4.13): the request's questions, in input order. */
data class QuestionCard(
    override val requestId: String,
    override val toolUseId: String?,
    override val agentDescription: String?,
    val questions: ImmutableList<Question>,
) : PendingCard

/** One question of an `AskUserQuestion` request; [header] is its tab label, empty when the request has none. */
data class Question(val text: String, val header: String, val multiSelect: Boolean, val options: ImmutableList<Option>)

data class Option(val label: String, val description: String?)

/** The plan approval card (spec 4.14): the [plan] text, where it came from ([source]) and the plan file, when there is one. */
data class PlanCard(
    override val requestId: String,
    override val toolUseId: String?,
    override val agentDescription: String?,
    val plan: String?,
    val source: PlanSource,
    val planFilePath: String?,
) : PendingCard

/** Where a plan card's text came from (spec 4.14). */
enum class PlanSource { EXIT_PLAN_INPUT, PLAN_FILE_DRAFT, ASSISTANT_TEXT, NONE }

/** What the user picked for one question: options by index, or the `Other` text. */
sealed interface Pick {
    data class Options(val indices: List<Int>) : Pick

    data class Other(val text: String) : Pick
}

/** The cards of pending requests (spec 4.12–4.14), pure functions of the state. */
object Cards {
    private const val URL_MAX = 80

    /** The inputs a payload shows when the tool has no rule of its own, in this order (spec 4.12 item 4). */
    private val payloadKeys = listOf("command", "file_path", "path", "pattern", "query", "url", "prompt", "description", "name", "skill")

    private val pretty = Json { prettyPrint = true }

    /**
     * The card of the oldest pending request, typed by its tool, and how many more wait behind it; `(null, 0)` when none waits. A
     * subagent's request carries the description its Agent call was given.
     */
    fun pending(state: ChatState): Pair<PendingCard?, Int> {
        val p = state.pendingPermissions.firstOrNull() ?: return null to 0
        val card = when (p.toolName) {
            "AskUserQuestion" -> question(p) ?: permission(p, state.cwd)
            "ExitPlanMode" -> plan(p, state)
            else -> permission(p, state.cwd)
        }
        val agent = agentDescription(state, p)
        val described = if (agent == null) {
            card
        } else {
            when (card) {
                is PermissionCard -> card.copy(agentDescription = agent)
                is QuestionCard -> card.copy(agentDescription = agent)
                is PlanCard -> card.copy(agentDescription = agent)
            }
        }
        return described to state.pendingPermissions.size - 1
    }

    /** The description of the main-thread Agent call whose subagent sent [p], from the task its `agent_id` names. */
    private fun agentDescription(state: ChatState, p: PendingPermission): String? {
        val agentId = p.agentId ?: return null
        val callId = state.tasks[agentId]?.toolUseId ?: return null
        val entries = state.entries
        for (i in entries.indices.reversed()) {
            val entry = entries[i] as? ChatEntry.Assistant ?: continue
            if (entry.parentToolUseId != null) continue
            val call = entry.blocks.firstOrNull { it is AssistantBlock.ToolUse && it.id == callId } as AssistantBlock.ToolUse? ?: continue
            return call.input?.string("description")
        }
        return null
    }

    /** The permission card of [p]; paths are named relative to [cwd]. */
    fun permission(p: PendingPermission, cwd: String?): PermissionCard {
        val (verb, objectText) = title(p, cwd)
        return PermissionCard(
            requestId = p.requestId,
            toolUseId = p.toolUseId,
            agentDescription = null,
            verb = verb,
            objectText = objectText,
            description = p.description,
            payload = payload(p),
            canAlwaysAllow = sessionPermissionUpdates(p.suggestions).isNotEmpty(),
        )
    }

    /** The verb and the object of `Allow Claude to <verb> <object>?` (spec 4.12 item 1); the object is never empty. */
    private fun title(p: PendingPermission, cwd: String?): Pair<String, String> {
        val input = p.input
        val name = p.toolName
        val fallback = displayName(name).ifEmpty { name }
        fun file(): String = (input.string("file_path") ?: input.string("notebook_path"))?.takeIf { it.isNotEmpty() }
            ?.let { ToolLabels.relativePath(it, cwd) } ?: fallback
        return when (name) {
            "Bash", "PowerShell" -> "Run" to (input.string("command")?.takeIf { it.isNotEmpty() } ?: fallback)
            "Read" -> "Read" to file()
            "Write" -> "Create" to file()
            "WebFetch" -> "Fetch" to (input.string("url")?.takeIf { it.isNotEmpty() }?.let { ToolLabels.middleTruncate(it, URL_MAX) } ?: fallback)
            "WebSearch" -> "Search the web for" to (input.string("query")?.let { "\"$it\"" } ?: fallback)
            "Agent", "Task" -> "Run" to "an agent"
            // The raw wire name: a malformed question falls back to this card as `Use AskUserQuestion` (spec 4.13).
            "AskUserQuestion", "ExitPlanMode" -> "Use" to name
            else -> when (ToolKinds.of(name)) {
                ToolKind.EDIT -> "Edit" to file()
                ToolKind.MCP -> "Use" to ToolLabels.mcpName(name).ifEmpty { fallback }
                else -> "Use" to fallback
            }
        }
    }

    /** What the card's mono box shows for [p] (spec 4.12 item 4). */
    private fun payload(p: PendingPermission): Payload {
        val input = p.input
        if (input.isEmpty()) return Payload.None
        val own: Payload? = when (p.toolName) {
            "Bash", "PowerShell" -> input.string("command")?.let(Payload::Mono)
            "Read" -> input.string("file_path")?.let(Payload::Mono)
            "Write" -> (input.string("content") ?: input.string("file_path"))?.let(Payload::Mono)
            "WebFetch" -> input.string("url")?.let { url -> Payload.Mono(listOfNotNull(url, input.string("prompt")).joinToString("\n")) }
            "WebSearch" -> input.string("query")?.let(Payload::Mono)
            "Agent", "Task" -> listOfNotNull(input.string("description"), input.string("prompt"))
                .takeIf { it.isNotEmpty() }?.let { Payload.Mono(it.joinToString("\n")) }
            else -> if (ToolKinds.of(p.toolName) == ToolKind.EDIT) edit(input) else null
        }
        return own
            ?: payloadKeys.firstNotNullOfOrNull { input.string(it) }?.let(Payload::Mono)
            ?: Payload.Json(pretty.encodeToString(JsonObject.serializer(), input))
    }

    /** An edit's file with the text it replaces and the new text; just the file when either is missing. */
    private fun edit(input: JsonObject): Payload? {
        val path = input.string("file_path") ?: input.string("notebook_path") ?: return null
        val old = input.string("old_string")
        val new = input.string("new_string")
        return if (old != null && new != null) Payload.OldNew(path, old, new) else Payload.Mono(path)
    }

    /**
     * The question card of an `AskUserQuestion` request; null when its `questions` are missing or malformed (not a non-empty list of
     * questions each with its text and a list of labelled options), so that the generic permission card shows it instead.
     */
    fun question(p: PendingPermission): QuestionCard? {
        val items = p.input.array("questions")?.takeIf { it.isNotEmpty() } ?: return null
        val questions = items.map { item ->
            val q = item as? JsonObject ?: return null
            val text = q.string("question") ?: return null
            val options = q.array("options") ?: return null
            Question(
                text = text,
                // The tab label: the question text stands in for a blank header (spec 4.6.2).
                header = q.string("header")?.takeIf { it.isNotBlank() } ?: text,
                multiSelect = q.boolean("multiSelect") ?: false,
                options = options.map { option ->
                    val o = option as? JsonObject ?: return null
                    Option(o.string("label") ?: return null, o.string("description"))
                }.toImmutableList(),
            )
        }
        return QuestionCard(p.requestId, p.toolUseId, agentDescription = null, questions.toImmutableList())
    }

    /** The plan card of an `ExitPlanMode` request [p], with the plan text [planText] finds in [state]. */
    fun plan(p: PendingPermission, state: ChatState): PlanCard {
        val (plan, source) = planText(state, p)
        val path = when (source) {
            PlanSource.EXIT_PLAN_INPUT -> p.input.string("planFilePath")?.takeIf { it.isNotBlank() }
            PlanSource.PLAN_FILE_DRAFT -> draftWrite(beforeCall(state.entries, state.entries.size, p.toolUseId))?.input?.string("file_path")
            PlanSource.ASSISTANT_TEXT, PlanSource.NONE -> null
        }
        return PlanCard(p.requestId, p.toolUseId, agentDescription = null, plan, source, path)
    }

    /**
     * The plan of an `ExitPlanMode` request (spec 4.14): its `plan` input; else the content of the failed Write of the plan file in
     * this turn (a path with a `plans` segment); else the last text Claude wrote in this turn before the call; else none.
     */
    fun planText(state: ChatState, p: PendingPermission): Pair<String?, PlanSource> {
        p.input.string("plan")?.takeIf { it.isNotBlank() }?.let { return it to PlanSource.EXIT_PLAN_INPUT }
        return draftedPlan(state.entries, state.entries.size, p.toolUseId)
    }

    /**
     * The plan drafted before the `ExitPlanMode` call [toolUseId] in the turn that ends before [end] (spec 4.14): the content of the
     * failed Write of the plan file in that turn (a path with a `plans` segment), else the last text Claude wrote in that turn before
     * the call, else none. The plan card reads it when the request carries no plan, and so does the call's row once it is answered.
     */
    internal fun draftedPlan(entries: List<ChatEntry>, end: Int, toolUseId: String?): Pair<String?, PlanSource> {
        val before = beforeCall(entries, end, toolUseId)
        draftWrite(before)?.let { write -> return write.input?.string("content") to PlanSource.PLAN_FILE_DRAFT }
        val text = before.firstNotNullOfOrNull { block -> (block as? AssistantBlock.Text)?.text?.takeIf { it.isNotBlank() } }
        return if (text != null) text to PlanSource.ASSISTANT_TEXT else null to PlanSource.NONE
    }

    /** The most recent Write of [before] (blocks latest first) whose path has a `plans` segment and which carries content. */
    private fun draftWrite(before: List<AssistantBlock>): AssistantBlock.ToolUse? =
        before.firstNotNullOfOrNull { block ->
            (block as? AssistantBlock.ToolUse)?.takeIf { write ->
                val input = write.input ?: return@takeIf false
                write.name == "Write" && input.string("content") != null &&
                    input.string("file_path")?.split('/', '\\')?.any { it.equals("plans", ignoreCase = true) } == true
            }
        }

    /**
     * The main thread's blocks of the turn that ends before [end] that come before call [toolUseId], latest first: from the call (or,
     * when its block is not in that turn, the turn's end) back to the turn's user message (or shell escape).
     */
    private fun beforeCall(entries: List<ChatEntry>, end: Int, toolUseId: String?): List<AssistantBlock> {
        val turn = ArrayList<AssistantBlock>()
        for (i in end - 1 downTo 0) {
            val entry = entries[i]
            if (entry.startsTurn()) break
            if (entry is ChatEntry.Assistant && entry.parentToolUseId == null) turn.addAll(entry.blocks.asReversed())
        }
        val call = turn.indexOfFirst { it is AssistantBlock.ToolUse && it.id == toolUseId }
        return if (call < 0) turn else turn.subList(call + 1, turn.size)
    }

    /**
     * The answers of [card] as the CLI takes them: the question's text to the picked labels (several joined with `, ` in option
     * order) or the trimmed `Other` text. A question without a valid pick is left out.
     */
    fun answers(card: QuestionCard, picks: Map<Int, Pick>): Map<String, String> {
        val answers = LinkedHashMap<String, String>()
        card.questions.forEachIndexed { i, question ->
            val value = when (val pick = picks[i]) {
                is Pick.Options -> pick.indices.distinct().sorted().mapNotNull { question.options.getOrNull(it)?.label }
                    .takeIf { it.isNotEmpty() }?.joinToString(", ")
                is Pick.Other -> pick.text.trim().takeIf { it.isNotEmpty() }
                null -> null
            }
            if (value != null) answers[question.text] = value
        }
        return answers
    }

    /** The plan card's source line (spec 4.14). A [PlanSource.PLAN_FILE_DRAFT] card always has its file ([plan] sets it). */
    fun sourceLine(card: PlanCard): String {
        val file = card.planFilePath?.let(ToolLabels::basename)
        return when (card.source) {
            PlanSource.EXIT_PLAN_INPUT -> if (file != null) "From the plan file $file" else "From the plan Claude sent"
            PlanSource.PLAN_FILE_DRAFT -> {
                val draft = checkNotNull(file) { "a plan file draft without its path" }
                "From Claude's draft of $draft (the plan file was not saved)"
            }
            PlanSource.ASSISTANT_TEXT -> "From Claude's last message (no plan file)"
            PlanSource.NONE -> "Claude sent no plan text."
        }
    }
}

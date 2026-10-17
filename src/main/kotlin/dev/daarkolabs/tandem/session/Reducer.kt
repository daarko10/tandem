package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContentBlock
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.InboundRequest
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.StreamEvent
import dev.daarkolabs.tandem.protocol.array
import dev.daarkolabs.tandem.protocol.asObject
import dev.daarkolabs.tandem.protocol.string
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toPersistentHashMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Inputs to [reduce]: decoded CLI lines plus what the host itself did. */
sealed interface SessionEvent {
    data class Cli(val message: CliMessage) : SessionEvent

    data class UserSent(val uuid: String, val text: String) : SessionEvent

    /** The user picked a model (at spawn or via `set_model`); null means the CLI default. */
    data class ModelRequested(val model: String?) : SessionEvent

    /**
     * The user picked a permission mode: with [requestId], the `set_permission_mode` request sent for it, whose answer confirms
     * or rejects it (`system/status` and the next `system/init` confirm it too); without, for the next process to start with.
     */
    data class PermissionModeRequested(val mode: PermissionMode, val requestId: String? = null) : SessionEvent

    data class PermissionAnswered(val requestId: String) : SessionEvent

    /** Something Tandem itself must tell the user, such as a pick it could not pass on to the CLI. Shown as a notice of [level]. */
    data class HostNotice(val level: NoticeLevel, val text: String) : SessionEvent

    /** The CLI process could not be started or exited. [detail] is shown to the user. */
    data class Ended(val detail: String, val isError: Boolean) : SessionEvent
}

/** Pure state transition. Never throws; deterministic, so fixture replays can test it. */
fun reduce(state: ChatState, event: SessionEvent): ChatState = when (event) {
    is SessionEvent.Cli -> reduceCli(state, event.message)
    is SessionEvent.UserSent -> state.append(ChatEntry.User(event.uuid, event.text)).copy(busy = true)
    // The CLI confirms a switch with the next system/init; until then the old model is expected, not a mismatch.
    is SessionEvent.ModelRequested -> state.copy(requestedModel = event.model, modelWarning = null)
    // Shown at once; what the CLI reports next (system/status, system/init) has the last word, and a rejection takes it back.
    is SessionEvent.PermissionModeRequested -> state.copy(
        permissionMode = event.mode,
        permissionModeChanges = event.requestId?.let { state.permissionModeChanges + (it to PermissionModeChange(event.mode, state.permissionMode)) }
            ?: state.permissionModeChanges,
    )
    is SessionEvent.PermissionAnswered -> state.copy(pendingPermissions = state.pendingPermissions.filter { it.requestId != event.requestId })
    is SessionEvent.HostNotice -> state.notice(event.level, event.text)
    is SessionEvent.Ended -> state.finishStreaming().copy(busy = false, ended = true, pendingPermissions = emptyList(), permissionModeChanges = emptyMap())
        .notice(if (event.isError) NoticeLevel.ERROR else NoticeLevel.INFO, event.detail)
}

private fun reduceCli(state: ChatState, message: CliMessage): ChatState = when (message) {
    is CliMessage.SystemInit -> state.copy(
        sessionId = message.sessionId.ifEmpty { state.sessionId },
        permissionMode = message.permissionMode ?: state.permissionMode,
        claudeCodeVersion = message.claudeCodeVersion ?: state.claudeCodeVersion,
    ).checkModel(message.model)
    is CliMessage.SystemStatus -> state.copy(permissionMode = message.permissionMode ?: state.permissionMode)
    is CliMessage.ApiRetry -> state.notice(
        NoticeLevel.WARNING,
        buildString {
            append("API request failed")
            message.error?.let { append(" ($it)") }
            append(", retrying")
            if (message.attempt != null && message.maxRetries != null) append(" ${message.attempt}/${message.maxRetries}")
            message.retryDelayMs?.let { append(" in ${(it + 999) / 1000}s") }
            append('.')
        },
    )
    is CliMessage.ModelRefusalFallback -> {
        val text = "${message.originalModel} declined this request; " +
            if (message.scope == "local") "this answer came from ${message.fallbackModel}." else "the session switched to ${message.fallbackModel}."
        val next = state.withoutAssistants(message.retractedMessageUuids.toSet()).notice(NoticeLevel.WARNING, message.content ?: text)
        if (message.scope == "local") next else next.checkModel(message.fallbackModel)
    }
    is CliMessage.ModelRefusalNoFallback ->
        state.notice(NoticeLevel.WARNING, message.content ?: "${message.originalModel ?: "The model"} declined this request.")
    is CliMessage.Assistant -> reduceAssistant(state, message)
    is CliMessage.User -> reduceUser(state, message)
    is CliMessage.StreamEvent -> reduceStream(state, message)
    is CliMessage.Result -> reduceResult(state, message)
    is CliMessage.ControlRequest -> when (val request = message.request) {
        is InboundRequest.CanUseTool -> state.copy(
            pendingPermissions = state.pendingPermissions + PendingPermission(
                requestId = message.requestId,
                toolName = request.toolName,
                input = request.input,
                toolUseId = request.toolUseId,
                description = request.description ?: request.title,
                suggestions = request.permissionSuggestions,
            ),
        )
        is InboundRequest.Other -> state
    }
    is CliMessage.ControlCancelRequest -> state.copy(pendingPermissions = state.pendingPermissions.filter { it.requestId != message.requestId })
    is CliMessage.ControlResponse -> {
        val change = state.permissionModeChanges[message.requestId]
        val answered = if (change == null) state else state.copy(permissionModeChanges = state.permissionModeChanges - message.requestId)
        when {
            message.isSuccess -> answered.withModels(message.payload)
            change == null -> answered.notice(NoticeLevel.ERROR, "The CLI rejected a request: ${message.error ?: "unknown error"}")
            else -> answered.rejectModeChange(change, message.error)
        }
    }
    is CliMessage.KeepAlive, is CliMessage.Unknown -> state
}

/**
 * The CLI rejected [change]: the mode shown before it comes back, unless another pick replaced it meanwhile, and the chat says which
 * switch was rejected.
 */
private fun ChatState.rejectModeChange(change: PermissionModeChange, error: String?): ChatState {
    val mode = if (permissionMode == change.requested) change.previous else permissionMode
    val reason = error?.let { " ($it)" }.orEmpty()
    val now = mode?.let { "; it stays ${it.wire}." } ?: "."
    return copy(permissionMode = mode).notice(NoticeLevel.ERROR, "The CLI rejected the switch to permission mode ${change.requested.wire}$reason$now")
}

private fun reduceAssistant(state: ChatState, message: CliMessage.Assistant): ChatState {
    var next = state
    if (message.supersedes.isNotEmpty()) next = next.withoutAssistants(message.supersedes.toSet())
    message.error?.let { return next.notice(NoticeLevel.ERROR, errorText(it, message)) }
    if (message.parentToolUseId == null) next = next.checkModel(message.model, fromAssistant = true)

    val messageId = message.messageId ?: message.uuid ?: return next
    val complete = message.content.map(::toBlock)
    val existing = next.assistant(messageId, message.parentToolUseId)
    val updated = if (existing == null) {
        ChatEntry.Assistant(
            id = entryId(messageId, message.parentToolUseId),
            messageId = messageId,
            parentToolUseId = message.parentToolUseId,
            model = message.model,
            blocks = complete,
            completedBlocks = complete.size,
            streaming = false,
            uuids = setOfNotNull(message.uuid),
        )
    } else {
        // The Nth complete line for a message replaces the Nth drafted block (docs/protocol.md 6.4).
        val blocks = existing.blocks.toMutableList()
        complete.forEachIndexed { offset, block ->
            val index = existing.completedBlocks + offset
            if (index < blocks.size) blocks[index] = block else blocks += block
        }
        existing.copy(
            blocks = blocks,
            completedBlocks = existing.completedBlocks + complete.size,
            model = message.model ?: existing.model,
            uuids = existing.uuids + setOfNotNull(message.uuid),
        )
    }
    return next.upsert(updated).let { if (message.aborted) it.finishStreaming() else it }
}

private fun reduceUser(state: ChatState, message: CliMessage.User): ChatState {
    if (message.isReplay) return state
    var next = state
    for (block in message.content) {
        next = when (block) {
            is ContentBlock.ToolResult ->
                next.append(ChatEntry.ToolResult("result-${block.toolUseId}", block.toolUseId, toolResultText(block), block.isError))
            is ContentBlock.Text ->
                if (block.text.startsWith("[Request interrupted")) next.notice(NoticeLevel.INFO, "Stopped.") else next
            is ContentBlock.Thinking, is ContentBlock.ToolUse, is ContentBlock.Other -> next
        }
    }
    return next
}

private fun reduceStream(state: ChatState, message: CliMessage.StreamEvent): ChatState {
    val parent = message.parentToolUseId
    return when (val event = message.event) {
        is StreamEvent.MessageStart -> {
            if (parent == null) {
                state.checkModel(event.model, fromAssistant = true)
            } else {
                state
            }.upsertIfAbsent(
                ChatEntry.Assistant(
                    id = entryId(event.messageId, parent),
                    messageId = event.messageId,
                    parentToolUseId = parent,
                    model = event.model,
                    blocks = emptyList(),
                    completedBlocks = 0,
                    streaming = true,
                    uuids = emptySet(),
                ),
            )
        }
        is StreamEvent.ContentBlockStart -> state.editDraft(message) { blocks ->
            if (event.index < blocks.size) blocks else blocks + List(event.index - blocks.size) { AssistantBlock.Other("gap") } + toBlock(event.block)
        }
        is StreamEvent.ContentBlockDelta -> state.editDraft(message) { blocks ->
            val draft = blocks.getOrNull(event.index) ?: return@editDraft blocks
            val updated = when (val delta = event.delta) {
                is Delta.Text -> (draft as? AssistantBlock.Text)?.copy(text = draft.text + delta.text)
                is Delta.Thinking -> (draft as? AssistantBlock.Thinking)?.copy(text = draft.text + delta.thinking)
                is Delta.InputJson -> (draft as? AssistantBlock.ToolUse)?.copy(partialInput = draft.partialInput + delta.partialJson)
                is Delta.Other -> null
            } ?: draft
            blocks.toMutableList().also { it[event.index] = updated }
        }
        is StreamEvent.MessageStop -> {
            val entry = state.draftFor(message) ?: return state
            state.upsert(entry.copy(streaming = false))
        }
        // A late content_block_stop for an already completed block is a no-op (docs/protocol.md 6.4).
        is StreamEvent.ContentBlockStop, is StreamEvent.MessageDelta, is StreamEvent.Other -> state
    }
}

private fun reduceResult(state: ChatState, result: CliMessage.Result): ChatState {
    val next = state.finishStreaming().copy(busy = false, sessionId = result.sessionId ?: state.sessionId, pendingPermissions = emptyList())
    return when {
        result.terminalReason == "aborted_streaming" -> next // the interrupt already showed "Stopped."
        result.isError -> next.notice(
            NoticeLevel.ERROR,
            "The turn failed (${result.subtype}): " + (result.result ?: result.errors.joinToString("; ").ifEmpty { "no details" }),
        )
        else -> next
    }
}

/**
 * Compares what the CLI reports with what the user picked. Never silently accept another model:
 * a mismatch sets [ChatState.modelWarning] and adds one notice per distinct mismatch.
 * A name in angle brackets, such as `<synthetic>` on a line the CLI writes itself for an API error, is not a model and changes nothing.
 */
private fun ChatState.checkModel(reported: String?, fromAssistant: Boolean = false): ChatState {
    if (reported == null || reported.startsWith("<")) return this
    val requested = requestedModel
    val changed = reported != activeModel
    val base = copy(activeModel = reported)
    if (requested == null || modelMatches(requested, reported)) return base.copy(modelWarning = null)
    val warning = "You picked $requested, but the CLI is using $reported" + if (fromAssistant) " for this reply." else "."
    return if (changed || modelWarning == null) base.copy(modelWarning = warning).notice(NoticeLevel.WARNING, warning) else base
}

/** Accepts the CLI's resolution of aliases (`opus` → `claude-opus-5-5`) and context-window suffixes (`[1m]`). */
internal fun modelMatches(requested: String, reported: String): Boolean {
    val wanted = requested.substringBefore('[').lowercase()
    val actual = reported.substringBefore('[').lowercase()
    if (wanted == "default" || wanted == actual) return true
    return wanted in setOf("opus", "sonnet", "haiku", "fable") && actual.startsWith("claude-$wanted")
}

private fun errorText(error: String, message: CliMessage.Assistant): String {
    val text = message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
    return when (error) {
        "authentication_failed" -> "Claude Code is not signed in. Run `claude` in a terminal to sign in, then retry."
        "model_not_found" -> "The CLI rejected the model${message.model?.let { " $it" } ?: ""}. Pick another model."
        else -> text.ifEmpty { "The CLI reported an error: $error" }
    }
}

private fun toBlock(block: ContentBlock): AssistantBlock = when (block) {
    is ContentBlock.Text -> AssistantBlock.Text(block.text)
    is ContentBlock.Thinking -> AssistantBlock.Thinking(block.thinking)
    is ContentBlock.ToolUse -> AssistantBlock.ToolUse(block.id, block.name, block.input, partialInput = "")
    is ContentBlock.ToolResult -> AssistantBlock.Other("tool_result")
    is ContentBlock.Other -> AssistantBlock.Other(block.type)
}

private fun toolResultText(block: ContentBlock.ToolResult): String = when (val content = block.content) {
    is JsonPrimitive -> content.content
    is JsonArray -> content.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }.joinToString("\n") { it.content }
    else -> ""
}

private fun entryId(messageId: String, parent: String?): String = if (parent == null) messageId else "$parent/$messageId"

/** The `models` a response's [payload] lists, as the `initialize` response does, replace [ChatState.models]; other payloads keep them. */
private fun ChatState.withModels(payload: JsonObject?): ChatState {
    val models = payload?.array("models") ?: return this
    return copy(models = models.mapNotNull { it.asObject()?.modelOption() })
}

/** One entry of a `models` list; null without a string `value`. */
private fun JsonObject.modelOption(): ModelOption? {
    val value = string("value") ?: return null
    return ModelOption(value, string("displayName") ?: value, string("description"))
}

private fun ChatState.assistant(messageId: String, parent: String?): ChatEntry.Assistant? =
    entries.getOrNull(positionOf(entryId(messageId, parent))) as? ChatEntry.Assistant

private fun ChatState.upsert(entry: ChatEntry): ChatState {
    val position = positionOf(entry.id)
    // A replacement keeps the id at its position, so the entry index stays as it is.
    return if (position < 0) append(entry) else copy(entries = entryList().mutate { list -> list[position] = entry })
}

private fun ChatState.upsertIfAbsent(entry: ChatEntry): ChatState = if (positionOf(entry.id) < 0) append(entry) else this

/**
 * The last position of the entry with [id] in [ChatState.entries], or -1. Takes it from [ChatState.entryIndex] and checks it;
 * only a state whose index is empty or out of step (built by hand, or changed outside [reduce]) is scanned.
 */
private fun ChatState.positionOf(id: String): Int {
    val position = entryIndex[id]
    if (position != null && entries.getOrNull(position)?.id == id) return position
    return if (position == null && indexInStep()) -1 else entries.indexOfLast { it.id == id }
}

/** Whether [ChatState.entryIndex] answers for [ChatState.entries]: [reduce] indexes every entry it appends, so the last one too. */
private fun ChatState.indexInStep(): Boolean = entries.isEmpty() || entryIndex[entries.last().id] == entries.lastIndex

/** This state with [entry] appended and indexed; an index out of step is rebuilt first. */
private fun ChatState.append(entry: ChatEntry): ChatState {
    val position = entries.size
    val index = if (indexInStep()) entryIndex.toPersistentHashMap() else positionsOf(entries)
    return copy(
        entries = entryList().mutate { list -> list.add(entry) },
        entryIndex = index.mutate { positions -> positions[entry.id] = position },
    )
}

/** This state without the assistant entries that carry one of [uuids]. The entries after a removed one move up, so the index is rebuilt. */
private fun ChatState.withoutAssistants(uuids: Set<String>): ChatState {
    val kept = entryList().mutate { list -> list.removeAll { it is ChatEntry.Assistant && it.uuids.any(uuids::contains) } }
    return if (kept.size == entries.size) this else copy(entries = kept, entryIndex = positionsOf(kept))
}

/**
 * [ChatState.entries] as the [PersistentList] that [reduce] keeps there, so that a change shares the rest of a long history
 * instead of copying it. A list built by hand is converted once; [toPersistentList] returns a persistent list as it is.
 *
 * Every change goes through the collections' builders ([mutate]): the persistent `add`, `set`, `removeAll` and `put` are
 * deprecated in the version that IDE 2026.2 bundles, and the names replacing them do not exist in 2026.1.
 */
private fun ChatState.entryList(): PersistentList<ChatEntry> = entries.toPersistentList()

/** The last position of each id in [entries], indexed afresh. */
private fun positionsOf(entries: List<ChatEntry>): PersistentMap<String, Int> =
    persistentHashMapOf<String, Int>().mutate { index -> entries.forEachIndexed { position, entry -> index[entry.id] = position } }

private fun ChatState.editDraft(message: CliMessage.StreamEvent, edit: (List<AssistantBlock>) -> List<AssistantBlock>): ChatState {
    val entry = draftFor(message) ?: return this
    return upsert(entry.copy(blocks = edit(entry.blocks)))
}

/** The streaming draft a frame belongs to: by `api_message_id` (undocumented), else the open draft of that thread. */
private fun ChatState.draftFor(message: CliMessage.StreamEvent): ChatEntry.Assistant? {
    val draft = message.apiMessageId?.let { assistant(it, message.parentToolUseId) }
        ?: entries.lastOrNull { it is ChatEntry.Assistant && it.streaming && it.parentToolUseId == message.parentToolUseId } as? ChatEntry.Assistant
    return draft?.takeIf { it.streaming }
}

// The same ids at the same positions: the entry index stays as it is.
private fun ChatState.finishStreaming(): ChatState = copy(
    entries = entryList().mutate { list ->
        for (position in list.indices) {
            val entry = list[position]
            if (entry is ChatEntry.Assistant && entry.streaming) list[position] = entry.copy(streaming = false)
        }
    },
)

private fun ChatState.notice(level: NoticeLevel, text: String): ChatState =
    append(ChatEntry.Notice("notice-$noticeCount", level, text)).copy(noticeCount = noticeCount + 1)

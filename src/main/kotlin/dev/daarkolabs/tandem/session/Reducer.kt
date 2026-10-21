package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContentBlock
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.InboundRequest
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.StreamEvent
import dev.daarkolabs.tandem.protocol.TaskUsage
import dev.daarkolabs.tandem.protocol.array
import dev.daarkolabs.tandem.protocol.asObject
import dev.daarkolabs.tandem.protocol.boolean
import dev.daarkolabs.tandem.protocol.string
import dev.daarkolabs.tandem.protocol.strings
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toPersistentHashMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Inputs to [reduce]: decoded CLI lines plus what the host itself did. [Cli], [UserSent] and [Ended] carry the host's time. */
sealed interface SessionEvent {
    /** [atMillis]: when the line came, on the host's clock (a loaded transcript passes the line's own timestamp); 0 when unknown. */
    data class Cli(val message: CliMessage, val atMillis: Long = 0L) : SessionEvent

    /** A prompt the user sent: its [text] and the [images] attached to it. */
    data class UserSent(val uuid: String, val text: String, val atMillis: Long = 0L, val images: List<UserImage> = emptyList()) : SessionEvent

    /** The user picked a model (at spawn or via `set_model`); null means the CLI default. */
    data class ModelRequested(val model: String?) : SessionEvent

    /**
     * The user picked a permission mode: with [requestId], the `set_permission_mode` request sent for it, whose answer confirms
     * or rejects it (`system/status` and the next `system/init` confirm it too); without, for the next process to start with.
     */
    data class PermissionModeRequested(val mode: PermissionMode, val requestId: String? = null) : SessionEvent

    /**
     * The user picked an effort [level]: with [requestId], the `apply_flag_settings` request sent for it, whose error answer puts
     * the effort shown before it back; without, for the next process to start with (`--effort`).
     */
    data class EffortRequested(val level: String, val requestId: String? = null) : SessionEvent

    data class PermissionAnswered(val requestId: String) : SessionEvent

    /** Something Tandem itself must tell the user, such as a pick it could not pass on to the CLI. Shown as a notice of [level]. */
    data class HostNotice(val level: NoticeLevel, val text: String) : SessionEvent

    /**
     * The CLI process could not be started or exited. [detail] is shown to the user. [endedByHost]: Tandem itself ended it at once,
     * as when the IDE exits, so a turn in progress did not fail.
     */
    data class Ended(val detail: String, val isError: Boolean, val endedByHost: Boolean = false, val atMillis: Long = 0L) : SessionEvent
}

/** Pure state transition. Never throws; deterministic, so fixture replays can test it. */
fun reduce(state: ChatState, event: SessionEvent): ChatState = when (event) {
    is SessionEvent.Cli -> reduceCli(state, event.message, event.atMillis)
    // A send opens a turn, replacing any still open: a loaded transcript sends again with no result between its turns.
    is SessionEvent.UserSent -> state.append(ChatEntry.User(event.uuid, event.text, event.atMillis, event.images)).copy(
        busy = true,
        turnInterrupted = false,
        turn = TurnProgress(event.uuid, event.atMillis, outputTokens = 0, thinkingEstimate = 0, lastToolUseId = null, toolCount = 0),
    )
    // The CLI confirms a switch with the next system/init; until then the old model is expected, not a mismatch.
    is SessionEvent.ModelRequested -> state.copy(requestedModel = event.model, modelWarning = null, modelSwitchPending = true)
    // Shown at once; what the CLI reports next (system/status, system/init) has the last word, and a rejection takes it back.
    is SessionEvent.PermissionModeRequested -> state.copy(
        permissionMode = event.mode,
        permissionModeChanges = event.requestId?.let { state.permissionModeChanges + (it to PermissionModeChange(event.mode, state.permissionMode)) }
            ?: state.permissionModeChanges,
    )
    is SessionEvent.EffortRequested -> state.copy(
        effort = event.level,
        effortChanges = event.requestId?.let { state.effortChanges + (it to EffortChange(event.level, state.effort)) } ?: state.effortChanges,
    )
    is SessionEvent.PermissionAnswered -> state.copy(pendingPermissions = state.pendingPermissions.filter { it.requestId != event.requestId })
    is SessionEvent.HostNotice -> state.notice(event.level, event.text)
    is SessionEvent.Ended -> reduceEnded(state, event)
}

/**
 * A process that exits during a turn ends that turn without its result: a failure, whatever its exit code, and the chat says so as
 * an error. One that Tandem itself ended at once (the IDE exits) ends it as interrupted, not failed, and says only how it exited.
 * One that exits between turns ends none. A Stop, close or eviction lets the process go before it ends, so this never sees it.
 * The turn's [ChatEntry.TurnEnded] comes before the notice.
 */
private fun reduceEnded(state: ChatState, event: SessionEvent.Ended): ChatState {
    val turn = state.turn
    val end = when {
        turn != null && event.endedByHost -> TurnEnd.INTERRUPTED
        (turn != null || state.busy) && !event.endedByHost -> TurnEnd.FAILED
        else -> null
    }
    val failedTurn = end == TurnEnd.FAILED
    val said = if (failedTurn) {
        val first = event.detail.substringBefore('\n')
        val rest = event.detail.substringAfter('\n', missingDelimiterValue = "")
        "$first The turn ended without its result." + if (rest.isEmpty()) "" else "\n$rest"
    } else {
        event.detail
    }
    var next = state.finishStreaming()
    if (turn != null && end != null) {
        next = next.append(
            ChatEntry.TurnEnded(
                id = turnEndId(state),
                end = end,
                durationMs = event.atMillis - turn.startedAtMillis,
                outputTokens = turn.outputTokens,
                denials = emptyList(),
                error = if (failedTurn) turn.lastError ?: event.detail else null,
            ),
        )
    }
    return next.copy(
        busy = false,
        ended = true,
        pendingPermissions = emptyList(),
        permissionModeChanges = emptyMap(),
        effortChanges = emptyMap(),
        lastTurnEnd = end ?: state.lastTurnEnd,
        turnsEnded = if (end != null) state.turnsEnded + 1 else state.turnsEnded,
        turnInterrupted = false,
        turn = null,
    ).notice(if (event.isError || failedTurn) NoticeLevel.ERROR else NoticeLevel.INFO, said)
}

/** The id of the [ChatEntry.TurnEnded] that ends the next turn of [state]: every turn end counts one, so it is never reused. */
private fun turnEndId(state: ChatState): String = "end-${state.turnsEnded + 1}"

private fun reduceCli(state: ChatState, message: CliMessage, at: Long): ChatState = when (message) {
    is CliMessage.SystemInit -> state.copy(
        sessionId = message.sessionId.ifEmpty { state.sessionId },
        permissionMode = message.permissionMode ?: state.permissionMode,
        claudeCodeVersion = message.claudeCodeVersion ?: state.claudeCodeVersion,
        cwd = message.cwd ?: state.cwd,
        modelSwitchPending = false,
    ).checkModel(message.model)
    is CliMessage.SystemStatus -> state.copy(permissionMode = message.permissionMode ?: state.permissionMode)
    is CliMessage.ApiRetry -> state.notice(NoticeLevel.INFO, retryText(message))
    is CliMessage.ModelRefusalFallback -> {
        val text = "${message.originalModel} declined this request; " +
            if (message.scope == "local") "this answer came from ${message.fallbackModel}." else "the session switched to ${message.fallbackModel}."
        val next = state.withoutAssistants(message.retractedMessageUuids.toSet()).notice(NoticeLevel.WARNING, message.content ?: text)
        if (message.scope == "local") next else next.copy(modelSwitchPending = false).checkModel(message.fallbackModel)
    }
    is CliMessage.ModelRefusalNoFallback ->
        state.notice(NoticeLevel.WARNING, message.content ?: "${message.originalModel ?: "The model"} declined this request.")
    is CliMessage.Assistant -> reduceAssistant(state, message, at)
    is CliMessage.User -> reduceUser(state, message, at)
    is CliMessage.StreamEvent -> reduceStream(state, message, at)
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
                displayName = request.displayName,
                requiresUserInteraction = request.requiresUserInteraction,
                decisionReasonType = request.decisionReasonType,
                agentId = request.agentId,
                blockedPath = request.blockedPath,
            ),
        )
        is InboundRequest.Other -> state
    }
    is CliMessage.ControlCancelRequest -> state.copy(pendingPermissions = state.pendingPermissions.filter { it.requestId != message.requestId })
    is CliMessage.ControlResponse -> {
        val change = state.permissionModeChanges[message.requestId]
        val answered = if (change == null) state else state.copy(permissionModeChanges = state.permissionModeChanges - message.requestId)
        val effortChange = state.effortChanges[message.requestId]
        when {
            effortChange != null && message.isSuccess -> state.copy(effortChanges = state.effortChanges - message.requestId)
            effortChange != null -> state.rejectEffortChange(message.requestId, effortChange, message.error)
            message.isSuccess -> answered.withModels(message.payload)
            change == null -> answered.notice(NoticeLevel.ERROR, "The CLI rejected a request: ${message.error ?: "unknown error"}")
            else -> state.rejectModeChange(message.requestId, change, message.error)
        }
    }
    is CliMessage.ThinkingTokens ->
        state.withTurn { it.copy(thinkingEstimate = message.estimatedTokens) }.estimateThinking(message.estimatedTokens)
    is CliMessage.TaskStarted ->
        // Only subagents: a background command (local_bash) belongs to its Bash call and attaches nothing.
        if (message.taskType == "local_agent" || message.subagentType != null) {
            state.withTask(
                TaskInfo(
                    taskId = message.taskId,
                    toolUseId = message.toolUseId,
                    description = message.description,
                    subagentType = message.subagentType,
                    status = null,
                    lastToolName = null,
                    totalTokens = null,
                    toolUses = null,
                    durationMs = null,
                    summary = null,
                ),
            )
        } else {
            state
        }
    is CliMessage.TaskProgress -> state.updateTask(message.taskId) { task ->
        task.copy(
            toolUseId = task.toolUseId ?: message.toolUseId,
            description = message.description ?: task.description,
            lastToolName = message.lastToolName ?: task.lastToolName,
        ).withUsage(message.usage)
    }
    is CliMessage.TaskUpdated -> state.updateTask(message.taskId) { it.copy(status = message.status ?: it.status) }
    is CliMessage.TaskNotification -> state.updateTask(message.taskId) { task ->
        task.copy(
            toolUseId = task.toolUseId ?: message.toolUseId,
            status = message.status ?: task.status,
            summary = message.summary ?: task.summary,
        ).withUsage(message.usage)
    }
    is CliMessage.KeepAlive, is CliMessage.Unknown -> state
}

/**
 * The retry notice, as the reference words it: `<error_status> <error> · Retrying in <s>s · attempt <n>/<max>`, each part left out
 * when the line lacks it (`API error` when it names neither status nor error).
 */
private fun retryText(message: CliMessage.ApiRetry): String {
    val cause = listOfNotNull(message.errorStatus?.toString(), message.error).joinToString(" ").ifEmpty { "API error" }
    val parts = listOfNotNull(
        cause,
        message.retryDelayMs?.let { "Retrying in ${it / 1000}s" },
        if (message.attempt != null && message.maxRetries != null) "attempt ${message.attempt}/${message.maxRetries}" else null,
    )
    return parts.joinToString(" · ")
}

/** This state with [change] applied to the turn in progress; unchanged when none is. */
private inline fun ChatState.withTurn(change: (TurnProgress) -> TurnProgress): ChatState = turn?.let { copy(turn = change(it)) } ?: this

/** This state with [task] stored under its id, in the persistent map that [ChatState.tasks] holds. */
private fun ChatState.withTask(task: TaskInfo): ChatState = copy(tasks = tasks.toPersistentHashMap().mutate { it[task.taskId] = task })

/** This state with the task [taskId] changed by [change]; a line for a task that was never stored (`local_bash`) changes nothing. */
private inline fun ChatState.updateTask(taskId: String, change: (TaskInfo) -> TaskInfo): ChatState =
    tasks[taskId]?.let { withTask(change(it)) } ?: this

private fun TaskInfo.withUsage(usage: TaskUsage?): TaskInfo = if (usage == null) {
    this
} else {
    copy(
        totalTokens = usage.totalTokens ?: totalTokens,
        toolUses = usage.toolUses ?: toolUses,
        durationMs = usage.durationMs ?: durationMs,
    )
}

/**
 * Sets [tokens] as the estimate of the thinking block that streams now: the last open one of the last streaming main-thread message
 * (a `thinking_tokens` line names no message, and subagents stream nothing). No block is open: nothing changes.
 */
private fun ChatState.estimateThinking(tokens: Int): ChatState {
    val draft = entries.lastOrNull { it is ChatEntry.Assistant && it.streaming && it.parentToolUseId == null } as? ChatEntry.Assistant
        ?: return this
    val index = draft.blocks.indexOfLast { it is AssistantBlock.Thinking && it.completedAtMillis == null }
    if (index < 0) return this
    val block = draft.blocks[index] as AssistantBlock.Thinking
    return upsert(draft.copy(blocks = draft.blocks.toMutableList().also { it[index] = block.copy(estimatedTokens = tokens) }))
}

/**
 * The CLI rejected [change], request [requestId], and stayed in the mode it was in before it. The CLI takes the requests in the
 * order they were sent, so the next change still waiting now starts from that mode. If no later change waits, the mode shown
 * before [change] comes back, unless something else (the CLI's own report) replaced it meanwhile. The chat says which switch was
 * rejected.
 */
private fun ChatState.rejectModeChange(requestId: String, change: PermissionModeChange, error: String?): ChatState {
    // In the order the requests were sent: the reducer adds each to the end of this map.
    val ids = permissionModeChanges.keys.toList()
    val next = ids.getOrNull(ids.indexOf(requestId) + 1)
    var pending = permissionModeChanges - requestId
    if (next != null) pending = pending + (next to pending.getValue(next).copy(previous = change.previous))
    val mode = if (next == null && permissionMode == change.requested) change.previous else permissionMode
    val reason = error?.let { " ($it)" }.orEmpty()
    // What the CLI is in now: with a later change still waiting, that change shows, but the CLI stays where it was.
    val now = (if (next == null) mode else change.previous)?.let { "; it stays ${it.wire}." } ?: "."
    return copy(permissionMode = mode, permissionModeChanges = pending)
        .notice(NoticeLevel.ERROR, "The CLI rejected the switch to permission mode ${change.requested.wire}$reason$now")
}

/**
 * The CLI answered [change], request [requestId], with [error] and kept the effort it had, as [rejectModeChange] does for modes: the
 * next change still waiting now starts from that effort; if none waits, the effort shown before [change] comes back, unless
 * something else replaced it meanwhile. The chat says why (the CLI's own words, docs/protocol.md 4.4).
 */
private fun ChatState.rejectEffortChange(requestId: String, change: EffortChange, error: String?): ChatState {
    // In the order the requests were sent: the reducer adds each to the end of this map.
    val ids = effortChanges.keys.toList()
    val next = ids.getOrNull(ids.indexOf(requestId) + 1)
    var pending = effortChanges - requestId
    if (next != null) pending = pending + (next to pending.getValue(next).copy(previous = change.previous))
    val level = if (next == null && effort == change.requested) change.previous else effort
    return copy(effort = level, effortChanges = pending).notice(NoticeLevel.ERROR, "Effort not changed: ${error ?: "unknown error"}")
}

private fun reduceAssistant(state: ChatState, message: CliMessage.Assistant, at: Long): ChatState {
    var next = state
    if (message.supersedes.isNotEmpty()) next = next.withoutAssistants(message.supersedes.toSet())
    message.error?.let { error ->
        // The turn's failure, as its footer says it: `API Error: 404 model_not_found`.
        val failure = "API Error: " + listOfNotNull(message.apiErrorStatus?.toString(), error).joinToString(" ")
        return next.notice(NoticeLevel.ERROR, errorText(error, message, next)).withTurn { it.copy(lastError = failure) }
    }
    if (message.parentToolUseId == null) next = next.checkModel(message.model, fromAssistant = true)

    val messageId = message.messageId ?: message.uuid ?: return next
    val complete = message.content.map { completeBlock(it, message, at) }
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
            agentId = message.agentId,
            subagentType = message.subagentType,
            taskDescription = message.taskDescription,
            atMillis = at.takeIf { it > 0 },
        )
    } else {
        // The Nth complete line for a message replaces the Nth drafted block (docs/protocol.md 6.4).
        val blocks = existing.blocks.toMutableList()
        complete.forEachIndexed { offset, block ->
            val index = existing.completedBlocks + offset
            if (index < blocks.size) blocks[index] = completed(blocks[index], block, at) else blocks += block
        }
        existing.copy(
            blocks = blocks,
            completedBlocks = existing.completedBlocks + complete.size,
            model = message.model ?: existing.model,
            uuids = existing.uuids + setOfNotNull(message.uuid),
            agentId = message.agentId ?: existing.agentId,
            subagentType = message.subagentType ?: existing.subagentType,
            taskDescription = message.taskDescription ?: existing.taskDescription,
            atMillis = existing.atMillis ?: at.takeIf { it > 0 },
        )
    }
    return next.upsert(updated).let { if (message.aborted) it.finishStreaming() else it }
}

/** Whether [text], a text block of a user line, is the CLI's interrupt marker ("[Request interrupted by user]" and the like). */
internal fun isInterruptMarker(text: String): Boolean = text.startsWith("[Request interrupted")

/** Whether a text block of this user line is the interrupt marker, which [reduce] shows as "Stopped.". */
internal fun CliMessage.User.isInterrupt(): Boolean = content.any { it is ContentBlock.Text && isInterruptMarker(it.text) }

private fun reduceUser(state: ChatState, message: CliMessage.User, at: Long): ChatState {
    if (message.isReplay) return state
    // The line's tool_use_result belongs to its one tool result; with several, none can tell whose it is.
    val result: JsonElement? = message.toolUseResult.takeIf { message.content.count { it is ContentBlock.ToolResult } == 1 }
    var next = state
    for (block in message.content) {
        next = when (block) {
            is ContentBlock.ToolResult -> next.append(
                ChatEntry.ToolResult(
                    id = "result-${block.toolUseId}",
                    toolUseId = block.toolUseId,
                    text = toolResultText(block),
                    isError = block.isError,
                    content = block.content,
                    result = result,
                    decision = message.toolResultMeta.firstOrNull { it.id == block.toolUseId }?.let { meta ->
                        meta.decision?.let { PermissionDecision(it, meta.source, meta.reasonType, meta.nonExecutionKind) }
                    },
                    parentToolUseId = message.parentToolUseId,
                    agentId = message.agentId,
                    atMillis = at,
                ),
            )
            is ContentBlock.Text -> if (isInterruptMarker(block.text)) {
                next.notice(NoticeLevel.INFO, "Stopped.", NoticeKind.INTERRUPTED).copy(turnInterrupted = true)
            } else {
                next
            }
            // A prompt's image is its entry's, which UserSent brings.
            is ContentBlock.Thinking, is ContentBlock.ToolUse, is ContentBlock.Image, is ContentBlock.Other -> next
        }
    }
    return next
}

private fun reduceStream(state: ChatState, message: CliMessage.StreamEvent, at: Long): ChatState {
    val parent = message.parentToolUseId
    return when (val event = message.event) {
        is StreamEvent.MessageStart -> {
            if (parent == null) {
                // Only the main thread's requests fill the context the ring shows; a subagent runs in its own.
                val tokens = event.inputTokens
                val counted = if (tokens == null) state else state.copy(context = ContextUsage(tokens, state.context?.window))
                counted.checkModel(event.model, fromAssistant = true)
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
                    // When the reply began, for its hover row's time (polish spec 2.6); 0 is an unknown time.
                    atMillis = at.takeIf { it > 0 && parent == null },
                ),
            )
        }
        is StreamEvent.ContentBlockStart -> state.editDraft(message) { blocks ->
            if (event.index < blocks.size) {
                blocks
            } else {
                blocks + List(event.index - blocks.size) { AssistantBlock.Other("gap") } + draftBlock(event.block, at)
            }
        }.let { next ->
            // A main-thread tool call starting: the working row's verb re-rolls on it.
            val block = event.block
            if (block !is ContentBlock.ToolUse || parent != null) next else next.withTurn { it.copy(lastToolUseId = block.id, toolCount = it.toolCount + 1) }
        }
        is StreamEvent.ContentBlockDelta -> state.editDraft(message) { blocks ->
            val draft = blocks.getOrNull(event.index) ?: return@editDraft blocks
            val updated = when (val delta = event.delta) {
                is Delta.Text -> (draft as? AssistantBlock.Text)?.copy(text = draft.text + delta.text)
                is Delta.Thinking -> (draft as? AssistantBlock.Thinking)?.copy(
                    text = draft.text + delta.thinking,
                    estimatedTokens = delta.estimatedTokens ?: draft.estimatedTokens,
                )
                is Delta.InputJson -> (draft as? AssistantBlock.ToolUse)?.copy(partialInput = draft.partialInput + delta.partialJson)
                is Delta.Other -> null
            } ?: draft
            blocks.toMutableList().also { it[event.index] = updated }
        }
        is StreamEvent.MessageStop -> {
            val entry = state.draftFor(message) ?: return state
            state.upsert(entry.copy(streaming = false))
        }
        // The request's final count now includes its thinking: the running estimate is done.
        is StreamEvent.MessageDelta ->
            state.withTurn { it.copy(outputTokens = it.outputTokens + (event.outputTokens ?: 0), thinkingEstimate = 0) }
        // A late content_block_stop for an already completed block is a no-op (docs/protocol.md 6.4).
        is StreamEvent.ContentBlockStop, is StreamEvent.Other -> state
    }
}

private fun reduceResult(state: ChatState, result: CliMessage.Result): ChatState {
    // An interrupt: the result says so, or, from a CLI that sends no terminal_reason, the turn carried the interrupt marker.
    val interrupted = result.terminalReason == "aborted_streaming" || state.turnInterrupted
    val end = when {
        interrupted -> TurnEnd.INTERRUPTED
        result.isError -> TurnEnd.FAILED
        else -> TurnEnd.COMPLETED
    }
    val ended = ChatEntry.TurnEnded(
        id = turnEndId(state),
        end = end,
        durationMs = result.durationMs,
        outputTokens = result.outputTokens,
        denials = result.permissionDenials,
        error = if (end != TurnEnd.FAILED) null else state.turn?.lastError ?: result.result ?: result.errors.joinToString("; ").ifEmpty { null },
    )
    val next = state.finishStreaming().append(ended).copy(
        busy = false,
        sessionId = result.sessionId ?: state.sessionId,
        pendingPermissions = emptyList(),
        lastTurnEnd = end,
        turnsEnded = state.turnsEnded + 1,
        turnInterrupted = false,
        turn = null,
        context = contextAfter(state, result),
    )
    return when {
        interrupted -> next // the interrupt already showed "Stopped."
        result.isError -> next.notice(
            NoticeLevel.ERROR,
            "The turn failed" + result.failureKind()?.let { " ($it)" }.orEmpty() + ": " +
                (result.result ?: result.errors.joinToString("; ").ifEmpty { "no details" }),
        )
        else -> next
    }
}

/**
 * The context with the window [result] reports: the active model's, else, after a mid-turn fallback that left it out of
 * `modelUsage`, the largest listed. The tokens stay as the last main-thread `message_start` set them (null, never 0, before one);
 * a result without any window keeps the context as it is.
 */
private fun contextAfter(state: ChatState, result: CliMessage.Result): ContextUsage? {
    val windows = result.contextWindows
    if (windows.isEmpty()) return state.context
    val window = windows[state.activeModel] ?: windows.values.max()
    return ContextUsage(state.context?.tokens, window)
}

/**
 * What kind of failure this error result is: its subtype (`error_max_turns` and the like), or, when the subtype is `success` as for
 * an API error (with `is_error`), its terminal reason (`api_error`); null when neither says anything.
 */
private fun CliMessage.Result.failureKind(): String? = subtype.takeIf { it.isNotEmpty() && it != "success" } ?: terminalReason

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

/**
 * What the chat says for an API error on [message]. A rejected model is named as the line names it, unless that is no model (the
 * CLI's own line says `<synthetic>`): then as [state] has it, the model picked, else the one the CLI reported.
 */
private fun errorText(error: String, message: CliMessage.Assistant, state: ChatState): String {
    val text = message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
    return when (error) {
        "authentication_failed" -> "The claude CLI (Claude Code) is not signed in. Run `claude` in a terminal to sign in, then retry."
        "model_not_found" -> {
            val model = message.model?.takeUnless { it.startsWith("<") } ?: state.requestedModel ?: state.activeModel
            "The CLI rejected the model${model?.let { " $it" } ?: ""}. Pick another model."
        }
        else -> text.ifEmpty { "The CLI reported an error: $error" }
    }
}

/**
 * A block as its `content_block_start` drafts it: a thinking block starts its clock at [at], and a tool call has no input until its
 * complete line (the start carries `{}`, which is not the input).
 */
private fun draftBlock(block: ContentBlock, at: Long): AssistantBlock = when (block) {
    is ContentBlock.Thinking -> AssistantBlock.Thinking(block.thinking, startedAtMillis = at)
    is ContentBlock.ToolUse -> AssistantBlock.ToolUse(block.id, block.name, input = null, partialInput = "")
    is ContentBlock.Text, is ContentBlock.ToolResult, is ContentBlock.Image, is ContentBlock.Other -> completeBlock(block, null, at)
}

/** A block of a complete line ([message]'s, if any): a thinking block takes the line's duration and completes at [at]. */
private fun completeBlock(block: ContentBlock, message: CliMessage.Assistant?, at: Long): AssistantBlock = when (block) {
    is ContentBlock.Text -> AssistantBlock.Text(block.text)
    is ContentBlock.Thinking -> AssistantBlock.Thinking(block.thinking, durationMs = message?.thinkingDurationMs, completedAtMillis = at)
    is ContentBlock.ToolUse -> AssistantBlock.ToolUse(block.id, block.name, block.input, partialInput = "")
    is ContentBlock.ToolResult -> AssistantBlock.Other("tool_result")
    is ContentBlock.Image -> AssistantBlock.Other("image")
    is ContentBlock.Other -> AssistantBlock.Other(block.type)
}

/**
 * [complete], a block of a complete line, in place of its [draft]. A thinking block keeps what streamed: its text when the line's is
 * empty (the line may omit what was displayed), its estimate and start, and takes its duration from the host's clock when the line
 * has none (an older CLI).
 */
private fun completed(draft: AssistantBlock, complete: AssistantBlock, at: Long): AssistantBlock {
    if (draft !is AssistantBlock.Thinking || complete !is AssistantBlock.Thinking) return complete
    return complete.copy(
        text = complete.text.ifEmpty { draft.text },
        estimatedTokens = draft.estimatedTokens,
        durationMs = complete.durationMs ?: draft.startedAtMillis?.let { at - it },
        startedAtMillis = draft.startedAtMillis,
    )
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

/**
 * One entry of a `models` list; null without a string `value`. Its effort levels count only with `supportsEffort` true (agent-sync:
 * `Haiku 4.5` has neither), and a level that is no string is skipped.
 */
private fun JsonObject.modelOption(): ModelOption? {
    val value = string("value") ?: return null
    return ModelOption(
        value = value,
        displayName = string("displayName") ?: value,
        description = string("description"),
        resolvedModel = string("resolvedModel"),
        supportsAutoMode = boolean("supportsAutoMode") == true,
        effortLevels = if (boolean("supportsEffort") == true) strings("supportedEffortLevels") else emptyList(),
    )
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

private fun ChatState.notice(level: NoticeLevel, text: String, kind: NoticeKind = NoticeKind.GENERIC): ChatState =
    append(ChatEntry.Notice("notice-$noticeCount", level, text, kind)).copy(noticeCount = noticeCount + 1)

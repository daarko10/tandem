package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContentBlock
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The prompt the fixtures were recorded with: no fixture holds the prompt's own user line, so a replay sends it before each `system/init`. */
internal const val PROMPT = "Reply with the single word: pong"

/**
 * Replays fixture [name] through [reduce], starting from [initial], with one [ChatRowsBuilder] ([builder]) that builds after every
 * event (it never throws); a test that goes on building passes its own. Before every `system/init` line it sends [prompt] as `UserSent("u<line>")`, as the fake CLI waits for one prompt per init.
 * Each line `i` (0-based) is stamped `i * 100` ms. [lines] replaces the fixture's lines (a test that patches one in memory). [onLine]
 * sees the 1-based line number, the state and the rows after that line.
 */
internal fun replay(
    name: String,
    options: RowOptions = RowOptions(showThinking = true),
    prompt: String = PROMPT,
    initial: ChatState = ChatState(),
    lines: List<String> = Fixtures.lines(name),
    builder: ChatRowsBuilder = ChatRowsBuilder(options),
    onLine: (line: Int, state: ChatState, rows: ChatRows) -> Unit = { _, _, _ -> },
): Pair<ChatState, ChatRows> {
    var state = initial
    var rows = builder.build(state)
    lines.forEachIndexed { i, line ->
        val message = Ndjson.decode(line)
        if (message is CliMessage.SystemInit) {
            state = reduce(state, SessionEvent.UserSent("u${i + 1}", prompt, atMillis = i * 100L))
            rows = builder.build(state)
        }
        state = reduce(state, SessionEvent.Cli(message, atMillis = i * 100L))
        rows = builder.build(state)
        onLine(i + 1, state, rows)
    }
    return state to rows
}

/**
 * Replays fixture [name] as [replay] does, with the host's own sends: the events [sends] lists under `n` are reduced right before line
 * `n` (1-based), and nothing else is sent. Unlike [replay] it sends no prompt before each `system/init`, which would put a made-up
 * [PROMPT] user message inside a queued turn (`queue-later:54`). Each line `i` (0-based) and the sends before it are stamped `i * 100`
 * ms. [onLine] sees the 1-based line number, the state and the rows after that line.
 */
internal fun replayWithSends(
    name: String,
    sends: Map<Int, List<SessionEvent>>,
    options: RowOptions = RowOptions(showThinking = true),
    onLine: (line: Int, state: ChatState, rows: ChatRows) -> Unit = { _, _, _ -> },
): Pair<ChatState, ChatRows> {
    val builder = ChatRowsBuilder(options)
    var state = ChatState()
    var rows = builder.build(state)
    Fixtures.lines(name).forEachIndexed { i, line ->
        sends[i + 1]?.forEach { event ->
            state = reduce(state, event)
            rows = builder.build(state)
        }
        state = reduce(state, SessionEvent.Cli(Ndjson.decode(line), atMillis = i * 100L))
        rows = builder.build(state)
        onLine(i + 1, state, rows)
    }
    return state to rows
}

/** The rows without the working row and the turn footer, which T6 adds. */
internal fun ChatRows.content(): List<ChatRow> = rows.filter { it !is WorkingRow && it !is TurnEndRow }

/** The decoded line [line] (1-based) of fixture [name]. */
internal fun fixtureLine(name: String, line: Int): CliMessage = Ndjson.decode(Fixtures.lines(name)[line - 1])

/** The single tool call of the complete `assistant` line [line] of fixture [name], as the reducer stores it. */
internal fun toolUseAt(name: String, line: Int): AssistantBlock.ToolUse {
    val block = (fixtureLine(name, line) as CliMessage.Assistant).content.filterIsInstance<ContentBlock.ToolUse>().single()
    return AssistantBlock.ToolUse(block.id, block.name, block.input, partialInput = "")
}

/** The tool result of the `user` line [line] of fixture [name], as the reducer stores it. */
internal fun toolResultAt(name: String, line: Int): ChatEntry.ToolResult =
    reduce(ChatState(), SessionEvent.Cli(fixtureLine(name, line))).entries.filterIsInstance<ChatEntry.ToolResult>().single()

/** What the builder hands labels and bodies for [result]: its `tool_use_result`, else its text. */
internal fun labelResult(result: ChatEntry.ToolResult): JsonElement = result.result ?: JsonPrimitive(result.text)

/** The input of the tool call on line [line] of fixture [name]. */
internal fun inputAt(name: String, line: Int): JsonObject = toolUseAt(name, line).input!!

/** Every tool row's lone call, in order. */
internal fun List<ChatRow>.toolRows(): List<ToolRow> = filterIsInstance<ToolRow>()

/** The `input` of the `can_use_tool` request on line [line] of fixture [name]. */
internal fun requestInput(name: String, line: Int): JsonObject =
    reduce(ChatState(), SessionEvent.Cli(fixtureLine(name, line))).pendingPermissions.single().input

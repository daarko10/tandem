package dev.daarkolabs.tandem.ui.dev

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContentBlock
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.StreamEvent
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/**
 * Development-only chat source for the Jewel rendering spike: [messageCount] messages, half of them prompts and half replies, the
 * replies streamed at roughly [tokensPerSecond] tokens per second (a token is ~4 characters). Each message goes through [reduce] as
 * the events a real turn has, so [state] is what a session's state would be.
 */
internal class FakeTranscript(private val messageCount: Int = 200, private val tokensPerSecond: Int = 50) {
    val state: MutableStateFlow<ChatState> = MutableStateFlow(ChatState())

    suspend fun play(onProgress: (index: Int) -> Unit = {}) {
        val random = Random(7)
        val tokenDelayMs = 1000L / tokensPerSecond
        for (index in 0 until messageCount) {
            if (index % 2 == 0) {
                send(SessionEvent.UserSent("m$index", userPrompt(index), System.currentTimeMillis()))
            } else {
                val started = System.currentTimeMillis()
                send(stream(StreamEvent.MessageStart("msg$index", model = null, thinkingDisplay = null, inputTokens = null)))
                send(stream(StreamEvent.ContentBlockStart(0, ContentBlock.Text(""))))
                for (token in assistantReply(index, random).chunked(4)) {
                    delay(tokenDelayMs)
                    send(stream(StreamEvent.ContentBlockDelta(0, Delta.Text(token))))
                }
                send(SessionEvent.Cli(result(System.currentTimeMillis() - started), System.currentTimeMillis()))
            }
            onProgress(index)
        }
    }

    private fun send(event: SessionEvent) {
        state.value = reduce(state.value, event)
    }

    private fun stream(event: StreamEvent): SessionEvent =
        SessionEvent.Cli(CliMessage.StreamEvent(event, parentToolUseId = null, apiMessageId = null, raw = EMPTY), System.currentTimeMillis())

    private fun result(durationMs: Long): CliMessage.Result = CliMessage.Result(
        subtype = "success",
        isError = false,
        result = null,
        stopReason = null,
        terminalReason = null,
        sessionId = null,
        totalCostUsd = null,
        errors = emptyList(),
        modelUsage = emptySet(),
        durationMs = durationMs,
        durationApiMs = null,
        outputTokens = null,
        numTurns = 1,
        permissionDenials = emptyList(),
        raw = EMPTY,
    )

    private fun userPrompt(index: Int): String = "Message ${index + 1}: can you refactor `Parser${index / 2}` to stream its output?"

    private fun assistantReply(index: Int, random: Random): String = buildString {
        append("### Step ${index / 2 + 1}\n\n")
        append("Here is a **streaming** version. It keeps the `reader` open and emits one item per line, ")
        append("so the caller sees results before the input ends.\n\n")
        if (random.nextBoolean()) {
            append("- parse lazily\n- keep the buffer small\n- close the reader in `finally`\n\n")
        }
        append("```kotlin\n")
        append("fun parse${index}(reader: BufferedReader): Sequence<Item> = sequence {\n")
        append("    reader.use { r ->\n")
        append("        while (true) {\n")
        append("            val line = r.readLine() ?: break\n")
        append("            if (line.isNotBlank()) yield(Item.parse(line))\n")
        append("        }\n")
        append("    }\n")
        append("}\n")
        append("```\n\n")
        append("Run the tests with `./gradlew test` and check the *Problems* view.")
    }

    private companion object {
        val EMPTY: JsonObject = JsonObject(emptyMap())
    }
}

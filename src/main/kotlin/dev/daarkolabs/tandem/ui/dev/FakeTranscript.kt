package dev.daarkolabs.tandem.ui.dev

import dev.daarkolabs.tandem.ui.ChatRole
import dev.daarkolabs.tandem.ui.TranscriptEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random

/**
 * Development-only transcript source for the Jewel rendering spike: [messageCount] messages, assistant
 * replies streamed at roughly [tokensPerSecond] tokens per second (a token is ~4 characters).
 */
internal class FakeTranscript(private val messageCount: Int = 200, private val tokensPerSecond: Int = 50) {
    val entries: MutableStateFlow<List<TranscriptEntry>> = MutableStateFlow(emptyList())

    suspend fun play(onProgress: (index: Int) -> Unit = {}) {
        val random = Random(7)
        val tokenDelayMs = 1000L / tokensPerSecond
        for (index in 0 until messageCount) {
            val id = "m$index"
            if (index % 2 == 0) {
                entries.value += TranscriptEntry(id, ChatRole.USER, userPrompt(index), streaming = false)
            } else {
                val full = assistantReply(index, random)
                val tokens = full.chunked(4)
                var text = ""
                entries.value += TranscriptEntry(id, ChatRole.ASSISTANT, text, streaming = true)
                for (token in tokens) {
                    delay(tokenDelayMs)
                    text += token
                    entries.value = entries.value.dropLast(1) + TranscriptEntry(id, ChatRole.ASSISTANT, text, streaming = true)
                }
                entries.value = entries.value.dropLast(1) + TranscriptEntry(id, ChatRole.ASSISTANT, text, streaming = false)
            }
            onProgress(index)
        }
    }

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
}

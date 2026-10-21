package dev.daarkolabs.tandem.protocol

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The CLI is an external process that changes weekly: decoding must never throw. */
class RobustnessTest {
    @Test
    fun `garbage and non-object lines decode to Unknown`() {
        for (line in listOf("", "not json", "{", "[]", "42", "null", "\"text\"", "{\"type\":7}", "{\"no_type\":true}")) {
            val message = Ndjson.decode(line)
            assertIs<CliMessage.Unknown>(message, line)
            assertEquals(line, message.line)
        }
    }

    @Test
    fun `unknown types and subtypes decode to Unknown`() {
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"brand_new","x":1}"""))
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"system","subtype":"brand_new"}"""))
        val request = Ndjson.decode("""{"type":"control_request","request_id":"r1","request":{"subtype":"brand_new"}}""")
        assertIs<InboundRequest.Other>(assertIs<CliMessage.ControlRequest>(request).request)
    }

    @Test
    fun `known types with missing or mistyped fields still decode`() {
        val assistant = Ndjson.decode("""{"type":"assistant","message":{"content":[{"type":"text"},{"type":"mystery"},7]}}""")
        assertIs<CliMessage.Assistant>(assistant)
        assertEquals(listOf("", "mystery"), assistant.content.map { (it as? ContentBlock.Text)?.text ?: (it as ContentBlock.Other).type })

        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"system","subtype":"init","model":42}"""))
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"control_request","request":{"subtype":"interrupt"}}"""))
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"stream_event"}"""))
        val delta = Ndjson.decode("""{"type":"stream_event","event":{"type":"content_block_delta","index":"x","delta":{"type":"new_delta"}}}""")
        assertIs<StreamEvent.Other>(assertIs<CliMessage.StreamEvent>(delta).event)
    }

    @Test
    fun `truncated and mutated fixture lines never throw`() {
        val random = Random(42)
        for (name in Fixtures.names()) {
            for (line in Fixtures.lines(name)) {
                repeat(20) {
                    val cut = line.substring(0, random.nextInt(line.length + 1))
                    Ndjson.decode(cut)
                    val chars = line.toCharArray()
                    chars[random.nextInt(chars.size)] = "{}[]\",:0an".random(random)
                    Ndjson.decode(String(chars))
                }
            }
        }
    }
}

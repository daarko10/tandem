package dev.daarkolabs.tandem.session.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptFilesTest {
    @TempDir
    lateinit var folder: Path

    private val id = "66666666-6666-4666-8666-666666666666"

    @Test
    fun `a custom-title line goes on a line of its own, even after a line the CLI did not finish`() {
        val transcript = folder.resolve("$id.jsonl")
        transcript.writeText("{\"type\":\"user\"}\n{\"type\":\"assist")
        TranscriptFiles.appendCustomTitle(transcript, id, "Gamma \"quoted\"", Instant.parse("2026-10-16T12:00:00Z"))

        val lines = transcript.readLines()
        assertEquals(listOf("{\"type\":\"user\"}", "{\"type\":\"assist"), lines.take(2), "the earlier lines stay as they were")
        val line = Json.parseToJsonElement(lines[2]).jsonObject
        assertEquals("Gamma \"quoted\"", line["customTitle"]!!.jsonPrimitive.content)
        val timestamp = line["timestamp"]!!.jsonPrimitive.content
        assertEquals("2026-10-16T12:00:00.000Z", timestamp, "milliseconds always, as JavaScript writes them")
        assertTrue(transcript.readText().endsWith("}\n"))
        assertEquals("Gamma \"quoted\"", TranscriptReader.meta(transcript).customTitle)
    }

    @Test
    fun `delete removes the transcript and its directory, and nothing a link in it points to`(@TempDir outside: Path) {
        val transcript = folder.resolve("$id.jsonl").apply { writeText("{}\n") }
        val sidecar = folder.resolve(id)
        sidecar.resolve("subagents").createDirectories().resolve("agent-x.jsonl").writeText("{}\n")
        sidecar.resolve("custom-title.json").writeText("{}")
        val kept = outside.resolve("keep.txt").apply { writeText("x") }
        sidecar.resolve("linked-folder").createSymbolicLinkPointingTo(outside)
        sidecar.resolve("subagents").resolve("linked-file").createSymbolicLinkPointingTo(kept)
        val neighbour = folder.resolve("77777777-7777-4777-8777-777777777777.jsonl").apply { writeText("{}\n") }

        TranscriptFiles.delete(transcript)
        assertFalse(transcript.exists())
        assertFalse(sidecar.exists(LinkOption.NOFOLLOW_LINKS))
        assertEquals("x", kept.readText())
        assertTrue(neighbour.exists())
    }

    @Test
    fun `a session directory that is a link is deleted as a link`(@TempDir outside: Path) {
        val transcript = folder.resolve("$id.jsonl").apply { writeText("{}\n") }
        val kept = outside.resolve("keep.txt").apply { writeText("x") }
        folder.resolve(id).createSymbolicLinkPointingTo(outside)

        TranscriptFiles.delete(transcript)
        assertFalse(folder.resolve(id).exists(LinkOption.NOFOLLOW_LINKS))
        assertEquals("x", kept.readText())
    }
}

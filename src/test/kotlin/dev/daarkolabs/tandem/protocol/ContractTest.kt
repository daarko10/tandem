package dev.daarkolabs.tandem.protocol

import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Run by the nightly CLI contract workflow against freshly recorded output of the newest CLI (`-Dtandem.contractDir=...`): its
 * stream-json output (`.ndjson` files) and the transcripts it wrote to disk (`.jsonl` files in `transcripts`). Skipped in normal
 * test runs.
 */
class ContractTest {
    @Test
    fun `live transcripts decode with every core type recognised`() {
        val dir = System.getProperty("tandem.contractDir").orEmpty()
        assumeTrue("no contract transcripts", dir.isNotEmpty())
        val files = File(dir).listFiles { f -> f.name.endsWith(".ndjson") }.orEmpty()
        assertTrue(files.isNotEmpty(), "no transcripts in $dir")

        val core = setOf("assistant", "user", "result", "stream_event", "control_request", "control_response")
        for (file in files) {
            val messages = file.readLines().filter { it.isNotBlank() }.map(Ndjson::decode)
            val unrecognised = messages.filterIsInstance<CliMessage.Unknown>().filter { it.type in core || it.type == null }
            assertTrue(unrecognised.isEmpty(), "${file.name}: ${unrecognised.map { it.type }}")
            assertTrue(messages.any { it is CliMessage.SystemInit }, "${file.name}: no system/init")
            assertTrue(messages.any { it is CliMessage.Result }, "${file.name}: no result")
        }
    }

    @Test
    fun `live transcripts load with the reader`() {
        val dir = System.getProperty("tandem.contractDir").orEmpty()
        assumeTrue("no contract transcripts", dir.isNotEmpty())
        val files = File(dir, "transcripts").listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
        assertTrue(files.isNotEmpty(), "no transcripts in $dir/transcripts")

        for (file in files) {
            val entries = runBlocking { TranscriptReader.read(file.toPath()) }.state.entries
            assertTrue(entries.any { it is ChatEntry.User }, "${file.name}: no user entry")
            assertTrue(entries.any { it is ChatEntry.Assistant }, "${file.name}: no assistant entry")
        }
    }
}

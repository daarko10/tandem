package dev.daarkolabs.tandem.protocol

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Run by the nightly CLI contract workflow against freshly recorded transcripts
 * (`-Dtandem.contractDir=...`). Skipped in normal test runs.
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
}

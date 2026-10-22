package dev.daarkolabs.tandem.protocol

import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Run by the nightly CLI contract workflow against freshly recorded output of the newest CLI (`-Dtandem.contractDir=...`): its
 * stream-json output (`.ndjson` files) and the transcripts it wrote to disk (`.jsonl` files in `transcripts`). Skipped in normal
 * test runs. Beside the checks every recording gets, the scenario recordings the workflow makes (`mcp-slash`, `mcp-status`,
 * `mcp-tool-call`, `bash-mode`, `bash-mode-respond`, `bash-mode-context`) are checked for what Tandem reads in them: the slash
 * catalogue, `init`'s `terminal_slash_commands` and a local command; the `mcp_status`, `mcp_toggle` and `mcp_authenticate` answers
 * and `init`'s `mcp_servers`; an MCP tool's permission request naming its server; and each quiet shell-escape frame's zero-turn
 * result. A check is skipped when its file is not there.
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
            // A session of shell escapes only (bash-mode-respond) has its prompts as shell rows.
            assertTrue(entries.any { it is ChatEntry.User || it is ChatEntry.Shell }, "${file.name}: no user or shell entry")
            if (file.name.startsWith("bash-mode")) assertTrue(entries.any { it is ChatEntry.Shell }, "${file.name}: no shell entry")
            assertTrue(entries.any { it is ChatEntry.Assistant }, "${file.name}: no assistant entry")
        }
    }

    @Test
    fun `mcp-slash - the slash catalogue and a local command decode`() {
        val messages = recording("mcp-slash")
        val catalogue = messages.filterIsInstance<CliMessage.ControlResponse>().firstNotNullOfOrNull { ControlPayloads.initialize(it.payload) }
        assertTrue(assertNotNull(catalogue, "no initialize answer with commands").commands.isNotEmpty(), "the initialize commands are empty")
        assertTrue(messages.filterIsInstance<CliMessage.SystemInit>().any { it.slashCommands.isNotEmpty() }, "no init lists slash commands")
        // The palette leaves these out (MCP spec 3.4).
        assertTrue(messages.filterIsInstance<CliMessage.SystemInit>().any { it.terminalSlashCommands.isNotEmpty() }, "no init lists terminal_slash_commands")
        // The first prompt is /mcp: the CLI answers it itself, with a local-command line and a result that names it.
        val local = messages.indexOfFirst { it is CliMessage.Assistant && it.localCommand != null }
        assertTrue(local >= 0, "no assistant line carries local_command_*")
        assertEquals("mcp", (messages[local] as CliMessage.Assistant).localCommand!!.command)
        val result = messages.drop(local).filterIsInstance<CliMessage.Result>().first()
        assertEquals("mcp", result.localCommand)
        assertEquals(0, result.numTurns)
    }

    @Test
    fun `mcp-status - mcp_status, mcp_toggle and mcp_authenticate answers and init's mcp_servers decode`() {
        val messages = recording("mcp-status")
        val responses = messages.filterIsInstance<CliMessage.ControlResponse>()
        val listings = responses.mapNotNull { ControlPayloads.mcpStatus(it.payload) }
        assertTrue(listings.isNotEmpty(), "no mcp_status answer with an mcpServers array")
        assertTrue(
            listings.any { listing -> listing.servers.any { it.name == "echo" && it.status == McpStatus.CONNECTED } },
            "echo is never connected: ${listings.map { l -> l.servers.map { it.name to it.statusText } }}",
        )
        // mcp_toggle's success is bare (docs/protocol.md 4.4); the scenario toggles echo off and on.
        assertTrue(responses.count { it.isSuccess && it.payload.isNullOrEmpty() } >= 2, "no bare success for the two mcp_toggle requests")
        // The sign-in opens authUrl itself (MCP spec 3.3); authy always needs auth.
        assertTrue(
            responses.mapNotNull { ControlPayloads.mcpAuthenticate(it.payload) }
                .any { ControlPayloads.browsableUrl(it.authUrl) != null && it.callbackPort != null },
            "no mcp_authenticate answer with a browsable authUrl",
        )
        // The header badge and the panel's merge read init's thin list (MCP spec 3.1, 4.2).
        assertTrue(
            messages.filterIsInstance<CliMessage.SystemInit>().any { init -> init.mcpServers.any { it.name == "echo" && it.status == McpStatus.CONNECTED } },
            "no init lists echo connected in mcp_servers",
        )
    }

    @Test
    fun `mcp-tool-call - the permission request names the MCP server`() {
        val asks = recording("mcp-tool-call").filterIsInstance<CliMessage.ControlRequest>().map { it.request }
            .filterIsInstance<InboundRequest.CanUseTool>()
        assertTrue(asks.any { it.mcpServer?.name == "echo" }, "no can_use_tool names the echo server: ${asks.map { it.mcpServer }}")
    }

    @Test
    fun `bash-mode-respond - the quiet frame gets a zero-turn result naming its uuid`() {
        val lines = recordingLines("bash-mode-respond")
        val quiet = commandUuids(lines).first()
        val results = lines.map(Ndjson::decode).filterIsInstance<CliMessage.Result>()
        assertTrue(results.any { it.numTurns == 0 && it.userMessageUuids == listOf(quiet) }, "no zero-turn result names $quiet")
        assertTrue(results.any { (it.numTurns ?: 0) > 0 && quiet !in it.userMessageUuids }, "the output frame ran no turn of its own")
    }

    @Test
    fun `bash-mode-context - the one context frame gets a zero-turn result naming its uuid`() {
        val lines = recordingLines("bash-mode-context")
        val quiet = commandUuids(lines).first()
        val results = lines.map(Ndjson::decode).filterIsInstance<CliMessage.Result>()
        val zero = results.indexOfFirst { it.numTurns == 0 && it.userMessageUuids == listOf(quiet) }
        assertTrue(zero >= 0, "no zero-turn result names $quiet alone")
        assertTrue(
            results.drop(zero + 1).any { (it.numTurns ?: 0) > 0 && quiet !in it.userMessageUuids },
            "the next prompt ran no turn of its own",
        )
    }

    /** Whether the CLI merges the two back-to-back frames into one result or answers each is timing; Tandem handles both. */
    @Test
    fun `bash-mode - each of the two quiet frames is named by a zero-turn result`() {
        val lines = recordingLines("bash-mode")
        val quiet = commandUuids(lines).take(2)
        val results = lines.map(Ndjson::decode).filterIsInstance<CliMessage.Result>()
        assertTrue(quiet.all { q -> results.any { it.numTurns == 0 && q in it.userMessageUuids } }, "no zero-turn result names each of $quiet")
    }

    /** The lines of recording [name], or the test is skipped when it was not recorded. */
    private fun recordingLines(name: String): List<String> {
        val dir = System.getProperty("tandem.contractDir").orEmpty()
        assumeTrue("no contract transcripts", dir.isNotEmpty())
        val file = File(dir, "$name.ndjson")
        assumeTrue("$name was not recorded", file.isFile)
        return file.readLines().filter { it.isNotBlank() }
    }

    private fun recording(name: String): List<CliMessage> = recordingLines(name).map(Ndjson::decode)

    /** The user frames' uuids in the order the CLI first queued them (`command_lifecycle`, not decoded by Tandem). */
    private fun commandUuids(lines: List<String>): List<String> = lines.mapNotNull { line ->
        val fields = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@mapNotNull null
        if (fields["type"]?.jsonPrimitive?.contentOrNull != "command_lifecycle") null else fields["command_uuid"]?.jsonPrimitive?.contentOrNull
    }.distinct()
}

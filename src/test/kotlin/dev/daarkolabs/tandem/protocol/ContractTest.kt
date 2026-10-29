package dev.daarkolabs.tandem.protocol

import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.isUsageLine
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * test runs.
 *
 * The two `live transcripts` tests read only the recording the drift loop promotes to a fixture
 * (`.claude/skills/fix-contract-drift`, step 2): [STREAM] and the [TRANSCRIPTS]. `scripts/classify_drift.py` hands their
 * failures to the fixer by test name, so drift only a scenario recording shows must not fail them. The scenario recordings the
 * workflow makes (`mcp-slash`, `mcp-status`, `mcp-tool-call`, `bash-mode`, `bash-mode-respond`, `bash-mode-context`, `queue-fold`,
 * `queue-later`, `queue-cancel`, `queue-cancel-steer`, `queue-during-card`, `fork-at`, `parity-requests`, `ide-edit`) get the
 * same decode and reader checks in the `scenario` tests, whose red the loop reports on its standing issue instead, and are
 * checked for what Tandem reads in them: the slash catalogue, `init`'s `terminal_slash_commands` and a local command; the
 * `mcp_status`, `mcp_toggle` and `mcp_authenticate` answers and `init`'s `mcp_servers`; an MCP tool's permission request naming
 * its server; each quiet shell-escape frame's zero-turn result; the command lifecycle of messages written while a turn runs
 * (folded, `later`, withdrawn, during a card), a fork cut at a reply, the context and usage answers, and Tandem's
 * own MCP server's `mcp_message` frames and tools in `init`. A check is skipped when
 * its file is not there.
 */
class ContractTest {
    @Test
    fun `live transcripts decode with every core type recognised`() {
        val dir = contractDir()
        val file = File(dir, STREAM)
        assertTrue(file.isFile, "no $STREAM in $dir")
        assertDecodes(file)
    }

    @Test
    fun `live transcripts load with the reader`() {
        val dir = contractDir()
        val files = File(dir, "transcripts").listFiles { f -> f.name in TRANSCRIPTS }.orEmpty()
        assertTrue(files.isNotEmpty(), "no transcripts in $dir/transcripts")

        for (file in files) {
            val entries = load(file)
            assertTrue(entries.any { it is ChatEntry.User }, "${file.name}: no user entry")
            assertTrue(entries.any { it is ChatEntry.Assistant }, "${file.name}: no assistant entry")
        }
    }

    @Test
    fun `scenario recordings decode with every core type recognised`() {
        val dir = contractDir()
        val files = File(dir).listFiles { f -> f.name.endsWith(".ndjson") && f.name != STREAM }.orEmpty().sortedBy { it.name }
        assumeTrue("no scenario recordings", files.isNotEmpty())
        files.forEach(::assertDecodes)
    }

    @Test
    fun `scenario transcripts load with the reader`() {
        val dir = contractDir()
        val files = File(dir, "transcripts").listFiles { f -> f.name.endsWith(".jsonl") && f.name !in TRANSCRIPTS }.orEmpty()
            .sortedBy { it.name }
        assumeTrue("no scenario transcripts", files.isNotEmpty())

        for (file in files) {
            val entries = load(file)
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

    // spec: ide-edit: mcp_message frames decode and init names both tools
    @Test
    fun `ide-edit - mcp_message frames decode and init names both tools`() {
        val messages = recording("ide-edit")
        val rpc = messages.filterIsInstance<CliMessage.ControlRequest>().map { it.request }.filterIsInstance<InboundRequest.McpMessage>()
            .filter { it.serverName == "tandem" }.mapNotNull { jsonRpcRequest(it.message) }
        val methods = rpc.map { it.method }
        for (method in listOf("initialize", "notifications/initialized", "tools/list", "tools/call")) {
            assertTrue(method in methods, "no mcp_message $method for tandem: $methods")
        }
        assertTrue(
            rpc.filter { it.method == "tools/call" }.all { (it.params?.get("_meta") as? JsonObject)?.containsKey("claudecode/toolUseId") == true },
            "a tools/call without _meta claudecode/toolUseId",
        )
        val init = messages.filterIsInstance<CliMessage.SystemInit>().firstOrNull()
        val tools = assertNotNull(init, "no init").tools
        assertTrue("mcp__tandem__edit_file" in tools && "mcp__tandem__write_file" in tools, "init does not name Tandem's tools: $tools")
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

    @Test
    fun `live queue and fork transcripts decode`() {
        // queue-fold: one result names the prompt and the message folded into its turn; the message was started before it, and the
        // transcript's queued_command attachment reads back as a folded prompt.
        val fold = recording("queue-fold")
        val foldResult = fold.indexOfFirst { it is CliMessage.Result && it.userMessageUuids.size == 2 }
        assertTrue(foldResult >= 0, "no result names two messages: ${fold.filterIsInstance<CliMessage.Result>().map { it.userMessageUuids.size }}")
        val named = (fold[foldResult] as CliMessage.Result).userMessageUuids
        assertTrue(named.any { uuid -> lifecycleIndex(fold, uuid, "started").let { it in 0 until foldResult } }, "none of $named started before the result")
        val twin = File(contractDir(), "transcripts/queue-fold.jsonl")
        if (twin.isFile) assertTrue(load(twin).any { it is ChatEntry.User && it.folded }, "the transcript has no folded prompt")

        // queue-later: a result that does not name the message comes between its queued and its started; a later result names it.
        val later = recording("queue-later")
        val laterUuid = lifecycleUuids(later).firstOrNull { uuid ->
            val queued = lifecycleIndex(later, uuid, "queued")
            val started = lifecycleIndex(later, uuid, "started")
            queued >= 0 && started > queued &&
                later.withIndex().any { (i, m) -> i in queued until started && m is CliMessage.Result && uuid !in m.userMessageUuids } &&
                later.withIndex().any { (i, m) -> i > started && m is CliMessage.Result && uuid in m.userMessageUuids }
        }
        assertNotNull(laterUuid, "no message was queued, held past a result, started and named by a later result")

        // queue-cancel-steer (outcome: withdrawable): cancel_async_message answers true and the message ends cancelled, never started.
        val steer = recording("queue-cancel-steer")
        val answer = steer.filterIsInstance<CliMessage.ControlResponse>().firstNotNullOfOrNull { cancelAsyncAnswer(it.payload) }
        assertEquals(true, answer, "the first cancel_async_message answer")
        val cancelled = lifecycleUuids(steer).filter { lifecycleIndex(steer, it, "cancelled") >= 0 }
        assertTrue(cancelled.any { lifecycleIndex(steer, it, "started") < 0 }, "no queued message ended cancelled: $cancelled")

        // queue-during-card (outcome: waits): the message is queued before the first result, which does not name it, and then
        // starts with an init and a result of its own.
        val card = recording("queue-during-card")
        val firstResult = card.indexOfFirst { it is CliMessage.Result }
        val first = card[firstResult] as CliMessage.Result
        assertEquals(1, first.userMessageUuids.size, "the first result names ${first.userMessageUuids}")
        val waiting = lifecycleUuids(card).firstOrNull { it !in first.userMessageUuids && lifecycleIndex(card, it, "queued") in 0 until firstResult }
        assertNotNull(waiting, "no message was queued before the first result")
        val startedAt = lifecycleIndex(card, waiting, "started")
        assertTrue(startedAt > firstResult, "the message started at $startedAt, not after the result at $firstResult")
        assertTrue(card.withIndex().any { (i, m) -> i > startedAt && m is CliMessage.SystemInit }, "no init after the message started")
        assertTrue(card.withIndex().any { (i, m) -> i > startedAt && m is CliMessage.Result && waiting in m.userMessageUuids }, "no result names $waiting")

        // fork-at: the fork's init carries its own id and its turn succeeds.
        val fork = recording("fork-at")
        val inits = fork.filterIsInstance<CliMessage.SystemInit>()
        assertTrue(inits.size >= 2 && inits.last().sessionId != inits.first().sessionId, "the fork's init has the origin's id: ${inits.size} inits")
        val last = fork.filterIsInstance<CliMessage.Result>().last()
        assertEquals("success", last.subtype)
        assertTrue(!last.isError, "the fork's last result is an error")
    }

    @Test
    fun `live control answers decode`() {
        // parity-requests: the context breakdown and the usage session decode, and /usage's reply is followed by a result.
        val requests = recording("parity-requests")
        val responses = requests.filterIsInstance<CliMessage.ControlResponse>()
        val breakdown = responses.firstOrNull { it.payload?.containsKey("categories") == true }
        assertNotNull(breakdown, "no answer with categories")
        assertTrue(assertNotNull(ControlPayloads.contextUsage(breakdown.payload)).categories.any { it.id == "messages" }, "no messages category")
        val session = responses.firstOrNull { it.payload?.containsKey("session") == true }
        assertNotNull(session, "no answer with a session")
        assertNotNull(assertNotNull(ControlPayloads.usage(session.payload)).session?.totalCostUsd, "the session has no total cost")
        val usageLine = requests.indexOfFirst { it is CliMessage.Assistant && it.isUsageLine() }
        assertTrue(usageLine >= 0, "no assistant line is /usage's reply")
        val after = requests.drop(usageLine + 1).filterIsInstance<CliMessage.Result>().firstOrNull()
        assertEquals("usage", assertNotNull(after, "no result after /usage's reply").localCommand)

        // queue-cancel: the withdrawal's answer and the interrupt's receipt.
        val cancel = recording("queue-cancel").filterIsInstance<CliMessage.ControlResponse>()
        assertEquals(true, cancel.firstNotNullOfOrNull { cancelAsyncAnswer(it.payload) }, "the cancel_async_message answer")
        val receipt = cancel.map { it.payload }.filter { it?.containsKey("still_queued") == true }.map(::interruptReceipt)
        assertTrue(receipt.any { it.cancelled.size == 1 }, "no interrupt receipt cancels one message: $receipt")
    }

    /** The recording's directory, or the test is skipped in a normal test run. */
    private fun contractDir(): String {
        val dir = System.getProperty("tandem.contractDir").orEmpty()
        assumeTrue("no contract transcripts", dir.isNotEmpty())
        return dir
    }

    private fun assertDecodes(file: File) {
        val messages = file.readLines().filter { it.isNotBlank() }.map(Ndjson::decode)
        val unrecognised = messages.filterIsInstance<CliMessage.Unknown>().filter { it.type in CORE || it.type == null }
        assertTrue(unrecognised.isEmpty(), "${file.name}: ${unrecognised.map { it.type }}")
        assertTrue(messages.any { it is CliMessage.SystemInit }, "${file.name}: no system/init")
        assertTrue(messages.any { it is CliMessage.Result }, "${file.name}: no result")
    }

    private fun load(file: File): List<ChatEntry> = runBlocking { TranscriptReader.read(file.toPath()) }.state.entries

    /** The lines of recording [name], or the test is skipped when it was not recorded. */
    private fun recordingLines(name: String): List<String> {
        val file = File(contractDir(), "$name.ndjson")
        assumeTrue("$name was not recorded", file.isFile)
        return file.readLines().filter { it.isNotBlank() }
    }

    private fun recording(name: String): List<CliMessage> = recordingLines(name).map(Ndjson::decode)

    /** The uuids of the `command_lifecycle` lines, in the order they first appear. */
    private fun lifecycleUuids(messages: List<CliMessage>): List<String> =
        messages.filterIsInstance<CliMessage.CommandLifecycle>().map { it.commandUuid }.distinct()

    /** The index of [uuid]'s first `command_lifecycle` line in [state], else -1. */
    private fun lifecycleIndex(messages: List<CliMessage>, uuid: String, state: String): Int =
        messages.indexOfFirst { it is CliMessage.CommandLifecycle && it.commandUuid == uuid && it.state == state }

    /** The user frames' uuids in the order the CLI first queued them (`command_lifecycle`, not decoded by Tandem). */
    private fun commandUuids(lines: List<String>): List<String> = lines.mapNotNull { line ->
        val fields = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@mapNotNull null
        if (fields["type"]?.jsonPrimitive?.contentOrNull != "command_lifecycle") null else fields["command_uuid"]?.jsonPrimitive?.contentOrNull
    }.distinct()

    private companion object {
        /** The stream the drift loop promotes (`scripts/classify_drift.py` reads it too). */
        const val STREAM: String = "contract-hello.ndjson"

        /** The transcripts the drift loop promotes, under `transcripts`. */
        val TRANSCRIPTS: Set<String> = setOf("contract-hello.jsonl", "session-id.jsonl")

        val CORE: Set<String> = setOf("assistant", "user", "result", "stream_event", "control_request", "control_response")
    }
}

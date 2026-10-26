package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.io.path.readLines
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `an image block without a base64 source stays Other`() {
        val blocks = listOf(
            """{"type":"image"}""",
            """{"type":"image","source":{"type":"url","url":"x"}}""",
            """{"type":"image","source":{"type":"base64","media_type":"image/png"}}""",
            """{"type":"image","source":{"type":"base64","data":"AA"}}""",
        )
        for (block in blocks) {
            val user = assertIs<CliMessage.User>(Ndjson.decode("""{"type":"user","message":{"content":[$block]}}"""), block)
            assertEquals("image", assertIs<ContentBlock.Other>(user.content.single(), block).type)
        }
    }

    @Test
    fun `a result without context windows decodes an empty map`() {
        val results = listOf(
            """{"type":"result","subtype":"success","modelUsage":{"m":{"inputTokens":1}}}""",
            """{"type":"result","subtype":"success"}""",
            """{"type":"result","subtype":"success","modelUsage":{"m":7,"n":[1],"o":{"contextWindow":"200000"}}}""",
            """{"type":"result","subtype":"success","modelUsage":[{"contextWindow":200000}]}""",
        )
        for (line in results) {
            assertEquals(emptyMap(), assertIs<CliMessage.Result>(Ndjson.decode(line), line).contextWindows, line)
        }
        val mixed = Ndjson.decode("""{"type":"result","subtype":"success","modelUsage":{"m":7,"n":{"contextWindow":200000}}}""")
        val result = assertIs<CliMessage.Result>(mixed)
        assertEquals(mapOf("n" to 200_000L), result.contextWindows)
        assertEquals(setOf("m", "n"), result.modelUsage)
    }

    @Test
    fun `new optional fields read as null or false when absent or mistyped`() {
        val user = assertIs<CliMessage.User>(
            Ndjson.decode(
                """{"type":"user","message":{"content":[]},"tool_use_result":null,"tool_result_meta":[{"permission_decision":{}},""" +
                    """{"id":7},5,{"id":"t1","permission_decision":"x","non_execution_kind":3}],"agent_id":5,"subagent_type":[],""" +
                    """"task_description":{},"timestamp":"yesterday"}""",
            ),
        )
        assertNull(user.toolUseResult)
        assertEquals(listOf(ToolResultMeta("t1", decision = null, source = null, reasonType = null, nonExecutionKind = null)), user.toolResultMeta)
        assertEquals(listOf(null, null, null, null), listOf(user.agentId, user.subagentType, user.taskDescription, user.timestampMillis))

        // On disk, one permissionDecision applies to every tool_result block of the line; a line without one has no meta.
        val disk = assertIs<CliMessage.User>(
            Ndjson.decode(
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"a"},{"type":"text","text":"x"},""" +
                    """{"type":"tool_result","tool_use_id":"b"}]},"permissionDecision":{"decision":"accept","reasonType":"mode"}}""",
            ),
        )
        assertEquals(listOf("a", "b"), disk.toolResultMeta.map { it.id })
        assertTrue(disk.toolResultMeta.all { it.decision == "accept" && it.reasonType == "mode" && it.nonExecutionKind == null })
        val plain = assertIs<CliMessage.User>(Ndjson.decode("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"a"}]}}"""))
        assertTrue(plain.toolResultMeta.isEmpty())

        val assistant = assertIs<CliMessage.Assistant>(
            Ndjson.decode("""{"type":"assistant","message":{"content":[]},"api_error_status":"404","thinking_duration_ms":"1","agent_id":1}"""),
        )
        assertEquals(listOf(null, null, null), listOf(assistant.apiErrorStatus, assistant.thinkingDurationMs, assistant.agentId))

        val ask = assertIs<InboundRequest.CanUseTool>(
            assertIs<CliMessage.ControlRequest>(
                Ndjson.decode(
                    """{"type":"control_request","request_id":"r","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{},""" +
                        """"requires_user_interaction":"yes","decision_reason_type":1}}""",
                ),
            ).request,
        )
        assertEquals(false, ask.requiresUserInteraction)
        assertNull(ask.decisionReasonType)

        val started = assertIs<CliMessage.TaskStarted>(Ndjson.decode("""{"type":"system","subtype":"task_started","task_id":"t","is_backgrounded":"no"}"""))
        assertEquals(false, started.isBackgrounded)
        val progress = assertIs<CliMessage.TaskProgress>(Ndjson.decode("""{"type":"system","subtype":"task_progress","task_id":"t","usage":{"tool_uses":"1"}}"""))
        assertEquals(TaskUsage(totalTokens = null, toolUses = null, durationMs = null), progress.usage)
        assertNull(assertIs<CliMessage.TaskUpdated>(Ndjson.decode("""{"type":"system","subtype":"task_updated","task_id":"t","patch":7}""")).status)
        for (subtype in listOf("task_started", "task_progress", "task_updated", "task_notification")) {
            assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"system","subtype":"$subtype","task_id":3}"""), subtype)
        }
        assertIs<CliMessage.Unknown>(Ndjson.decode("""{"type":"system","subtype":"thinking_tokens","estimated_tokens":"1"}"""))

        val start = Ndjson.decode("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m","usage":{"cache_read_input_tokens":5}}}}""")
        assertEquals(StreamEvent.MessageStart("m", model = null, thinkingDisplay = null, inputTokens = 5L), assertIs<CliMessage.StreamEvent>(start).event)
        val bare = Ndjson.decode("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m"}},"thinking_display":3}""")
        assertEquals(StreamEvent.MessageStart("m", model = null, thinkingDisplay = null, inputTokens = null), assertIs<CliMessage.StreamEvent>(bare).event)

        val result = assertIs<CliMessage.Result>(
            Ndjson.decode("""{"type":"result","subtype":"success","permission_denials":[{"tool_use_id":"x"},5,{"tool_name":"Bash"}],"usage":7}"""),
        )
        assertEquals(listOf(PermissionDenial("Bash", toolUseId = null)), result.permissionDenials)
        assertNull(result.outputTokens)
    }

    @Test
    fun `truncated and mutated fixture lines never throw`() {
        val random = Random(42)
        val transcripts = Fixtures.transcriptNames()
        assertTrue(transcripts.isNotEmpty(), "no transcript fixtures")
        val lines = Fixtures.names().flatMap(Fixtures::lines) +
            transcripts.flatMap { name -> Fixtures.transcript(name).readLines().filter { it.isNotBlank() } }
        for (line in lines) {
            Ndjson.decode(line)
            repeat(20) {
                val cut = line.substring(0, random.nextInt(line.length + 1))
                Ndjson.decode(cut)
                val chars = line.toCharArray()
                chars[random.nextInt(chars.size)] = "{}[]\",:0an".random(random)
                Ndjson.decode(String(chars))
            }
        }
    }

    /** Line [line] (1-based) of fixture [name] with [old], which must occur there exactly once, replaced by [new]. */
    private fun mutated(name: String, line: Int, old: String, new: String): String {
        val text = Fixtures.lines(name)[line - 1]
        assertEquals(1, text.split(old).size - 1, "$name:$line must hold $old once")
        return text.replace(old, new)
    }

    private fun payloadOf(line: String): JsonObject? = assertIs<CliMessage.ControlResponse>(Ndjson.decode(line)).payload

    private fun mcpServers(line: String): List<McpServerInfo> = assertNotNull(ControlPayloads.mcpStatus(payloadOf(line))).servers

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `mutated mcp shapes degrade, never throw`() {
        val numbered = mcpServers(mutated("mcp-status", 18, "\"name\":\"echo\",\"status\":\"connected\"", "\"name\":\"echo\",\"status\":7"))
        assertEquals(Triple("echo", McpStatus.OTHER, ""), numbered.first().let { Triple(it.name, it.status, it.statusText) })
        val unknown = mcpServers(mutated("mcp-status", 18, "\"name\":\"echo\",\"status\":\"connected\"", "\"name\":\"echo\",\"status\":\"connecting\""))
        assertEquals(McpStatus.OTHER to "connecting", unknown.first().let { it.status to it.statusText })

        val nameless = mcpServers(mutated("mcp-status", 18, "{\"name\":\"broken\",", "{"))
        assertEquals(listOf("echo", "authy"), nameless.map { it.name })
        val thinNameless = assertIs<CliMessage.SystemInit>(
            Ndjson.decode(mutated("mcp-status", 6, "{\"name\":\"broken\",\"status\":\"failed\",", "{\"status\":\"failed\",")),
        )
        assertEquals(listOf("echo", "authy"), thinNameless.mcpServers.map { it.name })
        val thinNumbered = assertIs<CliMessage.SystemInit>(Ndjson.decode(mutated("mcp-status", 6, "\"status\":\"failed\"", "\"status\":7")))
        assertEquals(McpStatus.OTHER to "", thinNumbered.mcpServers[1].let { it.status to it.statusText })

        val bare = mutated(
            "mcp-slash",
            1,
            "{\"name\":\"mcp\",\"description\":\"Manage MCP servers\",\"argumentHint\":\"[reconnect|enable|disable [<server>|all]]\",",
            "{\"name\":\"mcp\",",
        )
        val commands = assertNotNull(ControlPayloads.initialize(payloadOf(bare))).commands
        val mcp = commands.single { it.name == "mcp" }
        assertEquals("" to "", mcp.description to mcp.argumentHint)
        assertTrue(mcp.builtin)
        val withoutHello = assertNotNull(ControlPayloads.initialize(payloadOf(mutated("mcp-slash", 1, "{\"name\":\"hello\",", "{")))).commands
        assertEquals(72, withoutHello.size)

        val init = assertIs<CliMessage.SystemInit>(Ndjson.decode(mutated("mcp-slash", 4, "\"slash_commands\":[\"actions\",", "\"slash_commands\":[7,{},\"actions\",")))
        assertEquals(73, init.slashCommands.size)
    }

    @Test
    fun `a mistyped config or tools entry keeps the server`() {
        val echoConfig = "\"config\":{\"type\":\"stdio\",\"command\":\"/usr/bin/python3\",\"args\":[\"-I\",\"<path>\"]}"
        val configless = mcpServers(mutated("mcp-status", 18, echoConfig, "\"config\":\"x\"")).first()
        assertEquals("echo", configless.name)
        assertNull(configless.config)
        assertEquals(listOf(McpTool("echo")), configless.tools)

        val toolless = mcpServers(mutated("mcp-status", 18, "\"tools\":[{\"name\":\"echo\",\"annotations\":{}}]", "\"tools\":{}")).first()
        assertEquals("echo", toolless.name)
        assertNull(toolless.tools)
        assertEquals("0.1.0", toolless.serverInfo?.version)

        val mixedTools = mcpServers(mutated("mcp-status", 18, "\"tools\":[{\"name\":\"echo\",", "\"tools\":[7,{\"name\":3},{\"name\":\"echo\",")).first()
        assertEquals(listOf(McpTool("echo")), mixedTools.tools)

        val argless = mcpServers(mutated("mcp-status", 18, "\"args\":[\"-I\",\"<path>\"]", "\"args\":\"x\"")).first()
        assertEquals(McpConfig(type = "stdio", command = "/usr/bin/python3", args = emptyList(), url = null), argless.config)

        val peerless = mcpServers(mutated("mcp-status", 18, "\"serverInfo\":{\"name\":\"echo\",\"version\":\"0.1.0\"}", "\"serverInfo\":\"x\"")).first()
        assertEquals("echo", peerless.name)
        assertNull(peerless.serverInfo)
    }

    // unrecorded: no fixture has a <local-command-stderr> wrapper; the CLI's on-disk lines name it (TranscriptReader).
    @Test
    fun `a stderr wrapper unwraps like stdout`() {
        val stdout = "<local-command-stdout>/help isn't available in this environment.</local-command-stdout>"
        val stderr = Ndjson.decode(mutated("mcp-slash", 17, stdout, "<local-command-stderr>oops</local-command-stderr>"))
        assertEquals("oops", assertIs<CliMessage.Assistant>(stderr).localCommand?.stdout)
        val plain = Ndjson.decode(mutated("mcp-slash", 17, stdout, "plain text"))
        assertEquals("plain text", assertIs<CliMessage.Assistant>(plain).localCommand?.stdout)
    }

    @Test
    fun `mcp fields without their shape read as null, empty or skipped`() {
        assertNull(ControlPayloads.mcpStatus(null))
        assertNull(ControlPayloads.mcpStatus(json("""{"mcpServers":{}}""")))
        assertNull(ControlPayloads.initialize(null))
        assertNull(ControlPayloads.initialize(json("""{"commands":"x"}""")))
        assertNull(ControlPayloads.mcpAuthenticate(null))
        assertNull(ControlPayloads.mcpAuthenticate(json("""{"other":1}""")))
        assertEquals(
            McpAuthStart(authUrl = null, callbackPort = 1),
            ControlPayloads.mcpAuthenticate(json("""{"authUrl":5,"callbackPort":1,"requiresUserAction":"yes"}""")),
        )
        assertNull(ControlPayloads.mcpSetServers(null))
        assertNull(ControlPayloads.mcpSetServers(json("""{"errors":{}}""")))
        assertEquals(
            McpSetServersResponse(added = listOf("a"), removed = emptyList(), errors = emptyMap()),
            ControlPayloads.mcpSetServers(json("""{"added":["a",7],"errors":[]}""")),
        )
        assertEquals(McpStatus.OTHER, McpStatus.fromWire(null))
        assertEquals(McpStatus.OTHER, McpStatus.fromWire(""))

        val init = assertIs<CliMessage.SystemInit>(
            Ndjson.decode("""{"type":"system","subtype":"init","model":"m","mcp_servers":5,"slash_commands":{},"terminal_slash_commands":[1]}"""),
        )
        assertEquals(Triple(emptyList(), emptyList(), emptyList()), Triple(init.mcpServers, init.slashCommands, init.terminalSlashCommands))

        val assistant = assertIs<CliMessage.Assistant>(
            Ndjson.decode("""{"type":"assistant","message":{"content":[]},"tool_use_meta":[{"display_name":"x"},5,{"id":"t","tool_name":7}]}"""),
        )
        assertEquals(listOf(ToolUseMeta("t", displayName = null, serverDisplayName = null, toolName = null)), assistant.toolUseMeta)
        assertNull(assistant.localCommand)
        val mistypedRun = assertIs<CliMessage.Assistant>(
            Ndjson.decode("""{"type":"assistant","message":{"content":[]},"local_command_run":5,"local_command_outcome":{"kind":3}}"""),
        )
        assertEquals(LocalCommand(command = null, args = null, outcomeKind = null, stdout = null), mistypedRun.localCommand)
        val nullRun = assertIs<CliMessage.Assistant>(Ndjson.decode("""{"type":"assistant","message":{"content":[]},"local_command_run":null}"""))
        assertNull(nullRun.localCommand)

        assertNull(assertIs<CliMessage.Result>(Ndjson.decode("""{"type":"result","subtype":"success","local_command":5}""")).localCommand)

        val ask = assertIs<InboundRequest.CanUseTool>(
            assertIs<CliMessage.ControlRequest>(
                Ndjson.decode(
                    """{"type":"control_request","request_id":"r","request":{"subtype":"can_use_tool","tool_name":"mcp__x__y","input":{},""" +
                        """"mcp_server":{"source":"dynamic"},"server_policy_ask_only":"yes"}}""",
                ),
            ).request,
        )
        assertNull(ask.mcpServer)
        assertEquals(false, ask.serverPolicyAskOnly)
    }

    /** [payload] with [key] set to the JSON [value]. */
    private fun with(payload: JsonObject?, key: String, value: String): JsonObject =
        JsonObject(assertNotNull(payload) + (key to Json.parseToJsonElement(value)))

    /** The `system/init` of fixture [name] line [line] with [key] set to the JSON [value]. */
    private fun initWith(name: String, line: Int, key: String, value: String): CliMessage.SystemInit =
        assertIs<CliMessage.SystemInit>(Ndjson.decode(Json.encodeToString(JsonObject.serializer(), with(json(Fixtures.lines(name)[line - 1]), key, value))))

    @Test
    fun `mutated parity shapes degrade, never throw`() {
        val context = payloadOf(Fixtures.lines("parity-context")[16])
        val noId = assertNotNull(ControlPayloads.contextUsage(payloadOf(mutated("parity-context", 17, "{\"id\":\"system_prompt\",", "{"))))
        assertEquals(null to "System prompt", noId.categories[0].let { it.id to it.name })
        val noKind = assertNotNull(
            ControlPayloads.contextUsage(payloadOf(mutated("parity-context", 17, "\"color\":\"promptBorder\",\"kind\":\"used\"", "\"color\":\"promptBorder\""))),
        )
        assertNull(noKind.categories[0].kind)
        assertEquals(8, noKind.categories.size)
        for (tokens in listOf("\"12\"", "-1", "null")) {
            val skipped = assertNotNull(
                ControlPayloads.contextUsage(
                    payloadOf(mutated("parity-context", 17, "\"name\":\"Messages\",\"tokens\":6448", "\"name\":\"Messages\",\"tokens\":$tokens")),
                ),
            )
            assertEquals(7, skipped.categories.size, tokens)
            assertTrue(skipped.categories.none { it.id == "messages" }, tokens)
        }
        assertNull(ControlPayloads.contextUsage(with(context, "categories", "{}")))
        assertTrue(assertNotNull(ControlPayloads.contextUsage(with(context, "mcpTools", """[{"name":"x"}]"""))).mcpTools.isEmpty())
        assertTrue(assertNotNull(ControlPayloads.contextUsage(with(context, "skills", "[]"))).skills.isEmpty())
        assertTrue(assertNotNull(ControlPayloads.contextUsage(with(context, "skills", """{"skillFrontmatter":7}"""))).skills.isEmpty())
        assertNull(ControlPayloads.contextUsage(null))

        val usage = payloadOf(Fixtures.lines("parity-usage")[16])
        for (pct in listOf("140", "\"80\"", "-1")) {
            val day = assertNotNull(
                ControlPayloads.usage(payloadOf(mutated("parity-usage", 17, "\"pct\":80,\"count\":7726", "\"pct\":$pct,\"count\":7726"))),
            ).history!!.day!!
            assertTrue(day.behaviors.none { it.key == "workflow" }, pct)
            assertEquals(4, day.behaviors.size, pct)
        }
        val rounded = assertNotNull(ControlPayloads.usage(payloadOf(mutated("parity-usage", 17, "\"pct\":80,\"count\":7726", "\"pct\":99.6,\"count\":7726"))))
        assertEquals(100, rounded.history!!.day!!.behaviors.single { it.key == "workflow" }.pct)
        assertNull(assertNotNull(ControlPayloads.usage(with(usage, "behaviors", "7"))).history)
        val noSession = assertNotNull(ControlPayloads.usage(with(usage, "session", "\"x\"")))
        assertNull(noSession.session)
        assertNotNull(noSession.history)
        assertNull(ControlPayloads.usage(null))
        val costText = assertNotNull(
            ControlPayloads.usage(payloadOf(mutated("parity-usage", 16, "\"costUSD\":0.152182", "\"costUSD\":\"0.15\""))),
        ).session!!.models
        assertEquals("claude-sonnet-5-5", costText[1].first)
        assertNull(costText[1].second.costUsd)
        assertEquals(38037L, costText[1].second.cacheWrite)
        val searchText = assertNotNull(
            ControlPayloads.usage(
                payloadOf(mutated("parity-usage", 16, "\"webSearchRequests\":0,\"costUSD\":0.152182", "\"webSearchRequests\":\"2\",\"costUSD\":0.152182")),
            ),
        ).session!!.models
        assertNull(searchText[1].second.webSearches)
        assertEquals(0.152182, searchText[1].second.costUsd)

        val spaced = assertIs<CliMessage.SystemInit>(Ndjson.decode(mutated("parity-add-dir", 5, "\"version\":\"0.1.0\"", "\"version\":\"1 0\"")))
        assertEquals("probe-kit" to null, spaced.plugins[0].let { it.name to it.version })
        val beta = assertIs<CliMessage.SystemInit>(Ndjson.decode(mutated("parity-add-dir", 5, "\"version\":\"0.1.0\"", "\"version\":\"0.1.0-beta+3\"")))
        assertEquals("0.1.0-beta+3", beta.plugins[0].version)
        assertTrue(initWith("parity-add-dir", 5, "plugins", "{}").plugins.isEmpty())
        assertEquals(listOf("b"), initWith("parity-add-dir", 5, "plugins", """[{"path":"x"},7,{"name":"b","version":3}]""").plugins.map { it.name })
        assertEquals(listOf("/x"), initWith("parity-add-dir", 5, "additional_directories", """[1,"/x"]""").additionalDirectories)
        assertTrue(initWith("parity-add-dir", 5, "skills", "{}").skills.isEmpty())
        assertEquals(listOf("x"), initWith("parity-add-dir", 5, "skills", """[1,"x"]""").skills)
    }

    @Test
    fun `every control answer of every fixture decodes without throwing`() {
        var answers = 0
        for (name in Fixtures.names()) {
            for (line in Fixtures.lines(name)) {
                val answer = Ndjson.decode(line) as? CliMessage.ControlResponse ?: continue
                ControlPayloads.contextUsage(answer.payload)
                ControlPayloads.usage(answer.payload)
                answers++
            }
        }
        assertTrue(answers > 0, "no control answers in the fixtures")
    }

    @Test
    fun `docs-only agents and MCP server instructions decode in the SDK's shape`() {
        // Docs only (sdk.d.ts): no recording has either group, so a wrong key name would hide both on a machine that has them.
        val context = with(
            with(
                payloadOf(Fixtures.lines("parity-context")[16]),
                "agents",
                """[{"agentType":"reviewer","source":"projectSettings","tokens":919},{"agentType":"x"}]""",
            ),
            "mcpInstructions",
            """[{"serverName":"echo","tokens":26},{"serverName":"docs","tokens":22},{"tokens":5}]""",
        )
        val breakdown = assertNotNull(ControlPayloads.contextUsage(context))
        assertEquals(listOf(AgentUsage("reviewer", "projectSettings", 919)), breakdown.agents)
        assertEquals(listOf(McpInstructionsUsage("echo", 26), McpInstructionsUsage("docs", 22)), breakdown.mcpInstructions)
    }
}

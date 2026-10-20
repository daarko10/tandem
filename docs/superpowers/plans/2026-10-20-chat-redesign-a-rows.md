# Chat Redesign Slice A (Rows) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the four chat complaints: typed tool rows with grouping and states, a working indicator and turn footer, visible thinking rows, and intent-based autoscroll, on a pure `chat/` presentation layer rendered by a keyed `LazyColumn`.

**Architecture:** T1–T3 widen `protocol/` and `session/` (the 5.1 fields, reducer turn state, one `answer()` path, the `--thinking-display summarized` spawn flag). T4–T6 add the Compose-free package `chat/` (row model, labels, states, bodies, grouping, working indicator, cards, follow state), Kover-gated like `protocol/` and `session/`. T7–T9 replace the Markdown-flattening `ui/` chat with a presenter over `ChatRowsBuilder`, row composables, the working and thinking rows, the composer keys and the autoscroll connection. Today's `PermissionPrompt` stays below the list until slice B (T10). Run the tasks strictly in order T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9. Spec 12's parallel pairs do not hold for this plan: T3 needs T2's `atMillis` stamps, T5 and T6 both edit `ChatRowsBuilder.kt` and `ChatRowsBuilderTest.kt`, and T8 and T9 both edit `ChatPanel.kt` and `ChatScreen.kt`.

**Tech Stack:** Kotlin 2.4.20, IntelliJ Platform 2026.1.5 (Jewel 0.37.0, build 261.27258.48), kotlinx.serialization.json tree API, the platform's kotlinx.collections.immutable (not bundled, hard rule 7), the platform's kotlinx.coroutines, JUnit 5 (+ vintage for the JUnit 4 classes), IDE Starter + UI Driver, the Python fake CLI.

**Spec:** `docs/superpowers/specs/2026-10-19-chat-redesign-design.md` (sections 4, 5, 10, 12, appendix A are the contract). Reference: `docs/superpowers/research/2026-10-18-claude-code-desktop-ui-reference.md`; probes: `docs/superpowers/research/2026-10-18-protocol-probes.md`.

**Dependencies:** T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9, sequential: T3 stamps T2's `SessionEvent` fields; T5/T6 share `ChatRowsBuilder(.kt/Test)` and T8/T9 share `ChatPanel.kt`/`ChatScreen.kt`. Each task's "Depends on:" line names what it consumes.

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

## Global Constraints

- CLAUDE.md hard rules, verbatim:
  1. **Never block the EDT.** No process I/O, file I/O, JSON parsing or waiting on the CLI from the UI thread. Use the platform coroutine scopes (`platform-coroutines-structured-concurrency` skill).
  2. **No Node, no Agent SDK at runtime.** We spawn the user's `claude` binary directly. Do not add a sidecar.
  3. **Never touch credentials.** Auth belongs to the CLI. Do not read `~/.claude/.credentials*`, keychains or API keys, and never offer a login flow.
  4. **Edits go through the IDE, not the disk.** Claude's built-in Edit/Write tools are disabled; our MCP edit tools apply changes with `WriteCommandAction` on the `Document`, so undo, unsaved buffers and local history work. Every edit shows a native diff before it lands, unless the user picked auto-accept.
  5. **Never fail silently.** If the CLI falls back to another model, rejects a model, retries, or errors, the user sees it in the chat. Compare the model in the `system/init` event (and fallback events) with what the user picked.
  6. **Every protocol change gets a fixture test.** Recorded transcripts live in `src/test/resources/fixtures/`. Unknown message types must parse to `Unknown(raw)`, never throw.
  7. **Do not bundle kotlinx.coroutines or the Kotlin stdlib.** The platform provides both.
  8. **Do not call the product "Claude Code"**, in the plugin name, ID or UI. Use "Tandem" and say it works with Claude Code.
- CLAUDE.md Conventions, verbatim: "Validate everything that comes from the CLI; it is an external process that changes weekly." and "Never log prompt contents, file contents or secrets. Log message types and ids."
- Layers: `protocol/` imports nothing from IntelliJ; `session/` only the JDK, kotlinx.coroutines, kotlinx.serialization.json, kotlinx.collections.immutable and `com.intellij.openapi.diagnostic`; `chat/` (new, `dev.daarkolabs.tandem.chat`, `src/main/kotlin/dev/daarkolabs/tandem/chat/`) the JDK, kotlinx.collections.immutable, kotlinx.serialization.json and `session/`/`protocol/` types: no Compose, no Jewel, no IntelliJ. Row types hold only `String`, `Int`, `Long`, `Boolean`, enums and `ImmutableList`s (spec 5.3).
- Jewel 0.37.0 as bundled in 2026.1.5; nothing newer (`verifyPlugin` runs against every supported release). Every API named in spec 6 exists in the bundled jars (`scratchpad/understand/jewel.md`); check any other with `javap` before use.
- Theme tokens only (spec 4.2), read in composition or `remember(JewelTheme.instanceUuid)`; the only literal colour in the column is `TandemColors.spark = Color(0xFFD97757)`.
- Branch `feat/chat-redesign` → `feat/sessions-and-pickers`. Conventional Commits; `git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>"`; no trailers; stage by path. No personal data in committed files.
- Gradle: every command carries `--init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (the absolute path, so it works from a git worktree too). One class: `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.<pkg>.<Class>'`; full suite: `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts`; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` adds `koverVerify` (90 % lines per package of `protocol/`, `session/` and, from T4 on, `chat/`); IDE tests: `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (1.5–3 min each; T7, T8 and T9 run them). Every task ends with `check` green and one or two commits.
- Commands, CLAUDE.md's `## Commands` list verbatim:
  - `./gradlew test` unit, fixture and headless platform tests (no real `claude`; a fake CLI replays fixtures).
  - `./gradlew koverLog` line coverage of `protocol/` and `session/` per package after the tests (Kover, build-time only); `koverHtmlReport` writes `build/reports/kover/html/`. `./gradlew check` runs `koverVerify`, which fails under 90%.
  - `xvfb-run ./gradlew integrationTest` IDE Starter + UI Driver tests: install the built ZIP in a real IDE, type prompts, answer permission prompts, press Stop, use the pickers and run two sessions from the sidebar against a fake CLI (`src/test/resources/fake-claude.py`). Slow; catch plugin-classloader and UI problems the platform tests cannot.
  - Test hooks (system properties): `tandem.claudePath` is the `claude` to run (the test tasks point it at the fake CLI); `tandem.configDir` replaces `$CLAUDE_CONFIG_DIR` / `~/.claude` when looking for transcripts.
  - The fake CLI (see its docstring) reads `TANDEM_FAKE_FIXTURE` (one fixture) or `TANDEM_FAKE_FIXTURES` (comma list, one per spawn), `TANDEM_FAKE_LOG` (its argv and its `CLAUDE*` and `NODE_OPTIONS` environment variables with secrets redacted, then every frame the host writes), `TANDEM_FAKE_SESSION_ID`, `TANDEM_FAKE_DELAY_MS` and `TANDEM_FAKE_GATE_FILE` (holds each `control_request` back until the file exists).
  - `./gradlew runIde` sandbox IDE with the plugin. Use it to check feel, freezes and focus; agents cannot judge these.
  - `./gradlew verifyPlugin` Plugin Verifier against every supported IDE release.
  - `./gradlew buildPlugin` installable ZIP in `build/distributions/`.
  - `scripts/record-fixture.sh <name> "<prompt>"` records a real CLI transcript as a test fixture. Needs a logged-in `claude`.
  - `cd scripts && python3 -m unittest` tests the recording scripts (the redactor's safety net, `copy_transcript.py`, `drive_cli.py`'s control calls) without Gradle or a real `claude`.
  In this sandbox every Gradle command also takes `--init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts`; from T4 on, `koverLog`/`koverVerify` also cover `chat/`.
- Deviations from spec 5 (not contract breaks): `StreamEvent.MessageStart.messageId` stays `String` (5.1: `String?`): a `message_start` without an id decodes to `StreamEvent.Other`, as today. `SessionEvent.Ended(detail: String, isError: Boolean, endedByHost: Boolean = false, atMillis: Long = 0L)` keeps today's non-null `detail` and the `endedByHost` default (5.2: `detail: String?`, no default) so existing callers keep compiling. `ToolStates.of` checks `pending → WAITING` before `input == null → PENDING` (5.4 lists PENDING first): a request that arrives before the complete `tool_use` line (unrecorded) yields `WAITING`, which draws spec 8's "PENDING in text.warning" through the WAITING state, and a WAITING call with `input == null` labels like PENDING (`Reading…`, `Running…`; T4). Write requests always title `Create` (4.12's update case is unknowable at request time; T6). A cut `ToolBody.Diff` (`truncated`) ends with a bare `…` line, not 4.20's `… N more lines`: 5.3's `Diff(path, hunks, truncated)` carries no count of the lines left out (T7; 4.20's bodies row lists it). `Modifier.bottomFade(height)` reads `height` as the fade band: the mask is `(1 - height / boxHeight) to Color.Black, 1f to Color.Transparent`, not 4.3's fixed `0.7f to Color.Black`, which leaves `height` unused; it only fades, and the caller clamps and clips first (`heightIn(max = 160.dp).clipToBounds().bottomFade(60.dp)`), as 4.7's 3-line fade and C's T14 bubble need (T7). A body that cannot be cut by line (`ToolBody.Markdown`, `Links`) clamps by its measured 240 dp alone, since drawing fewer source lines is impossible (T7). Test names that Kotlin cannot compile follow the name rule below. Extensions beyond spec 5: `CliMessage.Assistant.apiErrorStatus: Int?` (T1; 5.2's `lastError` reads `api_error_status`, so hard rule 6 needs it decoded and fixture-tested), the `PendingPermission` field defaults (T2), `ToolCall.subject: String? = null` (T4, for `Grouping.sentence`), the two `ToolLabels.agentProgress` overloads (T4: 4.8's `Done (…)` sub-line and live progress line, for which 5.4 names no function) and the top-level `Text.kt` formatters `formatTokens`/`formatElapsed`/`formatDuration`/`displayName`/`conjugate` (T4: labels need them before T6's `WorkingIndicator` exists, which then delegates to them), `Grouping.stats`/`Grouping.state` (T5), `Cards.sourceLine` (T6), `ChatPresenter(scope, state: StateFlow<ChatState>, options, parse)` in place of 5.6's `handle: SessionHandle`, with an injectable `parse` (T7: the spike and the tests feed a `MutableStateFlow<ChatState>`, and the test observes the parse thread), `composerKey(key, type, shift, alt, ctrlOrMeta, busy, cardOpen)` in place of 4.15's `composerKey(event, busy, cardOpen)` (T9: a pure mapping, so tests build no AWT event), the `scrollKey` / `ScrollAction` (with `FOCUS_COMPOSER`) / `followEvent` names (T9: 4.16 and 4.18 name the keys but no function), and `picker("Model", "Default model")` in place of 10.6's `<the init model's label>` (T9: the picker shows the requested model, so the init model is no longer asserted by that locator).
- Fixtures: `name:line` is the 1-based line of `src/test/resources/fixtures/<name>.ndjson` (`permission.ndjson:21` names its file); `transcripts/<name>.jsonl` is its on-disk twin. A test reads a line with `Ndjson.decode(Fixtures.lines(name)[line - 1])`. The redactor makes every path `<path>`, so file names in fixture-backed assertions are literally `<path>`. Request and tool-use ids are placeholders (`<request_id-27>`, `<id-12>`).
- Test names are the spec's, verbatim except for the Kotlin name rule below (backtick JUnit names; the camelCase names of spec 10.5 as given). New classes use JUnit 5 (`org.junit.jupiter.api.Test`); additions to the JUnit 4 classes (`FixtureDecodeTest`, `HostMessagesTest`, `RobustnessTest`, `ReducerTest`, `ClaudeSessionTest`, `PermissionUpdatesTest`: `org.junit.Test`) keep JUnit 4; `SessionManagerTest`, `TandemSettingsTest` and `TandemSessionsTest` are already JUnit 5. A reloaded transcript in a test is `runBlocking { TranscriptReader.read(Fixtures.transcript(name)) }.state` (`session/transcript/TranscriptReader.kt`).
- Kotlin name rule: Kotlin/JVM rejects `.` `;` `[` `]` `/` `<` `>` `:` `\` in backtick names (compile error "Name contains illegal characters"). Where a spec name contains one, the Kotlin name replaces `: ` with ` - `, `<path>` with `path` and `.` with ` dot `, and a `// spec: <verbatim name>` comment sits above the test. The names below are already rewritten this way.
- Exact values: `--thinking-display summarized` right after `--include-partial-messages`; `initialize` = `{"subtype":"initialize","hooks":null,"forwardSubagentText":true}`; deny messages `The user denied this request.`, `cancelled`, `No, keep planning`; `RowGap` NONE 0 / ROW 6 / SECTION 16 / AFTER_BUBBLE 18 / BEFORE_TEXT 20 / TURN 24; column cap 768 dp; list `contentPadding` 16 dp horizontal, 12 dp vertical; bodies clamp at 20 lines, cap 400 lines / 50,000 characters; `middleTruncate` 48 for names, 60 for commands; not-responding 60_000 ms; spark step 120 ms; `Grouping.MAX_NAMES = 2`; 90 verbs (appendix A).

## Review Focus

1. A `user` line carrying two `tool_result` blocks and one `tool_use_result` (unrecorded): neither row may receive the other's object → `result == null` on both; pinned in T2 (`a line with two tool results attaches the object to neither`).
2. A tool call interrupted while its input streamed (`input == null`, `partialInput` a JSON fragment) must label from the name alone and never parse the fragment; pinned in T4 (`a call interrupted mid-input labels from the name alone` for the label, and `ToolStatesTest` `turn end yields INTERRUPTED` for `ToolStates.of` returning `INTERRUPTED` when `input == null` once the turn has ended).
3. A Bash `description` that is blank (`""`) must fall back to `Running <cmd>` / `Ran <cmd>`, never `Ran `; pinned in T4 (`a blank description falls back to the command`).
4. A group whose Edit result lacks `structuredPatch` (older CLI, spec 8) must sum the remaining stats and show none when no call has any; pinned in T5 (`missing stats are skipped in the sum`).
5. Scroll events in the wrong state (`ReachedBottom` or `Jump` while following, `Appended(0)` while paused) must change nothing; pinned in T6 (`events in the wrong state change nothing`).
6. A `system/model_refusal_fallback` with `retracted_message_uuids` (hand-made, as ReducerTest `refusal fallback retracts the refused reply and warns`; no fixture records one, spec 8) must remove the refused reply's rows and leave no stale cached row (5.4: a retraction drops the whole cache; a stale row would keep showing a retracted reply, against hard rule 5); pinned in T4 (`a retraction drops the cached rows`).

---

### Task T1: protocol-fields

**Depends on:** none.

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/protocol/CliMessage.kt`, `.../protocol/Ndjson.kt`, `.../protocol/HostMessages.kt`, `src/main/kotlin/dev/daarkolabs/tandem/session/Reducer.kt` (`reduceCli` is an exhaustive `when (message)` expression with no `else`: it gains `is CliMessage.ThinkingTokens, is CliMessage.TaskStarted, is CliMessage.TaskProgress, is CliMessage.TaskUpdated, is CliMessage.TaskNotification -> state`; T2 replaces it with the 5.2 rules)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt`, `.../protocol/HostMessagesTest.kt`, `.../protocol/RobustnessTest.kt`

**Interfaces:**
- Produces (spec 5.1; every new field optional, `null` on a type mismatch, never throws; existing fields keep their names):
  ```kotlin
  data class User(…existing…, val toolUseResult: JsonElement?, val toolResultMeta: List<ToolResultMeta>, val agentId: String?, val subagentType: String?, val taskDescription: String?, val timestampMillis: Long?, …)
  data class ToolResultMeta(val id: String, val decision: String?, val source: String?, val reasonType: String?, val nonExecutionKind: String?)
  data class Assistant(…existing…, val apiErrorStatus: Int?, val thinkingDurationMs: Long?, val thinkingDisplay: String?, val agentId: String?, val subagentType: String?, val taskDescription: String?, val timestampMillis: Long?)   // apiErrorStatus: top-level api_error_status (invalid-model:4 → 404); not in spec 5.1, needed by 5.2's lastError
  data class StreamEvent.MessageStart(val messageId: String, val model: String?, val thinkingDisplay: String?, val inputTokens: Long?)   // messageId stays non-null: a message_start without an id is StreamEvent.Other, as today
  data class StreamEvent.MessageDelta(val stopReason: String?, val outputTokens: Int?, val thinkingTokens: Int?)
  data class Delta.Thinking(val thinking: String, val estimatedTokens: Int?)
  data class ThinkingTokens(val estimatedTokens: Int, val estimatedTokensDelta: Int?, val userMessageUuid: String?, override val raw: JsonObject) : CliMessage
  data class TaskStarted(val taskId: String, val toolUseId: String?, val description: String?, val subagentType: String?, val taskType: String?, val isBackgrounded: Boolean, override val raw: JsonObject) : CliMessage
  data class TaskProgress(val taskId: String, val toolUseId: String?, val description: String?, val usage: TaskUsage?, val lastToolName: String?, override val raw: JsonObject) : CliMessage
  data class TaskUpdated(val taskId: String, val status: String?, override val raw: JsonObject) : CliMessage   // status from "patch"."status"
  data class TaskNotification(val taskId: String, val toolUseId: String?, val status: String?, val summary: String?, val usage: TaskUsage?, override val raw: JsonObject) : CliMessage
  data class TaskUsage(val totalTokens: Long?, val toolUses: Int?, val durationMs: Long?)
  data class Result(…existing…, val durationMs: Long?, val durationApiMs: Long?, val outputTokens: Int?, val numTurns: Int?, val permissionDenials: List<PermissionDenial>)
  data class PermissionDenial(val toolName: String, val toolUseId: String?)
  data class InboundRequest.CanUseTool(…existing…, val requiresUserInteraction: Boolean, val decisionReasonType: String?, val agentId: String?)
  fun HostMessages.initialize(requestId: String): JsonObject   // {"subtype":"initialize","hooks":null,"forwardSubagentText":true}
  ```
- Name sets (5.1): `decodeUser` reads `tool_use_result` | `toolUseResult`; `tool_result_meta[]` (stdout) | top-level `permissionDecision` + `toolDenialKind` (disk: one entry per `tool_result` block of the line, `id` = that block's `tool_use_id`); `agent_id` | `agentId`; `subagent_type` | `subagentType`; `task_description` | `taskDescription`; `timestamp` ISO-8601 through `java.time.Instant.parse` (`null` on failure). `decodeAssistant` reads `thinking_duration_ms` | `thinkingDurationMs`, `thinking_display` | `thinkingDisplay`, and the line's top-level `api_error_status` (an `Int`, `null` when absent or mistyped). `MessageStart.thinkingDisplay` = the line's top-level `thinking_display` (the `stream_event` wrapper, not `event.message`), as on deltas. `MessageStart.inputTokens` = `usage.input_tokens + cache_creation_input_tokens + cache_read_input_tokens` (absent fields count 0; `null` when all three are absent). `MessageDelta` reads `usage.output_tokens` and `usage.output_tokens_details.thinking_tokens`; `Result.outputTokens` = `usage.output_tokens`. Boolean flags default `false` when absent or mistyped; a meta entry without a string `id` is skipped; a `task_*` line without a string `task_id` is `Unknown`. `rate_limit_event`, `command_lifecycle`, `session_state_changed`, `ui_invalidate`, `tool_progress` stay `Unknown`.

- [ ] **Step 1: Write the failing tests.** `FixtureDecodeTest`: one `@Test` per 10.1 row, names verbatim under the Kotlin name rule (each with its `// spec:` line), assertions as listed (`L(name, n)` = `Ndjson.decode(Fixtures.lines(name)[n - 1])`; `meta` = `toolResultMeta`):

```kotlin
`ask-single - the question request decodes with requires_user_interaction and the answer result carries tool_use_result`   // L("ask-single",22): (request as CanUseTool).requiresUserInteraction; permissionSuggestions.isEmpty(); input["questions"][0]["options"].size == 2. L(26) as User: toolUseResult is JsonObject containing "answers"; meta[0].decision == "accept"; meta[0].source == "user_temporary"
`ask-deny - a denied question result is an error string with a reject decision`   // :24 toolUseResult == JsonPrimitive("Error: cancelled"); meta[0].nonExecutionKind == "permission-rule"; decision == "reject"; source == "user_reject"
`ask-deny - result lists the denied call`   // :40 Result.permissionDenials.map { it.toolName } == listOf("AskUserQuestion")
`plan-approve - ExitPlanMode carries the plan and the status line names the new mode`   // :88 input["plan"] is a non-blank string, permissionSuggestions.isEmpty(); :90 SystemStatus.permissionMode == PermissionMode.ACCEPT_EDITS
`plan-disallowed - ExitPlanMode with an empty input still decodes as a permission request`   // :173 input.isEmpty(); requiresUserInteraction
`think-display-summarized - thinking deltas carry text and thinking_tokens lines decode`   // :10 ThinkingTokens(estimatedTokens = 1, estimatedTokensDelta = 1); :11 Delta.Thinking("47", estimatedTokens = null); :34 Assistant.thinkingDurationMs == 1991L, thinkingDisplay == "summarized"
`think-display-summarized - message_start names the thinking display`   // :8 MessageStart.thinkingDisplay == "summarized"
`misc-state-emit - message_start input tokens sum the three input fields`   // :9 inputTokens == 31260L (2 + 4720 + 26538) == L(16).raw["first_request_input_tokens"]
`misc-image - message_delta carries thinking tokens`   // :48 MessageDelta.thinkingTokens == 62, outputTokens == 135
`agent-sync - subagent user lines carry subagent_type, task_description and a timestamp`   // :38 subagentType == "general-purpose", taskDescription == "Count lines in seed.txt", timestampMillis == Instant.parse("2026-10-18T20:12:55.307Z").toEpochMilli()
`agent-forwarded - the forwarded assistant line carries a timestamp`   // :38 Assistant.timestampMillis != null
`permission - result carries duration_api_ms and num_turns`   // permission.ndjson:35 durationApiMs == 3384L, numTurns == 2, durationMs == 5223L
`misc-state-action - a default-mode request carries blocked_path and three suggestions`   // :21 blockedPath == "<path>"; permissionSuggestions.map { it["type"].content } == listOf("addRules", "addDirectories", "setMode")
`think-default - empty thinking deltas carry estimated_tokens`   // :10 Delta.Thinking("", 50); :16 the Thinking block's text is empty, thinkingDurationMs == 2143L
`think-summarized - a set_max_thinking_tokens success has no response and the full value is an error`   // :25 isSuccess && payload == null; :73 !isSuccess && error!!.startsWith("set_max_thinking_tokens:")
`tools-read - Read results carry the file view`   // :21 toolUseResult["file"]["numLines"] == 31, meta[0].source == "config", meta[0].reasonType == "mode"; :38 ["file"]["startLine"] == 10; plan-approve:73 meta[0].reasonType == "other"; tools-bash:23 meta[0].source == "user_temporary", reasonType == null
`tools-edit - Edit and Write results carry structuredPatch`   // :44 structuredPatch.size == 1 and its "lines" hold "-line 07 golf" and "+line 07 GOLF"; :61 toolUseResult["type"] == "create"
`tools-bash - a Bash failure is an error string and the request names its reason type`   // :20 decisionReasonType == "subcommandResults"; :39 the ToolResult block isError and toolUseResult is a JsonPrimitive starting "Error: Exit code 3"
`tools-read-unavailable - an unknown tool error has no meta`   // :49 isError; meta.isEmpty()
`agent-sync - task lines decode and subagent lines carry agent ids`   // :37 TaskStarted(taskType == "local_agent", description == "Count lines in seed.txt", isBackgrounded == false, toolUseId == "<id-12>"); :41 TaskProgress(lastToolName == "Bash", usage == TaskUsage(21510, 1, 2006), description == "Running wc -l seed.txt && tail -c1 seed.txt | xxd"); :48 TaskUpdated(status == "completed"); :49 TaskNotification(summary != null, usage == TaskUsage(21826, 2, 5826)); :43 CanUseTool.agentId != null; :38 User.agentId == the taskId of :37; :47 User.toolUseResult == null && meta[0].reasonType == "subcommandResults"
`agent-bash-task - a Bash task line decodes with task_type local_bash`   // :21 TaskStarted(taskType == "local_bash", subagentType == null); :22 TaskNotification(usage == null)
`agent-forwarded - the forwarded subagent text is an assistant line with agent_id`   // :38 agentId != null, parentToolUseId != null, content == listOf(ContentBlock.Text("7")), subagentType == "general-purpose", taskDescription == "Count lines in seed.txt"
`misc-image - an image result is a block array and an image tool_use_result`   // :26 ToolResult.content is a JsonArray holding a block of type "image"; toolUseResult["type"] == "image"; ["file"]["dimensions"] width == 8, height == 8
`misc-web - ToolSearch, WebFetch and WebSearch results decode`   // :17 content array holds a "tool_reference" block; :34 toolUseResult["result"] is a string; :78 toolUseResult["results"] is an array whose first element has a "content" array
`permission - message_delta usage and result durations decode`   // :22 MessageDelta.outputTokens != null; :35 Result.durationMs != null, outputTokens == 90, permissionDenials.isEmpty()
`misc-state-emit - session_state_changed and rate_limit_event stay Unknown`   // :3 Unknown(subtype == "session_state_changed"); :5 Unknown(type == "rate_limit_event")
`invalid-model - the assistant error line carries api_error_status`   // L("invalid-model", 4) as Assistant: apiErrorStatus == 404, error == "model_not_found" (extra test beyond 10.1: the field T2's lastError reads)
`transcripts - on-disk tool results and thinking fields decode with their camelCase names`   // transcripts/ask-deny.jsonl line 20 as User: toolUseResult != null, meta[0].decision == "reject", meta[0].nonExecutionKind != null; tools-edit.jsonl: some User whose toolUseResult holds "structuredPatch"; think-default.jsonl: some Assistant with thinkingDurationMs == 2143L
```
```kotlin
// HostMessagesTest (JUnit 4, as the class is): add the 10.1 test and remove the initialize line from `control requests`
@Test fun `initialize forwards subagent text`() { assertEquals("""{"type":"control_request","request_id":"a0","request":{"subtype":"initialize","hooks":null,"forwardSubagentText":true}}""", wire(HostMessages.initialize("a0"))) }
// RobustnessTest `truncated and mutated fixture lines never throw`: the sweep's input also includes every non-blank line of every .jsonl under fixtures/transcripts/ (list the directory; Fixtures.transcript(name) for each)
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.protocol.FixtureDecodeTest'` → compilation errors (`ThinkingTokens`, `toolUseResult`, `requiresUserInteraction`, …).
- [ ] **Step 3: Implement** the Interfaces block (and the `Reducer.kt` `reduceCli` branch listed under Files, so `session/` keeps compiling): `decodeSystem` gains `thinking_tokens`, `task_started`, `task_progress`, `task_updated`, `task_notification`; `decodeUser`/`decodeAssistant`/`decodeEvent`/`decodeResult`/`decodeInboundRequest` read the fields and both name sets; `HostMessages.initialize` adds `forwardSubagentText`.
- [ ] **Step 4: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.protocol.FixtureDecodeTest' --tests 'dev.daarkolabs.tandem.protocol.HostMessagesTest' --tests 'dev.daarkolabs.tandem.protocol.RobustnessTest'` → PASS, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS. This is the first run over the 29 probe fixtures and their `.jsonl` twins: a line that breaks the sweep is a fixture bug (fix the line), never a reason to narrow the sweep.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/protocol src/main/kotlin/dev/daarkolabs/tandem/session/Reducer.kt src/test/kotlin/dev/daarkolabs/tandem/protocol src/test/resources/fixtures && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(protocol): decode tool results, thinking, task and usage fields"` (the fixtures path is there for any fixture line Step 4 fixes)

---

### Task T2: reducer-state

**Depends on:** T1.

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/ChatState.kt`, `.../session/Reducer.kt`, `.../ui/ChatTranscript.kt` (adds `is ChatEntry.TurnEnded -> null` to `toTranscript()`'s `when`; deleted again in T7)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/ReducerTest.kt`; Modify `src/test/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptReaderTest.kt` (`shape()` is an exhaustive `when (entry)`: it gains `is ChatEntry.TurnEnded -> "end: ${entry.end}"`; expected shapes after a `result` gain that line) and `src/test/kotlin/dev/daarkolabs/tandem/session/SessionManagerTest.kt` (line 348 waits for `!s.busy && s.entries.lastOrNull { it !is ChatEntry.TurnEnded } is ChatEntry.Assistant`, since the last entry after a `result` is now `TurnEnded`)

**Interfaces:**
- Consumes: T1's `CliMessage` fields.
- Produces (spec 5.2, copied; defaults keep every existing constructor call compiling):
  ```kotlin
  data class SessionEvent.Cli(val message: CliMessage, val atMillis: Long = 0L); UserSent(val uuid: String, val text: String, val atMillis: Long = 0L); Ended(val detail: String, val isError: Boolean, val endedByHost: Boolean = false, val atMillis: Long = 0L)
  data class ChatState(…existing…, val cwd: String? = null, val turn: TurnProgress? = null, val tasks: Map<String, TaskInfo> = emptyMap())
  data class TurnProgress(val userUuid: String, val startedAtMillis: Long, val outputTokens: Int, val thinkingEstimate: Int, val lastToolUseId: String?, val toolCount: Int, val lastError: String? = null)
  data class TaskInfo(val taskId: String, val toolUseId: String?, val description: String?, val subagentType: String?, val status: String?, val lastToolName: String?, val totalTokens: Long?, val toolUses: Int?, val durationMs: Long?, val summary: String?)
  data class ChatEntry.User(override val id: String, val text: String, val sentAtMillis: Long? = null)
  data class ChatEntry.Assistant(…existing…, val agentId: String? = null, val subagentType: String? = null, val taskDescription: String? = null)
  data class ChatEntry.ToolResult(override val id: String, val toolUseId: String, val text: String, val isError: Boolean, val content: JsonElement? = null, val result: JsonElement? = null, val decision: PermissionDecision? = null, val parentToolUseId: String? = null, val agentId: String? = null, val atMillis: Long? = null)
  data class ChatEntry.Notice(override val id: String, val level: NoticeLevel, val text: String, val kind: NoticeKind = NoticeKind.GENERIC); enum class NoticeKind { GENERIC, INTERRUPTED }
  data class ChatEntry.TurnEnded(override val id: String, val end: TurnEnd, val durationMs: Long?, val outputTokens: Int?, val denials: List<PermissionDenial>, val error: String? = null)   // id "end-<turnsEnded>"
  data class PermissionDecision(val decision: String, val source: String?, val reasonType: String?, val nonExecutionKind: String?)
  data class AssistantBlock.Thinking(val text: String, val estimatedTokens: Int? = null, val durationMs: Long? = null, val startedAtMillis: Long? = null, val completedAtMillis: Long? = null)
  data class PendingPermission(…existing…, val displayName: String? = null, val requiresUserInteraction: Boolean = false, val decisionReasonType: String? = null, val agentId: String? = null, val blockedPath: String? = null)   // defaults: TranscriptReaderTest.kt:752's positional call keeps compiling
  ```
- Rules: the 5.2 table, row by row (`UserSent` → `turn = TurnProgress(uuid, atMillis, 0, 0, null, 0)`; `system/init` → `cwd`; thinking draft/delta/tokens/complete; `assistant.error` → `turn.lastError = message.apiErrorStatus?.let { "API Error: $it ${message.error}" } ?: "API Error: ${message.error}"`; `content_block_start(tool_use)` → the draft `AssistantBlock.ToolUse(id, name, input = null, partialInput = "")` (today `Reducer.toBlock` stores the decoded `{}`, so `PENDING` could never happen; the complete line still sets the full input), and on the main thread `lastToolUseId`, `toolCount + 1`; `message_delta` → `outputTokens +=`, `thinkingEstimate = 0`; `user` tool results with `content`/`result`/`decision`/`parentToolUseId`/`agentId`/`atMillis`, `result` only when the line carries exactly one `tool_result` block; interrupt marker → `Notice(INFO, "Stopped.", kind = INTERRUPTED)`; `task_*` → `tasks` (`task_started` only with `task_type == "local_agent"` or a `subagent_type`; `task_progress` replaces `TaskInfo.description` with its own description, so the working row's step reads the latest one, 4.9); `result` → `TurnEnded` + `turn = null`, where a FAILED result's `TurnEnded.error = turn?.lastError ?: result.result ?: result.errors.joinToString("; ").ifEmpty { null }` (raw text; the T6 footer formats it) and `Ended`'s is `turn.lastError ?: detail`; both `Ended` rules append a `TurnEnded` and bump `turnsEnded`); `settled()` also clears `turn`. Retry notice: `Notice(INFO, …)` joining the present parts with `" · "`: `<error_status> <error>` (each omitted when null; with neither the part is `API error`), `Retrying in ${retryDelayMs / 1000}s` (integer division; omitted when null), `attempt <attempt>/<max_retries>` (omitted when either is null) (4.11).

- [ ] **Step 1: Write the failing tests** (`ReducerTest`, JUnit 4; `replay(fixture, initial)` exists; stamp hand-made events with `atMillis`; a test that reads `turn` replays with `initial = reduce(ChatState(), SessionEvent.UserSent("u1", "prompt", atMillis = 0L))`, since no fixture line opens a turn):

```kotlin
`ask-single - the question is a pending permission before the block stops and the answer links to the call`   // after line 22: pendingPermissions.single().toolName == "AskUserQuestion" && requiresUserInteraction, and the last Assistant entry is streaming; after 26: a ToolResult(toolUseId == "<id-12>") whose result!!["answers"] is a JsonObject
`ask-deny - a cancelled question is a rejected result and the turn still completes`   // a ToolResult with isError && decision!!.decision == "reject"; final TurnEnded(end == COMPLETED, denials.size == 1)
`a tool result before message_delta lands on the streaming message`   // ask-multi-array: after line 30 the ToolResult exists and the Assistant entry still streams; after 31 and 32 it does not
`plan-approve - the status line after approval switches the mode before the tool result`   // after 90 permissionMode == ACCEPT_EDITS; after 91 a ToolResult whose result!!["plan"] exists
`plan-deny - a deny keeps plan mode and the second request is pending again`   // feed PermissionAnswered(requestId) after each of the three requests (:79 ExitPlanMode, :143 ExitPlanMode, :175 Write): pendingPermissions.size == 1 at each; permissionMode == PLAN until line 145
`think-display-summarized - thinking text accumulates then the complete block brings the duration`   // the draft Thinking text grows on each delta; after 34: Thinking(text.startsWith("47×83"), durationMs == 1991L)
`a thinking block without thinking_duration_ms takes its duration from the host clock`   // think-default with the "thinking_duration_ms" key removed in memory, Cli events stamped atMillis 1000 (content_block_start) and 3000 (the complete line): Thinking(durationMs == 2000L)
`think-default - the thinking estimate rises with thinking_tokens and clears on message_delta`   // turn!!.thinkingEstimate takes 50, 150, 285 in order; after message_delta 0 and turn.outputTokens == the line's usage.output_tokens
`tools-bash - results keep content, result and decision`   // after line 20 pendingPermissions.single().decisionReasonType == "subcommandResults"; first ToolResult: decision == PermissionDecision("accept", "user_temporary", null, null) and result!!["stdout"] exists; second: isError and result is a JsonPrimitive; after line 18 turn.lastToolUseId == the Bash block id and turn.toolCount == 1; after 34 toolCount == 2
`permission - a denied call records the reject decision`   // permission.ndjson:56 → ToolResult.decision!!.decision == "reject"
`agent-sync - task lines fill tasks and subagent entries carry the parent id`   // tasks[<task_id>].status == "completed", summary != null, toolUses == 2; entries with parentToolUseId == "<id-12>" exist and carry agentId; after line 41 tasks["<task_id-42>"]!!.description == "Running wc -l seed.txt && tail -c1 seed.txt | xxd"; after line 43 pendingPermissions.single().agentId == "<task_id-42>"
`agent-bash-task - a bash task is not stored`   // tasks.isEmpty()
`misc-state-action - the pending permission keeps blocked_path, display name and suggestions`   // the reducer half of the 10.1 row: replay misc-state-action through line 21 → pendingPermissions.single() has blockedPath == "<path>", displayName == "Bash", suggestions.size == 3
`a streaming tool_use has no input until its complete line`   // permission: after line 11 the draft ToolUse's input == null (partialInput grows on input_json_delta); after line 19 input!!["command"] == "touch tandem-fixture.txt"
`agent-forwarded - the forwarded text is a subagent assistant entry`   // an Assistant entry with agentId != null whose Text block is "7"
`misc-image - an image result keeps its content array`   // the ToolResult's content is a JsonArray
`UserSent records the send time and result appends a TurnEnded entry`   // plain-reply with UserSent("u1", "hi", atMillis = 1234) first: turn!!.startedAtMillis == 1234L; final: TurnEnded(COMPLETED, durationMs == the fixture result's duration_ms), turn == null
`interrupt shows Stopped and no error`   // extend: the notice has kind == INTERRUPTED; a TurnEnded(INTERRUPTED) entry exists
`Ended during a turn appends a FAILED turn end with the host duration`   // UserSent(atMillis = 1000), Ended("exit 2", isError = true, atMillis = 4000): TurnEnded(FAILED, durationMs == 3000L, error == "exit 2") precedes the ERROR notice; turn == null
`Ended by the host during a turn appends an INTERRUPTED turn end`   // Ended(endedByHost = true): TurnEnded(INTERRUPTED, durationMs == 3000L), an INFO notice, turn == null
`turn end ids stay unique across a host stop and a later result`   // UserSent, Ended(endedByHost = true), UserSent, the plain-reply result: two TurnEnded with distinct ids and entryIndex.size == entries.size
`invalid-model - the assistant error line sets the turn error and the failed result carries it`   // after line 4 turn!!.lastError == "API Error: 404 model_not_found"; final TurnEnded(FAILED, error == "API Error: 404 model_not_found")
`an api_retry line yields the reference wording`   // the existing line (attempt 2, max_retries 10, retry_delay_ms 4000, error "overloaded") plus "error_status":529 → Notice(INFO, "529 overloaded · Retrying in 4s · attempt 2/10"); without error_status → "overloaded · Retrying in 4s · attempt 2/10"; the existing lines without error or retry_delay_ms (ReducerTest:233/238) never show "null"
`init stores cwd`   // tools-read:5 → cwd == "<path>"
`a line with two tool results attaches the object to neither`   // Review Focus 1: a hand-made user line with two tool_result blocks and one tool_use_result → both ToolResult.result == null, both content != null
```
Existing tests that count or index `entries` after a `result` gain the `TurnEnded` entry; `retries, errors and process exit are visible` takes the new retry wording and level. Adjust assertions, never the rules.

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.ReducerTest'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the rules in `Reducer.kt`; `ChatTranscript.toTranscript()` gets `is ChatEntry.TurnEnded -> null`.
- [ ] **Step 4: Run** the class, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`session/` stays ≥ 90 %).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session src/main/kotlin/dev/daarkolabs/tandem/ui/ChatTranscript.kt src/test/kotlin/dev/daarkolabs/tandem/session && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(session): turn progress, tool result details, thinking and task state in the reducer"`

---

### Task T3: session-answers-and-spawn

**Depends on:** T1, T2 (the `SessionEvent` `atMillis` stamps and the `PendingPermission` fields).

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/session/PermissionAnswers.kt`
- Modify: `.../session/ClaudeSession.kt` (`SessionConfig.argv`, `clock`, `answer`, `lastFrameAtMillis`), `.../session/SessionHandle.kt`, `.../session/SessionManager.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/session/PermissionAnswersTest.kt`; Modify `.../session/ClaudeSessionTest.kt`, `.../session/SessionManagerTest.kt`, `.../session/PermissionUpdatesTest.kt`

**Interfaces:**
- Consumes: T1 `HostMessages.allow/deny` (unchanged), `sessionPermissionUpdates` (`PermissionUpdates.kt`), T2 `SessionEvent` stamps and `PendingPermission`.
- Produces (spec 5.2):
  ```kotlin
  sealed interface PermissionAnswer { data class Allow(val updatedInput: JsonObject, val updatedPermissions: List<JsonObject> = emptyList()) : PermissionAnswer; data class Deny(val message: String) : PermissionAnswer }
  object PermissionAnswers {
      fun allowOnce(p: PendingPermission): Allow                       // Allow(p.input)
      fun allowForSession(p: PendingPermission): Allow                 // Allow(p.input, sessionPermissionUpdates(p.suggestions))
      fun deny(): Deny                                                 // "The user denied this request."
      fun questionAnswers(p: PendingPermission, answers: Map<String, String>): Allow   // updatedInput = {questions: p.input["questions"] unchanged, answers}
      fun cancelQuestion(): Deny                                       // "cancelled"
      fun approvePlan(p: PendingPermission, startCoding: Boolean): Allow   // updatedPermissions = [{"type":"setMode","mode":"acceptEdits","destination":"session"}] only when startCoding
      fun keepPlanning(feedback: String): Deny                         // feedback.trim().ifBlank { "No, keep planning" }
  }
  class ClaudeSession(parentScope, config, launcher = DefaultProcessLauncher(), initialState = ChatState(), clock: () -> Long = System::currentTimeMillis)
  fun ClaudeSession.answer(permission: PendingPermission, answer: PermissionAnswer)   // outbox.trySend(allow/deny frame), then PermissionAnswered
  val ClaudeSession.lastFrameAtMillis: StateFlow<Long>   // set to clock() in readLoop on every stdout line (keep_alive and Unknown included), before dispatch
  val SessionHandle.lastFrameAtMillis: StateFlow<Long>   // mirrors the attached process's flow (a child collector next to the state mirror in connectLocked)
  fun SessionManager.answer(id: SessionId, requestId: String, answer: PermissionAnswer)   // looks the PendingPermission up as allow/deny do; no-op when gone
  fun SessionManager.allow(id, requestId, always) = answer(id, requestId, if (always) allowForSession(p) else allowOnce(p)); fun deny(id, requestId) = answer(id, requestId, deny())
  ```
  `ClaudeSession.allow/deny` are replaced by `answer`; every `Cli`, `UserSent` and `Ended` event is stamped with `clock()`. `SessionConfig.argv()` adds `"--thinking-display", "summarized"` right after `--include-partial-messages`. No `set_max_thinking_tokens` request is ever sent (fake-CLI ordering, spec 5.2).

- [ ] **Step 1: Write the failing tests.** `PermissionAnswersTest` (requests from `ask-single:22`, `plan-approve:88`, `misc-state-action:21` decoded into `PendingPermission`s through `reduce`):

```kotlin
`questionAnswers keeps the questions and adds the answers`   // updatedInput == buildJsonObject { put("questions", <ask-single:22 input.questions>); putJsonObject("answers") { put("Which colour?", "Blue") } }; updatedPermissions.isEmpty()
`approvePlan adds setMode only when starting to code`   // approvePlan(p, true).updatedPermissions == listOf({"type":"setMode","mode":"acceptEdits","destination":"session"}); approvePlan(p, false).updatedPermissions.isEmpty(); both updatedInput === p.input
`keepPlanning sends the feedback verbatim and falls back when blank`   // keepPlanning("also add a date") == Deny("also add a date"); keepPlanning("  ") == Deny("No, keep planning")
`allowForSession echoes addRules only`   // moved from PermissionUpdatesTest `allowing for the session writes no setMode or addDirectories entry`: misc-state-action:21 → one updatedPermissions entry, type "addRules", destination "session"
```
`SessionManagerTest` (fake CLI, frames from `TANDEM_FAKE_LOG` through the existing `frames()`/`spawns()` helpers; the fake waits for a host `user` frame before each `system/init`, so send one prompt per `system/init` line of the fixture and wait for each turn end; wait for a request with `handle.state.first { it.pendingPermissions.isNotEmpty() }`):
```kotlin
`spawn passes thinking display summarized and initialize forwards subagent text`   // plain-reply: spawns().single() holds "--thinking-display" followed by "summarized"; the initialize control_request's request["forwardSubagentText"] == true
`answering a question sends the picked label`   // ask-single: answer(id, p.requestId, questionAnswers(p, mapOf("Which colour?" to "Blue"))); the control_response has request_id "<request_id-27>", response.response.behavior "allow", updatedInput.answers["Which colour?"] == "Blue", toolUseID "<id-12>"; then lastTurnEnd == COMPLETED
`answering a multi-select joins the labels`   // ask-multi: answers "Which toppings would you like?" to "Cheese, Ham" → the frame's answers value == "Cheese, Ham"
`cancelling a question denies with cancelled and the turn goes on`   // ask-deny: cancelQuestion(): behavior "deny", message "cancelled"; lastTurnEnd == COMPLETED
`approving a plan to start coding sends setMode and the mode follows the status line`   // plan-approve: approvePlan(p, true): updatedPermissions[0] == {"type":"setMode","mode":"acceptEdits","destination":"session"}; state.permissionMode == ACCEPT_EDITS after the turn
`approving a plan keeps the mode without permissions`   // plan-disallowed: approvePlan(p, false) on the ExitPlanMode request (:173): no "updatedPermissions" key; permissionMode == DEFAULT once :177 has been applied; then the second request (Bash, :213) is answered with allowOnce(p) and the test waits for lastTurnEnd == COMPLETED (the fake waits for a control_response after each request)
`keeping planning denies with the feedback`   // plan-deny: keepPlanning("also add a date") on :79: behavior "deny", message "also add a date"; wait for the :143 request (a second pending permission) and answer it with approvePlan(p, false), answer :175 (Write) with allowOnce(p), then wait for lastTurnEnd == COMPLETED
`lastFrameAt advances on every line`   // plain-reply: collect handle.lastFrameAtMillis while the turn runs: values non-decreasing, more than one distinct value, and the last ≥ the clock read just before the prompt was sent
`answering a permission that is no longer pending writes no frame`   // permission: answer the first request twice with allowOnce(p); frames() holds exactly one control_response with its request_id
```
`ClaudeSessionTest` `argv for a new session and for a resumed fork`: `base` gains `"--thinking-display", "summarized"` after `"--include-partial-messages"`. New `ClaudeSessionTest` test (JUnit 4, the class's `fakeCli` helper):
```kotlin
`keep_alive and unknown lines advance lastFrameAt without a state emission`   // fakeCli("echo '{\"type\":\"keep_alive\"}'; sleep 0.2; echo '{\"type\":\"no_such_type\"}'; sleep 0.2"); record lastFrameAtMillis values and state emissions while it runs: two distinct non-zero lastFrameAt values, the second greater than the first, and no ChatState emission between them (5.2: the not-responding check depends on it)
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.PermissionAnswersTest' --tests 'dev.daarkolabs.tandem.session.SessionManagerTest' --tests 'dev.daarkolabs.tandem.session.ClaudeSessionTest'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block. `SessionHandle` mirrors with a `MutableStateFlow(0L)` written by a child `launch` of the mirror job. Delete `PermissionUpdatesTest`'s moved test.
- [ ] **Step 4: Run** the Step 2 command → PASS, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/session && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(session): one answer path for permissions, frame clock and summarized thinking"`

---

### Task T4: chat-rows

**Depends on:** T2 (and T3 in run order).

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/chat/ChatRow.kt`, `.../chat/ChatRowsBuilder.kt`, `.../chat/ToolKinds.kt`, `.../chat/ToolLabels.kt`, `.../chat/ToolStates.kt`, `.../chat/ToolBodies.kt`, `.../chat/Text.kt`, `.../chat/Cards.kt` (only `sealed interface PendingCard` in this task)
- Modify: `build.gradle.kts` (Kover: `packages("dev.daarkolabs.tandem.protocol", "dev.daarkolabs.tandem.session", "dev.daarkolabs.tandem.chat")`, rule renamed `"90% of lines in each package of protocol/, session/ and chat/"`), `CLAUDE.md` (Layout gains `chat/`; the `koverLog` and coverage lines name `chat/`), `docs/design.md` (a `chat/` line after `protocol/` in the architecture block)
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/chat/Replay.kt` (shared helpers, below), `.../chat/ToolLabelsTest.kt`, `.../chat/ToolStatesTest.kt`, `.../chat/ToolBodiesTest.kt`, `.../chat/ChatRowsBuilderTest.kt`

**Interfaces:**
- Consumes: T2's `ChatState`, `ChatEntry`, `AssistantBlock`, `PendingPermission`, `TaskInfo`, `TurnProgress`.
- Produces: every type of spec 5.3 verbatim (`ChatRow`, `RowGap`, `UserRow`, `TextRow`, `ThinkingRow`, `ToolRow`, `NoticeRow`, `TurnEndRow`, `WorkingRow`, `RowLabel`, `ToolCall`, `ToolKind`, `ToolState`, `DiffStats`, `ToolBody.*`, `DiffHunk`, `DiffLine`, `DiffKind`, `Link`) and from 5.4:
  ```kotlin
  data class RowOptions(val showThinking: Boolean); data class ChatRows(val rows: ImmutableList<ChatRow>, val card: PendingCard?, val moreWaiting: Int)
  sealed interface PendingCard { val requestId: String; val toolUseId: String?; val agentDescription: String? }   // Cards.kt; implementations in T6
  class ChatRowsBuilder(private val options: RowOptions) { fun build(state: ChatState): ChatRows }   // card = null, moreWaiting = 0, no WorkingRow/TurnEndRow until T6; per-turn cache per 5.4; subagent entries (parentToolUseId != null) go into their Agent call's children
  object ToolKinds { fun of(name: String): ToolKind }   // 4.6.2 table; Task → AGENT; TaskCreate/Update/Get/List → TASKS; mcp__tandem__edit* → EDIT, else mcp__<server>__<tool> → MCP
  object ToolLabels {
      fun label(kind: ToolKind, name: String, input: JsonObject?, state: ToolState, result: JsonElement?, cwd: String?): RowLabel
      fun agentProgress(result: JsonElement?): String?          // "Done (<n> tool use|uses · <formatTokens> tokens · <formatDuration>)" from totalToolUseCount/totalTokens/totalDurationMs; null when any of the three is missing
      fun agentProgress(task: TaskInfo): String?                // live "${n} tool use${if (n == 1) "" else "s"} · ${formatTokens(t)} tokens · ${formatDuration(d)}" from toolUses/totalTokens/durationMs ("1 tool use" singular); null when any of the three is null
      fun middleTruncate(s: String, max: Int): String; fun relativePath(path: String, cwd: String?): String; fun hostOf(url: String): String
      fun stripAnsi(s: String): String; fun stripSystemReminders(s: String): String
  }
  object ToolStates { fun of(block: AssistantBlock.ToolUse, result: ChatEntry.ToolResult?, pending: Boolean, turnOpen: Boolean): ToolState }
  // order: a result → DONE / FAILED / DENIED; else pending → WAITING; else !turnOpen → INTERRUPTED (also when block.input == null); else input == null → PENDING; else RUNNING
  // A WAITING call with input == null labels like PENDING (the verb alone: `Reading…`, `Running…`). This is spec 8's "PENDING in text.warning" drawn through the WAITING state; never dereference a null input (Deviations, Global Constraints).
  // PENDING (and WAITING with input == null) label = the first word of the 4.6.2 Running form + "…": Reading… (READ), Editing… (EDIT), Creating… (CREATE), Running… (SHELL, AGENT), Searching… (SEARCH, WEB_SEARCH), Finding… (FIND), Fetching… (WEB_FETCH), Asking… (QUESTION), Proposing… (PLAN), Entering… (PLAN_ENTER), Updating… (TASKS), Using… (MCP, OTHER); spans empty.
  object ToolBodies { fun body(kind: ToolKind, input: JsonObject?, content: JsonElement?, result: JsonElement?, isError: Boolean): ToolBody; fun stats(kind: ToolKind, input: JsonObject?, result: JsonElement?): DiffStats? }
  // Text.kt (top-level internal): formatTokens(n: Long): String ("312", "1.2k", "1.5m"); formatElapsed(ms: Long): String; formatDuration(ms: Long): String; displayName(toolName: String): String (CamelCase split); conjugate(description: String): String (the 4.6.2 verb table)
  ```
  `ToolCall` gains `val subject: String? = null` (an extension of 5.3, for T5's `Grouping.sentence`): the untruncated name relative to cwd for READ/EDIT/CREATE, the one-line command for SHELL, else null; set by the builder. `label()` and `stats()` for kind CREATE read the result: `type == "update"` with a non-empty `structuredPatch` → `Edited <name>` and the patch stats; otherwise `Created <name>` and the content line count. The builder calls both with `ToolKinds.of(name)` and then sets `ToolCall.kind = EDIT` for an update.
  `RowLabel.text` never carries diff stats: `ToolLabels.label` returns `Edited <name>` / `Created <name>`, and the `+A -B` of 4.6.2 is `ToolCall.stats` / `ToolRow.stats` from `ToolBodies.stats`, drawn by T7 after the label. `RowLabel.spans`: one `IntRange` per mono substring of `text` (the `<name>`, `<cmd>`, `<pattern>`, `<host>`, `"<query>"`), `first` = start index, `last` = end index inclusive; T7 applies `addStyle(mono, r.first, r.last + 1)`.
  Builder rules beyond 5.3: the first row is `NONE` even when it is a `UserRow`; `UserRow.mono = text.startsWith("/") || text.startsWith("!")`; an entry whose `parentToolUseId` matches no Agent/Task call in the state is built at top level. Before folding (T5; in T4, before emitting), the builder drops every call with `kind == TOOL_SEARCH` whose state is not `FAILED` (PENDING, RUNNING, WAITING, DONE, DENIED, INTERRUPTED; 4.6.2 hides them and gives no base form): no row, no group member, not counted. No row ever has an empty label, and the next visible call's row is keyed `tool:<its id>`. AGENT call: `progress = ToolLabels.agentProgress(result)` when a result exists, else `ToolLabels.agentProgress(task)` for the `TaskInfo` with `toolUseId == call.id`, else `null`; `body = ToolBody.None` when a child `TextRow` exists, else `ToolBody.Markdown(<tool_use_result.content[] text blocks joined "\n\n"> ?: task.summary, null)` when either exists, else `ToolBody.None`.
  The builder passes `result = toolResult.result ?: JsonPrimitive(toolResult.text)` to labels and bodies, so the `No such tool available` rule (strip a leading `Error: `, then `startsWith`) and the `Used a tool` orphan row (4.6.2) see the error text. Keys and gaps as in 5.3, with this precedence where two 5.3 rules meet: `UserRow` is `TURN` (`NONE` when first); `NoticeRow`, `WorkingRow` and `TurnEndRow` are always `SECTION` (also right after a `UserRow`); the previous-row rules (`AFTER_BUBBLE`, `ROW`, `BEFORE_TEXT`, `SECTION`) apply to Text/Thinking/Tool rows only, and such a row right after a `NoticeRow` is `SECTION`. `ThinkingRow.label` per 4.5 (`Thinking…` before the first count; `"Thinking… · ${formatTokens(n.toLong())} tokens"` with `n` = the open block's `estimatedTokens`, which `system/thinking_tokens` and `thinking_delta.estimated_tokens` set (5.2); `Thought for <s>s` with `s = round half up`; `Thinking` for a done block with no duration); a done thinking block with empty text, no duration and no tokens emits no row; `showThinking = false` emits no thinking rows. `NoticeRow` is never emitted for `NoticeKind.INTERRUPTED`.

- [ ] **Step 1: Write the failing tests** (JUnit 5; blocks and results come from the named fixture lines replayed through `reduce`, or are hand-made where the spec says so):

```kotlin
// ToolLabelsTest (names from spec 10.3; cwd = "<path>" unless stated)
`read rows name the file and the range`                       // tools-read:17 RUNNING → "Reading <path>", DONE → "Read <path>" with label.spans == persistentListOf(5..10) (`<path>` in `Read <path>`); :34 DONE → "Read <path>:10-14"
`names are relative to cwd and middle-truncated`             // relativePath("/a/b/c.kt", "/a") == "b/c.kt"; relativePath("/a", "/a") == "a"; relativePath("/x/y.kt", "/a") == "y.kt"; relativePath("/a/b/c.kt", "/a/") == "b/c.kt"; middleTruncate(49 chars, 48).length == 48 and contains "…"
`bash rows show the description and fall back to the command`   // permission.ndjson:19 RUNNING → "Creating empty fixture file", DONE → "Ran Create empty fixture file"; no description → "Running <cmd>" / "Ran <cmd>"
`a leading Run is dropped after Ran`                          // tools-bash:18 DONE → "Ran echo to stdout and stderr"
`bash failure uses the description lowercased`                // tools-bash:34 FAILED → "Failed to run failing command with exit code 3"
`write create counts the content lines`                       // tools-edit:57 DONE with :61's result → label.text == "Created <path>" and ToolBodies.stats(ToolKind.CREATE, input, result) == DiffStats(2, 0); content "x" → DiffStats(1, 0)
`write update is an edit`                                     // r = {"type":"update","structuredPatch":[{"oldStart":1,"newStart":1,"lines":["-a","+b","+c"]}]}, input = {file_path:"<path>",content:"b\nc\n"} → label(ToolKind.CREATE, "Write", input, DONE, r, "<path>").text == "Edited <path>" and ToolBodies.stats(ToolKind.CREATE, input, r) == DiffStats(2, 1)
`edit stats come from structuredPatch`                        // tools-edit:44 → label.text == "Edited <path>", ToolBodies.stats(...) == DiffStats(1, 1)
`grep and glob rows count matches and files`                  // tools-read:62 → "Searched for seven · 1 match"; :50 → "Found 1 file"; numFiles 0 → "Found no files"
`web rows name host and query`                                // misc-web:29 → "Fetched " + hostOf(input.url) (no scheme, no path); :73 → "Searched the web for \"" + input.query + "\""
`agent rows carry the Done sub-line`                          // agent-sync:50 → agentProgress(result) == "Done (2 tool uses · 21.8k tokens · 5.8s)"; label "Ran an agent: Count lines in seed.txt"; replay agent-sync through line 41 → agentProgress(state.tasks.values.single()) == "1 tool use · 21.5k tokens · 2.0s"; a TaskInfo with toolUses = null → null
`tool search is hidden unless it failed`                      // label(TOOL_SEARCH, "ToolSearch", input, s, null, cwd).text == "" (hidden) for s = PENDING (input = null), RUNNING and DONE; FAILED → "Failed to load tools"
`mcp rows name server and tool`                               // "mcp__github__list_issues" → "Using github: list issues" / "Used github: list issues"
`unknown tools split camel case`                              // "NotebookEdit" is EDIT; "Monitor" → "Using Monitor"; "SomeNewTool" → "Used Some New Tool"
`denied rows use the base form`                               // permission.ndjson:50/56 → "Remove fixture file · Denied"; no description → "Run <cmd> · Denied"
`interrupted rows use the base form`                          // Read INTERRUPTED → "Read <path> · Interrupted"
`hostOf strips scheme and path`                               // hostOf("https://docs.example.com/a/b?q=1") == "docs.example.com"
`commands are middle-truncated at 60`                         // a 61-char command → 60 chars with "…"; newlines become "; "; the label's span covers the whole truncated command
`an unavailable tool reads Failed to use`                     // tools-read-unavailable:49 → "Failed to use Glob"; plan-disallowed:103 → "Failed to use Write"
`a path equal to cwd keeps its basename`                      // "<path>" with cwd "<path>" → "Read <path>"
`enter plan mode is a visible row`                            // hand-made EnterPlanMode block → "Entering plan mode" / "Entered plan mode" / "Failed to enter plan mode"
`write rows read Creating while running and Created or Edited from the result`   // tools-edit:57 RUNNING → "Creating <path>"; with :61's result → "Created <path>" + ToolBodies.stats == DiffStats(2, 0); with a result {type: "update", structuredPatch: [one hunk, "-a", "+b"]} → "Edited <path>" + DiffStats(1, 1)
`question rows read Questions with header lines`              // ask-single:22/26 → "Questions"; the blank-header fallback is asserted in ToolBodiesTest `answers body pairs headers with picks`
`a call interrupted mid-input labels from the name alone`     // Review Focus 2: ToolUse("Read", input = null, partialInput = "{\"file_pa") INTERRUPTED → "Read · Interrupted", never throws
`a blank description falls back to the command`               // Review Focus 3: description "" → "Running <cmd>" / "Ran <cmd>"
`pending rows show the verb alone`                            // label(READ, "Read", null, PENDING, null, cwd).text == "Reading…"; label(SHELL, "Bash", null, PENDING, null, cwd).text == "Running…"; label(SHELL, "Bash", null, WAITING, null, cwd).text == "Running…" (a request before the complete line)
// ToolStatesTest: one test per 4.6.3 transition, feeding ToolStates.of(block, result, pending, turnOpen) (b = ToolUse("t1", "Read", input, partialInput); ok = ToolResult(toolUseId = "t1", isError = false); err = isError, decision null, text "boom"; rej = isError, decision PermissionDecision("reject", …)):
//   `content_block_start yields PENDING`   (input = null, result = null, pending = false, turnOpen = true) → PENDING
//   `a complete line yields RUNNING`       (input = {file_path}, null, false, true) → RUNNING
//   `can_use_tool yields WAITING`          (input = {…}, null, true, true) → WAITING
//   `a request before the complete line yields WAITING`   (input = null, null, pending = true, turnOpen = true) → WAITING (Deviations)
//   `an answer returns to RUNNING`         (input = {…}, null, false, true) after the WAITING tuple → RUNNING
//   `an ok result yields DONE`             (input = {…}, ok, false, true) → DONE
//   `a reject decision yields DENIED`      (input = {…}, rej, false, true) → DENIED
//   `another error yields FAILED`          (input = {…}, err, false, true) → FAILED
//   `turn end yields INTERRUPTED`          (input = {…}, null, false, false) → INTERRUPTED; and ToolStates.of(AssistantBlock.ToolUse("t1", "Read", input = null, partialInput = "{\"file_pa"), result = null, pending = false, turnOpen = false) == INTERRUPTED (Review Focus 2)
//   `a late result upgrades INTERRUPTED`   (input = {…}, ok, false, false) → DONE; (input = {…}, err, false, false) → FAILED
// plus
`reload without meta still detects the denial text`           // isError + text "The user denied this command." / "cancelled" / "The user denied this request." and decision == null → DENIED
`a settled state never yields RUNNING`                        // input != null, result == null, pending == false, turnOpen == false → INTERRUPTED
// ToolBodiesTest
`read body has line numbers from startLine`                   // tools-read:38 → Mono whose first line starts with "10"
`bash body starts with the command and keeps the exit code line`   // tools-bash:39 → Command(command == input.command, output.first().startsWith("Exit code 3"), firstLineError)
`bash no output gives no body`                                 // misc-state-action:25 "(Bash completed with no output)" → ToolBody.None
`system reminders are stripped`                                // a Read result whose file.content ends with "\n<system-reminder>\nx\n</system-reminder>" → no body line contains "system-reminder" or "x"
`ansi is stripped`                                             // a Bash stdout "\u001b[31mred\u001b[0m" → output == ["red"]
`bodies clamp at 20 and cap at 400 with the total`             // 500 lines → lines.size == 400, totalLines == 500
`image result reads image WxH`                                 // misc-image:26 → Image(8, 8, "image/png")
`web search body lists links`                                  // misc-web:78 → Links(links non-empty, each with title and url)
`answers body pairs headers with picks`                        // ask-single:26 → Pairs([("Colour", "Blue")]); ask-single:22's input with questions[0].header replaced by "" and :26's result → Pairs([("Which colour?", "Blue")])
`cancelled question body`                                      // ask-deny:24 → Mono(["cancelled"]) (the error text)
`diff body from structuredPatch`                               // tools-edit:44 → Diff(hunks.size == 1) with one REMOVED and one ADDED line
`a result without the expected field falls back to the content text`   // a Grep result without content/filenames, a Read result without file.content → Mono(content text)
`a body over 50000 characters ends with the cut line`          // last line matches "… (cut, N more characters)"
// Replay.kt (src/test/kotlin/dev/daarkolabs/tandem/chat/Replay.kt, shared by T4–T7 and slice C): internal const val PROMPT = "Reply with the single word: pong";
//   internal fun replay(name: String, options: RowOptions = RowOptions(showThinking = true), prompt: String = PROMPT, initial: ChatState = ChatState(), onLine: (line: Int, state: ChatState, rows: ChatRows) -> Unit = { _, _, _ -> }): Pair<ChatState, ChatRows>
//   folds the decoded lines through reduce, starting from `initial`, with one ChatRowsBuilder(options), building after every event (never throws): for each decoded line i (0-based) it emits
//   SessionEvent.UserSent("u${i + 1}", prompt, atMillis = i * 100L) before every `system/init` line (the fake CLI likewise waits for one prompt per init; no fixture holds the prompt's user line),
//   then SessionEvent.Cli(m, atMillis = i * 100L), then onLine(i + 1, state, rows); "after line N" below means inside onLine with line == N; the pair is the final state and rows.
//   internal fun ChatRows.content(): List<ChatRow> = rows.filter { it !is WorkingRow && it !is TurnEndRow }. C's T13 extends these goldens.
// ChatRowsBuilderTest:
`plain-reply` → content() == [UserRow(text = PROMPT, mono = false), TextRow("pong", streaming = false)]
`permission` → after line 11 the ToolRow is PENDING with label "Running…"; after line 19 RUNNING with "Creating empty fixture file"; final ToolRows: "Ran Create empty fixture file" DONE body None; "Remove fixture file · Denied" DENIED body Mono; TextRows "done.", "denied."
`tools-bash` → "Ran echo to stdout and stderr" DONE; "Failed to run failing command with exit code 3" FAILED
`ask-single` → one ToolRow "Questions" DONE body Pairs; `ask-deny` → "Question cancelled" DENIED
`plan-approve` → "Plan approved" DONE; `plan-deny` → "Plan sent back" DENIED then "Plan approved" DONE; `plan-disallowed` → "Failed to use Write" FAILED, "Plan approved" DONE
`agent-sync` → after line 41 the Agent call is RUNNING with call.progress == "1 tool use · 21.5k tokens · 2.0s"; final "Ran an agent: Count lines in seed.txt" DONE, call.progress == "Done (2 tool uses · 21.8k tokens · 5.8s)", call.children non-empty and no top-level row from a subagent entry; `agent-forwarded` → the forwarded "7" is inside children, not a top-level TextRow
`think-display-summarized` → ThinkingRow(label == "Thought for 2s", summary.startsWith("47×83")); while streaming the label starts with "Thinking…"; `think-default` → after line 8 the ThinkingRow label == "Thinking…" and streaming == true; after line 9 == "Thinking… · 50 tokens"; after line 14 == "Thinking… · 285 tokens"; final "Thought for 2s" with summary ""
`a thinking block without a duration reads Thinking`           // a settled hand-made state with an Assistant entry holding Thinking("x") (no durationMs, no startedAtMillis) → ThinkingRow label "Thinking"; Thinking("") with no duration and no tokens → no ThinkingRow
`a retraction drops the cached rows`                           // Review Focus 6: plain-reply replayed, build; apply a hand-made system/model_refusal_fallback line whose retracted_message_uuids names the reply (as ReducerTest `refusal fallback retracts the refused reply and warns`), build again with the same builder: the retracted entry's TextRow key is gone, and the rows equal a fresh ChatRowsBuilder(options).build(state)
`misc-image` → "Read <path>" DONE body Image; `misc-web` → rows start "Fetched " and "Searched the web for"; after every event no ToolRow holds a TOOL_SEARCH call (neither as its only call nor among its calls), and no row has an empty label
`invalid-model` → a NoticeRow(ERROR) whose text starts with "The turn failed" (T6 adds the FAILED footer, drops this row because the footer carries it (4.11), and updates this golden); `interrupt` → no PENDING/RUNNING row and no NoticeRow with text "Stopped."
`keys are unique and stable across every state of every fixture`   // every .ndjson in Fixtures.names(): keys distinct after every event; a key present before an event that still describes the same entry/block/call is present after it (5.3 exception allowed)
`previous turns return the same row instances`                 // reloaded = runBlocking { TranscriptReader.read(Fixtures.transcript("history-reply")) }.state; replay("plain-reply", initial = reloaded, onLine = …) (its own UserSent before the init line opens the live turn; no extra UserSent): the history rows are === across every build
`unchanged rows of the live turn keep their instances across a text delta`   // tools-read: the Read/Glob rows are === before and after each text_delta
`showThinking false drops thinking rows without changing groups`   // tools-read-unavailable: no ThinkingRow, the ToolRows' keys and labels equal the showThinking = true build (re-asserted in T5 with groups)
`a result without a matching call renders a Used a tool row`   // hand-made ToolResult(toolUseId = "orphan", text = "x") → ToolRow(label.text == "Used a tool", DONE, body Mono(["x"]))
`gaps follow the previous row`                                 // first row NONE (also when it is a UserRow); a later UserRow TURN; UserSent("u", "/compact") → that UserRow's mono == true; first after UserRow AFTER_BUBBLE; tool after tool ROW; thinking after tool ROW; text after tool BEFORE_TEXT; tool after text SECTION; text after text SECTION; NoticeRow SECTION; a NoticeRow right after a UserRow is SECTION (invalid-model); a TextRow after a NoticeRow is SECTION
`building 32000 events stays linear`                           // as ReducerTest `reduces 32,000 events in linear time`: build after each of 32,000 synthetic events, total under 2 s
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.*'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block (one file per object as listed; the label and body rules are the 4.6.2 and 4.7 tables; `ToolKinds.of` is one `when`); add the Kover package, the `CLAUDE.md` and `docs/design.md` lines.
- [ ] **Step 4: Run** the package, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS with `chat/` ≥ 90 % in `koverLog`.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/chat build.gradle.kts CLAUDE.md docs/design.md && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(chat): pure row model, tool labels, states and bodies"`

---

### Task T5: chat-grouping

**Depends on:** T4.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/chat/Grouping.kt`
- Modify: `.../chat/ChatRowsBuilder.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/chat/GroupingTest.kt`; Modify `.../chat/ChatRowsBuilderTest.kt`

**Interfaces:**
- Consumes: T4's `ToolCall`, `ToolKind`, `ToolState`, `RowLabel`, `DiffStats`, `ChatRowsBuilder`.
- Produces (spec 5.4, 4.6.4):
  ```kotlin
  object Grouping {
      fun fold(calls: List<ToolCall>): List<List<ToolCall>>   // input: one run of visible main-thread calls, already split by the builder at text, thinking, user and notice boundaries (rule 1; the builder already removed hidden ToolSearch calls, T4); a FAILED TOOL_SEARCH is a singleton by rule 3; splits further by rules 2–3; singletons are lists of one
      fun sentence(calls: List<ToolCall>): RowLabel           // rules 5–7: past tense, or the running form ending "…"; names only in a done reads-only group of ≤ MAX_NAMES distinct names; a running group always counts; reads only kind, state and subject (distinct names = distinct subjects; the Bash family from subject.substringBefore(' '))
      fun stats(calls: List<ToolCall>): DiffStats?            // sum over EDIT/CREATE calls, null stats skipped; null when none has stats or any call is PENDING/RUNNING/WAITING
      fun state(calls: List<ToolCall>): ToolState             // rule 8
      const val MAX_NAMES = 2
  }
  ```
  The builder emits one `ToolRow` per fold result: a single call keeps the per-tool label; ≥ 2 calls get `sentence`, `Grouping.stats` and `Grouping.state`, key `tool:<first call id>`. `ToolKind` families for Bash count nouns: `cat`/`head`/`tail` → read, `ls`/`find`/`tree` → find, `grep`/`rg`/`ag` → search (constants in `Grouping.kt`).

- [ ] **Step 1: Write the failing tests** (`GroupingTest`; hand-made `ToolCall`s built through `ToolLabels.label`, with `subject` set as the builder sets it, so the sentence reads like production):

```kotlin
`Read a file, found files, searched for a pattern`     // tools-read's four calls (Read, Read :10-14, Glob, Grep) → sentence(...).text == "Read a file, found files, searched for a pattern"
`Read a file, edited a file, created path +3 -1`       // spec: `Read a file, edited a file, created <path> +3 -1`; tools-edit's three calls → sentence(...).text == "Read a file, edited a file, created <path>" and Grouping.stats(...) == DiffStats(3, 1)
`Found files, searched for a pattern`                  // Bash find + Bash grep -rn (tools-read-unavailable:73/82) → "Found files, searched for a pattern"
`Read 2 files, searched for a pattern`                 // Read a, Read b, Grep → not "Read a, b, searched …"
`Read 4 files, searched for 2 patterns`                // Read a, b, c, d + Grep p, q (all DONE) → "Read 4 files, searched for 2 patterns"
`Edited 2 files, created d dot kt +12 -4`              // spec: `Edited 2 files, created d.kt +12 -4`; Edit a (stats 5, 3), Edit b (stats 5, 1), Write create with subject "d.kt" (stats 2, 0), all DONE → sentence(...).text == "Edited 2 files, created d.kt" and Grouping.stats(...) == DiffStats(12, 4)
`Used 3 tools`                                         // three MCP calls (mcp__github__list_issues, distinct ids, DONE) → "Used 3 tools"
`Ran a, Failed to run b, Ran c`                        // 4.6.5's `Ran a › · Failed to run b › · Ran c ›`: fold(Bash a DONE, Bash b FAILED, Bash c DONE) == [[a], [b], [c]], labels "Ran a", "Failed to run b", "Ran c"
`Reading x`                                            // 4.6.5's running row: fold(listOf(Read x RUNNING)) == [[x]], label "Reading x", Grouping.state(listOf(x)) == RUNNING
`a text block closes a group`                          // builder over a hand-made turn: UserSent, then Assistant[ToolUse Read /p/a.kt, ToolUse Read /p/b.kt, Text "x", ToolUse Read /p/c.kt, ToolUse Read /p/d.kt], four ok results, cwd "/p" → ToolRow "Read a.kt, b.kt", TextRow "x", ToolRow "Read c.kt, d.kt" (never "Read 4 files")
`a thinking block closes a group`                      // the same with Thinking("t", durationMs = 1000L) in place of the Text → ToolRow "Read a.kt, b.kt", ThinkingRow, ToolRow "Read c.kt, d.kt"; with showThinking = false the same two ToolRows, same keys, no ThinkingRow
`a failed call stands alone`                           // Read a DONE, Read b FAILED, Read c DONE, Read d DONE → fold [[a], [b], [c, d]], labels "Read a", "Failed to read b", "Read c, d"
`a denied call stands alone`                           // Bash a DONE, Bash b DENIED, Bash c DONE → fold [[a], [b], [c]], labels "Ran a", "Run b · Denied", "Ran c"
`an interrupted call stands alone`                     // Read a DONE, Read b INTERRUPTED, Read c DONE, Read d DONE → fold [[a], [b], [c, d]], labels "Read a", "Read b · Interrupted", "Read c, d"
`agents never fold or absorb`                          // Read, Agent, Read → fold gives [[read1], [agent], [read2]]; the Agent row's label stays "Ran an agent: <d>"
`hidden tool search is not counted`                    // through the builder, a hand-made turn [ToolUse Read a, ToolUse ToolSearch, ToolUse Grep p] with Read and Grep DONE: with ToolSearch DONE, and again with ToolSearch RUNNING (no result, turn open) → one ToolRow "Read a file, searched for a pattern" of two calls, keyed tool:<the Read id>; with ToolSearch FAILED → three rows, the middle "Failed to load tools" (rule 3)
`enter plan mode never joins a group`                  // Read, EnterPlanMode, Read → three rows; the middle one "Entered plan mode"
`a running group uses the participle and ends with an ellipsis`   // Read a DONE + Read b RUNNING → "Reading 2 files…"; Bash DONE + Edit RUNNING → "Ran a command, editing a file…"; Grouping.state(...) == RUNNING; Grouping.stats(...) == null
`stats sum across a group`                             // Grouping.stats(Edit +1 -1, Write create +2 -0) == DiffStats(3, 1)
`bash cat, find and grep count as read, find and search`   // Bash "cat x", Bash "find .", Bash "grep -rn y" (all DONE) → "Read a file, found files, searched for a pattern"; Bash "cat x", Bash "cat y" → "Read 2 files" (a Bash read counts per call)
`a single call is not a group`                         // fold(listOf(x)) == listOf(listOf(x)); the builder keeps the per-tool label
`reads use names only in a reads-only group`           // Read a, Read b → "Read a, b"; Read a, Read b, Read c → "Read 3 files"
`missing stats are skipped in the sum`                 // Review Focus 4: Grouping.stats(Edit with stats null, Write create +2 -0) == DiffStats(2, 0); two Edits with null stats → null
`Ran echo to stdout and stderr, Failed to run failing command with exit code 3`   // tools-bash's two calls (:18 DONE, :34 FAILED) → fold == [[ok], [fail]]
`Failed to use Write, Plan approved`                   // plan-disallowed's Write FAILED and ExitPlanMode DONE → fold == [[write], [exit]]
`questions and plans never join a group`               // rule 2: Read a, AskUserQuestion, Read b → [[a], [ask], [b]]; Read a, ExitPlanMode, Read b → [[a], [exit], [b]]
// the permission, ask-single and agent-sync rows of 4.6.5 are pinned by T4's ChatRowsBuilderTest goldens of the same name
// ChatRowsBuilderTest additions (grouped goldens of 4.6.5; the T4 content() helper)
`tools-read` → one ToolRow "Read a file, found files, searched for a pattern" DONE with calls.map { it.label.text } == ["Read <path>", "Read <path>:10-14", "Found 1 file", "Searched for seven · 1 match"], then the TextRow
`tools-edit` → "Read a file, edited a file, created <path>" with stats DiffStats(3, 1)
`tools-read-unavailable` → content() == [UserRow, ToolRow "Read <path>" DONE with 2 calls (:18 and :36; one distinct name, rule 5's reads-only naming), ToolRow "Failed to use Glob" FAILED, ThinkingRow, ToolRow "Found files, searched for a pattern" DONE, TextRow "done."]
`showThinking false drops thinking rows without changing groups`   // re-assert on tools-read-unavailable: the three ToolRows keep their keys and labels, with no ThinkingRow
`keys are unique and stable across every state of every fixture`   // unchanged test, now exercising the 5.3 exception (a failed call leaving its group)
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.GroupingTest' --tests 'dev.daarkolabs.tandem.chat.ChatRowsBuilderTest'` → compilation errors / golden failures.
- [ ] **Step 3: Implement** `Grouping` (rules 1–9 of 4.6.4 as written) and wire `fold`/`sentence` into the builder.
- [ ] **Step 4: Run** the package, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/chat && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(chat): group consecutive tool calls into one summary row"`

---

### Task T6: chat-working-cards-follow

**Depends on:** T4, T5.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/chat/WorkingIndicator.kt`, `.../chat/Verbs.kt`, `.../chat/FollowState.kt`, `.../chat/ComposerModel.kt`
- Modify: `.../chat/Cards.kt` (the card types and `Cards`), `.../chat/ChatRowsBuilder.kt` (appends `WorkingRow` / `TurnEndRow`, fills `card` / `moreWaiting`)
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/chat/WorkingIndicatorTest.kt`, `.../chat/CardsTest.kt`, `.../chat/FollowStateTest.kt`, `.../chat/ComposerModelTest.kt`; Modify `.../chat/ChatRowsBuilderTest.kt`

**Interfaces:**
- Consumes: T4's rows, `PendingCard`, `Text.kt` formats; T2's `TurnProgress`, `TaskInfo`, `TurnEnded`, `PendingPermission`.
- Produces (spec 5.4, copied; slice B consumes `Cards`, `PermissionCard`, `QuestionCard`, `PlanCard`, `Payload`, `Pick`; T9 consumes `FollowState`, `ScrollEvent`, `ComposerModel`; T8 consumes `WorkingIndicator.view`):
  ```kotlin
  sealed interface PendingCard { val requestId: String; val toolUseId: String?; val agentDescription: String? }
  data class PermissionCard(override val requestId: String, override val toolUseId: String?, override val agentDescription: String?, val verb: String, val objectText: String, val description: String?, val payload: Payload, val canAlwaysAllow: Boolean) : PendingCard
  sealed interface Payload { data class Mono(val text: String) : Payload; data class OldNew(val path: String, val old: String, val new: String) : Payload; data class Json(val text: String) : Payload; data object None : Payload }
  data class QuestionCard(override val requestId: String, override val toolUseId: String?, override val agentDescription: String?, val questions: ImmutableList<Question>) : PendingCard
  data class Question(val text: String, val header: String, val multiSelect: Boolean, val options: ImmutableList<Option>); data class Option(val label: String, val description: String?)
  data class PlanCard(override val requestId: String, override val toolUseId: String?, override val agentDescription: String?, val plan: String?, val source: PlanSource, val planFilePath: String?) : PendingCard
  enum class PlanSource { EXIT_PLAN_INPUT, PLAN_FILE_DRAFT, ASSISTANT_TEXT, NONE }
  sealed interface Pick { data class Options(val indices: List<Int>) : Pick; data class Other(val text: String) : Pick }
  object Cards { fun pending(state: ChatState): Pair<PendingCard?, Int>; fun permission(p: PendingPermission, cwd: String?): PermissionCard; fun question(p: PendingPermission): QuestionCard?; fun plan(p: PendingPermission, state: ChatState): PlanCard; fun planText(state: ChatState, p: PendingPermission): Pair<String?, PlanSource>; fun answers(card: QuestionCard, picks: Map<Int, Pick>): Map<String, String>; fun sourceLine(card: PlanCard): String }   // sourceLine: the five 4.14 strings (slice B's T12 renders it; not in the spec's signature list)
  object WorkingIndicator {
      val VERBS: List<String>                                   // = SPINNER_VERBS of Verbs.kt (appendix A, 90 words, alphabetical, with its provenance line)
      fun verb(turnUuid: String, lastToolUseId: String?): String   // VERBS[abs((turnUuid + (lastToolUseId ?: "")).hashCode() % VERBS.size)]: the modulo before abs, so Int.MIN_VALUE never yields a negative index (build never throws); the same verb as spec 4.9's abs(h) % size for every other hash
      fun step(state: ChatState, rows: List<ChatRow>, options: RowOptions): String
      enum class Mode { RUNNING, WAITING, NOT_RESPONDING }; data class View(val mode: Mode, val animated: Boolean, val text: String, val accessibleLabel: String)
      fun view(row: WorkingRow, nowMillis: Long, lastFrameAtMillis: Long, reducedMotion: Boolean = false, notRespondingAfterMs: Long = 60_000): View
      fun formatElapsed(ms: Long): String; fun formatTokens(n: Long): String; fun formatDuration(ms: Long): String   // delegate to Text.kt package-qualified, e.g. fun formatTokens(n: Long): String = dev.daarkolabs.tandem.chat.formatTokens(n) (an unqualified call resolves to the member and recurses forever)
      fun footer(end: TurnEnd, durationMs: Long?, error: String?, textStreamed: Boolean): String
  }
  data class FollowState(val following: Boolean = true, val unseen: Int = 0) { fun on(event: ScrollEvent): FollowState; val buttonText: String? }
  sealed interface ScrollEvent { WheelUp; ThumbDragUp; ReachedBottom; Appended(val rows: Int); Sent; Jump; Resized }
  data class ComposerModel(val busy: Boolean, val ended: Boolean, val cardOpen: Boolean, val models: ImmutableList<ModelOption>, val requestedModel: String?, val activeModel: String?, val modelWarning: String?, val permissionMode: PermissionMode?, val effort: String?, val claudeCodeVersion: String?); fun composerModel(state: ChatState): ComposerModel   // cardOpen = state.pendingPermissions.isNotEmpty(); models = state.models.toImmutableList(); the other fields copy ChatState's
  ```
  View texts (4.9): `"<Step>… · <elapsed>"` + `" · <n> tokens"` when `row.outputTokens > 0`; `"Waiting for you · <elapsed>"`; `"Not responding · <elapsed>"`; accessible labels `Claude is working` / `Claude is waiting for you` / `Claude is not responding`. `WorkingIndicator.step` takes, in order: the description of `state.tasks.values.lastOrNull { (it.status == null || it.status == "running") && it.lastToolName != null && it.description != null }` through `ToolLabels.middleTruncate(description, 60)` (T2 stores only local_agent and subagent tasks, and task_started/task_progress carry no status; `lastToolName` is set only by task_progress, so a task's description shows only once a task_progress has arrived, T2 keeping it at the latest one; before that the verb); `Thinking` for a streaming thinking block when `showThinking = false`; else `verb(turn.userUuid, turn.lastToolUseId)`.
  Footer (4.10): `Worked for <formatElapsed(durationMs)>`, and `Worked for 0s` for COMPLETED with `durationMs == null`; `Interrupted · What should Claude do instead?`; FAILED: `textStreamed` → `Server error mid-response. The response above may be incomplete.`; else `error` when it starts with `API Error: `; else `"The turn failed ($error)"` when `error != null`; else `"The turn failed"`. `textStreamed` = the turn has a non-blank `AssistantBlock.Text` in a main-thread Assistant entry. `TurnEnded.error` stays raw (T2), so the footer alone formats it.
  Builder: while `state.turn != null` the last row is `WorkingRow(turnUuid, startedAtMillis, step, outputTokens = turn.outputTokens + turn.thinkingEstimate, waiting = pendingPermissions.isNotEmpty())`. A `TurnEndRow("e:<id>", end, footer(...))` is emitted only when `state.turn == null` and the latest `TurnEnded` entry (else the latest `NoticeKind.INTERRUPTED` notice, for a reloaded transcript with no `result` line) lies after the last main-thread `User` entry; it sits at that entry's position in entry order, so a process exit's ERROR notice, which 5.2 appends after its `TurnEnded`, follows the footer as 4.10 says, and in every other recorded turn the footer is the last row. Older turns keep no footer. In a turn whose `TurnEnded` is FAILED, the ERROR `NoticeRow` whose text starts with `The turn failed` is not emitted, because the footer carries it (4.11); every other notice stays (the model-rejection line, the process-exit line).
  Cards: `Cards.pending` returns `(null, 0)` when `pendingPermissions` is empty, else `(card, count - 1)`, typing the first pending permission by `toolName` (`AskUserQuestion` → `question(p) ?: permission(p, cwd)`, `ExitPlanMode` → `plan`, else `permission`) and then setting `agentDescription` on the typed card (a `copy`): the `input.description` of the main-thread Agent/Task `ToolUse` whose id == `state.tasks[p.agentId]?.toolUseId` (`Count lines in seed.txt` at agent-sync:43; `agent_id` equals `task_id`), `null` without `agentId`; `permission`/`question`/`plan` called directly leave it `null`. Card titles per 4.12 as (`verb`, `objectText`), `objectText` is never empty (slice B's `permissionTitle` always puts one space between verb and object): Bash, PowerShell `Run` / the command; Read `Read` / `relativePath(file_path, cwd)`; Edit, NotebookEdit, `mcp__tandem__edit*` `Edit` / `<name>`; Write `Create` / `<name>` (a request cannot tell an update), where `<name>` = `relativePath(input.file_path ?: input.notebook_path, cwd)`, untruncated; WebFetch `Fetch` / `middleTruncate(url, 80)`; WebSearch `Search the web for` / `"<query>"`; Agent, Task `Run` / `an agent` (slice B's T10 draws `an agent` in the 4.2 mono style like every other object); MCP `Use` / `<server>: <tool>`; AskUserQuestion, ExitPlanMode `Use` / `p.toolName` (the raw wire name: spec 4.13, 8 fix `Allow Claude to Use AskUserQuestion?` for the malformed-question fallback); anything else `Use` / `displayName(toolName)`. `description = p.description` (B's T10 hides it when it equals `objectText`); `canAlwaysAllow = sessionPermissionUpdates(p.suggestions).isNotEmpty()`. `PlanCard.planFilePath` = `input.planFilePath` (a string) for EXIT_PLAN_INPUT; the failed Write's `input.file_path` for PLAN_FILE_DRAFT; `null` for ASSISTANT_TEXT and NONE. `sourceLine` takes the basename of `planFilePath` for both file sources.

- [ ] **Step 1: Write the failing tests** (names from spec 10.3, values as given there):

```kotlin
// WorkingIndicatorTest
`verbs are the appendix list`                          // VERBS.size == 90 && VERBS == VERBS.sorted() && "Pondering" in VERBS
`verb is stable per turn and re-rolls per tool call`   // verb("u1", null) == verb("u1", null); verb("u1", "t1") != verb("u1", null) for the fixture ids used (pick ids that differ)
`step prefers the agent progress then hidden thinking then the verb`   // agent-sync replayed (T4's replay) through line 37 (task_started only) → a VERBS entry; through line 41 (the TaskInfo has status == null, lastToolName "Bash") → "Running wc -l seed.txt && tail -c1 seed.txt | xxd"; a streaming thinking block with showThinking = false → "Thinking"; else a VERBS entry
`step text never repeats the row above`                 // over every state of tools-read, agent-sync and think-default the step != the last live row's label text
`formatDuration one decimal under ten seconds`          // 5826 → "5.8s"; 2006 → "2.0s"; 12_000 → "12s"
`text omits tokens until known`                         // outputTokens 0 → no " · " tokens suffix; 1200 → ends with " · 1.2k tokens"
`waiting while a card is pending`                       // waiting = true → Mode.WAITING, "Waiting for you · 3s"
`not responding after 60 s without a frame and back on the next`   // silence 60_000 → NOT_RESPONDING; a later lastFrameAt → RUNNING
`silence is measured from the send until the first frame arrives`   // startedAt = now − 5_000, lastFrame = now − 600_000 → RUNNING; startedAt = now − 61_000 → NOT_RESPONDING
`not animated under reduced motion`                     // reducedMotion = true → animated == false while RUNNING
`formatElapsed seconds minutes hours`                   // 4_000 → "4s"; 2_591_000 → "43m 11s"; 3_660_000 → "1h 1m"
`formatTokens k and m`                                  // 312 → "312"; 1_200 → "1.2k"; 1_500_000 → "1.5m"
`footer strings`                                        // (COMPLETED, 4_000) → "Worked for 4s"; footer(COMPLETED, null, null, false) == "Worked for 0s"; INTERRUPTED → "Interrupted · What should Claude do instead?"
`FAILED mid-response`                                   // textStreamed = true → "Server error mid-response. The response above may be incomplete."
`FAILED before text`                                    // footer(FAILED, null, "API Error: 404 model_not_found", false) == "API Error: 404 model_not_found"; footer(FAILED, null, "boom", false) == "The turn failed (boom)"; footer(FAILED, null, null, false) == "The turn failed"
// CardsTest (names of 10.3)
`pending picks the first request and counts the rest`   // a hand-made state with two pending Bash permissions → (Cards.permission(first, cwd), 1); no pending permission → (null, 0)
`permission titles per tool`                             // (verb, objectText): tools-bash:20 → ("Run", input.command); misc-web:31 → ("Fetch", middleTruncate(url, 80)); :74 → ("Search the web for", "\"" + query + "\""); hand-made Read → ("Read", "<rel path>"); Edit → ("Edit", name); Write → ("Create", name); Agent → ("Run", "an agent"); "mcp__github__list_issues" → ("Use", "github: list issues"); "SomeNewTool" → ("Use", "Some New Tool"); Cards.permission(<permission.ndjson:21>, cwd).description == "Create empty fixture file"
`always allow only with addRules`                        // permission.ndjson:21 true; ask-single:22 false
`payload per tool`                                       // tools-bash:20 → Payload.Mono(input.command); a Read → Mono(file_path); an Edit → OldNew(file_path, old_string, new_string); a Write create → Mono(content); misc-web:31 → Mono(url + "\n" + prompt); misc-web:74 → Mono(query); an Agent → Mono(description + "\n" + prompt); an MCP tool with a "query" input → Mono(query); an unknown tool with no string input of 4.12 item 4 → Json(pretty input); an empty input → Payload.None
`agent description from agent_id`                        // Cards.pending(agent-sync replayed through line 43).first!!.agentDescription == "Count lines in seed.txt"
`question card from ask-single`                          // ask-single:22 → questions == [Question("Which colour?", "Colour", false, [Option("Red", "Red"), Option("Blue", "Blue")])]
`question card from ask-multi is multi-select`           // ask-multi:27 → questions.single().multiSelect == true, option labels [Cheese, Olives, Ham]
`question card with two questions keeps both`            // a hand-made two-question input → questions.size == 2, in input order
`a malformed question input falls back to the permission card`   // ask-single:22 with input {"questions": "x"} → question(p) == null and Cards.pending(...).first is PermissionCard with (verb, objectText) == ("Use", "AskUserQuestion")
`answers join multi-select labels in option order`       // Cards.answers(<ask-multi:27 card>, mapOf(0 to Pick.Options(listOf(2, 0)))) == mapOf("Which toppings would you like?" to "Cheese, Ham")
`answers use the Other text`                             // answers(single, mapOf(0 to Pick.Other(" Green "))) == mapOf("Which colour?" to "Green")
`plan from the request input`                            // plan-approve:88 → EXIT_PLAN_INPUT
`plan from the failed plan-file write`                   // plan-disallowed with line 102's file_path patched to "/home/u/.claude/plans/x.md" → PLAN_FILE_DRAFT, plan == that block's input.content
`a write outside a plans directory is not the plan`      // unpatched → NONE
`plan from the last assistant text`                      // a hand-made turn [Text("# Plan\n- a"), ToolUse(ExitPlanMode, {})] with a pending ExitPlanMode {} request → ("# Plan\n- a", ASSISTANT_TEXT)
`plan none`                                              // the same turn without the Text → (null, NONE)
`plan card says where the plan came from`                // Cards.sourceLine: plan-approve:88 → "From the plan file <path>" (the basename of the redacted planFilePath is "<path>"); EXIT_PLAN_INPUT without planFilePath → "From the plan Claude sent"; the patched draft → "From Claude's draft of x.md (the plan file was not saved)"; ASSISTANT_TEXT → "From Claude's last message (no plan file)"; NONE → "Claude sent no plan text."
`open plan file is offered only with a plan file path`   // plan-approve:88 → source == EXIT_PLAN_INPUT && planFilePath != null; patched plan-disallowed draft → source == PLAN_FILE_DRAFT && planFilePath == "/home/u/.claude/plans/x.md" (B's planFileToOpen returns null for it); unpatched → planFilePath == null
// FollowStateTest: one test per 4.16 table row
`following and WheelUp pauses`                          // FollowState().on(WheelUp) == FollowState(false, 0)
`following and Appended stays following`                // FollowState().on(Appended(3)) == FollowState()
`paused and Appended counts unseen`                     // FollowState(false, 2).on(Appended(3)) == FollowState(false, 5)
`paused and Jump resumes`                               // FollowState(false, 4).on(Jump) == FollowState(true, 0)
`Sent always follows`                                   // FollowState(false, 4).on(Sent) == FollowState(true, 0); FollowState().on(Sent) == FollowState()
// plus (ThumbDragUp, ReachedBottom and Resized rows)
`a wheel down that lands on the bottom resumes`         // FollowState(false, 3).on(ReachedBottom) == FollowState(true, 0)
`a thumb drag up pauses like a wheel up`                // FollowState().on(ThumbDragUp) == FollowState(false, 0)
`a resize while following pins, while paused does nothing`   // FollowState().on(Resized) == FollowState(); FollowState(false, 2).on(Resized) == FollowState(false, 2)
`button text counts unseen`                             // FollowState().buttonText == null; FollowState(false, 0).buttonText == "↓"; FollowState(false, 3).buttonText == "↓ 3 new"
`events in the wrong state change nothing`              // Review Focus 5: FollowState().on(ReachedBottom) == FollowState(); FollowState().on(Jump) == FollowState(); FollowState(false, 2).on(Appended(0)) == FollowState(false, 2)
// ComposerModelTest
`composer model changes only on its own fields`         // two plain-reply states differing by one text_delta give equal ComposerModels
// ChatRowsBuilderTest additions
`the working row is last while busy and the footer replaces it`   // tools-read: after UserSent the last row is WorkingRow(step non-blank, waiting == false); after result no WorkingRow and the last row is TurnEndRow(COMPLETED, text.startsWith("Worked for "))
`a failed turn ends with the failure footer`            // invalid-model → no NoticeRow whose text starts with "The turn failed", one NoticeRow(ERROR) whose text starts with "The CLI rejected the model", and the last row is TurnEndRow(FAILED, "API Error: 404 model_not_found"); T4's `invalid-model` golden is updated to match
`no footer while a later turn runs`                     // tools-read replayed, then UserSent("u2", "again"): no TurnEndRow, the last row is the WorkingRow
`a reloaded transcript has neither working row nor footer`         // history-reply.jsonl through TranscriptReader
`a reloaded interrupted transcript shows the Interrupted footer`   // history-interrupt.jsonl → TurnEndRow(INTERRUPTED) and no NoticeRow "Stopped."
`interrupt`                                             // T4's golden, extended (7.6: one footer): the last row is TurnEndRow(INTERRUPTED, "Interrupted · What should Claude do instead?"), exactly one TurnEndRow, no WorkingRow and no NoticeRow
`gaps follow the previous row`                          // add: WorkingRow and TurnEndRow carry SECTION, also a WorkingRow right after a UserRow
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.*'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block; `Verbs.kt` starts with the appendix A provenance comment verbatim; `Cards.planText` follows the 4.14 table; `Cards.pending` as in the Interfaces (`(null, 0)` when nothing is pending, `agentDescription` set there).
- [ ] **Step 4: Run** the package, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`Verbs.kt` is data; if it drags `chat/` under 90 %, the appendix test already executes it).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/chat && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(chat): working indicator, turn footer, card presenters and follow state"`

---

### Task T7: ui-list-and-rows

**Depends on:** T3, T4, T5, T6.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/dev/RecompositionCounter.kt` (`internal object RecompositionCounter { fun hit(key: String) }`: counts per row key and logs `TANDEM-SPIKE recompose key=<key> n=<n>` at most once a second, never row text; spec 9.4, debug only, never a test), `src/main/kotlin/dev/daarkolabs/tandem/ui/ChatPresenter.kt`, `.../ui/ChatPresenters.kt`, `.../ui/ChatUiState.kt`, `.../ui/Modifiers.kt`, `.../ui/TandemColors.kt`, `.../ui/ChatPalette.kt`, `.../ui/rows/UserBubble.kt`, `.../ui/rows/TextRowItem.kt`, `.../ui/rows/ToolRowItem.kt`, `.../ui/rows/ToolGroupBox.kt`, `.../ui/rows/ToolBodyView.kt`, `.../ui/rows/NoticeItem.kt`, `.../ui/rows/TurnEndItem.kt`
- Modify: `.../ui/ChatPanel.kt`, `.../ui/ChatScreen.kt`, `.../ui/TandemToolWindow.kt`, `.../ui/SessionHeader.kt` (the status line drops `working…`), `.../ui/TandemToolWindowFactory.kt` and `.../ui/dev/RenderingSpike.kt` + `FakeTranscript.kt` (the spike feeds `ChatState`s through `reduce` into a `MutableStateFlow<ChatState>` read by a `ChatPresenter`: `FakeTranscript` emits, per turn, `UserSent(id, prompt, now)`, `Cli(StreamEvent(MessageStart))`, `Cli(StreamEvent(ContentBlockStart(0, Text(""))))`, one `Cli(StreamEvent(ContentBlockDelta(0, Delta.Text(chunk))))` per chunk, then `Cli(Result(success))`, each `raw` an empty `JsonObject`; `TranscriptEntry` goes), `.../ide/TandemSessions.kt` (`private val _rowOptions = MutableStateFlow(RowOptions(showThinking = true))`; `val rowOptions: StateFlow<RowOptions> = _rowOptions.asStateFlow()`; T8 wires the setting)
- Delete: `.../ui/ChatRows.kt`, `.../ui/ChatRowsPresenters.kt`, `.../ui/ChatTranscript.kt`, `src/test/kotlin/dev/daarkolabs/tandem/ui/ChatTranscriptTest.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/ui/ChatUiStateTest.kt`, `.../ui/ChatPresenterTest.kt`, `.../ui/ToolRowItemTest.kt`; Modify `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`

**Interfaces:**
- Consumes: T4–T6's `ChatRowsBuilder`, `ChatRows`, every `ChatRow` type, `ToolBody`, `PendingCard`, `FollowState`, `composerModel`; T3's `SessionHandle.lastFrameAtMillis`.
- Produces (spec 5.6; slice B hooks: `ChatPanel`'s `RenderedChat.card` and `ChatUiState.onExpand`; T8/T9 consume `ChatUiState`, `RowItem`, `ChatPresenters`):
  ```kotlin
  internal data class RenderedRow(val row: ChatRow, val blocks: ImmutableList<MarkdownBlock>?, val bodies: ImmutableMap<String, ImmutableList<MarkdownBlock>> = persistentMapOf())
  internal data class RenderedChat(val rows: ImmutableList<RenderedRow>, val card: PendingCard?, val moreWaiting: Int, val planBlocks: ImmutableList<MarkdownBlock>?)
  internal class ChatPresenter(scope: CoroutineScope, state: StateFlow<ChatState>, options: StateFlow<RowOptions>, parse: ((String) -> ImmutableList<MarkdownBlock>)? = null) { private val processor = MarkdownProcessor(listOf(AutolinkProcessorExtension)); private val parseMarkdown = parse ?: { processor.processMarkdownDocument(it).toImmutableList() }; val chat: StateFlow<RenderedChat>; val composer: StateFlow<ComposerModel>; fun expandBody(key: String) }   // one processor with today's autolink extension (ui/ChatRows.kt:32), so bare URLs stay links (4.0, 4.4); parseMarkdown runs on Dispatchers.Default and `parse` is injectable so the test can observe the thread and count calls (C's T14 replaces `processor` with `tandemMarkdownProcessor()`); 5.6 verbatim otherwise (the spec's `handle: SessionHandle` parameter is its `state` flow, so the spike and the test feed a MutableStateFlow<ChatState>); a new RowOptions value replaces the builder and keeps the Markdown cache; RenderedChat.planBlocks = (card as? PlanCard)?.plan parsed through the same cache under the key "plan:<requestId>" (slice B's T12 renders it)
  internal class ChatUiState { fun expanded(key: String): MutableState<Boolean>; var onExpand: (String) -> Unit; val follow: MutableState<FollowState>; val draft: TextFieldState; val listState: LazyListState }
  internal class ChatPresenters(scope: CoroutineScope, options: StateFlow<RowOptions>) { fun presenter(handle: SessionHandle): ChatPresenter; fun ui(id: SessionId): ChatUiState; fun retain(open: List<SessionHandle>) }   // same lifecycle as ChatRowsPresenters; ui states survive until retain drops the session
  @Composable internal fun rememberChatPresenters(open: List<SessionHandle>, options: StateFlow<RowOptions>): ChatPresenters
  @Composable internal fun ChatPane(project, handle, manager, workspace, presenter: ChatPresenter, ui: ChatUiState, focusPrompt, onPromptFocused)   // emptyText from presenter.composer.ended; the legacy PermissionPrompt stays, fed by handle.state.map { it.pendingPermissions }.distinctUntilChanged()
  @Composable internal fun ChatPanel(project: Project, presenter: ChatPresenter, ui: ChatUiState, emptyText: String, lastFrameAt: StateFlow<Long>)   // VerticallyScrollableContainer(ui.listState) { LazyColumn(items keyed by row.key, contentType = row::class, contentPadding 16 dp × 12 dp) }; keeps today's ProvideMarkdownStyling(project) around the list; no autoscroll yet (T9); ChatPane passes handle.lastFrameAtMillis
  @Composable internal fun RowItem(rendered: RenderedRow, ui: ChatUiState, lastFrameAt: StateFlow<Long>)   // lives in ChatPanel.kt; dispatch: UserBubble, TextRowItem, ToolRowItem, NoticeItem, TurnEndItem; ThinkingRow and WorkingRow render as one secondary Text (row.label / row.step) until T8; lastFrameAt is forwarded to WorkingRowItem only (T8)
  // ui/rows/, signatures fixed for slices B and C:
  @Composable internal fun UserBubble(row: UserRow)
  @Composable internal fun TextRowItem(rendered: RenderedRow)   // reads rendered.blocks and (rendered.row as TextRow).markdown
  @Composable internal fun ToolRowItem(rendered: RenderedRow, ui: ChatUiState)
  // ToolRowItem.kt also holds: internal fun annotated(label: RowLabel, mono: SpanStyle): AnnotatedString (ToolGroupBox uses it for sub-rows, 4.6.1 rule 9); @Composable private fun DiffStatsText(stats: DiffStats)   // `+A` in chatPalette().added, ` -B` in removed, the zero side shown
  internal fun canExpand(row: ToolRow): Boolean = row.state !in setOf(ToolState.PENDING, ToolState.RUNNING, ToolState.WAITING) && (row.calls.size > 1 || row.calls.single().body !is ToolBody.None)
  // replaces the spec 6 sketch's canExpand: a running lone row or group draws the chevron in text.disabled with no click (4.6.1, 4.6.4 rule 7) and becomes clickable once DONE; C's T13 extends this function; ToolGroupBox's sub-rows apply the same rule per call
  @Composable internal fun ToolGroupBox(calls: ImmutableList<ToolCall>, bodies: ImmutableMap<String, ImmutableList<MarkdownBlock>>, ui: ChatUiState)
  @Composable internal fun ToolBodyView(call: ToolCall, blocks: ImmutableList<MarkdownBlock>?, ui: ChatUiState)
  @Composable internal fun NoticeItem(row: NoticeRow)
  @Composable internal fun TurnEndItem(row: TurnEndRow)
  fun Modifier.chatColumn(): Modifier; fun Modifier.bottomFade(height: Dp): Modifier   // 4.1 verbatim; 4.3 with `height` as the fade band (Global Constraints, Deviations)
  object TandemColors { val spark = Color(0xFFD97757) }
  internal data class ChatPalette(val bubbleFill: Color, val boxFill: Color, val added: Color, val removed: Color, val insertedFill: Color, val deletedFill: Color); @Composable internal fun chatPalette(): ChatPalette   // the 4.2 tokens and fallbacks under remember(JewelTheme.instanceUuid); slice B's T10 uses insertedFill / deletedFill
  // ToolWindowIdeTest gains private fun Driver.toolRow(label: String): UiComponent = ui.x("//div[@class='ComposeNode' and @contentdescription='$label']") next to iconButton (spec 10.6; slice B's T10 and C's T16 call toolRow(label).present())
  ```
  Composables follow the spec 6 sketches `ToolRowItem` (semantics `role = Role.Button`, `contentDescription = row.label.text`, chevron rules of 4.6.1 through `canExpand(row)` above (not the sketch's expression), `ui.onExpand(row.key)` for Markdown/Links bodies; the label is `Text(annotated(row.label, mono))` with `addStyle(mono, r.first, r.last + 1)` per span, then `DiffStatsText(row.stats)` before the chevron, the only place stats are drawn; hover = `chatPalette().bubbleFill.copy(alpha = 0.5f)`, radius 6 dp (4.6.1); a lone AGENT row draws `call.progress`, when non-null, as one secondary Text under the label at body − 1 (4.8: until T13 the Agent row shows its label and `Done (…)` sub-line; C's T13 extends it)), `ToolGroupBox` (1 dp `borders.normal`, radius 8 dp, no fill, 36 dp sub-rows, 1 dp dividers inset 8 dp; 4.6.1) and 4.3 (`UserBubble`: one `Text` in a `SelectionContainer`, `testTag("user-bubble")`, right-aligned, 85 % width), 4.4 (`TextRowItem`: `Markdown(blocks, markdown, Modifier.fillMaxWidth(), selectable = true)`), 4.7 (`ToolBodyView`: every `ToolBody` kind, 20-line clamp with `bottomFade` and `Show all N lines` `Link`, `… N more lines`, `Command` draws `$ <command>` in the box and the output on the page, `Image` → `[image W×H png]`; `Diff` → one mono box (box fill, 1 dp border, radius 6 dp) with one line per `DiffLine` of every hunk, prefixed `-` (REMOVED, on `chatPalette().deletedFill`), `+` (ADDED, on `insertedFill`) or a space (CONTEXT, no fill) (4.2's diff row fills; 4.6.2: 2a draws `-`/`+` lines, Phase 3 the real diff), under the same 20-line clamp; `Json` → a secondary `INPUT` line then the input in a mono box, and `OUTPUT` with its box only when `output != null`; `Pairs` → one `<first>: <second>` `Text` per pair), 4.10/4.19 (`TurnEndItem`: one `Text`, `testTag("turn-footer")`, FAILED in `text.error` with the ERROR icon), 4.11 (`NoticeItem` icons).

- [ ] **Step 1: Write the failing tests**

```kotlin
// ui/ChatUiStateTest
fun expansionIsKeptPerSessionKey()   // ChatPresenters.ui("a").expanded("tool:1").value = true; ui("b"); ui("a").expanded("tool:1") === the first MutableState and .value == true
// ui/ChatPresenterTest
`expanding a markdown body parses it once off the EDT and re-emits the row`   // state = MutableStateFlow(ChatState()); state.value = replay("misc-web").first (T4's Replay.kt); ChatPresenter(scope, state, options, parse = { text -> threads += Thread.currentThread().name; calls++; realParse(text) }); presenter.expandBody("tool:<the WebFetch call id>"); the next chat emission has rows.first { it.row.key == key }.bodies[key] != null; no recorded thread name starts with "AWT-EventQueue"; a second expandBody of the same key leaves `calls` unchanged and emits no new blocks instance
// ui/ToolRowItemTest (JUnit 5, pure)
`a running group cannot expand`   // canExpand: a two-call ToolRow in state RUNNING → false; the same in state DONE → true; a lone DONE call with ToolBody.None → false; a lone DENIED call with a Mono body → true
// ToolWindowIdeTest `a typed prompt is sent and the streamed reply is rendered`: after waitForText("pong"), waitFor("turn footer", 1.minutes) { ui.x("//div[@class='ComposeNode' and starts-with(@visible_text, 'Worked for')]").present() }
// ToolWindowIdeTest `Stop interrupts a running turn`: waitForText("Stopped.") becomes waitForText("Interrupted · What should Claude do instead?"); the permission test additionally waits with waitFor("tool row", 2.minutes) { toolRow("Ran Create empty fixture file").present() } after the first allow and waitFor("tool row", 2.minutes) { toolRow("Remove fixture file · Denied").present() } after the deny (the 4.6.2 Bash labels from permission.ndjson:19/50)
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.*'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the row composables; `RowItem` begins with `if (RenderingSpike.enabled) SideEffect { RecompositionCounter.hit(rendered.row.key) }` (spec 9.4; `RenderingSpike.enabled` reads `-Dtandem.spike=true`); delete the four files; `TandemToolWindow` uses `rememberChatPresenters(open, sessions.rowOptions)`, `presenters.presenter(selected)` and `presenters.ui(selected.id)` inside `key(selected.id)`.
- [ ] **Step 4: Run** `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` then `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → all PASS (the permission test still finds `Allow Bash?`; `pong`, `done.`, `denied.` stay exact paragraph nodes). On a locator failure read `out/ide-tests/.../ui-hierarchy/ui.html` and fix the semantics, not the test.
- [ ] **Step 5: Commit** `git add -A src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/ide/TandemSessions.kt src/test/kotlin/dev/daarkolabs/tandem/ui src/integrationTest && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(ui): typed chat rows, bubble and tool rows on a keyed list"`

---

### Task T8: ui-working-thinking

**Depends on:** T3, T6, T7.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/rows/WorkingRowItem.kt`, `.../ui/rows/Spark.kt`, `.../ui/rows/ThinkingRowItem.kt`
- Modify: `.../ui/ChatPanel.kt` (`RowItem` dispatch only; T7 already threads `lastFrameAt` through `ChatPanel` and `RowItem`), `.../ide/TandemSettings.kt`, `.../ide/TandemSettingsConfigurable.kt`, `.../ide/TandemSessions.kt`
- Test: Modify `src/test/kotlin/dev/daarkolabs/tandem/ide/TandemSettingsTest.kt`, `.../ide/TandemSessionsTest.kt`

**Interfaces:**
- Consumes: T6 `WorkingIndicator.view`, `WorkingRow`, `ThinkingRow`; T7 `ChatUiState.expanded`, `ChatPanel(project, presenter, ui, emptyText, lastFrameAt: StateFlow<Long>)` and `RowItem(rendered, ui, lastFrameAt: StateFlow<Long>)` (unchanged here; `RowItem` forwards `lastFrameAt` to `WorkingRowItem` only); T3 `SessionHandle.lastFrameAtMillis` (passed by `ChatPane` since T7).
- Produces:
  ```kotlin
  @Composable internal fun WorkingRowItem(row: WorkingRow, lastFrameAt: StateFlow<Long>)   // spec 6 sketch verbatim: 1 s ticker, view(row, now, lastFrame, reducedMotion()), semantics contentDescription = view.accessibleLabel, testTag("working-row"), one Text, text at body − 1
  @Composable internal fun Spark(animated: Boolean, tint: Color)                            // Spark.kt, with `private val GLYPHS` in the same file: the GLYPHS cycle of spec 6, 120 ms, index 4 ("✻") when static
  @Composable internal fun reducedMotion(): Boolean                                          // WorkingRowItem.kt: !UISettings.getInstance().animateWindows (open item 5)
  @Composable internal fun ThinkingRowItem(row: ThinkingRow, ui: ChatUiState)                // 4.5: label in text.info, chevron only when summary.isNotBlank(), expanded = ui.expanded(row.key), body = summary split on "\n\n" as Text paragraphs in text.normal; semantics role Button, contentDescription "Claude, thinking" while streaming else the label
  // ide/
  // TandemSettings.State gains the member `var showThinking: Boolean by property(true)` (`property` is a protected BaseState member, so it cannot be an extension property)
  fun settingsChanged()                          // a member of TandemSessions (it writes T7's private _rowOptions, so not an extension): sets _rowOptions.value = RowOptions(TandemSettings.getInstance().state.showThinking); T8 also initialises _rowOptions from that setting instead of `true`
  // TandemSettingsConfigurable: row { checkBox("Show thinking rows").bindSelected({ settings.state.showThinking }, { settings.state.showThinking = it }) } next to the cap; apply() calls TandemSessions.settingsChanged() on every open project's created service (ProjectManager.getInstance().openProjects, project.serviceIfCreated<TandemSessions>())
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
// ide/TandemSettingsTest
fun showThinkingRoundTrips()   // assertTrue(TandemSettings.State().showThinking) (the default of a fresh State, never the shared service); set false, loadState(State().apply { showThinking = false }) → false; the settings page's checkbox "Show thinking rows" applies the value (as `the settings page writes the path and cap` does for its fields)
// ide/TandemSessionsTest (@TestApplication, fake CLI replaying think-display-summarized)
fun aPresenterRebuildsWhenShowThinkingChanges()   // a ChatPresenter(presenterScope, handle.state, sessions.rowOptions) over a session that ran the fixture: chat.first { it.rows.any { r -> r.row is ThinkingRow } } (never chat.value, which can precede the first emission); TandemSettings.getInstance().state.showThinking = false; sessions.settingsChanged(); chat.first { it.rows.none { r -> r.row is ThinkingRow } } within the test timeout; the ToolRow/TextRow keys are unchanged. The body sits in try { … } finally { TandemSettings.getInstance().loadState(TandemSettings.State()); sessions.settingsChanged(); presenterScope.cancel() }, so no other test in the shared application sees showThinking = false
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ide.TandemSettingsTest' --tests 'dev.daarkolabs.tandem.ide.TandemSessionsTest'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block; `RowItem` dispatches `ThinkingRow` → `ThinkingRowItem`, `WorkingRow` → `WorkingRowItem` (the T7 stand-ins go).
- [ ] **Step 4: Run** the two classes, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts`, then `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → all PASS (every IDE test's turn now shows the animated working row and thinking rows). Optional feel check: `./gradlew runIde --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (spark cycle, elapsed tick; checklist 10.7 items 2, 3, 9 are the owner's). Open item 12 (spec 8): at the slice-A review the owner reports `claude --version` and whether an Opus session streams summaries. If their CLI is older than 2.1.302 or exits with an unknown-option error for `--thinking-display`, gate the flag: `SessionConfig.argv()` omits `--thinking-display summarized` when the detected CLI version is below 2.1.302, pinned by a `ClaudeSessionTest` argv case (then Step 5 also stages `src/main/kotlin/dev/daarkolabs/tandem/session` and `src/test/kotlin/dev/daarkolabs/tandem/session`). Otherwise nothing changes.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/ide src/test/kotlin/dev/daarkolabs/tandem/ide && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(ui): working indicator and thinking rows with a Show thinking setting"`

---

### Task T9: ui-composer-autoscroll

**Depends on:** T6, T7, T8.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/ComposerKeys.kt`, `.../ui/ScrollKeys.kt`
- Modify: `.../ui/PromptBar.kt`, `.../ui/ChatPanel.kt` (nested-scroll connection, thumb drags, pin effect, `↓` pill, scroll keys, `onFocusComposer`), `.../ui/ChatScreen.kt`, `.../ui/SessionHeader.kt` (status line = `claude <version>` only, from `presenter.composer`), `.../ui/TandemToolWindow.kt` (passes the composer flow to `SessionHeader`), `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/ui/ComposerKeysTest.kt`, `.../ui/ScrollKeysTest.kt`; Modify `.../ui/ChatUiStateTest.kt`

**Interfaces:**
- Consumes: T6 `FollowState`, `ScrollEvent`, `ComposerModel`; T7 `ChatUiState` (`draft`, `follow`, `listState`), `ChatPresenter.composer`, `RenderedChat`.
- Produces (pure mappings shaped like `rowKey(key, type, mode, mac)` so tests build no AWT event; the composables extract `key`, `type`, `isShiftPressed`, `isAltPressed`, `isCtrlPressed || isMetaPressed` from the `KeyEvent`):
  ```kotlin
  internal enum class ComposerAction { Send, Stop, CardEscape }
  internal fun composerKey(key: Key, type: KeyEventType, shift: Boolean, alt: Boolean, ctrlOrMeta: Boolean, busy: Boolean, cardOpen: Boolean): ComposerAction?   // Enter/NumPadEnter with !shift && !alt && !ctrlOrMeta && !busy → Send; Shift+Enter → null (the TextArea inserts the newline); Alt+Enter, Ctrl/Meta+Enter → null; Escape & cardOpen → CardEscape; Escape & busy → Stop; else null; KeyUp → null (plan B's `composerKey(…, cardOpen)` reference stays valid)
  internal enum class ScrollAction { PAGE_UP, PAGE_DOWN, HOME, END, FOCUS_COMPOSER }
  internal fun scrollKey(key: Key, type: KeyEventType, ctrlOrMeta: Boolean): ScrollAction?   // PageUp/PageDown/Home/End (Ctrl/Meta+End = END); Escape on KeyDown → FOCUS_COMPOSER (4.18)
  internal fun ScrollAction.followEvent(atBottomAfter: Boolean): ScrollEvent?   // PAGE_UP, HOME → WheelUp; END → Jump; PAGE_DOWN → ReachedBottom when atBottomAfter else null; FOCUS_COMPOSER → null
  // ChatPanel(project, presenter, ui, emptyText, lastFrameAt: StateFlow<Long>, onFocusComposer: () -> Unit): FOCUS_COMPOSER calls onFocusComposer
  // ChatPane(project, handle, manager, workspace, presenter, ui, focusPrompt, onPromptFocused, onFocusComposer: () -> Unit): forwards onFocusComposer to ChatPanel; TandemToolWindow passes onFocusComposer = { promptFocusFor = selected.id } (the same prompt-focus request it already uses; slice B's T10 edits this ChatPane)
  // SessionHeader(handle: SessionHandle?, composer: StateFlow<ComposerModel>?, …unchanged…) with StatusLine(model: ComposerModel) reading claudeCodeVersion and modelWarning; TandemToolWindow passes selected?.let { presenters.presenter(it).composer }; SessionHeader no longer collects handle.state (spec 6.0, 9.5)
  // PromptBar(handle, manager, workspace, presenter: ChatPresenter, ui: ChatUiState, focus, onFocused, onCardEscape: () -> Unit = {}): 4.15 verbatim: TextArea(ui.draft, heightIn(40.dp, 184.dp), MultiLine(1, 8), placeholder "Describe a task or ask a question") in the chatColumn box; IconButton "Send" (Text "↵") / "Stop" (AllIconsKeys.Actions.Suspend); onPreviewKeyEvent → composerKey(…, busy = composer.busy, cardOpen = composer.cardOpen); Send → only when ui.draft.text.trim().isNotEmpty() (4.15): manager.send + ui.draft.clearText() + ui.follow = on(Sent) + animateScrollToItem(last); Stop → manager.interrupt; CardEscape → onCardEscape (ChatPane passes manager.deny(handle.id, pendingPermissions.first().requestId) for the legacy prompt; slice B's T10 re-points it to the card); footer FlowRow: PermissionModePicker, ModelPicker, EffortBadge(level capitalised, description "Effort"), 8 dp apart; the composer collects presenter.composer only
  // ChatPanel: the spec 6 "autoscroll wiring (T9)" block verbatim (connection, scrollbarInteractionSource thumb, LaunchedEffect(chat, viewportHeight) pin, JumpToBottom OutlinedButton 28 dp, contentDescription "Jump to bottom", testTag("jump-to-bottom"), semantics "Transcript", focus on press, onPreviewKeyEvent(scrollKeys))
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
// ui/ComposerKeysTest (`key(code)` helper as in SessionSidebarTest)
`Enter sends`                        // composerKey(enter, KeyDown, shift = false, alt = false, ctrlOrMeta = false, busy = false, cardOpen = false) == Send
`Enter does nothing while busy`      // busy = true → null
`Cmd-Enter does nothing`             // ctrlOrMeta = true → null; alt = true → null
`Shift+Enter is a newline`           // shift = true → null (left to the TextArea)
`Esc stops while busy`               // escape, busy = true, cardOpen = false → Stop
`Esc with a card open routes to the card`   // escape, busy = true, cardOpen = true → CardEscape
// ui/ScrollKeysTest
`Home and PageUp pause, End resumes`   // FollowState().on(scrollKey(home, KeyDown, false)!!.followEvent(false)!!) == FollowState(false, 0); same for pageUp; FollowState(false, 2).on(scrollKey(end, KeyDown, false)!!.followEvent(false)!!) == FollowState(true, 0); scrollKey(pageDown…).followEvent(true) == ReachedBottom and .followEvent(false) == null
`Esc in the transcript focuses the composer`   // scrollKey(escape, KeyDown, false) == FOCUS_COMPOSER and FOCUS_COMPOSER.followEvent(false) == null
// ui/ChatUiStateTest
fun draftIsKeptPerSessionKey()       // presenters.ui("a").draft.edit { append("hello") }; ui("b"); ui("a").draft === the first TextFieldState and text == "hello"
// ToolWindowIdeTest locator updates (once; addressed by test name, since T7 shifts the line numbers): button("Send") → iconButton("Send"), button("Stop") → iconButton("Stop"); the clickButton("Send") in `a typed prompt is sent and the streamed reply is rendered` and in typePrompt → iconButton("Send").click(Point(10, 10)); clickButton("Stop") in `Stop interrupts a running turn` → iconButton("Stop").click(Point(10, 10)); the starts-with(@visible_text, 'claude-sonnet-5-5') status wait in `a typed prompt is sent and the streamed reply is rendered` → picker("Model", "Default model").present(); the contains(@visible_text, 'mode: plan') wait in `the pickers send set_permission_mode and set_model` → picker("Permission mode", "Plan").present()
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.*'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block. `StatusLine` keeps the model-warning banner and shows one `Text("claude <version>")`. PAGE_UP/PAGE_DOWN scroll by `viewportSize.height − 40.dp`, HOME/END scroll to item 0 / the last item (4.16); Send does nothing when `ui.draft.text.isBlank()` (4.15).
- [ ] **Step 4: Run** `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` then `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` then `./gradlew verifyPlugin --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → all PASS (slice A may land alone, spec 3, and T7–T9 bring new Compose and Jewel calls). Then `./gradlew runIde --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` once for the wheel sign (open item 6: wheel-up during a stream must pause, a wheel-down tick must not re-lock, `↓ N new` must appear); if `available.y` has the opposite sign, flip the two comparisons in the connection, nothing else.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ui src/integrationTest && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(ui): composer keys, icon send and stop, intent-based autoscroll"`

---

Slice-A review with the owner: `runIde` checklist 10.7 items 1, 2, 3, 4, 5, 9, 11; open items 3 (thinking label), 4 (spark), 5 (reduced-motion flag), 6 (wheel sign), 9 (bubble/box steps), 11 (strong skipping counter), 12 (thinking-display flag, T8's conditional gate), 13 (thinking on --resume), 14 (not-responding threshold during a silent Bash), 15 (grouping thresholds and the TURN gap), 17 (DiffColors fallback in a light scheme), 20 (step word), 22 (header status line), 23 (chevron on running rows).

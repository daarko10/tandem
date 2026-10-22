# Chat Redesign Slice C (Polish and Parity) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the chat redesign: subagent activity nested under its Agent row, GFM tables and strikethrough with a fence copy button, the last hard-coded colours out and the long-prompt clamp, transcript reload parity pinned over the 29 probe fixtures, the two remaining IDE Starter tests, the docs, and the PR.

**Architecture:** Slice A built the pure `chat/` row model (T4–T6) and the keyed `ui/` list (T7–T9); slice B the cards (T10–T12). Slice C adds only leaves and parity: `AgentBody` renders the `ToolCall.children` T4 already routes (T13); `TandemMarkdown.kt` wraps Jewel's bridge styling with the two GFM extensions and a `DefaultMarkdownBlockRenderer` subclass (T14); `TranscriptReader` stamps the events it already feeds `reduce` with the line's `timestamp` and `cwd`, and one golden test proves a reloaded transcript builds the same tool rows as its stream (T15); T16 adds the fake-CLI delay knob, the generated long-reply fixture, the working-indicator and autoscroll IDE tests, the doc updates and the PR.

**Tech Stack:** Kotlin 2.4.20, IntelliJ Platform 2026.1.5 (Jewel 0.37.0: `DefaultMarkdownBlockRenderer`, `GitHubTableProcessorExtension`, `GitHubStrikethroughProcessorExtension`, `GfmTableStyling.create()`, `InlineWarningBanner`, `IconActionButton`), kotlinx.collections.immutable, JUnit 5, IDE Starter + UI Driver, the Python fake CLI, Python 3 stdlib for the scripts.

**Spec:** `docs/superpowers/specs/2026-10-19-chat-redesign-design.md`: sections 4.3 (clamp), 4.4 (Markdown, links, copy button), 4.8 (subagents), 4.11 (banner), 5.5 (reload parity), 5.6, 6 (`AgentBody.kt`, `TandemMarkdown.kt`), 10.2 (reader tests), 10.3 (`ChatRowsBuilderTest` parity), 10.4 (`TANDEM_FAKE_LINE_DELAY_MS`), 10.5 (`TandemPluginTest`), 10.6 (the two IDE tests), 10.7 (the owner's `runIde` checklist), 12 (T13–T16), open items 7, 8, 19. Reference: `docs/superpowers/research/2026-10-18-claude-code-desktop-ui-reference.md`; probes: `docs/superpowers/research/2026-10-18-protocol-probes.md`.

**Dependencies (spec 12, refined for shared files):** T13 [T7, T10, T12] → T14 [T7, T11, T12, T13] → T15 [T4, T14] → T16 [T15, all], sequential, all after slice B (T10–T12) has landed. Spec 12 lists T13 [T7, T10], T14 [T7], T15 [T4]; this plan's T14 also consumes T12's `openInEditor`, edits T12's `PlanCardView` and the `PendingCardHost` that T10–T12 change. Run order: T13 → T14 → T15 → T16. Spec 12's parallel set (T11 ‖ T12 ‖ T13 ‖ T14 ‖ T15) and its "T15 may start right after T4" do not hold for this plan: T13 and T14 both edit `ui/ChatPresenter.kt` and `ui/ChatPanel.kt`; T13 and T15 both edit `chat/ChatRowsBuilderTest.kt` (and may fix `chat/ChatRowsBuilder.kt`, which T5/T6 also edit); T13 edits `ide/TandemSessionsTest.kt` (also T11) and `ui/ChatPresenterTest.kt` (also T12); T14 also edits T11/T12's `PendingCardHost`; T15 started right after T4 would edit `ChatRowsBuilderTest.kt` beside T5/T6. Run one task at a time. Serialising breaks no dependency (plan B does the same for T11/T12).

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

## Global Constraints

- CLAUDE.md hard rules, verbatim, each followed by what it means in slice C:
  1. **Never block the EDT.** No process I/O, file I/O, JSON parsing or waiting on the CLI from the UI thread. Use the platform coroutine scopes (`platform-coroutines-structured-concurrency` skill).
     - In slice C: Markdown is parsed in `ChatPresenter` on `Dispatchers.Default`; the link handler's VFS lookup runs on `Dispatchers.IO` through T12's `openInEditor`; no click handler parses.
  2. **No Node, no Agent SDK at runtime.** We spawn the user's `claude` binary directly. Do not add a sidecar.
     - In slice C: `make_long_reply.py` and the fake CLI are test tools, never shipped.
  3. **Never touch credentials.** Auth belongs to the CLI. Do not read `~/.claude/.credentials*`, keychains or API keys, and never offer a login flow.
     - In slice C: no task reads auth, keys or the environment's secrets.
  4. **Edits go through the IDE, not the disk.** Claude's built-in Edit/Write tools are disabled; our MCP edit tools apply changes with `WriteCommandAction` on the `Document`, so undo, unsaved buffers and local history work. Every edit shows a native diff before it lands, unless the user picked auto-accept.
     - In slice C: no edit path changes; a Markdown link only opens a file in the editor.
  5. **Never fail silently.** If the CLI falls back to another model, rejects a model, retries, or errors, the user sees it in the chat. Compare the model in the `system/init` event (and fallback events) with what the user picked.
     - In slice C: a subagent entry with an unknown parent, a progress line with missing usage and a transcript line without `timestamp` or `cwd` all still render.
  6. **Every protocol change gets a fixture test.** Recorded transcripts live in `src/test/resources/fixtures/`. Unknown message types must parse to `Unknown(raw)`, never throw.
     - In slice C: `synthetic-long-reply.ndjson` (T16) is the one generated fixture; CLAUDE.md says so.
  7. **Do not bundle kotlinx.coroutines or the Kotlin stdlib.** The platform provides both.
     - In slice C: the two GFM modules are the IDE's bundled modules, declared, not shipped.
  8. **Do not call the product "Claude Code"**, in the plugin name, ID or UI. Use "Tandem" and say it works with Claude Code.
     - In slice C: never "Claude Code" in `chat/` or `ui/` strings or the 4.20 strings; `Claude` alone is fine; T16 greps.
- Layers: `chat/` stays Compose-free; `session/transcript/` keeps its imports (JDK, coroutines, serialization, `com.intellij.openapi.diagnostic`); `ui/` holds no protocol logic. Theme tokens only (spec 4.2): after T14 the only literal colours in `ui/` are `TandemColors.spark`, the drawer scrim and `bottomFade`'s alpha mask (spec 4.3).
- Jewel 0.37.0 as bundled in 2026.1.5; `verifyPlugin` runs against every supported release (0.41.0 on 2026.2.3), so T14 runs it and drops the renderer subclass if it is reported (spec 4.4, open item 7). Any bundled module used needs both a `bundledModule(...)` line in `build.gradle.kts` and a `<module name=.../>` in `plugin.xml`.
- Strings exactly as spec 4.20: `Copy code`, `Show more`, `Show less`, `From the agent "<d>"`, `Done (N tool uses · T tokens · D)`, `N tool uses · T tokens · D` (`1 tool use` singular), `Claude is working`, `Claude is waiting for you`, `Jump to bottom`, `Transcript`, `Worked for <duration>`; fake-CLI knob `TANDEM_FAKE_LINE_DELAY_MS`.
- Fixtures: `name:line` is the 1-based line of `src/test/resources/fixtures/<name>.ndjson`; `transcripts/<name>.jsonl` its on-disk twin; paths are literally `<path>`; ids are placeholders (`<id-12>`, `<request_id-52>`). A reloaded transcript in a test is `runBlocking { TranscriptReader.read(Fixtures.transcript(name)) }.state`. The 29 probe fixtures are the `.ndjson` files whose name starts with `ask-`, `plan-`, `think-`, `tools-`, `agent-` or `misc-`; every one has a `.jsonl` twin. `synthetic-long-reply` (T16) is the 41st `.ndjson`, the only unrecorded one, with no twin.
- Test names are the spec's where it gives one (backtick JUnit 5 names); new tests in a class keep that class's style. Pure functions are tested without composition, as `PickersTest` and `CardKeysTest` do; no Compose UI test harness exists (tests.md).
- Gradle: every command carries `--init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (the absolute path works from a git worktree). One class: `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.<pkg>.<Class>'`; full suite: `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts`; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` adds `koverVerify` (90 % lines per package of `protocol/`, `session/` and `chat/`); IDE tests: `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (1.5–3 min per test alone, about 12 min for all after T16); `./gradlew verifyPlugin --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` ≈ 2 min. Every task ends with `check` green (plus the IDE tests in T16) and one or two commits.
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
- CLAUDE.md Conventions, verbatim: "Validate everything that comes from the CLI; it is an external process that changes weekly." and "Never log prompt contents, file contents or secrets. Log message types and ids." (T14's link miss logs at debug without the path, spec 4.4.)
- Extensions beyond spec 5 (not contract breaks): `WorkingRow.expandedStep: String? = null`, `WorkingIndicator.step(…, skipAgent: Boolean = false)`, `shownWorkingRow(row, agentExpanded)` and `RowItem`/`WorkingRowItem` `agentExpanded: Boolean = false` (T13); `AgentBody(rowKey, call, bodies, ui)` and `ChatPresenter`'s private `requested` key set behind `expandBody` (T13); `tandemMarkdownProcessor()`, `TandemBlockRenderer`, `TandemMarkdownStyling(project, content)`, `LocalUrlHandler`, `LinkTarget`, `linkTarget(url, projectBase)`, `rememberUrlHandler(project)`, `BubbleClamp` / `bubbleClamp(lineCount, shown)` (T14); the bubble's `Show less` link (T14: 4.3 names only the bubble's `Show more`, and 4.20 lists `Show less` for the cards only); the two spec 10.6 departures listed under T16 Interfaces. Deviation: T15's reloaded `ChatState.cwd` is `TranscriptMeta.cwd` (the first non-abandoned line with a `cwd`, of any type), not 5.5's first kept user line; every recorded transcript gives the same `<path>`.
- Spec 10.6's 200 ms assumes one stream line per paragraph; `synthetic-long-reply` emits three per paragraph (start, delta, stop), so the autoscroll test uses `TANDEM_FAKE_LINE_DELAY_MS=70` for the same ≈ 12 s stream (T16 Interfaces lists both departures from 10.6).
- Commits: Conventional Commits, `git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>"`, no trailers, stage by path; branch `feat/chat-redesign` → PR against `feat/sessions-and-pickers`; no attribution lines and no session link in the PR body (CLAUDE.md).

## Review Focus

1. A subagent `assistant` or `tool_result` entry whose `parentToolUseId` names no Agent call in the state (a retracted Agent block, an unknown parent) must not vanish: it renders at top level → T13 `a subagent entry whose agent call is unknown is not dropped`.
2. A `task_progress` line whose `usage` lacks a field must show no sub-line, never `null tool uses · null tokens` → T13 `a progress line with missing usage shows no sub-line`.
3. A Markdown link to a path outside the project (`../x`, `/etc/passwd`) or with no project base must open nothing, without an exception → T14 `links open only http and project paths`.
4. A transcript whose lines carry no `timestamp` and no `cwd` (older CLI) must reload with `cwd == null`, no positive send time, and the same entries → T15 `a transcript without timestamps or cwd still loads`.
5. A Markdown link to a project file with a line anchor (`src/A.kt#L42`, `A.kt:42`, the forms Claude writes for file references) must open that file, not silently miss → T14 `links open only http and project paths`.
6. A prompt of exactly 8 laid-out lines stays unclamped; one long line that wraps past 8 laid-out lines clamps (the count is `onTextLayout`'s `lineCount`, not the newlines) → T14 `prompts over eight laid-out lines clamp` and the owner's `runIde` pass (T16 Step 10).

---

### Task T13: ui-subagents

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/rows/AgentBody.kt`
- Modify: `.../ui/rows/ToolRowItem.kt` (`canExpand`, the lone-AGENT body dispatch to `AgentBody`, `onExpand` on every open of a lone Agent call; the progress sub-line is T7's and stays as it is), `.../ui/ChatPresenter.kt` (`expandBody`'s `requested` set, the cache rule), `.../ui/ChatPanel.kt` (`agentExpanded`, and `RowItem` where T7 put it), `.../ui/rows/WorkingRowItem.kt` (`agentExpanded`, `shownWorkingRow`), `.../chat/ChatRow.kt` (`WorkingRow.expandedStep`), `.../chat/WorkingIndicator.kt` (`step`'s `skipAgent`), `.../chat/ChatRowsBuilder.kt` (`expandedStep`, and only where the goldens below find T4's data short: `progress` while a task runs, the Agent body forms, an unknown-parent entry)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/chat/ChatRowsBuilderTest.kt`, `.../chat/WorkingIndicatorTest.kt`, `.../ui/ChatPresenterTest.kt`, `.../ide/TandemSessionsTest.kt`
- Modify: `src/test/kotlin/dev/daarkolabs/tandem/chat/Replay.kt` (`replay` gains `lines: List<String> = Fixtures.lines(name)`, placed after `prompt` and `initial`, before `onLine`; reuse it if T6 already added one; every existing call compiles unchanged)

**Interfaces:**
- Consumes (T4): `ToolCall(id, name, kind, state, label, stats, body, children: ImmutableList<ChatRow>, progress: String?)`, `ToolLabels.agentProgress(result)` / `agentProgress(task: TaskInfo)`, `ChatRowsBuilder(RowOptions).build(state): ChatRows`; (T6): `Cards.pending(state): Pair<PendingCard?, Int>`, `PendingCard.agentDescription`, `WorkingRow(key, turnUuid, startedAtMillis, step, outputTokens, waiting, gap)`, `WorkingIndicator.verb(turnUuid, lastToolUseId)` / `step(state, rows, options): String` / `view(row, nowMillis, lastFrameAtMillis, …)`; (T7): `RenderedRow(row, blocks, bodies)`, `ChatPresenter(scope, state: StateFlow<ChatState>, options, parse).expandBody(key)` and its `RenderedChat.planBlocks` cached under `"plan:<requestId>"` (T12 renders it), `ToolRowItem(rendered, ui)`, `ToolBodyView(call, blocks, ui)`, `TextRowItem(rendered: RenderedRow)`, `RowItem(rendered, ui, lastFrameAt)`, `ChatUiState.expanded(key)` / `onExpand`, the `ChatPresenterTest` fake state flow; (T8): `WorkingRowItem(row, lastFrameAt)`; (T10): `internal fun agentLine(card: PendingCard): String?` in `ui/cards/PermissionCardView.kt` (= `card.agentDescription?.let { "From the agent \"$it\"" }`), which `PermissionCardView` draws under the title in place of an inline string; (T3): `SessionManager.answer`, `PermissionAnswers.allowOnce`; (`ide/`): `TandemSessions.extraEnvironment`, `TandemSessions.manager`.
- Produces:
  ```kotlin
  // ui/rows/AgentBody.kt (spec 4.8): the children indented 16 dp, then the agent's own Markdown body
  @Composable internal fun AgentBody(rowKey: String, call: ToolCall, bodies: ImmutableMap<String, ImmutableList<MarkdownBlock>>, ui: ChatUiState)
  // Column(Modifier.padding(start = 16.dp)) { call.children.forEach { child -> key(child.key) { when (child) { is ToolRow -> ToolRowItem(RenderedRow(child, null, bodies), ui); is TextRow -> TextRowItem(RenderedRow(child, bodies[child.key], bodies)); else -> {} } } }; if (call.body !is ToolBody.None) ToolBodyView(call, bodies[rowKey], ui) }
  //   TextRowItem(rendered: RenderedRow) is T7's signature, called as RowItem calls it; if T7 left a different signature, use RowItem's own call
  // ToolRowItem: canExpand = <T7's expression> || (row.calls.size == 1 && call.children.isNotEmpty())   // extend, do not restate: a running group stays unclickable (4.6.4 rule 7); a running Agent with children is clickable (4.9 expects it expanded while it runs)
  //   the progress sub-line is T7's single secondary Text at body − 1 under a lone AGENT label, showing whatever `call.progress` holds (the live `N tool uses · T tokens · D` while running, `Done (…)` after); T13 adds no second Text;
  //   the body of a lone AGENT call is AgentBody(row.key, call, rendered.bodies, ui); ToolRowItem's click calls ui.onExpand(row.key) on every open of a lone AGENT call (whatever its body and children hold yet)
  // ChatPresenter.expandBody(key) (still 5.6's non-blocking trySend): the collector adds key to a presenter-held `requested: MutableSet<String>` (touched only on its Dispatchers.Default collector).
  // On every emission, for each requested key that is still a top-level row, a "c:<call id>" sub-row or a child row (recursively), the presenter parses through the same (key, markdown) cache
  // whatever is not cached yet: that row's ToolBody.Markdown / Links body under bodies[key], and the markdown of each child TextRow (and each child ToolRow's Markdown/Links body) under
  // bodies[child.key], the keys ToolRowItem / TextRowItem read; one cache entry each (5.6). Keys pruned from the rows leave the set. So an Agent row opened while it runs gets its forwarded
  // text (agent-forwarded:38) and its own Markdown body (agent-sync:50) on the emission that brings them, without a second click
  // Cache rule (5.6): every emission builds each RenderedRow.bodies from the cache entries of its own key, its "c:<call id>" keys and the keys of its Agent calls' children (recursively);
  // a parsed key stays cached, and is re-attached on every later emission, while it is a top-level row, a "c:<call id>" sub-row, a child row (recursively) of the latest ChatRows,
  // or the "plan:<requestId>" key of the open PlanCard (T7's planBlocks key); only keys gone from all four are pruned
  // chat/ChatRow.kt: WorkingRow gains `val expandedStep: String? = null` (defaulted, so T6's calls compile)
  // chat/WorkingIndicator.kt: fun step(state: ChatState, rows: List<ChatRow>, options: RowOptions, skipAgent: Boolean = false): String
  //   with skipAgent the running local_agent description branch is skipped: Thinking when a hidden thinking block streams, else verb(turn.userUuid, turn.lastToolUseId) (4.9's order otherwise)
  // the builder sets expandedStep = step(state, rows, options, skipAgent = true) only when step(state, rows, options) differs from it (the step came from the agent), else null
  // ui/ChatPanel.kt: val agentExpanded = chat.rows.any { r -> (r.row as? ToolRow)?.calls?.singleOrNull()?.let { it.kind == ToolKind.AGENT && it.state in setOf(ToolState.RUNNING, ToolState.WAITING) } == true && ui.expanded(r.row.key).value }
  //   passed through RowItem(rendered, ui, lastFrameAt, agentExpanded) to WorkingRowItem(row, lastFrameAt, agentExpanded); both gain `agentExpanded: Boolean = false`, so T7's and T8's calls compile
  // ui/rows/WorkingRowItem.kt: internal fun shownWorkingRow(row: WorkingRow, agentExpanded: Boolean): WorkingRow = if (agentExpanded && row.expandedStep != null) row.copy(step = row.expandedStep) else row
  // WorkingRowItem renders WorkingIndicator.view(shownWorkingRow(row, agentExpanded), …)   // 4.9: the agent's step is the working row's only while its Agent row is collapsed; expanded, the nested row names it and the working row returns to Thinking or the turn's verb
  ```
  Builder facts the goldens pin (T4 produced the data; T13 corrects it where a golden fails): the Agent body is `ToolBody.None` when a child `TextRow` exists (forwarded text), else `ToolBody.Markdown(<tool_use_result.content[].text joined "\n\n", or task_notification.summary>, null)`; `progress` is `"<n> tool use(s) · <tokens> tokens · <duration>"` from the latest `TaskInfo` while the call runs (`null` when `toolUses`, `totalTokens` or `durationMs` is null) and `Done (…)` from the result; an entry whose `parentToolUseId` matches no Agent call is built at top level. On reload the Agent call has no children, so its body is the Markdown one even for agent-forwarded: this live/reload body difference is accepted by T15's parity test (sig skips the AGENT body).

- [ ] **Step 1: Write the failing tests** (T4's shared `replay(name, onLine = …)` in `chat/Replay.kt`, which sends a `UserSent` before each `system/init`, so `turn` is open and calls read `RUNNING`; "after line N" = inside `onLine` with `line == N`, the state built after that fixture line). Replay.kt (T4) gains a parameter `lines: List<String> = Fixtures.lines(name)`, placed before `onLine` (reuse it if T6 already added one); the two patched tests pass `Fixtures.lines("agent-sync")` with index 40 (line 41) re-encoded without its `"usage"` key, and with index 43 (line 44) re-encoded with `"is_error": false` and content `"7 seed.txt"`.

```kotlin
// ChatRowsBuilderTest: extend T4's `agent-sync` and `agent-forwarded` goldens in place with the assertions below (no parallel tests); then three new tests
// (agent-sync: Agent block <id-12>, subagent Bash blocks <id-49> and <id-56>; agent(rows) = the ToolRow whose single call has kind AGENT)
`agent-sync` golden (T4's, extended): nests the subagent's calls, progress and final text
  // after line 41: agent.calls.single().progress == "1 tool use · 21.5k tokens · 2.0s"; agent.state == RUNNING; (rows.last() as WorkingRow).step == "Running wc -l seed.txt && tail -c1 seed.txt | xxd";
  //   state.turn!!.lastToolUseId == "<id-12>" (no main-thread tool_use since the Agent's) && (rows.last() as WorkingRow).expandedStep == WorkingIndicator.verb(state.turn!!.userUuid, state.turn!!.lastToolUseId)
  // after line 43: the child ToolRow whose call id is "<id-49>" has state WAITING; agent.state == RUNNING; Cards.pending(state).first!!.agentDescription == "Count lines in seed.txt"; rows.last() is WorkingRow && waiting
  // after line 50: the WorkingRow's expandedStep == null (no task runs)
  // final: no top-level TextRow or ToolRow comes from an entry with parentToolUseId != null; no WorkingRow; agent.state == DONE; progress == "Done (2 tool uses · 21.8k tokens · 5.8s)";
  //   children.map { (it as ToolRow).label.text to it.state } == listOf("Failed to run wc -l seed.txt && tail -c1 seed.txt | xxd" to FAILED, "Ran tail -c1 seed.txt | od -c" to DONE);
  //   agent.calls.single().body == ToolBody.Markdown("7 lines. The last line ends with a trailing newline.", null)
`agent-forwarded` golden (T4's, extended): the forwarded text sits inside the agent row
  // children.map { it::class } == listOf(ToolRow::class, TextRow::class); (children[0] as ToolRow).label.text == "Ran wc -l <path>"; (children[1] as TextRow).markdown == "7"; body == ToolBody.None; no top-level TextRow with markdown "7"
`a progress line with missing usage shows no sub-line`          // Review Focus 2: line 41's "usage" key removed in memory → after it progress == null; after line 50 "Done (2 tool uses · 21.8k tokens · 5.8s)"
  //   replay("agent-sync", lines = Fixtures.lines("agent-sync").mapIndexed { i, l -> if (i == 40) JsonObject(Json.parseToJsonElement(l).jsonObject - "usage").toString() else l }) { line, s, rows -> … }
`a subagent entry whose agent call is unknown is not dropped`   // Review Focus 1: the final agent-sync state s, then
  //   s.copy(entries = s.entries + ChatEntry.Assistant(id = "stray", messageId = "stray", parentToolUseId = "nope", model = null, blocks = listOf(AssistantBlock.Text("stray")), completedBlocks = 1, streaming = false, uuids = emptySet(), agentId = "x"), entryIndex = emptyMap())
  //   → a top-level TextRow with markdown "stray"
`subagent calls are never grouped`                              // 4.8: agent-sync with line 44's tool_result made a success in memory → children are two ToolRows, each with calls.size == 1
  //   replay("agent-sync", lines = Fixtures.lines("agent-sync").mapIndexed { i, l -> if (i == 43) <l with message.content[0] given "is_error": false and "content": "7 seed.txt", re-encoded> else l })
// ChatPresenterTest (the fake MutableStateFlow<ChatState> of T7)
// States come from T4's `dev.daarkolabs.tandem.chat.replay` (import it explicitly; not B's `ui.replay(name, through)`, which feeds no `UserSent`, so the Agent would read INTERRUPTED):
//   val states = mutableListOf<ChatState>(); replay("agent-forwarded") { _, s, _ -> states += s }. 'Through line N' is `state.value = states[N - 1]`, and 'push line M' is `state.value = states[M - 1]`,
//   awaiting the next `presenter.chat` emission after each, as `presenter.chat.first { <what the assertion reads> }` (`chat` is a StateFlow: a push that changes nothing, such as plan-approve:89, emits nothing);
//   'every later emission' checks read a list filled by a collector started before the first push (`launch { presenter.chat.toList(emissions) }`). The same for agent-sync (lines 45, 46…) and plan-approve (lines 88, 90, 91).
`expanding an agent row parses its nested text`
  // agent-forwarded replayed through line 37 (Agent RUNNING, one child ToolRow, no TextRow child yet): presenter.expandBody("tool:<id-12>"); push line 38 → the next emission's Agent row has
  //   bodies[<the child TextRow's key>] != null with no second expandBody, parsed on a thread not named "AWT-EventQueue…"
  //   then push lines 39 to the end into the fake flow one at a time → every later emission's Agent row has bodies[<child key>] === that same blocks instance (the cache rule: child keys survive the prune and are re-attached)
  // agent-sync replayed through line 45 (Agent RUNNING, body None), with a counting parse that records each argument: expandBody("tool:<id-12>"); push lines 46 to the end → the final emission's Agent row has bodies["tool:<id-12>"] != null
  //   (the Markdown body, agent-sync:50), and the counting parse saw "7 lines. The last line ends with a trailing newline." exactly once over lines 46 to the end
  //   (the same string arrives as task_notification.summary at :49 and as tool_use_result at :50, so the (key, markdown) cache must hit)
`a plan card's markdown stays cached while the card is open`
  // ChatPresenter(scope, state, options, parse = a counting parse that records each argument) over plan-approve replayed with T4's `replay` through line 88 (the card opens);
  //   first = chat.first { it.planBlocks != null }.planBlocks; push the state after line 90 (the status line: permissionMode becomes acceptEdits) and then after line 91
  //   (the ExitPlanMode tool_result: its row turns DONE, and the bare replay keeps the request pending, so the card stays open); wait for the emission whose `Plan approved` row is DONE
  //   → planBlocks === first, and the counting parse saw the plan text exactly once. (Line 89 changes no state, so it is not used.) T12's `a plan card's markdown is parsed off the EDT` still passes
`an expanded agent row returns the working row to the turn's verb`
  // row = WorkingRow(turnUuid = "u1", startedAtMillis = 0L, step = "Running x", outputTokens = 0, waiting = false, expandedStep = "Pondering"):
  //   shownWorkingRow(row, true).step == row.expandedStep; shownWorkingRow(row, false) === row; with expandedStep == null, shownWorkingRow(row, true) === row
// WorkingIndicatorTest
`step skips the agent progress when asked`
  // the agent-sync state after line 41 (replayed as above) and its rows: step(state, rows, RowOptions(showThinking = true)) == "Running wc -l seed.txt && tail -c1 seed.txt | xxd";
  //   step(state, rows, RowOptions(showThinking = true), skipAgent = true) == WorkingIndicator.verb(state.turn!!.userUuid, state.turn!!.lastToolUseId)
// ide/TandemSessionsTest (the T11 pattern: extraEnvironment, create, send, wait on handle.state)
`a subagent's permission request names the agent on its card and the nested row waits`
  // TANDEM_FAKE_FIXTURE=agent-sync; send("Count lines"); state = handle.state.first { it.pendingPermissions.isNotEmpty() }; p = state.pendingPermissions.single(): p.agentId != null
  // rows = ChatRowsBuilder(RowOptions(showThinking = true)).build(state): rows.card!!.agentDescription == "Count lines in seed.txt"; agentLine(rows.card!!) == "From the agent \"Count lines in seed.txt\"" (the card line end to end, spec 12); rows.moreWaiting == 0; the Agent row's child for "<id-49>" is WAITING; rows.rows.last() is WorkingRow && waiting
  // manager.answer(handle.id, p.requestId, PermissionAnswers.allowOnce(p)); handle.state.first { !it.busy }; the Agent row is DONE with progress "Done (2 tool uses · 21.8k tokens · 5.8s)"
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.ChatRowsBuilderTest' --tests 'dev.daarkolabs.tandem.chat.WorkingIndicatorTest' --tests 'dev.daarkolabs.tandem.ui.ChatPresenterTest' --tests 'dev.daarkolabs.tandem.ide.TandemSessionsTest'` → compilation errors: unresolved `expandedStep`, `skipAgent`, `shownWorkingRow` (no test runs yet). The golden differences from T4's builder, the presenter's missing `bodies` and an already-passing `subagent calls are never grouped` (4.8 is T4's rule) show in the first run once Step 3's signatures compile.
- [ ] **Step 3: Implement** the Interfaces block: `AgentBody.kt`, the three `ToolRowItem` changes (`canExpand`, the AGENT body dispatch, `onExpand`), `expandBody`'s `requested` set and the cache rule, `WorkingRow.expandedStep`, `step`'s `skipAgent`, `shownWorkingRow` with `agentExpanded`; fix the builder only where the first run after Step 3's signatures compile shows a difference.
- [ ] **Step 4: Run** the four classes, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`chat/` stays ≥ 90 %). Optional feel check: `./gradlew runIde --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` with a real Agent prompt (collapsed by default, chevron opens the nested rows).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ide && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(ui): nest subagent rows, progress and final text under the Agent row"`

---

### Task T14: markdown-theme-polish

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/TandemMarkdown.kt`
- Modify: `build.gradle.kts` (two `bundledModule` lines after `…extensions.autolink`), `src/main/resources/META-INF/plugin.xml` (two `<module>` lines), `.../ui/ChatPresenter.kt` (T7's `private val processor = MarkdownProcessor(listOf(AutolinkProcessorExtension))` becomes `private val processor = tandemMarkdownProcessor()`, one processor per presenter, behind T7's unchanged `parseMarkdown = parse ?: { processor.processMarkdownDocument(it).toImmutableList() }`), `.../ui/ChatPanel.kt` and `.../ui/cards/PendingCardHost.kt` (`ProvideMarkdownStyling(project)` → `TandemMarkdownStyling(project)`), `.../ui/rows/TextRowItem.kt`, `.../ui/rows/ToolBodyView.kt` and `.../ui/cards/PlanCardView.kt` (`Markdown(…, onUrlClick = LocalUrlHandler.current)`), `.../ui/SessionHeader.kt` (`StatusLine`: `InlineWarningBanner`), `.../ui/rows/UserBubble.kt` (the clamp)
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/ui/TandemMarkdownTest.kt`, `.../ui/UserBubbleTest.kt` (JUnit 5, pure); Modify `.../ide/TandemPluginTest.kt`

**Interfaces:**
- Consumes (T7): `ChatPresenter(scope, state, options, parse: ((String) -> ImmutableList<MarkdownBlock>)? = null)` with its `private val processor` field and `parseMarkdown = parse ?: { processor.processMarkdownDocument(it).toImmutableList() }` (`parse`: name and type unchanged; tests keep injecting it) and its Markdown cache, `TextRowItem`, `ToolBodyView`, `UserBubble` (one `Text` node, `testTag("user-bubble")`), `Modifier.bottomFade(height: Dp)`, `ChatPanel`'s `ProvideMarkdownStyling(project)`; (T9): `SessionHeader`'s `StatusLine(model: ComposerModel)` and `ComposerModel.modelWarning`; (T12): `openInEditor(project: Project, path: Path)`, `PlanCardView`'s `Markdown(...)` call and `PendingCardHost`'s `ProvideMarkdownStyling(project)` around it (T10–T12 all change `PendingCardHost`, hence T14 [T7, T11, T12]).
- Produces:
  ```kotlin
  // ui/TandemMarkdown.kt (spec 4.4 verbatim)
  internal fun tandemMarkdownProcessor(): MarkdownProcessor   // MarkdownProcessor(listOf(AutolinkProcessorExtension, GitHubTableProcessorExtension, GitHubStrikethroughProcessorExtension()))
  class TandemBlockRenderer(styling: MarkdownStyling, extensions: List<MarkdownRendererExtension>, inline: InlineMarkdownRenderer = DefaultInlineMarkdownRenderer(extensions)) : DefaultMarkdownBlockRenderer(styling, extensions, inline)
  // overrides @Composable RenderFencedCodeBlock(block, styling, enabled, modifier): Box { super.RenderFencedCodeBlock(block, styling, enabled, modifier); IconActionButton(AllIconsKeys.General.InlineCopy, contentDescription = "Copy code", onClick = { CopyPasteManager.getInstance().setContents(StringSelection(block.content)) }, modifier = Modifier.align(Alignment.TopEnd)) visible on hover };
  // override fun createCopy(rootStyling: MarkdownStyling?, rendererExtensions: List<MarkdownRendererExtension>?, inlineRenderer: InlineMarkdownRenderer?): MarkdownBlockRenderer =
  //     TandemBlockRenderer(rootStyling ?: this.rootStyling, rendererExtensions ?: this.rendererExtensions, inlineRenderer ?: this.inlineRenderer)
  // override fun plus(extension: MarkdownRendererExtension): MarkdownBlockRenderer = TandemBlockRenderer(rootStyling, rendererExtensions + extension, inlineRenderer)
  //   both are needed: 0.37.0's default plus builds a DefaultMarkdownBlockRenderer directly instead of calling createCopy; never `by` delegation (4.4)
  @Composable internal fun TandemMarkdownStyling(project: Project, content: @Composable () -> Unit)
  // val (styling, renderer) = remember(JewelTheme.instanceUuid) { val s = MarkdownStyling.create(); s to TandemBlockRenderer(s, listOf(GitHubTableRendererExtension(GfmTableStyling.create(), s), GitHubStrikethroughRendererExtension)) }; val onUrl = rememberUrlHandler(project)
  // ProvideMarkdownStyling(project, markdownStyling = styling, markdownBlockRenderer = renderer) { CompositionLocalProvider(LocalUrlHandler provides onUrl, content = content) }
  internal val LocalUrlHandler: ProvidableCompositionLocal<(String) -> Unit> = staticCompositionLocalOf { {} }   // every Markdown(...) call site passes onUrlClick = LocalUrlHandler.current; no composable signature changes, so T13's AgentBody and T7's row signatures stay as written
  internal sealed interface LinkTarget { data class Browse(val url: String) : LinkTarget; data class Open(val path: Path) : LinkTarget; data object None : LinkTarget }
  internal fun linkTarget(url: String, projectBase: Path?): LinkTarget   // blank → None; http/https (checked on the raw url) → Browse; then strip a `#…` fragment and a trailing `:<line>[:<col>]`; any other scheme (^[A-Za-z][A-Za-z0-9+.-]*:) → None; a relative path whose projectBase.resolve(path).normalize() starts with projectBase and differs from it → Open; absolute, ".." escapes, a null base and an InvalidPathException → None
  @Composable internal fun rememberUrlHandler(project: Project): (String) -> Unit   // Browse → BrowserUtil.browse(url); Open → openInEditor(project, path); None → nothing; projectBase = project.basePath?.let(Path::of)
  // UserBubble (4.3): var lines by remember(row.key) { mutableIntStateOf(0) } from onTextLayout { lines = it.lineCount }; var shown by remember(row.key) { mutableStateOf(false) };
  // when lines > 8 && !shown: Modifier.heightIn(max = 160.dp).clipToBounds().bottomFade(60.dp) (60 dp is the fade band) and a Link("Show more") under it; shown → no clamp and Link("Show less"); still one Text node with testTag("user-bubble")
  internal enum class BubbleClamp { NONE, CLAMPED, OPEN }   // in ui/rows/UserBubble.kt
  internal fun bubbleClamp(lineCount: Int, shown: Boolean): BubbleClamp   // NONE when lineCount <= 8, CLAMPED when > 8 && !shown, OPEN when > 8 && shown; UserBubble draws from it (CLAMPED: clamp, fade, Show more; OPEN: Show less)
  // SessionHeader.StatusLine(model: ComposerModel): model.modelWarning?.let { InlineWarningBanner(text = it, modifier = Modifier.fillMaxWidth()) } replaces the Color(0xFFB3261E)/Color.White Text
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
// ui/TandemMarkdownTest (plain JUnit 5; MarkdownProcessor needs no LaF)
@Test fun `tables and strikethrough parse with the extensions`() {
    val table = "| a | b |\n|---|---|\n| 1 | 2 |"
    assertFalse(tandemMarkdownProcessor().processMarkdownDocument(table).single() is MarkdownBlock.Paragraph)   // a table block
    assertTrue(MarkdownProcessor().processMarkdownDocument(table).all { it is MarkdownBlock.Paragraph })          // without the extension: plain paragraphs
    val struck = tandemMarkdownProcessor().processMarkdownDocument("~~x~~").single() as MarkdownBlock.Paragraph
    assertTrue(struck.inlineContent.any { it !is InlineMarkdown.Text })                                            // the strikethrough node
    assertTrue((MarkdownProcessor().processMarkdownDocument("~~x~~").single() as MarkdownBlock.Paragraph).inlineContent.all { it is InlineMarkdown.Text })
}
@Test fun `links open only http and project paths`() {   // Review Focus 3
    val base = Path.of("/home/u/proj")
    assertEquals(LinkTarget.Browse("https://e.com/a?b=1"), linkTarget("https://e.com/a?b=1", base)); assertEquals(LinkTarget.Browse("http://e.com"), linkTarget("http://e.com", null))
    assertEquals(LinkTarget.Open(base.resolve("src/A.kt")), linkTarget("src/A.kt", base)); assertEquals(LinkTarget.Open(base.resolve("src/A.kt")), linkTarget("./src/A.kt", base))
    assertEquals(LinkTarget.None, linkTarget("../x", base)); assertEquals(LinkTarget.None, linkTarget("/etc/passwd", base))
    assertEquals(LinkTarget.None, linkTarget("src/A.kt", null)); assertEquals(LinkTarget.None, linkTarget("mailto:a@b.c", base)); assertEquals(LinkTarget.None, linkTarget("", base))
    assertEquals(LinkTarget.Open(base.resolve("src/A.kt")), linkTarget("src/A.kt#L42", base)); assertEquals(LinkTarget.Open(base.resolve("A.kt")), linkTarget("A.kt:42", base))   // Review Focus 5
    assertEquals(LinkTarget.Browse("http://e.com:8080/x"), linkTarget("http://e.com:8080/x", base))
    assertEquals(LinkTarget.None, linkTarget("a\u0000b", base))   // the InvalidPathException branch (Markdown never passes a NUL: CommonMark maps it to U+FFFD), no exception
}
// ide/TandemPluginTest
@Test fun `the GFM table and strikethrough modules are visible to the plugin`() {
    val loader = assertNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.daarkolabs.tandem"))).pluginClassLoader
    Class.forName("org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension", false, loader)
    Class.forName("org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughProcessorExtension", false, loader)
    val xml = Path.of("src/main/resources/META-INF/plugin.xml").readText()   // the test task runs from the project dir, as Fixtures does
    assertTrue("<module name=\"intellij.platform.jewel.markdown.extensions.gfmTables\"/>" in xml)
    assertTrue("<module name=\"intellij.platform.jewel.markdown.extensions.gfmStrikethrough\"/>" in xml)
}
// ui/UserBubbleTest (plain JUnit 5; spec 4.3, Review Focus 6)
@Test fun `prompts over eight laid-out lines clamp`() {
    assertEquals(BubbleClamp.NONE, bubbleClamp(8, false)); assertEquals(BubbleClamp.CLAMPED, bubbleClamp(9, false)); assertEquals(BubbleClamp.OPEN, bubbleClamp(9, true))
}
// ide/TandemPluginTest
@Test fun `the block renderer stays a TandemBlockRenderer when extended`() {   // spec 4.4: plus (and createCopy) return a TandemBlockRenderer
    val r = TandemBlockRenderer(MarkdownStyling.create(), emptyList())
    assertIs<TandemBlockRenderer>(r.plus(GitHubStrikethroughRendererExtension))
    assertIs<TandemBlockRenderer>(r.createCopy(null, null, null))
}
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.TandemMarkdownTest' --tests 'dev.daarkolabs.tandem.ui.UserBubbleTest' --tests 'dev.daarkolabs.tandem.ide.TandemPluginTest'` → unresolved references (`tandemMarkdownProcessor`, `linkTarget`, `TandemBlockRenderer`, `bubbleClamp`); the module test fails on the plugin.xml assertions (the `Class.forName` lines fail only until the two `bundledModule` lines exist; the test task loads the plugin with the core class loader, so the runtime check of the `<module>` lines is Step 5's `verifyPlugin`, T16's IDE run and `runIde` checklist 10).
- [ ] **Step 3: Implement** the Interfaces block and the two declarations: `build.gradle.kts` `bundledModule("intellij.platform.jewel.markdown.extensions.gfmTables")`, `bundledModule("intellij.platform.jewel.markdown.extensions.gfmStrikethrough")`; `plugin.xml` `<module name="intellij.platform.jewel.markdown.extensions.gfmTables"/>`, `<module name="intellij.platform.jewel.markdown.extensions.gfmStrikethrough"/>`. `ChatPresenter`'s `processor` field becomes `tandemMarkdownProcessor()`: one instance per presenter, evaluated once at construction, which T7's `parseMarkdown` uses whenever no `parse` is injected (spec 4.4: the processor stays a presenter field; tests that inject `parse` are unchanged); `TandemMarkdownStyling` provides `LocalUrlHandler`, and the four `Markdown(...)` call sites pass `onUrlClick = LocalUrlHandler.current`: `TextRowItem` (text rows), `ToolBodyView`'s `ToolBody.Markdown` body and its `ToolBody.Links` summary, and `PlanCardView`'s plan body.
- [ ] **Step 4: Run** the three classes, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS; then `grep -rn "Color(0x\|Color\.White\|Color\.Black" src/main/kotlin/dev/daarkolabs/tandem/ui` → exactly three files: `TandemColors.kt` (spark), `TandemToolWindow.kt` (the drawer scrim `Color.Black.copy(alpha …)`) and `Modifiers.kt` (`bottomFade`'s `DstIn` alpha mask `start to Color.Black, 1f to Color.Transparent` with `start = 1 - height / boxHeight`, slice A's recorded deviation from 4.3's `0.7f`: `height` is the fade band, the caller clamps and clips; a mask, not an on-screen colour; leave it, and do not restore `0.7f`).
- [ ] **Step 5: Run** `./gradlew verifyPlugin --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (≈ 2 min). Expected: no problem naming `TandemBlockRenderer` against 2026.2.3 (0.41.0). If one is reported: delete `TandemBlockRenderer` and the `TandemPluginTest` test `the block renderer stays a TandemBlockRenderer when extended`; pass `markdownBlockRenderer = MarkdownBlockRenderer.create(styling, extensions)` (the bridge extension on the Companion: `import org.jetbrains.jewel.intui.markdown.bridge.create`); keep `tables and strikethrough parse with the extensions`, `links open only http and project paths` and `the GFM table and strikethrough modules are visible to the plugin` unchanged; then write "fence copy button deferred to 2b (verifyPlugin: <the reported problem>)" into `/home/user/tandem/scratch/t14-notes.md` (absolute, like the init script) and into the T14 commit body (Step 6 adds it as a second `-m`), for T16's PR body (spec 4.4).
- [ ] **Step 6: Commit** `git add build.gradle.kts src/main/resources/META-INF/plugin.xml src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ui/TandemMarkdownTest.kt src/test/kotlin/dev/daarkolabs/tandem/ui/UserBubbleTest.kt src/test/kotlin/dev/daarkolabs/tandem/ide/TandemPluginTest.kt && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(ui): GFM tables and strikethrough, fence copy button, warning banner and prompt clamp"`

---

### Task T15: reload-parity

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptReader.kt` (`Scan.accept`: the assistant line's stamp, `(message as? CliMessage.Assistant)?.timestampMillis ?: 0L`; `Scan.userLine`: the user `Cli` and `UserSent` stamps, `user.timestampMillis ?: 0L`; `readFrom`: `cwd`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptReaderTest.kt`, `.../chat/ChatRowsBuilderTest.kt`

**Interfaces:**
- Consumes (T1): `CliMessage.User.timestampMillis`, `CliMessage.Assistant.timestampMillis`, both name sets decoded by `Ndjson`; (T2): `SessionEvent.Cli(message, atMillis)`, `SessionEvent.UserSent(uuid, text, atMillis)`, `ChatState.cwd`, `ChatEntry.User.sentAtMillis`, `ChatEntry.ToolResult.result / decision / atMillis`, `AssistantBlock.Thinking.durationMs`; (T4/T5): `ChatRowsBuilder`, `ToolRow`, `ToolCall`, `ToolKind`, `ThinkingRow`, `WorkingRow`, `TurnEndRow`; the reader's existing `TranscriptMeta.cwd` (the `cwd` of the first non-abandoned line that has one, of any type; Global Constraints records the deviation from 5.5) and `readFrom`'s `state.copy(activeModel = meta.lastModel ?: …)` block.
- Produces (no new public API; spec 5.5):
  ```kotlin
  // Scan.accept (the assistant line): reduce(state, SessionEvent.Cli(message, atMillis = (message as? CliMessage.Assistant)?.timestampMillis ?: 0L))
  // Scan.userLine: reduce(state, SessionEvent.Cli(user, atMillis = user.timestampMillis ?: 0L));
  //               reduce(state, SessionEvent.UserSent(user.uuid ?: "line-$lineCount", typed.text, atMillis = user.timestampMillis ?: 0L))
  // readFrom: the settled state gains cwd = meta.cwd ?: state.cwd, next to activeModel / permissionMode / effort
  ```

- [ ] **Step 1: Write the failing tests** (`read(name)` = `runBlocking { TranscriptReader.read(Fixtures.transcript(name)) }.state`; `result(state, id)` = the `ChatEntry.ToolResult` with `toolUseId == id`)

```kotlin
// TranscriptReaderTest (names from spec 10.2)
`a recorded question session reloads with its answers and decision`
  // ask-single: result(state, "<id-12>").result!!.jsonObject["answers"]!!.jsonObject["Which colour?"]!!.jsonPrimitive.content == "Blue", !isError
  // ask-deny: the ToolResult for AskUserQuestion: isError && decision!!.decision == "reject" && decision.nonExecutionKind != null
`a recorded edit reloads with its patch`                        // tools-edit: some ToolResult whose result!!.jsonObject["structuredPatch"]!!.jsonArray.isNotEmpty(); the Write's result has ["type"] == "create"
`a recorded thinking block reloads with its duration`           // think-default: an Assistant entry holding Thinking(text == "", durationMs == 2143L)
`a recorded agent session reloads the agent result without children`
  // agent-sync: result(state, "<id-12>").result!!.jsonObject: ["totalToolUseCount"] == 2, ["totalTokens"] == 21781, ["totalDurationMs"] == 5829; no Assistant entry with parentToolUseId != null; no ToolResult with parentToolUseId != null
`timestamps and cwd are read from the lines`                    // ask-single: cwd == "<path>"; every ChatEntry.User has (sentAtMillis ?: 0L) > 0L; every ChatEntry.ToolResult has (atMillis ?: 0L) > 0L
`a transcript without timestamps or cwd still loads`
  // Review Focus 4: a @TempDir file of history-reply.jsonl's lines 3 and 18, with line 18's "parentUuid" set to line 3's "uuid" ("<uuid-17>"; its recorded parent, <uuid-27>, is an attachment line left out)
  //   and both lines' "timestamp" and "cwd" keys removed → shape() == ["user: Reply with the single word: pong", "assistant: pong"], cwd == null, (User.sentAtMillis ?: 0L) == 0L
// ChatRowsBuilderTest (spec 10.3)
`a reloaded transcript builds the same tool rows as its stream`
  // PROBE = Fixtures.names().filter { it.substringBefore("-") in setOf("ask", "plan", "think", "tools", "agent", "misc") }; assertEquals(29, PROBE.size)
  // stream(name) = the final state of T4's shared `replay(name)` in `chat/Replay.kt` (UserSent("u${i + 1}", PROMPT, atMillis = i * 100L) before every system/init, then Cli(m, atMillis = i * 100L)), i.e. `replay(name).first.settled()`; no second helper
  // build(s) = ChatRowsBuilder(RowOptions(showThinking = true)).build(s), a fresh builder per call (showThinking on, so the thinking parity and "Thought for 2s" are real checks)
  // reload(name) = read(name)
  // sig(rows) = rows.mapNotNull { when (it) { is ToolRow -> listOf(it.label.text, it.state, it.stats, it.calls.map { c -> listOf(c.label.text, c.state, c.stats, (if (c.kind == ToolKind.AGENT) null else c.body::class), c.progress) }); is ThinkingRow -> "thinking"; else -> null } }
  //   children ignored (5.5); thinking by presence; the Agent body follows its children, which are the 5.5 difference: a forwarded TextRow child makes it None live, its absence on disk makes it Markdown
  // for each name in PROBE: assertEquals(sig(build(stream(name)).rows), sig(build(reload(name)).rows), name); build(reload(name)).rows.none { it is WorkingRow || (it is TurnEndRow && it.end == TurnEnd.COMPLETED) }
  // plus: think-default's ThinkingRow label is "Thought for 2s" on both sides; agent-sync's Agent call has progress "Done (2 tool uses · 21.8k tokens · 5.8s)" on both sides and children.isEmpty() on reload;
  //   agent-forwarded: the reload's Agent call has body == ToolBody.Markdown("7", null) and children.isEmpty(). The live body (ToolBody.None) and children are pinned by T13's
  //   `agent-forwarded` golden, not here, so T15's assertions need only T4's data
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.transcript.TranscriptReaderTest' --tests 'dev.daarkolabs.tandem.chat.ChatRowsBuilderTest'` → `cwd`, send-time and `atMillis` assertions fail; the parity test fails on labels that need `cwd` (`Read <path>`) or shows a fixture whose on-disk twin decodes differently.
- [ ] **Step 3: Implement** the three stamps and the `cwd` copy in `TranscriptReader.kt`. A parity failure on one fixture is a decoder or builder gap on one side (T1's name sets, T4's `result ?: JsonPrimitive(text)` fallback), never a reason to drop the fixture from `PROBE`.
- [ ] **Step 4: Run** the two classes, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`session.transcript` stays ≥ 90 %).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptReader.kt src/test/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptReaderTest.kt src/test/kotlin/dev/daarkolabs/tandem/chat/ChatRowsBuilderTest.kt && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(session): reload transcripts with timestamps and cwd so rows match the live stream"`

---

### Task T16: ide-tests-and-docs

**Files:**
- Create: `scripts/make_long_reply.py`, `scripts/test_make_long_reply.py`, `src/test/resources/fixtures/synthetic-long-reply.ndjson` (generated by the script, committed)
- Modify: `src/test/resources/fake-claude.py`, `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`, `CLAUDE.md`, `docs/protocol.md`, `docs/design.md`, `docs/superpowers/research/2026-10-18-protocol-probes.md`
- Test: the two IDE tests below; `cd scripts && python3 -m unittest`

**Interfaces:**
- Consumes (T7): the `turn-footer` node (`starts-with(@visible_text, 'Worked for')`), `toolRow(label)`; (T8): the working row (`@contentdescription='Claude is working'` / `'Claude is waiting for you'`, its merged text as `@text`, `@visible_text` as the fallback); (T9): `iconButton("Send")` / `iconButton("Stop")`, the transcript container (`@contentdescription='Transcript'`), the pill (`@contentdescription='Jump to bottom'`, text `↓ N new`), `typePrompt`; (T10): `FOCUSED_IN_CARD` (companion const of `ToolWindowIdeTest`) and `Driver.answerCard()` (T10's: waits up to 10 s for the card's focus, asserts it sits on `Allow once` and presses Enter, else clicks `Allow once`; T16 defines neither and calls it as is), the card titles `Allow Claude to Run touch tandem-fixture.txt?` / `Allow Claude to Run rm tandem-fixture.txt?`; the sidebar test's seeding of `CLAUDE_CONFIG_DIR` and `runChat`/`ideContext`; (T4): `CLAUDE.md`'s `chat/` layout and Kover lines, already written.
- Produces:
  ```
  fake-claude.py: TANDEM_FAKE_LINE_DELAY_MS — sleep this long (ms) before emitting each stream_event line; unset or 0 → no sleep (every existing test unchanged); documented in the docstring and CLAUDE.md's fake-CLI line
  scripts/make_long_reply.py: def main(fixtures_dir: str, paragraphs: int = 60) -> None, plus an `if __name__ == "__main__":` argparse wrapper (<fixtures-dir> [--paragraphs N]), following copy_transcript.main; the tests call make_long_reply.main(tmp, 3).
    It writes <fixtures-dir>/synthetic-long-reply.ndjson from <fixtures-dir>/plain-reply.ndjson:
    lines 1–7 verbatim (through message_start); then for k in 1..N: content_block_start / content_block_delta / content_block_stop are copies of plain-reply's lines 8 / 9 / 11 with event.index = k−1,
    the delta text replaced by "Paragraph k of a long reply that must overflow the viewport while it streams." (k < N) or "pong" (k == N), and uuid "<uuid-" + (1000 + 3(k−1) + j) + ">" for j = 1, 2, 3 (start, delta, stop);
    then plain-reply's line 10 (the complete assistant line) with content = the N text blocks; then lines 12–15 (message_delta, message_stop, post_turn_summary, result) with line 15's "result" = the paragraphs joined by "\n\n". Every other field (session_id "<session_id-2>", api_message_id "<id-8>") stays plain-reply's, so a resumed session's init matches its id.
    Docstring: the only unrecorded fixture; one text block per paragraph so each one is a new row while it streams (the ↓ N new count). No timestamps, no randomness.
  ToolWindowIdeTest additions:
    runChat / ideContext gain lineDelayMs: Int? = null → withEnv("TANDEM_FAKE_LINE_DELAY_MS", it.toString())
    fun longTranscript(seeded: String, turns: Int = 30): String — history-reply.jsonl's line 3 (user) and line 18 (assistant) repeated turns times with the user text "Prompt <n> of the seeded history", the assistant text "Reply <n>.", uuids "u<n>" / "a<n>", message.id "m<n>" on each assistant line (the reducer merges equal message ids), parentUuid chained (user n → "a<n−1>" or null, assistant n → "u<n>"), then {"type":"custom-title","customTitle":"Seeded","sessionId":"$seeded"}
    private fun Driver.workingRow(description: String): UiComponent = ui.x("//div[@class='ComposeNode' and @contentdescription='$description']")
    private fun Driver.node(description: String, textPredicate: (attr: String) -> String): UiComponent = ui.x("//div[@class='ComposeNode' and @contentdescription='$description' and ((${textPredicate("@text")}) or (${textPredicate("@visible_text")}))]")   // @text first, @visible_text as the fallback (10.6)
    const val FOOTER = "//div[@class='ComposeNode' and starts-with(@visible_text, 'Worked for')]"; const val TRANSCRIPT = "//div[@class='ComposeNode' and @contentdescription='Transcript']"   // both in the existing `private companion object`
  ```
  Departures from spec 10.6, both forced by the fixtures: (1) one text block per paragraph and `TANDEM_FAKE_LINE_DELAY_MS=70` (183 stream lines ≈ 12.8 s) instead of one block at 200 ms, because `FollowState` counts appended keys and one growing `TextRow` would show `↓`, never `↓ N new`; (2) `permission.ndjson` is two turns (result :35, init :40), so the working-indicator test sees a footer after the first card and sends a second prompt, as T10's permission test does. Both go in the PR body's testing notes (Step 11).

- [ ] **Step 1: Write the script and its tests first** (`scripts/test_make_long_reply.py`, run from `scripts/`: `python3 -m unittest test_make_long_reply -v` → fails on import)

```python
class MakeLongReplyTest(unittest.TestCase):   # a temp fixtures dir holding a copy of ../src/test/resources/fixtures/plain-reply.ndjson
    def test_shape(self):          # 60 paragraphs: every line parses; exactly 60 content_block_delta lines; their indices are 0..59; the last delta text == "pong"; the assistant line has 60 text blocks whose last text == "pong"; the result line's "result" ends with "\n\npong"; the system/init line is plain-reply's; "<session_id-2>" appears in the init line
    def test_output_is_deterministic(self):   # two runs write identical bytes
    def test_paragraph_count_option(self):    # make_long_reply.main(tmp, 3) → 3 deltas, 3 blocks, the third == "pong"
```

- [ ] **Step 2: Implement `make_long_reply.py`** (stdlib `json`, `argparse`, `os`), run it: `python3 scripts/make_long_reply.py src/test/resources/fixtures` → the 41st `.ndjson`; `cd scripts && python3 -m unittest -v` → PASS. Then `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS: the every-fixture sweeps (`FixtureDecodeTest`, `RobustnessTest`, `ReducerTest` entry index, `ChatRowsBuilderTest` keys) now count 41; the T15 parity test ignores it by prefix. A sweep failure is a generator bug (fix the script and regenerate), never a sweep change.
- [ ] **Step 3: Add `TANDEM_FAKE_LINE_DELAY_MS` to `fake-claude.py`** (`line_delay_ms = int(os.environ.get("TANDEM_FAKE_LINE_DELAY_MS") or 0)`; in the replay loop, `if kind == "stream_event" and line_delay_ms: time.sleep(line_delay_ms / 1000)` before `emit`), the docstring line, and the CLAUDE.md Commands line that starts "The fake CLI (see its docstring) reads" (append `and TANDEM_FAKE_LINE_DELAY_MS (sleeps before each stream_event line)` after the `TANDEM_FAKE_GATE_FILE` clause). Run `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.SessionManagerTest'` → unchanged PASS (the knob is unset).
- [ ] **Step 4: Write the two IDE tests** (spec 10.6, decision 11)

```kotlin
@Test fun `the working indicator shows while a turn runs and a footer after it`() {
    val gate = Files.createTempDirectory("tandem-gate").resolve("open")
    val sent = runChat("working", fixtures = listOf("permission"), gateFile = gate) {
        newSession(); typePrompt("Create the fixture file")
        waitFor("working row with elapsed", 1.minutes) { node("Claude is working") { "contains($it, ' · ') and substring($it, string-length($it)) = 's'" }.present() }   // one merged node: "<Verb>… · Ns"
        gate.createFile()
        waitForText("Allow Claude to Run touch tandem-fixture.txt?")
        waitFor("amber waiting row", 1.minutes) { workingRow("Claude is waiting for you").present() }
        answerCard()
        waitFor("footer, working row gone", 2.minutes) { ui.x(FOOTER).present() && !workingRow("Claude is working").present() && !workingRow("Claude is waiting for you").present() }
        typePrompt("Now remove it")                                   // permission.ndjson:40 starts the fixture's second turn; the second request (:52) belongs to it
        waitForText("Allow Claude to Run rm tandem-fixture.txt?")
        waitFor("waiting again", 1.minutes) { workingRow("Claude is waiting for you").present() }
        iconButton("Deny").click(Point(10, 10))
        waitFor("denied row and footer", 2.minutes) { toolRow("Remove fixture file · Denied").present() && ui.x(FOOTER).present() }
    }
    val answers = sent.filter { it.type == "control_response" }.map { it["response"]!!.jsonObject["response"]!!.jsonObject["behavior"]!!.jsonPrimitive.content }
    assertEquals(listOf("allow", "deny"), answers)
}
@Test fun `scrolling up pauses following and the jump button catches up`() {
    val configDir = Files.createTempDirectory("tandem-config"); val project = newProject()
    val seeded = "55555555-5555-4555-8555-555555555555"
    configDir.resolve("projects").resolve(TranscriptFolder.key(project.toRealPath().toString())).createDirectories().resolve("$seeded.jsonl").writeText(longTranscript(seeded))
    val sent = runChat("autoscroll", fixtures = listOf("synthetic-long-reply"), configDir = configDir, project = project, lineDelayMs = 70) {   // 183 stream lines × 70 ms ≈ 12.8 s
        openSidebar(); row("Seeded").click(Point(10, 10))
        waitFor("seeded history shown, prompt focused", 1.minutes) { ui.x("$PROMPT_BOX[@focused='true']").present() }
        typePrompt("Write me a long reply")
        waitFor("streaming", 30.seconds) { iconButton("Stop").present() }
        ui.x(TRANSCRIPT).click(Point(10, 10)); ui.keyboard { key(KeyEvent.VK_HOME) }            // inside the 16 dp padding: the press focuses the list, no row takes it
        waitFor("jump button offered", 30.seconds) { iconButton("Jump to bottom").present() }
        waitForText("Prompt 1 of the seeded history")                                          // the first bubble is in the viewport
        Thread.sleep(3_000); assertFalse(ui.x("//div[@class='ComposeNode' and @visible_text='pong']").present(), "the viewport moved while paused")
        assertTrue(iconButton("Stop").present(), "the turn is still streaming")
        assertTrue(node("Jump to bottom") { "starts-with($it, '↓ ') and substring($it, string-length($it) - 3) = ' new'" }.present(), "rows were appended while paused")
        assertTrue(ui.x("//div[@class='ComposeNode' and @visible_text='Prompt 1 of the seeded history']").present(), "still at the top")
        iconButton("Jump to bottom").click(Point(10, 10))
        waitForText("pong")
        waitFor("jump button gone", 30.seconds) { !iconButton("Jump to bottom").present() }
    }
    assertEquals(1, sent.count { it.type == "user" })
}
```

- [ ] **Step 5: Run** `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests '*working indicator*' --tests '*jump button*'`, then the whole `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → nine tests PASS. On a locator miss read `out/ide-tests/…/ui-hierarchy/ui.html` and fix the predicate once (open item 8: `@text` vs `@visible_text`). Then read the three new tests' `time` attributes in `build/test-results/integrationTest/TEST-dev.daarkolabs.tandem.ToolWindowIdeTest.xml` (question card, working indicator, autoscroll): if their mean is under 150 s add `the plan card approves and the picker follows` (`plan-approve`: `newSession(); typePrompt("Plan a changelog"); waitForText("Approve this plan?"); iconButton("Approve and start coding").click(Point(10, 10)); waitFor("mode follows the approval", 1.minutes) { picker("Permission mode", "Accept edits").present() }`, then assert that the sent control_response's `updatedPermissions[0]["type"] == "setMode"`), run it alone, and rerun the suite; otherwise leave the plan card pinned by 10.2/10.4 and say so in the PR body.
- [ ] **Step 6: Commit the tests** `git add scripts/make_long_reply.py scripts/test_make_long_reply.py src/test/resources/fixtures/synthetic-long-reply.ndjson src/test/resources/fake-claude.py src/integrationTest CLAUDE.md && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "test: IDE tests for the working indicator and autoscroll on a generated long reply"`
- [ ] **Step 7: Docs**, one edit each:
  - `docs/protocol.md`: 1.1 gains `--thinking-display summarized` (after `--include-partial-messages`); 4.2 the `initialize` body with `"forwardSubagentText":true`; 4.3 `requires_user_interaction`, `decision_reason_type`, `agent_id` on `can_use_tool`; 4.4 a `set_max_thinking_tokens` success carries no `response` (not sent by Tandem, spec 5.2); 5.2 `thinking_tokens`, `task_started`, `task_progress`, `task_updated`, `task_notification` as typed lines with their fields (5.1); 6.1 `thinking_duration_ms`, `thinking_display`, `agent_id`, `subagent_type`, `task_description`, `timestamp`; 6.3 `estimated_tokens` on thinking deltas, `usage` on `message_start` / `message_delta`; 7 `duration_ms`, `duration_api_ms`, `num_turns`, `usage.output_tokens`, `permission_denials`; 10 moves `user.tool_result_meta` to "typed, section 6.2"; 11.4 gains the 5.5 table (what a reload shows per row element) and the line "`cwd`: the first line's `cwd`, so relative file names match the live session".
  - `docs/superpowers/research/2026-10-18-protocol-probes.md` §2.4, the sentence ending `(agent-sync:47, agent-forwarded:37, which also lack tool_use_result)`: `tool_result_meta` **is** on both lines (`reason_type:"subcommandResults"`); only `tool_use_result` is missing there (spec 10.1, pinned by `agent-sync: task lines decode and subagent lines carry agent ids`).
  - `docs/design.md`: the architecture block's `ui/` line names the chat rows, cards and composer (`chat/` was added in T4); under Milestones, after item 1: `**Phase 2a, chat redesign.** The desktop app's chat feel: typed tool rows with grouping, thinking rows, the working indicator, the permission / question / plan cards, intent-based autoscroll, subagent nesting, reload parity; spec docs/superpowers/specs/2026-10-19-chat-redesign-design.md. **Status: implemented on feat/chat-redesign**, covered by fixture, pure-presentation (chat/), platform and IDE Starter tests; feel is checked by hand (spec 10.7).`
  - `CLAUDE.md` hard rule 6 gains: `synthetic-long-reply.ndjson is generated by scripts/make_long_reply.py and is the only unrecorded fixture.`
- [ ] **Step 8: Hard rule 8 grep** `grep -rn "Claude Code" src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/ide/TandemSettingsConfigurable.kt` → no output, then `sed -n '/^### 4.20/,/^None says/p' docs/superpowers/specs/2026-10-19-chat-redesign-design.md | grep -v '^None says' | grep -n "Claude Code"` → no output (the 4.20 table rows; `grep -v` drops the closing sentence, which names the rule) (the allowed "works with Claude Code" lines live in `plugin.xml`, `SessionManager.kt`, `Reducer.kt`, `ClaudeSession.kt`, spec 4.20). Any hit is renamed to `Claude`.
- [ ] **Step 9: Verify and commit the docs** `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` and `./gradlew verifyPlugin --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → both green; `git add docs/protocol.md docs/design.md docs/superpowers/research/2026-10-18-protocol-probes.md CLAUDE.md && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "docs: protocol fields, reload parity and the chat redesign milestone"`
- [ ] **Step 10: The owner's `runIde` pass** `./gradlew runIde --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` and hand over spec 10.7 items 1–11 (LaF switch, spark cycle, streaming feel and the `-Ptandem.spike=true` stall monitor, trackpad wheel-up, tool rows, the three cards, thinking rows, tables and the copy button, 350 px and 1200 px), plus the 8-line bubble clamp with Show more (and its Show less). Only a person judges these; the PR lists the items and the open items they decide (spec 13: 1, 2, 3, 4, 6, 9, 10, 20, 21, 23).
- [ ] **Step 11: Pull request** write the body to `/home/user/tandem/scratch/pr-body.md`, show it to the owner and, once they agree, run `git push -u origin feat/chat-redesign` and `gh pr create --base feat/sessions-and-pickers --title "feat: chat redesign (desktop-app feel)" --body-file /home/user/tandem/scratch/pr-body.md`. The body: context (the four complaints and decisions 1–12 of the spec), approach (protocol → reducer → `chat/` → `ui/`, three slices), testing notes (`check` with Kover, the nine IDE tests and their wall-clock, the two departures from spec 10.6 listed under T16 Interfaces, `verifyPlugin`, the `runIde` checklist for the owner), follow-ups: record a `plan-enter` fixture (`EnterPlanMode` from a non-plan session) to decide whether its row can be hidden (spec 4.6.2), the fence copy button if T14 deferred it (read `/home/user/tandem/scratch/t14-notes.md` and `git log --grep "fence copy button deferred"`), the redactor follow-ups of probes §3.4. No attribution lines, no session link.

---

Slice-C review with the owner: open item 7 (the copy button survived `verifyPlugin` or was deferred), open item 19 (the parity test accepts the Agent row without children), open item 8 (which Driver attributes the new locators needed), and the `runIde` checklist results that decide open items 3, 4, 9, 20 and 23.

# Chat Redesign Slice C (Polish and Parity) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the chat redesign: subagent activity nested under its Agent row, GFM tables and strikethrough with a fence copy button, the last hard-coded colours out and the long-prompt clamp, transcript reload parity pinned over the 29 probe fixtures, the two remaining IDE Starter tests, the docs, and the PR.

**Architecture:** Slice A built the pure `chat/` row model (T4–T6) and the keyed `ui/` list (T7–T9); slice B the cards (T10–T12). Slice C adds only leaves and parity: `AgentBody` renders the `ToolCall.children` T4 already routes (T13); `TandemMarkdown.kt` wraps Jewel's bridge styling with the two GFM extensions and a `DefaultMarkdownBlockRenderer` subclass (T14); `TranscriptReader` stamps the events it already feeds `reduce` with the line's `timestamp` and `cwd`, and one golden test proves a reloaded transcript builds the same tool rows as its stream (T15); T16 adds the fake-CLI delay knob, the generated long-reply fixture, the working-indicator and autoscroll IDE tests, the doc updates and the PR.

**Tech Stack:** Kotlin 2.4.20, IntelliJ Platform 2026.1.5 (Jewel 0.37.0: `DefaultMarkdownBlockRenderer`, `GitHubTableProcessorExtension`, `GitHubStrikethroughProcessorExtension`, `GfmTableStyling.create()`, `InlineWarningBanner`, `IconActionButton`), kotlinx.collections.immutable, JUnit 5, IDE Starter + UI Driver, the Python fake CLI, Python 3 stdlib for the scripts.

**Spec:** `docs/superpowers/specs/2026-10-19-chat-redesign-design.md`: sections 4.3 (clamp), 4.4 (Markdown, links, copy button), 4.8 (subagents), 4.11 (banner), 5.5 (reload parity), 5.6, 6 (`AgentBody.kt`, `TandemMarkdown.kt`), 10.2 (reader tests), 10.3 (`ChatRowsBuilderTest` parity), 10.4 (`TANDEM_FAKE_LINE_DELAY_MS`), 10.5 (`TandemPluginTest`), 10.6 (the two IDE tests), 10.7 (the owner's `runIde` checklist), 12 (T13–T16), open items 7, 8, 19. Reference: `docs/superpowers/research/2026-10-18-claude-code-desktop-ui-reference.md`; probes: `docs/superpowers/research/2026-10-18-protocol-probes.md`.

**Dependencies (spec 12, refined):** T13 [T7, T10], T14 [T7, T11, T12] (spec 12 lists T14 [T7]; this plan's T14 consumes T12's `openInEditor`, edits T12's `PlanCardView` and the `PendingCardHost` that T10–T12 change), T15 [T4], T16 [all]. T13 ‖ T15 once T10 has landed; T14 once T11 and T12 have landed; T15 may start right after T4; T16 last.

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

## Global Constraints

- CLAUDE.md hard rules, verbatim, each with what it means in slice C:
  1. **Never block the EDT.** No process I/O, file I/O, JSON parsing or waiting on the CLI from the UI thread. Use the platform coroutine scopes (`platform-coroutines-structured-concurrency` skill). (Markdown is parsed in `ChatPresenter` on `Dispatchers.Default`; the link handler's VFS lookup runs on `Dispatchers.IO` through T12's `openInEditor`; no click handler parses.)
  2. **No Node, no Agent SDK at runtime.** We spawn the user's `claude` binary directly. Do not add a sidecar. (`make_long_reply.py` and the fake CLI are test tools, never shipped.)
  3. **Never touch credentials.** Auth belongs to the CLI. Do not read `~/.claude/.credentials*`, keychains or API keys, and never offer a login flow.
  4. **Edits go through the IDE, not the disk.** Claude's built-in Edit/Write tools are disabled; our MCP edit tools apply changes with `WriteCommandAction` on the `Document`, so undo, unsaved buffers and local history work. Every edit shows a native diff before it lands, unless the user picked auto-accept. (Slice C changes no edit path; a Markdown link only opens a file in the editor.)
  5. **Never fail silently.** If the CLI falls back to another model, rejects a model, retries, or errors, the user sees it in the chat. Compare the model in the `system/init` event (and fallback events) with what the user picked. (A subagent entry with an unknown parent, a progress line with missing usage and a transcript line without `timestamp` or `cwd` all still render.)
  6. **Every protocol change gets a fixture test.** Recorded transcripts live in `src/test/resources/fixtures/`. Unknown message types must parse to `Unknown(raw)`, never throw. (`synthetic-long-reply.ndjson`, T16, is the one generated fixture; CLAUDE.md says so.)
  7. **Do not bundle kotlinx.coroutines or the Kotlin stdlib.** The platform provides both. (The two GFM modules are the IDE's bundled modules, declared, not shipped.)
  8. **Do not call the product "Claude Code"**, in the plugin name, ID or UI. Use "Tandem" and say it works with Claude Code. (Never "Claude Code" in `chat/` or `ui/` strings or the 4.20 strings; `Claude` alone is fine; T16 greps.)
- Layers: `chat/` stays Compose-free; `session/transcript/` keeps its imports (JDK, coroutines, serialization, `com.intellij.openapi.diagnostic`); `ui/` holds no protocol logic. Theme tokens only (spec 4.2): after T14 the only literal colours in `ui/` are `TandemColors.spark` and the drawer scrim.
- Jewel 0.37.0 as bundled in 2026.1.5; `verifyPlugin` runs against every supported release (0.41.0 on 2026.2.3), so T14 runs it and drops the renderer subclass if it is reported (spec 4.4, open item 7). Any bundled module used needs both a `bundledModule(...)` line in `build.gradle.kts` and a `<module name=.../>` in `plugin.xml`.
- Strings exactly as spec 4.20: `Copy code`, `Show more`, `Show less`, `From the agent "<d>"`, `Done (N tool uses · T tokens · D)`, `N tool uses · T tokens · D` (`1 tool use` singular), `Claude is working`, `Claude is waiting for you`, `Jump to bottom`, `Transcript`, `Worked for <duration>`; fake-CLI knob `TANDEM_FAKE_LINE_DELAY_MS`.
- Fixtures: `name:line` is the 1-based line of `src/test/resources/fixtures/<name>.ndjson`; `transcripts/<name>.jsonl` its on-disk twin; paths are literally `<path>`; ids are placeholders (`<id-12>`, `<request_id-52>`). A reloaded transcript in a test is `runBlocking { TranscriptReader.read(Fixtures.transcript(name)) }.state`. The 29 probe fixtures are the `.ndjson` files whose name starts with `ask-`, `plan-`, `think-`, `tools-`, `agent-` or `misc-`; every one has a `.jsonl` twin. `synthetic-long-reply` (T16) is the 41st `.ndjson`, the only unrecorded one, with no twin.
- Test names are the spec's where it gives one (backtick JUnit 5 names); new tests in a class keep that class's style. Pure functions are tested without composition, as `PickersTest` and `CardKeysTest` do; no Compose UI test harness exists (tests.md).
- Gradle: every command carries `--init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (the absolute path works from a git worktree). One class: `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.<pkg>.<Class>'`; full suite: `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts`; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` adds `koverVerify` (90 % lines per package of `protocol/`, `session/` and `chat/`); IDE tests: `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (1.5–3 min per test alone, about 12 min for all after T16); `./gradlew verifyPlugin --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` ≈ 2 min. Every task ends with `check` green (plus the IDE tests in T16) and one or two commits. CLAUDE.md's other Commands lines, verbatim (Gradle ones with the init script):
  - `./gradlew koverLog --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` line coverage of `protocol/` and `session/` per package after the tests (Kover, build-time only); `koverHtmlReport` writes `build/reports/kover/html/`. `./gradlew check` runs `koverVerify`, which fails under 90%.
  - `./gradlew runIde --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` sandbox IDE with the plugin. Use it to check feel, freezes and focus; agents cannot judge these.
  - `./gradlew buildPlugin --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` installable ZIP in `build/distributions/`.
  - `scripts/record-fixture.sh <name> "<prompt>"` records a real CLI transcript as a test fixture. Needs a logged-in `claude`.
  - `cd scripts && python3 -m unittest` tests the recording scripts (the redactor's safety net, `copy_transcript.py`, `drive_cli.py`'s control calls) without Gradle or a real `claude`.
- Spec 10.6's 200 ms assumes one stream line per paragraph; `synthetic-long-reply` emits three per paragraph (start, delta, stop), so the autoscroll test uses `TANDEM_FAKE_LINE_DELAY_MS=70` for the same ≈ 12 s stream (T16 Interfaces lists both departures from 10.6).
- Commits: Conventional Commits, `git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>"`, no trailers, stage by path; branch `feat/chat-redesign` → PR against `feat/sessions-and-pickers`; no attribution lines and no session link in the PR body (CLAUDE.md).

## Review Focus

1. A subagent `assistant` or `tool_result` entry whose `parentToolUseId` names no Agent call in the state (a retracted Agent block, an unknown parent) must not vanish: it renders at top level → T13 `a subagent entry whose agent call is unknown is not dropped`.
2. A `task_progress` line whose `usage` lacks a field must show no sub-line, never `null tool uses · null tokens` → T13 `a progress line with missing usage shows no sub-line`.
3. A Markdown link to a path outside the project (`../x`, `/etc/passwd`) or with no project base must open nothing, without an exception → T14 `links open only http and project paths`.
4. A transcript whose lines carry no `timestamp` and no `cwd` (older CLI) must reload with `cwd == null`, no positive send time, and the same entries → T15 `a transcript without timestamps or cwd still loads`.
5. A Markdown link whose text is not a valid path (`"a\u0000b"`) must open nothing and throw nothing → T14 `links open only http and project paths`.

---

### Task T13: ui-subagents

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/rows/AgentBody.kt`
- Modify: `.../ui/rows/ToolRowItem.kt` (progress sub-line, `canExpand`, body dispatch), `.../ui/ChatPresenter.kt` (`expandBody` over children, the cache rule), `.../ui/ChatPanel.kt` (`agentExpanded`, and `RowItem` where T7 put it), `.../ui/rows/WorkingRowItem.kt` (`agentExpanded`), `.../chat/ChatRow.kt` (`WorkingRow.agentStep`), `.../chat/ChatRowsBuilder.kt` (`agentStep`, and only where the goldens below find T4's data short: `progress` while a task runs, the Agent body forms, an unknown-parent entry; `.../chat/WorkingIndicator.kt` only if `step` must report its source)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/chat/ChatRowsBuilderTest.kt`, `.../ui/ChatPresenterTest.kt`, `.../ide/TandemSessionsTest.kt`

**Interfaces:**
- Consumes (T4): `ToolCall(id, name, kind, state, label, stats, body, children: ImmutableList<ChatRow>, progress: String?)`, `ToolLabels.agentProgress(result)` / `agentProgress(task: TaskInfo)`, `ChatRowsBuilder(RowOptions).build(state): ChatRows`; (T6): `Cards.pending(state): Pair<PendingCard?, Int>`, `PendingCard.agentDescription`, `WorkingRow(key, turnUuid, startedAtMillis, step, outputTokens, waiting, gap)`, `WorkingIndicator.verb(turnUuid, lastToolUseId)` / `view(row, nowMillis, lastFrameAtMillis, …)`; (T7): `RenderedRow(row, blocks, bodies)`, `ChatPresenter(scope, state: StateFlow<ChatState>, options).expandBody(key)`, `ToolRowItem(rendered, ui)`, `ToolBodyView(call, blocks, ui)`, `TextRowItem(rendered: RenderedRow)`, `RowItem(rendered, ui, lastFrameAt)`, `ChatUiState.expanded(key)` / `onExpand`, the `ChatPresenterTest` fake state flow; (T8): `WorkingRowItem(row, lastFrameAt)`; (T10): `internal fun agentLine(card: PendingCard): String?` in `ui/cards/PermissionCardView.kt` (= `card.agentDescription?.let { "From the agent \"$it\"" }`), which `PermissionCardView` draws under the title in place of an inline string; (T3): `SessionManager.answer`, `PermissionAnswers.allowOnce`; (`ide/`): `TandemSessions.extraEnvironment`, `TandemSessions.manager`.
- Produces:
  ```kotlin
  // ui/rows/AgentBody.kt (spec 4.8): the children indented 16 dp, then the agent's own Markdown body
  @Composable internal fun AgentBody(rowKey: String, call: ToolCall, bodies: ImmutableMap<String, ImmutableList<MarkdownBlock>>, ui: ChatUiState)
  // Column(Modifier.padding(start = 16.dp)) { call.children.forEach { child -> key(child.key) { when (child) { is ToolRow -> ToolRowItem(RenderedRow(child, null, bodies), ui); is TextRow -> TextRowItem(RenderedRow(child, bodies[child.key], bodies)); else -> {} } } }; if (call.body !is ToolBody.None) ToolBodyView(call, bodies[rowKey], ui) }
  //   TextRowItem(rendered: RenderedRow) is T7's signature, called as RowItem calls it; if T7 left a different signature, use RowItem's own call
  // ToolRowItem: canExpand = <T7's expression> || (row.calls.size == 1 && call.children.isNotEmpty())   // extend, do not restate: a running group stays unclickable (4.6.4 rule 7); a running Agent with children is clickable (4.9 expects it expanded while it runs)
  //   a lone call's progress draws as a second secondary Text at body − 1 under the label;
  //   the body of a lone AGENT call is AgentBody(row.key, call, rendered.bodies, ui); the click calls ui.onExpand(row.key) also when any child is a TextRow or carries a Markdown/Links body
  // ChatPresenter.expandBody(key): resolves key as a row key (top-level or a child row's) or a group sub-row key "c:<call id>"; for an Agent row it parses the call's own Markdown body under
  // bodies[key] and every child row's markdown (a TextRow's text, a ToolRow's Markdown/Links body) under bodies[child.key], the key ToolRowItem / TextRowItem read, one cache entry each (5.6)
  // Cache rule (5.6): every emission builds each RenderedRow.bodies from the cache entries of its own key, its "c:<call id>" keys and the keys of its Agent calls' children (recursively);
  // a parsed key stays cached, and is re-attached to its row's RenderedRow.bodies on every later emission, while that key is a top-level row, a "c:<call id>" sub-row or a child row
  // (recursively) of the latest ChatRows; only keys gone from all three are pruned
  // chat/ChatRow.kt: WorkingRow gains `val agentStep: Boolean = false` (defaulted, so T6's calls compile); the builder sets it when `step` came from a TaskInfo.description (4.9)
  // ui/ChatPanel.kt: val agentExpanded = chat.rows.any { r -> (r.row as? ToolRow)?.calls?.singleOrNull()?.let { it.kind == ToolKind.AGENT && it.state in setOf(ToolState.RUNNING, ToolState.WAITING) } == true && ui.expanded(r.row.key).value }
  //   passed through RowItem(rendered, ui, lastFrameAt, agentExpanded) to WorkingRowItem(row, lastFrameAt, agentExpanded); both gain `agentExpanded: Boolean = false`, so T7's and T8's calls compile
  // WorkingRowItem renders WorkingIndicator.view(if (row.agentStep && agentExpanded) row.copy(step = WorkingIndicator.verb(row.turnUuid, null)) else row, …)   // 4.9: the agent's step is the working row's only while its Agent row is collapsed; expanded, the nested row names it
  ```
  Builder facts the goldens pin (T4 produced the data; T13 corrects it where a golden fails): the Agent body is `ToolBody.None` when a child `TextRow` exists (forwarded text), else `ToolBody.Markdown(<tool_use_result.content[].text joined "\n\n", or task_notification.summary>, null)`; `progress` is `"<n> tool use(s) · <tokens> tokens · <duration>"` from the latest `TaskInfo` while the call runs (`null` when `toolUses`, `totalTokens` or `durationMs` is null) and `Done (…)` from the result; an entry whose `parentToolUseId` matches no Agent call is built at top level. On reload the Agent call has no children, so its body is the Markdown one even for agent-forwarded: this live/reload body difference is accepted by T15's parity test (sig skips the AGENT body).

- [ ] **Step 1: Write the failing tests** (replay helpers as T4's `ChatRowsBuilderTest` has them, with a `UserSent` before the `system/init` as T6's `the working row is last while busy and the footer replaces it` replays, so `turn` is open and calls read `RUNNING`; "after line N" = the state built after that fixture line)

```kotlin
// ChatRowsBuilderTest: extend T4's `agent-sync` and `agent-forwarded` goldens in place with the assertions below (no parallel tests); then three new tests
// (agent-sync: Agent block <id-12>, subagent Bash blocks <id-49> and <id-56>; agent(rows) = the ToolRow whose single call has kind AGENT)
`agent-sync` golden (T4's, extended): nests the subagent's calls, progress and final text
  // after line 41: agent.calls.single().progress == "1 tool use · 21.5k tokens · 2.0s"; agent.state == RUNNING; (rows.last() as WorkingRow).step == "Running wc -l seed.txt && tail -c1 seed.txt | xxd" && agentStep
  // after line 43: the child ToolRow whose call id is "<id-49>" has state WAITING; agent.state == RUNNING; Cards.pending(state).first!!.agentDescription == "Count lines in seed.txt"; rows.last() is WorkingRow && waiting
  // final: no top-level TextRow or ToolRow comes from an entry with parentToolUseId != null; no WorkingRow; agent.state == DONE; progress == "Done (2 tool uses · 21.8k tokens · 5.8s)";
  //   children.map { (it as ToolRow).label.text to it.state } == listOf("Failed to run wc -l seed.txt && tail -c1 seed.txt | xxd" to FAILED, "Ran tail -c1 seed.txt | od -c" to DONE);
  //   agent.calls.single().body == ToolBody.Markdown("7 lines. The last line ends with a trailing newline.", null)
`agent-forwarded` golden (T4's, extended): the forwarded text sits inside the agent row
  // children.map { it::class } == listOf(ToolRow::class, TextRow::class); (children[0] as ToolRow).label.text == "Ran wc -l <path>"; (children[1] as TextRow).markdown == "7"; body == ToolBody.None; no top-level TextRow with markdown "7"
`a progress line with missing usage shows no sub-line`          // Review Focus 2: agent-sync with line 41's "usage" key removed in memory → after it progress == null; after line 50 "Done (2 tool uses · 21.8k tokens · 5.8s)"
`a subagent entry whose agent call is unknown is not dropped`   // Review Focus 1: the final agent-sync state plus a hand-made ChatEntry.Assistant(parentToolUseId = "nope", agentId = "x", blocks = [Text("stray")]) appended → a top-level TextRow with markdown "stray"
`subagent calls are never grouped`                              // 4.8: agent-sync with line 44's tool_result made a success (is_error false, content "7 seed.txt") in memory → children are two ToolRows, each with calls.size == 1
// ChatPresenterTest (the fake MutableStateFlow<ChatState> of T7)
`expanding an agent row parses its nested text`
  // agent-forwarded replayed through line 38 (Agent still RUNNING): presenter.expandBody("tool:<id-12>") → the next chat emission has bodies[<the child TextRow's key>] non-null for the Agent row, parsed on a thread not named "AWT-EventQueue…"
  //   then push lines 39 to the end into the fake flow one at a time → every later emission's Agent row has bodies[<child key>] === that same blocks instance (the cache rule: child keys survive the prune and are re-attached)
  // agent-sync: expandBody("tool:<id-12>") → bodies["tool:<id-12>"] non-null (the Markdown body); a second call emits no new blocks instance
// ide/TandemSessionsTest (the T11 pattern: extraEnvironment, create, send, wait on handle.state)
`a subagent's permission request names the agent on its card and the nested row waits`
  // TANDEM_FAKE_FIXTURE=agent-sync; send("Count lines"); state = handle.state.first { it.pendingPermissions.isNotEmpty() }; p = state.pendingPermissions.single(): p.agentId != null
  // rows = ChatRowsBuilder(RowOptions(showThinking = true)).build(state): rows.card!!.agentDescription == "Count lines in seed.txt"; agentLine(rows.card!!) == "From the agent \"Count lines in seed.txt\"" (the card line end to end, spec 12); rows.moreWaiting == 0; the Agent row's child for "<id-49>" is WAITING; rows.rows.last() is WorkingRow && waiting
  // manager.answer(handle.id, p.requestId, PermissionAnswers.allowOnce(p)); handle.state.first { !it.busy }; the Agent row is DONE with progress "Done (2 tool uses · 21.8k tokens · 5.8s)"
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.ChatRowsBuilderTest' --tests 'dev.daarkolabs.tandem.ui.ChatPresenterTest' --tests 'dev.daarkolabs.tandem.ide.TandemSessionsTest'` → the goldens fail where T4's builder differs from the facts above; the presenter test fails on `bodies`; `subagent calls are never grouped` may already pass (4.8 is T4's rule).
- [ ] **Step 3: Implement** the Interfaces block: `AgentBody.kt`, the three `ToolRowItem` changes, `expandBody` over children and the cache rule, `WorkingRow.agentStep` with `agentExpanded`; fix the builder only where Step 2 showed a difference.
- [ ] **Step 4: Run** the three classes, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`chat/` stays ≥ 90 %). Optional feel check: `./gradlew runIde --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` with a real Agent prompt (collapsed by default, chevron opens the nested rows).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ide && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(ui): nest subagent rows, progress and final text under the Agent row"`

---

### Task T14: markdown-theme-polish

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/TandemMarkdown.kt`
- Modify: `build.gradle.kts` (two `bundledModule` lines after `…extensions.autolink`), `src/main/resources/META-INF/plugin.xml` (two `<module>` lines), `.../ui/ChatPresenter.kt` (its processor becomes `tandemMarkdownProcessor()`), `.../ui/ChatPanel.kt` and `.../ui/cards/PendingCardHost.kt` (`ProvideMarkdownStyling(project)` → `TandemMarkdownStyling(project)`), `.../ui/rows/TextRowItem.kt`, `.../ui/rows/ToolBodyView.kt` and `.../ui/cards/PlanCardView.kt` (`Markdown(…, onUrlClick = LocalUrlHandler.current)`), `.../ui/SessionHeader.kt` (`StatusLine`: `InlineWarningBanner`), `.../ui/rows/UserBubble.kt` (the clamp)
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/ui/TandemMarkdownTest.kt`; Modify `.../ide/TandemPluginTest.kt`

**Interfaces:**
- Consumes (T7): `ChatPresenter`'s processor field and Markdown cache, `TextRowItem`, `ToolBodyView`, `UserBubble` (one `Text` node, `testTag("user-bubble")`), `Modifier.bottomFade(height: Dp)`, `ChatPanel`'s `ProvideMarkdownStyling(project)`; (T12): `openInEditor(project: Project, path: Path)`, `PlanCardView`'s `Markdown(...)` call and `PendingCardHost`'s `ProvideMarkdownStyling(project)` around it (T10–T12 all change `PendingCardHost`, hence T14 [T7, T11, T12]).
- Produces:
  ```kotlin
  // ui/TandemMarkdown.kt (spec 4.4 verbatim)
  internal fun tandemMarkdownProcessor(): MarkdownProcessor   // MarkdownProcessor(listOf(AutolinkProcessorExtension, GitHubTableProcessorExtension, GitHubStrikethroughProcessorExtension()))
  class TandemBlockRenderer(styling: MarkdownStyling, extensions: List<MarkdownRendererExtension>, inline: InlineMarkdownRenderer = DefaultInlineMarkdownRenderer(extensions)) : DefaultMarkdownBlockRenderer(styling, extensions, inline)
  // overrides @Composable RenderFencedCodeBlock(block, styling, enabled, modifier): Box { super.RenderFencedCodeBlock(block, styling, enabled, modifier); IconActionButton(AllIconsKeys.General.InlineCopy, contentDescription = "Copy code", onClick = { CopyPasteManager.getInstance().setContents(StringSelection(block.content)) }, modifier = Modifier.align(Alignment.TopEnd)) visible on hover };
  // overrides createCopy(…) and plus(ext) to return a TandemBlockRenderer; never `by` delegation (4.4)
  @Composable internal fun TandemMarkdownStyling(project: Project, content: @Composable () -> Unit)
  // val (styling, renderer) = remember(JewelTheme.instanceUuid) { val s = MarkdownStyling.create(); s to TandemBlockRenderer(s, listOf(GitHubTableRendererExtension(GfmTableStyling.create(), s), GitHubStrikethroughRendererExtension)) }; val onUrl = rememberUrlHandler(project)
  // ProvideMarkdownStyling(project, markdownStyling = styling, markdownBlockRenderer = renderer) { CompositionLocalProvider(LocalUrlHandler provides onUrl, content = content) }
  internal val LocalUrlHandler: ProvidableCompositionLocal<(String) -> Unit> = staticCompositionLocalOf { {} }   // every Markdown(...) call site passes onUrlClick = LocalUrlHandler.current; no composable signature changes, so T13's AgentBody and T7's row signatures stay as written (T7's LocalChatProject is not needed for links)
  internal sealed interface LinkTarget { data class Browse(val url: String) : LinkTarget; data class Open(val path: Path) : LinkTarget; data object None : LinkTarget }
  internal fun linkTarget(url: String, projectBase: Path?): LinkTarget   // blank → None; http/https → Browse; any other scheme (^[A-Za-z][A-Za-z0-9+.-]*:) → None; a relative path whose projectBase.resolve(url).normalize() starts with projectBase and differs from it → Open; absolute, ".." escapes, a null base and an InvalidPathException → None
  @Composable internal fun rememberUrlHandler(project: Project): (String) -> Unit   // Browse → BrowserUtil.browse(url); Open → openInEditor(project, path); None → nothing; projectBase = project.basePath?.let(Path::of)
  // UserBubble (4.3): var lines by remember(row.key) { mutableIntStateOf(0) } from onTextLayout { lines = it.lineCount }; var shown by remember(row.key) { mutableStateOf(false) };
  // when lines > 8 && !shown: Modifier.heightIn(max = 160.dp).bottomFade(60.dp) on the clipped box and a Link("Show more") under it; shown → no clamp and Link("Show less"); still one Text node with testTag("user-bubble")
  // SessionHeader.StatusLine: InlineWarningBanner(text = warning, Modifier.fillMaxWidth()) replaces the Color(0xFFB3261E)/Color.White Text
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
    assertEquals(LinkTarget.None, linkTarget("a\u0000b", base))   // Review Focus 5: not a valid path, no exception
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
@Test fun `the block renderer stays a TandemBlockRenderer when extended`() {   // spec 4.4: plus (and createCopy) return a TandemBlockRenderer
    assertIs<TandemBlockRenderer>(TandemBlockRenderer(MarkdownStyling.create(), emptyList()).plus(GitHubStrikethroughRendererExtension))
}
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.TandemMarkdownTest' --tests 'dev.daarkolabs.tandem.ide.TandemPluginTest'` → unresolved references (`tandemMarkdownProcessor`, `linkTarget`, `TandemBlockRenderer`); the module test fails on the plugin.xml assertions (the `Class.forName` lines fail only until the two `bundledModule` lines exist; the test task loads the plugin with the core class loader, so the runtime check of the `<module>` lines is Step 5's `verifyPlugin`, T16's IDE run and `runIde` checklist 10).
- [ ] **Step 3: Implement** the Interfaces block and the two declarations: `build.gradle.kts` `bundledModule("intellij.platform.jewel.markdown.extensions.gfmTables")`, `bundledModule("intellij.platform.jewel.markdown.extensions.gfmStrikethrough")`; `plugin.xml` `<module name="intellij.platform.jewel.markdown.extensions.gfmTables"/>`, `<module name="intellij.platform.jewel.markdown.extensions.gfmStrikethrough"/>`. `ChatPresenter` keeps one `tandemMarkdownProcessor()` instance; `TandemMarkdownStyling` provides `LocalUrlHandler`, and the four `Markdown(...)` call sites pass `onUrlClick = LocalUrlHandler.current`: `TextRowItem` (text rows), `ToolBodyView`'s `ToolBody.Markdown` body and its `ToolBody.Links` summary, and `PlanCardView`'s plan body.
- [ ] **Step 4: Run** the two classes, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS; then `grep -rn "Color(0x\|Color\.White\|Color\.Black" src/main/kotlin/dev/daarkolabs/tandem/ui` → only `TandemColors.kt` (spark) and `TandemToolWindow.kt` (the scrim `Color.Black.copy(alpha …)`).
- [ ] **Step 5: Run** `./gradlew verifyPlugin --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (≈ 2 min). Expected: no problem naming `TandemBlockRenderer` against 2026.2.3 (0.41.0). If one is reported: delete the subclass, pass `markdownBlockRenderer = MarkdownBlockRenderer.create(styling, extensions)` (the bridge extension on the Companion), keep the test above, and write "fence copy button deferred to 2b (verifyPlugin: <the reported problem>)" into `/home/user/tandem/scratch/t14-notes.md` (absolute, like the init script) and into the T14 commit body (Step 6 adds it as a second `-m`), for T16's PR body (spec 4.4).
- [ ] **Step 6: Commit** `git add build.gradle.kts src/main/resources/META-INF/plugin.xml src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ui/TandemMarkdownTest.kt src/test/kotlin/dev/daarkolabs/tandem/ide/TandemPluginTest.kt && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(ui): GFM tables and strikethrough, fence copy button, warning banner and prompt clamp"`

---

### Task T15: reload-parity

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptReader.kt` (`Scan.accept`: the stamps; `readFrom`: `cwd`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/transcript/TranscriptReaderTest.kt`, `.../chat/ChatRowsBuilderTest.kt`

**Interfaces:**
- Consumes (T1): `CliMessage.User.timestampMillis`, `CliMessage.Assistant.timestampMillis`, both name sets decoded by `Ndjson`; (T2): `SessionEvent.Cli(message, atMillis)`, `SessionEvent.UserSent(uuid, text, atMillis)`, `ChatState.cwd`, `ChatEntry.User.sentAtMillis`, `ChatEntry.ToolResult.result / decision / atMillis`, `AssistantBlock.Thinking.durationMs`; (T4/T5): `ChatRowsBuilder`, `ToolRow`, `ToolCall`, `ToolKind`, `ThinkingRow`, `WorkingRow`, `TurnEndRow`; the reader's existing `TranscriptMeta.cwd` (the first line's `cwd`) and `readFrom`'s `state.copy(activeModel = meta.lastModel ?: …)` block.
- Produces (no new public API; spec 5.5):
  ```kotlin
  // Scan.accept: reduce(state, SessionEvent.Cli(user, atMillis = user.timestampMillis ?: 0L)); reduce(state, SessionEvent.Cli(assistant, atMillis = assistant.timestampMillis ?: 0L));
  //              reduce(state, SessionEvent.UserSent(user.uuid ?: "line-$lineCount", typed.text, atMillis = user.timestampMillis ?: 0L))
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
  // stream(name) = Fixtures.decode(name).foldIndexed(ChatState()) { i, s, m -> (if (m is CliMessage.SystemInit) reduce(s, SessionEvent.UserSent("u$i", "prompt $i")) else s).let { reduce(it, SessionEvent.Cli(m, atMillis = i * 100L)) } }.settled()   // one prompt per init, as the fake CLI expects
  // reload(name) = read(name)
  // sig(rows) = rows.mapNotNull { when (it) { is ToolRow -> listOf(it.label.text, it.state, it.stats, it.calls.map { c -> listOf(c.label.text, c.state, c.stats, (if (c.kind == ToolKind.AGENT) null else c.body::class), c.progress) }); is ThinkingRow -> "thinking"; else -> null } }
  //   children ignored (5.5); thinking by presence; the Agent body follows its children, which are the 5.5 difference: a forwarded TextRow child makes it None live, its absence on disk makes it Markdown
  // for each name in PROBE: assertEquals(sig(build(stream(name)).rows), sig(build(reload(name)).rows), name); build(reload(name)).rows.none { it is WorkingRow || (it is TurnEndRow && it.end == TurnEnd.COMPLETED) }
  // plus: think-default's ThinkingRow label is "Thought for 2s" on both sides; agent-sync's Agent call has progress "Done (2 tool uses · 21.8k tokens · 5.8s)" on both sides and children.isEmpty() on reload;
  //   agent-forwarded: the stream's Agent call has body == ToolBody.None and children.map { it::class } == [ToolRow::class, TextRow::class]; the reload's has body == ToolBody.Markdown("7", null) and children.isEmpty()
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
    lines 1–7 verbatim (through message_start); then for k in 1..N, with index k−1 and uuid "<uuid-1000+k>": content_block_start {type:"text",text:""}, content_block_delta text_delta = "Paragraph k of a long reply that must overflow the viewport while it streams." (k < N) or "pong" (k == N), content_block_stop;
    then plain-reply's line 10 (the complete assistant line) with content = the N text blocks; then lines 12–15 (message_delta, message_stop, post_turn_summary, result) with line 15's "result" = the paragraphs joined by "\n\n". Every other field (session_id "<session_id-2>", api_message_id "<id-8>") stays plain-reply's, so a resumed session's init matches its id.
    Docstring: the only unrecorded fixture; one text block per paragraph so each one is a new row while it streams (the ↓ N new count). No timestamps, no randomness.
  ToolWindowIdeTest additions:
    runChat / ideContext gain lineDelayMs: Int? = null → withEnv("TANDEM_FAKE_LINE_DELAY_MS", it.toString())
    fun longTranscript(seeded: String, turns: Int = 30): String — history-reply.jsonl's line 3 (user) and line 18 (assistant) repeated turns times with the user text "Prompt <n> of the seeded history", the assistant text "Reply <n>.", uuids "u<n>" / "a<n>", message.id "m<n>" on each assistant line (the reducer merges equal message ids), parentUuid chained (user n → "a<n−1>" or null, assistant n → "u<n>"), then {"type":"custom-title","customTitle":"Seeded","sessionId":"$seeded"}
    fun Driver.workingRow(description: String): UiComponent = ui.x("//div[@class='ComposeNode' and @contentdescription='$description']")
    fun Driver.node(description: String, textPredicate: (attr: String) -> String): UiComponent = ui.x("//div[@class='ComposeNode' and @contentdescription='$description' and ((${textPredicate("@text")}) or (${textPredicate("@visible_text")}))]")   // @text first, @visible_text as the fallback (10.6)
    const val FOOTER = "//div[@class='ComposeNode' and starts-with(@visible_text, 'Worked for')]"; const val TRANSCRIPT = "//div[@class='ComposeNode' and @contentdescription='Transcript']"
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
    val sent = runChat("autoscroll", fixtures = listOf("synthetic-long-reply"), configDir = configDir, project = project, lineDelayMs = 70) {   // 60 blocks × 3 stream lines × 70 ms ≈ 12.6 s
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

- [ ] **Step 5: Run** `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests '*working indicator*' --tests '*jump button*'`, then the whole `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → nine tests PASS. On a locator miss read `out/ide-tests/…/ui-hierarchy/ui.html` and fix the predicate once (open item 8: `@text` vs `@visible_text`). Then read the three new tests' `time` attributes in `build/test-results/integrationTest/TEST-dev.daarkolabs.tandem.ToolWindowIdeTest.xml` (question card, working indicator, autoscroll): if their mean is under 150 s add `the plan card approves and the picker follows` (`plan-approve`: `typePrompt("Plan a changelog")`, `waitForText("Approve this plan?")`, `iconButton("Approve and start coding").click(Point(10, 10))`, `waitFor { picker("Permission mode", "Accept edits").present() }`, assert the sent frame's `updatedPermissions[0]["type"] == "setMode"`), run it alone, and rerun the suite; otherwise leave the plan card pinned by 10.2/10.4 and say so in the PR body.
- [ ] **Step 6: Commit the tests** `git add scripts/make_long_reply.py scripts/test_make_long_reply.py src/test/resources/fixtures/synthetic-long-reply.ndjson src/test/resources/fake-claude.py src/integrationTest CLAUDE.md && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "test: IDE tests for the working indicator and autoscroll on a generated long reply"`
- [ ] **Step 7: Docs**, one edit each:
  - `docs/protocol.md`: 1.1 gains `--thinking-display summarized` (after `--include-partial-messages`); 4.2 the `initialize` body with `"forwardSubagentText":true`; 4.3 `requires_user_interaction`, `decision_reason_type`, `agent_id` on `can_use_tool`; 4.4 a `set_max_thinking_tokens` success carries no `response` (not sent by Tandem, spec 5.2); 5.2 `thinking_tokens`, `task_started`, `task_progress`, `task_updated`, `task_notification` as typed lines with their fields (5.1); 6.1 `thinking_duration_ms`, `thinking_display`, `agent_id`, `subagent_type`, `task_description`, `timestamp`; 6.3 `estimated_tokens` on thinking deltas, `usage` on `message_start` / `message_delta`; 7 `duration_ms`, `duration_api_ms`, `num_turns`, `usage.output_tokens`, `permission_denials`; 10 moves `user.tool_result_meta` to "typed, section 6.2"; 11.4 gains the 5.5 table (what a reload shows per row element) and the line "`cwd`: the first line's `cwd`, so relative file names match the live session".
  - `docs/superpowers/research/2026-10-18-protocol-probes.md` §2.4, the sentence ending `(agent-sync:47, agent-forwarded:37, which also lack tool_use_result)`: `tool_result_meta` **is** on both lines (`reason_type:"subcommandResults"`); only `tool_use_result` is missing there (spec 10.1, pinned by `agent-sync: task lines decode and subagent lines carry agent ids`).
  - `docs/design.md`: the architecture block's `ui/` line names the chat rows, cards and composer (`chat/` was added in T4); under Milestones, after item 1: `**Phase 2a, chat redesign.** The desktop app's chat feel: typed tool rows with grouping, thinking rows, the working indicator, the permission / question / plan cards, intent-based autoscroll, subagent nesting, reload parity; spec docs/superpowers/specs/2026-10-19-chat-redesign-design.md. **Status: implemented on feat/chat-redesign**, covered by fixture, pure-presentation (chat/), platform and IDE Starter tests; feel is checked by hand (spec 10.7).`
  - `CLAUDE.md` hard rule 6 gains: `synthetic-long-reply.ndjson is generated by scripts/make_long_reply.py and is the only unrecorded fixture.`
- [ ] **Step 8: Hard rule 8 grep** `grep -rn "Claude Code" src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/ide/TandemSettingsConfigurable.kt` → no output, then `sed -n '/^### 4.20/,/^None says/p' docs/superpowers/specs/2026-10-19-chat-redesign-design.md | grep -v '^None says' | grep -n "Claude Code"` → no output (the 4.20 table rows; `grep -v` drops the closing sentence, which names the rule) (the allowed "works with Claude Code" lines live in `plugin.xml`, `SessionManager.kt`, `Reducer.kt`, `ClaudeSession.kt`, spec 4.20). Any hit is renamed to `Claude`.
- [ ] **Step 9: Verify and commit the docs** `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` and `./gradlew verifyPlugin --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → both green; `git add docs/protocol.md docs/design.md docs/superpowers/research/2026-10-18-protocol-probes.md CLAUDE.md && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "docs: protocol fields, reload parity and the chat redesign milestone"`
- [ ] **Step 10: The owner's `runIde` pass** `./gradlew runIde --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` and hand over spec 10.7 items 1–11 (LaF switch, spark cycle, streaming feel and the `-Ptandem.spike=true` stall monitor, trackpad wheel-up, tool rows, the three cards, thinking rows, tables and the copy button, 350 px and 1200 px). Only a person judges these; the PR lists the items and the open items they decide (spec 13: 1, 2, 3, 4, 6, 9, 10, 20, 21, 23).
- [ ] **Step 11: Pull request** `git push -u origin feat/chat-redesign` then `gh pr create --base feat/sessions-and-pickers --title "feat: chat redesign (desktop-app feel)" --body-file scratch/pr-body.md`. The body: context (the four complaints and decisions 1–12 of the spec), approach (protocol → reducer → `chat/` → `ui/`, three slices), testing notes (`check` with Kover, the nine IDE tests and their wall-clock, the two departures from spec 10.6 listed under T16 Interfaces, `verifyPlugin`, the `runIde` checklist for the owner), follow-ups: record a `plan-enter` fixture (`EnterPlanMode` from a non-plan session) to decide whether its row can be hidden (spec 4.6.2), the fence copy button if T14 deferred it (read `/home/user/tandem/scratch/t14-notes.md` and `git log --grep "fence copy button deferred"`), the redactor follow-ups of probes §3.4. No attribution lines, no session link.

---

Slice-C review with the owner: open item 7 (the copy button survived `verifyPlugin` or was deferred), open item 19 (the parity test accepts the Agent row without children), open item 8 (which Driver attributes the new locators needed), and the `runIde` checklist results that decide open items 3, 4, 9, 20 and 23.

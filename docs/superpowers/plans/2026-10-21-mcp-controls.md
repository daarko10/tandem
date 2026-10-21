# MCP Controls and Slash Palette Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From the tool window, list every MCP server the session has with its state and error and act on it (disable, enable, reconnect, authenticate through the system browser), and give the composer a `/` palette whose `/mcp`, `/model`, `/permissions` and `/mode` open Tandem's own panel and menus, while every other command goes to the CLI and a locally answered one renders as a notice, not a reply; and a composer line starting with `!` runs in the user's login shell, shows its output live in the chat and reaches the CLI as the terminal's own shell escape, so Claude answers it (spec 12).

**Architecture:** M1 types every MCP wire shape in `protocol/` with a fixture test per shape. M2a gives `ClaudeSession` one awaited request path (`request()`, a `replies` map, `Ended` fan-out) and the typed requests; M2b gives the reducer the init merge, the command catalogue and the local-command rules, and the reader their on-disk twins; M2c gives `SessionManager`/`SessionHandle` the `McpControls` functions, flows and the sign-in poll, and the fake CLI its inline answers. M3 adds the pure models in `chat/` (`mcpPanel`, `slashPalette`, `pick`, `slashIntercept`, the notice link). M4 renders the header button, badge and sheet on an `McpPanelController`; M5 the palette popup, keys, interception and the notice link; M5r the connector recording tooling; M7a adds bash mode's protocol and session layers (`BashEscape` and the bash frames in `protocol/`; `ShellRunner`, the `Shell` entry and the quiet-result rule in `session/`; the fake's `user_message_uuids` rule) and M7b its chat, ui and ide layers (`ShellRow` and the turn boundaries in `chat/`, the row and the send path in `ui/`, the setting in `ide/`), after M5; M6 docs, verification and the PR.

**Tech Stack:** Kotlin 2.4.20, IntelliJ Platform 2026.1.5 (Jewel 0.37.0, build 261.27258.48), kotlinx.serialization.json tree API, the platform's kotlinx.coroutines and kotlinx.collections.immutable (never bundled, hard rule 7), JUnit 5 (+ vintage for the JUnit 4 classes), IDE Starter + UI Driver, the Python fake CLI and recording scripts.

**Spec:** `docs/superpowers/specs/2026-10-21-mcp-controls-design.md` (sections 3, 4, 9, 10 are the contract; 7 the edge cases; 11 the open items; 12 the bash mode of M7a and M7b, its own contract, edge cases, tests and open items). Companions: `docs/superpowers/research/2026-10-21-mcp-probes.md` (probes), `docs/superpowers/research/2026-10-21-bash-mode-probe.md` (bash probes), `docs/superpowers/specs/2026-10-21-chat-polish-design.md` (polish: 2.3, 2.7, 3.4), `docs/superpowers/specs/2026-10-19-chat-redesign-design.md` (base: 4.11, 4.15, 4.19, 4.20), `/tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/understand/jewel.md`.

**Dependencies:** M1 → M2a → M2b → M2c → M3 → M4 → M5 → M7a → M7b → M6, sequential in spec 10's order (its M2 is M2a–M2c here; M3 consumes only M2b's types and M4 consumes M2c's, but M3 still runs after M2c); M4 and M5 both edit `ComposerKeys.kt`, `ChatUiState.kt`, `PromptBar.kt` and `TandemToolWindow.kt`, and M5 routes `/mcp` to M4's panel; M7a depends on M2c (the session layer and the fake) and runs after M5, so its `Reducer.kt`, `ChatState.kt` and `ChatRowsBuilder.kt` edits follow M2b–M5's; M7b depends on M7a, M3 (`ChatRow`, `ComposerModel`) and M5 (it extends `PromptBar`'s send path ahead of `slashIntercept`) and edits `PromptBar.kt`, `TandemToolWindow.kt` and `ChatPanel.kt` after them. M7 is two tasks, each green with its own commit and reviewer, because a reviewer can reject the process handling and the quiet-result rule while approving the row (as M2 is three). M5r depends on nothing, runs in parallel from the start in its own worktree and lands before M6. Tiers (spec 10): M1–M5, M7a, M7b and their reviewers on Opus; M5r and M6's docs on Sonnet; a task that fails review twice, and the whole-branch review after M6, on Fable.

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

## Global Constraints

- CLAUDE.md hard rules, verbatim:
  1. **Never block the EDT.** No process I/O, file I/O, JSON parsing or waiting on the CLI from the UI thread. Use the platform coroutine scopes (`platform-coroutines-structured-concurrency` skill).
  2. **No Node, no Agent SDK at runtime.** We spawn the user's `claude` binary directly. Do not add a sidecar.
  3. **Never touch credentials.** Auth belongs to the CLI. Do not read `~/.claude/.credentials*`, keychains or API keys, and never offer a login flow.
  4. **Edits go through the IDE, not the disk.** Claude's built-in Edit/Write tools are disabled; our MCP edit tools apply changes with `WriteCommandAction` on the `Document`, so undo, unsaved buffers and local history work. Every edit shows a native diff before it lands, unless the user picked auto-accept.
  5. **Never fail silently.** If the CLI falls back to another model, rejects a model, retries, or errors, the user sees it in the chat. Compare the model in the `system/init` event (and fallback events) with what the user picked.
  6. **Every protocol change gets a fixture test.** Recorded transcripts live in `src/test/resources/fixtures/`. Unknown message types must parse to `Unknown(raw)`, never throw. `synthetic-long-reply.ndjson` is generated by `scripts/make_long_reply.py` and is the only unrecorded fixture.
  7. **Do not bundle kotlinx.coroutines or the Kotlin stdlib.** The platform provides both.
  8. **Do not call the product "Claude Code"**, in the plugin name, ID or UI. Use "Tandem" and say it works with Claude Code.
- CLAUDE.md Conventions, verbatim: "Validate everything that comes from the CLI; it is an external process that changes weekly." and "Never log prompt contents, file contents or secrets. Log message types and ids." Spec 4.2's logging rule: MCP code logs the subtype, request id, server name and status only; never `authUrl`, never `config`, never an error text that echoes either. `McpConfig` never decodes `headers` or `env`. Spec 12.4's: shell-escape code logs the end kind, the duration and the character count only; never the command, its output or the frames.
- Bash mode (spec 12): the command runs in the user's login shell (`SHELL -lc`, else `/bin/zsh -lc` on macOS and `/bin/sh -lc` elsewhere; `%ComSpec% /c` on Windows, unverified) with `ShellEnvironment.get()` as its environment, in `SessionManager.workingDirectory`, on `Dispatchers.IO` in `handle.scope`, never the EDT; the output polled, never a blocking read (`ide/ClaudeBinary.outputOf`'s pattern); stdin closed; stderr merged into stdout in order (the 2.1.306 TUI's shape, bash probes 2.1); no permission card (the user typed it). In respond mode the output frame goes out only once frame 1's zero-turn result is in (`bash-mode-respond:6`, the recorded order, spec 12.4), never back to back with it, and a flushed escape waits for the prompt before it. `ShellRunnerTest` and the process tests of `SessionManagerTest` run a real `/bin/sh` and skip on Windows with `assumeTrue`.
- Layers: `protocol/` imports nothing from IntelliJ; `session/` only the JDK, kotlinx.coroutines, kotlinx.serialization.json, kotlinx.collections.immutable and `com.intellij.openapi.diagnostic`; `chat/` the JDK, kotlinx.collections.immutable, kotlinx.serialization.json and `session/`/`protocol/` types: no Compose, no Jewel, no IntelliJ. `koverVerify` holds each of the three at ≥ 90 % lines.
- Jewel 0.37.0 as bundled in 2026.1.5; every API named in spec 3 and 4.4 (`IconButton`, `OutlinedButton`, `PopupMenu`, `VerticallyScrollableContainer`, `AllIconsKeys.Webreferences.Server`, `Actions.Refresh`, `General.Close`) is in `jewel.md`; check any other with `javap` before use. Theme tokens only (base 4.2); the scrim is the drawer's `Color.Black.copy(alpha = if (JewelTheme.isDark) 0.4f else 0.2f)`.
- Branch: `feat/mcp-controls`, created from `feat/chat-redesign` once the polish round (P1–P7 of the polish spec) is on it. This plan relies on polish's names: `composerKey(key, type, shift, alt, ctrlOrMeta, busy, cardOpen, draftEmpty, hasAttachments, clipboardHasImage)`, `enum class ComposerAction { Send, Stop, CardEscape, PasteImage, RemoveLastImage }`, `PromptBox(ui, busy, cardOpen, …)` with `AddButton` and its `PopupMenu`, `ui/FooterControls.kt` (`ModeControl`, `ModelControl`, `AboveAnchor(alignEnd)`), `ComposerSender`. If one is missing when a task starts, stop and say so; the only allowed fallback is spec 11 item 6 (`menuRequest` focuses the control). Conventional Commits; `git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>"`; no trailers; stage by path. No personal data in committed files (M5r's redactor renames every connector; the owner's connector name goes only in an environment variable). The owner's connector names never appear in this plan, a test, a script or a doc: before M1 the controller writes them, one per line, with the commit name and email, to the gitignored `/home/user/tandem/scratch/personal-names.txt` (from `tandem-04`), and M1 and M6 check against that file.
- Gradle: every command carries `--init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (the absolute path, so it works from a git worktree). One class: `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.<pkg>.<Class>'`; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` adds `koverVerify`; IDE tests, only in M2c (the existing tests, after the fake's rewrite), M4, M5, M7b and M6: `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` (1.5–3 min each). M5r runs `cd /home/user/tandem/scripts && python3 -m unittest` and no Gradle. Every task ends with `check` green and one commit.
- Fixtures: `name:line` is the 1-based line of `src/test/resources/fixtures/<name>.ndjson`; `transcripts/<name>.jsonl` is its on-disk twin. `L(name, n)` in a test is `Ndjson.decode(Fixtures.lines(name)[n - 1])` (`FixtureDecodeTest.at`, `chat/Replay.fixtureLine`). The redactor makes every path `<path>`; ids are placeholders (`<request_id-23>`, `<id-36>`). `Fixtures.decode(name)` is the whole fixture; `ReducerTest.replay(fixture, initial)` folds it.
- Test names are the spec's (9.1–9.4), verbatim under the Kotlin name rule: Kotlin/JVM rejects `.` `;` `[` `]` `/` `<` `>` `:` `\` in backtick names, so the Kotlin name replaces `: ` with ` - `, `.` with ` dot `, `/<x>` with `<x>`, drops `<`/`>` and `"`, and a `// spec: <verbatim name>` comment sits above the test. The names below are already rewritten. New classes use JUnit 5; additions to the JUnit 4 classes (`FixtureDecodeTest`, `HostMessagesTest`, `RobustnessTest`, `ReducerTest`, `ClaudeSessionTest`) keep JUnit 4.
- Exact values (spec 3, 4): timeouts 15 s (`mcp_status`, `mcp_toggle`) and 30 s (`mcp_reconnect`, `mcp_authenticate`); sign-in poll every 2 s for 3 minutes; pending re-poll 2 s later, at most 5 times; an outcome caption stays 5 s; sheet height `min(60 % of the chat, content)`; palette at most 8 rows; `TANDEM_MCP_SERVER = "tandem"`; `MCP_TERMINAL_HINT = " Use `/mcp` in the terminal for details."`; the four host frames of probes 3.2 (`{"subtype":"mcp_status"}`, `{"subtype":"mcp_toggle","serverName":"echo","enabled":false}`, `{"subtype":"mcp_reconnect","serverName":"broken"}`, `{"subtype":"mcp_authenticate","serverName":"authy"}`); bash mode (spec 12.4): `MAX_OUTPUT_CHARS = 100_000`, `TIMEOUT_MILLIS = 600_000`, `BATCH_MILLIS = 50`, polled reads of at most 8 KB (a 15 ms `delay` while the pipe is empty), the respond frame sent once frame 1's quiet result is in or after `quietWaitMillis = 5_000`, `ShellRunner`'s bounded `waitFor(2, SECONDS)` after a kill, the row's cut at 400 lines / 50,000 characters, the three frames of bash probes 1 and 3 (`client_composed: true`; `shouldQuery: false` on `bashInput` and `bashContext`, absent on the respond `bashOutput`; `bashContext` as one host frame unrecorded; `<bash-stderr>` always empty, Tandem's Stopped/Killed note the last line of `<bash-stdout>`), the merged line `<bash-input>CMD</bash-input>\n<bash-stdout>OUT</bash-stdout><bash-stderr>ERR</bash-stderr>`; every user-facing string is spec 3.6's, verbatim; the hard-rule-8 check is `grep -rn "Claude Code" src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui` → no hit (CLI catalogue text is data, never a literal).
- Deviations from the spec (not contract breaks): `McpPanelController` takes `dispatcher: CoroutineDispatcher = Dispatchers.Default` and `pendingRepollMillis: Long = 2_000` and its test runs under `runBlocking` with `pendingRepollMillis = 20` (spec 9.4 names `runTest`: the test classpath has only the platform's coroutines, and `kotlinx-coroutines-test` beside the platform's fork is a classpath risk this plan avoids). `McpRowModel` gains `key: String` (Review Focus 1) and `rawSource: String?` (the `Other` group's pill text, spec 3.2); `McpPanelController` exposes `refreshing` and `rowErrors` flows (the title's ` · Refreshing…` and a row's error with `Retry`, which spec 4.4 leaves inside the row model); `NoticeRow` gains `kind: NoticeKind` beside spec 4.3's `command` and `link` (the accessibility name of 3.7 needs to know a notice is a local command's). `SessionManager`'s internal constructor gains `mcpAuthEvery: Duration = 2.seconds, mcpAuthTimeout: Duration = 3.minutes`; the public one keeps its parameters. `ChatRowsBuilder` removes `MCP_TERMINAL_HINT` with `removeSuffix` (spec 3.5's "exact match": the recorded text ends with it). `McpAuthOutcome` gains `NoLink` (spec 3.3 step 2's link-less success that still needs auth: as a `Failed(Rejected(…))` it would read like a poll that failed, whose caption differs, 3.3 step 3). `chat/SlashPalette.kt` gains `paletteFor(…)`, spec 4.4's `derivedStateOf` rule as a pure function, so `SlashPaletteTest` pins the stored-selection guard, and `committedDraft(…)`, spec 7's Palette and IME rule as a pure function, pinned by `a composition in progress does not move the selection`. `ui/McpPanelController.kt` gains the pure `authCaption(wait, nowMillis)` and the controller's `titleNote` flow, so the sign-in captions and the `Removed` title note of spec 3.3 are tested without Compose. The manager posts no notice for `Removed` and for `Changed` without an error (spec 3.6 has no string for them; spec 3.3 step 5 names every outcome but `Cancel` and the session's end), while `Changed` with an error posts `Sign-in to <name> failed: <error>`. Spec 10's M2 is three tasks here, M2a (request path), M2b (reducer, reader) and M2c (manager, handle, fake), each green with one commit. `SlashPaletteView` takes `maxHeightPx` beside the width (spec 3.4's min(8 rows, the space above the box)). The pull request targets `feat/chat-redesign`: spec 10 says the polish branch's base, but the polish round lands on `feat/chat-redesign` itself, so that is this branch's stacked base. M7a/M7b (spec 12): `ShellEnd.NotStarted` carries the shell's path beside the reason, so the row's `Could not start <shell>: <reason>` needs no second lookup; `SessionManager` formats a stopped command's elapsed time itself (`session/` may not import `chat/Text.formatDuration`), with the same `3.0s` / `1m 12s` text (`%.1fs` under 10 s); `TranscriptReader` feeds a reloaded shell escape through `SessionEvent.ShellLoaded` (spec 12.4 names it) rather than through `ShellStarted` + `ShellEnded`, since a reloaded entry has no end.

## Review Focus

Five per feature, the most likely failure modes; the other edge cases sit in the owning tasks' test lists.

MCP and palette:

1. Two `mcp_status` entries with the same name (a user config that repeats a `dynamic` name, unrecorded) would crash a `LazyColumn` keyed by name; rows get unique keys → M3 `two entries with the same name keep distinct keys`, and M4 keys rows by `row.key`.
2. A connector named exactly `claude.ai ` (the strip leaves nothing) must still show a name → M3 `a connector named only by the prefix keeps its full name`.
3. A typed command with arguments (`/mcp reconnect x`) must name its notice from the first token (`Reply to /mcp`), never from the whole line → M2b `a command with arguments names the notice from its first token`.
4. A CLI that lists `mcp` or `model` in `terminal_slash_commands` (unrecorded) must not hide Tandem's own rows → M3 `a terminal name Tandem intercepts still lists`.
5. Shift+Enter with the palette open must stay a newline, and Enter with both a card and the palette open must pick (palette before card) → M5 `Shift+Enter with the palette open is still a newline` and `Enter with a card and the palette open picks`.
Bash mode:

6. The output frame written back to back with frame 1, or a quiet frame written right after a flushed prompt: the CLI batches the sends queued before it starts processing into one line (`bash-mode:3-6`, `transcripts/bash-mode.jsonl:5`), and whether a merged quiet + querying batch calls the model is unrecorded, so Claude may never answer; the fake CLI cannot catch it (it reads a frame only where it waits) → M7a `ClaudeSessionTest` `the output frame waits for the quiet result` and `a shell escape after a prompt waits for the prompt's result`.
7. A quiet uuid left in `quietSends` (the process ended or was stopped before its result, or a CLI that omits `user_message_uuids`) keeps every later process of that session non-idle, so the live-session cap can never stop it, and a context-only `!` would draw a `Worked for` footer → M7a `a process that ends before the quiet result leaves no quiet send`, `a zero-turn result without uuids ends the quiet send without a footer` and `a session whose process ended during a quiet send is idle on its next process`.
8. Never a row stuck running: a command that prints nothing for a while (`sleep 60`) must stop at once on Stop, the cap or `close()`, a command stopped before its job is dispatched must still end `Stopped`, and a backgrounded child that keeps stdout open (`! npm run dev &`) must not hold the row: the runner polls `available()` and never blocks in a read, and the job starts `ATOMIC` → M7a `the tree is killed on stop`, `closing the session kills a running command`, `a command stopped at once still ends Stopped` and `a background child that keeps the output open does not hold the row`.
9. A responding shell turn must count as an open turn at every turn-boundary check, or Claude's running tool calls read `INTERRUPTED` (`ChatRowsBuilder.build`'s `turnOpen`) → M7b `a responding shell turn shows its running tool call as running`.
10. A command whose output itself contains `</bash-stdout>` or a `<bash-stderr>…</bash-stderr>` pair (a `cat` of a transcript) must reload whole, never cut at the inner tag nor shown again as stderr → M7a `an output that contains the closing tag is cut at the last one`.

---

### Task M1: protocol-mcp

**Depends on:** none.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/protocol/Mcp.kt`
- Modify: `.../protocol/CliMessage.kt`, `.../protocol/Ndjson.kt`, `.../protocol/HostMessages.kt`, `src/test/resources/fixtures/mcp-status.ndjson:40` (the two hand placeholders), `docs/superpowers/research/2026-10-21-mcp-probes.md:13` ("How to read" item 4), its section 1 and its connector names (`:3`, `:119`, `:256`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt`, `.../protocol/HostMessagesTest.kt`, `.../protocol/RobustnessTest.kt`

**Interfaces:**
- Produces (spec 4.1 verbatim, one line each; every field optional and validated, a mistyped entry skipped, never thrown):
  ```kotlin
  enum class McpStatus(val wire: String) { CONNECTED("connected"), PENDING("pending"), NEEDS_AUTH("needs-auth"), FAILED("failed"), DISABLED("disabled"), OTHER(""); companion object { fun fromWire(s: String?): McpStatus } }
  data class McpServerSummary(val name: String, val status: McpStatus, val statusText: String, val source: String?)   // statusText: the wire text, "" when missing or not a string
  data class McpServerInfo(val name: String, val status: McpStatus, val statusText: String, val error: String? = null, val source: String? = null, val scope: String? = null, val config: McpConfig? = null, val serverInfo: McpPeer? = null, val tools: List<McpTool>? = null)
  data class McpConfig(val type: String?, val command: String?, val args: List<String>, val url: String?)   // never headers or env; a non-object config → null
  data class McpPeer(val name: String?, val version: String?); data class McpTool(val name: String); data class McpStatusResponse(val servers: List<McpServerInfo>)
  data class McpAuthStart(val authUrl: String?, val requiresUserAction: Boolean? = null, val callbackExpected: Boolean? = null, val redirectScheme: String? = null, val state: String? = null, val callbackPort: Int? = null)
  data class McpSetServersResponse(val added: List<String>, val removed: List<String>, val errors: Map<String, JsonElement>)
  data class McpServerRef(val name: String, val source: String?); data class ToolUseMeta(val id: String, val displayName: String?, val serverDisplayName: String?, val toolName: String?)
  data class LocalCommand(val command: String?, val args: String?, val outcomeKind: String?, val stdout: String?)   // stdout: local_command_source with one <local-command-stdout>…</…> or <local-command-stderr>…</…> wrapper removed
  data class SlashCommand(val name: String, val description: String = "", val argumentHint: String = "", val builtin: Boolean = false, val aliases: List<String> = emptyList()); data class InitializeResponse(val commands: List<SlashCommand>)
  object ControlPayloads { fun mcpStatus(payload: JsonObject?): McpStatusResponse?; fun mcpAuthenticate(payload: JsonObject?): McpAuthStart?; fun mcpSetServers(payload: JsonObject?): McpSetServersResponse?; fun initialize(payload: JsonObject?): InitializeResponse?; fun browsableUrl(url: String?): String? }
  // mcpStatus: non-null only with an `mcpServers` JsonArray (an entry without a string `name` is skipped); initialize: only with a `commands` JsonArray (same rule);
  // mcpAuthenticate: null when payload is null or has none of the six keys; mcpSetServers: null without `added` and `removed` arrays;
  // browsableUrl: runCatching { URI(url) } → url when scheme is http/https ignoring case and host != null, else null
  CliMessage.SystemInit(+ mcpServers: List<McpServerSummary> = emptyList(), slashCommands: List<String> = emptyList(), terminalSlashCommands: List<String> = emptyList())
  CliMessage.Assistant(+ localCommand: LocalCommand? = null, toolUseMeta: List<ToolUseMeta> = emptyList()); CliMessage.Result(+ localCommand: String? = null)
  InboundRequest.CanUseTool(+ mcpServer: McpServerRef? = null, serverPolicyAskOnly: Boolean = false)
  object HostMessages { fun mcpStatus(requestId: String): JsonObject; fun mcpToggle(requestId: String, serverName: String, enabled: Boolean): JsonObject; fun mcpReconnect(requestId: String, serverName: String): JsonObject; fun mcpAuthenticate(requestId: String, serverName: String): JsonObject }   // members of the existing object
  ```
- Wire keys (recorded): init `mcp_servers[]{name,status,source}`, `slash_commands[]`, `terminal_slash_commands[]` (`mcp-status:6`, `mcp-slash:4`); assistant top-level `local_command_run{command,args}`, `local_command_outcome{kind}`, `local_command_source` (`mcp-slash:5`, `:17`), `localCommand` non-null iff one of the three keys is present; assistant top-level `tool_use_meta[]{id,display_name,server_display_name,tool_name}` (`mcp-tool-call:34`); result `local_command` (`mcp-slash:6`); `can_use_tool` `mcp_server{name,source}`, `server_policy_ask_only` (`mcp-tool-call:35`); `mcpServers[]{name,status,error,source,scope,config{type,command,args,url},serverInfo{name,version},tools[]{name}}` (`mcp-status:18`); `authUrl,requiresUserAction,callbackExpected,redirectScheme,state,callbackPort` (`:40`); `added,removed,errors` (`:42`); `commands[]{name,description,argumentHint,builtin,aliases}` (`mcp-slash:1`). The senders go through the private `request(requestId, subtype) { … }` builder, `serverName` then `enabled`, so the frames equal probes 3.2 byte for byte.

- [ ] **Step 1: Rewrite the placeholders.** In `mcp-status.ndjson:40` replace `<code_challenge>` with `REDACTED_CODE_CHALLENGE` and both `<state>` with `REDACTED_STATE` (`java.net.URI` rejects `<` in a query); `grep -n 'code_challenge' src/test/resources/fixtures/transcripts/mcp-status.jsonl` and do the same there if it hits. In the probe report, item 4 of "How to read" and section 1 say the two are now `REDACTED_CODE_CHALLENGE` / `REDACTED_STATE`; and the owner's connector names (the two `claude.ai <Name>` examples at `:3`, the second example at `:119`, the probe target at `:256`) become `claude.ai Connector-1` / `claude.ai Connector-2` in order of first appearance (`claude.ai Slack`, the docs' own example, stays); then `git grep -nFf /home/user/tandem/scratch/personal-names.txt -- docs/superpowers/research/2026-10-21-mcp-probes.md` → no hit.
- [ ] **Step 2: Write the failing tests.** `FixtureDecodeTest` (JUnit 4), one per 9.1 row, with `// spec:` lines:

```kotlin
`mcp-status - init carries the thin server list and redacted command lists`   // L("mcp-status",6).mcpServers == [McpServerSummary("echo",CONNECTED,"connected","dynamic"), ("broken",FAILED,"failed","dynamic"), ("authy",NEEDS_AUTH,"needs-auth","dynamic")]; slashCommands and terminalSlashCommands empty
`mcp-status - a disabled server leaves init dot tools and comes back`   // :6 tools contains "mcp__echo__echo"; :23 echo DISABLED and the tool absent; :48 echo CONNECTED and the tool back
`mcp-status - mcp_status decodes every entry shape`   // ControlPayloads.mcpStatus(L(18).payload)!!: echo serverInfo!!.version == "0.1.0", config!!.type == "stdio", config.command == "/usr/bin/python3", config.args.size == 2, tools == listOf(McpTool("echo")); broken error == "Connection closed", tools == null; authy config!!.type == "http", config.url == "http://127.0.0.1:33041/mcp", error == null
`mcp-status - pending before the first turn`   // :2 two PENDING, authy NEEDS_AUTH
`mcp-status - a toggle success has no payload and a disabled entry has no tools`   // :19 isSuccess && payload == null; :20 echo DISABLED, serverInfo == null, tools == null
`mcp-status - reconnect and toggle errors carry the CLI's text`   // :36 error == "Connection closed"; :37 "Server status: needs-auth"; :38 "Server not found: nosuch"
`mcp-status - mcp_authenticate answers with an auth url and a callback port`   // :40 authUrl!!.startsWith("http://127.0.0.1:33041/authorize?"), browsableUrl(authUrl) == authUrl, state == "REDACTED_STATE", requiresUserAction == true, callbackExpected == true, redirectScheme == "localhost", callbackPort == 53713
`mcp-status - a sign-in link that is not http is refused`   // browsableUrl of "file:///etc/passwd", "vscode://x", "authorize?x=1", "http:///x" (no host) and the :40 url with a raw "<" in its query → null; "HTTPS://example.test/a" kept
`mcp-status - mcp_set_servers reports added and removed`   // :42 added == ["echo2"], removed empty; :44 removed == ["echo2"], errors empty
`mcp-status - the redacted initialize answer has no commands`   // ControlPayloads.initialize(L(1).payload)!!.commands.isEmpty()
`mcp-slash - the initialize answer lists 73 commands with hints and aliases`   // size 73; 55 builtin; "mcp" hint "[reconnect|enable|disable [<server>|all]]"; "model" hint "<model>"; "code-review".aliases == ["review"]; "hello" description ends " (project)", builtin == false, aliases empty
`mcp-slash - init lists the command names and the terminal ones`   // :4 slashCommands.size == 73 in :1's order; terminalSlashCommands == ["doctor","color","focus","reload-plugins"]; neither "permissions" nor "mode" in slashCommands
`mcp-slash - a local command is a synthetic assistant line`   // :5 model == "<synthetic>", localCommand == LocalCommand("mcp", "", null, "3 MCP server(s): 1 connected, 2 not connected, 0 disabled. Use `/mcp` in the terminal for details."), the Text block's text == stdout; :6 Result.localCommand == "mcp", numTurns == 0, durationApiMs == 0L
`mcp-slash - an unavailable command carries an outcome kind`   // :17 localCommand!!.outcomeKind == "unavailable_headless", command == null, stdout == "/help isn't available in this environment."; :18 Result.localCommand == null
`mcp-tool-call - the permission request names the server`   // :35 mcpServer == McpServerRef("echo","dynamic"), serverPolicyAskOnly, displayName == "Echo"
`mcp-tool-call - the assistant line carries tool_use_meta`   // :34 toolUseMeta.single() == ToolUseMeta(<the tool_use block's id>, "Echo", "echo", "echo")
`mcp-tool-call - the result keeps structuredContent reachable`   // :39 toolUseResult["structuredContent"]["result"] == "hello tandem"; :62 the ToolResult block isError and meta[0].nonExecutionKind == "permission-rule"
// HostMessagesTest `the four mcp requests match the probe frames`: wire(mcpStatus("m1")) == {"type":"control_request","request_id":"m1","request":{"subtype":"mcp_status"}} and m2–m4 as the Exact values line
// RobustnessTest (the sweep already lists every fixture and twin, so the three new files are in it): `mutated mcp shapes degrade, never throw`: :18 with "status":7 → OTHER with statusText ""; without "name" → that entry skipped, the other two kept; a :1 command without "description"/"argumentHint" → ""; `a mistyped config or tools entry keeps the server` (a future CLI's shape: the server is kept, never dropped, never a throw): "config":"x" → config == null, "tools":{} → tools == null, name kept; `a stderr wrapper unwraps like stdout` (**unrecorded**): :17 with <local-command-stderr>oops</local-command-stderr> → stdout == "oops"
```

- [ ] **Step 3: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.protocol.*'` → compilation errors (`McpStatus`, `ControlPayloads`, `localCommand`, …).
- [ ] **Step 4: Implement** the Interfaces block: `Mcp.kt` with the types and `ControlPayloads` (string lists skip non-string elements; `args` absent → empty); `Ndjson.decodeSystem`, `decodeAssistant`, `decodeResult`, `decodeInboundRequest` read the wire keys above; the four senders. No `Reducer` change: no new `CliMessage` type.
- [ ] **Step 5: Run** the Step 3 command → PASS, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS.
- [ ] **Step 6: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/protocol src/test/kotlin/dev/daarkolabs/tandem/protocol src/test/resources/fixtures docs/superpowers/research/2026-10-21-mcp-probes.md && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(protocol): decode MCP status, auth, commands and local-command shapes"`

---

### Task M2a: session-request-path

**Depends on:** M1.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/session/McpControls.kt` (this task: `McpResult`, `McpError`, `TANDEM_MCP_SERVER`; M2c adds the `McpControls` interface and the sign-in types)
- Modify: `.../session/ClaudeSession.kt`, `.../session/Reducer.kt` (`SessionEvent.McpServersListed` and its rule), `.../session/ChatState.kt` (`McpState`, `ChatState.mcp`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/ClaudeSessionTest.kt`, `.../session/ReducerTest.kt` (the `mcp_status` answer test)

**Interfaces:**
- Consumes: M1's types, `ControlPayloads`, the four senders.
- Produces (spec 4.2):
  ```kotlin
  internal sealed interface Reply { data class Answered(val response: CliMessage.ControlResponse) : Reply; data object TimedOut : Reply; data object Ended : Reply }
  class ClaudeSession {   // members of the existing class (spec 4.2): they reach its private replies, send and newRequestId
      internal suspend fun request(timeoutMillis: Long, build: (requestId: String) -> JsonObject): Reply
      suspend fun mcpStatus(): McpResult<List<McpServerInfo>>; suspend fun mcpToggle(name: String, enabled: Boolean): McpResult<Unit>; suspend fun mcpReconnect(name: String): McpResult<Unit>; suspend fun mcpAuthenticate(name: String): McpResult<McpAuthStart> }
  sealed interface McpResult<out T> { data class Ok<T>(val value: T) : McpResult<T>; data class Failed(val error: McpError) : McpResult<Nothing> }
  sealed interface McpError { data class Rejected(val message: String) : McpError; data class TimedOut(val seconds: Int) : McpError; data object NotRunning : McpError; data object Ended : McpError }
  const val TANDEM_MCP_SERVER: String = "tandem"
  data class McpState(val servers: List<McpServerInfo> = emptyList(), val detailed: Boolean = false, val initCount: Int = 0)
  ChatState(+ mcp: McpState = McpState())
  SessionEvent.McpServersListed(val servers: List<McpServerInfo>)
  ```
- `request()` (the one algorithm the signature does not determine; `replies: ConcurrentHashMap<String, CompletableDeferred<Reply>>`, `@Volatile ended`):
  ```kotlin
  val id = newRequestId(); val waiter = CompletableDeferred<Reply>(); replies[id] = waiter
  try {
      if (ended || !job.isActive) return Reply.Ended            // nothing written
      send(build(id))
      withTimeoutOrNull(timeoutMillis) { waiter.await() }?.let { return it }
      replies.remove(id)
      return if (waiter.complete(Reply.TimedOut)) Reply.TimedOut else waiter.getCompleted()   // an answer that raced the timeout is the caller's
  } finally { replies.remove(id); waiter.cancel() }               // a cancelled caller's late answer then fails complete() and goes to the reducer
  ```
  `readLoop`, on a `ControlResponse`: `val w = replies.remove(requestId); if (w == null || !w.complete(Reply.Answered(message))) dispatch(...)`, else no dispatch (`set_permission_mode` and `initialize` are never sent through `request()`, so they keep their reducer path). `start()` wraps `run()` in `try { run() } finally { ended = true; replies.values.forEach { it.complete(Reply.Ended) }; replies.clear() }`. Elicitation: the existing `Unsupported request` answer plus `notice(WARNING, "An MCP server asked for input Tandem cannot show yet.")`. The typed methods decode under `withContext(Dispatchers.Default)`; `mcpStatus` dispatches `McpServersListed` on success and answers `Failed(Rejected("the CLI's answer listed no servers"))` for a success without a decodable array; `mcpAuthenticate` answers `Ok(McpAuthStart(authUrl = null))` for a null or shapeless payload and `Failed(Rejected("the sign-in link is not an http(s) URL"))` when `browsableUrl(authUrl) == null` with a non-null url; an error answer is `Rejected(error ?: "unknown error")`; `TimedOut(15)`/`TimedOut(30)`; `Ended`.
- Reducer rule (this task's only one, spec 4.2): `McpServersListed` → `servers` replaced, `detailed = true`.

- [ ] **Step 1: Write the failing tests** (spec 9.2, names verbatim; `ClaudeSessionTest` and `ReducerTest` JUnit 4; `ClaudeSessionTest` with `ScriptedProcess`; one WARNING/ERROR notice is `entries.filterIsInstance<ChatEntry.Notice>().count { it.level == … }`):

```kotlin
// ClaudeSessionTest (ScriptedProcess; the session's own request path)
`a request completes with the response that carries its id and the reducer never sees it`; `an unregistered error response still reaches the chat`   // ERROR notice "The CLI rejected a request: …"
`a request times out and a late answer goes to the reducer`; `an answer that races the timeout is returned, not lost`; `a cancelled request's error answer still reaches the chat`
`a process exit fails every waiting request with Ended`; `close while a request waits answers Ended`; `a failed launch answers Ended`; `a request after the end answers Ended at once`
`an mcp_status success without mcpServers is Rejected`   // "the CLI's answer listed no servers"
`an mcp_authenticate bare success is Ok without a url`; `an elicitation request keeps the Unsupported request answer and adds a warning notice`   // the written frame is today's error; one WARNING notice
// ReducerTest
`an mcp_status answer replaces the servers and marks them detailed`   // reduce(McpServersListed(ControlPayloads.mcpStatus(L("mcp-status",18).payload)!!.servers)) → three servers, detailed
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.ClaudeSessionTest' --tests 'dev.daarkolabs.tandem.session.ReducerTest'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the rules above.
- [ ] **Step 4: Run** the Step 2 command → PASS, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`session/` stays ≥ 90 %).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session/McpControls.kt src/main/kotlin/dev/daarkolabs/tandem/session/ClaudeSession.kt src/main/kotlin/dev/daarkolabs/tandem/session/Reducer.kt src/main/kotlin/dev/daarkolabs/tandem/session/ChatState.kt src/test/kotlin/dev/daarkolabs/tandem/session/ClaudeSessionTest.kt src/test/kotlin/dev/daarkolabs/tandem/session/ReducerTest.kt && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(session): one awaited request path with Ended fan-out and the typed MCP requests"`

---

### Task M2b: session-reducer-mcp

**Depends on:** M2a.

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/Reducer.kt`, `.../session/ChatState.kt`, `.../session/transcript/TranscriptReader.kt`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/ReducerTest.kt`, `.../session/transcript/TranscriptReaderTest.kt`

**Interfaces:**
- Consumes: M1's types, `ControlPayloads`; M2a's `McpState`, `SessionEvent.McpServersListed`.
- Produces (spec 4.2):
  ```kotlin
  ChatState(+ commands: List<SlashCommand> = emptyList(), slashCommandNames: List<String> = emptyList(), terminalCommands: Set<String> = emptySet())
  NoticeKind(+ LOCAL_COMMAND); ChatEntry.Notice(+ command: String? = null, runCommand: String? = null); TurnProgress(+ localCommand: Boolean = false)
  SessionEvent.LocalCommandOutput(val text: String, val runCommand: String?)
  ```
- Reducer rules, spec 4.2 verbatim: `SystemInit` → `initCount + 1`, the thin-list merge (a name whose status changed drops `error`, `tools`, `serverInfo`; a new name becomes an entry without config; a missing name is dropped unless `source == "claudeai"`), `slashCommandNames`, `terminalCommands`; `ControlResponse` success with `ControlPayloads.initialize(payload) != null` → `commands` replaced (keep `withModels`); `Assistant` with `localCommand != null`, and `LocalCommandOutput`, → `Notice(INFO, text = stdout ?: text blocks joined, LOCAL_COMMAND, command = first token of the latest User entry's text without "/" when it starts with "/", else runCommand, runCommand = localCommand.command)` and `turn.localCommand = true` when a turn is open; `Result` with `localCommand != null` or `turn?.localCommand == true` → `finishStreaming()`, `busy = false`, `turn = null`, `turnInterrupted = false`, `pendingPermissions` cleared, `sessionId` taken, no `TurnEnded`, `turnsEnded`/`lastTurnEnd` unchanged.
- `TranscriptReader.accept`: `"system"` with `subtype == "local_command"`: `content` wrapped in `<local-command-stdout>`/`<local-command-stderr>`, or `commandRun`/`commandOutcome` present → `reduce(state, LocalCommandOutput(unwrapped, commandRun?.command))`; bare `content` with `submitUuid` → `UserSent(uuid ?: "line-N", content, atMillis)`. Only `system`/`local_command` lines become notices: the existing test `a slash command loads as the command and its local output is skipped` (`TranscriptReaderTest.kt:298-326`) stays as it is, since its outputs are `type:"user"` `<local-command-stdout>` lines (an older shape) that stay skipped, as does the `<local-command-stderr>` user line of `a shell escape loads as its command, and output and notifications are skipped` (`:527`).

- [ ] **Step 1: Write the failing tests** (spec 9.2, names verbatim; `ReducerTest` JUnit 4 with `replay`; one WARNING/INFO/ERROR notice is `entries.filterIsInstance<ChatEntry.Notice>().count { it.level == … }`):

```kotlin
// ReducerTest
`an init merges the thin list and counts inits`; `a status change in init drops the stale error, tools and version`   // mcp-status :18, :19-20, :23 → echo DISABLED, tools == null, serverInfo == null; broken keeps "Connection closed" (its status is unchanged)
`the initialize answer fills the commands`   // mcp-slash:1 → commands.size == 73
`an init without a connector keeps it until the next mcp_status`   // synthetic claudeai entry stays; a dynamic name missing from init (:6 → :23 without broken) is dropped
`a local command is a notice and its result ends the turn without a footer`   // UserSent("/mcp") then :2-6: one User, one Notice(LOCAL_COMMAND, command == "mcp", runCommand == "mcp"), no TurnEnded, busy == false, turn == null, turnsEnded unchanged
`an unavailable command is an info notice named from the prompt`   // UserSent("/help") then :14-18: INFO, command == "help", runCommand == null
`a local command after a prompt that is not a command takes runCommand`   // UserSent("Reply with the single word: pong") then :2-6: command == "mcp", text == LocalCommand.stdout
`a project command is an ordinary turn`   // :20-34: an Assistant entry and a TurnEnded
`a command with arguments names the notice from its first token`   // Review Focus 3: UserSent("/mcp reconnect x") then :2-6 → command == "mcp"
// TranscriptReaderTest
`a local command on disk is the same notice as its stream`   // transcripts/mcp-slash.jsonl: a User("/mcp") then Notice(LOCAL_COMMAND, command == "mcp"); line 13 → User("/help"); 14 → the notice with command == "help"
// `loads the same conversation as the stream fixture`: its history-permission and history-reply assertions stay as they are (the 2 User entries, lastModel
//   `claude-sonnet-5-5` and history-reply's effort belong to those fixtures only); then a loop over mcp-status, mcp-slash and mcp-tool-call folds each
//   stream as today (no UserSent) and compares, per fixture, the Assistant blocks and the ToolResults (text, isError) as today, plus only the
//   NoticeKind.LOCAL_COMMAND notices as (level, text) (spec 9.2). The stream's other notices, such as mcp-status's three ERROR `The CLI rejected a
//   request: …` from the error answers at :36-38, have no twin line, so they stay out of the comparison and never out of the stream (hard rule 5);
//   `command` is not compared (ReducerTest asserts it). mcp-tool-call: stream and twin each hold three tool_use and three tool_result blocks (a grep
//   count, not yet run as a test), so its tool rows are expected to match; if a run shows a difference, report the two entries instead of narrowing
//   the comparison.
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.ReducerTest' --tests 'dev.daarkolabs.tandem.session.transcript.TranscriptReaderTest'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the reducer and reader rules above.
- [ ] **Step 4: Run** the Step 2 command → PASS, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`session/` stays ≥ 90 %).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/session && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(session): MCP server merge, command catalogue and local-command notices"`

---

### Task M2c: session-mcp-controls

**Depends on:** M2b.

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/McpControls.kt` (the `McpControls` interface, `McpAuthWait`, `McpAuthOutcome`), `.../session/SessionManager.kt`, `.../session/SessionHandle.kt`, `src/test/resources/fake-claude.py` (its docstring too)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/SessionManagerTest.kt` (`manager()` gains `mcpAuthEvery: Duration = 2.seconds, mcpAuthTimeout: Duration = 3.minutes`)

**Interfaces:**
- Consumes: M1's types, `ControlPayloads`; M2a's `request()`, typed methods, `McpResult`, `McpError`, `TANDEM_MCP_SERVER`, `McpState`, `SessionEvent.McpServersListed`; M2b's reducer (the fake's inline `initialize` answer fills `ChatState.commands`).
- Produces (spec 4.2):
  ```kotlin
  interface McpControls { suspend fun refreshMcp(id: SessionId): McpResult<List<McpServerInfo>>; suspend fun toggleMcp(id: SessionId, server: String, enabled: Boolean): McpResult<Unit>; suspend fun reconnectMcp(id: SessionId, server: String): McpResult<Unit>; suspend fun startMcpAuth(id: SessionId, server: String, open: suspend (url: String) -> Unit): McpResult<McpAuthStart>; fun cancelMcpAuth(id: SessionId, server: String) }
  class SessionManager(…, mcpAuthEvery: Duration = 2.seconds, mcpAuthTimeout: Duration = 3.minutes) : McpControls
  data class McpAuthWait(val server: String, val startedAtMillis: Long, val outcome: McpAuthOutcome? = null)
  sealed interface McpAuthOutcome { data object Connected; data object StillNeedsAuth; data class Changed(val status: McpStatus, val error: String?); data object Removed; data object NoLink; data class Failed(val error: McpError) }   // NoLink: a link-less success still needs-auth (spec 3.3 step 2)
  class SessionHandle { val mcpServers: StateFlow<McpState>; val mcpAuth: StateFlow<Map<String, McpAuthWait>>; val mcpLoginHinted: StateFlow<Set<String>>; val mcpActions: Mutex }   // members of the existing class
  ```
- `SessionManager`: `handle.liveSession() ?: return Failed(NotRunning)`; each request under `handle.mcpActions.withLock { … }`, never a follow-up refresh inside the lock. `refreshMcp` coalesces per handle under `lock`: `running: Deferred?` and `next: CompletableDeferred?`; a caller finding a run in flight awaits `next` (created once; the running job runs one more `mcpStatus` when it finishes and completes `next` with it). `startMcpAuth` is `handle.scope.async { … }.await()` (the whole flow survives the caller): the authenticate request; `Failed` → `handle.notice(ERROR, "Sign-in to <name> failed: <text>")` (text: `Rejected.message`, or `The CLI did not answer within 30 s.`; `Ended`/`NotRunning` post nothing), `mcpLoginHinted += name` for `Rejected`/`TimedOut` (an error or timeout of `mcp_authenticate` itself, spec 4.2), return it; `Ok` with a url and `requiresUserAction != false` → `open(url)` (a throw becomes `Failed(Rejected(e.message))` with the ERROR notice, and the name joins `mcpLoginHinted`, since the panel shows the hint for every `Failed` that `startMcpAuth` returns), publish `McpAuthWait(name, clock(), null)`, launch the poll in `handle.scope` (kept in a per-handle `MutableMap<String, Job>`): every `mcpAuthEvery` call `refreshMcp(id)`; `Ok` → the entry by name: absent → `Removed`; `CONNECTED` → `Connected`; `NEEDS_AUTH` → go on; else `Changed(status, error)`; `Failed(Rejected|TimedOut)` → remember it, go on; `Failed(NotRunning|Ended)` → `Failed(Ended)`; at `mcpAuthTimeout` → `StillNeedsAuth` when the last tick was `Ok`, else `Failed(lastError)`. `Ok` without a url or with `requiresUserAction == false` → one `refreshMcp` and the same mapping, with `NEEDS_AUTH` → `NoLink` (its own outcome, never a `Failed`, so the panel can tell it from a poll that failed: it reads `Could not start sign-in: the CLI sent no sign-in link` with no hint, and the name does not join `mcpLoginHinted`) and that one refresh's `Failed(Rejected|TimedOut)` → `Failed(that error)`. Each outcome sets `McpAuthWait.outcome`, posts its notice (`INFO "Signed in to <name>."`, `WARNING "Sign-in to <name> did not complete within 3 minutes."`, `ERROR "Sign-in to <name> failed: <text>"` for `Failed(Rejected|TimedOut)`, for `Changed` with a non-null error (the text: that error, so a sign-in that ends `failed` while the panel is closed still reaches the chat, spec 3.3 step 5 and hard rule 5), and for `NoLink` with the text `the CLI sent no sign-in link`; `Removed`, `Changed` without an error and `Failed(Ended)` post none, see Deviations) and removes the entry 5 s later. `cancelMcpAuth` cancels the job and removes the entry.
- `fake-claude.py`, spec 9.2 (a)–(f) verbatim (the docstring states (a)'s two sentences and (e)'s scope word for word): `initialize` answered inline from the fixture's initialize response when the host frame is read, inside every `wait_for`; every `mcp_*` answered inline, never satisfying a predicate or consuming a fixture line; an MCP fixture (a `control_response` with `mcpServers`) serves every `control_response` on demand from a server table seeded by the first payload without `pending`, the first `pending` payload served to the first `mcp_status` only, `interrupt` → `{"still_queued":[]}`, other host requests → bare success; a fixture without `mcpServers` answers every `mcp_*` with `{"subtype":"error","error":"Unknown subtype"}`; the (d) table (`mcp_toggle` flips any status, unknown name → `Server not found: <name>`; `mcp_reconnect`: `failed` → its error, `needs-auth` → `Server status: needs-auth`, unknown → not found, else bare success; `mcp_authenticate`: `needs-auth` → the `:40` payload, unknown → not found, else bare success without `authUrl`); `TANDEM_FAKE_AUTH_DONE_MS` flips the entry `connected` (echo's `serverInfo`/`tools`, its own config, no error) on the first `mcp_status` served that many ms after the authenticate; `TANDEM_FAKE_EXTRA_NEEDS_AUTH=<name>` copies authy's entry under that name; every replayed `init` in an MCP fixture has `mcp_servers` rewritten from the table.

- [ ] **Step 1: Write the failing tests** (spec 9.2, names verbatim; `SessionManagerTest` with the fake, `frames()`/`answers()`; one WARNING/INFO/ERROR notice is `entries.filterIsInstance<ChatEntry.Notice>().count { it.level == … }`). Rule (c) serves the `:2` pending payload (echo `pending`) to an `mcp-status` process's first `mcp_status` only, so every `SessionManagerTest` on the `mcp-status` fixture except `opening the servers sends mcp_status and the handle publishes the rows` calls `manager.refreshMcp(id)` once before its first action, which uses that payload up; never change the fake's pending rule to suit a test (M4's IDE test and the panel's pending re-poll rely on it):

```kotlin
// SessionManagerTest
`opening the servers sends mcp_status and the handle publishes the rows`   // mcp-status: refreshMcp before any prompt; frames() has mcp_status; mcpServers.value.servers.map { it.name } == [echo, broken, authy], detailed
`toggle sends mcp_toggle and a following refreshMcp shows disabled`   // `manager.refreshMcp(id)` once first (rule (c)), so the refresh after the toggle reads echo DISABLED, not the pending payload's PENDING
`toggle of an unknown server returns Server not found`; `reconnect of a failed server returns the CLI's error`
// spec: reconnect of a needs-auth server returns Server status: needs-auth
`reconnect of a needs-auth server returns Server status - needs-auth`
`startMcpAuth opens the auth url and the poll sees the server connect`   // TANDEM_FAKE_AUTH_DONE_MS=500, mcpAuthEvery = 50.milliseconds; `manager.refreshMcp(id)` once first, which uses up the fake's first-call pending payload (rule (c)): open called once with the :40 url; mcpAuth["authy"]!!.outcome becomes Connected; one INFO notice
`the sign-in poll times out while the server still needs auth`   // mcpAuthTimeout = 300.milliseconds: StillNeedsAuth; one WARNING notice
`an mcp_status error answer is Rejected with the CLI's text`   // plain-reply: Rejected("Unknown subtype")
`startMcpAuth answered with Unknown subtype opens nothing and posts the error notice`   // open never called, mcpAuth empty, one ERROR "Sign-in to authy failed: Unknown subtype", "authy" in mcpLoginHinted
`toggle and reconnect answered with Unknown subtype are Rejected with the CLI's text`; `startMcpAuth without an authUrl refreshes once`   // `manager.refreshMcp(id)` once first (the pending payload of rule (c) would read echo PENDING); echo: no open, exactly one mcp_status after the authenticate, outcome Connected
`the sign-in steps survive the caller's cancellation before and after the answer`; `two sign-in waits run side by side`   // TANDEM_FAKE_EXTRA_NEEDS_AUTH=authy2, `manager.refreshMcp(id)` once first (rule (c)'s pending payload has no authy2, so its first tick would end Removed): both entries present, both Connected, two INFO notices; cancelMcpAuth(id, "authy") in a second run leaves authy2's wait
`a stopped session answers NotRunning`; `two actions run one after the other`; `a toggle followed by its refresh completes while a poll runs`; `three refreshes at once share one follow-up run`   // after the first refreshMcp, frames() holds at most 2 more mcp_status
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.SessionManagerTest'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the rules above: the manager, the handle and the fake.
- [ ] **Step 4: Run** the Step 2 command → PASS, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`session/` stays ≥ 90 %); then the fake's docstring check: `grep -c "leaves \`initialize\` unanswered" src/test/resources/fake-claude.py` and `grep -c "replays \`init\` verbatim" src/test/resources/fake-claude.py` → 1 each (spec 9.2 (a) and (e) word for word); then `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS, the existing IDE tests only (the new ones arrive in M4 and M5): they replay `interrupt`, `model-switch`, `permission` and the other fixtures through the rewritten fake, whose rule (a) moves the `initialize` answer off its recorded position, so a regression shows here, on this task.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/session src/test/resources/fake-claude.py && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(session): MCP controls, sign-in poll and the fake CLI's MCP answers"`
---

### Task M3: chat-mcp-palette

**Depends on:** M2b (its types; runs after M2c, spec 10's order).

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/chat/McpPanelModel.kt`, `.../chat/SlashPalette.kt`
- Modify: `.../chat/ChatRow.kt` (`NoticeRow` fields, `NoticeLink`, `MCP_TERMINAL_HINT`), `.../chat/ChatRowsBuilder.kt:331-335`, `.../chat/ComposerModel.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/chat/McpPanelModelTest.kt`, `.../chat/SlashPaletteTest.kt`, `.../chat/SlashInterceptTest.kt`; Modify `.../chat/ChatRowsBuilderTest.kt`, `.../chat/ComposerModelTest.kt`

**Interfaces:**
- Consumes: M1 `McpStatus`, `McpServerInfo`, `SlashCommand`; M2a `McpState`, `TANDEM_MCP_SERVER`; M2b `NoticeKind.LOCAL_COMMAND`, `ChatEntry.Notice.command/runCommand`, `ChatState.commands/slashCommandNames/terminalCommands`, `ModelOption`.
- Produces (spec 4.3, plus the deviations):
  ```kotlin
  enum class McpAction(val label: String, val caption: String) { ENABLE("Enable","Enabling…"), DISABLE("Disable","Disabling…"), RECONNECT("Reconnect","Reconnecting…"), AUTHENTICATE("Authenticate","Starting sign-in…") }
  enum class McpSource(val title: String) { CLAUDEAI("claude.ai connectors"), PROJECT("Project"), LOCAL("Local"), USER("User"), PLUGIN("Plugins"), DYNAMIC("Session"), MANAGED("Managed"), OTHER("Other") }
  data class McpRowModel(val key: String, val name: String, val displayName: String, val status: McpStatus, val label: String, val detail: String?, val error: String?, val tools: String?, val version: String?, val actions: ImmutableList<McpAction>, val readOnly: Boolean, val rawSource: String?)
  data class McpGroupModel(val source: McpSource, val rows: ImmutableList<McpRowModel>); data class McpPanelModel(val connected: Int, val total: Int, val groups: ImmutableList<McpGroupModel>, val hasPending: Boolean, val stopped: Boolean)
  fun mcpPanel(state: McpState, ownServer: String = TANDEM_MCP_SERVER, loginHinted: Set<String> = emptySet(), live: Boolean = true): McpPanelModel; fun mcpBadge(state: McpState): String?
  enum class CommandScope { BUILTIN, PROJECT, USER, PLUGIN }
  data class PaletteItem(val name: String, val description: String, val argumentHint: String, val scope: CommandScope, val via: String?, val intercepted: Boolean)
  data class SlashPalette(val query: String, val items: ImmutableList<PaletteItem>, val selected: Int, val footer: String?)
  fun slashPalette(draft: String, commands: List<SlashCommand>, fallbackNames: List<String>, terminal: Set<String>, selected: Int = 0): SlashPalette?; fun SlashPalette.moved(delta: Int): SlashPalette
  fun paletteFor(draft: String, focused: Boolean, dismissedFor: String?, selection: Pair<String, Int>, commands: List<SlashCommand>, fallbackNames: List<String>, terminal: Set<String>): SlashPalette?
  //   null when !focused or dismissedFor == draft; else slashPalette(draft, commands, fallbackNames, terminal, selected = if (selection.first == draft) selection.second else 0) (spec 4.4)
  fun committedDraft(text: String, composition: IntRange?): String   // text without the composing range (spec 7, Palette and IME); null → text
  fun PaletteItem.insertion(): String; val PaletteItem.sendsAtOnce: Boolean
  sealed interface SlashIntercept { data object OpenMcp; data object PickModel; data class SetModel(val model: String); data object PickPermissionMode; data class Send(val text: String) }
  fun slashIntercept(text: String, models: List<ModelOption>): SlashIntercept
  sealed interface PaletteOutcome { data class Intercept(val intercept: SlashIntercept); data class Insert(val text: String); data class Send(val text: String); data object Ignored }
  fun pick(palette: SlashPalette, insert: Boolean, busy: Boolean, models: List<ModelOption>): PaletteOutcome
  data class NoticeRow(override val key: String, val level: NoticeLevel, val text: String, override val gap: RowGap = RowGap.SECTION, val kind: NoticeKind = NoticeKind.GENERIC, val command: String? = null, val link: NoticeLink? = null) : ChatRow
  enum class NoticeLink(val label: String) { OPEN_MCP("Open the MCP panel") }; const val MCP_TERMINAL_HINT: String = " Use `/mcp` in the terminal for details."
  ComposerModel(+ commands: ImmutableList<SlashCommand>, slashCommandNames: ImmutableList<String>, terminalCommands: ImmutableSet<String>)   // from ChatState; empty defaults
  ```
- Rules. `mcpPanel`: source → group: `"claudeai"` or `config.type == "claudeai-proxy"` → CLAUDEAI, `project`, `local`, `user`, `plugin`, `dynamic`, `managed`, else OTHER with `rawSource = source`; groups in enum order, only non-empty, rows in the CLI's order; `key = "<source.name>/<name>"` with `#2`, `#3`… on a repeat; `displayName` = the name without a leading `claude.ai ` for CLAUDEAI, the full name when that leaves a blank; `label` and `actions` per the 3.2 table (`OTHER` label = `statusText`, `Unknown status` when blank; `NEEDS_AUTH` in `loginHinted` and source != `dynamic` adds `RECONNECT`); own server = `name == ownServer && source == "dynamic"` → `readOnly`, no actions; `live == false` → `stopped = true`, every `actions` empty; `detail` = `config.url` up to its first `?` or `#`, else `config.command`, else null; `tools` = `"N tools"` / `"1 tool"` when `tools != null`; `version = "v" + serverInfo.version`; `error` verbatim whenever non-null; `connected` counts `CONNECTED`; `hasPending` any `PENDING`. `mcpBadge` = `"$connected/$total"`, null when `total == 0`. `slashPalette`: null unless `Regex("^/\\S*$")` matches; catalogue = `commands` minus `terminal` names (a name among Tandem's four is never removed), minus `__`-prefixed names, with each of `mcp`, `model`, `permissions` and `mode` replaced in place by Tandem's row when the catalogue has it and appended when absent (`Manage MCP servers`, `Pick the model`, `Pick the permission mode` for the last two; hint `""`: spec 3.4, an intercepted row always shows Tandem's description and no hint), `intercepted = name in {mcp, model, permissions, mode}`; empty `commands` → Tandem's four plus `fallbackNames` under the same filters (minus `terminal` names other than Tandem's four, minus `__`-prefixed names, deduped against Tandem's four and each other; description `""`) and footer `Commands load when the session starts`; scope: PROJECT for a description ending ` (project)`, USER for ` (user)` (suffix stripped), PLUGIN for a name containing `:`, else BUILTIN; `query == draft`, the slash included (spec 4.3: `pick` sends `Send(palette.query)`); rank on `query.drop(1)`: exact name 0, name prefix 1, alias prefix 2 (`via = alias`), substring 3, catalogue order within a rank, every row when the query is empty; no match → `items` empty, footer `No matching command`; `selected` clamped into the items (0 when empty); `moved` clamps. `insertion() = "/$name "`; `sendsAtOnce = intercepted || hint without every [...] group (depth-counted) contains no "<"`. `slashIntercept` on `text.trim()`: `/mcp` → `OpenMcp`; `/model` → `PickModel`; `/model <rest>` → `SetModel(option.value)` for the option whose `value` or `displayName` equals the whole `rest` ignoring case, else `Send(text)`; `/permissions`, `/mode` → `PickPermissionMode`; else `Send(text)`. `pick` as spec 4.3. Builder: a `LOCAL_COMMAND` notice → `NoticeRow(kind, command = entry.command, link = OPEN_MCP when runCommand == "mcp", text = text.removeSuffix(MCP_TERMINAL_HINT) when linked)`.

- [ ] **Step 1: Write the failing tests** (spec 9.3; synthetic `claudeai`/`project`/`local`/`managed` entries are hand-made `McpServerInfo`s, labelled docs only):

```kotlin
// McpPanelModelTest: `groups follow the fixed order and skip empty ones`; `a connector shows its name without the prefix`; `line two is the command without args or the url without query and fragment`   // "http://h/mcp?x=1#f" → "http://h/mcp"
`Reconnect on a needs-auth row only after the terminal hint and never on a dynamic one`; `a needs-auth entry with an error draws the error line`; `a blank or missing status reads Unknown status`; `a stopped session's rows have no actions`
`labels and actions - connected`; `labels and actions - pending`; `labels and actions - needs-auth`; `labels and actions - failed`; `labels and actions - disabled`; `labels and actions - any other status`   // one test per 3.2 row (spec 9.3): CONNECTED "Connected" [DISABLE]; PENDING "Pending" []; NEEDS_AUTH "Needs auth" [AUTHENTICATE, DISABLE]; FAILED "Failed" [RECONNECT, DISABLE]; DISABLED "Disabled" [ENABLE]; OTHER "weird" → label "weird", [DISABLE]
`the own server is read-only only with source dynamic`; `mcpBadge reads connected over total`   // mcp-status:18 → "1/3"; McpState() → null; `hasPending`
`two entries with the same name keep distinct keys`; `a connector named only by the prefix keeps its full name`   // Review Focus 1, 2
// SlashPaletteTest (catalogue = ControlPayloads.initialize(L("mcp-slash",1).payload)!!.commands, terminal = L("mcp-slash",4).terminalSlashCommands.toSet())
`closed unless the draft is one slash token`; `ranks exact, prefix, alias with via and substring`   // "/rev" → code-review with via == "review" before any substring row
`an exact name beats an alias`   // "/review" → the project `review` first, then code-review via /review
`terminal names and remote-workflow are hidden`; `project, user and plugin labels`   // user and plugin from synthetic entries
`selection is clamped`; `a hint with a placeholder inserts and a bracketed or empty one sends`   // compact → !sendsAtOnce; clear, usage → sendsAtOnce
`Tab always inserts`; `busy picks are ignored except interception and Tab`; `Enter sends the row's name, not the typed prefix`   // "/us" on usage → Send("/usage")
`Enter inserts compact with a trailing space`; `no match sends the draft and reads No matching command`; `a selection stored for another draft reads as 0`   // no match: query == "/help" and pick(slashPalette("/help", catalogue, …)!!, insert = false, busy = false, models) == PaletteOutcome.Send("/help"); through paletteFor: selection ("/m", 2) with draft "/mc" → selected 0; ("/mc", 2) → min(2, items.lastIndex); dismissedFor == draft → null; focused == false → null
`picking mcp or model acts at once whatever the hint`; `the catalogue plus per lists permissions`; `a catalogue that lists permissions shows Tandem's row`   // synthetic: permissions with its own description and hint "<x>" → description "Pick the permission mode", hint "", one row
 `mode plus Enter gives PickPermissionMode`; `the fallback palette lists the four rows and the init names`   // fallback: commands empty, fallbackNames = L("mcp-slash",4).slashCommands, terminal from :4 → no `doctor` row, one `mcp` and one `model` row
`a terminal name Tandem intercepts still lists`   // Review Focus 4: terminal + "mcp" → the mcp row is still there
`a composition in progress does not move the selection`   // spec 7: committedDraft("/mc", 2..2) == "/m"; committedDraft("/m", null) == "/m"; paletteFor(committedDraft("/mc", 2..2), …, selection = "/m" to 2) keeps selected == min(2, items.lastIndex)
// SlashInterceptTest: `the interception table`   // every 3.4 row; `model Opus is case-insensitive`; `mcp with arguments is sent`; `a display name with a space matches as a whole` (the whole remainder is the name: "/model Sonnet 5.5" with ModelOption("sonnet","Sonnet 5.5",null) → SetModel("sonnet"))
// ChatRowsBuilderTest: `mcp-slash golden`   // replay("mcp-slash", prompt = "/mcp", lines = Fixtures.lines("mcp-slash").take(7)) (the /mcp block, :1-7): user bubble "/mcp", NoticeRow(kind LOCAL_COMMAND, command "mcp", link OPEN_MCP, text "3 MCP server(s): 1 connected, 2 not connected, 0 disabled."); then a separate sweep, replay("mcp-slash") with the default prompt over every line: its notices are named from runCommand (command "mcp", "model") and /help's is null, and the only TurnEndRow is the /hello turn's
`the terminal hint is removed by exact match only`   // a text with the hint minus its backticks keeps it
// ComposerModelTest: `commands and terminal names come from the state`
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.*'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS, then `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`chat/` ≥ 90 %).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/chat && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(chat): MCP panel model, slash palette, interception and local-command notice rows"`

---

### Task M4: ui-mcp-panel

**Depends on:** M2c, M3.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/McpSheet.kt`, `.../ui/McpPanelController.kt`, `src/main/kotlin/dev/daarkolabs/tandem/ide/BrowserOpener.kt`
- Modify: `.../ui/SessionHeader.kt:58-90`, `.../ui/TandemToolWindow.kt:160-245`, `.../ui/ChatUiState.kt`, `.../ui/ComposerKeys.kt`, `.../ui/PromptBar.kt` (passes `panelOpen`, handles `ClosePanel`), `.../ide/TandemSessions.kt`, `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/ui/McpPanelControllerTest.kt`; Modify `.../ui/ComposerKeysTest.kt`, `.../ui/ChatUiStateTest.kt`, `src/test/kotlin/dev/daarkolabs/tandem/ide/TandemSessionsTest.kt`

**Interfaces:**
- Consumes: M2a–M2c `McpControls`, `SessionHandle.mcpServers/mcpAuth/mcpLoginHinted/status`, `McpAuthWait`, `McpAuthOutcome`, `McpError`; M3 `mcpPanel`, `mcpBadge`, `McpPanelModel`, `McpRowModel.key`, `McpAction`; `chat/Text.formatDuration`.
- Produces (spec 4.4):
  ```kotlin
  fun interface BrowserOpener { suspend fun open(url: String) }
  object BrowserOpeners { fun default(): BrowserOpener }
  //   System.getProperty("tandem.browserOpener") == "log" → one O_APPEND write of {"type":"__browse__","url":…}\n to $TANDEM_FAKE_LOG on Dispatchers.IO (test only);
  //   else withContext(Dispatchers.IO) { BrowserUtil.browse(url) }. The url is never written to the IDE log.
  class TandemSessions { val browserOpener: BrowserOpener }           // a member of the existing class, = BrowserOpeners.default(); TandemToolWindow reads TandemSessions.get(project).browserOpener
  internal data class McpBusy(val name: String, val action: McpAction, val caption: String, val since: Long)
  internal class McpPanelController(id: SessionId, servers: StateFlow<McpState>, auth: StateFlow<Map<String, McpAuthWait>>, loginHinted: StateFlow<Set<String>>,
      status: StateFlow<SessionStatus>, controls: McpControls, opener: BrowserOpener, scope: CoroutineScope,
      dispatcher: CoroutineDispatcher = Dispatchers.Default, pendingRepollMillis: Long = 2_000) {
      val rows: StateFlow<McpPanelModel>; val busy: StateFlow<McpBusy?>; val message: StateFlow<String?>; val refreshing: StateFlow<Boolean>
      val auth: StateFlow<Map<String, McpAuthWait>>; val rowErrors: StateFlow<Map<String, String>>   // a row's error text, shown with Retry (an extension: the spec keeps it in the row's caption)
      val titleNote: StateFlow<String?>   // " · <name> is no longer configured" while an auth entry's outcome is Removed (the manager drops the entry 5 s later, so the note lasts 5 s), else null
      fun refresh(); fun run(action: McpAction, name: String); fun cancelAuth(name: String); fun close() }
  internal data class AuthCaption(val text: String, val isError: Boolean, val showCancel: Boolean)
  internal fun authCaption(wait: McpAuthWait, nowMillis: Long): AuthCaption?   // pure, in McpPanelController.kt; no caption carries a hint
  //   outcome null → "Waiting for the browser… · ${formatDuration(nowMillis - wait.startedAtMillis)}" (isError false, showCancel true); Connected → "Signed in";
  //   StillNeedsAuth → "Still needs auth after 3 minutes. Finish signing in, then press Refresh servers."; NoLink → "Could not start sign-in: the CLI sent no sign-in link" (isError);
  //   Failed(Rejected(m)) → m (isError); Failed(TimedOut(s)) → "The CLI did not answer within $s s." (isError); Failed(Ended) and Failed(NotRunning) → "The session has ended." (isError);
  //   Changed and Removed → null; showCancel only for the null outcome
  @Composable internal fun McpSheet(controller: McpPanelController, onClose: () -> Unit, firstFocus: FocusRequester, modifier: Modifier = Modifier)   // firstFocus: its first enabled action button, else Close
  SessionHeader(…, mcp: StateFlow<McpState>? = null, onToggleMcp: () -> Unit = {}, mcpFocus: FocusRequester = remember { FocusRequester() })
  ChatUiState(+ val mcpPanelOpen: MutableState<Boolean> = mutableStateOf(false))
  ComposerAction(+ ClosePanel); composerKey(…, hasAttachments, panelOpen: Boolean = false, clipboardHasImage)   // Esc with panelOpen → ClosePanel before every other rule; M5 inserts paletteOpen before panelOpen
  ```
- Controller rules (spec 3.2, 3.3, 4.4), every request on `dispatcher`, never the EDT:
  - `rows = combine(servers, loginHinted, status) { s, h, st -> mcpPanel(s, loginHinted = h, live = st != STOPPED) }`, `stateIn(scope + dispatcher)`.
  - `refresh()`: `refreshing = true`, `controls.refreshMcp(id)`, `refreshing = false`; `Ok` clears `message`; `Failed(Rejected|TimedOut)` → `message = "Could not list servers: <text>"` where text is `Rejected.message` or `The CLI did not answer within 15 s.`; `Failed(NotRunning)` → no message (the status is `STOPPED` then, and the stopped sheet already shows its sentence); `Failed(Ended)` → `message = "The session has ended."` (spec 7). Triggers: construction; each change of `servers.value.initCount`; `status` going from `STOPPED` to any other value; `pendingRepollMillis` after a `rows` value with `hasPending`, at most 5 times in a row, the count reset by a value without.
  - `run(DISABLE|ENABLE|RECONNECT, name)`: `busy = McpBusy(name, action, action.caption, now)`; `toggleMcp(id, name, enabled = action == ENABLE)` / `reconnectMcp(id, name)`; `busy = null`; an error → `rowErrors[name] = text` (`Rejected.message`; `The CLI did not answer within 15 s.`, `30 s` for RECONNECT; `Ended` → `The session has ended.` with no `Retry`; `NotRunning` → no row error, since the stopped sheet shows its sentence); then `refresh()` unless the answer was `NotRunning`/`Ended`; if that refresh's `Ok` list no longer has `name` and `rowErrors[name]` is set → `message = "<name>: <error> · <name> is no longer configured"`, the row error dropped.
  - `run(AUTHENTICATE, name)`: `busy` with `Starting sign-in…`; `controls.startMcpAuth(id, name, opener::open)`; `busy = null`. Only a `Failed` returned by that call itself (spec 3.3 step 4; the manager has then put the name in `mcpLoginHinted`, so the row offers `Reconnect`) shows a hint: `Rejected(m)` → `rowErrors[name] = "Could not start sign-in: <m>"` (`m` = `Unknown subtype`, `the sign-in link is not an http(s) URL`, …), `TimedOut` → `The CLI did not answer within 30 s.` with no prefix, each plus, on the next line, the hint by the row's source: `Run claude mcp login "<name>" in a terminal, then press Reconnect, or start a new session.` for every source but `dynamic` (an unknown one included), `Start a new session to try the sign-in again.` for `dynamic`; `Ended` → `The session has ended.` with no hint and no `Retry`; `NotRunning` → nothing (the stopped sheet's sentence). The wait itself is read from `auth`: the sheet draws `authCaption(wait, now)` (Interfaces) under the row, in `text.error` when `isError`, with `Cancel` when `showCancel`, `now` ticking each second while the outcome is null; a poll that ended `Failed(Rejected|TimedOut)` shows the bare error, since the sign-in did start (no prefix, spec 3.3 step 3); `Changed` shows none (the row's own status and error say it); `Removed` shows through `titleNote` on the title. After an outcome the row keeps its own buttons, so a row still `needs-auth` offers `Authenticate` again.
  - `cancelAuth(name)` → `controls.cancelMcpAuth(id, name)`; `close()` cancels the controller's child `Job`, which every launch runs under.
- Sheet (spec 3.2, 3.7), placed in `TandemToolWindow`'s chat `Box` after the chat content and before the drawer, while `ui.mcpPanelOpen.value`:
  - a scrim `Box(Modifier.matchParentSize().background(scrim).pointerInput(Unit) { detectTapGestures { ui.mcpPanelOpen.value = false } })`, then the sheet at the top of the box, `Modifier.chatColumn()`, `heightIn(max = 60 % of the box's measured height)`, `VerticallyScrollableContainer`, `testTag("mcp-panel")`, `semantics { contentDescription = "MCP servers panel" }`, `onPreviewKeyEvent` Esc → `onClose()`.
  - title row: `Text("MCP servers · C of T connected" + " · Refreshing…" while refreshing + titleNote.orEmpty())`, `IconButton` `Refresh servers` (`Actions.Refresh`), `IconButton` `Close` (`General.Close`); under it `message` in `text.error` with an `OutlinedButton` `Retry` → `refresh()`.
  - a `LazyColumn` of groups (`Text(group.source.title)`) and rows keyed by `row.key`: `semantics(mergeDescendants = true) { contentDescription = "<name>, <label>" }`, ≥ 40 dp; line 1 `displayName` (one line, ellipsised, tooltip = the full name) · label · `tools` · `version`; line 2 `detail` in the editor style `text.info`, or `Tandem's own server` for a read-only row; line 3 `error` in `text.error` when present (`maxLines = 3`, the full text in the tooltip); the `Other` group draws `rawSource` as a plain `Text` pill.
  - at the right: `OutlinedButton`s for `row.actions` with `contentDescription = "<Action> <name>"`; while `busy` names this row its buttons are replaced by `busy.caption`, and every other row's buttons are disabled; a wait on this row shows `authCaption(wait, now)` and, when `showCancel`, `Cancel` (`contentDescription = "Cancel <name>"`); a `rowErrors` entry shows under the buttons with `Retry` (re-runs the same action; none for `The session has ended.`). Empty: `No MCP servers are configured for this session.` Stopped (`rows.stopped`): rows drawn with `alpha = 0.5f`, no buttons, and `The session is not running. Send a prompt to start it, then manage its servers.`
  - focus: `LaunchedEffect(Unit)` requests the first enabled action button, else `Close`; `onClose` sets `promptFocusFor = selected.id` so the composer gets it back. The controller is `remember(selected.id) { McpPanelController(…) }` inside `key(selected.id)`, closed in a `DisposableEffect`; the dimmed chat's `onEnter` skips Tab while the sheet is open, as under the drawer: `Previous` lands on `mcpFocus`, `Next` on the sheet's `firstFocus`.
- Header (spec 3.1): after the title, `IconButton(onClick = onToggleMcp, Modifier.focusRequester(mcpFocus)) { Icon(AllIconsKeys.Webreferences.Server, contentDescription = "MCP servers") }` and `Text(mcpBadge(state))` in the footer size, `text.info`, inside `WithTooltip("MCP servers: C of T connected")` (`"MCP servers"` when the badge is null); drawn only when `mcp != null`; `TandemToolWindow` passes `mcp = selected?.mcpServers`, `onToggleMcp = { ui.mcpPanelOpen.value = !ui.mcpPanelOpen.value }` and makes the chat's `Previous` redirect target `mcpFocus` when `selected != null`, else `newSessionFocus`. `PromptBar` passes `panelOpen = ui.mcpPanelOpen.value` and maps `ClosePanel` to `ui.mcpPanelOpen.value = false`.
- IDE harness: `runChat` and `ideContext` (`ToolWindowIdeTest.kt:662-702`) gain `extraEnv: Map<String, String> = emptyMap()` (each entry through `withEnv`) and `browserLog: Boolean = false` (`addSystemProperty("tandem.browserOpener", "log")`), passed by name from the new tests.

- [ ] **Step 1: Write the failing tests** (spec 9.4, 9.5; `McpPanelControllerTest` JUnit 5, `runBlocking { withTimeout(10_000) { … } }`, a `RecordingControls : McpControls` whose answers the test sets per call and which records every call, `MutableStateFlow`s, a recording `BrowserOpener`, `pendingRepollMillis = 20`):

```kotlin
// McpPanelControllerTest
`refresh on open, on initCount, after pending and never when closed`   // refreshMcp calls: 1 at construction; +1 after servers.value = servers.value.copy(initCount = 2); with hasPending answers at most 5 re-polls; after close() none
`a refresh error shows in the title area`                              // Failed(Rejected("Unknown subtype")) → message == "Could not list servers: Unknown subtype"
`an action whose follow-up refresh fails shows the title-area error`   // toggle Ok, then refresh TimedOut(15) → "Could not list servers: The CLI did not answer within 15 s."
`an action error on a server gone from the refresh moves to the title area`   // toggle Rejected("Server not found: echo"), refresh without echo → message == "echo: Server not found: echo · echo is no longer configured", rowErrors empty
`a panel opened while the session is stopped refreshes when the process starts`   // status STOPPED → IDLE: one more refreshMcp
`a process that stops greys the rows and hides the actions`           // status → STOPPED: rows.value.stopped, every actions empty
`Authenticate opens the url off the main thread`                       // run(AUTHENTICATE) is called from a single-thread dispatcher whose thread is named "edt" (Executors.newSingleThreadExecutor { Thread(it, "edt") }.asCoroutineDispatcher()), the controller on dispatcher = Dispatchers.Default; RecordingControls.startMcpAuth calls open, and the opener's recorded Thread.currentThread().name is not "edt"
`a wait already running shows on a new controller`; `cancelAuth calls cancelMcpAuth`; `other rows stay enabled during the wait`; `two rows wait at once`
`loginHinted from the handle survives a new controller`; `a needs-auth row offers Reconnect after the terminal hint`
// one test per 3.3 string (spec 9.4), plus the poll-failed caption:
`sign-in string - Could not start with the terminal hint on a user row`   // startMcpAuth Failed(Rejected("Unknown subtype")) → "Could not start sign-in: Unknown subtype" + "\n" + the terminal hint
`sign-in string - Could not start with the new-session hint on a dynamic row`   // the same answer → + "Start a new session to try the sign-in again."
`sign-in string - a link that is not http or https`   // Failed(Rejected("the sign-in link is not an http(s) URL")) → "Could not start sign-in: the sign-in link is not an http(s) URL" + the hint
`sign-in string - no sign-in link`   // authCaption(McpAuthWait("authy", 0, NoLink), 0) == AuthCaption("Could not start sign-in: the CLI sent no sign-in link", isError = true, showCancel = false); no hint
`sign-in string - Signed in`; `sign-in string - still needs auth after 3 minutes`   // authCaption(… Connected …)!!.text == "Signed in"; StillNeedsAuth → "Still needs auth after 3 minutes. Finish signing in, then press Refresh servers."; a null outcome at nowMillis = startedAtMillis + 65_000 → "Waiting for the browser… · " + formatDuration(65_000), showCancel
`sign-in string - the authenticate request times out`   // startMcpAuth Failed(TimedOut(30)) → "The CLI did not answer within 30 s." + the hint, no "Could not start sign-in:" prefix
`sign-in string - a poll whose last tick failed shows the bare error`   // authCaption(… Failed(Rejected("Unknown subtype")) …) == AuthCaption("Unknown subtype", isError = true, showCancel = false); Failed(TimedOut(15)) → "The CLI did not answer within 15 s."; no prefix, no hint; through the controller, AUTHENTICATE still in the row's actions
`sign-in string - a server gone from the poll`; `sign-in string - the session has ended`   // auth.value = mapOf("authy" to McpAuthWait("authy", 0, Removed)) → controller.titleNote.value == " · authy is no longer configured" and authCaption(…) == null; the entry gone → titleNote null; authCaption(… Failed(Ended) …)!!.text == "The session has ended."
// ComposerKeysTest: `Esc with the panel open closes it, never stops`   // busy = true, panelOpen = true → ClosePanel; `calls without the new argument behave as before`
// ChatUiStateTest: `the panel state is kept per session key`           // presenters.ui("a").mcpPanelOpen.value = true; ui("b") false; ui("a") still true
// TandemSessionsTest: `refreshing a session's servers through the service's manager sends mcp_status to the fake`   // fixture mcp-status; manager.refreshMcp(id) is Ok; the log has {"subtype":"mcp_status"}
// ToolWindowIdeTest (fixture mcp-status; newSession() first, then wait for the fake's __spawn__ line in the log):
`the MCP panel lists the servers and Disable sends mcp_toggle`   // iconButton("MCP servers").click(Point(10,10)); waitFor node @contentdescription='echo, Connected'; click 'Disable echo' at Point(10,10); waitFor 'echo, Disabled'; the log has {"subtype":"mcp_toggle","serverName":"echo","enabled":false}; iconButton("Close").click
`Authenticate opens the browser link and the row connects`       // extraEnv TANDEM_FAKE_AUTH_DONE_MS=4000, browserLog = true; iconButton("MCP servers").click(Point(10,10)); waitFor 'authy, Needs auth'; click 'Authenticate authy'; waitFor 'Cancel authy'; waitFor 'authy, Connected'; the log has a {"type":"__browse__","url":…} line whose url starts with "http://127.0.0.1:33041/authorize?"
// `several sessions live side by side in the sidebar` (:409-414): Shift+Tab from the drawer's New session → focusedIconButton("MCP servers"); Shift+Tab → focusedIconButton("New session"); Tab → "MCP servers"; Tab → focusedButton("New session")
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.*' --tests 'dev.daarkolabs.tandem.ide.TandemSessionsTest'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS; `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (the two new tests and the Shift+Tab update).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/ide src/test/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ide src/integrationTest && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(ui): MCP servers panel with badge, actions and browser sign-in"`

---

### Task M5: ui-palette-and-notices

**Depends on:** M4.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/SlashPaletteView.kt`
- Modify: `.../ui/PromptBar.kt` (palette state, keys, `pick`, interception before the attachment encoding, placeholder, the `Slash commands` row), `.../ui/ComposerKeys.kt`, `.../ui/ChatUiState.kt`, `.../ui/FooterControls.kt` (`menuRequest`), `.../ui/rows/NoticeItem.kt`, `.../ui/ChatPanel.kt:302-313` (`RowItem`), `.../ui/ChatScreen.kt` (`ChatPane` passes `onOpenMcp`), `.../ui/TandemToolWindow.kt`, `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/ui/ComposerKeysTest.kt`, `.../ui/ChatUiStateTest.kt`

**Interfaces:**
- Consumes: M3 `slashPalette`, `paletteFor`, `committedDraft`, `SlashPalette`, `PaletteItem`, `moved`, `pick`, `PaletteOutcome`, `slashIntercept`, `SlashIntercept`, `NoticeRow.kind/command/link`, `NoticeLink`; M4 `ChatUiState.mcpPanelOpen`, `ComposerAction.ClosePanel`; polish `AboveAnchor`, `AddButton`, `ModeControl`, `ModelControl`, `PromptBox(ui, busy, cardOpen, …)`.
- Produces (spec 4.4):
  ```kotlin
  internal enum class FooterMenu { MODE, MODEL }
  ChatUiState(+ val paletteSelection: MutableState<Pair<String, Int>> = mutableStateOf("" to 0), val paletteDismissedFor: MutableState<String?> = mutableStateOf(null),
                val menuRequest: MutableState<FooterMenu?> = mutableStateOf(null); fun openPalette())   // openPalette: draft becomes "/" when it is empty, else untouched
  ComposerAction(+ PaletteUp, PaletteDown, PalettePick, PaletteInsert, PaletteClose)
  composerKey(…, hasAttachments, paletteOpen: Boolean = false, panelOpen: Boolean = false, clipboardHasImage)
  //   panelOpen Esc first; then paletteOpen: Up/Down → PaletteUp/PaletteDown, an unmodified Enter → PalettePick, Tab → PaletteInsert, Esc → PaletteClose,
  //   each before CardEscape/Stop/Send; Shift+Enter stays null (the newline); every call site passes draftEmpty onwards by name
  @Composable internal fun SlashPaletteView(palette: SlashPalette, widthPx: Int, maxHeightPx: Int, onPick: (PaletteItem) -> Unit)
  PromptBox(ui, busy, cardOpen, …, palette: SlashPalette?, onPalettePick: (PaletteItem) -> Unit)   // measures its box with onSizeChanged (the width) and onGloballyPositioned (its top in the window) and passes both
  ModeControl(composer, onPick, menuRequest: MutableState<FooterMenu?>); ModelControl(composer, onPickModel, onPickEffort, menuRequest)
  //   LaunchedEffect(menuRequest.value) { if (it == MODE / MODEL) { expanded = true; menuRequest.value = null } } on the hoisted `expanded` of each control
  @Composable internal fun NoticeItem(row: NoticeRow, onOpenMcp: () -> Unit = {})
  RowItem(rendered, ui, lastFrameAt, agentExpanded = false, reply = null, onOpenMcp: () -> Unit = {}); ChatPanel(…, onOpenMcp: () -> Unit = {}); ChatPane(…, onOpenMcp)   // TandemToolWindow passes { ui.mcpPanelOpen.value = true }
  ```
- Palette view (spec 3.4, 3.7): `androidx.compose.ui.window.Popup(popupPositionProvider = AboveAnchor(alignEnd = false), properties = PopupProperties(focusable = false, dismissOnClickOutside = false))`, no `onDismissRequest`; content a `Column` `width(widthPx)` with `testTag("slash-palette")` and `contentDescription = "Slash commands"`, `heightIn(max = min(8 × the row height, maxHeightPx))` in a `VerticallyScrollableContainer`, where `maxHeightPx` is the box's top in the window minus 8 dp (spec 3.4: min(8 rows, the space above the box), scrolling past that, never clamped down over the box); a row (≥ 24 dp, `clickable { onPick(item) }`, `contentDescription = "/<name>"`, `selected = index == palette.selected`): `/<name>` in the editor style, the `argumentHint` after it in `text.info` when non-empty, ` · via /<alias>` when `via != null`, the description under it in the footer size, a scope pill at the right (`project`, `user`, `plugin`; none for BUILTIN) as a plain `Text` in the base 4.2 pill style; `palette.footer` as the last line when non-null. The selected row is scrolled into view on change.
- `PromptBar` rules (spec 3.4):
  - `palette = derivedStateOf { paletteFor(draft, focused, ui.paletteDismissedFor.value, ui.paletteSelection.value, composer.commands, composer.slashCommandNames, composer.terminalCommands) }` (M3) with `draft = committedDraft(ui.draft.text.toString(), ui.draft.composition?.let { it.min until it.max })` (spec 7, Palette and IME: the palette filters on the committed text only, so a composition in progress does not move the selection; check with `javap` that `TextFieldState.composition` is public in the bundled Compose, else read it from `ui.draft.text` as a `TextFieldCharSequence`) and `focused` from the `TextArea`'s `onFocusChanged`; `LaunchedEffect(Unit) { snapshotFlow { <the same committed draft> }.collect { ui.paletteDismissedFor.value = null } }`.
  - `composerKeyEvent` passes `paletteOpen = palette != null`, `panelOpen = ui.mcpPanelOpen.value`; `PaletteUp`/`PaletteDown` → `ui.paletteSelection.value = draft to palette.moved(∓1).selected`; `PaletteClose` → `ui.paletteDismissedFor.value = draft`; `PalettePick`/`PaletteInsert` → `pick(palette, insert = action == PaletteInsert, busy = composer.busy || sending, composer.models)`: `Intercept(i)` → the table below; `Insert(text)` → the draft becomes `text` with the cursor at its end; `Send(text)` → the draft becomes `text`, then the send path; `Ignored` → nothing, but the key event is still consumed (`true`), so a busy Enter types no newline.
  - the send path first runs `slashIntercept(text.trim(), composer.models)`, before polish 4's attachment encoding: `OpenMcp` → `ui.mcpPanelOpen.value = true`, draft cleared; `PickModel` → `ui.menuRequest.value = MODEL`, cleared; `PickPermissionMode` → `MODE`, cleared; `SetModel(m)` → `manager.setModel(handle.id, m)`, `workspace.state.lastModel = m`, cleared; `Send(text)` → today's send with the attachments. An intercepted command leaves the attachment strip untouched.
  - placeholder `Type / for commands`; `AddButton`'s menu gains the row `Slash commands` → `ui.openPalette(); inputFocus.requestFocus()`.
- Notice item: a `LOCAL_COMMAND` row's `Row` gets `semantics(mergeDescendants = true) { contentDescription = row.command?.let { "Reply to /$it" } ?: "Command output" }`; a `link == OPEN_MCP` draws, after the text, a clickable `Text("Open the MCP panel", color = JewelTheme.globalColors.text.info)` (the base link style) with `contentDescription = "Open the MCP panel"` → `onOpenMcp()`; the text is plain, never Markdown.

- [ ] **Step 1: Write the failing tests** (spec 9.4, 9.5):

```kotlin
// ComposerKeysTest
`palette keys map to the palette actions`                 // paletteOpen: Up → PaletteUp, Down → PaletteDown, Enter → PalettePick, Tab → PaletteInsert, Esc → PaletteClose
`Enter sends when the palette is closed`; `Esc goes to the palette before the card`   // paletteOpen && cardOpen: Esc → PaletteClose
`Shift+Enter with the palette open is still a newline`   // Review Focus 5: shift = true → null, and composerFallback(…) == Newline
`Enter with a card and the palette open picks`           // cardOpen = true, paletteOpen = true → PalettePick
`Esc with the panel and the palette open closes the panel`   // panelOpen first
`a call passing clipboardHasImage as a trailing lambda still compiles`   // composerKey(enter, KeyDown, false, false, false, busy = false, cardOpen = false, draftEmpty = true, hasAttachments = false) { false } == Send
// ChatUiStateTest
`palette state is kept per session key`                  // ui("a").paletteSelection.value = "/m" to 2; ui("b") is "" to 0; ui("a") keeps it
`Esc keeps the palette closed until the draft changes`   // paletteDismissedFor "/m" hides the palette for "/m" (the derivedStateOf rule, through M3's paletteFor); "/mc" shows it again
// ToolWindowIdeTest (fixture mcp-slash; newSession() and the __spawn__ wait first; prompts typed with ui.keyboard and sent with Enter, never with the Send click):
`typing slash opens the palette and mcp opens the panel`   // type "/"; waitFor @contentdescription='Slash commands' (if the Driver's tree has no node for the raw Popup, spec 11 item 14, assert the draft "/" through promptBox() instead); type "mc"; enter(); waitFor 'MCP servers panel'; the log has no "user" frame; the panel shows "Could not list servers: Unknown subtype"
`local commands show as notices without a footer`         // before typing each next prompt, wait for the turn to end (iconButton("Send") present and no "Stop"): the notice (:5) arrives before the result that clears busy (:6), and an Enter while busy is Ignored, which would hang the test; "/usage" + Enter → waitFor 'Reply to /usage', 'Open the MCP panel' present, no starts-with(@visible_text,'Worked for'); "/context" + Enter → 'Reply to /context', no 'Worked for'; "/help" + Enter (No matching command: the draft is sent) → 'Reply to /help', no 'Worked for' (spec 9.5: after each of the three); "/hello" + Enter → waitForText("hello from the hello command") and a 'Worked for' footer
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.*'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the rules; then `grep -rn "Claude Code" src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui` → no hit.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS; `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS. If the palette's nodes are absent from the Driver's tree, apply spec 11 item 14's first fallback (assert the draft and the panel) and say so in the test's comment; the in-tree rendering fallback is the owner's call, not this task's.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ui src/integrationTest && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat(ui): slash-command palette, command interception and local-command notices"`

---

### Task M7a: bash-mode-protocol-session

**Depends on:** M2c (`SessionHandle`, `SessionSpawner`, the fake as M2c rewrote it); runs after M5, so its `Reducer.kt`, `ChatState.kt` and `ChatRowsBuilder.kt` edits follow M2b–M5's, and before M7b; no IDE test. Opus; its reviewer Opus.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/protocol/BashEscape.kt`, `.../session/ShellRunner.kt`
- Modify: `.../protocol/HostMessages.kt`, `.../protocol/CliMessage.kt`, `.../protocol/Ndjson.kt` (`Result.userMessageUuids`), `.../session/ChatState.kt`, `.../session/Reducer.kt`, `.../session/SessionHandle.kt` (`queued`, `shellJob`, `shellEvent`, `firstPrompt`, the `firstUser`/`promptTitle` cache, `idleLocked`), `.../session/ClaudeSession.kt`, `.../session/SessionManager.kt`, `.../session/transcript/TranscriptReader.kt`, `.../chat/ChatRowsBuilder.kt` (only a `ChatEntry.Shell` branch that adds no row, so its exhaustive `when`s compile; M7b replaces it), `src/test/resources/fake-claude.py` (rule (g) and its docstring), `docs/protocol.md` (3, 11.4), `CLAUDE.md` (the fake-CLI line gains rule (g))
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/protocol/BashEscapeTest.kt`, `.../session/ShellRunnerTest.kt`; Modify `.../protocol/FixtureDecodeTest.kt`, `.../protocol/HostMessagesTest.kt`, `.../session/ReducerTest.kt`, `.../session/SessionManagerTest.kt`, `.../session/ClaudeSessionTest.kt`, `.../session/SessionHandleTest.kt`, `.../session/transcript/TranscriptReaderTest.kt`

**Interfaces:**
- Consumes: M2c `SessionHandle.pick`, `SessionSpawner.startInBackground`, `fake-claude.py`'s replay and `TANDEM_FAKE_LOG`; the polling read of `ide/ClaudeBinary.outputOf` (the pattern only: `session/` may not import `ide/`).
- Produces (spec 12.4 verbatim, one line each):
  ```kotlin
  object BashEscape { const val INPUT_OPEN = "<bash-input>"; const val INPUT_CLOSE = "</bash-input>"; const val STDOUT_OPEN = "<bash-stdout>"; const val STDOUT_CLOSE = "</bash-stdout>"; const val STDERR_OPEN = "<bash-stderr>"; const val STDERR_CLOSE = "</bash-stderr>"
      fun input(command: String): String; fun output(stdout: String, stderr: String): String; fun context(command: String, stdout: String, stderr: String): String; fun parse(text: String): BashLine? }
  data class BashLine(val command: String?, val stdout: String?, val stderr: String?) { fun shown(): String }   // command to the first </bash-input>, untrimmed; stdout and stderr to the LAST closing tag, stderr opening at the first <bash-stderr> after the last </bash-stdout> (the first one when there is no stdout); missing tag → null; shown(): stdout, then a non-empty stderr on a line of its own
  object HostMessages { fun bashInput(command: String, uuid: String): JsonObject; fun bashOutput(stdout: String, stderr: String, uuid: String, query: Boolean): JsonObject; fun bashContext(command: String, stdout: String, stderr: String, uuid: String): JsonObject }   // members of the existing object
  CliMessage.Result(+ userMessageUuids: List<String> = emptyList())
  fun interface ShellLauncher { fun launch(argv: List<String>, directory: Path, environment: Map<String, String>): Process }
  object Shells { fun argv(command: String, environment: Map<String, String>, os: String = System.getProperty("os.name")): List<String>; const val MAX_OUTPUT_CHARS: Int = 100_000; const val TIMEOUT_MILLIS: Long = 600_000; const val BATCH_MILLIS: Long = 50; fun defaultLauncher(): ShellLauncher }
  sealed interface ShellEnd { data class Exited(val code: Int) : ShellEnd; data object Stopped : ShellEnd; data object TimedOut : ShellEnd; data class NotStarted(val shell: String, val reason: String) : ShellEnd; data class Failed(val reason: String) : ShellEnd }
  data class ShellResult(val output: String, val end: ShellEnd, val durationMs: Long, val cut: Int)
  class ShellRunner(launcher: ShellLauncher = Shells.defaultLauncher(), environment: () -> Map<String, String>, clock: () -> Long = System::currentTimeMillis, timeoutMillis: Long = Shells.TIMEOUT_MILLIS, batchMillis: Long = Shells.BATCH_MILLIS) { suspend fun run(command: String, directory: Path, onOutput: suspend (String) -> Unit): ShellResult; fun killAllNow() }
  ChatEntry.Shell(override val id: String, val command: String, val output: String, val running: Boolean, val end: ShellEnd? = null, val durationMs: Long? = null, val startedAtMillis: Long? = null, val sent: Boolean = false) : ChatEntry
  ChatState(+ runningShell: String? = null, quietSends: Set<String> = emptySet())
  SessionEvent.ShellStarted(val id: String, val command: String, val atMillis: Long); SessionEvent.ShellOutput(val id: String, val text: String); SessionEvent.ShellEnded(val id: String, val end: ShellEnd, val durationMs: Long); SessionEvent.ShellSent(val id: String, val quiet: List<String>, val query: String?, val atMillis: Long)
  SessionEvent.ShellLoaded(val id: String, val command: String, val output: String, val atMillis: Long)   // TranscriptReader only: appends Shell(id, command, output, running = false, startedAtMillis = atMillis)
  SessionEvent.ShellReplyDropped(val id: String)   // ClaudeSession only: interrupt() while the respond frame waits for its quiet result
  sealed interface Outbound { data class Prompt(val text: String) : Outbound; data class ShellEscape(val id: String, val command: String, val output: String, val respond: Boolean) : Outbound }   // SessionHandle.queued: ArrayList<Outbound>; sendOrQueue(Outbound); output already ends with Tandem's Stopped/Killed note
  class SessionHandle { internal fun shellEvent(event: SessionEvent); internal val shellJob: Job?; internal fun startShell(launch: () -> Job): Boolean }   // members of the existing class
  class ClaudeSession(…, quietWaitMillis: Long = 5_000) { internal fun event(event: SessionEvent); fun sendShellEscape(id: String, command: String, output: String, respond: Boolean) }
  class SessionManager(…, respondToShell: () -> Boolean = { true }, shell: ShellRunner = ShellRunner(environment = System::getenv)) { fun runShell(id: SessionId, command: String); fun stopShell(id: SessionId) }   // both constructors; killAllNow also calls shell.killAllNow()
  ```
- Rules the signatures do not determine:
  - `ShellRunner.run` (spec 12.4): `withContext(Dispatchers.IO)`; `argv = Shells.argv(command, environment())`; `launcher.launch` in `runCatching` → a throw is `ShellResult("", NotStarted(argv[0], reason), 0, 0)` with `reason = (e.cause?.message ?: e.message ?: e.javaClass.simpleName).let { if (it.startsWith("error=")) it.substringAfter(", ") else it }`; the process joins `running` (`ConcurrentHashMap.newKeySet<Process>()`) until the `finally`; `p.outputStream.close()` at once; the read loop polls as `ide/ClaudeBinary.outputOf` does and never makes a blocking read (a `read` blocked on a pipe can be neither cancelled nor interrupted, so Stop, the cap and `close()` would wait for the command's next output, and a backgrounded child that keeps stdout open after the shell exits would hold the row for ever): `while (true) { val exited = !p.isAlive; val n = input.available(); if (n > 0) { read min(n, 8192) bytes; decode; batch; yield() } else if (exited) break else delay(15) }`; a `CharsetDecoder` (`UTF_8.newDecoder().onMalformedInput(REPLACE).onUnmappableCharacter(REPLACE)`) over the polled chunks, flushed at the end; kept text appends to a `StringBuilder` up to `MAX_OUTPUT_CHARS`, the rest counted in `cut`; a pending batch is handed to `onOutput` when `batchMillis` have passed since the last hand-off or it holds 8 KB, and once more at the end; `withTimeoutOrNull(timeoutMillis)` around the loop → null means `TimedOut` after the kill; `try/finally` kills on any exit: `p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly()` (skipped when the process already exited), then `p.waitFor(2, TimeUnit.SECONDS)` (bounded: a process stuck in uninterruptible I/O must not hang `close()`'s `cancelAndJoin`), then `running -= p`; `Exited(p.exitValue())` otherwise; `cut > 0` appends `"\n… (cut, $cut more characters)"` to the output; a read or decode that throws propagates after the `finally`. `killAllNow()`: for each process in `running`, `descendants().forEach { it.destroyForcibly() }` then `destroyForcibly()`, never waiting. Logging: the end class, `durationMs` and the character count only.
  - Reducer: `ShellStarted` → `append(Shell(id, command, "", running = true, startedAtMillis = atMillis)).copy(runningShell = id)`; `ShellOutput` → the entry's `output + text` (through `entryIndex`); `ShellEnded` → `running = false, end, durationMs`, `runningShell = null`; `ShellSent` → `sent = true`, `quietSends + quiet`, and with `query != null` the `UserSent` copy without the append (`busy = true, turnInterrupted = false, turn = TurnProgress(query, atMillis, 0, 0, null, 0)`); `Result` with `userMessageUuids.isNotEmpty() && quietSends.containsAll(userMessageUuids)` → `copy(quietSends = quietSends - uuids, sessionId = result.sessionId ?: sessionId)` and nothing else; a result naming a quiet uuid among others → the quiet ones leave the set, then `reduceResult` as today; a result with no uuids and `numTurns == 0` while `quietSends` is not empty (a CLI that omits `user_message_uuids`) → `quietSends - quietSends.first()` (the oldest: Kotlin's sets keep insertion order), `sessionId` taken, nothing else (no `TurnEnded`); `ShellReplyDropped` → `busy = false, turn = null, turnInterrupted = false` and the `Stopped.` notice (`NoticeKind.INTERRUPTED`) an interrupt marker adds, no `TurnEnded`; `reduceEnded` sets `quietSends = emptySet()`; `firstPrompt()` reads the first `User` or `Shell`, a `Shell` as `promptTitle("! " + command)`. `settled()` leaves `Shell` entries as they are (`running` stays what the reader set: false) and sets `quietSends = emptySet()`, as `reduceEnded` does: no process owes those results any more, and the next process starts from `chat.settled()` (`SessionSpawner.newSession`), so a quiet send never pins `idleLocked` past its process.
  - `SessionHandle`: the `firstUser`/`promptTitle` cache that `publishLocked` reads becomes the first `User` or `Shell` entry with `firstPrompt()`'s title, so a live session whose first entry is a Shell is titled `! <command>` before the index lists it; `idleLocked` is also false while `shellJob?.isActive == true` or `chat.quietSends.isNotEmpty()`; `sendOrQueue(Outbound)`: a live process gets `Prompt` through `sendUserMessage` and `ShellEscape` through `sendShellEscape`; `flushLocked` the same, in order; `startShell(launch)`: under the private `lock`, `false` when the handle is closed or `shellJob?.isActive == true`, else `shellJob = launch()` and `true` (the manager cannot take `lock`, so the check-and-set lives here; `shellJob` is read-only outside); `close()` cancels `shellJob` before the scope.
  - `ClaudeSession.sendShellEscape(id, command, output, respond)` (spec 12.4: the recorded sequence, never two sends back to back, since the CLI batches sends queued before it starts processing, `bash-mode:3-6`, and a merged quiet + querying batch is unrecorded): while `state.turn != null` (a prompt flushed just before it) the whole escape is held and starts once that turn's result has been reduced (`Ended` while held drops it; the prompt's turn ends `FAILED` as today). Respond → `uuid1`, `uuid2` = `UUID.randomUUID()`, `dispatch(ShellSent(id, quiet = listOf(uuid1), query = uuid2, clock()))`, `send(bashInput(command, uuid1))`; `bashOutput(output, "", uuid2, query = true)` waits in `pendingOutput` and goes out once `uuid1` has left `state.quietSends`, checked after each line the reader reduces (a result naming `uuid1`, `bash-mode-respond:6`, or the uuid-less zero-turn result rule); a job in the session's scope sends it anyway `quietWaitMillis` after frame 1 if it is still pending; the reader's `Ended` path drops it before dispatching `Ended` (reduceEnded ends the turn `ShellSent` opened as `FAILED`); `interrupt()` while it is pending drops it, sends no interrupt request (no model call runs, `bash-mode-respond:6` `duration_api_ms: 0`) and dispatches `ShellReplyDropped(id)`. Context only → `dispatch(ShellSent(id, quiet = listOf(uuid), query = null, clock()))`, `send(bashContext(command, output, "", uuid))`: one frame, nothing waits.
  - `SessionManager.runShell` (the elapsed time read once, `ms`, so the row and the frame say the same): `handle.startShell { … }` (false: ignored, a command runs or the handle is closed); inside it `id = "shell-${nextShell.incrementAndGet()}"` (a manager counter), `handle.shellEvent(ShellStarted(id, command, clock()))`, then `handle.scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) { … }` (ATOMIC: the body runs even when `stopShell` or `close()` cancels the job before it is dispatched, so its catch still records `Stopped`), whose body always dispatches one `ShellEnded`: `val soFar = StringBuilder(); val result = try { shell.run(command, workingDirectory) { soFar.append(it); handle.shellEvent(ShellOutput(id, it)) } } catch (e: CancellationException) { val ms = clock() - started; withContext(NonCancellable) { handle.shellEvent(ShellEnded(id, Stopped, ms)); if (!handle.isClosed && handle.scope.isActive) dispatchFrames(withNote(soFar.toString(), "Stopped after ${elapsed(ms)}")) }; throw e } catch (e: Exception) { handle.shellEvent(ShellEnded(id, Failed(e.message ?: e.javaClass.simpleName), clock() - started)); LOG.info("shell escape failed: ${e.javaClass.simpleName}"); return@launch }`; then `shellEvent(ShellEnded(id, result.end, result.durationMs))`; `NotStarted` → return; else `dispatchFrames(if (result.end is TimedOut) withNote(result.output, "Killed after 10 minutes") else result.output)`. `withNote(out, note)` = `out`, then `"\n"` unless `out` is empty or ends with one, then `note` (`<bash-stderr>` stays empty, spec 12.2); `dispatchFrames(output)` = `handle.sendOrQueue(Outbound.ShellEscape(id, command, output, respondToShell()))` and, when it queued, `spawner.startInBackground(handle, forPrompts = true)`. `handle.scope.isActive` is false when the project closes (the handle's parent scope cancelled without `close()`), so no Stopped frames reach a CLI that is shutting down. `killAllNow()` gains `shell.killAllNow()`. `formatDuration` is `chat/`'s: `session/` may not import it, so the manager formats with its own one-line `elapsed(ms)` giving the same text (`3.0s` under 10 s, `%.1fs`; `1m 12s`), pinned by `stopShell kills the command and the frames say so`'s regex.
  - `TranscriptReader` (`Scan.userLine`): a user line whose trimmed text starts with `<bash-input>` (today's `!command` branch) → `BashEscape.parse(text)` → `reduce(state, ShellLoaded(uuid ?: "line-N", command.trim(), line.shown(), atMillis))` (a reloaded entry: `running = false`, `end = null`); a line whose trimmed text starts with `<bash-stdout>` or `<bash-stderr>` → `parse` → when the latest entry is a `Shell` whose `output` is empty, `reduce(state, ShellOutput(thatId, line.shown()))`, else skipped (the stray line of the existing test); `typedText` gives `Typed("! " + command.trim(), isPrompt = true)` for a `<bash-input>` line, the title (`factsOf`), and its KDoc's `!command` becomes `! command`; `GENERATED_TAGS` keeps `<bash-stdout>` and `<bash-stderr>`, so `typedText` still gives null for an output line (its KDoc: "Their output … give null") and `factsOf` never counts one as a prompt or a title: `Scan.userLine` handles the output lines before it calls `typedText` (the rule above).
  - `fake-claude.py`, rule (g) (spec 12.8): the fake keeps the `uuid`s of the `user` frames it reads, in order, since the last `result` it wrote; when it writes a `result` line it replaces the line's `user_message_uuids` with that list (an empty list when it read none) and clears it; a `system/status` line's `user_message_uuids` gets the list as it stands. Its docstring states the rule, and `CLAUDE.md`'s fake line gains `It names, in a result's user_message_uuids, the user frames it read since its previous result.` Checked against the fixtures: `bash-mode:8` names the single context frame, `:23` the prompt; `bash-mode-respond:6` frame 1, `:11` and `:41` frame 2. Fixture lines are otherwise replayed verbatim (only `<session_id-N>` is substituted today), so without (g) every quiet result in a fake-CLI test names `<command_uuid-4>`, ends a turn and draws a footer.
  - `ChatRowsBuilder`: `is ChatEntry.Shell -> {}` (no row) in each exhaustive `when` over entries, nothing else; M7b replaces it with the row and the turn boundaries.

- [ ] **Step 1: Write the failing tests** (spec 12.8, names verbatim under the Kotlin name rule; `FixtureDecodeTest`, `HostMessagesTest`, `ReducerTest` JUnit 4, the rest JUnit 5; `ShellRunnerTest` and the process tests of `SessionManagerTest` under `assumeTrue(!System.getProperty("os.name").lowercase().contains("windows"))`, environment `mapOf("SHELL" to "/bin/sh", "TANDEM_TEST" to "x")`; paths compared through `toRealPath()` (macOS temp dirs are `/private/var/…`); `manager()` gains `respondToShell: () -> Boolean = { true }` and `shell: ShellRunner = ShellRunner(environment = { mapOf("SHELL" to "/bin/sh") })`):

```kotlin
// FixtureDecodeTest
`bash-mode - the quiet sends end in a zero-turn result naming both uuids`   // :8 numTurns == 0, durationApiMs == 0L, result == "", userMessageUuids.size == 2; :23 numTurns == 1, userMessageUuids.size == 1
`bash-mode-respond - the output send starts a normal turn`                  // :6 numTurns == 0, one uuid; :11 is SystemStatus "requesting"; :36 text contains "merged"; :41 numTurns == 1, userMessageUuids == listOf("<command_uuid-10>") (the uuid :11 names; SystemStatus gains no field)
// HostMessagesTest `the bash escape frames match the probe's`   // compared as parsed JsonObjects (key order is not significant): bashInput("echo bash-mode-probe && printf err >&2", "u1") and bashOutput("bash-mode-probe\nerr", "", "u2", query = false)
//   equal the two JSON lines of bash probes 1 (uuid replaced); bashOutput(…, query = true) has no "shouldQuery"; bashContext(…, "u3")["message"]["content"] == the message.content of transcripts/bash-mode.jsonl:5
// BashEscapeTest
`parse reads the merged line`   // transcripts/bash-mode.jsonl:5's message.content → BashLine("echo bash-mode-probe && printf err >&2", "bash-mode-probe\nerr", "")
`parse reads an input-only and an output-only line`   // transcripts/bash-mode-respond.jsonl:3 → command "echo bash-mode-probe && printf err >&2", stdout null, stderr null; :6 → command null, stdout "bash-mode-probe\nerr", stderr ""
`an output that contains the closing tag is cut at the last one`   // Review Focus 10: "<bash-stdout>a</bash-stdout>b</bash-stdout><bash-stderr></bash-stderr>" → stdout "a</bash-stdout>b";
//   "<bash-stdout>a<bash-stderr>x</bash-stderr>b</bash-stdout><bash-stderr></bash-stderr>" → stdout "a<bash-stderr>x</bash-stderr>b", stderr "" (stderr opens after the last </bash-stdout>)
`a line without the tags is null`   // "hello", "", "<local-command-stdout>x</local-command-stdout>" → null
`a leading space in the command is kept`   // "<bash-input> echo</bash-input>" → command " echo" (bash probes 2.1)
`input, output and context wrap as recorded`   // context("c", "o", "") == "<bash-input>c</bash-input>\n<bash-stdout>o</bash-stdout><bash-stderr></bash-stderr>"
`a non-empty stderr is shown after stdout`   // BashLine(null, "a", "b").shown() == "a\nb"; ("a\n", "b") → "a\nb"; ("a", "") and ("a", null) → "a"; (null, "b") → "b"
// ReducerTest
`a shell escape is one entry that runs, prints and ends`   // ShellStarted("shell-1", "x", 0), ShellOutput("a"), ShellOutput("b"), ShellEnded(Exited(0), 5): one Shell entry, output "ab", running false, end Exited(0), durationMs 5; runningShell "shell-1" before ShellEnded, null after
`the quiet result of a shell send ends no turn`   // ShellSent(quiet = ["<command_uuid-4>", "<command_uuid-6>"], query = null) then bash-mode:3-8: no TurnEnded, busy false, quietSends empty, sessionId set
`a responding shell send opens a turn that its reply's result ends`   // ShellSent(quiet = ["<command_uuid-4>"], query = "<command_uuid-10>") then bash-mode-respond:3-42: busy true after :6, one TurnEnded after :41, turnsEnded + 1
`a result that names a quiet send and a prompt is the prompt's`   // synthetic (an unrecorded merge): ShellSent(quiet = ["a"], query = null), UserSent("p", "x", 0), then plain-reply's result with userMessageUuids = ["a", "p"] → one TurnEnded, quietSends empty
`a process that ends before the quiet result leaves no quiet send`   // Review Focus 7: ShellSent(quiet = ["a"], query = null), then Ended → quietSends empty; settled() of a state with quietSends = setOf("a") → empty
`a zero-turn result without uuids ends the quiet send without a footer`   // Review Focus 7: ShellSent(quiet = ["a"], query = null), then bash-mode:8 with user_message_uuids removed → quietSends empty, no TurnEnded, busy false
`a dropped shell reply ends the turn without a footer`   // ShellSent(quiet = ["a"], query = "q"), ShellReplyDropped("shell-1") → busy false, turn null, no TurnEnded, the last entry the "Stopped." notice
`the first prompt of a shell escape session is the command`   // ShellStarted("shell-1", "echo hi", 0) → firstPrompt() == "! echo hi"
`a loaded shell escape is a finished entry without an end`   // ShellLoaded → running false, end null
// ShellRunnerTest
`runs in the directory with the environment and merges stderr in order`   // "printf a; printf b >&2; printf c" → "abc"; pwd → dir.toRealPath(); echo $TANDEM_TEST → "x"
`stdin is closed`   // "cat" → Exited(0), output "", within 2 s
`a non-zero exit is reported`   // "exit 3" → Exited(3)
`output over the cap is cut and the rest drained`   // "head -c 300000 /dev/zero | tr '\0' a" → output == "a".repeat(100_000) + "\n… (cut, 200000 more characters)", cut == 200_000, Exited(0)
`invalid UTF-8 is replaced`   // "printf '\377\376'" → output "��"
`a sequence split across chunks is not broken`   // "head -c 8191 /dev/zero | tr '\0' a; printf '\342\202\254'" → output == "a".repeat(8191) + "€", no U+FFFD
`the tree is killed on stop`   // Review Focus 8: "sleep 60 & wait" cancelled → the job ends within 2 s, no sleep among the test's descendants
`the cap kills after the timeout`   // timeoutMillis = 200, sleep 5 → TimedOut within 2 s
`a background child that keeps the output open does not hold the row`   // Review Focus 8: "sleep 30 & echo x" → Exited(0), output "x\n", within 2 s
`killAllNow destroys a running command's tree`   // sleep 30 running; killAllNow() → the run returns within 2 s, no sleep among the test's descendants
`a shell that cannot start reports NotStarted`   // SHELL=/nonexistent/sh → NotStarted("/nonexistent/sh", "No such file or directory")
`the shell argv is the login shell with -lc and ComSpec on Windows`   // argv("x", mapOf("SHELL" to "/bin/zsh")) == ["/bin/zsh","-lc","x"]; SHELL absent or blank → "/bin/zsh" with os "Mac OS X", "/bin/sh" with os "Linux"; os "Windows 11" with ComSpec "C:\\W\\cmd.exe" → [it, "/c", "x"], without → ["cmd.exe","/c","x"]
`output arrives in batches while the command runs`   // "printf a; sleep 0.3; printf b" → two onOutput calls
`a command that prints nothing sends empty stdout`   // `true` → output "", Exited(0) (its frames still go out, with an empty <bash-stdout>, spec 12.6)
// SessionManagerTest
`a shell escape runs, shows its output and sends the two frames`   // bash-mode-respond; runShell(id, "echo hi"): the Shell entry ends Exited(0) with output "hi\n"; the user frames after initialize: content "<bash-input>echo hi</bash-input>"
//   with client_composed true and shouldQuery false, then "<bash-stdout>hi\n</bash-stdout><bash-stderr></bash-stderr>" with client_composed true and no shouldQuery; the reply text contains "The probe ran"; exactly one TurnEnded; quietSends empty after the run, the quiet result having named the first logged frame's uuid (rule (g)); handle.title.value == "! echo hi" before any index listing
`with replies off a shell escape sends one context frame`   // bash-mode, respondToShell = { false }: one frame, content BashEscape.context("echo hi", "hi\n", ""), shouldQuery false; no TurnEnded, busy false; then the prompt → the reply "bash-mode-probe"
`stopShell kills the command and the frames say so`   // sleep 30; stopShell → Stopped within 2 s; the second frame's content matches ^<bash-stdout>Stopped after \d+\.\ds</bash-stdout><bash-stderr></bash-stderr>$
`a shell escape on a stopped session queues its frames and starts a process`   // no process yet; spawns().size == 1 after the run, the frames after that spawn's initialize
`a shell escape that cannot start sends nothing`   // a ShellLauncher that throws → NotStarted, no user frame
`a shell escape whose output cannot be read ends Failed and sends nothing`   // a ShellLauncher whose Process.inputStream throws IOException("boom") from available() → end Failed("boom"), no user frame, runningShell null
`a second shell escape while one runs is ignored`   // runShell twice while sleep 30 runs: one ShellStarted, one Shell entry
`closing the session kills a running command`   // Review Focus 8: sleep 30; close(id) returns within 2 s; end Stopped, no frame, no sleep among the test's descendants
`a project close sends no frame for a running command`   // sleep 30; the manager's scope cancelled without close(): the entry read from handle.liveSession()!!.state.value has end Stopped (the handle's mirror is
//   cancelled with the scope, so handle.state never sees that ShellEnded); no user frame logged; no sleep among the test's descendants
`the timed-out command sends the kill note`   // ShellRunner(timeoutMillis = 200), sleep 5 → the second frame's content "<bash-stdout>Killed after 10 minutes</bash-stdout><bash-stderr></bash-stderr>"
`the command and its output are never logged`   // a capturing Logger.Factory installed with Logger.setFactory (the previous one restored in a finally); runShell(id, "echo tandem-log-marker") through the fake
//   (bash-mode-respond) until its TurnEnded → no record's message or throwable text contains "tandem-log-marker"
`a command stopped at once still ends Stopped`   // Review Focus 8: runShell(id, "sleep 30"), then stopShell(id) in the same call stack → within 2 s end Stopped, runningShell null, the handle's shellJob not active
// ClaudeSessionTest (JUnit 4; a ScriptedProcess whose real process is sh -c "cat > <file>", so the file holds every frame and the test decides when each CLI line arrives;
//   the fake CLI cannot test the order: it reads a frame only where it waits, so its log keeps the recorded order whatever the host does)
`the output frame waits for the quiet result`   // Review Focus 6: sendShellEscape("shell-1", "echo hi", "hi\n", respond = true) → 300 ms later the file holds one user frame (bashInput); the script writes
//   bash-mode-respond:3-7 with :6's user_message_uuids = that frame's uuid → within 2 s a second user frame, the bashOutput without shouldQuery
`the output frame goes out after the wait without a quiet result`   // quietWaitMillis = 200, no script line → the second frame within 2 s
`interrupt drops a pending output frame`   // sendShellEscape(…, respond = true), then interrupt() before any script line → no second user frame and no control_request within 1 s; busy false, the "Stopped." notice
`Ended drops a pending output frame`   // the real process ended before the script writes :6 → one user frame only; one TurnEnded with end FAILED
`a shell escape after a prompt waits for the prompt's result`   // Review Focus 6: sendUserMessage("x"), then sendShellEscape(…, respond = true) → only the prompt frame until the script writes plain-reply's lines
//   (its result ends that turn); then bashInput
// SessionHandleTest
`close cancels the shell job`   // close() → shellJob!!.isCancelled
`a session is not idle while a command runs or a quiet send waits`   // isIdle() false with an active shellJob, false with quietSends not empty, true once both clear
`a session whose process ended during a quiet send is idle on its next process`   // Review Focus 7: ShellSent(quiet = ["a"], query = null) on the live process; the process ends before any result; a new process attached from chat.settled() → isIdle() true
// TranscriptReaderTest
`a merged shell escape line loads as the command and its output`   // transcripts/bash-mode.jsonl → Shell(command "echo bash-mode-probe && printf err >&2", output "bash-mode-probe\nerr", running false, end null), then the User
//   "Reply with only the text the command printed to stdout" (:8) and the Assistant text "bash-mode-probe" (:23)
`a shell escape on two lines loads the same`   // transcripts/bash-mode-respond.jsonl :3 + :6 → one Shell entry with the same command and output, then the Assistant reply (:19-20) whose text starts with "The probe ran"
`a shell escape loads with its output, and a stray stderr line and notifications are skipped`   // replaces the test at :527: shape "shell: !ls -la → notes.txt"; line b3 skipped; firstPrompt == "! ls -la"
`a shell escape reloads as the stream showed it`   // parity, both fixtures: ShellStarted / ShellOutput("bash-mode-probe\nerr") / ShellEnded(Exited(0)) / ShellSent with the fixture's uuids, then the stream lines,
//   against the twin: the Shell entries' (command, output) and the Assistant blocks equal
`the first prompt of a shell escape session is the command`   // meta.firstPrompt == "! echo bash-mode-probe && printf err >&2" on both twins; a transcript whose first and only user line is a stray
//   "<bash-stdout>x</bash-stdout><bash-stderr></bash-stderr>" has meta.firstPrompt == null (GENERATED_TAGS keeps both output tags)
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.protocol.*' --tests 'dev.daarkolabs.tandem.session.*'` → compilation errors (`BashEscape`, `ShellRunner`, `ChatEntry.Shell`, …).
- [ ] **Step 3: Implement** the Interfaces block and the rules above (the fake's rule (g) included); the rule that the command and its output are never logged is checked by `SessionManagerTest` `the command and its output are never logged` (a grep of the `LOG.` lines would miss `LOG.info("ran $c")` and flag `result.output.length`).
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`protocol/`, `session/`, `chat/` ≥ 90 %).
- [ ] **Step 5: Write the docs**: `docs/protocol.md` 3 gains the three bash frames as sent (`bashInput`, `bashOutput`, `bashContext`: `content` a string, `client_composed: true`, `shouldQuery: false` on the quiet ones; recorded, `bash-mode.ndjson:3-8`, `bash-mode-respond.ndjson:3-11`, `transcripts/bash-mode.jsonl:5`; `bashContext` as one host frame **unrecorded**, spec 12.9 item 1), and 11.4's table rows for `<bash-input>` (`! command`, the title form) and `<bash-stdout>`, `<bash-stderr>` (the row's output, no longer `nothing`); `CLAUDE.md`'s fake-CLI line gains rule (g)'s sentence. Check each citation: `sed -n '8p' src/test/resources/fixtures/bash-mode.ndjson | grep -c '"num_turns":0'` → 1, `sed -n '5p' src/test/resources/fixtures/transcripts/bash-mode.jsonl | grep -c '<bash-input>'` → 1.
- [ ] **Step 6: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/protocol src/main/kotlin/dev/daarkolabs/tandem/session src/main/kotlin/dev/daarkolabs/tandem/chat/ChatRowsBuilder.kt src/test/kotlin/dev/daarkolabs/tandem src/test/resources/fake-claude.py docs/protocol.md CLAUDE.md && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat: bash mode's shell runner, frames and quiet results in the session layer"`

---

### Task M7b: bash-mode-ui

**Depends on:** M7a, M3 (`ChatRow`, `ComposerModel`), M5 (`PromptBar`'s send path with `slashIntercept`, which this task extends ahead of it); runs before M6; carries an IDE test. Opus; its reviewer Opus.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/rows/ShellRowItem.kt`
- Modify: `.../chat/ChatRow.kt`, `.../chat/ChatRowsBuilder.kt` (M7a's placeholder becomes the row; `build`'s `turnOpen`, `Region`, `turnEnd`), `.../chat/WorkingIndicator.kt` (`inLastTurn`, `thinkingStreams`), `.../chat/Cards.kt` (`beforeCall`), `.../chat/ToolBodies.kt` (`clamp` made internal), `.../chat/ComposerModel.kt`, `.../ui/rows/ToolBodyView.kt` (`Clamped` and `MonoLines` made internal), `.../ui/PromptBar.kt`, `.../ui/ChatPanel.kt` (`RowItem`), `.../ui/ChatScreen.kt`, `.../ui/TandemToolWindow.kt`, `.../ide/TandemSettings.kt`, `.../ide/TandemSettingsConfigurable.kt`, `.../ide/TandemSessions.kt`, `CLAUDE.md` (the settings page line), `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: Modify `src/test/kotlin/dev/daarkolabs/tandem/chat/ComposerModelTest.kt`, `.../chat/ChatRowsBuilderTest.kt`, `.../ide/TandemSettingsTest.kt`, `.../ide/TandemSessionsTest.kt`

**Interfaces:**
- Consumes: M7a's `ChatEntry.Shell`, `ShellEnd`, `ChatState.runningShell`, `SessionEvent.Shell*`, `SessionManager.runShell`/`stopShell`, `ShellRunner`, the fake's rule (g); M3 `ChatRow`, `RowGap`, `ComposerModel`; M5 `PromptBar`'s send path (`slashIntercept` before the attachment encoding); polish `PromptBox(busy, sending, cardOpen, …)`, `ComposerSender`, `Clamped(owner, part, lineCount, total, ui, byLines, content)` and `MonoLines` (`ui/rows/ToolBodyView.kt`, private today), polish 2.4 item 3's unframed secondary output, `ToolBodies.clamp` (private today; 400 lines, 50,000 characters), `ToolLabels.stripAnsi`, `chat/Text.formatDuration`, `ide/ShellEnvironment.get`.
- Produces (spec 12.4 verbatim, one line each):
  ```kotlin
  fun shellCommand(draft: String): String?   // chat/ComposerModel.kt: ^\s*!\s*\S → the text after "!" trimmed, else null
  ComposerModel(+ shellRunning: Boolean = false)
  enum class StopTarget { Shell, Turn }; fun stopTarget(shellRunning: Boolean): StopTarget   // chat/ComposerModel.kt: Shell while a command runs, else Turn
  data class ShellRow(override val key: String, val command: String, val output: ImmutableList<String>, val totalLines: Int, val running: Boolean, val status: String?, val statusError: Boolean, override val gap: RowGap = RowGap.TURN) : ChatRow   // key "sh:<entry id>"
  @Composable internal fun ShellRowItem(row: ShellRow, ui: ChatUiState, onStop: () -> Unit); RowItem(…, onStop: () -> Unit = {}); ChatPanel(…, onStop: () -> Unit = {}); ChatPane(…, onStop)   // TandemToolWindow passes { manager.stopShell(selected.id) }
  TandemSettings.State(+ var respondToShellOutput: Boolean by property(true))
  ```
- Rules the signatures do not determine:
  - Builder: `ChatEntry.Shell` → `ShellRow("sh:${entry.id}", command, lines, total, running, status, statusError = status != null, gap(Kind.SHELL, before))` with `(lines, total) = ToolBodies.clamp(ToolLabels.stripAnsi(entry.output))` (the private `clamp` made internal; the frames keep the raw text) and `status` from the spec 12.2 table: `Exited(0)`/null → null; `Exited(n)` → `"exit $n"`; `Stopped` → `"Stopped after ${formatDuration(durationMs ?: 0)}"`; `TimedOut` → `"Killed after 10 minutes"`; `NotStarted(shell, reason)` → `"Could not start $shell: $reason"`; `Failed(reason)` → `"Could not read the command's output: $reason"`. Turn boundaries (spec 12.4), every check that reads a `User` reads a `Shell` too: `build`'s `turnOpen` (`entries[turn.first] is ChatEntry.User || entries[turn.first] is ChatEntry.Shell`; without it `ToolStates.of` marks every running call of a responding shell turn `INTERRUPTED`, `ToolStates.kt:23`), `Region`'s turn split, `turnEnd`, `WorkingIndicator.inLastTurn` (`is UserRow, is ShellRow -> return false`) and `thinkingStreams` (`is ChatEntry.User, is ChatEntry.Shell -> return false`), `Cards.beforeCall` (`if (entry is ChatEntry.User || entry is ChatEntry.Shell) break`); `gap`: `RowGap.TURN` above a `ShellRow`, `previous is ShellRow -> RowGap.SECTION`; `withKey` gains the branch.
  - `ShellRowItem`: `Column` (`chatColumn()`): header `Row` (≥ 24 dp): `Text("! ${row.command}", style = JewelTheme.editorTextStyle, modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "! ${row.command}" })` and, while `row.running`, `IconButton(onClick = onStop, Modifier.size(24.dp).semantics { contentDescription = "Stop command" }) { Icon(AllIconsKeys.Actions.Suspend, null) }`; when `row.output` is not empty, 10 dp below, `Clamped(row.key, "output", row.output.size, row.totalLines, ui) { shown, modifier -> Box(modifier.semantics { contentDescription = "Shell output" }) { MonoLines(row.output.take(shown), false) } }` (polish 2.4 item 3: mono, no fill, no `MonoBox`, in the secondary tier as polish draws a Bash output; the clamp state is `ui.clampOpen("sh:<id>", "output")`, spec 12.6); `row.status?.let { Text(it, color = colors.error, style = smallTextStyle()) }`.
  - `PromptBar`: in `send`, before `slashIntercept`: `shellCommand(text)?.let { manager.runShell(handle.id, it); ui.draft.clearText(); ui.follow.value = ui.follow.value.on(ScrollEvent.Sent); scroll; return }`; `ComposerAction.Stop` (Esc) and the composer's Stop button `-> when (stopTarget(composer.shellRunning)) { StopTarget.Shell -> manager.stopShell(handle.id); StopTarget.Turn -> manager.interrupt(handle.id) }` (the pure mapping is `ComposerModelTest`'s; the row's `Stop command` is the IDE test's); `PromptBox(busy = composer.busy || composer.shellRunning, …)`.
  - `TandemSettingsConfigurable`: after `Show thinking rows`, `row { checkBox("Claude replies to ! command output").bindSelected({ settings.state.respondToShellOutput }, { settings.state.respondToShellOutput = it }).comment("Off: the output is added to the conversation without a reply (the CLI's respondToBashCommands: false).") }`. `TandemSessions`: `respondToShell = { TandemSettings.getInstance().state.respondToShellOutput }`, `shell = ShellRunner(environment = ShellEnvironment::get)`.
  - IDE harness: M7a's fake rule (g) makes the quiet result name Tandem's own uuid, so the IDE test sees one `Worked for` footer, not two; `typePrompt("! echo hi")` goes through the same send path; the reply is awaited with `contains(@visible_text, 'The probe ran')`, since the existing `waitForText` matches `@visible_text` exactly and the reply's TextRow is the whole sentence of `bash-mode-respond.ndjson:41`; the second frame's `content` is checked by its start and end and the output by its last line, since the IDE runs the CI user's real login profile through `ShellEnvironment`, which may print first (as `TandemSessionsTest` reads only the last line).

- [ ] **Step 1: Write the failing tests** (spec 12.8, names verbatim under the Kotlin name rule, JUnit 5):

```kotlin
// ComposerModelTest
`a bang draft is a shell escape`   // "! echo hi" → "echo hi"; "  !ls" → "ls"; "!\n ls -la\n" → "ls -la"; "! a\nb" → "a\nb"; "!", "! ", "a!b", "/mcp", "" → null
`shellRunning follows the state`   // runningShell "shell-1" → true; null → false
`Stop stops the command while one runs, else the turn`   // stopTarget(true) == StopTarget.Shell; stopTarget(false) == StopTarget.Turn
// ChatRowsBuilderTest
`a shell escape is a shell row with its output and status`   // running → status null, running true; Exited(2) → "exit 2", statusError; Exited(0) → null; Stopped with durationMs 3000 → "Stopped after 3.0s";
//   TimedOut → "Killed after 10 minutes"; NotStarted("/bin/zsh", "boom") → "Could not start /bin/zsh: boom"; Failed("boom") → "Could not read the command's output: boom"; reloaded (end null, running false) → null
`a shell row's output is cut like a body`   // a command that prints nothing draws no output: 401 lines → 400, totalLines 401; "" → empty list, totalLines 0; "\u001b[31mred\u001b[0m" → ["red"]
`bash-mode-respond golden`   // ShellStarted, ShellOutput, ShellEnded(Exited(0)), ShellSent(quiet = ["<command_uuid-4>"], query = "<command_uuid-10>"), then Fixtures.lines("bash-mode-respond").subList(2, 42) (:3-42)
//   folded with reduce directly, not replay() (whose UserSent before each init would add User rows): at the end [ShellRow, ThinkingRow, TextRow, TurnEndRow] (:14-21's thinking under the
//   default RowOptions(showThinking = true)); after ShellEnded and before ShellSent no WorkingRow; from ShellSent to :41 a WorkingRow last; after :6 still no TurnEndRow
`a responding shell turn shows its running tool call as running`   // Review Focus 9: ShellStarted, ShellEnded(Exited(1)), ShellSent(quiet = ["a"], query = "q"), then an assistant tool_use with no result → its ToolState RUNNING, not INTERRUPTED; the WorkingRow last
`a shell row starts a turn`   // User "a" and its reply, a Shell entry, User "b" and its reply: three turns as with a User in the Shell's place; the gap above the ShellRow is TURN, the row after it SECTION
// TandemSettingsTest `the reply setting round-trips and defaults to on`   // TandemSettings.State().respondToShellOutput == true; set false, then getState/loadState into a new instance → false
// TandemSessionsTest `a shell escape through the service runs in the project directory and reaches the fake`   // runShell(id, "pwd"), bash-mode-respond: the output's last line == the project path's toRealPath(); the log holds both frames
// ToolWindowIdeTest `a bang command runs locally and Claude replies`   // fixture bash-mode-respond: newSession(), the __spawn__ wait; typePrompt("! echo hi"); the node @contentdescription='! echo hi'; waitForText("hi") under
//   @contentdescription='Shell output' (its last line); a node whose @visible_text contains "The probe ran"; exactly one node whose @visible_text starts with "Worked for"; the log's user frames: the first's content
//   == "<bash-input>echo hi</bash-input>" with shouldQuery false, the second's starts with "<bash-stdout>" and ends with "hi\n</bash-stdout><bash-stderr></bash-stderr>", no shouldQuery; no 'Stop command' node once the reply is in
// ToolWindowIdeTest `a bang command stops from the row`   // same fixture: typePrompt("! sleep 30"); click the node @contentdescription='Stop command'; waitFor a node whose @visible_text starts with "Stopped after"; no 'Stop command' node afterwards
```

- [ ] **Step 2: Run** `./gradlew test --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.*' --tests 'dev.daarkolabs.tandem.ide.*'` → compilation errors (`ShellRow`, `shellCommand`, `respondToShellOutput`, …).
- [ ] **Step 3: Implement** the Interfaces block and the rules above; then `grep -rn "Claude Code" src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/ide` → no hit.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (`protocol/`, `session/`, `chat/` ≥ 90 %); `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (the new test and every earlier one).
- [ ] **Step 5: Write the docs**: `CLAUDE.md`'s settings sentence (`## Layout`, the `ide/` line) lists `Claude replies to ! command output`.
- [ ] **Step 6: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/ide src/test/kotlin/dev/daarkolabs/tandem src/integrationTest CLAUDE.md && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "feat: bash mode in the chat, a ! line runs in the login shell and shows its output as a row"`

---

### Task M5r: connector-recording-tooling

**Depends on:** none (parallel in its own worktree; lands before M6; Sonnet).

**Files:**
- Modify: `scripts/drive_cli.py` (`Cli.__init__` base argv at `:55-58`, `SCENARIOS` `:231`, `SCENARIO_FLAGS` `:238`, `main` `:241-251`; the bash scenarios of spec 12.9 item 1, here so that only one task edits `drive_cli.py`), `scripts/redact_fixture.py` (`ID_KEYS` `:29`, `INIT_LISTS` `:40`, `scrub` `:62`, `empty_lists` `:102`, `redact` `:115`), `scripts/record-scenario.sh:13` (the usage line)
- Test: `scripts/test_drive_cli.py`, `scripts/test_redact_fixture.py`

**Interfaces:**
- Consumes: `drive_cli.py`'s `Cli` (`call(request, allow_error)`, `request`, `user(text)`, `until(predicate, on_message)`), `is_init`, `is_result`, `SCENARIOS`, `SCENARIO_FLAGS`; `redact_fixture.py`'s `scrub`, `empty_lists`, `redact(src, dst)`; `record-scenario.sh`'s `raw.ndjson` / `transcripts/<scenario>.jsonl` pairing (`:33-34`).
- Produces (spec 10 item 6):
  ```python
  def connector_name(extra: list[str]) -> tuple[str, list[str]]
  #   the name: os.environ.get("TANDEM_PROBE_CONNECTOR"), else the value after "--connector" in extra; returns it with extra minus
  #   "--connector <value>" (nothing of it reaches claude); neither set → "set TANDEM_PROBE_CONNECTOR" on stderr and SystemExit(2); never written to a file.
  #   main(scenario, out, extra): for mcp-connectors and mcp-connectors-all, name, extra = connector_name(extra) first, then
  #   cli = Cli(extra + SCENARIO_FLAGS.get(scenario, []), drop=SCENARIO_ARGV_DROPS.get(scenario, [])), cli.call(INITIALIZE) as today (:243-246), then run(cli, name);
  #   every other scenario as today: Cli(extra + SCENARIO_FLAGS.get(scenario, []), model=…), run(cli). Cli.__init__ gains drop: list[str] = (), removed from its base argv
  def mcp_connectors(cli: Cli, connector: str, sleep=time.sleep, every: float = 2.0, timeout: float = 180.0) -> None   # tests pass sleep=lambda s: None
  #   the mid-stream frames go through cli.until(is_result, on_message=…), which sends the mcp_status and the mcp_toggle on the first is_init message of the ping turn
  #   frames, in order (main has already sent initialize): mcp_status; mcp_authenticate {"serverName": connector} (allow_error=True: an error is recorded and the run goes on);
  #   the answer's authUrl printed on stderr as "open this link within 3 minutes: <url>";
  #   mcp_status every 2 s until the connector's status is not "needs-auth" or 3 minutes pass;
  #   mcp_toggle enabled False, mcp_toggle enabled True, mcp_reconnect on connector (each allow_error=True);
  #   cli.user("ping") with one mcp_status and one mcp_toggle(enabled False) sent after the ping's init and before its result, and mcp_toggle(enabled True) after the result
  def mcp_connectors_all(cli: Cli, connector: str) -> None   # the same frames
  SCENARIO_ARGV_DROPS = {"mcp-connectors": ["--safe-mode"], "mcp-connectors-all": ["--safe-mode"]}   # Cli(…, drop=…) removes them from its base argv (:55-58)
  SCENARIO_FLAGS["mcp-connectors"] = ["--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}']   # never --setting-sources; mcp-connectors-all adds nothing
  # redact_fixture.py
  INIT_KEEP = {"mcp-status": {"mcp_servers"}, "mcp-tool-call": {"mcp_servers"},
               "mcp-slash": {"mcp_servers", "slash_commands", "terminal_slash_commands", "commands"},
               "mcp-connectors": {"mcp_servers"}, "mcp-connectors-all": {"mcp_servers"}}   # probes 3.6: re-running over the committed MCP fixtures keeps what M1's tests read
  #   names kept out of empty_lists for that fixture; the fixture name is the dst path's stem, for the .ndjson and its .jsonl twin alike
  def filter_connectors(node) -> None   # for the two scenarios, on every line of both files:
  #   keep only source == "claudeai" entries in init.mcp_servers and in every mcpServers;
  #   drop from init.tools every mcp__<server>__* name whose server was not kept;
  #   drop config.env and config.headers;
  #   rename every connector, the probe's own included, to "claude.ai Connector-N" (N by first appearance, one map shared by the twin) everywhere its name occurs:
  #   mcp_servers, mcpServers, a control_request's serverName, a tool name's mcp__<server>__ prefix, and any string value equal to the name
  URL_KEYS = ("code_challenge", "state", "config.id", "config.url", "authUrl")
  #   the value, or each query value of a url, becomes REDACTED_<KEY> in upper case, no < or >; in authUrl and config.url the host becomes connector-N.invalid
  #   (N by first appearance of the host) and the path /redacted, the http(s) scheme kept, so browsableUrl still passes (a vendor's host or path names the connector)
  #   every config.args element → "REDACTED_ARG" (spec 10 item 6); home paths keep PATH_RE
  def bash_mode(cli: Cli, sleep=time.sleep) -> None            # spec 12.9 item 1 (the bash probes ran ad hoc): cli.send of <bash-input>echo bash-mode-probe && printf err >&2</bash-input> and
  #   <bash-stdout>bash-mode-probe\nerr</bash-stdout><bash-stderr></bash-stderr>, both shouldQuery False and client_composed True, back to back; sleep(3);
  #   cli.user("Reply with only the text the command printed to stdout"); until its result (the recorded bash-mode)
  def bash_mode_respond(cli: Cli, sleep=time.sleep, wait: float = 3.0) -> None   # <bash-input> quiet; sleep(wait); <bash-stdout> with client_composed True and no shouldQuery; until its result
  def bash_mode_respond_together(cli: Cli) -> None             # bash_mode_respond(cli, wait=0): the respond frames back to back (spec 12.6's unrecorded merge)
  def bash_mode_context(cli: Cli, sleep=time.sleep) -> None    # one frame, the content of transcripts/bash-mode.jsonl:5, shouldQuery False, client_composed True; sleep(3); bash_mode's prompt (spec 12.4's single context frame)
  # SCENARIOS gains "bash-mode", "bash-mode-respond", "bash-mode-respond-together", "bash-mode-context"
  ID_KEYS gains "submitUuid" (beside the transcript keys, :34-35)   # every fixture: an id placeholder instead of the <leaked-N> safety net (probes 3.6, transcripts/mcp-slash.jsonl:13)
  ```

- [ ] **Step 1: Write the failing tests** (`scripts/test_drive_cli.py` with its scripted `Cli` double; `scripts/test_redact_fixture.py` with inline JSON lines):

```python
def test_the_connector_scenario_reads_the_name_from_the_environment_and_never_writes_it(self)   # env set: connector_name([]) == (the env value, []); frames carry serverName == it; after redact no file holds it
def test_the_connector_scenario_takes_the_name_from_the_connector_flag(self)                     # connector_name(["--connector", "claude.ai X", "--foo"]) == ("claude.ai X", ["--foo"]); serverName "claude.ai X", the flag absent from claude's argv
def test_the_connector_scenario_fails_without_a_name(self)                                       # neither set: connector_name([]) raises SystemExit(2)
def test_the_connector_scenario_drops_safe_mode_and_adds_the_strict_flags(self)                  # argv lacks "--safe-mode", has "--strict-mcp-config" then "--mcp-config" then the empty config; mcp-connectors-all lacks all three
def test_the_connector_scenario_sends_status_and_toggle_while_ping_streams(self)                 # between the ping's init and result: one mcp_status and one mcp_toggle(enabled False); after the result: mcp_toggle(enabled True)
def test_the_connector_scenario_stops_polling_once_the_status_changes(self)                      # a double that answers "connected" on the third mcp_status → exactly three polls
def test_connector_fixtures_keep_only_claudeai_entries(self)                                     # init.mcp_servers and mcpServers keep source == "claudeai"; init.tools loses mcp__echo__*; config.env and config.headers gone
def test_connectors_are_renamed_everywhere_including_host_frames_and_the_twin(self)             # "claude.ai Example" → "claude.ai Connector-1" in mcp_servers, mcpServers, a control_request's serverName and a tool name; the .jsonl twin gets the same N
def test_oauth_values_get_url_safe_placeholders(self)                                            # code_challenge=REDACTED_CODE_CHALLENGE, state=REDACTED_STATE; urllib.parse.urlsplit(authUrl) parses; no "<" in any placeholder
def test_other_fixtures_still_empty_the_init_lists(self)                                         # a fixture outside INIT_KEEP: mcp_servers emptied as today
def test_the_mcp_fixtures_keep_their_init_lists(self)                                            # dst mcp-status / mcp-tool-call keep mcp_servers; mcp-slash also keeps slash_commands, terminal_slash_commands and commands
def test_submit_uuid_gets_an_id_placeholder(self)                                                # a system/local_command line's submitUuid → an ID_KEYS placeholder, never <leaked-N>, no warning
def test_config_args_are_redacted(self)                                                          # config.args ["-y", "/home/x/token=abc"] → ["REDACTED_ARG", "REDACTED_ARG"]
def test_the_bash_scenarios_send_the_probe_frames(self)                                         # each scenario's user frames in order: the content strings above, shouldQuery False on the quiet ones and absent on the respond output, client_composed True; together: no sleep between
def test_connector_urls_get_a_placeholder_host(self)                                             # authUrl and config.url on a vendor host → https://connector-1.invalid/redacted?…; browsable (http(s), a host); no vendor text left
```

- [ ] **Step 2: Run** `cd /home/user/tandem/scripts && python3 -m unittest` → every new test fails (`AttributeError`, `KeyError`, `AssertionError` or `SystemExit`) except `test_other_fixtures_still_empty_the_init_lists`, which pins today's behaviour.
- [ ] **Step 3: Implement** the Interfaces block; `record-scenario.sh:13` lists the four bash scenarios and `mcp-connectors | mcp-connectors-all` with the note `(needs TANDEM_PROBE_CONNECTOR="claude.ai <Name>"; the redactor renames the connector)`.
- [ ] **Step 4: Run** `cd /home/user/tandem/scripts && python3 -m unittest` → PASS (the existing tests too).
- [ ] **Step 5: Commit** `git add scripts && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "test: connector and bash-mode recording scenarios, and the redactor's keep-list for the connectors"`

---

### Task M6: docs-and-verify

**Depends on:** M5, M5r, M7b (Sonnet for the docs; the checks as listed; the whole-branch review after it on Fable).

**Files:**
- Modify: `docs/protocol.md` (4.1, 4.2, 4.3, 4.4, 4.5, 5.1, 5.2, 6.1, 6.2, 10, 11.2)
- Modify: `CLAUDE.md` (`## Commands`: the fake-CLI line and the test-hooks line; a `record-scenario.sh` line)
- Modify: `docs/design.md` (a milestone note; the `ui/` line of the architecture block)
- Modify: `docs/superpowers/research/2026-10-21-mcp-probes.md` section 3.6
- Test: none new; every suite runs.

**Interfaces:**
- Consumes: everything above; no code change.
- Produces: the docs lines below, each wire claim marked recorded (`fixture:line`), docs only, or unrecorded, as the spec does.

- [ ] **Step 1: Write `docs/protocol.md`:**
  - 4.1: an `mcp_*` subtype on a CLI that does not know it answers `{"subtype":"error","error":"Unknown subtype"}` or nothing (unrecorded for these subtypes); Tandem shows the text, or its timeout.
  - 4.2: the initialize answer's `commands[] {name, description, argumentHint, builtin?, aliases?}` (`mcp-slash:1`, 73 entries; `mcp-status:1` has `[]`, emptied by the redactor), decoded as `InitializeResponse`; project entries omit `builtin` and `aliases`.
  - 4.3: `can_use_tool` gains `mcp_server {name, source}` and `server_policy_ask_only` (recorded, `mcp-tool-call:35`), decoded as `InboundRequest.CanUseTool.mcpServer` / `serverPolicyAskOnly`.
  - 4.4: a sub-heading `MCP requests` with the four frames of probes 3.2 and their answers:
    - `mcp_status` → `{"mcpServers":[…]}` (`mcp-status:18`: the entry shape with `config`, `serverInfo`, `tools`, `error`; `:2` before the first turn, `pending`);
    - `mcp_toggle {serverName, enabled}` → bare success (`:19`) or `Server not found: <name>` (`:38`); the next `init` agrees (`:23`);
    - `mcp_reconnect {serverName}` → bare success (unrecorded), the connection error (`:36`) or `Server status: needs-auth` (`:37`);
    - `mcp_authenticate {serverName}` → `{authUrl, requiresUserAction, callbackExpected, redirectScheme, state, callbackPort}` at once, no browser, no `system` line (`:40`); the callback completing is unrecorded;
    - `mcp_set_servers` decoded only (`:42`, `:44`); Tandem's timeouts 15 s / 30 s; whether the CLI answers mid-turn is unrecorded (spec 11 item 13).
  - 4.5: `elicitation` is still answered with the `Unsupported request: elicitation` error, and now adds the WARNING notice `An MCP server asked for input Tandem cannot show yet.` (docs only, `sdk.d.ts` 3976-3999).
  - 5.1: `init.mcp_servers[] {name, status, source}` (thin; `mcp-status:6`), `slash_commands[]`, `terminal_slash_commands[]` (`mcp-slash:4`); which statuses are recorded (`connected`, `failed`, `needs-auth`, `disabled`, `pending`).
  - 5.2: `system/local_command_output` (listed there) was **not** used for `/mcp`, `/model` or `/help`; the reply came as the assistant-line keys of 6.1 (probes 2.8; `mcp-slash:5`, `:11`, `:17`); the subtype stays docs only.
  - 6.1: an assistant line with `local_command_run {command, args}`, `local_command_outcome {kind}`, `local_command_source` (`<local-command-stdout>…</local-command-stdout>`) and `message.model "<synthetic>"` (`mcp-slash:5`, `:17`) is a local command's reply, shown as a notice; its `result` carries `local_command` and `num_turns: 0` (`:6`); `tool_use_meta[] {id, display_name, server_display_name, tool_name}` (`mcp-tool-call:34`).
  - 6.2: an MCP tool's result: the `tool_result` block plus the user line's `tool_use_result` with `structuredContent` and `_meta` (`mcp-tool-call:39`); a denied MCP call's `tool_result` with `is_error` and the `permission-rule` meta (`:62`).
  - 10: three rows for `mcp-status`, `mcp-slash`, `mcp-tool-call` (what each records, its scenario, its twin, the `:40` placeholders).
  - 11.2: the `system/local_command` line shapes (`transcripts/mcp-slash.jsonl:5`, `:13`, `:14`) and how Tandem reads them (a notice; the bare `submitUuid` line is the typed command).
  - Check every citation: for each `name:line` written above, `sed -n '<line>p' src/test/resources/fixtures/<name>.ndjson | grep -c '<the key the sentence names>'` → 1 (a `0` means the sentence or the line is wrong; fix the sentence, never the fixture).
- [ ] **Step 2: Write `CLAUDE.md`** (the `## Commands` list), three lines:
  - the fake-CLI line gains `TANDEM_FAKE_AUTH_DONE_MS` (the sign-in flips the server to connected after that many ms) and `TANDEM_FAKE_EXTRA_NEEDS_AUTH` (a second needs-auth server under that name), and the sentence `It answers initialize and every mcp_* request inline from the fixture's recorded answers; a fixture whose control responses carry mcpServers serves them from a server table, and any other answers mcp_* with Unknown subtype.`;
  - the test-hooks line gains `tandem.browserOpener=log` (writes `{"type":"__browse__","url":…}` to the fake's log instead of opening a browser);
  - a line `scripts/record-scenario.sh mcp-connectors` / `mcp-connectors-all` records the claude.ai connector shapes on the owner's machine (`TANDEM_PROBE_CONNECTOR` names the connector; the redactor renames it).
- [ ] **Step 3: Write `docs/design.md`**: one milestone paragraph naming the MCP panel and badge, the browser sign-in (the CLI does the OAuth; Tandem opens the link), the palette, the local-command notices and the bash mode (a `!` line runs in the login shell, its output reaches the CLI as the terminal's shell escape), and what stays docs only until spec 11 item 1's recording (connectors, the OAuth completion, `mcp_*` mid-turn); the architecture block's `ui/` line mentions the sheet, the palette and the shell row, the `session/` line `ShellRunner`.
- [ ] **Step 4: Write the probe report's 3.6**: each of its items ends with `(done: M<n>)`, `(waits for the recording of 11 item 1)` or `(not in this plan: spec 11 item 10)` (the probe-server MCP scenario), several joined with `; ` when an item is partly done (the redaction rules: `(done: M5r)`; the Gradle note: `(done: M1)`); nothing else in the report changes.
- [ ] **Step 5: Run** the string checks:
  - `grep -rn "Claude Code" src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui` → no hit;
  - `git grep -nFf /home/user/tandem/scratch/personal-names.txt -- src scripts CLAUDE.md docs ':(exclude)docs/superpowers/plans'` → no hit (the probe report and the specs included), and `git grep -nFf /home/user/tandem/scratch/personal-names.txt -- docs/superpowers/plans | grep -v -- '--author='` → no hit (the plans' commit lines name the commit identity on purpose); the list lives only in that gitignored file (Global Constraints), so neither this plan nor a check writes a name into a tracked file; the package `dev.daarkolabs.tandem` is not personal data and is not in the list; a hit in a file this plan does not touch goes to the owner, not into an edit;
  - every string of spec 3.6, with its placeholders (`<name>`, `<error>`, `<elapsed>`, `<version>`, `<command>`, `<alias>`, `C`, `T`, `N`) cut back to its fixed part, is found by `grep -rF -- "<fixed part>" src/main/kotlin/dev/daarkolabs/tandem` → ≥ 1 hit each; a miss is a typo in `chat/` or `ui/` to fix (hard rule 5 texts must match the spec the owner reviewed).
- [ ] **Step 6: Run** the suites (show any failure's output; never narrow a sweep):
  - `./gradlew check --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (Kover ≥ 90 % in `protocol/`, `session/`, `chat/`);
  - `./gradlew verifyPlugin --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → no compatibility problem against any supported release;
  - `xvfb-run ./gradlew integrationTest --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts` → PASS (the four MCP tests, M7b's bash-mode test and the Shift+Tab update included);
  - `cd /home/user/tandem/scripts && python3 -m unittest` → PASS.
- [ ] **Step 7: Commit** `git add docs/protocol.md docs/design.md CLAUDE.md docs/superpowers/research/2026-10-21-mcp-probes.md && git commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "docs: MCP control requests, local commands and the connector recording"`
- [ ] **Step 8: Pull request** `feat/mcp-controls` → `feat/chat-redesign` (the stacked base; see Deviations), title `feat: MCP servers panel and slash-command palette`, no attribution line. Write the body below to `/home/user/tandem/scratch/pr-body.md` and show it to the owner (CLAUDE.md: ask before anything outward-facing). Once they agree: `git push -u origin feat/mcp-controls`, then `gh pr create --base feat/chat-redesign --head feat/mcp-controls --title "feat: MCP servers panel and slash-command palette" --body-file /home/user/tandem/scratch/pr-body.md`. Body:
  - Context: the owner's request ("let me control connectors and mcp from here please like interactive stuff on the cli") and the three screenshots, one line each and marked local-only (their folder is gitignored, so GitHub cannot show a link; attaching them to the PR by hand is the owner's call): a typed `/mcp` answered by one line of text and a `Worked for` footer; the model saying two claude.ai connectors need authorization (no connector named); the desktop app's `+` menu with `Slash commands` and the `Type / for commands` placeholder.
  - Approach: the ten slices M1–M7 (M2 in three: M2a, M2b, M2c; M7 in two, M7a and M7b, the bash mode of spec 12, from the owner's second request and `tandem-05`) in one paragraph; what is recorded and what is docs only (connectors, the OAuth completion, `mcp_*` mid-turn; for bash mode the merge of the respond frames, the TUI's wording for a stopped or failing command, Windows).
  - Testing: the suites of Step 6 with their counts, and `./gradlew koverLog --init-script /home/user/tandem/scratch/central-mirror.init.gradle.kts`'s three percentages (`protocol/`, `session/`, `chat/`).
  - The `runIde` checklist for the owner, spec 9.6 verbatim, one line each: badge legibility beside the title at 350 px; the sheet's height and scrolling with 41 servers; the `Waiting for the browser…` tick; the palette's position with 8 rows, Up/Down/Enter/Tab/Esc, typing never lost to the popup, one click on `Send` with the palette open sends; `/mcp` then Esc closes the panel and does not stop a running turn; `/model` opens the picker; a local command's notice has no footer; dark and light LaF; and spec 11 item 5: on macOS a composing `/` (IME) does not open and close the popup per keystroke.
  - The exact recording to ask for (spec 11 item 1): on the Mac, with the CLI signed in and one connector still `needs-auth`, run `TANDEM_PROBE_CONNECTOR="claude.ai <Name>" scripts/record-scenario.sh mcp-connectors`, open the printed link and finish the sign-in within 3 minutes; if its `mcp_status` lists no `source == "claudeai"` entry, run `scripts/record-scenario.sh mcp-connectors-all`; read the diff (no server but connectors, no real name, url, id, args, token, header, env value or home path) before committing it.
  - Verified and not: say plainly that every wire claim about the probe servers is recorded and tested, and that the connectors path, the OAuth completion, `Reconnect` after a terminal login, `mcp_toggle` on a failed or needs-auth server and the `mcp_*` answers mid-turn are unverified until that recording.
  - What that recording settles, one line each: the connector entry shape (`config.type == "claudeai-proxy"`, `id`, `url`); whether `init.mcp_servers` lists connectors; whether `needs-auth` carries an `error`; the connector's `authUrl` and that it passes `browsableUrl`; whether `mcp_authenticate` without `redirectUri` is accepted; whether toggle and reconnect work on a connector; whether the `mcp_*` requests are answered mid-turn; whether the status turns `connected` by itself after the callback (if not, M4 gains an automatic `mcp_reconnect` after the browser returns).
  Then the whole-branch review on Fable (spec 10).

---

## Self-review (done while writing)

- Spec coverage: 12 → M7a (12.4's protocol and session types, the runner, the reducer, handle and reader rules, the fake's rule (g); 12.5 the flows through `SessionManagerTest`; 12.6's runner and session edge cases, Review Focus 6, 7, 8, 10) + M7b (12.1–12.3 the row, the composer and the keys; 12.4's chat, ui and ide parts and every turn-boundary check; Review Focus 9; the two IDE tests); 12.8 every named test, each in one of the two; 12.9 stays open (item 1's scenarios in M5r); 3.1 → M4 (header, badge, focus); 3.2 → M3 (model) + M4 (sheet, controller); 3.3 → M2c (flow, notices) + M4 (captions, hints); 3.4 → M3 (palette, pick, intercept) + M5 (view, keys, routing, `+` row, placeholder); 3.5 → M1 (decode) + M2b (reducer, reader) + M3 (row, link) + M5 (item); 3.6/3.7 → the Exact values line and the tasks' semantics; 4.1 → M1; 4.2 → M2a, M2b, M2c; 4.3 → M3; 4.4 → M4, M5; 9.1 → M1; 9.2 → M2a, M2b, M2c; 9.3 → M3; 9.4 → M4, M5; 9.5 → M4, M5; 9.6 → M6's PR notes; 10 item 6 → M5r; 10 item 7 → M6; non-goals (2) stay out, except the elicitation notice (M2a) and the decode-only `mcp_set_servers` (M1).
- Step scan: every test step names its tests and assertions; every code step names its signatures; the only bodies are `request()` (M2a) and the controller rules, which no signature determines.
- Type consistency: `McpControls`, `McpResult`, `McpError`, `McpAuthWait`, `McpAuthOutcome`, `McpState`, `McpPanelModel`, `McpRowModel.key`, `SlashPalette`, `PaletteOutcome`, `FooterMenu`, `ComposerAction` entries and `composerKey`'s parameter order (`…, hasAttachments, paletteOpen, panelOpen, clipboardHasImage`) are the same names in M2a–M5; M7a's and M7b's `BashEscape`, `BashLine`, `ShellEnd` (with `Failed`), `ShellResult`, `ShellRunner`, `ChatEntry.Shell`, the six `Shell*` events (with `ShellReplyDropped`), `StopTarget`, `SessionHandle.startShell`, `Outbound`, `ShellRow`, `shellCommand` and `respondToShellOutput` are spec 12.4's names.
- Review Focus: ten lines, five per feature (MCP and palette 1–5, bash mode 6–10), each pinned to a named test in M2b, M3, M5, M7a or M7b; the edge cases that left it are in their tasks' test lines.
- Proportion: the plan is about a quarter of the spec's length; code blocks are signatures, names and assertions.

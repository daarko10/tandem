# Desktop Parity Implementation Plan: Context Breakdown, the `+` Menu, the /usage Card and the Second Mac Test

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the side chat, as the desktop app does but sized for a tool window: the context ring opens a breakdown of the context window by category with its MCP tools, memory files and skills; `/usage` (and `/cost`) draws a card with this session's cost, times and per-model tokens and what has used the plan's limits on this machine; and `+` opens a paged menu with Add files or photos, Add folder (the CLI's `--add-dir` on the next spawn), Slash commands, Connectors (the MCP panel) and the session's plugins with their commands. Every number comes from the CLI's own output.

**Goal (spec 12 to 16, the owner's second Mac test):** while Claude works, Enter sends a message the CLI reads at its next step and Ctrl+Enter (⌘↵ on macOS) queues one for after the turn, each a pending bubble until the CLI starts it, with Edit and Cancel on a queued one, Stop withdrawing what waits, and folded messages kept on reload; every ended turn's reply can be forked, cut there with `--resume-session-at`; Shift+Tab in the composer cycles the permission mode through a Keymap action and Tab moves the focus forward; the working row's spark breathes as a drawn shape and a light band sweeps its label, both still under `Reduce motion`; the /usage card shows each model's share of the cost, web searches, the request count and every tip, and the ring leads to it. The owner's words: "I can't queue messages, it doesn't let me [send] anything till the steps are over ... instead of allowing me to send it and then it queues it like in the cli/desktop app", "The fork should be allowed from everywhere also, otherwise we are over promising, the Claude code desktop app allows for forking all throughout", "I want a keybind to change modes while in a chat", "There is no cool animation like on Claude code for the thinking", "The usage doesn't do anything and I want to show more in detail".

**Architecture:** D1 types the three answers (`get_context_usage`, `get_usage`, `init.plugins` / `additional_directories`) in `protocol/` with a fixture test per shape, and `SessionConfig` learns `--add-dir`. D2a gives `ClaudeSession` the two requests and the `/usage` fetch, the reducer the usage card's part states and the breakdown, `SessionManager` the coalesced `contextBreakdown` and `retryUsage`, and the fake CLI its inline answers; D2b gives `SessionHandle` its added folders, the respawn when idle and the init confirmation. D3 adds the pure models in `chat/` (`contextPopover`, `usageCard`, `pluginMenu`); D4 draws the popover on a `ContextBreakdownController`, D5 the card, D6a the paged `+` menu, D6b Add folder and its pills; D0 records `--add-dir` with a real CLI first, and D7 writes the docs and runs every suite.

**Architecture (spec 12 to 16):** D8 scripts the committed queue and fork stories and records three more (a steer after the last tool round, a withdrawn steer, a fork while the original's process is live); D9 types `command_lifecycle`, the queue frames and their replies, and `SessionConfig.resumeSessionAt`; D10 tracks messages sent while busy in the session layer (pending and held sends, withdraw, Stop with `cancel_queued`, returned messages, folded `queued_command` lines on reload, attention and idle) and teaches the fake rule (m); D12a gives the reducer a fork point per reply and `SessionManager.fork` a cut, D13a the mode cycle's locked pick and refused modes; D11 draws the queue (`QueuedRow`, the keys, Send beside Stop, Edit and Cancel), D12b the hover row's fork on every reply, D13b the `Cycle Permission Mode` action and Tab's forward move, D14 the drawn spark and the shimmer. Section 16 rides on D1 (`webSearches`), D3 (`costShare`, every tip, the request count), D4 (`Show session usage`) and D5 (the bars, the tips' disclosure), marked (spec 16) there; D0 also records a fork at a reply with `--add-dir` and D2b gives such a fork its parent's folders, marked (spec 13).

**Tech Stack:** Kotlin 2.4.20, IntelliJ Platform 2026.1.5 (Jewel 0.37.0, build 261.27258.48), kotlinx.serialization.json tree API, the platform's kotlinx.coroutines and kotlinx.collections.immutable (never bundled, hard rule 7), JUnit 5 (+ vintage for the JUnit 4 classes), IDE Starter + UI Driver, the Python fake CLI and recording scripts.

**Spec:** `docs/superpowers/specs/2026-10-22-desktop-parity-design.md` (sections 3, 4, 9 and 10, and 12 to 16 for the second Mac test, are the contract; 7 the edge cases; 11 the open items). Companions: `docs/superpowers/research/2026-10-22-desktop-parity-probes.md` (probes), `docs/superpowers/specs/2026-10-21-mcp-controls-design.md` (MCP: 3.2, 3.4, 3.5, 4.2), `docs/superpowers/specs/2026-10-21-chat-polish-design.md` (polish: 2.3, 2.7), `docs/superpowers/plans/2026-10-21-mcp-controls.md` (the request path and fake rules (a)–(g) this plan extends), `docs/superpowers/specs/2026-10-19-chat-redesign-design.md` (chat-redesign: 4.9 the working row, 4.18 the keyboard map); the probes' section 5 (the `fork-at` and `queue-*` fixtures).

**Base:** implementation of this round starts later, from `feat/mcp-controls` once it has landed and has merged `feat/chat-redesign`'s 0.1.1 fixes (`dc3061f..8317665`, merged with `main` at `31b5d3e`: `ComposerImages.chooseImagesLater`, `FooterControls.focusLeft`, the `Reduce motion` setting and `LocalReducedMotion`), not from `522c3e2`. Before D1 the orchestrator moves `feat/desktop-parity` onto that commit (it rebases this branch's own commits: docs, fixtures, the redactor's queue keys and the decode test's transcript list; no product code), and every task assumes those pieces are there: a task that finds `ComposerImages.chooseImagesLater`, `FooterControls.focusLeft`, `TandemSettings.reduceMotion` or `LocalReducedMotion` missing stops and reports. D14 builds on `LocalReducedMotion`. The spec's sections 12 to 16 (slices D8 to D14) are tasks D8 to D14 below, D12 and D13 each split into a session task (a) and a chat and ui task (b) (deviation 14).

**Dependencies and tiers:** two recording tasks, D8 → D0 (both edit `scripts/drive_cli.py`, and D0's fork at a reply uses D8's `fork_at_argv` and `reply_uuid`), run beside the Kotlin chain D1 → D9 → D2a → D10 → D12a → D13a → D3 → D4 → D5 → D6a → D11 → D12b → D13b → D14 → D2b → D6b → D7 → whole-branch review: protocol and session first, then chat, then ui (the tasks with IDE Starter tests: D4, D5, D6a, D11, D12b, D13b, D14 (optional), D6b), then docs. D0 and D8 need a real signed-in `claude` and may take a while, so the chain waits only where it must: D11 starts once D8 has committed its `queue-cancel-steer` outcome, D12b once D8 has committed its `fork-at-live` outcome, D2b and D6b once D0 has committed its outcome (they run last for that reason). D14 needs nothing from this round, so it moves to any point where the chain waits for a recording (two Gradle builds in one worktree only queue on its lock). Shared files fix the rest: D9 after D1 (`HostMessages`, `SessionConfig`); D10 after D2a (`Reducer`, `ChatState`, `ClaudeSession`); D12a and D13a after D10 (`Reducer`, `SessionHandle`, `SessionManager`); D11 after D6a (`PromptBar`, `ComposerKeys`); D12b after D11 (`ChatRowsBuilder`, `ChatPresenter`); D13b after D12b (`ComposerKeys`, `PromptBar`, `FooterControls`); D2b after D12a (its `fork()` copies folders to a fork at a reply too). D4 and D5 both edit `NoticeItem.kt` and `ChatPanel.kt`, and D6a, D11, D13b and D6b all edit `PromptBar.kt`, so they run in order. Tiers (user CLAUDE.md, spec 10): D1, D2a, D2b, D3, D4, D5, D6a, D6b, D9, D10, D11, D12a, D12b, D13a, D13b, D14 and their per-task reviewers on Opus; D0 and D8 (recording tooling and fixtures) and D7's docs on Sonnet, as are small review fixes; a task that fails review twice moves to Fable; the whole-branch review after D7 runs on Fable.

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

## Global Constraints

- Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.
- CLAUDE.md hard rules, verbatim:
  1. **Never block the EDT.** No process I/O, file I/O, JSON parsing or waiting on the CLI from the UI thread. Use the platform coroutine scopes (`platform-coroutines-structured-concurrency` skill).
  2. **No Node, no Agent SDK at runtime.** We spawn the user's `claude` binary directly. Do not add a sidecar.
  3. **Never touch credentials.** Auth belongs to the CLI. Do not read `~/.claude/.credentials*`, keychains or API keys, and never offer a login flow.
  4. **Edits go through the IDE, not the disk.** Claude's built-in Edit/Write tools are disabled; our MCP edit tools apply changes with `WriteCommandAction` on the `Document`, so undo, unsaved buffers and local history work. Every edit shows a native diff before it lands, unless the user picked auto-accept.
  5. **Never fail silently.** If the CLI falls back to another model, rejects a model, retries, or errors, the user sees it in the chat. Compare the model in the `system/init` event (and fallback events) with what the user picked.
  6. **Every protocol change gets a fixture test.** Recorded transcripts live in `src/test/resources/fixtures/`. Unknown message types must parse to `Unknown(raw)`, never throw. `synthetic-long-reply.ndjson` is generated by `scripts/make_long_reply.py` and is the only unrecorded fixture.
  7. **Do not bundle kotlinx.coroutines or the Kotlin stdlib.** The platform provides both.
  8. **Do not call the product "Claude Code"**, in the plugin name, ID or UI. Use "Tandem" and say it works with Claude Code.
- Hard rule 3 for this round (spec 1): every number comes from the CLI's own output (a control response, a message, a local command's text, or a transcript the CLI wrote); Tandem never calls Anthropic's API and never computes an account limit; a part the CLI cannot give over stream-json is left out or deferred (spec 2.2), never computed.
- CLAUDE.md conventions, verbatim: "Validate everything that comes from the CLI; it is an external process that changes weekly." and "Never log prompt contents, file contents or secrets. Log message types and ids." Spec 4.2's rule: subtypes, request ids, counts and uuids only; never memory-file paths, tool, skill, server or plugin names, recommendation texts, folder paths or costs.
- Layers (CLAUDE.md Layout): `protocol/` imports nothing from IntelliJ; `session/` only the JDK, kotlinx.coroutines, kotlinx.serialization.json, kotlinx.collections.immutable and `com.intellij.openapi.diagnostic`; `chat/` only the JDK, kotlinx.collections.immutable, kotlinx.serialization.json and `session/`/`protocol/` types (no Compose, Jewel or IntelliJ). `koverVerify` holds each of the three at ≥ 90 % lines.
- Where the work happens: the git worktree `/tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/main-worktree` (called WT below) on `feat/desktop-parity`, cut from `origin/feat/mcp-controls` at `522c3e2` and moved onto the landed `feat/mcp-controls` (with 0.1.1's fixes) before D1 (**Base** above). Every command runs from WT. `/home/user/tandem` is another branch in progress: never write there, never create another worktree, and read nothing from it during the round: before D0 and D1 the orchestrator copies, once, `/home/user/tandem/scratch/central-mirror.init.gradle.kts` (Gradle) and `/home/user/tandem/scratch/personal-names.txt` (the D7 check) into `WT/scratch/` (gitignored), and every command below uses those copies, so a change or cleanup in that tree mid-round breaks no task.
- Gradle: every command carries `--init-script scratch/central-mirror.init.gradle.kts` (the copy in `WT/scratch/`, relative to WT, where every command runs). One class: `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.<pkg>.<Class>'`; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` adds `koverVerify`; IDE tests only in D4, D5, D6a, D6b, D11, D12b, D13b, D14 (optional) and D7: `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` (1.5–3 min each). Every Kotlin task ends with `check` green and one commit. If a host is refused, stop and name it.
- Jewel 0.37.0 as bundled in 2026.1.5: `PopupMenu(onDismissRequest, popupPositionProvider, modifier, …) { MenuScope }` with `selectableItem`, `passiveItem`, `separator`; `SegmentedControl(buttons: List<SegmentedControlButtonData>, …)`; `VerticallyScrollableContainer`; `InlineWarningBanner`; `AllIconsKeys.Actions.Attach`, `Webreferences.Server`, `General.Close`, and `AllIconsKeys.Nodes.Folder` and `Nodes.Plugin` (both checked with `javap` on the 2026.1.5 `intellij.platform.jewel.ui.jar` while writing this plan: spec 11 item 7 is settled). Check any other API with `javap` before use. Theme tokens only, except `TandemColors`.
- Fixtures: `name:line` is the 1-based line of `src/test/resources/fixtures/<name>.ndjson`; `transcripts/<name>.jsonl` is its on-disk twin. In tests, `at(name, n)` (`FixtureDecodeTest`) and `fixtureLine(name, n)` (`chat/Replay.kt`) decode one line; `ControlPayloads.x((at(name, n) as CliMessage.ControlResponse).payload)` decodes an answer; `b17` below is `ControlPayloads.contextUsage(…parity-context:17…)!!`, `u16`/`u17` are `ControlPayloads.usage(…parity-usage:16/17…)!!`. The redactor made every path `<path>` and every id a placeholder (`<uuid-28>`).
- Test names are the spec's (9.1–9.4, and 9.7's where it names one; the plan names the rest), verbatim under the Kotlin name rule: Kotlin/JVM rejects `.` `;` `[` `]` `/` `<` `>` `:` `\` in backtick names, so the Kotlin name replaces `: ` with ` - `, drops a leading `/` (`/usage` → `usage`), drops `<`/`>` and `"`, turns `; ` into `, `, and a `// spec: <verbatim name>` comment sits above the test. Names below are already rewritten. New classes use JUnit 5; additions to the JUnit 4 classes (`FixtureDecodeTest`, `HostMessagesTest`, `RobustnessTest`, `ReducerTest`, `ClaudeSessionTest`) keep JUnit 4. No `kotlinx-coroutines-test` on the classpath: coroutine tests run under `runBlocking { withTimeout(10_000) { … } }` with an injected dispatcher, as `McpPanelControllerTest` does.
- Exact values (spec 3, 4): timeouts `get_context_usage` 30 s, `get_usage` with `skip_behaviors` 15 s and without 60 s (`ClaudeSession.mcpRequest` seconds, scaled by `mcpMillisPerSecond` in tests); frames of probes 3.2: `{"type":"control_request","request_id":"c1","request":{"subtype":"get_context_usage"}}`, `{…"request_id":"u1","request":{"subtype":"get_usage","skip_behaviors":true}}`, `{…"request_id":"u2","request":{"subtype":"get_usage"}}`; argv `--add-dir <dir>` per folder after `--resume <id>` / `--fork-session` / `--session-id <id>`; popover width `min(360 dp, chat column − 32 dp)`, at least 280 dp, height at most 60 % of the tool window, 8 dp above the ring (`AboveAnchor(alignEnd = true)`); bar 4 dp, track `dim` at 30 % alpha, a non-zero segment at least 1 px; swatch 8 dp; at most 100 items per group then `and N more`; card frame 6 dp radius, 12 dp padding; share bar 4 dp; pill `×` 16 dp; every clickable row ≥ 24 dp from its top-left (the Driver clicks at `Point(10,10)`); plugin version check `^[0-9A-Za-z][0-9A-Za-z.+-]{0,31}$`; every user-facing string is spec 3.6's, verbatim. Context tones (`TandemColors.contextTone`, light / dark): `MESSAGES` `0xFF3574F0` / `0xFF548AF7`, `MCP_TOOLS` `0xFFE06C4C` / `0xFFF08A6C`, `MEMORY_FILES` `0xFF4FA562` / `0xFF5FB865`, `SYSTEM_PROMPT` `0xFFD9A343` / `0xFFE5B85C`; `NEUTRAL` is the chat palette's `dim`, `FREE` the track (hollow swatch).
- Fixtures of spec 12 and 13 (D8 to D12b): in `queue-fold`, `queue-later` and `queue-cancel` the turn's prompt and the message written while it runs are both `<command_uuid-N>` placeholders: `queue-fold` P `<command_uuid-4>`, F `<command_uuid-25>`; `queue-later` P `<command_uuid-4>`, F `<command_uuid-25>`; `queue-cancel` P1 `<command_uuid-4>`, F1 `<command_uuid-29>`, P2 `<command_uuid-47>`, F2 `<command_uuid-68>`; F's text in `queue-fold` and `queue-later` is `Also say the word KIWI at the end.` (`transcripts/queue-fold.jsonl:23`, `transcripts/queue-later.jsonl:32`), and F1 and F2 never reach a transcript, so tests give them their own text. In `fork-at`, A is `<session_id-2>` (the origin, inits `:6`, `:19`, `:45`), B `<session_id-70>` (the fork, init `:60`), and the cut `<uuid-14>` is the first turn's reply (`fork-at:11`, `transcripts/fork-at-origin.jsonl:20`, `transcripts/fork-at.jsonl:17`). Probe runs (spec 12, 13: **probe run**) are not fixtures: a test never cites one.
- Exact values (spec 12 to 16): frames `{"type":"user","session_id":"","message":{…},"parent_tool_use_id":null,"uuid":"u-1","priority":"later"}` (`HostMessagesTest`'s `user text message` frame with `"priority":"later"` after `uuid`), `{"type":"control_request","request_id":"a1","request":{"subtype":"interrupt","cancel_queued":true}}`, `{"type":"control_request","request_id":"c1","request":{"subtype":"cancel_async_message","message_uuid":"u-1"}}`; `cancel_async_message` 5 s (`mcpRequest` seconds); the capabilities `msg_lifecycle_v1` and `interrupt_cancel_queued_v1`; `<first line>` cut at 60 characters with `…`; the fork argv tail `--resume <A> --fork-session --session-id <B> --resume-session-at <uuid>`, then `--add-dir <dir>` per folder; action id `Tandem.CyclePermissionMode`, `first-keystroke="shift TAB"` in `$default`; the spark: six frames over 2000 ms, still frame 4 (`✻`), a 16 × 16 dp leaf, radius 7 dp, spokes from 0.25 of it, from −90°, `delay(50)` then `withFrameMillis`; the shimmer: first sweep at 600 ms, 900 ms eased `(1 − cos πp) / 2`, one every 3000 ms, band half-width 32 dp, base `text.info`, highlight `JewelTheme.globalColors.text.normal`; the usage card: a 4 dp cost-share bar, `<1%` above 0 and under 0.5 %, `>99%` from 99.5 %, three tips before the disclosure. Strings, verbatim from the spec (hard rule 8: none says "Claude Code"): 12.1 `Sent · Claude reads it at its next step`, `Queued · sends when Claude is done`, `Waits for the shell command's output to go out`, `Withdrawing…`, `Sent while Claude worked`, `↵ to send, ⌘↵ to queue for later`, `Enter to send, Ctrl+Enter to queue for later`, `Claude is working · Esc to stop`, `Type / for commands`, `Send now · ⌘↵ queues it for after this turn`, `Send now · Ctrl+Enter queues it for after this turn`; 12.2 `Claude is still starting: send again in a moment.`, `This claude CLI does not take messages while it works: wait for the reply, or press Esc to stop.`; 12.3 `Edit queued message`, `Cancel queued message`, `Claude had already taken "<first line>"; it was not withdrawn.`, `Not sent: "<first line>". It is back in the composer.`; 12.4 `Shell commands run when Claude is done: wait for the reply, or press Esc to stop.`; 12.5 `Not sent: "<first line>": the CLI ended before reading it. It is back in the composer.`; 13.1 `Fork from here`, `Starts a new session from this point. This one stays as it is.`, `Wait for the reply to finish`, `This reply cannot be forked: the CLI sent no id for it`, `This session has no transcript to fork yet`; 14.1 `Cycle Permission Mode`, `Switch the Tandem chat to the next permission mode: Manual, Accept edits, Plan, then Auto when the model supports it`, `<shortcut> to cycle`, `Permission mode <label>`; 15.1 `The spark and the shimmer beside Claude's working line stand still.`; 16.2 `<pct>% of cost`, `<1% of cost`, `>99% of cost`, `Web searches <n>`, `<n> requests`, `Tip`, `Tips`, `Show <n> more`, `Show fewer`, `Show session usage`, `Show session usage (after this turn)`.
- Personal data: the owner's reference frames come from a work machine; no name, path, company, URL, account, cost or number from them goes into a committed file (this plan, code, tests, docs, commit messages). The UI is described generically, the values in tests are the committed fixtures'.
- Commits: Conventional Commits, staged by path, no trailers, no AI attribution: `git -c user.name="Dago Kogos" -c user.email="34120174+daarko10@users.noreply.github.com" commit --author="Dago Kogos <34120174+daarko10@users.noreply.github.com>" -m "<type>: <subject>"` (written `COMMIT -m …` in the steps below).
- Deviations from the spec (not contract breaks):
  1. Spec 9.4 says `runTest`; the controller test runs under `runBlocking` with the dispatcher injected (no `kotlinx-coroutines-test` beside the platform's fork).
  2. Spec 9.1's `SessionConfigTest` row goes into `ClaudeSessionTest` beside `argv for a new session and for a resumed fork` (no `SessionConfigTest` class exists); spec 9.2's `SessionHandleTest` folder tests go into `SessionManagerTest`, since they need spawns through the fake and its `__spawn__` argv.
  3. Spec 10's D2 is two tasks (D2a requests, reducer and fake; D2b folders) and D6 two (D6a the menu; D6b Add folder), each green with one commit and its own reviewer, so that D0's recording holds back only the folder work.
  4. `NoticeRow` gains `sourceUuid` beside spec 4.3's `usage` and `markdown` (the card's `Retry` needs the uuid); `ContextBreakdownController` takes `home` and `projectRoot` (for `displayPath`) and names its state flow `fetch` (its constructor already has the session's `status`); `chat/` gains `displayPath(…)`, the loading constants `USAGE_LOADING`, `HISTORY_LOADING`, `WINDOW_EMPTY`, and `popoverStatus(…)` with `BREAKDOWN_LOADING`, `BREAKDOWN_UPDATING`, `BREAKDOWN_STOPPED` (the popover's status line), so the strings and the loading, error and stopped states are tested without Compose.
  5. One predicate decides the card and the fetch: `CliMessage.Assistant.isUsageLine()` = `localCommand?.command == "usage" && parentToolUseId == null && uuid != null`, used by the reducer and by `ClaudeSession.readLoop`, so no card waits for a fetch that never starts.
  6. A usage part still `Loading` turns `Failed(Ended)` on `SessionEvent.Ended` and in `settled()` (a stop by the cap or a folder respawn), so it reads `The session has ended.` (spec 3.2, 7) even when the answer can no longer reach the handle.
  7. `ChatRowsBuilder` reopens a cached (frozen) turn whose usage notice changed instance, so a card in an older turn still updates (spec 8 "recomposed only when its part states change" needs it; Review Focus 1).
  8. Not a deviation any more but a spec change: spec 3.3 and 3.5 now give the keyboard of the popover and the Plugins pages (Enter, Space or a click toggles a group or opens a plugin's page; in the `+` menu → on page 1 opens `Plugins`, its only `›` row, and does nothing elsewhere, ← goes back one page everywhere; in the popover → and ← are not bound), with Jewel's reason: its menu does not expose its keyboard selection to the AWT key dispatch the footer menus use, so an arrow key cannot know the selected row. Reviewers check the keys against spec 3.5.
  9. Fake rule (j) keys on the fixture name `parity-*` (their control responses answer requests the host sends out of the recorded order); a new rule (l) rewrites init's `additional_directories` from the fake's own `--add-dir` arguments (minus `TANDEM_FAKE_SKIP_ADD_DIR`), so the confirmation of spec 3.4 is testable; rule (h) serves the first full answer, which is `parity-context:2` (the pre-turn one: `messages` 10 tokens, `totalTokens` 30909, eight categories, `mcpTools` empty, so its groups are `Memory files` and `Skills` alone).
  10. IDE test 2 sends a first prompt and then `/usage` (the fixture's first turn is a model reply); IDE test 4 runs on `plain-reply` twice with rule (l), not on D0's fixture.
  11. The folder checks live in `ide/FolderCheck.kt` (`checkFolder`): file-system checks of the IDE's chooser result; `TandemSessions` exposes `projectDirectory`.
  12. The fake's queue rule is (m), not spec 9.7's (l): this plan gave (l) to `--add-dir` (deviation 9, D2b). D10 writes (m) and one knob spec 9.7 does not name, `TANDEM_FAKE_QUEUED_DELAY_MS` (a sleep after a `queued` lifecycle line that bound a host frame): without it the fake replays a steer's `queued` and `started` lines within milliseconds and IDE test 1 could not see the pending bubble.
  13. D0's run gains a process (spec 10 slice 0, 13.1 "Added folders"): a fork at the first turn's reply with `--add-dir`, third of five, so the plain fork becomes the fourth process and the removal the fifth. `copy_transcript.py` then sees three session ids, and today it refuses more than two: it now copies the first id's transcript as the origin and the last's as the twin, and names the ids between on stderr without copying them. D0 runs after D8 and uses its `fork_at_argv` and `reply_uuid` (spec 10 runs them side by side, but both edit the same scripts).
  14. Spec 10's D12 and D13 are two tasks each (a: the session layer; b: chat and ui), as D2 and D6 are (deviation 3), so the session work sits in the chain's session block: D12a and D13a are additive and green on their own, and D12b removes `latestReply` and `NOT_LATEST` together with their only readers.
  15. Spec 9.7's `SessionConfigTest` row goes into `ClaudeSessionTest` (deviation 2's reason); its `SessionHandleTest` row `a fork at a reply passes its parent's folders after the fork tail` goes into D2b's `SessionManagerTest` (it needs the fake's `__spawn__` argv, and `fork()` copies folders only from D2b on), marked (fork at) and dropped when D0's fork at a reply is **not listed**.
  16. `ChatState.cliCapabilities` is `Set<String>?`, null until the live process's `system/init` (spec 12.6 has `Set<String>`, empty by default), so the composer tells `Claude is still starting…` (no init yet) from `This claude CLI does not take messages while it works…` (an init without both capabilities), spec 12.2's two refusals.
  17. IDE test 4 (spec 9.7) presses Shift+Tab before the first prompt instead of holding the replies with `TANDEM_FAKE_GATE_FILE`, which holds only the CLI's own `control_request`s (the fake's docstring): before the first `user` frame the fake answers no `set_permission_mode` (its first `control_response` after `initialize`, `permission-mode:17`, is replayed only after the first turn), so both presses read Tandem's own picks.
  18. Pure helpers beyond spec 12.7 and 16.3, so their strings are tested without Compose: `chat/QueuedSends.kt` `sendNowTooltip(mac)` and `FOLDED_CAPTION`; `chat/UsageCardModel.kt` `TIPS_SHOWN`, `tipsTitle(count)`, `tipsToggle(count, open)`; `session/` `firstLine(text)` (spec 12.5's 60-character cut, used by the reducer's notices). `PaletteOutcome.Send` gains `later` and `pick` gains `midTurn` (spec 12.1: a palette pick that would send while busy sends it `later`).
  19. IDE test 5 (the spark; optional in spec 9.7) is written only if the 2026.1.5 UI Driver can capture one component's bounds (check its API with `javap` first, for a few minutes at most); otherwise D14 says so in its commit body and the 9.7 manual pass judges the motion.
  20. launch.md tactic 7 ("Protocol work is not done until `cli-contract.yml` covers it"), which spec 10 does not list: D7 adds `queue-later` and `fork-at` recordings to the contract job and a `ContractTest` case for them.
  21. Spec 12.5's IDE notification (beside attention) lives in `ide/SessionNotifier`: D10 changes it and adds a `SessionNotifierTest` case spec 9.7 does not name.

## Review Focus

1. A usage card in a turn that has since been cached (the user sent the next prompt while the up-to-60 s history scan ran, or pressed `Retry` on an older card) must still update, never show `Reading this machine's sessions…` forever → D3 `ChatRowsBuilderTest` `a usage card in an older turn updates when its answer arrives`, `a usage card in an earlier frozen turn updates and later turns keep their rows` (the case deviation 7 exists for: the card's turn is neither the last frozen turn nor ended by the card) and `Retry on an older card shows it loading again`.
2. A card part still loading when its process ends, is closed, or is stopped by the live-session cap (an idle session during the scan is the cap's first pick) or by a folder respawn must read `The session has ended.` → D2a `ReducerTest` `a process end turns loading usage parts into ended` and `a stop settles loading usage parts`; D2a `ClaudeSessionTest` `the history request is not sent once the process ended`.
3. A memory-file path that only shares a prefix with the home or the project root (`/home/al` and `/home/alex/…`, `/p/app` and `/p/app2/…`) must stay absolute, never read `~ex/…` or `2/…` → D3 `ContextBreakdownModelTest` `a path that only shares a prefix with home or the project stays absolute`.
4. A plugin whose name prefixes another's (`kit` and `kit-pro`) must list only its own commands, and a catalogue entry with nothing after the colon is in no page → D3 `PluginMenuTest` `a plugin whose name prefixes another's lists only its own commands`.
5. A folder added and removed again before the stop must not stop a process for nothing, and a folder path with spaces must stay one argv element → D2b `SessionManagerTest` `removing a folder before the stop leaves the process running`; D1 `ClaudeSessionTest` `added folders become --add-dir after --resume` (its `/b c` entry).
6. A message sent while Claude works is never lost and never dropped in silence: the process ending, a stop, the live-session cap, Stop with `cancel_queued` and a refused withdraw each give it back or say why, and a session with a message waiting is never idle → D10 `ReducerTest` `the process ending returns waiting messages with a WARNING`, `settled returns them with an INFO`, `a refused withdraw puts the message back and says so`, `queue-cancel - Stop's cancelled lifecycle returns F2 with one INFO notice and no further turn`; `ClaudeSessionTest` `the process ending drops held prompts and returns them`; `SessionHandleTest` `a session with a message in the CLI's queue is RUNNING and not idle, so stopIfIdle refuses`; D11 `ChatUiStateTest` `restoreReturned puts the texts ahead of the draft and re-attaches the images once`.
7. One CLI turn stays one visual turn with one footer; the prompt's own lifecycle lines and a `later` message's gap between `result` and `started` change nothing the user sees; a held shell escape never races a `later` message → D10 `the turn's own lifecycle lines change nothing`, `queue-fold, replayed with the host's sends - F waits, then joins the turn after the tool result`, `a held escape waits until no queued message remains`; D11 `ChatRowsBuilderTest` `a folded prompt has its caption and the turn one footer`, `queue-later has two footers`; `ComposerModelTest` `busy counts waiting messages`.
8. `--resume-session-at` never goes out without `--fork-session`, never blank, flag-like or outside the session's own history, and the original's transcript stays byte-identical → D9 `ClaudeSessionTest` `a fork at a reply ends with --resume-session-at`; D12a `SessionManagerTest` `fork at a reply resumes with --resume-session-at and starts from the history up to that turn`, `fork at a uuid not in the history gives null and starts nothing`.
9. Shift+Tab is taken only in the composer's text area and only together with Tab's forward move (no keyboard trap), and quick presses step one mode each → D13b `ComposerKeysTest` `Tab moves the focus forward and Shift+Tab is left to the IDE`, `CyclePermissionModeActionTest` `the action is enabled only with the composer's modes`, IDE test 4's Tab; D13a `SessionManagerTest` `two cyclePermissionMode calls with no reply between them send acceptEdits then plan`; D13b `ModesTest` `next never offers bypass or dontAsk`.
10. The spark and the shimmer cost nothing while nothing runs and never change the row's text or semantics per frame → D14 `WorkingIndicatorTest` `waiting and not responding neither animate nor shimmer`, `under reduced motion nothing animates or shimmers`; the reviewer reads `Spark.kt` and `Shimmer.kt` for state read only in draw and loops that park in `withFrameMillis` or `delay`.

---

### Task D8: queue-and-fork-recordings

**Depends on:** none (Sonnet; reviewer Opus; first of the two recording tasks, beside the Kotlin chain; D0 follows it, since both edit `scripts/drive_cli.py`). Needs the sandbox's signed-in `claude`, as the probes used; if it is not signed in, or a new recording fails twice, stop and report: Steps 1 to 4 still commit, D11 draws a steer's bubble without Edit and Cancel (spec 12.3's default), D12b keeps spec 13 as written, and the report names the outcome that is missing. Spec 10 slice 8, spec 12.1 and 12.3 (the steer), 13 (open item 14), 11 items 12 and 14.

**Files:**
- Modify: `scripts/drive_cli.py` (docstring, `Cli.user_content`, `Cli.user`, `Cli.user_later`, `reply_uuid`, `fork_at_argv`, seven scenarios, `SCENARIOS`), `scripts/test_drive_cli.py`, `scripts/record-scenario.sh` (usage list), `src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt` (the list of `transcript fixtures decode line by line without throwing`), `docs/superpowers/research/2026-10-22-desktop-parity-probes.md` (section 5: the three recordings and their outcomes)
- Create: `src/test/resources/fixtures/queue-steer-text.ndjson`, `src/test/resources/fixtures/queue-cancel-steer.ndjson`, `src/test/resources/fixtures/fork-at-live.ndjson`, `src/test/resources/fixtures/transcripts/queue-steer-text.jsonl`, `src/test/resources/fixtures/transcripts/queue-cancel-steer.jsonl`, `src/test/resources/fixtures/transcripts/fork-at-live.jsonl`, `src/test/resources/fixtures/transcripts/fork-at-live-origin.jsonl`

**Interfaces:**
- Produces (spec 10 slice 8):
  ```python
  # scripts/drive_cli.py
  class Cli:
      def user_content(self, content: list[dict], priority: str | None = None) -> str  # returns the uuid it sent; "priority" right after "uuid", only when given
      def user(self, text: str) -> str          # returns user_content's uuid; callers that ignore it are unchanged
      def user_later(self, text: str) -> str    # user_content([text block], priority="later")
  def reply_uuid(lines: list[str]) -> str | None  # the uuid of the last `assistant` stdout line with parent_tool_use_id null, or None
  def fork_at_argv(extra: list[str], origin: str, fork_id: str, cut: str) -> list[str]
      # extra + ["--resume", origin, "--fork-session", "--session-id", fork_id, "--resume-session-at", cut]
  def fork_at(cli: Cli, spawn=Cli) -> None; def fork_at_live(cli: Cli, spawn=Cli) -> None
  def queue_fold(cli: Cli) -> None; def queue_later(cli: Cli) -> None; def queue_cancel(cli: Cli) -> None
  def queue_steer_text(cli: Cli) -> None; def queue_cancel_steer(cli: Cli) -> None
  ```
- Scenarios. The first four re-record the committed stories (their prompts verbatim from the committed transcripts); every one answers any `can_use_tool` with allow through an `on_message` callback, and a fork's process starts with `first_id = cli.next_id`, so its user uuid never repeats a copied one (spec 13.1):
  - `fork-at`: turn 1 `Remember the word ALPHA. Reply with exactly one word: OK` (`transcripts/fork-at-origin.jsonl:5`; raises if it used a tool, since the cut must be the reply's own line), `cut = reply_uuid(<turn 1's lines>)` (raises when None); turn 2 ``Remember the word BRAVO. First run the shell command `echo hi` with the Bash tool, then reply with exactly one word: OK`` (`:27`); turn 3 `Remember the word CHARLIE. Reply with exactly one word: OK` (`:35`); close; then `spawn(fork_at_argv(cli.extra, sid, str(uuid.uuid4()), cut), first_id=cli.next_id)`, `call(INITIALIZE)`, `Which words did I ask you to remember? Answer with the words only, comma-separated, nothing else.` (`transcripts/fork-at.jsonl:18`), `until(is_result)`; raises unless the fork's reply holds `ALPHA` and neither `BRAVO` nor `CHARLIE`; the fork's lines follow the origin's in `cli.lines`.
  - `fork-at-live`: the same, except that the fork starts after turn 3's `result` while the origin's process is still open and idle, and the origin closes only after the fork's `result` (Tandem's usual case; the committed fork ran after the origin had exited).
  - `queue-fold`: prompt `Use the Bash tool to run exactly: sleep 6; ls. Then use the Bash tool to run exactly: sleep 6; echo second. Then count from 1 to 30, one number per line.` (`transcripts/queue-fold.jsonl:4`); on the first `system/task_started`, `user("Also say the word KIWI at the end.")`; `until(is_result)`.
  - `queue-later`: the same prompt; on the first `task_started`, `user_later("Also say the word KIWI at the end.")` (`transcripts/queue-later.jsonl:32`); until the second `result`.
  - `queue-cancel`: prompt `Use the Bash tool to run exactly: sleep 8; echo first. Then reply with exactly one word: DONE` (`transcripts/queue-cancel.jsonl:3`); on `task_started`, `f1 = user_later("Reply with only the word KIWI.")`, then `call({"subtype": "cancel_async_message", "message_uuid": f1})`; `until(is_result)`; prompt `Use the Bash tool to run exactly: sleep 8; echo second. Then reply with exactly one word: DONE` (`:30`); on `task_started`, `f2 = user_later("Reply with only the word MANGO.")`, then `call({"subtype": "interrupt", "cancel_queued": True})`; `until(is_result)`; then 3 s more, raising if an `init` arrives.
  - new `queue-steer-text` (spec 12.1, a steer with no tool round left; so far a probe run): prompt `Count from 1 to 150, one number per line, nothing else.`; on the first `stream_event` whose delta is a `text_delta`, `s = user("Then say KIWI.")`; until the `result` whose `user_message_uuids` contains `s` (the first if the CLI folds it, else the second).
  - new `queue-cancel-steer` (spec 12.3, withdrawing a steer; 11 item 12): prompt `Use the Bash tool to run exactly: sleep 8; echo first. Then use the Bash tool to run exactly: sleep 8; echo second. Then reply with exactly one word: DONE`; on the first `task_started`, `s1 = user("Also say the word KIWI at the end.")` and at once `call({"subtype": "cancel_async_message", "message_uuid": s1}, allow_error=True)`; on the second `task_started`, `s2 = user("Also say the word MANGO at the end.")`, then `call({"subtype": "interrupt", "cancel_queued": True})`; `until(is_result)`; 3 s more, raising if an `init` arrives.
- `record-scenario.sh`'s usage list and the docstring gain the seven names, one line each.

- [ ] **Step 1: Write the failing tests** in `scripts/test_drive_cli.py` (the existing `FakeCli` records the frames `send` writes):
  - `test_user_later_puts_priority_after_uuid_and_returns_it`: `user_later("x")` writes a frame whose keys run `…, "uuid", "priority", …` with `"priority": "later"` and returns that uuid; `user("x")` writes no `priority` and returns its uuid.
  - `test_reply_uuid_takes_the_last_main_thread_assistant_line`: lines `[assistant a1 (parent_tool_use_id null), assistant s1 (parent_tool_use_id "toolu_1"), result]` → `"a1"`; no assistant line → `None`.
  - `test_fork_at_argv_ends_with_the_cut`: `fork_at_argv(["--model", "m"], "A", "B", "X") == ["--model", "m", "--resume", "A", "--fork-session", "--session-id", "B", "--resume-session-at", "X"]`.
  - `test_the_fork_at_scenario_cuts_at_the_first_turns_reply`: a `FakeCli` whose turn 1 answers with assistant `a1`, a subagent line `s1` and a result, and an injected spawn that records its argv and `first_id`: the spawn's argv ends with `fork_at_argv(…, "s-1", F, "a1")` with `uuid.UUID(F).version == 4`, its `first_id == cli.next_id`; a fork reply naming `BRAVO` raises `RuntimeError`; a turn 1 with a `tool_use` raises.
  - `test_the_live_fork_starts_before_the_origin_closes`: the origin's `close()` is called after the fork's `result`, and the fork's spawn before it.
  - `test_queue_scenarios_send_at_task_started`: `queue-fold` writes the steer without `priority` right after the first `task_started`; `queue-later` writes it with `"priority": "later"`; `queue-cancel`'s host frames run P1, F1 (`later`), `cancel_async_message` with F1's uuid, P2, F2 (`later`), `interrupt` with `cancel_queued: true`; `queue-cancel-steer`'s run S1 (no `priority`), `cancel_async_message` with S1's uuid, S2, `interrupt` with `cancel_queued: true`; `queue-steer-text` writes its steer right after the first `text_delta`.
  - `test_every_scenario_is_listed`: every key of `SCENARIOS` appears in `record-scenario.sh`'s usage line and in the module docstring.
- [ ] **Step 2: Run** `cd WT/scripts && python3 -m unittest` → the seven new tests fail; every other passes.
- [ ] **Step 3: Implement** the Interfaces block.
- [ ] **Step 4: Run** `cd WT/scripts && python3 -m unittest` → PASS.
- [ ] **Step 5: Record** the three new stories, each from WT: `scripts/record-scenario.sh <s> scratch/rec-<s> --model claude-sonnet-5-5` for `<s>` in `queue-steer-text`, `queue-cancel-steer`, `fork-at-live` (the probes' model: the sandbox's default model refused every turn). Move each `<s>.ndjson` to `src/test/resources/fixtures/` and its `transcripts/<s>.jsonl` (and `fork-at-live-origin.jsonl`) to `src/test/resources/fixtures/transcripts/`. launch.md tactic 4: one redactor run per recording (the script's), a `<leaked-N>` warning stops it, then read each file whole and grep it by hand for `@`, UUIDs, `toolu_`, `msg_`, hostnames and account names. Check with `python3 -I -c` per fixture and print: each `command_lifecycle` line (number, `command_uuid`, `state`), each `control_response` payload (number), each `result` (number, `subtype`, `is_error`, `num_turns`, `user_message_uuid`, `user_message_uuids`), each `init` (number, `session_id`), and the number of assistant lines with a non-null `error`. An API refusal (`out of usage credits`, a synthetic `rate_limit` error, an `is_error` result before the scenario's `interrupt`) settles nothing: record again once, then stop and report. The four committed fixtures are not re-recorded: their scenarios exist so a later drift can re-record them.
- [ ] **Step 6: Write the outcomes** in the probe report's section 5, a new subsection `Steer after the last tool round, withdrawn steer, live fork`, with Step 5's printed line numbers:
  - `queue-steer-text`: **next turn** (the steer `queued`; after the first `result` its `started` and an `init`; a second `result` whose `user_message_uuid` is the steer: spec 12.1 as written) or **folded** (one `result` naming both: the probe run differed, and the report says spec 12.1's "With no tool round left" sentence is revised; D10's rules key on lifecycle lines and need no change).
  - `queue-cancel-steer`: **withdrawable** (S1 `cancelled`, the answer `{"cancelled":true}`, no `KIWI` in any reply: D11 gives every waiting send Edit and Cancel, `withdrawable = true`, and the report says spec 12.3's steer paragraph now holds) or **not** (any other answer, or `KIWI` in a reply: D11 keeps `withdrawable = later`); and Stop: **stop withdraws a steer** (S2 `cancelled` and in the receipt's `cancelled`: spec 12.3 as written) or not (S2 in `still_queued`, or folded: D10's receipt rule already keeps a `still_queued` message as a bubble; the report says spec 12.3's Stop paragraph is revised for steers).
  - `fork-at-live`: **works** (the fork's `init` carries the id passed with `--session-id`, its reply is `ALPHA` alone, and `transcripts/fork-at-live.jsonl` copies the origin's chain up to the cut and gives its new prompt that `parentUuid`, as `transcripts/fork-at.jsonl:6-18` does: D12b as written) or **refused or unlike** (D12b's `forkState` gains `liveOriginal: Boolean`, `WAIT` while the original has a live process, and the report says spec 13.1's States table is revised).
- [ ] **Step 7: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts` → PASS: the generic fixture sweeps (`FixtureDecodeTest`, `RobustnessTest`, the reducer's and the row builder's) take the three new fixtures, and the transcript list names the four new twins. A failure is a decoder gap to report, not a fixture to edit (hard rule 6: `command_lifecycle` is `Unknown(raw)` until D9).
- [ ] **Step 8: Commit** `git add scripts src/test/resources/fixtures/queue-steer-text.ndjson src/test/resources/fixtures/queue-cancel-steer.ndjson src/test/resources/fixtures/fork-at-live.ndjson src/test/resources/fixtures/transcripts/queue-steer-text.jsonl src/test/resources/fixtures/transcripts/queue-cancel-steer.jsonl src/test/resources/fixtures/transcripts/fork-at-live.jsonl src/test/resources/fixtures/transcripts/fork-at-live-origin.jsonl src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt docs/superpowers/research/2026-10-22-desktop-parity-probes.md && COMMIT -m "test: script the queue and fork stories, and record a late steer, a withdrawn steer and a live fork"`

---

### Task D0: add-dir-recording

**Depends on:** D8 (Sonnet; beside the Kotlin chain, after D8: both edit `scripts/drive_cli.py`, and process 3 below uses D8's `fork_at_argv` and `reply_uuid`; deviation 13). Needs the sandbox's signed-in `claude`, as the probes used; if it is not signed in, or the recording fails twice, stop and report: D2b and D6b wait, the rest goes on.

**Files:**
- Modify: `scripts/drive_cli.py` (scenario `add-dir-spawn`, its docstring line), `scripts/test_drive_cli.py`, `scripts/record-scenario.sh` (usage list), `scripts/redact_fixture.py`, `scripts/test_redact_fixture.py`, `scripts/copy_transcript.py` and `scripts/test_copy_transcript.py` (more than two session ids, deviation 13), `docs/superpowers/research/2026-10-22-desktop-parity-probes.md` (new 2.6, 3.5 marks, open question 4)
- Create: `src/test/resources/fixtures/parity-add-dir-spawn.ndjson`, `src/test/resources/fixtures/transcripts/parity-add-dir-spawn.jsonl`, `src/test/resources/fixtures/transcripts/parity-add-dir-spawn-origin.jsonl`

**Interfaces:**
- Produces: `Cli.__init__` gains `safe_mode: bool = True` (`--safe-mode` in the argv only when true; every other scenario unchanged). `add_dir_spawn(cli: Cli, dirs: list[str]) -> None` in `drive_cli.py`, `add-dir-spawn` in `SCENARIOS`: `main` gives this scenario two new empty directories `dirs = [A, B]` from `tempfile.mkdtemp(prefix="tandem-add-dir-")` (outside the run's cwd), starts the first `Cli` with `extra + ["--add-dir", A]` (A last) and `safe_mode=False`, runs `add_dir_spawn(cli, dirs)` after its `INITIALIZE`, and removes both directories in its `finally`. Inside, `base = cli.extra[:-2]` is the run's flags without that first `["--add-dir", A]` (the function raises unless `cli.extra[-2:] == ["--add-dir", A]`), and every later process is built from `base`, never from `cli.extra`. Every process of this scenario runs with `safe_mode=False`, as the probes ran (probes "How to read": the argv minus `--safe-mode`) and as Tandem's `SessionConfig.argv` spawns, so the outcome matches a real Tandem spawn. The scenario, each later process with `first_id` = the previous one's `next_id` and its lines appended to `cli.lines`, as `fork` does: (1) `cli.user("Reply with the single word: one")`, the init's `session_id` (`sid`), `until(is_result)`, `close()`; (2) `Cli(base + ["--resume", sid, "--add-dir", A, "--add-dir", B], …, safe_mode=False)`, `call(INITIALIZE)`, `user("Reply with the single word: two")`, `until(is_result)`, close; (3) (spec 13) a fork at the first turn's reply in spec 13.1's argv: `cut = reply_uuid(<process 1's lines>)` (raise when None), `fork_at_id = str(uuid.uuid4())`, `Cli(fork_at_argv(base, sid, fork_at_id, cut) + ["--add-dir", A], …, safe_mode=False)`, `call(INITIALIZE)`, `user("Reply with the single word: three")`, the init's `session_id` must equal `fork_at_id` (else `RuntimeError`), `until(is_result)`, close (spec 13.1 "Added folders": a fork at a reply passes its parent's folders after the fork tail); (4) a fork in Tandem's own argv (`SessionSpawner.configFor`, D1's argv test): `fork_id = str(uuid.uuid4())`, `Cli(base + ["--resume", sid, "--fork-session", "--session-id", fork_id, "--add-dir", A], …, safe_mode=False)`, `call(INITIALIZE)`, `user("Reply with the single word: four")`, the init's `session_id` must equal `fork_id` (else `RuntimeError`, as `ask_two` fails a run that did not ask), `until(is_result)`, close (spec 3.4 and 7 pass `--add-dir` on a fork spawn: this records that exact argv); (5) the removal: `Cli(base + ["--resume", sid, "--add-dir", B], …, safe_mode=False)` (A left out), `call(INITIALIZE)`, `user("Reply with the single word: five")`, `until(is_result)`, close (spec 3.4: does a folder passed before leave with a `--resume` without it). The fixture `parity-add-dir-spawn` holds the five processes' stdout in order. `copy_transcript.py` copies two transcripts, as for `fork` (`fork.jsonl`, `fork-origin.jsonl`): the last session id's, the plain fork's (process 4), to `add-dir-spawn.jsonl` (the twin by name), and the first id's (processes 1, 2 and 5) to `add-dir-spawn-origin.jsonl`; the fork at a reply's (process 3) is named on stderr and not copied (deviation 13): its stream is all D2b's test reads. `copy_transcript.py`: with more than two distinct init session ids it copies the first's as `-origin` and the last's as the twin and names those between on stderr, instead of failing. D2b's decode tests read the stream and the fork's transcript; Step 6 cites the origin for the resume keeping the transcript.
- Redactor (probes 3.5): `categories[].id` of a `get_context_usage` answer is not an `ID_KEYS` id; `submitUuid` joins `ID_KEYS`; in `get_usage.behaviors.<window>`, the `name` of `agents`, `skills`, `plugins`, `mcp_servers` entries becomes `agent-a`, `skill-a`, `plugin-a`, `server-a`, … per kind in order of first appearance across the run (agent types `general-purpose` and `workflow-subagent` kept); `PATH_RE` stops before a backtick and leaves a trailing `:` out of the path.

- [ ] **Step 1: Write the failing tests.** `scripts/test_drive_cli.py`: the existing `FakeCli` bypasses `Cli.__init__` and records no argv, so a `SpawnRecorder(FakeCli)` takes `Cli`'s parameters (`extra`, `model=None`, `first_id=0`, `safe_mode=True`), appends them to a class-level list, sets `lines = []`, answers each user message with an init (`session_id`: the value after `--session-id` in its `extra`, else `"s-1"`), an assistant line with `uuid` `"a-1"` and `parent_tool_use_id` null, and a result (all three also appended to `lines`), and returns 0 from `close()`. `test_the_add_dir_scenario_resumes_forks_and_drops_a_folder` runs `drive_cli.main("add-dir-spawn", <temp out file>, ["--model", "m"])` under `unittest.mock.patch.object(drive_cli, "Cli", SpawnRecorder)`, so `main`'s first spawn is covered too: five spawns, each with `safe_mode=False`; their `extra` are `["--model", "m", "--add-dir", A]`, `["--model", "m", "--resume", "s-1", "--add-dir", A, "--add-dir", B]`, `["--model", "m", "--resume", "s-1", "--fork-session", "--session-id", G, "--resume-session-at", "a-1", "--add-dir", A]` (spec 13), `["--model", "m", "--resume", "s-1", "--fork-session", "--session-id", F, "--add-dir", A]` with `uuid.UUID(F).version == 4`, `uuid.UUID(G).version == 4` and F != G, and `["--model", "m", "--resume", "s-1", "--add-dir", B]`; A and B are two different directories outside the test's cwd and no longer exist after `main` returns; the out file holds the five processes' lines in order; `scripts/test_copy_transcript.py` `test_a_run_with_two_forks_copies_the_first_and_the_last` (inits for ids X, Y, Z and their three transcripts: X's copied as `-origin`, Z's as the twin, Y named on stderr and not copied; the test that expected a failure for three ids changes with it). `test_safe_mode_false_leaves_the_flag_out`: with `drive_cli.subprocess.Popen` patched by a recorder whose `stdout` is an empty list, `Cli([], safe_mode=False)`'s argv has no `--safe-mode` and `Cli([])`'s has it. `scripts/test_redact_fixture.py`: `test_context_category_ids_are_kept` (`{"categories":[{"id":"system_prompt",…}]}` keeps `system_prompt`); `test_submit_uuid_is_an_id` (a `submitUuid` value becomes a placeholder, never `<leaked-N>`); `test_usage_share_names_are_renamed` (`mcp_servers` `[{"name":"corp-x","pct":2},{"name":"corp-y","pct":1}]` in `day` and `corp-x` again in `week` → `server-a`, `server-b`, `server-a`; agents `general-purpose` kept, `my-agent` → `agent-a`); `test_path_stops_at_a_backtick_and_a_trailing_colon` (``"rule to `/home/u/.claude/CLAUDE.md`: when"`` → ``"rule to `<path>`: when"``).
- [ ] **Step 2: Run** `cd WT/scripts && python3 -m unittest` → the seven new tests fail (and the changed copy_transcript test); every other passes.
- [ ] **Step 3: Implement** the Interfaces block; `record-scenario.sh`'s usage list and `drive_cli.py`'s docstring gain `add-dir-spawn` (one line: a spawn with `--add-dir`, a turn, a stop, a `--resume` respawn with that folder and a second, a turn, a fork at the first reply (`--resume-session-at`) with the first folder, a turn, a `--fork-session --session-id` fork with the first folder, a turn, a `--resume` with the second folder only, a turn; every process without `--safe-mode`; record it with `--model claude-sonnet-5-5`).
- [ ] **Step 4: Run** `cd WT/scripts && python3 -m unittest` → PASS.
- [ ] **Step 5: Record.** `scripts/record-scenario.sh add-dir-spawn scratch/rec-add-dir --model claude-sonnet-5-5` from WT (scratch is gitignored; the probes' model: the sandbox's default model refused every turn, probes "How to read"), then move `add-dir-spawn.ndjson` to `src/test/resources/fixtures/parity-add-dir-spawn.ndjson`, `transcripts/add-dir-spawn.jsonl` (the plain fork's transcript, process 4: `copy_transcript.py` copies the last session id's, so it is the twin by name, as `fork.jsonl` is the `fork` scenario's) to `src/test/resources/fixtures/transcripts/parity-add-dir-spawn.jsonl`, and `transcripts/add-dir-spawn-origin.jsonl` (the first session's: processes 1, 2 and 5) to `src/test/resources/fixtures/transcripts/parity-add-dir-spawn-origin.jsonl`. Read all three whole before committing: no home path, no real id, no name of the machine. Check: `python3 -I -c` over the fixture prints, per `system/init` line, its line number, `len(additional_directories)` and `session_id` (five inits, one per process; the scenario already failed any run whose fork inits did not carry the `--session-id` they passed); per `result`, its line number, `subtype` and `is_error`; and the number of assistant lines with a non-null `error`. Every `result` must have `is_error: false` and no assistant line an `error`: an API error result also has subtype `success` (`Reducer.failureKind`), so a refused turn (`out of usage credits`, a synthetic `rate_limit` error) would otherwise pass. A refused turn settles no branch: record again once, then stop and report.
- [ ] **Step 6: Write the outcome** in the probe report: a new `### 2.6 --add-dir at spawn and on resume` with what Step 5 printed (line numbers, counts, ids equal or not, the result) and the origin transcript's lines that show the resume kept the transcript (turns one, two and five in `parity-add-dir-spawn-origin.jsonl` under one `sessionId`), and which branch it settles (only from turns that were not refused, Step 5): **A** (each init lists the spawn's folders, the resume keeps the session id: D2b and D6b as written), **B** (the resume works, the folders are not listed: D2b's fallback, the folder turns `ACTIVE` at the spawn's first init and `MISSING` never happens), **C** (the CLI refuses `--add-dir` on a resume, or the resume loses the session or its transcript: D2b and D6b are not done, `Add folder` stays out of the menu, and the owner revises spec 3.4). Separately, the fork: **listed** (the fork's init lists A and its `session_id` is the one passed with `--session-id`, not the first's: `fork()` copies the folders, D2b as written) or **not listed** (or the CLI refuses `--add-dir` with `--fork-session`: D2b's `fork()` starts with no folders, the tests marked (fork) are dropped, and the report says spec 3.4's Lifetime line is revised). Separately (spec 13), the fork at a reply (process 3): **listed** (its init lists A under the id passed with `--session-id`: D2b's tests marked (fork at) as written) or **not listed** (or the CLI refuses `--add-dir` beside `--resume-session-at`: D2b drops them, and the report says spec 13.1's "Added folders" line is revised). Separately, the removal: **dropped** (branch A, and the fifth init lists exactly one directory, B: `removeFolder` and the pill's `×` as written) or **kept** (the fifth init still lists A; or branch B, where no init lists an `--add-dir` folder, so a removal cannot be seen: D2b drops `removeFolder`, its notice and the tests marked (removal), D6b draws no `×`, and the report says spec 3.4's "Shown in the session" is revised). Open question 4 points at 2.6; the 3.5 items done here end with `(done: D0)`.
- [ ] **Step 7: Commit** `git add scripts src/test/resources/fixtures/parity-add-dir-spawn.ndjson src/test/resources/fixtures/transcripts/parity-add-dir-spawn.jsonl src/test/resources/fixtures/transcripts/parity-add-dir-spawn-origin.jsonl docs/superpowers/research/2026-10-22-desktop-parity-probes.md && COMMIT -m "test: record --add-dir on a spawn and a resume, and the redactor's parity follow-ups"`

---

### Task D1: protocol-parity

**Depends on:** none (Opus; reviewer Opus).

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/protocol/ContextBreakdown.kt`, `.../protocol/Usage.kt`, `.../protocol/Plugins.kt`
- Modify: `.../protocol/Mcp.kt` (`ControlPayloads` members), `.../protocol/CliMessage.kt` (`SystemInit`), `.../protocol/Ndjson.kt` (`decodeSystem`), `.../protocol/HostMessages.kt`, `.../session/ClaudeSession.kt` (`SessionConfig` only)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt`, `.../protocol/HostMessagesTest.kt`, `.../protocol/RobustnessTest.kt`, `.../session/ClaudeSessionTest.kt` (the argv test)

**Interfaces:**
- Produces (spec 4.1; every field optional and validated, a mistyped entry skipped, never thrown):
  ```kotlin
  // protocol/ContextBreakdown.kt
  data class ContextCategory(val id: String?, val name: String, val tokens: Long, val kind: String?, val deferred: Boolean)
  data class MemoryFileUsage(val path: String, val type: String?, val tokens: Long)
  data class McpToolUsage(val name: String, val serverName: String?, val tokens: Long, val loaded: Boolean?)
  data class McpInstructionsUsage(val serverName: String, val tokens: Long)                  // docs only
  data class AgentUsage(val agentType: String, val source: String?, val tokens: Long)         // docs only
  data class SkillUsage(val name: String, val source: String?, val pluginName: String?, val tokens: Long)
  data class ContextBreakdown(val totalTokens: Long?, val maxTokens: Long?, val rawMaxTokens: Long?, val percentage: Int?, val model: String?,
      val categories: List<ContextCategory>, val memoryFiles: List<MemoryFileUsage>, val mcpTools: List<McpToolUsage>,
      val mcpInstructions: List<McpInstructionsUsage>, val agents: List<AgentUsage>, val skills: List<SkillUsage>)
  // protocol/Usage.kt
  data class ModelTokens(val input: Long?, val output: Long?, val cacheRead: Long?, val cacheWrite: Long?, val costUsd: Double?, val webSearches: Long? = null)   // webSearches: spec 16
  data class SessionUsage(val totalCostUsd: Double?, val apiDurationMs: Long?, val wallDurationMs: Long?, val models: List<Pair<String, ModelTokens>>)
  data class UsageShare(val name: String, val pct: Int)
  data class UsageBehavior(val key: String, val pct: Int)
  data class UsageTip(val key: String, val title: String, val detail: String?)
  data class UsageWindow(val sessionCount: Int?, val requestCount: Int?, val behaviors: List<UsageBehavior>, val tips: List<UsageTip>,
      val agents: List<UsageShare>, val skills: List<UsageShare>, val plugins: List<UsageShare>, val mcpServers: List<UsageShare>)
  data class UsageHistory(val day: UsageWindow?, val week: UsageWindow?)
  data class UsageAnswer(val session: SessionUsage?, val history: UsageHistory?)
  // protocol/Plugins.kt
  data class PluginInfo(val name: String, val path: String?, val source: String?, val version: String?)
  val PluginInfo.builtin: Boolean   // path == "builtin" || source?.endsWith("@builtin") == true
  // ControlPayloads (Mcp.kt), members of the existing object
  fun contextUsage(payload: JsonObject?): ContextBreakdown?   // non-null only with a `categories` JsonArray
  fun usage(payload: JsonObject?): UsageAnswer?               // non-null for any object payload
  // CliMessage.SystemInit(+ plugins: List<PluginInfo> = emptyList(), additionalDirectories: List<String> = emptyList())
  // HostMessages, members of the existing object, through its private request(requestId, subtype) builder
  fun getContextUsage(requestId: String): JsonObject
  fun getUsage(requestId: String, skipBehaviors: Boolean): JsonObject   // "skip_behaviors": true only when skipBehaviors
  // SessionConfig(+ additionalDirectories: List<String> = emptyList()); argv(): "--add-dir", <dir> per entry, appended last
  ```
- Wire keys (recorded): `categories[]{id,name,tokens,kind,isDeferred}` (an entry without a string `name` or with `tokens` not a non-negative JSON number is skipped; `deferred = isDeferred == true || kind == "deferred"`; `color` not decoded), `totalTokens`, `maxTokens`, `rawMaxTokens`, `percentage`, `model`, `memoryFiles[]{path,type,tokens}`, `mcpTools[]{name,serverName,tokens,isLoaded}`, `mcpInstructions[]{serverName,tokens}`, `agents[]{agentType,source,tokens}`, `skills.skillFrontmatter[]{name,source,pluginName,tokens}` (`parity-context:2`, `:17`, `:18`); `session{total_cost_usd,total_api_duration_ms,total_duration_ms,model_usage{<id>:{inputTokens,outputTokens,cacheReadInputTokens,cacheCreationInputTokens,costUSD,webSearchRequests}}}` (a non-object model entry skipped, the CLI's key order kept), `behaviors{day,week}` each `{session_count,request_count,behaviors[]{key,pct},recommendations[]{key,title,detail},agents[]{name,pct},skills[],plugins[],mcp_servers[]}` (`parity-usage:16`, `:17`); `pct` a JSON number in 0..100, rounded, else the entry skipped; `behaviors` null, absent or not an object → `history == null`; `session` not an object → `session == null`. Init `plugins[]{name,path,source,version}` (version kept only when it passes the check, else null; an entry without a string `name` skipped) and `additional_directories[]` (strings only) (`parity-add-dir:5`, `:17`). Not decoded (spec 4.1): `gridRows`, `color`, `slashCommands`, `messageBreakdown`, `apiUsage`, `autoCompactThreshold`, `isAutoCompactEnabled`, `autocompactSource`, `systemTools`, `systemPromptSections`, `deferredBuiltinTools`, `subscription_type`, `rate_limits_available`, `rate_limits`, `fix`, `total_lines_*`.

- [ ] **Step 1: Write the failing tests** (spec 9.1; `FixtureDecodeTest`, `HostMessagesTest`, `RobustnessTest`, `ClaudeSessionTest` JUnit 4):

```kotlin
// FixtureDecodeTest
`parity-context - the breakdown after a turn decodes every category`   // b17.categories.map { it.id } == [system_prompt, system_tools, system_tools_deferred, memory_files, skills, messages, autocompact_buffer, free_space]; kinds == [used, used, deferred, used, used, used, buffer, free]; used tokens sum == 38032 == b17.totalTokens; maxTokens == 1_000_000L, rawMaxTokens == 1_000_000L, percentage == 4, model == "claude-sonnet-5-5"; only system_tools_deferred is deferred (tokens 22275); memoryFiles == [MemoryFileUsage("<path>", "Project", 4300)]; mcpTools == [McpToolUsage("mcp__echo__echo", "echo", 0, false)]; skills.size == 36, skills.sumOf { it.tokens } == 3155, skills.single { it.name == "probe-kit:greet" } == SkillUsage("probe-kit:greet", "plugin", "probe-kit", 22); mcpInstructions and agents empty
`parity-context - the breakdown before the first turn`   // :2 decodes; mcpTools empty; the messages category's tokens == 10; totalTokens == 30909, percentage == 3
`parity-context - summary has no messages row and a deferred MCP row`   // :18: no category id "messages"; "mcp_tools_deferred" deferred with 120 tokens; totalTokens == 42383
`parity-context - an unknown subtype is an error with the CLI's text`   // :19 isSuccess == false, error == "Unsupported control request subtype: get_context_breakdown", contextUsage(payload) == null
`parity-usage - get_usage without the scan has the session and no history`   // u16.session!!: totalCostUsd == 0.1523063, apiDurationMs == 4192, wallDurationMs == 7234; models.map { it.first } == ["claude-haiku-5-5", "claude-sonnet-5-5"]; models[1].second == ModelTokens(2, 3, 0, 38037, 0.152182, webSearches = 0); models[0].second.input == 1173, costUsd == 0.0001243; u16.history == null
`parity-usage - get_usage with the scan has both windows`   // u17.history!!.day!!: sessionCount 23, requestCount 8314; behaviors keys [subagent_heavy, cron, workflow, long_context, large_tool_results], pcts [100, 100, 80, 65, 11]; tips[0] == UsageTip("subagent_heavy", "Give simple subagents a cheaper model", null), tips[1].detail == "some ran 8 hours or more"; agents == [UsageShare("workflow-subagent", 80)]; skills and plugins empty; mcpServers == [("server-a", 2), ("server-b", 2)]; week: sessionCount 88, plugins == [UsageShare("plugin-a", 1)], agents == [("workflow-subagent", 58), ("general-purpose", 19)], behaviors has ("max_effort", 19); u17.session!!.wallDurationMs == 9921
`parity-usage - usage and cost run the usage command locally`   // :22 and :28: Assistant.localCommand!!.command == "usage", parentToolUseId == null, uuid "<uuid-28>" and "<uuid-36>"; :23 Result.localCommand == "usage", numTurns == 0; :29 Result.localCommand == "cost"; spec 9.1's promptCacheLine assertion on this row is D3's (it is a chat/ function)
`parity-add-dir - init lists plugins and additional directories`   // :5 plugins.size == 4, count { it.builtin } == 3, plugins[0] == PluginInfo("probe-kit", "<path>", "probe-kit@inline", "0.1.0"), additionalDirectories empty; :17 additionalDirectories == ["<path>"]
`parity-add-dir - the catalogue names plugin commands with a prefix`   // ControlPayloads.initialize(:1).commands: "probe-kit:hello" with description "(probe-kit) Says hello from a plugin command"; "probe-kit:greet" with "(probe-kit) Greets the user by name in one short sentence." and aliases ["greet"]
// HostMessagesTest
`the parity requests match the probe frames`   // wire(getContextUsage("c1")), wire(getUsage("u1", true)), wire(getUsage("u2", false)) equal the three frames of the Exact values line, byte for byte
// ClaudeSessionTest (beside the existing argv test, its `base` list)
`added folders become --add-dir after --resume`   // SessionConfig("claude", tmp, resumeSessionId = "s2", additionalDirectories = listOf("/a", "/b c")).argv() == base + disallowed + ["--resume", "s2", "--add-dir", "/a", "--add-dir", "/b c"] ("/b c" one element: Review Focus 5); with sessionId = "s1" … ["--session-id", "s1", "--add-dir", "/a"]; a fork … ["--resume", "s2", "--fork-session", "--session-id", "s3", "--add-dir", "/a"]; without folders no "--add-dir"
// RobustnessTest
`mutated parity shapes degrade, never throw`   // :17 with a category without "id" → decoded, id null; without "kind" → kind null; "tokens":"12" or -1 → that category skipped, the rest kept; "categories":{} → contextUsage == null; "mcpTools":[{"name":"x"}] → empty; "skills":[] → skills empty. :17 (usage) with a behaviour "pct":140 or "pct":"80" → skipped, "pct":99.6 → 100; "behaviors":7 → history == null; "session":"x" → session == null, usage(...) != null. :5 with a plugin "version":"1 0" → null, "0.1.0-beta+3" kept; "plugins":{} → empty; "additional_directories":[1,"/x"] → ["/x"]. (spec 16) :16 with the sonnet model's "costUSD":"0.15" → its costUsd null, the model kept; "webSearchRequests":"2" → webSearches null
`every control answer of every fixture decodes without throwing`   // for each control_response line of Fixtures.names(): contextUsage(payload) and usage(payload) return without throwing
`docs-only agents and MCP server instructions decode in the SDK's shape`   // docs only (sdk.d.ts; no recording has either): :17 with "agents":[{"agentType":"reviewer","source":"projectSettings","tokens":919},{"agentType":"x"}] and "mcpInstructions":[{"serverName":"echo","tokens":26},{"serverName":"docs","tokens":22},{"tokens":5}] (token values taken from :17) → agents == [AgentUsage("reviewer", "projectSettings", 919)], mcpInstructions == [McpInstructionsUsage("echo", 26), McpInstructionsUsage("docs", 22)] (an entry without its name or tokens skipped); a wrong key name would hide both groups on a machine that has them
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.protocol.*' --tests 'dev.daarkolabs.tandem.session.ClaudeSessionTest'` → compilation errors (`ContextBreakdown`, `getUsage`, `additionalDirectories`, …).
- [ ] **Step 3: Implement** the Interfaces block with the `JsonAccess` helpers (`string`, `long`, `int`, `double`, `objects`, `strings`); no `Reducer` change (D2a).
- [ ] **Step 4: Run** the Step 2 command → PASS, then `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (`protocol/` ≥ 90 %).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/protocol src/main/kotlin/dev/daarkolabs/tandem/session/ClaudeSession.kt src/test/kotlin/dev/daarkolabs/tandem/protocol src/test/kotlin/dev/daarkolabs/tandem/session/ClaudeSessionTest.kt && COMMIT -m "feat(protocol): decode the context breakdown, usage and plugin shapes, and pass --add-dir"`

---

### Task D9: protocol-queue-and-fork

**Depends on:** D1 (both change `HostMessages` and `SessionConfig`) (Opus; reviewer Opus). Spec 12.6 (`protocol/` part), 13.2 (`SessionConfig`), 9.7 Protocol.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/protocol/QueueReplies.kt`
- Modify: `.../protocol/HostMessages.kt`, `.../protocol/CliMessage.kt`, `.../protocol/Ndjson.kt`, `.../session/ClaudeSession.kt` (`SessionConfig` only), and every exhaustive `when` over `CliMessage` (`Reducer.kt` and any other the compiler names) with `is CliMessage.CommandLifecycle -> state` (no behaviour: D10 gives it its rules)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt`, `.../protocol/HostMessagesTest.kt`, `.../protocol/RobustnessTest.kt`, `.../session/ClaudeSessionTest.kt` (beside D1's argv test)

**Interfaces:**
- Produces (spec 12.6, 13.2):
  ```kotlin
  // protocol/HostMessages.kt, members of the existing object
  fun userMessage(text: String, images: List<ImageBlock>, uuid: String, later: Boolean = false): JsonObject   // "priority":"later" right after "uuid", only when later
  fun interrupt(requestId: String, cancelQueued: Boolean = false): JsonObject                                  // "cancel_queued":true only when asked
  fun cancelAsyncMessage(requestId: String, messageUuid: String): JsonObject                                    // request(requestId, "cancel_async_message") { put("message_uuid", messageUuid) }
  // protocol/CliMessage.kt; Ndjson decodes the top-level type "command_lifecycle" (an Unknown today)
  data class CommandLifecycle(val commandUuid: String, val state: String, override val raw: JsonObject) : CliMessage
  //   state as sent (queued, started, completed, cancelled; any other string kept); a missing or non-string command_uuid or state → Unknown(raw)
  // protocol/QueueReplies.kt
  data class InterruptReceipt(val stillQueued: List<String>, val cancelled: List<String>)   // an absent or mistyped list reads as empty; a non-string entry is skipped
  fun interruptReceipt(payload: JsonObject?): InterruptReceipt
  fun cancelAsyncAnswer(payload: JsonObject?): Boolean?                                    // `cancelled`; null when absent or not a boolean
  // session/ClaudeSession.kt
  SessionConfig(+ resumeSessionAt: String? = null)
  //   argv(): "--resume-session-at", <uuid> right after "--session-id", before D1's "--add-dir" entries
  //   init: require(resumeSessionAt == null || forkSession) { "--resume-session-at only with --fork-session: without it the CLI branches the original's transcript." }
  //         require(resumeSessionAt == null || (resumeSessionAt.isNotBlank() && !resumeSessionAt.startsWith("-"))) { "A fork point is a message uuid, never a flag." }
  ```
- Wire keys (recorded): `{"type":"command_lifecycle","command_uuid":…,"state":…,"session_id":…,"uuid":…}` (`queue-fold:3`, `:19`, `:22`, `:52`; `queue-cancel:24`, `:55`); the `cancel_async_message` answer `{"cancelled":true}` (`queue-cancel:25`); the `interrupt` receipt `{"still_queued":[],"cancelled":["<command_uuid-68>"]}` (`queue-cancel:56`). `SystemStatus` and `Result` keep their fields: `system/status`'s `user_message_uuids` (`queue-fold:23`) and `result.queued_turn_count` are read from `raw` in the tests, not decoded (`queued_turn_count` is never a turn-over signal, spec 12.5). `SystemInit.capabilities` is decoded already. No strict UUID check on `resumeSessionAt` (the fixtures and IDE tests use placeholders such as `<uuid-14>` and `a1`; spec 13.1).

- [ ] **Step 1: Write the failing tests** (spec 9.7 Protocol; JUnit 4; P, F, A, B as Global Constraints name them):

```kotlin
// FixtureDecodeTest
`queue-fold - lifecycle lines decode with their states`   // at("queue-fold", n) as CommandLifecycle: 3 → (P, "queued"), 5 → (P, "started"), 19 → (F, "queued"), 22 → (F, "started"), 52 → (F, "completed"), 54 → (P, "completed")
`queue-fold - one result names the prompt and the folded message`   // :23 (SystemStatus).raw["user_message_uuids"] == [P, F]; :53 Result numTurns 3, userMessageUuid P, userMessageUuids [P, F], raw queued_turn_count 0; no `user` line of queue-fold has uuid F or a text block "Also say the word KIWI at the end."
`queue-later - the later message runs as its own turn`   // :19 (F, "queued"); :51 Result userMessageUuids == [P], raw queued_turn_count 0; :53 (F, "started"); :54 is a SystemInit; :63 Result userMessageUuid F, numTurns 1
`queue-cancel - cancel_async_message answers cancelled true`   // :24 (F1, "cancelled"); cancelAsyncAnswer(:25's payload) == true; :36 Result subtype "success", userMessageUuids == [P1]
`queue-cancel - interrupt with cancel_queued lists the cancelled message`   // :55 (F2, "cancelled"); interruptReceipt(:56's payload) == InterruptReceipt(emptyList(), listOf(F2)); :60 Result subtype "error_during_execution", terminalReason "aborted_tools", isError true; :61 (P2, "cancelled")
`the init lists the queue capabilities`   // SystemInit.capabilities of queue-fold:6, queue-cancel:5 and plain-reply:4 each contain "msg_lifecycle_v1" and "interrupt_cancel_queued_v1"
`fork-at - a fork at the first reply knows only that turn`   // SystemInit sessionIds at :6, :19, :45, :60 == [A, A, A, B], B != A; :11 Assistant uuid "<uuid-14>", parentToolUseId null; :65's text "ALPHA"; :70 Result subtype "success", numTurns 1
`fork-at transcripts - the fork copies the chain up to the cut and nothing after`   // transcripts/fork-at.jsonl: :17 the assistant line "<uuid-14>", :18 the user line with parentUuid "<uuid-14>"; every sessionId is B; no line's uuid is one of transcripts/fork-at-origin.jsonl's user or assistant uuids after its line 20 (BRAVO's prompt :27, CHARLIE's :35, :37's reply among them); fork-at-origin.jsonl:20's uuid is "<uuid-14>", as fork-at:11's
// HostMessagesTest
`queue frames`   // wire(userMessage("ping", emptyList(), uuid = "u-1")) is `user text message`'s expected string, unchanged; with later = true it is that string with ,"priority":"later" before the final }; wire(interrupt("a1")) == {"type":"control_request","request_id":"a1","request":{"subtype":"interrupt"}}; wire(interrupt("a1", cancelQueued = true)) == {"type":"control_request","request_id":"a1","request":{"subtype":"interrupt","cancel_queued":true}}; wire(cancelAsyncMessage("c1", "u-1")) == {"type":"control_request","request_id":"c1","request":{"subtype":"cancel_async_message","message_uuid":"u-1"}}
// ClaudeSessionTest (spec 9.7's SessionConfigTest row, deviation 15)
`a fork at a reply ends with --resume-session-at`   // SessionConfig("claude", tmp, sessionId = "B", resumeSessionId = "A", forkSession = true, resumeSessionAt = "X").argv() == base + disallowed + ["--resume", "A", "--fork-session", "--session-id", "B", "--resume-session-at", "X"]; with additionalDirectories = listOf("/a") it continues "--add-dir", "/a"; IllegalArgumentException for resumeSessionAt = "X" without forkSession, and for "", " " and "-x"
// RobustnessTest
`mutated queue shapes degrade, never throw`   // queue-fold:19 without "command_uuid" → Unknown; with "state":3 → Unknown; with "state":"paused" → CommandLifecycle(F, "paused"); queue-cancel:56's payload with "cancelled":true → interruptReceipt(…).cancelled empty; with "still_queued":"x" → stillQueued empty; with "cancelled":[1,"<command_uuid-68>"] → cancelled == ["<command_uuid-68>"]; cancelAsyncAnswer of {"cancelled":"yes"} and of {} → null; interruptReceipt(null) == InterruptReceipt(emptyList(), emptyList())
// and D1's `every control answer of every fixture decodes without throwing` also calls interruptReceipt and cancelAsyncAnswer on each payload
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.protocol.*' --tests 'dev.daarkolabs.tandem.session.ClaudeSessionTest'` → compilation errors (`CommandLifecycle`, `cancelAsyncMessage`, `resumeSessionAt`, …).
- [ ] **Step 3: Implement** the Interfaces block with the `JsonAccess` helpers; the no-op `CommandLifecycle` branches only, no reducer rule.
- [ ] **Step 4: Run** the Step 2 command → PASS, then `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (`protocol/` ≥ 90 %; `interrupt`, `history-interrupt` and their tests unchanged).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/protocol src/main/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/protocol src/test/kotlin/dev/daarkolabs/tandem/session/ClaudeSessionTest.kt && COMMIT -m "feat(protocol): command lifecycle lines, the queue frames and replies, and --resume-session-at"`

---

### Task D2a: session-requests-and-card

**Depends on:** D1 (Opus; reviewer Opus).

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/session/ContextControls.kt`
- Modify: `.../session/McpControls.kt` (the two aliases), `.../session/ClaudeSession.kt`, `.../session/ChatState.kt`, `.../session/Reducer.kt`, `.../session/SessionManager.kt`, `src/test/resources/fake-claude.py` (rules (h)–(k) and the docstring)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/ReducerTest.kt`, `.../session/ClaudeSessionTest.kt`, `.../session/SessionManagerTest.kt`

**Interfaces:**
- Consumes: D1's types, `ControlPayloads.contextUsage` / `usage`, `HostMessages.getContextUsage` / `getUsage`, `SystemInit.plugins` / `additionalDirectories`.
- Produces (spec 4.2):
  ```kotlin
  typealias ControlResult<T> = McpResult<T>; typealias ControlError = McpError            // McpControls.kt; no rename this round
  interface ContextControls { suspend fun contextBreakdown(id: SessionId): ControlResult<ContextBreakdown> }   // ContextControls.kt
  ChatState(+ contextBreakdown: ContextBreakdown? = null, plugins: List<PluginInfo> = emptyList(), additionalDirectories: List<String> = emptyList())
  ChatEntry.Notice(+ sourceUuid: String? = null, usage: UsageCard? = null)            // after runCommand
  data class UsageCard(val session: UsagePartState<SessionUsage>, val history: UsagePartState<UsageHistory?>)
  sealed interface UsagePartState<out T> {
      data object Loading : UsagePartState<Nothing>
      data class Loaded<T>(val value: T) : UsagePartState<T>
      data class Failed(val error: ControlError) : UsagePartState<Nothing>
  }
  enum class UsagePart { SESSION, HISTORY }
  SessionEvent.ContextBreakdownListed(val breakdown: ContextBreakdown)
  SessionEvent.UsageFetched(val uuid: String, val part: UsagePart, val result: ControlResult<UsageAnswer>)
  SessionEvent.UsageRetry(val uuid: String, val part: UsagePart)
  internal fun CliMessage.Assistant.isUsageLine(): Boolean   // localCommand?.command == "usage" && parentToolUseId == null && uuid != null
  class ClaudeSession {
      suspend fun contextUsage(): ControlResult<ContextBreakdown>      // mcpRequest(30, getContextUsage): Ok → dispatch ContextBreakdownListed;
                                                                     // success without categories → Failed(Rejected("the CLI's answer had no categories"))
      suspend fun usage(skipBehaviors: Boolean): ControlResult<UsageAnswer>   // mcpRequest(15 or 60, getUsage); null decode → Failed(Rejected("the CLI's answer had no session"))
      fun retryUsage(uuid: String, part: UsagePart)                   // dispatch UsageRetry, then SESSION: both requests as the fetch does; HISTORY: usage(false) alone
  }
  class SessionManager : McpControls, ContextControls {
      override suspend fun contextBreakdown(id: SessionId): ControlResult<ContextBreakdown>   // NotRunning without a live process; coalesced
      fun retryUsage(id: SessionId, uuid: String, part: UsagePart)   // handle.pick(live = { it.retryUsage(uuid, part) }, stopped = UsageFetched(uuid, part, Failed(NotRunning)))
  }
  ```
- `readLoop` (spec 3.2, 4.2): dispatching a `CliMessage.Assistant` with `isUsageLine()` remembers its uuid (`pendingUsage`, the latest one); the next `CliMessage.Result` (recorded: `local_command` `usage` or `cost`, `parity-usage:23`, `:29`) clears it and launches the fetch for that uuid in the session's scope, so `get_usage` goes out between turns, as the probes sent it: `usage(true)` → `dispatch(UsageFetched(uuid, SESSION, it))`; then, unless the process ended (`ended`), `usage(false)` → `dispatch(UsageFetched(uuid, HISTORY, it))`. A process that ends before that result fetches nothing; deviation 6 settles the card. `TranscriptReader` never fetches.
- Coalescing: make `McpRefresh` generic (`private class Coalesced<T> { var running: Job?; var next: CompletableDeferred<McpResult<T>>? }`) and `startRefreshes` a generic `startCoalesced(handle, entry, first, run: suspend () -> McpResult<T>)`; `refreshMcp` keeps its behaviour (its tests stay green) and `contextBreakdown` gets its own `WeakHashMap<SessionHandle, Coalesced<ContextBreakdown>>`, without `mcpActions`.
- Reducer rules (spec 4.2 plus deviations 5 and 6): `SystemInit` replaces `plugins` and `additionalDirectories`. A `CliMessage.Assistant` with `isUsageLine()` goes through `localCommandNotice` with `sourceUuid = uuid` and `usage = UsageCard(Loading, Loading)`; every other local-command line and `SessionEvent.LocalCommandOutput` get none. `UsageFetched` finds the notice by `sourceUuid`, scanning back from the end (none → the same state instance, logged at debug with the uuid only), and replaces it: `SESSION` + `Ok` → `Loaded(answer.session)` or `Failed(Rejected("the CLI's answer had no session"))` when null; `HISTORY` + `Ok` → `Loaded(answer.history)`; `Failed(e)` → `Failed(e)`. `UsageRetry(SESSION)` sets both parts `Loading`, `UsageRetry(HISTORY)` the history part. `ContextBreakdownListed` replaces `contextBreakdown`. `SessionEvent.Ended` and `settled()` turn every `Loading` part into `Failed(McpError.Ended)`; `settled()` keeps `contextBreakdown`.
- Fake CLI (spec 9.2, deviation 9), each rule in the docstring's list after (g): **(h)** `get_context_usage` is answered inline, at any time, with the fixture's first `control_response` whose payload has a `categories` array with a `messages` category, else `{"subtype":"error","error":"Unsupported control request subtype: get_context_usage"}`; **(i)** `get_usage` with `skip_behaviors: true` with the first whose payload has a `session` object and `behaviors: null`, without it with the first whose `behaviors` is an object, else the same error naming `get_usage`; **(j)** in a fixture whose name starts with `parity-`, every `control_response` line leaves the in-order replay (`initialize` still answered by (a)), and any other host control request gets a bare success, as in an MCP fixture (c); **(k)** `--add-dir` is already in the `__spawn__` argv. `TANDEM_FAKE_DELAY_MS` delays the (h) and (i) answers too.

- [ ] **Step 1: Write the failing tests** (spec 9.2; one WARNING notice is `entries.filterIsInstance<ChatEntry.Notice>().count { it.level == … }`):

```kotlin
// ReducerTest (JUnit 4, replay(fixture))
`an init sets the plugins and added directories`   // parity-add-dir: after :5 plugins.size == 4, additionalDirectories empty; after :17 additionalDirectories == ["<path>"]
`a stream usage line gets a pending card`   // parity-usage through :22: last Notice kind LOCAL_COMMAND, runCommand "usage", sourceUuid "<uuid-28>", usage == UsageCard(Loading, Loading)
`cost gets the same card`   // through :28: the second notice's sourceUuid "<uuid-36>", usage == UsageCard(Loading, Loading)
`a transcript's usage line has no card`   // TranscriptReader.read(transcripts/parity-usage.jsonl) (runBlocking): both LOCAL_COMMAND notices (the :30 and :35 lines) have usage == null, sourceUuid == null
`usage answers fill their own card`   // through :28, then UsageFetched("<uuid-28>", SESSION, Ok(u16)) → first card session == Loaded(u16.session!!), second still Loading; UsageFetched("<uuid-36>", HISTORY, Ok(u17)) → second card history == Loaded(u17.history)
`a usage answer for an unknown uuid changes nothing`   // reduce(state, UsageFetched("nope", SESSION, Ok(u16))) === state
`a session answer without session fails the part`   // Ok(UsageAnswer(null, null)) → session == Failed(Rejected("the CLI's answer had no session"))
`retry sets the parts loading`   // both Failed → UsageRetry(SESSION) → both Loading; from (Loaded, Failed) UsageRetry(HISTORY) → (Loaded, Loading)
`a breakdown answer replaces the last one`   // ContextBreakdownListed(b2) then (b17) → contextBreakdown == b17
`a process end turns loading usage parts into ended`   // (Loaded, Loading) + Ended("x", isError = false) → (Loaded, Failed(Ended))
`a stop settles loading usage parts`   // (Loading, Loading).settled() → (Failed(Ended), Failed(Ended)); contextBreakdown kept
`a usage line inside a subagent gets no card`   // :22 with "parent_tool_use_id":"toolu_1" → its notice has usage == null
// ClaudeSessionTest (JUnit 4, ScriptedSession)
`a usage line sends both get_usage requests in order`   // the script writes :22 → no get_usage frame yet; it writes :23 (the local turn's result) → the session writes get_usage {"skip_behaviors":true}; answered with :16's payload, it then writes get_usage without it; answered with :17's → card (Loaded(session totalCostUsd 0.1523063), Loaded(history day sessionCount 23))
`the history request is not sent once the process ended`   // the script writes :22 and :23; the process exits right after the first get_usage frame → no second get_usage frame; the card reads (Failed(Ended), Failed(Ended))
`contextUsage times out at 30 s and a success lands in the state`   // mcpMillisPerSecond = 10, no answer → Failed(TimedOut(30)); answered with :17's payload → Ok, state.contextBreakdown == b17; a success with payload {} → Failed(Rejected("the CLI's answer had no categories"))
`retryUsage of the history sends the full request alone`   // retryUsage("<uuid-28>", HISTORY) → exactly one get_usage frame, without skip_behaviors
// SessionManagerTest (JUnit 5, the fake)
`contextBreakdown coalesces a second call`   // parity-context: send a prompt, wait for idle; two concurrent calls → both Ok and equal, categories' messages tokens == 10 (rule (h) serves :2); the log holds at most 2 get_context_usage frames
`NotRunning without a process`   // a stopped session → Failed(NotRunning); no get_context_usage frame logged
`a CLI without get_context_usage answers its error`   // plain-reply → Failed(Rejected("Unsupported control request subtype: get_context_usage"))
`a usage prompt fills its card from the CLI's answers`   // parity-usage: send "one", wait for the turn's end; send "/usage" → the notice's card reaches (Loaded totalCostUsd 0.1523063, Loaded day sessionCount 23); the log holds get_usage with "skip_behaviors":true, then get_usage without it
`retryUsage on a stopped session ends the part`   // after the card loaded, stop(id); retryUsage(id, uuid, SESSION) → session part Failed(NotRunning), no frame
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.*'` → compilation errors (`UsageCard`, `contextUsage`, …).
- [ ] **Step 3: Implement** the Interfaces block, the rules and the fake's rules (h)–(k).
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (`session/` ≥ 90 %); `grep -cE '^  \([h-k]\) ' src/test/resources/fake-claude.py` → 4 (the docstring's rule lines, written as (a)–(g) are).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/session src/test/resources/fake-claude.py && COMMIT -m "feat(session): context breakdown and usage requests, the usage card's states and the fake CLI's answers"`

---

### Task D10: session-queue

**Depends on:** D9, D2a (`Reducer`, `ChatState`, `ClaudeSession`) (Opus; reviewer Opus). Spec 12.1 to 12.6 (the session layer), 9.7 Reducer, reader, session and fake.

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/ChatState.kt`, `.../session/Reducer.kt`, `.../session/ClaudeSession.kt`, `.../session/SessionHandle.kt`, `.../session/SessionManager.kt`, `.../session/transcript/TranscriptChain.kt`, `.../session/transcript/TranscriptReader.kt`, `.../ide/SessionNotifier.kt`, `src/test/resources/fake-claude.py` (rule (m), `TANDEM_FAKE_QUEUED_DELAY_MS`, the docstring)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/ReducerTest.kt`, `.../session/ClaudeSessionTest.kt`, `.../session/SessionHandleTest.kt`, `.../session/SessionManagerTest.kt`, `.../session/transcript/TranscriptReaderTest.kt`, `.../ide/SessionNotifierTest.kt`

**Interfaces:**
- Consumes: D9 `CommandLifecycle`, `HostMessages.userMessage(…, later)`, `interrupt(…, cancelQueued)`, `cancelAsyncMessage`, `interruptReceipt`, `cancelAsyncAnswer`; `ClaudeSession.mcpRequest` (the MCP round's).
- Produces (spec 12.6; deviation 16):
  ```kotlin
  // session/ChatState.kt
  ChatState(+ pendingSends: List<PendingSend> = emptyList(), returnedSends: List<ReturnedSend> = emptyList(), cliCapabilities: Set<String>? = null)
  data class PendingSend(val uuid: String, val text: String, val images: List<UserImage>, val sentAtMillis: Long, val later: Boolean,
      val stage: PendingStage, val restoreOnWithdraw: Boolean = false)
  enum class PendingStage { HELD, QUEUED, WITHDRAWING }
  data class ReturnedSend(val uuid: String, val text: String, val images: List<UserImage>)
  ChatEntry.User(+ folded: Boolean = false)
  internal fun firstLine(text: String): String   // the text's first line, cut at 60 characters with "…" (spec 12.5)
  // session/Reducer.kt
  SessionEvent.UserSent(+ later: Boolean = false, live: Boolean = false, held: Boolean = false, folded: Boolean = false)
  SessionEvent.SendReleased(val uuid: String)                                   // HELD → QUEUED
  SessionEvent.WithdrawRequested(val uuid: String, val restore: Boolean)
  SessionEvent.SendWithdrawn(val uuid: String, val restore: Boolean, val notice: String?)
  SessionEvent.WithdrawRefused(val uuid: String, val reason: String?)
  SessionEvent.ReturnedTaken(val uuids: List<String>)
  // session/ClaudeSession.kt
  fun sendUserMessage(text: String, images: List<ImageBlock> = emptyList(), later: Boolean = false)
  fun withdraw(uuid: String, restore: Boolean)
  // session/SessionHandle.kt
  Outbound.Prompt(val text: String, val images: List<ImageBlock> = emptyList(), val later: Boolean = false)
  // session/SessionManager.kt
  fun send(id: SessionId, text: String, images: List<ImageBlock> = emptyList(), later: Boolean = false)
  fun withdraw(id: SessionId, uuid: String, restore: Boolean)   // the live process's withdraw; without one nothing (settled() already returned what waited)
  fun takeReturned(id: SessionId, uuids: List<String>)          // ReturnedTaken on the live process's state or the stopped chat (handle.pick)
  ```
- Notices (spec 12.3, 12.5; `<first line>` is `firstLine(text)`; logs name uuids and stages only): the process ending, WARNING `Not sent: "<first line>": the CLI ended before reading it. It is back in the composer.`; Stop's `cancelled` and `settled()`, INFO `Not sent: "<first line>". It is back in the composer.`; a refused withdraw, INFO `Claude had already taken "<first line>"; it was not withdrawn.`
- Reducer rules (spec 12.6): `SystemInit` sets `cliCapabilities` to its list (an init without the key: empty), `Ended` and `settled()` set it to null. `UserSent(folded = true)` appends `User(folded = true)` and changes nothing else. `UserSent(live = true)` while a turn is open, a message waits, or with `held` appends a `PendingSend` (`HELD` when held, else `QUEUED`; `sentAtMillis` its `atMillis`) and leaves the turn and the entries alone; any other `UserSent` is reduced as today (the reader's replace-the-open-turn rule is kept). `CommandLifecycle`: `started` for a waiting uuid removes it and appends `User(folded = turn != null)`, and with no turn open also opens one (busy, `turnInterrupted = false`, `TurnProgress(uuid, at, …)`), as a send does today; `cancelled` for a waiting uuid removes it: a `WITHDRAWING` one with `restoreOnWithdraw == false` is dropped silently, a `WITHDRAWING` one with it goes to `returnedSends` without a notice, any other goes to `returnedSends` with Stop's INFO notice; `queued`, `completed` and a uuid that is not waiting (the turn's own prompt, `interrupt:5`, `:7`, `:20`) change nothing (the same instance). Before a `result`'s `TurnEnded`, each waiting uuid its `user_message_uuids` names is appended as a folded `User` (the fallback when no lifecycle came). `SendReleased` turns `HELD` into `QUEUED`. `WithdrawRequested` sets `WITHDRAWING` and `restoreOnWithdraw = restore`. `SendWithdrawn` for a waiting uuid removes it and, with `restore`, adds it to `returnedSends` (with `notice` when given); for a uuid no longer waiting it changes nothing (the CLI's `cancelled` line comes before its answer, `queue-cancel:24`, `:25`). `WithdrawRefused` puts the send back to `QUEUED` with the refusal notice. `Ended` returns every waiting message with the WARNING, `settled()` with the INFO; `ReturnedTaken` removes those uuids from `returnedSends`.
- `ClaudeSession` (spec 12.3, 12.4): `sendUserMessage` takes the shell lock; while a `!` command runs, escapes are held or an output frame waits for its quiet `result` (`pendingOutput != null`), the prompt joins `held` and dispatches `UserSent(live = true, held = true, later)`, whatever the capabilities (spec 12.4 rule 2); otherwise it dispatches `UserSent(live = true, later)` and writes `userMessage(…, later)` at once. `heldEscapes` becomes `held: ArrayDeque<Held>` (`private sealed interface Held { class Escape(…); class Prompt(val uuid: String, val frame: JsonObject) }`); `advanceShell` writes head prompts once `pendingOutput == null` and no `Shell` entry runs (dispatching `SendReleased`), and starts a head escape only when `turn == null` and no `QUEUED` send remains (rule 1: a `later` message starts about 10 ms after the running turn's `result`, `queue-later:51` → `:53`). `withdraw(uuid, restore)`: a held prompt leaves `held` with `SendWithdrawn(uuid, restore, null)`; a uuid not waiting does nothing; otherwise `WithdrawRequested`, then in the session's scope `mcpRequest(5, { cancelAsyncMessage(it, uuid) }) { Ok(cancelAsyncAnswer(it)) }` (5 s, scaled by `mcpMillisPerSecond` in tests): an answer `true` → `SendWithdrawn(uuid, restore, null)`; `false`, null, an error or a timeout → `WithdrawRefused(uuid, the error text or null)`. `interrupt()`: held prompts are dropped and returned (`SendWithdrawn(restore = true, notice = Stop's INFO)`); with `"interrupt_cancel_queued_v1"` in `cliCapabilities` and a `QUEUED` send waiting, the frame carries `cancel_queued` and goes through `request()` in the session's scope, each `interruptReceipt(payload).cancelled` uuid → `SendWithdrawn(uuid, restore = true, Stop's INFO)` (a no-op when its `cancelled` line came first, `queue-cancel:55`, `:56`), a `stillQueued` one stays a bubble; otherwise today's frame, byte for byte. The process ending drops `held` (its prompts come back through `Ended`).
- `SessionHandle`: `deliver` passes `later`; `idleLocked` also needs `pendingSends.isEmpty()`; `Snapshot.status` is `RUNNING` while `busy || pendingSends.isNotEmpty()` (`WAITING` first), so the cap and a folder respawn leave it alone (spec 12.5). `SessionManager.watch` raises the turn-end attention only when `turnsEnded` grew and `pendingSends` is empty, and moves its count on either way; `SessionNotifier` does the same for `FINISHED` (deviation 21).
- Reader (spec 12.1 Reload): `TranscriptChain` counts a non-sidechain `attachment` line whose `attachment.type` is `queued_command` and whose `commandMode` is absent or `prompt` as conversational; `TranscriptReader` reads it as `UserSent(uuid = attachment.source_uuid ?: the line's uuid, text = its string prompt or its text blocks joined by "\n", images = its base64 `image` blocks (unrecorded), atMillis = its timestamp, folded = true)`; every other `attachment` stays skipped.
- Fake rule **(m)** (spec 9.7's (l), deviation 12), after (l) in the docstring's list: in a fixture whose name starts with `queue-`, a line naming a `<command_uuid-N>` not yet bound (as `command_uuid` or inside a `user_message_uuids` list) binds it to the oldest host `user` frame read and not yet bound, waiting for the next one when there is none, and every bound placeholder in the line is rewritten to that frame's `uuid`; a `command_lifecycle` `cancelled` line for a placeholder bound after the last `init` (a message sent while the turn ran, not the turn's own prompt) first waits for the host's next `cancel_async_message` or `interrupt`, and the `control_response` after it answers that request (`queue-cancel:24`-`:25`, `:55`-`:56`; the prompt's own `cancelled` at `:61` does not wait); any other replayed `control_response` still pairs with the host's next `control_request`; the wait before an `init` is met by a frame already bound since the last `result`; rule (g) is off for these fixtures. `TANDEM_FAKE_QUEUED_DELAY_MS`: after a `queued` lifecycle line that bound a host frame, sleep this long (unset or 0: no sleep). `--resume-session-at` needs no rule: the fake ignores the flag and logs it in `__spawn__`.

- [ ] **Step 1: Write the failing tests** (spec 9.7; replays inject the host's sends as `UserSent(live = true)` events at the lines named):

```kotlin
// ReducerTest (JUnit 4)
`queue-fold, replayed with the host's sends - F waits, then joins the turn after the tool result`   // UserSent(P, …, live = true) before :1, UserSent(F, "Also say the word KIWI at the end.", live = true) after :18: after :19 pendingSends.map { it.uuid to it.stage } == [(F, QUEUED)], later false, the entries as after :18; after :22 pendingSends empty and the last entry is User(text "Also say the word KIWI at the end.", folded = true), the turn still open; after :53 turnsEnded == 1, busy false, exactly one User with folded == false
`queue-later - F opens its own turn after the first result`   // UserSent(F, later = true, live = true) after :18: after :19 [(F, QUEUED)], later true; after :51 turnsEnded 1, busy false, pendingSends still [F]; after :53 pendingSends empty, busy true, the last entry User(F, folded = false); after :63 turnsEnded == 2
`queue-cancel - a withdrawn later message leaves, Edit returns it, Cancel drops it, no notice`   // UserSent(F1, "Reply with only the word KIWI.", later = true, live = true) after :22; WithdrawRequested(F1, restore = true) → stage WITHDRAWING; :24 → pendingSends empty, returnedSends == [ReturnedSend(F1, "Reply with only the word KIWI.", [])], no new notice; the same with restore = false → returnedSends empty, no notice; :25's SendWithdrawn afterwards changes nothing; after :36 turnsEnded 1
`queue-cancel - Stop's cancelled lifecycle returns F2 with one INFO notice and no further turn`   // UserSent(F2, "Reply with only the word MANGO.", later = true, live = true) after :53; :55 → returnedSends == [F2] and one INFO `Not sent: "Reply with only the word MANGO.". It is back in the composer.`; SendWithdrawn(F2, true, that notice) from the receipt → still one notice; after :61 busy false, no further turn
`the turn's own lifecycle lines change nothing`   // interrupt:5, :7, :20 (`<command_uuid-1>` queued, started, cancelled) after UserSent of that prompt → each reduce returns the same instance
`a live send while a turn runs appends no entry and opens no turn`   // a running turn; UserSent("x", "hi", live = true) → entries and turn the same instances, pendingSends [(x, QUEUED)]
`a loaded transcript's UserSent still replaces the open turn`   // today's regression case, with live = false
`a result naming a waiting uuid folds it when no lifecycle came`   // queue-fold as above with :19 and :22 skipped → after :53 a User(F, folded = true) before the turn's TurnEnded, pendingSends empty
`the process ending returns waiting messages with a WARNING`   // pendingSends [(F, QUEUED, "Also say the word KIWI at the end.")] + Ended("x", isError = false) → returnedSends [F], one WARNING `Not sent: "Also say the word KIWI at the end.": the CLI ended before reading it. It is back in the composer.`
`settled returns them with an INFO`   // → one INFO `Not sent: "Also say the word KIWI at the end.". It is back in the composer.`, cliCapabilities null
`a refused withdraw puts the message back and says so`   // WITHDRAWING + WithdrawRefused(F, null) → stage QUEUED, INFO `Claude had already taken "Also say the word KIWI at the end."; it was not withdrawn.`
`a lifecycle for an unknown uuid changes nothing`   // CommandLifecycle("nope", "started", …) → the same instance
`an init keeps its capabilities, Ended clears them`   // after queue-fold:6 cliCapabilities contains "msg_lifecycle_v1" and "interrupt_cancel_queued_v1"; after Ended → null
`the first line is cut at 60 characters`   // firstLine("a".repeat(61)) == "a".repeat(60) + "…"; firstLine("one\ntwo") == "one"; firstLine("a".repeat(60)) unchanged
`ReturnedTaken clears what the composer took back`   // returnedSends [a, b] + ReturnedTaken([a]) → [b]
// TranscriptReaderTest (JUnit 5, runBlocking)
`a queued_command attachment reads as a folded prompt`   // transcripts/queue-fold.jsonl (:23 the attachment, source_uuid F) → exactly one User with folded = true, text "Also say the word KIWI at the end.", after the first Bash call's entries and before the reply's last text
`a later message's user line reads as a prompt`   // transcripts/queue-later.jsonl:32 → User(text "Also say the word KIWI at the end.", folded = false); two TurnEnded
`a withdrawn message leaves nothing`   // transcripts/queue-cancel.jsonl → no folded User; the Users are the two prompts and the interrupt marker as today
`a queued_command with another commandMode is skipped`   // hand-made (unrecorded): queue-fold.jsonl:23 with "commandMode":"bash" → no folded User
`image blocks in a queued_command prompt become images`   // hand-made (unrecorded): :23 with prompt [text block, {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0"}}] → the folded User has one UserImage with mediaType "image/png" and base64 "iVBORw0"
// ClaudeSessionTest (JUnit 4, ScriptedSession)
`a message sent mid-turn goes out at once without priority`   // a turn open (queue-fold through :18 written by the script); sendUserMessage("more") → a user frame at once, no "priority" key; pendingSends [(its uuid, QUEUED)]
`a later message carries priority later`   // sendUserMessage("more", later = true) → the frame has "priority":"later" right after "uuid"
`interrupt adds cancel_queued only when a message waits and the init lists the capability`   // after queue-fold:6 and a QUEUED send → the interrupt frame has "cancel_queued":true; answered with queue-cancel:56's payload (uuid rewritten to the send's) → the send in returnedSends with one INFO notice; nothing waiting → today's frame byte for byte; plain-reply:4 with "interrupt_cancel_queued_v1" removed and a send waiting → today's frame
`withdraw writes cancel_async_message, true removes, false, an error or a timeout refuses with the notice`   // mcpMillisPerSecond = 10: the frame {"subtype":"cancel_async_message","message_uuid":<uuid>}; {"cancelled":true} → gone from pendingSends; {"cancelled":false} → back to QUEUED with the INFO notice; an error answer → the same; no answer → after 5 scaled s the same
`a prompt sent while a shell escape's output frame waits goes out right after it`   // bash-mode-respond: sendShellEscape(respond = true) then sendUserMessage("next") while the output frame waits for its quiet result → frames in order: the escape's input, its output, the prompt; the prompt is (HELD) until SendReleased, then (QUEUED)
`a held escape waits until no queued message remains`   // a turn open and a later send QUEUED; sendShellEscape(…) is held; the turn's result → no escape frame yet; the later send's `started`, then its result → the escape's frame goes out
`the process ending drops held prompts and returns them`   // a prompt HELD behind an escape; the process exits → no prompt frame written; returnedSends holds it with the WARNING
// SessionHandleTest (JUnit 5)
`a session with a message in the CLI's queue is RUNNING and not idle, so stopIfIdle refuses`   // pendingSends [(x, QUEUED)], no turn → status RUNNING, isIdle() false, stopIfIdle() false, the process still attached
// SessionManagerTest (JUnit 5, the fake)
`send passes later through to the process`   // plain-reply: send(id, "hi"), wait for the turn's end; send(id, "more", later = true) → the log's second user frame has "priority":"later"
`withdraw and takeReturned reach the live process, or the stopped chat`   // queue-cancel (rule (m)): send(id, "Run the command"); wait for the Bash's task_started in the state; send(id, "Reply with only the word KIWI.", later = true); wait for pendingSends [(F1, QUEUED)]; withdraw(id, F1, restore = true) → the log holds cancel_async_message with F1's uuid; returnedSends == [F1]; takeReturned(id, [F1]) → empty; on a stopped session withdraw does nothing and takeReturned clears the chat's list
`no attention while a message waits, attention after the queued turn ends`   // queue-later, the session not selected: send P; during the Bash send F with later = true; after the first result attention false; after the second result attention true
// SessionNotifierTest
`no notification while a message waits, one after the queued turn ends`   // as the attention test, through the notifier's FINISHED event: none after the first result, one after the second
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.*' --tests 'dev.daarkolabs.tandem.ide.SessionNotifierTest'` → compilation errors (`PendingSend`, `withdraw`, `takeReturned`, …).
- [ ] **Step 3: Implement** the Interfaces block, the rules and fake rule (m).
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (`session/` ≥ 90 %; every existing `interrupt`, `history-interrupt` and bash-mode test unchanged); `grep -cE '^  \((h|i|j|k|m)\) ' src/test/resources/fake-claude.py` → 5 (D2b's (l) comes later).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session src/main/kotlin/dev/daarkolabs/tandem/ide/SessionNotifier.kt src/test/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/ide/SessionNotifierTest.kt src/test/resources/fake-claude.py && COMMIT -m "feat(session): messages sent while Claude works, their withdrawal and Stop, and folded prompts on reload"`

---

### Task D12a: session-fork-at

**Depends on:** D10 (`Reducer`, `SessionHandle`, `SessionManager`), D9 (`SessionConfig.resumeSessionAt`) (Opus; reviewer Opus). Spec 13.1, 13.2 (the session part). Additive: `fork(id)` keeps its behaviour; `ForkState`, the hover row and `latestReply`'s removal are D12b's; a fork's folders are D2b's.

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/ChatState.kt` (`Assistant.lastUuid`, `forkedAt`), `.../session/Reducer.kt`, `.../session/SessionHandle.kt` (`forkAt`), `.../session/SessionSpawner.kt` (`configFor`), `.../session/SessionManager.kt` (`fork`, `registerLocked`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/ReducerTest.kt`, `.../session/SessionManagerTest.kt`

**Interfaces:**
- Consumes: D9 `SessionConfig.resumeSessionAt`.
- Produces (spec 13.2):
  ```kotlin
  ChatEntry.Assistant(+ lastUuid: String? = null)              // the uuid of its latest complete line; null for a message drafted from deltas only
  internal fun ChatState.forkedAt(uuid: String): ChatState?   // null when no main-thread Assistant entry has uuid in its uuids
  SessionHandle(id, parentScope, initialState, listing, createdAt, forkOf: SessionId? = null, forkAt: String? = null, pendingTitle: String? = null)
  //   internal val forkAt: String? — with forkOf, the line of forkOf's transcript this fork starts after; passed until this session has a transcript
  // SessionSpawner.configFor: forkOf = handle.forkOf.takeUnless { hasTranscript } as today; resumeSessionAt = handle.forkAt.takeIf { forkOf != null }
  suspend fun fork(id: SessionId, atMessage: String? = null): SessionHandle?   // SessionManager; null: no transcript to fork, or atMessage not in the history
  ```
- Rules: the reducer's complete-assistant-line branch gives a new entry `lastUuid = message.uuid` and copies an existing one with `lastUuid = message.uuid ?: existing.lastUuid`; an entry drafted from stream deltas keeps null. `forkedAt(uuid)`: `settled()` first; the first main-thread (`parentToolUseId == null`) `Assistant` whose `uuids` contains `uuid`; the kept entries end before the first `User` or `Shell` entry after it, so the turn and its footer stay; `entryIndex` empty, `context` null (the ring waits for the fork's first turn), `tasks` kept only for calls still shown. `fork(id, atMessage)`: the source is `id` when it has a transcript, else its `forkOf` at its `forkAt` (a fork with no transcript of its own forks its original at the same line); history and title as today (the open original's settled state, else its loaded transcript); `start = if (atMessage == null) history else history.forkedAt(atMessage) ?: return null`; `cut = atMessage ?: sourceAt`; `registerLocked(forkId, start.copy(sessionId = null), forkOf = source, forkAt = cut, pendingTitle = "$title (fork)")`, then select and start as today. Logs: `session <id> forked as <id>` and whether at a point; ids only. Tandem never falls back to a whole fork: an argv the CLI refuses (`No message found with message.uuid of: <uuid>`, exit 1, a probe run) shows through today's result and exit paths.

- [ ] **Step 1: Write the failing tests** (spec 9.7):

```kotlin
// ReducerTest (JUnit 4)
`lastUuid is the latest complete line`   // fork-at through :11 → the Assistant's lastUuid == "<uuid-14>"; fork-at:11 copied with a thinking block and uuid "u1", then :11 itself (one message over two complete lines) → "<uuid-14>"; fork-at through :10 (deltas only) → the draft's lastUuid == null; TranscriptReader.read(transcripts/fork-at-origin.jsonl) (runBlocking) → the first reply's lastUuid == "<uuid-14>", as the stream's
`forkedAt keeps the turn and stops at the next User or Shell`   // fork-at :1 to :55 replayed (the origin's three turns; :56 starts the fork's process) → forkedAt("<uuid-14>")!!: its only User is ALPHA's prompt, it ends with turn one's TurnEnded, entryIndex empty, context null, no task of BRAVO's Bash call; a state with a Shell entry right after turn one → the cut stops before it; forkedAt("nope") == null; agent-sync replayed whole → forkedAt("<uuid-50>") == null (agent-sync:42, a subagent's line)
// SessionManagerTest (JUnit 5, the fake; seed gains `fixture: String = "history-reply"`)
`fork at a reply resumes with --resume-session-at and starts from the history up to that turn`   // seed(seeded, fixture = "fork-at-origin"); open it without a spawn; X = its first reply's lastUuid ("<uuid-14>"); fork(seeded, X) → the fork's __spawn__ argv ends with ["--resume", seeded, "--fork-session", "--session-id", fork.id, "--resume-session-at", "<uuid-14>"]; the fork's only User is ALPHA's prompt; title "<the original's title> (fork)"; selected; the original's state (===), title and transcript bytes unchanged; stop and send → the same tail; after seed(fork.id) the next spawn has "--resume", fork.id and neither --fork-session nor --resume-session-at
`fork at a uuid not in the history gives null and starts nothing`   // fork(seeded, "no-such-uuid") == null; no new __spawn__; sessions unchanged
`a fork without a transcript forks its original at the same line`   // F = fork(seeded) (no prompt); G = fork(F.id, "<uuid-14>") → G's tail ["--resume", seeded, "--fork-session", "--session-id", G.id, "--resume-session-at", "<uuid-14>"]; H = fork(seeded, "<uuid-14>"), then fork(H.id) → its tail also ends "--resume-session-at", "<uuid-14>" after "--resume", seeded
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.*'` → compilation errors (`lastUuid`, `forkedAt`, `fork(id, at)`).
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (`session/` ≥ 90 %; the existing whole-fork tests unchanged).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/session && COMMIT -m "feat(session): fork a session at any reply with --resume-session-at"`

---

### Task D13a: session-mode-cycle

**Depends on:** D12a (`SessionHandle`, `SessionManager`, `Reducer`) (Opus; reviewer Opus). Spec 14.1 ("No stale reads", "Refused, stopped, busy"), 14.3 (the session part). Additive.

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/ChatState.kt` (`rejectedPermissionModes`), `.../session/Reducer.kt`, `.../session/SessionHandle.kt` (`pickMode`), `.../session/SessionManager.kt` (`cyclePermissionMode`)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/ReducerTest.kt`, `.../session/SessionManagerTest.kt`

**Interfaces:**
- Produces (spec 14.3):
  ```kotlin
  ChatState(+ rejectedPermissionModes: Set<PermissionMode> = emptySet())
  //   Reducer: rejectModeChange adds the refused mode; SystemInit and SystemStatus remove the mode they report; Ended and settled() clear it
  internal fun SessionHandle.pickMode(next: (ChatState) -> PermissionMode): PermissionMode   // a member
  //   under the handle's lock: mode = next(liveLocked()?.state?.value ?: the handle's state); pick(live = { it.setPermissionMode(mode) }, stopped = PermissionModeRequested(mode)); mode
  fun cyclePermissionMode(id: SessionId, next: (ChatState) -> PermissionMode): PermissionMode?   // SessionManager; never waits; null for an unknown id
  ```
- Rules: the read happens under the handle's lock from the live process's own state, which holds every earlier pick synchronously (`ClaudeSession.dispatch`), so quick presses and key repeat step one mode each; the lock is never held across I/O (today's `pick`); the pick goes through today's path (`PermissionModeRequested`, `set_permission_mode`); a stopped session records it for the next spawn's `--permission-mode`. Logs: the mode's wire name only.

- [ ] **Step 1: Write the failing tests** (spec 9.7; `Modes.next` is D13b's, so the manager tests pass `step`, a test lambda walking Manual → Accept edits → Plan → Manual):

```kotlin
// ReducerTest (JUnit 4)
`a refused mode joins rejectedPermissionModes, a reported one leaves, Ended clears them`   // permission-mode-rejected replayed (:17 refuses bypassPermissions, error_code "bypass_not_launched") → rejectedPermissionModes == {BYPASS_PERMISSIONS}, today's ERROR notice unchanged; then a SystemStatus reporting bypassPermissions → empty; {AUTO} + Ended("x", isError = false) → empty; {AUTO}.settled() → empty
// SessionManagerTest (JUnit 5)
`two cyclePermissionMode calls with no reply between them send acceptEdits then plan`   // a scripted live session, no answer written: two calls → ACCEPT_EDITS, then PLAN; the process got two set_permission_mode frames, "acceptEdits" then "plan"
`a stopped session's cycle reaches the next spawn's --permission-mode`   // a stopped session: two calls; send → the spawn's argv holds "--permission-mode", "plan"
`cyclePermissionMode on an unknown id gives null`
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.*'` → compilation errors (`rejectedPermissionModes`, `cyclePermissionMode`).
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/session && COMMIT -m "feat(session): pick the next permission mode under the handle's lock and remember refused modes"`

---

### Task D3: chat-parity

**Depends on:** D2a (Opus; reviewer Opus).

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/chat/ContextBreakdownModel.kt`, `.../chat/UsageCardModel.kt`, `.../chat/PluginMenu.kt`
- Modify: `.../chat/ComposerModel.kt` (`ContextFormat.percentOf`, `ComposerModel` fields), `.../chat/ChatRow.kt` (`NoticeRow`), `.../chat/ChatRowsBuilder.kt`, `src/test/kotlin/dev/daarkolabs/tandem/chat/Replay.kt` (`replay` gains `builder: ChatRowsBuilder = ChatRowsBuilder(options)`)
- Test: Create `.../chat/ContextBreakdownModelTest.kt`, `.../chat/UsageCardModelTest.kt`, `.../chat/PluginMenuTest.kt`; Modify `.../chat/ChatRowsBuilderTest.kt`, `.../chat/ComposerModelTest.kt`

**Interfaces:**
- Consumes: D1's types; D2a's `UsageCard`, `UsagePartState`, `ChatEntry.Notice.sourceUuid/usage`, `ChatState.plugins`.
- Produces (spec 4.3):
  ```kotlin
  // chat/ContextBreakdownModel.kt
  enum class ContextTone { MESSAGES, MCP_TOOLS, MEMORY_FILES, SYSTEM_PROMPT, NEUTRAL, FREE }
  data class CategoryRow(val name: String, val tokens: String, val share: String, val tone: ContextTone, val description: String)
  data class BarSegment(val tone: ContextTone, val fraction: Float)
  data class GroupItem(val label: String, val tokens: String, val dim: Boolean, val tooltip: String?)
  data class BreakdownGroup(val key: String, val title: String, val total: String, val count: Int, val items: ImmutableList<GroupItem>, val more: Int)
  data class ContextPopoverModel(val value: String?, val segments: ImmutableList<BarSegment>, val rows: ImmutableList<CategoryRow>, val groups: ImmutableList<BreakdownGroup>)
  const val GROUP_ITEM_CAP: Int = 100
  data class PopoverStatus(val headerSuffix: String?, val line: String?, val error: Boolean, val dimRows: Boolean)
  const val BREAKDOWN_LOADING = "Loading the breakdown…"; const val BREAKDOWN_UPDATING = " · Updating…"
  const val BREAKDOWN_STOPPED = "The session is not running. The breakdown updates with the next prompt."
  fun popoverStatus(loading: Boolean, failedText: String?, stopped: Boolean, hasRows: Boolean): PopoverStatus
  fun contextPopover(breakdown: ContextBreakdown?, ring: ContextUsage?, home: String?, projectRoot: String?): ContextPopoverModel
  fun displayPath(path: String, home: String?, projectRoot: String?): String
  object ContextFormat { fun percentOf(tokens: Long, max: Long): String }   // a new member of the existing object (chat/ComposerModel.kt)
  // chat/UsageCardModel.kt
  data class UsageFact(val label: String, val value: String)
  data class ModelBlock(val label: String, val cost: String?, val rows: ImmutableList<UsageFact>, val costShare: Float? = null, val costShareText: String? = null)   // costShare: spec 16
  data class BehaviorLine(val pct: String, val label: String, val known: Boolean)   // known: Tandem's wording; false: the CLI's recommendation title
  data class ShareRow(val name: String, val kind: String, val fraction: Float, val pct: String)
  data class UsageWindowModel(val caveat: String, val behaviors: ImmutableList<BehaviorLine>, val shares: ImmutableList<ShareRow>, val tips: ImmutableList<UsageTip>)   // spec 16: every tip, in place of 3.2's first
  sealed interface UsageSection<out T> {
      data object Loading : UsageSection<Nothing>
      data class Shown<T>(val value: T) : UsageSection<T>
      data class Error(val text: String, val retry: Boolean) : UsageSection<Nothing>
      data class Note(val text: String) : UsageSection<Nothing>
  }
  data class UsageCardModel(val facts: UsageSection<ImmutableList<UsageFact>>, val promptCache: String?, val models: ImmutableList<ModelBlock>,
      val history: UsageSection<Pair<UsageWindowModel?, UsageWindowModel?>>, val cliText: String, val cliTextOpen: Boolean)   // a null window: WINDOW_EMPTY
  const val USAGE_LOADING = "Loading usage…"; const val HISTORY_LOADING = "Reading this machine's sessions…"; const val WINDOW_EMPTY = "Nothing recorded for this window."
  fun usageCard(card: UsageCard, noticeText: String, models: List<ModelOption>, activeModel: String?): UsageCardModel
  fun formatCost(usd: Double): String
  fun promptCacheLine(text: String): String?
  fun costShare(costUsd: Double?, totalUsd: Double?): Pair<Float, String>?   // spec 16: null without both, or with a total of 0
  const val TIPS_SHOWN: Int = 3                                             // spec 16 (deviation 18)
  fun tipsTitle(count: Int): String                                         // "Tip" for one, "Tips" for several
  fun tipsToggle(count: Int, open: Boolean): String?                        // "Show <count − 3> more" or "Show fewer" past three; else null
  // chat/PluginMenu.kt
  data class PluginCommand(val name: String, val command: String, val description: String)
  data class PluginEntry(val name: String, val version: String?, val commands: ImmutableList<PluginCommand>)
  fun pluginMenu(plugins: List<PluginInfo>?, commands: List<SlashCommand>, names: List<String>): ImmutableList<PluginEntry>   // null: no init yet
  fun withCommand(draft: String, command: String): String
  // existing classes
  NoticeRow(+ usage: UsageCardModel? = null, markdown: Boolean = false, sourceUuid: String? = null)
  ComposerModel(+ plugins: ImmutableList<PluginEntry> = persistentListOf(), pluginsKnown: Boolean = false)   // pluginsKnown: state.mcp.initCount > 0 || plugins.isNotEmpty()
  ```
- Rules (spec 3.1–3.3):
  - `contextPopover`: rows for `tokens > 0` only; order `kind == "used"` by tokens descending, then `buffer`, then `free`, then deferred by tokens descending, then any other kind in the CLI's order, then a missing kind in the CLI's order; tone by `id` (`messages`, `mcp_tools`, `memory_files`, `system_prompt`, `free_space` → FREE, anything else NEUTRAL), never by `name`; `tokens` by `formatTokens`; `share` by `percentOf(tokens, maxTokens)`, `—` for a deferred row; `description` `"<name>, <tokens> tokens, <share>"`, or `"<name>, <tokens> tokens, deferred"`. Segments: the `used` rows in row order, `tokens / maxTokens`; none when `maxTokens` is null or 0. `value`: `"${formatTokens(total)} / ${formatWindow(max)} (${pct}%)"` with `pct = percentage ?: round(100.0 * total / max)` when both are known and `max > 0`, else `ContextFormat.value(ring)`, else null. Groups, in this order and only with items: `mcp_tools` `MCP tools` (label `"<server> · <tool>"` with `mcp__<server>__` removed from the name, the raw name when it lacks the prefix, the name alone without a server; `dim = loaded == false`), `mcp_instructions` `MCP server instructions` (label the server name), `memory_files` `Memory files` (label `displayPath`, tooltip `"<path> · <type>"` or the path alone), `skills` `Skills` (the name as sent), `agents` `Custom agents` (label the `agentType`, tooltip the `source` when set); `total` the sum of every item's tokens, `count` every item, `items` the first `GROUP_ITEM_CAP`, `more = count - items.size`.
  - `displayPath`: the project root first (path == root + separator + rest → rest), then the home (path == home → `~`; home + separator + rest → `~/rest`), a separator being `/` or `\`; anything else unchanged. String prefixes only, no file access.
  - `popoverStatus` (spec 3.1, Errors): `stopped` → `(null, BREAKDOWN_STOPPED, false, dimRows = hasRows)`; else `failedText != null` → `(null, failedText, error = true, dimRows = hasRows)` (the view puts `Retry` beside it; rows from an earlier answer stay below it, dimmed); else `loading` → `(BREAKDOWN_UPDATING, null, false, false)` with rows, `(null, BREAKDOWN_LOADING, false, false)` without; else `(null, null, false, false)`.
  - `percentOf`: `max <= 0` → `—`; else `String.format(Locale.ROOT, "%.1f%%", 100.0 * tokens / max)`.
  - `usageCard`: facts from `session`: `Est. cost` `formatCost(totalCostUsd)`, `API` `formatElapsed(apiDurationMs)`, `Wall` `formatElapsed(wallDurationMs)`, each left out when absent (all three absent → `Shown([])`, and the view leaves the line out); `Loading` → `UsageSection.Loading`; `Failed`: `Rejected(m)` → `Error("Could not load usage: $m", true)`, `TimedOut(s)` → `Error("The CLI did not answer within $s s.", true)`, `Ended`/`NotRunning` → `Error("The session has ended.", false)`. `promptCache = promptCacheLine(noticeText)`. `models`: the session's active model first, then the CLI's order; label = `displayName` of the first `ModelOption` other than the `default` entry whose `resolvedModel` or `value` equals the id, else the id; cost `formatCost(costUsd)`; rows `Input`, `Output`, `Cache read`, `Cache write` by `formatTokens` (absent → left out), then `Web searches` with the plain count when `webSearches > 0` (spec 16); `costShare` and `costShareText` from `costShare(costUsd, session.totalCostUsd)` (spec 16); an empty `model_usage` → `models` empty, and the view leaves `Breakdown` out. History: `Loading` → `Loading`; `Loaded(null)` → `Note("The CLI did not report what is using your limits.")`; `Loaded(h)` → `Shown(window(h.day, "Last 24 hours") to window(h.week, "Last 7 days"))`, where `window` is null for a window the CLI left out or one that has, after the label rule below, no behaviour line, no share and no tip (the view draws `WINDOW_EMPTY` for null); `Failed`: `Rejected(m)` → `Error("Could not read the usage history: $m", true)`, `TimedOut(s)` as above, `Ended`/`NotRunning` → `Error("The session has ended.", false)`. A window: caveat `"<label> · <n> sessions · <r> requests · approximate, overlapping measures · this machine only"` (`<r>` is `formatTokens(requestCount)`, spec 16; each count part left out when absent); behaviours by `pct` descending (stable), label from `subagent_heavy` → `came from subagent-heavy sessions`, `long_context` → `came from sessions with a long context`, `large_tool_results` → `came from sessions with large tool results`, `max_effort` → `ran at max effort` (`known = true`); any other key takes the `title` of the window's first recommendation with the same key (`known = false`: the CLI's own words), and a key with neither is left out (probes 3.3: unknown keys are hidden); shares from `agents` (`Subagent`), `skills` (`Skill`), `plugins` (`Plugin`), `mcp_servers` (`MCP`) by `pct` descending (stable), `fraction = pct / 100f`, `pct` `"<n>%"`; `tips` every recommendation in the CLI's order (none → empty, and the view leaves the section out; spec 16). `cliText = noticeText`; `cliTextOpen` when the session part is `Failed`.
  - `formatCost`: `usd <= 0` or NaN → `$0.00`; `< 0.005` → `<$0.01`; else `String.format(Locale.ROOT, "$%.2f", usd)`. `promptCacheLine`: the rest of the first line that starts with `Prompt cache (main):`, trimmed; null without one.
  - (spec 16) `costShare`: null when `costUsd` or `totalUsd` is null or NaN, or `totalUsd <= 0`; `share = (costUsd / totalUsd).coerceIn(0.0, 1.0)`; text `<1% of cost` when `0 < share < 0.005`, `>99% of cost` when `share >= 0.995`, else `"${round(100 * share)}% of cost"`. `tipsTitle`: `Tip` for 1, else `Tips`. `tipsToggle(count, open)`: null when `count <= TIPS_SHOWN`, else `Show ${count - TIPS_SHOWN} more` closed and `Show fewer` open.
  - `pluginMenu`: `plugins` null (no init yet; `composerModel` passes `state.plugins.takeIf { state.mcp.initCount > 0 }`) → one entry per distinct prefix of the catalogue's names `<prefix>:<rest>` with a non-empty rest, in the catalogue's order, version null (spec 3.3: the init comes only with the first turn, the `initialize` answer at spawn); else the non-built-in plugins in the init's order; a command belongs to plugin `p` when `name.substringBefore(':')` equals `p.name` exactly and the part after the first `:` is not empty; from the catalogue (`commands`) when it is not empty, else from `names` with description `""`; description without the `"(<plugin>) "` prefix. `withCommand`: `"$command " + draft.trimStart()`.
  - `ChatRowsBuilder.noticeRow`: `usage = entry.usage?.let { usageCard(it, entry.text, state.models, state.activeModel) }`, `markdown = entry.kind == LOCAL_COMMAND && entry.runCommand == "context"`, `sourceUuid = entry.sourceUuid`. Deviation 7: a `Frozen` turn keeps its usage notices (index and instance); `reopenReferencedTurns` reopens from the earliest frozen turn whose usage notice at that index is no longer the same instance.

- [ ] **Step 1: Write the failing tests** (spec 9.3; JUnit 5):

```kotlin
// ContextBreakdownModelTest (m = contextPopover(b17, null, null, null))
`rows follow the desktop's order with shares of the window`   // m.rows.map { Triple(name, tokens, share) } == [("System tools","21.4k","2.1%"), ("Messages","6.4k","0.6%"), ("Memory files","4.3k","0.4%"), ("Skills","3.2k","0.3%"), ("System prompt","2.8k","0.3%"), ("Autocompact buffer","33.0k","3.3%"), ("Free space","929.0k","92.9%"), ("System tools (deferred)","22.3k","—")]
`tones follow the category id`   // tones == [NEUTRAL, MESSAGES, MEMORY_FILES, NEUTRAL, SYSTEM_PROMPT, NEUTRAL, FREE, NEUTRAL]; a category with id "something_new" → NEUTRAL; rows[1].description == "Messages, 6.4k tokens, 0.6%", the last "System tools (deferred), 22.3k tokens, deferred"
`an unknown or missing kind sorts last in the CLI's order`   // b17 plus ("x", kind "later") and ("y", kind null) → they follow the deferred row, x then y
`zero-token rows are hidden`   // a category with tokens 0 → no row
`the bar has one segment per used category`   // segments.map { it.tone } == [NEUTRAL, MESSAGES, MEMORY_FILES, NEUTRAL, SYSTEM_PROMPT]; fractions[1] == 0.006448f
`the header reads the CLI's total, window and percentage`   // m.value == "38.0k / 1M (4%)"; contextPopover(null, ContextUsage(38032, 1_000_000), …).value == "38.0k / 1M (4%)"; neither → null; percentage null → computed "4%"
`a window of zero shows no shares and no bar`   // maxTokens 0 → every share "—", segments empty, value from the ring
`the MCP tools group names server and tool`   // group "mcp_tools": title "MCP tools", count 1, total "0", items == [GroupItem("echo · echo", "0", dim = true, tooltip = null)]; McpToolUsage("plain", "s", 5, true) → "s · plain"; serverName null → "plain"
`groups appear only with items and keep the CLI's order`   // groups.map { it.key } == ["mcp_tools", "memory_files", "skills"]; skills: count 36, total "3.2k", items[0].label == "actions", an item ("probe-kit:greet", "22")
`a group shows at most 100 items and counts the rest`   // 250 tools → items.size 100, more 150, count 250
`memory paths show the home as tilde and project files relative`   // displayPath("/home/u/.claude/CLAUDE.md", "/home/u", "/home/u/p") == "~/.claude/CLAUDE.md"; ("/home/u/p/CLAUDE.md", …) == "CLAUDE.md"; a memory item's tooltip "/home/u/p/CLAUDE.md · Project"
`a path that only shares a prefix with home or the project stays absolute`   // displayPath("/home/alex/x", "/home/al", null) == "/home/alex/x"; ("/p/app2/CLAUDE.md", null, "/p/app") == "/p/app2/CLAUDE.md"; ("/home/al", "/home/al", null) == "~"   (Review Focus 3)
`the instructions and agents groups follow the SDK's shape`   // docs-only shapes: b17.copy(mcpInstructions = [("echo", 26), ("docs", 22)], agents = [AgentUsage("reviewer", "projectSettings", 919)]) (token values taken from :17) → groups.map { it.key } == ["mcp_tools", "mcp_instructions", "memory_files", "skills", "agents"]; mcp_instructions: title "MCP server instructions", total "48", count 2, labels ["echo", "docs"]; agents: title "Custom agents", total "919", count 1, items[0] == GroupItem("reviewer", "919", dim = false, tooltip = "projectSettings")
`the popover status follows the fetch`   // popoverStatus(loading = true, null, false, hasRows = false) == PopoverStatus(null, "Loading the breakdown…", false, false); (true, null, false, true) → (" · Updating…", null, false, false); (false, "Could not load the breakdown: x", false, true) → (null, that text, error = true, dimRows = true) (rows kept under the error); the same without rows → dimRows false; (false, null, stopped = true, true) → (null, "The session is not running. The breakdown updates with the next prompt.", false, true); stopped and failed → the stopped line; all false → (null, null, false, false)
`percentOf has one decimal and a dash without a window`   // percentOf(3155, 1_000_000) == "0.3%", (120, 1_000_000) == "0.0%", (928968, 1_000_000) == "92.9%", (5, 0) == "—"
// UsageCardModelTest (models: ModelOption("default","Default (recommended)",null,"claude-fable-5-1"), ("sonnet","Sonnet 5.5",null,"claude-sonnet-5-5"), ("haiku","Haiku 5.5",null,"claude-haiku-5-5"), parity-usage:1's; text22 = :22's text)
`the session facts read cost, API and wall time`   // usageCard(UsageCard(Loaded(u16.session!!), Loading), text22, models, "claude-sonnet-5-5").facts == Shown([("Est. cost","$0.15"), ("API","4s"), ("Wall","7s")])
`formatCost rounds to cents and marks a sliver`   // 0.0 → "$0.00", 0.004 → "<$0.01", 0.005 → "$0.01", 0.1523063 → "$0.15", -1.0 → "$0.00"
`costShare marks a sliver and the whole`   // (spec 16) costShare(0.152182, 0.1523063)!!.second == ">99% of cost"; (0.0001243, 0.1523063) → "<1% of cost"; (0.05, 0.1) == (0.5f, "50% of cost"); (0.0, 0.15) == (0f, "0% of cost"); (null, 0.15), (0.1, null) and (0.1, 0.0) → null
`web searches show only above zero`   // (spec 16) u16.session with the sonnet model's webSearches = 3 (a mutated copy) → its block's rows end with UsageFact("Web searches", "3"); with 0 → no such row
`the prompt cache line comes from the CLI's text`   // promptCacheLine(text22) == "1 request · 0% of input tokens from cache · no misses · warm (1h TTL, last activity 5s ago)"; a text without it → null
`the active model comes first with the footer's label`   // models.map { Triple(it.label, it.cost, it.rows) } == [("Sonnet 5.5", "$0.15", [Input 2, Output 3, Cache read 0, Cache write 38.0k]), ("Haiku 5.5", "<$0.01", [Input 1.2k, Output 14, Cache read 0, Cache write 0])] (no `Web searches`: 0 at :16); (spec 16) models.map { it.costShareText } == [">99% of cost", "<1% of cost"]; activeModel null → Haiku first; an id no option resolves to → the id as label; the default entry (claude-fable-5-1) never labels
`behaviours use Tandem's words, else the CLI's recommendation title, else are hidden`   // day behaviors == [("100%","came from subagent-heavy sessions",true), ("100%","Stop sessions you no longer need",false), ("80%","Save workflows for big tasks",false), ("65%","came from sessions with a long context",true), ("11%","came from sessions with large tool results",true)]; week has ("19%","ran at max effort",true); a key "high_parallel" with no recommendation of that key → no line
`shares sort by percent with their kind`   // day shares == [("workflow-subagent","Subagent",0.8f,"80%"), ("server-a","MCP",0.02f,"2%"), ("server-b","MCP",0.02f,"2%")]; week has ("plugin-a","Plugin",0.01f,"1%")
`the caveat names the window, its sessions and its requests`   // (spec 16) "Last 24 hours · 23 sessions · 8.3k requests · approximate, overlapping measures · this machine only"; week "Last 7 days · 88 sessions · 19.7k requests · approximate, overlapping measures · this machine only"; sessionCount null → "Last 24 hours · 8.3k requests · approximate, overlapping measures · this machine only"; both null → "Last 24 hours · approximate, overlapping measures · this machine only"
`every tip of the window in the CLI's order`   // (spec 16) day tips.map { it.title } == ["Give simple subagents a cheaper model", "Stop sessions you no longer need", "Save workflows for big tasks", "Start fresh when you switch tasks", "Shorten the output of \"sed\""]; tips[0] == UsageTip("subagent_heavy", "Give simple subagents a cheaper model", null); tips[1].detail == "some ran 8 hours or more"; week tips.last().title == "Save max effort for hard tasks"; tipsTitle(1) == "Tip", tipsTitle(5) == "Tips"; tipsToggle(5, open = false) == "Show 2 more", tipsToggle(5, true) == "Show fewer", tipsToggle(3, false) == null
`history null says the CLI did not report it`   // Loaded(null) → Note("The CLI did not report what is using your limits.")
`a missing or empty window is marked empty`   // Loaded(UsageHistory(day = null, week = u17.history!!.week)) → Shown(null to <the week's model>); a window whose behaviours, recommendations and four share lists are all empty → null; a window whose only behaviour has no label and no recommendation → null
`absent facts, models and tips are left out`   // SessionUsage(null, 4192, null, emptyList()) → facts == Shown([("API", "4s")]), models empty (model_usage {}); all three facts null → Shown([]); the day window with tips empty → tips empty
`loading and error sections`   // (Loading, Loading) → facts Loading, history Loading, models empty; Rejected("boom") → Error("Could not load usage: boom", true); TimedOut(15) → Error("The CLI did not answer within 15 s.", true); Ended and NotRunning → Error("The session has ended.", false); history Rejected("x") → Error("Could not read the usage history: x", true); TimedOut(60) → "The CLI did not answer within 60 s."
`the CLI text opens when the session part failed`   // cliText == text22; cliTextOpen true with session Failed, false otherwise
// PluginMenuTest (init5 = :5, catalogue = initialize(:1).commands)
`built-in plugins are left out`   // pluginMenu(init5.plugins, catalogue, init5.slashCommands).map { it.name } == ["probe-kit"], version "0.1.0"
`a plugin lists its commands with the prefix stripped`   // commands == [PluginCommand("hello","/probe-kit:hello","Says hello from a plugin command"), PluginCommand("greet","/probe-kit:greet","Greets the user by name in one short sentence.")]
`without the catalogue the init's names stand in`   // catalogue empty → [("hello","/probe-kit:hello",""), ("greet","/probe-kit:greet","")]
`a plugin whose name prefixes another's lists only its own commands`   // plugins kit, kit-pro; commands "kit:a", "kit-pro:b", "kit:", "kitb" → kit [a], kit-pro [b]   (Review Focus 4)
`before an init the catalogue's prefixes stand in`   // pluginMenu(null, catalogue, emptyList()) == [PluginEntry("probe-kit", null, [hello, greet] as above)]; pluginMenu(null, emptyList(), emptyList()) is empty
`withCommand puts the command first`   // withCommand("", "/probe-kit:greet") == "/probe-kit:greet "; withCommand("  Ada ", "/probe-kit:greet") == "/probe-kit:greet Ada "
// ComposerModelTest
`the composer lists the session's plugins from the catalogue, then from the init`   // ChatState(commands = initialize(:1).commands) with no init → plugins.single() is "probe-kit", version null, two commands, pluginsKnown; after parity-add-dir:5 → version "0.1.0", pluginsKnown; ChatState() → empty, !pluginsKnown; a catalogue without a colon name and no init → empty, !pluginsKnown
// ChatRowsBuilderTest (replay with one builder, the prompt "hi" before each init)
`a usage notice row carries its card`   // parity-usage through :22: the notice row's usage != null, sourceUuid "<uuid-28>", markdown false
`a context notice is markdown`   // parity-context through :24: its notice row markdown == true, usage null
`other local notices stay plain`   // mcp-slash through :5: markdown false, usage null
`a usage card in an older turn updates when its answer arrives`   // parity-usage whole (three turns: the first two cached), then build(reduce(state, UsageFetched("<uuid-28>", HISTORY, Ok(u17)))) → the "<uuid-28>" row's usage!!.history is Shown   (Review Focus 1)
`Retry on an older card shows it loading again`   // then UsageRetry("<uuid-28>", HISTORY) → that row's history is Loading   (Review Focus 1)
`a usage card in an earlier frozen turn updates and later turns keep their rows`   // parity-usage whole, then two more model turns into the same state and builder (plain-reply's lines, the second time with every uuid and message id suffixed "-2" so nothing merges; a prompt before each init, as replay sends it), so the "<uuid-28>" card's turn is neither the last frozen turn nor ended by the card; rows = build(state); then build(reduce(state, UsageFetched("<uuid-28>", HISTORY, Ok(u17)))) → that row's usage!!.history is Shown, and every row of the later turns is the same instance (===) as in rows   (Review Focus 1, deviation 7)
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.*'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (`chat/` ≥ 90 %).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/chat && COMMIT -m "feat(chat): context popover, usage card and plugin menu models"`

---

### Task D4: ui-context-popover

**Depends on:** D3 (Opus; reviewer Opus). Carries the three popover IDE tests (spec 9.5: mouse, keyboard, error).

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/ContextBreakdownController.kt`, `.../ui/ContextPopover.kt`
- Modify: `.../ui/FooterControls.kt` (`ContextRing`, `RingControl`), `.../ui/TandemColors.kt`, `.../ui/PromptBar.kt` (builds the host), `.../ui/ChatScreen.kt` (the height), `.../ui/ChatPresenter.kt` (Markdown of a `markdown` notice), `.../ui/rows/NoticeItem.kt`, `.../ui/ChatPanel.kt` (passes `blocks`), `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/ui/ContextBreakdownControllerTest.kt`; Modify `.../ui/ChatPresenterTest.kt`

**Interfaces:**
- Consumes: D2a `ContextControls`, `ControlError`, `SessionManager.send`; D3 `contextPopover`, `ContextPopoverModel`, `ContextTone`.
- Produces (spec 4.4):
  ```kotlin
  internal sealed interface FetchState { data object Idle : FetchState; data object Loading : FetchState; data class Failed(val text: String) : FetchState; data object Stopped : FetchState }
  internal class ContextBreakdownController(id: SessionId, breakdown: StateFlow<ContextBreakdown?>, ring: StateFlow<ContextUsage?>, turnsEnded: StateFlow<Int>,
      status: StateFlow<SessionStatus>, controls: ContextControls, scope: CoroutineScope, home: String?, projectRoot: String?,
      dispatcher: CoroutineDispatcher = Dispatchers.Default) {
      val model: StateFlow<ContextPopoverModel>; val fetch: StateFlow<FetchState>; val rowsOpen: StateFlow<Boolean>; fun toggleRows(); fun retry(); fun close() }
  internal class ContextPopoverHost(val open: (CoroutineScope) -> ContextBreakdownController, val busy: StateFlow<Boolean>, val maxHeight: Dp, val onDetailed: () -> Unit, val onUsage: () -> Unit)   // onUsage: spec 16
  @Composable internal fun ContextRing(context: StateFlow<ContextUsage?>, popover: ContextPopoverHost? = null)
  internal object TandemColors { fun contextTone(tone: ContextTone, dark: Boolean): Color? }   // null for NEUTRAL and FREE (theme colours)
  @Composable internal fun NoticeItem(row: NoticeRow, blocks: ImmutableList<MarkdownBlock>? = null, onOpenMcp: () -> Unit = {})
  ```
- Controller rules (spec 3.1): every request on `dispatcher`, under a child `Job` of `scope` that `close()` cancels. `model = combine(breakdown, ring) { b, r -> contextPopover(b, r, home, projectRoot) }` built on `dispatcher`. Requests: one at construction unless `status` is `STOPPED`; one on every change of `turnsEnded` after its first value; one when `status` leaves `STOPPED`; `retry()`. While one runs, a new trigger runs once after it (at most one waiting). Answers: `Ok` → `Idle` (the reducer already holds the breakdown); `Failed(Rejected(m))` → `Failed("Could not load the breakdown: $m")`; `Failed(TimedOut(s))` → `Failed("The CLI did not answer within $s s.")`; `NotRunning`/`Ended` → `Stopped`; `status == STOPPED` → `Stopped`, no request. While a request runs, `fetch` is `Loading`. `rowsOpen` starts `false` (the popover opens compact, spec 3.1) and `toggleRows()` flips it.
- Popover (spec 3.1, 3.5, 3.7), the `PopupMenu` of `RingControl`'s `if (open)` branch, `Modifier.testTag("context-popover").semantics { contentDescription = "Context window breakdown" }`, width `min(360 dp, chat column − 32 dp)` (at least 280 dp), height capped at `popover.maxHeight`; its status is `popoverStatus(fetch is Loading, (fetch as? Failed)?.text, fetch is Stopped, model.rows.isNotEmpty())` (D3), never decided in the composable: the header row (`Context window` dim, then `model.value` plus `headerSuffix`, then `›` / `⌄`) is a `selectableItem` that toggles `rowsOpen` and keeps the popover open (the hold-open flag below), `contentDescription = "Context window categories"`, `stateDescription` `Expanded` / `Collapsed`; under it `line` when set, in `text.error` with a `Retry` item when `error`; the bar; the header row, the status line, the bar and `See detailed breakdown` show in both states, and only while `rowsOpen` the category rows (dimmed when `dimRows`) and the groups below; the bar (`Canvas`, 4 dp, rounded, track `dim` at 30 %, segments in `contextTone` or `dim`, each non-zero one at least 1 px); passive category rows (`semantics(mergeDescendants = true) { contentDescription = row.description }`, swatch, name, tokens, share); groups collapsed on open, each a `selectableItem` whose click toggles it and keeps the popover open (a hold-open flag read by `onDismissRequest`, as `FooterControl`'s `turning`), `›` / `⌄`, title, total, count, `contentDescription = "<title>, <count> items, <total> tokens"`, `stateDescription` `Expanded` / `Collapsed`; expanded items passive, dim when `item.dim`, tooltip when set, then `and N more` when `more > 0`; then a separator and `See detailed breakdown` (`See detailed breakdown (after this turn)`, disabled, while `busy`), which closes the popover and calls `onDetailed`; under it (spec 16.2) `Show session usage` (`Show session usage (after this turn)`, disabled, while `busy`), which closes the popover and calls `onUsage`. The ring's `stateDescription` is the header value. If Jewel's menu does not honour the height cap with scrolling (spec 11 item 6), the category rows and groups go into one passive item holding a `VerticallyScrollableContainer`, `Retry`, `See detailed breakdown` and `Show session usage` stay selectable items outside it; say which in the commit body.
- Wiring: `PromptBar` passes `ContextPopoverHost(open = { scope -> ContextBreakdownController(handle.id, handle.state.map { it.contextBreakdown }…, presenter.context, handle.state.map { it.turnsEnded }…, handle.status, manager, scope, System.getProperty("user.home"), project.basePath) }, busy = presenter.busy, maxHeight = 60 % of the tool window, onDetailed = { manager.send(handle.id, "/context") }, onUsage = { manager.send(handle.id, "/usage") })` (the draft and the attachments untouched); `ChatScreen` measures the tool window's height and passes it to `PromptBar`. `ChatPresenter.renderRow`: a `NoticeRow` with `markdown` gets `RenderedRow(row, blocks(row.key, row.text))` (parsed off the EDT, cached by key and text), every other notice `blocks = null`; `NoticeItem` draws `Markdown(blocks, …)` as `TextRowItem` does when `blocks != null`, keeping the `Reply to /context` semantics.
- IDE harness: none new.

- [ ] **Step 1: Write the failing tests** (spec 9.4, 9.5; `ContextBreakdownControllerTest` JUnit 5 under `runBlocking { withTimeout(10_000) { … } }`, a `RecordingControls : ContextControls` whose answers the test sets and which counts calls and can hold a call until released, `MutableStateFlow`s, `dispatcher = Dispatchers.Default`):

```kotlin
// ContextBreakdownControllerTest
`one request on start`   // construction with status IDLE → 1 call
`one request per turn end while open`   // turnsEnded 0 → 1 → 2 → 3 calls
`none after close`   // close(); turnsEnded++ → still the same count
`an error shows the CLI's text`   // Failed(Rejected("Unsupported control request subtype: get_context_usage")) → fetch == Failed("Could not load the breakdown: Unsupported control request subtype: get_context_usage"); TimedOut(30) → Failed("The CLI did not answer within 30 s."); Rejected("the CLI's answer had no categories") → Failed("Could not load the breakdown: the CLI's answer had no categories")
`stopped while the session is stopped, and a request when it starts`   // status STOPPED → fetch Stopped, 0 calls; status IDLE → 1 call
`a refresh during a request runs once after it`   // first call held; turnsEnded++ twice; release → 2 calls in all
`retry asks again`   // retry() → one more call
`the fetch is loading while a request runs`   // the first call held → fetch.value == Loading; released with Ok → Idle; the next call answers Failed(Rejected("x")) → Failed("Could not load the breakdown: x"); retry() with the call held → Loading; released with Ok → Idle
`the category list opens collapsed and toggles`   // rowsOpen.value == false after construction; toggleRows() → true; toggleRows() → false
`the model follows the breakdown and the ring`   // breakdown = b17 → model.value == "38.0k / 1M (4%)", rows.size == 8; breakdown null, ring ContextUsage(38032, 1_000_000) → value "38.0k / 1M (4%)", rows empty
// ChatPresenterTest
`a context notice's markdown is parsed off the EDT`   // a state with a LOCAL_COMMAND notice whose runCommand is "context" → its RenderedRow.blocks is non-null and not empty; a "usage" notice → blocks == null
// ToolWindowIdeTest (fixture parity-context)
`the context ring opens the breakdown`   // newSession(); enterPrompt("hi"); waitForTurnEnd(); ui.x("//div[@class='ComposeNode' and @contentdescription='Context window']").click(Point(10,10)); waitFor described("Context window categories"), and no node starts-with(@contentdescription, 'Messages,') (collapsed on open); click it at Point(10,10); waitFor a node starts-with(@contentdescription, 'Messages,'); takeScreenshot("context-popover"); the log holds {"subtype":"get_context_usage"}; ui.keyboard { escape() } → described("Context window breakdown") gone
`the context popover works from the keyboard`   // fixture parity-context: newSession(); enterPrompt("hi"); waitForTurnEnd(); focusPromptBox(), then Tab or Shift+Tab, as the footer's order has it (say which in the commit body), until the "Context window" node has @focused='true'; Enter → described("Context window breakdown") present; Down until the header row is selected (say in the commit body where Jewel's selection starts), Enter → a node starts-with(@contentdescription, 'Messages,') appears and described("Context window breakdown") is still present (the hold-open flag); Esc → the popover gone and the "Context window" node @focused='true'
`the context popover shows the CLI's error`   // fixture plain-reply (rule (h) answers {"subtype":"error","error":"Unsupported control request subtype: get_context_usage"}): newSession(); enterPrompt("hi"); waitForTurnEnd(); click the ring at Point(10,10); waitFor a node starts-with(@visible_text, 'Could not load the breakdown: Unsupported control request subtype') and a "Retry" item; takeScreenshot("context-popover-error")
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.*'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS; `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → PASS (the three new tests and every earlier one; `local commands show as notices without a footer` still passes: its `/context` prompt gets `/model`'s recorded reply, so no Markdown notice there). Look at the `context-popover` screenshot (taken after the header row expanded the list): header, bar, eight rows, two collapsed groups (`Memory files`, `Skills`: rule (h) serves `parity-context:2`, whose `mcpTools` is empty), `See detailed breakdown`, `Show session usage`.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ui src/integrationTest && COMMIT -m "feat(ui): context window breakdown in the ring's popover"`

---

### Task D5: ui-usage-card

**Depends on:** D4 (Opus; reviewer Opus). Carries IDE test 2.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/rows/UsageCardView.kt`
- Modify: `.../ui/rows/NoticeItem.kt`, `.../ui/ChatPanel.kt` (and the caller that wires `onOpenMcp` today, which gains `onUsageRetry`), `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: the IDE test only (the card's model is D3's, its requests D2a's).

**Interfaces:**
- Consumes: D3 `UsageCardModel`, `UsageSection`, `USAGE_LOADING`, `HISTORY_LOADING`, `WINDOW_EMPTY`, `NoticeRow.usage/sourceUuid`; D2a `SessionManager.retryUsage`, `UsagePart`.
- Produces:
  ```kotlin
  @Composable internal fun UsageCardView(model: UsageCardModel, key: String, onRetry: (UsagePart) -> Unit)
  @Composable internal fun NoticeItem(row: NoticeRow, blocks: ImmutableList<MarkdownBlock>? = null, onOpenMcp: () -> Unit = {}, onUsageRetry: (uuid: String, part: UsagePart) -> Unit = { _, _ -> })
  ```
- Rules (spec 3.2, 3.5, 3.7): `NoticeItem` draws `UsageCardView(row.usage, row.key) { part -> onUsageRetry(row.sourceUuid!!, part) }` in place of the text when `row.usage != null && row.sourceUuid != null`; the wiring calls `manager.retryUsage(handle.id, uuid, part)`. Such a row is not merged: today `NoticeItem` merges every LOCAL_COMMAND row into one node (`semantics(mergeDescendants = true) { contentDescription = "Reply to /…" }`); with `row.usage != null` its `Reply to /<command>` description sits on a container that does not merge (`semantics { … }` without `mergeDescendants`), so the card, the window control, each `Retry` and the disclosure stay their own nodes; every other notice keeps today's merge. The card: a bordered box in the chat column (6 dp radius, 12 dp padding, the polish cards' frame), `testTag("usage-card")`, `contentDescription = "Usage for this session"` (not merged), text in a `SelectionContainer`, every section stacked and wrapping. `This session`: the facts as one wrapping line of `label value` pairs (left out when the list is empty), then `Prompt cache` and `promptCache` when set; `Loading` → `USAGE_LOADING`; `Error` → its text in `text.error` with `Retry` (→ `onRetry(SESSION)`) when `retry`. `Breakdown` (left out when `models` is empty): per `ModelBlock` its label and cost, then (spec 16.2) a 4 dp bar of `costShare` with `costShareText` when set, then the rows (`Web searches` among them when present). `What's using your limits?`: `SegmentedControl` `24h` | `7d` (`contentDescription = "Usage window"`, the choice `remember(key)`, `24h` first), the window's caveat, behaviour lines (`<pct>` then the label, plain text), share rows (name, kind dim, a 4 dp bar of `fraction`, `pct`), (spec 16.2) `tipsTitle(tips.size)` and the first `TIPS_SHOWN` tips (every one while open), each its title and, under it in the dim tier, its detail when present, plain text, then `tipsToggle` as a disclosure row (`remember(key)`, `stateDescription` `Expanded` / `Collapsed`), the section left out when `tips` is empty; a null window (the model marks an absent or empty one, D3) → `WINDOW_EMPTY`; `Loading` → `HISTORY_LOADING`; `Error` with `Retry` (→ `onRetry(HISTORY)`); `Note` → its text. `CLI output`: a disclosure row (`›` / `⌄`, `stateDescription` `Expanded` / `Collapsed`, open initially when `cliTextOpen`, `remember(key)`) showing `cliText` plain in the editor style. Nothing in the card takes the focus by itself.
- IDE harness: `waitForTextContaining(text)` beside `waitForText` (which matches `@visible_text` whole): `contains(@visible_text, '$text')`, for a part of a wrapping line.

- [ ] **Step 1: Write the failing test** (spec 9.5):

```kotlin
// ToolWindowIdeTest (fixture parity-usage)
`usage shows a card`   // newSession(); enterPrompt("hi"); waitForTurnEnd()   (the fixture's first turn is a model reply)
                       // enterPrompt("/usage"); waitFor described("Usage for this session"); waitForTextContaining("Est. cost $0.15"); waitForTextContaining(">99% of cost"); waitForText("Last 24 hours · 23 sessions · 8.3k requests · approximate, overlapping measures · this machine only"); (spec 16) no text "Start fresh when you switch tasks"; click "Show 2 more" at Point(10,10) → waitForText("Start fresh when you switch tasks") and "Show fewer" present
                       // click the "7d" segment at Point(10,10); waitForText("Last 7 days · 88 sessions · 19.7k requests · approximate, overlapping measures · this machine only"); takeScreenshot("usage-card")
                       // (spec 16) click the ring (drawn since turn one's result) at Point(10,10), then menuItem("Show session usage").click() → the popover closes and a second node described "Usage for this session" appears (the fake replays the /cost turn, whose local command is usage, parity-usage:28); the log's last user frame's text is "/usage"
                       // the log's get_usage frames, in order: with "skip_behaviors":true, then without it, then the same pair for the second card
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.*'` → PASS (nothing new there), then `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → the new test fails (no card node).
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS; `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → PASS. Look at the `usage-card` screenshot at the test's tool-window width: nothing side by side overflows.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ui src/integrationTest && COMMIT -m "feat(ui): the /usage card in the chat"`

---

### Task D6a: ui-plus-menu

**Depends on:** D5 (Opus; reviewer Opus). Carries IDE tests 3 and 5 and the keyboard variant of 3.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/PagedMenu.kt`
- Modify: `.../ui/FooterControls.kt` (`FooterControl` uses the helper), `.../ui/PromptBar.kt` (`AddButton`, `PromptBox`), `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/ui/PagedMenuTest.kt`; `FooterControlsTest` unchanged and green.

**Interfaces:**
- Consumes: D3 `ComposerModel.plugins/pluginsKnown`, `PluginEntry`, `PluginCommand`, `withCommand`; `ChatUiState.mcpPanelOpen`; `FooterMenuHandle`.
- Produces (spec 3.3, 3.5):
  ```kotlin
  internal class PagedMenuState { val open: Boolean; val page: Int; fun openAt(page: Int = 0); fun close(); fun show(page: Int) }   // show: holds the menu open through Jewel's close request after the click
  @Composable internal fun PagedMenuKeys(state: PagedMenuState, panel: JComponent, turn: (AwtKeyEvent, Int) -> Int?, digitPick: ((Int) -> Boolean)? = null)
  //   while state.open: the AWT KeyEventDispatcher (digits on page 0 through digitPick, arrows through turn, keys of `panel` only: takesKey)
  //   and the focus listener that closes the menu when the focus leaves `panel` (focusLeft): FooterControl's code, moved
  internal fun PagedMenuState.dismiss(mode: InputMode, anchorHovered: Boolean): Boolean   // PopupMenu's onDismissRequest
  internal fun plusMenuTurn(event: AwtKeyEvent, page: Int): Int?   // → on page 0 → 1; ← on 1 → 0, on ≥ 2 → 1; anything else, or with a modifier, null
  ```
  `pageTurn(event, page)` stays as it is; `FooterControl` passes `{ e, p -> pageTurn(e, p)?.takeIf { it in 0 until pages } }`.
- Menu (spec 3.3, 3.5, 3.7): `+` keeps its place, size and `Add` description; its `PopupMenu` above it, left-aligned. Page 0: `Add files or photos` (`Actions.Attach`, unchanged), `Slash commands` (unchanged), `Connectors` (`Webreferences.Server`; closes the menu, `ui.mcpPanelOpen.value = true`), `Plugins` with `›` (`Nodes.Plugin`; `show(1)`); `Add folder` joins in D6b, after `Add files or photos`. Page 1: `‹ Plugins` (`show(0)`), a separator, one row per `PluginEntry` (`contentDescription = "<plugin> plugin"`, the name verbatim, `v<version>` dim when set, `›` when it has commands; a click or Enter on a row with commands `show(2 + index)`), a separator, the dim passive line `Add or manage plugins with /plugin in a terminal.`; with none: `No plugins in this session.`; with `!pluginsKnown` (no init and no plugin command in the catalogue): `Plugins show after the first prompt.`; before an init the rows are the catalogue's, without a version (D3) Page `2 + i`: `‹ <plugin>` (`show(1)`), a separator, one row per command (`contentDescription = "/<plugin>:<name>"`, the name, under it the description in the footer size and the dim tier, at most two lines, the full description in the tooltip); a pick closes the menu, sets `ui.draft.setTextAndPlaceCursorAtEnd(withCommand(ui.draft.text.toString(), command.command))` and focuses the text area; nothing is sent. Esc closes and the focus returns to `+`.
- IDE harness: none new.

- [ ] **Step 1: Write the failing tests** (spec 9.4, 9.5):

```kotlin
// PagedMenuTest (JUnit 5; key events built as FooterControlsTest builds them)
`right on the first page opens the plugins page`   // plusMenuTurn(VK_RIGHT pressed, 0) == 1
`left goes back to the parent page`   // (VK_LEFT, 1) == 0; (VK_LEFT, 3) == 1; (VK_LEFT, 0) == null
`right on a plugin list does nothing`   // (VK_RIGHT, 1) == null; (VK_RIGHT, 2) == null
`a modifier or a release turns nothing`   // Shift+VK_RIGHT pressed → null; VK_RIGHT released → null
`the plus menu takes keys from its own panel alone and closes when the focus leaves it`   // as FooterControlsTest's `a footer menu takes keys from its own panel alone and closes when the focus leaves it`, on PagedMenuKeys with plusMenuTurn: a VK_RIGHT from another component → page unchanged; from the panel → page 1; the focus leaving the panel → closed
// ToolWindowIdeTest
`the plus menu inserts a plugin command`   // fixture parity-add-dir, before any prompt (spec 3.3: the fake answers initialize at spawn, rule (a), and no init comes until a turn starts): newSession(); wait for the "__spawn__" line; iconButton("Add").click(Point(10,10)); menuItem("Plugins").click(); waitFor described("probe-kit plugin") (the catalogue's row) with no text "v0.1.0"; described("probe-kit plugin").click(Point(10,10)); described("/probe-kit:greet").click(Point(10,10)); waitFor the prompt box's editabletext == "/probe-kit:greet "; takeScreenshot("plus-menu"); the log holds no "user" frame
`the plus menu works from the keyboard`   // fixture parity-add-dir: newSession(); enterPrompt("hi"); waitForTurnEnd(); focusPromptBox(), then Shift+Tab until iconButton("Add") has @focused='true' (`+` comes before the text area, spec 3.5); Enter → menuItem("Add files or photos") present; → (VK_RIGHT) → "‹ Plugins" present, with the text "v0.1.0" (the init's row); Down to the `probe-kit plugin` row (count the Downs from `‹ Plugins`; say in the commit body where Jewel's selection starts), Enter → "‹ probe-kit" present; ← → "‹ Plugins" again; Enter on `probe-kit plugin` again, Down to `/probe-kit:greet`, Enter → the menu gone, the prompt box's editabletext == "/probe-kit:greet " and $PROMPT_BOX[@focused='true']; then Shift+Tab back to `+`, Enter, Esc → no menu item present and iconButton("Add") focused; no "user" frame after the first prompt
`Connectors opens the MCP panel`   // fixture mcp-status: newSession(); wait for "__spawn__"; iconButton("Add").click(Point(10,10)); menuItem("Connectors").click(); waitFor described("MCP servers panel")
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ui.*'` → compilation errors (`plusMenuTurn`).
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS (`FooterControlsTest` included, unchanged); `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS; `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → PASS (the footer menus' test `the footer menus send set_permission_mode and set_model` included: the helper moved, the behaviour did not).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ui src/integrationTest && COMMIT -m "feat(ui): paged + menu with connectors and the session's plugins"`

---

### Task D11: chat-ui-queue

**Depends on:** D10, D6a (`PromptBar`, `ComposerKeys`), D8's committed `queue-cancel-steer` outcome (Opus; reviewer Opus). Carries spec 9.7's IDE tests 1 and 2 (ide). Spec 12.1 to 12.5 (what the user sees), 12.7.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/chat/QueuedSends.kt`, `.../ui/rows/QueuedRowItem.kt`
- Modify: `.../chat/ComposerModel.kt`, `.../chat/ChatRow.kt` (`QueuedRow`, `UserRow.caption`), `.../chat/ChatRowsBuilder.kt`, `.../chat/SlashPalette.kt` (`pick`, `PaletteOutcome.Send`), `.../ui/ComposerKeys.kt`, `.../ui/ComposerSender.kt`, `.../ui/ChatPresenters.kt` (the sender passes `later` to `manager.send`), `.../ui/PromptBar.kt`, `.../ui/ChatPanel.kt` (the `QueuedRow` branch), `.../ui/rows/UserBubble.kt` (the caption), `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/chat/QueuedSendsTest.kt`; Modify `.../chat/ComposerModelTest.kt`, `.../chat/ChatRowsBuilderTest.kt`, `.../chat/SlashPaletteTest.kt`, `.../ui/ComposerKeysTest.kt`, `.../ui/ComposerSenderTest.kt`, `.../ui/ChatUiStateTest.kt`

**Interfaces:**
- Consumes: D10 `PendingSend`, `PendingStage`, `ReturnedSend`, `ChatState.pendingSends/returnedSends/cliCapabilities`, `ChatEntry.User.folded`, `SessionManager.send(…, later)`, `withdraw`, `takeReturned`.
- Produces (spec 12.7; deviation 18):
  ```kotlin
  // chat/ComposerModel.kt
  ComposerModel(+ midTurnSends: Boolean = false, capabilitiesKnown: Boolean = false, returned: ImmutableList<ReturnedSend> = persistentListOf())
  //   busy = state.busy || state.pendingSends.isNotEmpty(); capabilitiesKnown = state.cliCapabilities != null;
  //   midTurnSends = state.cliCapabilities?.containsAll(listOf("msg_lifecycle_v1", "interrupt_cancel_queued_v1")) == true && !state.ended
  // chat/QueuedSends.kt
  fun sendsLater(text: String, imageCount: Int, busy: Boolean, queueKey: Boolean): Boolean   // busy && (queueKey || text.startsWith("/") || imageCount > 0)
  fun composerPlaceholder(model: ComposerModel, mac: Boolean): String
  fun busyRefusal(model: ComposerModel, shell: Boolean): String?
  fun queuedCaption(send: PendingSend): String
  fun sendNowTooltip(mac: Boolean): String
  const val FOLDED_CAPTION: String = "Sent while Claude worked"
  // chat/ChatRow.kt
  data class QueuedRow(override val key: String, val text: String, val images: ImmutableList<ImageRef>, val caption: String,
      val withdrawable: Boolean, val withdrawing: Boolean, override val gap: RowGap = RowGap.AFTER_BUBBLE) : ChatRow   // key "q:<uuid>"
  UserRow(+ caption: String? = null)
  // chat/SlashPalette.kt
  PaletteOutcome.Send(val text: String, val later: Boolean = false)
  fun pick(palette: SlashPalette, insert: Boolean, busy: Boolean, models: List<ModelOption>, midTurn: Boolean = false): PaletteOutcome
  // ui/ComposerKeys.kt
  ComposerAction.QueueLater; ComposerAction.BusyRefused
  internal fun composerKey(…, midTurn: Boolean = false, …): ComposerAction?   // a new parameter beside busy
  internal fun sendDraft(ui: ChatUiState, sender: ComposerSender, later: Boolean = false): Boolean
  internal suspend fun restoreReturned(ui: ChatUiState, returned: List<ReturnedSend>, attach: (mediaType: String, bytes: ByteArray) -> Unit)
  // ui/ComposerSender.kt
  CapturedPrompt(val text: String, val images: List<CapturedImage>, val later: Boolean = false)
  ComposerSender(…, send: (String, List<ImageBlock>, Boolean) -> Unit, …)   // the channel keeps the order across encodes, as today
  // ui/rows/QueuedRowItem.kt
  @Composable internal fun QueuedRowItem(row: QueuedRow, onWithdraw: (restore: Boolean) -> Unit)
  ```
- Rules (spec 12.1 to 12.5):
  - `composerKey`'s Enter branch: `shift || alt || cardOpen` → null; `paletteOpen && ctrlOrMeta` → null; `!busy` → `Send`; `!midTurn` → `BusyRefused`; `ctrlOrMeta` → `QueueLater`; else `Send`. `composerFallback` keeps `Swallow` for a plain Enter with a card open; a busy Enter no longer reaches it. Ctrl and Meta are both accepted on every OS.
  - `PromptBar`: `Send` → `sendDraft(ui, sender, later = sendsLater(text, attachments.size, composer.busy, queueKey = false))`; `QueueLater` → `sendDraft(ui, sender, later = true)`; `BusyRefused` → `ui.composerNotice.value = busyRefusal(composer, shell = false)`; a `!` draft while `composer.busy` → `busyRefusal(composer, shell = true)` and nothing runs (MCP 12.6's silent swallow, now said); the four intercepted commands act at once, busy or not, as today. The placeholder is `composerPlaceholder(composer, SystemInfo.isMac)`. While busy with `midTurnSends` and `canSend`, an icon button `Send` (`contentDescription = "Send"`, tooltip `sendNowTooltip(SystemInfo.isMac)`) sits left of Stop, a click acting as Enter. A palette pick passes `midTurn = composer.midTurnSends`; its `Send(text, later)` goes out through the sender with `later`. A `LaunchedEffect(handle.id)` collects `composer.returned`: when not empty, `restoreReturned(ui, returned, attach)`, then `manager.takeReturned(handle.id, returned.map { it.uuid })`. The context popover's `busy` and the reply row's `busy` (both the presenter's `busy`, which follows `ComposerModel.busy`) now count waiting messages too (spec 13.1, 16.2).
  - `restoreReturned`: the returned texts in order, joined by a blank line, ahead of the current draft (a blank line between them and the draft; none when the draft is empty), the cursor at the end; each image's base64 decoded on `Dispatchers.Default`, then attached once through `attach` on the caller's context (the EDT; hard rule 1).
  - `ChatRowsBuilder`: after the working row, one `QueuedRow` per `pendingSends` entry, in order: key `q:<uuid>`, images as `UserRow` makes them, `caption = queuedCaption(send)`, `withdrawable = send.later` (every send when D8's `queue-cancel-steer` outcome is **withdrawable**), `withdrawing = stage == WITHDRAWING`. A folded `User` is a `UserRow` with `caption = FOLDED_CAPTION` and starts no turn: a turn splits only at a `User` that is not folded, so one CLI turn is one visual turn with one footer. `withUniqueKey` and row reuse cover `QueuedRow`; once started, the message's row is the `UserRow` with the user row's own key.
  - `queuedCaption`: `WITHDRAWING` → `Withdrawing…`; `HELD` → `Waits for the shell command's output to go out`; `QUEUED` and `later` → `Queued · sends when Claude is done`; `QUEUED` and not `later` → `Sent · Claude reads it at its next step`.
  - `composerPlaceholder`: busy with `midTurnSends` → `↵ to send, ⌘↵ to queue for later` on macOS, `Enter to send, Ctrl+Enter to queue for later` elsewhere; busy without → `Claude is working · Esc to stop`; idle → `Type / for commands`.
  - `busyRefusal`: not busy → null; `shell` → `Shell commands run when Claude is done: wait for the reply, or press Esc to stop.`; `!capabilitiesKnown` → `Claude is still starting: send again in a moment.`; `!midTurnSends` → `This claude CLI does not take messages while it works: wait for the reply, or press Esc to stop.`; else null.
  - `sendNowTooltip`: `Send now · ⌘↵ queues it for after this turn` on macOS, `Send now · Ctrl+Enter queues it for after this turn` elsewhere.
  - `pick`: busy with `midTurn` → what an idle pick sends, with `later = true` (spec 12.1); busy without → `Ignored`, as today; intercepted rows and Tab's insert unchanged.
  - `QueuedRowItem`: the user bubble's frame at the dim tier with its text and thumbnails, the caption under it in the footer size; when `withdrawable`, two 16 dp icon buttons `Edit queued message` (→ `onWithdraw(true)`) and `Cancel queued message` (→ `onWithdraw(false)`), disabled while `withdrawing`; `testTag("queued-row")`; the text and caption one merged node, each button its own; nothing takes the focus by itself. `ChatPanel` wires `onWithdraw = { manager.withdraw(handle.id, uuid, it) }`; `UserBubble` draws `caption` under the bubble in the dim tier when set.
- IDE harness: none new (`waitForTextContaining` is D5's).

- [ ] **Step 1: Write the failing tests** (spec 9.7 `chat/` and `ui/`, IDE tests 1 and 2):

```kotlin
// QueuedSendsTest (JUnit 5)
`sendsLater is busy with the queue key, a slash command or an image`   // ("hi", 0, busy = false, queueKey = true) → false; ("hi", 0, true, false) → false; ("hi", 0, true, true) → true; ("/usage", 0, true, false) → true; ("hi", 1, true, false) → true; ("/usage", 0, false, false) → false
`the placeholder follows busy, mid-turn sends and the OS`   // busy + midTurnSends, mac → "↵ to send, ⌘↵ to queue for later", else "Enter to send, Ctrl+Enter to queue for later"; busy without → "Claude is working · Esc to stop"; idle → "Type / for commands"
`a busy Enter that cannot send says why`   // !capabilitiesKnown → "Claude is still starting: send again in a moment."; known without midTurnSends → "This claude CLI does not take messages while it works: wait for the reply, or press Esc to stop."; shell while busy → "Shell commands run when Claude is done: wait for the reply, or press Esc to stop." (with midTurnSends too); midTurnSends, not shell → null; idle → null
`captions follow the stage and the kind`   // QUEUED steer → "Sent · Claude reads it at its next step"; QUEUED later → "Queued · sends when Claude is done"; HELD → "Waits for the shell command's output to go out"; WITHDRAWING → "Withdrawing…"
`the Send tooltip names the queue key`   // sendNowTooltip(true) == "Send now · ⌘↵ queues it for after this turn"; (false) == "Send now · Ctrl+Enter queues it for after this turn"
// ComposerModelTest
`midTurnSends needs both capabilities and a live process`   // after queue-fold:6 → midTurnSends, capabilitiesKnown; after plain-reply:4 → midTurnSends; queue-fold:6 with "interrupt_cancel_queued_v1" removed → !midTurnSends, capabilitiesKnown; ChatState() → !midTurnSends, !capabilitiesKnown; after Ended → !midTurnSends
`busy counts waiting messages`   // ChatState(busy = false, pendingSends = [a QUEUED later send]) → busy; returned == the state's returnedSends
// ChatRowsBuilderTest
`waiting messages draw QueuedRows after the working row, in order`   // a running turn with pendingSends [s1 QUEUED steer, s2 QUEUED later, s3 HELD later, s4 WITHDRAWING later] → after the WorkingRow: keys "q:s1".."q:s4", their captions as above, withdrawable [false, true, true, true] (all true under D8's withdrawable outcome), withdrawing only for s4
`a folded prompt has its caption and the turn one footer`   // queue-fold replayed with the host's sends (as D10's ReducerTest) → one UserRow with caption "Sent while Claude worked" and text "Also say the word KIWI at the end.", after the first Bash call's row; exactly one turn footer row; the prompt's UserRow caption null
`queue-later has two footers`   // queue-later with its sends → two footer rows; F's UserRow caption null
`the key moves from the queued row to the user row once started`   // queue-later through :19 → a QueuedRow "q:<command_uuid-25>"; through :53 → no QueuedRow, a UserRow for F
// SlashPaletteTest
`a busy pick with mid-turn sends sends the command later`   // a row that sends at once: pick(…, busy = true, midTurn = true) == Send("/<name>", later = true); busy, midTurn false → Ignored; idle → Send("/<name>", later = false)
// ComposerKeysTest (JUnit 5)
`busy with mid-turn sends, Enter sends and Ctrl or Cmd+Enter queues`   // composerKey(Enter, busy = true, midTurn = true) == Send; with ctrlOrMeta (Ctrl and Meta alike) → QueueLater
`busy without mid-turn sends, Enter is refused`   // == BusyRefused
`idle, Ctrl or Cmd+Enter sends`   // == Send
`a card open takes neither Enter`   // cardOpen, busy or not, with or without ctrlOrMeta → null; composerFallback(plain Enter, cardOpen = true) == Swallow
`the palette open, Ctrl or Cmd+Enter does nothing and Enter picks`   // ctrlOrMeta → null; plain Enter → PalettePick
`Shift+Enter stays a newline and Alt+Enter is left alone`   // composerKey null for both; composerFallback(Shift+Enter) == Newline, (Alt+Enter) == null
// ComposerSenderTest
`later travels with its prompt and the order holds across encodes`   // submit(A with an image), submit(B, later = true) → send called with (A, …, false), then (B, …, true)
// ChatUiStateTest
`restoreReturned puts the texts ahead of the draft and re-attaches the images once`   // draft "draft"; returned [("a", "first", []), ("b", "second", [one PNG])] → the draft reads "first\n\nsecond\n\ndraft", the cursor at the end; attach called once, with ("image/png", the decoded bytes)
// ToolWindowIdeTest (ide)
`send while Claude works`   // fixture queue-fold (rule (m)), TANDEM_FAKE_QUEUED_DELAY_MS 1500: newSession(); enterPrompt("Run the two commands"); waitForTextContaining("sleep 6") (the Bash row; the fake waits at :19 for F); enterPrompt("Also say the word KIWI at the end.") (the composer clears); waitForText("Sent · Claude reads it at its next step"); takeScreenshot("queued-steer"); waitForText("Sent while Claude worked"); waitForTurnEnd(); exactly one node whose @visible_text starts with "Worked for"; the log's user frames: two, the second without "priority"
`queue for later and edit`   // fixture queue-cancel (its first turn): newSession(); enterPrompt("Run the command"); waitForTextContaining("sleep 8"); type "Reply with only the word KIWI." into the prompt box, then ui.keyboard { hotKey(if (SystemInfo.isMac) VK_META else VK_CONTROL, VK_ENTER) }; the prompt box clears; waitForText("Queued · sends when Claude is done"); takeScreenshot("queued-later"); iconButton("Edit queued message").click(Point(10,10)); the prompt box's editabletext == "Reply with only the word KIWI."; waitForTurnEnd(); the log: the second user frame has "priority":"later", then a cancel_async_message whose message_uuid is that frame's uuid, and no third user frame (also the check that the keymap lets Ctrl/Cmd+Enter reach the composer, 11 item 10)
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.*' --tests 'dev.daarkolabs.tandem.ui.*'` → compilation errors (`QueuedRow`, `QueueLater`, `sendsLater`, …).
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (`chat/` ≥ 90 %); `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → PASS (the two new tests and every earlier one: `waitForTurnEnd` still waits for Send without Stop, and an empty draft shows no busy Send). Look at `queued-steer` and `queued-later`: the bubble dimmed, its caption, the buttons only on the later one (unless D8's outcome is withdrawable).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/ui src/integrationTest && COMMIT -m "feat(ui): send while Claude works, queue for later with Ctrl/Cmd+Enter, and edit or cancel a queued message"`

---

### Task D12b: chat-ui-fork-at

**Depends on:** D12a, D11 (`ChatRowsBuilder`, `ChatPresenter`), D8's committed `fork-at-live` outcome (Opus; reviewer Opus). Carries spec 9.7's IDE test 3 (ide). Spec 13.1 (States and tooltip), 13.2 (the chat and ui part). With D8's outcome **refused or unlike**, `forkState` takes `liveOriginal: Boolean` and gives `WAIT` while it is true (the presenter knows the session's status); everything else stands.

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/chat/ChatRow.kt` (`MessageActions.forkAt`, `ForkState`, `forkState`), `.../chat/ChatRowsBuilder.kt` (`forkAt` per reply; `ChatRows.latestReply` and `latestReply()` removed), `.../ui/ChatPresenter.kt` (`latestReply` removed, `RenderedChat`), `.../ui/rows/MessageActionsRow.kt` (`ReplyHost`, the tooltip), `.../ui/ChatScreen.kt`, `.../ui/SessionActions.kt`, `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/chat/ChatRowsBuilderTest.kt` (its `latestReply` tests replaced), `.../ui/ChatPresenterTest.kt` (its `latestReply` test replaced), `.../ui/SessionActionsTest.kt`

**Interfaces:**
- Consumes: D12a `ChatEntry.Assistant.lastUuid`, `SessionManager.fork(id, atMessage)`; D11's `busy`.
- Produces (spec 13.2):
  ```kotlin
  data class MessageActions(val entryId: String, val markdown: String, val atMillis: Long?, val forkAt: String? = null)
  enum class ForkState { ENABLED, WAIT, UNAVAILABLE }                   // NOT_LATEST removed
  fun forkState(forkAt: String?, busy: Boolean): ForkState          // forkAt null → UNAVAILABLE; busy → WAIT; else ENABLED
  data class ChatRows(val rows: ImmutableList<ChatRow>, val card: PendingCard?, val moreWaiting: Int)   // latestReply removed
  internal class ReplyHost(val onCopy: (String) -> Unit, val onFork: (forkAt: String) -> Unit, val busy: State<Boolean>)
  fun fork(id: SessionId, at: String? = null)                        // SessionActions: manager.fork(id, at); null → the notice "This session has no transcript to fork yet"
  ```
- Rules: `ChatRowsBuilder` gives each ended turn's reply `forkAt` = the `lastUuid` of the turn's last main-thread `Assistant` that has one (scanning the turn backwards), null when none has; the point lives on the row, so a frozen turn keeps it; a turn whose reply is only a notice has no reply row and no button. `MessageActionsRow`: `forkState(actions.forkAt, reply.busy.value)` in a `derivedStateOf`; a click calls `onFork(forkAt)` only when `ENABLED`; tooltips: `ENABLED` `Fork from here` with the dim line `Starts a new session from this point. This one stays as it is.`, `WAIT` `Wait for the reply to finish`, `UNAVAILABLE` `This reply cannot be forked: the CLI sent no id for it`; the accessible name stays `Fork from here`. `ChatScreen`: `ReplyHost(::copyToClipboard, { at -> actions.fork(handle.id, at) }, busy)`. The sidebar's `Fork` still forks the whole session (`fork(id)`). `NOT_LATEST`, `Only the latest reply can be forked`, `ChatRows.latestReply`, `ChatRowsBuilder.latestReply()` and `ChatPresenter.latestReply` are gone: `grep -rn 'NOT_LATEST\|Only the latest reply\|latestReply' src/main` → no hit.

- [ ] **Step 1: Write the failing tests** (spec 9.7):

```kotlin
// ChatRowsBuilderTest (replacing the latestReply tests)
`every ended turn's reply carries its fork point`   // permission-mode replayed (three turns): each ended turn's reply TextRow has actions!!.forkAt == that turn's last main-thread Assistant's lastUuid; three distinct non-null values
`earlier replies keep forkAt and their row instances while a turn runs`   // the same, then a fourth prompt's UserSent and its init → the three earlier reply rows are the same instances (===), with the same forkAt
`a subagent line is not the fork point`   // agent-sync replayed whole → the reply's forkAt != "<uuid-50>" (agent-sync:42, a subagent's line) and == the turn's last main-thread Assistant's lastUuid
`a stopped turn's reply with only a draft falls back to the turn's last saved line, or null`   // interrupt replayed whole → the stopped turn's reply forkAt == the lastUuid of its last main-thread Assistant that has one, or null when none has
`forkState follows the fork point and busy`   // ("x", false) → ENABLED; ("x", true) → WAIT; (null, false) and (null, true) → UNAVAILABLE
// ChatPresenterTest (replacing the latestReply test)
`a turn's start sets every earlier reply to WAIT and its end gives ENABLED to all`   // permission-mode through the presenter: after turn 1 forkState(reply1.forkAt, busy) == ENABLED; during turn 2 → WAIT; after it reply 1 and reply 2 ENABLED; reply 1's TextRow the same instance throughout
// SessionActionsTest
`fork at a reply calls the manager with the point, and a null gets the notice`   // fork(id, "a1") → manager.fork(id, "a1"); a session without a transcript → "This session has no transcript to fork yet" in its chat
// ToolWindowIdeTest (ide)
`fork from here on an earlier reply`   // a seeded three-turn history (longTranscript(seeded, turns = 3): replies "Reply 1." to "Reply 3.", uuids a1 to a3), fixture plain-reply: openSidebar(); row("Seeded").click(Point(10,10)); waitForText("Reply 3."); move the mouse onto "Reply 1." and click the first iconButton("Fork from here") in document order at Point(10,10); waitFor row("Seeded (fork)") selected, showing "Reply 1." and neither "Prompt 2 of the seeded history" nor "Reply 3."; the last __spawn__ argv holds "--resume", seeded, "--fork-session" and "--resume-session-at", "a1", in that order; takeScreenshot("fork-at"); row("Seeded").click(Point(10,10)) → "Reply 3." shown
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.*' --tests 'dev.daarkolabs.tandem.ui.*'` → compilation errors (`forkAt`, `forkState(String?, Boolean)`).
- [ ] **Step 3: Implement** the Interfaces block and the rules; the grep above → no hit.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS; `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → PASS (the new test and every earlier one, the sidebar's whole fork included).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/ui src/integrationTest && COMMIT -m "feat(ui): Fork from here on every reply"`

---

### Task D13b: chat-ui-mode-cycle

**Depends on:** D13a, D12b (`ComposerKeys`, `PromptBar`), D6a (`FooterControls`) (Opus; reviewer Opus). Carries spec 9.7's IDE test 4 (ide, deviation 17). Spec 14.1, 14.2, 14.3 (the chat and ui part); `ui-accessibility`.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/CyclePermissionModeAction.kt`
- Modify: `.../chat/Modes.kt`, `src/main/resources/META-INF/plugin.xml`, `.../ui/ComposerKeys.kt` (`FocusNext`), `.../ui/PromptBar.kt` (`COMPOSER_MODES`, `FocusNext`), `.../ui/FooterControls.kt` (`modeMenuHint`, the menu's header), `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/ui/CyclePermissionModeActionTest.kt`; Modify `.../chat/ModesTest.kt`, `.../ui/ComposerKeysTest.kt`, `.../ui/FooterControlsTest.kt`

**Interfaces:**
- Consumes: D13a `SessionManager.cyclePermissionMode`, `ChatState.rejectedPermissionModes`.
- Produces (spec 14.3):
  ```kotlin
  // chat/Modes.kt, members of object Modes
  val CYCLE: List<PermissionMode>   // [DEFAULT, ACCEPT_EDITS, PLAN, AUTO], the CLI's order
  fun next(current: PermissionMode?, autoSupported: Boolean, rejected: Set<PermissionMode> = emptySet()): PermissionMode
  fun cycleFrom(state: ChatState): PermissionMode   // next(state.permissionMode, shownModel(…)?.supportsAutoMode == true, state.rejectedPermissionModes)
  // ui/CyclePermissionModeAction.kt
  const val CYCLE_MODE_ACTION_ID: String = "Tandem.CyclePermissionMode"
  fun interface ComposerModes { fun cycle(): PermissionMode? }
  val COMPOSER_MODES: DataKey<ComposerModes>          // DataKey.create("dev.daarkolabs.tandem.composerModes")
  class CyclePermissionModeAction : DumbAwareAction()  // getActionUpdateThread() = BGT; update: isEnabledAndVisible = e.getData(COMPOSER_MODES) != null;
                                                       // actionPerformed: cycle(), then AccessibleAnnouncerUtil.announce "Permission mode <Modes.label(mode)>"; logs the wire name only
  // ui/ComposerKeys.kt
  ComposerAction.FocusNext                             // unmodified Key.Tab, after the palette rules; Shift+Tab never handled here
  // ui/FooterControls.kt
  internal fun modeMenuHint(shortcut: String): String?   // "$shortcut to cycle"; null when blank
  ```
  `plugin.xml`: `<actions><action id="Tandem.CyclePermissionMode" class="dev.daarkolabs.tandem.ui.CyclePermissionModeAction" text="Cycle Permission Mode" description="Switch the Tandem chat to the next permission mode: Manual, Accept edits, Plan, then Auto when the model supports it"><keyboard-shortcut keymap="$default" first-keystroke="shift TAB"/></action></actions>` (the plugin's first action; no "Claude Code" in it, hard rule 8).
- Rules (spec 14.1, 14.2): `next` walks `CYCLE` from the current mode's slot, wrapping, and returns the first allowed mode (`AUTO` only with `autoSupported`; never one in `rejected`); start slots: a mode in `CYCLE` its own, `BYPASS_PERMISSIONS` Plan's, `DONT_ASK` Auto's, null Manual's (the first press asks for Accept edits); fallback `DEFAULT`; it never returns bypass or dontAsk. `PromptBar`: `val modes = remember(manager, workspace) { ComposerModes { manager.cyclePermissionMode(currentHandle.id, Modes::cycleFrom)?.also { workspace.state.lastPermissionMode = it.wire } } }` (`currentHandle` through `rememberUpdatedState`); the text area's `provideData` sets `COMPOSER_MODES` beside `PASTE_PROVIDER` only while no footer menu, the ring's popover or the `+` menu is open; `FocusNext` → `LocalFocusManager.current.moveFocus(FocusDirection.Next)` (an open palette's Tab still inserts; typed tabs end, pasted ones land). The mode menu's header row: `Mode`, a weight spacer, then `modeMenuHint(KeymapUtil.getFirstKeyboardShortcutText(CYCLE_MODE_ACTION_ID))` in the dim tier, left out when null. Esc with nothing to close stays unconsumed, as today (checked in `runIde`).

- [ ] **Step 1: Write the failing tests** (spec 9.7):

```kotlin
// ModesTest (JUnit 5)
`next walks the CLI's cycle`   // next(DEFAULT, true) == ACCEPT_EDITS; (ACCEPT_EDITS, true) == PLAN; (PLAN, true) == AUTO; (AUTO, true) == DEFAULT; (PLAN, false) == DEFAULT; (null, true) == ACCEPT_EDITS; (AUTO, false) == DEFAULT; (BYPASS_PERMISSIONS, true) == AUTO; (BYPASS_PERMISSIONS, false) == DEFAULT; (DONT_ASK, true) == DEFAULT
`a refused mode is skipped`   // next(PLAN, true, rejected = setOf(AUTO)) == DEFAULT; next(DEFAULT, true, setOf(ACCEPT_EDITS)) == PLAN
`next never offers bypass or dontAsk`   // for every current in PermissionMode.entries + null, autoSupported true and false, rejected every subset of CYCLE → the result is in OFFERED_MODES, neither BYPASS_PERMISSIONS nor DONT_ASK
`cycleFrom reads supportsAutoMode from the shown model`   // ChatState(models = initialize(fork-at:2).models, activeModel "claude-sonnet-5-5", permissionMode PLAN) → AUTO; activeModel "claude-haiku-4-5-20251001" (fork-at:2 gives it no supportsAutoMode) → DEFAULT
// ComposerKeysTest
`Tab moves the focus forward and Shift+Tab is left to the IDE`   // unmodified Tab → FocusNext, also busy and with a card open; Tab with the palette open → PaletteInsert; Shift+Tab, Ctrl+Tab, Meta+Tab and Alt+Tab → null (replaces the old assertNull(composer(tab)) and its "Shift+Tab (focus traversal)" comment)
// FooterControlsTest
`the mode menu hint names the shortcut`   // modeMenuHint("⇧⇥") == "⇧⇥ to cycle"; modeMenuHint("") == null
// CyclePermissionModeActionTest (JUnit 5, headless @TestApplication)
`the action is registered with Shift+Tab in the default and macOS keymaps`   // ActionManager.getInstance().getAction(CYCLE_MODE_ACTION_ID) is CyclePermissionModeAction; KeymapManager's "$default" and "Mac OS X 10.5+" keymaps list KeyboardShortcut(KeyStroke.getKeyStroke(VK_TAB, SHIFT_DOWN_MASK), null) for it
`the action is enabled only with the composer's modes`   // update with an empty SimpleDataContext → !isEnabledAndVisible; with COMPOSER_MODES set to a fake → enabled and visible
`performing the action cycles once and survives a null`   // actionPerformed → the fake's cycle() called once; a fake returning null → no throw
// ToolWindowIdeTest (ide)
`Shift+Tab in the composer cycles the mode and Tab leaves it`   // fixture permission-mode, before any prompt (deviation 17): newSession(); wait for the __spawn__ line; focusPromptBox(); ui.keyboard { hotKey(VK_SHIFT, VK_TAB) } twice; waitFor picker("Permission mode", "Plan"); takeScreenshot("mode-cycle"); ui.keyboard { key(VK_TAB) } → $PROMPT_BOX[@focused='true'] gone; type "x" → the prompt box's editabletext still ''; the log's set_permission_mode modes == ["acceptEdits", "plan"]
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.ModesTest' --tests 'dev.daarkolabs.tandem.ui.*'` → compilation errors (`Modes.next`, `FocusNext`, `CyclePermissionModeAction`).
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS; `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → PASS (the new test, the sidebar's Shift+Tab test and the footer menus' test included).
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui src/main/resources/META-INF/plugin.xml src/test/kotlin/dev/daarkolabs/tandem/chat src/test/kotlin/dev/daarkolabs/tandem/ui src/integrationTest && COMMIT -m "feat(ui): Shift+Tab cycles the permission mode from the composer, and Tab moves the focus forward"`

---

### Task D14: spark-and-shimmer

**Depends on:** nothing in this round but the base's `Reduce motion` (`TandemSettings.reduceMotion`, `LocalReducedMotion`) (Opus; reviewer Opus). No other task touches its files, so it can run at any point where the chain waits for a recording. Optional IDE test 5 (ide, deviation 19). Spec 15.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ui/rows/Shimmer.kt`
- Modify: `.../chat/WorkingIndicator.kt`, `.../ui/rows/Spark.kt`, `.../ui/rows/WorkingRowItem.kt`, `.../ui/rows/ToolRowItem.kt` (`ToolLine`), `.../ui/rows/ThinkingRowItem.kt`, `.../ide/TandemSettingsConfigurable.kt` (the comment); optionally `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt`
- Test: `src/test/kotlin/dev/daarkolabs/tandem/chat/WorkingIndicatorTest.kt`

**Interfaces:**
- Produces (spec 15.3):
  ```kotlin
  // chat/WorkingIndicator.kt, members of object WorkingIndicator
  const val SPARK_FRAMES: Int = 6; const val SPARK_STILL: Int = 4; const val SPARK_CYCLE_MS: Long = 2_000L
  fun sparkFrame(elapsedMs: Long): Int        // p = elapsedMs mod 2000, q = min(p, 2000 − p): round(5 · (1 − cos(2π · q / 2000)) / 2)
  const val SHIMMER_START_MS: Long = 600L; const val SHIMMER_SWEEP_MS: Long = 900L; const val SHIMMER_PERIOD_MS: Long = 3_000L
  fun shimmerProgress(elapsedMs: Long): Float?   // null before 600 ms and while resting; else (1 − cos(π · s / 900)) / 2 for s = (t − 600) mod 3000 < 900
  fun msToNextSweep(elapsedMs: Long): Long       // at least 1
  data class View(val mode: Mode, val animated: Boolean, val text: String, val accessibleLabel: String, val shimmerEnd: Int = 0)
  //   shimmerEnd: "${row.step}…".length while RUNNING and not under reduced motion; else 0
  // ui/rows/Spark.kt: Spark(animated: Boolean, tint: Color) keeps its signature; the glyph Text becomes a 16 × 16 dp Spacer + drawBehind leaf
  // ui/rows/Shimmer.kt
  @Composable internal fun rememberShimmer(active: Boolean): State<Float?>
  internal fun Modifier.shimmer(active: Boolean, progress: () -> Float?, endX: () -> Float, highlight: Color): Modifier
  // ui/rows/ToolRowItem.kt
  ToolLine(…, shimmer: Boolean = false, onClick)   // a new parameter before the trailing onClick
  ```
- Rules (spec 15.1, 15.2): the spark's frame is a `mutableIntStateOf(SPARK_STILL)` read only inside the draw lambda; its `LaunchedEffect(animated)` returns at once (frame `SPARK_STILL`) when not animated, else takes its start from `withFrameMillis`, then loops `delay(50)` and `withFrameMillis`, setting the frame only when `sparkFrame` changes. It draws round-capped spokes from 0.25 of a 7 dp radius outwards, from −90°, per frame (spokes, length as a share of the radius, stroke): `·` a 3 dp dot; `✢` 4, 0.55, 1.8 dp; `*` 6, 0.70, 1.4 dp; `✶` 6, 0.85, 1.8 dp; `✻` 8, 0.90, 1.8 dp; `✽` 8, 1.0, 2.4 dp, in `TandemColors.spark` (tuned by eye in `runIde`); it keeps `clearAndSetSemantics {}`. `rememberShimmer(active)`: null when not active; else `delay(msToNextSweep(…))` until a sweep, then per frame `shimmerProgress(…)` until it is null again. `Modifier.shimmer`: only while `active`, a `graphicsLayer(compositingStrategy = Offscreen)` and `drawWithContent { drawContent(); progress() ?: return; drawRect(Brush.horizontalGradient(Transparent, highlight, Transparent) over a band of half-width 32 dp whose centre moves from −32 dp to endX + 32 dp, size = (endX clamped to the width, height), blendMode = SrcAtop) }`. `WorkingRowItem`: one `Text` with one string, `onTextLayout = { stepEnd = it.getHorizontalPosition(view.shimmerEnd, usePrimaryDirection = true) }`, `Modifier.shimmer(view.shimmerEnd > 0, { shimmer.value }, { stepEnd }, JewelTheme.globalColors.text.normal)`; the base stays `text.info`; the row's merged semantics (`Claude is working`) and `testTag("working-row")` unchanged. `ThinkingRowItem` passes `shimmer = row.streaming && !reducedMotion()` (the band crosses its whole `Thinking… · N tokens` label); running tool rows pass nothing. The settings comment reads `The spark and the shimmer beside Claude's working line stand still.`

- [ ] **Step 1: Write the failing tests** (spec 9.7 `WorkingIndicatorTest`; JUnit 5):

```kotlin
`the spark breathes over 2 s like the CLI`   // sparkFrame at 0, 250, 500, 750, 1000, 1500, 2000, 3000 == 0, 1, 2, 4, 5, 2, 0, 5; every t in 0..10_000 step 10 gives a frame in 0..5; sparkFrame(t) == sparkFrame(2000 − t) for t in 0..2000 step 10 (on the folded phase; the raw phase gives 2 at 500 and 3 at 1500)
`the shimmer rests, sweeps 900 ms and repeats every 3 s`   // shimmerProgress(0) and (599) null; (600) == 0f; (1050) within 0.01 of 0.5f; (1499) > 0.99f; (1500) null; (3600) == 0f; msToNextSweep(0) == 600; msToNextSweep(1500) == 2100; msToNextSweep(t) >= 1 for t in 0..10_000 step 7
`the shimmer covers the step only`   // view(a running row with step "Pondering", started 3_000 ms before now, …) has text "Pondering… · 3s" and shimmerEnd == 10
`waiting and not responding neither animate nor shimmer`   // a waiting row, and a running row after 60 s of silence: animated false, shimmerEnd 0
`under reduced motion nothing animates or shimmers`   // reducedMotion = true on a running row → animated false, shimmerEnd 0, the same text
`animated by default`   // view(a running row, now, now).animated == true (kept as is if 0.1.1 already has it)
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.chat.WorkingIndicatorTest'` → compilation errors (`sparkFrame`, `shimmerEnd`, …).
- [ ] **Step 3: Implement** the Interfaces block and the rules. Optional IDE test 5 (deviation 19): `the spark moves` (a turn held open with `TANDEM_FAKE_GATE_FILE`): two screenshots of the working row's spark 700 ms apart differ, and are equal with `Reduce motion` on; only if the Driver can capture one component's bounds, else say so in the commit body.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (`chat/` ≥ 90 %); `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → PASS (`the working indicator shows while a turn runs and a footer after it` included). Look at that test's screenshot: the spark is drawn, not a font glyph, and the row reads as before.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/chat/WorkingIndicator.kt src/main/kotlin/dev/daarkolabs/tandem/ui/rows src/main/kotlin/dev/daarkolabs/tandem/ide/TandemSettingsConfigurable.kt src/test/kotlin/dev/daarkolabs/tandem/chat/WorkingIndicatorTest.kt src/integrationTest && COMMIT -m "feat(ui): a drawn spark that breathes like the CLI's and a shimmer across the working label"`

---

### Task D2b: session-folders

**Depends on:** D0's committed outcome, D2a, D10, D12a; runs after D14, last but one before D7 (Opus; reviewer Opus). Branch **C** in D0's report: skip this task and D6b, and tell the owner spec 3.4 needs revising. Branch **B**: apply the fallback lines marked (B) and drop the test marked (A only). Fork **not listed**: `fork()` starts with no folders and the tests marked (fork) are dropped. Fork at a reply **not listed** (spec 13): a fork at a reply starts with no folders and the tests marked (fork at) are dropped. Removal **kept** (also in branch B): no `removeFolder`, no removal notice, the tests marked (removal) are dropped, and D6b draws no `×`.

**Files:**
- Modify: `src/main/kotlin/dev/daarkolabs/tandem/session/SessionHandle.kt`, `.../session/SessionManager.kt`, `.../session/SessionSpawner.kt`, `src/test/resources/fake-claude.py` (rule (l), its two knobs, the docstring)
- Test: `src/test/kotlin/dev/daarkolabs/tandem/session/SessionManagerTest.kt`, `.../protocol/FixtureDecodeTest.kt` (D0's fixture)

**Interfaces:**
- Consumes: D1 `SessionConfig.additionalDirectories`, `SystemInit.additionalDirectories`; D2a `ChatState.additionalDirectories`, `settled()`.
- Produces (spec 3.4, 4.2):
  ```kotlin
  data class AddedFolder(val path: String, val state: FolderState)                // SessionHandle.kt
  enum class FolderState { PENDING, ACTIVE, MISSING }
  class SessionHandle {
      val folders: StateFlow<List<AddedFolder>>
      internal fun addFolder(realPath: String): Boolean        // false when already listed (same string)
      internal fun removeFolder(realPath: String): Boolean     // (removal) also remembers it as removed; a later addFolder of it forgets that
      internal fun copyFolders(from: SessionHandle)            // all PENDING
      internal suspend fun foldersForSpawn(): List<String>     // on Dispatchers.IO: a folder that is no longer a directory leaves the list with the WARNING notice
                                                               // "The folder <path> no longer exists, so it was not added."; the rest in the order added, remembered as the spawn's
      internal fun spawnDiffers(): Boolean                     // a live process runs and its spawn's folders differ from folders' paths
      internal suspend fun confirmFolders(listed: List<String>) // on IO: per spawn folder, listed (string equality, then Files.isSameFile) → ACTIVE, else MISSING
                                                               // and the WARNING "The CLI did not add the folder <path>." once per folder per spawn;
                                                               // (removal) per removed folder not among the spawn's: listed (same comparison) → the WARNING
                                                               // "The CLI still lists the removed folder <path>." once per folder per spawn
  }
  class SessionManager {
      fun addFolder(id: SessionId, realPath: String)      // the caller checked it on IO; then respawnWhenIdle
      fun removeFolder(id: SessionId, realPath: String)   // then respawnWhenIdle
  }
  ```
- Rules: `respawnWhenIdle(handle)` keeps one job per handle in `handle.scope`: while `handle.spawnDiffers()`, it tries `handle.stopIfIdle()`, and on `false` waits for the next change of `handle.state` and tries again; it ends once the process is gone or the spawn no longer differs (so a folder added and removed before the stop stops nothing). No process → nothing to stop. `SessionSpawner.start` calls `handle.foldersForSpawn()` inside its existing IO block and passes the list as `configFor(…).additionalDirectories`. After each `system/init` (its `mcp.initCount` grows) of a process spawned with folders, or of any process once a folder was removed from the handle (the removal check must also run when the spawn has no folder left), the handle calls `confirmFolders(state.additionalDirectories)` on IO. (B) instead: the spawn's folders turn `ACTIVE` at its first init, never `MISSING`. `fork()` copies the open original's folders (fork **listed** in D0; else none), and so does `fork(id, atMessage)` (D12a; fork at a reply **listed** in D0; else none), passed after the fork tail (spec 13.1 "Added folders"); `create()` starts with none; folders live as long as the handle. Logs: counts only, never a path.
- Fake rule **(l)**: with `--add-dir <dir>` (repeatable) in its argv, every replayed `system/init` gets `additional_directories` = those dirs in order, less the one `TANDEM_FAKE_SKIP_ADD_DIR` names; `TANDEM_FAKE_KEEP_ADD_DIR=<dir>` (a CLI that keeps a removed folder) adds that dir to every replayed init, after the others and once, with or without `--add-dir`; with neither the line replays as recorded.

- [ ] **Step 1: Write the failing tests** (spec 9.1's D0 row, 9.2; `SessionManagerTest` on `plain-reply` for every spawn, paths through `toRealPath()`, temp dirs outside the project):

```kotlin
// FixtureDecodeTest (names from D0's lines)
`parity-add-dir-spawn - each init lists the spawn's folders and the resume keeps the id`   // the first process's init additionalDirectories.size == 1, the second's == 2 (A); (B): both 0; the first, second and fifth inits' sessionId equal; every Result of the fixture has subtype "success" and isError == false, and no Assistant has a non-null error (a refused turn would pass on subtype alone)
`parity-add-dir-spawn - a fork with --add-dir lists its folder`   // (fork) the fourth process's init additionalDirectories.size == 1; its sessionId differs from the first's and is the sessionId of every line of transcripts/parity-add-dir-spawn.jsonl (the fork's transcript: one redactor run gives one id one placeholder, and D0's run failed unless the fork's init carried the --session-id it passed, which the redacted stream no longer shows); its Result isError == false
`parity-add-dir-spawn - a resume without a folder leaves it out`   // (removal) the fifth process's init additionalDirectories.size == 1 (B alone)
`parity-add-dir-spawn - a fork at a reply with --add-dir lists its folder`   // (fork at; spec 13) the third process's init additionalDirectories.size == 1; its sessionId differs from the first's and from the fourth's; its Result isError == false
// SessionManagerTest
`adding a folder stops an idle process and the next spawn carries it`   // create; wait for the __spawn__ line; addFolder(id, dir) → folders == [(dir, PENDING)], status → STOPPED; send(id, "hi") → the second __spawn__ argv ends with ["--add-dir", dir] after the id flag; after the turn folders == [(dir, ACTIVE)]
`adding during a turn stops after the turn`   // TANDEM_FAKE_LINE_DELAY_MS 200; send; while RUNNING addFolder → still RUNNING, no stop; after the result → STOPPED
`a folder missing from init warns once`   // (A only) TANDEM_FAKE_SKIP_ADD_DIR = dir; two turns → (dir, MISSING), exactly one WARNING "The CLI did not add the folder $dir."
`a vanished folder is dropped with a notice`   // addFolder; stop; delete dir; send → the spawn's argv has no "--add-dir"; folders empty; WARNING "The folder $dir no longer exists, so it was not added."
`a fork copies the folders`   // (fork) addFolder on the original with a transcript; fork(id)!!.folders.value == [(dir, PENDING)]; a new session's folders empty
`a fork at a reply passes its parent's folders after the fork tail`   // (fork at; spec 9.7's SessionHandleTest row, deviation 15) seed(seeded, fixture = "fork-at-origin") (D12a); addFolder(seeded, dir); fork(seeded, "<uuid-14>")!!.folders.value == [(dir, PENDING)], and its __spawn__ argv ends ["--resume-session-at", "<uuid-14>", "--add-dir", dir]
`removing a folder respawns without it`   // (removal) after the ACTIVE spawn: removeFolder → STOPPED; next send's argv has no "--add-dir"; no removal notice
`a removed folder the CLI still lists warns`   // (removal) TANDEM_FAKE_KEEP_ADD_DIR = dir: the first spawn's init lists dir, no notice (never added); addFolder → after the turn (dir, ACTIVE), no notice; removeFolder → STOPPED; send → the spawn's argv has no "--add-dir", its init lists dir → exactly one WARNING "The CLI still lists the removed folder $dir."; a second turn in that process → still one
`removing a folder before the stop leaves the process running`   // (removal) during a turn: addFolder then removeFolder → after the result the process still runs (no second __spawn__ on the next send)   (Review Focus 5)
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.session.SessionManagerTest' --tests 'dev.daarkolabs.tandem.protocol.FixtureDecodeTest'` → compilation errors (`addFolder`, `folders`).
- [ ] **Step 3: Implement** the Interfaces block, the rules and fake rule (l).
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (`session/` ≥ 90 %); `grep -cE '^  \([h-l]\) ' src/test/resources/fake-claude.py` → 5.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/session src/test/kotlin/dev/daarkolabs/tandem/protocol/FixtureDecodeTest.kt src/test/resources/fake-claude.py && COMMIT -m "feat(session): added folders reach the CLI as --add-dir on the next spawn"`

---

### Task D6b: ui-add-folder

**Depends on:** D2b, D6a; runs right after D2b, last before D7 (Opus; reviewer Opus). Not done in D0's branch C. Carries IDE test 4.

**Files:**
- Create: `src/main/kotlin/dev/daarkolabs/tandem/ide/FolderChooser.kt`, `.../ide/FolderCheck.kt`
- Modify: `.../ide/TandemSessions.kt` (`folderChooser`, `projectDirectory`), `.../ui/PromptBar.kt` (`Add folder` row, `FolderStrip`), `src/integrationTest/kotlin/dev/daarkolabs/tandem/ToolWindowIdeTest.kt` (`runChat`/`ideContext` gain `systemProperties: Map<String, String> = emptyMap()`, each through `addSystemProperty`)
- Test: Create `src/test/kotlin/dev/daarkolabs/tandem/ide/FolderCheckTest.kt`, `.../ide/FolderChooserTest.kt`

**Interfaces:**
- Consumes: D2b `SessionManager.addFolder/removeFolder`, `SessionHandle.folders`, `AddedFolder`, `FolderState`; D3 `displayPath`; D6a's menu.
- Produces (spec 3.4, 4.4):
  ```kotlin
  fun interface FolderChooser { fun choose(project: Project): Path? }   // on the EDT (a modal chooser)
  object FolderChoosers { fun default(): FolderChooser; internal fun propertyFolder(): Path? }
  //   propertyFolder(): System.getProperty("tandem.addFolder")?.let(Path::of); default(): that path when set (IDE tests), else FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Add Folder"), project, null)?.toNioPath()
  sealed interface FolderCheck { data class Add(val realPath: String) : FolderCheck; data class Refused(val message: String) : FolderCheck }
  fun checkFolder(chosen: Path, projectRoot: Path, added: List<String>): FolderCheck   // blocking file checks: call on Dispatchers.IO
  class TandemSessions { val folderChooser: FolderChooser /* = FolderChoosers.default() */; internal val projectDirectory: Path }
  ```
- Check order (spec 3.4): not a directory → `Refused("Could not add $chosen: it is not a folder.")`; `real = chosen.toRealPath()`, `root = projectRoot.toRealPath()`; `real == root` or `real.startsWith(root)` (path segments, not strings) → `Refused("${real.fileName} is inside the project, so Claude can already use it.")`; `real.toString() in added` → `Refused("${real.fileName} is already added.")`; else `Add(real.toString())`.
- UI (spec 3.3–3.5, 3.7): page 0 gains `Add folder` (`Nodes.Folder`) after `Add files or photos`: the menu closes, then `scope.launch { chooser.choose(project) ?: return; val check = withContext(Dispatchers.IO) { checkFolder(it, sessions.projectDirectory, handle.folders.value.map { f -> f.path }) }; Refused → ui.composerNotice.value = message; Add → manager.addFolder(handle.id, realPath) }`, and the text area takes the focus. `FolderStrip` at the top of the composer box, above the image strip, collecting `handle.folders`: one pill per folder (`Nodes.Folder`, the folder's name, a 16 dp `×` `IconButton` with `contentDescription = "Remove folder <name>"` → `manager.removeFolder`, only when D0's removal is **dropped**, else no `×`), `contentDescription = "Folder <name>"`, the tooltip `displayPath(path, home, null)` and `Applies with the next prompt.` (PENDING), `Added to this session.` (ACTIVE) or `The CLI did not add this folder.` (MISSING, the pill in `text.error`). The `×` buttons sit in the box's focus order after `+`, before the text area.

- [ ] **Step 1: Write the failing tests** (spec 9.4, 9.5; JUnit 5, `@TempDir`; a symlink test under `assumeTrue(!isWindows)`):

```kotlin
// FolderCheckTest
`a folder outside the project is added by its real path`   // checkFolder(outside, root, emptyList()) == Add(outside.toRealPath().toString())
`the project root and a folder under it are refused`   // root → Refused("<root name> is inside the project, so Claude can already use it."); root/sub → Refused("sub is inside the project, so Claude can already use it.")
`a folder already added is refused, also through a link`   // added = [outside real]; a symlink to outside → Refused("<name> is already added.")
`a file is not a folder`   // a regular file → Refused("Could not add <file>: it is not a folder.")
`a sibling that shares the project's prefix is outside`   // root "/x/app", chosen "/x/app2" → Add
// FolderChooserTest
`the addFolder property names the folder`   // System.setProperty("tandem.addFolder", dir) → FolderChoosers.propertyFolder() == Path.of(dir); cleared → null (the platform chooser is modal: not called in a unit test)
// ToolWindowIdeTest (fixtures plain-reply, plain-reply; systemProperties = mapOf("tandem.addFolder" to <temp dir outside the project>))
`Add folder respawns with --add-dir`   // newSession(); wait for the first __spawn__; iconButton("Add").click(Point(10,10)); menuItem("Add folder").click(); waitFor described("Folder <name>"); enterPrompt("hi"); waitForTurnEnd(); the second __spawn__ argv holds "--add-dir" followed by the dir's real path; takeScreenshot("folder-pill")
```

- [ ] **Step 2: Run** `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests 'dev.daarkolabs.tandem.ide.*'` → compilation errors.
- [ ] **Step 3: Implement** the Interfaces block and the rules.
- [ ] **Step 4: Run** the Step 2 command → PASS; `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS; `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → PASS.
- [ ] **Step 5: Commit** `git add src/main/kotlin/dev/daarkolabs/tandem/ide src/main/kotlin/dev/daarkolabs/tandem/ui src/test/kotlin/dev/daarkolabs/tandem/ide src/integrationTest && COMMIT -m "feat(ui): Add folder in the + menu, with its pills"`

---

### Task D7: docs-and-verify

**Depends on:** D1–D6b and D8–D14 (D2b and D6b only if D0 did not end in branch C) (Sonnet for the docs; the checks as listed; the whole-branch review after it on Fable). Read `docs/launch.md` first (CLAUDE.md: before editing `docs/protocol.md`).

**Files:**
- Modify: `docs/protocol.md` (1.1, 4.4, 5.1, 6.1, 10), `CLAUDE.md` (`## Commands`: the fake-CLI line, the test-hooks line, the `record-scenario.sh` line), `docs/design.md` (Milestones, the Architecture block), `docs/superpowers/research/2026-10-22-desktop-parity-probes.md` (3.5's remaining items), and for spec 12 to 16 `docs/superpowers/specs/2026-10-19-chat-redesign-design.md` (4.9, 4.18), `docs/superpowers/specs/2026-10-21-chat-polish-design.md` (2.3, 2.6), `docs/superpowers/specs/2026-10-21-mcp-controls-design.md` (12.6, 12.9 item 7), `.github/workflows/cli-contract.yml` and `src/test/kotlin/dev/daarkolabs/tandem/protocol/ContractTest.kt` (Step 5b, deviation 20)
- Test: none new; every suite runs.

**Interfaces:**
- Consumes: everything above; no code change. Each wire claim written below is marked recorded (`fixture:line`), docs only, or unrecorded, as the spec does.

- [ ] **Step 1: Write `docs/protocol.md`:**
  - 1.1: `--add-dir <dir>`, repeatable, after `--resume` / `--session-id`; what D0's 2.6 recorded (`parity-add-dir-spawn:<lines>`), the fork with `--add-dir` included; a running stream-json session cannot gain a folder outside its cwd (`parity-add-dir:6`, `:12`, `:13`).
  - 4.4: `get_context_usage` (no `detail`: the `full` answer with a Messages row, `parity-context:17`; `summary` other numbers, `:18`; an unknown subtype's error shape, `:19`), its payload keys and what Tandem decodes and skips; `get_usage` with and without `skip_behaviors` (`parity-usage:16`, `:17`), `behaviors` null for the scan-less form, `rate_limits_available:false` here; Tandem's timeouts 30 s, 15 s, 60 s; requests mid-turn unrecorded (spec 11 item 2).
  - 5.1: `init.plugins[] {name, path, source, version}` (`parity-add-dir:5`; built-ins with `path: "builtin"`), `additional_directories` (`:5` empty, `:17` one).
  - 6.1: the `usage` local command (`/usage` and `/cost`, `local_command_run.command: "usage"`, `parity-usage:22`, `:28`; results `local_command` `usage` / `cost`, `:23`, `:29`) and the card's two requests after it; `/context`'s local reply is Markdown (`parity-context:24`).
  - 10: rows for `parity-context`, `parity-usage`, `parity-add-dir`, `parity-add-dir-spawn` (what each records, its twin; `parity-add-dir-spawn` also its origin transcript).
  - Check every citation: for each `name:line` written, `sed -n '<line>p' src/test/resources/fixtures/<name>.ndjson | grep -c '<the key the sentence names>'` → 1; a `0` means the sentence is wrong (fix the sentence, never the fixture).
- [ ] **Step 1b: Write `docs/protocol.md` for spec 12 and 13** (for a stranger: the CLI version each behaviour was seen on, 2.1.308, and the fixture line; no spec or plan numbers; launch.md tactic 3):
  - 3: a `user` frame written while a turn runs is queued by the CLI and never echoed on stdout; without `priority` it is folded into the running turn at the next tool round (`queue-fold:19`, `:22`, `:23`, `:53`), and with no tool round left it runs as the next turn, or not, as D8's `queue-steer-text` recorded (its lines); `"priority":"later"` waits for the turn's `result` and runs as its own turn (`queue-later:19`, `:51`, `:53`, `:54`, `:63`); `"priority":"now"` exists and is not sent by Tandem (unrecorded).
  - 4.4: `interrupt` with `cancel_queued:true` and its receipt (`queue-cancel:55`, `:56`, `:60`, `:61`); `cancel_async_message` with `message_uuid` and its answer (`:23`-`:25`, `:36`), and for a message sent without `priority` what D8's `queue-cancel-steer` recorded (its lines).
  - 2 (the top-level types) and 10: `command_lifecycle` (`command_uuid`, `state`: `queued`, `started`, `completed`, `cancelled`), also for the turn's own prompt (`queue-fold:3`, `:5`, `:54`; `interrupt:5`, `:7`, `:20`); `system/status`'s `user_message_uuids` (`queue-fold:23`); init's `capabilities` `msg_lifecycle_v1` and `interrupt_cancel_queued_v1` (`queue-fold:6`).
  - 7: `user_message_uuids` names a folded message (`queue-fold:53`); `queued_turn_count` is 0 with a `later` message waiting (`queue-later:51`), so it is not a turn-over signal.
  - 1.1 and 8: `--resume-session-at <uuid>` beside `--fork-session` (the flag row, the session-flags row, section 8's row): the fork's transcript copies the chain up to and including the cut and nothing after (`transcripts/fork-at.jsonl:6`-`:18`, `transcripts/fork-at-origin.jsonl:20`, `:27`, `:35`), its prompt's `parentUuid` is the cut (`:18`); a fork while the original's process is live, as D8's `fork-at-live` recorded; without `--fork-session` the flag branches the original's own transcript, and an unknown uuid exits 1 with `No message found with message.uuid of: <uuid>` (both seen with 2.1.308, not in a fixture: say so); `fork-at` and `fork-at-live` in the Sources list.
  - 11.2 and 11.4: the `attachment` of type `queued_command` (`transcripts/queue-fold.jsonl:23`) and the `queue-operation` `remove` reasons `absorbed_mid_turn` (`:24`) and `cleared_on_cancel` (`transcripts/queue-cancel.jsonl:34`); Tandem shows a `queued_command` with `commandMode` absent or `prompt` as a folded prompt.
  - 10: rows for `queue-fold`, `queue-later`, `queue-cancel`, `queue-steer-text`, `queue-cancel-steer`, `fork-at` (with `fork-at-origin`) and `fork-at-live` (with `fork-at-live-origin`) and their twins.
  - The same citation check as Step 1.
- [ ] **Step 2: Write `CLAUDE.md`** (`## Commands`): the fake-CLI line gains `TANDEM_FAKE_SKIP_ADD_DIR` and the sentence `It answers get_context_usage and get_usage inline from the fixture's recorded answers (else the CLI's unsupported error), replays a parity-* fixture without its control responses, and lists its own --add-dir folders in each init.`; the test-hooks line gains `tandem.addFolder=<path>` (the `Add folder` chooser returns that path); the `record-scenario.sh` line lists `add-dir-spawn`.
- [ ] **Step 2b: Write `CLAUDE.md` for spec 12 to 15**: the fake-CLI line gains `TANDEM_FAKE_QUEUED_DELAY_MS` and the sentence `In a queue-* fixture it binds the recorded queued-message ids to the host's own user frames, and replays a message's cancelled line only after the host's cancel_async_message or interrupt.`; the `record-scenario.sh` line lists `fork-at`, `fork-at-live`, `queue-fold`, `queue-later`, `queue-cancel`, `queue-steer-text` and `queue-cancel-steer`; the `ui/` Layout line names the queued bubbles and `CyclePermissionModeAction` (Shift+Tab in the composer); the `ide/` line's settings list names `Reduce motion` if 0.1.1 did not.
- [ ] **Step 3: Write `docs/design.md`**: one milestone paragraph (the context breakdown from the ring, the `/usage` card from `get_usage`, the paged `+` menu with Add folder through `--add-dir` on the next spawn, Connectors and the plugins' commands; what stays deferred per spec 2.2: plan-limit bars, Manage and Browse plugins, the Connectors submenu); the Architecture block's `ui/` line names the popover, the card and the paged `+` menu.
- [ ] **Step 3b: Bring the earlier specs in line** (spec 10 slice 7): chat-redesign 4.9 (the drawn spark's 2 s breath, the shimmer, `Reduce motion` as Tandem's own setting) and 4.18 (Tab moves forward from the composer; Shift+Tab is `Cycle Permission Mode`, backward traversal when unbound); polish 2.6 (`Fork from here` on every ended turn's reply, `WAIT` on all while busy, the `UNAVAILABLE` copy, `NOT_LATEST` gone) and 2.3 (Shift+Tab cycles the mode; the menu header's hint); MCP 12.6 (`!` while busy is refused with the notice) and 12.9 item 7 (a prompt behind a shell escape is held, spec 12.4).
- [ ] **Step 4: Write the probe report's 3.5**: each item ends with `(done: D<n>)` or `(not in this plan: spec 2.2)`; nothing else in the report changes.
- [ ] **Step 5: Run** the string checks:
  - `grep -rn "Claude Code" src/main/kotlin/dev/daarkolabs/tandem/chat src/main/kotlin/dev/daarkolabs/tandem/ui src/main/kotlin/dev/daarkolabs/tandem/ide` → no hit;
  - every string of spec 3.6, its placeholders (`<used>`, `<window>`, `<pct>`, `<error>`, `<name>`, `<path>`, `<n>`, `N`, `<plugin>`, `<version>`, `<group>`, `<count>`, `<tokens>`, `<share>`) cut back to the fixed parts, is found by `grep -rF -- "<fixed part>" src/main/kotlin/dev/daarkolabs/tandem` → ≥ 1 hit each (the folder strings only when D6b ran; the removal notice and `Remove folder <name>` only when D0's removal is dropped); a miss is a typo to fix in `chat/` or `ui/`;
  - (spec 12 to 16) the strings of the Global Constraints' spec 12 to 16 list, cut back to their fixed parts the same way, ≥ 1 hit each in `src/main/kotlin/dev/daarkolabs/tandem` (the plugin.xml ones in `src/main/resources/META-INF/plugin.xml`); `grep -rn 'NOT_LATEST\|Only the latest reply\|latestReply' src/main` → no hit;
  - `git grep -nFf scratch/personal-names.txt -- src scripts CLAUDE.md docs ':(exclude)docs/superpowers/plans'` → no hit, and `git grep -nFf scratch/personal-names.txt -- docs/superpowers/plans | grep -v -- '--author=\|user.name=\|user.email='` → no hit.
- [ ] **Step 5b: The contract run covers the new protocol** (launch.md tactic 7, deviation 20): `.github/workflows/cli-contract.yml`'s `Record live transcripts` step also runs `./scripts/record-scenario.sh queue-later build/contract` and `./scripts/record-scenario.sh fork-at build/contract`; `ContractTest` gains `live queue and fork transcripts decode` (skipped when the directory lacks them, as its other cases are): in `queue-later` one `CommandLifecycle` uuid has `queued` and `started` and a `Result` names it in `userMessageUuids`; in `fork-at` the last `SystemInit`'s sessionId differs from the first's and the last `Result` is a success. Run `./gradlew test --init-script scratch/central-mirror.init.gradle.kts --tests '*ContractTest*'` → PASS (skipping without `-Dtandem.contractDir`); the job itself is unverified until it runs on GitHub, which the PR notes say. Commit `git add .github/workflows/cli-contract.yml src/test/kotlin/dev/daarkolabs/tandem/protocol/ContractTest.kt && COMMIT -m "test: the contract run records a queued message and a fork at a reply"`
- [ ] **Step 6: Run** the suites (show any failure's output; never narrow a sweep):
  - `./gradlew check --init-script scratch/central-mirror.init.gradle.kts` → PASS (Kover ≥ 90 % in `protocol/`, `session/`, `chat/`);
  - `./gradlew verifyPlugin --init-script scratch/central-mirror.init.gradle.kts` → no compatibility problem against any supported release;
  - `xvfb-run ./gradlew integrationTest --init-script scratch/central-mirror.init.gradle.kts` → PASS (the twelve new tests, thirteen with D14's optional one, and every earlier one);
  - `cd WT/scripts && python3 -m unittest` → PASS.
- [ ] **Step 7: Commit** `git add docs/protocol.md docs/design.md CLAUDE.md docs/superpowers/research/2026-10-22-desktop-parity-probes.md docs/superpowers/specs/2026-10-19-chat-redesign-design.md docs/superpowers/specs/2026-10-21-chat-polish-design.md docs/superpowers/specs/2026-10-21-mcp-controls-design.md && COMMIT -m "docs: context breakdown, usage requests and --add-dir in the protocol reference"`
- [ ] **Step 8: Pull request** `feat/desktop-parity` → `feat/mcp-controls` (its stacked base; `main` if `feat/mcp-controls` has merged by then), title `feat: desktop parity: context breakdown, /usage card, + menu, sending while Claude works, fork from any reply, Shift+Tab and the spark`, no attribution line. Write the body to `WT/scratch/pr-body.md` and show it to the owner (CLAUDE.md: ask before anything outward-facing); once they agree, `git push -u origin feat/desktop-parity` and `gh pr create --base feat/mcp-controls --head feat/desktop-parity --title "feat: desktop parity: context breakdown, /usage card, + menu, sending while Claude works, fork from any reply, Shift+Tab and the spark" --body-file WT/scratch/pr-body.md`. Body: context (the owner's three choices, in their words, and that the reference frames stay out of the repository); approach (the ten slices in one paragraph, D0's branch); testing (Step 6's suites with their counts and `./gradlew koverLog`'s three percentages); the spec 9.6 `runIde` checklist for the owner, one line each, plus open items 2 and 3 to measure there (requests mid-turn: the popover's, a card's `Retry`, a scan still running when the next prompt starts; the latency of `full` and of the scan); verified and not, plainly: subscriber shapes, `mcpInstructions`, `agents` and requests mid-turn are unrecorded. For spec 12 to 16 the body adds the owner's five sentences from the second Mac test, D8's three outcomes and D0's fork at a reply, the contract job's state (Step 5b), and spec 9.7's manual pass for the owner, one line each: Enter and ⌘↵ while a long Bash runs, each bubble's states, Edit and Cancel, Stop with a message queued and the text coming back, the hint, ⌘↵ reaching the composer; a fork from the first reply of a long chat while the original's process is live and idle, the tooltips; Shift+Tab cycling with the label changing at once, Shift+Tab in the editor still unindenting, the Keymap entry with a rebind and an unbind, VoiceOver's announcement, the Tab order from the composer, idle Esc reaching the editor; the spark's breath and the band beside the CLI and the desktop app in both themes, Activity Monitor back at idle with the tool window hidden or the row scrolled away, Reduce motion, VoiceOver not re-announcing per frame; the card's share bars, request count and tips at 350 px, `Show session usage` from the ring. Unrecorded and said so: a message during a card or a subagent, two `later` messages, a folded image, `cancelled:false`, a fork before a compaction boundary or of a renamed session, other models and runs without `--safe-mode` (spec 11 items 13 to 15), the animation's cost off macOS (item 16). Then the whole-branch review on Fable.

---

## Self-review (done while writing)

- Spec coverage: 2.1 item 1 → D2a (requests, coalescing, the breakdown in the state) + D3 (`contextPopover`) + D4 (controller, popover, `See detailed breakdown`, the Markdown `/context` notice); item 2 → D1 (decode) + D2a (fetch, part states, retry) + D3 (`usageCard`) + D5 (view); item 3 → D1 (`plugins`, `--add-dir`) + D3 (`pluginMenu`, `withCommand`) + D6a (paged menu, Connectors, Plugins pages) + D0, D2b, D6b (Add folder). 2.2's deferrals stay out (nothing decodes `rate_limits`, `messageBreakdown`, `fix`, `reload_plugins`). 3.1 → D3, D4; 3.2 → D2a, D3, D5; 3.3 → D3, D6a; 3.4 → D0, D2b, D6b; 3.5 and 3.7 → the semantics and keys in D4, D5, D6a, D6b (spec 3.5's keys); 3.6 → the Exact values line and D7 Step 5; 4.1 → D1; 4.2 → D2a, D2b; 4.3 → D3; 4.4 → D4, D5, D6a, D6b; 6 flows 1–9 → the IDE tests and `SessionManagerTest`; 7 → `RobustnessTest`, the model tests and Review Focus; 8 → the controller rules and the row caching; 9.1 → D1 (+ D2b for D0's row); 9.2 → D2a, D2b; 9.3 → D3; 9.4 → D4, D6a, D6b; 9.5 → D4, D5, D6a, D6b; 9.6 → D7's PR notes; 10 → the task order and tiers; 11 items 1 → D0, 6 → D4's fallback, 7 → settled while planning.
- Step scan: every test step names its tests and assertions with the fixtures' values; every code step names its signatures and the values the spec pins; the only rules written out are those no signature determines (sorting, prefixes, coalescing, the respawn loop, the fake's rules).
- Type consistency: `ContextBreakdown`, `UsageAnswer`, `SessionUsage`, `UsageHistory`, `UsageWindow`, `UsageTip`, `PluginInfo`, `ControlResult`/`ControlError`, `UsageCard`, `UsagePartState`, `UsagePart`, `ContextControls`, `ContextPopoverModel`, `UsageCardModel`, `UsageSection`, `PluginEntry`, `PluginCommand`, `NoticeRow.usage/markdown/sourceUuid`, `ComposerModel.plugins/pluginsKnown`, `FetchState`, `ContextPopoverHost`, `PagedMenuState`, `plusMenuTurn`, `AddedFolder`, `FolderState`, `FolderChooser`, `FolderCheck` carry the same names and types in every task that uses them. With D8 to D14: `CommandLifecycle`, `InterruptReceipt`, `PendingSend`, `PendingStage`, `ReturnedSend`, `ChatEntry.User.folded`, `cliCapabilities` (`Set<String>?`), `QueuedRow`, `UserRow.caption`, `ComposerAction.QueueLater/BusyRefused/FocusNext`, `CapturedPrompt.later`, `PaletteOutcome.Send.later`, `ChatEntry.Assistant.lastUuid`, `forkedAt`, `SessionHandle.forkAt`, `MessageActions.forkAt`, `ForkState` (three values), `ReplyHost`, `rejectedPermissionModes`, `pickMode`, `cyclePermissionMode`, `Modes.CYCLE/next/cycleFrom`, `COMPOSER_MODES`, `CYCLE_MODE_ACTION_ID`, `WorkingIndicator.View.shimmerEnd`, `ModelTokens.webSearches`, `ModelBlock.costShare/costShareText`, `UsageWindowModel.tips`, `ContextPopoverHost.onUsage` carry the same names and types in every task that uses them.
- Review Focus: ten lines, each pinned to named tests (1 to 5 in D1, D2a, D2b or D3; 6 to 10 in D9 to D14).
- Spec 12 to 16 (added with D8 to D14): 12.1 to 12.5 → D9 (frames, lifecycle, replies) + D10 (pending and held sends, withdraw, Stop with `cancel_queued`, returned messages, reload, attention, idle) + D11 (keys, placeholder, Send beside Stop, bubbles, captions, refusals, palette, restore); 12.6 → D9, D10; 12.7 → D11; 13.1, 13.2 → D9 (`resumeSessionAt`) + D12a (`lastUuid`, `forkedAt`, `fork(id, at)`) + D12b (`forkAt` per reply, `ForkState`, tooltips, `SessionActions`) + D0 and D2b (a fork at a reply with folders) + D8 (`fork-at-live`); 14 → D13a (the locked pick, refused modes) + D13b (`Modes`, the action and `plugin.xml`, Tab, the hint, the announcement); 15 → D14; 16 → D1, D3, D4, D5 (marked (spec 16)); 9.7 → each task's tests (deviations 15, 17, 19); 10's slices → D8 to D14 (deviation 14); 11 items 10 and 15 → IDE tests 2 and 4 and the 9.7 pass, item 12 → D8's `queue-cancel-steer`, items 13, 14 and 16 → D7's PR notes, item 17 → nothing shown (spec 2.2).
- Proportion: with D8 to D14 the plan is about 1.4 times the spec's length (235 kB against 169 kB); the bulk is test assertions with the fixtures' values and the rules no signature determines, and its code blocks are signatures, names and assertions, no function bodies.

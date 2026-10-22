# Tandem design

## Goal
The best Claude Code experience in JetBrains IDEs: fast, native, and honest about what the CLI is doing.
Free and open source. Success is measured by benchmarks we publish (time to first token, memory, plugin size)
and by not breaking on IDE or CLI updates.

## Why another plugin
The existing options were studied in October 2026:
- Anthropic's own "Claude Code [Beta]" plugin (rating 2.3) is a terminal launcher with a diff hook.
- CC GUI (largest community plugin, ~340k lines, 489 open issues) is a React webview + Node sidecar + pinned Agent SDK.
  Its top problems map directly to that architecture.

| CC GUI pain (issue examples) | Root cause | Tandem decision |
|---|---|---|
| Black/white screen every IDE release (#813, #1302, #1907) | JCEF webview | Jewel (Compose) UI, no browser |
| Typing/IME/selection bugs (#1854, #1465) | JCEF input handling | Jewel text fields |
| Windows spawn errors, node pile-up, RAM (#1928, #953, #1324) | Node sidecar | No Node; spawn `claude` directly |
| Missing models, broken `/compact`, "works in CLI" (#1517, #592) | Pinned SDK drifts from user's CLI | Use the user's own CLI, version-gated features |
| Model switch silently ignored (#1462, #1741) | Model fixed at process start | `set_model` control request, verify via init/fallback events, show mismatch and refusal |
| IDE freezes waiting on bridge (#1859, #1801) | Blocking I/O on EDT | Coroutines only; EDT never waits |
| History lost / not resumable in CLI (#1852, #1259) | Own session format | Use the CLI's own sessions (`--resume`) |
| Wrong diffs, diff exceptions (#1891, #275) | Writes to disk, diffs afterwards | Edits applied through `Document` + native diff before apply |

## Architecture

```
 Jewel tool window (ui/)  session sidebar, header (with the MCP servers button and its connected/total badge); the
        │                 chat: a keyed list of rows (bubbles, Markdown, tool, thinking, shell (`! command`), notice,
        │                 working and footer rows), a hover row under each reply (Copy, Fork from here, time), the
        │                 permission / question / plan cards, the MCP servers sheet over the chat, the composer (the
        │                 `+` attach button, the thumbnail strip, the text area with its `/` slash-command palette) and
        │                 its footer: the mode control, the model · effort control and the context ring
        │  collects StateFlows, calls SessionManager
        ▼
 TandemSessions (ide/)  project service: owns the SessionManager in its coroutine scope, restores the open sessions
        │               from TandemWorkspaceState, reads TandemSettings (claude path, max live sessions, show thinking rows,
        │               whether Claude replies to `!` command output; Settings | Tools | Tandem), runs SessionNotifier (IDE
        │               notifications), opens an MCP server's sign-in link in the system browser (BrowserOpener)
        ▼
 SessionManager (session/)  the project's sessions: StateFlow<List<SessionHandle>> and the selection, the live cap,
        │                   create / open / fork / rename / delete / close, the resume-id mismatch rule. It raises the
        │                   attention of a session that is not selected when it waits for a permission or ends a turn;
        │                   the sidebar draws that as the badge. McpControls: list, disable / enable, reconnect and sign
        │                   in to a session's MCP servers (the CLI's `mcp_*` control requests) and the sign-in poll
        ├─ ShellRunner  a `! command` in the user's login shell on Dispatchers.IO, never the EDT: stdin closed, stderr
        │               merged, the output polled (never a blocking read) and capped, the process tree killed on Stop
        ├─ SessionHandle  one session's chat across processes: status, title, attention, prompt queue
        │    └─ ClaudeSession  one live `claude` process, while the session has one
        │         • spawns `claude --input-format stream-json --output-format stream-json --verbose --include-partial-messages
        │           … --session-id <id>` (or `--resume <id>`, or a fork: `--resume <id> --fork-session --session-id <new>`)
        │         • writer coroutine: intents → stdin NDJSON (user messages, control requests and responses)
        │         • reader coroutine: stdout NDJSON → protocol/ decode → reducer → ChatState
        │         • control channel: can_use_tool → permission prompt in the chat → control_response
        └─ session/transcript/  the CLI's own `<config>/projects/<key>/<id>.jsonl` files (protocol.md 11)
             • TranscriptFolder  config directory and folder key
             • SessionIndex      lists the transcripts (cached, watched) as SessionSummary rows
             • TranscriptReader  a transcript → settled ChatState + TranscriptMeta; TranscriptChain picks the current history
        ▼
 protocol/  pure Kotlin. Json tree → sealed CliMessage; unknown types → Unknown(raw)
 chat/      pure Kotlin. ChatState → chat rows (ChatRowsBuilder): tool labels, states, bodies; no Compose, no IntelliJ
        ▲
 ide/ (Milestone 2)  Tandem MCP server (edit tools via WriteCommandAction), diff preview, file/selection context
```

Key choices:
- **One long-lived CLI process per live session.** No per-message spawn. Several sessions run at once, up to a cap
  (default 4): past it, the least recently used idle session is stopped and resumes with its next prompt. A busy
  session is never stopped: with none idle, a new session or a prompt starts over the cap and says so, and a past
  session that is only opened waits for its first prompt.
- **The CLI's transcripts are the session store.** Tandem keeps no session format of its own: the sidebar lists the
  CLI's `.jsonl` files for the project and history loads from them, so terminal sessions show up too. Its only writes
  there are a rename's `custom-title` line and deleting a session (`protocol.md` 11).
- **Reducer, not mutation.** Every decoded message goes through a pure `reduce(state, message)`; this is what the
  fixture tests exercise, and it makes partial/complete message reconciliation deterministic.
- **Partial messages.** `stream_event` deltas build a draft keyed by message id; the complete `assistant`
  message replaces the draft. Never render both.
- **Permissions answered by the host** over the control channel (see `protocol.md`), shown as native, non-modal UI
  inside the chat, with "allow once / always for this session / deny".
- **Edits through the IDE.** Built-in `Edit`, `Write`, `MultiEdit`, `NotebookEdit` are disallowed; Tandem's MCP server
  exposes equivalent tools that apply via `WriteCommandAction` on the open `Document`.

## Milestones
0. **Jewel spike (2–3 days).** Streaming markdown + code blocks in a Jewel tool window at 60fps on a long transcript.
   Go/no-go for Jewel. If no-go, fall back to JCEF with strict isolation.
   **Result: go.** 200 messages streamed at ~50 tokens/s in a headless sandbox IDE (Xvfb, IU 2026.2.3): one EDT
   stall over 100 ms (~190 ms, first Markdown composition), otherwise p99 EDT wait 6 ms. The idle IDE's own startup
   produced larger stalls (up to ~490 ms). Frame rate and feel were not measured headless; check them in `runIde`.
   Reproduce with `xvfb-run ./gradlew runIde -Ptandem.spike=true -Ptandem.openProject=<dir>` (see `ui/dev/`).
1. **MVP.** Protocol + session + chat + host permissions + model/error status + resume.
   **Status: implemented, not merged yet** (`feat/protocol-layer`, `feat/sessions-and-pickers` on top): the protocol layer,
   `ClaudeSession`, the Jewel chat, host-answered permissions, model mismatch and error notices, and the sessions spec
   (`docs/superpowers/specs/2026-10-16-sessions-and-pickers-design.md`): a sidebar of the CLI's sessions for the project,
   several live sessions under a cap, resume, fork, rename and delete, model and permission-mode pickers, effort picked
   at creation. Covered by unit, fixture and platform tests, IDE Starter tests and the Plugin Verifier; feel, focus and
   freezes are still to be checked by hand in `runIde`.

   **Phase 2a, chat redesign.** The desktop app's chat feel: typed tool rows with grouping, thinking rows, the working
   indicator, the permission / question / plan cards, intent-based autoscroll, subagent nesting, reload parity; spec
   docs/superpowers/specs/2026-10-19-chat-redesign-design.md. **Status: implemented on feat/chat-redesign**, covered by
   fixture, pure-presentation (chat/), platform and IDE Starter tests; feel is checked by hand (spec 10.7).

   **Phase 2a polish** (spec docs/superpowers/specs/2026-10-21-chat-polish-design.md): live tool states and Show more /
   Show less, the desktop-style composer and footer menus with the context ring, coloured Bash bodies and the inline code
   pill, the hover action row, image attachments by paste, drop and the `+` menu. A model switch the CLI refuses puts
   back the model shown before the pick and says so in the chat, naming the model the CLI stays on, as a refused
   permission mode or effort does (`protocol.md` 8). **Status: implemented on feat/chat-redesign.**

   **Phase 2c, MCP controls and bash mode** (spec docs/superpowers/specs/2026-10-21-mcp-controls-design.md). Phase 2b,
   the chat redesign spec's deferred list (its section 2, the task-list widget among it), is not yet a milestone of its
   own; the polish round and this phase took some of its items. The MCP servers panel, opened from the header's button
   with its connected/total badge, lists the session's servers with their state and error and disables, enables,
   reconnects or signs in to each. The sign-in is the CLI's own OAuth flow (`mcp_authenticate`): Tandem only opens the
   link in the system browser and waits for the server to connect, and never sees a token (hard rule 3). A `/` in the
   composer opens the slash-command palette, built from the CLI's own catalogue: `/mcp`, `/model`, `/permissions` and
   `/mode` open Tandem's panel and menus, every other command goes to the CLI, and a command the CLI answers itself
   (`/help`, or `/mcp` with arguments or from a session started in a terminal) shows as a notice, not a reply, with no
   `Worked for` footer. Bash mode: a composer line starting with `!` runs in the user's login shell, its output shows
   live in the chat as a shell row, and it reaches the CLI as the terminal's own shell escape, so Claude answers it (or,
   with the setting off, keeps it as context only). Recorded with CLI 2.1.306 against local probe servers (`protocol.md`
   3, 4.4, 6.1, 11.2); docs only until a recording on a machine with claude.ai connectors (spec 11 item 1): the
   connectors themselves, the OAuth callback completing, and whether the CLI answers `mcp_*` requests while a turn runs.
   **Status: implemented on feat/mcp-controls.**
2. **IDE-native edits.** MCP edit tools, diff preview, undo.
3. **Parity.** Slash commands, plan mode, AskUserQuestion, rewind, subagent status.
4. **Hardening.** Windows/WSL, Remote Development, IDE matrix in CI, published benchmarks.

## Open questions
- Does the platform's classloader expose kotlinx.serialization to plugins? We bundle 1.9.0 (tree API only) for now.
- Lowest supported IDE: 2026.1 (build 261). Revisit after the Jewel spike. The sessions work stayed on 261; Jewel's
  `ContextMenu` is internal there, so the session menu is a `PopupMenu`.
- ~~Exact flag set for host-answered permissions~~: `--permission-prompt-tool=stdio`, confirmed by the `permission`
  fixture (`protocol.md` 4.3).
- Jewel Markdown and other IDE content modules must be listed under `<dependencies>` in `plugin.xml`; compiling
  against them is not enough. Check with the Plugin Verifier that the module names hold for every release since 261.
- ~~kotlinx.collections.immutable~~: provided by the platform on 261 and 262 and not bundled; `ChatState` uses its
  persistent list and map through the builder API, which neither release deprecates.

Resolved while building the sessions spec (its open items; details in `protocol.md`):
- ~~What the CLI keeps beside `<id>.jsonl`~~: an `<id>/` folder (a `custom-title.json` after a rename; per the SDK,
  subagent transcripts and tool results). Delete removes both, as the SDK's `deleteSession` does (`protocol.md` 11.5).
- ~~How `set_permission_mode` is acknowledged~~: a success with `{"mode": …}`, then `system/status` with the new
  `permissionMode`; the next `init` agrees (`permission-mode` fixture, `protocol.md` 4.4).
- ~~`--session-id` with a hosted parent~~: Tandem drops the parent's variables (`protocol.md` 1.2). With
  `CLAUDE_CODE_SESSION_ID` set and nothing else of a hosted session, 2.1.301 ignores the variable and `--session-id`
  names the session; the nightly contract run checks that. Which hosted variables make the CLI adopt an inherited id
  was not measured.
- ~~Who titles a fork~~: the CLI writes no "(fork)" title, so Tandem renames the fork itself. Tandem also gives the fork
  its id (`--resume <id> --fork-session --session-id <new>`), since no `system/init` reports it before the fork's first
  prompt (a signed-in CLI names it earlier only in undocumented lines); the fork's `system/init` then reports that id
  (`protocol.md` 1.1 and 8).
- ~~Changing effort on a live session~~: `apply_flag_settings` with `{"effortLevel": …}` changes the next turn's effort
  (the transcript shows it; `init` has no effort); `update_settings` writes settings files and is not for this
  (`protocol.md` 4.4). An effort picked in the footer is sent as `apply_flag_settings` (`protocol.md` 4.4).

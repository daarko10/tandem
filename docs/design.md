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
 Jewel tool window (ui/)  session sidebar, one tab per live session in the tool window's header (the selected one and
        │                 any that needs the user, a status dot, a `+`, one shared chat panel under every tab), the
        │                 header (with the MCP servers button and its connected/total badge); the
        │                 chat: a keyed list of rows (bubbles, pending bubbles for messages sent while Claude works,
        │                 Markdown, tool, thinking, shell (`! command`), notice, the `/usage` card, working (a light
        │                 band sweeps its label beside the orbs) and footer rows), a hover row under every reply (Copy,
        │                 Fork from here, time), the permission / question / plan cards, the MCP servers sheet over the
        │                 chat, the composer (the paged `+` menu, the thumbnail strip with the folder pills, the text
        │                 area with its `/` slash-command palette) and its footer: the mode control (Shift+Tab, the
        │                 `Cycle Permission Mode` action, cycles it), the model · effort control and the context ring,
        │                 which opens the context window's breakdown
        │  collects StateFlows, calls SessionManager
        ▼
 TandemSessions (ide/)  project service: owns the SessionManager in its coroutine scope, restores the open sessions
        │               from TandemWorkspaceState, reads TandemSettings (claude path, max live sessions, show thinking rows,
        │               reduce motion, whether Claude replies to `!` command output; Settings | Tools | Tandem), runs
        │               SessionNotifier (IDE notifications), opens an MCP server's sign-in link in the system browser
        │               (BrowserOpener)
        ▼
 SessionManager (session/)  the project's sessions: StateFlow<List<SessionHandle>> and the selection, the live cap,
        │                   create / open / fork / rename / delete / close, the resume-id mismatch rule. It raises the
        │                   attention of a session that is not selected when it waits for a permission or ends a turn;
        │                   the sidebar draws that as the badge. McpControls: list, disable / enable, reconnect and sign
        │                   in to a session's MCP servers (the CLI's `mcp_*` control requests) and the sign-in poll
        ├─ ShellRunner  a `! command` in the user's login shell on Dispatchers.IO, never the EDT: stdin closed, stderr
        │               merged, the output polled (never a blocking read) and capped, the process tree killed on Stop
        ├─ SessionHandle  one session's chat across processes: status, title, attention, added folders. A message
        │                 sent while Claude works goes to the CLI's own queue at once and shows as a pending bubble;
        │                 Tandem holds a prompt only behind a shell escape
        │    └─ ClaudeSession  one live `claude` process, while the session has one
        │         • spawns `claude --input-format stream-json --output-format stream-json --verbose --include-partial-messages
        │           … --session-id <id>` (or `--resume <id>`, or a fork: `--resume <id> --fork-session --session-id <new>`, cut at a reply with
        │           `--resume-session-at <uuid>`; `--add-dir <folder>` per added folder)
        │         • writer coroutine: intents → stdin NDJSON (user messages, control requests and responses)
        │         • reader coroutine: stdout NDJSON → protocol/ decode → reducer → ChatState
        │         • control channel: can_use_tool → permission prompt in the chat → control_response
        │         • requests: get_context_usage (the ring's breakdown), get_usage (the /usage card), cancel_async_message
        └─ session/transcript/  the CLI's own `<config>/projects/<key>/<id>.jsonl` files (protocol.md 11)
             • TranscriptFolder  config directory and folder key
             • SessionIndex      lists the transcripts (cached, watched) as SessionSummary rows
             • TranscriptReader  a transcript → settled ChatState + TranscriptMeta; TranscriptChain picks the current history
        ▼
 protocol/  pure Kotlin. Json tree → sealed CliMessage; unknown types → Unknown(raw)
 chat/      pure Kotlin. ChatState → chat rows (ChatRowsBuilder): tool labels, states, bodies; no Compose, no IntelliJ
        ▲
 ide/ (Milestone 2)  TandemEdits and IdeEditTools (ide/edits/): Tandem's edit tools (edit_file, write_file) applied with
                     WriteCommandAction on the Document and saved, behind the editable native diff (EditDiff, one at a time
                     through EditQueue); ProjectFiles and EditorSelection (ide/context/): the `@` file palette's candidates
                     and the editor's selection. The tools are an SDK MCP server served over the control channel
                     (session/ routes mcp_message to protocol/ TandemTools; session/edits/ holds the matcher and the
                     result texts).
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
- **Edits through the IDE.** Built-in `Edit`, `Write`, `MultiEdit`, `NotebookEdit` are disallowed; Tandem serves
  equivalent tools, `edit_file` and `write_file`, as an in-process SDK MCP server named `tandem` over the control channel it
  already has (`initialize.sdkMcpServers`, then `mcp_message` requests: no port, no sidecar, no `--mcp-config`). A call opens
  the IDE's diff with the file's own `Document` on the left and the proposal editable on the right; Accept applies the right
  side with one `WriteCommandAction`, saves the file, and answers the CLI with the file as it now is, so a hand change in
  the diff reaches the model.

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

   **Phase 2d, desktop parity** (specs docs/superpowers/specs/2026-10-22-desktop-parity-design.md, sections 2 to 18). The
   context ring opens a breakdown of the context window by category (`get_context_usage`), with the MCP tools, memory files
   and skills behind it. `/usage` and `/cost` draw a card with the session's cost, times and each model's tokens and share
   of the cost, and what has used the plan's limits on this machine (`get_usage`, the CLI's own scan of its transcripts); the
   ring leads to it. The `+` menu is paged and split by where each thing comes from, with an icon on each row: `Add files or
   photos` (Ctrl+Alt+Shift+O, ⌘⌥⇧O), `Add folder` (the CLI's `--add-dir` on the next spawn: the session restarts when idle,
   a folder is removed the same way), `Slash commands`, `Connectors` (a switch per claude.ai connector on the MCP panel's
   own controller, `Browse connectors` and `Manage connectors`) and `Plugins`, each plugin under the name the CLI gives it
   with its `Skills` apart from its `Commands`. While Claude works, Enter sends a message the CLI reads at its next step and
   Ctrl+Enter (⌘↵) queues one for after the turn; each shows as a pending bubble with Edit and Cancel, Stop withdraws what
   waits, and a message folded into a turn survives a reload. Every ended turn's reply can be forked, cut there with
   `--resume-session-at`. Shift+Tab in the composer cycles the permission mode, and a light band sweeps the working row's
   label beside the orbs, both under `Reduce motion`. The tool window's header gets one tab per live session. Deferred
   (spec 2.2): the plan-limit bars (nothing decodes `rate_limits`), Manage and Browse plugins, connector icons and custom
   servers among the connectors, every open session as a tab, tab reordering and split. Recorded with CLI 2.1.308 and 2.1.310
   (`protocol.md` 1.1, 3, 4.4, 5.1, 6.1, 8, 11); unrecorded: the subscriber shapes of `get_usage`, a claude.ai connector's
   row and switch, requests made while a turn runs, a message during a subagent, and the connectors address.
   **Status: implemented on feat/desktop-parity; the manual pass in `runIde` is the owner's.**
2. **IDE-native edits** (spec docs/superpowers/specs/2026-10-28-ide-edits-design.md). Claude's edits go through the IDE.
   `edit_file` and `write_file` are served over the control channel (`protocol.md` 4.2, 4.5); each call opens the platform's
   diff, the file's `Document` read-only on the left and the proposal editable on the right, with Accept (Ctrl+S, ⌘S, which
   inside the diff never means Save All), Accept all, and Reject with an optional reason. Accept writes the right side with
   one `WriteCommandAction` (one undo step, one Local History entry), saves the file to disk before the CLI is answered, and
   the answer carries the file as it now is, hand changes included. In `acceptEdits` and `bypassPermissions`, or after Accept
   all (for the rest of the turn), edits apply without a diff, and the checks (the project's folders, binary, size, plan mode)
   never widen. Pending edits queue one diff at a time across the project's sessions; the chat rows say where each is, and
   Stop withdraws a waiting call. A CLI that did not take the server shows the notice `Edits are off in this session`. `@` in
   the composer opens a file palette that inserts the CLI's own `@path` mention (a file with unsaved changes goes as a text
   block of the editor's buffer instead), and the editor's selection travels with the prompt as a chip the user can remove.
   Recorded with CLI 2.1.312 (`ide-edit`, `ide-edit-two`, `ide-edit-stop`, `ide-mention`; `protocol.md` 3, 4.5, 6.2); the
   server sits beside configured servers, and a configured server also named `tandem` loses dispatch to Tandem's but shows as
   the one `tandem` row (recorded only for a `--mcp-config` server; one from the user's, project's or local settings is
   unrecorded). The MCP panel treats the row as Tandem's own and never offers to turn it off. Deferred (spec 2.2): a
   `read_file` tool that sees unsaved buffers (an edit matches the editor's text, and a miss returns it), `multi_edit`,
   per-hunk accept, the CLI's own IDE integration (lock file, `--ide`), and mirroring the CLI's `Edit` quote normalisation.
   Unrecorded: `MCP_TOOL_TIMEOUT`'s default for a held call, the frames of `bypassPermissions`, subagent edits, and mentions
   with spaces or of directories. **Status: implemented on feat/ide-edits; the manual pass in `runIde` (focus on the right
   side, Ctrl+S in the diff, the editor-tab preference, undo as one step, Enter in the mention palette) is the owner's.**
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

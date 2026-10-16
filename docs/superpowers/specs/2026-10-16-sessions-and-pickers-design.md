# Sessions and pickers (Phase 1)

Status: draft for review. Follows `docs/design.md` (architecture, hard rules) and `docs/protocol.md` (wire protocol).

## Goal

Make the Tandem tool window behave like the desktop app's Code tab for one project folder:

- a sidebar listing every session the CLI has for this folder, including sessions started from a terminal;
- several sessions live at once, each its own `claude` process, so a long task and a quick question can run side by side;
- open, resume, fork, rename and delete sessions;
- the prompt-bar pickers the desktop shows: model and permission mode, with effort chosen at session start.

Later phases build on this: rich transcript rendering (2), IDE-native edits (3), dialogs and control panels (4), other session kinds (5).

## Non-goals (deferred)

- Thinking toggle and changing effort mid-session (no control request exists; see Open items).
- Bypass-permissions mode (needs its own confirmation flow and spawn flag; Phase 4).
- Tool-window tabs, custom layouts, sessions from other folders, full-text search across transcripts.
- Subagent (`isSidechain`) history and background-session kinds (`--bg`, `--worktree`, `--cloud`).

## What the user sees

### Layout

Two panes inside the tool window: a collapsible sidebar on the left and the chat on the right. A header button toggles the sidebar; it collapses automatically when the window is narrower than 500 px and expands again above it. The collapsed state is remembered per project.

### Sidebar

- "New session" button at the top; opens a small popup with model, effort and permission mode, prefilled with the last choices for this project.
- A filter box that narrows the list by title.
- Sessions newest first (by transcript modification time; a live session whose file does not exist yet sorts at the top). Each row shows the title, a relative time ("3 min ago"), and a status dot:

  | Status | Meaning |
  |---|---|
  | running | process alive, a turn is in progress |
  | waiting | a permission prompt is pending |
  | idle | process alive, nothing in progress |
  | stopped | no process (not opened yet this IDE run, closed, or evicted by the cap) |

- A badge on a row that is not selected when its session asks for permission or finishes a turn. The badge clears when the session is selected.
- Right-click menu: Rename, Fork, Delete (asks for confirmation), Copy `claude --resume <id>`.
- Sessions created outside Tandem appear within a few seconds (file watcher) and immediately when the sidebar is opened.

### Header

Sidebar toggle, the session title (double-click to rename, Enter to confirm, Escape to cancel), and the status line Tandem already shows (model, mode, CLI version, "working…"). The model-mismatch banner stays as it is, per session.

### Prompt bar

Below the prompt box, like the desktop:

- **Model picker:** entries from the `initialize` response (`models[]`: `value`, `displayName`, `description`), "Default" first. Choosing one sends `set_model` on a live session; on a stopped session the choice is applied with `--model` when the process starts. The next `system/init` confirms it; a mismatch shows the existing warning.
- **Permission mode picker:** Default, Accept edits, Plan. Sends `set_permission_mode` on a live session; `--permission-mode` at start otherwise. Confirmed by `system/status.permissionMode` or the next `init`.
- **Effort badge:** read-only, from `init.effort`. Effort is chosen in the New Session popup and passed as `--effort`.

### Several live sessions

- Every opened session keeps its process until the user closes it (sidebar "x" on hover, or Close in the menu).
- Setting "Max live sessions" (default 4, range 1–16). When opening a session would exceed it, the least recently used session that is idle (no turn in progress, no pending permission) is stopped: its process ends, its chat stays on screen, and its row shows "stopped". The next prompt restarts it with `--resume`. If no session is idle, the new one starts anyway and a notice says the cap was exceeded.
- A running turn is never interrupted by switching sessions or by the cap.

### Notifications

When a session that is not selected (or the tool window is hidden) asks for permission or finishes a turn, an IDE notification in the existing "Tandem" group says which session and offers "Open". Permission requests use the warning level; finished turns use information. The sidebar badge appears at the same time.

### Settings

Settings | Tools | Tandem (application level, not roamed, since paths are machine-specific):

- `claude` executable: auto-detected path shown; optional override with a file chooser and a "Test" button that runs `claude --version` off the EDT.
- Max live sessions.

### Persistence

Per project (workspace file): open session ids, selected session id, sidebar collapsed, last model, effort and permission mode. On restart, open sessions are listed and the selected one is shown with its history; processes start only when a session is opened or the user sends a prompt.

## Where sessions live on disk

Verified against `@anthropic-ai/claude-agent-sdk` 0.3.300 (`sdk.mjs`), which reads the same files.

- Config directory: `$CLAUDE_CONFIG_DIR` if set, else `~/.claude`.
- Transcript folder: `<config>/projects/<key>/`, where `<key>` is the project base path with every character outside `A–Z a–z 0–9` replaced by `-`. Keys longer than 200 characters are truncated to 200 and get a `-<hash>` suffix computed by the SDK's own hash; Tandem does not reproduce that hash. For such paths it lists folders matching the 200-character prefix and checks the `cwd` field of their transcript lines.
- One transcript per session: `<sessionId>.jsonl`, newline-delimited JSON, appended as the session runs. A session id is a UUID; new ones come from `UUID.randomUUID()` and are passed with `--session-id`.
- The file may be written by a running CLI while Tandem reads it; readers must tolerate a truncated last line.

### Transcript lines Tandem uses

| `type` | Fields used | Use |
|---|---|---|
| `user` | `uuid`, `parentUuid`, `timestamp`, `isSidechain`, `isMeta`, `message.content` | text blocks become the user's prompts; `tool_result` blocks become tool results; `isMeta` and `isSidechain` lines are skipped |
| `assistant` | `uuid`, `isSidechain`, `message` (API message with `id`, `model`, `content`), `requestedModel` | complete assistant messages; the last one's `message.model` is the session's last model |
| `custom-title` | `customTitle` | title set by the user (last one wins) |
| `ai-title` | `aiTitle` | generated title |
| `summary` | `summary` | generated summary |
| `last-prompt` | `lastPrompt` | not used in Phase 1 |

Title precedence: last `custom-title`, else the last `ai-title` or `summary` (whichever comes last), else the first non-meta user text, trimmed to one line of at most 80 characters, else the session id. Every other line type (`attachment`, `queue-operation`, `cost-state`, `atis-latch`, `system`, `file-history-snapshot`, `progress`, and anything new) is skipped. A line that is not a JSON object is skipped. Nothing throws.

### Writes Tandem makes to the folder

- Rename of a session without a live process: append `{"type":"custom-title","customTitle":"<title>","sessionId":"<id>"}` as one line, the same write the SDK makes.
- Delete: remove `<sessionId>.jsonl` and the sidecar directory `<sessionId>/` if it exists (see Open items for what the CLI stores there).

Tandem never reads anything else under the config directory. `.credentials*` and settings files are out of bounds (hard rule 3).

## Components

### `session/` (no IntelliJ imports except the project scope)

- **`SessionManager`** (project service with an injected `CoroutineScope`, replaces today's `TandemChat`): owns `StateFlow<List<SessionHandle>>` and `StateFlow<SessionId?>` (selected). A `SessionHandle` has the id, `StateFlow<ChatState>`, the `SessionStatus` above, the title, and the last-used timestamp. Operations: `create(config)`, `open(id)`, `select(id)`, `close(id)`, `fork(id)`, `rename(id, title)`, `delete(id)`, `send(id, text)`, `interrupt(id)`, `answerPermission(...)`, `setModel(id, model)`, `setPermissionMode(id, mode)`. Enforces the cap. All file and process work runs on `Dispatchers.IO`; the UI only collects flows (hard rule 1).
- **`SessionIndex`**: lists the transcript folder into `SessionSummary(id, title, lastModified, firstPrompt)`; caches per file keyed by size and modification time so unchanged files are not re-read; refreshes on demand and from a `java.nio.file.WatchService` on the folder, debounced to 500 ms. Merged with live sessions so a new session shows before its file exists.
- **`TranscriptReader`**: reads one `.jsonl` through the existing `Ndjson.decode` and `reduce`: `user` text lines become `SessionEvent.UserSent`, `user` tool-result lines become `SessionEvent.Cli(User)`, `assistant` lines become `SessionEvent.Cli(Assistant)`. Returns the resulting `ChatState` with `busy = false`, `ended = false` and no pending permissions, plus the metadata (title, last model, line count). Runs off the EDT and is cancellable between lines.
- **`ClaudeSession` changes:** `SessionConfig` gains `sessionId` (new sessions) and `effort`; `--session-id` is passed for new sessions and `--resume` for existing ones, never neither. `rename(title)` sends `{"subtype":"rename_session","title":...,"source":"host"}`. The process environment drops variables that identify a parent session or hosted environment: `CLAUDECODE`, `CLAUDE_PID`, `CLAUDE_CODE_SESSION_ID`, `CLAUDE_CODE_CHILD_SESSION`, `CLAUDE_CODE_SESSION_ATTENDED`, `CLAUDE_CODE_CONTAINER_ID`, `CLAUDE_CODE_WORKER_EPOCH`, `CLAUDE_SESSION_INGRESS_TOKEN_FILE`, and every `CLAUDE_CODE_REMOTE*`, `CLAUDE_CODE_MESSAGING_*`, `CLAUDE_CODE_ENVIRONMENT_*`. Everything else passes through unchanged, in particular `CLAUDE_CONFIG_DIR` and provider settings such as Bedrock or Vertex variables. `CLAUDE_CODE_ENTRYPOINT` is set to `sdk-tandem` as today.

### `ide/`

- **`TandemSettings`** (`PersistentStateComponent`, application level, roaming disabled): `claudePath: String?`, `maxLiveSessions: Int = 4`. A `Configurable` built with Kotlin UI DSL v2.
- **`TandemWorkspaceState`** (project level, workspace file): `openSessionIds`, `selectedSessionId`, `sidebarCollapsed`, `lastModel`, `lastEffort`, `lastPermissionMode`.
- **`SessionNotifier`**: collects every handle's state; on a permission request or turn end in a non-selected session, posts the IDE notification and sets the badge.
- `ClaudeBinary.find()` reads the override from `TandemSettings` instead of a system property; the property stays as a test hook.

### `ui/`

- **`TandemToolWindow`**: the two-pane layout, sidebar visibility, width-based auto-collapse.
- **`SessionSidebar`**: New session button, filter, list rows with status dot, badge, hover "x", context menu; `NewSessionPopup`.
- **`SessionHeader`**: toggle, editable title, status line.
- **`PromptBar`**: prompt box, Send/Stop, `ModelPicker`, `PermissionModePicker`, effort badge.
- `ChatPanel` and `ChatRowsPresenter` are reused per session; one presenter per open session, created when the session is opened and disposed when it is closed.

## Flows

- **New session:** generate the id, record the pickers' choices, spawn with `--session-id <id> --model … --effort … --permission-mode …`. The row appears from the live map at once; the index picks the file up when the CLI creates it.
- **Open a past session:** `TranscriptReader` builds the initial `ChatState` (entries, last model, permission mode from the last user line); the row is selected immediately with that history. If the cap allows, spawn `--resume <id>`; `system/init` then confirms the model and id. Otherwise the session stays "stopped" until the first prompt.
- **Send on a stopped session:** spawn `--resume <id>` first, then send; the prompt queue that `TandemChat` has today moves into the handle, so a prompt sent before the process is up is never dropped.
- **Fork:** spawn `--resume <id> --fork-session`; the new id arrives in `init`; the CLI gives the fork a "(fork)" custom title. The new session is selected; the original stays as it was.
- **Rename:** live session → `rename_session`; stopped session → append the `custom-title` line. Refresh the index entry either way.
- **Close:** stop the process (existing shutdown sequence), remove from the open list, keep the transcript.
- **Delete:** stop the process if any, confirm, delete the file and sidecar, remove from the open list; if it was selected, select the next session or show the empty state.
- **Cap:** evaluated before every spawn; eviction stops the least recently used idle session; a session is "used" when selected or when it sends or receives a message.
- **Restart:** restore the open list and selection from the workspace state; load the selected session's history; do not spawn until the user acts.

## Errors and edge cases

- Malformed or truncated transcript lines are skipped. A missing folder is an empty list. A file that disappears during a read ends that read with what was parsed.
- `--resume` fails (process exits, "No conversation found", or a non-zero exit): the chat shows the existing error notice, the session shows "stopped", and the next prompt tries again.
- `init.session_id` differs from the id Tandem expected on a plain resume: treat it like a fork (new row, warning notice), never silently relabel.
- Rename to an empty title is rejected in the UI. Titles are shown as plain text, never interpreted as Markdown.
- Eviction and the cap never stop a session that is busy or waiting for permission.
- Deleting the selected session while it is running: stop first, then delete.
- Two Tandem windows of the same project (the IDE allows it) share the services, so there is one process per session regardless of how many tool windows show it.
- The file watcher failing to start (unsupported file system) degrades to refresh-on-open only, with a log line, not an error dialog.

## Testing

Test-first, as in the earlier phases. The fake CLI (`src/test/resources/fake-claude.py`) grows two features: it writes a first log line `{"type":"__spawn__","argv":[…],"env":{…}}` so tests can assert flags and environment, and it picks its fixture per spawn from `TANDEM_FAKE_FIXTURES` (a comma-separated list consumed in spawn order) so two live sessions can replay different conversations.

- **Transcript fixtures:** `scripts/record-scenario.sh` also copies the session's `.jsonl` (found from `init.session_id` under the config directory), redacted, to `src/test/resources/fixtures/transcripts/<name>.jsonl`. The redactor learns the transcript id keys (`uuid`, `parentUuid`, `sessionId`, `promptId`, `leafUuid`, `requestId`) and the `cwd`/`gitBranch` fields. Every fixture is checked for personal data before committing, as before.
- **`TranscriptReader`:** loading the transcript of a recorded conversation yields the same user prompts, assistant blocks and tool results as reducing its stream fixture; title precedence (custom, generated, first prompt, id); `isMeta` and `isSidechain` lines skipped; unknown types and malformed lines skipped; a truncated last line.
- **`SessionIndex`:** lists only `.jsonl` files, newest first; cache hit when size and mtime are unchanged; a file written after listing appears on the next refresh; the long-path prefix rule.
- **`SessionManager` (fake CLI):** new session passes `--session-id` and the chosen flags; reopening passes `--resume`; the denylisted variables are absent from the fake's environment while `CLAUDE_CONFIG_DIR` passes through; the cap evicts only idle sessions and never a busy one; close stops the process; `rename` sends `rename_session` on a live session and appends the line on a stopped one; delete removes the file.
- **Reducer:** unchanged, plus a test that a loaded history followed by a live turn renders correctly (ids stay unique, `busy` toggles).
- **Platform tests:** `TandemSettings` and `TandemWorkspaceState` round-trip; the settings page reads and writes the path and cap.
- **IDE test (UI Driver):** create two sessions that stay live (two fake processes), switch between them, rename one from the sidebar and see the title update, and see the badge when the background session's fixture asks for permission.
- **Nightly contract:** the transcript written by the newest CLI still loads with the reader (a `.jsonl` recorded by the contract workflow).

Hard rules checked by tests: no EDT blocking (platform tests run the manager with the EDT free), no orphan processes after close or project close (existing tests extended to several sessions).

## Open items to verify during implementation

1. **Delete:** what the current CLI stores beside `<sessionId>.jsonl` (a sidecar directory for tool results, subagent transcripts). The plan includes reading the SDK's `deleteSession` before writing Tandem's delete, and the IDE test checks nothing of the session remains.
2. **Permission mode mid-session:** record a `set_permission_mode` scenario (Default → Plan → Accept edits) as a fixture and confirm how the CLI acknowledges it (`status.permissionMode` or the next `init`).
3. **`--session-id` with a hosted parent:** the nightly contract run passes a bogus `CLAUDE_CODE_SESSION_ID` in the environment and checks that `init.session_id` equals the id Tandem passed.
4. **Fork title:** confirm the "(fork)" custom title is written by the CLI for `--fork-session`, not only by the SDK's `forkSession`.
5. **Effort control:** check `update_settings` / `apply_flag_settings` as a way to change effort on a live session; if one works, Phase 4 turns the badge into a picker.

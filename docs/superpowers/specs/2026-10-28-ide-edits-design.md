# IDE-native edits: Tandem's edit tools over the control channel, the editable diff, and editor context (Milestone 2)

Status: draft for review, 2026-10-28. Lands on `feat/ide-edits`, cut from `main` at `4e6f99b` (PR #1, desktop parity merged). This spec touches the spawn and the control channel (`SessionConfig.argv`, `HostMessages.initialize`, `ClaudeSession.readLoop`, `InboundRequest`), the reducer and the chat rows (`Reducer`, `ChatState`, `ToolKinds`, `ToolStates`, `ToolLabels`, `ToolBodies`, `ChatRowsBuilder`), the composer (`PromptBar`, `ComposerKeys`, `ComposerSender`, `HostMessages.userMessage`) and `ide/`, which gets its first real service beside `TandemSessions`: the edit tools, the diff and the editor context.

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

Inputs: the owner's words for this round, "Yeah just run it through, when showing edits allow me to edit stuff on the diff view and when I click save it will send the latest version of the file to the llm as a result cause programmers might change what it put out small changes by hand which is the strength of the diff viewer in jetbrains"; `docs/design.md` (Milestone 2, the `ide/` line of the architecture and the "Edits through the IDE" key choice) and CLAUDE.md hard rule 4; the probe report `docs/superpowers/research/2026-10-28-ide-edits-probes.md` (probes; its runs `r1`, `r2`, `r3` under `scratch/probes/ide-edits/`, not fixtures: section 9 names the scenarios that make them fixtures) made against claude 2.1.312 (the sandbox's version; the task named 2.1.310); the IU 2026.1.5 jars read with `javap` (probes 2); and the code at `4e6f99b`: `protocol/CliMessage.kt` (`InboundRequest`), `HostMessages.kt`, `Mcp.kt`; `session/ClaudeSession.kt` (`argv`, `readLoop`, `request`), `Reducer.kt` (the `CanUseTool` case, `pendingPermissions`), `ChatState.kt` (`ToolResult`, `PendingPermission`, `ChatEntry.User`), `PermissionAnswers.kt`; `chat/ToolKinds.kt` (`tandemTools` already maps the prefix `mcp__tandem__edit` to `ToolKind.EDIT`), `ToolStates.kt`, `ToolLabels.kt` (`fileForms`), `ToolBodies.kt` (`diff`, `patchStats`), `ChatRow.kt` (`ToolCall`, `ToolState`, `DiffStats`), `SlashPalette.kt`; `ui/PromptBar.kt`, `ComposerKeys.kt`, `ComposerSender.kt`, `rows/ToolRowItem.kt`, `rows/UserBubble.kt`; `ide/TandemSessions.kt`, `TandemSettings.kt`; `src/test/resources/fake-claude.py`, `scripts/drive_cli.py`, `scripts/redact_fixture.py`. Every wire claim below is **probe run** (`r1:line`, `r2:line`, `r3:line`, or `transcript.jsonl:line` for `r3`'s transcript) until section 9's recordings exist, after which the fixture lines replace them; a claim marked **docs only**, **binary** (the 2.1.312 binary's strings, probes) or **unrecorded** is what it says.

---

## 1. Goal

Claude changes files in the project, and every change lands the way a JetBrains user expects: through the IDE's `Document`, as one undoable command with local history, after a native side-by-side diff in which the proposal is editable. The user reviews Claude's edit, fixes it by hand where it is nearly right, and presses Accept (Ctrl+S, ⌘S on macOS, as the owner's "when I click save"): the final text, hand changes included, goes into the file, and the tool result tells the model what was applied and what the user changed, so the model's picture of the file is the file. Reject sends the model an error with the user's reason, and the turn goes on. In `acceptEdits` and `bypassPermissions`, or after `Accept all`, edits apply without a diff. The tools are Tandem's own, served to the CLI over the control channel it already has: no port, no Node, no sidecar (hard rule 2), no credentials (hard rule 3), nothing on the EDT but the IDE's own write action (hard rule 1), every failure in the chat (hard rule 5), a fixture test for every new frame (hard rule 6), and no "Claude Code" in a string (hard rule 8). The editor's selection and `@file` mentions reach the model as context (section 3.5).

## 2. Simplest working version, and what is deferred

### 2.1 This round

1. **Two tools, one in-process MCP server** (4.1): `edit_file` (the CLI's `Edit`: `file_path`, `old_string`, `new_string`, `replace_all`) and `write_file` (its `Write`: `file_path`, `content`), served as the SDK server `tandem` that `initialize` registers with `sdkMcpServers:["tandem"]` and the CLI drives with `mcp_message` control requests (probes 1.1). The built-in `Edit`, `Write`, `MultiEdit`, `NotebookEdit` stay disallowed.
2. **The editable diff** (3.1, 3.2): every `edit_file` and `write_file` call opens the platform's diff, the file's own `Document` read-only on the left, the proposal editable on the right, with `Accept` (Ctrl+S / ⌘S, Ctrl+Enter / ⌘↵), `Accept all`, `Reject` and a reason field under the editors. Accept applies the right side's text with `WriteCommandAction`; the result carries the applied region, and the user's changes to the proposal when there are any, with the file's current text (4.3).
3. **Auto-accept** (3.3): `acceptEdits`, `bypassPermissions`, or `Accept all` for the rest of the turn.
4. **One diff at a time** (3.4): pending edits queue in the order they arrived, across the project's sessions; each row says where it is.
5. **Every file case named** (section 7): not open in an editor, new file, the old string no longer matches, outside the project, read-only, too large, binary, a directory, an unknown tool, a CLI that does not take the server, Stop while a diff is open.
6. **Context** (3.5): `@` in the composer opens a file picker that inserts the CLI's own `@path` mention (which the CLI expands itself, probes 3); a file with unsaved changes is sent from the editor's buffer instead; the active editor's selection goes with the prompt as a chip the user can remove.

### 2.2 Deferred (with the reason)

- **A `read_file` tool that sees unsaved buffers.** The model reaches for the built-in `Read` (not deferred, in every prompt it was trained on); a second read tool would be used rarely and would still race the disk. Instead, every `edit_file` matches against the editor's `Document` (the buffer, unsaved changes included), and a miss returns that text (4.3), so the loop converges in one extra round; `@path` of a dirty file sends the buffer (3.5). A `read_file` tool, and `NotebookEdit`'s equivalent, wait for a case the owner hits (11 item 1).
- **`multi_edit`.** The CLI itself dropped `MultiEdit` (`docs/protocol.md` 1.1: in no 2.1.302 `init.tools`); several `edit_file` calls each get their diff, in order (3.4).
- **The http/sse server with `--mcp-config`.** The control channel needs no port and was recorded working; the alternative was not tried (probes 1.1).
- **`sdkMcpServerManifests`** (**binary**: a one-shot cache of the handshake that lets the CLI skip `tools/list`) and **`sdkMcpServerConfigs`** (per-tool `always_ask` / `always_deny`): the live handshake is three frames and the permission request arrives in every mode anyway (probes 1.2); neither is sent (11 item 3).
- **Lifting the deferral.** The tools are deferred like every MCP tool: the model calls `ToolSearch` first (`r1:19`, `:21`). Nothing recorded lifts it; not attempted (11 item 4).
- **The CLI's own IDE integration** (**binary**: the `~/.claude/ide/<port>.lock` file with `authToken`, `--ide`, the `openDiff` / `FILE_SAVED` / `DIFF_REJECTED` flow, `at_mentioned` and `selection_changed` notifications, `ide_selection` / `ide_opened_file` reminders): a WebSocket server on a port and a lock file in the user's config directory, with the file written by the CLI from the IDE's returned text rather than through the `Document`, and whether the CLI connects to it in stream-json mode is unrecorded. Tandem's own tools and context blocks cover the same ground without a port (11 item 5).
- **The read-before-edit rule** of the CLI's `Edit` ("File has not been read yet"): the diff is the gate; the model edits what it has not read at the price of a mismatch error (4.3). Tandem does not track reads.
- **A diff for `write_file` of an existing file as a merge**, **per-hunk accept in the diff**, **opening the diff beside the chat instead of the editor area**: the two-sided editable diff is the simplest form of the owner's request; the IDE's own "Open diff as editor tab" preference is honoured (3.1) and nothing more.
- **Mentions of directories, of paths with spaces, during a running turn** (3.5): unrecorded (probes 3); the picker lists files only, and a mention is inserted as typed.
- **Edits from a subagent** (`parent_tool_use_id` set): the row nests under the `Agent` row as every subagent tool does; the diff opens the same way. No recording has one (11 item 8).
- **A setting to turn the tools off**, **a global "never show diffs" setting**: the mode menu's `acceptEdits` and the diff's `Accept all` are the two auto-accept choices; a user who wants no edits at all picks `plan` or tells Claude. No new setting this round (3.6).

## 3. What the user sees

### 3.1 The diff

**Opening.** When a `tools/call` for `edit_file` or `write_file` arrives and auto-accept (3.3) is off, Tandem opens the IDE's diff: `DiffContentFactory.getInstance().create(project, document, virtualFile)` on the left (the file's own `Document`, so unsaved changes show as they are) and `createEditable(project, proposedText, fileType)` on the right, in a `SimpleDiffRequest(title, left, right, leftTitle, rightTitle)` with `DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS = [true, false]` (the left side cannot be typed into; the right can), `PREFERRED_FOCUS_SIDE = Side.RIGHT`, `DiffUserDataKeysEx.BOTTOM_PANEL` = the bar of 3.2, `DO_NOT_CHANGE_WINDOW_TITLE = true` (probes 2 has every signature). It is shown through `DiffEditorTabFilesManager.getInstance(project).showDiffFile(ChainDiffVirtualFile(SimpleDiffRequestChain(request), title), true)`, so the IDE's own preference decides between an editor tab and a window (Settings | Editor | General | Editor Tabs, "Open diff as editor tab"); the tab or window is focused. For a new file (`write_file` of a path that does not exist) the left side is `createEmpty()`.

Strings: window and tab title `Tandem: <file name>`; left title `Current` (with `Current (unsaved changes)` when `FileDocumentManager.isDocumentUnsaved(document)`); right title `Proposed by Claude`. For a new file the left title is `New file`.

**The right side is a real editor**: syntax highlighting from the file's type, the IDE's completion is off (it is an in-memory document), undo within the diff works as in any editor. The user changes the proposal there as they like: the diff re-computes as they type, as the platform does.

**Closing without a choice** (the window's ×, the tab's close, Esc in the diff) counts as `Reject` with no reason (3.2). The IDE's "close all tabs" does the same. Tandem learns of it from `DiffRequest.onAssigned(false)` (probes 2), after it checks that no choice was made.

**Several files**: each call is one diff; see 3.4.

### 3.2 The bar under the diff, and what each button does

A panel (`DiffUserDataKeysEx.BOTTOM_PANEL`) with, left to right: the text `Claude proposes an edit to <file name>. Change the right side as you like, then accept or reject.` on the first line; on the second a text field with the placeholder `Reason (optional): goes back to Claude with a rejection` that takes one line, then the buttons `Accept` (the default button; tooltip `Apply the right side to <file name> and tell Claude (Ctrl+S)`, `⌘S` on macOS), `Accept all` (tooltip `Accept this and every other edit Claude makes in this turn without a diff`) and `Reject` (tooltip `Change nothing and tell Claude why (Esc)`). The buttons are `JButton`s on a `DialogWrapper`-free panel; `Accept` is bound to the shortcuts of the IDE's `SaveAll` action (read from `ActionManager.getInstance().getAction("SaveAll").shortcutSet`, Ctrl+S or ⌘S by default) and to Ctrl+Enter / ⌘↵, both registered on the diff's component with `DiffUtil.registerAction` (probes 2), so Save inside the diff is Accept and never the IDE's Save All; outside the diff the IDE's Save All is untouched. `Reject` is bound to Esc on the same component.

**Accept.** The right document's text is read on the EDT, then applied to the file's `Document` in one `WriteCommandAction` named `Tandem: edit <file name>` (`Tandem: create <file name>` for a new file), so Ctrl+Z in the editor undoes the whole edit and Local History shows one entry under that name. For `edit_file` the whole document is set to the final text only when the user changed the proposal; otherwise only the matched range is replaced (`replaceString`), which keeps the caret and folds of an open editor. The document is not saved to disk by Tandem: it stays a modified buffer the IDE saves as it saves everything (on focus loss, build, commit), exactly as if the user had typed the change; a new file is created on disk (`VfsUtil.createDirectories`, `createChildData`) because a `Document` needs a `VirtualFile`, with its content set in the same command. Then the diff closes and the tool result of 4.3 goes to the CLI.

**Accept all.** As Accept, and the session enters `Accept all` for the rest of this turn: every later `edit_file` / `write_file` call of this turn applies without a diff (3.3). The turn's `result` line ends it.

**Reject.** The diff closes, nothing is changed, and the tool result is an error carrying the reason when one was typed (4.3). The turn goes on: the model reads the reason and answers or tries something else (`r1:95`, `:108`).

**A diff left open.** Claude waits (the CLI holds the `tools/call` until the host answers, probes 1.4). The chat row says so (3.4) and `Stop` withdraws it (7.11). The CLI's own per-call timeout (`MCP_TOOL_TIMEOUT`, **binary**, default unmeasured) may cancel a diff left open for very long: Tandem treats that cancellation as 7.11 and the row says `Claude stopped waiting for this edit.`

### 3.3 Auto-accept

An edit applies without a diff, with the same `WriteCommandAction` and a result that says so, when any of these holds at the moment the `tools/call` arrives:

- the session's permission mode is `acceptEdits` or `bypassPermissions` (`ChatState.permissionMode`, the mode the CLI confirmed: `system/init.permissionMode` or `system/status` after `set_permission_mode`, `docs/protocol.md` 4.4). The CLI still sends `can_use_tool` for Tandem's tools in those modes (`r2:66` under `acceptEdits`, `server_policy_ask_only:true`): Tandem answers `allow` and skips the diff itself;
- `Accept all` was pressed in a diff of this turn (3.2), until the turn's `result`;
- the call comes while the same file's diff was accepted with `Accept all` (the same rule).

`auto` mode shows the diff (the CLI's classifier decides nothing about Tandem's tools; the request arrives as in `default`). `plan` mode applies nothing: see 7.9. `dontAsk`: the CLI denies ask-policy calls itself; no `tools/call` arrives (**docs only**, the binary's `always_ask` text).

The row of an auto-accepted edit reads `Edited <file>` with the tag `auto-accepted` (3.4), and the file's editor, if open, shows the change at once; the user undoes it with Ctrl+Z as any edit.

### 3.4 The chat rows and the queue

`mcp__tandem__edit_file` is `ToolKind.EDIT` (`ToolKinds.tandemTools` already maps the prefix `mcp__tandem__edit`) and `mcp__tandem__write_file` is `ToolKind.CREATE` (new prefix `mcp__tandem__write`). Labels follow `ToolLabels.fileForms` with the file name relative to the project: `Editing <file>` / `Edited <file>` / `Failed to edit <file>` / `Rejected edit of <file>`, and `Creating <file>` / `Created <file>` / `Failed to create <file>` / `Rejected creating <file>`. The row's body is the result's text in the editor font (`ToolBody.Mono`, as a `Write` without a patch shows today) and its `DiffStats` are counted from the input (`old_string` / `new_string` lines, or `content`'s lines for a new file) until a result gives better numbers.

New states while the call waits for the user, from `ChatState.edits` (4.4):

| `EditReview.phase` | Row label | Row action |
|---|---|---|
| `QUEUED` (another diff is open) | `Edit of <file> waits for the open diff` | none |
| `OPEN` | `Reviewing <file> in the diff` with a soft pulse of the orbs as a running tool has | `Show diff` (focuses the diff's tab or window) |
| after Accept with changes | `Edited <file>` + tag `with your changes` | none |
| after Accept, Accept all, auto | `Edited <file>` (+ tag `auto-accepted` when 3.3 applied) | none |
| after Reject | `Rejected edit of <file>` (`ToolState.DENIED`: the result text starts with `The user rejected this edit`, added to `ToolStates`' denial rule) | none |
| after Stop | `ToolState.INTERRUPTED` as today (the CLI's synthetic result `r2:43` carries no `is_error`, `non_execution_kind:"user-rejected"`) | none |

The permission card does not appear for Tandem's tools: the `can_use_tool` is answered `allow` at once (4.2), the diff is the question. The `Accept all` state shows under the composer as the mode control's hint `Accepting all edits this turn` beside the mode name until the turn ends.

**The queue.** One diff at a time per project (`EditQueue` in `TandemEdits`, 5): calls queue in arrival order across the project's sessions; a queued call's row says so. The model of a session is blocked on its own call anyway, so the queue only matters with two live sessions editing at once. Stop in a session withdraws its queued calls (7.11); `Accept all` applies to the pressing session's later calls only.

### 3.5 Context: `@file` mentions and the selection

**`@` opens the file picker.** Typing `@` at the start of the composer or after a space opens a palette in the slash palette's compact form (`SlashPaletteView`, MCP spec 14; the same keys: Up / Down, Enter picks, Tab inserts, Esc closes until the draft changes): the project's files whose relative path matches what follows `@`, by the IDE's own matcher (`NameUtil.buildMatcher` over `FilenameIndex`'s names, read in a `readAction` off the EDT, debounced 100 ms, at most 40 rows, the open editors' files first). A pick replaces the typed `@…` with `@<path relative to the project base> ` (a trailing space). The pill `@path` stays plain text in the composer. The CLI expands it: the prompt's text block goes out as typed, and the CLI attaches the file from the disk, which the model sees without a tool (`r3:12`, `transcript.jsonl:6`, probes 3). Nothing shows on stdout for it; the bubble shows the text as typed, with each `@path` drawn as an inline chip when the path exists.

**A mentioned file with unsaved changes.** If, at send time, a mentioned path's `Document` is modified (`FileDocumentManager.getInstance().isDocumentUnsaved`), the CLI's disk read would be stale. Tandem then sends the mention as the bare path (no `@`, so the CLI does not expand it) and adds its own text block **before** the prompt's block: `<path> as open in the IDE, with unsaved changes (N lines):` and the buffer's text in a fenced block, at most 64 KB per file (beyond that the first 64 KB and the line `… truncated: <T> lines in all`). The bubble shows a chip `<name> (unsaved)`; the transcript reload recognises the block by its first line (`TranscriptReader`, 4.4) and draws the same chip.

**The selection.** While a text editor has a selection (at least one character, `SelectionListener` on `EditorFactory.getEventMulticaster()`, the editor the IDE reports as `FileEditorManager.selectedTextEditor`), the composer shows a chip above the text area: `<name>:<start>-<end>` (lines, 1-based) with an `×` (accessibility name `Remove the selection from the message`). Sending with the chip adds a text block before the prompt: `The user's selection in <path>, lines <start>–<end>:` and the selected text in a fenced block (64 KB bound as above). `×` drops the chip for this message; it comes back with the next selection. The chip is also in the bubble, and on reload. A selection in a diff editor or a console is ignored (`DiffUtil.isDiffEditor`, no `VirtualFile`). Nothing is sent when the chip is absent: the model never sees the selection by accident.

Both blocks are plain `text` blocks of the same `user` message (`HostMessages.userMessage` grows `context: List<ContextBlock>`), in front of the prompt's own block, as `user-image` records several blocks in one message (`docs/protocol.md` 3). A context-only message (chip, no text) is refused with the composer's existing empty-draft rule.

### 3.6 Settings

No new setting. Settings | Tools | Tandem is unchanged; the IDE's "Open diff as editor tab" governs the diff's placement (3.1).

### 3.7 Every string (hard-rule-8 checklist; none says "Claude Code")

Diff: `Tandem: <file name>`, `Current`, `Current (unsaved changes)`, `New file`, `Proposed by Claude`, `Claude proposes an edit to <file name>. Change the right side as you like, then accept or reject.`, `Reason (optional): goes back to Claude with a rejection`, `Accept`, `Accept all`, `Reject`, the three tooltips of 3.2, `Tandem: edit <file name>`, `Tandem: create <file name>`. Rows: the labels and tags of 3.4, `Show diff`, `Claude stopped waiting for this edit.`, `Accepting all edits this turn`. Notices (7): `Edits are off in this session: claude <version> did not take Tandem's edit tools. Update claude or use the terminal for edits.`, `Could not apply the edit to <file>: <reason>`, `The diff could not be opened: <reason>`. Composer: `Remove the selection from the message`, the chip texts of 3.5. Tool results (4.3) are for the model and may say "Claude" but never "Claude Code".

### 3.8 Semantics and test hooks

`Modifier.testTag`: `edit-row-<tool use id>`, `edit-show-diff-<tool use id>`, `mention-palette`, `mention-row-<index>`, `selection-chip`, `selection-chip-remove`. The diff's bar: Swing names `tandem.edit.accept`, `tandem.edit.acceptAll`, `tandem.edit.reject`, `tandem.edit.reason` (`JComponent.setName`), which the IDE tests find with the UI Driver's `byAccessibleName` or `xpath` on the name. The accessible names are the button labels; the reason field's is `Reason for rejecting`.

## 4. Data model

### 4.1 `protocol/` (every field validated; a mistyped frame is `Other`, never a throw)

**`initialize`** (`HostMessages.initialize`) gains `"sdkMcpServers":["tandem"]`:

```json
{"type":"control_request","request_id":"<id>","request":{"subtype":"initialize","hooks":null,"forwardSubagentText":true,"sdkMcpServers":["tandem"]}}
```

**`InboundRequest.McpMessage(serverName: String, message: JsonObject)`**, decoded from `{"subtype":"mcp_message","server_name":<string>,"message":<object>}` (`r1:1`, `:4`, `:6`, `:38`); a frame without a string `server_name` or an object `message` stays `Other` and gets today's `Unsupported request` error. The JSON-RPC inside: `method` (string), `id` (absent on a notification; a number in every recorded request, `0` to `3`), `params` (object or absent). `tools/call`'s `params` are `{name, arguments, _meta:{"claudecode/toolUseId": <tool_use id>, progressToken}}` (`r1:38`); `notifications/cancelled`'s are `{requestId, reason}` (`r2:41`).

**`HostMessages.mcpResponse(requestId: String, reply: JsonObject)`**:

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<id>","response":{"mcp_response":<reply>}}}
```

where `reply` is a JSON-RPC response (`{"jsonrpc":"2.0","id":<id>,"result":…}` or `{"jsonrpc":"2.0","id":<id>,"error":{"code","message"}}`), or for a notification `{"jsonrpc":"2.0","result":{},"id":0}` (what the Agent SDK answers, **binary**; `r1/sent.ndjson:5`).

**`TandemTools`** (pure Kotlin in `protocol/`, no IntelliJ): the server's answers.

| JSON-RPC `method` | Reply `result` |
|---|---|
| `initialize` | `{"protocolVersion": <the request's>, "capabilities": {"tools": {}}, "serverInfo": {"name": "tandem", "version": <plugin version>}}` (`r1/sent.ndjson:2`; the CLI asked `2025-11-25`, `r1:1`) |
| `notifications/initialized`, any notification | the empty result above |
| `ping` | `{}` (MCP spec; **unrecorded**) |
| `tools/list` | `{"tools": [edit_file, write_file]}` (below) |
| `tools/call` | handed to `HostTools` (4.2); the reply is `{"content": [{"type": "text", "text": <text>}]}` or `{"isError": true, "content": [{"type": "text", "text": <text>}]}` |
| `notifications/cancelled` | the empty result, and `HostTools.cancel(requestId)` |
| anything else | `{"jsonrpc":"2.0","id":<id>,"error":{"code":-32601,"message":"Method not found: <method>"}}` |

The tools, as `tools/list` lists them (names and descriptions are the wire; the schema is JSON Schema):

```json
{"name":"edit_file","description":"Replace one occurrence of old_string with new_string in the file at file_path (every occurrence with replace_all). The match is exact and runs against the file as open in the IDE, unsaved changes included. The user reviews the change in a diff and may alter it before it lands; the result tells you what was applied.","inputSchema":{"type":"object","properties":{"file_path":{"type":"string","description":"Absolute path, inside the project or an added folder"},"old_string":{"type":"string"},"new_string":{"type":"string"},"replace_all":{"type":"boolean","default":false}},"required":["file_path","old_string","new_string"]}}
{"name":"write_file","description":"Create the file at file_path with content, or replace an existing file's content. The user reviews it in a diff and may alter it before it lands; the result tells you what was applied.","inputSchema":{"type":"object","properties":{"file_path":{"type":"string"},"content":{"type":"string"}},"required":["file_path","content"]}}
```

The CLI names them `mcp__tandem__edit_file` and `mcp__tandem__write_file` in `init.tools`, `tool_use` blocks and `can_use_tool` (`r1:8`, `:35`, `:37`), with `tool_use_meta:[{display_name:"Edit File", server_display_name:"tandem", tool_name:"edit_file"}]` on the assistant line (`:35`).

**Decoded, not new:** the `can_use_tool` of an SDK tool has exactly `subtype, tool_name, mcp_server{name, source:"sdk"}, display_name, input, permission_suggestions, server_policy_ask_only, tool_use_id` (`r1:37`, `r2:66`): today's `CanUseTool` decodes all of it (`docs/protocol.md` 4.3); `McpServerRef.source` reads `sdk`. `mcp_status` lists the server with `transport:"sdk"` (new key, ignored by `ControlPayloads.mcpStatus`) and `source:"sdk"` (`r1:63`); before the first prompt it is absent (`r1:3`). The result line of a call (`r1:41`): `tool_result.content` is the block array and `tool_use_result` the same array (no `structuredContent`, since the server declares no output schema), decoded as today's `JsonElement`; an `isError` reply becomes `content` as a string, `is_error:true`, `tool_use_result:"Error: <text>"` (`r1:95`), the shape every failed tool has.

### 4.2 `session/`

- `SessionConfig.argv()`: unchanged flags; `--disallowedTools Edit,Write,MultiEdit,NotebookEdit` stays.
- `ClaudeSession` takes a `HostTools` (an interface in `session/`: `suspend fun call(call: ToolCall): ToolReply`, `fun cancel(requestId: Long)`, with `ToolCall(name, arguments: JsonObject, toolUseId: String?, requestId: Long, session: SessionId)` and `ToolReply(text: String, isError: Boolean)`), given by `TandemSessions` and null in tests that want none. `readLoop` routes `InboundRequest.McpMessage` for `server_name == "tandem"` to `TandemTools.answer(message, hostTools)` in the session's scope (`Dispatchers.Default`; a `tools/call` runs in its own child job so the reader keeps reading, and the reply goes out with `send(HostMessages.mcpResponse(...))`); another `server_name` is answered with the JSON-RPC error `-32601` and logged (never left hanging). `notifications/cancelled` cancels that child job (`HostTools.cancel`) and is acknowledged.
- **Auto-allow.** A `CanUseTool` whose `toolName` starts with `mcp__tandem__` is answered at once with `PermissionAnswers.allowOnce(p)` (the input echoed, no permission update) and never reaches `pendingPermissions`: `Reducer` drops it, `ClaudeSession` sends the allow. Rationale: the diff is the question (3.4). The suggestion the CLI sends (`addRules` for the whole tool, `r1:37`) is not applied: the request keeps coming in every mode anyway (`r2:66`).
- **Version gate** (hard rule 5): if the first `system/init` after a spawn lists no `mcp__tandem__edit_file` in `tools` (a CLI that ignored `sdkMcpServers`, or one that failed the handshake), the session shows the notice `Edits are off in this session: claude <version> did not take Tandem's edit tools. Update claude or use the terminal for edits.` once per process, with `init.claude_code_version`; the session goes on (Claude can still read and run commands).
- `ChatState.edits: Map<String, EditReview>` keyed by tool use id, `EditReview(file: String, phase: QUEUED | OPEN | DONE, outcome: ACCEPTED | ACCEPTED_CHANGED | AUTO | REJECTED | STOPPED | null)`, set by `SessionEvent.EditPhase(toolUseId, review)` from `TandemEdits` (4.4); cleared with the entries on `/clear`. `ChatState.acceptAll: Boolean`, set by `SessionEvent.AcceptAll(true)` and reset at the turn's `result`.
- `SessionHandle.hostTools` is the project's `TandemEdits` (one per project); a session's stop or close cancels its open and queued calls (7.11).

### 4.3 The edit itself (`ide/edits/`, with a pure core in `session/edits/`)

**`EditMatcher`** (pure Kotlin, `session/edits/EditMatcher.kt`): `match(text: String, oldString: String, replaceAll: Boolean): Match` where `Match` is `Found(ranges)`, `NotFound`, `Ambiguous(count)`; exact matching, as the CLI's `Edit` (its quote and whitespace normalisation is **not** mirrored this round, 11 item 6). `apply(text, match, newString): String`. `snippet(text, ranges, context = 4): String` numbers the changed region with `cat -n` style lines (`%6d\t`), as the CLI's `Edit` result does. `unifiedDiff(a: String, b: String, aName, bName, maxLines = 400): String` (a small Myers diff, no library; truncated with `… <n> more lines`).

**`EditResults`** (pure Kotlin): the texts the model gets. `<path>` is the absolute path as the model gave it.

| Case | `isError` | Text |
|---|---|---|
| Accept, proposal unchanged | no | `Applied the edit to <path>.\nThe edited region (line numbers as in the file):\n<snippet>` |
| Accept, proposal changed by the user | no | `Applied to <path>. The user changed your proposal in the diff before accepting it.\nWhat changed between your proposal and what was applied:\n<unified diff proposal → applied, 400 lines at most>\nThe file now reads (lines 1–<N> of <N>):\n<whole file, cat -n style>` when the file has at most 2000 lines and 64 KB; else the last block is `The file now has <N> lines; the edited region:\n<snippet of the applied ranges>` and `Read <path> for the rest.` |
| Auto-accepted | no | `Applied the edit to <path> (auto-accepted by the user's mode).\nThe edited region (line numbers as in the file):\n<snippet>` |
| `write_file`, new file | no | `Created <path> (<N> lines).` (`(… and the user changed your proposal before accepting it; the file now reads:\n<whole file>)` when changed, same bounds) |
| `write_file`, existing file | no | `Replaced the content of <path> (<N> lines; +<a> −<r> against the previous content).` with the same changed-by-the-user addition |
| Reject | yes | `The user rejected this edit in the IDE; nothing was changed.` + `\nThe user says: <reason>` when typed |
| Stop | (no reply: the CLI withdrew the call and wrote its own result, `r2:43`) | |
| `old_string` not found | yes | `old_string was not found in <path>. The file as open in the IDE (unsaved changes included) reads:\n<whole file, cat -n style, 2000 lines / 64 KB; beyond that the first 200 lines and "… <N> lines in all; Read the file for the rest">` |
| `old_string` found `n` > 1 times, no `replace_all` | yes | `old_string matches <n> places in <path>. Make it unique with more context, or pass replace_all: true.` |
| `old_string` equals `new_string` | yes | `old_string and new_string are the same; nothing to change.` |
| the file does not exist (`edit_file`) | yes | `<path> does not exist. Use write_file to create it.` |
| a directory | yes | `<path> is a directory.` |
| outside the project and its added folders | yes | `<path> is outside the project and its added folders; Tandem edits files there only.` |
| binary | yes | `<path> is a binary file; Tandem edits text files only.` |
| too large for the IDE | yes | `<path> is too large for the IDE to open as text (<size>).` |
| read-only and not made writable | yes | `<path> is read-only and could not be made writable in the IDE.` |
| the IDE refused the write | yes | `The IDE could not apply the edit to <path>: <reason>.` |
| plan mode (7.9) | yes | `The session is in plan mode: Tandem applies no edits until the plan is approved.` |
| unknown tool name | JSON-RPC error `-32602` `Unknown tool: <name>` | |
| malformed arguments (a missing or non-string field) | yes | `edit_file needs file_path, old_string and new_string as strings.` / `write_file needs file_path and content as strings.` |

Every result stays under 100 KB; the whole-file and diff blocks shrink in that order to fit.

**`IdeEditTools`** (`ide/edits/IdeEditTools.kt`, the `HostTools` of the project): resolves the path (absolute, normalised, under the project's base path or one of the session's added folders, both compared after `toRealPath()` as the transcript folder does), finds the `VirtualFile` (`LocalFileSystem.getInstance().refreshAndFindFileByNioFile`, on `Dispatchers.IO`), checks `isDirectory`, `fileType.isBinary` / `FileTypeRegistry`, `FileUtilRt.isTooLarge(length)`, reads the `Document` with `FileDocumentManager.getDocument(file)` in a `readAction`, matches, builds the proposal, and hands `EditProposal(file, document?, original: String, proposed: String, ranges)` to `TandemEdits` (4.4), which shows the diff or applies at once. Applying: `ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(file)` first (it may check the file out of VCS or ask; a refusal is the read-only error), then `WriteCommandAction.writeCommandAction(project).withName(...).run` on the EDT (`withContext(Dispatchers.EDT)`): `document.replaceString(range)` per matched range from the last to the first when the proposal is unchanged, else `document.setText(final)`; a new file: `createDirectories`, `createChildData(this, name)`, then its `Document`'s `setText`. `PsiDocumentManager.commitDocument` after, so inspections follow. A thrown exception becomes the "IDE refused" error and the notice `Could not apply the edit to <file>: <reason>` in the chat (hard rule 5). Nothing here blocks the EDT beyond the write action itself: file lookup and matching run on `Dispatchers.IO` / `Default`, the write on the EDT is the IDE's own.

### 4.4 `ide/` and `chat/`

- **`TandemEdits`** (project service, `ide/edits/TandemEdits.kt`): the `EditQueue` (FIFO of `EditProposal`s with their `CompletableDeferred<ToolReply>`), `showNext()` on the EDT, the diff builder `EditDiff` (3.1, 3.2) and the outcome reporter (`SessionEvent.EditPhase` to the session). The queue's head opens; Accept / Reject / cancel complete its deferred and open the next. `cancel(requestId)` removes a queued proposal or closes the open diff (`FileEditorManager.closeFile(diffFile)`) and completes with `Stopped`, which sends no reply (the CLI withdrew the call, `r2:41`).
- **`EditDiff`**: builds the request of 3.1, the bar of 3.2 (a `JPanel` with `JBTextField` and `JButton`s; `DialogWrapper` is not used), binds the shortcuts, and resolves to `Accepted(finalText)`, `AcceptedAll(finalText)` or `Rejected(reason)`. `onAssigned(false)` with no decision resolves `Rejected(null)`. The final text is read from the right `DocumentContent.getDocument()` on the EDT.
- **`chat/`**: `ToolKinds` adds `mcp__tandem__write` → `CREATE`; `ToolStates.isDenial` adds the `The user rejected this edit` prefix; `ToolLabels.fileForms` gets the denied form for `CREATE` (`Rejected creating`); `ChatRowsBuilder` reads `ChatState.edits` for the phase labels and the `Show diff` action of 3.4, and `ChatState.acceptAll` for the footer hint; `ToolBodies` counts `DiffStats` from the input for `EDIT` and `CREATE` when the result has no `structuredPatch`.
- **Context** (3.5): `chat/MentionPalette.kt` (pure: filtering and picking over a `List<String>` of candidates, the `@` detection in the draft, the insertion), `ui/MentionPaletteView.kt` on `SlashPaletteView`'s rows, `ide/context/ProjectFiles.kt` (the candidate list and the matcher, off the EDT), `ide/context/EditorSelection.kt` (a `StateFlow<Selection?>` from the multicaster, read by the composer), `ComposerSender` (the context blocks, the dirty-buffer swap), `HostMessages.userMessage(text, images, uuid, later, context)`, `ChatEntry.User.context: List<ContextChip>` filled by the reducer from the sent blocks and by `TranscriptReader` from a user line whose first text blocks start with `The user's selection in ` or match `<path> as open in the IDE, with unsaved changes`.

## 5. Components per layer

| Layer | New | Changed |
|---|---|---|
| `protocol/` | `InboundRequest.McpMessage`, `HostMessages.mcpResponse`, `TandemTools` (handshake answers, the tool list), `JsonRpc` helpers | `HostMessages.initialize` (`sdkMcpServers`), `HostMessages.userMessage` (context blocks), `CliMessage` decoding of `mcp_message` |
| `session/` | `HostTools`, `ToolCall`, `ToolReply`, `edits/EditMatcher`, `edits/EditResults`, `SessionEvent.EditPhase`, `SessionEvent.AcceptAll`, `ChatState.edits`, `ChatState.acceptAll`, `EditReview` | `ClaudeSession` (routing, auto-allow, the version-gate notice), `Reducer` (the `CanUseTool` drop for Tandem's tools, the edit events, `acceptAll` reset), `SessionHandle` / `SessionManager` (the `HostTools` hand-over, cancellation on stop), `TranscriptReader` (context chips) |
| `chat/` | `MentionPalette`, `ContextChip` | `ToolKinds`, `ToolStates`, `ToolLabels`, `ToolBodies`, `ChatRowsBuilder`, `ComposerModel` (the footer hint) |
| `ide/` | `edits/TandemEdits`, `edits/EditQueue`, `edits/EditDiff`, `edits/IdeEditTools`, `context/ProjectFiles`, `context/EditorSelection` | `TandemSessions` (gives the manager the project's `TandemEdits`), `plugin.xml` (the service) |
| `ui/` | `MentionPaletteView`, the selection chip in `PromptBar`, the `Show diff` row action in `ToolRowItem`, chips in `UserBubble` | `ComposerKeys` (`@` palette keys), `ComposerSender`, `FooterControls` (the `Accept all` hint) |
| tooling | `drive_cli.py` scenarios `ide-edit`, `ide-edit-stop`, `ide-mention`; `redact_fixture.py` learns `claudecode/toolUseId`; `fake-claude.py` serves `mcp_message` | `record-scenario.sh`'s list, `cli-contract.yml`, `ContractTest` |

## 6. Flows

**An edit, accepted with a hand change.** The model calls `mcp__tandem__edit_file` (`r1:35`) → `can_use_tool` (`:37`) → Tandem answers `allow` at once → `tools/call` (`:38`) → `ClaudeSession` hands the call to `IdeEditTools` on `Dispatchers.Default` → path and file checks on IO, the `Document` read in a `readAction`, `EditMatcher.match` → `TandemEdits.enqueue(proposal)` → the row reads `Reviewing <file> in the diff` → `EditDiff` opens on the EDT → the user edits the right side, presses Ctrl+S → `Accepted(finalText)` → `WriteCommandAction` on the EDT → `EditResults.accepted(...)` → `send(HostMessages.mcpResponse(...))` → the CLI writes the `user` tool result (`:41`) and the model goes on (`:57`). The row reads `Edited <file>` + `with your changes`.

**An edit, rejected.** As above until `Reject` with the reason → `ToolReply(isError = true)` → `r1:95`'s shape → the model answers (`:108`); the turn's `result` is a success with empty `permission_denials` (`:112`): the row is `Rejected edit of <file>` (DENIED), no permission card, no turn failure.

**Auto-accept.** `tools/call` → the checks → `ChatState.permissionMode` is `acceptEdits` → apply at once → `(auto-accepted)` result (`r2:68` under `acceptEdits`).

**Stop while the diff is open.** The user presses Stop → `interrupt` → the CLI sends `notifications/cancelled {requestId}` (`r2:41`) → `TandemEdits.cancel` closes the diff, the row goes `INTERRUPTED` with the CLI's own synthetic result (`:43`), the turn ends `aborted_tools` (`:45`), nothing is sent for the call.

**A mention.** The user types `@Foo` → the palette lists `src/main/kotlin/Foo.kt` → Enter inserts `@src/main/kotlin/Foo.kt ` → send → the text goes as typed → the CLI attaches the file (`transcript.jsonl:6`) → the reply. With unsaved changes in `Foo.kt`: the block of 3.5 goes first and the mention loses its `@`.

## 7. Errors and edge cases

1. **The file is not open in an editor.** `FileDocumentManager.getDocument(file)` loads a `Document` for any text file; the edit applies to it and the IDE holds it as a modified buffer (saved by the IDE's usual rules), and the file opens in no editor. The row's `Show diff` opened the diff only; a later click of the file name in the row opens the file (`OpenInEditor`, as today's rows do).
2. **A new file** (`write_file`, no such path): the diff shows `New file` on the left; Accept creates it (4.3). A path whose parent does not exist: the directories are created in the same command. A `write_file` for an existing file shows the whole file on both sides.
3. **The old string no longer matches** (the user edited the file, or the model never read it): the `NotFound` result with the buffer's text (4.3, `r2:104`: the model read it and did not retry). `Ambiguous` likewise. The match runs on the `Document`, so a buffer with unsaved changes is matched as the user sees it, not as the disk has it.
4. **Outside the project**: refused with the path rule (4.3); the project's base path and the session's added folders (`--add-dir`) are the roots, as the CLI's own tools have.
5. **Read-only**: `ensureFilesWritable` (VCS checkout or the IDE's "make writable" prompt, which is modal and on the EDT by the platform's design; it is the IDE's own dialog); a refusal is the read-only error. A read-only file system likewise.
6. **Very large**: `FileUtilRt.isTooLarge` (the IDE's own limit, `idea.max.intellisense.filesize` / `idea.max.content.load.filesize`) refuses the edit; the model is told the size. A `write_file` whose `content` is over 10 MB is refused the same way before any document is made.
7. **Binary**: `FileType.isBinary` (and `FileTypeRegistry.isFileOfType(file, UnknownFileType)` with a NUL in the first 8 KB) refuses the edit.
8. **A directory, a symlink**: a directory is refused; a symlink is followed (`toRealPath`) and must land inside a root.
9. **Plan mode**: the CLI's own `Write` is disallowed, so the model may try `write_file` for its plan file; Tandem refuses with the plan-mode text (4.3). Under `plan`, a `ExitPlanMode` approval switches the mode as today; edits start after.
10. **A `tools/call` while a diff of another session is open**: queued (3.4). A queued call's session stops: removed, no reply.
11. **Stop, close, the process dying**: Stop → `notifications/cancelled` (`r2:41`) → closed, no reply. The session closed or its process gone while a diff is open → `TandemEdits.cancel` for every call of that session, the diff closes with no reply (nobody reads it), the row shows the process's end as today. The project closing disposes `TandemEdits`, which closes any diff.
12. **The CLI does not take the server**: the version-gate notice (4.2). Also when `mcp_message` frames stop coming after `initialize` (a handshake the CLI gave up on): the same check on `init.tools` covers it.
13. **A JSON-RPC method Tandem does not serve** (`resources/list`, `prompts/list`, …): the `-32601` error. The CLI asked for none in the probes (`r1`, `r2`: `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `notifications/cancelled` only).
14. **Two calls for the same file in one turn**: each diff shows the file as it is when its turn comes, so the second proposal is matched against the first's applied text (the model sends the second call only after the first's result).
15. **The diff's window closed by the IDE** (project reload, "close all"): `onAssigned(false)` → `Rejected(null)`.
16. **The result is huge** (a 64 KB file quoted): the bounds of 4.3; the model is told to `Read` the rest.
17. **A mention of a path that does not exist**: inserted as typed; the CLI's expansion of a missing path is **unrecorded** (probes 3): the bubble draws no chip for it.
18. **The selection is in the diff's right side**: ignored (3.5).
19. **`Accept all` then a `write_file` of a file outside the roots**: the checks come first; auto-accept never widens them.

## 8. Performance

The match and the result text run on `Dispatchers.Default`; file lookups on `Dispatchers.IO`; the only EDT work is the diff's construction and the write command, which the platform requires there. A 64 KB file numbers in under a millisecond; the Myers diff is bounded at 400 output lines and gives up (falls back to the whole-file block) past 20,000 input lines. The mention palette's candidate list is computed off the EDT and cached per project until the VFS changes (`VirtualFileManager` listener), at most 40 rows shown.

## 9. Testing

Every new frame rests on a fixture recorded with `scripts/record-scenario.sh` against the real CLI, redacted, with its transcript; until the recordings land, the tests below that need them are written against the probe runs' redacted copies kept out of the repository and marked `@Disabled("needs fixture ide-edit")`, never deleted.

### 9.1 Recordings (`scripts/drive_cli.py`, `scripts/record-scenario.sh`, `scripts/test_drive_cli.py`)

| Scenario | Host frames | What it records |
|---|---|---|
| `ide-edit` | `initialize` with `sdkMcpServers:["tandem"]`; the handshake answered (`initialize`, `notifications/initialized`, `tools/list`); `mcp_status`; a prompt asking for `mcp__tandem__edit_file` on `hello.txt`, allowed, answered with the hand-changed result; `mcp_status`; a prompt asking for `mcp__tandem__write_file`, answered `isError` with a reason | 4.1's frames: the three handshake requests and their timing, the deferred `ToolSearch`, `tool_use_meta`, `can_use_tool` with `source:"sdk"`, `tools/call` with `_meta`, the success and error result lines, `mcp_status` without and with the server (`r1`) |
| `ide-edit-stop` | as above, then: a call held 3 s and `interrupt`; `set_permission_mode acceptEdits` and a call; `set_permission_mode default` and a call whose `old_string` is stale, answered `isError` with the file's text | `notifications/cancelled`, the CLI's synthetic rejection and `aborted_tools`; the request under `acceptEdits`; the stale-string round trip (`r2`) |
| `ide-mention` | every file tool disallowed; two prompts with `@hello.txt` and `@hello.txt#L2` | the expansion without a tool, and the transcript's `attachment` lines (`r3`) |

`scripts/redact_fixture.py` learns the id key `claudecode/toolUseId` (today its safety net replaces it with `<leaked-N>`, probes 1.2) and keeps `init.tools` and `init.mcp_servers` for the `ide-edit*` fixtures (`INIT_KEEP`). The nightly `cli-contract.yml` records `ide-edit`, and `ContractTest` checks that `mcp_message` frames decode and `init.tools` names both tools. `test_drive_cli.py` unit-tests the three scenarios' control calls.

### 9.2 Protocol fixture tests (`FixtureDecodeTest`, `HostMessagesTest`, `RobustnessTest`, `TandemToolsTest`)

- `FixtureDecodeTest`: `ide-edit:1` decodes to `McpMessage("tandem", initialize)`; `:4` a notification without `id`; `:38` a `tools/call` with `_meta["claudecode/toolUseId"]` equal to `:35`'s block id; `:37` a `CanUseTool` with `mcpServer.source == "sdk"` and `serverPolicyAskOnly`; `:41` a `ToolResult` whose `content` is a block array and `isError` false; `:95` an error result with `tool_use_result` string; `ide-edit-stop:41` `notifications/cancelled` with `requestId`; `:43` the synthetic result with `non_execution_kind:"user-rejected"` and no `is_error`; `:45` `aborted_tools`.
- `HostMessagesTest`: `initialize` carries `sdkMcpServers:["tandem"]` and still `forwardSubagentText`; `mcpResponse` wraps the reply under `mcp_response` with the request id; `userMessage` puts context blocks before the prompt's block and sends none when the list is empty.
- `RobustnessTest`: `mcp_message` without `server_name`, with a non-object `message`, with a `message` lacking `method`, each decodes to `Other` / an error reply, never a throw; a `tools/call` with `arguments` missing yields the malformed-arguments error; a 1 MB `content` yields the size error.
- `TandemToolsTest`: every row of 4.1's table, including `-32601` for `resources/list`, the empty result for a notification, the `protocolVersion` echo, and that `tools/list` is byte-stable (a golden JSON in `src/test/resources/tandem-tools.json`).

### 9.3 Session (`ClaudeSessionTest`, `ReducerTest`, `EditMatcherTest`, `EditResultsTest`)

- `ClaudeSessionTest`: the scripted process sends the handshake and a `tools/call`; the session answers each in order while a text turn streams; a `tools/call` is answered from the fake `HostTools` off the reader; `notifications/cancelled` cancels the pending call's job and the late reply is not sent; an unknown `server_name` gets `-32601`; a `can_use_tool` for `mcp__tandem__edit_file` is answered `allow` with the input and raises no `pendingPermissions`; the version-gate notice fires when `init.tools` lacks the tool and not when it has it; a session closed with a call open cancels it.
- `ReducerTest`: `EditPhase` events set `ChatState.edits`; `AcceptAll` sets and the `result` resets `acceptAll`; the Tandem `CanUseTool` is dropped; `/clear` clears `edits`.
- `EditMatcherTest`: exact match, `replace_all`, ambiguity count, CRLF text (the `Document` has `\n`; the file's separator is restored by the IDE on save), the snippet's numbering at the file's start and end, the unified diff on an insertion, a deletion, a change, and the 400-line cut.
- `EditResultsTest`: every text of 4.3's table, the 2000-line / 64 KB / 100 KB bounds.

### 9.4 Pure presentation (`ToolKindsTest`, `ToolStatesTest`, `ToolLabelsTest`, `ToolBodiesTest`, `ChatRowsBuilderTest`, `MentionPaletteTest`)

- `ToolKinds`: `mcp__tandem__write_file` → `CREATE`; `ToolStates`: the rejection text → `DENIED`, the Stop result → `INTERRUPTED`; `ToolLabels`: the eight labels of 3.4; `ToolBodies`: `DiffStats` from the input; `ChatRowsBuilder`: the phase labels, the tags, `Show diff` only in `OPEN`, the footer hint while `acceptAll`.
- `MentionPaletteTest`: `@` at the start and after a space opens, `@` inside a word does not, the filter, the insertion with the trailing space, Esc closes until the draft changes, 40 rows at most.

### 9.5 Platform tests (`IdeEditToolsTest`, `EditDiffTest`, `EditQueueTest`, `TandemEditsTest`, `ProjectFilesTest`, `EditorSelectionTest`, `ComposerSenderTest`, `TranscriptReaderTest`)

- `IdeEditToolsTest` (headless, a light project): an edit applies to the `Document` of a file open in no editor; one `UndoManager` undo restores it; an unsaved buffer is matched, not the disk; a new file is created with its directories; a read-only file (`VirtualFile.setWritable(false)`) is refused with the text of 4.3; a binary file (a `.png` header), a directory, a path outside the base path, a file over the IDE's limit (the property lowered for the test) are each refused with their text; `write_file` over an existing file replaces it; `plan` mode refuses; a thrown `IOException` in the write becomes the "IDE refused" error and a notice.
- `EditDiffTest` (headless): the request has `FORCE_READ_ONLY_CONTENTS [true,false]`, the titles of 3.1 (`Current (unsaved changes)` for a dirty document, `New file` for a new one), the bottom panel's three buttons and field with their names; typing into the right document and `Accept` resolves `Accepted` with the changed text; `Reject` with a reason resolves `Rejected(reason)`; `onAssigned(false)` resolves `Rejected(null)`; the Accept action carries the `SaveAll` shortcut set.
- `EditQueueTest`: FIFO, one open at a time, cancel of a queued and of the open one, the next opens after a resolution.
- `TandemEditsTest`: the outcomes reach the session as `EditPhase` events; `Accept all` applies the next call without a diff and ends at the result; a stopped session's calls are dropped.
- `ProjectFilesTest`: the candidate list from a light project, the matcher, open editors first; `EditorSelectionTest`: the flow follows a selection and clears on a caret move; `ComposerSenderTest`: the context blocks and the `@` swap for a dirty file; `TranscriptReaderTest`: `ide-mention`'s transcript reloads with the mention as typed and no chip; a user line with a selection block reloads with the chip.

### 9.6 The fake CLI (`src/test/resources/fake-claude.py`)

For a fixture that recorded `mcp_message` frames, the fake sends them in the recorded order and waits for the host's `mcp_response` to each request before going on (`tools/list` before the first turn's `init`, as recorded); a replayed `user` tool-result line for a `tools/call` is rewritten with the host's reply: `content` from `mcp_response.result.content`, `is_error` and the `Error: ` form of `tool_use_result` from `isError`, so the IDE tests see the text Tandem produced. `TANDEM_FAKE_EDIT_CANCEL_MS` makes the fake send `notifications/cancelled` for a `tools/call` that stays unanswered that long, then the recorded synthetic rejection (the `ide-edit-stop` lines). A `can_use_tool` for a Tandem tool is sent as recorded; the fake does not check the answer.

### 9.7 IDE Starter tests (`ToolWindowIdeTest`, one at a time under the shared lock)

- `an edit opens the diff and Accept applies it`: fixture `ide-edit`; the diff tab titled `Tandem: hello.txt`; `Accept` → the editor shows `beta`, Ctrl+Z restores `alpha`, the fake's log holds the `mcp_response` with `Applied the edit to`, the row reads `Edited hello.txt`.
- `a hand change in the diff reaches Claude`: type `!` after `beta` on the right side, Ctrl+S → the file reads `beta!` and the logged result holds `The user changed your proposal` and `beta!`; the row tag `with your changes`.
- `Reject with a reason sends an error result`: the reason typed, `Reject` → `isError:true` and the reason in the log; the row `Rejected edit of hello.txt`; the turn's footer appears (the turn went on).
- `Accept all skips the next diffs`: a fixture with two edit calls (`ide-edit` played twice); the second lands without a diff tab.
- `acceptEdits applies without a diff`: the mode picked in the footer, then the edit: no diff tab, the file changed, the row `Edited hello.txt` + `auto-accepted`.
- `Stop closes the diff`: `TANDEM_FAKE_EDIT_CANCEL_MS` unset; Stop pressed with the diff open → the tab closes, the row `INTERRUPTED`, no `mcp_response` for the call in the log.
- `typing at opens the file palette`: `@hel` lists `hello.txt`, Enter inserts `@hello.txt `, send → the logged user text holds `@hello.txt`.
- `a selection goes with the prompt`: lines selected in an editor → the chip; send → the logged message's first block starts with `The user's selection in`; `×` on the chip → no block.

The macOS IDE run (`macos-ide-test.yml`) covers ⌘S.

### 9.8 Manual `runIde` checklist (feel; the owner)

The diff's focus lands on the right side; Ctrl+S inside the diff accepts and never triggers Save All; the editor-tab preference is honoured; undo after an accepted edit is one step; the mention palette does not steal Enter from a plain send; the selection chip does not flicker while typing in the editor.

## 10. Task slices (SDD; each green on its own; dependencies in brackets)

Model tiers: implementation tasks and per-task reviewers run on Opus; T1's recordings, T11's docs and the test scaffolding of T8 run on Sonnet; the final whole-branch review and any task that failed review twice run on Fable.

- **T1 Recordings and the redactor** (Sonnet): `drive_cli.py` scenarios `ide-edit`, `ide-edit-stop`, `ide-mention` with `test_drive_cli.py`; `redact_fixture.py`'s new key and `INIT_KEEP`; the three fixtures and transcripts recorded on a signed-in `claude`; `cli-contract.yml` and `ContractTest`.
- **T2 Protocol** [T1]: `InboundRequest.McpMessage`, `HostMessages.initialize` / `mcpResponse` / `userMessage(context)`, `TandemTools` with the golden tool list; 9.2.
- **T3 Session routing** [T2]: `HostTools`, `ClaudeSession`'s routing and cancellation, the auto-allow, the version-gate notice; `ChatState.edits` / `acceptAll` and the events; 9.3's session tests.
- **T4 The edit core** [T2]: `EditMatcher`, `EditResults`; 9.3's pure tests.
- **T5 `IdeEditTools`** [T3, T4]: the file checks and the write command; `IdeEditToolsTest`.
- **T6 The diff and the queue** [T5]: `EditDiff`, `EditQueue`, `TandemEdits`, `TandemSessions` wiring; `EditDiffTest`, `EditQueueTest`, `TandemEditsTest`.
- **T7 Chat rows** [T3]: `ToolKinds`, `ToolStates`, `ToolLabels`, `ToolBodies`, `ChatRowsBuilder`, `ToolRowItem`'s `Show diff`, the footer hint; 9.4.
- **T8 The fake and the IDE tests** [T6, T7] (scaffolding on Sonnet, the tests on Opus): 9.6 and the first six tests of 9.7.
- **T9 Mentions** [T2]: `MentionPalette`, `MentionPaletteView`, `ProjectFiles`, `ComposerSender`'s swap, the bubble chips, `TranscriptReader`; 9.4, 9.5 and the seventh IDE test.
- **T10 The selection chip** [T9]: `EditorSelection`, the chip, the block; the eighth IDE test.
- **T11 Docs** [all] (Sonnet): `docs/protocol.md` 1.1 (`--mcp-config` no longer planned), 4.2 (`sdkMcpServers`), 4.5 (`mcp_message` answered), 6.2 (an SDK tool's result), 10 (the new keys), the Sources list; `docs/design.md` Milestone 2 status; CLAUDE.md's `ide/` line and the fake's variables; `README.md`'s claim about edits.
- **T12 Review** (Fable): the whole branch against this spec, then the full IDE suite at the merge.

## 11. Open items

1. **`read_file` seeing unsaved buffers, and `NotebookEdit`'s equivalent**: deferred (2.2) until a real session shows the mismatch loop costing more than one round.
2. **`MCP_TOOL_TIMEOUT`'s default for an SDK server's call** (**binary**, unmeasured): if a diff left open for minutes gets cancelled, the row says so (3.2); a recording with a call held past the default would settle the number and whether `notifications/cancelled` carries another reason.
3. **`sdkMcpServerConfigs` and `sdkMcpServerManifests`**: unused; whether `always_ask` changes the `bypassPermissions` case, and whether a manifest saves the handshake, are unrecorded.
4. **Lifting the deferral** (`ToolSearch` before each first use): unknown whether the CLI offers a way; one extra round per session.
5. **The CLI's own IDE integration** (lock file, `--ide`, `openDiff`, `selection_changed`): the owner may want the CLI's `/ide` status to say "connected" one day; it would be a second channel to the same CLI and is not in this round.
6. **The CLI's `Edit` normalisation** (curly quotes, trailing whitespace, tabs) is not mirrored; a mismatch returns the text and the model retries. Mirror it if the retries show up.
7. **`bypassPermissions` with Tandem's tools**: the request's arrival is **docs only** (the binary's `always_ask` text); 3.3 treats the mode as auto-accept either way, so the only difference is which frame the fixture would show.
8. **Subagent edits** (`parent_tool_use_id`): unrecorded; the diff opens the same way by design.
9. **Mentions with spaces, directories, during a running turn**: unrecorded (probes 3).
10. **Should Accept also save the file to disk?** This spec keeps the IDE's own save rules (the file stays a modified buffer, as if typed). The owner's words say "save"; if the owner expects the file written on Accept, `FileDocumentManager.saveDocument(document)` after the write command is one line, and the CLI's own `Read` would then see it at once. Ask the owner on the first `runIde` pass.
11. **The CLI version claim**: recorded on 2.1.312; the task named 2.1.310. The nightly run tells whether 2.1.31x keeps the shapes.

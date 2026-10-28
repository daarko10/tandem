# IDE-edit probes against claude 2.1.312 (host MCP tools over the control channel, the diff's answers, @path mentions)

Status: research result, 2026-10-28. Companion to `2026-10-21-mcp-probes.md` (same method, same citation rules) and to `docs/protocol.md` 4.2, 4.3, 4.5, 6.2. Trigger: Milestone 2 (`docs/design.md`, `ide/`: "Tandem MCP server (edit tools via WriteCommandAction), diff preview, file/selection context") and the owner's words for this round: "when showing edits allow me to edit stuff on the diff view and when I click save it will send the latest version of the file to the llm as a result".

Audience: the engineer writing the IDE-edits spec (`docs/superpowers/specs/2026-10-28-ide-edits-design.md`) and the session code that will serve the tools.

How to read this file:

- Every claim marked **probe run** cites `r1:<line>`, `r2:<line>` or `r3:<line>`: the three runs' raw stdout under `scratch/probes/ide-edits/r<N>/raw.ndjson` (gitignored, not fixtures; `redacted.ndjson` beside each is `scripts/redact_fixture.py`'s output, `sent.ndjson` the host's frames). Every cited line was opened while writing this. The spec's testing section names the `scripts/record-scenario.sh` scenarios that turn these runs into committed fixtures; until they exist, nothing in `docs/protocol.md` may cite them.
- Every claim marked **binary** comes from the strings of the installed `claude` 2.1.312 (`strings -n 6 /opt/claude-code/bin/claude`, a Bun-compiled native binary with its JavaScript bundle inside; read, not run) and names the string it rests on. Nothing was read from `~/.claude/.credentials*`, keychains or keys.
- Every claim marked **javap** comes from `javap` over the IU 2026.1.5 jars in the Gradle cache (`intellij.platform.diff.jar` and the rest of `lib/`), the platform Tandem compiles against.
- The probes ran `claude` 2.1.312 (`/opt/node22/bin/claude`; the task named 2.1.310, the sandbox has 2.1.312) on `claude-sonnet-5-5` with `scripts/drive_cli.py`'s `Cli` class: its argv (`--output-format stream-json --verbose --input-format stream-json --include-partial-messages --permission-prompt-tool=stdio`) **without `--safe-mode`** (safe mode turns MCP servers off), plus `--strict-mcp-config --setting-sources project,local --disallowedTools Edit,Write,MultiEdit,NotebookEdit`, in an empty temporary directory holding one file `hello.txt` (`alpha\nline two\n`). The probe scripts are `scratch/probes/ide-edits/probe.py`, `probe2.py` and `probe3.py`.
- Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

---

## 1. Questions and answers

### 1.1 How do a host's tools reach the CLI? Answer: `initialize.sdkMcpServers`, then `mcp_message` control requests. No port, no sidecar.

**Probe run.** The host's `initialize` named one in-process server, `{"subtype":"initialize","hooks":null,"sdkMcpServers":["tandem"]}` (`r1/sent.ndjson:1`). The CLI then drove the MCP handshake over the control channel as `control_request` frames of subtype `mcp_message`, each carrying one JSON-RPC message under `message` and the server's name under `server_name`:

```json
{"type":"control_request","request_id":"<uuid>","request":{"subtype":"mcp_message","server_name":"tandem","message":{"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"claude-code","title":"Claude Code","description":"Anthropic's agentic coding tool","websiteUrl":"https://claude.com/claude-code","version":"2.1.312"}},"jsonrpc":"2.0","id":0}}}
```

(`r1:1`). The host answers with a success whose `response` carries the JSON-RPC reply under `mcp_response`:

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<uuid>","response":{"mcp_response":{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"tandem","version":"0.3.0"}}}}}}
```

(`r1/sent.ndjson:2`). Order and timing (`r1:1-8`): the MCP `initialize` request (`id` 0) arrives **before** the CLI's own `initialize` response (`r1:1`, then `:2`); `notifications/initialized` (no `id`, `r1:4`) and `tools/list` (`id` 1, `r1:6`) arrive only after the host's first user message, between its `command_lifecycle` lines (`:5`, `:7`), before `system/init` (`:8`). An `mcp_status` sent before the first prompt lists **no** server, `{"mcpServers":[]}` (`r1:3`): the CLI connects an SDK server lazily, with the first prompt. After a turn `mcp_status` lists it with `transport:"sdk"`, `source:"sdk"`, `scope:"dynamic"`, the `serverInfo` the host gave and the tools' names (`r1:63`):

```json
{"mcpServers":[{"name":"tandem","status":"connected","serverInfo":{"name":"tandem","version":"0.3.0"},"transport":"sdk","scope":"dynamic","source":"sdk","tools":[{"name":"edit_file","annotations":{}},{"name":"write_file","annotations":{}}]}]}
```

A notification is answered with an empty JSON-RPC result (`{"mcp_response":{"jsonrpc":"2.0","result":{},"id":0}}`, `r1/sent.ndjson:5`), which is what the Agent SDK itself answers (**binary**: `if(n.onmessage)n.onmessage(r.message);return{mcp_response:{jsonrpc:"2.0",result:{},id:0}}`). `system/init.tools` lists the two tools under the MCP naming `mcp__tandem__edit_file`, `mcp__tandem__write_file` beside `Read` (the disallowed `Edit`, `Write` are absent), `init.mcp_servers` has `{"name":"tandem","status":"connected","source":"sdk"}`, and `init.capabilities` includes `sdk_mcp_tools_list_changed`, `sdk_mcp_manifests`, `mcp_read_resource_v1`, `mcp_tool_ui_meta_v1`, `mcp_call_result_not_for_model_v1` (`r1:8`).

**Binary.** The `initialize` request schema has `sdkMcpServers: string[]` ("initialize: sdkMcpServers and webSearchIsolationExemptMcpServers must be arrays of strings"), `sdkMcpServerConfigs` keyed by server name ("Settings for the SDK-hosted MCP servers named in sdkMcpServers, keyed by server name. Sent as a separate field so a CLI that predates it ignores it"; a per-tool policy `always_ask` / `always_deny`: "always_ask: the CLI asks the host (canUseTool or the permission prompt tool) before each call, even where an allow rule or allowedTools covers the tool, and under bypassPermissions outside a hosted remote session"), and `sdkMcpServerManifests` (a one-shot cache of the server's `initialize` and `tools/list` answers; the reply reports what became of each under `sdk_mcp_manifests_parked`, absent in `r1:2` since none was sent). `mcp_message` is documented in the binary as "Carries one MCP JSON-RPC message for an SDK-hosted MCP server (one named in initialize.sdkMcpServers or added later with mcp_set_servers). Flows in both directions ... When the client answers, the success response carries the server's JSON-RPC reply under mcp_response". A cloud-hosted session rejects it ("SDK MCP servers are not available in a cloud-hosted session"): not Tandem's case. A `--mcp-config` entry of `type:"sdk"` is refused ("declares type \"sdk\", which only an SDK host application can register ... in-process SDK servers are registered by the host application at startup, not read from configuration files"): the control channel is the only way in.

**Not probed:** the http/sse alternative (`--mcp-config` with a `127.0.0.1` URL and a per-session secret). It needs a listening port and a server stack, and nothing above requires it, so it was not tried.

### 1.2 What does a tool call look like? Deferred, with `ToolSearch` first; `can_use_tool` with `mcp_server.source:"sdk"`; then `tools/call`.

**Probe run.** As every MCP tool (`2026-10-21-mcp-probes.md` 2.3), the SDK server's tools are deferred: the model first calls `ToolSearch` with `select:mcp__tandem__edit_file` (`r1:19`), answered by a `tool_reference` result with `total_deferred_tools:19` (`r1:21`), then calls the tool (`r1:35`):

```json
{"type":"tool_use","id":"<id>","name":"mcp__tandem__edit_file","input":{"file_path":"<path>/hello.txt","old_string":"alpha","new_string":"beta"},"caller":{"type":"direct"}}
```

with `tool_use_meta:[{"id":"<id>","display_name":"Edit File","server_display_name":"tandem","tool_name":"edit_file"}]` on the line (the display name is derived from the tool name). The permission request follows (`r1:37`), with exactly the keys of an MCP tool's request (`docs/protocol.md` 4.3), `mcp_server.source` now `"sdk"`:

```json
{"type":"control_request","request_id":"<uuid>","request":{"subtype":"can_use_tool","tool_name":"mcp__tandem__edit_file","mcp_server":{"name":"tandem","source":"sdk"},"display_name":"Edit File","input":{"file_path":"<path>/hello.txt","old_string":"alpha","new_string":"beta"},"permission_suggestions":[{"type":"addRules","rules":[{"toolName":"mcp__tandem__edit_file"}],"behavior":"allow","destination":"localSettings"}],"server_policy_ask_only":true,"tool_use_id":"<id>"}}
```

After the host's `allow` (`r1/sent.ndjson:7`, echoing the input) the call arrives as `tools/call` (`r1:38`):

```json
{"method":"tools/call","params":{"name":"edit_file","arguments":{"file_path":"<path>/hello.txt","old_string":"alpha","new_string":"beta"},"_meta":{"claudecode/toolUseId":"<id>","progressToken":2}},"jsonrpc":"2.0","id":2}
```

`_meta["claudecode/toolUseId"]` is the `tool_use` block's id, so a host can tie the call to its row and to the permission request it answered. `scripts/redact_fixture.py` does not know that key: its safety net replaced the three values in `r2` with `<leaked-N>` (its stderr: "unredacted id at request.message.params._meta.claudecode/toolUseId, replaced"), so the redactor needs the key before these runs become fixtures.

**Permission requests arrive in every mode.** With the session switched to `acceptEdits` (`set_permission_mode`, `r2:47`, `:48`; the next `init` agrees, `:51`), the next `mcp__tandem__edit_file` call still raised `can_use_tool` (`r2:66`, the same keys), as `server_policy_ask_only:true` says. So the host decides what auto-accept means; the CLI does not skip the request for an SDK server's tool. Not probed: `bypassPermissions` (the binary's `always_ask` text says the request comes there too, outside a hosted session) and `sdkMcpServerConfigs` policies.

### 1.3 What does the CLI do with the tool's result? It passes the content blocks to the model untouched.

**Probe run.** The host answered the call with one text block that said the user had changed the proposal by hand and quoted the final file (`r1/sent.ndjson:8`). The `user` line (`r1:41`) carries the block as `tool_result.content` (an array of `{"type":"text","text":…}`) and as `tool_use_result` (the same array), no `is_error`, with `tool_result_meta[{id, permission_decision:{decision:"accept", source:"user_temporary"}}]`:

```json
{"type":"user","message":{"role":"user","content":[{"tool_use_id":"<id>","type":"tool_result","content":[{"type":"text","text":"Applied to <path>/hello.txt. The user edited the proposal by hand before accepting it: your new_string became \"beta!\". The file now reads:\nbeta!\nline two\n"}]}]},"tool_use_result":[{"type":"text","text":"…"}],"tool_result_meta":[…]}
```

The model read it: "The file now reads \"beta!\" on line one and \"line two\" on line two. You changed my replacement to \"beta!\" before accepting it." (`r1:57`). An `isError:true` result (`r1/sent.ndjson:11`, the write_file rejection) becomes a `tool_result` with `content` as a plain string, `is_error:true`, and `tool_use_result:"Error: <text>"` (`r1:95`), as any failed tool (`docs/protocol.md` 6.2); the turn goes on and the model stops as told ("The tool refused: you rejected the write of notes.md…", `r1:108`); the turn's `result` is `success` with empty `permission_denials` (`r1:61`, `r1:112`): a rejection in the diff is a tool error, not a permission denial. A stale `old_string`, answered `isError:true` with the current text (`r2/sent.ndjson:17`), reads the same way (`r2:104`) and the model answered from it without retrying (`r2:116`). No `structuredContent` or `_meta` was sent in these results, so the `tool_use_result` object shape of `mcp-tool-call.ndjson:39` does not apply: with content blocks alone, `tool_use_result` **is** the array.

### 1.4 What happens to a call the user has not answered when the turn is stopped? The CLI withdraws it with `notifications/cancelled`.

**Probe run** (`r2`, first turn). The host held the `tools/call` (`r2:40`) for 3 s and sent `interrupt`. The CLI sent one more `mcp_message` for the server, the JSON-RPC notification `{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2,"reason":"AbortError: remote-cancel"}}` (`r2:41`), then the interrupt's receipt `{"still_queued":[]}` (`:42`), then a synthetic `tool_result` for the call with **`is_error:true`**, `content` "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing and wait for the user to tell you how to proceed." (and a memory note after it), `tool_use_result:"User rejected tool use"` and `tool_result_meta:[{id, non_execution_kind:"user-rejected"}]` with **no** `permission_decision` (`:43`; an earlier draft of this file said `is_error` was absent: it is not), the text line `[Request interrupted by user for tool use]` (`:44`) and `result{subtype:"error_during_execution", is_error:true, terminal_reason:"aborted_tools"}` (`:45`). No `control_cancel_request` arrived for the `mcp_message` request. The host's late answer, sent after the receipt (`r2/sent.ndjson:9`), produced nothing: no second `tool_result`, no error. So `notifications/cancelled` is the withdrawal, keyed by the JSON-RPC `requestId`, and answering a withdrawn call is harmless.

**Binary.** The SDK transport sends a request without a timeout (`sendMcpMessage`: `let o=qGn(s)?void 0:us(void 0,{timeoutMs:n,…})`, the timer only for notifications), but the CLI's MCP client has a per-call wall clock: `MCP_TOOL_TIMEOUT` ("Per-server tool-call timeout in milliseconds. Overrides the MCP_TOOL_TIMEOUT environment variable for this server. Hard wall-clock limit per call; progress notifications do not extend it"). Its default and whether it applies to an SDK server's call were **not measured**: no held call in `r2` waited longer than 5 s. A diff the user leaves open for long may hit it; the spec treats a cancellation that arrives while the diff is open as a withdrawal either way.

### 1.5 Does the CLI expand `@path` in a stream-json user message? See section 3 (probe 3).

---

## 2. The platform's diff API (javap over IU 2026.1.5)

In `intellij.platform.diff.jar`, except `DiffUserDataKeysEx`, `DiffEditorTabFilesManager`, `ChainDiffVirtualFile` and `DiffUtil`, which `javap` finds in `intellij.platform.diff.impl.jar` (both in `lib/` of IU 2026.1.5); `FileDocumentManager.saveDocument(Document)` and `Document.createRangeMarker` are in `intellij.platform.core.jar`, `FileEditorManagerListener.fileClosed` in `intellij.platform.analysis.jar`. The signatures below are the ones the spec names.

- `com.intellij.diff.DiffManager`: `getInstance()`; `showDiff(Project, DiffRequest)`; `showDiff(Project, DiffRequest, DiffDialogHints)`; `showDiff(Project, DiffRequestChain, DiffDialogHints)`; `createRequestPanel(Project, Disposable, Window): DiffRequestPanel`; `showMerge(Project, MergeRequest)`.
- `com.intellij.diff.DiffDialogHints`: `DEFAULT`, `FRAME`, `MODAL`, `NON_MODAL`; `DiffDialogHints(WindowWrapper.Mode, Component, Consumer<WindowWrapper>)` (the consumer gets the window, so a host can close it itself).
- `com.intellij.diff.DiffContentFactory`: `create(Project, Document)`, `create(Project, Document, VirtualFile)` (a `DocumentContent` on the real, open `Document`: its highlighting, charset and `getHighlightFile`), `createEditable(Project, String, FileType): DocumentContent` (an in-memory, editable document), `create(Project, String, VirtualFile)` (read-only text typed like the file), `createEmpty()` (the left side of a new file), `createDocument(Project, VirtualFile)`, `createFile(Project, VirtualFile): FileContent` (binary).
- `com.intellij.diff.contents.DocumentContent`: `getDocument()`, `getHighlightFile()`, `getLineSeparator()`, `getCharset()`.
- `com.intellij.diff.requests.SimpleDiffRequest(String title, DiffContent left, DiffContent right, String leftTitle, String rightTitle)`; `DiffRequest.onAssigned(boolean)` is called when a viewer takes and releases the request, **but not only on close**: `DiffRequestProcessor.doApplyRequest` (javap `-c`, `intellij.platform.diff.impl.jar`) calls `onAssigned(false)` on the active request and then `onAssigned(true)` each time it re-applies one, which a viewer switch (side-by-side ↔ unified) or a refresh does, so it cannot tell a host that the diff closed. The close signal is the file editor's disposal (`DiffEditorTabFilesManager.showDiffFile` returns the `FileEditor[]`, and `FileEditor` is `Disposable`) and `FileEditorManagerListener.fileClosed`. `putUserData`/`getUserData`.
- `com.intellij.diff.util.DiffUserDataKeys`: `FORCE_READ_ONLY_CONTENTS: Key<boolean[]>` (per side: `[true, false]` keeps the file side read-only and the proposal editable), `FORCE_READ_ONLY`, `CONTEXT_ACTIONS: Key<List<AnAction>>` (actions on the diff toolbar), `PREFERRED_FOCUS_SIDE`, `MASTER_SIDE`, `DO_NOT_CHANGE_WINDOW_TITLE`, `NOTIFICATION_PROVIDERS: Key<List<DiffNotificationProvider>>` (`createNotification(FrameDiffTool.DiffViewer): JComponent`, a banner above the editors).
- `com.intellij.diff.util.DiffUserDataKeysEx`: `BOTTOM_PANEL: Key<JComponent>` (a panel under the editors: where Accept / Reject go), `EDITORS_TITLE_CUSTOMIZER`, `SCROLL_TO_CHANGE`, `FORCE_DIFF_TOOL`, `DIFF_NEW_TOOLBAR`, `VCS_DIFF_ACCEPT_LEFT_ACTION_TEXT` and friends (merge captions, not used).
- `com.intellij.diff.util.DiffUtil`: `registerAction(AnAction, JComponent)`, `recursiveRegisterShortcutSet(ActionGroup, JComponent, Disposable)` (how a Ctrl/⌘+S shortcut is bound inside the diff's component), `createMessagePanel`.
- `com.intellij.diff.editor.DiffEditorTabFilesManager.getInstance(Project).showDiffFile(VirtualFile, boolean, boolean)` and `ChainDiffVirtualFile(DiffRequestChain, String)` open a diff as an editor tab instead of a window; `SimpleDiffRequestChain(DiffRequest)`.
- `com.intellij.diff.merge.MergeResult`: `CANCEL`, `LEFT`, `RIGHT`, `RESOLVED` (the merge dialog's three-way outcome; not used: a two-sided editable diff reads better than a merge with a base).

The write side is the platform's usual: `WriteCommandAction.writeCommandAction(project).withName(...).run { document.setText(...) }` (or `replaceString`), `FileDocumentManager.getDocument(file)`, `LocalFileSystem.getInstance().refreshAndFindFileByNioFile`, `VfsUtil.createDirectories` and `VirtualFile.createChildData` for a new file, `ReadonlyStatusHandler.ensureFilesWritable` for a read-only file, `FileTypeRegistry.getInstance().isFileOfType` / `FileType.isBinary` and `FileUtilRt.isTooLarge` for the binary and size checks: not javap'd here, they are public API Tandem's `ide/` already compiles against.

---

## 3. `@path` in a user message (probe 3): the CLI expands it itself, from the disk

**Probe run** (`r3`; `scripts/drive_cli.py`'s argv with `--safe-mode`, every file tool disallowed: `--disallowedTools Edit,Write,MultiEdit,NotebookEdit,Read,Bash,Glob,Grep`). A plain user text block, not `client_composed`, `Without using any tool: what is the first line of @hello.txt ? Answer in one line.` was answered `The first line of `hello.txt` is `alpha`.` with no tool call (`r3:12`, one `assistant` line, `result` `num_turns:1`, `r3:16`); `@hello.txt#L2` in the second prompt was answered `The second line of `hello.txt` is `line two`.` (`r3:27`). So the CLI expanded the mention: the file's content reached the model without a tool. Nothing of the expansion shows on stdout (no `user` line between the prompt and the reply), and the transcript (`scratch/probes/ide-edits/r3/transcript.jsonl`, the CLI's own) holds it as an `attachment` line right after the user line:

```json
{"type":"attachment","attachment":{"type":"file","filename":"<path>/hello.txt","content":{"type":"text","file":{"filePath":"<path>/hello.txt","content":"alpha\nline two\n","numLines":3,"startLine":1,"totalLines":3}},"displayPath":"hello.txt"},…}
```

(`transcript.jsonl:6`); the `#L2` form, in a session that had the file already, is `{"type":"already_read_file","filename":…,"displayPath":"hello.txt","content":{…,"startLine":2,"totalLines":3}}` (`:29`). The expansion reads the **disk**: an editor buffer with unsaved changes would not be what the model sees, which the spec answers by sending the buffer's text itself when the file is dirty (spec 3.5). The transcript's `attachment` lines are already skipped by `TranscriptReader` (every line type outside `docs/protocol.md` 11.2's list is), so a reload shows the user's text with the mention as typed. The `environment`, `model`, `deferred_tools_delta`, `skill_listing`, `session_context` and `prompt_snapshot` attachments of the same file (`:7-17`) are the CLI's own, unrelated to the mention, and carry the recording machine's details: this transcript stays in `scratch/`.

**Not probed:** a path with spaces (the CLI's terminal UI quotes them), a directory mention, a mention the CLI cannot find (the terminal shows no error and sends the text as typed: **inferred**), and whether the expansion happens for a message sent while a turn runs (`priority:"later"` or a steer).

---

## 4. What the probes settle for the spec

1. Tandem's tools are an in-process MCP server registered with `initialize.sdkMcpServers:["tandem"]` and served over `mcp_message` control requests: no port, no Node, no sidecar (1.1). `InboundRequest` grows an `McpMessage(serverName, message: JsonObject)` case and `HostMessages` an `mcpResponse(requestId, reply)`; the MCP handshake (`initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `notifications/cancelled`, and `ping`, which is in the MCP spec but was not seen) is the whole JSON-RPC surface Tandem answers; any other method gets JSON-RPC error `-32601`.
2. The tools are deferred like every MCP tool (`ToolSearch` first), named `mcp__tandem__<tool>`, and every call raises `can_use_tool` with `mcp_server.source:"sdk"` in every permission mode (1.2): auto-accept is Tandem's decision.
3. A result is content blocks the model reads verbatim; `isError:true` is a tool error the turn survives (1.3): the diff's Accept, Reject and "the old string no longer matches" all fit in one text block each.
4. Stop while a diff is open: `notifications/cancelled` for the call's JSON-RPC id, then the CLI's own synthetic rejection (`is_error:true`, `non_execution_kind:"user-rejected"`, no `permission_decision`) and an `aborted_tools` result (1.4): Tandem closes the diff, answers nothing more (an answer would be ignored), and must map that result to the interrupted row itself, since today's `ToolStates` would read an error result without a reject decision as a failure.
5. `@path` in the prompt needs no work on the wire: the CLI expands it from the disk (3); Tandem adds the picker and the dirty-buffer case.
6. The platform's diff API has everything the editable proposal needs: `createEditable`, `FORCE_READ_ONLY_CONTENTS`, `BOTTOM_PANEL`, the returned `FileEditor` for the close signal (`onAssigned` is not one), `saveDocument` for the disk (2).
7. The probes ran with `--strict-mcp-config --setting-sources project,local`, so the SDK server was alone; Tandem spawns without those flags (`ClaudeSession.argv`). Coexistence with configured servers and a name collision with a user server called `tandem` are **unrecorded** (spec 9.1 records both).

Open (carried into the spec's section 11): `MCP_TOOL_TIMEOUT`'s default for an SDK server's call; `bypassPermissions` and `sdkMcpServerConfigs`; whether `sdkMcpServerManifests` lifts the deferral or saves the `tools/list` round trip; the http alternative; mentions with spaces, directories, during a turn.

---

## 5. Recordings (F1): the fixtures and their line map

Recorded 2026-10-28 with `scripts/record-scenario.sh` against `claude` 2.1.312 on `sonnet`, redacted, each with its CLI transcript under `src/test/resources/fixtures/transcripts/`. Re-recorded the same day after review round 1, once the redactor kept a working-directory file's name (below); every number here is from that second recording. Every number below is a 1-based line of `src/test/resources/fixtures/<name>.ndjson` and was opened while writing this. Tests cite this map, never a probe line (`r1:38` and the like). The numbers differ from the probes' because the SDK server now sits beside a configured one, so the MCP handshake starts before the CLI's `initialize` answer and the first `mcp_status` is an extra line.

Three facts about the redacted text, all visible in the lines below:

- **A path under the recording's working directory keeps its file name.** `redact_fixture.py` learns the working directory from a line's top-level `cwd` (the init's, a transcript line's) with its resolved path and macOS `/private` twin, and, when it is a temporary directory (the recorders' `mktemp -d`; a project's file names never show), writes it `<path>` while keeping the tail, so a recorded `tools/call` reads `"file_path":"<path>/hello.txt"` (`edit_file`) or `"<path>/new.txt"` (`write_file`), as spec 9.6 expects; `init.cwd` is `<path>` itself. Any other absolute path is still the whole `<path>` (the echo server's script in `mcp_status`, `args:["-I","<path>","echo"]`), and a path no longer swallows a sentence's period (`not found in <path>/hello.txt. The file`). The fake substitutes `<path>` with its working directory (9.6).
- **`init.tools` is empty in every fixture but the `INIT_KEEP` ones** (the `ide-edit*` three, the four MCP fixtures, `mcp-connectors*`). The redactor empties it on recording (`INIT_LISTS`), and the fixtures committed before this round, which still held the recording machine's whole list, were emptied in one pass with the same rule; `test_redact_fixture.py` (`test_every_committed_fixture_outside_init_keep_has_no_init_tools`) fails if a fixture outside `INIT_KEEP` holds a non-empty `init.tools`. So the plan's deviation 1 holds: the version gate's "a non-empty `tools` lacking `mcp__tandem__edit_file`" never fires on a replayed fixture without Tandem's tools.
- **The result texts come from `drive_cli.py`, not from `EditResults`.** They follow spec 4.3's wording but not its bytes everywhere (the unified diff carries difflib's `--- proposal` / `+++ applied` headers; the not-found and file blocks use `drive_cli`'s `cat_n`). `EditResultsTest` owns the exact texts; no test compares a recorded result with `EditResults` output verbatim.

### `ide-edit` (122 lines; the transcript is `transcripts/ide-edit.jsonl`, 47 lines)

| Frame | Line |
|---|---|
| `mcp_message` `initialize` (`id` 0, `protocolVersion` `2025-11-25`), before the CLI's `initialize` answer at `:2` | `ide-edit:1` |
| `mcp_status` before the first prompt: `echo` `pending`, **no** `tandem` row | `ide-edit:3` |
| `mcp_message` `notifications/initialized` (no `id`) | `ide-edit:4` |
| `mcp_message` `tools/list` (`id` 1) | `ide-edit:6` |
| `system/init`: `tools` holds `mcp__echo__echo`, `mcp__tandem__edit_file`, `mcp__tandem__write_file` (no built-in `Edit`/`Write`); `mcp_servers` holds `echo` (`dynamic`) and `tandem` (`sdk`), both `connected` | `ide-edit:8` |
| `ToolSearch` `tool_use`, its `tool_reference` result for `mcp__tandem__edit_file` | `ide-edit:19`, `:21` |
| first `tool_use` (`mcp__tandem__edit_file`, with `tool_use_meta`) | `ide-edit:35` |
| its `can_use_tool` (`mcp_server {name:"tandem", source:"sdk"}`, `server_policy_ask_only`) | `ide-edit:37` |
| its `tools/call` (JSON-RPC `id` 2; `_meta["claudecode/toolUseId"]` equals `:35`'s block id; `file_path` is `<path>/hello.txt`) | `ide-edit:38` |
| its result: `tool_result.content` a block array, no `is_error`, `permission_decision {accept, user_temporary}`; the text is the changed-proposal text with the unified diff (no blank line before `The file now reads`) and the file `beta!` | `ide-edit:41` |
| `result` `success` of the first turn | `ide-edit:71` |
| `mcp_status` after the first turn: `echo` (`stdio`, `dynamic`) and `tandem` (`transport:"sdk"`, `source:"sdk"`, `serverInfo.version` `0.4.0`, tools `edit_file`, `write_file`) | `ide-edit:73` |
| second `system/init` (the next turn of the same process) | `ide-edit:76` |
| `write_file`: `tool_use`, `can_use_tool`, `tools/call` (`id` 3, `file_path` `<path>/new.txt`) | `ide-edit:101`, `:103`, `:104` |
| the rejected `write_file`'s result: `content` a string, `is_error:true`, the text `The user rejected this edit in the IDE; nothing was changed.\nThe user says: not now` (`tool_use_result` the same text after `Error: `) | `ide-edit:105` |
| `result` `success` of the second turn | `ide-edit:121` |

### `ide-edit-two` (78 lines; transcript 38)

| Frame | Line |
|---|---|
| `initialize`, `notifications/initialized`, `tools/list`, `system/init` | `ide-edit-two:1`, `:3`, `:5`, `:7` |
| first call: `tool_use`, `can_use_tool`, `tools/call` (`alpha` to `beta`, `id` 2), result | `ide-edit-two:34`, `:36`, `:37`, `:40` |
| second call in the same turn: `tool_use`, `can_use_tool`, `tools/call` (`line two` to `line 2`, `id` 3), result | `ide-edit-two:52`, `:54`, `:55`, `:56` |
| `result` `success` | `ide-edit-two:77` |

### `ide-edit-stop` (122 lines; transcript 49)

| Frame | Line |
|---|---|
| `initialize`, `notifications/initialized`, `tools/list`, `system/init` | `ide-edit-stop:1`, `:3`, `:5`, `:7` |
| held call: `tool_use`, `can_use_tool`, `tools/call` (JSON-RPC `id` 2, never answered by the host) | `ide-edit-stop:36`, `:38`, `:39` |
| `notifications/cancelled` (`requestId` 2, `reason` `AbortError: remote-cancel`) | `ide-edit-stop:42` |
| the interrupt's receipt (`still_queued: []`) | `ide-edit-stop:43` |
| the synthetic rejection: `is_error:true`, `tool_use_result` `User rejected tool use`, `tool_result_meta [{id, non_execution_kind:"user-rejected"}]` with **no** `permission_decision` | `ide-edit-stop:44` |
| the text line `[Request interrupted by user for tool use]` | `ide-edit-stop:45` |
| `result` `error_during_execution`, `is_error:true`, `terminal_reason` `aborted_tools` (the only error result of the four fixtures) | `ide-edit-stop:46` |
| the `set_permission_mode acceptEdits` answer (`{mode}`), then the next `system/init` | `ide-edit-stop:48`, `:52` |
| under `acceptEdits`: `tool_use`, `can_use_tool` (the request still arrives), `tools/call`, result (applied, `permission_decision {accept, user_temporary}`) | `ide-edit-stop:66`, `:68`, `:69`, `:70` |
| `result` `success` of that turn | `ide-edit-stop:81` |
| the `set_permission_mode default` answer, then `system/init` | `ide-edit-stop:83`, `:87` |
| the stale `old_string`: `tool_use`, `can_use_tool`, `tools/call`, the not-found result (`is_error:true`, text `old_string was not found in <path>/hello.txt. The file as open in the IDE (unsaved changes included) reads:` followed by the numbered file) | `ide-edit-stop:101`, `:103`, `:104`, `:105` |
| `result` `success` | `ide-edit-stop:121` |

The host's `set_permission_mode` requests themselves are not in a fixture (the files hold the CLI's stdout); the fake CLI answers them from the `{mode}` lines above.

### `ide-mention` (36 lines; transcript 33)

The prompts are host frames and not in the stdout. The line range goes first, so the CLI expands both mentions instead of writing `already_read_file` for the second. The CLI answered both without a tool (`tools` disallowed): the first prompt `What is on @hello.txt#L2?` ends at the reply `ide-mention:13` (`line two`) and `result` `ide-mention:17`; the second, `What does @hello.txt say?`, at the reply `ide-mention:31` and `result` `ide-mention:35`; a `system/init` precedes each (`:5`, `:21`). In `transcripts/ide-mention.jsonl` the user lines are `:5` and `:28`; the expansion is an `attachment` line right after each, both `type: file` (`:6`, `:29`). The redactor reduces every attachment to its `type`, so the transcript does not carry the expanded text: what `#L2` sends (the one line, or the file) is **unrecorded**; the reply only shows that the model was given line 2.

### Other numbers the spec's 9.2 names

For `FixtureDecodeTest`: the `init` is `ide-edit:8`, the `McpTest` answer with `transport:"sdk"` is `ide-edit:73`, the block-array result is `ide-edit:41` and the error result with the `tool_use_result` string is `ide-edit:105`; the `permission_decision` of the unchanged accept in `ide-edit-two:40` and `:56` reads `{accept, user_temporary}`.

### The collision probe (`ide-edit-collision`, `scratch/probes/ide-edits/r4/ide-edit-collision.ndjson`, 81 lines, not committed)

`ide-edit`'s spawn with the configured stdio server **named `tandem`** (the echo server, so a call to it would answer `echo`): the CLI keeps **the SDK server for dispatch**. The MCP handshake and the `edit_file` call arrive as `mcp_message` frames for `tandem` (`r4:1`, `:6`, `:42`), `init.tools` lists `mcp__tandem__edit_file` and `mcp__tandem__write_file` and no `mcp__tandem__echo`, and the call is answered by the host (`r4:45`). But the status lists **one** row, and it reads as the configured server: `init.mcp_servers` has `{name:"tandem", status:"connected", source:"dynamic"}` (`r4:8`, not `sdk`), and `mcp_status` after the turn (`r4:81`) has `tandem` with `transport:"stdio"`, `source:"dynamic"`, the configured `config`, `serverInfo {name:"echo"}` (the configured server was spawned and connected) and the SDK server's tools `edit_file` and `write_file`; before the first prompt it is `pending` with the stdio config (`r4:3`). So spec 11 item 12's assumption holds: the SDK server wins and the panel shows one `tandem` row; Tandem need not refuse or rename its server. The one correction is the row's source: in a collision it is `dynamic`, not `sdk`, which `McpPanelModel`'s own-server rule (`name == ownServer && source == "dynamic"`, the plan's base) already treats as read-only, so the panel never offers to toggle or reconnect the shadowed user server; a user's own server called `tandem` is unreachable while the session lives.

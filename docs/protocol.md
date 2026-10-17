# CLI stream-json protocol reference

How Tandem talks to the user's `claude` binary (Claude Code 2.1.x) over stdin/stdout NDJSON.

Sources, in order of authority:
1. Recorded transcripts in `src/test/resources/fixtures/`: `plain-reply`, `permission`, `interrupt`, `model-switch`
   (CLI 2.1.300); `history-reply`, `history-permission`, `history-interrupt`, `permission-mode`, `fork` (CLI 2.1.301).
   The `history-*` and `fork` recordings come with the CLI's own on-disk transcript of the same session in
   `fixtures/transcripts/` (section 11). Re-record with `scripts/record-fixture.sh` (one-shot prompt) or
   `scripts/record-scenario.sh` (bidirectional scenarios); both copy the transcript too.
2. Measurements with CLI 2.1.301 where a section says so: headless runs in an empty scratch folder with the variables
   of section 1.2 removed. Most send only `initialize`, so no model is used; those that needed a prompt say so. The
   checks of `--session-id X` and `--resume X` on their own (1.1) ran signed in, with the usual config directory. Those
   of forks, of `--session-id` together with `--resume`, of the failed starts' `result` ids and of an early
   `rename_session` (1.1, 4.4) ran without sign-in: a scratch `CLAUDE_CONFIG_DIR` and no variable whose name contains
   `TOKEN`, `API_KEY` or `OAUTH`. Such a run writes nothing before its `initialize` response, where a signed-in run first
   writes a `system/ui_invalidate` line with the session id (1.1). The fork probe without sign-in forked a session that
   had no title line, so its missing `system/session_title_changed` (1.1) says nothing about sign-in.
3. `@anthropic-ai/claude-agent-sdk` 0.3.300: `sdk.d.ts` (types) and `sdk.mjs` (what the SDK actually writes and reads).
4. `claude --help` output.

Anything marked **(inferred)** is not directly shown by those sources. The CLI changes weekly: validate every field, and
parse unknown `type`/`subtype` values to `Unknown(raw)` instead of throwing.

---

## 1. Spawn

### 1.1 argv

The SDK's `ProcessTransport` always starts with:

```
claude --output-format stream-json --verbose --input-format stream-json
```

then appends optional flags in this order (only those Tandem cares about are listed):

| Flag | When the SDK adds it | Tandem |
|---|---|---|
| `--thinking=adaptive` / `--thinking=disabled` / `--max-thinking-tokens N` | `thinking` option | optional |
| `--effort <low\|medium\|high\|xhigh\|max>` | `effort` option | yes, picked when a session is created |
| `--max-turns N`, `--max-budget-usd X` | limits | no |
| `--model <id>` | `model` option | yes, user's pick |
| `--permission-prompt-tool=stdio` | a `canUseTool` callback is set | **yes, always** |
| `--permission-prompt-tool <mcp tool>` | `permissionPromptToolName` (mutually exclusive with the above) | no |
| `--permission-prompts <host\|none>` | option | no (default `host`) |
| `--continue` / `--resume <session-id>` | resume options | `--resume` for an existing session (section 8); no `--continue` |
| `--allowedTools a,b` / `--disallowedTools a,b` | options | `--disallowedTools Edit,Write,MultiEdit,NotebookEdit` (hard rule 4) |
| `--tools a,b` / `--tools=default` | `tools` option | optional |
| `--mcp-config <json>` | non-SDK MCP servers given | yes: our IDE MCP server (see 1.4) |
| `--setting-sources user,project,local` | option | optional |
| `--strict-mcp-config` | option | no |
| `--permission-mode <mode>` | option | yes |
| `--allow-dangerously-skip-permissions` | option | only if user enables bypass |
| `--fallback-model <id>` | option; SDK throws if equal to `--model` | no by default |
| `--include-hook-events` | option | no |
| `--include-partial-messages` | option | **yes** (streaming text) |
| `--add-dir <dir>` (repeat) | `additionalDirectories` | for extra content roots |
| `--plugin-dir <path>` or `--await-initialize` | plugins | no |
| `--fork-session` | `forkSession` | with `--resume` and `--session-id` for a fork (section 8) |
| `--resume-session-at <uuid>` | `resumeSessionAt` | see section 8 |
| `--session-id <uuid>` | `sessionId` | **yes**: a new session's id, and a fork's own id (section 8) |
| `--no-session-persistence` | `persistSession:false` | no |

Notes:
- The SDK does **not** pass `-p`/`--print`. `--input-format stream-json` implies headless print mode.
- `--replay-user-messages` (help text) re-emits user messages from stdin as `{"type":"user",...,"isReplay":true}` acks. The SDK does not add it by default; useful for binding our sends to transcript uuids.
- `--verbose` is required with `stream-json` output, otherwise the CLI rejects the combination (inferred from the SDK always adding it).

Recommended Tandem argv (`SessionConfig.argv()` builds it):

```
claude --output-format stream-json --verbose --input-format stream-json \
  --include-partial-messages --permission-prompt-tool=stdio \
  --model <picked> --effort <level> --permission-mode <mode> \
  --mcp-config '{"mcpServers":{"tandem":{...}}}' \
  --disallowedTools Edit,Write,MultiEdit,NotebookEdit \
  <session flags>
```

`--model`, `--effort` and `--permission-mode` are left out when there is no pick, so the CLI's default applies;
`--mcp-config` comes with Milestone 2 and is not passed yet. `<session flags>` is exactly one of these, never none:

| Session | Flags |
|---|---|
| new | `--session-id <uuid>`, an id Tandem generates |
| existing, once its `<id>.jsonl` exists | `--resume <id>` |
| fork of `<id>`, until the fork's own transcript exists | `--resume <id> --fork-session --session-id <new uuid>` |

Measured with CLI 2.1.301, sending only `initialize` (conditions in Sources item 2; these facts also sit on
`SessionManager.configFor`):
- `--session-id X` without a prompt writes no transcript, not even the project folder: the CLI writes `X.jsonl` with
  the session's first prompt.
- `--resume X` before that fails: exit code 1, a `result` with subtype `error_during_execution`, and on stderr
  "No conversation found with session ID: X". That `result` still carries a `session_id`: X for a plain resume, the
  `--session-id` value for a fork that has one, a fresh UUID for `--fork-session` alone.
- `--session-id X` once `X.jsonl` exists fails: exit code 1, "Error: Session ID X is already in use."
- `--resume X --session-id Z` without `--fork-session` exits with code 1: "Error: --session-id can only be used with
  --continue or --resume if --fork-session is also specified." (`sdk.d.ts` documents the same for `sessionId`.)
- `--resume X` and `--resume X --fork-session` answer `initialize` but emit no `system/init` until the first prompt.
  A signed-in run names the session's id before that all the same, in lines the SDK does not document:
  `system/ui_invalidate` at start (in every recording, and in a run with `--session-id` and no prompt), and
  `system/session_title_changed` when the original has a title (fixture `fork`: started without `--session-id`, the
  fork names its new id in both before its `initialize` response). The run without sign-in wrote neither. Tandem
  passes `--session-id`, a fork's too, so it never relies on those lines.

So Tandem passes `--resume` exactly when `<id>.jsonl` exists, and gives every new session and every fork its id up
front. Either mistake above would fail again on every retry.

### 1.2 Environment

The SDK copies the host environment (or `options.env` if given) and then:

| Var | Value | Notes |
|---|---|---|
| `CLAUDE_CODE_ENTRYPOINT` | `sdk-ts` if unset | Tandem sets `sdk-tandem` (inferred: any string; certain values like `claude-desktop-3p`, `remote_cowork` switch CLI behaviour, avoid them) |
| `CLAUDE_AGENT_SDK_VERSION` | `0.3.300` if unset | do not fake this |
| `CLAUDE_CODE_SDK_READS_SESSION_STATE` | `1` unless set (any case) | makes the CLI emit `system/session_state_changed` (inferred) |
| `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING` | `true` when file checkpointing enabled | needed for `rewind_files` |
| `NODE_OPTIONS` | deleted | Tandem deletes it too |
| `DEBUG` | deleted (or `1` if `DEBUG_CLAUDE_AGENT_SDK`) | |
| `TRACEPARENT`/`TRACESTATE` | set from the active OTel context | ignore |

Never set or read API keys (hard rule 3); the CLI resolves auth itself.

What Tandem passes (`cliEnvironment` in `session/CliEnvironment.kt`): the login shell's environment, as the platform
loads it (`EnvironmentUtil.getEnvironmentMap()`, read off the EDT by `ide/ShellEnvironment.kt`), without the variables
that tie a process to a parent Claude Code session or hosted environment, without `NODE_OPTIONS`, and with
`CLAUDE_CODE_ENTRYPOINT=sdk-tandem`. The shell's, not the IDE's own: an IDE started from the macOS Dock or Finder has
launchd's minimal `PATH`, and the CLI should see the `PATH`, proxies and tool locations it sees in a terminal. Where the
platform did not load the shell's environment (the IDE was started from a terminal), it is the IDE's own. Everything else passes through unchanged, notably `CLAUDE_CONFIG_DIR` (where the
transcripts are, section 11; Tandem reads it from the same shell environment, so it looks where the CLI writes) and provider settings such as the Bedrock and Vertex variables. Removed:

- `CLAUDECODE`, `CLAUDE_PID`, `CLAUDE_CODE_SESSION_ID`, `CLAUDE_CODE_CHILD_SESSION`, `CLAUDE_CODE_SESSION_ATTENDED`:
  the CLI sets these for the processes it starts itself (read from the 2.1.301 binary);
- `CLAUDE_CODE_CONTAINER_ID`, `CLAUDE_CODE_WORKER_EPOCH`, `CLAUDE_SESSION_INGRESS_TOKEN_FILE`, and every name that starts
  with `CLAUDE_CODE_REMOTE`, `CLAUDE_CODE_MESSAGING_` or `CLAUDE_CODE_ENVIRONMENT_`: a hosted session sets these.

Why: a `claude` that inherits them acts on the parent session. Inside a hosted Claude Code session, every recording this
project made before the list existed ran under the parent's session id and wrote its transcript as `<parent id>.jsonl`.
The recording scripts remove the same list (`drive_cli.py`, `record-fixture.sh`).

Measured with 2.1.301 with the hosted variables removed, as on a CI runner (one prompt each): `CLAUDE_CODE_SESSION_ID`
on its own is ignored, as `bogus`, as a UUID, and also with `CLAUDECODE=1` and `CLAUDE_CODE_CHILD_SESSION=1`; the CLI
picks its own id. With `--session-id X` as well, `system/init` reports X and the transcript is `X.jsonl`. The nightly
contract run checks that with `CLAUDE_CODE_SESSION_ID=bogus` set. Which of the hosted variables make the CLI take over
an inherited id was not measured.

### 1.3 cwd, stdio, binary lookup

- `cwd` = `options.cwd` (Tandem: project base path). The CLI reports it back in `system/init.cwd`.
- `stdio: ["pipe","pipe","pipe"]`, `windowsHide: true`. stderr is buffered as a tail for error messages only, not parsed.
- Binary: the SDK uses `pathToClaudeCodeExecutable` or its own bundled native package
  (`@anthropic-ai/claude-agent-sdk-<platform>-<arch>[-musl]/claude[.exe]`). If the path ends in `.js/.mjs/.ts/...` it is run with node.
  Tandem has no bundled binary. It runs, in this order: the path in Settings | Tools | Tandem when it can run; each entry
  of the login shell's `PATH` (section 1.2); then the usual install locations `~/.local/bin` (the native installer),
  `~/.claude/local` (the older local install), and outside Windows `/opt/homebrew/bin` and `/usr/local/bin` (Homebrew,
  and npm's global bin for a Node from Homebrew or nodejs.org), `~/.npm-global/bin`, `~/.bun/bin` and `~/.volta/bin`
  (inferred locations). On Windows it looks for `claude.exe`, `claude.cmd`, then `claude`. The settings page shows what
  it found and where. Spawn the native binary directly. When the binary's directory is not on the `PATH`, it goes to the
  end of the child's `PATH`: a `claude` that npm, Volta or Bun installed is `#!/usr/bin/env node`, with `node` beside it.
  At the end, so that the user's own `node` and system tools still come first.

### 1.4 Startup sequence

```
spawn ──► write control_request{initialize} ──► read control_response{success, models, commands, account...}
      ──► write first user message ──► read system/init ... result
```

The SDK queues user messages until the `initialize` request is written (`writeAfterInitialize`); it does not wait for the response before writing. Default init timeout in the SDK is 60 s.

---

## 2. Envelopes

One JSON object per line, UTF-8, `\n`-terminated. Blank lines and non-JSON lines on stdout must be skipped (the SDK logs `Non-JSON stdout` and continues).

### stdout (CLI → host)

| `type` | Meaning |
|---|---|
| `system` | metadata/events, discriminated by `subtype` (section 5) |
| `assistant` | complete assistant content block(s) |
| `user` | tool results, replays, synthetic user messages |
| `stream_event` | raw Anthropic SSE event (only with `--include-partial-messages`) |
| `result` | end of turn |
| `rate_limit_event` | subscription rate-limit status (`SDKRateLimitEvent`) |
| `conversation_reset` | `/clear` (and plan-mode exit with clear context): `new_conversation_id`, `trigger?`, `user_message_uuid?`. Drop the rendered transcript |
| `control_request` | CLI asks the host (e.g. `can_use_tool`) |
| `control_response` | answer to a host request |
| `control_cancel_request` | CLI withdraws its own pending request |
| `keep_alive` | heartbeat, ignore |
| `active_goal` | goal state (fixture; typed in SDK) |
| `autocompact_state` | auto-compact config (fixture; untyped) |
| `tool_progress`, `auth_status`, `tool_use_summary`, `prompt_suggestion`, `transcript_mirror`, ... | other SDK types; render or ignore |

### stdin (host → CLI)

| `type` | Meaning |
|---|---|
| `user` | a prompt |
| `control_request` | host asks the CLI (initialize, interrupt, set_model, ...) |
| `control_response` | host answers a CLI request (can_use_tool, hook_callback, mcp_message) |
| `control_cancel_request` | host withdraws its own pending request |
| `keep_alive` | optional |

Every stdout message except control frames and `keep_alive` carries `uuid` and `session_id`.

---

## 3. User messages (stdin)

Exact line the SDK writes for a string prompt:

```json
{"type":"user","session_id":"","message":{"role":"user","content":[{"type":"text","text":"ping"}]},"parent_tool_use_id":null}
```

| Field | Notes |
|---|---|
| `session_id` | the SDK sends `""`; the CLI owns the id. Sending the real id is harmless (inferred) |
| `message` | Anthropic `MessageParam`, `role:"user"`, `content` string or block array |
| `parent_tool_use_id` | `null` for top-level prompts |
| `uuid` | optional client uuid; echoed as `user_message_uuid(s)` on `stream_event`/`result`. **Send one** so turns can be correlated |
| `priority` | `"now" \| "next" \| "later"` optional queueing hint |
| `shouldQuery` | `false` appends without starting a turn |
| `client_composed` | `true` disables `@path` expansion and slash-command dispatch |
| `timestamp` | ISO string, optional |

Images (base64 content block, Anthropic format):

```json
{"type":"user","session_id":"","parent_tool_use_id":null,"uuid":"8c1e...","message":{"role":"user","content":[
  {"type":"text","text":"What is in this screenshot?"},
  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0..."}}]}}
```

Slash commands are sent as plain text (`"/compact"`) unless `client_composed:true`.
Messages sent while a turn is running are queued and may be folded into the running turn; `result.user_message_uuids` lists which sends a turn consumed.

---

## 4. Control protocol

### 4.1 Envelope

Request (either direction):

```json
{"type":"control_request","request_id":"k3j9x2a1b","request":{"subtype":"interrupt"}}
```

Response (exactly one per request, same `request_id`):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"k3j9x2a1b","response":{}}}
{"type":"control_response","response":{"subtype":"error","request_id":"k3j9x2a1b","error":"Unknown subtype"}}
```

| Rule | Source |
|---|---|
| `request_id` chosen by the sender, unique among its in-flight requests (SDK: `Math.random().toString(36).substring(2,15)`) | sdk.mjs |
| Ignore responses for ids you are not waiting on | sdk.d.ts |
| Duplicate delivery of an in-flight request id: skip | sdk.mjs |
| Cancellation: `{"type":"control_cancel_request","request_id":"..."}`; receiver aborts its handler and sends nothing further | sdk.mjs |
| Unknown inbound request subtype: reply `error` (never leave the CLI hanging) | inferred |

### 4.2 `initialize` (host → CLI, first frame)

```json
{"type":"control_request","request_id":"init1","request":{"subtype":"initialize",
  "hooks":null,"sdkMcpServers":[],"systemPrompt":null,"appendSystemPrompt":null,
  "supportedDialogKinds":[],"promptSuggestions":false}}
```

Request fields (all optional): `hooks`, `sdkMcpServers`, `sdkMcpServerConfigs`, `sdkMcpServerManifests`, `jsonSchema`,
`systemPrompt` (string[]), `appendSystemPrompt`, `systemPromptSnapshot`, `planModeInstructions`, `toolAliases`,
`excludeDynamicSections`, `agents`, `title`, `skills`, `promptSuggestions`, `agentProgressSummaries`,
`forwardSubagentText`, `supportedDialogKinds` (absence = cannot render any `request_user_dialog`; fails closed),
`perTaskStopAffordance`, `plugins` (only with `--await-initialize`).

Success `response` payload (`SDKControlInitializeResponse`):

| Field | Type |
|---|---|
| `commands` | `SlashCommand[]` |
| `agents` | `AgentInfo[]` |
| `output_style`, `available_output_styles` | string, string[] |
| `models` | `ModelInfo[]` (use this to populate the model picker) |
| `account` | `AccountInfo` (display only; never store) |
| `claude_code_version` | string (absent on older CLIs) |
| `fast_mode_state`, `fast_mode_disabled_reason` | optional |
| `hooks_applied`, `plugins_applied`, `sdk_mcp_manifests_parked` | optional |

The `control_response.response` object (sibling of `request_id`) on initialize also carries
`pending_permission_requests: control_request[]` and `pending_user_dialog_requests` (always present from 2.1.268; absence means older CLI). Process them as if they had just arrived.

### 4.3 `can_use_tool` (CLI → host)

Enabled by `--permission-prompt-tool=stdio`. The CLI blocks the tool until answered.

```json
{"type":"control_request","request_id":"perm7","request":{"subtype":"can_use_tool",
  "tool_name":"Bash","input":{"command":"rm -rf build"},"tool_use_id":"toolu_01...",
  "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"rm -rf build"}],"behavior":"allow","destination":"localSettings"}],
  "decision_reason_type":"rule","title":"...","display_name":"Bash","description":"..."}}
```

| Field | Notes |
|---|---|
| `tool_name`, `input`, `tool_use_id` | always |
| `permission_suggestions` | `PermissionUpdate[]` for "always allow" buttons |
| `blocked_path` | path that triggered the ask |
| `decision_reason`, `decision_reason_type` | `rule\|mode\|subcommandResults\|permissionPromptTool\|hook\|asyncAgent\|sandboxOverride\|workingDir\|safetyCheck\|classifier\|other` |
| `classifier_approvable` | `false` = a safety check needs a human |
| `suppress_always_allow_rule` | hide "don't ask again" |
| `default_to_no` | do not preselect Approve |
| `requires_user_interaction` | no one-tap Approve/Deny |
| `matched_ask_rule` | `{source, tool_name, rule_content?}` |
| `title`, `display_name`, `description` | UI strings |
| `agent_id` | subagent origin |
| `mcp_server` | `{name, source}` for MCP tools |

Response payload (`PermissionResult`; the SDK adds `toolUseID` = request `tool_use_id`):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"perm7","response":
  {"behavior":"allow","updatedInput":{"command":"rm -rf build"},"toolUseID":"toolu_01..."}}}
{"type":"control_response","response":{"subtype":"success","request_id":"perm7","response":
  {"behavior":"allow","updatedInput":{...},"updatedPermissions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"rm -rf build"}],"behavior":"allow","destination":"session"}],"toolUseID":"toolu_01..."}}}
{"type":"control_response","response":{"subtype":"success","request_id":"perm7","response":
  {"behavior":"deny","message":"User rejected","interrupt":true,"toolUseID":"toolu_01..."}}}
```

- `allow`: `updatedInput` optional in types; **always echo the (possibly edited) input** for safety on older CLIs (inferred).
- `deny`: `message` required; `interrupt:true` also aborts the turn.
- `PermissionUpdate` variants: `addRules|replaceRules|removeRules {rules:[{toolName,ruleContent?}], behavior:"allow"|"deny"|"ask", destination}`, `setMode {mode, destination}`, `addDirectories|removeDirectories {directories, destination}`; destination `userSettings|projectSettings|localSettings|session|cliArg`.
- Fixture order (`permission`): `assistant` with the `tool_use` block, then `content_block_stop`, then the
  `can_use_tool` request (its `tool_use_id` equals the block's `id`), then `message_delta`/`message_stop`. After the
  answer, a `user` line carries the `tool_result` (`is_error:true` with the deny message on deny) plus
  `tool_result_meta[{id, permission_decision:{decision:"accept"|"reject", source}}]`.
- Read-only commands (e.g. `echo`) are auto-approved and never reach the host.
- If the turn is interrupted while a prompt is open, the CLI sends `control_cancel_request` for it: close the dialog, send nothing.
- Auto-denials without a prompt arrive as `system/permission_denied` (section 5).

### 4.4 Host → CLI requests

| subtype | Request fields | Success `response` |
|---|---|---|
| `interrupt` | `cancel_queued?: boolean` | `{still_queued: string[], cancelled?: string[]}` (empty `{}` on CLIs without `interrupt_receipt_v1`) |
| `set_model` | `model?: string\|null` (`null`/`"default"` = session default) | none: the success frame has no `response` field (fixture). The CLI also emits a replayed `user` line (`isReplay:true`) with `<local-command-stdout>Set model to ...</local-command-stdout>` |
| `set_permission_mode` | `mode: "default"\|"acceptEdits"\|"bypassPermissions"\|"plan"\|"dontAsk"\|"auto"` | `{"mode": "<wire name>"}`, then a `system/status` line with `status: null` and the new `permissionMode`; the next `init` agrees (fixture `permission-mode`, 2.1.301) |
| `rename_session` | `title: string`, `source: "host"` | none: no `response` field, like `set_model`. A `system/session_title_changed` line (`title`) comes just before it, and the CLI appends a `custom-title` line to the transcript (fixtures `fork`, `fork-origin`) |
| `set_max_thinking_tokens` | `max_thinking_tokens?: number\|null`, `thinking_display?` | `{}` |
| `list_models` | — | model catalog (`{models: ModelInfo[]}`, inferred shape) |
| `get_context_usage` | `detail?: "summary"\|"full"` | categories `{name, tokens, kind:"used"\|"free"\|"buffer"\|"deferred"}` plus totals |
| `rewind_files` | `user_message_id: string`, `dry_run?: boolean` | rewind result (needs file checkpointing env) |
| `mcp_status`, `mcp_reconnect`, `mcp_toggle`, `mcp_set_servers` | per type | per type |
| `stop_task` | `task_id` | `{}` |
| `apply_flag_settings` | `settings: object`, merged into the session's flag layer | none (no `response` field); `{"effortLevel": …}` changes the effort of the next turn (below) |
| `update_settings` | `source: "localSettings"\|"userSettings"`, `settings: object` | writes a settings file; rejected without `source` (below). Tandem does not use it |
| `get_settings`, ... | see sdk.d.ts | |

Examples:

```json
{"type":"control_request","request_id":"a1","request":{"subtype":"interrupt"}}
{"type":"control_request","request_id":"a2","request":{"subtype":"set_model","model":"claude-opus-5-5"}}
{"type":"control_request","request_id":"a3","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}
{"type":"control_request","request_id":"a4","request":{"subtype":"get_context_usage","detail":"summary"}}
{"type":"control_request","request_id":"a5","request":{"subtype":"rewind_files","user_message_id":"<uuid>","dry_run":true}}
{"type":"control_request","request_id":"a6","request":{"subtype":"rename_session","title":"Fix the login test","source":"host"}}
{"type":"control_request","request_id":"a7","request":{"subtype":"apply_flag_settings","settings":{"effortLevel":"high"}}}
```

`rename_session` sent before the session's first prompt (measured with 2.1.301, `initialize` and `rename_session`
only): `system/session_title_changed` under the session's id, then success, for a new session (`--session-id`) and for
a fork with `--session-id` alike, and nothing is written to disk yet. Whether that title reaches the transcript once a
prompt creates it was not measured, so Tandem sends a title set before a process's first `system/init`, a fork's or a
rename's, only with that init. A session whose transcript exists already also gets the `custom-title` line from Tandem
at once (11.5), since the process may never get a prompt. The CLI also keeps the title in `<id>/custom-title.json`
beside the transcript (seen in the `fork` recording, not committed).

Effort on a live session, probed once with `scripts/drive_cli.py settings` against CLI 2.1.301 (not a fixture): one
turn at the default effort, then two requests, then a second turn.
- `{"subtype":"update_settings","settings":{"effortLevel":"high"}}` is rejected:
  `{"subtype":"error","request_id":…,"error":"update_settings: unsupported source undefined"}`. Its schema in the CLI's
  binary requires `source`: `localSettings` takes only `outputStyle`, and `userSettings` only `effortLevel`, which it
  saves in the user's settings file as the default for the current model rather than setting the running session's
  level. Writing the user's settings is not Tandem's to do.
- `{"subtype":"apply_flag_settings","settings":{"effortLevel":"high"}}` succeeds, with no `response` field, and the next
  turn ran at `high`: in the transcript the first turn's `assistant` line has `"effort":"medium"` and the second's
  `"effort":"high"` (`perTurnEffort` likewise).
- Neither turn's `system/init` names an effort (section 5.1), so only the transcript confirms the change. The models in
  the `initialize` response list `supportsEffort` and `supportedEffortLevels` (`low` to `max`).

Tandem passes effort only at spawn (`--effort`) for now; `apply_flag_settings` is the way to let a live session change it.

From the 2.1.301 binary's strings, not measured: a `CLAUDE_CODE_EFFORT_LEVEL` environment variable overrides the
session's effort, and `apply_flag_settings` then refuses an effort change ("apply_flag_settings: CLAUDE_CODE_EFFORT_LEVEL
overrides effort for this session"); a `maxEffort` setting or an organization's cap also clamps `--effort`. The variable
passes through `cliEnvironment` (1.2), so the effort badge, which shows what Tandem passed, can disagree with the level
the CLI runs at. The transcript's `assistant` lines should show the real level (their `effort` followed
`apply_flag_settings` in the probe above). The effort picker of Phase 4 (the sessions spec's numbering: dialogs and
control panels) has to handle both cases.

### 4.5 Other CLI → host requests

| subtype | When | Tandem |
|---|---|---|
| `hook_callback` | host-registered hooks in `initialize` | not used; reply `error` |
| `mcp_message` | `sdkMcpServers` in-process servers | not used (our MCP server is a real socket server via `--mcp-config`) |
| `elicitation` | MCP elicitation | reply `error` or render |
| `request_user_dialog` | only for kinds we declared | declare none |

The SDK leaves some subtypes unanswered on purpose (those "for the machine serving this session's tools"). Tandem: answer every unknown with `error`.

---

## 5. `system` messages

All carry `type:"system"`, `subtype`, `uuid`, `session_id`.

### 5.1 `init`

Emitted **at the start of every turn** (not only once). Fixture line (truncated):

```json
{"type":"system","subtype":"init","cwd":"<path>","session_id":"<sid>","tools":[],"mcp_servers":[],
 "model":"claude-sonnet-5-5","permissionMode":"default","slash_commands":[],"apiKeySource":"none",
 "claude_code_version":"2.1.300","output_style":"default","agents":[],"skills":[],"plugins":[],
 "capabilities":["interrupt_receipt_v1","interrupt_cancel_queued_v1","msg_lifecycle_v1",...],
 "fast_mode_state":"off","view_mode":"default","uuid":"<uuid>", ...}
```

| Field | Use |
|---|---|
| `model` | **compare with the user's pick every turn** (hard rule 5) |
| `session_id` | persist for resume |
| `cwd`, `tools`, `mcp_servers[{name,status,source?}]` | show MCP health; verify our `tandem` server is `connected` |
| `permissionMode` | sync UI |
| `slash_commands`, `terminal_slash_commands`, `skills`, `agents`, `plugins`, `plugin_errors` | command palette |
| `apiKeySource` | display only |
| `claude_code_version` | version gating |
| `capabilities` | feature detection; open set |
| `effort`, `betas`, `output_style`, `fast_mode_state` | display |

Extra fixture fields not in types: `product_feedback_disabled`, `analytics_disabled`, `scratchpad_path`, `messaging_socket_path`, `per_turn_effort_active`, `startup_timing{...}`. Ignore unknown fields.

`effort` is typed in the SDK but missing from every recorded `init` (2.1.300 and 2.1.301), which carry only
`per_turn_effort_active: true`. So Tandem shows the effort it passed with `--effort`, which for a session loaded from
disk is the last `assistant` line's `effort` in its transcript (section 11).

### 5.2 Other typed subtypes

| subtype | Fields | Tandem action |
|---|---|---|
| `status` | `status: "requesting"\|"compacting"\|null`, `permissionMode?`, `compact_result?: "success"\|"failed"`, `compact_error?` | spinner |
| `compact_boundary` | `compact_metadata{trigger:"manual"\|"auto", pre_tokens, post_tokens?, duration_ms?, preserved_segment?, preserved_messages?}` | divider in chat |
| `api_retry` | `attempt, max_retries, retry_delay_ms, error_status: number\|null, error: SDKAssistantMessageError, no_response?{waited_ms,retry_wait_ms}` | **show** "retrying (2/10) in 4s" |
| `model_refusal_fallback` | see 5.3 | **show** |
| `model_refusal_no_fallback` | see 5.3 | **show** |
| `permission_denied` | `tool_name, tool_use_id, agent_id?, decision_reason_type?, decision_reason?` | show denial |
| `notification` | `key, text, priority:"low"\|"medium"\|"high"\|"immediate", color?, timeout_ms?` | IDE notification/inline |
| `informational` | `content, level:"info"\|"notice"\|"suggestion"\|"warning", tool_use_id?, prevent_continuation?` | inline note |
| `session_state_changed` | `state:"idle"\|"running"\|"requires_action"` | `idle` is the authoritative turn-over signal |
| `local_command_output` | output of local slash commands | render as assistant text |
| `hook_started/progress/response` | hook lifecycle | ignore (or debug) |
| `task_started/progress/updated/notification`, `background_tasks_changed` | subagent/background tasks | task list |
| `commands_changed` | `commands` | refresh palette |
| `thinking_tokens`, `files_persisted`, `memory_recall`, `elicitation_complete`, `control_request_progress`, `plugin_install`, `mirror_error`, `worker_shutting_down` | | ignore or log type |

`SDKAssistantMessageError` = `authentication_failed | oauth_org_not_allowed | account_on_hold | verification_required | billing_error | rate_limit | overloaded | invalid_request | model_not_found | server_error | unknown | max_output_tokens | cloud_credential_error`.

### 5.3 Model fallback signals (critical)

There are three different ways the model can change under the user. Surface all of them.

**A. Refusal fallback** — `system/model_refusal_fallback`:

```json
{"type":"system","subtype":"model_refusal_fallback","trigger":"refusal","direction":"retry","scope":"session",
 "original_model":"claude-opus-5-5","fallback_model":"claude-sonnet-5-5","request_id":"req_...",
 "api_refusal_category":"cyber","api_refusal_explanation":"...","retracted_message_uuids":["<uuid>"],
 "refused_user_message_uuid":"<uuid>","content":"<banner text>","uuid":"...","session_id":"..."}
```

| Field | Meaning |
|---|---|
| `direction` | `retry` (retried once on fallback), `revert`, `sticky` |
| `scope` | `session` = session model swapped; `local` = only a subagent/side answer; absent = treat as `session` |
| `original_model`, `fallback_model` | show both |
| `retracted_message_uuids` | **remove these messages from the transcript** (arrives after the retraction; idempotent) |
| `refused_user_message_uuid` | edit-and-retry target |
| `content` | banner text to display |

**B. Refusal without fallback** — `system/model_refusal_no_fallback`: `original_model, request_id, api_refusal_category?, api_refusal_explanation?, refused_user_message_uuid?, content`. Also the assistant message has `stop_reason:"refusal"`.

**C. Overload/unavailable fallback** (`--fallback-model` or settings `fallbackModel`): no dedicated system subtype exists in the 0.3.300 types (inferred). Detect by comparing the requested model with `system/init.model`, `assistant.message.model`, `stream_event` `message_start.message.model` and the keys of `result.modelUsage`.
Note `modelUsage` legitimately contains helper models (fixture: `claude-haiku-5-5` for internal calls), so only `init.model`/`assistant.message.model` mismatches count as a fallback.
Also `assistant.error = "model_not_found"` means the CLI rejected the model.

**D. Explicit switches** (`set_model`, `/model`, automatic changes) have no stdout message of their own. The only typed
signal is the `PreModelSwitch`/`PostModelSwitch` hook input (`from_model`, `to_model`, `requested_model`,
`source: "command"|"picker"|"sdk"|"auto"|"resume"`), which a host only sees if it registers hooks in `initialize`.
Tandem relies on comparing `init.model` and `assistant.message.model` against the requested model instead.

---

## 6. Assistant, user, and stream events

### 6.1 `assistant`

```json
{"type":"assistant","message":{"model":"claude-sonnet-5-5","id":"msg_011C...","type":"message","role":"assistant",
 "content":[{"type":"text","text":"pong"}],"stop_reason":null,"usage":{...}},
 "parent_tool_use_id":null,"session_id":"<sid>","uuid":"<uuid>","timestamp":"2026-10-15T18:46:58.911Z","request_id":"req_..."}
```

- One `assistant` line **per completed content block**; consecutive lines share `message.id`, each with only that block in `content`.
- `stop_reason` is `null` and `usage` is not final on these; final stop reason and usage come on `result`.
- Content block types: `text`, `thinking` (`thinking`, `signature`), `redacted_thinking`, `tool_use` (`id`, `name`, `input`), `server_tool_use`, `web_search_tool_result`, others: keep as unknown blocks.
- `error?: SDKAssistantMessageError` marks synthetic error messages (auth, rate limit, model_not_found).
- `parent_tool_use_id` non-null = produced inside a subagent started by that `tool_use`.
- `supersedes?: uuid[]`: uuids of earlier messages this one replaces (refusal-fallback retry). Evict them on arrival; idempotent with `model_refusal_fallback.retracted_message_uuids`.
- `aborted?: true`: truncated by an interrupt; `stop_reason` never arrived and the text may end mid-word.

### 6.2 `user` (tool results)

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01...","content":"...","is_error":false}]},
 "parent_tool_use_id":null,"tool_use_result":{...},"session_id":"<sid>","uuid":"<uuid>"}
```

- `tool_use_result` is the tool's structured output (per-tool shape, unknown-typed).
- `isSynthetic: true` for CLI-generated user messages; `isReplay: true` for echoes of our own sends (with `--replay-user-messages`).

### 6.3 `stream_event` (with `--include-partial-messages`)

```json
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"pong"}},
 "session_id":"<sid>","parent_tool_use_id":null,"uuid":"<uuid>","api_message_id":"msg_01..."}
```

| Field | Notes |
|---|---|
| `event` | Anthropic SSE event: `message_start`, `content_block_start`, `content_block_delta` (`text_delta`, `thinking_delta`, `signature_delta`, `input_json_delta`), `content_block_stop`, `message_delta`, `message_stop` |
| `uuid` | unique per frame, **not** the assistant message uuid |
| `api_message_id` | fixture-only field: the API message id; matches `message_start.message.id` and `assistant.message.id` in real runs (inferred; redacted in the fixture) |
| `ttft_ms` | on `message_start` |
| `user_message_uuid(s)` | on the turn's first non-ping event: which client send this turn answers |
| `thinking_display` | on `message_start` in the fixture; the SDK types it only on `set_max_thinking_tokens` |

### 6.4 Correlating partial and complete messages

Fixture order for one text block:

```
stream_event message_start            (message.id = M)
stream_event content_block_start  idx 0
stream_event content_block_delta  idx 0 "pong"
assistant    message.id = M, content=[text "pong"]   <-- complete block arrives here
stream_event content_block_stop   idx 0              <-- AFTER the assistant line
stream_event message_delta        stop_reason=end_turn, usage
stream_event message_stop
system       post_turn_summary
result
```

Reconciliation rules for `session/`:
1. Key the in-progress bubble by (`message.id` from `message_start`, `parent_tool_use_id`); track blocks by `index`.
2. Append deltas to block `index`.
3. When an `assistant` line with the same `message.id` arrives, **replace** the matching block with the complete one (match by position: the Nth `assistant` line for a message id = the Nth started block (inferred)). Do not wait for `content_block_stop`; it may come after.
4. A late `content_block_stop` for an already-completed block is a no-op.
5. If partials are missing (no flag, or retries), the `assistant` line alone is sufficient.
6. Subagent traffic interleaves; always key by `parent_tool_use_id` too.
7. On `model_refusal_fallback`, drop `retracted_message_uuids` (these are `assistant`/`user` `uuid`s, not stream-frame uuids).

---

## 7. `result`

Exactly one per turn, after that turn's `assistant`/`user`/`stream_event` lines; some `system` lines (e.g. `post_turn_summary`, `session_state_changed`) can still follow.

```json
{"type":"result","subtype":"success","is_error":false,"result":"pong","num_turns":1,"duration_ms":1456,"duration_api_ms":1961,
 "stop_reason":"end_turn","total_cost_usd":0.0092643,"usage":{...},"modelUsage":{"claude-sonnet-5-5":{"inputTokens":2,"outputTokens":4,"costUSD":0.0091409,"contextWindow":1000000,...}},
 "permission_denials":[],"terminal_reason":"completed","session_id":"<sid>","uuid":"<uuid>","result_index":0,"queued_turn_count":0}
```

| subtype | Meaning |
|---|---|
| `success` | normal end; `is_error:true` possible (then `result` holds error text) |
| `error_during_execution` | crash / abort / startup failure (`startup_failure_reason` set for known causes) |
| `error_max_turns` | `--max-turns` hit |
| `error_max_budget_usd` | budget hit |
| `error_max_structured_output_retries` | `--json-schema` retries exhausted |

Common fields: `duration_ms`, `duration_api_ms`, `is_error`, `num_turns`, `stop_reason`, `total_cost_usd` (cumulative over the process; read latest, don't sum), `usage` (main loop, per turn), `modelUsage` (cumulative, per model), `permission_denials[{tool_name,tool_use_id,tool_input}]`, `queued_turn_count`, `user_message_uuid(s)`, `terminal_reason`, `result_index` (gap = lost result), `fast_mode_state`.
Success only: `result`, `ttft_ms`, `ttft_stream_ms`, `time_to_request_ms`, `structured_output?`.
Error only: `errors: string[]`, `startup_failure_reason?`.
The fixture also has `api_error_status`, `subagent_stats`, `safety_stops`, `turn_start_resume_kind`, many `process_*` timing fields: ignore.

Turn over = `result` for the main thread (`queued_turn_count == 0`), or `session_state_changed{state:"idle"}` when available.

---

## 8. Sessions, resume, fork, model switching

| Goal | How |
|---|---|
| New session | `--session-id <uuid>` with an id Tandem generates. The first prompt's `system/init` reports it, and the CLI writes `<uuid>.jsonl` from that prompt on (section 1.1) |
| Resume | `--resume <id>` once `<id>.jsonl` exists, else `--session-id <id>` again. The CLI loads the transcript itself and does not stream the history to stdout (fixture `fork`: the second process streams only its own turn); Tandem loads it from the transcript (section 11). Without `--model` the session ran the model it was started with (`--model` at the start), not the default (below); a model switched mid-session with `set_model` was not measured |
| Continue latest in cwd | `--continue` (not used) |
| Fork | `--resume <id> --fork-session --session-id <new uuid>` until the fork's own transcript exists, then `--resume <new uuid>`. Measured with 2.1.301 (one prompt each): the fork's `system/init` reports `<new uuid>` and `<id>.jsonl` is left as it was. The fork's transcript starts with a copy of the original's history, every `sessionId` rewritten to the fork's (fixture `fork`). Without `--session-id` the CLI picks the fork's id, and no `system/init` reports it before the fork's first prompt (only undocumented lines do, section 1.1) |
| Resume at a point | `--resume <id> --resume-session-at <message uuid>` (not used) |
| Switch model mid-session | `control_request{set_model, model}`; next turn's `system/init.model` must equal it. `--model` on resume also works |
| Switch permission mode | `control_request{set_permission_mode}`: acknowledged with `{"mode": …}` and a `system/status` line carrying `permissionMode` right after it; the next `init.permissionMode` agrees (fixture `permission-mode`: `default`, `plan`, `acceptEdits`). Tandem shows the new mode at once; an `error` response to that request (envelope 4.1; no rejection has been recorded) puts back the mode shown before it, unless a later pick replaced it, with an error notice naming the rejected mode |
| Change effort | `control_request{apply_flag_settings, settings: {effortLevel}}` (section 4.4); Tandem picks effort only when a session is created |
| Rename | a live process that has reported its first `system/init`: `control_request{rename_session}` (section 4.4); otherwise append a `custom-title` line (section 11.5) if the transcript exists. A live process that has not reported it yet also gets `rename_session` with that init, and so does the next process of a session renamed without a transcript or while an earlier title still waited for an init; only the last title is sent |
| Delete | remove `<id>.jsonl` and `<id>/` (section 11.5) |
| Rewind files | `rewind_files{user_message_id}` (needs `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true` at spawn) |

The `session_id` on every line is authoritative; it changes after fork.

Model on resume, measured with 2.1.301 (one short prompt each, signed in, from an empty scratch folder, spawned as
`drive_cli.py` spawns the CLI: the parent-session variables of section 1.2 removed, `--safe-mode`): a session started
with `--model sonnet --session-id <uuid>` ran `claude-sonnet-5-5`; `--resume <uuid>` without `--model` then reported
`init.model` `claude-sonnet-5-5`, while that run's `initialize` response listed `default` as `claude-opus-5-5`. The
transcript's `assistant` lines carry `requestedModel` `claude-sonnet-5-5` for both turns. So the CLI restores the
model a session was started with itself. Not measured: whether a resume restores a model switched mid-session with
`set_model` rather than the start-time one; the badge corrects itself either way once `init` reports the model.
Tandem relies only on the measured case: Tandem passes `--model` on resume only for a model picked in Tandem, a loaded session's
`requestedModel` stays null, and the badge shows the transcript's last model until `init` confirms it.

Resume-id mismatch: the `session_id` of a process's first `system/init` must be the id Tandem started it with
(`--session-id`, `--resume`, or a fork's own id). If it is another id, Tandem never relabels the session: the process and
its chat move to a new row for the reported id with the warning "Resumed as a new session <id>", and the original row
goes back to its chat from before that start and shows stopped. If the reported id is already open in another row, the
process is stopped instead (`SessionManager.takeOver`).

Fork title: the CLI writes no "(fork)" title. A fork's transcript only inherits the original's `custom-title` and
`ai-title` lines (fixture `fork`, and the measurement above). Tandem shows "<original title> (fork)" at once and sends
`rename_session` with that title when the fork's first `system/init` arrives, which is with its first prompt.

---

## 9. Gotchas

| Topic | Detail |
|---|---|
| Version gating | Use `init.capabilities` first, `claude_code_version` second. `pending_permission_requests` on initialize from 2.1.268; `interrupt_receipt_v1` for `still_queued`; `scope` on refusal fallback is new (absent = `session`). Refuse to start below a minimum tested version and say so. |
| Interrupt | Fixture `interrupt`: the receipt `{still_queued:[]}` arrives, then the partial `assistant`, a synthetic `user` text `[Request interrupted by user]`, the remaining stream frames, and `result{subtype:"error_during_execution", is_error:true, terminal_reason:"aborted_streaming", stop_reason:null}`. Treat that result as "stopped", not as a failure. Recorded again with 2.1.301 as `history-interrupt`, with its transcript (section 11): the same order; closing stdin afterwards ended the process with exit code 1. |
| Undocumented stdout types | See section 10. The SDK passes them through untouched. |
| Pre-init lines | `active_goal`/`autocompact_state` arrive **before** `system/init`. Do not assume `init` is first. |
| Keep-alive | `{"type":"keep_alive"}` can arrive anytime (e.g. during long control requests); ignore. Must not reset any "turn finished" state. |
| Ordering | complete `assistant` precedes `content_block_stop` (section 6.4). |
| stdin close | Closing stdin = end of input. The SDK only calls `endInput()` after the last result when no bidirectional needs (permissions, hooks, MCP) remain. **Keep stdin open for the whole session**; close it to shut down gracefully, then wait for exit. |
| Shutdown | On dispose: close stdin, wait briefly, then SIGTERM, then SIGKILL (inferred timings). Writes after the child exits raise EPIPE: treat as session ended. |
| Exit codes | `0` normal. Non-zero: SDK error text `Claude Code process exited with code N` + stderr tail. Killed by signal: report the signal. Startup failures may first write a zeroed `result{subtype:"error_during_execution", startup_failure_reason}`; show `errors[]`. |
| Not logged in | surfaces as `assistant.error:"authentication_failed"` and/or error `result` (inferred). Tell the user to run `claude` in a terminal; never handle credentials. |
| Non-JSON stdout | skip line, log length only. |
| Large lines | single lines can be hundreds of KB (tool results, images). Use a streaming line reader without a small max line length. |
| `--permission-prompt-tool` | `stdio` and an MCP tool name are mutually exclusive. |
| `--fallback-model` | must differ from `--model` (SDK throws). |
| Workspace trust | headless mode skips the trust dialog; Tandem should only start in trusted projects (IDE trust). |
| Settings parse errors | invalid settings files are silently ignored in print mode; `get_settings` reports them. |

---

## 10. Fixture fields and types the SDK does not document

Seen in the fixtures (`plain-reply.ndjson` and the others, CLI 2.1.300 and 2.1.301) but absent from `sdk.d.ts` 0.3.300,
or typed differently there.
Parse them leniently; any of them can change without notice.

| Where | What | Notes |
|---|---|---|
| top-level `autocompact_state` | `{value:{enabled, effective_window, threshold, enforced, source}}` | not in `sdk.d.ts`. Emitted twice before `init` in the fixture; the last one wins |
| top-level `active_goal` | `{value: {condition, iterations, set_at, tokens_at_start, last_reason?} \| null}` | typed in `sdk.d.ts` but not part of the `SDKMessage` union; arrives before `init` |
| `system/post_turn_summary` | `summarizes_uuid, status_category, status_detail, needs_action` | not in `sdk.d.ts`. After `message_stop`, before `result` |
| `stream_event.api_message_id` | API message id on every stream frame | not in `sdk.d.ts`; equals `message_start.message.id` |
| `message_start.message` | `container, stop_details, input_transformations, diagnostics, context_management` | API-level fields, not in the SDK types |
| `system/init` | `analytics_disabled, product_feedback_disabled, scratchpad_path, messaging_socket_path, per_turn_effort_active, startup_timing` | not in `SDKSystemMessage` |
| `result` | `api_error_status, subagent_stats, safety_stops, turn_start_resume_kind, process_*` timings | not in `SDKResultMessage` |
| `rate_limit_event.rate_limit_info` | `overageDisabledReason, unifiedWindows{five_hour,seven_day}` | beyond the typed fields |
| top-level `command_lifecycle` | `{command_uuid, state:"queued"\|"started"\|"completed"\|"cancelled"}` | not in `sdk.d.ts`. `command_uuid` is the `uuid` the host sent with the user message |
| `system/ui_invalidate` | `{event:"ui.render"}` | not in `sdk.d.ts`; before `init` |
| `system/task_summary` | `{detail: string\|null}` | not in `sdk.d.ts`; short status line while a tool runs, `null` when done |
| `system/session_title_changed` | `{title}` | not in `sdk.d.ts`. Just before the success of a `rename_session` (section 4.4); a fork of a renamed session also emits it, before its `initialize` response (fixture `fork`, 2.1.301) |
| `assistant.wire_tool_inputs` | `{<tool_use id>: input}` | not in `sdk.d.ts` |
| `user.tool_result_meta` | `[{id, permission_decision:{decision, source}, non_execution_kind?}]` | not in `sdk.d.ts` |
| initialize `control_response` | `pid, current_permission_mode, session_state, feedback_mode, remote_control_*, user_output_styles_dir` | beyond `SDKControlInitializeResponse` |

---

## 11. Transcripts on disk

The CLI keeps each session as a JSONL file that it only ever appends to. Tandem has no session store of its own: the
sidebar lists these files and a session's history loads from its file (`session/transcript/`), so sessions started in a
terminal show up too. Sources: SDK 0.3.300 (`sdk.mjs` reads and writes the same files), the transcript fixtures in
`src/test/resources/fixtures/transcripts/` (CLI 2.1.301), and measurements where noted.

### 11.1 Where they are

- Config directory: `$CLAUDE_CONFIG_DIR` when it is set and not blank, else `~/.claude`.
- Folder: `<config>/projects/<key>/`. `<key>` is the project path with every character outside `A–Z a–z 0–9` replaced
  by `-`, one dash per UTF-16 unit as the CLI's JavaScript `replace(/[^a-zA-Z0-9]/g, "-")` does (an emoji gives `--`),
  cut to 200 characters: `/home/u/my proj.v2` gives `-home-u-my-proj-v2`, `C:\Users\dev\app` gives `C--Users-dev-app`.
- The path is the CLI's physical working directory (`/private/tmp/…` for `/tmp/…` on macOS), so Tandem resolves the
  project path's links before computing the key, off the EDT.
- A key cut at 200 characters gets `-<hash>` appended, from the CLI's own hash, which Tandem does not reproduce. For such
  a project Tandem lists every folder whose name starts with the 200-character key and keeps the transcripts whose `cwd`
  is the project path (or that name none).
- On a case-insensitive volume (macOS by default) `~/Code/App` and `~/code/app` are one directory, and the CLI may have
  keyed or recorded it in other case than the path Tandem resolved. A short key needs nothing for that: the volume finds
  the folder whatever the case. For a cut key, a folder whose name starts with the key in other case only is taken, and a
  `cwd` that is another string is kept, when the file system says it is the same directory (`Files.isSameFile`); case is
  never folded otherwise, so on a case-sensitive volume they stay another project's. Not measured on a Mac yet: which
  case the CLI records for a working directory entered in other case.
- One file per session: `<session id>.jsonl`, the id a lower-case UUID. Other files in the folder, such as a subagent's
  `agent-*.jsonl` or temporary files, are not sessions.
- `<session id>/` beside it holds what else the CLI keeps for the session: a `custom-title.json` after a rename (seen in
  a 2.1.301 recording) and, per the SDK, subagent transcripts and tool results.
- The file appears with the session's first prompt (section 1.1). A running CLI may be appending to it while Tandem
  reads: a last line the CLI is still writing is not a JSON object yet and is skipped.
- Tandem watches the folders, so a session started elsewhere shows up within a few seconds on Linux and Windows. On
  macOS the JDK's `WatchService` polls, by default every 10 s; Tandem registers the folders with the JDK's
  `SensitivityWatchEventModifier.HIGH` (looked up by name, since it is deprecated for removal), which makes it poll every
  2 s, so a session shows up within about 3 s there. A runtime without that modifier, or a watch service that rejects
  it, watches without it, at the default rate (the sidebar also lists the folders again whenever it appears). For the list, a transcript that only grew since it was read is not read in full
  again: the lines appended since are scanned for title lines (11.4). It is read in full when it shrank, or when the
  part read before had no prompt yet or did not end with a whole line.

### 11.2 Line types

Each line is one JSON object with a `type`. Most lines also have `sessionId`, `timestamp`, `cwd`, `version`,
`gitBranch` and `entrypoint`. Tandem takes `cwd` from the first line that has one, whatever its type (lines off the
current history aside), to tell apart projects that share a folder (11.1). The lines that make up the conversation
have `uuid` and `parentUuid` (11.3).

| `type` | Fields Tandem reads | Use |
|---|---|---|
| `user` | `uuid`, `parentUuid`, `isSidechain`, `isMeta`, `isCompactSummary`, `permissionMode`, `message.content` | prompts (a string or text blocks) and tool results (`tool_result` blocks); the last `permissionMode` is the session's mode |
| `assistant` | `uuid`, `parentUuid`, `isSidechain`, `effort`, `message.id`, `message.model`, `message.content` | one line per content block, sharing `message.id`, as on stdout (6.1); the last model and effort |
| `custom-title` | `customTitle` | the title the user or `rename_session` set |
| `ai-title` | `aiTitle` | a generated title |
| `summary` | `summary` | a generated summary, a title like `ai-title` |
| `last-prompt` | none | `lastPrompt`, `leafUuid`: not used |
| `system` | `uuid`, `parentUuid`, `subtype` | links of the chain; `subtype: compact_boundary` marks a compaction (11.3) |
| `attachment`, `queue-operation`, `cost-state`, `atis-latch`, `mode`, `agent-name`, `file-history-snapshot`, `progress`, any other type | `uuid`, `parentUuid` when present | skipped, except as links of the chain |

The 2.1.301 fixtures show every type above except `summary`, `system`, `file-history-snapshot` and `progress`, which
come from the SDK's reader. Also recorded: a prompt's `user` line has the `uuid` the host sent with the prompt
(`history-interrupt`); `assistant` lines also carry `requestedModel`, `perTurnEffort` and `requestId`; a reply cut off
by an interrupt has `isAbortedMidStream: true` where its stdout line has `aborted: true`.

### 11.3 The current history

A rewind or an edited prompt leaves the abandoned turns in the file, so the history is not simply every line. A first
pass (`TranscriptChain`) works out which conversational lines (`user` or `assistant`, neither `isSidechain` nor
`isMeta`) belong to it:

1. The current history is the `parentUuid` chain that ends at the last conversational line with a `uuid`. The chain runs through lines
   of any type (in the fixtures every `assistant` line's parent is an `attachment` line), so every line with a `uuid` is
   indexed. A line whose parent is not in the file is a root.
2. If that chain starts at a compaction root, a `user` line with `isCompactSummary` or a `system` line with
   `subtype: compact_boundary`, the chain before the compaction is kept as well, and so on back to the first compaction. A
   `compact_boundary` line has a null `parentUuid` and points back with `logicalParentUuid` (inferred: no fixture); that
   chain starts at the line it names when it names an earlier line, else at the last conversational line with a `uuid`
   before the root.
3. Parallel tool calls branch off the chain. An off-chain `assistant` line with the `message.id` of a kept `assistant`
   line joins the history, and so does an off-chain `user` line that holds only `tool_result` blocks and whose parent is
   kept, until nothing changes. A `user` line with text never joins this way: a rewound branch starts with one.
4. A conversational line without a `uuid` is always kept. A line that was not a JSON object in the first pass (one the
   CLI was still writing) is left out of the second, even if it is complete by then.

The second pass reads the kept lines the way the live stream would have shown them; title lines count wherever they
are. The SDK's reader (`getSessionMessages`, 0.3.300) also walks the chain and re-attaches such off-chain lines. No
fixture has a rewind, a compaction or parallel tool calls yet; `TranscriptReaderTest` pins them with hand-written lines.

### 11.4 What Tandem shows

- Title, the first that applies: the last `custom-title`; the last `ai-title` or `summary`, whichever comes last; the
  first prompt of the current history, slash commands aside, as its first line trimmed and cut to 80 characters; the
  session id. A blank title does not count. The SDK's `listSessions` (0.3.300) differs: it joins the prompt's lines,
  cuts at 200 characters and prefers `aiTitle` over `summary`.
- Model: `message.model` of the last `assistant` line, except models that start with `<`, such as `<synthetic>` on the
  lines the CLI writes itself for API errors. Effort: the last `effort` present. Permission mode: the last `user` line's
  `permissionMode` that names a known mode; compact summaries do not count. All three from the current history only.
- Skipped: `isMeta` lines (context the CLI adds), `isSidechain` lines (subagents) and `isCompactSummary` lines.
- User lines that the CLI writes itself, told apart by how the text starts, as the CLI does:

| Text starts with | Written for | Tandem shows |
|---|---|---|
| `[Request interrupted` (`by user`, `by user for tool use`) | an interrupt | the "Stopped." notice, as live |
| `<command-name>` or `<command-message>`, with `<command-args>` | a slash command | `/name args`, never as the title |
| `<local-command-stdout>`, `<local-command-stderr>` | a command's output | nothing |
| `<bash-input>` | a `!command` shell escape | `!command` |
| `<bash-stdout>`, `<bash-stderr>` | its output | nothing |
| `<task-notification>` | a background task | nothing |

Only the interrupt line is recorded (`history-interrupt`, 2.1.301):
`{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"parentUuid":<the cut-off reply>,…}`,
the shape the hand-written test uses. The others come from the CLI's binary and the SDK and are pinned with hand-written
lines in `TranscriptReaderTest`.

A loaded chat is settled whatever the file ends with: not busy, no pending permission, no streaming entry.

### 11.5 What Tandem writes

- Rename of a session without a live process, or whose process has not reported its first `system/init` yet: one line
  appended, the one the SDK's `renameSession` appends:
  `{"type":"custom-title","customTitle":<title>,"sessionId":<id>,"uuid":<random UUID>,"timestamp":<ISO-8601 UTC, ms>}`.
  A file that ends inside a line gets its newline first. A live session is renamed with `rename_session` (4.4), and the
  CLI appends the line itself; a process that has not reported its `system/init` gets `rename_session` with it.
- Delete: `<id>.jsonl` and the `<id>/` folder beside it, which is what the SDK's `deleteSession` removes. Links are
  deleted, never followed.

Apart from the binary lookup, which also looks for `claude` in `~/.claude/local` (section 1.3), Tandem reads and writes
nothing else under the config directory: credentials (`.credentials*`) and settings files are out of bounds (hard
rule 3).

# CLI stream-json protocol reference

How Tandem talks to the user's `claude` binary (Claude Code 2.1.x) over stdin/stdout NDJSON.

Sources, in order of authority:
1. The recorded transcript `src/test/resources/fixtures/plain-reply.ndjson` (CLI 2.1.300).
2. `@anthropic-ai/claude-agent-sdk` 0.3.300: `sdk.d.ts` (types) and `sdk.mjs` (what the SDK actually writes and reads).
3. `claude --help` output.

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
| `--effort <low\|medium\|high\|xhigh\|max>` | `effort` option | optional |
| `--max-turns N`, `--max-budget-usd X` | limits | no |
| `--model <id>` | `model` option | yes, user's pick |
| `--permission-prompt-tool=stdio` | a `canUseTool` callback is set | **yes, always** |
| `--permission-prompt-tool <mcp tool>` | `permissionPromptToolName` (mutually exclusive with the above) | no |
| `--permission-prompts <host\|none>` | option | no (default `host`) |
| `--continue` / `--resume <session-id>` | resume options | see section 8 |
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
| `--fork-session` | `forkSession` | see section 8 |
| `--resume-session-at <uuid>` | `resumeSessionAt` | see section 8 |
| `--session-id <uuid>` | `sessionId` | optional (pre-assign id) |
| `--no-session-persistence` | `persistSession:false` | no |

Notes:
- The SDK does **not** pass `-p`/`--print`. `--input-format stream-json` implies headless print mode.
- `--replay-user-messages` (help text) re-emits user messages from stdin as `{"type":"user",...,"isReplay":true}` acks. The SDK does not add it by default; useful for binding our sends to transcript uuids.
- `--verbose` is required with `stream-json` output, otherwise the CLI rejects the combination (inferred from the SDK always adding it).

Recommended Tandem argv:

```
claude --output-format stream-json --verbose --input-format stream-json \
  --include-partial-messages --permission-prompt-tool=stdio \
  --model <picked> --permission-mode <mode> \
  --mcp-config '{"mcpServers":{"tandem":{...}}}' \
  --disallowedTools Edit,Write,MultiEdit,NotebookEdit \
  [--resume <session-id> [--fork-session]]
```

### 1.2 Environment

The SDK copies the host environment (or `options.env` if given) and then:

| Var | Value | Notes |
|---|---|---|
| `CLAUDE_CODE_ENTRYPOINT` | `sdk-ts` if unset | Tandem should set its own value, e.g. `sdk-tandem` (inferred: any string; certain values like `claude-desktop-3p`, `remote_cowork` switch CLI behaviour, avoid them) |
| `CLAUDE_AGENT_SDK_VERSION` | `0.3.300` if unset | do not fake this |
| `CLAUDE_CODE_SDK_READS_SESSION_STATE` | `1` unless set (any case) | makes the CLI emit `system/session_state_changed` (inferred) |
| `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING` | `true` when file checkpointing enabled | needed for `rewind_files` |
| `NODE_OPTIONS` | deleted | |
| `DEBUG` | deleted (or `1` if `DEBUG_CLAUDE_AGENT_SDK`) | |
| `TRACEPARENT`/`TRACESTATE` | set from the active OTel context | ignore |

Never set or read API keys (hard rule 3); the CLI resolves auth itself.

### 1.3 cwd, stdio, binary lookup

- `cwd` = `options.cwd` (Tandem: project base path). The CLI reports it back in `system/init.cwd`.
- `stdio: ["pipe","pipe","pipe"]`, `windowsHide: true`. stderr is buffered as a tail for error messages only, not parsed.
- Binary: the SDK uses `pathToClaudeCodeExecutable` or its own bundled native package
  (`@anthropic-ai/claude-agent-sdk-<platform>-<arch>[-musl]/claude[.exe]`). If the path ends in `.js/.mjs/.ts/...` it is run with node.
  Tandem has no bundled binary: resolve `claude` from a user setting, else `PATH` (`where claude` on Windows, also try
  `~/.claude/local/claude`, `~/.local/bin/claude`) (inferred locations). Spawn the native binary directly.

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
| `rate_limit_event` | subscription rate-limit status |
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
- If the turn is interrupted while a prompt is open, the CLI sends `control_cancel_request` for it: close the dialog, send nothing.
- Auto-denials without a prompt arrive as `system/permission_denied` (section 5).

### 4.4 Host → CLI requests

| subtype | Request fields | Success `response` |
|---|---|---|
| `interrupt` | `cancel_queued?: boolean` | `{still_queued: string[], cancelled?: string[]}` (empty `{}` on CLIs without `interrupt_receipt_v1`) |
| `set_model` | `model?: string\|null` (`null`/`"default"` = session default) | `{}` |
| `set_permission_mode` | `mode: "default"\|"acceptEdits"\|"bypassPermissions"\|"plan"\|"dontAsk"\|"auto"` | `{}` |
| `set_max_thinking_tokens` | `max_thinking_tokens?: number\|null`, `thinking_display?` | `{}` |
| `list_models` | — | model catalog (`{models: ModelInfo[]}`, inferred shape) |
| `get_context_usage` | `detail?: "summary"\|"full"` | categories `{name, tokens, kind:"used"\|"free"\|"buffer"\|"deferred"}` plus totals |
| `rewind_files` | `user_message_id: string`, `dry_run?: boolean` | rewind result (needs file checkpointing env) |
| `mcp_status`, `mcp_reconnect`, `mcp_toggle`, `mcp_set_servers` | per type | per type |
| `stop_task` | `task_id` | `{}` |
| `get_settings`, `apply_flag_settings`, `rename_session`, ... | see sdk.d.ts | |

Examples:

```json
{"type":"control_request","request_id":"a1","request":{"subtype":"interrupt"}}
{"type":"control_request","request_id":"a2","request":{"subtype":"set_model","model":"claude-opus-5-5"}}
{"type":"control_request","request_id":"a3","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}
{"type":"control_request","request_id":"a4","request":{"subtype":"get_context_usage","detail":"summary"}}
{"type":"control_request","request_id":"a5","request":{"subtype":"rewind_files","user_message_id":"<uuid>","dry_run":true}}
```

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
| `thinking_display` | fixture-only field on `message_start` |

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
| New session | no flag; read id from `system/init.session_id` (or pre-assign with `--session-id <uuid>`) |
| Resume | `--resume <session-id>` (new process; transcript is loaded by the CLI, history is **not** re-streamed to stdout (inferred): load past messages from the session JSONL if needed) |
| Continue latest in cwd | `--continue` |
| Fork | `--resume <id> --fork-session` → new `session_id` in `init` |
| Resume at a point | `--resume <id> --resume-session-at <message uuid>` |
| Switch model mid-session | `control_request{set_model, model}`; next turn's `system/init.model` must equal it. `--model` on resume also works |
| Switch permission mode | `control_request{set_permission_mode}`; confirmed by `system/status.permissionMode` / next `init.permissionMode` |
| Rewind files | `rewind_files{user_message_id}` (needs `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true` at spawn) |

The `session_id` on every line is authoritative; it changes after fork.

---

## 9. Gotchas

| Topic | Detail |
|---|---|
| Version gating | Use `init.capabilities` first, `claude_code_version` second. `pending_permission_requests` on initialize from 2.1.268; `interrupt_receipt_v1` for `still_queued`; `scope` on refusal fallback is new (absent = `session`). Refuse to start below a minimum tested version and say so. |
| Undocumented stdout types | `active_goal` `{value: {condition,iterations,set_at,tokens_at_start,last_reason?}\|null}`, `autocompact_state` `{value:{enabled,effective_window,threshold,enforced,source}}` (fixture: emitted twice before init, second wins), `rate_limit_event` `{rate_limit_info:{status:"allowed"\|"allowed_warning"\|"rejected", resetsAt, rateLimitType, utilization?, overageStatus?, isUsingOverage?, unifiedWindows?}}`, `system/post_turn_summary` `{summarizes_uuid, status_category, status_detail, needs_action}` (after the turn, before `result`). The SDK passes them through untouched. |
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

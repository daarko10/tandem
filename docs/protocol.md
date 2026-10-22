# CLI stream-json protocol reference

How Tandem talks to the user's `claude` binary (Claude Code; recorded with CLI 2.1.300 to 2.1.306) over stdin/stdout
NDJSON. Every claim names the fixture line that shows it and the CLI version it was recorded on, or the source it rests
on (below); the traps that cost the most time are collected in section 9 (Gotchas).

Sources, in order of authority:
1. Recorded transcripts in `src/test/resources/fixtures/`, cited as `<name>.ndjson:<line>` (also `<name>:<line>`, and
   `:<line>` for another line of the fixture just named). A `.ndjson` file is the CLI's stdout only: where a claim rests on
   what the host sent, this document quotes or describes that frame. `fixtures/transcripts/<name>.jsonl` is the CLI's own
   on-disk transcript of the same session (section 11), where the recording has one. By CLI version:
   - 2.1.300: `plain-reply`, `permission`, `interrupt`, `model-switch` (no transcripts).
   - 2.1.301: `history-reply`, `history-permission`, `history-interrupt` and `fork`, each with its transcript (`fork-origin`
     is the transcript of the session `fork` forks, with no stream twin); `permission-mode` (no transcript).
   - 2.1.302: 29 probe recordings made on 2026-10-18, each with its transcript: `ask-*` (`AskUserQuestion` answered in each
     way a host can), `plan-*` (`ExitPlanMode`), `think-*` (the thinking displays), `tools-*` (Read, Edit, Write, Bash and an
     unknown tool), `agent-*` (subagents and Bash tasks), `misc-*` (an image, the web tools, the session-state lines). Also
     `invalid-model` (a `--model` that does not exist) and `permission-mode-rejected`, each with its transcript.
   - 2.1.306, each with its transcript: `ask-two` (one `AskUserQuestion` call with two questions, a one-answer and a
     multi-select, answered together), `effort-switch` (a live effort change, 4.4), `permission-mode-auto` (the `auto` mode,
     8), `user-image` (a text block then a base64 PNG block, section 3; the transcript's user line keeps both blocks, the
     image's `data` verbatim), `bash-mode` and `bash-mode-respond` (shell escapes, section 3;
     `transcripts/bash-mode-interactive.jsonl` is the terminal's own `!`, with no stream twin), and the MCP recordings
     `mcp-status`, `mcp-tool-call` and `mcp-slash` (how they were made: section 10).
   `synthetic-long-reply` is generated from `plain-reply` by `scripts/make_long_reply.py`, the only fixture that was not
   recorded; nothing here cites it. Re-record with `scripts/record-fixture.sh` (one-shot prompt) or
   `scripts/record-scenario.sh` (bidirectional scenarios); both copy the transcript too and redact the recording in the
   same run: paths read `<path>`, ids are numbered placeholders such as `<id-36>` or `<request_id-23>`.
2. Measurements with CLI 2.1.301 where a section says so: headless runs in an empty scratch folder with the variables
   of section 1.2 removed. Most send only `initialize`, so no model is used; those that needed a prompt say so. The
   checks of `--session-id X` and `--resume X` on their own (1.1) ran signed in, with the usual config directory. Those
   of forks, of `--session-id` together with `--resume`, of the failed starts' `result` ids and of an early
   `rename_session` (1.1, 4.4) ran without sign-in: a scratch `CLAUDE_CONFIG_DIR` and no variable whose name contains
   `TOKEN`, `API_KEY` or `OAUTH`. Such a run writes nothing before its `initialize` response, where a signed-in run
   usually first writes a `system/ui_invalidate` line with the session id (1.1; three 2.1.302 probe recordings lack it,
   5.2, so it is no sign-in signal). The fork probe without sign-in forked a session that
   had no title line, so its missing `system/session_title_changed` (1.1) says nothing about sign-in.
3. `@anthropic-ai/claude-agent-sdk` 0.3.300: `sdk.d.ts` (types) and `sdk.mjs` (what the SDK actually writes and reads);
   0.3.306's `sdk.d.ts` for the MCP types of 4.4 and 4.5.
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

then appends optional flags in this order (SDK 0.3.300; only those Tandem cares about are listed):

| Flag | When the SDK adds it | Tandem |
|---|---|---|
| `--thinking=adaptive` / `--thinking=disabled` / `--max-thinking-tokens N` | `thinking` option | optional. `--thinking=adaptive` is accepted and changed nothing against the default (`think-adaptive.ndjson:7`, 2.1.302: `thinking_display:"updates"`, empty thinking text, as `think-default.ndjson:7`) |
| `--thinking-display <summarized\|omitted\|highlights>` | not in the SDK; hidden from `--help` (2.1.302) | yes, always `summarized` (`SessionConfig.argv()`); section 6.3. `summarized` streams the thinking summary as text in `thinking_delta` frames and in the completed block (`think-display-summarized.ndjson:11,34`). `highlights` is accepted (`message_start` says `highlights`, `think-display-highlights.ndjson:8`) but gave one empty `thinking_delta`, no `system/thinking_tokens` line and an empty block while 79 thinking tokens were used (`:10,12,18`): unusable over stdio on the probing account. `omitted` was tried only through `set_max_thinking_tokens` (section 4.4). Without the flag the display is `updates`: empty text, token estimates only (`think-default.ndjson:10,16`) |
| `--effort <low\|medium\|high\|xhigh\|max>` | `effort` option | yes, picked when a session is created |
| `--max-turns N`, `--max-budget-usd X` | limits | no |
| `--model <id>` | `model` option | yes, user's pick |
| `--permission-prompt-tool=stdio` | a `canUseTool` callback is set | **yes, always** |
| `--permission-prompt-tool <mcp tool>` | `permissionPromptToolName` (mutually exclusive with the above) | no |
| `--permission-prompts <host\|none>` | option | no (default `host`) |
| `--continue` / `--resume <session-id>` | resume options | `--resume` for an existing session (section 8); no `--continue` |
| `--allowedTools a,b` / `--disallowedTools a,b` | options | `--disallowedTools Edit,Write,MultiEdit,NotebookEdit` (hard rule 4). `MultiEdit` no longer exists in 2.1.302 (in no `system/init.tools`, `tools-edit.ndjson:6`); naming it is harmless |
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
  --include-partial-messages --thinking-display summarized --permission-prompt-tool=stdio \
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
`SessionSpawner.configFor`):
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
  `system/ui_invalidate` at start (in 26 of the 29 probe recordings, in every older signed-in fixture and in a run with
  `--session-id` and no prompt; `think-default`, `think-adaptive` and `think-opus` have none, 5.2), and
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
| `CLAUDE_CODE_SDK_READS_SESSION_STATE` | `1` unless set (any case) | makes the CLI emit `system/session_state_changed` with `sdk_host_only:true` (`misc-state-sdk.ndjson:3`, 2.1.302; section 5.2). `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` emits the same lines without that flag (`misc-state-emit.ndjson:3`); with neither, none (`misc-state-none`) |
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
- Binary: the SDK (0.3.300) uses `pathToClaudeCodeExecutable` or its own bundled native package
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

The SDK (0.3.300) queues user messages until the `initialize` request is written (`writeAfterInitialize`); it does not wait for the response before writing. Default init timeout in the SDK is 60 s. Tandem writes `initialize` first too (4.2).

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

The tables follow `sdk.d.ts` 0.3.300. Recorded on stdout with CLI 2.1.300 to 2.1.306: `system`, `assistant`, `user`,
`stream_event`, `result`, `rate_limit_event`, `control_request`, `control_response`, and the undocumented
`command_lifecycle`, `active_goal` and `autocompact_state` (section 10). No fixture has the other types.

---

## 3. User messages (stdin)

Exact line the SDK (0.3.300) writes for a string prompt:

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

Tandem sends this shape for attachments (`HostMessages.userMessage`): the text block, left out when blank, then one image block per attachment; the CLI does not echo the frame (`user-image.ndjson`), its on-disk line is `transcripts/user-image.jsonl`.

Fixture `user-image` (CLI 2.1.306) sends exactly this with a 2 × 2 PNG. The transcript's user line keeps both blocks, the
image's `data` verbatim, under the uuid the host sent; the CLI adds an `isMeta` user line after it naming where it cached the
image, which the reader skips as it skips every `isMeta` line (section 11).

Slash commands are sent as plain text (`"/compact"`) unless `client_composed:true`. A built-in the CLI answers itself
(`/mcp`, `/model`, `/help` in 2.1.306) runs no model turn: the reply is one synthetic `assistant` line (6.1,
`mcp-slash.ndjson:5`), and the CLI does not echo the typed line. A project command (`/hello` from `.claude/commands/hello.md`)
is a normal turn (`mcp-slash.ndjson:22-34`).
Messages sent while a turn is running are queued and may be folded into the running turn; `result.user_message_uuids` lists which sends a turn consumed.

**Shell escapes (`! command`, bash mode).** Tandem runs the command itself and sends what the terminal's own `!` sends: `content` is a
string, not a block array, and every frame carries `client_composed:true` (`HostMessages.bashInput`, `bashOutput`, `bashContext`):

```json
{"type":"user","session_id":"","parent_tool_use_id":null,"uuid":"…","message":{"role":"user","content":"<bash-input>echo bash-mode-probe && printf err >&2</bash-input>"},"client_composed":true,"shouldQuery":false}
{"type":"user","session_id":"","parent_tool_use_id":null,"uuid":"…","message":{"role":"user","content":"<bash-stdout>bash-mode-probe\nerr</bash-stdout><bash-stderr></bash-stderr>"},"client_composed":true}
{"type":"user","session_id":"","parent_tool_use_id":null,"uuid":"…","message":{"role":"user","content":"<bash-input>CMD</bash-input>\n<bash-stdout>OUT</bash-stdout><bash-stderr></bash-stderr>"},"client_composed":true,"shouldQuery":false}
```

| Frame | When | Recorded |
|---|---|---|
| `bashInput` (`shouldQuery:false`) | the command, replies on | `bash-mode-respond.ndjson:3-7`: lifecycle, `init`, a zero-turn `result` (`num_turns:0`, `result:""`, `duration_api_ms:0`) naming its uuid (`:6`) |
| `bashOutput` (no `shouldQuery`) | the output, replies on, sent only once that zero-turn result is in | `bash-mode-respond.ndjson:8-11`: a normal turn, `status requesting` naming its uuid (`:11`), its `result` (`:41`) |
| `bashContext` (`shouldQuery:false`) | command and output, replies off | **unrecorded** as one host frame: the probe sent two quiet frames back to back, which the CLI queued together (`bash-mode.ndjson:3-6`), answered with one zero-turn `result` naming both (`:8`) and merged into this content (`transcripts/bash-mode.jsonl:5`) |

stderr is merged into stdout in order, so `<bash-stderr>` is empty, as in the 2.1.306 terminal; Tandem's note for a stopped or capped
command (`Stopped after 3.0s`, `Killed after 10 minutes`) is the last line of `<bash-stdout>`. The CLI echoes none of these frames.
The CLI batches the sends queued before it starts processing, so Tandem keeps the recorded order: the output frame waits for the
command frame's zero-turn result, and an escape sent behind a prompt waits for that prompt's result. Two quiet frames can still go
out back to back (a context-only escape and the escape held behind the same prompt), the recorded shape of `bash-mode.ndjson:3-8`.
Nothing holds a prompt: one sent before a context-only escape's zero-turn result, or one queued behind an escape while the process
starts, goes out right after the quiet frame, and what the CLI makes of that quiet + querying batch is **unrecorded**. A `result`
whose `user_message_uuids` names only quiet sends ends no turn and draws no footer.

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

The rules come from SDK 0.3.300. Both answers are recorded: a success from 2.1.300 on (`interrupt.ndjson:13`), an error with 2.1.302
(`permission-mode-rejected`, section 8) and 2.1.306 (`mcp-status.ndjson:36-38`, 4.4). The `Unknown subtype` text above is an
example: what a CLI that predates the `mcp_*` subtypes (4.4) answers to one is unrecorded, that error or nothing at all.
Tandem shows the CLI's `error` text verbatim, or, when no answer comes, its own timeout (4.4, MCP requests).

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

Tandem sends (`HostMessages.initialize`):

```json
{"type":"control_request","request_id":"<id>","request":{"subtype":"initialize","hooks":null,"forwardSubagentText":true}}
```

`forwardSubagentText:true` makes the CLI forward a subagent's text replies as `assistant` lines with an `agent_id`
(`agent-forwarded.ndjson:38`; section 5.2, `task_notification`), which Tandem nests under the `Agent` row.

Success `response` payload (`SDKControlInitializeResponse`):

| Field | Type |
|---|---|
| `commands` | `SlashCommand[]` |
| `agents` | `AgentInfo[]` |
| `output_style`, `available_output_styles` | string, string[] |
| `models` | `ModelInfo[]`: `value`, `displayName`, `description`, and per model `resolvedModel` (the wire id an alias or the `default` entry stands for: the footer labels the entry whose `resolvedModel` equals `init.model`, never the `default` entry), `supportsAutoMode` (`auto` is offered only while the shown model has it; `agent-sync.ndjson:2`: true on every model but Haiku 4.5), `supportsEffort` and `supportedEffortLevels` (the effort page; `low` to `max`, no `xhigh` on Opus 4.6 and Sonnet 4.6). Decoded leniently in `Reducer.modelOption`: an entry without a string `value` is skipped, a missing `displayName` reads as the `value`, a missing flag reads false, and the levels count only with `supportsEffort` true |
| `account` | `AccountInfo` (display only; never store) |
| `claude_code_version` | string (absent on older CLIs) |
| `fast_mode_state`, `fast_mode_disabled_reason` | optional |
| `hooks_applied`, `plugins_applied`, `sdk_mcp_manifests_parked` | optional |

`commands` is the slash-command catalogue (2.1.306, `mcp-slash.ndjson:1`: 73 entries): `{name, description, argumentHint,
builtin?: true, aliases?: string[]}`, in the same order and with the same names as `init.slash_commands` (5.1). 55 of the 73
are `builtin:true`, some with `aliases` (`clear: ["reset","new"]`, `usage: ["cost","stats"]`); a project command omits both
and ends its description with ` (project)` (`{"name":"hello","description":"Says hello from a project command (project)","argumentHint":""}`).
Tandem decodes it as `InitializeResponse` (`ControlPayloads.initialize`; an entry without a string `name` is skipped) and
builds its `/` palette from it. The redactor empties the list in the other fixtures (`mcp-status.ndjson:1` has
`"commands":[]`), so only `mcp-slash` shows real entries.

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
| `tool_name`, `input`, `tool_use_id` | always. `input` can be `{}`: `ExitPlanMode` under Tandem's argv, where the plan-file `Write` is disallowed (`plan-disallowed.ndjson:173`, 2.1.302) |
| `permission_suggestions` | `PermissionUpdate[]` for "always allow" buttons. Absent on `AskUserQuestion` and `ExitPlanMode` requests (`ask-single.ndjson:22`, `plan-approve.ndjson:88`) |
| `blocked_path` | path that triggered the ask |
| `decision_reason`, `decision_reason_type` | `rule\|mode\|subcommandResults\|permissionPromptTool\|hook\|asyncAgent\|sandboxOverride\|workingDir\|safetyCheck\|classifier\|other`. Recorded: `decision_reason_type:"subcommandResults"` on a Bash whose sub-command was not auto-allowed, with a suggestion naming only that sub-command (`tools-bash.ndjson:20`); no 2.1.302 fixture has `decision_reason` |
| `classifier_approvable` | `false` = a safety check needs a human |
| `suppress_always_allow_rule` | hide "don't ask again" |
| `default_to_no` | do not preselect Approve |
| `requires_user_interaction` | no one-tap Approve/Deny. `true` on `AskUserQuestion` and `ExitPlanMode` (`ask-single.ndjson:22`, `plan-disallowed.ndjson:173`); absent on Bash prompts (`tools-bash.ndjson:20`) |
| `matched_ask_rule` | `{source, tool_name, rule_content?}` |
| `title`, `display_name`, `description` | UI strings |
| `agent_id` | subagent origin: a prompt from inside an `Agent` subagent is a normal request plus `agent_id` = the task's `task_id` (`agent-sync.ndjson:43`; section 5.2) |
| `mcp_server` | `{name, source}` on an MCP tool's request, beside `server_policy_ask_only:true` (2.1.306, `mcp-tool-call.ndjson:35`: `{"name":"echo","source":"dynamic"}`) |

Tandem decodes `requires_user_interaction` (absent or mistyped: `false`), `decision_reason_type` and `agent_id` on
`CanUseTool`; `agent_id` gives the card its `From the agent "<description>"` line.

An MCP tool's request (2.1.306, `mcp-tool-call.ndjson:35`, the same keys at `:60`) has exactly `subtype, tool_name,
mcp_server, display_name, input, permission_suggestions, server_policy_ask_only, tool_use_id`: no `decision_reason_type`,
`description`, `blocked_path` or `requires_user_interaction`, and its one suggestion allows the whole tool
(`{"type":"addRules","rules":[{"toolName":"mcp__echo__echo"}],"behavior":"allow","destination":"localSettings"}`). Tandem
decodes `InboundRequest.CanUseTool.mcpServer` (`McpServerRef{name, source}`; null without a string `name`) and
`serverPolicyAskOnly` (absent: `false`).

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
- Fixture order (`permission`, 2.1.300): `assistant` with the `tool_use` block, then `content_block_stop`, then the
  `can_use_tool` request (its `tool_use_id` equals the block's `id`), then `message_delta`/`message_stop`. In the
  2.1.302 recordings the request arrives in either order relative to `content_block_stop`: before it for
  AskUserQuestion, ExitPlanMode and WebSearch (`ask-single.ndjson:21-23`, `plan-approve.ndjson:87-89`,
  `misc-web.ndjson:73-74`), after it for Bash and WebFetch (`tools-bash.ndjson:18-20`, `misc-web.ndjson:29-31`), so
  a decoder must not key on either. When the host answers at once the `user` line with the tool result
  precedes `message_delta`/`message_stop` (`ask-deny.ndjson:24,25`): accept a tool result for a message that is not
  closed yet. After the answer, a `user` line carries the `tool_result` (`is_error:true` with the deny message on deny)
  plus `tool_result_meta[{id, permission_decision:{decision:"accept"|"reject", source, reason_type?}}]` (section 6.2).
- Read-only commands (e.g. `echo`) are auto-approved and never reach the host.
- If the turn is interrupted while a prompt is open, the CLI sends `control_cancel_request` for it: close the dialog, send nothing.
- Auto-denials without a prompt arrive as `system/permission_denied` (section 5).
- `AskUserQuestion` (2.1.302, fixtures `ask-single`, `ask-multi`, `ask-multi-array`, `ask-other`, `ask-response` and
  `ask-deny`): the request arrives in `default` mode too, with exactly
  `subtype, tool_name, display_name, input, tool_use_id, requires_user_interaction` (`ask-single.ndjson:22`): it is how
  the CLI asks the host to render the question. `input.questions[]` is `{question, header, options[{label,
  description}], multiSelect}`; the `tool_use` block has the same input in another key order, so compare by value.
  Answer with `allow` and `updatedInput` = the input plus `answers`, keyed by the exact question text: a label, the
  `", "`-joined labels of a multi-select (what the CLI's own UI sends; an array is accepted too,
  `ask-multi-array.ndjson:30`) or free text (`ask-other.ndjson:26`); or `updatedInput.response` with a free-text reply
  and no `answers` (`ask-response.ndjson:24`). The `tool_result` text differs per case ("Your questions have been
  answered: ...", "The user answered: ...", "The user responded: ...") and `tool_use_result` is the `updatedInput` sent
  (`ask-single.ndjson:26`). `deny` with a `message` and no `interrupt` cancels the question only: an `is_error` result
  holding the message, and the turn goes on (`ask-deny.ndjson:24,36`); `result.permission_denials` lists the call.
- `ExitPlanMode` (2.1.302, fixtures `plan-approve`, `plan-deny` and `plan-disallowed`): `input` is `{plan, planFilePath}`
  once the plan file was written
  (`plan-approve.ndjson:88`), `{}` when `Write` is disallowed, as under Tandem's argv (`plan-disallowed.ndjson:173`;
  the model's plan-file `Write` then fails with a `tool_use_error`, no prompt, `:103`). Never `permission_suggestions`,
  so the host builds the mode switch itself: `allow` with `updatedInput` = the input and
  `updatedPermissions:[{"type":"setMode","mode":"acceptEdits","destination":"session"}]` switches the live session, and
  the CLI confirms with `system/status {status:null, permissionMode:"acceptEdits"}` before the tool result
  (`plan-approve.ndjson:90,91`); the next turn's `init` agrees (`:160`). A plain `allow` leaves plan mode to `default`
  (`plan-disallowed.ndjson:177`, `plan-deny.ndjson:145`). `deny` with the feedback as `message` keeps plan mode: an
  `is_error` result, no `system/status`, and the model revises the plan file and asks again (`plan-deny.ndjson:81,143`).

### 4.4 Host → CLI requests

Request fields follow `sdk.d.ts` 0.3.300. A row that names no fixture or measurement (`list_models`, `get_context_usage`,
`rewind_files`, `stop_task`, `get_settings`) is from the SDK alone: Tandem does not send it and no recording has it.

| subtype | Request fields | Success `response` |
|---|---|---|
| `interrupt` | `cancel_queued?: boolean` | `{still_queued: string[], cancelled?: string[]}` (empty `{}` on CLIs without `interrupt_receipt_v1`); recorded `{"still_queued":[]}` (`interrupt.ndjson:13`, 2.1.300) |
| `set_model` | `model?: string\|null` (`null`/`"default"` = session default) | none: the success frame has no `response` field (`model-switch.ndjson:21`, 2.1.300), and just before it the CLI emits a replayed `user` line (`isReplay:true`) with `<local-command-stdout>Set model to ...</local-command-stdout>` (`:20`). An `error` answer is unrecorded; what Tandem does with one: section 8, Switch model mid-session |
| `set_permission_mode` | `mode: "default"\|"acceptEdits"\|"bypassPermissions"\|"plan"\|"dontAsk"\|"auto"` | `{"mode": "<wire name>"}`, then a `system/status` line with `status: null` and the new `permissionMode`; the next `init` agrees (fixture `permission-mode`, 2.1.301) |
| `rename_session` | `title: string`, `source: "host"` | none: no `response` field, like `set_model`. A `system/session_title_changed` line (`title`) comes just before it, and the CLI appends a `custom-title` line to the transcript (fixtures `fork`, `fork-origin`) |
| `set_max_thinking_tokens` | `max_thinking_tokens?: number\|null`, `thinking_display?: "summarized"\|"omitted"\|"highlights"\|null` | none: the success frame has no `response` field (`think-summarized.ndjson:25`, 2.1.302). Takes effect on the next turn (below). Not sent by Tandem, which passes `--thinking-display summarized` at spawn (1.1) |
| `list_models` | — | model catalog (`{models: ModelInfo[]}`, inferred shape) |
| `get_context_usage` | `detail?: "summary"\|"full"` | categories `{name, tokens, kind:"used"\|"free"\|"buffer"\|"deferred"}` plus totals |
| `rewind_files` | `user_message_id: string`, `dry_run?: boolean` | rewind result (needs file checkpointing env) |
| `mcp_status`, `mcp_toggle`, `mcp_reconnect`, `mcp_authenticate`, `mcp_set_servers` | see MCP requests, below (2.1.306) | see MCP requests, below |
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

Tandem passes `--effort` at spawn, and an effort picked in the footer of a live session goes as
`{"subtype":"apply_flag_settings","settings":{"effortLevel":<level>}}` (fixture `effort-switch`, CLI 2.1.306: a turn, the
`high` request answered by a success with no `response` field, `:18`, then a second turn). Both of the fixture's turns ran at
`high`, the model's default in that recording (the transcript's `assistant` lines, `transcripts/effort-switch.jsonl:20` and `:29`,
both have `"effort":"high"`), so the fixture records the request and the shapes of its answers, not a change of level; that the
next turn runs at the new level rests on the 2.1.301 probe above (`medium`, then `high`). The footer shows the picked level at once; an `error` answer puts the level shown before it back, with the
notice `Effort not changed: <error>` quoting the CLI's `error`. CLI 2.1.306 takes any `effortLevel` over stdio without
checking it (`"bogus"` too, and with `CLAUDE_CODE_EFFORT_LEVEL` set), so the one refusal the fixture records is a request whose
`settings` is not an object: `` {"subtype":"error","error":"apply_flag_settings requires `settings` to be an object, got string"} ``
(`:19`). A picked effort on a stopped session is passed with `--effort` at the next spawn.

Thinking display on a live session (fixture `think-summarized`, 2.1.302; section 6.3):
`{"subtype":"set_max_thinking_tokens","thinking_display":"summarized"}` sent after `initialize` gets a bare success
(`:25`), and the next turn's `message_start` says `summarized` and streams the summary text (`:30`, `:33-59`, block
`:62`); the turn already running is unchanged. `"highlights"` (`:74`; next `message_start` says `highlights`, `:79`),
`"omitted"` (`:100`) and `null` (`:118`) succeed too, but `omitted` and `null` both read back as `updates` on the next
`message_start` (`:105`, `:123`), so a host cannot tell from the stream that it asked for `omitted`. `"full"` is
rejected: `{"subtype":"error","error":"set_max_thinking_tokens: max_thinking_tokens must be an integer or null and
thinking_display must be \"summarized\", \"omitted\", \"highlights\", or null"}` (`:73`). Not measured:
`max_thinking_tokens` together with `thinking_display`.

From the 2.1.301 binary's strings, not measured: a `CLAUDE_CODE_EFFORT_LEVEL` environment variable overrides the
session's effort, and `apply_flag_settings` then refuses an effort change ("apply_flag_settings: CLAUDE_CODE_EFFORT_LEVEL
overrides effort for this session"); a `maxEffort` setting or an organization's cap also clamps `--effort`. Measured with
2.1.306: over stdio the request succeeds with the variable set (the refusal and an "unrecognized effortLevel" error sit in the
2.1.306 binary's Remote Control path, `bridge_flag_settings`). The variable passes through `cliEnvironment` (1.2), so
the footer's effort, which shows what Tandem passed or picked, can disagree with the level the CLI runs at. The
transcript's `assistant` lines should show the real level (their `effort` followed `apply_flag_settings` in the probe
above); reading it from there is left for later.

#### MCP requests

Recorded with CLI 2.1.306 (fixture `mcp-status`, three local servers: section 10). The CLI manages its MCP servers on
request and announces no change: no `system` line in the MCP fixtures is about a server, so a host learns of a new status
from `mcp_status`, the next turn's `init` (5.1) or the model's own text. The frames Tandem sends (`HostMessages.mcpStatus`,
`mcpToggle`, `mcpReconnect`, `mcpAuthenticate`; the request ids here are examples):

```json
{"type":"control_request","request_id":"m1","request":{"subtype":"mcp_status"}}
{"type":"control_request","request_id":"m2","request":{"subtype":"mcp_toggle","serverName":"echo","enabled":false}}
{"type":"control_request","request_id":"m3","request":{"subtype":"mcp_reconnect","serverName":"broken"}}
{"type":"control_request","request_id":"m4","request":{"subtype":"mcp_authenticate","serverName":"authy"}}
```

`serverName` is the name exactly as `mcp_status` reports it.

| subtype | Answer |
|---|---|
| `mcp_status` | `{"mcpServers":[…]}` (`mcp-status.ndjson:18`). Per entry: `name`, `status`, `config` (`{type:"stdio", command, args}` or `{type:"http", url}`), `scope` and `source` always; `serverInfo{name, version}` and `tools[{name, annotations}]` only when `connected`; `error` only when `failed` (`"Connection closed"`). A `needs-auth` entry has neither `error` nor `tools`, and a tool's description is not forwarded. Sent right after `initialize`, before the first prompt, the two stdio servers are still `pending` (`:2`); the first `init` already has settled states (`:6`). Tandem decodes it with `ControlPayloads.mcpStatus` (`McpServerInfo`; an entry without a string `name` is skipped, an unknown status is kept as text) and never decodes `config.headers` or `config.env`, which can hold secrets |
| `mcp_toggle {serverName, enabled}` | a bare success, no `response` field (`:19`). The next `mcp_status` shows the server `disabled`, without `serverInfo` or `tools` (`:20`), and the next turn's `init` agrees (`:23`) and no longer lists its tool in `init.tools`; `enabled:true` brings it back `connected` at once (`:34`, `:35`). An unknown name: `{"subtype":"error","error":"Server not found: nosuch"}` (`:38`). A toggle of a `failed` or `needs-auth` server is unrecorded |
| `mcp_reconnect {serverName}` | an error with the connection error itself for a failed server, `Connection closed` (`:36`), and `Server status: needs-auth` for a server that needs a sign-in (`:37`): a reconnect starts no sign-in, and the status stays `needs-auth` (`:39`). The success of a reconnect that works is unrecorded |
| `mcp_authenticate {serverName}` | at once, without waiting for the sign-in: `{authUrl, requiresUserAction, callbackExpected, redirectScheme, state, callbackPort}` (`:40`). `authUrl` is an OAuth authorization link with PKCE whose `redirect_uri` is `http://localhost:<callbackPort>/callback`. The CLI opens no browser and writes no `system` line (nothing between `:40` and `:41`), and `mcp_status` right after still says `needs-auth` (`:41`). The callback completing, and whether the status then turns `connected` by itself, are unrecorded (`authy`, section 10, never issues a token). Not in the SDK's typed request union |
| `mcp_set_servers {servers}` | `{added, removed, errors}` (`:42`, `:44`). It replaces only the servers added this way: `{"servers":{}}` removed the added `echo2` and kept the `--mcp-config` servers (`:44`, `:45`), which report the same `source:"dynamic"`, so a host must remember what it added. Tandem decodes the answer (`ControlPayloads.mcpSetServers`) and does not send the request |

Tandem waits 15 s for `mcp_status` and `mcp_toggle` and 30 s for `mcp_reconnect` and `mcp_authenticate`, then shows `The CLI
did not answer within 15 s.` (or `30 s.`). Every `mcp_*` request in the fixture falls between turns: whether the CLI answers
one while a turn runs, or holds it until the `result`, is unrecorded (it does answer `interrupt` mid-turn,
`interrupt.ndjson:13`). For a sign-in Tandem opens `authUrl` in the system browser, only when it is an absolute `http` or
`https` URL with a host (`ControlPayloads.browsableUrl`), then lists the servers every 2 s for up to 3 minutes, until the
server leaves `needs-auth`. None of these requests reaches the on-disk transcript (`transcripts/mcp-status.jsonl` holds only
the three turns). claude.ai connectors, which the documentation describes as servers with `source:"claudeai"` and
`config.type:"claudeai-proxy"`, are in no recording yet.

### 4.5 Other CLI → host requests

| subtype | When | Tandem |
|---|---|---|
| `hook_callback` | host-registered hooks in `initialize` | not used; reply `error` |
| `mcp_message` | `sdkMcpServers` in-process servers | not used (our MCP server is a real socket server via `--mcp-config`) |
| `elicitation` | an MCP server asks the user for input (`sdk.d.ts` 0.3.306, lines 3976-3999; no recording has one) | answered with the error `Unsupported request: elicitation`, and the chat shows the warning `An MCP server asked for input Tandem cannot show yet.` |
| `request_user_dialog` | only for kinds we declared | declare none |

The subtypes come from `sdk.d.ts` 0.3.300; only `can_use_tool` (4.3) is recorded. The SDK leaves some subtypes unanswered
on purpose (those "for the machine serving this session's tools"). Tandem answers every one it does not handle with the
error `Unsupported request: <subtype>`.

---

## 5. `system` messages

All carry `type:"system"`, `subtype`, `uuid`, `session_id`.

### 5.1 `init`

Emitted **at the start of every turn** (not only once). Fixture line (`plain-reply.ndjson`, 2.1.300; truncated):

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
| `cwd`, `tools`, `mcp_servers[{name,status,source?}]` | MCP servers: below |
| `permissionMode` | sync UI |
| `slash_commands`, `terminal_slash_commands`, `skills`, `agents`, `plugins`, `plugin_errors` | command palette: below |
| `apiKeySource` | display only |
| `claude_code_version` | version gating |
| `capabilities` | feature detection; open set |
| `effort`, `betas`, `output_style`, `fast_mode_state` | display |

Extra fixture fields not in types: `product_feedback_disabled`, `analytics_disabled`, `scratchpad_path`, `messaging_socket_path`, `per_turn_effort_active`, `startup_timing{...}`. Ignore unknown fields.

MCP servers and commands (2.1.306). `mcp_servers` is the thin list `[{name, status, source}]` (`mcp-status.ndjson:6`): no
error, config or tools, which come from `mcp_status` (4.4). Recorded statuses: `connected`, `failed` and `needs-auth` (`:6`),
`disabled` after a toggle (`:23`), and `pending` only in an `mcp_status` answer sent before the first turn (`:2`); the SDK
(0.3.306) types the same five. Tandem keeps any other value as text (`McpStatus.OTHER`). It merges each `init`'s list into
the servers it knows: a server whose status changed loses its error, tools and version until the next `mcp_status`, and a
server the `init` leaves out is dropped, except a claude.ai connector (`source:"claudeai"`), which stays until the next
`mcp_status`: whether `init` lists connectors is unrecorded. `slash_commands` lists the command names (73 in `mcp-slash.ndjson:4`, the names of the
initialize `commands`, 4.2) and `terminal_slash_commands` the ones that need the terminal
(`["doctor","color","focus","reload-plugins"]`, `:4`). In the other fixtures the redactor empties these lists.

`effort` is typed in the SDK but missing from every recorded `init` (2.1.300 and 2.1.301), which carry only
`per_turn_effort_active: true`. So Tandem shows the effort it passed with `--effort`, which for a session loaded from
disk is the last `assistant` line's `effort` in its transcript (section 11).

### 5.2 Other typed subtypes

A row that cites a fixture was recorded (versions in Sources); the others come from `sdk.d.ts` 0.3.300 only, and no fixture
has `compact_boundary`, `api_retry`, `model_refusal_fallback`, `model_refusal_no_fallback`, `permission_denied`,
`notification`, `informational`, `local_command_output`, `hook_*`, `background_tasks_changed` or `commands_changed`.

| subtype | Fields | Tandem action |
|---|---|---|
| `status` | `status: "requesting"\|"compacting"\|null`, `permissionMode?`, `compact_result?: "success"\|"failed"`, `compact_error?`. `{status:null, permissionMode}` also after an allowed `ExitPlanMode`, before its tool result (`plan-approve.ndjson:90`; section 4.3) | spinner |
| `compact_boundary` | `compact_metadata{trigger:"manual"\|"auto", pre_tokens, post_tokens?, duration_ms?, preserved_segment?, preserved_messages?}` | divider in chat |
| `api_retry` | `attempt, max_retries, retry_delay_ms, error_status: number\|null, error: SDKAssistantMessageError, no_response?{waited_ms,retry_wait_ms}` | **show** "retrying (2/10) in 4s" |
| `model_refusal_fallback` | see 5.3 | **show** |
| `model_refusal_no_fallback` | see 5.3 | **show** |
| `permission_denied` | `tool_name, tool_use_id, agent_id?, decision_reason_type?, decision_reason?` | show denial |
| `notification` | `key, text, priority:"low"\|"medium"\|"high"\|"immediate", color?, timeout_ms?` | IDE notification/inline |
| `informational` | `content, level:"info"\|"notice"\|"suggestion"\|"warning", tool_use_id?, prevent_continuation?` | inline note |
| `session_state_changed` | `state:"idle"\|"running"\|"requires_action"`, `sdk_host_only?: true`. Only with `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` (`misc-state-emit.ndjson:3,18`) or `CLAUDE_CODE_SDK_READS_SESSION_STATE=1`, which adds `sdk_host_only:true` (`misc-state-sdk.ndjson:3`); with neither, never (`misc-state-none`, 2.1.302). `running` before `system/init`; `requires_action` right before each `can_use_tool` and `running` right after the answer (`misc-state-action.ndjson:20,21,24`); `idle` after `result` and `command_lifecycle completed`, as the turn's last line | `idle` is the authoritative turn-over signal |
| `local_command_output` | output of local slash commands (SDK only) | not used for `/mcp`, `/model` or `/help` in 2.1.306: their replies came as the `assistant` lines of 6.1 (`mcp-slash.ndjson:5`, `:11`, `:17`), and no fixture has this subtype. Not decoded: an unknown line |
| `hook_started/progress/response` | hook lifecycle | ignore (or debug) |
| `task_started` | `task_id, run_id, tool_use_id, description, is_backgrounded, task_type:"local_agent"\|"local_bash"`; a subagent adds `subagent_type, spawn_depth, prompt` (`agent-sync.ndjson:37`, 2.1.302). `task_id` is the `agent_id` of every subagent line and `tool_use_result.agentId`; `tool_use_id` is the `Agent` block's id (the block is named `Agent` while `init.tools` lists `Task`, `agent-sync.ndjson:35,6`). A plain foreground `Bash` is a task too: `task_type:"local_bash"`, `description` = the call's description, no `subagent_type` (`agent-bash-task.ndjson:21`, a 6 s command), so key subagent handling on `task_type` or `subagent_type` | task list |
| `task_progress` | `task_id, run_id, tool_use_id, description:"Running <command>", subagent_type, usage{total_tokens, tool_uses, duration_ms}, last_tool_name`; once per subagent tool call, before that call's `assistant` line (`agent-sync.ndjson:41`); none for a Bash task | task list |
| `task_updated` | `task_id, run_id, patch{status, end_time}`; no `tool_use_id`, correlate by `task_id` (`agent-sync.ndjson:48`) | task list |
| `task_notification` | `task_id, run_id, tool_use_id, status:"completed", output_file, summary, usage` (`agent-sync.ndjson:49`; `summary` is the subagent's final text, which is not streamed as an `assistant` line unless `initialize` had `forwardSubagentText:true`, `agent-forwarded.ndjson:38`). A Bash task gets one too, with `output_file:""`, `summary` = its description and no `usage` (`agent-bash-task.ndjson:22`) | task list |
| `background_tasks_changed` | background tasks; not recorded | task list |
| `thinking_tokens` | `estimated_tokens, estimated_tokens_delta, user_message_uuid` (`think-default.ndjson:11`): a running estimate while a thinking block streams, interleaved with its `thinking_delta` frames in the `updates` and `summarized` displays, none in `highlights` (section 6.3). It overshoots the final `message_delta.usage.output_tokens_details.thinking_tokens` | token counter |
| `ui_invalidate` | `event:"ui.render"`; usually the first line of a signed-in run, before `init` (26 of the 29 probe recordings, e.g. `tools-bash.ndjson:1`; section 10). Absent from `think-default`, `think-adaptive` and `think-opus` (2.1.302), which start with the `initialize` response, so key nothing on it | ignore |
| `commands_changed` | `commands` | refresh palette |
| `files_persisted`, `memory_recall`, `elicitation_complete`, `control_request_progress`, `plugin_install`, `mirror_error`, `worker_shutting_down` | | ignore or log type |

Tandem decodes `thinking_tokens` (`ThinkingTokens{estimatedTokens, estimatedTokensDelta?, userMessageUuid?}`; a line
without an integer `estimated_tokens` stays unknown), `task_started` (`TaskStarted{taskId, toolUseId?, description?,
subagentType?, taskType?, isBackgrounded}`), `task_progress` (`TaskProgress{taskId, toolUseId?, description?,
usage{totalTokens?, toolUses?, durationMs?}?, lastToolName?}`), `task_updated` (`TaskUpdated{taskId, status?}`, the
status read from `patch.status`) and `task_notification` (`TaskNotification{taskId, toolUseId?, status?, summary?,
usage?}`) as typed lines; every field but `task_id` is optional and a mistyped one reads as absent. The thinking row
counts tokens from `thinking_tokens`; the `Agent` row's sub-line is `N tool uses · T tokens · D` from the latest
`task_progress` while the task runs, and the working row names a running subagent's step from its `description`.

`SDKAssistantMessageError` = `authentication_failed | oauth_org_not_allowed | account_on_hold | verification_required | billing_error | rate_limit | overloaded | invalid_request | model_not_found | server_error | unknown | max_output_tokens | cloud_credential_error`.

### 5.3 Model fallback signals (critical)

There are three different ways the model can change under the user. Surface all of them. No fixture has a refusal or an
overload fallback: A to C come from `sdk.d.ts` 0.3.300; `model_not_found` is recorded (`invalid-model`, 2.1.302, section 7).

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

Shape (`plain-reply.ndjson:10`, 2.1.300; trimmed):

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
- 2.1.302 additions: `thinking_duration_ms` and `thinking_display` on the line that completes a thinking block
  (section 6.3); `caller:{type:"direct"}` on every `tool_use` block (`ask-single.ndjson:21`); `wire_tool_inputs`
  (section 10); on subagent lines `agent_id`, `subagent_type` and `task_description` beside `parent_tool_use_id`
  (section 6.2).
- Tandem reads `thinking_duration_ms` (the thinking row's `Thought for Ns`), `thinking_display`, `agent_id`,
  `subagent_type`, `task_description` and `timestamp` (ISO-8601, as epoch milliseconds) on `assistant` lines, and
  `agent_id`, `subagent_type`, `task_description` and `timestamp` on `user` lines (6.2). On disk the names are
  `thinkingDurationMs`, `thinkingDisplay` and `timestamp` (section 11); `agentId`, `subagentType` and `taskDescription`
  are read as fallbacks too, though the main `<id>.jsonl` carries `agentId` only inside `toolUseResult`
  (`agent-sync.jsonl`, `agent-forwarded.jsonl`).
- A local command's reply (2.1.306): when the CLI answers a typed `/mcp`, `/model` or `/help` itself (section 3), it writes
  one `assistant` line with `message.model:"<synthetic>"`, zero usage and three top-level keys: `local_command_run {command,
  args}` (`mcp-slash.ndjson:5`, `:11`), or instead `local_command_outcome {kind}` for a command it cannot run headless
  (`{"kind":"unavailable_headless"}`, `/help`, `:17`), and `local_command_source`, the text wrapped in
  `<local-command-stdout>…</local-command-stdout>` (`:5`). No `system/status requesting`, no `stream_event`. Its `result`
  carries `local_command:"mcp"` (none for `/help`, `:18`), `num_turns:0` and `duration_api_ms:0` (`:6`). Tandem shows a line
  with any of the three keys as an INFO notice under the prompt, never as a reply, the text unwrapped, and that `result`
  ends the turn without a `Worked for` footer (section 7). `/mcp`'s text ends with `` Use `/mcp` in the terminal for
  details.``, which Tandem replaces with a link to its MCP panel.
- `tool_use_meta` (2.1.306) on the line of an MCP tool call: `[{id, display_name, server_display_name, tool_name}]`
  (`mcp-tool-call.ndjson:34`: `{"id":…,"display_name":"Echo","server_display_name":"echo","tool_name":"echo"}`). Decoded as
  `Assistant.toolUseMeta` (an entry without a string `id` is skipped), not shown yet: the row names an
  `mcp__<server>__<tool>` call `<server>: <tool>`. MCP tools are deferred: before the call the model runs `ToolSearch` with
  `select:mcp__echo__echo` (`:22`).

### 6.2 `user` (tool results)

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01...","content":"...","is_error":false}]},
 "parent_tool_use_id":null,"session_id":"<sid>","uuid":"<uuid>","timestamp":"2026-10-18T20:07:10.236Z",
 "tool_use_result":{...},"tool_result_meta":[{"id":"toolu_01...","permission_decision":{"decision":"accept","source":"user_temporary"}}]}
```

- `tool_use_result` is the tool's structured output: a JSON object on success, per-tool shape, and on any failure the
  **string** `"Error: " + content` (`tools-bash.ndjson:39`, `tools-read-unavailable.ndjson:49`, `ask-deny.ndjson:24`,
  `plan-deny.ndjson:81`, 2.1.302), so decode it as a `JsonElement`. Absent on a subagent's successful tool results
  (`agent-sync.ndjson:47`).
- `is_error` is absent on most successes (Read, Glob, Grep, Edit, Write, Agent, ToolSearch, AskUserQuestion:
  `tools-read.ndjson:21`), `false` on a Bash success (`tools-bash.ndjson:23`), `true` on errors (`:39`). `content` is a
  string, or a block array: `image` for a Read of an image (`misc-image.ndjson:26`), `tool_reference` for `ToolSearch`
  (`misc-web.ndjson:17`), `text` for `Agent` (`agent-sync.ndjson:50`).
- `tool_result_meta`: `[{id: <tool_use id>, permission_decision:{decision:"accept"|"reject", source, reason_type?},
  non_execution_kind?}]`. Recorded: `source:"config"` with `reason_type:"mode"` for a mode auto-accept
  (`tools-read.ndjson:21`), `"subcommandResults"` for read-only Bash (`agent-sync.ndjson:47`), `"other"` for the plan
  file's Write in plan mode (`plan-approve.ndjson:73`), and no `reason_type` for ToolSearch and Agent;
  `source:"user_temporary"` after a host allow (`tools-bash.ndjson:23`); `{decision:"reject", source:"user_reject"}`
  with `non_execution_kind:"permission-rule"` after a host deny (`ask-deny.ndjson:24`). Missing on an unknown-tool
  error (`tools-read-unavailable.ndjson:49`).
- Subagent lines, `user` and `assistant`, carry `parent_tool_use_id` (the `Agent` block's id), `agent_id` (the task's
  `task_id`, section 5.2), `subagent_type` and `task_description` (`agent-sync.ndjson:38,47`); the first is a `user`
  text line echoing the subagent's prompt (`:38`). No `stream_event` frames come from a subagent.
- Per-tool `tool_use_result` on success, the shapes Tandem renders, recorded with 2.1.302 at the fixture lines of the last
  column; each cited line also holds the call's `tool_result.content` text, and the `assistant` line with its `tool_use`
  block, before it, the call's input:

| Tool | `tool_use_result` | Fixture |
|---|---|---|
| Read | `{type:"text", file:{filePath, content, numLines, startLine, totalLines}}`; an image: `{type:"image", file:{base64, type, originalSize, dimensions{originalWidth, originalHeight, displayWidth, displayHeight}}}` | `tools-read.ndjson:21`, `misc-image.ndjson:26` |
| Edit | `{filePath, oldString, newString, originalFile, structuredPatch[{oldStart, oldLines, newStart, newLines, lines}], userModified, replaceAll}` | `tools-edit.ndjson:44` |
| Write | `{type:"create"\|"update", filePath, content, structuredPatch, originalFile, userModified}` | `tools-edit.ndjson:61`, `plan-deny.ndjson:141` |
| Bash | `{stdout, stderr:"", interrupted, isImage, noOutputExpected}`: stderr is merged into `stdout`, no exit code; a failure is the string `"Error: Exit code 3\n..."` | `tools-bash.ndjson:23,39` |
| Agent | `{status, prompt, agentId, agentType, content[{type:"text", text}], totalDurationMs, totalTokens, totalToolUseCount, usage, toolStats, canContinueAgent}`; render `content[].text`, not the hand-back frame in `tool_result.content` | `agent-sync.ndjson:50` |
| AskUserQuestion | `{questions, answers, response?}` (section 4.3) | `ask-single.ndjson:26` |
| ExitPlanMode | `{plan, isAgent, filePath}`; `plan:null` when `Write` was disallowed | `plan-approve.ndjson:91`, `plan-disallowed.ndjson:178` |

- An MCP tool's result (2.1.306, `mcp-tool-call.ndjson:39`): the `tool_result` block's `content` is what the server
  returned, here the JSON string `{"result":"hello tandem"}` because the `echo` server (section 10) declares an output schema (a server
  without one would return text blocks: unrecorded), and the line's `tool_use_result` is `{content, _meta,
  structuredContent}`, with `_meta:{"io.modelcontextprotocol/serverInfo":{name, version}}` and `structuredContent` the
  parsed object. A denied MCP call (`:62`) is a `tool_result` with `is_error:true` and the deny message as `content`,
  `tool_use_result:"Error: <message>"`, and `tool_result_meta:[{id, non_execution_kind:"permission-rule",
  permission_decision:{decision:"reject", source:"user_reject"}}]`; the turn's `result.permission_denials` lists the call
  (`:73`). On disk `toolUseResult` is the content string only and the rest moves to `mcpMeta:{_meta, structuredContent}`
  (`transcripts/mcp-tool-call.jsonl:32`).
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
| `estimated_tokens` | on `thinking_delta` frames, beside `thinking`: an integer on the first deltas of an `updates` block, `null` on its last one and on every `summarized` delta (below) |
| `message_start.message.usage` | `input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`, `output_tokens`; Tandem decodes the sum of the three input counts (`plain-reply.ndjson:7`) |
| `message_delta.usage` | the request's `output_tokens` and `output_tokens_details.thinking_tokens` (`plain-reply.ndjson:12`); Tandem decodes both, and the working row's ` · N tokens` adds up each request's `output_tokens` (plus the running `thinking_tokens` estimate until the request's `message_delta`) |
| `thinking_display` | wrapper-level `"updates"\|"summarized"\|"highlights"` on `message_start`, the block's `content_block_start` and every `thinking_delta` frame (`think-default.ndjson:7,8,10`; `think-display-summarized.ndjson:8,11`), not on `signature_delta` or `content_block_stop` (`think-default.ndjson:15,17`; `think-display-highlights.ndjson:11`), so no decoder keys block membership on its presence; and on the `assistant` line that completes the block, with `thinking_duration_ms` (`think-default.ndjson:16`: 2143 ms). The SDK types it only on `set_max_thinking_tokens`; `"full"` never appears over stdio (section 4.4) |

Thinking blocks (2.1.302, fixtures `think-default`, `think-adaptive`, `think-opus`, `think-display-summarized`,
`think-display-highlights` and `think-summarized`). In the default display, `updates`
(without `--thinking-display`; Opus the same, `think-opus`), every `thinking_delta` has `thinking:""` and a non-standard sibling `estimated_tokens`, an
integer on the first deltas and `null` on the last one before `signature_delta` (`think-default.ndjson:10,12,13`); the
completed block is `{type:"thinking", thinking:"", signature}` (`:16`), and the thinking shows only as
`system/thinking_tokens` lines (section 5.2) and `message_delta.usage.output_tokens_details.thinking_tokens`. With
`summarized` (`--thinking-display summarized`, section 1.1, or `set_max_thinking_tokens`, section 4.4)
`thinking_delta.thinking` is a text chunk and `estimated_tokens` is `null` (`think-display-summarized.ndjson:11`), each
chunk preceded by a `thinking_tokens` line (`:10`), and the completed block holds the whole summary (`:34`). Tandem
always passes `--thinking-display summarized` (section 1.1). With `highlights` the block streams one empty delta and no
`thinking_tokens` line (`think-display-highlights.ndjson:10`). So
render a thinking block by appending `thinking_delta.thinking` and replacing it with the completed block; an empty
string with a signature is normal, not a parse error. The transcript on disk keeps what was streamed (section 11), so a
session recorded in `updates` has no summaries to load.

### 6.4 Correlating partial and complete messages

Fixture order for one text block (`plain-reply.ndjson:7-15`, 2.1.300):

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
8. A `control_request` can arrive before the block's `content_block_stop`, and a `user` tool result before the parent
   message's `message_delta`/`message_stop` (`ask-single.ndjson:21-23`, `ask-deny.ndjson:24,25`; section 4.3);
   `task_started` and the subagent's first line also arrive before the `Agent` call's message closes
   (`agent-sync.ndjson:35-40`). Never make a tool's card wait for the message to close.

---

## 7. `result`

Exactly one per turn, after that turn's `assistant`/`user`/`stream_event` lines; some `system` lines (e.g. `post_turn_summary`, `session_state_changed`) can still follow. Shape (as `plain-reply.ndjson:15`, 2.1.300; trimmed, values illustrative):

```json
{"type":"result","subtype":"success","is_error":false,"result":"pong","num_turns":1,"duration_ms":1456,"duration_api_ms":1961,
 "stop_reason":"end_turn","total_cost_usd":0.0092643,"usage":{...},"modelUsage":{"claude-sonnet-5-5":{"inputTokens":2,"outputTokens":4,"costUSD":0.0091409,"contextWindow":1000000,...}},
 "permission_denials":[],"terminal_reason":"completed","session_id":"<sid>","uuid":"<uuid>","result_index":0,"queued_turn_count":0}
```

`modelUsage[<model>].contextWindow` is the context ring's window (`Result.contextWindows`, `misc-image.ndjson:66`: 1000000): the active model's, else the largest listed; a `result` without any keeps the ring as it was. The ring's tokens are the last main-thread `message_start` input sum (section 6.3).

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
Tandem reads `duration_ms` (the footer's `Worked for <duration>`), `duration_api_ms`, `num_turns`, `usage.output_tokens`
and `permission_denials` (`PermissionDenial{toolName, toolUseId?}`; an entry without a string `tool_name` is skipped):
each optional, a mistyped one absent.

API error (fixture `invalid-model`, 2.1.302, run with `--model claude-nonexistent-0`): no stream frames; the CLI writes an
`assistant` line itself, `message.model:"<synthetic>"`, with `error:"model_not_found"`, `is_api_error_message:true`,
`api_error_status:404` and the error text as its one text block, then
`result{subtype:"success", is_error:true, terminal_reason:"api_error", stop_reason:"stop_sequence", result:<that text>}`.
The subtype says nothing here: Tandem names the failure by `terminal_reason`, and the rejected model by the model picked
or reported, never `<synthetic>`.

A local command's turn (6.1; 2.1.306) ends with a `result` that has `num_turns:0`, `duration_api_ms:0` and, for `/mcp` and
`/model`, `local_command` (`mcp-slash.ndjson:6`, `:12`; none for `/help`, `:18`). Tandem ends a turn without a `Worked for`
footer when its `result` has `local_command` or a local-command line (6.1) came during it, as for `/help`.

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
| Switch model mid-session | `control_request{set_model, model}` (4.4; fixture `model-switch`, 2.1.300); next turn's `system/init.model` must equal it. `--model` on resume also works. Tandem shows the picked model at once. An `error` answer is unrecorded (no recording has one); Tandem then puts back what the footer showed before that pick, its own mismatch warning included, unless a later pick replaced it meanwhile, and adds the error notice `The CLI rejected the switch to model <m> (<error>); it stays on model <x>.`: `<x>` is the model the CLI still runs, an earlier accepted pick that still waits for its `system/init`, else the last `init`'s model, else the pick before; `the default model` stands for `model <m>` or `model <x>` when that is the default, and the parentheses are left out when the answer has no `error` text. The CLI takes the requests in the order they were sent, so a pick still waiting starts from the model the refused one left |
| Switch permission mode | `control_request{set_permission_mode}`: acknowledged with `{"mode": …}` and a `system/status` line carrying `permissionMode` right after it; the next `init.permissionMode` agrees (fixture `permission-mode`: `default`, `plan`, `acceptEdits`). Tandem shows the new mode at once; an `error` response to that request (envelope 4.1; fixture `permission-mode-rejected`, 2.1.302: `bypassPermissions` to a process not launched with `--dangerously-skip-permissions` gets `{"subtype":"error","error":"Cannot set permission mode to bypassPermissions because the session was not launched with --dangerously-skip-permissions","error_code":"bypass_not_launched"}`, and the session goes on) puts back the mode shown before it, unless a later pick replaced it, with an error notice naming the rejected mode. `auto` (fixture `permission-mode-auto`, 2.1.306): `set_permission_mode auto` is acknowledged with `{"mode":"auto"}` and a `system/status` line in `auto`, and the next `init` reports `auto`; a process spawned with `--permission-mode auto` reports `auto` in its first `init` too (a probe, not committed), so Tandem offers `auto` and a respawn keeps it |
| Change effort | `control_request{apply_flag_settings, settings: {effortLevel}}` (section 4.4; fixture `effort-switch` for the request, its success and its error shape): the next turn runs at the new level (the 2.1.301 probe of section 4.4; both of the fixture's turns ran at its default, `high`). Tandem sends it for an effort picked in the footer of a live session and shows the level at once; an `error` answer puts back the level shown before it, with the notice `Effort not changed: <error>`. A stopped session passes the pick with `--effort` at its next spawn |
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
process is stopped instead (`SessionSpawner.takeOver`).

Fork title: the CLI writes no "(fork)" title. A fork's transcript only inherits the original's `custom-title` and
`ai-title` lines (fixture `fork`, and the measurement above). Tandem shows "<original title> (fork)" at once and sends
`rename_session` with that title when the fork's first `system/init` arrives, which is with its first prompt.

---

## 9. Gotchas

A row that cites a fixture was recorded (versions in Sources); the others come from SDK 0.3.300 or say **inferred**.

| Topic | Detail |
|---|---|
| Version gating | Use `init.capabilities` first, `claude_code_version` second. `pending_permission_requests` on initialize from 2.1.268; `interrupt_receipt_v1` for `still_queued`; `scope` on refusal fallback is new (absent = `session`). Refuse to start below a minimum tested version and say so. |
| Interrupt | Fixture `interrupt`: the receipt `{still_queued:[]}` arrives, then the partial `assistant`, a synthetic `user` text `[Request interrupted by user]`, the remaining stream frames, and `result{subtype:"error_during_execution", is_error:true, terminal_reason:"aborted_streaming", stop_reason:null}`. Treat that result as "stopped", not as a failure. Recorded again with 2.1.301 as `history-interrupt`, with its transcript (section 11): the same order; closing stdin afterwards ended the process with exit code 1. |
| API error | Fixture `invalid-model`: a synthetic `assistant` line (`model:"<synthetic>"`, `error:"model_not_found"`, `api_error_status:404`), then `result{subtype:"success", is_error:true, terminal_reason:"api_error"}` (section 7). A failed turn, named by `terminal_reason`, not by the subtype. |
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

Seen in the fixtures (`plain-reply.ndjson` and the others; CLI 2.1.300 and 2.1.301 unless a row names another) but absent from `sdk.d.ts` 0.3.300,
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
| `user.tool_result_meta` | `[{id, permission_decision:{decision, source, reason_type?}, non_execution_kind?}]` | not in `sdk.d.ts`; typed, section 6.2 (`ToolResultMeta`; an entry without a string `id` is skipped; `reason_type`: `tools-read.ndjson:21`, 2.1.302) |
| initialize `control_response` | `pid, current_permission_mode, session_state, feedback_mode, remote_control_*, user_output_styles_dir` | beyond `SDKControlInitializeResponse` |
| assistant `local_command_run`, `local_command_outcome`, `local_command_source`; result `local_command` | a local command's reply (6.1, 7) | not in `sdk.d.ts` 0.3.300 (`mcp-slash.ndjson:5`, `:6`, `:17`, 2.1.306) |
| assistant `tool_use_meta`, user `tool_use_result._meta` / `structuredContent`, `can_use_tool.server_policy_ask_only` | an MCP tool call's label, result and request (4.3, 6.1, 6.2) | not in `sdk.d.ts` 0.3.300 (`mcp-tool-call.ndjson:34`, `:35`, `:39`, 2.1.306) |
| `mcp_authenticate` and its answer | 4.4, MCP requests | not in the typed request union of `sdk.d.ts` 0.3.306 (`mcp-status.ndjson:40`, 2.1.306) |

The MCP fixtures (CLI 2.1.306, each with its transcript) were recorded by a one-off driver, not yet a
`scripts/record-scenario.sh` scenario, with `scripts/drive_cli.py`'s argv minus
`--safe-mode` (which turns MCP servers off), plus `--mcp-config <cwd>/mcp.json --strict-mcp-config --setting-sources
project,local`, against three local servers: `echo` (stdio, one tool `echo` with an output schema), `broken` (`/bin/false`,
so it fails at once) and `authy` (local HTTP that always answers 401 with OAuth metadata and dynamic client registration,
and never issues a token). The redactor kept their `init` lists, which it empties elsewhere.

| Fixture | Host frames (not in the file) | What it records |
|---|---|---|
| `mcp-status` | `initialize`; `mcp_status`; a turn; `mcp_status`; `mcp_toggle echo false`; `mcp_status`; a turn; `mcp_toggle echo true`; `mcp_status`; `mcp_reconnect broken`; `mcp_reconnect authy`; `mcp_toggle nosuch`; `mcp_status`; `mcp_authenticate authy`; `mcp_status`; `mcp_set_servers {echo2}`; `mcp_status`; `mcp_set_servers {}`; `mcp_status`; a turn | every answer of 4.4's MCP requests, `pending` before the first turn (`:2`), the thin `init` list (`:6`, `:23`, `:48`). In `:40` the OAuth nonces of the run were replaced by hand with the URL-safe `REDACTED_CODE_CHALLENGE` and `REDACTED_STATE` (in `authUrl`'s query and in `state`), so the link still parses as a URI; the redactor now does the same. Its transcript holds only the three turns |
| `mcp-tool-call` | `initialize`; a prompt asking for `mcp__echo__echo` with `hello tandem`, allowed; a prompt asking again with `second`, denied with `The user denied this call.` | the deferred tool's `ToolSearch` (`:22`, `:24`), `tool_use_meta` (`:34`), `can_use_tool` with `mcp_server` and `server_policy_ask_only` (`:35`), the result's `structuredContent` and `_meta` (`:39`), the denial (`:62`, `:73`). Transcript: `mcpMeta` (`transcripts/mcp-tool-call.jsonl:32`), `toolDenialKind` (`:41`) |
| `mcp-slash` | `initialize`; the prompts `/mcp`, `/model`, `/help`, then `/hello` (a project command, `.claude/commands/hello.md` in the working directory) | the initialize `commands` catalogue (`:1`), `init.slash_commands` and `terminal_slash_commands` (`:4`), three local commands answered without the model (`:5`-`:6`, `:11`-`:12`, `:17`-`:18`) and one project command that runs a turn (`:22`-`:34`). Transcript: the `local_command` lines of 11.2 (`transcripts/mcp-slash.jsonl:5`, `:10`, `:13`, `:14`); `:13`'s `submitUuid` reads `<leaked-1>`, the redactor's safety net (the redactor now replaces that key) |

---

## 11. Transcripts on disk

The CLI keeps each session as a JSONL file that it only ever appends to. Tandem has no session store of its own: the
sidebar lists these files and a session's history loads from its file (`session/transcript/`), so sessions started in a
terminal show up too. Sources: SDK 0.3.300 (`sdk.mjs` reads and writes the same files), the transcript fixtures in
`src/test/resources/fixtures/transcripts/` (CLI 2.1.301 to 2.1.306, as Sources at the top lists them), and measurements
where noted.

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
| `user` | `uuid`, `parentUuid`, `isSidechain`, `isMeta`, `isCompactSummary`, `permissionMode`, `message.content` | prompts (a string, or text and base64 `image` blocks) and tool results (`tool_result` blocks); the last `permissionMode` is the session's mode |
| `assistant` | `uuid`, `parentUuid`, `isSidechain`, `effort`, `message.id`, `message.model`, `message.content` | one line per content block, sharing `message.id`, as on stdout (6.1); the last model and effort |
| `custom-title` | `customTitle` | the title the user or `rename_session` set |
| `ai-title` | `aiTitle` | a generated title |
| `summary` | `summary` | a generated summary, a title like `ai-title` |
| `last-prompt` | none | `lastPrompt`, `leafUuid`: not used |
| `system` | `uuid`, `parentUuid`, `subtype`; for `local_command`: `content`, `commandRun`, `commandOutcome`, `submitUuid` | links of the chain; `subtype: compact_boundary` marks a compaction (11.3); `subtype: local_command` is a local command (below) |
| `attachment`, `queue-operation`, `cost-state`, `atis-latch`, `mode`, `agent-name`, `file-history-snapshot`, `progress`, any other type | `uuid`, `parentUuid` when present | skipped, except as links of the chain |

The 2.1.301 fixtures show every type above except `summary`, `system`, `file-history-snapshot` and `progress`. `system`
(`subtype: local_command`, and `turn_duration` in the terminal's own transcript) and `file-history-snapshot` are recorded
with 2.1.306 (`transcripts/mcp-slash.jsonl`, `transcripts/bash-mode-interactive.jsonl`); `summary` and `progress` come
from the SDK's reader only. Also recorded: a prompt's `user` line has the `uuid` the host sent with the prompt
(`history-interrupt`); `assistant` lines also carry `requestedModel`, `perTurnEffort` and `requestId`; a reply cut off
by an interrupt has `isAbortedMidStream: true` where its stdout line has `aborted: true`.

A local command (2.1.306, `transcripts/mcp-slash.jsonl`; on stdout, 6.1) is written as an `isMeta` user line with a
`<local-command-caveat>` (`:3`, skipped as every `isMeta` line is), the `<command-name>/mcp</command-name>` user line (`:4`,
shown as `/mcp`, 11.4), then a `system` line with `subtype:"local_command"`, `level:"info"`, `content` wrapped in
`<local-command-stdout>` and `commandRun:{command, args}` (`:5`, `/model` `:10`) or `commandOutcome:{kind:"unavailable_headless"}`
(`:14`). Tandem reads that line as it reads the stream's local-command line: the same INFO notice with the same text. `/help`
has no `<command-name>` line; a `local_command` line with the bare `content:"/help"` and a `submitUuid` (`:13`) holds the
typed command instead, which Tandem shows as the user's `/help`. A `local_command` line wrapped in `<local-command-stderr>`
is read the same way (unrecorded).

### 11.3 The current history

A rewind or an edited prompt leaves the abandoned turns in the file, so the history is not simply every line. A first
pass (`TranscriptChain`) works out which conversational lines (`user` or `assistant`, and a local command's `system`
line with `subtype: local_command`, neither `isSidechain` nor `isMeta`) belong to it:

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
  first prompt of the current history, slash commands aside, as its first line trimmed and cut to 80 characters (a prompt
  of images only: `[Image #1]`); the session id. A blank title does not count. The SDK's `listSessions` (0.3.300)
  differs: it joins the prompt's lines, cuts at 200 characters and prefers `aiTitle` over `summary`.
- Model: `message.model` of the last `assistant` line, except models that start with `<`, such as `<synthetic>` on the
  lines the CLI writes itself for API errors. Effort: the last `effort` present. Permission mode: the last `user` line's
  `permissionMode` that names a known mode; compact summaries do not count. All three from the current history only.
- Skipped: `isMeta` lines (context the CLI adds), `isSidechain` lines (subagents) and `isCompactSummary` lines.
- User lines that the CLI writes itself, told apart by how the text starts, as the CLI does:

| Text starts with | Written for | Tandem shows |
|---|---|---|
| `[Request interrupted` (`by user`, `by user for tool use`) | an interrupt | the interrupt notice, shown as `Interrupted · What should Claude do instead?`, as live |
| `<command-name>` or `<command-message>`, with `<command-args>` | a slash command | `/name args`, never as the title |
| `<local-command-stdout>`, `<local-command-stderr>` | a command's output | nothing |
| `<bash-input>` | a `! command` shell escape | the shell row `! command` (the title form too, as the CLI's own `last-prompt`); its output on the same line (`transcripts/bash-mode.jsonl:5`) is the row's output |
| `<bash-stdout>`, `<bash-stderr>` | its output, on a line of its own (`transcripts/bash-mode-respond.jsonl:6`) | the row's output: stdout, then a non-empty stderr on a line of its own; skipped when no shell row before it is still without output |
| `<task-notification>` | a background task | nothing |

Recorded: the interrupt line (`history-interrupt`, 2.1.301):
`{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"parentUuid":<the cut-off reply>,…}`,
the shape the hand-written test uses; a slash command's line (`transcripts/mcp-slash.jsonl:4`, 2.1.306); the shell-escape
lines (`transcripts/bash-mode.jsonl:5`, `transcripts/bash-mode-respond.jsonl:6`, 2.1.306). The others, a
`<local-command-stdout>` user line and `<task-notification>`, come from the CLI's binary and the SDK and are pinned with
hand-written lines in `TranscriptReaderTest`.

A loaded chat is settled whatever the file ends with: not busy, no pending permission, no streaming entry.

The reader feeds the reducer the same events as the live stream, each stamped with its line's `timestamp`, and the
decoder reads the on-disk names (`toolUseResult`, `thinkingDurationMs`, `agentId`, `permissionDecision` +
`toolDenialKind`), so a reloaded session builds the same rows, with these differences:

| Row element | Live source | On disk (`fixtures/transcripts/*.jsonl`) | On reload |
|---|---|---|---|
| tool bodies, stats | `tool_use_result` | `toolUseResult` (`tools-edit.jsonl` has `structuredPatch`) | identical bodies |
| `DONE` / `FAILED` / `DENIED` | `tool_result_meta` / `is_error` | `permissionDecision` + `toolDenialKind` (`ask-deny.jsonl:20`), `is_error` in the block | identical states |
| thinking summary, duration | deltas, `thinking_duration_ms` | the block text as recorded, `thinkingDurationMs` (`think-default.jsonl`: 2143) | `Thought for 2s`; the summary only if the session ran summarized (`think-display-summarized.jsonl:18`) |
| thinking tokens | `system/thinking_tokens` | not on disk | no ` · n tokens` (the row is complete anyway) |
| `Worked for …` footer | `result` | no `result` lines | no footer; `Interrupted · …` from the marker notice only |
| working row | `turn` | settled state | none; every call without a result is `INTERRUPTED` |
| subagent children | sidechain lines with `parent_tool_use_id` | not in `<id>.jsonl` (they live in `<id>/subagents/agent-*.jsonl`, not read) | `Agent` row with `Done (…)` from `toolUseResult.totalToolUseCount/totalTokens/totalDurationMs` (`agent-sync.jsonl`) and no children; its body is the agent's final text |
| user send time, elapsed | `sentAtMillis` | `timestamp` on the user line | unused on a settled state |
| notices (retries, fallbacks) | `system/*` lines | not on disk | absent |
| `cwd` for relative names | `system/init.cwd` | every line's `cwd` | identical names |

`cwd`: the first line's `cwd` (the first line of the current history that has one), so relative file names match the
live session. A transcript whose lines have no `timestamp` and no `cwd` (an older CLI) still loads, with no `cwd` and the
same entries.

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

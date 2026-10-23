---
title: "What Claude Code's CLI does over stream-json, from recorded sessions"
---

# What Claude Code's CLI does over stream-json, from recorded sessions

Claude Code's CLI has a machine mode. Start it with `--output-format stream-json --input-format stream-json` and it reads one JSON object per line on stdin and writes one per line on stdout. The Agent SDKs are wrappers around that mode. I have spent the last weeks driving the mode directly, from a JVM, and recording what came back. This is the reference I wished I had.

Every claim below rests on a recorded transcript of a real session, CLI 2.1.300 to 2.1.308, or on a measurement where a recording would show nothing. Each gotcha names the CLI version and the recording. Where something comes from the SDK's types alone, or is an inference, I say so. Excerpts are redacted and trimmed: paths read `<path>`, ids are numbered placeholders such as `<id-8>`, and `...` stands for fields I cut.

## Why drive the CLI directly

The TypeScript Agent SDK (`@anthropic-ai/claude-agent-sdk`) is a Node package that runs either a `claude` path you give it or its own bundled native binary. I wanted the user's installed `claude`, with their sign-in, their settings and their MCP servers, and no Node runtime inside an IDE plugin. So I spawn the binary and speak the protocol myself. Sign-in stays the CLI's business; I never touch credentials.

The second reason is that the SDK's types do not tell the whole story. `sdk.d.ts` describes what the SDK reads. The CLI writes more: lines before `init`, fields on `result`, whole message types the SDK passes through untouched. And the behaviour that matters most, which result means "stopped" and which means "failed", is not in any type. You only find it by recording.

My order of authority: recorded transcripts, then measurements, then SDK 0.3.300's `sdk.d.ts` and `sdk.mjs`, then `claude --help`.

## The gotchas

### 1. An interrupted turn ends in an error result

Expected: send `interrupt`, get a receipt, then a `result` with `subtype:"success"`.

Actual (CLI 2.1.300, recording `interrupt`): the receipt comes first.

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-16>","response":{"still_queued":[]}}}
```

Then the cut-off `assistant` line, with `"aborted":true`. Then a synthetic `user` line the CLI writes itself:

```json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"parent_tool_use_id":null,"session_id":"<session_id-2>","uuid":"<uuid-19>", ...}
```

Then the remaining stream frames, and a `result` with `"subtype":"error_during_execution"`, `"is_error":true`, `"terminal_reason":"aborted_streaming"` and `"stop_reason":null`. Treat that as "stopped", not as a failure.

### 2. A failed API call ends in a success result

Expected: a `result` whose subtype says error.

Actual (2.1.302, `invalid-model`, started with `--model claude-nonexistent-0`): no stream frames at all. The CLI writes an `assistant` line itself, with `"model":"<synthetic>"`, `"error":"model_not_found"`, `"is_api_error_message":true`, `"api_error_status":404` and one text block: "There's an issue with the selected model (claude-nonexistent-0). It may not exist or you may not have access to it." Then:

```
result: "subtype":"success"  "is_error":true  "terminal_reason":"api_error"  "stop_reason":"stop_sequence"
```

The subtype says nothing here; `terminal_reason` names the failure. And never show `<synthetic>` as the model that ran.

### 3. `init` is not the first line, and it comes every turn

Expected: `system/init` is the first line on stdout, once per process.

Actual (2.1.300, `plain-reply`, lines 1, 2 and 4):

```json
{"type":"active_goal","value":null,"uuid":"<uuid-1>","session_id":"<session_id-2>"}
{"type":"autocompact_state","value":{"enabled":true,"effective_window":980000,"threshold":784000,"enforced":true,"source":"model-default"},"uuid":"<uuid-3>","session_id":"<session_id-2>"}
```

Line 3 is `system/ui_invalidate`; `init` is line 4. `active_goal` is typed in the SDK but outside its message union; `autocompact_state` is not typed at all. Most signed-in runs have that `ui_invalidate` line before `init`, but three of my 29 probe recordings on 2.1.302 have none, so key nothing on it.

`init` is then emitted at the start of every turn. Use that: compare `init.model` with what you asked for, every turn.

### 4. The complete `assistant` line arrives before `content_block_stop`

Expected: deltas, then `content_block_stop`, then the complete block.

Actual (2.1.300, `plain-reply`, lines 7 to 15):

```
stream_event message_start            (message.id = M)
stream_event content_block_start  idx 0
stream_event content_block_delta  idx 0 "pong"
assistant    message.id = M, content=[text "pong"]   <-- complete block
stream_event content_block_stop   idx 0              <-- after the assistant line
stream_event message_delta        stop_reason=end_turn, usage
stream_event message_stop
system       post_turn_summary
result
```

When an `assistant` line with the same `message.id` arrives, replace the in-progress block with it. Do not wait for `content_block_stop`; a late stop for a completed block is a no-op. One `assistant` line comes per completed block, consecutive lines share `message.id`, and `stop_reason` is `null` on all of them. The final stop reason and usage come on `result`.

### 5. Permission requests do not wait for the message to close

Expected: `message_stop`, then the CLI asks for permission.

Actual (2.1.300, `permission`): the `assistant` line with the `tool_use` block, `content_block_stop`, the `can_use_tool` request, and only then `message_delta` and `message_stop`. If you answer at once, the `user` line with the tool result arrives before `message_delta` (`ask-deny`, lines 24 and 25). Never make a tool's card wait for the message to close.

### 6. Thinking is empty unless you ask with a hidden flag

Expected: `thinking_delta` frames carry text.

Actual (2.1.302, `think-default`, line 10):

```json
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"","estimated_tokens":50}},"session_id":"<session_id-4>","parent_tool_use_id":null,"uuid":"<uuid-13>","thinking_display":"updates","api_message_id":"<id-9>"}
```

Every delta has `"thinking":""` plus a non-standard `estimated_tokens`. The completed block (line 16) is `{"type":"thinking","thinking":"","signature":"..."}`. That is the default display, `updates`.

The flag that changes it is `--thinking-display summarized`. It is not in the SDK and it is hidden from `--help` on 2.1.302. With it, `thinking_delta.thinking` is a text chunk and the completed block holds the whole summary (`think-display-summarized`, lines 11 and 34). An empty thinking string with a signature is normal, not a parse error.

### 7. A typed slash command runs no turn and is not echoed

Expected: `/mcp` as a user message either errors or starts a turn.

Actual (2.1.306, `mcp-slash`, lines 5 and 6): the CLI answers `/mcp`, `/model` and `/help` itself, with one `assistant` line:

```json
{"type":"assistant","message":{...,"model":"<synthetic>","role":"assistant",...,"stop_reason":"end_turn",...,"content":[{"type":"text","text":"3 MCP server(s): 1 connected, 2 not connected, 0 disabled. Use `/mcp` in the terminal for details."}], ...}, ...,"local_command_run":{"command":"mcp", ...}, ...}
```

Zero usage, and a `result` with `"num_turns":0`, `"duration_api_ms":0` and `"local_command":"mcp"`. No `system/status requesting`, no `stream_event`, and the typed line is not echoed.

### 8. `set_model` succeeds silently

Expected: a response payload naming the new model, or a typed system line.

Actual (2.1.300, `model-switch`, lines 20 and 21):

```json
{"type":"user","message":{"role":"user","content":"<local-command-stdout>Set model to `claude-haiku-5-5`</local-command-stdout>"},"session_id":"<session_id-2>","parent_tool_use_id":null,"uuid":"<uuid-24>", ...,"isReplay":true}
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-25>"}}
```

The success frame has no `response` field. The only trace is that replayed `user` line. So compare `init.model` and `assistant.message.model` against what you asked for. Do not key on `result.modelUsage`: it legitimately lists helper models (`plain-reply`, line 15: `claude-haiku-5-5` beside `claude-sonnet-5-5` with no switch).

### 9. `--session-id` and `--resume` have exact rules

Measured on 2.1.301, sending only `initialize`; not a recording.

Expected: `--session-id X` creates the session on disk; `--resume X` opens it.

Actual: `--session-id X` without a prompt writes no transcript, not even the project folder; the CLI writes `X.jsonl` with the first prompt. `--resume X` before that fails: exit code 1, a `result` with `subtype:"error_during_execution"`, and on stderr "No conversation found with session ID: X". `--session-id X` once `X.jsonl` exists also fails: exit code 1, "Error: Session ID X is already in use." `--resume X --session-id Z` without `--fork-session` exits with code 1: "Error: --session-id can only be used with --continue or --resume if --fork-session is also specified."

The rule: pass `--resume` exactly when `<id>.jsonl` exists, and give every new session and every fork its id up front. Either mistake fails again on every retry.

### 10. `tool_use_result` is an object on success and a string on failure

Expected: one shape per tool.

Actual (2.1.302, `tools-bash`, line 39):

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"Exit code 3\nfailing","is_error":true,"tool_use_id":"<id-229>"}]}, ...,"tool_use_result":"Error: Exit code 3\nfailing","tool_result_meta":[{"id":"<id-229>","permission_decision":{"decision":"accept","source":"user_temporary"}}]}
```

On success `tool_use_result` is a JSON object with a per-tool shape; on any failure it is the string `"Error: " + content`. Decode it as a generic JSON element. A Bash success is `{stdout, stderr:"", interrupted, isImage, noOutputExpected}`: stderr is merged into stdout and there is no exit code. The exit code only shows up in the failure string.

### Smaller ones

- Queued sends are batched: two quiet frames (`shouldQuery:false`) sent back to back are both queued, then both started, and the CLI merges them into one transcript line and one zero-turn `result` naming both (`bash-mode`, 2.1.306). Send a `uuid` with every user message and read `result.user_message_uuids`.
- `mcp_status` right after `initialize` reports the stdio servers as `pending`; the first turn's `init` has the settled states (`mcp-status`, 2.1.306).
- `mcp_set_servers {"servers":{}}` removes only what it added, and `source` is `dynamic` for the `--mcp-config` servers too, so a host has to remember what it added (`mcp-status`, 2.1.306).
- `apply_flag_settings {settings:{effortLevel}}` changes the effort of a running session; `update_settings` without a `source` was rejected (2.1.301, a probe, not a recording; `effort-switch`, 2.1.306). `init` never names the effort, so only the transcript on disk confirms the change.
- A refused control request is an `error` frame and the session goes on: show the `error` text verbatim and put the UI back (`permission-mode-rejected`, 2.1.302).
- A `claude` that inherits the CLI's own session variables (`CLAUDECODE`, `CLAUDE_CODE_SESSION_ID` and the like) acts on the parent session. Every recording I made inside a hosted Claude Code session, before I removed them, wrote its transcript as `<parent id>.jsonl`.

## Spawning the CLI

The SDK always starts with `claude --output-format stream-json --verbose --input-format stream-json`. It does not pass `-p`; `--input-format stream-json` implies headless print mode. `--verbose` is required with `stream-json` output (inferred from the SDK always adding it). What I add:

- `--include-partial-messages` for streaming text.
- `--permission-prompt-tool=stdio`, so permission prompts come as control requests. It and an MCP tool name are mutually exclusive.
- `--thinking-display summarized` (gotcha 6).
- `--model`, `--effort <low|medium|high|xhigh|max>` and `--permission-mode <mode>` when the user picked one; left out, the CLI's default applies.
- `--disallowedTools Edit,Write,MultiEdit,NotebookEdit`, because I want edits to go through the IDE.
- - Exactly one of `--session-id <uuid>` (new), `--resume <id>` (once `<id>.jsonl` exists) or `--resume <id> --fork-session --session-id <new uuid>` (fork).

I remove the CLI's own session variables from the child's environment, delete `NODE_OPTIONS` as the SDK does, and set `CLAUDE_CODE_ENTRYPOINT` to a name of my own.

`CLAUDE_CODE_SDK_READS_SESSION_STATE=1` makes the CLI emit `system/session_state_changed` lines (`misc-state-sdk`, 2.1.302). Their `state:"idle"` after `result` is the cleanest turn-over signal there is.

Startup: spawn, write `control_request{initialize}`, read its answer, write the first user message, read `system/init` and so on to `result`. Keep stdin open for the whole session. Closing it is the graceful shutdown: wait for exit, then signal.

## Envelopes

One JSON object per line, UTF-8, newline-terminated. Skip blank and non-JSON lines. Single lines can be hundreds of KB (tool results, images), so do not read with a small maximum line length. Every stdout message except the control frames and `keep_alive` carries `uuid` and `session_id`. Parse an unknown `type` or `subtype` as unknown and keep going; never throw.

A user message on stdin, exactly as the SDK writes it for a string prompt:

```json
{"type":"user","session_id":"","message":{"role":"user","content":[{"type":"text","text":"ping"}]},"parent_tool_use_id":null}
```

`session_id` is `""`: the CLI owns the id. Optional: `uuid` (send one; it comes back as `user_message_uuid(s)` on `stream_event` and `result`), `shouldQuery:false` to append without starting a turn, and `client_composed:true` to disable `@path` expansion and slash-command dispatch.

## The control channel

Both directions use the same envelope (shape only; ids illustrative):

```json
{"type":"control_request","request_id":"k3j9x2a1b","request":{"subtype":"interrupt"}}
{"type":"control_response","response":{"subtype":"success","request_id":"k3j9x2a1b","response":{}}}
{"type":"control_response","response":{"subtype":"error","request_id":"k3j9x2a1b","error":"Unknown subtype"}}
```

The sender picks `request_id`. Exactly one response per request; ignore responses for ids you are not waiting on; `control_cancel_request` withdraws a pending request. What a CLI that predates a request subtype answers to it, that error or nothing at all, I have not recorded, so every request I send has a timeout.

### `initialize`

The first frame. Mine is `{"subtype":"initialize","hooks":null,"forwardSubagentText":true}`; the flag makes the CLI forward a subagent's text replies as `assistant` lines with an `agent_id` (`agent-forwarded`, 2.1.302). The answer carries `commands` (the slash-command catalogue), `agents`, `models`, `account` (display only; never store it) and `claude_code_version`.

### `can_use_tool`

Enabled by `--permission-prompt-tool=stdio`; the CLI blocks the tool until answered (2.1.300, `permission`, line 21).

```json
{"type":"control_request","request_id":"<request_id-26>","request":{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash","input":{"command":"touch tandem-fixture.txt","description":"Create empty fixture file"},"description":"Create empty fixture file","permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"touch tandem-fixture.txt"}],"behavior":"allow","destination":"localSettings"}, ...],"blocked_path":"<path>","tool_use_id":"<id-14>"}}
```

`permission_suggestions` feeds "always allow" buttons and is absent on `AskUserQuestion` and `ExitPlanMode`. `requires_user_interaction:true` marks requests that need a real answer, not a one-tap approve.

Answer `{"behavior":"allow","updatedInput":{...},"toolUseID":"..."}`, echoing the (possibly edited) input, plus `updatedPermissions` for an "always allow" rule; or `{"behavior":"deny","message":"...","interrupt":true}`, where `interrupt:true` also aborts the turn. Read-only commands are auto-approved and never reach you.

`AskUserQuestion` (2.1.302, the `ask-*` recordings) is how the CLI asks the host to render a question, in `default` mode too:

```json
{"type":"control_request","request_id":"<request_id-27>","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","display_name":"AskUserQuestion","input":{"questions":[{"question":"Which colour?","header":"Colour","options":[{"label":"Red","description":"Red"},{"label":"Blue","description":"Blue"}],"multiSelect":false}]},"tool_use_id":"<id-12>","requires_user_interaction":true}}
```

Answer `allow` with `updatedInput` = the input plus `answers`, keyed by the exact question text. `deny` with a `message` and no `interrupt` cancels the question only, and the turn goes on. `ExitPlanMode` never carries `permission_suggestions`, so the host builds the mode switch itself: `allow` with `updatedPermissions:[{"type":"setMode","mode":"acceptEdits","destination":"session"}]` (`plan-approve`, 2.1.302).

### Other requests

- `set_permission_mode {mode}`: `default`, `acceptEdits`, `bypassPermissions`, `plan`, `dontAsk`, `auto`. Answers `{"mode": …}`, then a `system/status` line with the new `permissionMode`.
- `mcp_reconnect {serverName}`: for a failed server, an error carrying the connection error itself, `Connection closed`; for one that needs a sign-in, `Server status: needs-auth`. A reconnect starts no sign-in.
- `mcp_authenticate {serverName}` answers at once, without waiting for the sign-in (`mcp-status`, 2.1.306, line 40):

```json
{"authUrl":"http://127.0.0.1:33041/authorize?response_type=code&client_id=...&code_challenge=...&code_challenge_method=S256&redirect_uri=http%3A%2F%2Flocalhost%3A53713%2Fcallback&state=...&scope=mcp&resource=...","requiresUserAction":true,"callbackExpected":true,"redirectScheme":"localhost","state":"...","callbackPort":53713}
```

`authUrl` is an OAuth authorization link with PKCE whose `redirect_uri` is `http://localhost:<callbackPort>/callback`. The CLI opens no browser, so the host opens the URL. What happens when the callback completes I have not recorded.

The SDK also types `hook_callback`, `mcp_message`, `elicitation` and `request_user_dialog`; only `can_use_tool` is in my recordings. Answer every subtype you do not handle with an `error`; never leave the CLI hanging.

## `system` messages beyond `init`

The SDK types `model_refusal_fallback` and `model_refusal_no_fallback`; neither is in any of my recordings. I decode them from the types anyway, because a model change must never go unnoticed. An overload fallback (`--fallback-model`) has no dedicated subtype in the 0.3.300 types at all; the fallback is to compare the requested model with `init.model` and `assistant.message.model`.

## Streaming, tool results and the turn

`stream_event` wraps a raw Anthropic SSE event. Key the in-progress message on (`message.id`, `parent_tool_use_id`) and track blocks by `index`: subagent traffic interleaves, and no `stream_event` frames come from a subagent.

`result` comes exactly once per turn; `post_turn_summary` and `session_state_changed` can still follow it. `total_cost_usd` is cumulative over the process: read the latest, do not sum. Turn over means `result` with `queued_turn_count == 0`, or `session_state_changed{state:"idle"}` when you enabled it.

## Sessions: resume, fork, model

- New: `--session-id <uuid>`. The first prompt's `init` reports it and the CLI writes `<uuid>.jsonl` from then on.
- Resume: `--resume <id>`. The CLI loads the transcript itself and does not stream the history to stdout; load the history from the file. Measured on 2.1.301: a session started with `--model sonnet` and resumed without `--model` reported `init.model` as the Sonnet model. The CLI restores the model a session was started with. Whether it restores a model switched mid-session with `set_model` I have not measured.
- Fork: `--resume <id> --fork-session --session-id <new uuid>`. The fork's `init` reports the new id, `<id>.jsonl` is left as it was, and the fork's transcript starts with a copy of the original's history with every `sessionId` rewritten (`fork`, 2.1.301).
- The `session_id` on every line is authoritative; it changes after a fork. If a process's first `init` reports an id other than the one you started it with, do not relabel: treat it as a new session.

## Transcripts on disk

The CLI keeps each session as a JSONL file it only ever appends to, under `$CLAUDE_CONFIG_DIR` when set, else `~/.claude`: `<config>/projects/<key>/<session id>.jsonl`. The key is the project path with every character outside `A-Z a-z 0-9` replaced by `-`, cut to 200 characters with a hash appended when cut: `/home/u/my proj.v2` gives `-home-u-my-proj-v2`. The path is the CLI's physical working directory (`/private/tmp/…` for `/tmp/…` on macOS). The file appears with the first prompt (gotcha 9).

The history is not simply every line. A rewind or an edited prompt leaves the abandoned turns in the file. The current history is the `parentUuid` chain that ends at the last conversational line, and the chain runs through lines of any type: in my recordings 110 of the 136 `assistant` lines have an `attachment` line as their parent, not the prompt. Nothing from the stream's `result` or `system` lines is on disk, so a reloaded session has no durations, retries or fallback notices.

## How the evidence stays honest

The recordings are made by two scripts against a real, signed-in CLI, from an empty directory. Each run copies the CLI's own transcript next to the stdout recording. A redactor runs in the same run: paths become `<path>`, ids become numbered placeholders, and an id under a key it does not know comes out as `<leaked-N>`, which is a stop, not a note.

The recordings are test fixtures. A fake CLI, a small script, replays one in place of the real binary, so the tests run against the recorded bytes without a sign-in. The CLI changes weekly, so a nightly job records a few scenarios with the newest released CLI and decodes them with the same code that decodes the committed ones. A red run means the CLI moved.

## Close

I built this reference for Tandem, a JetBrains IDE plugin for Claude Code. It is early: not on the JetBrains Marketplace yet, and it does not edit files yet (it disables the CLI's own Edit and Write tools until its IDE edit tools land). The protocol layer is in place and tested against these recordings; it is what this article is about, and what I have not recorded is marked as such above.

If you are building on the claude CLI, record before you trust a type. The CLI is honest about what it does; it just does not say it where the SDK's types would lead you to look.

### Links

These point at the repository's `main` branch.

- The project, Tandem: https://github.com/daarko10/tandem
- The full reference, with the fixture line or other source for every claim: https://daarko10.github.io/tandem/docs/protocol.html
- The recordings and their transcripts: https://daarko10.github.io/tandem/src/test/resources/fixtures/
- The one-shot recorder: https://daarko10.github.io/tandem/scripts/record-fixture.sh
- The scenario recorder and its driver: https://daarko10.github.io/tandem/scripts/record-scenario.sh and https://daarko10.github.io/tandem/scripts/drive_cli.py
- The redactor: https://daarko10.github.io/tandem/scripts/redact_fixture.py
- The local MCP probe servers: https://daarko10.github.io/tandem/scripts/mcp_probe_servers.py
- The fake CLI the tests run against: https://daarko10.github.io/tandem/src/test/resources/fake-claude.py
- The nightly contract run: https://daarko10.github.io/tandem/.github/workflows/cli-contract.yml

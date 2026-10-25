---
title: "What Claude Code's CLI does over stream-json, from recorded sessions"
---

# What Claude Code's CLI does over stream-json, from recorded sessions

This is for people who spawn the `claude` binary themselves: IDE plugins, wrappers, bots, CI jobs. If you use the Agent SDK, much of what follows is handled for you and you can skim. If you read the CLI's stdout yourself, every item below is a bug you will otherwise write.

Claude Code's CLI has a machine mode. Start it with `--output-format stream-json --input-format stream-json` and it reads one JSON object per line on stdin and writes one per line on stdout. I have spent the last weeks driving that mode directly, from a JVM, and recording what came back. Every claim here rests on a recorded transcript of a real, signed-in session, CLI 2.1.300 to 2.1.308. Each one names the CLI version and the recording, and the recordings are public (links at the end). Excerpts are redacted and trimmed: paths read `<path>`, ids are numbered placeholders such as `<id-8>`, and `...` marks every cut.

It is a living reference, not a snapshot. A nightly job re-records a handful of scenarios against the newest released CLI and decodes them with the same code that decodes the committed recordings. When the CLI moves, the run goes red and the reference gets a new version number next to the claim that changed.

## The gotchas

Each one has the kind of thing it is: a protocol rule (how the stream is shaped), a documented contract, a version-specific behaviour (what these versions did; it may change), or an easy consumer mistake.

### 1. The tool result is JSON, unless something went wrong

Protocol rule.

Expected: one shape per tool for `tool_use_result`, the structured copy of a tool's output on the `user` line that carries the `tool_result` block.

Actual (CLI 2.1.302, recording `tools-bash`, lines 23 and 39): on success it is an object with a per-tool shape. For Bash:

```json
{"stdout":"out-line\nerr-line","stderr":"","interrupted":false,"isImage":false,"noOutputExpected":false}
```

Note what is missing: no exit code, and stderr merged into stdout. On any failure the same field is a string:

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"Exit code 3\nfailing","is_error":true,"tool_use_id":"<id-229>"}]}, ...,"tool_use_result":"Error: Exit code 3\nfailing", ...}
```

Nine of my recordings have the string form: failing and denied commands, a denied MCP call, a cancelled question, a rejected plan, a disabled tool. A subagent's own tool results can lack the field entirely (`agent-sync`, line 47). The exit code only ever shows up inside the failure string.

What to do: decode `tool_use_result` as a generic JSON element, branch on its kind, and never trust a per-tool class for the failure case.

### 2. Press Stop, receive an error

Version-specific behaviour (2.1.300 and 2.1.301).

Expected: send `interrupt`, get a receipt, then a `result` with `subtype:"success"` and a short reply.

Actual (2.1.300, `interrupt`, lines 13 to 19): the receipt comes first.

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-16>","response":{"still_queued":[]}}}
```

Then the cut-off `assistant` line, with `"aborted":true` at the top level. Then a `user` line the CLI writes itself:

```json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"parent_tool_use_id":null,"session_id":"<session_id-2>","uuid":"<uuid-19>", ...}
```

Then the remaining stream frames, and a `result` with `"subtype":"error_during_execution"`, `"is_error":true`, `"terminal_reason":"aborted_streaming"`, `"stop_reason":null` and `"result":null`. The same order again on 2.1.301 (`history-interrupt`, lines 11, 13 and 17).

What to do: treat `terminal_reason:"aborted_streaming"` as "stopped", not as a failure, and do not show the synthetic user line as something the user typed.

### 3. The model did not exist, and the turn succeeded

Version-specific behaviour (2.1.302, one recording).

Expected: a `result` whose subtype says error.

Actual (2.1.302, `invalid-model`, started with `--model claude-nonexistent-0`, lines 4 and 5): no stream frames at all. The CLI writes an `assistant` line itself, with `"model":"<synthetic>"`, `"error":"model_not_found"`, `"is_api_error_message":true`, `"api_error_status":404` and one text block that starts "There's an issue with the selected model (claude-nonexistent-0)". Then:

```json
{"duration_api_ms":0,"stop_reason":"stop_sequence","session_id":"<session_id-2>","total_cost_usd":0, ...,"terminal_reason":"api_error", ...,"is_error":true,"num_turns":1,"subtype":"success","api_error_status":404, ...,"type":"result", ...}
```

The subtype says nothing here. `terminal_reason` names the failure, and `is_error` is the flag that is set.

What to do: read `is_error` and `terminal_reason`, not `subtype`. And never show `<synthetic>` as the model that ran; it is the CLI talking, not a model.

### 4. The finished block arrives before the block has finished

Protocol rule.

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

This is not a quirk of one turn. In 47 of my 50 recordings a `content_block_stop` follows directly after an `assistant` line, 126 times in all. One `assistant` line comes per completed block, consecutive lines share `message.id`, and `stop_reason` is `null` on every one of the 148 model-written `assistant` lines (the other 4 of 152 are the CLI's own `<synthetic>` lines). The final stop reason and usage come on `result`.

What to do: when an `assistant` line with the same `message.id` arrives, replace the in-progress block with it. A late `content_block_stop` for a completed block is a no-op. Do not wait for it to render.

### 5. May I? asked before the sentence ends

Protocol rule.

Expected: `message_stop`, then the CLI asks for permission.

Actual (2.1.300, `permission`, lines 19 to 23): the `assistant` line with the `tool_use` block, `content_block_stop`, the `can_use_tool` request, and only then `message_delta` and `message_stop`. If you answer at once, the `user` line with the tool result can arrive before `message_delta` (2.1.302, `ask-deny`, lines 24 and 25). `tools-bash` (lines 18 to 23) has the same request-first order.

```json
{"type":"control_request","request_id":"<request_id-26>","request":{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash","input":{"command":"touch tandem-fixture.txt","description":"Create empty fixture file"}, ...,"tool_use_id":"<id-14>"}}
```

The CLI blocks the tool until you answer, and the tool's result can arrive while the message that asked for it is still open.

What to do: handle `control_request` the moment it arrives, independent of message state. Never make a tool's card wait for `message_stop`.

### 6. Thinking, streamed in zero characters

Version-specific behaviour (2.1.302).

Expected: `thinking_delta` frames carry text.

Actual (2.1.302, `think-default`, line 10):

```json
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"","estimated_tokens":50}}, ...,"thinking_display":"updates", ...}
```

Every delta has `"thinking":""`, and most carry a non-standard `estimated_tokens` (50, then 100, then `null`). The completed block (line 16) is `{"type":"thinking","thinking":"","signature":"..."}`. Same on `think-opus` and `think-adaptive`. The frames label this display `updates`.

With `--thinking-display summarized` the frame reads `"thinking":"47","estimated_tokens":null`, labelled `"thinking_display":"summarized"`, and the completed block holds the whole summary (`think-display-summarized`, lines 11 and 34).

What to do: pass the flag if you want text. Either way, render thinking by appending deltas and replacing with the completed block, and treat an empty thinking string with a signature as normal, not as a parse error.

### 7. A built-in slash command runs a turn with zero turns in it

Version-specific behaviour (2.1.306).

Expected: `/mcp` sent as a user message either errors or starts a model turn.

Actual (2.1.306, `mcp-slash`, lines 4 to 6): the CLI answers `/mcp`, `/model` and `/help` itself. There is an `init`, then one `assistant` line:

```json
{"type":"assistant","message":{...,"model":"<synthetic>","role":"assistant", ...,"stop_reason":"end_turn", ...,"usage":{...,"input_tokens":0,"output_tokens":0, ...},"content":[{"type":"text","text":"3 MCP server(s): 1 connected, 2 not connected, 0 disabled. Use `/mcp` in the terminal for details."}], ...}, ...,"local_command_run":{"command":"mcp","args":""}}
```

Then a `result` with `"num_turns":0`, `"duration_api_ms":0` and `"local_command":"mcp"`. No `system/status`, no `stream_event`, and the typed line is not echoed back as a `user` line.

What to do: treat an `assistant` line with `"model":"<synthetic>"` and a `local_command_source` as a local notice, not as a model reply; `local_command_run` and `result.local_command` name the command when present, but `/help` has neither (lines 17 and 18). Do not divide anything by `num_turns`.

### 8. set_model says yes, and nothing else

Protocol rule.

Expected: a response payload naming the new model, or a typed system line.

Actual (2.1.300, `model-switch`, lines 20 and 21):

```json
{"type":"user","message":{"role":"user","content":"<local-command-stdout>Set model to `claude-haiku-5-5`</local-command-stdout>"},"session_id":"<session_id-2>","parent_tool_use_id":null,"uuid":"<uuid-24>", ...,"isReplay":true}
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-25>"}}
```

The success frame has no `response` field at all. The only trace is that replayed `user` line, and look at its `content`: a string, not an array of blocks. That is the one place on stdout in my recordings where it happens, and the transcripts on disk have thirteen more (bash-mode input and output, slash-command caveats and command names, two plain prompts).

What to do: accept `message.content` as string or array everywhere. To know what model is running, compare `init.model` and `assistant.message.model` with what you asked for, every turn. Do not key on `result.modelUsage`: it legitimately lists helper models (`plain-reply`, line 15: `claude-haiku-5-5` beside `claude-sonnet-5-5` with no switch).

### 9. The cost of this turn is the cost of every turn so far

Easy consumer mistake.

Expected: `result.total_cost_usd` is what this turn cost.

Actual (2.1.300, `permission`, lines 35 and 66): the first `result` says `0.0258`, the second `0.0341`, and the second's `modelUsage` has the first turn's tokens in it:

```json
"modelUsage":{"claude-haiku-5-5":{"inputTokens":1199,"outputTokens":19, ...},"claude-sonnet-5-5":{"inputTokens":8,"outputTokens":180,"cacheReadInputTokens":127866, ...}}
```

Line 35 had `inputTokens:4`, `outputTokens:90`, `cacheReadInputTokens:61509` for the same model. In every recording `total_cost_usd` equals the sum of `modelUsage[*].costUSD`, and both grow turn over turn: five rising totals in `think-summarized` (2.1.302), three in `mcp-status` (2.1.306).

What to do: show the latest value; never add results up. A per-turn number is the difference between two results.

### 10. The model calls the tool you took away

Easy consumer mistake.

Expected: a tool removed with `--disallowedTools` is gone. It is not in `init.tools`, so it will not appear in the stream.

Actual (2.1.302, `plan-disallowed`, lines 6, 102 to 104): `init.tools` has no `Write`. The model calls it anyway, and the CLI answers before the block has even closed:

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"<tool_use_error>Error: No such tool available: Write. Write is disabled for this session, in subagents as well as here.</tool_use_error>","is_error":true, ...}]}, ...,"tool_use_result":"Error: No such tool available: Write. Write is disabled for this session, in subagents as well as here."}
```

Then `content_block_stop`, then the next block; the turn goes on. Same with `Glob` in `tools-read-unavailable` (lines 6, 48 and 49). Note the two spellings of one error: `content` wrapped in `<tool_use_error>`, `tool_use_result` plain.

What to do: label tool calls by the `tool_use` block's `name`, never by lookup in `init.tools`, and expect an error result for any name. If you show `content` raw, strip the tag.

### Smaller ones

- `init` is not the first line. `plain-reply` (2.1.300) opens with `active_goal`, `autocompact_state` and `system/ui_invalidate`; `init` is line 4. Six of my 50 recordings have no `ui_invalidate` before `init`, so key nothing on it. `init` comes at the start of every turn (`think-summarized`, five of them), which is the place to compare `init.model` with what you asked for.
- Two quiet frames (`shouldQuery:false`) sent back to back become one zero-turn `result` naming both uuids in `user_message_uuids` (`bash-mode`, lines 3 to 8, 2.1.306). One `result` can answer several sends: send a `uuid` with every user message and match on the list.
- `mcp_status` right after `initialize` reports the stdio servers as `pending`; the first turn's `init` has the settled states (`mcp-status`, lines 2 and 6, 2.1.306).
- A refused control request is an `error` frame with an `error_code`, and the session goes on (`permission-mode-rejected`, line 17, 2.1.302). Show the text and put the UI back.
- Read, Glob and Grep raised no `can_use_tool` in my recordings: `tools-read` (2.1.302, `acceptEdits` mode) has four such calls and no `control_request` at all, and a `Read` in `default` mode (`misc-image`, line 22) raised none either. A UI that only learns of tools from permission prompts never sees them.

## The essentials

What a wrapper author needs to start, in the order you meet it.

### Spawning

The one-shot recordings were made with `claude -p "<prompt>" --output-format stream-json --verbose --include-partial-messages --safe-mode`. The bidirectional ones drop `-p` and add `--input-format stream-json --permission-prompt-tool=stdio`, plus whatever the scenario needed: `--model`, `--permission-mode`, `--thinking-display summarized`, `--disallowedTools Edit,Write,MultiEdit,NotebookEdit` (I want edits to go through the IDE), an `--mcp-config`. `--safe-mode` keeps the user's hooks, plugins and MCP servers out of the recording; the MCP scenarios drop it (it turns MCP servers off) and use `--mcp-config` with `--strict-mcp-config` instead. `--permission-prompt-tool=stdio` is what turns permission prompts into control requests on stdout.

Startup: spawn, write `control_request{initialize}`, read its answer, write the first user message, read `system/init` and so on to `result`. Keep stdin open for the whole session; closing it is the graceful shutdown.

Setting `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` makes the CLI emit `system/session_state_changed` lines (`misc-state-sdk`, 2.1.302: `running` on line 3, `idle` on line 18). The `idle` after `result` is the cleanest turn-over signal there is.

### Envelopes

One JSON object per line, UTF-8, newline-terminated. Skip blank and non-JSON lines. Single lines can be large (images travel inline as base64, `misc-image` line 26, redacted in the recording), so do not read with a small maximum line length. Parse an unknown `type` or `subtype` as unknown and keep going; never throw. Some types sit outside the SDK's message union (`autocompact_state` and `command_lifecycle` are untyped, `active_goal` is typed but not in `SDKMessage`), and they can come before `init`.

A user message on stdin:

```json
{"type":"user","session_id":"","message":{"role":"user","content":[{"type":"text","text":"ping"}]},"parent_tool_use_id":null}
```

`session_id` is `""`: the CLI owns the id. Add a `uuid`; it comes back as `user_message_uuid(s)` on `stream_event`, `assistant` and `result` lines.

### The control channel

Both directions use the same envelope (shape only; ids illustrative):

```json
{"type":"control_request","request_id":"k3j9x2a1b","request":{"subtype":"interrupt"}}
{"type":"control_response","response":{"subtype":"success","request_id":"k3j9x2a1b","response":{}}}
{"type":"control_response","response":{"subtype":"error","request_id":"k3j9x2a1b","error":"Unknown subtype"}}
```

The sender picks `request_id`. Exactly one response per request; ignore responses for ids you are not waiting on; give every request you send a timeout, because what an older CLI answers to a subtype it does not know is not in my recordings.

`initialize` is the first frame. Mine is `{"subtype":"initialize","hooks":null,"forwardSubagentText":true}`; the flag makes the CLI forward a subagent's text as `assistant` lines with an `agent_id` (`agent-forwarded`, 2.1.302). The answer carries `commands`, `agents`, `models`, `account` (display only; never store it) and `claude_code_version`.

`can_use_tool` is the request you answer most. `permission_suggestions` feeds "always allow" buttons; `requires_user_interaction:true` marks a request that needs a real answer, not a one-tap approve. Answer `{"behavior":"allow","updatedInput":{...}}`, echoing the input, plus `updatedPermissions` for a rule; or `{"behavior":"deny","message":"...","interrupt":true}`, where `interrupt:true` also aborts the turn.

`AskUserQuestion` (2.1.302, the `ask-*` recordings) is how the CLI asks you to render a question, in `default` mode too:

```json
{"type":"control_request","request_id":"<request_id-27>","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion", ...,"input":{"questions":[{"question":"Which colour?","header":"Colour","options":[{"label":"Red","description":"Red"},{"label":"Blue","description":"Blue"}],"multiSelect":false}]},"tool_use_id":"<id-12>","requires_user_interaction":true}}
```

Answer `allow` with `updatedInput` = the input plus `answers`, keyed by the exact question text. `deny` with a `message` and no `interrupt` cancels the question only (`ask-deny`, line 24: `"Error: cancelled"`) and the turn goes on. `ExitPlanMode` carries no `permission_suggestions` (`plan-approve`, line 88, 2.1.302), so the host builds the mode switch itself: I answer `allow` with `updatedPermissions:[{"type":"setMode","mode":"acceptEdits","destination":"session"}]`, and two lines later, before the tool result, comes a `system/status` with `"permissionMode":"acceptEdits"` (line 90).

Other requests in the recordings: `set_permission_mode` (answers `{"mode": ...}`, then a `system/status` line with the new `permissionMode`), `set_model` (gotcha 8), `interrupt` (gotcha 2), `mcp_status`, `mcp_toggle`, `mcp_reconnect`, `mcp_set_servers` and `mcp_authenticate`, which answers at once with an OAuth `authUrl` and a `callbackPort` and opens no browser (`mcp-status`, line 40, 2.1.306). Answer every subtype you do not handle with an `error`; never leave the CLI hanging.

### Streaming, tool results and the turn

`stream_event` wraps a raw Anthropic SSE event. Key the in-progress message on (`message.id`, `parent_tool_use_id`) and track blocks by `index`; subagent traffic interleaves. A tool's result is a `user` line with a `tool_result` block and the `tool_use_result` field (gotcha 1). `tool_result.content` is usually a string; in my recordings it is an array for a subagent hand-back (text), a tool reference and an image.

`result` comes once per turn (`post_turn_summary` just before it); `command_lifecycle`, `session_state_changed` and other `system` lines can still follow it. Turn over means `result` with `queued_turn_count == 0`, or `session_state_changed{state:"idle"}` when you enabled it. Read `is_error` and `terminal_reason` before `subtype` (gotchas 2 and 3).

### Sessions and the files on disk

A fork is `--resume <id> --fork-session`: the fork's `init` reports a new id, and its transcript starts with a copy of the original's history with every `sessionId` rewritten (`fork`, 2.1.301, inits on lines 5 and 24). The `session_id` on every line is authoritative; if a process's first `init` reports an id other than the one you expected, treat it as a new session, do not relabel.

The CLI keeps each session as an append-only JSONL file under `$CLAUDE_CONFIG_DIR` or `~/.claude`, `projects/<key>/<session id>.jsonl`. The history in it is not every line: the current history is the `parentUuid` chain ending at the last conversational line, and the chain runs through lines of any type. In my transcripts 110 of the 136 `assistant` lines have an `attachment` line as their parent, not the prompt. An interrupted reply is marked `isAbortedMidStream:true` on disk where its stdout line had `aborted:true` (`transcripts/history-interrupt.jsonl`, line 20). `result` lines are not on disk, and only some `system` ones are (`turn_duration`, `local_command`); costs survive only as a running `cost-state` line. A reloaded session has no per-turn results and no fallback or retry notices.

## How the evidence stays honest

Two scripts record against a real, signed-in CLI, from an empty directory, and copy the CLI's own transcript next to the stdout recording. A redactor runs in the same pass: paths become `<path>`, ids become numbered placeholders, and an id under a key it does not know still comes out masked, as `<leaked-N>`, with a warning naming its key path, and I read the redacted diff before committing.

The recordings are test fixtures. A small fake CLI replays one in place of the real binary, so the tests run against the recorded bytes without a sign-in. Nightly, a job records a few scenarios with the newest released CLI and decodes them with the same code. A red run means the CLI moved, and the next version of this reference says so, with the new version number on the claim.

## Close

I built this for Tandem, a plugin for Claude Code in JetBrains IDEs. It is early: not on the JetBrains Marketplace yet, and IDE edits are not built yet (it disables the CLI's own Edit and Write tools until its own edit tools land). The protocol layer is in place and tested against these recordings; that layer is what this article is about. Repository: https://github.com/daarko10/tandem.

If you are building on the claude CLI, record before you trust a type. The CLI is consistent about what it does. It just does not say it where a type definition would lead you to look.

### Links

- The full reference, with the fixture line for every claim: https://github.com/daarko10/tandem/blob/main/docs/protocol.md
- The recordings and their transcripts: https://github.com/daarko10/tandem/tree/main/src/test/resources/fixtures (for example https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/tools-bash.ndjson, https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/interrupt.ndjson, https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/invalid-model.ndjson, https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/plain-reply.ndjson, https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/permission.ndjson, https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/think-default.ndjson, https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/mcp-slash.ndjson, https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/model-switch.ndjson, https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/think-summarized.ndjson, https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/plan-disallowed.ndjson)
- The one-shot recorder: https://github.com/daarko10/tandem/blob/main/scripts/record-fixture.sh
- The scenario recorder and its driver: https://github.com/daarko10/tandem/blob/main/scripts/record-scenario.sh and https://github.com/daarko10/tandem/blob/main/scripts/drive_cli.py
- The redactor: https://github.com/daarko10/tandem/blob/main/scripts/redact_fixture.py
- The fake CLI the tests run against: https://github.com/daarko10/tandem/blob/main/src/test/resources/fake-claude.py
- The nightly contract run: https://github.com/daarko10/tandem/blob/main/.github/workflows/cli-contract.yml

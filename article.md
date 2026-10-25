# What Claude Code's CLI does over stream-json, from recorded sessions

You can drive the `claude` CLI from your own program: start it with `--output-format stream-json --input-format stream-json` and it speaks one JSON object per line over stdin and stdout. IDE plugins, wrappers, bots and CI jobs do this.

I did it from a JVM and recorded every session. Below are the ten things that surprised me. Every one comes from a real recording of a signed-in CLI (versions 2.1.300 to 2.1.308), and the recordings are public.

If you use the Agent SDK, it handles most of this for you. If you read stdout yourself, each item is a bug you would otherwise write.

## The gotchas

Each one is tagged: a **protocol rule** (how the stream is shaped), **version-specific behaviour** (may change), or an **easy consumer mistake**. Open "Evidence" under any of them for the recording and line numbers.

### 1. The tool result is JSON, unless something went wrong { #g1 }

Protocol rule.

<!-- badge 1 -->

**You'd expect** one shape per tool. **You get** an object when the tool works and a plain string when it fails.

```json
"tool_use_result": {"stdout":"out-line\nerr-line","stderr":"","interrupted":false, ...}
"tool_use_result": "Error: Exit code 3\nfailing"
```

Bonus: there is no exit code on success, and stderr arrived inside stdout. The exit code only shows up in the failure string.

**Do this:** decode `tool_use_result` as any JSON value and branch on its kind.

??? note "Evidence"
    CLI 2.1.302, `tools-bash`, lines 23 and 39. Nine recordings have the string form: failing and denied commands, a denied MCP call, a cancelled question, a rejected plan, a disabled tool. A subagent's own tool results can lack the field entirely (`agent-sync`, line 47).

### 2. Press Stop, receive an error { #g2 }

Version-specific behaviour.

<!-- badge 2 -->

**You'd expect** a polite "stopped". **You get** an error, plus a message from the user that the user never typed.

```json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}, ...}
{"type":"result","subtype":"error_during_execution","is_error":true,"terminal_reason":"aborted_streaming", ...}
```

**Do this:** treat `terminal_reason: "aborted_streaming"` as "stopped", and don't show that user line as something the user wrote.

??? note "Evidence"
    CLI 2.1.300, `interrupt`, lines 13 to 19: first the receipt (`{"still_queued":[]}`), then the cut-off `assistant` line with `"aborted":true`, then the synthetic `user` line, then a `result` with `stop_reason:null` and `result:null`. Same order on 2.1.301 (`history-interrupt`, lines 11, 13 and 17).

### 3. The model did not exist, and the turn succeeded { #g3 }

Version-specific behaviour.

<!-- badge 3 -->

Start the CLI with `--model claude-nonexistent-0`. **You'd expect** an error result. **You get** `subtype: "success"`.

```json
{"type":"result","subtype":"success","is_error":true,"terminal_reason":"api_error","api_error_status":404, ...}
```

Before it comes a reply the CLI wrote itself, signed `"model":"<synthetic>"`: "There's an issue with the selected model".

**Do this:** read `is_error` and `terminal_reason`, never `subtype`. And never show `<synthetic>` as the model that answered.

??? note "Evidence"
    CLI 2.1.302, `invalid-model`, lines 4 and 5. No stream frames at all. The `assistant` line carries `"error":"model_not_found"`, `"is_api_error_message":true` and `"api_error_status":404`.

### 4. The finished block arrives before the block has finished { #g4 }

Protocol rule.

<!-- badge 4 -->

**You'd expect** deltas, then "block stopped", then the finished block. **You get** the finished block first.

```
content_block_delta  "pong"
assistant            content=[text "pong"]   <-- the complete block
content_block_stop                           <-- arrives after it
message_stop
result
```

Not a fluke: it happens in 47 of my 50 recordings. And `stop_reason` is `null` on every model-written `assistant` line; the real stop reason and usage only come on `result`.

**Do this:** when an `assistant` line arrives, replace the in-progress block with it (match on `message.id`). Treat the late `content_block_stop` as a no-op.

??? note "Evidence"
    CLI 2.1.300, `plain-reply`, lines 7 to 15. Across the recordings: 126 `content_block_stop` frames directly after an `assistant` line; `stop_reason:null` on all 148 model-written `assistant` lines (the other 4 of 152 are the CLI's own `<synthetic>` lines).

### 5. May I? asked before the sentence ends { #g5 }

Protocol rule.

<!-- badge 5 -->

**You'd expect** the message to finish, then a permission prompt. **You get** the prompt while the message is still open.

```
assistant          [tool_use Bash]
content_block_stop
control_request    can_use_tool   <-- asks here
message_delta
message_stop
```

Answer quickly and the tool's result can even arrive before `message_delta`.

**Do this:** handle a `control_request` the moment it arrives, whatever state the message is in.

??? note "Evidence"
    CLI 2.1.300, `permission`, lines 19 to 23. `tools-bash` (lines 18 to 23) has the same order. The result before `message_delta`: CLI 2.1.302, `ask-deny`, lines 24 and 25.

### 6. Thinking, streamed in zero characters { #g6 }

Version-specific behaviour.

<!-- badge 6 -->

**You'd expect** thinking deltas to carry text. **You get** empty strings, with a token estimate attached.

```json
{"type":"thinking_delta","thinking":"","estimated_tokens":50}
```

The finished thinking block is empty too: `"thinking":""` with a signature.

**Do this:** if you want the text, pass `--thinking-display summarized`. Either way, treat an empty thinking block with a signature as normal, not as a parse error.

??? note "Evidence"
    CLI 2.1.302, `think-default`, lines 10 and 16, labelled `"thinking_display":"updates"`. Same on `think-opus` and `think-adaptive`. With the flag the deltas carry text and the finished block holds the summary (`think-display-summarized`, lines 11 and 34).

### 7. A built-in slash command runs a turn with zero turns in it { #g7 }

Version-specific behaviour.

<!-- badge 7 -->

Send `/mcp` as a message. **You'd expect** an error or a model reply. **You get** an answer from the CLI itself, dressed up as a model reply, and a result that says nothing ran.

```json
{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"3 MCP server(s): 1 connected, ..."}], ...}}
{"type":"result","num_turns":0,"duration_api_ms":0,"local_command":"mcp", ...}
```

Same for `/model` and `/help`.

**Do this:** show an `assistant` line with `"model":"<synthetic>"` and a `local_command_source` as a local notice, not a reply. And never divide by `num_turns`.

??? note "Evidence"
    CLI 2.1.306, `mcp-slash`, lines 4 to 6. No `stream_event`, and the typed line is not echoed back. The `assistant` line has `local_command_run` and 0 tokens used. `/help` has neither `local_command_run` nor `result.local_command` (lines 17 and 18).

### 8. set_model says yes, and nothing else { #g8 }

Protocol rule.

<!-- badge 8 -->

**You'd expect** the switch to be confirmed with the new model's name. **You get** a bare "success" with nothing in it.

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-25>"}}
```

The only trace is a replayed user line, and its `content` is a string, not the usual array:

```json
{"type":"user","message":{"role":"user","content":"<local-command-stdout>Set model to `claude-haiku-5-5`</local-command-stdout>"},"isReplay":true, ...}
```

**Do this:** accept `content` as a string or an array everywhere. To know which model is running, compare `init.model` and `assistant.message.model` with what you asked for.

??? note "Evidence"
    CLI 2.1.300, `model-switch`, lines 20 and 21. It is the only string `content` on stdout in my recordings; the transcripts on disk have thirteen more. Don't use `result.modelUsage` to detect the model: it also lists helper models (`plain-reply`, line 15).

### 9. The cost of this turn is the cost of every turn so far { #g9 }

Easy consumer mistake.

<!-- badge 9 -->

**You'd expect** `total_cost_usd` to be what this turn cost. **You get** the running total for the session.

```
turn 1 result: "total_cost_usd": 0.0258
turn 2 result: "total_cost_usd": 0.0341
```

**Do this:** show the latest value and never add results up. A turn's cost is the difference between two results.

??? note "Evidence"
    CLI 2.1.300, `permission`, lines 35 and 66: the second result's `modelUsage` includes the first turn's tokens. In every recording `total_cost_usd` equals the sum of `modelUsage[*].costUSD`, and both grow turn over turn (five totals in `think-summarized`, three in `mcp-status`).

### 10. The model calls the tool you took away { #g10 }

Easy consumer mistake.

<!-- badge 10 -->

Take `Write` away with `--disallowedTools`. **You'd expect** it to be gone; it isn't in `init.tools`. **You get** the model calling it anyway, and the CLI answering with an error.

```json
{"type":"tool_result","is_error":true,"content":"<tool_use_error>Error: No such tool available: Write. ...</tool_use_error>"}
```

The turn just carries on. And the same error comes in two spellings: wrapped in `<tool_use_error>` in `content`, plain in `tool_use_result`.

**Do this:** label tool calls by the `tool_use` block's `name`, not by looking them up in `init.tools`, and expect an error result for any name.

??? note "Evidence"
    CLI 2.1.302, `plan-disallowed`, lines 6 and 102 to 104. Same with `Glob` in `tools-read-unavailable` (lines 6, 48 and 49).

### Smaller ones

- **`init` is not the first line.** Other lines come first (`plain-reply`: `init` is line 4), and `init` comes again at the start of every turn.
- **One `result` can answer several messages.** Send a `uuid` with each message and match on `user_message_uuids` (`bash-mode`, 2.1.306).
- **MCP servers start as `pending`.** `mcp_status` right after start-up says `pending`; the first turn's `init` has the real states (`mcp-status`, 2.1.306).
- **A refused control request doesn't end the session.** You get an `error` frame with an `error_code`; show it and put the UI back (`permission-mode-rejected`, 2.1.302).
- **Read, Glob and Grep never asked for permission** in my recordings. A UI that only learns about tools from permission prompts never sees them (`tools-read`, `misc-image`).

## The essentials

The minimum to get a session running. The [full reference](https://github.com/daarko10/tandem/blob/main/docs/protocol.md) has the rest.

**Start it**

```
claude --output-format stream-json --input-format stream-json --verbose \
       --include-partial-messages --permission-prompt-tool=stdio
```

`--permission-prompt-tool=stdio` turns permission prompts into control requests on stdout.

**The order of a session**

1. Write the `initialize` request and read its answer (commands, models, version):
   ```json
   {"type":"control_request","request_id":"k3j9x2a1b","request":{"subtype":"initialize","hooks":null,"forwardSubagentText":true}}
   ```
2. Write a user message:
   ```json
   {"type":"user","session_id":"","message":{"role":"user","content":[{"type":"text","text":"ping"}]},"parent_tool_use_id":null}
   ```
3. Read until `result`. Keep stdin open; closing it is the clean shutdown.

**Reading the stream**

- One JSON object per line. Lines can be huge (images are inline base64), so no small line limits.
- Unknown `type` or `subtype`: skip it, never throw.
- The CLI owns the session id; send `""`. Add a `uuid` to each message to match replies.

**The control channel**

- Both directions use `control_request` and `control_response`; the sender picks `request_id`.
- Answer every request you don't handle with an `error`. Never leave the CLI waiting.
- Put a timeout on every request you send.
- To allow a tool: `{"behavior":"allow","updatedInput":{...}}`. To deny: `{"behavior":"deny","message":"..."}`; add `"interrupt":true` to also stop the turn.

**Knowing the turn is over**

`result` with `queued_turn_count` 0. Or set `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` and wait for `session_state_changed` with `idle`.

??? note "Sessions on disk, forks, AskUserQuestion and plan mode"
    - Each session is an append-only JSONL file at `~/.claude/projects/<key>/<session id>.jsonl` (or under `$CLAUDE_CONFIG_DIR`). The history is the `parentUuid` chain ending at the last conversational line, not every line. `result` lines are not on disk.
    - A fork is `--resume <id> --fork-session`. The fork's `init` reports a new id (`fork`, 2.1.301).
    - `AskUserQuestion` arrives as a `can_use_tool` request. Answer `allow` with the input plus `answers`, keyed by the exact question text. `deny` without `interrupt` cancels only the question.
    - `ExitPlanMode` brings no permission suggestions, so you build the mode switch yourself: `updatedPermissions:[{"type":"setMode","mode":"acceptEdits","destination":"session"}]` (`plan-approve`, 2.1.302).

## How the evidence stays honest

Two scripts record a real, signed-in CLI from an empty directory. A redactor replaces paths and ids before anything is saved, and I read every diff before committing it.

The recordings are test fixtures: a small fake CLI replays them, so the tests run against the recorded bytes. Every night a job records a few scenarios with the newest CLI and decodes them with the same code. When the CLI changes, that job goes red and the change gets a new version on this site.

## Close

I built this for Tandem, a plugin for Claude Code in JetBrains IDEs. It is early: not on the Marketplace yet, and its own edit tools are not built yet. The protocol layer is in place and tested against these recordings: [github.com/daarko10/tandem](https://github.com/daarko10/tandem).

If you build on the `claude` CLI, record before you trust a type. The CLI is consistent about what it does. It just doesn't say so where you would look.

### Links

- [The full reference, with the recording line for every claim](https://github.com/daarko10/tandem/blob/main/docs/protocol.md)
- [All recordings and their transcripts](https://github.com/daarko10/tandem/tree/main/src/test/resources/fixtures)
- Recorders: [record-fixture.sh](https://github.com/daarko10/tandem/blob/main/scripts/record-fixture.sh), [record-scenario.sh](https://github.com/daarko10/tandem/blob/main/scripts/record-scenario.sh) and [drive_cli.py](https://github.com/daarko10/tandem/blob/main/scripts/drive_cli.py)
- [The redactor](https://github.com/daarko10/tandem/blob/main/scripts/redact_fixture.py) and [the fake CLI the tests use](https://github.com/daarko10/tandem/blob/main/src/test/resources/fake-claude.py)
- [The nightly contract run](https://github.com/daarko10/tandem/blob/main/.github/workflows/cli-contract.yml)

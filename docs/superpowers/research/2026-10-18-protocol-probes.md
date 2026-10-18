# Protocol probes against claude 2.1.302 (wire shapes for the chat redesign)

Status: research result, 2026-10-18. Companion to `2026-10-18-claude-code-desktop-ui-reference.md` (section 6 of that file lists the open questions this one closes) and to `docs/protocol.md`.

Audience: the engineer writing the Phase 2a spec and the reducer/UI code.

How to read this file:

- Every claim cites `fixture:line` in `src/test/resources/fixtures/` (`.ndjson` is the CLI's stdout; `transcripts/<name>.jsonl` is the CLI's own on-disk transcript of the same session). Every cited file and line was opened and checked on 2026-10-18 while writing this; where a probe report's line number was off, the number here is the corrected one.
- All six probes ran `claude` 2.1.302 on `claude-sonnet-5-5` (one run on `claude-opus-5-5`) with the `scripts/drive_cli.py` argv: `--output-format stream-json --verbose --input-format stream-json --include-partial-messages --permission-prompt-tool=stdio --safe-mode`, plus the flags named per fixture. Tandem itself does not pass `--safe-mode`; nothing here is expected to depend on it, but it was not measured.
- Host-to-CLI frames (user prompts, `control_response` answers, `set_max_thinking_tokens` requests) are **not** in the fixtures, which hold stdout only. The bodies the probes sent are quoted verbatim in section 2.
- Placeholders: `<id-N>`, `<uuid-N>`, `<session_id-N>`, `<task_id-N>`, `<request_id-N>`, `<path>`, `<base64>` are the redactor's. Key order inside JSON examples is the CLI's. Examples are trimmed with `...` where the fixture line is long.
- Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

---

## 1. Fixtures recorded

29 fixtures, each with a matching `transcripts/<name>.jsonl`. Paths are relative to `src/test/resources/fixtures/`.

| Name | Flags beyond the common argv | What it shows | Key lines |
|---|---|---|---|
| `ask-single` | `--disallowedTools Edit,Write,MultiEdit,NotebookEdit --session-id <uuid>` (Tandem's argv), default mode | Single-select AskUserQuestion (Red/Blue) answered allow + `answers{"Which colour?":"Blue"}`; CLI echoes the "Your questions have been answered" text; model replies `Blue` | 6 init; 9 `content_block_start` tool_use; 10-20 `input_json_delta`; 21 assistant tool_use; 22 `can_use_tool`; 23 `content_block_stop`; 24 `message_delta`; 26 user tool_result; 31 text; 35 result. Transcript 19/20 |
| `ask-multi` | same | `multiSelect:true` (Cheese/Olives/Ham) answered with the string `"Cheese, Ham"`; echoed verbatim | 26 tool_use; 27 request; 28 stop; 29 delta; 31 tool_result; 36 text; 40 result |
| `ask-multi-array` | same | Same question answered with the array `["Cheese","Ham"]`: accepted; `tool_use_result.answers` keeps the array; model-visible text joins with a bare comma `Cheese,Ham` | 27 tool_use; 28 request; 29 stop; 30 tool_result (before `message_delta` 31); 37 text; 41 result |
| `ask-other` | same | Single-select answered with a non-label `"Green"`: accepted, cautionary "The user answered" wording | 21; 22; 23; 24; 26 tool_result; 31 text `Green`; 35 |
| `ask-response` | same | Answered with `updatedInput.response:"free text reply"` and no `answers`: "The user responded: ..." text; CLI adds `answers:{}`; model thinks (empty thinking block) then says it has no colour | 21; 22; 23; 24 tool_result (before `message_delta` 25); 29 thinking start; 35 assistant thinking; 49 text; 53 result |
| `ask-deny` | same | `behavior:"deny", message:"cancelled"`, no interrupt: `is_error` tool_result `cancelled`, turn continues, `result.permission_denials` lists the call | 21; 22; 23; 24 tool_result; 25 delta; 36 text; 40 result. Transcript 20 |
| `plan-disallowed` | `--permission-mode plan --disallowedTools Edit,Write,MultiEdit,NotebookEdit` (Tandem's argv) | Plan-file `Write` fails with a `tool_use_error` (no prompt); `ExitPlanMode` with input `{}`; plain allow leaves plan mode to `default`; model then creates the file via a Bash heredoc that prompts | 6 init (no Write); 102 Write tool_use; 103 error tool_result; 172 ExitPlanMode tool_use; 173 request; 174 stop; 177 status default; 178 tool_result; 213 Bash request; 217 Bash result; 257 result |
| `plan-approve` | `--permission-mode plan` (Write allowed) | Plan-file Write auto-approved; `ExitPlanMode` input `{plan, planFilePath}`; allow + `setMode acceptEdits` gives `system/status permissionMode acceptEdits`; next Write silent; next turn's init agrees | 5 init; 69 Write; 73 result (source config); 87 ExitPlanMode; 88 request; 89 stop; 90 status; 91 tool_result; 117/121 silent Write; 160 init acceptEdits. Transcript 3, 42 |
| `plan-deny` | `--permission-mode plan` | First `ExitPlanMode` denied with a message: `is_error` result, no status line, model rewrites the plan (Write `type:"update"`) and asks again; second allowed plainly; then Write prompts with a `setMode` suggestion | 60/64 plan Write; 78/79 ExitPlanMode + request; 81 deny result; 137/141 revised Write; 142/143 second request; 145 status default; 146 approval; 173/175 Write prompt; 219 result |
| `think-default` | Tandem's argv, no thinking flag | `thinking_display:"updates"`: empty thinking text, `estimated_tokens` only, `system/thinking_tokens` lines; 216 thinking tokens used | 1 initialize response; 7 message_start; 8 block start; 9/11/14 thinking_tokens; 10/12/13 deltas; 15 signature; 16 assistant (duration 2143 ms); 45 message_delta; 47 result |
| `think-adaptive` | `+ --thinking=adaptive` | Accepted, no change from default | 7; 10/11; 14; 37 |
| `think-display-summarized` | `+ --thinking-display summarized` | Real summary text in `thinking_delta` frames and in the assistant block | 8 message_start summarized; 10 thinking_tokens 1; 11-31 text deltas; 32 thinking_tokens 166; 33 signature; 34 assistant block text; 40 message_delta |
| `think-summarized` | Tandem's argv; control requests between turns | Turn 1 updates -> `set_max_thinking_tokens{thinking_display:"summarized"}` -> turn 2 real text -> `"full"` rejected (error text) -> `"highlights"`, `"omitted"`, `null` accepted | 8; 15; 21; 25 success; 30 summarized; 33-59 text deltas; 60 thinking_tokens 201; 62 block text; 73 error; 74; 79 highlights; 100; 105 updates; 118; 123 updates; 142 |
| `think-display-highlights` | `+ --thinking-display highlights` | Accepted (`message_start` says highlights) but no text and no `thinking_tokens` lines; 79 thinking tokens used | 8; 10; 11; 12; 18; 20 |
| `think-opus` | `--model claude-opus-5-5` | Same as Sonnet default (updates, empty text, 93 thinking tokens) | 5 init; 7; 10/11; 14; 46 |
| `tools-read` | `--permission-mode acceptEdits --tools Read,Glob,Grep,Edit,Write,Bash` | Read whole, Read offset/limit, Glob, Grep: all auto-accepted (`source:"config", reason_type:"mode"`) | 5 init (6 tools); 17/21 Read; 34/38 Read offset; 46/50 Glob; 58/62 Grep; 67 text; 71 result |
| `tools-edit` | `--permission-mode acceptEdits` | Read, Edit (golf -> GOLF), Write new file; no prompts | 6 init (no Glob/Grep/MultiEdit/TodoWrite); 40/44 Edit; 57/61 Write; 66; 70 |
| `tools-bash` | `--permission-mode acceptEdits` | Bash exit 0 with stderr, Bash exit 3; both prompt (`decision_reason_type:"subcommandResults"`), allowed | 1 ui_invalidate; 3 lifecycle; 7 rate_limit_event; 18/20/23 ok; 34/36/39 error; 44; 48 result; 49 lifecycle |
| `tools-read-unavailable` | `--permission-mode acceptEdits` (default tool set) | Glob call fails `No such tool available` (Glob is not in the default 2.1.302 tool set); model thinks, falls back to Bash `find` and `grep` (auto-approved) | 6 init; 48/49 Glob error (no `tool_result_meta`); 56-59 thinking_tokens/deltas; 73/79 find; 82/86 grep; 95 result |
| `agent-sync` | `--permission-mode acceptEdits --disallowedTools ...` (Tandem's argv) | Foreground `Agent` subagent: `task_started/progress/updated/notification`, subagent lines with `parent_tool_use_id` + `agent_id`, a `can_use_tool` with `agent_id`, final Agent tool_result with totals; subagent text NOT forwarded | 2 initialize; 6 init; 35 Agent tool_use; 37 task_started; 38 subagent prompt echo; 41/45 task_progress; 42 subagent Bash; 43 request with agent_id; 44 subagent error result; 46/47; 48 task_updated; 49 task_notification; 50 Agent tool_result; 61 text; 65 result |
| `agent-forwarded` | same, `initialize{forwardSubagentText:true}` | Same run; the subagent's final text block IS emitted as an assistant line with `agent_id` | 2; 29; 31; 32; 35; 36/37; 38 forwarded text `7`; 39; 40; 41; 50; 54 |
| `agent-todos` | same, env `CLAUDE_CODE_ENABLE_TODO_TOOLS=1` | init gains `TaskCreate/TaskGet/TaskList/TaskUpdate` (deferred: loaded via `ToolSearch`); TaskCreate x3, TaskUpdate in_progress/completed | 5 init; 16/18 ToolSearch (`tool_reference` blocks); 31/35 TaskCreate; 61/63 and 73/77 TaskUpdate; 82; 86 |
| `agent-bash-task` | same | A plain 6 s Bash tool also emits `task_started` (`task_type:"local_bash"`) and `task_notification` | 17 tool_use; 21 task_started; 22 task_notification; 23 result; 32 |
| `misc-image` | default mode | Read of an 8x8 PNG: image block in `tool_result.content`, `tool_use_result{type:"image", file{dimensions}}`; model runs `file` because it cannot see the size | 4 rate_limit_event; 6 init; 9 block start; 22 Read; 24 delta; 26 image result; 28-35 empty thinking; 46/50 Bash `file`; 62 text; 66 result |
| `misc-web` | default mode, two turns | WebFetch then WebSearch, each preceded by a `ToolSearch` round-trip (deferred tools), each prompting | 15/17 ToolSearch; 29 WebFetch; 31 request; 34 result; 44 result; 45-48 lifecycle + init; 57/59 ToolSearch; 73 WebSearch; 74 request; 78 result; 105 result |
| `misc-state-emit` | env `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` | `system/session_state_changed` running/idle without `sdk_host_only` | 2 initialize; 3 running; 5 rate_limit_event; 9 message_start; 14 message_delta; 16 result; 17 lifecycle; 18 idle |
| `misc-state-sdk` | env `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` | Same with `sdk_host_only:true` | 3; 18 |
| `misc-state-none` | neither | No `session_state_changed` at all | 4; 6; 13; 15; 16 (last line) |
| `misc-state-action` | `CLAUDE_CODE_SDK_READS_SESSION_STATE=1`, one Bash prompt | `requires_action` right before the `can_use_tool`, `running` after the answer; `rate_limit_event` twice mid-turn | 3; 8; 18; 20 requires_action; 21 request; 22/23 delta/stop; 24 running; 25 result; 34 rate_limit; 35; 37 idle |

Lines that every probe saw and that are already in older fixtures: `command_lifecycle {command_uuid, state:"queued"|"started"|"completed"}` around each turn, `system/status {status:"requesting"}` before each API call, `system/ui_invalidate` first, `rate_limit_event` (section 2.6). No fixture contains `system/task_summary`, `tool_progress`, `background_tasks_changed`, `system/informational` or `system/notification` (grep over all 29 files: 0; the only `task_summary` in the repo is `permission.ndjson`, recorded with an older CLI).

---

## 2. Answers

### 2.1 AskUserQuestion

**Request (CLI -> host).** `ask-single:22`. Exactly these keys: `subtype, tool_name, display_name, input, tool_use_id, requires_user_interaction`. No `permission_suggestions`, `decision_reason(_type)`, `title`, `description` or `blocked_path`. Arrives in `default` mode even though the tool is read-only: it is how the CLI asks the host to render the question.

```json
{"type":"control_request","request_id":"<request_id-27>","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","display_name":"AskUserQuestion",
 "input":{"questions":[{"question":"Which colour?","header":"Colour","options":[{"label":"Red","description":"Red"},{"label":"Blue","description":"Blue"}],"multiSelect":false}]},
 "tool_use_id":"<id-12>","requires_user_interaction":true}}
```

The `tool_use` block on the assistant line (`ask-single:21`) has the same input in the model's key order (`question, header, multiSelect, options`) while the request uses the CLI's schema order (`question, header, options, multiSelect`): compare by value, never by text. Every `tool_use` block in 2.1.302 carries `"caller":{"type":"direct"}` (also on `content_block_start`, `ask-single:9`); the assistant line also carries `wire_tool_inputs:{"<id-12>":<input>}`, `user_message_uuid(s)` and `request_id`.

**Host answers (what the probes sent; not in the fixtures).**

```json
// allow with answers, keyed by the exact question text; value = label, or ", "-joined labels, or an array, or free text
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-27>","response":{"behavior":"allow",
 "updatedInput":{"questions":[/* unchanged */],"answers":{"Which colour?":"Blue"}},"toolUseID":"<id-12>"}}}
// allow with a free-text reply instead of answers
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-27>","response":{"behavior":"allow",
 "updatedInput":{"questions":[/* unchanged */],"response":"free text reply"},"toolUseID":"<id-12>"}}}
// deny (cancel), no interrupt
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-27>","response":{"behavior":"deny","message":"cancelled","toolUseID":"<id-12>"}}}
```

**User line after an answer.** `ask-single:26`. No `is_error` key on success. `tool_use_result` is exactly the `updatedInput` the host sent; `tool_result_meta[].permission_decision` is `{decision:"accept", source:"user_temporary"}`.

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"Your questions have been answered: \"Which colour?\"=\"Blue\". You can now continue with these answers in mind.","tool_use_id":"<id-12>"}]},
 "parent_tool_use_id":null,"session_id":"<session_id-2>","uuid":"<uuid-31>","timestamp":"2026-10-18T20:02:24.250Z",
 "tool_use_result":{"questions":[/* ... */],"answers":{"Which colour?":"Blue"}},
 "tool_result_meta":[{"id":"<id-12>","permission_decision":{"decision":"accept","source":"user_temporary"}}]}
```

Text variants of `tool_result.content`:

| Host sent | `content` | `tool_use_result` | Fixture |
|---|---|---|---|
| labels only (single) | `Your questions have been answered: "Which colour?"="Blue". You can now continue with these answers in mind.` | `{questions, answers:{"Which colour?":"Blue"}}` | `ask-single:26` |
| multi, string `"Cheese, Ham"` | same wording, `..."="Cheese, Ham".` | `answers` keeps the string | `ask-multi:31` |
| multi, array `["Cheese","Ham"]` | same wording, `..."="Cheese,Ham".` (bare comma: JS `Array.toString`) | `answers` keeps the **array** | `ask-multi-array:30` |
| non-label `"Green"` (the "Other" case) | `The user answered: "Which colour?"="Green". Read the answers carefully — they may request clarification, changes, or that you not proceed — and follow what they actually say.` (U+2014 dashes) | `answers:{"Which colour?":"Green"}` | `ask-other:26` |
| `response` only | `The user responded: free text reply` | `{questions, answers:{}, response:"free text reply"}` (CLI inserts `answers:{}`) | `ask-response:24` |
| deny `"cancelled"` | `cancelled`, with `is_error:true` | the **string** `"Error: cancelled"` | `ask-deny:24` |

**User line after a deny.** `ask-deny:24`: block `{type:"tool_result", content:"cancelled", is_error:true, tool_use_id}`, `tool_use_result:"Error: cancelled"` (a string, not an object), `tool_result_meta:[{id, non_execution_kind:"permission-rule", permission_decision:{decision:"reject", source:"user_reject"}}]`. The turn goes on: the model replied `The question was cancelled, so you didn't pick a colour. Do you want me to ask again?` (`:36`); `result` is `subtype:"success", is_error:false` with `permission_denials:[{tool_name:"AskUserQuestion", tool_use_id, tool_input:{questions:[...]}}]` (`:40`).

**Transcript on disk.** `transcripts/ask-single.jsonl:20`: the same `tool_use_result` under `toolUseResult`, and the stream's `tool_result_meta[0].permission_decision` becomes a top-level `permissionDecision`; `sourceToolAssistantUUID` points at the assistant line. Deny (`transcripts/ask-deny.jsonl:20`): `toolUseResult:"Error: cancelled"`, `toolDenialKind:"permission-rule"`, `permissionDecision:{reject, user_reject}`.

**Stream order (6 of 6 runs).** `content_block_start(tool_use, input:{})` -> `input_json_delta` x N (first fragment is `""`, `ask-single:10`) -> complete `assistant` line -> **`control_request can_use_tool`** -> `content_block_stop` -> `message_delta(stop_reason:"tool_use")` and the user `tool_result` **in either order** -> `message_stop` -> `system/status`. The tool_result landed before `message_delta` in 3 of 6 runs because the probe answered instantly (`ask-multi-array:30/31`, `ask-response:24/25`, `ask-deny:24/25`). So: the card never has to wait for stream reconciliation (the request carries the full input), but the UI must not key "question open" on the message being closed, and the reducer must accept a tool result for a message whose `message_delta`/`message_stop` has not arrived.

**Direct answers.**

1. Round trip (allow + answers): as above; model replied `Blue` (`ask-single:31`).
2. multiSelect: **both** the `", "`-joined string and an array are accepted (`ask-multi:31`, `ask-multi-array:30`). The CLI's own TUI sends the `", "`-joined string (read from the 2.1.302 bundle: labels joined with `", "`, a label containing `", "` or `"` is quoted; a string answer is parsed back and must re-join to the original with every part an option label to get the "answered" wording; an array passes when `multiSelect` is true, non-empty and all elements are labels). Recommendation: send the `", "`-joined string, which matches the TUI and gives the model `", "`-separated labels.
3. Free text: a non-label value in `answers` is accepted and gets the cautionary wording (`ask-other:26`); `updatedInput.response` is accepted and gets `The user responded:` (`ask-response:24`). The bundle checks a non-blank `response` **before** `answers`, so `response` wins if both are sent (read from code, not recorded).
4. Deny: as above; without `interrupt:true` the turn continues (`ask-deny:24-40`).

**Unknown.** More than one question per call (how several `"Q"="A"` pairs are joined); `response` together with `answers`; deny with `interrupt:true`; the newer per-question fields the bundle handles but the model did not emit (`questions[].kind` `"text"|"number"` with min/max, and a per-question notes field with the sentinel answer `(notes only)`); the TUI's exact quoting of a label containing `", "`.

### 2.2 Plan mode and ExitPlanMode

**With Tandem's argv (`--permission-mode plan --disallowedTools Edit,Write,MultiEdit,NotebookEdit`).** `plan-disallowed`. The init tools list omits the four disallowed tools (`:6`). The model still issues a `Write` of the plan file under `~/.claude/plans/<slug>.md` (`:102`) and gets a registry-level error, not a permission denial:

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"<tool_use_error>Error: No such tool available: Write. Write is disabled for this session, in subagents as well as here.</tool_use_error>","is_error":true,"tool_use_id":"<id-21>"}]},
 "parent_tool_use_id":null,"session_id":"<session_id-2>","uuid":"<uuid-108>","timestamp":"...",
 "tool_use_result":"Error: No such tool available: Write. Write is disabled for this session, in subagents as well as here."}
```

No `can_use_tool`, no `system/permission_denied`, no `tool_result_meta`, and `result.permission_denials` stays `[]` (`:103`, `:257`). The model then loads `ExitPlanMode` through `ToolSearch` and calls it with **empty input** (`:172`); the request is:

```json
{"type":"control_request","request_id":"<request_id-182>","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","display_name":"ExitPlanMode","input":{},"tool_use_id":"<id-178>","requires_user_interaction":true}}
```

A plain allow (`behavior:"allow", updatedInput:{}`) is followed by `system/status {status:null, permissionMode:"default"}` (`:177`) and the tool_result `User has approved exiting plan mode. You can now proceed.` with `tool_use_result:{plan:null, isAgent:false, filePath:"<path>"}` (`:178`; `filePath` is the plan path the CLI chose even though the file was never written). The model then created `CHANGELOG.md` through a Bash heredoc that prompted with `permission_suggestions:[{addRules Bash "cat > *" localSettings}, {addDirectories [...] session}]` and `blocked_path` (`:213`).

**With Write allowed (`--permission-mode plan` only).** `plan-approve`, `plan-deny`. The plan-file Write is auto-approved in plan mode with no prompt: `tool_result_meta.permission_decision {decision:"accept", source:"config", reason_type:"other"}` (`plan-approve:73`, `plan-deny:64`). `ExitPlanMode` then carries the plan:

```json
{"type":"control_request","request_id":"<request_id-97>","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","display_name":"ExitPlanMode",
 "input":{"plan":"# Plan: Add CHANGELOG.md\n\n## Context\n...\n## Steps\n1. ...\n2. ...\n\n## Verification\n...\n","planFilePath":"<path>"},
 "tool_use_id":"<id-92>","requires_user_interaction":true}}
```

Exactly these keys in all four `ExitPlanMode` requests recorded (`plan-approve:88`, `plan-deny:79,143`, `plan-disallowed:173`): **never** `permission_suggestions`, so there is no CLI-provided "approve and switch to acceptEdits" suggestion; the host builds the `setMode` update itself. `input.plan` equals the file content the model wrote (`plan-approve:69` vs `:87`); the CLI does not read the file back.

Host allow with mode switch (what the probe sent):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-97>","response":{"behavior":"allow","updatedInput":{"plan":"...","planFilePath":"<path>"},"toolUseID":"<id-92>",
 "updatedPermissions":[{"type":"setMode","mode":"acceptEdits","destination":"session"}]}}}
```

The CLI answers with the status line **before** the tool result (`plan-approve:90`, `:91`):

```json
{"type":"system","subtype":"status","status":null,"permissionMode":"acceptEdits","uuid":"<uuid-99>","session_id":"<session_id-2>"}
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"User has approved your plan. You can now start coding. Start with updating your todo list if applicable\n\nYour plan has been saved to: <path>\nYou can refer back to it if needed during implementation.\n\n## Approved Plan:\n# Plan: Add CHANGELOG.md\n...","tool_use_id":"<id-92>"}]},
 "tool_use_result":{"plan":"# Plan: ...","isAgent":false,"filePath":"<path>"},
 "tool_result_meta":[{"id":"<id-92>","permission_decision":{"decision":"accept","source":"user_temporary"}}]}
```

Without `updatedPermissions` the status line says `permissionMode:"default"` (`plan-deny:145`, `plan-disallowed:177`). The next turn's `system/init` reports the switched mode (`plan-approve:160` `acceptEdits`; `transcripts/plan-approve.jsonl:42` user line `permissionMode:"acceptEdits"`). A project `Write` after the switch is silent with `reason_type:"mode"` (`plan-approve:121`); in `default` mode it prompts with `permission_suggestions:[{type:"setMode", mode:"acceptEdits", destination:"session"}]` and `description` = file basename (`plan-deny:175`).

**Deny with feedback.** `plan-deny:81`: `{content:"No, keep planning: also add a date", is_error:true}`, `tool_use_result:"Error: No, keep planning: also add a date"`, `tool_result_meta:[{non_execution_kind:"permission-rule", permission_decision:{reject, user_reject}}]`. **No `system/status` follows** (the only status-with-permissionMode line in that file is `:145`, after the second, allowed request), so the session stays in plan mode and the turn continues: the model rewrote the plan file (`:137`, Write `tool_use_result.type:"update"` with one `structuredPatch` hunk and `originalFile`, `:141`) and called `ExitPlanMode` again (`:142/143`). `result.permission_denials` lists the first call with `tool_input:{plan, planFilePath}` (`:219`, `num_turns:7`).

**Plan files.** `~/.claude/plans/<slug>.md`, where `slug` is the transcript user line's `slug` field (`transcripts/plan-approve.jsonl:3`: `make-a-two-step-plan-reflective-micali`): first words of the prompt plus two random words. Content is model-written Markdown (here `# Plan: ...` / `## Context` / `## Steps` / `## Verification`; an older recording used H1s, so headings are not fixed) and names the absolute project path, so treat it as user content.

**Unknown.** `EnterPlanMode` from a non-plan session; deny with `interrupt:true`; subagent (`isAgent:true`) plans; whether repeated denies ever end the turn on their own; a permission rule that allows `Write` only under the plans directory (untested alternative to dropping `Write` from `--disallowedTools`).

### 2.3 Thinking

**Default (Tandem's argv, no flag): no text anywhere.** `think-default`. `message_start` carries a wrapper-level `thinking_display:"updates"` (`:7`); `thinking_delta` frames have `thinking:""` plus a non-standard `estimated_tokens` (int on the first deltas, `null` on the last before `signature_delta`, `:10,12,13`); `system/thinking_tokens` lines interleave (`:9,11,14`: 50, 150, 285); the assistant line's block is `{type:"thinking", thinking:"", signature:"..."}` and the line carries `thinking_duration_ms:2143` and `thinking_display:"updates"` (`:16`). 216 thinking tokens were used (`:45`). `--thinking=adaptive` (`think-adaptive`) and Opus (`think-opus`) behave the same.

```json
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"","estimated_tokens":50}},"session_id":"<session_id-4>","parent_tool_use_id":null,"uuid":"<uuid-13>","thinking_display":"updates","api_message_id":"<id-9>"}
{"type":"system","subtype":"thinking_tokens","estimated_tokens":166,"estimated_tokens_delta":145,"session_id":"<session_id-2>","uuid":"<uuid-13>","user_message_uuid":"<command_uuid-4>"}
```

**Summarized: the one that carries text.** Two ways, same output:

- Spawn flag `--thinking-display summarized` (hidden from `--help`; `think-display-summarized`). `message_start.thinking_display:"summarized"` (`:8`); each `thinking_delta` carries a text chunk with `estimated_tokens:null` (`:11` `"47"`, `:13` `"×"`, ... `:31` `"\n\n"`), each preceded by a `system/thinking_tokens` line whose delta is the chunk's token count (`:10` = 1); one big jump right before `signature_delta` (`:32`: 166, delta 145) accounts for the hidden thinking; the assistant block holds the whole summary (`:34`):

```json
{"type":"assistant","message":{"model":"claude-sonnet-5-5","id":"<id-10>","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"47×83 gives 3901, and 19² is 361, so subtracting leaves 3540.\n\n","signature":"CAQS..."}],"stop_reason":null,"...":"..."},
 "parent_tool_use_id":null,"session_id":"<session_id-2>","uuid":"<uuid-39>","thinking_duration_ms":1991,"thinking_display":"summarized","api_message_id":"<id-10>"}
```

- Control request after `initialize` (`think-summarized`): `{"type":"control_request","request_id":"host-29","request":{"subtype":"set_max_thinking_tokens","thinking_display":"summarized"}}` -> `{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-29>"}}` (`:25`; **no `response` field**, contrary to `docs/protocol.md` 4.4's `{}`). Takes effect on the next turn (`:30` summarized, `:33-59` text deltas, `:62` block text `I recall that the number of compositions of n using only odd parts follows the Fibonacci sequence, ...`).

Other values (`think-summarized`): `"full"` -> error `set_max_thinking_tokens: max_thinking_tokens must be an integer or null and thinking_display must be "summarized", "omitted", "highlights", or null` (`:73`); `"highlights"` -> success, next `message_start` says `highlights` (`:74`, `:79`; that turn had 0 thinking tokens); `"omitted"` -> success, next `message_start` says **`updates`** (`:100`, `:105`), so a host cannot read back that it asked for omitted; `null` -> success, `updates` (`:118`, `:123`). With the spawn flag `--thinking-display highlights` the model thought 79 tokens but the stream had one empty `thinking_delta` (`estimated_tokens:null`), no `thinking_tokens` lines and an empty block (`think-display-highlights:10,12,18`): highlights is not usable over stdio on this account.

On disk, the transcript keeps whatever was streamed: the summary text in `transcripts/think-display-summarized.jsonl:18`, `thinking:""` with a signature in `transcripts/think-default.jsonl:19`. A session resumed from a transcript can show summaries only if it was summarized when recorded.

**Direct answers.** (a) Default: no text; `updates`; `estimated_tokens` and `system/thinking_tokens` only. (b) `--thinking=adaptive` changes nothing; `--thinking-display summarized` gives text at spawn. (c) `set_max_thinking_tokens{thinking_display:"summarized"}` gives text from the next turn; accepted set is `summarized | omitted | highlights | null`; error text above. (d) Opus: identical to Sonnet.

**Unknown.** Highlights text shape (never seen; the bundle says highlights is accepted only for Anthropic-hosted sessions and otherwise silently becomes omitted); summarized on Opus (not recorded, expected identical); `max_thinking_tokens` together with `thinking_display`; whether `--safe-mode` matters (not expected).

### 2.4 Tool result shapes per tool

Common to every tool: the `user` line is `{type:"user", message:{role:"user", content:[{tool_use_id, type:"tool_result", content, is_error?}]}, parent_tool_use_id, session_id, uuid, timestamp, tool_use_result?, tool_result_meta?}`. `is_error` is **absent** on Read/Glob/Grep/Edit/Write/Agent/ToolSearch/AskUserQuestion successes, `false` on Bash success, `true` on errors; success blocks are keyed `{tool_use_id, type, content}` and error blocks `{type, content, is_error, tool_use_id}` (`tools-read:21` vs `tools-bash:39`). On any error, `tool_use_result` is the **string** `"Error: " + content` (`tools-bash:39`, `tools-read-unavailable:49`, `ask-deny:24`, `plan-deny:81`), so decode it as a `JsonElement`. `tool_result_meta` is `[{id:<tool_use id>, permission_decision:{decision, source, reason_type?}}]` with `source:"config"` + `reason_type:"mode"` for mode auto-accepts, `source:"config"` + `reason_type:"subcommandResults"` for auto-approved read-only Bash, `source:"config"` + `reason_type:"other"` for the plan-file Write, `source:"config"` without `reason_type` for ToolSearch/Agent/TaskCreate, and `source:"user_temporary"` (no `reason_type`) after a host allow; it is **missing** on the unknown-tool error (`tools-read-unavailable:49`) and on the subagent's successful Bash results (`agent-sync:47`, `agent-forwarded:37`, which also lack `tool_use_result`).

| Tool | Input seen | `tool_result.content` | `tool_use_result` | Fixture |
|---|---|---|---|---|
| Read | `{file_path}` / `{file_path, offset:10, limit:5}` | `cat -n` style `N\tline` text | `{type:"text", file:{filePath, content (raw, no numbers), numLines:31, startLine:1, totalLines:31}}`; offset run `numLines:5, startLine:10, totalLines:31`. A 30-line file with a trailing newline counts 31 lines | `tools-read:21`, `:38` |
| Read (image) | `{file_path}` | **array** `[{type:"image", source:{type:"base64", data:"<base64>", media_type:"image/png"}}]`, no text block | `{type:"image", file:{base64:"<base64>", type:"image/png", originalSize:80, dimensions:{originalWidth:8, originalHeight:8, displayWidth:8, displayHeight:8}}}` | `misc-image:26` |
| Glob | `{pattern:"*.txt"}` | newline-joined names (`seed.txt`) | `{filenames:["seed.txt"], durationMs:14, numFiles:1, truncated:false, totalMatches:1, countIsComplete:true}` | `tools-read:50` |
| Grep | `{pattern:"seven", output_mode:"content"}` | `seed.txt:29:line 29 seven` | `{mode:"content", numFiles:0, filenames:[], content:"seed.txt:29:line 29 seven", numLines:1, totalLines:1}` (content mode leaves `numFiles` 0) | `tools-read:62` |
| Edit | `{replace_all:false, file_path, old_string, new_string}` | `The file <path> has been updated successfully. (file state is current in your context — no need to Read it back)` | `{filePath, oldString, newString, originalFile:<full pre-edit text>, structuredPatch:[{oldStart:4, oldLines:7, newStart:4, newLines:7, lines:[" line 04 delta", ..., "-line 07 golf", "+line 07 GOLF", ...]}], userModified:false, replaceAll:false}`; **no `gitDiff`** (not a git repo) | `tools-edit:44` |
| Write (new) | `{file_path, content}` | `File created successfully at: <path> (file state is current in your context — no need to Read it back)` | `{type:"create", filePath, content, structuredPatch:[], originalFile:null, userModified:false}` | `tools-edit:61` |
| Write (existing) | same | `The file <path> has been updated successfully. (...)` | `{type:"update", filePath, content, structuredPatch:[<1 hunk>], originalFile:<text>, userModified:false}` | `plan-deny:141` |
| Bash ok | `{command, description}` | merged stdout+stderr `out-line\nerr-line`, `is_error:false` | `{stdout:"out-line\nerr-line", stderr:"", interrupted:false, isImage:false, noOutputExpected:false}`: stderr is merged into stdout, `stderr` stays `""`, no exit code | `tools-bash:23` |
| Bash exit 3 | same | `Exit code 3\nfailing`, `is_error:true` | string `"Error: Exit code 3\nfailing"`: the exit code exists only as the first text line | `tools-bash:39` |
| Bash no output | | `(Bash completed with no output)` | `{stdout:"", stderr:"", ..., noOutputExpected:true}` | `misc-state-action:25` |
| Unknown tool | `Glob` without `--tools` | `<tool_use_error>Error: No such tool available: Glob. Glob is not available in this session — find files with \`find\` via the Bash tool instead.</tool_use_error>`, `is_error:true` | bare string `"Error: No such tool available: Glob. ..."`; **no `tool_result_meta`** | `tools-read-unavailable:49` |
| ToolSearch | `{query:"select:WebFetch", max_results:1}` | **array** `[{type:"tool_reference", tool_name:"WebFetch"}]` | `{matches:["WebFetch"], query:"select:WebFetch", total_deferred_tools:18}` | `misc-web:17`, `agent-todos:18` |
| WebFetch | `{url, prompt}` | the model's answer string | `{bytes:577, code:200, codeText:"OK", result:"The title of this page is \"Example Domain.\"", durationMs:1589, url}` | `misc-web:34` |
| WebSearch | `{query, mode:"standard"}` | one string: `Web search results for query: "..."\n\nLinks: [{"title":..,"url":..},...]\n\n<summary markdown>` | `{query, results:[{tool_use_id:"<tool_use_id-96>", content:[{title, url}, ...]}, "<summary string>"], durationSeconds:5.446..., searchCount:1}` (heterogeneous list: dicts and strings) | `misc-web:78` |
| Agent | `{description, subagent_type, prompt, run_in_background:false}` | array with one text block: the harness-framed hand-back (`[Subagent hand-back] ... agentId: ... <usage>...</usage>`) | `{status:"completed", prompt, agentId, agentType, harnessNoteCount, harnessTailCount, harnessSectionHash, content:[{type:"text", text:"7 lines. ..."}], resolvedModel, totalDurationMs:5829, totalTokens:21781, totalToolUseCount:2, usage{...}, toolStats:{readCount, searchCount, bashCount, editFileCount, linesAdded, linesRemoved, otherToolCount}, canContinueAgent:true}` | `agent-sync:50` |
| TaskCreate | `{subject, description}` | `Task #1 created successfully: First task` | `{task:{id:"1", subject}}` (the fixture shows `id:"<id-45>"`: the redactor placeholders every `id` key; the raw value was `"1"`, and `TaskUpdate`'s `taskId:"1"` is kept) | `agent-todos:35` |
| TaskUpdate | `{taskId:"1", status:"in_progress"}` | `Updated task #1 status` | `{success:true, taskId:"1", updatedFields:["status"], statusChange:{from:"pending", to:"in_progress"}}` | `agent-todos:63`, `:77` |
| ExitPlanMode | see 2.2 | see 2.2 | `{plan, isAgent:false, filePath}` | `plan-approve:91` |
| AskUserQuestion | see 2.1 | see 2.1 | `{questions, answers, response?}` | `ask-single:26` |

The 2.1.302 **default tool set has no Glob, Grep, MultiEdit, TodoWrite or LS** (`tools-edit:6`, `tools-read-unavailable:6`, `agent-sync:6`; with `--tools Read,Glob,Grep,Edit,Write,Bash` the init lists exactly those six, `tools-read:5`). The model still calls Glob, gets the unknown-tool error, thinks, and falls back to Bash `find`/`grep -rn`, which auto-approve with `reason_type:"subcommandResults"` (`tools-read-unavailable:48-86`). Tandem's `--disallowedTools` names `MultiEdit`, which no longer exists (harmless).

`can_use_tool` for Bash in acceptEdits (`tools-bash:20`): keys `subtype, tool_name, display_name, input, description, permission_suggestions, decision_reason_type:"subcommandResults", tool_use_id`; the suggested rule names only the sub-command that was not auto-allowed (`exit 0`), not the whole line; no `title`, `blocked_path` or `requires_user_interaction`. In default mode the request adds `blocked_path` and three suggestions `addRules` / `addDirectories` / `setMode acceptEdits` (`misc-state-action:21`).

**Unknown.** `Edit.gitDiff` inside a git repo; Grep `files_with_matches` / `count` modes; Read of PDFs/notebooks; Bash `run_in_background` and `timedOutAfterMs`; error shapes for WebFetch/WebSearch (nothing failed).

### 2.5 Subagents, todos and task events

**Parent call.** The `tool_use` block is named `Agent` (`agent-sync:35`) although `system/init.tools` lists `Task` (`:6`): match both. Its `message_delta`/`message_stop` arrive **after** `task_started` and the subagent's first line.

**Lifecycle lines** (`agent-sync:37,41,48,49`), all `type:"system"`:

```json
{"type":"system","subtype":"task_started","task_id":"<task_id-42>","run_id":"0mve9in4g-5b530e91","tool_use_id":"<id-12>","description":"Count lines in seed.txt","subagent_type":"general-purpose","is_backgrounded":false,"spawn_depth":1,"task_type":"local_agent","prompt":"...","uuid":"<uuid-43>","session_id":"<session_id-2>"}
{"type":"system","subtype":"task_progress","task_id":"<task_id-42>","run_id":"0mve9in4g-5b530e91","tool_use_id":"<id-12>","description":"Running wc -l seed.txt && tail -c1 seed.txt | xxd","subagent_type":"general-purpose","usage":{"total_tokens":21510,"tool_uses":1,"duration_ms":2006},"last_tool_name":"Bash","uuid":"<uuid-47>","session_id":"<session_id-2>"}
{"type":"system","subtype":"task_updated","task_id":"<task_id-42>","run_id":"0mve9in4g-5b530e91","patch":{"status":"completed","end_time":1792354381129},"uuid":"<uuid-60>","session_id":"<session_id-2>"}
{"type":"system","subtype":"task_notification","task_id":"<task_id-42>","run_id":"0mve9in4g-5b530e91","tool_use_id":"<id-12>","status":"completed","output_file":"<path>","summary":"7 lines. The last line ends with a trailing newline.","usage":{"total_tokens":21826,"tool_uses":2,"duration_ms":5826},"uuid":"<uuid-61>","session_id":"<session_id-2>"}
```

`task_id` equals the `agent_id` of every subagent line and `tool_use_result.agentId`; `tool_use_id` is the Agent block id; `task_updated` has no `tool_use_id` (correlate by `task_id`). `task_progress` comes once per subagent tool call, **before** that call's assistant line, with `description:"Running <command>"`. `task_notification.summary` is the subagent's final text.

**A plain Bash tool is also a task.** `agent-bash-task:21-22`: a 6 s foreground Bash emits `task_started {task_type:"local_bash", description:<Bash description>, is_backgrounded:false}` (no `subagent_type`, `spawn_depth` or `prompt`) and `task_notification {status:"completed", output_file:"", summary:<Bash description>}` (no `usage`), with no `task_progress`/`task_updated`. Key subagent UI on `task_type` (`local_agent`) or `subagent_type`, not on `task_started` alone.

**Subagent lines.** Every subagent `assistant`/`user` line carries `parent_tool_use_id:<Agent block id>`, `agent_id:<task_id>`, `subagent_type`, `task_description` (`agent-sync:38,42,44,46,47`); the first is a user text line echoing the prompt (`:38`). Subagent `tool_use` and `tool_result` lines are always forwarded; no `stream_event` frames come from a subagent in either mode; subagent assistant lines have no `wire_tool_inputs`/`user_message_uuid`. A permission prompt from inside the subagent is a normal `can_use_tool` plus `agent_id` (`agent-sync:43`); after the allow its result has `source:"user_temporary"` (`:44`).

**`forwardSubagentText`.** `initialize{subtype:"initialize", hooks:null, forwardSubagentText:true}` is accepted (success, not echoed; `agent-forwarded:2`). The only difference: the subagent's final text block is emitted as an assistant line with `parent_tool_use_id`/`agent_id` (`agent-forwarded:38`, text `7`); `agent-sync` has no such line, so without the flag that text is visible only in `task_notification.summary` and `tool_use_result.content`. The initialize response's outer envelope also carries `pending_permission_requests:[]` and `pending_user_dialog_requests:[]` beside `response` (`agent-sync:2`, `think-default:1`, `misc-state-emit:2`).

**Agent result** (`agent-sync:50`): render `tool_use_result.content[].text`, not the model-visible hand-back frame; `tool_result_meta` says `{accept, source:"config"}` (the Agent call itself never prompted in acceptEdits). `result.subagent_stats` (`:65`): `{spawned:1, requested:{background:0, foreground:1, unset:0}, started_in_background:0, max_depth:1, spawned_by_subagents:0, completed:1, failed:0, killed:{parent, user, system}, refused:{depth_limit, concurrency_limit, budget}, by_type:{"general-purpose":1}}`; `num_turns` counts main-thread rounds only (2).

**Todos.** Without the env var neither Sonnet 5.5 nor Opus 5.5 lists `TodoWrite`, `TaskCreate` or `TaskUpdate` (`agent-sync:6`; the Opus check compared `init.tools` only and is not a fixture). With `CLAUDE_CODE_ENABLE_TODO_TOOLS=1` the init adds `TaskCreate, TaskGet, TaskList, TaskUpdate` (`agent-todos:5`); `TodoWrite` does not exist in 2.1.302. They are deferred: the model first calls `ToolSearch select:TaskCreate,TaskUpdate` (`:16/18`, `total_deferred_tools:21`). Inputs/results are in the 2.4 table; no system lines accompany them, so the list is reconstructed from TaskCreate/TaskUpdate results.

**Unknown.** Background subagents (`run_in_background:true`: `background_tasks_changed`, `task_notification` for a backgrounded task, `TaskStop`, the `SendMessage` continuation that `canContinueAgent` promises); `tool_progress` (never seen, even for the 6 s Bash); what triggers `system/task_summary` in 2.1.302 (none in 29 sessions); Opus with the todo env var; subagent transcripts live at `<config>/projects/<key>/<session id>/subagents/agent-<agent id>.jsonl` with `isSidechain:true` and are not copied into fixtures.

### 2.6 Images, web tools, session-state events, usage lines

**Images.** Shape in 2.4. `dimensions` is present with all four fields. Read of an image needed no prompt in default mode (`reason_type:"mode"`, `misc-image:26`). The model cannot see the pixel size from the result (no text block beside the image) and ran `file` (`:46/50`). The redactor leaves base64 alone; the two misc-image files were post-processed to `"<base64>"` (a screenshot would otherwise put hundreds of KB on one line).

**Web tools.** Network worked through the sandbox proxy (WebFetch 200). Both tools are deferred in 2.1.302, so each first use is preceded by a `ToolSearch` round-trip whose result content is a `tool_reference` block (`misc-web:15/17`, `:57/59`). Both prompt: WebFetch's request has `description:"https://example.com"` and suggestion `addRules [{toolName:"WebFetch", ruleContent:"domain:example.com"}]` (`:31`); WebSearch's has `description` = the query and `addRules [{toolName:"WebSearch"}]` with no `ruleContent` (`:74`). WebSearch runs on the Haiku helper: `result.modelUsage["claude-haiku-5-5"].webSearchRequests:1` while `result.usage.server_tool_use.web_search_requests` stays 0 (`:105`).

**Session state.** Both env vars produce `system/session_state_changed`; `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` adds `sdk_host_only:true`; with neither, no such line (`misc-state-none`, 16 lines, last is `command_lifecycle completed`).

```json
{"type":"system","subtype":"session_state_changed","state":"running","uuid":"<uuid-4>","session_id":"<session_id-2>"}                    // misc-state-emit:3
{"type":"system","subtype":"session_state_changed","state":"requires_action","sdk_host_only":true,"uuid":"...","session_id":"..."}  // misc-state-action:20
```

Timing: `running` before `system/init`, as soon as the user message is read (`misc-state-emit:3`); `requires_action` immediately before each `can_use_tool` (`misc-state-action:20/21`), `running` again right after the host's answer (`:24`); `idle` after `result` and `command_lifecycle completed`, as the turn's last line (`misc-state-emit:16-18`, `misc-state-action:35-37`). The initialize response payload also carries `session_state:"idle"` (`misc-state-emit:2`).

**rate_limit_event.** Arrives whenever an API response's rate-limit headers come in, not at a fixed position: before `system/init` in most recordings (`misc-state-emit:5`, `tools-bash:7`, `misc-image:4`), after `status requesting` and again before `result` in `misc-state-action:8,34` (utilization 0.61 -> 0.62). Accept it anywhere, including between `stream_event` frames.

```json
{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1792371000,"rateLimitType":"five_hour","overageStatus":"rejected","overageDisabledReason":"out_of_credits","isUsingOverage":false,"unifiedWindows":{"five_hour":{"utilization":0.59,"resetsAt":1792371000},"seven_day":{"utilization":0.21,"resetsAt":1792400400}}},"uuid":"<uuid-7>","session_id":"<session_id-2>"}
```

**Usage lines, per API request.** (a) `stream_event message_start` `.event.message.usage {input_tokens, cache_creation_input_tokens, cache_read_input_tokens, output_tokens (placeholder), cache_creation{...}, service_tier, inference_geo, speed}` plus wrapper `ttft_ms`: the request's context size is the sum of the three input fields (2 + 4720 + 26538 = 31260 = `result.first_request_input_tokens`, `misc-state-emit:9`, `:16`); it grows across a turn's requests (`misc-image:8/28/52`). (b) `stream_event message_delta` `.event.usage {input_tokens, cache_creation_input_tokens, cache_read_input_tokens, output_tokens (final), output_tokens_details:{thinking_tokens}, iterations:[...]}` and `context_management:{applied_edits:[]}`: the only final per-request output count; one per request, so a multi-tool turn has several (`misc-image:24,48,64`; `:48` has `thinking_tokens:62`). Text deltas carry no counts. (c) `system/thinking_tokens` and `thinking_delta.estimated_tokens` are the only mid-stream running counter and overshoot (186 estimated vs 62 final, `misc-image:33` vs `:48`).

**Usage, per turn** (`misc-image:66`, `misc-state-emit:16`): `result.usage` sums the turn's requests (`cache_read 89238` = 26538 + 31292 + 31408; `output_tokens_details.thinking_tokens`, `server_tool_use`); `result.modelUsage[<model>]{inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, webSearchRequests, costUSD, contextWindow:1000000, maxOutputTokens:128000, thinkingTokens, canonicalModel, provider, costBasis}` is **cumulative per process** (`misc-web:105`, `result_index:1`); plus `first_request_input_tokens`, `ttft_ms`, `ttft_stream_ms`, `time_to_request_ms`, `first_content_frame_ms`, `duration_ms`, `duration_api_ms`, `total_cost_usd`, `num_turns`, `result_index`, `terminal_reason`, `subagent_stats`, `safety_stops`, `queued_turn_count`.

Context ring = last `message_start` input sum / `modelUsage[init.model].contextWindow`; account windows = `rate_limit_event.unifiedWindows.{five_hour, seven_day}.utilization` (0..1) + `resetsAt` (epoch seconds); live counter = accumulate `message_delta.usage.output_tokens` per request plus `system/thinking_tokens` while a thinking block streams.

**Other lines.** `system/init` in 2.1.302 adds `capabilities:[interrupt_receipt_v1, interrupt_cancel_queued_v1, interrupt_send_now_v1, msg_lifecycle_v1, request_marker_lists_v1, sdk_mcp_tools_list_changed, sdk_mcp_manifests, mcp_read_resource_v1, mcp_tool_ui_meta_v1, ui_surface_v1, mcp_call_result_not_for_model_v1]`, `scratchpad_path`, `messaging_socket_path`, `fast_mode_state`, `fast_mode_disabled_reason` (`misc-state-emit:7`); the initialize response payload adds `capabilities:[ui_surface_v1, mcp_call_result_not_for_model_v1]`, `ide_rc_auto_enable_gate`, `session_state` (`:2`). `system/informational` and `system/notification` never appeared; `post_turn_summary`, `active_goal` and `autocompact_state` from the 2.1.300 fixtures did not appear in these runs either.

**Unknown.** `system/informational` / `notification` shapes; WebFetch/WebSearch error results and behaviour on a machine without the proxy; whether `rate_limit_event` ever lacks `unifiedWindows`.

---

## 3. Implications for the redesign

### 3.1 What the reducer must decode (today `Ndjson.kt` types only `init`, `status`, `api_retry`, `model_refusal_*`, `can_use_tool`; `decodeUser` keeps only `content`)

1. **`user` lines**: keep `tool_result.content` as either a string or a block array (`text`, `image`, `tool_reference`), `is_error` as absent/false/true, `tool_use_result` as a `JsonElement` (object on success, string `"Error: ..."` on failure, absent on subagent successes), `tool_result_meta[].permission_decision {decision, source, reason_type?}` and `non_execution_kind`, `parent_tool_use_id`, `agent_id`, `subagent_type`, `task_description`, `timestamp`. Typed views per tool from the 2.4 table (Read `file`, Edit `structuredPatch`/`originalFile`, Write `type`, Bash `stdout`, Agent `content[]`/`toolStats`, AskUserQuestion `answers`/`response`, ExitPlanMode `plan`).
2. **`assistant` lines**: `caller` on tool_use blocks (ignore), `wire_tool_inputs`, `thinking_duration_ms`, `thinking_display`, `agent_id`/`subagent_type` on subagent lines; an empty `thinking` string with a signature is normal.
3. **`stream_event`**: `thinking_delta.thinking` may be text (summarized) or `""` (updates); `estimated_tokens` is a non-standard sibling that is `null` in summarized mode; wrapper `thinking_display`, `ttft_ms`, `api_message_id`; `message_start.message.usage` and `message_delta.usage` (+ `context_management`) for the counters.
4. **`system` subtypes**: `thinking_tokens {estimated_tokens, estimated_tokens_delta, user_message_uuid}`; `task_started`, `task_progress`, `task_updated`, `task_notification` (fields above; branch on `task_type`); `session_state_changed {state, sdk_host_only?}`; `status {status:null, permissionMode}` after `ExitPlanMode` (already typed); `ui_invalidate` (ignore).
5. **Top-level types**: `rate_limit_event` (anywhere in the stream), `command_lifecycle`, `control_response` with **no** `response` field on `set_max_thinking_tokens` success and an `error` string on failure, and the initialize envelope's `pending_permission_requests` / `pending_user_dialog_requests`.
6. **`control_request can_use_tool`**: `requires_user_interaction`, `decision_reason_type`, `agent_id`; `permission_suggestions` may be absent (AskUserQuestion, ExitPlanMode); `input` may be `{}` (ExitPlanMode with Write disallowed).
7. **`result`**: `permission_denials[]`, `usage`, `modelUsage` (cumulative), `first_request_input_tokens`, `duration_ms`, `ttft_ms`, `result_index`, `subagent_stats`.
8. **Ordering**: a `control_request` arrives before `content_block_stop`; a tool result may arrive before the parent message's `message_delta`/`message_stop`; `task_started` arrives before the parent message closes; subagent lines interleave with the main thread.

### 3.2 What the host must send back

- AskUserQuestion: allow with `updatedInput:{questions:<unchanged>, answers:{<question text>: <label | ", "-joined labels | free text>}}` or `updatedInput:{questions, response:<text>}`; deny with `message` to cancel (the turn continues). Key the card on the `control_request`, not on message close.
- ExitPlanMode: allow with `updatedInput:<input>` plus a host-built `updatedPermissions:[{type:"setMode", mode:"acceptEdits", destination:"session"}]` for "approve and start coding" (the CLI confirms with `system/status permissionMode`; no suggestion is offered); plain allow leaves plan mode to `default`; "keep planning" = deny with the feedback as `message` (mode stays plan, the model revises and asks again). Under Tandem's argv the request input is `{}` and the plan text exists only in the failed Write's `input.content` and the assistant text: either allow `Write` for the plans directory, serve it through the IDE MCP tool, or render the plan from the preceding assistant text.
- Thinking summaries: add `--thinking-display summarized` to the spawn argv or send `set_max_thinking_tokens{thinking_display:"summarized"}` after `initialize` (effective next turn). Render by appending `thinking_delta.thinking`, replacing with the assistant block, and showing `thinking_duration_ms`; keep the token-count placeholder for `updates`.
- Subagents: send `initialize{hooks:null, forwardSubagentText:true}` to get the subagent's final text as a line; otherwise read `task_notification.summary`.
- Session state: set `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` (or `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1`) in `CliEnvironment` if the working indicator should follow `running`/`requires_action`/`idle` instead of host inference.
- Tool availability: expect Glob/Grep-less sessions by default (the model uses Bash `find`/`grep`, auto-approved with `reason_type:"subcommandResults"`); do not add `--tools`. Deferred tools cost a visible `ToolSearch` round-trip to collapse.

### 3.3 Open questions in the UI reference, section 6, now closed

| # | Status | Answer |
|---|---|---|
| 4 | Partly | No `system/task_summary` in any of the 29 sessions on 2.1.302 (only `permission.ndjson`, older CLI), so verb text cannot rely on `task_summary.detail`; `task_progress.description` ("Running ...") exists for subagents only. Rotation interval remains open. |
| 5 | **Closed** | Thinking text over stdio = `thinking_display:"summarized"`, via `--thinking-display summarized` or `set_max_thinking_tokens`; the default `updates` mode never carries text; `full` is rejected; `highlights` gives nothing on this account (2.3). |
| 6 | Wire half closed | AskUserQuestion and plan-approval wire shapes are recorded (2.1, 2.2); the Desktop card visuals remain open. |
| 7 | **Closed** | `can_use_tool.input` for `ExitPlanMode` carries `{plan, planFilePath}` when the plan file was written, `{}` when `Write` is disallowed. Plan mode does not break with `Write` disallowed: the Write fails with a `tool_use_error` and the model still calls `ExitPlanMode`, but the host then has no plan to show (2.2). |
| 8 | **Closed** | Yes: echoing `setMode acceptEdits` in `updatedPermissions` switches the live session and emits `system/status {status:null, permissionMode:"acceptEdits"}` before the tool result; the next turn's init agrees (`plan-approve:90,160`). This also confirms the 4.7 "Allow for this session" risk: a `setMode` suggestion echoed back switches the mode. |
| 9 | Closed for availability | Absent on Sonnet 5.5 and Opus 5.5 by default; `CLAUDE_CODE_ENABLE_TODO_TOOLS=1` adds `TaskCreate/TaskGet/TaskList/TaskUpdate` (deferred, loaded via `ToolSearch`); `TodoWrite` does not exist in 2.1.302 (2.5). The widget look remains open. |
| 11 | Wire half closed | Image `Read` result shape recorded with `dimensions` (2.4, 2.6); thumbnail size and click behaviour remain open. |
| 14 | **Closed** | Both variables work: `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` emits `session_state_changed {state}`, `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` the same with `sdk_host_only:true`; states `running`, `requires_action`, `idle` with the timing in 2.6. |

Still open (visual, need the owner): 1, 2, 3, 10, 12, 13, and the visual halves of 6, 9, 11.

### 3.4 Fixture tooling follow-ups (not done; tracked files untouched)

- `scripts/redact_fixture.py`: add `parent_tool_use_id`, `agent_id`, `task_id` to `ID_KEYS` (the agent fixtures were produced with a patched copy; re-running the tracked redactor over them is a no-op, re-recording with it would break the correlation); add a rule that replaces long `data`/`base64` values with `"<base64>"`; the `id` placeholder also hits `TaskCreate`'s `task.id` (`agent-todos:35`); the hand-back text in Agent results still contains the raw 17-hex agent id (`agent-sync:50`, not personal).
- `docs/protocol.md`: 4.4 (`set_max_thinking_tokens` success has no `response`; accepted `thinking_display` values and error text), 1.1 (`--thinking-display`, `--thinking`), 6.3 (`thinking_display` also on thinking-block frames and the assistant line, with `thinking_duration_ms`), 5.2 (task lines, `session_state_changed`, both env variables), 6.2 (string `tool_use_result` on errors, `tool_result_meta`).
- `scripts/drive_cli.py`: the probe scenarios (in the probe reports' `scenario_code`) are not yet merged; `ask-*`, `plan-*`, `think-*`, `tools-*`, `agent-*` and `misc-*` need per-scenario flags, environment and (for `agent-forwarded`) an initialize body.
- Gradle was not run for any probe, so whether a test enumerating `fixtures/` or `fixtures/transcripts/` accepts the 29 new files is unverified; every line was parsed as JSON by the redactor and by the checks behind this report.

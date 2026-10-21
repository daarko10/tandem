# Bash mode probe against claude 2.1.306 (the `!` shell escape over stream-json)

Status: research result, 2026-10-21. Companion to `docs/protocol.md` (sections 3 and 11.4) and
`2026-10-18-protocol-probes.md`. Audience: whoever specs and builds Tandem's `! command` (task 39).

- Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.
- Every claim cites `fixture:line` under `src/test/resources/fixtures/` (`.ndjson` is stdout, `transcripts/<name>.jsonl`
  the CLI's own transcript). Placeholders (`<uuid-N>`, `<path>`, `<branch>`, ...) are `scripts/redact_fixture.py`'s.
- CLI 2.1.306 at `/opt/node22/bin/claude`, default model `claude-fable-5-1`, all fixtures recorded with `--safe-mode`
  (as the 2026-10-18 probes). A first run of each probe without `--safe-mode` (scratch only, not committed) wrote the
  same user lines and stream shape; it also carried the environment's plugin hook lines, hence the rerun.

## 1. Fixtures

| Fixture | How it was recorded | What it shows |
|---|---|---|
| `transcripts/bash-mode-interactive.jsonl` (no stream twin) | Interactive TUI under a Python `pty`, 120x40, onboarding screens answered (theme, security notes, folder trust); typed `!`, then `echo bash-mode-probe && printf err >&2`, Enter, waited 12 s, `/exit` | The interactive shape: two user lines, then Claude answers on its own |
| `bash-mode.ndjson` + `transcripts/bash-mode.jsonl` | stream-json argv of `drive_cli.py` (`--output-format stream-json --verbose --input-format stream-json --include-partial-messages --permission-prompt-tool=stdio --safe-mode`); initialize; `<bash-input>` and `<bash-stdout>` sent back to back, both `shouldQuery:false`, `client_composed:true`; 3 s wait; then the prompt `Reply with only the text the command printed to stdout` | Quiet append: zero-turn `result`, the two sends merged into one transcript line, the model sees it on the next prompt |
| `bash-mode-respond.ndjson` + `transcripts/bash-mode-respond.jsonl` | Same argv; `<bash-input>` with `shouldQuery:false`, 3 s wait, then `<bash-stdout>` with `client_composed:true` and no `shouldQuery` | The interactive default over stream-json: the output send starts a normal turn |

Host frames are not in the fixtures; the ones sent were (uuid aside):

```json
{"type":"user","session_id":"","parent_tool_use_id":null,"uuid":"…","message":{"role":"user","content":"<bash-input>echo bash-mode-probe && printf err >&2</bash-input>"},"client_composed":true,"shouldQuery":false}
{"type":"user","session_id":"","parent_tool_use_id":null,"uuid":"…","message":{"role":"user","content":"<bash-stdout>bash-mode-probe\nerr</bash-stdout><bash-stderr></bash-stderr>"},"client_composed":true,"shouldQuery":false}
```

(`bash-mode-respond` sends the second frame without `shouldQuery`.) Content is a string, as in the interactive
transcript; the follow-up prompt used the usual text-block array.

## 2. Shapes

### 2.1 Interactive TUI (`transcripts/bash-mode-interactive.jsonl`)

- Line 5, the command, verbatim (redacted):
  `{"parentUuid":null,"isSidechain":false,"promptId":"<promptId-2>","type":"user","message":{"role":"user","content":"<bash-input>echo bash-mode-probe && printf err >&2</bash-input>"},"uuid":"<uuid-3>","timestamp":…,"userType":"external","entrypoint":"<entrypoint>","cwd":"<path>","sessionId":"<sessionId-1>","version":"2.1.306","gitBranch":"<branch>"}`
- Line 6, the output, child of line 5, same `promptId`:
  `{"parentUuid":"<uuid-3>",…,"message":{"role":"user","content":"<bash-stdout>bash-mode-probe\nerr</bash-stdout><bash-stderr></bash-stderr>"},"uuid":"<uuid-4>",…,"turnOrigin":"human","turnPosition":{"promptIndex":1,"turnIndex":1},…}`
- `content` is a plain string, not blocks. Neither line has `isMeta` (so both are conversational lines of the chain,
  `docs/protocol.md` 11.3). Only the output line has `turnOrigin`/`turnPosition`: it is the one that starts the turn.
- **stderr is merged into stdout**: `err` went to fd 2 yet sits in `<bash-stdout>`, and `<bash-stderr>` is empty (line 6;
  same in the non-safe-mode run). No exit code anywhere.
- **The model is called**: attachments (7-10), then an assistant thinking + text reply (11-12, "Your probe ran. Both
  outputs landed in the stdout block ..."), `system/turn_duration` (14). This is documented behaviour: the
  interactive-mode docs ("Shell mode with `!` prefix") say Claude responds to the output automatically, and that the
  `respondToBashCommands: false` setting adds it to context without a response.
- `last-prompt` renders the escape back as `! echo bash-mode-probe && printf err >&2` (15).
- The command text is what follows `!`, untrimmed: the non-safe-mode run typed `! echo …` and recorded
  `<bash-input> echo …</bash-input>` with the leading space.

### 2.2 stream-json, both sends `shouldQuery:false` (`bash-mode`)

- The CLI emits no echo, but it does run a zero-cost "turn" for the queued sends: `command_lifecycle` queued/started for
  both uuids (`bash-mode.ndjson:3-6`), `system/init` (7), then `result` `subtype:"success"`, `num_turns:0`,
  `result:""`, `duration_api_ms:0`, `total_cost_usd:0`, `stop_reason:null`, `user_message_uuids` = both sends (8),
  lifecycle completed (9-10). No `status:requesting`, no API call.
- Sent back to back, the two sends become **one** transcript line (`transcripts/bash-mode.jsonl:5`): content
  `"<bash-input>…</bash-input>\n<bash-stdout>bash-mode-probe\nerr</bash-stdout><bash-stderr></bash-stderr>"`, `uuid`
  = the last send's, `mergedSendUuids` = the earlier ones (redacted as `<leaked-1>`, see 4), plus
  `queueTranscriptOnly:true`, `queueSkipSlashCommands:true`, `queueSkipAttachments:true`, `promptSource:"sdk"`,
  `permissionMode`. Lines 1-4 are `queue-operation` enqueue/dequeue lines carrying each content.
- The model saw it: the next prompt (`transcripts/bash-mode.jsonl:8`, parent = line 5) got `bash-mode-probe`
  (`bash-mode.ndjson:18-19`, `result` 23, `num_turns:1`).

### 2.3 stream-json, output send without `shouldQuery` (`bash-mode-respond`)

- The `<bash-input>` send alone: zero-turn `result` as above (`bash-mode-respond.ndjson:5-6`); transcript line 3,
  `queueTranscriptOnly:true`, its own `promptId`.
- The `<bash-stdout>` send: a normal turn (`bash-mode-respond.ndjson:8-11` lifecycle, init, `status:requesting`;
  thinking 14-21; text 24-36; `result` 41). Transcript line 6: child of line 3, `turnOrigin:"sdk"`, `turnPosition`,
  `queueSkipSlashCommands:true` (from `client_composed`), no `queueTranscriptOnly`. The reply discusses the output
  (merged stderr), so the model read the tags. `last-prompt` again shows `! echo …` (17): the CLI recognises the tag.
- So `bash-mode-respond` reproduces the interactive transcript (2.1) except `turnOrigin` (`sdk` vs `human`), separate
  `promptId`s and the `queue*`/`promptSource` fields.

## 3. What the host must send and show

Send (after running the command itself, off the EDT, in the session's cwd with the login-shell environment):

- **Claude answers, as the terminal does by default**: two frames, both `client_composed:true` (no `@path` expansion,
  no slash dispatch): `<bash-input>CMD</bash-input>` with `shouldQuery:false`, then
  `<bash-stdout>OUT</bash-stdout><bash-stderr>ERR</bash-stderr>` without it (`bash-mode-respond`).
- **Context only** (`respondToBashCommands:false`): one frame, `shouldQuery:false`, content
  `<bash-input>CMD</bash-input>\n<bash-stdout>OUT</bash-stdout><bash-stderr>ERR</bash-stderr>`, the exact line the CLI
  writes when it merges (`transcripts/bash-mode.jsonl:5`). Two quiet frames sent together are merged anyway, under the
  last uuid.
- To match the 2.1.306 TUI, OUT is stdout and stderr interleaved and ERR is empty (2.1); separate streams are also
  accepted, the tags are only text to the CLI.

Show:

- Live: the CLI echoes nothing, so the `! CMD` row and its output come from Tandem's own run. The zero-turn `result`
  (`num_turns:0`, `result:""`, cost 0) and its `system/init` must not produce a reply, a `Worked for` footer or a
  notice (mcp-slash.ndjson already has such results). Correlate by `user_message_uuids`.
- Reload: both shapes start with `<bash-input>`, so `docs/protocol.md` 11.4 shows `!command`, but the merged line
  (`transcripts/bash-mode.jsonl:5`) carries the output after `\n` in the same text: the reader must cut the command at
  `</bash-input>` and parse `<bash-stdout>`/`<bash-stderr>` instead of showing raw tags. 11.4 hides the output lines
  today; for this feature they likely should render as the output block. The new fields (`queueTranscriptOnly`,
  `mergedSendUuids`, `promptSource`, `turnOrigin`, `turnPosition`) need no handling; `queue-operation` lines are skipped
  already.
- Title: the first prompt may now be a bash line; show it as `! CMD` (what the CLI's `last-prompt` does) or skip it.

## 4. Open questions and follow-ups

1. Respond or not: the terminal responds by default and `respondToBashCommands` turns it off, but Tandem may not read
   settings files (`docs/protocol.md` 11.5). A Tandem setting or a per-send choice is a product decision.
2. Not measured: a non-zero exit or a killed command (no exit code appears in the tags), whether `<bash-stderr>` is ever
   filled, output size limits/truncation, output that contains `</bash-stdout>`, which shell and environment the TUI
   uses, Windows shells, `Ctrl+B` backgrounding, and sending a bash escape while a turn is running.
3. Redactor: `mergedSendUuids` is not in `ID_LIST_KEYS`, so its value became `<leaked-1>` instead of the correlated
   `<command_uuid-4>` (`transcripts/bash-mode.jsonl:5`); `file-history-snapshot.messageId` also lands in the safety
   net (`transcripts/bash-mode-interactive.jsonl:4,20`). Both values are safe (a host-chosen uuid, a line uuid);
   `scripts/redact_fixture.py` was left untouched.
4. The new fixtures are picked up by the generic fixture loops (`FixtureDecodeTest`, `ReducerTest`,
   `ChatRowsBuilderTest`, `RobustnessTest`); Gradle was not run, and no dedicated fixture test exists yet (hard rule 6).

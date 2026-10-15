# Tandem design

## Goal
The best Claude Code experience in JetBrains IDEs: fast, native, and honest about what the CLI is doing.
Free and open source. Success is measured by benchmarks we publish (time to first token, memory, plugin size)
and by not breaking on IDE or CLI updates.

## Why another plugin
The existing options were studied in October 2026:
- Anthropic's own "Claude Code [Beta]" plugin (rating 2.3) is a terminal launcher with a diff hook.
- CC GUI (largest community plugin, ~340k lines, 489 open issues) is a React webview + Node sidecar + pinned Agent SDK.
  Its top problems map directly to that architecture.

| CC GUI pain (issue examples) | Root cause | Tandem decision |
|---|---|---|
| Black/white screen every IDE release (#813, #1302, #1907) | JCEF webview | Jewel (Compose) UI, no browser |
| Typing/IME/selection bugs (#1854, #1465) | JCEF input handling | Jewel text fields |
| Windows spawn errors, node pile-up, RAM (#1928, #953, #1324) | Node sidecar | No Node; spawn `claude` directly |
| Missing models, broken `/compact`, "works in CLI" (#1517, #592) | Pinned SDK drifts from user's CLI | Use the user's own CLI, version-gated features |
| Model switch silently ignored (#1462, #1741) | Model fixed at process start | `set_model` control request, verify via init/fallback events, show mismatch |
| IDE freezes waiting on bridge (#1859, #1801) | Blocking I/O on EDT | Coroutines only; EDT never waits |
| History lost / not resumable in CLI (#1852, #1259) | Own session format | Use the CLI's own sessions (`--resume`) |
| Wrong diffs, diff exceptions (#1891, #275) | Writes to disk, diffs afterwards | Edits applied through `Document` + native diff before apply |

## Architecture

```
 Jewel tool window (ui/)
        │  observes StateFlow<ChatState>, sends Intents
        ▼
 ClaudeSession (session/)  ── one per chat, owned by a project-level service scope
   • spawns `claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages …`
   • writer coroutine: Intents → stdin NDJSON (user messages, control responses)
   • reader coroutine: stdout NDJSON → protocol/ decode → reducer → ChatState
   • control channel: can_use_tool → PermissionBroker (ide/) → control_response
        ▼
 protocol/  pure Kotlin. Json tree → sealed CliMessage; unknown types → Unknown(raw)
        ▲
 ide/  Tandem MCP server (edit tools via WriteCommandAction), diff preview, file/selection context
```

Key choices:
- **One long-lived CLI process per chat.** No per-message spawn. Restart only on crash or explicit resume.
- **Reducer, not mutation.** Every decoded message goes through a pure `reduce(state, message)`; this is what the
  fixture tests exercise, and it makes partial/complete message reconciliation deterministic.
- **Partial messages.** `stream_event` deltas build a draft keyed by message id; the complete `assistant`
  message replaces the draft. Never render both.
- **Permissions answered by the host** over the control channel (see `protocol.md`), shown as native, non-modal UI
  inside the chat, with "allow once / always for this session / deny".
- **Edits through the IDE.** Built-in `Edit`, `Write`, `MultiEdit`, `NotebookEdit` are disallowed; Tandem's MCP server
  exposes equivalent tools that apply via `WriteCommandAction` on the open `Document`.

## Milestones
0. **Jewel spike (2–3 days).** Streaming markdown + code blocks in a Jewel tool window at 60fps on a long transcript.
   Go/no-go for Jewel. If no-go, fall back to JCEF with strict isolation.
1. **MVP.** Protocol + session + chat + host permissions + model/error status + resume.
2. **IDE-native edits.** MCP edit tools, diff preview, undo.
3. **Parity.** Slash commands, plan mode, AskUserQuestion, images, context meter, rewind, subagent status.
4. **Hardening.** Windows/WSL, Remote Development, IDE matrix in CI, published benchmarks.

## Open questions
- Does the platform's classloader expose kotlinx.serialization to plugins? We bundle 1.9.0 (tree API only) for now.
- Lowest supported IDE: 2026.1 (build 261). Revisit after the Jewel spike.
- Exact flag set for host-answered permissions: confirm against `protocol.md` and a recorded fixture.

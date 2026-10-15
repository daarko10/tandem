# Tandem

A fast, native JetBrains plugin for Claude Code. Free and open source.
It drives the user's own installed `claude` CLI over stream-json and renders everything with Jewel (Compose).
Read `docs/design.md` before any non-trivial change, and `docs/protocol.md` before touching `protocol/` or `session/`.

## Hard rules (do not break these)
1. **Never block the EDT.** No process I/O, file I/O, JSON parsing or waiting on the CLI from the UI thread. Use the platform coroutine scopes (`platform-coroutines-structured-concurrency` skill). CC GUI's worst bugs were EDT freezes while waiting on its bridge.
2. **No Node, no Agent SDK at runtime.** We spawn the user's `claude` binary directly. Do not add a sidecar.
3. **Never touch credentials.** Auth belongs to the CLI. Do not read `~/.claude/.credentials*`, keychains or API keys, and never offer a login flow.
4. **Edits go through the IDE, not the disk.** Claude's built-in Edit/Write tools are disabled; our MCP edit tools apply changes with `WriteCommandAction` on the `Document`, so undo, unsaved buffers and local history work. Every edit shows a native diff before it lands, unless the user picked auto-accept.
5. **Never fail silently.** If the CLI falls back to another model, rejects a model, retries, or errors, the user sees it in the chat. Compare the model in the `system/init` event (and fallback events) with what the user picked.
6. **Every protocol change gets a fixture test.** Recorded transcripts live in `src/test/resources/fixtures/`. Unknown message types must parse to `Unknown(raw)`, never throw.
7. **Do not bundle kotlinx.coroutines or the Kotlin stdlib.** The platform provides both.
8. **Do not call the product "Claude Code"**, in the plugin name, ID or UI. Use "Tandem" and say it works with Claude Code.

## Layout
- `protocol/` pure Kotlin: NDJSON codec, typed messages, control requests. No IntelliJ imports, so it unit-tests fast.
- `session/` one `ClaudeSession` per chat: spawns the CLI, owns stdin/stdout, reconciles partial and complete messages, exposes `StateFlow`s.
- `ide/` IDE-side integration: MCP server with edit tools, permission dialogs, diff, file context.
- `ui/` Jewel tool window. Renders state from `session/`; holds no protocol logic.

## Commands
- `./gradlew test` unit and fixture tests (fast, no IDE).
- `./gradlew runIde` sandbox IDE with the plugin. Use it to check feel, freezes and focus; agents cannot judge these.
- `./gradlew verifyPlugin` Plugin Verifier against every supported IDE release.
- `./gradlew buildPlugin` installable ZIP in `build/distributions/`.
- `scripts/record-fixture.sh <name> "<prompt>"` records a real CLI transcript as a test fixture. Needs a logged-in `claude`.

## Tools and skills to use
- IntelliJ's MCP server (Settings | Tools | MCP Server): run `get_file_problems` / `lint_files` on files you change, and prefer `rename_refactoring` over text edits.
- Context7 for current Jewel, IntelliJ Platform Gradle Plugin and Compose APIs. These move faster than training data; check before guessing an API.
- `.claude/skills/` has JetBrains' own IntelliJ skills (Apache-2.0, see its README). In them, `tests.cmd` and Bazel are monorepo-only: use `./gradlew test` instead.
- `superpowers` for multi-file features: plan, then TDD. Skip it for small fixes.

## How to work with me
- Clarify ambiguity before coding; look for existing patterns in the repo first.
- Start with the simplest working version, then iterate. Test as you go.
- Keep changes focused; don't refactor unrelated code.
- Say plainly what was verified and what was not. If tests fail, show the output.
- Ask before anything destructive or outward-facing (force push, deleting data, publishing to the Marketplace).

## Conventions
- Kotlin, explicit types on public API. Follow the `code-style` skill.
- Validate everything that comes from the CLI; it is an external process that changes weekly.
- Never log prompt contents, file contents or secrets. Log message types and ids.
- Coverage target: 90% on `protocol/` and `session/`.
- Commits: Conventional Commits (`feat:`, `fix:`, `perf:`, `docs:`, `test:`, `refactor:`).
- PRs: clear title, context + approach + testing notes, CI green.

## Remote-session notes
- The cloud environment allows Maven, Gradle and `*.jetbrains.com`, so Gradle runs there. The first build downloads the IntelliJ Platform (over 1 GB) and can take several minutes; run it in the background rather than letting it time out. If a host is refused, stop and name it instead of guessing that code compiles.
- No IDE runs here, so the IntelliJ MCP server is unavailable. Use `./gradlew test`, `./gradlew verifyPlugin` and compiler output. For UI checks run the sandbox IDE under `xvfb-run` and look at screenshots.
- `claude` inside the session uses the session's own sign-in, so `scripts/record-fixture.sh` works without an API key.
- Work on a feature branch, never directly on `main`.
- Put scratch files outside the repo or in `scratch/` (gitignored).

_Based on [claude-base](https://github.com/daarko10/claude-base)._

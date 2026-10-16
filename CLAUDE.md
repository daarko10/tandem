# Tandem

A fast, native JetBrains plugin for Claude Code. Free and open source.
It drives the user's own installed `claude` CLI over stream-json and renders everything with Jewel (Compose).
Read `docs/design.md` before any non-trivial change, and `docs/protocol.md` before touching `protocol/` or `session/`.

## Hard rules (do not break these)
1. **Never block the EDT.** No process I/O, file I/O, JSON parsing or waiting on the CLI from the UI thread. Use the platform coroutine scopes (`platform-coroutines-structured-concurrency` skill).
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
- `./gradlew test` unit, fixture and headless platform tests (no real `claude`; a fake CLI replays fixtures).
- `xvfb-run ./gradlew integrationTest` IDE Starter + UI Driver tests: install the built ZIP in a real IDE, type prompts, answer permission prompts and press Stop against a fake CLI (`src/test/resources/fake-claude.py`). Slow; catch plugin-classloader and UI problems the platform tests cannot.
- `./gradlew runIde` sandbox IDE with the plugin. Use it to check feel, freezes and focus; agents cannot judge these.
- `./gradlew verifyPlugin` Plugin Verifier against every supported IDE release.
- `./gradlew buildPlugin` installable ZIP in `build/distributions/`.
- `scripts/record-fixture.sh <name> "<prompt>"` records a real CLI transcript as a test fixture. Needs a logged-in `claude`.

## Tools and skills to use
- IntelliJ's MCP server (Settings | Tools | MCP Server): run `get_file_problems` / `lint_files` on files you change, and prefer `rename_refactoring` over text edits.
- Context7 for current Jewel, IntelliJ Platform Gradle Plugin and Compose APIs. These move faster than training data; check before guessing an API.
- `.claude/skills/` has JetBrains' own IntelliJ skills (Apache-2.0, see its README). In them, `tests.cmd` and Bazel are monorepo-only: use `./gradlew test` instead.
- `superpowers` for multi-file features: plan, then TDD. Skip it for small fixes.

## Working in this repo
- Look for existing patterns before adding new ones. Keep changes focused; don't refactor unrelated code.
- Say plainly what was verified and what was not. If tests fail, show the output.
- Ask before anything destructive or outward-facing (force push, deleting data, publishing to the Marketplace).
- Work on a feature branch, never directly on `main`. Scratch files go in `scratch/` (gitignored).

## Conventions
- Kotlin, explicit types on public API. Follow the `code-style` skill.
- Validate everything that comes from the CLI; it is an external process that changes weekly.
- Never log prompt contents, file contents or secrets. Log message types and ids.
- Coverage target: 90% on `protocol/` and `session/`.
- Commits: Conventional Commits (`feat:`, `fix:`, `perf:`, `docs:`, `test:`, `refactor:`).
- PRs: clear title, context + approach + testing notes, CI green.

## Headless and cloud sandboxes
- Gradle needs Maven Central, the Gradle Plugin Portal and `*.jetbrains.com`. The first build downloads the IntelliJ Platform (over 1 GB); run it in the background rather than letting it time out. If a host is refused, stop and name it instead of guessing that code compiles.
- Without a running IDE there is no IntelliJ MCP server. Use `./gradlew test`, `./gradlew verifyPlugin` and compiler output. For UI checks run `xvfb-run ./gradlew integrationTest`, or the sandbox IDE under `xvfb-run` and look at screenshots.
- `scripts/record-fixture.sh` uses whatever sign-in the local `claude` has; in a hosted Claude Code session that is the session's own.

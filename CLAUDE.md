# Tandem

A fast, native JetBrains plugin for Claude Code. Free and open source.
It drives the user's own installed `claude` CLI over stream-json and renders everything with Jewel (Compose).
Read `docs/design.md` before any non-trivial change, and `docs/protocol.md` before touching `protocol/` or `session/`.
Read `docs/launch.md` before any work described as ship, release, publish, launch, announce, post, demo or benchmark, before editing `docs/protocol.md` or the fixtures, and before changing a claim in `README.md` or `plugin.xml`.

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
- `session/` `SessionManager` owns the project's sessions (`SessionHandle`), each with one `ClaudeSession` per live process: spawns the CLI, owns stdin/stdout, reconciles partial and complete messages, exposes `StateFlow`s. Imports only the JDK, kotlinx.coroutines, kotlinx.serialization.json, the platform's kotlinx.collections.immutable and `com.intellij.openapi.diagnostic`.
- `session/transcript/` the CLI's own transcripts, `<config>/projects/<key>/<id>.jsonl` (`docs/protocol.md` 11): folder key, index, reader. Read-only, except a rename's `custom-title` line and delete.
- `ide/` IDE-side integration: the `TandemSessions` project service, the settings page (Settings | Tools | Tandem: `claude` path, max live sessions), workspace state, notifications. Later the MCP server with edit tools, diff and file context.
- `ui/` Jewel tool window. Renders state from `session/`; holds no protocol logic.

## Commands
- `./gradlew test` unit, fixture and headless platform tests (no real `claude`; a fake CLI replays fixtures).
- `./gradlew koverLog` line coverage of `protocol/` and `session/` per package after the tests (Kover, build-time only); `koverHtmlReport` writes `build/reports/kover/html/`. `./gradlew check` runs `koverVerify`, which fails under 90%.
- `xvfb-run ./gradlew integrationTest` IDE Starter + UI Driver tests: install the built ZIP in a real IDE, type prompts, answer permission prompts, press Stop, use the pickers and run two sessions from the sidebar against a fake CLI (`src/test/resources/fake-claude.py`). Slow; catch plugin-classloader and UI problems the platform tests cannot.
- Test hooks (system properties): `tandem.claudePath` is the `claude` to run (the test tasks point it at the fake CLI); `tandem.configDir` replaces `$CLAUDE_CONFIG_DIR` / `~/.claude` when looking for transcripts; `tandem.ideTestShard=<index>/<total>` (index from 0) makes `integrationTest` run only the tests whose position in their class, sorted by name, is index modulo total (`IdeTestShard`; CI splits the IDE test into jobs with it), and a malformed value fails the tests.
- The fake CLI (see its docstring) reads `TANDEM_FAKE_FIXTURE` (one fixture) or `TANDEM_FAKE_FIXTURES` (comma list, one per spawn), `TANDEM_FAKE_LOG` (its argv and its `CLAUDE*` and `NODE_OPTIONS` environment variables with secrets redacted, then every frame the host writes), `TANDEM_FAKE_SESSION_ID`, `TANDEM_FAKE_DELAY_MS` and `TANDEM_FAKE_GATE_FILE` (holds each `control_request` back until the file exists).
- `./gradlew runIde` sandbox IDE with the plugin. Use it to check feel, freezes and focus; agents cannot judge these.
- `./gradlew verifyPlugin` Plugin Verifier against every supported IDE release.
- `./gradlew buildPlugin` installable ZIP in `build/distributions/`.
- `scripts/record-fixture.sh <name> "<prompt>"` records a real CLI transcript as a test fixture. Needs a logged-in `claude`.
- `cd scripts && python3 -m unittest` tests the recording scripts (the redactor's safety net, `copy_transcript.py`, `drive_cli.py`'s control calls) without Gradle or a real `claude`.
- `.github/workflows/drift-fix.yml` runs after every red nightly `CLI contract` run: it classifies the red (`scripts/classify_drift.py`) and, for decoder or reader drift, runs the repository skill `.claude/skills/fix-contract-drift/SKILL.md`, which promotes the run's `contract-recording` artifact to a fixture, patches the decoder or reader and opens a pull request labelled `contract-drift`; a run the CLI did not answer is reported on the issue "CLI contract: the CLI did not answer" instead, as is a red it cannot classify (the comment's first line says the CLI answered), and an agent run that left no branch and no pull request, which also labels that issue `drift-halted`: the fixer does not start while it carries the label, so remove the label (or close the issue) to re-arm the loop. By hand: `gh run download <run id> -n contract-recording -D build/drift/recording`, then `/fix-contract-drift run=<run url> recording=build/drift/recording classification=build/drift/classification.json rehearsal=false date=<YYYY-MM-DD>`. Rehearsal (needs `main`: the federation rule admits nothing else; and an active ruleset on `main` requiring a pull request, a code owner's approving review and approval of the last push, with the Claude App off its bypass list, and enforced (a public repository, or a private one on GitHub Pro or above: the fixer cannot tell an unenforced ruleset from an enforced one, so that is the owner's part); and the repository settings "Allow GitHub Actions to create and approve pull requests" off and "Automatically delete head branches" on, since a merged fix's kept branch makes the fixer refuse every later night until it is deleted: the fixer refuses to start without the ruleset): `gh workflow run cli-contract.yml --ref main -f drift=init-model-key` (or `transcript-content-key`) makes the nightly mutate its own recording (`scripts/make_drift.py --from`) so the fixer fires through `workflow_run` as for a real red; `gh workflow run drift-fix.yml --ref main -f rehearse=true -f mode=<mode>` runs the fixer alone on the mutated `history-reply` fixture; `gh workflow run drift-fix.yml --ref main -f run_id=<id>` fixes, or reports, a red `CLI contract` run now (a run the CLI did not answer rehearses the no-agent path, an issue comment and no spend). Either draft PR is closed, not merged. Repeat a rehearsal after any change to the skill, `classify_drift.py`, `make_drift.py`, `cli-contract.yml` or `drift-fix.yml`.

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
- A red nightly `CLI contract` run usually already has the loop's pull request (`gh pr list --label contract-drift`): review that instead of fixing by hand (docs/launch.md, tactic 7).

## Conventions
- Kotlin, explicit types on public API. Follow the `code-style` skill.
- Validate everything that comes from the CLI; it is an external process that changes weekly.
- Never log prompt contents, file contents or secrets. Log message types and ids.
- Coverage target: 90% of lines in each package of `protocol/` and `session/`, enforced by `koverVerify` in `check`.
- Commits: Conventional Commits (`feat:`, `fix:`, `perf:`, `docs:`, `test:`, `refactor:`).
- PRs: clear title, context + approach + testing notes, CI green.

## Headless and cloud sandboxes
- Gradle needs Maven Central, the Gradle Plugin Portal and `*.jetbrains.com`. The first build downloads the IntelliJ Platform (over 1 GB); run it in the background rather than letting it time out. If a host is refused, stop and name it instead of guessing that code compiles.
- Without a running IDE there is no IntelliJ MCP server. Use `./gradlew test`, `./gradlew verifyPlugin` and compiler output. For UI checks run `xvfb-run ./gradlew integrationTest`, or the sandbox IDE under `xvfb-run` and look at screenshots.
- `scripts/record-fixture.sh` uses whatever sign-in the local `claude` has; in a hosted Claude Code session that is the session's own.

## macOS
- `claude` is found, and run, with the login shell's environment, not the IDE's: an IDE started from the Dock or Finder has launchd's minimal `PATH`. `ide/ShellEnvironment.kt` reads it with `EnvironmentUtil.getEnvironmentMap()`, which can block while the platform runs the shell: never on the EDT. After the settings path and `tandem.claudePath`, `ClaudeBinary.detect` searches that `PATH`, then `~/.local/bin`, `~/.claude/local`, `/opt/homebrew/bin`, `/usr/local/bin`, `~/.npm-global/bin`, `~/.bun/bin`, `~/.volta/bin`. Settings | Tools | Tandem shows where it found it.
- The CLI keeps its sign-in in the macOS Keychain. That is the CLI's business: Tandem never reads the Keychain (hard rule 3).
- Whatever the shell profile exports now reaches the CLI from a Dock-launched IDE exactly as in a terminal, for example `ANTHROPIC_API_KEY` or `ANTHROPIC_BASE_URL`, which can change how the CLI authenticates and who is billed. Tandem itself never reads those values.
- The CLI keys transcripts on its physical working directory (`/private/tmp/…` for `/tmp/…`, `/private/var/…` for temporary directories), and the default APFS volume ignores case. Tandem resolves the project path's links and compares paths with `Files.isSameFile` (`docs/protocol.md` 11.1); tests compute keys from `toRealPath()`. A test that needs such a volume, or Linux, says so with `assumeTrue`.
- The JDK's `WatchService` polls on macOS; Tandem asks it for its 2 s rate, so a session started in a terminal shows up within about 3 s.
- In the sidebar, Backspace (the Mac's "delete" key, also with Command) asks whether to delete a session, as Delete does.
- CI: the `build-macos` job in `build.yml` runs `./gradlew check` on `macos-latest` for every pull request push and every push to `main`. The IDE test runs on a Mac in `macos-ide-test.yml`: nightly, from Actions > macOS IDE test > Run workflow (`gh workflow run macos-ide-test.yml --ref <branch>` once the workflow is on `main`), and on a pull request labelled `macos-ide-test`. On a Mac, run `./gradlew integrationTest` without `xvfb-run`.

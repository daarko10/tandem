# Benchmarks

Every number here names the versions measured, the machine and how. Cells nobody has measured yet say so; nothing here
is estimated.

## Opening a long conversation

Does a plugin get stuck when it opens a long session? The input is a transcript the plugin reads from the CLI's own
folder, so every plugin that lists past sessions can be given the same one.

### Method
- **Input.** `python3 -I scripts/bench/make_long_session.py --turns N --project <path> --config <configDir>` writes
  `<configDir>/projects/<key>/<session id>.jsonl`, the key computed from `<path>` as `docs/protocol.md` 11.1 says. The
  file has the shape CLI 2.1.3xx writes (version `2.1.319`), as the recorded transcripts in
  `src/test/resources/fixtures/transcripts/` show it: prompts with their attachment lines, assistant lines with text,
  thinking and tool_use blocks, tool results, an intact `parentUuid` chain. Replies are Markdown with headings, lists,
  fenced code and inline code, about one in eight 5 to 20 KB long; tools are Bash, Read, Grep, Glob, Tandem's
  `mcp__tandem__edit_file` and `mcp__tandem__write_file`, and one AskUserQuestion exchange. The project is fictitious
  (`src/app/Orders.kt`) and the text lorem-like. The same `--seed` (default 1) gives the same bytes; the size varies by
  a few bytes per line with the length of `<path>`. Nothing generated is committed.

  | Turns | Lines | Bytes (project `/home/u/my proj.v2`) |
  |---:|---:|---:|
  | 100 | 1,197 | 1,290,697 |
  | 400 | 4,997 | 5,706,928 |
  | 1000 | 12,517 | 14,240,587 |

- **Without an IDE.** `LongSessionTest` (`./gradlew test --tests '*LongSession*'`) lists, loads and builds the rows of a
  400-turn session as the sidebar and the chat do (`SessionIndex.list`, `TranscriptReader.read`, `ChatRowsBuilder.build`)
  and prints the wall time of each.
- **In the IDE.** `LongSessionIdeTest` (`xvfb-run ./gradlew integrationTest --tests '*LongSessionIdeTest*'`) installs
  the built plugin ZIP in a fresh IDE per size, generates the session into the CLI's config folder for the project,
  opens the tool window and the sidebar, focuses the session's row and presses Enter. Timed from Enter until the last
  reply's text is in the chat's UI tree, then from Home in the transcript until the first prompt is. The UI is polled
  every 50 ms. Enter, not a click: the UI driver's click alone took about 3.8 s on this machine (`baselineClickMs`,
  opening the sidebar), which would hide the load.
- **Freezes.** The IDE's own watcher, with its threshold lowered to 1 s (`performance.watcher.unresponsive.interval.ms`):
  the `UI was frozen for N ms` lines in `idea.log` and the `threadDumps-freeze-*` folders that appeared during each
  step, read 3 s after it. Between polls the test also times a no-op on the EDT through the driver: the slowest round
  trip shows a stall shorter than the watcher's threshold.
- The numbers go to stdout and to `build/reports/long-session/<turns>.json`.

### Results
Measured 2026-11-07 on a GitHub-hosted-like Linux sandbox, 4 CPUs, 16 GB RAM, under Xvfb. IDE: IntelliJ IDEA Ultimate
2026.2.3 (IU-262.10968.63). One run per size.

Without an IDE (400 turns, Tandem 0.4.1 at `7672c38`): list 536 ms, read 350 ms, row build 420 ms (2,018 entries,
1,429 rows), first run in a fresh test JVM.

In the IDE:

| Plugin | Version | Turns | Enter to last reply | Home to first prompt | Freezes ≥ 1 s (longest) | Slowest EDT round trip |
|---|---|---:|---:|---:|---|---:|
| Tandem | 0.4.1 | 400 | 1.4 s | 0.16 s | 0 | 571 ms |
| Tandem | 0.4.1 | 1000 | 1.4 s | 0.16 s | 0 | 419 ms |
| Claude Code [Beta] (Anthropic) | | | not measured yet | not measured yet | not measured yet | not measured yet |
| CC GUI | | | not measured yet | not measured yet | not measured yet | not measured yet |
| Claude Code with GUI | | | not measured yet | not measured yet | not measured yet | not measured yet |

- The times include the driver's round trips (about 0.1 s for a key press). The 1000-turn session opened as fast as the
  400-turn one. Opening a session from the sidebar also starts its `claude --resume` process (here the fake CLI); how
  the 1.4 s splits between reading, building rows, the process and composing is not measured yet.
- The EDT round trip of 0.4 to 0.6 s while a session opens is a stall below the watcher's 1 s threshold; where it goes
  is not measured yet.

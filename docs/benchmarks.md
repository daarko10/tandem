# Benchmarks

Tandem is measured against the other plugins for Claude Code on the JetBrains Marketplace. Every number here
names the versions measured and how. Cells nobody has measured yet say so; nothing here is estimated.

## Plugin ZIP size and contents
`python3 -I scripts/bench/marketplace_plugins.py` (add `--json` for machine output) downloads the latest update of
each plugin from the Marketplace and inspects the ZIP. These are Marketplace downloads, not measured on a machine:
anyone can re-run the script and get the same numbers for the same versions.

| Plugin | Version | Released | ZIP size | Unpacked | Jars | Node | Webview | Native libs |
|---|---|---|---:|---:|---:|---|---|---:|
| Tandem (35641) | 0.4.1 | 2026-10-30 | 4.0 MB | 4.2 MB | 4 | no | no | 0 |
| Claude Code [Beta] (Anthropic) (27310) | 0.1.14-beta | 2025-12-05 | 11.3 MB | 12.4 MB | 33 | no | no | 12 |
| CC GUI (29342) | 0.6.3 | 2026-10-27 | 28.3 MB | 28.7 MB | 14 | yes | yes | 24 |
| Claude Code with GUI (30313) | 0.35.1 | 2026-11-02 | 11.5 MB | 12.0 MB | 7 | no | yes | 0 |

Measured 2026-11-03 from the JetBrains Marketplace downloads (latest update of each plugin).

- **Unpacked** and **Jars** count the files of the ZIP itself, not the contents of the jars.
- **Node**: a file named `node` or `node.exe`, or a `node_modules` directory, anywhere in the ZIP, nested jars and ZIPs
  included. CC GUI's comes from `node_modules` in its bundled `ai-bridge.zip`; none of the four ships a Node binary.
- **Webview**: an `.html` file plus at least one `.js` file over 1 MB.
- **Native libs**: `.so`, `.dylib` and `.dll` files, nested jars included, one per platform each library is built for
  (for example a JNI library inside a dependency jar).

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

## On a machine: time to first token and memory
These need a real machine, a signed-in `claude` and a person with a stopwatch. They are not measured yet.

### Setup
1. Use one Mac. Write down its model, chip, RAM, macOS version, the IDE and its version, the `claude --version`, and
   each plugin's version (Settings | Plugins).
2. Use one small project for every run, opened fresh (File | Open), with indexing finished before you start.
3. Install exactly one of the three plugins at a time: Tandem, Anthropic's "Claude Code [Beta]", CC GUI. Uninstall the
   others and restart the IDE, so that no other plugin's processes are running.
4. Pick the same model in every plugin (the CLI's `/model` for Anthropic's plugin, which runs the CLI in a terminal)
   and write down which one. Use the same prompt every time:
   `Reply with the word "ready" and nothing else.`
5. Quit other apps. Run each measurement three times per plugin and record the median.

### Time to first token
1. Start a screen recording (Shift-Command-5, "Record Entire Screen") with the menu-bar clock showing seconds
   (System Settings | Control Center | Clock Options | Display the time with seconds).
2. Restart the IDE and open the project. Open the plugin's tool window and start a new session (for Anthropic's
   plugin, its "Run Claude Code" action, then wait for the CLI's prompt).
3. Paste the prompt and press Enter.
4. Stop the recording once the reply has finished.
5. Step through the recording frame by frame (QuickTime: arrow keys). Time to first token is the time from the frame where
   the prompt leaves the input box to the first frame showing any part of the reply. Write it down in seconds, to one decimal.

### Idle and streaming memory
1. Restart the IDE, open the project and wait for indexing to finish. Open the plugin's tool window and start a session,
   but send nothing. Wait one minute.
2. Open macOS Activity Monitor (Memory tab, View | All Processes, Hierarchically). Find the IDE's process and expand it.
3. Idle memory: write down the Memory column of the IDE process, and separately the sum over its child processes
   (`claude`, `node`, any `jcef Helper` or webview helper). Also note the IDE's own heap from the status bar's
   memory indicator (right-click the status bar, Memory Indicator) or Help | Diagnostic Tools | Activity Monitor.
4. Streaming memory: send `Write a 600-word story about a lighthouse.` and, while the reply is still streaming, take the
   same readings. A screen recording with Activity Monitor next to the IDE makes the peak easy to read afterwards.
5. Close the session and the IDE between runs.

### Results
Machine: not measured yet. CLI version: not measured yet. Model: not measured yet.

| Plugin | Version | Time to first token | Idle memory: IDE / children | Streaming memory: IDE / children |
|---|---|---|---|---|
| Tandem | not measured yet | not measured yet | not measured yet | not measured yet |
| Claude Code [Beta] (Anthropic) | not measured yet | not measured yet | not measured yet | not measured yet |
| CC GUI | not measured yet | not measured yet | not measured yet | not measured yet |

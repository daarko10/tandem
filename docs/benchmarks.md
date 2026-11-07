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

## Time to first token
Time to first token (TTFT) is the wall-clock time from the Enter that sends a prompt to the first character of the model's reply
on screen. `scripts/bench/ttft/run.sh` measures it for every plugin the same way, on one machine, with the same IDE build, `claude`
CLI, model and prompt, and keeps the evidence: anyone can re-run it and check each number against the frame it was read from.

### How it is measured
- **The IDE.** IntelliJ IDEA 2026.2.3 (JetBrains' Air needs 2026.2), started fresh for every run by the IDE Starter
  (`./gradlew benchTtft`, `TtftBenchmarkIdeTest`) on a one-file project, with exactly one of the plugins installed from its
  Marketplace ZIP (Tandem: the ZIP this repository builds), under Xvfb at 1600x1000. The IDE's environment carries
  `ANTHROPIC_MODEL=sonnet`, so every plugin that runs the CLI with its defaults asks for the same model, and
  `DISABLE_AUTOUPDATER=1`, so the CLI stays at the version measured. The model each run got is read from the CLI's own transcript
  for the run's project (its `model` field only).
- **The prompt.** The test opens the plugin's UI, types `Reply with exactly the single word PONG and nothing else.` like a person
  (15 ms between keys), waits 0.7 s, writes the epoch millisecond to a marks file and presses Enter. A cold run is this first prompt
  after the IDE starts; the warm run is a second prompt, `... nothing else. (2)`, in the same session, sent 3 s after the first reply.
  Each plugin gets one IDE start per run index, and the plugins take turns (run 1 of every plugin, then run 2), so network variance
  spreads over all of them.
- **The recording.** ffmpeg grabs the screen at 30 fps, lossless, and stamps every frame with the wall clock it was grabbed at
  (`-use_wallclock_as_timestamps 1 -copyts`), the same clock as the Enter's mark.
- **The detection.** `scripts/bench/ttft/detect.py` reads the frames after the Enter with tesseract (only the plugin's tool window or
  editor area when the test can name its bounds) and finds the first frame with a text line that is `PONG` on its own (a bullet or
  prompt mark before it is ignored) below the prompt's echo. The prompt contains PONG too, but never as a line of its own, and a warm
  run's reply must sit under the `(2)` prompt, not the cold reply above it. It probes every 0.5 s and then narrows down frame by
  frame, so the resolution is one frame, 33 ms. The frame is kept, cropped, with the timestamps drawn on it, in
  [`benchmarks/evidence/`](benchmarks/evidence/), next to the full results in `benchmarks/ttft-<date>.json`.
- **The floor.** The same pipeline measures the IDE's own terminal running `echo PONG`. Its number is what the method itself adds
  (key events through the Driver, the frame interval, rendering), and every plugin's number includes it.
- **Setup is not timed.** A plugin's one-time setup (the CLI's folder trust question, an SDK install, picking the model) happens
  before the prompt is typed. A plugin the harness could not get a reply from is listed as not measured, with the reason and a
  screenshot, never estimated.

To re-run: `scripts/bench/ttft/run.sh` (it needs a signed-in `claude` on `PATH`, Xvfb, ffmpeg and tesseract; see its header for
options), or the manual workflow `.github/workflows/bench.yml`, which uses the repository secret `ANTHROPIC_API_KEY` when set and
uploads the results as an artifact.

### Results
<!-- ttft:begin -->
Measured 2026-11-07 by `scripts/bench/ttft/run.sh`, with every run in [`benchmarks/ttft-2026-11-07.json`](benchmarks/ttft-2026-11-07.json) and each detected frame in [`benchmarks/evidence/`](benchmarks/evidence/).

- Machine: Intel(R) Xeon(R) Processor @ 2.10GHz, 4 cores, 16 GB RAM, Ubuntu 24.04.5 LTS, kernel 6.18.44-fc-v310 (a cloud container, not a desktop).
- IDE: IntelliJ IDEA 2026.2.3 (build 262.10968.63), under Xvfb at 1600x1000, recorded at 30 fps.
- CLI: `claude` 2.1.319 (Claude Code). Model asked for: sonnet (ANTHROPIC_MODEL=sonnet in the IDE's environment).
- Prompt: `Reply with exactly the single word PONG and nothing else.`; the warm run's: `Reply with exactly the single word PONG and nothing else. (2)`.

Time to first token is the wall-clock time from the Enter that sends the prompt to the first recorded frame showing the reply, in seconds: the median, then each run in order. A cold run is the first prompt after the IDE starts; a warm run is the second prompt in the same session, sent right after the first reply.

| Plugin | Version | Model | Cold | Warm |
|---|---|---|---|---|
| Floor: IDE terminal, `echo PONG` | IDE 2026.2.3 | none | **0.12 s** (0.09, 0.14, 0.12, 0.10, 0.13) | **0.08 s** (0.07, 0.09, 0.09, 0.08, 0.07) |
| Tandem (35641) | 0.4.1 | claude-sonnet-5-5 | **2.18 s** (2.18, 1.75, 2.33, 2.13, 2.46) | **1.67 s** (1.35, 1.45, 1.81, 2.01, 1.67) |
| Claude Code [Beta] (Anthropic) (27310) | 0.1.14-beta | claude-sonnet-5-5 | **1.75 s** (1.78, 1.79, 1.75, 1.67, 1.72) | **1.69 s** (1.64, 1.69, 1.97, 1.66, 2.17) |
| Air (JetBrains) (33314) | 262.8665.532-linux-x86_64 | | not measured | not measured |
| CC GUI (29342) | 0.6.4 | | not measured | not measured |
| Claude Code with GUI (30313) | 0.35.3 | | not measured | not measured |

The first row is the method's floor: the IDE's own terminal running `echo PONG`, measured the same way: the time from the Enter to the first frame showing the output, which is what the recording, the Driver's key events and the detection add to any number above.

- **Air (JetBrains)** not measured: Air 262.8665.532 (linux-x86_64) lists Claude as an agent, but its chat view does not take a Claude subscription: after the Claude agent was installed from the ACP registry, the prompt got 'Choose AI Provider', whose 'Claude Subscription' entry says 'This will open the Air Terminal in a new tab. Anthropic TOS does not allow us to use GUI in this case' (an earlier attempt read 'Claude subscription works in Terminal view only'); the other entries are an Anthropic Console sign-in, a JetBrains AI sign-in or an API key. The harness never signs in or hands over credentials, and this machine's CLI is signed in with a subscription. ([screenshot](benchmarks/evidence/air-cold-1-failed.png))
- **CC GUI** not measured: CC GUI 0.6.4 installs its Claude Code SDK (v0.3.319, from npm) on first use, which the harness did; the prompt then got 'Provider Not Configured: No AI provider is configured yet, or access to the local configuration has not been authorized', which needs the user to set up a provider, or to authorize the plugin's access to the local Claude configuration, in its Provider Management. The harness does not grant a plugin access to credentials or configuration. ([screenshot](benchmarks/evidence/ccgui-cold-1-failed.png))
- **Claude Code with GUI** not measured: Claude Code with GUI 0.35.3 opens its chat as a webview editor tab (with Fable 5.1 preselected as its model), but in this harness (JCEF under Xvfb, run as root with JCEF's sandbox off) the webview took no clicks or keys from the IDE Starter's robot: the prompt box stayed empty, and its model menu and usage-statistics banner did not respond, in four attempts. This is likely a limit of the harness, not of the plugin. ([screenshot](benchmarks/evidence/ccwithgui-cold-1-failed.png))
<!-- ttft:end -->

## On a machine: memory
Memory needs a person reading Activity Monitor. It is not measured yet.

### Setup
1. Use one Mac. Write down its model, chip, RAM, macOS version, the IDE and its version, the `claude --version`, and
   each plugin's version (Settings | Plugins).
2. Use one small project for every run, opened fresh (File | Open), with indexing finished before you start.
3. Install exactly one of the three plugins at a time: Tandem, Anthropic's "Claude Code [Beta]", CC GUI. Uninstall the
   others and restart the IDE, so that no other plugin's processes are running.
4. Pick the same model in every plugin (the CLI's `/model` for Anthropic's plugin, which runs the CLI in a terminal)
   and write down which one.
5. Quit other apps. Run each measurement three times per plugin and record the median.

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

### Memory results
Machine: not measured yet. CLI version: not measured yet. Model: not measured yet.

| Plugin | Version | Time to first token | Idle memory: IDE / children | Streaming memory: IDE / children |
|---|---|---|---|---|
| Tandem | 0.4.1 | 2.18 s (cold median, see above) | not measured yet | not measured yet |
| Claude Code [Beta] (Anthropic) | 0.1.14-beta | 1.75 s (cold median, see above) | not measured yet | not measured yet |
| CC GUI | 0.6.4 | not measured (see above) | not measured yet | not measured yet |

# Tandem

A fast, native JetBrains plugin for Claude Code. Free and open source.

> Status: early. On the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35641-tandem) since 0.4.1.
> macOS and Linux today; Windows in progress.
> Edits go through the IDE: the CLI's own Edit and Write tools are disabled in Tandem, which serves its own. The editable
> diff is tested against a fake CLI that replays real recordings and by hand on macOS; expect rough edges.

## Why
- **Native UI.** Built with Jewel (Compose), not an embedded browser. No black screens after IDE updates.
- **No Node, no bundled SDK.** Tandem drives the `claude` CLI you already have, so new models and features
  work the day the CLI ships them.
- **Edits through the IDE.** Every edit Claude proposes opens in the IDE's own diff, and you can change the proposal
  by hand there. Accept (Ctrl+S, ⌘S in the diff) writes it through the editor, so undo, unsaved buffers and local
  history work, saves the file, and tells Claude what the file now says, your changes included. Reject sends your reason.
  The Accept edits mode, or Accept all in a diff (for the rest of that turn), skips the diff. `@` in the prompt picks a project file
  (a file with unsaved changes is sent from the editor), and the editor's selection goes with the prompt as a chip you can remove.
- **Honest status.** If your model is unavailable and the CLI falls back to another, you see it.
- **Your sign-in stays with the CLI.** Tandem never reads credentials or API keys.

## Compared with the other plugins
CC GUI is the largest community plugin for Claude Code: a React webview, a Node sidecar and a pinned Agent SDK. Its open
issues, studied in October 2026, trace back to that design. Issue numbers link to
[its tracker](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues).

| Problem in other plugins | Why it happens | What Tandem does |
|---|---|---|
| Black or white screen after IDE updates ([#813](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/813), [#1302](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1302), [#1907](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1907)) | The chat is a web page in the IDE's embedded browser (JCEF) | Draws the chat with Jewel (Compose), the IDE's own UI toolkit. No browser |
| Typing, input method and selection bugs ([#1854](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1854), [#1465](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1465)) | Keys pass through the embedded browser | Uses Jewel text fields, so input goes through the IDE |
| Spawn errors on Windows, Node processes piling up, memory ([#1928](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1928), [#953](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/953), [#1324](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1324)) | A Node sidecar process sits between the IDE and Claude | No Node. Tandem starts your `claude` directly, one process per live session, and stops it with the session. Windows support is in progress |
| Missing models, broken `/compact`, "works in the CLI" ([#1517](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1517), [#592](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/592)) | A bundled SDK falls behind the CLI you installed | Runs the CLI you installed, so its models and commands are the ones you get |
| Model switch silently ignored ([#1462](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1462), [#1741](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1741)) | The model is fixed when the process starts | Switches with the CLI's `set_model` request, checks the model the CLI reports, and says so in the chat when it differs or falls back |
| IDE freezes waiting on the bridge ([#1859](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1859), [#1801](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1801)) | Blocking I/O on the IDE's UI thread | Process I/O runs in coroutines; the UI thread never waits on the CLI |
| History lost, or not resumable in the CLI ([#1852](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1852), [#1259](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1259)) | The plugin keeps its own session format | Uses the CLI's own sessions and `--resume`. A session started in a terminal shows up in the sidebar |
| Wrong diffs, diff exceptions ([#1891](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/1891), [#275](https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues/275)) | Edits are written to disk, then diffed | Shows the IDE's own diff first, then applies the edit through the editor (unless you chose Accept edits) |

## How it works
Tandem starts the `claude` binary you installed, with `--output-format stream-json --input-format stream-json`, and
renders what it streams with Jewel. Its edit tools are an SDK MCP server that the CLI reaches over the same control
channel. Nothing else runs: no Node, no sidecar, no browser. [docs/protocol.md](docs/protocol.md) describes the protocol
as the CLI really speaks it.

## Size
Plugin ZIPs on the JetBrains Marketplace, measured 2026-11-03 with `scripts/bench/marketplace_plugins.py`
(details and the method in [docs/benchmarks.md](docs/benchmarks.md)):

| Plugin | Version | ZIP size |
|---|---|---:|
| Tandem | 0.4.1 | 4.0 MB |
| Claude Code [Beta] (Anthropic) | 0.1.14-beta | 11.3 MB |
| Claude Code with GUI | 0.35.1 | 11.5 MB |
| CC GUI | 0.6.3 | 28.3 MB |

Time to first token and memory are not measured yet.

## Requirements
- A JetBrains IDE, 2026.1 or newer.
- [Claude Code](https://code.claude.com) installed and signed in (`claude --version` works in a terminal).

## Install
Settings | Plugins | Marketplace, search for **Tandem**, or open
[plugins.jetbrains.com/plugin/35641-tandem](https://plugins.jetbrains.com/plugin/35641-tandem). Then open the Tandem tool
window and type a prompt.

## Build from source
```bash
./gradlew runIde        # sandbox IDE with the plugin
./gradlew test          # unit and protocol fixture tests
./gradlew buildPlugin   # ZIP in build/distributions/
```
Install the ZIP with Settings | Plugins | ⚙ | Install Plugin from Disk.

## Working on Tandem with Claude Code
Run `./scripts/setup-agent.sh` once. It installs the plugins, language server and docs servers this repo expects.
Then read `CLAUDE.md`, `docs/design.md` and `docs/protocol.md`.

## License
Apache 2.0. Tandem is an independent project, not affiliated with or endorsed by Anthropic or JetBrains.
Claude and Claude Code are trademarks of Anthropic, PBC.

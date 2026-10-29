# Tandem

A fast, native JetBrains plugin for Claude Code. Free and open source.

> Status: early development. macOS and Linux today; Windows in progress. Not on the JetBrains Marketplace yet.
> Edits go through the IDE: the CLI's own Edit and Write tools are disabled in Tandem, which serves its own. The editable
> diff is tested against a fake CLI that replays real recordings, and has not had a pass in a real project yet.

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

## Requirements
- A JetBrains IDE, 2026.1 or newer.
- [Claude Code](https://code.claude.com) installed and signed in (`claude --version` works in a terminal).

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

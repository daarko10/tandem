# Tandem

A fast, native Claude Code chat for JetBrains IDEs. Free and open source.

> Status: early development. Not on the JetBrains Marketplace yet.

## Why
- **Native UI.** Built with Jewel (Compose), not an embedded browser. No black screens after IDE updates.
- **No Node, no bundled SDK.** Tandem drives the `claude` CLI you already have, so new models and features
  work the day the CLI ships them.
- **Edits through the IDE.** Changes land via the editor, so undo, unsaved buffers and the native diff viewer work.
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

## Working on Tandem with Claude Code
Run `./scripts/setup-agent.sh` once. It installs the plugins, language server and docs servers this repo expects.
Then read `CLAUDE.md`, `docs/design.md` and `docs/protocol.md`.

## License
Apache 2.0. Tandem is an independent project, not affiliated with or endorsed by Anthropic or JetBrains.
Claude and Claude Code are trademarks of Anthropic, PBC.

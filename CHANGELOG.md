# Changelog

All notable changes to Tandem. The newest version's section becomes the plugin's change notes on the Marketplace.

## [Unreleased]

## [0.5.0] - 2026-11-09

### Added
- Tandem runs on Windows, with the CLI installed by npm (`claude.cmd`, whose `claude.exe` Tandem runs directly) or by the
  native installer (`%USERPROFILE%\.local\bin\claude.exe`). `! command` runs in Git Bash, as the CLI's Bash tool does,
  and in `cmd.exe` with a note when Git Bash is missing. Every test runs on `windows-latest` in CI; the plugin has not yet
  been tried on a real Windows machine, so reports from Windows users are welcome in the issue tracker.
- Settings | Tools | Tandem says when a saved `claude` path is not something Windows can run (no `.exe`, `.cmd` or `.bat`).

### Fixed
- The project file list behind the `@` palette could fail at project open with a `NullPointerException`: its first read ran
  before the service had finished constructing.
- Closing a session whose CLI had left a child process behind could wait forever on Windows: the session's stdout read now
  runs on a thread the close does not wait for, as the stderr read already did, and a close returns once the CLI's process
  tree is gone.
- On Windows, a file Tandem creates keeps the `\n` line endings Claude sent instead of the project's default `\r\n`, and a
  file outside the project is shown as `C:\…` in the `@` palette.

## [0.4.2] - 2026-11-07

### Fixed
- Files opened from outside the project (File | Open) are listed by `@` and can be edited; a file counts as part of the
  workspace while it is open in an editor.

### Changed
- A file outside the project folder is shown by its absolute path in tool rows and diff titles, instead of a misleading
  relative one.

## [0.4.1] - 2026-10-30

### Fixed
- Compatible with the 2026.3 EAP: the `+` menu's rows and the small text no longer call Jewel helpers that 2026.3 removes or
  makes internal.

## [0.4.0] - 2026-10-28

### Added
- Claude's edits go through the IDE. Each one opens in the IDE's own diff, where you can change the proposal by hand.
  Accept (Ctrl+S, ⌘S) applies it through the editor, so undo, unsaved buffers and local history work, saves the file and
  tells Claude what the file now says, your changes included. Reject sends your reason. Accept all skips the diff for the
  rest of the turn, and the Accept edits mode skips it altogether.
- `@` in the prompt picks a project file; a file with unsaved changes is sent from the editor.
- The editor's selection goes with the prompt as a chip you can remove.

## [0.3.0] - 2026-10-26

### Added
- The context ring opens a breakdown of the context window: by category, MCP tools, memory files and skills.
- A `/usage` card (and `/cost`) with this session's cost and tokens and the plan's limits.
- A paged `+` menu: Add files or photos, Add folder, Slash commands, Connectors (a switch per claude.ai connector) and
  Plugins by source.
- Send while Claude works, or queue a message for after the turn with Ctrl+Enter (⌘↵); queued messages can be edited
  or cancelled.
- Fork from here on every finished reply.
- Shift+Tab in the composer cycles the permission mode.
- One tab per live session in the tool window's header.

## [0.2.1] - 2026-10-22

### Fixed
- The slash palette's widest row is never cut, and a resumed session has its palette and MCP panel before the first prompt.
- The CLI's end is shown when its output is cut off.

## [0.2.0] - 2026-10-22

### Added
- An MCP servers panel: list, toggle, reconnect and sign in from the IDE.
- A `/` slash-command palette.
- Bash mode: a `! command` runs in your login shell and its output goes to Claude.

## [0.1.2] - 2026-10-22

### Changed
- The working indicator is drawn as orbs, not font glyphs.

### Fixed
- The model menu offers the CLI's aliases before any session has started.

## [0.1.1] - 2026-10-22

### Fixed
- Fixes from the first test on macOS.

## [0.1.0]

### Added
- A native Jewel chat that drives your own `claude` CLI over stream-json.
- Several live sessions in a sidebar, with rename, fork and delete, and history loaded from the CLI's own transcripts.
- Model, effort and permission-mode pickers, permission cards and questions, and a notice whenever the CLI falls back
  to another model, retries or fails.

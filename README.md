# claude-base

Private, generic home base for working with Claude Code remotely (claude.ai/code, mobile) without a computer.

## Use
1. In Claude Code on the web, connect GitHub and pick `daarko10/claude-base` (or any project repo).
2. `CLAUDE.md` gives Claude my stack, conventions and working style; `.claude/commands/` has slash commands:
   - `/review` review the branch diff
   - `/new-project <name>` scaffold a project folder
   - `/ship` test, commit, push
3. For a real project, create a repo from this template (or copy `CLAUDE.md` + `.claude/`) so the context travels with it.

## Add
Extend `CLAUDE.md` whenever you correct Claude twice about the same thing.

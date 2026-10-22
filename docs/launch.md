# Launch tactics to keep in mind while developing

Read this before any work described as ship, release, publish, launch, announce, post, demo or benchmark,
before editing docs/protocol.md or the fixtures, and before changing a claim in README.md or plugin.xml.
Milestones follow docs/design.md; this file says what each one unlocks in public and how that shapes the
work in between. There are no dates here: milestones drive the schedule, not the other way round.

## What goes public, and in what order
Tandem goes public three times. The order comes from dependencies.

1. **The protocol reference.** An article, plus the material behind it in this repository, on how the
   `claude` CLI really behaves over stream-json: spawn flags, envelopes, the control channel, sessions and
   forks, the gotchas, and the fields the SDK leaves undocumented. Every claim names the fixture that proves it
   and the CLI version it was recorded on. Purpose: the document people building on the CLI link to, published
   before the plugin is. Depends only on docs/protocol.md and the fixtures, so it can go out before the plugin.
2. **The launch.** Tandem 0.1 on the JetBrains Marketplace, announced once with a single Show HN post. A Show HN
   is not repeatable and most of its effect lands in the first 48 hours, so a launch with a visible bug costs
   more than a later launch. Depends on Milestone 2 and every launch gate below.
3. **The drift-loop article.** How a plugin for Claude Code keeps itself working against a CLI that changes
   weekly: the nightly contract run, the fixture recorder and redactor, the fake CLI, and a scheduled agent
   that turns a red run into a pull request. Depends on that loop having fired on a real CLI change, not a
   rehearsal.

Nothing outward-facing overstates what the code does, and nothing is done to inflate stars (no bought stars,
star swaps or vote rings, ever): GitHub removes them, and the project's credibility is the point.

## Tactics while developing
1. **Build everything to be shown.** The launch GIF has three scenes: an edit opening as a native diff and
   being accepted; two sessions running in parallel; a session started in a terminal appearing in the sidebar.
   A change that cannot appear in one of those scenes, in a benchmark or in the comparison table needs a stated
   reason to exist before Milestone 3.
2. **Keep the demo re-recordable.** The three scenes are driven by fixtures or a script under `scripts/`, so
   the GIF can be recorded again after any UI change without staging it by hand.
3. **docs/protocol.md is a public document from now on.** Write every addition for a stranger: no references
   to spec or plan section numbers, the CLI version each behaviour was observed on, and the fixture that
   shows it. The gotchas table stays near the top.
4. **The fixtures will be public.** Record from an empty directory, redact in the same run, and treat a
   `<leaked-N>` warning as a stop, not a note. Before anything is published, grep the fixtures and transcripts
   by hand for `@`, UUIDs, `toolu_`, `msg_`, hostnames and account names.
5. **Benchmarks are a feature.** Time to first token, idle and streaming memory, and plugin ZIP size are
   measured by a repeatable script kept in the repository, against CC GUI and the official plugin on the same
   machine, so the numbers in the README and the article can be re-run by anyone.
6. **The comparison table is live.** design.md's table of CC GUI pains and Tandem decisions gains a row each
   time a decision is proven in code or a benchmark, and the README version never lists a row that `main`
   does not yet deliver.
7. **The contract run is the immune system.** Protocol work is not done until `cli-contract.yml` covers it.
   A red run is the first thing to fix in any session, and the fix is a pull request carrying the run's recording
   promoted to a fixture and the decoder change, with a description written for a reader: those pull requests are
   the material of the drift-loop article.
8. **Hand-test findings become tests.** Every item from the owner's `runIde` or real-project pass ends as a
   fixture, a platform test or an IDE Starter test, so the next polish round starts from a higher floor.
9. **README.md and plugin.xml say only what `main` does today.** A roadmap item is labelled as one. Hard rule
   5 (never fail silently) applies to our own claims. Until Milestone 2 is merged, both say edits are coming,
   not that they work, and the README's first screen carries the project's status line.
10. **"A plugin for Claude Code", never "a Claude Code plugin"** (hard rule 8), in code, docs, posts and the
    Marketplace listing alike.

## Milestones and what each unlocks
- **Protocol reference ready** (independent of plugin milestones). Done when tactics 3 and 4 hold for the whole
  of docs/protocol.md and every fixture. Unlocks: the article.
- **Milestone 2, IDE-native edits** (design.md). Done when Claude's edits apply through `WriteCommandAction`,
  a native diff precedes every edit unless the user chose auto-accept, and editor selection and `@file`
  context reach the CLI. Unlocks: Marketplace submission the day it works, and the launch GIF.
- **Launch-ready**: every launch gate below is green, in any order after Milestone 2.
- **Launch**: Show HN, once. Unlocks: Milestone 3 (parity), with real users filing the backlog.
- **Drift loop closed.** Done when a red `cli-contract.yml` run promotes its recording to a fixture, patches the
  decoder, runs the tests and opens a pull request without a human, and that has happened once on a real CLI change,
  the owner merged it and the next nightly was green (the owner appends the PR number, the CLI version and the fixture
  name here). Unlocks: the drift-loop article.

## Launch gates (all required; none is waived by speed)
- **Marketplace listing** approved and installable. Review is outside our control; a ZIP-only launch is not a
  launch.
- **Outside users:** at least ten people installed from the Marketplace and used Tandem on their own projects;
  every issue that blocks a first session is closed.
- **Owner feel test:** the owner's own `runIde` and real-project pass after Milestone 2 and the polish round it
  produces. Agents cannot pass this gate: feel, focus and freezes are judged by hand (CLAUDE.md).
- **Windows:** tested by one real user, or the README's first screen says "macOS and Linux today; Windows in
  progress". Silence is not an option.
- **Launch assets:** the GIF (tactic 1), the benchmarks (tactic 5), the comparison table in the README
  (tactic 6), and repository topics set: claude-code, jetbrains-plugin, stream-json, intellij.

## Outward-facing rules
- Agents prepare; the owner presses the button. Nothing is posted, published, submitted or made public by an
  agent (CLAUDE.md, "Working in this repo").
- One Show HN for the product. The two articles are link posts with their own URLs.
- Benchmarks and comparisons name the versions measured and the machine they ran on.

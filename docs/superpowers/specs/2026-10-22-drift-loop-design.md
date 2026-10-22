# The drift loop

Status: draft for review. Follows `docs/launch.md` (tactic 7 and the milestone "Drift loop closed"), `CLAUDE.md`
(hard rule 6) and `docs/protocol.md` (the fixtures and their sources). Time matters here: do not spend time that can
be avoided, and the earlier a correct result is obtained, the better.

## Goal

Turn a red nightly `CLI contract` run into a pull request without a human. The nightly (`.github/workflows/cli-contract.yml`)
installs the newest `claude`, records a one-shot reply and a `--session-id` run, decodes the stream with Tandem's
protocol layer and loads the transcripts with its reader. Today a red run waits for someone to download the recording,
work out what changed, re-record a fixture and patch the decoder. After this change a second workflow, `drift-fix.yml`,
does that work the same night and leaves the owner one decision: merge the pull request or close it with a comment.

The milestone in `docs/launch.md` is met when such a pull request has been opened for a real CLI change, not a
rehearsal, and the owner has merged it.

## Non-goals (deferred)

- Widening what the nightly records (the bidirectional control protocol, scenarios, version comparison). Listed under
  "Follow-up" at the end; this design only makes the nightly's artifact fully redacted and its failure message readable.
- Fixing a run that is red because the CLI could not be reached or would not answer. That is authentication, billing or
  an outage, not code; the loop reports it and stops.
- Merging. Nothing in this design pushes to `main`, merges, publishes or posts outside the repository (`docs/launch.md`,
  "Outward-facing rules").

## How a red run presents

The nightly's steps run in order and the first failure ends the run; only the upload of the recording runs regardless.
The artifact `contract-recording` therefore holds whatever `build/contract` had when the run stopped. Three kinds of red
exist, and they are told apart by which step failed and what the artifact holds:

| Kind | Failing step | What the artifact holds | Cause |
|---|---|---|---|
| 1, the CLI did not answer | "Fetch an identity token" (an `::error title=Workload Identity Federation::` line), "Record live transcripts" (no `system/init`, so `copy_transcript.py` exits), or "Check that the CLI authenticated and answered" (`::error title=CLI contract::The CLI did not answer: …`, with `apiKeySource` on the summary line) | nothing, or `contract-hello.ndjson` whose `result` has `is_error: true` and no `assistant` line from the model (fixture `invalid-model`, CLI 2.1.302, shows that shape: a synthetic `assistant` line with `model: "<synthetic>"` and `result{is_error: true, terminal_reason: "api_error"}`) | the federation rule, the service account, the workspace's spend limit, the API, or a broken `@anthropic-ai/claude-code` publish |
| 2, decoder drift | the Gradle step, test `live transcripts decode with every core type recognised` in `ContractTest` | `contract-hello.ndjson`, `transcripts/contract-hello.jsonl`, `transcripts/session-id.jsonl` | a core type (`assistant`, `user`, `result`, `stream_event`, `control_request`, `control_response`) or a line without `type` decoded to `Unknown`, or no `system/init` or `result` was recognised |
| 3, reader drift | the Gradle step, test `live transcripts load with the reader` | the same three files | `TranscriptReader` found no user or no assistant entry in one of the two `.jsonl` files |

The Gradle step's console names the failing test and the line in `ContractTest.kt` but not the assertion message (the
file and the unrecognised types). The message is in `build/test-results/test/*.xml`, which the nightly does not upload.

## Decisions

### The trigger

A second workflow, `.github/workflows/drift-fix.yml`, named `Drift fix`, runs on:

- `workflow_run` of the workflow named `CLI contract`, `types: [completed]`. The job runs only when
  `github.event.workflow_run.conclusion == 'failure'` and `github.event.workflow_run.head_branch == 'main'`. A
  `workflow_run` job checks out `main` at its head and presents the OIDC subject `repo:daarko10/tandem:ref:refs/heads/main`,
  the one the federation rule admits (`cli-contract.yml` says so in its header).
- `workflow_dispatch` with two inputs: `rehearse` (boolean, default `false`; see "The rehearsal") and `run_id` (string,
  optional: the id of a red `CLI contract` run to fix now, for a run that happened before this workflow reached `main`
  or whose automatic firing was skipped). Dispatch from `main`: a dispatch from any other branch fails at the federation
  exchange.

The whole job is guarded by `github.repository == 'daarko10/tandem'`, a `concurrency` group `drift-fix` without
cancellation (one fixer at a time; a second firing waits), `timeout-minutes: 90`, and these permissions: `id-token: write`
(the federation exchange and the Claude GitHub App token exchange), `contents: write` (the fix branch), `pull-requests:
write`, `issues: write` (the kind-1 report) and `actions: read` (the red run's artifact and log). The workflow file must
exist on `main` for `workflow_run` to fire at all, so the first real firing happens only after this branch is merged.

The fix pull request cannot restart the loop: `cli-contract.yml` has no `push` or `pull_request` trigger, `drift-fix.yml`
names only `CLI contract` in `workflows:`, and the fixer never edits anything under `.github/` (see "Safeguards"). The
`Build` workflow that runs on the fix PR is not in the fixer's filter.

### The red run's recording is the new fixture; nothing is re-recorded in the fixer

The fixer takes the fixture from the red run's `contract-recording` artifact instead of running `claude` again, for
three reasons:

1. **The bytes that failed are the bytes to test.** The artifact's `contract-hello.ndjson` and `transcripts/contract-hello.jsonl`
   were redacted by `scripts/record-fixture.sh` in the red run itself (ids as `<key-N>`, paths as `<path>`, the
   `system/init` lists emptied), with the `<session_id-N>` placeholders the fake CLI expects. They are a fixture already;
   a re-recording could reproduce a different reply, a different model or a different shape.
2. **No nested CLI can authenticate inside the fixer.** `anthropics/claude-code-action` removes `ACTIONS_ID_TOKEN_REQUEST_URL`
   and `ACTIONS_ID_TOKEN_REQUEST_TOKEN` from the environment of the `claude` it runs, so a `claude` started by the
   agent (as `record-fixture.sh` would) cannot fetch a GitHub identity token; the token file it inherits has a `jti`
   the action's own CLI has already spent (one exchange per token), and a token fetched earlier is dead within five
   minutes. Recording inside the agent is a dead end, not a configuration problem.
3. **No second identity token, no second process, no second spend.** The fixer's only live CLI is the agent itself.

A re-recording is still needed when the drift is in something the one-shot recording does not show: a control response,
a `can_use_tool` request, a fork. Then the fixer job records it in a `run:` step placed **before** the action step, with
`uses: ./.github/actions/anthropic-token` immediately before that `run:` step (its own, fresh token file; the action
later fetches another for the agent), and `scripts/record-scenario.sh <scenario> build/drift/recorded` leaves the
redacted result in the checkout for the skill to promote. This design wires no such step: the nightly records nothing
bidirectional yet (see "Follow-up"), so there is nothing to re-record. The skill names `build/drift/recorded` as the
place a re-recording appears, and the follow-up adds the step together with the scenario.

### Two small changes to the nightly

The nightly keeps its name, trigger, steps and artifact name. Two additions:

- `transcripts/session-id.jsonl` is redacted before upload. Today the `--session-id` step copies it with `copy_transcript.py`
  alone, so the artifact of a public repository carries the runner's paths, the literal session id, API message ids,
  the branch name and the prompt for fourteen days, and a fixer would be promoting raw data. The step copies into the
  temporary directory and runs `scripts/redact_fixture.py` into `build/contract/transcripts/session-id.jsonl`, the
  same two commands `record-fixture.sh` runs.
- A step with `if: failure()` after the Gradle step prints every `<failure message="…">` from `build/test-results/test/*.xml`
  as an `::error title=CLI contract::` annotation, so the run log names the file and the unrecognised types and a
  human can read a red run without downloading anything.

### The fixer runtime

One job, `fix`, on `ubuntu-latest`:

1. `jlumbroso/free-disk-space@v2.0.0` (the same settings as `build.yml`: the platform tests need the room),
   `actions/checkout@v7` with `persist-credentials: false` (so the only GitHub credential the agent holds is the App
   token the action exports; see step 7), `actions/setup-java@v6` (zulu 21), `gradle/actions/setup-gradle@v6`.
2. **Fetch the recording.** `gh run download <run id> -n contract-recording -D build/drift/recording` with
   `GITHUB_TOKEN`, where the run id is `github.event.workflow_run.id` or the `run_id` input. A missing artifact is not an
   error of the fixer: it is a kind-1 red (the record step wrote nothing), and the step records that for step 4.
   In a rehearsal this step is replaced by `scripts/make_drift.py` (see "The rehearsal").
3. **Run the contract test against the recording.** `./gradlew test --tests '*ContractTest*' -Dtandem.contractDir=build/drift/recording`,
   `continue-on-error: true`. This reproduces the red locally and leaves the assertion messages in
   `build/test-results/test/`.
4. **Classify.** `python3 -I scripts/classify_drift.py build/drift/recording build/test-results/test` prints one JSON
   object: `kind` (`cli-did-not-answer`, `decoder`, `reader` or `unknown`), `cli_version` (from the recording's
   `system/init.claude_code_version`; `null` without one), `detail` (the error text, or the assertion message) and
   `files` (the artifact files involved). The rules, in order: no recording or no `contract-hello.ndjson` → `cli-did-not-answer`
   ("the run left no recording"); `result.is_error` truthy or no `assistant` line → `cli-did-not-answer` with the
   result text or `errors` (first 400 characters) and `apiKeySource`; a failure of the decode test → `decoder` with its
   message; a failure of the reader test → `reader` with its message; both → `decoder` (the one PR fixes both, decoder
   first); test results absent or neither test failed → `unknown`.
5. **Kind 1 and unknown stop here, without the agent.** The step comments on the standing issue titled
   "CLI contract: the CLI did not answer" (label `contract-drift`), creating it when none is open, with the run link,
   `kind`, `cli_version` and `detail`. One issue, one comment per red run, no branch, no PR, no agent spend. The job
   ends green: the loop did its work.
6. **Skip when a fix is already waiting.** `gh pr list --label contract-drift --state open` non-empty → the step
   comments on that PR ("the nightly of <date> is still red: run <link>") and the job ends. A red that persists for
   a week opens one PR, not seven.
7. **The agent.** `anthropics/claude-code-action@v1` with the federation inputs `anthropic_federation_rule_id`,
   `anthropic_organization_id`, `anthropic_service_account_id` and `anthropic_workspace_id` set to the same four
   identifiers `cli-contract.yml` carries in `env:` (they are not secrets), and no `github_token` input, so the action
   authenticates to GitHub as the Claude GitHub App and the branch it pushes and the PR it opens start the `Build`
   workflow (a PR opened with `GITHUB_TOKEN` would leave its CI waiting for approval). The prompt is
   `/fix-contract-drift run=<run url> recording=build/drift/recording classification=build/drift/classification.json rehearsal=<true|false>`.
   `claude_args`:

   ```
   --model claude-opus-5-5
   --max-turns 60
   --allowedTools "Read,Grep,Glob,Edit,Write,Bash(./gradlew:*),Bash(python3:*),Bash(git:*),Bash(gh auth setup-git),Bash(gh pr:*),Bash(gh issue:*),Bash(cp:*),Bash(ls:*),Bash(cat:*),Bash(grep:*),Bash(date:*)"
   ```

   Opus is the tier for an implementation task. `Edit` and `Write` here are the CLI's own tools on the runner's
   checkout, not Tandem's; the allowlist is explicit so the agent has no tool the procedure does not name. The step
   has `timeout-minutes: 75` inside the job's 90.
8. **Enforce the edit allowlist.** After the action, a `run:` step lists the files the drift branch changed against
   `main` and fails the job, comments on the PR naming the offending files and closes it, when any path is outside
   `src/main/kotlin/dev/daarkolabs/tandem/protocol/`, `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/`,
   `src/test/` and `docs/protocol.md`. The skill says the same rule; this step is the check that does not depend on the
   agent obeying it.

Secrets: none. The four Anthropic identifiers are workflow `env`, the GitHub credential is the App's, the Anthropic
credential is minted from the run's OIDC token.

### The skill: `.claude/skills/fix-contract-drift/SKILL.md`

A repository skill, so the procedure is versioned with the code it fixes and can be run by hand in a Claude Code
session (`/fix-contract-drift` with the same arguments, after `gh run download`). Frontmatter: `name: fix-contract-drift`,
a one-line `description`, and `allowed-tools` equal to the action's allowlist. The body prescribes, in this order:

1. **Read the classification.** `python3 -I scripts/classify_drift.py <recording> build/test-results/test` again (the
   workflow passed its output, but the skill is also run by hand). `cli-did-not-answer` or `unknown`: stop; open no
   branch; if run by hand, say what the detail is. The skill never fixes authentication, and it never guesses.
2. **Name the fixture.** `drift-<YYYY-MM-DD>` with the date of the red run in UTC; the rehearsal uses `rehearsal-<date>`.
   Copy `contract-hello.ndjson` to `src/test/resources/fixtures/<name>.ndjson` and `transcripts/contract-hello.jsonl` to
   `src/test/resources/fixtures/transcripts/<name>.jsonl`. For reader drift in `session-id.jsonl`, copy that file to
   `transcripts/<name>-session-id.jsonl` as well. Existing fixtures are never overwritten: 14 `Fixtures.decode("…")`,
   16 `Fixtures.transcript("…")` and 5 fake-CLI replays pin today's content (`plain-reply` pins `2.1.300` and the single
   "pong"), and re-recording one would turn one red into many.
3. **Run the redactor's safety net.** `python3 -I scripts/redact_fixture.py` over each copied file onto itself (a second
   run over redacted output changes nothing; the raw-by-mistake case is caught). Any `<leaked-N>` or `unredacted id`
   line on stderr is a stop: delete the copies, open no PR, and comment on the standing issue with the key path the
   redactor printed, never the value. Then `grep -nE '@|[0-9a-f]{8}-[0-9a-f]{4}-|toolu_|msg_|fv-az|runner'` over the
   copies, the hand check of `docs/launch.md` tactic 4 done by the agent: a hit that is an e-mail address, a UUID, an
   API id, a hostname or an account name is the same stop; a hit inside reply text (a package name with `@`) is not.
   The rule is the tactic's: a warning is a stop, not a note.
4. **Add the fixture to the tests.** The stream joins `FixtureDecodeTest` and `RobustnessTest` by directory listing
   (`Fixtures.names()`), so the decode test is red now, which is the failing test the fix must turn green. The
   transcript twin joins only by name: add `<name>` (and `<name>-session-id` when copied) to the list in
   `transcript fixtures decode line by line without throwing`, and a `TranscriptReaderTest` case that loads it and
   asserts one `ChatEntry.User` and one `ChatEntry.Assistant`. Add one test that states the fact that drifted: the
   field or type the decoder now recognises, read from the new fixture, and a `RobustnessTest` case for the malformed
   version of that shape (it must still decode to `Unknown`, never throw).
5. **Patch minimally.** Decoder drift: `src/main/kotlin/dev/daarkolabs/tandem/protocol/Ndjson.kt` and `CliMessage.kt`
   only; a renamed field is read under both names, a new required field is read optionally, a new type or subtype gets
   a typed message only when Tandem needs it and `Unknown(raw)` otherwise. Reader drift: `TranscriptReader.kt` and
   `TranscriptChain.kt` under `session/transcript/`. The hard rules apply unchanged: validate every field from the CLI,
   unknown types parse to `Unknown(raw)` and never throw, no logging of prompt or file contents, no IntelliJ imports
   in `protocol/`. Nothing outside the allowlist is edited, in particular not `.github/`, `build.gradle.kts` or
   `CLAUDE.md`, and the coverage bound is never lowered: if `koverVerify` fails, the new fixture or a test must
   exercise the new branch.
6. **Update `docs/protocol.md`** for a stranger: the new fixture and its CLI version in the Sources list; the changed
   shape in the section that describes it, citing the fixture by name and the CLI version it was recorded on, and the
   row in the undocumented-fields table (section "Fixture fields and types the SDK does not document") when the
   change is a new field; a gotchas row when the old shape is still possible. No reference to this document or to any
   plan.
7. **Verify.** `./gradlew check` (unit, fixture and headless platform tests, `koverVerify`) and
   `cd scripts && python3 -m unittest`. The live contract check cannot run on the branch: the federation rule admits
   only `main`, so the evidence is the red run's artifact plus `check` on the branch, and the next nightly after the
   merge is the real proof. The PR says so.
8. **Commit and open the pull request.** `gh auth setup-git` (git then pushes with the App token the action exported),
   branch `drift/<YYYY-MM-DD>-<run id>` from `main`, one commit, Conventional Commit
   `fix(protocol): follow CLI <version>: <what changed>` or `fix(transcript): …`, authored by the App identity
   (`claude[bot]`) with no trailers. `gh pr create --base main --label contract-drift` (`gh label create contract-drift`
   first, idempotent), title equal to the commit subject, body written for a reader:
   - **What the CLI changed**, in one paragraph, with the CLI version from the recording's `system/init` and the one
     the previous fixtures were recorded on.
   - **The fixture line that shows it**: the fixture name, the line number and the field or type, quoted.
   - **What the decoder (or reader) does now**, and what it still does for the old shape.
   - **Verified**: `./gradlew check` on the branch, the script tests, the redactor's safety net clean.
   - **Not verified**: the live check on this branch (federation admits `main` only); the next nightly after merge is
     the proof. Anything the agent could not reproduce.
   - The nightly run's link and the artifact name.

   In a rehearsal the PR is a draft, its title starts with `[rehearsal]`, it carries the labels `contract-drift` and
   `rehearsal`, and its body says in the first line that it is to be closed, not merged.
9. **Stop.** No merge, no second PR, no comment anywhere else. If any step fails after the branch was pushed, the
   PR is still opened, marked draft, and its body names the failing step; a half-done branch is more useful than
   silence (hard rule 5).

### Safeguards

- **Branch**: `drift/<date>-<run id>`, always from `main`, never `main` itself. The owner enables branch protection on
  `main` requiring a pull request; that, not the skill's rule, is what makes "never push to main" true for the App
  token.
- **One open fix at a time**: an open PR labelled `contract-drift` (rehearsal or real) skips the agent entirely (step 6
  of the runtime). Closing or merging it re-arms the loop.
- **Edit allowlist**: `protocol/`, `session/transcript/`, `src/test/`, `docs/protocol.md`; enforced after the action
  by the workflow (step 8), which closes an offending PR. The allowlist keeps the fixer out of `.github/`, so a bot
  can never become the user GitHub attributes the cron schedule to, which would fail the action's human-actor check
  on later nightlies.
- **No retrigger**: the nightly has no `push`/`pull_request` trigger; the fixer listens to `CLI contract` only.
- **Cost**: `--max-turns 60`, `timeout-minutes` on the step and the job, one concurrency slot, kind-1 and unknown
  reds never start the agent, the open-PR skip, and the workspace spend limit the owner sets in the Claude Console
  for `wrkspc_012JJzfEG8tDKeta7pe3CCa5` as the ceiling nothing in the repository can raise.
- **Missing artifact**: the fixer refuses (kind 1 path) rather than recording.
- **Nothing outward-facing**: the PR and the issue are inside the repository; nothing is published, posted or merged.

### What the owner does

- Reads the PR. Merges it (squash), or closes it with a comment saying why (the wrong fix, a change Tandem should
  handle differently, a fixture to re-record by hand). Either re-arms the loop. A merge is followed by the next
  nightly, which is the real verification; if it is red again the loop opens the next PR.
- On a comment on the standing issue "CLI contract: the CLI did not answer": fixes the cause (the federation rule,
  the service account, the spend limit, a pinned CLI version while a broken publish lasts) and closes the issue; the
  next kind-1 red opens it again.
- Once, before the first real firing: installs the Claude GitHub App on `daarko10/tandem`, enables branch protection
  on `main`, and runs the rehearsal (below).

### The rehearsal

`workflow_dispatch` with `rehearse: true` runs the whole loop against a recording mutated on purpose, so the loop is
proven end to end before a real CLI change. In a rehearsal step 2 of the runtime runs
`python3 -I scripts/make_drift.py <mode> build/drift/recording` instead of the download. The script reads the committed
fixture `history-reply` (CLI 2.1.301, the one-shot reply that has a transcript twin) and writes the artifact's layout:
`contract-hello.ndjson`, `transcripts/contract-hello.jsonl` and `transcripts/session-id.jsonl` (the twin again). Two
modes, each a drift the current decoder or reader rejects in the way a real rename would:

- `init-model-key` (the default; decoder drift): the `system/init` line's `model` key becomes `model_id`. `Ndjson`
  requires `model` on `init`, so the line decodes to `Unknown` and the decode test fails with "no system/init".
- `transcript-content-key` (reader drift): every `user` line of the transcripts has `message.content` renamed to
  `message.blocks`, so the reader yields no user entry.

The `workflow_dispatch` input `mode` (string, default `init-model-key`) picks one. Everything after step 2 runs
unchanged, including the classification, the skill, the protocol.md edit and the PR; the PR is a draft titled
`[rehearsal] …` with the `rehearsal` label, and the owner closes it. The fixture name is `rehearsal-<date>`, so a
rehearsal branch that was merged by mistake is recognisable; the owner does not merge it. Two rehearsals, one per
mode, are run before the branch holding this design is considered done; a rehearsal is repeated after any change to the
skill, `classify_drift.py` or `drift-fix.yml`.

### The "closed" criterion and how it is recorded

The milestone is met when a PR labelled `contract-drift` and not `rehearsal`, opened by the loop for a red nightly,
was merged by the owner and the following nightly was green. The record is the PR itself (`gh pr list --label
contract-drift --state merged`), and the owner appends to the milestone bullet in `docs/launch.md` the PR number, the
CLI version the fix followed and the fixture name, in the same commit as the merge or the next docs commit. Until that
line exists, the milestone is open, whatever the rehearsals showed.

## Components

- `.github/workflows/drift-fix.yml`: the fixer, as above.
- `.github/workflows/cli-contract.yml`: the two additions (redacted `session-id.jsonl`, failure messages printed).
- `scripts/classify_drift.py`: the classification, pure Python, `-I`-safe, exit code 0 with JSON on stdout; exit 2 on a
  usage error.
- `scripts/make_drift.py`: the rehearsal mutation, pure Python; refuses to write into `src/test/resources/fixtures`.
- `scripts/test_classify_drift.py`, `scripts/test_make_drift.py`: unit tests, run by `cd scripts && python3 -m unittest`
  and so by `build.yml`'s script-tests step.
- `.claude/skills/fix-contract-drift/SKILL.md`: the procedure.
- `docs/protocol.md`: no change in this design; the fixer edits it per drift.
- `CLAUDE.md`: one line under "Commands" naming `drift-fix.yml`, the skill and the rehearsal, so a session that finds a
  red run knows the loop exists before fixing by hand (tactic 7 still says a red run is the first thing to fix in any
  session: by then the loop has usually opened the PR, and the session reviews it instead).

## Errors and edge cases

- The `CLI contract` run was cancelled or timed out: `conclusion` is not `failure`; the fixer does not run.
- The artifact exists but holds only `contract-hello.ndjson` (the record step failed before the transcript copy): the
  classification is kind 1 ("no system/init" in the stream, or `is_error`), handled without the agent.
- Both tests fail: one PR, decoder first, the reader fix in the same commit; the classification says `decoder` and
  the skill reads both messages from the XML.
- The agent exceeds `--max-turns`: the action step fails; the allowlist step still runs; if a branch was pushed the PR
  exists as a draft naming the failure. The job is red, which is the signal to look.
- The redactor prints a leak: no PR, a comment on the standing issue naming the key path only.
- `./gradlew check` fails for a reason unrelated to the drift (a flaky platform test, disk space): the skill retries
  `check` once, then opens the PR as a draft and says which test failed and that it is unrelated, so the owner decides.
- Two nightlies red before the owner acts: the second fixer comments on the open PR and stops.
- The fixer workflow is itself broken (a YAML error, a missing script): GitHub shows it in Actions; the standing issue
  is not touched. `actionlint` on the branch catches the YAML part before merge.
- The action's human-actor check: the nightly's `schedule` runs are attributed to the user who last edited the cron
  line (`daarko10`), who has write access, so the check passes for the chain schedule → red run → `workflow_run`. The
  allowlist keeps it that way.

## Testing

- `scripts/test_classify_drift.py`: each rule in order against hand-built recordings and test XML (no recording,
  `is_error`, no assistant line, decode failure message, reader failure message, both, neither, no XML); the
  `cli_version` from `system/init`; the first-400-characters cut; a stream line that is not JSON is skipped as the
  nightly's check skips it.
- `scripts/test_make_drift.py`: each mode changes exactly the lines it names and nothing else (line count and every
  other line byte-identical); the output layout; the refusal to write under the fixtures directory; the `<session_id-N>`
  placeholders survive.
- `actionlint` over both workflows when it is installed; otherwise the push's own parse and the rehearsal.
- The rehearsal, both modes, from `workflow_dispatch` on `main` after merge: proves the download replacement, the
  classification, the skill end to end, the App token (the PR's `Build` runs start), the allowlist step and the labels.
  Its draft PRs are closed by the owner.
- `./gradlew check` unchanged: the new scripts and workflows do not touch Kotlin.
- Not testable before merge: the `workflow_run` firing itself (the workflow must be on `main`), and the federation
  exchange of a `workflow_run` job (inferred from GitHub's subject rules; the first firing's log line "Fetched a GitHub
  identity token (sub …)" confirms it, and the Console's authentication history shows a mismatch if not).

## Follow-up: widening the nightly

Today the nightly covers one one-shot `-p` reply and one `--session-id` run: no `initialize` response, no
`can_use_tool`, no interrupt receipt, `set_model`, `set_permission_mode` (and its rejection), `rename_session`, fork or
effort change; no tool calls; no comparison of `claude --version` with the versions the fixtures were recorded on
(2.1.300 to 2.1.302), so a green run on a new version is silent; and `ContractTest` tolerates any `Unknown` whose
type is not core, so a new top-level type or `system` subtype passes. After the loop has fired once:

1. Record each `drive_cli.py` scenario (`permission`, `interrupt`, `model-switch`, `permission-mode`,
   `permission-mode-rejected`, `fork`) with `scripts/record-scenario.sh` into `build/contract`, a fresh identity token
   before each, and extend `ContractTest` with the per-scenario facts `FixtureDecodeTest` pins. Whether one token file
   serves the fork scenario's two sequential processes is not known; the first run shows it, and a second token file
   named in the scenario is the fallback.
2. Print the run's `claude --version` and fail or annotate when it is newer than every fixture's `claude_code_version`
   while the run is green, so the Sources list in `docs/protocol.md` cannot go stale silently.
3. Diff the set of `Unknown` types and the keys of `system/init` and `result` against the undocumented-fields table
   and annotate additions.
4. Add the re-recording `run:` step to the fixer for those scenarios, as described under "The red run's recording is
   the new fixture", and let the skill promote from `build/drift/recorded`.

## Open items

1. Whether the Claude GitHub App is installed on `daarko10/tandem`; the owner checks before the rehearsal. Without it
   the action falls back to `GITHUB_TOKEN` and the fix PR's `Build` runs wait for approval.
2. The exact OIDC subject of a `workflow_run` job on `main`, confirmed by the first fixer run's token line.
3. The Opus model id to pin in `claude_args`: `claude-opus-5-5` is the `default` the 2.1.301 fixtures' `initialize`
   response lists; the id current at implementation time is used.
4. Whether `gh auth setup-git` is enough for the push with the App token under `persist-credentials: false`, or
   whether the skill must set the remote URL with the token; the rehearsal shows it.

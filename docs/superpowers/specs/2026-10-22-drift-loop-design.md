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
| 2, decoder drift | the Gradle step, test `live transcripts decode with every core type recognised` in `ContractTest`; or the check step with `::error title=CLI contract::No assistant line although the result holds text` (the reply came under a type the decoder does not know: a renamed core type, which `ContractTest` alone would pass, since an `Unknown` whose type is not core is tolerated) | `contract-hello.ndjson`, `transcripts/contract-hello.jsonl`, `transcripts/session-id.jsonl` (the check-step case: `contract-hello.ndjson` alone) | a core type (`assistant`, `user`, `result`, `stream_event`, `control_request`, `control_response`) or a line without `type` decoded to `Unknown`, or no `system/init` or `result` was recognised, or no `assistant` line while `result.result` holds the reply |
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
- `workflow_dispatch` with three inputs: `rehearse` (boolean, default `false`) and `mode` (string, default
  `init-model-key`) for the fixer-only rehearsal (see "The rehearsal"), and `run_id` (string, optional: the id of a red
  `CLI contract` run to fix now, for a run that happened before this workflow reached `main` or whose automatic firing
  was skipped). Dispatch from `main`: the job's `if` skips a dispatch from any other branch, which would otherwise fail
  at the federation exchange after the disk and Gradle setup. The rehearsal that proves the trigger is not a dispatch of
  this workflow but of the nightly (`cli-contract.yml` with `drift=<mode>`, see "The rehearsal").

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

### The nightly's side

The nightly keeps its name, schedule, steps and artifact name. Four additions:

- `transcripts/session-id.jsonl` is redacted before upload. Today the `--session-id` step copies it with `copy_transcript.py`
  alone, so the artifact of a public repository carries the runner's paths, the literal session id, API message ids,
  the branch name and the prompt for fourteen days, and a fixer would be promoting raw data. The step copies into the
  temporary directory and runs `scripts/redact_fixture.py` into `build/contract/transcripts/session-id.jsonl`, the
  same two commands `record-fixture.sh` runs.
- A step with `if: failure()` after the Gradle step prints every `<failure message="…">` from `build/test-results/test/*.xml`
  as an `::error title=CLI contract::` annotation, so the run log names the file and the unrecognised types and a
  human can read a red run without downloading anything.
- The step "Check that the CLI authenticated and answered" tells a renamed core type from a run the CLI did not answer.
  `result.is_error`, or no `assistant` line together with no non-empty `result.result` string, is "The CLI did not
  answer" as today. No `assistant` line while the result holds the reply text is a red of its own
  (`::error title=CLI contract::No assistant line although the result holds text: a core type may have been renamed`):
  the run is red, the recording is uploaded, and the fixer classifies it as decoder drift (rule 3 of the
  classification) instead of reporting it as authentication, which is the kind of drift the loop exists for reported
  as the kind it refuses. `ContractTest` cannot catch this case on its own: an `Unknown` whose type is not core passes
  it (see "Follow-up").
- A `workflow_dispatch` input `drift` (string, default empty; `init-model-key` or `transcript-content-key`) and a step
  "Mutate the recording for a rehearsal" after the two record steps, `if: inputs.drift != ''`, that runs
  `python3 -I scripts/make_drift.py "$DRIFT" build/contract --from build/contract` with the input passed through
  `env:` (never interpolated into the shell line; `actionlint` flags that). The live recording is mutated in place and
  the marker file `build/contract/rehearsal` is left beside it, the Gradle step goes red, the real artifact is
  uploaded, and `workflow_run` fires the fixer exactly as a real red does. That is the rehearsal (below); a scheduled
  run has no input and records as today.

### The fixer runtime

One job, `fix`, on `ubuntu-latest`:

1. `jlumbroso/free-disk-space@v2.0.0` (the same settings as `build.yml`: the platform tests need the room),
   `actions/checkout@v7` with `persist-credentials: false` (so the only GitHub credential the agent holds is the App
   token the action exports; see step 7), `actions/setup-java@v6` (zulu 21), `gradle/actions/setup-gradle@v6`.
2. **Fetch the recording.** `mkdir -p build/drift` first (nothing else creates the directory, and the steps below
   write into it under `set -euo pipefail`). Then, with `GITHUB_TOKEN` and the run id from `github.event.workflow_run.id`
   or the `run_id` input, the step asks whether the run has the artifact at all:
   `gh api "repos/$GITHUB_REPOSITORY/actions/runs/$id/artifacts" --jq '[.artifacts[].name] | index("contract-recording")'`.
   `null` means the record step wrote nothing: a kind-1 red, not an error of the fixer; the step prints "the run left
   no recording" and ends, and step 3 classifies it. Otherwise `gh run download $id -n contract-recording -D
   build/drift/recording`, and any failure of that download (a 403, an API outage, a renamed artifact, an expired
   retention) fails the job: it is never read as "the CLI did not answer", which would report the loop's first real
   drift as authentication with a green job (hard rule 5). In a fixer-only rehearsal (`workflow_dispatch` with
   `rehearse=true`) this step is replaced by `scripts/make_drift.py` (see "The rehearsal").
3. **Classify before Gradle.** `python3 -I scripts/classify_drift.py build/drift/recording build/test-results/test`
   (a directory that does not exist yet) into `build/drift/precheck.json`. Rules 1–3 of the classification (step 5)
   need no test results, so a kind-1 red is known here and the Gradle step, which downloads the IntelliJ platform and
   compiles for several minutes, never runs for the commonest red (authentication, an outage). The step also outputs
   `rehearsal=true` when the marker `build/drift/recording/rehearsal` exists (written by `make_drift.py`, in the
   nightly or in the fixer), else `false`; that is what the prompt passes to the skill.
4. **Run the contract test against the recording**, `if` the precheck's kind is not `cli-did-not-answer`:
   `./gradlew test --tests '*ContractTest*' -Dtandem.contractDir=build/drift/recording`, `continue-on-error: true`.
   This reproduces the red locally and leaves the assertion messages in `build/test-results/test/`.
5. **Classify**, under the same guard. `python3 -I scripts/classify_drift.py build/drift/recording build/test-results/test`
   prints one JSON object, kept as `build/drift/classification.json`: `kind` (`cli-did-not-answer`, `decoder`, `reader`
   or `unknown`), `cli_version` (from the recording's `system/init.claude_code_version`; `null` without one), `detail`
   (the error text, or the assertion message) and `files` (the artifact files involved). The rules, in order: no
   recording or no `contract-hello.ndjson` → `cli-did-not-answer` ("the run left no recording"); no `system/init` →
   `cli-did-not-answer`; `result.is_error` truthy → `cli-did-not-answer` with the result text or `errors` (first 400
   characters) and `apiKeySource`; no `assistant` line → `decoder` with the detail `contract-hello.ndjson: no assistant
   line, result text present` when `result.result` is a non-empty string (the reply came under a type the decoder does
   not know; the nightly's check step reds the run the same way), else `cli-did-not-answer` with the same detail as for
   `is_error` (an empty or missing result); a failure of the decode test → `decoder` with its message; a failure of the
   reader test → `reader` with its message; both → `decoder` (the one PR fixes both, decoder first); test results
   absent or neither test failed → `unknown`. A stream with a reply but no `result` line (the CLI was killed mid-run)
   passes rule 3 on the strength of its `assistant` line and is decoder drift by the decode test's "no result".
6. **Kind 1 and unknown stop here, without the agent.** The step runs when the precheck or the classification says
   `cli-did-not-answer`, or the classification says `unknown`, and reads whichever of the two JSON files exists (the
   classification when Gradle ran, else the precheck). It comments on the standing issue titled
   "CLI contract: the CLI did not answer" (label `contract-drift`), creating it when none is open, with the run link,
   `kind`, `cli_version` and `detail`. One issue, one comment per red run, no branch, no PR, no agent spend. The job
   ends green: the loop did its work.
7. **Skip when a fix is already waiting.** `gh pr list --label contract-drift --state open` non-empty → the step
   comments on that PR ("the nightly of <date> is still red: run <link>") and the job ends. A red that persists for
   a week opens one PR, not seven.
8. **The agent.** `anthropics/claude-code-action@v1` with the federation inputs `anthropic_federation_rule_id`,
   `anthropic_organization_id`, `anthropic_service_account_id` and `anthropic_workspace_id` set to the same four
   identifiers `cli-contract.yml` carries in `env:` (they are not secrets), and no `github_token` input, so the action
   authenticates to GitHub as the Claude GitHub App and the branch it pushes and the PR it opens start the `Build`
   workflow (a PR opened with `GITHUB_TOKEN` starts no `Build` run at all: events raised with the workflow token never
   trigger workflows). Before the CLI starts, the action puts the App token into the `origin` URL and sets git's
   `user.name` and `user.email` from its `bot_name` and `bot_id`, so the skill runs no `gh auth setup-git` and sets no
   identity of its own. The action rewrites the identity-token file every four minutes for the length of the step and
   the CLI re-reads it at each exchange, so the five-minute GitHub token does not bound the step. The prompt is
   `/fix-contract-drift run=<run url> recording=build/drift/recording classification=build/drift/classification.json rehearsal=<true|false> date=<YYYY-MM-DD>`
   (the date is the red run's, in UTC, so the skill never looks the run up). `claude_args`:

   ```
   --model claude-opus-5-5
   --max-turns 60
   --allowedTools "Read,Grep,Glob,Edit,Write,Bash(./gradlew:*),Bash(python3:*),Bash(gh pr create:*),Bash(gh issue list:*),Bash(gh issue create:*),Bash(gh issue comment:*),Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(git add:*),Bash(git commit:*),Bash(git switch:*),Bash(git clean:*),Bash(git push -u origin drift/:*),Bash(cp:*),Bash(ls:*),Bash(cat:*),Bash(grep:*),Bash(date:*)"
   ```

   Opus is the tier for an implementation task. `Edit` and `Write` here are the CLI's own tools on the runner's
   checkout, not Tandem's. The allowlist names verbs, not programs, because the GitHub credential behind them is the
   App's installation token with write access to contents, pull requests and issues: `Bash(gh pr:*)` would admit
   `gh pr merge` of the agent's own PR and `gh pr close` of any other, `Bash(gh issue:*)` the closing of any issue, and
   `Bash(git:*)` a `git push -f`, a `git push origin --delete <branch>` or a push to any unprotected branch (the
   project's `.claude/settings.json`, which the action loads, denies only the prefix `git push --force`). The only push
   on the list is `git push -u origin drift/…`. In headless mode a denied call is a dead end, not a prompt, so every
   command the skill prescribes is on the list and nothing else is. The step has `timeout-minutes: 75` inside the
   job's 90.
9. **Enforce the edit allowlist.** After the action, `if: always()`, a `run:` step lists the files the drift branch
   changed against `main` and fails the job, comments on the PR naming the offending files and closes it, when any
   path is outside `src/main/kotlin/dev/daarkolabs/tandem/protocol/`,
   `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/`, `src/test/` and `docs/protocol.md`. The skill says the
   same rule; this step is the check that does not depend on the agent obeying it. When no `contract-drift` PR is
   open, the step runs `git ls-remote --heads origin "drift/<date>-<run id>"`: a branch without a PR means the agent
   was stopped after its push (the step's `timeout-minutes`, `--max-turns`) and could not keep skill step 9's promise.
   The step then opens the draft PR itself with `GITHUB_TOKEN` (`gh pr create --draft --head drift/… --label
   contract-drift --title "[incomplete] drift fix for run <id>"`, body: the agent stopped before opening this PR,
   timeout or turn limit, review the branch; no `Build` run starts on a PR opened with the workflow token, acceptable
   for a failure draft) and exits 1, so the run is red and the pushed branch is never silent with a green loop (hard
   rule 5).

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
   `src/test/resources/fixtures/transcripts/<name>.jsonl`. For reader drift, copy `transcripts/session-id.jsonl` to
   `transcripts/<name>-session-id.jsonl` as well, always: `ContractTest` stops at the first failing transcript in
   directory order (`File.listFiles`, unspecified), so its message names one of the two files by chance while both
   carry the CLI's new shape (the `--session-id` run is the same CLI version), and the fixture set must not depend on
   that order. Existing fixtures are never overwritten: 14 `Fixtures.decode("…")`, 16 `Fixtures.transcript("…")` and 5
   fake-CLI replays pin today's content (`plain-reply` pins `2.1.300` and the single "pong"), and re-recording one
   would turn one red into many.
3. **Run the redactor's safety net.** `python3 -I scripts/redact_fixture.py` over each copied file onto itself (a second
   run over redacted output changes nothing; the raw-by-mistake case is caught). Any `<leaked-N>` or `unredacted id`
   line on stderr is a stop: remove the copies (`git clean -f -- <copies>`; they are untracked, and `rm` is not on the
   tool allowlist), open no PR, and comment on the standing issue with the key path the redactor printed, never the
   value. Then `grep -nE '@|[0-9a-f]{8}-[0-9a-f]{4}-|toolu_|msg_|fv-az|runner'` over the copies, the hand check of
   `docs/launch.md` tactic 4 done by the agent: a hit that is an e-mail address, a UUID, an API id, a hostname or an
   account name is the same stop; a hit inside reply text (a package name with `@`) is not. The rule is the tactic's:
   a warning is a stop, not a note.
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
   exercise the new branch. A `decoder` classification whose detail is `no assistant line, result text present` comes
   with a green decode test: the reply arrived under a type `Ndjson` does not know. The failing test to write first
   decodes the new fixture and asserts an `Assistant` message; the fix reads the new type beside `assistant`.
6. **Update `docs/protocol.md`** for a stranger: the new fixture and its CLI version in the Sources list; the changed
   shape in the section that describes it, citing the fixture by name and the CLI version it was recorded on, and the
   row in the undocumented-fields table (section "Fixture fields and types the SDK does not document") when the
   change is a new field; a gotchas row when the old shape is still possible. No reference to this document or to any
   plan.
7. **Verify.** `./gradlew check` (unit, fixture and headless platform tests, `koverVerify`) and
   `python3 -m unittest discover -s scripts -t scripts` from the repository root (the same tests `build.yml` runs as
   `cd scripts && python3 -m unittest`; the skill cannot use that spelling, since `cd` is not on the tool allowlist and
   a compound command needs every part allowed). The live contract check cannot run on the branch: the federation rule
   admits only `main`, so the evidence is the red run's artifact plus `check` on the branch, and the next nightly after
   the merge is the real proof. The PR says so.
8. **Commit and open the pull request.** Branch `drift/<YYYY-MM-DD>-<run id>` from `main` (`git switch -c`), one
   commit, Conventional Commit `fix(protocol): follow CLI <version>: <what changed>` or `fix(transcript): …`, with no
   trailers; the author is the App identity (`claude[bot]`), which the action configured together with the remote URL
   before the CLI started (runtime step 8), so the skill sets no identity and runs no `gh auth setup-git`.
   `git push -u origin drift/<YYYY-MM-DD>-<run id>`, the one push the allowlist admits, then
   `gh pr create --base main --label contract-drift --title "<the commit subject>" --body-file build/drift/pr-body.md`
   (`build/` is gitignored, so the body can never land in the commit; the labels exist already: the workflow creates
   both before the agent starts, and by hand the owner creates `contract-drift` and `rehearsal` once). The body,
   written for a reader:
   - **What the CLI changed**, in one paragraph, with the CLI version from the recording's `system/init` and the one
     the previous fixtures were recorded on.
   - **The fixture line that shows it**: the fixture name, the line number and the field or type, quoted.
   - **What the decoder (or reader) does now**, and what it still does for the old shape.
   - **Verified**: `./gradlew check` on the branch, `python3 -m unittest discover -s scripts -t scripts`, the
     redactor's safety net clean.
   - **Not verified**: the live check on this branch (federation admits `main` only); the next nightly after merge is
     the proof. Anything the agent could not reproduce.
   - The nightly run's link and the artifact name.

   In a rehearsal the PR is a draft, its title starts with `[rehearsal]`, it carries the labels `contract-drift` and
   `rehearsal`, and its body says in the first line that it is to be closed, not merged.
9. **Stop.** No merge, no second PR, no comment anywhere else. If any step fails after the branch was pushed, the
   PR is still opened, marked draft, and its body names the failing step; a half-done branch is more useful than
   silence (hard rule 5). When the agent itself is stopped (the step's timeout, the turn limit) it cannot keep that
   promise; runtime step 9 keeps it for the pushed branch.

### Safeguards

- **Branch**: `drift/<date>-<run id>`, always from `main`, never `main` itself. The owner enables branch protection on
  `main` requiring a pull request and one approving review; that, not the skill's rule, is what makes "never push to
  main" and "never merge" true for the App token. A protection that only requires a pull request does not stop a
  `gh pr merge` by the App: a pull request is what it merges. The App cannot approve its own pull request, so with one
  review required the merge is refused even if the tool allowlist regresses. (The owner, an administrator, still
  merges their own pull requests unless "Do not allow bypassing the above settings" is on.)
- **Tool allowlist**: verbs, not programs (runtime step 8). With the App token the agent can create a pull request,
  list, create and comment on issues, and push to `drift/…`; no merge, close, force push, branch deletion or push
  elsewhere is possible, whatever the agent decides. The skill prescribes no command outside the list: in headless
  mode a denied call is a dead end, not a prompt.
- **One open fix at a time**: an open PR labelled `contract-drift` (rehearsal or real) skips the agent entirely (step 7
  of the runtime). Closing or merging it re-arms the loop.
- **Edit allowlist**: `protocol/`, `session/transcript/`, `src/test/`, `docs/protocol.md`; enforced after the action
  by the workflow (step 9), which closes an offending PR. The allowlist keeps the fixer out of `.github/`, so no bot
  ever becomes the last editor of the cron line, which is what GitHub's documented rule attributes a schedule run to
  (see "Errors and edge cases" on the actor check).
- **A stopped agent**: a pushed `drift/` branch without a pull request gets an `[incomplete]` draft from the workflow
  and a red run (runtime step 9), never a green loop and silence.
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
  on `main` requiring a pull request and one approving review, and runs the rehearsal (below). After the first
  scheduled nightly (03:17 UTC) records which actor GitHub attributed it to (open item 4), before the first real
  firing is trusted.

### The rehearsal

The loop is proven end to end before a real CLI change, through the real trigger: `gh workflow run cli-contract.yml
--ref main -f drift=<mode>`. The nightly records live as always, then its step "Mutate the recording for a rehearsal"
(`if: inputs.drift != ''`) runs `python3 -I scripts/make_drift.py "$DRIFT" build/contract --from build/contract`. The
script reads the three recorded files (`contract-hello.ndjson`, `transcripts/contract-hello.jsonl`,
`transcripts/session-id.jsonl`) before it writes any, so the recording is mutated in place, and leaves the marker
`build/contract/rehearsal` holding the mode. The Gradle step goes red, the real artifact is uploaded, and `workflow_run`
fires the fixer with `conclusion == failure` on `main`. Everything a dispatch of the fixer alone would skip is
exercised: the `workflow_run` trigger and the job `if` (`github.ref`, `head_branch`), the action's checks of
`github.actor` and `workflow_run.actor` for a `workflow_run` event, the OIDC subject of a `workflow_run` job (open item
2), `actions: read` with the artifact listing and `gh run download`, and the artifact's layout. The fixer finds the
marker and passes `rehearsal=true` to the skill. Two modes, each a drift the current decoder or reader rejects in the
way a real rename would:

- `init-model-key` (decoder drift): the `system/init` line's `model` key becomes `model_id`. `Ndjson` requires `model`
  on `init`, so the line decodes to `Unknown` and the decode test fails with "no system/init".
- `transcript-content-key` (reader drift): every `user` line of both transcripts has `message.content` renamed to
  `message.blocks`, so the reader yields no user entry. Which transcript the test names is up to directory order; the
  skill copies both for every reader drift (skill step 2).

Without `--from`, `make_drift.py <mode> <out>` mutates the committed fixture `history-reply` (CLI 2.1.301, the one-shot
reply that has a transcript twin) into the artifact's layout, the twin standing in for both transcripts, marker
included. That is the fixer-only rehearsal: `workflow_dispatch` of `drift-fix.yml` with `rehearse=true` and `mode`
(string, default `init-model-key`) replaces the download (runtime step 2) with that script, spends no nightly
recording, and runs the classification, the skill, the protocol.md edit and the PR as the real path does, but
exercises no trigger; it serves a by-hand run of the loop and the proof of the open-PR skip. Either way the marker
makes the fixture `rehearsal-<date>`, the PR a draft titled `[rehearsal] …` with the `rehearsal` label, and the owner
closes it; a rehearsal branch merged by mistake is recognisable by the fixture name. Two rehearsals through the
nightly, one per mode, are run before the branch holding this design is considered done; a rehearsal is repeated
after any change to the skill, `classify_drift.py`, `make_drift.py`, `cli-contract.yml` or `drift-fix.yml`.

### The "closed" criterion and how it is recorded

The milestone is met when a PR labelled `contract-drift` and not `rehearsal`, opened by the loop for a red nightly,
was merged by the owner and the following nightly was green. The record is the PR itself (`gh pr list --label
contract-drift --state merged`), and the owner appends to the milestone bullet in `docs/launch.md` the PR number, the
CLI version the fix followed and the fixture name, in the same commit as the merge or the next docs commit. Until that
line exists, the milestone is open, whatever the rehearsals showed.

## Components

- `.github/workflows/drift-fix.yml`: the fixer, as above.
- `.github/workflows/cli-contract.yml`: the four additions (redacted `session-id.jsonl`, failure messages printed, the
  renamed-type case in the check step, the `drift` input with the mutate step).
- `scripts/classify_drift.py`: the classification, pure Python, `-I`-safe, exit code 0 with JSON on stdout; exit 2 on a
  usage error.
- `scripts/make_drift.py`: the rehearsal mutation, pure Python; `--from <dir>` mutates a recording in place; writes the
  `rehearsal` marker; refuses to write into `src/test/resources/fixtures`.
- `scripts/test_classify_drift.py`, `scripts/test_make_drift.py`: unit tests, run by `python3 -m unittest discover -s
  scripts -t scripts` from the repository root (the skill's spelling) and by `cd scripts && python3 -m unittest`
  (`build.yml`'s script-tests step); the same tests either way.
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
- The agent exceeds `--max-turns` or the step's `timeout-minutes` kills it: the action step fails; the allowlist step
  still runs. If the skill got as far as its PR, that PR is a draft naming the failure; if the branch was pushed and no
  PR exists, the allowlist step opens the `[incomplete]` draft for it and exits 1 (runtime step 9). The job is red
  either way, which is the signal to look.
- The artifact is listed but `gh run download` fails (a 403, an API outage, a renamed artifact): the job is red at the
  fetch step and the standing issue is not touched; GitHub shows it in Actions. It is never reported as "the CLI did
  not answer".
- The redactor prints a leak: no PR, a comment on the standing issue naming the key path only.
- The CLI renamed or stopped emitting `assistant` in `-p` mode: the nightly's check step reds the run with "No
  assistant line although the result holds text", the classification is `decoder` by rule 3 (the decode test itself is
  green), and the skill's first test decodes the new fixture and asserts an `Assistant` message (skill step 5).
- `./gradlew check` fails for a reason unrelated to the drift (a flaky platform test, disk space): the skill retries
  `check` once, then opens the PR as a draft and says which test failed and that it is unrelated, so the owner decides.
- Two nightlies red before the owner acts: the second fixer comments on the open PR and stops.
- The fixer workflow is itself broken (a YAML error, a missing script): GitHub shows it in Actions; the standing issue
  is not touched. `actionlint` on the branch catches the YAML part before merge.
- The action's human-actor check (`github.actor` of the fixer's `workflow_run` job is the actor of the nightly, and the
  action checks `workflow_run.actor.login` as well): not verified yet. GitHub's documented rule attributes a `schedule`
  run to the user who last modified the cron line (`daarko10`, who has write access), which would pass the chain
  schedule → red run → `workflow_run`; but `CLI contract` has only ever run by `workflow_dispatch` (actor `daarko10`),
  never on schedule. After the first scheduled run (03:17 UTC) the owner records
  `gh api 'repos/daarko10/tandem/actions/runs?event=schedule' --jq '.workflow_runs[0].actor.login'` in open item 4.
  If it is not a User with write access, the fixer needs the action's `allowed_bots` input or a `workflow_dispatch`
  relay, and that is settled before the first real firing is trusted. The edit allowlist keeps the cron line's last
  editor human.

## Testing

- `scripts/test_classify_drift.py`: each rule in order against hand-built recordings and test XML (no recording,
  `is_error`, no assistant line with an empty result, no assistant line with result text, decode failure message,
  reader failure message, both, neither, no XML); the `cli_version` from `system/init`; the first-400-characters cut;
  a stream line that is not JSON is skipped as the nightly's check skips it; a failure in another test class is not
  drift (pinned by a contract test name under another class).
- `scripts/test_make_drift.py`: each mode changes exactly the lines it names and nothing else (line count and every
  other line byte-identical); the output layout with the marker; `--from` on a recording mutated in place gives the
  same bytes as the fixture-sourced run; the refusal to write under the fixtures directory; the `<session_id-N>`
  placeholders survive.
- `scripts/test_workflows.py`: the fixer's trigger, permissions, step order and guards (the fetch's `mkdir`, the
  presence check and the absence of `|| echo`; the Gradle and classification guards on the precheck; the
  `[incomplete]` draft in the allowlist step; the input passed through `env:`); the nightly's `drift` input, mutate
  step, check step and redaction; the skill's `allowed-tools` equal to the action's.
- `actionlint` over both workflows when it is installed; otherwise the push's own parse and the rehearsal.
- The rehearsal, both modes, through `cli-contract.yml` with `drift=<mode>` on `main` after merge: proves the
  `workflow_run` trigger and the job `if`, the action's actor checks for that event, the artifact listing and the
  download, the classification, the skill end to end, the App token (the PR's `Build` run starts), the allowlist step
  and the labels. The fixer-only dispatch (`rehearse=true`) proves the open-PR skip. Its draft PRs are closed by the
  owner.
- `./gradlew check` unchanged: the new scripts and workflows do not touch Kotlin.
- Not testable before merge: the `workflow_run` firing itself (the workflow must be on `main`), the federation
  exchange of a `workflow_run` job (inferred from GitHub's subject rules; the first fixer run's federation log line
  names the subject, and the Console's authentication history shows a mismatch if not), and the actor of a scheduled
  nightly (open item 4). The first rehearsal through the nightly answers the first two; the first scheduled run
  answers the third.

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
   the action falls back to `GITHUB_TOKEN`, and the symptom is that no `Build` run appears on the fix PR at all
   (events raised with the workflow token never trigger workflows), not a run waiting for approval.
2. The exact OIDC subject of a `workflow_run` job on `main`, confirmed by the federation log line of the fixer run the
   first rehearsal through the nightly triggers.
3. The Opus model id to pin in `claude_args`: `claude-opus-5-5` is the `default` the 2.1.301 fixtures' `initialize`
   response lists; the id current at implementation time is used.
4. The actor GitHub attributes a scheduled `CLI contract` run to, recorded after the first scheduled run as
   `gh api 'repos/daarko10/tandem/actions/runs?event=schedule' --jq '.workflow_runs[0].actor.login'` (see "Errors and
   edge cases"). Answered elsewhere, no longer open: the push identity. The action sets the `origin` URL with the App
   token and `user.name`/`user.email` from `bot_name`/`bot_id` before the CLI starts, so neither `gh auth setup-git`
   nor an identity fallback is needed.

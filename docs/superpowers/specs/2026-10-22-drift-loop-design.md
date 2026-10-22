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
  `github.event.workflow_run.conclusion == 'failure'`, `github.event.workflow_run.head_branch == 'main'`,
  `github.event.workflow_run.head_repository.full_name == github.repository` and `github.event.workflow_run.event` is
  `schedule` or `workflow_dispatch` (the nightly's only triggers). A `workflow_run` job checks out `main` at its head
  and presents the OIDC subject `repo:daarko10@34120174/tandem@1421183639:ref:refs/heads/main` (GitHub embeds the
  owner and repository ids), the one the federation rule admits (`cli-contract.yml` says so in its header).
- `workflow_dispatch` with three inputs: `rehearse` (boolean, default `false`) and `mode` (string, default
  `init-model-key`) for the fixer-only rehearsal (see "The rehearsal"), and `run_id` (string, optional: the id of a red
  `CLI contract` run on `main` to fix now, for a run that happened before this workflow reached `main` or whose
  automatic firing was skipped; a green run, another workflow's run, another branch's run or a run that is not a
  number is refused; with an old run the CLI did not answer it is also the kind-1 rehearsal, see "The rehearsal").
  Dispatch from `main`: the job's `if` skips a dispatch from any other branch, which would otherwise fail at the
  federation exchange after the disk and Gradle setup. The rehearsal that proves the trigger is not a dispatch of this
  workflow but of the nightly (`cli-contract.yml` with `drift=<mode>`, see "The rehearsal").

`workflow_run` also fires for a run of a fork's pull request once the repository is public, and that run's
`head_branch` and workflow name are the fork's to choose: a fork can name a workflow `CLI contract`, run it from its
own branch `main`, fail it on purpose and upload a `contract-recording` it wrote, which the agent would then promote
holding the App token. The head-repository and event clauses refuse it, and step "Name the red run" checks the same
fields again through `gh api "repos/$GITHUB_REPOSITORY/actions/runs/<id>"` (`name`, `conclusion`, `head_branch`,
`head_repository.full_name`, `event`; `gh run view` names neither the head repository nor the event), which also
covers a `run_id` passed by dispatch.

The whole job is guarded by `github.repository == 'daarko10/tandem'`, a `concurrency` group `drift-fix` without
cancellation (one fixer at a time; a second firing waits), `timeout-minutes: 90`, and these permissions: `id-token: write`
(the federation exchange and the Claude GitHub App token exchange), `contents: read` (the checkout, and the drift branch
read through the API; the fix branch is pushed with the App token, whose scope comes from the App's installation, not
from the workflow token, and the job runs code the agent wrote, which a writable workflow token would let push tags,
releases or any unprotected branch as `github-actions[bot]`), `pull-requests: read` (the gate's PR list; its comment
goes through the issues endpoint, so this token, which the agent's test code can read from the runner's memory, holds
no pull-request write and cannot approve a pull request), `issues: write` (the kind-1 report and the gate's comment)
and `actions: read` (the red run's artifact and log). The `enforce` job sets its own (step 9). The
workflow file must
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
2. **A nested CLI would record on the action's credential, racily and without a bound.** `anthropics/claude-code-action`
   removes `ACTIONS_ID_TOKEN_REQUEST_URL` and `ACTIONS_ID_TOKEN_REQUEST_TOKEN` from the environment of the `claude` it
   runs, so a `claude` started by the agent (as `record-fixture.sh` would) cannot fetch a GitHub identity token of its
   own; but it inherits `ANTHROPIC_IDENTITY_TOKEN_FILE` (rewritten by the action every four minutes) and
   `ANTHROPIC_CONFIG_DIR` plus `ANTHROPIC_PROFILE`, a federation profile whose on-disk credential cache every `claude`
   the action spawns shares. A nested CLI authenticates from that cache and re-exchanges from the refreshed file when
   it expires; a `jti_reused` 401 happens only when it races the agent's own exchange. Recording inside the agent is
   therefore possible, racy, and bounded in spend only by the workspace limit, which is why the fixer denies
   `Bash(claude:*)` (runtime step 8) and why a re-recording, when it is added, runs in a workflow step with its own
   `anthropic-token` fetch before the action (below).
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
  alone, so the artifact carries the runner's paths, the literal session id, API message ids, the branch name and the
  prompt for fourteen days, and a fixer would be promoting raw data into a fixture that will be public (`docs/launch.md`
  tactic 4; the repository itself is private today, which is not what justifies the redaction). The step copies into the
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

Two jobs on `ubuntu-latest`: `fix` (steps 1-8) and `enforce` (step 9):

1. `jlumbroso/free-disk-space@v2.0.0` (the same settings as `build.yml`: the platform tests need the room),
   `actions/checkout@v7` with `persist-credentials: false` (so the only GitHub credential the agent holds is the App
   token the action exports; see step 7), `actions/setup-java@v6` (zulu 21), `gradle/actions/setup-gradle@v6` with
   `cache-read-only: true`, against an accidental save: the agent runs test code it wrote in this job, which can
   rewrite the Gradle User Home (build cache entries, dependency jars), and a cache setup-gradle's post step saved
   from it in `main`'s scope would be restored by `Build` on `main` and on every pull request, past the ruleset's review.
   It does not bind hostile code, which runs with sudo on a job on `main` and could write such a cache itself with the
   runner's cache token (the remaining risk named in "Safeguards").
2. **Fetch the recording.** `mkdir -p build/drift` first (nothing else creates the directory, and the steps below
   write into it under `set -euo pipefail`). Then, with `GITHUB_TOKEN` and the run id from `github.event.workflow_run.id`
   or the `run_id` input, the step asks whether the run has the artifact at all:
   `gh api "repos/$GITHUB_REPOSITORY/actions/runs/$id/artifacts" --jq '[.artifacts[].name] | index("contract-recording") | tostring'`
   (gh prints a jq `null` as an empty line; `tostring` makes it the string `null`).
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
   `./gradlew test --tests '*ContractTest*' --rerun -Dtandem.contractDir="$PWD/build/drift/recording"`, `continue-on-error: true`
   (`--rerun`: the build cache keys the task on the code and the path, not the recording, so a pass on this commit
   would otherwise replay from the cache).
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
   comments on that PR ("the nightly of <date> is still red: run <link>", through `gh api
   "repos/$GITHUB_REPOSITORY/issues/<n>/comments"`, which `issues: write` covers: the job holds no pull-request write)
   and the job ends. A red that persists for a week opens one PR, not seven. Once the gate proceeds, the step `Require
   a protected main` (`id: protect`, with `GITHUB_TOKEN`) checks the precondition of "Safeguards" before any spend: the
   number of `pull_request` rules in `gh api "repos/$GITHUB_REPOSITORY/rules/branches/main?per_page=100"` (the active
   rulesets' rules, readable with metadata access) whose `required_approving_review_count` is at least 1,
   `require_code_owner_review` is `true` and `require_last_push_approval` is `true` must be a number of at least 1, and
   `main`'s `.github/CODEOWNERS` (read raw through the contents API; `contents: read` covers it) must have a line
   owning every file (`* @owner`), since the code-owner review binds only files that have an owner. One approving
   review alone would not do: the App holds pull-request write, so it can approve a pull request it did not author
   (the owner's open PR after a push to its unprotected head branch, a Dependabot PR) and then merge it, and the
   agent's test code runs with the App token in its environment; an App is no code owner and cannot approve its own
   push. Classic branch protection does not count: the workflow token cannot read its review count (administration
   access), and `branches/main`'s `protected: true` holds for a classic rule with no option ticked, which still
   accepts a direct push. Anything else, a failed read or an answer that is not a number included: `::error` and
   `exit 1`, the agent does not start and step 9 is skipped. A ruleset on a private repository
   owned by a personal account is enforced only with GitHub Pro, and what the endpoint lists for an unenforced ruleset
   is not known, so reading the rule does not prove enforcement: the owner's part ("What the owner does") comes first.
   The same step then refuses a drift branch `drift/<date>-<run id>` that already exists (`gh api
   "repos/$GITHUB_REPOSITORY/branches/$BRANCH"` succeeds: a closed pull request whose branch was kept, and the same run
   dispatched again), with `::error … delete it to retry` and `exit 1`: the agent's `git push -u origin HEAD` would be
   rejected as non-fast-forward after the spend, and step 9 would misread the old branch as a stopped agent. Steps 8
   and 9 run only when this step succeeded.
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
   --max-turns 120
   --allowedTools "Read,Grep,Glob,Edit,Write,Bash(./gradlew check),Bash(./gradlew test:*),Bash(python3 -I scripts/classify_drift.py:*),Bash(python3 -I scripts/redact_fixture.py:*),Bash(python3 -m unittest discover -s scripts -t scripts),Bash(gh pr create:*),Bash(gh issue list:*),Bash(gh issue create:*),Bash(gh issue comment:*),Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(git add:*),Bash(git commit:*),Bash(git switch:*),Bash(git clean:*),Bash(git push -u origin HEAD),Bash(cp:*),Bash(ls:*),Bash(cat:*),Bash(grep:*),Bash(date:*)"
   ```

   and the action's `settings` input (JSON, merged into the CLI's permissions for this run only; the project's
   `.claude/settings.json` is loaded as well) with `permissions.deny`: `Edit(.github/**)`, `Write(.github/**)`,
   `Edit(build.gradle.kts)`, `Write(build.gradle.kts)`, `Edit(settings.gradle.kts)`, `Write(settings.gradle.kts)`,
   `Edit(gradle.properties)`, `Write(gradle.properties)`, `Edit(gradle/**)`, `Write(gradle/**)`, `Edit(.git/**)`,
   `Write(.git/**)`, `Edit(scripts/**)`, `Write(scripts/**)`, `Edit(.claude/**)`, `Write(.claude/**)`,
   `Edit(CLAUDE.md)`, `Write(CLAUDE.md)`, `Bash(claude:*)`, and, since the project's `.claude/settings.json` allow
   rules are merged in (among them the prefix rules `Bash(./gradlew check:*)` and `Bash(./gradlew test:*)`),
   `Bash(./gradlew check -x:*)`, `Bash(./gradlew check --exclude-task:*)`, `Bash(./gradlew check *-*)`,
   `Bash(./gradlew test *check*)`, `Bash(./gradlew compileKotlin:*)`, `Bash(./gradlew compileTestKotlin:*)`,
   `Bash(./gradlew buildPlugin:*)`, `Bash(./gradlew verifyPlugin:*)` and `Bash(ktlint:*)` (28 rules). The three
   `check` rules refuse any flag after `./gradlew check`: a rule whose `*` is not a trailing `:*` is a wildcard pattern
   in the installed CLI (2.1.307), so `*-*` covers `--exclude-task=koverVerify`, `--init-script` and the rest, and the
   two prefix rules hold on a CLI that reads it as an exact command. `Bash(./gradlew test *check*)` refuses the other
   spelling, `./gradlew test check -x koverVerify`, which the merged `Bash(./gradlew test:*)` would admit (the
   prescribed test command, `./gradlew test --tests '*ContractTest*' …`, contains no `check`). None of them is what
   guarantees the coverage bound: the real `./gradlew check` is `Build` on the pull request, not the agent's report.
   `env`: `BASH_DEFAULT_TIMEOUT_MS` `900000` and
   `BASH_MAX_TIMEOUT_MS` `1800000`. The CLI's Bash tool times a command out after two minutes by default (the model can
   raise it to ten), and `./gradlew check` takes about three minutes on a warm runner (`Build` run 39706160759: 2m44s),
   so without those values the first `check` is killed and its retry costs turns. The turn budget is 120: a real fixture
   from the newest CLI also reds tests the skill names in its step 4 beyond the contract test, each needing reads and
   edits, and one retry of `check` must fit; the step's 75 minutes and the workspace limit still bound spend. The step
   has `id: agent`, so the `fix` job's output `agent` carries `steps.agent.outputs.conclusion` to step 9.

   Opus is the tier for an implementation task. `Edit` and `Write` here are the CLI's own tools on the runner's
   checkout, not Tandem's. The allowlist names the invocations the skill prescribes, not programs, because the GitHub
   credential behind them is the App's installation token with write access to contents, pull requests and issues,
   and the action puts it into the CLI's environment as `GITHUB_TOKEN` and `GH_TOKEN` and into the `origin` URL:
   `Bash(gh pr:*)` would admit `gh pr merge` of the agent's own PR and `gh pr close` of any other, `Bash(gh issue:*)`
   the closing of any issue, `Bash(git:*)` a `git push -f`, a `git push origin --delete <branch>` or a push to any
   unprotected branch, `main` included while it is unprotected (the project's `.claude/settings.json` denies only the
   prefix `git push --force`),
   `Bash(python3:*)` a `python3 -c` that runs `gh` or `git` with that token, and `Bash(./gradlew:*)` any task. The
   deny rules keep the CLI's `Edit` and `Write` tools out of some of the files that would run code before the push
   (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/`, `scripts/`, the hooks in `.claude/`
   and `.git/`) and out of a second CLI; test code under `src/test/` runs anyway, and `Bash(cp:*)` is not bound by
   them, so the `enforce` job (step 9) checks the result on a fresh runner. The push rule is exact:
   `Bash(git push -u origin HEAD)` admits
   that command and nothing appended to it (no `--force`, no refspec such as `drift/x:main`), because the installed
   CLI (2.1.307) matches a rule without `*` as the whole command (`n===e.command`) and a `:*` rule as the command
   itself or the prefix followed by a space (`n.startsWith(e.prefix+" ")`): `Bash(git push -u origin drift/:*)` would
   never match `git push -u origin drift/2026-10-22-123`, and in headless mode a denied call is a dead end, not a
   prompt. Every command the skill prescribes is on the list exactly as the skill spells it, and the action's allowlist
   names nothing else; the project's merged allow rules still admit their own prefixes (`./gradlew test` and
   `./gradlew check` followed by a task name, `git status`, `git diff`, `git log`), less the deny rules above. That is
   acceptable because what bounds the loop whatever the agent decides is the ruleset on `main` and the App token's
   scope ("Safeguards"), not this list. The step has `timeout-minutes: 75` inside the job's 90.
9. **Enforce the edit allowlist.** A second job, `enforce`: `needs: fix`, `if: always() && needs.fix.outputs.proceed
   == 'true' && needs.fix.outputs.protected == 'success'`, `timeout-minutes: 10`, job permissions `contents: read`,
   `pull-requests: write` and `issues: write` (no `id-token`, no `actions`), no checkout and no action, `GH_REPO` set
   because `gh` has no remote to infer the repository from. A fresh runner, because the `fix` job runs test code the
   agent wrote (`./gradlew test`) with sudo, and that code can change what a later step of the same job sees (a `gh`
   first on `PATH`, `BASH_ENV`, which `bash -e {0}` sources, `GITHUB_PATH`, `GITHUB_ENV`): a step there could be made
   to print "stays within the allowlist" for a PR that edits `build.gradle.kts`. The job reads the `fix` job's outputs
   `proceed` (the gate), `protected` (the protect step's outcome), `kind`, `agent`, `id`, `url` and `date`; all but
   `agent` were written before the agent started (a step's `GITHUB_OUTPUT` is read when that step ends), and `agent`
   only appears in a message. Its one `run:` step finds the loop's pull requests: the open ones whose head is
   `drift/<date>-<run id>` or which carry the label `contract-drift` (`gh pr list --state open --limit 100 --json
   number,headRefName,labels`, selected with `jq --arg branch`; the gate proceeded only when none carried the label,
   so every one of them was opened during this run). A PR from the branch that lacks the label gets it (the issues
   labels endpoint), so the gate holds while it is open instead of the next nightly starting a second agent run. For
   each of them, never only the first, the step lists the
   files it changed against `main` (`gh api --paginate "repos/$GITHUB_REPOSITORY/pulls/<n>/files"`, each
   `filename` and a rename's `previous_filename`: `gh pr view --json files` stops at 100 files and names only a
   rename's new path) and, when any path is outside
   `src/main/kotlin/dev/daarkolabs/tandem/protocol/`, `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/`,
   `src/test/` and `docs/protocol.md`, comments on the PR naming the offending paths, leaves it open and fails the job
   (`exit 1`). More than one such PR is red too (`exit 1`, after every file list was checked): the agent opens one,
   and the extra ones are closed by hand. The PR is not closed: the gate of step 7 keys on an open `contract-drift` PR, so a closed one would
   re-arm the loop, and the next nightly (same red, same CLI) would start a fresh agent run that produces the same
   out-of-allowlist fix, one Opus run plus a `Build` run per night until the owner intervened, against the cost
   safeguard below; and to the gate a close by this step is indistinguishable from the owner closing a wrong fix.
   Left open, the PR holds the gate (step 7 comments on it once per further red), the ruleset on `main` still blocks
   its merge, and the owner decides: close it to re-arm the loop, or merge after review, cherry-picking the agent's work
   where the paths named in the comment are worth keeping. The skill says the
   same rule and the deny rules of step 8 enforce most of it before the push; this job is the check that depends
   neither on the agent obeying either nor on the runner its code ran on. When no PR from the branch and none
   labelled `contract-drift` is open, the step is red (`exit 1`) in both
   remaining cases, so a loop that proceeded past the gate and produced no PR is never green and silent (hard rule 5):
   - It reads the branch through the API, `gh api "repos/$GITHUB_REPOSITORY/branches/drift/<date>-<run id>" --jq
     .name` with `GITHUB_TOKEN` (`contents: read` covers it), never with `git ls-remote`: the job has no checkout
     and no git credential (the action revoked its App token in the `fix` job's post step), so git itself would get a
     401 that `[ -z "$(…)" ]` swallows. A branch without a PR means the agent was stopped after its push (the step's
     `timeout-minutes`, `--max-turns`) and could not keep skill step 9's promise: the step comments on the standing
     issue "CLI contract: the CLI did not answer" (creating it when none is open) naming the branch, the run and the
     stop, and exits 1. It opens no draft: `GITHUB_TOKEN` cannot create a pull request while the repository setting
     "Allow GitHub Actions to create and approve pull requests" is off, and off it stays ("What the owner does"): on,
     `github-actions[bot]` could approve pull requests, and the `fix` job's token is readable by the agent's test code.
   - No branch either: the classification was `decoder` or `reader` but the agent pushed nothing and opened no PR (a
     denied command, the redactor's leak stop of skill step 3, the action skipped after a workflow-validation error
     without running the agent, or the agent gave up). The step prints `::error title=Drift fix::The classification
     was <kind> but the agent pushed no branch and opened no PR …` with `steps.agent.outputs.conclusion` and exits 1.
     The leak stop therefore ends as a red run plus the issue comment, which is the signal to look.

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
   If `src/test/resources/fixtures/<name>.ndjson` exists already (a second red on the same UTC date, after a merged
   fix), the name is `<name>-<run id>` for every file of this fix. Copy `contract-hello.ndjson` to
   `src/test/resources/fixtures/<name>.ndjson` and `transcripts/contract-hello.jsonl` to
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
   value. Then `grep -nE '@|[0-9a-f]{8}-[0-9a-f]{4}-|toolu_|msg_|fv-az|runner|<leaked-'` over the copies, the hand check
   of `docs/launch.md` tactic 4 done by the agent: a hit that is an e-mail address, a UUID, an API id, a hostname, an
   account name or a `<leaked-N>` placeholder is the same stop (a placeholder means the nightly's own redactor already
   caught a leak; the second run leaves it alone and prints nothing, so only the grep sees it); a hit inside reply
   text (a package name with `@`) is not. The rule is the tactic's: a warning is a stop, not a note.
4. **Add the fixture to the tests.** The stream joins `FixtureDecodeTest` and `RobustnessTest` by directory listing
   (`Fixtures.names()`), so the decode test is red now, which is the failing test the fix must turn green. The
   transcript twin joins only by name: add `<name>` (and `<name>-session-id` when copied) to the list in
   `transcript fixtures decode line by line without throwing`, and a `TranscriptReaderTest` case that loads it and
   asserts one `ChatEntry.User` and one `ChatEntry.Assistant`. Add one test that states the fact that drifted: the
   field or type the decoder now recognises, read from the new fixture, and a `RobustnessTest` case for the malformed
   version of that shape (it must still decode to `Unknown`, never throw). Any other test that iterates
   `Fixtures.names()` (`FixtureDecodeTest`'s unknown-types set, `ReducerTest`) may go red with the new fixture: update
   its expectation, never remove the fixture.
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
   a compound command needs every part allowed). A `check` red for a reason unrelated to the drift (a flaky platform
   test, disk space) is run once more; still red, the PR of step 8 is opened as a draft (`--draft`) naming the failing
   test and that it is unrelated, so the owner decides. The live contract check cannot run on the branch: the
   federation rule admits only `main`, so the evidence is the red run's artifact plus `check` on the branch, and the
   next nightly after the merge is the real proof. The PR says so.
8. **Commit and open the pull request.** Branch `drift/<YYYY-MM-DD>-<run id>` from `main` (`git switch -c`), one
   commit, Conventional Commit `fix(protocol): follow CLI <version>: <what changed>` or `fix(transcript): …`, with no
   trailers; the author is the App identity (`claude[bot]`), which the action configured together with the remote URL
   before the CLI started (runtime step 8), so the skill sets no identity and runs no `gh auth setup-git`.
   `git push -u origin HEAD` (`HEAD` is that branch; the exact rule admits this spelling and no other, so no
   `HEAD:main`, no refspec, no flag), then
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
   promise; runtime step 9 reports the pushed branch on the standing issue and reds the run.

### Safeguards

- **Branch**: `drift/<date>-<run id>`, always from `main`, never `main` itself. The owner creates a ruleset on `main`
  requiring a pull request, an approving review from a code owner and approval of the most recent reviewable push,
  before the first rehearsal (the first bullet of "What the owner does"); `.github/CODEOWNERS` owning every file
  (`* @daarko10`) is on this branch. The workflow refuses to start the agent unless it reads such a rule and such a
  file (runtime step 7, `Require a protected main`; classic branch protection is not read, since the workflow token
  cannot see its review count). That, not the skill's rule, is what makes "never push to main" and "never merge" true
  for the App token. A protection that only requires a pull request does not stop a `gh pr merge` by the App: a pull
  request is what it merges. One approving review alone does not either: the App cannot approve its own pull request,
  but it holds pull-request write and can approve any pull request it did not author (the owner's open feature PR
  after the App pushes commits to its unprotected head branch, a Dependabot or outside PR) and then merge it, and
  the agent's test code runs with the App token in its environment. An App is no code owner, so the code-owner
  review is one it cannot give, and approval of the most recent push is one it cannot give for its own push; with
  both required the merge is refused even if the tool allowlist regresses. (The owner, the only code owner, cannot
  approve their own pull requests either, so they merge them through the ruleset's bypass list, which names the
  repository admin role and never the Claude App.)
- **Tool allowlist and deny rules**: the allowlist names the invocations the skill prescribes, with an exact push rule,
  and the `settings` deny rules keep the agent out of `.github/`, the build files (`build.gradle.kts`,
  `settings.gradle.kts`, `gradle.properties`, `gradle/`), `scripts/`, `.claude/`, `.git/`, `CLAUDE.md` and a second
  CLI (runtime step 8). They guide the agent; they are not what protects `main`. The App token is in the
  agent's environment (`GITHUB_TOKEN`, `GH_TOKEN`) and in the `origin` URL, `Edit` and `Write` are otherwise
  unrestricted, and an allowlist regression is one line, so the guarantee is the ruleset on `main` (a pull request
  plus a code owner's approving review of the last push; the App is no code owner, cannot approve its own push and,
  absent from the bypass list, cannot bypass) together with the App's permission scope (contents, pull requests, issues: no administration, no
  workflows). The skill prescribes no command outside the list: in headless mode a denied call is a dead end, not a
  prompt.
- **One open fix at a time**: an open PR labelled `contract-drift` (rehearsal or real) skips the agent entirely (step 7
  of the runtime). Closing or merging it re-arms the loop.
- **Edit allowlist**: `protocol/`, `session/transcript/`, `src/test/`, `docs/protocol.md`; enforced before the push by
  the deny rules of step 8 and after the action by the `enforce` job (step 9) on a fresh runner, which comments on an
  offending PR naming the paths, leaves it open so the gate holds, and reds the run; never closes it. The allowlist
  keeps the fixer out of
  `.github/`, so no bot
  ever becomes the last editor of the cron line, which is what GitHub's documented rule attributes a schedule run to
  (see "Errors and edge cases" on the actor check).
- **A stopped agent**: a pushed `drift/` branch without a pull request is reported on the standing issue and the run
  is red; no branch and no pull request after the gate proceeded is red as well (runtime step 9): never a green loop
  and silence.
- **No retrigger**: the nightly has no `push`/`pull_request` trigger; the fixer listens to `CLI contract` only.
- **Cost**: `--max-turns 120`, `timeout-minutes` on the step and the job, one concurrency slot, kind-1 and unknown
  reds never start the agent, the open-PR skip, and the workspace spend limit the owner sets in the Claude Console
  for `wrkspc_012JJzfEG8tDKeta7pe3CCa5` as the ceiling nothing in the repository can raise.
- **Missing artifact**: the fixer refuses (kind 1 path) rather than recording.
- **Remaining risk, the Actions cache**: the agent's test code runs with sudo in a job on `main`, so it can write a
  cache in `main`'s scope (with the runner's cache token, or by rewriting setup-gradle's post step under `_actions/`)
  that `Build` restores on `main` and on every pull request; `cache-read-only: true` stops only setup-gradle's own
  save. Nothing in the repository closes this while the agent runs on `main`. The ruleset and the App token's scope
  still keep the agent off `main` and out of a merge, and the owner can clear the caches (Actions | Caches) after a
  suspect run.
- **Nothing outward-facing**: the PR and the issue are inside the repository; nothing is published, posted or merged.

### What the owner does

- Once, before the first rehearsal, in this order: creates a ruleset (Settings | Rules | Rulesets) targeting `main`,
  enforcement active, with "Require a pull request before merging", one required approval, "Require review from Code
  Owners" and "Require approval of the most recent reviewable push" (the guarantee behind the tool and edit
  allowlists; `.github/CODEOWNERS` with `* @daarko10` is on this branch, so `main` owns every file once it is merged;
  no rehearsal is run until the ruleset is on, and until it is on every decoder or reader red ends red at `Require a
  protected main` without starting the agent; classic branch protection alone does not pass that step, nor does a
  ruleset without the two review options). Keeps the repository setting "Allow GitHub Actions to create and approve
  pull requests" (Settings | Actions | General) off: on, `github-actions[bot]` could approve pull requests, and the
  `fix` job's token is readable by the agent's test code.
  A ruleset on a private repository owned by a personal account is enforced only with GitHub Pro: on Free, the
  repository goes public before the ruleset and the rehearsals, and the rehearsal (D5) waits for that. Before the
  first rehearsal the owner runs `gh api repos/daarko10/tandem/rules/branches/main` and sees the `pull_request` rule
  with `required_approving_review_count` of at least 1, `require_code_owner_review` true and
  `require_last_push_approval` true (what the endpoint lists for an unenforced ruleset is not known, so the check
  follows the enforcement, never replaces it).
  Its bypass list never names the Claude App, and names the repository admin role: the owner is the only code owner
  and cannot approve their own pull requests, so the bypass is how they merge anything. Then checks that the Claude GitHub App is installed on `daarko10/tandem`
  (open item 1); runs the rehearsal (below). Nothing waits on a scheduled nightly: its actor is already recorded (open
  item 4, closed).
- Reads the PR. Merges it (squash), or closes it with a comment saying why (the wrong fix, a change Tandem should
  handle differently, a fixture to re-record by hand, files outside the edit allowlist, which the allowlist step's
  comment names). Either re-arms the loop. A merge is followed by the next
  nightly, which is the real verification; if it is red again the loop opens the next PR.
- On a comment on the standing issue "CLI contract: the CLI did not answer": fixes the cause (the federation rule,
  the service account, the spend limit, a pinned CLI version while a broken publish lasts) and closes the issue; the
  next kind-1 red opens it again. The same issue carries the loop's other reports: a leak the redactor found (skill
  step 3) and a branch pushed without a pull request (runtime step 9).

### The rehearsal

The loop is proven end to end before a real CLI change, through the real trigger:
`gh workflow run cli-contract.yml --ref main -f drift=<mode>`. The nightly records live as always, then its step "Mutate
the recording for a rehearsal" (`if: inputs.drift != ''`) runs
`python3 -I scripts/make_drift.py "$DRIFT" build/contract --from build/contract`. The script reads the three recorded
files (`contract-hello.ndjson`, `transcripts/contract-hello.jsonl`, `transcripts/session-id.jsonl`) before it writes
any, so the recording is mutated in place, and leaves the marker `build/contract/rehearsal` holding the mode. The Gradle
step goes red, the real artifact is uploaded, and `workflow_run` fires the fixer with `conclusion == failure` on `main`.
Everything a dispatch of the fixer alone would skip is exercised: the `workflow_run` trigger and the job `if`
(`github.ref`, `head_branch`, `head_repository`, `event`), the action's checks of `github.actor` and
`workflow_run.actor` for a `workflow_run` event, the OIDC subject of a `workflow_run` job (open item 2), `actions: read`
with the artifact listing and `gh run download`, and the artifact's layout. The fixer finds the marker and passes
`rehearsal=true` to the skill. Two modes, each a drift the current decoder or reader rejects in the way a real rename
would:

- `init-model-key` (decoder drift): the `system/init` line's `model` key becomes `model_id`. `Ndjson` requires `model`
  on `init`, so the line decodes to `Unknown` and the decode test fails with "no system/init". The line is found by
  content (`type` `system`, `subtype` `init`), never by number: a live recording has `active_goal` and
  `autocompact_state` lines before it, their count varying by CLI version (`history-reply` has `init` on line 2,
  `plain-reply` on line 4, `permission` on line 8).
- `transcript-content-key` (reader drift): every `user` line of both transcripts (found by `type`, with a
  `message.content`) has `message.content` renamed to `message.blocks`, so the reader yields no user entry. Which
  transcript the test names is up to directory order; the skill copies both for every reader drift (skill step 2).

Without `--from`, `make_drift.py <mode> <out>` mutates the committed fixture `history-reply` (CLI 2.1.301, the one-shot
reply that has a transcript twin) into the artifact's layout, the twin standing in for both transcripts, marker
included. That is the fixer-only rehearsal: `workflow_dispatch` of `drift-fix.yml` with `rehearse=true` and `mode`
(string, default `init-model-key`) replaces the download (runtime step 2) with that script, spends no nightly
recording, and runs the classification, the skill, the protocol.md edit and the PR as the real path does, but
exercises no trigger; it serves a by-hand run of the loop and the proof of the open-PR skip. Either way the marker
makes the fixture `rehearsal-<date>`, the PR a draft titled `[rehearsal] …` with the `rehearsal` label, and the owner
closes it; a rehearsal branch merged by mistake is recognisable by the fixture name. Two rehearsals through the
nightly, one per mode, are run before the branch holding this design is considered done, and so is the kind-1 path,
the commonest red, which neither of them touches: a `workflow_dispatch` with `run_id` set to one of the six red
scheduled `CLI contract` runs (2026-10-17 to 2026-10-21) exercises the fetch with no artifact (or an `is_error`
stream), the precheck short-circuit that skips Gradle, and the report step, which is the most hand-written shell in
the workflow and otherwise first runs on a real night (see "Testing"). A rehearsal is repeated after any change to the
skill, `classify_drift.py`, `make_drift.py`, `cli-contract.yml` or `drift-fix.yml`.

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
  PR exists, the allowlist step reports the branch on the standing issue and exits 1 (runtime step 9). The job is red
  either way, which is the signal to look.
- The gate proceeded (`decoder` or `reader`) but the agent pushed no branch and opened no PR (a denied command, the
  leak stop of skill step 3, the action skipped after a workflow-validation error without running the agent, or the
  agent gave up): the allowlist step exits 1 naming the classification and `steps.agent.outputs.conclusion` (runtime
  step 9). Never a green `Drift fix` run that did nothing.
- The artifact is listed but `gh run download` fails (a 403, an API outage, a renamed artifact): the job is red at the
  fetch step and the standing issue is not touched; GitHub shows it in Actions. It is never reported as "the CLI did
  not answer".
- The redactor prints a leak, or the recording already holds a `<leaked-N>` placeholder: no PR, a comment on the
  standing issue naming the key path only, and a red run (the bullet above).
- The CLI renamed or stopped emitting `assistant` in `-p` mode: the nightly's check step reds the run with "No
  assistant line although the result holds text", the classification is `decoder` by rule 3 (the decode test itself is
  green), and the skill's first test decodes the new fixture and asserts an `Assistant` message (skill step 5).
- `./gradlew check` fails for a reason unrelated to the drift (a flaky platform test, disk space): the skill retries
  `check` once, then opens the PR as a draft and says which test failed and that it is unrelated, so the owner decides.
- Two nightlies red before the owner acts: the second fixer comments on the open PR and stops.
- The fix edits a file outside the edit allowlist: the allowlist step comments on the PR naming the paths, leaves it
  open and exits 1 (runtime step 9). The open PR holds the gate, so the same red costs no second agent run; closing
  it is the owner's decision and re-arms the loop; the ruleset on `main` blocks its merge.
- The fixer workflow is itself broken (a YAML error, a missing script): GitHub shows it in Actions; the standing issue
  is not touched. `actionlint` on the branch catches the YAML part before merge.
- The action's human-actor check (`github.actor` of the fixer's `workflow_run` job is the actor of the nightly, and the
  action checks `workflow_run.actor.login` as well): answered. GitHub attributes a `schedule` run to the user who last
  modified the cron line, and the six scheduled `CLI contract` runs so far (2026-10-17 to 2026-10-21, all red) carry
  `actor` and `triggering_actor` `daarko10`, a User with admin access (`gh api
  'repos/daarko10/tandem/actions/runs?event=schedule' --jq '.workflow_runs[].actor.login'`), so the action's
  `checkHumanActor` and `checkWritePermissions` pass for the chain schedule → red run → `workflow_run`. The edit
  allowlist keeps the cron line's last editor human. The schedule fires hours after 03:17 UTC under load; the date the
  fixer takes from the run's `createdAt` tolerates that.

## Testing

- `scripts/test_classify_drift.py`: each rule in order against hand-built recordings and test XML (no recording,
  `is_error`, no assistant line with an empty result, no assistant line with result text, decode failure message,
  reader failure message, both, neither, no XML); the `cli_version` from `system/init`; the first-400-characters cut;
  a stream line that is not JSON is skipped as the nightly's check skips it; a failure in another test class is not
  drift (pinned by a contract test name under another class).
- `scripts/test_make_drift.py`: each mode changes exactly the lines it names and nothing else (line count and every
  other line byte-identical); the output layout with the marker; `--from` on a recording mutated in place gives the
  same bytes as the fixture-sourced run; `--from` finds the `init` line by content (a `plain-reply` stream, `init` on
  line 4 behind `active_goal`, `autocompact_state` and `ui_invalidate`, mutated in place: line 4 gains `model_id`,
  every other line is byte-identical); the refusal to write under the fixtures directory; the `<session_id-N>`
  placeholders survive.
- `scripts/test_workflows.py`: the fixer's trigger, permissions, step order and guards (the fetch's `mkdir`, the
  presence check and the absence of `|| echo`; the Gradle and classification guards on the precheck; the allowlist
  step in its own job `enforce` (`needs`, `if`, permissions, no checkout, `GH_REPO`, the `fix` job's outputs), its
  branch read through the API and its `exit 1` whenever no PR exists; the protect step's refusal of an existing drift
  branch; the exact push rule and the absence of
  any `Bash(git push…:*)` or broad `Bash(python3:*)`/`Bash(./gradlew:*)` rule; the deny rules of the `settings` input
  (the list, exactly) and the step id `agent`; the protection step before the agent, which the agent and the allowlist
  step depend on; the REST file list with renames' old paths and the exact edit allowlist; `--rerun`; a `run_id` on
  `main`; no `${{` in any `run:` block); the nightly's `drift` input, mutate step, check step and
  redaction; the skill's `allowed-tools` equal to the action's. Under `GITHUB_ACTIONS` a missing PyYAML is an error,
  not a skip.
- `actionlint` over both workflows when it is installed; otherwise the push's own parse and the rehearsal.
- The rehearsal, both modes, through `cli-contract.yml` with `drift=<mode>` on `main` after merge: proves the
  `workflow_run` trigger and the job `if`, the action's actor checks for that event, the artifact listing and the
  download, the classification, the skill end to end, the App token (the PR's `Build` run starts), the allowlist step
  and the labels. The fixer-only dispatch (`rehearse=true`) proves the open-PR skip. Its draft PRs are closed by the
  owner.
- The kind-1 path, with the existing `run_id` input and no spend: `gh workflow run drift-fix.yml --ref main -f
  run_id=<id>` of one of the six red scheduled `CLI contract` runs (2026-10-17 to 2026-10-21), one that left no
  artifact or whose artifact is still within its 14-day retention (an expired artifact is listed but cannot be
  downloaded, so the fetch step reds the job by design: that is the fetch-failure path, not kind 1). Expected: "Name
  the red run" prints that run's url and date; "Fetch the recording" prints `the run left no recording` (or downloads
  a stream whose `result` has `is_error: true`); "Classify before Gradle" prints `"kind": "cli-did-not-answer"`;
  "Reproduce the red", "Classify", the gate, "Fix the drift" and the allowlist step are skipped; "Report a run the
  agent cannot fix" creates the issue "CLI contract: the CLI did not answer" with the label `contract-drift` (or
  comments on it); the job is green with no Gradle and no agent spend; `gh issue view` shows the run link, `kind`, the
  CLI version, `detail` and `files`. The issue may stay open or be closed; either is fine for the next rehearsal.
- `./gradlew check` unchanged: the new scripts and workflows do not touch Kotlin.
- Not testable before merge: the `workflow_run` firing itself (the workflow must be on `main`), the federation
  exchange of a `workflow_run` job (inferred from GitHub's subject rules; the first fixer run's federation log line
  names the subject, and the Console's authentication history shows a mismatch if not). The first rehearsal through
  the nightly answers both.

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
   the new fixture" (a workflow step with its own `anthropic-token` fetch before the action, never a `claude` started
   by the agent, which the deny rules refuse), and let the skill promote from `build/drift/recorded`.

## Open items

1. Whether the Claude GitHub App is installed on `daarko10/tandem`; the owner checks before the rehearsal. Without it
   the action does not fall back to `GITHUB_TOKEN`: its App token exchange throws after retries and the step fails
   before the CLI starts, so the symptom is a red "Fix the drift" step at "Exchanging OIDC token for app token". The
   one exchange error the action swallows is a workflow-validation error, after which the step ends green without
   running the agent (output `skipped_due_to_workflow_validation_mismatch=true`); the allowlist step then finds no PR
   and no branch and exits 1 (runtime step 9), so that case is red too.
2. The exact OIDC subject of a `workflow_run` job on `main`, confirmed by the federation log line of the fixer run the
   first rehearsal through the nightly triggers.
3. The Opus model id to pin in `claude_args`: `claude-opus-5-5` is the `default` the 2.1.301 fixtures' `initialize`
   response lists; the id current at implementation time is used.
4. Closed: the actor GitHub attributes a scheduled `CLI contract` run to is `daarko10` (six scheduled runs, 2026-10-17
   to 2026-10-21; see "Errors and edge cases"), so nothing waits on a scheduled run. Also closed: the push identity. The action sets the
   `origin` URL with the App token and `user.name`/`user.email` from `bot_name`/`bot_id` before the CLI starts, so neither `gh auth setup-git`
   nor an identity fallback is needed.

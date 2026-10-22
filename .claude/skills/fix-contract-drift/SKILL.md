---
name: fix-contract-drift
description: "Turn a red nightly CLI contract run into a pull request carrying the recording as a fixture and the minimal decoder or reader change. Arguments: run=<url> recording=<dir> classification=<file> rehearsal=<true|false> date=<YYYY-MM-DD>."
allowed-tools: Read, Grep, Glob, Edit, Write, Bash(./gradlew check), Bash(./gradlew test:*), Bash(python3 -I scripts/classify_drift.py:*), Bash(python3 -I scripts/redact_fixture.py:*), Bash(python3 -m unittest discover -s scripts -t scripts), Bash(gh pr create:*), Bash(gh issue list:*), Bash(gh issue create:*), Bash(gh issue comment:*), Bash(git status:*), Bash(git diff:*), Bash(git log:*), Bash(git add:*), Bash(git commit:*), Bash(git switch:*), Bash(git clean:*), Bash(git push -u origin HEAD), Bash(cp build/drift/recording/* src/test/resources/fixtures/*), Bash(grep:*)
---

# Fix contract drift

Arguments (`$ARGUMENTS`): `run=<url of the red CLI contract run>` `recording=<dir>` (the run's `contract-recording`
artifact, or `scripts/make_drift.py`'s output in a rehearsal) `classification=<file>` (what `scripts/classify_drift.py`
printed) `rehearsal=<true|false>` `date=<YYYY-MM-DD>` (the red run's UTC date; today when absent). By hand:
`gh run download <run id> -n contract-recording -D build/drift/recording` first. The run id is the last path
segment of `run=`. Time matters here: do not spend time that can be avoided, and the earlier a correct result is
obtained, the better.

You never fix authentication, never guess, never merge, never edit `.github/`, `build.gradle.kts` or `CLAUDE.md`,
never lower the coverage bound, never start a second `claude`, and never re-record: the recording is the fixture. A re-recorded scenario, when the
workflow records one, appears under `build/drift/recorded`; today it records none. Only the commands named here are
allowed (the tool list above). In the workflow a denied command is a dead end, not a prompt: try no other, and run no
compound command (`cd … && …`) whose parts are not all on the list.

## 1. Read the classification
`python3 -I scripts/classify_drift.py <recording> build/test-results/test` (run `./gradlew test --tests '*ContractTest*'
-Dtandem.contractDir=<recording> --rerun` first if `build/test-results/test` is missing). `cli-did-not-answer` or
`unknown`: stop, open no branch; by hand, say what `detail` is. `decoder` or `reader`: go on; `detail` holds the
assertion message(s), both when both tests failed (decoder first). A `decoder` whose detail ends in `no assistant line,
result text present` comes with a green decode test: the reply arrived under a type `Ndjson` does not know (see 5).

## 2. Name the fixture and copy the recording
Name `drift-<date>`, or `rehearsal-<date>` when `rehearsal=true`. If `src/test/resources/fixtures/<name>.ndjson` exists
already, use `<name>-<run id>` for every file of this fix. The recording is `build/drift/recording` (the `recording=`
argument), and `cp` is allowed from there into the fixtures only, spelled with those two relative paths and nothing
else (no `./`, no `..`, no flag, no third argument):
`cp build/drift/recording/contract-hello.ndjson src/test/resources/fixtures/<name>.ndjson` and
`cp build/drift/recording/transcripts/contract-hello.jsonl src/test/resources/fixtures/transcripts/<name>.jsonl`. For
reader drift, always `cp build/drift/recording/transcripts/session-id.jsonl
src/test/resources/fixtures/transcripts/<name>-session-id.jsonl` too, whichever file the detail names: the test stops
at the first failing transcript in directory order, and both carry the new shape. Never overwrite an existing fixture:
the committed ones pin recorded facts (`plain-reply` pins 2.1.300 and one "pong").

## 3. Run the redactor's safety net
`python3 -I scripts/redact_fixture.py <copy> <copy>` for each copied file (onto itself; redacted input changes
nothing). Any `unredacted id` or `<leaked-N>` on stderr is a stop: remove the copies with `git clean -f -- <copies>`,
open no PR, and comment on the open issue titled "CLI contract: the CLI did not answer" (`gh issue list --label
contract-drift --state open --json number,title`; `gh issue create` with that title and the label `contract-drift`
when none is open) naming the key path the redactor printed, never the value. Then
`grep -nE '@|[0-9a-f]{8}-[0-9a-f]{4}-|toolu_|msg_|fv-az|runner|<leaked-' <copies>`, over the copies and nothing else
(never `.git/`, whose `config` holds the push credential): an e-mail address, a UUID, an API id, a hostname, an
account name or a `<leaked-N>` placeholder (the nightly's redactor already caught a leak; your run leaves the
placeholder alone and prints nothing) is the same stop; a package name with `@` inside reply text is not,
nor is a capability name such as `msg_lifecycle_v1` in `system/init` (an API id is `msg_` or `toolu_` followed by a
random string, and the redactor has already replaced every one it knows with a `<key-N>` placeholder).

## 4. Add the fixture to the tests
The stream joins `FixtureDecodeTest` and `RobustnessTest` by directory listing (`Fixtures.names()`); `every fixture
line decodes and core types are never Unknown` is red only when a core type now decodes to `Unknown`. For a
`system/init` or field drift (the rehearsal's `init-model-key` included) nothing is red yet: write the test stating the
fact that drifted (below) first; it is the failing test the fix turns green. Add `<name>` (and `<name>-session-id`) to the list in
`transcript fixtures decode line by line without throwing` (`FixtureDecodeTest`), and a `TranscriptReaderTest` case
that loads `Fixtures.transcript("<name>")` and asserts one `ChatEntry.User` and one `ChatEntry.Assistant`. Add one test
stating the fact that drifted (the field or type now recognised, read from the new fixture) and a `RobustnessTest` case
for its malformed version (still `Unknown`, never a throw). Any other test that iterates `Fixtures.names()`
(`FixtureDecodeTest`'s unknown-types set, `ReducerTest`) may go red with the new fixture: update its expectation, never
remove the fixture.

## 5. Patch minimally
Decoder drift: `src/main/kotlin/dev/daarkolabs/tandem/protocol/Ndjson.kt` and `CliMessage.kt` only; a renamed field is
read under both names, a new required field is read optionally, a new type or subtype gets a typed message only when
Tandem needs it and `Unknown(raw)` otherwise. For a detail ending in `no assistant line, result text present`, write first a
test that decodes the new fixture and asserts a `CliMessage.Assistant`; the fix reads the new type beside `assistant`.
Reader drift: `TranscriptReader.kt` and `TranscriptChain.kt` under
`src/main/kotlin/dev/daarkolabs/tandem/session/transcript/`. Validate every field, no IntelliJ imports in `protocol/`,
no logging of prompt or file contents. Edits are allowed only under `src/main/kotlin/dev/daarkolabs/tandem/protocol/`,
`src/main/kotlin/dev/daarkolabs/tandem/session/transcript/`, `src/test/` and `docs/protocol.md`; the workflow reds its
run and comments on a PR that touches anything else, naming the paths, and leaves it open for the owner. If
`koverVerify` fails, a test must exercise the new branch; never lower the bound.

## 6. Update docs/protocol.md for a stranger
The new fixture and its CLI version (`system/init.claude_code_version` of the recording) in the Sources list; the
changed shape in the section that describes it, citing the fixture by name and the CLI version; a row in section 10
("Fixture fields and types the SDK does not document") for a new field; a gotchas row (section 9) when the old shape
is still possible. No reference to any spec or plan.

## 7. Verify
`./gradlew check` and, from the repository root, `python3 -m unittest discover -s scripts -t scripts` (the same tests
as `cd scripts && python3 -m unittest`, which you cannot run: `cd` is not on the list). If `check` fails for a reason
unrelated to the drift (a flaky platform test, disk space), run it once more; still red: open the PR as a draft
(section 8, add `--draft`) and name the failing test and that it is unrelated to the drift, so the owner decides.
The live contract check cannot run on the branch (the federation rule admits `main` only); the evidence is the red
run's artifact plus `check` here, and the next nightly after the merge is the real proof.

## 8. Commit and open the pull request
Branch `drift/<date>-<run id>` from the current `HEAD` of `main` (`git switch -c drift/<date>-<run id>`). One commit,
no trailers, subject `fix(protocol): follow CLI <version>: <what changed>` or `fix(transcript): …`. The author and the
push credential are set before you start (in the workflow, the Claude GitHub App: `claude[bot]`, and the App token in
the `origin` URL): set no identity and run no `gh auth setup-git`. `git push -u origin HEAD`, spelled exactly so (`HEAD`
is the branch you just created; `HEAD:main`, a refspec or a flag is a different command and is denied). Write
the body to `build/drift/pr-body.md` (`build/` is gitignored, so it cannot end up in the commit), then
`gh pr create --base main --label contract-drift --title "<subject>" --body-file build/drift/pr-body.md` (add
`--draft --label rehearsal` and the title prefix `[rehearsal] ` in a rehearsal, whose body starts with the line "This
is a rehearsal of the drift loop: close it, do not merge it."). The labels exist: the workflow creates them before
you start; by hand, the owner created `contract-drift` and `rehearsal` once. The body, for a reader:
- **What the CLI changed**: one paragraph, the CLI version from the recording and the version the previous fixtures
  were recorded on (Sources list of docs/protocol.md).
- **The fixture line that shows it**: fixture name, line number, the field or type, quoted.
- **What the decoder (or reader) does now**, and what it still does for the old shape.
- **Verified**: `./gradlew check` on the branch, `python3 -m unittest discover -s scripts -t scripts`, the redactor's
  safety net clean.
- **Not verified**: the live check on this branch (federation admits `main` only); the next nightly after merge is
  the proof. Anything you could not reproduce.
- The run link (`run=`) and the artifact name `contract-recording`.

## 9. Stop
No merge, no second PR, no comment anywhere else. If a step after the push fails, still open the PR, as a draft, and
name the failing step in the body: a half-done branch is more useful than silence. (If you are stopped before you can,
the workflow reports the pushed branch on the standing issue and goes red.)

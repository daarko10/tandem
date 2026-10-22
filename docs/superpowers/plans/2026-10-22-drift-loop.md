# Drift Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A red nightly `CLI contract` run becomes a pull request the same night, without a human: the fixer workflow downloads the red run's recording, classifies the red, and a repository skill promotes the recording to a fixture, patches the decoder or the reader, runs the tests and opens the PR; a run the CLI did not answer is reported on a standing issue instead.

**Architecture:** Two pure-Python scripts under `scripts/` (`classify_drift.py` tells the three kinds of red apart from the recording and the JUnit XML; `make_drift.py` mutates the committed `history-reply` fixture pair into a rehearsal recording), a repository skill `.claude/skills/fix-contract-drift/SKILL.md` that prescribes the fix procedure, and a workflow `.github/workflows/drift-fix.yml` (`workflow_run` of `CLI contract` plus a `workflow_dispatch` rehearsal) that runs the scripts, guards against double fixes, runs `anthropics/claude-code-action@v1` with Workload Identity Federation and enforces an edit allowlist afterwards. The nightly gets four additions (a redacted `session-id.jsonl`, failure messages as annotations, the renamed-core-type case in its check step, and a `drift` input whose run mutates its own recording for the rehearsal). Nothing merges, publishes or posts outside the repository.

**Tech Stack:** GitHub Actions (`workflow_run`, `workflow_dispatch`, OIDC `id-token`), `anthropics/claude-code-action@v1` with federation inputs, `gh` CLI, Python 3 `unittest` (`-I`-safe scripts, `xml.etree`, `json`), PyYAML for the workflow tests, the existing `scripts/redact_fixture.py`, Gradle `ContractTest`.

**Spec:** `docs/superpowers/specs/2026-10-22-drift-loop-design.md`

Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

## Global Constraints

- Branch `ci/drift-loop` in the worktree `/tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree`; never touch `/home/user/tandem`. Every Gradle command below runs from that worktree.
- Commits are authored by Dago Kogos with no trailers of any kind (no `Co-Authored-By`, no `Claude-Session`, no "Generated with" line). Before the first commit, once: `git config user.name "Dago Kogos" && git config user.email "$(git log -1 --format=%ae 72ae807)"` (the spec commit's author). Conventional Commits.
- Model tiers: Tasks D1, D2, D3 and D5 run on Opus with an Opus per-task reviewer; Task D4 (docs) runs on Sonnet; the final whole-branch review runs on Fable. A task that fails review twice moves to Fable.
- Hard rules of `CLAUDE.md` 6 and 8: every protocol change gets a fixture test; unknown message types parse to `Unknown(raw)`, never throw; the product is "Tandem", which works with Claude Code. Nothing in this plan changes Kotlin: `./gradlew check` stays as it is.
- `docs/launch.md` is not edited (its milestone bullet "Drift loop closed" is appended by the owner after the first real merged fix); `docs/protocol.md` is not edited (the fixer edits it per drift); nothing under `.github/` is ever edited by the fixer.
- Exact values from the spec: workflow name `Drift fix`; job `fix`; `concurrency.group: drift-fix`, `cancel-in-progress: false`; job `timeout-minutes: 90`, action step `timeout-minutes: 75`; permissions `id-token: write`, `contents: read`, `pull-requests: read`, `issues: write`, `actions: read` (the `fix` job's token holds no pull-request write: a comment on a pull request needs it even through the issues endpoint, so the gate only writes `proceed=false` and `waiting=<n>`); job `remind` (`needs: fix`, `if: needs.fix.outputs.proceed == 'false'`, `timeout-minutes: 5`, job permissions `pull-requests: write`, `issues: write`, no checkout, `GH_REPO: ${{ github.repository }}`) holding the step `Comment on the waiting pull request`, which reads the `fix` job's outputs `waiting`, `url` and `date`; job `enforce` (`needs: fix`, `timeout-minutes: 10`, job permissions `contents: read`, `pull-requests: write`, `issues: write`, no checkout, `GH_REPO: ${{ github.repository }}`) holding the step `Enforce the edit allowlist`, which reads the `fix` job's outputs `proceed`, `protected`, `kind`, `agent`, `id`, `url`, `date` (`waiting` is the `remind` job's); artifact `contract-recording`; recording dir `build/drift/recording`; classification `build/drift/classification.json`; re-recording dir (unused today) `build/drift/recorded`; labels `contract-drift` and `rehearsal`; standing issue title `CLI contract: the CLI did not answer`; branch `drift/<YYYY-MM-DD>-<run id>`; fixture names `drift-<YYYY-MM-DD>` and `rehearsal-<YYYY-MM-DD>` (UTC date of the red run); kinds `cli-did-not-answer`, `decoder`, `reader`, `unknown`; detail cut at 400 characters; `--model claude-opus-5-5`, `--max-turns 120`; the tool allowlist `Read,Grep,Glob,Edit,Write,Bash(./gradlew check),Bash(./gradlew test:*),Bash(python3 -I scripts/classify_drift.py:*),Bash(python3 -I scripts/redact_fixture.py:*),Bash(python3 -m unittest discover -s scripts -t scripts),Bash(gh pr create:*),Bash(gh issue list:*),Bash(gh issue create:*),Bash(gh issue comment:*),Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(git add:*),Bash(git commit:*),Bash(git switch:*),Bash(git clean:*),Bash(git push -u origin HEAD),Bash(cp:*),Bash(ls:*),Bash(cat:*),Bash(grep:*),Bash(date:*)` (27 entries: the invocations the skill prescribes, the push exact); the action's `settings` input `{"permissions": {"deny": ["Edit(.github/**)", "Write(.github/**)", "Edit(build.gradle.kts)", "Write(build.gradle.kts)", "Edit(settings.gradle.kts)", "Write(settings.gradle.kts)", "Edit(gradle.properties)", "Write(gradle.properties)", "Edit(gradle/**)", "Write(gradle/**)", "Edit(.git/**)", "Write(.git/**)", "Edit(scripts/**)", "Write(scripts/**)", "Edit(.claude/**)", "Write(.claude/**)", "Edit(CLAUDE.md)", "Write(CLAUDE.md)", "Bash(claude:*)", "Bash(./gradlew check -x:*)", "Bash(./gradlew check --exclude-task:*)", "Bash(./gradlew check *-*)", "Bash(./gradlew test *check*)", "Bash(./gradlew compileKotlin:*)", "Bash(./gradlew compileTestKotlin:*)", "Bash(./gradlew buildPlugin:*)", "Bash(./gradlew verifyPlugin:*)", "Bash(ktlint:*)"]}, "env": {"BASH_DEFAULT_TIMEOUT_MS": "900000", "BASH_MAX_TIMEOUT_MS": "1800000"}}` (28 deny rules, and two `env` values: the CLI's Bash tool times a command out after two minutes by default, under `./gradlew check`'s three minutes on a warm runner, so `check` and its one retry run to completion); the action step `id: agent`; edit allowlist `src/main/kotlin/dev/daarkolabs/tandem/protocol/`, `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/`, `src/test/`, `docs/protocol.md`; the four Anthropic identifiers exactly as `cli-contract.yml` carries them in `env:` (`fdrl_019BGJmAMDiAL6DmAQENbhfk`, `8336fe5f-a7f7-4255-9fe0-7f594d5a8ec8`, `svac_01F45vdtRBnPSRZHXY8ek1fn`, `wrkspc_012JJzfEG8tDKeta7pe3CCa5`).
- The workflow passes `date=<YYYY-MM-DD>` (the red run's UTC date) as the prompt's fifth argument, so the skill never needs `actions: read` through the App token to look the run up. Run by hand, `date=` defaults to today in UTC. The prompt's `rehearsal=` comes from the marker file `rehearsal` in the recording (written by `make_drift.py`), not from a dispatch input: the real rehearsal arrives through `workflow_run` like any red.
- The fixer's tool allowlist names the invocations the skill prescribes (spec, runtime step 8), and the push rule is exact: `Bash(git push -u origin HEAD)`. The installed CLI matches a rule without `*` as the whole command and a `:*` rule as the command itself or the prefix followed by a space, so `Bash(git push -u origin drift/:*)` would never match `git push -u origin drift/<date>-<run id>` and the loop could never open a PR. The list guides the agent; what protects `main` is a ruleset on `main` requiring a pull request, an approving review from a code owner and approval of the most recent reviewable push, with `main`'s `.github/CODEOWNERS` owning every file (`* @daarko10`, created by D2; the step `Require a protected main` reads the rule and the file and starts no agent without both; classic branch protection is not read, and one approving review alone is not enough: the App can approve a pull request it did not author, but it is no code owner and cannot approve its own push) and the App token's scope, since the token is in the agent's environment and the `origin` URL. The skill prescribes no command outside the list and no compound command whose parts are not all on it (in headless mode a denied call is a dead end, not a prompt): the script tests run as `python3 -m unittest discover -s scripts -t scripts` from the repository root, the leak stop removes copies with `git clean -f --`, the labels are created by the workflow and never by the skill, the push is `git push -u origin HEAD` from the `drift/…` branch, and no `gh auth setup-git` or identity fallback exists (the action sets the `origin` URL and `user.name`/`user.email` before the CLI starts). The deny rules of the action's `settings` input keep the CLI's `Edit` and `Write` tools out of `.github/`, the build files (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/`), `scripts/`, `.claude/`, `.git/` and `CLAUDE.md`, and keep out a second `claude`, any flag after `./gradlew check` and a `./gradlew test … check …`; the `Edit`/`Write` rules do not bind `Bash(cp:*)`, and test code under `src/test/` runs anyway with sudo and can change what a later step of the same job sees, so the `enforce` job checks the result after the push on a fresh runner, from the `fix` job's outputs. The real `./gradlew check` is `Build` on the pull request.
- `scripts/*.py` run under `python3 -I` and import only the standard library (PyYAML only in `test_workflows.py`, which skips itself when PyYAML is missing, except under `GITHUB_ACTIONS`, where it raises). Tests run with `python3 -m unittest discover -s scripts -t scripts -v` from the worktree root (the skill's spelling), the same set `build.yml`'s "Script tests" step runs as `cd scripts && python3 -m unittest`.
- The federation rule admits only `refs/heads/main`: the rehearsal (D5) can run only after `ci/drift-loop` is merged. Everything else is verified on the branch before that, so the merge is not a wait: D1 proves both rehearsal modes against the real decoder locally, D2 and D3 prove the YAML, and the rehearsal follows the merge immediately, with one exception: a ruleset on a private repository owned by a personal account is enforced only with GitHub Pro, so on Free the repository goes public before the ruleset and the rehearsals, and D5 waits for that.

## Review Focus

1. A recording whose stream has a reply but no `result` line (the CLI was killed mid-run): the nightly's check passes, the decode test fails with "no result", and the classification must be `decoder` with that message, not kind 1 → pinned in D1 (`test_a_reply_without_a_result_is_decoder_drift_when_the_decode_test_failed`).
2. A failure message with XML-escaped characters (Kotlin's `[assistant, user]` lists and quoted file names) must come out as the test wrote it, not as `&quot;` → pinned in D1 (`test_failure_messages_are_unescaped`).
3. The results directory holds XML of every test class when the whole `test` task ran; a failure in another class must not be read as drift, even under a contract test's name → pinned in D1 (`test_failures_of_other_test_classes_are_ignored` fails `DECODE_TEST` under `CardsTest`, so it is red when `failures()` ignores `classname`).
4. `make_drift.py` given `src/test/resources/fixtures/../fixtures/x`, or a symlink into the fixtures directory, must refuse as it refuses the plain path → pinned in D1 (`test_writing_under_the_fixtures_directory_is_refused` covers both spellings).
5. A reader failure that says "no transcripts in" (the stream is fine, the transcript copy never happened) has nothing for the skill to promote; it must be `unknown`, not `reader`, so the agent is not started → pinned in D1 (`test_a_reader_failure_for_missing_transcripts_is_unknown`).
6. A stream whose `result` holds the reply text but has no `assistant` line is a renamed core type, the drift the loop exists for, not "the CLI did not answer" → pinned in D1 (`test_a_result_with_text_but_no_assistant_line_is_decoder_drift`) and in D3 (the nightly's check step reds it with its own message).
7. `make_drift.py <mode> <dir> --from <dir>` mutates a recording in place: every source file is read before any output is written, and the result is byte-identical to the fixture-sourced run → pinned in D1 (`test_from_mutates_a_recording_in_place`).
8. A kind-1 red with no artifact must reach the report step: `build/drift` is created by the fetch step before anything is written there, and Gradle never runs for kind 1 → pinned in D2 (`test_the_fetch_the_precheck_and_the_allowlist_step_fail_loudly`).

---

### Task D1: The classification, the rehearsal mutation and the skill

**Files:**
- Create: `scripts/classify_drift.py`
- Create: `scripts/make_drift.py`
- Create: `.claude/skills/fix-contract-drift/SKILL.md`
- Test: `scripts/test_classify_drift.py`, `scripts/test_make_drift.py`

**Interfaces:**
- Consumes: `scripts/redact_fixture.py` (unchanged; the skill runs it), `src/test/resources/fixtures/history-reply.ndjson` (12 lines, `system/init` on line 2 with `"model":"claude-sonnet-5-5"`, `"claude_code_version":"2.1.301"`, `"apiKeySource":"none"`) and `src/test/resources/fixtures/transcripts/history-reply.jsonl` (23 lines, one `"type":"user"` line, line 3, with `message.content` a string), `src/test/kotlin/dev/daarkolabs/tandem/protocol/ContractTest.kt` (test names `live transcripts decode with every core type recognised` and `live transcripts load with the reader`, assertion messages `<file>: [types]`, `<file>: no system/init`, `<file>: no result`, `<file>: no user entry`, `<file>: no assistant entry`, `no transcripts in <dir>`).
- Produces:
  ```python
  # scripts/classify_drift.py            Usage: classify_drift.py <recording-dir> <test-results-dir>
  STREAM = "contract-hello.ndjson"
  DECODE_TEST = "live transcripts decode with every core type recognised"
  READER_TEST = "live transcripts load with the reader"
  DETAIL_LIMIT = 400
  def read_stream(path: str) -> list[dict]           # JSON objects per line; non-JSON and non-object lines skipped
  def failures(results_dir: str) -> dict[str, str]   # testcase name -> failure message, ContractTest only; {} when the dir is missing
  def classify(recording: str, results_dir: str) -> dict   # {"kind", "cli_version", "detail", "files"}
  def main(argv: list[str]) -> int                    # prints json.dumps(classify(...)) and returns 0; returns 2 on a usage error (usage on stderr)

  # scripts/make_drift.py                Usage: make_drift.py <mode> <out-dir> [--from <recording-dir>]
  FIXTURES = <repo>/src/test/resources/fixtures      # from os.path.dirname(os.path.abspath(__file__)): `python3 -I scripts/make_drift.py` gives a relative __file__
  SOURCE = "history-reply"
  MODES = ("init-model-key", "transcript-content-key")
  MARKER = "rehearsal"
  def rename_key(obj: dict, old: str, new: str) -> dict           # same key order
  def mutate_stream(lines: list[str], mode: str) -> list[str]
  def mutate_transcript(lines: list[str], mode: str) -> list[str]
  def main(mode: str, out_dir: str, source: str | None = None) -> None   # sys.exit(...) on an unknown mode, an out_dir under FIXTURES, or a source missing one of its three files
  ```
  Output layout of `make_drift.py`: `<out>/contract-hello.ndjson`, `<out>/transcripts/contract-hello.jsonl`, `<out>/transcripts/session-id.jsonl` and the marker `<out>/rehearsal` (one line: the mode), which the fixer reads as `rehearsal=true`. Without `--from` the sources are the `history-reply` fixture pair (the twin written as both transcripts); with `--from <dir>` they are that recording's three files, each mutated from its own lines, and `<dir>` may be `<out>` itself: all three are read before any file is written (the nightly's rehearsal step runs `make_drift.py "$DRIFT" build/contract --from build/contract`).
  The skill is invoked as `/fix-contract-drift run=<url> recording=<dir> classification=<file> rehearsal=<true|false> date=<YYYY-MM-DD>`.

- [ ] **Step 1: Write the failing tests for `classify_drift.py`**

`scripts/test_classify_drift.py`, in the style of `test_copy_transcript.py` (a `TemporaryDirectory` per test, helpers). Helpers: `recording(self, stream: list | None, transcripts: tuple[str, ...] = ("contract-hello.jsonl", "session-id.jsonl")) -> str` creates `<dir>` (`self.root/recording`), writes `<dir>/contract-hello.ndjson` unless `stream is None` (one `json.dumps` per element; a `str` element is written verbatim) and an empty file per name under `<dir>/transcripts/`; `results(self, failing: dict[str, str], classname="dev.daarkolabs.tandem.protocol.ContractTest") -> str` writes `TEST-<classname>.xml` into the one directory `self.root/test-results` (created on first use, so two calls share it) with `xml.etree.ElementTree` (a `<testsuite>` with a `<testcase name=… classname=…>` for both contract tests and for every other key of `failing`, each failing one holding `<failure message=… type="java.lang.AssertionError">`), and returns that directory. `INIT = {"type": "system", "subtype": "init", "model": "claude-sonnet-5-5", "apiKeySource": "none", "claude_code_version": "2.1.302"}`, `REPLY = {"type": "assistant", "message": {"content": [{"type": "text", "text": "pong"}]}}`, `OK = {"type": "result", "subtype": "success", "is_error": False, "result": "pong"}`.

```python
def test_no_recording_is_the_cli_not_answering(self):
    out = classify_drift.classify(os.path.join(self.root, "missing"), self.results({}))
    self.assertEqual({"kind": "cli-did-not-answer", "cli_version": None,
                      "detail": "the run left no recording", "files": []}, out)

def test_a_recording_without_the_stream_is_the_cli_not_answering(self):   # transcripts only
    ...assertEqual("cli-did-not-answer", out["kind"]); assertEqual("the run left no recording", out["detail"])

def test_a_stream_without_init_is_the_cli_not_answering(self):
    out = classify_drift.classify(self.recording([REPLY, OK]), self.results({}))
    self.assertEqual(("cli-did-not-answer", None, "no system/init in contract-hello.ndjson"),
                     (out["kind"], out["cli_version"], out["detail"]))

def test_an_error_result_is_the_cli_not_answering_with_the_text_cut_to_400_characters(self):
    bad = {"type": "result", "is_error": True, "result": "x" * 500}
    out = classify_drift.classify(self.recording([INIT, REPLY, bad]), self.results({}))
    self.assertEqual("cli-did-not-answer", out["kind"])
    self.assertEqual("2.1.302", out["cli_version"])
    self.assertEqual("The CLI did not answer (apiKeySource='none'): " + "x" * 400, out["detail"])

def test_no_assistant_line_uses_the_errors_field(self):
    out = classify_drift.classify(self.recording([INIT, {"type": "result", "errors": ["boom"]}]), self.results({}))
    self.assertEqual("cli-did-not-answer", out["kind"])
    self.assertEqual("The CLI did not answer (apiKeySource='none'): [\"boom\"]", out["detail"])

def test_a_result_with_text_but_no_assistant_line_is_decoder_drift(self):
    out = classify_drift.classify(self.recording([INIT, OK]), self.results({}))        # the reply came under a type the decoder does not know
    self.assertEqual(("decoder", "2.1.302", "contract-hello.ndjson: no assistant line, result text present"), (out["kind"], out["cli_version"], out["detail"]))

def test_a_decode_failure_is_decoder_drift(self):
    message = "contract-hello.ndjson: [assistant]"
    out = classify_drift.classify(self.recording([INIT, REPLY, OK]), self.results({classify_drift.DECODE_TEST: message}))
    self.assertEqual({"kind": "decoder", "cli_version": "2.1.302", "detail": message,
                      "files": ["contract-hello.ndjson", "transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"]}, out)

def test_a_reader_failure_is_reader_drift(self):
    message = "contract-hello.jsonl: no user entry"
    out = classify_drift.classify(self.recording([INIT, REPLY, OK]), self.results({classify_drift.READER_TEST: message}))
    self.assertEqual(("reader", message), (out["kind"], out["detail"]))

def test_both_failures_are_decoder_drift_with_both_messages(self):
    out = classify_drift.classify(self.recording([INIT, REPLY, OK]),
                                  self.results({classify_drift.DECODE_TEST: "a: [user]", classify_drift.READER_TEST: "b: no user entry"}))
    self.assertEqual(("decoder", "a: [user]\nb: no user entry"), (out["kind"], out["detail"]))

def test_no_failure_is_unknown(self):
    out = classify_drift.classify(self.recording([INIT, REPLY, OK]), self.results({}))
    self.assertEqual(("unknown", "neither contract test failed"), (out["kind"], out["detail"]))

def test_missing_test_results_are_unknown(self):
    out = classify_drift.classify(self.recording([INIT, REPLY, OK]), os.path.join(self.root, "no-results"))
    self.assertEqual("unknown", out["kind"])
    self.assertEqual("no test results in " + os.path.join(self.root, "no-results"), out["detail"])

def test_lines_that_are_not_json_are_skipped(self):
    out = classify_drift.classify(self.recording(["not json", "[1]", INIT, REPLY, OK]), self.results({}))
    self.assertEqual("unknown", out["kind"])          # the stream itself is fine

def test_a_reply_without_a_result_is_decoder_drift_when_the_decode_test_failed(self):
    out = classify_drift.classify(self.recording([INIT, REPLY]), self.results({classify_drift.DECODE_TEST: "contract-hello.ndjson: no result"}))
    self.assertEqual(("decoder", "contract-hello.ndjson: no result"), (out["kind"], out["detail"]))

def test_failure_messages_are_unescaped(self):
    message = 'contract-hello.ndjson: ["<x>", null]'
    out = classify_drift.classify(self.recording([INIT, REPLY, OK]), self.results({classify_drift.DECODE_TEST: message}))
    self.assertEqual(message, out["detail"])

def test_failures_of_other_test_classes_are_ignored(self):
    results = self.results({})
    self.results({classify_drift.DECODE_TEST: "boom"}, classname="dev.daarkolabs.tandem.chat.CardsTest")   # same dir; a contract test's name under another class
    out = classify_drift.classify(self.recording([INIT, REPLY, OK]), results)
    self.assertEqual(("unknown", "neither contract test failed"), (out["kind"], out["detail"]))                   # red when failures() ignores classname

def test_a_reader_failure_for_missing_transcripts_is_unknown(self):
    out = classify_drift.classify(self.recording([INIT, REPLY, OK], transcripts=()),
                                  self.results({classify_drift.READER_TEST: "no transcripts in build/drift/recording/transcripts"}))
    self.assertEqual(("unknown", "no transcripts in build/drift/recording/transcripts"), (out["kind"], out["detail"]))

def test_main_prints_json_and_exits_zero(self):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = classify_drift.main([self.recording([INIT, REPLY, OK]), self.results({})])
    self.assertEqual(0, code)
    self.assertEqual("unknown", json.loads(out.getvalue())["kind"])

def test_wrong_arguments_exit_2(self):
    with contextlib.redirect_stderr(io.StringIO()):
        self.assertEqual(2, classify_drift.main(["only-one"]))
```

`test_a_recording_without_the_stream_is_the_cli_not_answering` calls `self.recording(None)`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree/scripts && python3 -m unittest test_classify_drift -v`
Expected: `ModuleNotFoundError: No module named 'classify_drift'`

- [ ] **Step 3: Implement `scripts/classify_drift.py`**

Module docstring in the style of `copy_transcript.py` (usage, the four kinds, the rules in order). `read_stream` mirrors the nightly's check (`json.loads` per line, skip on `ValueError` or non-dict). `failures` globs `TEST-*.xml` under `results_dir`, parses each with `xml.etree.ElementTree`, and keeps `testcase.get("name")` → `failure.get("message")` for test cases whose `classname` ends with `.ContractTest` (ElementTree unescapes attributes, which is what pins Review Focus 2). The stdlib parser is deliberate: the XML is Gradle's own output on the same machine, the scripts import only the standard library (`-I`), and Python 3.13's expat already limits entity expansion. `classify`, rules in this order:

1. `recording` is not a directory or has no `contract-hello.ndjson` → `cli-did-not-answer`, `"the run left no recording"`.
2. no line with `type == "system"` and `subtype == "init"` → `cli-did-not-answer`, `"no system/init in contract-hello.ndjson"`.
3. `result.get("is_error")` truthy → `cli-did-not-answer`, detail `f"The CLI did not answer (apiKeySource={init.get('apiKeySource')!r}): {text[:DETAIL_LIMIT]}"` where `text = result["result"] if isinstance(result.get("result"), str) else json.dumps(result.get("errors", result))` (the nightly's own expression; `result` is `{}` without a result line). Otherwise, when no `assistant` line exists: `decoder` with the detail `"contract-hello.ndjson: no assistant line, result text present"` when `result.get("result")` is a non-empty string (the reply came under a type the decoder does not know; the nightly's check step reds the run the same way, D3), else `cli-did-not-answer` with the detail above (an empty or missing result). A stream with an `assistant` line and no `result` line goes on to rule 4 (Review Focus 1).
4. `results_dir` is not a directory → `unknown`, `f"no test results in {results_dir}"`.
5. the decode test failed → `decoder`; detail is its message, or `decode + "\n" + reader` when the reader test failed too.
6. the reader test failed → `unknown` when its message starts with `"no transcripts in"`, else `reader` with the message.
7. otherwise `unknown`, `"neither contract test failed"`.

`cli_version` is `init.get("claude_code_version")` when it is a string, else `None`; `files` is the sorted list of regular files under the recording directory as `/`-separated relative paths (`[]` when there is no directory). `main(argv)`: two arguments or return 2 after printing `__doc__` to stderr; `print(json.dumps(classify(*argv)))`; `if __name__ == "__main__": sys.exit(main(sys.argv[1:]))`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree/scripts && python3 -m unittest test_classify_drift -v`
Expected: `Ran 18 tests` … `OK`

- [ ] **Step 5: Write the failing tests for `make_drift.py`**

`scripts/test_make_drift.py`. `setUp` creates a `TemporaryDirectory`; `self.out = os.path.join(self.root, "recording")`. Helpers `fixture(name) -> list[str]` (the committed file's lines via `readlines()`, newlines kept), `written(rel) -> list[str]` (the output file's lines) and `recording_from_fixture(dir) -> str` (copies `history-reply.ndjson` to `<dir>/contract-hello.ndjson` and the twin to `<dir>/transcripts/contract-hello.jsonl` and `<dir>/transcripts/session-id.jsonl` with `shutil.copy`, returns `dir`). `UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")`.

```python
def test_init_model_key_renames_only_the_init_line_s_model_key(self):
    make_drift.main("init-model-key", self.out)
    source, out = self.fixture("history-reply.ndjson"), self.written("contract-hello.ndjson")
    self.assertEqual(len(source), len(out))
    self.assertEqual(source[:1] + source[2:], out[:1] + out[2:])              # every other line byte-identical
    before, after = json.loads(source[1]), json.loads(out[1])
    self.assertEqual("claude-sonnet-5-5", after["model_id"])
    self.assertNotIn("model", after)
    self.assertEqual([("model_id" if k == "model" else k) for k in before], list(after))
    twin = self.fixture("transcripts/history-reply.jsonl")
    self.assertEqual(twin, self.written("transcripts/contract-hello.jsonl"))
    self.assertEqual(twin, self.written("transcripts/session-id.jsonl"))

def test_transcript_content_key_renames_content_on_every_user_line_of_both_transcripts(self):
    make_drift.main("transcript-content-key", self.out)
    self.assertEqual(self.fixture("history-reply.ndjson"), self.written("contract-hello.ndjson"))
    source = self.fixture("transcripts/history-reply.jsonl")
    for rel in ("transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"):
        out = self.written(rel)
        self.assertEqual(len(source), len(out))
        renamed = 0
        for a, b in zip(source, out):
            if json.loads(a).get("type") == "user":
                before, after = json.loads(a), json.loads(b)
                self.assertEqual(before["message"]["content"], after["message"]["blocks"])
                self.assertNotIn("content", after["message"])
                renamed += 1
            else:
                self.assertEqual(a, b)
        self.assertEqual(1, renamed)

def test_the_output_layout_and_the_marker(self):
    make_drift.main("init-model-key", self.out)
    files = sorted(os.path.relpath(os.path.join(d, f), self.out) for d, _, fs in os.walk(self.out) for f in fs)
    self.assertEqual(["contract-hello.ndjson", "rehearsal", "transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"], files)
    self.assertEqual(["init-model-key\n"], self.written("rehearsal"))

def test_from_mutates_a_recording_in_place(self):
    for mode in make_drift.MODES:
        recording = self.recording_from_fixture(os.path.join(self.root, mode))
        make_drift.main(mode, recording, source=recording)                      # in place: everything read, then written
        make_drift.main(mode, os.path.join(self.out, mode))
        for rel in ("contract-hello.ndjson", "transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl", "rehearsal"):
            with open(os.path.join(recording, rel), "rb") as a, open(os.path.join(self.out, mode, rel), "rb") as b:
                self.assertEqual(a.read(), b.read(), rel)

def test_from_refuses_an_incomplete_recording_before_writing(self):
    recording = os.path.join(self.root, "partial"); os.makedirs(recording)
    shutil.copy(os.path.join(make_drift.FIXTURES, "history-reply.ndjson"), os.path.join(recording, "contract-hello.ndjson"))
    with self.assertRaises(SystemExit) as raised:
        make_drift.main("init-model-key", self.out, source=recording)
    self.assertIn("transcripts/contract-hello.jsonl", str(raised.exception))
    self.assertFalse(os.path.exists(self.out))

def test_the_placeholders_survive(self):
    for mode in make_drift.MODES:
        make_drift.main(mode, os.path.join(self.out, mode))
        for rel in ("contract-hello.ndjson", "transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"):
            with open(os.path.join(self.out, mode, rel), encoding="utf-8") as f:
                text = f.read()
            self.assertIn("<session_id-2>", text)
            self.assertIsNone(UUID_RE.search(text))

def test_writing_under_the_fixtures_directory_is_refused(self):
    link = os.path.join(self.root, "link"); os.symlink(make_drift.FIXTURES, link)
    for out in (os.path.join(make_drift.FIXTURES, "x"), os.path.join(make_drift.FIXTURES, "..", "fixtures", "x"), os.path.join(link, "x")):
        with self.assertRaises(SystemExit) as raised:
            make_drift.main("init-model-key", out)
        self.assertIn("refusing to write under", str(raised.exception))
        self.assertFalse(os.path.exists(out))

def test_an_unknown_mode_is_refused(self):
    with self.assertRaises(SystemExit) as raised:
        make_drift.main("rename-everything", self.out)
    self.assertIn("rename-everything", str(raised.exception))
    self.assertFalse(os.path.exists(self.out))

def test_from_finds_the_init_line_by_content(self):
    recording = os.path.join(self.root, "live"); os.makedirs(os.path.join(recording, "transcripts"))
    shutil.copy(os.path.join(make_drift.FIXTURES, "plain-reply.ndjson"), os.path.join(recording, "contract-hello.ndjson"))   # system/init on line 4
    for rel in ("transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"):
        shutil.copy(os.path.join(make_drift.FIXTURES, "transcripts", "history-reply.jsonl"), os.path.join(recording, rel))
    source = self.fixture("plain-reply.ndjson")
    make_drift.main("init-model-key", recording, source=recording)
    with open(os.path.join(recording, "contract-hello.ndjson"), encoding="utf-8") as f:
        out = f.readlines()
    self.assertEqual(len(source), len(out))
    self.assertEqual(source[:3] + source[4:], out[:3] + out[4:])              # lines 1-3 and 5 onwards byte-identical
    before, after = json.loads(source[3]), json.loads(out[3])
    self.assertEqual(("system", "init"), (after["type"], after["subtype"]))
    self.assertEqual(before["model"], after["model_id"])
    self.assertNotIn("model", after)
```

`test_from_finds_the_init_line_by_content` is red for an implementation that mutates line 2 (where `history-reply` keeps its `init`): `plain-reply` has `active_goal`, `autocompact_state` and `system/ui_invalidate` before it, and a live recording's count of such lines varies by CLI version.

- [ ] **Step 6: Run the tests to verify they fail**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree/scripts && python3 -m unittest test_make_drift -v`
Expected: `ModuleNotFoundError: No module named 'make_drift'`

- [ ] **Step 7: Implement `scripts/make_drift.py`**

Docstring: usage (`make_drift.py <mode> <out-dir> [--from <recording-dir>]`), the two modes and what each breaks (`init-model-key`: `system/init.model` → `model_id`, so `Ndjson` decodes the line to `Unknown` and the decode test fails with "no system/init"; `transcript-content-key`: every transcript `user` line's `message.content` → `message.blocks`, so the reader yields no user entry), the `--from` form (the nightly's rehearsal mutates its own `build/contract` in place), the marker `rehearsal` (the fixer reads it as `rehearsal=true`), and that it never writes under `src/test/resources/fixtures`. `FIXTURES` is computed from `os.path.dirname(os.path.abspath(__file__))` (as `test_workflows.py`'s `ROOT` is): `python3 -I scripts/make_drift.py` gives a relative `__file__`, and a relative `FIXTURES` would make the fixture reads depend on the caller's cwd. Both modes find their lines by content, never by index: `init-model-key` rewrites every line whose `type` is `system` and `subtype` is `init` (a live recording has `active_goal` and `autocompact_state` lines before it, their count varying by CLI version: `history-reply` has `init` on line 2, `plain-reply` on line 4, `permission` on line 8), `transcript-content-key` every line whose `type` is `user` with a `message.content`. A mutated line is re-serialized with `json.dumps(obj, ensure_ascii=False, separators=(",", ":"))` plus `"\n"` (the redactor's format); every other line is copied byte for byte. `main` checks `mode in MODES` (else `sys.exit(f"make_drift: unknown mode {mode!r}; one of {', '.join(MODES)}")`), then refuses when `os.path.realpath(out_dir)` equals or starts with `os.path.realpath(FIXTURES) + os.sep` (`sys.exit(f"make_drift: refusing to write under the fixtures directory: {out_dir}")`), then reads every source into memory before writing anything: without `source`, the two fixture files (the twin serves as both transcripts); with `source`, `<source>/contract-hello.ndjson`, `<source>/transcripts/contract-hello.jsonl` and `<source>/transcripts/session-id.jsonl`, each mutated from its own lines (`sys.exit(f"make_drift: no {rel} in {source}")` when one is missing, before any write, so an in-place run never half-mutates). Then it writes the three outputs and `<out>/rehearsal` holding `mode + "\n"` (`os.makedirs(..., exist_ok=True)`) and prints `wrote <path>` per file to stderr. Argument parsing under `if __name__ == "__main__":`: `sys.argv[1:]` is `[mode, out]` or `[mode, out, "--from", dir]`; anything else prints `__doc__` to stderr and exits 2.

- [ ] **Step 8: Run the tests to verify they pass, with the skill's own command**

Run, from the worktree root, the spelling the skill prescribes (no `cd`: it is not on the fixer's tool allowlist): `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree && python3 -m unittest discover -s scripts -t scripts -v`
Expected: every test of `test_classify_drift` (18), `test_make_drift` (9), `test_copy_transcript`, `test_drive_cli` and `test_redact_fixture` reports `ok`; the summary ends `OK`. This proves the command the skill's section 7 and the PR body's "Verified" bullet name; it is the same set `cd scripts && python3 -m unittest` runs.

- [ ] **Step 9: Prove both rehearsal modes against the real decoder and reader**

Run, from the worktree root (the first Gradle run may take minutes; run it in the background if the harness times out):
```bash
python3 -I scripts/make_drift.py init-model-key build/drift/recording
./gradlew test --tests '*ContractTest*' -Dtandem.contractDir="$PWD/build/drift/recording" --rerun; echo "gradle exit $?"
python3 -I scripts/classify_drift.py build/drift/recording build/test-results/test
rm -rf build/drift/recording
python3 -I scripts/make_drift.py transcript-content-key build/drift/recording
./gradlew test --tests '*ContractTest*' -Dtandem.contractDir="$PWD/build/drift/recording" --rerun; echo "gradle exit $?"
python3 -I scripts/classify_drift.py build/drift/recording build/test-results/test
```
Expected: the first Gradle run fails only `live transcripts decode with every core type recognised` and the classification prints `{"kind": "decoder", "cli_version": "2.1.301", "detail": "contract-hello.ndjson: no system/init", "files": ["contract-hello.ndjson", "rehearsal", "transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"]}`; the second fails only `live transcripts load with the reader` and prints `"kind": "reader"` with a detail ending in `: no user entry` (naming `contract-hello.jsonl` or `session-id.jsonl`: `ContractTest` stops at the first failing transcript in directory order, which is why the skill copies both). If a mode does not go red this way, the mutation, not the decoder, is wrong: fix `make_drift.py` and repeat. `./gradlew test` with no property still skips `ContractTest` (`assumeTrue`).

- [ ] **Step 10: Write the skill `.claude/skills/fix-contract-drift/SKILL.md`**

Frontmatter and body below; the body is the spec's procedure and the commands are exact. Write it for an agent that has not seen the spec. Every command in it is on the `allowed-tools` list (the action's `--allowedTools`, D2): in the workflow a denied call is a dead end, not a prompt.

````markdown
---
name: fix-contract-drift
description: "Turn a red nightly CLI contract run into a pull request carrying the recording as a fixture and the minimal decoder or reader change. Arguments: run=<url> recording=<dir> classification=<file> rehearsal=<true|false> date=<YYYY-MM-DD>."
allowed-tools: Read, Grep, Glob, Edit, Write, Bash(./gradlew check), Bash(./gradlew test:*), Bash(python3 -I scripts/classify_drift.py:*), Bash(python3 -I scripts/redact_fixture.py:*), Bash(python3 -m unittest discover -s scripts -t scripts), Bash(gh pr create:*), Bash(gh issue list:*), Bash(gh issue create:*), Bash(gh issue comment:*), Bash(git status:*), Bash(git diff:*), Bash(git log:*), Bash(git add:*), Bash(git commit:*), Bash(git switch:*), Bash(git clean:*), Bash(git push -u origin HEAD), Bash(cp:*), Bash(ls:*), Bash(cat:*), Bash(grep:*), Bash(date:*)
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
assertion message(s), both when both tests failed (decoder first). A `decoder` whose detail is `no assistant line,
result text present` comes with a green decode test: the reply arrived under a type `Ndjson` does not know (see 5).

## 2. Name the fixture and copy the recording
Name `drift-<date>`, or `rehearsal-<date>` when `rehearsal=true`. If `src/test/resources/fixtures/<name>.ndjson` exists
already, use `<name>-<run id>` for every file of this fix. Copy `<recording>/contract-hello.ndjson` to
`src/test/resources/fixtures/<name>.ndjson` and `<recording>/transcripts/contract-hello.jsonl` to
`src/test/resources/fixtures/transcripts/<name>.jsonl`. For reader drift, always copy
`<recording>/transcripts/session-id.jsonl` to `src/test/resources/fixtures/transcripts/<name>-session-id.jsonl` too,
whichever file the detail names: the test stops at the first failing transcript in directory order, and both carry
the new shape. Never overwrite an existing fixture: the committed ones pin recorded facts (`plain-reply` pins 2.1.300
and one "pong").

## 3. Run the redactor's safety net
`python3 -I scripts/redact_fixture.py <copy> <copy>` for each copied file (onto itself; redacted input changes
nothing). Any `unredacted id` or `<leaked-N>` on stderr is a stop: remove the copies with `git clean -f -- <copies>`,
open no PR, and comment on the open issue titled "CLI contract: the CLI did not answer" (`gh issue list --label
contract-drift --state open --json number,title`; `gh issue create` with that title and the label `contract-drift`
when none is open) naming the key path the redactor printed, never the value. Then
`grep -nE '@|[0-9a-f]{8}-[0-9a-f]{4}-|toolu_|msg_|fv-az|runner|<leaked-' <copies>`: an e-mail address, a UUID, an API
id, a hostname, an account name or a `<leaked-N>` placeholder (the nightly's redactor already caught a leak; your run
leaves the placeholder alone and prints nothing) is the same stop; a package name with `@` inside reply text is not.

## 4. Add the fixture to the tests
The stream joins `FixtureDecodeTest` and `RobustnessTest` by directory listing (`Fixtures.names()`), so the decode
tests are red now: that is the failing test the fix turns green. Add `<name>` (and `<name>-session-id`) to the list in
`transcript fixtures decode line by line without throwing` (`FixtureDecodeTest`), and a `TranscriptReaderTest` case
that loads `Fixtures.transcript("<name>")` and asserts one `ChatEntry.User` and one `ChatEntry.Assistant`. Add one test
stating the fact that drifted (the field or type now recognised, read from the new fixture) and a `RobustnessTest` case
for its malformed version (still `Unknown`, never a throw). Any other test that iterates `Fixtures.names()`
(`FixtureDecodeTest`'s unknown-types set, `ReducerTest`) may go red with the new fixture: update its expectation, never
remove the fixture.

## 5. Patch minimally
Decoder drift: `src/main/kotlin/dev/daarkolabs/tandem/protocol/Ndjson.kt` and `CliMessage.kt` only; a renamed field is
read under both names, a new required field is read optionally, a new type or subtype gets a typed message only when
Tandem needs it and `Unknown(raw)` otherwise. For the detail `no assistant line, result text present`, write first a
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
````

- [ ] **Step 11: Check the skill is well-formed**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree && python3 - <<'PY'
import re, yaml
text = open(".claude/skills/fix-contract-drift/SKILL.md", encoding="utf-8").read()
front = yaml.safe_load(re.match(r"---\n(.*?)\n---\n", text, re.S).group(1))
print(front["name"], "|", len(front["allowed-tools"].split(",")), "tools")
PY`
Expected: `fix-contract-drift | 27 tools`
- [ ] **Step 12: Commit**

```bash
git add scripts/classify_drift.py scripts/make_drift.py scripts/test_classify_drift.py scripts/test_make_drift.py .claude/skills/fix-contract-drift/SKILL.md
git commit -m "feat(ci): classify a red contract run, mutate a rehearsal recording, and the fix-contract-drift skill"
```

---

### Task D2: The fixer workflow `drift-fix.yml`

**Files:**
- Create: `.github/workflows/drift-fix.yml`
- Create: `.github/CODEOWNERS` (one line, `* @daarko10`: the ruleset's code-owner review binds only owned files)
- Test: `scripts/test_workflows.py`

**Interfaces:**
- Consumes: `scripts/classify_drift.py` and `scripts/make_drift.py` (D1, command lines as in their usage), the skill name and argument string (D1), `anthropics/claude-code-action@v1` inputs `prompt`, `claude_args`, `settings`, `anthropic_federation_rule_id`, `anthropic_organization_id`, `anthropic_service_account_id`, `anthropic_workspace_id` (verified against the action's `action.yml` at tag `v1`; the action exposes `conclusion` and `github_token` outputs and revokes the token after the run, so later steps use `github.token`).
- Produces: the workflow as below; `scripts/test_workflows.py` with `load(path) -> dict` (PyYAML; note that PyYAML reads the `on` key as the boolean `True`) and `allowed_tools(text) -> set[str]`, which D3 extends.

- [ ] **Step 1: Write the failing workflow tests**

`scripts/test_workflows.py`, guarded by `yaml = importlib.import_module("yaml")` in a `try` (`yaml = None` on `ImportError`), followed at import time by `if yaml is None and os.environ.get("GITHUB_ACTIONS"): raise RuntimeError("PyYAML is required in CI")`, so the only tests pinning both workflows never skip silently in `build.yml`'s "Script tests" step, and `@unittest.skipUnless(yaml, "PyYAML is not installed")` on the class for a machine without it. Imports `json` as well (the `settings` input is JSON). `ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")`; `DRIFT = os.path.join(ROOT, ".github/workflows/drift-fix.yml")`; `SKILL = os.path.join(ROOT, ".claude/skills/fix-contract-drift/SKILL.md")`. Helpers: `load(path)` (`yaml.safe_load`), `steps(workflow, job)`, `step(workflow, job, name)`, `frontmatter(path)` (the YAML between the first two `---` lines), `tools(text)` (`{t.strip() for t in text.split(",")}` of the value after `--allowedTools`, quotes stripped).

```python
"""Tests for the drift loop's workflows and the skill they run. Run from scripts/: python3 -m unittest

They read .github/workflows/drift-fix.yml (and cli-contract.yml) with PyYAML, which reads the `on` key as the boolean
True. Without PyYAML they skip, except under GITHUB_ACTIONS, where a missing PyYAML is an error: these are the only
tests pinning the workflows, and build.yml's "Script tests" step must never skip them silently.

The outcome tests (StepOutcomeTest) run a step's `run:` block under bash as GitHub does (-e -o pipefail) with a stub
`gh` first on PATH that answers from canned output and, on an HTTP error, prints the error body on stdout and exits 1
as gh 2.89 does. They need bash 4 (mapfile) and jq; without them they skip, except under GITHUB_ACTIONS on Linux.
"""
import importlib
import json
import os
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest

try:
    yaml = importlib.import_module("yaml")
except ImportError:
    yaml = None

if yaml is None and os.environ.get("GITHUB_ACTIONS"):
    raise RuntimeError("PyYAML is required in CI")


def bash_can_run_steps() -> bool:
    if not shutil.which("bash") or not shutil.which("jq"):
        return False
    major = subprocess.run(["bash", "-c", 'echo "${BASH_VERSINFO[0]}"'], capture_output=True, text=True).stdout.strip()
    return major.isdigit() and int(major) >= 4


BASH = bash_can_run_steps()
if not BASH and os.environ.get("GITHUB_ACTIONS") and os.environ.get("RUNNER_OS") == "Linux":
    raise RuntimeError("bash 4 and jq are required in CI: the step outcome tests must not skip")

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
DRIFT = os.path.join(ROOT, ".github/workflows/drift-fix.yml")
SKILL = os.path.join(ROOT, ".claude/skills/fix-contract-drift/SKILL.md")
DENY = {"Edit(.github/**)", "Write(.github/**)", "Edit(build.gradle.kts)", "Write(build.gradle.kts)", "Edit(settings.gradle.kts)",
        "Write(settings.gradle.kts)", "Edit(gradle.properties)", "Write(gradle.properties)", "Edit(gradle/**)", "Write(gradle/**)",
        "Edit(.git/**)", "Write(.git/**)", "Edit(scripts/**)", "Write(scripts/**)", "Edit(.claude/**)", "Write(.claude/**)",
        "Edit(CLAUDE.md)", "Write(CLAUDE.md)", "Bash(claude:*)", "Bash(./gradlew check -x:*)", "Bash(./gradlew check --exclude-task:*)",
        "Bash(./gradlew check *-*)", "Bash(./gradlew test *check*)", "Bash(./gradlew compileKotlin:*)",
        "Bash(./gradlew compileTestKotlin:*)", "Bash(./gradlew buildPlugin:*)", "Bash(./gradlew verifyPlugin:*)", "Bash(ktlint:*)"}
ALLOWLIST = "'^(src/main/kotlin/dev/daarkolabs/tandem/protocol/|src/main/kotlin/dev/daarkolabs/tandem/session/transcript/|src/test/|docs/protocol\\.md$)'"
GATE_IF = "steps.classify.outputs.kind == 'decoder' || steps.classify.outputs.kind == 'reader'"
REPORT_IF = "steps.precheck.outputs.kind == 'cli-did-not-answer' || steps.classify.outputs.kind == 'cli-did-not-answer' || steps.classify.outputs.kind == 'unknown'"


def load(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def steps(workflow: dict, job: str) -> list[dict]:
    return workflow["jobs"][job]["steps"]


def step(workflow: dict, job: str, name: str) -> dict:
    found = [s for s in steps(workflow, job) if s.get("name") == name]
    if len(found) != 1:
        raise AssertionError(f"{len(found)} steps named {name!r} in job {job!r}")
    return found[0]


def frontmatter(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    marks = [i for i, line in enumerate(lines) if line.strip() == "---"]
    if len(marks) < 2:
        raise AssertionError(f"no frontmatter in {path}")
    return yaml.safe_load("\n".join(lines[marks[0] + 1:marks[1]]))


def allowed_tools(text: str) -> set[str]:
    """The rules after --allowedTools in an action's claude_args, as a set."""
    rest = text.split("--allowedTools", 1)[1].strip().splitlines()[0].strip()
    if rest[:1] in ("'", '"') and rest[-1:] == rest[:1]:
        rest = rest[1:-1]
    return {t.strip() for t in rest.split(",")}


tools = allowed_tools


def stub_gh(answers: list[tuple[str, str, int]]) -> str:
    """A bash `gh` that logs each call's argument line ("$*") to $GH_STUB_LOG and answers the first entry whose prefix
    matches it with that stdout and exit code; an unmatched call fails with status 2, so a step never passes on a call
    the test did not expect."""
    lines = ["#!/usr/bin/env bash", 'printf "%s\\n" "$*" >> "$GH_STUB_LOG"', 'case "$*" in']
    for prefix, stdout, code in answers:
        lines.append("  '" + prefix.replace("'", "'\\''") + f"'*) printf '%s' {shlex.quote(stdout)}; exit {code};;")
    lines += ['  *) echo "stub gh: unexpected call: $*" >&2; exit 2;;', "esac", ""]
    return "\n".join(lines)


def run_step(wf: dict, job: str, name: str, env: dict, gh: list[tuple[str, str, int]], files: dict | None = None) -> tuple[int, str, str, str]:
    """Runs the step's `run:` under bash as GitHub does, in an empty working directory (plus `files`), with the stub gh
    first on PATH, GITHUB_OUTPUT in a temporary file and the step's env from `env`, which must name every key the step
    declares. Returns (exit code, stdout and stderr, GITHUB_OUTPUT's text, the stub's call log)."""
    s = step(wf, job, name)
    missing = set(s.get("env", {})) - set(env)
    if missing:
        raise AssertionError(f"the test must supply the step's env: {sorted(missing)}")
    with tempfile.TemporaryDirectory() as tmp:
        bindir, work = os.path.join(tmp, "bin"), os.path.join(tmp, "work")
        os.mkdir(bindir)
        os.mkdir(work)
        script, output, log = os.path.join(tmp, "step.sh"), os.path.join(tmp, "output"), os.path.join(tmp, "calls")
        with open(script, "w", encoding="utf-8") as f:
            f.write(s["run"])
        with open(os.path.join(bindir, "gh"), "w", encoding="utf-8") as f:
            f.write(stub_gh(gh))
        os.chmod(os.path.join(bindir, "gh"), 0o755)
        open(output, "w").close()
        open(log, "w").close()
        for path, text in (files or {}).items():
            os.makedirs(os.path.dirname(os.path.join(work, path)), exist_ok=True)
            with open(os.path.join(work, path), "w", encoding="utf-8") as f:
                f.write(text)
        full = {"PATH": bindir + os.pathsep + os.environ.get("PATH", ""), "HOME": tmp, "GITHUB_OUTPUT": output, "GH_STUB_LOG": log,
                "GITHUB_REPOSITORY": "daarko10/tandem", "GITHUB_SERVER_URL": "https://github.com", "GITHUB_RUN_ID": "99", **env}
        proc = subprocess.run(["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", script], cwd=work, env=full,
                              capture_output=True, text=True, timeout=60)
        with open(output, encoding="utf-8") as f:
            out = f.read()
        with open(log, encoding="utf-8") as f:
            calls = f.read()
        return proc.returncode, proc.stdout + proc.stderr, out, calls


@unittest.skipUnless(yaml, "PyYAML is not installed")
class DriftFixWorkflowTest(unittest.TestCase):
    def test_the_fixer_listens_to_the_nightly_only(self):
        on = load(DRIFT)[True]
        self.assertEqual({"workflows": ["CLI contract"], "types": ["completed"]}, on["workflow_run"])
        inputs = on["workflow_dispatch"]["inputs"]
        self.assertEqual(("boolean", False), (inputs["rehearse"]["type"], inputs["rehearse"]["default"]))
        self.assertEqual("init-model-key", inputs["mode"]["default"])
        self.assertIn("run_id", inputs)
        self.assertEqual(["workflow_run", "workflow_dispatch"], list(on))

    def test_permissions_concurrency_and_guards(self):
        wf = load(DRIFT)
        self.assertEqual({"id-token": "write", "contents": "read", "pull-requests": "read", "issues": "write", "actions": "read"}, wf["permissions"])   # the fix branch is pushed with the App token, never the workflow token, which the agent's test code can read: no pull-request write
        gate = step(wf, "fix", "Skip when a fix is already waiting")
        self.assertEqual(GATE_IF, gate["if"])
        self.assertNotIn("gh pr comment", gate["run"])                                # a comment on a PR needs pull-requests: write even through the issues endpoint: the remind job's
        self.assertNotIn("/comments", gate["run"])
        self.assertIn("printf 'proceed=false\\nwaiting=%s\\n' \"$pr\" >> \"$GITHUB_OUTPUT\"", gate["run"])
        self.assertEqual([], [s.get("name") for s in steps(wf, "fix") if "gh pr comment" in s.get("run", "") or "/comments" in s.get("run", "")])   # nothing in the fix job needs pull-requests: write
        self.assertEqual({"group": "drift-fix", "cancel-in-progress": False}, wf["concurrency"])
        job = wf["jobs"]["fix"]
        self.assertEqual(90, job["timeout-minutes"])
        for clause in ("github.repository == 'daarko10/tandem'", "github.ref == 'refs/heads/main'", "workflow_run.conclusion == 'failure'", "workflow_run.head_branch == 'main'"):
            self.assertIn(clause, job["if"])
        self.assertEqual(False, step(wf, "fix", "Check out main")["with"]["persist-credentials"])

    def test_the_identifiers_are_the_nightly_s(self):
        self.assertEqual(load(os.path.join(ROOT, ".github/workflows/cli-contract.yml"))["env"], load(DRIFT)["env"])

    def test_the_agent_step(self):
        agent = step(load(DRIFT), "fix", "Fix the drift")
        self.assertEqual("anthropics/claude-code-action@v1", agent["uses"])
        self.assertEqual(75, agent["timeout-minutes"])
        for key in ("anthropic_federation_rule_id", "anthropic_organization_id", "anthropic_service_account_id", "anthropic_workspace_id"):
            self.assertEqual("${{ env." + key.upper() + " }}", agent["with"][key])
        self.assertNotIn("github_token", agent["with"])
        self.assertEqual("steps.gate.outputs.proceed == 'true' && steps.protect.outcome == 'success'", agent["if"])   # never on an unprotected main
        self.assertTrue(agent["with"]["prompt"].startswith("/fix-contract-drift run="))
        for part in ("recording=build/drift/recording", "classification=build/drift/classification.json", "rehearsal=", "date="):
            self.assertIn(part, agent["with"]["prompt"])
        self.assertIn("--model claude-opus-5-5", agent["with"]["claude_args"])
        self.assertIn("--max-turns 120", agent["with"]["claude_args"])

    def test_the_skill_and_the_action_allow_the_same_tools(self):
        front = frontmatter(SKILL)
        self.assertEqual("fix-contract-drift", front["name"])
        self.assertEqual({t.strip() for t in front["allowed-tools"].split(",")}, tools(step(load(DRIFT), "fix", "Fix the drift")["with"]["claude_args"]))

    def test_the_steps_run_in_the_spec_s_order(self):
        names = [s.get("name") for s in steps(load(DRIFT), "fix")]
        expected = ["Free disk space", "Check out main", None, None, "Ensure the labels exist", "Name the red run", "Fetch the recording",
                    "Mutate a recording for the rehearsal", "Classify before Gradle", "Reproduce the red", "Classify",
                    "Report a run the agent cannot fix", "Skip when a fix is already waiting", "Require a protected main", "Fix the drift"]
        self.assertEqual(expected, names)                                             # the allowlist step is the enforce job's, after this job
        wf = load(DRIFT)
        self.assertEqual(["Enforce the edit allowlist"], [s.get("name") for s in steps(wf, "enforce")])
        self.assertEqual(["Comment on the waiting pull request"], [s.get("name") for s in steps(wf, "remind")])
        self.assertTrue(step(wf, "fix", "Reproduce the red")["continue-on-error"])
        self.assertIn("--rerun", step(wf, "fix", "Reproduce the red")["run"])        # the build cache never replays a pass on another recording
        for name in ("Name the red run", "Fetch the recording", "Report a run the agent cannot fix", "Skip when a fix is already waiting",
                     "Require a protected main"):
            self.assertEqual("${{ github.token }}", step(wf, "fix", name)["env"]["GH_TOKEN"])
        self.assertEqual("${{ github.token }}", step(wf, "enforce", "Enforce the edit allowlist")["env"]["GH_TOKEN"])
        self.assertEqual("${{ github.token }}", step(wf, "remind", "Comment on the waiting pull request")["env"]["GH_TOKEN"])
        name_run = step(wf, "fix", "Name the red run")["run"]
        self.assertIn('.conclusion == "failure"', name_run)                           # a green or foreign run is refused before any spend
        self.assertIn('.head_branch == "main"', name_run)                             # so is a run of another branch, whose recording that branch produced
        for s in steps(wf, "fix") + steps(wf, "remind") + steps(wf, "enforce"):
            self.assertNotIn("${{", s.get("run", ""), s.get("name"))                 # inputs and outputs reach the shell through env only
        mutate = step(wf, "fix", "Mutate a recording for the rehearsal")
        self.assertIn("scripts/make_drift.py", mutate["run"])
        self.assertEqual("${{ inputs.mode }}", mutate["env"]["MODE"])                 # through env, never interpolated into the shell line
        self.assertNotIn("${{", mutate["run"])
        self.assertIn("gh run download", step(wf, "fix", "Fetch the recording")["run"])

    def test_the_fetch_the_precheck_and_the_allowlist_step_fail_loudly(self):
        wf = load(DRIFT)
        fetch = step(wf, "fix", "Fetch the recording")["run"]
        self.assertIn("mkdir -p build/drift", fetch)                                  # a kind-1 red with no artifact must reach the report step
        self.assertIn("set -euo pipefail", fetch)
        self.assertIn('index("contract-recording")', fetch)                           # presence first: null is kind 1
        self.assertIn('index("contract-recording") | tostring', fetch)                # gh's --jq prints null as an empty line, never "null"
        self.assertNotIn("|| echo", fetch)                                            # any other download failure fails the job
        precheck = step(wf, "fix", "Classify before Gradle")
        self.assertEqual("precheck", precheck["id"])
        self.assertIn("build/drift/precheck.json", precheck["run"])
        self.assertIn("build/drift/recording/rehearsal", precheck["run"])            # the marker makes the run a rehearsal
        for name in ("Reproduce the red", "Classify"):
            self.assertIn("steps.precheck.outputs.kind != 'cli-did-not-answer'", step(wf, "fix", name)["if"])
        report = step(wf, "fix", "Report a run the agent cannot fix")
        self.assertEqual(REPORT_IF, report["if"])
        self.assertIn("precheck.json", report["run"])
        enforce = step(wf, "enforce", "Enforce the edit allowlist")
        self.assertIn('if branch="$(gh api "repos/$GITHUB_REPOSITORY/branches/$BRANCH" --jq .name 2>/dev/null)"; then', enforce["run"])   # by exit status: gh prints the error body on stdout, so the old [ -n ] test was never empty
        self.assertIn("status=\"$(jq -r '.status // empty' <<<\"$branch\" 2>/dev/null || true)\"", enforce["run"])
        self.assertIn('if [ "$status" != "404" ]; then', enforce["run"])              # only a 404 is "no branch"; a 5xx or a rate limit is red without a verdict
        self.assertNotIn("|| true)\"\n            if [ -n", enforce["run"])
        self.assertNotIn("git ls-remote", enforce["run"])
        self.assertNotIn("gh pr close", enforce["run"])                               # an offending PR is commented on and left open: closing would re-arm the loop nightly
        self.assertIn("gh pr comment", enforce["run"])
        self.assertIn('files="$(gh api --paginate "repos/$GITHUB_REPOSITORY/pulls/$pr/files"', enforce["run"])   # a failed read fails the step; REST pages past 100 files
        self.assertIn("previous_filename", enforce["run"])                            # a rename from outside the allowlist names its old path
        self.assertNotIn("gh pr view", enforce["run"])
        self.assertIn(ALLOWLIST, enforce["run"])                                      # the edit allowlist, exactly
        self.assertNotIn("if", enforce)                                               # the job's if decides; see test_the_allowlist_is_enforced_in_its_own_job
        self.assertIn("pushed no branch and opened no PR", enforce["run"])
        self.assertIn("pushed $BRANCH but opened no PR", enforce["run"])
        self.assertEqual("${{ needs.fix.outputs.kind }}", enforce["env"]["KIND"])    # the fix job's outputs, not its steps: this is another job
        self.assertEqual("${{ needs.fix.outputs.agent }}", enforce["env"]["AGENT"])
        self.assertEqual("drift/${{ needs.fix.outputs.date }}-${{ needs.fix.outputs.id }}", enforce["env"]["BRANCH"])
        self.assertEqual("${{ needs.fix.outputs.url }}", enforce["env"]["RUN_URL"])
        self.assertIn("rehearsal=${{ steps.precheck.outputs.rehearsal }}", step(wf, "fix", "Fix the drift")["with"]["prompt"])

    def test_the_agent_never_starts_on_an_unprotected_main(self):
        wf = load(DRIFT)
        protect = step(wf, "fix", "Require a protected main")
        self.assertEqual("protect", protect["id"])
        self.assertEqual("steps.gate.outputs.proceed == 'true'", protect["if"])
        self.assertIn("set -euo pipefail", protect["run"])                            # a failed read fails the step, and the agent never starts
        self.assertIn('repos/$GITHUB_REPOSITORY/rules/branches/main?per_page=100"', protect["run"])
        self.assertIn('select(.type == "pull_request" and (.parameters.required_approving_review_count // 0) >= 1 and .parameters.require_code_owner_review == true and .parameters.require_last_push_approval == true)] | length', protect["run"])   # an App can approve a PR it did not author: a code owner's review of the last push is what it cannot give
        self.assertNotIn(".protected", protect["run"])                                # classic protected: true accepts a direct push when no option is ticked
        self.assertIn('if [[ "$rules" =~ ^[0-9]+$ ]] && [ "$rules" -ge 1 ]; then', protect["run"])   # fail closed: only a number >= 1 passes
        self.assertIn('gh api -H "Accept: application/vnd.github.raw+json" "repos/$GITHUB_REPOSITORY/contents/.github/CODEOWNERS?ref=main"', protect["run"])   # the code-owner review binds only owned files
        self.assertIn("grep -qE '^\\*[[:space:]]+@' <<<\"$owners\"", protect["run"])
        self.assertIn("exit 1", protect["run"].split("CODEOWNERS?ref=main", 1)[1].split("branches/$BRANCH", 1)[0])
        self.assertLess(protect["run"].index("rules/branches/main"), protect["run"].index("CODEOWNERS?ref=main"))
        self.assertLess(protect["run"].index("CODEOWNERS?ref=main"), protect["run"].index("branches/$BRANCH"))
        self.assertIn("exit 1", protect["run"].split("else", 1)[1])                 # anything else ends the step red
        self.assertNotIn("-lt", protect["run"])                                       # a non-number made [ -lt ] false and passed the step
        self.assertEqual("drift/${{ steps.run.outputs.date }}-${{ steps.run.outputs.id }}", protect["env"]["BRANCH"])
        self.assertIn('if existing="$(gh api "repos/$GITHUB_REPOSITORY/branches/$BRANCH" --jq .name 2>/dev/null)"; then', protect["run"])   # a kept branch would reject the push after the spend
        self.assertIn("delete it to retry", protect["run"].split("branches/$BRANCH", 1)[1])
        self.assertIn("exit 1", protect["run"].split("delete it to retry", 1)[1])
        self.assertIn("status=\"$(jq -r '.status // empty' <<<\"$existing\" 2>/dev/null || true)\"", protect["run"])   # only a 404 lets the agent start: a 5xx would be a push rejected after the spend
        self.assertIn('if [ "$status" != "404" ]; then', protect["run"])
        self.assertLess(protect["run"].index("rules/branches/main"), protect["run"].index("branches/$BRANCH"))   # the ruleset first: it is the loop's precondition

    def test_the_allowlist_is_enforced_in_its_own_job(self):
        wf = load(DRIFT)
        self.assertEqual(["fix", "remind", "enforce"], list(wf["jobs"]))
        enforce = wf["jobs"]["enforce"]
        self.assertEqual("fix", enforce["needs"])                                      # a fresh runner: code the agent ran in fix cannot reach it
        self.assertEqual("always() && needs.fix.outputs.proceed == 'true' && needs.fix.outputs.protected == 'success'", enforce["if"])
        self.assertEqual({"contents": "read", "pull-requests": "write", "issues": "write"}, enforce["permissions"])   # no id-token, no actions
        self.assertEqual(10, enforce["timeout-minutes"])
        self.assertEqual([], [s for s in enforce["steps"] if "uses" in s])            # no checkout, no action: nothing the agent could have changed
        self.assertEqual("${{ github.repository }}", step(wf, "enforce", "Enforce the edit allowlist")["env"]["GH_REPO"])   # gh has no remote to infer it from
        self.assertEqual({"proceed": "${{ steps.gate.outputs.proceed }}", "waiting": "${{ steps.gate.outputs.waiting }}",
                          "protected": "${{ steps.protect.outcome }}",
                          "kind": "${{ steps.classify.outputs.kind }}", "agent": "${{ steps.agent.outputs.conclusion }}",
                          "id": "${{ steps.run.outputs.id }}", "url": "${{ steps.run.outputs.url }}", "date": "${{ steps.run.outputs.date }}"},
                         wf["jobs"]["fix"]["outputs"])                                # all but agent are written before the agent starts
        names = [s.get("name") for s in steps(wf, "fix")]
        self.assertLess(names.index("Skip when a fix is already waiting"), names.index("Fix the drift"))
        self.assertLess(names.index("Require a protected main"), names.index("Fix the drift"))
        self.assertLess(names.index("Classify"), names.index("Fix the drift"))
        enforce_run = step(wf, "enforce", "Enforce the edit allowlist")["run"]
        self.assertEqual("drift/${{ needs.fix.outputs.date }}-${{ needs.fix.outputs.id }}", step(wf, "enforce", "Enforce the edit allowlist")["env"]["BRANCH"])
        self.assertIn('open="$(gh pr list --state open --limit 100 --json number,headRefName,labels,isCrossRepository)"', enforce_run)   # a failed list fails the step
        self.assertIn('jq -r --arg branch "$BRANCH" \'.[] | select((.headRefName == $branch and .isCrossRepository == false) or any(.labels[]; .name == "contract-drift"))', enforce_run)   # this run's branch in this repository first, then the label: an unlabelled PR is checked, not misread as a stopped agent; a fork's PR named after the run is never labelled
        self.assertIn('gh api "repos/$GITHUB_REPOSITORY/issues/$pr/labels" -f "labels[]=contract-drift"', enforce_run)   # so the gate holds while it is open
        self.assertIn('for entry in "${prs[@]}"; do', enforce_run)                    # every PR is checked, never only the first
        self.assertIn('if [ "${#prs[@]}" -gt 1 ]; then', enforce_run)                  # a second PR is red: the agent opens one
        self.assertIn('exit "$red"', enforce_run)
        self.assertNotIn("][0].number", enforce_run)                               # not the first contract-drift PR only

    def test_the_reminder_is_its_own_job(self):
        wf = load(DRIFT)
        remind = wf["jobs"]["remind"]
        self.assertEqual("fix", remind["needs"])
        self.assertEqual("needs.fix.outputs.proceed == 'false'", remind["if"])        # the fix job ends green when the gate skipped: no always()
        self.assertEqual({"pull-requests": "write", "issues": "write"}, remind["permissions"])   # a comment on a PR needs pull-requests: write; issues: write alone is a 403
        self.assertEqual(5, remind["timeout-minutes"])
        self.assertEqual([], [s for s in remind["steps"] if "uses" in s])             # no checkout, no agent code
        comment = step(wf, "remind", "Comment on the waiting pull request")
        self.assertEqual({"GH_TOKEN": "${{ github.token }}", "GH_REPO": "${{ github.repository }}", "WAITING": "${{ needs.fix.outputs.waiting }}",
                          "RUN_URL": "${{ needs.fix.outputs.url }}", "DATE": "${{ needs.fix.outputs.date }}"}, comment["env"])
        self.assertIn('gh pr comment "$WAITING" --body "The nightly of $DATE is still red: $RUN_URL.', comment["run"])
        self.assertIn('[[ "$WAITING" =~ ^[0-9]+$ ]] || {', comment["run"])

    def test_main_s_codeowners_owns_every_file(self):
        with open(os.path.join(ROOT, ".github/CODEOWNERS"), encoding="utf-8") as f:
            lines = f.read().splitlines()
        self.assertTrue(any(re.fullmatch(r"\*\s+@\S+", line.strip()) for line in lines), lines)   # the ruleset's code-owner review binds only owned files

    def test_a_fork_s_run_never_reaches_the_agent(self):
        wf = load(DRIFT)
        for clause in ("github.event.workflow_run.head_repository.full_name == github.repository",
                       "github.event.workflow_run.event == 'schedule'", "github.event.workflow_run.event == 'workflow_dispatch'"):
            self.assertIn(clause, wf["jobs"]["fix"]["if"])                            # a fork names its own workflow "CLI contract" and branch main
        name_run = step(wf, "fix", "Name the red run")["run"]
        self.assertIn('view="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$id")"', name_run)   # the REST run names the head repository and the event
        self.assertNotIn("gh run view", name_run)
        for check in ('.name == "CLI contract"', '.head_repository.full_name == $repo', '(.event == "schedule" or .event == "workflow_dispatch")',
                      "jq -e", "jq -r .html_url", ".created_at[0:10]"):
            self.assertIn(check, name_run)                                            # a run_id passed by dispatch is held to the same rule
        self.assertIn('[[ "$id" =~ ^[0-9]+$ ]] || {', name_run)                        # no newline reaches GITHUB_OUTPUT or the branch name
        self.assertLess(name_run.index("=~ ^[0-9]+$"), name_run.index("gh api"))
        self.assertIn("jq -c '[.name, .conclusion, .head_branch, .head_repository.full_name, .event]'", name_run)   # one line: no field starts a workflow command

    def test_the_gradle_cache_is_read_only(self):
        gradle = [s for s in steps(load(DRIFT), "fix") if s.get("uses", "").startswith("gradle/actions/setup-gradle@")]
        self.assertEqual(1, len(gradle))
        self.assertEqual({"cache-read-only": True}, gradle[0]["with"])                # the agent's test code runs here; main's cache must not carry it

    def test_the_only_push_is_exact_and_the_broad_rules_are_gone(self):
        allowed = tools(step(load(DRIFT), "fix", "Fix the drift")["with"]["claude_args"])
        self.assertIn("Bash(git push -u origin HEAD)", allowed)                        # a :* rule matches prefix + space; drift/:* never matched the push
        self.assertEqual([], [t for t in allowed if t.startswith("Bash(git push") and t.endswith(":*)")])
        for broad in ("Bash(python3:*)", "Bash(./gradlew:*)", "Bash(gh pr:*)", "Bash(gh issue:*)", "Bash(git:*)"):
            self.assertNotIn(broad, allowed)
        for exact in ("Bash(./gradlew check)", "Bash(python3 -m unittest discover -s scripts -t scripts)"):
            self.assertIn(exact, allowed)

    def test_the_agent_cannot_edit_the_build_or_start_a_second_cli(self):
        agent = step(load(DRIFT), "fix", "Fix the drift")
        self.assertEqual("agent", agent["id"])
        deny = json.loads(agent["with"]["settings"])["permissions"]["deny"]
        self.assertEqual(DENY, set(deny))                                             # the spec's list, exactly: nothing dropped, nothing unexplained
        self.assertEqual(len(DENY), len(deny))
        self.assertEqual({"BASH_DEFAULT_TIMEOUT_MS": "900000", "BASH_MAX_TIMEOUT_MS": "1800000"}, json.loads(agent["with"]["settings"])["env"])   # ./gradlew check outlives the Bash tool's two-minute default


BRANCH = "drift/2026-10-22-7"
RUN_URL = "https://github.com/daarko10/tandem/actions/runs/7"
NOT_FOUND = '{"message":"Branch not found","documentation_url":"https://docs.github.com/rest/branches/branches#get-a-branch","status":"404"}'
SERVER_ERROR = '{"message":"Server Error","status":"502"}'
PR_LIST = "pr list --state open --limit 100 --json number,headRefName,labels,isCrossRepository"
BRANCH_READ = f"api repos/daarko10/tandem/branches/{BRANCH} --jq .name"
ISSUE_LIST = "issue list --label contract-drift --state open --search"
ISSUE_CREATE = "issue create --title CLI contract: the CLI did not answer --label contract-drift --body"
CLEAN_FILES = "src/main/kotlin/dev/daarkolabs/tandem/protocol/Ndjson.kt\nsrc/test/resources/fixtures/drift-2026-10-22.ndjson\ndocs/protocol.md\n"
ENFORCE_ENV = {"GH_TOKEN": "stub", "GH_REPO": "daarko10/tandem", "BRANCH": BRANCH, "RUN_URL": RUN_URL, "KIND": "decoder", "AGENT": "success"}
RUN_VIEW = {"html_url": RUN_URL, "created_at": "2026-10-21T03:17:09Z", "name": "CLI contract", "conclusion": "failure", "head_branch": "main",
            "head_repository": {"full_name": "daarko10/tandem"}, "event": "schedule"}
NAME_ENV = {"GH_TOKEN": "stub", "EVENT_RUN_ID": "7", "INPUT_RUN_ID": "", "REHEARSE": "false"}
RULES = "api repos/daarko10/tandem/rules/branches/main?per_page=100"
CODEOWNERS = "api -H Accept: application/vnd.github.raw+json repos/daarko10/tandem/contents/.github/CODEOWNERS?ref=main"


def pr(number: int, head: str = BRANCH, labelled: bool = False, fork: bool = False) -> dict:
    return {"number": number, "headRefName": head, "labels": [{"name": "contract-drift"}] if labelled else [], "isCrossRepository": fork}


@unittest.skipUnless(yaml and BASH, "PyYAML, bash 4 and jq are needed to run the steps")
class StepOutcomeTest(unittest.TestCase):
    """Each step's run: block under bash with a stub gh: what the step does, not what its text says."""

    def enforce(self, prs: list[dict], gh: list[tuple[str, str, int]]) -> tuple[int, str, str, str]:
        return run_step(load(DRIFT), "enforce", "Enforce the edit allowlist", ENFORCE_ENV, [(PR_LIST, json.dumps(prs), 0)] + gh)

    def test_enforce_with_no_pr_and_no_branch_is_red_and_says_so(self):
        rc, out, _, calls = self.enforce([], [(BRANCH_READ, NOT_FOUND, 1)])
        self.assertEqual(1, rc, out)
        self.assertIn("The classification was decoder but the agent pushed no branch and opened no PR (its step ended 'success')", out)
        self.assertNotIn("but opened no PR; reported", out)                           # the old [ -n "$(gh … || true)" ] read gh's error body as a branch name
        self.assertNotIn("issue", calls)

    def test_enforce_with_a_branch_and_no_pr_reports_the_stopped_agent(self):
        rc, out, _, calls = self.enforce([], [(BRANCH_READ, BRANCH + "\n", 0), (ISSUE_LIST, "", 0), (ISSUE_CREATE, "https://github.com/daarko10/tandem/issues/9\n", 0)])
        self.assertEqual(1, rc, out)
        self.assertIn(f"The agent pushed {BRANCH} but opened no PR; reported on the standing issue.", out)
        self.assertIn(f"{ISSUE_CREATE} The drift fix for {RUN_URL} pushed the branch {BRANCH} but opened no pull request", calls)
        self.assertIn("its step ended 'success'", calls)

    def test_enforce_comments_on_the_standing_issue_when_it_is_open(self):
        rc, out, _, calls = self.enforce([], [(BRANCH_READ, BRANCH + "\n", 0), (ISSUE_LIST, "3\n", 0), ("issue comment 3 --body", "", 0)])
        self.assertEqual(1, rc, out)
        self.assertIn(f"issue comment 3 --body The drift fix for {RUN_URL} pushed the branch", calls)
        self.assertNotIn("issue create", calls)

    def test_enforce_is_red_without_a_verdict_when_the_branch_read_fails(self):
        rc, out, _, calls = self.enforce([], [(BRANCH_READ, SERVER_ERROR, 1)])
        self.assertEqual(1, rc, out)
        self.assertIn(f"Reading the branch {BRANCH} failed (HTTP status '502')", out)
        self.assertNotIn("pushed no branch", out)
        self.assertNotIn("issue", calls)

    def test_enforce_passes_one_clean_pr_and_labels_it(self):
        rc, out, _, calls = self.enforce([pr(5)], [("api repos/daarko10/tandem/issues/5/labels -f labels[]=contract-drift", "{}", 0),
                                                  ("api --paginate repos/daarko10/tandem/pulls/5/files", CLEAN_FILES, 0)])
        self.assertEqual(0, rc, out)
        self.assertIn(f"PR #5 ({BRANCH}) stays within the allowlist", out)
        self.assertIn("PR #5 from drift/2026-10-22-7 carried no contract-drift label; added", out)
        self.assertIn("api repos/daarko10/tandem/issues/5/labels -f labels[]=contract-drift", calls)
        self.assertNotIn("pr comment", calls)

    def test_enforce_comments_on_an_offending_pr_leaves_it_open_and_is_red(self):
        files = CLEAN_FILES + "build.gradle.kts\nscripts/redact_fixture.py\n"                     # the last one a rename's old path
        rc, out, _, calls = self.enforce([pr(5, labelled=True)], [("api --paginate repos/daarko10/tandem/pulls/5/files", files, 0), ("pr comment 5 --body", "", 0)])
        self.assertEqual(1, rc, out)
        self.assertIn("PR #5 edits files outside the allowlist; commented and left open.", out)
        self.assertIn("pr comment 5 --body drift-fix.yml: this fix edits files outside the allowlist", calls)
        self.assertIn("\nbuild.gradle.kts\nscripts/redact_fixture.py", calls)
        self.assertNotIn("Ndjson.kt", calls.split("pr comment 5", 1)[1])            # only the offending paths are named
        self.assertNotIn("pr close", calls)
        self.assertNotIn("issues/5/labels", calls)                                    # already labelled

    def test_enforce_is_red_with_two_prs_even_when_both_are_clean(self):
        rc, out, _, calls = self.enforce([pr(5, labelled=True), pr(6, head="drift/2026-10-21-6", labelled=True)],
                                         [("api --paginate repos/daarko10/tandem/pulls/5/files", CLEAN_FILES, 0),
                                          ("api --paginate repos/daarko10/tandem/pulls/6/files", CLEAN_FILES, 0)])
        self.assertEqual(1, rc, out)
        self.assertIn("2 open pull requests belong to the loop (5 6); the agent opens one.", out)
        self.assertIn("PR #5", out)
        self.assertIn("PR #6", out)                                                   # every file list was checked before the verdict
        self.assertNotIn("pr comment", calls)

    def test_enforce_never_selects_or_labels_a_fork_s_pr_named_after_the_run(self):
        rc, out, _, calls = self.enforce([pr(5, fork=True)], [(BRANCH_READ, NOT_FOUND, 1)])
        self.assertEqual(1, rc, out)
        self.assertIn("pushed no branch and opened no PR", out)                        # the fork's PR is not the loop's
        self.assertNotIn("issues/5/labels", calls)
        self.assertNotIn("pulls/5/files", calls)

    def test_the_gate_skips_and_names_the_waiting_pr_without_commenting(self):
        rc, out, output, calls = run_step(load(DRIFT), "fix", "Skip when a fix is already waiting", {"GH_TOKEN": "stub"},
                                          [("pr list --label contract-drift --state open --json number --jq", "12\n", 0)])
        self.assertEqual(0, rc, out)
        self.assertEqual("proceed=false\nwaiting=12\n", output)
        self.assertEqual(1, len(calls.splitlines()))                                  # the comment is the remind job's: this token cannot post it

    def test_the_gate_proceeds_when_no_fix_is_waiting(self):
        rc, out, output, _ = run_step(load(DRIFT), "fix", "Skip when a fix is already waiting", {"GH_TOKEN": "stub"},
                                      [("pr list --label contract-drift --state open --json number --jq", "", 0)])
        self.assertEqual(0, rc, out)
        self.assertEqual("proceed=true\n", output)

    def test_the_gate_fails_closed_when_the_list_fails(self):
        rc, out, output, _ = run_step(load(DRIFT), "fix", "Skip when a fix is already waiting", {"GH_TOKEN": "stub"},
                                      [("pr list --label contract-drift --state open --json number --jq", SERVER_ERROR, 1)])
        self.assertEqual(1, rc, out)
        self.assertEqual("", output)

    def test_the_reminder_comments_on_the_waiting_pr(self):
        env = {"GH_TOKEN": "stub", "GH_REPO": "daarko10/tandem", "WAITING": "12", "RUN_URL": RUN_URL, "DATE": "2026-10-22"}
        rc, out, _, calls = run_step(load(DRIFT), "remind", "Comment on the waiting pull request", env, [("pr comment 12 --body", "", 0)])
        self.assertEqual(0, rc, out)
        self.assertEqual(f"pr comment 12 --body The nightly of 2026-10-22 is still red: {RUN_URL}. This pull request is still waiting; merge or close it to re-arm the loop.\n", calls)
        rc, out, _, calls = run_step(load(DRIFT), "remind", "Comment on the waiting pull request", {**env, "WAITING": ""}, [])
        self.assertEqual(1, rc, out)
        self.assertEqual("", calls)

    def name_the_red_run(self, env: dict, view: dict | None) -> tuple[int, str, str, str]:
        gh = [] if view is None else [("api repos/daarko10/tandem/actions/runs/", json.dumps(view), 0)]
        return run_step(load(DRIFT), "fix", "Name the red run", env, gh)

    def test_name_the_red_run_accepts_the_nightly_s_own_red_run(self):
        rc, out, output, _ = self.name_the_red_run(NAME_ENV, RUN_VIEW)
        self.assertEqual(0, rc, out)
        self.assertEqual(f"id=7\nurl={RUN_URL}\ndate=2026-10-21\nrehearse=false\n", output)

    def test_name_the_red_run_refuses_a_fork_s_a_green_and_another_branch_s_run(self):
        for field, value in (("head_repository", {"full_name": "someone/tandem"}), ("conclusion", "success"), ("head_branch", "feature"),
                             ("event", "pull_request"), ("name", "Build")):
            rc, out, output, calls = self.name_the_red_run(NAME_ENV, {**RUN_VIEW, field: value})
            self.assertEqual(1, rc, (field, out))
            self.assertEqual("", output, field)                                       # nothing reaches GITHUB_OUTPUT: the branch name and the fetch depend on it
            self.assertIn("Run 7 is not a failed scheduled or dispatched CLI contract run on main of daarko10/tandem", out)
            self.assertIn("api repos/daarko10/tandem/actions/runs/7\n", calls)

    def test_name_the_red_run_refuses_an_id_that_is_not_a_number_before_any_call(self):
        for bad in ("7;rm", "7\n8", "abc"):
            rc, out, output, calls = self.name_the_red_run({**NAME_ENV, "EVENT_RUN_ID": "", "INPUT_RUN_ID": bad}, None)
            self.assertEqual(1, rc, (bad, out))
            self.assertIn("run_id must be a number", out)
            self.assertEqual(("", ""), (output, calls))
        rc, out, output, calls = self.name_the_red_run({**NAME_ENV, "EVENT_RUN_ID": ""}, None)
        self.assertEqual(1, rc, out)
        self.assertIn("No run to fix", out)
        self.assertEqual(("", ""), (output, calls))

    def test_name_the_red_run_names_this_run_for_a_rehearsal_without_a_call(self):
        rc, out, output, calls = self.name_the_red_run({**NAME_ENV, "EVENT_RUN_ID": "", "REHEARSE": "true"}, None)
        self.assertEqual(0, rc, out)
        self.assertRegex(output, r"^id=99\nurl=https://github\.com/daarko10/tandem/actions/runs/99\ndate=\d{4}-\d{2}-\d{2}\nrehearse=true\n$")
        self.assertEqual("", calls)

    def protect(self, rules: str, owners: str, branch: tuple[str, int]) -> tuple[int, str, str, str]:
        return run_step(load(DRIFT), "fix", "Require a protected main", {"GH_TOKEN": "stub", "BRANCH": BRANCH},
                        [(RULES, rules, 0), (CODEOWNERS, owners, 0), (BRANCH_READ, branch[0], branch[1])])

    def test_protect_passes_a_ruleset_an_owner_and_a_missing_branch(self):
        rc, out, _, calls = self.protect("1\n", "* @daarko10\n", (NOT_FOUND, 1))
        self.assertEqual(0, rc, out)
        self.assertIn("main requires a pull request, an approving review from a code owner and approval of the last push (ruleset)", out)
        self.assertEqual([RULES, CODEOWNERS, BRANCH_READ.split(" --jq", 1)[0]], [c.split(" --jq", 1)[0] for c in calls.splitlines()])   # the ruleset first

    def test_protect_refuses_without_the_ruleset_the_owner_line_or_a_404(self):
        for rules, owners, branch, message in (("0\n", "* @daarko10\n", (NOT_FOUND, 1), "No ruleset on main requires a pull request"),
                                               ("x\n", "* @daarko10\n", (NOT_FOUND, 1), "No ruleset on main requires a pull request"),
                                               ("1\n", "docs/ @daarko10\n", (NOT_FOUND, 1), "CODEOWNERS has no line owning every file"),
                                               ("1\n", "* @daarko10\n", (BRANCH + "\n", 0), f"The branch {BRANCH} exists"),
                                               ("1\n", "* @daarko10\n", (SERVER_ERROR, 1), f"Reading the branch {BRANCH} failed (HTTP status '502')"),
                                               ("1\n", "* @daarko10\n", ("", 1), f"Reading the branch {BRANCH} failed (HTTP status 'none')")):
            rc, out, _, _ = self.protect(rules, owners, branch)
            self.assertEqual(1, rc, (message, out))
            self.assertIn(message, out)

    def test_the_report_opens_or_comments_on_the_standing_issue(self):
        classification = json.dumps({"kind": "cli-did-not-answer", "cli_version": "2.1.301", "detail": "no system/init", "files": ["contract-hello.ndjson"]})
        env = {"GH_TOKEN": "stub", "RUN_URL": RUN_URL}
        rc, out, _, calls = run_step(load(DRIFT), "fix", "Report a run the agent cannot fix", env,
                                     [(ISSUE_LIST, "", 0), (ISSUE_CREATE, "https://github.com/daarko10/tandem/issues/9\n", 0)], {"build/drift/precheck.json": classification})
        self.assertEqual(0, rc, out)
        self.assertIn(f"{ISSUE_CREATE} The CLI contract run {RUN_URL} is red and the drift loop cannot fix it.\n\n- kind: cli-did-not-answer\n- CLI version: 2.1.301\n- detail: no system/init\n- files: contract-hello.ndjson", calls)
        rc, out, _, calls = run_step(load(DRIFT), "fix", "Report a run the agent cannot fix", env,
                                     [(ISSUE_LIST, "3\n", 0), ("issue comment 3 --body", "", 0)],
                                     {"build/drift/precheck.json": classification, "build/drift/classification.json": classification.replace("cli-did-not-answer", "unknown")})
        self.assertEqual(0, rc, out)
        self.assertIn("issue comment 3 --body The CLI contract run", calls)
        self.assertIn("- kind: unknown", calls)                                       # the classification when Gradle ran, else the precheck
        self.assertNotIn("issue create", calls)

    def test_the_fetch_tells_no_recording_from_a_failed_listing(self):
        env = {"GH_TOKEN": "stub", "RUN_ID": "7"}
        listing = "api repos/daarko10/tandem/actions/runs/7/artifacts --jq"
        rc, out, _, calls = run_step(load(DRIFT), "fix", "Fetch the recording", env, [(listing, "null\n", 0)])
        self.assertEqual(0, rc, out)
        self.assertIn("the run left no recording", out)
        self.assertNotIn("run download", calls)
        rc, out, _, calls = run_step(load(DRIFT), "fix", "Fetch the recording", env, [(listing, "0\n", 0), ("run download 7 -n contract-recording -D build/drift/recording", "", 0)])
        self.assertEqual(0, rc, out)
        self.assertIn("run download 7 -n contract-recording -D build/drift/recording\n", calls)
        rc, out, _, calls = run_step(load(DRIFT), "fix", "Fetch the recording", env, [(listing, SERVER_ERROR, 1)])
        self.assertEqual(1, rc, out)                                                  # a failed listing is not "the CLI did not answer"
        self.assertNotIn("run download", calls)


if __name__ == "__main__":
    unittest.main()
```

(The two `None` names are the `actions/setup-java@v6` and `gradle/actions/setup-gradle@v6` steps, unnamed as in `build.yml`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree/scripts && python3 -m unittest test_workflows -v`
Expected: `FileNotFoundError: … drift-fix.yml` in every test.

- [ ] **Step 3: Write `.github/workflows/drift-fix.yml` and `.github/CODEOWNERS`**

`.github/CODEOWNERS` is the one line `* @daarko10`. The workflow:

```yaml
# Turns a red nightly `CLI contract` run into a pull request: downloads the run's redacted recording, classifies the red
# (scripts/classify_drift.py), and runs the repository skill fix-contract-drift with Workload Identity Federation (the same
# four identifiers as cli-contract.yml; the federation rule admits runs on main only). A run the CLI did not answer is
# reported on the issue "CLI contract: the CLI did not answer" instead, and an open PR labelled contract-drift skips the
# agent. Only a scheduled or dispatched run of this repository's own nightly is fixed, never a fork's or a pull request's.
# The agent never starts unless a ruleset on main requires a pull request with an approving review from a code owner and
# approval of the most recent push, and main's CODEOWNERS owns every file: that protection, not the tool allowlist, keeps
# the App token off main. A second job, enforce, checks the pull request's file list on a fresh runner once the agent's
# job has ended; a third, remind, comments on the pull request the gate found waiting. Nothing here merges or
# publishes. Rehearsal: `gh workflow run
# cli-contract.yml -f drift=<mode>` makes the nightly mutate its own recording, so this workflow fires as for a real red;
# workflow_dispatch with rehearse=true runs the fixer alone on a mutated fixture. CLAUDE.md names the loop.
name: Drift fix

on:
  workflow_run:
    workflows: [CLI contract]
    types: [completed]
  workflow_dispatch:
    inputs:
      rehearse:
        description: Run the fixer alone against a recording mutated on purpose, with no download; the draft PR it opens is closed, not merged
        type: boolean
        default: false
      mode:
        description: "Rehearsal drift: init-model-key (decoder) or transcript-content-key (reader)"
        type: string
        default: init-model-key
      run_id:
        description: The id of a red CLI contract run to fix now (ignored with rehearse)
        type: string
        default: ""

permissions:
  id-token: write # the federation exchange, and the Claude GitHub App token exchange
  contents: read # the checkout, and the drift branch read through the API; the fix branch is pushed with the App token
  pull-requests: read # the gate's PR list only: this token, which test code the agent ran with sudo can read from the
  # runner's memory, can neither approve nor comment on a pull request (a comment needs pull-requests: write even through
  # the issues endpoint); the reminder on a waiting PR is the remind job's, with its own token (see the enforce job's note)
  issues: write # the report for a run the CLI did not answer
  actions: read # the red run and its artifact

concurrency:
  group: drift-fix
  cancel-in-progress: false

env:
  ANTHROPIC_FEDERATION_RULE_ID: fdrl_019BGJmAMDiAL6DmAQENbhfk
  ANTHROPIC_ORGANIZATION_ID: 8336fe5f-a7f7-4255-9fe0-7f594d5a8ec8
  ANTHROPIC_SERVICE_ACCOUNT_ID: svac_01F45vdtRBnPSRZHXY8ek1fn
  ANTHROPIC_WORKSPACE_ID: wrkspc_012JJzfEG8tDKeta7pe3CCa5

jobs:
  fix:
    runs-on: ubuntu-latest
    timeout-minutes: 90
    # Read by the enforce and remind jobs. All but agent were written before the agent started, and a step's
    # GITHUB_OUTPUT is read when that step ends, so no later step of this job can rewrite them through GITHUB_OUTPUT;
    # agent only appears in a message. Code the agent ran holds root on this runner, though, and could tamper with the
    # runner worker that reports them: enforce is a check, not a guarantee; the ruleset on main is the guarantee.
    outputs:
      proceed: ${{ steps.gate.outputs.proceed }}
      waiting: ${{ steps.gate.outputs.waiting }}
      protected: ${{ steps.protect.outcome }}
      kind: ${{ steps.classify.outputs.kind }}
      agent: ${{ steps.agent.outputs.conclusion }}
      id: ${{ steps.run.outputs.id }}
      url: ${{ steps.run.outputs.url }}
      date: ${{ steps.run.outputs.date }}
    # github.ref is refs/heads/main for a workflow_run job, whose OIDC subject is then
    # repo:daarko10@34120174/tandem@1421183639:ref:refs/heads/main, the one the federation rule admits. A dispatch from
    # another branch is skipped here instead of failing at the federation exchange after the disk and Gradle setup.
    # workflow_run also fires for a fork's pull request, whose head_branch and workflow name are the fork's to choose: a
    # fork can name a workflow "CLI contract", run it from its own branch main, fail it on purpose and upload its own
    # contract-recording. So the run must come from this repository and from the nightly's own triggers (schedule,
    # workflow_dispatch); "Name the red run" checks the same through the API, for a run_id too.
    if: >-
      github.repository == 'daarko10/tandem' && github.ref == 'refs/heads/main' &&
      (github.event_name == 'workflow_dispatch' ||
       (github.event.workflow_run.conclusion == 'failure' && github.event.workflow_run.head_branch == 'main' &&
        github.event.workflow_run.head_repository.full_name == github.repository &&
        (github.event.workflow_run.event == 'schedule' || github.event.workflow_run.event == 'workflow_dispatch')))
    steps:
      # The platform tests the skill runs need the room, as in build.yml.
      - name: Free disk space
        uses: jlumbroso/free-disk-space@v2.0.0
        with:
          tool-cache: false
          large-packages: false
      # No stored credential: the only GitHub credential the agent holds is the App token the action exports.
      - name: Check out main
        uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: actions/setup-java@v6
        with:
          distribution: zulu
          java-version: 21
      # Read-only against an accidental save: setup-gradle's post step never saves this job's Gradle User Home, which the
      # test code the agent wrote can rewrite (build cache entries, dependency jars), into main's scope, which Build on
      # main and on every PR restores. It does not bind hostile code: that test code runs with sudo on a job on main and
      # could write such a cache itself with the runner's cache token; nothing in the repository closes that, and after
      # a suspect run the owner clears the caches (Actions | Caches).
      - uses: gradle/actions/setup-gradle@v6
        with:
          cache-read-only: true
      - name: Ensure the labels exist
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh label create contract-drift --force --color B60205 --description "Opened or reported by the drift loop (drift-fix.yml)"
          gh label create rehearsal --force --color FBCA04 --description "A drift-loop rehearsal: close, do not merge"
      # Outputs: id, url and UTC date of the red run (this run itself when rehearse=true), and rehearse=true|false. The id
      # must be a number and name a failed run of this repository's CLI contract on main, scheduled or dispatched (the
      # nightly's only triggers): a branch's run carries that branch's own recording, and a fork's pull request can name a
      # workflow "CLI contract" and a branch main. The REST run object names the head repository and the event, which
      # gh run view does not. The refusal prints those fields as compact JSON, so no field can start a workflow command.
      - name: Name the red run
        id: run
        env:
          GH_TOKEN: ${{ github.token }}
          EVENT_RUN_ID: ${{ github.event.workflow_run.id }}
          INPUT_RUN_ID: ${{ inputs.run_id }}
          REHEARSE: ${{ inputs.rehearse }}
        run: |
          set -euo pipefail
          if [ "$REHEARSE" = "true" ]; then
            id="$GITHUB_RUN_ID"; url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"; date="$(date -u +%F)"
          else
            id="${EVENT_RUN_ID:-$INPUT_RUN_ID}"
            if [ -z "$id" ]; then echo "::error title=Drift fix::No run to fix: pass run_id, or rehearse=true."; exit 1; fi
            [[ "$id" =~ ^[0-9]+$ ]] || { echo "::error title=Drift fix::run_id must be a number."; exit 1; }
            view="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$id")"
            url="$(jq -r .html_url <<<"$view")"; date="$(jq -r '.created_at[0:10]' <<<"$view")"
            if ! jq -e --arg repo "$GITHUB_REPOSITORY" '.name == "CLI contract" and .conclusion == "failure" and .head_branch == "main" and .head_repository.full_name == $repo and (.event == "schedule" or .event == "workflow_dispatch")' <<<"$view" >/dev/null; then
              echo "::error title=Drift fix::Run $id is not a failed scheduled or dispatched CLI contract run on main of $GITHUB_REPOSITORY: $(jq -c '[.name, .conclusion, .head_branch, .head_repository.full_name, .event]' <<<"$view")."; exit 1
            fi
          fi
          printf 'id=%s\nurl=%s\ndate=%s\nrehearse=%s\n' "$id" "$url" "$date" "$([ "$REHEARSE" = "true" ] && echo true || echo false)" >> "$GITHUB_OUTPUT"
          echo "fixing run $url ($date)"
      # No artifact at all is a red of kind 1 (the record step wrote nothing), classified below; not an error here. Any
      # other failure of the download (a 403, an API outage, a renamed artifact, an expired retention) fails the job: it is
      # not "the CLI did not answer". build/drift is created here because the steps below write into it. gh's --jq prints
      # null as an empty line, hence tostring: the absent artifact reads "null", never "".
      - name: Fetch the recording
        if: steps.run.outputs.rehearse != 'true'
        env:
          GH_TOKEN: ${{ github.token }}
          RUN_ID: ${{ steps.run.outputs.id }}
        run: |
          set -euo pipefail
          mkdir -p build/drift
          present="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$RUN_ID/artifacts" --jq '[.artifacts[].name] | index("contract-recording") | tostring')"
          if [ "$present" = "null" ]; then echo "the run left no recording"; exit 0; fi
          gh run download "$RUN_ID" -n contract-recording -D build/drift/recording
      # The fixer-only rehearsal: the committed history-reply fixture, mutated, stands in for the download. The script leaves
      # the marker build/drift/recording/rehearsal, as the nightly's own rehearsal step leaves one in its artifact.
      - name: Mutate a recording for the rehearsal
        if: steps.run.outputs.rehearse == 'true'
        env:
          MODE: ${{ inputs.mode }}
        run: python3 -I scripts/make_drift.py "$MODE" build/drift/recording
      # Rules 1-3 of the classification need no test results: a run the CLI did not answer is known here, and the Gradle
      # step (the platform download, a compile) never runs for it. The marker names a rehearsal, whichever path left it.
      - name: Classify before Gradle
        id: precheck
        run: |
          set -euo pipefail
          mkdir -p build/drift
          python3 -I scripts/classify_drift.py build/drift/recording build/test-results/test | tee build/drift/precheck.json
          echo "kind=$(jq -r .kind build/drift/precheck.json)" >> "$GITHUB_OUTPUT"
          echo "rehearsal=$([ -f build/drift/recording/rehearsal ] && echo true || echo false)" >> "$GITHUB_OUTPUT"
      # Reproduces the red here and leaves the assertion messages in build/test-results/test for the classification.
      # --rerun: the build cache (org.gradle.caching, restored by setup-gradle) keys the task on the code and the path, not
      # the recording's contents, so without it a ContractTest that passed once on this commit could replay from the cache.
      - name: Reproduce the red
        if: steps.precheck.outputs.kind != 'cli-did-not-answer'
        continue-on-error: true
        run: ./gradlew test --tests '*ContractTest*' --rerun -Dtandem.contractDir="$PWD/build/drift/recording"
      - name: Classify
        id: classify
        if: steps.precheck.outputs.kind != 'cli-did-not-answer'
        run: |
          set -euo pipefail
          python3 -I scripts/classify_drift.py build/drift/recording build/test-results/test | tee build/drift/classification.json
          echo "kind=$(jq -r .kind build/drift/classification.json)" >> "$GITHUB_OUTPUT"
      # Kind 1 and unknown: one standing issue, one comment per red run, no branch, no agent. The job ends green. Reads the
      # classification when Gradle ran, else the precheck.
      - name: Report a run the agent cannot fix
        if: steps.precheck.outputs.kind == 'cli-did-not-answer' || steps.classify.outputs.kind == 'cli-did-not-answer' || steps.classify.outputs.kind == 'unknown'
        env:
          GH_TOKEN: ${{ github.token }}
          RUN_URL: ${{ steps.run.outputs.url }}
        run: |
          set -euo pipefail
          file=build/drift/classification.json; [ -f "$file" ] || file=build/drift/precheck.json
          title="CLI contract: the CLI did not answer"
          body="$(jq -r --arg url "$RUN_URL" '"The CLI contract run \($url) is red and the drift loop cannot fix it.\n\n- kind: \(.kind)\n- CLI version: \(.cli_version // "unknown")\n- detail: \(.detail)\n- files: \(if .files == [] then "none" else (.files | join(", ")) end)"' "$file")"
          issue="$(gh issue list --label contract-drift --state open --search "\"$title\" in:title" --json number --jq '.[0].number // empty')"
          if [ -z "$issue" ]; then gh issue create --title "$title" --label contract-drift --body "$body"; else gh issue comment "$issue" --body "$body"; fi
      # One open fix at a time: a red that persists for a week opens one PR, not seven. The reminder on the waiting PR
      # is the remind job's: commenting on a pull request needs pull-requests: write even through the issues endpoint
      # (issues: write alone gets a 403), and this job's token holds none. Only the number reaches the output.
      - name: Skip when a fix is already waiting
        id: gate
        if: steps.classify.outputs.kind == 'decoder' || steps.classify.outputs.kind == 'reader'
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail
          pr="$(gh pr list --label contract-drift --state open --json number --jq '.[0].number // empty')"
          if [[ "$pr" =~ ^[0-9]+$ ]]; then
            echo "a fix is already waiting in PR #$pr; the remind job comments on it and the agent does not start"
            printf 'proceed=false\nwaiting=%s\n' "$pr" >> "$GITHUB_OUTPUT"
          elif [ -z "$pr" ]; then
            echo "proceed=true" >> "$GITHUB_OUTPUT"
          else
            echo "::error title=Drift fix::gh pr list named an open contract-drift pull request without a number; the agent is not started."; exit 1
          fi
      # The precondition the whole loop rests on, checked before any spend: the App token the action exports can push to
      # any branch no rule protects, main included. Only a ruleset counts: the workflow token can read its rules (metadata
      # access) but not a classic protection's review count (administration access), and a classic rule with no option
      # ticked still reads protected: true while it accepts a direct push. One approving review alone is not enough
      # either: the App holds pull-requests write, so it can approve a pull request it did not author (the owner's open
      # PR after a push to its unprotected head branch, a Dependabot PR) and then merge it, and test code the agent wrote
      # runs with the App token in its environment. main passes when an active ruleset's pull_request rule requires at
      # least one approving review, review from code owners and approval of the most recent reviewable push (an App is
      # no code owner, and cannot approve its own push), and main's .github/CODEOWNERS owns every file with a `* @owner`
      # line: the code-owner review binds only files that have an owner. Anything else, a failed read or an answer that
      # is not a number included, fails the step: the run is red and the agent does not start. A ruleset bypass list
      # naming the Claude App would defeat the rule; keeping the App off that list is the owner's part. A ruleset on a
      # private repository owned by a personal account is enforced only with GitHub Pro: on Free the repository goes
      # public before the ruleset and the rehearsals, and what this endpoint lists for an unenforced ruleset is not
      # known, so reading the rule here does not prove enforcement. The same step refuses a drift branch that already
      # exists (a closed PR whose branch was kept, and the same run dispatched again): the agent's `git push -u origin
      # HEAD` would be rejected as non-fast-forward after the spend, and the enforce job would misread the old branch as
      # a stopped agent. Only a 404 means "no branch": gh prints the error body on stdout and exits 1 on any HTTP error,
      # so the read is decided by its exit status and then by the body's status, and a 5xx or a rate limit refuses too.
      - name: Require a protected main
        id: protect
        if: steps.gate.outputs.proceed == 'true'
        env:
          GH_TOKEN: ${{ github.token }}
          BRANCH: drift/${{ steps.run.outputs.date }}-${{ steps.run.outputs.id }}
        run: |
          set -euo pipefail
          rules="$(gh api "repos/$GITHUB_REPOSITORY/rules/branches/main?per_page=100" --jq '[.[] | select(.type == "pull_request" and (.parameters.required_approving_review_count // 0) >= 1 and .parameters.require_code_owner_review == true and .parameters.require_last_push_approval == true)] | length')"
          if [[ "$rules" =~ ^[0-9]+$ ]] && [ "$rules" -ge 1 ]; then
            echo "main requires a pull request, an approving review from a code owner and approval of the last push (ruleset)"
          else
            echo "::error title=Drift fix::No ruleset on main requires a pull request with an approving review from a code owner and approval of the most recent push (a private repository of a personal account enforces one only with GitHub Pro); the agent is not started."; exit 1
          fi
          owners="$(gh api -H "Accept: application/vnd.github.raw+json" "repos/$GITHUB_REPOSITORY/contents/.github/CODEOWNERS?ref=main" 2>/dev/null || true)"
          if ! grep -qE '^\*[[:space:]]+@' <<<"$owners"; then
            echo "::error title=Drift fix::main's .github/CODEOWNERS has no line owning every file (* @owner), so the ruleset's code-owner review binds nothing; the agent is not started."; exit 1
          fi
          if existing="$(gh api "repos/$GITHUB_REPOSITORY/branches/$BRANCH" --jq .name 2>/dev/null)"; then
            echo "::error title=Drift fix::The branch $BRANCH exists (a closed pull request's branch, kept); delete it to retry this run."; exit 1
          fi
          status="$(jq -r '.status // empty' <<<"$existing" 2>/dev/null || true)"
          if [ "$status" != "404" ]; then
            echo "::error title=Drift fix::Reading the branch $BRANCH failed (HTTP status '${status:-none}'), so whether it exists is not known; the agent is not started."; exit 1
          fi
      # No github_token input: the action authenticates as the Claude GitHub App, sets the origin URL and the commit identity
      # before the CLI starts, and the PR it opens starts the Build workflow. The allowlist names the invocations the skill
      # prescribes; the push rule is exact (a :* rule matches the prefix followed by a space, so drift/:* would never match
      # the push). The deny rules keep the CLI's Edit and Write tools out of some of the files that run code before the
      # push (the build scripts, gradle.properties and the wrapper, scripts/, the hooks in .claude/ and .git/) and out of
      # a second CLI; test code under src/test runs anyway, and `cp` is not bound by them, so the enforce job checks the
      # result on a fresh runner. The action also loads the project's .claude/settings.json, whose allow rules are merged
      # in; the deny rules below take back the Gradle tasks and ktlint it allows that the skill never prescribes, every
      # flag after `./gradlew check` (its rule there is a prefix): `-x` and `--exclude-task` as prefix rules, and the
      # wildcard rule `./gradlew check *-*` (a rule with a `*` not in a trailing `:*` is a pattern) for
      # `--exclude-task=koverVerify`, `--init-script` and the rest, and `./gradlew test *check*`, the other spelling of a
      # check that skips koverVerify. What runs the real `./gradlew check` is Build on the pull request, not the agent's
      # report. Neither list is what protects main: the ruleset on main (a pull request plus a code owner's approving review
      # of the last push, required by the step above) and the App token's scope are.
      - name: Fix the drift
        id: agent
        if: steps.gate.outputs.proceed == 'true' && steps.protect.outcome == 'success'
        uses: anthropics/claude-code-action@v1
        timeout-minutes: 75
        with:
          anthropic_federation_rule_id: ${{ env.ANTHROPIC_FEDERATION_RULE_ID }}
          anthropic_organization_id: ${{ env.ANTHROPIC_ORGANIZATION_ID }}
          anthropic_service_account_id: ${{ env.ANTHROPIC_SERVICE_ACCOUNT_ID }}
          anthropic_workspace_id: ${{ env.ANTHROPIC_WORKSPACE_ID }}
          prompt: "/fix-contract-drift run=${{ steps.run.outputs.url }} recording=build/drift/recording classification=build/drift/classification.json rehearsal=${{ steps.precheck.outputs.rehearsal }} date=${{ steps.run.outputs.date }}"
          claude_args: |
            --model claude-opus-5-5
            --max-turns 120
            --allowedTools "Read,Grep,Glob,Edit,Write,Bash(./gradlew check),Bash(./gradlew test:*),Bash(python3 -I scripts/classify_drift.py:*),Bash(python3 -I scripts/redact_fixture.py:*),Bash(python3 -m unittest discover -s scripts -t scripts),Bash(gh pr create:*),Bash(gh issue list:*),Bash(gh issue create:*),Bash(gh issue comment:*),Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(git add:*),Bash(git commit:*),Bash(git switch:*),Bash(git clean:*),Bash(git push -u origin HEAD),Bash(cp:*),Bash(ls:*),Bash(cat:*),Bash(grep:*),Bash(date:*)"
          settings: |
            {"permissions": {"deny": ["Edit(.github/**)", "Write(.github/**)", "Edit(build.gradle.kts)", "Write(build.gradle.kts)", "Edit(settings.gradle.kts)", "Write(settings.gradle.kts)", "Edit(gradle.properties)", "Write(gradle.properties)", "Edit(gradle/**)", "Write(gradle/**)", "Edit(.git/**)", "Write(.git/**)", "Edit(scripts/**)", "Write(scripts/**)", "Edit(.claude/**)", "Write(.claude/**)", "Edit(CLAUDE.md)", "Write(CLAUDE.md)", "Bash(claude:*)", "Bash(./gradlew check -x:*)", "Bash(./gradlew check --exclude-task:*)", "Bash(./gradlew check *-*)", "Bash(./gradlew test *check*)", "Bash(./gradlew compileKotlin:*)", "Bash(./gradlew compileTestKotlin:*)", "Bash(./gradlew buildPlugin:*)", "Bash(./gradlew verifyPlugin:*)", "Bash(ktlint:*)"]}, "env": {"BASH_DEFAULT_TIMEOUT_MS": "900000", "BASH_MAX_TIMEOUT_MS": "1800000"}}
  # The reminder on the pull request the gate found waiting, once per further red. Commenting on a pull request needs
  # pull-requests: write even through the issues endpoint (issues: write alone gets a 403), and the fix job's token must
  # hold no such write (the agent's test code can read it), so the comment is posted here, in a job that runs no agent
  # code and has no checkout. The fix job ends green when the gate skipped, so no always() is needed; GH_REPO names the
  # repository because no checkout is there to infer it from.
  remind:
    needs: fix
    runs-on: ubuntu-latest
    timeout-minutes: 5
    if: needs.fix.outputs.proceed == 'false'
    permissions:
      pull-requests: write
      issues: write
    steps:
      - name: Comment on the waiting pull request
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          WAITING: ${{ needs.fix.outputs.waiting }}
          RUN_URL: ${{ needs.fix.outputs.url }}
          DATE: ${{ needs.fix.outputs.date }}
        run: |
          set -euo pipefail
          [[ "$WAITING" =~ ^[0-9]+$ ]] || { echo "::error title=Drift fix::The gate skipped the agent but named no waiting pull request."; exit 1; }
          gh pr comment "$WAITING" --body "The nightly of $DATE is still red: $RUN_URL. This pull request is still waiting; merge or close it to re-arm the loop."
          echo "commented on PR #$WAITING"
  # The check that depends neither on the agent obeying the skill nor on the runner its code ran on: the fix job runs test
  # code the agent wrote (./gradlew test) with sudo, and that code can change what a later step of the same job sees (a
  # gh first on PATH, BASH_ENV, GITHUB_PATH, GITHUB_ENV), so the step that reads the PR's file list runs here, on a fresh
  # runner, with no checkout and only the permissions it needs. What it reads from the fix job was written before the
  # agent started and cannot be rewritten through GITHUB_OUTPUT (see that job's outputs); root on that runner could still
  # tamper with the worker that reports them, so this job is a check, not a guarantee: the ruleset on main is the
  # guarantee. agent only appears in a message. The loop's pull requests are the open ones whose head is this run's
  # branch in this repository (never a fork's PR from a branch of the same name: the run id is visible in Actions, and a
  # fork's PR labelled here would hold the gate against every later real fix) or which carry the label contract-drift
  # (the gate proceeded only when none carried it, so every one of them was opened during this run): the file list of
  # each is checked, never only the first's; a PR from the branch that lacks the label gets it, so the gate holds while
  # it is open; and more than one is red, since the agent opens one. A PR touching anything outside the allowlist is
  # commented on, left open and the run is red. It is never closed: the gate keys on an open contract-drift PR, so a
  # close would re-arm the loop and the next nightly would produce the same fix again, every night.
  # Once the gate proceeded, no PR is a red run: a pushed branch without one (the agent was stopped by the timeout or the
  # turn limit) is reported on the standing issue; no branch at all is a denied command, a redactor stop, an action that
  # skipped the agent, or an agent that gave up. The branch is read through the API with the workflow token (contents:
  # read; the App token was revoked in the fix job's post step): gh prints the error body on stdout and exits 1 on any
  # HTTP error, so the read is decided by its exit status, only a 404 in the body means "no branch", and any other
  # failure (a 5xx, a rate limit) is red without a verdict rather than "pushed no branch". GH_REPO names the repository
  # because no checkout is there to infer it from. No draft is opened: GITHUB_TOKEN cannot create a pull request while the repository setting
  # "Allow GitHub Actions to create and approve pull requests" is off, and off it stays: on, github-actions[bot] could
  # approve pull requests, and the fix job's token is readable by the agent's test code (the code-owner rule would still
  # refuse that approval; the setting is the second lock). The PR's file list is read on its own line, so a failed read
  # fails the step instead of passing as "no offending path". It comes from the REST files list, paged (up to 3000
  # files), with the old path of each rename: GraphQL's `files` (gh pr view) stops at 100 and names only a rename's new
  # path, so a file moved into src/test/ from outside the allowlist would pass.
  enforce:
    needs: fix
    runs-on: ubuntu-latest
    timeout-minutes: 10
    if: always() && needs.fix.outputs.proceed == 'true' && needs.fix.outputs.protected == 'success'
    permissions:
      contents: read
      pull-requests: write
      issues: write
    steps:
      - name: Enforce the edit allowlist
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          BRANCH: drift/${{ needs.fix.outputs.date }}-${{ needs.fix.outputs.id }}
          RUN_URL: ${{ needs.fix.outputs.url }}
          KIND: ${{ needs.fix.outputs.kind }}
          AGENT: ${{ needs.fix.outputs.agent }}
        run: |
          set -euo pipefail
          open="$(gh pr list --state open --limit 100 --json number,headRefName,labels,isCrossRepository)"
          mapfile -t prs < <(jq -r --arg branch "$BRANCH" '.[] | select((.headRefName == $branch and .isCrossRepository == false) or any(.labels[]; .name == "contract-drift")) | "\(.number) \(.headRefName) \(if any(.labels[]; .name == "contract-drift") then "labelled" else "unlabelled" end)"' <<<"$open")
          if [ "${#prs[@]}" -eq 0 ]; then
            if branch="$(gh api "repos/$GITHUB_REPOSITORY/branches/$BRANCH" --jq .name 2>/dev/null)"; then
              title="CLI contract: the CLI did not answer"
              body="The drift fix for $RUN_URL pushed the branch $BRANCH but opened no pull request: the agent was stopped (timeout or turn limit; its step ended '${AGENT:-skipped}'). Review the branch by hand."
              issue="$(gh issue list --label contract-drift --state open --search "\"$title\" in:title" --json number --jq '.[0].number // empty')"
              if [ -z "$issue" ]; then gh issue create --title "$title" --label contract-drift --body "$body"; else gh issue comment "$issue" --body "$body"; fi
              echo "::error title=Drift fix::The agent pushed $BRANCH but opened no PR; reported on the standing issue."; exit 1
            fi
            status="$(jq -r '.status // empty' <<<"$branch" 2>/dev/null || true)"
            if [ "$status" != "404" ]; then
              echo "::error title=Drift fix::Reading the branch $BRANCH failed (HTTP status '${status:-none}'), so whether the agent pushed it is not known; the run is red without a verdict."; exit 1
            fi
            echo "::error title=Drift fix::The classification was $KIND but the agent pushed no branch and opened no PR (its step ended '${AGENT:-skipped}'): a denied command, a redactor stop (see the standing issue), the action skipped after a workflow-validation error, or the agent gave up; read the Fix the drift step's log."; exit 1
          fi
          red=0
          for entry in "${prs[@]}"; do
            read -r pr head labelled <<<"$entry"
            if [ "$labelled" = unlabelled ]; then
              gh api "repos/$GITHUB_REPOSITORY/issues/$pr/labels" -f "labels[]=contract-drift" >/dev/null
              echo "::warning title=Drift fix::PR #$pr from $head carried no contract-drift label; added, so the gate holds while it is open."
            fi
            files="$(gh api --paginate "repos/$GITHUB_REPOSITORY/pulls/$pr/files" --jq '.[] | .filename, (.previous_filename // empty)')"
            offending="$(grep -vE '^(src/main/kotlin/dev/daarkolabs/tandem/protocol/|src/main/kotlin/dev/daarkolabs/tandem/session/transcript/|src/test/|docs/protocol\.md$)' <<<"$files" || true)"
            if [ -n "$offending" ]; then
              gh pr comment "$pr" --body "$(printf 'drift-fix.yml: this fix edits files outside the allowlist (protocol/, session/transcript/, src/test/, docs/protocol.md); it is left open so the loop holds (the gate skips while it is open) and the owner decides. Close it to re-arm the loop, or merge after review.\n\n%s' "$offending")"
              echo "::error title=Drift fix::PR #$pr edits files outside the allowlist; commented and left open."; red=1
            else
              echo "PR #$pr ($head) stays within the allowlist"
            fi
          done
          if [ "${#prs[@]}" -gt 1 ]; then
            echo "::error title=Drift fix::${#prs[@]} open pull requests belong to the loop (${prs[*]%% *}); the agent opens one. Close the extra ones by hand; the gate holds while any is open."; red=1
          fi
          exit "$red"
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree/scripts && python3 -m unittest test_workflows -v`
Expected: `Ran 35 tests` … `OK` (not `skipped`: PyYAML 6, bash 5 and jq are installed in this sandbox; `StepOutcomeTest` runs the steps under bash with a stub `gh`).
- [ ] **Step 5: Lint the YAML**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree && if command -v actionlint >/dev/null; then actionlint .github/workflows/drift-fix.yml .github/workflows/cli-contract.yml; else python3 -c 'import yaml,sys; [yaml.safe_load(open(p)) for p in sys.argv[1:]]; print("yaml ok")' .github/workflows/drift-fix.yml .github/workflows/cli-contract.yml; fi`
Expected: `actionlint` prints nothing (exit 0), or `yaml ok`. `actionlint` is not installed in this sandbox and its download is refused by the proxy; the rehearsal (D5) is the remaining validation.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/drift-fix.yml .github/CODEOWNERS scripts/test_workflows.py
git commit -m "ci: drift-fix.yml turns a red CLI contract run into a pull request"
```

---

### Task D3: The nightly's side

**Files:**
- Modify: `.github/workflows/cli-contract.yml` (the `workflow_dispatch` input `drift`; the check step's renamed-type case; the `--session-id` step's copy; a mutate step between it and the Gradle step; a failure step after the Gradle step; the artifact step unchanged)
- Test: `scripts/test_workflows.py` (extend)

**Interfaces:**
- Consumes: `scripts/copy_transcript.py <work-dir> <raw> <out-dir> <name>` and `scripts/redact_fixture.py <in> <out>` (unchanged); `scripts/make_drift.py <mode> <out> --from <dir>` (D1); `load`, `steps`, `step` from D2's test.
- Produces: an artifact `contract-recording` whose `transcripts/session-id.jsonl` is redacted; `::error title=CLI contract::` annotations naming each failed assertion; a check step that reds a stream with result text but no `assistant` line as a drift of its own, not as "the CLI did not answer"; a dispatch input `drift` whose run mutates its own live recording in place (marker `build/contract/rehearsal` in the artifact), reds the Gradle step and so fires `drift-fix.yml` through `workflow_run` as a real red does. No second identity token: the design wires no re-recording step in the fixer (the spec says so under "The red run's recording is the new fixture"), so the nightly's two tokens stay as they are.

- [ ] **Step 1: Write the failing tests**

Append to `scripts/test_workflows.py`:

```python
def test_the_nightly_redacts_the_session_id_transcript_and_keeps_the_artifact(self):
    wf = load(os.path.join(ROOT, ".github/workflows/cli-contract.yml"))
    run = step(wf, "contract", "Check --session-id with an inherited CLAUDE_CODE_SESSION_ID")["run"]
    self.assertIn('scripts/copy_transcript.py "$work" "$work/raw.ndjson" "$work/transcripts" session-id', run)
    self.assertIn('scripts/redact_fixture.py "$work/transcripts/session-id.jsonl" build/contract/transcripts/session-id.jsonl', run)
    keep = step(wf, "contract", "Keep the recording")
    self.assertEqual(("always()", "contract-recording", "build/contract"), (keep["if"], keep["with"]["name"], keep["with"]["path"]))

def test_the_nightly_prints_failed_assertions_as_annotations(self):
    wf = load(os.path.join(ROOT, ".github/workflows/cli-contract.yml"))
    names = [s.get("name") for s in steps(wf, "contract")]
    self.assertEqual(names.index("Decode with Tandem's protocol layer and load the transcripts with its reader") + 1, names.index("Name the failed assertions"))
    report = step(wf, "contract", "Name the failed assertions")
    self.assertEqual("failure()", report["if"])
    self.assertIn("::error title=CLI contract::", report["run"])
    self.assertIn("build/test-results/test", report["run"])

def test_the_nightly_s_check_step_tells_a_renamed_type_from_no_answer(self):
    run = step(load(os.path.join(ROOT, ".github/workflows/cli-contract.yml")), "contract", "Check that the CLI authenticated and answered")["run"]
    self.assertIn("The CLI did not answer", run)
    self.assertIn("No assistant line although the result holds text", run)

def test_the_nightly_rehearsal_mutates_its_own_recording(self):
    wf = load(os.path.join(ROOT, ".github/workflows/cli-contract.yml"))
    drift = wf[True]["workflow_dispatch"]["inputs"]["drift"]
    self.assertEqual(("string", ""), (drift["type"], drift["default"]))
    names = [s.get("name") for s in steps(wf, "contract")]
    self.assertEqual(names.index("Check --session-id with an inherited CLAUDE_CODE_SESSION_ID") + 1, names.index("Mutate the recording for a rehearsal"))
    self.assertEqual(names.index("Mutate the recording for a rehearsal") + 1, names.index("Decode with Tandem's protocol layer and load the transcripts with its reader"))
    mutate = step(wf, "contract", "Mutate the recording for a rehearsal")
    self.assertEqual("inputs.drift != ''", mutate["if"])
    self.assertEqual("${{ inputs.drift }}", mutate["env"]["DRIFT"])                # through env, never interpolated into the shell line
    self.assertIn('scripts/make_drift.py "$DRIFT" build/contract --from build/contract', mutate["run"])
    self.assertNotIn("${{", mutate["run"])
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree/scripts && python3 -m unittest test_workflows -v`
Expected: the four new tests fail (`AssertionError` on the `copy_transcript.py` line; `ValueError: 'Name the failed assertions' is not in list`; `AssertionError` on `No assistant line although the result holds text`; `TypeError: 'NoneType' object is not subscriptable` on the `drift` input, since `workflow_dispatch:` has no value today).

- [ ] **Step 3: Change `cli-contract.yml`**

Under `on:`, give `workflow_dispatch:` its input:
```yaml
  workflow_dispatch:
    inputs:
      drift:
        description: "Rehearse the drift loop: mutate this run's own recording (init-model-key or transcript-content-key) so Drift fix fires as for a real red; empty for a real run"
        type: string
        default: ""
```
In the step "Check that the CLI authenticated and answered", replace the `if` block of the Python with (the reply text is the result's `result` string; no reply line while it holds text is a renamed core type, which `ContractTest` alone would pass):
```python
          has_reply = any(m.get("type") == "assistant" for m in lines)
          text = result.get("result") if isinstance(result.get("result"), str) else ""
          if result.get("is_error") or not (has_reply or text):
              detail = text or json.dumps(result.get("errors", result))
              print(f"::error title=CLI contract::The CLI did not answer: {str(detail)[:400]}")
              sys.exit(1)
          if not has_reply:
              print("::error title=CLI contract::No assistant line although the result holds text: a core type may have been renamed; see the recording.")
              sys.exit(1)
```
In the `--session-id` step replace the last line with:
```bash
          python3 -I scripts/copy_transcript.py "$work" "$work/raw.ndjson" "$work/transcripts" session-id
          mkdir -p build/contract/transcripts
          python3 -I scripts/redact_fixture.py "$work/transcripts/session-id.jsonl" build/contract/transcripts/session-id.jsonl
```
and a comment above the step's `run:` that the copy is redacted before upload because the fixer promotes the artifact as a fixture and the fixtures will be public (`docs/launch.md` tactic 4; the repository is private today, so the artifact itself is not). After that step, before the Gradle step:
```yaml
      # The rehearsal of the drift loop: the live recording is mutated in place (all three files are read before any is
      # written) and a marker file names the mode, so the Gradle step goes red, the real artifact is uploaded and
      # drift-fix.yml fires through workflow_run exactly as for a real red. A scheduled run has no input and records as is.
      - name: Mutate the recording for a rehearsal
        if: inputs.drift != ''
        env:
          DRIFT: ${{ inputs.drift }}
        run: python3 -I scripts/make_drift.py "$DRIFT" build/contract --from build/contract
```
After the Gradle step, before "Keep the recording":
```yaml
      # The Gradle console names the failing test but not the assertion message (the file and the unrecognised types);
      # the message is in the JUnit XML. Printed as annotations, so a red run can be read without downloading anything.
      - name: Name the failed assertions
        if: failure()
        run: |
          python3 -I - build/test-results/test <<'PY'
          import glob, sys
          import xml.etree.ElementTree as ET
          for path in sorted(glob.glob(sys.argv[1] + "/TEST-*.xml")):
              for case in ET.parse(path).getroot().iter("testcase"):
                  for failure in case.findall("failure"):
                      print(f"::error title=CLI contract::{case.get('name')}: {(failure.get('message') or '')[:400]}")
          PY
```

- [ ] **Step 4: Run the tests to verify they pass, and lint**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree && python3 -m unittest discover -s scripts -t scripts -v && python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/cli-contract.yml")); print("yaml ok")'`
Expected: every test `ok`, summary `OK`, then `yaml ok` (`actionlint` instead when installed; it is what flags a `${{ inputs.* }}` interpolated into a `run:` line, which both workflows avoid by `env:`).

- [ ] **Step 5: Prove the annotation script on a real failure**

Run, from the worktree root (reuses D1 step 9's recipe): `python3 -I scripts/make_drift.py init-model-key build/drift/recording && (./gradlew test --tests '*ContractTest*' -Dtandem.contractDir="$PWD/build/drift/recording" --rerun || true) && python3 -I - build/test-results/test <<'PY'` … (the step's script, pasted) … `PY`
Expected: one line `::error title=CLI contract::live transcripts decode with every core type recognised: contract-hello.ndjson: no system/init`. Then `rm -rf build/drift`.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/cli-contract.yml scripts/test_workflows.py
git commit -m "ci: the nightly redacts session-id.jsonl before upload and names failed assertions"
```

---

### Task D4: Docs

**Files:**
- Modify: `CLAUDE.md` ("Commands" list, after the `scripts/record-scenario.sh` bullet; one sentence under "Working in this repo")
- Unchanged, checked: `docs/launch.md`, `docs/protocol.md`

**Interfaces:**
- Consumes: the skill's argument string (D1), the dispatch inputs `rehearse` and `mode` (D2).

- [ ] **Step 1: Add the loop to `CLAUDE.md`**

Under "Commands", after the `scripts/record-scenario.sh` bullet, add exactly:

```markdown
- `.github/workflows/drift-fix.yml` runs after every red nightly `CLI contract` run: it classifies the red (`scripts/classify_drift.py`) and, for decoder or reader drift, runs the repository skill `.claude/skills/fix-contract-drift/SKILL.md`, which promotes the run's `contract-recording` artifact to a fixture, patches the decoder or reader and opens a pull request labelled `contract-drift`; a run the CLI did not answer is reported on the issue "CLI contract: the CLI did not answer" instead. By hand: `gh run download <run id> -n contract-recording -D build/drift/recording`, then `/fix-contract-drift run=<run url> recording=build/drift/recording classification=build/drift/classification.json rehearsal=false date=<YYYY-MM-DD>`. Rehearsal (needs `main`: the federation rule admits nothing else): `gh workflow run cli-contract.yml --ref main -f drift=init-model-key` (or `transcript-content-key`) makes the nightly mutate its own recording (`scripts/make_drift.py --from`) so the fixer fires through `workflow_run` as for a real red; `gh workflow run drift-fix.yml --ref main -f rehearse=true -f mode=<mode>` runs the fixer alone on the mutated `history-reply` fixture; `gh workflow run drift-fix.yml --ref main -f run_id=<id>` fixes, or reports, a red `CLI contract` run now (a run the CLI did not answer rehearses the kind-1 path with no spend). Either draft PR is closed, not merged. Repeat a rehearsal after any change to the skill, `classify_drift.py`, `make_drift.py`, `cli-contract.yml` or `drift-fix.yml`.
```

Under "Working in this repo", after the "Work on a feature branch" bullet, add exactly:

```markdown
- A red nightly `CLI contract` run usually already has the loop's pull request (`gh pr list --label contract-drift`): review that instead of fixing by hand (docs/launch.md, tactic 7).
```

- [ ] **Step 2: Check the docs that stay unchanged**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree && git diff --stat 72ae807 -- docs/launch.md docs/protocol.md && grep -c 'drift-fix.yml' CLAUDE.md`
Expected: no diff lines for the two docs; `1` (the one Commands bullet; `grep -c` counts lines).

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md names the drift loop and its rehearsal"
```

---

### Task D5: The rehearsal

**Files:**
- None in the repository (the rehearsal's own pull requests are opened by the loop and closed by the owner).

**Interfaces:**
- Consumes: D1–D4 merged to `main`; the owner's one-time preparation, in this order and before any rehearsal: a ruleset on `main`, enforcement active, requiring a pull request, one approving review, "Require review from Code Owners" and "Require approval of the most recent reviewable push" (`.github/CODEOWNERS` with `* @daarko10` is on `main` once D2 is merged), with the Claude App never on its bypass list and the repository admin role on it (so that the App, which is no code owner and cannot approve its own push, can never push to `main` or merge a PR even if the tool allowlist regresses, although it can approve a PR it did not author; the owner, the only code owner, cannot approve their own PRs and merges them through the bypass; classic branch protection alone does not pass `Require a protected main`, nor does a ruleset without the two review options), the repository setting "Allow GitHub Actions to create and approve pull requests" kept off, then confirms the Claude GitHub App is still installed on `daarko10/tandem` (it is: spec open item 1, closed). A ruleset on a private repository owned by a personal account is enforced only with GitHub Pro: on Free the repository goes public before the ruleset and the rehearsals, and D5 waits for that. Before D5 the owner runs `gh api repos/daarko10/tandem/rules/branches/main` and sees the `pull_request` rule with `required_approving_review_count` of at least 1, `require_code_owner_review` true and `require_last_push_approval` true (what the endpoint lists for an unenforced ruleset is not known, so the check follows the enforcement).

- [ ] **Step 1: Open the pull request for `ci/drift-loop` and hand the merge to the owner**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree && git push -u origin ci/drift-loop && gh pr create --base main --title "ci: the drift loop" --body-file <body>`
Body: context (a red nightly becomes a PR; what each file does), approach (the recording is the fixture; kind 1 is reported, not fixed; one open fix at a time; the tool allowlist of the skill's own invocations with an exact push rule, the deny rules of the action's `settings` input; the edit-allowlist step, red whenever the gate proceeded and no PR exists), testing notes (`python3 -m unittest discover -s scripts -t scripts` green, both rehearsal modes reproduced against the real decoder locally in D1 step 9, YAML parsed; not verified: the `workflow_run` firing and the rehearsal itself, which need `main`), and the two owner steps before the first rehearsal. No attribution lines.
Expected: a PR URL; `Build` green. The owner merges (squash). Nothing waits on this beyond the merge itself: the rehearsals start the moment `main` has the workflows.

- [ ] **Step 2: Rehearse the decoder drift through the real trigger**

Run: `gh workflow run cli-contract.yml --ref main -f drift=init-model-key && sleep 20 && gh run list --workflow cli-contract.yml --limit 1 --json databaseId --jq '.[0].databaseId' | xargs -I{} gh run watch {}; sleep 30; gh run list --workflow drift-fix.yml --limit 1 --json databaseId --jq '.[0].databaseId' | xargs -I{} gh run watch {} --exit-status`
Expected: the `CLI contract` run is red at "Decode with Tandem's protocol layer…": its "Mutate the recording for a rehearsal" step printed `wrote build/contract/…` four times, "Name the failed assertions" printed `live transcripts decode with every core type recognised: contract-hello.ndjson: no system/init`, and `contract-recording` was uploaded. A `Drift fix` run fired by `workflow_run` follows (`gh run view <id> --json event` says `workflow_run`); in its log (`gh run view <id> --log`): "Name the red run" prints `fixing run <the nightly's url> (<today>)`; "Fetch the recording" downloads the artifact (no "the run left no recording"); "Classify before Gradle" prints `"kind": "unknown"` (no test results yet: only kind 1 is told here) and sets `rehearsal=true` from the marker; "Classify" prints `"kind": "decoder"` with detail `contract-hello.ndjson: no system/init`; "Require a protected main" prints `main requires a pull request, an approving review from a code owner and approval of the last push (ruleset)` (red with `No ruleset on main requires a pull request with an approving review from a code owner and approval of the most recent push`, or `main's .github/CODEOWNERS has no line owning every file`, until the owner's first step is done); "Fix the drift" shows the action's federation line with the OIDC subject of a `workflow_run` job (expected `repo:daarko10@34120174/tandem@1421183639:ref:refs/heads/main`; record it in open item 2 of the spec); "Enforce the edit allowlist" (the `enforce` job, after `fix`) prints `PR #<n> stays within the allowlist`; the run is green. Then `gh pr list --label contract-drift --json number,title,isDraft,labels,headRefName`: one draft PR, title starting `[rehearsal] fix(protocol): follow CLI <the run's claude --version>:`, labels `contract-drift` and `rehearsal`, head `drift/<today>-<the nightly's run id>`, with `src/test/resources/fixtures/rehearsal-<today>.ndjson` (recorded by the newest CLI, not `history-reply`), its transcript twin, tests, `Ndjson.kt` reading `model_id` as well as `model`, and a `docs/protocol.md` change; its commit authored by `claude[bot]` (set by the action). `gh pr checks <n>` shows the `Build` workflow running (the App token worked). If the "Fix the drift" step is red at "Exchanging OIDC token for app token", the Claude GitHub App is not installed (spec open item 1): the action never falls back to `GITHUB_TOKEN`. If that step is green but "Enforce the edit allowlist" is red with `pushed no branch and opened no PR`, read the action's log: a denied command, or the action's workflow-validation skip.

- [ ] **Step 3: The owner closes the rehearsal PR, then rehearse the reader drift**

The owner closes PR `<n>` (`gh pr close <n> --comment "rehearsal"`), which re-arms the loop. Run: `gh workflow run cli-contract.yml --ref main -f drift=transcript-content-key` and watch both runs as in step 2.
Expected: "Classify" prints `"kind": "reader"` with a detail ending `: no user entry` (naming `contract-hello.jsonl` or `session-id.jsonl`: `ContractTest` stops at the first failing transcript in directory order); a draft PR `[rehearsal] fix(transcript): …` whose diff touches `TranscriptReader.kt` and adds both `rehearsal-<today>.jsonl` and `rehearsal-<today>-session-id.jsonl` (the skill copies both transcripts for every reader drift, whichever the detail names); the allowlist step green. The owner closes it.

- [ ] **Step 4: Prove the open-PR skip and the fixer-only rehearsal once**

With the second rehearsal PR still open, run the fixer alone: `gh workflow run drift-fix.yml --ref main -f rehearse=true -f mode=init-model-key` and watch it.
Expected: "Mutate a recording for the rehearsal" writes the four files; "Classify before Gradle" sets `rehearsal=true`; "Classify" prints `"kind": "decoder"`; the run is green without an agent step: "Skip when a fix is already waiting" prints `a fix is already waiting in PR #<n>` and "Fix the drift" is skipped; the `remind` job (its step "Comment on the waiting pull request") comments `The nightly of <today> is still red: …` on the open PR, and `enforce` is skipped. Then the owner closes that PR.

- [ ] **Step 5: Rehearse the kind-1 path with an old red run**

Steps 2-4 never exercise the commonest red: the fetch with no artifact, the precheck short-circuit (Gradle skipped) and "Report a run the agent cannot fix" (the most hand-written `jq`/`gh` shell in the workflow, pinned by `test_workflows.py` only as string presence) would otherwise first run on a real night, and a wrong report step would end every kind-1 night as a red `Drift fix` run, training the owner to ignore the signal the loop relies on. It is provable for free with the existing `run_id` input. Run: `gh workflow run drift-fix.yml --ref main -f run_id=<id>` with the id of one of the six red scheduled `CLI contract` runs, 2026-10-17 to 2026-10-21 (`gh run list --workflow cli-contract.yml --status failure --json databaseId,createdAt,url`), choosing one that left no artifact or whose artifact is still within its 14-day retention (an expired artifact is listed but cannot be downloaded, so "Fetch the recording" reds the job by design: that is the fetch-failure path, not kind 1), then `sleep 20 && gh run list --workflow drift-fix.yml --limit 1 --json databaseId --jq '.[0].databaseId' | xargs -I{} gh run watch {} --exit-status`.
Expected: "Name the red run" prints `fixing run <that run's url> (<its date>)`; "Fetch the recording" prints `the run left no recording` (or downloads a stream whose `result` has `is_error: true`); "Classify before Gradle" prints `"kind": "cli-did-not-answer"` and `rehearsal=false`; "Reproduce the red", "Classify", "Skip when a fix is already waiting", "Fix the drift" and "Enforce the edit allowlist" are skipped; "Report a run the agent cannot fix" creates the issue "CLI contract: the CLI did not answer" with the label `contract-drift` (or comments on it when one is open); the job is green with no Gradle and no agent spend. Then `gh issue view <n>` shows the run link, `kind`, the CLI version (`unknown` when the stream had no `system/init`), `detail` and `files` (`none` without an artifact). Keep the issue open or close it; either is fine for the next rehearsal.

- [ ] **Step 6: Record what the rehearsal answered**

Anything the rehearsal showed that differs from the skill or the workflow (the token subject line, a `gh` command that needed a different flag) goes into a follow-up PR on a branch `ci/drift-loop-fixes` with the same tests, and a rehearsal of the mode it touched is repeated after it. Nothing waits on a scheduled run: the actor of a scheduled nightly is already recorded as `daarko10` (six scheduled runs, 2026-10-17 to 2026-10-21; spec open item 4, closed), so the first real firing is trusted as soon as both rehearsals and the kind-1 dispatch passed. The schedule fires hours after 03:17 UTC under load; the date the fixer takes from the run's `createdAt` tolerates that. The milestone bullet in `docs/launch.md` stays as it is: it closes only when a non-rehearsal `contract-drift` PR has been merged and the next nightly was green, and the owner appends the PR number, CLI version and fixture name then.

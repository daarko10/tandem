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
- Exact values from the spec: workflow name `Drift fix`; job `fix`; `concurrency.group: drift-fix`, `cancel-in-progress: false`; job `timeout-minutes: 90`, action step `timeout-minutes: 75`; permissions `id-token: write`, `contents: write`, `pull-requests: write`, `issues: write`, `actions: read`; artifact `contract-recording`; recording dir `build/drift/recording`; classification `build/drift/classification.json`; re-recording dir (unused today) `build/drift/recorded`; labels `contract-drift` and `rehearsal`; standing issue title `CLI contract: the CLI did not answer`; branch `drift/<YYYY-MM-DD>-<run id>`; fixture names `drift-<YYYY-MM-DD>` and `rehearsal-<YYYY-MM-DD>` (UTC date of the red run); kinds `cli-did-not-answer`, `decoder`, `reader`, `unknown`; detail cut at 400 characters; `--model claude-opus-5-5`, `--max-turns 60`; the tool allowlist `Read,Grep,Glob,Edit,Write,Bash(./gradlew check),Bash(./gradlew test:*),Bash(python3 -I scripts/classify_drift.py:*),Bash(python3 -I scripts/redact_fixture.py:*),Bash(python3 -m unittest discover -s scripts -t scripts),Bash(gh pr create:*),Bash(gh issue list:*),Bash(gh issue create:*),Bash(gh issue comment:*),Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(git add:*),Bash(git commit:*),Bash(git switch:*),Bash(git clean:*),Bash(git push -u origin HEAD),Bash(cp:*),Bash(ls:*),Bash(cat:*),Bash(grep:*),Bash(date:*)` (27 entries: the invocations the skill prescribes, the push exact); the action's `settings` input `{"permissions": {"deny": ["Edit(.github/**)", "Write(.github/**)", "Edit(build.gradle.kts)", "Write(build.gradle.kts)", "Edit(settings.gradle.kts)", "Write(settings.gradle.kts)", "Edit(scripts/**)", "Write(scripts/**)", "Edit(.claude/**)", "Write(.claude/**)", "Edit(CLAUDE.md)", "Write(CLAUDE.md)", "Bash(claude:*)"]}}` (13 rules); the action step `id: agent`; edit allowlist `src/main/kotlin/dev/daarkolabs/tandem/protocol/`, `src/main/kotlin/dev/daarkolabs/tandem/session/transcript/`, `src/test/`, `docs/protocol.md`; the four Anthropic identifiers exactly as `cli-contract.yml` carries them in `env:` (`fdrl_019BGJmAMDiAL6DmAQENbhfk`, `8336fe5f-a7f7-4255-9fe0-7f594d5a8ec8`, `svac_01F45vdtRBnPSRZHXY8ek1fn`, `wrkspc_012JJzfEG8tDKeta7pe3CCa5`).
- The workflow passes `date=<YYYY-MM-DD>` (the red run's UTC date) as the prompt's fifth argument, so the skill never needs `actions: read` through the App token to look the run up. Run by hand, `date=` defaults to today in UTC. The prompt's `rehearsal=` comes from the marker file `rehearsal` in the recording (written by `make_drift.py`), not from a dispatch input: the real rehearsal arrives through `workflow_run` like any red.
- The fixer's tool allowlist names the invocations the skill prescribes (spec, runtime step 8), and the push rule is exact: `Bash(git push -u origin HEAD)`. The installed CLI matches a rule without `*` as the whole command and a `:*` rule as the command itself or the prefix followed by a space, so `Bash(git push -u origin drift/:*)` would never match `git push -u origin drift/<date>-<run id>` and the loop could never open a PR. The list guides the agent; what protects `main` is branch protection (a pull request plus one approving review) and the App token's scope, since the token is in the agent's environment and the `origin` URL. The skill prescribes no command outside the list and no compound command whose parts are not all on it (in headless mode a denied call is a dead end, not a prompt): the script tests run as `python3 -m unittest discover -s scripts -t scripts` from the repository root, the leak stop removes copies with `git clean -f --`, the labels are created by the workflow and never by the skill, the push is `git push -u origin HEAD` from the `drift/…` branch, and no `gh auth setup-git` or identity fallback exists (the action sets the `origin` URL and `user.name`/`user.email` before the CLI starts). The deny rules of the action's `settings` input keep the agent out of `.github/`, the build files, `scripts/`, `.claude/`, `CLAUDE.md` and a second `claude` before the push; the allowlist step checks after it.
- `scripts/*.py` run under `python3 -I` and import only the standard library (PyYAML only in `test_workflows.py`, which skips itself when PyYAML is missing, except under `GITHUB_ACTIONS`, where it raises). Tests run with `python3 -m unittest discover -s scripts -t scripts -v` from the worktree root (the skill's spelling), the same set `build.yml`'s "Script tests" step runs as `cd scripts && python3 -m unittest`.
- The federation rule admits only `refs/heads/main`: the rehearsal (D5) can run only after `ci/drift-loop` is merged. Everything else is verified on the branch before that, so the merge is not a wait: D1 proves both rehearsal modes against the real decoder locally, D2 and D3 prove the YAML, and the rehearsal follows the merge immediately.

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
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree/scripts && python3 -m unittest test_make_drift -v`
Expected: `ModuleNotFoundError: No module named 'make_drift'`

- [ ] **Step 7: Implement `scripts/make_drift.py`**

Docstring: usage (`make_drift.py <mode> <out-dir> [--from <recording-dir>]`), the two modes and what each breaks (`init-model-key`: `system/init.model` → `model_id`, so `Ndjson` decodes the line to `Unknown` and the decode test fails with "no system/init"; `transcript-content-key`: every transcript `user` line's `message.content` → `message.blocks`, so the reader yields no user entry), the `--from` form (the nightly's rehearsal mutates its own `build/contract` in place), the marker `rehearsal` (the fixer reads it as `rehearsal=true`), and that it never writes under `src/test/resources/fixtures`. `FIXTURES` is computed from `os.path.dirname(os.path.abspath(__file__))` (as `test_workflows.py`'s `ROOT` is): `python3 -I scripts/make_drift.py` gives a relative `__file__`, and a relative `FIXTURES` would make the fixture reads depend on the caller's cwd. A mutated line is re-serialized with `json.dumps(obj, ensure_ascii=False, separators=(",", ":"))` plus `"\n"` (the redactor's format); every other line is copied byte for byte. `main` checks `mode in MODES` (else `sys.exit(f"make_drift: unknown mode {mode!r}; one of {', '.join(MODES)}")`), then refuses when `os.path.realpath(out_dir)` equals or starts with `os.path.realpath(FIXTURES) + os.sep` (`sys.exit(f"make_drift: refusing to write under the fixtures directory: {out_dir}")`), then reads every source into memory before writing anything: without `source`, the two fixture files (the twin serves as both transcripts); with `source`, `<source>/contract-hello.ndjson`, `<source>/transcripts/contract-hello.jsonl` and `<source>/transcripts/session-id.jsonl`, each mutated from its own lines (`sys.exit(f"make_drift: no {rel} in {source}")` when one is missing, before any write, so an in-place run never half-mutates). Then it writes the three outputs and `<out>/rehearsal` holding `mode + "\n"` (`os.makedirs(..., exist_ok=True)`) and prints `wrote <path>` per file to stderr. Argument parsing under `if __name__ == "__main__":`: `sys.argv[1:]` is `[mode, out]` or `[mode, out, "--from", dir]`; anything else prints `__doc__` to stderr and exits 2.

- [ ] **Step 8: Run the tests to verify they pass, with the skill's own command**

Run, from the worktree root, the spelling the skill prescribes (no `cd`: it is not on the fixer's tool allowlist): `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree && python3 -m unittest discover -s scripts -t scripts -v`
Expected: every test of `test_classify_drift` (18), `test_make_drift` (8), `test_copy_transcript`, `test_drive_cli` and `test_redact_fixture` reports `ok`; the summary ends `OK`. This proves the command the skill's section 7 and the PR body's "Verified" bullet name; it is the same set `cd scripts && python3 -m unittest` runs.

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
for its malformed version (still `Unknown`, never a throw).

## 5. Patch minimally
Decoder drift: `src/main/kotlin/dev/daarkolabs/tandem/protocol/Ndjson.kt` and `CliMessage.kt` only; a renamed field is
read under both names, a new required field is read optionally, a new type or subtype gets a typed message only when
Tandem needs it and `Unknown(raw)` otherwise. For the detail `no assistant line, result text present`, write first a
test that decodes the new fixture and asserts a `CliMessage.Assistant`; the fix reads the new type beside `assistant`.
Reader drift: `TranscriptReader.kt` and `TranscriptChain.kt` under
`src/main/kotlin/dev/daarkolabs/tandem/session/transcript/`. Validate every field, no IntelliJ imports in `protocol/`,
no logging of prompt or file contents. Edits are allowed only under `src/main/kotlin/dev/daarkolabs/tandem/protocol/`,
`src/main/kotlin/dev/daarkolabs/tandem/session/transcript/`, `src/test/` and `docs/protocol.md`; the workflow closes a
PR that touches anything else. If `koverVerify` fails, a test must exercise the new branch; never lower the bound.

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
- Test: `scripts/test_workflows.py`

**Interfaces:**
- Consumes: `scripts/classify_drift.py` and `scripts/make_drift.py` (D1, command lines as in their usage), the skill name and argument string (D1), `anthropics/claude-code-action@v1` inputs `prompt`, `claude_args`, `settings`, `anthropic_federation_rule_id`, `anthropic_organization_id`, `anthropic_service_account_id`, `anthropic_workspace_id` (verified against the action's `action.yml` at tag `v1`; the action exposes `conclusion` and `github_token` outputs and revokes the token after the run, so later steps use `github.token`).
- Produces: the workflow as below; `scripts/test_workflows.py` with `load(path) -> dict` (PyYAML; note that PyYAML reads the `on` key as the boolean `True`) and `allowed_tools(text) -> set[str]`, which D3 extends.

- [ ] **Step 1: Write the failing workflow tests**

`scripts/test_workflows.py`, guarded by `yaml = importlib.import_module("yaml")` in a `try` (`yaml = None` on `ImportError`), followed at import time by `if yaml is None and os.environ.get("GITHUB_ACTIONS"): raise RuntimeError("PyYAML is required in CI")`, so the only tests pinning both workflows never skip silently in `build.yml`'s "Script tests" step, and `@unittest.skipUnless(yaml, "PyYAML is not installed")` on the class for a machine without it. Imports `json` as well (the `settings` input is JSON). `ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")`; `DRIFT = os.path.join(ROOT, ".github/workflows/drift-fix.yml")`; `SKILL = os.path.join(ROOT, ".claude/skills/fix-contract-drift/SKILL.md")`. Helpers: `load(path)` (`yaml.safe_load`), `steps(workflow, job)`, `step(workflow, job, name)`, `frontmatter(path)` (the YAML between the first two `---` lines), `tools(text)` (`{t.strip() for t in text.split(",")}` of the value after `--allowedTools`, quotes stripped).

```python
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
    self.assertEqual({"id-token": "write", "contents": "write", "pull-requests": "write", "issues": "write", "actions": "read"}, wf["permissions"])
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
    self.assertTrue(agent["with"]["prompt"].startswith("/fix-contract-drift run="))
    for part in ("recording=build/drift/recording", "classification=build/drift/classification.json", "rehearsal=", "date="):
        self.assertIn(part, agent["with"]["prompt"])
    self.assertIn("--model claude-opus-5-5", agent["with"]["claude_args"])
    self.assertIn("--max-turns 60", agent["with"]["claude_args"])

def test_the_skill_and_the_action_allow_the_same_tools(self):
    front = frontmatter(SKILL)
    self.assertEqual("fix-contract-drift", front["name"])
    self.assertEqual({t.strip() for t in front["allowed-tools"].split(",")}, tools(step(load(DRIFT), "fix", "Fix the drift")["with"]["claude_args"]))

def test_the_steps_run_in_the_spec_s_order(self):
    names = [s.get("name") for s in steps(load(DRIFT), "fix")]
    expected = ["Free disk space", "Check out main", None, None, "Ensure the labels exist", "Name the red run", "Fetch the recording",
                "Mutate a recording for the rehearsal", "Classify before Gradle", "Reproduce the red", "Classify",
                "Report a run the agent cannot fix", "Skip when a fix is already waiting", "Fix the drift", "Enforce the edit allowlist"]
    self.assertEqual(expected, names)
    wf = load(DRIFT)
    self.assertTrue(step(wf, "fix", "Reproduce the red")["continue-on-error"])
    self.assertTrue(step(wf, "fix", "Enforce the edit allowlist")["if"].startswith("always()"))
    for name in ("Name the red run", "Fetch the recording", "Report a run the agent cannot fix", "Skip when a fix is already waiting", "Enforce the edit allowlist"):
        self.assertEqual("${{ github.token }}", step(wf, "fix", name)["env"]["GH_TOKEN"])
    self.assertIn("conclusion", step(wf, "fix", "Name the red run")["run"])      # a green or foreign run is refused before any spend
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
    self.assertNotIn("|| echo", fetch)                                            # any other download failure fails the job
    precheck = step(wf, "fix", "Classify before Gradle")
    self.assertEqual("precheck", precheck["id"])
    self.assertIn("build/drift/precheck.json", precheck["run"])
    self.assertIn("build/drift/recording/rehearsal", precheck["run"])            # the marker makes the run a rehearsal
    for name in ("Reproduce the red", "Classify"):
        self.assertIn("steps.precheck.outputs.kind != 'cli-did-not-answer'", step(wf, "fix", name)["if"])
    report = step(wf, "fix", "Report a run the agent cannot fix")
    self.assertIn("steps.precheck.outputs.kind == 'cli-did-not-answer'", report["if"])
    self.assertIn("precheck.json", report["run"])
    enforce = step(wf, "fix", "Enforce the edit allowlist")
    self.assertIn('repos/$GITHUB_REPOSITORY/branches/$BRANCH', enforce["run"])    # the branch is read through the API with GH_TOKEN: git has no credential here
    self.assertNotIn("git ls-remote", enforce["run"])
    self.assertNotIn('no branch was pushed"; exit 0', enforce["run"])              # no PR after the gate proceeded is red, never green and silent
    self.assertIn("pushed no branch and opened no PR", enforce["run"])
    self.assertIn("pushed $BRANCH but opened no PR", enforce["run"])
    self.assertEqual("${{ steps.classify.outputs.kind }}", enforce["env"]["KIND"])
    self.assertEqual("${{ steps.agent.outputs.conclusion }}", enforce["env"]["AGENT"])
    self.assertIn("rehearsal=${{ steps.precheck.outputs.rehearsal }}", step(wf, "fix", "Fix the drift")["with"]["prompt"])

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
    for rule in ("Edit(.github/**)", "Write(.github/**)", "Edit(build.gradle.kts)", "Edit(settings.gradle.kts)", "Edit(scripts/**)", "Write(scripts/**)",
                 "Edit(.claude/**)", "Write(.claude/**)", "Edit(CLAUDE.md)", "Bash(claude:*)"):
        self.assertIn(rule, deny)
```

(The two `None` names are the `actions/setup-java@v6` and `gradle/actions/setup-gradle@v6` steps, unnamed as in `build.yml`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree/scripts && python3 -m unittest test_workflows -v`
Expected: `FileNotFoundError: … drift-fix.yml` in every test.

- [ ] **Step 3: Write `.github/workflows/drift-fix.yml`**

```yaml
# Turns a red nightly `CLI contract` run into a pull request: downloads the run's redacted recording, classifies the red
# (scripts/classify_drift.py), and runs the repository skill fix-contract-drift with Workload Identity Federation (the same
# four identifiers as cli-contract.yml; the federation rule admits runs on main only). A run the CLI did not answer is
# reported on the issue "CLI contract: the CLI did not answer" instead, and an open PR labelled contract-drift skips the
# agent. Nothing here merges or publishes. Rehearsal: `gh workflow run cli-contract.yml -f drift=<mode>` makes the nightly
# mutate its own recording, so this workflow fires as for a real red; workflow_dispatch with rehearse=true runs the fixer
# alone on a mutated fixture. CLAUDE.md names the loop.
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
  contents: write # the fix branch
  pull-requests: write
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
    # github.ref is refs/heads/main for a workflow_run job; a dispatch from another branch is skipped here instead of
    # failing at the federation exchange after the disk and Gradle setup.
    if: >-
      github.repository == 'daarko10/tandem' && github.ref == 'refs/heads/main' &&
      (github.event_name == 'workflow_dispatch' ||
       (github.event.workflow_run.conclusion == 'failure' && github.event.workflow_run.head_branch == 'main'))
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
      - uses: gradle/actions/setup-gradle@v6
      - name: Ensure the labels exist
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh label create contract-drift --force --color B60205 --description "Opened or reported by the drift loop (drift-fix.yml)"
          gh label create rehearsal --force --color FBCA04 --description "A drift-loop rehearsal: close, do not merge"
      # Outputs: id, url and UTC date of the red run (this run itself when rehearse=true), and rehearse=true|false.
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
            view="$(gh run view "$id" --json url,createdAt,conclusion,workflowName)"
            url="$(jq -r .url <<<"$view")"; date="$(jq -r '.createdAt[0:10]' <<<"$view")"
            if [ "$(jq -r .workflowName <<<"$view")" != "CLI contract" ] || [ "$(jq -r .conclusion <<<"$view")" != "failure" ]; then
              echo "::error title=Drift fix::Run $id is not a failed CLI contract run: $(jq -r '[.workflowName, .conclusion] | join(", ")' <<<"$view")."; exit 1
            fi
          fi
          printf 'id=%s\nurl=%s\ndate=%s\nrehearse=%s\n' "$id" "$url" "$date" "$([ "$REHEARSE" = "true" ] && echo true || echo false)" >> "$GITHUB_OUTPUT"
          echo "fixing run $url ($date)"
      # No artifact at all is a red of kind 1 (the record step wrote nothing), classified below; not an error here. Any
      # other failure of the download (a 403, an API outage, a renamed artifact, an expired retention) fails the job: it is
      # not "the CLI did not answer". build/drift is created here because the steps below write into it.
      - name: Fetch the recording
        if: steps.run.outputs.rehearse != 'true'
        env:
          GH_TOKEN: ${{ github.token }}
          RUN_ID: ${{ steps.run.outputs.id }}
        run: |
          set -euo pipefail
          mkdir -p build/drift
          present="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$RUN_ID/artifacts" --jq '[.artifacts[].name] | index("contract-recording")')"
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
      - name: Reproduce the red
        if: steps.precheck.outputs.kind != 'cli-did-not-answer'
        continue-on-error: true
        run: ./gradlew test --tests '*ContractTest*' -Dtandem.contractDir="$PWD/build/drift/recording"
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
      # One open fix at a time: a red that persists for a week opens one PR, not seven.
      - name: Skip when a fix is already waiting
        id: gate
        if: steps.classify.outputs.kind == 'decoder' || steps.classify.outputs.kind == 'reader'
        env:
          GH_TOKEN: ${{ github.token }}
          RUN_URL: ${{ steps.run.outputs.url }}
          DATE: ${{ steps.run.outputs.date }}
        run: |
          set -euo pipefail
          pr="$(gh pr list --label contract-drift --state open --json number --jq '.[0].number // empty')"
          if [ -n "$pr" ]; then
            gh pr comment "$pr" --body "The nightly of $DATE is still red: $RUN_URL. This pull request is still waiting; merge or close it to re-arm the loop."
            echo "proceed=false" >> "$GITHUB_OUTPUT"
          else
            echo "proceed=true" >> "$GITHUB_OUTPUT"
          fi
      # No github_token input: the action authenticates as the Claude GitHub App, sets the origin URL and the commit identity
      # before the CLI starts, and the PR it opens starts the Build workflow. The allowlist names the invocations the skill
      # prescribes; the push rule is exact (a :* rule matches the prefix followed by a space, so drift/:* would never match
      # the push). The deny rules keep the agent out of the files that run code before the push and out of a second CLI.
      # Neither is what protects main: branch protection and the App token's scope are (the spec's Safeguards).
      - name: Fix the drift
        id: agent
        if: steps.gate.outputs.proceed == 'true'
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
            --max-turns 60
            --allowedTools "Read,Grep,Glob,Edit,Write,Bash(./gradlew check),Bash(./gradlew test:*),Bash(python3 -I scripts/classify_drift.py:*),Bash(python3 -I scripts/redact_fixture.py:*),Bash(python3 -m unittest discover -s scripts -t scripts),Bash(gh pr create:*),Bash(gh issue list:*),Bash(gh issue create:*),Bash(gh issue comment:*),Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(git add:*),Bash(git commit:*),Bash(git switch:*),Bash(git clean:*),Bash(git push -u origin HEAD),Bash(cp:*),Bash(ls:*),Bash(cat:*),Bash(grep:*),Bash(date:*)"
          settings: |
            {"permissions": {"deny": ["Edit(.github/**)", "Write(.github/**)", "Edit(build.gradle.kts)", "Write(build.gradle.kts)", "Edit(settings.gradle.kts)", "Write(settings.gradle.kts)", "Edit(scripts/**)", "Write(scripts/**)", "Edit(.claude/**)", "Write(.claude/**)", "Edit(CLAUDE.md)", "Write(CLAUDE.md)", "Bash(claude:*)"]}}
      # The check that does not depend on the agent obeying the skill: a PR touching anything outside the allowlist is closed.
      # Once the gate proceeded, no PR is a red run: a pushed branch without one (the agent was stopped by the timeout or the
      # turn limit) is reported on the standing issue; no branch at all is a denied command, a redactor stop, an action that
      # skipped the agent, or an agent that gave up. The branch is read through the API with the workflow token: the checkout
      # has persist-credentials: false, the action revokes its App token before this step, and the repository is private, so
      # git itself would get a 401 here. No draft is opened: GITHUB_TOKEN cannot create a pull request unless the repository
      # setting "Allow GitHub Actions to create and approve pull requests" is on.
      - name: Enforce the edit allowlist
        if: always() && steps.gate.outputs.proceed == 'true'
        env:
          GH_TOKEN: ${{ github.token }}
          BRANCH: drift/${{ steps.run.outputs.date }}-${{ steps.run.outputs.id }}
          RUN_URL: ${{ steps.run.outputs.url }}
          KIND: ${{ steps.classify.outputs.kind }}
          AGENT: ${{ steps.agent.outputs.conclusion }}
        run: |
          set -euo pipefail
          pr="$(gh pr list --label contract-drift --state open --json number,headRefName --jq '[.[] | select(.headRefName | startswith("drift/"))][0].number // empty')"
          if [ -z "$pr" ]; then
            branch="$(gh api "repos/$GITHUB_REPOSITORY/branches/$BRANCH" --jq .name 2>/dev/null || true)"
            if [ -n "$branch" ]; then
              title="CLI contract: the CLI did not answer"
              body="The drift fix for $RUN_URL pushed the branch $BRANCH but opened no pull request: the agent was stopped (timeout or turn limit; its step ended '${AGENT:-skipped}'). Review the branch by hand."
              issue="$(gh issue list --label contract-drift --state open --search "\"$title\" in:title" --json number --jq '.[0].number // empty')"
              if [ -z "$issue" ]; then gh issue create --title "$title" --label contract-drift --body "$body"; else gh issue comment "$issue" --body "$body"; fi
              echo "::error title=Drift fix::The agent pushed $BRANCH but opened no PR; reported on the standing issue."; exit 1
            fi
            echo "::error title=Drift fix::The classification was $KIND but the agent pushed no branch and opened no PR (its step ended '${AGENT:-skipped}'): a denied command, a redactor stop (see the standing issue), the action skipped after a workflow-validation error, or the agent gave up; read the Fix the drift step's log."; exit 1
          fi
          offending="$(gh pr view "$pr" --json files --jq '.files[].path' \
            | grep -vE '^(src/main/kotlin/dev/daarkolabs/tandem/protocol/|src/main/kotlin/dev/daarkolabs/tandem/session/transcript/|src/test/|docs/protocol\.md$)' || true)"
          if [ -n "$offending" ]; then
            gh pr close "$pr" --comment "$(printf 'Closed by drift-fix.yml: the fix edits files outside the allowlist (protocol/, session/transcript/, src/test/, docs/protocol.md):\n\n%s' "$offending")"
            echo "::error title=Drift fix::PR #$pr edits files outside the allowlist; closed."; exit 1
          fi
          echo "PR #$pr stays within the allowlist"
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree/scripts && python3 -m unittest test_workflows -v`
Expected: `Ran 9 tests` … `OK` (not `skipped`: PyYAML 6 is installed in this sandbox).
- [ ] **Step 5: Lint the YAML**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree && if command -v actionlint >/dev/null; then actionlint .github/workflows/drift-fix.yml .github/workflows/cli-contract.yml; else python3 -c 'import yaml,sys; [yaml.safe_load(open(p)) for p in sys.argv[1:]]; print("yaml ok")' .github/workflows/drift-fix.yml .github/workflows/cli-contract.yml; fi`
Expected: `actionlint` prints nothing (exit 0), or `yaml ok`. `actionlint` is not installed in this sandbox and its download is refused by the proxy; the rehearsal (D5) is the remaining validation.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/drift-fix.yml scripts/test_workflows.py
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
- `.github/workflows/drift-fix.yml` runs after every red nightly `CLI contract` run: it classifies the red (`scripts/classify_drift.py`) and, for decoder or reader drift, runs the repository skill `.claude/skills/fix-contract-drift/SKILL.md`, which promotes the run's `contract-recording` artifact to a fixture, patches the decoder or reader and opens a pull request labelled `contract-drift`; a run the CLI did not answer is reported on the issue "CLI contract: the CLI did not answer" instead. By hand: `gh run download <run id> -n contract-recording -D build/drift/recording`, then `/fix-contract-drift run=<run url> recording=build/drift/recording classification=build/drift/classification.json rehearsal=false date=<YYYY-MM-DD>`. Rehearsal (needs `main`: the federation rule admits nothing else): `gh workflow run cli-contract.yml --ref main -f drift=init-model-key` (or `transcript-content-key`) makes the nightly mutate its own recording (`scripts/make_drift.py --from`) so the fixer fires through `workflow_run` as for a real red; `gh workflow run drift-fix.yml --ref main -f rehearse=true -f mode=<mode>` runs the fixer alone on the mutated `history-reply` fixture. Either draft PR is closed, not merged. Repeat a rehearsal after any change to the skill, `classify_drift.py`, `make_drift.py`, `cli-contract.yml` or `drift-fix.yml`.
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
- Consumes: D1–D4 merged to `main`; the owner's one-time preparation, in this order and before any rehearsal: branch protection on `main` requiring a pull request and one approving review (so that the App, which cannot approve its own PR and is not an administrator, can never merge one even if the tool allowlist regresses; the owner, an administrator, still merges their own PRs unless "Do not allow bypassing the above settings" is on), then the Claude GitHub App installed on `daarko10/tandem`.

- [ ] **Step 1: Open the pull request for `ci/drift-loop` and hand the merge to the owner**

Run: `cd /tmp/claude-0/-home-user-tandem/ee35bc45-05bb-55f2-a063-cf2c94eb6664/scratchpad/drift-worktree && git push -u origin ci/drift-loop && gh pr create --base main --title "ci: the drift loop" --body-file <body>`
Body: context (a red nightly becomes a PR; what each file does), approach (the recording is the fixture; kind 1 is reported, not fixed; one open fix at a time; the tool allowlist of the skill's own invocations with an exact push rule, the deny rules of the action's `settings` input; the edit-allowlist step, red whenever the gate proceeded and no PR exists), testing notes (`python3 -m unittest discover -s scripts -t scripts` green, both rehearsal modes reproduced against the real decoder locally in D1 step 9, YAML parsed; not verified: the `workflow_run` firing and the rehearsal itself, which need `main`), and the two owner steps before the first rehearsal. No attribution lines.
Expected: a PR URL; `Build` green. The owner merges (squash). Nothing waits on this beyond the merge itself: the rehearsals start the moment `main` has the workflows.

- [ ] **Step 2: Rehearse the decoder drift through the real trigger**

Run: `gh workflow run cli-contract.yml --ref main -f drift=init-model-key && sleep 20 && gh run list --workflow cli-contract.yml --limit 1 --json databaseId --jq '.[0].databaseId' | xargs -I{} gh run watch {}; sleep 30; gh run list --workflow drift-fix.yml --limit 1 --json databaseId --jq '.[0].databaseId' | xargs -I{} gh run watch {} --exit-status`
Expected: the `CLI contract` run is red at "Decode with Tandem's protocol layer…": its "Mutate the recording for a rehearsal" step printed `wrote build/contract/…` four times, "Name the failed assertions" printed `live transcripts decode with every core type recognised: contract-hello.ndjson: no system/init`, and `contract-recording` was uploaded. A `Drift fix` run fired by `workflow_run` follows (`gh run view <id> --json event` says `workflow_run`); in its log (`gh run view <id> --log`): "Name the red run" prints `fixing run <the nightly's url> (<today>)`; "Fetch the recording" downloads the artifact (no "the run left no recording"); "Classify before Gradle" prints `"kind": "unknown"` (no test results yet: only kind 1 is told here) and sets `rehearsal=true` from the marker; "Classify" prints `"kind": "decoder"` with detail `contract-hello.ndjson: no system/init`; "Fix the drift" shows the action's federation line with the OIDC subject of a `workflow_run` job (expected `repo:daarko10/tandem:ref:refs/heads/main`; record it in open item 2 of the spec); "Enforce the edit allowlist" prints `PR #<n> stays within the allowlist`; the run is green. Then `gh pr list --label contract-drift --json number,title,isDraft,labels,headRefName`: one draft PR, title starting `[rehearsal] fix(protocol): follow CLI <the run's claude --version>:`, labels `contract-drift` and `rehearsal`, head `drift/<today>-<the nightly's run id>`, with `src/test/resources/fixtures/rehearsal-<today>.ndjson` (recorded by the newest CLI, not `history-reply`), its transcript twin, tests, `Ndjson.kt` reading `model_id` as well as `model`, and a `docs/protocol.md` change; its commit authored by `claude[bot]` (set by the action). `gh pr checks <n>` shows the `Build` workflow running (the App token worked). If the "Fix the drift" step is red at "Exchanging OIDC token for app token", the Claude GitHub App is not installed (spec open item 1): the action never falls back to `GITHUB_TOKEN`. If that step is green but "Enforce the edit allowlist" is red with `pushed no branch and opened no PR`, read the action's log: a denied command, or the action's workflow-validation skip.

- [ ] **Step 3: The owner closes the rehearsal PR, then rehearse the reader drift**

The owner closes PR `<n>` (`gh pr close <n> --comment "rehearsal"`), which re-arms the loop. Run: `gh workflow run cli-contract.yml --ref main -f drift=transcript-content-key` and watch both runs as in step 2.
Expected: "Classify" prints `"kind": "reader"` with a detail ending `: no user entry` (naming `contract-hello.jsonl` or `session-id.jsonl`: `ContractTest` stops at the first failing transcript in directory order); a draft PR `[rehearsal] fix(transcript): …` whose diff touches `TranscriptReader.kt` and adds both `rehearsal-<today>.jsonl` and `rehearsal-<today>-session-id.jsonl` (the skill copies both transcripts for every reader drift, whichever the detail names); the allowlist step green. The owner closes it.

- [ ] **Step 4: Prove the open-PR skip and the fixer-only rehearsal once**

With the second rehearsal PR still open, run the fixer alone: `gh workflow run drift-fix.yml --ref main -f rehearse=true -f mode=init-model-key` and watch it.
Expected: "Mutate a recording for the rehearsal" writes the four files; "Classify before Gradle" sets `rehearsal=true`; "Classify" prints `"kind": "decoder"`; the run is green without an agent step: "Skip when a fix is already waiting" comments `The nightly of <today> is still red: …` on the open PR and "Fix the drift" is skipped. Then the owner closes that PR.

- [ ] **Step 5: Record what the rehearsal answered**

Anything the rehearsal showed that differs from the skill or the workflow (the token subject line, a `gh` command that needed a different flag) goes into a follow-up PR on a branch `ci/drift-loop-fixes` with the same tests, and a rehearsal of the mode it touched is repeated after it. Nothing waits on a scheduled run: the actor of a scheduled nightly is already recorded as `daarko10` (six scheduled runs, 2026-10-17 to 2026-10-21; spec open item 4, closed), so the first real firing is trusted as soon as both rehearsals passed. The schedule fires hours after 03:17 UTC under load; the date the fixer takes from the run's `createdAt` tolerates that. The milestone bullet in `docs/launch.md` stays as it is: it closes only when a non-rehearsal `contract-drift` PR has been merged and the next nightly was green, and the owner appends the PR number, CLI version and fixture name then.

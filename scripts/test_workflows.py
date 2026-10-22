"""Tests for the drift loop's workflows and the skill they run. Run from scripts/: python3 -m unittest

They read .github/workflows/drift-fix.yml (and cli-contract.yml) with PyYAML, which reads the `on` key as the boolean
True. Without PyYAML they skip, except under GITHUB_ACTIONS, where a missing PyYAML is an error: these are the only
tests pinning the workflows, and build.yml's "Script tests" step must never skip them silently.

The outcome tests (StepOutcomeTest) run a step's `run:` block under bash as GitHub does: `bash -e {0}` for a step
without `shell:` (no pipefail; every multi-command step sets its own), `-o pipefail` added for `shell: bash`, with a stub
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
# No Write(path) rule: the CLI (2.1.308) matches the Write, Edit and NotebookEdit tools against Edit(path) rules and never
# consults a Write(path) one, so each Edit rule below covers all three.
DENY = {"Edit(.github/**)", "Edit(build.gradle.kts)", "Edit(settings.gradle.kts)", "Edit(gradle.properties)", "Edit(gradle/**)",
        "Read(.git/**)", "Edit(.git/**)", "Edit(scripts/**)", "Edit(.claude/**)", "Edit(CLAUDE.md)", "Bash(claude:*)",
        "Bash(cp *..*)", "Bash(cp * * *)", "Bash(python3 -I scripts/redact_fixture.py *..*)", "Bash(python3 -I scripts/redact_fixture.py * * *)",
        "Bash(./gradlew check -x:*)", "Bash(./gradlew check --exclude-task:*)", "Bash(./gradlew check *-*)", "Bash(./gradlew test *check*)",
        "Bash(./gradlew compileKotlin:*)", "Bash(./gradlew compileTestKotlin:*)", "Bash(./gradlew buildPlugin:*)", "Bash(./gradlew verifyPlugin:*)",
        "Bash(ktlint:*)", "Bash(grep *.git/*)", "Bash(grep -r*)", "Bash(grep * -r*)", "Bash(grep -R*)", "Bash(grep * -R*)"}
# The invocations the skill prescribes, exactly: a rule added to both the skill and the action fails here.
ALLOWED = {"Read", "Grep", "Glob", "Edit", "Write", "Bash(./gradlew check)", "Bash(./gradlew test:*)", "Bash(python3 -I scripts/classify_drift.py:*)",
           "Bash(python3 -I scripts/redact_fixture.py src/test/resources/fixtures/* src/test/resources/fixtures/*)",
           "Bash(python3 -m unittest discover -s scripts -t scripts)", "Bash(gh pr create:*)", "Bash(gh issue list:*)", "Bash(gh issue create:*)",
           "Bash(gh issue comment:*)", "Bash(git status:*)", "Bash(git diff:*)", "Bash(git log:*)", "Bash(git add:*)", "Bash(git commit:*)",
           "Bash(git switch:*)", "Bash(git clean:*)", "Bash(git push -u origin HEAD)", "Bash(cp build/drift/recording/* src/test/resources/fixtures/*)",
           "Bash(grep:*)"}
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
    """Runs the step's `run:` under bash as GitHub does (`bash -e`, plus `-o pipefail` for `shell: bash`), in an empty working directory (plus `files`), with the stub gh
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
        shell = ["bash", "--noprofile", "--norc", "-e"] + (["-o", "pipefail"] if s.get("shell") == "bash" else [])
        proc = subprocess.run(shell + [script], cwd=work, env=full, capture_output=True, text=True, timeout=60)
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
        self.assertEqual(120, job["timeout-minutes"])                                 # the agent step's 75 plus a cold-cache setup (a nightly took 23 minutes): the step's timeout fires first, and the job ends with its outputs
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

    def test_the_fix_job_s_steps_run_in_order(self):
        names = [s.get("name") for s in steps(load(DRIFT), "fix")]
        expected = ["Check out main", "Ensure the labels exist", "Name the red run", "Fetch the recording", "Mutate a recording for the rehearsal",
                    "Classify before Gradle", "Free disk space", None, None, "Reproduce the red", "Classify",
                    "Report a run the agent cannot fix", "Skip when a fix is already waiting", "Require a protected main", "Fix the drift"]
        self.assertEqual(expected, names)                                             # the allowlist step is the enforce job's, after this job
        wf = load(DRIFT)
        labels = step(wf, "fix", "Ensure the labels exist")
        self.assertIn("gh label create contract-drift --force", labels["run"])        # the gate, the report and the PR key on it
        self.assertIn("gh label create rehearsal --force", labels["run"])             # the skill's `gh pr create --label rehearsal` fails after the spend without it
        self.assertIn("gh label create drift-halted --force", labels["run"])          # the enforce job's halt and the gate key on it
        self.assertEqual("${{ github.token }}", labels["env"]["GH_TOKEN"])
        self.assertLess(names.index("Ensure the labels exist"), names.index("Fix the drift"))
        self.assertEqual(["Enforce the edit allowlist"], [s.get("name") for s in steps(wf, "enforce")])
        self.assertEqual(["Comment on the waiting pull request"], [s.get("name") for s in steps(wf, "remind")])
        self.assertTrue(step(wf, "fix", "Reproduce the red")["continue-on-error"])
        self.assertIn("--rerun", step(wf, "fix", "Reproduce the red")["run"])        # the build cache never replays a pass on another recording
        self.assertEqual(30, step(wf, "fix", "Reproduce the red")["timeout-minutes"])   # a hung download or test is a failed reproduction, not the job's cancellation
        self.assertEqual(5, step(wf, "fix", "Fetch the recording")["timeout-minutes"])   # 75 + 30 + 5 leaves the rest of the job's 120 to the setup
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
            if len(s.get("run", "").strip().splitlines()) > 1 and s.get("name") != "Ensure the labels exist":
                self.assertTrue(s["run"].startswith("set -euo pipefail\n"), s.get("name"))   # GitHub's default shell is bash -e, without pipefail
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
        self.assertIn('select(.type == "pull_request" and (.parameters.required_approving_review_count // 0) >= 1 and .parameters.require_code_owner_review == true and .parameters.require_last_push_approval == true) | .ruleset_id | tostring] | unique | join(" ")', protect["run"])   # an App can approve a PR it did not author: a code owner's review of the last push is what it cannot give
        self.assertNotIn(".protected", protect["run"])                                # classic protected: true accepts a direct push when no option is ticked
        self.assertIn('if [[ "$ids" =~ ^[0-9]+( [0-9]+)*$ ]]; then', protect["run"])   # fail closed: only one or more ruleset ids pass
        self.assertIn('if ! ruleset="$(gh api "repos/$GITHUB_REPOSITORY/rulesets/$id" 2>/dev/null)"; then', protect["run"])   # by exit status: a failed read refuses without a verdict
        self.assertIn('select(.actor_type == "Integration")', protect["run"])          # an App on the bypass list defeats the rule, whatever its bypass mode
        self.assertLess(protect["run"].index("rules/branches/main"), protect["run"].index("rulesets/$id"))
        self.assertLess(protect["run"].index("rulesets/$id"), protect["run"].index("CODEOWNERS?ref=main"))
        self.assertIn('if ! owners="$(gh api -H "Accept: application/vnd.github.raw+json" "repos/$GITHUB_REPOSITORY/contents/.github/CODEOWNERS?ref=main" 2>/dev/null)"; then', protect["run"])   # the code-owner review binds only owned files; by exit status: a 5xx is not "no owner line"
        self.assertIn("status=\"$(jq -r '.status // empty' <<<\"$owners\" 2>/dev/null || true)\"", protect["run"])
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
        stale = protect["run"].split("delete it to retry", 1)[1]                       # last: a drift/ branch without an open PR (a stopped agent's push, a closed fix's branch) would start a fresh run every night
        self.assertIn('if ! branches="$(gh api --paginate "repos/$GITHUB_REPOSITORY/branches?per_page=100" --jq \'.[].name\')"; then', stale)
        self.assertIn('if ! heads="$(gh pr list --state open --limit 100 --json headRefName,isCrossRepository --jq', stale)
        self.assertIn("delete them to re-arm the loop", stale)
        self.assertIn("exit 1", stale.split("delete them to re-arm the loop", 1)[1])

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
        self.assertEqual("needs.fix.outputs.proceed == 'false' && needs.fix.outputs.waiting != ''", remind["if"])   # the fix job ends green when the gate skipped: no always(); a halted gate names no PR
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

    def test_a_run_the_cli_did_not_answer_sets_up_no_disk_java_or_gradle(self):
        fix = steps(load(DRIFT), "fix")
        names = [s.get("name") for s in fix]
        precheck, reproduce = names.index("Classify before Gradle"), names.index("Reproduce the red")
        setup = [i for i, s in enumerate(fix) if s.get("uses", "").split("@")[0] in ("jlumbroso/free-disk-space", "actions/setup-java", "gradle/actions/setup-gradle")]
        self.assertEqual(3, len(setup))
        for i in setup:
            self.assertEqual("steps.precheck.outputs.kind != 'cli-did-not-answer'", fix[i]["if"], fix[i]["uses"])   # skipped on kind 1, as Reproduce the red is
            self.assertLess(precheck, i, fix[i]["uses"])                              # the kind is known before any of them
            self.assertLess(i, reproduce, fix[i]["uses"])                             # and they are all set up before Gradle runs
        for s in fix[:precheck + 1]:
            self.assertIn(s.get("uses", "actions/checkout@").split("@")[0], ("actions/checkout",), s.get("name"))   # nothing before the precheck needs Java or the room
            self.assertNotIn("gradlew", s.get("run", ""), s.get("name"))
        for s in fix:
            if "gradlew" in s.get("run", ""):
                self.assertIn("steps.precheck.outputs.kind != 'cli-did-not-answer'", s["if"], s.get("name"))   # Gradle never runs on kind 1

    def test_the_gradle_cache_is_read_only(self):
        gradle = [s for s in steps(load(DRIFT), "fix") if s.get("uses", "").startswith("gradle/actions/setup-gradle@")]
        self.assertEqual(1, len(gradle))
        self.assertEqual({"cache-read-only": True}, gradle[0]["with"])                # the agent's test code runs here; main's cache must not carry it

    def test_the_only_push_is_exact_and_the_broad_rules_are_gone(self):
        allowed = tools(step(load(DRIFT), "fix", "Fix the drift")["with"]["claude_args"])
        self.assertEqual(ALLOWED, allowed)                                              # the 24 invocations the skill prescribes, exactly; the checks below say why
        self.assertEqual(24, len(ALLOWED))
        self.assertIn("Bash(git push -u origin HEAD)", allowed)                        # a :* rule matches prefix + space; drift/:* never matched the push
        self.assertEqual([], [t for t in allowed if t.startswith("Bash(git push") and t.endswith(":*)")])
        for broad in ("Bash(python3:*)", "Bash(./gradlew:*)", "Bash(gh pr:*)", "Bash(gh issue:*)", "Bash(git:*)",
                      "Bash(cp:*)", "Bash(cat:*)", "Bash(ls:*)", "Bash(date:*)",       # `cat <<EOF > .github/…` and `cp x scripts/…` wrote past the Edit deny rules
                      "Bash(python3 -I scripts/redact_fixture.py:*)"):               # the redactor writes its own output path, which no Edit rule sees
            self.assertNotIn(broad, allowed)
        for exact in ("Bash(./gradlew check)", "Bash(python3 -m unittest discover -s scripts -t scripts)",
                      "Bash(cp build/drift/recording/* src/test/resources/fixtures/*)",   # a `*` not in a trailing `:*` is a pattern: the recording into the fixtures only
                      "Bash(python3 -I scripts/redact_fixture.py src/test/resources/fixtures/* src/test/resources/fixtures/*)"):   # the fixtures onto the fixtures only
            self.assertIn(exact, allowed)

    def test_the_agent_cannot_edit_the_build_or_start_a_second_cli(self):
        agent = step(load(DRIFT), "fix", "Fix the drift")
        self.assertEqual("agent", agent["id"])
        deny = json.loads(agent["with"]["settings"])["permissions"]["deny"]
        self.assertEqual(DENY, set(deny))                                             # the deny list the agent step's comment explains, exactly: nothing dropped, nothing unexplained
        self.assertEqual(len(DENY), len(deny))
        self.assertEqual(29, len(deny))
        self.assertEqual([], [d for d in deny if d.startswith("Write(")])              # never consulted by the CLI: Edit(path) covers Write
        for rule in ("Bash(cp *..*)", "Bash(cp * * *)", "Bash(python3 -I scripts/redact_fixture.py *..*)", "Bash(python3 -I scripts/redact_fixture.py * * *)"):
            self.assertIn(rule, deny)                                                 # each path pattern on the allowlist loses `..` and a third argument
        for rule in ("Bash(grep *.git/*)", "Bash(grep -r*)", "Bash(grep * -r*)", "Bash(grep -R*)", "Bash(grep * -R*)"):
            self.assertIn(rule, deny)                                                 # Read(.git/**) binds the Read tool, not a Bash grep of .git/config
        self.assertEqual({"BASH_DEFAULT_TIMEOUT_MS": "1800000", "BASH_MAX_TIMEOUT_MS": "2700000"}, json.loads(agent["with"]["settings"])["env"])   # ./gradlew check (platform tests, coverage) outlives the Bash tool's two-minute default; 30 minutes, under the step's 75
        self.assertEqual({"commit": "", "pr": ""}, json.loads(agent["with"]["settings"])["attribution"])   # the CLI's own co-author trailer and "Generated with" line stay out of the commit and the PR body (2.1.308: an empty string hides attribution; includeCoAuthoredBy is deprecated)


BRANCH = "drift/2026-10-22-7"
RUN_URL = "https://github.com/daarko10/tandem/actions/runs/7"
NOT_FOUND = '{"message":"Branch not found","documentation_url":"https://docs.github.com/rest/branches/branches#get-a-branch","status":"404"}'
SERVER_ERROR = '{"message":"Server Error","status":"502"}'
PR_LIST = "pr list --state open --limit 100 --json number,headRefName,labels,isCrossRepository"
BRANCH_READ = f"api repos/daarko10/tandem/branches/{BRANCH} --jq .name"
ISSUE_LIST = "issue list --label contract-drift --state open --search"
ISSUE_CREATE = "issue create --title CLI contract: the CLI did not answer --label contract-drift --body"
ISSUE_CREATE_HALTED = "issue create --title CLI contract: the CLI did not answer --label contract-drift --label drift-halted --body"
HALT_LABEL = "api repos/daarko10/tandem/issues/3/labels -f labels[]=drift-halted"
HALTED = "issue list --label drift-halted --state open --json number --jq"
WAITING = "pr list --label contract-drift --state open --json number --jq"
CLEAN_FILES = "src/main/kotlin/dev/daarkolabs/tandem/protocol/Ndjson.kt\nsrc/test/resources/fixtures/drift-2026-10-22.ndjson\ndocs/protocol.md\n"
ENFORCE_ENV = {"GH_TOKEN": "stub", "GH_REPO": "daarko10/tandem", "BRANCH": BRANCH, "RUN_URL": RUN_URL, "KIND": "decoder", "AGENT": "success"}
RUN_VIEW = {"html_url": RUN_URL, "created_at": "2026-10-21T03:17:09Z", "name": "CLI contract", "conclusion": "failure", "head_branch": "main",
            "head_repository": {"full_name": "daarko10/tandem"}, "event": "schedule"}
NAME_ENV = {"GH_TOKEN": "stub", "EVENT_RUN_ID": "7", "INPUT_RUN_ID": "", "REHEARSE": "false"}
RULES = "api repos/daarko10/tandem/rules/branches/main?per_page=100"
RULESET = "api repos/daarko10/tandem/rulesets/17"
APP_BYPASS = json.dumps({"id": 17, "enforcement": "active", "bypass_actors": [{"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"},
                                                                     {"actor_id": 1236702, "actor_type": "Integration", "bypass_mode": "pull_request"}]})
ROLE_ONLY = json.dumps({"id": 17, "enforcement": "active", "bypass_actors": [{"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"}]})
WITHHELD = json.dumps({"id": 17, "enforcement": "active"})                         # GitHub's answer to a token that cannot edit the ruleset
CODEOWNERS = "api -H Accept: application/vnd.github.raw+json repos/daarko10/tandem/contents/.github/CODEOWNERS?ref=main"
BRANCHES = "api --paginate repos/daarko10/tandem/branches?per_page=100 --jq .[].name"
HEADS = "pr list --state open --limit 100 --json headRefName,isCrossRepository --jq"
CONTRACT_XML = ('<?xml version="1.0" encoding="UTF-8"?><testsuite name="dev.daarkolabs.tandem.protocol.ContractTest">'
                '<testcase name="live transcripts decode with every core type recognised" classname="dev.daarkolabs.tandem.protocol.ContractTest">'
                '<failure message="java.lang.AssertionError: contract-hello.ndjson: no system/init" type="java.lang.AssertionError">trace</failure></testcase>'
                '<testcase name="live transcripts load with the reader" classname="dev.daarkolabs.tandem.protocol.ContractTest"/></testsuite>')


def pr(number: int, head: str = BRANCH, labelled: bool = False, fork: bool = False) -> dict:
    return {"number": number, "headRefName": head, "labels": [{"name": "contract-drift"}] if labelled else [], "isCrossRepository": fork}


@unittest.skipUnless(yaml and BASH, "PyYAML, bash 4 and jq are needed to run the steps")
class StepOutcomeTest(unittest.TestCase):
    """Each step's run: block under bash with a stub gh: what the step does, not what its text says."""

    def enforce(self, prs: list[dict], gh: list[tuple[str, str, int]]) -> tuple[int, str, str, str]:
        return run_step(load(DRIFT), "enforce", "Enforce the edit allowlist", ENFORCE_ENV, [(PR_LIST, json.dumps(prs), 0)] + gh)

    def test_enforce_with_no_pr_and_no_branch_is_red_reports_and_halts_the_loop(self):
        rc, out, _, calls = self.enforce([], [(BRANCH_READ, NOT_FOUND, 1), (ISSUE_LIST, "", 0), (ISSUE_CREATE_HALTED, "https://github.com/daarko10/tandem/issues/9\n", 0)])
        self.assertEqual(1, rc, out)
        self.assertIn("The classification was decoder but the agent pushed no branch and opened no PR (its step ended 'success')", out)
        self.assertNotIn("but opened no PR; reported", out)                           # the old [ -n "$(gh … || true)" ] read gh's error body as a branch name
        self.assertIn(f"{ISSUE_CREATE_HALTED} The drift fix for {RUN_URL} (classification decoder) pushed no branch and opened no pull request; its agent step ended 'success'", calls)   # no branch, so no stale-branch refusal: the label halts the next nightly's gate

    def test_enforce_with_no_pr_and_no_branch_labels_the_open_standing_issue(self):
        rc, out, _, calls = self.enforce([], [(BRANCH_READ, NOT_FOUND, 1), (ISSUE_LIST, "3\n", 0), ("issue comment 3 --body", "", 0), (HALT_LABEL, "{}", 0)])
        self.assertEqual(1, rc, out)
        self.assertIn(f"issue comment 3 --body The drift fix for {RUN_URL} (classification decoder) pushed no branch", calls)
        self.assertIn(HALT_LABEL + "\n", calls)
        self.assertNotIn("issue create", calls)

    def test_enforce_with_a_branch_and_no_pr_reports_the_stopped_agent(self):
        rc, out, _, calls = self.enforce([], [(BRANCH_READ, BRANCH + "\n", 0), (ISSUE_LIST, "", 0), (ISSUE_CREATE, "https://github.com/daarko10/tandem/issues/9\n", 0)])
        self.assertEqual(1, rc, out)
        self.assertIn(f"The agent pushed {BRANCH} but opened no PR; reported on the standing issue.", out)
        self.assertIn(f"{ISSUE_CREATE} The drift fix for {RUN_URL} pushed the branch {BRANCH} but opened no pull request", calls)
        self.assertIn("its step ended 'success'", calls)
        self.assertNotIn("drift-halted", calls)                                      # the stale-branch refusal halts this case; deleting the branch re-arms it

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
        rc, out, _, calls = self.enforce([pr(5, fork=True)], [(BRANCH_READ, NOT_FOUND, 1), (ISSUE_LIST, "", 0), (ISSUE_CREATE_HALTED, "", 0)])
        self.assertEqual(1, rc, out)
        self.assertIn("pushed no branch and opened no PR", out)                        # the fork's PR is not the loop's
        self.assertNotIn("issues/5/labels", calls)
        self.assertNotIn("pulls/5/files", calls)

    def gate(self, halted: tuple[str, int], waiting: tuple[str, int]) -> tuple[int, str, str, str]:
        return run_step(load(DRIFT), "fix", "Skip when a fix is already waiting", {"GH_TOKEN": "stub"}, [(HALTED, *halted), (WAITING, *waiting)])

    def test_the_gate_skips_and_names_the_waiting_pr_without_commenting(self):
        rc, out, output, calls = self.gate(("", 0), ("12\n", 0))
        self.assertEqual(0, rc, out)
        self.assertEqual("proceed=false\nwaiting=12\n", output)
        self.assertEqual([HALTED, WAITING], [c.split(" .[0]", 1)[0] for c in calls.splitlines()])   # the comment is the remind job's: this token cannot post it

    def test_the_gate_proceeds_when_no_fix_is_waiting(self):
        rc, out, output, _ = self.gate(("", 0), ("", 0))
        self.assertEqual(0, rc, out)
        self.assertEqual("proceed=true\n", output)

    def test_the_gate_fails_closed_when_the_list_fails(self):
        for halted, waiting in ((("", 0), (SERVER_ERROR, 1)), ((SERVER_ERROR, 1), ("", 0)), (("x\n", 0), ("", 0))):
            rc, out, output, _ = self.gate(halted, waiting)
            self.assertEqual(1, rc, out)
            self.assertEqual("", output)                                              # no proceed=: the agent never starts

    def test_the_gate_is_halted_by_an_open_drift_halted_issue(self):
        rc, out, output, calls = self.gate(("4\n", 0), ("", 0))
        self.assertEqual(0, rc, out)
        self.assertEqual("proceed=false\n", output)                                    # no waiting=: the remind job does not run
        self.assertIn("the loop is halted by issue #4; remove the drift-halted label to re-arm", out)
        self.assertEqual(1, len(calls.splitlines()))                                  # the PR list is not read

    def test_the_reminder_comments_on_the_waiting_pr(self):
        env = {"GH_TOKEN": "stub", "GH_REPO": "daarko10/tandem", "WAITING": "12", "RUN_URL": RUN_URL, "DATE": "2026-10-22"}
        rc, out, _, calls = run_step(load(DRIFT), "remind", "Comment on the waiting pull request", env, [("pr comment 12 --body", "", 0)])
        self.assertEqual(0, rc, out)
        self.assertEqual(f"pr comment 12 --body The nightly of 2026-10-22 is still red: {RUN_URL}. This pull request is still waiting; merge it, or close it and delete its branch, to re-arm the loop.\n", calls)
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

    def protect(self, rules: str, owners: str | tuple[str, int], branch: tuple[str, int], ruleset: tuple[str, int] = (ROLE_ONLY, 0),
                branches: tuple[str, int] = ("main\nci/drift-loop\n", 0), heads: tuple[str, int] = ("", 0)) -> tuple[int, str, str, str]:
        owners = owners if isinstance(owners, tuple) else (owners, 0)
        return run_step(load(DRIFT), "fix", "Require a protected main", {"GH_TOKEN": "stub", "BRANCH": BRANCH},
                        [(RULES, rules, 0), (RULESET, ruleset[0], ruleset[1]), (CODEOWNERS, owners[0], owners[1]),
                         (BRANCH_READ, branch[0], branch[1]), (BRANCHES, branches[0], branches[1]), (HEADS, heads[0], heads[1])])

    def test_protect_passes_a_ruleset_an_owner_and_a_missing_branch(self):
        rc, out, _, calls = self.protect("17\n", "* @daarko10\n", (NOT_FOUND, 1))
        self.assertEqual(0, rc, out)
        self.assertIn("main requires a pull request, an approving review from a code owner and approval of the last push (ruleset 17)", out)
        self.assertIn("ruleset 17: no App on its bypass list", out)                   # the owner's admin role is how the only code owner merges
        self.assertEqual([RULES, RULESET, CODEOWNERS, BRANCH_READ.split(" --jq", 1)[0], BRANCHES.split(" --jq", 1)[0], HEADS.split(" --jq", 1)[0]],
                         [c.split(" --jq", 1)[0] for c in calls.splitlines()])        # the ruleset first; the stale-branch listing last

    def test_protect_refuses_a_drift_branch_without_an_open_pull_request(self):
        rc, out, _, _ = self.protect("17\n", "* @daarko10\n", (NOT_FOUND, 1), branches=("main\ndrift/2026-10-20-5\ndrift/2026-10-19-4\nfeature/drift\n", 0),
                                     heads=("drift/2026-10-19-4\nfeature/x\n", 0))
        self.assertEqual(1, rc, out)                                                  # a stopped agent's push or a closed fix's branch: without this, the same red starts a run every night
        self.assertIn("The drift branches drift/2026-10-20-5 exist without an open pull request", out)   # the one whose PR is open is not named, nor a branch outside drift/
        self.assertIn("delete them to re-arm the loop", out)

    def test_protect_refuses_a_drift_branch_when_no_pull_request_is_open_at_all(self):
        rc, out, _, _ = self.protect("17\n", "* @daarko10\n", (NOT_FOUND, 1), branches=("main\ndrift/2026-10-20-5\n", 0), heads=("", 0))
        self.assertEqual(1, rc, out)                                                  # the commonest failed night: the pattern file holds one empty line, which only -x keeps from matching every branch
        self.assertIn("The drift branches drift/2026-10-20-5 exist without an open pull request", out)

    def test_protect_passes_a_drift_branch_whose_pull_request_is_open(self):
        rc, out, _, _ = self.protect("17\n", "* @daarko10\n", (NOT_FOUND, 1), branches=("main\ndrift/2026-10-19-4\n", 0), heads=("drift/2026-10-19-4\n", 0))
        self.assertEqual(0, rc, out)                                                  # the gate, not this step, decides about an open fix

    def test_protect_refuses_without_a_verdict_when_a_listing_fails(self):
        for branches, heads, message in (((SERVER_ERROR, 1), ("", 0), "Listing the branches failed"),
                                         (("main\n", 0), (SERVER_ERROR, 1), "Listing the open pull requests failed")):
            rc, out, _, _ = self.protect("17\n", "* @daarko10\n", (NOT_FOUND, 1), branches=branches, heads=heads)
            self.assertEqual(1, rc, (message, out))
            self.assertIn(message, out)
            self.assertNotIn("exist without an open pull request", out)              # red without a verdict, never "stale"

    def test_protect_says_when_github_withholds_the_bypass_list(self):
        rc, out, _, _ = self.protect("17\n", "* @daarko10\n", (NOT_FOUND, 1), (WITHHELD, 0))
        self.assertEqual(0, rc, out)                                                  # the workflow token never sees it: the owner's part, said in the log
        self.assertIn("ruleset 17: GitHub shows its bypass list only to a caller who can edit the ruleset, not to this token; keeping the Claude App off it is the owner's part", out)

    def test_protect_reads_every_qualifying_ruleset(self):
        rc, out, _, calls = run_step(load(DRIFT), "fix", "Require a protected main", {"GH_TOKEN": "stub", "BRANCH": BRANCH},
                                     [(RULES, "17 23\n", 0), (RULESET, ROLE_ONLY, 0), ("api repos/daarko10/tandem/rulesets/23", APP_BYPASS, 0)])
        self.assertEqual(1, rc, out)
        self.assertIn("The ruleset 23 on main lets an App bypass it", out)
        self.assertNotIn("CODEOWNERS", calls)

    def test_protect_refuses_without_the_ruleset_the_owner_line_or_a_404(self):
        for rules, owners, branch, message in (("\n", "* @daarko10\n", (NOT_FOUND, 1), "No ruleset on main requires a pull request"),
                                               ("x\n", "* @daarko10\n", (NOT_FOUND, 1), "No ruleset on main requires a pull request"),
                                               ("null\n", "* @daarko10\n", (NOT_FOUND, 1), "No ruleset on main requires a pull request"),   # a rule without its ruleset_id
                                               ("17\n", "docs/ @daarko10\n", (NOT_FOUND, 1), "CODEOWNERS has no line owning every file"),
                                               ("17\n", ('{"message":"Not Found","status":"404"}', 1), (NOT_FOUND, 1), "CODEOWNERS has no line owning every file"),   # no file on main: a 404, which owns nothing
                                               ("17\n", (SERVER_ERROR, 1), (NOT_FOUND, 1), "Reading main's .github/CODEOWNERS failed (HTTP status '502')"),   # not "no owner line": red without a verdict
                                               ("17\n", "* @daarko10\n", (BRANCH + "\n", 0), f"The branch {BRANCH} exists"),
                                               ("17\n", "* @daarko10\n", (SERVER_ERROR, 1), f"Reading the branch {BRANCH} failed (HTTP status '502')"),
                                               ("17\n", "* @daarko10\n", ("", 1), f"Reading the branch {BRANCH} failed (HTTP status 'none')")):
            rc, out, _, calls = self.protect(rules, owners, branch)
            self.assertEqual(1, rc, (message, out))
            self.assertIn(message, out)
            if not message.startswith(("The branch", "Reading the branch")):
                self.assertNotIn(BRANCH, calls)                                       # refused before the branch read
        for ruleset, message in (((APP_BYPASS, 0), 'The ruleset 17 on main lets an App bypass it ([{"actor_type":"Integration","actor_id":1236702}])'),
                                 ((SERVER_ERROR, 1), "Reading the ruleset 17 failed (HTTP status '502')"),   # red without a verdict, never "no App"
                                 (('{"bypass_actors":"x"}', 0), "The ruleset 17 answered a bypass list of type 'string'")):
            rc, out, _, calls = self.protect("17\n", "* @daarko10\n", (NOT_FOUND, 1), ruleset)
            self.assertEqual(1, rc, (message, out))
            self.assertIn(message, out)
            self.assertNotIn("CODEOWNERS", calls)                                     # refused before any later read

    def test_the_report_opens_or_comments_on_the_standing_issue(self):
        classification = json.dumps({"kind": "cli-did-not-answer", "cli_version": "2.1.301", "detail": "no system/init", "files": ["contract-hello.ndjson"]})
        env = {"GH_TOKEN": "stub", "RUN_URL": RUN_URL}
        rc, out, _, calls = run_step(load(DRIFT), "fix", "Report a run the agent cannot fix", env,
                                     [(ISSUE_LIST, "", 0), (ISSUE_CREATE, "https://github.com/daarko10/tandem/issues/9\n", 0)], {"build/drift/precheck.json": classification})
        self.assertEqual(0, rc, out)
        self.assertIn(f"{ISSUE_CREATE} **The CLI did not answer.**\n\nThe CLI contract run {RUN_URL} is red and the drift loop cannot fix it.\n\n- kind: cli-did-not-answer\n- CLI version: 2.1.301\n- detail: no system/init\n- files: contract-hello.ndjson", calls)
        rc, out, _, calls = run_step(load(DRIFT), "fix", "Report a run the agent cannot fix", env,
                                     [(ISSUE_LIST, "3\n", 0), ("issue comment 3 --body", "", 0)],
                                     {"build/drift/precheck.json": classification, "build/drift/classification.json": classification.replace("cli-did-not-answer", "unknown")})
        self.assertEqual(0, rc, out)
        self.assertIn("issue comment 3 --body **The CLI answered; the red is unclassified.**\n\nThe CLI contract run", calls)   # the title names kind 1 only: the first line names the kind
        self.assertIn("- kind: unknown", calls)                                       # the classification when Gradle ran, else the precheck
        self.assertNotIn("issue create", calls)

    def classify(self, name: str, files: dict) -> tuple[int, str, str]:
        """The real classifier in the step's working directory: the step runs `python3 -I scripts/classify_drift.py`."""
        with open(os.path.join(ROOT, "scripts/classify_drift.py"), encoding="utf-8") as f:
            script = f.read()
        rc, out, output, _ = run_step(load(DRIFT), "fix", name, {}, [], {"scripts/classify_drift.py": script, **files})
        return rc, out, output

    def test_the_precheck_names_the_kind_and_the_rehearsal_marker(self):
        reply = stream(INIT, REPLY, {"type": "result", "is_error": False, "result": "pong"})
        rc, out, output = self.classify("Classify before Gradle", {"build/drift/recording/contract-hello.ndjson": reply, "build/drift/recording/rehearsal": "init-model-key\n"})
        self.assertEqual(0, rc, out)
        self.assertEqual("kind=unknown\nrehearsal=true\n", output)                    # no test results before Gradle (rule 4); the marker is what the prompt passes as rehearsal=
        self.assertIn('"kind": "unknown"', out)                                       # tee: the JSON stays in the log
        rc, out, output = self.classify("Classify before Gradle", {})
        self.assertEqual(0, rc, out)
        self.assertEqual("kind=cli-did-not-answer\nrehearsal=false\n", output)        # no recording at all: kind 1 without Gradle

    def test_classify_reads_the_failed_decode_test(self):
        reply = stream(INIT, REPLY, {"type": "result", "is_error": False, "result": "pong"})
        rc, out, output = self.classify("Classify", {"build/drift/recording/contract-hello.ndjson": reply,
                                                     "build/test-results/test/TEST-dev.daarkolabs.tandem.protocol.ContractTest.xml": CONTRACT_XML})
        self.assertEqual(0, rc, out)
        self.assertEqual("kind=decoder\n", output)
        self.assertIn('"detail": "contract-hello.ndjson: no system/init"', out)       # the assertion's own message, without the exception class

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


NIGHTLY = os.path.join(ROOT, ".github/workflows/cli-contract.yml")
CHECK = "Check that the CLI authenticated and answered"
NAME_FAILURES = "Name the failed assertions"


@unittest.skipUnless(yaml, "PyYAML is not installed")
class NightlyWorkflowTest(unittest.TestCase):
    """The nightly's side of the drift loop: what cli-contract.yml leaves in its artifact and its log for the fixer."""

    def test_the_nightly_redacts_the_session_id_transcript_and_keeps_the_artifact(self):
        wf = load(os.path.join(ROOT, ".github/workflows/cli-contract.yml"))
        run = step(wf, "contract", "Check --session-id with an inherited CLAUDE_CODE_SESSION_ID")["run"]
        self.assertIn('scripts/copy_transcript.py "$work" "$work/raw.ndjson" "$work/transcripts" session-id', run)
        self.assertIn('scripts/redact_fixture.py "$work/transcripts/session-id.jsonl" build/contract/transcripts/session-id.jsonl', run)
        self.assertNotIn("build/contract/transcripts session-id", run)                # the raw copy never lands in the artifact
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

    def test_the_nightly_keeps_its_name_schedule_and_no_interpolated_run_line(self):
        wf = load(NIGHTLY)
        self.assertEqual("CLI contract", wf["name"])                                  # drift-fix.yml listens to this name
        self.assertEqual([{"cron": "17 3 * * *"}], wf[True]["schedule"])
        for s in steps(wf, "contract"):
            self.assertNotIn("${{", s.get("run", ""), s.get("name"))


def stream(*lines: dict) -> str:
    return "".join(json.dumps(line) + "\n" for line in lines)


INIT = {"type": "system", "subtype": "init", "model": "claude-opus-5-5", "apiKeySource": "none"}
REPLY = {"type": "assistant", "message": {"content": [{"type": "text", "text": "pong"}]}}


@unittest.skipUnless(yaml and BASH, "PyYAML, bash 4 and jq are needed to run the steps")
class NightlyStepOutcomeTest(unittest.TestCase):
    """The nightly's check and annotation steps run under bash on hand-built recordings and test results."""

    def check(self, text: str) -> tuple[int, str]:
        rc, out, _, _ = run_step(load(NIGHTLY), "contract", CHECK, {}, [], {"build/contract/contract-hello.ndjson": text})
        return rc, out

    def test_a_reply_passes(self):
        rc, out = self.check(stream(INIT, REPLY, {"type": "result", "subtype": "success", "is_error": False, "result": "pong"}))
        self.assertEqual(0, rc, out)
        self.assertNotIn("::error", out)

    def test_an_error_result_is_no_answer_even_with_an_assistant_line(self):
        rc, out = self.check(stream(INIT, REPLY, {"type": "result", "is_error": True, "result": "API Error: 401"}))
        self.assertEqual(1, rc, out)
        self.assertIn("::error title=CLI contract::The CLI did not answer: API Error: 401", out)

    def test_no_assistant_line_and_no_result_text_is_no_answer(self):
        rc, out = self.check(stream(INIT, {"type": "result", "is_error": False, "errors": ["overloaded"]}))
        self.assertEqual(1, rc, out)
        self.assertIn('::error title=CLI contract::The CLI did not answer: ["overloaded"]', out)

    def test_result_text_without_an_assistant_line_is_a_renamed_type_not_no_answer(self):
        rc, out = self.check(stream(INIT, {"type": "assistant_message", "message": {}}, {"type": "result", "is_error": False, "result": "pong"}))
        self.assertEqual(1, rc, out)
        self.assertIn("::error title=CLI contract::No assistant line although the result holds text", out)
        self.assertNotIn("did not answer", out)

    def test_a_multi_line_detail_stays_one_annotation(self):
        rc, out = self.check(stream(INIT, {"type": "result", "is_error": True, "result": "line one\n::warning::two 100%"}))
        self.assertEqual(1, rc, out)
        self.assertIn("::error title=CLI contract::The CLI did not answer: line one%0A::warning::two 100%25\n", out)

    def name_failures(self, files: dict) -> tuple[int, str]:
        rc, out, _, _ = run_step(load(NIGHTLY), "contract", NAME_FAILURES, {}, [], files)
        return rc, out

    def test_each_failed_assertion_is_an_annotation_with_its_message_unescaped(self):
        # Gradle's shape: the message is Throwable.toString(), the exception class before the assertion's own message.
        xml = ('<?xml version="1.0" encoding="UTF-8"?><testsuite name="dev.daarkolabs.tandem.protocol.ContractTest">'
               '<testcase name="live transcripts decode with every core type recognised" classname="dev.daarkolabs.tandem.protocol.ContractTest">'
               '<failure message="java.lang.AssertionError: contract-hello.ndjson: [&quot;assistant&quot;, user]&#10;second line" type="java.lang.AssertionError">trace</failure></testcase>'
               '<testcase name="live transcripts load with the reader" classname="dev.daarkolabs.tandem.protocol.ContractTest">'
               '<failure message="boom" type="java.lang.IllegalStateException">trace</failure></testcase></testsuite>')
        rc, out = self.name_failures({"build/test-results/test/TEST-dev.daarkolabs.tandem.protocol.ContractTest.xml": xml})
        self.assertEqual(0, rc, out)
        self.assertEqual(['::error title=CLI contract::live transcripts decode with every core type recognised: contract-hello.ndjson: ["assistant", user]%0Asecond line',
                          "::error title=CLI contract::live transcripts load with the reader: boom"],
                         out.splitlines())

    def test_no_test_results_print_nothing(self):
        rc, out = self.name_failures({})                                              # an earlier step failed before Gradle ran
        self.assertEqual((0, ""), (rc, out))



BUILD = os.path.join(ROOT, ".github/workflows/build.yml")


@unittest.skipUnless(yaml, "PyYAML is not installed")
class BuildWorkflowTest(unittest.TestCase):
    """build.yml: which jobs wait for which, and how the IDE test is split across jobs."""

    def test_the_verifier_and_the_ide_test_do_not_wait_for_build_and_test(self):
        jobs = load(BUILD)["jobs"]
        for name in ("verify", "ide-test"):
            self.assertNotIn("build", jobs[name].get("needs", []), name)               # each builds its own plugin ZIP; waiting only lengthened the critical path
        self.assertIn("plugin-distribution", [s.get("with", {}).get("name") for s in jobs["build"]["steps"]])   # the ZIP people download stays Build and test's

    def test_the_ide_test_runs_in_three_shards(self):
        wf = load(BUILD)
        job = wf["jobs"]["ide-test"]
        self.assertEqual({"fail-fast": False, "matrix": {"shard": [1, 2, 3]}}, job["strategy"])   # a red shard never cancels the others
        self.assertEqual("IDE test (${{ matrix.shard }}/${{ strategy.job-total }})", job["name"])
        run = step(wf, "ide-test", "Run the IDE test")
        self.assertEqual("${{ strategy.job-index }}/${{ strategy.job-total }}", run["env"]["IDE_TEST_SHARD"])   # IdeTestShard counts from 0, as job-index does
        self.assertIn('./gradlew integrationTest -Dtandem.ideTestShard="$IDE_TEST_SHARD"', run["run"])
        self.assertNotIn("${{", run["run"])                                           # through env, never interpolated into the shell line
        uploads = [s for s in job["steps"] if s.get("uses", "").startswith("actions/upload-artifact@")]
        self.assertEqual(["ide-test-logs-${{ matrix.shard }}"], [s["with"]["name"] for s in uploads])   # an artifact name must be unique in the run
        with open(os.path.join(ROOT, "build.gradle.kts"), encoding="utf-8") as f:
            self.assertIn('systemProperty("tandem.ideTestShard", providers.systemProperty("tandem.ideTestShard").getOrElse(""))', f.read())   # reaches the test JVM


if __name__ == "__main__":
    unittest.main()

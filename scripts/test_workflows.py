"""Tests for the drift loop's workflows and the skill they run. Run from scripts/: python3 -m unittest

They read .github/workflows/drift-fix.yml (and cli-contract.yml) with PyYAML, which reads the `on` key as the boolean
True. Without PyYAML they skip, except under GITHUB_ACTIONS, where a missing PyYAML is an error: these are the only
tests pinning the workflows, and build.yml's "Script tests" step must never skip them silently.
"""
import importlib
import json
import os
import re
import unittest

try:
    yaml = importlib.import_module("yaml")
except ImportError:
    yaml = None

if yaml is None and os.environ.get("GITHUB_ACTIONS"):
    raise RuntimeError("PyYAML is required in CI")

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
        gate = step(wf, "fix", "Skip when a fix is already waiting")["run"]
        self.assertIn('gh api "repos/$GITHUB_REPOSITORY/issues/$pr/comments" -f body=', gate)   # the comment goes through the issues endpoint: issues: write covers it
        self.assertEqual([], [s.get("name") for s in steps(wf, "fix") if "gh pr comment" in s.get("run", "")])   # nothing in the fix job needs pull-requests: write
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
        self.assertTrue(step(wf, "fix", "Reproduce the red")["continue-on-error"])
        self.assertIn("--rerun", step(wf, "fix", "Reproduce the red")["run"])        # the build cache never replays a pass on another recording
        for name in ("Name the red run", "Fetch the recording", "Report a run the agent cannot fix", "Skip when a fix is already waiting",
                     "Require a protected main"):
            self.assertEqual("${{ github.token }}", step(wf, "fix", name)["env"]["GH_TOKEN"])
        self.assertEqual("${{ github.token }}", step(wf, "enforce", "Enforce the edit allowlist")["env"]["GH_TOKEN"])
        name_run = step(wf, "fix", "Name the red run")["run"]
        self.assertIn('.conclusion == "failure"', name_run)                           # a green or foreign run is refused before any spend
        self.assertIn('.head_branch == "main"', name_run)                             # so is a run of another branch, whose recording that branch produced
        for s in steps(wf, "fix") + steps(wf, "enforce"):
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
        self.assertIn("steps.precheck.outputs.kind == 'cli-did-not-answer'", report["if"])
        self.assertIn("precheck.json", report["run"])
        enforce = step(wf, "enforce", "Enforce the edit allowlist")
        self.assertIn('repos/$GITHUB_REPOSITORY/branches/$BRANCH', enforce["run"])    # the branch is read through the API with GH_TOKEN: there is no checkout here
        self.assertNotIn("git ls-remote", enforce["run"])
        self.assertNotIn("gh pr close", enforce["run"])                               # an offending PR is commented on and left open: closing would re-arm the loop nightly
        self.assertIn("gh pr comment", enforce["run"])
        self.assertIn('files="$(gh api --paginate "repos/$GITHUB_REPOSITORY/pulls/$pr/files"', enforce["run"])   # a failed read fails the step; REST pages past 100 files
        self.assertIn("previous_filename", enforce["run"])                            # a rename from outside the allowlist names its old path
        self.assertNotIn("gh pr view", enforce["run"])
        self.assertIn(ALLOWLIST, enforce["run"])                                      # the edit allowlist, exactly
        self.assertNotIn("if", enforce)                                               # the job's if decides; see test_the_allowlist_is_enforced_in_its_own_job
        self.assertNotIn('no branch was pushed"; exit 0', enforce["run"])              # no PR after the gate proceeded is red, never green and silent
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
        self.assertIn('if gh api "repos/$GITHUB_REPOSITORY/branches/$BRANCH" --jq .name >/dev/null 2>&1; then', protect["run"])   # a kept branch would reject the push after the spend
        self.assertIn("delete it to retry", protect["run"].split("branches/$BRANCH", 1)[1])
        self.assertIn("exit 1", protect["run"].split("delete it to retry", 1)[1])
        self.assertLess(protect["run"].index("rules/branches/main"), protect["run"].index("branches/$BRANCH"))   # the ruleset first: it is the loop's precondition

    def test_the_allowlist_is_enforced_in_its_own_job(self):
        wf = load(DRIFT)
        self.assertEqual(["fix", "enforce"], list(wf["jobs"]))
        enforce = wf["jobs"]["enforce"]
        self.assertEqual("fix", enforce["needs"])                                      # a fresh runner: code the agent ran in fix cannot reach it
        self.assertEqual("always() && needs.fix.outputs.proceed == 'true' && needs.fix.outputs.protected == 'success'", enforce["if"])
        self.assertEqual({"contents": "read", "pull-requests": "write", "issues": "write"}, enforce["permissions"])   # no id-token, no actions
        self.assertEqual(10, enforce["timeout-minutes"])
        self.assertEqual([], [s for s in enforce["steps"] if "uses" in s])            # no checkout, no action: nothing the agent could have changed
        self.assertEqual("${{ github.repository }}", step(wf, "enforce", "Enforce the edit allowlist")["env"]["GH_REPO"])   # gh has no remote to infer it from
        self.assertEqual({"proceed": "${{ steps.gate.outputs.proceed }}", "protected": "${{ steps.protect.outcome }}",
                          "kind": "${{ steps.classify.outputs.kind }}", "agent": "${{ steps.agent.outputs.conclusion }}",
                          "id": "${{ steps.run.outputs.id }}", "url": "${{ steps.run.outputs.url }}", "date": "${{ steps.run.outputs.date }}"},
                         wf["jobs"]["fix"]["outputs"])                                # all but agent are written before the agent starts
        names = [s.get("name") for s in steps(wf, "fix")]
        self.assertLess(names.index("Skip when a fix is already waiting"), names.index("Fix the drift"))
        self.assertLess(names.index("Require a protected main"), names.index("Fix the drift"))
        self.assertLess(names.index("Classify"), names.index("Fix the drift"))
        enforce_run = step(wf, "enforce", "Enforce the edit allowlist")["run"]
        self.assertEqual("drift/${{ needs.fix.outputs.date }}-${{ needs.fix.outputs.id }}", step(wf, "enforce", "Enforce the edit allowlist")["env"]["BRANCH"])
        self.assertIn('open="$(gh pr list --state open --limit 100 --json number,headRefName,labels)"', enforce_run)   # a failed list fails the step
        self.assertIn('jq -r --arg branch "$BRANCH" \'.[] | select(.headRefName == $branch or any(.labels[]; .name == "contract-drift"))', enforce_run)   # this run's branch first, then the label: an unlabelled PR is checked, not misread as a stopped agent
        self.assertIn('gh api "repos/$GITHUB_REPOSITORY/issues/$pr/labels" -f "labels[]=contract-drift"', enforce_run)   # so the gate holds while it is open
        self.assertIn('for entry in "${prs[@]}"; do', enforce_run)                    # every PR is checked, never only the first
        self.assertIn('if [ "${#prs[@]}" -gt 1 ]; then', enforce_run)                  # a second PR is red: the agent opens one
        self.assertIn('exit "$red"', enforce_run)
        self.assertNotIn("][0].number", enforce_run)                               # not the first contract-drift PR only

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


if __name__ == "__main__":
    unittest.main()

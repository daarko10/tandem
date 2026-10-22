"""Tests for the drift loop's workflows and the skill they run. Run from scripts/: python3 -m unittest

They read .github/workflows/drift-fix.yml (and cli-contract.yml) with PyYAML, which reads the `on` key as the boolean
True. Without PyYAML they skip, except under GITHUB_ACTIONS, where a missing PyYAML is an error: these are the only
tests pinning the workflows, and build.yml's "Script tests" step must never skip them silently.
"""
import importlib
import json
import os
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
        self.assertIn("--max-turns 120", agent["with"]["claude_args"])

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
        enforce = step(wf, "fix", "Enforce the edit allowlist")
        self.assertIn('repos/$GITHUB_REPOSITORY/branches/$BRANCH', enforce["run"])    # the branch is read through the API with GH_TOKEN: git has no credential here
        self.assertNotIn("git ls-remote", enforce["run"])
        self.assertNotIn("gh pr close", enforce["run"])                               # an offending PR is commented on and left open: closing would re-arm the loop nightly
        self.assertIn("gh pr comment", enforce["run"])
        self.assertIn('files="$(gh pr view "$pr" --json files', enforce["run"])      # a failed read of the PR's files fails the step, never "within the allowlist"
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
        self.assertEqual({"BASH_DEFAULT_TIMEOUT_MS": "900000", "BASH_MAX_TIMEOUT_MS": "1800000"}, json.loads(agent["with"]["settings"])["env"])   # ./gradlew check outlives the Bash tool's two-minute default


if __name__ == "__main__":
    unittest.main()

"""Tests for drive_cli.py's Cli.call, without a real `claude`. Run from scripts/: python3 -m unittest"""
import base64
import contextlib
import importlib
import io
import json
import os
import queue
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import drive_cli
import redact_fixture

CONNECTOR = "claude.ai Example"
FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "fixtures")


class FakeCli(drive_cli.Cli):
    """A Cli whose process is replaced: what it sends is kept, and each request is answered by `answer`."""

    def __init__(self, answer):
        self.next_id = 0
        self.inbox = queue.Queue()
        self.sent: list[dict] = []
        self.answer = answer

    def send(self, msg: dict) -> None:
        self.sent.append(msg)
        if msg.get("type") == "control_request":
            self.inbox.put(self.answer(msg["request_id"], msg["request"]))
        elif msg.get("type") == "user":
            self.inbox.put({"type": "result"})


def success(request_id: str, _request: dict) -> dict:
    return {"type": "control_response", "response": {"subtype": "success", "request_id": request_id, "response": {}}}


def error(request_id: str, _request: dict) -> dict:
    return {"type": "control_response",
            "response": {"subtype": "error", "request_id": request_id, "error": "Unknown model"}}


class CallTest(unittest.TestCase):
    def test_call_returns_the_response_to_its_request(self):
        cli = FakeCli(success)
        cli.inbox.put({"type": "control_response", "response": {"subtype": "success", "request_id": "host-0"}})
        response = cli.call({"subtype": "interrupt"})
        self.assertEqual("host-1", response["response"]["request_id"])

    def test_call_fails_on_an_error_response(self):
        with self.assertRaisesRegex(RuntimeError, "interrupt.*Unknown model"):
            FakeCli(error).call({"subtype": "interrupt"})

    def test_call_returns_an_error_response_when_errors_are_allowed(self):
        response = FakeCli(error).call({"subtype": "update_settings"}, allow_error=True)
        self.assertEqual("error", response["response"]["subtype"])

    def test_model_switch_waits_for_set_model_and_fails_when_it_is_rejected(self):
        cli = FakeCli(success)
        drive_cli.model_switch(cli)
        self.assertEqual(["user", "control_request", "user"], [m["type"] for m in cli.sent])
        with self.assertRaises(RuntimeError):
            drive_cli.model_switch(FakeCli(error))

    def test_the_rejected_mode_scenario_records_the_rejection_and_goes_on(self):
        cli = FakeCli(error)
        drive_cli.permission_mode_rejected(cli)
        modes = [m["request"].get("mode") for m in cli.sent if m["type"] == "control_request"]
        self.assertEqual(["bypassPermissions"], modes)
        self.assertEqual(2, sum(m["type"] == "user" for m in cli.sent), "both turns ran")

    def test_the_rejected_mode_scenario_fails_when_the_mode_is_accepted(self):
        with self.assertRaisesRegex(RuntimeError, "accepted bypassPermissions"):
            drive_cli.permission_mode_rejected(FakeCli(success))

    def test_the_settings_probe_records_rejected_requests_and_goes_on(self):
        cli = FakeCli(error)
        drive_cli.settings(cli)
        self.assertEqual(2, sum(m["type"] == "user" for m in cli.sent), "both turns ran")

    def test_the_effort_scenario_sends_two_settings_requests_between_two_turns(self):
        def malformed_errors(request_id: str, request: dict) -> dict:
            if not isinstance(request["settings"], dict):
                return error(request_id, request)
            return success(request_id, request)

        cli = FakeCli(malformed_errors)
        drive_cli.effort_switch(cli)
        self.assertEqual(["user", "control_request", "control_request", "user"], [m["type"] for m in cli.sent])
        self.assertEqual({"effortLevel": "high"}, cli.sent[1]["request"]["settings"])

    def test_the_effort_scenario_fails_when_a_malformed_request_is_accepted(self):
        with self.assertRaisesRegex(RuntimeError, "accepted an apply_flag_settings"):
            drive_cli.effort_switch(FakeCli(success))

    def test_the_auto_mode_scenario_records_the_answer_and_goes_on(self):
        cli = FakeCli(error)
        drive_cli.permission_mode_auto(cli)
        modes = [m["request"].get("mode") for m in cli.sent if m["type"] == "control_request"]
        self.assertEqual(["auto"], modes)
        self.assertEqual(2, sum(m["type"] == "user" for m in cli.sent), "both turns ran")

    def test_the_image_scenario_sends_a_text_block_then_a_base64_png(self):
        cli = FakeCli(success)
        drive_cli.user_image(cli)
        content = cli.sent[0]["message"]["content"]
        self.assertEqual(1, len(cli.sent))
        self.assertEqual(["text", "image"], [block["type"] for block in content])
        source = content[1]["source"]
        self.assertEqual("base64", source["type"])
        self.assertEqual("image/png", source["media_type"])
        self.assertEqual(b"\x89PNG\r\n\x1a\n", base64.b64decode(source["data"])[:8])

    @staticmethod
    def ask_two_request(request_id: str = "req-1", questions: list[dict] | None = None) -> dict:
        """A can_use_tool for AskUserQuestion with the two questions ASK_TWO_PROMPT asks for, as the CLI sends it."""
        if questions is None:
            questions = [
                {"question": "Which colour?", "header": "Colour", "multiSelect": False,
                 "options": [{"label": "Red", "description": "A warm colour"},
                             {"label": "Blue", "description": "A cool colour"}]},
                {"question": "Which toppings would you like?", "header": "Toppings", "multiSelect": True,
                 "options": [{"label": "Cheese", "description": "Melted"}, {"label": "Olives", "description": "Black"},
                             {"label": "Ham", "description": "Sliced"}]},
            ]
        return {"type": "control_request", "request_id": request_id,
                "request": {"subtype": "can_use_tool", "tool_name": "AskUserQuestion", "display_name": "AskUserQuestion",
                            "input": {"questions": questions}, "tool_use_id": "toolu_1",
                            "requires_user_interaction": True}}

    def test_the_ask_two_scenario_answers_both_questions_and_fails_on_one(self):
        cli = FakeCli(success)
        request = self.ask_two_request()
        cli.inbox.put(request)
        drive_cli.ask_two(cli)
        responses = [m for m in cli.sent if m["type"] == "control_response"]
        self.assertEqual(1, len(responses))
        self.assertEqual("req-1", responses[0]["response"]["request_id"])
        body = responses[0]["response"]["response"]
        self.assertEqual("allow", body["behavior"])
        self.assertEqual("toolu_1", body["toolUseID"])
        self.assertEqual(request["request"]["input"]["questions"], body["updatedInput"]["questions"])
        self.assertEqual({"Which colour?": "Red", "Which toppings would you like?": "Cheese, Ham"}, body["updatedInput"]["answers"])
        one = FakeCli(success)
        one.inbox.put(self.ask_two_request(questions=self.ask_two_request()["request"]["input"]["questions"][:1]))
        with self.assertRaises(RuntimeError):
            drive_cli.ask_two(one)

    def test_the_ask_two_scenario_fails_on_a_missing_or_reworded_request(self):
        # The model replied without calling AskUserQuestion: the inbox holds only the turn's result.
        with self.assertRaisesRegex(RuntimeError, "AskUserQuestion"):
            drive_cli.ask_two(FakeCli(success))
        # A second can_use_tool after the answered one.
        twice = FakeCli(success)
        twice.inbox.put(self.ask_two_request("req-1"))
        twice.inbox.put(self.ask_two_request("req-2"))
        with self.assertRaises(RuntimeError):
            drive_cli.ask_two(twice)
        # Another tool.
        other = FakeCli(success)
        bash = self.ask_two_request()
        bash["request"]["tool_name"] = "Bash"
        other.inbox.put(bash)
        with self.assertRaises(RuntimeError):
            drive_cli.ask_two(other)
        # A reworded question, header or option label.
        for path, value in ((("question",), "Which color?"), (("header",), "Color"), (("options", 1, "label"), "Navy")):
            questions = self.ask_two_request()["request"]["input"]["questions"]
            target = questions[0]
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            reworded = FakeCli(success)
            reworded.inbox.put(self.ask_two_request(questions=questions))
            with self.assertRaises(RuntimeError, msg=str(path)):
                drive_cli.ask_two(reworded)
        # The multi-select flag of the second question.
        questions = self.ask_two_request()["request"]["input"]["questions"]
        questions[1]["multiSelect"] = False
        flag = FakeCli(success)
        flag.inbox.put(self.ask_two_request(questions=questions))
        with self.assertRaises(RuntimeError):
            drive_cli.ask_two(flag)
        # No answer is sent for a request the scenario refuses.
        self.assertEqual([], [m for m in flag.sent if m["type"] == "control_response"])

    def test_the_tiny_png_is_pinned(self):
        # TranscriptReaderTest holds the same literal as TINY_PNG_BASE64: the recorded transcript keeps these bytes.
        self.assertEqual(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP4z8AARAwQCgAf7gP9i18U1AAAAABJRU5ErkJggg==",
            base64.b64encode(drive_cli.tiny_png()).decode())


class LoggingQueue(queue.Queue):
    """An inbox that logs every message the scenario reads, so a test sees what was sent before and after it."""

    def __init__(self, log: list):
        super().__init__()
        self.log = log

    def get(self, *args, **kwargs):
        msg = super().get(*args, **kwargs)
        self.log.append(("read", msg))
        return msg


class ConnectorCli(FakeCli):
    """A CLI with one connector (`name`, source claudeai) beside a user-scope stdio server. mcp_status lists both, the
    connector needs-auth until the `connected_on`-th mcp_status after mcp_authenticate (never, when None);
    mcp_authenticate answers with a sign-in link on a vendor host (an error when `auth_error`); a prompt streams an init
    (unless not `init`) and a delta before its result. `log` holds what was sent and read, in order; `lines` holds what
    the CLI printed, as Cli.lines does."""

    def __init__(self, name: str = CONNECTOR, connected_on: int | None = 1, auth_error: bool = False, init: bool = True):
        super().__init__(self.respond)
        self.name = name
        self.connected_on = connected_on
        self.auth_error = auth_error
        self.init = init
        self.log: list = []
        self.inbox = LoggingQueue(self.log)
        self.lines: list[str] = []
        self.polls: int | None = None  # mcp_status requests since mcp_authenticate

    def servers(self) -> list[dict]:
        connected = self.polls is not None and self.connected_on is not None and self.polls >= self.connected_on
        return [{"name": self.name, "status": "connected" if connected else "needs-auth",
                 "config": {"type": "claudeai-proxy", "url": "https://mcp.example-vendor.com/v1/example", "id": "srv_example"},
                 "scope": "claudeai", "source": "claudeai"},
                {"name": "echo", "status": "connected",
                 "config": {"type": "stdio", "command": "/usr/bin/python3", "args": ["/home/someone/echo.py"],
                            "env": {"TOKEN": "secret"}},
                 "scope": "user", "source": "user"}]

    def respond(self, request_id: str, request: dict) -> dict:
        subtype = request.get("subtype")
        body: dict = {}
        if subtype == "mcp_status":
            if self.polls is not None:
                self.polls += 1
            body = {"mcpServers": self.servers()}
        elif subtype == "mcp_authenticate":
            self.polls = 0
            if self.auth_error:
                return {"type": "control_response",
                        "response": {"subtype": "error", "request_id": request_id, "error": "Unknown subtype"}}
            body = {"authUrl": "https://auth.example-vendor.com/authorize?code_challenge=abc&state=xyz",
                    "requiresUserAction": True, "state": "xyz"}
        return {"type": "control_response", "response": {"subtype": "success", "request_id": request_id, "response": body}}

    def emit(self, msg: dict) -> None:
        self.lines.append(json.dumps(msg))
        self.inbox.put(msg)

    def send(self, msg: dict) -> None:
        self.sent.append(msg)
        self.log.append(("sent", msg))
        if msg.get("type") == "control_request":
            self.emit(self.answer(msg["request_id"], msg["request"]))
        elif msg.get("type") == "user":
            if self.init:
                self.emit({"type": "system", "subtype": "init", "session_id": "s",
                           "tools": ["Bash", f"mcp__{self.name.replace('.', '_').replace(' ', '_')}__search",
                                     "mcp__echo__echo"],
                           "mcp_servers": [{"name": self.name, "status": "connected", "source": "claudeai"},
                                           {"name": "echo", "status": "connected", "source": "user"}]})
            self.emit({"type": "stream_event", "event": {"type": "content_block_delta"}})
            self.emit({"type": "result", "subtype": "success", "num_turns": 1})


class QuietResultCli(FakeCli):
    """Answers as the CLI does once the quiet frames are merged (bash-mode.ndjson:8): a querying frame gets a zero-turn
    result listing the quiet frames' uuids, then its own result listing its uuid, then a marker nothing should read."""

    def __init__(self):
        super().__init__(success)
        self.log: list = []
        self.inbox = LoggingQueue(self.log)
        self.quiet: list[str] = []

    def send(self, msg: dict) -> None:
        self.sent.append(msg)
        if msg.get("shouldQuery") is False:
            self.quiet.append(msg["uuid"])
            return
        self.inbox.put({"type": "result", "subtype": "success", "num_turns": 0, "user_message_uuids": self.quiet})
        self.inbox.put({"type": "result", "subtype": "success", "num_turns": 1, "user_message_uuids": [msg["uuid"]]})
        self.inbox.put({"type": "marker"})


# A claude for record-scenario.sh: strict mode has dropped the connector, every request on it fails naming it, the ping
# streams an init and a result, and the transcript is written where copy_transcript.py looks for it.
FAKE_CLAUDE = r"""#!python3
import json, os, re, sys
if "--version" in sys.argv:
    print("0.0.0 (fake)")
    sys.exit(0)
session = "fake-session"
folder = os.path.join(os.environ["CLAUDE_CONFIG_DIR"], "projects", re.sub(r"[^A-Za-z0-9]", "-", os.path.realpath(".")))
os.makedirs(folder, exist_ok=True)
transcript = open(os.path.join(folder, session + ".jsonl"), "w", encoding="utf-8")
def out(msg):
    print(json.dumps(msg), flush=True)
for line in sys.stdin:
    msg = json.loads(line)
    if msg["type"] == "control_request":
        request = msg["request"]
        if "serverName" in request:
            answer = {"subtype": "error", "request_id": msg["request_id"],
                      "error": "Server not found: " + request["serverName"]}
            transcript.write(json.dumps({"type": "system", "sessionId": session, "content": answer["error"]}) + "\n")
        else:
            body = {"mcpServers": []} if request["subtype"] == "mcp_status" else {}
            answer = {"subtype": "success", "request_id": msg["request_id"], "response": body}
        out({"type": "control_response", "response": answer})
    elif msg["type"] == "user":
        transcript.write(json.dumps({"type": "user", "sessionId": session, "uuid": msg["uuid"]}) + "\n")
        out({"type": "system", "subtype": "init", "session_id": session, "tools": [], "mcp_servers": []})
        out({"type": "result", "subtype": "success", "num_turns": 1, "session_id": session})
transcript.close()
"""


def requests(cli: FakeCli) -> list[dict]:
    return [m["request"] for m in cli.sent if m["type"] == "control_request"]


class ConnectorScenarioTest(unittest.TestCase):
    def setUp(self):
        environment = mock.patch.dict(os.environ)
        environment.start()
        self.addCleanup(environment.stop)
        os.environ.pop("TANDEM_PROBE_CONNECTOR", None)
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = temp.name

    def run_main(self, scenario: str, extra: list[str]) -> list[ConnectorCli]:
        """main() with a ConnectorCli in place of the process; returns every Cli it made, with the argv parts it got."""
        made = []

        def make(extra: list[str], model=None, first_id=0, drop=()):
            cli = ConnectorCli(name=CONNECTOR if "TANDEM_PROBE_CONNECTOR" in os.environ else "claude.ai X")
            cli.extra, cli.drop = extra, list(drop)
            cli.close = lambda: 0
            made.append(cli)
            return cli

        with mock.patch.object(drive_cli, "Cli", make), contextlib.redirect_stderr(io.StringIO()):
            drive_cli.main(scenario, os.path.join(self.dir, "raw.ndjson"), extra)
        return made

    def test_the_connector_scenario_reads_the_name_from_the_environment_and_never_writes_it(self):
        os.environ["TANDEM_PROBE_CONNECTOR"] = CONNECTOR
        self.assertEqual((CONNECTOR, []), drive_cli.connector_name([]))

        (cli,) = self.run_main("mcp-connectors", [])
        names = {r["serverName"] for r in requests(cli) if "serverName" in r}
        self.assertEqual({CONNECTOR}, names)
        self.assertNotIn(CONNECTOR, " ".join(cli.extra))

        # main writes only what the CLI printed; redacted as record-scenario.sh does, the name is in no output file.
        self.assertEqual(["raw.ndjson"], os.listdir(self.dir))
        raw = os.path.join(self.dir, "raw.ndjson")
        with open(raw, encoding="utf-8") as f:
            self.assertIn(CONNECTOR, f.read(), "the double printed the name, so the redactor has something to rename")
        os.makedirs(os.path.join(self.dir, "out", "transcripts"))
        dst = os.path.join(self.dir, "out", "mcp-connectors.ndjson")
        importlib.reload(redact_fixture)
        with contextlib.redirect_stderr(io.StringIO()):
            redact_fixture.redact(raw, dst)
        with open(dst, encoding="utf-8") as f:
            text = f.read()
        self.assertNotIn("Example", text)
        self.assertIn("claude.ai Connector-1", text)

    def test_the_connector_scenario_takes_the_name_from_the_connector_flag(self):
        self.assertEqual(("claude.ai X", ["--foo"]), drive_cli.connector_name(["--connector", "claude.ai X", "--foo"]))

        (cli,) = self.run_main("mcp-connectors", ["--connector", "claude.ai X", "--foo"])
        self.assertEqual({"claude.ai X"}, {r["serverName"] for r in requests(cli) if "serverName" in r})
        self.assertNotIn("--connector", cli.extra)
        self.assertNotIn("claude.ai X", cli.extra)
        self.assertEqual("--foo", cli.extra[0])

    def test_the_connector_scenario_fails_without_a_name(self):
        for extra in ([], ["--connector"]):
            err = io.StringIO()
            with contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as exit_:
                drive_cli.connector_name(extra)
            self.assertEqual(2, exit_.exception.code)
            self.assertIn("set TANDEM_PROBE_CONNECTOR", err.getvalue())

    def test_the_connector_scenario_drops_safe_mode_and_adds_the_strict_flags(self):
        os.environ["TANDEM_PROBE_CONNECTOR"] = CONNECTOR
        argvs = []

        environments = []

        class Popen:  # a claude that exits at once: main fails on initialize, after the argv is built
            def __init__(self, argv, **kwargs):
                argvs.append(argv)
                environments.append(kwargs["env"])
                self.stdin, self.stdout = io.StringIO(), io.StringIO("")

            def wait(self, timeout=None):
                return 0

        for scenario in ("mcp-connectors", "mcp-connectors-all", "permission"):
            with mock.patch.object(drive_cli.subprocess, "Popen", Popen), contextlib.redirect_stderr(io.StringIO()), \
                    self.assertRaisesRegex(RuntimeError, "exited"):
                drive_cli.main(scenario, os.path.join(self.dir, f"{scenario}.ndjson"), [])
        connectors, all_, permission_ = argvs
        self.assertNotIn("--safe-mode", connectors)
        strict = connectors.index("--strict-mcp-config")
        self.assertEqual(["--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}'], connectors[strict:strict + 3])
        self.assertNotIn("--setting-sources", connectors)
        for flag in ("--safe-mode", "--strict-mcp-config", "--mcp-config", "--setting-sources"):
            self.assertNotIn(flag, all_)
        self.assertIn("--safe-mode", permission_)
        self.assertNotIn(CONNECTOR, " ".join(connectors + all_))
        for env in environments:  # the name is the scripts' own: claude never gets it
            self.assertNotIn("TANDEM_PROBE_CONNECTOR", env)
            self.assertNotIn(CONNECTOR, env.values())

    def test_the_connector_scenario_sends_status_and_toggle_while_ping_streams(self):
        cli = ConnectorCli()
        with contextlib.redirect_stderr(io.StringIO()):
            drive_cli.mcp_connectors(cli, CONNECTOR, sleep=lambda s: None)
        log = cli.log
        ping = next(i for i, (kind, m) in enumerate(log) if kind == "sent" and m["type"] == "user")
        self.assertEqual([{"type": "text", "text": "ping"}], log[ping][1]["message"]["content"])
        init = next(i for i in range(ping, len(log)) if log[i][0] == "read" and drive_cli.is_init(log[i][1]))
        result = next(i for i in range(ping, len(log)) if log[i][0] == "read" and drive_cli.is_result(log[i][1]))
        during = [m["request"] for kind, m in log[init:result] if kind == "sent"]
        self.assertEqual([{"subtype": "mcp_status"}, {"subtype": "mcp_toggle", "serverName": CONNECTOR, "enabled": False}],
                         during)
        after = [m["request"] for kind, m in log[result:] if kind == "sent"]
        self.assertEqual([{"subtype": "mcp_toggle", "serverName": CONNECTOR, "enabled": True}], after)
        before = [m["request"] for kind, m in log[:ping] if kind == "sent"]
        self.assertEqual(
            [{"subtype": "mcp_status"}, {"subtype": "mcp_authenticate", "serverName": CONNECTOR}, {"subtype": "mcp_status"},
             {"subtype": "mcp_toggle", "serverName": CONNECTOR, "enabled": False},
             {"subtype": "mcp_toggle", "serverName": CONNECTOR, "enabled": True},
             {"subtype": "mcp_reconnect", "serverName": CONNECTOR}], before)

    def test_the_connector_scenario_stops_polling_once_the_status_changes(self):
        def polls(cli: ConnectorCli, **kwargs) -> tuple[int, list[float], str]:
            slept: list[float] = []
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                drive_cli.mcp_connectors(cli, CONNECTOR, sleep=slept.append, **kwargs)
            subtypes = [r["subtype"] for r in requests(cli)]
            auth = subtypes.index("mcp_authenticate")
            return subtypes[auth:subtypes.index("mcp_toggle")].count("mcp_status"), slept, err.getvalue()

        count, slept, err = polls(ConnectorCli(connected_on=3))
        self.assertEqual(3, count)
        self.assertEqual([2.0, 2.0], slept)
        self.assertIn("open this link within 3 minutes: https://auth.example-vendor.com/authorize?", err)
        # Never connected: polls every `every` seconds until `timeout` has passed.
        count, slept, _ = polls(ConnectorCli(connected_on=None), every=2.0, timeout=6.0)
        self.assertEqual(4, count)
        self.assertEqual([2.0, 2.0, 2.0], slept)

    def test_a_refused_authenticate_still_polls_and_runs_the_rest(self):
        # The poll is the window for spec 3.3 step 4's terminal login (plan M5r: mcp_status every 2 s in every case).
        cli = ConnectorCli(connected_on=2, auth_error=True)
        slept: list[float] = []
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            drive_cli.mcp_connectors(cli, CONNECTOR, sleep=slept.append)
        toggle = {"subtype": "mcp_toggle", "serverName": CONNECTOR}
        self.assertEqual(
            [{"subtype": "mcp_status"}, {"subtype": "mcp_authenticate", "serverName": CONNECTOR},
             {"subtype": "mcp_status"}, {"subtype": "mcp_status"},
             {**toggle, "enabled": False}, {**toggle, "enabled": True}, {"subtype": "mcp_reconnect", "serverName": CONNECTOR},
             {"subtype": "mcp_status"}, {**toggle, "enabled": False}, {**toggle, "enabled": True}], requests(cli))
        self.assertEqual([2.0], slept)
        self.assertEqual(1, sum(m["type"] == "user" for m in cli.sent), "the ping ran")
        self.assertIn(f'mcp_authenticate was refused: Unknown subtype; run claude mcp login "{CONNECTOR}" in a terminal',
                      err.getvalue())
        self.assertNotIn("open this link", err.getvalue())

    def test_a_ping_without_init_says_the_mid_turn_requests_were_not_sent(self):
        cli = ConnectorCli(init=False)
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            drive_cli.mcp_connectors(cli, CONNECTOR, sleep=lambda s: None)
        ping = next(i for i, m in enumerate(cli.sent) if m["type"] == "user")
        self.assertEqual([{"subtype": "mcp_toggle", "serverName": CONNECTOR, "enabled": True}],
                         [m["request"] for m in cli.sent[ping + 1:]])
        self.assertIn("no system/init came before the ping's result", err.getvalue())

    def test_record_scenario_hands_the_connector_flag_to_the_redactor(self):
        """record-scenario.sh with --connector and no TANDEM_PROBE_CONNECTOR, against a claude double whose strict mode
        drops the connector and whose errors name it: the committed files never hold the name."""
        name = "claude.ai Probe"
        fake_bin = os.path.join(self.dir, "bin")
        os.makedirs(fake_bin)
        claude = os.path.join(fake_bin, "claude")
        with open(claude, "w", encoding="utf-8") as f:
            f.write(FAKE_CLAUDE.replace("#!python3", "#!" + sys.executable, 1))
        os.chmod(claude, 0o755)
        out = os.path.join(self.dir, "out")
        env = {k: v for k, v in os.environ.items() if k != "TANDEM_PROBE_CONNECTOR"}
        env.update(PATH=fake_bin + os.pathsep + env.get("PATH", ""), CLAUDE_CONFIG_DIR=os.path.join(self.dir, "config"))
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "record-scenario.sh")
        done = subprocess.run(["bash", script, "mcp-connectors", out, "--connector", name], env=env,
                              capture_output=True, text=True, timeout=60)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertNotIn("is not set", done.stderr, "the redactor got the name")
        for path in ("mcp-connectors.ndjson", os.path.join("transcripts", "mcp-connectors.jsonl")):
            with open(os.path.join(out, path), encoding="utf-8") as f:
                text = f.read()
            self.assertNotIn("Probe", text, path)
            self.assertIn("Server not found: claude.ai Connector-1", text, path)

    def test_the_bash_scenarios_send_the_probe_frames(self):
        command = "<bash-input>echo bash-mode-probe && printf err >&2</bash-input>"
        output = "<bash-stdout>bash-mode-probe\nerr</bash-stdout><bash-stderr></bash-stderr>"
        with open(os.path.join(FIXTURES, "transcripts", "bash-mode.jsonl"), encoding="utf-8") as f:
            context = json.loads(f.readlines()[4])["message"]["content"]  # the line the CLI wrote for the merged pair
        self.assertEqual(command + "\n" + output, context)
        prompt = [{"type": "text", "text": "Reply with only the text the command printed to stdout"}]

        def frames(run, *args, **kwargs) -> tuple[list[tuple], list[float]]:
            cli = FakeCli(success)
            slept: list[float] = []
            if "sleep" in kwargs:
                kwargs["sleep"] = slept.append
            run(cli, *args, **kwargs)
            users = [m for m in cli.sent if m["type"] == "user"]
            self.assertEqual(len(cli.sent), len(users), "no control requests")
            self.assertEqual(len(users), len({m["uuid"] for m in users}), "one uuid per frame")
            return [(m["message"]["content"], m.get("shouldQuery", "absent"), m.get("client_composed", "absent"))
                    for m in users], slept

        self.assertEqual(([(command, False, True), (output, False, True), (prompt, "absent", "absent")], [3]),
                         frames(drive_cli.bash_mode, sleep=True))
        self.assertEqual(([(command, False, True), (output, "absent", True)], [3.0]),
                         frames(drive_cli.bash_mode_respond, sleep=True))
        original = drive_cli.bash_mode_respond
        slept: list[float] = []
        with mock.patch.object(drive_cli, "bash_mode_respond",
                               side_effect=lambda cli, **kw: original(cli, sleep=slept.append, **kw)) as respond:
            sent, _ = frames(drive_cli.bash_mode_respond_together)
        self.assertEqual([(command, False, True), (output, "absent", True)], sent)
        respond.assert_called_once()
        self.assertEqual(0, respond.call_args.kwargs["wait"], "together: no wait between the frames")
        self.assertEqual([], slept)
        self.assertEqual(([(context, False, True), (prompt, "absent", "absent")], [3]),
                         frames(drive_cli.bash_mode_context, sleep=True))
        for name in ("bash-mode", "bash-mode-respond", "bash-mode-respond-together", "bash-mode-context",
                     "mcp-connectors", "mcp-connectors-all"):
            self.assertIn(name, drive_cli.SCENARIOS)

    def test_result_of_matches_the_result_that_lists_the_uuid(self):
        ends = drive_cli.result_of("u")
        self.assertTrue(ends({"type": "result", "user_message_uuids": ["q", "u"]}))
        self.assertFalse(ends({"type": "result", "user_message_uuids": ["q1", "q2"]}))
        self.assertTrue(ends({"type": "result"}), "a CLI that lists no uuids ends it with its next result")
        self.assertTrue(ends({"type": "result", "user_message_uuids": []}))
        self.assertFalse(ends({"type": "assistant", "user_message_uuids": ["u"]}))

    def test_the_bash_scenarios_wait_past_the_quiet_frames_result(self):
        for run in (drive_cli.bash_mode, drive_cli.bash_mode_respond, drive_cli.bash_mode_respond_together,
                    drive_cli.bash_mode_context):
            cli = QuietResultCli()
            if run is drive_cli.bash_mode_respond_together:
                run(cli)
            else:
                run(cli, sleep=lambda s: None)
            read = [m for kind, m in cli.log if kind == "read"]
            self.assertEqual(2, len(read), run.__name__)
            self.assertEqual(0, read[0]["num_turns"], run.__name__)
            self.assertEqual([cli.sent[-1]["uuid"]], read[1]["user_message_uuids"], run.__name__)
            self.assertEqual({"type": "marker"}, cli.inbox.get_nowait(), run.__name__)
            self.assertTrue(cli.inbox.empty(), run.__name__)



class ToolCallCli(FakeCli):
    """A CLI whose every prompt asks for one MCP tool call (can_use_tool) before its result."""

    def send(self, msg: dict) -> None:
        self.sent.append(msg)
        if msg.get("type") == "control_request":
            self.inbox.put(self.answer(msg["request_id"], msg["request"]))
        elif msg.get("type") == "user":
            self.inbox.put({"type": "control_request", "request_id": f"cli-{len(self.sent)}",
                            "request": {"subtype": "can_use_tool", "tool_name": "mcp__echo__echo",
                                        "input": {"text": "x"}, "tool_use_id": f"toolu_{len(self.sent)}"}})
            self.inbox.put({"type": "result", "user_message_uuids": [msg["uuid"]]})


class ProbeScenarioTest(unittest.TestCase):
    def test_the_mcp_status_scenario_sends_every_mcp_request_between_three_turns(self):
        cli = FakeCli(success)
        drive_cli.mcp_status(cli)
        kinds = [m["request"]["subtype"] if m["type"] == "control_request" else m["message"]["content"][0]["text"]
                 for m in cli.sent]
        self.assertEqual(["mcp_status", "Reply with the single word: one", "mcp_status", "mcp_toggle", "mcp_status",
                          "Reply with the single word: two", "mcp_toggle", "mcp_status", "mcp_reconnect", "mcp_reconnect",
                          "mcp_toggle", "mcp_status", "mcp_authenticate", "mcp_status", "mcp_set_servers", "mcp_status",
                          "mcp_set_servers", "mcp_status", "Reply with the single word: three"], kinds)
        named = [(r.get("serverName"), r.get("enabled")) for r in requests(cli) if "serverName" in r]
        self.assertEqual([("echo", False), ("echo", True), ("broken", None), ("authy", None), ("nosuch", False),
                          ("authy", None)], named)
        added = [r["servers"] for r in requests(cli) if r["subtype"] == "mcp_set_servers"]
        self.assertEqual([{"echo2": drive_cli.echo_config()}, {}], added)
        self.assertEqual(["-I", drive_cli.PROBE_SERVERS, "echo"], drive_cli.echo_config()["args"])

    def test_the_mcp_status_scenario_records_the_expected_errors_and_fails_on_others(self):
        def refusing(allowed: set):
            def answer(request_id: str, request: dict) -> dict:
                key = (request["subtype"], request.get("serverName"))
                return error(request_id, request) if key in allowed else success(request_id, request)
            return answer

        expected = {("mcp_reconnect", "broken"), ("mcp_reconnect", "authy"), ("mcp_toggle", "nosuch"),
                    ("mcp_authenticate", "authy")}
        drive_cli.mcp_status(FakeCli(refusing(expected)))  # goes on
        for fatal in (("mcp_status", None), ("mcp_toggle", "echo"), ("mcp_set_servers", None)):
            with self.assertRaises(RuntimeError, msg=fatal):
                drive_cli.mcp_status(FakeCli(refusing({fatal})))

    def test_the_tool_call_scenario_allows_the_first_call_and_denies_the_second(self):
        cli = ToolCallCli(success)
        drive_cli.mcp_tool_call(cli)
        answers = [m["response"]["response"] for m in cli.sent if m["type"] == "control_response"]
        self.assertEqual(["allow", "deny"], [a["behavior"] for a in answers])
        self.assertEqual({"text": "x"}, answers[0]["updatedInput"])
        self.assertEqual("The user denied this call.", answers[1]["message"])
        prompts = [m["message"]["content"][0]["text"] for m in cli.sent if m["type"] == "user"]
        self.assertIn("mcp__echo__echo", prompts[0])
        self.assertIn("hello tandem", prompts[0])
        self.assertIn("second", prompts[1])

    def test_the_slash_scenario_sends_four_commands_each_to_its_result(self):
        cli = FakeCli(success)
        drive_cli.mcp_slash(cli)
        self.assertEqual(["/mcp", "/model", "/help", "/hello"],
                         [m["message"]["content"][0]["text"] for m in cli.sent])

    def test_the_probe_scenarios_drop_safe_mode_and_name_the_probe_config(self):
        argvs = []

        class Popen:  # a claude that exits at once: main fails on initialize, after the argv is built
            def __init__(self, argv, **kwargs):
                argvs.append(argv)
                self.stdin, self.stdout = io.StringIO(), io.StringIO("")

            def wait(self, timeout=None):
                return 0

        servers = mock.Mock()
        with tempfile.TemporaryDirectory() as work, contextlib.chdir(work):
            for scenario in ("mcp-status", "mcp-tool-call", "mcp-slash"):
                with mock.patch.object(drive_cli.subprocess, "Popen", Popen), \
                        mock.patch.object(drive_cli, "start_probe_servers", return_value=servers) as start, \
                        contextlib.redirect_stderr(io.StringIO()), self.assertRaisesRegex(RuntimeError, "exited"):
                    drive_cli.main(scenario, os.path.join(work, f"{scenario}.ndjson"), ["--model", "m"])
                start.assert_called_once_with(os.getcwd(), scenario)
            for argv in argvs:
                self.assertNotIn("--safe-mode", argv)
                at = argv.index("--mcp-config")
                self.assertEqual(["--mcp-config", os.path.join(os.getcwd(), "mcp.json"), "--strict-mcp-config",
                                  "--setting-sources", "project,local"], argv[at:at + 5])
                self.assertIn("--model", argv)
        self.assertEqual(3, servers.kill.call_count, "authy ends with every run")
        for name in ("mcp-status", "mcp-tool-call", "mcp-slash"):
            self.assertIn(name, redact_fixture.INIT_KEEP, "the redactor keeps the lists the fixture's tests read")

    def test_the_probe_servers_start_and_answer(self):
        with tempfile.TemporaryDirectory() as work:
            authy = drive_cli.start_probe_servers(work, "mcp-slash")
            try:
                with open(os.path.join(work, "mcp.json"), encoding="utf-8") as f:
                    servers = json.load(f)["mcpServers"]
                self.assertEqual(["echo", "broken", "authy"], list(servers))
                self.assertEqual(drive_cli.echo_config(), servers["echo"])
                self.assertEqual("/bin/false", servers["broken"]["command"])
                url = servers["authy"]["url"]
                self.assertRegex(url, r"^http://127\.0\.0\.1:\d+/mcp$")
                with open(os.path.join(work, ".claude", "commands", "hello.md"), encoding="utf-8") as f:
                    self.assertEqual(drive_cli.HELLO_COMMAND, f.read())
                import urllib.error
                import urllib.request
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                with self.assertRaises(urllib.error.HTTPError) as refused:
                    opener.open(urllib.request.Request(url, data=b"{}", method="POST"), timeout=10)
                self.assertEqual(401, refused.exception.code)
                self.assertIn("resource_metadata=", refused.exception.headers["WWW-Authenticate"])
                base = url.removesuffix("/mcp")
                with opener.open(base + "/.well-known/oauth-authorization-server", timeout=10) as answer:
                    self.assertEqual(base + "/authorize", json.load(answer)["authorization_endpoint"])
            finally:
                authy.kill()
                authy.wait()
        with tempfile.TemporaryDirectory() as work:
            authy = drive_cli.start_probe_servers(work, "mcp-status")
            authy.kill()
            authy.wait()
            self.assertFalse(os.path.exists(os.path.join(work, ".claude")), "only mcp-slash gets the project command")

    def test_the_echo_server_answers_as_an_mcp_server(self):
        import mcp_probe_servers
        frames = [{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
                  {"jsonrpc": "2.0", "method": "notifications/initialized"},
                  {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                  {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                   "params": {"name": "echo", "arguments": {"text": "hello tandem"}}},
                  {"jsonrpc": "2.0", "id": 4, "method": "resources/list"}]
        out = io.StringIO()
        mcp_probe_servers.serve_echo(io.StringIO("\n".join(json.dumps(f) for f in frames) + "\nnot json\n"), out)
        answers = [json.loads(line) for line in out.getvalue().splitlines()]
        self.assertEqual([1, 2, 3, 4], [a["id"] for a in answers], "a notification gets no answer")
        self.assertEqual({"name": "echo", "version": "0.1.0"}, answers[0]["result"]["serverInfo"])
        self.assertEqual("2025-06-18", answers[0]["result"]["protocolVersion"])
        self.assertEqual(["echo"], [t["name"] for t in answers[1]["result"]["tools"]])
        self.assertIn("outputSchema", answers[1]["result"]["tools"][0])
        self.assertEqual({"content": [{"type": "text", "text": "hello tandem"}],
                          "structuredContent": {"result": "hello tandem"}, "isError": False}, answers[2]["result"])
        self.assertEqual(-32601, answers[3]["error"]["code"])

if __name__ == "__main__":
    unittest.main()

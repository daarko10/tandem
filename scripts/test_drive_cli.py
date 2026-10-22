"""Tests for drive_cli.py's Cli.call, without a real `claude`. Run from scripts/: python3 -m unittest"""
import base64
import contextlib
import importlib
import io
import json
import os
import queue
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
    mcp_authenticate answers with a sign-in link on a vendor host; a prompt streams an init and a delta before its
    result. `log` holds what was sent and read, in order; `lines` holds what the CLI printed, as Cli.lines does."""

    def __init__(self, name: str = CONNECTOR, connected_on: int | None = 1):
        super().__init__(self.respond)
        self.name = name
        self.connected_on = connected_on
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
            self.emit({"type": "system", "subtype": "init", "session_id": "s",
                       "tools": ["Bash", f"mcp__{self.name.replace('.', '_').replace(' ', '_')}__search", "mcp__echo__echo"],
                       "mcp_servers": [{"name": self.name, "status": "connected", "source": "claudeai"},
                                       {"name": "echo", "status": "connected", "source": "user"}]})
            self.emit({"type": "stream_event", "event": {"type": "content_block_delta"}})
            self.emit({"type": "result", "subtype": "success", "num_turns": 1})


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

        class Popen:  # a claude that exits at once: main fails on initialize, after the argv is built
            def __init__(self, argv, **_kwargs):
                argvs.append(argv)
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
        with mock.patch.object(drive_cli.time, "sleep", side_effect=AssertionError("slept between the respond frames")):
            self.assertEqual(([(command, False, True), (output, "absent", True)], []),
                             frames(drive_cli.bash_mode_respond_together))
        self.assertEqual(([(context, False, True), (prompt, "absent", "absent")], [3]),
                         frames(drive_cli.bash_mode_context, sleep=True))
        for name in ("bash-mode", "bash-mode-respond", "bash-mode-respond-together", "bash-mode-context",
                     "mcp-connectors", "mcp-connectors-all"):
            self.assertIn(name, drive_cli.SCENARIOS)


if __name__ == "__main__":
    unittest.main()

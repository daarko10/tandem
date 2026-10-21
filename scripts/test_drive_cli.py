"""Tests for drive_cli.py's Cli.call, without a real `claude`. Run from scripts/: python3 -m unittest"""
import base64
import queue
import unittest

import drive_cli


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


if __name__ == "__main__":
    unittest.main()

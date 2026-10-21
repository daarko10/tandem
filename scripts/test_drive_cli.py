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

    def test_the_tiny_png_is_pinned(self):
        # TranscriptReaderTest holds the same literal as TINY_PNG_BASE64: the recorded transcript keeps these bytes.
        self.assertEqual(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP4z8AARAwQCgAf7gP9i18U1AAAAABJRU5ErkJggg==",
            base64.b64encode(drive_cli.tiny_png()).decode())


if __name__ == "__main__":
    unittest.main()

"""Tests for drive_cli.py's Cli.call, without a real `claude`. Run from scripts/: python3 -m unittest"""
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

    def test_the_settings_probe_records_rejected_requests_and_goes_on(self):
        cli = FakeCli(error)
        drive_cli.settings(cli)
        self.assertEqual(2, sum(m["type"] == "user" for m in cli.sent), "both turns ran")


if __name__ == "__main__":
    unittest.main()

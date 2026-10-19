"""Tests for redact_fixture.py. Run from scripts/: python3 -m unittest"""
import contextlib
import importlib
import io
import json
import os
import tempfile
import unittest

import redact_fixture


class RedactTest(unittest.TestCase):
    def setUp(self):
        importlib.reload(redact_fixture)  # the placeholders are module state, numbered per run
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def redact(self, *lines) -> tuple[list[dict], str]:
        """Redacts `lines` (dicts, or raw strings) and returns the output lines and what went to stderr."""
        src = os.path.join(self.dir.name, "in.ndjson")
        dst = os.path.join(self.dir.name, "out.ndjson")
        with open(src, "w", encoding="utf-8") as f:
            for line in lines:
                f.write((line if isinstance(line, str) else json.dumps(line)) + "\n")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            redact_fixture.redact(src, dst)
        with open(dst, encoding="utf-8") as f:
            return [json.loads(line) for line in f if line.strip()], err.getvalue()

    def test_a_uuid_under_a_key_no_rule_knows_is_caught_and_named(self):
        uuid = "0b0e5c1a-3f2d-4c6b-9a8e-7d6c5b4a3f2e"
        (out,), err = self.redact({"type": "system", "subtype": "x", "nested": {"note": f"see {uuid} here"}})
        self.assertEqual("see <leaked-1> here", out["nested"]["note"])
        self.assertIn("unredacted id at nested.note", err)
        self.assertNotIn(uuid, json.dumps(out))

    def test_an_api_id_under_a_key_no_rule_knows_is_caught(self):
        (out,), err = self.redact({"type": "assistant", "items": [{"ref": "toolu_01AbCdEf"}]})
        self.assertEqual("<leaked-1>", out["items"][0]["ref"])
        self.assertIn("unredacted id at items[0].ref", err)

    def test_the_same_leaked_value_gets_the_same_placeholder_everywhere(self):
        uuid = "11111111-2222-4333-8444-555555555555"
        out, _ = self.redact({"a": uuid}, {"b": [uuid], "c": "11111111-2222-4333-8444-666666666666"})
        self.assertEqual("<leaked-1>", out[0]["a"])
        self.assertEqual(["<leaked-1>"], out[1]["b"])
        self.assertEqual("<leaked-2>", out[1]["c"])

    def test_ids_the_rules_know_are_not_reported_as_leaks(self):
        (out,), err = self.redact({"type": "user", "uuid": "11111111-2222-4333-8444-555555555555",
                                   "session_id": "99999999-2222-4333-8444-555555555555"})
        self.assertEqual("<uuid-1>", out["uuid"])
        self.assertEqual("<session_id-2>", out["session_id"])
        self.assertEqual("", err)

    def test_a_second_run_over_redacted_output_changes_nothing_and_reports_nothing(self):
        first, _ = self.redact({"uuid": "11111111-2222-4333-8444-555555555555", "x": "toolu_01Ab",
                                "path": "/home/someone/project"})
        second, err = self.redact(*first)
        self.assertEqual(first, second)
        self.assertEqual("", err)

    def test_task_and_agent_ids_get_placeholders_correlated_across_lines(self):
        out, err = self.redact(
            {"type": "system", "subtype": "task_started", "task_id": "a1b2c3d4e5f6a7b8c", "tool_use_id": "toolu_01Spawn"},
            {"type": "user", "parent_tool_use_id": "toolu_01Spawn", "agent_id": "a1b2c3d4e5f6a7b8c"},
            {"type": "control_request", "request": {"tool_use_id": "toolu_01Inner", "agent_id": "a1b2c3d4e5f6a7b8c"}})
        self.assertEqual("<task_id-1>", out[0]["task_id"])
        self.assertEqual("<tool_use_id-2>", out[0]["tool_use_id"])
        self.assertEqual("<tool_use_id-2>", out[1]["parent_tool_use_id"])  # the same value as the tool_use it points at
        self.assertEqual("<task_id-1>", out[1]["agent_id"])  # the same value as the task
        self.assertEqual("<task_id-1>", out[2]["request"]["agent_id"])
        self.assertEqual("<tool_use_id-3>", out[2]["request"]["tool_use_id"])
        self.assertNotIn("a1b2c3d4e5f6a7b8c", json.dumps(out))
        self.assertEqual("", err)

    def test_long_base64_payloads_are_replaced(self):
        payload = "iVBORw0KGgo=" * 30  # 360 base64 characters
        (out,), err = self.redact({
            "type": "user",
            "message": {"content": [{"type": "tool_result", "content": [
                {"type": "image", "source": {"type": "base64", "data": payload, "media_type": "image/png"}}]}]},
            "tool_use_result": {"type": "image", "file": {"base64": payload, "type": "image/png", "originalSize": 80}}})
        self.assertEqual("<base64>", out["message"]["content"][0]["content"][0]["source"]["data"])
        self.assertEqual("<base64>", out["tool_use_result"]["file"]["base64"])
        self.assertEqual("image/png", out["message"]["content"][0]["content"][0]["source"]["media_type"])
        self.assertEqual(80, out["tool_use_result"]["file"]["originalSize"])
        self.assertNotIn(payload, json.dumps(out))
        self.assertEqual("", err)

    def test_short_or_non_base64_data_strings_are_untouched(self):
        long_but_not_base64 = "a few words " * 30  # spaces: not a base64 payload
        long_under_another_key = "QUJD" * 100
        (out,), err = self.redact({"data": "hello", "base64": "QUJD", "note": {"data": long_but_not_base64},
                                   "blob": long_under_another_key, "n": 1})
        self.assertEqual("hello", out["data"])
        self.assertEqual("QUJD", out["base64"])
        self.assertEqual(long_but_not_base64, out["note"]["data"])
        self.assertEqual(long_under_another_key, out["blob"])
        self.assertEqual("", err)

    def test_a_second_run_over_ids_and_base64_is_byte_identical(self):
        src = os.path.join(self.dir.name, "sample.ndjson")
        first = os.path.join(self.dir.name, "first.ndjson")
        second = os.path.join(self.dir.name, "second.ndjson")
        with open(src, "w", encoding="utf-8") as f:
            for line in (
                {"type": "system", "subtype": "task_started", "task_id": "a1b2c3d4e5f6a7b8c", "tool_use_id": "toolu_01Spawn",
                 "uuid": "11111111-2222-4333-8444-555555555555"},
                {"type": "user", "parent_tool_use_id": "toolu_01Spawn", "agent_id": "a1b2c3d4e5f6a7b8c",
                 "message": {"content": [{"type": "image", "source": {"type": "base64", "data": "QUJD" * 100}}]},
                 "tool_use_result": {"file": {"base64": "QUJD" * 100}}, "cwd": "/home/someone/project"},
            ):
                f.write(json.dumps(line) + "\n")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            redact_fixture.redact(src, first)
            importlib.reload(redact_fixture)  # a fresh run, as a later recording session would be
            redact_fixture.redact(first, second)
        with open(first, "rb") as a, open(second, "rb") as b:
            self.assertEqual(a.read(), b.read())
        self.assertEqual("", err.getvalue())
        with open(second, encoding="utf-8") as f:
            text = f.read()
        self.assertIn('"<base64>"', text)
        self.assertIn('"<task_id-1>"', text)

    def test_a_line_that_is_not_a_json_object_is_dropped_with_a_warning(self):
        out, err = self.redact("[1, 2]", "not json", {"type": "kept"})
        self.assertEqual([{"type": "kept"}], out)
        self.assertIn("line 1: not a JSON object, dropped", err)
        self.assertIn("line 2: not a JSON object, dropped", err)


if __name__ == "__main__":
    unittest.main()

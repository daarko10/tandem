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

    def test_a_line_that_is_not_a_json_object_is_dropped_with_a_warning(self):
        out, err = self.redact("[1, 2]", "not json", {"type": "kept"})
        self.assertEqual([{"type": "kept"}], out)
        self.assertIn("line 1: not a JSON object, dropped", err)
        self.assertIn("line 2: not a JSON object, dropped", err)


if __name__ == "__main__":
    unittest.main()

"""Tests for copy_transcript.py. Run from scripts/: python3 -m unittest"""
import contextlib
import io
import json
import os
import tempfile
import unittest
from unittest import mock

import copy_transcript

FIRST = "11111111-1111-4111-8111-111111111111"
SECOND = "22222222-2222-4222-8222-222222222222"


class CopyTranscriptTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = temp.name
        self.config = os.path.join(self.root, "config")
        self.work = os.path.join(self.root, "work")
        self.out = os.path.join(self.root, "out")
        os.makedirs(self.work)
        environment = mock.patch.dict(os.environ, {"CLAUDE_CONFIG_DIR": self.config})
        environment.start()
        self.addCleanup(environment.stop)

    def folder(self) -> str:
        return os.path.join(self.config, "projects", copy_transcript.folder_key(self.work))

    def stream(self, *session_ids: str) -> str:
        path = os.path.join(self.root, "raw.ndjson")
        with open(path, "w", encoding="utf-8") as f:
            f.write("not json\n")
            for session_id in session_ids:
                f.write(json.dumps({"type": "system", "subtype": "init", "session_id": session_id}) + "\n")
                f.write(json.dumps({"type": "result", "session_id": session_id}) + "\n")
        return path

    def transcript(self, session_id: str, *lines: str) -> str:
        os.makedirs(self.folder(), exist_ok=True)
        path = os.path.join(self.folder(), f"{session_id}.jsonl")
        body = lines or (json.dumps({"type": "user", "sessionId": session_id}), "",
                         json.dumps({"type": "summary"}))
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(body) + "\n")
        return path

    def run_main(self, stream: str) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            copy_transcript.main(self.work, stream, self.out, "name")

    def assert_fails(self, stream: str, message: str) -> None:
        with self.assertRaises(SystemExit) as raised:
            self.run_main(stream)
        self.assertIn(message, str(raised.exception))
        self.assertFalse(os.path.exists(self.out), "nothing is copied when a check fails")

    def test_the_folder_key_is_the_physical_directory_with_every_other_character_a_dash(self):
        link = os.path.join(self.root, "link")
        os.symlink(self.work, link)
        expected = "".join(c if c.isascii() and c.isalnum() else "-" for c in os.path.realpath(self.work))
        self.assertEqual(expected, copy_transcript.folder_key(link))
        self.assertEqual(200, len(copy_transcript.folder_key("/" + "a" * 300)))

    def test_the_session_s_transcript_is_copied(self):
        source = self.transcript(FIRST)
        self.run_main(self.stream(FIRST, FIRST))
        with open(source, encoding="utf-8") as a, open(os.path.join(self.out, "name.jsonl"), encoding="utf-8") as b:
            self.assertEqual(a.read(), b.read())
        self.assertEqual(["name.jsonl"], os.listdir(self.out))

    def test_a_fork_also_copies_the_session_it_started_from(self):
        self.transcript(FIRST)
        self.transcript(SECOND)
        self.run_main(self.stream(FIRST, SECOND))
        with open(os.path.join(self.out, "name.jsonl"), encoding="utf-8") as f:
            self.assertIn(SECOND, f.read())
        with open(os.path.join(self.out, "name-origin.jsonl"), encoding="utf-8") as f:
            self.assertIn(FIRST, f.read())

    def test_a_stream_without_a_session_fails(self):
        self.assert_fails(self.stream(), "no system/init line with a session_id")

    def test_a_run_with_two_forks_copies_the_first_and_the_last(self):
        third = "33333333-3333-4333-8333-333333333333"
        self.transcript(FIRST)
        self.transcript(SECOND)
        self.transcript(third)
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            copy_transcript.main(self.work, self.stream(FIRST, SECOND, third), self.out, "name")
        self.assertEqual(["name-origin.jsonl", "name.jsonl"], sorted(os.listdir(self.out)))
        with open(os.path.join(self.out, "name.jsonl"), encoding="utf-8") as f:
            self.assertIn(third, f.read())
        with open(os.path.join(self.out, "name-origin.jsonl"), encoding="utf-8") as f:
            self.assertIn(FIRST, f.read())
        self.assertIn(f"{SECOND} not copied", err.getvalue())

    def test_a_run_with_two_forks_whose_origin_is_missing_copies_nothing(self):
        third = "33333333-3333-4333-8333-333333333333"
        self.transcript(SECOND)
        self.transcript(third)
        self.assert_fails(self.stream(FIRST, SECOND, third), "no transcript at")

    def test_a_missing_transcript_fails(self):
        self.assert_fails(self.stream(FIRST), "no transcript at")

    def test_a_fork_whose_origin_is_missing_copies_neither(self):
        self.transcript(SECOND)
        self.assert_fails(self.stream(FIRST, SECOND), "no transcript at")

    def test_a_symlink_fails(self):
        target = os.path.join(self.root, "elsewhere.jsonl")
        with open(target, "w", encoding="utf-8") as f:
            f.write(json.dumps({"sessionId": FIRST}) + "\n")
        os.makedirs(self.folder())
        os.symlink(target, os.path.join(self.folder(), f"{FIRST}.jsonl"))
        self.assert_fails(self.stream(FIRST), "is a symlink")

    def test_a_transcript_over_two_megabytes_fails(self):
        self.transcript(FIRST, json.dumps({"sessionId": FIRST, "pad": "x" * (2 * 1024 * 1024)}))
        self.assert_fails(self.stream(FIRST), "more than 2097152")

    def test_a_line_that_is_not_json_fails(self):
        self.transcript(FIRST, json.dumps({"sessionId": FIRST}), "{broken")
        self.assert_fails(self.stream(FIRST), "line 2 is not JSON")

    def test_a_line_that_is_not_an_object_fails(self):
        self.transcript(FIRST, "[1]")
        self.assert_fails(self.stream(FIRST), "line 1 is not a JSON object")

    def test_a_line_of_another_session_fails(self):
        self.transcript(FIRST, json.dumps({"sessionId": FIRST}), json.dumps({"sessionId": SECOND}))
        self.assert_fails(self.stream(FIRST), "line 2 belongs to another session")

    def test_a_transcript_without_the_session_id_fails(self):
        self.transcript(FIRST, json.dumps({"type": "summary"}))
        self.assert_fails(self.stream(FIRST), "has no line with the session id")


if __name__ == "__main__":
    unittest.main()

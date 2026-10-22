"""Tests for make_drift.py. Run from scripts/: python3 -m unittest"""
import contextlib
import io
import json
import os
import re
import shutil
import tempfile
import unittest

import make_drift

UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


class MakeDriftTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = temp.name
        self.out = os.path.join(self.root, "recording")
        quiet = contextlib.redirect_stderr(io.StringIO())
        quiet.__enter__()
        self.addCleanup(quiet.__exit__, None, None, None)

    def fixture(self, name: str) -> list[str]:
        with open(os.path.join(make_drift.FIXTURES, name), encoding="utf-8") as f:
            return f.readlines()

    def written(self, rel: str) -> list[str]:
        with open(os.path.join(self.out, rel), encoding="utf-8") as f:
            return f.readlines()

    def recording_from_fixture(self, directory: str) -> str:
        os.makedirs(os.path.join(directory, "transcripts"))
        shutil.copy(os.path.join(make_drift.FIXTURES, "history-reply.ndjson"),
                    os.path.join(directory, "contract-hello.ndjson"))
        for rel in ("transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"):
            shutil.copy(os.path.join(make_drift.FIXTURES, "transcripts", "history-reply.jsonl"),
                        os.path.join(directory, rel))
        return directory

    def test_init_model_key_renames_only_the_init_line_s_model_key(self):
        make_drift.main("init-model-key", self.out)
        source, out = self.fixture("history-reply.ndjson"), self.written("contract-hello.ndjson")
        self.assertEqual(len(source), len(out))
        self.assertEqual(source[:1] + source[2:], out[:1] + out[2:])  # every other line byte-identical
        before, after = json.loads(source[1]), json.loads(out[1])
        self.assertEqual("claude-sonnet-5-5", after["model_id"])
        self.assertNotIn("model", after)
        self.assertEqual([("model_id" if k == "model" else k) for k in before], list(after))
        twin = self.fixture("transcripts/history-reply.jsonl")
        self.assertEqual(twin, self.written("transcripts/contract-hello.jsonl"))
        self.assertEqual(twin, self.written("transcripts/session-id.jsonl"))

    def test_transcript_content_key_renames_content_on_every_user_line_of_both_transcripts(self):
        make_drift.main("transcript-content-key", self.out)
        self.assertEqual(self.fixture("history-reply.ndjson"), self.written("contract-hello.ndjson"))
        source = self.fixture("transcripts/history-reply.jsonl")
        for rel in ("transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"):
            out = self.written(rel)
            self.assertEqual(len(source), len(out))
            renamed = 0
            for a, b in zip(source, out):
                if json.loads(a).get("type") == "user":
                    before, after = json.loads(a), json.loads(b)
                    self.assertEqual(before["message"]["content"], after["message"]["blocks"])
                    self.assertNotIn("content", after["message"])
                    renamed += 1
                else:
                    self.assertEqual(a, b)
            self.assertEqual(1, renamed)

    def test_the_output_layout_and_the_marker(self):
        make_drift.main("init-model-key", self.out)
        files = sorted(os.path.relpath(os.path.join(d, f), self.out) for d, _, fs in os.walk(self.out) for f in fs)
        self.assertEqual(["contract-hello.ndjson", "rehearsal", "transcripts/contract-hello.jsonl",
                          "transcripts/session-id.jsonl"], files)
        self.assertEqual(["init-model-key\n"], self.written("rehearsal"))

    def test_from_mutates_a_recording_in_place(self):
        for mode in make_drift.MODES:
            recording = self.recording_from_fixture(os.path.join(self.root, mode))
            make_drift.main(mode, recording, source=recording)  # in place: everything read, then written
            make_drift.main(mode, os.path.join(self.out, mode))
            for rel in ("contract-hello.ndjson", "transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl",
                        "rehearsal"):
                with open(os.path.join(recording, rel), "rb") as a, open(os.path.join(self.out, mode, rel), "rb") as b:
                    self.assertEqual(a.read(), b.read(), rel)

    def test_from_refuses_an_incomplete_recording_before_writing(self):
        recording = os.path.join(self.root, "partial")
        os.makedirs(recording)
        shutil.copy(os.path.join(make_drift.FIXTURES, "history-reply.ndjson"),
                    os.path.join(recording, "contract-hello.ndjson"))
        with self.assertRaises(SystemExit) as raised:
            make_drift.main("init-model-key", self.out, source=recording)
        self.assertIn("transcripts/contract-hello.jsonl", str(raised.exception))
        self.assertFalse(os.path.exists(self.out))

    def test_the_placeholders_survive(self):
        for mode in make_drift.MODES:
            make_drift.main(mode, os.path.join(self.out, mode))
            for rel in ("contract-hello.ndjson", "transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"):
                with open(os.path.join(self.out, mode, rel), encoding="utf-8") as f:
                    text = f.read()
                self.assertIn("<session_id-2>", text)
                self.assertIsNone(UUID_RE.search(text))

    def test_writing_under_the_fixtures_directory_is_refused(self):
        link = os.path.join(self.root, "link")
        os.symlink(make_drift.FIXTURES, link)
        for out in (os.path.join(make_drift.FIXTURES, "x"), os.path.join(make_drift.FIXTURES, "..", "fixtures", "x"),
                    os.path.join(link, "x")):
            with self.assertRaises(SystemExit) as raised:
                make_drift.main("init-model-key", out)
            self.assertIn("refusing to write under", str(raised.exception))
            self.assertFalse(os.path.exists(out))

    def test_an_unknown_mode_is_refused(self):
        with self.assertRaises(SystemExit) as raised:
            make_drift.main("rename-everything", self.out)
        self.assertIn("rename-everything", str(raised.exception))
        self.assertFalse(os.path.exists(self.out))

    def test_from_finds_the_init_line_by_content(self):
        recording = os.path.join(self.root, "live")
        os.makedirs(os.path.join(recording, "transcripts"))
        # plain-reply has system/init on line 4, behind active_goal, autocompact_state and system/ui_invalidate.
        shutil.copy(os.path.join(make_drift.FIXTURES, "plain-reply.ndjson"),
                    os.path.join(recording, "contract-hello.ndjson"))
        for rel in ("transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl"):
            shutil.copy(os.path.join(make_drift.FIXTURES, "transcripts", "history-reply.jsonl"),
                        os.path.join(recording, rel))
        source = self.fixture("plain-reply.ndjson")
        make_drift.main("init-model-key", recording, source=recording)
        with open(os.path.join(recording, "contract-hello.ndjson"), encoding="utf-8") as f:
            out = f.readlines()
        self.assertEqual(len(source), len(out))
        self.assertEqual(source[:3] + source[4:], out[:3] + out[4:])  # lines 1-3 and 5 onwards byte-identical
        before, after = json.loads(source[3]), json.loads(out[3])
        self.assertEqual(("system", "init"), (after["type"], after["subtype"]))
        self.assertEqual(before["model"], after["model_id"])
        self.assertNotIn("model", after)


if __name__ == "__main__":
    unittest.main()

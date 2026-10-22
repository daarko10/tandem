"""Tests for make_long_reply.py, without Gradle. Run from scripts/: python3 -m unittest"""
import json
import os
import shutil
import tempfile
import unittest

import make_long_reply

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "fixtures")


class MakeLongReplyTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = temp.name
        shutil.copyfile(os.path.join(FIXTURES, "plain-reply.ndjson"), os.path.join(self.dir, "plain-reply.ndjson"))

    def generate(self, *paragraphs: int) -> list[dict]:
        make_long_reply.main(self.dir, *paragraphs)
        with open(os.path.join(self.dir, "synthetic-long-reply.ndjson"), encoding="utf-8") as f:
            return [json.loads(line) for line in f if line.strip()]

    @staticmethod
    def events(lines: list[dict], kind: str) -> list[dict]:
        return [line["event"] for line in lines if line.get("type") == "stream_event" and line["event"]["type"] == kind]

    @staticmethod
    def assistant_texts(lines: list[dict]) -> list[str]:
        assistant = [line for line in lines if line.get("type") == "assistant"]
        return [block["text"] for block in assistant[0]["message"]["content"]] if len(assistant) == 1 else []

    def test_shape(self):
        lines = self.generate()
        deltas = self.events(lines, "content_block_delta")
        self.assertEqual(60, len(deltas))
        self.assertEqual(list(range(60)), [delta["index"] for delta in deltas])
        self.assertEqual("pong", deltas[-1]["delta"]["text"])
        texts = self.assistant_texts(lines)
        self.assertEqual(60, len(texts))
        self.assertEqual("pong", texts[-1])
        result = [line for line in lines if line.get("type") == "result"]
        self.assertEqual(1, len(result))
        self.assertTrue(result[0]["result"].endswith("\n\npong"), result[0]["result"][-40:])
        with open(os.path.join(self.dir, "plain-reply.ndjson"), encoding="utf-8") as f:
            plain = [json.loads(line) for line in f if line.strip()]
        init = [line for line in lines if line.get("type") == "system" and line.get("subtype") == "init"]
        self.assertEqual([line for line in plain if line.get("type") == "system" and line.get("subtype") == "init"], init)
        self.assertEqual("<session_id-2>", init[0]["session_id"])

    def test_output_is_deterministic(self):
        path = os.path.join(self.dir, "synthetic-long-reply.ndjson")
        make_long_reply.main(self.dir)
        with open(path, "rb") as f:
            first = f.read()
        os.remove(path)
        make_long_reply.main(self.dir)
        with open(path, "rb") as f:
            self.assertEqual(first, f.read())

    def test_paragraph_count_option(self):
        lines = self.generate(3)
        deltas = self.events(lines, "content_block_delta")
        self.assertEqual(3, len(deltas))
        texts = self.assistant_texts(lines)
        self.assertEqual(3, len(texts))
        self.assertEqual("pong", texts[2])


if __name__ == "__main__":
    unittest.main()

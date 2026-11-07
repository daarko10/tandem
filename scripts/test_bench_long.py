"""Tests for bench/make_long_session.py, without Gradle. Run from scripts/: python3 -m unittest"""
import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest

_SPEC = importlib.util.spec_from_file_location(
    "make_long_session", os.path.join(os.path.dirname(os.path.abspath(__file__)), "bench", "make_long_session.py"))
make_long_session = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(make_long_session)

PROJECT = "/home/u/my proj.v2"


class MakeLongSessionTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = temp.name

    def generate(self, turns: int, config: str = "config", seed: int = 1) -> str:
        with contextlib.redirect_stdout(io.StringIO()):
            return make_long_session.main(
                ["--turns", str(turns), "--project", PROJECT, "--config", os.path.join(self.dir, config), "--seed", str(seed)])

    def lines(self, path: str) -> list[dict]:
        with open(path, encoding="utf-8") as f:
            return [json.loads(line) for line in f]

    def test_every_line_is_a_json_object(self):
        lines = self.lines(self.generate(60))
        self.assertTrue(all(isinstance(line, dict) and "type" in line for line in lines))

    def test_first_prompt_is_a_user_line_with_a_prompt_id(self):
        lines = self.lines(self.generate(10))
        first = next(line for line in lines if line["type"] in ("user", "assistant"))
        self.assertEqual("user", first["type"])
        self.assertIn("promptId", first)
        self.assertIsNone(first["parentUuid"])
        self.assertTrue(first["message"]["content"][0]["text"].startswith("Let us start with src/app/Orders.kt"))

    def test_parent_chain_is_intact(self):
        seen = set()
        for line in self.lines(self.generate(80)):
            if "uuid" not in line:
                continue
            parent = line["parentUuid"]
            self.assertTrue(parent is None and not seen or parent in seen, line["uuid"])
            seen.add(line["uuid"])

    def test_n_prompts_and_the_fields_every_line_has(self):
        lines = self.lines(self.generate(120))
        prompts = [line for line in lines if line["type"] == "user" and "promptId" in line
                   and line["message"]["content"][0]["type"] == "text"]
        self.assertEqual(120, len(prompts))
        self.assertEqual(list(range(1, 121)), [p["turnPosition"]["turnIndex"] for p in prompts])
        for line in lines:
            if "uuid" in line:
                self.assertEqual(("2.1.319", PROJECT, "main"), (line["version"], line["cwd"], line["gitBranch"]))
        stamps = [line["timestamp"] for line in lines if "uuid" in line]
        self.assertEqual(sorted(stamps), stamps)
        self.assertTrue(lines[-1]["type"] == "cost-state")

    def test_the_mix(self):
        lines = self.lines(self.generate(400))
        tools = {block["name"] for line in lines if line["type"] == "assistant"
                 for block in line["message"]["content"] if block["type"] == "tool_use"}
        self.assertTrue({"Bash", "Read", "Grep", "Glob", "AskUserQuestion", "mcp__tandem__edit_file"} <= tools, tools)
        kinds = {block["type"] for line in lines if line["type"] == "assistant" for block in line["message"]["content"]}
        self.assertEqual({"text", "thinking", "tool_use"}, kinds)
        texts = [block["text"] for line in lines if line["type"] == "assistant"
                 for block in line["message"]["content"] if block["type"] == "text"]
        self.assertTrue(any(len(t) > 5000 for t in texts))
        self.assertTrue(any("```" in t and "## " in t for t in texts))
        self.assertTrue(texts[-1].startswith("Summary of the session (400 turns)"))
        results = [line for line in lines if line["type"] == "user" and "toolUseResult" in line]
        self.assertTrue(all(line["message"]["content"][0]["type"] == "tool_result" for line in results))
        self.assertEqual(1, sum(1 for line in lines if line["type"] == "ai-title"))

    def test_same_seed_same_bytes(self):
        with open(self.generate(50, "a"), "rb") as a, open(self.generate(50, "b"), "rb") as b:
            self.assertEqual(a.read(), b.read())
        with open(self.generate(50, "c", seed=2), "rb") as c, open(self.generate(50, "a"), "rb") as a:
            self.assertNotEqual(c.read(), a.read())

    def test_folder_key_matches_the_protocol_example(self):
        self.assertEqual("-home-u-my-proj-v2", make_long_session.folder_key(PROJECT))
        self.assertEqual("C--Users-dev-app", make_long_session.folder_key("C:\\Users\\dev\\app"))
        self.assertEqual("-a--", make_long_session.folder_key("/a\U0001F600"))
        path = self.generate(3)
        self.assertEqual("-home-u-my-proj-v2", os.path.basename(os.path.dirname(path)))
        self.assertEqual(os.path.join(self.dir, "config", "projects"), os.path.dirname(os.path.dirname(path)))


if __name__ == "__main__":
    unittest.main()

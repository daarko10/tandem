"""Tests for bench/ttft/detect.py and bench/ttft/report.py. Run from scripts/: python3 -m unittest.

The OCR tests read the committed frames in bench/ttft/fixtures/ (drawn by make_fixtures.py) and need the tesseract and ffmpeg
binaries; without them they are skipped.
"""
import contextlib
import io
import json
import os
import re
import shutil
import tempfile
import unittest

from bench.ttft import detect, report

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bench", "ttft", "fixtures")
HAVE_TOOLS = all(shutil.which(t) for t in ("tesseract", "ffmpeg", "ffprobe"))
COLD_ANCHOR = r"nothing\s*else"
WARM_ANCHOR = r"\(2\)"
PROMPT_BOX = (8, 180, 545, 33)


class IsPongTest(unittest.TestCase):
    def test_the_word_alone(self):
        for text in ("PONG", "PONG.", "● PONG", "@ PONG", "e PONG", "> PONG"):
            self.assertTrue(detect.is_pong(text), text)

    def test_the_prompt_and_its_echo_are_not_the_reply(self):
        for text in ("Reply with exactly the single word PONG and nothing else.", "echo PONG", "$ echo PONG", "Pong", "PONGS", "PING"):
            self.assertFalse(detect.is_pong(text), text)


def tsv(words):
    """tesseract TSV for words given as (block, line, text, left, top)."""
    header = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext"
    rows = [header]
    for n, (block, line, text, left, top) in enumerate(words):
        rows.append(f"5\t1\t{block}\t1\t{line}\t{n}\t{left}\t{top}\t{20 * len(text)}\t20\t95\t{text}")
    return "\n".join(rows)


class ParseAndReplyTest(unittest.TestCase):
    def test_lines_in_screen_coordinates(self):
        lines = detect.parse_tsv(tsv([(1, 1, "Reply", 10, 10), (1, 1, "else.", 200, 10), (1, 2, "PONG", 10, 80)]), (100, 50), 2)
        self.assertEqual([l.text for l in lines], ["Reply else.", "PONG"])
        self.assertEqual(lines[1].box(), [105, 90, 40, 10])

    def test_masked_words_are_dropped(self):
        lines = detect.parse_tsv(tsv([(1, 1, "PONG", 10, 300)]), masks=[(0, 290, 500, 40)])
        self.assertEqual(lines, [])

    def test_reply_below_the_anchor(self):
        anchor = re.compile(WARM_ANCHOR)
        before = [detect.Line("prompt nothing else.", 0, 10, 100, 20), detect.Line("PONG", 0, 40, 40, 50),
                  detect.Line("prompt nothing else. (2)", 0, 80, 100, 90)]
        self.assertIsNone(detect.reply_line(before, anchor, baseline=1))
        after = before + [detect.Line("PONG", 0, 120, 40, 130)]
        self.assertEqual(detect.reply_line(after, anchor, baseline=1).top, 120)

    def test_without_an_anchor_a_new_pong_line_counts(self):
        anchor = re.compile(WARM_ANCHOR)
        one = [detect.Line("PONG", 0, 40, 40, 50)]
        self.assertIsNone(detect.reply_line(one, anchor, baseline=1))
        self.assertIsNotNone(detect.reply_line(one + [detect.Line("PONG", 0, 90, 40, 100)], anchor, baseline=1))


class SearchTest(unittest.TestCase):
    def test_finds_the_first_hit_frame_with_few_reads(self):
        times = [1000 + 33 * i for i in range(300)]
        first = times[137]
        reads = []

        def read(t):
            reads.append(t)
            return "hit" if t >= first else None

        t, value, probes = detect.search(times, 1000, 90_000, 500, read)
        self.assertEqual((t, value), (first, "hit"))
        self.assertEqual(probes, len(reads))
        self.assertLess(probes, 20)

    def test_frames_before_the_enter_are_not_read(self):
        times = [1000 + 33 * i for i in range(100)]
        t, _, _ = detect.search(times, 2000, 90_000, 500, lambda t: "hit")
        self.assertEqual(t, 2023)

    def test_timeout(self):
        times = [1000 + 33 * i for i in range(100)]
        t, value, probes = detect.search(times, 1000, 1000, 500, lambda t: None)
        self.assertIsNone(t)
        self.assertGreater(probes, 1)


@unittest.skipUnless(HAVE_TOOLS, "needs tesseract, ffmpeg and ffprobe")
class DetectOnFramesTest(unittest.TestCase):
    """Builds a folder of <epoch ms>.png frames, 33 ms apart, from the committed fixture images."""

    def frames(self, sequence):
        folder = tempfile.mkdtemp(prefix="ttft-test-")
        self.addCleanup(shutil.rmtree, folder, ignore_errors=True)
        t = 10_000
        for name, count in sequence:
            for _ in range(count):
                shutil.copy(os.path.join(FIXTURES, name + ".png"), os.path.join(folder, f"{t}.png"))
                t += 33
        return folder

    def run_detect(self, folder, enter, anchor, **kwargs):
        work = tempfile.mkdtemp(prefix="ttft-work-")
        self.addCleanup(shutil.rmtree, work, ignore_errors=True)
        return detect.detect(detect.Frames(folder=folder, work=work), enter, anchor, **kwargs)

    def test_cold_reply_found_at_its_frame(self):
        folder = self.frames([("cold-before", 3), ("cold-sent", 25), ("cold-reply", 20)])
        # The Enter lands between frames 2 and 3; the reply is frame 28.
        result = self.run_detect(folder, 10_080, COLD_ANCHOR)
        self.assertTrue(result["hit"], result)
        self.assertEqual(result["frame_ms"], 10_000 + 28 * 33)
        self.assertEqual(result["ttft_ms"], 10_000 + 28 * 33 - 10_080)
        self.assertEqual(result["line"].strip(), "PONG")

    def test_the_prompt_in_the_box_is_not_the_reply(self):
        folder = self.frames([("cold-before", 30)])
        result = self.run_detect(folder, 10_000, COLD_ANCHOR)
        self.assertFalse(result["hit"])
        self.assertIn("no reply line", result["reason"])

    def test_warm_reply_under_the_second_prompt(self):
        folder = self.frames([("warm-sent", 17), ("warm-reply", 10)])
        result = self.run_detect(folder, 10_000, WARM_ANCHOR)
        self.assertTrue(result["hit"], result)
        self.assertEqual(result["baseline_pongs"], 1)
        self.assertEqual(result["frame_ms"], 10_000 + 17 * 33)

    def test_terminal_ui_bullet_before_the_reply(self):
        folder = self.frames([("cold-sent", 5), ("tui-reply", 5)])
        result = self.run_detect(folder, 10_000, COLD_ANCHOR)
        self.assertEqual(result.get("frame_ms"), 10_000 + 5 * 33, result)

    def test_masked_prompt_box(self):
        folder = self.frames([("masked-pong", 10)])
        # Without the mask the lone PONG in the box would be read as the reply (it is below the echo).
        self.assertTrue(self.run_detect(folder, 10_000, COLD_ANCHOR)["hit"])
        self.assertFalse(self.run_detect(folder, 10_000, COLD_ANCHOR, masks=[PROMPT_BOX])["hit"])

    def test_timeout(self):
        folder = self.frames([("cold-sent", 40)])
        result = self.run_detect(folder, 10_000, COLD_ANCHOR, timeout_s=1)
        self.assertFalse(result["hit"])
        self.assertIn("1.0 s", result["reason"])

    def test_evidence_image(self):
        folder = self.frames([("cold-sent", 4), ("cold-reply", 4)])
        out = os.path.join(tempfile.mkdtemp(prefix="ttft-ev-"), "ev.png")
        self.addCleanup(shutil.rmtree, os.path.dirname(out), ignore_errors=True)
        result = self.run_detect(folder, 10_000, COLD_ANCHOR, evidence=out, label="test cold 1")
        self.assertTrue(result["hit"])
        with open(out, "rb") as f:
            self.assertEqual(f.read(8), b"\x89PNG\r\n\x1a\n")
        self.assertLess(os.path.getsize(out), 100_000)


RESULTS = {
    "date": "2026-11-07",
    "results_file": "benchmarks/ttft-2026-11-07.json",
    "results_link": "benchmarks/ttft-2026-11-07.json",
    "machine": {"cpu": "Test CPU", "cores": 4, "ram_gb": 16, "os": "Ubuntu 24.04", "kernel": "6.1"},
    "ide": {"name": "IntelliJ IDEA Ultimate", "version": "2026.2.3", "build": "262.10968.63"},
    "screen": "1600x1000", "fps": 30, "cli": "2.1.319 (Claude Code)", "model_requested": "sonnet",
    "prompt": "P", "prompt_warm": "P (2)",
    "floor": {"name": "Floor: IDE terminal, `echo PONG`", "description": "the terminal echoing a word.",
              "runs": [{"run": i, "phase": "cold", "ttft_ms": 100 + 10 * i} for i in range(1, 6)]},
    "plugins": [
        {"name": "Tandem", "table_name": "Tandem", "id": 35641, "version": "0.4.1", "status": "measured",
         "runs": [{"run": i, "phase": "cold", "ttft_ms": 1000 * i, "model": "claude-sonnet-4-6"} for i in range(1, 6)]
         + [{"run": 1, "phase": "warm", "ttft_ms": None, "failure": "timeout", "failure_detail": "no reply in 90 s"}]
         + [{"run": i, "phase": "warm", "ttft_ms": 500, "model": "claude-sonnet-4-6"} for i in range(2, 6)]},
        {"name": "CC GUI", "table_name": "CC GUI", "id": 29342, "version": "0.6.4", "status": "not measured",
         "reason": "its webview took no keys", "screenshot": "benchmarks/evidence/cc-gui-not-measured.png", "runs": []},
    ],
}


class ReportTest(unittest.TestCase):
    def test_table(self):
        text = report.render(RESULTS)
        self.assertIn("| Floor: IDE terminal, `echo PONG` | IDE 2026.2.3 | none | **0.13 s** (0.11, 0.12, 0.13, 0.14, 0.15) | not measured |", text)
        self.assertIn("| Tandem (35641) | 0.4.1 | claude-sonnet-4-6 | **3.00 s** (1.00, 2.00, 3.00, 4.00, 5.00) "
                      "| **0.50 s** (timeout, 0.50, 0.50, 0.50, 0.50) |", text)
        self.assertIn("| CC GUI (29342) | 0.6.4 | | not measured | not measured |", text)
        self.assertIn("- **CC GUI** not measured: its webview took no keys ([screenshot](benchmarks/evidence/cc-gui-not-measured.png))", text)
        self.assertIn("- Tandem, warm run 1: no reply in 90 s", text)
        self.assertIn("Test CPU, 4 cores, 16 GB RAM", text)

    def test_write_replaces_the_block_and_the_ttft_cells_only(self):
        doc = ("# B\n<!-- ttft:begin -->\nold\n<!-- ttft:end -->\n"
               "| Plugin | Version | Time to first token | Idle memory: IDE / children | Streaming memory: IDE / children |\n"
               "|---|---|---|---|---|\n"
               "| Tandem | not measured yet | not measured yet | 1 GB | 2 GB |\n"
               "| CC GUI | not measured yet | not measured yet | not measured yet | not measured yet |\n")
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as f:
            f.write(doc)
        self.addCleanup(os.remove, f.name)
        report.write(f.name, RESULTS, report.render(RESULTS))
        with open(f.name) as f2:
            text = f2.read()
        self.assertNotIn("\nold\n", text)
        self.assertIn("| Tandem | 0.4.1 | 3.00 s (cold median, see above) | 1 GB | 2 GB |", text)
        self.assertIn("| CC GUI | 0.6.4 | not measured (see above) | not measured yet | not measured yet |", text)

    def test_main_prints_the_section(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(RESULTS, f)
        self.addCleanup(os.remove, f.name)
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(report.main([f.name]), 0)
        self.assertIn("| Tandem (35641) |", out.getvalue())


if __name__ == "__main__":
    unittest.main()

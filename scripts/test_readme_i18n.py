"""Tests that the translated READMEs follow README.md. Run from scripts/: python3 -m unittest"""
import glob
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
URL = re.compile(r"https?://[^\s)>\]]+")


def outline(path):
    """The `## ` sections in order, each with its `### ` headings, outside code fences."""
    sections, fenced = [], False
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("```"):
                fenced = not fenced
            elif not fenced and line.startswith("## "):
                sections.append((line[3:].strip(), []))
            elif not fenced and line.startswith("### ") and sections:
                sections[-1][1].append(line[4:].strip())
    return sections


class ReadmeTranslationsTest(unittest.TestCase):
    def setUp(self):
        self.english = os.path.join(ROOT, "README.md")
        self.translations = sorted(glob.glob(os.path.join(ROOT, "README.*.md")))

    def test_there_are_translations(self):
        self.assertEqual(["README.ja.md", "README.ko.md", "README.zh-CN.md"],
                         [os.path.basename(p) for p in self.translations])

    def test_same_sections_plus_one_subsection_under_requirements(self):
        english = outline(self.english)
        names = [name for name, _ in english]
        requirements = names.index("Requirements")
        for path in self.translations:
            with self.subTest(path=os.path.basename(path)):
                translated = outline(path)
                self.assertEqual(len(english), len(translated))
                for i, ((_, en_subs), (_, tr_subs)) in enumerate(zip(english, translated)):
                    extra = 1 if i == requirements else 0
                    self.assertEqual(len(en_subs) + extra, len(tr_subs), f"section {names[i]}")

    def test_every_english_url_appears_in_each_translation(self):
        with open(self.english, encoding="utf-8") as f:
            urls = set(URL.findall(f.read()))
        self.assertTrue(urls)
        for path in self.translations:
            with open(path, encoding="utf-8") as f:
                text = f.read()
            with self.subTest(path=os.path.basename(path)):
                self.assertEqual(set(), {u for u in urls if u not in text})


if __name__ == "__main__":
    unittest.main()

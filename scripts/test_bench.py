"""Tests for bench/marketplace_plugins.py. Run from scripts/: python3 -m unittest (no network)."""
import contextlib
import datetime
import io
import json
import os
import tempfile
import unittest
import zipfile

from bench import marketplace_plugins as mp


def jar_bytes(files: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as jar:
        for name, data in files.items():
            jar.writestr(name, data)
    return buffer.getvalue()


class InspectZipTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = temp.name

    def make_zip(self, files: dict[str, bytes], dirs: tuple[str, ...] = ()) -> str:
        path = os.path.join(self.root, "plugin.zip")
        with zipfile.ZipFile(path, "w") as z:
            for d in dirs:
                z.writestr(zipfile.ZipInfo(d), b"")
            for name, data in files.items():
                z.writestr(name, data)
        return path

    def test_plain_plugin_has_nothing_extra(self):
        found = mp.inspect_zip(self.make_zip({"p/lib/p.jar": jar_bytes({"a.class": b"x"}), "p/lib/q.jar": b"not a zip"}))
        self.assertEqual(found["jars"], 2)
        self.assertFalse(found["node"])
        self.assertFalse(found["webview"])
        self.assertEqual(found["native_libs"], 0)
        self.assertEqual(found["zip_bytes"], os.path.getsize(os.path.join(self.root, "plugin.zip")))

    def test_unpacked_size_sums_outer_files(self):
        found = mp.inspect_zip(self.make_zip({"a": b"x" * 100, "b": b"y" * 23}, dirs=("p/",)))
        self.assertEqual(found["unpacked_bytes"], 123)
        self.assertEqual(found["jars"], 0)

    def test_node_binary(self):
        self.assertTrue(mp.inspect_zip(self.make_zip({"p/bin/node": b"elf"}))["node"])
        self.assertTrue(mp.inspect_zip(self.make_zip({"p/bin/node.exe": b"mz"}))["node"])
        self.assertFalse(mp.inspect_zip(self.make_zip({"p/bin/node.txt": b"no"}))["node"])

    def test_node_modules_inside_a_nested_zip(self):
        bridge = jar_bytes({"node_modules/x/index.js": b"1"})
        self.assertTrue(mp.inspect_zip(self.make_zip({"p/bridge.zip": bridge}))["node"])

    def test_node_modules_empty_directory(self):
        self.assertTrue(mp.inspect_zip(self.make_zip({}, dirs=("p/node_modules/",)))["node"])

    def test_webview_needs_html_and_a_large_script(self):
        big = b"x" * (mp.WEBVIEW_JS_BYTES + 1)
        jar = jar_bytes({"webview/index.html": b"<html>", "webview/app.js": big})
        self.assertTrue(mp.inspect_zip(self.make_zip({"p/lib/ui.jar": jar}))["webview"])
        self.assertFalse(mp.inspect_zip(self.make_zip({"p/app.js": big}))["webview"])
        self.assertFalse(mp.inspect_zip(self.make_zip({"p/index.html": b"<html>", "p/app.js": b"small"}))["webview"])

    def test_native_libraries_counted_in_nested_jars(self):
        jar = jar_bytes({"native/libx.so": b"1", "native/x.dylib": b"2", "native/X.DLL": b"3"})
        found = mp.inspect_zip(self.make_zip({"p/lib/n.jar": jar, "p/lib/y.so": b"4"}))
        self.assertEqual(found["native_libs"], 4)


class TableTest(unittest.TestCase):
    def fake_fetch(self, zips: dict[int, bytes]):
        def fetch(url: str) -> bytes:
            for plugin_id, data in zips.items():
                if url == mp.API.format(id=plugin_id):
                    return json.dumps([{"version": f"1.{plugin_id}", "size": len(data), "cdate": "1793363338000",
                                        "file": f"{plugin_id}/1/p.zip"}]).encode()
                if url == mp.FILES.format(file=f"{plugin_id}/1/p.zip"):
                    return data
            raise AssertionError(url)
        return fetch

    def test_measure_reads_the_update(self):
        data = jar_bytes({"p/lib/a.jar": b"x"})
        row = mp.measure(1, "One", self.fake_fetch({1: data}))
        self.assertEqual(row["version"], "1.1")
        self.assertEqual(row["released"], "2026-10-30")
        self.assertEqual(row["zip_bytes"], len(data))
        self.assertEqual(row["jars"], 1)

    def test_size_mismatch_warns(self):
        data = jar_bytes({"a": b"x"})

        def fetch(url):
            if "api" in url:
                return json.dumps([{"version": "1", "size": 1, "cdate": "0", "file": "f"}]).encode()
            return data
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            mp.measure(1, "One", fetch)
        self.assertIn("API size 1", err.getvalue())

    def test_markdown_table(self):
        rows = [{"id": 7, "plugin": "Seven", "version": "0.1", "released": "2026-01-02", "zip_bytes": 3_965_915,
                 "unpacked_bytes": 4_209_524, "jars": 4, "node": True, "webview": False, "native_libs": 2}]
        table = mp.markdown(rows, "2026-11-03").splitlines()
        self.assertTrue(table[0].startswith("| Plugin |"))
        self.assertEqual(table[0].count("|"), table[1].count("|"))
        self.assertEqual(table[2], "| Seven (7) | 0.1 | 2026-01-02 | 4.0 MB | 4.2 MB | 4 | yes | no | 2 |")
        self.assertEqual(table[2].count("|"), table[0].count("|"))
        self.assertIn("Measured 2026-11-03", table[-1])

    def test_main_prints_every_plugin_as_table_and_json(self):
        zips = {pid: jar_bytes({"x": b"1"}) for pid in mp.PLUGINS}
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            mp.main([], self.fake_fetch(zips), datetime.date(2026, 11, 3))
        for pid, name in mp.PLUGINS.items():
            self.assertIn(f"| {name} ({pid}) |", out.getvalue())
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            mp.main(["--json"], self.fake_fetch(zips), datetime.date(2026, 11, 3))
        parsed = json.loads(out.getvalue())
        self.assertEqual(parsed["measured"], "2026-11-03")
        self.assertEqual([p["id"] for p in parsed["plugins"]], list(mp.PLUGINS))


if __name__ == "__main__":
    unittest.main()

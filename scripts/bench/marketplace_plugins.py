"""Measure the JetBrains Marketplace downloads of Tandem and the other plugins for Claude Code.

For each plugin id, fetch its latest update from the Marketplace API, download the ZIP into a temporary
directory and inspect it without extracting: ZIP size, unpacked size, .jar count, a bundled Node, a webview
bundle and native libraries. Prints a Markdown table (default) or JSON (--json).

Run from the repository root: python3 -I scripts/bench/marketplace_plugins.py [--json]
Standard library only. These are Marketplace downloads, not measurements on a machine.
"""
import argparse
import datetime
import io
import json
import os
import sys
import tempfile
import urllib.request
import zipfile

API = "https://plugins.jetbrains.com/api/plugins/{id}/updates?size=1"
FILES = "https://plugins.jetbrains.com/files/{file}"

# Marketplace id -> name shown in the table.
PLUGINS = {
    35641: "Tandem",
    27310: "Claude Code [Beta] (Anthropic)",
    29342: "CC GUI",
    30313: "Claude Code with GUI",
}

WEBVIEW_JS_BYTES = 1_000_000
NATIVE_SUFFIXES = (".so", ".dylib", ".dll")


def http_get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "tandem-bench"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def _entries(archive: zipfile.ZipFile, prefix: str = ""):
    """Yields (path, size) for every file, descending into nested .jar and .zip files."""
    for info in archive.infolist():
        if info.is_dir():
            yield prefix + info.filename, None
            continue
        yield prefix + info.filename, info.file_size
        if info.filename.lower().endswith((".jar", ".zip")):
            try:
                with zipfile.ZipFile(io.BytesIO(archive.read(info))) as inner:
                    yield from _entries(inner, prefix + info.filename + "!/")
            except zipfile.BadZipFile:
                pass


def inspect_zip(path: str) -> dict:
    """What a plugin ZIP carries. Unpacked size and the jar count cover the outer ZIP only."""
    with zipfile.ZipFile(path) as archive:
        outer = [i for i in archive.infolist() if not i.is_dir()]
        unpacked = sum(i.file_size for i in outer)
        jars = sum(1 for i in outer if i.filename.lower().endswith(".jar"))
        node, html, big_js, native = [], False, [], []
        for name, size in _entries(archive):
            parts = [p for p in name.replace("!/", "/").split("/") if p]
            if "node_modules" in parts[:-1] or (size is None and parts and parts[-1] == "node_modules"):
                node.append("node_modules")
            if size is None:
                continue
            base = parts[-1] if parts else name
            lower = base.lower()
            if base in ("node", "node.exe"):
                node.append(name)
            if lower.endswith((".html", ".htm")):
                html = True
            if lower.endswith(".js") and size > WEBVIEW_JS_BYTES:
                big_js.append(name)
            if lower.endswith(NATIVE_SUFFIXES):
                native.append(name)
    return {
        "zip_bytes": os.path.getsize(path),
        "unpacked_bytes": unpacked,
        "jars": jars,
        "node": bool(node),
        "webview": html and bool(big_js),
        "native_libs": len(native),
    }


def measure(plugin_id: int, name: str, fetch=http_get, workdir: str | None = None) -> dict:
    update = json.loads(fetch(API.format(id=plugin_id)))[0]
    cdate = datetime.datetime.fromtimestamp(int(update["cdate"]) / 1000, datetime.timezone.utc).date().isoformat()
    with tempfile.TemporaryDirectory(dir=workdir) as temp:
        zip_path = os.path.join(temp, "plugin.zip")
        with open(zip_path, "wb") as f:
            f.write(fetch(FILES.format(file=update["file"])))
        found = inspect_zip(zip_path)
    if update.get("size") is not None and int(update["size"]) != found["zip_bytes"]:
        print(f"warning: {plugin_id}: API size {update['size']} != downloaded {found['zip_bytes']}", file=sys.stderr)
    return {"id": plugin_id, "plugin": name, "version": update["version"], "released": cdate, **found}


def mb(n: int) -> str:
    return f"{n / 1_000_000:.1f} MB"


def yes_no(flag: bool) -> str:
    return "yes" if flag else "no"


def markdown(rows: list[dict], measured: str) -> str:
    lines = [
        "| Plugin | Version | Released | ZIP size | Unpacked | Jars | Node | Webview | Native libs |",
        "|---|---|---|---:|---:|---:|---|---|---:|",
    ]
    for r in rows:
        lines.append(
            f"| {r['plugin']} ({r['id']}) | {r['version']} | {r['released']} | {mb(r['zip_bytes'])} | "
            f"{mb(r['unpacked_bytes'])} | {r['jars']} | {yes_no(r['node'])} | {yes_no(r['webview'])} | {r['native_libs']} |"
        )
    lines.append("")
    lines.append(f"Measured {measured} from the JetBrains Marketplace downloads (latest update of each plugin).")
    return "\n".join(lines)


def main(argv: list[str] | None = None, fetch=http_get, today=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--json", action="store_true", help="print JSON instead of a Markdown table")
    args = parser.parse_args(argv)
    measured = (today or datetime.date.today()).isoformat()
    rows = [measure(pid, name, fetch) for pid, name in PLUGINS.items()]
    if args.json:
        print(json.dumps({"measured": measured, "plugins": rows}, indent=2))
    else:
        print(markdown(rows, measured))
    return 0


if __name__ == "__main__":
    sys.exit(main())

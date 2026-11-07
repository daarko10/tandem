"""Render a time-to-first-token results file (docs/benchmarks/ttft-<date>.json) as Markdown, into docs/benchmarks.md.

    python3 -I scripts/bench/ttft/report.py docs/benchmarks/ttft-2026-11-07.json            (prints the section)
    python3 -I scripts/bench/ttft/report.py docs/benchmarks/ttft-2026-11-07.json --write docs/benchmarks.md

--write replaces what lies between `<!-- ttft:begin -->` and `<!-- ttft:end -->` in the file, and fills the Version and
Time to first token cells of the plugins' rows in its memory Results table (the memory cells are left as they are).
Standard library only.
"""
import argparse
import json
import re
import statistics
import sys

BEGIN, END = "<!-- ttft:begin -->", "<!-- ttft:end -->"


def seconds(ms) -> str:
    return f"{ms / 1000:.2f}"


def cell(runs: list, phase: str) -> str:
    """'**median s** (each run's s)' over the runs of a phase; failed runs show as 'timeout' (or their short reason)."""
    mine = sorted((r for r in runs if r.get("phase") == phase), key=lambda r: r.get("run", 0))
    if not mine:
        return "not measured"
    values = [r["ttft_ms"] for r in mine if r.get("ttft_ms") is not None]
    each = ", ".join(seconds(r["ttft_ms"]) if r.get("ttft_ms") is not None else (r.get("failure") or "failed") for r in mine)
    if not values:
        return f"no reply ({each})"
    return f"**{seconds(statistics.median(values))} s** ({each})"


def median_s(runs: list, phase: str):
    values = [r["ttft_ms"] for r in runs if r.get("phase") == phase and r.get("ttft_ms") is not None]
    return f"{seconds(statistics.median(values))} s" if values else None


def models(entry: dict) -> str:
    seen = []
    for r in entry.get("runs", []):
        m = r.get("model")
        if m and m not in seen:
            seen.append(m)
    return ", ".join(seen) or entry.get("model") or "not reported"


def render(data: dict) -> str:
    m = data["machine"]
    ide = data["ide"]
    out = [
        f"Measured {data['date']} by `scripts/bench/ttft/run.sh`, with every run in "
        f"[`{data.get('results_file', 'the results file')}`]({data.get('results_link', '')}) and each detected frame in "
        "[`benchmarks/evidence/`](benchmarks/evidence/).",
        "",
        f"- Machine: {m['cpu']}, {m['cores']} cores, {m['ram_gb']} GB RAM, {m['os']}, kernel {m['kernel']}"
        + (f" ({m['note']})" if m.get("note") else "") + ".",
        f"- IDE: {ide['name']} {ide['version']} (build {ide['build']}), under Xvfb at {data['screen']}, recorded at {data['fps']} fps.",
        f"- CLI: `claude` {data['cli']}. Model asked for: {data['model_requested']}.",
        f"- Prompt: `{data['prompt']}`; the warm run's: `{data['prompt_warm']}`.",
        "",
        "Time to first token is the wall-clock time from the Enter that sends the prompt to the first recorded frame showing the reply,"
        " in seconds: the median, then each run in order. A cold run is the first prompt after the IDE starts; a warm run is the"
        " second prompt in the same session, sent right after the first reply.",
        "",
        "| Plugin | Version | Model | Cold | Warm |",
        "|---|---|---|---|---|",
    ]
    floor = data.get("floor")
    if floor:
        out.append(f"| {floor['name']} | IDE {ide['version']} | none | {cell(floor['runs'], 'cold')} | {cell(floor['runs'], 'warm')} |")
    notes = []
    for p in data["plugins"]:
        name = f"{p['name']} ({p['id']})" if p.get("id") else p["name"]
        if p.get("status") == "not measured":
            out.append(f"| {name} | {p.get('version', '')} | | not measured | not measured |")
            notes.append(f"- **{p['name']}** not measured: {p.get('reason', 'no reason recorded')}"
                         + (f" ([screenshot]({p['screenshot']}))" if p.get("screenshot") else ""))
        else:
            out.append(f"| {name} | {p.get('version', '')} | {models(p)} | {cell(p['runs'], 'cold')} | {cell(p['runs'], 'warm')} |")
            for r in p["runs"]:
                if r.get("ttft_ms") is None and r.get("failure"):
                    notes.append(f"- {p['name']}, {r['phase']} run {r['run']}: {r.get('failure_detail') or r['failure']}")
    if floor:
        out += ["", f"The first row is the method's floor: {floor['description']}"]
    if notes:
        out += [""] + notes
    return "\n".join(out) + "\n"


def write(path: str, data: dict, section: str) -> None:
    with open(path) as f:
        text = f.read()
    if BEGIN not in text or END not in text:
        raise SystemExit(f"report.py: no {BEGIN} ... {END} block in {path}")
    head, rest = text.split(BEGIN, 1)
    _, tail = rest.split(END, 1)
    text = head + BEGIN + "\n" + section + END + tail
    lines = text.split("\n")
    for p in data["plugins"]:
        for i, line in enumerate(lines):
            cols = line.split("|")
            # | Plugin | Version | Time to first token | Idle memory | Streaming memory |
            if len(cols) == 7 and cols[1].strip() == p["table_name"]:
                cold = median_s(p.get("runs", []), "cold")
                cols[2] = f" {p.get('version') or 'not measured yet'} "
                cols[3] = f" {cold + ' (cold median, see above)' if cold else 'not measured (see above)'} "
                lines[i] = "|".join(cols)
    with open(path, "w") as f:
        f.write("\n".join(lines))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("results")
    parser.add_argument("--write", metavar="MARKDOWN")
    args = parser.parse_args(argv)
    with open(args.results) as f:
        data = json.load(f)
    section = render(data)
    if args.write:
        write(args.write, data, section)
    else:
        sys.stdout.write(section)
    return 0


if __name__ == "__main__":
    sys.exit(main())

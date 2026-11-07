"""Turn the benchmark's marks and screen recordings into results, for scripts/bench/ttft/run.sh.

    collect.py detect --marks M --video V --plugin P --run N --results R --evidence-dir D [--config-dir C]
        For every Enter that the IDE test marked for plugin P's run N, finds the first frame showing the reply (detect.py), keeps
        that frame as evidence (D/<plugin>-<phase>-<run>.png) and appends one line per Enter to R (JSON lines). A run marked as a
        failure gets its screenshot as evidence (D/<plugin>-<phase>-<run>-failed.png). The model is the one the CLI's transcript for
        the run's project folder names (C, default $CLAUDE_CONFIG_DIR or ~/.claude); only its `model` fields are read.
    collect.py finalize --results R --out J --ide-home H --claude CLAUDE --date YYYY-MM-DD
        Writes the results file J: every run, the machine, the IDE build, the CLI version.

Standard library only (plus detect.py's binaries).
"""
import argparse
import datetime
import glob
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import detect  # noqa: E402

PLUGINS = {
    "floor": {"name": "Floor: IDE terminal, `echo PONG`", "id": None, "table_name": None},
    "tandem": {"name": "Tandem", "id": 35641, "table_name": "Tandem"},
    "anthropic": {"name": "Claude Code [Beta] (Anthropic)", "id": 27310, "table_name": "Claude Code [Beta] (Anthropic)"},
    "air": {"name": "Air (JetBrains)", "id": 33314, "table_name": "Air (JetBrains)"},
    "ccgui": {"name": "CC GUI", "id": 29342, "table_name": "CC GUI"},
    "ccwithgui": {"name": "Claude Code with GUI", "id": 30313, "table_name": "Claude Code with GUI"},
}
ORDER = list(PLUGINS)
FLOOR_DESCRIPTION = ("the IDE's own terminal running `echo PONG`, measured the same way: the time from the Enter to the first frame "
                     "showing the output, which is what the recording, the Driver's key events and the detection add to any number above.")


def read_lines(path: str) -> list:
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


def transcript_model(config_dir: str, project: str):
    """The model of the first assistant message in the CLI's transcripts for project (docs/protocol.md 11)."""
    if not project:
        return None
    key = re.sub(r"[^A-Za-z0-9]", "-", project)
    for path in sorted(glob.glob(os.path.join(config_dir, "projects", key, "*.jsonl")), key=os.path.getmtime):
        with open(path, errors="replace") as f:
            for line in f:
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                message = entry.get("message") if isinstance(entry, dict) else None
                if entry.get("type") == "assistant" and isinstance(message, dict) and message.get("model") not in (None, "<synthetic>"):
                    return message["model"]
    return None


def shrink(src: str, dst: str) -> None:
    detect.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-filter_complex",
                "scale='min(800,iw)':-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse=dither=none", dst])


def cmd_detect(args) -> int:
    marks = [m for m in read_lines(args.marks) if m.get("plugin") == args.plugin and m.get("run") == args.run]
    config_dir = args.config_dir or os.environ.get("CLAUDE_CONFIG_DIR") or os.path.expanduser("~/.claude")
    os.makedirs(args.evidence_dir, exist_ok=True)
    out = []
    for phase in ("cold", "warm"):
        mine = [m for m in marks if m.get("phase") == phase]
        enter = next((m for m in mine if "enter_ms" in m), None)
        failure = next((m for m in mine if "failure" in m), None)
        base = {k: (enter or failure or {}).get(k) for k in ("plugin", "name", "version", "run", "phase")}
        if not mine:
            out.append({**base, "plugin": args.plugin, "run": args.run, "phase": phase, "ttft_ms": None,
                        "failure": "no marks", "failure_detail": "the IDE test wrote no marks for this run"})
            continue
        record = {**base, "enter_ms": enter.get("enter_ms") if enter else None, "ttft_ms": None,
                  "model": transcript_model(config_dir, (enter or failure).get("project", ""))}
        if failure and not enter:
            record.update(failure=failure["failure"], failure_detail=failure["failure"])
        elif enter:
            region = detect.box(enter["region"]) if enter.get("region") else None
            masks = [detect.box(m) for m in enter.get("masks", [])]
            evidence = os.path.join(args.evidence_dir, f"{args.plugin}-{phase}-{args.run}.png")
            work = tempfile.mkdtemp(prefix="ttft-")
            try:
                frames = detect.Frames(video=args.video, region=region, work=work)
                label = f"{base.get('name') or args.plugin} {base.get('version') or ''} {phase} {args.run}"
                result = detect.detect(frames, enter["enter_ms"], enter["anchor"], args.timeout, 500, masks, evidence, label)
            finally:
                shutil.rmtree(work, ignore_errors=True)
            record["detection"] = {k: result.get(k) for k in ("anchor", "region", "masks", "baseline_pongs", "frames_read", "line", "line_box")}
            if result["hit"]:
                record.update(frame_ms=result["frame_ms"], ttft_ms=result["ttft_ms"], evidence=os.path.relpath(evidence, args.relative_to))
                if failure:
                    record["note"] = f"the IDE test gave up ({failure['failure']}) but the recording shows the reply"
            else:
                record.update(failure="timeout" if not failure else failure["failure"], failure_detail=result["reason"])
        if record.get("ttft_ms") is None:
            shot = (failure or {}).get("screenshot")
            if shot and os.path.exists(shot):
                dst = os.path.join(args.evidence_dir, f"{args.plugin}-{phase}-{args.run}-failed.png")
                shrink(shot, dst)
                record["screenshot"] = os.path.relpath(dst, args.relative_to)
        out.append(record)
    with open(args.results, "a") as f:
        for record in out:
            f.write(json.dumps(record) + "\n")
    for record in out:
        print(f"collect.py: {record.get('plugin')} {record.get('phase')} {record.get('run')}: "
              + (f"{record['ttft_ms']} ms" if record.get("ttft_ms") is not None else f"no reply ({record.get('failure_detail')})"))
    return 0


def machine() -> dict:
    cpu = ""
    try:
        cpu = next((l.split(":", 1)[1].strip() for l in subprocess.run(["lscpu"], capture_output=True, text=True).stdout.splitlines()
                    if l.startswith("Model name")), "")
    except OSError:
        pass
    ram = 0
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemTotal"):
                ram = round(int(line.split()[1]) / 1024 / 1024)
    os_name = platform.system()
    if os.path.exists("/etc/os-release"):
        with open("/etc/os-release") as f:
            os_name = next((l.split("=", 1)[1].strip().strip('"') for l in f if l.startswith("PRETTY_NAME=")), os_name)
    note = "a GitHub Actions runner" if os.environ.get("GITHUB_ACTIONS") == "true" else "a cloud container, not a desktop"
    return {"cpu": cpu, "cores": os.cpu_count(), "ram_gb": ram, "os": os_name, "kernel": platform.release(), "note": note}


def cmd_finalize(args) -> int:
    runs = read_lines(args.results)
    with open(os.path.join(args.ide_home, "product-info.json")) as f:
        info = json.load(f)
    cli = subprocess.run([args.claude, "--version"], capture_output=True, text=True).stdout.strip()
    data = {
        "date": args.date,
        "results_file": f"benchmarks/{os.path.basename(args.out)}",
        "results_link": f"benchmarks/{os.path.basename(args.out)}",
        "machine": machine(),
        "ide": {"name": info.get("name", "IntelliJ IDEA"), "version": info.get("version", ""), "build": info.get("buildNumber", "")},
        "screen": args.screen, "fps": args.fps, "cli": cli, "model_requested": "sonnet (ANTHROPIC_MODEL=sonnet in the IDE's environment)",
        "prompt": "Reply with exactly the single word PONG and nothing else.",
        "prompt_warm": "Reply with exactly the single word PONG and nothing else. (2)",
        "method": "scripts/bench/ttft/run.sh; detection by scripts/bench/ttft/detect.py",
        "plugins": [],
    }
    notes = dict(n.split("=", 1) for n in args.note)
    for key in ORDER:
        mine = sorted((r for r in runs if r.get("plugin") == key), key=lambda r: (r.get("run", 0), r.get("phase", "")))
        if not mine and key not in notes:
            continue
        meta = PLUGINS[key]
        version = next((r["version"] for r in mine if r.get("version")), "")
        entry = {"key": key, "name": meta["name"], "id": meta["id"], "table_name": meta["table_name"], "version": version, "runs": mine}
        if key == "floor":
            data["floor"] = {**entry, "description": FLOOR_DESCRIPTION}
            continue
        if not any(r.get("ttft_ms") is not None for r in mine):
            entry["status"] = "not measured"
            entry["reason"] = notes.get(key) or "; ".join(sorted({r.get("failure_detail") or r.get("failure") or "?" for r in mine}))
            entry["screenshot"] = next((r["screenshot"] for r in mine if r.get("screenshot")), None)
        else:
            entry["status"] = "measured"
        data["plugins"].append(entry)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(data, f, indent=1)
        f.write("\n")
    print(f"collect.py: wrote {args.out}")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    d = sub.add_parser("detect")
    d.add_argument("--marks", required=True)
    d.add_argument("--video", required=True)
    d.add_argument("--plugin", required=True)
    d.add_argument("--run", type=int, required=True)
    d.add_argument("--results", required=True)
    d.add_argument("--evidence-dir", required=True)
    d.add_argument("--relative-to", default="docs")
    d.add_argument("--config-dir")
    d.add_argument("--timeout", type=float, default=90)
    f = sub.add_parser("finalize")
    f.add_argument("--results", required=True)
    f.add_argument("--out", required=True)
    f.add_argument("--ide-home", required=True)
    f.add_argument("--claude", required=True)
    f.add_argument("--date", default=datetime.date.today().isoformat())
    f.add_argument("--screen", default="1600x1000")
    f.add_argument("--fps", type=int, default=30)
    f.add_argument("--note", action="append", default=[], help="plugin=reason, for a plugin that is not measured")
    args = parser.parse_args(argv)
    return cmd_detect(args) if args.command == "detect" else cmd_finalize(args)


if __name__ == "__main__":
    sys.exit(main())

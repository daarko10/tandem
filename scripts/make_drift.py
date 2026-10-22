#!/usr/bin/env python3
"""Make a rehearsal recording for the drift loop: a CLI contract recording with one drift the decoder or reader rejects.

Usage: make_drift.py <mode> <out-dir> [--from <recording-dir>]

Writes the layout of the nightly's contract-recording artifact: <out-dir>/contract-hello.ndjson,
<out-dir>/transcripts/contract-hello.jsonl and <out-dir>/transcripts/session-id.jsonl, plus the marker
<out-dir>/rehearsal (one line: the mode), which the drift fixer reads as rehearsal=true.

The modes, each a drift the current decoder or reader rejects the way a real rename would:

- init-model-key: the system/init line's "model" key becomes "model_id". Ndjson requires "model" on init, so the line
  decodes to Unknown and ContractTest's decode test fails with "no system/init".
- transcript-content-key: every transcript "user" line's message.content becomes message.blocks, so the reader yields
  no user entry and ContractTest's reader test fails with "no user entry".

Lines are found by content (type, subtype), never by number: a live recording has a varying count of lines before
system/init. A mutated line is re-serialized compactly; every other line is copied byte for byte.

Without --from, the sources are the committed fixture history-reply (src/test/resources/fixtures/history-reply.ndjson
and its transcript twin, which stands in for both transcripts). With --from, they are that recording's three files,
each mutated from its own lines; <recording-dir> may be <out-dir> itself (the nightly's rehearsal mutates its own
build/contract in place): all three are read before any file is written. Never writes under the fixtures directory.
"""
import json
import os
import sys
from typing import NoReturn

FIXTURES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src", "test", "resources",
                        "fixtures")
SOURCE = "history-reply"
MODES = ("init-model-key", "transcript-content-key")
MARKER = "rehearsal"
STREAM = "contract-hello.ndjson"
TRANSCRIPTS = ("transcripts/contract-hello.jsonl", "transcripts/session-id.jsonl")


def fail(message: str) -> NoReturn:
    sys.exit(f"make_drift: {message}")


def rename_key(obj: dict, old: str, new: str) -> dict:
    """`obj` with the key `old` renamed to `new`, in the same key order."""
    return {(new if key == old else key): value for key, value in obj.items()}


def parsed(line: str) -> dict | None:
    try:
        obj = json.loads(line)
    except ValueError:
        return None
    return obj if isinstance(obj, dict) else None


def serialized(obj: dict, line: str) -> str:
    # The redactor's format; the line keeps its own ending.
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + ("\n" if line.endswith("\n") else "")


def mutate_stream(lines: list[str], mode: str) -> list[str]:
    """For init-model-key, every system/init line with its "model" key renamed to "model_id"; else `lines`."""
    if mode != "init-model-key":
        return list(lines)
    out: list[str] = []
    for line in lines:
        obj = parsed(line)
        if obj is not None and obj.get("type") == "system" and obj.get("subtype") == "init" and "model" in obj:
            out.append(serialized(rename_key(obj, "model", "model_id"), line))
        else:
            out.append(line)
    return out


def mutate_transcript(lines: list[str], mode: str) -> list[str]:
    """For transcript-content-key, every user line with message.content renamed to message.blocks; else `lines`."""
    if mode != "transcript-content-key":
        return list(lines)
    out: list[str] = []
    for line in lines:
        obj = parsed(line)
        message = obj.get("message") if obj is not None else None
        if obj is not None and obj.get("type") == "user" and isinstance(message, dict) and "content" in message:
            out.append(serialized({**obj, "message": rename_key(message, "content", "blocks")}, line))
        else:
            out.append(line)
    return out


def read_lines(path: str) -> list[str]:
    with open(path, encoding="utf-8", newline="") as f:
        return f.readlines()


def main(mode: str, out_dir: str, source: str | None = None) -> None:
    if mode not in MODES:
        fail(f"unknown mode {mode!r}; one of {', '.join(MODES)}")
    fixtures = os.path.realpath(FIXTURES)
    target = os.path.realpath(out_dir)
    if target == fixtures or target.startswith(fixtures + os.sep):
        fail(f"refusing to write under the fixtures directory: {out_dir}")

    if source is None:
        stream = read_lines(os.path.join(FIXTURES, f"{SOURCE}.ndjson"))
        twin = read_lines(os.path.join(FIXTURES, "transcripts", f"{SOURCE}.jsonl"))
        transcripts = [twin, twin]
    else:
        for rel in (STREAM, *TRANSCRIPTS):
            if not os.path.isfile(os.path.join(source, rel)):
                fail(f"no {rel} in {source}")
        stream = read_lines(os.path.join(source, STREAM))
        transcripts = [read_lines(os.path.join(source, rel)) for rel in TRANSCRIPTS]

    outputs = [(STREAM, mutate_stream(stream, mode))]
    outputs += [(rel, mutate_transcript(lines, mode)) for rel, lines in zip(TRANSCRIPTS, transcripts)]
    outputs.append((MARKER, [mode + "\n"]))
    for rel, lines in outputs:
        path = os.path.join(out_dir, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.writelines(lines)
        print(f"wrote {path}", file=sys.stderr)


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) == 2:
        main(args[0], args[1])
    elif len(args) == 4 and args[2] == "--from":
        main(args[0], args[1], source=args[3])
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(2)

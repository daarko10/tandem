#!/usr/bin/env python3
"""Copy the CLI's own on-disk transcript of a recorded run, so it can be redacted into a test fixture.

Usage: copy_transcript.py <work-dir> <raw-stream.ndjson> <out-dir> <name>

The stream's system/init lines name the session. The transcript of the last session id they show is copied to
<out-dir>/<name>.jsonl; when an earlier, different id shows up too (the fork scenario), that session's transcript is
copied to <out-dir>/<name>-origin.jsonl. Transcripts are read from
${CLAUDE_CONFIG_DIR:-~/.claude}/projects/<key>/<session id>.jsonl, where <key> is computed from <work-dir> (the
directory `claude` ran in) as the plugin's TranscriptFolder.key does.

Each file must exist, be at most 2 MB, and every line with a sessionId field must name the expected session;
otherwise nothing is copied and the exit code is non-zero. The copies are raw: redact them with redact_fixture.py,
never commit them as they are.
"""
import json
import os
import re
import shutil
import sys

MAX_BYTES = 2 * 1024 * 1024
MAX_KEY_LENGTH = 200


def fail(message: str) -> None:
    sys.exit(f"copy_transcript: {message}")


def folder_key(work_dir: str) -> str:
    # TranscriptFolder.key, for the short ASCII temp dirs the recording scripts use. The CLI replaces each UTF-16 unit
    # (JavaScript regex), Python each code point, so a character outside the Basic Multilingual Plane would give one
    # '-' here and two in the CLI. Keys over 200 characters also get a hash suffix from the CLI, not reproduced here.
    # The CLI keys on its physical working directory, hence realpath (e.g. /tmp is /private/tmp on macOS).
    return re.sub(r"[^A-Za-z0-9]", "-", os.path.realpath(work_dir))[:MAX_KEY_LENGTH]


def session_ids(stream: str) -> list[str]:
    """The distinct session ids of the stream's system/init lines, in order of appearance."""
    ids: list[str] = []
    with open(stream, encoding="utf-8") as f:
        for line in f:
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(msg, dict) and msg.get("type") == "system" and msg.get("subtype") == "init":
                session_id = msg.get("session_id")
                if isinstance(session_id, str) and session_id and session_id not in ids:
                    ids.append(session_id)
    return ids


def check(path: str, session_id: str) -> None:
    """Fails unless `path` is a transcript of at most MAX_BYTES whose sessionId fields all name `session_id`."""
    if not os.path.isfile(path):
        fail(f"no transcript at {path}")
    size = os.path.getsize(path)
    if size > MAX_BYTES:
        fail(f"{path} has {size} bytes, more than {MAX_BYTES}")
    with open(path, encoding="utf-8") as f:
        for number, line in enumerate(f, 1):
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(msg, dict) and "sessionId" in msg and msg["sessionId"] != session_id:
                fail(f"{path} line {number} belongs to another session")


def main(work_dir: str, stream: str, out_dir: str, name: str) -> None:
    ids = session_ids(stream)
    if not ids:
        fail(f"no system/init line with a session_id in {stream}")
    if len(ids) > 2:
        fail(f"{stream} shows {len(ids)} sessions; expected one, or two for a fork")
    config_dir = os.environ.get("CLAUDE_CONFIG_DIR") or os.path.expanduser("~/.claude")
    folder = os.path.join(config_dir, "projects", folder_key(work_dir))
    copies = [(ids[-1], f"{name}.jsonl")]
    if len(ids) == 2:
        copies.append((ids[0], f"{name}-origin.jsonl"))
    for session_id, _ in copies:
        check(os.path.join(folder, f"{session_id}.jsonl"), session_id)
    os.makedirs(out_dir, exist_ok=True)
    for session_id, file_name in copies:
        shutil.copyfile(os.path.join(folder, f"{session_id}.jsonl"), os.path.join(out_dir, file_name))
        print(f"copied the transcript of {session_id} to {os.path.join(out_dir, file_name)}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])

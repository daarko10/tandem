#!/usr/bin/env python3
"""Fake `claude` for platform and IDE tests: replays a recorded fixture in step with the host.

Ignores its arguments. Environment:
  TANDEM_FAKE_FIXTURE  fixture name in ./fixtures (default: plain-reply)
  TANDEM_FAKE_LOG      optional file; every frame the host writes is appended to it, one per line

The fixture is replayed line by line, waiting for the host where the real CLI would:
  - before each system/init (a turn starts): wait for a `user` message;
  - before each control_response (the CLI answering the host): wait for the host's next control_request
    and answer with that request's id;
  - after each control_request from the CLI (e.g. can_use_tool): wait for the host's control_response.
After the fixture ends it reads stdin until the host closes it, as the real CLI does.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
fixture = os.environ.get("TANDEM_FAKE_FIXTURE", "plain-reply")
log_path = os.environ.get("TANDEM_FAKE_LOG")


def read_host():
    """Next JSON frame from the host, or None at end of input."""
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if log_path:
            with open(log_path, "a", encoding="utf-8") as log:
                log.write(line + "\n")
        try:
            return json.loads(line)
        except json.JSONDecodeError:
            continue
    return None


def wait_for(predicate):
    while True:
        frame = read_host()
        if frame is None:
            sys.exit(0)  # the host closed stdin: end like the real CLI
        if predicate(frame):
            return frame


def emit(message):
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


with open(os.path.join(HERE, "fixtures", fixture + ".ndjson"), encoding="utf-8") as f:
    lines = [json.loads(l) for l in f if l.strip()]

for message in lines:
    kind = message.get("type")
    if kind == "system" and message.get("subtype") == "init":
        wait_for(lambda m: m.get("type") == "user")
    if kind == "control_response":
        request = wait_for(lambda m: m.get("type") == "control_request")
        message["response"]["request_id"] = request.get("request_id")
    emit(message)
    if kind == "control_request":
        wait_for(lambda m: m.get("type") == "control_response"
                 and m.get("response", {}).get("request_id") == message.get("request_id"))

wait_for(lambda m: False)

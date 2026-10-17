#!/usr/bin/env python3
"""Fake `claude` for platform and IDE tests: replays a recorded fixture in step with the host.

Reads --session-id <id>, --resume <id> and --fork-session from its arguments and ignores the rest. Environment:
  TANDEM_FAKE_FIXTURES    comma-separated fixture names in ./fixtures. Process number N (from 0) started against the same
                          TANDEM_FAKE_LOG replays name N; the last name is reused once the list runs out
  TANDEM_FAKE_FIXTURE     a single fixture name, used when TANDEM_FAKE_FIXTURES is unset (default: plain-reply)
  TANDEM_FAKE_LOG         optional file. The first line this process writes to it is
                            {"type":"__spawn__","argv":[...],"env":{...}}
                          how it was started: its arguments, and only the CLAUDE* variables and NODE_OPTIONS of its
                          environment, never the whole environment. The value of a variable whose name contains TOKEN,
                          KEY, SECRET, PASSWORD, CREDENTIAL or AUTH (in any case) is logged as <redacted>.
                          Every frame the host writes follows, one per line
  TANDEM_FAKE_SESSION_ID  the session id to use, whatever the arguments say
  TANDEM_FAKE_DELAY_MS    sleep this long before emitting each control_request
  TANDEM_FAKE_GATE_FILE   before emitting each control_request (after the delay, if both are set), wait until this file
                          exists, checking every 100 ms for up to 60 s, then emit it anyway: a test holds a request
                          back until it is ready for it, in place of guessing a delay

Every `<session_id-N>` placeholder in the fixture becomes the session id: the value after --session-id or --resume;
with --fork-session the value after --session-id (the fork's own id) or else a fresh UUID; a fresh UUID when neither
flag is given; or TANDEM_FAKE_SESSION_ID when that is set.
An unknown fixture name ends the process with exit code 2 and `no fixture <name>` on stderr.

The fixture is replayed line by line, waiting for the host where the real CLI would:
  - before each system/init (a turn starts): wait for a `user` message;
  - before each control_response (the CLI answering the host): wait for the host's next control_request
    and answer with that request's id;
  - after each control_request from the CLI (e.g. can_use_tool): wait for the host's control_response.
After the fixture ends it reads stdin until the host closes it, as the real CLI does.
"""
import fcntl
import json
import os
import re
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
log_path = os.environ.get("TANDEM_FAKE_LOG")
delay_ms = int(os.environ.get("TANDEM_FAKE_DELAY_MS") or 0)
gate_file = os.environ.get("TANDEM_FAKE_GATE_FILE")
SPAWN_PREFIX = '{"type":"__spawn__"'
SESSION_PLACEHOLDER = re.compile(r"<session_id-\d+>")
SECRET_NAME_PARTS = ("TOKEN", "KEY", "SECRET", "PASSWORD", "CREDENTIAL", "AUTH")


def option(name):
    """The argument that follows `name`, or None."""
    return args[args.index(name) + 1] if name in args[:-1] else None


def session_id():
    if os.environ.get("TANDEM_FAKE_SESSION_ID"):
        return os.environ["TANDEM_FAKE_SESSION_ID"]
    if "--fork-session" in args:
        return option("--session-id") or str(uuid.uuid4())
    return option("--session-id") or option("--resume") or str(uuid.uuid4())


def loggable(name, value):
    """The value to write to the log: the value of a secret never is."""
    return "<redacted>" if any(part in name.upper() for part in SECRET_NAME_PARTS) else value


def record_spawn():
    """Appends this process's `__spawn__` line to the log; returns how many such lines were there before it."""
    if not log_path:
        return 0
    spawn = {
        "type": "__spawn__",
        "argv": args,
        "env": {name: loggable(name, value) for name, value in os.environ.items() if name.startswith("CLAUDE") or name == "NODE_OPTIONS"},
    }
    with open(log_path, "a+", encoding="utf-8") as log:
        fcntl.flock(log, fcntl.LOCK_EX)  # processes that start together must not count the same lines
        log.seek(0)
        earlier = sum(1 for line in log if line.startswith(SPAWN_PREFIX))
        log.write(json.dumps(spawn, separators=(",", ":")) + "\n")
    return earlier


def fixture_name(spawn_index):
    names = (os.environ.get("TANDEM_FAKE_FIXTURES") or os.environ.get("TANDEM_FAKE_FIXTURE") or "plain-reply").split(",")
    names = [name.strip() for name in names if name.strip()] or ["plain-reply"]
    return names[min(spawn_index, len(names) - 1)]


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


def wait_for_gate():
    """Waits until TANDEM_FAKE_GATE_FILE exists, for up to 60 s."""
    deadline = time.monotonic() + 60
    while not os.path.exists(gate_file):
        if time.monotonic() > deadline:
            sys.stderr.write("gate file never appeared; going on\n")
            return
        time.sleep(0.1)


def emit(message):
    line = SESSION_PLACEHOLDER.sub(lambda _: escaped_session_id, json.dumps(message, separators=(",", ":")))
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


fixture = fixture_name(record_spawn())
fixture_path = os.path.join(HERE, "fixtures", fixture + ".ndjson")
if not os.path.isfile(fixture_path):
    sys.stderr.write("no fixture " + fixture + "\n")
    sys.exit(2)
with open(fixture_path, encoding="utf-8") as f:
    lines = [json.loads(l) for l in f if l.strip()]

escaped_session_id = json.dumps(session_id())[1:-1]  # as it appears inside a JSON string

for message in lines:
    kind = message.get("type")
    if kind == "system" and message.get("subtype") == "init":
        wait_for(lambda m: m.get("type") == "user")
    if kind == "control_response":
        request = wait_for(lambda m: m.get("type") == "control_request")
        message["response"]["request_id"] = request.get("request_id")
    if kind == "control_request" and delay_ms:
        time.sleep(delay_ms / 1000)
    if kind == "control_request" and gate_file:
        wait_for_gate()
    emit(message)
    if kind == "control_request":
        wait_for(lambda m: m.get("type") == "control_response"
                 and m.get("response", {}).get("request_id") == message.get("request_id"))

wait_for(lambda m: False)

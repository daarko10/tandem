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
  TANDEM_FAKE_DELAY_MS    sleep this long before emitting each control_request, and before the answers of rules (h) and (i)
  TANDEM_FAKE_GATE_FILE   before emitting each control_request (after the delay, if both are set), wait until this file
                          exists, checking every 100 ms for up to 60 s, then emit it anyway: a test holds a request
                          back until it is ready for it, in place of guessing a delay
  TANDEM_FAKE_LINE_DELAY_MS  sleep this long before emitting each stream_event line (unset or 0: no sleep), so that a
                          reply streams for as long as a test needs to act while it streams
  TANDEM_FAKE_AUTH_DONE_MS  in an MCP fixture (below): each mcp_status served at least this many ms after the first
                          mcp_authenticate of a needs-auth server first turns that server `connected` (its own config kept,
                          the seeded connected entry's serverInfo and tools, no error). Unset leaves it needs-auth. The flip
                          without an mcp_reconnect is a guess at the unrecorded callback completion (probes open question 2)
  TANDEM_FAKE_EXTRA_NEEDS_AUTH  in an MCP fixture: the seeded server table gains a copy of the seeded needs-auth entry
                          under this name, served like it. Synthetic: a test-only knob for two sign-in waits at once

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

Answers given at once, whenever a host frame is read (spec 9.2 of the MCP controls design):
  (a) In every fixture with a control_response, `initialize` is answered inline with the fixture's initialize response
      (the first control_response whose payload has a `commands` key, else the first control_response), and that line
      leaves the in-order replay.
      A fixture without any `control_response` (`plain-reply`, `history-reply`, `invalid-model`, `synthetic-long-reply`) leaves `initialize` unanswered, as today.
      The answer is written when the host frame is read inside `wait_for`, not at the response's old position (in
      `interrupt`, `model-switch` and `permission` it sat at `:4`, after other lines).
  (b) Every read of a host frame, inside any `wait_for`, answers `initialize` and every `mcp_*` request at once from (a)
      and the server table, and never lets it satisfy the predicate or consume a fixture line; so an `mcp_status` sent
      before the first prompt, while the fake waits for a `user` frame, is answered.
  (c) A fixture with a control_response carrying `mcpServers` is an MCP fixture: all its control_response lines leave the
      in-order replay and are served on demand; `interrupt` gets the recorded payload {"still_queued":[]}
      (`interrupt.ndjson:13`) and any other host control request (`set_model`, `set_permission_mode`,
      `rename_session`) a bare success. The server table is seeded from the first `mcpServers` payload without a
      `pending` entry; the first payload with `pending` entries (when present) is served to the first `mcp_status` only,
      so the pending refresh is exercised. A fixture without `mcpServers` payloads answers every `mcp_*` with
      {"subtype":"error","error":"Unknown subtype"} (a CLI that does not know them) and keeps the in-order pairing for
      its other control requests.
  (d) In an MCP fixture: `mcp_status` serves the table. `mcp_toggle` makes the entry `disabled` (config kept,
      serverInfo, tools and error dropped) or its seeded form again, with a bare success, on any status, `failed` and
      `needs-auth` included: a guess, not a recording (probes open question 4); an unknown name answers the error
      `Server not found: <name>` (recorded, `mcp-status:38`). `mcp_reconnect`: a `failed` entry answers its own error
      (recorded, `:36`), a `needs-auth` one `Server status: needs-auth` (recorded, `:37`), an unknown name
      `Server not found: <name>` (a guess here), anything else a bare success (a guess: a reconnect that works is
      unrecorded). `mcp_authenticate`: a `needs-auth` entry gets the fixture's `authUrl` payload (`:40`), an unknown
      name `Server not found: <name>` (a guess), a known server that is not `needs-auth` a bare success without
      `authUrl` (a guess, unrecorded). Any other `mcp_*` request gets a bare success.
  (e) In an MCP fixture, every replayed `init` has its `mcp_servers` rewritten from the table; every other fixture replays `init` verbatim (`mcp-slash` and `mcp-tool-call` keep their recorded thin lists, which IDE tests 3 and 4 rely on).
  (f) Every host frame is still logged.
  (g) The fake keeps the `uuid`s of the `user` frames it reads, in order, since the last `result` it wrote; when it writes a
      `result` line it replaces the line's `user_message_uuids` with that list (an empty list when it read none) and clears it;
      a `system/status` line that has `user_message_uuids` gets the list as it stands. So a result names the host's own sends,
      as the real CLI's does (`bash-mode:8` names both quiet sends, `bash-mode-respond:6` the command frame and `:41` the
      output frame), never the recorded `<command_uuid-N>` placeholders.
  (h) `get_context_usage` is answered inline, at any time and in every fixture, with the fixture's first control_response
      whose payload has a `categories` array with a `messages` category (the full answer; `parity-context:2`), else with
      {"subtype":"error","error":"Unsupported control request subtype: get_context_usage"} (the shape of `parity-context:19`).
  (i) `get_usage` is answered inline, at any time and in every fixture: with `skip_behaviors: true` by the first
      control_response whose payload has a `session` object and `behaviors: null` (`parity-usage:16`), without it by the
      first whose `behaviors` is an object (`:17`), else with the same error naming `get_usage`.
  (j) In a fixture whose name starts with `parity-`, every control_response line leaves the in-order replay (`initialize`
      is still answered by (a)): its answers were given to requests sent out of the recorded order. Any other host control
      request gets a bare success there, as in an MCP fixture (c).
  (k) `--add-dir` arguments are logged with the rest of the argv in `__spawn__`, nothing more.
"""
import copy
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
line_delay_ms = int(os.environ.get("TANDEM_FAKE_LINE_DELAY_MS") or 0)
gate_file = os.environ.get("TANDEM_FAKE_GATE_FILE")
auth_done_ms = os.environ.get("TANDEM_FAKE_AUTH_DONE_MS")
auth_done_ms = int(auth_done_ms) if auth_done_ms else None
extra_needs_auth = os.environ.get("TANDEM_FAKE_EXTRA_NEEDS_AUTH") or None
SPAWN_PREFIX = '{"type":"__spawn__"'
SESSION_PLACEHOLDER = re.compile(r"<session_id-\d+>")
SECRET_NAME_PARTS = ("TOKEN", "KEY", "SECRET", "PASSWORD", "CREDENTIAL", "AUTH")
UNKNOWN_SUBTYPE = "Unknown subtype"
read_user_uuids = []  # rule (g): the uuids of the user frames read since the last result written


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
            frame = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(frame, dict) and frame.get("type") == "user" and isinstance(frame.get("uuid"), str):
            read_user_uuids.append(frame["uuid"])
        return frame
    return None


def wait_for(predicate):
    """Reads host frames until one satisfies `predicate`; frames answered inline (answer_inline) never do."""
    while True:
        frame = read_host()
        if frame is None:
            sys.exit(0)  # the host closed stdin: end like the real CLI
        if answer_inline(frame):
            continue
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
    if message.get("type") == "result":
        message["user_message_uuids"] = list(read_user_uuids)
        del read_user_uuids[:]
    elif message.get("type") == "system" and message.get("subtype") == "status" and "user_message_uuids" in message:
        message["user_message_uuids"] = list(read_user_uuids)
    line = SESSION_PLACEHOLDER.sub(lambda _: escaped_session_id, json.dumps(message, separators=(",", ":")))
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


def payload_of(message):
    """A control_response line's payload object, or None."""
    payload = (message.get("response") or {}).get("response")
    return payload if isinstance(payload, dict) else None


def answer(request_id, payload=None, error=None):
    """Writes a control_response for `request_id`: an error with `error`, else a success with `payload` (bare when None)."""
    if error is not None:
        response = {"subtype": "error", "request_id": request_id, "error": error}
    else:
        response = {"subtype": "success", "request_id": request_id}
        if payload is not None:
            response["response"] = payload
    emit({"type": "control_response", "response": response})


def seeded_entry(name):
    return next((entry for entry in seeded if entry.get("name") == name), None)


def flip_signed_in():
    """TANDEM_FAKE_AUTH_DONE_MS: the servers whose sign-in started long enough ago turn `connected`."""
    if auth_done_ms is None:
        return
    now = time.monotonic()
    connected = next((entry for entry in seeded if entry.get("status") == "connected"), {})
    for name, started in list(auth_started.items()):
        if (now - started) * 1000 < auth_done_ms:
            continue
        del auth_started[name]
        for i, entry in enumerate(table):
            if entry.get("name") != name:
                continue
            flipped = {key: value for key, value in entry.items() if key not in ("error", "serverInfo", "tools")}
            flipped["status"] = "connected"
            for key in ("serverInfo", "tools"):
                if key in connected:
                    flipped[key] = copy.deepcopy(connected[key])
            table[i] = flipped


def answer_mcp(request_id, subtype, request):
    """An `mcp_*` request in an MCP fixture, from the server table (rule (d))."""
    global pending_served
    name = request.get("serverName")
    index = next((i for i, entry in enumerate(table) if entry.get("name") == name), None)
    entry = table[index] if index is not None else None
    not_found = "Server not found: %s" % name
    if subtype == "mcp_status":
        if pending_payload is not None and not pending_served:
            pending_served = True
            answer(request_id, copy.deepcopy(pending_payload))
        else:
            flip_signed_in()
            answer(request_id, {"mcpServers": copy.deepcopy(table)})
    elif subtype == "mcp_toggle":
        if entry is None:
            answer(request_id, error=not_found)
            return
        if request.get("enabled") is False:
            disabled = {key: value for key, value in entry.items() if key not in ("serverInfo", "tools", "error")}
            disabled["status"] = "disabled"
            table[index] = disabled
        else:
            table[index] = copy.deepcopy(seeded_entry(name) or entry)
        answer(request_id)
    elif subtype == "mcp_reconnect":
        if entry is None:
            answer(request_id, error=not_found)
        elif entry.get("status") == "failed":
            answer(request_id, error=entry.get("error") or "Connection closed")
        elif entry.get("status") == "needs-auth":
            answer(request_id, error="Server status: needs-auth")
        else:
            answer(request_id)
    elif subtype == "mcp_authenticate":
        if entry is None:
            answer(request_id, error=not_found)
        elif entry.get("status") == "needs-auth" and auth_payload is not None:
            auth_started.setdefault(name, time.monotonic())
            answer(request_id, copy.deepcopy(auth_payload))
        else:
            answer(request_id)
    else:
        answer(request_id)


def unsupported(subtype):
    return "Unsupported control request subtype: " + subtype


def answer_parity(request_id, subtype, request):
    """Rules (h) and (i): `get_context_usage` and `get_usage` from the fixture's recorded answers, else the CLI's error."""
    if delay_ms:
        time.sleep(delay_ms / 1000)
    if subtype == "get_context_usage":
        payload = context_payload
    elif request.get("skip_behaviors") is True:
        payload = usage_session_payload
    else:
        payload = usage_history_payload
    if payload is None:
        answer(request_id, error=unsupported(subtype))
    else:
        answer(request_id, copy.deepcopy(payload))


def answer_inline(frame):
    """Answers a host control request at once when rules (a)-(d) and (h)-(j) say so; True if it did (the frame is then used up)."""
    if frame.get("type") != "control_request":
        return False
    request = frame.get("request") or {}
    subtype = request.get("subtype")
    request_id = frame.get("request_id")
    if subtype == "initialize":
        if initialize_line is not None:
            answer(request_id, copy.deepcopy(payload_of(initialize_line)))
        return True
    if subtype in ("get_context_usage", "get_usage"):
        answer_parity(request_id, subtype, request)
        return True
    if isinstance(subtype, str) and subtype.startswith("mcp_"):
        if mcp_fixture:
            answer_mcp(request_id, subtype, request)
        else:
            answer(request_id, error=UNKNOWN_SUBTYPE)
        return True
    if mcp_fixture:
        answer(request_id, {"still_queued": []} if subtype == "interrupt" else None)
        return True
    if parity_fixture:
        answer(request_id)
        return True
    return False


def thin_list():
    """The table as `init.mcp_servers` lists it (`mcp-status:6`): name, status and source."""
    return [{key: entry[key] for key in ("name", "status", "source") if key in entry} for entry in table]


fixture = fixture_name(record_spawn())
fixture_path = os.path.join(HERE, "fixtures", fixture + ".ndjson")
if not os.path.isfile(fixture_path):
    sys.stderr.write("no fixture " + fixture + "\n")
    sys.exit(2)
with open(fixture_path, encoding="utf-8") as f:
    lines = [json.loads(l) for l in f if l.strip()]

escaped_session_id = json.dumps(session_id())[1:-1]  # as it appears inside a JSON string

responses = [message for message in lines if message.get("type") == "control_response"]
initialize_line = next((m for m in responses if "commands" in (payload_of(m) or {})), responses[0] if responses else None)
server_payloads = [payload_of(m) for m in responses if isinstance((payload_of(m) or {}).get("mcpServers"), list)]
mcp_fixture = bool(server_payloads)
parity_fixture = fixture.startswith("parity-")


def is_full_breakdown(payload):
    """A `get_context_usage` answer with its `messages` category: the full one, not the summary (`parity-context:18`)."""
    categories = payload.get("categories")
    return isinstance(categories, list) and any(isinstance(c, dict) and c.get("id") == "messages" for c in categories)


payloads = [payload_of(m) or {} for m in responses]
context_payload = next((p for p in payloads if is_full_breakdown(p)), None)
usage_session_payload = next((p for p in payloads if isinstance(p.get("session"), dict) and "behaviors" in p and p["behaviors"] is None), None)
usage_history_payload = next((p for p in payloads if isinstance(p.get("behaviors"), dict)), None)


def has_pending(payload):
    return any(isinstance(entry, dict) and entry.get("status") == "pending" for entry in payload["mcpServers"])


seeded = copy.deepcopy(next((p["mcpServers"] for p in server_payloads if not has_pending(p)), []))
seeded = [entry for entry in seeded if isinstance(entry, dict)]
if mcp_fixture and extra_needs_auth:
    needs_auth = next((entry for entry in seeded if entry.get("status") == "needs-auth"), None)
    if needs_auth is not None:
        extra = copy.deepcopy(needs_auth)
        extra["name"] = extra_needs_auth
        seeded.append(extra)
table = copy.deepcopy(seeded)
pending_payload = next((p for p in server_payloads if has_pending(p)), None)
pending_served = False
auth_payload = next((payload_of(m) for m in responses if "authUrl" in (payload_of(m) or {})), None)
auth_started = {}

if mcp_fixture or parity_fixture:
    replay = [message for message in lines if message.get("type") != "control_response"]
else:
    replay = [message for message in lines if message is not initialize_line]

for message in replay:
    kind = message.get("type")
    if kind == "system" and message.get("subtype") == "init":
        wait_for(lambda m: m.get("type") == "user")
        if mcp_fixture:
            message["mcp_servers"] = thin_list()
    if kind == "control_response":
        request = wait_for(lambda m: m.get("type") == "control_request")
        message["response"]["request_id"] = request.get("request_id")
    if kind == "control_request" and delay_ms:
        time.sleep(delay_ms / 1000)
    if kind == "control_request" and gate_file:
        wait_for_gate()
    if kind == "stream_event" and line_delay_ms:
        time.sleep(line_delay_ms / 1000)
    emit(message)
    if kind == "control_request":
        wait_for(lambda m: m.get("type") == "control_response"
                 and m.get("response", {}).get("request_id") == message.get("request_id"))

wait_for(lambda m: False)

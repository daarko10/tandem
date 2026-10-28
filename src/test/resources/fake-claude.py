#!/usr/bin/env python3
"""Fake `claude` for platform and IDE tests: replays a recorded fixture in step with the host.

Reads --session-id <id>, --resume <id>, --fork-session and --add-dir <dir> from its arguments and ignores the rest. Environment:
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
  TANDEM_FAKE_QUEUED_DELAY_MS  in a `queue-` fixture (rule (m)): after a `queued` lifecycle line that bound a host frame, sleep
                          this long (unset or 0: no sleep), so that a test sees the message wait before its `started` comes
  TANDEM_FAKE_EXIT_AT_END  after the last replayed line, sleep this many ms, then exit 0 (unset: read stdin until the host
                          closes it, as below), so that a test sees a process end by itself
  TANDEM_FAKE_SKIP_ADD_DIR  rule (l): this `--add-dir` directory is left out of every replayed init, as a CLI that did not add it
  TANDEM_FAKE_KEEP_ADD_DIR  rule (l): this directory is listed in every replayed init, after the others and once, with or without
                          `--add-dir`, as a CLI that keeps a folder a resume left out
  TANDEM_FAKE_EXIT_DELAY_MS  once the host closes stdin, sleep this long before exiting (unset or 0: exit at once), so that a
                          test can hold a stop (the host waits for the exit) while something else happens
  TANDEM_FAKE_EDIT_CANCEL_MS  rule (p): a replayed `tools/call` left unanswered this long is withdrawn as on an `interrupt`, without
                          the interrupt's receipt (the CLI's own MCP_TOOL_TIMEOUT; unset or 0: never)
  TANDEM_FAKE_RESULT_GATE_FILE  before emitting each replayed `result` line (a turn's end), wait until this file exists, checking
                          every 100 ms for up to 60 s, then emit it anyway: a test holds the turn running until it has seen
                          what shows only while a turn runs, in place of a slow stream

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
  (k) `--add-dir` arguments are logged with the rest of the argv in `__spawn__`.
  (l) With `--add-dir <dir>` (repeatable) in the argv, every replayed `system/init` gets `additional_directories` = those dirs
      in order, less the one TANDEM_FAKE_SKIP_ADD_DIR names; TANDEM_FAKE_KEEP_ADD_DIR adds its dir to every replayed init, after
      the others and once, with or without `--add-dir`; with neither the line replays as recorded.
  (m) In a fixture whose name starts with `queue-`, the `<command_uuid-N>` placeholders stand for the host's own `user` frames:
      a line naming one not yet bound (as `command_uuid`, or inside a `user_message_uuids` list) binds it to the oldest host
      `user` frame read and not yet bound, waiting for the next one when there is none, and every bound placeholder in the line
      is rewritten to that frame's `uuid`. A `command_lifecycle` `cancelled` line for a placeholder bound after the last `init` (a
      message sent while the turn ran, not the turn's own prompt) first waits for the host's next `cancel_async_message` or
      `interrupt`, and the `control_response` after it answers that request (`queue-cancel:24`-`:25`, `:55`-`:56`; the prompt's
      own `cancelled` at `:61` does not wait); any other replayed `control_response` still pairs with the host's next
      `control_request`. The wait before an `init` is met by a frame a line has named since the last `result` (bound then or
      before: `queue-later:53` names F, bound at `:19`, ahead of the `init` at `:54`). Rule (g) is off for these fixtures.
      `--resume-session-at` needs no rule: it is logged with the argv in `__spawn__`, like any other argument.
  (n) The CLI's `mcp_message` control requests (Tandem's own server, `ide-edit*`) replay in the recorded order, each waiting for the
      host's `control_response` to it (`tools/list` before the first turn's `init`, as recorded). The host's `mcp_response` to a
      `tools/call` is kept under the call's `_meta["claudecode/toolUseId"]`, and the replayed `user` line holding that tool use's
      `tool_result` is rewritten with it: `content` from the reply's `result.content` (its text joined into one string when the
      reply `isError`, as the CLI writes an error), `is_error` from `isError`, and the line's `tool_use_result` the content blocks,
      or `Error: <text>` for an error; a JSON-RPC error reply becomes an error with its message. So a test sees the text Tandem
      produced, never the recorded one. A `can_use_tool` for a Tandem tool is sent as recorded and its answer is not checked.
      With TANDEM_FAKE_LOG set, right after logging the host's `control_response` to a `tools/call` the fake appends
      {"type":"__disk__","tool_use_id":<the call's tool use id>,"text":<the call's `file_path` as read from disk now, null when
      it cannot be read>}, so a test sees what the disk held when the reply arrived (spec 9.7: saved before the reply).
  (o) In a fixture with `mcp_message` frames, `<path>` (the redactor's name for the recording's working directory) becomes this
      process's working directory (`os.getcwd()`, the project the host spawned it in) in every replayed frame: the `tools/call`
      arguments, the `can_use_tool` input, `init.cwd` and the result lines. Other fixtures keep `<path>` as recorded (rows such
      as `Read <path>` in `tools-read` are drawn from it). The directory is escaped once, as inside a JSON string: inside a
      `stream_event`'s `partial_json` (JSON within a JSON string) a `\\` or `"` in it would come out malformed. Neither occurs in
      a Linux or macOS temporary directory.
  (p) A host `interrupt` read while a replayed `tools/call` waits for its reply withdraws the call when the fixture recorded the
      withdrawal (a `notifications/cancelled` for that call's JSON-RPC id before the next `tools/call`, `ide-edit-stop:42`): the
      fake, without waiting for an `mcp_response`, sends the recorded lines from the call to the turn's `result` in order (the
      `stream_event`s the CLI had streamed, `notifications/cancelled` (`:42`), the interrupt's receipt `{"still_queued":[]}` (`:43`)
      answering the host's `interrupt`, the synthetic `tool_result` with `non_execution_kind:"user-rejected"` (`:44`),
      `[Request interrupted by user for tool use]` (`:45`) and the `aborted_tools` result (`:46`)), none of them waiting for the
      host, then goes on after that `result`. A late `mcp_response` for the withdrawn id is logged and ignored, as the CLI does.
      Without a recorded withdrawal an `interrupt` there is handled as anywhere else. TANDEM_FAKE_EDIT_CANCEL_MS does the same
      once the call has waited that long, and sends no receipt (there is no interrupt to answer).
"""
import copy
import fcntl
import json
import os
import re
import select
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
log_path = os.environ.get("TANDEM_FAKE_LOG")
delay_ms = int(os.environ.get("TANDEM_FAKE_DELAY_MS") or 0)
line_delay_ms = int(os.environ.get("TANDEM_FAKE_LINE_DELAY_MS") or 0)
gate_file = os.environ.get("TANDEM_FAKE_GATE_FILE")
result_gate_file = os.environ.get("TANDEM_FAKE_RESULT_GATE_FILE")
auth_done_ms = os.environ.get("TANDEM_FAKE_AUTH_DONE_MS")
auth_done_ms = int(auth_done_ms) if auth_done_ms else None
extra_needs_auth = os.environ.get("TANDEM_FAKE_EXTRA_NEEDS_AUTH") or None
SPAWN_PREFIX = '{"type":"__spawn__"'
SESSION_PLACEHOLDER = re.compile(r"<session_id-\d+>")
SECRET_NAME_PARTS = ("TOKEN", "KEY", "SECRET", "PASSWORD", "CREDENTIAL", "AUTH")
UNKNOWN_SUBTYPE = "Unknown subtype"
read_user_uuids = []  # rule (g): the uuids of the user frames read since the last result written
queued_delay_ms = int(os.environ.get("TANDEM_FAKE_QUEUED_DELAY_MS") or 0)
exit_at_end = os.environ.get("TANDEM_FAKE_EXIT_AT_END")
COMMAND_PLACEHOLDER = re.compile(r"<command_uuid-\d+>")
unbound_frames = []  # rule (m): the uuids of the host's user frames read and not yet bound, oldest first
bound = {}  # rule (m): placeholder -> the host frame's uuid
bound_after_init = set()  # rule (m): the placeholders bound since the last init
named_since_result = [False]  # rule (m): whether a line has named a bound placeholder since the last result
add_dirs = [args[i + 1] for i in range(len(args) - 1) if args[i] == "--add-dir"]  # rule (l)
skip_add_dir = os.environ.get("TANDEM_FAKE_SKIP_ADD_DIR") or None
keep_add_dir = os.environ.get("TANDEM_FAKE_KEEP_ADD_DIR") or None
exit_delay_ms = int(os.environ.get("TANDEM_FAKE_EXIT_DELAY_MS") or 0)
edit_cancel_ms = int(os.environ.get("TANDEM_FAKE_EDIT_CANCEL_MS") or 0)  # rule (p)
PATH_PLACEHOLDER = "<path>"  # rule (o)
escaped_cwd = json.dumps(os.getcwd())[1:-1]  # rule (o): the working directory as it appears inside a JSON string
host_replies = {}  # rule (n): tool use id -> the host's mcp_response to its tools/call
TIMED_OUT = object()  # read_host's answer when its timeout passed with no frame
stdin_buffer = bytearray()  # what was read from stdin and not yet split into lines: read with select, so a wait can time out (rule (p))
stdin_closed = [False]


def next_host_line(deadline):
    """The host's next stdin line (bytes, without its newline), None at the end of stdin, TIMED_OUT past [deadline] (monotonic)."""
    while True:
        end = stdin_buffer.find(b"\n")
        if end >= 0:
            line = bytes(stdin_buffer[:end])
            del stdin_buffer[:end + 1]
            return line
        if stdin_closed[0]:
            if stdin_buffer:
                line = bytes(stdin_buffer)
                del stdin_buffer[:]
                return line
            return None
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([0], [], [], remaining)[0]:
                return TIMED_OUT
        chunk = os.read(0, 65536)
        if chunk:
            stdin_buffer.extend(chunk)
        else:
            stdin_closed[0] = True


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


def read_host(timeout=None):
    """Next JSON frame from the host, None at end of input, or TIMED_OUT when [timeout] seconds passed without one."""
    deadline = None if timeout is None else time.monotonic() + timeout
    while True:
        raw = next_host_line(deadline)
        if raw is TIMED_OUT:
            return TIMED_OUT
        if raw is None:
            break
        line = raw.decode("utf-8", errors="replace").strip()
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
            unbound_frames.append(frame["uuid"])
        return frame
    if exit_delay_ms:
        time.sleep(exit_delay_ms / 1000)
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


def wait_for_gate(path):
    """Waits until [path] (TANDEM_FAKE_GATE_FILE or TANDEM_FAKE_RESULT_GATE_FILE) exists, for up to 60 s."""
    deadline = time.monotonic() + 60
    while not os.path.exists(path):
        if time.monotonic() > deadline:
            sys.stderr.write("gate file never appeared; going on\n")
            return
        time.sleep(0.1)


def emit(message):
    if queue_fixture:
        line = SESSION_PLACEHOLDER.sub(lambda _: escaped_session_id, json.dumps(message, separators=(",", ":")))
        line = COMMAND_PLACEHOLDER.sub(lambda m: bound.get(m.group(0), m.group(0)), line)
        write_line(line)
        return
    if message.get("type") == "result":
        message["user_message_uuids"] = list(read_user_uuids)
        del read_user_uuids[:]
    elif message.get("type") == "system" and message.get("subtype") == "status" and "user_message_uuids" in message:
        message["user_message_uuids"] = list(read_user_uuids)
    write_line(SESSION_PLACEHOLDER.sub(lambda _: escaped_session_id, json.dumps(message, separators=(",", ":"))))


def write_line(line):
    if edit_fixture:
        line = line.replace(PATH_PLACEHOLDER, escaped_cwd)  # rule (o)
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


def named_placeholders(message):
    """Rule (m): the placeholders a line names as `command_uuid` or inside a `user_message_uuids` list, in order."""
    names = []
    if isinstance(message.get("command_uuid"), str):
        names.append(message["command_uuid"])
    uuids = message.get("user_message_uuids")
    if isinstance(uuids, list):
        names.extend(u for u in uuids if isinstance(u, str))
    return [name for name in names if COMMAND_PLACEHOLDER.fullmatch(name)]


def bind_queue_line(message):
    """Rule (m): binds the placeholders `message` names; returns the request id a replayed cancel answers, if it waited for one."""
    newly = False
    for name in named_placeholders(message):
        if name not in bound:
            while not unbound_frames:
                wait_for(lambda m: m.get("type") == "user")
            bound[name] = unbound_frames.pop(0)
            bound_after_init.add(name)
            newly = True
        named_since_result[0] = True
    if message.get("type") == "command_lifecycle":
        name = message.get("command_uuid")
        if message.get("state") == "queued" and newly and queued_delay_ms:
            time.sleep(queued_delay_ms / 1000)
        if message.get("state") == "cancelled" and name in bound_after_init:
            request = wait_for(lambda m: m.get("type") == "control_request"
                               and (m.get("request") or {}).get("subtype") in ("cancel_async_message", "interrupt"))
            return request.get("request_id")
    return None


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
queue_fixture = fixture.startswith("queue-")


def is_mcp_message(message):
    return message.get("type") == "control_request" and (message.get("request") or {}).get("subtype") == "mcp_message"


def rpc_of(message):
    """The JSON-RPC message an `mcp_message` control request carries, or {}."""
    rpc = (message.get("request") or {}).get("message")
    return rpc if isinstance(rpc, dict) else {}


edit_fixture = any(is_mcp_message(message) for message in lines)  # rules (n) and (o)


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

def tool_call_of(message):
    """Rule (n): (JSON-RPC id, tool use id) of a replayed `tools/call`, or None for any other line."""
    rpc = rpc_of(message) if is_mcp_message(message) else {}
    if rpc.get("method") != "tools/call":
        return None
    meta = (rpc.get("params") or {}).get("_meta") or {}
    return rpc.get("id"), meta.get("claudecode/toolUseId")


def is_cancel_of(message, rpc_id):
    rpc = rpc_of(message) if is_mcp_message(message) else {}
    return rpc.get("method") == "notifications/cancelled" and (rpc.get("params") or {}).get("requestId") == rpc_id


def recorded_withdrawal(index, rpc_id):
    """Rule (p): the index of the `result` that ends the recorded withdrawal of the call at replay[index], or None."""
    for j in range(index + 1, len(replay)):
        if tool_call_of(replay[j]) is not None:
            return None
        if is_cancel_of(replay[j], rpc_id):
            return next((k for k in range(j, len(replay)) if replay[k].get("type") == "result"), None)
    return None


def with_host_reply(message):
    """Rule (n): a `user` line whose `tool_result` answers a call the host replied to carries the host's reply."""
    content = (message.get("message") or {}).get("content")
    if message.get("type") != "user" or not isinstance(content, list):
        return message
    for block in content:
        if not (isinstance(block, dict) and block.get("type") == "tool_result" and block.get("tool_use_id") in host_replies):
            continue
        reply = host_replies[block["tool_use_id"]]
        result = reply.get("result")
        error = reply.get("error")
        if isinstance(result, dict):
            blocks = [b for b in result.get("content") or [] if isinstance(b, dict)]
            is_error = result.get("isError") is True
        elif isinstance(error, dict):
            blocks = [{"type": "text", "text": str(error.get("message", ""))}]
            is_error = True
        else:
            continue
        if is_error:
            text = "\n".join(str(b.get("text", "")) for b in blocks if b.get("type") == "text")
            block["content"] = text
            block["is_error"] = True
            message["tool_use_result"] = "Error: " + text
        else:
            block["content"] = blocks
            block.pop("is_error", None)
            message["tool_use_result"] = copy.deepcopy(blocks)
    return message


def await_tool_reply(index, message):
    """
    Rule (n) and (p): waits for the host's reply to the `tools/call` at replay[index] and keeps it; returns the replay index to
    go on from: index + 1, or past the recorded withdrawal when an `interrupt` (or TANDEM_FAKE_EDIT_CANCEL_MS) withdrew the call.
    """
    rpc_id, tool_use_id = tool_call_of(message)
    end = recorded_withdrawal(index, rpc_id)
    deadline = time.monotonic() + edit_cancel_ms / 1000 if edit_cancel_ms and end is not None else None
    while True:
        frame = read_host(None if deadline is None else max(0.0, deadline - time.monotonic()))
        if frame is TIMED_OUT:
            return withdraw(index, end, None)
        if frame is None:
            sys.exit(0)  # the host closed stdin: end like the real CLI
        request = frame.get("request") or {}
        if end is not None and frame.get("type") == "control_request" and request.get("subtype") == "interrupt":
            return withdraw(index, end, frame.get("request_id"))
        if answer_inline(frame):
            continue
        response = frame.get("response") or {}
        if frame.get("type") == "control_response" and response.get("request_id") == message.get("request_id"):
            reply = (response.get("response") or {}).get("mcp_response")
            if isinstance(reply, dict) and tool_use_id is not None:
                host_replies[tool_use_id] = reply
            log_disk(message, tool_use_id)
            return index + 1


def log_disk(message, tool_use_id):
    """Rule (n): appends what the `tools/call` [message]'s `file_path` holds on disk now to TANDEM_FAKE_LOG, as a `__disk__` line."""
    if not log_path:
        return
    arguments = (rpc_of(message).get("params") or {}).get("arguments") or {}
    file_path = arguments.get("file_path")
    text = None
    if isinstance(file_path, str):
        try:
            with open(file_path.replace(PATH_PLACEHOLDER, os.getcwd()), encoding="utf-8") as file:
                text = file.read()
        except (OSError, UnicodeDecodeError):
            text = None
    with open(log_path, "a", encoding="utf-8") as log:
        log.write(json.dumps({"type": "__disk__", "tool_use_id": tool_use_id, "text": text}, separators=(",", ":")) + "\n")


def withdraw(index, end, interrupt_id):
    """Rule (p): sends replay[index + 1 .. end] as recorded, waiting for nothing; the receipt answers [interrupt_id] (none without)."""
    for message in replay[index + 1:end + 1]:
        if message.get("type") == "control_response":
            if interrupt_id is None:
                continue
            message["response"]["request_id"] = interrupt_id
        emit(message)
    return end + 1


cancel_request_id = None  # rule (m): the request a waited-for cancelled line's control_response answers
position = 0
while position < len(replay):
    message = replay[position]
    position += 1
    kind = message.get("type")
    if kind == "system" and message.get("subtype") == "init":
        if not (queue_fixture and named_since_result[0]):
            wait_for(lambda m: m.get("type") == "user")
        if queue_fixture:
            bound_after_init.clear()
        if mcp_fixture:
            message["mcp_servers"] = thin_list()
        if add_dirs or keep_add_dir:
            listed = [d for d in add_dirs if d != skip_add_dir]
            if keep_add_dir and keep_add_dir not in listed:
                listed.append(keep_add_dir)
            message["additional_directories"] = listed
    if queue_fixture:
        cancel_request_id = bind_queue_line(message) or cancel_request_id
    if kind == "control_response":
        if cancel_request_id is not None:
            message["response"]["request_id"] = cancel_request_id
            cancel_request_id = None
        else:
            request = wait_for(lambda m: m.get("type") == "control_request")
            message["response"]["request_id"] = request.get("request_id")
    if kind == "control_request" and delay_ms:
        time.sleep(delay_ms / 1000)
    if kind == "control_request" and gate_file:
        wait_for_gate(gate_file)
    if kind == "result" and result_gate_file:
        wait_for_gate(result_gate_file)
    if kind == "stream_event" and line_delay_ms:
        time.sleep(line_delay_ms / 1000)
    if kind == "user" and host_replies:
        message = with_host_reply(message)
    emit(message)
    if kind == "result":
        named_since_result[0] = False
    if tool_call_of(message) is not None:
        position = await_tool_reply(position - 1, message)
    elif kind == "control_request":
        wait_for(lambda m: m.get("type") == "control_response"
                 and m.get("response", {}).get("request_id") == message.get("request_id"))

if exit_at_end:
    time.sleep(int(exit_at_end) / 1000)
    sys.exit(0)
wait_for(lambda m: False)

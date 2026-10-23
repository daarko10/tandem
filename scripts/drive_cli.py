#!/usr/bin/env python3
"""Drive a real `claude` process over bidirectional stream-json and save the raw stdout transcript.

Usage: drive_cli.py <scenario> <raw-out.ndjson> [extra claude flags...]

Scenarios (each is the smallest exchange that exercises one part of docs/protocol.md):
  permission       one tool call allowed through can_use_tool, then one denied
  interrupt        a long reply interrupted after the first text delta
  model-switch     one turn, set_model to another model, a second turn
  permission-mode  one turn, set_permission_mode plan, a second turn, set_permission_mode acceptEdits, a third turn;
                   in plan mode the model sometimes drafts a plan instead of replying: the run then stops, record it again
  permission-mode-rejected
                   one turn, set_permission_mode bypassPermissions, which a CLI not launched with
                   --dangerously-skip-permissions rejects, a second turn
  fork             one turn and a rename_session, then a second process resumes that session with --fork-session
                   for one more turn; its lines follow the first process's in the output
  settings         a probe, not a fixture: one turn, then update_settings and apply_flag_settings asking for effort
                   high, then a second turn; the transcript's assistant lines show the effort each turn ran with
  effort-switch    one turn, apply_flag_settings effortLevel high, then an apply_flag_settings whose settings is a
                   string (which the CLI must reject), a second turn. CLI 2.1.306 takes any effortLevel over stdio, even
                   "bogus" and even with CLAUDE_CODE_EFFORT_LEVEL set, so a settings that is not an object is the one
                   refusal it records; with CLAUDE_CODE_EFFORT_LEVEL set, a CLI that refuses the high one is recorded too
  permission-mode-auto
                   one turn, set_permission_mode auto (answered either way), a second turn; record it once more with
                   --permission-mode auto as an extra flag to see what the spawn's init reports
  user-image       one turn whose user message holds a text block then a base64 image block (a 2 x 2 PNG built here),
                   as Tandem sends an attachment (docs/protocol.md 3)
  ask-two          one turn whose single AskUserQuestion call asks two questions, a one-answer one and a multi-select
                   one, answered together (Red, then Cheese and Ham); the run stops if the model asks anything else,
                   in another number of calls, or not at all: record it again (with --model opus as an extra flag)
  mcp-connectors   a claude.ai connector, named by TANDEM_PROBE_CONNECTOR (or --connector <name>, which never reaches
                   claude): mcp_status, mcp_authenticate on it (the sign-in link goes to stderr: open it within 3 minutes),
                   mcp_status every 2 s until it no longer needs auth, mcp_toggle off and on, mcp_reconnect, then the
                   prompt ping with an mcp_status and an mcp_toggle off sent while it streams and the toggle back on after
                   its result. Runs without --safe-mode and with --strict-mcp-config and an empty --mcp-config; the name is
                   never written to a file, and redact_fixture.py renames every connector
  mcp-connectors-all
                   the same frames without the two strict flags, so every configured server loads (the redactor keeps
                   only the claude.ai connectors); use it when mcp-connectors lists no connector
  bash-mode        the terminal's `!` as context only: <bash-input> and <bash-stdout> sent back to back, both
                   shouldQuery false and client_composed, a 3 s wait, then a prompt asking for the command's output
  bash-mode-respond
                   the `!` Claude answers: <bash-input> quiet, a 3 s wait, then <bash-stdout> without shouldQuery
  bash-mode-respond-together
                   bash-mode-respond with the two frames back to back (whether a merged quiet + querying batch gets a reply)
  bash-mode-context
                   context only as one frame, the line the CLI writes when it merges the pair, then bash-mode's prompt
  mcp-status       the MCP requests of docs/protocol.md 4.4 against three local servers (mcp_probe_servers.py: echo, a stdio
                   server with one tool; broken, /bin/false; authy, local HTTP that needs a sign-in it never grants):
                   mcp_status; a turn; mcp_status; mcp_toggle echo off; mcp_status; a turn; mcp_toggle echo on; mcp_status;
                   mcp_reconnect broken and authy; mcp_toggle nosuch; mcp_status; mcp_authenticate authy; mcp_status;
                   mcp_set_servers {echo2}; mcp_status; mcp_set_servers {}; mcp_status; a turn
  mcp-tool-call    the same servers: a prompt asking for mcp__echo__echo with "hello tandem", allowed; a prompt asking again
                   with "second", denied
  mcp-slash        the same servers and a project command (.claude/commands/hello.md): the prompts /mcp, /model, /help and
                   /hello, each to its result
                   The three MCP scenarios run without --safe-mode (it turns MCP servers off) and with
                   --mcp-config <cwd>/mcp.json --strict-mcp-config --setting-sources project,local

Run it from an empty directory (scripts/record-scenario.sh does this) and redact the output
with redact_fixture.py before committing. Uses whatever sign-in the local `claude` already has.
"""
import base64
import json
import os
import struct
import subprocess
import sys
import threading
import time
import queue
import zlib

TIMEOUT_S = 180
FIRST_MODEL = "claude-sonnet-5-5"
SECOND_MODEL = "claude-haiku-5-5"
INITIALIZE = {"subtype": "initialize", "hooks": None}

# Variables that tie a process to a parent Claude Code session or hosted environment; the plugin drops the same ones
# (cliEnvironment). A `claude` that inherits them, e.g. inside a hosted session, writes its transcript under the
# parent's session id. Everything else passes through, sign-in included, and CLAUDE_CODE_ENTRYPOINT gets Tandem's own
# value as in cliEnvironment, so a recording shows what Tandem gets.
PARENT_SESSION_VARIABLES = {
    "CLAUDECODE", "CLAUDE_PID", "CLAUDE_CODE_SESSION_ID", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ATTENDED",
    "CLAUDE_CODE_CONTAINER_ID", "CLAUDE_CODE_WORKER_EPOCH", "CLAUDE_SESSION_INGRESS_TOKEN_FILE",
}
PARENT_SESSION_PREFIXES = ("CLAUDE_CODE_REMOTE", "CLAUDE_CODE_MESSAGING_", "CLAUDE_CODE_ENVIRONMENT_")


def child_environment() -> dict[str, str]:
    # The probe's connector name (CONNECTOR_VARIABLE) is for this script and the redactor only: claude never needs it.
    env = {name: value for name, value in os.environ.items()
           if name not in PARENT_SESSION_VARIABLES and not name.startswith(PARENT_SESSION_PREFIXES)
           and name != CONNECTOR_VARIABLE}
    env["CLAUDE_CODE_ENTRYPOINT"] = "sdk-tandem"
    return env


class Cli:
    def __init__(self, extra: list[str], model: str | None = None, first_id: int = 0, drop: list[str] = ()):
        argv = ["claude", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json",
                "--include-partial-messages", "--permission-prompt-tool=stdio",
                # No user hooks, plugins or MCP servers: keeps fixtures reproducible and free of local setup.
                "--safe-mode"]
        argv = [arg for arg in argv if arg not in drop]  # a scenario that needs what --safe-mode turns off
        if model:
            argv += ["--model", model]
        self.extra = extra
        self.proc = subprocess.Popen(argv + extra, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, text=True, encoding="utf-8", env=child_environment())
        self.lines: list[str] = []
        self.inbox: "queue.Queue[dict]" = queue.Queue()
        # Request ids and user message uuids count up from here; a second process continues the first one's count.
        self.next_id = first_id
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self) -> None:
        for line in self.proc.stdout:
            line = line.rstrip("\n")
            if not line:
                continue
            self.lines.append(line)
            try:
                self.inbox.put(json.loads(line))
            except json.JSONDecodeError:
                pass
        self.inbox.put({"type": "__eof__"})

    def send(self, msg: dict) -> None:
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()

    def request(self, request: dict) -> None:
        self.next_id += 1
        self.send({"type": "control_request", "request_id": f"host-{self.next_id}", "request": request})

    def call(self, request: dict, allow_error: bool = False) -> dict:
        """Sends a control request and waits for the CLI's control_response to it. Fails if the CLI answers with an
        error, unless `allow_error` (a probe that records what the CLI says either way)."""
        self.request(request)
        request_id = f"host-{self.next_id}"
        message = self.until(lambda m: m.get("type") == "control_response"
                             and m.get("response", {}).get("request_id") == request_id)
        response = message.get("response", {})
        if response.get("subtype") == "error" and not allow_error:
            raise RuntimeError(f"claude rejected {request.get('subtype')}: {response.get('error')}")
        return message

    def user(self, text: str) -> None:
        self.user_content([{"type": "text", "text": text}])

    def user_blocks(self, text: str, png: bytes) -> None:
        """One user message as Tandem sends an attachment: the text block, then the image as a base64 block."""
        self.user_content([{"type": "text", "text": text},
                           {"type": "image", "source": {"type": "base64", "media_type": "image/png",
                                                        "data": base64.b64encode(png).decode()}}])

    def user_content(self, content: list[dict]) -> None:
        self.next_id += 1
        self.send({"type": "user", "session_id": "", "parent_tool_use_id": None, "uuid": self.last_uuid(),
                   "message": {"role": "user", "content": content}})

    def composed(self, content: str, quiet: bool) -> None:
        """A user frame as the terminal's `!` sends it: string content and client_composed (no @path expansion, no slash
        dispatch); a quiet one also has shouldQuery false, so the CLI appends it without a turn."""
        self.next_id += 1
        msg = {"type": "user", "session_id": "", "parent_tool_use_id": None, "uuid": self.last_uuid(),
               "message": {"role": "user", "content": content}, "client_composed": True}
        if quiet:
            msg["shouldQuery"] = False
        self.send(msg)

    def last_uuid(self) -> str:
        """The uuid of the user message sent last."""
        return f"00000000-0000-4000-8000-{self.next_id:012d}"

    def until(self, predicate, on_message=None) -> dict:
        while True:
            msg = self.inbox.get(timeout=TIMEOUT_S)
            if msg.get("type") == "__eof__":
                raise RuntimeError("claude exited before the scenario finished")
            if on_message:
                on_message(msg)
            if predicate(msg):
                return msg

    def close(self) -> int:
        self.proc.stdin.close()
        try:
            code = self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            code = self.proc.wait()
        self.reader.join(timeout=30)  # every line the process wrote is in `lines` now
        return code


def is_result(msg: dict) -> bool:
    return msg.get("type") == "result"


def is_init(msg: dict) -> bool:
    return msg.get("type") == "system" and msg.get("subtype") == "init"


def result_of(uuid: str):
    """The result that ends the user message `uuid`: a quiet send gets its own zero-turn result first. A CLI that lists no
    user_message_uuids ends it with its next result."""
    return lambda msg: is_result(msg) and uuid in (msg.get("user_message_uuids") or [uuid])


def permission(cli: Cli) -> None:
    decisions = iter(["allow", "deny"])

    def answer(msg: dict) -> None:
        req = msg.get("request", {})
        if msg.get("type") != "control_request" or req.get("subtype") != "can_use_tool":
            return
        if next(decisions, "deny") == "allow":
            body = {"behavior": "allow", "updatedInput": req.get("input", {}), "toolUseID": req.get("tool_use_id")}
        else:
            body = {"behavior": "deny", "message": "The user denied this command.", "toolUseID": req.get("tool_use_id")}
        cli.send({"type": "control_response",
                  "response": {"subtype": "success", "request_id": msg["request_id"], "response": body}})

    cli.user("Use the Bash tool to run exactly: touch tandem-fixture.txt. Then reply with: done.")
    cli.until(is_result, answer)
    cli.user("Use the Bash tool to run exactly: rm tandem-fixture.txt. If it is denied, reply with: denied.")
    cli.until(is_result, answer)


def interrupt(cli: Cli) -> None:
    cli.user("Count from 1 to 300, one number per line, nothing else.")
    cli.until(lambda m: m.get("type") == "stream_event"
              and m.get("event", {}).get("type") == "content_block_delta")
    cli.request({"subtype": "interrupt"})
    cli.until(is_result)


def model_switch(cli: Cli) -> None:
    cli.user("Reply with the single word: one")
    cli.until(is_result)
    cli.call({"subtype": "set_model", "model": SECOND_MODEL})
    cli.user("Reply with the single word: two")
    cli.until(is_result)


def permission_mode(cli: Cli) -> None:
    def replies_only(msg: dict) -> None:
        # The fixture is replayed without answering permission prompts, so every turn must be a plain reply.
        content = msg.get("message", {}).get("content", []) if msg.get("type") == "assistant" else []
        if msg.get("type") == "control_request" or any(block.get("type") == "tool_use" for block in content):
            raise RuntimeError("claude used a tool instead of replying; record the scenario again")

    cli.user("Reply with the single word: one")
    cli.until(is_result, replies_only)
    cli.call({"subtype": "set_permission_mode", "mode": "plan"})
    cli.user("Reply with the single word: two")
    cli.until(is_result, replies_only)
    cli.call({"subtype": "set_permission_mode", "mode": "acceptEdits"})
    cli.user("Reply with the single word: three")
    cli.until(is_result, replies_only)


def permission_mode_rejected(cli: Cli) -> None:
    cli.user("Reply with the single word: one")
    cli.until(is_result)
    response = cli.call({"subtype": "set_permission_mode", "mode": "bypassPermissions"}, allow_error=True)
    if response.get("response", {}).get("subtype") != "error":
        raise RuntimeError("claude accepted bypassPermissions; this scenario records its rejection")
    cli.user("Reply with the single word: two")
    cli.until(is_result)


def fork(cli: Cli) -> None:
    cli.user("Reply with the single word: alpha")
    session_id = cli.until(is_init)["session_id"]
    cli.until(is_result)
    cli.call({"subtype": "rename_session", "title": "Renamed by host", "source": "host"})
    print(f"fork: first process exit code {cli.close()}", file=sys.stderr)

    # Continue the id count so the fork's user message uuid differs from the one it inherits from the first session.
    forked = Cli(cli.extra + ["--resume", session_id, "--fork-session"], first_id=cli.next_id)
    try:
        forked.call(INITIALIZE)
        forked.user("Reply with the single word: beta")
        forked.until(is_result)
    finally:
        print(f"fork: second process exit code {forked.close()}", file=sys.stderr)
        cli.lines.extend(forked.lines)


def settings(cli: Cli) -> None:
    # Can effort change on a live session? docs/protocol.md 4.4 records the answer. Each request's control_response is in
    # the output, error or not. Without a `source`, update_settings writes no settings file; apply_flag_settings only
    # touches the running session.
    cli.user("Reply with the single word: one")
    cli.until(is_result)
    cli.call({"subtype": "update_settings", "settings": {"effortLevel": "high"}}, allow_error=True)
    cli.call({"subtype": "apply_flag_settings", "settings": {"effortLevel": "high"}}, allow_error=True)
    cli.user("Reply with the single word: two")
    cli.until(is_result)


def effort_switch(cli: Cli) -> None:
    # A live effort pick (docs/protocol.md 4.4): one the CLI takes and one it must refuse. 2.1.306 takes any effortLevel over
    # stdio ("bogus" too, with or without CLAUDE_CODE_EFFORT_LEVEL), so the refused one is a settings that is not an object.
    # With CLAUDE_CODE_EFFORT_LEVEL set, a CLI that refuses the first one as well is recorded as it answers.
    cli.user("Reply with the single word: one")
    cli.until(is_result)
    cli.call({"subtype": "apply_flag_settings", "settings": {"effortLevel": "high"}},
             allow_error=bool(os.environ.get("CLAUDE_CODE_EFFORT_LEVEL")))
    response = cli.call({"subtype": "apply_flag_settings", "settings": "high"}, allow_error=True)
    if response.get("response", {}).get("subtype") != "error":
        raise RuntimeError("claude accepted an apply_flag_settings whose settings is not an object; this scenario records a refusal")
    cli.user("Reply with the single word: two")
    cli.until(is_result)


def permission_mode_auto(cli: Cli) -> None:
    # Does the CLI take `auto` (docs/protocol.md 8)? The answer is recorded whichever it is.
    cli.user("Reply with the single word: one")
    cli.until(is_result)
    cli.call({"subtype": "set_permission_mode", "mode": "auto"}, allow_error=True)
    cli.user("Reply with the single word: two")
    cli.until(is_result)


def tiny_png() -> bytes:
    """A 2 x 2 RGB PNG, the same bytes every time: TranscriptReaderTest pins its base64 as TINY_PNG_BASE64.

    The pin assumes the reference zlib (as on CI's ubuntu-latest): zlib-ng compresses the same rows to other bytes. Moving to
    stored blocks (level 0) would remove that, but needs a re-recording of user-image and a new pinned literal.
    """
    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0)  # 2 x 2, depth 8, colour type 2 (RGB)
    rows = b"\x00" + b"\xff\x00\x00" * 2 + b"\x00" + b"\xff\x00\x00" * 2  # filter 0, then two red pixels, per row
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def user_image(cli: Cli) -> None:
    # An image attachment on the wire (docs/protocol.md 3): the text block, then one base64 image block.
    cli.user_blocks("What colour is this image? Reply with one word.", tiny_png())
    cli.until(is_result)


ASK_TWO_PROMPT = ("Use the AskUserQuestion tool exactly once, with two questions in that single call. Question 1: question "
                  "'Which colour?', header 'Colour', options Red and Blue, multiSelect false. Question 2: question "
                  "'Which toppings would you like?', header 'Toppings', options Cheese, Olives and Ham, multiSelect true. "
                  "Then reply with the two answers joined by ' and ', nothing else.")
# What ask_two expects each question of the one request to be: question, header, option labels, multiSelect.
ASK_TWO_QUESTIONS = [("Which colour?", "Colour", ["Red", "Blue"], False),
                     ("Which toppings would you like?", "Toppings", ["Cheese", "Olives", "Ham"], True)]
ASK_TWO_ANSWERS = ["Red", "Cheese, Ham"]


def ask_two(cli: Cli) -> None:
    """One AskUserQuestion call with the two questions of ASK_TWO_PROMPT, answered together. Raises, so that a bad recording
    fails here and never later in Gradle, when the model calls another tool, asks other questions, asks a second time, or
    replies without asking."""
    answered = []

    def answer(msg: dict) -> None:
        req = msg.get("request", {})
        if msg.get("type") != "control_request" or req.get("subtype") != "can_use_tool":
            return
        if req.get("tool_name") != "AskUserQuestion":
            raise RuntimeError(f"expected an AskUserQuestion request, got {req.get('tool_name')}")
        if answered:
            raise RuntimeError("a second can_use_tool after the AskUserQuestion request: the model split the questions")
        questions = req.get("input", {}).get("questions")
        shape = [(q.get("question"), q.get("header"), [o.get("label") for o in q.get("options", [])],
                  q.get("multiSelect", False)) for q in questions or []]
        if shape != ASK_TWO_QUESTIONS:
            raise RuntimeError(f"the AskUserQuestion request does not ask the two questions of the prompt: {shape}")
        answered.append(msg["request_id"])
        answers = {q["question"]: a for q, a in zip(questions, ASK_TWO_ANSWERS)}
        body = {"behavior": "allow", "updatedInput": {"questions": questions, "answers": answers},
                "toolUseID": req.get("tool_use_id")}
        cli.send({"type": "control_response",
                  "response": {"subtype": "success", "request_id": msg["request_id"], "response": body}})

    cli.user(ASK_TWO_PROMPT)
    cli.until(is_result, answer)
    if len(answered) != 1:
        raise RuntimeError("the turn ended without an AskUserQuestion request: the model replied without asking")


CONNECTOR_VARIABLE = "TANDEM_PROBE_CONNECTOR"
CONNECTOR_FLAG = "--connector"


def connector_name(extra: list[str]) -> tuple[str, list[str]]:
    """The connector the MCP scenarios sign in to: $TANDEM_PROBE_CONNECTOR, else the value after --connector in `extra`.
    Returns it with `extra` minus "--connector <value>", so nothing of it reaches claude. The name is the owner's: it is
    never written to a file (redact_fixture.py renames every connector in what claude printed)."""
    rest = list(extra)
    flagged = None
    if CONNECTOR_FLAG in rest:
        at = rest.index(CONNECTOR_FLAG)
        flagged = rest[at + 1] if at + 1 < len(rest) else None
        del rest[at:at + 2]
    name = os.environ.get(CONNECTOR_VARIABLE) or flagged
    if not name:
        print(f'set {CONNECTOR_VARIABLE}="claude.ai <Name>" (the name as the CLI\'s /mcp shows it), '
              f'or pass {CONNECTOR_FLAG} "claude.ai <Name>"', file=sys.stderr)
        raise SystemExit(2)
    return name, rest


def mcp_status_of(answer: dict, name: str) -> str | None:
    """The status mcp_status reports for the server `name`, None when it is not listed."""
    servers = answer.get("response", {}).get("response", {}).get("mcpServers")
    for server in servers if isinstance(servers, list) else []:
        if isinstance(server, dict) and server.get("name") == name:
            return server.get("status")
    return None


def mcp_connectors(cli: Cli, connector: str, sleep=time.sleep, every: float = 2.0, timeout: float = 180.0) -> None:
    """A claude.ai connector driven as Tandem's MCP panel drives it (spec 10 item 6, 11 items 1 and 13). mcp_authenticate,
    the toggles and the reconnect may fail: the answer is what the recording is for. An error answer to an mcp_status
    ends the run."""
    status = {"subtype": "mcp_status"}

    def toggle(enabled: bool) -> dict:
        return {"subtype": "mcp_toggle", "serverName": connector, "enabled": enabled}

    cli.call(status)
    answer = cli.call({"subtype": "mcp_authenticate", "serverName": connector}, allow_error=True)
    response = answer.get("response", {})
    if response.get("subtype") == "error":
        # Spec 3.3 step 4's way out, a terminal login and then Reconnect, is unrecorded: the poll below is the window for it.
        print(f"mcp_authenticate was refused: {response.get('error')}; run claude mcp login \"{connector}\" in a terminal "
              f"within 3 minutes", file=sys.stderr)
    else:
        link = (response.get("response") or {}).get("authUrl")
        if link:
            print(f"open this link within 3 minutes: {link}", file=sys.stderr)
    waited = 0.0
    while mcp_status_of(cli.call(status), connector) == "needs-auth" and waited < timeout:
        sleep(every)
        waited += every
    cli.call(toggle(False), allow_error=True)
    cli.call(toggle(True), allow_error=True)
    cli.call({"subtype": "mcp_reconnect", "serverName": connector}, allow_error=True)

    # Does the CLI answer mcp_* requests while a turn streams, or only after its result? Sent on the turn's init, answers
    # not awaited; the toggle back on after the result is awaited, and the CLI answers in order.
    sent_mid_turn = []

    def during(msg: dict) -> None:
        if is_init(msg) and not sent_mid_turn:
            sent_mid_turn.append(True)
            cli.request(status)
            cli.request(toggle(False))

    cli.user("ping")
    cli.until(result_of(cli.last_uuid()), on_message=during)
    if not sent_mid_turn:
        print("no system/init came before the ping's result, so the mid-turn mcp_status and mcp_toggle were not sent",
              file=sys.stderr)
    cli.call(toggle(True), allow_error=True)


def mcp_connectors_all(cli: Cli, connector: str) -> None:
    """mcp-connectors' frames; the scenario differs only in its argv (SCENARIO_FLAGS)."""
    mcp_connectors(cli, connector)


# The MCP fixtures' local servers (docs/protocol.md 10): scripts/mcp_probe_servers.py serves echo and authy.
PROBE_SERVERS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mcp_probe_servers.py")
PROBE_SCENARIOS = {"mcp-status", "mcp-tool-call", "mcp-slash"}
# mcp-slash's project command, in the working directory's .claude/commands/.
HELLO_COMMAND = "---\ndescription: Says hello from a project command\n---\nReply with exactly: hello from the hello command\n"


def echo_config() -> dict:
    return {"type": "stdio", "command": sys.executable, "args": ["-I", PROBE_SERVERS, "echo"]}


def probe_flags(work: str) -> list[str]:
    """The MCP scenarios' flags: only the probe servers, and no user settings (which could add hooks or servers)."""
    return ["--mcp-config", os.path.join(work, "mcp.json"), "--strict-mcp-config", "--setting-sources", "project,local"]


def start_probe_servers(work: str, scenario: str, wait: float = 10.0) -> subprocess.Popen:
    """Starts authy, then writes <work>/mcp.json naming echo, broken and authy (and, for mcp-slash, the project command
    /hello). Returns authy's process, which the caller ends."""
    port_file = os.path.join(work, "authy.port")
    authy = subprocess.Popen([sys.executable, "-I", PROBE_SERVERS, "authy", port_file], stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + wait
    while not os.path.exists(port_file):
        if authy.poll() is not None or time.monotonic() > deadline:
            authy.kill()
            raise RuntimeError("the authy probe server did not start")
        time.sleep(0.05)
    with open(port_file, encoding="utf-8") as f:
        port = f.read().strip()
    os.remove(port_file)
    servers = {"echo": echo_config(), "broken": {"type": "stdio", "command": "/bin/false", "args": []},
               "authy": {"type": "http", "url": f"http://127.0.0.1:{port}/mcp"}}
    with open(os.path.join(work, "mcp.json"), "w", encoding="utf-8") as f:
        json.dump({"mcpServers": servers}, f)
    if scenario == "mcp-slash":
        commands = os.path.join(work, ".claude", "commands")
        os.makedirs(commands, exist_ok=True)
        with open(os.path.join(commands, "hello.md"), "w", encoding="utf-8") as f:
            f.write(HELLO_COMMAND)
    return authy


def mcp_status(cli: Cli) -> None:
    """Every MCP request of docs/protocol.md 4.4, the error answers included (allowed: the answer is what the recording is
    for). An mcp_status, the toggle of echo or an mcp_set_servers that fails ends the run."""
    status = {"subtype": "mcp_status"}

    def toggle(name: str, enabled: bool) -> dict:
        return {"subtype": "mcp_toggle", "serverName": name, "enabled": enabled}

    def turn(word: str) -> None:
        cli.user(f"Reply with the single word: {word}")
        cli.until(result_of(cli.last_uuid()))

    cli.call(status)  # before the first turn: the stdio servers are still pending (mcp-status.ndjson:2)
    turn("one")
    cli.call(status)
    cli.call(toggle("echo", False))
    cli.call(status)
    turn("two")  # its init lists echo disabled
    cli.call(toggle("echo", True))
    cli.call(status)
    cli.call({"subtype": "mcp_reconnect", "serverName": "broken"}, allow_error=True)
    cli.call({"subtype": "mcp_reconnect", "serverName": "authy"}, allow_error=True)
    cli.call(toggle("nosuch", False), allow_error=True)
    cli.call(status)
    cli.call({"subtype": "mcp_authenticate", "serverName": "authy"}, allow_error=True)
    cli.call(status)
    cli.call({"subtype": "mcp_set_servers", "servers": {"echo2": echo_config()}})
    cli.call(status)
    cli.call({"subtype": "mcp_set_servers", "servers": {}})
    cli.call(status)
    turn("three")


def mcp_tool_call(cli: Cli) -> None:
    """An MCP tool call allowed, then one denied (each can_use_tool in turn: allow, then deny)."""
    decisions = iter(["allow", "deny"])

    def answer(msg: dict) -> None:
        req = msg.get("request", {})
        if msg.get("type") != "control_request" or req.get("subtype") != "can_use_tool":
            return
        if next(decisions, "deny") == "allow":
            body = {"behavior": "allow", "updatedInput": req.get("input", {}), "toolUseID": req.get("tool_use_id")}
        else:
            body = {"behavior": "deny", "message": "The user denied this call.", "toolUseID": req.get("tool_use_id")}
        cli.send({"type": "control_response",
                  "response": {"subtype": "success", "request_id": msg["request_id"], "response": body}})

    cli.user("Use the echo tool of the echo MCP server (mcp__echo__echo) with text: hello tandem. "
             "Reply with exactly the text it returns.")
    cli.until(result_of(cli.last_uuid()), answer)
    cli.user("Call the same echo tool again with text: second. If the call is denied, reply with the single word: denied.")
    cli.until(result_of(cli.last_uuid()), answer)


def mcp_slash(cli: Cli) -> None:
    """Three local commands the CLI answers without the model, then a project command, which runs a turn."""
    for prompt in ("/mcp", "/model", "/help", "/hello"):
        cli.user(prompt)
        cli.until(result_of(cli.last_uuid()))


# The bash probes' frames (docs/superpowers/research/2026-10-21-bash-mode-probe.md 1); BASH_CONTEXT is the line the CLI
# writes when it merges the two quiet sends (transcripts/bash-mode.jsonl:5).
BASH_INPUT = "<bash-input>echo bash-mode-probe && printf err >&2</bash-input>"
BASH_OUTPUT = "<bash-stdout>bash-mode-probe\nerr</bash-stdout><bash-stderr></bash-stderr>"
BASH_CONTEXT = BASH_INPUT + "\n" + BASH_OUTPUT
BASH_PROMPT = "Reply with only the text the command printed to stdout"


def bash_mode(cli: Cli, sleep=time.sleep) -> None:
    """Context only, as two quiet frames back to back: the CLI merges them and answers with a zero-turn result; the model
    sees them on the next prompt."""
    cli.composed(BASH_INPUT, quiet=True)
    cli.composed(BASH_OUTPUT, quiet=True)
    sleep(3)
    cli.user(BASH_PROMPT)
    cli.until(result_of(cli.last_uuid()))


def bash_mode_respond(cli: Cli, sleep=time.sleep, wait: float = 3.0) -> None:
    """The `!` Claude answers: the command quiet, then its output as a querying frame, which starts a turn."""
    cli.composed(BASH_INPUT, quiet=True)
    if wait:
        sleep(wait)
    cli.composed(BASH_OUTPUT, quiet=False)
    cli.until(result_of(cli.last_uuid()))


def bash_mode_respond_together(cli: Cli) -> None:
    """bash_mode_respond's frames back to back: does a merged quiet + querying batch still get a reply? (spec 12.6)"""
    bash_mode_respond(cli, wait=0)


def bash_mode_context(cli: Cli, sleep=time.sleep) -> None:
    """Context only as the single frame Tandem sends (spec 12.4's bashContext), then bash_mode's prompt."""
    cli.composed(BASH_CONTEXT, quiet=True)
    sleep(3)
    cli.user(BASH_PROMPT)
    cli.until(result_of(cli.last_uuid()))


SCENARIOS = {"permission": permission, "interrupt": interrupt, "model-switch": model_switch,
             "permission-mode": permission_mode, "permission-mode-rejected": permission_mode_rejected, "fork": fork,
             "settings": settings, "effort-switch": effort_switch, "permission-mode-auto": permission_mode_auto,
             "user-image": user_image, "ask-two": ask_two,
             "mcp-connectors": mcp_connectors, "mcp-connectors-all": mcp_connectors_all,
             "bash-mode": bash_mode, "bash-mode-respond": bash_mode_respond,
             "bash-mode-respond-together": bash_mode_respond_together, "bash-mode-context": bash_mode_context,
             "mcp-status": mcp_status, "mcp-tool-call": mcp_tool_call, "mcp-slash": mcp_slash}
# Scenarios whose run also takes the connector's name (connector_name).
CONNECTOR_SCENARIOS = {"mcp-connectors", "mcp-connectors-all"}

# Flags a scenario adds to the caller's. The settings probe loads no user settings: 2.1.301 refuses an update_settings for
# a source that --setting-sources leaves out, so even a CLI that took the user's settings as the default source would
# refuse rather than write that file during the probe.
SCENARIO_FLAGS = {"settings": ["--setting-sources", "project,local"],
                  # Tandem's argv, as the ask-single probe was recorded with (its built-in edit tools are disallowed).
                  "ask-two": ["--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit"],
                  # No configured server but the account's claude.ai connectors (strict mode may drop those too: then
                  # record mcp-connectors-all). Never --setting-sources: it would hide what the user's settings enable.
                  "mcp-connectors": ["--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}']}
# Base flags a scenario removes from Cli's argv: --safe-mode turns MCP servers off.
SCENARIO_ARGV_DROPS = {"mcp-connectors": ["--safe-mode"], "mcp-connectors-all": ["--safe-mode"],
                       **{name: ["--safe-mode"] for name in PROBE_SCENARIOS}}


def main(scenario: str, out: str, extra: list[str]) -> None:
    run = SCENARIOS[scenario]
    args = []
    servers = None
    if scenario in CONNECTOR_SCENARIOS:
        name, extra = connector_name(extra)
        args.append(name)
        cli = Cli(extra + SCENARIO_FLAGS.get(scenario, []), drop=SCENARIO_ARGV_DROPS.get(scenario, []))
    elif scenario in PROBE_SCENARIOS:
        work = os.getcwd()
        servers = start_probe_servers(work, scenario)
        try:
            cli = Cli(extra + probe_flags(work), drop=SCENARIO_ARGV_DROPS[scenario])
        except BaseException:
            servers.kill()
            raise
    else:
        cli = Cli(extra + SCENARIO_FLAGS.get(scenario, []), model=FIRST_MODEL if scenario == "model-switch" else None)
    try:
        cli.call(INITIALIZE)
        run(cli, *args)
    finally:
        code = cli.close()
        if servers is not None:
            servers.kill()
            servers.wait()
        with open(out, "w", encoding="utf-8") as f:
            f.write("\n".join(cli.lines) + "\n")
        print(f"{scenario}: {len(cli.lines)} lines, claude exit code {code}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] not in SCENARIOS:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], sys.argv[3:])

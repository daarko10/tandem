#!/usr/bin/env python3
"""Drive a real `claude` process over bidirectional stream-json and save the raw stdout transcript.

Usage: drive_cli.py <scenario> <raw-out.ndjson> [extra claude flags...]

Scenarios (each is the smallest exchange that exercises one part of docs/protocol.md):
  permission       one tool call allowed through can_use_tool, then one denied
  interrupt        a long reply interrupted after the first text delta
  model-switch     one turn, set_model to another model, a second turn
  permission-mode  one turn, set_permission_mode plan, a second turn, set_permission_mode acceptEdits, a third turn;
                   in plan mode the model sometimes drafts a plan instead of replying: the run then stops, record it again
  fork             one turn and a rename_session, then a second process resumes that session with --fork-session
                   for one more turn; its lines follow the first process's in the output
  settings         a probe, not a fixture: one turn, then update_settings and apply_flag_settings asking for effort
                   high, then a second turn; the transcript's assistant lines show the effort each turn ran with

Run it from an empty directory (scripts/record-scenario.sh does this) and redact the output
with redact_fixture.py before committing. Uses whatever sign-in the local `claude` already has.
"""
import json
import os
import subprocess
import sys
import threading
import queue

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
    env = {name: value for name, value in os.environ.items()
           if name not in PARENT_SESSION_VARIABLES and not name.startswith(PARENT_SESSION_PREFIXES)}
    env["CLAUDE_CODE_ENTRYPOINT"] = "sdk-tandem"
    return env


class Cli:
    def __init__(self, extra: list[str], model: str | None = None, first_id: int = 0):
        argv = ["claude", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json",
                "--include-partial-messages", "--permission-prompt-tool=stdio",
                # No user hooks, plugins or MCP servers: keeps fixtures reproducible and free of local setup.
                "--safe-mode"]
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
        self.next_id += 1
        self.send({"type": "user", "session_id": "", "parent_tool_use_id": None,
                   "uuid": f"00000000-0000-4000-8000-{self.next_id:012d}",
                   "message": {"role": "user", "content": [{"type": "text", "text": text}]}})

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


SCENARIOS = {"permission": permission, "interrupt": interrupt, "model-switch": model_switch,
             "permission-mode": permission_mode, "fork": fork, "settings": settings}

# Flags a scenario adds to the caller's. The settings probe loads no user settings: 2.1.301 refuses an update_settings for
# a source that --setting-sources leaves out, so even a CLI that took the user's settings as the default source would
# refuse rather than write that file during the probe.
SCENARIO_FLAGS = {"settings": ["--setting-sources", "project,local"]}


def main(scenario: str, out: str, extra: list[str]) -> None:
    run = SCENARIOS[scenario]
    cli = Cli(extra + SCENARIO_FLAGS.get(scenario, []), model=FIRST_MODEL if scenario == "model-switch" else None)
    try:
        cli.call(INITIALIZE)
        run(cli)
    finally:
        code = cli.close()
        with open(out, "w", encoding="utf-8") as f:
            f.write("\n".join(cli.lines) + "\n")
        print(f"{scenario}: {len(cli.lines)} lines, claude exit code {code}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] not in SCENARIOS:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], sys.argv[3:])

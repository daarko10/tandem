#!/usr/bin/env python3
"""Drive a real `claude` process over bidirectional stream-json and save the raw stdout transcript.

Usage: drive_cli.py <scenario> <raw-out.ndjson> [extra claude flags...]

Scenarios (each is the smallest exchange that exercises one part of docs/protocol.md):
  permission    one tool call allowed through can_use_tool, then one denied
  interrupt     a long reply interrupted after the first text delta
  model-switch  one turn, set_model to another model, a second turn

Run it from an empty directory (scripts/record-scenario.sh does this) and redact the output
with redact_fixture.py before committing. Uses whatever sign-in the local `claude` already has.
"""
import json
import subprocess
import sys
import threading
import queue

TIMEOUT_S = 180
FIRST_MODEL = "claude-sonnet-5-5"
SECOND_MODEL = "claude-haiku-5-5"


class Cli:
    def __init__(self, extra: list[str], model: str | None = None):
        argv = ["claude", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json",
                "--include-partial-messages", "--permission-prompt-tool=stdio",
                # No user hooks, plugins or MCP servers: keeps fixtures reproducible and free of local setup.
                "--safe-mode"]
        if model:
            argv += ["--model", model]
        self.proc = subprocess.Popen(argv + extra, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
        self.lines: list[str] = []
        self.inbox: "queue.Queue[dict]" = queue.Queue()
        self.next_id = 0
        threading.Thread(target=self._read, daemon=True).start()

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
            return self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            return self.proc.wait()


def is_result(msg: dict) -> bool:
    return msg.get("type") == "result"


def is_init_response(msg: dict) -> bool:
    return msg.get("type") == "control_response" and msg.get("response", {}).get("request_id") == "host-1"


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
    cli.request({"subtype": "set_model", "model": SECOND_MODEL})
    cli.until(lambda m: m.get("type") == "control_response"
              and m.get("response", {}).get("request_id") == f"host-{cli.next_id}")
    cli.user("Reply with the single word: two")
    cli.until(is_result)


SCENARIOS = {"permission": permission, "interrupt": interrupt, "model-switch": model_switch}


def main(scenario: str, out: str, extra: list[str]) -> None:
    run = SCENARIOS[scenario]
    cli = Cli(extra, model=FIRST_MODEL if scenario == "model-switch" else None)
    try:
        cli.request({"subtype": "initialize", "hooks": None})
        cli.until(is_init_response)
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

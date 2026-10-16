#!/usr/bin/env python3
"""Redact a recorded `claude` stream-json transcript so it is safe to commit as a test fixture.

Usage: redact_fixture.py <in.ndjson> <out.ndjson> [<in> <out> ...]

- Replaces ids (session_id, uuid, request_id, message ids, tool_use ids, also when used as object keys)
  with stable placeholders.
- Replaces absolute paths with <path>, e-mail addresses with <email> and git branch names with <branch>.
- Empties environment-specific lists in system/init and in the initialize control_response
  (MCP servers, plugins, skills, agents, commands) and masks account details.
- Keeps every message type, field name and ordering, which is what the protocol tests care about.
- Also redacts the CLI's on-disk transcripts (<session id>.jsonl), which hold one JSON object per line as well.
  Pass a stream and its transcripts in one call: they share the placeholders, so an id reads the same in all of them.
  A line that is not a JSON object is dropped with a warning.
- Cuts transcript `attachment` lines down to their type: the payload (the CLI's system prompt, tool, skill and agent
  listings, an environment snapshot) and its `rendered` copy describe the recording machine, not the protocol.
  Every other field of the line stays.
- Leaves values that already are placeholders (<name-N>) alone, so a second run over a redacted file changes nothing.
"""
import json
import re
import sys

ID_KEYS = {
    "session_id", "uuid", "request_id", "parent_uuid", "tool_use_id", "id",
    "api_message_id", "summarizes_uuid", "user_message_uuid", "refused_user_message_uuid", "new_conversation_id",
    "command_uuid",
    # Transcript lines
    "parentUuid", "sessionId", "promptId", "leafUuid", "requestId", "toolUseID", "agentId", "sourceToolUseID",
    "sourceToolAssistantUUID", "serverClassifierRequest",
    "organizationUuid",  # credential_org attachment: the signed-in account's organization
}
ID_LIST_KEYS = {"user_message_uuids", "retracted_message_uuids", "supersedes"}
INIT_LISTS = {"mcp_servers", "plugins", "skills", "agents", "slash_commands", "terminal_slash_commands", "commands"}
PATH_RE = re.compile(r"(?:/(?:root|home|Users|tmp|private|var|opt)|[A-Za-z]:\\\\)[^\s\"']*")
API_ID_RE = re.compile(r"^(?:toolu|srvtoolu|msg|req)_[A-Za-z0-9]+$")
EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
PLACEHOLDER_RE = re.compile(r"<[A-Za-z_]\w*-\d+>")

ids: dict[str, str] = {}


def stable_id(value: str, key: str) -> str:
    if PLACEHOLDER_RE.fullmatch(value):  # redacted before
        return value
    if value not in ids:
        ids[value] = f"<{key}-{len(ids) + 1}>"
    return ids[value]


def scrub(node, key=None):
    if isinstance(node, dict):
        # API ids can also appear as object keys (e.g. wire_tool_inputs).
        return {(stable_id(k, "id") if API_ID_RE.match(k) else k): scrub(v, k) for k, v in node.items()}
    if isinstance(node, list):
        return [scrub(v, key) for v in node]
    if isinstance(node, str):
        if (key in ID_KEYS or key in ID_LIST_KEYS) and node:
            return stable_id(node, key)
        if key == "gitBranch" and node:
            return "<branch>"
        node = PATH_RE.sub("<path>", node)
        return EMAIL_RE.sub("<email>", node)
    return node


def empty_lists(node: dict) -> None:
    for k in INIT_LISTS & node.keys():
        node[k] = []


def strip_attachment(msg: dict) -> None:
    if "attachment" in msg:
        kind = msg["attachment"].get("type") if isinstance(msg["attachment"], dict) else None
        msg["attachment"] = {"type": kind if isinstance(kind, str) else "unknown"}
    if "rendered" in msg:
        msg["rendered"] = ""


def redact(src: str, dst: str) -> None:
    out = []
    with open(src, encoding="utf-8") as f:
        for number, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                msg = None
            if not isinstance(msg, dict):
                print(f"{src} line {number}: not a JSON object, dropped", file=sys.stderr)
                continue
            if msg.get("type") == "system" and msg.get("subtype") == "init":
                empty_lists(msg)
            if msg.get("type") == "attachment":
                strip_attachment(msg)
            if msg.get("type") == "control_response":
                payload = msg.get("response", {}).get("response")
                if isinstance(payload, dict) and "commands" in payload:  # initialize response
                    empty_lists(payload)
                    if isinstance(payload.get("account"), dict):
                        payload["account"] = {k: "<account>" for k in payload["account"]}
                    if "pid" in payload:
                        payload["pid"] = 0
            out.append(json.dumps(scrub(msg), ensure_ascii=False, separators=(",", ":")))
    with open(dst, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


if __name__ == "__main__":
    if len(sys.argv) < 3 or len(sys.argv) % 2 == 0:
        sys.exit(__doc__)
    for i in range(1, len(sys.argv), 2):
        redact(sys.argv[i], sys.argv[i + 1])

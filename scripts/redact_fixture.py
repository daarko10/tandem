#!/usr/bin/env python3
"""Redact a recorded `claude` stream-json transcript so it is safe to commit as a test fixture.

Usage: redact_fixture.py <in.ndjson> <out.ndjson>

- Replaces ids (session_id, uuid, request_id, message ids, tool_use ids) with stable placeholders.
- Replaces absolute paths with <path> and e-mail addresses with <email>.
- Empties environment-specific lists in system/init (MCP servers, plugins, skills, agents, commands).
- Keeps every message type, field name and ordering, which is what the protocol tests care about.
"""
import json
import re
import sys

ID_KEYS = {"session_id", "uuid", "request_id", "parent_uuid", "tool_use_id", "id"}
INIT_LISTS = {"mcp_servers", "plugins", "skills", "agents", "slash_commands", "terminal_slash_commands"}
PATH_RE = re.compile(r"(?:/(?:root|home|Users|tmp|private|var|opt)|[A-Za-z]:\\\\)[^\s\"']*")
EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")

ids: dict[str, str] = {}


def stable_id(value: str, key: str) -> str:
    if value not in ids:
        ids[value] = f"<{key}-{len(ids) + 1}>"
    return ids[value]


def scrub(node, key=None):
    if isinstance(node, dict):
        return {k: scrub(v, k) for k, v in node.items()}
    if isinstance(node, list):
        return [scrub(v, key) for v in node]
    if isinstance(node, str):
        if key in ID_KEYS and node:
            return stable_id(node, key)
        node = PATH_RE.sub("<path>", node)
        return EMAIL_RE.sub("<email>", node)
    return node


def main(src: str, dst: str) -> None:
    out = []
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            msg = json.loads(line)
            if msg.get("type") == "system" and msg.get("subtype") == "init":
                for k in INIT_LISTS & msg.keys():
                    msg[k] = []
            out.append(json.dumps(scrub(msg), ensure_ascii=False, separators=(",", ":")))
    with open(dst, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])

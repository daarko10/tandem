#!/usr/bin/env python3
"""Redact a recorded `claude` stream-json transcript so it is safe to commit as a test fixture.

Usage: redact_fixture.py <in.ndjson> <out.ndjson> [<in> <out> ...]

- Replaces ids (session_id, uuid, request_id, message ids, tool_use ids, task and agent ids, also when used as
  object keys) with stable placeholders. One value gets one placeholder wherever it appears, so a task's `task_id`,
  the `agent_id` of the lines it produces and the `parent_tool_use_id` pointing at its tool_use stay correlated.
- Replaces long base64 payloads (the `data` of a base64 source and the `base64` of an image tool_use_result) with
  "<base64>". Only a `data` or `base64` value of at least 256 base64 characters counts, so short strings are never touched.
- Replaces absolute paths with <path>, e-mail addresses with <email>, git branch names with <branch> and the
  entrypoint (how the recording was started) with <entrypoint>.
- Empties environment-specific lists in system/init and in the initialize control_response
  (MCP servers, plugins, skills, agents, commands) and masks account details. The MCP fixtures keep the lists their
  tests read (INIT_KEEP, by fixture name: the output path's file name without its extension, for a stream and its
  transcript alike).
- Replaces the OAuth nonces of an mcp_authenticate answer (its `state` and `code_challenge`, also as query values of
  its `authUrl`) with URL-safe placeholders, REDACTED_STATE and REDACTED_CODE_CHALLENGE, so the link still parses.
- The claude.ai connector fixtures (mcp-connectors, mcp-connectors-all) keep only the `source: claudeai` servers and
  the tools of those, drop `env` and `headers` from every server config, rename every connector to
  "claude.ai Connector-N" wherever its name occurs (also the probe's own, $TANDEM_PROBE_CONNECTOR, which
  record-scenario.sh also sets from --connector; without it a warning goes to stderr), and give every
  `authUrl` and `config.url` a placeholder host and path with REDACTED_<KEY> query values, `config.id` and every
  `config.args` element a REDACTED_ placeholder.
- Keeps the `id` of a get_context_usage category (system_prompt, messages, ...: a protocol name), renames the entries
  of a get_usage answer's behaviors.<window> agents, skills, plugins and mcp_servers to agent-a, skill-a, plugin-a,
  server-a, ... per kind in order of first appearance (the agent types general-purpose and workflow-subagent stay), and
  ends a path before a backtick and before a trailing colon.
- Keeps every message type, field name and ordering, which is what the protocol tests care about.
- Also redacts the CLI's on-disk transcripts (<session id>.jsonl), which hold one JSON object per line as well.
  Pass a stream and its transcripts in one call: they share the placeholders, so an id reads the same in all of them.
  A line that is not a JSON object is dropped with a warning.
- Cuts transcript `attachment` lines down to their type: the payload (the CLI's system prompt, tool, skill and agent
  listings, an environment snapshot) and its `rendered` copy describe the recording machine, not the protocol.
  Every other field of the line stays. A `queued_command` attachment (a message the host sent mid-turn, folded into
  the running turn) keeps its payload, the host's own prompt and ids, which the other rules redact; only its
  `rendered` copy goes.
- Leaves values that already are placeholders (<name-N>) alone, so a second run over a redacted file changes nothing.
- Safety net: a UUID or API id that no rule above caught becomes <leaked-N>, with a warning naming its key path.
"""
import json
import os
import re
import sys
import urllib.parse

ID_KEYS = {
    "session_id", "uuid", "request_id", "parent_uuid", "tool_use_id", "parent_tool_use_id", "id",
    "task_id", "agent_id",
    "api_message_id", "summarizes_uuid", "user_message_uuid", "refused_user_message_uuid", "new_conversation_id",
    "command_uuid", "submitUuid",
    "source_uuid", "delivery_id",  # queued_command attachment
    # Transcript lines
    "parentUuid", "sessionId", "promptId", "leafUuid", "requestId", "toolUseID", "agentId", "sourceToolUseID",
    "sourceToolAssistantUUID", "serverClassifierRequest", "commandUuid", "deliveryId",
    "organizationUuid",  # credential_org attachment: the signed-in account's organization
}
ID_LIST_KEYS = {"user_message_uuids", "retracted_message_uuids", "supersedes",
                "still_queued", "cancelled",  # the interrupt receipt
                "mergedSendUuids"}  # a transcript's merged quiet sends (transcripts/bash-mode.jsonl:5)
KEPT_ATTACHMENTS = {"queued_command"}  # protocol, not a description of the recording machine
INIT_LISTS = {"mcp_servers", "plugins", "skills", "agents", "slash_commands", "terminal_slash_commands", "commands"}
# The INIT_LISTS a fixture keeps, by fixture name: what the MCP fixtures' tests read (mcp probes 3.6).
INIT_KEEP = {"mcp-status": {"mcp_servers"}, "mcp-tool-call": {"mcp_servers"},
             "mcp-slash": {"mcp_servers", "slash_commands", "terminal_slash_commands", "commands"},
             "mcp-connectors": {"mcp_servers"}, "mcp-connectors-all": {"mcp_servers"}}
CONNECTOR_FIXTURES = {"mcp-connectors", "mcp-connectors-all"}
CONNECTOR_VARIABLE = "TANDEM_PROBE_CONNECTOR"  # the probe's connector, as drive_cli.py reads it
# Values that name a connector or carry an OAuth nonce. filter_connectors makes each REDACTED_<KEY> (a URL's query values
# by their own key, its host connector-N.invalid); in every other fixture redact_nonces does so for NONCE_KEYS only.
URL_KEYS = ("code_challenge", "state", "config.id", "config.url", "authUrl")
NONCE_KEYS = URL_KEYS[:2]
REDACTED_RE = re.compile(r"REDACTED_[A-Z0-9_]+")
# A path ends before a quote or backtick and leaves a trailing colon out ("rule to `/home/u/CLAUDE.md`: when").
PATH_RE = re.compile(r"(?:/(?:root|home|Users|tmp|private|var|opt)|[A-Za-z]:\\\\)(?:[^\s\"'`]*[^\s\"'`:])?")
API_ID_RE = re.compile(r"^(?:toolu|srvtoolu|msg|req)_[A-Za-z0-9]+$")
EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
PLACEHOLDER_RE = re.compile(r"<[A-Za-z_]\w*-\d+>")
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
BASE64_KEYS = {"data", "base64"}
BASE64_MIN_LENGTH = 256
BASE64_RE = re.compile(r"[A-Za-z0-9+/=]+")

CATEGORY_ID_RE = re.compile(r"[a-z][a-z_]*")  # get_context_usage's categories[].id: a protocol name, not an identifier
# get_usage.behaviors.<window>: the lists whose entries name something on the recording machine, and the name each gets.
USAGE_SHARE_KINDS = {"agents": "agent", "skills": "skill", "plugins": "plugin", "mcp_servers": "server"}
USAGE_KEPT_NAMES = {"agents": {"general-purpose", "workflow-subagent"}}
USAGE_PLACEHOLDER_RE = re.compile(r"(?:agent|skill|plugin|server)-[a-z]{1,3}")

ids: dict[str, str] = {}
usage_names: dict[tuple[str, str], str] = {}  # (list, real name) -> "server-a", shared across a run's lines
leaked: dict[str, str] = {}
connectors: dict[str, str] = {}  # a connector's name -> "claude.ai Connector-N", shared by a stream and its transcripts
hosts: dict[str, str] = {}  # a connector URL's host -> "connector-N.invalid"


def stable_id(value: str, key: str) -> str:
    if PLACEHOLDER_RE.fullmatch(value) or REDACTED_RE.fullmatch(value):  # redacted before
        return value
    if value not in ids:
        ids[value] = f"<{key}-{len(ids) + 1}>"
    return ids[value]


def letters(n: int) -> str:
    """a, b, ... z, aa, ab, ... for n = 0, 1, ..."""
    out = ""
    n += 1
    while n:
        n, r = divmod(n - 1, 26)
        out = chr(ord("a") + r) + out
    return out


def usage_name(kind: str, name: str) -> str:
    if name in USAGE_KEPT_NAMES.get(kind, ()) or USAGE_PLACEHOLDER_RE.fullmatch(name):
        return name
    if (kind, name) not in usage_names:
        count = sum(1 for k, _ in usage_names if k == kind)
        usage_names[(kind, name)] = f"{USAGE_SHARE_KINDS[kind]}-{letters(count)}"
    return usage_names[(kind, name)]


def rename_usage_shares(payload: dict) -> None:
    """The `name`s of a get_usage answer's agents, skills, plugins and MCP servers, per window."""
    behaviors = payload.get("behaviors")
    for window in behaviors.values() if isinstance(behaviors, dict) else ():
        if not isinstance(window, dict):
            continue
        for kind in USAGE_SHARE_KINDS:
            for entry in window.get(kind) if isinstance(window.get(kind), list) else ():
                if isinstance(entry, dict) and isinstance(entry.get("name"), str):
                    entry["name"] = usage_name(kind, entry["name"])


def scrub_category(node):
    """A context category keeps its `id` when that is a protocol name such as system_prompt."""
    if isinstance(node, dict) and isinstance(node.get("id"), str) and CATEGORY_ID_RE.fullmatch(node["id"]):
        return {k: node[k] if k == "id" else scrub(v, k) for k, v in node.items()}
    return scrub(node, "categories")


def scrub(node, key=None):
    if isinstance(node, dict):
        # API ids can also appear as object keys (e.g. wire_tool_inputs).
        return {(stable_id(k, "id") if API_ID_RE.match(k) else k): scrub(v, k) for k, v in node.items()}
    if isinstance(node, list):
        if key == "categories":
            return [scrub_category(v) for v in node]
        return [scrub(v, key) for v in node]
    if isinstance(node, str):
        if (key in ID_KEYS or key in ID_LIST_KEYS) and node:
            return stable_id(node, key)
        if key == "gitBranch" and node:
            return "<branch>"
        if key == "entrypoint":
            return "<entrypoint>"
        if key in BASE64_KEYS and len(node) >= BASE64_MIN_LENGTH and BASE64_RE.fullmatch(node):
            return "<base64>"
        node = PATH_RE.sub("<path>", node)
        return EMAIL_RE.sub("<email>", node)
    return node


def leaked_id(value: str) -> str:
    if value not in leaked:  # its own name, so it can never collide with a <key-N> placeholder
        leaked[value] = f"<leaked-{len(leaked) + 1}>"
    return leaked[value]


def catch_leaks(node, path: str, where: str):
    """Replaces any UUID or API id still in a string value, warning once per value with its key path."""
    if isinstance(node, dict):
        return {k: catch_leaks(v, f"{path}.{k}" if path else k, where) for k, v in node.items()}
    if isinstance(node, list):
        return [catch_leaks(v, f"{path}[{i}]", where) for i, v in enumerate(node)]
    if not isinstance(node, str) or PLACEHOLDER_RE.fullmatch(node):
        return node
    clean = leaked_id(node) if API_ID_RE.fullmatch(node) else UUID_RE.sub(lambda m: leaked_id(m.group()), node)
    if clean != node:
        print(f"{where}: unredacted id at {path}, replaced", file=sys.stderr)
    return clean


def empty_lists(node: dict, keep: set[str] = frozenset()) -> None:
    for k in (INIT_LISTS - keep) & node.keys():
        node[k] = []


def redacted(key: str) -> str:
    """The URL-safe placeholder of `key`'s value: no < or >, so a link that holds it still parses."""
    return "REDACTED_" + re.sub(r"[^A-Za-z0-9]", "_", key).upper()


def connector_url(url: str) -> str:
    """A connector's link with every query value as REDACTED_<KEY>, the host as connector-N.invalid (N by first appearance)
    and the path as /redacted: a vendor's host or path names the connector. The scheme stays, so the link still passes
    browsableUrl."""
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname
    if host and not host.endswith(".invalid"):  # .invalid: redacted before
        hosts.setdefault(host, f"connector-{len(hosts) + 1}.invalid")
    query = urllib.parse.urlencode([(k, redacted(k)) for k, _ in urllib.parse.parse_qsl(parts.query, keep_blank_values=True)])
    return urllib.parse.urlunsplit((parts.scheme, hosts.get(host, host) if host else "", "/redacted", query, ""))


NONCE_QUERY_RE = re.compile(r"(?<=[?&])(" + "|".join(NONCE_KEYS) + r")=[^&#]*")


def redact_nonces(node) -> None:
    """Any fixture: an mcp_authenticate answer's OAuth nonces, as fields and as query values of its authUrl; the rest of
    the link stays as recorded."""
    if isinstance(node, list):
        for v in node:
            redact_nonces(v)
    if not isinstance(node, dict):
        return
    if isinstance(node.get("authUrl"), str):
        node["authUrl"] = NONCE_QUERY_RE.sub(lambda m: f"{m.group(1)}={redacted(m.group(1))}", node["authUrl"])
        for key in NONCE_KEYS:
            if isinstance(node.get(key), str):
                node[key] = redacted(key)
    for v in node.values():
        redact_nonces(v)


def is_connector(entry) -> bool:
    return isinstance(entry, dict) and entry.get("source") == "claudeai" and isinstance(entry.get("name"), str)


def add_connectors(node) -> None:
    """Numbers every connector (a `source: claudeai` server entry) in `node`, by first appearance."""
    if isinstance(node, list):
        for v in node:
            add_connectors(v)
    elif isinstance(node, dict):
        if is_connector(node):
            connectors.setdefault(node["name"], f"claude.ai Connector-{len(connectors) + 1}")
        for v in node.values():
            add_connectors(v)


def tool_form(name: str, collapse: bool) -> str:
    """`name` as the CLI writes it in a tool's mcp__<server>__ prefix: every character but letters, digits, _ and - as _
    ("claude.ai Slack" -> claude_ai_Slack), runs of _ collapsed too when `collapse`."""
    normal = re.sub(r"[^A-Za-z0-9_-]", "_", name)
    return re.sub(r"_+", "_", normal).strip("_") if collapse else normal


def renames() -> list[tuple[str, str]]:
    """Every connector's name, as is and in its tool forms, with its replacement; longest first, so no name is cut inside
    a longer one."""
    pairs = {}
    for name, new in connectors.items():
        pairs[name] = new
        for collapse in (False, True):
            pairs.setdefault(tool_form(name, collapse), tool_form(new, collapse))
    return sorted(pairs.items(), key=lambda pair: -len(pair[0]))


def renamer():
    """A function that renames every connector in a string in one pass (renames()), so a replacement is never matched
    again: a name that is a prefix of a placeholder ("claude.ai Con") leaves "claude.ai Connector-1" alone."""
    pairs = [(old, new) for old, new in renames() if old]
    if not pairs:
        return lambda text: text
    mapping = dict(pairs)
    pattern = re.compile("|".join(re.escape(old) for old, _ in pairs))  # longest first: the first match is the longest
    return lambda text: pattern.sub(lambda m: mapping[m.group(0)], text)


def rename(node, sub):
    if isinstance(node, dict):
        return {rename(k, sub): rename(v, sub) for k, v in node.items()}
    if isinstance(node, list):
        return [rename(v, sub) for v in node]
    if isinstance(node, str):
        return sub(node)
    return node


def filter_connectors(node) -> None:
    """mcp-connectors and mcp-connectors-all, every line of the stream and its transcript: only the claude.ai connectors
    stay, with no env, headers, args, id or vendor URL, and none under its real name."""
    if isinstance(node, list):
        for v in node:
            filter_connectors(v)
        return
    if not isinstance(node, dict):
        return
    for key in ("mcp_servers", "mcpServers"):
        if isinstance(node.get(key), list):
            node[key] = [entry for entry in node[key] if is_connector(entry)]
    if node.get("type") == "system" and node.get("subtype") == "init" and isinstance(node.get("tools"), list):
        prefixes = tuple(f"mcp__{form}__" for name in connectors
                         for form in (name, tool_form(name, False), tool_form(name, True)))
        node["tools"] = [tool for tool in node["tools"]
                         if not (isinstance(tool, str) and tool.startswith("mcp__")) or tool.startswith(prefixes)]
    config = node.get("config")
    if isinstance(config, dict):
        config.pop("env", None)
        config.pop("headers", None)
        if isinstance(config.get("url"), str):
            config["url"] = connector_url(config["url"])
        if isinstance(config.get("id"), str):
            config["id"] = redacted("config.id")
        if isinstance(config.get("args"), list):
            config["args"] = ["REDACTED_ARG"] * len(config["args"])
    if isinstance(node.get("authUrl"), str):
        node["authUrl"] = connector_url(node["authUrl"])
        for key in NONCE_KEYS:
            if isinstance(node.get(key), str):
                node[key] = redacted(key)
    for v in node.values():
        filter_connectors(v)


def strip_attachment(msg: dict) -> None:
    if "attachment" in msg:
        kind = msg["attachment"].get("type") if isinstance(msg["attachment"], dict) else None
        if kind not in KEPT_ATTACHMENTS:
            msg["attachment"] = {"type": kind if isinstance(kind, str) else "unknown"}
    if "rendered" in msg:
        msg["rendered"] = ""


def fixture_name(dst: str) -> str:
    return os.path.splitext(os.path.basename(dst))[0]


def redact(src: str, dst: str) -> None:
    fixture = fixture_name(dst)
    keep = INIT_KEEP.get(fixture, set())
    lines = []
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
            lines.append((number, msg))
    if fixture in CONNECTOR_FIXTURES:
        # Every connector is known before the first line is written, so a name that shows before its entry is renamed too.
        for _, msg in lines:
            add_connectors(msg)
        probe = os.environ.get(CONNECTOR_VARIABLE)
        if probe:
            connectors.setdefault(probe, f"claude.ai Connector-{len(connectors) + 1}")
    sub = renamer()
    out = []
    for number, msg in lines:
        if fixture in CONNECTOR_FIXTURES:
            filter_connectors(msg)
            msg = rename(msg, sub)
        else:
            redact_nonces(msg)
        if msg.get("type") == "system" and msg.get("subtype") == "init":
            empty_lists(msg, keep)
        if msg.get("type") == "attachment":
            strip_attachment(msg)
        if msg.get("type") == "control_response":
            payload = msg.get("response", {}).get("response")
            if isinstance(payload, dict):
                rename_usage_shares(payload)
            if isinstance(payload, dict) and "commands" in payload:  # initialize response
                empty_lists(payload, keep)
                if isinstance(payload.get("account"), dict):
                    payload["account"] = {k: "<account>" for k in payload["account"]}
                if "pid" in payload:
                    payload["pid"] = 0
        clean = catch_leaks(scrub(msg), "", f"{src} line {number}")
        out.append(json.dumps(clean, ensure_ascii=False, separators=(",", ":")))
    with open(dst, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


def main(paths: list[str]) -> None:
    """Redacts each <in> <out> pair of `paths`, in one run."""
    if not os.environ.get(CONNECTOR_VARIABLE) and any(fixture_name(dst) in CONNECTOR_FIXTURES for dst in paths[1::2]):
        print(f"warning: {CONNECTOR_VARIABLE} is not set, so the probe's connector is renamed only where a source: claudeai "
              "entry names it; read the output for its real name before committing it", file=sys.stderr)
    for i in range(0, len(paths), 2):
        redact(paths[i], paths[i + 1])


if __name__ == "__main__":
    if len(sys.argv) < 3 or len(sys.argv) % 2 == 0:
        sys.exit(__doc__)
    main(sys.argv[1:])

"""Tests for redact_fixture.py. Run from scripts/: python3 -m unittest"""
import contextlib
import importlib
import io
import json
import os
import tempfile
import unittest
import urllib.parse
from unittest import mock

import redact_fixture


def init(**lists) -> dict:
    return {"type": "system", "subtype": "init", "session_id": "s", "tools": ["Bash"], **lists}


def status(*servers: dict) -> dict:
    """An mcp_status answer listing `servers`."""
    return {"type": "control_response",
            "response": {"subtype": "success", "request_id": "r", "response": {"mcpServers": list(servers)}}}


def connector(name: str, **fields) -> dict:
    return {"name": name, "status": "needs-auth", "scope": "claudeai", "source": "claudeai",
            "config": {"type": "claudeai-proxy", "url": "https://mcp.example-vendor.com/v1/sse", "id": "srv_0123"}, **fields}


def echo() -> dict:
    return {"name": "echo", "status": "connected", "scope": "user", "source": "user",
            "config": {"type": "stdio", "command": "/usr/bin/python3", "args": ["/home/someone/echo.py"],
                       "env": {"TOKEN": "secret"}}}


class RedactTest(unittest.TestCase):
    def setUp(self):
        importlib.reload(redact_fixture)  # the placeholders are module state, numbered per run
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def redact(self, *lines, name: str = "out") -> tuple[list[dict], str]:
        """Redacts `lines` (dicts, or raw strings) into the fixture `name` (a path under the output folder, without its
        extension; a name under transcripts/ is a .jsonl twin) and returns the output lines and what went to stderr."""
        src = os.path.join(self.dir.name, "in.ndjson")
        dst = os.path.join(self.dir.name, name + (".jsonl" if name.startswith("transcripts/") else ".ndjson"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(src, "w", encoding="utf-8") as f:
            for line in lines:
                f.write((line if isinstance(line, str) else json.dumps(line)) + "\n")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            redact_fixture.redact(src, dst)
        with open(dst, encoding="utf-8") as f:
            return [json.loads(line) for line in f if line.strip()], err.getvalue()

    def test_a_uuid_under_a_key_no_rule_knows_is_caught_and_named(self):
        uuid = "0b0e5c1a-3f2d-4c6b-9a8e-7d6c5b4a3f2e"
        (out,), err = self.redact({"type": "system", "subtype": "x", "nested": {"note": f"see {uuid} here"}})
        self.assertEqual("see <leaked-1> here", out["nested"]["note"])
        self.assertIn("unredacted id at nested.note", err)
        self.assertNotIn(uuid, json.dumps(out))

    def test_an_api_id_under_a_key_no_rule_knows_is_caught(self):
        (out,), err = self.redact({"type": "assistant", "items": [{"ref": "toolu_01AbCdEf"}]})
        self.assertEqual("<leaked-1>", out["items"][0]["ref"])
        self.assertIn("unredacted id at items[0].ref", err)

    def test_the_same_leaked_value_gets_the_same_placeholder_everywhere(self):
        uuid = "11111111-2222-4333-8444-555555555555"
        out, _ = self.redact({"a": uuid}, {"b": [uuid], "c": "11111111-2222-4333-8444-666666666666"})
        self.assertEqual("<leaked-1>", out[0]["a"])
        self.assertEqual(["<leaked-1>"], out[1]["b"])
        self.assertEqual("<leaked-2>", out[1]["c"])

    def test_ids_the_rules_know_are_not_reported_as_leaks(self):
        (out,), err = self.redact({"type": "user", "uuid": "11111111-2222-4333-8444-555555555555",
                                   "session_id": "99999999-2222-4333-8444-555555555555"})
        self.assertEqual("<uuid-1>", out["uuid"])
        self.assertEqual("<session_id-2>", out["session_id"])
        self.assertEqual("", err)

    def test_a_second_run_over_redacted_output_changes_nothing_and_reports_nothing(self):
        first, _ = self.redact({"uuid": "11111111-2222-4333-8444-555555555555", "x": "toolu_01Ab",
                                "path": "/home/someone/project"})
        second, err = self.redact(*first)
        self.assertEqual(first, second)
        self.assertEqual("", err)

    def test_task_and_agent_ids_get_placeholders_correlated_across_lines(self):
        out, err = self.redact(
            {"type": "system", "subtype": "task_started", "task_id": "a1b2c3d4e5f6a7b8c", "tool_use_id": "toolu_01Spawn"},
            {"type": "user", "parent_tool_use_id": "toolu_01Spawn", "agent_id": "a1b2c3d4e5f6a7b8c"},
            {"type": "control_request", "request": {"tool_use_id": "toolu_01Inner", "agent_id": "a1b2c3d4e5f6a7b8c"}})
        self.assertEqual("<task_id-1>", out[0]["task_id"])
        self.assertEqual("<tool_use_id-2>", out[0]["tool_use_id"])
        self.assertEqual("<tool_use_id-2>", out[1]["parent_tool_use_id"])  # the same value as the tool_use it points at
        self.assertEqual("<task_id-1>", out[1]["agent_id"])  # the same value as the task
        self.assertEqual("<task_id-1>", out[2]["request"]["agent_id"])
        self.assertEqual("<tool_use_id-3>", out[2]["request"]["tool_use_id"])
        self.assertNotIn("a1b2c3d4e5f6a7b8c", json.dumps(out))
        self.assertEqual("", err)

    def test_long_base64_payloads_are_replaced(self):
        payload = "iVBORw0KGgo=" * 30  # 360 base64 characters
        (out,), err = self.redact({
            "type": "user",
            "message": {"content": [{"type": "tool_result", "content": [
                {"type": "image", "source": {"type": "base64", "data": payload, "media_type": "image/png"}}]}]},
            "tool_use_result": {"type": "image", "file": {"base64": payload, "type": "image/png", "originalSize": 80}}})
        self.assertEqual("<base64>", out["message"]["content"][0]["content"][0]["source"]["data"])
        self.assertEqual("<base64>", out["tool_use_result"]["file"]["base64"])
        self.assertEqual("image/png", out["message"]["content"][0]["content"][0]["source"]["media_type"])
        self.assertEqual(80, out["tool_use_result"]["file"]["originalSize"])
        self.assertNotIn(payload, json.dumps(out))
        self.assertEqual("", err)

    def test_short_or_non_base64_data_strings_are_untouched(self):
        long_but_not_base64 = "a few words " * 30  # spaces: not a base64 payload
        long_under_another_key = "QUJD" * 100
        (out,), err = self.redact({"data": "hello", "base64": "QUJD", "note": {"data": long_but_not_base64},
                                   "blob": long_under_another_key, "n": 1})
        self.assertEqual("hello", out["data"])
        self.assertEqual("QUJD", out["base64"])
        self.assertEqual(long_but_not_base64, out["note"]["data"])
        self.assertEqual(long_under_another_key, out["blob"])
        self.assertEqual("", err)

    def test_a_second_run_over_ids_and_base64_is_byte_identical(self):
        src = os.path.join(self.dir.name, "sample.ndjson")
        first = os.path.join(self.dir.name, "first.ndjson")
        second = os.path.join(self.dir.name, "second.ndjson")
        with open(src, "w", encoding="utf-8") as f:
            for line in (
                {"type": "system", "subtype": "task_started", "task_id": "a1b2c3d4e5f6a7b8c", "tool_use_id": "toolu_01Spawn",
                 "uuid": "11111111-2222-4333-8444-555555555555"},
                {"type": "user", "parent_tool_use_id": "toolu_01Spawn", "agent_id": "a1b2c3d4e5f6a7b8c",
                 "message": {"content": [{"type": "image", "source": {"type": "base64", "data": "QUJD" * 100}}]},
                 "tool_use_result": {"file": {"base64": "QUJD" * 100}}, "cwd": "/home/someone/project"},
            ):
                f.write(json.dumps(line) + "\n")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            redact_fixture.redact(src, first)
            importlib.reload(redact_fixture)  # a fresh run, as a later recording session would be
            redact_fixture.redact(first, second)
        with open(first, "rb") as a, open(second, "rb") as b:
            self.assertEqual(a.read(), b.read())
        self.assertEqual("", err.getvalue())
        with open(second, encoding="utf-8") as f:
            text = f.read()
        self.assertIn('"<base64>"', text)
        self.assertIn('"<task_id-1>"', text)

    def test_a_line_that_is_not_a_json_object_is_dropped_with_a_warning(self):
        out, err = self.redact("[1, 2]", "not json", {"type": "kept"})
        self.assertEqual([{"type": "kept"}], out)
        self.assertIn("line 1: not a JSON object, dropped", err)
        self.assertIn("line 2: not a JSON object, dropped", err)


    def test_other_fixtures_still_empty_the_init_lists(self):
        (out,), _ = self.redact(init(mcp_servers=[{"name": "echo", "status": "connected", "source": "user"}],
                                     slash_commands=["review"], plugins=[{"name": "p"}]), name="permission")
        self.assertEqual([], out["mcp_servers"])
        self.assertEqual([], out["slash_commands"])
        self.assertEqual([], out["plugins"])

    def test_the_mcp_fixtures_keep_their_init_lists(self):
        servers = [{"name": "echo", "status": "connected", "source": "dynamic"}]
        initialize = {"type": "control_response", "response": {"subtype": "success", "request_id": "r", "response": {
            "commands": [{"name": "mcp", "description": "Manage MCP servers"}], "agents": [{"name": "a"}]}}}
        for name in ("mcp-status", "mcp-tool-call"):
            importlib.reload(redact_fixture)
            (out, answer), _ = self.redact(init(mcp_servers=servers, slash_commands=["mcp"]), initialize, name=name)
            self.assertEqual(servers, out["mcp_servers"], name)
            self.assertEqual([], out["slash_commands"], name)
            self.assertEqual([], answer["response"]["response"]["commands"], name)
        for name in ("mcp-slash", "transcripts/mcp-slash"):
            importlib.reload(redact_fixture)
            (out, answer), _ = self.redact(
                init(mcp_servers=servers, slash_commands=["mcp"], terminal_slash_commands=["exit"], plugins=[{"name": "p"}]),
                initialize, name=name)
            self.assertEqual(servers, out["mcp_servers"], name)
            self.assertEqual(["mcp"], out["slash_commands"], name)
            self.assertEqual(["exit"], out["terminal_slash_commands"], name)
            self.assertEqual([], out["plugins"], name)
            self.assertEqual([{"name": "mcp", "description": "Manage MCP servers"}], answer["response"]["response"]["commands"])
            self.assertEqual([], answer["response"]["response"]["agents"])

    def test_submit_uuid_gets_an_id_placeholder(self):
        (out,), err = self.redact({"type": "system", "subtype": "local_command", "content": "/help",
                                   "uuid": "11111111-2222-4333-8444-555555555555",
                                   "submitUuid": "99999999-2222-4333-8444-555555555555"})
        self.assertEqual("<submitUuid-2>", out["submitUuid"])
        self.assertNotIn("leaked", json.dumps(out))
        self.assertEqual("", err)

    def test_connector_fixtures_keep_only_claudeai_entries(self):
        for name in ("mcp-connectors", "mcp-connectors-all"):
            importlib.reload(redact_fixture)
            secret = connector("claude.ai Example")
            secret["config"].update(env={"TOKEN": "secret"}, headers={"Authorization": "Bearer secret"})
            (first, answer), _ = self.redact(
                init(tools=["Bash", "mcp__echo__echo", "mcp__claude_ai_Example__search"],
                     mcp_servers=[{"name": "echo", "status": "connected", "source": "user"},
                                  {"name": "claude.ai Example", "status": "needs-auth", "source": "claudeai"}]),
                status(echo(), secret), name=name)
            self.assertEqual([{"name": "claude.ai Connector-1", "status": "needs-auth", "source": "claudeai"}],
                             first["mcp_servers"], name)
            self.assertEqual(["Bash", "mcp__claude_ai_Connector-1__search"], first["tools"], name)
            (entry,) = answer["response"]["response"]["mcpServers"]
            self.assertEqual("claude.ai Connector-1", entry["name"])
            self.assertNotIn("env", entry["config"])
            self.assertNotIn("headers", entry["config"])
            self.assertNotIn("secret", json.dumps([first, answer]))

    def test_connectors_are_renamed_everywhere_including_host_frames_and_the_twin(self):
        stream = [
            status(connector("claude.ai Example"), connector("claude.ai Other")),
            {"type": "control_request", "request_id": "host-3",
             "request": {"subtype": "mcp_toggle", "serverName": "claude.ai Example", "enabled": False}},
            {"type": "control_response",
             "response": {"subtype": "error", "request_id": "host-3", "error": "claude.ai Example"}},
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "id": "toolu_01A", "name": "mcp__claude_ai_Example__search", "input": {}}]}},
        ]
        twin = [{"type": "assistant", "message": {"content": [
            {"type": "tool_use", "id": "toolu_01A", "name": "mcp__claude_ai_Example__search", "input": {}},
            {"type": "tool_use", "id": "toolu_01B", "name": "mcp__claude_ai_Other__list", "input": {}}]}}]
        out, _ = self.redact(*stream, name="mcp-connectors")
        renamed, _ = self.redact(*twin, name="transcripts/mcp-connectors")  # the same run: one map for both files

        servers = out[0]["response"]["response"]["mcpServers"]
        self.assertEqual(["claude.ai Connector-1", "claude.ai Connector-2"], [s["name"] for s in servers])
        self.assertEqual("claude.ai Connector-1", out[1]["request"]["serverName"])
        self.assertEqual("claude.ai Connector-1", out[2]["response"]["error"])
        self.assertEqual("mcp__claude_ai_Connector-1__search", out[3]["message"]["content"][0]["name"])
        self.assertEqual(["mcp__claude_ai_Connector-1__search", "mcp__claude_ai_Connector-2__list"],
                         [block["name"] for block in renamed[0]["message"]["content"]])
        for text in (json.dumps(out), json.dumps(renamed)):
            self.assertNotIn("Example", text)
            self.assertNotIn("Other", text)

    def test_the_probe_connector_from_the_environment_is_renamed_even_outside_a_claudeai_entry(self):
        with mock.patch.dict(os.environ, {"TANDEM_PROBE_CONNECTOR": "claude.ai Example"}):
            (out,), _ = self.redact({"type": "control_response", "response": {
                "subtype": "error", "request_id": "r", "error": "Server not found: claude.ai Example"}},
                name="mcp-connectors")
        self.assertEqual("Server not found: claude.ai Connector-1", out["response"]["error"])

    def test_oauth_values_get_url_safe_placeholders(self):
        auth = {"type": "control_response", "response": {"subtype": "success", "request_id": "r", "response": {
            "authUrl": "https://auth.example-vendor.com/oauth/authorize?response_type=code&code_challenge=abc123"
                       "&state=st4te&redirect_uri=http%3A%2F%2Flocalhost%3A53713%2Fcallback",
            "state": "st4te", "requiresUserAction": True}}}
        (out,), err = self.redact(auth, name="mcp-connectors")
        payload = out["response"]["response"]
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(payload["authUrl"]).query)
        self.assertEqual(["REDACTED_CODE_CHALLENGE"], query["code_challenge"])
        self.assertEqual(["REDACTED_STATE"], query["state"])
        self.assertEqual("REDACTED_STATE", payload["state"])
        self.assertNotIn("<", payload["authUrl"])
        self.assertNotIn("abc123", json.dumps(out))
        self.assertNotIn("st4te", json.dumps(out))
        self.assertEqual("", err)

        # Any fixture: the OAuth nonces get the same placeholders, and the rest of the link stays (mcp-status:40).
        importlib.reload(redact_fixture)
        auth["response"]["response"]["authUrl"] = "http://127.0.0.1:33041/authorize?code_challenge=abc123&state=st4te&scope=mcp"
        (out,), err = self.redact(auth, name="mcp-status")
        self.assertEqual("http://127.0.0.1:33041/authorize?code_challenge=REDACTED_CODE_CHALLENGE&state=REDACTED_STATE&scope=mcp",
                         out["response"]["response"]["authUrl"])
        self.assertEqual("REDACTED_STATE", out["response"]["response"]["state"])
        self.assertEqual("", err)

    def test_connector_urls_get_a_placeholder_host(self):
        auth = {"type": "control_response", "response": {"subtype": "success", "request_id": "r", "response": {
            "authUrl": "https://auth.example-vendor.com/oauth/authorize?client_id=vendor-app&state=s1"}}}
        (answer, link), err = self.redact(status(connector("claude.ai Example")), auth, name="mcp-connectors")
        config = answer["response"]["response"]["mcpServers"][0]["config"]
        self.assertEqual("https://connector-1.invalid/redacted", config["url"])
        self.assertEqual("REDACTED_CONFIG_ID", config["id"])
        auth_url = link["response"]["response"]["authUrl"]
        self.assertEqual("https://connector-2.invalid/redacted?client_id=REDACTED_CLIENT_ID&state=REDACTED_STATE", auth_url)
        for url in (config["url"], auth_url):
            parts = urllib.parse.urlsplit(url)
            self.assertIn(parts.scheme, ("http", "https"))
            self.assertTrue(parts.hostname)
        self.assertNotIn("vendor", json.dumps([answer, link]))
        self.assertEqual("", err)

    def test_config_args_are_redacted(self):
        (out,), _ = self.redact(status(connector("claude.ai Example", config={
            "type": "claudeai-proxy", "args": ["-y", "/home/x/token=abc"]})), name="mcp-connectors")
        self.assertEqual(["REDACTED_ARG", "REDACTED_ARG"], out["response"]["response"]["mcpServers"][0]["config"]["args"])

    def test_a_second_run_over_a_connector_fixture_changes_nothing(self):
        auth = {"type": "control_response", "response": {"subtype": "success", "request_id": "r", "response": {
            "authUrl": "https://auth.example-vendor.com/authorize?state=s1", "state": "s1"}}}
        first, _ = self.redact(status(connector("claude.ai Example"), echo()), auth, name="mcp-connectors")
        importlib.reload(redact_fixture)
        second, err = self.redact(*first, name="mcp-connectors")
        self.assertEqual(first, second)
        self.assertEqual("", err)


if __name__ == "__main__":
    unittest.main()

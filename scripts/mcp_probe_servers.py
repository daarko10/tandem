#!/usr/bin/env python3
"""The local MCP servers that drive_cli.py's MCP scenarios (mcp-status, mcp-tool-call, mcp-slash) record against, as
docs/protocol.md section 10 describes them. Standard library only, so the nightly contract run needs nothing installed.

Usage:
  mcp_probe_servers.py echo
      A stdio MCP server (newline-delimited JSON-RPC) with one tool, `echo(text)`, which returns the text as a text block
      and, under its output schema, as structuredContent {"result": text}.
  mcp_probe_servers.py authy <port-file> [<log-file>]
      An HTTP MCP server on 127.0.0.1 that needs a sign-in it never grants: every MCP request is answered 401 with a
      WWW-Authenticate header naming its RFC 9728 protected-resource metadata, and the OAuth metadata and dynamic client
      registration are served, so the CLI can build an authorization link; no token is ever issued. It binds an ephemeral
      port and writes it to <port-file>; <log-file>, if given, gets one line per request (method and path, no bodies).

The third server, `broken`, needs no script: it is /bin/false, which exits at once.
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ECHO_TOOL = {
    "name": "echo",
    "description": "Returns the given text unchanged.",
    "inputSchema": {"type": "object", "properties": {"text": {"type": "string", "title": "Text"}}, "required": ["text"]},
    "outputSchema": {"type": "object", "properties": {"result": {"type": "string", "title": "Result"}},
                     "required": ["result"]},
}


def echo_answer(request: dict) -> dict | None:
    """The JSON-RPC answer of the echo server to `request`; None for a notification, which gets none."""
    if "id" not in request:
        return None
    method, params = request.get("method"), request.get("params") or {}
    if method == "initialize":
        result = {"protocolVersion": params.get("protocolVersion", "2025-06-18"),
                  "capabilities": {"tools": {"listChanged": False}},
                  "serverInfo": {"name": "echo", "version": "0.1.0"}}
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": [ECHO_TOOL]}
    elif method == "tools/call" and params.get("name") == "echo":
        text = str((params.get("arguments") or {}).get("text", ""))
        result = {"content": [{"type": "text", "text": text}], "structuredContent": {"result": text}, "isError": False}
    else:
        return {"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32601, "message": f"Method not found: {method}"}}
    return {"jsonrpc": "2.0", "id": request["id"], "result": result}


def serve_echo(stdin=sys.stdin, stdout=sys.stdout) -> None:
    for line in stdin:
        if not line.strip():
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            continue
        answer = echo_answer(request) if isinstance(request, dict) else None
        if answer is not None:
            stdout.write(json.dumps(answer) + "\n")
            stdout.flush()


class AuthyHandler(BaseHTTPRequestHandler):
    log_file: str | None = None

    def log_message(self, fmt, *args):  # noqa: N802 (BaseHTTPRequestHandler's name)
        if self.log_file:
            with open(self.log_file, "a", encoding="utf-8") as f:
                f.write(f"{self.command} {self.path.split('?')[0]}\n")

    def base(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def send_json(self, code: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def route(self) -> None:
        path, base = self.path.split("?")[0], self.base()
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        if path.startswith("/.well-known/oauth-protected-resource"):
            self.send_json(200, {"resource": f"{base}/mcp", "authorization_servers": [base],
                                 "bearer_methods_supported": ["header"], "scopes_supported": ["mcp"]})
        elif path.startswith(("/.well-known/oauth-authorization-server", "/.well-known/openid-configuration")):
            self.send_json(200, {"issuer": base, "authorization_endpoint": f"{base}/authorize",
                                 "token_endpoint": f"{base}/token", "registration_endpoint": f"{base}/register",
                                 "response_types_supported": ["code"],
                                 "grant_types_supported": ["authorization_code", "refresh_token"],
                                 "code_challenge_methods_supported": ["S256"],
                                 "token_endpoint_auth_methods_supported": ["none"], "scopes_supported": ["mcp"]})
        elif path == "/register":
            try:
                request = json.loads(body or b"{}")
            except json.JSONDecodeError:
                request = {}
            self.send_json(201, {"client_id": "tandem-probe-client", "redirect_uris": request.get("redirect_uris", []),
                                 "token_endpoint_auth_method": "none",
                                 "grant_types": ["authorization_code", "refresh_token"], "response_types": ["code"],
                                 "client_name": request.get("client_name", "probe")})
        elif path == "/authorize":
            data = b"tandem probe: the authorization page never redirects back\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        elif path == "/token":
            self.send_json(400, {"error": "invalid_grant"})
        else:
            self.send_response(401)
            self.send_header("WWW-Authenticate",
                             f'Bearer realm="tandem-probe", resource_metadata="{base}/.well-known/oauth-protected-resource"')
            self.send_header("Content-Length", "0")
            self.end_headers()

    do_GET = do_POST = do_DELETE = do_PUT = route


def serve_authy(port_file: str, log_file: str | None = None) -> None:
    AuthyHandler.log_file = log_file
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), AuthyHandler)
    partial = port_file + ".partial"  # renamed into place, so a reader never sees half a port
    with open(partial, "w", encoding="utf-8") as f:
        f.write(str(httpd.server_address[1]))
    os.replace(partial, port_file)
    httpd.serve_forever()


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "echo":
        serve_echo()
    elif len(sys.argv) in (3, 4) and sys.argv[1] == "authy":
        serve_authy(*sys.argv[2:])
    else:
        sys.exit(__doc__)

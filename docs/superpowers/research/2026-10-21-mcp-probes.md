# MCP, connector and slash-command probes against claude 2.1.306 (wire shapes for the /mcp panel and the command palette)

Status: research result, 2026-10-21. Companion to `2026-10-18-protocol-probes.md` (same method, same citation rules) and to `docs/protocol.md` 4.4, 4.5, 5.1, 5.2. Trigger: the owner's request to control connectors and MCP from the IDE "like the interactive stuff on the CLI", and the two screenshots `assets/owner-2026-10-21/tandem-03-slash-mcp-reply.png` (a typed `/mcp` comes back as the one-line text `41 MCP server(s): 15 connected, 25 not connected, 1 disabled. Use /mcp in the terminal for details.`, "Worked for 0s") and `tandem-04-connectors-need-auth.png` (the model says `claude.ai Connector A` and `claude.ai Connector B` need authorization and that it "can't run the OAuth flow in this session").

Audience: the engineer writing the spec for the MCP panel, the connectors list and the slash-command palette, and the reducer code.

How to read this file:

- Every claim marked **recorded** cites `fixture:line` in `src/test/resources/fixtures/` (`.ndjson` is the CLI's stdout; `transcripts/<name>.jsonl` is the CLI's own on-disk transcript of the same session). Every cited fixture file and line was opened and checked on 2026-10-21 while writing this.
- Every claim marked **docs only** rests on public material: `https://code.claude.com/docs/en/mcp`, `.../agent-sdk/mcp`, `.../agent-sdk/slash-commands`, `.../commands`, `.../remote-control`, `.../desktop`, the public npm package `@anthropic-ai/claude-agent-sdk` 0.3.306 (`sdk.d.ts`, `sdk.mjs`, `bridge.mjs`; line numbers are those of the research input and were not re-checked here) and GitHub issues. Nothing was read from `~/.claude/.credentials*`, keychains or keys.
- All three probes ran `claude` 2.1.306 (`/opt/node22/bin/claude`) on `claude-fable-5-1` with the `scripts/drive_cli.py` argv **minus `--safe-mode`** (safe mode disables MCP servers) plus `--mcp-config <cwd>/mcp.json --strict-mcp-config --setting-sources project,local`. Servers in `mcp.json`: `echo` (python stdio, one tool `echo` with an output schema), `broken` (`/bin/false`), `authy` (local HTTP, always 401 with `WWW-Authenticate: Bearer resource_metadata=...`, serves RFC 9728/8414 metadata and dynamic client registration, never issues a token). Without `--setting-sources project,local` the sandbox's user-enabled plugins added two `system/hook_started` + `system/hook_response` lines (`hook_name:"SessionStart:startup"`) at startup and 38 more slash commands; those first attempts are kept under `scratch/probes/mcp-*/raw.ndjson` (gitignored) and are not fixtures.
- Host-to-CLI frames are **not** in the fixtures (stdout only); the bodies sent are quoted verbatim in section 2 from `scratch/probes/mcp-*/r2/sent.ndjson`.
- Placeholders `<id-N>`, `<uuid-N>`, `<session_id-N>`, `<request_id-N>`, `<command_uuid-N>`, `<path>` are the redactor's; `<code_challenge>` and `<state>` in `mcp-status:40` were replaced by hand (per-run OAuth nonces). Key order inside JSON examples is the CLI's; examples are trimmed with `...`.
- **No claude.ai connector was observable**: the sandbox CLI has none configured (this session's connectors belong to the hosted environment, not to the CLI), so everything about `source:"claudeai"` servers is docs only (2.5).
- Time matters here: do not spend time that can be avoided, and the earlier a correct result is obtained, the better.

---

## 1. Fixtures recorded

3 fixtures, each with a matching `transcripts/<name>.jsonl`. Paths are relative to `src/test/resources/fixtures/`. All were produced by `scripts/redact_fixture.py` through a scratchpad wrapper that **keeps** `init.mcp_servers` (all three) and `slash_commands`, `terminal_slash_commands` and the initialize response's `commands` (`mcp-slash` only); the committed `INIT_LISTS` (`scripts/redact_fixture.py:40`) would empty them (3.6).

| Name | Host frames (not in the file) | What it shows | Key lines |
|---|---|---|---|
| `mcp-status` | `initialize`; `mcp_status`; turn one; `mcp_status`; `mcp_toggle echo false`; `mcp_status`; turn two; `mcp_toggle echo true`; `mcp_status`; `mcp_reconnect broken`; `mcp_reconnect authy`; `mcp_toggle nosuch`; `mcp_status`; `mcp_authenticate authy`; `mcp_status`; `mcp_set_servers {echo2}`; `mcp_status`; `mcp_set_servers {}`; `mcp_status`; turn three | The full `mcp_status` shape, `pending` before the first turn, `init.mcp_servers` as the thin list, disable/enable round trip with the tool leaving `init.tools`, the three error strings, the `mcp_authenticate` payload (`authUrl`, `callbackPort`, ...), add/remove via `mcp_set_servers` | 1 initialize response (`commands` emptied); 2 status before the first turn; 6 init; 18 status after a turn; 19 toggle success (no `response`); 20 `disabled`; 23 init without `mcp__echo__echo`; 34/35 enable; 36/37/38 errors; 40 authenticate; 41 still `needs-auth`; 42/43 add `echo2`; 44/45 remove; 48 init; 57 result. Transcript: 39 lines, nothing MCP-related (user 4/28/34, assistant 21/31/37, `ai-title` 1, `cost-state` 39) |
| `mcp-tool-call` | `initialize`; prompt asking for `mcp__echo__echo` with `hello tandem` (answered allow); prompt asking again with `second` (answered deny `The user denied this call.`) | MCP tools are deferred (`ToolSearch` first); `tool_use_meta` on the assistant line; `can_use_tool` with `mcp_server`, `display_name`, `server_policy_ask_only`; the result's `_meta`/`structuredContent`; a deny | 5 init; 14 text; 22 `ToolSearch`; 24 `tool_reference`; 34 tool_use + `tool_use_meta`; 35 request; 39 result; 44 text; 48 result; 52 init; 59/60 second call; 62 `is_error`; 69 `denied`; 73 result with `permission_denials`. Transcript 22/23, 31/32, 40/41, 44 |
| `mcp-slash` | `initialize`; the user texts `/mcp`, `/model`, `/help`, `/hello` (cwd has `.claude/commands/hello.md`) | The initialize `commands` catalogue (73 entries), `init.slash_commands` and `terminal_slash_commands` with real values, three local commands answered without an API call, one project command that runs a model turn | 1 initialize response with `commands`; 4 init; 5/6 `/mcp`; 11/12 `/model`; 17/18 `/help` (`unavailable_headless`); 22 init; 29 text; 34 result. Transcript 3/4/5 (`/mcp`), 9/10 (`/model`), 13/14 (`/help`), 17/18 (`/hello`), 34 |

Non-fixture probe `scratch/probes/mcp-list/r1/raw.ndjson` (default setting sources, no `--strict-mcp-config`, `initialize` + two `mcp_status`): the three dynamic servers plus the sandbox's user server `context7` (`scope:"user", source:"user"`) and the plugin server `plugin:pdf-viewer:pdf` (`source:"plugin"` but `scope:"dynamic"`), with a richer `serverInfo` (`title`, `icons[{src,mimeType,sizes}]`, `websiteUrl`, `description`) and tools with `annotations:{readOnly:true, openWorld:true}` and `_meta:{ui:{visibility:["app"]}}` / `_meta.ui.resourceUri`. Not committed (raw paths and ids).

No fixture contains `system/notification`, `system/informational`, `system/commands_changed`, `system/local_command_output`, `system/elicitation_complete`, a `control_request elicitation`, `system/status` for the local commands, or a replayed user line (grep over the three files: 0 each).

---

## 2. Answers

### 2.1 Listing servers with status: `init.mcp_servers` versus `mcp_status`

**Recorded.** `system/init` carries only the thin list `[{name, status, source}]` (`mcp-status:6`):

```json
{"type":"system","subtype":"init","cwd":"<path>","session_id":"<session_id-5>","tools":["Task",...,"ListMcpResourcesTool",...,"ReadMcpResourceDirTool","ReadMcpResourceTool",...,"Write","mcp__echo__echo"],
 "mcp_servers":[{"name":"echo","status":"connected","source":"dynamic"},{"name":"broken","status":"failed","source":"dynamic"},{"name":"authy","status":"needs-auth","source":"dynamic"}],
 "model":"claude-fable-5-1","permissionMode":"default","slash_commands":[],"terminal_slash_commands":[],"apiKeySource":"none","claude_code_version":"2.1.306",...,
 "capabilities":["interrupt_receipt_v1",...,"sdk_mcp_tools_list_changed","sdk_mcp_manifests","mcp_read_resource_v1","mcp_tool_ui_meta_v1","ui_surface_v1","mcp_call_result_not_for_model_v1"],...}
```

No error text, config, tools or `serverInfo` there. The detail comes from the `mcp_status` control request. Host frame: `{"type":"control_request","request_id":"host-4","request":{"subtype":"mcp_status"}}`. Answer after the first turn (`mcp-status:18`):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-22>","response":{"mcpServers":[
 {"name":"echo","status":"connected","serverInfo":{"name":"echo","version":"0.1.0"},"config":{"type":"stdio","command":"/usr/bin/python3","args":["-I","<path>"]},"scope":"dynamic","source":"dynamic","tools":[{"name":"echo","annotations":{}}]},
 {"name":"broken","status":"failed","error":"Connection closed","config":{"type":"stdio","command":"/bin/false","args":[]},"scope":"dynamic","source":"dynamic"},
 {"name":"authy","status":"needs-auth","config":{"type":"http","url":"http://127.0.0.1:33041/mcp"},"scope":"dynamic","source":"dynamic"}]}}}
```

Per entry: `name`, `status`, `config` (`{type:"stdio", command, args}` or `{type:"http", url}`), `scope`, `source` always; `serverInfo{name, version}` and `tools[{name, annotations}]` only when `connected`; `error` only when `failed`. A `needs-auth` entry has neither `error` nor `tools`. The tool's description from the server is **not** forwarded (`tools[]` has `name` and an empty `annotations` only); the `mcp-list` probe shows `annotations` filled (`readOnly`, `openWorld`) and an optional `_meta` when the server sets them. `--mcp-config` servers report `scope:"dynamic"` and `source:"dynamic"`; a plugin server reports `source:"plugin"` with `scope:"dynamic"`, a user-scope server `source:"user"`/`scope:"user"` (non-fixture `mcp-list/r1/raw.ndjson:6-7`). This matches the SDK's `McpServerStatus` (`sdk.d.ts` 1251-1316: status `connected|failed|needs-auth|pending|disabled`, optional `serverInfo`, `error`, `config`, `scope`, `source`, `tools[].description?/annotations?/_meta?`).

**Timing (recorded).** `mcp_status` sent right after `initialize`, before any prompt, shows both stdio servers `pending` while the HTTP 401 server is already `needs-auth` (`mcp-status:2`):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-2>","response":{"mcpServers":[
 {"name":"echo","status":"pending","config":{...},"scope":"dynamic","source":"dynamic"},{"name":"broken","status":"pending","config":{...},...},{"name":"authy","status":"needs-auth","config":{"type":"http","url":"http://127.0.0.1:33041/mcp"},...}]}}}
```

The first `init` already has the settled states (`:6`); in the first attempt `broken` was already `failed` at that point, so the window is short and not deterministic. Treat `pending` as transient and refresh after `init` or a short delay. **Docs only**: a connected remote server that drops mid-session goes back to `pending` during five reconnect attempts, then `failed` or `needs-auth`; a failed HTTP/SSE server can return to `connected` by itself (the CLI retries in the background for about half an hour); "don't treat pending as a failure on its own" (`agent-sdk/mcp`, Error handling; `mcp`, Automatic reconnection).

**No push.** Nothing announces a status change: the three fixtures have no `system` line about MCP, and the typed `SDKMessage` union has no `mcp_server_status`/`tools_list_changed` message (docs only, `sdk.d.ts` 5553). Fresh statuses come from polling `mcp_status`, from the next turn's `init.mcp_servers`, or from the model's own text (`tandem-04`).

### 2.2 Enable, disable, reconnect

**`mcp_toggle` (recorded).** Host: `{"type":"control_request","request_id":"host-5","request":{"subtype":"mcp_toggle","serverName":"echo","enabled":false}}`. Success has **no `response` field** (`mcp-status:19`), like `set_model`:

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-23>"}}
```

The change is immediate: the next `mcp_status` shows `{"name":"echo","status":"disabled","config":{...},"scope":"dynamic","source":"dynamic"}` with no `serverInfo` or `tools` (`:20`), the next turn's `init` agrees (`:23`, `status:"disabled"`) and `mcp__echo__echo` is gone from `init.tools` (`:23` ends with `"Write"`; `:6` ends with `"mcp__echo__echo"`). `enabled:true` (`:34`, same bare success) brings it back `connected` with `serverInfo` and `tools` at once (`:35`), and the final `init` lists the tool again (`:48`). Unknown name: `{"subtype":"error","request_id":"<request_id-45>","error":"Server not found: nosuch"}` (`:38`). No `system` line accompanies a toggle (nothing between `:19` and `:20`, `:34` and `:35`). Whether the toggle persisted to `~/.claude.json` `disabledMcpServers` was not checked (docs only: `/mcp` records the choice per project there; `mcp`, Disable a server without removing it).

**`mcp_reconnect` (recorded).** Host: `{"subtype":"mcp_reconnect","serverName":"broken"}`. On the failed stdio server the error is the connection error itself:

```json
{"type":"control_response","response":{"subtype":"error","request_id":"<request_id-43>","error":"Connection closed"}}   // mcp-status:36
{"type":"control_response","response":{"subtype":"error","request_id":"<request_id-44>","error":"Server status: needs-auth"}}  // mcp-status:37, reconnect of authy
```

A reconnect of a `needs-auth` server does **not** start the auth flow and the status stays `needs-auth` (`:39`), so the panel must offer *Authenticate*, not *Reconnect*, for that state. The success shape of a reconnect that works was not recorded (no server was in a recoverable failed state); the SDK ignores the payload (`sdk.d.ts` 3205-3212, docs only). Not recorded: toggle on a failed or needs-auth server, reconnect on a connected server, `serverName:"all"`.

**Transcript (recorded).** None of this reaches the on-disk transcript: `transcripts/mcp-status.jsonl` holds only the three user lines (`:4`, `:28`, `:34`), the three assistant replies (`:21`, `:31`, `:37`), `ai-title` (`:1`) and `cost-state` (`:39`).

### 2.3 Authenticate: `mcp_authenticate` and the OAuth path

**Recorded.** `mcp_authenticate` is not in the SDK's typed `SDKControlRequestInner` union (`sdk.d.ts` 5212), but the 2.1.306 binary accepts it over stdio stream-json. Host: `{"type":"control_request","request_id":"host-14","request":{"subtype":"mcp_authenticate","serverName":"authy"}}` (no `redirectUri`). It answers **at once**, without blocking for the callback (`mcp-status:40`):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-47>","response":{
 "authUrl":"http://127.0.0.1:33041/authorize?response_type=code&client_id=tandem-probe-client&code_challenge=<code_challenge>&code_challenge_method=S256&redirect_uri=http%3A%2F%2Flocalhost%3A53713%2Fcallback&state=<state>&scope=mcp&resource=http%3A%2F%2F127.0.0.1%3A33041%2Fmcp",
 "requiresUserAction":true,"callbackExpected":true,"redirectScheme":"localhost","state":"<state>","callbackPort":53713}}}
```

`authUrl` is a standard PKCE authorization request (`response_type=code`, `client_id` from dynamic client registration, `code_challenge` S256, `redirect_uri=http://localhost:<callbackPort>/callback`, `state`, `scope`, `resource`). Observed around it (`scratch/probes/mcp-status/r2/authy.log`, not a fixture): the CLI did RFC 9728 discovery, `GET /.well-known/oauth-protected-resource` then `GET /.well-known/oauth-authorization-server`, four times in the run (first connect, the init's connect, the reconnect, the authenticate), and one `POST /register` at the authenticate; every request arrived without an `Authorization` header (no token ever existed). The CLI did **not** open a browser (stderr empty) and emitted **no** `system` line (nothing between `:40` and `:41`): the host must open `authUrl` itself. `mcp_status` right after still says `needs-auth` (`:41`). The `redirect_uri` names `localhost:53713`, so the CLI must be listening there; the listener was not exercised (the process was killed with the authenticate pending), so the callback completing, the status turning `connected`, `mcp_oauth_callback_url {serverName, callbackUrl}` and `mcp_clear_auth {serverName}` (both present in the binary and in `sdk.mjs`, docs only) are **not recorded**. Whether a `redirectUri` value (custom scheme, or a URL the IDE serves) changes `redirectScheme`/`callbackExpected` is not recorded either.

**Docs only.** The SDK's stated position is that it "doesn't open a browser or run an interactive OAuth flow"; a server with an authorization challenge and no stored token reports `needs-auth` and the run continues without its tools (`agent-sdk/mcp`, OAuth2 authentication). The CLI docs say a `claude -p`/SDK run "has no /mcp panel, so Claude Code can't run the OAuth flow there" and that with tool search on "Claude Code tells Claude that the server's tools are unavailable until you authorize it" (`mcp`, Authenticate with remote MCP servers), which is exactly the reply in `tandem-04`. The recording above shows the flow *is* reachable over stdio through `mcp_authenticate`; the claude.ai web UI drives a session's servers the same way through the Remote Control bridge (`bridge.mjs` forwards `mcp_authenticate` with `redirectUri` and `mcp_oauth_callback_url` with `callbackUrl`; changelog 2.1.121 "mcp_authenticate now supports redirectUri for custom scheme completion and claude.ai connectors"). The shell path also exists: `claude mcp login <name>` (`--no-browser` prints the URL and waits for the pasted redirect URL, needs an interactive terminal), `claude mcp logout <name>`, `--callback-port` (`mcp`, Authenticate from the command line). A server is flagged `needs-auth` on 401/403 (changelog 2.1.132, 2.1.141). MCP servers can also ask for sign-in through **elicitation** (`control_request {subtype:"elicitation", mcp_server_name, message, mode:"url", url, elicitation_id, ...}` answered with an `ElicitResult {action:"accept"|"decline"|"cancel", content?}`, then `system/elicitation_complete`; `sdk.d.ts` 3976-3999); never seen in any Tandem recording.

### 2.4 Adding and removing servers at run time: `mcp_set_servers`

**Recorded.** Host: `{"subtype":"mcp_set_servers","servers":{"echo2":{"type":"stdio","command":"/usr/bin/python3","args":["-I","<path>"]}}}`:

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<request_id-49>","response":{"added":["echo2"],"removed":[],"errors":{}}}}   // mcp-status:42
```

`echo2` then appears in `mcp_status` as `connected` with the same shape as `echo` (`:43`, `"name":"echo2","status":"connected","serverInfo":{"name":"echo","version":"0.1.0"},...,"scope":"dynamic","source":"dynamic","tools":[{"name":"echo","annotations":{}}]`). `{"subtype":"mcp_set_servers","servers":{}}` removed only `echo2` (`:44` `{"added":[],"removed":["echo2"],"errors":{}}`) and left the three `--mcp-config` servers in place (`:45`, `:48`) although they also report `source:"dynamic"`: from `mcp_status` alone a host cannot tell a `--mcp-config` server from one it added, so Tandem must remember what it added. Not measured: whether an added server's tool reaches `init.tools` (no turn ran between add and remove); `errors{}` for a server that fails or needs auth.

### 2.5 claude.ai connectors (docs only; nothing recorded)

The sandbox CLI loads no connector, so every statement here is from the docs and the SDK types:

- Connectors are ordinary entries of `init.mcp_servers` and `mcp_status` with `source:"claudeai"`, `scope:"claudeai"` and `config:{type:"claudeai-proxy", url, id, timeout?}` (`McpClaudeAIProxyServerConfig`, `sdk.d.ts` 1194-1202, 1316). There is no separate list. Their display name is `claude.ai <Name>` (`claude.ai Slack`, `claude.ai Connector A`), which is also the name used by `mcp_toggle`/`mcp_reconnect`, `disabledMcpServers`, `claude mcp login "claude.ai Slack"` and `/mcp reconnect claude.ai Slack`. The statuses are the same five values.
- `claudeai-proxy` is a member of `McpServerStatusConfig` only, not of the union `mcp_set_servers` takes: connectors are reported but cannot be added through `mcp_set_servers`. The `disableClaudeAiConnectors` setting stops the CLI fetching them.
- In terminal, VS Code, JetBrains and SDK sessions the CLI fetches connectors from claude.ai itself, only when the active auth method is a claude.ai subscription login (not with `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `apiKeyHelper`, a third-party provider or `CLAUDE_CODE_OAUTH_TOKEN`); a startup fetch failure is retried up to three times in the background; a locally added server with the same URL hides the connector (`/mcp` lists it as hidden); connectors sit lowest in precedence (`mcp`, How connectors reach Claude Code; Scope hierarchy and precedence). The desktop app instead registers connectors as in-process `type:"sdk"` servers.
- Special states shown by `/mcp` that have no typed status: `session token rejected` (claude.ai rejected the session token; the fix is `/login` then reconnect, v2.1.222+), `hidden` (duplicate local server), `not configured` (empty url), `Pending approval` (project `.mcp.json` not yet approved), `Rejected (see disabledMcpjsonServers)`, `cached 2h ago · connects on first use · N tools` (discovery cache, v2.1.221+), `managed` (org-managed auth), tools `disabled by your organization` (`mcp`, Server status detail; Use MCP servers from claude.ai; Organization controls on connector tools). How each maps onto the five `mcp_status` values (and whether `error` carries the detail) is unknown.
- Re-authorizing a connector from the shell: `claude mcp login "claude.ai Slack" --no-browser` "prints a claude.ai link and exits"; after authorizing in the browser, a new session connects automatically, a running one needs `/mcp reconnect claude.ai Slack` (`remote-control`, Authorize a connector again from your shell). Some Anthropic-hosted connectors (Microsoft 365, Gmail, Google Calendar) do not support local OAuth from Claude Code and must be connected at `claude.ai/customize/connectors`.
- Community: GitHub #26364 (CLI 2.1.14) reported connectors `connected` in `claude mcp list` but `mcp_servers:[]` in `-p` stream-json, closed stale; changelog 2.1.132/2.1.141 fixed unauthorized connectors showing as `failed` instead of `needs auth`; 2.1.296 "Fixed Claude calling cloud sessions non-interactive and suggesting /mcp or claude mcp when an MCP server needs authentication"; 2.1.288 (VS Code) "the MCP servers dialog now offers Check connection" after authorizing.

Recording the connector shapes (`needs-auth` connector in `mcp_status`, `mcp_authenticate` on one, the resulting `authUrl`) needs the owner's Mac, where `tandem-04` was taken.

### 2.6 MCP tool calls in the chat

**Recorded** (`mcp-tool-call`). Although `init.tools` lists `mcp__echo__echo` (`:5`), MCP tools are deferred: the model first calls `ToolSearch {query:"select:mcp__echo__echo", max_results:1}` (`:22`) and gets `[{type:"tool_reference", tool_name:"mcp__echo__echo"}]` with `tool_use_result{matches, query, total_deferred_tools:22}` (`:24`). The tool call's assistant line carries a ready-made label (`:34`):

```json
{"type":"assistant","message":{"model":"claude-fable-5-1","id":"<id-34>",...,"content":[{"type":"tool_use","id":"<id-36>","name":"mcp__echo__echo","input":{"text":"hello tandem"},"caller":{"type":"direct"}}],...},
 "parent_tool_use_id":null,"session_id":"<session_id-4>","uuid":"<uuid-41>","timestamp":"2026-10-21T10:23:47.467Z","request_id":"<request_id-42>","wire_tool_inputs":{"<id-36>":{"text":"hello tandem"}},
 "tool_use_meta":[{"id":"<id-36>","display_name":"Echo","server_display_name":"echo","tool_name":"echo"}]}
```

The permission request adds the server identity (`:35`; same keys on `:60`):

```json
{"type":"control_request","request_id":"<request_id-43>","request":{"subtype":"can_use_tool","tool_name":"mcp__echo__echo","mcp_server":{"name":"echo","source":"dynamic"},"display_name":"Echo","input":{"text":"hello tandem"},
 "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"mcp__echo__echo"}],"behavior":"allow","destination":"localSettings"}],"server_policy_ask_only":true,"tool_use_id":"<id-36>"}}
```

Keys: `subtype, tool_name, mcp_server{name, source}, display_name, input, permission_suggestions, server_policy_ask_only, tool_use_id`; no `decision_reason(_type)`, `description`, `blocked_path` or `requires_user_interaction`. Docs: "Key trust on `source`, not on the name or the tool-name prefix" (`agent-sdk/mcp`). After the host's allow (`{"behavior":"allow","updatedInput":{"text":"hello tandem"},"toolUseID":"<id-36>"}`) the result is (`:39`):

```json
{"type":"user","message":{"role":"user","content":[{"tool_use_id":"<id-36>","type":"tool_result","content":"{\"result\":\"hello tandem\"}"}]},...,
 "tool_use_result":{"content":"{\"result\":\"hello tandem\"}","_meta":{"io.modelcontextprotocol/serverInfo":{"name":"echo","version":"0.1.0"}},"structuredContent":{"result":"hello tandem"}},
 "tool_result_meta":[{"id":"<id-36>","permission_decision":{"decision":"accept","source":"user_temporary"}}]}
```

`content` is a JSON **string** because the python server declares an `outputSchema`; a server without one would return its text blocks (not recorded). The deny (`{"behavior":"deny","message":"The user denied this call.","toolUseID":"<id-67>"}`) gives `{type:"tool_result", content:"The user denied this call.", is_error:true, tool_use_id}`, `tool_use_result:"Error: The user denied this call."`, `tool_result_meta:[{id, non_execution_kind:"permission-rule", permission_decision:{decision:"reject", source:"user_reject"}}]` (`:62`), the model says `denied` (`:69`) and `result.permission_denials:[{tool_name:"mcp__echo__echo", tool_use_id, tool_input:{text:"second"}}]` (`:73`). On disk, `toolUseResult` is the content string only and the extras move to `mcpMeta:{_meta, structuredContent}` (`transcripts/mcp-tool-call.jsonl:32`); the deny has `toolDenialKind:"permission-rule"` (`:41`).

### 2.7 The command catalogue: `init.slash_commands`, `terminal_slash_commands`, initialize `commands`

**Recorded** (`mcp-slash`). `init.slash_commands` is a flat list of 73 names (`:4`): the git root's `.claude/skills` and `.claude/commands` (`actions`, `code-style`, ..., `writing-tests`, `new-project`, `record-fixture`, `review`, `ship`, `verify`; cwd was `scratch/probes/mcp-slash/r2`, so the CLI walks up to the repository root for project commands), the cwd's own `hello`, then the built-ins (`deep-research`, `design`, `slides`, ..., `code-review`, `simplify`, `doctor`, `loop`, `schedule`, ..., `clear`, `color`, `compact`, `config`, `output-style`, `context`, `effort`, `fast`, `focus`, `heapdump`, `init`, `mcp`, `import`, `model`, `reload-plugins`, `reload-skills`, `rename`, `ultrareview`, `security-review`, `usage`, `insights`, `recap`, `skill-doctor`, `goal`, ...). `mcp` and `model` are listed; `help`, `login`, `theme`, `exit`, `statusline`, `terminal-setup`, `resume` are not. `init.terminal_slash_commands` is `["doctor","color","focus","reload-plugins"]` (`:4`; the same four in `:10`, `:16`, `:22`): the TUI-bound subset the docs say phone/remote UIs should hide and desktop surfaces may keep (`sdk.d.ts` 6190-6193). Whether the key is omitted or `[]` when empty is still unknown (every scrubbed fixture reads `[]`, and here it is non-empty).

The initialize response's `commands` (`:1`) is the richer catalogue a palette needs: 73 entries in **the same order and with the same names** as `init.slash_commands` (checked by equality), each `{name, description, argumentHint, builtin?: true, aliases?}`; 55 are `builtin:true`; project entries end their description with ` (project)` (the non-fixture `mcp-list/r1/raw.ndjson:5` initialize response, recorded with the user's settings, shows ` (user)` for user skills and `aliases:["session-start-hook"]` on a user skill whose file name differs from its name); the aliases recorded are `code-review:["review"]`, `doctor:["checkup"]`, `loop:["proactive"]`, `schedule:["routines"]`, `clear:["reset","new"]`, `config:["settings"]`, `rename:["name"]`, `usage:["cost","stats"]`, `list-agents:["peers"]`. Trimmed examples:

```json
{"name":"hello","description":"Says hello from a project command (project)","argumentHint":""}
{"name":"mcp","description":"Manage MCP servers","argumentHint":"[reconnect|enable|disable [<server>|all]]","builtin":true}
{"name":"model","description":"Set the AI model for Claude Code","argumentHint":"<model>","builtin":true}
{"name":"code-review","description":"Review the current diff, ...","argumentHint":"[low|medium|high|xhigh|max|ultra] [--fix] [--comment] [<pr#>|<branch>|<path>] [--max-findings <n>|all]","aliases":["review"],"builtin":true}
{"name":"usage","description":"Show session cost, plan usage, and what's contributing to your limits","argumentHint":"","aliases":["cost","stats"],"builtin":true}
```

The `mcp` entry's `argumentHint` confirms the text forms `/mcp reconnect|enable|disable <server>|all` exist in 2.1.306 (not recorded over stdio). A name clash between a project command and a built-in (`review` is both a project command and an alias of `code-review`) is resolved by the docs' rule "a marked row wins" (docs only, `sdk.d.ts` 9645-9665). **Docs only**: `system/commands_changed {commands}` replaces the cached list (never recorded); MCP prompts join as `mcp__<server>__<prompt>`; skills with `user-invocable: false` are omitted.

### 2.8 Slash commands sent as prompts: local commands versus model turns

**Recorded** (`mcp-slash`). The host sent each as plain user text (`{"type":"user",...,"message":{"role":"user","content":[{"type":"text","text":"/mcp"}]}}`). `/mcp`, `/model` and `/help` ran **locally** with no API call: no `system/status requesting`, no `stream_event`, no user echo; one `assistant` line whose `message.model` is `"<synthetic>"` with zero usage, then a `result` with `num_turns:0` and `duration_api_ms:0`, each wrapped in `command_lifecycle` and preceded by a fresh `init` (`:2-:7`, `:8-:13`, `:14-:19`). The `/mcp` reply (`:5`):

```json
{"type":"assistant","message":{"diagnostics":null,"id":"<id-7>","container":null,"model":"<synthetic>","role":"assistant","stop_details":null,"stop_reason":"end_turn","stop_sequence":null,"type":"message",
 "usage":{"output_tokens_details":null,"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,...,"service_tier":null,...,"iterations":null,"speed":null,"fallback_credit":null},
 "content":[{"type":"text","text":"3 MCP server(s): 1 connected, 2 not connected, 0 disabled. Use `/mcp` in the terminal for details."}],"context_management":null},
 "parent_tool_use_id":null,"local_command_source":"<local-command-stdout>3 MCP server(s): 1 connected, 2 not connected, 0 disabled. Use `/mcp` in the terminal for details.</local-command-stdout>",
 "session_id":"<session_id-4>","uuid":"<uuid-8>","timestamp":"2026-10-21T10:23:55.022Z","local_command_run":{"command":"mcp","args":""}}
```

and its result (`:6`): `{"is_error":false,"duration_api_ms":0,"num_turns":0,"stop_reason":null,...,"total_cost_usd":0,...,"modelUsage":{},"permission_denials":[],...,"subtype":"success","result":"3 MCP server(s): ...","user_message_uuid":"<command_uuid-2>",...,"local_command":"mcp","type":"result","duration_ms":55,...}`. `needs-auth` and `failed` both count as "not connected"; the owner's `41 ... 15 connected, 25 not connected, 1 disabled` line is the same summary. `/model` (`:11`, `:12`): text `Current model: \`Fable 5.1 (default)\`\nUsage: /model <name>. Available: sonnet, opus, haiku, fable, best, sonnet[1m], opus[1m], fable[1m], opusplan, default, or a full model ID.`, `local_command_run:{command:"model", args:""}`, result `local_command:"model"`. `/help` (`:17`, `:18`): text `/help isn't available in this environment.`, **`local_command_outcome:{kind:"unavailable_headless"}`** instead of `local_command_run`, and no `local_command` key on the result. So the three top-level keys to decode on an assistant line are `local_command_source` (string wrapped in `<local-command-stdout>...</local-command-stdout>`), `local_command_run {command, args}` and `local_command_outcome {kind}`; `system/local_command_output` (listed in `docs/protocol.md` 5.2) was **not** used.

The project command `/hello` is a real model turn: `init` (`:22`), `status requesting`, stream, text `hello from the hello command` (`:29`), result `num_turns:1`, `duration_api_ms:4373` (`:34`). The stream never shows the expanded prompt; the transcript does (`transcripts/mcp-slash.jsonl:17` user `<command-message>hello</command-message>\n<command-name>/hello</command-name>` with `turnOrigin:"sdk"`, `:18` an `isMeta`, `turnCompanion` user line with the file body `Reply with exactly: hello from the hello command\n`; `:34` assistant with `attributionSkill:"hello"`).

On disk the local commands are an `isMeta` `<local-command-caveat>` user line (`:3`), a `<command-name>/mcp</command-name>\n<command-message>mcp</command-message>\n<command-args></command-args>` user line (`:4`) and a `system` line `subtype:"local_command"` with `content:"<local-command-stdout>...</local-command-stdout>"`, `level:"info"`, `commandRun:{command, args}` (`:5`; `/model` `:9`/`:10`) or `commandOutcome:{kind:"unavailable_headless"}` (`:14`). `/help` also wrote a `local_command` line with `content:"/help"` and a `submitUuid` (`:13`), which the redactor's safety net turned into `<leaked-1>`: `submitUuid` belongs in `ID_KEYS` (3.6). **Docs only**: `/theme` and other terminal-only built-ins should answer the same way (`"/theme isn't available in this environment."` as the result, no model turn); an unknown `/name` is sent to the model as an ordinary message with a note (v2.1.274+); `/login` was not probed (hard rule 3).

### 2.9 System lines about MCP, notifications, hooks

**Recorded.** None of `system/notification`, `system/informational`, `system/commands_changed`, `system/elicitation_complete`, `system/local_command_output` or a `control_request elicitation` appears in the three fixtures; the toggle, reconnect, authenticate and set_servers answers are the only lines those requests produce. The only MCP-related `system` lines seen anywhere are the plugin SessionStart hook lines of the first attempts (`hook_started {hook_id, hook_name:"SessionStart:startup", hook_event}` and `hook_response {..., output, stdout, stderr, exit_code, outcome}`, `scratch/probes/mcp-list/r1/raw.ndjson:1-4`), which `--setting-sources project,local` removes and which Tandem's argv (no such flag) will see on machines with plugins. **Docs only**: terminal notices such as `MCP server "<name>" disconnected · open /mcp to reconnect` and `N MCP servers need authentication` may travel as `system/notification {key, text, priority, color?, timeout_ms?}`; `sdk_mcp_manifests_parked` never appears for Tandem (it sends no `sdkMcpServers`).

---

## 3. Implications

### 3.1 What the reducer must decode (today `SystemInit` keeps `sessionId, model, cwd, permissionMode, claudeCodeVersion, tools, capabilities, raw`; nothing MCP- or command-related is typed)

1. **`system/init`**: `mcp_servers[{name, status, source?}]` with `status` as an open string set (`connected | failed | needs-auth | pending | disabled`, unknown kept as text), `slash_commands: string[]`, `terminal_slash_commands?: string[]` (absent or `[]` both mean none). Keep `tools` (an `mcp__` prefix marks MCP tools; it comes and goes with the toggle).
2. **`control_response` payloads**, keyed by the request Tandem sent: `mcp_status` -> `{mcpServers:[{name, status, serverInfo?{name, version, title?, icons?, websiteUrl?, description?}, error?, config?{type, command?, args?, url?, id?}, scope?, source?, tools?[{name, description?, annotations?, _meta?}]}]}`; `mcp_toggle` / `mcp_reconnect` -> success with **no `response`** or `error: string`; `mcp_authenticate` -> `{authUrl, requiresUserAction, callbackExpected, redirectScheme, state, callbackPort}` (untyped in the SDK, treat every field as optional); `mcp_set_servers` -> `{added[], removed[], errors{}}`; the initialize response's `commands[{name, description, argumentHint, builtin?, aliases?}]`.
3. **`assistant` lines**: `local_command_source`, `local_command_run{command, args}`, `local_command_outcome{kind}` and `message.model == "<synthetic>"` mark a local command's output (render as assistant text with a "local" marker, no model, no cost); `tool_use_meta[{id, display_name, server_display_name, tool_name}]` is the label for an MCP tool row.
4. **`result`**: `local_command?: string`, `num_turns:0`, `duration_api_ms:0` for a local command.
5. **`control_request can_use_tool`**: `mcp_server{name, source}`, `display_name`, `server_policy_ask_only`; `permission_suggestions` is `addRules` only (no `setMode`).
6. **`user` tool results of MCP tools**: `content` may be a JSON string; `tool_use_result{content, _meta, structuredContent}`; render `structuredContent` when present, else the content.
7. **`system` subtypes to accept and log** (docs only, never recorded): `notification`, `informational`, `commands_changed {commands}` (replace the catalogue), `elicitation_complete`, `local_command_output`; **`control_request elicitation`** (answer `decline` until rendered; mode `url` carries the sign-in URL).
8. **Hook lines** `hook_started` / `hook_response` from user plugins at startup (ignore).

### 3.2 What the host sends

```json
{"type":"control_request","request_id":"m1","request":{"subtype":"mcp_status"}}
{"type":"control_request","request_id":"m2","request":{"subtype":"mcp_toggle","serverName":"echo","enabled":false}}
{"type":"control_request","request_id":"m3","request":{"subtype":"mcp_reconnect","serverName":"broken"}}
{"type":"control_request","request_id":"m4","request":{"subtype":"mcp_authenticate","serverName":"authy"}}
{"type":"control_request","request_id":"m5","request":{"subtype":"mcp_set_servers","servers":{"echo2":{"type":"stdio","command":"/usr/bin/python3","args":["-I","<path>"]}}}}
{"type":"user","session_id":"","parent_tool_use_id":null,"uuid":"<uuid>","message":{"role":"user","content":[{"type":"text","text":"/mcp"}]}}
```

`serverName` is the name exactly as `mcp_status` reports it (for a connector, `claude.ai <Name>`; docs only). Not sent by Tandem: `mcp_oauth_callback_url`, `mcp_clear_auth` (unrecorded), anything under `claude_authenticate`/`claude_oauth_*` (hard rule 3), `update_settings`.

### 3.3 An MCP panel on this

- **Source of truth**: `mcp_status` on open and after every action, plus a refresh on each `init` (its thin list is enough to update badges between polls). Because `pending` is transient, refresh once more a second or two after a `pending` entry, and stop polling when nothing is `pending`.
- **Row**: name (escape it; it is config text), status, `source`/`scope` as a scope chip (`dynamic` = Tandem's own `--mcp-config` server or a server added at run time; `user`, `project`, `local`, `plugin`, `claudeai`, `managed`), `serverInfo.version`, `tools.size` with the tool names on expand, `error` text under a failed row, `config.url` or `config.command` as the detail line.
- **Actions per state (recorded unless noted)**: `connected` -> Disable (`mcp_toggle false`); `failed` -> Reconnect (`mcp_reconnect`; an error string is the reason to show inline, `Connection closed`), Disable; `needs-auth` -> Authenticate (`mcp_authenticate`, open `authUrl` with `BrowserUtil.browse`, then poll `mcp_status` until the row leaves `needs-auth` or the user cancels; Reconnect only answers `Server status: needs-auth`), Disable; `disabled` -> Enable (`mcp_toggle true`, immediate); `pending` -> spinner, no action. Unknown names answer `Server not found: <name>`.
- **Summary line**: `N MCP server(s): C connected, N-C-D not connected, D disabled` reproduces the CLI's own count (`failed`, `needs-auth`, `pending` all count as not connected).
- **Tandem's own server**: it reports as `dynamic` like any `--mcp-config` server; mark it read-only in the list (or hide it) and never toggle it. `mcp_set_servers` does not remove `--mcp-config` servers, so a future "add server" feature only needs to track its own additions.
- **Hard rule 5 applies**: show every error string the CLI returns, in the row and as a notification when the panel is closed.
- **Typed `/mcp`**: intercept it in the composer and open the panel; sending it costs no model turn but only yields the one-line summary.

### 3.4 A connectors list on this (docs only until recorded on the owner's Mac)

- Filter `mcp_status` by `source == "claudeai"` (fallback: `config.type == "claudeai-proxy"`), show `config.id`/`url` as the detail, strip the `claude.ai ` prefix for display but keep the full name for every request.
- `needs-auth` -> Authenticate through `mcp_authenticate` exactly as for any server; what `authUrl` looks like for a connector (a claude.ai link, as `claude mcp login "claude.ai X" --no-browser` prints) is not recorded. If the request errors, fall back to telling the user to run `claude mcp login "claude.ai <Name>"` in a terminal, then offer Reconnect (`/mcp reconnect claude.ai <Name>` is the documented remedy for a running session).
- The states `session token rejected`, `hidden`, `managed` and org-blocked tools are not distinguishable from the typed statuses; show the raw `error` string when present and otherwise the plain status. A `session token rejected` connector needs `/login`, which Tandem never drives (hard rule 3): say so in the UI.
- Expect an empty connectors list whenever the CLI is not signed in with a claude.ai subscription (API key, `CLAUDE_CODE_OAUTH_TOKEN`, third-party providers) and when `disableClaudeAiConnectors` is set: show "no connectors" rather than an error.

### 3.5 A slash-command palette on this

- Build the palette from the initialize response's `commands` (names, descriptions, `argumentHint`, `aliases`, `builtin`), replace it on `system/commands_changed`, and use `init.slash_commands` only as a fallback list of names (older CLIs) or to drop entries that disappeared. Hide or grey the names in `init.terminal_slash_commands` (`doctor`, `color`, `focus`, `reload-plugins` here).
- Group by `builtin` (built-ins) versus the ` (project)` / ` (user)` suffix of the description (which the UI can strip into a chip); an alias is a secondary match key, not a row.
- Send `/<name> <args>` as the user text. A local command answers with the synthetic assistant line (render it as assistant text, flagged local, no "Worked for 0s" working state: `num_turns:0`); `local_command_outcome.kind == "unavailable_headless"` is a note, not an error; a project or skill command is a normal model turn.
- `/mcp` (and anything Tandem has a native surface for: `/model`, `/rename`, `/compact`'s boundary) can be intercepted locally; everything else goes to the CLI.

### 3.6 Fixture tooling follow-ups (not done; tracked files untouched)

- `scripts/redact_fixture.py`: a per-fixture keep-list for `INIT_LISTS` (these three fixtures keep `mcp_servers`, and `mcp-slash` also keeps `slash_commands`, `terminal_slash_commands` and `commands`; re-running the committed redactor over them would empty those lists and break the tests that will read them); add `submitUuid` to `ID_KEYS` (`transcripts/mcp-slash.jsonl:13` shows `<leaked-1>`).
- `scripts/record-fixture.sh` / `drive_cli.py`: an MCP scenario needs the argv without `--safe-mode`, `--mcp-config` + `--strict-mcp-config`, `--setting-sources project,local`, the two python servers (`scratchpad/mcp-probes/echo_server.py`, `authy_server.py`) and the host frames of 1; the OAuth nonces (`code_challenge`, `state`) need a redaction rule.
- `docs/protocol.md`: 4.4 (`mcp_toggle`/`mcp_reconnect` bare success and error strings, `mcp_status` shape, `mcp_authenticate` payload, `mcp_set_servers` result and the `--mcp-config` exemption), 5.1 (`init.mcp_servers` is the thin list; `terminal_slash_commands` real values), 5.2 (`local_command_output` was not used; the assistant-line keys of 2.8), 6.2 (`tool_use_meta`, MCP result `_meta`/`structuredContent`), 4.3 (`can_use_tool.mcp_server`, `server_policy_ask_only`).
- Gradle was not run (rule): `FixtureDecodeTest` (every line decodes), `RobustnessTest`, `ChatRowsBuilderTest` and `ReducerTest` were not executed against the six new files, which `Fixtures.names()` will pick up automatically (`src/test/kotlin/.../protocol/Fixtures.kt:11,15`). `SystemInit` does not decode `mcp_servers` and `Ndjson` reads fields by hand, so no enum rejection of `needs-auth` is expected, but that is unverified.

---

## 4. Open questions

1. **claude.ai connectors**: not one `source:"claudeai"` entry has been recorded; the `config:{type:"claudeai-proxy", url, id}` shape, the `claude.ai <Name>` naming in `mcp_status`, how `session token rejected`/`hidden`/`managed`/org-blocked map onto the five statuses, and whether `needs-auth` connectors carry an `error`, all rest on docs. Needs a recording on the owner's Mac (CLI signed in with the subscription, `mcp_status` + `mcp_authenticate` on `claude.ai Connector A`).
2. **OAuth completion**: the callback landing on `localhost:<callbackPort>/callback`, the status turning `connected` without an extra `mcp_reconnect`, how long the CLI waits, what a second `mcp_authenticate` does while one is pending, `mcp_oauth_callback_url {serverName, callbackUrl}` and `mcp_clear_auth {serverName}` (success shape, effect on status), and which `redirectUri` values are accepted (omitted is recorded; `http://localhost:<port>/callback` and a custom scheme are not). Needs an authorization server that issues a code (the probe's never does).
3. **Text forms over stdio**: `/mcp reconnect <name>`, `/mcp reconnect all`, `/mcp enable|disable <name>`, `/model <name>` as user text (what the synthetic assistant line says, whether `local_command_run.args` carries the argument), and `/theme` (expected `unavailable_headless`, not recorded). `/login` stays unprobed.
4. **Toggle and reconnect edge cases**: `mcp_toggle` on a `failed` or `needs-auth` server, `mcp_reconnect` on a `connected` one and on a server that recovers (the success frame), `serverName:"all"`, whether a toggle persists to `~/.claude.json` `disabledMcpServers` for a `dynamic` server and under which name for a connector, whether a server added with `mcp_set_servers` contributes to `init.tools`, and what `errors{}` holds for a server that fails to connect.
5. **Push notifications**: whether `system/notification` carries `MCP server "X" disconnected · open /mcp to reconnect` or `N MCP servers need authentication` over stream-json (keys, priority), whether `commands_changed` fires when a reconnect or toggle changes the MCP prompts, and whether `terminal_slash_commands` is omitted or `[]` when empty.
6. **Elicitation**: the URL-mode sequence (`control_request elicitation mode:"url"` -> host `ElicitResult` -> `system/elicitation_complete`) with a real server; what `accept` does when the host, not the CLI, must open the URL.
7. **Per-server context cost**: `get_context_usage` `mcpTools[{name, serverName, tokens, isLoaded?}]` and `mcpInstructions[{serverName, tokens}]` (docs only) were not probed; they would give the panel a token column.
8. **Headless shell path**: whether `claude mcp login "claude.ai <Name>" --no-browser` run from the IDE without a TTY really prints the link and exits, and whether the running session then connects after `mcp_reconnect`.
9. **MCP results without an output schema** (text blocks, images, `ui://` resources via `mcp_read_resource`) and the `_meta.ui` fields seen on the plugin server's tools (`mcp-list` probe) are not in any fixture.

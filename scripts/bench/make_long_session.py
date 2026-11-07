#!/usr/bin/env python3
"""Write a long synthetic Claude Code session transcript, the input of the "open a long conversation" benchmark.

Usage: python3 -I make_long_session.py --turns N --project <path> --config <configDir> [--seed S] [--session-id UUID]

Writes <configDir>/projects/<key>/<session id>.jsonl, <key> being the project path with every character outside A-Z a-z 0-9
replaced by "-" (one per UTF-16 unit) and cut to 200 characters (docs/protocol.md 11.1), and prints the file's path, its
line count and its byte size. The file has the shape CLI 2.1.3xx writes for a session of N user turns in <path>, as the
recorded transcripts in src/test/resources/fixtures/transcripts/ show it: queue-operation lines, a user prompt line with
promptId, turnOrigin and turnPosition, the attachment lines that follow a prompt, assistant lines (one per content block:
thinking, text, tool_use) and user lines with tool_result blocks, last-prompt, ai-title, atis-latch and cost-state lines. The
parentUuid chain is intact and timestamps advance. Tools: Bash, Read, Grep, Glob, Tandem's own mcp__tandem__edit_file and
mcp__tandem__write_file (shaped as fixtures/transcripts/ide-edit.jsonl), and one AskUserQuestion exchange (as ask-two.jsonl).

The project is fictitious (src/app/Orders.kt and friends) and the text lorem-like; nothing in it comes from a real session.
The same arguments always give the same bytes: every choice comes from random.Random(seed), and the session id, when not
given, from the seed and the turn count. The first prompt starts with "Let us start with src/app/Orders.kt" and the last
reply with "Summary of the session (N turns)", so a test can look for both.
"""
import argparse
import base64
import json
import os
import random
import sys
import uuid
from datetime import datetime, timedelta, timezone

VERSION = "2.1.319"
MODEL = "claude-sonnet-5-5"
BRANCH = "main"
ENTRYPOINT = "sdk-cli"
MAX_KEY_LENGTH = 200
START = datetime(2026, 10, 1, 9, 0, 0, tzinfo=timezone.utc)

FILES = [
    "src/app/Orders.kt", "src/app/OrderRepository.kt", "src/app/Invoice.kt", "src/app/Customers.kt", "src/app/Pricing.kt",
    "src/app/Shipping.kt", "src/app/Inventory.kt", "src/app/Checkout.kt", "src/app/Discounts.kt", "src/app/Ledger.kt",
    "src/app/http/OrderRoutes.kt", "src/app/http/Json.kt", "src/app/db/Migrations.kt", "src/app/db/Schema.kt",
    "src/test/app/OrdersTest.kt", "src/test/app/PricingTest.kt", "src/test/app/CheckoutTest.kt", "build.gradle.kts",
]
NOUNS = [
    "order", "invoice", "customer", "line item", "discount", "tax rate", "shipment", "warehouse", "ledger entry", "price list",
    "checkout", "refund", "coupon", "basket", "currency", "repository", "migration", "schema", "route", "handler", "cache",
    "retry", "timeout", "batch", "report", "total", "rounding rule", "audit log", "query", "index",
]
VERBS = [
    "computes", "validates", "stores", "loads", "rounds", "groups", "filters", "merges", "splits", "caches", "retries",
    "serialises", "parses", "checks", "updates", "reads", "writes", "sorts", "maps", "reports",
]
ADJECTIVES = [
    "pending", "settled", "partial", "stale", "empty", "negative", "duplicate", "late", "cancelled", "large", "small",
    "nullable", "immutable", "shared", "local", "nested", "optional", "default", "rounded", "cached",
]
FUNCTIONS = [
    "total", "subtotal", "applyDiscount", "taxFor", "findById", "save", "cancel", "refund", "ship", "reserve", "release",
    "validate", "toJson", "fromJson", "migrate", "priceOf", "split", "merge", "close", "open",
]
TYPES = ["Order", "Invoice", "Customer", "LineItem", "Money", "Discount", "Shipment", "Ledger", "Basket", "Coupon"]
ASKS = [
    "Why does {f} round the {n} before it adds the {n2}? Look at {file} and explain.",
    "Add a test for a {a} {n} in {file}.",
    "Run the tests for {file} and tell me what fails.",
    "Rename {fn} to something clearer across the code base.",
    "Where do we read the {n} from the database? Find every call site.",
    "Make {file} handle a {a} {n} without throwing.",
    "Can you simplify {fn} in {file}? Keep the behaviour.",
    "What happens to a {a} {n} when the {n2} is {a2}?",
    "Check the git status and summarise what changed since this morning.",
    "Write the KDoc for {fn} in {file}.",
    "Is {fn} safe to call from two threads at once?",
    "List the files that touch the {n} and say which one owns it.",
]
LANGS = ["kotlin", "bash", "json", "sql"]

TRAILING = [
    "rendered", "renderedRole", "permissionMode", "promptSource", "turnOrigin", "turnPosition", "toolUseResult",
    "sourceToolAssistantUUID", "permissionDecision", "thinkingDurationMs", "effort", "perTurnEffort",
]

SESSION_NS = uuid.UUID("00000000-0000-4000-8000-000000000000")


def folder_key(project: str) -> str:
    """The CLI's folder key for a project path: JavaScript's replace(/[^a-zA-Z0-9]/g, "-"), cut to 200 characters."""
    out = []
    for ch in project:
        if ch.isascii() and ch.isalnum():
            out.append(ch)
        else:
            out.append("-" * (2 if ord(ch) > 0xFFFF else 1))  # one dash per UTF-16 unit
    return "".join(out)[:MAX_KEY_LENGTH]


class Writer:
    """Builds the transcript's lines, keeping the parentUuid chain, the clock and the random choices."""

    def __init__(self, rng: random.Random, project: str, session_id: str):
        self.rng = rng
        self.project = project
        self.session_id = session_id
        self.lines: list[str] = []
        self.leaf: str | None = None
        self.clock = START
        self.title_written = False
        self.tools_used: list[str] = []
        self.output_tokens = 0
        self.cache_read = 24000

    # ids and time

    def new_uuid(self) -> str:
        return str(uuid.UUID(int=self.rng.getrandbits(128), version=4))

    def token(self, prefix: str, length: int) -> str:
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return prefix + "".join(self.rng.choice(alphabet) for _ in range(length))

    def tick(self, low_ms: int, high_ms: int) -> str:
        self.clock += timedelta(milliseconds=self.rng.randint(low_ms, high_ms))
        return self.clock.strftime("%Y-%m-%dT%H:%M:%S.") + f"{self.clock.microsecond // 1000:03d}Z"

    # line helpers

    def emit(self, obj: dict) -> None:
        self.lines.append(json.dumps(obj, ensure_ascii=False, separators=(",", ":")))

    def common(self) -> dict:
        return {
            "userType": "external", "entrypoint": ENTRYPOINT, "cwd": self.project, "sessionId": self.session_id,
            "version": VERSION, "gitBranch": BRANCH,
        }

    def chained(self, obj: dict) -> str:
        """Emits [obj] as the next link of the chain and returns its uuid."""
        line_uuid = self.new_uuid()
        timestamp = obj.pop("timestamp", None) or self.tick(1, 40)
        trailing = {k: obj.pop(k) for k in TRAILING if k in obj}
        # the recorded key order: chain, payload, uuid, timestamp, the rest, then the fields every line has
        line = {"parentUuid": self.leaf, "isSidechain": False}
        line.update(obj)
        line["uuid"] = line_uuid
        line["timestamp"] = timestamp
        line.update(trailing)
        line.update(self.common())
        self.emit(line)
        self.leaf = line_uuid
        return line_uuid

    def attachment(self, kind: str, rendered: bool = True, role: str = "system") -> None:
        obj: dict = {"attachment": {"type": kind}, "type": "attachment"}
        if rendered:
            obj["rendered"] = ""
            obj["renderedRole"] = role
        self.chained(obj)

    def queue(self, prompt: str) -> None:
        self.emit({"type": "queue-operation", "operation": "enqueue", "timestamp": self.tick(500, 30000),
                   "sessionId": self.session_id, "content": prompt})
        self.emit({"type": "queue-operation", "operation": "dequeue", "timestamp": self.tick(1, 80),
                   "sessionId": self.session_id})

    def usage(self, output_tokens: int) -> dict:
        created = self.rng.randint(80, 900)
        self.cache_read += created
        self.output_tokens += output_tokens
        return {
            "input_tokens": 2, "cache_creation_input_tokens": created, "cache_read_input_tokens": self.cache_read,
            "output_tokens": output_tokens, "output_tokens_details": {"thinking_tokens": 0},
            "server_tool_use": {"web_search_requests": 0, "web_fetch_requests": 0}, "service_tier": "standard",
            "cache_creation": {"ephemeral_1h_input_tokens": created, "ephemeral_5m_input_tokens": 0},
            "inference_geo": "global",
            "iterations": [{
                "input_tokens": 2, "output_tokens": output_tokens, "cache_read_input_tokens": self.cache_read,
                "cache_creation_input_tokens": created,
                "cache_creation": {"ephemeral_5m_input_tokens": 0, "ephemeral_1h_input_tokens": created}, "type": "message",
            }],
            "speed": "standard", "fallback_credit": None,
        }

    def assistant_message(self, blocks: list[dict], stop_reason: str) -> list[str]:
        """One API message, written as the CLI does: one assistant line per content block, sharing message.id."""
        message_id = self.token("msg_01", 22)
        request_id = self.token("req_011C", 20)
        output_tokens = max(1, sum(len(json.dumps(b)) for b in blocks) // 4)
        usage = self.usage(output_tokens)
        uuids = []
        self.tick(900, 9000)
        for index, block in enumerate(blocks):
            obj: dict = {
                "message": {
                    "model": MODEL, "id": message_id, "type": "message", "role": "assistant", "content": [block],
                    "container": None, "stop_reason": stop_reason, "stop_sequence": None, "stop_details": None,
                    "usage": usage, "input_transformations": [], "diagnostics": None, "context_management": None,
                },
                "apiBlockIndex": index, "requestId": request_id, "requestedModel": MODEL, "type": "assistant",
            }
            obj["timestamp"] = self.tick(5, 400)
            if block["type"] == "thinking":
                obj["thinkingDurationMs"] = self.rng.randint(800, 9000)
            obj["effort"] = "high"
            obj["perTurnEffort"] = "high"
            uuids.append(self.chained(obj))
        return uuids

    def tool_result(self, tool_use_id: str, assistant_uuid: str, content, tool_use_result, prompt_id: str,
                    is_error: bool = False, decision: dict | None = None) -> None:
        block: dict = {"tool_use_id": tool_use_id, "type": "tool_result", "content": content}
        if is_error:
            block["is_error"] = True
        self.chained({
            "promptId": prompt_id, "type": "user", "message": {"role": "user", "content": [block]},
            "timestamp": self.tick(20, 4000), "toolUseResult": tool_use_result, "sourceToolAssistantUUID": assistant_uuid,
            "permissionDecision": decision or {"decision": "accept", "source": "config", "reasonType": "mode"},
        })

    # text

    def words(self, low: int, high: int) -> str:
        parts = []
        for _ in range(self.rng.randint(low, high)):
            kind = self.rng.random()
            if kind < 0.45:
                parts.append(self.rng.choice(NOUNS))
            elif kind < 0.7:
                parts.append(self.rng.choice(VERBS))
            elif kind < 0.9:
                parts.append(self.rng.choice(ADJECTIVES))
            else:
                parts.append(self.rng.choice(["the", "a", "each", "every", "no", "its"]))
        return " ".join(parts)

    def sentence(self) -> str:
        text = self.words(6, 16)
        if self.rng.random() < 0.3:
            text += f" in `{self.rng.choice(FUNCTIONS)}()`"
        return text[0].upper() + text[1:] + "."

    def paragraph(self, low: int = 2, high: int = 6) -> str:
        return " ".join(self.sentence() for _ in range(self.rng.randint(low, high)))

    def kotlin(self, lines: int) -> str:
        out = [f"package app", "", f"class {self.rng.choice(TYPES)}Service(private val repo: OrderRepository) {{"]
        while len(out) < lines - 1:
            fn = self.rng.choice(FUNCTIONS)
            t = self.rng.choice(TYPES)
            out.append(f"    fun {fn}(id: Long): {t}? {{")
            for _ in range(self.rng.randint(1, 4)):
                out.append(f"        val {self.rng.choice(['x', 'total', 'item', 'rate', 'amount'])} = repo.{self.rng.choice(FUNCTIONS)}(id)")
            out.append(f"        return repo.findById(id)?.let {{ {t}(it) }}")
            out.append("    }")
            out.append("")
        out.append("}")
        return "\n".join(out[:lines])

    def code_block(self) -> str:
        lang = self.rng.choice(LANGS)
        if lang == "kotlin":
            body = self.kotlin(self.rng.randint(6, 30))
        elif lang == "bash":
            body = "\n".join(f"./gradlew test --tests '*{self.rng.choice(TYPES)}*'" for _ in range(self.rng.randint(1, 4)))
        elif lang == "json":
            body = json.dumps({"order": self.rng.randint(1, 9999), "status": self.rng.choice(ADJECTIVES),
                               "total": f"{self.rng.randint(1, 900)}.{self.rng.randint(0, 99):02d}"}, indent=2)
        else:
            body = f"SELECT id, total FROM orders WHERE status = '{self.rng.choice(ADJECTIVES)}' ORDER BY id;"
        return f"```{lang}\n{body}\n```"

    def reply(self, long: bool) -> str:
        """Markdown: headings, lists, fenced code and inline code; 5 to 20 KB when [long]."""
        target = self.rng.randint(5000, 20000) if long else self.rng.randint(80, 1500)
        parts = [self.paragraph(1, 3)]
        size = len(parts[0])
        while size < target:
            kind = self.rng.random()
            if long and kind < 0.15:
                part = f"## {self.words(2, 5).capitalize()}"
            elif kind < 0.4:
                part = "\n".join(f"- `{self.rng.choice(FILES)}`: {self.sentence()}" for _ in range(self.rng.randint(2, 6)))
            elif kind < 0.55:
                part = "\n".join(f"{i + 1}. {self.sentence()}" for i in range(self.rng.randint(2, 5)))
            elif kind < 0.75:
                part = self.code_block()
            else:
                part = self.paragraph()
            parts.append(part)
            size += len(part) + 2
        return "\n\n".join(parts)

    def thinking(self) -> dict:
        signature = base64.b64encode(bytes(self.rng.getrandbits(8) for _ in range(self.rng.randint(120, 600)))).decode()
        return {"type": "thinking", "thinking": self.paragraph(1, 4), "signature": signature}

    def prompt_text(self, index: int, turns: int) -> str:
        if index == 0:
            return "Let us start with src/app/Orders.kt: read it and tell me how an order's total is computed."
        if index == turns - 1:
            return "Summarise what we changed today in one paragraph."
        template = self.rng.choice(ASKS)
        return template.format(
            f=self.rng.choice(FUNCTIONS) + "()", fn=self.rng.choice(FUNCTIONS), n=self.rng.choice(NOUNS),
            n2=self.rng.choice(NOUNS), a=self.rng.choice(ADJECTIVES), a2=self.rng.choice(ADJECTIVES),
            file=self.rng.choice(FILES),
        )

    # tools

    def path(self, rel: str) -> str:
        return self.project.rstrip("/") + "/" + rel

    def tool_call(self, prompt_id: str, think: bool) -> None:
        kind = self.rng.choice(["Bash", "Bash", "Read", "Read", "Grep", "Glob"])
        tool_id = self.token("toolu_01", 22)
        if kind == "Bash":
            target = self.rng.choice(TYPES)
            command, description = self.rng.choice([
                (f"./gradlew test --tests '*{target}*'", f"Run the {target} tests"),
                ("git status --short", "Show changed files"),
                (f"git log --oneline -n {self.rng.randint(3, 12)}", "Show recent commits"),
                ("ls src/app", "List the app sources"),
            ])
            tool_input = {"command": command, "description": description}
            failed = self.rng.random() < 0.08
            if command.startswith("./gradlew"):
                out = "\n".join([f"> Task :{t}" for t in ["compileKotlin", "compileTestKotlin", "test"]] +
                                [f"{target}Test > {self.rng.choice(FUNCTIONS)} {self.words(2, 5)}() PASSED"
                                 for _ in range(self.rng.randint(2, 12))] +
                                ["", "BUILD SUCCESSFUL in %ds" % self.rng.randint(4, 60)])
            elif command.startswith("git status"):
                out = "\n".join(f" M {f}" for f in self.rng.sample(FILES, self.rng.randint(1, 5)))
            elif command.startswith("git log"):
                out = "\n".join(f"{self.rng.getrandbits(28):07x} {self.rng.choice(['fix', 'feat', 'test', 'refactor'])}: {self.words(3, 7)}"
                                for _ in range(int(command.split()[-1])))
            else:
                out = "\n".join(sorted(os.path.basename(f) for f in FILES if f.startswith("src/app/") and "/" not in f[8:]))
        elif kind == "Read":
            rel = self.rng.choice(FILES)
            tool_input = {"file_path": self.path(rel)}
            content = self.kotlin(self.rng.randint(15, 160))
            lines = content.split("\n")
            failed = False
        elif kind == "Grep":
            needle = self.rng.choice(FUNCTIONS)
            tool_input = {"pattern": needle, "output_mode": "content"}
            hits = [f"{self.rng.choice(FILES)}:{self.rng.randint(1, 300)}:        val x = repo.{needle}(id)"
                    for _ in range(self.rng.randint(1, 15))]
            failed = False
        else:
            pattern = self.rng.choice(["src/**/*.kt", "src/test/**/*.kt", "**/*.gradle.kts"])
            tool_input = {"pattern": pattern}
            names = [f for f in FILES if f.endswith(".kt" if ".kt" in pattern and "gradle" not in pattern else ".kts")]
            if "test" in pattern:
                names = [f for f in names if f.startswith("src/test/")]
            failed = False
        blocks = ([self.thinking()] if think else []) + [
            {"type": "tool_use", "id": tool_id, "name": kind, "input": tool_input, "caller": {"type": "direct"}}]
        assistant_uuid = self.assistant_message(blocks, "tool_use")[-1]
        if kind == "Bash":
            if failed:
                self.tool_result(tool_id, assistant_uuid, f"Exit code 1\n{out}", f"Error: Exit code 1\n{out}", prompt_id,
                                 is_error=True)
            else:
                self.tool_result(tool_id, assistant_uuid, out,
                                 {"stdout": out, "stderr": "", "interrupted": False, "isImage": False,
                                  "noOutputExpected": False}, prompt_id)
        elif kind == "Read":
            numbered = "\n".join(f"{i + 1}\t{line}" for i, line in enumerate(lines))
            self.tool_result(tool_id, assistant_uuid, numbered,
                             {"type": "text", "file": {"filePath": tool_input["file_path"], "content": content,
                                                       "numLines": len(lines), "startLine": 1, "totalLines": len(lines)}},
                             prompt_id)
        elif kind == "Grep":
            text = "\n".join(hits)
            self.tool_result(tool_id, assistant_uuid, text,
                             {"mode": "content", "numFiles": 0, "filenames": [], "content": text, "numLines": len(hits),
                              "totalLines": len(hits)}, prompt_id)
        else:
            self.tool_result(tool_id, assistant_uuid, "\n".join(names),
                             {"filenames": names, "durationMs": self.rng.randint(3, 60), "numFiles": len(names),
                              "truncated": False, "totalMatches": len(names), "countIsComplete": True}, prompt_id)
        self.tools_used.append(kind)

    def tool_search(self, prompt_id: str, name: str) -> None:
        tool_id = self.token("toolu_01", 22)
        query = f"select:{name}"
        assistant_uuid = self.assistant_message(
            [{"type": "tool_use", "id": tool_id, "name": "ToolSearch", "input": {"query": query, "max_results": 1},
              "caller": {"type": "direct"}}], "tool_use")[-1]
        self.tool_result(tool_id, assistant_uuid, [{"type": "tool_reference", "tool_name": name}],
                         {"matches": [name], "query": query, "total_deferred_tools": 20}, prompt_id,
                         decision={"decision": "accept", "source": "config"})
        self.attachment("deferred_tools_record", rendered=False)

    def edit_call(self, prompt_id: str) -> None:
        rel = self.rng.choice([f for f in FILES if f.endswith(".kt")])
        tool_id = self.token("toolu_01", 22)
        if self.rng.random() < 0.75:
            name = "mcp__tandem__edit_file"
            old = f"repo.{self.rng.choice(FUNCTIONS)}(id)"
            new = f"repo.{self.rng.choice(FUNCTIONS)}(id)"
            tool_input = {"file_path": self.path(rel), "old_string": old, "new_string": new}
        else:
            name = "mcp__tandem__write_file"
            rel = rel.replace(".kt", "Extra.kt")
            tool_input = {"file_path": self.path(rel), "content": self.kotlin(self.rng.randint(8, 30))}
        if name not in self.tools_used:
            self.tool_search(prompt_id, name)
        assistant_uuid = self.assistant_message(
            [{"type": "tool_use", "id": tool_id, "name": name, "input": tool_input, "caller": {"type": "direct"}}],
            "tool_use")[-1]
        decision = {"decision": "accept", "source": "user_temporary", "grantScope": "once", "reasonCode": "unknown"}
        if self.rng.random() < 0.15:
            text = "The user rejected this edit in the IDE; nothing was changed.\nThe user says: not now"
            self.chained({
                "promptId": prompt_id, "type": "user",
                "message": {"role": "user", "content": [
                    {"type": "tool_result", "content": text, "is_error": True, "tool_use_id": tool_id}]},
                "timestamp": self.tick(500, 20000), "toolUseResult": "Error: " + text,
                "sourceToolAssistantUUID": assistant_uuid, "permissionDecision": decision,
            })
        else:
            body = self.kotlin(self.rng.randint(4, 12)).split("\n")
            listing = "\n".join(f"{i + 1:6d}\t{line}" for i, line in enumerate(body))
            text = (f"Applied the edit to {self.path(rel)} and saved the file.\n"
                    f"The file now reads (lines 1-{len(body)} of {len(body)}):\n{listing}")
            result = [{"type": "text", "text": text}]
            self.tool_result(tool_id, assistant_uuid, result, result, prompt_id, decision=decision)
        self.tools_used.append(name)

    def ask_call(self, prompt_id: str) -> None:
        tool_id = self.token("toolu_01", 22)
        questions = [
            {"question": "Which rounding should totals use?", "header": "Rounding",
             "options": [{"label": "Half up", "description": "Round half up to cents"},
                         {"label": "Half even", "description": "Banker's rounding to cents"}], "multiSelect": False},
            {"question": "Which modules should get the new tests?", "header": "Tests",
             "options": [{"label": "Orders", "description": "src/app/Orders.kt"},
                         {"label": "Pricing", "description": "src/app/Pricing.kt"},
                         {"label": "Checkout", "description": "src/app/Checkout.kt"}], "multiSelect": True},
        ]
        assistant_uuid = self.assistant_message(
            [{"type": "tool_use", "id": tool_id, "name": "AskUserQuestion", "input": {"questions": questions},
              "caller": {"type": "direct"}}], "tool_use")[-1]
        answers = {"Which rounding should totals use?": "Half even",
                   "Which modules should get the new tests?": "Orders, Pricing"}
        self.chained({
            "promptId": prompt_id, "type": "user",
            "message": {"role": "user", "content": [{
                "type": "tool_result",
                "content": "Your questions have been answered: " + ", ".join(f"\"{q}\"=\"{a}\"" for q, a in answers.items())
                + ". You can now continue with these answers in mind.",
                "tool_use_id": tool_id}]},
            "timestamp": self.tick(2000, 30000), "toolUseResult": {"questions": questions, "answers": answers},
            "sourceToolAssistantUUID": assistant_uuid,
            "permissionDecision": {"decision": "accept", "source": "user_temporary"},
        })
        self.tools_used.append("AskUserQuestion")

    # turns

    def turn(self, index: int, turns: int, ask_turn: int) -> None:
        prompt = self.prompt_text(index, turns)
        prompt_id = self.new_uuid()
        self.queue(prompt)
        self.chained({
            "promptId": prompt_id, "type": "user", "message": {"role": "user", "content": [{"type": "text", "text": prompt}]},
            "timestamp": self.tick(5, 60), "permissionMode": "default", "promptSource": "sdk", "turnOrigin": "sdk",
            "turnPosition": {"promptIndex": 0, "turnIndex": index + 1},
        })
        if index == 0:
            for kind in ["environment", "model", "deferred_tools_delta", "agent_listing_delta", "skill_listing",
                         "total_tokens_reminder"]:
                self.attachment(kind)
            self.emit({"type": "atis-latch", "atis": ""})
            self.attachment("session_context", role="user")
            self.attachment("date")
            self.attachment("credential_org", rendered=False)
            self.attachment("prompt_snapshot", rendered=False)
        else:
            self.attachment("total_tokens_reminder")
        self.emit({"type": "last-prompt", "lastPrompt": prompt, "leafUuid": self.leaf})
        if not self.title_written:
            self.emit({"type": "atis-latch", "atis": ""})
            self.emit({"type": "ai-title", "aiTitle": "Orders module review"})
            self.title_written = True
        last = index == turns - 1
        if index == ask_turn:
            self.ask_call(prompt_id)
        elif not last:
            for _ in range(self.rng.choice([0, 0, 1, 1, 2, 3, 4])):
                if self.rng.random() < 0.08:
                    self.edit_call(prompt_id)
                else:
                    self.tool_call(prompt_id, think=self.rng.random() < 0.25)
                if self.rng.random() < 0.5:
                    self.attachment("total_tokens_reminder")
            if self.tools_used:
                self.emit({"type": "tools-used", "tools": sorted(set(self.tools_used))})
        if last:
            text = f"Summary of the session ({turns} turns): " + self.paragraph(3, 6)
        else:
            text = self.reply(long=self.rng.random() < 0.12)
        blocks = ([self.thinking()] if self.rng.random() < 0.3 else []) + [{"type": "text", "text": text}]
        self.assistant_message(blocks, "end_turn")
        self.emit({"type": "last-prompt", "lastPrompt": prompt, "leafUuid": self.leaf})

    def cost_state(self, turns: int) -> None:
        duration = int((self.clock - START).total_seconds() * 1000)
        self.emit({
            "type": "cost-state", "sessionId": self.session_id, "totalCostUSD": round(self.output_tokens * 0.000015, 6),
            "totalAPIDuration": duration // 3, "totalAPIDurationWithoutRetries": duration // 3, "totalToolDuration": turns * 40,
            "totalLinesAdded": 0, "totalLinesRemoved": 0, "totalDuration": duration,
            "startTime": int(START.timestamp() * 1000),
            "modelUsage": {MODEL: {"inputTokens": turns * 2, "outputTokens": self.output_tokens, "thinkingTokens": 0,
                                   "cacheReadInputTokens": self.cache_read, "cacheCreationInputTokens": 0,
                                   "webSearchRequests": 0, "costUSD": round(self.output_tokens * 0.000015, 6)}},
            "hasUnknownModelCost": False,
        })


def generate(turns: int, project: str, seed: int, session_id: str) -> list[str]:
    """The transcript's lines, without newlines."""
    rng = random.Random(seed)
    writer = Writer(rng, project, session_id)
    ask_turn = turns // 3 if turns > 2 else -1
    for index in range(turns):
        writer.turn(index, turns, ask_turn)
    writer.cost_state(turns)
    return writer.lines


def default_session_id(seed: int, turns: int) -> str:
    return str(uuid.uuid5(SESSION_NS, f"tandem-long-session/{seed}/{turns}"))


def main(argv: list[str] | None = None) -> str:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--turns", type=int, required=True, help="user turns (prompts) in the session")
    parser.add_argument("--project", required=True, help="absolute project path, the session's cwd")
    parser.add_argument("--config", required=True, help="the CLI's config directory ($CLAUDE_CONFIG_DIR)")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--session-id", help="lower-case UUID; default: derived from the seed and the turn count")
    args = parser.parse_args(argv)
    if args.turns < 1:
        parser.error("--turns must be at least 1")
    if not os.path.isabs(args.project):
        parser.error("--project must be an absolute path")
    session_id = args.session_id or default_session_id(args.seed, args.turns)
    try:
        if str(uuid.UUID(session_id)) != session_id:
            raise ValueError
    except ValueError:
        parser.error("--session-id must be a lower-case UUID")
    lines = generate(args.turns, args.project, args.seed, session_id)
    folder = os.path.join(args.config, "projects", folder_key(args.project))
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, session_id + ".jsonl")
    data = ("\n".join(lines) + "\n").encode("utf-8")
    with open(path, "wb") as f:
        f.write(data)
    print(path)
    print(f"lines {len(lines)}")
    print(f"bytes {len(data)}")
    return path


if __name__ == "__main__":
    main(sys.argv[1:])

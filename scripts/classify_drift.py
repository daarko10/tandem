#!/usr/bin/env python3
"""Tell the three kinds of red of a nightly CLI contract run apart, from its recording and the JUnit XML.

Usage: classify_drift.py <recording-dir> <test-results-dir>

<recording-dir> is the red run's contract-recording artifact (contract-hello.ndjson, transcripts/contract-hello.jsonl,
transcripts/session-id.jsonl, which the fixer promotes, beside the scenario recordings <scenario>.ndjson and
transcripts/<scenario>.jsonl, which it does not), or make_drift.py's output in a rehearsal. <test-results-dir> is Gradle's
build/test-results/test after `./gradlew test --tests '*ContractTest*' -Dtandem.contractDir=<recording-dir>`; it may
not exist yet, which the first three rules do not need. Prints one JSON object and exits 0; exits 2 on a usage error.

The object: "kind", "cli_version" (system/init.claude_code_version, or null), "detail" (the error text or the
assertion message(s)) and "files" (the recording's regular files, relative, sorted). The kinds:

- cli-did-not-answer: authentication, billing, an outage or a broken publish. Not code: reported, never fixed.
- decoder: the decode test of ContractTest failed, or the reply came under a type the decoder does not know.
- reader: the reader test of ContractTest failed (TranscriptReader found no user or no assistant entry).
- unknown: nothing to fix from this recording. The decode and reader tests read only the three files the fixer promotes;
  drift only a scenario recording shows fails ContractTest's scenario tests, which land here, so the fixer is never
  handed drift its copies do not carry.

The rules, in order:

1. no recording, or no contract-hello.ndjson in it: cli-did-not-answer, "the run left no recording".
2. no system/init line: cli-did-not-answer.
3. result.is_error: cli-did-not-answer, with apiKeySource and the result text (or its errors) cut to 400 characters.
   No assistant line: decoder when result.result is a non-empty string (a renamed core type), else
   cli-did-not-answer as for is_error. A reply without a result line goes on to rule 4.
4. no test results: unknown.
5. the decode test failed: decoder, with its message (and the reader test's, on a second line, when it failed too).
6. the reader test failed: reader, with its message; unknown when the message is "no transcripts in ...".
7. otherwise: unknown, "neither contract test failed", followed by one line per other failing ContractTest case
   ("<test name>: <message>", the message cut to 400 characters), so the standing issue names the scenario check.

Only test cases of ContractTest count: the results directory holds every test class's XML when the whole test task
ran. A message is the assertion's own, without the exception class Gradle writes before it. Lines of the stream that
are not JSON objects are skipped: the nightly's check fails on such a line, so the classifier skips it to still
classify the red.
"""
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

STREAM = "contract-hello.ndjson"
DECODE_TEST = "live transcripts decode with every core type recognised"
READER_TEST = "live transcripts load with the reader"
DETAIL_LIMIT = 400
CONTRACT_CLASS_SUFFIX = ".ContractTest"


def read_stream(path: str) -> list[dict]:
    """The JSON objects of the stream, one per line; lines that are not JSON, or not objects, are skipped."""
    messages: list[dict] = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if isinstance(msg, dict):
                messages.append(msg)
    return messages


def failures(results_dir: str) -> dict[str, str]:
    """Test case name to failure message, for the failing test cases of ContractTest; {} when the dir is missing."""
    found: dict[str, str] = {}
    for path in sorted(glob.glob(os.path.join(glob.escape(results_dir), "TEST-*.xml"))):
        for case in ET.parse(path).getroot().iter("testcase"):
            if not (case.get("classname") or "").endswith(CONTRACT_CLASS_SUFFIX):
                continue
            failure = case.find("failure")
            if failure is not None:
                # Gradle writes Throwable.toString(), "<exception class>: <message>"; the class is not the message.
                message, exception = failure.get("message") or "", failure.get("type") or ""
                if exception and message.startswith(exception + ": "):
                    message = message[len(exception) + 2:]
                found[case.get("name") or ""] = message
    return found


def recorded_files(recording: str) -> list[str]:
    files: list[str] = []
    for directory, _, names in os.walk(recording):
        for name in names:
            path = os.path.join(directory, name)
            if os.path.isfile(path) and not os.path.islink(path):
                files.append(os.path.relpath(path, recording).replace(os.sep, "/"))
    return sorted(files)


def classify(recording: str, results_dir: str) -> dict:
    def verdict(kind: str, detail: str, cli_version: str | None = None) -> dict:
        files = recorded_files(recording) if os.path.isdir(recording) else []
        return {"kind": kind, "cli_version": cli_version, "detail": detail, "files": files}

    stream = os.path.join(recording, STREAM)
    if not os.path.isdir(recording) or not os.path.isfile(stream):
        return verdict("cli-did-not-answer", "the run left no recording")
    messages = read_stream(stream)
    init = next((m for m in messages if m.get("type") == "system" and m.get("subtype") == "init"), None)
    if init is None:
        return verdict("cli-did-not-answer", f"no system/init in {STREAM}")
    version = init.get("claude_code_version")
    cli_version = version if isinstance(version, str) else None
    result = next((m for m in messages if m.get("type") == "result"), {})  # the first, as the nightly reads it
    text = result["result"] if isinstance(result.get("result"), str) else json.dumps(result.get("errors", result))
    not_answered = f"The CLI did not answer (apiKeySource={init.get('apiKeySource')!r}): {text[:DETAIL_LIMIT]}"
    if result.get("is_error"):
        return verdict("cli-did-not-answer", not_answered, cli_version)
    if not any(m.get("type") == "assistant" for m in messages):
        if isinstance(result.get("result"), str) and result["result"]:
            return verdict("decoder", f"{STREAM}: no assistant line, result text present", cli_version)
        return verdict("cli-did-not-answer", not_answered, cli_version)

    if not os.path.isdir(results_dir):
        return verdict("unknown", f"no test results in {results_dir}", cli_version)
    failed = failures(results_dir)
    decode, reader = failed.get(DECODE_TEST), failed.get(READER_TEST)
    if decode is not None:
        return verdict("decoder", decode if reader is None else f"{decode}\n{reader}", cli_version)
    if reader is not None:
        return verdict("unknown" if reader.startswith("no transcripts in") else "reader", reader, cli_version)
    others = [f"{name}: {message[:DETAIL_LIMIT]}" for name, message in sorted(failed.items())]
    return verdict("unknown", "\n".join(["neither contract test failed"] + others), cli_version)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    print(json.dumps(classify(*argv)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

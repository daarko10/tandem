#!/usr/bin/env python3
"""Generate fixtures/synthetic-long-reply.ndjson, a reply long enough to overflow the chat while it streams, from plain-reply.

Usage: make_long_reply.py <fixtures-dir> [--paragraphs N]

This is the only unrecorded fixture: the autoscroll IDE test needs a reply that keeps streaming for seconds, which no
recorded reply does. It is built from <fixtures-dir>/plain-reply.ndjson: lines 1-7 verbatim (through message_start); then,
for each paragraph k of N, copies of plain-reply's content_block_start, content_block_delta and content_block_stop (lines 8,
9 and 11) with index k-1, the delta text "Paragraph k of a long reply that must overflow the viewport while it streams."
(the last one "pong") and the uuids <uuid-1001>, <uuid-1002>, ...; then the complete assistant line (line 10) holding the N
text blocks; then lines 12-15 (message_delta, message_stop, post_turn_summary, result), the result's text being the
paragraphs joined by a blank line. One text block per paragraph, so each paragraph is a new row while it streams (the
"↓ N new" count). Every other field stays plain-reply's (session_id <session_id-2>, api_message_id <id-8>), so a resumed
session's init matches its id. No timestamps, no randomness: two runs write the same bytes. It has no transcripts/ twin.
"""
import argparse
import json
import os

SOURCE = "plain-reply.ndjson"
TARGET = "synthetic-long-reply.ndjson"
FIRST_UUID = 1000


def encode(message: dict) -> str:
    return json.dumps(message, ensure_ascii=False, separators=(",", ":"))


def paragraph(k: int, count: int) -> str:
    return "pong" if k == count else f"Paragraph {k} of a long reply that must overflow the viewport while it streams."


def block_line(template: str, k: int, j: int, text: str | None = None) -> str:
    """A copy of the stream line [template] for block k (from 1): index k-1, uuid number j (1 start, 2 delta, 3 stop)."""
    line = json.loads(template)
    line["event"]["index"] = k - 1
    if text is not None:
        line["event"]["delta"]["text"] = text
    line["uuid"] = f"<uuid-{FIRST_UUID + 3 * (k - 1) + j}>"
    return encode(line)


def main(fixtures_dir: str, paragraphs: int = 60) -> None:
    if paragraphs < 1:
        raise ValueError("paragraphs must be at least 1")
    with open(os.path.join(fixtures_dir, SOURCE), encoding="utf-8") as f:
        source = [line.rstrip("\n") for line in f if line.strip()]
    if len(source) != 15:
        raise ValueError(f"{SOURCE} has {len(source)} lines, expected 15")
    start, delta, assistant, stop = source[7], source[8], source[9], source[10]
    texts = [paragraph(k, paragraphs) for k in range(1, paragraphs + 1)]

    out = source[:7]
    for k, text in enumerate(texts, 1):
        out.append(block_line(start, k, 1))
        out.append(block_line(delta, k, 2, text))
        out.append(block_line(stop, k, 3))
    complete = json.loads(assistant)
    complete["message"]["content"] = [{"type": "text", "text": text} for text in texts]
    out.append(encode(complete))
    out.extend(source[11:14])
    result = json.loads(source[14])
    result["result"] = "\n\n".join(texts)
    out.append(encode(result))

    with open(os.path.join(fixtures_dir, TARGET), "w", encoding="utf-8", newline="\n") as f:
        f.write("".join(line + "\n" for line in out))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("fixtures_dir", metavar="fixtures-dir")
    parser.add_argument("--paragraphs", type=int, default=60)
    arguments = parser.parse_args()
    main(arguments.fixtures_dir, arguments.paragraphs)

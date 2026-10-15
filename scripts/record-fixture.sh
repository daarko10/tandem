#!/usr/bin/env bash
# Record a real `claude` stream-json transcript as a redacted test fixture.
#
# Usage: scripts/record-fixture.sh <name> "<prompt>" [out-dir] [extra claude flags...]
# Example: scripts/record-fixture.sh plain-reply "Reply with the single word: pong"
#
# Uses whatever sign-in the local `claude` already has (in a hosted Claude Code session, the
# session's own sign-in works; no API key needed). Never commit the raw file, only the redacted one.
set -euo pipefail

name="${1:?fixture name}"; prompt="${2:?prompt}"; out_dir="${3:-src/test/resources/fixtures}"
shift $(( $# < 3 ? $# : 3 ))

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$out_dir"

# Run from an empty directory so the transcript does not mention the repo's files.
(cd "$work" && claude -p "$prompt" \
    --output-format stream-json --verbose --include-partial-messages --safe-mode \
    "$@" > raw.ndjson) || echo "claude exited non-zero; keeping the transcript anyway" >&2

python3 -I "$repo/scripts/redact_fixture.py" "$work/raw.ndjson" "$out_dir/$name.ndjson"
echo "wrote $out_dir/$name.ndjson ($(wc -l < "$out_dir/$name.ndjson") lines)"
echo "claude version: $(claude --version)"

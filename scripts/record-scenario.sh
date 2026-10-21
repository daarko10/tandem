#!/usr/bin/env bash
# Record a bidirectional stream-json scenario (permission, interrupt, model-switch) as a redacted fixture.
#
# Usage: scripts/record-scenario.sh <scenario> [out-dir] [extra claude flags...]
# Example: scripts/record-scenario.sh permission
#
# Like record-fixture.sh, this uses the local `claude` sign-in and never commits the raw transcript.
set -euo pipefail

scenario="${1:?scenario: permission | interrupt | model-switch}"; out_dir="${2:-src/test/resources/fixtures}"
shift $(( $# < 2 ? $# : 2 ))

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$out_dir"

# Run from an empty directory so the transcript does not mention the repo's files.
(cd "$work" && python3 -I "$repo/scripts/drive_cli.py" "$scenario" raw.ndjson "$@")

python3 -I "$repo/scripts/redact_fixture.py" "$work/raw.ndjson" "$out_dir/$scenario.ndjson"
echo "wrote $out_dir/$scenario.ndjson ($(wc -l < "$out_dir/$scenario.ndjson") lines)"
echo "claude version: $(claude --version)"

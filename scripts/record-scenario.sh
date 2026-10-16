#!/usr/bin/env bash
# Record a bidirectional stream-json scenario (see drive_cli.py) as a redacted fixture, plus the CLI's own transcript
# of the same session as a redacted transcript fixture in <out-dir>/transcripts/.
#
# Usage: scripts/record-scenario.sh <scenario> [out-dir] [extra claude flags...]
# Example: scripts/record-scenario.sh permission
#
# Like record-fixture.sh, this uses the local `claude` sign-in and never commits the raw transcripts.
set -euo pipefail

scenario="${1:?scenario: permission | interrupt | model-switch | permission-mode | fork}"
out_dir="${2:-src/test/resources/fixtures}"
shift $(( $# < 2 ? $# : 2 ))

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$out_dir/transcripts"

# Run from an empty directory so the transcript does not mention the repo's files.
(cd "$work" && python3 -I "$repo/scripts/drive_cli.py" "$scenario" raw.ndjson "$@")

# The CLI wrote its transcript under the folder of the directory it ran in; a fork also has the original session's.
python3 -I "$repo/scripts/copy_transcript.py" "$work" "$work/raw.ndjson" "$work/transcripts" "$scenario"
pairs=("$work/raw.ndjson" "$out_dir/$scenario.ndjson"
       "$work/transcripts/$scenario.jsonl" "$out_dir/transcripts/$scenario.jsonl")
if [[ -f "$work/transcripts/$scenario-origin.jsonl" ]]; then
  pairs+=("$work/transcripts/$scenario-origin.jsonl" "$out_dir/transcripts/$scenario-origin.jsonl")
fi
# One redactor run, so an id gets the same placeholder in the stream and in the transcripts.
python3 -I "$repo/scripts/redact_fixture.py" "${pairs[@]}"

for (( i = 1; i < ${#pairs[@]}; i += 2 )); do
  echo "wrote ${pairs[i]} ($(wc -l < "${pairs[i]}") lines)"
done
echo "claude version: $(claude --version)"

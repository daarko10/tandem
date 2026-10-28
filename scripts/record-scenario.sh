#!/usr/bin/env bash
# Record a bidirectional stream-json scenario (see drive_cli.py) as a redacted fixture, plus the CLI's own transcript
# of the same session as a redacted transcript fixture in <out-dir>/transcripts/.
#
# Usage: scripts/record-scenario.sh <scenario> [out-dir] [extra claude flags...]
# Example: scripts/record-scenario.sh permission
# `settings` is a probe, not a fixture: it needs an out-dir outside the fixtures,
# e.g. scripts/record-scenario.sh settings scratch/rec
# ide-edit, ide-edit-two, ide-edit-stop and ide-mention record Tandem's edit tools (an SDK MCP server served over the control
# channel) and the CLI's @path expansion; record them with --model sonnet. ide-edit-collision is a probe, like settings.
# mcp-status, mcp-tool-call and mcp-slash start their own local MCP servers (scripts/mcp_probe_servers.py, Python's
# standard library only) in the scenario's working directory, so they need nothing installed.
# The connector scenarios sign in to one claude.ai connector, named in the environment and never in a file:
# TANDEM_PROBE_CONNECTOR="claude.ai <Name>" scripts/record-scenario.sh mcp-connectors
# then open the printed link within 3 minutes. Read the redacted diff before committing it. An extra
# `--connector "claude.ai <Name>"` works too: it is exported as TANDEM_PROBE_CONNECTOR (unless that is set), so the
# redactor renames the probe's connector wherever the CLI names it.
#
# Like record-fixture.sh, this uses the local `claude` sign-in and never commits the raw transcripts.
set -euo pipefail

scenario="${1:?scenario: permission | interrupt | model-switch | permission-mode | permission-mode-rejected | fork | effort-switch | permission-mode-auto | user-image | ask-two | fork-at | fork-at-live | fork-at-busy | queue-fold | queue-later | queue-cancel | queue-steer-text | queue-cancel-steer | queue-later-command | queue-during-card | parity-requests | add-dir-spawn | ide-edit | ide-edit-two | ide-edit-stop | ide-mention | ide-edit-collision (a probe: give an out-dir outside the fixtures) | bash-mode | bash-mode-respond | bash-mode-respond-together | bash-mode-context | mcp-status | mcp-tool-call | mcp-slash | mcp-connectors | mcp-connectors-all (needs TANDEM_PROBE_CONNECTOR=\"claude.ai <Name>\"; the redactor renames the connector) | settings (a probe: give an out-dir outside the fixtures)}"
out_dir="${2:-src/test/resources/fixtures}"
if [[ "$scenario" == settings || "$scenario" == ide-edit-collision ]]; then
  case "${2:-}" in
    "" | *src/test/resources/fixtures*)
      echo "$scenario is a probe, not a fixture: give an out-dir outside src/test/resources/fixtures, e.g. scratch/rec" >&2
      exit 2 ;;
  esac
fi
shift $(( $# < 2 ? $# : 2 ))

# The redactor learns the probe's connector only from the environment; drive_cli.py takes it from either.
previous=""
for arg in "$@"; do
  if [[ "$previous" == --connector && -z "${TANDEM_PROBE_CONNECTOR:-}" ]]; then
    export TANDEM_PROBE_CONNECTOR="$arg"
  fi
  previous="$arg"
done

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

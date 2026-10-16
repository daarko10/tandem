#!/usr/bin/env bash
# Record a real `claude` stream-json transcript as a redacted test fixture, plus the CLI's own transcript of the
# same session as a redacted transcript fixture in <out-dir>/transcripts/.
#
# Usage: scripts/record-fixture.sh <name> "<prompt>" [out-dir] [extra claude flags...]
# Example: scripts/record-fixture.sh plain-reply "Reply with the single word: pong"
#
# Uses whatever sign-in the local `claude` already has (in a hosted Claude Code session, the
# session's own sign-in works; no API key needed). Never commit the raw files, only the redacted ones.
set -euo pipefail

name="${1:?fixture name}"; prompt="${2:?prompt}"; out_dir="${3:-src/test/resources/fixtures}"
shift $(( $# < 3 ? $# : 3 ))

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$out_dir/transcripts"

# Variables that tie a process to a parent Claude Code session or hosted environment; the plugin (cliEnvironment)
# and drive_cli.py drop the same ones. A `claude` that inherits them writes its transcript under the parent's
# session id. Everything else passes through, sign-in included.
parent_session_variable() {
  case "$1" in
    CLAUDECODE | CLAUDE_PID | CLAUDE_CODE_SESSION_ID | CLAUDE_CODE_CHILD_SESSION | CLAUDE_CODE_SESSION_ATTENDED | \
    CLAUDE_CODE_CONTAINER_ID | CLAUDE_CODE_WORKER_EPOCH | CLAUDE_SESSION_INGRESS_TOKEN_FILE | \
    CLAUDE_CODE_REMOTE* | CLAUDE_CODE_MESSAGING_* | CLAUDE_CODE_ENVIRONMENT_*) return 0 ;;
    *) return 1 ;;
  esac
}

# Run from an empty directory so the transcript does not mention the repo's files.
(
  cd "$work" || exit
  for variable in $(compgen -e); do
    if parent_session_variable "$variable"; then unset "$variable"; fi
  done
  claude -p "$prompt" --output-format stream-json --verbose --include-partial-messages --safe-mode "$@" > raw.ndjson
) || echo "claude exited non-zero; keeping the transcript anyway" >&2

# The CLI wrote its own transcript under the folder of the directory it ran in.
python3 -I "$repo/scripts/copy_transcript.py" "$work" "$work/raw.ndjson" "$work/transcripts" "$name"
# One redactor run, so an id gets the same placeholder in the stream and in the transcript.
python3 -I "$repo/scripts/redact_fixture.py" "$work/raw.ndjson" "$out_dir/$name.ndjson" \
  "$work/transcripts/$name.jsonl" "$out_dir/transcripts/$name.jsonl"
echo "wrote $out_dir/$name.ndjson ($(wc -l < "$out_dir/$name.ndjson") lines)"
echo "wrote $out_dir/transcripts/$name.jsonl ($(wc -l < "$out_dir/transcripts/$name.jsonl") lines)"
echo "claude version: $(claude --version)"

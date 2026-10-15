#!/usr/bin/env bash
# PostToolUse hook: format the Kotlin file Claude just edited with ktlint, if ktlint is installed.
# Silent no-op otherwise, so it never blocks work on machines without ktlint.
set -euo pipefail

input="$(cat)"
file="$(printf '%s' "$input" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"

case "$file" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac

command -v ktlint >/dev/null 2>&1 || exit 0
[ -f "$file" ] || exit 0

# Report remaining violations to Claude (exit 2 feeds stderr back), but never fail on formatting itself.
if ! out="$(ktlint --format "$file" 2>&1)"; then
  printf 'ktlint left issues in %s:\n%s\n' "$file" "$out" >&2
  exit 2
fi

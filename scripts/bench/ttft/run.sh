#!/usr/bin/env bash
# Time to first token of Tandem and the other Claude plugins (docs/benchmarks.md): every plugin on the same IDE build, CLI, model and
# prompt, on this machine. Needs a signed-in `claude` (or ANTHROPIC_API_KEY), Xvfb (or a DISPLAY of at least 1600x1000), ffmpeg,
# tesseract and python3.
#
#   scripts/bench/ttft/run.sh [--runs N] [--plugins "floor tandem anthropic air ccgui ccwithgui"] [--once "plugin ..."] [--keep-video]
#                             [--no-publish] [--note plugin=reason]... [-- extra Gradle arguments, e.g. --init-script <file>]
#
# --once names plugins tried in the first run only (ones known not to reply here, to record why without five failed IDE starts);
# --note replaces the reason a plugin that never replied is reported with.
#
# For run 1..N, and within a run for every plugin in turn (so that network variance spreads over all of them), it starts the screen
# recording, runs `./gradlew benchTtft` for that plugin (a fresh IDE: one cold prompt, then one warm prompt in the same session),
# stops the recording and finds the first frame showing each reply (collect.py, detect.py). Then it writes
# docs/benchmarks/ttft-<date>.json (every run), docs/benchmarks/evidence/ (each detected frame) and the TTFT section of
# docs/benchmarks.md (report.py); --no-publish leaves docs/ alone and writes build/bench/ttft-<date>.json only.
#
# The IDE is IntelliJ IDEA 2026.2.3 (Air needs 2026.2): -Dtandem.ideHome=<unpacked IDE> in the Gradle arguments runs one already
# unpacked; otherwise the IDE Starter downloads it. Gradle and the IDE run with a scrubbed environment (no variables of a Claude
# Code session the script may be started from), plus ANTHROPIC_MODEL=sonnet.
set -euo pipefail

cd "$(dirname "$0")/../../.."
OUT=build/bench
WIDTH=1600
HEIGHT=1000
FPS=30
RUNS=5
PLUGINS="floor tandem anthropic air ccgui ccwithgui"
ONCE=""
KEEP_VIDEO=false
PUBLISH=true
NOTES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --runs) RUNS=$2; shift ;;
    --plugins) PLUGINS=$2; shift ;;
    --once) ONCE=$2; shift ;;
    --keep-video) KEEP_VIDEO=true ;;
    --no-publish) PUBLISH=false ;;
    --note) NOTES+=(--note "$2"); shift ;;
    --) shift; break ;;
    *) break ;;
  esac
  shift
done
GRADLE_ARGS=("$@")

for tool in ffmpeg ffprobe tesseract python3; do command -v "$tool" >/dev/null || { echo "run.sh: $tool not found" >&2; exit 1; }; done
CLAUDE=$(command -v claude || true)
[ -n "$CLAUDE" ] || { echo "run.sh: claude not found on PATH" >&2; exit 1; }
DATE=$(date -u +%F)

rm -rf "$OUT/rec" "$OUT/screens" "$OUT/marks.jsonl" "$OUT/results.jsonl" "$OUT/evidence"
mkdir -p "$OUT/rec" "$OUT/evidence"
MARKS="$PWD/$OUT/marks.jsonl"
RESULTS="$PWD/$OUT/results.jsonl"

XVFB_PID=""
FFMPEG_PID=""
cleanup() {
  [ -n "$FFMPEG_PID" ] && kill -INT "$FFMPEG_PID" 2>/dev/null && wait "$FFMPEG_PID" 2>/dev/null || true
  [ -n "$XVFB_PID" ] && kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT
if [ -z "${DISPLAY:-}" ]; then
  command -v Xvfb >/dev/null || { echo "run.sh: no DISPLAY and no Xvfb" >&2; exit 1; }
  n=99
  while [ -e "/tmp/.X$n-lock" ] || [ -e "/tmp/.X11-unix/X$n" ]; do n=$((n + 1)); done
  Xvfb ":$n" -screen 0 "${WIDTH}x${HEIGHT}x24" -nolisten tcp >"$OUT/xvfb.log" 2>&1 &
  XVFB_PID=$!
  export DISPLAY=":$n"
  for _ in $(seq 50); do [ -e "/tmp/.X11-unix/X$n" ] && break; sleep 0.1; done
fi
echo "run.sh: display $DISPLAY, $RUNS runs of: $PLUGINS"

# Only what the build, the IDE and the CLI need: a Claude Code session's own variables would change how the CLI behaves.
KEEP=(HOME USER PATH LANG LC_ALL TERM DISPLAY JAVA_HOME JAVA_TOOL_OPTIONS GRADLE_USER_HOME GRADLE_OPTS CI GITHUB_ACTIONS
  HTTPS_PROXY https_proxy HTTP_PROXY http_proxy NO_PROXY no_proxy NODE_EXTRA_CA_CERTS SSL_CERT_FILE ANTHROPIC_API_KEY CLAUDE_CONFIG_DIR)
CLEAN_ENV=()
for name in "${KEEP[@]}"; do [ -n "${!name:-}" ] && CLEAN_ENV+=("$name=${!name}"); done
gradle() { env -i "${CLEAN_ENV[@]}" ./gradlew "${GRADLE_ARGS[@]}" "$@"; }

gradle buildPlugin compileIntegrationTestKotlin

for run in $(seq "$RUNS"); do
  for plugin in $PLUGINS; do
    if [ "$run" -gt 1 ] && [[ " $ONCE " == *" $plugin "* ]]; then continue; fi
    rm -rf out/ide-tests/tests/*
    echo "run.sh: $plugin, run $run"
    video="$OUT/rec/$plugin-$run.mkv"
    # Lossless, with each frame stamped with the wall clock it was grabbed at (an epoch time, kept with -copyts).
    ffmpeg -hide_banner -loglevel error -y -f x11grab -use_wallclock_as_timestamps 1 -framerate "$FPS" -video_size "${WIDTH}x${HEIGHT}" \
      -i "$DISPLAY" -copyts -fps_mode passthrough -c:v libx264 -preset ultrafast -qp 0 -g "$FPS" "$video" </dev/null &
    FFMPEG_PID=$!
    status=0
    gradle benchTtft --tests "*TtftBenchmarkIdeTest.$plugin" -Ptandem.bench.run="$run" -Ptandem.bench.marks="$MARKS" \
      -Ptandem.bench.claude="$CLAUDE" || status=$?
    kill -INT "$FFMPEG_PID"
    wait "$FFMPEG_PID" || true
    FFMPEG_PID=""
    [ "$status" -eq 0 ] || echo "run.sh: benchTtft failed for $plugin run $run ($status); its runs count as failures" >&2
    python3 -I scripts/bench/ttft/collect.py detect --marks "$MARKS" --video "$video" --plugin "$plugin" --run "$run" \
      --results "$RESULTS" --evidence-dir "$OUT/evidence" --relative-to "$OUT"
    [ "$KEEP_VIDEO" = true ] || rm -f "$video"
  done
done

# The IDE that ran: the one named by -Dtandem.ideHome, else the one the Starter unpacked.
IDE_HOME=""
for arg in "${GRADLE_ARGS[@]}"; do case "$arg" in -Dtandem.ideHome=*) IDE_HOME=${arg#-Dtandem.ideHome=} ;; esac; done
[ -n "$IDE_HOME" ] || IDE_HOME=$(dirname "$(find out/ide-tests -maxdepth 6 -name product-info.json -path '*2026.2*' | head -1)")

python3 -I scripts/bench/ttft/collect.py finalize --results "$RESULTS" --out "$OUT/ttft-$DATE.json" --ide-home "$IDE_HOME" \
  --claude "$CLAUDE" --date "$DATE" --screen "${WIDTH}x${HEIGHT}" --fps "$FPS" "${NOTES[@]}"
if [ "$PUBLISH" = true ]; then
  mkdir -p docs/benchmarks/evidence
  # Evidence paths in the results are relative to build/bench; published, they sit under docs/benchmarks/.
  sed 's#"evidence/#"benchmarks/evidence/#g; s#"screenshot": "evidence/#"screenshot": "benchmarks/evidence/#g' \
    "$OUT/ttft-$DATE.json" >"docs/benchmarks/ttft-$DATE.json"
  rm -f docs/benchmarks/evidence/*.png
  cp "$OUT"/evidence/*.png docs/benchmarks/evidence/ 2>/dev/null || true
  python3 -I scripts/bench/ttft/report.py "docs/benchmarks/ttft-$DATE.json" --write docs/benchmarks.md
  echo "run.sh: wrote docs/benchmarks/ttft-$DATE.json, docs/benchmarks/evidence/ and docs/benchmarks.md"
fi
python3 -I scripts/bench/ttft/report.py "$OUT/ttft-$DATE.json" | tee "$OUT/ttft-$DATE.md"

#!/usr/bin/env bash
# Records the launch GIF (docs/launch.md, tactics 1 and 2) from the IDE Starter scenes of DemoRecordingIdeTest, which play against the
# fake CLI: nothing is staged by hand, so run it again after any UI change.
#
#   scripts/demo/record.sh [extra Gradle arguments, e.g. --init-script <file>]
#   scripts/demo/record.sh --cut-only   (cuts build/demo/raw.mp4 again by its marks, for a change to the captions or the GIF settings)
#
# Writes build/demo/: raw.mp4 (the whole screen recording), scene-{1,2,3}.mp4 (1600 px wide, with a caption band), scene-{1,2,3}.gif and
# tandem.gif (all three, 960 px wide, 12 fps, one palette). Needs ffmpeg (x11grab) and, with no DISPLAY, Xvfb. Without a font (fc-match)
# or drawtext, the captions are left out.
set -euo pipefail

cd "$(dirname "$0")/../.."
OUT=build/demo
WIDTH=1600
HEIGHT=1000
FPS=15
GIF_WIDTH=960
GIF_FPS=12
GIF_LIMIT=$((10 * 1024 * 1024))
CAPTIONS=(
  "An edit opens as a native diff. Change it, Accept, and it lands through the IDE."
  "Sessions run side by side. Switch while both are working."
  "A session started in a terminal shows up in the sidebar."
)

for tool in ffmpeg ffprobe; do command -v "$tool" >/dev/null || { echo "record.sh: $tool not found" >&2; exit 1; }; done
MARKS="$PWD/$OUT/marks.txt"
CUT_ONLY=false
if [ "${1:-}" = "--cut-only" ]; then
  CUT_ONLY=true
  [ -f "$OUT/raw.mp4" ] && [ -f "$OUT/record-start.txt" ] && [ -f "$MARKS" ] || { echo "record.sh: nothing recorded in $OUT" >&2; exit 1; }
fi

if [ "$CUT_ONLY" = false ]; then
  rm -rf "$OUT"
  mkdir -p "$OUT"

  XVFB_PID=""
  FFMPEG_PID=""
  cleanup() {
    [ -n "$FFMPEG_PID" ] && kill -INT "$FFMPEG_PID" 2>/dev/null && wait "$FFMPEG_PID" 2>/dev/null || true
    [ -n "$XVFB_PID" ] && kill "$XVFB_PID" 2>/dev/null || true
  }
  trap cleanup EXIT

  if [ -z "${DISPLAY:-}" ]; then
    command -v Xvfb >/dev/null || { echo "record.sh: no DISPLAY and no Xvfb" >&2; exit 1; }
    n=99
    while [ -e "/tmp/.X$n-lock" ] || [ -e "/tmp/.X11-unix/X$n" ]; do n=$((n + 1)); done
    Xvfb ":$n" -screen 0 "${WIDTH}x${HEIGHT}x24" -nolisten tcp >"$OUT/xvfb.log" 2>&1 &
    XVFB_PID=$!
    export DISPLAY=":$n"
    for _ in $(seq 50); do [ -e "/tmp/.X11-unix/X$n" ] && break; sleep 0.1; done
  fi
  echo "record.sh: recording $DISPLAY"

  # Build first, so that the recording holds only the IDE runs.
  ./gradlew "$@" buildPlugin compileIntegrationTestKotlin

  # x11grab stamps frames with the wall clock; the marks are wall-clock milliseconds too.
  RECORD_START=$(date +%s%3N)
  echo "$RECORD_START" >"$OUT/record-start.txt"
  ffmpeg -hide_banner -loglevel error -y -f x11grab -framerate "$FPS" -video_size "${WIDTH}x${HEIGHT}" -i "$DISPLAY" \
    -c:v libx264 -preset ultrafast -crf 18 -pix_fmt yuv420p "$OUT/raw.mp4" </dev/null &
  FFMPEG_PID=$!

  status=0
  ./gradlew "$@" demoRecord -Ptandem.demoMarks="$MARKS" || status=$?
  kill -INT "$FFMPEG_PID"
  wait "$FFMPEG_PID" || true
  FFMPEG_PID=""
  [ "$status" -eq 0 ] || { echo "record.sh: demoRecord failed ($status); the raw recording is $OUT/raw.mp4" >&2; exit "$status"; }
fi
RECORD_START=$(cat "$OUT/record-start.txt")

FONT=""
if command -v fc-match >/dev/null && ffmpeg -hide_banner -filters 2>/dev/null | grep -c drawtext >/dev/null; then
  FONT=$(fc-match -f '%{file}' 'Inter:style=Medium' 2>/dev/null || true)
  [ -f "$FONT" ] || FONT=$(fc-match -f '%{file}' 'sans' 2>/dev/null || true)
  [ -f "$FONT" ] || FONT=""
fi

mark() { awk -v what="$1 $2" '$1 " " $2 == what { print $3 }' "$MARKS" | tail -1; }
seconds() { awk -v ms="$1" -v start="$RECORD_START" -v pad="$2" 'BEGIN { s = (ms - start) / 1000 + pad; if (s < 0) s = 0; printf "%.3f", s }'; }

for n in 1 2 3; do
  start=$(mark "scene-$n" start)
  end=$(mark "scene-$n" end)
  [ -n "$start" ] && [ -n "$end" ] || { echo "record.sh: no marks for scene $n in $MARKS" >&2; exit 1; }
  filter="fps=$FPS"
  if [ -n "$FONT" ]; then
    printf '%s' "${CAPTIONS[$((n - 1))]}" >"$OUT/caption-$n.txt"
    filter="$filter,pad=iw:ih+64:0:0:color=0x1e1f22,drawtext=fontfile=$FONT:textfile=$OUT/caption-$n.txt:fontcolor=0xdfe1e5:fontsize=30:x=(w-text_w)/2:y=h-47"
  fi
  ffmpeg -hide_banner -loglevel error -y -ss "$(seconds "$start" -0.3)" -to "$(seconds "$end" 0.3)" -i "$OUT/raw.mp4" \
    -vf "$filter" -an -c:v libx264 -preset slow -crf 20 -pix_fmt yuv420p -movflags +faststart "$OUT/scene-$n.mp4"
done

gif() { # gif <output> <input>...
  local output=$1; shift
  local inputs=() chain="" i=0
  for input in "$@"; do inputs+=(-i "$input"); chain="$chain[$i:v]"; i=$((i + 1)); done
  ffmpeg -hide_banner -loglevel error -y "${inputs[@]}" -filter_complex \
    "${chain}concat=n=$i:v=1:a=0,fps=$GIF_FPS,scale=$GIF_WIDTH:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
    "$output"
}
for n in 1 2 3; do gif "$OUT/scene-$n.gif" "$OUT/scene-$n.mp4"; done
gif "$OUT/tandem.gif" "$OUT/scene-1.mp4" "$OUT/scene-2.mp4" "$OUT/scene-3.mp4"

for file in "$OUT"/scene-*.mp4 "$OUT"/scene-*.gif "$OUT/tandem.gif"; do
  printf '%-24s %8.2f MB  %6.1f s\n' "$file" "$(awk -v b="$(stat -c %s "$file")" 'BEGIN { print b / 1048576 }')" \
    "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$file")"
done
size=$(stat -c %s "$OUT/tandem.gif")
if [ "$size" -gt "$GIF_LIMIT" ]; then echo "record.sh: tandem.gif is over 10 MB" >&2; exit 1; fi

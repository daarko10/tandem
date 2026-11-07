"""Find the first frame of a screen recording that shows the model's reply, for the time-to-first-token benchmark.

The recording is ffmpeg's x11grab with wall-clock timestamps kept (`-use_wallclock_as_timestamps 1 -copyts`), so every frame's
timestamp is an epoch time. Given the epoch millisecond of the Enter that sent the prompt, the detector OCRs frames after it with
the `tesseract` binary and returns the first one that shows the reply.

What counts as the reply. The prompt asks for the single word PONG and contains PONG itself, so the detector looks for a text
line that is PONG on its own (one-character tokens and punctuation around it are ignored, such as a terminal UI's bullet). The
prompt's echo is never such a line. As a second guard, and for warm runs where an earlier reply PONG is still on screen, a frame
counts only when that PONG line lies below the lowest line matching the anchor (a pattern of the prompt's echo, `--anchor`): a
reply is drawn under the prompt it answers. When no anchor line is visible, the frame counts when it shows more PONG lines than
the frame at the Enter did. Regions the adapter names as masks (`--mask`, such as the prompt box) are ignored, and with
`--region` only that part of the screen is read.

Search. Frames are read every `--step` ms from the Enter; the first hit is then narrowed down frame by frame with a binary
search between it and the last miss (a reply, once drawn, stays). The resolution is one frame (33 ms at 30 fps).

    python3 -I scripts/bench/ttft/detect.py --video raw.mkv --enter 1794043584021 --anchor 'nothing else' \
        [--region x,y,w,h] [--mask x,y,w,h]... [--timeout 90] [--evidence out.png --label 'Tandem cold 1']
    python3 -I scripts/bench/ttft/detect.py --frames DIR ...   (DIR holds <epoch ms>.png frames, for tests)

Prints one JSON object: hit (bool), enter_ms, frame_ms, ttft_ms, the matched line's box, the frames read, or reason.
Standard library only, plus the ffmpeg, ffprobe and tesseract binaries.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

SCALE = 2
TOKEN = re.compile(r"[A-Za-z0-9]+")


class Line:
    def __init__(self, text: str, left: int, top: int, right: int, bottom: int):
        self.text, self.left, self.top, self.right, self.bottom = text, left, top, right, bottom

    def box(self) -> list:
        return [self.left, self.top, self.right - self.left, self.bottom - self.top]

    def __repr__(self) -> str:
        return f"Line({self.text!r}, {self.box()})"


def is_pong(text: str) -> bool:
    """A line that is the word PONG on its own: one-character tokens and punctuation (a bullet, a prompt mark) are ignored."""
    tokens = [t for t in TOKEN.findall(text) if len(t) > 1]
    return tokens == ["PONG"]


def parse_tsv(tsv: str, origin=(0, 0), scale: float = 1, masks=()) -> list:
    """tesseract's TSV as lines in screen coordinates, without the words whose centre lies in a mask."""
    rows = tsv.splitlines()
    if not rows:
        return []
    header = rows[0].split("\t")
    lines, order = {}, []
    for row in rows[1:]:
        cols = row.split("\t")
        if len(cols) != len(header):
            continue
        r = dict(zip(header, cols))
        text = r.get("text", "").strip()
        if r.get("level") != "5" or not text:
            continue
        left = origin[0] + int(r["left"]) / scale
        top = origin[1] + int(r["top"]) / scale
        width, height = int(r["width"]) / scale, int(r["height"]) / scale
        cx, cy = left + width / 2, top + height / 2
        if any(mx <= cx <= mx + mw and my <= cy <= my + mh for mx, my, mw, mh in masks):
            continue
        key = (r["page_num"], r["block_num"], r["par_num"], r["line_num"])
        if key not in lines:
            lines[key] = []
            order.append(key)
        lines[key].append((text, left, top, left + width, top + height))
    out = []
    for key in order:
        words = lines[key]
        out.append(Line(" ".join(w[0] for w in words), int(min(w[1] for w in words)), int(min(w[2] for w in words)),
                        int(max(w[3] for w in words)), int(max(w[4] for w in words))))
    return out


def reply_line(lines: list, anchor: "re.Pattern", baseline: int):
    """The PONG line that answers the prompt in this frame, or None."""
    pongs = [l for l in lines if is_pong(l.text)]
    if not pongs:
        return None
    anchors = [l for l in lines if anchor.search(l.text) and not is_pong(l.text)]
    if anchors:
        lowest = max(a.top for a in anchors)
        below = [p for p in pongs if p.top > lowest]
        return below[0] if below else None
    return pongs[-1] if len(pongs) > baseline else None


def count_pongs(lines: list) -> int:
    return sum(1 for l in lines if is_pong(l.text))


def search(times: list, enter_ms: int, timeout_ms: int, step_ms: int, read):
    """The first frame time at or after enter_ms whose read(t) is truthy, as (t, value, frames read); None for t on a timeout.

    read(t) returns the frame's reply line or None. Frames are probed every step_ms, then the first hit is narrowed down by binary
    search among the frames between it and the last miss.
    """
    window = [t for t in times if enter_ms <= t <= enter_ms + timeout_ms]
    probes = 0
    if not window:
        return None, None, probes
    last_miss, hit, hit_value = -1, None, None
    i = 0
    next_t = window[0]
    while i < len(window):
        if window[i] >= next_t or i == len(window) - 1:
            probes += 1
            value = read(window[i])
            if value is not None:
                hit, hit_value = i, value
                break
            last_miss = i
            next_t = window[i] + step_ms
        i += 1
    if hit is None:
        return None, None, probes
    lo, hi = last_miss + 1, hit
    while lo < hi:
        mid = (lo + hi) // 2
        probes += 1
        value = read(window[mid])
        if value is not None:
            hi, hit_value = mid, value
        else:
            lo = mid + 1
    return window[hi], hit_value, probes


class Frames:
    """Frames of a recording (--video) or of a folder of <epoch ms>.png files (--frames), cropped, scaled and grey for OCR."""

    def __init__(self, video=None, folder=None, region=None, work=None):
        self.video, self.folder, self.region = video, folder, region
        self.work = work or tempfile.mkdtemp(prefix="ttft-")
        if video:
            out = run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0", video])
            self.times = sorted({round(float(x.strip().rstrip(",")) * 1000) for x in out.split() if x.strip().rstrip(",")})
            start = run(["ffprobe", "-v", "error", "-show_entries", "format=start_time", "-of", "csv=p=0", video]).strip()
            self.start_ms = round(float(start) * 1000)
        else:
            self.times = sorted(int(os.path.splitext(f)[0]) for f in os.listdir(folder) if re.fullmatch(r"\d+\.png", f))
            self.start_ms = self.times[0] if self.times else 0

    def _input(self, t: int) -> list:
        if self.video:
            # Input seeking is relative to the file's start; accurate seeking returns the first frame at or after it.
            return ["-ss", "%.4f" % max(0.0, (t - self.start_ms) / 1000 - 0.0005), "-i", self.video]
        return ["-i", os.path.join(self.folder, f"{t}.png")]

    def ocr_image(self, t: int) -> str:
        path = os.path.join(self.work, f"ocr-{t}.png")
        crop = f"crop={self.region[2]}:{self.region[3]}:{self.region[0]}:{self.region[1]}," if self.region else ""
        run(["ffmpeg", "-v", "error", "-y", *self._input(t), "-frames:v", "1",
             "-vf", f"{crop}scale=iw*{SCALE}:ih*{SCALE}:flags=bicubic,format=gray", path])
        return path

    def lines(self, t: int, masks=()) -> list:
        path = self.ocr_image(t)
        tsv = run(["tesseract", path, "stdout", "--psm", "3", "tsv"])
        os.remove(path)
        origin = (self.region[0], self.region[1]) if self.region else (0, 0)
        return parse_tsv(tsv, origin, SCALE, masks)

    def evidence(self, t: int, line, path: str, label: str):
        """The hit frame, cropped to the area above and around the reply line, with the label and a box on the line."""
        x0, y0, w0, h0 = self.region or (0, 0, *frame_size(self))
        top = max(y0, line.top - 260)
        bottom = min(y0 + h0, line.bottom + 80)
        height = bottom - top
        box = f"drawbox=x={line.left - x0 - 4}:y={line.top - top - 4}:w={line.right - line.left + 8}:h={line.bottom - line.top + 8}:color=red:t=2"
        font = font_file()
        text = ""
        if font:
            label_file = os.path.join(self.work, "label.txt")
            with open(label_file, "w") as f:
                f.write(label)
            text = (f",pad=iw:ih+46:0:46:color=black,drawtext=fontfile={font}:textfile={label_file}:fontcolor=white:fontsize=15:line_spacing=4:x=8:y=6")
        vf = (f"crop={w0}:{height}:{x0}:{top},{box}{text},scale='min(800,iw)':-2:flags=lanczos,"
              "split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse=dither=none")
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        run(["ffmpeg", "-v", "error", "-y", *self._input(t), "-frames:v", "1", "-filter_complex", vf, path])


def frame_size(frames: Frames):
    src = frames.video or os.path.join(frames.folder, f"{frames.times[0]}.png")
    out = run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", src])
    w, h = out.strip().split(",")[:2]
    return int(w), int(h)


def font_file() -> str:
    if not shutil.which("fc-match"):
        return ""
    try:
        path = subprocess.run(["fc-match", "-f", "%{file}", "DejaVu Sans Mono"], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return ""
    return path if path and os.path.isfile(path) and ":" not in path else ""


def run(cmd: list) -> str:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"{cmd[0]} failed ({result.returncode}): {result.stderr.strip()[:500]}")
    return result.stdout


def box(value: str) -> tuple:
    parts = [int(float(p)) for p in value.split(",")]
    if len(parts) != 4 or parts[2] <= 0 or parts[3] <= 0:
        raise argparse.ArgumentTypeError(f"expected x,y,w,h: {value}")
    return tuple(parts)


def detect(frames: Frames, enter_ms: int, anchor: str, timeout_s: float = 90, step_ms: int = 500, masks=(),
           evidence=None, label="") -> dict:
    pattern = re.compile(anchor, re.IGNORECASE)
    before = [t for t in frames.times if t <= enter_ms]
    baseline = count_pongs(frames.lines(before[-1], masks)) if before else 0
    result = {"enter_ms": enter_ms, "anchor": anchor, "region": list(frames.region) if frames.region else None,
              "masks": [list(m) for m in masks], "baseline_pongs": baseline}
    if not frames.times or frames.times[-1] < enter_ms:
        return {**result, "hit": False, "reason": "the recording ends before the Enter", "frames_read": 0}
    t, line, probes = search(frames.times, enter_ms, int(timeout_s * 1000), step_ms,
                             lambda ft: reply_line(frames.lines(ft, masks), pattern, baseline))
    result["frames_read"] = probes
    if t is None:
        covered = min(frames.times[-1], enter_ms + int(timeout_s * 1000)) - enter_ms
        return {**result, "hit": False, "reason": f"no reply line within {covered / 1000:.1f} s of the Enter"}
    result.update({"hit": True, "frame_ms": t, "ttft_ms": t - enter_ms, "line": line.text, "line_box": line.box()})
    if evidence:
        frames.evidence(t, line, evidence, f"{label}: TTFT {t - enter_ms} ms".strip(": ") + f"\nEnter {enter_ms}, frame {t} (epoch ms)")
        result["evidence"] = evidence
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--video")
    source.add_argument("--frames")
    parser.add_argument("--enter", type=int, required=True, help="epoch ms of the Enter")
    parser.add_argument("--anchor", required=True, help="regular expression for a line of the prompt's echo")
    parser.add_argument("--region", type=box)
    parser.add_argument("--mask", type=box, action="append", default=[])
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--step", type=int, default=500)
    parser.add_argument("--evidence")
    parser.add_argument("--label", default="")
    args = parser.parse_args(argv)
    for tool in ("ffmpeg", "ffprobe", "tesseract"):
        if not shutil.which(tool):
            print(f"detect.py: {tool} not found", file=sys.stderr)
            return 2
    work = tempfile.mkdtemp(prefix="ttft-")
    try:
        frames = Frames(video=args.video, folder=args.frames, region=args.region, work=work)
        result = detect(frames, args.enter, args.anchor, args.timeout, args.step, args.mask, args.evidence, args.label)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())

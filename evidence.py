"""Which recorded fixtures show each gotcha, by CLI version. Reads only the recordings."""
import json, pathlib, sys

def lines(path):
    out = []
    for raw in path.read_text().splitlines():
        try:
            out.append(json.loads(raw))
        except ValueError:
            out.append(None)
    return out

def ev(o):
    return (o or {}).get("event") or {}

def g1(ls):  # tool_use_result is a string
    return any(o and o.get("type") == "user" and isinstance(o.get("tool_use_result"), str) for o in ls)

def g2(ls):
    return any(o and o.get("type") == "result" and o.get("subtype") == "error_during_execution"
               and o.get("terminal_reason") == "aborted_streaming" for o in ls)

def g3(ls):
    return any(o and o.get("type") == "result" and o.get("subtype") == "success" and o.get("is_error") is True
               and o.get("api_error_status") for o in ls)

def g4(ls):
    return any(a and a.get("type") == "assistant" and b and b.get("type") == "stream_event"
               and ev(b).get("type") == "content_block_stop" for a, b in zip(ls, ls[1:]))

def g5(ls):
    open_ = False
    for o in ls:
        if not o:
            continue
        t = ev(o).get("type") if o.get("type") == "stream_event" else None
        if t == "message_start":
            open_ = True
        elif t == "message_stop":
            open_ = False
        elif o.get("type") == "control_request" and (o.get("request") or {}).get("subtype") == "can_use_tool" and open_:
            return True
    return False

def g6(ls):
    return any(o and o.get("type") == "stream_event" and (ev(o).get("delta") or {}).get("type") == "thinking_delta"
               and (ev(o).get("delta") or {}).get("thinking") == "" for o in ls)

def g7(ls):
    return any(o and o.get("type") == "result" and o.get("num_turns") == 0 and o.get("local_command") for o in ls)

def g8(ls):
    return any(o and o.get("type") == "user" and o.get("isReplay")
               and isinstance((o.get("message") or {}).get("content"), str)
               and "Set model to" in o["message"]["content"] for o in ls)

def g9(ls):
    costs = [o.get("total_cost_usd") for o in ls if o and o.get("type") == "result"]
    costs = [c for c in costs if isinstance(c, (int, float)) and c > 0]
    return len(costs) >= 2 and all(b > a for a, b in zip(costs, costs[1:]))

def g10(ls):
    return any(o and o.get("type") == "user" and "No such tool available" in json.dumps(o.get("message")) for o in ls)

CHECKS = [g1, g2, g3, g4, g5, g6, g7, g8, g9, g10]

def version(ls):
    for o in ls:
        if o and o.get("claude_code_version"):
            return o["claude_code_version"]
        if o and isinstance(o.get("response"), dict):
            r = o["response"].get("response") or {}
            if isinstance(r, dict) and r.get("claude_code_version"):
                return r["claude_code_version"]
    return None

def collect(fixtures_dir):
    """{gotcha number: {version: [fixture names]}}, and {version: [fixture names]}."""
    by_gotcha = {n: {} for n in range(1, 11)}
    by_version = {}
    for f in sorted(pathlib.Path(fixtures_dir).glob("*.ndjson")):
        if f.stem.startswith("synthetic"):
            continue  # generated, not recorded
        ls = lines(f)
        v = version(ls)
        if not v:
            continue
        by_version.setdefault(v, []).append(f.stem)
        for n, check in enumerate(CHECKS, 1):
            if check(ls):
                by_gotcha[n].setdefault(v, []).append(f.stem)
    return by_gotcha, by_version

if __name__ == "__main__":
    g, v = collect(sys.argv[1])
    for n, m in g.items():
        print(n, {k: m[k] for k in sorted(m)})
    print({k: len(x) for k, x in sorted(v.items())})

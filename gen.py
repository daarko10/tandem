"""Write docs/ for one view of the site: "all", or one CLI version. Usage: gen.py <fixtures dir> <view>"""
import pathlib, re, sys
from evidence import collect

BLOB = "https://github.com/daarko10/tandem/blob/main/src/test/resources/fixtures/"
KIND_CLASS = {"Protocol rule": "rule", "Version-specific behaviour": "version",
              "Easy consumer mistake": "mistake", "Documented contract": "contract"}

def vkey(v):
    return tuple(int(x) for x in v.split("."))

def fixture_links(names):
    return ", ".join(f"[`{n}`]({BLOB}{n}.ndjson)" for n in names)

def chips(n, by_gotcha, versions):
    seen = by_gotcha[n]
    out = []
    for v in versions:
        if v in seen:
            tip = "Recorded in " + ", ".join(seen[v])
            out.append(f'<a class="vchip on" href="../{v}/#g{n}" title="{tip}">{v}</a>')
        else:
            out.append(f'<span class="vchip off" title="No recording on {v} shows it">{v}</span>')
    return '<div class="vchips"><span class="vchips-label">Recorded on</span>' + "".join(out) + "</div>"

def admonition(n, by_gotcha, view):
    seen = by_gotcha[n]
    if view in seen:
        links = ", ".join(f'<a href="{BLOB}{f}.ndjson"><code>{f}</code></a>' for f in seen[view])
        return f'<div class="vstate on"><span class="vchip on">{view}</span> Recorded on this version, in {links}</div>'
    others = "".join(f'<a class="vchip on" href="../{v}/#g{n}">{v}</a>' for v in sorted(seen, key=vkey))
    return (f'<div class="vstate off"><span class="vchip off">{view}</span> Not seen in this version\'s recordings, '
            f'which is not the same as fixed. Recorded on {others}</div>')

def banner(view, by_gotcha, by_version):
    if view == "all":
        return ('!!! tip "Pick a CLI version"\n\n'
                "    Under each gotcha are the versions it was recorded on. Click one, or use the "
                "version menu at the top. [All versions in one table](versions.md).\n")
    hits = [n for n in range(1, 11) if view in by_gotcha[n]]
    count = len(by_version[view])
    return (f'!!! tip "CLI {view}"\n\n'
            f"    {len(hits)} of the 10 gotchas show up in the {count} recording{'s' if count != 1 else ''} "
            f"made with this version. [See all versions](../all/).\n")

def article(src, view, by_gotcha, by_version, versions):
    src = src.replace("\n## The gotchas\n", "\n" + banner(view, by_gotcha, by_version) + "\n## The gotchas\n", 1)
    for kind, cls in KIND_CLASS.items():
        src = re.sub(rf"^{kind}\.$", f'<span class="kind kind-{cls}">{kind}</span>', src, flags=re.M)
    def badge(m):
        n = int(m.group(1))
        return chips(n, by_gotcha, versions) if view == "all" else admonition(n, by_gotcha, view)
    return re.sub(r"<!-- badge (\d+) -->", badge, src)

def versions_page(src, by_gotcha, by_version, versions):
    titles = dict(re.findall(r"^### (\d+)\. (.+?) \{ #g\d+ \}$", src, flags=re.M))
    head = "| Gotcha | " + " | ".join(versions) + " |\n|---|" + "---|" * len(versions) + "\n"
    rows = "".join(
        f"| [{n}. {titles[str(n)]}](index.md#g{n}) | "
        + " | ".join(":material-check:{ .yes title=\"" + ", ".join(by_gotcha[n][v]) + "\" }" if v in by_gotcha[n] else "·"
                     for v in versions) + " |\n"
        for n in range(1, 11))
    per = "".join(
        f"### CLI {v}\n\n{len(by_version[v])} recording{'s' if len(by_version[v]) != 1 else ''}: "
        f"{fixture_links(by_version[v])}.\n\n" for v in reversed(versions))
    return f"""# Versions

Every view of this site is built from the same recordings. A version's view marks each gotcha
as recorded or not recorded on that version; nothing is claimed for a version that no recording covers.
A dot means no recording on that version shows it, which is not the same as fixed.

{head}{rows}
## How a new version gets here

A nightly job records a set of scenarios with the newest released CLI and decodes them with the
same code that decodes the committed recordings. When the CLI changes shape, the run goes red, the
new recording is committed as a fixture, and the next build of this site gains that version's view, with the change on it.

## Recordings by version

{per}"""

def main():
    fixtures, view = sys.argv[1], sys.argv[2]
    by_gotcha, by_version = collect(fixtures)
    versions = sorted(by_version, key=vkey)
    if view != "all" and view not in by_version:
        sys.exit(f"no recordings for {view}")
    src = pathlib.Path(__file__).with_name("article.md").read_text()
    docs = pathlib.Path(__file__).with_name("docs")
    docs.mkdir(exist_ok=True)
    (docs / "index.md").write_text(article(src, view, by_gotcha, by_version, versions))
    (docs / "versions.md").write_text(versions_page(src, by_gotcha, by_version, versions))
    print(" ".join(reversed(versions)))

if __name__ == "__main__":
    main()

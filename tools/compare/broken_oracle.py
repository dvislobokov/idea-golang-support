"""Reference side of docs/compare part 1: applies each breakage of a `path|was|becomes` file to the clone ON DISK, one at a time, runs
`go build -gcflags=-e`, `go vet` and the go/types oracle (tools/compare/oracle) on the breakage's package, and restores the file byte for
byte. Output: one JSON object per breakage (jsonl). `--check` prints the breakages whose oracle does not give exactly one error.

    python tools/compare/broken_oracle.py --root build/compare/prometheus --breaks docs/compare/prometheus-broken-dumps/breakages.txt \
        --out docs/compare/prometheus-broken-dumps/oracle.jsonl [--only 3,7] [--check]

The breakage format is the one GoBrokenHarnessTest reads: `## category` lines, `#` comments, `\\n` `\\t` `\\|` escapes.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
POS = re.compile(r"^(?P<file>(?:[A-Za-z]:)?[^:]+):(?P<line>\d+):(?:(?P<col>\d+):)?\s*(?P<msg>.*)$")


def unescape(s):
    return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\|", "|")


def read_breakages(path):
    category, out = "", []
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip("\r\n").rstrip()
        if line.startswith("## "):
            category = line[3:].strip()
            continue
        if not line or line.startswith("#"):
            continue
        parts = [p.replace("\x01", "\\|") for p in line.replace("\\|", "\x01").split("|")]
        if len(parts) != 3:
            sys.exit(f"bad breakage line: {line}")
        out.append({"id": len(out) + 1, "category": category, "path": parts[0], "was": unescape(parts[1]), "becomes": unescape(parts[2])})
    return out


def oracle_exe():
    exe = os.path.join(tempfile.gettempdir(), "go-compare-oracle.exe")
    subprocess.run(["go", "build", "-o", exe, "."], cwd=os.path.join(HERE, "oracle"), check=True)
    return exe


def positions(text, root):
    """`file:line:col: msg` lines of go build / go vet, paths relative to the clone, slashes forward."""
    found = []
    for line in text.splitlines():
        m = POS.match(line.strip())
        if not m or m.group("file").startswith("#"):
            continue
        f = m.group("file").replace("\\", "/")
        if os.path.isabs(f):
            f = os.path.relpath(f, root).replace("\\", "/")
        f = f[2:] if f.startswith("./") else f
        found.append({"file": f, "line": int(m.group("line")), "col": int(m.group("col") or 0), "msg": m.group("msg")})
    return found


def primary(e):
    """A reference error of its own: not the `# pkg` list entry of a failed package, not go/types' "other declaration of x" companion."""
    if e.get("kind") == "list" and not e.get("line"):
        return False
    return not e.get("msg", "").strip().startswith("other declaration of")


def run(cmd, cwd):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, p.stdout + p.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--breaks", required=True)
    ap.add_argument("--out")
    ap.add_argument("--only")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    root = os.path.abspath(a.root)
    only = {int(x) for x in a.only.split(",")} if a.only else None
    exe = oracle_exe()
    bin_dir = tempfile.mkdtemp(prefix="go-compare-bin")
    results = []
    for b in read_breakages(a.breaks):
        if only and b["id"] not in only:
            continue
        path = os.path.join(root, b["path"])
        original = open(path, "rb").read()
        text = original.decode("utf-8")
        at = text.find(b["was"])
        r = dict(b)
        if at < 0:
            r.update(applied=False, note="text not found")
            results.append(r)
            print(f"#{b['id']} NOT FOUND {b['path']}: {b['was']!r}")
            continue
        r["note"] = "ambiguous: first occurrence used" if text.find(b["was"], at + 1) >= 0 else None
        r["line"] = text.count("\n", 0, at) + 1
        pkg = "./" + os.path.dirname(b["path"]).replace("\\", "/")
        try:
            open(path, "wb").write((text[:at] + b["becomes"] + text[at + len(b["was"]):]).encode("utf-8"))
            _, build = run(["go", "build", "-gcflags=-e", "-o", os.devnull, pkg], root)
            _, vet = run(["go", "vet", pkg], root)
            code, oracle = run([exe, "-dir", root, pkg], root)
        finally:
            open(path, "wb").write(original)
        r.update(applied=True, build=positions(build, root), buildRaw=build, vet=positions(vet, root), vetRaw=vet,
                 oracle=[e for e in map(json.loads, [x for x in oracle.splitlines() if x.startswith("{")]) if primary(e)])
        results.append(r)
        n = len([e for e in r["oracle"]])
        flag = "" if n == 1 else "   <-- not exactly one error"
        print(f"#{b['id']} {b['category']} {b['path']}:{r['line']} oracle={n} build={len(r['build'])} vet={len(r['vet'])}{flag}")
        if a.check and n != 1:
            for e in r["oracle"]:
                print(f"      {e['file']}:{e['line']}:{e['col']} [{e['code']}] {e['msg']}")
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            for r in results:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()

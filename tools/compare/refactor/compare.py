"""Joins the plugin stand (refactor.json), the gopls reference (refactor-gopls.json) and both build checks (*-build.json) into one row
per scenario, and tells whether the two results are the same text. Prints a Markdown table and writes <out>/refactor-summary.json.

    python tools/compare/refactor/compare.py [--out build/compare/out] [--scenarios tools/compare/refactor/scenarios.json]
"""
import argparse
import json
import os
import re

# the names gopls gives what it extracts (the user renames them afterwards); the plugin stand passes the scenario's name instead
GOPLS_NAMES = re.compile(r"\b(newVar|newConst|newFunction|newMethod|newParam)\d*\b")


def load(path, key=None):
    if not os.path.isfile(path):
        return {}
    data = json.load(open(path, encoding="utf-8"))
    rows = data[key] if key else data
    return {r["id"]: r for r in rows}


def files(base, sid):
    root = os.path.join(base, sid, "files")
    out = {}
    if os.path.isdir(root):
        for dirpath, _, names in os.walk(root):
            for n in names:
                p = os.path.join(dirpath, n)
                out[os.path.relpath(p, root).replace("\\", "/")] = open(p, encoding="utf-8").read()
    return out


def norm(text):
    return "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").split("\n")).strip()


def verdict(build):
    if not build:
        return "-"
    v = build.get("verdict", "-")
    return "ok" if v == "ok" else v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/compare/out")
    ap.add_argument("--scenarios", default="tools/compare/refactor/scenarios.json")
    a = ap.parse_args()
    scenarios = json.load(open(a.scenarios, encoding="utf-8"))["scenarios"]
    plugin = load(os.path.join(a.out, "refactor.json"))
    gopls = load(os.path.join(a.out, "refactor-gopls.json"), "results")
    pbuild = load(os.path.join(a.out, "refactor-build.json"), "results")
    gbuild = load(os.path.join(a.out, "refactor-gopls-build.json"), "results")
    rows = []
    for s in scenarios:
        sid = s["id"]
        p, g = plugin.get(sid, {}), gopls.get(sid, {})
        pf, gf = files(os.path.join(a.out, "refactor"), sid), files(os.path.join(a.out, "refactor-gopls"), sid)
        name = (s.get("params") or {}).get("name")
        if name:
            gf = {k: GOPLS_NAMES.sub(name, v) for k, v in gf.items()}
        pd, gd = set(p.get("deleted", [])), set(g.get("deleted", []))
        if not pf and not gf and not pd and not gd:
            same = "-"
        elif not pf and not pd:
            same = "gopls only"
        elif not gf and not gd:
            same = "plugin only"
        elif set(pf) != set(gf) or pd != gd:
            same = f"differs: files {sorted(set(pf) ^ set(gf)) or ''} deleted {sorted(pd ^ gd) or ''}".replace(" deleted ", "; deleted ").strip()
        else:
            diff_files = [f for f in pf if norm(pf[f]) != norm(gf[f])]
            same = "same" if not diff_files else "differs in " + ", ".join(diff_files)
        pstatus = p.get("status", "-")
        if p.get("forced"):
            pstatus += " (forced)"
        row = {"id": sid, "refactoring": s["refactoring"], "place": f"{s['file']}:{p.get('line', g.get('line', '?'))}",
               "pluginAvailable": p.get("available"), "pluginStatus": pstatus, "pluginMessage": (p.get("message") or "").split("\n")[0],
               "pluginBuild": verdict(pbuild.get(sid)), "goplsStatus": g.get("status", "-"), "goplsMessage": (g.get("message") or g.get("done") or "").split("\n")[0],
               "goplsBuild": verdict(gbuild.get(sid)), "compare": same}
        rows.append(row)
    json.dump(rows, open(os.path.join(a.out, "refactor-summary.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("| id | refactoring | place | plugin: available / result / build | gopls: result / build | plugin vs gopls |")
    print("|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['id']} | {r['refactoring']} | {r['place']} | {'yes' if r['pluginAvailable'] else 'no'} / {r['pluginStatus']} / {r['pluginBuild']} | "
              f"{r['goplsStatus']} / {r['goplsBuild']} | {r['compare']} |")


if __name__ == "__main__":
    main()

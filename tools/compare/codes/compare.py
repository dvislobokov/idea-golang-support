"""Error-code coverage: go/types (oracle.jsonl) against what the plugin shows (codes-plugin.json) on the examples of codes.go.

    python tools/compare/codes/compare.py build/compare/codes build/compare/out/codes-plugin.json > build/compare/out/codes-table.md

Per example: the errors of go/types with the documented code (the site of the example); the plugin's problems on those lines.
Verdicts: ok (an ERROR on the line), warning (a lower level with the text of go/types on the line), miss (nothing about it: an
unrelated `Unused function` of the example does not count), no-site (go/types itself gives another code there: the example depends on the environment, e.g. cgo).
"""
import collections
import difflib
import json
import os
import re
import sys

root, plugin_json = sys.argv[1], sys.argv[2]
codes = json.load(open(os.path.join(root, "codes.json"), encoding="utf-8"))
plugin = json.load(open(plugin_json, encoding="utf-8"))
oracle = collections.defaultdict(list)
for line in open(os.path.join(root, "oracle.jsonl"), encoding="utf-8"):
    e = json.loads(line)
    if e["kind"] == "list":
        continue
    oracle[e["pkg"].rsplit("/", 1)[-1]].append(e)


def norm(s):
    return re.sub(r"\s+", " ", s.replace("…", "...")).strip().lower()


rows = []
for c in codes:
    for i in range(1, len(c["examples"]) + 1):
        ex = "%s_%d" % (c["name"], i)
        errs = oracle.get(ex, [])
        sites = [e for e in errs if e["code"] == c["name"]]
        probs = plugin.get(ex, [])
        if not sites:
            verdict, best, other = "no-site", None, ", ".join(sorted({e["code"] or e["kind"] for e in errs})) or "no error"
        else:
            lines = {e["line"] for e in sites}
            # a problem of the line that is about this error: an ERROR, or a lower level with the text of go/types
            similar = lambda p: difflib.SequenceMatcher(None, norm(p["message"]), norm(sites[0]["msg"])).ratio() >= 0.6
            on = [p for p in probs if p["line"] in lines and (p["severity"] == "ERROR" or similar(p))]
            errors = [p for p in on if p["severity"] == "ERROR"]
            pick = errors or on
            best = max(pick, key=lambda p: difflib.SequenceMatcher(None, norm(p["message"]), norm(sites[0]["msg"])).ratio()) if pick else None
            verdict = "ok" if errors else "warning" if on else "miss"
            other = ""
        rows.append({"code": c["name"], "example": ex, "verdict": verdict, "gotypes": sites[0]["msg"] if sites else other,
                     "plugin": ("%s: %s" % (best["severity"], best["message"])) if best else "; ".join("%d %s: %s" % (p["line"], p["severity"], p["message"]) for p in probs[:2]),
                     "text": round(difflib.SequenceMatcher(None, norm(best["message"]), norm(sites[0]["msg"])).ratio(), 2) if best and sites else None})

by_code = collections.OrderedDict()
order = ["ok", "warning", "miss", "no-site"]
for r in rows:
    by_code.setdefault(r["code"], []).append(r["verdict"])
code_verdict = {k: min(v, key=order.index) for k, v in by_code.items()}
count = collections.Counter(code_verdict.values())
print("# Error codes of go/types: plugin coverage\n")
print("Codes with examples: %d. By code (best example): %s. By example: %s.\n" % (
    len(code_verdict), ", ".join("%s %d" % (k, count[k]) for k in order if count[k]),
    ", ".join("%s %d" % (k, n) for k, n in collections.Counter(r["verdict"] for r in rows).most_common())))
print("Without an example in codes.go: %s.\n" % ", ".join(c["name"] for c in codes if not c["examples"]))
print("| code | example | verdict | go/types | plugin | text |")
print("|---|---|---|---|---|---|")
for r in sorted(rows, key=lambda r: (order.index(r["verdict"]), r["example"])):
    cell = lambda s: (s or "").replace("|", "\\|").replace("\n", " ")[:140]
    print("| %s | %s | %s | %s | %s | %s |" % (r["code"], r["example"], r["verdict"], cell(r["gotypes"]), cell(r["plugin"]), r["text"] if r["text"] is not None else ""))
json.dump(rows, open(os.path.join(os.path.dirname(plugin_json), "codes-verdicts.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)

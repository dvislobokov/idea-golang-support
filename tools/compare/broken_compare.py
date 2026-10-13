"""docs/compare part 1: joins the reference (broken_oracle.py: go/types oracle, go build -gcflags=-e, go vet) with what the plugin shows
(GoBrokenHarnessTest: broken.json, broken-baseline.json) and prints the per-breakage verdicts as JSON and a Markdown table.

    python tools/compare/broken_compare.py --oracle <oracle.jsonl> --plugin <broken.json> --baseline <broken-baseline.json> --out <verdicts.json> --md <table.md>

Matching is by (file, line, column, normalized text), never by line alone: five errors in different arguments of one call are five.
Verdicts per reference error: ok / wrong-text (same place, other message) / wrong-place (same file, other line or column within 3 lines,
similar text) / wrong-severity (found, but as a warning) / missing. The plugin's errors left over after the matching and after removing
what the same files show on the clean clone are extra: secondary (the same file and within the function of the breakage) or extra.
"""
import argparse
import collections
import difflib
import json
import re


def norm(msg):
    m = msg.lower().replace("`", "'").replace("\u2018", "'").replace("\u2019", "'")
    m = re.sub(r"\s+", " ", m.split("\n")[0]).strip()
    m = re.sub(r"\(\w+ of (\w+ )?type ", "(", m)  # go/types: (variable of type T) / (value of struct type T) -> (T)
    return m


def similar(a, b):
    a, b = norm(a), norm(b)
    return a == b or a in b or b in a or difflib.SequenceMatcher(None, a, b).ratio() >= 0.75


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--oracle", required=True)
    ap.add_argument("--plugin", required=True)
    ap.add_argument("--baseline", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--md", required=True)
    a = ap.parse_args()
    oracle = {r["id"]: r for r in map(json.loads, open(a.oracle, encoding="utf-8"))}
    plugin = {r["id"]: r for r in json.load(open(a.plugin, encoding="utf-8"))}
    baseline = json.load(open(a.baseline, encoding="utf-8"))
    base_keys = collections.Counter()
    for problems in baseline.values():
        for p in problems:
            base_keys[(p["file"], p["severity"], p["message"])] += 1
    verdicts, rows = [], []
    for bid in sorted(oracle):
        o, p = oracle[bid], plugin.get(bid)
        ref = [e for e in o.get("oracle", [])]
        vet_only = not ref
        if vet_only:  # e.g. unreachable code: the reference is go vet
            ref = [{"file": e["file"], "line": e["line"], "col": e["col"], "code": "vet", "msg": e["msg"]} for e in o.get("vet", []) if e["file"] == o["path"]]
        shown = list(p["problems"]) if p else []
        # what the same files show on the clean clone is not caused by the breakage
        left = collections.Counter(base_keys)
        fresh = []
        for q in shown:
            k = (q["file"], q["severity"], q["message"])
            if left[k] > 0:
                left[k] -= 1
            else:
                fresh.append(q)
        errors = [q for q in fresh if q["severity"] == "ERROR"]
        weaker = [q for q in fresh if q["severity"] != "ERROR"]
        used = set()
        result = []
        for e in ref:
            match, verdict = None, "missing"
            # the compiler (gc) and go/types sometimes put one error at different columns or word it differently: both are reference
            gc = [b for b in o.get("build", []) if b["file"] == e["file"] and b["line"] == e["line"]]
            places = {(e["line"], e["col"])} | {(b["line"], b["col"]) for b in gc}
            texts = [e["msg"]] + [b["msg"] for b in gc]
            e["gc"] = gc
            same = [i for i, q in enumerate(errors + (weaker if vet_only else [])) if i not in used and (errors + (weaker if vet_only else []))[i]["file"] == e["file"]]
            pool = errors + (weaker if vet_only else [])
            for i in same:  # exact place
                q = pool[i]
                if (q["line"], q["col"]) in places:
                    match, verdict = i, ("ok" if any(similar(q["message"], t) for t in texts) else "wrong-text")
                    break
            if match is None:
                for i in same:  # same text near
                    q = pool[i]
                    if abs(q["line"] - e["line"]) <= 3 and any(similar(q["message"], t) for t in texts):
                        match, verdict = i, "wrong-place"
                        break
            if match is None:
                for i in same:  # same line, other text and column
                    q = pool[i]
                    if q["line"] == e["line"]:
                        match, verdict = i, "wrong-place+text"
                        break
            if match is not None:
                used.add(match)
                q = pool[match]
                result.append({"ref": e, "verdict": verdict, "plugin": q})
                continue
            w = next((q for q in weaker if q["file"] == e["file"] and abs(q["line"] - e["line"]) <= 1 and similar(q["message"], e["msg"])), None)
            result.append({"ref": e, "verdict": "wrong-severity" if w else "missing", "plugin": w})
        extra = [q for i, q in enumerate(errors) if i not in used]
        line = o.get("line", 0)
        secondary = [q for q in extra if q["file"] == o["path"] and abs(q["line"] - line) <= 40]
        other = [q for q in extra if q not in secondary]
        verdicts.append({"id": bid, "category": o["category"], "path": o["path"], "line": line, "was": o["was"], "becomes": o["becomes"],
                         "vetOnly": vet_only, "build": o.get("build", []), "vet": [v for v in o.get("vet", [])],
                         "results": result, "secondary": secondary, "extra": other, "warnings": weaker, "millis": p["millis"] if p else None})
    json.dump(verdicts, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    def cell(s):
        return s.replace("|", "\\|").replace("\n", "⏎").replace("\t", "→")

    out = ["| # | Категория | Место | Поломка | Компилятор (go/types) | vet | Плагин | Вердикт | Вторичные / extra |", "|---|---|---|---|---|---|---|---|---|"]
    for v in verdicts:
        refs = "<br>".join(f"{r['ref']['line']}:{r['ref']['col']} `{cell(r['ref'].get('code') or '')}` {cell(r['ref']['msg'][:90])}" for r in v["results"]) or "—"
        mine = "<br>".join((f"{r['plugin']['line']}:{r['plugin']['col']} {cell(r['plugin']['message'][:90])}" if r["plugin"] else "—") for r in v["results"]) or "—"
        verdict = ", ".join(r["verdict"] for r in v["results"]) or "—"
        vet = "<br>".join(cell(f"{x['line']}:{x['col']} {x['msg'][:60]}") for x in v["vet"] if x["file"] == v["path"]) or "—"
        extra = "<br>".join(cell(f"{q['line']}:{q['col']} {q['message'][:70]}") for q in v["secondary"] + v["extra"]) or "—"
        out.append(f"| {v['id']} | {v['category']} | `{v['path']}:{v['line']}` | `{cell(v['was'][:50])}` → `{cell(v['becomes'][:50])}` | {refs} | {vet} | {mine} | {verdict} | {extra} |")
    open(a.md, "w", encoding="utf-8").write("\n".join(out) + "\n")
    c = collections.Counter(r["verdict"] for v in verdicts for r in v["results"])
    n = sum(c.values())
    print(f"reference errors: {n}; verdicts: {dict(c)}; secondary: {sum(len(v['secondary']) for v in verdicts)}; extra: {sum(len(v['extra']) for v in verdicts)}")


if __name__ == "__main__":
    main()

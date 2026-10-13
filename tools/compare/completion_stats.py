"""docs/compare part 2: statistics of the "restore the deleted identifier" measurement for the plugin (GoCompletionHarnessTest, one or
more chunks), gopls (gopls_completion.py) and GoLand (restore_complete.js, jsonl). Prints Markdown tables: top-1 / top-5 / not in list
and latency (median, p95) per side, per kind of place and per prefix, on the attempts all sides have; and a JSON of the plugin's misses
with what stood above the right answer (for the "what to fix" part).

    python tools/compare/completion_stats.py --plugin a.json [b.json ...] --gopls g.json --goland g.jsonl --out misses.json > tables.md
"""
import argparse
import collections
import json
import statistics


def load_plugin(paths):
    attempts = []
    for p in paths:
        attempts += json.load(open(p, encoding="utf-8"))["attempts"]
    return attempts


def key(a):
    return (a["site"], a["prefix"])


def pct(n, d):
    return f"{100.0 * n / d:.1f} %" if d else "—"


def p95(values):
    if not values:
        return 0
    v = sorted(values)
    return v[min(len(v) - 1, int(round(0.95 * (len(v) - 1))))]


def summary(rows):
    n = len(rows)
    top1 = sum(1 for a in rows if a["rank"] == 1)
    top5 = sum(1 for a in rows if 1 <= a["rank"] <= 5)
    miss = sum(1 for a in rows if a["rank"] < 0)
    ms = [a["millis"] for a in rows if a.get("millis", -1) >= 0]
    return n, top1, top5, miss, (statistics.median(ms) if ms else 0), p95(ms)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plugin", nargs="+", required=True)
    ap.add_argument("--gopls", required=True)
    ap.add_argument("--goland", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sides = {
        "плагин": load_plugin(a.plugin),
        "gopls": json.load(open(a.gopls, encoding="utf-8"))["attempts"],
        "GoLand": [json.loads(l) for l in open(a.goland, encoding="utf-8") if l.strip()],
    }
    for name in sides:
        sides[name] = {key(x): x for x in sides[name] if x["prefix"] >= 0 and x.get("note") not in ("site text mismatch", "timeout")}
    common = set.intersection(*(set(v) for v in sides.values()))
    print(f"Попыток (место × префикс), общих для трёх сторон: **{len(common)}** "
          f"(плагин {len(sides['плагин'])}, gopls {len(sides['gopls'])}, GoLand {len(sides['GoLand'])}).\n")

    def table(title, group):
        print(f"### {title}\n")
        print("| Группа | N | " + " | ".join(f"{s} top-1 | {s} top-5 | {s} нет" for s in sides) + " |")
        print("|---|---|" + "---|---|---|" * len(sides))
        groups = collections.defaultdict(list)
        for k in sorted(common):
            groups[group(sides["плагин"][k])].append(k)
        for g in sorted(groups, key=str):
            ks = groups[g]
            cells = []
            for s, rows in sides.items():
                n, t1, t5, miss, _, _ = summary([rows[k] for k in ks])
                cells += [pct(t1, n), pct(t5, n), pct(miss, n)]
            print(f"| {g} | {len(ks)} | " + " | ".join(cells) + " |")
        print()

    table("Всего", lambda x: "все")
    table("По префиксу", lambda x: f"префикс {x['prefix']}")
    table("По виду места", lambda x: x["kind"])
    table("По виду места и префиксу", lambda x: f"{x['kind']} / {x['prefix']}")
    table("По подвиду", lambda x: f"{x['kind']}: {x['sub']}")

    print("### Латентность, мс\n")
    print("| Сторона | медиана | p95 | макс |")
    print("|---|---|---|---|")
    for s, rows in sides.items():
        ms = [rows[k]["millis"] for k in common if rows[k].get("millis", -1) >= 0]
        print(f"| {s} | {statistics.median(ms):.0f} | {p95(ms)} | {max(ms)} |")
    print()

    # the plugin's misses where a reference side has the identifier first
    misses = []
    for k in sorted(common):
        p, g, l = sides["плагин"][k], sides["gopls"][k], sides["GoLand"][k]
        if p["rank"] != 1:
            misses.append({"site": k[0], "prefix": k[1], "file": p["file"], "line": p["line"], "ident": p["ident"], "kind": p["kind"], "sub": p["sub"],
                           "plugin": p["rank"], "pluginSize": p["size"], "pluginTop": p["top"], "gopls": g["rank"], "goplsTop": g.get("top", [])[:3],
                           "goland": l["rank"], "golandTop": l.get("top", [])[:3]})
    json.dump(misses, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    better = sum(1 for m in misses if m["gopls"] == 1 or m["goland"] == 1)
    print(f"Промахи плагина мимо top-1: {len(misses)}; из них у gopls или GoLand на первом месте: {better}.")


if __name__ == "__main__":
    main()

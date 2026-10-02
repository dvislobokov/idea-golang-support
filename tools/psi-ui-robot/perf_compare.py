"""Compares two perf.json files of the UI robot's performance mode (autotest.py --perf).

    python tools/ui-robot/perf_compare.py OLD.json NEW.json [--threshold 20]

Prints a Markdown table per metric: old and new median, the change of the median in percent, and old and new min..max. A metric
is marked SLOWER (FASTER for the reverse) when its median grew by more than the threshold (default 20 %) and by more than 20 ms,
and the min..max ranges of the two runs do not overlap; for MB the same with 5 MB, marked LARGER / SMALLER. A metric with one
sample per run gets the mark with a `?` and never fails the comparison. Live numbers are
noisy: compare runs on the same machine, run each side twice, and read a change as real only when it is larger than the spread
of two runs of the same build (see docs/TESTING.md "Performance mode").
Exit code 1 when anything is SLOWER or LARGER, so the script can gate a local check.
"""
import argparse
import json
import sys

MIN_DELTA = {"ms": 20.0, "MB": 5.0}
MARKS = {"ms": ("SLOWER", "FASTER"), "MB": ("LARGER", "SMALLER")}


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def num(v):
    if v is None:
        return "-"
    return "%.0f" % v if abs(v) >= 100 else "%.1f" % v


def span(m):
    return "-" if m.get("min") is None else "%s..%s" % (num(m["min"]), num(m["max"]))


def compare(a, b, threshold):
    """(change text, mark, is regression) for two measurements."""
    ma, mb = a["median"], b["median"]
    if ma is None or mb is None:
        return "", "FAILED" if mb is None and ma is not None else "", mb is None and ma is not None
    if ma <= 0:
        return "", "", False
    pct = (mb - ma) * 100.0 / ma
    unit = a.get("unit")
    if unit not in MIN_DELTA or abs(mb - ma) <= MIN_DELTA[unit] or abs(pct) <= threshold:
        return "%+.0f%%" % pct, "", False
    worse, better = MARKS[unit]
    mark = worse if pct > 0 else better
    if a["n"] <= 1 or b["n"] <= 1:
        # one sample per run (first completion, first open, P9 memory): too noisy to gate, shown with a question mark
        return "%+.0f%%" % pct, mark + "?", False
    if a["min"] <= b["max"] and b["min"] <= a["max"]:
        return "%+.0f%%" % pct, "(noise)", False
    return "%+.0f%%" % pct, mark, pct > 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("old")
    parser.add_argument("new")
    parser.add_argument("--threshold", type=float, default=20.0, help="percent change of the median that counts (default 20)")
    args = parser.parse_args(argv)
    old, new = load(args.old), load(args.new)
    for label, path, data in (("old", args.old, old), ("new", args.new, new)):
        meta = data.get("meta", {})
        print("%s: %s, %s, go-psi %s (%s)" % (label, path, meta.get("date"), meta.get("plugin"), meta.get("flags")))
    print()
    print("| Metric | unit | old median | new median | change | old min..max | new min..max | |")
    print("|---|---|---|---|---|---|---|---|")
    worse = 0
    om, nm = old["measurements"], new["measurements"]
    for k in list(om) + [k for k in nm if k not in om]:
        a, b = om.get(k), nm.get(k)
        if a is None or b is None:
            m = a or b
            print("| `%s` | %s | %s | %s | | | | %s |" % (k, m["unit"], num(a and a["median"]), num(b and b["median"]), "new" if a is None else "gone"))
            continue
        change, mark, regression = compare(a, b, args.threshold)
        worse += regression
        print("| `%s` | %s | %s | %s | %s | %s | %s | %s |" % (k, a["unit"], num(a["median"]), num(b["median"]), change, span(a), span(b), mark))
    oh, nh = old.get("heap", {}), new.get("heap", {})
    for point in list(oh) + [p for p in nh if p not in oh]:
        print("| heap used after GC, %s | MB | %s | %s | | | | |" % (point, oh.get(point, {}).get("usedMb", "-"), nh.get(point, {}).get("usedMb", "-")))
    failed = [c for c in new.get("checks", []) if not c["ok"]]
    if failed:
        print()
        print("new run: %d failed checks: %s" % (len(failed), "; ".join("%s %s" % (c["step"], c["check"]) for c in failed)))
    return 1 if worse else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

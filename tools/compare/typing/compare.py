#!/usr/bin/env python3
"""Plugin vs GoLand on the typing scenarios: a Markdown table id | category | scenario | GoLand | plugin | verdict.

Usage: python tools/compare/typing/compare.py PLUGIN_JSON GOLAND_JSON [--scenarios tools/compare/typing/scenarios.json] [--details]
  PLUGIN_JSON  build/compare/out/typing.json (GoTypingHarnessTest)
  GOLAND_JSON  the file typing_batch.js wrote in GoLand (__OUT__), or a directory of such files (typing_batch.sh writes one per chunk);
               "-" or a missing path = no reference yet (the GoLand column stays empty)
Verdicts: ok = the same text with the caret / selection markers, the same steps, the same list or hint text;
          нет = the plugin failed, gave nothing ("<no ...>", an empty list) or left the text as it was while GoLand changed it;
          иначе = both did something, differently; — = no GoLand result for the scenario.
--details appends, per scenario that is not ok, both results in full (a unified diff for texts).
For `gofmt: yes` scenarios the table also says whether each side matches gofmt (the gofmt column of scenarios.json).
"""
import argparse
import difflib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load_results(path):
    if not path or path == "-" or not os.path.exists(path):
        return {}, None
    if os.path.isdir(path):
        # the chunks typing_batch.sh writes (one robot call per few scenarios): merged, later files win
        merged, ide = {}, None
        for name in sorted(os.listdir(path)):
            if name.endswith(".json"):
                part, part_ide = load_results(os.path.join(path, name))
                merged.update(part)
                ide = ide or part_ide
        return merged, ide
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    results = data["results"] if isinstance(data, dict) else data
    return {r["id"]: r for r in results}, (data.get("ide") if isinstance(data, dict) else None)


def strip_markers(text):
    return (text or "").replace("<caret>", "").replace("<selection>", "").replace("</selection>", "")


def norm(s):
    return re.sub(r"\s+", " ", s or "").strip()


def empty(r):
    """Nothing to show: an error, an empty list, only placeholders like <no ...>."""
    if r is None or r.get("error"):
        return True
    items = [i for i in (r.get("items") or []) if not i.startswith("handler: ") and not i.startswith("count: 0")]
    vals = items + ([r["value"]] if r.get("value") else [])
    if r.get("text") is not None:
        return False
    return not vals or all(v.startswith("<no ") or v.startswith("<empty") for v in vals)


def key_of(r):
    """What is compared: text + steps for edits; items (minus the handler line, whose class differs) or value for queries."""
    steps = [(s["line"], s["selection"]) for s in (r.get("steps") or [])]
    items = [i for i in (r.get("items") or []) if not i.startswith("handler: ")]
    if r.get("query") == "quickdoc":
        return (r.get("text"), steps, norm(r.get("value")))
    return (r.get("text"), steps, items, norm(r.get("value")) if not items else None)


def caret_line(text):
    if not text:
        return ""
    at = text.find("<caret>")
    if at < 0:
        return text.splitlines()[0] if text else ""
    start = text.rfind("\n", 0, at) + 1
    end = text.find("\n", at)
    return text[start:end if end >= 0 else len(text)]


def first_diff_line(a, b):
    la, lb = (a or "").split("\n"), (b or "").split("\n")
    for i in range(max(len(la), len(lb))):
        x = la[i] if i < len(la) else "<eof>"
        y = lb[i] if i < len(lb) else "<eof>"
        if x != y:
            return i + 1, x, y
    return None


def cell(s, limit=110):
    s = (s or "").replace("\t", "→").replace("|", "\\|").replace("\n", "⏎")
    return s if len(s) <= limit else s[:limit - 1] + "…"


def summary(r, other=None):
    if r is None:
        return ""
    if r.get("error"):
        return "ERROR " + r["error"]
    if r.get("text") is not None and r.get("query") in (None, "surround"):
        if other is not None and other.get("text") is not None and other["text"] != r["text"]:
            d = first_diff_line(other["text"], r["text"])
            if d:
                return f"L{d[0]}: `{d[2].strip()}`"
        line = caret_line(r["text"])
        if r.get("steps"):
            line = "; ".join(f"{s['line'].strip()}" + (f" [{s['selection']}]" if s.get("selection") and r["category"] == "select" else "") for s in r["steps"][-3:])
        prefix = ("items: " + ", ".join(r["items"]) + " → ") if r.get("items") else ""
        return prefix + f"`{line.strip()}`"
    if r.get("query") == "quickdoc":
        return r.get("value") or ""
    items = [i for i in (r.get("items") or []) if not i.startswith("handler: ")]
    if items:
        return "; ".join(items)
    return r.get("value") or ""


def verdict(p, g, before):
    if g is None:
        return "—"
    if p is None or p.get("error"):
        return "нет"
    if g.get("error"):
        return "— (GoLand error)"
    if key_of(p) == key_of(g):
        return "ok"
    if empty(p) and not empty(g):
        return "нет"
    if p.get("text") is not None and g.get("text") is not None and before is not None:
        if strip_markers(p["text"]) == strip_markers(before) and strip_markers(g["text"]) != strip_markers(before):
            return "нет"
    return "иначе"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("plugin")
    ap.add_argument("goland", nargs="?", default="-")
    ap.add_argument("--scenarios", default=os.path.join(HERE, "scenarios.json"))
    ap.add_argument("--details", action="store_true")
    args = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    plugin, plugin_ide = load_results(args.plugin)
    goland, goland_ide = load_results(args.goland)
    scenarios = []
    if os.path.exists(args.scenarios):
        with open(args.scenarios, encoding="utf-8") as f:
            scenarios = json.load(f)
    order = [s["id"] for s in scenarios] or list(plugin)
    by_id = {s["id"]: s for s in scenarios}
    counts = {}
    print(f"Plugin: {plugin_ide or '?'}; GoLand: {goland_ide or 'no result file'}\n")
    print("| id | category | scenario | GoLand | plugin | verdict |")
    print("|---|---|---|---|---|---|")
    details = []
    for sid in order:
        s = by_id.get(sid, {})
        p, g = plugin.get(sid), goland.get(sid)
        if p is None and g is None:
            continue
        before = s.get("code")
        v = verdict(p, g, before)
        if s.get("gofmt") and s.get("gofmtResult"):
            ref = s["gofmtResult"]
            marks = []
            for name, r in (("GoLand", g), ("plugin", p)):
                if r and r.get("text") is not None:
                    marks.append(f"{name} {'=' if strip_markers(r['text']) == strip_markers(ref) else '≠'} gofmt")
            if marks:
                v += " (" + ", ".join(marks) + ")"
        counts[v.split(" ")[0]] = counts.get(v.split(" ")[0], 0) + 1
        title = s.get("title") or (p or g or {}).get("title", "")
        cat = s.get("category") or (p or g or {}).get("category", "")
        print(f"| {sid} | {cat} | {cell(title, 70)} | {cell(summary(g))} | {cell(summary(p, g))} | {v} |")
        if args.details and not v.startswith("ok"):
            details.append((sid, title, p, g, s))
    print("\n" + ", ".join(f"{k}: {n}" for k, n in sorted(counts.items())))
    for sid, title, p, g, s in details:
        print(f"\n### {sid} {title}\n")
        for note in s.get("notes", []):
            print(f"note: {note}")
        if p and p.get("notes"):
            print("plugin notes: " + "; ".join(p["notes"]))
        if g and g.get("notes"):
            print("GoLand notes: " + "; ".join(g["notes"]))
        if p and g and p.get("text") is not None and g.get("text") is not None:
            print("```diff")
            for line in difflib.unified_diff(g["text"].split("\n"), p["text"].split("\n"), "GoLand", "plugin", lineterm="", n=2):
                print(line)
            print("```")
        for name, r in (("GoLand", g), ("plugin", p)):
            if r is None:
                continue
            body = {k: r.get(k) for k in ("error", "steps", "items", "value") if r.get(k)}
            if r.get("text") is not None and not (p and g and p.get("text") is not None and g.get("text") is not None):
                body["text"] = r["text"]
            if r.get("query") == "quickdoc" and "items" in body:
                body["items"] = [i[:600] for i in body["items"]]
            if body:
                print(f"{name}:\n```\n{json.dumps(body, ensure_ascii=False, indent=1)}\n```")
        if s.get("gofmtResult"):
            print(f"gofmt:\n```go\n{s['gofmtResult']}```")


if __name__ == "__main__":
    main()

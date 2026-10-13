#!/usr/bin/env python3
"""scenarios.txt -> scenarios.json, read by both stands (GoTypingHarnessTest.kt parses the txt by the same rules and can be diffed
against this file; typing_batch.js in GoLand reads the JSON).

Usage: python tools/compare/typing/to_json.py [scenarios.txt] [out.json]
For scenarios with `gofmt: yes` the field "gofmt" holds what `gofmt` (on PATH) makes of the before-text with the markers removed,
and "gofmtCaret" the same text with <caret> put back at the same token (best effort: before the same following text).
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN = re.compile(r"\{[A-Z_]+(?::[^}]*)?\}")
SECTION = re.compile(r"^--- (code|extra|paste|keys)(?: (.*))?$")
HEADER = re.compile(r"^=== (\S+) \| ([^|]+?) \| (.+)$")


def tokens(keys):
    """Literal characters become one-character tokens; {NAME} / {NAME:arg} stay whole."""
    out, i = [], 0
    while i < len(keys):
        m = TOKEN.match(keys, i)
        if m:
            out.append(m.group(0))
            i = m.end()
        else:
            out.append(keys[i])
            i += 1
    return out


def section_text(lines):
    while lines and lines[-1].strip() == "":
        lines = lines[:-1]
    return "\n".join(lines)


def parse(text):
    scenarios, cur, section, buf = [], None, None, []

    def flush():
        nonlocal section, buf
        if cur is None or section is None:
            section, buf = None, []
            return
        kind, arg = section
        body = section_text(buf)
        if kind == "code":
            cur["code"] = body + "\n"
        elif kind == "extra":
            cur["extras"].append({"name": arg.strip(), "text": body + "\n"})
        elif kind == "paste":
            cur["paste"] = body if (arg or "").strip() == "inline" else body + "\n"
        elif kind == "keys":
            cur["keys"] = tokens(body.replace("\n", ""))
        section, buf = None, []

    for line in text.split("\n"):
        line = line.rstrip("\r")
        h = HEADER.match(line)
        if h:
            flush()
            cur = {"id": h.group(1), "category": h.group(2).strip(), "title": h.group(3).strip(), "file": "a.go", "steps": False,
                   "gofmt": False, "query": None, "notes": [], "code": "", "extras": [], "paste": None, "keys": []}
            scenarios.append(cur)
            continue
        s = SECTION.match(line)
        if s and cur is not None:
            flush()
            section = (s.group(1), s.group(2))
            continue
        if line.startswith("#"):
            continue  # a comment everywhere, sections included (no Go line starts with '#' at column 0)
        if section is not None:
            buf.append(line)
            continue
        if cur is None or line.startswith("#") or not line.strip():
            continue
        key, _, value = line.partition(":")
        key, value = key.strip(), value.strip()
        if key == "file":
            cur["file"] = value
        elif key == "steps":
            cur["steps"] = value == "yes"
        elif key == "gofmt":
            cur["gofmt"] = value == "yes"
        elif key == "query":
            cur["query"] = value
        elif key == "note":
            cur["notes"].append(value)
        else:
            raise SystemExit(f"{cur['id']}: unknown header line {line!r}")
    flush()
    ids = [s["id"] for s in scenarios]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup:
        raise SystemExit(f"duplicate ids: {sorted(dup)}")
    for s in scenarios:
        if "<caret>" not in s["code"]:
            raise SystemExit(f"{s['id']}: no <caret> in the code")
    return scenarios


def strip_markers(code):
    return code.replace("<caret>", "").replace("<selection>", "").replace("</selection>", "")


def gofmt(code):
    try:
        p = subprocess.run(["gofmt"], input=code.encode("utf-8"), capture_output=True, timeout=30)
    except FileNotFoundError:
        return None, "gofmt not on PATH"
    if p.returncode != 0:
        return None, p.stderr.decode("utf-8", "replace").strip()
    return p.stdout.decode("utf-8"), None


def caret_back(original, formatted):
    """Put <caret> before the first non-blank text that followed it in the original (what a formatter keeps)."""
    at = original.index("<caret>")
    before = re.sub(r"\s+", "", strip_markers(original[:at]))
    # the caret goes before the n-th non-blank character, n = non-blank characters before it in the original (formatters move blanks only)
    n = len(before)
    seen = 0
    for i, ch in enumerate(formatted):
        if ch.isspace():
            continue
        if seen == n:
            return formatted[:i] + "<caret>" + formatted[i:]
        seen += 1
    return formatted + "<caret>"


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "scenarios.txt")
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "scenarios.json")
    with open(src, encoding="utf-8", newline="") as f:
        scenarios = parse(f.read())
    for s in scenarios:
        if s["gofmt"]:
            out, err = gofmt(strip_markers(s["code"]))
            s["gofmtResult"] = caret_back(s["code"], out) if out is not None else None
            if err:
                s["gofmtError"] = err
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        json.dump(scenarios, f, ensure_ascii=False, indent=1)
    cats = {}
    for s in scenarios:
        cats[s["category"]] = cats.get(s["category"], 0) + 1
    print(f"{len(scenarios)} scenarios -> {dst}: " + ", ".join(f"{k} {v}" for k, v in cats.items()))


if __name__ == "__main__":
    main()

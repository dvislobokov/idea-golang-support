#!/usr/bin/env python3
"""Compare `go vet` findings with the plugin's inspections (MIGRATION step 13A gate: ours must cover vet's).

Usage:
    uv run tools/vet/compare.py --module <dir> --sarif <go-inspect.sarif> [--allow <file>] [--root <dir>]
    uv run tools/vet/compare.py --vet-json <saved vet output> --sarif <file> --root <dir>

--module runs `go vet -json ./...` in <dir>; --vet-json reads a saved copy of that output instead (stdout and stderr together;
`# package` header lines and non-JSON lines are skipped). The SARIF report comes from the headless runner `tools/ci/go-inspect.sh`
(docs/CI.md): `ruleId` is the inspection short name, `GoRules` results carry the rule id as a `[govet:x]` message prefix.

Findings are matched per file and line (column and message are ignored). vet paths are made relative to the SARIF `%SRCROOT%`,
else to --root, else to --module. Output: vet-only findings (missing in ours), ours-only findings of mapped inspections
(informational), counts per analyzer. Exit code 1 when a mapped analyzer has a vet-only finding not listed in --allow,
2 on bad input. The allow file has one exception per line, `path:line analyzer reason`; `#` starts a comment.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlparse

# vet analyzer -> the plugin findings that cover it: an inspection short name, or `GoRules[rule id]` for the lint rule engine.
MAPPING: dict[str, list[str]] = {
    "appends": ["GoRules[govet:appends]"],
    "assign": ["GoSelfAssignment"],
    "atomic": ["GoRules[govet:atomic]"],
    "bools": ["GoRules[govet:bools]"],
    "buildtag": ["GoBuildConstraint"],
    "composites": ["GoRules[govet:composites]"],
    "copylocks": ["GoCopyLocks"],
    "defers": ["GoRules[govet:defers]"],
    "directive": ["GoRules[govet:directive]", "GoEmbedDirective"],
    "errorsas": ["GoErrorsPackage"],
    "hostport": ["GoRules[govet:hostport]"],
    "httpmux": ["GoRules[govet:httpmux]"],
    "httpresponse": ["GoDeferBeforeErrorCheck"],
    "ifaceassert": ["GoRules[govet:ifaceassert]"],
    "loopclosure": ["GoLoopClosure"],
    "lostcancel": ["GoLostCancel"],
    "nilfunc": ["GoRules[govet:nilfunc]", "GoDfaConstantCondition"],
    "nilness": ["GoNilDereference", "GoDfaConstantCondition", "GoImpossibleNilCheck"],
    "printf": ["GoPrintFunctions"],
    "shadow": ["GoShadowedError", "GoShadowedVar"],
    "shift": ["GoRules[govet:shift]"],
    "sigchanyzer": ["GoRules[govet:sigchanyzer]"],
    "slog": ["GoRules[govet:slog]"],
    "sortslice": ["GoRules[govet:sortslice]"],
    "stdmethods": ["GoRules[govet:stdmethods]"],
    "stdversion": ["GoRules[govet:stdversion]"],
    "stringintconv": ["GoRules[govet:stringintconv]"],
    "structtag": ["GoStructTag"],
    "testinggoroutine": ["GoTestingGoroutine"],
    "tests": ["GoRules[govet:tests]"],
    "timeformat": ["GoTimeLayout"],
    "unmarshal": ["GoRules[govet:unmarshal]"],
    "unreachable": ["GoUnreachableCode"],
    "unsafeptr": ["GoRules[govet:unsafeptr]"],
    "unusedresult": ["GoUnusedResult"],
    "waitgroup": ["GoWaitGroupAddInGoroutine"],
}

# Analyzers of `go vet` the plugin does not check (assembly and cgo; see docs/LINT-RULES.md).
NOT_COVERED = {"asmdecl", "cgocall", "framepointer"}

RULE_PREFIX = re.compile(r"^\[([\w.:-]+)]")


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    source: str  # vet analyzer, or our finding id (`GoX` / `GoRules[govet:x]`)
    message: str


def parse_vet(text: str) -> list[Finding]:
    """Findings of `go vet -json` output: JSON objects `{pkg: {analyzer: [{posn, message}] | {error}}}` between `#` header lines."""
    body = "\n".join(line for line in text.splitlines() if not line.startswith("#"))
    decoder = json.JSONDecoder()
    result: list[Finding] = []
    i = 0
    while True:
        i = body.find("{", i)
        if i < 0:
            break
        try:
            obj, end = decoder.raw_decode(body, i)
        except json.JSONDecodeError:
            i += 1
            continue
        i = end
        if not isinstance(obj, dict):
            continue
        for analyzers in obj.values():
            if not isinstance(analyzers, dict):
                continue
            for analyzer, diagnostics in analyzers.items():
                if not isinstance(diagnostics, list):
                    continue  # {"error": "..."}: the analyzer failed on the package
                for d in diagnostics:
                    pos = split_posn(d.get("posn", ""))
                    if pos:
                        result.append(Finding(pos[0], pos[1], analyzer, d.get("message", "")))
    return result


def split_posn(posn: str) -> tuple[str, int] | None:
    """`file:line:col` or `file:line` (Windows drive letters included) -> (file, line)."""
    m = re.match(r"^(.*?):(\d+)(?::\d+)?$", posn)
    return (m.group(1), int(m.group(2))) if m else None


def parse_sarif(text: str) -> tuple[list[Finding], str | None]:
    """Results of a go-inspect SARIF report and its `%SRCROOT%` directory (or None)."""
    doc = json.loads(text)
    findings: list[Finding] = []
    root = None
    for run in doc.get("runs", []):
        base = run.get("originalUriBaseIds", {}).get("%SRCROOT%", {}).get("uri")
        if base and root is None:
            root = uri_to_path(base)
        for r in run.get("results", []):
            rule = r.get("ruleId", "")
            message = r.get("message", {}).get("text", "")
            if rule == "GoRules":
                m = RULE_PREFIX.match(message)
                if m:
                    rule = f"GoRules[{m.group(1)}]"
            for loc in r.get("locations", [])[:1]:
                phys = loc.get("physicalLocation", {})
                uri = phys.get("artifactLocation", {}).get("uri")
                line = phys.get("region", {}).get("startLine")
                if uri and line:
                    findings.append(Finding(unquote(uri), int(line), rule, message))
    return findings, root


def uri_to_path(uri: str) -> str:
    if not uri.startswith("file:"):
        return uri
    path = unquote(urlparse(uri).path)
    if re.match(r"^/[A-Za-z]:", path):
        path = path[1:]
    return path


def normalize(path: str, root: str | None) -> str:
    """A path relative to [root] with forward slashes; absolute paths outside it stay absolute."""
    p = path.replace("\\", "/")
    if root and (os.path.isabs(path) or re.match(r"^[A-Za-z]:/", p)):
        r = root.replace("\\", "/").rstrip("/")
        if os.name == "nt" or re.match(r"^[A-Za-z]:", r):
            if p.lower().startswith(r.lower() + "/"):
                return p[len(r) + 1:]
        elif p.startswith(r + "/"):
            return p[len(r) + 1:]
    return str(PurePosixPath(p)) if not p.startswith("./") else p[2:]


def parse_allow(text: str) -> set[tuple[str, int, str]]:
    """`path:line analyzer reason` per line -> {(path, line, analyzer)}."""
    allowed = set()
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split(None, 2)
        pos = split_posn(parts[0])
        if len(parts) < 2 or not pos:
            raise ValueError(f"bad allow line: {raw!r} (want `path:line analyzer reason`)")
        allowed.add((normalize(pos[0], None), pos[1], parts[1]))
    return allowed


@dataclass
class Report:
    vet_only: list[Finding]  # mapped analyzers, not allowed: these fail the gate
    allowed: list[Finding]
    ours_only: list[Finding]
    not_covered: list[Finding]  # vet findings of analyzers without a mapping
    counts: dict[str, tuple[int, int, int]]  # analyzer -> (vet, matched, vet-only)

    @property
    def failed(self) -> bool:
        return bool(self.vet_only)


def compare(vet: list[Finding], ours: list[Finding], root: str | None, allowed: set[tuple[str, int, str]] = frozenset()) -> Report:
    ours_at: dict[tuple[str, int], set[str]] = defaultdict(set)
    for f in ours:
        ours_at[(normalize(f.path, root), f.line)].add(f.source)
    vet_only, allowed_hits, not_covered = [], [], []
    matched_ours: set[tuple[str, int, str]] = set()
    counts: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])
    for f in vet:
        path = normalize(f.path, root)
        c = counts[f.source]
        c[0] += 1
        ids = MAPPING.get(f.source)
        if ids is None:
            not_covered.append(Finding(path, f.line, f.source, f.message))
            continue
        hit = ours_at.get((path, f.line), set()) & set(ids)
        if hit:
            c[1] += 1
            matched_ours.update((path, f.line, h) for h in hit)
        elif (path, f.line, f.source) in allowed:
            allowed_hits.append(Finding(path, f.line, f.source, f.message))
        else:
            c[2] += 1
            vet_only.append(Finding(path, f.line, f.source, f.message))
    mapped_ids = {i for ids in MAPPING.values() for i in ids}
    ours_only = sorted(
        {Finding(normalize(f.path, root), f.line, f.source, f.message) for f in ours
         if f.source in mapped_ids and (normalize(f.path, root), f.line, f.source) not in matched_ours},
        key=lambda f: (f.path, f.line, f.source))
    key = lambda f: (f.path, f.line, f.source)
    return Report(sorted(vet_only, key=key), sorted(allowed_hits, key=key), ours_only, sorted(not_covered, key=key),
                  {a: (v[0], v[1], v[2]) for a, v in sorted(counts.items())})


def render(report: Report) -> str:
    out = [f"== vet-only ({len(report.vet_only)}): vet reports, the plugin does not =="]
    out += [f"{f.path}:{f.line}  {f.source}  {f.message}  (expected: {', '.join(MAPPING[f.source])})" for f in report.vet_only]
    if report.allowed:
        out.append(f"== allowed ({len(report.allowed)}) ==")
        out += [f"{f.path}:{f.line}  {f.source}  {f.message}" for f in report.allowed]
    out.append(f"== ours-only ({len(report.ours_only)}, informational) ==")
    out += [f"{f.path}:{f.line}  {f.source}  {f.message}" for f in report.ours_only]
    if report.not_covered:
        out.append(f"== not covered analyzers ({len(report.not_covered)} findings) ==")
        out += [f"{f.path}:{f.line}  {f.source}  {f.message}" for f in report.not_covered]
    out.append("== counts per analyzer: vet / matched / vet-only ==")
    for analyzer, (v, m, o) in report.counts.items():
        tag = "" if analyzer in MAPPING else "  (not covered)"
        out.append(f"{analyzer:<18} {v:>5} {m:>5} {o:>5}{tag}")
    out.append("FAIL: vet-only findings of covered analyzers" if report.failed else "OK: every vet finding of covered analyzers is ours too")
    return "\n".join(out)


def run_vet(module: str) -> str:
    p = subprocess.run(["go", "vet", "-json", "./..."], cwd=module, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.stdout + "\n" + p.stderr


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Compare go vet findings with the plugin's SARIF report.")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--module", help="module directory: runs `go vet -json ./...` there")
    src.add_argument("--vet-json", help="saved `go vet -json` output (stdout and stderr)")
    ap.add_argument("--sarif", required=True, help="SARIF report of tools/ci/go-inspect.sh")
    ap.add_argument("--allow", help="exceptions, one `path:line analyzer reason` per line")
    ap.add_argument("--root", help="directory the paths are relative to (default: SARIF %%SRCROOT%%, else --module)")
    args = ap.parse_args(argv)
    try:
        vet_text = run_vet(args.module) if args.module else Path(args.vet_json).read_text(encoding="utf-8")
        ours, sarif_root = parse_sarif(Path(args.sarif).read_text(encoding="utf-8"))
        allowed = parse_allow(Path(args.allow).read_text(encoding="utf-8")) if args.allow else set()
    except (OSError, ValueError) as e:
        print(f"compare.py: {e}", file=sys.stderr)
        return 2
    root = args.root or sarif_root or (str(Path(args.module).resolve()) if args.module else None)
    report = compare(parse_vet(vet_text), ours, root, allowed)
    print(render(report))
    return 1 if report.failed else 0


if __name__ == "__main__":
    sys.exit(main())

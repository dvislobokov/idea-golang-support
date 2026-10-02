"""Turns the deps.dev export into `modules.lock`: the pinned list of modules to download.

Input CSV columns (from the BigQuery query in tools/ml/README.md):
module, version, licenses, direct_dependents, stars, repo
"""

from __future__ import annotations

import csv
import re
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

from . import goversion
from .licenses import PERMISSIVE, spdx_list_allowed

LOCK_FIELDS = ("module", "version", "licenses", "direct_dependents", "stars", "reason")

# Always part of the corpus: the extended standard library, BSD-3 like GOROOT.
ALWAYS_PREFIXES = ("golang.org/x/",)


@dataclass(frozen=True)
class Candidate:
    module: str
    version: str
    licenses: tuple[str, ...]
    direct_dependents: int
    stars: int


@dataclass(frozen=True)
class Locked:
    candidate: Candidate
    reason: str


@dataclass(frozen=True)
class SelectionReport:
    total: int
    rejected_license: int
    rejected_version: int
    rejected_pattern: int
    selected: int


def read_depsdev_csv(path: Path) -> list[Candidate]:
    with path.open(newline="", encoding="utf-8") as f:
        return [_candidate(row) for row in csv.DictReader(f)]


def _candidate(row: dict[str, str]) -> Candidate:
    licenses = tuple(s.strip() for s in (row.get("licenses") or "").split("|") if s.strip())
    return Candidate(
        module=row["module"].strip(),
        version=row["version"].strip(),
        licenses=licenses,
        direct_dependents=_int(row.get("direct_dependents")),
        stars=_int(row.get("stars")),
    )


def _int(value: str | None) -> int:
    try:
        return int(value or 0)
    except ValueError:
        return 0


def select(
    candidates: Iterable[Candidate],
    *,
    top_dependents: int,
    top_stars: int,
    allowed: frozenset[str] = PERMISSIVE,
    exclude: Iterable[str] = (),
) -> tuple[list[Locked], SelectionReport]:
    """Libraries by direct dependents, then applications by stars, plus golang.org/x.

    Stars catch applications (kubernetes, hugo, ...) that nobody imports but that hold a large
    share of real-world Go code; a corpus of libraries only would skew towards library style.
    """
    patterns = [re.compile(p) for p in exclude]
    total = rej_license = rej_version = rej_pattern = 0
    eligible: list[Candidate] = []
    for c in candidates:
        total += 1
        if not goversion.is_valid(c.version):
            rej_version += 1
        elif not spdx_list_allowed(c.licenses, allowed):
            rej_license += 1
        elif any(p.search(c.module) for p in patterns):
            rej_pattern += 1
        else:
            eligible.append(c)

    chosen: dict[str, Locked] = {}

    def add(c: Candidate, reason: str) -> None:
        if c.module not in chosen:
            chosen[c.module] = Locked(c, reason)

    for c in eligible:
        if c.module.startswith(ALWAYS_PREFIXES):
            add(c, "always")
    by_deps = sorted(eligible, key=lambda c: (-c.direct_dependents, c.module))
    for c in [c for c in by_deps if c.direct_dependents > 0][:top_dependents]:
        add(c, "dependents")
    remaining = [c for c in eligible if c.module not in chosen and c.stars > 0]
    for c in sorted(remaining, key=lambda c: (-c.stars, c.module))[:top_stars]:
        add(c, "stars")

    locked = sorted(chosen.values(), key=lambda lk: lk.candidate.module)
    return locked, SelectionReport(total, rej_license, rej_version, rej_pattern, len(locked))


def write_lock(path: Path, locked: Iterable[Locked]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(LOCK_FIELDS)
        for lk in locked:
            c = lk.candidate
            w.writerow([c.module, c.version, " | ".join(c.licenses), c.direct_dependents, c.stars, lk.reason])


def read_lock(path: Path) -> list[Locked]:
    with path.open(newline="", encoding="utf-8") as f:
        return [Locked(_candidate(row), row.get("reason", "")) for row in csv.DictReader(f)]

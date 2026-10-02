"""License policy: which modules may enter the training corpus.

Deliberately conservative: an unrecognised license text or a license expression that cannot be
parsed excludes the module. Only permissive licenses are allowed by default.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from pathlib import Path

PERMISSIVE: frozenset[str] = frozenset(
    {
        "MIT",
        "MIT-0",
        "BSD-2-Clause",
        "BSD-3-Clause",
        "Apache-2.0",
        "ISC",
        "0BSD",
        "Unlicense",
        "CC0-1.0",
        "Zlib",
        "PostgreSQL",
    }
)

_LICENSE_FILE = re.compile(r"^(licen[cs]e|copying)([._-].*)?$", re.IGNORECASE)


_POSTGRESQL = (
    "permission to use, copy, modify, and distribute this software and its documentation for any "
    "purpose, without fee, and without a written agreement is hereby granted"
)

_COPYLEFT = (
    ("mozilla public license", "MPL-2.0"),
    ("gnu affero general public license", "AGPL-3.0"),
    ("gnu lesser general public license", "LGPL"),
    ("gnu library general public license", "LGPL"),
    ("gnu general public license", "GPL"),
)


def classify_text(text: str) -> str | None:
    """Maps a license file to an SPDX id by its characteristic wording; None if unrecognised.

    Copyleft markers are checked first so that a file mentioning both a GPL and a permissive
    license is never classified as permissive. Among copyleft licenses the name that appears
    first wins: the MPL text names the GNU licenses as "Secondary Licenses" and GPLv3 refers to
    the AGPL, so mere presence of a name says nothing.
    """
    t = " ".join(text.lower().split())
    found = [(pos, spdx) for name, spdx in _COPYLEFT if (pos := t.find(name)) >= 0]
    if found:
        return min(found)[1]
    if "apache license" in t and "version 2.0" in t:
        return "Apache-2.0"
    if "permission is hereby granted, free of charge" in t:
        return "MIT"
    if "redistribution and use in source and binary forms" in t:
        if "neither the name" in t or "names of its contributors" in t or "name of the copyright holder" in t:
            return "BSD-3-Clause"
        return "BSD-2-Clause"
    if "permission to use, copy, modify, and/or distribute this software for any purpose" in t:
        return "ISC"
    if "permission to use, copy, modify, and distribute this software for any purpose" in t:
        return "ISC"
    if _POSTGRESQL in t:
        return "PostgreSQL"
    if "this is free and unencumbered software released into the public domain" in t:
        return "Unlicense"
    if "this software is provided 'as-is'" in t and "altered source versions must be plainly marked" in t:
        return "Zlib"
    return None


def is_license_file(name: str) -> bool:
    return _LICENSE_FILE.match(name) is not None


def license_files(module_root: Path) -> list[Path]:
    """License files at the module root (LICENSE, LICENSE.md, COPYING, ...)."""
    if not module_root.is_dir():
        return []
    return sorted(p for p in module_root.iterdir() if p.is_file() and is_license_file(p.name))


def classify_module(module_root: Path) -> list[str | None]:
    """One classification per license file at the module root; empty if there is none."""
    out: list[str | None] = []
    for f in license_files(module_root):
        try:
            out.append(classify_text(f.read_text(encoding="utf-8", errors="replace")))
        except OSError:
            out.append(None)
    return out


def ids_allowed(ids: Iterable[str | None], allowed: frozenset[str] = PERMISSIVE) -> bool:
    """True when there is at least one license and every one of them is allowed."""
    ids = list(ids)
    return bool(ids) and all(i is not None and i in allowed for i in ids)


# SPDX expressions (deps.dev reports one or more per version) ---------------------------------

_TOKEN = re.compile(r"\s*(\(|\)|[A-Za-z0-9.+:-]+)")


class _Parser:
    def __init__(self, expr: str, allowed: frozenset[str]):
        self.tokens = _tokenize(expr)
        self.pos = 0
        self.allowed = allowed

    def peek(self) -> str | None:
        return self.tokens[self.pos] if self.pos < len(self.tokens) else None

    def take(self) -> str:
        tok = self.peek()
        if tok is None:
            raise ValueError("unexpected end of expression")
        self.pos += 1
        return tok

    def parse(self) -> bool:
        value = self.or_expr()
        if self.peek() is not None:
            raise ValueError(f"unexpected token {self.peek()!r}")
        return value

    def or_expr(self) -> bool:
        value = self.and_expr()
        while (tok := self.peek()) is not None and tok.upper() == "OR":
            self.take()
            value = self.and_expr() or value
        return value

    def and_expr(self) -> bool:
        value = self.atom()
        while (tok := self.peek()) is not None and tok.upper() == "AND":
            self.take()
            value = self.atom() and value
        return value

    def atom(self) -> bool:
        tok = self.take()
        if tok == "(":
            value = self.or_expr()
            if self.take() != ")":
                raise ValueError("missing )")
            return value
        if tok in (")",) or tok.upper() in ("AND", "OR", "WITH"):
            raise ValueError(f"unexpected token {tok!r}")
        if (nxt := self.peek()) is not None and nxt.upper() == "WITH":
            self.take()
            self.take()  # the exception id never makes a license more permissive than its base
        return tok.removesuffix("+") in self.allowed


def _tokenize(expr: str) -> list[str]:
    tokens: list[str] = []
    pos = 0
    expr = expr.strip()
    while pos < len(expr):
        m = _TOKEN.match(expr, pos)
        if m is None:
            raise ValueError(f"bad character at {pos} in {expr!r}")
        tokens.append(m.group(1))
        pos = m.end()
    return tokens


def spdx_allowed(expr: str, allowed: frozenset[str] = PERMISSIVE) -> bool:
    """Evaluates an SPDX license expression against the allow-list; unparsable means False."""
    if not expr.strip():
        return False
    try:
        return _Parser(expr, allowed).parse()
    except ValueError:
        return False


def spdx_list_allowed(exprs: Iterable[str], allowed: frozenset[str] = PERMISSIVE) -> bool:
    """Several expressions found for one version all apply at once, so each must be allowed."""
    exprs = [e for e in exprs if e.strip()]
    return bool(exprs) and all(spdx_allowed(e, allowed) for e in exprs)

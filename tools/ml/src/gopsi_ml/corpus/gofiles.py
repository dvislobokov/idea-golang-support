"""Per-file facts about Go sources and the deterministic train/valid/test split."""

from __future__ import annotations

import hashlib
import re
from pathlib import PurePosixPath

# https://go.dev/s/generatedcode: the marker must appear before the first non-comment text.
_GENERATED = re.compile(r"^// Code generated .* DO NOT EDIT\.$")
_CGO_IMPORT = re.compile(r'^\s*import\s+"C"\s*$|^\s*"C"\s*$', re.MULTILINE)

SPLIT_SALT = "gopsi-corpus-v1"
SPLITS: tuple[tuple[str, int], ...] = (("train", 80), ("valid", 10), ("test", 10))


def excluded_dir(name: str) -> bool:
    """Directories the go tool ignores (`testdata`, `.x`, `_x`) plus `vendor` copies."""
    return name in ("vendor", "testdata") or name.startswith((".", "_"))


def is_go_source(name: str) -> bool:
    return name.endswith(".go") and not name.startswith((".", "_"))


def is_generated(text: str) -> bool:
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("package "):
            return False
        if _GENERATED.match(line.rstrip("\r")):
            return True
    return False


def uses_cgo(text: str) -> bool:
    return _CGO_IMPORT.search(text) is not None


def std_unit(rel_path: str) -> str:
    """Split unit inside GOROOT/src: a top-level package tree, or one command under cmd/."""
    parts = list(PurePosixPath(rel_path).parts[:-1])
    if not parts:
        return "std"
    if parts[0] == "cmd" and len(parts) > 1:
        return f"std/cmd/{parts[1]}"
    return f"std/{parts[0]}"


def split_of(unit: str, salt: str = SPLIT_SALT) -> str:
    """Stable split by unit (a module, or a package tree of std) so that no unit straddles splits."""
    bucket = int.from_bytes(hashlib.sha256(f"{salt}:{unit}".encode()).digest()[:8], "big") % 100
    acc = 0
    for name, share in SPLITS:
        acc += share
        if bucket < acc:
            return name
    return SPLITS[-1][0]

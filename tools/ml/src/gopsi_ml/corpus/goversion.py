"""Module path escaping and semantic version ordering as defined by golang.org/x/mod."""

from __future__ import annotations

import re

_SEMVER = re.compile(
    r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


def escape(path_or_version: str) -> str:
    """Case-encodes a module path or version for the proxy and GOMODCACHE: `A` becomes `!a`."""
    return "".join("!" + c.lower() if "A" <= c <= "Z" else c for c in path_or_version)


def unescape(escaped: str) -> str:
    """Inverse of [escape]; raises ValueError on a malformed escape."""
    out: list[str] = []
    bang = False
    for c in escaped:
        if bang:
            if not "a" <= c <= "z":
                raise ValueError(f"bad escape in {escaped!r}")
            out.append(c.upper())
            bang = False
        elif c == "!":
            bang = True
        else:
            out.append(c)
    if bang:
        raise ValueError(f"trailing escape in {escaped!r}")
    return "".join(out)


def is_valid(version: str) -> bool:
    return _SEMVER.match(version) is not None


def sort_key(version: str) -> tuple:
    """Orders versions by semver precedence; build metadata (`+incompatible`) is ignored and
    invalid versions sort before every valid one. Pseudo-versions are prereleases, as in Go."""
    m = _SEMVER.match(version)
    if m is None:
        return (-1,)
    major, minor, patch, pre = m.group(1, 2, 3, 4)
    if pre is None:
        pre_key: tuple = (1,)
    else:
        pre_key = (0, *((0, int(p), "") if p.isdigit() else (1, 0, p) for p in pre.split(".")))
    return (0, int(major), int(minor), int(patch), pre_key)

"""Module roots that make up the corpus: GOROOT/src, fetched modules, a local GOMODCACHE."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

from . import goversion
from .fetch import MARKER
from .gofiles import std_unit
from .licenses import PERMISSIVE, classify_module, ids_allowed, spdx_list_allowed


@dataclass(frozen=True)
class ModuleRoot:
    source: str  # goroot | fetched | gomodcache
    module: str
    version: str
    root: Path
    license: str
    allowed: bool

    def unit(self, rel_path: str) -> str:
        if self.source != "goroot":
            return self.module
        return std_unit(rel_path if self.module == "std" else f"{self.module}/{rel_path}")


def go_env() -> dict[str, str]:
    """GOROOT and GOMODCACHE from `go env`, falling back to the environment."""
    env = {k: os.environ.get(k, "") for k in ("GOROOT", "GOMODCACHE")}
    go = shutil.which("go")
    if go is not None:
        try:
            out = subprocess.run(
                [go, "env", "-json", "GOROOT", "GOMODCACHE"],
                capture_output=True,
                text=True,
                check=True,
                timeout=30,
            ).stdout
            env.update({k: v for k, v in json.loads(out).items() if v})
        except (OSError, subprocess.SubprocessError, ValueError):
            pass
    return env


def goroot_modules(goroot: Path) -> list[ModuleRoot]:
    """GOROOT/src holds two modules: `std` and `cmd` (src/cmd has its own go.mod)."""
    version_file = goroot / "VERSION"
    version = "unknown"
    if version_file.is_file():
        version = version_file.read_text(encoding="utf-8").splitlines()[0].strip()
    src = goroot / "src"
    return [
        ModuleRoot("goroot", "std", version, src, "BSD-3-Clause", True),
        ModuleRoot("goroot", "cmd", version, src / "cmd", "BSD-3-Clause", True),
    ]


def fetched_modules(out: Path) -> Iterator[ModuleRoot]:
    """Modules finished by `fetch` (those carrying a marker file)."""
    for marker in sorted(out.rglob(MARKER)):
        meta = json.loads(marker.read_text(encoding="utf-8"))
        licenses = meta.get("licenses") or []
        yield ModuleRoot(
            "fetched",
            meta["module"],
            meta["version"],
            marker.parent,
            " | ".join(licenses),
            spdx_list_allowed(licenses),
        )


def gomodcache_modules(modcache: Path, allowed: frozenset[str] = PERMISSIVE) -> Iterator[ModuleRoot]:
    """Highest version of each module in a module cache, licensed by its license files."""
    best: dict[str, tuple[str, Path]] = {}
    for dirpath, dirnames, _ in os.walk(modcache):
        here = Path(dirpath)
        if here == modcache:
            dirnames[:] = [d for d in dirnames if d != "cache"]
        keep = []
        for d in dirnames:
            if "@" not in d:
                keep.append(d)
                continue
            esc_name, _, esc_version = d.partition("@")
            rel = (here / esc_name).relative_to(modcache).as_posix()
            try:
                module, version = goversion.unescape(rel), goversion.unescape(esc_version)
            except ValueError:
                continue
            current = best.get(module)
            if current is None or goversion.sort_key(version) > goversion.sort_key(current[0]):
                best[module] = (version, here / d)
        dirnames[:] = keep
    for module in sorted(best):
        version, root = best[module]
        ids = classify_module(root)
        yield ModuleRoot(
            "gomodcache",
            module,
            version,
            root,
            " | ".join(i or "unknown" for i in ids),
            ids_allowed(ids, allowed),
        )

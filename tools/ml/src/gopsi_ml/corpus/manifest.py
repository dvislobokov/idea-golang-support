"""`manifest.parquet`: one row per Go file of the corpus, the input of every dataset exporter.

Files stay where they are (GOROOT, the fetched directory, GOMODCACHE); the manifest records
absolute paths plus everything needed to filter: license, split, test/generated/cgo flags and
content duplicates. A module present in several sources is taken from the first one in the
order goroot, fetched, gomodcache, so two versions of one module never land in two splits.
"""

from __future__ import annotations

import hashlib
import os
import sys
from collections.abc import Iterable
from dataclasses import dataclass, field

import pyarrow as pa
import pyarrow.parquet as pq

from .gofiles import excluded_dir, is_generated, is_go_source, split_of, uses_cgo
from .sources import ModuleRoot

SCHEMA = pa.schema(
    [
        ("path", pa.string()),
        ("rel_path", pa.string()),
        ("source", pa.string()),
        ("module", pa.string()),
        ("version", pa.string()),
        ("license", pa.string()),
        ("unit", pa.string()),
        ("split", pa.string()),
        ("sha256", pa.string()),
        ("bytes", pa.int64()),
        ("lines", pa.int64()),
        ("is_test", pa.bool_()),
        ("is_generated", pa.bool_()),
        ("uses_cgo", pa.bool_()),
        ("duplicate_of", pa.string()),
    ]
)

SOURCE_ORDER = ("goroot", "fetched", "gomodcache")


@dataclass
class ManifestStats:
    modules: int = 0
    modules_skipped_license: int = 0
    modules_skipped_duplicate: int = 0
    files: int = 0
    duplicates: int = 0
    generated: int = 0
    by_split: dict[str, int] = field(default_factory=dict)


def go_files(root_dir: str) -> Iterable[str]:
    """Relative POSIX paths of Go sources under a module root, skipping ignored directories and
    nested modules (a subdirectory with its own go.mod belongs to another module)."""
    for dirpath, dirnames, filenames in os.walk(root_dir):
        rel_dir = os.path.relpath(dirpath, root_dir).replace(os.sep, "/")
        dirnames[:] = sorted(
            d
            for d in dirnames
            if not excluded_dir(d) and not os.path.isfile(os.path.join(dirpath, d, "go.mod"))
        )
        for name in sorted(filenames):
            if is_go_source(name):
                yield name if rel_dir == "." else f"{rel_dir}/{name}"


def build(
    modules: Iterable[ModuleRoot], *, include_disallowed: bool = False
) -> tuple[pa.Table, ManifestStats]:
    stats = ManifestStats()
    ordered = sorted(modules, key=lambda m: (SOURCE_ORDER.index(m.source), m.module))
    seen_modules: set[str] = set()
    first_by_hash: dict[str, str] = {}
    columns: dict[str, list] = {name: [] for name in SCHEMA.names}

    for m in ordered:
        if m.module in seen_modules:
            stats.modules_skipped_duplicate += 1
            continue
        seen_modules.add(m.module)
        if not m.allowed and not include_disallowed:
            stats.modules_skipped_license += 1
            continue
        stats.modules += 1
        root = str(m.root)
        for rel in go_files(root):
            path = os.path.join(root, *rel.split("/"))
            try:
                data = open(path, "rb").read()  # noqa: SIM115 - one-shot read
            except OSError as e:
                print(f"  skip {path}: {e}", file=sys.stderr)
                continue
            text = data.decode("utf-8", errors="replace")
            sha = hashlib.sha256(data).hexdigest()
            posix_path = path.replace(os.sep, "/")
            duplicate_of = first_by_hash.setdefault(sha, posix_path)
            unit = m.unit(rel)
            split = split_of(unit)
            generated = is_generated(text)
            row = {
                "path": posix_path,
                "rel_path": rel,
                "source": m.source,
                "module": m.module,
                "version": m.version,
                "license": m.license,
                "unit": unit,
                "split": split,
                "sha256": sha,
                "bytes": len(data),
                "lines": text.count("\n") + (0 if text.endswith("\n") or not text else 1),
                "is_test": rel.endswith("_test.go"),
                "is_generated": generated,
                "uses_cgo": uses_cgo(text),
                "duplicate_of": None if duplicate_of == posix_path else duplicate_of,
            }
            for k, v in row.items():
                columns[k].append(v)
            stats.files += 1
            stats.duplicates += row["duplicate_of"] is not None
            stats.generated += generated
            stats.by_split[split] = stats.by_split.get(split, 0) + 1
        if stats.modules % 200 == 0:
            print(f"  {stats.modules} modules, {stats.files} files", file=sys.stderr)

    return pa.table(columns, schema=SCHEMA), stats


def write(table: pa.Table, path: str) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    pq.write_table(table, path, compression="zstd")

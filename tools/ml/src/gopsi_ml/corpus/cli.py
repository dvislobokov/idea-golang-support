"""gopsi-corpus: select, fetch and catalogue the Go source corpus.

uv run gopsi-corpus select   --depsdev data/deps_dev_go_modules.csv
uv run gopsi-corpus fetch    --jobs 8
uv run gopsi-corpus manifest --with-gomodcache
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import fetch, manifest, selection, sources

DATA = Path(__file__).resolve().parents[3] / "data"


def _select(args: argparse.Namespace) -> int:
    candidates = selection.read_depsdev_csv(args.depsdev)
    locked, report = selection.select(
        candidates,
        top_dependents=args.top_dependents,
        top_stars=args.top_stars,
        exclude=args.exclude,
    )
    selection.write_lock(args.lock, locked)
    reasons: dict[str, int] = {}
    for lk in locked:
        reasons[lk.reason] = reasons.get(lk.reason, 0) + 1
    print(
        f"candidates {report.total}: rejected license {report.rejected_license}, "
        f"version {report.rejected_version}, pattern {report.rejected_pattern}; "
        f"selected {report.selected} {reasons} -> {args.lock}"
    )
    return 0


def _fetch(args: argparse.Namespace) -> int:
    locked = selection.read_lock(args.lock)
    if args.limit:
        locked = locked[: args.limit]
    results = fetch.fetch_all(
        locked,
        args.out,
        jobs=args.jobs,
        report=args.out / "fetch-report.csv",
        proxy=args.proxy,
        max_zip_bytes=args.max_zip_mb << 20,
    )
    by_status: dict[str, int] = {}
    for r in results:
        by_status[r.status] = by_status.get(r.status, 0) + 1
    size = sum(r.bytes for r in results)
    print(f"{len(results)} modules {by_status}, {size / (1 << 30):.2f} GiB of kept files this run")
    return 0 if by_status.get("error", 0) == 0 else 1


def _manifest(args: argparse.Namespace) -> int:
    env = sources.go_env()
    roots: list[sources.ModuleRoot] = []
    goroot = args.goroot or env.get("GOROOT")
    if goroot and not args.no_goroot:
        roots.extend(sources.goroot_modules(Path(goroot)))
    if args.fetched.is_dir():
        roots.extend(sources.fetched_modules(args.fetched))
    if args.with_gomodcache:
        modcache = args.gomodcache or env.get("GOMODCACHE")
        if not modcache:
            print("GOMODCACHE is unknown; pass --gomodcache", file=sys.stderr)
            return 2
        roots.extend(sources.gomodcache_modules(Path(modcache)))
    if not roots:
        print("nothing to catalogue", file=sys.stderr)
        return 2
    table, stats = manifest.build(roots, include_disallowed=args.include_disallowed)
    manifest.write(table, str(args.out))
    print(
        f"{stats.modules} modules ({stats.modules_skipped_license} skipped by license, "
        f"{stats.modules_skipped_duplicate} duplicates), {stats.files} files, "
        f"{stats.duplicates} duplicate files, {stats.generated} generated, "
        f"splits {stats.by_split} -> {args.out}"
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="gopsi-corpus", description="Select, fetch and catalogue the Go corpus")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("select", help="deps.dev CSV -> modules.lock")
    s.add_argument("--depsdev", type=Path, default=DATA / "deps_dev_go_modules.csv")
    s.add_argument("--lock", type=Path, default=DATA / "modules.lock")
    s.add_argument("--top-dependents", type=int, default=2000)
    s.add_argument("--top-stars", type=int, default=1000)
    s.add_argument("--exclude", action="append", default=[], help="regex on module path; repeatable")
    s.set_defaults(func=_select)

    f = sub.add_parser("fetch", help="download modules.lock from the module proxy")
    f.add_argument("--lock", type=Path, default=DATA / "modules.lock")
    f.add_argument("--out", type=Path, default=DATA / "modules")
    f.add_argument("--proxy", default=fetch.DEFAULT_PROXY)
    f.add_argument("--jobs", type=int, default=8)
    f.add_argument("--max-zip-mb", type=int, default=200)
    f.add_argument("--limit", type=int, default=0, help="only the first N modules (smoke runs)")
    f.set_defaults(func=_fetch)

    m = sub.add_parser("manifest", help="catalogue corpus files into manifest.parquet")
    m.add_argument("--out", type=Path, default=DATA / "manifest.parquet")
    m.add_argument("--fetched", type=Path, default=DATA / "modules")
    m.add_argument("--goroot")
    m.add_argument("--no-goroot", action="store_true")
    m.add_argument("--with-gomodcache", action="store_true")
    m.add_argument("--gomodcache")
    m.add_argument("--include-disallowed", action="store_true", help="keep non-permissive modules")
    m.set_defaults(func=_manifest)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

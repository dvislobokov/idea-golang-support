"""Downloads locked modules from the Go module proxy and keeps only what the corpus needs.

Talks to the proxy protocol directly (`$GOPROXY/<module>/@v/<version>.zip`), so neither the go
binary nor the modules' dependencies are required. The output directory has the GOMODCACHE
layout (`<escaped path>@<escaped version>/`), which lets it double as a module cache later.
Each finished module gets a marker file; reruns skip finished modules and redo partial ones.
"""

from __future__ import annotations

import csv
import hashlib
import json
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path

from . import goversion
from .gofiles import excluded_dir, is_go_source
from .licenses import classify_module, is_license_file
from .selection import Locked

DEFAULT_PROXY = "https://proxy.golang.org"
MARKER = ".gopsi-module.json"
USER_AGENT = "gopsi-ml-corpus/0.0.1"
_CHUNK = 1 << 20


@dataclass
class FetchResult:
    module: str
    version: str
    status: str  # ok | cached | gone | too_large | error
    files: int = 0
    bytes: int = 0
    zip_sha256: str = ""
    error: str = ""


def module_dir(out: Path, module: str, version: str) -> Path:
    return out.joinpath(*f"{goversion.escape(module)}@{goversion.escape(version)}".split("/"))


def zip_url(proxy: str, module: str, version: str) -> str:
    return f"{proxy.rstrip('/')}/{goversion.escape(module)}/@v/{goversion.escape(version)}.zip"


def keep_member(rel: str) -> bool:
    """Go sources outside ignored directories, plus go.mod/go.sum and license files at the root."""
    parts = rel.split("/")
    if any(p in ("", ".", "..") for p in parts) or ":" in rel or "\\" in rel:
        return False
    if any(excluded_dir(d) for d in parts[:-1]):
        return False
    name = parts[-1]
    if is_go_source(name):
        return True
    return len(parts) == 1 and (name in ("go.mod", "go.sum") or is_license_file(name))


def extract(zip_path: Path, module: str, version: str, dest: Path) -> tuple[int, int]:
    """Extracts kept members of a module zip into [dest]; returns (files, bytes)."""
    prefix = f"{module}@{version}/"
    files = total = 0
    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            if not info.filename.startswith(prefix):
                raise ValueError(f"zip member outside {prefix}: {info.filename}")
            rel = info.filename[len(prefix) :]
            if not keep_member(rel):
                continue
            target = dest.joinpath(*rel.split("/"))
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst, _CHUNK)
            files += 1
            total += info.file_size
    return files, total


class _TooLarge(Exception):
    pass


def _download(url: str, to: Path, max_bytes: int, timeout: float) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        length = resp.headers.get("Content-Length")
        if length is not None and int(length) > max_bytes:
            raise _TooLarge(length)
        digest = hashlib.sha256()
        size = 0
        with to.open("wb") as f:
            while chunk := resp.read(_CHUNK):
                size += len(chunk)
                if size > max_bytes:
                    raise _TooLarge(size)
                digest.update(chunk)
                f.write(chunk)
    return digest.hexdigest()


def fetch_one(
    locked: Locked,
    out: Path,
    *,
    proxy: str = DEFAULT_PROXY,
    max_zip_bytes: int = 200 << 20,
    timeout: float = 120.0,
    retries: int = 3,
) -> FetchResult:
    c = locked.candidate
    dest = module_dir(out, c.module, c.version)
    if (dest / MARKER).exists():
        return FetchResult(c.module, c.version, "cached")
    url = zip_url(proxy, c.module, c.version)
    tmp_root = out / ".tmp"
    tmp_root.mkdir(parents=True, exist_ok=True)
    staging = dest.with_name(dest.name + ".partial")
    with tempfile.TemporaryDirectory(dir=tmp_root) as tmp:
        zip_path = Path(tmp) / "module.zip"
        sha = ""
        for attempt in range(retries + 1):
            try:
                sha = _download(url, zip_path, max_zip_bytes, timeout)
                break
            except _TooLarge as e:
                return FetchResult(c.module, c.version, "too_large", error=f"{e} bytes")
            except urllib.error.HTTPError as e:
                if e.code in (404, 410):
                    return FetchResult(c.module, c.version, "gone", error=f"HTTP {e.code}")
                if attempt == retries or (e.code < 500 and e.code != 429):
                    return FetchResult(c.module, c.version, "error", error=f"HTTP {e.code}")
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt == retries:
                    return FetchResult(c.module, c.version, "error", error=str(e))
            time.sleep(2**attempt)
        try:
            for stale in (staging, dest):
                if stale.exists():
                    shutil.rmtree(stale)
            files, size = extract(zip_path, c.module, c.version, staging)
        except (zipfile.BadZipFile, ValueError, OSError) as e:
            shutil.rmtree(staging, ignore_errors=True)
            return FetchResult(c.module, c.version, "error", error=f"extract: {e}")
    marker = {
        "module": c.module,
        "version": c.version,
        "licenses": list(c.licenses),
        "detected_licenses": classify_module(staging),
        "reason": locked.reason,
        "direct_dependents": c.direct_dependents,
        "stars": c.stars,
        "url": url,
        "zip_sha256": sha,
        "files": files,
        "bytes": size,
        "fetched_at": datetime.now(UTC).isoformat(timespec="seconds"),
    }
    (staging / MARKER).write_text(json.dumps(marker, indent=2), encoding="utf-8")
    staging.rename(dest)
    return FetchResult(c.module, c.version, "ok", files, size, sha)


def fetch_all(
    locked: Iterable[Locked],
    out: Path,
    *,
    jobs: int = 8,
    report: Path | None = None,
    **kwargs,
) -> list[FetchResult]:
    items = list(locked)
    out.mkdir(parents=True, exist_ok=True)
    results: list[FetchResult] = []
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = [pool.submit(fetch_one, lk, out, **kwargs) for lk in items]
        for i, fut in enumerate(as_completed(futures), 1):
            r = fut.result()
            results.append(r)
            if r.status not in ("ok", "cached"):
                print(f"  {r.status:9} {r.module}@{r.version} {r.error}", file=sys.stderr)
            if i % 100 == 0 or i == len(items):
                print(f"[{i}/{len(items)}] fetched", file=sys.stderr)
    shutil.rmtree(out / ".tmp", ignore_errors=True)
    results.sort(key=lambda r: r.module)
    if report is not None:
        with report.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(asdict(results[0]).keys()) if results else ["module"])
            w.writeheader()
            for r in results:
                w.writerow(asdict(r))
    return results

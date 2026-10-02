from gopsi_ml.corpus.selection import Candidate, read_lock, select, write_lock


def c(module, deps=0, stars=0, lic=("MIT",), version="v1.0.0"):
    return Candidate(module, version, tuple(lic), deps, stars)


def test_select_libraries_then_apps_then_always():
    cands = [
        c("example.com/lib-a", deps=500),
        c("example.com/lib-b", deps=300),
        c("example.com/lib-c", deps=100),
        c("example.com/app", stars=50_000),
        c("example.com/gpl", deps=10_000, lic=("GPL-3.0-only",)),
        c("example.com/nolicense", deps=10_000, lic=()),
        c("example.com/badversion", deps=10_000, version="master"),
        c("golang.org/x/mod", deps=1),
        c("example.com/skipme", deps=900),
    ]
    locked, report = select(cands, top_dependents=2, top_stars=1, exclude=["skipme"])
    got = {lk.candidate.module: lk.reason for lk in locked}
    assert got == {
        "golang.org/x/mod": "always",
        "example.com/lib-a": "dependents",
        "example.com/lib-b": "dependents",
        "example.com/app": "stars",
    }
    assert (report.rejected_license, report.rejected_version, report.rejected_pattern) == (2, 1, 1)


def test_lock_roundtrip(tmp_path):
    locked, _ = select(
        [c("example.com/a", deps=3, lic=("MIT", "BSD-3-Clause"))], top_dependents=10, top_stars=0
    )
    path = tmp_path / "modules.lock"
    write_lock(path, locked)
    assert read_lock(path) == locked

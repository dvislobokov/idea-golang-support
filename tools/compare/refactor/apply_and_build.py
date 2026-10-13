"""Build check of the refactoring comparison: for every scenario directory <out>/<side>/<id>/ (files/<path> = the new text of each changed
or created file, deleted.txt = the deleted files) the result is copied over a git clone of its own, `go build ./...` and
`go vet <touched packages>` run in it (exit code and the first 30 lines of output recorded), and the clone is restored with
`git checkout -- . && git clean -fdq` (that clone only) and checked with `git status --short`. The vet of the same packages on the clean
clone is recorded once per package as the baseline, so a vet failure that was there before is told apart.

    python tools/compare/refactor/apply_and_build.py --clone build/compare/prometheus-refactor [--out build/compare/out]
                                                     [--sides refactor,refactor-gopls] [--only id,id]

Never point --clone at a clone other tools use: it is written and reset. Output: <out>/<side>-build.json.
"""
import argparse
import json
import os
import shutil
import subprocess
import time


def run(cmd, cwd, timeout=1800):
    t0 = time.time()
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    out = (p.stdout + p.stderr).replace("\r\n", "\n")
    lines = [l for l in out.split("\n") if l.strip()]
    return {"cmd": " ".join(cmd), "exit": p.returncode, "lines": lines[:30], "millis": int((time.time() - t0) * 1000)}


def git(clone, *args):
    return subprocess.run(["git", "-C", clone, *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def restore(clone):
    git(clone, "checkout", "--", ".")
    git(clone, "clean", "-fdq")
    status = git(clone, "status", "--short").strip()
    if status:
        raise SystemExit(f"the clone {clone} is not clean after restore:\n{status}")


def first_error(step):
    if step is None or step["exit"] == 0:
        return None
    for l in step["lines"]:
        if not l.startswith("#") and ("go:" in l or ":" in l):
            return l.strip()
    return step["lines"][0] if step["lines"] else f"exit {step['exit']}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clone", required=True)
    ap.add_argument("--out", default="build/compare/out")
    ap.add_argument("--sides", default="refactor,refactor-gopls")
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    clone = os.path.abspath(a.clone)
    out = os.path.abspath(a.out)
    only = {x.strip() for x in a.only.split(",") if x.strip()}
    if git(clone, "status", "--short").strip():
        raise SystemExit(f"{clone} is not clean; refusing to start")
    baseline_vet = {}
    for side in [s.strip() for s in a.sides.split(",") if s.strip()]:
        base = os.path.join(out, side)
        results = []
        for sid in sorted(os.listdir(base)) if os.path.isdir(base) else []:
            sdir = os.path.join(base, sid)
            if not os.path.isdir(sdir) or (only and sid not in only):
                continue
            files_dir = os.path.join(sdir, "files")
            written = []
            if os.path.isdir(files_dir):
                for dirpath, _, names in os.walk(files_dir):
                    for n in names:
                        written.append(os.path.relpath(os.path.join(dirpath, n), files_dir).replace("\\", "/"))
            deleted_file = os.path.join(sdir, "deleted.txt")
            deleted = [l.strip() for l in open(deleted_file, encoding="utf-8")] if os.path.isfile(deleted_file) else []
            deleted = [d for d in deleted if d]
            res = {"id": sid, "side": side, "written": sorted(written), "deleted": deleted}
            if not written and not deleted:
                res["verdict"] = "nothing to build"
                results.append(res)
                continue
            try:
                for rel in written:
                    target = os.path.join(clone, rel)
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    shutil.copyfile(os.path.join(files_dir, rel), target)
                for rel in deleted:
                    target = os.path.join(clone, rel)
                    if os.path.isfile(target):
                        os.remove(target)
                    parent = os.path.dirname(target)
                    if os.path.isdir(parent) and not os.listdir(parent):
                        os.rmdir(parent)
                dirs = sorted({os.path.dirname(r) for r in written + deleted if r.endswith(".go")})
                pkgs = ["./" + d + "/" if d else "./" for d in dirs if os.path.isdir(os.path.join(clone, d)) and any(f.endswith(".go") for f in os.listdir(os.path.join(clone, d)))]
                res["build"] = run(["go", "build", "./..."], clone)
                res["vet"] = run(["go", "vet", *pkgs], clone) if pkgs else None
            finally:
                restore(clone)
            # vet of the same packages before the change (only those that exist in HEAD)
            res["baselineVetFails"] = []
            for p in pkgs if res.get("vet") else []:
                if not os.path.isdir(os.path.join(clone, p)):
                    continue
                if p not in baseline_vet:
                    baseline_vet[p] = run(["go", "vet", p], clone)
                if baseline_vet[p]["exit"] != 0:
                    res["baselineVetFails"].append(p)
            b, v = res["build"], res.get("vet")
            if b["exit"] != 0:
                res["verdict"] = "build broken: " + (first_error(b) or "")
            elif v is not None and v["exit"] != 0 and not res["baselineVetFails"]:
                res["verdict"] = "vet broken: " + (first_error(v) or "")
            elif v is not None and v["exit"] != 0:
                res["verdict"] = "vet fails (also on the clean clone): " + (first_error(v) or "")
            else:
                res["verdict"] = "ok"
            print(f"{side}/{sid}: {res['verdict']}", flush=True)
            results.append(res)
        json.dump({"clone": clone, "results": results}, open(os.path.join(out, f"{side}-build.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("clone clean:", not git(clone, "status", "--short").strip())


if __name__ == "__main__":
    main()

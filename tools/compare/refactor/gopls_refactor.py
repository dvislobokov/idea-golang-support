"""gopls side of the refactoring comparison (tools/compare/refactor/scenarios.json): for each scenario `gopls serve` is asked what it
offers at the place (textDocument/codeAction, triggerKind Invoked, no `only` filter: the full list is recorded) and, where the scenario
names a gopls operation, it is run: textDocument/rename, or the first offered code action of the listed kinds (resolved through
codeAction/resolve, or executed as a command whose workspace/applyEdit is captured). The WorkspaceEdit is applied to in-memory copies
only — the clone is never written — and the result goes to <out>/refactor-gopls/<id>/files/<path>, diff.patch, deleted.txt, plus
<out>/refactor-gopls.json.

    python tools/compare/refactor/gopls_refactor.py --root <clean clone> [--scenarios tools/compare/refactor/scenarios.json]
                                                    [--out build/compare/out] [--only id,id]
"""
import argparse
import difflib
import json
import os
import shutil
import subprocess
import threading
import time
import urllib.parse


def uri(path):
    return "file:///" + urllib.parse.quote(os.path.abspath(path).replace("\\", "/").lstrip("/"), safe="/:")


def path_of(u):
    p = urllib.parse.unquote(u[len("file://"):])
    if len(p) > 2 and p[0] == "/" and p[2] == ":":
        p = p[1:]
    return os.path.normpath(p)


class Gopls:
    def __init__(self, root):
        self.p = subprocess.Popen(["gopls", "serve"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd=root)
        self.responses, self.cv, self.seq = {}, threading.Condition(), 0
        self.applied = []  # WorkspaceEdits the server asked to apply (workspace/applyEdit)
        self.lock = threading.Lock()
        threading.Thread(target=self.reader, daemon=True).start()

    def send(self, message):
        body = json.dumps(message).encode()
        with self.lock:
            self.p.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
            self.p.stdin.flush()

    def reader(self):
        while True:
            header = b""
            while not header.endswith(b"\r\n\r\n"):
                byte = self.p.stdout.read(1)
                if not byte:
                    return
                header += byte
            length = int([h for h in header.split(b"\r\n") if h.lower().startswith(b"content-length")][0].split(b":")[1])
            m = json.loads(self.p.stdout.read(length))
            if "id" in m and "method" not in m:
                with self.cv:
                    self.responses[m["id"]] = m
                    self.cv.notify_all()
            elif "id" in m:
                method = m["method"]
                if method == "workspace/configuration":
                    result = [{} for _ in m.get("params", {}).get("items", [None])]
                elif method == "workspace/applyEdit":
                    self.applied.append(m["params"]["edit"])
                    result = {"applied": True}
                else:
                    result = None
                self.send({"jsonrpc": "2.0", "id": m["id"], "result": result})

    def request(self, method, params, timeout=180):
        self.seq += 1
        rid = self.seq
        self.send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        with self.cv:
            self.cv.wait_for(lambda: rid in self.responses, timeout)
            return self.responses.pop(rid, {"error": {"message": "timeout"}})

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})


def position(text, char_offset):
    line = text.count("\n", 0, char_offset)
    start = text.rfind("\n", 0, char_offset) + 1
    return {"line": line, "character": len(text[start:char_offset].encode("utf-16-le")) // 2}


def offset(text, pos):
    lines = text.split("\n")
    base = sum(len(l) + 1 for l in lines[: pos["line"]])
    line = lines[pos["line"]] if pos["line"] < len(lines) else ""
    units, i = 0, 0
    while i < len(line) and units < pos["character"]:
        units += len(line[i].encode("utf-16-le")) // 2
        i += 1
    return base + i


class Workspace:
    """In-memory copies of the clone's files that a WorkspaceEdit touches."""

    def __init__(self, root):
        self.root = root
        self.texts = {}     # abs path -> text (None = deleted)
        self.original = {}  # abs path -> original text (None = did not exist)

    def get(self, path):
        if path not in self.texts:
            text = open(path, encoding="utf-8", newline="").read() if os.path.isfile(path) else None
            self.texts[path] = text
            self.original.setdefault(path, text)
        return self.texts[path]

    def set(self, path, text):
        self.get(path)
        self.texts[path] = text

    def apply_text_edits(self, path, edits):
        text = self.get(path) or ""
        spans = sorted(((offset(text, e["range"]["start"]), offset(text, e["range"]["end"]), e["newText"]) for e in edits), key=lambda s: (s[0], s[1]), reverse=True)
        for start, end, new in spans:
            text = text[:start] + new + text[end:]
        self.set(path, text)

    def apply(self, edit):
        for u, edits in (edit.get("changes") or {}).items():
            self.apply_text_edits(path_of(u), edits)
        for change in edit.get("documentChanges") or []:
            kind = change.get("kind")
            if kind == "create":
                p = path_of(change["uri"])
                if self.get(p) is None:
                    self.set(p, "")
            elif kind == "rename":
                old, new = path_of(change["oldUri"]), path_of(change["newUri"])
                if os.path.isdir(old):
                    for dirpath, _, names in os.walk(old):
                        for n in names:
                            src = os.path.join(dirpath, n)
                            self.set(os.path.join(new, os.path.relpath(src, old)), self.get(src))
                            self.set(src, None)
                else:
                    self.set(new, self.get(old))
                    self.set(old, None)
            elif kind == "delete":
                self.set(path_of(change["uri"]), None)
            else:
                self.apply_text_edits(path_of(change["textDocument"]["uri"]), change["edits"])

    def result(self, out_dir):
        changed, created, deleted, diff = [], [], [], []
        for p in sorted(self.texts):
            before, after = self.original.get(p), self.texts[p]
            if before == after:
                continue
            rel = os.path.relpath(p, self.root).replace("\\", "/")
            if after is None:
                deleted.append(rel)
            else:
                (created if before is None else changed).append(rel)
                target = os.path.join(out_dir, "files", rel)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                open(target, "w", encoding="utf-8", newline="").write(after)
            diff.extend(difflib.unified_diff((before or "").splitlines(True), (after or "").splitlines(True),
                                             "/dev/null" if before is None else "a/" + rel, "/dev/null" if after is None else "b/" + rel))
        text = "".join(d if d.endswith("\n") else d + "\n" for d in diff)
        if text:
            os.makedirs(out_dir, exist_ok=True)
            open(os.path.join(out_dir, "diff.patch"), "w", encoding="utf-8", newline="").write(text)
        if deleted:
            open(os.path.join(out_dir, "deleted.txt"), "w", encoding="utf-8").write("\n".join(deleted) + "\n")
        return changed, created, deleted, text


ALL_KINDS = ["quickfix", "refactor", "refactor.extract", "refactor.inline", "refactor.rewrite", "source", "source.organizeImports",
             "source.fixAll", "source.doc", "source.assembly", "source.freesymbols", "source.test", "source.addTest", "gopls.doc.features"]


def place(s, text):
    """(caret, selection start, selection end) of the plugin's place and the gopls place."""
    if "select" in s:
        base = text.index(s["within"]) if "within" in s else 0
        start = text.index(s["select"], base)
        sel = (start, start + len(s["select"]))
    else:
        start = text.index(s["anchor"]) + s.get("at", 0)
        sel = (start, start)
    if "goplsSelect" in s:
        g = text.index(s["goplsSelect"])
        gsel = (g, g + len(s["goplsSelect"]))
    elif "goplsAnchor" in s:
        g = text.index(s["goplsAnchor"]) + s.get("goplsAt", 0)
        gsel = (g, g)
    else:
        gsel = sel
    return sel, gsel


def code_actions(g, u, text, sel):
    r = g.request("textDocument/codeAction", {"textDocument": {"uri": u}, "range": {"start": position(text, sel[0]), "end": position(text, sel[1])},
                                               "context": {"diagnostics": [], "triggerKind": 1}})
    return r.get("result") or [], r.get("error")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--scenarios", default="tools/compare/refactor/scenarios.json")
    ap.add_argument("--out", default="build/compare/out")
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    root = os.path.abspath(a.root)
    scenarios = json.load(open(a.scenarios, encoding="utf-8"))["scenarios"]
    only = {x.strip() for x in a.only.split(",") if x.strip()}
    out_root = os.path.join(os.path.abspath(a.out), "refactor-gopls")
    g = Gopls(root)
    caps = {"workspace": {"applyEdit": True, "workspaceEdit": {"documentChanges": True, "resourceOperations": ["create", "rename", "delete"]},
                          "configuration": True, "workspaceFolders": True},
            "textDocument": {"rename": {"prepareSupport": True},
                             "codeAction": {"codeActionLiteralSupport": {"codeActionKind": {"valueSet": ALL_KINDS}}, "dataSupport": True,
                                            "resolveSupport": {"properties": ["edit"]}, "isPreferredSupport": True}},
            "window": {"workDoneProgress": False}}
    g.request("initialize", {"processId": os.getpid(), "rootUri": uri(root), "workspaceFolders": [{"uri": uri(root), "name": "root"}], "capabilities": caps})
    g.notify("initialized", {})
    version = json.loads(subprocess.run(["gopls", "version", "-json"], capture_output=True, text=True).stdout or "{}")
    results = []
    opened = set()
    for s in scenarios:
        if only and s["id"] not in only:
            continue
        out_dir = os.path.join(out_root, s["id"])
        shutil.rmtree(out_dir, ignore_errors=True)
        path = os.path.join(root, s["file"])
        text = open(path, encoding="utf-8", newline="").read()
        u = uri(path)
        if path not in opened:
            g.notify("textDocument/didOpen", {"textDocument": {"uri": u, "languageId": "go", "version": 1, "text": text}})
            opened.add(path)
        sel, gsel = place(s, text)
        res = {"id": s["id"], "refactoring": s["refactoring"], "what": s.get("what", ""), "file": s["file"], "line": text.count("\n", 0, sel[0]) + 1}
        t0 = time.perf_counter()
        listed, err = code_actions(g, u, text, sel)
        res["actions"] = [f"{x.get('kind', '')}: {x.get('title', '')}" for x in listed]
        if err:
            res["actionsError"] = err
        if gsel != sel:
            glisted, _ = code_actions(g, u, text, gsel)
            res["goplsPlaceLine"] = text.count("\n", 0, gsel[0]) + 1
            res["goplsPlaceActions"] = [f"{x.get('kind', '')}: {x.get('title', '')}" for x in glisted]
        else:
            glisted = listed
        op = s.get("gopls")
        ws = Workspace(root)
        try:
            if op is None:
                res["status"] = "not-supported"
                res["message"] = "gopls has no such refactoring"
            elif op["op"] == "rename":
                name = s["params"]["newName"]
                r = g.request("textDocument/rename", {"textDocument": {"uri": u}, "position": position(text, gsel[0]), "newName": name})
                if r.get("error"):
                    res["status"], res["message"] = "refused", r["error"].get("message")
                elif not r.get("result"):
                    res["status"], res["message"] = "no-change", "empty WorkspaceEdit"
                else:
                    ws.apply(r["result"])
                    res["status"], res["done"] = "applied", f"textDocument/rename to {name}"
            else:
                kinds = op.get("kinds", [])
                chosen = None
                for k in kinds:
                    chosen = next((x for x in glisted if x.get("kind") == k), None)
                    if chosen:
                        break
                if not kinds:
                    res["status"], res["message"] = "not-supported", "no gopls code action for this; list recorded"
                elif chosen is None:
                    res["status"], res["message"] = "not-offered", f"none of {kinds} offered at the place"
                else:
                    res["done"] = f"{chosen.get('kind')}: {chosen.get('title')}"
                    edit = chosen.get("edit")
                    if edit is None and chosen.get("data") is not None and chosen.get("command") is None:
                        r = g.request("codeAction/resolve", chosen)
                        if r.get("error"):
                            raise RuntimeError("resolve: " + r["error"].get("message", ""))
                        chosen = r.get("result") or chosen
                        edit = chosen.get("edit")
                    if edit is None and chosen.get("command"):
                        g.applied.clear()
                        cmd = chosen["command"]
                        r = g.request("workspace/executeCommand", {"command": cmd["command"], "arguments": cmd.get("arguments", [])})
                        if r.get("error"):
                            raise RuntimeError("executeCommand: " + r["error"].get("message", ""))
                        time.sleep(0.5)
                        for e in list(g.applied):
                            ws.apply(e)
                        res["status"] = "applied" if g.applied else "no-change"
                    elif edit is not None:
                        ws.apply(edit)
                        res["status"] = "applied"
                    else:
                        res["status"], res["message"] = "no-change", "the action has neither an edit nor a command"
        except Exception as e:  # noqa: BLE001 - recorded, the run goes on
            res["status"], res["message"] = "exception", f"{type(e).__name__}: {e}"
        changed, created, deleted, diff = ws.result(out_dir)
        if res.get("status") == "applied" and not (changed or created or deleted):
            res["status"] = "no-change"
        res.update({"changed": changed, "created": created, "deleted": deleted, "diff": diff, "millis": int((time.perf_counter() - t0) * 1000)})
        results.append(res)
        print(f"{s['id']}: {res['status']} {res.get('message') or res.get('done') or ''}", flush=True)
    g.request("shutdown", None)
    g.notify("exit", None)
    os.makedirs(os.path.abspath(a.out), exist_ok=True)
    target = os.path.join(os.path.abspath(a.out), "refactor-gopls.json")
    if only and os.path.isfile(target):  # a partial run replaces its scenarios in the previous results
        mine = {r["id"] for r in results}
        order = [s["id"] for s in scenarios]
        kept = [r for r in json.load(open(target, encoding="utf-8")).get("results", []) if r["id"] not in mine]
        results = sorted(kept + results, key=lambda r: order.index(r["id"]) if r["id"] in order else len(order))
    json.dump({"gopls": version, "root": root, "results": results}, open(os.path.join(os.path.abspath(a.out), "refactor-gopls.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()

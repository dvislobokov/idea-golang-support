"""gopls side of docs/compare part 2: the same "restore the deleted identifier" measurement as GoCompletionHarnessTest, through
textDocument/completion of `gopls serve` (what VS Code shows). For each site and prefix 0/1/3: the tail of the identifier is deleted
(and the import line for unimported-package sites), the document is changed in gopls only (didChange — the clone is never written),
completion is asked at the caret and the rank of the identifier in the list ordered by sortText is recorded with the time.

    python tools/compare/gopls_completion.py --root build/compare/prometheus --sites build/compare/sites.json --out <file.json> [--limit N]
"""
import argparse
import json
import os
import subprocess
import threading
import time


def uri(path):
    return "file:///" + os.path.abspath(path).replace("\\", "/").lstrip("/")


class Gopls:
    def __init__(self, root):
        self.p = subprocess.Popen(["gopls", "serve"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd=root)
        self.responses, self.cv, self.seq = {}, threading.Condition(), 0
        threading.Thread(target=self.reader, daemon=True).start()

    def send(self, message):
        body = json.dumps(message).encode()
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
                result = [{}] if m["method"] == "workspace/configuration" else None
                self.send({"jsonrpc": "2.0", "id": m["id"], "result": result})

    def request(self, method, params, timeout=120):
        self.seq += 1
        rid = self.seq
        self.send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        with self.cv:
            self.cv.wait_for(lambda: rid in self.responses, timeout)
            return self.responses.pop(rid, {"error": "timeout"})

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})


def utf16_col(line_text):
    return len(line_text.encode("utf-16-le")) // 2


def position(text, char_offset):
    line = text.count("\n", 0, char_offset)
    start = text.rfind("\n", 0, char_offset) + 1
    return {"line": line, "character": utf16_col(text[start:char_offset])}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--sites", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=100000)
    a = ap.parse_args()
    root = os.path.abspath(a.root)
    sites = json.load(open(a.sites, encoding="utf-8"))[: a.limit]
    g = Gopls(root)
    g.request("initialize", {"processId": os.getpid(), "rootUri": uri(root), "workspaceFolders": [{"uri": uri(root), "name": "root"}],
                             "capabilities": {"textDocument": {"completion": {"completionItem": {"snippetSupport": False}}}}})
    g.notify("initialized", {})
    version = json.loads(subprocess.run(["gopls", "version", "-json"], capture_output=True, text=True).stdout or "{}")
    attempts, opened, ver = [], None, 1
    for s in sites:
        path = os.path.join(root, s["file"])
        raw = open(path, "rb").read()
        text = raw.decode("utf-8")
        chars = lambda b: len(raw[:b].decode("utf-8"))
        at, ident = chars(s["offset"]), s["ident"]
        if text[at:at + len(ident)] != ident:
            attempts.append({"site": s["id"], "file": s["file"], "ident": ident, "kind": s["kind"], "sub": s["sub"], "prefix": -1, "rank": -1, "note": "site text mismatch"})
            continue
        if opened != path:
            if opened:
                g.notify("textDocument/didClose", {"textDocument": {"uri": uri(opened)}})
            g.notify("textDocument/didOpen", {"textDocument": {"uri": uri(path), "languageId": "go", "version": ver, "text": text}})
            opened = path
            # warm-up: the first request of a file loads its package
            g.request("textDocument/completion", {"textDocument": {"uri": uri(path)}, "position": position(text, at)})
        for prefix in [p for p in (0, 1, 3) if p < len(ident)]:
            edited = text[: at + prefix] + text[at + len(ident):]
            caret = at + prefix
            if s["kind"] == "unimported-package":
                i0, i1 = chars(s["importStart"]), chars(s["importEnd"])
                edited = edited[:i0] + edited[i1:]
                caret -= i1 - i0
            ver += 1
            g.notify("textDocument/didChange", {"textDocument": {"uri": uri(path), "version": ver}, "contentChanges": [{"text": edited}]})
            t0 = time.perf_counter()
            r = g.request("textDocument/completion", {"textDocument": {"uri": uri(path)}, "position": position(edited, caret)})
            ms = int((time.perf_counter() - t0) * 1000)
            res = r.get("result") or {}
            items = res.get("items", []) if isinstance(res, dict) else (res or [])
            items = sorted(enumerate(items), key=lambda x: (x[1].get("sortText", ""), x[0]))
            labels = [it.get("filterText") or it["label"] for _, it in items]
            rank = next((i + 1 for i, l in enumerate(labels) if l == ident), -1)
            if rank < 0 and s["kind"] == "unimported-package":  # same rule as the IDE sides: the import path or a qualified member
                rank = next((i + 1 for i, l in enumerate(labels) if l == s["importPath"] or l.startswith(ident + ".")), -1)
            imported = None
            if s["kind"] == "unimported-package" and rank > 0:
                edits = items[rank - 1][1].get("additionalTextEdits") or []
                imported = any(s["importPath"] in e.get("newText", "") for e in edits)
            attempts.append({"site": s["id"], "file": s["file"], "line": s["line"], "ident": ident, "kind": s["kind"], "sub": s["sub"],
                             "prefix": prefix, "rank": rank, "size": len(labels), "millis": ms, "top": labels[:5], "importAdded": imported,
                             "error": r.get("error")})
            ver += 1
            g.notify("textDocument/didChange", {"textDocument": {"uri": uri(path), "version": ver}, "contentChanges": [{"text": text}]})
        if s["id"] % 20 == 0:
            print(f"site {s['id']}/{len(sites)}", flush=True)
    g.request("shutdown", None)
    g.notify("exit", None)
    json.dump({"gopls": version, "attempts": attempts}, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()

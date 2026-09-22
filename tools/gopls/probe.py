"""Asks gopls, without an IDE, what it offers: diagnostics of a file and the code actions at places of it. Not a part of the plugin build.

    python tools/gopls/probe.py PROJECT_DIR FILE [LINE[:COL[-LINE:COL]] ...] [--options JSON]

LINE and COL are 1-based; a single position is an empty range (the caret), `7:2-7:14` is a selection. Without positions only the
diagnostics are printed. `--options` are the `initializationOptions`, as the plugin sends them (see GoplsOptions in the lsp package).
"""
import json
import os
import subprocess
import sys
import threading
import time

args = sys.argv[1:]
options = {"staticcheck": True, "semanticTokens": True}
if "--options" in args:
    i = args.index("--options")
    options = json.loads(args[i + 1])
    del args[i:i + 2]
if len(args) < 2:
    raise SystemExit(__doc__)
root, file = os.path.abspath(args[0]), os.path.abspath(args[1])


def uri(path):
    return "file:///" + path.replace("\\", "/").lstrip("/")


process = subprocess.Popen(["gopls", "serve"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd=root)
responses, diagnostics, lock = {}, {}, threading.Lock()


def reader():
    while True:
        header = b""
        while not header.endswith(b"\r\n\r\n"):
            byte = process.stdout.read(1)
            if not byte:
                return
            header += byte
        length = int([line for line in header.split(b"\r\n") if line.lower().startswith(b"content-length")][0].split(b":")[1])
        message = json.loads(process.stdout.read(length))
        with lock:
            if "id" in message and "method" not in message:
                responses[message["id"]] = message
            elif message.get("method") == "textDocument/publishDiagnostics":
                diagnostics[message["params"]["uri"]] = message["params"]["diagnostics"]
            elif message.get("method") == "window/showMessage":
                # what the IDE shows in a balloon: this is how gopls complains about settings it does not like
                print("gopls says:", message["params"]["message"])
        # requests of the server (configuration, registerCapability, progress tokens) get an empty answer
        if "id" in message and "method" in message:
            send({"jsonrpc": "2.0", "id": message["id"], "result": [options] if message["method"] == "workspace/configuration" else None})


def send(message):
    body = json.dumps(message).encode()
    process.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    process.stdin.flush()


counter = [0]


def request(method, params, timeout=60):
    counter[0] += 1
    rid = counter[0]
    send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
    deadline = time.time() + timeout
    while time.time() < deadline:
        with lock:
            if rid in responses:
                return responses.pop(rid)
        time.sleep(0.05)
    raise SystemExit("no answer to " + method)


threading.Thread(target=reader, daemon=True).start()
kinds = ["quickfix", "refactor", "refactor.extract", "refactor.inline", "refactor.rewrite", "source", "source.organizeImports", "source.fixAll"]
request("initialize", {
    "processId": os.getpid(), "rootUri": uri(root), "workspaceFolders": [{"uri": uri(root), "name": "probe"}], "initializationOptions": options,
    "capabilities": {"textDocument": {"codeAction": {"codeActionLiteralSupport": {"codeActionKind": {"valueSet": kinds}}, "resolveSupport": {"properties": ["edit"]}},
                                      "publishDiagnostics": {}}, "workspace": {"configuration": True, "applyEdit": True}},
})
send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
with open(file, encoding="utf-8") as f:
    text = f.read()
send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument": {"uri": uri(file), "languageId": "go", "version": 1, "text": text}}})

deadline = time.time() + 40
while time.time() < deadline and uri(file) not in diagnostics:
    time.sleep(0.2)
time.sleep(3)  # the analyzers report after the type checker
found = diagnostics.get(uri(file), [])
print("diagnostics: %d" % len(found))
for d in found:
    print("  %d:%d %s [%s] %s" % (d["range"]["start"]["line"] + 1, d["range"]["start"]["character"] + 1, d.get("source", ""), d.get("code", ""), d["message"].splitlines()[0][:110]))


def position(spec):
    line, _, column = spec.partition(":")
    return {"line": int(line) - 1, "character": int(column or 1) - 1}


for spec in args[2:]:
    start, _, end = spec.partition("-")
    rng = {"start": position(start), "end": position(end or start)}
    inside = [d for d in found if d["range"]["start"]["line"] <= rng["start"]["line"] <= d["range"]["end"]["line"]]
    answer = request("textDocument/codeAction", {"textDocument": {"uri": uri(file)}, "range": rng, "context": {"diagnostics": inside}})
    print("code actions at %s: %d" % (spec, len(answer.get("result") or [])))
    for action in answer.get("result") or []:
        print("  [%s] %s" % (action.get("kind", "command"), action.get("title")))

send({"jsonrpc": "2.0", "id": 9999, "method": "shutdown", "params": None})
time.sleep(0.5)
send({"jsonrpc": "2.0", "method": "exit", "params": None})
process.wait(timeout=10)

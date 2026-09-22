"""Prints what gopls would insert for a completion: the items at the end of a marker in a file. Not a part of the plugin build.

    python tools/gopls/completion.py PROJECT_DIR FILE MARKER [--options JSON]

The completion is asked right after the first occurrence of MARKER (`os.Op`). Shows label, insertTextFormat (1 plain, 2 snippet) and the text.
"""
import json
import os
import subprocess
import sys
import threading
import time

args = sys.argv[1:]
options = {}
if "--options" in args:
    i = args.index("--options")
    options = json.loads(args[i + 1])
    del args[i:i + 2]
root, file, marker = os.path.abspath(args[0]), os.path.abspath(args[1]), args[2]


def uri(path):
    return "file:///" + path.replace("\\", "/").lstrip("/")


process = subprocess.Popen(["gopls", "serve"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd=root)
responses = {}


def send(message):
    body = json.dumps(message).encode()
    process.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    process.stdin.flush()


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
        if "id" in message and "method" not in message:
            responses[message["id"]] = message
        elif "id" in message:
            send({"jsonrpc": "2.0", "id": message["id"], "result": [options] if message["method"] == "workspace/configuration" else None})


def request(rid, method, params):
    send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
    while rid not in responses:
        time.sleep(0.05)
    return responses[rid]


threading.Thread(target=reader, daemon=True).start()
request(1, "initialize", {
    "processId": os.getpid(), "rootUri": uri(root), "initializationOptions": options,
    "capabilities": {"workspace": {"configuration": True}, "textDocument": {"completion": {"completionItem": {"snippetSupport": True}}}},
})
send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
with open(file, encoding="utf-8") as f:
    text = f.read()
send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument": {"uri": uri(file), "languageId": "go", "version": 1, "text": text}}})
time.sleep(4)
offset = text.index(marker) + len(marker)
line = text.count("\n", 0, offset)
column = offset - (text.rfind("\n", 0, offset) + 1)
result = request(2, "textDocument/completion", {"textDocument": {"uri": uri(file)}, "position": {"line": line, "character": column}})["result"]
for item in (result.get("items") or [])[:5]:
    edit = item.get("textEdit") or {}
    print("%-12s format=%s  insert=%r  additional=%s" % (item["label"], item.get("insertTextFormat"), edit.get("newText", item.get("insertText")), json.dumps(item.get("additionalTextEdits"))[:160]))

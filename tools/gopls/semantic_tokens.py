"""Prints the semantic tokens gopls reports for a file: line, text, type, modifiers. Not a part of the plugin build.

    python tools/gopls/semantic_tokens.py PROJECT_DIR FILE [FIRST_LINE LAST_LINE]

What the colours of the editor are made of: the plugin maps these types and modifiers to text attributes (GoplsSemanticTokens in the lsp package).
"""
import json
import os
import subprocess
import sys
import threading
import time

root, file = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
first, last = (int(sys.argv[3]), int(sys.argv[4])) if len(sys.argv) > 4 else (1, 10 ** 6)


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
            send({"jsonrpc": "2.0", "id": message["id"], "result": [{"semanticTokens": True}] if message["method"] == "workspace/configuration" else None})


def request(rid, method, params):
    send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
    while rid not in responses:
        time.sleep(0.05)
    return responses[rid]


threading.Thread(target=reader, daemon=True).start()
types = ["namespace", "type", "class", "enum", "interface", "struct", "typeParameter", "parameter", "variable", "property", "enumMember", "event", "function",
         "method", "macro", "keyword", "modifier", "comment", "string", "number", "regexp", "operator", "decorator", "label"]
modifiers = ["declaration", "definition", "readonly", "static", "deprecated", "abstract", "async", "modification", "documentation", "defaultLibrary"]
initialized = request(1, "initialize", {
    "processId": os.getpid(), "rootUri": uri(root), "initializationOptions": {"semanticTokens": True},
    "capabilities": {"workspace": {"configuration": True}, "textDocument": {"semanticTokens": {
        "requests": {"full": True}, "tokenTypes": types, "tokenModifiers": modifiers, "formats": ["relative"]}}},
})
legend = initialized["result"]["capabilities"]["semanticTokensProvider"]["legend"]
print("types:    ", legend["tokenTypes"])
print("modifiers:", legend["tokenModifiers"])
send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
with open(file, encoding="utf-8") as f:
    text = f.read()
send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument": {"uri": uri(file), "languageId": "go", "version": 1, "text": text}}})
time.sleep(4)
data = request(2, "textDocument/semanticTokens/full", {"textDocument": {"uri": uri(file)}})["result"]["data"]
lines = text.split("\n")
line = column = 0
for i in range(0, len(data), 5):
    delta_line, delta_column, length, token_type, token_modifiers = data[i:i + 5]
    line, column = (line + delta_line, delta_column) if delta_line else (line, column + delta_column)
    if first <= line + 1 <= last and legend["tokenTypes"][token_type] not in ("keyword", "comment", "string", "number", "operator"):
        mods = [m for bit, m in enumerate(legend["tokenModifiers"]) if token_modifiers & (1 << bit)]
        print("%4d  %-16s %-14s %s" % (line + 1, lines[line][column:column + length], legend["tokenTypes"][token_type], " ".join(mods)))

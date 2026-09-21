"""Starts `dlv dap`, prints how it announces its port and what it answers to `initialize`. Not a part of the plugin build.

    python tools/dlv-dap/probe.py
"""
import json
import re
import socket
import subprocess
import sys


def send(sock, seq, command, arguments):
    body = json.dumps({"seq": seq, "type": "request", "command": command, "arguments": arguments}).encode()
    sock.sendall(b"Content-Length: %d\r\n\r\n" % len(body) + body)


def receive(sock):
    header = b""
    while not header.endswith(b"\r\n\r\n"):
        header += sock.recv(1)
    length = int(re.search(rb"Content-Length: (\d+)", header).group(1))
    body = b""
    while len(body) < length:
        body += sock.recv(length - len(body))
    return json.loads(body)


process = subprocess.Popen(["dlv", "dap", "--listen=127.0.0.1:0"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
line = process.stdout.readline().strip()
print("announce:", repr(line))
port = int(re.search(r":(\d+)\s*$", line).group(1))
with socket.create_connection(("127.0.0.1", port)) as sock:
    send(sock, 1, "initialize", {"adapterID": "go", "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path"})
    response = receive(sock)
    print(json.dumps(response.get("body"), indent=2))
    send(sock, 2, "disconnect", {})
process.wait(timeout=10)
print("exit code:", process.returncode)
sys.exit(0)

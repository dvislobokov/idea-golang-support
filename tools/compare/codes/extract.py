"""Examples of $GOROOT/src/internal/types/errors/codes.go as one package each, for the error-code coverage check.

Same rules as TestErrorCodeExamples (codes_test.go): the doc comment of a code is split at "Example:", each part trimmed, and
`package p` is prepended when it has no package clause. Writes <out>/go.mod, <out>/ex/<Code>_<n>/example.go and <out>/codes.json
(code -> value, doc summary, examples), codes without an example included.

    python tools/compare/codes/extract.py [out=build/compare/codes]
"""
import json
import os
import re
import shutil
import subprocess
import sys
import textwrap

out = sys.argv[1] if len(sys.argv) > 1 else "build/compare/codes"
goroot = subprocess.run(["go", "env", "GOROOT"], capture_output=True, text=True, check=True).stdout.strip()
src = open(os.path.join(goroot, "src", "internal", "types", "errors", "codes.go"), encoding="utf-8").read()
# the second const block: the first one holds InvalidSyntaxTree = -1 only
body = src[src.index("// The zero Code value"):]

codes = []
comment = []
value = -1
for raw in body.splitlines()[1:]:
    line = raw.strip()
    if line == ")":
        break
    if line.startswith("//"):
        text = line[2:]
        comment.append(text[1:] if text.startswith(" ") else text)
        continue
    if not line:
        comment = [] if not comment else comment + [""]
        continue
    m = re.match(r"^(\w+)(\s+Code\s*=\s*iota)?$", line)
    if m:
        value += 1
        name = m.group(1)
        doc = "\n".join(comment).strip("\n")
        if name != "_":
            parts = doc.split("Example:")
            # the code of an example is indented one space more than the text ("//  import ..."): dedent it
            examples = [textwrap.dedent(p.strip("\n")).strip() for p in parts[1:]]
            codes.append({"name": name, "value": value, "summary": parts[0].strip().split("\n\n")[0].replace("\n", " "),
                          "deprecated": "Deprecated:" in doc, "examples": examples})
    comment = []

if os.path.isdir(out):
    shutil.rmtree(out)
os.makedirs(os.path.join(out, "ex"))
with open(os.path.join(out, "go.mod"), "w", encoding="utf-8", newline="\n") as f:
    f.write("module codes\n\ngo 1.27\n")
count = 0
for c in codes:
    for i, example in enumerate(c["examples"], 1):
        text = example if example.startswith("package") else "package p\n\n" + example
        d = os.path.join(out, "ex", "%s_%d" % (c["name"], i))
        os.makedirs(d)
        with open(os.path.join(d, "example.go"), "w", encoding="utf-8", newline="\n") as f:
            f.write(text + "\n")
        count += 1
with open(os.path.join(out, "codes.json"), "w", encoding="utf-8") as f:
    json.dump(codes, f, indent=1, ensure_ascii=False)
print("%d codes (%d with examples, %d deprecated), %d examples -> %s" % (
    len(codes), sum(1 for c in codes if c["examples"]), sum(1 for c in codes if c["deprecated"]), count, out))

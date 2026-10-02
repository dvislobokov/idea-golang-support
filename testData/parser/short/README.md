# Parser short-snippet conformance data

Frozen snapshot of the `valids` and `invalids` lists from `src/go/parser/short_test.go`
of **Go 1.27.1**. Generated data is committed on purpose: it must not change when the local
Go toolchain is upgraded. Snippets are byte-identical to the Go string literals.

- `valid/NNN.go`: snippet that must parse without errors; `valid/index.txt`:
  `NNN<TAB>snippet` (snippet is a Go-quoted string, one line).
- `invalid/NNN.go`: snippet containing `/* ERROR "msg" */` comments marking the error
  position and expected message substring; `invalid/index.txt`:
  `NNN<TAB>expected-error-substring<TAB>snippet` (several expected messages are joined with
  ` | `; snippets 028 and 030 use `ERROR HERE`, the message is still extracted).

Regeneration (from the repository root; only after a deliberate Go upgrade):

```
go run -C tools/portshorttest . -out "$PWD/testData/parser/short"
```

Optional flag: `-goroot <dir>` (default: `go env GOROOT`).

# Generates docs/LINT-RULES.md from the compact table below.
# Generates docs/LINT-RULES.md: python tools/lint-rules/gen_rules.py docs/LINT-RULES.md tools/lint-rules/plan.md
# Row: id | what | scope | needs | status | size | default | options
# scope: C E S F T Fi P M ; needs: Y T F A X joined by '+'
# status: n:Short (native), n~:Short (native, partial), p (port), a (port-approx), k:reason (skip), f (formatter)
# default: 12 = v1+v2, 2 = v2 only, - = no
import io, sys, collections, re

SCOPE = {"C": "CALL", "E": "EXPRESSION", "S": "STATEMENT", "F": "FUNCTION", "T": "TYPE_SPEC", "Fi": "FILE", "P": "PACKAGE", "M": "MODULE"}
NEEDS = {"Y": "SYNTAX", "T": "TYPES", "F": "FLOW", "A": "SSA-heavy", "X": "PROJECT_INDEX"}
LICENSE = {
    "errcheck": "MIT†", "govet": "BSD-3-Clause (x/tools)", "staticcheck": "MIT", "unused": "MIT (staticcheck)", "ineffassign": "MIT†",
    "revive": "MIT", "gocritic": "MIT", "gosec": "Apache-2.0", "errorlint": "MIT†", "bodyclose": "MIT†", "nilerr": "MIT†", "nilnil": "MIT†",
    "rowserrcheck": "MIT†", "sqlclosecheck": "MIT†", "noctx": "MIT†", "misspell": "MIT†", "unconvert": "BSD-3-Clause†", "unparam": "BSD-3-Clause†",
    "prealloc": "MIT†", "dupword": "MIT†", "goconst": "MIT†", "gocyclo": "BSD-3-Clause†", "cyclop": "MIT†", "funlen": "MIT†", "nestif": "BSD-2-Clause†",
    "wastedassign": "MIT†", "makezero": "MIT†", "exhaustive": "BSD-2-Clause†", "forcetypeassert": "MIT†", "contextcheck": "Apache-2.0†",
    "containedctx": "MIT†", "errname": "MIT†", "nakedret": "MIT†", "nolintlint": "GPL-3.0 (golangci-lint)", "predeclared": "BSD-2-Clause†",
    "usestdlibvars": "MIT†", "perfsprint": "MIT†", "copyloopvar": "MIT†", "intrange": "MIT†", "testifylint": "MIT†", "thelper": "MIT†",
    "tparallel": "MIT†", "paralleltest": "MIT†", "gofmt": "BSD-3-Clause (Go)", "gofumpt": "BSD-3-Clause†", "goimports": "BSD-3-Clause (x/tools)", "gci": "BSD-3-Clause†",
    "golines": "MIT†", "goland": "— (behavior only; closed-source product)",
}

SECTIONS = collections.OrderedDict()

def section(title, linter, body):
    rows = []
    for line in body.strip().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in re.split(r"(?<!\\)\|", line)]
        while len(parts) < 8:
            parts.append("")
        rid, what, scope, needs, status, size, dflt, opts = parts[:8]
        rows.append((rid, linter, what, scope, needs, status, size, dflt, opts))
    SECTIONS[title] = rows

# ---------------------------------------------------------------- golangci default set (non-staticcheck)
section("errcheck, ineffassign, unused", None, "")
SECTIONS["errcheck, ineffassign, unused"] = [
    ("errcheck", "errcheck", "error result (or type assertion `ok`) dropped", "C", "T", "n:GoUncheckedError", "M", "12",
     "`exclude-functions`, `check-type-assertions`, `check-blank`, `disable-default-exclusions`"),
    ("ineffassign", "ineffassign", "assignment whose value is never read", "F", "F", "n:GoIneffectualAssignment", "M", "12", "—"),
    ("unused", "unused", "U1000: unused unexported func/type/const/var/field/method (exported only in `package main` / internal)", "P", "T+X",
     "n~:GoUnusedVariable, GoUnusedParameter, GoUnusedExported (unexported package-level decls and fields still to port)", "L", "12",
     "`field-writes-are-uses`, `exported-fields-are-used`, `parameters-are-used`, `local-variables-are-used`, `generated-is-used`"),
]

# ---------------------------------------------------------------- govet analyzers
section("govet analyzers", "govet", r"""
govet:appends | `append(s)` with no values to add | C | T | p | S | 12 | —
govet:asmdecl | Go declaration does not match the assembly (`.s`) frame | Fi | T | k: assembly files, low value in an editor | L | 12 | —
govet:assign | `x = x` | S | Y | n:GoSelfAssignment | S | 12 | —
govet:atomic | `x = atomic.AddInt64(&x, 1)` | S | T | p | S | 12 | —
govet:atomicalign | 64-bit atomic field not 64-bit aligned on 32-bit platforms | C | T | p | M | - | —
govet:bools | redundant / suspect `a == x \|\| a == x`, `a != 1 \|\| a != 2` | E | T | p | M | 12 | —
govet:buildtag | malformed or misplaced `//go:build` / `+build` | Fi | Y | n:GoBuildConstraint | S | 12 | —
govet:cgocall | Go pointers passed to C violating cgo rules | C | T | k: cgo-only, needs cgo type info | L | 12 | —
govet:composites | unkeyed fields in a composite literal of an imported struct type | E | T | p | S | 12 | `composites.whitelist`
govet:copylocks | lock value copied (assignment, call, range, return) | E | T | n:GoCopyLocks | M | 12 | —
govet:deepequalerrors | `reflect.DeepEqual` on errors | C | T | p | S | - | —
govet:defers | `defer log.Println(time.Since(start))` evaluates args early | S | T | p | S | 12 | —
govet:directive | misplaced or unknown `//go:debug` directive | Fi | Y | p | S | 12 | —
govet:errorsas | `errors.As` second argument not a non-nil pointer | C | T | n:GoErrorsPackage | S | 12 | —
govet:fieldalignment | struct fields ordered wastefully | T | T | k: noisy; host has Reorder Fields intention (`GoReorderFieldsIntention`) | M | - | —
govet:findcall | demo analyzer | C | Y | k: test analyzer | S | - | —
govet:framepointer | assembly clobbers frame pointer | Fi | Y | k: assembly | M | 12 | —
govet:hostport | `fmt.Sprintf("%s:%d", host, port)` not IPv6-safe | C | T | p | S | 12 | —
govet:httpmux | `http.ServeMux` pattern with Go 1.22 syntax under older go version | C | T | p | S | 12 | —
govet:httpresponse | `resp.Body` used/deferred before the error check | S | T+F | n:GoDeferBeforeErrorCheck | S | 12 | —
govet:ifaceassert | impossible interface-to-interface assertion | E | T | p | S | 12 | —
govet:loopclosure | loop variable captured by `go`/`defer` closure (go < 1.22) | S | T | n:GoLoopClosure | S | 12 | —
govet:lostcancel | cancel of `context.WithCancel` not called on all paths | F | T+F | n:GoLostCancel | M | 12 | —
govet:nilfunc | comparison of a function with nil | E | T | p | S | 12 | —
govet:nilness | nil dereference / impossible nil comparison | F | T+F | n:GoNilDereference, GoImpossibleNilCheck | L | - | —
govet:printf | printf format/argument mismatch, non-constant format | C | T | n:GoPrintf | L | 12 | `printf.funcs`
govet:reflectvaluecompare | `reflect.Value` compared with `==` | E | T | p | S | - | —
govet:shadow | variable shadows an outer one that is used after | F | T+F | n~:GoShadowedError (errors only; generalize behind `strict`) | M | - | `shadow.strict`
govet:shift | shift count >= width of the operand | E | T | p | S | 12 | —
govet:sigchanyzer | unbuffered channel passed to `signal.Notify` | C | T | p | S | 12 | —
govet:slog | `slog` key/value pairs mismatched | C | T | p | M | 12 | —
govet:sortslice | `sort.Slice` called on a non-slice | C | T | p | S | - | —
govet:stdmethods | well-known method (`String`, `ReadFrom`, `MarshalJSON`…) with wrong signature | F | T | p | M | 12 | —
govet:stdversion | std symbol newer than the module's `go` version | E | T+X | p | M | 12 | —
govet:stringintconv | `string(int)` conversion | E | T | p | S | 12 | —
govet:structtag | malformed or duplicate struct tag | T | Y | n:GoStructTag | S | 12 | —
govet:testinggoroutine | `t.Fatal` from a goroutine started by the test | C | T | n:GoTestingGoroutine | S | 12 | —
govet:tests | malformed `Test`/`Example`/`Benchmark`/`Fuzz` names and signatures | F | T | p | M | 12 | —
govet:timeformat | layout `2006-02-01` | C | T | n:GoTimeLayout | S | 12 | —
govet:unmarshal | non-pointer passed to `json.Unmarshal` & co. | C | T | p | S | 12 | —
govet:unreachable | unreachable code | F | F | n:GoUnreachableCode | S | 12 | —
govet:unsafeptr | invalid `uintptr` -> `unsafe.Pointer` conversion | E | T | p | M | 12 | —
govet:unusedresult | result of pure function (`fmt.Sprintf`, `errors.New`…) dropped | C | T | n:GoUnusedResult | S | 12 | `unusedresult.funcs`, `stringmethods`
govet:unusedwrite | write to a struct field/array element never read | F | A | a | L | - | —
govet:waitgroup | `wg.Add` inside the goroutine | S | T | n:GoWaitGroupAddInGoroutine | S | 12 | —
""")

# ---------------------------------------------------------------- staticcheck SA
section("staticcheck SA (bugs)", "staticcheck", r"""
SA1000 | invalid regexp in a constant argument | C | T | p | S | 12 | —
SA1001 | invalid `text/template` / `html/template` | C | T | p | M | 12 | —
SA1002 | impossible `time.Parse` layout | C | T | n~:GoTimeLayout (notation checks; full layout validation to port) | M | 12 | —
SA1003 | `encoding/binary` with a type of undefined size | C | T | p | S | 12 | —
SA1004 | `time.Sleep(1)` with a small untyped constant (nanoseconds) | C | T | p | S | 12 | —
SA1005 | `exec.Command("ls -l")`: arguments inside the program name | C | T | p | S | 12 | —
SA1006 | `Printf(dynamic)` without arguments | C | T | p | S | 12 | —
SA1007 | constant URL that does not parse | C | T | p | S | 12 | —
SA1008 | non-canonical key in direct `http.Header` map access | E | T | p | S | 12 | —
SA1010 | `FindAll(…, 0)` returns nothing | C | T | p | S | 12 | —
SA1011 | invalid UTF-8 constant passed to `strings` API | C | T | p | S | 12 | —
SA1012 | nil `context.Context` passed | C | T | p | S | 12 | —
SA1013 | `Seek(io.SeekStart, 0)`: offset and whence swapped | C | T | p | S | 12 | —
SA1014 | non-pointer passed to `Unmarshal`/`Decode` | C | T | p | S | 12 | —
SA1015 | `time.Tick` leaking a ticker (go < 1.23) | C | T | p | S | 12 | —
SA1016 | trapping `SIGKILL`/`SIGSTOP` | C | T | p | S | 12 | —
SA1017 | unbuffered channel for `signal.Notify` | C | T | p | S | 12 | —
SA1018 | `strings.Replace(…, 0)` replaces nothing | C | T | p | S | 12 | —
SA1019 | use of a deprecated identifier (`Deprecated:` paragraph) | E | T+X | p | M | 12 | —
SA1020 | invalid `host:port` constant | C | T | p | S | 12 | —
SA1021 | `bytes.Equal` on `net.IP` | C | T | p | S | 12 | —
SA1023 | `io.Writer` implementation modifies its buffer | F | A | a | M | 12 | —
SA1024 | duplicate characters in a `Trim` cutset | C | T | p | S | 12 | —
SA1025 | `Timer.Reset` return value used incorrectly | C | T+F | a | M | 12 | —
SA1026 | marshaling channels or functions | C | T | p | S | 12 | —
SA1027 | misaligned 64-bit atomic access | C | T | p | M | 12 | —
SA1028 | `sort.Slice` on a non-slice | C | T | p | S | 12 | —
SA1029 | built-in type as `context.WithValue` key | C | T | p | S | 12 | —
SA1030 | invalid `strconv` base / bitSize | C | T | p | S | 12 | —
SA1031 | overlapping src/dst for an encoder | C | T | a | M | 12 | —
SA1032 | `errors.Is(target, err)` arguments swapped | C | T | p | S | 12 | —
SA2000 | `wg.Add` inside the goroutine | S | T | n:GoWaitGroupAddInGoroutine | S | 12 | —
SA2001 | empty critical section `mu.Lock(); mu.Unlock()` | S | T | p | S | 12 | —
SA2002 | `t.FailNow` from a goroutine | C | T | n:GoTestingGoroutine | S | 12 | —
SA2003 | `defer mu.Lock()` right after `Lock` | S | T | p | S | 12 | —
SA3000 | `TestMain` without `os.Exit` (go < 1.15) | F | T | k: obsolete since Go 1.15 | S | 12 | —
SA3001 | assignment to `b.N` | S | T | p | S | 12 | —
SA4000 | identical operands of a binary expression (`x == x`) | E | T | p | S | 12 | —
SA4001 | `&*x` | E | T | p | S | 12 | —
SA4003 | unsigned compared `< 0` / `>= 0` | E | T | p | S | 12 | —
SA4004 | loop exits unconditionally after one iteration | S | F | p | M | 12 | —
SA4005 | field assignment to a value receiver never observed | F | T+F | a | M | 12 | —
SA4006 | value assigned and never read | F | F | n:GoIneffectualAssignment | M | 12 | —
SA4008 | loop condition variable never changes | S | F | a | M | 12 | —
SA4009 | argument overwritten before first use | F | F | p | M | 12 | —
SA4010 | `append` result never observed | F | F | a | M | 12 | —
SA4011 | `break` in a `switch`/`select` inside a loop | S | Y | p | S | 12 | —
SA4012 | comparison with `NaN` | E | T | p | S | 12 | —
SA4013 | `!!b` | E | Y | p | S | 12 | —
SA4014 | duplicate condition in an `if`/`else if` chain | S | T | p | S | 12 | —
SA4015 | `math.Ceil(float64(i))` of an integer | C | T | p | S | 12 | —
SA4016 | `x ^ 0`, `x & 0`, `x << 0` | E | T | p | S | 12 | —
SA4017 | result of a pure function discarded | C | T | n:GoUnusedResult | S | 12 | —
SA4018 | self-assignment | S | Y | n:GoSelfAssignment | S | 12 | —
SA4019 | duplicate build constraints | Fi | Y | p | S | 12 | —
SA4020 | unreachable case in a type switch | S | T | p | M | 12 | —
SA4021 | `x = append(y)` | C | T | p | S | 12 | —
SA4022 | `&x == nil` | E | T | p | S | 12 | —
SA4023 | impossible comparison of an interface with nil (typed nil) | E | T+F | a | L | 12 | —
SA4024 | `len(x) < 0` | E | T | p | S | 12 | —
SA4025 | integer division of constants yields 0 | E | T | p | S | 12 | —
SA4026 | `-0.0` constant | E | Y | p | S | 12 | —
SA4027 | `u.Query().Set(...)` on a copy | C | T | p | S | 12 | —
SA4028 | `x % 1` | E | T | p | S | 12 | —
SA4029 | `sort.IntSlice(x)` as a statement | S | T | p | S | 12 | —
SA4030 | `rand.New(...)` result discarded | C | T | p | S | 12 | —
SA4031 | nil check of a never-nil value | E | T+F | n:GoImpossibleNilCheck | S | 12 | —
SA4032 | `runtime.GOOS == "linx"` | E | T | p | S | 12 | —
SA5000 | assignment to a nil map | S | T+F | p | M | 12 | —
SA5001 | `defer f.Close()` before the error check | S | T+F | n:GoDeferBeforeErrorCheck | S | 12 | —
SA5002 | empty `for {}` spins | S | Y | p | S | 12 | —
SA5003 | `defer` in an infinite loop | S | Y | p | S | 12 | —
SA5004 | `for { select { … default: } }` busy loop | S | Y | p | S | 12 | —
SA5005 | finalizer references the finalized object | C | T | p | M | 12 | —
SA5007 | infinite recursion | F | T+F | p | M | 12 | —
SA5008 | invalid struct tag | T | Y | n:GoStructTag | S | 12 | —
SA5009 | printf format mismatch | C | T | n:GoPrintf | S | 12 | —
SA5010 | impossible type assertion | E | T | p | M | 12 | —
SA5011 | nil pointer dereference after a nil check | F | T+F | n:GoNilDereference | L | 12 | —
SA5012 | odd-length slice to a pairs function | C | T | p | M | 12 | —
SA6000 | `regexp.MustCompile` in a loop | S | T | p | S | 12 | —
SA6001 | `m[string(b)]` hoisted out of the index | E | T | p | S | 12 | —
SA6002 | non-pointer value put into `sync.Pool` | C | T | p | S | 12 | —
SA6003 | `range []rune(s)` | S | T | p | S | 12 | —
SA6005 | `strings.ToLower(a) == strings.ToLower(b)` | E | T | p | S | 12 | —
SA6006 | `io.WriteString(w, string(b))` | C | T | p | S | 12 | —
SA9001 | `defer` in a `range` loop | S | Y | n:GoDeferInLoop | S | 12 | —
SA9002 | file mode looks like a forgotten octal (`644`) | C | T | p | S | 12 | —
SA9003 | empty branch | S | Y | p | S | 12 | —
SA9004 | only the first constant of a group has an explicit type | T | T | p | S | 12 | —
SA9005 | marshaling a struct with no exported fields | C | T | p | S | 12 | —
SA9006 | shift in a too-narrow type before widening | E | T | p | S | 12 | —
SA9007 | `os.RemoveAll` of a directory that should not be deleted | C | T | p | S | 12 | —
SA9008 | `else` branch of a type assertion reads the shadowed zero value | S | T | p | M | 12 | —
SA9009 | `// go:generate` with a space is not a directive | Fi | Y | p | S | 12 | —
SA9010 | `defer setup()` instead of `defer setup()()` | S | T | p | S | 12 | —
""")

section("staticcheck S (simple, former gosimple)", "staticcheck", r"""
S1000 | single-case `select` | S | Y | p | S | 12 | —
S1001 | element copy loop -> `copy` | S | T | p | M | 12 | —
S1002 | `if x == true` | E | T | p | S | 12 | —
S1003 | `strings.Index(…) != -1` -> `Contains` | E | T | p | S | 12 | —
S1004 | `bytes.Compare(a, b) == 0` -> `bytes.Equal` | E | T | p | S | 12 | —
S1005 | `for x, _ = range` / `x, _ = <-ch` | S | Y | p | S | 12 | —
S1006 | `for true {}` | S | Y | p | S | 12 | —
S1007 | regexp in an interpreted string -> raw string | C | T | p | S | 12 | —
S1008 | `if c { return true }; return false` | S | Y | p | S | 12 | —
S1009 | `x != nil && len(x) != 0` | E | T | p | S | 12 | —
S1010 | `s[a:len(s)]` | E | T | p | S | 12 | —
S1011 | append loop -> `append(a, b...)` | S | T | p | S | 12 | —
S1012 | `time.Now().Sub(t)` -> `time.Since` | C | T | p | S | 12 | —
S1016 | field-by-field copy -> conversion | E | T | p | M | 12 | —
S1017 | `if HasPrefix { s = s[n:] }` -> `TrimPrefix` | S | T | p | M | 12 | —
S1018 | shifting loop -> `copy` | S | T | p | M | 12 | —
S1019 | redundant `make` length/capacity | C | T | p | S | 12 | —
S1020 | `if _, ok := x.(T); ok && x != nil` | E | T | p | S | 12 | —
S1021 | `var x T; x = v` -> one statement | S | Y | p | S | 12 | —
S1023 | redundant trailing `return`/`break` | S | Y | p | S | 12 | —
S1024 | `t.Sub(time.Now())` -> `time.Until` | C | T | p | S | 12 | —
S1025 | `fmt.Sprintf("%s", s)` of a string | C | T | p | S | 12 | —
S1028 | `errors.New(fmt.Sprintf(…))` -> `fmt.Errorf` | C | T | p | S | 12 | —
S1029 | `range []rune(s)` -> `range s` | S | T | p | S | 12 | —
S1030 | `string(buf.Bytes())` -> `buf.String()` | E | T | p | S | 12 | —
S1031 | nil check around `range` | S | T | p | S | 12 | —
S1032 | `sort.Sort(sort.IntSlice(x))` -> `sort.Ints` | C | T | p | S | 12 | —
S1033 | `if _, ok := m[k]; ok { delete(m, k) }` | S | T | p | S | 12 | —
S1034 | repeated assertions in a type switch -> bind the variable | S | T | p | M | 12 | —
S1035 | `CanonicalHeaderKey` inside `Header.Add/Set/Get/Del` | C | T | p | S | 12 | —
S1036 | guard before map increment / append | S | T | p | S | 12 | —
S1037 | `select { case <-time.After(d): }` -> `time.Sleep` | S | T | p | S | 12 | —
S1038 | `Print(Sprintf(…))` -> `Printf` | C | T | p | S | 12 | —
S1039 | `fmt.Sprint("literal")` | C | T | p | S | 12 | —
S1040 | assertion to the current interface type | E | T | p | S | 12 | —
""")

section("staticcheck ST (stylecheck)", "staticcheck", r"""
ST1000 | missing or malformed package comment | P | Y | p | S | - | —
ST1001 | dot import | Fi | Y | p | S | 2 | `dot-import-whitelist`
ST1003 | naming (MixedCaps, initialisms) | Fi | Y | p | M | - | `initialisms`
ST1005 | error string capitalized or ending with punctuation | C | T | p | S | 2 | —
ST1006 | receiver named `self`/`this` | F | Y | p | S | 2 | —
ST1008 | `error` not the last result | F | T | p | S | 2 | —
ST1011 | `time.Duration` variable named with a unit suffix | T | T | p | S | 2 | —
ST1012 | error variable not named `ErrX`/`errX` | T | T | p | S | 2 | —
ST1013 | HTTP status as a number | C | T | p | S | 2 | `http-status-code-whitelist`
ST1015 | `default` in the middle of a switch | S | Y | p | S | 2 | —
ST1016 | receivers of one type named differently | P | Y | p | S | - | —
ST1017 | Yoda condition `nil == x` | E | Y | p | S | 2 | —
ST1018 | invisible / control characters in a string literal | E | Y | p | S | 2 | —
ST1019 | package imported twice | Fi | Y | p | S | 2 | —
ST1020 | doc of an exported function does not start with its name | F | Y | n:GoDocComment | S | - | —
ST1021 | doc of an exported type does not start with its name | T | Y | n:GoDocComment | S | - | —
ST1022 | doc of an exported var/const does not start with its name | T | Y | n:GoDocComment | S | - | —
ST1023 | redundant explicit type in `var x T = T(…)` | S | T | p | S | 2 | —
""")

section("staticcheck QF (quickfix)", "staticcheck", r"""
QF1001 | apply De Morgan's law | E | Y | p | S | 2 | —
QF1002 | tagless switch comparing one value -> tagged switch | S | T | p | S | 2 | —
QF1003 | `if/else if` chain on one value -> switch | S | T | p | M | 2 | —
QF1004 | `strings.Replace(…, -1)` -> `ReplaceAll` | C | T | p | S | 2 | —
QF1005 | `math.Pow(x, 2)` -> `x * x` | C | T | p | S | 2 | —
QF1006 | `for { if c { break } }` -> loop condition | S | Y | p | S | 2 | —
QF1007 | conditional assignment merged into the declaration | S | Y | p | S | 2 | —
QF1008 | redundant embedded field in a selector | E | T | p | S | 2 | —
QF1009 | `t1 == t2` on `time.Time` -> `Equal` | E | T | p | S | 2 | —
QF1010 | printing `[]byte` -> explicit `string` conversion | C | T | p | S | 2 | —
QF1011 | redundant type in a variable declaration | S | T | p | S | 2 | —
QF1012 | `w.Write([]byte(fmt.Sprintf(…)))` -> `fmt.Fprintf` | C | T | p | S | 2 | —
""")

# ---------------------------------------------------------------- revive
section("revive rules", "revive", r"""
revive:add-constant | magic number / string literal | E | Y | p | M | - | `allowInts`, `allowStrs`, `maxLitCount`, `ignoreFuncs`
revive:argument-limit | too many parameters | F | Y | p | S | - | max (8)
revive:atomic | `x = atomic.AddInt64(&x, 1)` | S | T | p | S | - | —
revive:banned-characters | identifier contains banned characters | Fi | Y | p | S | - | list of characters
revive:bare-return | naked return | S | Y | p | S | - | —
revive:blank-imports | blank import outside `main`/tests without comment | Fi | Y | p | S | rd | —
revive:bool-literal-in-expr | `x == true`, `b && false` | E | T | p | S | - | —
revive:call-to-gc | `runtime.GC()` | C | T | p | S | - | —
revive:cognitive-complexity | function too complex (cognitive) | F | Y | p | M | - | max (7)
revive:comment-spacings | `//comment` without space | Fi | Y | p | S | - | allowed prefixes (`nolint`, …)
revive:comments-density | too few comments | Fi | Y | k: policy metric, noisy | S | - | min %
revive:confusing-naming | methods differing only by capitalization | P | Y | p | S | - | —
revive:confusing-results | unnamed results of the same type | F | T | p | S | - | —
revive:constant-logical-expr | `x == x`, `x && x` | E | T | p | S | - | —
revive:context-as-argument | `context.Context` not the first parameter | F | T | n:GoContextPlacement | S | rd | `allowTypesBefore`
revive:context-keys-type | built-in type as `WithValue` key | C | T | p | S | rd | —
revive:cyclomatic | cyclomatic complexity over limit | F | Y | p | S | - | max (10)
revive:datarace | named result captured by a goroutine | F | Y | a | M | - | —
revive:deep-exit | `os.Exit`/`log.Fatal` outside `main`/`init` | C | T | p | S | - | —
revive:defer | defer patterns: in loop, recover outside defer, `return` in defer, call chain, method on nil | S | T | n~:GoDeferInLoop (loop only; other subchecks to port) | M | - | subcheck list
revive:dot-imports | dot import | Fi | Y | p | S | rd | `allowedPackages`
revive:duplicated-imports | same path imported twice | Fi | Y | p | S | - | —
revive:early-return | `if c { … } else { return }` -> guard | S | Y | p | M | - | `preserveScope`, `allowJump`
revive:empty-block | empty block | S | Y | p | S | rd | —
revive:empty-lines | leading/trailing blank lines in a block | S | Y | k: formatter territory (gofumpt) | S | - | —
revive:epoch-naming | epoch-valued variable lacks a unit suffix | S | T | p | S | - | —
revive:enforce-map-style | `map[K]V{}` vs `make` | E | Y | p | S | - | `any`/`literal`/`make`
revive:enforce-repeated-arg-type-style | `a int, b int` vs `a, b int` | F | Y | p | S | - | `short`/`full`
revive:enforce-slice-style | `[]T{}` vs `make` vs `nil` | E | Y | p | S | - | `any`/`literal`/`make`/`nil`
revive:enforce-switch-style | `default` presence/position | S | Y | p | S | - | `allowNoDefault`, `allowDefaultNotLast`
revive:error-naming | error variable not `errX`/`ErrX` | T | T | p | S | rd | —
revive:error-return | `error` not the last result | F | T | p | S | rd | —
revive:error-strings | error string capitalized / punctuated | C | T | p | S | rd | extra functions
revive:errorf | `errors.New(fmt.Sprintf(…))` | C | T | p | S | rd | —
revive:exported | exported symbol without doc / stutter / malformed doc | Fi | Y | n:GoDocComment | M | rd | `checkPrivateReceivers`, `disableStutteringCheck`, `sayRepetitiveInsteadOfStutters`
revive:file-header | file lacks configured header | Fi | Y | p | S | - | header regexp
revive:file-length-limit | file too long | Fi | Y | p | S | - | `max`, `skipComments`, `skipBlankLines`
revive:filename-format | file name does not match a pattern | Fi | Y | p | S | - | regexp
revive:flag-parameter | `bool` parameter used as a control flag | F | Y | p | S | - | —
revive:forbidden-call-in-wg-go | `wg.Done()` / panic inside `WaitGroup.Go` | C | T | p | S | - | —
revive:function-length | too many statements / lines | F | Y | p | S | - | max statements, max lines
revive:function-result-limit | too many results | F | Y | p | S | - | max (3)
revive:get-return | `GetX` function with no result | F | Y | p | S | - | —
revive:identical-branches | `if` and `else` bodies identical | S | Y | p | S | - | —
revive:identical-ifelseif-branches | two branches of an `else if` chain identical | S | Y | p | S | - | —
revive:identical-ifelseif-conditions | repeated condition in an `else if` chain | S | Y | p | S | - | —
revive:identical-switch-branches | identical case bodies | S | Y | p | S | - | —
revive:identical-switch-conditions | repeated case expression | S | Y | p | S | - | —
revive:if-return | `if err := f(); err != nil { return err }; return nil` | S | T | p | S | - | —
revive:import-alias-naming | alias does not match a pattern | Fi | Y | p | S | - | allow / deny regexps
revive:import-shadowing | identifier shadows an import | F | T | p | S | - | —
revive:imports-blocklist | blocked import path | Fi | Y | p | S | - | path globs
revive:increment-decrement | `x += 1` -> `x++` | S | Y | p | S | rd | —
revive:indent-error-flow | `else` after `return` in an error branch | S | Y | p | S | rd | `preserveScope`
revive:inefficient-map-lookup | key looked up again after existence check | S | T | p | S | - | —
revive:line-length-limit | line too long | Fi | Y | p | S | - | max (80)
revive:marshal-receiver | `MarshalJSON` on a pointer receiver etc. | F | T | p | S | - | —
revive:max-control-nesting | nesting too deep | F | Y | p | S | - | max (5)
revive:max-public-structs | too many exported structs in a file | Fi | Y | p | S | - | max (5)
revive:modifies-parameter | assignment to a parameter | F | T | p | S | - | —
revive:modifies-value-receiver | assignment to a value receiver's field | F | T | a | S | - | —
revive:multiline-if-init | multi-line `if` init statement | S | Y | p | S | - | —
revive:nested-structs | anonymous struct inside a struct | T | Y | p | S | - | —
revive:optimize-operands-order | expensive operand before cheap one in `&&`/`\|\|` | E | T | p | M | - | —
revive:package-comments | missing / malformed package comment | P | Y | p | S | rd | —
revive:package-naming | package name not lowercase / `util` etc. | Fi | Y | p | S | - | —
revive:package-directory-mismatch | package name differs from directory | Fi | Y | p | S | - | ignore patterns
revive:range-val-address | `&v` of a range variable (go < 1.22) | E | T | p | S | - | —
revive:range-val-in-closure | range variable in a closure (go < 1.22) | E | T | n:GoLoopClosure | S | - | —
revive:range | `for i, _ := range` | S | Y | p | S | rd | —
revive:receiver-naming | `self`/`this`, inconsistent receiver names | P | Y | p | S | rd | `maxLength`
revive:redefines-builtin-id | local name shadows a builtin | S | Y | p | S | rd | —
revive:redundant-build-tag | `+build` next to `//go:build` | Fi | Y | n~:GoBuildConstraint (`+build is deprecated`) | S | - | —
revive:redundant-import-alias | alias equal to the package name | Fi | T | p | S | - | —
revive:redundant-test-main-exit | `os.Exit(m.Run())` in TestMain (go >= 1.15) | F | T | p | S | - | —
revive:string-format | string literal violates a configured regexp per call | C | T | p | M | - | rule list
revive:string-of-int | `string(int)` | E | T | p | S | - | —
revive:struct-tag | invalid / unknown struct tag options | T | T | n~:GoStructTag (syntax; option validation to port) | M | - | user-defined tags
revive:superfluous-else | `else` after `break`/`continue`/`goto` | S | Y | p | S | rd | `preserveScope`
revive:time-date | `time.Date` with out-of-range or octal components | C | T | p | S | - | —
revive:time-equal | `t1 == t2` on `time.Time` | E | T | p | S | - | —
revive:time-naming | `time.Duration` named `…Secs` | T | T | p | S | rd | —
revive:unchecked-type-assertion | `x.(T)` without `ok` | E | T | p | S | - | `acceptIgnoredAssertionResult`
revive:unconditional-recursion | recursion without a base case | F | T+F | p | M | - | —
revive:unexported-naming | unexported symbol named with a capital after `_` etc. | Fi | Y | p | S | - | —
revive:unexported-return | exported function returns an unexported type | F | T | p | S | rd | —
revive:unhandled-error | error result dropped | C | T | n:GoUncheckedError | S | - | function list to ignore
revive:unnecessary-if | `if c { x = true } else { x = false }` | S | Y | p | S | - | —
revive:unnecessary-format | `Printf` without verbs | C | T | p | S | - | —
revive:unnecessary-stmt | `switch` with one case, trailing `return` | S | Y | p | S | - | —
revive:unreachable-code | code after `return`/`panic` | F | F | n:GoUnreachableCode | S | rd | —
revive:unsecure-url-scheme | `http://` / `ws://` URL literal | E | Y | p | S | - | —
revive:unused-parameter | parameter never read | F | T | n:GoUnusedParameter | S | rd | `allowRegex`
revive:unused-receiver | receiver never used | F | T | p | S | - | `allowRegex`
revive:use-any | `interface{}` -> `any` | E | Y | p | S | - | —
revive:use-errors-new | `fmt.Errorf` without verbs -> `errors.New` | C | T | p | S | - | —
revive:use-fmt-print | `fmt.Printf` without verbs | C | T | p | S | - | —
revive:use-slices-concat | nested appends -> `slices.Concat` | E | T | p | S | - | —
revive:use-slices-sort | `sort.Ints` -> `slices.Sort` | C | T | p | S | - | —
revive:use-waitgroup-go | `wg.Add(1); go func(){ defer wg.Done() }` -> `wg.Go` | S | T | p | M | - | —
revive:useless-break | `break` at the end of a case | S | Y | p | S | - | —
revive:useless-fallthrough | empty case + `fallthrough` | S | Y | p | S | - | —
revive:var-declaration | `var x int = 0` | S | T | p | S | rd | —
revive:var-naming | MixedCaps, initialisms (`Id` -> `ID`), `_` in names | Fi | Y | p | M | rd | allow list, deny list, `skipPackageNameChecks`
revive:waitgroup-by-value | `sync.WaitGroup` parameter by value | F | T | n~:GoCopyLocks (copy of a WaitGroup) | S | - | —
""")

# ---------------------------------------------------------------- gocritic
section("gocritic checkers", "gocritic", r"""
gocritic:appendAssign | `x = append(y, …)` with x != y | S | T | p | S | gd | —
gocritic:appendCombine | consecutive appends to one slice | S | T | p | S | - | —
gocritic:argOrder | `strings.HasPrefix("prefix", s)` | C | T | p | S | gd | —
gocritic:assignOp | `x = x + 1` -> `x += 1` | S | Y | p | S | gd | —
gocritic:badCall | `strings.Replace(s, a, b, 0)`, `filepath.Join(x)` | C | T | p | S | gd | —
gocritic:badCond | `x < 0 && x > 10` | E | T | p | S | gd | —
gocritic:badLock | `mu.Lock(); defer mu.Lock()` | S | T | p | S | - | —
gocritic:badRegexp | suspicious regexp (`[a-z]\|[a-z]`, unescaped `.`) | C | T | p | M | - | —
gocritic:badSorting | `x = sort.StringSlice(x)` | S | T | p | S | - | —
gocritic:badSyncOnceFunc | `sync.OnceFunc(f)()` | C | T | p | S | - | —
gocritic:boolExprSimplify | `!(a == b)` -> `a != b` | E | T | p | S | - | —
gocritic:builtinShadow | local shadows a builtin | S | Y | p | S | - | —
gocritic:builtinShadowDecl | declaration shadows a builtin | Fi | Y | p | S | - | —
gocritic:captLocal | capitalized local / parameter name | F | Y | p | S | gd | `paramsOnly`
gocritic:caseOrder | type-switch case subsumed by an earlier one | S | T | p | S | gd | —
gocritic:codegenComment | malformed `// Code generated … DO NOT EDIT.` | Fi | Y | p | S | gd | —
gocritic:commentFormatting | `//comment` without space | Fi | Y | p | S | gd | —
gocritic:commentedOutCode | commented-out code | Fi | Y | k: noisy, needs parsing every comment | M | - | `minLength`
gocritic:commentedOutImport | commented-out import | Fi | Y | p | S | - | —
gocritic:defaultCaseOrder | `default` not first or last | S | Y | p | S | gd | —
gocritic:deferInLoop | `defer` in a loop | S | Y | n:GoDeferInLoop | S | - | —
gocritic:deferUnlambda | `defer func() { f() }()` -> `defer f()` | S | T | p | S | - | —
gocritic:deprecatedComment | malformed `Deprecated:` comment | Fi | Y | p | S | gd | —
gocritic:docStub | stub doc comment (`// Foo ...`) | Fi | Y | p | S | - | —
gocritic:dupArg | `copy(x, x)`, `strings.Contains(x, x)` | C | T | p | S | gd | —
gocritic:dupBranchBody | identical `if`/`else` bodies | S | Y | p | S | gd | —
gocritic:dupCase | duplicate case expression | S | T | p | S | gd | —
gocritic:dupImport | package imported twice | Fi | Y | p | S | - | —
gocritic:dupSubExpr | `a == a`, `x - x` | E | T | p | S | gd | —
gocritic:dynamicFmtString | `fmt.Errorf(msg)` with a non-constant format | C | T | p | S | - | —
gocritic:elseif | `else { if … }` -> `else if` | S | Y | p | S | gd | `skipBalanced`
gocritic:emptyDecl | `var ()` | Fi | Y | p | S | - | —
gocritic:emptyFallthrough | empty case with `fallthrough` | S | Y | p | S | - | —
gocritic:emptyStringTest | `len(s) == 0` -> `s == ""` | E | T | p | S | - | —
gocritic:equalFold | `ToLower(a) == ToLower(b)` | E | T | p | S | - | —
gocritic:evalOrder | `return x, f(&x)` relies on evaluation order | S | T | p | S | - | —
gocritic:exitAfterDefer | `log.Fatal` after `defer` | F | T | p | S | gd | —
gocritic:exposedSyncMutex | exported struct embeds `sync.Mutex` | T | T | p | S | - | —
gocritic:externalErrorReassign | `io.EOF = nil` | S | T | p | S | - | —
gocritic:filepathJoin | `filepath.Join("a/b", c)` with separators | C | T | p | S | - | —
gocritic:flagDeref | `*flag.Bool(…)` | E | T | p | S | gd | —
gocritic:flagName | flag name with spaces/odd characters | C | T | p | S | gd | —
gocritic:hexLiteral | mixed-case hex literal | E | Y | p | S | - | —
gocritic:httpNoBody | `nil` body -> `http.NoBody` | C | T | p | S | - | —
gocritic:hugeParam | big struct passed by value | F | T | p | S | - | `sizeThreshold` (80)
gocritic:ifElseChain | `if-else if` chain -> switch | S | Y | p | S | gd | `minThreshold`
gocritic:importShadow | local shadows an imported package | F | T | p | S | - | —
gocritic:indexAlloc | `strings.Index(string(b), …)` | C | T | p | S | - | —
gocritic:initClause | `if` init without effect | S | Y | p | S | - | —
gocritic:mapKey | map literal key with spaces | E | T | p | S | gd | —
gocritic:methodExprCall | `T.Method(x)` -> `x.Method()` | C | T | p | S | - | —
gocritic:nestingReduce | invert `if` to reduce nesting in a loop | S | Y | p | S | - | `bodyWidth`
gocritic:newDeref | `*new(T)` | E | T | p | S | gd | —
gocritic:nilValReturn | `if err == nil { return err }` | S | T | n~:GoErrNilReturned | S | - | —
gocritic:octalLiteral | `0644` -> `0o644` | E | Y | p | S | - | —
gocritic:offBy1 | `s[len(s)]` | E | T | p | S | gd | —
gocritic:paramTypeCombine | `a int, b int` -> `a, b int` | F | Y | p | S | - | —
gocritic:preferDecodeRune | `[]rune(s)[0]` | E | T | p | S | - | —
gocritic:preferFilepathJoin | `a + "/" + b` | E | T | p | S | - | —
gocritic:preferFprint | `w.Write([]byte(fmt.Sprintf(…)))` | C | T | p | S | - | —
gocritic:preferStringWriter | `w.Write([]byte(s))` -> `WriteString` | C | T | p | S | - | —
gocritic:preferWriteByte | `WriteRune('a')` -> `WriteByte` | C | T | p | S | - | —
gocritic:ptrToRefParam | pointer to map/chan/interface parameter | F | T | p | S | - | —
gocritic:rangeAppendAll | `for range x { y = append(y, x...) }` | S | T | p | S | - | —
gocritic:rangeExprCopy | ranging over a large array copies it | S | T | p | S | - | `sizeThreshold`, `skipTestFuncs`
gocritic:rangeValCopy | large range value copied per iteration | S | T | p | S | - | `sizeThreshold`, `skipTestFuncs`
gocritic:redundantSprint | `fmt.Sprint(x)` of a `Stringer`/string | C | T | p | S | - | —
gocritic:regexpMust | `regexp.Compile` of a constant -> `MustCompile` | C | T | p | S | gd | —
gocritic:regexpPattern | regexp missing escapes for `.` in domains | C | T | p | S | - | —
gocritic:regexpSimplify | simplifiable regexp | C | T | p | M | - | —
gocritic:returnAfterHttpError | `http.Error` not followed by `return` | S | T | p | S | - | —
gocritic:ruleguard | user rules (ruleguard DSL) | Fi | T | k: needs ruleguard engine | L | - | `rules`
gocritic:singleCaseSwitch | switch with one case -> `if` | S | Y | p | S | gd | —
gocritic:sliceClear | zeroing loop -> `clear` | S | T | p | S | - | —
gocritic:sloppyLen | `len(x) <= 0` | E | T | p | S | gd | —
gocritic:sloppyReassign | `if err = f(); err != nil` with outer err | S | T | p | S | - | —
gocritic:sloppyTypeAssert | assertion to an interface it already implements | E | T | p | S | gd | —
gocritic:sortSlice | `sort.Slice(x, func(i, j) bool { return y[i] < y[j] })` | C | T | p | S | - | —
gocritic:sprintfQuotedString | `"'%s'"` -> `%q` | C | T | p | S | - | —
gocritic:sqlQuery | `db.Query` result ignored where `Exec` fits | C | T | p | S | - | —
gocritic:stringConcatSimplify | `strings.Join([]string{a, b}, "")` | C | T | p | S | - | —
gocritic:stringXbytes | `copy(b, []byte(s))` | C | T | p | S | - | —
gocritic:stringsCompare | `strings.Compare(a, b) == 0` | E | T | p | S | - | —
gocritic:switchTrue | `switch true {` | S | Y | p | S | gd | —
gocritic:syncMapLoadAndDelete | `Load` + `Delete` -> `LoadAndDelete` | S | T | p | S | - | —
gocritic:timeExprSimplify | `t.Unix() * 1000` -> `UnixMilli` | E | T | p | S | - | —
gocritic:todoCommentWithoutDetail | bare `// TODO` | Fi | Y | p | S | - | —
gocritic:tooManyResultsChecker | too many results | F | Y | p | S | - | `maxResults`
gocritic:truncateCmp | `int32(x) < y` truncates before comparing | E | T | p | S | - | `skipArchDependent`
gocritic:typeAssertChain | repeated assertions -> type switch | S | T | p | S | - | —
gocritic:typeDefFirst | type declared after its methods | Fi | Y | p | S | - | —
gocritic:typeSwitchVar | type switch without a bound variable that re-asserts | S | T | p | S | gd | —
gocritic:typeUnparen | `[](T)` redundant parentheses | E | Y | p | S | - | —
gocritic:uncheckedInlineErr | `if err := f(); f2() != nil` | S | T | p | S | - | —
gocritic:underef | `(*p).f` -> `p.f` | E | T | p | S | gd | `skipRecvDeref`
gocritic:unlabelStmt | redundant label on `break`/`continue` | S | Y | p | S | - | —
gocritic:unlambda | `func(x int) int { return f(x) }` -> `f` | E | T | p | S | gd | —
gocritic:unnamedResult | several unnamed results of the same type | F | T | p | S | - | `checkExported`
gocritic:unnecessaryBlock | `{ … }` block without purpose | S | Y | p | S | - | —
gocritic:unnecessaryDefer | `defer f()` right before `return` | S | Y | p | S | - | —
gocritic:unslice | `s[:]` of a slice | E | T | p | S | gd | —
gocritic:valSwap | swap via temporary -> `a, b = b, a` | S | Y | p | S | gd | —
gocritic:weakCond | `len(x) > 0 \|\| x[0] == 1` | E | T | p | S | - | —
gocritic:whyNoLint | `//nolint` without explanation | Fi | Y | p | S | - | —
gocritic:wrapperFunc | `strings.SplitN(s, sep, -1)` -> `Split` | C | T | p | S | gd | —
gocritic:yodaStyleExpr | `nil != x` | E | Y | p | S | - | —
""")

# ---------------------------------------------------------------- gosec
section("gosec rules", "gosec", r"""
gosec:G101 | hard-coded credentials in names/values | E | Y | p | M | - | `pattern`, `ignore_entropy`, `entropy_threshold`
gosec:G102 | listening on all interfaces (`0.0.0.0`, `:port`) | C | T | p | S | - | —
gosec:G103 | use of `unsafe` | E | T | p | S | - | —
gosec:G104 | unchecked error | C | T | n:GoUncheckedError | S | - | `G104` audit functions
gosec:G106 | `ssh.InsecureIgnoreHostKey` | C | T | p | S | - | —
gosec:G107 | HTTP request URL from a variable | C | T | p | S | - | —
gosec:G108 | `net/http/pprof` exposed (blank import) | Fi | Y | p | S | - | —
gosec:G109 | `strconv.Atoi` result converted to int16/int32 | E | T+F | p | M | - | —
gosec:G110 | decompression bomb (`io.Copy` from a decompressor) | C | T | p | S | - | —
gosec:G111 | `http.Dir("/")` directory traversal | C | T | p | S | - | —
gosec:G112 | `http.Server` without `ReadHeaderTimeout` (Slowloris) | E | T | p | S | - | —
gosec:G114 | `http.ListenAndServe` without timeouts | C | T | p | S | - | —
gosec:G115 | integer overflow on conversion | E | T+F | a | L | - | —
gosec:G201 | SQL built with `fmt.Sprintf` | C | T | p | M | - | —
gosec:G202 | SQL built with string concatenation | C | T | p | M | - | —
gosec:G203 | unescaped data in `template.HTML` & co. | E | T | p | S | - | —
gosec:G204 | subprocess launched with variable arguments | C | T | p | M | - | —
gosec:G301 | `os.Mkdir` with permissions over 0750 | C | T | p | S | - | `G301` mode
gosec:G302 | `os.OpenFile`/`Chmod` with permissions over 0600 | C | T | p | S | - | `G302` mode
gosec:G303 | predictable temp file name | C | T | p | S | - | —
gosec:G304 | file path from a variable | C | T | p | M | - | —
gosec:G305 | zip/tar slip (archive entry path joined) | C | T+F | a | M | - | —
gosec:G306 | `os.WriteFile` with permissions over 0600 | C | T | p | S | - | `G306` mode
gosec:G307 | `defer f.Close()` on a writable file (error dropped) | S | T | p | S | - | —
gosec:G401 | weak hash `md5`/`sha1` used | C | T | p | S | - | —
gosec:G402 | TLS `InsecureSkipVerify` / low `MinVersion` | E | T | p | M | - | —
gosec:G403 | RSA key shorter than 2048 bits | C | T | p | S | - | —
gosec:G404 | `math/rand` for security | C | T | p | S | - | —
gosec:G405 | DES / RC4 cipher | C | T | p | S | - | —
gosec:G406 | MD4 / RIPEMD160 | C | T | p | S | - | —
gosec:G407 | hard-coded IV / nonce | C | T+F | a | M | - | —
gosec:G501 | import `crypto/md5` | Fi | Y | p | S | - | —
gosec:G502 | import `crypto/des` | Fi | Y | p | S | - | —
gosec:G503 | import `crypto/rc4` | Fi | Y | p | S | - | —
gosec:G504 | import `net/http/cgi` | Fi | Y | p | S | - | —
gosec:G505 | import `crypto/sha1` | Fi | Y | p | S | - | —
gosec:G506 | import `golang.org/x/crypto/md4` | Fi | Y | p | S | - | —
gosec:G507 | import `golang.org/x/crypto/ripemd160` | Fi | Y | p | S | - | —
gosec:G601 | implicit memory aliasing of a range variable (go < 1.22) | E | T | p | S | - | —
gosec:G602 | slice index out of range after a length check | E | T+F | a | M | - | —
""")

# ---------------------------------------------------------------- testifylint
section("testifylint checkers", "testifylint", r"""
testifylint:blank-import | blank import of `testify` packages | Fi | Y | p | S | - | —
testifylint:bool-compare | `assert.Equal(t, true, x)` -> `True` | C | T | p | S | - | `ignore-custom-types`
testifylint:compares | `assert.True(t, a == b)` -> `Equal` | C | T | p | S | - | —
testifylint:contains | `assert.True(t, strings.Contains(…))` -> `Contains` | C | T | p | S | - | —
testifylint:empty | `assert.Len(t, x, 0)` -> `Empty` | C | T | p | S | - | —
testifylint:encoded-compare | JSON/YAML strings compared with `Equal` -> `JSONEq` | C | T | p | S | - | —
testifylint:equal-values | `EqualValues` on equal types -> `Equal` | C | T | p | S | - | —
testifylint:error-is-as | `assert.Error(t, err, ErrX)` -> `ErrorIs` | C | T | p | S | - | —
testifylint:error-nil | `assert.Nil(t, err)` -> `NoError` | C | T | p | S | - | —
testifylint:expected-actual | expected and actual swapped | C | T | p | S | - | `pattern`
testifylint:float-compare | `Equal` on floats -> `InEpsilon` | C | T | p | S | - | —
testifylint:formatter | `assert.Equalf` misuse / format args | C | T | p | M | - | `check-format-string`, `require-f-funcs`
testifylint:go-require | `require` in a goroutine | C | T | p | S | - | `ignore-http-handlers`
testifylint:len | `assert.Equal(t, 3, len(x))` -> `Len` | C | T | p | S | - | —
testifylint:negative-positive | `assert.Less(t, x, 0)` -> `Negative` | C | T | p | S | - | —
testifylint:nil-compare | `assert.Equal(t, nil, x)` -> `Nil` | C | T | p | S | - | —
testifylint:regexp | `assert.Regexp(t, regexp.MustCompile(…))` | C | T | p | S | - | —
testifylint:require-error | error assertions should be `require` | C | T | p | S | - | `fn-pattern`
testifylint:suite-broken-parallel | `t.Parallel` in a suite | C | T | p | S | - | —
testifylint:suite-dont-use-pkg | `assert.X(s.T(), …)` -> `s.X(…)` | C | T | p | S | - | —
testifylint:suite-extra-assert-call | `s.Assert().X` vs `s.X` | C | T | p | S | - | `mode`
testifylint:suite-method-signature | suite method with a wrong signature | F | T | p | S | - | —
testifylint:suite-subtest-run | `t.Run` inside a suite -> `s.Run` | C | T | p | S | - | —
testifylint:suite-thelper | suite helper without `s.T().Helper()` | F | T | p | S | - | —
testifylint:useless-assert | `assert.Equal(t, x, x)` | C | T | p | S | - | —
""")

# ---------------------------------------------------------------- popular single-purpose linters
SECTIONS["Popular linters (one rule each, or a few sub-checks)"] = [
    ("errorlint:errorf", "errorlint", "`fmt.Errorf` with an error argument not wrapped with `%w`", "C", "T", "p", "S", "-", "`errorf`, `errorf-multi`"),
    ("errorlint:asserts", "errorlint", "type assertion / type switch on an error -> `errors.As`", "E", "T", "p", "S", "-", "`asserts`, allowed list"),
    ("errorlint:comparison", "errorlint", "`err == ErrX` -> `errors.Is`", "E", "T", "n:GoErrorsPackage", "S", "-", "`comparison`, allowed list"),
    ("bodyclose", "bodyclose", "`resp.Body` not closed", "F", "T+F", "n:GoBodyNotClosed", "M", "-", "—"),
    ("nilerr", "nilerr", "`return nil` while err != nil (and the reverse)", "F", "T+F", "n:GoNilErrorReturn", "M", "-", "—"),
    ("nilnil", "nilnil", "`return nil, nil` for a nilable result with error", "S", "T", "n:GoNilValueNilError", "S", "-", "`checked-types`, `detect-opposite`"),
    ("rowserrcheck", "rowserrcheck", "`rows.Err()` not checked after iterating `*sql.Rows`", "F", "T+F", "p", "M", "-", "`packages` (sqlx, …)"),
    ("sqlclosecheck", "sqlclosecheck", "`*sql.Rows` / `*sql.Stmt` not closed", "F", "T+F", "n~:GoRowsNotClosed (Rows; Stmt to add)", "M", "-", "—"),
    ("noctx", "noctx", "HTTP request / `sql` call without a context (`http.Get`, `db.Query`)", "C", "T", "p", "S", "-", "—"),
    ("misspell", "misspell", "common English misspellings in comments/strings", "Fi", "Y", "k: covered by the IDE spellchecker (`GoSpellcheckingStrategy` + Typo)", "S", "-", "`locale`, `ignore-rules`"),
    ("unconvert", "unconvert", "redundant type conversion `T(x)` where x is T", "E", "T", "p", "S", "-", "`fast-math`, `safe`"),
    ("unparam", "unparam", "parameter always receives the same value / result always the same", "F", "T+X", "a", "L", "-", "`check-exported`"),
    ("prealloc", "prealloc", "slice could be preallocated before an append loop", "F", "T", "p", "S", "-", "`simple`, `range-loops`, `for-loops`"),
    ("dupword", "dupword", "repeated word in comments/strings (`the the`)", "Fi", "Y", "p", "S", "-", "`keywords`, `ignore`"),
    ("goconst", "goconst", "repeated string literal could be a constant", "P", "Y", "p", "M", "-", "`min-len`, `min-occurrences`, `ignore-tests`, `match-constant`"),
    ("gocyclo", "gocyclo", "cyclomatic complexity over the limit", "F", "Y", "p", "S", "-", "`min-complexity` (30)"),
    ("cyclop", "cyclop", "cyclomatic complexity of functions / package average", "F", "Y", "p", "S", "-", "`max-complexity`, `package-average`"),
    ("funlen", "funlen", "function too long (lines / statements)", "F", "Y", "p", "S", "-", "`lines`, `statements`, `ignore-comments`"),
    ("nestif", "nestif", "deeply nested `if` complexity", "F", "Y", "p", "S", "-", "`min-complexity` (5)"),
    ("wastedassign", "wastedassign", "assignment never used / reassigned before use", "F", "F", "n~:GoIneffectualAssignment (same analysis; wastedassign also flags assignments before `return`)", "S", "-", "—"),
    ("makezero", "makezero", "`append` to a slice created with non-zero length", "F", "T+F", "p", "S", "-", "`always`"),
    ("exhaustive", "exhaustive", "`switch` over an enum misses members", "S", "T+X", "n:GoExhaustiveSwitch", "M", "-", "`default-signifies-exhaustive`, `check: [switch, map]`, `ignore-enum-members`"),
    ("forcetypeassert", "forcetypeassert", "`x.(T)` without `ok`", "E", "T", "p", "S", "-", "—"),
    ("contextcheck", "contextcheck", "function uses a non-inherited context", "F", "T+F", "n:GoContextNotPropagated", "M", "-", "—"),
    ("containedctx", "containedctx", "struct field of type `context.Context`", "T", "T", "p", "S", "-", "—"),
    ("errname", "errname", "error types named `XxxError`, sentinels `ErrXxx`", "T", "T", "p", "S", "-", "—"),
    ("nakedret", "nakedret", "naked return in a function longer than N lines", "F", "Y", "p", "S", "-", "`max-func-lines` (30)"),
    ("nolintlint", "nolintlint", "malformed / unused / unexplained `//nolint`", "Fi", "Y", "p", "M", "-", "`require-explanation`, `require-specific`, `allow-unused`, `allow-no-explanation`"),
    ("predeclared", "predeclared", "declaration shadows a predeclared identifier", "Fi", "Y", "p", "S", "-", "`ignore`, `qualified-name`"),
    ("usestdlibvars", "usestdlibvars", "literal where a stdlib constant exists (`\"GET\"` -> `http.MethodGet`, 200 -> `http.StatusOK`)", "E", "T", "p", "S", "-", "`http-method`, `http-status-code`, `time-month`, …"),
    ("perfsprint", "perfsprint", "`fmt.Sprintf` replaceable by `strconv` / concatenation / `errors.New`", "C", "T", "p", "S", "-", "`int-conversion`, `err-error`, `errorf`, `sprintf1`, `strconcat`"),
    ("copyloopvar", "copyloopvar", "`v := v` copy of a loop variable is redundant (go >= 1.22)", "S", "T", "p", "S", "-", "`check-alias`"),
    ("intrange", "intrange", "`for i := 0; i < n; i++` -> `for i := range n`", "S", "T", "p", "S", "-", "—"),
    ("thelper", "thelper", "test helper without `t.Helper()` first / `t` not first parameter", "F", "T", "p", "S", "-", "`test.first`, `test.name`, `test.begin`, same for `benchmark`, `tb`, `fuzz`"),
    ("tparallel", "tparallel", "`t.Parallel` in subtests but not the top-level test (or vice versa)", "F", "T", "p", "S", "-", "—"),
    ("paralleltest", "paralleltest", "test does not call `t.Parallel`", "F", "T", "p", "S", "-", "`ignore-missing`, `ignore-missing-subtests`"),
]

SECTIONS["Formatters"] = [
    ("gofmt", "gofmt", "file is not gofmt-ed", "Fi", "Y", "f", "-", "-", "`simplify`, `rewrite-rules`"),
    ("gofumpt", "gofumpt", "file is not gofumpt-ed", "Fi", "Y", "f", "-", "-", "`extra-rules`, `module-path`"),
    ("goimports", "goimports", "imports not goimports-ed", "Fi", "Y", "f", "-", "-", "`local-prefixes`"),
    ("gci", "gci", "import order by sections", "Fi", "Y", "f", "-", "-", "`sections`, `custom-order`"),
    ("golines", "golines", "lines longer than the limit are wrapped", "Fi", "Y", "f", "-", "-", "`max-len`"),
]

# ---------------------------------------------------------------- GoLand inspections (docs/LINTING-CATALOG.md, "GoLand: 123 инспекции")
# Status extra: s:<rule id> = same-as (the check is implemented once under that id; the GoLand name becomes its alias).
section("GoLand inspections", "goland", r"""
goland:GoAssignmentToReceiver | assignment to the receiver variable changes only the local copy | S | Y | p | S | - | —
goland:GoBoolExpressions | redundant or contradictory part of a boolean expression (`n > 10 && n > 5`) | E | T | s:govet:bools | - | - | range subsumption is also `gocritic:badCond` (B13): check both cover it
goland:GoBuildTag | malformed or misplaced build constraint | Fi | Y | n:GoBuildConstraint | - | - | —
goland:GoCommentLeadingSpace | no space after `//` | Fi | Y | s:gocritic:commentFormatting | - | - | alias also `revive:comment-spacings`
goland:GoCommentStart | doc comment of an exported declaration does not start with its name | T | Y | n:GoDocComment | - | - | —
goland:GoContextTodo | `context.TODO()` left in production code | C | T | n~:GoContextPlacement (only `TODO`/`Background` passed where the function has a ctx parameter; every `context.TODO()` call to add) | S | - | —
goland:GoConvertStringLiterals | literal can be written with other quotes (interpreted <-> raw) | E | Y | p | S | - | style, intention-like; weak warning
goland:GoCyclicImports | import cycle | P | X | n:GoImportCycle | - | - | —
goland:GoDebugDirective | `//go:debug` in a wrong package or place | Fi | Y | s:govet:directive | - | - | —
goland:GoDebugMinGoSdkVersion | `//go:debug` with a `go` version below the supported one | Fi | Y | s:govet:directive | - | - | check the min-version part when `govet:directive` is ported
goland:GoDeferGo | `go`/`defer` of `panic`/`recover` called directly (`defer recover()`) | S | T | s:revive:defer | - | - | `recover` sub-checks of `revive:defer` (B1)
goland:GoDeferInLoop | `defer` in a loop runs at function exit | S | Y | n:GoDeferInLoop | - | - | —
goland:GoDeprecation | use of a symbol documented `Deprecated:` | E | T+X | s:SA1019 | - | - | —
goland:GoDetectSetFinalizerUsages | `runtime.SetFinalizer` where `runtime.AddCleanup` (go >= 1.24) is available | C | T | p | S | - | version-gated (go directive)
goland:GoDfaConstantCondition | condition proven constant on the paths leading to it | E | F | n~:GoImpossibleNilCheck (nil conditions only; other proven-constant conditions to add on the same lattice) | M | - | —
goland:GoDfaErrorMayBeNotNil | result used before the paired `error` is checked | F | T+F | n:GoResultUsedBeforeErrorCheck | - | - | —
goland:GoDfaInspectionRunner | service entry point of the DFA inspections (the JAR description is an empty template) | F | F | k: not a check; the flow inspections run on their own (`semantic.flow`) | - | - | —
goland:GoDfaNilDereference | pointer may be nil on some path before the dereference | F | T+F | n~:GoNilDereference (variable nil on every path; may-be-nil on some path to add) | M | - | —
goland:GoDirectComparisonOfErrors | `err == ErrX` ignores wrapping | E | T | n:GoErrorsPackage | - | - | alias of `errorlint:comparison`
goland:GoDisabledGopathIndexing | IDE setting excludes needed GOPATH libraries from indexing | M | Y | k: IDE mechanics; the project model reads module cache / GOPATH roots itself (`GoCatalogueService`, Dependencies node) | - | - | —
goland:GoDivisionByZero | divisor proven zero | E | T+F | n~:GoChecker (a constant zero divisor is a go/types error, verify the message; a divisor proven zero by flow to add) | S | - | —
goland:GoEmptyDeclaration | empty `var ()` / `const ()` / `type ()` group | S | Y | s:gocritic:emptyDecl | - | - | —
goland:GoErrorStringFormat | error text capitalized or ending with punctuation | C | T | s:ST1005 | - | - | alias also `revive:error-strings`
goland:GoErrorsAs | `errors.As` target is not a non-nil pointer | C | T | n:GoErrorsPackage | - | - | —
goland:GoExportedElementShouldHaveComment | exported declaration without a doc comment | T | Y | n:GoDocComment | - | - | off by default, like in the plugin
goland:GoExportedFuncWithUnexportedType | exported function returns an unexported type | F | T | s:revive:unexported-return | - | - | —
goland:GoExportedOwnDeclaration | several exported names declared in one line (`const A, B = 1, 2`) | S | Y | p | S | - | do not split iota groups
goland:GoFuzzingSupport | fuzz test with Go before 1.18 | Fi | Y | k: go < 1.18 is not supported by the plugin's toolchain checks; fuzz names and signatures are `govet:tests` | - | - | —
goland:GoImportUsedAsName | local name shadows an imported package | E | T | s:gocritic:importShadow | - | - | alias also `revive:import-shadowing`
goland:GoInfiniteFor | empty `for {}` spins the CPU | S | Y | s:SA5002 | - | - | —
goland:GoInterfaceToAny | `interface{}` can be `any` | E | T | s:revive:use-any | - | - | also `goland:GoFixAny`
goland:GoIrregularIota | iota group mixes declaration forms that change the expected values | T | Y | p | S | - | no automatic fix
goland:GoLeadingWhitespaceInDirectiveComment | space between `//` and a directive: it is not recognized | Fi | Y | s:SA9009 | - | - | —
goland:GoLoopClosure | closure captures the loop variable (go < 1.22) | S | T | n:GoLoopClosure | - | - | —
goland:GoMaybeNil | interprocedural: a call result may be nil and is dereferenced | F | A | a | L | - | needs per-function nil summaries (SCC for recursion)
goland:GoMissingTrailingComma | missing trailing comma before a newline | E | Y | k: syntax error of the go-psi parser (verify the message text for a missing comma) | - | - | —
goland:GoMixedReceiverTypes | value and pointer receivers mixed on one type | P | T | p | S | - | —
goland:GoNameStartsWithPackageName | exported name repeats the package name (`http.HTTPServer`) | Fi | Y | p | S | - | the stutter part of `revive:exported` (not covered by `GoDocComment`)
goland:GoNilness | nil misuse: interfaces, maps, pointers, channels, calls | F | T+F | n~:GoNilDereference, GoImpossibleNilCheck (verify nil channel send/close/receive and nil-map reads) | M | - | —
goland:GoPreferNilSlice | empty slice literal can be a nil slice | E | T | p | S | - | `[]T{}` -> `nil` is unsafe for JSON and reflect: weak warning
goland:GoPrintFunctions | printf format does not match the arguments | C | T | n:GoPrintf | - | - | —
goland:GoReceiverNames | receiver named `self`/`this`/`me` or inconsistent | T | T | s:ST1006 | - | - | alias also `ST1016`, `revive:receiver-naming`
goland:GoRedundantBlankArgInRange | `for i, _ := range` | S | Y | s:revive:range | - | - | alias also `S1005`
goland:GoRedundantComma | redundant comma where none is needed or allowed | E | Y | p | S | - | exact scope: verify against GoLand's resource and the parser's recovery
goland:GoRedundantConversion | conversion changes neither type nor value | E | T | s:unconvert | - | - | —
goland:GoRedundantElseInIf | `else` after a terminating branch | S | Y | s:revive:indent-error-flow | - | - | alias also `revive:superfluous-else`
goland:GoRedundantImportAlias | import alias equals the package name | Fi | T | s:revive:redundant-import-alias | - | - | —
goland:GoRedundantParens | parentheses that do not change grouping | E | Y | p | S | - | `gocritic:typeUnparen` is the type-only part
goland:GoRedundantSecondIndexInSlices | `s[a:len(s)]` | E | T | s:S1010 | - | - | —
goland:GoRedundantSemicolon | explicit semicolon that is redundant | S | Y | p | S | - | —
goland:GoRedundantTrueInForCondition | `for true {}` | S | Y | s:S1006 | - | - | —
goland:GoRedundantTypeDeclInCompositeLit | element type repeated in a nested composite literal (`[]Point{Point{}}`) | E | T | p | S | - | `gofmt -s` simplification; pointer elements and go version
goland:GoReservedWordUsedAsName | name shadows a predeclared identifier | Fi | Y | s:predeclared | - | - | alias also `revive:redefines-builtin-id`, `gocritic:builtinShadow`
goland:GoResourceLeak | acquired resource is not closed on some path | F | T+F | n~:GoBodyNotClosed, GoRowsNotClosed (HTTP bodies and sql rows only; `os.Open`, `net.Dial`, `Closer` results in general to add) | M | - | —
goland:GoSelfAssignment | variable assigned to itself | S | Y | n:GoSelfAssignment | - | - | —
goland:GoShadowedVar | inner declaration shadows an outer variable and may lose the result | F | T+F | s:govet:shadow | - | - | the plugin has `GoShadowedError` (errors only); generalized in B1
goland:GoShift | shift count not below the operand's width | E | T | s:govet:shift | - | - | —
goland:GoSnakeCaseUsage | snake_case name of a Go declaration | Fi | Y | s:revive:var-naming | - | - | alias also `ST1003`
goland:GoStandardMethods | well-known method with a non-standard signature | T | T | s:govet:stdmethods | - | - | —
goland:GoStringsReplaceCount | `strings.Replace` with a zero count | C | T | s:SA1018 | - | - | —
goland:GoStructInitializationWithoutFieldNames | unkeyed composite literal of a foreign struct | E | T | s:govet:composites | - | - | —
goland:GoStructLayout | field order creates padding or a pointer prefix | T | T | k: noisy; see `govet:fieldalignment` (skipped), the host has the Reorder Fields intention | - | - | —
goland:GoSwitchMissingCasesForIotaConsts | switch over an enum misses constants | S | T+X | n:GoExhaustiveSwitch | - | - | alias of `exhaustive`
goland:GoTestName | test name or signature breaks the testing convention | F | T | s:govet:tests | - | - | —
goland:GoTypeAssertionOnErrors | type assertion on an error ignores wrapping | E | T | s:errorlint:asserts | - | - | —
goland:GoTypeParameterInLowerCase | type parameter name does not match the chosen style | T | Y | p | S | - | style option; off by default
goland:GoUnhandledErrorResult | returned error dropped | C | T | n:GoUncheckedError | - | - | alias of `errcheck`
goland:GoUnitSpecificDurationSuffix | `time.Duration` named with a unit (`timeoutSecs`) | Fi | T | s:ST1011 | - | - | alias also `revive:time-naming`
goland:GoUnnecessarilyExportedIdentifiers | exported name used only inside its package | P | T+X | p | M | - | project-wide, opt-in; no mass fix
goland:GoUnreachableCode | no reachable path to the statement | S | F | n:GoUnreachableCode | - | - | —
goland:GoUnsortedImport | imports not in the formatter's order | Fi | Y | k: formatter territory: gofmt/goimports on save and Optimize Imports (`GoUnusedImport` fix) | - | - | —
goland:GoUnusedCallResult | result of a pure / result-oriented function dropped | C | T | n:GoUnusedResult | - | - | —
goland:GoUnusedConst | constant without usages | P | T+X | s:unused | - | - | `unused` is partial: unexported package-level decls arrive with B1
goland:GoUnusedExportedFunction | exported function never called in the search scope | P | T+X | n:GoUnusedExported | - | - | the plugin checks `internal/` and application packages only
goland:GoUnusedExportedType | exported type of a main/test package unused | P | T+X | n:GoUnusedExported | - | - | —
goland:GoUnusedFunction | unexported function unreachable from used code | P | T+X | s:unused | - | - | see `goland:GoUnusedConst`
goland:GoUnusedGlobalVariable | package variable unused | P | T+X | s:unused | - | - | see `goland:GoUnusedConst`
goland:GoUnusedParameter | parameter never read | F | T | n:GoUnusedParameter | - | - | alias of `revive:unused-parameter`
goland:GoUnusedType | unexported type without usages | P | T+X | s:unused | - | - | see `goland:GoUnusedConst`
goland:GoUnusedTypeParameter | type parameter not used where it should be | F | T | p | S | - | —
goland:GoVarAndConstTypeMayBeOmitted | explicit type fully inferred | S | T | s:ST1023 | - | - | alias also `revive:var-declaration`, `QF1011`
goland:GoVetAtomic | `x = atomic.AddInt64(&x, 1)` | S | T | s:govet:atomic | - | - | —
goland:GoVetCopyLock | lock copied by value | E | T | n:GoCopyLocks | - | - | —
goland:GoVetFailNowInNotTestGoroutine | `FailNow`/`Fatal` from a child goroutine | S | T | n:GoTestingGoroutine | - | - | —
goland:GoVetImpossibleInterfaceToInterfaceAssertion | interface assertion that can never succeed | E | T | s:govet:ifaceassert | - | - | —
goland:GoVetIntToStringConversion | `string(integer)` | E | T | s:govet:stringintconv | - | - | —
goland:GoVetLostCancel | cancel function of `WithCancel`/`WithTimeout` not called | F | T+F | n:GoLostCancel | - | - | —
goland:GoVetStructTag | malformed struct tag | T | Y | n:GoStructTag | - | - | —
goland:GoVetUnmarshal | `Unmarshal` target is not a pointer | C | T | s:govet:unmarshal | - | - | —
goland:GoVetUnsafePointer | `uintptr` kept as a number between pointer conversions | E | T | s:govet:unsafeptr | - | - | —
goland:VgoDependencyDeprecated | module metadata marks a dependency deprecated | M | X | p | M | - | go.mod; read `// Deprecated:` of the dependency's go.mod in the module cache; implement in the host `mod` package (language GoModule)
goland:VgoDependencyUpdateAvailable | newer version of a dependency exists | M | X | p | M | - | go.mod; needs `go list -m -u` (network, background only, off by default); host `mod` package
goland:VgoDependencyVersionRetracted | required version retracted by the author | M | X | p | M | - | go.mod; `retract` directives from the module cache; host `mod` package
goland:VgoMigrateFromReplacesToWorkspace | local `replace` directives used for multi-module work -> `go.work` | M | Y | p | S | - | go.mod; host `mod` package
goland:VgoRequireDirectivesMerge | homogeneous `require` directives can be merged | M | Y | p | S | - | go.mod; host `mod` package
goland:VgoUnresolvedIgnorePath | relative `ignore` path in go.mod does not resolve | M | Y | p | S | - | go.mod; host `mod` package (next to `GoModPaths`)
goland:VgoUnusedDependency | direct dependency not needed by the package graph | M | X | n~:GoModUnused (every file counts: build tags are ignored; a build-context-aware graph to add) | M | - | go.mod
goland:GoFixAny | modernize: `interface{}` -> `any` | E | T | s:revive:use-any | - | - | —
goland:GoFixAtomicTypes | modernize: primitive `atomic.AddInt64(&x, …)` -> typed `atomic.Int64` field | C | T | p | M | - | go >= 1.19; the fix touches all uses of the field
goland:GoFixBuildTag | modernize: invalid build constraints | Fi | Y | n:GoBuildConstraint | - | - | —
goland:GoFixEmbedLit | modernize: nested embedded literal -> promoted-field literal | E | T | p | S | - | go 1.27 (per GoLand's description): version-gated, off for older `go` directives
goland:GoFixErrorsAsType | modernize: `errors.As(err, &target)` -> `errors.AsType[T](err)` | C | T | p | S | - | go >= 1.26
goland:GoFixForVar | modernize: `v := v` copy of the loop variable is redundant | S | T | s:copyloopvar | - | - | go >= 1.22
goland:GoFixHostPort | modernize: `host + ":" + port` is not IPv6-safe | E | T | s:govet:hostport | - | - | check that the concatenation form (not only Sprintf) is covered
goland:GoFixInline | `//go:fix inline`: replace calls by the function body | C | T+X | a | L | - | uses the plugin's Inline refactoring; evaluation order and scope hygiene
goland:GoFixMapsLoop | modernize: manual keys/values copy loop -> `maps` API | S | T | p | S | - | go >= 1.21 / 1.23
goland:GoFixMinMax | modernize: conditional assignment -> `min` / `max` | S | T | p | S | - | go >= 1.21
goland:GoFixNewExpr | modernize: pointer-to-value helper -> `new(value)` | F | T | p | S | - | go 1.26
goland:GoFixOmitZero | modernize: `omitempty` on a struct field -> `omitzero` | T | T | p | S | - | go >= 1.24; changes serialization, no blind fix
goland:GoFixPlusBuild | modernize: `+build` repeats the `//go:build` line | Fi | Y | s:revive:redundant-build-tag | - | - | —
goland:GoFixRangeInt | modernize: counting loop -> `for i := range n` | S | T | s:intrange | - | - | go >= 1.22
goland:GoFixReflectTypeFor | modernize: `reflect.TypeOf(x)` of a static type -> `reflect.TypeFor[T]()` | C | T | p | S | - | go >= 1.22
goland:GoFixSlicesBackward | modernize: reverse index loop -> `slices.Backward` | S | T | p | S | - | go >= 1.23
goland:GoFixSlicesContains | modernize: search loop -> `slices.Contains` / `slices.Index` | S | T | p | S | - | go >= 1.21
goland:GoFixSlicesSort | modernize: `sort.Slice` with a simple comparison -> `slices.Sort` | C | T | s:revive:use-slices-sort | - | - | check that `sort.Slice` with a trivial less is covered, not only `sort.Ints`
goland:GoFixStdIterators | modernize: index-based API walks -> standard iterators | S | T | a | M | - | go >= 1.24; a table of known APIs
goland:GoFixStringsBuilder | modernize: repeated string concatenation -> `strings.Builder` | S | T | p | M | - | only loops with a clear accumulator
goland:GoFixStringsCut | modernize: `Index` + slicing -> `strings.Cut` | S | T | p | S | - | go >= 1.18
goland:GoFixStringsCutPrefix | modernize: `HasPrefix` + `TrimPrefix` -> `CutPrefix` | S | T | p | S | - | go >= 1.20; differs from `S1017`
goland:GoFixStringsSeq | modernize: `Split` / `Fields` only ranged over -> `SplitSeq` / `FieldsSeq` | S | T | p | S | - | go >= 1.24
goland:GoFixTestingContext | modernize: manual test context -> `t.Context()` | S | T | p | S | - | go >= 1.24
goland:GoFixUnsafeFuncs | modernize: manual pointer arithmetic -> `unsafe.Add` / `unsafe.Slice` | E | T | p | S | - | go >= 1.17
goland:GoFixWaitGroup | modernize: `Add` / `go` / `defer Done` -> `WaitGroup.Go` | S | T | s:revive:use-waitgroup-go | - | - | go >= 1.25
""")

# ---------------------------------------------------------------- render
def status_text(s):
    if s.startswith("n~:"):
        return "native (partial): " + s[3:]
    if s.startswith("n:"):
        return "native: " + ", ".join("`%s`" % x.strip() for x in s[2:].split(","))
    if s.startswith("s:"):
        return "same-as: `%s`" % s[2:].strip()
    if s == "p":
        return "port"
    if s == "a":
        return "port-approx"
    if s.startswith("k:"):
        return "skip: " + s[2:].strip()
    if s == "f":
        return "formatter — not a check"
    raise ValueError(s)

def status_key(s):
    return {"n~": "native (partial)", "n:": "native", "s:": "same-as", "p": "port", "a": "port-approx", "k:": "skip", "f": "formatter"}[s[:2] if s[:2] in ("n~", "n:", "k:", "s:") else s]

def default_text(d, linter):
    if linter == "revive":
        return "revive default" if d == "rd" else "—"
    if linter == "gocritic":
        return "gocritic default" if d == "gd" else "—"
    return {"12": "v1+v2", "2": "v2", "-": "—", "": "—"}[d]

def needs_text(n):
    return " + ".join(NEEDS[x] for x in n.split("+"))

def main(out, plan=None):
    rows_all = []
    o = []
    w = o.append
    w("# Lint rules: golangci-lint, staticcheck, revive, gocritic, gosec -> native rules")
    w("")
    w("The mapping table that drives porting the checks of golangci-lint and the analyzers it runs into native rules of the plugin")
    w("(the rule engine of `go-psi-ide`, package `io.github.golangsupport.ide.rules`). Decision of 2026-10-03: the native checks are the default,")
    w("golangci-lint is optional and off by default. Source of the rule list and semantics: `docs/LINTING-CATALOG.md` and its pinned sources")
    w("(golangci efac294f1005, staticcheck v0.8.1, revive v1.17.0, gocritic v0.15.0, gosec v2.29.0, x/tools v0.50.0). govet analyzers, gocritic checkers,")
    w("gosec G-rules and testifylint checkers have no per-rule cards in the catalog yet; their rows are from the analyzers' documentation and must be")
    w("reconciled with the pinned sources before a batch starts (a rule the pinned version lacks is dropped from the batch).")
    w("")
    w("The section **GoLand inspections** lists the 123 inspections of GoLand (`goland:<Name>`, from the catalog) against the rest of this table: it shows which of them we")
    w("already have, which are the same check as a rule above, and which are GoLand-only (batches B28-B30).")
    w("")
    w("## Columns")
    w("")
    w("- **Rule id** — the engine id: `errcheck`, `SA4006`, `govet:printf`, `revive:var-naming`, `gocritic:ifElseChain`, `gosec:G104`, `testifylint:len`.")
    w("  Staticcheck ids are bare (golangci prints them as the message prefix); the engine's `linter` of S* is `gosimple`, of ST* `stylecheck`,")
    w("  of SA*/QF* `staticcheck` (v2 reports all of them as `staticcheck`: `GolangciLinters.reportedAs`).")
    w("- **Scope** — the PSI unit the rule visits: CALL, EXPRESSION, STATEMENT, FUNCTION (a body, with its control flow), TYPE_SPEC, FILE, PACKAGE")
    w("  (all files of a package, e.g. receiver-name consistency), MODULE.")
    w("- **Needs** — SYNTAX (PSI only), TYPES (`GoSemanticService`), FLOW (`semantic.flow`: CFG, liveness, reaching definitions, nilness),")
    w("  SSA-heavy (the original relies on SSA / pointer facts; a port is an approximation on FLOW), PROJECT_INDEX (stub indices across packages).")
    w("- **Status** — `native: <ShortName>` already reported by an inspection of the plugin; `native (partial)` covers part of the rule, the rest")
    w("  goes to the batch named in the plan; `same-as: <id>` (GoLand rows only) the check is implemented once under that id and the GoLand name is its alias; `port` feasible on PSI + types + flow; `port-approx` SSA-heavy, an approximation; `skip: <reason>`.")
    w("- **Size** — S (< 1 day, one visitor and a fixture), M (flow or several shapes, fixes), L (cross-package or SSA-like reasoning).")
    w("- **Default** — in golangci's default set: `v1+v2` (v1 default linters, v2 `linters.default: standard`), `v2` (only via v2 staticcheck's")
    w("  default `checks`, which include ST*/QF* except ST1000/1003/1016/1020/1021/1022), `revive default` / `gocritic default` (on when that linter")
    w("  is enabled with no rule list), `—` otherwise.")
    w("- **Options** — settings worth reading from `.golangci.yml` (`io.github.golangsupport.lint.config`) into rule options.")
    w("- **License** — of the original analyzer. † — not pinned in the catalog; from the project's repository, verify before copying any code.")
    w("  Ports are re-implementations from behavior (catalog cards, docs, test data as a spec); copied fragments go to `NOTICE.md` with")
    w("  attribution. `nolintlint` is part of golangci-lint (GPL-3.0): behavior only, never code.")
    w("")
    native_names = set()
    for title, rows in SECTIONS.items():
        w("## " + title)
        w("")
        w("| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |")
        w("|---|---|---|---|---|---|---|---|---|---|")
        for rid, linter, what, scope, needs, status, size, dflt, opts in rows:
            if linter is None:
                linter = rid
            rows_all.append((title, rid, linter, scope, needs, status, size, dflt))
            if linter == "staticcheck" and rid.startswith("S") and not rid.startswith("SA") and not rid.startswith("ST"):
                shown = "staticcheck (gosimple)"
            elif linter == "staticcheck" and rid.startswith("ST"):
                shown = "staticcheck (stylecheck)"
            else:
                shown = linter
            lic = LICENSE.get(linter, "?")
            w("| `%s` | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                rid, shown, what, SCOPE[scope], needs_text(needs), status_text(status), size if size != "-" else "—",
                default_text(dflt, linter), opts or "—", lic))
        w("")
    # summary
    counts = collections.Counter(status_key(r[5]) for r in rows_all)
    w("## Summary")
    w("")
    order = ["native", "native (partial)", "same-as", "port", "port-approx", "skip", "formatter"]
    w("| Section | " + " | ".join(order) + " | total |")
    w("|---|" + "---|" * (len(order) + 1))
    for title in SECTIONS:
        c = collections.Counter(status_key(r[5]) for r in rows_all if r[0] == title)
        w("| %s | %s | %d |" % (title, " | ".join(str(c.get(k, 0)) for k in order), sum(c.values())))
    w("| **All** | %s | **%d** |" % (" | ".join("**%d**" % counts.get(k, 0) for k in order), len(rows_all)))
    w("")
    dflt_rows = [r for r in rows_all if r[7] in ("12", "2")]
    dc = collections.Counter(status_key(r[5]) for r in dflt_rows)
    w("golangci default set (v1+v2 and v2 rows, %d rules): %s." % (len(dflt_rows), ", ".join("%s %d" % (k, dc.get(k, 0)) for k in order if dc.get(k))))
    w("")
    gl = [r for r in rows_all if r[0] == "GoLand inspections"]
    if gl:
        by_id = {r[1]: r for r in rows_all}
        gc = collections.Counter(status_key(r[5]) for r in gl)
        w("GoLand inspections (%d): %s." % (len(gl), ", ".join("%s %d" % (k, gc.get(k, 0)) for k in order if gc.get(k))))
        # coverage now: native, plus same-as whose target is native; after B1-B7: also partial rows and same-as targets planned in B1-B7
        planned_early = set()
        if plan:
            m7 = re.search(r"### B1\..*?(?=### B8\.)", plan, re.S)
            planned_early = set(re.findall(r"`([A-Za-z0-9:_\-]+)`", m7.group(0))) if m7 else set()
        def target_state(r):
            st = r[5]
            if st.startswith("s:"):
                t = by_id[st[2:].strip()]
                k = status_key(t[5])
                if k == "native":
                    return "now"
                if t[1] in planned_early:
                    return "b1-7"
                return "later"
            return None
        now = sum(1 for r in gl if status_key(r[5]) == "native" or target_state(r) == "now")
        partial_now = sum(1 for r in gl if status_key(r[5]) == "native (partial)")
        after = sum(1 for r in gl if status_key(r[5]) == "native" or target_state(r) in ("now", "b1-7")
                    or (status_key(r[5]) in ("native (partial)", "port", "port-approx") and r[1] in planned_early))
        skip = gc.get("skip", 0)
        w("GoLand coverage: %d of %d inspections are covered today by a native inspection (native, or same-as a rule that is native); %d more are partial."
          % (now, len(gl), partial_now))
        w("Estimate after batches B1-B7: %d of %d (%d%%), %d skipped by design; the rest is GoLand-only or sits in later batches."
          % (after, len(gl), round(100.0 * after / len(gl)), skip))
        w("After all batches (B1-B30): %d of %d; the %d skipped rows are IDE mechanics or covered elsewhere (reason in the Status column)." % (len(gl) - skip, len(gl), skip))
        w("")
    return "\n".join(o), rows_all

if __name__ == "__main__":
    plan = io.open(sys.argv[2], encoding="utf-8").read()
    text, rows = main(None, plan)
    io.open(sys.argv[1], "w", encoding="utf-8", newline="\n").write(text + plan)
    ids = [r[1] for r in rows]
    dup = [i for i, c in collections.Counter(ids).items() if c > 1]
    print("rows", len(rows), "dups", dup)
    known = set(ids)
    badtarget = [r[1] for r in rows if r[5].startswith("s:") and r[5][2:].strip() not in known]
    print("same-as targets missing", badtarget)
    # verify that every port/approx rule appears in the plan exactly once
    import re
    planned = re.findall(r"`([A-Za-z0-9:_\-]+)`", plan[plan.index("### B1."):])
    todo = [r[1] for r in rows if status_key(r[5]) in ("port", "port-approx", "native (partial)")]
    missing = [t for t in todo if t not in planned]
    extra = [p for p in set(planned) if p in ids and p not in todo]
    multi = [t for t in todo if planned.count(t) > 1]
    print("missing from plan", len(missing), missing)
    print("planned but native/skip", extra)
    print("planned twice", multi)
    for m in re.finditer(r"### (B\d+)\.[^\n]*?\((\d+)\)[^\n]*\n(.*?)(?=\n### |\nEvery batch|\Z)", plan, re.S):
        ids_b = [x for x in re.findall(r"`([A-Za-z0-9:_\-]+)`", m.group(3)) if x in ids]
        if len(ids_b) != int(m.group(2)): print("count mismatch", m.group(1), m.group(2), len(ids_b))

def dump():
    _, rows = main(None)
    for r in rows:
        if status_key(r[5]) in ("port", "port-approx", "native (partial)"):
            print(r[1], r[3], r[4], status_key(r[5])[:6], r[6], r[7])

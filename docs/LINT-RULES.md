# Lint rules: golangci-lint, staticcheck, revive, gocritic, gosec -> native rules

The mapping table that drives porting the checks of golangci-lint and the analyzers it runs into native rules of the plugin
(the rule engine of `go-psi-ide`, package `io.github.golangsupport.ide.rules`). Decision of 2026-10-03: the native checks are the default,
golangci-lint is optional and off by default. Source of the rule list and semantics: `docs/LINTING-CATALOG.md` and its pinned sources
(golangci efac294f1005, staticcheck v0.8.1, revive v1.17.0, gocritic v0.15.0, gosec v2.29.0, x/tools v0.50.0). govet analyzers, gocritic checkers,
gosec G-rules and testifylint checkers have no per-rule cards in the catalog yet; their rows are from the analyzers' documentation and must be
reconciled with the pinned sources before a batch starts (a rule the pinned version lacks is dropped from the batch).

The section **GoLand inspections** lists the 123 inspections of GoLand (`goland:<Name>`, from the catalog) against the rest of this table: it shows which of them we
already have, which are the same check as a rule above, and which are GoLand-only (batches B28-B30).

## Columns

- **Rule id** — the engine id: `errcheck`, `SA4006`, `govet:printf`, `revive:var-naming`, `gocritic:ifElseChain`, `gosec:G104`, `testifylint:len`.
  Staticcheck ids are bare (golangci prints them as the message prefix); the engine's `linter` of S* is `gosimple`, of ST* `stylecheck`,
  of SA*/QF* `staticcheck` (v2 reports all of them as `staticcheck`: `GolangciLinters.reportedAs`).
- **Scope** — the PSI unit the rule visits: CALL, EXPRESSION, STATEMENT, FUNCTION (a body, with its control flow), TYPE_SPEC, FILE, PACKAGE
  (all files of a package, e.g. receiver-name consistency), MODULE.
- **Needs** — SYNTAX (PSI only), TYPES (`GoSemanticService`), FLOW (`semantic.flow`: CFG, liveness, reaching definitions, nilness),
  SSA-heavy (the original relies on SSA / pointer facts; a port is an approximation on FLOW), PROJECT_INDEX (stub indices across packages).
- **Status** — `native: <ShortName>` already reported by an inspection of the plugin; `native (partial)` covers part of the rule, the rest
  goes to the batch named in the plan; `same-as: <id>` (GoLand rows only) the check is implemented once under that id and the GoLand name is its alias; `port` feasible on PSI + types + flow; `port-approx` SSA-heavy, an approximation; `skip: <reason>`.
- **Size** — S (< 1 day, one visitor and a fixture), M (flow or several shapes, fixes), L (cross-package or SSA-like reasoning).
- **Default** — in golangci's default set: `v1+v2` (v1 default linters, v2 `linters.default: standard`), `v2` (only via v2 staticcheck's
  default `checks`, which include ST*/QF* except ST1000/1003/1016/1020/1021/1022), `revive default` / `gocritic default` (on when that linter
  is enabled with no rule list), `—` otherwise.
- **Options** — settings worth reading from `.golangci.yml` (`io.github.golangsupport.lint.config`) into rule options.
- **License** — of the original analyzer. † — not pinned in the catalog; from the project's repository, verify before copying any code.
  Ports are re-implementations from behavior (catalog cards, docs, test data as a spec); copied fragments go to `NOTICE.md` with
  attribution. `nolintlint` is part of golangci-lint (GPL-3.0): behavior only, never code.

## errcheck, ineffassign, unused

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `errcheck` | errcheck | error result (or type assertion `ok`) dropped | CALL | TYPES | native: `GoUncheckedError` | M | v1+v2 | `exclude-functions`, `check-type-assertions`, `check-blank`, `disable-default-exclusions` | MIT† |
| `ineffassign` | ineffassign | assignment whose value is never read | FUNCTION | FLOW | native: `GoIneffectualAssignment` | M | v1+v2 | — | MIT† |
| `unused` | unused | U1000: unused unexported func/type/const/var/field/method (exported only in `package main` / internal) | PACKAGE | TYPES + PROJECT_INDEX | native (partial): GoUnusedVariable, GoUnusedParameter, GoUnusedExported (unexported package-level decls and fields still to port) | L | v1+v2 | `field-writes-are-uses`, `exported-fields-are-used`, `parameters-are-used`, `local-variables-are-used`, `generated-is-used` | MIT (staticcheck) |

## govet analyzers

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `govet:appends` | govet | `append(s)` with no values to add | CALL | TYPES | native: GoVetAppendsRule | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:asmdecl` | govet | Go declaration does not match the assembly (`.s`) frame | FILE | TYPES | skip: assembly files, low value in an editor | L | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:assign` | govet | `x = x` | STATEMENT | SYNTAX | native: `GoSelfAssignment` | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:atomic` | govet | `x = atomic.AddInt64(&x, 1)` | STATEMENT | TYPES | native: GoVetAtomicRule | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:atomicalign` | govet | 64-bit atomic field not 64-bit aligned on 32-bit platforms | CALL | TYPES | native: GoVetAtomicAlignRule | M | — | — | BSD-3-Clause (x/tools) |
| `govet:bools` | govet | redundant / suspect `a == x \|\| a == x`, `a != 1 \|\| a != 2` | EXPRESSION | TYPES | native: GoVetBoolsRule | M | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:buildtag` | govet | malformed or misplaced `//go:build` / `+build` | FILE | SYNTAX | native: `GoBuildConstraint` | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:cgocall` | govet | Go pointers passed to C violating cgo rules | CALL | TYPES | skip: cgo-only, needs cgo type info | L | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:composites` | govet | unkeyed fields in a composite literal of an imported struct type | EXPRESSION | TYPES | port | S | v1+v2 | `composites.whitelist` | BSD-3-Clause (x/tools) |
| `govet:copylocks` | govet | lock value copied (assignment, call, range, return) | EXPRESSION | TYPES | native: `GoCopyLocks` | M | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:deepequalerrors` | govet | `reflect.DeepEqual` on errors | CALL | TYPES | port | S | — | — | BSD-3-Clause (x/tools) |
| `govet:defers` | govet | `defer log.Println(time.Since(start))` evaluates args early | STATEMENT | TYPES | native: GoVetDefersRule | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:directive` | govet | misplaced or unknown `//go:debug` directive | FILE | SYNTAX | port | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:errorsas` | govet | `errors.As` second argument not a non-nil pointer | CALL | TYPES | native: `GoErrorsPackage` | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:fieldalignment` | govet | struct fields ordered wastefully | TYPE_SPEC | TYPES | skip: noisy; host has Reorder Fields intention (`GoReorderFieldsIntention`) | M | — | — | BSD-3-Clause (x/tools) |
| `govet:findcall` | govet | demo analyzer | CALL | SYNTAX | skip: test analyzer | S | — | — | BSD-3-Clause (x/tools) |
| `govet:framepointer` | govet | assembly clobbers frame pointer | FILE | SYNTAX | skip: assembly | M | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:hostport` | govet | `fmt.Sprintf("%s:%d", host, port)` not IPv6-safe | CALL | TYPES | port | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:httpmux` | govet | `http.ServeMux` pattern with Go 1.22 syntax under older go version | CALL | TYPES | port | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:httpresponse` | govet | `resp.Body` used/deferred before the error check | STATEMENT | TYPES + FLOW | native: `GoDeferBeforeErrorCheck` | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:ifaceassert` | govet | impossible interface-to-interface assertion | EXPRESSION | TYPES | native: GoVetIfaceAssertRule | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:loopclosure` | govet | loop variable captured by `go`/`defer` closure (go < 1.22) | STATEMENT | TYPES | native: `GoLoopClosure` | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:lostcancel` | govet | cancel of `context.WithCancel` not called on all paths | FUNCTION | TYPES + FLOW | native: `GoLostCancel` | M | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:nilfunc` | govet | comparison of a function with nil | EXPRESSION | TYPES | native: GoVetNilFuncRule | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:nilness` | govet | nil dereference / impossible nil comparison | FUNCTION | TYPES + FLOW | native: `GoNilDereference`, `GoImpossibleNilCheck` | L | — | — | BSD-3-Clause (x/tools) |
| `govet:printf` | govet | printf format/argument mismatch, non-constant format | CALL | TYPES | native: `GoPrintf` | L | v1+v2 | `printf.funcs` | BSD-3-Clause (x/tools) |
| `govet:reflectvaluecompare` | govet | `reflect.Value` compared with `==` | EXPRESSION | TYPES | port | S | — | — | BSD-3-Clause (x/tools) |
| `govet:shadow` | govet | variable shadows an outer one that is used after | FUNCTION | TYPES + FLOW | native (partial): GoShadowedError (errors only; generalize behind `strict`) | M | — | `shadow.strict` | BSD-3-Clause (x/tools) |
| `govet:shift` | govet | shift count >= width of the operand | EXPRESSION | TYPES | native: GoVetShiftRule | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:sigchanyzer` | govet | unbuffered channel passed to `signal.Notify` | CALL | TYPES | native: GoSigchanyzerRule | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:slog` | govet | `slog` key/value pairs mismatched | CALL | TYPES | port | M | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:sortslice` | govet | `sort.Slice` called on a non-slice | CALL | TYPES | native: GoVetSortSliceRule | S | — | — | BSD-3-Clause (x/tools) |
| `govet:stdmethods` | govet | well-known method (`String`, `ReadFrom`, `MarshalJSON`…) with wrong signature | FUNCTION | TYPES | port | M | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:stdversion` | govet | std symbol newer than the module's `go` version | EXPRESSION | TYPES + PROJECT_INDEX | port | M | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:stringintconv` | govet | `string(int)` conversion | EXPRESSION | TYPES | native: GoVetStringIntConvRule | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:structtag` | govet | malformed or duplicate struct tag | TYPE_SPEC | SYNTAX | native: `GoStructTag` | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:testinggoroutine` | govet | `t.Fatal` from a goroutine started by the test | CALL | TYPES | native: `GoTestingGoroutine` | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:tests` | govet | malformed `Test`/`Example`/`Benchmark`/`Fuzz` names and signatures | FUNCTION | TYPES | port | M | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:timeformat` | govet | layout `2006-02-01` | CALL | TYPES | native: `GoTimeLayout` | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:unmarshal` | govet | non-pointer passed to `json.Unmarshal` & co. | CALL | TYPES | native: GoVetUnmarshalRule | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:unreachable` | govet | unreachable code | FUNCTION | FLOW | native: `GoUnreachableCode` | S | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:unsafeptr` | govet | invalid `uintptr` -> `unsafe.Pointer` conversion | EXPRESSION | TYPES | native: GoVetUnsafePointerRule | M | v1+v2 | — | BSD-3-Clause (x/tools) |
| `govet:unusedresult` | govet | result of pure function (`fmt.Sprintf`, `errors.New`…) dropped | CALL | TYPES | native: `GoUnusedResult` | S | v1+v2 | `unusedresult.funcs`, `stringmethods` | BSD-3-Clause (x/tools) |
| `govet:unusedwrite` | govet | write to a struct field/array element never read | FUNCTION | SSA-heavy | port-approx | L | — | — | BSD-3-Clause (x/tools) |
| `govet:waitgroup` | govet | `wg.Add` inside the goroutine | STATEMENT | TYPES | native: `GoWaitGroupAddInGoroutine` | S | v1+v2 | — | BSD-3-Clause (x/tools) |

## staticcheck SA (bugs)

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `SA1000` | staticcheck | invalid regexp in a constant argument | CALL | TYPES | native: GoInvalidRegexpRule | S | v1+v2 | — | MIT |
| `SA1001` | staticcheck | invalid `text/template` / `html/template` | CALL | TYPES | native: GoTemplateParseRule | M | v1+v2 | — | MIT |
| `SA1002` | staticcheck | impossible `time.Parse` layout | CALL | TYPES | native (partial): GoTimeLayout (notation checks; full layout validation to port) | M | v1+v2 | — | MIT |
| `SA1003` | staticcheck | `encoding/binary` with a type of undefined size | CALL | TYPES | native: GoBinaryWriteRule | S | v1+v2 | — | MIT |
| `SA1004` | staticcheck | `time.Sleep(1)` with a small untyped constant (nanoseconds) | CALL | TYPES | native: GoSleepNanosecondsRule | S | v1+v2 | — | MIT |
| `SA1005` | staticcheck | `exec.Command("ls -l")`: arguments inside the program name | CALL | TYPES | native: GoExecCommandRule | S | v1+v2 | — | MIT |
| `SA1006` | staticcheck | `Printf(dynamic)` without arguments | CALL | TYPES | native: GoDynamicFormatRule | S | v1+v2 | — | MIT |
| `SA1007` | staticcheck | constant URL that does not parse | CALL | TYPES | native: GoInvalidUrlRule | S | v1+v2 | — | MIT |
| `SA1008` | staticcheck | non-canonical key in direct `http.Header` map access | EXPRESSION | TYPES | native: GoHttpHeaderKeyRule | S | v1+v2 | — | MIT |
| `SA1010` | staticcheck | `FindAll(…, 0)` returns nothing | CALL | TYPES | native: GoRegexpFindAllZeroRule | S | v1+v2 | — | MIT |
| `SA1011` | staticcheck | invalid UTF-8 constant passed to `strings` API | CALL | TYPES | native: GoInvalidUtf8CutsetRule | S | v1+v2 | — | MIT |
| `SA1012` | staticcheck | nil `context.Context` passed | CALL | TYPES | native: GoNilContextRule | S | v1+v2 | — | MIT |
| `SA1013` | staticcheck | `Seek(io.SeekStart, 0)`: offset and whence swapped | CALL | TYPES | native: GoSeekerArgumentsRule | S | v1+v2 | — | MIT |
| `SA1014` | staticcheck | non-pointer passed to `Unmarshal`/`Decode` | CALL | TYPES | native: GoUnmarshalPointerRule | S | v1+v2 | — | MIT |
| `SA1015` | staticcheck | `time.Tick` leaking a ticker (go < 1.23) | CALL | TYPES | native: GoTimeTickLeakRule | S | v1+v2 | — | MIT |
| `SA1016` | staticcheck | trapping `SIGKILL`/`SIGSTOP` | CALL | TYPES | native: GoUntrappableSignalRule | S | v1+v2 | — | MIT |
| `SA1017` | staticcheck | unbuffered channel for `signal.Notify` | CALL | TYPES | native: GoUnbufferedSignalChannelRule | S | v1+v2 | — | MIT |
| `SA1018` | staticcheck | `strings.Replace(…, 0)` replaces nothing | CALL | TYPES | native: GoReplaceZeroRule | S | v1+v2 | — | MIT |
| `SA1019` | staticcheck | use of a deprecated identifier (`Deprecated:` paragraph) | EXPRESSION | TYPES + PROJECT_INDEX | port | M | v1+v2 | — | MIT |
| `SA1020` | staticcheck | invalid `host:port` constant | CALL | TYPES | native: GoInvalidListenAddressRule | S | v1+v2 | — | MIT |
| `SA1021` | staticcheck | `bytes.Equal` on `net.IP` | CALL | TYPES | native: GoBytesEqualIpRule | S | v1+v2 | — | MIT |
| `SA1023` | staticcheck | `io.Writer` implementation modifies its buffer | FUNCTION | SSA-heavy | port-approx | M | v1+v2 | — | MIT |
| `SA1024` | staticcheck | duplicate characters in a `Trim` cutset | CALL | TYPES | native: GoNonUniqueCutsetRule | S | v1+v2 | — | MIT |
| `SA1025` | staticcheck | `Timer.Reset` return value used incorrectly | CALL | TYPES + FLOW | port-approx | M | v1+v2 | — | MIT |
| `SA1026` | staticcheck | marshaling channels or functions | CALL | TYPES | native: GoMarshalUnsupportedRule | S | v1+v2 | — | MIT |
| `SA1027` | staticcheck | misaligned 64-bit atomic access | CALL | TYPES | native: GoAtomicAlignmentRule | M | v1+v2 | — | MIT |
| `SA1028` | staticcheck | `sort.Slice` on a non-slice | CALL | TYPES | native: GoSortSliceRule | S | v1+v2 | — | MIT |
| `SA1029` | staticcheck | built-in type as `context.WithValue` key | CALL | TYPES | native: GoContextKeyTypeRule | S | v1+v2 | — | MIT |
| `SA1030` | staticcheck | invalid `strconv` base / bitSize | CALL | TYPES | native: GoStrconvArgumentsRule | S | v1+v2 | — | MIT |
| `SA1031` | staticcheck | overlapping src/dst for an encoder | CALL | TYPES | port-approx | M | v1+v2 | — | MIT |
| `SA1032` | staticcheck | `errors.Is(target, err)` arguments swapped | CALL | TYPES | native: GoErrorsIsOrderRule | S | v1+v2 | — | MIT |
| `SA2000` | staticcheck | `wg.Add` inside the goroutine | STATEMENT | TYPES | native: `GoWaitGroupAddInGoroutine` | S | v1+v2 | — | MIT |
| `SA2001` | staticcheck | empty critical section `mu.Lock(); mu.Unlock()` | STATEMENT | TYPES | native: GoEmptyCriticalSectionRule | S | v1+v2 | — | MIT |
| `SA2002` | staticcheck | `t.FailNow` from a goroutine | CALL | TYPES | native: `GoTestingGoroutine` | S | v1+v2 | — | MIT |
| `SA2003` | staticcheck | `defer mu.Lock()` right after `Lock` | STATEMENT | TYPES | native: GoDeferLockRule | S | v1+v2 | — | MIT |
| `SA3000` | staticcheck | `TestMain` without `os.Exit` (go < 1.15) | FUNCTION | TYPES | skip: obsolete since Go 1.15 | S | v1+v2 | — | MIT |
| `SA3001` | staticcheck | assignment to `b.N` | STATEMENT | TYPES | native: GoBenchmarkNRule | S | v1+v2 | — | MIT |
| `SA4000` | staticcheck | identical operands of a binary expression (`x == x`) | EXPRESSION | TYPES | native: GoIdenticalOperandsRule | S | v1+v2 | — | MIT |
| `SA4001` | staticcheck | `&*x` | EXPRESSION | TYPES | native: GoIneffectiveCopyRule | S | v1+v2 | — | MIT |
| `SA4003` | staticcheck | unsigned compared `< 0` / `>= 0` | EXPRESSION | TYPES | native: GoExtremeComparisonRule | S | v1+v2 | — | MIT |
| `SA4004` | staticcheck | loop exits unconditionally after one iteration | STATEMENT | FLOW | port | M | v1+v2 | — | MIT |
| `SA4005` | staticcheck | field assignment to a value receiver never observed | FUNCTION | TYPES + FLOW | port-approx | M | v1+v2 | — | MIT |
| `SA4006` | staticcheck | value assigned and never read | FUNCTION | FLOW | native: `GoIneffectualAssignment` | M | v1+v2 | — | MIT |
| `SA4008` | staticcheck | loop condition variable never changes | STATEMENT | FLOW | port-approx | M | v1+v2 | — | MIT |
| `SA4009` | staticcheck | argument overwritten before first use | FUNCTION | FLOW | port | M | v1+v2 | — | MIT |
| `SA4010` | staticcheck | `append` result never observed | FUNCTION | FLOW | port-approx | M | v1+v2 | — | MIT |
| `SA4011` | staticcheck | `break` in a `switch`/`select` inside a loop | STATEMENT | SYNTAX | native: GoIneffectiveBreakRule | S | v1+v2 | — | MIT |
| `SA4012` | staticcheck | comparison with `NaN` | EXPRESSION | TYPES | native: GoNaNComparisonRule | S | v1+v2 | — | MIT |
| `SA4013` | staticcheck | `!!b` | EXPRESSION | SYNTAX | native: GoDoubleNegationRule | S | v1+v2 | — | MIT |
| `SA4014` | staticcheck | duplicate condition in an `if`/`else if` chain | STATEMENT | TYPES | native: GoRepeatedConditionRule | S | v1+v2 | — | MIT |
| `SA4015` | staticcheck | `math.Ceil(float64(i))` of an integer | CALL | TYPES | native: GoIntegerMathRule | S | v1+v2 | — | MIT |
| `SA4016` | staticcheck | `x ^ 0`, `x & 0`, `x << 0` | EXPRESSION | TYPES | native: GoSillyBitwiseRule | S | v1+v2 | — | MIT |
| `SA4017` | staticcheck | result of a pure function discarded | CALL | TYPES | native: `GoUnusedResult` | S | v1+v2 | — | MIT |
| `SA4018` | staticcheck | self-assignment | STATEMENT | SYNTAX | native: `GoSelfAssignment` | S | v1+v2 | — | MIT |
| `SA4019` | staticcheck | duplicate build constraints | FILE | SYNTAX | port | S | v1+v2 | — | MIT |
| `SA4020` | staticcheck | unreachable case in a type switch | STATEMENT | TYPES | native: GoUnreachableTypeCaseRule | M | v1+v2 | — | MIT |
| `SA4021` | staticcheck | `x = append(y)` | CALL | TYPES | native: GoSingleArgAppendRule | S | v1+v2 | — | MIT |
| `SA4022` | staticcheck | `&x == nil` | EXPRESSION | TYPES | native: GoAddressIsNilRule | S | v1+v2 | — | MIT |
| `SA4023` | staticcheck | impossible comparison of an interface with nil (typed nil) | EXPRESSION | TYPES + FLOW | port-approx | L | v1+v2 | — | MIT |
| `SA4024` | staticcheck | `len(x) < 0` | EXPRESSION | TYPES | native: GoBuiltinNegativeRule | S | v1+v2 | — | MIT |
| `SA4025` | staticcheck | integer division of constants yields 0 | EXPRESSION | TYPES | native: GoIntegerDivisionZeroRule | S | v1+v2 | — | MIT |
| `SA4026` | staticcheck | `-0.0` constant | EXPRESSION | SYNTAX | native: GoNegativeZeroRule | S | v1+v2 | — | MIT |
| `SA4027` | staticcheck | `u.Query().Set(...)` on a copy | CALL | TYPES | native: GoUrlQueryCopyRule | S | v1+v2 | — | MIT |
| `SA4028` | staticcheck | `x % 1` | EXPRESSION | TYPES | native: GoModuloOneRule | S | v1+v2 | — | MIT |
| `SA4029` | staticcheck | `sort.IntSlice(x)` as a statement | STATEMENT | TYPES | native: GoSortTypeConversionRule | S | v1+v2 | — | MIT |
| `SA4030` | staticcheck | `rand.New(...)` result discarded | CALL | TYPES | native: GoRandIntnOneRule | S | v1+v2 | — | MIT |
| `SA4031` | staticcheck | nil check of a never-nil value | EXPRESSION | TYPES + FLOW | native: `GoImpossibleNilCheck` | S | v1+v2 | — | MIT |
| `SA4032` | staticcheck | `runtime.GOOS == "linx"` | EXPRESSION | TYPES | native: GoImpossibleGoosRule | S | v1+v2 | — | MIT |
| `SA5000` | staticcheck | assignment to a nil map | STATEMENT | TYPES + FLOW | port | M | v1+v2 | — | MIT |
| `SA5001` | staticcheck | `defer f.Close()` before the error check | STATEMENT | TYPES + FLOW | native: `GoDeferBeforeErrorCheck` | S | v1+v2 | — | MIT |
| `SA5002` | staticcheck | empty `for {}` spins | STATEMENT | SYNTAX | native: GoSpinningLoopRule | S | v1+v2 | — | MIT |
| `SA5003` | staticcheck | `defer` in an infinite loop | STATEMENT | SYNTAX | native: GoDeferInInfiniteLoopRule | S | v1+v2 | — | MIT |
| `SA5004` | staticcheck | `for { select { … default: } }` busy loop | STATEMENT | SYNTAX | native: GoBusySelectLoopRule | S | v1+v2 | — | MIT |
| `SA5005` | staticcheck | finalizer references the finalized object | CALL | TYPES | native: GoCyclicFinalizerRule | M | v1+v2 | — | MIT |
| `SA5007` | staticcheck | infinite recursion | FUNCTION | TYPES + FLOW | port | M | v1+v2 | — | MIT |
| `SA5008` | staticcheck | invalid struct tag | TYPE_SPEC | SYNTAX | native: `GoStructTag` | S | v1+v2 | — | MIT |
| `SA5009` | staticcheck | printf format mismatch | CALL | TYPES | native: `GoPrintf` | S | v1+v2 | — | MIT |
| `SA5010` | staticcheck | impossible type assertion | EXPRESSION | TYPES | native: GoImpossibleAssertionRule | M | v1+v2 | — | MIT |
| `SA5011` | staticcheck | nil pointer dereference after a nil check | FUNCTION | TYPES + FLOW | native: `GoNilDereference` | L | v1+v2 | — | MIT |
| `SA5012` | staticcheck | odd-length slice to a pairs function | CALL | TYPES | native: GoEvenSliceLengthRule | M | v1+v2 | — | MIT |
| `SA6000` | staticcheck | `regexp.MustCompile` in a loop | STATEMENT | TYPES | native: GoRegexpInLoopRule | S | v1+v2 | — | MIT |
| `SA6001` | staticcheck | `m[string(b)]` hoisted out of the index | EXPRESSION | TYPES | native: GoMapByteKeyRule | S | v1+v2 | — | MIT |
| `SA6002` | staticcheck | non-pointer value put into `sync.Pool` | CALL | TYPES | native: GoPoolPutRule | S | v1+v2 | — | MIT |
| `SA6003` | staticcheck | `range []rune(s)` | STATEMENT | TYPES | native: GoRangeRunesRule | S | v1+v2 | — | MIT |
| `SA6005` | staticcheck | `strings.ToLower(a) == strings.ToLower(b)` | EXPRESSION | TYPES | native: GoToLowerComparisonRule | S | v1+v2 | — | MIT |
| `SA6006` | staticcheck | `io.WriteString(w, string(b))` | CALL | TYPES | native: GoWriteStringBytesRule | S | v1+v2 | — | MIT |
| `SA9001` | staticcheck | `defer` in a `range` loop | STATEMENT | SYNTAX | native: `GoDeferInLoop` | S | v1+v2 | — | MIT |
| `SA9002` | staticcheck | file mode looks like a forgotten octal (`644`) | CALL | TYPES | native: GoOctalFileModeRule | S | v1+v2 | — | MIT |
| `SA9003` | staticcheck | empty branch | STATEMENT | SYNTAX | native: GoEmptyBranchRule | S | v1+v2 | — | MIT |
| `SA9004` | staticcheck | only the first constant of a group has an explicit type | TYPE_SPEC | TYPES | port | S | v1+v2 | — | MIT |
| `SA9005` | staticcheck | marshaling a struct with no exported fields | CALL | TYPES | native: GoNoopMarshalRule | S | v1+v2 | — | MIT |
| `SA9006` | staticcheck | shift in a too-narrow type before widening | EXPRESSION | TYPES | native: GoDubiousShiftRule | S | v1+v2 | — | MIT |
| `SA9007` | staticcheck | `os.RemoveAll` of a directory that should not be deleted | CALL | TYPES | native: GoRemoveUserDirRule | S | v1+v2 | — | MIT |
| `SA9008` | staticcheck | `else` branch of a type assertion reads the shadowed zero value | STATEMENT | TYPES | native: GoShadowedAssertionElseRule | M | v1+v2 | — | MIT |
| `SA9009` | staticcheck | `// go:generate` with a space is not a directive | FILE | SYNTAX | port | S | v1+v2 | — | MIT |
| `SA9010` | staticcheck | `defer setup()` instead of `defer setup()()` | STATEMENT | TYPES | native: GoDeferredFuncNotCalledRule | S | v1+v2 | — | MIT |

## staticcheck S (simple, former gosimple)

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `S1000` | staticcheck (gosimple) | single-case `select` | STATEMENT | SYNTAX | native: GoSingleCaseSelectRule | S | v1+v2 | — | MIT |
| `S1001` | staticcheck (gosimple) | element copy loop -> `copy` | STATEMENT | TYPES | native: GoLoopCopyRule | M | v1+v2 | — | MIT |
| `S1002` | staticcheck (gosimple) | `if x == true` | EXPRESSION | TYPES | native: GoBoolComparisonRule | S | v1+v2 | — | MIT |
| `S1003` | staticcheck (gosimple) | `strings.Index(…) != -1` -> `Contains` | EXPRESSION | TYPES | native: GoStringsIndexRule | S | v1+v2 | — | MIT |
| `S1004` | staticcheck (gosimple) | `bytes.Compare(a, b) == 0` -> `bytes.Equal` | EXPRESSION | TYPES | native: GoBytesCompareRule | S | v1+v2 | — | MIT |
| `S1005` | staticcheck (gosimple) | `for x, _ = range` / `x, _ = <-ch` | STATEMENT | SYNTAX | native: GoUnnecessaryBlankRule | S | v1+v2 | — | MIT |
| `S1006` | staticcheck (gosimple) | `for true {}` | STATEMENT | SYNTAX | native: GoForTrueRule | S | v1+v2 | — | MIT |
| `S1007` | staticcheck (gosimple) | regexp in an interpreted string -> raw string | CALL | TYPES | native: GoRegexpRawStringRule | S | v1+v2 | — | MIT |
| `S1008` | staticcheck (gosimple) | `if c { return true }; return false` | STATEMENT | SYNTAX | native: GoIfReturnBoolRule | S | v1+v2 | — | MIT |
| `S1009` | staticcheck (gosimple) | `x != nil && len(x) != 0` | EXPRESSION | TYPES | native: GoNilLenCheckRule | S | v1+v2 | — | MIT |
| `S1010` | staticcheck (gosimple) | `s[a:len(s)]` | EXPRESSION | TYPES | native: GoSliceLenRule | S | v1+v2 | — | MIT |
| `S1011` | staticcheck (gosimple) | append loop -> `append(a, b...)` | STATEMENT | TYPES | native: GoLoopAppendRule | S | v1+v2 | — | MIT |
| `S1012` | staticcheck (gosimple) | `time.Now().Sub(t)` -> `time.Since` | CALL | TYPES | native: GoTimeSinceRule | S | v1+v2 | — | MIT |
| `S1016` | staticcheck (gosimple) | field-by-field copy -> conversion | EXPRESSION | TYPES | native: GoStructConversionRule | M | v1+v2 | — | MIT |
| `S1017` | staticcheck (gosimple) | `if HasPrefix { s = s[n:] }` -> `TrimPrefix` | STATEMENT | TYPES | native: GoTrimPrefixRule | M | v1+v2 | — | MIT |
| `S1018` | staticcheck (gosimple) | shifting loop -> `copy` | STATEMENT | TYPES | native: GoLoopSlideRule | M | v1+v2 | — | MIT |
| `S1019` | staticcheck (gosimple) | redundant `make` length/capacity | CALL | TYPES | native: GoMakeLenCapRule | S | v1+v2 | — | MIT |
| `S1020` | staticcheck (gosimple) | `if _, ok := x.(T); ok && x != nil` | EXPRESSION | TYPES | native: GoAssertNotNilRule | S | v1+v2 | — | MIT |
| `S1021` | staticcheck (gosimple) | `var x T; x = v` -> one statement | STATEMENT | SYNTAX | native: GoMergeVarAssignRule | S | v1+v2 | — | MIT |
| `S1023` | staticcheck (gosimple) | redundant trailing `return`/`break` | STATEMENT | SYNTAX | native: GoRedundantControlFlowRule | S | v1+v2 | — | MIT |
| `S1024` | staticcheck (gosimple) | `t.Sub(time.Now())` -> `time.Until` | CALL | TYPES | native: GoTimeUntilRule | S | v1+v2 | — | MIT |
| `S1025` | staticcheck (gosimple) | `fmt.Sprintf("%s", s)` of a string | CALL | TYPES | native: GoRedundantSprintfRule | S | v1+v2 | — | MIT |
| `S1028` | staticcheck (gosimple) | `errors.New(fmt.Sprintf(…))` -> `fmt.Errorf` | CALL | TYPES | native: GoErrorsNewSprintfRule | S | v1+v2 | — | MIT |
| `S1029` | staticcheck (gosimple) | `range []rune(s)` -> `range s` | STATEMENT | TYPES | native: GoRangeStringRunesRule | S | v1+v2 | — | MIT |
| `S1030` | staticcheck (gosimple) | `string(buf.Bytes())` -> `buf.String()` | EXPRESSION | TYPES | native: GoBufferConversionRule | S | v1+v2 | — | MIT |
| `S1031` | staticcheck (gosimple) | nil check around `range` | STATEMENT | TYPES | native: GoNilCheckAroundRangeRule | S | v1+v2 | — | MIT |
| `S1032` | staticcheck (gosimple) | `sort.Sort(sort.IntSlice(x))` -> `sort.Ints` | CALL | TYPES | native: GoSortHelperRule | S | v1+v2 | — | MIT |
| `S1033` | staticcheck (gosimple) | `if _, ok := m[k]; ok { delete(m, k) }` | STATEMENT | TYPES | native: GoGuardedDeleteRule | S | v1+v2 | — | MIT |
| `S1034` | staticcheck (gosimple) | repeated assertions in a type switch -> bind the variable | STATEMENT | TYPES | native: GoTypeSwitchAssertRule | M | v1+v2 | — | MIT |
| `S1035` | staticcheck (gosimple) | `CanonicalHeaderKey` inside `Header.Add/Set/Get/Del` | CALL | TYPES | native: GoCanonicalHeaderKeyRule | S | v1+v2 | — | MIT |
| `S1036` | staticcheck (gosimple) | guard before map increment / append | STATEMENT | TYPES | native: GoMapGuardRule | S | v1+v2 | — | MIT |
| `S1037` | staticcheck (gosimple) | `select { case <-time.After(d): }` -> `time.Sleep` | STATEMENT | TYPES | native: GoElaborateSleepRule | S | v1+v2 | — | MIT |
| `S1038` | staticcheck (gosimple) | `Print(Sprintf(…))` -> `Printf` | CALL | TYPES | native: GoPrintSprintfRule | S | v1+v2 | — | MIT |
| `S1039` | staticcheck (gosimple) | `fmt.Sprint("literal")` | CALL | TYPES | native: GoSprintLiteralRule | S | v1+v2 | — | MIT |
| `S1040` | staticcheck (gosimple) | assertion to the current interface type | EXPRESSION | TYPES | native: GoSameTypeAssertionRule | S | v1+v2 | — | MIT |

## staticcheck ST (stylecheck)

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `ST1000` | staticcheck (stylecheck) | missing or malformed package comment | PACKAGE | SYNTAX | port | S | — | — | MIT |
| `ST1001` | staticcheck (stylecheck) | dot import | FILE | SYNTAX | port | S | v2 | `dot-import-whitelist` | MIT |
| `ST1003` | staticcheck (stylecheck) | naming (MixedCaps, initialisms) | FILE | SYNTAX | port | M | — | `initialisms` | MIT |
| `ST1005` | staticcheck (stylecheck) | error string capitalized or ending with punctuation | CALL | TYPES | port | S | v2 | — | MIT |
| `ST1006` | staticcheck (stylecheck) | receiver named `self`/`this` | FUNCTION | SYNTAX | port | S | v2 | — | MIT |
| `ST1008` | staticcheck (stylecheck) | `error` not the last result | FUNCTION | TYPES | port | S | v2 | — | MIT |
| `ST1011` | staticcheck (stylecheck) | `time.Duration` variable named with a unit suffix | TYPE_SPEC | TYPES | port | S | v2 | — | MIT |
| `ST1012` | staticcheck (stylecheck) | error variable not named `ErrX`/`errX` | TYPE_SPEC | TYPES | port | S | v2 | — | MIT |
| `ST1013` | staticcheck (stylecheck) | HTTP status as a number | CALL | TYPES | port | S | v2 | `http-status-code-whitelist` | MIT |
| `ST1015` | staticcheck (stylecheck) | `default` in the middle of a switch | STATEMENT | SYNTAX | port | S | v2 | — | MIT |
| `ST1016` | staticcheck (stylecheck) | receivers of one type named differently | PACKAGE | SYNTAX | port | S | — | — | MIT |
| `ST1017` | staticcheck (stylecheck) | Yoda condition `nil == x` | EXPRESSION | SYNTAX | port | S | v2 | — | MIT |
| `ST1018` | staticcheck (stylecheck) | invisible / control characters in a string literal | EXPRESSION | SYNTAX | port | S | v2 | — | MIT |
| `ST1019` | staticcheck (stylecheck) | package imported twice | FILE | SYNTAX | port | S | v2 | — | MIT |
| `ST1020` | staticcheck (stylecheck) | doc of an exported function does not start with its name | FUNCTION | SYNTAX | native: `GoDocComment` | S | — | — | MIT |
| `ST1021` | staticcheck (stylecheck) | doc of an exported type does not start with its name | TYPE_SPEC | SYNTAX | native: `GoDocComment` | S | — | — | MIT |
| `ST1022` | staticcheck (stylecheck) | doc of an exported var/const does not start with its name | TYPE_SPEC | SYNTAX | native: `GoDocComment` | S | — | — | MIT |
| `ST1023` | staticcheck (stylecheck) | redundant explicit type in `var x T = T(…)` | STATEMENT | TYPES | port | S | v2 | — | MIT |

## staticcheck QF (quickfix)

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `QF1001` | staticcheck | apply De Morgan's law | EXPRESSION | SYNTAX | port | S | v2 | — | MIT |
| `QF1002` | staticcheck | tagless switch comparing one value -> tagged switch | STATEMENT | TYPES | port | S | v2 | — | MIT |
| `QF1003` | staticcheck | `if/else if` chain on one value -> switch | STATEMENT | TYPES | port | M | v2 | — | MIT |
| `QF1004` | staticcheck | `strings.Replace(…, -1)` -> `ReplaceAll` | CALL | TYPES | port | S | v2 | — | MIT |
| `QF1005` | staticcheck | `math.Pow(x, 2)` -> `x * x` | CALL | TYPES | port | S | v2 | — | MIT |
| `QF1006` | staticcheck | `for { if c { break } }` -> loop condition | STATEMENT | SYNTAX | port | S | v2 | — | MIT |
| `QF1007` | staticcheck | conditional assignment merged into the declaration | STATEMENT | SYNTAX | port | S | v2 | — | MIT |
| `QF1008` | staticcheck | redundant embedded field in a selector | EXPRESSION | TYPES | port | S | v2 | — | MIT |
| `QF1009` | staticcheck | `t1 == t2` on `time.Time` -> `Equal` | EXPRESSION | TYPES | port | S | v2 | — | MIT |
| `QF1010` | staticcheck | printing `[]byte` -> explicit `string` conversion | CALL | TYPES | port | S | v2 | — | MIT |
| `QF1011` | staticcheck | redundant type in a variable declaration | STATEMENT | TYPES | port | S | v2 | — | MIT |
| `QF1012` | staticcheck | `w.Write([]byte(fmt.Sprintf(…)))` -> `fmt.Fprintf` | CALL | TYPES | port | S | v2 | — | MIT |

## revive rules

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `revive:add-constant` | revive | magic number / string literal | EXPRESSION | SYNTAX | port | M | — | `allowInts`, `allowStrs`, `maxLitCount`, `ignoreFuncs` | MIT |
| `revive:argument-limit` | revive | too many parameters | FUNCTION | SYNTAX | port | S | — | max (8) | MIT |
| `revive:atomic` | revive | `x = atomic.AddInt64(&x, 1)` | STATEMENT | TYPES | port | S | — | — | MIT |
| `revive:banned-characters` | revive | identifier contains banned characters | FILE | SYNTAX | port | S | — | list of characters | MIT |
| `revive:bare-return` | revive | naked return | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:blank-imports` | revive | blank import outside `main`/tests without comment | FILE | SYNTAX | port | S | revive default | — | MIT |
| `revive:bool-literal-in-expr` | revive | `x == true`, `b && false` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `revive:call-to-gc` | revive | `runtime.GC()` | CALL | TYPES | port | S | — | — | MIT |
| `revive:cognitive-complexity` | revive | function too complex (cognitive) | FUNCTION | SYNTAX | port | M | — | max (7) | MIT |
| `revive:comment-spacings` | revive | `//comment` without space | FILE | SYNTAX | port | S | — | allowed prefixes (`nolint`, …) | MIT |
| `revive:comments-density` | revive | too few comments | FILE | SYNTAX | skip: policy metric, noisy | S | — | min % | MIT |
| `revive:confusing-naming` | revive | methods differing only by capitalization | PACKAGE | SYNTAX | port | S | — | — | MIT |
| `revive:confusing-results` | revive | unnamed results of the same type | FUNCTION | TYPES | port | S | — | — | MIT |
| `revive:constant-logical-expr` | revive | `x == x`, `x && x` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `revive:context-as-argument` | revive | `context.Context` not the first parameter | FUNCTION | TYPES | native: `GoContextPlacement` | S | revive default | `allowTypesBefore` | MIT |
| `revive:context-keys-type` | revive | built-in type as `WithValue` key | CALL | TYPES | port | S | revive default | — | MIT |
| `revive:cyclomatic` | revive | cyclomatic complexity over limit | FUNCTION | SYNTAX | port | S | — | max (10) | MIT |
| `revive:datarace` | revive | named result captured by a goroutine | FUNCTION | SYNTAX | port-approx | M | — | — | MIT |
| `revive:deep-exit` | revive | `os.Exit`/`log.Fatal` outside `main`/`init` | CALL | TYPES | port | S | — | — | MIT |
| `revive:defer` | revive | defer patterns: in loop, recover outside defer, `return` in defer, call chain, method on nil | STATEMENT | TYPES | native (partial): GoDeferInLoop (loop only; other subchecks to port) | M | — | subcheck list | MIT |
| `revive:dot-imports` | revive | dot import | FILE | SYNTAX | port | S | revive default | `allowedPackages` | MIT |
| `revive:duplicated-imports` | revive | same path imported twice | FILE | SYNTAX | port | S | — | — | MIT |
| `revive:early-return` | revive | `if c { … } else { return }` -> guard | STATEMENT | SYNTAX | port | M | — | `preserveScope`, `allowJump` | MIT |
| `revive:empty-block` | revive | empty block | STATEMENT | SYNTAX | port | S | revive default | — | MIT |
| `revive:empty-lines` | revive | leading/trailing blank lines in a block | STATEMENT | SYNTAX | skip: formatter territory (gofumpt) | S | — | — | MIT |
| `revive:epoch-naming` | revive | epoch-valued variable lacks a unit suffix | STATEMENT | TYPES | port | S | — | — | MIT |
| `revive:enforce-map-style` | revive | `map[K]V{}` vs `make` | EXPRESSION | SYNTAX | port | S | — | `any`/`literal`/`make` | MIT |
| `revive:enforce-repeated-arg-type-style` | revive | `a int, b int` vs `a, b int` | FUNCTION | SYNTAX | port | S | — | `short`/`full` | MIT |
| `revive:enforce-slice-style` | revive | `[]T{}` vs `make` vs `nil` | EXPRESSION | SYNTAX | port | S | — | `any`/`literal`/`make`/`nil` | MIT |
| `revive:enforce-switch-style` | revive | `default` presence/position | STATEMENT | SYNTAX | port | S | — | `allowNoDefault`, `allowDefaultNotLast` | MIT |
| `revive:error-naming` | revive | error variable not `errX`/`ErrX` | TYPE_SPEC | TYPES | port | S | revive default | — | MIT |
| `revive:error-return` | revive | `error` not the last result | FUNCTION | TYPES | port | S | revive default | — | MIT |
| `revive:error-strings` | revive | error string capitalized / punctuated | CALL | TYPES | port | S | revive default | extra functions | MIT |
| `revive:errorf` | revive | `errors.New(fmt.Sprintf(…))` | CALL | TYPES | port | S | revive default | — | MIT |
| `revive:exported` | revive | exported symbol without doc / stutter / malformed doc | FILE | SYNTAX | native: `GoDocComment` | M | revive default | `checkPrivateReceivers`, `disableStutteringCheck`, `sayRepetitiveInsteadOfStutters` | MIT |
| `revive:file-header` | revive | file lacks configured header | FILE | SYNTAX | port | S | — | header regexp | MIT |
| `revive:file-length-limit` | revive | file too long | FILE | SYNTAX | port | S | — | `max`, `skipComments`, `skipBlankLines` | MIT |
| `revive:filename-format` | revive | file name does not match a pattern | FILE | SYNTAX | port | S | — | regexp | MIT |
| `revive:flag-parameter` | revive | `bool` parameter used as a control flag | FUNCTION | SYNTAX | port | S | — | — | MIT |
| `revive:forbidden-call-in-wg-go` | revive | `wg.Done()` / panic inside `WaitGroup.Go` | CALL | TYPES | port | S | — | — | MIT |
| `revive:function-length` | revive | too many statements / lines | FUNCTION | SYNTAX | port | S | — | max statements, max lines | MIT |
| `revive:function-result-limit` | revive | too many results | FUNCTION | SYNTAX | port | S | — | max (3) | MIT |
| `revive:get-return` | revive | `GetX` function with no result | FUNCTION | SYNTAX | port | S | — | — | MIT |
| `revive:identical-branches` | revive | `if` and `else` bodies identical | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:identical-ifelseif-branches` | revive | two branches of an `else if` chain identical | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:identical-ifelseif-conditions` | revive | repeated condition in an `else if` chain | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:identical-switch-branches` | revive | identical case bodies | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:identical-switch-conditions` | revive | repeated case expression | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:if-return` | revive | `if err := f(); err != nil { return err }; return nil` | STATEMENT | TYPES | port | S | — | — | MIT |
| `revive:import-alias-naming` | revive | alias does not match a pattern | FILE | SYNTAX | port | S | — | allow / deny regexps | MIT |
| `revive:import-shadowing` | revive | identifier shadows an import | FUNCTION | TYPES | port | S | — | — | MIT |
| `revive:imports-blocklist` | revive | blocked import path | FILE | SYNTAX | port | S | — | path globs | MIT |
| `revive:increment-decrement` | revive | `x += 1` -> `x++` | STATEMENT | SYNTAX | port | S | revive default | — | MIT |
| `revive:indent-error-flow` | revive | `else` after `return` in an error branch | STATEMENT | SYNTAX | port | S | revive default | `preserveScope` | MIT |
| `revive:inefficient-map-lookup` | revive | key looked up again after existence check | STATEMENT | TYPES | port | S | — | — | MIT |
| `revive:line-length-limit` | revive | line too long | FILE | SYNTAX | port | S | — | max (80) | MIT |
| `revive:marshal-receiver` | revive | `MarshalJSON` on a pointer receiver etc. | FUNCTION | TYPES | port | S | — | — | MIT |
| `revive:max-control-nesting` | revive | nesting too deep | FUNCTION | SYNTAX | port | S | — | max (5) | MIT |
| `revive:max-public-structs` | revive | too many exported structs in a file | FILE | SYNTAX | port | S | — | max (5) | MIT |
| `revive:modifies-parameter` | revive | assignment to a parameter | FUNCTION | TYPES | port | S | — | — | MIT |
| `revive:modifies-value-receiver` | revive | assignment to a value receiver's field | FUNCTION | TYPES | port-approx | S | — | — | MIT |
| `revive:multiline-if-init` | revive | multi-line `if` init statement | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:nested-structs` | revive | anonymous struct inside a struct | TYPE_SPEC | SYNTAX | port | S | — | — | MIT |
| `revive:optimize-operands-order` | revive | expensive operand before cheap one in `&&`/`\|\|` | EXPRESSION | TYPES | port | M | — | — | MIT |
| `revive:package-comments` | revive | missing / malformed package comment | PACKAGE | SYNTAX | port | S | revive default | — | MIT |
| `revive:package-naming` | revive | package name not lowercase / `util` etc. | FILE | SYNTAX | port | S | — | — | MIT |
| `revive:package-directory-mismatch` | revive | package name differs from directory | FILE | SYNTAX | port | S | — | ignore patterns | MIT |
| `revive:range-val-address` | revive | `&v` of a range variable (go < 1.22) | EXPRESSION | TYPES | port | S | — | — | MIT |
| `revive:range-val-in-closure` | revive | range variable in a closure (go < 1.22) | EXPRESSION | TYPES | native: `GoLoopClosure` | S | — | — | MIT |
| `revive:range` | revive | `for i, _ := range` | STATEMENT | SYNTAX | port | S | revive default | — | MIT |
| `revive:receiver-naming` | revive | `self`/`this`, inconsistent receiver names | PACKAGE | SYNTAX | port | S | revive default | `maxLength` | MIT |
| `revive:redefines-builtin-id` | revive | local name shadows a builtin | STATEMENT | SYNTAX | port | S | revive default | — | MIT |
| `revive:redundant-build-tag` | revive | `+build` next to `//go:build` | FILE | SYNTAX | native (partial): GoBuildConstraint (`+build is deprecated`) | S | — | — | MIT |
| `revive:redundant-import-alias` | revive | alias equal to the package name | FILE | TYPES | port | S | — | — | MIT |
| `revive:redundant-test-main-exit` | revive | `os.Exit(m.Run())` in TestMain (go >= 1.15) | FUNCTION | TYPES | port | S | — | — | MIT |
| `revive:string-format` | revive | string literal violates a configured regexp per call | CALL | TYPES | port | M | — | rule list | MIT |
| `revive:string-of-int` | revive | `string(int)` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `revive:struct-tag` | revive | invalid / unknown struct tag options | TYPE_SPEC | TYPES | native (partial): GoStructTag (syntax; option validation to port) | M | — | user-defined tags | MIT |
| `revive:superfluous-else` | revive | `else` after `break`/`continue`/`goto` | STATEMENT | SYNTAX | port | S | revive default | `preserveScope` | MIT |
| `revive:time-date` | revive | `time.Date` with out-of-range or octal components | CALL | TYPES | port | S | — | — | MIT |
| `revive:time-equal` | revive | `t1 == t2` on `time.Time` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `revive:time-naming` | revive | `time.Duration` named `…Secs` | TYPE_SPEC | TYPES | port | S | revive default | — | MIT |
| `revive:unchecked-type-assertion` | revive | `x.(T)` without `ok` | EXPRESSION | TYPES | port | S | — | `acceptIgnoredAssertionResult` | MIT |
| `revive:unconditional-recursion` | revive | recursion without a base case | FUNCTION | TYPES + FLOW | port | M | — | — | MIT |
| `revive:unexported-naming` | revive | unexported symbol named with a capital after `_` etc. | FILE | SYNTAX | port | S | — | — | MIT |
| `revive:unexported-return` | revive | exported function returns an unexported type | FUNCTION | TYPES | port | S | revive default | — | MIT |
| `revive:unhandled-error` | revive | error result dropped | CALL | TYPES | native: `GoUncheckedError` | S | — | function list to ignore | MIT |
| `revive:unnecessary-if` | revive | `if c { x = true } else { x = false }` | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:unnecessary-format` | revive | `Printf` without verbs | CALL | TYPES | port | S | — | — | MIT |
| `revive:unnecessary-stmt` | revive | `switch` with one case, trailing `return` | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:unreachable-code` | revive | code after `return`/`panic` | FUNCTION | FLOW | native: `GoUnreachableCode` | S | revive default | — | MIT |
| `revive:unsecure-url-scheme` | revive | `http://` / `ws://` URL literal | EXPRESSION | SYNTAX | port | S | — | — | MIT |
| `revive:unused-parameter` | revive | parameter never read | FUNCTION | TYPES | native: `GoUnusedParameter` | S | revive default | `allowRegex` | MIT |
| `revive:unused-receiver` | revive | receiver never used | FUNCTION | TYPES | port | S | — | `allowRegex` | MIT |
| `revive:use-any` | revive | `interface{}` -> `any` | EXPRESSION | SYNTAX | port | S | — | — | MIT |
| `revive:use-errors-new` | revive | `fmt.Errorf` without verbs -> `errors.New` | CALL | TYPES | port | S | — | — | MIT |
| `revive:use-fmt-print` | revive | `fmt.Printf` without verbs | CALL | TYPES | port | S | — | — | MIT |
| `revive:use-slices-concat` | revive | nested appends -> `slices.Concat` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `revive:use-slices-sort` | revive | `sort.Ints` -> `slices.Sort` | CALL | TYPES | port | S | — | — | MIT |
| `revive:use-waitgroup-go` | revive | `wg.Add(1); go func(){ defer wg.Done() }` -> `wg.Go` | STATEMENT | TYPES | port | M | — | — | MIT |
| `revive:useless-break` | revive | `break` at the end of a case | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:useless-fallthrough` | revive | empty case + `fallthrough` | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `revive:var-declaration` | revive | `var x int = 0` | STATEMENT | TYPES | port | S | revive default | — | MIT |
| `revive:var-naming` | revive | MixedCaps, initialisms (`Id` -> `ID`), `_` in names | FILE | SYNTAX | port | M | revive default | allow list, deny list, `skipPackageNameChecks` | MIT |
| `revive:waitgroup-by-value` | revive | `sync.WaitGroup` parameter by value | FUNCTION | TYPES | native (partial): GoCopyLocks (copy of a WaitGroup) | S | — | — | MIT |

## gocritic checkers

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `gocritic:appendAssign` | gocritic | `x = append(y, …)` with x != y | STATEMENT | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:appendCombine` | gocritic | consecutive appends to one slice | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:argOrder` | gocritic | `strings.HasPrefix("prefix", s)` | CALL | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:assignOp` | gocritic | `x = x + 1` -> `x += 1` | STATEMENT | SYNTAX | port | S | gocritic default | — | MIT |
| `gocritic:badCall` | gocritic | `strings.Replace(s, a, b, 0)`, `filepath.Join(x)` | CALL | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:badCond` | gocritic | `x < 0 && x > 10` | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:badLock` | gocritic | `mu.Lock(); defer mu.Lock()` | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:badRegexp` | gocritic | suspicious regexp (`[a-z]\|[a-z]`, unescaped `.`) | CALL | TYPES | port | M | — | — | MIT |
| `gocritic:badSorting` | gocritic | `x = sort.StringSlice(x)` | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:badSyncOnceFunc` | gocritic | `sync.OnceFunc(f)()` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:boolExprSimplify` | gocritic | `!(a == b)` -> `a != b` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `gocritic:builtinShadow` | gocritic | local shadows a builtin | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `gocritic:builtinShadowDecl` | gocritic | declaration shadows a builtin | FILE | SYNTAX | port | S | — | — | MIT |
| `gocritic:captLocal` | gocritic | capitalized local / parameter name | FUNCTION | SYNTAX | port | S | gocritic default | `paramsOnly` | MIT |
| `gocritic:caseOrder` | gocritic | type-switch case subsumed by an earlier one | STATEMENT | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:codegenComment` | gocritic | malformed `// Code generated … DO NOT EDIT.` | FILE | SYNTAX | port | S | gocritic default | — | MIT |
| `gocritic:commentFormatting` | gocritic | `//comment` without space | FILE | SYNTAX | port | S | gocritic default | — | MIT |
| `gocritic:commentedOutCode` | gocritic | commented-out code | FILE | SYNTAX | skip: noisy, needs parsing every comment | M | — | `minLength` | MIT |
| `gocritic:commentedOutImport` | gocritic | commented-out import | FILE | SYNTAX | port | S | — | — | MIT |
| `gocritic:defaultCaseOrder` | gocritic | `default` not first or last | STATEMENT | SYNTAX | port | S | gocritic default | — | MIT |
| `gocritic:deferInLoop` | gocritic | `defer` in a loop | STATEMENT | SYNTAX | native: `GoDeferInLoop` | S | — | — | MIT |
| `gocritic:deferUnlambda` | gocritic | `defer func() { f() }()` -> `defer f()` | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:deprecatedComment` | gocritic | malformed `Deprecated:` comment | FILE | SYNTAX | port | S | gocritic default | — | MIT |
| `gocritic:docStub` | gocritic | stub doc comment (`// Foo ...`) | FILE | SYNTAX | port | S | — | — | MIT |
| `gocritic:dupArg` | gocritic | `copy(x, x)`, `strings.Contains(x, x)` | CALL | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:dupBranchBody` | gocritic | identical `if`/`else` bodies | STATEMENT | SYNTAX | port | S | gocritic default | — | MIT |
| `gocritic:dupCase` | gocritic | duplicate case expression | STATEMENT | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:dupImport` | gocritic | package imported twice | FILE | SYNTAX | port | S | — | — | MIT |
| `gocritic:dupSubExpr` | gocritic | `a == a`, `x - x` | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:dynamicFmtString` | gocritic | `fmt.Errorf(msg)` with a non-constant format | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:elseif` | gocritic | `else { if … }` -> `else if` | STATEMENT | SYNTAX | port | S | gocritic default | `skipBalanced` | MIT |
| `gocritic:emptyDecl` | gocritic | `var ()` | FILE | SYNTAX | port | S | — | — | MIT |
| `gocritic:emptyFallthrough` | gocritic | empty case with `fallthrough` | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `gocritic:emptyStringTest` | gocritic | `len(s) == 0` -> `s == ""` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `gocritic:equalFold` | gocritic | `ToLower(a) == ToLower(b)` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `gocritic:evalOrder` | gocritic | `return x, f(&x)` relies on evaluation order | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:exitAfterDefer` | gocritic | `log.Fatal` after `defer` | FUNCTION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:exposedSyncMutex` | gocritic | exported struct embeds `sync.Mutex` | TYPE_SPEC | TYPES | port | S | — | — | MIT |
| `gocritic:externalErrorReassign` | gocritic | `io.EOF = nil` | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:filepathJoin` | gocritic | `filepath.Join("a/b", c)` with separators | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:flagDeref` | gocritic | `*flag.Bool(…)` | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:flagName` | gocritic | flag name with spaces/odd characters | CALL | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:hexLiteral` | gocritic | mixed-case hex literal | EXPRESSION | SYNTAX | port | S | — | — | MIT |
| `gocritic:httpNoBody` | gocritic | `nil` body -> `http.NoBody` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:hugeParam` | gocritic | big struct passed by value | FUNCTION | TYPES | port | S | — | `sizeThreshold` (80) | MIT |
| `gocritic:ifElseChain` | gocritic | `if-else if` chain -> switch | STATEMENT | SYNTAX | port | S | gocritic default | `minThreshold` | MIT |
| `gocritic:importShadow` | gocritic | local shadows an imported package | FUNCTION | TYPES | port | S | — | — | MIT |
| `gocritic:indexAlloc` | gocritic | `strings.Index(string(b), …)` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:initClause` | gocritic | `if` init without effect | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `gocritic:mapKey` | gocritic | map literal key with spaces | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:methodExprCall` | gocritic | `T.Method(x)` -> `x.Method()` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:nestingReduce` | gocritic | invert `if` to reduce nesting in a loop | STATEMENT | SYNTAX | port | S | — | `bodyWidth` | MIT |
| `gocritic:newDeref` | gocritic | `*new(T)` | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:nilValReturn` | gocritic | `if err == nil { return err }` | STATEMENT | TYPES | native (partial): GoErrNilReturned | S | — | — | MIT |
| `gocritic:octalLiteral` | gocritic | `0644` -> `0o644` | EXPRESSION | SYNTAX | port | S | — | — | MIT |
| `gocritic:offBy1` | gocritic | `s[len(s)]` | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:paramTypeCombine` | gocritic | `a int, b int` -> `a, b int` | FUNCTION | SYNTAX | port | S | — | — | MIT |
| `gocritic:preferDecodeRune` | gocritic | `[]rune(s)[0]` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `gocritic:preferFilepathJoin` | gocritic | `a + "/" + b` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `gocritic:preferFprint` | gocritic | `w.Write([]byte(fmt.Sprintf(…)))` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:preferStringWriter` | gocritic | `w.Write([]byte(s))` -> `WriteString` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:preferWriteByte` | gocritic | `WriteRune('a')` -> `WriteByte` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:ptrToRefParam` | gocritic | pointer to map/chan/interface parameter | FUNCTION | TYPES | port | S | — | — | MIT |
| `gocritic:rangeAppendAll` | gocritic | `for range x { y = append(y, x...) }` | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:rangeExprCopy` | gocritic | ranging over a large array copies it | STATEMENT | TYPES | port | S | — | `sizeThreshold`, `skipTestFuncs` | MIT |
| `gocritic:rangeValCopy` | gocritic | large range value copied per iteration | STATEMENT | TYPES | port | S | — | `sizeThreshold`, `skipTestFuncs` | MIT |
| `gocritic:redundantSprint` | gocritic | `fmt.Sprint(x)` of a `Stringer`/string | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:regexpMust` | gocritic | `regexp.Compile` of a constant -> `MustCompile` | CALL | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:regexpPattern` | gocritic | regexp missing escapes for `.` in domains | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:regexpSimplify` | gocritic | simplifiable regexp | CALL | TYPES | port | M | — | — | MIT |
| `gocritic:returnAfterHttpError` | gocritic | `http.Error` not followed by `return` | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:ruleguard` | gocritic | user rules (ruleguard DSL) | FILE | TYPES | skip: needs ruleguard engine | L | — | `rules` | MIT |
| `gocritic:singleCaseSwitch` | gocritic | switch with one case -> `if` | STATEMENT | SYNTAX | port | S | gocritic default | — | MIT |
| `gocritic:sliceClear` | gocritic | zeroing loop -> `clear` | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:sloppyLen` | gocritic | `len(x) <= 0` | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:sloppyReassign` | gocritic | `if err = f(); err != nil` with outer err | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:sloppyTypeAssert` | gocritic | assertion to an interface it already implements | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:sortSlice` | gocritic | `sort.Slice(x, func(i, j) bool { return y[i] < y[j] })` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:sprintfQuotedString` | gocritic | `"'%s'"` -> `%q` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:sqlQuery` | gocritic | `db.Query` result ignored where `Exec` fits | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:stringConcatSimplify` | gocritic | `strings.Join([]string{a, b}, "")` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:stringXbytes` | gocritic | `copy(b, []byte(s))` | CALL | TYPES | port | S | — | — | MIT |
| `gocritic:stringsCompare` | gocritic | `strings.Compare(a, b) == 0` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `gocritic:switchTrue` | gocritic | `switch true {` | STATEMENT | SYNTAX | port | S | gocritic default | — | MIT |
| `gocritic:syncMapLoadAndDelete` | gocritic | `Load` + `Delete` -> `LoadAndDelete` | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:timeExprSimplify` | gocritic | `t.Unix() * 1000` -> `UnixMilli` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `gocritic:todoCommentWithoutDetail` | gocritic | bare `// TODO` | FILE | SYNTAX | port | S | — | — | MIT |
| `gocritic:tooManyResultsChecker` | gocritic | too many results | FUNCTION | SYNTAX | port | S | — | `maxResults` | MIT |
| `gocritic:truncateCmp` | gocritic | `int32(x) < y` truncates before comparing | EXPRESSION | TYPES | port | S | — | `skipArchDependent` | MIT |
| `gocritic:typeAssertChain` | gocritic | repeated assertions -> type switch | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:typeDefFirst` | gocritic | type declared after its methods | FILE | SYNTAX | port | S | — | — | MIT |
| `gocritic:typeSwitchVar` | gocritic | type switch without a bound variable that re-asserts | STATEMENT | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:typeUnparen` | gocritic | `[](T)` redundant parentheses | EXPRESSION | SYNTAX | port | S | — | — | MIT |
| `gocritic:uncheckedInlineErr` | gocritic | `if err := f(); f2() != nil` | STATEMENT | TYPES | port | S | — | — | MIT |
| `gocritic:underef` | gocritic | `(*p).f` -> `p.f` | EXPRESSION | TYPES | port | S | gocritic default | `skipRecvDeref` | MIT |
| `gocritic:unlabelStmt` | gocritic | redundant label on `break`/`continue` | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `gocritic:unlambda` | gocritic | `func(x int) int { return f(x) }` -> `f` | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:unnamedResult` | gocritic | several unnamed results of the same type | FUNCTION | TYPES | port | S | — | `checkExported` | MIT |
| `gocritic:unnecessaryBlock` | gocritic | `{ … }` block without purpose | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `gocritic:unnecessaryDefer` | gocritic | `defer f()` right before `return` | STATEMENT | SYNTAX | port | S | — | — | MIT |
| `gocritic:unslice` | gocritic | `s[:]` of a slice | EXPRESSION | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:valSwap` | gocritic | swap via temporary -> `a, b = b, a` | STATEMENT | SYNTAX | port | S | gocritic default | — | MIT |
| `gocritic:weakCond` | gocritic | `len(x) > 0 \|\| x[0] == 1` | EXPRESSION | TYPES | port | S | — | — | MIT |
| `gocritic:whyNoLint` | gocritic | `//nolint` without explanation | FILE | SYNTAX | port | S | — | — | MIT |
| `gocritic:wrapperFunc` | gocritic | `strings.SplitN(s, sep, -1)` -> `Split` | CALL | TYPES | port | S | gocritic default | — | MIT |
| `gocritic:yodaStyleExpr` | gocritic | `nil != x` | EXPRESSION | SYNTAX | port | S | — | — | MIT |

## gosec rules

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `gosec:G101` | gosec | hard-coded credentials in names/values | EXPRESSION | SYNTAX | port | M | — | `pattern`, `ignore_entropy`, `entropy_threshold` | Apache-2.0 |
| `gosec:G102` | gosec | listening on all interfaces (`0.0.0.0`, `:port`) | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G103` | gosec | use of `unsafe` | EXPRESSION | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G104` | gosec | unchecked error | CALL | TYPES | native: `GoUncheckedError` | S | — | `G104` audit functions | Apache-2.0 |
| `gosec:G106` | gosec | `ssh.InsecureIgnoreHostKey` | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G107` | gosec | HTTP request URL from a variable | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G108` | gosec | `net/http/pprof` exposed (blank import) | FILE | SYNTAX | port | S | — | — | Apache-2.0 |
| `gosec:G109` | gosec | `strconv.Atoi` result converted to int16/int32 | EXPRESSION | TYPES + FLOW | port | M | — | — | Apache-2.0 |
| `gosec:G110` | gosec | decompression bomb (`io.Copy` from a decompressor) | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G111` | gosec | `http.Dir("/")` directory traversal | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G112` | gosec | `http.Server` without `ReadHeaderTimeout` (Slowloris) | EXPRESSION | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G114` | gosec | `http.ListenAndServe` without timeouts | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G115` | gosec | integer overflow on conversion | EXPRESSION | TYPES + FLOW | port-approx | L | — | — | Apache-2.0 |
| `gosec:G201` | gosec | SQL built with `fmt.Sprintf` | CALL | TYPES | port | M | — | — | Apache-2.0 |
| `gosec:G202` | gosec | SQL built with string concatenation | CALL | TYPES | port | M | — | — | Apache-2.0 |
| `gosec:G203` | gosec | unescaped data in `template.HTML` & co. | EXPRESSION | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G204` | gosec | subprocess launched with variable arguments | CALL | TYPES | port | M | — | — | Apache-2.0 |
| `gosec:G301` | gosec | `os.Mkdir` with permissions over 0750 | CALL | TYPES | port | S | — | `G301` mode | Apache-2.0 |
| `gosec:G302` | gosec | `os.OpenFile`/`Chmod` with permissions over 0600 | CALL | TYPES | port | S | — | `G302` mode | Apache-2.0 |
| `gosec:G303` | gosec | predictable temp file name | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G304` | gosec | file path from a variable | CALL | TYPES | port | M | — | — | Apache-2.0 |
| `gosec:G305` | gosec | zip/tar slip (archive entry path joined) | CALL | TYPES + FLOW | port-approx | M | — | — | Apache-2.0 |
| `gosec:G306` | gosec | `os.WriteFile` with permissions over 0600 | CALL | TYPES | port | S | — | `G306` mode | Apache-2.0 |
| `gosec:G307` | gosec | `defer f.Close()` on a writable file (error dropped) | STATEMENT | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G401` | gosec | weak hash `md5`/`sha1` used | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G402` | gosec | TLS `InsecureSkipVerify` / low `MinVersion` | EXPRESSION | TYPES | port | M | — | — | Apache-2.0 |
| `gosec:G403` | gosec | RSA key shorter than 2048 bits | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G404` | gosec | `math/rand` for security | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G405` | gosec | DES / RC4 cipher | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G406` | gosec | MD4 / RIPEMD160 | CALL | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G407` | gosec | hard-coded IV / nonce | CALL | TYPES + FLOW | port-approx | M | — | — | Apache-2.0 |
| `gosec:G501` | gosec | import `crypto/md5` | FILE | SYNTAX | port | S | — | — | Apache-2.0 |
| `gosec:G502` | gosec | import `crypto/des` | FILE | SYNTAX | port | S | — | — | Apache-2.0 |
| `gosec:G503` | gosec | import `crypto/rc4` | FILE | SYNTAX | port | S | — | — | Apache-2.0 |
| `gosec:G504` | gosec | import `net/http/cgi` | FILE | SYNTAX | port | S | — | — | Apache-2.0 |
| `gosec:G505` | gosec | import `crypto/sha1` | FILE | SYNTAX | port | S | — | — | Apache-2.0 |
| `gosec:G506` | gosec | import `golang.org/x/crypto/md4` | FILE | SYNTAX | port | S | — | — | Apache-2.0 |
| `gosec:G507` | gosec | import `golang.org/x/crypto/ripemd160` | FILE | SYNTAX | port | S | — | — | Apache-2.0 |
| `gosec:G601` | gosec | implicit memory aliasing of a range variable (go < 1.22) | EXPRESSION | TYPES | port | S | — | — | Apache-2.0 |
| `gosec:G602` | gosec | slice index out of range after a length check | EXPRESSION | TYPES + FLOW | port-approx | M | — | — | Apache-2.0 |

## testifylint checkers

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `testifylint:blank-import` | testifylint | blank import of `testify` packages | FILE | SYNTAX | port | S | — | — | MIT† |
| `testifylint:bool-compare` | testifylint | `assert.Equal(t, true, x)` -> `True` | CALL | TYPES | port | S | — | `ignore-custom-types` | MIT† |
| `testifylint:compares` | testifylint | `assert.True(t, a == b)` -> `Equal` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:contains` | testifylint | `assert.True(t, strings.Contains(…))` -> `Contains` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:empty` | testifylint | `assert.Len(t, x, 0)` -> `Empty` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:encoded-compare` | testifylint | JSON/YAML strings compared with `Equal` -> `JSONEq` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:equal-values` | testifylint | `EqualValues` on equal types -> `Equal` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:error-is-as` | testifylint | `assert.Error(t, err, ErrX)` -> `ErrorIs` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:error-nil` | testifylint | `assert.Nil(t, err)` -> `NoError` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:expected-actual` | testifylint | expected and actual swapped | CALL | TYPES | port | S | — | `pattern` | MIT† |
| `testifylint:float-compare` | testifylint | `Equal` on floats -> `InEpsilon` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:formatter` | testifylint | `assert.Equalf` misuse / format args | CALL | TYPES | port | M | — | `check-format-string`, `require-f-funcs` | MIT† |
| `testifylint:go-require` | testifylint | `require` in a goroutine | CALL | TYPES | port | S | — | `ignore-http-handlers` | MIT† |
| `testifylint:len` | testifylint | `assert.Equal(t, 3, len(x))` -> `Len` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:negative-positive` | testifylint | `assert.Less(t, x, 0)` -> `Negative` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:nil-compare` | testifylint | `assert.Equal(t, nil, x)` -> `Nil` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:regexp` | testifylint | `assert.Regexp(t, regexp.MustCompile(…))` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:require-error` | testifylint | error assertions should be `require` | CALL | TYPES | port | S | — | `fn-pattern` | MIT† |
| `testifylint:suite-broken-parallel` | testifylint | `t.Parallel` in a suite | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:suite-dont-use-pkg` | testifylint | `assert.X(s.T(), …)` -> `s.X(…)` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:suite-extra-assert-call` | testifylint | `s.Assert().X` vs `s.X` | CALL | TYPES | port | S | — | `mode` | MIT† |
| `testifylint:suite-method-signature` | testifylint | suite method with a wrong signature | FUNCTION | TYPES | port | S | — | — | MIT† |
| `testifylint:suite-subtest-run` | testifylint | `t.Run` inside a suite -> `s.Run` | CALL | TYPES | port | S | — | — | MIT† |
| `testifylint:suite-thelper` | testifylint | suite helper without `s.T().Helper()` | FUNCTION | TYPES | port | S | — | — | MIT† |
| `testifylint:useless-assert` | testifylint | `assert.Equal(t, x, x)` | CALL | TYPES | port | S | — | — | MIT† |

## Popular linters (one rule each, or a few sub-checks)

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `errorlint:errorf` | errorlint | `fmt.Errorf` with an error argument not wrapped with `%w` | CALL | TYPES | port | S | — | `errorf`, `errorf-multi` | MIT† |
| `errorlint:asserts` | errorlint | type assertion / type switch on an error -> `errors.As` | EXPRESSION | TYPES | port | S | — | `asserts`, allowed list | MIT† |
| `errorlint:comparison` | errorlint | `err == ErrX` -> `errors.Is` | EXPRESSION | TYPES | native: `GoErrorsPackage` | S | — | `comparison`, allowed list | MIT† |
| `bodyclose` | bodyclose | `resp.Body` not closed | FUNCTION | TYPES + FLOW | native: `GoBodyNotClosed` | M | — | — | MIT† |
| `nilerr` | nilerr | `return nil` while err != nil (and the reverse) | FUNCTION | TYPES + FLOW | native: `GoNilErrorReturn` | M | — | — | MIT† |
| `nilnil` | nilnil | `return nil, nil` for a nilable result with error | STATEMENT | TYPES | native: `GoNilValueNilError` | S | — | `checked-types`, `detect-opposite` | MIT† |
| `rowserrcheck` | rowserrcheck | `rows.Err()` not checked after iterating `*sql.Rows` | FUNCTION | TYPES + FLOW | port | M | — | `packages` (sqlx, …) | MIT† |
| `sqlclosecheck` | sqlclosecheck | `*sql.Rows` / `*sql.Stmt` not closed | FUNCTION | TYPES + FLOW | native (partial): GoRowsNotClosed (Rows; Stmt to add) | M | — | — | MIT† |
| `noctx` | noctx | HTTP request / `sql` call without a context (`http.Get`, `db.Query`) | CALL | TYPES | port | S | — | — | MIT† |
| `misspell` | misspell | common English misspellings in comments/strings | FILE | SYNTAX | skip: covered by the IDE spellchecker (`GoSpellcheckingStrategy` + Typo) | S | — | `locale`, `ignore-rules` | MIT† |
| `unconvert` | unconvert | redundant type conversion `T(x)` where x is T | EXPRESSION | TYPES | port | S | — | `fast-math`, `safe` | BSD-3-Clause† |
| `unparam` | unparam | parameter always receives the same value / result always the same | FUNCTION | TYPES + PROJECT_INDEX | port-approx | L | — | `check-exported` | BSD-3-Clause† |
| `prealloc` | prealloc | slice could be preallocated before an append loop | FUNCTION | TYPES | port | S | — | `simple`, `range-loops`, `for-loops` | MIT† |
| `dupword` | dupword | repeated word in comments/strings (`the the`) | FILE | SYNTAX | port | S | — | `keywords`, `ignore` | MIT† |
| `goconst` | goconst | repeated string literal could be a constant | PACKAGE | SYNTAX | port | M | — | `min-len`, `min-occurrences`, `ignore-tests`, `match-constant` | MIT† |
| `gocyclo` | gocyclo | cyclomatic complexity over the limit | FUNCTION | SYNTAX | port | S | — | `min-complexity` (30) | BSD-3-Clause† |
| `cyclop` | cyclop | cyclomatic complexity of functions / package average | FUNCTION | SYNTAX | port | S | — | `max-complexity`, `package-average` | MIT† |
| `funlen` | funlen | function too long (lines / statements) | FUNCTION | SYNTAX | port | S | — | `lines`, `statements`, `ignore-comments` | MIT† |
| `nestif` | nestif | deeply nested `if` complexity | FUNCTION | SYNTAX | port | S | — | `min-complexity` (5) | BSD-2-Clause† |
| `wastedassign` | wastedassign | assignment never used / reassigned before use | FUNCTION | FLOW | native (partial): GoIneffectualAssignment (same analysis; wastedassign also flags assignments before `return`) | S | — | — | MIT† |
| `makezero` | makezero | `append` to a slice created with non-zero length | FUNCTION | TYPES + FLOW | port | S | — | `always` | MIT† |
| `exhaustive` | exhaustive | `switch` over an enum misses members | STATEMENT | TYPES + PROJECT_INDEX | native: `GoExhaustiveSwitch` | M | — | `default-signifies-exhaustive`, `check: [switch, map]`, `ignore-enum-members` | BSD-2-Clause† |
| `forcetypeassert` | forcetypeassert | `x.(T)` without `ok` | EXPRESSION | TYPES | port | S | — | — | MIT† |
| `contextcheck` | contextcheck | function uses a non-inherited context | FUNCTION | TYPES + FLOW | native: `GoContextNotPropagated` | M | — | — | Apache-2.0† |
| `containedctx` | containedctx | struct field of type `context.Context` | TYPE_SPEC | TYPES | port | S | — | — | MIT† |
| `errname` | errname | error types named `XxxError`, sentinels `ErrXxx` | TYPE_SPEC | TYPES | port | S | — | — | MIT† |
| `nakedret` | nakedret | naked return in a function longer than N lines | FUNCTION | SYNTAX | port | S | — | `max-func-lines` (30) | MIT† |
| `nolintlint` | nolintlint | malformed / unused / unexplained `//nolint` | FILE | SYNTAX | port | M | — | `require-explanation`, `require-specific`, `allow-unused`, `allow-no-explanation` | GPL-3.0 (golangci-lint) |
| `predeclared` | predeclared | declaration shadows a predeclared identifier | FILE | SYNTAX | port | S | — | `ignore`, `qualified-name` | BSD-2-Clause† |
| `usestdlibvars` | usestdlibvars | literal where a stdlib constant exists (`"GET"` -> `http.MethodGet`, 200 -> `http.StatusOK`) | EXPRESSION | TYPES | port | S | — | `http-method`, `http-status-code`, `time-month`, … | MIT† |
| `perfsprint` | perfsprint | `fmt.Sprintf` replaceable by `strconv` / concatenation / `errors.New` | CALL | TYPES | port | S | — | `int-conversion`, `err-error`, `errorf`, `sprintf1`, `strconcat` | MIT† |
| `copyloopvar` | copyloopvar | `v := v` copy of a loop variable is redundant (go >= 1.22) | STATEMENT | TYPES | port | S | — | `check-alias` | MIT† |
| `intrange` | intrange | `for i := 0; i < n; i++` -> `for i := range n` | STATEMENT | TYPES | port | S | — | — | MIT† |
| `thelper` | thelper | test helper without `t.Helper()` first / `t` not first parameter | FUNCTION | TYPES | port | S | — | `test.first`, `test.name`, `test.begin`, same for `benchmark`, `tb`, `fuzz` | MIT† |
| `tparallel` | tparallel | `t.Parallel` in subtests but not the top-level test (or vice versa) | FUNCTION | TYPES | port | S | — | — | MIT† |
| `paralleltest` | paralleltest | test does not call `t.Parallel` | FUNCTION | TYPES | port | S | — | `ignore-missing`, `ignore-missing-subtests` | MIT† |

## Formatters

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `gofmt` | gofmt | file is not gofmt-ed | FILE | SYNTAX | formatter — not a check | — | — | `simplify`, `rewrite-rules` | BSD-3-Clause (Go) |
| `gofumpt` | gofumpt | file is not gofumpt-ed | FILE | SYNTAX | formatter — not a check | — | — | `extra-rules`, `module-path` | BSD-3-Clause† |
| `goimports` | goimports | imports not goimports-ed | FILE | SYNTAX | formatter — not a check | — | — | `local-prefixes` | BSD-3-Clause (x/tools) |
| `gci` | gci | import order by sections | FILE | SYNTAX | formatter — not a check | — | — | `sections`, `custom-order` | BSD-3-Clause† |
| `golines` | golines | lines longer than the limit are wrapped | FILE | SYNTAX | formatter — not a check | — | — | `max-len` | MIT† |

## GoLand inspections

| Rule id | Linter | What | Scope | Needs | Status | Size | Default | Options | License |
|---|---|---|---|---|---|---|---|---|---|
| `goland:GoAssignmentToReceiver` | goland | assignment to the receiver variable changes only the local copy | STATEMENT | SYNTAX | port | S | — | — | — (behavior only; closed-source product) |
| `goland:GoBoolExpressions` | goland | redundant or contradictory part of a boolean expression (`n > 10 && n > 5`) | EXPRESSION | TYPES | same-as: `govet:bools` | — | — | range subsumption is also `gocritic:badCond` (B13): check both cover it | — (behavior only; closed-source product) |
| `goland:GoBuildTag` | goland | malformed or misplaced build constraint | FILE | SYNTAX | native: `GoBuildConstraint` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoCommentLeadingSpace` | goland | no space after `//` | FILE | SYNTAX | same-as: `gocritic:commentFormatting` | — | — | alias also `revive:comment-spacings` | — (behavior only; closed-source product) |
| `goland:GoCommentStart` | goland | doc comment of an exported declaration does not start with its name | TYPE_SPEC | SYNTAX | native: `GoDocComment` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoContextTodo` | goland | `context.TODO()` left in production code | CALL | TYPES | native (partial): GoContextPlacement (only `TODO`/`Background` passed where the function has a ctx parameter; every `context.TODO()` call to add) | S | — | — | — (behavior only; closed-source product) |
| `goland:GoConvertStringLiterals` | goland | literal can be written with other quotes (interpreted <-> raw) | EXPRESSION | SYNTAX | port | S | — | style, intention-like; weak warning | — (behavior only; closed-source product) |
| `goland:GoCyclicImports` | goland | import cycle | PACKAGE | PROJECT_INDEX | native: `GoImportCycle` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoDebugDirective` | goland | `//go:debug` in a wrong package or place | FILE | SYNTAX | same-as: `govet:directive` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoDebugMinGoSdkVersion` | goland | `//go:debug` with a `go` version below the supported one | FILE | SYNTAX | same-as: `govet:directive` | — | — | check the min-version part when `govet:directive` is ported | — (behavior only; closed-source product) |
| `goland:GoDeferGo` | goland | `go`/`defer` of `panic`/`recover` called directly (`defer recover()`) | STATEMENT | TYPES | same-as: `revive:defer` | — | — | `recover` sub-checks of `revive:defer` (B1) | — (behavior only; closed-source product) |
| `goland:GoDeferInLoop` | goland | `defer` in a loop runs at function exit | STATEMENT | SYNTAX | native: `GoDeferInLoop` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoDeprecation` | goland | use of a symbol documented `Deprecated:` | EXPRESSION | TYPES + PROJECT_INDEX | same-as: `SA1019` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoDetectSetFinalizerUsages` | goland | `runtime.SetFinalizer` where `runtime.AddCleanup` (go >= 1.24) is available | CALL | TYPES | port | S | — | version-gated (go directive) | — (behavior only; closed-source product) |
| `goland:GoDfaConstantCondition` | goland | condition proven constant on the paths leading to it | EXPRESSION | FLOW | native (partial): GoImpossibleNilCheck (nil conditions only; other proven-constant conditions to add on the same lattice) | M | — | — | — (behavior only; closed-source product) |
| `goland:GoDfaErrorMayBeNotNil` | goland | result used before the paired `error` is checked | FUNCTION | TYPES + FLOW | native: `GoResultUsedBeforeErrorCheck` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoDfaInspectionRunner` | goland | service entry point of the DFA inspections (the JAR description is an empty template) | FUNCTION | FLOW | skip: not a check; the flow inspections run on their own (`semantic.flow`) | — | — | — | — (behavior only; closed-source product) |
| `goland:GoDfaNilDereference` | goland | pointer may be nil on some path before the dereference | FUNCTION | TYPES + FLOW | native (partial): GoNilDereference (variable nil on every path; may-be-nil on some path to add) | M | — | — | — (behavior only; closed-source product) |
| `goland:GoDirectComparisonOfErrors` | goland | `err == ErrX` ignores wrapping | EXPRESSION | TYPES | native: `GoErrorsPackage` | — | — | alias of `errorlint:comparison` | — (behavior only; closed-source product) |
| `goland:GoDisabledGopathIndexing` | goland | IDE setting excludes needed GOPATH libraries from indexing | MODULE | SYNTAX | skip: IDE mechanics; the project model reads module cache / GOPATH roots itself (`GoCatalogueService`, Dependencies node) | — | — | — | — (behavior only; closed-source product) |
| `goland:GoDivisionByZero` | goland | divisor proven zero | EXPRESSION | TYPES + FLOW | native (partial): GoChecker (a constant zero divisor is a go/types error, verify the message; a divisor proven zero by flow to add) | S | — | — | — (behavior only; closed-source product) |
| `goland:GoEmptyDeclaration` | goland | empty `var ()` / `const ()` / `type ()` group | STATEMENT | SYNTAX | same-as: `gocritic:emptyDecl` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoErrorStringFormat` | goland | error text capitalized or ending with punctuation | CALL | TYPES | same-as: `ST1005` | — | — | alias also `revive:error-strings` | — (behavior only; closed-source product) |
| `goland:GoErrorsAs` | goland | `errors.As` target is not a non-nil pointer | CALL | TYPES | native: `GoErrorsPackage` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoExportedElementShouldHaveComment` | goland | exported declaration without a doc comment | TYPE_SPEC | SYNTAX | native: `GoDocComment` | — | — | off by default, like in the plugin | — (behavior only; closed-source product) |
| `goland:GoExportedFuncWithUnexportedType` | goland | exported function returns an unexported type | FUNCTION | TYPES | same-as: `revive:unexported-return` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoExportedOwnDeclaration` | goland | several exported names declared in one line (`const A, B = 1, 2`) | STATEMENT | SYNTAX | port | S | — | do not split iota groups | — (behavior only; closed-source product) |
| `goland:GoFuzzingSupport` | goland | fuzz test with Go before 1.18 | FILE | SYNTAX | skip: go < 1.18 is not supported by the plugin's toolchain checks; fuzz names and signatures are `govet:tests` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoImportUsedAsName` | goland | local name shadows an imported package | EXPRESSION | TYPES | same-as: `gocritic:importShadow` | — | — | alias also `revive:import-shadowing` | — (behavior only; closed-source product) |
| `goland:GoInfiniteFor` | goland | empty `for {}` spins the CPU | STATEMENT | SYNTAX | same-as: `SA5002` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoInterfaceToAny` | goland | `interface{}` can be `any` | EXPRESSION | TYPES | same-as: `revive:use-any` | — | — | also `goland:GoFixAny` | — (behavior only; closed-source product) |
| `goland:GoIrregularIota` | goland | iota group mixes declaration forms that change the expected values | TYPE_SPEC | SYNTAX | port | S | — | no automatic fix | — (behavior only; closed-source product) |
| `goland:GoLeadingWhitespaceInDirectiveComment` | goland | space between `//` and a directive: it is not recognized | FILE | SYNTAX | same-as: `SA9009` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoLoopClosure` | goland | closure captures the loop variable (go < 1.22) | STATEMENT | TYPES | native: `GoLoopClosure` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoMaybeNil` | goland | interprocedural: a call result may be nil and is dereferenced | FUNCTION | SSA-heavy | port-approx | L | — | needs per-function nil summaries (SCC for recursion) | — (behavior only; closed-source product) |
| `goland:GoMissingTrailingComma` | goland | missing trailing comma before a newline | EXPRESSION | SYNTAX | skip: syntax error of the go-psi parser (verify the message text for a missing comma) | — | — | — | — (behavior only; closed-source product) |
| `goland:GoMixedReceiverTypes` | goland | value and pointer receivers mixed on one type | PACKAGE | TYPES | port | S | — | — | — (behavior only; closed-source product) |
| `goland:GoNameStartsWithPackageName` | goland | exported name repeats the package name (`http.HTTPServer`) | FILE | SYNTAX | port | S | — | the stutter part of `revive:exported` (not covered by `GoDocComment`) | — (behavior only; closed-source product) |
| `goland:GoNilness` | goland | nil misuse: interfaces, maps, pointers, channels, calls | FUNCTION | TYPES + FLOW | native (partial): GoNilDereference, GoImpossibleNilCheck (verify nil channel send/close/receive and nil-map reads) | M | — | — | — (behavior only; closed-source product) |
| `goland:GoPreferNilSlice` | goland | empty slice literal can be a nil slice | EXPRESSION | TYPES | port | S | — | `[]T{}` -> `nil` is unsafe for JSON and reflect: weak warning | — (behavior only; closed-source product) |
| `goland:GoPrintFunctions` | goland | printf format does not match the arguments | CALL | TYPES | native: `GoPrintf` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoReceiverNames` | goland | receiver named `self`/`this`/`me` or inconsistent | TYPE_SPEC | TYPES | same-as: `ST1006` | — | — | alias also `ST1016`, `revive:receiver-naming` | — (behavior only; closed-source product) |
| `goland:GoRedundantBlankArgInRange` | goland | `for i, _ := range` | STATEMENT | SYNTAX | same-as: `revive:range` | — | — | alias also `S1005` | — (behavior only; closed-source product) |
| `goland:GoRedundantComma` | goland | redundant comma where none is needed or allowed | EXPRESSION | SYNTAX | port | S | — | exact scope: verify against GoLand's resource and the parser's recovery | — (behavior only; closed-source product) |
| `goland:GoRedundantConversion` | goland | conversion changes neither type nor value | EXPRESSION | TYPES | same-as: `unconvert` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoRedundantElseInIf` | goland | `else` after a terminating branch | STATEMENT | SYNTAX | same-as: `revive:indent-error-flow` | — | — | alias also `revive:superfluous-else` | — (behavior only; closed-source product) |
| `goland:GoRedundantImportAlias` | goland | import alias equals the package name | FILE | TYPES | same-as: `revive:redundant-import-alias` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoRedundantParens` | goland | parentheses that do not change grouping | EXPRESSION | SYNTAX | port | S | — | `gocritic:typeUnparen` is the type-only part | — (behavior only; closed-source product) |
| `goland:GoRedundantSecondIndexInSlices` | goland | `s[a:len(s)]` | EXPRESSION | TYPES | same-as: `S1010` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoRedundantSemicolon` | goland | explicit semicolon that is redundant | STATEMENT | SYNTAX | port | S | — | — | — (behavior only; closed-source product) |
| `goland:GoRedundantTrueInForCondition` | goland | `for true {}` | STATEMENT | SYNTAX | same-as: `S1006` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoRedundantTypeDeclInCompositeLit` | goland | element type repeated in a nested composite literal (`[]Point{Point{}}`) | EXPRESSION | TYPES | port | S | — | `gofmt -s` simplification; pointer elements and go version | — (behavior only; closed-source product) |
| `goland:GoReservedWordUsedAsName` | goland | name shadows a predeclared identifier | FILE | SYNTAX | same-as: `predeclared` | — | — | alias also `revive:redefines-builtin-id`, `gocritic:builtinShadow` | — (behavior only; closed-source product) |
| `goland:GoResourceLeak` | goland | acquired resource is not closed on some path | FUNCTION | TYPES + FLOW | native (partial): GoBodyNotClosed, GoRowsNotClosed (HTTP bodies and sql rows only; `os.Open`, `net.Dial`, `Closer` results in general to add) | M | — | — | — (behavior only; closed-source product) |
| `goland:GoSelfAssignment` | goland | variable assigned to itself | STATEMENT | SYNTAX | native: `GoSelfAssignment` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoShadowedVar` | goland | inner declaration shadows an outer variable and may lose the result | FUNCTION | TYPES + FLOW | same-as: `govet:shadow` | — | — | the plugin has `GoShadowedError` (errors only); generalized in B1 | — (behavior only; closed-source product) |
| `goland:GoShift` | goland | shift count not below the operand's width | EXPRESSION | TYPES | same-as: `govet:shift` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoSnakeCaseUsage` | goland | snake_case name of a Go declaration | FILE | SYNTAX | same-as: `revive:var-naming` | — | — | alias also `ST1003` | — (behavior only; closed-source product) |
| `goland:GoStandardMethods` | goland | well-known method with a non-standard signature | TYPE_SPEC | TYPES | same-as: `govet:stdmethods` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoStringsReplaceCount` | goland | `strings.Replace` with a zero count | CALL | TYPES | same-as: `SA1018` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoStructInitializationWithoutFieldNames` | goland | unkeyed composite literal of a foreign struct | EXPRESSION | TYPES | same-as: `govet:composites` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoStructLayout` | goland | field order creates padding or a pointer prefix | TYPE_SPEC | TYPES | skip: noisy; see `govet:fieldalignment` (skipped), the host has the Reorder Fields intention | — | — | — | — (behavior only; closed-source product) |
| `goland:GoSwitchMissingCasesForIotaConsts` | goland | switch over an enum misses constants | STATEMENT | TYPES + PROJECT_INDEX | native: `GoExhaustiveSwitch` | — | — | alias of `exhaustive` | — (behavior only; closed-source product) |
| `goland:GoTestName` | goland | test name or signature breaks the testing convention | FUNCTION | TYPES | same-as: `govet:tests` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoTypeAssertionOnErrors` | goland | type assertion on an error ignores wrapping | EXPRESSION | TYPES | same-as: `errorlint:asserts` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoTypeParameterInLowerCase` | goland | type parameter name does not match the chosen style | TYPE_SPEC | SYNTAX | port | S | — | style option; off by default | — (behavior only; closed-source product) |
| `goland:GoUnhandledErrorResult` | goland | returned error dropped | CALL | TYPES | native: `GoUncheckedError` | — | — | alias of `errcheck` | — (behavior only; closed-source product) |
| `goland:GoUnitSpecificDurationSuffix` | goland | `time.Duration` named with a unit (`timeoutSecs`) | FILE | TYPES | same-as: `ST1011` | — | — | alias also `revive:time-naming` | — (behavior only; closed-source product) |
| `goland:GoUnnecessarilyExportedIdentifiers` | goland | exported name used only inside its package | PACKAGE | TYPES + PROJECT_INDEX | port | M | — | project-wide, opt-in; no mass fix | — (behavior only; closed-source product) |
| `goland:GoUnreachableCode` | goland | no reachable path to the statement | STATEMENT | FLOW | native: `GoUnreachableCode` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoUnsortedImport` | goland | imports not in the formatter's order | FILE | SYNTAX | skip: formatter territory: gofmt/goimports on save and Optimize Imports (`GoUnusedImport` fix) | — | — | — | — (behavior only; closed-source product) |
| `goland:GoUnusedCallResult` | goland | result of a pure / result-oriented function dropped | CALL | TYPES | native: `GoUnusedResult` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoUnusedConst` | goland | constant without usages | PACKAGE | TYPES + PROJECT_INDEX | same-as: `unused` | — | — | `unused` is partial: unexported package-level decls arrive with B1 | — (behavior only; closed-source product) |
| `goland:GoUnusedExportedFunction` | goland | exported function never called in the search scope | PACKAGE | TYPES + PROJECT_INDEX | native: `GoUnusedExported` | — | — | the plugin checks `internal/` and application packages only | — (behavior only; closed-source product) |
| `goland:GoUnusedExportedType` | goland | exported type of a main/test package unused | PACKAGE | TYPES + PROJECT_INDEX | native: `GoUnusedExported` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoUnusedFunction` | goland | unexported function unreachable from used code | PACKAGE | TYPES + PROJECT_INDEX | same-as: `unused` | — | — | see `goland:GoUnusedConst` | — (behavior only; closed-source product) |
| `goland:GoUnusedGlobalVariable` | goland | package variable unused | PACKAGE | TYPES + PROJECT_INDEX | same-as: `unused` | — | — | see `goland:GoUnusedConst` | — (behavior only; closed-source product) |
| `goland:GoUnusedParameter` | goland | parameter never read | FUNCTION | TYPES | native: `GoUnusedParameter` | — | — | alias of `revive:unused-parameter` | — (behavior only; closed-source product) |
| `goland:GoUnusedType` | goland | unexported type without usages | PACKAGE | TYPES + PROJECT_INDEX | same-as: `unused` | — | — | see `goland:GoUnusedConst` | — (behavior only; closed-source product) |
| `goland:GoUnusedTypeParameter` | goland | type parameter not used where it should be | FUNCTION | TYPES | port | S | — | — | — (behavior only; closed-source product) |
| `goland:GoVarAndConstTypeMayBeOmitted` | goland | explicit type fully inferred | STATEMENT | TYPES | same-as: `ST1023` | — | — | alias also `revive:var-declaration`, `QF1011` | — (behavior only; closed-source product) |
| `goland:GoVetAtomic` | goland | `x = atomic.AddInt64(&x, 1)` | STATEMENT | TYPES | same-as: `govet:atomic` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoVetCopyLock` | goland | lock copied by value | EXPRESSION | TYPES | native: `GoCopyLocks` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoVetFailNowInNotTestGoroutine` | goland | `FailNow`/`Fatal` from a child goroutine | STATEMENT | TYPES | native: `GoTestingGoroutine` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoVetImpossibleInterfaceToInterfaceAssertion` | goland | interface assertion that can never succeed | EXPRESSION | TYPES | same-as: `govet:ifaceassert` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoVetIntToStringConversion` | goland | `string(integer)` | EXPRESSION | TYPES | same-as: `govet:stringintconv` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoVetLostCancel` | goland | cancel function of `WithCancel`/`WithTimeout` not called | FUNCTION | TYPES + FLOW | native: `GoLostCancel` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoVetStructTag` | goland | malformed struct tag | TYPE_SPEC | SYNTAX | native: `GoStructTag` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoVetUnmarshal` | goland | `Unmarshal` target is not a pointer | CALL | TYPES | same-as: `govet:unmarshal` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoVetUnsafePointer` | goland | `uintptr` kept as a number between pointer conversions | EXPRESSION | TYPES | same-as: `govet:unsafeptr` | — | — | — | — (behavior only; closed-source product) |
| `goland:VgoDependencyDeprecated` | goland | module metadata marks a dependency deprecated | MODULE | PROJECT_INDEX | port | M | — | go.mod; read `// Deprecated:` of the dependency's go.mod in the module cache; implement in the host `mod` package (language GoModule) | — (behavior only; closed-source product) |
| `goland:VgoDependencyUpdateAvailable` | goland | newer version of a dependency exists | MODULE | PROJECT_INDEX | port | M | — | go.mod; needs `go list -m -u` (network, background only, off by default); host `mod` package | — (behavior only; closed-source product) |
| `goland:VgoDependencyVersionRetracted` | goland | required version retracted by the author | MODULE | PROJECT_INDEX | port | M | — | go.mod; `retract` directives from the module cache; host `mod` package | — (behavior only; closed-source product) |
| `goland:VgoMigrateFromReplacesToWorkspace` | goland | local `replace` directives used for multi-module work -> `go.work` | MODULE | SYNTAX | port | S | — | go.mod; host `mod` package | — (behavior only; closed-source product) |
| `goland:VgoRequireDirectivesMerge` | goland | homogeneous `require` directives can be merged | MODULE | SYNTAX | port | S | — | go.mod; host `mod` package | — (behavior only; closed-source product) |
| `goland:VgoUnresolvedIgnorePath` | goland | relative `ignore` path in go.mod does not resolve | MODULE | SYNTAX | port | S | — | go.mod; host `mod` package (next to `GoModPaths`) | — (behavior only; closed-source product) |
| `goland:VgoUnusedDependency` | goland | direct dependency not needed by the package graph | MODULE | PROJECT_INDEX | native (partial): GoModUnused (every file counts: build tags are ignored; a build-context-aware graph to add) | M | — | go.mod | — (behavior only; closed-source product) |
| `goland:GoFixAny` | goland | modernize: `interface{}` -> `any` | EXPRESSION | TYPES | same-as: `revive:use-any` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoFixAtomicTypes` | goland | modernize: primitive `atomic.AddInt64(&x, …)` -> typed `atomic.Int64` field | CALL | TYPES | port | M | — | go >= 1.19; the fix touches all uses of the field | — (behavior only; closed-source product) |
| `goland:GoFixBuildTag` | goland | modernize: invalid build constraints | FILE | SYNTAX | native: `GoBuildConstraint` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoFixEmbedLit` | goland | modernize: nested embedded literal -> promoted-field literal | EXPRESSION | TYPES | port | S | — | go 1.27 (per GoLand's description): version-gated, off for older `go` directives | — (behavior only; closed-source product) |
| `goland:GoFixErrorsAsType` | goland | modernize: `errors.As(err, &target)` -> `errors.AsType[T](err)` | CALL | TYPES | port | S | — | go >= 1.26 | — (behavior only; closed-source product) |
| `goland:GoFixForVar` | goland | modernize: `v := v` copy of the loop variable is redundant | STATEMENT | TYPES | same-as: `copyloopvar` | — | — | go >= 1.22 | — (behavior only; closed-source product) |
| `goland:GoFixHostPort` | goland | modernize: `host + ":" + port` is not IPv6-safe | EXPRESSION | TYPES | same-as: `govet:hostport` | — | — | check that the concatenation form (not only Sprintf) is covered | — (behavior only; closed-source product) |
| `goland:GoFixInline` | goland | `//go:fix inline`: replace calls by the function body | CALL | TYPES + PROJECT_INDEX | port-approx | L | — | uses the plugin's Inline refactoring; evaluation order and scope hygiene | — (behavior only; closed-source product) |
| `goland:GoFixMapsLoop` | goland | modernize: manual keys/values copy loop -> `maps` API | STATEMENT | TYPES | port | S | — | go >= 1.21 / 1.23 | — (behavior only; closed-source product) |
| `goland:GoFixMinMax` | goland | modernize: conditional assignment -> `min` / `max` | STATEMENT | TYPES | port | S | — | go >= 1.21 | — (behavior only; closed-source product) |
| `goland:GoFixNewExpr` | goland | modernize: pointer-to-value helper -> `new(value)` | FUNCTION | TYPES | port | S | — | go 1.26 | — (behavior only; closed-source product) |
| `goland:GoFixOmitZero` | goland | modernize: `omitempty` on a struct field -> `omitzero` | TYPE_SPEC | TYPES | port | S | — | go >= 1.24; changes serialization, no blind fix | — (behavior only; closed-source product) |
| `goland:GoFixPlusBuild` | goland | modernize: `+build` repeats the `//go:build` line | FILE | SYNTAX | same-as: `revive:redundant-build-tag` | — | — | — | — (behavior only; closed-source product) |
| `goland:GoFixRangeInt` | goland | modernize: counting loop -> `for i := range n` | STATEMENT | TYPES | same-as: `intrange` | — | — | go >= 1.22 | — (behavior only; closed-source product) |
| `goland:GoFixReflectTypeFor` | goland | modernize: `reflect.TypeOf(x)` of a static type -> `reflect.TypeFor[T]()` | CALL | TYPES | port | S | — | go >= 1.22 | — (behavior only; closed-source product) |
| `goland:GoFixSlicesBackward` | goland | modernize: reverse index loop -> `slices.Backward` | STATEMENT | TYPES | port | S | — | go >= 1.23 | — (behavior only; closed-source product) |
| `goland:GoFixSlicesContains` | goland | modernize: search loop -> `slices.Contains` / `slices.Index` | STATEMENT | TYPES | port | S | — | go >= 1.21 | — (behavior only; closed-source product) |
| `goland:GoFixSlicesSort` | goland | modernize: `sort.Slice` with a simple comparison -> `slices.Sort` | CALL | TYPES | same-as: `revive:use-slices-sort` | — | — | check that `sort.Slice` with a trivial less is covered, not only `sort.Ints` | — (behavior only; closed-source product) |
| `goland:GoFixStdIterators` | goland | modernize: index-based API walks -> standard iterators | STATEMENT | TYPES | port-approx | M | — | go >= 1.24; a table of known APIs | — (behavior only; closed-source product) |
| `goland:GoFixStringsBuilder` | goland | modernize: repeated string concatenation -> `strings.Builder` | STATEMENT | TYPES | port | M | — | only loops with a clear accumulator | — (behavior only; closed-source product) |
| `goland:GoFixStringsCut` | goland | modernize: `Index` + slicing -> `strings.Cut` | STATEMENT | TYPES | port | S | — | go >= 1.18 | — (behavior only; closed-source product) |
| `goland:GoFixStringsCutPrefix` | goland | modernize: `HasPrefix` + `TrimPrefix` -> `CutPrefix` | STATEMENT | TYPES | port | S | — | go >= 1.20; differs from `S1017` | — (behavior only; closed-source product) |
| `goland:GoFixStringsSeq` | goland | modernize: `Split` / `Fields` only ranged over -> `SplitSeq` / `FieldsSeq` | STATEMENT | TYPES | port | S | — | go >= 1.24 | — (behavior only; closed-source product) |
| `goland:GoFixTestingContext` | goland | modernize: manual test context -> `t.Context()` | STATEMENT | TYPES | port | S | — | go >= 1.24 | — (behavior only; closed-source product) |
| `goland:GoFixUnsafeFuncs` | goland | modernize: manual pointer arithmetic -> `unsafe.Add` / `unsafe.Slice` | EXPRESSION | TYPES | port | S | — | go >= 1.17 | — (behavior only; closed-source product) |
| `goland:GoFixWaitGroup` | goland | modernize: `Add` / `go` / `defer Done` -> `WaitGroup.Go` | STATEMENT | TYPES | same-as: `revive:use-waitgroup-go` | — | — | go >= 1.25 | — (behavior only; closed-source product) |

## Summary

| Section | native | native (partial) | same-as | port | port-approx | skip | formatter | total |
|---|---|---|---|---|---|---|---|---|
| errcheck, ineffassign, unused | 2 | 1 | 0 | 0 | 0 | 0 | 0 | 3 |
| govet analyzers | 28 | 1 | 0 | 10 | 1 | 5 | 0 | 45 |
| staticcheck SA (bugs) | 79 | 1 | 0 | 8 | 7 | 1 | 0 | 96 |
| staticcheck S (simple, former gosimple) | 35 | 0 | 0 | 0 | 0 | 0 | 0 | 35 |
| staticcheck ST (stylecheck) | 3 | 0 | 0 | 15 | 0 | 0 | 0 | 18 |
| staticcheck QF (quickfix) | 0 | 0 | 0 | 12 | 0 | 0 | 0 | 12 |
| revive rules | 6 | 4 | 0 | 91 | 2 | 2 | 0 | 105 |
| gocritic checkers | 1 | 1 | 0 | 102 | 0 | 2 | 0 | 106 |
| gosec rules | 1 | 0 | 0 | 35 | 4 | 0 | 0 | 40 |
| testifylint checkers | 0 | 0 | 0 | 25 | 0 | 0 | 0 | 25 |
| Popular linters (one rule each, or a few sub-checks) | 6 | 2 | 0 | 26 | 1 | 1 | 0 | 36 |
| Formatters | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 5 |
| GoLand inspections | 23 | 7 | 47 | 37 | 3 | 6 | 0 | 123 |
| **All** | **184** | **17** | **47** | **361** | **18** | **17** | **5** | **649** |

golangci default set (v1+v2 and v2 rows, 194 rules): native 141, native (partial) 2, port 40, port-approx 7, skip 4.

GoLand inspections (123): native 23, native (partial) 7, same-as 47, port 37, port-approx 3, skip 6.
GoLand coverage: 34 of 123 inspections are covered today by a native inspection (native, or same-as a rule that is native); 7 more are partial.
Estimate after batches B1-B7: 54 of 123 (44%), 6 skipped by design; the rest is GoLand-only or sits in later batches.
After all batches (B1-B30): 117 of 123; the 6 skipped rows are IDE mechanics or covered elsewhere (reason in the Status column).
## Porting plan

Batches of 10–20 rules, each one agent (one brief, one fixture set, `:go-psi-ide:test --tests "…ide.rules.*"`), grouped by scope and needs so that a
batch shares one visitor shape and one kind of analysis. Order is by value: golangci's default set first (it is what users of golangci already
see), then the rest of staticcheck, then popular linters, gosec, test linters, and the long tail of revive / gocritic.

**One check, several ids.** Many rules are the same check under different linters. A batch implements the check once and registers every id
as an alias, so a `.golangci.yml` that enables any of the linters turns it on and its exclusions apply under the reporting linter's name.
When the ids of a family fall into different batches, the first batch implements the check and the later one only registers the id.

| Check | Ids |
|---|---|
| unmarshal non-pointer | `SA1014`, `govet:unmarshal` |
| unbuffered signal channel | `SA1017`, `govet:sigchanyzer` |
| sort.Slice of a non-slice | `SA1028`, `govet:sortslice` |
| 64-bit atomic alignment | `SA1027`, `govet:atomicalign` |
| impossible type assertion | `SA5010`, `govet:ifaceassert` |
| `x = atomic.Add(&x, …)` | `govet:atomic`, `revive:atomic` |
| `string(int)` | `govet:stringintconv`, `revive:string-of-int` |
| infinite recursion | `SA5007`, `revive:unconditional-recursion` |
| package comment | `ST1000`, `revive:package-comments` |
| dot import | `ST1001`, `revive:dot-imports` |
| naming / initialisms | `ST1003`, `revive:var-naming` |
| error strings | `ST1005`, `revive:error-strings` |
| receiver names | `ST1006`, `ST1016`, `revive:receiver-naming` |
| error last | `ST1008`, `revive:error-return` |
| duration unit suffix | `ST1011`, `revive:time-naming` |
| error variable names | `ST1012`, `revive:error-naming` |
| duplicate import | `ST1019`, `revive:duplicated-imports`, `gocritic:dupImport` |
| redundant var type | `ST1023`, `QF1011`, `revive:var-declaration` |
| `errors.New(fmt.Sprintf())` | `S1028`, `revive:errorf` |
| `time.Time ==` | `QF1009`, `revive:time-equal` |
| identical operands | `SA4000`, `gocritic:dupSubExpr`, `revive:constant-logical-expr` |
| duplicate conditions / branches | `SA4014`, `revive:identical-ifelseif-conditions`, `gocritic:dupBranchBody`, `revive:identical-branches`, `revive:identical-ifelseif-branches`, `revive:identical-switch-branches`, `revive:identical-switch-conditions`, `gocritic:dupCase` |
| empty branch / block | `SA9003`, `revive:empty-block` |
| `x.(T)` without ok | `forcetypeassert`, `revive:unchecked-type-assertion` |
| builtin shadowed | `predeclared`, `revive:redefines-builtin-id`, `gocritic:builtinShadowDecl`, `gocritic:builtinShadow` |
| cyclomatic complexity | `gocyclo`, `cyclop`, `revive:cyclomatic` |
| function length | `funlen`, `revive:function-length` |
| too many results | `revive:function-result-limit`, `gocritic:tooManyResultsChecker` |
| nesting | `nestif`, `revive:max-control-nesting` |
| naked return | `nakedret`, `revive:bare-return` |
| repeated literal | `goconst`, `revive:add-constant` |
| `//comment` spacing | `revive:comment-spacings`, `gocritic:commentFormatting` |
| Yoda condition | `ST1017`, `gocritic:yodaStyleExpr` |
| `Printf` without verbs | `revive:use-fmt-print`, `revive:unnecessary-format`, `revive:use-errors-new`, `perfsprint` (part) |
| `ToLower(a) == ToLower(b)` | `SA6005`, `gocritic:equalFold` |
| `w.Write([]byte(Sprintf()))` | `QF1012`, `gocritic:preferFprint` |
| loop variable: redundant copy (go >= 1.22) / aliasing (go < 1.22) | `copyloopvar`; `gosec:G601`, `revive:range-val-address` |
| `x == true` | `S1002`, `revive:bool-literal-in-expr`, `gocritic:boolExprSimplify` (part) |
| octal-looking mode | `SA9002`, `gocritic:octalLiteral` (style part) |
| `else` after return | `revive:indent-error-flow`, `revive:superfluous-else`, `revive:early-return` |
| `//nolint` hygiene | `nolintlint`, `gocritic:whyNoLint` |

### B1. Extend the native inspections (17) — FUNCTION / TYPE_SPEC / FILE, TYPES + FLOW + PROJECT_INDEX
Not new checks: widen what an existing inspection reports and register the rule ids on it.
`unused` (unexported package-level funcs/types/consts/vars and struct fields; options), `govet:shadow` (any variable, `strict`),
`SA1002` (full `time.Parse` layout validation in `GoTimeLayout`), `sqlclosecheck` (`*sql.Stmt`), `wastedassign` (assignment before `return`),
`revive:defer` (recover outside defer, return in defer, call chains), `revive:struct-tag` (options of known tags), `revive:redundant-build-tag`,
`revive:waitgroup-by-value`, `gocritic:nilValReturn`, `unparam` (constant arguments across call sites, PROJECT_INDEX).
GoLand-parity widenings of the same inspections: `goland:GoContextTodo` (every `context.TODO()` in `GoContextPlacement`), `goland:GoDfaConstantCondition`
(other proven-constant conditions next to `GoImpossibleNilCheck`), `goland:GoDfaNilDereference` (may-be-nil on some path in `GoNilDereference`),
`goland:GoDivisionByZero`, `goland:GoNilness` (nil channels, nil-map reads), `goland:GoResourceLeak` (`os.Open` & co. next to `GoBodyNotClosed`).

### B2. staticcheck SA: stdlib call contracts, part 1 (20) — CALL, TYPES — done (versions 0.2.74)
`SA1000`, `SA1004`, `SA1005`, `SA1006`, `SA1007`, `SA1010`, `SA1012`, `SA1013`, `SA1014`, `govet:unmarshal`, `SA1016`, `SA1017`, `govet:sigchanyzer`,
`SA1018`, `SA1020`, `SA1021`, `SA1024`, `SA1029`, `SA1030`, `SA1032`.

### B3. staticcheck SA: stdlib call contracts, part 2 (19) — CALL, TYPES — done (versions 0.2.74)
`SA1001`, `SA1003`, `SA1008`, `SA1011`, `SA1015`, `SA1026`, `SA1027`, `govet:atomicalign`, `SA1028`, `govet:sortslice`, `SA5005`, `SA5012`, `SA6002`,
`SA9002`, `SA9005`, `SA9007`, `SA4027`, `SA4030`, `SA4015`.

### B4. staticcheck SA + govet: suspicious expressions (20) — EXPRESSION, TYPES — done (versions 0.2.75)
`SA4000`, `SA4001`, `SA4003`, `SA4012`, `SA4013`, `SA4016`, `SA4022`, `SA4024`, `SA4025`, `SA4026`, `SA4028`, `SA4032`, `SA9006`, `SA5010`,
`govet:ifaceassert`, `govet:nilfunc`, `govet:shift`, `govet:bools`, `govet:stringintconv`, `govet:unsafeptr`.

### B5. staticcheck SA + govet: suspicious statements (20) — STATEMENT, SYNTAX/TYPES — done (versions 0.2.75)
`SA2001`, `SA2003`, `SA3001`, `SA4011`, `SA4014`, `SA4020`, `SA4029`, `SA4021`, `govet:appends`, `SA5002`, `SA5003`, `SA5004`, `SA6000`, `SA6003`,
`SA9003`, `SA9008`, `SA9010`, `govet:atomic`, `govet:defers`, `SA6001`.

### B6. govet remainder and deprecation (14) — FUNCTION / FILE / CALL, TYPES + PROJECT_INDEX
`SA1019` (Deprecated: paragraphs through stubs; also strikethrough highlighting), `govet:stdversion`, `govet:stdmethods`, `govet:tests`,
`govet:directive`, `govet:hostport`, `govet:httpmux`, `govet:slog`, `govet:composites`, `govet:deepequalerrors`, `govet:reflectvaluecompare`, `SA4019`,
`SA9009`, `SA9004`.

### B7. Flow-based checks (16) — FUNCTION, FLOW (SSA-heavy ones as approximations)
`SA4004`, `SA4009`, `SA5000`, `SA5007`, `revive:unconditional-recursion`, `SA4005`, `SA4008`, `SA4010`, `SA4023`, `SA1025`, `SA1023`, `SA1031`,
`govet:unusedwrite`, `makezero`, `rowserrcheck`, `goland:GoMaybeNil` (interprocedural nil summaries, an approximation).

### B8. staticcheck S (simple): statement rewrites (17) — STATEMENT, SYNTAX/TYPES, each with a quick fix — done (versions 0.2.76)
`S1000`, `S1001`, `S1005`, `S1006`, `S1008`, `S1011`, `S1016`, `S1017`, `S1018`, `S1021`, `S1023`, `S1029`, `S1031`, `S1033`, `S1034`, `S1036`, `S1037`.

### B9. staticcheck S (simple): call and expression rewrites (20) — CALL / EXPRESSION, TYPES, each with a quick fix — done (versions 0.2.76)
`S1002`, `S1003`, `S1004`, `S1007`, `S1009`, `S1010`, `S1012`, `S1019`, `S1020`, `S1024`, `S1025`, `S1028`, `S1030`, `S1032`, `S1035`, `S1038`,
`S1039`, `S1040`, `SA6005`, `SA6006`.

### B10. stylecheck + revive: naming and doc conventions (17) — FILE / PACKAGE, SYNTAX/TYPES
`ST1000`, `revive:package-comments`, `ST1001`, `revive:dot-imports`, `ST1003`, `revive:var-naming`, `ST1005`, `revive:error-strings`, `ST1006`, `ST1016`,
`revive:receiver-naming`, `ST1008`, `revive:error-return`, `ST1011`, `revive:time-naming`, `ST1012`, `revive:error-naming`.

### B11. stylecheck rest + revive default rules (17) — STATEMENT / FILE, SYNTAX/TYPES
`ST1013`, `ST1015`, `ST1017`, `ST1018`, `ST1019`, `revive:duplicated-imports`, `gocritic:dupImport`, `revive:blank-imports`, `revive:context-keys-type`,
`revive:empty-block`, `revive:errorf`, `revive:increment-decrement`, `revive:indent-error-flow`, `revive:range`, `revive:redefines-builtin-id`,
`revive:superfluous-else`, `revive:unexported-return`.

### B12. staticcheck QF (quick fixes, v2 default) (14) — STATEMENT / EXPRESSION, SYNTAX/TYPES; weak warnings or intentions
`QF1001`, `QF1002`, `QF1003`, `QF1004`, `QF1005`, `QF1006`, `QF1007`, `QF1008`, `QF1009`, `QF1010`, `QF1011`, `QF1012`, `ST1023`, `revive:var-declaration`.

### B13. gocritic default checks, part 1 (20) — mixed scopes, SYNTAX/TYPES
`gocritic:appendAssign`, `gocritic:argOrder`, `gocritic:assignOp`, `gocritic:badCall`, `gocritic:badCond`, `gocritic:captLocal`, `gocritic:caseOrder`,
`gocritic:codegenComment`, `gocritic:commentFormatting`, `gocritic:defaultCaseOrder`, `gocritic:deprecatedComment`, `gocritic:dupArg`,
`gocritic:dupBranchBody`, `gocritic:dupCase`, `gocritic:dupSubExpr`, `gocritic:elseif`, `gocritic:exitAfterDefer`, `gocritic:flagDeref`,
`gocritic:flagName`, `gocritic:ifElseChain`.

### B14. gocritic default checks, part 2 (14) — EXPRESSION / STATEMENT, SYNTAX/TYPES
`gocritic:mapKey`, `gocritic:newDeref`, `gocritic:offBy1`, `gocritic:regexpMust`, `gocritic:singleCaseSwitch`, `gocritic:sloppyLen`,
`gocritic:sloppyTypeAssert`, `gocritic:switchTrue`, `gocritic:typeSwitchVar`, `gocritic:underef`, `gocritic:unlambda`, `gocritic:unslice`,
`gocritic:valSwap`, `gocritic:wrapperFunc`.

### B15. Popular single-purpose linters (15) — CALL / EXPRESSION / TYPE_SPEC, TYPES
`errorlint:errorf`, `errorlint:asserts`, `noctx`, `forcetypeassert`, `revive:unchecked-type-assertion`, `containedctx`, `errname`, `unconvert`,
`copyloopvar`, `intrange`, `usestdlibvars`, `perfsprint`, `predeclared`, `gocritic:builtinShadowDecl`, `gocritic:builtinShadow`.

### B16. Metrics and thresholds (20) — FUNCTION / FILE / PACKAGE, SYNTAX; all option-driven, off unless configured
`gocyclo`, `cyclop`, `revive:cyclomatic`, `revive:cognitive-complexity`, `funlen`, `revive:function-length`, `nestif`, `revive:max-control-nesting`,
`nakedret`, `revive:bare-return`, `revive:argument-limit`, `revive:function-result-limit`, `gocritic:tooManyResultsChecker`, `revive:file-length-limit`,
`revive:line-length-limit`, `revive:max-public-structs`, `goconst`, `revive:add-constant`, `prealloc`, `dupword`.

### B17. gosec, part 1: dangerous APIs and imports (20) — CALL / FILE, SYNTAX/TYPES
`gosec:G101`, `gosec:G102`, `gosec:G103`, `gosec:G106`, `gosec:G108`, `gosec:G111`, `gosec:G112`, `gosec:G114`, `gosec:G401`, `gosec:G403`,
`gosec:G404`, `gosec:G405`, `gosec:G406`, `gosec:G501`, `gosec:G502`, `gosec:G503`, `gosec:G504`, `gosec:G505`, `gosec:G506`, `gosec:G507`.

### B18. gosec, part 2: injection, files, conversions (19) — CALL / EXPRESSION, TYPES (+FLOW)
`gosec:G107`, `gosec:G109`, `gosec:G110`, `gosec:G115`, `gosec:G201`, `gosec:G202`, `gosec:G203`, `gosec:G204`, `gosec:G301`, `gosec:G302`,
`gosec:G303`, `gosec:G304`, `gosec:G305`, `gosec:G306`, `gosec:G307`, `gosec:G402`, `gosec:G407`, `gosec:G601`, `gosec:G602`.
Reuse the SQL-injection detection of the host (0.2.51) for G201/G202.

### B19. Test linters, part 1 (16) — FUNCTION / CALL, TYPES
`thelper`, `tparallel`, `paralleltest`, `revive:redundant-test-main-exit`, `testifylint:blank-import`, `testifylint:bool-compare`,
`testifylint:compares`, `testifylint:contains`, `testifylint:empty`, `testifylint:encoded-compare`, `testifylint:equal-values`,
`testifylint:error-is-as`, `testifylint:error-nil`, `testifylint:expected-actual`, `testifylint:float-compare`, `testifylint:len`.

### B20. Test linters, part 2: testifylint (13) — CALL / FUNCTION, TYPES
`testifylint:formatter`, `testifylint:go-require`, `testifylint:negative-positive`, `testifylint:nil-compare`, `testifylint:regexp`,
`testifylint:require-error`, `testifylint:suite-broken-parallel`, `testifylint:suite-dont-use-pkg`, `testifylint:suite-extra-assert-call`,
`testifylint:suite-method-signature`, `testifylint:suite-subtest-run`, `testifylint:suite-thelper`, `testifylint:useless-assert`.

### B21. revive long tail, part 1: style policies (20) — FILE / FUNCTION / STATEMENT, SYNTAX
`revive:banned-characters`, `revive:comment-spacings`, `revive:confusing-naming`, `revive:confusing-results`, `revive:constant-logical-expr`,
`revive:bool-literal-in-expr`, `revive:call-to-gc`, `revive:deep-exit`, `revive:early-return`, `revive:enforce-map-style`,
`revive:enforce-repeated-arg-type-style`, `revive:enforce-slice-style`, `revive:enforce-switch-style`, `revive:file-header`, `revive:filename-format`,
`revive:flag-parameter`, `revive:get-return`, `revive:identical-branches`, `revive:identical-ifelseif-branches`, `revive:identical-ifelseif-conditions`.

### B22. revive long tail, part 2 (20) — mixed scopes, SYNTAX/TYPES
`revive:identical-switch-branches`, `revive:identical-switch-conditions`, `revive:if-return`, `revive:import-alias-naming`, `revive:import-shadowing`,
`revive:imports-blocklist`, `revive:inefficient-map-lookup`, `revive:marshal-receiver`, `revive:modifies-parameter`, `revive:modifies-value-receiver`,
`revive:multiline-if-init`, `revive:nested-structs`, `revive:optimize-operands-order`, `revive:package-naming`, `revive:package-directory-mismatch`,
`revive:range-val-address`, `revive:redundant-import-alias`, `revive:string-format`, `revive:string-of-int`, `revive:time-date`.

### B23. revive long tail, part 3: modernizations and the rest (19) — CALL / STATEMENT, TYPES
`revive:time-equal`, `revive:unexported-naming`, `revive:unnecessary-if`, `revive:unnecessary-format`, `revive:unnecessary-stmt`,
`revive:unsecure-url-scheme`, `revive:unused-receiver`, `revive:use-any`, `revive:use-errors-new`, `revive:use-fmt-print`, `revive:use-slices-concat`,
`revive:use-slices-sort`, `revive:use-waitgroup-go`, `revive:useless-break`, `revive:useless-fallthrough`, `revive:atomic`, `revive:datarace`,
`revive:forbidden-call-in-wg-go`, `revive:epoch-naming`.

### B24. gocritic diagnostics (18) — CALL / STATEMENT, TYPES
`gocritic:badLock`, `gocritic:badRegexp`, `gocritic:badSorting`, `gocritic:badSyncOnceFunc`, `gocritic:dynamicFmtString`, `gocritic:evalOrder`,
`gocritic:externalErrorReassign`, `gocritic:filepathJoin`, `gocritic:rangeAppendAll`, `gocritic:regexpPattern`, `gocritic:returnAfterHttpError`,
`gocritic:sloppyReassign`, `gocritic:sortSlice`, `gocritic:sprintfQuotedString`, `gocritic:sqlQuery`, `gocritic:truncateCmp`,
`gocritic:uncheckedInlineErr`, `gocritic:weakCond`.

### B25. gocritic performance (15) — CALL / STATEMENT / FUNCTION, TYPES (type sizes)
`gocritic:appendCombine`, `gocritic:equalFold`, `gocritic:hugeParam`, `gocritic:indexAlloc`, `gocritic:preferDecodeRune`, `gocritic:preferFprint`,
`gocritic:preferStringWriter`, `gocritic:preferWriteByte`, `gocritic:rangeExprCopy`, `gocritic:rangeValCopy`, `gocritic:sliceClear`,
`gocritic:stringXbytes`, `gocritic:httpNoBody`, `gocritic:exposedSyncMutex`, `gocritic:ptrToRefParam`.

### B26. gocritic style with types (14) — EXPRESSION / CALL, TYPES
`gocritic:boolExprSimplify`, `gocritic:deferUnlambda`, `gocritic:emptyStringTest`, `gocritic:importShadow`, `gocritic:methodExprCall`,
`gocritic:redundantSprint`, `gocritic:regexpSimplify`, `gocritic:stringConcatSimplify`, `gocritic:stringsCompare`, `gocritic:syncMapLoadAndDelete`,
`gocritic:timeExprSimplify`, `gocritic:typeAssertChain`, `gocritic:unnamedResult`, `gocritic:preferFilepathJoin`.

### B27. gocritic style syntax + `//nolint` hygiene (18) — FILE / STATEMENT, SYNTAX
`gocritic:commentedOutImport`, `gocritic:docStub`, `gocritic:emptyDecl`, `gocritic:emptyFallthrough`, `gocritic:hexLiteral`, `gocritic:initClause`,
`gocritic:nestingReduce`, `gocritic:octalLiteral`, `gocritic:paramTypeCombine`, `gocritic:todoCommentWithoutDetail`, `gocritic:typeDefFirst`,
`gocritic:typeUnparen`, `gocritic:unlabelStmt`, `gocritic:unnecessaryBlock`, `gocritic:unnecessaryDefer`, `gocritic:yodaStyleExpr`,
`gocritic:whyNoLint`, `nolintlint`.

### B28. GoLand-only: syntax, naming and API style (15) — mixed scopes, SYNTAX/TYPES
`goland:GoAssignmentToReceiver`, `goland:GoConvertStringLiterals`, `goland:GoDetectSetFinalizerUsages`, `goland:GoExportedOwnDeclaration`,
`goland:GoIrregularIota`, `goland:GoMixedReceiverTypes`, `goland:GoNameStartsWithPackageName`, `goland:GoPreferNilSlice`, `goland:GoRedundantComma`,
`goland:GoRedundantParens`, `goland:GoRedundantSemicolon`, `goland:GoRedundantTypeDeclInCompositeLit`, `goland:GoTypeParameterInLowerCase`,
`goland:GoUnusedTypeParameter`, `goland:GoUnnecessarilyExportedIdentifiers`.

### B29. GoLand-only: modernize (`GoFix*`) (18) — STATEMENT / CALL, TYPES; each gated by the module's `go` version, with a quick fix
`goland:GoFixAtomicTypes`, `goland:GoFixEmbedLit`, `goland:GoFixErrorsAsType`, `goland:GoFixInline`, `goland:GoFixMapsLoop`, `goland:GoFixMinMax`,
`goland:GoFixNewExpr`, `goland:GoFixOmitZero`, `goland:GoFixReflectTypeFor`, `goland:GoFixSlicesBackward`, `goland:GoFixSlicesContains`,
`goland:GoFixStdIterators`, `goland:GoFixStringsBuilder`, `goland:GoFixStringsCut`, `goland:GoFixStringsCutPrefix`, `goland:GoFixStringsSeq`,
`goland:GoFixTestingContext`, `goland:GoFixUnsafeFuncs`.
The other `GoFix*` rows are `same-as` existing rules (`GoFixAny`, `GoFixForVar`, `GoFixHostPort`, `GoFixRangeInt`, `GoFixSlicesSort`, `GoFixWaitGroup`,
`GoFixPlusBuild`) or native (`GoFixBuildTag`).

### B30. GoLand-only: go.mod / go.work inspections (7) — MODULE; host `mod` package (language GoModule), not the rule engine
`goland:VgoDependencyDeprecated`, `goland:VgoDependencyUpdateAvailable`, `goland:VgoDependencyVersionRetracted`, `goland:VgoMigrateFromReplacesToWorkspace`,
`goland:VgoRequireDirectivesMerge`, `goland:VgoUnresolvedIgnorePath`, `goland:VgoUnusedDependency` (build-context-aware graph; `GoModUnused` ignores build tags).
`VgoDependencyUpdateAvailable` runs `go list -m -u` in the background and is off by default (it needs the network).

Every batch: rules registered with their engine id, `linter`, scope, needs and options from the table; one fixture per rule with a positive and a
negative case; the alias table above decides which ids share an implementation. Before a batch starts, its rows are checked against the pinned
sources (the rule exists in that version; message texts, since `text:` exclusions of users match them), and the GOROOT noise gate
(`FlowCorpusTest`-style) gets the new rules.

## GoLand names as aliases

The rows `goland:<Name>` of `LINT-RULES.md` with status `same-as: <id>` are not ported separately: the check is implemented once under `<id>` and
registered with the GoLand name as an additional alias (so that `//noinspection <Name>` and the settings tree can address it). Rows with `native` /
`native (partial)` keep their inspection's short name; the partial ones are in B1 or B30. `GoLand-only` rows are B28-B30 (and the interprocedural nil check in B7).

## Configuration of the rules from `.golangci.yml`

The host package `io.github.golangsupport.lint.config` reads `.golangci.yml|yaml|json` of both formats (TOML: an "unsupported" result) into
`GolangciConfig`; the engine's `GoRuleConfigSource` adapts it: a rule is on when its linter is in `GolangciLinters.effectiveLinters(config)`
(staticcheck ids also need `isStaticcheckEnabled`, govet analyzers `isGovetAnalyzerEnabled`, revive rules `revive(config).isEnabled`, gocritic
checks `isGocriticEnabled`, gosec rules `isGosecRuleEnabled`); its options come from `config.settingsOf(linter)` (the Options column names
the keys); a finding is suppressed when `GolangciConfigs.isExcluded(config, relPath, reportedAs(linter), message, sourceLine)` holds. Without a
configuration file the rules run with the plugin's own defaults (the native profile), not golangci's.

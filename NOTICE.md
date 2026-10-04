# Third-party notices

Go Project Support is licensed under the MIT License (`LICENSE`). It re-implements checks of other tools from their documented
behaviour. Where message texts, lists or tables are taken verbatim, where an algorithm is ported from a Go package, or where test data
is copied, the source and its license are listed here.

## staticcheck

MIT License, Copyright (c) 2016 Dominik Honnef. <https://github.com/dominikh/go-tools>

- Message texts of the SA1000-SA1032 call checks (`go-psi-ide`, package `io.github.golangsupport.ide.rules.builtin.staticcheck`):
  SA1004, SA1005, SA1006, SA1007, SA1010, SA1012, SA1013, SA1014, SA1016, SA1017, SA1018, SA1020, SA1021, SA1024, SA1029, SA1030, SA1032;
  the lists of functions each check covers, the service-name rule of SA1020 (`validateServiceName`).
- Diagnostic messages, rule titles and descriptions of the staticcheck S checks of batch B8 (`go-psi-ide`, package
  `io.github.golangsupport.ide.rules.builtin.simple`): S1000, S1001, S1005, S1006, S1008, S1011, S1016, S1017, S1018, S1021, S1023, S1029,
  S1031, S1033, S1034, S1036, S1037 — behaviour re-implemented from staticcheck v0.8.1 on the plugin's PSI.
- Diagnostic messages, rule titles and descriptions of the call and expression checks of batch B9 (same package; S1002 in
  `io.github.golangsupport.ide.rules.builtin`): S1002, S1003, S1004, S1007, S1009, S1010, S1012, S1019, S1020, S1024, S1025, S1028, S1030,
  S1032, S1035, S1038, S1039, S1040, SA6005, SA6006; the method mapping of S1038 (`checkSprintfMapping`) — behaviour re-implemented from
  staticcheck v0.8.1 on the plugin's PSI.
- Message texts of the expression checks SA4000, SA4001, SA4003, SA4012, SA4013, SA4016, SA4022, SA4024, SA4025, SA4026, SA4028, SA4032,
  SA5010 and SA9006 (same package); the `math/rand` exemptions of SA4000, the GOOS / GOARCH lists and tag matching of SA4032
  (`knowledge.KnownGOOS`, `KnownGOARCH`, `validateGOOSComparison`, `constraintsFromName`).
- Message texts of the batch-B3 checks (`go-psi-ide`, package `io.github.golangsupport.ide.rules.builtin.staticcheck`, files `GoB3*`):
  SA1001, SA1003, SA1008, SA1011, SA1015, SA1026, SA1027, SA1028, SA4015, SA4027, SA4030, SA5005, SA5012, SA6002, SA9002, SA9005, SA9007;
  the lists of functions each check covers, and the rules of `fakejson` / `fakexml` (which types `encoding/json` / `encoding/xml` reject,
  the paths in their messages), re-implemented on the plugin's types (`GoB3MarshalTypes`).
- Message texts, rule titles and descriptions of the statement checks of batch B5 (`go-psi-ide`, package
  `io.github.golangsupport.ide.rules.builtin.statements`): SA2001, SA2003, SA3001, SA4011, SA4014, SA4020, SA4021, SA4029, SA5002, SA5003,
  SA5004, SA6000, SA6001, SA6003, SA9003, SA9008, SA9010; the lists of functions and types they cover (`regexp.Match*`,
  `sort.*Slice`, `sync.Mutex.Lock` / `sync.RWMutex.RLock`) and the side-effect rules of `code.MayHaveSideEffects`; the quick-fix names
  "Replace with call to sort.…" and "Remove empty default branch".
- Message texts, rule titles and descriptions of the batch-B6 checks SA1019, SA4019, SA9004 and SA9009 (`go-psi-ide`, package
  `io.github.golangsupport.ide.rules.builtin.vet`), the quick-fix name "Add type to all constants in group", and the rules of
  `fact_deprecated` / `code.SelectorName` / `code.StdlibVersion` that SA1019 follows. The table `knowledge.StdlibDeprecations`
  (knowledge/deprecated.go of v0.8.1) is copied as `go-psi-ide/src/main/resources/lint/stdlib-deprecations.txt` by
  `tools/lint-rules/stdlib_data.py`.

## errcheck

MIT License, Copyright (c) 2013 Kamil Kisiel. <https://github.com/kisielk/errcheck>

- Default exclude list in `GoUncheckedErrorInspection` (`go-psi-ide`, package `io.github.golangsupport.ide.inspections.lint`).

## Go and golang.org/x/tools

BSD 3-Clause License, Copyright (c) 2009 The Go Authors. <https://go.dev/LICENSE>

- The parser of `go-psi-core` (`Go.bnf`, `GoParserUtil`) ports the disambiguation rules of `go/parser`; each rule names the function it
  follows (`docs/GRAMMAR.md`).
- The formatter of `go-psi-ide` (package `io.github.golangsupport.ide.formatter.printer`: `GoPrinter`, `GoLayout`, `GoAlignmentStrategy`)
  is a port of `go/printer` (printer.go, nodes.go of Go 1.27) and `text/tabwriter`.
- Test data copied byte-for-byte from GOROOT (Go 1.27.1): `testData/parser/goroot` (`src/go/parser/testdata`) and `testData/types/goroot`
  (`src/internal/types/testdata`); the file lists are in their `SOURCES.txt`. Used by tests only, not shipped in the plugin.

- Error texts of `regexp/syntax` (`GoRegexpSyntax`), `net/url.Parse` and `net.SplitHostPort` (`GoNetSyntax`), reproduced so that SA1000,
  SA1007 and SA1020 report what the Go compiler / runtime would; `strconv.Quote` formatting.
- Message texts of the vet analyzers `unmarshal` and `sigchanyzer` (`GoVetUnmarshalRule`, `GoSigchanyzerRule`) and their lists of functions.
- Message texts of the vet analyzers `ifaceassert`, `nilfunc`, `shift`, `bools`, `stringintconv` and `unsafeptr` (`GoVetIfaceAssertRule`,
  `GoVetNilFuncRule`, `GoVetShiftRule`, `GoVetBoolsRule`, `GoVetStringIntConvRule`, `GoVetUnsafePointerRule`), the names of their quick
  fixes, and the list of pure builtins of `typesinternal.NoEffects`.
- Error texts of `text/template/parse` (`GoTemplateSyntax`: its lexer and parser re-implemented to find the first error `Parse` returns,
  for SA1001); `net/textproto.CanonicalMIMEHeaderKey` (SA1008).
- Message texts of the vet analyzers `atomicalign` and `sortslice` (`GoVetAtomicAlignRule`, `GoVetSortSliceRule`), their lists of functions
  and the fixes of `sortslice`.
- Message texts of the vet analyzers `appends`, `atomic` and `defers` (`GoVetAppendsRule`, `GoVetAtomicRule`, `GoVetDefersRule`) and the
  list of `sync/atomic.Add*` functions of `atomic`.
- Message texts of the vet analyzers `stdversion`, `stdmethods`, `tests`, `directive`, `hostport`, `httpmux`, `slog`, `composites`,
  `deepequalerrors` and `reflectvaluecompare` (`go-psi-ide`, package `io.github.golangsupport.ide.rules.builtin.vet`), the names of the
  fixes of `hostport` and `composites`, and their tables: `canonicalMethods` (stdmethods), the accepted fuzz argument types (tests),
  `kvFuncs` (slog), `unkeyedLiteral` (composites), the wildcard pattern of `httpmux`. `tools/lint-rules/stdlib_data.py` ports the
  GOROOT/api parsing of `x/tools/internal/stdlib/generate.go`; its output `go-psi-ide/src/main/resources/lint/stdlib-since.txt` is
  derived from GOROOT/api/go1.*.txt (Go 1.27.1).

## Delve

MIT License, Copyright (c) 2014 Derek Parker. <https://github.com/go-delve/delve>

- The sources of delve (`third_party/delve`, a git submodule at tag v1.27.2, with its `vendor/` directory and the licenses of the vendored
  modules in it) ship inside the plugin unmodified and are built on the user's machine (`GoBundledDelve`).

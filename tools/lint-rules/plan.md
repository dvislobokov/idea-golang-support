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

### B2. staticcheck SA: stdlib call contracts, part 1 (20) — CALL, TYPES
`SA1000`, `SA1004`, `SA1005`, `SA1006`, `SA1007`, `SA1010`, `SA1012`, `SA1013`, `SA1014`, `govet:unmarshal`, `SA1016`, `SA1017`, `govet:sigchanyzer`,
`SA1018`, `SA1020`, `SA1021`, `SA1024`, `SA1029`, `SA1030`, `SA1032`.

### B3. staticcheck SA: stdlib call contracts, part 2 (19) — CALL, TYPES
`SA1001`, `SA1003`, `SA1008`, `SA1011`, `SA1015`, `SA1026`, `SA1027`, `govet:atomicalign`, `SA1028`, `govet:sortslice`, `SA5005`, `SA5012`, `SA6002`,
`SA9002`, `SA9005`, `SA9007`, `SA4027`, `SA4030`, `SA4015`.

### B4. staticcheck SA + govet: suspicious expressions (20) — EXPRESSION, TYPES
`SA4000`, `SA4001`, `SA4003`, `SA4012`, `SA4013`, `SA4016`, `SA4022`, `SA4024`, `SA4025`, `SA4026`, `SA4028`, `SA4032`, `SA9006`, `SA5010`,
`govet:ifaceassert`, `govet:nilfunc`, `govet:shift`, `govet:bools`, `govet:stringintconv`, `govet:unsafeptr`.

### B5. staticcheck SA + govet: suspicious statements (20) — STATEMENT, SYNTAX/TYPES
`SA2001`, `SA2003`, `SA3001`, `SA4011`, `SA4014`, `SA4020`, `SA4029`, `SA4021`, `govet:appends`, `SA5002`, `SA5003`, `SA5004`, `SA6000`, `SA6003`,
`SA9003`, `SA9008`, `SA9010`, `govet:atomic`, `govet:defers`, `SA6001`.

### B6. govet remainder and deprecation (14) — FUNCTION / FILE / CALL, TYPES + PROJECT_INDEX
`SA1019` (Deprecated: paragraphs through stubs; also strikethrough highlighting), `govet:stdversion`, `govet:stdmethods`, `govet:tests`,
`govet:directive`, `govet:hostport`, `govet:httpmux`, `govet:slog`, `govet:composites`, `govet:deepequalerrors`, `govet:reflectvaluecompare`, `SA4019`,
`SA9009`, `SA9004`.

### B7. Flow-based checks (16) — FUNCTION, FLOW (SSA-heavy ones as approximations)
`SA4004`, `SA4009`, `SA5000`, `SA5007`, `revive:unconditional-recursion`, `SA4005`, `SA4008`, `SA4010`, `SA4023`, `SA1025`, `SA1023`, `SA1031`,
`govet:unusedwrite`, `makezero`, `rowserrcheck`, `goland:GoMaybeNil` (interprocedural nil summaries, an approximation).

### B8. staticcheck S (simple): statement rewrites (17) — STATEMENT, SYNTAX/TYPES, each with a quick fix
`S1000`, `S1001`, `S1005`, `S1006`, `S1008`, `S1011`, `S1016`, `S1017`, `S1018`, `S1021`, `S1023`, `S1029`, `S1031`, `S1033`, `S1034`, `S1036`, `S1037`.

### B9. staticcheck S (simple): call and expression rewrites (20) — CALL / EXPRESSION, TYPES, each with a quick fix
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

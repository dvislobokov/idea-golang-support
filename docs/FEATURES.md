# Feature roadmap

What go-psi offers today against three references (the LSP 3.17 surface, what gopls provides on top
of it, and GoLand's observable behaviour), plus features beyond them, and the order to build them.
`docs/IDE-FEATURES.md` describes how the implemented features work; this file tracks what is
missing. GoLand is a behavioural reference only (CLAUDE.md hard rules: no decompiling or copying).

Legend:
- Status: ✅ done, 🟡 partial (gaps noted), ❌ missing, 🗓️ planned.
- Effort: **S** (one agent session), **M** (2-3 sessions), **L** (a milestone of its own).
- Needs:
  - `go`: running the `go` binary outside the project model, which CLAUDE.md currently forbids
    (decision D1);
  - `dlv`: Delve (decision D2);
  - `plugin`: an optional IDE plugin dependency;
  - `perf`: runs in the highlighting daemon, so it must be measured with the benchmarks and the
    UI robot before and after.

## 1. LSP 3.17 surface

| LSP method | IntelliJ mechanism | Status | Notes / gap | Effort |
|---|---|---|---|---|
| completion, completionItem/resolve | `completion.contributor` | ✅ | smart (type-filtered) completion, chains one level deep, postfix templates (waves 1 and 3) | |
| hover | `psiTargetProvider` documentation | ✅ | doc links `[Name]` render as code, not links | S |
| signatureHelp | `codeInsight.parameterInfo` | ✅ | | |
| declaration / definition | references + `gotoDeclaration` | ✅ | | |
| typeDefinition | `typeDeclarationProvider` | ✅ | | |
| implementation | `definitionsScopedSearch`, line markers, `gotoSuper` | 🟡 | generic interfaces, sub-interfaces | M |
| references | `referencesSearch`, find usages | ✅ | | |
| documentHighlight | `highlightUsagesHandlerFactory` | ✅ | | |
| documentSymbol | structure view, breadcrumbs | ✅ | | |
| workspaceSymbol | `gotoSymbolContributor` / `gotoClassContributor` | ✅ | | |
| codeAction (quick fix) | `LocalQuickFix` | 🟡 | fix kinds: add import, remove import, optimize imports, unused variable (remove, rename to `_`, replace with `_ =`), wrap in conversion; see section 3 | M |
| codeAction (refactor / source) | intentions, refactorings | ❌ | see sections 3 and 4 | L |
| codeLens | Code Vision (`codeInsight.daemonBoundCodeVisionProvider`) | ✅ | usages / implementations counts (`ide.codevision`); run test stays with the host's run line markers | |
| documentLink | references in import paths, `//go:embed` | ✅ | import paths and embed patterns navigate (0.2.35) | |
| formatting / rangeFormatting | `lang.formatter` (gofmt-compatible) | ✅ | | |
| onTypeFormatting | `typedHandler`, `enterHandler` | 🟡 | platform defaults only; no Go-specific handlers | S |
| rename, prepareRename | `renamePsiElementProcessor`, `namesValidator` | 🟡 | package rename out of scope | M |
| foldingRange | `lang.foldingBuilder` | ✅ | | |
| selectionRange | `extendWordSelectionHandler` | 🟡 | platform PSI defaults; no Go-specific handlers (call args, struct literal fields) | S |
| callHierarchy | `callHierarchyProvider` | ✅ (0.2.45) | `ide.hierarchy`: callers (through interfaces, "via Iface") and callees | |
| typeHierarchy | `typeHierarchyProvider` | ✅ (0.2.45) | interface ↔ implementations, embedding; implicit interface-to-interface satisfaction not linked | |
| semanticTokens | annotator (`GoSemanticHighlightingAnnotator`) | ✅ | | |
| linkedEditingRange | not applicable to Go | — | | |
| inlayHint | `codeInsight.declarativeInlayProvider` | ✅ (wave 1, 0.2.9) | `ide.hints`, see section 2 | |
| inlineValue | debugger | ❌ | needs `dlv` | L |
| diagnostics (push/pull) | inspections + annotator | 🟡 | 17 inspections (checker, Printf, struct tags, exhaustive switch, context, errors, doc comments, build constraints, …); data-flow and the rest of vet missing (section 5) | L |
| workspace/willRenameFiles, file events | VFS / PSI listeners | ✅ | | |
| documentColor, moniker | not relevant to Go | — | | |

## 2. Editor productivity

| Feature | Status | Notes | Effort | Needs |
|---|---|---|---|---|
| Completion snippets: `iferr`, `for range`, `switch`, `select` | ✅ | `GoSnippets` | | |
| Postfix templates: `.err`, `.nn`, `.nil`, `.if`, `.not`, `.for`, `.forr`, `.var`, `.return`, `.par`, `.sort`, `.print`, … | ✅ (wave 1, 0.2.2) | `lang.GoPostfixTemplates` of the host: the expression and its type from the PSI, `.forr` is the reverse index loop, `.for` the range loop | | |
| Live templates: `fori`, `forr`, `meth`, `func`, `test`, `bench`, `fuzz`, `main`, `err`, `json` tag (~40) | ✅ (wave 1, 0.2.3) | `liveTemplates/Go.xml` of the host; contexts Go statement / top level / struct field / expression from the PSI | | |
| Inlay hints: parameter names, `:=` and range variable types, `iota` constant values, composite literal field names and types, type parameter instantiation (`f[int]`) | ✅ (wave 1, 0.2.9) | `ide.hints`, behind `GoIdeFeature.INLAY_HINTS` (gopls stands down per file); `GoInlayHintsBenchmark` | | |
| Struct size/alignment inlay, padding warning, "reorder fields" fix | ✅ (wave 1, 0.2.10) | `ide.hints` (`24 bytes, 11 padding (16 if reordered)`), the fix is Reorder Fields of the host | | |
| Scope-aware `select`: Alt+Enter "Fill select", keyword templates `select` / `for { select {…} }`, and grey inline text after `select {` — cases from the scope (`<-ctx.Done()` for a `context.Context`, `<-t.C` for timers/tickers, `v := <-ch` per channel, `ch <- zero` for send-only, `<-time.After(d)` for a `time.Duration`) | ✅ (step 9) | typed, never by name | | |
| Scope-aware `switch`: grey `case`s after `switch x {` over an `iota` enum (all constants) or a type switch over an interface (implementing types), Alt+Enter "Fill switch" | ✅ (step 9) | pairs with the exhaustive-switch check of section 5 | | |
| `make` by expected type: completion inside `make(` (`chan T`, `[]T, 0, len(x)`, `map[K]V`), templates `make(chan T[, n])` / slice / map, `v, ok := <-ch`, `for v := range ch`, `close(ch)` / `defer close(ch)` for a channel made in the function | ✅ (step 9) | `expectedTypeAt` | | |
| Smart completion (Ctrl+Shift+Space) by expected type, second-level `x.F.M()` chains | ✅ (wave 3, 0.2.26–0.2.27) | `GoSmartProvider` + `GoSmartLiterals` (filter by `expectedTypeAt`, literals for the type), `GoChainCandidates` (one level, capped) | | |
| Smart `return`: values by result type from the scope else zero values, `fmt.Errorf("…: %w", err)` when `err` is in scope; the same candidates behind "Fill return values" | ✅ (step 9) | `lang.GoReturnValues` on the PSI | | |
| Type-aware idioms as inline grey text: `if err != nil {…}` after `x, err :=`, `defer f.Close()`, `defer mu.Unlock()` | ✅ (step 9) | `lang.GoIdioms` on the PSI | | |
| Completion of map keys, struct tag keys and options, `go.mod` versions from GOMODCACHE | 🟡 | struct tags: host `GoStructTagCompletionContributor` (25 keys, validator and gorm rules), naming style of the other fields and autopopup since 0.2.36; map keys and `go.mod` versions missing | S | |
| Completion of members of unimported packages (`Println` → `fmt.Println` + import) | ✅ (wave 3, 0.2.28) | project packages typed from the stub index (`GoProjectMemberCandidates`); stdlib and dependencies by the host catalogue | | |
| Smart Enter (complete statement) | ✅ (wave 1, 0.2.4) | `lang.GoSmartEnter` of the host, on the PSI | | |
| Move statement / element up/down | ✅ (wave 1, 0.2.7) | `ide.editor.GoStatementMover` | | |
| Surround with (`if`, `for`, `func(){}()`, `if err != nil`, `for range`, `(…)`, `!(…)`) | ✅ (wave 1, 0.2.5) | `ide.editor.GoSurroundDescriptors` | | |
| Unwrap / remove (`if`, `for`, `else`, `func(){}()`, `defer`/`go`, `case`, braces) | ✅ (wave 1, 0.2.6) | `ide.editor.GoUnwrapDescriptor` | | |
| Join Lines: `var x T` + `x = v` → `x := v`, string concatenation, call arguments / literal elements | ✅ (wave 1, 0.2.8) | `ide.editor.GoJoinLinesHandler` | | |
| Add imports on paste (resolve pasted `pkg.X` against the source file's imports; external text through the stdlib catalogue) | ✅ (wave 1, 0.2.11) | `ide.editor.paste`, EP `pasteImportResolver` answered by the host catalogue | | |
| Spelling in identifiers, comments and strings | ✅ (wave 1, 0.2.12) | `ide.spelling`, optional dependency on `com.intellij.modules.spellchecker` | | |
| Extend selection with Go-specific steps | 🟡 | | S | |
| Paste JSON as struct, struct → JSON sample | 🟡 | JSON → types: host Alt+Insert / keyword template Type from JSON (`GoJsonTypes`); struct → JSON sample missing | S | |
| Doc comments (Go 1.19 syntax): navigable `[pkg.Name]` links, rename updates links, lists and headings | ✅ (wave 1, 0.2.13) | `ide.documentation.GoDocLinks`: references in `//` doc comments, Quick Documentation links | | |
| Goimports-style grouping (std / third-party / `-local`) in Optimize Imports | ✅ (wave 3, 0.2.25) | `GoImportGroups`: Optimize Imports regroups, auto-import inserts into the group; local = main module(s) | | |

## 3. Quick fixes, intentions, generation

| Feature | Status | Notes | Effort |
|---|---|---|---|
| Add import, remove/optimize imports, unused variable, convert | ✅ | | |
| Create function / method / field / variable / type from usage | ✅ (wave 3, 0.2.29) | `GoCreateFromUsageIntentions` (typed, gate CODE_ACTIONS) | |
| Implement interface (stub methods), with the type picker | ✅ (wave 3, 0.2.30) | host Ctrl+I with the picker; typed quick fix on "does not implement" (`GoImplementMissingMethodsFix`); the host chooser uses `GoImplementStubs` since 0.2.31 | |
| Fill struct literal with fields (zero values) | ✅ | `ide.intentions`: Fill all fields / Fill required fields (step 9 F); gopls `fillstruct` | S |
| Fill switch: missing `case`s of `iota` enums and sealed interfaces (see section 5) | ✅ | `GoFillSwitchIntention`: enum constants, implementing types of an interface; the exhaustiveness inspection is `GoExhaustiveSwitch` (section 5) | S |
| Fill return values (`return` with too few values) | ✅ | `GoFillReturnValuesIntention`, also "Add missing return" | S |
| Handle error: `if err != nil { return ..., err }`, wrap with `fmt.Errorf("...: %w", err)` | ✅ | `GoHandleErrorIntention`, `GoWrapErrorIntention` | S |
| Fill select: `ctx.Done()`, channel receives/sends, timer/ticker `C`, `time.After(d)` from the scope, optional `default` | ✅ | `GoFillSelectIntention`, `GoFillSelectWithDefaultIntention` | S |
| Invert / flip `if`, merge nested `if`, `if` ↔ `switch` | ✅ (wave 2, 0.2.20) | `GoIfIntentions`: invert (also early return / continue), merge, split condition, if ↔ switch | |
| Split / join `var` declarations, `var x T = v` ↔ `x := v` | ✅ (wave 2, 0.2.21) | `GoDeclarationIntentions`: split / group, join declaration and assignment (shared with Join Lines), `:=` ↔ `var` | |
| Change quote (interpreted ↔ raw string) | ✅ (wave 2, 0.2.19) | `GoChangeQuoteIntention` (`strconv`-like quoting rules) | |
| Remove unused parameter (with call sites) | ✅ (0.2.49) | `GoUnusedParameter`, fixes "Rename to _" / "Remove unused parameter" | |
| Add/convert struct tags for all fields | ✅ | host Generate → Struct Tags (`GoGenerateStructTagsAction`: json/yaml/xml/db/mapstructure, case styles) | |
| Generate: constructor, getters/setters, `String()` for `iota` enums (`stringer` without the tool), `Equal` | ✅ (wave 3, 0.2.23–0.2.24) | host `GoGenerateActions` (Alt+Insert) | |
| Generate table-driven test for a function | ✅ | host Generate → Test (`GoGenerators.testFunction`) | |

## 4. Refactorings

| Refactoring | Status | Effort |
|---|---|---|
| Rename (locals, package-level, fields, methods with interface propagation) | ✅ | |
| Rename package (directory, package clause, import paths in the module) | ✅ (0.2.48) | |
| Introduce variable / constant | ✅ (0.2.46) | |
| Extract function / method (free variables, results, `return` / `break` handling) | ✅ (0.2.55) | no dialog, `if err != nil` tails not special-cased |
| Inline variable / function call | ✅ (0.2.56) | one-statement functions; multi-statement bodies 🗓️ |
| Change signature (parameters, results, call sites, interface implementations) | ✅ (0.2.57, hierarchy 0.2.62) | results at call sites 🗓️; Add Method to Interface (0.2.63) |
| Move declaration to another file / package (imports, exportedness) | ✅ (0.2.58) | no automatic export |
| Safe delete | ✅ (0.2.47, parameters 0.2.54) | |

## 5. Analysis

Each analysis needs the type system to agree with `go/types`; false positives are worse than no
check. They go through the corpus gates (no new diagnostics on GOROOT/src and GOMODCACHE). Many
fit the declarative rules engine planned in `docs/RULES.md`.

| Check | Status | Notes | Effort |
|---|---|---|---|
| Unresolved, unused import/variable/label, type mismatch, call arity, duplicates, generics, missing return | ✅ | | |
| Exhaustive `switch` over `iota` enums and sealed interfaces | ✅ (wave 2, 0.2.14) | `GoExhaustiveSwitchInspection`, fix "Add missing cases" shares `GoSwitchCases` with Fill switch; interfaces of the project content stand in for sealed ones; bit-flag enums skipped; weak warning (2026-10-03) | |
| `Printf` family: verb vs argument type, argument count, `%w` only in `Errorf`, completion of verbs; user wrappers detected like vet | ✅ (wave 2, 0.2.15, 0.2.22) | `ide.inspections.printf` (pure parser + vet tables), `GoPrintfInspection`, verb completion `GoFormatVerbCompletion`; wrappers within the call's package, depth 3 | |
| Per-function data-flow framework: CFG, liveness, reaching definitions, nilness | ✅ (wave 4, 0.2.37) | `semantic.flow` (`GoControlFlow`, `GoDataflowSolver`, `GoLiveness`, `GoReachingDefinitions`, `GoNilness`); corpus gate `goroot-src-flow.json` | |
| Error flow: overwritten unchecked, wrong `err` checked, nilerr and its inverse, `defer` before the check, shadowed `err`, result used before the check | ✅ (wave 4, 0.2.38) | `ide.inspections.flow`; "error assigned and never checked" is `GoUnusedResult` / errcheck of golangci-lint | |
| Nil flow: dereference of a nil value, impossible `nil` comparison, `return nil, nil` | ✅ (wave 4, 0.2.39) | `GoNilDereference`, `GoImpossibleNilCheck`, `GoNilValueNilError` (opt-in) | |
| Resources: response body / `sql.Rows` not closed, lost cancel, context not propagated | ✅ (wave 4, 0.2.41) | `GoBodyNotClosed`, `GoRowsNotClosed`, `GoLostCancel`, `GoContextNotPropagated` | |
| Concurrency: copying locks (`copylocks`), lock not released, `wg.Add` inside the goroutine, loop variable capture for `go` < 1.22, send on a closed channel, `t.Fatal` in a goroutine | ✅ (wave 4, 0.2.42) | `GoCopyLocks`, `GoLockNotReleased`, `GoWaitGroupAddInGoroutine`, `GoLoopClosure`, `GoSendAfterClose`, `GoTestingGoroutine` | |
| `context.Context` not first, lost or replaced by `context.Background()` | ✅ (wave 2, 0.2.17) | `GoContextPlacementInspection`, fixes "Use ctx" | |
| Struct tags: syntax, duplicate keys/names | ✅ (wave 2, 0.2.16) | `GoStructTagInspection` (vet `structtag` + repeated names, unexported fields with `json`), fixes "Fix quoting", "Remove duplicate key" | |
| `errors.As` with a non-pointer target, `errors.Is` vs `==` | ✅ (wave 2, 0.2.18) | `GoErrorsPackageInspection` (vet `errorsas`; sentinel comparison is a weak warning) | |
| Unreachable code, self-assignment, ineffectual assignment, unused pure results, `defer` in a loop | ✅ (wave 4, 0.2.40, 0.2.43) | `GoUnreachableCode`, `GoSelfAssignment`, `GoIneffectualAssignment`, `GoUnusedResult` (fix also on the compiler's unused `append`), `GoDeferInLoop` | |
| Unused exported declarations across the project, import cycles, `internal/` violations | ✅ (0.2.61) | `ide.inspections.project`: GoImportCycle, GoInternalImport, GoUnusedExported (opt-in) | |
| Doc comment lint (exported symbol without a comment, comment not starting with the name) | ✅ (0.2.32) | `GoDocCommentInspection`, opt-in weak warning, fixes Add doc comment / Start comment with 'Name' | |
| Build constraints: `//go:build` expression syntax, unknown GOOS/GOARCH | ✅ (0.2.33) | `GoBuildConstraintInspection` (vet `buildtag`; parser `GoBuildConstraintEvaluator` of the project model), `+build` → `//go:build` fix | |
| `go.mod`: `replace` to a missing path, duplicate / self requires, `go` / `toolchain` versions, `vendor/` out of sync, go.work `use` | ✅ (0.2.44) | host `mod.GoModChecks` + four inspections; unused requires `GoModUnused` (0.2.52, build tags not considered) | |

## 6. Languages inside Go strings

| Feature | Status | Notes | Effort | Needs |
|---|---|---|---|---|
| RE2 regular expressions in `regexp.Compile`/`MustCompile` (highlighting, RE2-specific validation, Check RegExp) | ✅ (0.2.50) | `ide.injection`: `GoRegExpInjector`, `GoRegExpLanguageHost`, `GoRegExpAnnotator`; literal first argument only | | |
| `text/template` / `html/template`: highlighting, navigation to fields of the data type | ❌ | own lexer/parser | L | |
| SQL in `database/sql` calls | ✅ (0.2.53) | `ide.injection.sql.GoSqlInjector` (optional Database plugin): database/sql, sqlx, pgx v5, `*Query`/`*SQL` consts | | plugin (Database) |
| `time.Format` layouts: validation, hint of the rendered example | ✅ (0.2.34) | `GoTimeLayoutInspection`, `GoTimeLayoutHintsProvider`; the inspection checks literals only | | |
| JSON in raw strings | ✅ (0.2.50) | `GoJsonInjector` (optional JSON plugin): Unmarshal / Valid / NewDecoder arguments, `…json…`-named raw literals | | |

## 7. Go-specific files and directives

| Feature | Status | Effort |
|---|---|---|
| `go.mod` / `go.work` PSI, navigation | 🟡 (model; `go.work` multi-module editing support to check) | M |
| `//go:embed`: pattern navigation and validation | ✅ (0.2.35) | `ide.directives`, inspection `GoEmbedDirective` |
| `//go:linkname`, `//go:generate` navigation; run `go generate` | 🟡 | navigation since 0.2.35; running `go generate` from the directive missing (needs `go`) |
| Assembly `.s`: `TEXT ·Func` ↔ Go declaration without body | ✅ (0.2.60) | `ide.asm` |
| cgo: `import "C"` preamble, `C.` symbols | ❌ | L |
| Status-bar widget for GOOS / GOARCH / build tags, re-evaluating the analysis | ✅ (0.2.51) | |

## 8. Run, test, debug (outside PSI)

Since the transplant (2026-10-02) the host plugin idea-golang-support provides all of this outside go-psi (packages `run`, `testing`,
`debugger`, `monitor`; see its CLAUDE.md and ROADMAP.md). go-psi only has to feed it PSI inputs where it still uses the text scanner
(MIGRATION.md step 9: run gutters, Go to Test, generators).

| Feature | Status | Effort | Needs |
|---|---|---|---|
| Run configurations: `go run`, `go build`, `go test` (package, file, function, subtest) | host | | `go` |
| Run gutter icons on `main`, `TestXxx`, `BenchmarkXxx`, `FuzzXxx`, subtests `t.Run("name")`, table-test rows | host (text scanner; PSI inputs at step 9) | S | `go` |
| Test tree from `go test -json`, rerun failed, benchmark results | host | | `go` |
| Coverage | host | | `go` |
| Navigate test ↔ subject, `Example` functions from docs | host (test ↔ subject; `Example` from docs missing) | S | |
| Debugger | host (own DAP client over `dlv dap`) | | `dlv` |

## 9. Performance and platform

| Feature | Status | Notes | Effort |
|---|---|---|---|
| Per-package trackers, library caches independent of project edits | ✅ | CHANGELOG 2026-10-02 | |
| One inference cache per function body | ✅ | CHANGELOG 2026-10-02 | |
| Lazy reparse of function bodies | ✅ | `GoLazyBlockElementType`, CHANGELOG 2026-10-02 | |
| Shared indexes for GOROOT per Go version | ❌ | first open spends 3-5 s indexing GOROOT (UI robot P7); platform shared-indexes mechanism | M |
| Headless inspections (CLI, SARIF report for CI) | ✅ (0.2.59; Go menu Inspect Project / Export SARIF 0.2.66) | `go-inspect` app starter (`ci.GoInspectStarter`), SARIF 2.1.0, `tools/ci/go-inspect.sh|cmd`, `docs/CI.md` | |
| ML completion ranking, name suggestions | 🗓️ | `docs/ML.md` | L |
| errcheck while typing | ✅ (0.2.64) | `GoUncheckedError`; `defer` / `go` and `-blank` not checked, as errcheck by default | |
| golangci-lint optional (off by default), custom linters (golangci JSON / SARIF) | ✅ (0.2.65) | host `lint` | |
| Declarative lint rules, ruleguard importer | 🗓️ | `docs/RULES.md` | L |

## 10. Decisions needed

- **D1. The `go` binary outside the project model.** Decided by the host: `go` runs only in
  user-triggered actions (run configurations, tests, coverage, `go generate`, build, lint), never
  in analysis, highlighting or indexing; go-psi keeps its pure fallback (`DefaultGoToolchainProvider`).
- **D2. Delve.** Decided by the host: its own DAP client over `dlv dap` (package `debugger`); not a go-psi concern.
- **D3. Optional plugin dependencies** (Database for SQL injection) through `<depends optional>`.

## 11. Order

Each wave is measured with the benchmarks and the UI robot before and after, like the cache work.

1. **Wave 1 (S, visible daily):** done 2026-10-02 (versions 0.2.2–0.2.13, one feature per version): postfix and live templates, inlay hints, struct size inlay, Smart
   Enter, statement mover, surround with, unwrap, Join Lines, imports on paste, spelling, doc links; Code Vision counts came with step 8i. Robot-checked
   (`tools/ui-robot/scripts/editop.js`).
2. **Wave 2 (analysis with the highest value):** done 2026-10-02 (versions 0.2.14–0.2.22): exhaustive switch with fill switch, `Printf`
   checks and verb completion, struct tags, `context.Context` placement, `errors.Is`/`errors.As`, change quote, `if` intentions, `var` split/join;
   fill struct/returns and handle error came with step 9.
3. **Wave 3 (code creation):** done 2026-10-03 (versions 0.2.23–0.2.30): create from usage, implement missing methods, Generate `String()` for enums
   and `Equal`, goimports grouping, smart and chain completion, completion of unimported project members.
4. **Wave 4 (data flow):** done 2026-10-03 (versions 0.2.37–0.2.43): per-function data-flow framework (`semantic.flow`), 25 checks of error and nil flow,
   resources, concurrency and dead code; noise reviewed on GOROOT/src by `:go-psi-ide:corpusTest`. Robot-checked (`store/wave6.go`, `store/wave7.go`).
5. **Wave 5 (platform):** call and type hierarchy (0.2.45) and headless inspections (0.2.59) done 2026-10-03; shared indexes for GOROOT remain.
6. **Wave 6:** done by the host (section 8); what remains is the PSI inputs of step 9 of MIGRATION.md.
7. **Wave 7 (refactorings):** introduce variable / constant (0.2.46), safe delete (0.2.47), rename package (0.2.48) and remove unused parameter
   (0.2.49), safe delete of parameters (0.2.54), extract (0.2.55), inline (0.2.56), change signature (0.2.57) and move (0.2.58) done 2026-10-03.
8. **Later:** string languages (RE2, templates, SQL), assembly and cgo, Delve (after D2).

## 12. Beyond GoLand (host plugin, after the migration)

What GoLand does not have and what the base of this plugin makes cheap. Not go-psi work alone: most of it lives in the host
(`monitor`, `testing`, `lint`, `settings`) and uses go-psi for types and declarations.

| Feature | Base | Effort |
|---|---|---|
| Exhaustive `switch` over `iota` enums and sealed interfaces, with "fill switch" (section 5) | `GoImplementations`, constants of a type from the stubs | M |
| Error flow and nil flow checks (section 5, wave 4) | per-function data-flow framework over `GoSemanticService` | M |
| Struct size / alignment inlay with padding warning and "reorder fields" fix (section 2) | `GoSizes` | S |
| Declarative rule engine for project rules (`docs/RULES.md`): YAML rules over the PSI, ruleguard importer | types and resolve of go-psi | L |
| Benchmarks as first-class: history of results per function, benchstat-style comparison with a base, regression marker in the gutter | host `testing` (`go test -json`), stubs for the function list | M |
| Goroutine tree with states and GC / scheduler timeline in Go Monitor; pprof flame graph of a test or benchmark in an editor tab | host `monitor` (`GoRuntimeTrace`, `GoSnapshot`, `GoProfiles`) | M |
| Test failure diff: expected / actual of `go test` output side by side, navigable | host `testing` console | S |
| Analysis for another GOOS / GOARCH without changing the environment: a switch in the status bar over the toolchain of the project model | `GoToolchainProvider` (host override), `GoSettings.analysisGoos/analysisGoarch` | ✅ (0.2.51) `GoPlatformWidget`; gopls not switched |
| Format on type (gofmt-compatible, no process) and instant Reformat (step 8j) | `lang.formatter` of go-psi-ide | S |
| Go next to other languages in IDEs that have them: Protobuf / gRPC stubs ↔ Go navigation, SQL in strings (section 6), `html/template` fields | optional plugin dependencies (D3) | M |
| Full function without gopls and without the network (MIGRATION.md step 12) on large monorepositories: indexes by build list only | library roots policy (step 7) | — |

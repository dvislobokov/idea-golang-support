# Feature roadmap

What go-psi offers today against three references (the LSP 3.17 surface, what gopls provides on top
of it, and GoLand's observable behaviour), plus features beyond them, and the order to build them.
`docs/IDE-FEATURES.md` describes how the implemented features work; this file tracks what is
missing. GoLand is a behavioural reference only (CLAUDE.md hard rules: no decompiling or copying).

Legend:
- Status: **done**, **partial** (gaps noted), **missing**.
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
| completion, completionItem/resolve | `completion.contributor` | partial | no smart (type-filtered) completion, no second-level chains, no postfix | M |
| hover | `psiTargetProvider` documentation | done | doc links `[Name]` render as code, not links | S |
| signatureHelp | `codeInsight.parameterInfo` | done | | |
| declaration / definition | references + `gotoDeclaration` | done | | |
| typeDefinition | `typeDeclarationProvider` | done | | |
| implementation | `definitionsScopedSearch`, line markers, `gotoSuper` | partial | generic interfaces, sub-interfaces | M |
| references | `referencesSearch`, find usages | done | | |
| documentHighlight | `highlightUsagesHandlerFactory` | done | | |
| documentSymbol | structure view, breadcrumbs | done | | |
| workspaceSymbol | `gotoSymbolContributor` / `gotoClassContributor` | done | | |
| codeAction (quick fix) | `LocalQuickFix` | partial | fix kinds: add import, remove import, optimize imports, unused variable (remove, rename to `_`, replace with `_ =`), wrap in conversion; see section 3 | M |
| codeAction (refactor / source) | intentions, refactorings | missing | see sections 3 and 4 | L |
| codeLens | Code Vision (`codeInsight.daemonBoundCodeVisionProvider`) | done | usages / implementations counts (`ide.codevision`); run test stays with the host's run line markers | |
| documentLink | references in import paths, `//go:embed` | partial | import paths navigate; embed patterns do not | S |
| formatting / rangeFormatting | `lang.formatter` (gofmt-compatible) | done | | |
| onTypeFormatting | `typedHandler`, `enterHandler` | partial | platform defaults only; no Go-specific handlers | S |
| rename, prepareRename | `renamePsiElementProcessor`, `namesValidator` | partial | package rename out of scope | M |
| foldingRange | `lang.foldingBuilder` | done | | |
| selectionRange | `extendWordSelectionHandler` | partial | platform PSI defaults; no Go-specific handlers (call args, struct literal fields) | S |
| callHierarchy | `callHierarchyProvider` | missing | | M |
| typeHierarchy | `typeHierarchyProvider` | missing | interface ↔ implementations, embedding | M |
| semanticTokens | annotator (`GoSemanticHighlightingAnnotator`) | done | | |
| linkedEditingRange | not applicable to Go | — | | |
| inlayHint | `codeInsight.declarativeInlayProvider` | missing | see section 2 | S |
| inlineValue | debugger | missing | needs `dlv` | L |
| diagnostics (push/pull) | inspections + annotator | partial | 10 inspections; vet-class analyzers missing (section 5) | L |
| workspace/willRenameFiles, file events | VFS / PSI listeners | done | | |
| documentColor, moniker | not relevant to Go | — | | |

## 2. Editor productivity

| Feature | Status | Notes | Effort | Needs |
|---|---|---|---|---|
| Completion snippets: `iferr`, `for range`, `switch`, `select` | done | `GoSnippets` | | |
| Postfix templates: `.err`, `.nn`, `.nil`, `.if`, `.not`, `.for`, `.forr`, `.var`, `.return`, `.par`, `.sort`, `.print` | missing | `codeInsight.template.postfixTemplateProvider` | S | |
| Live templates: `fori`, `forr`, `meth`, `func`, `test`, `bench`, `fuzz`, `main`, `err`, `json` tag | missing | `defaultLiveTemplates` + Go template context | S | |
| Inlay hints: parameter names, `:=` and range variable types, `iota` constant values, composite literal field names and types, type parameter instantiation (`f[int]`) | missing | the same hint set as gopls; types of every `:=` in the visible range go through the per-body inference cache, measured with the UI robot | M | perf |
| Struct size/alignment inlay, padding warning, "reorder fields" fix | missing | uses `GoSizes`; GoLand does not have it | S | |
| Scope-aware `select`: Alt+Enter "Fill select", keyword templates `select` / `for { select {…} }`, and grey inline text after `select {` — cases from the scope (`<-ctx.Done()` for a `context.Context`, `<-t.C` for timers/tickers, `v := <-ch` per channel, `ch <- zero` for send-only, `<-time.After(d)` for a `time.Duration`) | done (step 9) | typed, never by name | | |
| Scope-aware `switch`: grey `case`s after `switch x {` over an `iota` enum (all constants) or a type switch over an interface (implementing types), Alt+Enter "Fill switch" | done (step 9) | pairs with the exhaustive-switch check of section 5 | | |
| `make` by expected type: completion inside `make(` (`chan T`, `[]T, 0, len(x)`, `map[K]V`), templates `make(chan T[, n])` / slice / map, `v, ok := <-ch`, `for v := range ch`, `close(ch)` / `defer close(ch)` for a channel made in the function | done (step 9) | `expectedTypeAt` | | |
| Smart completion (Ctrl+Shift+Space) by expected type, second-level `x.F.M()` chains | missing | `GoExpectedTypes` exists; plan: `expectedTypeAt(element)` in `semantic.api` (assignment, argument by position, `return` by index, literal element, binary operand, `case`, channel, condition), assignability filter in SMART, weigher boost in BASIC, chains one level deep with a candidate cap | M | perf |
| Smart `return`: values by result type from the scope (`err` for `error`, nearest variable of the type) else zero values, `fmt.Errorf("…: %w", err)` when `err` is in scope; the same candidates behind a "Fill return values" fix | missing | the host has a text version (`GoIdioms.returnValues`, step 9 moves it to PSI); `GoSnippets.iferrText` has the zero values | S | |
| Type-aware idioms as inline grey text: `if err != nil {…}` after `x, err :=`, `defer f.Close()` when the type has `Close() error`, `defer mu.Unlock()` after `Lock()` | missing | the host shows them by text rules (`GoInlineIdiomsProvider`); step 9 | S | |
| Completion of map keys, struct tag keys and options, `go.mod` versions from GOMODCACHE | missing | | S | |
| Completion of members of unimported packages (`Println` → `fmt.Println` + import) | missing | gopls unimported completion; unimported package names are done | M | perf |
| Smart Enter (complete statement) | missing | `lang.smartEnterProcessor` | S | |
| Move statement / element up/down | missing | `statementUpDownMover` | S | |
| Surround with (`if`, `for`, `func(){}()`, `if err != nil`) | missing | `lang.surroundDescriptor` | S | |
| Unwrap / remove (`if`, `for`, `else`, `func(){}()`, `defer`/`go`) | missing | `lang.unwrapDescriptor` | S | |
| Join Lines: `var x T` + `x = v` → `x := v`, string concatenation, call arguments | missing | `joinLinesHandler` | S | |
| Add imports on paste (resolve pasted `pkg.X` against the source file's imports) | missing | `copyPastePostProcessor` | S | |
| Spelling in identifiers, comments and strings | missing | `spellchecker.support`; skip import paths, struct tags, directives | S | |
| Extend selection with Go-specific steps | partial | | S | |
| Paste JSON as struct, struct → JSON sample | missing | | S | |
| Doc comments (Go 1.19 syntax): navigable `[pkg.Name]` links, rename updates links, lists and headings | partial | rendering exists, links do not navigate | S | |
| Goimports-style grouping (std / third-party / `-local`) in Optimize Imports | partial | sorting only, no regrouping | S | |

## 3. Quick fixes, intentions, generation

| Feature | Status | Notes | Effort |
|---|---|---|---|
| Add import, remove/optimize imports, unused variable, convert | done | | |
| Create function / method / field / variable / type from usage | missing | gopls `undeclared name` fix | M |
| Implement interface (stub methods), with the type picker | missing | gopls `stubmethods` | M |
| Fill struct literal with fields (zero values) | done | `ide.intentions`: Fill all fields / Fill required fields (step 9 F); gopls `fillstruct` | S |
| Fill switch: missing `case`s of `iota` enums and sealed interfaces (see section 5) | done | `GoFillSwitchIntention`: enum constants, implementing types of an interface; the exhaustiveness inspection is still missing | S |
| Fill return values (`return` with too few values) | done | `GoFillReturnValuesIntention`, also "Add missing return" | S |
| Handle error: `if err != nil { return ..., err }`, wrap with `fmt.Errorf("...: %w", err)` | done | `GoHandleErrorIntention`, `GoWrapErrorIntention` | S |
| Fill select: `ctx.Done()`, channel receives/sends, timer/ticker `C`, `time.After(d)` from the scope, optional `default` | done | `GoFillSelectIntention`, `GoFillSelectWithDefaultIntention` | S |
| Invert / flip `if`, merge nested `if`, `if` ↔ `switch` | missing | | S |
| Split / join `var` declarations, `var x T = v` ↔ `x := v` | missing | | S |
| Change quote (interpreted ↔ raw string) | missing | gopls `changequote` | S |
| Remove unused parameter (with call sites) | missing | gopls `unusedparams` | M |
| Add/convert struct tags for all fields | missing | | S |
| Generate: constructor, getters/setters, `String()` for `iota` enums (`stringer` without the tool), `Equal` | missing | `codeInsight.generate` actions | M |
| Generate table-driven test for a function | missing | | M |

## 4. Refactorings

| Refactoring | Status | Effort |
|---|---|---|
| Rename (locals, package-level, fields, methods with interface propagation) | done | |
| Rename package (directory, package clause, import paths in the module) | missing | M |
| Introduce variable / constant | missing | M |
| Extract function / method (free variables, results, `return` / `break` handling) | missing | L |
| Inline variable / function call | missing | L |
| Change signature (parameters, results, call sites, interface implementations) | missing | L |
| Move declaration to another file / package (imports, exportedness) | missing | L |
| Safe delete | missing | M |

## 5. Analysis

Each analysis needs the type system to agree with `go/types`; false positives are worse than no
check. They go through the corpus gates (no new diagnostics on GOROOT/src and GOMODCACHE). Many
fit the declarative rules engine planned in `docs/RULES.md`.

| Check | Status | Notes | Effort |
|---|---|---|---|
| Unresolved, unused import/variable/label, type mismatch, call arity, duplicates, generics, missing return | done | | |
| Exhaustive `switch` over `iota` enums and sealed interfaces | missing | with the "fill switch" fix; GoLand has no equivalent. Go has no sealed interfaces: here one is an interface with an unexported method whose implementations all live in its package. Off by default or weak warning: non-exhaustive switches are often intentional (the `exhaustive` linter is opt-in) | S |
| `Printf` family: verb vs argument type, argument count, `%w` only in `Errorf`, completion of verbs; user wrappers detected like vet | missing | | M |
| Error flow: error assigned and not checked, overwritten unchecked, wrong `err` checked | missing | needs a per-function data-flow framework | M |
| Nil flow: dereference after `x == nil` without exit, `defer resp.Body.Close()` before the error check | missing | same framework | M |
| Concurrency: copying locks (`copylocks`), `wg.Add` inside the goroutine, loop variable capture for `go` < 1.22, send on a closed channel | missing | | M |
| `context.Context` not first, lost or replaced by `context.Background()` | missing | | S |
| Struct tags: syntax, duplicate keys/names | missing | vet `structtag` | S |
| `errors.As` with a non-pointer target, `errors.Is` vs `==` | missing | | S |
| Unreachable code, self-assignment, impossible `nil` comparison, shadowing (optional) | missing | vet passes | M |
| Unused exported declarations across the project, import cycles, `internal/` violations | partial | `internal/` is detected by the project model, not reported | M |
| Doc comment lint (exported symbol without a comment, comment not starting with the name) | missing | | S |
| Build constraints: `//go:build` expression syntax, unknown GOOS/GOARCH | missing | | S |
| `go.mod`: `replace` to a missing path, unused requires (`go mod tidy` without the tool), `vendor/` out of sync | missing | | M |

## 6. Languages inside Go strings

| Feature | Status | Notes | Effort | Needs |
|---|---|---|---|---|
| RE2 regular expressions in `regexp.Compile`/`MustCompile` (highlighting, RE2-specific validation, Check RegExp) | missing | `languageInjector` + RE2 dialect | M | |
| `text/template` / `html/template`: highlighting, navigation to fields of the data type | missing | own lexer/parser | L | |
| SQL in `database/sql` calls | missing | | S | plugin (Database) |
| `time.Format` layouts: validation, hint of the rendered example | missing | | S | |
| JSON in raw strings | missing | | S | |

## 7. Go-specific files and directives

| Feature | Status | Effort |
|---|---|---|
| `go.mod` / `go.work` PSI, navigation | partial (model; `go.work` multi-module editing support to check) | M |
| `//go:embed`: pattern navigation and validation | missing | S |
| `//go:linkname`, `//go:generate` navigation; run `go generate` | missing | S (run: needs `go`) |
| Assembly `.s`: `TEXT ·Func` ↔ Go declaration without body | missing | M |
| cgo: `import "C"` preamble, `C.` symbols | missing | L |
| Status-bar widget for GOOS / GOARCH / build tags, re-evaluating the analysis | missing | S |

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
| Per-package trackers, library caches independent of project edits | done | CHANGELOG 2026-10-02 | |
| One inference cache per function body | done | CHANGELOG 2026-10-02 | |
| Lazy reparse of function bodies | done | `GoLazyBlockElementType`, CHANGELOG 2026-10-02 | |
| Shared indexes for GOROOT per Go version | missing | first open spends 3-5 s indexing GOROOT (UI robot P7); platform shared-indexes mechanism | M |
| Headless inspections (CLI, SARIF report for CI) | missing | the same analysis without gopls; library use | M |
| ML completion ranking, name suggestions | planned | `docs/ML.md` | L |
| Declarative lint rules, ruleguard importer | planned | `docs/RULES.md` | L |

## 10. Decisions needed

- **D1. The `go` binary outside the project model.** Decided by the host: `go` runs only in
  user-triggered actions (run configurations, tests, coverage, `go generate`, build, lint), never
  in analysis, highlighting or indexing; go-psi keeps its pure fallback (`DefaultGoToolchainProvider`).
- **D2. Delve.** Decided by the host: its own DAP client over `dlv dap` (package `debugger`); not a go-psi concern.
- **D3. Optional plugin dependencies** (Database for SQL injection) through `<depends optional>`.

## 11. Order

Each wave is measured with the benchmarks and the UI robot before and after, like the cache work.

1. **Wave 1 (S, visible daily):** postfix and live templates, inlay hints, struct size inlay, Smart
   Enter, statement mover, surround with, unwrap, Join Lines, imports on paste, spelling, Code
   Vision counts, doc links.
2. **Wave 2 (analysis with the highest value):** exhaustive switch with fill switch, `Printf`
   checks, struct tags, fill struct/returns, handle error, change quote, `if` intentions.
3. **Wave 3 (code creation):** create from usage, implement interface, Generate actions,
   goimports grouping, smart and chain completion, completion of unimported members.
4. **Wave 4 (data flow):** per-function data-flow framework, error and nil flow, concurrency and
   `context` checks.
5. **Wave 5 (platform):** shared indexes for GOROOT, call and type hierarchy, headless
   inspections.
6. **Wave 6:** done by the host (section 8); what remains is the PSI inputs of step 9 of MIGRATION.md.
7. **Wave 7 (refactorings):** introduce variable, rename package, safe delete, then extract,
   inline, change signature, move.
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
| Analysis for another GOOS / GOARCH without changing the environment: a switch in the status bar over the toolchain of the project model | `GoToolchainProvider` (host override), `GoSettings.analysisGoos/analysisGoarch` | S |
| Format on type (gofmt-compatible, no process) and instant Reformat (step 8j) | `lang.formatter` of go-psi-ide | S |
| Go next to other languages in IDEs that have them: Protobuf / gRPC stubs ↔ Go navigation, SQL in strings (section 6), `html/template` fields | optional plugin dependencies (D3) | M |
| Full function without gopls and without the network (MIGRATION.md step 12) on large monorepositories: indexes by build list only | library roots policy (step 7) | — |

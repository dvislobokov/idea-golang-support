# Changelog

All notable changes to the Go Project Support plugin are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Versions 0.2.14–0.2.22 are wave 2 of `docs/FEATURES.md` §11 (analysis and intentions on the native PSI; all of them act only with Language features: Built-in,
gopls keeps its own analyzers otherwise); versions 0.2.2–0.2.13 are wave 1 (editor features on the native PSI): one feature per version.
Versions 0.2.34–0.2.36 are the second batch of quick tasks (time layouts, directive comments, struct tag naming style).
Versions 0.2.31–0.2.33 are quick follow-ups (typed Implement Interface, doc comment and build constraint inspections).
Versions 0.2.23–0.2.30 are wave 3 (code creation: Generate, import groups, smart / chain / project-member completion, create from usage, implement missing methods).

### Changed
- Exhaustive switch inspection (`GoExhaustiveSwitch`) is a weak warning: a plain warning was noise on `reflect.Kind`-like switches without `default`.

## [0.2.36] - 2026-10-03

### Changed — Struct tag completion
- The tag name offered first follows the naming style the struct's other fields use for that key (`userId` next to `firstName`, `user_id` next to `first_name`); the other spellings stay in the list.
- Typing `:` after a known key in a raw-string tag writes `""` and opens the name list; typing `,` inside a value opens the options.

## [0.2.35] - 2026-10-03

### Added — Directive comments: go:embed, go:linkname, go:generate
- `//go:embed` patterns (globs, `all:`, quoted, directories) are references to the matched files and directories: Ctrl+B goes to the file or offers a list. The new `GoEmbedDirective` inspection (error) reports go's messages: no matching files found, invalid pattern syntax, directory with no embeddable files, misplaced directive, missing `embed` import. Its fix "Add import "embed"" adds a blank import for string/[]byte vars and a plain one for `embed.FS`.
- `//go:linkname local pkg.name`: `local` references the local declaration and follows its rename; `pkg.name`, `pkg.Type.method` and `pkg.(*Type).method` reference the target in the project, GOROOT or the module cache, resolved from stubs. An unresolved target is silent.
- `//go:generate`: `go run ./cmd/x` and any other argument that is an existing relative file or directory is a reference. `go generate` is not run.

## [0.2.34] - 2026-10-03

### Added — time.Format layouts
- Inlay hint after the layout argument of `Time.Format`, `AppendFormat`, `time.Parse` and `ParseInLocation` (a string literal or a string constant such as `time.RFC3339`): the layout rendered with a fixed sample time (`→ 2026-03-07 15:09`). The call is found by resolve; the hint has its own option "Time layout example" in Settings | Editor | Inlay Hints | Go.
- Inspection `GoTimeLayout` (weak warning, on by default): a layout in the `yyyy-MM-dd HH:mm:ss` notation (fix "Convert to Go layout"), a layout with no time element, and the swapped ISO date `2006-02-01` (fix "Swap to '2006-01-02'"). Stdlib layout constants are never reported.

## [0.2.33] - 2026-10-03

### Added — Build constraint checks
- Inspection "Build constraint comments" (`GoBuildConstraint`, vet `buildtag`): syntax errors in `//go:build` expressions, a misplaced or repeated `//go:build` line (errors), and tags one edit away from a known GOOS/GOARCH (`linx`, weak warning) with the fix "Replace with 'linux'"; custom tags like `integration` stay quiet.
- A `// +build` line without `//go:build` gets a weak warning and the fix "Add //go:build line", which converts the old syntax (space = OR, comma = AND, `!` = NOT) into the new one.

## [0.2.32] - 2026-10-03

### Added — Doc comment inspection
- `GoDocComment` inspection (opt-in, weak warning, golint / revive `exported` rules): an exported package-level function, method, type, const or var without a doc comment, and a doc comment that does not start with the declared name (`A`, `An` and `The` are allowed, `Deprecated:` is ignored). A comment on a `const (...)` / `var (...)` group counts for its specs. `_test.go`, `main` packages and generated files are skipped.
- Quick fixes "Add doc comment" (inserts `// Name ` above the declaration with its indentation) and "Start comment with 'Name'" (rewrites the first word or prepends the name).

## [0.2.31] - 2026-10-03

### Changed — Implement Interface on the typed API
- Ctrl+I / Alt+Insert / Alt+Enter "Implement interface" write the method stubs through `GoImplementStubs.compute` (the computation of the "Implement missing methods" fix): types are spelled as the target file writes them, the needed imports are added, and the receiver name and kind follow the type's own methods.
- In dumb mode, and for catalogue-only interfaces the PSI cannot resolve, the previous text generator is used.

## [0.2.30] - 2026-10-03

### Added — Implement missing methods quick fix
- `T does not implement I` (assignment, argument, return, composite literal element, impossible type assertion) gets the fix `Implement 'I' for T: add missing methods`: stubs after T's last method with the interface's parameter names (names from the types when it has none), types and imports as the file writes them, a pointer receiver for `*T` and a value receiver for `T`.
- Methods T has with a pointer receiver or another signature are not stubbed again; no fix when nothing is missing. The gopls `stubmethods` fix is hidden with Built-in features.

## [0.2.29] - 2026-10-03

### Added — Create from usage on the native PSI
- Alt+Enter on an undefined name creates it from its use: `Create function 'f'` (parameter types and names from the arguments, results from the context: `x, err := f()` gives `(any, error)`, a call statement has none), `Create method 'M' on T` (after T's last method, receiver named and pointed like T's methods), `Create field 'F' in T` (type from the assigned value or the expected type), `Create variable 'x'` (`x := <zero>` or `var x T` before the statement, `var x T` at package level), `Create type 'T'` (`struct{}`, `interface{}` in a constraint). Bodies are `panic("not implemented")`.
- `pkg.F(...)` of a package of the project is created in that package; nothing is offered for the standard library, the module cache, generic code or a name that resolves.
- With Built-in language features the text-based Create function and the gopls "Create function / variable" fix are hidden.
- A field created in a one-line struct (`struct{ A int }`) spreads the struct over lines; `time` of `time.Time` without its import is never offered as a variable.

## [0.2.28] - 2026-10-03

### Added — Members of unimported project packages
- Typing two or more characters of an exported function, type, var or const of another package of the module offers `pkg.Name`; inserting it adds the import. Typed values rank by the expected type, below names in scope.
- Read from the stub index without loading files. Skipped: the current package, `main` packages, test files, `vendor`/`testdata`, `internal` packages the file may not import, and packages whose name is already taken in the file.
- The host catalogue no longer lists the project's own packages when built-in completion is active (stdlib and dependencies stay there).

## [0.2.27] - 2026-10-03

### Added — Chain completion
- `u.Profile.Email` / `u.Settings().Theme()`: a field or a parameterless method of a variable or parameter, then a member of its type, promoted fields included; it matches by its last name too.
- Smart completion keeps only chains of the expected type; basic completion shows them from two typed characters. Capped at 50 chains.

## [0.2.26] - 2026-10-03

### Added — Smart completion by expected type
- Ctrl+Shift+Space in an expression offers only what fits the expected type, identical types first: variables, members of the qualifier, functions and methods by their first result, `len`/`cap`/`append`/`new` where they fit.
- Values written for the type: `T{}` / `&T{}` for structs, `make(T)` / `make(T, 0)` for maps, channels and slices, a `func(...) R {}` literal for function types, `""` and `0`; the caret lands inside braces or quotes.
- Without an expected type smart completion falls back to the basic list.

## [0.2.25] - 2026-10-03

### Added — goimports grouping of imports
- Optimize Imports (Built-in diagnostics) regroups each `import ( … )` like `goimports -local <main module>`: `"C"`, standard library, third-party, the project's own module, one blank line apart, each group sorted. Comment lines above a spec move with it, named/dot/blank imports stay in their group, and a single import keeps its form.
- Auto-import (completion, Add import fix, paste, intentions) puts a new path into its group and creates the group with its blank line when it is missing; a single-line import becomes a grouped declaration in that order.
- Reformat Code still only sorts within the existing blank-line groups, as gofmt does.

## [0.2.24] - 2026-10-03

### Added — Generate Equal method
- Alt+Insert → Equal Method... on a struct: a dialog of fields, then `func (p Point) Equal(other Point) bool` comparing them with `==`, `bytes.Equal` for `[]byte`, `slices.Equal` / `maps.Equal` for slices and maps of comparable elements, and `t.Equal(...)` for `time.Time`; the packages are imported.
- Fields that cannot be compared that way (funcs, slices of slices, maps of slices) are unticked; ticked by hand they use `reflect.DeepEqual`. Receiver form and name follow the type's existing methods (value receiver when there are none).
- Fixed: an import added by the host's generators, postfix templates and catalogue completion goes to its sorted place among the imports of its kind, not to the end of the block (`bytes` after `strings`).

## [0.2.23] - 2026-10-03

### Added — Generate String() for an enum
- Alt+Insert → String() for Enum on an integer type with constants in its package (`type Color int` + `iota`): writes `String()` the way `stringer` does, with a `case` per value (equal values give one case, the first name wins) and `fmt.Sprintf("Color(%d)", int(c))` for any other value; `fmt` is imported.
- Not offered for bit flags (`1 << iota`), for a type that already has `String()`, or while the IDE is indexing; the receiver name of the type's existing methods is kept. The enum lookup (`GoEnumConstants`) is shared with Fill Switch and the exhaustive-switch inspection.

## [0.2.22] - 2026-10-02

### Added — Printf verb completion
- Printf verb completion: typing `%` in the format string of a printf-like call (including package wrappers) opens `%v %+v %#v %T %d %s %q %x %t %f %.2f …`, with `%w` only in `Errorf`; flags and width typed before the verb are kept.
- Verbs are ranked by the type of the argument the directive will read (`%d` for ints, `%s` for strings, `%f` for floats, `%w` for errors in `Errorf`).

## [0.2.21] - 2026-10-02

### Added — `var` / `const` split and join
- **Split into separate declarations** (`var a, b int`, `var (…)` / `const (…)` groups without `iota`) and **Group declarations** (adjacent `var` or `const` lines into one group).
- **Join declaration and assignment**: `var x T` + `x = v` → `x := v`, or `var x T = v` when `v` alone would give `x` another type — the same rule as Join Lines, which now keeps a comment above the declaration.
- **Convert to 'var' declaration** (`x := v` → `var x T = v`, the type written with the file's import names) and **Convert to short variable declaration** (`var x T = v` → `x := v` when the types agree). Built-in language features only.

## [0.2.20] - 2026-10-02

### Added — `if` intentions
- **Invert 'if' condition** (also from the `else` keyword) swaps the branches and negates the condition: `==`↔`!=`, `<`↔`>=` for integers and strings (`!(a < b)` for floats), `!x`→`x`, De Morgan one level.
- **Invert 'if' with early return / continue** for an `if` ending a function without results or a loop body; **Merge nested 'if'** and **Split 'if' condition** (at the `&&` under the caret).
- **Convert 'if' to 'switch'** for `if x == 1 … else if x == 2 || x == 3 … else …` over one variable or field, and **Convert 'switch' to 'if'** back (`default` becomes the last `else`; not offered with `fallthrough` or a `break` leaving the switch). Built-in language features only.

## [0.2.19] - 2026-10-02

### Added — Change quote
- **Change quote** (Alt+Enter on a string literal): "Convert to raw string literal" / "Convert to interpreted string literal", like gopls `changequote`. An interpreted string becomes raw only when its value fits between backquotes unchanged (no backquote, newline or other control character but a tab); escapes are converted. Built-in language features only (gopls offers its own).

## [0.2.18] - 2026-10-02

### Added — errors.Is / errors.As
- errors.Is / errors.As inspection (`GoErrorsPackage`): vet `errorsas` (the target of `errors.As` must be a non-nil pointer to an error type or an interface, never `*error`), and `err == ErrX` / `!=` against a package-level `error` variable (weak warning; not `nil`, not inside `Is` methods).
- Quick fixes "Take the address of target" and "Replace with errors.Is(err, ErrX)" (`errors` imported when missing).

## [0.2.17] - 2026-10-02

### Added — context.Context placement
- context.Context inspection (`GoContextPlacement`): a `context.Context` parameter that is not first (testing `*T`/`*B`/`TB` may precede it), a context parameter replaced or shadowed by `context.Background()`/`TODO()`, and `context.Background()` passed as an argument where the function has a context parameter (weak warning).
- Quick fixes "Use ctx" and "Use ctx (remove the assignment)"; function literals without their own context parameter (detached goroutines) are not reported.

## [0.2.16] - 2026-10-02

### Added — Struct tags
- Struct tag inspection (`GoStructTag`, vet `structtag`): tags `reflect.StructTag.Get` cannot read (unquoted values, missing colon, unbalanced quotes, pairs not separated by spaces, suspicious spaces), a key repeated in one tag, the same `json`/`xml`/`yaml`/`db` name on two fields of a struct, `json`/`xml` tags on unexported fields.
- Quick fixes "Fix quoting" (`json:id` → `json:"id"`, when unambiguous) and "Remove duplicate key"; the tag parser is pure (`GoStructTags`) and unit-tested.

## [0.2.15] - 2026-10-02

### Added — Printf checks
- Printf checks without gopls (`GoPrintf`, vet `printf`): verb against argument type, argument count with `[n]` indexes and `*` width/precision, unknown verbs and flags, `%w` only in `Errorf` and only with an `error`, Printf directives and redundant newlines in `Println`; `fmt`, `log`, `testing` and the package's own `(format string, args ...any)` wrappers.
- Warnings point at the directive inside the string (escapes are mapped back to the source); fixes: replace the verb with the one the argument's type takes, remove extra arguments, add `%v` placeholders; `%v` of an error in `Errorf` offers `%w`.

## [0.2.14] - 2026-10-02

### Added — Exhaustive switch
- Exhaustive switch inspection (`GoExhaustiveSwitch`, go-psi-ide): a `switch` over an enum (constants of a named type in its package) or a type switch over an interface of the project that misses members and has no `default` is reported on the `switch` keyword ("Missing cases in switch of type Color: Green, Blue and 1 more"); constants with equal values are one member, bit-flag enums and library interfaces are skipped; option to report switches with `default` too.
- Quick fix "Add missing cases" inserts the same cases as Fill Switch (`GoSwitchCases`, shared).
- Fixed: Fill switch no longer adds a duplicate case for constants with the same value (`Ptr = Pointer`), and a case naming an interface covers its implementations.

## [0.2.13] - 2026-10-02

### Added — Doc links
- **Doc links** of Go 1.19 doc comments are references: `[Name]`, `[Type.Method]`, `[pkg.Name]`, `[pkg.Type.Field]`, `[import/path.Name]`, `[*T]` in `//` comments outside function bodies navigate with Ctrl+click, show up in Find Usages and follow Rename (each name of a link separately). Built-in language features only (gopls serves its own).
- Quick Documentation renders the doc links that resolve as links that open the documentation of their target.

## [0.2.12] - 2026-10-02

### Added — Spelling
- **Spelling** in Go files (the platform's Typo inspection, through an optional dependency on the spellchecker): identifiers where they are declared (camel case and underscores split, Rename fix), comments and string literals. Directives (`//go:build`, `//nolint`, `//export`), indented code in doc comments, URLs, `[pkg.Name]` doc links, back-quoted code, the cgo preamble, import paths, struct tags, escapes, `fmt` verbs and rune literals are not checked.

## [0.2.11] - 2026-10-02

### Added — Add imports on paste
- **Add imports on paste**: Go code copied from one Go file into another brings its imports along: every `pkg.X` qualifier of the copied range is recorded with the import it resolved to (and its alias), and the imports the target misses are added in place (no duplicates; a name that already means something in the target is left alone). Settings | Editor | General | Auto Import, "Insert imports on paste": Ask / All / None.
- Text pasted from outside the IDE: an unresolved qualifier gets the import of the one standard package with that name that has every name used through it (`json.Marshal` → `encoding/json`; `template.New` stays as it is). go-psi-ide asks the new extension point `pasteImportResolver`, the plugin answers from its package catalogue.

## [0.2.10] - 2026-10-02

### Added — Struct size inlay
- **Struct size inlay**: after `type T struct {` the size of the struct as gc lays it out for the GOARCH of the build, the bytes lost to padding and the size with the fields reordered — `24 bytes, 11 padding (16 if reordered)`; Alt+Enter → Reorder fields gets there. 64-bit targets only, in both language-feature modes (gopls has no such hint); Settings | Editor | Inlay Hints | Go | Other.

## [0.2.9] - 2026-10-02

### Added — Inlay hints
- **Inlay hints over the PSI** (Settings | Editor | Inlay Hints | Go): the hint set of gopls drawn by the IDE itself — parameter names at call sites (silent when the argument already says the name, or a one-parameter function says it by its own name), struct literal field names, types of `:=` and `for … range` variables, values of `iota` and computed constants (on by default); types of elided nested literals and inferred type arguments of generic calls (off by default). Types are printed with the import name of their package.
- With **Language features: Built-in** the hints of gopls are not requested (per file, so while the IDE indexes gopls still shows them); with gopls the native providers collect nothing (`GoFeature.INLAY_HINTS`). Every `:=` type comes from the per-function-body inference cache: 688 hints of `net/http/server.go` in ~15 ms warm, ~24 ms after an edit in one function (`GoInlayHintsBenchmark`).
- The type renderer of go-psi-semantic keeps its qualifier per thread: the checker, the intentions and now the hints render types from different daemon threads at once.

## [0.2.8] - 2026-10-02

### Added — Join Lines
- **Join Lines** (Ctrl+Shift+J) for Go: `var x T` + `x = v` → `x := v` (`var x T = v` when `v` alone would change the type), `"a" +` + `"b"` → `"ab"` (two interpreted or two raw literals), and call arguments / composite literal elements spread over lines join to `f(a, b)` / `T{A: 1, B: 2}` without the trailing comma. Everything else joins as before.

## [0.2.7] - 2026-10-02

### Added — Move Statement
- **Move Statement Up/Down** (Ctrl+Shift+Up/Down) moves whole Go elements: statements (all lines of a multi-line literal at once), `case` clauses, struct fields, interface methods, specs of `var (…)` / `const (…)` / `type (…)` / `import (…)` groups and top-level declarations, with the comment lines right above them. The element swaps with its neighbour and stops at the edge of its list; blank lines between declarations stay in place, so the gofmt layout does not change. Anything else falls back to moving the line.

## [0.2.6] - 2026-10-02

### Added — Unwrap/Remove
- **Unwrap/Remove** (Ctrl+Shift+Delete) for Go: Unwrap `if` (the init statement is kept, `else` dropped), Unwrap `else` (the last branch replaces the whole chain), Remove `else`, Unwrap `for` (the init of a three-clause loop is kept), Unwrap `func() {…}()` (also under `go` / `defer`), Remove `defer` / `go`, Unwrap `case` of `switch` / type switch / `select`, Unwrap braces. The body moves one tab to the left; raw strings and block comments stay as they are.

## [0.2.5] - 2026-10-02

### Added — Surround With
- **Surround With** (Ctrl+Alt+T) works on the Go PSI: the selection grows to whole statements of one block or `case` body (a trailing comment goes along, raw-string lines are not reindented), then `if`, `if / else`, `for`, `func() {…}()`, `go func() {…}()`, `defer func() {…}()`, `{…}`; a selected expression gets `(expr)`, `!(expr)` (booleans only), `for range` (variables by type: `_, v` / `k, v` / `v` / `i` / `_, r`) and `if err != nil {…}` after a call returning an `error` (`v, err := call` with zero values of the enclosing function's results in the `return`; a nested `(T, error)` call is moved out before its statement). The text-based surrounder of the plugin is gone.

## [0.2.4] - 2026-10-02

### Added — Smart Enter
- Complete Statement (Ctrl+Shift+Enter) reads the PSI of the caret line: `if`/`for`/`switch`/`select`/`else`/`func`/`struct`/`interface` without a body get `{}` with the caret inside (`func run` also gets `()`), unclosed calls, indexes and one-line literals are closed (parentheses in strings no longer count), an element of a multi-line literal or call gets its `,`, `go func`/`defer func` get `() {…}()`, and a complete statement moves the caret to a new indented line.
- A bare `for` no longer takes the next line as its header; a header with a doc comment above it is recognised; `//` alone above a declaration becomes `// Name `. The line-based text engine is gone.

## [0.2.3] - 2026-10-02

### Added — Live templates
- Live templates apply only where they make sense: statements (`fori`, `forr`, `err`, `sel`, `mu`, …) inside function bodies, declarations (`func`, `meth`, `main`, `test`, `bench`, `fuzz`, …) at the top level of a file, the `json` tag in a struct field, `errf` in an expression; new templates `func` and `errf` (`fmt.Errorf("…: %w", err)`).
- New template contexts Go statement / Go top level / Go struct field / Go expression under Go (Settings | Editor | Live Templates), decided by the PSI, by the lexer's tokens while the document is not committed; `goTypeName()` / `goErrorReturn()` read the text of the file when the PSI has not seen it yet.

## [0.2.2] - 2026-10-02

### Added — Postfix templates
- Postfix templates are offered by the type of the expression: `.if`/`.not` on booleans, `.nil`/`.nn` on nillable types, `.for` on what `range` takes (`for k, v := range m`, `for v := range ch`, `for i := range n`), `.sort`/`.append` on slices, `.len` on what has a length, `.go`/`.defer` on calls; an unresolved expression keeps them.
- `.err` on a call returning an error last declares its results and checks the error (`v, err := load()` + `if err != nil { return 0, err }`), on an `error` call it checks inline; `.return` puts the value among the function's results with zero values for the rest (`return 0, err`); `.var` declares every result of a call; new `.par`, `.nn`; `.not` writes `!(a == b)` and turns `!x` into `x`; `.sort` uses `slices.Sort` for ordered elements; `.print`, `.printf`, `.wrap`, `.sort` add their import.
- The expression comes from the go-psi PSI (statement templates only on an expression statement); the text matcher remains only for a document that is not committed yet.

## Before 0.2.2 — unversioned (migration steps 1–10 and the logs window, shipped with 0.2.2)

### Logs
- **Go | Plugin Logs**: the journal of the plugin in a tool window (only the events of the plugin; Clear, Warnings and Errors Only, Open Logs Folder), and a "Plugin Logs" button on every error notification. One folder for every log, `~/idea-golang-logs`: `plugin/plugin-DATE.log` (the journal), `commands/commands-DATE.log` (every `go` command and tool the plugin runs, with its output, timestamps and exit code), `delve/` (the debugger logs moved here from the log directory of the IDE). Files are kept for two weeks. **Go | Open Logs Folder** opens it.

### Migration to the native Go PSI (branch `migration`, `MIGRATION.md`)

- Step 10 (C): one documentation set in `docs/` (the go-psi docs moved from `docs/psi`; their changelog is a section here, [go-psi](#go-psi-the-native-go-psi-modules)).
- Step 10 (A): the plugin's text scanner (`GoDeclarations`, `GoStructure`), the PSI bridge `GoDeclarationPsi` and the hand-written lexer `GoTextLexer` / `GoTextTokens` are gone, with the text fallbacks of steps 8-9; every consumer reads the PSI of go-psi (run icons and the run producer, Go to Test, Generate Test, the generators, keyword templates, the gopls usages / implementations / counts / Go to Super, the identifier colours, the test tree, function breakpoints, `goTypeName()` / `goErrorReturn()`). What is typed and not yet committed keeps working from the tokens of the go-psi lexer (indent, import insertion, the live-template context, debugger expression completion); the debugger hover, inline values and breakpoint lines answer only for a committed document (breakpoint lines keep the last answer meanwhile), inline values and subtests nothing in dumb mode. The text intentions Handle error / Add if err != nil check / Add missing return are removed: go-psi-ide's Handle error / Wrap error / Fill return values replace them with Built-in, the gopls actions with gopls. The catalogue of the standard library and the module cache reads files with its own scanner on the go-psi lexer (`catalogue.GoSourceScanner`; catalogue files are rebuilt once, `VERSION` 2).
- One switch **Language features** (gopls / Built-in) on the Language Server page replaces the ten per-feature rows; the formatter keeps its own choice; per-feature choices of earlier builds are not carried over (default gopls).
- Step 9 (S): what the plugin guessed about types from the text comes from go-psi where there is a PSI. The fixes of the linter learn what a function returns from the call's signature (`GoNativeSignatureProvider`, before gopls, which stays the fallback for calls that do not resolve). The `return` item of completion takes the result types of the function around: inside `if err != nil {` the zero values and the checked error, elsewhere the variables of exactly those types in scope (`err` first) or the zero values, plus the same with the error wrapped in `fmt.Errorf("…: %w", err)` where `fmt` is imported; the grey `if err != nil { return … }` writes the exact zero values. The grey idioms ask the types where they went by names: `if err != nil` only after a call whose last result is `error`, `defer x.Close()` for whatever has `Close() error` (and not for what has not), `defer mu.Unlock()` only where there is an `Unlock`; new grey text for an empty `select {` (a case for the context, timers and channels in scope), `switch x {` over a type with constants (a case per constant), `switch v := x.(type) {` (a case per implementation in the project) and `for {` with a channel or context in scope (the `select`). Completion offers the type of `make(` from what the place expects (`[]T, 0, len(xs)` with a slice in scope) and the comma-ok receive `v, ok := <-ch` after `<-ch`. The text versions stay for code without PSI until step 10.
- Step 9 (I): Implement Interface, struct tags, field alignment and the Alt+Insert generators read structs and method sets from the PSI of go-psi (`lang.GoStructPsi`) instead of the scanner and regexes. Implement Interface takes the method set from the type checker (embedded interfaces of any package and `error` unfolded) for the project and, when they are in the indices, the standard library and the module cache; the signatures are rendered with the target file's import names (an alias kept, a missing package imported); the methods a type already has count those of other files and those promoted from embedded fields; the receiver style is that of its declared methods. Only a package outside the indices (library roots of the standard library alone, or indexing not done) is still read from disk by the scanner. Add Struct Tags writes the tag before a trailing comment and turns an interpreted tag (`"json:\"x\""`) into a raw one; struct tag completion finds the tag and its field through the PSI (any struct, a tag still being typed included); `GoFieldAlignment.analyze(GoStructType)` takes the sizes from `GoSizes` (types of other files and packages). A variadic parameter (`...pkg.T`) of a text-rewritten signature is qualified too (was left as written).
- Step 9 (F): the Alt+Enter actions that rewrite code by its types come from go-psi-ide behind the switch **Code actions**: Fill all fields, Fill required fields, Fill return values (and Add missing return), Fill switch (enum constants, implementing types), Fill select (with default), Handle error, Wrap error with fmt.Errorf; with it on Built-in the fill actions of gopls (`Fill <struct>`, `Add cases for <type>`, `Fill in return values`) and the plugin's text intentions Handle error / Add if err != nil check / Add missing return / gopls Fill all fields stand down, with it on gopls the native ones are unavailable. Default stays gopls.
- Step 9 (R): the keyword templates, the debugger helpers and subtest detection read the PSI of go-psi instead of regexes over text (the text versions stay as the fallback for texts without that PSI, uncommitted documents and dumb mode). Keyword templates (`GoScopeInputs`): what a `for range` goes over, the context, `*testing.T` and channels come from the variables in scope by their types (a `ctx int` is no context, an inner `items := 3` hides the slice of the function); `select` is built from the scope - the context under its own name, a receive per channel, `<-t.C` per `*time.Timer` / `*time.Ticker`, a send per send-only channel - with a "select loop" item; per channel `v, ok := <-ch`, and `close(ch)` / `defer close(ch)` for one this function made and has not closed; `make` items for a buffered channel and a slice with capacity; the missing methods of an interface by the method sets of the semantic layer. Debugger: the expression under the mouse is the smallest one delve evaluates without running code (`a.b[i].c`, `a[i]` on its bracket, `*p`; no calls, no type or function names), inline values go only to names that resolve to variables, parameters and receivers (`b.c` for a field), breakpoint lines are decided by PSI nodes with the same rules. Subtests: `t.Run` only on a `*testing.T` (a `Run` method of anything else is no subtest), nested runs by their path (`outer/inner`), runs under a run with a computed name left out.
- The toolchain of the native project model is detected once per settings / `go env` state, not on every import resolve: the detection walks the PATH and GOROOT on disk and held a read action for 12 seconds in a freeze (seen live); Go | Reanalyze looks again.
- Step 8i: the "N usages" / "N implementations" hints above declarations come from go-psi-ide behind the switch **Code vision** (the group of the implementation gutters): references counted by the PSI in the use scope (stopped at "100+"), implementations from the stub indices on interfaces and their methods; with it on NATIVE the gopls hints stand down, with it on GOPLS (the default) the native providers answer nothing.
- Step 8j: the gofmt-compatible formatter of go-psi-ide is one more choice of **Reformat Code with** on Settings | Tools | Go | Code Quality: **Built-in**. With it Reformat Code and format on save run no process (the platform engine formats, with the import sorter of the port), with a tool chosen the plugin's formatting service claims the file and the engine never reaches the port, with None gopls formats as before (said explicitly to the LSP client: a Go file now has a formatter of its own). Default stays gofmt.
- The three services of go-psi the plugin overrides (toolchain provider, library roots policy, feature gate) are declared `open="true"` in the go-psi descriptors: the platform logged `InstanceNotOverridableException` for each at start (seen live in the sandbox; the overrides were in effect nevertheless).
- Step 8h: Rename of go-psi-ide (the platform's rename refactoring over the references of the native PSI: in-place for locals, parameters, receivers, labels and import aliases, the dialog for package-level names, interface methods together with their implementations on request) is in the plugin behind the switch **Rename**; with it on Built-in gopls does not offer its rename, with it on gopls (and while the IDE indexes) the LSP rename of the platform runs alone, the native in-place rename and the interface-method processor stand down. Default stays gopls.
- Step 8d: the semantic colours of go-psi-ide (identifiers coloured by what they resolve to: types, functions, fields, parameters, locals, package variables, constants, packages, labels, builtins) are in the plugin behind the switch **Semantic colours**; with it on Built-in the semantic tokens of gopls are not requested (per file, so while the IDE indexes gopls still colours) and the plugin's text rules leave the identifiers alone, so every identifier is coloured by one source; the text rules keep colouring the compiler directives (`//go:build`, `//go:generate`, …) in every mode. Default stays gopls.
- Step 8g: the ten inspections of go-psi-ide over the semantic check (unresolved references, unused imports / variables / labels, type mismatches, arity, duplicates, generics, missing return, other checker errors) with their quick fixes, `//noinspection` suppression and Optimize Imports are in the plugin behind the switch **Diagnostics**; with the Built-in source gopls keeps only its analyzers (`printf`, `unusedparams`, staticcheck, `go list`) and drops its compiler errors, so nothing is underlined twice, and the messages of an explicit Build / Vet show in the editor again (the compiler's word on what the checker may have missed). Default stays gopls.
- Step 8f: Quick Documentation, parameter info (Ctrl+P) and Type Info (Ctrl+Shift+P) of go-psi-ide are in the plugin behind the switch **Documentation** through `GoIgsIdeFeatureGate`; with it on NATIVE gopls stands down from hover and from the platform's signature help (`signatureHelpCustomizer`), with it on GOPLS the three native extensions answer nothing, so one source answers at a time.
- Step 8e: code completion of go-psi-ide (scopes, members, keywords, snippets, unimported packages, import paths, labels) is in the plugin behind the switch **Completion** (default still gopls): with Built-in the native contributor answers and gopls does not (`shouldRunCodeCompletion`, per request, so while the IDE indexes gopls still completes); the plugin's own contributors stay, and the keyword templates hide the bare keywords and snippets of the same word behind them. Known gap of Built-in: braces after a struct type and parameter info after `(` come with gopls and catalogue items only (step 9 for PSI items).
- Step 8c: the navigation and usages extensions of go-psi-ide (Go to Type Declaration / Implementation / Super, implementation gutters, reference search, usage types, read/write access, Find Usages handler, exit points) are in the plugin behind the switches **Navigation**, **Usages** and **Code vision** through `GoIgsIdeFeatureGate`: one source answers at a time, gopls or the PSI; with gopls on duty Find Usages lists its usages alone (the references of the PSI no longer double them).
- Step 8a: syntax errors come from one source at a time (the message of the parser no longer names the inserted semicolon: "';' or '}' expected"): the error elements of the native parser (`GoSyntaxErrorFilter`) or the `syntax` diagnostics of gopls (`GoplsDiagnosticsSupport`), chosen by **Syntax errors** in the new group **Source of features** on Settings | Tools | Go | Language Server (default still gopls; without the server the parser always shows them); a change restarts the highlighting of the open files.
- Step 7: `$GOROOT/src` and the module directories of the build list are indexed library roots (navigable, searchable); the setting **Index for navigation** on Settings | Tools | Go chooses between the standard library alone and the standard library with the dependencies, and a change re-indexes without a restart.
- Step 7 (part): one go.mod/go.work parser: `mod.GoModFile` is a thin view over the native project model's `GoModFileParser` (quoted paths, `// indirect; comment`, blocks and comments read by one grammar); the plugin's line-by-line parser is gone.
- Step 4 follow-up: Structure view titles methods with their receiver again, `(Store) Add(item Item)`, under the type too; folding folds every nested `{...}` block of a body (`if`, `for`, `switch`, `select`, function literals) and runs of two or more `//` comments, as before the native parser.
- Step 7 (part): the toolchain of the native project model is the plugin's (`go` of the settings, its `go env`, build tags, GOOS/GOARCH); Go | Reanalyze also drops the caches of the native PSI.
- Step 6: Implement Interface and the project packages of the catalogue read the stub indices of the native PSI (no file is parsed, no AST loaded); the two text-based file indices are gone.
- Steps 4–5: the Go parser is the native one (go-psi-core); Structure view, breadcrumbs, folding, Go to Class/Symbol, commenter, brace matcher and quote handler come from go-psi-ide; the text-level pseudo-PSI (`GoDeclaration`) is gone, the text scanner stays behind the `GoDeclarationPsi` bridge for run icons, Go to Test, the annotator and the gopls handlers. Gates: tests, both corpus gates, benchmarks, verifier, UI robot.
- gopls: a diagnostic whose range lies past the end of the file (published for a longer version of a big file, before format on save shrank it) is dropped; the platform threw "Range must be inside element being annotated" on it.
- gopls: semantic tokens are not requested for files above 100 000 bytes (the server refuses them and the platform logged an exception per request).
- Step 3: the PSI modules are composed into the plugin jar; `plugin.xml` includes their stub and file indices, project model and semantic services (not the parser, not the library roots, not the IDE features yet). `verifyPlugin` runs against the installed IDE 2026.1.4.
- Step 2: one `GoLanguage`/`GoFileType` for the plugin (hosted in `go-psi-core`, icon from the root module); the text-level helpers renamed to `GoTextLexer`/`GoTextTokens`; the `GO_*` colour keys defined once in `lang.GoColors`; the PSI modules ship no icons or colour page of their own.
- Step 1: per-feature source switches `GOPLS | NATIVE` in `GoSettings` (syntax errors, diagnostics, completion, hover, navigation, usages, rename, semantic colours, code vision) read through `GoFeatures`; the gopls customizers and handlers stand down when a feature is served natively. Defaults stay `GOPLS`; no settings UI until a native source exists.

## go-psi (the native Go PSI modules)

The modules `go-psi-core`, `go-psi-semantic` and `go-psi-ide` were developed as a standalone library (go-psi) before they were transplanted into this plugin on 2026-10-02; their history follows, newest first, as kept there (its `docs/…` paths are those of go-psi: the documents are now in `docs/`, its `docs/PLAN.md` is `docs/PSI-PLAN.md`).

### 2026-10-02 - Transplant into idea-golang-support, step 10 (MIGRATION.md)
- Step 10 (B): one UI robot in tools/ui-robot (the go-psi scenarios, --perf, --cold moved in; port 8083).

### 2026-10-02 - Transplant into idea-golang-support, step 9 (MIGRATION.md)
- go-psi-semantic: `GoSemanticService.expectedTypeAt(expression)` and `enclosingResultTypes(element)` are public API (ABI dump updated).
  The expected-type logic of completion moved into `semantic.infer.GoExpectedType` and gained: conversions give no expectation, a
  spread argument (`f(xs...)`) expects the variadic slice, a single call returning all results expects their tuple, the left operand
  expects the right one's type unless it is an untyped constant, a map index expects the key type. `GoExpectedTypeTest` (6: assignments
  and declarations, call arguments, returns, composite literals, operands / sends / cases / conditions / map index, enclosing results).
- go-psi-ide: `GoExpectedTypes.compute` delegates to the service; `GoCompletionSemantics.literalType` and `derefUnderlying` to
  `GoExpectedType` (the completion tests are the regression suite, unchanged).

### 2026-10-02 - Transplant into idea-golang-support, step 9 (F) (MIGRATION.md)
- go-psi-ide: intentions that rewrite code by its types (`ide.intentions`, `go-psi-ide-intentions.xml`, docs/IDE-FEATURES.md "Intentions"):
  Fill all fields, Fill required fields, Fill return values / Add missing return, Fill switch, Fill select, Fill select with default,
  Handle error, Wrap error with fmt.Errorf. All ask `GoIdeFeatureGate` for `CODE_ACTIONS` and are unavailable while it is off.
- Shared helpers: `GoZeroValues` (zero values by type; `GoSnippets.iferrText` now uses it), `GoScopeValues` over
  `GoScopeCandidates.walkLocals` (the local-scope walk of completion moved to the companion, completion unchanged),
  `GoCompletionSemantics.literalType(value, service)` callable without a completion session.
- Tests: `GoCodeActionIntentionsTest` (20), `GoIdeFeatureGateTest.testClosedGateCodeActions`.

### 2026-10-02 - Transplant into idea-golang-support, step 8i (MIGRATION.md)
- go-psi-ide: code vision over the PSI (`ide.codevision`, `go-psi-ide-codevision.xml`): `GoUsagesCodeVisionProvider` ("N usages", references in
  the use scope, stopped at 100) and `GoImplementationsCodeVisionProvider` ("N implementations" on interfaces and their method specs only,
  from the stub indices as the gutters) above package-level functions (not entry points or tests), methods, type specs and interface method
  specs, anchored below the doc comment; a click runs `ShowUsages` / `GotoImplementation` on the name. Both follow `GoIdeFeatureGate`
  (`IMPLEMENTATION_MARKERS`) and stand down in dumb mode. `GoCodeVisionTest` (7, with the AST-loading guard on the implementing file).

### 2026-10-02 - Transplant into idea-golang-support, step 8j (MIGRATION.md)
- The host includes `go-psi-ide-formatter.xml`: the formatting model, code style pages, `GoImportSorter` and the post-format processor
  are in the plugin behind its formatter setting (`GoFormatter.NATIVE`, "Built-in"). No gate in go-psi-ide: the host's `FormattingService`
  claims a Go file whenever an external tool is chosen, and the platform engine (`CoreFormattingService`, the one that builds this model)
  is only the fallback, so the port formats exactly when nothing claims the file. Nothing changed in the module itself.

### 2026-10-02 - Transplant into idea-golang-support, step 8h (MIGRATION.md)
- go-psi-ide: `GoIdeFeature.RENAME`; `GoRefactoringSupportProvider.isInplaceRenameAvailable` and `GoRenameMethodProcessor.canProcessElement`
  ask the gate and stand down while the group is off (a host's other rename handler, a language server's, would otherwise share the
  registry's chooser with the platform's `VariableInplaceRenameHandler`; the default `PsiElementRenameHandler` is the registry's fallback,
  not an extension, so it never runs next to another handler). The manipulators, `GoNamesValidator` and `GoRenameInputValidator` stay
  passive in every mode. `GoIdeFeatureGateTest.testClosedGateRename`.

### 2026-10-02 - Transplant into idea-golang-support, step 8d (MIGRATION.md)
- The default application services a host overrides (`DefaultGoToolchainProvider`, `DefaultGoLibraryRootsPolicy`, `DefaultGoIdeFeatureGate`) carry
  `open="true"`: IntelliJ 2026.1 warns `InstanceNotOverridableException` for an `overrides="true"` of a service not declared open.
- go-psi-ide: `GoSemanticHighlightingAnnotator` asks `GoIdeFeatureGate` for `SEMANTIC_COLORS` at its entry (per element: a settings read)
  and colours nothing while the group is off; the host includes `go-psi-ide-highlighting.xml` and lets the semantic tokens of gopls and its
  own text-rule annotator stand down when the group is on, so each identifier is coloured by one source. `GoIdeFeatureGateTest` covers the
  closed gate (no `GO_*` info of the annotator in a highlighting pass).

### 2026-10-02 - Transplant into idea-golang-support, step 8g (MIGRATION.md)
- go-psi-ide: the diagnostics follow `GoIdeFeatureGate` (`DIAGNOSTICS`): `GoDiagnosticsInspectionBase.checkFile` reports nothing and
  `GoImportOptimizer.supports` declines the file while the group is off; `GoInspectionSuppressor` works regardless. The semantic
  highlighting annotator moved from `go-psi-ide-inspections.xml` to its own descriptor `go-psi-ide-highlighting.xml`, so a host can
  include the inspections (step 8g) without the colours (step 8d); the test descriptor includes both.

### 2026-10-02 - Transplant into idea-golang-support, step 8f (MIGRATION.md)
- go-psi-ide: the extensions of `go-psi-ide-documentation.xml` ask `GoIdeFeatureGate` for `HOVER` at their entry: `GoDocumentationTargetProvider`
  gives no target, `GoExpressionTypeProvider` no expressions, `GoParameterInfoHandler` no argument list when the group is off (the platform's
  `ShowParameterInfoHandler` takes the first handler of the language that answers, so no ordering is needed).

### 2026-10-02 - Transplant into idea-golang-support, step 8e (MIGRATION.md)
- go-psi-ide: `GoCompletionContributor` (now `id="goPsiCompletion"`, so a host can order its own contributors around it) and
  `GoCompletionConfidence` ask `GoIdeFeatureGate` for `COMPLETION` at the entry and stand down when it is off (the contributor adds nothing,
  the confidence answers `UNSURE`). A Go file in no directory (`virtualFile?.parent == null`: a code fragment made from text, such as a
  debugger's expression editor) is not completed either: `GoCompletionContributor.isCodeFragment`. Weighers unchanged.
- Known gap of the host's Built-in source: braces after a struct type and parameter info after `(` of a completed call
  (`completionStructBraces` / `completionArguments`) apply to gopls and catalogue items only; PSI items get them at step 9.

### 2026-10-02 - Transplant into idea-golang-support, step 8c (MIGRATION.md)
- go-psi-ide: `ide.GoIdeFeatureGate` (application service, default `DefaultGoIdeFeatureGate` in `go-psi-ide-editor.xml`) is asked at the
  entry of every extension of `go-psi-ide-navigation.xml` for its group (`NAVIGATION`, `USAGES`, `IMPLEMENTATION_MARKERS`); the host
  overrides it with its feature switches. `targetElementEvaluator` and `codeInsight.gotoSuper` (one per language in the platform) are
  registered first and defer to the next extension for Go when off.
- go-psi-core: the inserted semicolon is consumed by the external rule `syntheticSemi` (`consumeTokenFast`): an error reads
  "';' or '}' expected", not "';', SEMICOLON_SYNTHETIC or '}' expected" (seen live at step 8a); recovery goldens updated, positions unchanged.

### 2026-10-02 - Transplant into idea-golang-support, step 7 (MIGRATION.md)
- go-psi-semantic: `GoLibraryRootsPolicy` (application service, `project.impl`) decides what `GoRootsProvider` exposes: nothing,
  the standard library, or also the modules of the build list. The default reads the registry key `gopsi.libraryRoots` as before;
  the host overrides it with its setting. `scheduleRootsUpdate` also reports a change of the policy.
- go-psi-ide: a method in the Structure view is titled with its receiver type, `(Point) Move(dx, dy int)`, under its type as well;
  `GoFoldingBuilder` folds every `GoBlock` and the braces of `switch`/`select`, and `//` runs from two lines (was three).
- go-psi-semantic: `GoModFileParser.directives(text)` gives every directive of a go.mod/go.work with its 1-based line and `// indirect`
  flag (`GoModDirective`, internal), from the same tokenizer as `parseGoMod`/`parseGoWork`; the host's `mod.GoModFile` is built on it.

### 2026-10-02 - Transplant into idea-golang-support, steps 4-5 (MIGRATION.md)
- The host plugin registers this parser and the editor descriptor (`go-psi-ide-editor.xml`); the file type is the host's
  (the test descriptors of the modules register it themselves, `go-psi-core-language.xml` no longer does).
- `tools/psi-ui-robot`: the robot accepts this repository's sandbox (`idea-golang-support` in the config path), `autotest.py`
  reads the sandbox log of this repository (`GOPSI_SANDBOX_LOG` overrides). The scenario still assumes go-psi completion,
  documentation and formatter, which the host wires at step 8; P1/P6/P8/P9 are comparable now, P7 after step 7 (library roots).

### 2026-10-02 - Transplant into idea-golang-support, step 3 (MIGRATION.md)
- Descriptors split by what the host includes when: `go-psi-core.xml` (stub types, stub and file indices) and
  `go-psi-core-language.xml` (file type, parser, AST factory); `go-psi-semantic.xml` (services) and
  `go-psi-semantic-roots.xml` (`GoRootsProvider`, registry keys); `go-psi-ide.xml` -> `go-psi-ide-{editor,formatter,
  navigation,refactoring,documentation,completion,inspections}.xml`. The test descriptors of the modules include them all.

### 2026-10-02 - Transplant into idea-golang-support, step 2 (MIGRATION.md)
- `GoLanguage`/`GoFileType` are the host plugin's objects, hosted in `go-psi-core` under the same FQN; `GoIcons` and
  `icons/go.svg` removed (the file icon comes from the host's `/icons/go.svg`; empty in the tests of this module).
- `ide.highlighting.*` (lexer highlighter, `GoHighlightingColors`, colour settings page) removed: the host's
  `GoSyntaxHighlighter` and colour schemes are the one palette, its keys hosted as `lang.GoColors`.
  `GoSemanticHighlightingAnnotator` maps onto them: a type's name in its spec is `GO_TYPE_DECLARATION`, other types and
  type parameters `GO_TYPE_REFERENCE`, methods share the function keys. Golden `testData/highlighting/semantic.txt` updated.
- `GoIdeIcons` uses the platform `AllIcons.Nodes.*` (no SVGs of its own). Tests of go-psi-ide register a colourless
  highlighter over the PSI lexer (brace matching, quotes and TODO read the editor highlighter's tokens).

### 2026-10-02 - Performance wave: results on a quiet machine
- Items 1-4, 6 and 8 of `docs/PERF-BACKLOG.md` merged (per-body diagnostics, package scope on own
  stamps with import edges from the index, import path cache, marker existence cache, lazy
  completion presentation, shared header scan); item 5 measured and rejected, replaced by one
  package scope per package; item 9 is a design (`docs/LIBRARY-SUMMARIES.md`).
- `./gradlew benchmark -Dgopsi.benchmark.update=true`: 34 of 39 thresholds improved, none worse.
  `GoBodyEditRehighlightBenchmark.bodyEditCheck` 65 -> 15 ms, `warmCheck` 40 -> 0.45 ms,
  `otherFileBodyEditCheck` 38 -> 0.55 ms; `GoDeclarationEditBenchmark.declEditCheckOtherFile`
  76 -> 58 ms, `unrelatedDeclEditCheckOtherFile` 29 -> 0.65 ms, `declEditLibraryExprs` 20 -> 14 ms/edit;
  `GoHighlightingPassBenchmark` warm/afterBodyEdit/afterTopLevelEdit/cold 216/285/310/384 ->
  185/193/234/320 ms; `GoCompletionLatencyBenchmark.statementWarm` 70 -> 63 ms, `statementCold`
  100 -> 83 ms; `GoIndexBenchmark.stringsBytes` 65 -> 50 ms; `GoCacheMemoryBenchmark` 65.7 -> 60.7 MB.
- UI robot `--perf` (against the lazy-bodies build): P1 `check()` after a body edit 92/99 -> 1.7 ms,
  keystroke to daemon finished 482/470 -> 458 ms (313 ms of it is the platform's autoreparse delay
  before the daemon starts; the daemon run itself is 145 ms, annotator 122 ms), P8 `check()` after
  a sibling body edit -> 0.4 ms, after a sibling declaration edit 403/419 -> 85 ms, P9 heap
  retained by caches 66/74 -> 45 MB, cold `check()` of 16k GOROOT lines 5139/5192 -> 1606 ms.
- Gates: `./gradlew build` (ABI check included), `:go-psi-core:corpusTest` (0 mismatches),
  `:go-psi-semantic:corpusTest` (counts unchanged), robot scenario 13/13, no go-psi exceptions.

### 2026-10-02 - Implementation marker existence cached
- `GoImplementationLineMarkerProvider` caches the boolean "has at least one target" per owner (type
  spec, method spec, method declaration) and marker kind in a `CachedValue` on the owner, with
  `GoTrackers.projectWideDependencies()` (project out-of-block tracker, library tracker, project
  model, roots; file additions/removals bump the project tracker). Only a Boolean is cached; popup
  targets stay lazy and uncached. Index-not-ready and cancellation are not cached (not DumbAware).
- Tests: `GoImplementationMarkerCacheTest` (computed once across two passes; recomputed after an
  implementation is added/removed in another file; "implements" direction after project and library
  changes); `GoImplementationsAstLoadingTest` unchanged and green.
### 2026-10-02 - Import path lookup cached per directory
- `DefaultGoPackageResolver.importPathOf` caches its result (null included) per directory in a
  `CachedValue` (deps: `VFS_STRUCTURE_MODIFICATIONS`, `GoProjectModelTracker`, toolchain tracker);
  entries of invalid directories are dropped on access and swept when the map doubles. The GOROOT
  `src` `VirtualFile` is cached per toolchain path (VFS structure + toolchain tracker) and the
  `GoModuleCacheLayout` is memoized per `GOMODCACHE`. No signature or resolution change.
- Tests: `ImportPathCacheTest` (no recompute between calls, recompute after model and toolchain
  change, deleted directory dropped). `go-psi-semantic:test`, `go-psi-ide:test`, `corpusTest`
  green; corpus counts unchanged. Benchmark gain not measurable on a loaded machine
  (GoResolveBenchmark cold ~165-190 ms, warm ~57-78 ms vs ~150-320 / 49-110 ms before).
### 2026-10-02 - One package scope per package
- `GoPackageModel.scopeOf(file)` of a regular (non-test) file whose package the project model
  resolves returns `scopeOf(pkg)`, the scope cached on the directory, instead of building a merged
  name map per file (item 5 measurement: 151 per-file scopes next to 59 directory scopes during a
  `check` of `net/http`). Test and external-test files keep their own scope (different file sets).
  `GoCacheMemoryBenchmark.netHttp` retained 65.7 -> 60.7 MB; `:go-psi-semantic:test`, `:go-psi-ide:test` green.

### 2026-10-02 - Package scope on own stamps; imports from the index
- `docs/PERF-BACKLOG.md` item 2. `GoPackageModel.PackageScope` (names, methods by receiver name)
  no longer depends on the import closure: `scopeOf(file)` and `scopeOf(pkg)` use the new
  `GoTrackers.ownPackageDependencies(element)` (the package's own stamp, not below the file-set
  stamp, + project model + roots; library packages: `library`). A top-level edit in an imported
  project package keeps the importer's scope. Type-carrying consumers keep `packageDependencies`
  (closure) and look the scope up per computation: `GoScopes.importName`, `GoResolver`
  (`resolveMember`), `GoTypeBuilder` (`methodsOf`, stub array lengths), `GoScopes` receiver type
  params and file scope, `GoUniverse`, `GoChecker` (redeclarations, through the diagnostics cache).
- Per-file top-level names: `GoPackageModel.fileDeclarations(file)` (names -> elements, methods by
  receiver) is a `CachedValue` on the file depending on the new per-file out-of-block tracker
  (`GoTrackers.forFileOutOfBlock`, bumped with the package stamp, and by a generic change without
  precise events) + roots. `PackageScope` merges the per-file lists, so a rebuild after an edit in
  one file re-reads only that file's stub.
- `PackageTracker.directImports` reads import paths from `GoFileImportsIndex` through
  `FileBasedIndex.getFileData` (per-file keys of the existing scalar index; no index change, no
  version bump) instead of loading every file's stub; files with an unsaved document or a loaded
  AST use `GoFile.imports`; dumb mode (`IndexNotReadyException`) falls back to the PSI walk. New
  `GoPackageModel.resolveImport(path, VirtualFile)` overload (the `GoFile` one delegates).
- Tests (`GoTrackersTest`): an imported package edit keeps the importer's own dependencies and
  scope but bumps its `packageDependencies`; a top-level edit rebuilds only the edited file's
  declarations (body edits keep them); import paths from the index equal the PSI walk (and the
  raw index data per file); an import added in an unsaved document joins the closure.
  `GoCacheInvalidationTest` unchanged and green.
- Effect (`GoDeclarationEditBenchmark` medians, before -> after, one run each on a shared, loaded
  machine; the untouched `warmCheckOtherFile` moved 60.97 -> 37.40 ms between the runs, so treat
  as indicative): `declEditCheckOtherFile` 87.13 -> 66.87 ms, `unrelatedDeclEditCheckOtherFile`
  36.37 -> 35.97 ms, `declEditLibraryExprs` 225.63 -> 70.84 ms/3 edits. Thresholds unchanged.
- Gates: `:go-psi-core:test :go-psi-semantic:test :go-psi-ide:test` and `:go-psi-semantic:corpusTest`
  green; metric counts unchanged (millis not recorded: the machine was shared).
### 2026-10-02 - Lazy completion presentation
- `GoCandidate` gets `tailSupplier`/`typeSupplier`; fields, methods, functions, constants, variables,
  parameters, type kinds and struct keys no longer render types while candidates are built.
  `GoLookupElementFactory` renders icon/text/tail/type in a `LookupElementRenderer` (`withRenderer`)
  when a row is presented; constant texts ("package", "label", "(path)") stay eager. Lookup
  strings, insert handlers, weigher inputs and the public `completion.api` are unchanged.
  `withExpensiveRenderer` was not used: `LookupElementPresentation.renderElement` (and so the
  presentation tests) skips an expensive renderer.
- `GoImportPaths.modules` is split in two cached values: dependency modules depend on
  `GoProjectModelTracker` only (module cache directories are immutable, a new dependency bumps the
  tracker), workspace modules keep `VFS_STRUCTURE_MODIFICATIONS` (a new directory may be a new
  package) but no longer invalidate the dependency list. `receiverTypes` filters the package scope
  as a sequence (no intermediate list).
- Tests: `GoMemberCompletionTest.testPresentationTextsAreRenderedLazily` (a ranker observes the
  render counter after candidates are built); `:go-psi-ide:test` green. `GoCompletionLatencyBenchmark`
  medians on a loaded machine, before -> after (same session, thresholds not updated, the test
  fixture's popup still renders every row so the gain is a lower bound): memberCold 95.2 -> 81.6 ms,
  memberWarm 90.0 -> 58.1 ms, statementWarm 109.7 -> 99.4 ms, statementCold 127.8 -> 154.1 ms (noise).
- Known gap: a `typesByName` view on `PackageScope` would let `receiverTypes` skip the full scan.
### 2026-10-02 - Per-body diagnostics
- `GoSemanticService.check(file)` is assembled by `semantic.check.GoIncrementalChecker` from cached
  parts instead of one walk over the file: a package-level pass (`GoChecker.checkPackageLevel`:
  everything outside function bodies, no body is entered or expanded; `CachedValue` on the file
  with the new `GoTrackers.fileOutOfBlockDependencies` = package dependencies + the file's body
  fallback stamp), init cycles (`checkInitCycleUnit`, cached with the trackers of every body the
  dependency walk read: called functions of the file and function literals in initializers), and
  one result per outermost body (`checkBody`: missing return, all checks inside, unused variables
  and labels, unused values, containment filter among the body's diagnostics; same unit as
  `GoBodyCache`, stored in the body's store). Unused imports are computed per call from the union
  of the imports used by the package level and by every body. Cached ranges are relative (to the
  top-level element or to the body start) and shifted on assembly. The containment filter runs
  once more across parts (package-level diagnostics against everything, body diagnostics against
  package-level ones), so the result equals the single walk, which stays as
  `GoChecker.checkMonolithic()` (`-Dgopsi.check.monolithic=true` switches `check` to it).
- The init-cycle walk collects package-level var/const specs without entering bodies (it used to
  expand every body of the file); its result is unchanged.
- Effect (medians, before -> after, same machine, loaded by other agents in both runs):
  `GoBodyEditRehighlightBenchmark.bodyEditCheck` 103.33 -> 18.13 ms, `warmCheck` 93.26 -> 0.495 ms,
  `otherFileBodyEditCheck` 63.82 -> 0.567 ms, `bodyEditTypeOfOtherFunction` 0.085 -> 0.061 ms/edit
  (noise). Thresholds not updated.
- Tests: `GoIncrementalCheckTest` (split == monolithic, same order, cold and warm, over
  `testData/check`, the 51 go/types testdata files, `net/http/server.go` and `go/types/*.go`; an
  edit in F recomputes only F's result (G's and H's result objects kept); a package-level edit
  recomputes every body; an unused import is reported when its last use leaves a body and not
  after it is used again; an unused variable in F stays correct (and shifted) after edits in G;
  missing return of a function and of a package-level literal; an init cycle through a function
  body appears and disappears with edits of that body; every edit step re-compared with the
  monolithic result).
- Gates: `:go-psi-semantic:test :go-psi-ide:test` (136/257 tests) green; `:go-psi-semantic:corpusTest`
  green, counts unchanged (`goroot-src-check`: 3201 files, 0 diagnostics, 0 crashed, 0 timed out).
  Wall times were higher in this run on all gates, including the untouched resolve gates (check
  54.9 -> 65.1 s, resolve 33.8 -> 38.7 s: machine load), so the metric files were not updated.
- Known gaps: the assembly still runs on every `check` call (cheap: ~0.5 ms on `server.go`);
  `GoDiagnosticsCache` keeps caching the assembled list per file change.
### 2026-10-02 - Indexing cost of GOROOT
- Measured (docs/PERF-BACKLOG.md item 8) with a throwaway in-process harness over 20.4 MB / 2086
  GOROOT files (`net`, `go`, `crypto`, `runtime`, `fmt`, `strings`, `bytes`, `encoding`; not
  committed) plus JFR of the `Stubs` indexer (1516 samples). One indexing pass (one `FileContent`
  per file, every index that takes `.go` in the core test environment) is 1.46-1.79 s, 71-88 ms/MB
  single-threaded: `Stubs` 1.23-1.43 s (~80%), `IdIndex` 138-174 ms (~9%), `Trigram.Index`
  88-105 ms (~6%), `go.file.imports` 7-9 ms + `go.build.tags` 4-5 ms (0.7%). At this rate all of
  GOROOT (6823 files, 85.6 MB) is ~6-7 s of CPU, i.e. the observed 3-5 s with parallel indexing.
- Inside `Stubs`: parse 65% (PsiBuilder lexing 13.5%, tree building 13.9%, grammar rules ~37% of
  which top-level `var` initializers 19% - composite-literal tables, `CompositeLit` 17.6% -,
  function/method declarations 9.7% with `lazyBlock` 3.5%, backtracking `rollbackTo` 5.9%,
  Grammar-Kit error-variant list growth 4.3%); stub tree walk 10% (PSI wrappers ~2%, `getText` of
  var/const initializers ~2%); serialization 9.7%; `indexTree` 2.4%; header scan of
  `GoStubBuilder` 0.6%; `StubUpdatingIndex.assertDeserializedStubMatchesOriginalStub` 8.7% runs in
  tests only. Bodies are really skipped: the parse inside `Stubs` is ~40-45 ms/MB against 200-240 ms/MB
  for a parse that expands every body (`PsiFileFactory` copy). Serialized stubs are 5.4 MB per 20.4 MB of source; 2.33 M
  chars of that (~43%) are `GoVarSpecStub`/`GoConstSpecStub.values` (initializer texts).
- Lexing per pass: the whole file once (PsiBuilder of the stub parse, 6.4-10.2 ms/MB, 5.5 M
  tokens); the header indices lex only the header (0.2-0.45 ms/MB), twice before this change,
  once now. With `go-psi-ide` loaded, `IdIndex` (`GoWordsScanner`) and `TodoIndex`
  (`GoIndexPatternBuilder`) lex the whole file once more each (platform indexers, not shared).
- Cut: `GoFileImportsIndex` and `GoBuildTagsIndex` share one `GoFileHeaderScanner` scan through
  `FileContent` user data (`lang.index.GoIndexedFileHeader`; `FileBasedIndexImpl.doIndexFileContent`
  creates one `FileContentImpl` per file for all indices, checked in the 2026.1.5 bytecode); in-process
  A/B over the same 20.4 MB: both indexers 18.4 -> 8.5 ms. `GoStubBuilder.createStubForFile` calls
  `parseBuildConstraint` instead of a lexer scan (3.7-6.5 -> 1.4-2.5 ms per 20.4 MB). Index values,
  stub form and versions unchanged. Tried and dropped: a raw-token fast path in `lazyBlock`
  (A/B in one JVM 1212 -> 1183 ms table-heavy, 408 -> 411 ms body-heavy: noise); its regression
  golden `parser/recovery/UnclosedBodyTrailingComments` (unclosed bodies whose trailing semicolons
  are interleaved with comments) is kept.
- Benchmarks (medians of runs on a shared machine, before -> after): `GoIndexBenchmark.stringsBytes`
  52.3/55.6/68.8/77.2 -> 55.6/57.4/57.8/58.3/59.7/62.7 ms; `GoStubBenchmark.nethttp`
  50.3/52.8/59.0/91.5 -> 44.5/47.4/47.9/48.3/49.6/58.1 ms (0.29-0.38 ms/1000 stubs); the gain is
  below the run-to-run noise, as the attribution predicts (<1% of a pass). Thresholds untouched.
- What is left is outside this change: lazy top-level literal values (grammar, ~19% of `Stubs`),
  dropping initializer texts from var/const stubs (public API + `STUB_VERSION`, ~43% of stub bytes),
  a `LightStubBuilder` (no AST/PSI during indexing, ~16%). Shared indexes for GOROOT: design note in
  docs/PERF-BACKLOG.md item 8 (possible through `intellij.indexing.shared.core`, but the dump side is
  `visibility="internal"`; not supportable for a third-party plugin today).
- `:go-psi-core:benchmark` accepts `-Pgopsi.benchmark.jvmArgs` like `go-psi-ide` (docs/TESTING.md).
- Gates: `:go-psi-core:test` 98 tests green; `:go-psi-core:corpusTest` green (lexer diff 0, AST diff
  0, fuzz 0 hard failures / 0 locality violations, stub round trip 0 mismatches); metric counts
  unchanged (only timing fields moved with machine load; not committed).

### 2026-10-02 - Lazy, incrementally reparseable function bodies
- `GoTypes.BLOCK` is now `lang.parser.GoLazyBlockElementType` (`IReparseableElementType` +
  `ILightLazyParseableElementType`). Bodies of function/method declarations and function literals
  are collapsed by the outer parse (`GoParserUtil.lazyBlock`: brace matching on tokens, stopping
  where the eager parse of an unclosed body stopped) and parsed on first access with the `Block`
  rule (`extraRoot`) in a fresh builder (`exprLev = 0`). Grammar: `FunctionBody ::= <<lazyBlock
  Block>> | Block` for declarations (the dead `| Block` keeps the generated `getBlock()`),
  `FunctionLit ::= func Signature <<lazyBlock Block>>`. PSI and public API unchanged (`GoBlock`,
  `getBlock()`, `getStatementList()`; `apiSurfaceCheck` green).
- Reparse: an edit inside a body re-parses only that body when the body is a reparse root (a
  top-level function/method whose `func` is at column 0, or a literal inside one outside control
  clause headers, literal values, index brackets and types) and the new text is one balanced
  `{ ... }` (no stray/missing `}`, no unterminated raw string or block comment, no `func IDENT`, no
  column-0 declaration keyword). Otherwise the platform falls back to the enclosing body or a full
  re-parse. The new body is merged into the old one, so the body block, untouched statements and
  every other declaration keep their PSI identity, and events stay inside the body (GoTrackers
  attributes them to the body, the other functions keep their `GoBodyCache` stores). The light
  variant makes a full re-parse diff an expanded body node by node instead of replacing it.
  Docs: `docs/GRAMMAR.md` section N (and B, J.1).
- Stub building and indexing never parse bodies (they were already not stubbed; now they are not
  even built). `STUB_VERSION` 3 -> 4: the serialized form is the same, but erroneous files can get a
  different stub tree (a body closed early by a stray inner `}` no longer leaks its remaining
  tokens to the top level, where `var x` used to become a package-level declaration).
- Effect (medians, before -> after, same machine, baseline from an identical copy of the tree):
  keystroke in a body + commit `GoTypingReparseBenchmark.serverBody` 24.09 -> 2.8-3.4 ms,
  `exprBody` 10.02 -> 1.8-2.1 ms; top-level keystroke (full outer parse, bodies not parsed)
  `serverTopLevel` 25.35 -> 7.9-8.4 ms, `exprTopLevel` 10.07 -> 2.5-2.7 ms;
  `GoParserBenchmark.serverIncrementalReparse` 35.9-40.8 -> 11.6-15.0 ms; `GoIndexBenchmark.stringsBytes`
  84-87 -> 50-65 ms; `GoCacheMemoryBenchmark` `check` of `net/http` 2251-2501 -> 1559-1563 ms and
  retained 79.0 -> 61.6 MB; `GoHighlightingPassBenchmark.warm` 216.7 -> 189.5 ms, `afterBodyEdit`
  228.1 -> 201.3 ms. Full parse with every body expanded (`GoParserBenchmark.server`/`gotypes`,
  which walk the whole tree) is within noise (baseline 118-148 / 150-183 ms/MB, after 127-195 /
  163-194 ms/MB over 3-5 runs each). Corpus gates: `goroot-src-resolve` 42.2 -> 34.0 s,
  `goroot-src-check` 64.4 -> 54.8 s, `gomodcache-golang-org-x-resolve` 239.5 -> 203.2 s (counts unchanged).
- Thresholds lowered: `GoTypingReparseBenchmark.serverBody` 23.37 -> 3.35, `exprBody` 9.55 -> 2.09,
  `serverTopLevel` 23.14 -> 8.36, `exprTopLevel` 9.56 -> 2.71, `GoParserBenchmark.serverIncrementalReparse`
  39.73 -> 15.00, `GoIndexBenchmark.stringsBytes` 99.31 -> 64.71.
- Goldens: all `cases/`, `goroot/`, `short/` goldens unchanged; two recovery goldens changed
  (`recovery/MissingClosingBrace`, `recovery/UnclosedBlockBeforeDecls`): the missing `}` of an
  unclosed body is reported at the end of the body without `got 'func'`/`got 'var'`, and the last
  inserted semicolon stays outside the body.
- Tests: `GoLazyBodyTest` (bodies collapsed until accessed; stub building over `net/http/server.go`
  and indexing parse no body; re-parse roots for declaration, method and literal bodies, enclosing
  body for literals in an `if` header or literal value, full re-parse for package-level literals
  and indented declarations; PSI identity after an incremental re-parse; fallbacks for unbalanced
  `{`, stray `}`, raw string, block comment, `func IDENT`, column-0 `var`, each compared with a fresh
  parse; empty body and balanced edits with `}` in strings stay incremental; unclosed-body
  recovery), `GoLazyBodyRandomEditTest` (240 random edits over 4 GOROOT files, 162 incremental,
  ~6 s; re-parsed tree == fresh parse after every edit), `GoBodyCacheTest`
  (`testIncrementalBodyReparseIsAttributedToThatBody`, `testFullReparseOfABodyEditKeepsOtherBodiesCorrect`).
- Gates: `generateParser generateLexer build`, `:go-psi-core:test :go-psi-semantic:test
  :go-psi-ide:test` (96/126/257 tests), `:go-psi-core:corpusTest` (AST diff 0 mismatches, fuzz
  locality violations 0, stub round trip 0) and `:go-psi-semantic:corpusTest` green; metric counts unchanged.
- Known gaps: a body whose closing brace is missing is re-parsed in full on every keystroke until
  it is closed; function literals in control clause headers, literal values and package-level
  initializers re-parse the enclosing body (or the file); bodies are re-lexed when expanded
  (`reuseCollapsedTokens` off: no token arrays kept on unexpanded bodies); a column-0 non-`func`
  declaration keyword inside a body (non-gofmt code) disables incremental re-parse of that body.
- UI robot `--perf`, two runs per side against the per-function body cache build: P1 commit +
  reparse per keystroke 26.5/26.0 -> 3.7/3.8 ms, keystroke to daemon finished 526/517 -> 482/470 ms,
  last key of a 10-key burst to daemon 529/494 -> 453/443 ms, P9 cold `check()` of 16k GOROOT lines
  6637/6882 -> 5139/5192 ms, heap retained by ASTs + caches 87/87 -> 66/74 MB; declaration-edit,
  completion, navigation and formatter steps unchanged within noise. Against the start of the cache
  work (before per-package trackers): keystroke to daemon finished 879 -> 482 ms, `check()` after a
  body edit 418 -> 98 ms, P9 retained 96-98 -> 66-74 MB.

### 2026-10-02 - Per-function body inference cache
- `semantic.cache.GoBodyCache`: expression types, constants, callee signatures, resolve results,
  type nodes and block statement lists inside a function body live in one lazily filled store per
  outermost body (top-level function or method; function literals belong to their enclosing
  function, a literal in a package-level initializer is its own unit), held by one `CachedValue` on
  the body block. The store depends on the body's tracker (`GoTrackers.forBody`), a per-file body
  fallback stamp and the package dependencies, no longer on the file tracker: a keystroke in one
  function keeps the caches of every other function of the file. Package-level elements keep a
  `CachedValue` per element (now with explicit keys). Filled get-then-put; values computed while a
  recursion was prevented are not stored (`RecursionManager.markStack`); nulls via a sentinel.
- `GoTrackers`: the listener bumps the tracker of the outermost body containing the event's parent
  (`GoPsiUtil.outermostBody`); changes outside bodies stay out-of-block. The generic
  `childrenChanged` after a commit drops all bodies of the file only when no precise event preceded
  it. `forFile` is unchanged (any change; used by the diagnostics list).
- Lookup cost: the body is found by an AST-node walk to the nearest block plus a per-block hint
  (and every 32 levels in deep chains), and a validated store is reused while no Go PSI, roots or
  project-model change happened. `warmLeftSpine` skips the spine walk when the left operand is
  already cached (it was quadratic: `GoDeepExpressionTest` 88 s -> 7 s).
- Effect (medians, before -> after, same machine and conditions): `bodyEditCheck` 149.6 -> 57-65 ms,
  `bodyEditTypeOfOtherFunction` 1.30 -> 0.045 ms/edit, retained memory after `check` of `net/http`
  88.3 -> 79 MB (CachedValues on `server.go` PSI 15571 -> 2235, plus 162 body stores holding 13498
  values), `GoHighlightingPassBenchmark.afterTopLevelEdit` 285.9 -> 233.9 ms, warm `typeOf` 0.078 ->
  0.060-0.066 and warm resolve 0.064 -> 0.061-0.067 per 1000; others within noise
  (`GoHighlightingPassBenchmark.afterBodyEdit` 219.8 -> 233.6 ms, highlighting is dominated by
  non-semantic passes there). Corpus gates: `goroot-src-check` 76.3 -> 64.4 s, resolve gates slightly
  faster, counts unchanged.
- Tests: `GoBodyCacheTest` (an edit in F keeps G's types, resolve results, store and tracker; edits
  in F and in its literal update F; method bodies and literals behave alike; a package-level literal
  is its own unit; package-level and other-file declaration edits reach bodies; typing between
  functions and breaking/restoring braces leave no stale types; a generic change without precise
  events drops all bodies of the file).
- Thresholds: `bodyEditCheck` 137.17 -> 65.12, `bodyEditTypeOfOtherFunction` 1.31 -> 0.047.
  `GoCacheMemoryBenchmark` also reports body stores and their values.
- Gates: `:go-psi-semantic:test :go-psi-ide:test build`, `:go-psi-semantic:corpusTest` green.
- Known gaps: per-block hints and the last-store reference add one user-data entry per block; a
  function body edit still re-runs `check` of the whole file (warm for the other functions).
- UI robot `--perf`, two runs per side against the per-package trackers build: P1 keystroke to
  daemon finished 865/896 -> 526/517 ms, `check()` after the edit 436/415 -> 92/99 ms, P8 `check()`
  after a sibling declaration edit 446/452 -> 403/419 ms, P9 heap retained by ASTs + caches over
  16k GOROOT lines 99/98 -> 87/87 MB; warm, completion, navigation steps unchanged within noise.

### 2026-10-02 - Per-package out-of-block trackers; library caches independent of project edits
- `GoTrackers`: the project-wide `outOfBlock` tracker is replaced by per-package trackers. A
  project package's values depend on the newest stamp among the package and its transitive
  project imports; library code (GOROOT, module cache, vendor, outside content) shares one
  `library` tracker bumped only by library edits. All values also depend on the project model
  and project roots. New API: `dependencies(element)`, `packageDependencies(element)`,
  `forPackage(dir)`, `invalidateAll()`. Adding/removing/renaming a Go file now bumps its package, and in project
  content also a file-set stamp that every project package count includes (a directory becomes
  or stops being a package, so an unresolved import may start resolving).
- Tests: `GoTrackersTest` (importers bumped, unrelated packages and library not, new file bumps
  its package), `GoCacheInvalidationTest` (A's field type change seen by B and transitive C; an
  unrelated edit keeps cached instances; a project edit keeps GOROOT type instances; a vendor
  edit and a project-model change recompute library types; the first Go file of a directory makes
  an importer's unresolved import resolve, and removing it unresolves it again).
- New benchmark `GoDeclarationEditBenchmark.unrelatedDeclEditCheckOtherFile`.
- Effect (median ms, before -> after, same machine and load; the machine was ~1.3x slower than
  for the stored thresholds): `GoLibraryCacheBenchmark.afterProjectTopLevelEdit` 177.6 -> 2.2-3.0
  (warm 0.74); `GoDeclarationEditBenchmark.declEditCheckOtherFile` (same package, A/B in two runs
  each) 113-116 -> 88-91; `unrelatedDeclEditCheckOtherFile` 27-33 (warm 26-38);
  `declEditLibraryExprs` unchanged (29-30 vs 28-31 ms/edit: the expressions are in the edited
  package, the GOROOT part was already a small share); `GoHighlightingPassBenchmark.afterTopLevelEdit`
  347.7 -> 279.0; body-edit and warm cases unchanged within noise.
- Thresholds: `afterProjectTopLevelEdit` 138 -> 3.0, `unrelatedDeclEditCheckOtherFile` 29.0 (new).
- Gates: `:go-psi-semantic:test`, `:go-psi-ide:test`, `./gradlew build` green.
- UI robot `--perf`, two runs per side: neutral (all changes within noise; every robot scenario
  edits the package it checks, the gain is for edits in other packages and for library caches).

### 2026-10-02 - UI robot --perf: declaration/sibling edits and cache memory
- `tools/ui-robot/perf.py`: P8 (top-level edit in a large open file; body and signature edits in an
  unopened sibling file of the same package; `check()` attribution after each) and P9 (heap retained
  by the semantic caches over ~16k lines of GOROOT, MB per kloc, cold/warm `check()`).
- `perf_compare.py`: argparse, old/new median and min..max, `(noise)` when ranges overlap, MB metrics,
  single-sample metrics marked `?` and never failing the exit code. `tools/ui-robot/ruff.toml`
  (ignores UP031 only); `uvx ruff check tools/ui-robot` clean.
- Two runs on one build: daemon medians within 5%, `check()` attributions 10-20%, single samples
  20-70%; treat >15% on n>=5 metrics as real. One run takes ~7 min.

### 2026-10-02 - Quiet gate output
- `tools/gates.sh <gate>... [-- <gradle args>]` (`test`, `build`, `corpus[:module]`,
  `bench[:module]`, `:module:task`) prints only what tests print themselves (corpus summaries,
  `BENCH` lines, failure samples), failed tests with the first lines of the exception, compile
  errors and the build result; the full output stays in `build/gates/<gate>.log`.
- Test JVMs no longer get the kotlinx-coroutines debug agent that the IntelliJ Platform Gradle
  Plugin attaches (its transformer printed dozens of `JPLISAgent.c ... ASSERTION FAILED` lines per
  run; `-Pgopsi.coroutinesAgent=true` brings it back), and the CDS warning is silenced.
  `:go-psi-semantic:test` passes, 0 JPLISAgent lines (was dozens).

### 2026-10-02 - Body edits no longer flush package and library caches
- Found by the new editing benchmarks: after every commit the platform sends a generic
  `childrenChanged` with the file as parent, and `GoTrackers` classified it as an out-of-block
  change, so every keystroke in any Go file bumped the project-wide tracker and recomputed all
  package-level and GOROOT types. Generic events now bump only the file-local trackers; the
  out-of-block decision uses the precise events. Regression test `GoTrackersTest`.
- Effect (median, ms): library types after a body edit 90 -> 1.4 per edit; check of an unchanged
  file after a body edit elsewhere 156 -> 38; re-check of the edited file 227 -> 137; typeOf in
  another function after a body edit 18 -> 1.3. Top-level edits are unchanged (~105-155, noisy).
- Deep chains: constant folding and typing now use separate warm-up guards (a shared guard let
  constant folding recurse through thousands of typeOf levels), and short expressions skip the
  spine walk via an O(1) text-length check.
- Thresholds updated for the improved benchmarks; `afterProjectTopLevelEdit` set to its measured
  median because the recorded 93 ms was not reproducible on unchanged main.

### 2026-10-02 - Editing-performance benchmarks (baselines)
- Seven new benchmark classes measure editing cost (docs/TESTING.md "Editing performance");
  thresholds recorded for 1-6, the memory probe only reports. Nothing optimised. Harness:
  `BenchmarkSupport.runTimed` (an iteration times only its own segments), `timed`, `median`,
  `report`; `tools/benchmark/EditingBenchmarkSupport.kt` (copy a GOROOT package into the light
  project at runtime, reversible edits).
- Baselines (AMD Ryzen 5 8400F, medians):
  - `GoTypingReparseBenchmark` (commit per keystroke): `serverBody` 23.4 ms, `serverTopLevel`
    23.1 ms, `exprBody` 9.5 ms, `exprTopLevel` 9.6 ms; 2-3 PSI events per keystroke, bodies far
    from the edit keep their PSI (50/50 for body edits, 30-34/50 for top-level edits). Cost
    scales with the file: a full re-parse merged by DiffTree.
  - `GoBodyEditRehighlightBenchmark` (copy of `net/http`): `warmCheck` 46.6 ms, `bodyEditCheck`
    227 ms, `otherFileBodyEditCheck` 156 ms, `bodyEditTypeOfOtherFunction` 18.2 ms/edit.
  - `GoDeclarationEditBenchmark`: `warmCheckOtherFile` 29.1 ms vs `declEditCheckOtherFile`
    75.7 ms; GOROOT-only expressions `warmLibraryExprs` 0.33 ms/pass vs `declEditLibraryExprs`
    20.1 ms/edit (x61).
  - `GoLibraryCacheBenchmark` (6702 expressions): `warm` 0.92 ms/pass, `afterProjectTopLevelEdit`
    93 ms, `afterProjectBodyEdit` 100 ms (both about x100-130 of warm).
  - `GoCompletionLatencyBenchmark` (3000 lines, 500 symbols): `memberWarm` 35 ms, `memberCold`
    53 ms, `statementWarm` 70 ms (563 items), `statementCold` 100 ms.
  - `GoHighlightingPassBenchmark` (`server.go` copy, all inspections): `warm` 216 ms, `cold`
    384 ms, `afterBodyEdit` 285 ms, `afterTopLevelEdit` 310 ms.
  - `GoCacheMemoryBenchmark`: `check` of the 33 GOROOT `net/http` files retains ~87 MB of caches;
    `server.go` holds 15571 cached values on 8929 PSI elements.
- Finding (not fixed): every commit ends with the platform's generic file-level `childrenChanged`
  event, which `GoTrackers` counts as out-of-block, so every edit, body edits included, bumps the
  project-wide `outOfBlock` tracker and drops all package-level and GOROOT caches
  (`afterProjectBodyEdit` = `afterProjectTopLevelEdit`; `otherFileBodyEditCheck` is 3.4x
  `warmCheck` although `server.go` did not change).
- Gates: `./gradlew benchmark` green (all old and new suites), `./gradlew build` green.

### 2026-10-02 - UI robot run: 16/16 after a completion fix
- UI robot scenario (`tools/ui-robot/autotest.py`, IDE on port 8084) over 15 steps: highlighting,
  inspections, quick fixes, completion, navigation into GOROOT, implementations, usages, rename,
  structure view, folding, gofmt-identical reformat, documentation, typing latency in
  `net/http/server.go` (daemon done ~1 s after the last keystroke), clean shutdown.
- Fixed the one failure it found: `GoCompletionContext` was cached on the dummy-identifier leaf,
  which the platform reuses with the file copy between sessions, so a session saw the previous
  session's context (empty list on a statement line after a `strings.` session, and the reverse).
  The cache is now valid only for the same `CompletionParameters`, offset and copy stamp. The light
  fixture cannot reproduce this; robot step 6 is the regression check. Rerun: 16/16 PASS.
- docs/IDE-FEATURES.md: missing return is now checked.

### 2026-10-02 - UI robot harness and end-to-end scenario
- `./gradlew :plugin:runIdeForUiTests` (robot-server on port 8084, `-ProbotPort=` overrides) and
  `tools/ui-robot` (`robot.py`, `scripts/*.js`, `autotest.py`, scenario project with `go.mod`),
  adapted from idea-golang-support. `autotest.py` opens a scratch copy of the scenario project,
  runs 15 steps and writes `build/ui-robot/report.md` with component screenshots. See
  docs/TESTING.md "UI robot".
- First full run (fresh sandbox, IDEA 2026.1.5): 15 of 16 checks PASS: highlighting and
  semantic keys, the exact problems of `broken.go` (including `missing return`), quick fixes,
  navigation into GOROOT, implementations and gutters, Find Usages, rename (in place, and via the
  interface prompt and dialog), structure, folding, Reformat Code identical to `gofmt`, Quick
  Documentation and Parameter Info, no go-psi exceptions in `idea.log`. Latency on a copy of
  `net/http/server.go`: daemon done 976 ms after the last of 20 typed characters (keys max
  50 ms), Go to Declaration 21 ms.
- FAIL (go-psi bug, not fixed here): completion returns stale results across sessions.
  `GoCompletionContext.of` caches the context on the copy's leaf without validation; the platform
  reuses the file copy and the dummy-identifier leaf between sessions, so a session can get the
  previous session's context (`strings.` lists statement items, a blank statement line after a
  `strings.` session shows "No suggestions").
- docs/IDE-FEATURES.md still lists "missing return is not checked" as a known gap; the checker
  reports it.

### 2026-10-01 - 0.0.9 verified on merged main
- Merged semantic tails (0 false positives on GOROOT and a golang.org/x sample, `cannot-infer`
  on by default, go/types testdata 92.7%), binary compatibility validation (`checkKotlinAbi`)
  and performance work (non-injectable Go comments, first-touch fixes on huge files).
- `GoChecker.checkConstSpec` uses the typer's cached const-group layout instead of a sibling
  walk that was quadratic on huge groups (`opGen.go`, 8421 specs).
- `GoTypeParamType.renamed` marked `@ApiStatus.Internal`; ABI dumps regenerated.
- Gates on merged main: lexer/parser/go-ast-diff/fuzz/stubs unchanged; gofmt 4621/4621;
  imports 0, resolve 128 (GOROOT) / 6563 (x, mostly missing modules); check gate 0
  diagnostics, 0 crashes, 76 s for 3201 files (23 ms/file, was 136 s), no file over 5 s.

### 2026-10-01 - Performance: non-injectable comments, first-touch cost on huge files
- `GoASTFactory` (`lang.ast.factory`) creates comments as `GoCommentImpl`. It is a `PsiComment`
  but not a `PsiLanguageInjectionHost`, so the platform no longer probes every comment for
  injections on reformat. `toString` and the parser goldens are unchanged. `GoParsingTestCase`
  registers the factory. New test `GoCommentImplTest` covers directives, the visitor,
  reparse after an edit and the doc comment binder. `GoFormatterBenchmark` in one busy session:
  65-69 ms -> 36-48 ms (30-45%). The threshold went from 49.5 to 35.5 ms.
- Removed quadratic walks found with JFR on GOROOT's generated files:
  - `GoScopes` caches block and case-clause statement lists. Lists of 32 or more statements
    get a name index (`simdAMD64intrinsics.go`).
  - Switch header lookups stop at `{` (`rewriteAMD64.go`).
  - `GoFile.topLevel` caches its AST results per modification stamp.
  - `GoExpressionTyper` computes the iota and repetition source of a const group in one pass
    (`opGen.go`) and exposes it as `repeatedConstSpec(spec)`.
- New regression tests: resolve fixture `bigblock` (indexed blocks, a case clause, a `var` group,
  a reference at the start of a statement, declaration after use) and `GoConstantTest`
  (repetition with typed and multi-name specs, a function-local group after an edit, a group of
  5000 specs).
- New dev test `GorootSlowFilesCheckCorpusTest` (opt-in via `-Dgopsi.check.files`) and a
  `-Pgopsi.corpus.jvmArgs` passthrough for the semantic corpus task.
- Check gate, same session, before -> after: `simdAMD64intrinsics.go` 7216 ms -> under 0.9 s,
  `rewriteAMD64.go` 6851 -> 2822-3579 ms, total 140 s -> 91 s. Diagnostics stay at 13, and the
  resolve gates are unchanged (128 / 6563 unresolved).
- `opGen.go` stays at about 10 s: the same quadratic walk remains in
  `GoChecker.previousSpecWithValues`. The one-line fix is in docs/SEMANTIC.md; it was not
  applied because `semantic.check` is owned by another branch.
- Gates: `build`, core corpus (lexer/parser/go-ast-diff 0/fuzz/stubs unchanged), ide corpus
  (gofmt 4621/4621), semantic corpus.
### 2026-10-01 - Semantic tails: receiver type-param conversions, inference, allowlist (0.0.9)
- Recursive generic calls are inferred on renamed type parameters (go/types renameTParams);
  constraint inference through a foreign type-parameter bound; `EI(x)` with a receiver type
  parameter is a conversion.
- Checker: union term limits and overlaps, built-ins used as values, comparison operand rules,
  3-index slice positions, nested named types in conversions.
- GOROOT check gate: 13 -> 0 false positives; new golang.org/x sampled check gate
  (`gomodcache-x-sample-check.json`): 0 cannot-infer. `cannot-infer` is now on by default in
  `GoGenerics` (option can turn it off). go/types testdata 91.5% -> 92.7%, 0 false positives.
- Gates: resolve GOROOT 128 / golang.org/x 6563 unresolved (unchanged), check 42 ms per file.
### 2026-10-01 - Binary compatibility validation
- Kotlin Gradle plugin built-in ABI validation (`kotlin { abiValidation }`, Kotlin 2.4.20, no extra plugin) on
  `go-psi-core`, `go-psi-semantic`, `go-psi-ide`; committed dumps `<module>/api/<module>.api`, restricted to the six
  public API packages (nested classes included, `lang.psi.impl` / `lang.stubs.index` excluded) and without
  `@ApiStatus.Internal`. Tasks: `checkKotlinAbi` (in `check`/`build`), `updateKotlinAbi`.
- Kept `apiSurfaceCheck` / `docs/API-SURFACE.txt` next to it (readable cross-module list); roles described in
  `docs/API.md` ("Binary compatibility").
- Verified: removing `GoSemanticService.render(GoType)` makes `checkKotlinAbi` fail with the dump diff.

### 2026-10-01 - Debts closed; 0.0.8 verified on merged main
- Merged API debts, formatter performance (~700 -> ~255 ms/MB, layout cache, partial layout
  under syntax errors) and Phase 5c (types/testdata ERROR-site coverage 55% -> 91%, GOROOT
  false positives 29 -> 13, missing return, exact constants).
- Fix found by the merged-main corpus run: generated `x/text/unicode/norm` tables contain
  left-deep `"" + "..." + ...` chains of thousands of operands. Typing them overflowed the
  stack inside RecursionManager and poisoned it for later files (1 crash in the check gate).
  `GoExpressionTyper` now evaluates long left spines bottom-up through the same cache entry
  point, and `GoChecker` builds operand texts lazily (it was quadratic on such chains).
  Regression test `GoDeepExpressionTest`. All corpus gates green: lexer/parser/go-ast-diff/fuzz/
  stubs unchanged, gofmt 4621/4621, resolve 128 unresolved, check 13 false positives, 0 crashes.

### 2026-10-01 - Phase 5c: checker coverage, constants, control flow (0.0.8)
- go/types testdata harness: ERROR-site coverage 55% -> 91% (1651/1804 sites, 0 false
  positives, 147 allowlisted lines with reasons); the test now fails below 91%, on duplicate
  identical diagnostics, and ignores `/* ERROR */` inside `//` comments (as go/types).
- Constants: 512-bit untyped integers (`constant <op> overflow`, `constant shift overflow`,
  `invalid shift count`), typed-constant overflow/truncation (incl. `-x`, `^x`, repeated iota
  specs), division by zero, full go/types shift rules for non-constant shifts of untyped
  constants (context type), folding of `real`/`imag`/`complex`/`min`/`max` and
  `unsafe.Sizeof`/`Alignof`/`Offsetof` with gc 64-bit sizes (`GoSizes`; not constant when the
  layout depends on a type parameter); conversions to type parameters are never constant.
- Control flow: `GoTerminating` (spec "Terminating statements") and `missing return`; new
  `GoMissingReturnInspection` (ERROR) in go-psi-ide. Also fallthrough placement, select case
  shape, for post statements, duplicate defaults, blank labels, go/defer of conversions and of
  discarded builtin results.
- Declarations: invalid recursive types, initialization cycles (through same-file functions),
  `func init` signature, receiver base types (pointer/interface/unsafe.Pointer/non-local),
  embedded field types, `iota` outside constant declarations, `cannot assign to`.
- Expressions: duplicate expression/type switch cases (int/float/string only, as gc), ordered
  operators and conversions over type parameters with go/types causes (type sets flattened),
  core-type causes for make/range/slice, type assertion/switch on type parameter values,
  pointer methods of non-addressable values, field selectors on types, struct literal field
  name/unexported-field errors, `make` length/capacity checks, unsafe builtin argument checks.
- Lookup: identical embedded types at one depth are consolidated, so a member reached through
  two paths is ambiguous (go/types `consolidateMultiples`). `implements` treats an unknown
  method package path as matching.
- Grammar: unary `~x` parses (checker: `cannot use ~ outside of interface or type
  constraint`); 3-index slices without middle/final index parse (checker reports them). Parser
  golden `TypeParamsVsArrayInvalid.txt` updated.
- Duplicate diagnostics fixed at the source (`_ = none()`, `var n int = two()`, explicit type
  arguments of calls, shift operands); `GoDiagnosticsCache` no longer calls `distinct()`.
- Tests: new `GoCheckTest` fixtures `declarations`, `typeparams`, `controlflow` and corpus
  regressions in `clean.go`; `GoInspectionsTest.testMissingReturn`.
- Corpus gates: GOROOT check false positives 29 -> 13 (assignability 18 -> 2, cannot-infer 11;
  38 ms/file, 0 crashes, 0 timeouts; diagnostics written to
  `go-psi-semantic/build/reports/goroot-check-diagnostics.txt`); GOROOT resolve 128 unresolved
  and golang.org/x resolve 6563 unresolved, both unchanged.
- Known gaps: the typer types a conversion to a receiver-declared type parameter
  (`func (d *T[EI]) f() { id := EI(n) }`) as its operand type (the 2 remaining assignability
  false positives); union term checks (overlap, >100 terms), interface-vs-concrete comparison rules,
  some generic instantiation errors, parser-level errors go/parser reports, initialization
  cycles across files; the ported testdata set stays at 50 files.
### 2026-10-01 - Formatter: performance, layout cache, partial layout
- Speed (`GoFormatterBenchmark.reformat`, alternating runs on the same machine): 669-718 ms/MB
  before, 255-289 ms/MB after; threshold re-recorded (49.52 ms, 255.46 ms/MB), other thresholds
  unchanged. Reformat Code with a complete layout hands the engine `GoSegmentRootBlock`: flat leaf
  blocks, one per run of tokens whose whitespace already is gofmt's (a gofmt-clean file is one
  block); `GoFormattingDocumentModel` answers the engine's whitespace check from the text instead
  of a PSI lookup per block; `GoImportSorter` looks only at the top level; layout internals
  without per-leaf `getStartOffset`, boxed maps, per-flush allocations or UTF-16 buffers
  (escape character U+0000), tabwriter on primitive arrays. `GoFormattingModel` no longer rebuilds
  the file text after every whitespace change (quadratic on files with many changes) and never
  re-indents the inside of a multi-line block (the editor path shifted raw string and `/* */`
  comment lines when their first line moved).
- Layout cache: `GoLayout.cached` (CachedValue on the file and the code style settings tracker),
  used by Reformat Code, range reformat and Auto-Indent Lines; the cached layout does not keep
  the formatted text.
- Partial layout: with syntax errors, maximal runs of error-free top-level units are laid out by
  the printer (gofmt-exact inside a run); only broken declarations and the gaps before runs use
  the structural fallback (`GoLayout.covers`, `GoAstBuilder.topLevelDecls`, `GoPrinter.printDecls`).
- File edges: `GoFileEdgesPostFormatProcessor` removes whitespace before the first token and
  leaves exactly one line feed after the last (an added one gets the lexer's token type).
- `-Pgopsi.benchmark.jvmArgs="..."` passes JVM options (JFR) to the go-psi-ide benchmark JVM.
- Tests: `GoFormatterLayoutTest` (11: cache reuse and invalidation, partial layout against
  `testData/formatter/partial/partialLayout.clean.go` (gofmt output), file edges, range inside one
  function, reformat-on-save path); every `GoFormatterTest` golden also runs through the PSI-shaped
  block tree. `./gradlew :go-psi-ide:test` 250 green; `GofmtCorpusTest` 4621/4621 identical
  (20 s, was 29 s). Dev checks (not committed): 461 GOROOT files with indentation stripped and
  blanks doubled give identical output with segments and with the PSI tree; a broken function
  appended to 921 GOROOT files leaves the rest as gofmt prints it.
- Known gaps: blank lines next to a broken declaration and alignment across it follow the
  fallback; Go comments are `PsiCommentImpl` injection hosts, so the platform probes injections
  for every comment on each reformat (~40% of the benchmark; fixing it is a go-psi-core change).
### 2026-10-01 - API debts: doc comments, JvmOverloads, dumb mode, language id check, verifier cleanup, Maven local publication
- `GoNamedElement.docComment` / `docText` (go-psi-core mixins, `GoDocComments`): the doc run bound by
  `DOC_COMMENT_BINDER`; grouped specs use their own doc, then the group's (as go/doc); `var`/`const`
  definitions use their spec, fields their field declaration; `docText` ports `ast.CommentGroup.Text`
  (directives dropped). `GoFile.packageDoc` / `packageDocText`. Not stub-based. Sample consumer's
  inspection uses `docComment`. Tests: `GoDocCommentsTest`.
- `@JvmOverloads` on Kotlin default-argument API (constructors, objects); explicit shorter overloads on the
  interfaces `GoPackageResolver.packageOf`, `GoSemanticService.lookupFieldOrMethod`/`render`.
  `API-SURFACE.txt` updated.
- Dumb mode covered by `GoDumbModeTest` (typeOf, declarationType, resolve, check, packageOf,
  resolveImport in dumb mode equal the smart-mode answers, no `IndexNotReadyException`); go-psi-semantic uses
  no index, so no service change was needed. docs/API.md describes the tested behaviour.
- Language id `"Go"` documented with a plugin.xml snippet; `GoLanguageIdCheckActivity` (go-psi-ide
  `postStartupActivity`) logs a warning naming plugins that register language-keyed extensions with
  `language="go"` (reads the extension point beans, no internal API); `GoLanguageIdCheckTest` covers the pure logic.
- Plugin Verifier cleanup: the deprecated `CompletionConfidence.shouldSkipAutopopup` overload is replaced; the
  3 remaining experimental usages are documented in docs/API.md "Platform API notes".
- Maven local publication of `go-psi-core` and `go-psi-semantic` (`publishToMavenLocal`, sources jars, POM
  without the platform dependency); docs/API.md explains the non-plugin JVM use and its `CoreApplicationEnvironment` limitation.
- Gates: `./gradlew build`, `publishToMavenLocal`, sample consumer `test`. No version bump.

### 2026-10-01 - Phase 6d: inspections, quick fixes, semantic highlighting
- go-psi-ide `inspections`: nine `localInspection`s (group "Go") over
  `GoSemanticService.check(file)`, one per diagnostic family so each can be toggled:
  `GoUnresolvedReference`, `GoUnusedImport`, `GoUnusedVariable`, `GoUnusedLabel` (WARNING,
  greyed), `GoTypeMismatch`, `GoCallArity`, `GoDuplicateDeclaration`, `GoGenerics`, `GoChecker`
  (catch-all for the remaining classes; ERROR). `GoDiagnosticsInspectionBase` maps
  `GoDiagnostic.code` to inspections (`GoDiagnosticClasses`); `GoDiagnosticsCache` runs `check`
  once per file and modification (CachedValue on the Go trackers, lazily under a lock, identical
  diagnostics deduplicated). Messages are the checker's text; multi-line go/types messages are
  joined with `; `. `cannot infer` (11 corpus false positives) is hidden unless the
  `GoGenerics` option enables it; `assignability` stays on.
- Quick fixes: add import for an unresolved package qualifier (std/module packages with that
  name that export the selected member, via `GoImportPaths` + `GoImportInserter`); remove unused
  import; optimize imports (`lang.importOptimizer` `GoImportOptimizer`: removes unused imports,
  sorts runs like gofmt); unused variable: remove (no side effects) / `_ = value` / rename to `_`;
  wrap in conversion `T(x)` for assignability errors when the value converts (never int ->
  string, never untyped constants).
- Suppression: `lang.inspectionSuppressor` `GoInspectionSuppressor` for
  `//noinspection Id[,Id]`/`ALL` above a statement, top-level declaration or grouped import spec,
  and above the package clause for the file; suppress fixes for statement, declaration, file.
- Semantic highlighting: `GoSemanticHighlightingAnnotator` colours identifiers by resolved kind
  (package, type, type parameter, function declaration/call, method declaration/call, field,
  parameter, local/package variable, constant, label, builtin type/function/constant) with 16
  new `GoHighlightingColors` keys on the colour settings page (fallbacks to
  `DefaultLanguageHighlighterColors`); resolve-only, not dumb-aware.
- Tests: `inspections.GoInspectionsTest` (17: per-inspection highlighting fixtures under
  `testData/inspections`, `cannot infer` option, suppression, suppress fixes, one check per
  modification, deduplication), `GoQuickFixesTest` (14, `testData/inspections/fixes`),
  `GoInspectionsGorootTest` (2: all inspections report nothing on 10 GOROOT files; a planted error is reported),
  `annotator.GoSemanticHighlightingTest` (3, golden `testData/highlighting/semantic.txt`).
- Known gaps: no missing-return inspection (not in the checker); optimize imports keeps the
  existing blank-line groups (no goimports regrouping); no fixes for `if`/`for` header and
  type-switch variables.
### 2026-10-01 - Phase 7: sample consumer plugin, API surface check
- `samples/consumer-plugin`: standalone Gradle build (IPGP 2.19.0, Kotlin 2.4.20 with apiVersion 2.3, IDEA 2026.1.5)
  consuming go-psi through `localPlugin(<plugin ZIP>)`; `<depends>io.github.dvislobokov.gopsi</depends>`; one
  `localInspection` (exported function without doc comment) using only public API; `BasePlatformTestCase` test green.
  Prerequisite: `./gradlew :plugin:buildPlugin`.
- `apiSurfaceCheck` (root alias of `:plugin:apiSurfaceCheck`, part of `check`): ASM scan of the API packages
  (`lang.psi`, `lang.stubs`, `semantic.api`, `semantic.types`, `project.api`, `ide.completion.api`) compared with
  the committed `docs/API-SURFACE.txt`; `-Dgopsi.updateApi=true` rewrites it.
- `@ApiStatus.Internal` added to leaked internals in `lang.stubs`: all `Go*ElementType` classes, `GoStubElementType`,
  `GoStubBuilder`, `GoFileElementType` (no behaviour or stub-version change).
- `docs/API.md`: guide for library consumers (entry points, threading, dumb mode, stability promise `0.0.x` = none).
- Known gaps: no `docComment` accessor on declarations (doc comments are the first child of the declaration);
  generated `GoTypes.Factory`/`GoVisitor` cannot be annotated; `GoElementTypes.FILE` exposes `GoFileElementType`.
### 2026-10-01 - Semantic follow-ups: alias rendering, originalFile
- `byte`/`rune` keep their spelling: `GoBasicType.BYTE`/`RUNE` are distinct instances of kind
  `UINT8`/`INT32` with the name `byte`/`rune`; `equals`/`hashCode` compare kinds, so `identical`,
  `assignable` and caches treat them as `uint8`/`int32`. `GoBasicType.byName` returns them for the
  source names; string indexing yields `byte`, string range and the default type of an untyped
  rune yield `rune` (as go/types). Composite types keep the marker (`[]byte`).
- Completion/intention copies: `GoPsiUtil.originalFile`/`originalVirtualFile`; `GoPackageModel`
  (`computeScope`, `resolveImport`, `packagePathOf`) and `GoSemanticService.packageOf` derive
  package/directory/module from the original file. `GoCompletionSemantics` lost its text-range
  mapping into the original file (about 75 lines): the copy is resolved and typed directly.
- Tests: `GoFileCopyTest` (copy resolves package-level names, sibling-file names and imports like
  the original), `GoTypeOfTest` goldens (`byte`/`rune`/`uint8`/`int32` spellings),
  `GoTypePredicatesTest.testByteRuneAliasesAreIdentical`; `GoMemberCompletionTest` expectation
  `[]uint8` -> `[]byte`.
### 2026-10-01 - Phases 5b, 6d (completion) and 7 (benchmarks) accepted and merged
- Orchestrator verification on the merged main: build green (core 74, semantic 92, ide 154
  tests); semantic gates: GOROOT/src 2.52M references, 128 unresolved (was 566); golang.org/x
  6563 (was 8195, mostly modules missing from the cache); check gate 29 false positives on
  3201 GOROOT files (was 3259 on the first run), 0 crashes, slowest files ~7 s on first touch.
- Known gaps carried forward: ERROR-site coverage of `internal/types/testdata` at 55% (constant
  overflow, shifts, missing return, duplicate cases, init cycles, iota placement, unsafe
  sizes); `byte`/`rune` rendered as `uint8`/`int32`; completion runs on a file copy mapped
  back to the original (`GoPackageModel` should honour `originalFile`); benchmark tolerance
  raised to +50% because of shared-machine noise.

### 2026-10-01 - Phase 5b: generic inference and diagnostics (0.0.7)
- Unification-based type inference (`GoUnifier`, `GoInference`): typed arguments, constraint
  type inference through core types (incl. channel direction rules) and method requirements,
  untyped-constant defaulting, generic function values as arguments, partial explicit
  instantiation (`GoSignatureType.partialSubst`), interface inference, fixed-point substitution
  with cycle detection. `GoExpressionTyper.calleeSignature(call)`; `unsafe.*` typing.
- Type sets and constraint satisfaction: `comparable` marker, implicit constraint interfaces,
  `GoTypePredicates.satisfies` / `satisfactionFailure` / `isKnown` / `identicalIgnoreTags`,
  `GoLookup.alternativeMember`, method lookup on type parameters through constraint methods,
  field selection through named pointer types, methods declared on aliases, `var (...)` /
  `const (...)` group scoping, `new(expr)`, `len`/`cap` constants.
- `GoSemanticService.check(file)` -> `GoDiagnostic` list: a go/types-wording checker covering
  undefined names/members (with lookup hints), unused imports/variables/labels, redeclarations,
  assignability and representability, assignment arity, call arity and `...`, generic
  instantiation and constraint errors, conversions, operators and shifts, division by zero,
  constant overflow, indexing, composite literals, type assertions/switches, conditions,
  range, defer/go, break/continue, expression statements, builtins (see `docs/SEMANTIC.md`).
- Grammar: `const x T` without values is accepted (go/parser); the checker reports
  `missing init expr`.
- Tests: `GoTypeOfTest.testInference` (66 goldens, stdlib generics), `GoResolveTest.testGenerics2`
  (methods on inferred instantiations, promoted fields through embedded pointers),
  `GoCheckTest` (9 fixture files, zero false positives on `clean.go`), `GoTypesTestdataTest`
  upgraded to the go/types `check_test.go` protocol over 50 testdata files (1812 ERROR sites:
  997 matched = 55%, the rest allowlisted with reasons, 0 false positives),
  `GorootCheckCorpusTest` (false-positive gate over GOROOT/src: 3201 files, 29 false positives,
  0 crashes, 0 timeouts with a 20 s per-file budget, 33 ms per file).
- Corpus effect of the inference work: unresolved references on GOROOT/src 566 -> 128
  (0.005%), on golang.org/x 8195 -> 6563 (remaining: missing modules in the cache).
- Scoping fix: receiver, parameter and result names are visible in the function body only
  (`func (lines *lines) add()`, `newDRBG[H hash.Hash](hash func() H)`); `unsafe.Pointer` maps to
  the basic unsafe pointer type; type-switch bindings use the clause of their own switch.
### 2026-10-01 - Phase 6d: completion
- go-psi-ide `completion`: `GoCompletionContributor` (`order="first"`, dumb-aware; providers for
  import path strings, labels, the package clause and identifiers) with `GoCompletionContext`
  (position kinds: statement/expression, type, receiver type, selector, type selector, struct
  literal key, import path, label, package clause, func name, switch/select body, top level,
  none) and `GoCompletionConfidence` (no autopopup in comments, strings, runes, numbers; import
  paths do pop up).
- Candidates: scope walk mirroring `GoScopes` (locals before the caret, init/range/type-switch
  variables, signatures, receiver type parameters, package level across files via stubs, imports
  and dot imports, universe); members after `.` (fields with promotion depth, method sets `T` vs
  `*T` by addressability, interface methods, method expressions `T.M`, exported package members,
  members of unimported packages); struct literal keys (unused, promoted and embedded fields);
  labels (`break`/`continue` only enclosing targets); import paths (GOROOT walk cached per GOROOT,
  module-cache modules cached per directory, main modules cached on the project-model tracker);
  unimported packages by name (std first, then build-list modules) with auto-import
  (`GoImportInserter`: goimports-style std/module groups, sorted, single import turned into a
  group).
- `GoLookupElementFactory`: icons from `GoIdeIcons`, signature/type/kind/path tail texts,
  `()` insert handler (caret inside unless no parameters; none when a function value is
  expected), `pkg.` + autopopup, `Key: `, keyword suffixes; snippets `iferr` (zero values of the
  enclosing results), `for range xs`, `switch {}`, `select {}`.
- Ranking: `GoCompletionWeigher` (`weigher key="completion"`, after `priority`, before `prefix`):
  expected-type match (assignment, var type, argument, return, binary operand, literal
  element/field/key, send, switch tag, conditions), ranker score, scope distance;
  `GoAlphabeticalWeigher` as the last tie-break. New EP `io.github.dvislobokov.gopsi.completionRanker`
  (`GoCompletionRanker` in `ide.completion.api`, no implementation; docs/ML.md section 2).
- Completion runs on a file copy without a directory; `GoCompletionSemantics` maps expressions
  before the caret to the original file for `typeOf` and resolve. No change in go-psi-semantic or
  go-psi-core; no stub/index version change.
- Tests (83): `GoScopeCompletionTest` (15), `GoMemberCompletionTest` (19), `GoKeywordCompletionTest`
  (19), `GoInsertCompletionTest` (11), `GoRankingCompletionTest` (9), `GoCompletionEnvironmentTest`
  (10: std and on-disk module import paths, module auto-import, comments/strings, confidence, dumb
  mode, stub-only candidates under `setAssertOnFileLoadingFilter`, 3000-line/500-symbol file
  under 300 ms, measured ~100 ms warm). Fixture `testData/completion/module`.
- Known gaps: the type model prints `byte`/`rune` as `uint8`/`int32` in tail texts; untyped
  package-level vars/consts of other files show no type (their initializers are not stubbed);
  method expressions on `(*T)`; the platform's start-vs-middle-match classifier runs before every
  weigher, so the expected-type order applies within each match class.
### 2026-10-01 - Phase 7: benchmarks
- `./gradlew benchmark` (root aggregate) and `:go-psi-{core,semantic,ide}:benchmark`
  (`intellijPlatformTesting.testIde` tasks running `*Benchmark` classes, excluded from `test`;
  pass-through of `gopsi.goroot`, `gopsi.gomodcache`, `gopsi.benchmark.update`,
  `gopsi.benchmark.tolerance`). Harness `tools/benchmark/BenchmarkSupport.kt` (own median over
  3 warm-up + 5 measured iterations, not `Benchmark.newBenchmark`, which cannot do the stored
  per-machine threshold comparison); thresholds in `testData/benchmark/thresholds.json` (+30%).
- Suites: core lexer/parser (incl. incremental re-parse)/stubs/index; semantic resolve and typeOf
  (cold and warm), project model; ide formatter, structure view, folding. See docs/TESTING.md.
- Known gaps: completion latency benchmark (completion not merged yet); thresholds are
  machine-specific and noisy on a loaded machine.
### 2026-10-01 - ML-0: Go corpus tooling (`tools/ml`)
- New `uv` project `tools/ml` (`gopsi-ml`), offline only. `gopsi-corpus select` turns a deps.dev
  BigQuery export into `modules.lock` (permissive SPDX only; golang.org/x, top libraries by direct
  dependents, top applications by stars); `fetch` downloads module zips straight from
  proxy.golang.org into a GOMODCACHE-layout directory, resumable; `manifest` catalogues GOROOT
  (`std` and `cmd`), fetched modules and optionally the local GOMODCACHE into
  `manifest.parquet` (license, module-level 80/10/10 split, test/generated/cgo flags, content
  duplicates). BigQuery queries documented in `tools/ml/README.md`.
- Tests: 54 pytest cases (version ordering, license classification incl. MPL/GPLv3 naming other
  GNU licenses, SPDX expressions, zip filtering, nested modules, source precedence); ruff and
  pyright clean. Smoke run: 3 modules fetched from the proxy; local manifest 438 modules,
  35 355 files (27 modules rejected by license).
- Gaps: no dependency download for fetched modules yet (needed for resolve-based features);
  `docs/ML.md` still to be updated with the dataset decisions.

### 2026-10-01 - Phase 6c: navigation, usages, rename, documentation
- go-psi-ide `navigation`: Go to Declaration through references (verified into GOROOT:
  `fmt.Println` -> `$GOROOT/src/fmt/print.go`, import path -> package directory);
  `GoTypeDeclarationProvider` (through pointers/slices/arrays/channels/map values/single results,
  generic origin, type parameters); `GoImplementationSearch` (`definitionsScopedSearch`:
  interface -> implementing types, interface method -> implementing methods) with
  `GoTargetElementEvaluator`; `GoGotoSuperHandler` (method -> interface methods, type ->
  interfaces); `GoImplementationLineMarkerProvider` (slow pass, "Is implemented by" /
  "Implements" / "Implements method in"). `GoImplementations`: candidates from
  `GoMethodFingerprintIndex` / `GoMethodSpecFingerprintIndex`, type specs from `GoTypesIndex` in
  the package directory, then `GoSemanticService.implements`; stub-only (asserted).
- `usages`: `GoUsageTypeProvider`, `GoReadWriteAccessDetector`, `GoFindUsagesHandlerFactory`
  (method usages optionally include the implemented interface methods), `GoReferencesSearch`
  (imports whose local name differs from the last path segment), exit-point highlighting
  `GoHighlightExitPointsHandlerFactory` (`func`/`return` -> returns and panics; `break`/`continue`
  and `for`/`switch`/`select` -> loop keyword and jumps, labels honoured).
- `rename`: element manipulators for reference expressions, type references, label refs and
  import specs; `GoRefactoringSupportProvider` (in-place for local use scopes),
  `GoNamesValidator`, `GoRenameInputValidator`, `GoRenameMethodProcessor` (interface method +
  implementations renamed together, GoLand-style prompt from an implementing method).
- `documentation`: `GoDocumentationTargetProvider`/`GoDocumentationTarget` (gopls-style
  definitions, doc comments via a `go/doc/comment` port `GoDocHtml`, package docs from `doc.go`,
  Struct/Interface/Package/File sections), `GoParameterInfoHandler`, `GoExpressionTypeProvider`.
  `docs/IDE-FEATURES.md` lists every EP and behaviour.
- Core (allowed narrowing): `GoUseScopes` + `getUseScope()` overrides in `GoNamedElementImpl` and
  `GoLabelDefinitionMixin` (imports: file; labels/locals: enclosing function; params/type params:
  declaring signature owner; unexported package-level: package directory; exported: project +
  libraries). No stub/index version change.
- Semantic (bug fix): `GoFieldKeyReference` now lives on the key's `GoReferenceExpression` (the
  element it is obtained from) instead of the `GoKey` parent; the old shape violated
  `PsiReferenceService`'s same-element contract and broke Find Usages/rename of fields used as
  struct literal keys. Regression: `GoFindUsagesTest`, `GoRenameTest.testFieldWithLiteralKeysAndPromotion`.
- Tests: go-psi-ide test plugin descriptor now includes `go-psi-semantic.xml`; the `test` task
  passes `-Dgopsi.goroot`/`-Dgopsi.gomodcache`. New: `GoNavigationTest` (13),
  `GoImplementationsAstLoadingTest` (1), `GoFindUsagesTest` (4), `GoHighlightUsagesTest` (6),
  `GoRenameTest` (13), `GoDocumentationTest` (12); goldens `testData/navigation/shapes/gutters.txt`,
  `testData/usages/kinds/usages.txt`, `testData/documentation/doc/expected.html`.
- Known gaps: generic interfaces/instantiation-dependent implementations, sub-interfaces as
  implementations, non-navigable doc links, `C.x` goes to `import "C"`.
### 2026-10-01 - parser conformance gates merged; unclosed-block recovery
- Merged the go/ast diff gate (0 mismatches on 6727 GOROOT files / 11.06M nodes and on 24357
  golang.org/x files / 50.6M nodes) and the mutation fuzz gate (6000 mutants, 0 hard failures).
- Grammar: an unclosed `{` now stops at a `var`/`const`/`type`/`import`/`func` keyword at
  column 0 (`GoParserUtil.columnZeroDeclaration`), so the following top-level declarations
  parse cleanly; fuzz locality violations 7 -> 0; new recovery golden
  `UnclosedBlockBeforeDecls`.

### Parser conformance gates: go/ast diff and fuzzing
- `GorootAstDiffCorpusTest` + `GoAstMapping`/`GoAstDump`: the PSI tree, normalised to go/ast shape,
  is diffed node by node (kind, byte range, a few fields) against `astdump walk -ast`. Result:
  6727 GOROOT/src files, 11,058,865 go/ast nodes, 0 mismatches (24,357 files / 50.6M nodes of
  `$GOMODCACHE/golang.org/x` also 0, via `-Pgopsi.astdiff.root`). 2 GOROOT files (`opGen.go`,
  `rewriteAMD64.go`) are skipped because the platform builds no PSI for them (size limit).
  Metrics: `testData/metrics/goroot-src-ast-diff.json`; allowlist: `testData/parser/ast-diff-allowlist.txt`
  (empty). The corpus run accepts `-Pgopsi.astdiff.root` (one line in `go-psi-core/build.gradle.kts`).
- `GoParserFuzzCorpusTest` (300 GOROOT files x 20 mutations = 6000 mutants; 0 exceptions, 0 lossless
  violations, max parse 25 ms) and the fast `GoParserFuzzTest` (10 files x 5). Known gap: 7 of 2537
  checked single-token mutants lose 3-5 error-free top-level declarations because an unbalanced `{`
  swallows the following `var`/`type`/`const` declarations (the block only resynchronises at
  `func IDENT`); tracked as `localityViolations` in `testData/metrics/goroot-src-fuzz.json`.

### 2026-10-01 - Phases 6a and 6b accepted
- Orchestrator verification: full build green; `go-psi-ide` 71 tests; gofmt corpus gate
  reproduced: 4621/4621 GOROOT/src files byte-identical after Reformat Code (plus 2155/2155
  under `cmd/`). Structure view, breadcrumbs, folding, brace matching, commenter, quote
  handler, Goto Symbol/Class, find-usages provider and TODO patterns are in.
- Formatter design: a go/printer + tabwriter port computing exact whitespace, fed to the
  platform engine through `GoBlock` spacings/indents (`docs/FORMATTER.md`); Enter indentation
  is structural and independent of the printer.

### 2026-10-01 - Phase 6b: gofmt-compatible formatter
- go-psi-ide `formatter.printer`: a port of `go/printer` (printer.go + nodes.go: exprList
  alignment sections, binary-expression cutoffs, fieldList/valueSpec cells, keepTypeColumn,
  one-line function bodies and field lists via nodeSize, comment interspersing) over a `go/ast`
  mirror built from PSI (`GoAstBuilder`), and of `text/tabwriter` + the trimmer
  (`GoAlignmentStrategy`, gofmt's configuration). `GoLayout` = the exact whitespace gofmt puts
  before every token/comment; it is verified to reproduce every token and comment and to keep a
  line break wherever an inserted semicolon is.
- `formatter.GoFormattingModelBuilder` (`lang.formatter`): `GoBlock` per PSI node; spacing
  (line-feed count or exact spaces) and indents (`getSpaceIndent` deltas between line-start
  blocks) come from the layout, so the platform engine applies gofmt's output as whitespace-only
  edits (range reformat, reformat on save, Auto-Indent Lines). `GoFormattingModel` keeps
  SEMICOLON_SYNTHETIC tokens. Enter: structural `getChildAttributes`/`isIncomplete`. Files the
  printer cannot handle (syntax errors) fall back to structural indents + `GoSpacingBuilder`.
- `GoImportSorter` (`preFormatProcessor`): sorts runs of parenthesised import specs like
  `ast.SortImports` (path, name, comment; comments stay on their spec's line); pre-format so that
  alignment is computed on the sorted order.
- Code style: `GoLanguageCodeStyleSettingsProvider` (tabs, TAB_SIZE = INDENT_SIZE = 4,
  LINE_COMMENT_ADD_SPACE, comments at code indentation, gofmt-clean code sample) and
  `GoCodeStyleSettingsProvider` (indent tab only). `GoEditorTest.testCommentLine` now expects
  `\t// x := 1` (new commenter defaults).
- Tests: `GoFormatterTest` (39: 28 gofmt goldens in `testData/formatter`, each with token-stream
  and idempotence checks; registration, code sample, Reformat action, range reformat, kept
  `;`/`,`/parentheses, syntax-error fallback, Auto-Indent Lines, Enter in blocks/case
  bodies/structs). `GofmtCorpusTest` (`:go-psi-ide:corpusTest`): Reformat Code over
  `$GOROOT/src` compared byte-for-byte with gofmt's output (the file itself, or `gofmt` output for
  the files `gofmt -l` lists): 4621/4621 identical without `cmd/`, 2155/2155 for `cmd/`
  (`-Pgopsi.formatter.corpus.includeCmd=true`); metrics `testData/metrics/goroot-src-gofmt.json`.
  `docs/FORMATTER.md`.
- Known gaps (none occur on gofmt-clean code): tokens gofmt drops (explicit `;`, trailing `,` on
  the closer's line, redundant parentheses) are kept; comment text is never rewritten (no `//`
  trimming, doc-comment or `/* */` re-indentation); numbers/import paths are not normalised;
  duplicate imports are kept; a syntax error disables the layout for the whole file; the layout is
  not cached between requests.

### 2026-10-01 - Phase 5a accepted
- Orchestrator verification: build green, semantic corpus gates reproduced (GOROOT/src 2.52M
  references, 566 unresolved = 0.022%; golang.org/x 4.39M references, 8195 unresolved = 0.19%,
  mostly missing modules in the cache and generic inference left to 5b); imports gate 0.
- Core: `GoNamedElementImpl.getPresentation()` delegates to the `itemPresentationProvider`
  from go-psi-ide, so Goto Symbol/Class popups show signatures and locations.

### 2026-10-01 - Phase 5a: types, scopes, resolve (0.0.6)
- Type model (`semantic.types`): `GoType` hierarchy (basic incl. untyped kinds, array, slice,
  pointer, map, chan, tuple, struct, signature, interface with lazy type sets, named with lazy
  underlying/methods, type parameter with bound/core type, union, unknown), predicates
  (identical, assignable, convertible, comparable, implements), `LookupFieldOrMethod` BFS and
  method sets, gopls-style renderer, `GoConstant` values.
- Scopes and references: `GoScopes` (blocks with declaration-before-use, clauses, init
  statements, range vars, signatures, receiver type params, type spec params, file imports,
  package via stubs, dot imports, universe), `GoPackageModel`, `GoUniverse`; references for value,
  type, label, import path and struct literal keys through the core `GoReferenceProvider` hook;
  `GoResolver` with per-element caching on the Go trackers.
- Expression typing and constants: `GoTypeBuilder` (stub-first), `GoExpressionTyper` (literals,
  selectors, calls incl. builtins/conversions/explicit instantiation, composite literals,
  index/slice, operators, `var`/`:=`, `range` kinds, type-switch bindings, method values and
  expressions, minimal argument-based generic inference), `iota` tables.
- Public API `GoSemanticService`; `GoTrackers` (out-of-block + per-file + project model).
- Core: `GoReferenceProvider` service interface and mixins on `ReferenceExpression`,
  `TypeReferenceExpression`, `LabelRef`, `ImportSpec` (`getReference()`).
- Tests: resolve markers (11 groups), 150+ `/*T:*/` type checks, constants, pure type-model
  tests, AST-loading assertions for cross-file resolve, go/types testdata harness (16 files),
  corpus gates over GOROOT/src and golang.org/x with metrics. `docs/SEMANTIC.md`.

### 2026-10-01 - Phase 6a: editor features (syntax level)
- go-psi-ide, resolve-independent: `editor.GoBraceMatcher` (`()`, `[]`, structural `{}`),
  `editor.GoQuoteHandler` (`"`, `` ` ``, `'`; escaped closing quotes stay open),
  `editor.GoCommenter` (`//`, `/* */`, no doc comment syntax), `editor.GoIndexPatternBuilder`
  (TODO items without the comment delimiters), `editor.GoWordsScanner` + `GoFindUsagesProvider`
  (kinds: function, method, type, variable, constant, parameter, field, label, package).
- `folding.GoFoldingBuilder` (DumbAware): function and function literal bodies, composite
  literal values, struct/interface bodies, parenthesised import/const/var/type groups, multi-line
  block comments, runs of 3+ whole-line `//` comments; imports collapsed per "Fold imports".
- `structure.GoStructureViewFactory`/`GoStructureViewModel`: package-level declarations in text
  order, methods under their type when it is declared in the same file, fields, interface methods
  and embedded elements as children, grouped specs flattened; signature text
  (`Map[K comparable, V any](m map[K]V) (map[K]V, error)`); sorters alphabetical, visibility,
  kind. `structure.GoBreadcrumbsProvider`: functions, `(T) M()`, types, fields, interface
  methods, `func()`, `if`/`for`/`switch`/`select`/`case`/`default`.
- `navigation.GoGotoSymbolContributor` (public + private names indices) and
  `GoGotoClassContributor` (types index, `pkg.T` qualified names), stub-only;
  `navigation.GoItemPresentation` (+ `itemPresentationProvider` for `GoNamedElement`), location
  `pkg (dir/file.go)`. Placeholder SVG node icons in `GoIdeIcons` until the transplant.
- Tests (go-psi-ide, 6 classes, 32 tests): `GoEditorTest`, `GoFoldingTest`
  (`testData/folding`), `GoStructureViewTest` (goldens `testData/structure`), `GoBreadcrumbsTest`,
  `GoGotoContributorTest`, `GoSyntaxHighlighterTest`. go-psi-ide has its own test `plugin.xml`.
- Known gaps: spellchecker support skipped (`com.intellij.modules.spellchecker` is a separate
  product module that needs a `bundledModule` dependency and a plugin.xml `<depends>`); goto
  popups render names only until the core PSI's `getPresentation()` delegates to
  `ItemPresentationProviders`.

### 2026-10-01 - Phase 4 accepted
- Orchestrator verification: build green (semantic: 59 tests in 6 classes), import-resolution
  gate on GOROOT/src: 748 packages, 16066 imports, 0 unresolved; MVS matches `go list -m all`
  on the real fixture without running `go`.
- Decisions: library roots stay on by default (`gopsi.libraryRoots`), to be re-decided at the
  transplant; the async `go list -m` fallback may use the network like gopls does.
- Core fix: `GoFileHeaderScanner.parseBuildConstraint` is now a line-by-line port of
  go/build `parseFileHeader`/`shouldBuild` (`//go:build` counts anywhere in the leading comment
  run, even directly above `package`; `// +build` only before the last blank line; no space
  allowed in `//go:build`). `STUB_VERSION` 3, `GoBuildTagsIndex` version 2.

### 2026-10-01 - Phase 4: project model (0.0.5)
- Public API `gopsi.project.api` (go-psi-semantic): `GoVersion` (go/version ordering),
  `GoToolchainInfo` + `GoToolchainProvider`, `GoBuildContext`/`GoPlatforms`,
  `GoBuildConstraintEvaluator` (`//go:build`, `// +build`, file name suffixes, `unix`,
  ios/android/illumos implications, `cgo`, `gc`, `goN.M`, custom and tool tags), `GoModule`,
  `GoModuleGraph`, `GoModuleGraphProvider`, `GoPackage`, `GoImportResolution`,
  `GoPackageResolver`. Described in `docs/PROJECT-MODEL.md`.
- Implementation `gopsi.project.impl`: `DefaultGoToolchainProvider` (GOROOT env, `go` on PATH,
  default paths, `$GOROOT/VERSION`, `go env -w` file; `go env -json` once on a pooled thread),
  `GoModFileParser` (go.mod with all directives, go.work, go.sum, vendor/modules.txt),
  `GoModuleCacheLayout`, `SemVer`, `Mvs` (graph pruning, excludes, tidy root raising),
  `GoModuleGraphBuilder` (workspaces, replace, vendor mode), `GoListModuleGraph` fallback
  (`go list -m -json -e all`, background, logged), `DefaultGoModuleGraphProvider`,
  `DefaultGoPackageResolver` (std/GOROOT vendoring, module cache, replace, vendor, workspace,
  nested modules, `internal`, `C`, relative imports), `GoProjectModelTracker` (+ BulkFileListener),
  `GoRootsProvider` (GOROOT/src and module dirs as synthetic libraries, registry
  `gopsi.libraryRoots` default true, `gopsi.libraryRoots.includeCmd` default false).
- Registered in `go-psi-semantic.xml`, included from `plugin.xml`. No go.mod file type/language.
- Tests (go-psi-semantic, 6 classes, 59 tests): `GoBuildConstraintEvaluatorTest` (ports of
  go/build/constraint expr_test/vers_test and go/build build_test/syslist_test),
  `GoModFileParserTest`, `MvsTest` (vgo-mvs examples, pruning, real fixture
  `testData/project/mvs-real` vs golden from `go list -m -json all`), `GoPackageResolverTest`
  (fixtures `testData/project/{simple,workspace,vendor,replace-local,nested-module,buildtags}`),
  `DefaultGoToolchainProviderTest`, `GoRootsProviderTest`.
- New gate `:go-psi-semantic:corpusTest` (`GorootImportsCorpusTest`): Go 1.27.1, 748 packages,
  4930 files, 16066 imports, 119 `C`, 912 via GOROOT vendor dirs, 0 unresolved, 0 internal
  denied, 0 outside GOROOT (`testData/metrics/goroot-src-imports.json`). Core corpus gates
  unchanged.
- Known gaps: core's header scanner ignores a `//go:build` line not followed by a blank line
  (go/build accepts it); the resolver evaluates file text itself. No go.mod PSI (destination
  plugin owns it); `go list` fallback is asynchronous only; GOPATH mode is minimal.

### 2026-10-01 - Phase 3 accepted; Go 1.27.1 re-baseline
- Orchestrator verification of Phase 3: build green (17 test classes in core), stub corpus on
  GOROOT 1.27.1: 6823 files, 2.0M stubs, 0 failures, 0 round-trip mismatches.
- Parser fixes for Go 1.27 conformance (go/parser parity): `...` only on the final parameter and
  never in a result list (`GoParserUtil.variadic`, backward scans for context), `a, b ...int`
  rejected, interface methods with type parameters wrapped in an error element
  (`noTypeParameters`); `GoShortParsingTest` is now driven by `invalid/index.txt` (empty expected
  message = must parse cleanly, which covers Go 1.27 generic methods).
- Corpus gates re-baselined on Go 1.27.1: lexer 8078 files / 0 BAD_CHARACTER (2 intentionally
  invalid test inputs exempt) / 0 token mismatches (allowlist offsets refreshed); parser GOROOT
  6823 files, golang.org/x 24383 files, 0 error elements; 2 oversized generated files have no PSI.
- New parser case `Go127.go` (generic methods, `new(expr)`, self-referential constraints,
  promoted-field literal keys) with golden.

### 2026-10-01 - toolchain updated to Go 1.27.1
- Local GOROOT is now Go 1.27.1 (8079 files, includes generic methods). Target grammar scope
  raised to Go 1.27; parser test data refreshed from the 1.27.1 GOROOT; corpus metrics will
  be re-baselined.

### 2026-10-01 - Phase 3: stubs and indices (0.0.4)
- Named-element PSI: `GoNamedElement` (`PsiNameIdentifierOwner` + `NavigatablePsiElement`,
  `isPublic()`), stub-based bases `GoStubbedElementImpl`/`GoNamedElementImpl`, plain base
  `GoCompositeElementImpl`, `GoFunctionOrMethodDeclaration`; mixins for import specs (name = alias
  or last path segment, `path`/`alias`/`isDot`/`isBlank`), embedded fields (name = last type
  identifier), type specs (`isAlias`), methods (`receiverTypeName`, `isPointerReceiver`), labels.
  Wired with Grammar-Kit attributes only (`elementTypeFactory`, `stubClass`, `mixin`,
  `implements`, global `extends`); parser goldens unchanged.
- Stubs for everything outside function bodies (39 stub element types, `docs/GRAMMAR.md`
  section M): package clause, imports, funcs, methods, receivers, signatures, parameters,
  results, type specs, type parameters and constraints, var/const declarations/specs/definitions
  (const `iota` index and initializer texts), struct fields, embedded fields, tags, interface
  method specs and all type nodes. `GoFileStub`: package name, `//go:build` / `// +build`,
  test-file and cgo flags. `STUB_VERSION` 2; external ids `go.<NAME>` via
  `stubElementTypeHolder` on the generated `GoTypes`.
- Stub indices: packages, functions, methods by receiver type, types, all public/private names,
  method and interface-method fingerprints (`name/arity`). File-based (lexer-only) indices:
  file imports (path -> files), build tags (file -> constraint).
- `GoFile` API (`packageName`, `isTestFile`, `isCgo`, `buildConstraint`, `imports`, `functions`,
  `methods`, `types`, `vars`, `consts`), stub-first; `GoElementFactory`.
- Tests: `GoStubsTest` (stub trees of all parser cases and `testData/stubs` from the builder, the
  indexer entry point and a serialization round trip are identical; stub-backed `GoFile` API equals
  the AST-backed one; 5 golden stub dumps), `GoStubSerializationTest`, `GoIndicesTest`,
  `GoAstLoadingTest` (no AST loads with `AstLoadingFilter`), `GoNamedElementsTest`,
  `GoFileHeaderScannerTest`; corpus gate `GorootStubCorpusTest`: 0 failures, 0 round-trip
  mismatches on Go 1.24.7 (6035 files, 1744736 stubs, 2.6 s) and Go 1.27.1 (6823 files, 2005696
  stubs, 2.4 s, 2 files above the platform size limit get no PSI); metrics in
  `testData/metrics/goroot-src-stubs.json`. Parser corpus gates unchanged (0 error elements).
- Environment note: GOROOT was upgraded to Go 1.27.1 during this phase; the lexer gates now report
  46 BAD_CHARACTER in `cmd/cover/testdata/ranges/ranges.go` and 10 ASI mismatches in 2 files
  (stale allowlist offsets in `asm9.go`, `elf.go`); not addressed here.
- Known gaps: no `GoTypeAliasIndex`, `GoImportPathPrefixIndex`, go.mod index or
  `GoNonPackageLevelNamesIndex` yet; build constraints are stored raw, not evaluated (Phase 4).

### 2026-10-01 - Phase 2 accepted; migration plan
- Orchestrator verification: `./gradlew build` green, corpus gates green (GOROOT/src 6035 files,
  golang.org/x 24383 files, 0 error elements; lexer 0 mismatches); sandbox IDE loads 0.0.3.
- `docs/MIGRATION-idea-golang-support.md`: analysis of the destination plugin and the ordered
  transplant plan. Step 1 applied here: Kotlin `apiVersion/languageVersion = 2.3` (platform
  stdlib), `<incompatible-with>io.github.golangsupport</incompatible-with>`.
- `docs/GRAMMAR.md` section C corrected: `type A[P (E)]` is an array (go/parser `extractName`
  makes the parenthesised form generic only with a comma or a type-element argument).

### 2026-10-01 - Phase 2: parser (0.0.3)
- Full Go grammar in Grammar-Kit (`Go.bnf`, 103 generated PSI classes named after GoLand's
  public PSI) with `GoParserUtil` external rules porting go/parser: `exprLev` composite-literal
  gating, `type Name [` type-parameter vs array decision (`extractName`), `name [` field/param
  array vs instantiation, parameter name/type propagation, index/slice/instantiation, simple
  statement and header lookaheads (init statement, range, type switch, send vs receive),
  channel-arrow and pointer-type operand rules, doc comment binding via a left binder.
- Error recovery with per-level stop sets; `func Name` ends an unclosed block.
- Go 1.26/1.27 forms accepted (generic methods, self-referential constraints).
- Tests: 15 case goldens + parenthesised type-parameter forms, 8 recovery goldens, port of
  `go/parser/short_test.go` (100/100 valid, 50/58 invalid rejected; 8 accepted by design and
  listed), 106 GOROOT testdata files (5 with semantic-only ERROR annotations listed), mutation
  robustness test (200 mutants). Corpus gates: GOROOT/src 6035 files, 0 errors (184 ms/MB);
  golang.org/x 24383 files, 0 errors (24 generated `x/text` tables exceed the platform file
  size limit and get no PSI). Metrics in `testData/metrics/*-parser.json`.
- `corpusTest` now includes classes extending platform test bases and inherits the standard
  test classpath.
- `docs/GRAMMAR.md` sections B-K rewritten to the implemented behaviour.

### 2026-10-01 - Phase 1: lexer (0.0.2)
- Full JFlex lexer: all Go tokens (go/token names), automatic semicolon insertion in the lexer
  (`SEMICOLON_SYNTHETIC` = the newline), numeric literal forms, rune/string/raw string, Unicode
  identifiers, BOM/CRLF handling, error-tolerant unterminated literals and comments.
- `GoTokenSets`, syntax highlighter and colour settings page (`go-psi-ide`).
- Tests: lexer goldens (semicolons, numbers, strings, comments, operators, unicode), ASI table
  test, highlighter test; corpus gates: `GorootCorpusTest` (7117 files, 0 BAD_CHARACTER) and
  `GorootLexerDiffCorpusTest` (6003 files diffed against go/scanner via astdump: 0 mismatches,
  5 allowlisted multi-line-block-comment semicolon positions). Metrics in
  `testData/metrics/goroot-src-lexer.json`.
- Parser conformance data for Phase 2: port of `go/parser/short_test.go` (100 valid, 58
  invalid), 106 GOROOT files (go/parser testdata, test/syntax, 25 typeparam files, syntax
  testdata), 15 hand-written cases per `docs/GRAMMAR.md`; `tools/portshorttest`.
- `docs/GRAMMAR.md` section A updated to the verified behaviour.

### 2026-10-01 - Phase 0: bootstrap (0.0.1)
- Gradle 9.7.1 multi-module build: `go-psi-core`, `go-psi-semantic`, `go-psi-ide`, `plugin`;
  IntelliJ Platform Gradle Plugin 2.19.0, Grammar-Kit subplugin, Kotlin 2.4.20, JDK 21;
  platform IntelliJ IDEA 2026.1.5 (build 261), `sinceBuild = 261`.
- Placeholder JFlex lexer and Grammar-Kit grammar, `GoLanguage`, `GoFileType`, `GoFile`,
  `GoParserDefinition`, `GoFileElementType` (stub version 1), plugin.xml with
  `incompatible-with org.jetbrains.plugins.go`.
- Test scaffolding: `GoParsingTestCase` (goldens, `-Dgopsi.updateGoldens`), `GoLexerTestCase`,
  `GoCodeInsightTestBase`, `GoTestUtil`; smoke tests; `corpusTest` task with
  `GorootCorpusTest` (7117 files lexed, 0 failures).
- `tools/astdump` (Go): `go/scanner` token dumps and `go/ast` S-expression dumps, `walk` mode
  for corpora; used as the oracle for lexer/parser corpus diffs.
- Docs: `docs/GRAMMAR.md` (ambiguities and go/parser ports), `docs/TESTING.md`,
  `docs/AGENT_BRIEFS.md`. GitHub Actions workflow.
- Verified: `./gradlew build` green, `verifyPlugin` Compatible with 2026.1.5 and 2026.2.2,
  `runIde` sandbox loads "Go PSI (0.0.1)" and PsiViewer without errors.

### 2026-10-01 - research and planning
- Analysed GoLand 2025.1.3 plugin layout (Grammar-Kit + JFlex, stub/index set, go/types port,
  frontend/backend split), go-lang-idea-plugin, intellij-rust and other native plugins, Go 1.25
  spec, go/parser and go/types internals, gopls model. See `docs/ANALYSIS.md`.
- Wrote `docs/PLAN.md` (decisions, module layout, phases 0-7, testing strategy, live checks)
  and `CLAUDE.md`.
- Decisions: Kotlin, JFlex + Grammar-Kit, go/types port as non-PSI model, modules
  `go-psi-core` / `go-psi-semantic` / `go-psi-ide` / `plugin`, target IC 2026.1 (build 261),
  root package `io.github.dvislobokov.gopsi`.

## [0.2.1] - 2026-10-01

### Code quality
- On startup, a modal dialog (instead of a corner balloon) when `.go`, `go.mod` or `go.work` are not recognized as Go — a leftover association from another Go plugin, or a file opened before this plugin was installed — with a one-click fix to associate them back with Go.

## [0.2.0] - 2026-10-01

### Language (works without any tool)
- Grey-text idioms accepted with Tab: `if err != nil`, the matching `defer` (context, mutex, file, response body, `sql.Rows`, `wg.Done`), `if !ok` after comma-ok, error wrapping that follows the file's habit, and framework-aware error exits (net/http, gin, gRPC status).
- Completion after `return` offering the function's result values as one item; package names that start with the typed prefix ranked above gopls fuzzy matches; completion by the expected type with Smart Completion; call-argument completion with Parameter Info.
- Symbol catalogue for dependencies and the project's own packages: complete by a bare name (`Printl` → `fmt.Println`) and let the plugin write the qualifier and the import. The standard library and direct `require`s are scanned by the plugin, project packages come from the platform index.
- Russian keyboard layout in code: Cyrillic letters typed in code are inserted as the Latin symbol of their key, while strings, runes and comments keep the text as typed.
- Keyword templates, Type from JSON, missing interface methods, and HTTP-status / time-layout constants offered inline in the completion list.
- gopls code actions as separate Alt+Enter items (Extract, Inline, Invert if, Fill struct, Optimize Imports…), with imports written by the plugin when gopls leaves them out.

### gopls (LSP content module)
- Settings page generated from `gopls api-json` of the installed gopls, plus a dedicated analyzers dialog; only the difference from the plugin's defaults is stored.
- Usages, implementations and reference counts over declarations; Go to Declaration / Type Declaration / Implementation / Super Method; call and type hierarchies.
- Code lens commands routed through the plugin (`run_tests` → the test runner, `generate` → the Build window), a **Go | gopls** submenu, a server-log tool window and a status-bar widget showing the server's memory and CPU.

### Daily coding
- Alt+Insert generators (Constructor, Getters/Setters, String(), Struct Tags, Implement Interface, Test), Surround With, Complete Statement, postfix templates, 41 live templates and struct-tag completion.
- Implement Interface by Ctrl+I with a Choose-by-Name popup over project, stdlib and module interfaces.
- Navigate to Test and back, Reanalyze Project, doc-comment insertion, and a Help Page that renders the plugin's reference with the IDE's current keymap.

### Modules
- go.mod / go.work file type, highlighting, folding and parsing; a Dependencies node in the Project view; the **Go Dependencies** tool window (upgrade, tidy, vulncheck); completion of module paths and versions; and a post-save banner suggesting `go mod tidy`.

### Build / Run / Test
- Build, Vet and Generate into the Build tool window; a **Go** run configuration (`go run` / `go test`) with gutter icons and a producer.
- Test tree from `go test -json` with subtests and table cases, Rerun Failed, coverage with gutter stripes and per-package percentages, fuzzing, race, a benchmark table with per-run deltas, and a Go Tests tool window with rerun-on-save.

### Debugger (own DAP client over delve)
- Own DAP client on the XDebugger API (the platform DAP module is not in every IDE or fork): breakpoints (line, function, conditional, hit count, logpoints, exception), frames, values, stepping, Evaluate with function calls, Set Value, a Goroutines tab, hover and inline values, and Attach to Process.
- Launch, Binary, Core dump and Remote dlv-dap configurations with `substitutePath`; the debug binary is built into a temp directory and removed with the session.

### Monitoring
- **Go Monitor** tool window with CPU / memory charts and Go-runtime telemetry (GC, scheduler, threads) collected via `GODEBUG`; goroutine snapshots via delve attach.
- Test profiles (CPU / memory / block / mutex / trace) and a pprof viewer opened in an editor tab.

### Code quality
- gofmt / goimports / `golangci-lint fmt` behind Reformat Code and on save; golangci-lint in the editor (v1 and v2) with quick fixes; Reorder fields for a smaller struct.
- Russian localization of the settings pages (chosen from the IDE language or a per-plugin setting).
- A "Go on This Machine" window, one-notification install of missing tools, and a prompt to disable plugins not needed for Go.

[0.2.1]: https://github.com/dvislobokov/idea-golang-support/releases/tag/v0.2.1
[0.2.0]: https://github.com/dvislobokov/idea-golang-support/releases/tag/v0.2.0

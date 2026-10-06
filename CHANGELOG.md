# Changelog

All notable changes to the Go Project Support plugin are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Versions 0.2.14–0.2.22 are wave 2 of `docs/FEATURES.md` §11 (analysis and intentions on the native PSI; all of them act only with Language features: Built-in,
gopls keeps its own analyzers otherwise); versions 0.2.2–0.2.13 are wave 1 (editor features on the native PSI): one feature per version.
Version 0.2.77 bundles delve as sources built on the user's machine.
Versions 0.2.78–0.2.81: unreachable code greyed out; grey text by the name alone (values and field types) and next to the completion
list; types only where a type stands.
Versions 0.2.82–0.2.85: gopls is off by default and not started; go.mod shows newer versions of dependencies and update lines
itself; go.sum nested under go.mod.
Versions 0.2.86–0.2.92: GoLand run configurations understood; plugin data directory checked for running programs; nothing of
the plugin in projects without Go files; GOEXPERIMENT from a list; one `go env` per project open; project problems kept between sessions.
Versions 0.2.67–0.2.76 are the sixth batch (native lint rule engine with 136 rules, project-wide problems, grey text from context in
colours, Go settings at the root with a table of every check).
Versions 0.2.60–0.2.66 are the fifth batch (Go assembly, project-wide checks, interface hierarchy refactorings, unchecked errors, optional
golangci-lint and custom linters, Inspect Project from the Go menu, new demo and guide).
Versions 0.2.55–0.2.59 are the refactoring batch (Extract Function / Method, Inline, Change Signature, Move) and inspections in CI (SARIF).
Versions 0.2.51–0.2.54 are the third batch (GOOS/GOARCH in the status bar, unused requires, SQL in strings, Safe Delete of parameters).
Versions 0.2.48–0.2.50 are the second batch (rename package, unused parameters, regular expressions and JSON in strings).
Versions 0.2.44–0.2.47 are the first batch after wave 4 (go.mod checks, call and type hierarchy, Introduce Variable / Constant, Safe Delete).
Versions 0.2.37–0.2.43 are wave 4 (data flow): the per-function control-flow graph and analyses, then the checks built on it; every check
is gated by the false-positive corpus over GOROOT/src (`:go-psi-ide:corpusTest`, `testData/metrics/goroot-src-flow.json`). Like wave 2, they act only with
Language features: Built-in.
Versions 0.2.34–0.2.36 are the second batch of quick tasks (time layouts, directive comments, struct tag naming style).
Versions 0.2.31–0.2.33 are quick follow-ups (typed Implement Interface, doc comment and build constraint inspections).
Versions 0.2.23–0.2.30 are wave 3 (code creation: Generate, import groups, smart / chain / project-member completion, create from usage, implement missing methods).

## [0.2.195] - 2026-10-06

### Fixed — the Go menu vanished after indexing in a project without modules (GIGA IDE, seen in a log with the new journal lines)
- A directory opened as a project in GIGA IDE got no module ("0 modules added"), so the project had no content roots, the project scope of
  the index was empty and, once indexing ended, the plugin decided the project had no Go files: the menu Go, the tool windows and the
  widget went away although the walk in dumb mode had found the files. Without content roots the project directory is walked instead
  of asking the index; the journal line of the decision now also says how many content roots there are

### Fixed — a frozen IDE while the run configurations of the programs were being collected (thread dump, seen live 2026-10-06)
- The scan for `package main` directories parsed every `.go` file of the project (a stub tree built from the AST) under a blocking read
  action, which a pending write action of another plugin could not interrupt: the EDT waited on it. The scan now reads the text alone
  (`package main` and `func main(` by regular expressions, no PSI) in a cancellable non-blocking read action that restarts after a write

## [0.2.194] - 2026-10-06

### Added — the journal says why the Go menu is shown or hidden
- Go | Plugin Logs (`~/idea-golang-logs/plugin/`) records the answer of the Go-presence check of a project when it is first computed and
  whenever it changes: the answer, what gave it (the index, a walk of the content roots in dumb mode, the look at the project directory),
  the project directory and which file type owns `.go`; and once per project and place, that the menu group Go was asked and hidden. For
  a report "there is no Go menu" the journal now tells whether the plugin decided the project has no Go files or the menu is simply
  folded into the main-menu button of the new UI

## [0.2.193] - 2026-10-06

### Fixed — a 14.6 s UI freeze at the first project open after an update (seen in a GIGA IDE log, Linux)
- The page about the plugin, shown once per version, was opened from the startup activity on the EDT while the project was still
  scanning: `HTMLEditorProvider.openEditor` blocks the EDT until the editor exists, and that wait met the first JCEF start and the
  workspace model sync — the plugin at the bottom of the EDT stack in both thread dumps. The page now waits for smart mode and five quiet
  seconds, starts JCEF on a background thread first and only then opens the editor

### Fixed — the working directory of a program is the module root, not its package
- `go run` and Debug of a `Go` configuration without a working directory ran the program in the package directory (`cmd/<name>`), so a
  program looking for `./config.yaml` next to go.mod did not find it. The default is now the root of the module (the nearest `go.mod`
  upwards; the project directory without one), as GoLand's `$ProjectFileDir$`; tests keep running in their package directory, as `go test`
  does. Delve starts in the package directory as before and gets the module root as `cwd` of the launch

### Changed — plugin data directory: a fallback where programs may run
- When programs cannot run in the default data directory (the IDE cache under `~/.cache` on a noexec mount or under an execution policy:
  the bundled delve was never built there), the data moves by itself to `/home/work/<user>@<domain>/.cache/go-support` when such a work
  home exists and allows running, with a notification; the work home is looked up both as `user.name` and with the swapped
  `domain@user` / `user@domain` of a domain account, and only a work home owned by this user and not writable by the group or others is
  taken (the name is guessed: a directory of that name made by someone else is no place to build and run delve from). Without one, the
  old notification with the choice of a directory stays

## [0.2.192] - 2026-10-06

### Added — completion in comments, `//go:embed` patterns and regular expressions (GoLand parity, checked live 2026-10-06)
- Ctrl+Space in a top-level comment (file, package clause, doc comment of a package-level declaration) offers the package's types,
  functions, methods (bare name), constants and variables, as GoLand does: the documented declaration's own name first (`// Circle` above
  `type Circle`), then exported before unexported, current file first, source order; other files are read from stubs. No auto-popup in
  comments; nothing inside function bodies or on `//go:` / `//line` / `// +build` directives (`GoCommentCompletionContributor`)
- After `//go:embed ` (also inside quotes and after `all:`) the files, then directories, of the package directory or of the typed `dir/`,
  with file-type icons; names go would not embed are left out (`.` / `_` names unless `all:`, names with `"*<>?`'|:\`, symlinks, directories
  of another module); a prefix with glob characters, `..` or a leading `/` gets nothing (`GoEmbedCompletionContributor`)
- Completion after `\` in the RegExp injected into `regexp.MustCompile` / `Compile` / `MatchString` / … lists the RE2 escapes with the
  descriptions of `regexp/syntax` (`\d digits (== [0-9])`, `\A`, `\z`, `\b`, `\Q`/`\E`, `\x{10FFFF}`, …), `\p{…}` for the Unicode categories
  and the scripts of `unicode.Scripts`, and script names after `\p{`. The RE2 language host used to give the platform's RegExp completion
  empty tables, hence 0 rows (`GoRegExpSyntax`, `GoRegExpLanguageHost`)

## [0.2.191] - 2026-10-06

### Added — completion rows and insertions as in GoLand (dump probes 1, 2, 3, 6, 9, 10, 19, 39, C5; `return err` from README §4)
- A type picked where a value goes becomes a composite literal: `x := Cir` / `return Cir` / `use(Cir` / `&Cir` → `Circle{<caret>}` for
  struct, map, slice and array types, `strings.Build` → `strings.Builder{<caret>}` with the import; the kind is decided at insertion, so
  building the list reads no type declarations. Stays a bare name in type positions (`var c Circle`, `[]Circle`, parameters), at a statement
  start, in `make(` / `new(`, `f[...]`, before `{ ( . [`, for generic types and for other named types (`type MyInt int`, interfaces)
- Time layout completion inside the layout argument of `Time.Format`, `Time.AppendFormat`, `time.Parse`, `time.ParseInLocation`: GoLand's
  14 rows `YY YYYY MM DD hh mm ss` with their descriptions, written as the Go reference element (`YYYY` → `2006`, `hh` → `15`), then
  `year... month... day... hour... minute... second... zone...` whose pick reopens the list (the group's elements `January Jan 01 1`,
  `.000 … .999999999`, `MST Z07:00 -0700 …` are in it, among the whole layouts `2006-01-02`, … of the host: seen live, GoLand shows the group
  alone and only its 14 rows at first — left for later); the popup opens by itself while typing in such strings
- Rows rendered as GoLand renders them: fields, struct literal keys and methods show their declaring type after an arrow
  (`created → Base  time.Time`, `Area() → *Square  float64`, `Run(…) → *T  bool`; embedded fields have no owner); functions and methods
  carry the parameters in the tail and the results as the type (`Cut(s string, sep string)  (before string, after string, found bool)`,
  builtins too); variables and parameters show their type; members of a package not imported yet, by a bare name, through a dot import or
  after `pkg.` carry the import path in the tail (`Marshal(v any) encoding/json  ([]byte, error)`), members after an imported `pkg.` show the
  result instead of the package name; catalogue rows use the same layout (path in the tail, result as the type, no ` struct` / ` interface`);
  `byte` / `rune` are kept as written
- `return err` first on an empty statement line (or while typing `ret`) in a function that returns an error, with an error variable named
  `err` in scope: the whole statement, `return err` or `return nil, 0, err` with several results (zero values for the others); respects the
  "complete return values" setting

## [0.2.190] - 2026-10-06

### Added — completion from the catalogue of importable packages, closer to GoLand (dump probes 10, 10b, 12, 13, 24)
- Methods of catalogue types: the catalogue scanner records the exported methods of exported types (receiver, name, signature, pointer
  receiver) in the catalogue file (format version 3, the cache is rebuilt once); `GoSymbolIndex.methodsOf(importPath, type)`. Member
  completion on values of standard-library and module types keeps coming from the stubs of the library roots
- Indirect dependencies: bare-name completion also offers the packages of the rest of the build list (the module graph of the project
  model: pure MVS or `go list -m all`), below the standard library and the modules go.mod requires directly; at most 200 indirect modules,
  scanned once per version in the background like the others
- Smart completion (Ctrl+Shift+Space) after `x = `, `var i int = `, `ch <- `, `return ` or in an argument lists functions, variables and
  constants of the standard library and of direct dependencies whose value has the expected type (`strings.Count(…)`, `utf8.RuneLen(…)`),
  below the file's own items, the imported packages first; choosing one writes the call and the import. Basic types, `error` and named
  types (with or without `*`); at most 10 per package and 30 in all
- Implementations of an interface in smart completion: where an interface is expected (`var s Shape = `) the struct types that implement
  it come as literals, `Circle{}` when the value has the methods, `&Square{}` when only the pointer does, and `&sq` for a variable of such
  a type, as GoLand does
- `json.` / `json.Mar` without an import of `json` lists the members of every package of that name (`encoding/json/v2` next to
  `encoding/json`) with the import path in the row; choosing one imports that package
- Completion ranking by use (`GoHeuristicRanker`, `completionRanker` with `order="last"`): without the ML ranker, equal candidates are
  ordered by how often the name is used in the file and its package (identifier tokens of the lexer, cached on the file stamp, up to 50
  neighbour files) and by what was chosen lately in the project (an in-memory list of the last 100 picks); the expected type and the scope
  still decide first. The ML ranker, when built in, wins

## [0.2.189] - 2026-10-06

### Added — Go 1.27 in the semantic layer and the language-version inspection
- Generic methods (`func (l List[E]) Apply[F any](f func(E) F) List[F]`): inferred calls `l.Apply(func(int) string {...})` are typed
  `List[string]`; explicit `l.Apply[string]`, method values and method expressions `List[int].Apply[string]` keep the method's type
  parameters; `cannot use generic function l.Apply without instantiation`, `got 2 type arguments but want 1`, `in call to l.Two, cannot infer B`
- Function type inference in every assignment context (typed var, `=`, slice / map / struct-field elements, `ch <-`, argument, `return`)
  gives no false diagnostics; the variable keeps its declared type
- Promoted fields as keys of struct literals (`Foo{Baz: 1}` with `Baz` in the embedded `Bar`): resolve and the checker accept them at any
  depth; `invalid implicit pointer indirection to reach Baz`, `cannot specify promoted field Baz and enclosing embedded field Mid`,
  `cannot specify embedded field Bar and enclosed promoted field Baz`, `duplicate field name Baz in struct literal`; dotted keys stay
  `invalid field name Bar.Baz in struct literal`. Every wording checked with `go build -gcflags=-e` on Go 1.27.1
- `GoLanguageVersion` (go-psi-ide): `generic method requires go1.27 or later` on the first type parameter of a method, and
  `use of promoted field Bar.Baz in struct literal of type Foo requires go1.27 or later` on the key, when the file's Go version is older
- Fixtures: `testData/check/go127.go` (valid Go 1.27, 0 diagnostics), `go127errors.go`, `testData/types/go127`, `testData/resolve/promotedkeys`;
  the parser accepted all three forms since the Go 1.27.1 re-baseline (`testData/parser/cases/Go127.go`)

### Fixed
- A self-embedding interface (`type I interface{ I }`, also generic) no longer overflows the stack in the daemon (`GoInterfaceType.hasTypeTerms`
  guarded like `isComparableConstraint`; seen live on the probe files): the checker reports `invalid recursive type: I refers to itself` instead

## [0.2.188] - 2026-10-06

### Added — semantic checker: generics (go/types testdata 1753 of 1804 sites, allowlist 143 → 60 sites)
- `cannot use generic function f without instantiation` (var / `:=` / `_ =`, non-function targets, operands of `==`, expression statements)
  and `cannot use generic type List[T any] without instantiation` (type positions, `new`, method expressions, qualified types, composite literals)
- `invalid operation: myInt[int, string] (myInt is not a generic type)`; `got 3 type arguments but want 2` (at the first extra argument)
- `cannot use a type parameter as RHS in type declaration`; `term cannot be a type parameter` / `type in term ~T cannot be a type parameter`
- `cannot use type comparable outside a type constraint: interface is (or embeds) comparable` / `... interface contains type constraints`
  (var, param, result, field, pointer, map, chan, slice, array, `new`); `invalid map key type T (missing comparable constraint)` / `invalid map key type []int`
- `invalid recursive type: X refers to itself` / `invalid recursive type A` with the `A refers to B` lines, also through embedded interface
  elements and generic instantiations (`SelfGen[A] refers to itself`); no foreign AST is loaded
- Partial instantiation `f[A]`: `A (type int) does not satisfy Stringer (missing method String)`, prefixed `in call to f[int], ` inside a
  call; core-type and type-set failures
- `in call to f, cannot infer T` with unknown argument types, only when T cannot be bound by anything else (untyped nil does not take part)
- Assignability and conversions with type parameters as in go/types: untyped constants against every specific type (`cannot use 1 (untyped
  int constant) as T value in ...`), nil to a type parameter, named ↔ type parameter, channel direction, float ↔ complex conversions,
  `cannot assign to x[0] (neither addressable nor a map index expression)` for string elements of a type parameter
- `E redeclared in this block` for duplicate receiver type parameters
- Not reported on purpose (false-positive risk or parser-level): cycles through selectors and array lengths, `interface method must have no
  type parameters` (parser), cascades after an invalid instantiation, `cannot infer` when the names do not resolve — `docs/SEMANTIC.md` "Generics gaps"

## [0.2.187] - 2026-10-06

### Added — semantic checker: builtins, constants, comparisons, declarations (40 diagnostics, go/types testdata 1712 of 1804 sites)
- `clear` / `close` / `copy` / `delete` / `append` / `min` / `max` / `new` over type sets and odd arguments with the go/types texts:
  `invalid argument: cannot clear x (variable of type T constrained by any): argument must be (or constrained by) map or slice`,
  `invalid operation: cannot close non-channel ch (...)` / `cannot close receive-only channel ch (...)`, `invalid copy: argument must be a
  slice; have "foo" (untyped string constant)` / `mismatched slice element types int and string in x (...)` / `arguments b (...) and y (...)
  have different element types myByte and byte`, `invalid argument: m (...) is not a map` / `maps of m (...) must have identical key types`,
  `invalid append: argument must be a slice; have 1st function result (value of type int)`, `invalid argument: mismatched types untyped int
  (previous argument) and untyped string (type of "x")`, `use of untyped nil in argument to new`, `use of package unsafe not in selector`
- Constants: `invalid constant type []int`; array lengths (`invalid array length -1 (untyped int constant)`, `invalid array length n`,
  `invalid array length 1 << 64 (untyped int constant 18446744073709551616)`, `array length 1.5 (untyped float constant) must be integer`,
  `array length f() (value of type int) must be constant`); `constant overflow` beyond 512 bits, `constant bitwise complement overflow`;
  the default type of an untyped constant (`cannot use 1 << 100 (...) as int value in variable declaration (overflows)`, the same `in
  assignment` for `:=` and `in assignment to _ identifier` for `_ =`); `constant 256 overflows byte` for an implicitly repeated typed
  constant; `invalid operation: operator % not defined on 1 (untyped float constant)`
- Comparisons: `invalid operation: i == 0 (mismatched types I and untyped int)`, `x == y (incomparable types in type set)` / `(empty type set)`
- Declarations: `result parameter a not in scope at return`; `x.m undefined (type *T is pointer to type parameter, not type parameter)`;
  `math.Pi (untyped float constant 3.14159) is not a type`; `a redeclared` for struct fields and embedded fields; `method T0.m1 already
  declared` through an alias receiver; `invalid receiver type A10` (alias of an unnamed type); `func main must have no arguments and no
  return values`, `func main must have no type parameters`, `func init must have a body`; ambiguous selectors at any embedding depth
- Left out on purpose: array lengths through `unsafe` (sizes assume amd64), a local `iota`, shifts inside conversions, `missing function
  body` for non-`init` functions (assembly-backed functions in GOROOT) — `docs/SEMANTIC.md`

### Changed
- Float constants in messages follow go/constant `%.6g` (`3.14159`, not `3.14159e+00`); `append` slice errors say `invalid append:`
- `GoLookup`: "reached by several paths" propagates to nested embedded types, fields of `type S7 S6` differ by embedding path

## [0.2.186] - 2026-10-06

### Added — package-level build errors (go-psi-ide `ide.inspections.project`, group Go, ERROR)
- `GoMissingMainFunction`: `function main is undeclared in the main package` on the package clause of every non-test file of a `package
  main` whose buildable files (GOOS / GOARCH / tags of the toolchain) have no `func main`; from stubs, cached per directory
- `GoMultiplePackages`: go/build's `found packages a (a.go) and b (b.go) in <dir>` on each file whose package clause differs from the
  first buildable file's (`x_test` in a `_test.go` counts as `x`; files excluded by build constraints and `package documentation` do not count)
- `GoInitializationCycle`: `initialization cycle for x; x refers to f; f refers to x` for package-level initialization cycles that go
  through declarations of other files of the package (single-file cycles stay the checker's `init-cycle`); other files' bodies are read
  only when the walk reaches them; off above 100 files, gives up after 2000 declarations
- `GoLanguageVersion`: features newer than the file's Go version (`//go:build go1.N`, else the module's `go` directive; unknown → silent)
  with the go/types + cmd/compile texts `type parameter requires go1.18 or later`, `clear requires go1.21 or later`, `built-in min|max
  requires go1.21 or later`, `cannot range over n (variable of type int): requires go1.22 or later`, `cannot range over seq (...):
  requires go1.23 or later`, each followed by `(-lang was set to go1.17; check go.mod)` or `(file declares //go:build go1.21)`
- `tools/ui-robot/goland/probe/compile-errors/`: one probe Go file per diagnostic of 0.2.186–0.2.189 (`// want: <message>` above the
  line; `*.skipped.go` for cases left out, with the reason), checked against `go build` 1.27.1, gopls and the sandbox; `README.md` lists them

### Fixed
- A file excluded by the project's build context (`x_windows.go` on linux) no longer reports `Name redeclared in this block` against the
  files of another platform: its package scope is the package built for a context under which the file builds (`project.api.GoFileBuildContext`);
  `ignore`, `cgo`, `test`, `unix` are never satisfied as custom tags, so `//go:build ignore` programs keep the previous scope

## [0.2.185] - 2026-10-05

### Added — Go color palettes (ported from the C# palettes of idea-dotnet-support 0.1.97)
- GoLand, VS Code (Dark / Light Modern with the semantic tokens of the Go extension), Nord, Dracula, One Dark / One Light, Solarized
  and GitHub colors for Go and go.mod on top of the color scheme you use: the background, the selection and every other language stay
  as they are, and the dark or light variant follows the background of the scheme (also when the scheme or the theme is switched)
- Choose it in Settings | Go | Editor ("Go color palette") or in Go → Go Color Palette…, which previews each palette in the open editors
  while you move through the list (Esc puts the previous one back); "IDE default" returns the scheme's own Go colors, your own changes included
- The first Go file opened with a scheme of the IDE offers the palettes once ("Don't Show Again" silences it); a scheme with Go colors of
  its own (yours, or one written for GoLand) is left alone

## [0.2.184] - 2026-10-05

### Added — ML completion ranking, step 2: the ranker in the IDE, behind a build flag (docs/ML.md ML-1)
- `./gradlew.bat buildPlugin -PmlEnabled=true -Pml.models=<dir>` (or `MLENABLED=true` in the environment; the directory holds the trained
  `lm.cml` and `rank.cml`, default `../ml-data/go/models`) builds the plugin with Smart Completion: `META-INF/go-ml.xml` registers
  `io.github.golangsupport.ml.GoMlCompletionRanker` on the `completionRanker` extension point and the page Settings | Go | Smart
  Completion; the models go into the plugin under `ml/go/`. A build without the flag has none of it (plugin.xml includes go-ml.xml
  with an `xi:fallback`), and `GoSettingsTree.PAGES` lists the page only when the models are bundled.
- `GoMlCompletionRanker`: the text before the caret feeds the per-file cache of the n-gram language model, every candidate gets the
  13 common and 17 Go features (the code of the dataset export, `GoMlFeatureParityTest`) and the linear ranker scores them. The
  models (`GoMlModels`) load once, in the background, on the first completion; until then and whenever the switch is off, the
  deterministic order applies. The page has the switch and a directory with other `lm.cml` / `rank.cml` for trying a new training.
- `GoCompletionRanker.marker`: a ranker may name a grey tail text for the rows it scored; the ML ranker shows `ML` (switch on the page,
  on by default) so that a tester sees which order is the model's.
- `GoCompletionWeigher`: a ranker score now decides before the expected-type match (the ranker is trained on that match and on the
  scope level), scored candidates above unscored ones; without a ranker nothing changes.
- `GoMlCompletionRankerTest`: with `-Dml.models=<dir>` (`-Pml.models` of `:go-psi-ide:test`) completes over the real models and
  checks that every Go candidate is scored and the expected parameter stays on top; without the models it checks the abstention.
### Changed — two plugin files per version
- The ML build is a separate file with the same version: `buildPlugin -PmlEnabled=true` writes `idea-golang-support-<version>-ml.zip`
  (archive classifier `ml`) next to the plain `idea-golang-support-<version>.zip`; both are kept in `build/distributions/`.
  `build.ps1` (JBR of the target IDE, submodule, `--offline`): `-NoTests`, `-Run`, `-Ml [-MlModels <dir>]`, `-IdePath`.

### Changed — the ML engine is a plain copy, not a subtree
- `ml/` (the git subtree of idea-ml-completion with its training CLI, corpus tools and docs) is gone; `ml-core/` is a copy of that
  repository's pure-Kotlin `ml-core` module alone (`tools/ml/sync-ml-core.sh <checkout>` refreshes it). Nothing else of the engine
  is needed at build time; models are trained with the CLI of that repository and handed to the build through `-Pml.models`.
- Engine subtree `ml/` synced (streaming corpus loader, model readers over a stream for bundled resources; `ml/CHANGELOG.md` e10–e11).

## [0.2.183] - 2026-10-05

### Added — GOROOT shared indexes (MIGRATION step 11, wave 5; docs/SHARED-INDEXES.md)
- With the Shared Indexes plugin of the IDE (optional dependency `intellij.indexing.shared.core`, go-shared-indexes.xml) the platform's
  on-disk locator asks `GoSharedIndexFinder` (`sharedIndexLocalFinder`) for chunks of the project's GOROOT before the first indexing, so
  `$GOROOT/src` is attached instead of indexed. Key: release from `$GOROOT/VERSION`, `pkg/tool/<goos>_<goarch>`, sha256 of VERSION
  (`go1.27.1-windows-amd64-<12 hex>`); chunks in `<IDE system>/go-plugin/shared-indexes/<key>/`. Go | Build Shared Index for GOROOT... runs
  the IDE's `dump-shared-index project` headless over a throwaway go.mod project (own config/system/log, the running IDE's plugins), moves
  the chunk there and notifies; journal category `index`. Settings | Go: "Shared indexes URL" (`<url>/index.json`, entries key / ideBuild /
  url / sha256) downloads a missing chunk in the background for the next open. Development GOROOTs get no key.
  The file name of a downloaded chunk is the last url segment reduced to name characters: the index is a remote document, so a
  backslash segment or a drive letter in it never reaches the file system.

### Fixed
- The one-time "lenses off" default of the syntax-update code vision was written off EDT (a SEVERE "dropPsiCaches must be called in EDT" in
  the headless go-inspect run); it now runs on EDT.

## [0.2.182] - 2026-10-05

### Added — function literal by the expected type in basic completion
- Wherever a function is expected (an argument, `return`, an assignment, a field of a literal), basic completion offers the literal first:
  `func(name string) error {}` with the caret in the body; `fu` matches it. After `go` / `defer` the item is `func() {}()`. Before, the literal
  was in smart completion only (Ctrl+Shift+Space), and `fu` listed catalogue names (`ast.Fun`, `expvar.Func`) instead (seen live).
- Ranking against the host's catalogue: items of the PSI completion now carry the same priority as the catalogue's importable names
  (2.0 / 1.0 for a name that begins with what is typed) plus the fit of the expected type (+1.0 identical, +0.5 assignable,
  `GoLookupPriority`), so a local `fuel` or a fitting literal sorts above `ast.Fun`; before, every catalogue name that began with the prefix
  came first (seen live).
- A signature without parameter names (`http.HandleFunc`'s `func(ResponseWriter, *Request)`) gets names after the types in the literal,
  `func(w http.ResponseWriter, r *http.Request) {}`, not `p0`, `p1` (seen live).

## [0.2.181] - 2026-10-05

### Added — `x == nil` of a value that can never be nil (MIGRATION 13A)
- Constant condition (`GoDfaConstantCondition`) also reports nil comparisons whose operand is never nil by its form: `&T{}`, `&v`,
  `new(T)`, `make(…)`, `[]T{}`, `map[K]V{}`, a function literal (`Condition '&T{} == nil' is always 'false'`). Operands typed by a
  type parameter are skipped; declared functions and method values stay with govet `nilfunc` (`GoRules`).

### Added — `tools/vet/compare.py`: the 13A gate "our findings ⊇ go vet"
- Runs `go vet -json ./...` (`--module`) or reads saved output (`--vet-json`) and compares it with the go-inspect SARIF (`--sarif`)
  by file:line through an analyzer → inspection table (`GoRules[govet:x]` for the rule engine). Prints vet-only, ours-only and counts
  per analyzer; exit 1 on vet-only findings of covered analyzers, `--allow` takes `path:line analyzer reason` exceptions.
  `asmdecl`, `cgocall`, `framepointer` are reported as not covered. Usage in docs/CI.md.

## [0.2.180] - 2026-10-05

### Changed — after a live pass over G6–G9 on the sandbox
- Analyze Data Flow to / from Here: the dialog is titled by the variable ("Analyze Dataflow to variable b"), not by the PSI class of the reference.
- Go Optimization: when `go build -gcflags=-m` fails for a package, the notification lists the compiler errors only (the decisions of the other
  packages are still shown); before, it dumped the first `-m` lines as the error text.
- `tools/ui-robot/desktop.ps1`: real mouse, keyboard and screenshots of the Windows desktop for live checks (dialogs, typing, screenshots);
  hovers must still go through the IDE's AWT robot (`hover.js`) — injected moves do not open tooltips in an RDP session.

## [0.2.179] - 2026-10-05

### Changed — iota switch fixes as seen live on GoLand
- "Missing 'case' statements for 'iota' consts in 'switch'" reports switches over bit flags (`1 << iota`) too; its fixes are GoLand's
  Create missing iota clauses (one `case X:` per missing constant before the closing brace) and Create 'default' clause, which now inserts
  `default:` with `panic("unhandled default case")`.

## [0.2.178] - 2026-10-05

### Added — ML completion ranking, step 1: the shared engine and the offline dataset export (docs/ML.md ML-1)
- The shared ML completion engine (https://github.com/dvislobokov/idea-ml-completion) is a git subtree under `ml/`; only its pure-Kotlin
  `ml-core` (lexer, vocabulary, n-gram LM, linear ranker, feature extraction, model and shard formats) is part of the build, as `:ml-core`
  of `go-psi-ide`. Contract for the adapter: `ml/docs/ADAPTER.md`.
- Package `io.github.golangsupport.ml` (internal): `GoMlLanguage` and `GoMlFeatures` — the Go block of ranker features computed from what
  the `completionRanker` extension point receives (candidate kind, scope level, expected-type match, declaration in the file and its
  distance, the plugin's own deterministic order as `rule_rank_log`); the same code will serve the IDE ranker and the training export.
- `./gradlew.bat :go-psi-ide:mlDataset --offline --no-configuration-cache -Pml.repos=<list> -Pml.lm=<lm.cml> [-Pml.data -Pml.out -Pml.perFile
  -Pml.maxFiles]` runs the real completion headlessly over Go repositories (`GoMlDatasetExport`, copies of the sources in a temporary content
  root) and writes one example shard per repository for `ml-train`; ~30 ms per position. `GoMlFeatureParityTest` checks that the export and
  the extension-point path compute identical features.

## [0.2.178] - 2026-10-05

### Changed — GoLand parity: iota switch, Printf, shadowing level, comment spacing on reformat
- The exhaustive-switch inspection is now GoLand's "Missing 'case' statements for 'iota' consts in 'switch'" (`GoSwitchMissingCasesForIotaConsts`,
  Go | Probable bugs, warning; was `GoExhaustiveSwitch`, weak warning): an expression `switch` without `default` that leaves out constants of its type
  declared in a const block using `iota` (every constant of the block counts, even a spec without `iota`). Enums without `iota` and type switches over
  interfaces are no longer reported (GoLand does not; Fill switch still covers them). Fixes: Create missing iota clauses (one `case` per missing constant)
  and Create 'default' clause.
- The Printf inspection is GoLand's `GoPrintFunctions` ("Incorrect usage of 'fmt.Printf' and 'fmt.Println' functions", Go | Probable bugs, weak
  warning; was `GoPrintf`, warning). A verb without an argument reads `No argument for verb: argument index = 2, arguments count = 1 (%s)`; the other
  messages stay as vet's. Profiles that configured the old short names start from the defaults again.
- "Shadowing variable" is at GoLand's TEXT ATTRIBUTES level: the name keeps its colour and the tooltip, without a weak-warning underline.
- Code Style | Go | Other "Add a leading space to comments" also applies on Reformat Code with the Built-in formatter: `//text` becomes `// text`,
  with the inspection's exclusions (directives, `//line`, `//export`, `//extern`, `//nolint`, regions, `//#`, `//+build`, `////`, bare `//`) and
  generated files untouched.
- Add key to tags keeps its chooser of tag keys (GoLand writes an empty-key entry into every field and lets the key be typed in a template; seen live).

## [0.2.177] - 2026-10-05

### Fixed
- Go fix "CutPrefix / CutSuffix": `if strings.HasPrefix(name, "go") { name = strings.TrimPrefix(name, "go") }` is reported and rewritten to
  `if after, ok := strings.CutPrefix(name, "go"); ok { name = after }` (seen on GoLand); other writes of the subject inside the `if` still keep it quiet.

## [0.2.176] - 2026-10-05

### Changed — GoLand parity G10: Alt+Enter on //go:generate, struct tags, Sprintf; semantic colours
- Alt+Enter on a `//go:generate` line offers GoLand's three items in its order: Go Generate File, Go generate '<import path of the package>',
  Go generate '<command of the directive>'. The gutter ▶ is a plain line marker with GoLand's tooltip "Run go generate on comment" (a click runs
  the directive); its actions no longer duplicate the intentions in Alt+Enter.
- Struct tags: new intention Add key to tags (popup of the name keys some field lacks; writes `key:"name"` into every such field in the struct's
  style for that key). Add key / Change field name style / Update key value in tags are listed first, in GoLand's order, and are offered on a
  matching tag (`json:"value"`) as well as on a differing one.
- Add format string argument is offered inside `fmt.Sprintf("%d %s", n)`, also on `%s`: the expression becomes the argument of the first verb
  that has none.
- Shadowing variable: message as GoLand's, `Declaration of 'x' shadows declaration at style.go` (file name only; `builtin.go` for a predeclared
  name), and predeclared names are reported too (`new := 2`).

### Fixed
- Semantic colours: every word of a doc comment that names a package-level declaration, and both parts of a resolving qualified name
  (`time.Duration`, `os.PathError`), get GO_COMMENT_REFERENCE, not only the first word and `[Name]` links. The uses of a shadowing variable are
  GO_SHADOWING_VARIABLE like its declaration; `case n := <-ch` of a `select` is GO_LOCAL_VARIABLE, not GO_SCOPE_VARIABLE.

## [0.2.175] - 2026-10-05

### Changed — GoLand parity G10: inspection texts, ranges and levels as captured on GoLand 2026.2.3
- The G7 inspections use GoLand's own texts, ranges and levels (probe2 dumps): `Imports are not sorted` (every spec of an unsorted group),
  `Redundant alias`, `Comment should have the following format 'Name ...' (with an optional leading article)` (also `//no space` and `/* */` docs),
  `Use camel case instead of snake case`, `Exported variable 'B' should have its own declaration` (names after the first), `Name starts with the
  package name`, `Redundant 'else' in 'if'`, `Redundant type`, `Empty declaration 'var ()'`, `Empty slice declaration using a literal` (on `[]T`),
  `Error string should not be capitalized or end with punctuation mark` (whole literal, one finding), `defer should not call recover() directly` /
  `go should not call panic() directly` (whole statement), `Variable 'new' collides with the 'builtin' function`, `Unit-specific suffix 'Seconds'`
  (vars and consts only), `Unused type parameter 'T any'`, `Exported function F should have a comment or be unexported`. Redundant parentheses are
  no longer greyed out.
- GoReceiverNames compares receiver names across all files of the package (stubs): `Receiver names are different` on every named receiver of the
  type when they differ, `Receiver has a generic name` on `this` / `self` / `me`.
- GoMixedReceiverTypes: `Struct T has methods on both value and pointer receivers. Such usage is not recommended by the Go Documentation.` on the
  name of every method of the type.
- GoStructInitializationWithoutFieldNames: types of the file's own package and anonymous structs are reported at INFORMATION level; the range is
  the whole `T{…}`.
- GoCommentLeadingSpace works only with the new Code Style | Go | Other option "Add a leading space to comments" (off by default).
- SYNTAX_UPDATE severity is 20 as in GoLand (was 150), with a refresh icon for the inspections widget; the "Update syntax" and "What's New"
  lenses are off by default (GoLand has no in-file lens: its count lives in the inspections widget).

### Fixed
- GoIrregularIota follows GoLand's semantics: a spec repeating an earlier `iota` list after only specs without a list (`a = iota; b; c = iota`)
  is reported as `Irregular usage of 'iota'`; the false reports on `const x = iota` and on adjacent repeats are gone.
- GoAssignmentToReceiver no longer reports field writes (`c.name = v`); `c = …` on a value receiver reads `Assignment to the method receiver
  doesn't propagate to other calls`.

## [0.2.174] - 2026-10-05

### Fixed — review of the G5–G9 parity batches
- Go fix "slices.Backward": when the loop body writes an element of the slice, takes its address or passes the slice on, element reads stay `s[i]` instead of
  becoming a value that could be stale.
- "Redundant 'else' in 'if'": the quick fix is no longer offered when outdenting the else block would redeclare or capture a variable (`v, err := …` next to an outer `err`).
- "Struct initialization without field names": no "Add field names" fix for structs with a blank `_` field.
- "Type assertion on errors": the "Replace with 'errors.As'" fix picks a free variable name (`e2`, …) instead of redeclaring or shadowing an existing one.
- Go fix "CutPrefix / CutSuffix": `if r := strings.TrimPrefix(s, p); r != s` is only rewritten when `p` is a non-empty constant (an empty prefix changed the result).
- Go fix "strings.Builder": no internal error when the first `+=` of a variable could not be matched.
- Go fix "slices.Sort": no longer suggested for float slices (`slices.Sort` orders NaN differently from `sort.Slice` with `<`).
- Go fix inspections of the second group honour a file's `//go:build go1.N` line for the Go version, like the others.
- Unused declaration inspections: usage search results are cached between highlighting passes (per file for local names, per package and project-wide
  dependencies for exported ones) instead of searching the project again on every pass.
- "Update syntax" lens: counts the daemon's findings once highlighting has finished instead of re-running all Go fix inspections, and caches the enabled tool list.
- Introduce Type: "replace all" no longer rewrites the type inside type assertions `x.(T)`, type-switch `case T:` clauses or method signatures.
- Remove method from interface and all its implementations: refused with a hint naming the first call through the interface; implementing methods that may
  satisfy another interface (another project interface, or `String`, `Error`, `MarshalJSON`, `ServeHTTP` and the like) are no longer ticked by default.
- Introduce Field: the new field goes after the last field's trailing comment; refused with the location of the first positional literal (`S{1, 2}`) of the struct.
- Introduce Parameter: cancelling the search for calls from other packages no longer throws.
- Generate Test / Tests for Package: generated tests compile for slice, map, func and struct results (`reflect.DeepEqual`, with the import), variadic parameters
  (`[]T` field passed with `...`) and unnamed or `_` parameters (`arg1`, `arg2`, …); Generate Test adds the imports to an existing `_test.go`.
- go.mod "Unresolved path in 'ignore' directive" caches the module walk until files or directories change.
- Vulnerability check: a running govulncheck is stopped when the project closes and writes nothing afterwards.
- Dump Goroutines: Cancel stops collecting stacks through delve; goroutine ids beyond 2^31 are read.
- Saving go.mod offline no longer shows an error after every save: a failed automatic `go mod download` is not repeated for the same requirements and only the
  first failure is notified until a download succeeds.
- Goimports File passes the file as `./name`, and go.mod paths never reach `go list` / `go get` as flags.
- Refactor | Update Syntax… is shown only in projects with Go files.

## [0.2.173] - 2026-10-05

### Added — Analyze and Code Cleanup for Go (GoLand parity G9)
- Analyze | Data Flow to Here / from Here: where a variable's or parameter's value comes from and where it goes, one level per node, across assignments, call
  arguments, parameters and returns (`lang.sliceProvider` on the reaching-definitions analysis).
- Analyze | Locate Duplicates for Go (IDEs with the duplicates module): functions and blocks that differ only in identifiers and literals, with the dialog's
  anonymization options; statements cost 2, expressions 1.
- Code | Code Cleanup applies in one pass the fixes of the formatting-like inspections (redundant parentheses / comma / semicolon / type / import alias /
  conversion / else, unsorted imports) and of the Go fix group except `omitzero`; "Empty slice literal" is left out too, as `nil` changes JSON output.

## [0.2.172] - 2026-10-05

### Added — Go Optimization tool window (GoLand parity G9)
- Go | Go Optimization Decisions runs `go build -gcflags=-m=2` (plus `-d=ssa/check_bce/debug=1` when Bounds Checks is on) over every module in the background
  and shows the compiler's decisions in the Go Optimization tool window: file → Inlining / Escape analysis / Bounds checks → `line:col text`, with filters per
  kind, navigation and, while Show in Editor is on, gutter marks with the line's decisions in open editors. The gopls toggle in Go | gopls stays.

## [0.2.171] - 2026-10-05

### Added — Sync, Attach and Detach Go Module; toolbar entries (GoLand parity G9)
- Sync Go Module (Go | Modules and the project-view context menu): `go mod download`, then the module graph, the packages and the highlighting are read again.
- Attach Go Module… / Detach Go Module…: a Go module outside the project directory joins the project as a content root and leaves it again (modules only;
  GOPATH mode is not supported, as before).
- Go Settings… and Actions on Save… in the settings gear of the main toolbar for projects with go.mod, where GoLand keeps them.

## [0.2.170] - 2026-10-05

### Added — Share / Run in Playground (GoLand parity G9)
- Share in Playground / Run in Playground (Go Tools, Run also in the editor menu): the selection or the file goes to play.golang.org, the `go.dev/play` link is
  copied and shown with Open (Run also opens the browser), after a confirmation with "Don't ask again" (Settings | Tools | Go | Editor and Completion "Ask before
  sharing in Go Playground", on). Blank code and code over 64 KB are refused before sending.

## [0.2.169] - 2026-10-05

### Added — Tools | Go Tools (GoLand parity G9)
- The Go Tools submenu (Tools, the Go menu and the project-view context menu): Go Fmt Project (`go fmt ./...` in every module), Goimports File (`goimports -w`,
  Install offered when missing), Go Vet File (vet of the file's package, Build window) and Generate File.

## [0.2.168] - 2026-10-05

### Added — paste JSON as a Go type (GoLand parity G8)
- JSON pasted into a Go file (Settings | Tools | Go | Editor and Completion "When JSON is pasted": Show options by default / Convert JSON to a Go type / Insert
  JSON as-is): between declarations a `type Generated struct` with nested types, after `type Name` the struct type, inside a struct its fields (nested objects as
  anonymous structs); inside functions, strings and expressions the text stays as pasted. The options dialog has "Don't ask again".

## [0.2.167] - 2026-10-05

### Added — linked renames (GoLand parity G8)
- Settings | Tools | Go | Editor and Completion, each with Show options / Rename / Do not rename (Show options by default): renaming a file renames its test or
  production file (`foo.go` ↔ `foo_test.go`), renaming a struct field renames its struct tags in the style of each key, renaming a directory renames its package
  and a package its directory (checkbox in the Rename dialog; the last choice is kept for in-place renames).

## [0.2.166] - 2026-10-05

### Added — printf-like functions in Settings (GoLand parity G8)
- Settings | Tools | Go | Linters: the lists of functions checked as printf-like and of functions excluded from the check, the same lists Alt+Enter "Mark as
  string formatting function" / "Exclude …" edit.

## [0.2.165] - 2026-10-05

### Added — Code Style | Go: Imports and Wrapping (GoLand parity G8)
- Imports tab: sorting goimports / gofmt / None (goimports by default), group standard library (on), group project packages with local prefixes (on; typed prefixes
  are also passed to goimports as `-local`), move all imports to a single declaration (off; `import "C"` stays apart), remove redundant import aliases (off);
  applied by Optimize Imports.
- Wrapping and Braces: chop down call arguments / composite literal elements / function parameters if long (all off): one item per line with a trailing comma past
  the right margin; Reformat Code with the Built-in formatter only — gofmt and goimports never move line breaks. One "Go" page in Code Style (the duplicate
  page is gone).

## [0.2.164] - 2026-10-05

### Added — auto import options (GoLand parity G8)
- Settings | Editor | General | Auto Import, section Go: "Show import popup" (on; `Import "strings"? Alt+Enter` over an unresolved package), "Add unambiguous
  imports on the fly" (on; the import is added when the candidate package is the only one and the caret has left the name), "Optimize imports on the fly" (off;
  unused imports go when they are the file's only problems and no lookup or template is open), "Exclude from import and completion" (import paths, `/...` for
  subpackages; honoured by bare-name completion, the import quick fixes and paste import resolution). Settings | Tools | Go | Imports links to the section.

## [0.2.163] - 2026-10-05

### Fixed
- The checks settings test accepts GoLand's `Vgo*` ids of the go.mod inspections.

## [0.2.162] - 2026-10-05

### Added — Dump Goroutines (GoLand parity G9)
- Run | Dump Goroutines (and a Dump button in the Go Monitor): every goroutine with its stack in a console tab with clickable frames and a summary by state,
  from a paused Go debug session (through its DAP connection) or from a running program of a Go configuration (delve attach, the program keeps running; on
  Linux / macOS optionally SIGQUIT after a confirmation, since the program exits after printing). New setting Settings | Tools | Go | Debugger "Dump goroutines
  via delve attach" (on and fixed on Windows). The delve dump has no goroutine states: DAP threads do not carry them.

## [0.2.161] - 2026-10-05

### Added — Run with Profiler (GoLand parity G9)
- Run | Run with Profiler: "Profile 'x' with 'CPU Profiler' / 'Memory Profiler' / 'Blocking Profiler' / 'Mutex Profiler'" for go test configurations, plus a
  Profile executor that takes the kind from the configuration's Profile field (CPU by default); the profile opens in an editor tab (pprof flame graph, in the
  browser without JCEF) as soon as the tests end. `go run` configurations are not profiled (the program has to write the profile itself), as in GoLand.

## [0.2.160] - 2026-10-05

### Added — Coverage through the IDE's coverage engine (GoLand parity G9)
- "Run 'x' with Coverage" for go test configurations through the platform's coverage module (`com.intellij.modules.coverage`, optional): the Coverage tool
  window with packages, files and percentages, covered / partially covered / uncovered bars in the editor, percentages in the Project view, Hide Coverage and
  Manage Coverage Reports (`go test -coverprofile -covermode=atomic`, the profile kept under the IDE's system/coverage). Files no test reached are not listed.
  The "Collect coverage" box keeps the plugin's own gutter and the Go Tests percentages for IDEs without the coverage module. `go run` is not covered
  (needs `GOCOVERDIR` + `go tool covdata`).

## [0.2.159] - 2026-10-05

### Added — vulnerabilities with govulncheck (GoLand parity G7)
- Settings | Tools | Go | Code Quality, group govulncheck: "Check for vulnerabilities with govulncheck" (off by default: it downloads the vulnerability database)
  runs `govulncheck -json ./...` in the background for every module at project open, 10 s after a go.mod / go.sum change and when the result is older than an
  hour; results are kept per module in the plugin data directory (`vulncheck/`, keyed by go.mod + go.sum); runs and failures go to Plugin Logs under `vulncheck`.
- "Vulnerable API usage (imports)" (`GoVulnerablePackageImport`, Go | Security, warning, on): `Package 'x' of module m@v is affected by GO-…: summary` on the import
  (standard library: `… (fixed in Go 1.x.y)`); fix "Upgrade m to v" (`go get m@v` in the background).
- "Vulnerable API usage (calls)" (`GoVulnerableCodeUsages`, Go | Security, warning, off by default as in GoLand): `Call to vulnerable function pkg.F (GO-…)` /
  `Call to pkg.G reaches vulnerable function pkg.F (GO-…)` on the callee of the call govulncheck traced.
- Go | Check Vulnerabilities: govulncheck over every module now (offers Install when missing); findings as navigable warnings in the Build window (calls in the
  code, vulnerable modules on their go.mod require line) and a balloon "N vulnerabilities reachable from the code, … more in imported packages, … more in required modules".

## [0.2.158] - 2026-10-05

### Added — go.mod layout inspections (GoLand parity G7)
- "Multiple 'require' directives can be merged in groups by dependency type" (`VgoRequireDirectivesMerge`, information): more require directives than `go mod tidy`
  lays out (one direct block and one `// indirect` block); fix "Merge 'require' directives".
- "Migration to Go workspace is possible" (`VgoMigrateFromReplacesToWorkspace`, warning): a `replace` to a local module directory with no go.work in or above the
  module; fix "Create go.work" writes go.work (`go` of the module, at least 1.18; `use .` + `use <dir>`) and removes those replaces.
- "Unresolved path 'x' in 'ignore' directive" (`VgoUnresolvedIgnorePath`, warning; Go 1.25 `ignore`): `./x` from the module root, a bare path at any depth
  (search capped at 20 000 entries); fix "Remove the path".

### Fixed
- go.mod intentions and fixes: an edit at the end of the file no longer leaves an extra line break (`GoModDirectiveIntention.replaceLines` shared by all).

## [0.2.156] - 2026-10-05

### Added — typing (GoLand parity G8)
- A `}` that closes a block reformats the block with the built-in formatter (platform Smart Keys "Reformat block on typing '}'", on by default; the plugin's
  formatting service leaves fragment formatting to the built-in formatter, so no extra setting).
- Enter right after a bare `//` on the line above a declaration writes the `// Name ` doc comment stub with the caret after the name (Smart Keys "Insert
  documentation comment stub", on; also needs the plugin's doc-comment names option). Settings | Tools | Go | Editor and Completion links to Smart Keys.

## [0.2.155] - 2026-10-05

### Added — Debugger | Data Views | Go (GoLand parity G8)
- A "Go" tab in Settings | Build, Execution, Deployment | Debugger | Data Views: "Show integers as" (Decimal by default / Hexadecimal / Binary / Decimal and
  hexadecimal), "Show pointer addresses" (on; off hides the addresses delve prints for unloaded pointers and interface data), "Enable String() view" (off; the
  value of `String()` for values of the top frame of the stopped goroutine, through delve's `call`, cached per stop; types without `String()` are not asked again).

## [0.2.154] - 2026-10-05

### Added — Go Modules settings (GoLand parity G8)
- Settings | Tools | Go | Go Modules, group "Go Commands": "Environment" (`NAME=value;…` — GOPROXY, GOPRIVATE and other variables for the plugin's go commands;
  `go env` keeps the machine's values), "Enable vendoring support" (Automatically by default / Always `-mod=vendor` / Never `-mod=mod`, for modules with
  `vendor/modules.txt`, passed through GOFLAGS; a `-mod` in the user's GOFLAGS wins), "Download Go module dependencies" with GoLand's four choices (enabled for
  all projects by default; the per-project exception is kept in the project): `go mod download` runs in the Build window after go.mod is saved with changed requirements.

## [0.2.153] - 2026-10-05

### Added — Optimize imports on save (GoLand parity G8)
- Settings | Tools | Go | Formatting "Optimize imports on save" (off by default, as in GoLand), also shown as "Optimize Go imports" in Settings | Tools | Actions on
  Save: the plugin's import optimizer runs on save independently of Reformat; when the platform's own Optimize imports on save covers Go, it takes precedence.

## [0.2.152] - 2026-10-05

### Added — go.mod dependency issues (GoLand parity G7)
- "Deprecated dependency" (`VgoDependencyDeprecated`, Go modules | Dependency issues (go list -m -u), warning): `Module 'm' is deprecated: <comment>` on the
  require, from the background `go list -m -u` check (now run with `-u`).
- "Retracted dependency version" (`VgoDependencyVersionRetracted`, warning): `Version v1.2.0 of 'm' is retracted: <rationale>` on the version, from a background
  `go list -m -e -retracted -json` at most once an hour; fix "Upgrade to vX.Y.Z" when a newer version is known.

## [0.2.151] - 2026-10-05

### Added — unused declarations (GoLand parity G7)
- Unused function / exported function / type / exported type / constant / global variable (`GoUnusedFunction`, `GoUnusedExportedFunction`, `GoUnusedType`,
  `GoUnusedExportedType`, `GoUnusedConst`, `GoUnusedGlobalVariable`; Go | Declaration redundancy, warning, greyed out): `Unused function 'helper'`,
  `Unused constant 'Debug'`, `Unused type 'node'`, `Unused global variable 'cache'`; fix "Safe delete". Exported names as GoLand reports them: functions,
  constants and variables of `internal` and application packages, types only in `package main`; library API and methods are never reported. On the GoLand
  probe files the plugin gives exactly GoLand's findings (constants at lines 23–25 and 29, functions at 161, 200, 211 of `analysis.go`, `Broken` in `broken.go`).

### Changed
- "Unused parameter" (`GoUnusedParameter`) matches GoLand: warning, greyed out, `Unused parameter 'c Circle'` on the whole parameter, exported functions of
  `internal` and application packages checked too (call sites searched project-wide). The old opt-in `GoUnusedExported` stays as it was.

## [0.2.150] - 2026-10-05

### Added — data flow inspections (GoLand parity G7)
- "Constant condition" (`GoDfaConstantCondition`, Go | Data flow analysis, warning): `Condition 'x != nil' is always 'true'` for nil comparisons known on every
  path (after `if x == nil { return }`, `&T{}`, `make`, `x = nil`), for comparisons of a local whose every reaching assignment stores the same literal
  (`n := 0 … if n == 0`) and for literal-only comparisons; named constants (`if debug`, `runtime.GOOS == "linux"`) and escaping locals never count.
  "Impossible nil check" (`GoImpossibleNilCheck`) is now off by default: the new inspection reports the same comparisons with GoLand's text.
- "Division by zero" (`GoDivisionByZero`, Go | Probable bugs, warning): `Division by zero` on `/`, `%`, `/=`, `%=` by a local the data flow proves zero, or by a
  literal zero with a non-constant float / complex dividend (constant integer cases remain the compiler's error).
- "Exported function with an unexported return type" (`GoExportedFuncWithUnexportedType`, Go | General, warning): `Exported function with the unexported return
  type 'store'` (methods of exported types too; not `main`, not tests).
- "Redundant type conversion" (`GoRedundantConversion`, Go | Declaration redundancy, weak warning): `T(x)` with `x` already of type `T`; fix "Remove redundant type conversion".
- Already covered and not duplicated: nil dereference (`GoNilDereference`), error may be not nil (`GoResultUsedBeforeErrorCheck`), resource leaks
  (`GoBodyNotClosed`, `GoRowsNotClosed`, `GoLockNotReleased`), shift / impossible assertion / nil func / bools (vet rules), unreachable code, missing return,
  loop closure (go < 1.22), self-assignment, defer in loop, lost cancel, unused call result.

## [0.2.149] - 2026-10-05

### Added — probable bugs and control flow inspections (GoLand parity G7)
- "Defer / go on a builtin" (`GoDeferGo`): `'recover()' is called directly by 'defer' and does not stop a panic`, `'panic()' is called directly by 'go'`;
  fix Wrap in a function literal.
- "Name collides with an import" (`GoImportUsedAsName`): `Variable 'strings' collides with imported package name`; fix Rename.
- "Builtin used as a name" (`GoReservedWordUsedAsName`): `Variable 'len' collides with the builtin function`; fix Rename.
- "Irregular use of iota" (`GoIrregularIota`): `'iota' in a single constant declaration is always 0` (fix Replace with 0),
  `Redundant repetition of the previous constant expression with 'iota'` (fix Remove the repeated expression).
- "Mixed receiver types" (`GoMixedReceiverTypes`, package-wide through stubs): `Methods of 'T' have both value and pointer receivers`;
  fixes Change receiver to pointer / to value.
- "Type assertion on errors" (`GoTypeAssertionOnErrors`): `Type assertion on errors fails on wrapped errors`; fix Replace with 'errors.As' (if-ok form, imports `errors`).
- Build constraints (`GoBuildConstraint`, GoLand `GoBuildTag`): `misplaced +build comment` outside the header, `possible malformed +build comment` for a header
  comment that mentions `+build` without being one.
- "Assignment to receiver" (`GoAssignmentToReceiver`, Control flow): `Assignment to method receiver 'c' does not propagate to callers`,
  `Assignment to a field of value receiver 'c' is lost when the method returns`; fix Change receiver to pointer.
- Already covered by the lint rules and not duplicated: `GoRedundantTrueInForCondition` (S1006), `GoStringsReplaceCount` (SA1018),
  `GoLeadingWhitespaceInDirectiveComment` (SA9009).

## [0.2.148] - 2026-10-05

### Added — declaration redundancy inspections (GoLand parity G7)
- "Empty declaration" (`GoEmptyDeclaration`): `Empty 'var' declaration` (also const / type / import); fix Delete empty declaration.
- "Empty slice literal" (`GoPreferNilSlice`): `Empty slice declared using a literal`; fix Replace with nil slice declaration.
- "Redundant comma" / "Redundant semicolon" (`GoRedundantComma`, `GoRedundantSemicolon`) with removal fixes.
- "Redundant import alias" (`GoRedundantImportAlias`): `Redundant alias 'fmt'`; fix Remove redundant alias.
- "Redundant type in composite literal" (`GoRedundantTypeDeclInCompositeLit`): `Redundant type declaration` for `[]T{T{}}`, `[]*T{&T{}}`, map keys and values; fix Remove redundant type.
- "Type can be omitted" (`GoVarAndConstTypeMayBeOmitted`): `Type can be omitted` on `var x T = T(v)`; fix Remove type.
- "Unused type parameter" (`GoUnusedTypeParameter`): `Unused type parameter 'T'`; fix Rename to '_'.
- "Redundant parentheses" (`GoRedundantParens`, group General): operands, conditions, returned and assigned values, arguments, named types in declarations
  (composite literals in control headers are kept); fix Remove redundant parentheses.

## [0.2.147] - 2026-10-05

### Added — code style inspections (GoLand parity G7; GoLand's ids, groups and levels, all enabled by default)
- "Comment without a leading space" (`GoCommentLeadingSpace`): `Line comment should have a space after '//'` (directives, `//nolint`, `//line`, `//export`,
  cgo `//#` and `//+build` exempt); fix Add a space after '//'.
- "Comment start" (`GoCommentStart`): `comment on exported function Foo should be of the form "Foo ..."` and `Comment should be meaningful or it should be removed`
  (as GoLand on `// NewOrder`); fixes Start comment with 'Name', Remove comment. The opt-in `GoDocComment` now reports only missing comments.
- "Error string format" (`GoErrorStringFormat`, ST1005, `errors.New` / `fmt.Errorf`): `Error string should not be capitalized`,
  `Error string should not end with punctuation or a newline`; fixes Lowercase the first letter, Remove the trailing punctuation.
- "Exported names in one declaration" (`GoExportedOwnDeclaration`): `Exported var A should have its own declaration`; fix Split into separate declarations.
- "Name starts with the package name" (`GoNameStartsWithPackageName`): `type name will be used as probe.ProbeThing by other packages, and that stutters;
  consider calling this Thing`; fix Rename to 'Thing'.
- "Receiver names" (`GoReceiverNames`): `this` / `self`, `_`, `Receiver name x should be consistent with previous receiver name t for T` (within a file);
  fixes Rename to 'c', Remove the receiver name.
- "Redundant else" (`GoRedundantElseInIf`): `'if' block ends with a 'return' statement, so drop this 'else' and outdent its block` (also break / continue /
  goto / panic); fix Remove redundant 'else'.
- "Struct literal without field names" (`GoStructInitializationWithoutFieldNames`): `Fields are assigned without explicit names` (same-package and anonymous
  structs too, as GoLand on the probe's test table); fix Add field names.
- "Lower-case type parameter" (`GoTypeParameterInLowerCase`, information): `Type parameter 't' is declared in lowercase`; fix Rename to 'T'.
- "Unit-specific duration suffix" (`GoUnitSpecificDurationSuffix`, ST1011): `var timeoutSeconds is of type time.Duration; don't use unit-specific suffix "Seconds"`; fix Rename.
- "Unsorted import" (`GoUnsortedImport`): `Import is not sorted`; fix Sort imports.
- "Snake case" (`GoSnakeCaseUsage`): `Don't use underscores in Go names; func parse_url should be parseUrl`; fix Rename to camelCase.
- Shared fixes `GoEditFix`, `GoRenameToFix` (rename with references through `RenameProcessor`) and `GoRenameVariableFix(text)`.

## [0.2.146] - 2026-10-05

### Added — Copy / Clone declaration (GoLand parity G6)
- F5 / Refactor | Copy on the name of a top-level function, method, type, variable or constant: "Copy Declaration…" copies it under a new name into the same or
  another file of the package (Clone: the same file); references to itself and the name in the doc comment follow the new name, imports are added.
  Files and directories are still copied by the platform. Refused for a taken name and for a constant with `iota` or without a value in its group.

## [0.2.145] - 2026-10-05

### Added — Invert Boolean (GoLand parity G6)
- Refactor | Invert Boolean… on a boolean variable, parameter, field or a function returning one `bool`: a dialog suggests the inverted name (`isEnabled` →
  `isDisabled`, `ok` → `notOk`, `has…` → `lacks…`, `notX` → `x`, else a `not` prefix) and every value it gets or gives is negated: initializer, assignments,
  keyed literals, call arguments, return values; reads become `!x`, `x == true` → `x == false`, `&&` / `||` by De Morgan. Non-boolean names get a hint.

## [0.2.144] - 2026-10-05

### Added — Introduce Parameter Object (GoLand parity G6)
- Refactor | Extract/Introduce | Introduce Parameter Object… on a function with two or more parameters: the chosen ones move into `type NameParams struct { … }`
  above it, the function takes `p NameParams`, the body uses `p.Field`, every call passes `NameParams{Field: arg, …}` (qualified from other packages). Dialog with
  the struct name and the parameters; receiver, `_` and variadic parameters are not offered. Refused for generic functions, interface methods, functions used as values.

## [0.2.143] - 2026-10-05

### Added — Introduce Field (GoLand parity G6)
- Refactor | Extract/Introduce | Introduce Field (Ctrl+Alt+F) in a method with a struct receiver: the selected expression becomes an unexported field of the struct
  (name unique among fields and methods, struct re-aligned), replaced by `r.name`; a popup chooses "Initialize in current method" (`r.name = expr` before the
  statement) or "Leave initialization to the caller".

## [0.2.142] - 2026-10-05

### Added — Introduce Parameter (GoLand parity G6)
- Refactor | Extract/Introduce | Introduce Parameter (Ctrl+Alt+P): the selected expression of a function body becomes a new parameter (before a variadic one;
  type from the semantics, name suggested, in-place rename, all occurrences on request); every call in the project passes the expression, interface methods
  change with their implementations (through Change Signature, under a progress). Refused when the expression uses the function's parameters, receiver or locals,
  or package names the callers from other packages cannot see.

## [0.2.141] - 2026-10-05

### Added — Generate: Method…, Tests for Package…, Copyright (GoLand parity G6)
- Alt+Insert | Method…: a method of the type at the caret from a dialog (name, pointer receiver — default as the type's other methods, parameters, results),
  body `panic("not implemented")`, placed after the type's last method.
- Alt+Insert | Tests for Package…: table-driven tests for every exported function and method of the package that has no `TestF` / `TestT_M` yet, each in the
  `_test.go` file of its source file (created when needed), `testing` imported; a pre-checked list chooses them.
- Generate | Copyright and Code | Update Copyright work in Go files when the Copyright plugin is present (`copyright.updater` for Go): the notice goes above
  `package` as `//` lines.

## [0.2.140] - 2026-10-05

### Added — Remove method from interface and all its implementations (GoLand parity G6)
- Alt+Enter on a method of a project interface: "Remove method from interface and all its implementations" removes the spec and, after a chooser listing the
  implementing types' methods, those methods with their doc comments; methods that are still called elsewhere are kept and named in a hint. Embedded, generated
  and out-of-project implementations are left alone. One undo.

## [0.2.139] - 2026-10-05

### Added — Introduce Type (GoLand parity G6)
- Refactor | Extract/Introduce | Introduce Type… on a type literal (`struct{…}`, `func(…)`, `map[K]V`, `[]T`, `chan`, the type of a composite literal) at the caret
  or selected: `type Name …` goes above the enclosing top-level declaration and its comment; identical type expressions of the file are replaced too (by default),
  the name — from the field, parameter or variable (`cfg` → `Cfg`), else by the kind (`Handler`, `Config`) — is edited in place. Not offered on a named type,
  on the type's own declaration, or on a type with a type parameter or a local type.

## [0.2.138] - 2026-10-05

### Added — Override Methods (GoLand parity G6)
- Code | Override Methods (Ctrl+O) on a struct with embedded fields: a chooser, grouped by embedded field, of the methods promoted from embedded structs and
  interfaces (any package, pointer or not) that the struct does not declare; each becomes a wrapper `return s.Base.Close()` with the type's receiver style,
  variadic arguments passed on, unnamed parameters named, imports added. Unavailable when nothing is promoted.

## [0.2.137] - 2026-10-05

### Added — Update Syntax and the Go fix lenses (GoLand parity G5)
- Refactor | Update Syntax... (also in the Go menu): runs the enabled inspections of the Go fix group over a chosen scope (file, directory, module, project,
  custom scope); the findings open in Inspection Results, where their fixes apply to one finding, a file or all at once.
- Code vision at the top of a Go file with Go fix findings: "Update syntax (N places)" applies Update Syntax to the file, "What's New" opens the Go fix
  section of the help page. Both can be switched off in Settings | Editor | Inlay Hints | Code vision (Batch syntax update, What's New).

## [0.2.136] - 2026-10-05

### Added — typed Go fix inspections (GoLand parity G5)
- "'strings.Split' or 'strings.Fields' loop can use a 'Seq' variant" (`GoFixStringsSeq`, Go 1.24, `strings` and `bytes`): `Ranging over SplitSeq is more efficient`,
  also for `parts := strings.Split(…)` followed by `for range parts`.
- "String concatenation can use 'strings.Builder'" (`GoFixStringsBuilder`): `using string += string in a loop is inefficient` for `s := ""` / `var s string`
  appended in a loop; the fix adds the `strings` import.
- "Primitive atomic value can use typed wrapper" (`GoFixAtomicTypes`, Go 1.19): `Variable 'n' is accessed only through sync/atomic functions; it can be
  declared as atomic.Int64` for locals and unexported package variables of one file; the fix rewrites `atomic.XxxInt64(&n, …)` into `n.Xxx(…)`.
- "Pointer arithmetic can use 'unsafe.Add'" (`GoFixUnsafeFuncs`, Go 1.17): `pointer + integer can be simplified using unsafe.Add`;
  `slice conversion can be simplified using unsafe.Slice` for `(*[N]T)(unsafe.Pointer(p))[:n:n]`.

## [0.2.135] - 2026-10-05

### Added — Go fix inspections on calls (GoLand parity G5)
- "Address can be built with 'net.JoinHostPort'" (`GoFixHostPort`): `address format "%s:%d" does not work with IPv6` for a `Sprintf` or `host + ":" + port`
  that reaches `net.Dial`, `Listen` and the like directly or through a local used once; fix Replace with net.JoinHostPort (adds `strconv` when needed).
- "WaitGroup goroutine pattern can use 'WaitGroup.Go'" (`GoFixWaitGroup`, Go 1.25): `Goroutine creation can be simplified using WaitGroup.Go` for
  `wg.Add(1); go func() { defer wg.Done(); … }()`.
- "Test context cancellation can use 't.Context()'" (`GoFixTestingContext`, Go 1.24): `context.WithCancel can be modernized using t.Context`
  (`WithCancel` + `defer cancel()` → `ctx := t.Context()`) and `context.Background() can be replaced by t.Context() in a test` (not inside literals,
  `go`, `defer` or tests with `Cleanup`).
- "'reflect.TypeOf' can be replaced with 'reflect.TypeFor'" (`GoFixReflectTypeFor`, Go 1.22): `reflect.TypeOf call can be simplified using TypeFor`
  for `TypeOf((*T)(nil)).Elem()` and `TypeOf(T{})`.
- "'errors.As' can be replaced with 'errors.AsType'" (`GoFixErrorsAsType`, Go 1.26): `errors.As can be simplified using AsType[*T]` for
  `var e *T` right before `if errors.As(err, &e)`; the fix produces `if e, ok := errors.AsType[*T](err); ok {`.

## [0.2.134] - 2026-10-05

### Added — Go fix inspections on declarations (GoLand parity G5)
- "Pointer helper call can be replaced with 'new()'" (`GoFixNewExpr`, Go 1.26): `call of ptr(x) can be simplified to new(x)` for
  `func ptr[T any](x T) *T { return &x }`-style helpers (`int64Ptr(5)` → `new(int64(5))`), `function literal can be simplified to new(e)` for
  `func() *T { v := e; return &v }()`, `variable 'x' is used only for its address; it can be created with new(e)` for `x := e` + `&x`.
- "'omitempty' tag on a struct field can be changed" (`GoFixOmitZero`, Go 1.24): `Omitempty has no effect on nested struct fields` on struct and
  `time.Time` fields; fixes Replace omitempty with omitzero (behavior change) / Remove redundant omitempty tags.
- "Obsolete '+build' line" (`GoFixPlusBuild`, Go 1.17): `+build line is no longer needed` (fix Remove obsolete +build lines) with a `//go:build` line
  present, `+build line is obsolete; use //go:build` (fix Replace +build lines with //go:build) without one.
- "Embedded variable converted at every use" (`GoFixEmbedTyped`, Go 1.16; no GoLand counterpart): `Embedded variable 'x' is used only as []byte; it can be
  declared as []byte` (and the `string` case) for an unexported `//go:embed` variable whose every use is a conversion passed as an argument.

### Changed
- The build constraint inspection no longer reports a `// +build` line as deprecated and has no "Add //go:build line" fix: the Go fix inspection
  reports and converts the line, the build constraint one checks only its tags.

## [0.2.133] - 2026-10-05

### Added — Go fix inspections on the standard library (GoLand parity G5)
- "Loop can be replaced with 'slices.Contains' / 'slices.Index'" (`GoFixSlicesContains`, Go 1.21): `Loop can be simplified using slices.Contains` /
  `slices.Index` for return true / return false loops, the `return i` / `return -1` pair and `if v == x { …; break }`; adds the `slices` import.
- "'sort.Slice' can be replaced with 'slices.Sort'" (`GoFixSlicesSort`, Go 1.21): `sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })` →
  `slices.Sort(s)`, message `sort.Slice can be modernized using slices.Sort`; the `sort` import goes when nothing else uses it.
- "Backward loop can use 'slices.Backward'" (`GoFixSlicesBackward`, Go 1.23): `for i := len(s) - 1; i >= 0; i--` → `for _, v := range slices.Backward(s)`
  with `s[i]` reads becoming `v`; message `for loop can be modernized using slices.Backward`.
- "'strings.Index' and slicing can be replaced with 'strings.Cut'" (`GoFixStringsCut`, Go 1.18, also `bytes`): `i := strings.Index(s, sep); if i >= 0 { … }`
  → `before, after, ok := strings.Cut(s, sep); if ok { … }`; message `strings.Index can be simplified using strings.Cut`.
- "'HasPrefix' and 'TrimPrefix' can be replaced with 'strings.CutPrefix'" (`GoFixStringsCutPrefix`, Go 1.20, also Suffix and `bytes`):
  `if strings.HasPrefix(s, p) { … strings.TrimPrefix(s, p) … }` → `if after, ok := strings.CutPrefix(s, p); ok { … after … }`, also the
  `if r := strings.TrimPrefix(s, p); r != s` form; message `HasPrefix + TrimPrefix can be simplified to CutPrefix`.
- "Map loop can use a 'maps' function" (`GoFixMapsLoop`): `for k, v := range src { dst[k] = v }` → `maps.Copy(dst, src)` (Go 1.21), key / value
  appends → `slices.AppendSeq(…, maps.Keys(m))` or `keys := slices.Collect(maps.Keys(m))` after `var keys []K` (Go 1.23); messages
  `Replace m[k]=v loop with maps.Copy`, `Replace append loop with slices.Collect` / `slices.AppendSeq`.

## [0.2.132] - 2026-10-05

### Added — Go fix inspections on the language (GoLand parity G5)
- "'interface{}' can be replaced with 'any'" (`GoFixAny`, Go 1.18): `interface{} can be replaced by any`; fix Replace 'interface{}' with 'any';
  quiet where `any` is redeclared.
- "Conditional assignment can use 'min' / 'max'" (`GoFixMinMax`, Go 1.21): `if a < b { x = a } else { x = b }` → `x = min(a, b)`,
  `if n > limit { n = limit }` → `n = min(n, limit)`; messages `if/else statement can be modernized using min` / `if statement can be modernized using max`;
  integers and strings only (floats differ on NaN and -0).
- "For loop can be replaced with range over int" (`GoFixRangeInt`, Go 1.22): `for i := 0; i < n; i++` → `for i := range n` (or `for range n` when `i` is
  unused); message `for loop can be modernized using range over int` on the loop header, as in GoLand; only when neither `i` nor the limit changes
  in the body and the limit is an `int` constant, local, parameter or `len` of one.
- "Redundant range variable shadowing" (`GoFixForVar`, Go 1.22): removes `v := v` and `k, v := k, v` at the top of a range loop body;
  message `copying variable is unneeded`.

## [0.2.131] - 2026-10-05

### Added — Go fix group and the "Syntax update" level (GoLand parity G5)
- New inspection group Go | Go fix for the modernizers of the following versions: every inspection in it is skipped for files whose Go version is
  older than it needs — the version comes from the file's `//go:build go1.N` line, else from the `go` directive of the module's go.mod; without a go.mod
  everything is reported. Quick fixes have the family "Go fix" and show a preview.
- New inspection level "Syntax update" (`SYNTAX_UPDATE`, GoLand's name, so a profile exported from GoLand keeps its levels) between weak warning and
  information, coloured by Go | Syntax update (`GO_SYNTAX_UPDATE`); the Go fix inspections use it.

## [0.2.130] - 2026-10-05

### Added — go.mod intentions (GoLand parity G4)
- "Merge a group of directives" (consecutive one-line directives of one kind into a `( … )` block), "Merge all directives" (every directive of that kind in the
  file into one block), "Merge directive up" (a one-line directive into the block of the same kind right above); comments kept; go.mod and go.work, for
  `require`, `replace`, `exclude`, `retract`, `tool`, `ignore`, `godebug`, `use`.
- "Update dependencies…" on a `require` with a known newer version: a dialog with the direct dependencies that have one (all checked); Update runs
  `go get module@version` for the chosen ones in the background, then `go mod vendor` when the module vendors.

## [0.2.129] - 2026-10-05

### Added — Run go generate from Alt+Enter (GoLand parity G4)
- On a `//go:generate` line: "Run go generate on comment" (this directive alone, `go generate -run '^…$' file.go`), "Run go generate on file", "Run go generate
  on package" (`go generate .`); output in the Build tool window; available during indexing.

## [0.2.128] - 2026-10-05

### Added — navigation from Alt+Enter (GoLand parity G4)
- "Go to Implementations" on an interface type or an interface method, "Go to Interfaces" on a type that implements interfaces, "Go to Method Specifications" on a
  method that implements interface methods — listed low, offered only when there is a target; one target opens at once, several in a chooser.

### Fixed — review of the parity batch
- "Negate topmost expression" no longer throws on a binary expression without a right operand (code being typed).
- `.not` postfix: the operand goes through the template variable, a `$` in its text no longer breaks the template.
- Fill all fields… from completion: no exception when the struct changed before the chooser answered.
- The semantic annotator checks the feature gate only for the elements it colours.

## [0.2.127] - 2026-10-05

### Added — struct tag intentions (GoLand parity G4)
- "Change field name style in tags" (on a tag, the type name or `struct`): a popup of `full-name`, `full_name`, `FullName`, `fullName`, applied to every field with that
  key, options kept. "Update key value in tags" on a field whose tag name no longer matches the style the other fields use.

## [0.2.126] - 2026-10-05

### Added — Unchecked error: "Do not report this method/function anymore" (GoLand parity G4)
- The quick fix adds the callee (`path.Func` / `path.Type.Method`, as the built-in errcheck exclusions) to the inspection's exclude list, editable in the inspection
  options; the errcheck rule has the same fix.

## [0.2.125] - 2026-10-05

### Added — printf intentions (GoLand parity G4)
- "Add format string argument" asks for an expression and adds `%v` with its argument at the caret; "Exclude string formatting function" / "Mark as string
  formatting function" turn the printf check (and verb highlighting) off or on for a function, remembered for all projects (`GoPrintfFunctions`, vet-style names
  `fmt.Printf`, `(*log.Logger).Printf`, `example.com/x.report`). Mark is offered for functions ending in `...any`.

## [0.2.124] - 2026-10-05

### Added — argument layout and string intentions (GoLand parity G4)
- "Put arguments on separate lines" / "Put arguments on one line" for call arguments and "Put elements on separate lines" / "Put elements on one line" for composite
  literals (gofmt layout, trailing comma; lists with comments are left alone). "Join concatenated string literals" merges adjacent literals of a `+` chain
  (raw parts are escaped into an interpreted string).

## [0.2.123] - 2026-10-05

### Added — import intentions (GoLand parity G4)
- "Import for side-effects" (an unused import → `_ "pkg"`), "Add import alias" (the package name as alias, typing a new name renames the qualifiers),
  "Add dot import alias" / "Remove dot import alias" (qualifiers dropped / restored in the file; not offered when a name would change meaning).

## [0.2.122] - 2026-10-05

### Added — Create global variable / Create parameter from usage (GoLand parity G4)
- Alt+Enter on an undefined name inside a function: "Create global variable 'x'" (after the imports, a `func(…)` type for a call) and "Create parameter 'x'"
  (the calls of the function in the project get the zero value so they keep compiling). Package-level names keep the existing "Create variable".

## [0.2.121] - 2026-10-05

### Added — Export, Migrate function parameter to method receiver (GoLand parity G4)
- "Export" renames an unexported function, type, variable, constant, method or field to its exported name with all usages (the rename refactoring; no preview).
- "Migrate function parameter to method receiver" turns `func f(t *T, x int)` into `func (t *T) f(x int)` and rewrites the calls (`f(&v, 1)` → `v.f(1)`);
  unavailable when the type is from another package or `T.f` exists.

## [0.2.120] - 2026-10-05

### Added — signature and declaration intentions (GoLand parity G4)
- "Expand signature types" (`a, b int` → `a int, b int`) and "Reuse signature types" (back) on function, method and function literal signatures.
- "Merge declaration up" (into an existing group too), "Merge declaration up via comma" (`var a int` + `var b int` → `var a, b int`, `:=` too) and
  "Split declarations into two groups". GoLand's "Split all declarations" / "by comma" are the existing "Split into separate declarations".

## [0.2.119] - 2026-10-05

### Added — struct literal intentions (GoLand parity G4)
- "Remove keys from struct literal" turns `T{B: "a", A: 2}` into `T{2, "a", nil}` as GoLand does (field order, omitted fields as zero values); not for
  literals of another package's struct with unexported fields or with an elided type.
- "Move field assignment to struct initialization" folds the `s.F = v` statements right after `s := T{…}` into the literal.

## [0.2.118] - 2026-10-05

### Added — expression intentions (GoLand parity G4)
- "Flip binary operator" swaps the operands ("Flip '>=' to '<='", "Flip '+'", "Flip '-' (changes semantics)").
- "Negate expression", "Negate expression recursively", "Negate topmost expression" and "… recursively" rewrite a boolean expression as the equivalent negated
  one (De Morgan, flipped comparisons, "Negate '||' to '&&'"); `<` / `>` on floats are left alone (NaN). The topmost / recursive variants appear only when they
  differ from the plain one.
- "Specify type explicitly" adds the type to `var x = v` / `const c = v` (`var i int = 1`), importing the type's package when needed; `x := 1` keeps the existing
  "Convert to 'var' declaration". GoLand's "Specify dot type" is a Go templates feature, not done.

## [0.2.117] - 2026-10-05

### Added — parameter names by type and variable name hints (GoLand parity G3)
- In `func g(` (functions, methods, function literals) the types come as `name Type` items: `err error`, `base Base`, `string2 string` (a reserved or taken name
  gets the next digit), `t T` for a type parameter; packages stay in the list; after a typed prefix exported types of imported packages follow at the bottom
  (`ctx context.Context`).
- Variable name hints where a name is declared: `var <caret> Circle` → `circle`, `c`; `<caret> := c.Area()` → `area`; `for i, <caret> := range names` → `name`;
  range index `i` (`j`, `k` nested). Names come from the right-hand side (getter / constructor prefixes dropped, singular for elements), then from the type
  (idiomatic `ctx`, `err`, `t`, `w`, `r`, `buf`, `mu`, `wg`, the lowercased type name, its last word, its initial). Names in scope are skipped or numbered.

## [0.2.116] - 2026-10-05

### Added — live templates from GoLand (GoLand parity G3)
- `map` (in type positions: after a name, `]`, `*`, `chan`, `make(`, `new(`), `p`, `imports`, `consts`, `vars`, `types`, `iota`, `:` and the `xml` struct tag,
  with GoLand's keys, texts and variables. The 9 Go Template templates wait for Go templates support.

## [0.2.115] - 2026-10-05

### Changed — postfix templates: GoLand's keys and names (GoLand parity G3)
- New: `.!`, `.&` / `.p` / `.pointer`, `.*` / `.d` / `.dereference`, `.cap`, `.copy`, `.close`, `.delete`, `.complex`, `.real`, `.imag`, `.println`,
  `.aappend` / `.appendAssign`, `.remove`, `.as`, `.is`, `.parseInt`, `.parseFloat`. `.sort` picks `sort.Strings` / `sort.Ints` / `sort.Float64s` / `sort.Sort` /
  `slices.Sort` by type.
- Names inferred as GoLand does: `.var` → `area := c.Area()`, `user, err := loadUser()`; `.forr` → `for i, name := range names` (singular of the slice name).
- **Behaviour changes**: `.forr` is now GoLand's range loop (the reverse loop moved to `.forrev`); `.append` is now the expression `append(expr, …)` (the
  assignment form is `.aappend` / `.appendAssign`). `.print` (`fmt.Println`) and `.rr` stay ours.

## [0.2.114] - 2026-10-05

### Fixed — postfix templates in the completion list (GoLand parity G3)
- After `expr.` the applicable postfix keys follow the fields and methods (a weigher keeps them behind the members), filtered by the expression's type:
  `err.` offers `as`, `is`, `nil`, `nn`; a package or type qualifier (`fmt.`, `T.`) offers none (before, every key applied to an unknown type).
- Settings | Editor | General | Postfix Completion | Go has a description page: the platform logged an error without the resources.

## [0.2.113] - 2026-10-05

### Added — struct tag completion as in GoLand (GoLand parity G3)
- At a tag key the first item is **Add tag key to all fields…**: a popup of keys (`json`, `yaml`, `xml`, `toml`, `db`, …), the chosen key goes to every exported field
  that lacks it, named in the style the struct already uses. In `json:"…"` (and the other name keys) the field name is offered in GoLand's four styles:
  `full-name`, `full_name`, `FullName`, `fullName`; a style another field already uses comes first.

## [0.2.112] - 2026-10-05

### Added — top-level completion items `func (*T)` and Implement Interface (GoLand parity G3)
- At the top level of a file, next to `func`: **`func (*T)`** inserts a method stub for the nearest type above the caret with the receiver its methods already use;
  **`func` · Implement Interface...** puts the caret on that type and opens the plugin's Implement Interface chooser.

## [0.2.111] - 2026-10-05

### Changed — constant rows show their value (GoLand parity G3)
- Constants in completion show value and type as GoLand does: `MaxItems = 10  untyped int`, `Debug = iota  Level`; a repeated row in a `const` group shows the
  expression it repeats. Values come from the stubs, no other file's AST is loaded.

## [0.2.110] - 2026-10-05

### Added — Fill all fields… / Fill selected fields… in completion (GoLand parity G3)
- In a struct literal (`T{}`, `&T{}`, nested, partly filled) the first two items are **Fill all fields…** and **Fill selected fields…**: the fields not set yet,
  one per line, zero values aligned like gofmt, caret after the first value; Fill selected asks which fields to write. Not offered in positional literals.
  The Alt+Enter Fill intention shares the generator (its output is unchanged, unaligned).

## [0.2.109] - 2026-10-05

### Added — Shadowing variable inspection (GoLand parity G1)
- A local variable that shadows a variable, constant, parameter or receiver of an enclosing scope, or a package-level variable of the same package, is painted
  with the "Shadowing variable" colour (`GO_SHADOWING_VARIABLE`) and gets a weak warning "Declaration of 'x' shadows declaration at line N" (`b.go:N` for another
  file); Alt+Enter offers Navigate to shadowed declaration and Rename variable. A `:=` that reuses a variable of its own scope, `x := x` and `switch x := x.(type)`
  are not reported. The dataflow check "Shadowed error" (the outer `err` is what gets returned) stays as it was.

## [0.2.108] - 2026-10-05

### Changed — inlay hints as in GoLand (GoLand parity G2)
- Parameter name hints are shown only at literal arguments (strings, numbers, `nil`, `true`, `false`), never at variables, selectors or calls; one-letter
  parameter names are shown too (`produce(n: 3)`). Checked line by line against the GoLand probe files.
- New option "Show return parameters" (Parameter names, on by default): the names of named results at the literal values of `return` (`return n: 0, err: nil`).

## [0.2.107] - 2026-10-05

### Added — code vision "Implement interface" and "Add method" (GoLand parity G2)
- "Implement interface" above every type declaration that is not an interface (structs, named basic types, generic types; one per `type ( … )` group): a click
  opens Implement Methods (Ctrl+I) for that type. Own group in Settings | Editor | Inlay Hints | Code vision, on by default.
- "Add method" above an interface that has implementations in the project: a click opens Add Method to Interface, which adds the method to the interface and a stub
  to every implementation. Own group "Add method to interface and all its implementations", on by default.
- Usages / implementations code vision follows the platform's position setting (per group, or the default position); choose "Right" there to get them at the end
  of the declaration line as in GoLand.

## [0.2.106] - 2026-10-05

### Added — struct tags and printf verbs highlighted (GoLand parity G1)
- Struct tags are highlighted by parts: key, colon, quoted value and arbitrary text (`GO_TAG_KEY`, `GO_TAG_COLON`, `GO_TAG_VALUE`, `GO_TAG_TEXT`).
- Printf verbs (`%d`, `%-10s`, `%[1]v`, `%%`) are highlighted inside the format string of `fmt.Printf`, `Sprintf`, `Errorf`, `log.Printf`, `t.Errorf`
  and the project's own printf wrappers (the list of the Printf inspection); valid and invalid string escapes are highlighted too (`GO_FORMAT_VERB` falls back
  onto the valid escape colour, as GoLand paints verbs).

## [0.2.105] - 2026-10-05

### Changed — Go colours as in GoLand (GoLand parity G1)
- The Color Scheme | Go page has GoLand's 64 keys in GoLand's groups: exported and local functions and their calls, builtin calls, struct and interface
  types (declaration and reference), package / local constants, package / scope variables, method receiver apart from parameters, exported / local fields,
  calls of func-valued variables and fields, `nil`, reassignment in `:=`, comment keyword, doc comment references, build constraint tag / parentheses / operators.
  A scheme exported from GoLand reads the same here; each new key falls back onto the key that coloured the element before, so a scheme tuned earlier keeps its look.
- Directive comments: the directive name (`go:generate`, `go:embed`, `line`, `export`) is the comment keyword, `//go:build` lines are split into tags, parentheses
  and operators.

## [0.2.104] - 2026-10-05

### Added — recursive call gutter, Shell Script in `//go:generate` (GoLand parity G1)
- A "Recursive call" icon in the gutter marks calls of the function or method they are made from (`f(n-1)`, `r.walk(x)`, `(*T).f(r)`); off together with the
  implementation gutters (Settings | Editor | General | Gutter Icons, "Go recursive call").
- The command of a `//go:generate` line is highlighted as Shell Script when the IDE has the Shell Script plugin (optional dependency); for `-command NAME cmd`
  the aliased command is highlighted. Only `//go:generate` comments became injection hosts, other comments stay cheap on reformat.

## [0.2.103] - 2026-10-05

### Added — one-line folding (GoLand parity G1)
- Code folding shows short blocks on one line, like GoLand: `if err != nil { return "", err }`, a function with a single `return`, a `case` with one statement,
  empty functions and empty struct / interface types. Five options in Settings | Editor | General | Code Folding (Go), all on by default. Syntactic: the
  condition is a nil comparison, the body one `return` / `panic` / `break` / `continue` / `goto` that fits one line, no comments inside.

## [0.2.102] - 2026-10-04

### Changed — the form of a Go configuration
- Package is a list of the packages of the project, by import path: programs (`package main` with `func main`) for `go run`, packages
  with tests for `go test`; the button next to it picks another directory, or a single `.go` file for `go run`. A new configuration
  starts on the first one; a stored target stays in the list whatever it is.
- Files to copy (debug on an SSH host) is a table: + picks files and directories in the project, written relative to the package
  directory; the second column is where each goes there, empty for the same relative path. Stored as before.
- The directory there is `~/.cache/<project name>` by default (shown in the empty field). The plugin makes it private (`chmod 700`)
  only when it made it itself (a `.go-project-support` marker in it): a `.cache/<name>` that was there before may be another tool's.

## [0.2.101] - 2026-10-04

### Changed — project problems at open: the changed files only
- The snapshot of the Project Errors tab no longer goes to waste when a file changed while the IDE was closed (a pull, a branch switch,
  an edit elsewhere): its findings are shown at once, and the changed and new files go to the incremental queue with their packages and
  importers, the deleted ones take their package's dependents along. The full pass runs only when what every file reports may differ:
  the plugin version, the inspection profile, the toolchain or the build settings (or a deleted directory, whose importers are known
  only while it exists). Seen live: one file edited between two sessions, 9 of 18 files analysed instead of all.

## [0.2.100] - 2026-10-04

### Fixed — debug on an SSH host, two more guards
- The SSH host may contain letters, digits and `. _ - @ : / [ ] %` only, and neither the user nor the host name may start with `-`
  (`ssh://-oProxyCommand=…`, `me@-o…` passed the first check): a `ProxyCommand` with `%h` in `~/.ssh/config` hands the name to a shell
  (CVE-2023-51385).
- The directory there must be owned by the user, and each of its parents by the user or root, writable by others only when sticky
  (`/tmp`): a parent others can write lets them rename the directory away and put their own delve in its place.

## [0.2.99] - 2026-10-04

### Added — Files to copy for debug on an SSH host
- Files to copy: `local` or `local=there` a line each, files and directories of the project relative to the package directory. They go
  in one tar stream through ssh into the directory the program runs in, readable by the user alone (files `0600`, directories `0700`),
  and are sent again only when the set or a file changes (size, time). There a file keeps its relative path (`certs/ca.pem`), or takes
  the relative path after `=`. `go test` takes the package's `testdata` along (a checkbox, on by default).
- Both ends stay inside: a file outside the project (links resolved) and a place outside the run directory (absolute, `~`, `..`) are
  refused. Run configurations come with projects, and one shared with a cloned repository must not send `~/.ssh/id_rsa` to a host it
  names, or write `~/.ssh/authorized_keys` there.
- Each package has its own run directory there (`runs/run-…`, `runs/test-…`), so two packages never share a `testdata`; the program is
  named after its package (`shop`, `store.test`).

### Changed — debug on an SSH host is private to the user
- delve there listens on a unix socket in a fresh `mktemp -d` directory (`0700`), not on a port of 127.0.0.1: any user of the host could
  connect to that port and run code as you through delve. `ssh -W` connects to the socket; the field dlv port there is gone. A server with
  `AllowStreamLocalForwarding no` is named in the error.
- The directory there is `0700`; a directory the user chose that others can write to is refused (they could replace delve).
- An SSH host starting with `-` is refused, and ssh gets `--` before the host: a run configuration shared with a project could otherwise
  pass ssh an option such as `-oProxyCommand`.

## [0.2.98] - 2026-10-04

### Added — Debug on an SSH host
- A "Go" configuration (`go run` / `go test`) has the field SSH host (`user@host`, `ssh://user@host:port` or a host of `~/.ssh/config`,
  key login): Debug then runs the program there. The plugin asks the host for its platform (`uname -sm`), builds the bundled delve for it
  (once per delve version, `CGO_ENABLED=0`) and the program (`go build` / `go test -c` with `-gcflags=all=-N -l`), copies both through
  `ssh 'cat > file'` (delve only when that version is not there yet) into Directory there (default `~/.cache/go-project-support`) and
  starts `dlv dap` on 127.0.0.1 there; the DAP connection is `ssh -W`, so no port is opened here and none to the network there.
  dlv port there: 0 is a free port delve picks; a fixed port in use stops the start with a message. delve and the program end with the
  session or when the connection breaks. The paths in the binary stay the paths here, so breakpoints, frames and the standard library
  open the local sources (seen live: linux delve takes a `C:/...` path).
- Errors of ssh say the way out: a host not in known_hosts yet, no key (prompts are off), TCP forwarding refused by the server.

### Fixed
- The sandbox of `runIdeForUiTests` had no bundled delve (its prepare task was excluded with the test sandboxes): the UI robot
  debugged with a `dlv` from PATH.
- The Go Monitor no longer samples a local process for the process id of a program delve runs on another machine (Remote dlv dap).

## [0.2.97] - 2026-10-04

### Added — govet remainder and deprecation (native lint rules, batch B6)
- 14 warnings of go vet and staticcheck. SA1019 strikes through uses of deprecated functions, types, fields, methods, constants and
  imports of deprecated packages (a `Deprecated: ` paragraph in their doc); for the standard library only once the module's `go` directive
  (or the file's `//go:build go1.N`) reaches the version that deprecated them, with staticcheck's "deprecated since Go X / alternative
  available since Go Y" wording. go vet: stdversion (a stdlib symbol newer than the module's Go version, from a table generated out of
  GOROOT/api), stdmethods (`WriteTo`, `Format`, `MarshalJSON`, error `Is`/`As`/`Unwrap` … with a non-standard signature), tests (malformed
  Test/Benchmark/Fuzz/Example names, generic tests, misplaced `// Output:`, fuzz targets and `f.Add` types), directive (misplaced
  `//go:debug`), hostport, httpmux (enhanced ServeMux patterns before Go 1.22), slog (key/value pairs), composites (unkeyed fields of
  imported structs), deepequalerrors, reflectvaluecompare and httpmux (off with golangci's default vet set). staticcheck: SA4019 identical
  `+build` lines, SA9004 a const group where only the first constant has a type, SA9009 `// go:directive` with a space.
- Quick fixes: Replace fmt.Sprintf with net.JoinHostPort, Add field names to struct literal, Add type to all constants in group, Remove
  the space before the directive.
- golangci-lint's govet defaults include `hostport` (go vet since Go 1.25); `httpmux` is among the analyzers it leaves off.
- `tools/lint-rules/stdlib_data.py` regenerates the two stdlib tables (symbols since go1.22, staticcheck's deprecations).

## [0.2.96] - 2026-10-04

### Added — go:generate from the directive
- ▶ in the gutter of each `//go:generate` line: "Run go:generate" runs this directive alone (`go generate -run '^<the line, regexp-quoted>$' file.go`),
  "go generate file.go" every directive of the file; output in the Build tool window as Go | Generate, documents saved first, the module re-read after.
- Go | Generate File: `go generate` for the Go file of the editor (disabled when the file has no directive).

## [0.2.95] - 2026-10-04

### Added — Check Go code before a commit
- The Commit dialog of a Go project has the option "Check Go code" (on by default, kept per project in the workspace, like "Analyze code").
  Before the commit it runs the plugin's own Go inspections — the ones of Go | Inspect Project, by the current inspection profile — over
  the changed `.go` files only (not vendor / testdata / `.x` / `_x`). Warnings and errors stop the commit: "Review Go problems" opens them
  in Inspection Results with their fixes, "Commit Anyway" goes on; weak warnings do not stop it. No external linter runs here.

## [0.2.94] - 2026-10-04

### Added — Copy JSON sample
- Alt+Enter on a struct type: "Copy JSON sample to clipboard", the reverse of Type from JSON. Keys from `json` tags (`name,omitempty`, `-`),
  unexported fields skipped, embedded structs flattened as encoding/json does, sample values by type (`""`, `0`, `false`, an RFC 3339
  `time.Time`, `[sample]` for slices, `{}` for maps, the pointee for pointers, nested structs of the same package with a cycle guard, `null`
  for the rest).
- Docs: `docs/LINT-RULES.md` statuses of batches B2–B5, B8, B9 and its summary brought up to date; stale rows of `docs/FEATURES.md` fixed.

## [0.2.93] - 2026-10-04

### Changed
- The plugin builds against IntelliJ IDEA Community: the Database plugin is no longer needed at compile time (SQL is injected by
  language id where the Database plugin is installed, and the build adds it only when the IDE bundles it).
- Build without internet: `tools/ci/nexus.init.gradle` redirects every repository of the build to Nexus proxies, `tools/ci/truststore.sh`
  adds corporate root certificates, `tools/ci/Jenkinsfile` is an example pipeline; instructions in `docs/BUILD-OFFLINE.md`.

## [0.2.92] - 2026-10-03

### Changed
- Problems | Project Errors is not analysed again at every project open: after a complete pass the findings are kept on disk with a
  fingerprint of the project (the analysed files by size and time, go.mod / go.sum / go.work / vendor/modules.txt, the toolchain, GOOS /
  GOARCH / build tags / GOEXPERIMENT, the inspection profile, the plugin version). Opened again unchanged, the tab is filled from it at
  once and no pass runs; any difference runs the full pass as before. No snapshot is written while a Go file has unsaved changes.

## [0.2.91] - 2026-10-03

### Fixed
- "Accessing invalid virtual file" from the package scope when a Go file was deleted and created again at the same path (seen on
  `~/.aws/main.go`): a package remembered by the project model finds the new file by its path, a file gone for good is skipped.

## [0.2.90] - 2026-10-03

### Changed — faster project open
- Project open runs `go env -json` once: concurrent callers share one process, and the answer of the previous session is served from
  disk (`go-env.json` in the plugin data directory, keyed by the go executable, the go env file and the GO* environment) and checked once
  in the background.
- The toolchain of the project model is computed once at a time and bumps the model only when it actually changes; every bump is logged
  with its reason and a counter.
- The `go list -m -json all` fallback of the module graph is kept on disk per go.mod and reused by later sessions with the same
  go.mod/go.sum/go.work and go; one run per module at a time, and the model is bumped only when the result has other modules than the
  pure graph.
- The bundled delve is built at project open only after indexing, with `-p 2` and `GOMAXPROCS=2`; Debug still builds it at once at full speed.
- The first pass of Problems | Project Errors waits until the daemon has highlighted the open editors (at most 15 s after indexing).

### Fixed
- 22–28 `go env` processes at every project open, each dropping the caches of the project model.
- The background check of `go env` no longer counts every answer as new: `GOGCCFLAGS` names a random `go-build` directory each run.
- A go.mod in the IDE's own directories (the sources of the bundled delve in the plugin, rewritten on every update) no longer bumps the
  model of every open project at start.

## [0.2.89] - 2026-10-03

### Added
- Settings | Go | Build Tags → Experiments → Choose…: a checklist of the GOEXPERIMENT names the installed Go knows, read from its sources
  (`internal/goexperiment/flags.go`, defaults from `internal/buildcfg/exp.go`), so the list follows the Go version. Defaults are marked;
  only differences from them are written (`arenas,nogreenteagc`); names the toolchain does not know are kept.

### Fixed
- The gopls tool window button is not shown while the language server is off (the default); it comes back when gopls is turned on.

## [0.2.88] - 2026-10-03

### Added — the plugin stays out of projects without Go
- A project without Go files (no `.go`, `go.mod`, `go.work`) shows nothing of the plugin: the Go main menu and the Go entries of the
  Project view popup are hidden, the Go Tests, Go Monitor, Go Dependencies and gopls tool windows and the Go status bar widgets are not
  there. As soon as a Go file appears (created, copied, updated from VCS), everything comes back without a restart.

### Changed
- A project without Go files pays nothing at startup: no toolchain check (`go env`), no build of the bundled delve, no package catalogue,
  no project problems pass, no run configuration scan, no welcome page, no check of the plugin data directory. When Go files appear,
  the toolchain check, the delve build, the file type check, the catalogue, the interfaces warm-up, the run configurations and the
  project analysis start then.

## [0.2.87] - 2026-10-03

### Added — plugin data directory, checked for running programs
- Settings | Go | Tools → Plugin data directory: where the plugin keeps the delve it builds, the tools installed from that page (`GOBIN`),
  the symbol catalogue, temporary `go run` / `go test` builds and, when chosen, the logs. Default: the system directory of the IDE.
  Changing it moves the files in the background and builds delve again there.
- On start the plugin checks that programs can run from that directory (a copy of `/bin/true` is started there): a `noexec` mount or an
  execution policy that allows only `/home/work/<user>` gives a notification with "Use /home/work/<user>/.go-plugin" or the settings
  page. When the system temporary directory cannot run programs, go commands get `GOTMPDIR` in the plugin data directory. Not checked
  on Windows.
- The bundled delve now lives in `<plugin data>/delve/<hash>` (built once more after the update).

## [0.2.86] - 2026-10-03

### Fixed
- Projects with GoLand run configurations (`.idea/workspace.xml` or shared `.run` files) no longer show "Plugin Go supporting run
  configuration 'GoApplicationRunConfiguration' is currently not installed": the plugin registers GoLand's configuration type ids itself.

### Added
- GoLand's Go Build and Go Test run configurations load as Go configurations and run with the plugin's runner and debugger: package,
  file or directory, working directory, program and go tool arguments, environment, test pattern and benchmarks. They are saved back in
  GoLand's format, so a shared `.run` file still opens in GoLand; unchanged ones are not rewritten.

## [0.2.85] - 2026-10-03

### Added — file nesting
- go.sum is nested under go.mod and go.work.sum under go.work in the Project view (Project view | File Nesting), as in GoLand.

## [0.2.84] - 2026-10-03

### Added — update lines in go.mod
- "Update all dependencies" and "Update direct dependencies" above the first `require` of go.mod, as in GoLand: `go get module@latest`
  for the requires, then `go mod vendor` when the module vendors (the Upgrade quick fix does the same).

### Fixed
- Newer versions were never shown in a module with a `vendor/` directory: go then defaults to `-mod=vendor` and refuses every query.
  The check asks with `-mod=readonly`, which writes nothing.

## [0.2.83] - 2026-10-03

### Added — newer versions in go.mod
- A `require` whose module has a newer version in the module proxy is highlighted on its version ("Newer version is available: v1.7.0");
  Alt+Enter → "Upgrade to v1.7.0" runs `go get`. Asked in the background with `go list -m -e -json module@latest` when go.mod is opened
  and when its requires change, at most once an hour; it needs the network, and the inspection "Newer version of a dependency" (Go
  modules) turns it off. Before, this came from gopls, which is now off by default.

## [0.2.82] - 2026-10-03

### Changed — gopls off by default
- The language server is off by default and is not started: errors, completion, navigation, refactorings and formatting come from the
  built-in analysis (Language features: Built-in is the default too). gopls is not offered for installation at start any more. Settings |
  Go | Language Server → "Use gopls…" brings it back, with the Language features switch as before. A choice saved earlier is kept.

## [0.2.81] - 2026-10-03

### Fixed
- Completion where only a type can stand (a struct field, `var x `, a parameter) offered functions and constants of the catalogue
  (`flag.String`, `reflect.String`) and, while the IDE indexed, no `string`: the catalogue offers types there, and during indexing the
  predeclared types and `struct` / `interface` / `map` / `chan` / `func` are offered too.

## [0.2.80] - 2026-10-03

### Added — the type of a field by its name, as grey text
- `Name str|` shows `ing`, `CreatedAt ` → `time.Time` (the import is added on accept), `Timeout ` → `time.Duration`, `IsAdmin ` → `bool`,
  `Price ` → `float64`, `Tags ` → `[]string`, `Users ` → `[]User` when the package has `User`, a field named like a type of the package
  → that type. A field of the same name in another struct of the package wins (`ID` is what the code base says it is; an unknown `ID`
  gets nothing). The same names complete `var count` when nothing below tells the type.

## [0.2.79] - 2026-10-03

### Added — grey text by the name alone, next to the completion list
- `x :=` whose type nothing below tells gets its `make` from the name: `tables` → `make([]Table, 0)` when the package has a type `Table`
  (else the caret waits in `make([]|, 0)`), `jobsCh` / `ch` → `make(chan Job)` / `make(chan |)`, `usersByID` / `userMap` →
  `make(map[|]User)`, `idSet` / `seen` → `make(map[|]struct{})`. Not for names already used below (the rules by use decide there), not for
  test tables (`tests`, `cases`).
- Grey text is shown while the completion list is open too (`tables := ma` showed only the list), as in GoLand.

## [0.2.78] - 2026-10-03

### Changed
- Unreachable code is greyed out like unused code, the whole dead run up to a label, instead of a warning on its first statement; "Delete
  unreachable code" removes exactly what is grey.

## [0.2.77] - 2026-10-03

### Added — delve inside the plugin
- The sources of delve v1.27.2 (`third_party/delve`, a git submodule at the release tag with its `vendor/`) ship inside the plugin and are
  built with the user's `go` in a background task on project open (progress in the status bar; `-mod=vendor`, `GOTOOLCHAIN=local`: no
  network) into the system directory of the IDE, one directory per hash of the sources. A plugin update with other delve sources builds
  again; a lost binary is built again on the next start or on Debug. The `dlv` path from Settings | Go | Tools still wins; when the build
  fails (no `go`, a toolchain older than delve needs), the plugin falls back to a `dlv` on PATH and the install offer as before.

## [0.2.76] - 2026-10-03

### Added — staticcheck simplifications (native lint rules, batches B8 and B9)
- 37 weak-warning rules of staticcheck S (former gosimple), each with a quick fix that keeps gofmt formatting and is withheld when it would
  drop a comment or change meaning. Statements (B8): S1000 single-case select, S1001 copy loop, S1005 needless blank, S1006 `for true`, S1008
  if-return-bool, S1011 append loop, S1016 struct conversion, S1017 TrimPrefix/TrimSuffix, S1018 sliding loop, S1021 merged var, S1023
  redundant return/break, S1029 range over `[]rune(s)`, S1031 nil check around range, S1033 guarded delete, S1034 type-switch assertions,
  S1036 guarded map update, S1037 `time.After` select. Calls and expressions (B9): S1003 Index → Contains, S1004 Compare → Equal, S1007 raw
  regexp strings, S1009, S1010, S1012 time.Since, S1019, S1020, S1024 time.Until, S1025, S1028 Errorf, S1030, S1032, S1035, S1038 Printf,
  S1039, S1040, SA6005 EqualFold, SA6006 Write.
- S1002 follows staticcheck: any comparison with a bool constant, not only `if` conditions; not in `_test.go` files. `//nolint:gosimple` works.

### Fixed
- Debugging a test logged "[Split debugger] RunContentDescriptor should not be used in split mode": the session starts through
  `XDebuggerManager.newSessionBuilder`, which hands out the descriptor in both modes.

## [0.2.75] - 2026-10-03

### Added — suspicious expressions and statements (native lint rules, batches B4 and B5)
- 40 warnings of staticcheck SA and go vet. Expressions (B4): SA4000 identical operands, SA4001 `&*x`, SA4003 impossible comparisons, SA4012
  NaN, SA4013 `!!b`, SA4016, SA4022 `&x == nil`, SA4024, SA4025, SA4026 `-0.0`, SA4028 `x % 1`, SA4032 GOOS ruled out by build constraints,
  SA5010 impossible assertions, SA9006 shifts that clear the value; vet ifaceassert, nilfunc, shift, bools, stringintconv, unsafeptr.
  Statements (B5): SA2001 empty critical section, SA2003 deferred Lock, SA3001 `b.N` assignment, SA4011 ineffective break, SA4014 repeated
  condition, SA4020 unreachable type case, SA4021 / vet appends, SA4029, SA5002 spinning loop, SA5003 defer in an infinite loop, SA5004 busy
  select, SA6000 regexp in a loop, SA6001, SA6003, SA9003 empty branch (off by default, as in staticcheck), SA9008, SA9010; vet atomic, defers.
- Quick fixes where safe (math.IsNaN, math.Copysign, fmt.Sprint, defer Unlock, sort.Ints, remove empty default, `defer f()()`, …). A vet
  rule stays quiet where its staticcheck twin reports.

## [0.2.74] - 2026-10-03

### Added — stdlib call contracts (native lint rules, batches B2 and B3)
- 39 warnings of staticcheck SA on stdlib calls, callees resolved by types (aliased and dot imports): SA1000 invalid regexp (Go's own error
  text), SA1001 invalid template, SA1003, SA1004 `time.Sleep(5)`, SA1005 shell line in exec.Command, SA1006 dynamic Printf format, SA1007
  invalid URL, SA1008 non-canonical header key, SA1010, SA1011, SA1012 nil context, SA1013 Seek arguments, SA1014 / vet unmarshal non-pointer,
  SA1015 time.Tick leak, SA1016 untrappable signals, SA1017 / vet sigchanyzer unbuffered signal channel, SA1018, SA1020 bad host:port, SA1021,
  SA1024, SA1026 unsupported marshaling, SA1027 / vet atomicalign, SA1028 / vet sortslice, SA1029 context key type, SA1030 strconv arguments,
  SA1032 errors.Is order, SA4015, SA4027 `u.Query().Set`, SA4030 `rand.Intn(1)`, SA5005, SA5012, SA6002 Pool.Put, SA9002 `644`, SA9005,
  SA9007 RemoveAll of the home/temp directory. Quick fixes where safe. Attribution in `NOTICE.md`.

## [0.2.73] - 2026-10-03

### Added — every check in one table; Cgo and experiments
- Settings | Go | Linters → Built-in: all inspections of the plugin and all lint rules in one table — search, on/off, level, a source note
  (set by `.golangci.yml`, quiet with gopls), group enable / disable / reset, description and rule options. Inspections live in the project
  profile (in sync with Settings | Editor | Inspections), rules in `.idea/goRules.xml`.
- Settings | Go | Build Tags: Cgo support (Default from `go env` / Enabled / Disabled) and Experiments (GOEXPERIMENT). They decide the `cgo`
  and `goexperiment.X` build constraints of the analysis and go to the go commands of the plugin and to delve; the status bar shows `· cgo off`.

## [0.2.72] - 2026-10-03

### Changed — Go settings at the root, as in GoLand
- Settings | Go is a top-level node above Appearance & Behavior with pages GOROOT, GOPATH, Go Modules, Build Tags, Imports, Linters,
  Formatting, Editor and Completion, Language Server (+ gopls), Debugger and Tools; the old Code Quality page is split into Linters and
  Formatting. Stored values are kept. Page titles follow the plugin's language (Russian too) through `messages.GoSettingsTitles`.

## [0.2.71] - 2026-10-03

### Added — grey text in the colours of the code, second batch of templates
- Grey-text suggestions (idioms and code from context) use the colours of the editor scheme faded halfway towards the background, like
  GoLand's Full Line; Tab, word and line acceptance work as before. Editor and Completion → "Colour grey-text suggestions like code".
- 60 more templates (P2 of `docs/INLINE-SUGGESTIONS.md`): maps and channels by their use below, ticker / timer / deadline, strconv, JSON
  encoders, `db.QueryContext`, `regexp.MustCompile` with the caret inside, table tests and `t.Run` loops, benchmarks, httptest, `errors.Join`,
  `Len()`, `Error()`, enum `String()` switches, `errors.Is(err, ErrX)`, sort comparators, `signal.NotifyContext`, `for range ch`, type switches,
  `close(ch)`, `var _ I = (*T)(nil)`.

## [0.2.70] - 2026-10-03

### Added — grey text from context
- Suggestions built from the variables in scope, their types, the function signature and how a name is used below: `make([]T, 0, len(xs))`,
  sets and counters, `context.WithTimeout(ctx, timeout)`, `time.Now()`, constructors and struct literals from fields in scope, `return` with
  zero values and the built value, call arguments by type and name, `for _, user := range users {`, `go func() { defer wg.Done() }()`,
  constructor / getter / setter bodies, sentinel errors. Missing imports are added on accept. Editor and Completion → "Suggest code from context".

## [0.2.69] - 2026-10-03

### Fixed
- Struct tags: `validate:"mi`, `binding:` and `gorm:` values complete their rules again (the autopopup was suppressed inside rule keys).
- "Handle error" is offered once on a call that Unchecked error already reports.

## [0.2.68] - 2026-10-03

### Added — problems of the whole project
- Problems | Project Errors lists the problems of every Go file of the project, not only the open ones: a background pass after indexing and
  on changes, Go | Reanalyse Project Problems, settings "Analyse the whole project" and "Include warnings". vendor, testdata, generated files
  and directories without Go files are skipped.

## [0.2.67] - 2026-10-03

### Added — native lint rule engine
- One inspection (`GoRules`) runs lint rules by context (call, expression, statement, function, type, file, package) and by what they need
  (syntax, types, flow, project index). Suppression by `//nolint`, `//lint:ignore`, `//noinspection`. The project's `.golangci.yml` decides
  which rules run and their options (staticcheck `checks`, revive rules and arguments, govet / gocritic / gosec settings); `.idea/goRules.xml`
  overrides it. First rules: errcheck, S1002, revive function-result-limit and package-comments, interfacebloat.
- `docs/LINT-RULES.md`: the catalog of 526 golangci-lint and 123 GoLand checks with their status and batches.

## [0.2.66] - 2026-10-03

### Added — Inspect Project and SARIF export from the Go menu
- **Go | Inspect Project** runs every Go and go.mod inspection the current profile enables over the project (from the Project view: over the selected
  directory) through the platform's batch inspection: results in the standard Inspection Results window, grouped by inspection and file, with
  navigation, quick fixes and batch apply.
- **Go | Export Inspections to SARIF…** runs the same inspections as `go-inspect` in a cancellable background task, writes a SARIF 2.1.0 report to
  the chosen file (default `<project>/go-inspect.sarif`) and shows "N findings written to …" with Open File and Show in Explorer.
- Both skip vendor, testdata, `.x` / `_x` directories, generated files and files excluded by build constraints, and work with Language features =
  gopls: the native inspections are let through for the duration of the run, open editors are re-highlighted afterwards.

### Changed
- `go-inspect` and the SARIF export share one implementation (`ci.GoInspectRun`); files are walked through the VFS. The guide describes `go-inspect`
  as a mode of the IDE launcher (`idea64.exe go-inspect …`), not a separate program.
- Welcome page and Help Page: seven new animated scenes (Extract Function, Change Signature across an interface hierarchy, analysis while typing,
  Inline, SQL and RE2 in strings, the GOOS/GOARCH widget, inspections from the Go menu and in CI); new guide sections Refactorings and Checks with
  CSS animations; the static last frame under `prefers-reduced-motion` in the guide.
- Exhaustive switch inspection (`GoExhaustiveSwitch`) is a weak warning: a plain warning was noise on `reflect.Kind`-like switches without `default`.

## [0.2.65] - 2026-10-03

### Changed — golangci-lint is optional, off by default
- Settings | Tools | Go | Code Quality → "Use golangci-lint (optional)", off: the built-in inspections are the analysis of the plugin. While it is
  off the editor annotator does not run, the "Go tools are missing" notification does not ask for golangci-lint, and the `golangci-lint fmt`
  formatter is hidden (a stored choice falls back to gofmt). The old "Show golangci-lint warnings in the editor" switch is gone.
- A saved Go file is linted again right after the save (golangci-lint and custom linters); before, the warnings waited for the next edit.
- External linter findings that a native inspection already reports are dropped: errcheck → Unchecked error, ineffassign → Ineffectual
  assignment, unused → Unused variable / parameter, govet printf → Printf.

### Added — Custom linters
- Code Quality → Custom linters: Name | Command line | Enabled | Run (On save / On the fly, saved files) | Working directory (Module root / File
  directory) | Output format (golangci JSON / SARIF 2.1.0). Macros `$FilePath$`, `$FileDir$`, `$ModuleDir$`, `$Package$`, `$ImportPath$`. Findings
  show as `[name] message` with Suppress with `//nolint:name`. Failures and timeouts go to the plugin log (category `lint`) and once per linter to a
  balloon. Linter timeout, seconds (default 90) for golangci-lint and the custom linters.

## [0.2.64] - 2026-10-03

### Added — Unchecked errors while typing
- Inspection **Unchecked error** (`GoUncheckedError`, warning, on by default): errcheck over the PSI, reported while typing in both Language
  features modes (gopls has no errcheck; golangci-lint reported it only after a save). A call standing alone whose last result is `error`
  (`os.Open("x")`, `f.Close()`, a method through `io.Closer`); errcheck's defaults: `defer` / `go`, `_ = f()` and its exclude list (`fmt.Print*`,
  `fmt.Fprint*` to `bytes.Buffer` / `strings.Builder` / `os.Stderr`, `bytes.Buffer` / `strings.Builder` writes, `hash.Hash.Write`, …) stay quiet;
  `//nolint:errcheck` silences it.
- Fixes: **Handle error** (`if err := f(); err != nil { return … }`, or `_, err := f()` + check; zero values of the enclosing results, `err1` when
  the block already declares `err`) and **Assign to blank identifier** (`_ = f()`). The "Handle error" intention steps aside on such calls, so
  Alt+Enter offers it once.

## [0.2.63] - 2026-10-03

### Added — Add Method to Interface
- Intention "Add method to interface" (on an interface of the project): a form with Name (checked as you type: Go identifier, a clash with the
  interface's methods, a hint for an unexported method of an exported interface), Parameters and Results tables (Name | Type; add / remove / move
  with Alt+Insert / Alt+Delete / Alt+Up / Alt+Down; variadic only last, names all-or-none), a live signature preview, "Delegate in wrappers" and the
  list of implementations that get a stub (wrappers, generated and outside types marked). Refactor stays off until the method is valid.
- The method is added to the interface spec and to every implementation in the project: wrappers that hold the interface in a field delegate
  (`return s.next.Delete(ctx, id)`), other types (hand-written mocks) get a `panic("not implemented")` stub; generated files and code outside the
  project are listed, not changed.
- Go completion in the refactoring dialogs: type cells of Add Method to Interface and Change Signature, the Results field and the Default value
  column (expressions) offer the types, packages and values of the declaration's package. A package chosen there is imported into every file the
  refactoring changes (Change Signature used to leave new qualified types and default values without imports).

## [0.2.62] - 2026-10-03

### Changed — Change Signature across an interface hierarchy
- Change Signature on an interface method, or on a method that implements a project interface, changes the whole hierarchy: the interface spec,
  every implementing method (cache wrappers, business-logic types, hand-written mocks), the delegating calls inside wrappers
  (`s.next.Get(ctx, id, opts)`) and every call site. Checkbox "Change the whole hierarchy" (on by default); generated files and types outside the
  project are reported as conflicts instead of being edited.

## [0.2.61] - 2026-10-03

### Added — Project-wide checks
- **Import cycle** (`GoImportCycle`, error): an import that closes a cycle among the packages of the project, with the shortest chain in the
  message; in-package `_test.go` files count, external test packages do not.
- **Internal import** (`GoInternalImport`, error): an import of an `internal/` package from outside its tree, for project packages, module cache
  dependencies and the standard library.
- **Unused exported declaration** (`GoUnusedExported`, off by default): an exported function, type, variable or constant nothing in the project
  refers to, only in packages whose every importer is project code (under `internal/`, or a module with `package main`); fix Safe Delete.

## [0.2.60] - 2026-10-03

### Added — Go assembly
- `.s` files next to Go code are "Go Assembly" (Plan 9 syntax of cmd/asm): highlighting with its own colour page, commenter, and navigation
  `TEXT ·Name(SB)` ↔ the Go function declared without a body in the same directory (reference and gutter markers both ways). Other assemblers'
  `.s` files (outside Go directories) keep their file type.

## [0.2.59] - 2026-10-03

### Added — Inspections in CI (SARIF)
- `go-inspect` command: the plugin's Go and go.mod inspections run without the IDE UI and write a SARIF 2.1.0 report
  (`idea.sh go-inspect <projectDir> <out.sarif> [--inspections A,B] [--min-severity weak|warning|error]`). No gopls: the run switches Language
  features to Built-in and the language server off, and restores both. Takes the inspections the project profile enables (or the listed ones),
  honours `//noinspection`, skips vendor / testdata / generated files and files excluded by build constraints. Exit code 0 (clean), 1 (findings at
  or above the minimal severity), 2 (bad arguments, a failed run, or inspections that failed while nothing was found). Results sorted, paths
  relative to the project.
- `tools/ci/go-inspect.sh` / `.cmd` install the plugin ZIP into a throwaway IDE config and return the exit code (`idea.bat` loses it); `docs/CI.md`
  has the options, the report format and a GitHub Actions job with `upload-sarif`. The platform's `inspect` writes only XML / JSON (SARIF is Qodana's).

## [0.2.58] - 2026-10-03

### Added — Move
- Move (F6) of package-level declarations on the built-in PSI: functions, types (with their methods), vars and consts, chosen by the caret, a
  reference at the caret, a selection or the Project / Structure view; a dialog with the target directory, the file name and "Move methods of the types too".
- Another file of the same package (existing or new): the text moves with its doc comments, needed imports are added to the target, imports left
  unused in the source are removed; a spec taken out of a group becomes its own declaration.
- Another package of the project (existing directory or a new one under the module, `package <dirname>`): references are qualified or unqualified,
  imports added and cleaned, the moved code qualifies what it uses from the source package; a type takes its methods.
- Conflicts: unexported names across the new boundary, import cycles, names the target declares already, dot imports, qualifier clashes, package
  main. Refused: one name of a multi-name spec, one constant of an iota group, a target outside the project or a Go module.

## [0.2.57] - 2026-10-03

### Added — Change Signature
- Change Signature (Ctrl+F6) for functions, methods and interface methods: a dialog with the name, the parameter table (name, type, default value
  for new ones; add, remove, move up / down), the results as text and a signature preview. The declaration is regenerated (receiver, type parameters
  and doc comment kept), every reference gets the new name, every call gets its arguments mapped from the old slots (defaults for new parameters;
  method expressions keep the receiver first; `f(xs...)` keeps the spread); a renamed parameter is renamed in the body.
- Refused: a variadic parameter not last, a new parameter without a default value, invalid names, mixing named and unnamed. Conflicts: reordered or
  removed arguments with side effects, `f(g())`, the function used as a value, interface methods and their implementations (changed alone), a
  removed parameter used in the body, a changed result count where calls use the results, name clashes. Results are not rewritten at call sites.

## [0.2.56] - 2026-10-03

### Added — Inline
- Inline (Ctrl+Alt+N), no dialog, a refusal is an error hint with the reason. Inline Variable: a local `x := v` / `var x T = v` has every read
  replaced by `v` (parenthesised by precedence, `T(v)` when the explicit type differs) when, on the flow graph, its definition is the only one reaching
  each read and the variables of `v` hold the same values there; values with calls or memory reads only into a single use of the same block.
- Inline Constant: package-level or local, every use in the package becomes the value (`T(value)` for a typed constant), the spec goes; not `iota`.
- Inline Function: on a call, that call of a one-statement function or method (`return expr` or an expression statement); on the declaration's name,
  every call and the declaration (functions only). Refused: recursive, generic, variadic, named or several results, several statements, arguments
  with side effects, a non-trivial argument used twice, calls from another package.

## [0.2.55] - 2026-10-03

### Added — Extract Function / Method
- Extract Function / Method (Ctrl+Alt+M): whole statements of one block or a single expression become a new function after the enclosing
  declaration, named `extracted` (a free variant) and renamed in place. No dialog.
- Parameters: variables declared outside and used inside, in order of first use. Results from the flow graph: written variables read after
  (`x = extracted(…)`), variables declared inside and used after (`a, b := extracted(x)`), read-and-written live variables go in and come back.
  A selection using the method receiver becomes a method on the same receiver. Every path returning → `return extracted(…)`. Generic functions copy
  the used type parameters with their constraints.
- Refused with a hint: partial statements, `return` on some paths, `break` / `continue` / `goto` / `fallthrough` leaving the selection, `defer`,
  assigning the receiver, local types and constants used inside.

## [0.2.54] - 2026-10-03

### Added — Safe Delete of parameters
- Safe Delete (Alt+Delete) on a parameter of a function or method declaration (Rename: Built-in): the parameter goes from the signature (`a, b int`
  keeps `a int`; variadic too) and its argument from every call in the project, method expressions `T.M(recv, …)` / `(*T).M(&v, …)` included.
- Conflicts in the Safe Delete dialog: the parameter is used in the body; the function is used as a value; the method implements a project interface
  method; an argument has side effects (removed on Refactor Anyway); arguments do not map one to one (`f(g())`, that call is left as is). A parameter of
  an interface method spec is a conflict ("delete in implementations first").
- "Remove unused parameter" and Safe Delete share one implementation (`ide.refactoring.GoParameterRemoval`).

## [0.2.53] - 2026-10-03

### Added — SQL in strings
- SQL injected into Go strings when the IDE has the Database plugin (optional dependency `com.intellij.database`): the query argument of `database/sql`
  (`DB` / `Tx` / `Conn`: `Query`, `QueryRow`, `Exec`, `Prepare` and the `Context` variants), sqlx (`Select`, `Get`, `Queryx`, `NamedExec`, … and `Context`
  variants) and pgx v5 / pgxpool (`Query`, `QueryRow`, `Exec`), plus a raw string starting with a SQL keyword assigned to a const / var named `*Query`,
  `*SQL` or `*Sql`. Generic dialect (the platform's SQL dialect mapping applies); placeholders `$1`, `?`, `:name`, `@name` are not errors. Acts with the
  Semantic colors switch on Built-in.

## [0.2.52] - 2026-10-03

### Added — Unused requires
- go.mod inspection "Unused require" (warning, group Go modules): a direct `require` that no .go file of the module imports a package of (the longest
  required module path that equals the import or is its parent; `tool` directives count as imports; `// indirect` lines, `vendor`, `testdata` and nested
  modules are skipped; build tags are not considered). Quiet while indexing. Fix "Remove unused require" deletes the line, and the `require ( )` block
  when it becomes empty; `go mod tidy` is not run.

## [0.2.51] - 2026-10-03

### Added — GOOS/GOARCH in the status bar
- Status-bar widget "Go Build Target" with the platform of the analysis (`windows/amd64`, `· tags` when build tags are set). Click: common pairs
  (linux/amd64, linux/arm64, darwin/arm64, windows/amd64, js/wasm, wasip1/wasm), all `go tool dist list` pairs, "Host default", "Edit build tags…". The
  choice is saved in the settings and highlighting is recomputed. Shown in projects with go.mod / go.work. Affects the built-in analysis, not gopls.

## [0.2.50] - 2026-10-03

### Added — Regular expressions and JSON in strings
- RE2 regular expressions in the pattern argument of `regexp.Compile`, `MustCompile`, `CompilePOSIX`, `MustCompilePOSIX`, `MatchString`, `Match` and
  `MatchReader`: highlighting, completion and the platform's RegExp checks in the RE2 dialect. Named groups `(?P<n>…)` / `(?<n>…)`, `\Q…\E`,
  `[[:alpha:]]` and `\pL` are accepted; lookahead, lookbehind, backreferences, atomic groups and possessive quantifiers are errors
  ("RE2 (Go regexp) does not support lookahead"). Acts with the Semantic colors switch on Built-in.
- JSON in the argument of `json.Unmarshal([]byte("…"), …)` / `json.Valid`, in `json.NewDecoder(strings.NewReader("…"))`, and in a raw string that looks
  like JSON assigned to a const or variable whose name contains "json". Needs the JSON plugin (optional dependency); without it there is no injection.
- Go string literals are language injection hosts (interpreted strings decode their escapes, raw strings are taken verbatim): `GoStringLiteralMixin`
  in go-psi-core, tree and stubs unchanged.

## [0.2.49] - 2026-10-03

### Added — Unused parameters
- Inspection "Unused parameter" (`GoUnusedParameter`, weak warning, gopls `unusedparams`): a named parameter of an unexported function or method that
  its body never uses. Quiet for exported declarations (callers in other modules fix the signature), `*testing.T` / `B` / `F` / `TB` parameters, functions
  without a body, with an empty or panic-only body, `init` / `main`, test functions, `//export` / `//go:linkname`, HTTP handlers, methods implementing an
  interface method, functions used as values (references searched in the package's directory), function literals and `_`.
- Fixes: "Rename to _" keeps the signature; "Remove unused parameter" removes it from the signature (groups, variadic) and the argument at every call,
  dropping imports left unused. Offered only when every reference is a direct call whose argument has no side effects and none is a method expression.

## [0.2.48] - 2026-10-03

### Added — Rename package
- Rename Package on the native PSI (Rename: Built-in): Shift+F6 on a package clause, on the qualifier of an unaliased import of a project package
  (`store.Load`), on an import path or on a package directory in the Project view.
- Clause `old` → `new`: every file of the directory gets `package new` (`old_test` → `new_test`), unaliased qualifiers in importers become `new.X`
  (aliased imports keep their alias), import paths follow, and the directory is renamed when it is named after the package (not the module root).
- Directory rename: import paths `…/old` and `…/old/sub…` in the project are rewritten (quote style kept); the clause follows when the package is named
  after the directory, is not `main` and the new name is an identifier (`my-store` renames the directory only).
- Conflicts dialog: the new name clashes with another import or a package-level name in an importing file, or the directory exists. Packages outside
  the project (GOROOT, module cache) cannot be renamed. Shift+F6 on an unaliased project import qualifier now renames the package instead of adding an alias.

## [0.2.47] - 2026-10-03

### Added — Safe Delete
- Safe Delete (Alt+Delete) on the native PSI for package-level functions, methods, types, variables and constants, struct fields and interface methods;
  usages come from reference search and any usage outside the deleted element is a conflict in the platform dialog.
- Extra conflicts: a method implementing a project interface method that is called through the interface; a field listed in an unkeyed literal (`T{1, 2}`);
  constants of a group that repeat the deleted one's expression or shift `iota`; one name of `var a, b = f()`.
- The declaration goes with its doc comment, trailing comment and one blank line; a single spec takes its `type` / `var` / `const` along; from `a, b T`
  only the name (and its value) is removed. Parameters and locals are not handled. Acts with the Rename switch on Built-in.

## [0.2.46] - 2026-10-03

### Added — Introduce Variable / Introduce Constant
- Introduce Variable (Ctrl+Alt+V): the selected expression (or one picked from the platform chooser) becomes `name := expr` before the statement that
  evaluates it; an `if` / `switch` header, init statement or range expression puts it before the statement. "Replace all" covers equal expressions in the
  function and declares the variable in their innermost common block; a call with several results becomes `a, b := f()` named after the results.
- Names: `err`, `ctx`, the last word of the call or selector (`GetName()` → `name`), then by type; clashes get `name1`; the name is then edited in place.
- Unavailable where moving the evaluation changes meaning: right operand of `&&` / `||`, `for` condition and post statement, `else if` header, `case`
  expressions, `go` / `defer` calls, names declared in the same header, assignment targets, `&` operands, callees, types, package qualifiers, `nil`.
- Introduce Constant (Ctrl+Alt+C): a constant expression (value from the checker, no `iota`) becomes `const name = expr` after the imports, for this
  occurrence or all in the file; the name comes from the string's words (`"r8 failed"` → `r8Failed`) or the type. Both act with the Rename switch on Built-in.

## [0.2.45] - 2026-10-03

### Added — Call and type hierarchy
- Call Hierarchy (Ctrl+Alt+H) on the native PSI for functions, methods and interface methods: callers grouped by the enclosing declaration (a call in a
  function literal counts for the function around it, one in a package-level `var` for the variable), with the usage count; callers of a method include
  the calls through the interface methods it implements, marked "via Iface". Callees: calls in the body, through interfaces too; builtins, conversions
  and calls of function-typed variables are left out. Recursion is shown once.
- Type Hierarchy (Ctrl+H) for named types: supertypes are the embedded types and the interfaces a concrete type implements; subtypes are an interface's
  implementations and embedding interfaces, a struct's embedding structs. An interface opens on Subtypes, other types on Supertypes.
- Both answer with the Navigation switch on Built-in; otherwise the platform asks the next provider.

## [0.2.44] - 2026-10-03

### Added — go.mod checks
- Inspections for go.mod / go.work (group "Go modules", always on, independent of the gopls / Built-in switch): a `replace` to a missing local directory
  or one without go.mod and a go.work `use` of a directory without go.mod (errors); a duplicate `require` (fix "Remove duplicate require"); a module that
  requires or replaces itself; `vendor/modules.txt` out of sync with the requirements (missing, other version, not `## explicit`; the fix copies
  `go mod vendor` to the clipboard, nothing is run); a malformed `go` version (error) and a `toolchain` older than `go`.

## [0.2.43] - 2026-10-03

### Added — Unused results and lint without data flow
- `GoUnusedResult`: the result of a pure function or method dropped (`strings.ReplaceAll(s, …)`, `errors.New(…)`, `fmt.Sprintf(…)`, `context.WithCancel(…)`); fix "Assign the result to s"
  when the first argument is a variable. The dropped `append(xs, 1)` stays the compiler's error, which now carries the same fix "Assign the result to xs":
  a warning on the same range as an error is hidden by the platform together with its fixes (seen live).
- `GoSelfAssignment` (`x = x`, `a, b = a, b`; fix "Remove self-assignment"), `GoDeferInLoop` (`defer` inside `for`: runs at function exit, not per iteration).

## [0.2.42] - 2026-10-03

### Added — Concurrency checks
- `GoLockNotReleased`: `mu.Lock()` / `RLock()` of `sync.Mutex` / `RWMutex` with a path to `return` without the matching unlock. Quiet when a `defer mu.Unlock()`
  exists anywhere (re-acquire after a temporary unlock, net/http `Server.Close`), when the unlock is under a bare flag (`if held`), when the function's first
  lock operation is an Unlock (called with the lock held, x/term), in functions named like `lock…` / `…Locked`, and when the mutex is passed to a call.
- `GoSendAfterClose` (send on a channel after `close` on the same path), `GoWaitGroupAddInGoroutine` (`wg.Add` inside the `go func`; fix "Move Add before go").
- `GoCopyLocks` (vet `copylocks`): value receivers, parameters, assignments, returns, range values and call arguments that copy a lock; a lock is a type
  with `Lock()` and `Unlock()` without parameters (vet's shape: `internal/gate`'s `Unlock(bool)` is not one). Fix "Use a pointer receiver".
- `GoLoopClosure` (loop variable captured by `go` / `defer` literal, only for modules with `go` < 1.22) and `GoTestingGoroutine` (vet `testinggoroutine`: `t.Fatal` from a non-test goroutine).

## [0.2.41] - 2026-10-03

### Added — Resource checks
- `GoBodyNotClosed`: `resp, err := http.Get/Post/Do(…)` with a path where `resp.Body` is neither closed nor handed over; fix "Add defer resp.Body.Close()".
- `GoRowsNotClosed`: the same for `*sql.Rows` (`Query`, `QueryContext`); fix "Add defer rows.Close()".
- `GoLostCancel` (vet `lostcancel`): the cancel function of `context.WithCancel` / `WithTimeout` / `WithDeadline` not called on all paths.
- `GoContextNotPropagated` (contextcheck): `context.Background()` / `TODO()` passed while a local context or a parameter of an enclosing function is in
  scope (fix "Use ctx"). A parameter of the innermost function stays `GoContextPlacement`'s report: both on one range were a duplicate (seen live).

## [0.2.40] - 2026-10-03

### Added — Dead stores and dead code
- `GoIneffectualAssignment` (ineffassign): a value written and overwritten or never read on any path; fix "Remove assignment to 'x'". Quiet in generated
  files and on swaps (`a, b = b, a`).
- `GoUnreachableCode` (vet `unreachable`): the first statement of each run the control-flow graph cannot reach (after return, panic, goto, an endless `for`,
  a switch whose clauses all end); fix "Delete unreachable code". After `os.Exit` / `log.Fatal` it stays quiet, like vet: the compiler still wants the `return` there.

## [0.2.39] - 2026-10-03

### Added — Nil flow
- `GoNilDereference` (nilness): a field, method, index or `*p` on a value that is nil on every path (after `p == nil` without exit, after `var p *T`);
  operands of `unsafe.Sizeof` / `Alignof` / `Offsetof` are not evaluated and stay quiet.
- `GoImpossibleNilCheck` (weak warning): `x == nil` / `x != nil` whose answer is known on every path.
- `GoNilValueNilError` (nilnil, weak warning, **off by default**): `return nil, nil` in a `(T, error)` function with a nilable T, unless the doc comment says so
  (GOROOT has 195 such returns: an opt-in style rule).

## [0.2.38] - 2026-10-03

### Added — Error flow
- `GoErrorOverwritten`: an error result overwritten before it is checked (`err = f(); err = g()`); `err = error(nil)` is a reset, not a result.
- `GoWrongErrorChecked`: `if err != nil` right after `v, err2 := f()` checks the old error.
- `GoNilErrorReturn` (nilerr): `return nil` inside `if err != nil`; `GoErrNilReturned` (weak warning, its inverse): `return …, err` where `err` is known nil
  (fix "Return nil").
- `GoDeferBeforeErrorCheck`: `defer f.Close()` before the error of `f, err := os.Open(…)` is checked; fix "Move defer after the error check".
- `GoShadowedError`: `err :=` in an inner block shadows an outer `err` read after the block while the inner one is only compared with nil and the
  branch does not leave the function.
- `GoResultUsedBeforeErrorCheck` (weak warning): a pointer or interface result of `v, err := f()` used before `err` is read; quiet for slices / maps (partial
  output is an idiom), for the io.Reader methods and when the result itself is nil-checked.

## [0.2.37] - 2026-10-03

### Added — Data-flow framework (`semantic.flow`, go-psi-semantic)
- `GoControlFlow.of(function)`: a control-flow graph per function body or literal (statement, condition, range, case and comm nodes; `defer`, `panic`,
  `os.Exit`-like terminators, `goto` and labels; true/false edges) with variable accesses (read, write, define, compound, zero value); null when it gives up.
- Analyses on the generic worklist solver (`GoDataflowSolver`): liveness (`GoLiveness`: read / overwritten / returned after a write), reaching definitions,
  nilness (`GoNilness`: nil / not nil / unknown per access, refined by `== nil` branches). Cached per body by `GoBodyCache`; public API in `api/go-psi-semantic.api`.
- `:go-psi-ide:corpusTest` runs every flow inspection over GOROOT/src: 0 crashes, the counts in `testData/metrics/goroot-src-flow.json` may only go down.

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

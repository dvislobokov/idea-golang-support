# Plan: native Go PSI for the IntelliJ Platform

Goal: a complete, LSP-free implementation of Go language support (lexer, parser, PSI, stubs,
indices, project model, type system, resolve, IDE features) that can also be consumed as a
library by other plugins and tools. Quality bar: GoLand-grade correctness on real-world code,
measured by corpus tests rather than by hand-picked samples.

## 1. Architectural decisions

| Decision | Choice | Why |
|---|---|---|
| Language | Kotlin (JDK 21 toolchain), generated Java from Grammar-Kit/JFlex | Platform standard; Kotlin 2.x mandatory for 2025.1+ |
| Lexer | JFlex with ASI done in the lexer (`MAYBE_SEMICOLON` state) | Proven by GoLand and the old plugin; keeps the parser context-free |
| Parser | Grammar-Kit BNF + Kotlin `GoParserUtil` external rules that port go/parser's `exprLev`, `extractName`, `parseArrayFieldOrTypeInstance` logic | GoLand still uses Grammar-Kit in 2025; generated PSI/visitor/stub glue for ~150 node kinds; external rules handle the two real ambiguities |
| PSI | Generated interfaces + hand-written mixins (`mixin=`), light stubs for everything outside function bodies | Same stub policy as GoLand: `shouldCreateStub` = not inside a block |
| Semantic model | A go/types port that lives next to PSI: `GoType` sealed hierarchy (not PSI), `Scope`/`Object` model, untyped constants, method sets, generics inference | The old plugin failed precisely because it had no real type model |
| Caching | `CachedValuesManager` with language-scoped and out-of-code-block modification trackers; `ResolveCache` for references | Avoid "any edit flushes everything" |
| Project model | GOROOT SDK + go.mod/go.work/vendor resolved from GOMODCACHE by a pure MVS port; `go env -json` and `go list -m -json all` as authoritative fallback when a toolchain exists | Works without the `go` binary; matches `go` exactly when it is present |
| Target platform | IC 2026.1 (branch 261), `sinceBuild = 261`, no `untilBuild` | Matches the installed IDEA 2026.1.4 for live checks |
| Coexistence | `<incompatible-with>org.jetbrains.plugins.go</incompatible-with>` | Both register `.go`; the plugin is for IDEs without the bundled Go plugin |
| Modules | `go-psi-core` (lexer/parser/PSI/stubs/indices), `go-psi-semantic` (types/resolve/project model), `go-psi-ide` (editor features), `plugin` (assembly) | Library reuse: consumers depend on `core` or `core+semantic` without IDE features |
| Grammar scope | Go 1.27 full syntax (toolchain and GOROOT corpus are 1.27.1); generic methods and other 1.26/1.27 forms are first-class, with a language-version check in the semantic layer | Spec and local toolchain are 1.27 |

Alternatives considered and rejected:

- Hand-written recursive-descent port of go/parser on `PsiBuilder`: best fidelity and recovery
  control, but ~150 PSI interface/impl pairs plus visitor and stub glue by hand. Revisit only if
  the BNF becomes unmanageable for generics; the external-rule approach keeps the hard logic in
  Kotlin anyway.
- New KMP `com.intellij.platform.syntax` Grammar-Kit target (`parser-api="syntax"`): needed only
  for frontend-side parsing in split mode; the generator is on Grammar-Kit master and not in a
  released Gradle plugin. Keep `core` free of non-core platform APIs so migration stays possible.
- Driving everything from `go list -json -deps` like gopls: simple, but requires the toolchain
  and a process per change. Used only as a fallback.

## 1a. Destination

go-psi is developed and shipped standalone for now. A possible later move into the user's
other plugin (`idea-golang-support`) is out of scope of this plan; the analysis and a draft
transplant plan are kept in `docs/MIGRATION-idea-golang-support.md` for reference only. The
API feedback below still applies to go-psi as a library.

## 2. Module layout

```
go-psi/
  build.gradle.kts, settings.gradle.kts, gradle.properties, gradle/libs.versions.toml
  go-psi-core/        lexer (go.flex), grammar (go.bnf), GoParserUtil, element types,
                      PSI mixins, stubs, stub + file-based indices, GoFile, GoLanguage, file types
  go-psi-semantic/    types (GoType hierarchy, Universe), scopes, resolve, inference,
                      project model (SDK, modules, packages, build constraints), go.mod PSI
  go-psi-ide/         highlighting, annotator, completion, navigation, find usages, rename,
                      structure view, folding, formatter, inspections, documentation, intentions
  plugin/             plugin.xml, icons, packaging; depends on the three modules
  tools/astdump/      Go program that dumps go/ast as S-expressions for PSI diff tests
  testData/           parser/, lexer/, resolve/, types/, completion/, highlighting/, stubs/
  docs/               ANALYSIS.md, PLAN.md, GRAMMAR.md (ambiguity notes), TESTING.md
```

Each module applies `org.jetbrains.intellij.platform.module`; `plugin` applies
`org.jetbrains.intellij.platform`. Published Maven coordinates: `io.github.golangsupport:go-psi-core`,
`io.github.golangsupport:go-psi-semantic`, `io.github.golangsupport:go-psi-ide`. Internal classes are annotated
`@ApiStatus.Internal`; public API lives in `io.github.golangsupport.lang.psi`, `...lang.stubs`,
`...semantic.api`, `...project.api`.

## 3. Phases

Each phase ends with: all its tests green, corpus gate passing, a live check in `runIde`, and a
short entry in `docs/CHANGELOG.md`.

### Phase 0: bootstrap (1 session)
- Gradle multi-module build with IPGP 2.19.0, Grammar-Kit subplugin, Kotlin 2.4.x, JDK 21.
- `GoLanguage`, `GoFileType`, empty parser definition, plugin.xml with `incompatible-with`.
- Test scaffolding: `GoParsingTestCase`, `GoLexerTestCase`, `GoCodeInsightTestBase`, corpus
  test runner (separate Gradle task `corpusTest`, not part of `test`).
- `runIde` sandbox with PsiViewer plugin; GitHub Actions: build + test + `verifyPlugin`.
- Exit: `./gradlew build` green, IDE starts with an empty Go file type.

### Phase 1: lexer (1 session)
- `go.flex`: all tokens, ASI state machine, numeric literal forms (`0b`, `0o`, `0x` floats,
  `_` separators, imaginary), rune/string escapes, raw strings, Unicode identifiers, `~`, `&^=`,
  `...`, block comment containing newline => semicolon.
- Syntax highlighter + colour settings page.
- Tests: `LexerTestCase` goldens; corpus gate "no BAD_CHARACTER on GOROOT/src"; token-stream
  diff against `go/scanner` output from `tools/astdump -tokens`.

### Phase 2: parser and PSI (2-3 sessions)
- `go.bnf` for the full Go 1.27 grammar; expression precedence via `extends`; statements,
  declarations, types, generics (type params, constraints with `~` and `|`, instantiation),
  range-over-func, labels, select, type switches.
- `GoParserUtil` external rules: `exprLev` counter for composite literals, `typeParamsOrArray`
  (port of `parseTypeSpec` + `extractName`), `arrayFieldOrTypeInstance`, parameter-list name/type
  propagation, index-vs-instantiation.
- Error recovery: `pin`/`recoverWhile` at declaration, statement and expression-list levels;
  golden tests for incomplete code.
- Comment binding (doc comments to declarations, `//go:build`, `//go:embed` directives).
- Tests: `ParsingTestCase` goldens (port of `go/parser/short_test.go` valid+invalid lists,
  `GOROOT/src/go/parser/testdata`, `GOROOT/test/syntax`); corpus gate: zero error elements on
  GOROOT/src, golang.org/x, google.golang.org; PSI-vs-go/ast structural diff on GOROOT/src;
  mutation test (drop random tokens, assert no exceptions and bounded error spans);
  parse-time budget per MB.

### Phase 3: stubs and indices (1-2 sessions)
- Light stubs for package clause, imports, funcs, methods, type specs (+ type params), type
  nodes under specs and signatures, interface method specs, consts, vars, fields, params,
  receivers, labels. Stubs store enough to compute signatures without AST.
- Stub indices: packages, functions, methods by receiver, types, aliases, all public/private
  names, method fingerprints (for implements search). File-based indices: file imports,
  import-path prefix, build tags, go.mod files.
- Tests: stub-vs-AST equality (`StubTreeBuilder` both ways), index versioning test, AST-loading
  assertions (`AstLoadingFilter`) in resolve tests, index build time on the corpus.

### Phase 4: project model (2 sessions)
Note (from the migration plan): define `project.api` interfaces (`GoToolchainInfo`: GOROOT,
version, GOPATH, GOMODCACHE, GOOS/GOARCH, tags; `GoModuleGraph`; package model) and ship a
default implementation here; a host plugin could implement them with its own `cli`, `mod`
and `settings` packages. Library roots for GOROOT and the module cache are a decision to take
explicitly: the destination plugin avoided them for indexing cost.
- Go SDK: detect from `GOROOT`, `go env`, PATH, `$GOROOT/VERSION`; `AdditionalLibraryRootsProvider`
  for `$GOROOT/src` and module cache roots; `SdkType` only where IDEA requires it.
- go.mod / go.work / go.sum PSI (small Grammar-Kit grammar), `vendor/modules.txt` reader.
- Module graph: pure MVS over GOMODCACHE with pruning, `replace`/`exclude`, workspace mode,
  vendor mode; fallback `go list -m -json all`; `go mod download` suggestion when modules are
  missing.
- Package model: directory -> packages, build constraint evaluation (`//go:build`, `+build`,
  filename suffixes, GOOS/GOARCH/cgo/custom tags), `_test` packages, `internal` visibility,
  import path computation, `C` pseudo package.
- Tests: fixture projects under `testData/project` (single module, workspace, vendor, replace to
  local path, nested modules); MVS results compared with `go list -m all` output recorded as
  goldens; build-constraint table tests.

### Phase 5: type system and resolve (3-4 sessions)
- `GoType` hierarchy + Universe (builtins from a bundled `builtin.go` plus a typed table).
- Scopes and objects: file scope with imports, package scope across files, block scopes with
  declaration-before-use; labels.
- Name resolution: identifiers, qualified names, selectors (field/method via
  `LookupFieldOrMethod` BFS, embedded promotion, pointer auto-deref), struct literal keys,
  interface embedding, type-switch bindings, dot imports, `init`/`main` rules.
- Expression typing: untyped constants with `go/constant`-like representation and defaulting,
  iota, conversions, builtins, calls incl. multi-value, composite literals, func literals,
  channel ops, range (all five kinds incl. iterators), method values/expressions.
- Generics: instantiation, substitution, type sets, core/common underlying type, inference by
  unification (typed args first, constraint inference, untyped defaulting).
- Predicates: identical, assignable, convertible, comparable, implements, method sets.
- Caching: per-expression cached types keyed on out-of-block modification tracker for
  package-level declarations and per-file tracker for bodies; `ResolveCache` for references;
  recursion guards.
- Tests: resolve markers (`/*ref*/`, `/*def*/`), type goldens (`x /*T: []int*/`), port of
  `internal/types/testdata` harness (expected `/* ERROR */` substrings, start with `check/` and
  `spec/`), corpus gate: zero unresolved identifiers on GOROOT/src excluding cgo and known
  exclusions, with the unresolved count tracked as a metric that may only decrease.

### Phase 6: IDE features (3 sessions)
- Annotator for syntax-level errors, inspections: unresolved reference, unused import/var,
  type mismatch in assignment/call/return, missing return, duplicate declarations.
- Completion: scope-aware identifiers, members after `.`, keywords, types in type positions,
  unimported packages via index, struct literal keys, auto-import on insert.
- Navigation: go to declaration/type declaration/implementation, Go to Symbol/Class, find
  usages with narrowed use scope, highlight usages, rename (locals, package-level, fields,
  methods with interface propagation), structure view, breadcrumbs, folding, brace matching,
  commenter, quote handler, parameter info, documentation (`psiTargetProvider`), line markers
  for implementations.
- Formatter: gofmt-compatible `FormattingModelBuilder` (tabs, alignment of comments and struct
  fields), verified byte-for-byte against gofmt on GOROOT/src; optional `gofmt` external
  formatter when a toolchain exists.
- Tests: fixture-based tests per feature; gopls marker tests ported for definition, hover,
  completion as the conformance oracle.

### Phase 7: quality and library packaging (ongoing)
- Benchmarks (`Benchmark.newBenchmark`): parse, stub build, index, resolve-all-file on a large
  file, completion latency; thresholds in CI.
- Robustness: fuzzing the lexer/parser with random token deletion; `verifyPlugin` against IC
  2026.1 and 2026.2.
- Library: publish `core`/`semantic` to Maven local, a sample consumer plugin in
  `samples/consumer-plugin` that depends on the plugin id and uses the public API, API
  compatibility check (binary-compatibility-validator).
- Documentation: `docs/API.md` for library consumers.

### API feedback from the sample consumer
- `GoNamedElement.docComment`/`docText` accessors (doc comments are bound inside declarations).
- Language id is `"Go"`; document it in API.md and add a startup check that logs consumers'
  descriptors using `language="go"`.
- `@JvmOverloads` on Kotlin default-argument API methods; keep `$default` synthetics out.
- Dumb-mode behaviour of `GoSemanticService` must be covered by tests, not reasoning.
- Maven-local publication of `core`/`semantic` and binary-compatibility-validator are still open.

### Future directions (planned separately)
- Declarative lint rules in YAML with type-aware constraints and a ruleguard importer:
  `docs/RULES.md`.
- ML assistance (completion ranking, name suggestion, inspection hints): `docs/ML.md`.
- Feature roadmap against LSP 3.17, gopls and GoLand, with waves and open decisions:
  `docs/FEATURES.md`.

## 4. Testing strategy (summary)

| Layer | Fast tests (`./gradlew test`) | Slow gates (`./gradlew corpusTest`, nightly) |
|---|---|---|
| Lexer | `LexerTestCase` goldens | Token diff vs `go/scanner` on GOROOT/src |
| Parser | `ParsingTestCase` goldens, short_test port, recovery goldens | Zero error elements on 45k files, go/ast diff, mutation fuzz, parse time |
| Stubs | Stub-vs-AST equality, serialization round-trip | Index build time |
| Project | Fixture projects, MVS goldens | Compare with `go list -m all` on real modules |
| Types/resolve | Marker tests, type goldens, types testdata subset | Unresolved-count metric on GOROOT/src and x/tools |
| IDE | Fixture tests per feature, gopls marker subset | Benchmarks with thresholds |

Rules: every bug fix adds a regression test; corpus metrics are stored in
`testData/metrics/*.json` and may not regress; AST loading is asserted off in resolve tests.

## 5. Live verification

1. `./gradlew :plugin:runIde` starts IC 2026.1 in a sandbox with PsiViewer preinstalled.
2. Open `%USERPROFILE%/go/pkg/mod/golang.org/x/tools@<ver>` (copy to a scratch folder)
   and the `go-psi` fixture projects. Checklist per phase in `docs/TESTING.md`: highlighting,
   PSI Viewer tree matches expectations, indexing time, Go to Declaration across packages and
   into GOROOT, completion after `.`, Find Usages, rename, no exceptions in `idea.log`.
3. `./gradlew :plugin:buildPlugin` then install the ZIP into the installed IDEA 2026.1.4
   (Settings > Plugins > Install from disk) for a real-world session.
4. `./gradlew :plugin:verifyPlugin` for API compatibility.
5. Performance: open `kubernetes`-sized repo (clone to scratch), measure indexing and
   first-highlighting time with the built-in `Help > Diagnostic Tools`.

## 6. Risks

- Generics inference is the largest single piece; ship resolve without inference first
  (explicit instantiation), add inference incrementally against `types/testdata`.
- gofmt-compatible formatting is a deep rabbit hole; a correct formatter is a Phase 6 goal, with
  the external `gofmt` as a bridge.
- cgo: `import "C"` members resolve to a synthetic package with unknown types; no C parsing.
- Grammar-Kit method mixins are not supported from Gradle; use `mixin=` classes only.
- Go 1.27 generic methods change method-set semantics; keep the grammar extension behind a
  language-version switch.

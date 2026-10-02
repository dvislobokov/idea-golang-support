# Analysis: how existing Go IDE support is built

Research date: 2026-10-01. Local environment: GoLand 2025.1.3 (build 251.26927), IntelliJ IDEA
2026.1.4 (build 261.26222), Go 1.24.7, JDK 21, Gradle 9.7.1 (wrapper cache).

## 1. GoLand (closed source, inspected from the installed distribution)

Inspected `plugins/go-plugin/lib/*.jar` of GoLand 2025.1.3: class names, package layout and
`META-INF/*.xml` descriptors only. No decompilation.

### 1.1 Technology choices that are still current in 2025

- **Lexer: JFlex** (`com.goide.lexer._GoLexer` has `ZZ_*` tables, `FlexLexer`).
- **Parser: Grammar-Kit** (`com.goide.parser.GoParser` references
  `GeneratedParserUtilBase`, `LightPsiParser`; `GoParserUtil$MyBuilder` is a custom builder with
  parsing-mode state; `CaseWhitespacesAndCommentsBinder` binds comments to case clauses).
- **PSI: generated interfaces + hand-written mixins** (`com.goide.psi` has ~150 interfaces,
  `com.goide.psi.impl` ~200 classes).
- **Stubs for everything outside function bodies**: `GoFileStub`, `GoPackageClauseStub`,
  `GoImportSpecStub`, `GoFunctionDeclarationStub`, `GoMethodDeclarationStub`, `GoTypeSpecStub`,
  `GoTypeStub`, `GoMethodSpecStub`, `GoConstSpecStub`/`GoConstDefinitionStub`,
  `GoVarSpecStub`/`GoVarDefinitionStub`, `GoFieldDeclarationStub`/`GoFieldDefinitionStub`,
  `GoAnonymousFieldDefinitionStub`, `GoParameterDeclarationStub`/`GoParamDefinitionStub`,
  `GoReceiverStub`, `GoLabelDefinitionStub`, `GoTypeParamDefinitionStub`,
  `GoConstraintTermStub`, `GoTypeReferenceExpressionStub`. Stubs carry text (`StubWithText`,
  `TextHolder`) so signatures and types are available without loading the AST.

### 1.2 Indices (13 stub indices, 8 file-based indices)

Stub indices: `GoAllPublicNamesIndex`, `GoAllPrivateNamesIndex`, `GoFunctionIndex`,
`GoMethodIndex`, `GoMethodFingerprintIndex`, `GoMethodSpecFingerprintIndex`,
`GoMethodSpecInheritanceIndex`, `GoTypeSpecInheritanceIndex`, `GoNonPackageLevelNamesIndex`,
`GoPackageLevelPublicElementsIndex`, `GoPackagesIndex`, `GoTypeAliasIndex`, `GoTypesIndex`.

File-based indices: `GoFileImportsIndex`, `GoImportPathPrefixIndex`, `GoImportPathToAliasIndex`,
`GoAliasToImportPathIndex`, `GoLinkNameIndex`, `GoBuildTagIndex`,
`GoUseScopeOptimizer$GoImportedDirectoriesIndex` (use-scope narrowing by "who imports this
directory"), `VgoModulesIndex` (go.mod discovery).

Takeaways: method lookup is keyed by receiver type; "implements" search uses method
fingerprints (`name/arity`); the imports index drives use-scope optimisation for Find Usages.

### 1.3 Semantic layer

- `com.goide.psi.impl.generics` (105 classes): `Unifier`, `GoSubstitution`, `GoTypeSet`,
  `CoreTerm`, `GoTypeInferenceSpecification`, `GoReverseTypeInferenceContext`,
  `GoSelfRecursiveSubstitution`, `instantiated/*` (66 classes of instantiated light PSI). A full
  port of the go/types inference model.
- `psi/impl/typesCompatibility` (18 classes), `psi/impl/expectedTypes` (22 classes),
  `GoTypeIdenticalCache`, `GoTypesMatcher`, `GoIdenticalTypesMatcher`, `GoExpressionEvaluator`,
  `GoIotaUtil`, `GoSizes` (unsafe.Sizeof).
- `GoResolveCache`, `GoCachedReference`, `GoReference`, `GoTypeReference`, `GoFieldNameReference`,
  `GoLabelReference`, `GoVarReference`, `GoScopeProcessor`, `GoLimitedScopeProcessor`.
- `controlflow` + `dfa` (symbolic execution, 94 classes) for nil/unreachable analysis.

### 1.4 Project model

- SDK: `GoSdk`, `GoSdkService`, `GoSdkUtil`, `GoSdkVersion`, `GoZVersionFileGist` (parses
  `$GOROOT/VERSION`), `GoBasedSdk*` for custom toolchains.
- Roots: `GoRootsProvider` EP, `GoSdkLibraryRootProvider`, `GoPathLibraryRootProvider`,
  `GoSyntheticLibrary` (AdditionalLibraryRootsProvider pattern), `GoLibrariesService`.
- Modules: `VgoModule`, `VgoWorkspace`, `VgoModulesRegistry`, `VgoRootToModule`,
  `VgoModuleInfoProvider` (+ `Optimized` impl), `GoListOutputParser` (runs `go list -m -json`),
  `VgoDependency`, workspace-model entities under `vgo/project/workspaceModel`.
- Vendoring: `GoVendorDirectoriesCollector`, `GoVendoredFileChangeTracker`.
- Build constraints: separate mini-languages `GoBuild` (`//go:build`), `GoPlusBuild`
  (`// +build`), plus `GoTag` (struct tags), `GoTime` (time layouts), `GoDebug`, `GoRegExp`,
  `cgo`, `vgo` (go.mod/go.work), `plan9_x86` (assembly), `GoFuzzCorpus`.

### 1.5 Frontend/backend split (Remote Development)

- `go-frontback.jar` (shared by thin client and backend): `GoLanguage`, `GoFileType`,
  `GoLexer`, `_GoLexer`, `GoParser`, `GoParserUtil`, `GoParserDefinition`, `GoTypes`,
  `GoCompositeElementType`, `GoTokenType`, `GoElementTypeFactory`, syntax highlighter,
  brace matcher, commenter, line indent provider, code style settings.
- `frontend-split/go-frontend.jar`: `GoFrontendParserDefinition`, `GoFrontendFile`,
  `GoFrontendFileElementType`, `GoFrontendElementTypeFactorySupplierImpl`.
- `go-plugin.jar` (backend): everything else (PSI impl, stubs, resolve, types, inspections).

Lesson: keep lexer/parser/element types in a module that depends only on
`intellij.platform.core`/`syntax` APIs so it can be loaded on the frontend later.

### 1.6 Own extension points

`com.goide.packageFactory`, `importResolver`, `importsFilter`, `sdkProvider`, `rootsProvider`,
`importPathsProvider`, `imports.weigher`, `support`, `documentation.*`,
`highlighting.errorAnnotatorSuppressor`, `sdk.targetSdkVersionProvider`, `sdk.sdkVetoer`.
These are the seams third-party plugins (Bazel, AppEngine) use. Our library should expose
similar seams: package factory, import resolver, roots provider.

## 2. go-lang-idea-plugin (open source predecessor, Apache-2.0, archived)

Repository: https://github.com/go-lang-plugin-org/go-lang-idea-plugin. See also the living
descendant `consulo/consulo-google-go` (license unclear, treat as non-reusable).

Key techniques worth porting:

- **ASI in the lexer**: JFlex state `MAYBE_SEMICOLON`; after ASI-eligible tokens a newline
  yields a synthetic `;` and is pushed back as whitespace.
- **Composite-literal ambiguity**: parser-mode stack in `PsiBuilder` user data
  (`enterMode "BLOCK?"`, `isModeOn "PAR"`, `prevIsType`) so `if x == T{}` parses as
  condition + block. go/parser's `exprLev` counter is simpler and self-restoring, we will use
  that instead.
- **Error recovery**: `pin(".*Statement")=1`, `recoverWhile` rules per statement/declaration
  level, golden tests for `*Recover.go`.
- **Stub policy**: `shouldCreateStub` = "not inside a `GoBlock`".
- **Resolve order** for unqualified names: block walk-up, receiver, parameters, file-level,
  other files of the package, imports (alias/dot/package name), builtins.
- **Test organisation**: `ParsingTestCase` goldens (`.go` + `.txt`), resolve tests with
  `/*ref*/` `/*def*/` `/*no ref*/` markers, completion tests with `<caret>`, highlighting tests
  with `<error>` markup, stub-vs-AST consistency tests, performance tests with budgets.

Known failure modes to avoid:

- No real type model (no untyped constants, no assignability): false "unresolved reference",
  no type-aware completion. This was the headline reason JetBrains rewrote it as Gogland.
- All type caches keyed on `PsiModificationTracker.MODIFICATION_COUNT`: any edit anywhere
  flushed everything. Use language-specific trackers and per-file/out-of-block trackers.
- Text-based type stubs pushed re-parsing into resolve.
- Grammar had no generics, so the grammar itself cannot be reused for Go >= 1.18.

## 3. Other native language plugins

| Plugin | Parser | What to learn |
|---|---|---|
| intellij-rust (MIT, archived 2023) | Grammar-Kit + JFlex | Light stubs, `RsResolveProcessor`, type inference with caches, resolve/perf tests, `<caret>` marker test DSL |
| intellij-erlang (Apache-2.0, active) | Grammar-Kit | Compact canonical Grammar-Kit plugin: stubs, indices, `ChooseByNameContributorEx` |
| intellij-elixir (Apache-2.0, active) | Grammar-Kit + JFlex | Large generated PSI at scale |
| intellij-scala | Hand-written `PsiBuilder` | Hand-written parsing done right, custom modification trackers |
| Kotlin plugin | Compiler's hand-written parser | Frontend/backend split (Analysis API) |
| intellij-community JSON | Grammar-Kit, `parser-api="syntax"` | New KMP `com.intellij.platform.syntax` target, `json/syntax` + `json/backend` split |
| intellij-community Properties | Hand-written | Textbook `psi-api` / `psi-impl` module separation |
| ZigBrains | Grammar-Kit + LSP for semantics | Modern IPGP multi-IDE Gradle layout (not a semantic model example) |

Rule of thumb from the ecosystem: Grammar-Kit wins when the grammar is context-free enough and
you want PSI/stub glue generated; hand-written parsers win for context-sensitive grammars
(indentation, Scala). Go is context-free with two local ambiguities (composite literals in
control headers, `[` after a type name) that are solved by external rules.

## 4. Go language and toolchain facts that shape the design

- Spec served at go.dev/ref/spec is already go1.27. Our target is **Go 1.25 syntax** first, with
  1.26 (`new(expr)`, self-referential constraints) and 1.27 (generic methods
  `func (r *R) M[T any]()`, nested selector keys in struct literals) tracked as grammar
  extensions behind tests. Local toolchain is 1.24.7, so GOROOT corpora are 1.24.
- ASI rules (spec #Semicolons) including the "block comment containing a newline" subtlety.
- go/parser mechanisms to port: `exprLev` (composite literal gating), `parseSimpleStmt` modes,
  `parseTypeSpec` + `extractName` (`type A [P]int` vs `type A[P any] ...`),
  `parseArrayFieldOrTypeInstance`, `parseParameterList` right-to-left type propagation,
  `parseIndexOrSliceOrInstance` (index vs instantiation decided later by the checker).
- go/types model to port: Universe/package/file/block scopes; objects (PkgName, Const, TypeName,
  Var kinds, Func, Label, Builtin, Nil); types (Basic incl. untyped kinds, Array, Slice, Struct,
  Pointer, Tuple, Signature, Interface with computed type sets, Map, Chan, Named with lazy
  underlying, TypeParam, Alias, Union); `LookupFieldOrMethod` BFS with depth/ambiguity;
  method sets; Identical/AssignableTo/ConvertibleTo; untyped constant defaulting and backward
  propagation; cycle detection with colouring; instantiation + unification-based inference.
- Build constraints: `//go:build`, legacy `// +build`, filename suffixes `_GOOS`, `_GOARCH`,
  `_test`, known GOOS/GOARCH tables, `unix` tag, `cgo` tag.
- Module system: go.mod directives (`module`, `go`, `toolchain`, `godebug`, `require`,
  `exclude`, `replace`, `retract`, `tool`, `ignore`), go.work, go.sum, `vendor/modules.txt`,
  GOMODCACHE layout (`<escaped path>@<ver>/`, `cache/download/.../@v/<ver>.mod`), MVS with
  graph pruning for go >= 1.17.
- gopls feature bar: hover, signature help, highlights, inlay hints, semantic tokens, folding,
  diagnostics (compile + ~60 analyzers), definition/type definition/references/implementation,
  symbols, call/type hierarchy, completion incl. unimported, formatting, rename, organize
  imports, extract/inline, fill struct, go.mod support.
- gopls depends on the `go` binary for metadata (`go list`) and module operations; parsing and
  type checking are pure. We will keep the `go` binary optional: pure go.mod/GOMODCACHE
  resolution first, `go list -m -json all` and `go env -json` as the authoritative fallback
  when a toolchain is configured.

## 5. Test corpora available locally

| Corpus | Files | Use |
|---|---|---|
| `$GOROOT/src` | 7118 `.go` | Must parse with zero error elements; gofmt-clean, so round-trip tests |
| `$GOROOT/test` | 3250 | `errorcheck` files with `// ERROR` annotations; `test/syntax` pure syntax errors |
| `$GOROOT/src/internal/types/testdata` | check/examples/fixedbugs/spec | Type-checker conformance (`/* ERROR */` annotations, `-lang` flags) |
| `$GOROOT/src/go/parser/testdata`, `short_test.go` | small | Canonical valid/invalid one-liners incl. the `type A [P*C]int` family |
| `$GOMODCACHE/golang.org/x` | 26792 | Large real-world corpus (tools, net, text, exp) |
| `$GOMODCACHE/google.golang.org` | 9681 | grpc, protobuf: generated code stress test |
| gopls `internal/test/marker/testdata` | in x/tools | Feature-level marker tests (`//@def`, `//@hover`, `//@complete`) |

## 6. Build and platform facts (verified 2026-10-01)

- IntelliJ Platform Gradle Plugin 2.19.0; Grammar-Kit via `org.jetbrains.intellij.platform.grammarkit`
  2.19.0 (standalone `org.jetbrains.grammarkit` is archived at 2023.3.0.4). Gradle 9.x, JDK 21.
- Kotlin Gradle plugin stable 2.4.20. `kotlin.stdlib.default.dependency=false`.
- IC releases: 2026.1.5 (branch 261, matches the locally installed IDEA 2026.1.4), 2026.2.3
  (branch 262). Target `sinceBuild = 261`.
- JFlex (IntelliJ fork) 1.10.18.
- New stub API (`StubRegistryExtension`, `LanguageStubDefinition`) exists since 2024.x and is
  the path to frontend-safe element types; `IStubElementType` still works and is what
  Grammar-Kit generates glue for.
- Documentation EP: `com.intellij.platform.backend.documentation.psiTargetProvider`
  (`lang.documentationProvider` is obsolete).
- Benchmarks: `com.intellij.tools.ide.metrics.benchmark.Benchmark.newBenchmark`.
- Split mode: a plugin without frontend modules loads on the backend only and all PSI-based
  features work through the thin client. No work needed for Gateway compatibility.

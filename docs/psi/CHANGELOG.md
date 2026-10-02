# Changelog

All notable progress is recorded here, newest first. Versions follow `0.0.N` until the plugin is
stable on real-world projects.

## Unreleased

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

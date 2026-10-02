# Performance backlog

Findings of a profiling pass on 2026-10-02 (JFR over `GoHighlightingPassBenchmark`, `net/http/server.go`
copy, all inspections; 3274 samples) plus code reading. Ranked by expected effect on what a user
feels: keystroke-to-daemon-finished (UI robot P1: ~480 ms, of which `check()` ~95 ms), top-level
edits (P8: ~400 ms `check()` after a sibling declaration edit) and first open. Every item must be
measured with `./gradlew benchmark` and `tools/ui-robot/perf.py` before and after, like the cache
work in `CHANGELOG.md`. Tracker rules of `CLAUDE.md` apply (never `PsiModificationTracker`).

Where the daemon time goes (share of all samples): `GeneralHighlightingPass` 12% (annotator,
platform highlight visitors), `LineMarkersPass` 9%, inspection workers (fork-join / coroutine
threads) ~15%, `InjectedGeneralHighlightingPass` 3%, `LocalInspectionsPass` 3%. Inside go-psi:
`GoResolver.cached` 13%, `GoBodyCache.cached` 15%, `GoChecker` 8%, `GoDiagnosticsCache` 8%,
`PackageScope.declarationsByName` + `GoFile.getFileStub` 6%, `DefaultGoPackageResolver` 4%,
`GoTrackers.PackageTracker.directImports` 2%, `GoImplementationLineMarkerProvider` 1.6%.

## 1. Per-body diagnostics (biggest structural win) - effort M

`GoDiagnosticsCache` caches `GoSemanticService.check(file)` on `bodyDependencies(file)` =
`forFile` (any change) + package deps, and `GoChecker.check()` walks the whole file. A keystroke in
one body re-checks every body of the file (warm, but still a full walk: ~95 ms on `server.go`).

Change: split `GoChecker` into a package-level pass (declarations, signatures, const/var specs,
duplicate declarations, unused imports) and a body pass per outermost body (`GoPsiUtil.outermostBody`)
that returns `(diagnostics, usedImportNames, usedLabels)`; store the body result in the body's
`GoBodyCache` store (depends on `GoTrackers.forBody` + package deps). `check(file)` =
package-level diagnostics + concatenation of body results; unused-import needs the union of the
per-body `usedImportNames`. The containment filter at the end of `check()` runs per body and once
over package-level diagnostics. Expected: `check()` after a body edit 95 -> 10-20 ms (one body),
`GoHighlightingPassBenchmark.afterBodyEdit` 200 -> ~120 ms. Risk: diagnostics that span bodies
(`unused-value`, `init-cycle`; missing-return is per function, fine); keep a test that
`check(file)` of the split equals the monolithic result on the GOROOT check corpus.

## 2. `PackageScope` and `directImports` read every file's stub on each package change - effort S

`GoPackageModel.scopeOf(pkg)` depends on the package tracker whose count is `closureMax` over
transitive imports: a top-level edit in any imported project package rebuilds the name map of every
importer, and the rebuild calls `GoFile.functions/methods/types/...` on all files of the package
(`getFileStub` -> stub tree deserialization, or `StubTreeLoaderImpl.build` from text when the stub
is not yet indexed: 155 samples). `PackageTracker.directImports` does the same walk over
`file.imports` (73 samples).

Change:
- The name map depends only on the package's own stamp (`PackageTracker.own` + file-set stamp),
  not on the closure: declarations by name do not change when an import changes. Keep the closure
  dependency for values that carry types.
- Cache the per-file top-level name list (`name -> stub elements`) on the file with the file's
  out-of-block tracker, and build `declarationsByName` by merging per-file lists: after an edit in
  one file only that file's stub is re-read.
- `directImports`: take the import paths from `GoFileImportsIndex` (file-based, lexer-only,
  already exists: key = import path -> files) instead of loading stubs of every file; or add the
  reverse projection (file -> import paths) as the index value. No PSI is needed.

Expected: P8 `check()` after a sibling declaration edit 400 -> ~250 ms; fewer stub loads on
first highlighting of a package.

## 3. `importPathOf(dir)` is recomputed on every selector resolve - effort S

`DefaultGoPackageResolver.importPathOf` (called from `GoResolver.resolveSelector`, `computeFieldKey`,
`GoTypeBuilder.structOf` through `GoPackageModel.packagePathOf`) runs `LocalFileSystem.findFileByNioFile`
(`findDir`), `isAncestor`, `getRelativePath`, `GoModuleCacheLayout.locate` and a module-graph
lookup per call; `textOf`/`isPackageDir` show up next to it (4% together).

Change: cache `importPathOf` per directory `VirtualFile` (`CachedValue` on the directory, deps:
toolchain tracker, `GoProjectModelTracker`, `VFS_STRUCTURE_MODIFICATIONS`), and cache the GOROOT
`src` `VirtualFile` per toolchain. Expected: resolve warm -5-10%, cold more.

## 4. Implementation line markers: existence check per pass - effort S

`GoImplementationLineMarkerProvider.collectSlowLineMarkers` runs the stub-index fingerprint
queries and `implements` for every type spec, method spec and method of the file on every slow
pass (1.6% here, more in files with many types). Change: cache the boolean "has implementations /
implements something" per owner with `packageDependencies(owner)` + library tracker + roots
(project-content scope for implementations, all scope for interfaces); invalidate on the
file-set stamp. The popup targets stay lazy. Expected: `LineMarkersPass` share 9% -> ~3%.

## 5. Package scope lookups through the stub index instead of a per-package map - effort M

`PackageScope.lookup(name)` builds a `Map<String, List<GoNamedElement>>` over all files of the
package and keeps PSI (stub) elements alive. For large packages (`net/http`: 33 files, `go/types`)
the map is the dominant retained structure after a `check()` (61 MB for `net/http`). Alternative:
resolve a package-level name through `GoAllPublicNamesIndex`/`GoAllPrivateNamesIndex` with a
directory scope (`GlobalSearchScopes.directoryScope`), which is what `GoImplementations` already
does for types; keep only a small LRU per package. Trade-off: index queries are slower per
lookup (~5-20 us) than a map hit; measure with `GoResolveBenchmark` before deciding. Also
reduces item 2.

**Measured 2026-10-02, rejected** (after the item-2 merge). Prototype: library scopes (not
project, in an indexed library root, not dumb) answered `lookup` through
`GoAllPublicNamesIndex`/`GoAllPrivateNamesIndex` and `methodsOf` through `GoMethodIndex`, both with
`GlobalSearchScope.filesScope(package files)`, methods filtered out of the all-names results,
sorted to the map order (file, then function/type/var/const), a 256-entry memo per scope;
`GoTypeBuilder.methodsOf` read a lazy alias list instead of `allDeclarations()`; dumb mode used the
map (`GoDumbModeTest` requires identical answers in dumb mode, so the index path cannot simply let
`IndexNotReadyException` propagate). Lookups were identical to the map over `fmt`, `strings`,
`net/http`, `go/types`, `os` (every name, `init`, missing names, `methodsOf` for every name).
Saved as WIP commit `b9b86ace` on the agent's worktree branch (not merged).

- Where the memory is: `GoCacheMemoryBenchmark` was extended with a class histogram diff and a
  measurement after clearing softly reachable objects (`GCUtil.tryGcSoftlyReachableObjects`).
  Status quo, GOROOT not a library root (the default in tests): retained 68.5 / 67.0 MB, of which
  **0 PackageScope and 0 FileDeclarations survive soft-reference clearing** (strongly retained
  26.1 / 25.0 MB: expanded lazy bodies' AST 7.9 MB, body stores, types). Every package scope and
  per-file map is only softly reachable (cached on `PsiDirectory`/`GoFile`, which the platform holds
  through soft references), so the JVM drops them under memory pressure. During `check` of
  net/http: 59 of the 193 packages of the import closure get a directory `PackageScope`; 210
  `PackageScope` objects in total (one per checked/looked-up file through `scopeOf(file)`, each with
  its own merged map) and 677 `FileDeclarations`. The scope objects themselves are small
  (`goScope` 0.12 MB); the bulk is stub trees, stub-backed PSI, maps and strings.
- Before/after with GOROOT indexed as a library (as in the IDE; `-Dgopsi.scope.indexLookups` A/B,
  two runs each, shared loaded machine): retained 29.9 / 60.8 MB (map) vs 51.1 / 51.3 MB (index);
  strongly retained -1.2 / 28.5 vs 28.4 / 28.5 MB; histogram total 62.8 -> 53.5 MB in the
  comparable run (collections -4.4, Object[] -2.0, go PSI -0.4, go stubs -0.5 MB; index caches of
  the platform grow). The gain is at most ~15% of softly reachable memory, inside run-to-run noise,
  and 0% of strongly retained memory.
- Time (same A/B): `GoResolveBenchmark.cold` 142.1 / 121.0 -> 215.2 / 166.9 ms (+45%),
  `warm` 51.2 / 54.0 -> 104.2 / 48.6 ms (one noisy run), `GoLibraryCacheBenchmark.warm`
  12.1 / 12.9 -> 17.8 / 12.0 ms. Without library roots (status quo setup): cold 166.3 / 164.5 ms,
  warm 48.8 / 185.2 ms (load spike), library warm 15.6 / 14.1 ms.
- Corpus: the corpus gates run without library roots (`GoRootsProvider` is off in tests), so the
  index path is never taken there; baseline goroot-src-resolve 36.5 s, check 59.1 s.

Decision rule (retained -15% and warm resolve within +10%) not met: no strongly retained memory
is saved and cold resolve gets slower. Better levers for memory: persisted library summaries
(`docs/LIBRARY-SUMMARIES.md`), and, cheaply, sharing one merged map per package instead of one per
`scopeOf(file)` (210 scopes after net/http's `check`: 59 per directory, 151 per file).

## 6. Completion - effort S each

- `GoCompletionLatencyBenchmark.statementWarm` 70 ms for 563 items: `GoCandidate` carries
  `tailText`/`typeText` rendered eagerly (`GoLookupElementFactory.typeText` -> `GoDocSignature.renderType`)
  for fields, methods and struct keys; render in a `LookupElementRenderer` (lazily, only for the
  visible rows), keep `valueType` for the weigher.
- `unimportedPackages`: `GoImportPaths.std` walks GOROOT once per process (ok); `modules`
  re-walks the module cache per directory key on `VFS_STRUCTURE_MODIFICATIONS`, which fires on
  every file creation anywhere. Depend on `GoProjectModelTracker` only and on the module cache
  directory's own structure stamp.
- `receiverTypes` concatenates `file.types + packageScope.allDeclarations().filterIsInstance<GoTypeSpec>()`
  per session: a `typesByName` view on `PackageScope` avoids the full scan.

## 7. Platform overhead worth knowing (no go-psi code change)

- `InjectedGeneralHighlightingPass` + `InjectedLanguageManagerImpl.processInPlaceInjectorsFor`
  3-5%: the platform probes every element for injection hosts even though no Go element is a
  `PsiLanguageInjectionHost`. When string injection (RE2, SQL, templates; `docs/FEATURES.md` section 6)
  arrives, make only `GoStringLiteral` a host and keep `isValidHost` cheap.
- `RunLineMarkerProvider` 2.3%, `TodoHighlightVisitor`, `StickyLinesPass`, `ChameleonSyntaxHighlightingPass`
  ~1% each: platform passes that run for every language.

## 8. First open: GOROOT indexing 3-5 s - effort M (already in FEATURES section 9)

Stub building parses every GOROOT file once (bodies are lazy now, `GoIndexBenchmark.stringsBytes`
50-65 ms for two packages). Options: the platform shared-indexes mechanism keyed by Go version
(`go version` string + GOROOT hash) so GOROOT stubs are downloaded/loaded instead of built; or
at least `GoFileImportsIndex`/`GoBuildTagsIndex` could be derived from the stub (they re-lex the
file) by moving the data into `GoFileStub` and dropping the two file-based indices (saves one
lexer pass per file during indexing; needs `STUB_VERSION` bump).

**Measured 2026-10-02** (details and numbers in `CHANGELOG.md`, "Indexing cost of GOROOT"): one
indexing pass over 20.4 MB of GOROOT (2086 files) costs 71-88 ms/MB single-threaded; `Stubs` is
~80% of it, `IdIndex` ~9%, `Trigram.Index` ~6%, the two header indices 0.7% (they lex only the
header, not the file; now one shared scan per `FileContent`, see `GoIndexedFileHeader`). Inside
`Stubs`: parse 65% (top-level `var` initializers, i.e. composite-literal tables, 19%; PsiBuilder
lexing 13.5%; tree building 14%; function declarations incl. `lazyBlock` 10%), stub tree walk 10%,
serialization 10%. Remaining levers, by share: (1) lazy top-level literal values (grammar, like
bodies: `GoVarSpecStub` keeps only text) ~19%; (2) `GoVarSpecStub`/`GoConstSpecStub.values` are
43% of serialized stub bytes and have no consumer outside tests (public API, `STUB_VERSION`);
(3) a `LightStubBuilder` over the LighterAST would skip AST/PSI creation (~16%), large refactor.

**Shared indexes (design note).** The platform mechanism lives in the bundled plugin
`intellij.indexing.shared.core` (`plugins/indexing-shared`, IDEA 2026.1.5 unified distribution).
Consumer side, public EPs of that plugin's main descriptor (no `@ApiStatus` marks found, but
undocumented and JetBrains-internal in practice): `com.intellij.sharedIndexLocalFinder`
(`indexing.shared.local.SharedIndexLocalFinder.findSharedIndexChunks(Project): List<Path>`),
`com.intellij.sharedIndexSuggester` (`download.SharedIndexSuggester.suggestRequests(Project)` ->
`SharedIndexSuggestion` with a URL; JDK uses `JdkSharedIndexSuggester` against
`index-cdn.jetbrains.com/v2/jdk`) and `com.intellij.sharedIndexBundled` (`BundledSharedIndexProvider`,
`productPath`/`pluginPath`: chunks shipped inside a plugin). The platform side is
`SharedIndexInfrastructure` (a `fileBasedIndexInfrastructureExtension`): a chunk is attached when its
`SharedIndexMetadata`/`SharedIndexInfrastructureVersion` (maps of file-based index, stub index and
`stubFileElementTypeVersions` versions, `baseIndexes`, OS set, `weakVersionHash`) is compatible, and
per file the shared value is used only if the file's content hash (`SharedIndexContentHashProvider`)
is in the chunk. So keying by Go version is implicit: the GOROOT bytes of a release are the key, and
`GoFileElementType.STUB_VERSION` plus every Go index version are checked by the platform; a version
bump makes old chunks unusable (files fall back to local indexing), nothing to manage by hand.
Production side: the `dump-shared-index` app starter (`DumpSharedIndexStarter`) runs a
`com.intellij.sharedIndexDumpCommand` (`DumpSharedIndexCommand<Args>`, e.g. Python's
`PyDumpRootsIndexCommand` "python-sdk" with an `IndexChunk` whose `getRootIterators()` lists the SDK
roots). A Go command would be ~100 lines: `IndexChunk(kind = "go", name = "goroot-<go version>")`
over `$GOROOT/src`, run headless once per Go release and per plugin index-version change, output
published (CDN) or bundled. Viability for a third-party plugin on 2026.1: the generator module
`intellij.indexing.shared.generator` is declared `visibility="internal"`, so a marketplace plugin
cannot register a dump command against it supportedly; the consumer EPs are reachable with
`<depends optional="true">intellij.indexing.shared.core</depends>`, but the chunk format and
metadata are not a published API and can change per IDE build (chunks are tied to the IDE's
`baseIndexes`), so chunks would have to be produced per IDE major build too. Verdict: technically
possible (local finder or bundled chunk, produced by a private build of the dump command), not
supportable as a product feature; revisit if JetBrains opens the generator API. Cheaper wins first:
items (1)-(3) above.

## 9. Persisting library type information - effort L (design only)

`GoLibraryCacheBenchmark.warm` 0.77 ms/pass vs cold type-checking of GOROOT packages on each
IDE start (P9 cold `check()` of 16k GOROOT lines ~5 s). gopls solves this with export data; the
PSI equivalent is a `FileBasedIndex` whose value is a serialized summary of exported
declarations with resolved types (`GoType` is already a plain Kotlin model with stable
`GoTypeDeclarationPointer`s). Package scopes of library packages could then be built from the
index value without touching stubs. Needs a `GoType` serializer and a decision on how pointers
survive (by `importPath + name`). Do after items 1-5; measure with `GoCacheMemoryBenchmark` and P9.

## Order

1 (per-body diagnostics), 2 (package scope stamps + imports index), 3 (importPathOf cache),
4 (line marker existence cache), 6 (completion renderer), then 5/8/9 as separate milestones.

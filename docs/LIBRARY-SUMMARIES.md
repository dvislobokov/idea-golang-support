# Library summaries: persisted type information for library packages (design)

Status: design only (PERF-BACKLOG item 9). Nothing here is implemented. Names marked *(new)* do
not exist yet; every other class or method named below exists in the tree as of `fa742e5`.

## 0. Decision in one paragraph

Persist, per library package directory and build context, a **package summary**: every
package-level declaration of the package with its resolved `GoType`, method sets, constant
values and type parameters, in a compact versioned binary form. Store it in an
**application-level `PersistentHashMap`** keyed by (directory, build context, toolchain), validated
on load by a fingerprint of the package's files and the summary hashes of its direct
dependencies (the gopls scheme). Compute it **in the background** with the existing PSI path
(`GoTypeBuilder`, `GoExpressionTyper`), so the PSI path stays the fallback and the reference for
correctness. Serve it to `GoPackageModel.scopeOf(GoPackage)`, `GoExpressionTyper.declarationType`,
`constantValueOf` and `GoTypeBuilder.typeOfDeclaration` for library packages only. A
`FileBasedIndex` (option a) cannot hold resolved types, and shared indexes (option c) solve item 8,
not this. Prerequisite: named-type identity has to move from PSI (`GoNamedType.declaration ==`)
to a stable pointer. That is a change to the public `semantic.types` API and needs the
orchestrator's approval (section 4).

## 1. Problem and numbers

What a user pays today for library code, from the measurements quoted in the brief and the latest
UI robot run (`build/ui-robot/perf-20261002-045153.json`):

| Measurement | Value | What it contains |
|---|---|---|
| `GoLibraryCacheBenchmark.warm` | 0.77 ms/pass | warm library caches, nothing to gain |
| same benchmark before library caches were split from project edits | 177.6 ms/pass | recomputing library-related types from stubs in a warm JVM (upper bound for 3 GOROOT files + 1 project file) |
| UI robot P9 `check_cold` (7 files, 16k lines) | 5191 ms total | per file: 112, 110, 340, 333, 197, 151, **3948** ms (`cmd/compile/internal/ssagen/ssa.go`) |
| P9 `check_warm` | 601 ms total | per file: 11, 16, 24, 46, 32, 36, 437 ms |
| P9 retained caches | 60.9 MB (66-74 MB in other runs) | semantic caches plus the library PSI/stubs `check()` loaded; ASTs of the 7 files: 12.7 MB |
| `GoCacheMemoryBenchmark` (`check` of `net/http`) | ~62 MB retained | own body stores plus dependencies' package-level caches |
| first open | 3-5 s | GOROOT stub indexing (item 8, not addressed here) |

Where the cold cost of library code comes from (code reading):

1. **Typing library declarations from stubs.** `GoTypeBuilder.namedType(spec)`, `functionType(f)`,
   `methodOf(m, pkgPath)` and `typeOf(node)` keep one `CachedValue` per stub PSI element, and the
   stub-backed PSI keeps the file's stub tree reachable.
2. **AST loads of library files.** Typing a package-level `var` without a type
   (`GoExpressionTyper.varType` -> `valueTypeAt(parent.expressionList, ...)`) and any constant
   value or untyped constant type (`constType` -> `constSpecSource` -> `repeatedConstSpec(spec).expressionList`,
   `constantValueOf`) reads expressions. Expressions are not stubbed (`GoVarSpecStub.values` and
   `GoConstSpecStub.values` keep only text), so the platform loads the whole AST of the declaring
   file. `http.StatusOK` loads `net/http/status.go`, and `ssa.OpAMD64ADDQ` loads `ssa/opGen.go`
   (8421 const specs).
3. **Package scopes.** `GoPackageModel.PackageScope.declarationsByName` reads the stubs of *every*
   file of every imported package and keeps PSI for all its names (PERF item 5).
4. **Not-indexed directories.** `cmd/` is not a library root by default
   (`gopsi.libraryRoots.includeCmd = false`), so the first touch of `cmd/compile/internal/ssa`
   builds stubs by parsing every file. That is why `ssagen/ssa.go` takes 76% of P9 cold.

Throwaway probe (Go 1.27.1, `go/importer` over `go list -export std` and a `go/parser` count of
`GOROOT/src`, non-test, without `cmd` and `testdata`; the programs ran from `build/sumprobe` and were
deleted afterwards):

- std: 376 packages; 13,035 package-level objects reachable through export data (11,151 exported),
  9,134 methods, 9,894 struct fields. Their `types.TypeString` text is 1.25 MB in total
  (`net/http`: 247 objects, 333 methods, 366 fields, 35 KB of text; `go/types`: 41 KB).
- Source, over all build variants: 3,176 files, 30.1 MB, 131,526 package-level declarations
  (20,332 funcs, 19,881 methods, 7,770 types, 5,098 vars of which **3,488 have no declared type**,
  78,445 consts, mostly generated `syscall`/`zerrors` tables).
- Estimated summary size with all declarations (exported and unexported) of the std packages of one
  build context: **2-4 MB on disk**, about 5-30 KB per typical package and ~100 KB for `net/http`.
  gc export files are much larger (181 MB) because they also carry inline bodies, so they are not
  a useful reference.

## 2. Prior art

- **gopls (v0.12+)** type-checks one package at a time against the *shallow export data* of its
  dependencies. Each package's export data lists only its own declarations, and objects of other
  packages are referenced by (package path, name), never embedded. Export data is kept in a
  file-based cache (`filecache`) whose key hashes the package's sources, the build configuration
  and the keys of its direct dependencies. Fields and methods are addressed with `objectpath`
  (`T.UM0.F1`). This design copies those three ideas: shallow summaries, references by pointer,
  and dependency-hashed validation.
- **GoLand** (ANALYSIS.md section 1) keeps rich stubs with text (`StubWithText`) and computes types from
  stubs into in-memory caches (`GoTypeIdenticalCache`, `GoResolveCache`). No persistent type store
  is visible in its descriptors. go-psi already matches that level today.

## 3. What a package summary contains

One summary per (package directory, `GoBuildContext`): the files `GoPackageResolver.packageOf(dir, ctx).goFiles`.
Test files are excluded because importers never see them.

| Entry | Content | Source today |
|---|---|---|
| header | package name, import path (`importPathOf`), `GoPackageKey` *(new)*, file table (name, length, mtime), direct deps (import path as written, resolved `GoPackageKey`, dep summary hash), own summary hash | `GoPackage`, `DefaultGoPackageResolver` |
| type `T` | alias flag; type parameters (name, bound `GoType`); for a defined type: the underlying `GoType` (struct fields with name, embedded flag, tag, pkgPath; interface methods and embedded types and terms, `comparableMarker`, `implicit`) and the **declared methods** (name, `pointerReceiver`, signature, declaring file index), including methods declared through aliases (`GoTypeBuilder.methodsOf` alias loop); for an alias: the aliased `GoType` | `GoTypeBuilder.namedType`, `underlyingOf`, `methodsOf`, `boundOf` |
| func `F` | `GoSignatureType` (params with names, results with names, variadic, type params) | `GoTypeBuilder.functionType` |
| var `V` | resolved type (declared, or inferred from the initializer, tuple element included) | `GoExpressionTyper.declarationType` -> `varType` |
| const `C` | type (typed or untyped kind) and `GoConstant` value (or none) | `constType`, `constantValueOf` |
| name index | sorted names -> (kind, file index, offset of the entry) | `PackageScope.declarationsByName`, `methodsByReceiver` |

Every package-level name goes in, exported or not. Unexported types are reachable through
exported API (`func New() *client`, promoted methods of unexported embedded types), and the
same-package case (a library file opened in the editor sees its siblings through the summary)
needs all of them. Method sets are not stored: `GoLookup.methodSet` and `lookupFieldOrMethod`
derive them from the declared methods and the embedded fields, as they do today.

Deliberately excluded:

- function bodies, local declarations and local types. Go forbids generic local types, and a
  package-level declaration's type can never mention a local type, so a summary never needs one.
- doc comments, positions beyond the file index, and parameter-declaration PSI. Navigation and
  documentation materialize PSI on demand (section 7).
- diagnostics, implementation relations (they stay on the `GoMethodFingerprintIndex` stub
  indices), and import lists (`GoFileImportsIndex`).
- `builtin` and `unsafe`. `GoUniverse` and the special cases in `GoTypeBuilder.builtinDeclarationType`
  and `unsafeType` keep serving them. A summary refers to universe types by name only.
- entries whose computation hit `RecursionManager` prevention. Same rule as `GoBodyCache`: a value
  computed while recursion was prevented is not stored. The entry is marked *unsummarized* and that
  name falls back to PSI.
- cgo: `C.x` is `GoUnknownType` both here and on the PSI path. The summary stores unknown, which
  gives the same result.

## 4. Identity: `GoTypeDeclarationPointer` and the model change

`CLAUDE.md` asks types to point to declarations through stable `GoTypeDeclarationPointer`s. That
class does not exist yet. Today identity is PSI:

- `GoNamedType(declaration: GoTypeSpec, ...)`: `equals` = `declaration == other.declaration && typeArgs`,
  and `GoTypePredicates.identical` (line 34) and `GoUnifier` (line 46) compare `declaration`.
- `GoTypeParamType(declaration: GoTypeParamDefinition, index, source, generation)`: `equals` =
  declaration + generation.
- `GoField.declaration`, `GoMethod.declaration`, `GoParam.declaration`: eager PSI (nullable).

A type deserialized from a summary has no PSI unless we load the stubs. That would defeat the
purpose. Proposed *(new)*, in `semantic.types`:

```kotlin
/** Where a package lives, independent of the VFS session: survives restarts and stub/AST switches. */
data class GoPackageKey(val root: Root, val path: String) {   // GOROOT_SRC + "net/http",
    enum class Root { GOROOT_SRC, MODCACHE, ABSOLUTE }       // MODCACHE + "golang.org/x/tools@v0.30.0/go/ast/inspector"
}
data class GoTypeDeclarationPointer(
    val pkg: GoPackageKey,
    val file: String,          // declaring file name: disambiguates the same name in two build variants
    val name: String,
    val receiver: String?,     // receiver base type for methods (owner of receiver type params)
    val kind: Kind,            // TYPE, FUNC, METHOD, VAR, CONST
)
```

- `GoNamedType` gets an identity `key: Any`: the pointer for package-level types and the
  `GoTypeSpec` itself for local types. `equals`/`hashCode` use `key + typeArgs`. `declaration`
  stays a non-null `GoTypeSpec` getter but becomes lazy (resolved through the pointer on first
  access). The public constructor `(GoTypeSpec, List, GoTypeSource, GoNamedType?)` stays and
  derives the key. An internal constructor takes `(pointer, name, typeParamCount, source)`. Call
  sites that read `.declaration` for data rather than navigation must stop doing so:
  `GoChecker` line 177 (`isBuiltinDeclaration`, becomes a pointer check), lines 1997/2006
  (`typeParams(xt.declaration.typeParameters)`, which needs a `typeParamCount` / `typeParams` on
  the type served by `GoTypeSource`), `GoUnifier` line 46, `GoTypePredicates` line 34,
  `GoSnippets` line 84 and `GoWrapConversionFix` line 123. Navigation (`GoTypeDeclarationProvider`
  line 43) keeps using the lazy getter.
- `GoTypeParamType` key = (owner pointer, index, generation). Type parameters always belong to a
  package-level type or function (methods use the receiver type's parameters, see
  `GoScopes.receiverTypeParamOf`), so every type parameter has a pointer owner.
- `GoField`/`GoMethod`/`GoParam`: `declaration` becomes a getter over a `GoDeclarationRef` *(new,
  internal)*: eager for PSI-built values, and for summary values a pointer plus a path in the
  style of `objectpath` (owner type pointer + field index path through nested struct types, or
  method name). `GoResolver.Result.Selection.element` must then be computed lazily from the
  selection instead of in `toResult`, so that typing `a.b.c` does not materialize PSI for every
  link of the chain.
- `GoPackageKey.of(dir)` is computed from the toolchain's GOROOT and GOMODCACHE prefixes. It has to
  be cached per directory, which is exactly PERF item 3 (`importPathOf` cache). Do item 3 first.

Identity of PSI-built and summary-built types for the same declaration is then equal by
construction. That matters for a library file open in the editor: its own declarations are typed
from PSI, its siblings' from the summary.

## 5. Serialization format

One value per package: a `ByteArray` written with `DataOutputStream` and the platform varints
(`DataInputOutputUtil.writeINT/writeLONG`, `IOUtil.writeUTF`), behind a
`GoSummaryExternalizer : DataExternalizer<GoPackageSummary>` *(new)*. Layout:

```
u8 format version (SUMMARY_FORMAT)  | header (section 3) | string table | package table | pointer table
| name index (sorted; binary search without decoding entries) | entries
```

- **string table**: every name, import path, tag and file name once per package, referenced by
  varint index. This is the per-package interning. In memory, pointers are interned per store with
  a weak interner (`Interner.createWeakInterner()`), so the many `GoField`s that point to the same
  type share one pointer instance.
- **package table**: index 0 = self, then each `GoPackageKey` + import path referenced by any type
  in the summary, including packages reached only transitively (`var X = a.F()` returning `b.T`).
- **pointer table**: (package index, file index, name, receiver, kind) for every named type or
  type-parameter owner referenced.
- **GoType**: a tag byte, then the payload, written pre-order:

| tag | type | payload |
|---|---|---|
| 0 | `GoUnknownType` | - |
| 1 | `GoBasicType` | kind ordinal; bit 7 = `byte`/`rune` spelling (`BYTE`/`RUNE` instances) |
| 2 | `GoArrayType` | length varlong + 1 (0 = null), elem |
| 3/4 | `GoSliceType`/`GoPointerType` | elem |
| 5 | `GoMapType` | key, value |
| 6 | `GoChanType` | dir ordinal, elem |
| 7 | `GoTupleType` | n, types |
| 8 | `GoStructType` | n; per field: name, flags (embedded, has tag, foreign pkgPath), tag, pkgPath index, type |
| 9 | `GoSignatureType` | n params (name index or -1, type), n results, variadic; own type params are declared by the owning entry |
| 10 | `GoInterfaceType` | n methods (name, pkgPath, signature), n embedded types, `comparableMarker`, `implicit` |
| 11 | `GoNamedType` reference | pointer index, n type args, args |
| 12 | `GoTypeParamType` reference | owner pointer index, index |
| 13 | `GoUnionType` | n; per term: tilde, type |
| 14 | universe named | name (`error`, `comparable`, `any` map to `GoTypeBuilder.ERROR/COMPARABLE/ANY` or `GoUniverse` declarations, as the PSI path does) |

**No cycles at serialization time.** Named types and type parameters are always written as
references (tags 11 and 12) and never inlined, so each serialized `GoType` is a finite tree:
`type List struct{ next *List }` writes `struct{next *ref(List)}`. Recursion appears only when
decoding: a `GoNamedType` built from a reference decodes its underlying type lazily through its
`GoTypeSource`, inside the `RecursionManager` guard `GoNamedType.underlyingLazy` already has.

**Decoding.** `GoSummaryTypeSource : GoTypeSource` *(new)* implements `underlyingOf`,
`methodsOf`, `boundOf` and `packagePathOf` from the entries. Instantiated types reuse
`GoNamedType.instantiate`, and the substitution of type arguments into the underlying type and
methods runs as in `GoTypeBuilder.underlyingOf` (same code moved to a shared helper). Decoded
`GoNamedType` instances (uninstantiated) are interned per pointer in the package's decoded cache,
so `===` fast paths (`substitute` returning `this`) keep working.

**Determinism.** Two computations must give byte-identical output: methods in `PackageScope.methodsOf`
order (file order, then source order), struct fields and interface members in source order,
`GoInterfaceType.allMethods` never serialized (derived). The hash of the bytes is the summary
hash that dependents record.

## 6. Where it lives: options

| | (a) `FileBasedIndex`, value = file summary | (b) persistent map per package *(recommended)* | (c) platform shared indexes for GOROOT |
|---|---|---|---|
| Can hold **resolved** types | **No.** An indexer sees one `FileContent` and must be a pure function of it. It may not read other files, PSI or indices. Resolved types depend on sibling files, imports and the build context. What a per-file index can hold (unresolved syntax) is what stubs already hold. | Yes. Computed by the existing PSI path with full project context. | No, same per-file index contract. It ships prebuilt *index* data. |
| Invalidation | automatic per file content, but not on dependency change | explicit: key (dir, context, toolchain) + header validation (file fingerprint, deps' summary hashes) | content hashes |
| Dumb mode | unavailable while indexing | readable (no index needed). PSI materialization uses per-file stubs. | n/a |
| Build tags per GOOS/GOARCH | index cannot depend on context | context hash in the key, one summary per context | one chunk per context would be needed |
| Several Go versions | content-addressed, fine | toolchain version + GOROOT path in the key; GOROOT summaries shared across projects | chunk per Go version, needs generation and hosting |
| Memory | index values cached by the platform | raw bytes of the used packages + decoded entries, dropped by the library tracker | n/a |
| Fit | wrong layer | right layer | belongs to item 8 (stub building of GOROOT) |

The platform's `GistManager.newVirtualFileGist` was also considered and rejected: a gist is keyed by
one file and invalidated by that file's content only, while a summary depends on N files, the build
context and its dependencies.

**Recommendation: (b)**, as an application service `GoSummaryStore` *(new)* over
`PersistentHashMap<GoSummaryKey, ByteArray>` (`PersistentMapBuilder`), under
`PathManager.getSystemDir()/go-psi/summaries/v<SUMMARY_VERSION>-s<STUB_VERSION>/`:

- `GoSummaryKey(dir: String /* normalized absolute */, context: GoBuildContext hash, toolchain: String /* GoVersion + GOROOT */)`.
  It is app-level because GOROOT and module-cache directories are immutable (module cache
  directories carry `@version`) and shared between projects.
- **Validation on load** (cheap, no PSI): (1) the header's file table equals the current
  `packageOf(dir, ctx).goFiles` names, lengths and timestamps. This covers vendor, GOPATH and local
  replacements, which are mutable. (2) for each direct dependency, `GoPackageResolver.resolveImport(path, anyFileOfPkg)`
  gives the same `GoPackageKey`, and that dependency's current summary hash equals the recorded one.
  A module-cache package's imports resolve through the *project's* build list
  (`DefaultGoPackageResolver.graphForImporter`), so the same `x/tools@v1` can see different
  dependency versions in two projects. The dependency hash check catches that. GOROOT packages
  import only GOROOT, so their check is always positive for one toolchain.
- Not served (PSI fallback): project packages (anything `GoTrackers` treats as a project package),
  a package with an unsaved document or uncommitted PSI change in any of its files
  (`FileDocumentManager.isFileModified`), and a package whose dependency closure contains a project
  package. That last case is the module cycle `GoTrackers` already documents as a known gap.
- Corruption (`CorruptedException`, `IOException` on open) deletes the store and starts empty. A
  size cap (default 256 MB) or a format/stub version change drops the directory. Directories of
  older versions are deleted at startup.

## 7. Computation and consumers

**Computing a summary.** `GoSummaryBuilder` *(new)* walks `PackageScope` of the package and asks
the existing code for every entry: `GoTypeBuilder.typeOfDeclaration`/`underlyingOf`/`methodsOf`/`boundOf`,
`functionType`, `GoExpressionTyper.declarationType`, `constantValueOf`. This way the summary is
correct by construction, and every later fix to typing flows into it, given a version bump
(section 8). It is never computed on the request path. Computing a whole package (for example all
`syscall` constants with their ASTs) is far more work than the few names a file uses. Instead:

1. On a miss, `GoLibrarySummaries.summaryOf(pkg)` *(new, project service)* returns null. The caller
   takes the PSI path, as today, so there is no regression, and the package is queued.
2. A queue worker runs in `DumbService.runWhenSmart` + `ReadAction.nonBlocking(...).expireWith(project)`
   at low priority. It processes dependencies first (topological order over the direct imports, so
   dependency hashes exist) and writes to the store. A write action cancels the worker, which
   restarts later. After indexing, a `ProjectActivity` queues the import closure of the project's
   packages, so the next cold `check()` finds summaries even in the first session.
3. In tests and corpus gates the builder runs synchronously (`GoLibrarySummaries.computeNow(pkg)`).

**Consumers** (all behind registry key `gopsi.librarySummaries`):

- `GoPackageModel.scopeOf(pkg: GoPackage)`: for a served library package it returns a
  `SummaryPackageScope` *(new)*, and `PackageScope` becomes an internal interface with
  today's class as `PsiPackageScope`. `lookup(name)` checks existence in the name index and only then
  materializes PSI for that name: the declaring file from the entry's file index, then its `GoFile`
  stub accessors (`types`/`functions`/`vars`/`consts`/`methods`) filtered by name. That is one
  file's stub tree, never the package's, and no global stub index query, so it also works in dumb
  mode. `lookupType`, `methodsOf` and `allDeclarations` work the same way. A cheap `names(): Sequence<String>`
  plus `entry(name)` serve completion (`GoMemberCandidates` line 59, `GoScopeCandidates` line 160)
  without PSI. The lookup element takes a lazy PSI supplier.
- `GoResolver.resolveMember(spec, name)` is unchanged in shape (it calls `scopeOf(pkg).lookup`).
  Resolve results stay PSI (`Result.Member`). That costs at most one stub tree per referenced file,
  which the editor's annotator would load anyway for highlighting references.
- `GoExpressionTyper.declarationType(decl, place)`, `constantValueOf(def)`, `constType` and
  `GoTypeBuilder.typeOfDeclaration(spec)`, `functionType(f)`, `methodOf(m)`: first line
  `summaries.entryFor(decl)?.let { return it.type }`. `entryFor` computes the pointer of a
  package-level declaration from its stub (file name, name, receiver) and finds the summary of its
  directory. This removes AST loads for untyped library vars and constants, and the per-element
  `CachedValue`s on library PSI.
- `GoLookup.lookupFieldOrMethod` / `methodSet` on summary types: unchanged code. Fields and methods
  come from `GoSummaryTypeSource`, and `declaration` of a member is materialized only when resolve
  or navigation asks.

**Still PSI:** navigation (`GoTypeDeclarationProvider`, Go to Declaration into GOROOT),
documentation (doc comments), Find Usages and rename (library elements are read-only targets),
implementation markers (stub indices), the bodies of an opened library file, and everything in
project packages.

## 8. Interaction with caches, trackers and versions

```
GoSummaryStore (app, disk) --validated by--> file table, deps' summary hashes, SUMMARY_VERSION, STUB_VERSION
   v loaded by
GoLibrarySummaries (project): decoded summaries per GoPackageKey
   deps: GoTrackers.library, GoTrackers.projectModel, roots tracker, toolchain tracker
   v serves
PackageScope (library) / declarationType / typeOfDeclaration / constantValueOf
   v used by
GoBodyCache stores and package-level CachedValues of project code (unchanged deps: packageDependencies)
```

- **Library tracker.** An out-of-block edit in any library file bumps `GoTrackers.library`. That
  drops all decoded summaries (as it drops library `CachedValue`s today), and the edited package is
  not served while its document is modified. After a save, the fingerprint differs, the summary is
  invalid and gets re-queued. Values in project caches already depend on `library`, so nothing new
  is needed there.
- **Module cache or GOROOT changes.** A new module version is a new directory and therefore a new
  key. `go mod tidy` changing the build list bumps `GoProjectModelTracker`, which drops decoded
  summaries, and validation re-checks dependency resolution. A toolchain switch changes the key.
  GOROOT updated in place (same path, new `VERSION`) changes the toolchain part of the key.
  Directory moves or deletions under library roots already call `invalidateAll()`, which bumps
  `library`.
- **Versions.** `SUMMARY_VERSION` *(new)* lives in the store path and covers the format **and the
  semantics of declaration typing**. Any change to `GoTypeBuilder`/`GoExpressionTyper` that can
  change a declaration's type or constant value must bump it, or users keep stale persisted
  types. A golden guard enforces this: `GoSummaryVersionTest` *(new)* renders the summaries of a
  fixed package set (`fmt`, `io`, `net/http`, `go/types`, `slices`, `syscall` on linux/amd64)
  to `testData/summaries/golden.txt`, with `SUMMARY_VERSION` on the first line. Regenerating with
  `-Dgopsi.updateGoldens=true` fails unless the version was bumped. `STUB_VERSION` is part of the
  store path, so a stub change (which can change the computed types, e.g. version 4) drops all
  summaries. The stub indices' own versions are unaffected: summaries are not an index.
- **No `PsiModificationTracker`** anywhere, as required by `CLAUDE.md`.

## 9. Correctness strategy

`GorootSummaryCorpusTest` *(new, `:go-psi-semantic:corpusTest`, context pinned to linux/amd64 + cgo)*.
For every buildable GOROOT package (like `GorootResolveCorpusTest`, `cmd` included):

1. Compute the summary with `GoSummaryBuilder`, serialize, and deserialize into a fresh
   `GoLibrarySummaries` instance with no access to the builder's in-memory types.
2. For every package-level declaration, compare **rendered strings exactly**. Use
   `GoTypeRenderer.render(type, qualifier = { it.pkgPath })` (full import path for every named type,
   so equal names in different packages cannot collide), extended for declarations:
   - type: `type T[P bound, ...]`, then `= <aliased>` for an alias or `<underlying>` for a defined
     type, plus sorted `name(pointer receiver?) signature` of the declared
     methods + the method set of `T` and `*T` (`GoLookup.methodSet`);
   - func/var: the type. Const: the type + `GoConstant.toString()`;
   - fields and methods of struct/interface underlying types are expanded one level, so tags,
     embedded flags and pkgPaths are compared.
3. Also check `GoTypePredicates.identical(psiType, summaryType)` for every entry (identity across
   PSI-built and summary-built types, section 4), and byte-identical re-serialization of the
   decoded summary (determinism).
4. Metrics go to `testData/metrics/goroot-src-summary.json`: packages, entries, mismatches (must
   be 0), unsummarized entries (recursion-prevented; may only decrease), bytes, build ms. The same
   test over the golang.org/x sample used by `GomodcacheResolveCorpusTest`.
5. Consumer gate: `GorootResolveCorpusTest`, `GorootCheckCorpusTest`, `GomodcacheResolveCorpusTest`
   and `GoTypesTestdataTest` run with `gopsi.librarySummaries=true` (summaries computed
   synchronously first), and their counts must equal the PSI-path counts. An `AstLoadingFilter`
   test (`GoResolveAstLoadingTest` style) asserts that typing project code that uses
   `http.StatusOK`, `io.EOF` and `os.Args` loads no AST of a library file.

## 10. Expected gains

Honest ranges. The P9 split is unknown until step 0 measures it.

| Metric | Today | Expected with summaries (persisted, after the first session) | Reasoning |
|---|---|---|---|
| P9 `check_cold`, `ssagen/ssa.go` | 3948 ms | 1.0-2.0 s | the `ssa`/`types`/`ir` packages of `cmd` are not indexed: today stubs are built by parsing, and `opGen.go`'s AST is loaded for `Op*` constants. With a summary, both are skipped. The file's own body typing remains (warm 437 ms, cold several times that). |
| P9 `check_cold`, other six files | 1243 ms | 0.8-1.1 s | GOROOT is indexed. The gain is typing library declarations and the AST loads for vars/consts. |
| P9 `check_cold_total` | 5.1 s | **2-3 s** | |
| P9 retained | 61-74 MB | -10 to -25 MB | no stub trees and per-element `CachedValue`s for library declarations. Raw summaries of the touched packages are ~1-3 MB. |
| `GoCacheMemoryBenchmark` (`net/http`) | 62 MB | -5 to -15 MB | most of the retention is net/http's own body stores, which summaries do not touch |
| `GoLibraryCacheBenchmark.warm` | 0.77 ms | unchanged | already warm. Add a `coldLibrary` case (`invalidateAll()` of library state only, then one pass): 177.6 ms-class today, expected < 40 ms |
| first open (GOROOT indexing) | 3-5 s | unchanged | item 8. Background summary computation adds CPU after indexing (estimate 5-20 s at low priority for a std closure), once per toolchain and context |

The first session after install gets the gains only after the background queue has caught up. Every
later IDE start gets them immediately (store read: ~µs per entry, about 1 ms per package header
validation).

## 11. Phased plan

| Step | Effort | Work | Test | Gate |
|---|---|---|---|---|
| 0 | S | Measure before building. Add to `perf.py` a P9 variant with a second set of fresh copies analysed after the first (own caches cold, library caches warm). The difference bounds the gain. Add a `coldLibrary` case to `GoLibraryCacheBenchmark`. | n/a | **go/no-go**: library share of P9 cold >= 30 % or >= 15 MB retained; otherwise stop after step 1 |
| 1 | M | Identity: `GoPackageKey`, `GoTypeDeclarationPointer`, key-based equality of `GoNamedType`/`GoTypeParamType`, lazy `declaration` on types, fields, methods and params, lazy `Result.Selection.element`, the call-site changes of section 4, PERF item 3 cache for `GoPackageKey.of(dir)`. No summaries yet. | `GoTypeIdentityTest` *(new)*: equal across stub/AST switch, same name in a build-excluded file is distinct, local types by PSI; all semantic/ide tests | `apiSurfaceCheck` with approved diff; `:go-psi-semantic:corpusTest` counts unchanged; `GoResolveBenchmark`, `GoHighlightingPassBenchmark` within noise |
| 2 | M | `GoSummaryBuilder`, `GoSummaryExternalizer`, `GoSummaryTypeSource`, in-memory only | `GorootSummaryCorpusTest` + golang.org/x sample; `GoSummaryFormatTest` (every tag round trip, recursive types, generic instantiation, `byte`/`rune`, untyped consts, aliases of generic types) | 0 mismatches; `goroot-src-summary.json` committed |
| 3 | M | Consumers behind `gopsi.librarySummaries` (default off): `SummaryPackageScope`, the `entryFor` hooks, completion via `names()`/`entry()`; summaries computed synchronously on demand in tests, queued in the IDE | `AstLoadingFilter` test; `GoCacheInvalidationTest` cases for library edit, model change and unsaved document | resolve/check/testdata gates with the key on equal to off; `GoCacheMemoryBenchmark`, `GoLibraryCacheBenchmark.coldLibrary`, P9 |
| 4 | M | Persistence: `GoSummaryStore`, validation (fingerprint, deps' hashes), background queue in dependency order, startup cleanup, size cap; `GoSummaryVersionTest` golden | store reopen test (fixture temp dir); invalidation tests: touched file, changed dependency version (two `replace` fixtures), toolchain change, corrupted file | UI robot `--perf --cold` twice (second start measures persisted summaries); P9 against step 0 |
| 5 | S | Default on; `docs/SEMANTIC.md` caching section, `CHANGELOG.md`; registry key kept as a kill switch | full `./gradlew build` | all gates; thresholds lowered where improved |

Total: about one L milestone, with steps 1 and 2 independently useful (step 1 fixes the documented
intent of `CLAUDE.md`, step 2 gives an exactness oracle for type rendering).

## 12. Risks

- **Stale persisted types after a typing fix.** The version guard golden (section 8) catches only
  the fixed package set. A fix that changes a declaration outside it, without a bump, ships stale
  types until the next bump. Mitigation: bump `SUMMARY_VERSION` on every release-like milestone
  that touches `semantic.infer` (a `CLAUDE.md` rule for the orchestrator to decide).
- **Identity change (step 1)** touches the heart of the type system. Equality is used as a map key
  in substitutions, caches and the unifier. Mitigation: step 1 ships alone with all corpus gates.
- **Two models of one declaration.** A summary type and a PSI type of the same library declaration
  must behave identically, not only compare equal (for example `GoField.declaration` null vs
  non-null breaks completion). The corpus test compares rendering and identity, not behaviour of
  IDE features. Run the ide tests with the key on.
- **Background CPU and disk** on first use (one pass over the import closure; estimate 2-4 MB per
  std context, plus module-cache packages). Low priority, cancellable, capped.
- **Build-context churn.** Switching GOOS/GOARCH in settings creates a second set of summaries,
  and the old one stays until the size cap evicts it.
- **Platform API stability.** `PersistentHashMap`/`PersistentMapBuilder` are stable, but
  their concurrency contract requires that no write happens under a read action holding locks for
  long. Write from the queue worker outside read actions (compute in a read action, write after).

## 13. PERF-BACKLOG items 5 and 8

**Item 5 (package scope lookups through the stub index instead of a per-package map).**
For library packages this design **subsumes** it: a served library package's scope is the
summary's sorted name index (bytes, no PSI retained), which is smaller and faster than both the
current map and per-name `GoAllPublicNamesIndex` queries with `directoryScope`. Item 5 then only
matters for **project** packages, which are usually small. Re-measure `GoResolveBenchmark` and
`GoCacheMemoryBenchmark` after step 3 before doing it. No conflict, but the same `PackageScope`
code is touched: introduce the `PackageScope` interface (step 3) first, so that an item-5
`IndexPackageScope` for project packages can be a third implementation.

**Item 8 (first open: GOROOT indexing 3-5 s).** **Not subsumed.** Summaries do not replace
stubs: navigation, documentation, implementations and Go to Symbol into GOROOT still need indexed
stubs. They are complementary:
- shared indexes (option c above) are the right tool for item 8. A future "prebuilt summaries per
  Go version" download could reuse that distribution channel, but that is a separate decision;
- moving `GoFileImportsIndex`/`GoBuildTagsIndex` data into `GoFileStub` (item 8's cheaper
  option) bumps `STUB_VERSION`. Because `STUB_VERSION` is in the summary store path, that drops
  all summaries once, which is correct and harmless. The import data in the stub would also let
  the summary dependency check skip `GoFileImportsIndex`;
- summaries make the cost of *not* indexing `cmd/` (P9's 3.9 s file) mostly disappear after the
  first session, which weakens the case for `gopsi.libraryRoots.includeCmd=true` and its indexing
  cost.

## 14. Open decisions for the orchestrator

1. Approve the public API change in `semantic.types` (step 1): key-based equality and a lazy
   `declaration` on `GoNamedType`, `GoTypeParamType`, `GoField`, `GoMethod` and `GoParam`, plus the
   new `GoTypeDeclarationPointer`/`GoPackageKey`. The alternative (keep PSI identity and
   materialize `GoTypeSpec` through stubs on decode) keeps the API but loses most of the memory gain.
2. Should the pointer include the declaring **file name**? It distinguishes the same name in two
   build-variant files at the cost of a few bytes. Recommended: yes.
3. Store scope: application-level (recommended: GOROOT/module cache shared across projects) or
   project-level.
4. Granularity: whole-package summaries computed in the background (recommended) or per-entry
   lazy persistence (no background CPU, but more complex validation and no complete name index).
5. Which library kinds are served: GOROOT + module cache only (immutable), or also vendor, GOPATH
   and local replacements (mutable, fingerprint-validated).
6. Should `cmd/` packages (not indexed by default) get summaries? They are the biggest P9 win.
7. The step-0 go/no-go threshold, and whether `SUMMARY_VERSION` bumps become a `CLAUDE.md` rule.

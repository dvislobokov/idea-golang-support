# Project model (Phase 4)

Module `go-psi-semantic`, packages `io.github.golangsupport.project.api` (public, stable) and
`io.github.golangsupport.project.impl` (internal). The model answers four questions without
gopls and, by default, without running `go`:

1. Which toolchain applies? (`GoToolchainProvider`)
2. Which files belong to a package under a build configuration? (`GoBuildConstraintEvaluator`)
3. Which module versions make up the build? (`GoModuleGraphProvider`)
4. Which directory does an import path denote, from a given file? (`GoPackageResolver`)

The destination plugin (idea-golang-support) replaces the default services with implementations
backed by its own `cli`, `mod` and `settings` packages (`overrides="true"` on the service
declarations in `META-INF/go-psi-semantic.xml`). go-psi registers no go.mod file type or language.

## Public API (`project.api`)

| Type | Kind | Members |
|---|---|---|
| `GoVersion` | value class, `Comparable` | `parse(text)`, `major`, `minor`, `isValid`, `languageVersion`, `isAtLeast`, `toString()` = `go1.x.y`. Ordering of `go/version`: `1.21 < 1.21rc1 < 1.21.0 < 1.21.1` |
| `GoToolchainInfo` | data class | `goroot`, `version`, `gopath`, `gomodcache`, `goos`, `goarch`, `cgoEnabled`, `buildTags`, `env`, `goBinary`; derived `gorootSrc`, `buildContext` (adds `goexperiment.*` and `amd64.vN` tool tags) |
| `GoToolchainProvider` | application service interface | `toolchainFor(project: Project?)`, `getInstance()` |
| `GoBuildContext` | data class | `goos`, `goarch`, `cgoEnabled`, `compiler`, `goVersion`, `buildTags`, `toolTags`; `matchTag(tag)`; `LINUX_AMD64` |
| `GoPlatforms` | object | `KNOWN_OS`, `UNIX_OS`, `KNOWN_ARCH` (from `internal/syslist`) |
| `GoConstraintExpr` | sealed class | `Tag`, `Not`, `And`, `Or`; `eval(ok)`; `toString()` in `//go:build` syntax |
| `GoConstraintSyntaxException` | exception | `offset`, `message` (texts of `go/build/constraint`) |
| `GoBuildConstraintEvaluator` | object | `parse(line)`, `parseExpr`, `parsePlusBuildExpr`, `isGoBuild`, `isPlusBuild`, `goVersion(expr)`, `parseFileHeader(text)`, `shouldBuild(text, ctx)`, `matches(GoBuildConstraint, ctx)`, `goodOSArchFile(name, ctx)`, `matchFile(name, text, ctx)`, `matches(GoFile, ctx)` |
| `GoModuleVersion`, `GoRequire`, `GoReplace`, `GoRetract` | data classes | directive values |
| `GoModule` | data class | `path`, `version`, `dir`, `goModFile`, `goVersion`, `isMain`, `isWorkspaceMember`, `isIndirect`, `replacement`, `requires`, `replaces`, `excludes`, `retracts`, `tools`, `deprecated` |
| `GoModuleGraph` | class | `mainModules`, `modules` (build list), `workFile`, `vendorMode`, `vendorDir`, `missing`, `source` (`PURE`, `VENDOR`, `GO_LIST`); `module(path)`, `modulesForImportPath(path)` |
| `GoModuleGraphProvider` | project service interface | `graphFor(fileOrDirectory: VirtualFile)`, `getInstance(project)` |
| `GoPackage` | data class | `importPath`, `name`, `directory`, `module`, `goFiles`, `testFiles`, `xTestFiles`, `ignoredFiles`, `isStd`; `isCommand`, `isEmpty` |
| `GoImportResolution` | sealed interface | `Resolved(pkg)`, `CPseudoPackage`, `InternalDenied(pkg)`, `Unresolved(importPath, reason)`; `packageOrNull` |
| `GoPackageResolver` | project service interface | `resolveImport(importPath, fromFile)`, `packageOf(directory, context = null)`, `importPathOf(fileOrDirectory)`, `getInstance(project)` |

## Toolchain detection (`DefaultGoToolchainProvider`)

1. `GOROOT` from the environment when it points at a Go installation (`src/` plus `VERSION` or `bin/`).
2. `go` on `PATH`: GOROOT is the parent of its (real) `bin` directory.
3. Default locations: `C:\Program Files\Go`, `/usr/local/go`, `/usr/lib/go`, Homebrew.

The pure answer reads `$GOROOT/VERSION`, the process environment and the `go env -w` file
(`GOENV`, default `<config dir>/go/env`); cgo defaults to enabled only for native builds with
`gcc`/`clang` on `PATH`, like Go. When a `go` binary is known, `go env -json` runs **once, on a
pooled thread** (never the EDT); its answer replaces the pure one, bumps the provider's
modification tracker and every open project's `GoProjectModelTracker`.

## Build constraints

Ported from `go/build/constraint` (parser, `+build` semantics, size limits, `GoVersion`) and
`go/build` (`parseFileHeader`, `shouldBuild`, `goodOSArchFile`, `matchFile`, `matchTag`):

- `//go:build` anywhere in the leading comment block controls; otherwise every `// +build` line
  ending before the last blank line must hold (lines AND, space-separated options OR,
  comma-separated terms AND, `!` negates). Two `//go:build` lines or a malformed one exclude the file.
- File names: `_GOOS`, `_GOARCH`, `_GOOS_GOARCH`, optionally followed by `_test`, only for known
  names; text before the first `_` is ignored (`linux.go` is not constrained).
- Tags: GOOS, GOARCH, compiler (`gc`), `cgo`, `unix` (Unix GOOS list), `linux` on android,
  `darwin` on ios, `solaris` on illumos, `boringcrypto` -> `goexperiment.boringcrypto`, custom and
  tool tags, release tags `go1`..`go1.N` up to the toolchain minor version (all hold when the
  version is unknown).

Note: core's `GoFile.buildConstraint` / `GoBuildTagsIndex` only record a `//go:build` line that
precedes a blank line (go/build accepts it anywhere in the header, e.g. directly above `package`).
The resolver therefore evaluates the file text with `parseFileHeader`; `matches(GoBuildConstraint)`
is offered for index-based callers.

## Module graph

`GoModuleGraphBuilder` (pure, `java.nio` paths):

1. Locate the nearest `go.mod` and the applicable `go.work` (`GOWORK=off|<file>`, else nearest
   ancestor). Workspace mode applies when the module is a `use`d member (or there is no go.mod).
2. Parse main modules (`GoModFileParser`, modfile grammar). Replacements: go.work wins over
   go.mod; version-specific before wildcard; local targets resolve against the declaring file's
   directory. Excludes: union of the main modules'.
3. Vendor mode: `vendor/modules.txt` (go.work dir for workspaces) exists and go >= 1.14 (1.22 for
   workspaces), unless `GOFLAGS` has `-mod=mod|readonly` (`-mod=vendor` forces it); for go >= 1.17
   each requirement must match an `## explicit` entry with the same version and vice versa. In
   vendor mode the build list is modules.txt; MVS is skipped.
4. Otherwise `Mvs` over the module cache (`GoModuleCacheLayout`: `cache/download/<escaped>/@v/<v>.mod`,
   extracted `<escaped>@<v>/`, `!x` escaping):
   - roots = main-module requirements; requirements on excluded versions and on main-module paths
     are ignored;
   - pruning (go >= 1.17): a pruned module reached through a pruned path contributes edges but its
     dependencies' go.mod files are not loaded; an unpruned module (< 1.17 or no `go` line) loads
     its full transitive graph; a main module below 1.17 runs classic MVS;
   - selected version = max over every version in the graph (`SemVer`, pseudo-versions included);
   - single module, pruned: a root whose selected version exceeds the listed one is raised and the
     graph recomputed (what `go mod tidy` would record); workspaces are not raised.
   - After selection, each module's go.mod is read once more for metadata (go version,
     directives) without adding graph edges. `dir` is the extracted directory when present, the
     local replacement directory, or null.

Missing go.mod files are listed in `GoModuleGraph.missing`.

### `go` binary fallback

`DefaultGoModuleGraphProvider` uses the pure graph when nothing is missing (or in vendor mode).
When modules are missing and a `go` binary is known, it schedules `go list -m -json -e all`
(`GOFLAGS=-mod=readonly`, so go.mod is never rewritten; it may download go.mod files through the
user's `GOPROXY`) once per input state on a pooled thread, returns the incomplete pure graph
meanwhile, and on completion stores the result and bumps `GoProjectModelTracker`; the next query
returns the `GO_LIST` graph. Every decision is logged at INFO (`go-psi: module graph for ...`).
Without a binary the incomplete graph is used and the log suggests `go mod download`.

## Import resolution order (`DefaultGoPackageResolver.resolveImport`)

1. `C` -> `CPseudoPackage`.
2. Relative (`./x`, `../x`) -> directory relative to the importer.
3. Importer inside `$GOROOT/src`: `src/vendor/<path>` (or `src/cmd/vendor/<path>` for `cmd/...`),
   then `src/<path>`. Nothing else applies inside GOROOT.
4. Standard library: first path element without a dot and `$GOROOT/src/<path>` has `.go` files.
5. The importer's module graph (for a module-cache importer: the graph of a project module whose
   build list contains that module version, else the cached module's own go.mod):
   - vendor mode: main-module packages, then `<vendor>/<path>`;
   - otherwise modules whose path prefixes the import path, longest first, using `GoModule.dir`
     (main/workspace module directory, local replacement, extracted cache directory); directories
     inside a nested module (own go.mod) do not belong to the outer main module.
6. `GOPATH/src/<path>` when there is no module.
7. `internal` (`load.findInternal`): allowed when the importer's directory lies under the parent
   of the last `internal` element, or the importer's import path has that parent as prefix;
   `internal/...` of the standard library only from inside GOROOT. Otherwise `InternalDenied`.

`importPathOf`: `$GOROOT/src/<rel>` (`vendor/...` kept, `cmd/vendor/` stripped, matching `go list`),
module cache `<escaped>@<v>/<rel>`, main module path + relative path (vendor dirs stripped), GOPATH.

`packageOf(dir, ctx)`: `.go` files sorted by name; `matchFile` decides inclusion (names starting
with `_`/`.`, file name suffixes, header constraints); the package name comes from the first
included non-test file; `_test.go` files of the same package are `testFiles`, of `<name>_test`
are `xTestFiles`; mismatching package clauses and `package documentation` are ignored.

## Caching and invalidation

- `GoProjectModelTracker` (project `SimpleModificationTracker`) is bumped by a project-level
  `BulkFileListener` on create/delete/move/rename/content change of `go.mod`, `go.work`,
  `go.sum`, `go.work.sum`, `vendor/modules.txt`, `vendor` directories; also by the toolchain
  refinement and by `go list` completion. `PsiModificationTracker.MODIFICATION_COUNT` is not used.
- Module graphs: per (go.mod, go.work) location, a `CachedValue` depending on the tracker, the
  toolchain tracker and the VFS stamps of go.mod/go.work/go.sum/modules.txt of all main modules.
- Packages: per directory (project-level map, since VirtualFile user data is shared between
  projects) a `CachedValue` holding one partition per `GoBuildContext`, depending on
  `VFS_STRUCTURE_MODIFICATIONS`, the package's `.go` files, the tracker and the toolchain tracker.
  Headers are read from the cached document or the file text with the core lexer
  (`GoFileHeaderScanner`), never from PSI.

## Library roots (`GoRootsProvider`)

An `AdditionalLibraryRootsProvider` exposing `$GOROOT/src` (library `gopsi.goroot`; `testdata`
and `src/cmd` excluded) and the non-main module directories of the content-root graphs outside
the project (`gopsi.modules`; `testdata` excluded) so they are indexed and navigable. Registry keys:
`gopsi.libraryRoots` (default **true**) and `gopsi.libraryRoots.includeCmd` (default false).
Disabled in unit-test mode unless a test opts in. After a model change the roots are recomputed in a
non-blocking read action and `AdditionalLibraryRootsListener.fireAdditionalLibraryChanged` is
fired in a write action when they differ. The destination plugin avoided library roots for indexing
cost; the default is a decision for the transplant (see the Phase 4 report).

## Tests and gates

- `GoBuildConstraintEvaluatorTest`: ports of `go/build/constraint` `expr_test.go`, `vers_test.go`,
  `go/build` `build_test.go` (shouldBuild, goodOSArchFile, matchFile) and `syslist_test.go`.
- `GoModFileParserTest`: every go.mod directive, blocks, comments, quoting, errors, canonical
  round trip; go.work, go.sum, modules.txt; cache layout escaping; `SemVer`; `MiniJson`.
- `MvsTest`: vgo-mvs article graphs (build list, upgrade with a cycle, highest requirement),
  excludes, pruning and unpruned dependencies, root raising, workspaces, missing modules, builder
  replacements; real fixture `testData/project/mvs-real` against the golden recorded from
  `go list -m -json all` (`go-list-m-all.golden.txt`).
- `GoPackageResolverTest`: fixtures `testData/project/{simple,workspace,vendor,replace-local,nested-module,buildtags}`.
- `DefaultGoToolchainProviderTest`, `GoRootsProviderTest`.
- `./gradlew :go-psi-semantic:corpusTest` (`GorootImportsCorpusTest`): every import of every
  non-test file of every package in `$GOROOT/src` resolves inside GOROOT (except `C`); metrics in
  `testData/metrics/goroot-src-imports.json`.

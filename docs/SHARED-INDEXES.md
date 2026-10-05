# Shared indexes for GOROOT

Goal (`docs/FEATURES.md` section 9, `docs/PERF-BACKLOG.md` section 8): the first open of a project spends 3-5 s indexing
`$GOROOT/src` (a synthetic library of `GoRootsProvider`). The platform's shared indexes let an IDE attach prebuilt index data
("chunks", `*.ijx`) instead. Findings below are from the jars of IntelliJ IDEA 2026.1.4 (build 261.26222.65), read with `javap`.

## The platform plugin

`plugins/indexing-shared`, plugin id **`intellij.indexing.shared.core`** ("Shared Indexes", bundled; depends on
`com.intellij.configurationScript`). Main jar `lib/indexing-shared.jar`, content modules in `lib/modules/`:

| Module | Visibility | What matters here |
|---|---|---|
| main (`com.intellij.indexing.shared.*`) | public (no `@ApiStatus.Internal` on the EPs below) | locating, downloading, attaching chunks |
| `intellij.indexing.shared.generator` | **`visibility="internal"`** | `appStarter id="dump-shared-index"`, EP `sharedIndexDumpCommand` |
| `intellij.indexing.shared.ultimate` | default; needs `com.intellij.modules.jetbrains` | command `project` (`DumpProjectIndexesStarter`) |
| `intellij.indexing.shared.java`, `.ultimate.java` | | JDK / Maven chunks (`jdk` kind, CDN `https://index-cdn.jetbrains.com/v2/jdk`) |
| `intellij.python.sharedIndexes` | | command for Python roots (`PyDumpRootsIndexCommand`) |

## Extension points of the main module (public)

```
com.intellij.sharedIndexLocalFinder      interface com.intellij.indexing.shared.local.SharedIndexLocalFinder
    List<Path> findSharedIndexChunks(Project)
com.intellij.sharedIndexSuggester        interface com.intellij.indexing.shared.download.SharedIndexSuggester
    List<SharedIndexSuggestion> suggestRequests(Project)                      // download through the platform, with the consent UI
    SharedIndexSuggestion: SharedIndexId getSharedIndexId(); SharedIndexLookupRequest resolveRequest(ProgressIndicator); ...
    SharedIndexLookupRequest: String getKind(); String baseUrl(); List<String> indexUrls(); String getPresentableChunkName(); ...
com.intellij.sharedIndexBundled          bean BundledSharedIndexProvider(productPath)  // chunks shipped inside the IDE distribution
com.intellij.projectSharedIndexSourceProvider  ProjectSharedIndexSourceProvider.getSharedIndexSourcesFor(Project) -> url, auth
com.intellij.sharedIndexHashProvider / sharedIndexHashExporter / sharedIndexDownloadExtension / projectConsentDecisionOverrider
```

How local chunks are attached: `platform.impl.OnDiskSharedIndexChunkLocator` is a `requiredForSmartModeStartupActivity`
(`order="first, before projectIndexStartup"`, runs in dumb mode before the first indexing). It scans `<system>/shared-index/*.ijx`
(or the directory of the system property `on.disk.shared.index.root`) plus every path `sharedIndexLocalFinder` returns, reads each chunk's
metadata and attaches the ones compatible with the running IDE (index versions of every `FileBasedIndexExtension` and stub serializer;
otherwise "Local shared index ... is incompatible with current IDE version" in idea.log). Files are matched by **content hash**, not by
path, so a chunk built from one GOROOT serves any GOROOT with the same files.

What a chunk is keyed by: nothing beyond its file. The platform's own kinds key the download URL (`jdk`: JDK content hash and aliases;
`project`: project id + VCS commit); the `.ijx` metadata only carries index versions. The plugin chooses its own key (below).

## Producing a chunk

`dump-shared-index` is an app starter of the internal generator module; `--name=value` arguments (`ArgsParser`), `@args-file` accepted.
Common arguments (`MainGenerateArgs`): `--output` (required), `--temp`, `--compression=plain|xz`, `--dump-project-roots`, `--additional-indexes`,
`--no-stub-tree-file-types`, `--base-shared-index`, `--add-hash-to-output-names`, `--generate-binary-reproducible-maps`.
Commands registered in 2026.1: `project` (ultimate), `jdk`, `maven`, `jars` (ultimate.java), `python-sdk` (arbitrary roots, but only
with the Python plugin; args `roots`, `name`, `sdkMode`), `version` (metadata only).

A new command (`DumpSharedIndexCommand` with an `IndexChunk` of our own roots) would need the **internal** generator module:
not usable from a third-party plugin. The usable route is the public command **`project`**, which indexes everything
`FileBasedIndexImpl.getIndexableFilesProviders(project)` yields, synthetic libraries of `AdditionalLibraryRootsProvider` included:

```
<IDE>/bin/idea.bat dump-shared-index project --project-dir=<dir with go.mod only> --output=<out> --temp=<tmp> \
    --project-id=go-stdlib-<key> --compression=plain
```

with `IDEA_PROPERTIES` (`PYCHARM_PROPERTIES`, `WEBIDE_PROPERTIES`, ...) pointing to a properties file that sets `idea.config.path`,
`idea.system.path`, `idea.log.path` to a throwaway directory (otherwise the launcher hands the command to the running instance) and
`idea.plugins.path` to the running IDE's plugins directory (so this plugin, with the same index versions, does the Go indexing).
The project directory holds `go.mod` (`module gosharedindex`, `go 1.27`) and nothing else, so the only library is `$GOROOT/src`
(minus `cmd` and `testdata`, as `GoRootsProvider` exposes it). `bin\idea.bat` loses the JVM exit code: success is "an `.ijx` appeared".

## What the plugin does (`io.github.golangsupport.sharedindex`, `META-INF/go-shared-indexes.xml`)

- `<depends optional="true" config-file="go-shared-indexes.xml">intellij.indexing.shared.core</depends>`; classes of the package are referenced
  only from that descriptor (settings check the plugin by id).
- Key (`GoSharedIndexKey`): `<release>-<goos>-<goarch>-<sha256(VERSION)[:12]>`, e.g. `go1.27.1-windows-amd64-0123456789ab`. Release = first line
  of `$GOROOT/VERSION` (`goX.Y[.Z][rcN]`); host = the `pkg/tool/<goos>_<goarch>` directory (toolchain GOOS/GOARCH as a fallback). No `go`
  process. Development trees (`devel ...`) get no key.
- Local layout: `<IDE system dir>/go-plugin/shared-indexes/<key>/*.ijx` + `go-shared-index.json` (key, release, IDE build, plugin version, date).
  `GoSharedIndexFinder` (`sharedIndexLocalFinder`) returns those files; the platform checks compatibility.
- Go | Build Shared Index for GOROOT... (`GoBuildSharedIndexAction`): prepares `<...>/shared-indexes/.work/<key>/` (project, config with the
  `go` executable of Settings | Go, properties), runs the command above through `GoCli.runInBackground` (output in the Build window), moves
  the chunk into `<key>/`, notifies. Journal category `index`.
- Download (optional, Settings | Go, "Shared indexes URL"): `<url>/index.json` =
  `{"chunks": [{"key": "...", "ideBuild": "IU-261.26222.65", "url": "relative/or/absolute.ijx", "sha256": "..."}]}`. Without a local chunk the
  finder fetches the entry of its key and IDE build (same build number, any product code) in the background; the sha256 is checked; the
  chunk is used from the next project open (the locator has already run by then).

## Limitations

- A chunk is valid for one IDE build and one set of index versions: an IDE update or a `STUB_VERSION` / index version bump of this plugin
  makes it incompatible (the platform skips it and logs why); rebuild with the action.
- `dump-shared-index project` needs `intellij.indexing.shared.ultimate` (IDEs with `com.intellij.modules.jetbrains`); where the command is
  missing the headless run fails and the action reports its log.
- The headless run starts a second IDE: memory of a second JVM, roughly a minute; project-open activities of other plugins run in it too.
- Not verified live yet: that the `project` chunk of a throwaway project attaches for library files of another project (content hashes say
  yes), and that the headless IDE accepts a project without a trust prompt. See the report of the step for the live check.
- The platform downloader (`sharedIndexSuggester`) was not used: it brings the consent balloon and its own CDN layout
  (`list.json.xz`, `index.json.xz` per kind); our own fetch is simpler for a self-hosted directory.

## Fallbacks considered

- `com.intellij.psi.stubs.PrebuiltStubsProvider` (`SerializedStubTree findStub(FileContent)`, platform `analysis-impl`) still exists, but the
  old `PrebuiltIndexProvider` storage around it is gone: the plugin would have to ship its own content-hash -> serialized stub storage
  and would cover stubs only (80% of GOROOT indexing cost, `PERF-BACKLOG.md` section 8), not `IdIndex` / `Trigram.Index`.
- Cheaper indexing itself (PERF-BACKLOG section 8: lazy top-level literal values, header indices from the stub).

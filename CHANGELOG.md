# Changelog

All notable changes to the Go Project Support plugin are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Logs
- **Go | Plugin Logs**: the journal of the plugin in a tool window (only the events of the plugin; Clear, Warnings and Errors Only, Open Logs Folder), and a "Plugin Logs" button on every error notification. One folder for every log, `~/idea-golang-logs`: `plugin/plugin-DATE.log` (the journal), `commands/commands-DATE.log` (every `go` command and tool the plugin runs, with its output, timestamps and exit code), `delve/` (the debugger logs moved here from the log directory of the IDE). Files are kept for two weeks. **Go | Open Logs Folder** opens it.

### Migration to the native Go PSI (branch `migration`, `MIGRATION.md`)
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

# Changelog

All notable changes to the Go Project Support plugin are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Migration to the native Go PSI (branch `migration`, `MIGRATION.md`)
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

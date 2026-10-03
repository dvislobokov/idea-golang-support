# Go inspections in CI (SARIF)

The plugin's Go and `go.mod` inspections can run without the IDE UI and write a [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html)
report: GitHub code scanning, GitLab, Azure DevOps and most SARIF viewers read it. It is the same analysis as in the editor with
Language features = Built-in: no gopls, no `go vet`, no golangci-lint.

## From the IDE

The same run is in the **Go** menu, no command line needed. **Go | Inspect Project** runs every Go and go.mod inspection the current
profile enables over the project (on a directory in the Project view: over that directory) through the platform's batch inspection, so the
results land in the standard Inspection Results tool window, grouped by inspection and file, with navigation, quick fixes and
batch apply. **Go | Export Inspections to SARIF…** asks where to save (default `<project>/go-inspect.sarif`), runs the same code as
`go-inspect` (`io.github.golangsupport.ci.GoInspectRun`) in a cancellable background task, writes the report and shows a notification
"N findings written to go-inspect.sarif" with Open File and Show in Explorer / Reveal in Finder. Both read the same files as the command
below (vendor, testdata, generated code and files excluded by build constraints are skipped) and work with Language features = gopls too:
for the duration of the run the native inspections are let through, and the open editors are re-highlighted afterwards.

## Why a command of our own

The platform's `inspect` command (`bin/inspect.sh`, `InspectionApplication`) runs our inspections too, but writes only its XML
(`-format xml`, the default) or JSON (`-format json` / `json-single-file`). Checked in the IntelliJ IDEA 2026.1.4 jars: the report
converters of `intellij.platform.analysis.impl` are `XSLTReportConverter`, `JsonInspectionsReportConverter` and
`JsonSingleFileInspectionsReportConverter`, there is no `sarif`. SARIF comes only from the bundled Qodana plugin (`qodana` starter),
which ships with IntelliJ IDEA only and belongs to the Qodana product (its own CLI, images and licensing). So the plugin registers its own application starter, `go-inspect`
(`io.github.golangsupport.ci.GoInspectStarter`).

## Command

```
<IDE>/bin/idea.sh go-inspect <projectDir> <out.sarif> [--inspections A,B] [--min-severity weak|warning|error]
<IDE>\bin\idea.bat go-inspect ...                       (Windows; the exit code is lost there, use tools\ci\go-inspect.cmd)
```

Any IDE with the plugin installed works (`idea.sh`, `pycharm.sh`, `webstorm.sh`, ...). The IDE must not already be running with the
same config directory; the wrappers below use a throwaway one.

| Argument | Meaning |
|---|---|
| `projectDir` | the project root (the directory with `go.mod` or `go.work`); opened like File, Open, `.idea` is not needed |
| `out.sarif` | the report; parent directories are created |
| `--inspections A,B` | only these inspections (short names, e.g. `GoUnusedVariable,GoNilDereference`), even if the profile disables them |
| `--min-severity` | `weak` (default: everything), `warning` or `error`; results below it are left out of the report |

What runs: every enabled inspection of language Go or GoModule in the project's current inspection profile (`.idea/inspectionProfiles`
when the project has one, the defaults otherwise), at the level the profile gives it. `//noinspection` suppressions are honoured.

What is read: `*.go`, `go.mod` and `go.work` under `projectDir`, without `vendor`, `testdata`, `node_modules`, directories starting with
`.` or `_`, files with the `// Code generated ... DO NOT EDIT.` header, and files the build constraints exclude for the host
GOOS/GOARCH (or those of Settings, Tools, Go).

For the run the plugin switches Language features to Built-in and turns gopls off, then restores both settings.

Exit code: `0` nothing at or above `--min-severity`, `1` findings, `2` bad arguments, a failed run, or inspections that failed while nothing was found (the reason on stderr and in
the plugin log, category `ci`). One line of summary goes to stdout:
`go-inspect: 63 inspections, 41 files, 7 findings (1 error, 6 warning, 0 note), 0 inspection failures, 5321 ms -> out.sarif`.

An inspection that throws on a file does not fail the run: it is listed under `invocations[0].toolExecutionNotifications`.

### Report

- `tool.driver`: `name` "Go Project Support", `version` of the plugin, one `rules[]` entry per inspection that ran (`id` = short
  name, `shortDescription` = display name, `fullDescription` = the inspection description as text, `defaultConfiguration.level`,
  `properties.tags` = the group).
- `results[]`: `ruleId`, `ruleIndex`, `level` (`error` / `warning` / `note`), `message.text`, one `physicalLocation` with `uri`
  relative to `projectDir` (`uriBaseId` `%SRCROOT%`, given in `originalUriBaseIds`) and a 1-based `region`, end column exclusive,
  columns in UTF-16 code units (`columnKind`).
- Results are sorted by file, line, column and rule, so two runs over the same code give the same file.

## Wrappers

`tools/ci/go-inspect.sh` (Linux, macOS) and `tools/ci/go-inspect.cmd` (Windows) take the same arguments and:

- install the plugin from `GO_PLUGIN` (the ZIP from `build/distributions`, or an unpacked directory for the `.sh`) into a fresh
  config under `GO_INSPECT_WORK` (a temporary directory by default), so a running IDE is not disturbed;
- find the launcher in `IDE_HOME/bin`;
- return the exit code of the run (the plugin also writes it to `GO_INSPECT_EXIT_CODE_FILE`, since `idea.bat` drops it).

```sh
IDE_HOME=/opt/idea GO_PLUGIN=build/distributions/idea-golang-support-0.2.51.zip \
  tools/ci/go-inspect.sh . build/go-inspections.sarif --min-severity warning
```

```bat
set IDE_HOME=C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4
set GO_PLUGIN=C:\work\idea-golang-support-0.2.51.zip
tools\ci\go-inspect.cmd C:\work\app C:\work\app\go-inspections.sarif
```

## GitHub Actions

```yaml
name: go-inspections
on: [push, pull_request]

jobs:
  inspect:
    runs-on: ubuntu-latest
    permissions:
      security-events: write   # upload-sarif
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5        # the plugin reads GOROOT and the module cache through `go env`
        with:
          go-version-file: go.mod
      - run: go mod download             # dependencies resolve from the module cache
      - name: IDE
        run: |
          curl -fsSL -o ide.tar.gz "$IDE_URL"
          mkdir ide && tar -xzf ide.tar.gz -C ide --strip-components=1
        env:
          IDE_URL: https://download.jetbrains.com/idea/idea-2026.1.4.tar.gz   # any IDE of build 261+ that runs headless in CI
      - name: Plugin
        run: curl -fsSL -o go-plugin.zip "$PLUGIN_URL"                        # the plugin ZIP of the release you use
        env:
          PLUGIN_URL: ${{ vars.GO_PLUGIN_URL }}
      - name: Inspect
        run: tools/ci/go-inspect.sh . go-inspections.sarif --min-severity warning
        env:
          IDE_HOME: ${{ github.workspace }}/ide
          GO_PLUGIN: go-plugin.zip
          GO_INSPECT_WORK: ${{ runner.temp }}/go-inspect
        continue-on-error: true            # findings (exit 1) still upload; drop this to fail the build on them
      - uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: go-inspections.sarif
          category: go-project-support
      - uses: actions/upload-artifact@v4   # IDE and plugin logs when something went wrong
        if: failure()
        with:
          name: go-inspect-logs
          path: ${{ runner.temp }}/go-inspect/log
```

`tools/ci/go-inspect.sh` is in this repository; in another repository copy it next to the workflow or inline its few lines.

## Limitations

- The first run indexes GOROOT and the module cache: count a minute or more on a cold runner. Cache `GO_INSPECT_WORK/system`
  (`actions/cache`) to keep the indexes between runs.
- Without `go` on PATH the standard library is not found and unresolved-reference errors flood the report: install Go first.
- One build configuration per run (host GOOS/GOARCH and the tags of the settings); files of other platforms are skipped, not checked.
- Global inspections and the external linters of the plugin (golangci-lint, gopls diagnostics) are not part of the run.

# Testing and live verification

## Test layers

| Command | What runs | When |
|---|---|---|
| `./gradlew test` | Fast unit/fixture tests (lexer goldens, parser goldens, stubs, resolve markers, IDE fixtures) | Every change |
| `./gradlew test --tests "<pattern>"` | A subset | While iterating |
| `./gradlew :go-psi-core:corpusTest` | Slow gates over `$GOROOT/src`, `$GOROOT/test`, GOMODCACHE corpora (`*CorpusTest` classes) | Before finishing grammar/stub/resolve work; nightly in CI |
| `./gradlew benchmark` | `Benchmark.newBenchmark` suites with thresholds | Phase 7+, before milestones |
| `./gradlew :plugin:verifyPlugin` | Plugin Verifier against IC 2026.1 (and 2026.2 later) | Before milestones |

System properties: `gopsi.goroot`, `gopsi.gomodcache`, `gopsi.updateGoldens=true`,
`gopsi.testDataPath`.

## Corpus metrics

`testData/metrics/*.json` holds the last accepted numbers per corpus, for example:

```json
{ "corpus": "goroot-src", "files": 7118, "filesWithErrors": 0, "badCharacters": 0,
  "unresolvedIdentifiers": 0, "parseMillisPerMb": 0 }
```

A corpus test fails if a metric regresses. Improvements are committed together with the code.

## Benchmarks

Benchmarks live in `*Benchmark` classes of the three modules (`go-psi-core`, `go-psi-semantic`,
`go-psi-ide`; package `io.github.golangsupport.benchmark`) and are excluded from `test`.
Inputs are real files read from `$GOROOT/src` at runtime (`net/http/server.go`, `go/types`,
`go/printer/nodes.go`, `net/http`, `strings`, `bytes`, `testData/project/mvs-real`); nothing from
GOROOT is committed. The shared harness is `tools/benchmark/BenchmarkSupport.kt`, compiled into the
test source set of each module.

```
./gradlew benchmark                                  # all modules, sequentially
./gradlew :go-psi-core:benchmark --tests "*GoParserBenchmark"
./gradlew benchmark -Dgopsi.benchmark.update=true    # record missing / faster thresholds
./gradlew benchmark -Dgopsi.benchmark.tolerance=2.0  # CI on a slower machine
# go-psi-core and go-psi-ide: extra options for the benchmark JVM, e.g. a JFR recording for profiling
./gradlew :go-psi-core:benchmark --tests "*GoIndexBenchmark*" "-Pgopsi.benchmark.jvmArgs=-XX:StartFlightRecording=filename=index.jfr,settings=profile"
./gradlew :go-psi-ide:benchmark --tests "*GoFormatterBenchmark*" "-Pgopsi.benchmark.jvmArgs=-XX:StartFlightRecording=filename=fmt.jfr,settings=profile"
# go-psi-semantic corpusTest: the same for corpus tests; GorootSlowFilesCheckCorpusTest times check() on single files
./gradlew :go-psi-semantic:corpusTest --tests "*GorootSlowFilesCheckCorpusTest" -Dgopsi.check.files=default "-Pgopsi.corpus.jvmArgs=-XX:StartFlightRecording=filename=check.jfr,settings=profile"
```

Every benchmark runs 3 warm-up and 5 measured iterations (a GC before each, untimed `prepare`
steps for fresh inputs), takes the median and prints one line:
`BENCH <name>: median=<ms> <unit>=<value> (threshold <value>)`. Very short bodies are repeated
inside an iteration (lexer, stubs, warm resolve/typeOf, folding, structure view, module graph) so
that an iteration is tens of milliseconds; the unit value accounts for the repetitions.

Suites and units: lexer (`server`, `gotypes`: ms/MB), parser (`server`, `gotypes`: ms/MB;
`serverIncrementalReparse`: ms for a one-character edit inside `(*conn).serve`), stubs
(`nethttp`: ms/1000 stubs), index (`stringsBytes`: ms to index + query `GoAllPublicNamesIndex`
over fresh copies of `strings` and `bytes`), resolve and typeOf (`cold`/`warm`, `net/http/server.go`
+ `go/types/expr.go`: ms/1000 refs or exprs; cold discards the Go trackers before every iteration,
other files' stubs stay cached), project model (`mvsReal`: ms, skipped when the modules are not in
the local module cache), formatter (`reformat`: ms/MB), structure view (`api`: ms), folding
(`server`: ms/MB).

Thresholds are in `testData/benchmark/thresholds.json`: `{name: {"medianMs", "unit", "value"}}`.
A run fails when `median > stored medianMs * 1.50 * tolerance`. With `-Dgopsi.benchmark.update=true`
an entry is written when it is missing or the run is faster; without a stored entry and without
the flag the run only reports. Thresholds are machine-specific (initial values: AMD Ryzen 5 8400F,
JDK 21, Windows 11, a quiet-ish machine); on another machine delete the file or re-record it, and
on CI pass a looser multiplier via `-Dgopsi.benchmark.tolerance`. Timings on a busy machine
(parallel Gradle builds, IDE running) swing by 30-40%: rerun before treating a failure as real.

### Editing performance

Baselines for the planned editing optimisations: per-package trackers with library caches
independent of project edits, lazy re-parseable function bodies, per-function-body inference
instead of per-expression `CachedValue`s. Edited files live in the light project (copies of
GOROOT files made at runtime, or generated sources); GOROOT is used through the VFS. Edits are
reversible toggles (`tools/benchmark/EditingBenchmarkSupport.kt`: insert, commit; delete, commit)
and are untimed unless stated; `BenchmarkSupport.runTimed` lets an iteration time only its
measured segments. Report-only lines end with `(report only)`.

| Benchmark (module) | Measures | Target |
| --- | --- | --- |
| `GoTypingReparseBenchmark` (core) | `serverBody`/`exprBody`: commit time of one character typed into a name inside a function body of copies of `net/http/server.go` / `go/types/expr.go`, median of 50 keystrokes at spread positions (ms/keystroke). `serverTopLevel`/`exprTopLevel`: one character on an empty line between top-level declarations. `*.psi` report: PSI tree change events per keystroke, whether the body of a far function kept its PSI identity | lazy re-parseable function bodies (today: full re-parse + DiffTree merge, so cost scales with the file) |
| `GoBodyEditRehighlightBenchmark` (semantic) | copy of the `net/http` package; `warmCheck` (reference), `bodyEditCheck` (`_ = 0` toggled in `(*conn).serve`, then `check(server.go)`), `bodyEditTypeOfOtherFunction` (same edit, `typeOf` of every expression of `(*response).WriteHeader`, ms/edit), `otherFileBodyEditCheck` (edit in `request.go`, `check(server.go)`) | per-function-body inference; a body edit should not touch other functions or files |
| `GoDeclarationEditBenchmark` (semantic) | synthetic 3-file package using `fmt`, `strings`, `sort`, `bytes`, `net/http`; a field toggled in `Config` of `a.go`; `warmCheckOtherFile` / `declEditCheckOtherFile` (`check(b.go)`), `warmLibraryExprs` / `declEditLibraryExprs` (`typeOf` of expressions of `b.go` that use only GOROOT, ms/pass, ms/edit) | per-package trackers: GOROOT caches should survive a project declaration edit |
| `GoLibraryCacheBenchmark` (semantic) | `typeOf` of all expressions of GOROOT `net/http/client.go`, `fmt/print.go`, `go/types/call.go` and a project file using them: `warm` (ms/pass), `afterProjectTopLevelEdit` (a field toggled in an unrelated project package), `afterProjectBodyEdit` (a statement toggled in a body there, ms/edit); `ratio` report | library caches independent of project edits (both after-edit cases should equal `warm`) |
| `GoCompletionLatencyBenchmark` (ide) | `completeBasic` in a generated 3000-line file with 500 package-level symbols: `member*` after `req.` (`*http.Request`), `statement*` at an empty statement; `*Cold` bumps the Go trackers before every run | per-function-body inference (completion types expressions of a fresh file copy every time) and per-package trackers (cold) |
| `GoHighlightingPassBenchmark` (ide) | `myFixture.doHighlighting()` with all go-psi inspections and the semantic annotator on a copy of `server.go` in a copy of `net/http`: `cold` (trackers bumped + daemon restart), `warm` (daemon restart only), `afterBodyEdit`, `afterTopLevelEdit` (the daemon re-runs what the edit made dirty) | all three |
| `GoCacheMemoryBenchmark` (semantic, report only) | heap retained by caches after `check` of every non-test GOROOT `net/http` file (min of 4 forced-GC samples, ASTs held before the baseline) and the number of `CachedValue`s on the PSI of `server.go` (read from the user data maps) | per-function-body inference (fewer, larger cache entries) |

Finding recorded with the baselines (not fixed by them): every commit ends with the platform's
generic file-level `childrenChanged` event, which `GoTrackers` classifies as out-of-block, so at the
time of writing every edit, including a body edit, bumps the project-wide `outOfBlock` tracker:
`GoLibraryCacheBenchmark.afterProjectBodyEdit` and `GoBodyEditRehighlightBenchmark.otherFileBodyEditCheck`
show it.

## Reference dumps

`tools/astdump` (Go) produces `go/scanner` token streams and `go/ast` S-expressions. Corpus
tests compare:
- lexer: token kinds and offsets against `astdump tokens` (mapping table in
  `GoTokenMapping`);
- parser: a normalised PSI tree against `astdump ast` (node mapping in `GoAstMapping`,
  documented differences listed in `testData/parser/ast-diff-allowlist.txt`).

### Parser conformance gates

- **go/ast diff** (`GorootAstDiffCorpusTest`): `astdump walk <root> -ast` vs the PSI tree mapped
  by `GoAstMapping` (kind, UTF-8 byte range, `Ident.Name`, `BasicLit.Kind`, operators, `ChanType.Dir`,
  `SliceExpr.Slice3`, `RangeStmt.Tok`). Siblings are aligned by start offset, so one missing node
  does not cascade. Ranges are trimmed to the first/last significant token (no comments, no inserted
  semicolons); wrapper nodes (`Type`, `Signature`, `LeftHandExprList`, ...) are unwrapped and the
  go/ast-only nodes (`FuncType`, `FieldList`, `DeclStmt`, `KeyValueExpr`, switch `BlockStmt`, ...)
  are synthesised; each normalisation is listed in the KDoc of `GoAstMapping`. The summary prints
  mismatch classes (`Kind missing|extra|range|value`, `A -> B kind`) and 40 examples; the full list is
  `go-psi-core/build/astdump/ast-mismatches.txt`. Deliberate differences go to
  `testData/parser/ast-diff-allowlist.txt` as `class <class>  # reason`. Metrics:
  `testData/metrics/goroot-src-ast-diff.json` (`mismatches` must stay 0). Another corpus:
  `./gradlew :go-psi-core:corpusTest --tests "*GorootAstDiffCorpusTest" -Pgopsi.astdiff.root=<dir>`
  (prints only, no metrics). Files the platform refuses to build PSI for (over ~2.5 MB) are skipped.
- **Fuzzing** (`GoParserFuzzCorpusTest`, fast variant `GoParserFuzzTest`): 300 files of GOROOT/src
  (fixed seed) x 20 seeded mutations (delete/duplicate a token, insert a bracket or keyword, swap two
  adjacent tokens, truncate, delete a line). Each mutant must parse without exception, keep
  `psi.text == input` and parse in < 2 s (`hardFailures`). Recovery locality: a single-token mutation
  inside a function body may not reduce the number of error-free top-level declarations by more than 2;
  violations are counted in `testData/metrics/goroot-src-fuzz.json` (`localityViolations`, may only
  decrease) and the worst ones printed with the mutated text around the damage. The fast test asserts
  zero violations on 10 small files of `testData/parser`.

## Live verification checklist (per phase)

1. `./gradlew :plugin:runIde` (sandbox IC 2026.1 with PsiViewer).
2. Open `testData/project/simple` and a scratch copy of a GOMODCACHE module
   (for example `golang.org/x/tools@<ver>`; copy, do not open the cache directly).
3. Phase 1: syntax highlighting correct, no red "bad character" marks on GOROOT files.
4. Phase 2: PSI Viewer shows the expected tree; no error elements on valid files; typing
   inside a function body does not freeze the editor.
5. Phase 3: indexing finishes; "Go to Symbol" finds package-level names.
6. Phase 4: SDK detected; GOROOT and module cache appear as libraries; build-tag-excluded
   files are greyed.
7. Phase 5: Go to Declaration across files, packages, GOROOT, dependencies; no false
   "unresolved" highlights in a large file.
8. Phase 6: completion after `.`, Find Usages, rename, structure view, folding, formatter.
9. Always: `idea.log` in the sandbox has no exceptions from `io.github.golangsupport`.
10. Milestones: `:plugin:buildPlugin`, install the ZIP into the local IDEA 2026.1.4, work in it
    for a session on a real project.

## UI robot

An end-to-end check of the IDE features in a real sandbox IDE, driven through the
[Remote Robot](https://github.com/JetBrains/intellij-ui-test-robot) server. One robot serves the
whole plugin: `tools/ui-robot/robot.py`, the `scripts/*.js` helpers with `prelude.js` and
`scripts/session.sh` (the plugin's own checks: markers, structure, targets, debugger, ...) and
`tools/ui-robot/autotest.py` (this scenario, steps 1-16, and the performance mode P1-P9). It is a
developer tool, not part of `build` or CI. Since step 10 of the migration the former
`tools/psi-ui-robot` (port 8084) is merged into it: one sandbox, one port.

```
python tools/ui-robot/autotest.py                # starts runIdeForUiTests, runs all steps, exits the IDE
python tools/ui-robot/autotest.py --attach       # uses a sandbox already running on port 8083 and keeps it
python tools/ui-robot/autotest.py --attach --steps 6,9
python tools/ui-robot/autotest.py --help         # the options
./gradlew.bat runIdeForUiTests --no-configuration-cache [-ProbotPort=8090]   # the sandbox alone (ROBOT_PORT=8090 for robot.py)
python tools/ui-robot/robot.py wait|windows|shot|find|js|script|openfile|breakpoint|run ...   # manual driving
. tools/ui-robot/scripts/session.sh              # Git Bash helpers: robot_js, invoke, setting, openfile, state, ...
```

- Port 8083 by default (`ROBOT_PORT` and `-ProbotPort=` override it). 8082 belongs to the
  sandbox of idea-dotnet-support; `robot.py` refuses it and refuses an IDE whose config path is
  not a sandbox of this repository (`.intellijPlatform/sandbox/idea-golang-support/...`).
  `--attach` with nothing listening on the port fails at once.
- The log checks read `.intellijPlatform/sandbox/idea-golang-support/IU-*/log_runIdeForUiTests/idea.log`
  of the checkout the script lives in (`SANDBOX_LOG` overrides the path); `--cold` deletes
  `index` and `caches` of the `system_runIdeForUiTests` next to it.
- The scenario project `tools/ui-robot/project-psi` (its own `go.mod`, `main.go`, `util/util.go`,
  `broken.go` with deliberate problems, `fmtcheck/misformatted.go.txt`; not the playground: the
  steps check these exact files) is copied to `%TEMP%/gopsi-ui-project` and opened from there.
  Steps restore file texts through the editor, so `--attach` runs can be repeated.
- Output: `build/ui-robot/report.md` (a PASS/FAIL table and the text read from the IDE as
  evidence) and `build/ui-robot/NN-step.png`, painted by an IDE component (never a desktop
  screenshot). Steps: open the project and smart mode; plugin line and no go-psi exceptions in
  `idea.log` (checked again at the end); highlighting of a valid file; exact problems of
  `broken.go`; quick fixes; completion; navigation, implementations and gutter markers; Find
  Usages; rename (in place and through the interface prompt and dialog); structure view;
  folding; Reformat Code against `gofmt`; Quick Documentation and Parameter Info; typing and
  Go to Declaration latency in a copy of `net/http/server.go`; close and exit.
- Pitfalls met while writing it: model changes on the EDT must use a non-modal
  `ModalityState` (under `any()` TransactionGuard logs errors and the daemon stalls); a modal
  dialog blocks every non-modal EDT call, so dialogs are read and clicked with `edtAny`; writing
  a file that is open in the IDE raises the modal "File Cache Conflict" dialog; a script that
  throws or returns a Rhino `ConsString` makes the server answer HTTP 500 with an empty body
  (`autotest.js()` wraps the script and returns a `java.lang.String`); a lookup is hidden when
  the editor loses focus, and typing runs inside a `CommandProcessor` command.

### Performance mode

Live editing latency in the same sandbox, as baselines for optimisation work (no thresholds:
the in-process benchmarks gate regressions; this mode shows what a user feels).

```
python tools/ui-robot/autotest.py --perf --cold      # wipe the sandbox's index/caches, start, P1-P9, exit
python tools/ui-robot/autotest.py --perf             # same, indexes on disk kept (first open is "disk-warm")
python tools/ui-robot/autotest.py --perf --scenario  # perf first, then every functional step
python tools/ui-robot/autotest.py --attach --perf --steps 2   # on a running sandbox (no first-open number)
python tools/ui-robot/perf_compare.py build/ui-robot/perf-A.json build/ui-robot/perf-B.json
```

- Output: `build/ui-robot/perf.md` (tables), `build/ui-robot/perf.json` (machine-readable:
  `meta`, `measurements.<key>.{samples,median,max,min,n,failed,what,unit}`, `heap`, `notes`,
  `checks`) and a copy `perf-YYYYMMDD-HHMMSS.json` per run; `report.md` gets one PASS/FAIL line
  per P-step. Screenshots only when a P-step fails.
- Everything is timed inside the IDE with `System.nanoTime` (the robot's script thread or the
  EDT), never across HTTP. Daemon timings come from a `DAEMON_EVENT_TOPIC` listener
  (`daemonStarting`/`daemonFinished` per editor and the platform's annotator statistics); it is
  a `java.lang.reflect.Proxy`, because Rhino's interface adapters do not override default
  methods. "Daemon finished" = the last `daemonFinished` for the editor after the action, and
  `isAllAnalysisFinished` with nothing pending. Each measurement runs 5 times (P1: 20
  keystrokes); tables give median, max, min.
- P1 typing, in a copy of `net/http/server.go` (`httpsrv/`, in its package only with the small `perfextra.go` of P8, so the
  checker reports the other `net/http` files' names as undefined). Per keystroke: a line
  `_ = 1` is inserted as the first statement of a function body (20 different functions),
  the daemon goes quiet, then `2` is typed through `TypedAction` inside a command.
  `key_to_daemon_finished` (includes the platform's autoreparse delay, 300 ms by default,
  `meta.autoReparseDelayMs`), `key_to_daemon_start`, `daemon_run`, `annotator_gopsi`
  (`GoSemanticHighlightingAnnotator` start to finish in that run), `key_edt` (the handler on
  the EDT), `key_roundtrip`, `edt_stall_max` (longest EDT round trip while the daemon runs).
  Burst: 10 keys 60 ms apart, last key to daemon finished. Attribution with the daemon timer off:
  `attr_commit_reparse` (commitDocument after the insert), `attr_check_after_edit`
  (`GoSemanticService.check` of the whole file right after the edit), `attr_check_warm`.
- P2 top-level edit: package `perfpkg` (a.go, b.go, c.go). b.go is the open editor; a.go's
  document (not shown) gets a third parameter on `Compute`, then loses it again. Time until
  b.go's daemon finished with the `not enough arguments` errors present / gone.
- P3 completion in `perfcompl/compl.go`: `r.` on a `*http.Request` and an empty statement line;
  basic completion through `CodeCompletionHandlerBase` as the action creates it, until the lookup
  is shown with items. `*_first` is the first invocation (the first in the IDE session for
  `member` without `--attach`), `*_warm` the next 5.
- P4: Go to Declaration on `c.readRequest` (same file) and `context.WithCancel` (into GOROOT)
  until the caret/editor moved; Quick Documentation computed in a read action and the
  `QuickJavaDoc` popup until it shows the doc text; `ReferencesSearch` for type `conn` and for
  `context.WithCancel` in project and project+libraries scope; the Show Usages popup until it
  has rows and until its row count stops changing.
- P5: `CodeStyleManager.reformat` of server.go in a write command (already gofmt-formatted, and
  with every leading tab removed, result checked against the original), and the `ReformatCode`
  action on the unindented file until the text equals the original.
- P6: five GOROOT files never opened before (`go/types/expr.go`, `call.go`,
  `go/parser/parser.go`, `net/http/transport.go`, `runtime/proc.go`, each copied into its own
  directory under `open/`): `openTextEditor` to daemon finished; then expr.go closed and
  reopened 5 times.
- P7 indexing: from the open call (`ProjectUtil.openOrImport`), polled every 100 ms:
  `first_smart` = first time initialized and not dumb; `goroot_indexed` = the last switch to
  smart mode that was followed by 5 s of uninterrupted smart mode while
  `$GOROOT/src/fmt/print.go` is in a library of the project; then 10 s more to catch late
  rescans (`dumb_in_next_10s`). Index queries are not used as the criterion: right after a cold
  start they answered for GOROOT files before the GOROOT scan had even finished, and scanning
  runs partly outside dumb mode (a gap of about 1 s between the project and the GOROOT scan). `idea.log` after a
  marker line gives the scans (reason, scanned files, files for indexing), `Unindexed files
  update took` and the dumb-mode time. `first_open` is step 1 (cold with `--cold`, otherwise
  indexes from the previous session are on disk); `warm_reopen` closes and reopens the project
  5 times in the same session.
- P8 declaration and sibling edits, server.go open: a keystroke in a top-level `var` added at
  the end of server.go (`decl_key_*`, an out-of-body change of the open file); a body edit and a
  signature edit in `httpsrv/perfextra.go` (same package, not shown, nothing in server.go uses it),
  each reverted after the measurement, until server.go's daemon finished (`0` when the daemon did
  not start on server.go within 3 s) plus the go-psi annotator time and the daemon runs per edit.
  Attribution with the daemon timer off: `GoSemanticService.check(server.go)` warm and right after
  each of the three edits; the gap to `attr_check_warm` is what the edit invalidated.
- P9 memory: seven GOROOT files never analysed in the session (`mem/`, copies of go/types,
  go/printer, encoding/json, text/template, net/http and cmd/compile files, about 16k lines),
  all editors closed. Used heap after GC with their ASTs loaded and held (`ast_mb`), then after
  `check()` on each with the ASTs still held: `cache_retained_mb` is the difference, i.e. the
  semantic caches plus the library PSI/stubs the checks loaded. Single GC-based samples (a few
  MB of noise); only meaningful on the first run in an IDE session (the scratch project is fresh
  without `--attach`). `check_cold` per file and its total, `check_warm` again.
- Order: P1-P6, P8, P9, then P7 (it closes and reopens the project).
- Heap: used heap after three `System.gc()` (MemoryMXBean) at the start, before P7 and at the end.
- Comparing: `perf_compare.py OLD NEW` prints old/new median, the median change and old/new
  min..max. A median more than 20 % (`--threshold`) and more than 20 ms slower (5 MB larger for
  MB metrics) is marked SLOWER (LARGER) and the exit code is 1, unless the min..max ranges of the
  two runs overlap (`(noise)`); a metric with one sample per run only gets `SLOWER?` and never
  fails. Live numbers move with the machine's load: compare runs on the
  same machine, run each side twice, and read a change as real only when it is larger than the
  spread of two runs of the same build. Two back-to-back runs of the same build on the
  development machine (about 400 s each): the daemon medians of P1, P2 and P8 within 5 %, the
  `check()` attributions and P5/P6 within 10-20 %, metrics of a few ms (goto, docs, completion
  warm) up to 30 %; single samples (`*_first`, P7 first open, P9 `check_cold_total`) moved by
  20-70 %, `cache_retained_mb` by 7 % (90 vs 84 MB); maxima are not comparable.
- Lint: `uvx ruff check tools/ui-robot` (`tools/ui-robot/ruff.toml` allows %-formatting).

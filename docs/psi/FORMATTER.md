# Formatter (Phase 6b)

`go-psi-ide/src/main/kotlin/io/github/golangsupport/ide/formatter`. Goal: Reformat Code
produces exactly what `gofmt` produces, without calling `gofmt`, and only ever changes whitespace.

## Design

```
GoFormattingModelBuilder (lang.formatter)
 ├─ GoLayout.cached(file)                       printer/ — the gofmt layout, cached per file
 │    GoSource      leaves (tokens + comments), line table, inserted-semicolon offsets, error flag
 │    GoAstBuilder  PSI -> GoAst (a mirror of go/ast with offsets)
 │    GoPrinter     port of go/printer (printer.go + nodes.go): tokens, '\t' '\v' '\n' '\f' cells
 │    GoAlignmentStrategy + GoTrimmer   port of text/tabwriter (+ go/printer's trimmer)
 │    => for every leaf: the exact whitespace gofmt puts before it (complete or partial layout)
 ├─ GoSegmentRootBlock  Reformat Code with a complete layout: flat leaf blocks, one per run of
 │                      tokens whose whitespace already is gofmt's
 ├─ GoBlock          one block per non-empty PSI node (indent requests, partial layouts, Enter)
 ├─ GoFormattingModel  PsiBasedFormattingModel that never deletes inserted semicolons and never
 │                     re-indents the inside of a block; text-only whitespace check
 └─ GoSpacingBuilder   token rules used only where there is no layout (inside broken declarations)
GoImportSorter (preFormatProcessor)   sorts parenthesised import groups like ast.SortImports
GoFileEdgesPostFormatProcessor (postFormatProcessor)   no whitespace before the first token,
                                                       exactly one line feed after the last
GoLanguageCodeStyleSettingsProvider / GoCodeStyleSettingsProvider   tabs, size 4
```

### Why a printer port and not block rules

gofmt's output is not a function of local token pairs: binary-expression spacing depends on the
precedence mix of the whole expression and its nesting depth (`cutoff`/`diffPrec`), alignment is
done by `text/tabwriter` over *cells* (a column block is a run of consecutive lines that have a
cell in that column; blank lines, `\f` section breaks and indentation changes end it), and many
decisions use node sizes (`exprList`'s geometric-mean ratio for key/value alignment, one-line
function bodies up to 100 columns, one-line struct/interface literals up to 30). A
`SpacingBuilder` plus `Alignment` objects cannot express these, so the printer is ported and the
platform engine is used only to apply the result.

### How the engine applies the layout

The layout gives, for each leaf, a gap string such as `" "`, `"\n\t\t"` or `"\n\n\t"`.

- `GoBlock.getSpacing(a, b)` looks up the gap before `b`'s first leaf: with line breaks it returns
  `Spacing.createSpacing(0, 0, n, false, 0)` (exactly `n` line feeds), otherwise exactly that many
  spaces. Mid-line whitespace is always spaces (tabwriter pads with blanks), so `USE_TAB_CHARACTER`
  never turns alignment into tabs (the engine forces spaces for whitespace without line feeds).
- Indentation: the engine computes a line's indent as the sum of the `Indent`s of the line-start
  blocks above it. The outermost block starting at a line-start token gets
  `Indent.getSpaceIndent(cols - anchorCols)`, where `anchorCols` is the line indentation of the
  nearest ancestor block whose first token starts a line (the file root counts as column 0). With
  `TAB_SIZE` columns per tab this yields exactly gofmt's tabs.
- Inserted semicolons (`SEMICOLON_SYNTHETIC`, text `"\n"`) are tokens, not whitespace. They are not
  blocks, so the engine sees them as part of the (whitespace-only) text between two blocks;
  `GoFormattingModel.replaceWhiteSpace` maps the first line feed of the new whitespace onto the
  token and edits only the whitespace leaves before and after it. The layout check guarantees
  every gap that holds an inserted semicolon keeps a line break.
- No `Alignment`/`Wrap` objects are used: alignment is already in the gap strings, gofmt never wraps.
- Reformat Code with a complete layout does not build the PSI-shaped tree: `GoSegmentRootBlock`
  gives the engine a flat list of leaf blocks, each a maximal run of leaves whose source
  whitespace already equals the layout's (a gofmt-clean file is one block). Segments that start a
  line get `Indent.getSpaceIndent(cols)` relative to the file block; spacing between segments is
  the layout gap. The engine's per-block work (wrappers, whitespace checks, indent bookkeeping)
  then scales with the number of changes instead of the number of PSI nodes. Formatter on/off
  tags still work: the platform splits the range by comment text before building blocks. The
  PSI-shaped `GoBlock` tree is still used for indent-only requests (Auto-Indent Lines, line indent
  queries), whose `getChildAttributes` needs the structure, and for partial layouts. Both trees
  give the same result: every golden case runs through both, and a perturbed-GOROOT check
  (indentation stripped, blanks doubled; 461 files) gave identical output.
- `GoFormattingModel` implements `FormattingModelWithShiftIndentInsideDocumentRange` with no-op
  shifts: when a multi-line leaf block moves, the editor's document model would otherwise shift
  the lines inside it (a raw string, a `/* */` comment, or a segment), which gofmt never does.

Range reformat, caret and fold preservation, reformat-on-save and Auto-Indent Lines work through
the normal engine paths. Enter (`ADJUST_INDENT_ON_ENTER`) does not compute the layout: indents
there are structural (`getChildAttributes`: one level inside `{}`/`()`/`[]` bodies, case bodies
after `:`, a new line right after a case clause continues its body; `isIncomplete` for unclosed
constructs).

### Alignment groups (GoAlignmentStrategy)

The printer emits cells exactly where go/printer does:

- struct fields: `name \v type \v tag`, plus extra `\v`s before a line comment so that comments of
  fields with and without tags line up (`extraTabs`); a single-field struct uses blanks;
- `const`/`var` groups: `names \v type \v = values`, with `keepTypeColumn` deciding per run
  whether the type column is kept; `type` groups: `name \v type`;
- key/value elements of multi-line composite literals: `key: \v value`, only for elements that
  start a line and fit on one line; a new section (`\f`) starts when the key size differs too
  much from the geometric mean of the previous keys (`exprList`, ratio 2.5, sizes <= 40 always
  align);
- trailing comments: `\t` before a comment on the same line as code, so consecutive trailing
  comments align (and `writeCommentPrefix` drops pending blanks before it);
- `func` one-liners in a declaration list are separated from their header by `\v`.

Groups end where tabwriter ends a column block: an empty line (a line with a single cell flushes),
a formfeed (go/printer uses `\f` after a multi-line field/spec/statement - `linesFrom(line) > 0` -,
before and between comment lines on their own, at section starts of lists, around labels), a line
with fewer cells (different indentation means different leading empty cells) and the end of an
escaped multi-line literal (`endAlignment`). Widths count code points; empty columns are
discarded (`DiscardEmptyColumns`); leading empty cells are written as tabs (`TabIndent`).

### Deviations forced by "whitespace only"

| gofmt does | here |
|---|---|
| drops explicit `;` between statements, after specs, in `for ;; {` | kept, glued to the previous token |
| drops a trailing `,` before `)`/`]`/`}` on the same line | kept |
| strips redundant parentheses (`if (x) {`, `((x))`, `func f() (int)`, `(T)` params) | kept, printed as parentheses |
| trims trailing blanks in `//` comments, re-indents `/* */` comment lines, reformats doc comments (`go/doc/comment`) | comment text never changes |
| normalises number literals (`0X1F` -> `0x1F`) and import path quoting | literals unchanged |
| removes duplicate imports while sorting | duplicates kept |
| rewrites `// +build` lines to match `//go:build` | comments unchanged |

On gofmt-clean input none of these apply.

## Layout cache

`GoLayout.cached(file, settings)` keeps the layout in a `CachedValue` on the file, dependent on the
file (its modification stamp) and on `CodeStyleSettings.modificationTracker` (the layout itself
does not depend on settings; the tracker is a cheap safety net). Repeated requests on an unchanged
file (reformat-on-save, range reformat, Auto-Indent Lines) reuse it; Enter never computes a
layout. A request that changes the file invalidates it, so the next request recomputes once and
later ones reuse that. The cached layout holds the leaf offsets and gap strings (gaps of the form
`"\n" + tabs` are shared instances), not the formatted text. `GoLayout.computationCount()` is the
test hook.

## Partial layout and fallback

When the file has `PsiErrorElement`s (or the printer rejects some construct), `GoLayout.compute`
returns a *partial* layout: the top-level units (package clause, import list, declarations) are
split into maximal runs of consecutive error-free units, and each run is printed on its own as a
declaration list (the run with the package clause as a file), over a slice of the file's leaves.
Inside a run every gap is gofmt's, including blank-line normalisation and alignment between the
run's declarations; a clean function next to a broken one is formatted exactly as gofmt formats
the file without the broken one. The gap before the first leaf of a run and everything in broken
units are unknown: there indents are structural and spacing comes from `GoSpacingBuilder` (blanks
around assignment and comparison operators, after commas and keywords, none inside brackets or
before calls/indexes). Inserted semicolons keep their line break in this mode too. A block uses
the layout only if all its leaves lie in one run (`GoLayout.covers`). Appending a broken function
to every fifth GOROOT file (921) left everything before it as gofmt prints it.

## File edges

The engine does not touch the whitespace after the last block. `GoFileEdgesPostFormatProcessor`
(runs when the formatted range reaches the first or the last token) removes whitespace before the
first token or comment and makes the text after the last one exactly one line feed. The line feed
after a token like `}` is an inserted semicolon and is kept as the final line feed; when the file
has no line feed at the end, the added leaf gets the token type the lexer gives it there
(SEMICOLON_SYNTHETIC or WHITE_SPACE), so the tree matches what a reparse produces. Trailing
blanks inside the file are gaps of the layout, which never has blanks before a line feed.

## Performance

`./gradlew :go-psi-ide:benchmark --tests "*GoFormatterBenchmark*"`: Reformat Code of
`net/http/server.go` + `go/printer/nodes.go` (gofmt-clean, 0.194 MB, fresh PSI every iteration).
Before and after were run alternately in one session on the same machine (other Gradle builds
running, so single runs swing by up to 30%):

| | median | ms/MB |
|---|---|---|
| before | 130-139 ms | 669-718 |
| after | 50-56 ms | 255-289 |

Profiling with JFR (`-Pgopsi.benchmark.jvmArgs=-XX:StartFlightRecording=filename=<file>,settings=profile`
passes JVM options to the benchmark JVM) showed, and the changes removed:

- the engine's per-block work was half of the time: one block per PSI node, and
  `WhiteSpace.coveredByBlock` looked up the PSI element and language at every block through
  `PsiFile.findElementAt` (a linear scan over the file's top-level children). Now segments, and a
  text-only whitespace check (`GoFormattingDocumentModel`, delegating anything non-blank);
- `GoImportSorter` walked the whole PSI for import declarations: now only the file's top level;
- layout: offsets as a running sum instead of `getStartOffset` per leaf, binary searches over
  offset arrays instead of boxed hash maps, a remembered line in `lineFor`, the error check folded
  into the leaf walk, no allocation per whitespace flush, piece indices in an `IntArray`, the
  tabwriter on flat primitive arrays, `nodeSize` without a trimmer pass, padding written without
  per-character strings, and U+0000 instead of go/printer's U+FFFF as the escape character (U+FFFF
  made every printer and tabwriter buffer UTF-16);
- `GoFormattingModel` read the whole file text after every whitespace change (quadratic on files
  with many changes); it now reads only the leaves around the change.

After that, platform work around the formatter remained. `CodeFormattingData` enumerates
language injections for every injection host. Go comments were `PsiCommentImpl` (an injection
host), so every reformat probed all comments and, on fresh files, created PSI wrappers for the
whole tree: about 40% of the benchmark. go-psi-core now registers `GoASTFactory`
(`lang.ast.factory`), which creates comments as `GoCommentImpl`. It is a `PsiComment` (same
visitor callback, same `PsiComment(LINE_COMMENT)` dump, references from providers so URLs stay
clickable) but not a `PsiLanguageInjectionHost`. Directives (`//go:build`, `//go:embed`) remain
plain comments, and nothing in go-psi injects into comments. TODO, commenter, folding, doc
comments and parser goldens are unchanged. `ParsingTestCase` does not load descriptors, so
`GoParsingTestCase` registers the factory itself. Measured alternately in one busy session:

| | median | ms/MB |
|---|---|---|
| `PsiCommentImpl` comments | 65-69 ms | 335-354 |
| `GoCommentImpl` comments | 36-48 ms | 183-247 |

That is a 30-45% cut; the stored threshold went from 49.5 to 35.5 ms. In a JFR profile only
about 5% of the samples still touch injection code, through per-file checks. The layout is now
the larger part (about 55 ms/MB warm: leaf walk, AST, printer and tabwriter roughly a quarter
each).

## Testing

- `GoFormatterTest`: 28 golden cases `testData/formatter/<name>.go` -> `<name>.after.go`; the
  goldens are produced by the real `gofmt` (a missing golden is created on the first run and the
  test fails once). Each case checks the token stream is unchanged (as a multiset for import
  sorting), that formatting the golden again is a no-op, and that the PSI-shaped block tree gives
  the same result as the segments. Plus registration, code sample, editor Reformat action, range
  reformat, kept `;`/`,`/parentheses, syntax-error fallback, Auto-Indent Lines and Enter
  indentation.
- `GoFormatterLayoutTest` (11): the layout is reused by repeated requests (Reformat Code, range
  reformat, Auto-Indent Lines) and invalidated by an edit and by a settings change; partial layout
  (`testData/formatter/partial/partialLayout.go`: the clean declarations around a broken function
  equal `partialLayout.clean.go`, gofmt's output of the file without it; runs keep gofmt's blank
  lines); file edges (one line feed at the end, an added one with the lexer's token type, none
  before the first token, also through the editor action, untouched by a range away from the
  end); a range inside one function changes only its lines; the reformat-on-save path
  (whole-file `reformatText` through the core formatting service, nothing recomputed on the next
  save).
- `GofmtCorpusTest` (`./gradlew :go-psi-ide:corpusTest`): Reformat Code on every `.go` file under
  `$GOROOT/src` (no `testdata`, files <= 200 KB, `cmd/` only with
  `-Pgopsi.formatter.corpus.includeCmd=true`) must equal gofmt's output: the original file, or
  `gofmt <file>` for the few files `gofmt -l` lists. `-Pgopsi.formatter.corpus.mode=layout`
  checks the printer alone; `-Pgopsi.formatter.corpus.filter=<substring>` restricts the files.
  Metrics: `testData/metrics/goroot-src-gofmt.json` (`different` may only go down).

## Status and known gaps

GOROOT/src (Go 1.27.1), Reformat Code through `CodeStyleManager`, compared with gofmt:

| set | files | identical | different |
|---|---|---|---|
| `src` without `cmd/` (the metrics file) | 4621 | 4621 | 0 |
| `src/cmd` (`includeCmd=true`, `filter=cmd/`) | 2155 | 2155 | 0 |

GOROOT is not entirely gofmt-clean (`gofmt -l src` lists 190 files, most under `testdata`); for
the listed files the expected text is gofmt's output, and those match too. Development history of the difference classes, for future
regressions: generic composite literal types (`T[int]{...}`: the unpinned type arguments are
flattened into the COMPOSITE_LIT node), interface methods sized as function types (go/printer
counts a `func` keyword that is not in the source), inserted semicolons around the engine
(`GoFormattingModel`), and specs whose PSI node includes a bound doc comment (import runs).

Known gaps (none occur on gofmt-clean code):

- with syntax errors, the whitespace between a broken top-level declaration and its neighbours
  follows the fallback rules (gofmt would normalise blank lines there), and alignment that gofmt
  would carry across a broken declaration (trailing comments of adjacent one-line declarations)
  is computed per run;
- a reformat that changes the file invalidates the cached layout, so the next request recomputes
  it once;
- reformatting a file with many changes through the PSI-based model (non-physical files, as in
  the tests) is dominated by the platform's document/PSI consistency check after each whitespace
  change (about 120 ms for a 20 KB file with every line changed); the editor path edits the
  document instead;
- the deviations in the table above.

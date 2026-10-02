# IDE features (go-psi-ide)

Extensions contributed by `go-psi-ide` (`META-INF/go-psi-ide-*.xml`, one descriptor per feature: editor, formatter, navigation, refactoring, documentation, completion, inspections), grouped by phase. All
behaviour is PSI/semantic based (`GoSemanticService`, stub indices); no LSP. Code lives in
`io.github.golangsupport.ide.<feature>`.

## Phase 6a/6b: editor basics and formatter

| EP | Class | Notes |
|---|---|---|
| `lang.syntaxHighlighterFactory`, `colorSettingsPage` | — | none here: the root module's `lang.GoSyntaxHighlighter` / `GoColorSettingsPage`, keys `lang.GoColors` (go-psi-core) |
| `lang.braceMatcher`, `lang.quoteHandler`, `lang.commenter`, `indexPatternBuilder` | `editor.*` | |
| `lang.findUsagesProvider` | `editor.GoFindUsagesProvider` | words scanner + kind names |
| `lang.foldingBuilder` | `folding.GoFoldingBuilder` | |
| `lang.psiStructureViewFactory`, `breadcrumbsInfoProvider` | `structure.*` | |
| `itemPresentationProvider`, `gotoSymbolContributor`, `gotoClassContributor` | `navigation.*` | stub-only presentations |
| `lang.formatter`, `langCodeStyleSettingsProvider`, `codeStyleSettingsProvider`, `preFormatProcessor` | `formatter.*` | gofmt port, see `docs/FORMATTER.md` |

## Phase 6c: navigation, usages, rename, documentation

### Navigation (`ide.navigation`)

| EP | Class | Behaviour |
|---|---|---|
| (references) | semantic `GoReferenceProviderImpl` | Go to Declaration works through references: locals, package scope (stubs), imports, `pkg.X` into GOROOT/modules (`fmt.Println` -> `$GOROOT/src/fmt/print.go`), fields/methods via types, struct literal keys, labels. The import path string resolves to the package directory. `C.x` navigates to `import "C"` (cgo sentinel). No `GotoDeclarationHandler` is needed. |
| `typeDeclarationProvider` | `GoTypeDeclarationProvider` | Go to Type Declaration from a var/const/field/param/receiver/function: through `*T`, `[]T`, `[N]T`, `chan T`, map values and a single function result to the named type's `GoTypeSpec`; instantiated generics go to the origin, type parameters to their definition. |
| `definitionsScopedSearch` | `GoImplementationSearch` | Go to Implementation: interface type spec -> concrete types whose `T` or `*T` implements it; interface method spec (also from a call site `r.Read()`) -> implementing methods. |
| `targetElementEvaluator` | `GoTargetElementEvaluator` | Go to Implementation on an interface/method spec lists only implementations, not the interface itself. |
| `codeInsight.gotoSuper` | `GoGotoSuperHandler` | Go to Super (Ctrl+U): method -> interface method specs it implements; concrete type -> interfaces it implements (project + libraries). |
| `codeInsight.lineMarkerProvider` | `GoImplementationLineMarkerProvider` | Slow-pass gutters: "Is implemented by" on interfaces and their methods (implementations in project content), "Implements" on concrete types and "Implements method in" on methods (interfaces in project + libraries). Existence stops at the first hit and is cached per owner (`CachedValue<Boolean>`, deps: project out-of-block + library trackers, model, roots); targets are computed when the popup opens. Not DumbAware (indices). |

Implementation search (`GoImplementations`): candidates come from the stub indices
(`GoMethodFingerprintIndex` by `name/arity` of the interface method with the fewest candidate
files; `GoMethodSpecFingerprintIndex` for the reverse direction); receiver/interface type specs
are looked up by name in the candidate's package directory (`GoTypesIndex`, directory scope);
only then `GoSemanticService.implements` decides. Interfaces without methods, constraint
interfaces (type terms) and generic interfaces are skipped. Everything runs on stubs: a test
asserts that markers and search do not load the AST of the implementing file.

### Usages (`ide.usages`)

| EP | Class | Behaviour |
|---|---|---|
| (core) `getUseScope` | core `GoUseScopes` | imports: file; labels and body-local declarations: enclosing function; params/receivers/type params: declaring function, literal, function type, interface method or type spec; unexported package-level: package directory; exported: project + libraries. |
| `referencesSearch` | `GoReferencesSearch` | Only for imports whose local name differs from the spec name (`math/rand/v2` used as `rand.X`, `gopkg.in/yaml.v3` as `yaml.X`); everything else is the platform word-index search restricted by the use scope. |
| `usageTypeProvider` | `GoUsageTypeProvider` | Call, Method call, Method value, Field read/write, Composite literal key, Type reference, Embedded field, Type conversion, Package qualifier, Import, Label reference, Value read/write. |
| `readWriteAccessDetector` | `GoReadWriteAccessDetector` | `x = v`, `for x = range`, `case x = <-ch` write; `x += 1`, `x++` read-write; literal keys write; initialized declarations are write declarations. |
| `findUsagesHandlerFactory` | `GoFindUsagesHandlerFactory` | Find Usages of a method implementing interface methods asks whether to include the interface methods (calls through interfaces resolve to the method spec). |
| `highlightUsagesHandlerFactory` | `GoHighlightExitPointsHandlerFactory` | On `func`/`return`: the function's `return` statements and statement-level `panic(...)` (nested literals excluded). On `break`/`continue`: the target `for`/`switch`/`select` keyword and all jumps to it (labels honoured). On `for`/`switch`/`select`: the keyword and its jumps. DumbAware. Identifier highlighting itself comes from references. |

### Rename (`ide.rename`)

| EP | Class | Behaviour |
|---|---|---|
| `lang.elementManipulator` | `GoReferenceExpressionManipulator`, `GoTypeReferenceExpressionManipulator`, `GoLabelRefManipulator`, `GoImportSpecManipulator` | Replace the identifier leaf (or the import path) of a reference on rename. |
| `lang.refactoringSupport` | `GoRefactoringSupportProvider` | In-place rename when the use scope is local (locals, params, receivers, labels, imports); dialog otherwise. |
| `lang.namesValidator` | `GoNamesValidator` | Go identifiers (`[\p{L}_][\p{L}\p{Nd}_]*`), the 25 keywords. |
| `renameInputValidator` | `GoRenameInputValidator` | Rejects keywords/non-identifiers with a message. |
| `renamePsiElementProcessor` | `GoRenameMethodProcessor` | Renaming a method that implements project interface methods asks "Rename the interface method and all its implementations?" (Yes: the interface method spec is renamed with every implementation, transitively; No: only this method). Renaming an interface method spec renames all project implementations. Library code is never renamed. |

Struct literal keys follow field renames through their references (`GoFieldKeyReference`);
promoted fields (`w.Name` through an embedded struct) follow too. Package rename is not
supported.

### Documentation (`ide.documentation`)

| EP | Class | Behaviour |
|---|---|---|
| `platform.backend.documentation.psiTargetProvider` | `GoDocumentationTargetProvider` / `GoDocumentationTarget` | Ctrl+Q and hover for every named declaration, import specs and package directories. |
| `codeInsight.parameterInfo` | `GoParameterInfoHandler` | Signature help inside call arguments; current parameter by comma count; variadic tails highlight the last parameter; generic functions show `[T any, U any]` when not instantiated; method values and GOROOT functions. |
| `expressionTypeProvider` | `GoExpressionTypeProvider` | Type Info (Ctrl+Shift+P) for the enclosing expressions, via `GoSemanticService.typeOf`. |

Documentation layout: a gopls-style definition (`GoDocSignature`): `func Println(a ...any) (n
int, err error)`, `func (b *Buffer) Write(p []byte) (n int, err error)`, `type T struct {...}`
(source shape in the popup, `{...}` in the hover hint) followed by the type's exported methods,
`var x int`, `const Pi untyped float = 3.14` (value from constant evaluation), `field X int`,
`func (Reader) Read(...)` for interface methods, `type T any` for type parameters, `label L`,
`package fmt`. The empty interface prints as `any`. Then the doc comment (`GoDocComment`:
go/ast attribution - comment group above the declaration, the group's comment for single-spec
declarations, trailing line comments for fields and specs; `//go:` style directives dropped)
converted by `GoDocHtml` (port of the essentials of `go/doc/comment`: paragraphs, `# Heading`,
indented code blocks, bullet and numbered lists, `[Text]: URL` link definitions, `[Name]` doc
links as code, bare URLs, ` `` `/`''` quotes). Sections: `Struct:`/`Interface:` for members,
`Package:` (import path from the project model) and `File:`. Packages show the package doc from
`doc.go` or the first file with a package comment. Doc links are not navigable (no
`DocumentationLinkHandler` yet).

### Tests

`navigation.GoNavigationTest` (13), `navigation.GoImplementationsAstLoadingTest` (1),
`usages.GoFindUsagesTest` (4, golden `testData/usages/kinds/usages.txt`),
`usages.GoHighlightUsagesTest` (6), `rename.GoRenameTest` (13, `testData/rename/*_after.go`),
`documentation.GoDocumentationTest` (12, golden `testData/documentation/doc/expected.html`).
Tests that resolve into the standard library extend `GoSemanticIdeTestBase`, which pins the
toolchain to `-Dgopsi.goroot` (linux/amd64, cgo). GOROOT is not indexed in tests (library
roots are off in unit-test mode), so library-side searches are covered with project packages.

### Known gaps

- Implementations of generic interfaces and implementation checks that need instantiation.
- Sub-interfaces (interfaces embedding an interface) are not listed as its implementations.
- Doc links (`[Name]`) are rendered as code, not as navigable links.
- Inlay hints, run line markers and package rename are out of scope.

## Phase 6d: completion (`ide.completion`)

| EP | Class | Behaviour |
|---|---|---|
| `completion.contributor` (`order="first"`) | `GoCompletionContributor` | Basic completion. Providers by leaf pattern: the string of an import spec (`GoImportPathProvider`), a label reference (`GoLabelProvider`), the package clause name (`GoPackageClauseProvider`), any other identifier (`GoIdentifierProvider`). The dummy identifier is the trimmed one, so `x.<caret>(` and `T{<caret>}` parse like the final code. Contributor and providers are `DumbAware`: every source is index-free. |
| `completion.confidence` | `GoCompletionConfidence` | No autopopup in comments, ordinary strings, runes and numbers (`1.` stays quiet); import path strings pop up. |
| `weigher key="completion"` (`goCompletion`, after `priority`, before `prefix`) | `GoCompletionWeigher` | Expected-type match, then a `GoCompletionRanker` score, then scope distance. |
| `weigher key="completion"` (`goAlphabetical`, after `proximity`) | `GoAlphabeticalWeigher` | Alphabetical tie-break for Go items. |
| `io.github.golangsupport.completionRanker` (new EP) | `api.GoCompletionRanker` | Optional re-ranking (docs/ML.md section 2); no implementation in go-psi. |

Tail and type texts of fields, methods, functions and variables are lazy (`GoCandidate.tailSupplier`/`typeSupplier`, rendered by a `LookupElementRenderer` when a row is shown); `GoImportPaths.modules` caches dependency packages on `GoProjectModelTracker` only.

### Position analysis (`GoCompletionContext`)

Computed once per session on the file copy (cached on the copy's leaf), purely syntactic:

| Kind | Where | Offered |
|---|---|---|
| `STATEMENT` | an expression alone at a statement start in a block/clause | values, statement keywords, snippets, unimported packages |
| `EXPRESSION` | operands, arguments, after `=`/`:=`/`return`/`case`/`<-`, unkeyed literal elements, `for x := <caret>` | values, `func map chan struct interface`, `range` in a `for` header, struct keys in unkeyed elements of struct literals |
| `TYPE` | var/field/param/result/conversion/literal types, type arguments, constraints | types, packages, type params, `chan map struct interface func`; `comparable` only in constraints |
| `RECEIVER_TYPE` | `func (r *<caret>` | types of the current package |
| `SELECTOR` / `TYPE_SELECTOR` | after `.` in an expression / a type | members (below); types only in type positions; no keywords |
| `STRUCT_KEY` | `T{K<caret>: v}` | unused fields (map/slice literal keys fall back to expressions) |
| `IMPORT_PATH` | inside `import "<caret>"` | importable paths |
| `LABEL` | after `goto`/`break`/`continue` | labels of the enclosing function; `break`: enclosing `for`/`switch`/`select` labels; `continue`: enclosing `for` labels |
| `PACKAGE_CLAUSE` | `package <caret>` | sibling files' package names, the directory name, `main`, `<name>_test` in tests |
| `SWITCH_BODY` | `switch x {` / `select {` before any clause | `case`, `default` |
| `TOP_LEVEL` | between declarations | `func type var const`, `import` before the first declaration, `package` without a clause |
| `FUNC_NAME`, `NONE` | after `func`, names being declared, comments, strings, numbers | nothing |

Statement flags: `break` only inside `for`/`switch`/`select` clauses, `continue` only in loops,
`fallthrough` in expression switch clauses, `case`/`default` inside clauses, `else` right after
the `}` of an `if` on the same line, `range` after `for` or `for a, b :=`/`=`.

### Candidates

- Scope walk (`GoScopeCandidates`), innermost first, an inner name hides an outer one: block
  declarations before the caret, `if`/`for`/`switch` init statements, range and type-switch
  variables, `select` receive variables, function/literal signatures (receiver, parameters,
  results, type parameters), receiver type parameters (`func (l *List[U])`), type-spec type
  parameters; package level of every file of the package (`GoPackageModel` scope, stub
  accessors; the current file's declarations from the copy); imports (local name, `_` skipped)
  and dot-imported exported members; universe types, `nil true false` (`iota` only in `const`),
  builtin functions with their documented signatures. `init` and `_` are never offered.
- Members (`GoMemberCandidates`): a qualifier resolving to an import gives the package's exported
  package-level declarations (no methods, no `init`); a type name gives method expressions (value
  receiver methods); a value gives fields by promotion depth (shallower hides deeper, same-depth
  duplicates are ambiguous and dropped) and the method set of `T`, or of `*T` when the operand is
  a pointer or addressable (variables, parameters, fields of addressable operands, index
  expressions, `*p`); interface and constraint methods come from the method set. Unexported
  members of other packages are hidden (`GoField.pkgPath`/`GoMethod.pkgPath` vs the current
  package path). An unresolved qualifier that names an importable package (`strings.` without
  the import) lists that package's members and adds the import on insertion.
- Struct literal keys: fields of the literal's struct type (also elided nested literals
  `[]T{{...}}` and `&T` elements), including promoted fields and embedded field names (Go 1.27
  promoted keys), minus keys already used; not offered in positional literals.
- Import paths (`GoImportPaths`): `$GOROOT/src` directories with non-test `.go` files (no
  `internal`, `vendor`, `cmd`, `testdata`, `builtin`), walked once per GOROOT; modules of the
  build list walked once per module directory (immutable cache), main modules walked per
  `GoProjectModelTracker`/VFS-structure change (`internal` allowed only there); nested modules
  and `testdata` skipped. Matching by path start or path element start (`ht` finds `net/http`);
  already imported paths are excluded.
- Unimported packages: names (last path element, `GoScopes.defaultImportName`) that start with
  the typed prefix and are not shadowed or imported; standard library first.

Completion runs on a copy of the file without a directory, so the project model does not see it.
`GoCompletionSemantics` maps elements before the caret to the original file by text range
(identical before the caret) and asks `typeOf`/resolve there; the package scope comes from the
original file with the copy's own declarations. `typeOf` runs only for the qualifier and the
expected-type site (once per session); candidate types are declaration types, which are
stub-based for other files: a var/const of another file shows a type only when it is declared
with one (its initializer is not stubbed). A test asserts that completion does not load the AST
of another file (`setAssertOnFileLoadingFilter`).

### Lookup elements and insertion (`GoLookupElementFactory`)

- Presentation: `GoIdeIcons` icon (platform `AllIcons.Nodes.*` as the root module's `GoDeclarationIcons`, files `GoFileType.icon`), tail text `(a int, b string) error` for functions and methods,
  ` T` for variables/constants/fields/parameters, ` struct`/` interface`/` func`/` type` for
  types, ` (path)` for packages; type text: owning type for members, package for package
  members, `import` for unimported packages. Keywords and `nil true false iota` are bold.
- Calls: `()` with the caret inside, after them when the callee has no parameters; an existing
  `(` is reused; no parentheses when a function value is expected (`apply(double)`).
- Packages: `pkg.` and the member autopopup; unimported ones add the import.
- Struct keys: `Name: ` (an existing `:` is reused).
- Keywords: trailing space (`return `, `if `, `case `), `default:`, `struct{<caret>}`,
  `interface{<caret>}`, `map[<caret>]`, `func(<caret>)`; none for `break`/`continue`/`fallthrough`.
- Auto-import (`GoImportInserter`, text-based): into the first parenthesised import group, inside
  the block (blank-line separated) of the same kind (standard library vs. module paths) at the
  sorted position, or as a new block; a single-line import becomes a group; without imports a
  declaration is added after the package clause.
- Snippets at a statement start inside a function (`GoSnippets`): `iferr` ->
  `if err != nil { return <zero values> }` with the enclosing function's (or literal's) results
  (`0`, `""`, `false`, `nil`, `T{}`/`pkg.T{}`, `*new(T)` for type parameters, `err` for `error`);
  `for range xs` -> `for i, v := range xs {}` (`k, v` maps, `i, r` strings, `v` channels) for
  each visible local/parameter of a rangeable type (integers are not offered); `switch {}` and
  `select {}` skeletons. Lines are indented like the current line.

### Ranking

`GoCompletionWeigher` (registered after `priority`, before `prefix`): (1) expected-type match:
identical (also untyped constants and `T`-returning calls) > assignable (`nil` to nilable types)
> none; the expected type comes from assignment targets, declared var/const types, call
parameters (variadic tail: element type), function results (`return`), the other operand of a
binary expression (`&&`/`||`: bool; shifts: none), composite literal fields/elements/keys,
channel elements of a send, the switch tag of a `case`, and conditions (bool); `any` counts as no
expectation. (2) The score of a registered `GoCompletionRanker`. (3) Scope distance: locals 0,
parameters/receiver 1, keywords/snippets 2, package 3, imported 4, universe 5, unimported 6;
members: direct 0, promoted by depth. `GoAlphabeticalWeigher` breaks ties. The platform's own
start-vs-middle-match classifier runs before all weighers, so the order above applies within
each match class (an exact prefix match of a universe name still precedes a middle match of a
local).

### Tests

`completion.GoScopeCompletionTest` (15), `GoMemberCompletionTest` (19),
`GoKeywordCompletionTest` (19), `GoInsertCompletionTest` (11), `GoRankingCompletionTest` (9),
`GoCompletionEnvironmentTest` (10: import paths from GOROOT and from an on-disk module copied
from `testData/completion/module`, module auto-import, comments/strings, confidence, dumb mode,
stub-only candidates, a 3000-line file with 500 package-level symbols under 300 ms).

### Known gaps

- Tail texts print `byte`/`rune` as `uint8`/`int32` (the type model does not keep aliases).
- Method expressions on `(*T)`, completion of generic instantiation arguments by constraint,
  postfix templates, smart (type-filtered) completion and second-level completion
  (`x.Field.Method` chains) are not implemented.
- `GoCompletionRanker` has no implementation; the ML module is planned (docs/ML.md).

## Phase 6d: inspections, quick fixes, semantic highlighting

Syntax errors are `PsiErrorElement`s highlighted by the platform. Semantic errors come from the
type checker (`GoSemanticService.check(file)`, docs/SEMANTIC.md "Diagnostics") through
inspections, so users can disable them or change their severity.

### Inspections (`ide.inspections`, group "Go")

| Short name | Classes (`GoDiagnostic.code`) | Default | Quick fixes |
|---|---|---|---|
| `GoUnresolvedReference` | `undefined`, `undefined-member`, `unexported`, `unknown-field` | ERROR (unknown-symbol style) | Import `"path"` for an unresolved package qualifier |
| `GoUnusedImport` | `unused-import` | WARNING (greyed) | Remove unused import; Optimize imports |
| `GoUnusedVariable` | `unused-variable` | WARNING (greyed) | Remove variable; Replace with `_ =`; Rename to `_` |
| `GoUnusedLabel` | `unused-label` | WARNING (greyed) | |
| `GoTypeMismatch` | `assignability`, `representability`, `conversion`, `untyped-nil`, `mismatched-types` | ERROR | Convert to `T` (`T(x)`) |
| `GoCallArity` | `call-arity`, `spread`, `return-arity`, `assignment-mismatch`, `multiple-value`, `no-value` | ERROR | |
| `GoDuplicateDeclaration` | `redeclared`, `no-new-variables` | ERROR | |
| `GoGenerics` | `inference`, `constraint`, `type-args`, `generic-no-instantiation`, `cannot-infer` (option can turn it off) | ERROR | |
| `GoChecker` | every other class (operators, indexing, literals, statements, builtins, ...) | ERROR | |

All are enabled by default. `GoDiagnosticsInspectionBase` reports each diagnostic on the element
whose range equals the diagnostic's (or with a range inside the smallest enclosing element).
Messages are the checker's go/types text; continuation lines (`\n\thave (...)\n\twant (...)`)
are joined with `; ` because problem descriptions are single-line.

- One check per file and modification: `GoDiagnosticsCache` keeps the checker result in a
  `CachedValue` on the file depending on the Go trackers (file, out-of-block, project model);
  the result is computed lazily under a lock so parallel inspections share it. Identical
  diagnostics are reported once.
- False positives: the GOROOT corpus gate reports none since 0.0.9 (it was `assignability` 18,
  `cannot-infer` 11). `GoDiagnosticClasses.SHAKY` is empty; `cannot-infer` is on by default.
  `GoInspectionsGorootTest` asserts zero problems from all inspections on 10 GOROOT files.
- Not dumb-aware (the checker resolves through stub indices); library sources are not
  inspected by the platform.

### Quick fixes

- Add import (`GoAddImportFix`, high priority): for `undefined: pkg` on a qualifier `pkg.X` or
  `pkg.T` in a type, one fix per importable package (`GoImportPaths`, standard library first, at
  most 5) whose name is `pkg` and which exports `X`; inserted by the completion's
  `GoImportInserter` (goimports-style groups).
- Remove unused import (`GoRemoveImportFix`): the spec's line, or the whole declaration when it
  becomes empty; a blank line left between blank lines is collapsed.
- Optimize imports: `lang.importOptimizer` `GoImportOptimizer` (Code | Optimize Imports and the
  `GoOptimizeImportsFix`): removes the imports the checker reports unused, then sorts each run of
  specs like gofmt (`GoImportSorter`). Blank-line groups are kept (no goimports regrouping).
- Unused variable (`GoUnusedVariableFixes`): a single-variable statement (`x := v`,
  `var x T = v`) is removed when its values have no calls or receives, otherwise replaced by
  `_ = v`; one of several variables (`a, x := f()`, `var a, x`, `for i, x := range`) is renamed
  to `_` when another variable keeps a name. Header and type-switch variables get no fix.
- Convert (`GoWrapConversionFix`): for `cannot use x (...) as T value in ...` when the target
  type is known from the context (declared variable type, `=` target, function result, call
  parameter incl. variadic elements), the value is typed and converts to `T`; integer-to-string
  conversions are not offered. Types of other packages are written with the file's import name.

### Suppression (`GoInspectionSuppressor`, `lang.inspectionSuppressor`)

`//noinspection GoUnusedVariable` (several ids comma-separated, or `ALL`) on its own line
directly above a statement (in a block or case clause), a top-level declaration (also inside
its doc comment) or an import spec of a group suppresses that inspection inside it; the same
comment before the package clause, separated by a blank line, suppresses it for the file.
Suppress fixes (`GoSuppressByCommentFix`): "Suppress for statement", "Suppress for declaration"
(inserted below the doc comment), "Suppress for file"; an existing `//noinspection` comment
gets the id appended.

### Semantic highlighting (`ide.annotator.GoSemanticHighlightingAnnotator`)

An `Annotator` that colours identifiers by declaration kind and by what references resolve to,
using only the cached resolve (`GoResolver`; struct literal keys through `resolveFieldKey`),
never expression typing directly. Keys (`lang.GoColors` in go-psi-core, the palette of the root module's `GoSyntaxHighlighter` and
colour page): `GO_PACKAGE`, `GO_TYPE_DECLARATION` (the name of a type spec), `GO_TYPE_REFERENCE` (other types, type parameters),
`GO_FUNCTION_DECLARATION`, `GO_FUNCTION_CALL` (functions and methods alike),
`GO_FIELD`, `GO_PARAMETER` (also receivers and named results), `GO_LOCAL_VARIABLE`,
`GO_PACKAGE_VARIABLE`, `GO_CONSTANT`, `GO_LABEL`, `GO_BUILTIN_TYPE`, `GO_BUILTIN_FUNCTION`,
`GO_BUILTIN_CONSTANT` (`true`, `false`, `nil`, `iota`), each falling back to a
`DefaultLanguageHighlighterColors` key. Unresolved identifiers keep the lexer colour. Not
dumb-aware.

### Tests

`inspections.GoInspectionsTest` (17), `inspections.GoQuickFixesTest` (14),
`inspections.GoInspectionsGorootTest` (2), `annotator.GoSemanticHighlightingTest` (2, golden
`testData/highlighting/semantic.txt`). Fixtures: `testData/inspections/*.go` (markup with the
checker's messages), `testData/inspections/fixes/*.go` and `*_after.go`.

### Known gaps

- Missing return: terminating-statement analysis per the spec; reported by `GoMissingReturnInspection` (ERROR).
- Optimize imports does not regroup standard and third-party imports, and does not remove
  duplicate imports.
- Add import does not consider packages outside the build list (no `go get`).

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

Every extension of `go-psi-ide-navigation.xml` asks the application service `ide.GoIdeFeatureGate` at its entry
(`enabled(GoIdeFeature.NAVIGATION | USAGES | IMPLEMENTATION_MARKERS, project)`) and stands down when the answer is no:
providers return null, searches process nothing, factories cannot find usages, the goto-super handler is not valid.
`DefaultGoIdeFeatureGate` (registered in `go-psi-ide-editor.xml`) says yes to everything; a host plugin overrides the
service with its own feature switches so that one source answers at a time, and says no while the IDE indexes. The
platform consults a single `targetElementEvaluator` and a single `codeInsight.gotoSuper` per language, so those two are
registered `order="first"` and, when off, delegate to the next extension registered for Go. `GoIdeFeatureGateTest` covers
the closed gate; the other tests run against the default.

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

### Code vision (`ide.codevision`, `go-psi-ide-codevision.xml`)

| EP | Class | Behaviour |
|---|---|---|
| `codeInsight.daemonBoundCodeVisionProvider` | `GoUsagesCodeVisionProvider` (group `references`) | "no usages" / "1 usage" / "N usages" / "100+ usages" above package-level functions (not `main`, `init` or the test functions of a `_test.go`), methods, type specs and the method specs of package-level interfaces: `ReferencesSearch` in the use scope, stopped after 100 references. A click runs `ShowUsages` with the caret on the name. |
| `codeInsight.daemonBoundCodeVisionProvider` | `GoImplementationsCodeVisionProvider` (group `inheritors`) | "N implementations" above interfaces and their method specs when there is at least one (`GoImplementations` in project content, stubs only, stopped after 100); nothing on the concrete side, where the gutter icon already says "implements". A click runs `GotoImplementation`. |

The anchor is the declaration without its doc comment (the hint sits right above the `func`/`type` line). Both providers follow the
gate group `IMPLEMENTATION_MARKERS` and answer nothing in dumb mode; at most 300 declarations of a file are asked, with a
cancellation check between them. `codevision.GoCodeVisionTest` (7) covers the anchors, the wording, the cap, the closed gate and that
counting implementations keeps the implementing file's AST unloaded.

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
  the block (blank-line separated) of its goimports group (`GoImportGroups`: `"C"`, standard library,
  third-party, main module from `GoModuleGraphProvider`) at the sorted position, or as a new block
  where that group goes; a single-line import becomes a grouped declaration split into groups
  (`import "C"` is never grouped); without imports a declaration is added after the package clause.
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

### Smart, chain and project-member completion (wave 3 of FEATURES.md §11, 0.2.26–0.2.28)

| Where | Class | What |
|---|---|---|
| `completion.contributor` (SMART) | `GoSmartProvider` + `GoSmartLiterals` | Smart completion (Ctrl+Shift+Space) in expressions and after `.`: candidates filtered by `GoLookupElementFactory.smartMatch` against `expectedTypeAt` (functions and methods by their first result; `len`/`cap`/`append`/`new` by rule), plus `LITERAL` items `T{}`, `&T{}`, `make(T)`, `make(T, 0)`, `func(...) R {}`, `""`, `0` written as the file sees the type (no literal for a type of an unimported package). No expected type: the basic set. |
| `completion.contributor` (chains) | `GoChainCandidates` | `x.F.M` / `x.F().M`: roots are locals, parameters and variables with a known type (30); first steps are fields with promotion and parameterless one-result methods (20 per root, 200 expansions); 50 chains. Lookup string is the whole chain; parentheses as for methods. In smart filtered by type, in basic from a 2-character prefix (restart at length 2). Level `UNIMPORTED`. |
| `completion.contributor` (project members) | `GoProjectMemberCandidates` | Bare names to `pkg.Name` from `GoAllPublicNamesIndex` (project scope, prefix ≥ 2, ≤ 200 keys / ≤ 100 items). Import path from `GoPackageResolver.importPathOf(dir)`; skips the current package, `main`, `_test.go`, `vendor`/`testdata`, `internal` per the go rule, imported paths and taken names. Import through `GoImportInserter`; `expectedMatch` from stub declaration types. The host `GoCatalogueCompletionContributor` leaves project entries out when `GoFeatures.native(COMPLETION)`. |

### Tests

`completion.GoSmartCompletionTest` (10), `GoProjectMemberCompletionTest` (6),
`completion.GoScopeCompletionTest` (15), `GoMemberCompletionTest` (19),
`GoKeywordCompletionTest` (19), `GoInsertCompletionTest` (11), `GoRankingCompletionTest` (9),
`GoCompletionEnvironmentTest` (10: import paths from GOROOT and from an on-disk module copied
from `testData/completion/module`, module auto-import, comments/strings, confidence, dumb mode,
stub-only candidates, a 3000-line file with 500 package-level symbols under 300 ms).

### Known gaps

- Tail texts print `byte`/`rune` as `uint8`/`int32` (the type model does not keep aliases).
- Method expressions on `(*T)`, completion of generic instantiation arguments by constraint,
  postfix templates are not implemented. Smart literals are not offered for types of packages
  the file does not import; chains go one level deep only.
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

### Analysis inspections (wave 2 of FEATURES.md §11, part E)

PSI walkers on `GoAnalysisInspectionBase` (a `buildVisitor` that returns the empty visitor while `GoIdeFeature.DIAGNOSTICS` is off,
like the checker inspections); types and resolve come from `GoSemanticService` (cached per body), no project-wide search except the
implementations of a type switch's interface (stub indices, as Fill Switch). WARNING and enabled by default unless the row says otherwise.

| Short name | Rules | Quick fixes |
|---|---|---|
| `GoExhaustiveSwitch` | `switch` over an enum (constants of a named type in its package; equal values are one member, unexported constants of another package are not members, bit flags skipped) or a type switch over an interface of the project content (library interfaces skipped) without `default` (option: with `default` too): `Missing cases in switch of type Color: Red, Green, Blue and 2 more` on the `switch` keyword (weak warning) | Add missing cases (`GoSwitchCases`, the Fill Switch computation) |
| `GoStructTag` | vet `structtag`: "struct field tag ‹tag› not compatible with reflect.StructTag.Get: ‹vet reason›"; `Duplicate key "json" in struct field tag`; `struct field B repeats json tag "id" also at field A` (`json`, `xml` with attributes apart and `XMLName` skipped, `yaml`, `db`; `-` and empty names skipped, embedded structs not descended into); `struct field x has json tag but is not exported` (`json`, `xml`) | Fix quoting (`GoStructTags.repaired`: bare value quoted, space after the colon, missing closing quote at the end, comma or nothing between pairs); Remove duplicate key |
| `GoContextPlacement` | `context.Context should be the first parameter of a function` (declarations and methods; `testing` `*T`/`*B`/`*F`/`TB` may come first); `'ctx' is replaced/shadowed by context.Background(): …` inside the innermost function that has a context parameter; `context.Background() is passed where 'ctx' is available` (weak warning; function literals without their own context parameter are not reported) | Use ctx; Use ctx (remove the assignment) |
| `GoDocComment` | golint `exported` (opt-in, weak warning, off by default): `exported function Foo should have comment or be unexported` (also method `T.Foo`, type, const, var; in a group without a group comment: `… should have comment (or a comment on this block) or be unexported`); `comment on exported type Foo should be of the form "Foo ..."` (`A`/`An`/`The Foo` and a `Deprecated:` paragraph accepted; the group comment is not checked for the form; only the first name of a spec is checked). Skipped: `_test.go`, `package main`, generated files, methods of unexported types | Add doc comment (`// Name ` above the declaration; caret after it when the file is open in the selected editor); Start comment with 'Name' (`//` comments only) |
| `GoBuildConstraint` | vet `buildtag`: `invalid //go:build expression: …`, `misplaced //go:build comment` (after the package clause, or no blank line before it), `multiple //go:build comments` (errors); `// +build is deprecated; use //go:build` and `unknown GOOS/GOARCH 'linx'` for a tag one edit from a known one (weak warnings; `cgo`, `unix`, `ignore`, `go1.*`, `goexperiment.*`, tags under 3 characters never) | Add //go:build line (converts `+build`, Go's printing: `(a && !b) \|\| c`); Replace with 'linux' |
| `GoErrorsPackage` | vet `errorsas` (`second argument to errors.As must be a non-nil pointer …`, `… should not be *error`; `any` targets accepted); `err == ErrX` / `!=` with a package-level `error` variable (weak warning; not `nil`, not inside `Is` methods) | Take the address of target; Replace with errors.Is(err, ErrX) (imports `errors`) |

Struct tag parsing is pure (`GoStructTags`: vet's `validateStructTag`, reflect's `Lookup`, `strconv.Unquote`), tested by `GoStructTagsTest`;
the inspections by `GoAnalysisInspectionsTest`.

### Quick fixes

- Add import (`GoAddImportFix`, high priority): for `undefined: pkg` on a qualifier `pkg.X` or
  `pkg.T` in a type, one fix per importable package (`GoImportPaths`, standard library first, at
  most 5) whose name is `pkg` and which exports `X`; inserted by the completion's
  `GoImportInserter` (goimports-style groups).
- Remove unused import (`GoRemoveImportFix`): the spec's line, or the whole declaration when it
  becomes empty; a blank line left between blank lines is collapsed.
- Optimize imports: `lang.importOptimizer` `GoImportOptimizer` (Code | Optimize Imports and the
  `GoOptimizeImportsFix`): removes the imports the checker reports unused, then regroups each
  parenthesised declaration like `goimports -local <main module>` (`GoImportGroups.regroup`: `"C"`,
  std, third-party, local; comment lines above a spec move with it; one spec per line required,
  otherwise only the gofmt sort applies). Reformat Code (`GoImportSorter`) still sorts within the
  existing blank-line groups only, as gofmt does (wave 3, 0.2.25).
- Unused variable (`GoUnusedVariableFixes`): a single-variable statement (`x := v`,
  `var x T = v`) is removed when its values have no calls or receives, otherwise replaced by
  `_ = v`; one of several variables (`a, x := f()`, `var a, x`, `for i, x := range`) is renamed
  to `_` when another variable keeps a name. Header and type-switch variables get no fix.
- Convert (`GoWrapConversionFix`): for `cannot use x (...) as T value in ...` when the target
  type is known from the context (declared variable type, `=` target, function result, call
  parameter incl. variadic elements), the value is typed and converts to `T`; integer-to-string
  conversions are not offered. Types of other packages are written with the file's import name.

### Intentions (`ide.intentions`, `go-psi-ide-intentions.xml`, category "Go")

Alt+Enter actions that rewrite code by its types (MIGRATION.md step 9 F). Each is an `IntentionAction` (`GoCodeActionIntention`:
`startInWriteAction`, not dumb-aware) that asks `GoIdeFeatureGate` for `CODE_ACTIONS` first, computes a `GoEditPlan` (document edits +
imports) from the PSI and `GoSemanticService`, applies it like the quick fixes and adds imports through `GoImportInserter`. Types are
written as the file spells them (`GoSourceText`: import name of another package, `builtin` types unqualified); zero values come from
`GoZeroValues` (shared with the `iferr` snippet), variables in scope from `GoScopeValues` (the local-scope walk of `GoScopeCandidates`).

- Fill all fields / Fill required fields (`GoFillStructFieldsIntention`, `GoFillRequiredFieldsIntention`): inside `T{…}`, `&T{…}`, an
  elided nested literal or on the literal's type; the fields not written yet, keyed, one per line (a one-line literal is spread over
  lines; a multi-line one gets a comma after its last element). An embedded field is filled by its type name (`Base: Base{}`), never by
  promoted fields (not valid keys). Unexported fields of another package are skipped. "Required" leaves out `nil`able fields (pointers,
  slices, maps, channels, functions, interfaces). Positional literals: not offered. Values are not aligned (gofmt does it on save).
- Fill return values (`GoFillReturnValuesIntention`): a `return` with fewer values than the results; each written value keeps the first
  result slot it is assignable to, the others get a local of exactly that type declared before the statement (nearest, `err` first for
  `error`) or the zero value. Not offered for a bare `return` with named results or `return f()` forwarding a tuple. Inside a function
  whose body the checker reports as "missing return" (and whose last statement is not a `return`), the same intention reads
  "Add missing return" and inserts the statement before the closing brace.
- Fill switch (`GoFillSwitchIntention`): expression switch over a named non-interface type — `case C:` for every constant of exactly that
  type in its package (files in order, unexported ones only in the own package), minus the ones resolved or named in a case and the ones
  whose constant value is already in a case or added for an earlier constant (no duplicate case for `Ptr = Pointer`); type switch
  over a named interface — every implementing type of the project (`GoImplementations.implementingTypes`, project scope, generic types
  skipped), `*T` when only the pointer implements it; a case naming the type with or without `*`, or naming an interface the type
  implements, counts. The computation is `GoSwitchCases`, shared with `GoExhaustiveSwitchInspection`. Inserted after the existing
  cases, before `default`.
- Fill select / Fill select with default (`GoFillSelectIntention`, `GoFillSelectWithDefaultIntention`): `case <-ctx.Done():` with
  `return …, ctx.Err()` (zero values, or bare `return` without results) for every `context.Context` in scope, `case v := <-ch:` for
  receive-capable channels (`v`, `v2`… when a local is called `v`), `case ch <- <zero>:` for send-only ones, `case <-t.C:` for
  `*time.Timer`/`*time.Ticker`, `case <-time.After(d):` for `time.Duration` variables (import added); a case whose channel expression
  already appears is not repeated; the second variant adds `default:` when missing.
- Handle error (`GoHandleErrorIntention`, description directory `GoPsiHandleErrorIntention`): after `x, err := f()` / `x, err = f()` whose
  callee's last result is `error`, unless the next statement is an `if` mentioning the variable: `if err != nil { return <zero values>, err }`.
  A call standing alone becomes `if [_, …]err := f(); err != nil { … }`.
- Wrap error with fmt.Errorf (`GoWrapErrorIntention`): the last value of a `return` that is an `error` variable becomes
  `fmt.Errorf("<func>: %w", err)` (`Type.Method` for methods); `fmt` is imported when missing.

Editing intentions (FEATURES.md section 11, wave 2 G), the same base and gate, text edits with tabs (no reformat):

- Change quote (`GoChangeQuoteIntention`, gopls `changequote`): "Convert to raw string literal" when the value passes
  `strconv.CanBackquote` (no backquote, newline or other control character but a tab; `\x80`+ byte escapes refused), "Convert to
  interpreted string literal" always (`strconv.Quote`; carriage returns of the raw literal dropped as the compiler does). `GoStringQuotes`.
- `if` (`GoIfIntentions.kt`, caret on the `if` header): Invert 'if' condition (needs an `else` block, also on the `else` keyword; branches
  swapped; `==`↔`!=`, `<`↔`>=` only for integer/string operands, otherwise `!(a < b)`; `!x`→`x`; De Morgan one level over a flattened
  `&&`/`||` chain); Invert 'if' with early return / continue (last statement of a function without results or of a loop body, no `else`,
  no init statement, no name of the body already declared in the enclosing block or the parameters); Merge nested 'if' (outer body is
  only the inner `if`, no `else`, no init on the inner one, no comments between); Split 'if' condition (at the `&&` under the caret, else
  the last one; no `else`); Convert 'if' to 'switch' (two or more `if`s, every condition `s == v` or an `||` of them over one plain
  reference `s`, init only on the first `if`, no duplicate values, no unlabeled `break` in a branch, comparable operand type); Convert
  'switch' to 'if' (expression switch whose tag is a plain reference or absent; no `fallthrough`, no unlabeled `break`; `default` becomes
  the final `else` wherever it stands).
- Declarations (`GoDeclarationIntentions.kt`): Split into separate declarations (`var a, b T` / `var a, b = 1, 2` per name, a `var (…)`
  or `const (…)` group per spec; not for `iota` or implicit-value const groups, nor when a value names a declared name); Group
  declarations (adjacent single `var` or `const` declarations into one group, no comments inside, no `iota`); Join declaration and
  assignment (`GoDeclarationJoin`, shared with Join Lines: `x := v` when `defaultType(typeOf(v))` is `T`, otherwise `var x T = v`);
  Convert to 'var' declaration (`x := v` → `var x T = v`, `T` through `GoSourceText`, not for unexported types of other packages);
  Convert to short variable declaration (`var x = v`, or `var x T = v` when the types agree).

Known gaps: values in filled literals are not aligned until the file is formatted.

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

## Wave 1 of `docs/FEATURES.md` §11 (2026-10-02, versions 0.2.2–0.2.13)

Editing features over the PSI; none of them talks to gopls, and only inlay hints and doc links stand behind the feature gate.

### Editing (`ide.editor`, `go-psi-ide-editing.xml`; no gate)
- `GoSurroundDescriptors`: statement surrounders (`if`, `if / else`, `for`, `func() {…}()`, `go func`, `defer func`, `{…}`; the selection grows to whole
  statements of one block or `case` body) and expression surrounders (`(expr)`, `!(expr)` for booleans, `for range` with variables by type, `if err != nil {…}`
  after a call whose last result is `error` — a nested `(T, error)` call is moved out before its statement, the `return` gets zero values of the enclosing function).
- `GoUnwrapDescriptor`: unwrap `if` (init kept, `else` dropped), unwrap / remove `else`, unwrap `for`, unwrap `func() {…}()` (also under `go` / `defer`),
  remove `defer` / `go`, unwrap `case` of `switch` / type switch / `select`, unwrap braces. Bodies move one tab left; raw strings and block comments stay.
- `GoStatementMover` (`statementUpDownMover`, `order="first"`): statements, `case` clauses, struct fields, interface methods, specs of grouped declarations and
  top-level declarations with the comments right above; stops at the edge of its list; blank lines between neighbours stay in place; anything else falls back to the line mover.
- `GoJoinLinesHandler`: `var x T` + `x = v` → `x := v` (or `var x T = v` when the default type of `v` differs), adjacent string literals of one kind, call arguments
  and composite literal elements (trailing comma removed). Edits are text with tabs, as gofmt writes them; no `CodeStyleManager` pass (the host may format with an external gofmt).
- `GoEditText`: shared helpers (statement lists, line shifts that skip raw strings and block comments, body extraction).

### Paste (`ide.editor.paste`, `go-psi-ide-paste.xml`; no gate)
- `GoPasteImportsProcessor` (`copyPastePostProcessor`): on copy, every `pkg.X` qualifier of the range is recorded with its import path and alias; on paste into
  another Go file the missing imports are added through `GoImportInserter` (sorted into the std / non-std group; a qualifier that already means something in the
  target is left alone). `CodeInsightSettings.ADD_IMPORTS_ON_PASTE`: YES silent, NO nothing, ASK a `ChooseElementsDialog`.
- Text without copy metadata: unresolved qualifiers go to the EP `io.github.golangsupport.pasteImportResolver` (`GoPasteImportResolver`); the host answers from
  its stdlib catalogue when exactly one standard package of that name has every used member.

### Spelling (`ide.spelling`, `go-psi-ide-spelling.xml` — loaded through `<depends optional="true">com.intellij.modules.spellchecker</depends>` of the host)
- `GoSpellcheckingStrategy`: identifiers at their declaration (not package names, import aliases, builtins), comments through `CommentSplitter` (directives,
  indented code, URLs, `[pkg.Name]` links, back-quoted text and the cgo preamble skipped), string literals through `PlainTextSplitter` with escapes and `fmt`
  verbs blanked (import paths and struct tags skipped), never runes. The concrete inspection is Grazie's Typo; the tests check `SpellCheckingInspection.tokenize`.

### Doc links (`ide.documentation.GoDocLinks`, `GoDocLinkReference`; gate `NAVIGATION`)
- `[Name]`, `[A.B]`, `[pkg.T.M]`, `[import/path.Name]`, `[*T]`, `[pkg]` in `//` doc comments outside function bodies (brackets standing apart from words; not
  indented lines, not `[Text]: URL` definitions) are soft references, one per name, resolved like go/doc through `GoPackageModel` / `GoScopes` / `GoUniverse` and
  `lookupFieldOrMethod`; Rename rewrites the comment leaf. `GoDocHtml` renders the resolvable ones as `psi_element://` links; `GoDocLinkHandler` opens the target's documentation.

### Inlay hints (`ide.hints`, `go-psi-ide-hints.xml`; gate `INLAY_HINTS` for the gopls set, none for the struct size)
- Declarative providers: `go.parameter.names`, `go.literal.fields`, `go.types` (options `assign`, `range`, `literal` off, `instantiation` off), `go.constant.values`,
  `go.struct.size` (`24 bytes, 11 padding (16 if reordered)`, 64-bit GOARCH only, not for generic or empty structs). Parameter-name heuristics follow gopls (the
  argument says the name, a one-parameter function says it, one-letter parameters, `f(g())` with a multi-value `g` gets nothing). Types are printed with the
  import name of the file; `:=` types come from `declarationType` and the per-body caches, call signatures from `calleeSignature` (type arguments from `partialSubst`).
- `GoInlayHintsBenchmark` (`net/http/server.go`, 688 hints): cold ≈ 139 ms, warm ≈ 15 ms, after a body edit ≈ 24 ms (`testData/benchmark/thresholds.json`).
- Tests dump the hints of a real `DeclarativeInlayHintsPass` in the `/*<# … #>*/` format; `testHintsDoNotLoadOtherFiles` keeps the AST of the callee's file unloaded.

### Wave 3: code creation (2026-10-03, versions 0.2.23–0.2.30)

Completion parts are in "Smart, chain and project-member completion" above, import grouping in "Quick fixes". Create intentions:
`ide.intentions.GoCreateFromUsageIntentions` on `GoCreateText` / `GoCreatePlan` (a plan may write into another file of the package;
the preview shows only plans for the editor's file), gate `CODE_ACTIONS`; each reads the checker's `undefined` / `undefined-member`
diagnostic at the caret, so nothing is offered for a name that resolves.

| Feature | Class | What |
|---|---|---|
| Create function from usage | `GoCreateFunctionFromUsageIntention` | `undefined: f` on a call: params from argument types (untyped → default, nil → any), names from arguments/types (`v1, v2`), results from the context; `pkg.F` into the project package's file; generic arguments → not offered |
| Create method from usage | `GoCreateMethodFromUsageIntention` | `x.M(...)` missing on a named non-generic type of the project: receiver name/pointer from T's methods (struct without methods → pointer); after T's last method in its file or after the type |
| Create field from usage | `GoCreateFieldFromUsageIntention` | `x.F` missing on a struct of the project: type from `x.F = v` or `expectedTypeAt`, else `any`; appended before `}` |
| Create variable from usage | `GoCreateVariableFromUsageIntention` | undefined value: `x := zero` when the literal has the exact type (int, string, bool, struct/array) else `var x T`, before the statement; package level `var x T` after the declaration |
| Create type from usage | `GoCreateTypeFromUsageIntention` | undefined type reference: `type T struct{}`, `interface{}` inside a constraint; not for `T[...]` |
| Implement missing methods (quick fix) | `GoImplementMissingMethodsFix` in `GoTypeMismatchInspection` (`assignability`) and `GoCheckerInspection` (`type-assertion`), gate `DIAGNOSTICS` | stubs from `GoTypePredicates.missingMethods` minus names T already has; `*T` → pointer receivers, `T` → value; API `GoImplementStubs.missing/compute`, also used by the host's Ctrl+I / Alt+Insert / Alt+Enter Implement Interface (0.2.31; text path in dumb mode) |
| Generate (host) | `GoGenerateEnumStringAction`, `GoGenerateEqualAction` | String() for Enum: constants of the type from `GoEnumConstants` (shared with Fill Switch / exhaustive switch), one case per value, flags and types with String() excluded. Equal Method: per-field comparison from semantic types (`==`, bytes/slices/maps.Equal, time.Time.Equal; reflect.DeepEqual only when ticked by hand) |

The host's text-based `GoCreateFunctionIntention` stands down with Built-in code actions; `GoplsActionKinds.isNativeCodeAction` hides the
gopls `Create …`, `Implement …` and `Declare missing methods …` actions then.

### Printf checks (`ide.inspections.printf`, `GoPrintfInspection`; gate `DIAGNOSTICS`) and verb completion (wave 2, part F)
- `GoFormatString` is a pure parser of `fmt` directives (flags, `[n]` indexes, `*` width/precision) with a decoder that maps every character of an
  interpreted or raw literal back to its source range, so a problem is reported on the directive, not on the call. `GoPrintfVerbs` holds vet's verb /
  flag / argument-class table; `GoPrintfTypes` is vet's `matchArgType` over `GoType` (Stringer / `error` for `%s`, `fmt.Formatter` accepts all, elements of
  slices / arrays / maps, fields of structs, unknown types and interfaces always match).
- `GoPrintfCalls` recognises `fmt`, `log`, `(*log.Logger)`, `testing` methods, `runtime/trace.Logf`, and the package's own wrappers: a function whose last
  parameters are `(format string, args ...any)` (or only `args ...any` for Print-like) and whose body forwards them to a printf-like callee; forwards are
  cached per body in `GoBodyCache` (`gopsi.printf.forwards`), chains up to depth 3, same package only (no AST of other packages is loaded).
- Checks and messages follow vet (`fmt.Printf format %d has arg s of wrong type string`, `call needs 1 arg but has 2 args`, unknown verb / flag, `%w` only
  in `Errorf` with an `error` argument, several `%w` only from Go 1.20, Println with directives or a redundant `
`, func values not called, recursive
  `String`); the check stops at the first bad directive. Fixes: replace the verb with the one the argument type takes, remove extra arguments, add `%v`
  placeholders; `%v` of an error in `Errorf` is an INFORMATION-level suggestion with "Replace %v with %w".
- `GoFormatVerbCompletion`: typing `%` in the format string of a printf-like call opens the verb list (`GoFormatVerbTypedHandler` + confidence), ranked by the
  type of the argument the directive will read; `%w` only in Errorf-like calls; flags and width typed before the verb are kept. Nothing in non-printf calls.

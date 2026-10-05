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
| `codeFoldingOptionsProvider` | `folding.GoCodeFoldingOptionsProvider`, `GoFoldingSettings` | GoLand one-liners: `if x != nil { return … }` (one return / panic / break / continue / goto), function with one `return`, `case` with one statement, empty function, empty struct / interface fold to one line with their text (`{ return "", err }`, capped at 60 chars), replacing the `{...}` region of the same range; five Code Folding options (Go section), collapsed by default. Syntax only, no comment may get hidden. Tests: `GoFoldingTest` (`OneLine.go`) |
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
| `codeInsight.lineMarkerProvider` | `GoRecursiveCallLineMarkerProvider` | Slow-pass "Recursive call" gutter (`AllIcons.Gutter.RecursiveMethod`) on the callee identifier of a call that resolves to the enclosing function / method (`f()`, `r.f()`, `T.f(r)`, `(*T).f(r)`, also from a function literal inside); name check before resolve, one marker per line; gate `IMPLEMENTATION_MARKERS`. Tests: `GoRecursiveCallLineMarkerTest`. |

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
| `codeInsight.daemonBoundCodeVisionProvider` | `GoImplementInterfaceCodeVisionProvider` (group `go.psi.implement.interface`, Top) | "Implement interface" above every type declaration with a non-interface, non-alias type (structs, `type Level int`, generic types), once per `type ( … )` group, as GoLand shows it. A click puts the caret on the type name and runs the platform's `ImplementMethods` (Ctrl+I), which the host answers with its interface chooser. |
| `codeInsight.daemonBoundCodeVisionProvider` | `GoAddInterfaceMethodCodeVisionProvider` (group `go.psi.add.interface.method`, Top) | "Add method" above a package-level, non-generic interface of the project with at least one implementation in the project (not in generated files). A click opens Add Method to Interface (`GoAddInterfaceMethodIntention`: the method into the interface, stubs into the implementing types). Also gated by `RENAME`, like that refactoring. |
| `config.codeVisionGroupSettingProvider` | `GoImplementInterfaceCodeVisionSettings`, `GoAddInterfaceMethodCodeVisionSettings` | Names and descriptions of the two action groups in Settings \| Editor \| Inlay Hints \| Code vision. |

The anchor is the declaration without its doc comment (the hint sits right above the `func`/`type` line). The providers follow the
gate group `IMPLEMENTATION_MARKERS` and answer nothing in dumb mode; at most 300 declarations of a file are asked, with a
cancellation check between them. Position: usages and implementations keep `CodeVisionAnchorKind.Default` in the platform's groups, so
`CodeVisionHost` places them by the group's position in Settings, else by the default position (`CodeVisionSettingsDefaults` of the
product: line end in GoLand, top in IDEA); the plugin does not override it. `codevision.GoCodeVisionTest` (11) covers the anchors, the
wording, the cap, the closed gate, the action lenses (which declarations, only with implementations, gates), positions and groups, and that
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
promoted fields (`w.Name` through an embedded struct) follow too. Package rename: `GoRenamePackageProcessor` (`renamePsiElementProcessor`,
order first) renames the clause of every file of the directory (`old_test` → `new_test`), unaliased qualifiers and import paths in importers
(found by `GoFileImportsIndex`, re-checked by resolve) and the directory when it is named after the package; a directory rename rewrites import paths of the
package and its subpackages. Conflicts: another import or package-level name with the new name in an importer; existing directory.
`GoLibraryPackageRenameVeto` keeps GOROOT and module cache packages. Tests: `GoRenamePackageTest`.

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

#### GoLand parity G7: cheap inspections (`ide.inspections.style` / `.redundancy` / `.bugs`)

GoLand's ids, groups (`groupPath="Go"`, `groupName` as GoLand's) and levels from `docs/goland-analysis/dumps/inspections-go.txt`, all enabled
by default, on `GoAnalysisInspectionBase` (gate `DIAGNOSTICS`). Inspections, not lint rules: they carry GoLand's ids for `//noinspection`
and the profile, and need no `[id]` prefix. Shared fixes: `GoEditFix` (text edits computed at apply time), `GoRenameToFix` (platform
`RenameProcessor`, references too), `GoRenameVariableFix("Rename")` (interactive). Messages GoLand shows verbatim in the dumps:
"Fields are assigned without explicit names", "Comment should be meaningful or it should be removed"; the rest follow golint /
staticcheck wording. GoLand checks that already existed under other names: `GoRedundantTrueInForCondition` = rule S1006,
`GoStringsReplaceCount` = SA1018, `GoLeadingWhitespaceInDirectiveComment` = SA9009, `GoBuildTag` = `GoBuildConstraint` (extended).

| Short name (GoLand group, level) | Rules | Quick fixes |
|---|---|---|
| `GoCommentLeadingSpace` (Code style, weak) | `//text`: `Line comment should have a space after '//'`; directives (`//word:`, `//line`, `//export`, `//extern`, `//nolint`, `//noinspection`, regions, `//#`, `//+build`), `////`, bare `//` and generated files quiet | Add a space after '//' |
| `GoCommentStart` (Code style, weak) | the form half of golint `exported` (split from `GoDocComment`): `comment on exported function Foo should be of the form "Foo ..."`; `Comment should be meaningful or it should be removed` for a `//` comment that is only the name (GoLand on `// NewOrder`); `_test.go` and generated files skipped, `main` checked | Start comment with 'Name'; Remove comment |
| `GoErrorStringFormat` (Code style, weak) | ST1005 on the literal of `errors.New` / `fmt.Errorf` (resolved): `Error string should not be capitalized` (first letter; initialisms like `URL`, `IPv4` pass), `Error string should not end with punctuation or a newline` (`.`, `:`, `!`, `\n`) | Lowercase the first letter; Remove the trailing punctuation |
| `GoExportedOwnDeclaration` (Code style, weak) | package-level `var A, B int` / `const A, B = …` with an exported name: `Exported var A should have its own declaration` (first exported name, once per spec) | Split into separate declarations (no comments, a value per name or none, no implicit repetition after it) |
| `GoNameStartsWithPackageName` (Code style, weak) | `type name will be used as probe.ProbeThing by other packages, and that stutters; consider calling this Thing` for exported top-level func / type / var / const; the rest must start upper-case; `main` and tests skipped | Rename to 'Thing' |
| `GoReceiverNames` (Code style, weak) | `this` / `self`; `_` (`omit the name if it is unused`); `Receiver name x should be consistent with previous receiver name t for T` (first named method of the type in the file) | Rename to 'c' / to the previous name; Remove the receiver name |
| `GoRedundantElseInIf` (Code style, weak) | `'if' block ends with a 'return' statement, so drop this 'else' and outdent its block` (also `break`, `continue`, `goto`, builtin `panic`); not for `else if` chains or an `if` that is an `else` branch | Remove redundant 'else' (no init statement, no comments in the else) |
| `GoStructInitializationWithoutFieldNames` (Code style, weak) | `Fields are assigned without explicit names` on the `{…}` of an unkeyed struct literal, also elided ones in slices / maps / `[]*T`, same-package and anonymous structs (GoLand on a test table) | Add field names (when every field is listed) |
| `GoTypeParameterInLowerCase` (Code style, information) | `Type parameter 't' is declared in lowercase` | Rename to 'T' |
| `GoUnitSpecificDurationSuffix` (Code style, weak) | ST1011: `var timeoutSeconds is of type time.Duration; don't use unit-specific suffix "Seconds"` (vars, consts, parameters, fields; ST1011's suffix list) | Rename to 'timeout' |
| `GoUnsortedImport` (Code style, weak) | `Import is not sorted` at the first spec out of path order in an import group (blank or comment-only lines split groups) | Sort imports (each spec on its own line) |
| `GoSnakeCaseUsage` (Code style, weak) | `Don't use underscores in Go names; func parse_url should be parseUrl` (funcs, methods, types, vars, consts, params, receivers, type params; not fields, `ALL_CAPS`, edge underscores, test functions, generated files) | Rename to camelCase |
| `GoEmptyDeclaration` (Declaration redundancy, warning) | `Empty 'var' declaration` (also `const`, `type`, `import`; a comment inside keeps it) | Delete empty declaration |
| `GoPreferNilSlice` (Declaration redundancy, weak) | `Empty slice declared using a literal` for a local `s := []T{}` / `var s = []T{}` | Replace with nil slice declaration (statement lists only) |
| `GoRedundantComma` (Declaration redundancy, weak) | `Redundant comma` before `)` / `}` / `]` on the same line | Remove redundant comma |
| `GoRedundantImportAlias` (Declaration redundancy, weak) | `Redundant alias 'fmt'` (alias = resolved package name, else the last path segment) | Remove redundant alias |
| `GoRedundantSemicolon` (Declaration redundancy, weak) | `Redundant semicolon` at the end of a line, before `}` / `)` / `;`; `for` clauses never | Remove redundant semicolon |
| `GoRedundantTypeDeclInCompositeLit` (Declaration redundancy, warning) | `Redundant type declaration` for `[]T{T{…}}`, `[]*T{&T{…}}`, map keys and values (type text compared, gofmt -s) | Remove redundant type |
| `GoVarAndConstTypeMayBeOmitted` (Declaration redundancy, weak) | `Type can be omitted`: declared type identical to the values' types (default type of untyped constants for `var`; `const` keeps its type for untyped values) | Remove type |
| `GoUnusedTypeParameter` (Declaration redundancy, warning) | `Unused type parameter 'T'` of a function or type (no unqualified mention in signature, constraints, body, type) | Rename to '_' |
| `GoRedundantParens` (General, weak) | `Redundant parentheses` around primary operands, whole conditions / return values / assigned values / arguments, and named types in declarations; composite literals in `if` / `for` / `switch` headers kept | Remove redundant parentheses |
| `GoDeferGo` (Probable bugs, weak) | `'recover()' is called directly by 'defer' and does not stop a panic`; `'panic()' is called directly by 'go'` (builtins only, resolved) | Wrap in a function literal |
| `GoImportUsedAsName` (Probable bugs, warning) | `Variable 'strings' collides with imported package name` (locals, params, receivers, type params, local types; blank / dot imports ignored) | Rename |
| `GoIrregularIota` (Probable bugs, weak) | `'iota' in a single constant declaration is always 0`; `Redundant repetition of the previous constant expression with 'iota'` | Replace with 0; Remove the repeated expression |
| `GoMixedReceiverTypes` (Probable bugs, weak) | `Methods of 'T' have both value and pointer receivers` at the minority receivers (value ones on a tie), across the package's files (stubs) | Change receiver to pointer / to value |
| `GoReservedWordUsedAsName` (Probable bugs, warning) | `Variable 'len' collides with the builtin function` (type / constant / function; not fields, methods) | Rename |
| `GoTypeAssertionOnErrors` (Probable bugs, weak) | `Type assertion on errors fails on wrapped errors` for `x.(T)` with `x` of type `error`; `Is` / `As` / `Unwrap` methods skipped | Replace with 'errors.As' (`if e, ok := err.(*T); ok {` → `var e *T` + `if errors.As(err, &e) {`, imports `errors`) |
| `GoAssignmentToReceiver` (Control flow, weak) | `Assignment to method receiver 'c' does not propagate to callers` (`c = …`, `c++`); `Assignment to a field of value receiver 'c' is lost when the method returns` (`c.X = …` through value fields, the receiver never used as a whole) | Change receiver to pointer |

Tests: `GoCodeStyleInspectionsTest`, `GoRedundancyInspectionsTest`, `GoProbableBugInspectionsTest`, `GoBuildConstraintInspectionTest`;
`GoParityProbeTest` runs all of them over the GoLand probe files and `playground/store/order.go` and compares with the dumps (only the
two lines GoLand shows: the unkeyed rows of `analysis_test.go` and `// NewOrder`).


## Known gaps

- Implementations of generic interfaces and implementation checks that need instantiation.
- Sub-interfaces (interfaces embedding an interface) are not listed as its implementations.
- Doc links (`[Name]`) are rendered as code, not as navigable links.
- Inlay hints and run line markers are out of scope.

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

### GoLand's items (PLAN.md G3; `docs/goland-analysis/dumps/completion.txt`)

| Where | Class | What |
|---|---|---|
| struct literal key | `GoFillStructCompletion` | **Fill all fields…** / **Fill selected fields…** first (priority) in a keyed or empty `T{}` / `&T{}` / `[]T{{}}` with fields left (not in a positional literal, not at a value, basic only). Text from `intentions.GoFillStruct` (the Fill intention's generator) with `align = true`: one field per line, values aligned as gofmt does (the run of one-line elements above is re-padded), blank lines before `}` replaced, caret after the first value. Fill selected opens `ChooseElementsDialog` (all ticked) after the insertion (`invokeLater`); `chooserForTests` picks in tests. |
| constant rows | `GoScopeCandidates.constValueText` | `MaxItems = 10  untyped int`, `Info = iota  Level`: tail ` = <expression>` (a repeated row shows the one it repeats, whitespace collapsed, cut at 40), type text the type. Values from `GoConstSpecStub.values`, so no AST of other files. |
| top level | `GoTopLevelTemplates` | `func (*T)` (type text `Method`): `func (r *T) <caret>() {\n}` for the type declared last above the caret (else the first of the file), receiver name and pointer-ness from its methods. `func` / `Implement Interface...`: deletes the prefix, puts the caret on that type's name and runs the host action `Go.Generate.Implement` (`invokeLater`; offered only when the action is registered; `actionRunnerForTests`). Not inside functions, not without a type in the file. |
| struct tags (host) | `lang.GoStructTagKeyToAllFields`, `GoStructTagCompletion.nameStyles` / `styleFor` | At a key of a closed tag the first item **Add tag key to all fields…**: a popup of the name keys (`json yaml xml toml db mapstructure bson env form`), then `GoGenerateStructTagsAction.tagEdits` writes the key into every exported field that lacks it, named in the style the struct uses for that key. Names in a value: the style other fields use (only when one has the key), then GoLand's `full-name`, `full_name`, `FullName`, `fullName`, then `fullname`. |
| parameter name | `GoNameCompletion.parameterItems`, `GoNameSuggestions.parameterName` | `func g(<caret>` / `func g(a int, <caret>` (a bare type of a function, method or literal parameter; not results, receivers, function types, `...T`, `pkg.`): the types become `name Type` items as in GoLand: `err error`, `string2 string` (reserved or taken name: next digit), `base Base`, `t T` for a type parameter, `set Set[<caret>]` for a generic type; packages stay, keywords and project members are left out. With a typed prefix the exported types of imported packages follow at level `UNIMPORTED` (`ctx context.Context`; GoLand offers none). Extra lookup strings: the name, the type name, the qualified type. |
| variable / parameter name | `GoNameCompletion.variableNames`, `GoNameSuggestions` | At a name being declared (`var <caret> Circle`, `<caret> := c.Area()`, `for i, <caret> := range names`, `func g(<caret> Base)`; GoLand offers nothing here): from the right-hand side (callee without `Get`/`New`/`Parse`… → `area`, `name`, `server`; `len` → `n`; value → its name; `names[0]`, `<-names`, range value → singular), then from the declared type rendered qualified (well-known `ctx err t w r buf mu wg d f db`, lowercased name, last camel word, first letter; slices plural, `[]byte` → `data buf b`, maps `m`, channels `ch`, funcs `fn f`, basics `s str` / `i n` / `ok b`). Range index over a slice, array, string or int: `i` (then `j`, `k`). Unique against locals, parameters, package-level and import names (digit suffix); at most 6, in this order. |

### Tests

`completion.GoSmartCompletionTest` (10), `GoProjectMemberCompletionTest` (6),
`completion.GoScopeCompletionTest` (15), `GoMemberCompletionTest` (19),
`GoKeywordCompletionTest` (19), `GoInsertCompletionTest` (11), `GoRankingCompletionTest` (9),
`GoCompletionEnvironmentTest` (10: import paths from GOROOT and from an on-disk module copied
from `testData/completion/module`, module auto-import, comments/strings, confidence, dumb mode,
stub-only candidates, a 3000-line file with 500 package-level symbols under 300 ms),
`GoGolandParityCompletionTest` (15: Fill items, constant values, top-level declarations), `GoNameSuggestionsTest` (7, pure name rules), `GoNameCompletionTest` (15: parameter and variable names); host `GoStructTagKeyCompletionTest` (4).

### Known gaps

- Tail texts print `byte`/`rune` as `uint8`/`int32` (the type model does not keep aliases).
- Method expressions on `(*T)` and completion of generic instantiation arguments by constraint
  are not implemented. Postfix templates live in the host plugin (`lang.GoPostfixTemplates`): the
  platform's live template contributor puts the applicable keys into this list after `x.`, and the
  host's `GoPostfixCompletionWeigher` (after `priority`, before `goCompletion`) keeps them behind
  the members. Smart literals are not offered for types of packages
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
| `GoDocComment` | golint `exported` (opt-in, weak warning, off by default): `exported function Foo should have comment or be unexported` (also method `T.Foo`, type, const, var; in a group without a group comment: `… should have comment (or a comment on this block) or be unexported`); only the first name of a spec is checked. Skipped: `_test.go`, `package main`, generated files, methods of unexported types. The form check (`comment on exported type Foo should be of the form "Foo ..."`) moved to GoLand's `GoCommentStart` (G7, below), a subclass sharing the walk | Add doc comment (`// Name ` above the declaration; caret after it when the file is open in the selected editor) |
| `GoTimeLayout` | weak warning: the layout literal of `Time.Format` / `AppendFormat` / `time.Parse` / `ParseInLocation` (found by resolve) in `yyyy-MM-dd` notation, with no time elements, or the ISO date with day before month `2006-02-01`; string literals only, stdlib constants never | Convert to Go layout; Swap to '2006-01-02' |
| `GoEmbedDirective` | error, go's messages for `//go:embed`: no matching files found, invalid pattern syntax, directory with no embeddable files, misplaced directive (not above a package-level `var`), file does not import `embed` | Add import "embed" (blank import for string/[]byte vars, plain for `embed.FS`) |
| `GoBuildConstraint` | vet `buildtag`: `invalid //go:build expression: …`, `misplaced //go:build comment` (after the package clause, or no blank line before it), `multiple //go:build comments` (errors); `unknown GOOS/GOARCH 'linx'` for a tag one edit from a known one, in `//go:build` and `// +build` lines, `misplaced +build comment` outside the header and `possible malformed +build comment` for a header comment that mentions `+build` without being one (GoLand `GoBuildTag`; weak warnings; the `+build` line itself is `GoFixPlusBuild`'s; `cgo`, `unix`, `ignore`, `go1.*`, `goexperiment.*`, tags under 3 characters never) | Replace with 'linux' |
| `GoShadowedVar` | GoLand's "Shadowing variable" (weak warning, name painted with `GO_SHADOWING_VARIABLE` through `ProblemDescriptorBase.setTextAttributes`): `Declaration of 'x' shadows declaration at line N` (`at b.go:N` for another file) for a local variable (`:=`, `var`, `if`/`for`/`switch` header, range, type-switch guard, `select` receive) whose name `GoScopes.resolveName` finds outside its own scope as a variable, constant, parameter, result or receiver, or a package-level var/const of the same package. Quiet: `_`, a `:=` reusing its own scope's variable (incl. the body's parameters, a type-case guard), parameters themselves, `x := x`, `switch x := x.(type)`, imports, builtins | Navigate to shadowed declaration; Rename variable (platform rename handler at the name) |
| `GoErrorsPackage` | vet `errorsas` (`second argument to errors.As must be a non-nil pointer …`, `… should not be *error`; `any` targets accepted); `err == ErrX` / `!=` with a package-level `error` variable (weak warning; not `nil`, not inside `Is` methods) | Take the address of target; Replace with errors.Is(err, ErrX) (imports `errors`) |

### Go fix, second set (GoLand parity G5; package `ide.inspections.gofix2`)

Group "Go fix" (`groupPath="Go"`), weak warnings, enabled by default; modelled on gopls `modernize`. Base `GoFix2InspectionBase`: the version
gate (`GoFixGoVersion.atLeast`: the module's `go` directive from the project model's module graph, cached per file on `GoTrackers.projectModel`;
no module or no directive reports everything) and quick fixes that re-detect the finding at apply time and apply text edits plus imports
(`GoSourceText` + `GoImportInserter`). Exact shapes only; `GoFixProbeTest` runs the whole set over the GoLand probe files expecting nothing.

| Short name | Go | Rules (message) | Quick fixes |
|---|---|---|---|
| `GoFixNewExpr` | 1.26 | pointer helper call `ptr(x)` (`func ptr[T any](x T) *T { return &x }`, non-generic too): `call of ptr(x) can be simplified to new(x)`; `func() *T { v := e; return &v }()`: `function literal can be simplified to new(e)`; `x := e` before `p := &x` / `p = &x` / `return &x`, `x` used nowhere else: `variable 'x' is used only for its address; …` | Simplify call of ptr to new (untyped constant to a typed helper: `new(int64(5))`); Simplify to new(expr) |
| `GoFixOmitZero` | 1.24 | `json:",omitempty"` on a struct-typed field (`time.Time` included; raw-string tags; not pointers, not embedded): `Omitempty has no effect on nested struct fields` | Replace omitempty with omitzero (behavior change); Remove redundant omitempty tags |
| `GoFixPlusBuild` | 1.17 | header `// +build`: `+build line is no longer needed` (with `//go:build`) / `+build line is obsolete; use //go:build` (without) | Remove obsolete +build lines; Replace +build lines with //go:build |
| `GoFixEmbedTyped` | 1.16 | unexported `//go:embed` `string` used only as `[]byte(x)` call arguments (or `[]byte` only as `string(x)`), no other file of the directory mentions it: `Embedded variable 'x' is used only as []byte; …` | Declare 'x' as []byte / string |
| `GoFixHostPort` | — | `fmt.Sprintf("%s:%d"\|"%s:%s", host, port)` / `host + ":" + port` reaching the address argument of `net.Dial`, `DialTimeout`, `Listen`, `ListenPacket`, `Dialer.Dial(Context)`, `ListenConfig.Listen(Packet)`, `Resolve{TCP,UDP}Addr` (directly or via a local used once): `address format "%s:%d" does not work with IPv6` | Replace with net.JoinHostPort (`strconv.Itoa` for `int`, `fmt.Sprint` for other integers) |
| `GoFixWaitGroup` | 1.25 | `wg.Add(1)` right before `go func() { defer wg.Done(); … }()` (or `wg.Done()` last) on `sync.WaitGroup`: `Goroutine creation can be simplified using WaitGroup.Go` (on `go`) | Simplify by using WaitGroup.Go |
| `GoFixTestingContext` | 1.24 | in `Test*`/`Benchmark*`/`Fuzz*` of a `_test.go` with a `*testing.T\|B\|F` parameter: `ctx, cancel := context.WithCancel(context.Background())` + `defer cancel()`: `context.WithCancel can be modernized using t.Context`; bare `context.Background()`/`TODO()` in the test body (not in literals, `go`/`defer`, tests with `Cleanup`): `context.Background() can be replaced by t.Context() in a test` | Replace context.WithCancel with t.Context; Replace with t.Context() |
| `GoFixReflectTypeFor` | 1.22 | `reflect.TypeOf((*T)(nil)).Elem()`, `reflect.TypeOf(T{})`: `reflect.TypeOf call can be simplified using TypeFor` | Replace TypeOf by TypeFor |
| `GoFixErrorsAsType` | 1.26 | `var e *T` right before `if errors.As(err, &e) {`, `e` used only in the `if`: `errors.As can be simplified using AsType[*T]` | Replace errors.As with AsType[*T] (`if e, ok := errors.AsType[*T](err); ok {`) |
| `GoFixStringsSeq` | 1.24 | `for _, x := range strings.Split(…)` / `for range` (also `Fields`, `bytes.*`), or `parts := strings.Split(…)` right before `for … range parts` with `parts` used only there: `Ranging over SplitSeq is more efficient` | Replace Split with SplitSeq |
| `GoFixStringsBuilder` | 1.10 | local `s := ""` / `var s string` growing only by `s += x` (string `x`) inside loops, read once after them: `using string += string in a loop is inefficient` (at the first `+=`) | Replace string += string with strings.Builder |
| `GoFixAtomicTypes` | 1.19 | `int32`/`int64`/`uint32`/`uint64` var without initializer (local, or unexported package-level used only in its file) whose every use is `&n` to the matching `sync/atomic` function: `Variable 'n' is accessed only through sync/atomic functions; it can be declared as atomic.Int64` | Use atomic.Int64 (`n.Add(d)`, `n.Load()`, `n.Store(v)`, `n.Swap(v)`, `n.CompareAndSwap(o, n)`) |
| `GoFixUnsafeFuncs` | 1.17 | `unsafe.Pointer(uintptr(p) + off)` (`p` an `unsafe.Pointer`): `pointer + integer can be simplified using unsafe.Add`; `(*[N]T)(unsafe.Pointer(p))[:n:n]` (`p` a `*T`): `slice conversion can be simplified using unsafe.Slice` | Simplify using unsafe.Add; Simplify using unsafe.Slice |

Tests: `inspections.gofix2.*` (`GoFixCallShapesTest.kt`, `GoFixDeclarationShapesTest.kt`, `GoFixTypedShapesTest.kt`: markers, the fix result, negatives, the version gate).

### Go fix inspections (`ide.inspections.gofix`, group "Go fix"; PLAN.md G5)

GoLand's `Go | Go fix` group, with the Go team's `modernize` analyzers as the behavioural model. Base `GoFixInspectionBase` (on
`GoAnalysisInspectionBase`, so gated by `DIAGNOSTICS`; its KDoc is the README of the conventions): each inspection declares `minGoVersion`
and is skipped for files whose language version is older (`GoFixVersions`: the file's `//go:build go1.N`, else the `go` directive of the
main module containing the file, cached per directory on `GoTrackers.packageDependencies` + the go.mod file; no go.mod, no gate). `plan(element)`
is detection and rewrite at once (`GoFixPlan`: message, fix name, text edits, imports to add, imports to drop when unused, highlighted range);
`GoFixQuickFix` (family "Go fix") recomputes it from the reported element, edits the document, drops unused imports, adds new ones through
`GoImportInserter`; its preview is a text diff without the import changes. Weak warnings in XML for now (the SYNTAX_UPDATE severity comes with
G5-c). Messages are gopls' (GoLand shows those: `for loop can be modernized using range over int`).

| Short name | Go | Rewrites (exact shapes only) | Message |
|---|---|---|---|
| `GoFixAny` | 1.18 | `interface{}` (no elements, no comments) → `any`, where `any` is the predeclared one | `interface{} can be replaced by any` |
| `GoFixMinMax` | 1.21 | `if a < b { x = a } else { x = b }` → `x = min(a, b)` (all four orders, `<=`/`>=`); `if x > y { x = y }` → `x = min(x, y)`; integers and strings only (floats differ on NaN / -0), pure operands, `x` of the operands' type | `if/else statement can be modernized using min` / `if statement can be modernized using max` |
| `GoFixRangeInt` | 1.22 | `for i := 0; i < n; i++` → `for i := range n` (`for range n` when `i` is unused); `i` not written in the body; `n` an `int` or untyped integer constant: a constant, a local/parameter not written in the body and never address-taken in the function, or `len` of one. Highlight = the for clause, like GoLand | `for loop can be modernized using range over int` |
| `GoFixForVar` | 1.22 | `v := v` / `k, v := k, v` at the top of a `range` body whose variables are declared with `:=` → removed | `copying variable is unneeded` |
| `GoFixSlicesContains` | 1.21 | `for _, e := range s { if e == x { return true } }; return false` → `return slices.Contains(s, x)` (and the negated pair); `… return i …; return -1` → `slices.Index`; `if e == x { …; break }` → `if slices.Contains(s, x) { … }` (no other branch, no loop variable in `…`); `s` a slice, `x` pure and assignable to the element type | `Loop can be simplified using slices.Contains` / `slices.Index` |
| `GoFixSlicesSort` | 1.21 | `sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })` → `slices.Sort(s)`, `s` pure, ordered basic element (integer, float, string); `sort` import removed when unused | `sort.Slice can be modernized using slices.Sort` |
| `GoFixSlicesBackward` | 1.23 | `for i := len(s) - 1; i >= 0; i--` → `for i, v := range slices.Backward(s)`; `s` a local slice not written in the body; value reads of `s[i]` become `v` (`elem`/`item` when `v` is taken), the index stays only when used otherwise | `for loop can be modernized using slices.Backward` |
| `GoFixStringsCut` | 1.18 | `i := strings.Index(s, sep)` (before the `if` or in its header) + `if i >= 0` / `!= -1` / `> -1` with `i` used only as `s[:i]` / `s[i+len(sep):]` (`s[i+1:]` for a one-byte literal) → `before, after, ok := strings.Cut(s, sep); if ok`; also `bytes` | `strings.Index can be simplified using strings.Cut` |
| `GoFixStringsCutPrefix` | 1.20 | `if strings.HasPrefix(s, p) { … strings.TrimPrefix(s, p) … }` → `if after, ok := strings.CutPrefix(s, p); ok { … after … }`; `if r := strings.TrimPrefix(s, p); r != s` → `if r, ok := strings.CutPrefix(s, p); ok`; `Suffix` pair, `bytes` | `HasPrefix + TrimPrefix can be simplified to CutPrefix` |
| `GoFixMapsLoop` | 1.21 / 1.23 | `for k, v := range src { dst[k] = v }` → `maps.Copy(dst, src)` (same key and value types); `for k := range m { keys = append(keys, k) }` → `keys = slices.AppendSeq(keys, maps.Keys(m))`, or `keys := slices.Collect(maps.Keys(m))` replacing `var keys []K` right before; values with `maps.Values` (1.23) | `Replace m[k]=v loop with maps.Copy` / `Replace append loop with slices.Collect` / `… slices.AppendSeq` |

Tests: `inspections/gofix/*Test.kt` on `GoFixTestBase` (markers, all fixes of a name applied, `useGoVersion`); `GoFixProbeTest` runs every
inspection of `GoFixInspections.ALL` over the GoLand probe files and expects only GoLand's finding (`analysis.go:118`).

### Data-flow inspections (wave 4 of FEATURES.md §11)

`ide.inspections.flow` builds on `semantic.flow` (`GoControlFlow.of(body)`, liveness, reaching definitions, nilness; one graph per body, cached by
`GoBodyCache`) through `GoFlowInspectionBase` (one `check(flow, holder)` per function or literal); `ide.inspections.lint` holds the checks that need
types but no flow. All act only with Language features: Built-in. The noise gate is `FlowCorpusTest` (`:go-psi-ide:corpusTest`): every check over
GOROOT/src, the first 20 reports of each in `go-psi-ide/build/flow-corpus/goroot-src-flow-reports.txt`, counts in `testData/metrics/goroot-src-flow.json`.
WARNING and enabled by default unless the row says otherwise.

| Short name | Rules | Quick fixes |
|---|---|---|
| `GoErrorOverwritten` | error result overwritten before it is read; `error(nil)` is a reset | |
| `GoWrongErrorChecked` | `if err != nil` right after `v, err2 := f()` | Check 'err2' instead |
| `GoNilErrorReturn` | nilerr: `return nil` inside `if err != nil` | |
| `GoErrNilReturned` | weak: `return …, err` where `err` is known nil (`if err == nil` branch, `else` of `err != nil`) | Return nil |
| `GoDeferBeforeErrorCheck` | `defer x.Close()` before the error of `x, err := …` is checked | Move defer after the error check |
| `GoShadowedError` | `err :=` in an inner block shadows an outer err read after the block; the inner one is only nil-checked and the branch does not leave | |
| `GoResultUsedBeforeErrorCheck` | weak: a pointer / interface result of `v, err := f()` used before err is read; io.Reader methods and nil-checked results exempt | |
| `GoNilDereference` | nilness: field, method, index or `*p` on a value nil on every path; `unsafe.Sizeof` operands exempt | |
| `GoImpossibleNilCheck` | weak, off by default since G7 (`GoDfaConstantCondition` reports the same comparisons): `x == nil` / `x != nil` known on every path | |
| `GoNilValueNilError` | nilnil, weak, off by default: `return nil, nil` in `(T, error)` with nilable T unless the doc comment says so | |
| `GoIneffectualAssignment` | ineffassign: a value overwritten or never read; generated files and swaps exempt | Remove assignment to 'x' |
| `GoUnreachableCode` | vet unreachable: first statement of each unreachable run; not after `os.Exit` / `log.Fatal` | Delete unreachable code |
| `GoLostCancel` | vet lostcancel: cancel of `WithCancel` / `WithTimeout` / `WithDeadline` not called on all paths | |
| `GoBodyNotClosed` | `resp.Body` of `http.Get` / `Post` / `Do` not closed on a path | Add defer resp.Body.Close() |
| `GoRowsNotClosed` | `*sql.Rows` not closed on a path | Add defer rows.Close() |
| `GoLockNotReleased` | `Lock` / `RLock` without the matching unlock on a path; deferred unlock, flag-guarded unlock, hand-back and `…Locked` functions exempt | |
| `GoSendAfterClose` | send after `close(ch)` on the same path | |
| `GoWaitGroupAddInGoroutine` | `wg.Add` inside the `go func` | Move Add before the go statement |
| `GoContextNotPropagated` | contextcheck: `context.Background()` / `TODO()` while a local context or an enclosing function's parameter is in scope (the innermost parameter is `GoContextPlacement`'s) | Use ctx |
| `GoSelfAssignment` | `x = x` | Remove self-assignment |
| `GoUnusedResult` | dropped result of a pure function; the unused `append` is the compiler's error, which carries the fix | Assign the result to x |
| `GoDeferInLoop` | `defer` inside a loop body | |
| `GoCopyLocks` | vet copylocks (`Lock()` + `Unlock()` without parameters) | Use a pointer receiver |
| `GoLoopClosure` | loop variable captured by `go` / `defer` literal, `go` < 1.22 | Insert 'v := v' |
| `GoTestingGoroutine` | `t.Fatal` / `FailNow` / `SkipNow` from a goroutine | |

Tests: `GoControlFlowTest`, `GoFlowAnalysesTest` (semantic), `GoFlowInspectionsTest`, `GoFlowInspections2Test`, `GoResourceFlowInspectionsTest`, `GoLintInspectionsTest`.

Struct tag parsing is pure (`GoStructTags`: vet's `validateStructTag`, reflect's `Lookup`, `strconv.Unquote`), tested by `GoStructTagsTest`;
the inspections by `GoAnalysisInspectionsTest`.

### Data flow, unused declarations, go.mod (GoLand parity G7)

GoLand's ids and groups (`groupPath="Go"`, `docs/goland-analysis/dumps/inspections-go.txt`), enabled by default, registered at the end of
`go-psi-ide-inspections.xml`. `GoG7ProbeTest` runs the whole set over the GoLand probe files laid out as GoLand saw them (`internal/probe`,
`internal/probeerr` of an application module) and expects exactly GoLand's findings of these ids (the `highlight-internal-*.txt` dumps).

| Short name (group, level) | Rules | Quick fixes |
|---|---|---|
| `GoDfaConstantCondition` (Data flow analysis, warning; `flow.GoConstantConditionInspection`) | `Condition 'x' is always 'true'/'false'`: nil comparisons known on every path (`GoImpossibleNilCheckInspection.nilChecks`), comparisons of a local whose every reaching definition stores the same literal (`var n int` = its zero value) with a literal or another such local, literal-only comparisons (`GoFlowConstants`); named constants never (`if debug`, `runtime.GOOS`), escaping locals never, `const` specs never | |
| `GoDivisionByZero` (Probable bugs, warning; `flow.GoDivisionByZeroInspection`) | `/`, `%`, `/=`, `%=` by a local proven zero, or by a literal zero with a non-constant float / complex dividend; constant integer cases stay the checker's compile error | |
| `GoExportedFuncWithUnexportedType` (General, warning; `declarations`) | exported function, or exported method of an exported type, returning `T` / `*T` of an unexported package-level type of its package; not `main`, not tests, not `error` | |
| `GoRedundantConversion` (Declaration redundancy, weak warning; `declarations`) | `T(x)` / `[]T(x)` with `x` of an identical type (`byte(u8)` too); untyped constants, unknown types and `C.T(x)` skipped; highlighted as unused | Remove redundant type conversion (parenthesizes an operator operand inside another expression) |
| `GoUnusedFunction` / `GoUnusedExportedFunction` / `GoUnusedType` / `GoUnusedExportedType` / `GoUnusedConst` / `GoUnusedGlobalVariable` (Declaration redundancy, warning; `unused`) | `Unused function / type / constant / global variable 'x'` on the name, highlighted as unused; `ReferencesSearch` in the use scope ∩ project, uses inside the declaration (and, for a type, inside its own methods) do not count. Unexported: always (types and constants local too). Exported, as on GoLand's probe: functions, constants and variables of an internal / application package (`GoProjectPackages.isClosed`; exported functions of `main` are `GoUnusedExportedFunction`'s), types only in `package main`. Skipped: methods, `init`, `main`, `_`, test entry points, bodyless and `//export` / `//go:linkname` functions, generated files | Safe delete |

`GoUnusedParameter` follows GoLand since G7: warning, highlighted as unused, `Unused parameter 'name Type'` on the whole parameter (the name
alone when the declaration has several), and exported functions of internal / application packages are checked too (call sites then searched in
the project). The old `GoUnusedExported` (off by default) is superseded by the unused set above.

go.mod (host `mod/GoModUpdates.kt`, `plugin.xml`, group "Go modules | Dependency issues (go list -m -u)"): `VgoDependencyDeprecated` (warning on the
module path: `Module 'm' is deprecated: <comment>`, from `Deprecated` of the background `go list -m -e -u -json m@latest`) and
`VgoDependencyVersionRetracted` (warning on the version: `Version v of 'm' is retracted: <rationale>`, from `go list -m -e -retracted -json m@v`
in the same background run; fix "Upgrade to vX" when a newer version is known). Pure parts: `GoModuleList.parse` (`Deprecated`, `Retracted`),
`GoModIssues.deprecated / retracted / problems`; tests in `GoModUpdatesTest`. `VgoUnusedDependency` is the existing `GoModUnused`.

Tests: `inspections.unused.*` (`GoUnusedDeclarationInspectionsTest`, `GoG7ProbeTest`), `inspections.declarations.*`, `flow.GoG7FlowInspectionsTest`.

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

GoLand parity G4 (texts and behaviour after GoLand's intention list and descriptions), the same base and gate:

- Expressions (`GoExpressionIntentions.kt`): Flip binary operator (innermost binary expression at the caret; "Flip '>=' to '<='" for
  ordering comparisons, "Flip '+'" for commutative operators, "Flip '-' (changes semantics)" for the rest and string `+`; the old left
  operand parenthesized when it binds as tight, not in `&&`/`||` chains; the blanks around the operator kept). Negate expression /
  Negate expression recursively / Negate topmost expression / Negate topmost expression recursively (`GoNegateIntentionBase`; texts
  "Negate '||' to '&&'", "Negate topmost '&&' to '||' recursively"): the current (innermost) or the outermost (through `&&`, `||`,
  parentheses and `!`) boolean binary expression becomes `!(negation)`, or loses the `!` already around it, so the value is unchanged;
  De Morgan one level or into every `&&`/`||` operand; ordering comparisons only for integer/string operands (float orderings are not
  offered). Topmost and recursive variants appear only when they write something different. Specify type explicitly
  (`GoSpecifyTypeIntention`): `var x = v` / `const c = v` without a type → `var x T = v`, the default type for untyped constants, several
  names only with one type, `GoSourceText` qualifies and imports; not for untyped `nil` or unexported types of other packages.
- Literals (`GoLiteralIntentions.kt`): Remove keys from struct literal (all elements keyed → values in field order, omitted fields as
  zero values, multi-line literals stay one value per line; not for structs of other packages with unexported fields, nor elided-type
  literals `[]T{{A: 1}}`); Move field assignment to struct initialization (caret on `s.F = v`; the consecutive `s.X = …` statements after
  `s := T{…}` / `var s = T{…}` / `&T{…}` up to the caret's one fold into the literal; not when a value reads `s`, a field repeats or is
  keyed already, or is promoted).
- Signatures (`GoSignatureIntentions.kt`, caret on the header of a function, method or literal, or in a function type): Expand signature
  types (`a, b T` → `a T, b T` in parameters and named results); Reuse signature types (adjacent named, non-variadic declarations with the
  same type text → `a, b T`).
- Declarations (`GoDeclarationMergeIntentions.kt`): Merge declaration up (into the previous adjacent declaration of the same kind, a group
  is created or extended; not across comments, not for `iota` / implicit const specs); Merge declaration up via comma (`var a T` + `var b T`,
  `var a = 1` + `var b = "s"`, two specs of one group, `a := 1` + `b := 2`; same type text, one value per name or none, the second's values
  must not read the first's names); Split declarations into two groups (a group split before the spec at the caret; not for `iota` /
  implicit specs after it). GoLand's "Split all declarations" and "Split declarations by comma" are the existing Split into separate
  declarations. Export (`GoExportIntentions.kt`, `GoRefactoringIntention`): on the name of a package-level function, type, var or const,
  a method or a field → `RenameProcessor` to the upper-case name; not for `init` / `main` or a taken name (package scope through
  `GoPackageModel`, fields and methods through `lookupFieldOrMethod`). Migrate function parameter to method receiver
  (`GoMigrateParameterToReceiverIntention`): first named parameter of a named, non-generic, non-interface type of the same package;
  declaration rewritten, calls found with `ReferencesSearch` → `v.f(…)` (`&v` → `v`, composite literals parenthesized), function values →
  `(*T).f`; a `nil` receiver, a multi-value argument or a call nested in the receiver argument stop it with an error hint.
- Unresolved (`GoCreateFromUsageIntentions.kt`, `GoValueUsage`): Create global variable 'x' (inside a function body: `var x T` after the
  imports; the callee of a call gets `func(params)`); Create parameter 'x' (appended to the enclosing function or literal, before a
  variadic one; type parameters allowed; calls of a declared function in the project get the zero value of `T` appended).

GoLand parity G4 (texts as GoLand's Alt+Enter), the same gate:

- Imports (`GoImportIntentions.kt`, caret on an import spec, not `"C"`): Import for side-effects (`_ "pkg"`, only when the file does not use
  the package); Add import alias (the package name as the alias, then a template over the alias and the qualifiers of its uses, so typing
  renames them together); Add dot import alias (`. "pkg"`, qualifiers dropped; not offered when a name would then resolve to something
  else); Remove dot import alias (uses of the package's names get `pkg.`; not offered when `pkg` is taken at a use).
- Layout (`GoArgumentLayoutIntentions.kt`, `GoListLayout`): Put arguments / elements on separate lines (innermost call arguments or
  composite literal elements around the caret with two or more items, not beyond the enclosing block; gofmt layout with a trailing comma,
  lines inside an item move with it, `...` kept) and Put … on one line (multi-line list of single-line items, no trailing comma). Lists with
  comments are left alone.
- Join concatenated string literals (`GoStringIntentions.kt`): every run of adjacent literals in the `+` chain at the caret; interpreted
  bodies glued as written, raw parts of a mixed run quoted (`GoStringQuotes.quote`), all-raw runs stay raw.
- Printf (`GoFormatIntentions.kt`): Add format string argument (caret inside the format string of a printf-like call: input dialog for the
  expression, `%v` at the caret, the argument after the arguments of the verbs before it; not with `%[n]` indexes or `args...`); Exclude /
  Mark as string formatting function edit the application-level `inspections.printf.GoPrintfFunctions` (vet full names, `excluded` /
  `extra`), which `GoPrintfCalls` consults first (a marked function is Printf-like when a `string` precedes `...any`).
- errcheck: quick fix "Do not report this method/function anymore" (`GoDoNotReportCalleeFix`) on `GoUncheckedErrorInspection` and the
  errcheck rule adds `path.Func` / `path.Type.Method` to the inspection's `excludedFunctions` (options panel `OptPane.stringList`); both
  read the current profile's list through `isUnchecked`.
- Tags (`GoTagIntentions.kt`): Change field name style in tags (on a tag, the type name or the `struct` keyword; popup `full-name` /
  `full_name` / `FullName` / `fullName`, test hook `GoChangeTagNameStyleIntention.chooser`; rewrites the name of the key at the caret or the
  first name-like key in every single-name field, options kept, raw and interpreted tags); Update key value in tags (field whose name in
  a key differs from its field name in the style the other fields use, `detectStyle`).

Navigation intentions (GoLand parity G4, `GoNavigationIntentions.kt`, same gate, `LowPriorityAction`, no preview, not in a write action):
Go to Implementations (`GoGotoImplementationsIntention`: interface type spec header or interface method name → implementing types /
methods in project content), Go to Interfaces (`GoGotoInterfacesIntention`: concrete type spec header → interfaces in project and
libraries), Go to Method Specifications (`GoGotoMethodSpecificationsIntention`: method header up to `{` → interface method specs).
Targets from `GoImplementations` (the gutter's lookup); availability asks for one target, `invoke` collects all under a modal progress,
opens a single one, several in `PsiTargetNavigator`. The host adds Run go generate on comment / file / package
(`build.GoGenerateIntentions`) and the go.mod intentions (`mod.GoModIntentions`: Merge a group of directives / all directives / directive up
over the pure `GoModDirectiveEdits`, Update dependencies… over `GoModUpdates`).

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
colour page) are GoLand's (`docs/goland-analysis/dumps/color-keys-go.txt`, same external names and page groups, pinned by the root
`GoColorSettingsPageTest`): exported / local function and call, builtin call, struct / interface / other type spec and reference,
package exported / local and local constant, package exported / local, local, scope (declared in an `if` / `for` / `switch` header or a
case clause) and reassigned-in-`:=` variable, receiver apart from parameter, exported / local field, calls of func-valued variables and
fields, `nil` as `GO_BUILTIN_VARIABLE`, doc comment references (the first word naming the declaration, resolved `[Name]` links). Each new
key falls back onto the older base key (`GO_FUNCTION_CALL`, `GO_FIELD`, ...) so tuned schemes keep their look. `GO_SHADOWING_VARIABLE`
(for the shadowing inspection) and `GO_SYNTAX_UPDATE` (G5) are defined, nothing colours with them yet. Unresolved identifiers keep the
lexer colour. Not dumb-aware. `GoStringContentAnnotator` (DumbAware) colours inside literals: struct tag key / colon / value / text
(`GoStructTags.parse`), printf verbs `GO_FORMAT_VERB` in the format argument of the calls `GoPrintfCalls` knows (falls back onto the
valid escape, as GoLand shows them), valid / invalid escapes of interpreted strings and runes (an annotator: the lexer keeps a literal
one token for the quote handler). Directive parts (`go:generate` comment keyword, build tags / parens / operators) are the root's `GoIdentifierAnnotator`.

### Tests

`inspections.GoInspectionsTest` (17), `inspections.GoQuickFixesTest` (14),
`inspections.GoInspectionsGorootTest` (2), `annotator.GoSemanticHighlightingTest` (3, goldens
`testData/highlighting/semantic.txt`, `goland.txt`), `annotator.GoStringContentRangesTest`. Fixtures: `testData/inspections/*.go` (markup with the
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

### Directive comments (`ide.directives`, `GoDirectiveReferenceContributor`; gate `NAVIGATION`; 0.2.35)
- `//go:embed` patterns: poly-variant soft references to the matched files and directories (path.Match globs, `all:`, quoted patterns; a directory embeds its tree without `.`/`_` files unless `all:`).
- `//go:linkname local pkg.name`: `local` references the local declaration (rename-aware); `pkg.name`, `pkg.Type.method`, `pkg.(*Type).method` resolve through stubs in the project, GOROOT or the module cache; unresolved targets are silent.
- `//go:generate`: `go run <path>` and other arguments that are existing relative files or directories. No tool binaries, no running.

### Doc links (`ide.documentation.GoDocLinks`, `GoDocLinkReference`; gate `NAVIGATION`)
- `[Name]`, `[A.B]`, `[pkg.T.M]`, `[import/path.Name]`, `[*T]`, `[pkg]` in `//` doc comments outside function bodies (brackets standing apart from words; not
  indented lines, not `[Text]: URL` definitions) are soft references, one per name, resolved like go/doc through `GoPackageModel` / `GoScopes` / `GoUniverse` and
  `lookupFieldOrMethod`; Rename rewrites the comment leaf. `GoDocHtml` renders the resolvable ones as `psi_element://` links; `GoDocLinkHandler` opens the target's documentation.

### Inlay hints (`ide.hints`, `go-psi-ide-hints.xml`; gate `INLAY_HINTS` for the gopls set, none for the struct size)
- Declarative providers: `go.parameter.names` (option `return`, "Show return parameters", on), `go.literal.fields`, `go.types` (options `assign`, `range`, `literal` off, `instantiation` off), `go.constant.values`,
  `go.struct.size` (`24 bytes, 11 padding (16 if reordered)`, 64-bit GOARCH only, not for generic or empty structs). Parameter names follow GoLand (checked line by
  line against its dumps of the probe files, `testParameterNamesAsInTheGoLandProbe`): a hint only at a literal argument — string, number, signed number, `nil`,
  `true`, `false` — never at identifiers, selectors, calls, composite or function literals; one-letter names are shown (`produce(n: 3)`, `Println(a...: "x")`);
  a one-parameter function whose name says the parameter gets nothing (`SetName("x")`). With `return`, the names of named results at the literal values of
  `return` (`return n: 0, err: nil`; nothing for unnamed results or `return f()`). Types are printed with the
  import name of the file; `:=` types come from `declarationType` and the per-body caches, call signatures from `calleeSignature` (type arguments from `partialSubst`).
- `go.time.layout` (0.2.34, VALUES_GROUP): `t.Format("2006-01-02 15:04"/*→ 2026-03-07 15:09*/)`, the layout argument (literal or string constant) rendered with the sample Saturday 2026-03-07 15:09:08.123456789 +03:00 MSK; only when the callee resolves to package `time` (`GoTimeLayout.render`, a port of `nextStdChunk`).
- `GoInlayHintsBenchmark` (`net/http/server.go`, 688 hints): cold ≈ 139 ms, warm ≈ 15 ms, after a body edit ≈ 24 ms (`testData/benchmark/thresholds.json`).
- Tests dump the hints of a real `DeclarativeInlayHintsPass` in the `/*<# … #>*/` format; `testHintsDoNotLoadOtherFiles` keeps the AST of the callee's file unloaded.

### Unused parameters (`ide.inspections.lint.GoUnusedParameterInspection`; gate `DIAGNOSTICS`; 0.2.49)

gopls `unusedparams`, weak warning, unexported functions and methods only (G7: warning, GoLand's text, exported ones of internal / application packages too — see "GoLand parity G7" above). Skips: no / empty / panic-only body, `init` / `main`, test functions,
`//export`, `//go:linkname`, HTTP handler shape, `*testing.T`-like parameters, a method implementing an interface method (`GoImplementations.superMethods`),
a function used as a value (`ReferencesSearch` in the package's directory). Fixes: "Rename to _", "Remove unused parameter" (signature + every call
site, unused imports dropped; not with side-effect arguments, method expressions, multi-value arguments). The flow corpus cannot judge it (references
and implementations of GOROOT files are not searched there): its count is recorded, its noise is reviewed by the regression tests.

### Inspections in CI (host `ci`; 0.2.59)

`GoInspectStarter` (`appStarter` `go-inspect`, headless, not on EDT): opens the project, waits for configuration and smart mode, forces Built-in
and gopls off for the run, runs every enabled Go / GoModule `LocalInspectionTool` per file under a progress indicator (inspections refuse to run
without one — seen live), writes SARIF through the pure `GoSarif`; arguments and file filters in `GoInspectOptions` / `GoInspectFiles`. Exit code also
goes to `GO_INSPECT_EXIT_CODE_FILE` (`idea.bat` drops it). Wrappers `tools/ci/go-inspect.sh|cmd` (the `.cmd` calls System32 `tar.exe`: GNU tar from
Git Bash takes `C:` for a host — seen live). Tests: `GoSarifTest`, `GoInspectOptionsTest`. Guide: `docs/CI.md`.

### SQL injection (`ide.injection.sql.GoSqlInjector`, `go-psi-ide-injection-sql.xml`, optional `com.intellij.database`; gate `SEMANTIC_COLORS`; 0.2.53)

Methods of `database/sql` `DB` / `Tx` / `Conn`, sqlx and pgx v5 / pgxpool, resolved through `GoSemanticService.resolve` to the receiver type and its
package (embedded methods resolve to the embedded type: `sqlx.DB.Query` → `database/sql`); `GoSqlDetect` for `*Query` / `*SQL` / `*Sql` raw literals.
Injected language is the SQL dialect of the platform mapping (`GenericSQL` by default). Tests: `GoSqlInjectionTest`.

### Shell Script in `//go:generate` (`ide.injection.sh.GoGenerateShellInjector`, `go-psi-ide-injection-sh.xml`, optional `com.jetbrains.sh`; no gate)

The command of a `//go:generate` line (after a `-command NAME` alias: the aliased command) is Shell Script, found by id. The host is `GoGenerateCommentImpl`, the only comment that is a `PsiLanguageInjectionHost` (go-psi-core `GoASTFactory`). Tests: `GoGenerateShellInjectionTest`, core `GoCommentImplTest`.

### String injections (`ide.injection`, `go-psi-ide-injection.xml`, `go-psi-ide-injection-json.xml`; gate `SEMANTIC_COLORS`; 0.2.50)

`GoStringLiteral` is a `PsiLanguageInjectionHost` (`GoStringLiteralMixin`: raw strings verbatim, interpreted strings through `GoInterpretedStringEscaper`).
`GoRegExpInjector` injects RegExp into the first argument of the `regexp` constructors and matchers (callee resolved after a cheap name check);
`GoRegExpLanguageHost` + `GoRegExpCapabilities` set the RE2 dialect, `GoRegExpAnnotator` reports what the platform has no host hook for (lookahead,
atomic groups, branch reset, backreferences). `json/GoJsonInjector` (loaded only with the JSON plugin): `json.Unmarshal` / `Valid` / `NewDecoder`
arguments and JSON-looking raw literals named `…json…` (`GoJsonDetect`). Tests: `GoInjectionTest`.

### Hierarchies (`ide.hierarchy`, `go-psi-ide-hierarchy.xml`; gate `NAVIGATION`; 0.2.45)

- Call Hierarchy: `GoCallHierarchyProvider` → `GoCallHierarchyBrowser` (`CallHierarchyBrowserBase`). Callers via `ReferencesSearch`, grouped by
  `GoCalls.callerOf` (function or method; literals go to the outer declaration; package-level `var`); for a method also the callers of
  `GoImplementations.superMethods` ("via Iface"). Callees: `GoCallExpr` in the body → `GoSemanticService.resolve` (builtin package skipped).
- Type Hierarchy: `GoTypeHierarchyProvider` → `GoTypeHierarchyBrowser` (`TypeHierarchyBrowserBase`). `GoTypeRelations`: supertypes = embedded types +
  `GoImplementations.implementedInterfaces`; subtypes = `implementingTypes` (interfaces) + embedders. The "Type" view equals Subtypes.
- Nodes: `GoHierarchyNodeDescriptor` ("Recv.Method [(N usages)] [via I]  pkg (file.go)"); a node already on the path is not expanded. Tests: `GoHierarchyTest`.

### Refactorings (`ide.refactoring`, `go-psi-ide-refactoring.xml`; gate `RENAME`; 0.2.46–0.2.47)

| Refactoring | Classes | Registration | Tests |
|---|---|---|---|
| Introduce Variable | `GoIntroduceVariableHandler`, `GoExtraction` (availability, anchor, occurrences, names) | `GoRefactoringSupportProvider.getIntroduceVariableHandler` | `GoIntroduceTest` |
| Introduce Constant | `GoIntroduceConstantHandler` | `getIntroduceConstantHandler` | `GoIntroduceTest` |
| Safe Delete | `GoSafeDeleteProcessor` (`SafeDeleteProcessorDelegateBase`; companions removed in `prepareForDeletion`); parameters (0.2.54) through `GoParameterRemoval` (signature + argument at every call, method expressions; conflicts: used in body, function value, implements interface, side-effect / multi-value argument, interface spec) | `refactoring.safeDeleteProcessor`, `isSafeDeleteAvailable` | `GoSafeDeleteTest`, `GoSafeDeleteParameterTest` |
| Extract Function / Method (0.2.55) | `GoExtractFunctionHandler`, `GoExtractFunction` (selection, inputs / outputs from `GoControlFlow`, jumps, receiver, type parameters, text) | `GoRefactoringSupportProvider.getExtractMethodHandler` | `GoExtractFunctionTest` |
| Inline (0.2.56) | `GoInlineActionHandler`, `GoInlineVariable`, `GoInlineConstant`, `GoInlineFunction`, `GoInlineSupport` (precedence, conversions, side effects, name capture) | `inlineActionHandler` (`isEnabledForLanguage` Go) | `GoInlineTest` |
| Change Signature (0.2.57) | `GoChangeSignatureHandler`, `GoChangeSignatureDialog` (`RefactoringDialog`), `GoChangeSignatureProcessor` (`BaseRefactoringProcessor`; calls through `GoParameterRemoval`), `GoChangeSignature` (model, text) | `getChangeSignatureHandler` | `GoChangeSignatureTest` |
| Move (0.2.58) | `GoMoveHandler` (`MoveHandlerDelegate`), `GoMoveDialog`, `GoMoveProcessor` (planner, conflicts), `GoMoveDeclarations` (units, refusals) | `refactoring.moveHandler` (before `moveFileOrDir`) | `GoMoveTest` |
| Introduce Parameter (G6) | `GoIntroduceParameterHandler` (over `GoIntroduceHandlerBase`; signature and calls through `GoChangeSignatureProcessor` with a new parameter whose default is the expression, the expression replaced in its `afterRefactoring` hook), `GoIntroduceSupport` (locals, own-package names, declared type) | `getIntroduceParameterHandler` | `GoIntroduceParameterTest` |
| Introduce Field (G6) | `GoIntroduceFieldHandler` (field appended to the receiver's struct and formatted; popup "Initialize in current method" / "Leave initialization to the caller"; `GoIntroduceFieldOptions` for tests) | `getIntroduceFieldHandler` | `GoIntroduceFieldTest` |
| Introduce Parameter Object (G6) | `GoIntroduceParameterObjectHandler`, `GoIntroduceParameterObjectDialog` (struct name, check list), `GoParameterObject` (edits: body `p.Field`, calls `T{Field: arg}`, signature, struct); `GoIntroduceParameterObjectDelegate` only hands the platform action our handler (the platform processor is Java-shaped) | `refactoring.introduceParameterObject` (`go-psi-ide-refactoring2.xml`) | `GoIntroduceParameterObjectTest` |
| Invert Boolean (G6) | `GoInvertBooleanDelegate` (`InvertBooleanDelegate`: usages to negate, negation, initializers; `adjustElement` opens `GoInvertBooleanDialog` with the inverted name and returns null, the platform's dialog would offer the old name), `GoInvertBoolean` (targets, negation over `GoExpressionText`, names); the platform's `InvertBooleanProcessor` renames and negates | `refactoring.invertBoolean` (`go-psi-ide-refactoring2.xml`) | `GoInvertBooleanTest` |
| Copy / Clone declaration (G6) | `GoCopyDeclarationHandler` (`CopyHandlerDelegateBase`, the name of a top-level declaration only; files stay the platform's), `GoCopyDeclaration` (text with self references and doc name renamed, insertion, imports), `GoCopyDeclarationDialog` | `refactoring.copyHandler` (`order="first"`, `go-psi-ide-refactoring2.xml`) | `GoCopyDeclarationTest` |

GoLand parity G6 (Introduce Type, Remove Method from Interface; Override Methods and the new Generate items live in the host):

| Feature | Classes | Registration | Tests |
|---|---|---|---|
| Introduce Type | `GoIntroduceTypeHandler`, `GoIntroduceType` (targets, refusals: named type, a type spec's own type, type parameters / local types; occurrences by `PsiEquivalenceUtil`; names from the field / parameter / variable, else by kind; placement above the top-level declaration and its comment, re-indented), `GoIntroduceTypeAction` (`BaseRefactoringAction`) | host `plugin.xml`: action `Go.IntroduceType` in `IntroduceActionsGroup` (the platform's support provider has no introduce-type slot) | `GoIntroduceTypeTest` |
| Remove method from interface and all its implementations | `GoRemoveInterfaceMethodIntention`, `GoRemoveInterfaceMethod` (candidates from `GoImplementations.implementingMethods`, only methods declared by the implementing types; `ReferencesSearch` marks the called ones, which are kept; deletions through `GoSafeDeleteProcessor.lines` with the doc comment; one command) | end of `go-psi-ide-intentions.xml` | `GoRemoveInterfaceMethodIntentionTest` |
| Override Methods (host, `codeInsight.overrideMethod`) | `GoPromotedMethods` (here, `ide.intentions`: promoted methods via `methodsOf(*T)` / `lookupFieldOrMethod` path, signatures through `GoCreateText.signature`) + host `GoOverrideMethodsHandler`, `GoGenerators.delegatingMethod` | host `plugin.xml` | host `GoOverrideMethodsTest`, `GoGeneratorsTest` |

The platform has no language-neutral Introduce Variable base: the handler is our own over `IntroduceTargetChooser`, `OccurrencesChooser.simpleChooser`
and `VariableInplaceRenamer` / `MemberInplaceRenamer`. `GoRefactoringSupportProvider.isAvailable` accepts any Go element while Rename is Built-in:
the platform looks the provider up by the leaf at the caret.

### go.mod checks (host `mod`; 0.2.44)

`mod/GoModChecks.kt` (pure, over `GoModFileParser.directives` and a `GoModEnvironment` for directories and `vendor/modules.txt`) and
`mod/GoModInspections.kt`: `GoModPaths` (error: `replace` / `use` directory missing or without go.mod), `GoModRequires` (duplicate and self requires,
vendor sync; fixes "Remove duplicate require", "Copy 'go mod vendor' to the clipboard"), `GoModVersions` (`go` syntax, `toolchain` older than `go`).
Tests: `GoModInspectionsTest` (`GoModChecksTest`, `GoModInspectionsFixtureTest`).
0.2.52: `GoModUnusedInspection` — a direct require no file of the module imports from (`GoFileImportsIndex`, longest module-path prefix, `tool` lines count;
pure `GoModChecks.unusedRequires`), fix "Remove unused require" (no `go mod tidy`). Tests: `GoModUnusedTest`.
0.2.51: `settings/GoPlatformWidget.kt` — status-bar widget `Go.Platform.Status` over `GoSettings.analysisGoos/analysisGoarch` and build tags; the
toolchain provider (`GoIgsToolchainProvider`) keys on them, the widget restarts the daemon. gopls is not given the same env.

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
| Generate (host, G6) | `GoGenerateMethodAction` (dialog; `GoGenerators.method`), `GoGenerateTestsForPackageAction` (`GoGenerators.untested` + `testFunction`, a `_test.go` per source file), `GoUpdateCopyrightsProvider` (`go-copyright.xml`, optional `com.intellij.copyright`: enables the Copyright plugin's own Generate \| Copyright for Go, `//` lines above `package`) | Alt+Insert Method…, Tests for Package…, Copyright; tests `GoGenerateActionsTest`, `GoGeneratorsTest` |
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

### Go fix: severity, Update Syntax, lenses (`ide.inspections.gofix`, `go-psi-ide-gofix.xml`; PLAN.md G5)
- `GoSyntaxUpdateSeverity` / `GoSyntaxUpdateSeveritiesProvider` (`severitiesProvider`): severity `SYNTAX_UPDATE` (GoLand's name, so its exported profiles keep
  the level), value 150 — between SERVER PROBLEM (100) and WEAK WARNING (200), display name "Syntax update", colour `GoColors.SYNTAX_UPDATE`
  (`GO_SYNTAX_UPDATE`, fallback weak warning). An inspection declares it as `level="SYNTAX_UPDATE"`; `HighlightDisplayLevel.find` resolves it by name.
- `GoSyntaxUpdate`: the tools of the group "Go fix" (`groupPath="Go" groupName="Go fix"` or short name `GoFix*`; `select` is the pure selection), a
  profile of just them built like Run Inspection by Name (`InspectionToolsSupplier.Simple` over the current profile), `run(project, scope)` —
  `GlobalInspectionContextImpl` with that external profile, results and batch fixes in Inspection Results.
- `GoUpdateSyntaxAction` (`Go.UpdateSyntax`, Refactor | Update Syntax..., last; the host references it in `Go.MainMenu`): the platform's scope dialog
  (`BaseAnalysisAction`), then `run`. GoLand's own preview of the rewritten code is not reproduced: the preview is the Inspection Results window.
- Lenses at the package clause of a file with Go fix findings (gate `DIAGNOSTICS`, Top): `GoBatchSyntaxUpdateCodeVisionProvider` (group `go.syntax.update`,
  "Update syntax (N places)", click = `run` on the file without the dialog) and `GoModernizerWhatsNewCodeVisionProvider` (group `Go modernizer whats new`,
  "What's New", click = the host's `Go.HelpPage.GoFix`: the guide at `#go-fix`). The count runs the enabled local Go fix tools on the file
  (`LocalInspectionTool.processFile`, stopped at 100), cached on the file by document stamp and the set of enabled tools; the daemon's highlights would be
  cheaper, but code vision and the inspection pass run in no fixed order. Tests: `inspections.gofix.GoSyntaxUpdateTest` (10, with a test-only `GoFixTestInspection`).

### Settings and typing help (host; PLAN.md G8, items 6–8)
- Optimize imports on save: `lang.GoOptimizeImportsOnSave` (`actionOnSave`) runs `OptimizeImportsProcessor` (so `lang.importOptimizer` =
  `GoImportOptimizer`) over the Go files being saved when `GoSettings.optimizeImportsOnSave` (Settings | Go | Formatting, off) is on and the platform's
  own Optimize imports on save does not cover Go already. `GoOptimizeImportsOnSaveInfoProvider` (`actionOnSaveInfoProvider`) shows it as "Optimize Go
  imports" on Tools | Actions on Save, backed by the checkbox of `GoFormattingConfigurable`. Tests: `GoSettingsG8Test`.
- Go Modules page: Environment (`modulesEnvironment`, `NAME=value;…`), Enable vendoring support (`GoVendoring`: AUTO / ALWAYS `-mod=vendor` /
  NEVER `-mod=mod`, only for a module with `vendor/modules.txt`, through GOFLAGS unless it has a `-mod`), both applied by `GoCli.buildEnvironment`
  via `cli.GoModulesEnvironment` (not to `go env`); Download Go module dependencies (`mod.GoModDownloadChoice`, GoLand's four choices: the app switch
  `downloadDependencies` plus the project as its exception in `PropertiesComponent`) — `GoModSaveListener` runs `go mod download` after a save of
  go.mod with other requirements. The project model's own vendor-mode decision (`GoModuleGraphBuilder`) is untouched. Tests: `GoModulesEnvironmentTest`, `GoSettingsG8Test`.
- Debugger | Data Views | Go: `debugger.GoDebuggerSettings` (`xdebugger.settings`, id `go`, stored in the platform's debugger.xml): integer format
  (`GoIntegerFormat`), pointer addresses, String() view. `GoDataViews.render` rewrites delve's value text in `GoValue.computePresentation`; the String()
  view (`GoStringViews`, one per `GoDebugProcess`) evaluates `call (x).String()` in the top frame of the stop, cached per stop, and remembers the types
  delve answers "has no member String" for. Delve sends no address of a pointer it followed, so "Show pointer addresses" can only hide. Tests: `GoDebugDataViewsTest`.
- Typing: Reformat block on typing `}` needs no code — the platform's `TypedHandler.indentBrace` reformats the block through `lang.formatter`
  (the range is not claimed by `GoFormattingService`: it has no FORMAT_FRAGMENTS) when Smart Keys | Reformat block on typing '}' is on; pinned by
  `GoTypingTest`. `lang.GoDocCommentEnterHandler` (`enterHandlerDelegate`): Enter right after a bare `//` above a declaration writes `// Name `
  (Smart Keys | Insert documentation comment stub and `docCommentNames`). Tests: `GoTypingTest`.

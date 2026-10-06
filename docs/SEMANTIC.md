# Semantic layer (Phases 5a/5b/5c): types, scopes, resolve, inference, diagnostics

Module `go-psi-semantic`, packages `io.github.golangsupport.semantic.*`. Public API:
`semantic.api.GoSemanticService` (project service) and the type model in `semantic.types`
(`GoType` and friends, `GoLookup`, `GoTypePredicates`, `GoTypeRenderer`, `GoConstant`).
Everything else is `@ApiStatus.Internal`.

## Type model (`semantic.types`)

A port of `go/types` as immutable Kotlin objects, never PSI:

| Kind | Class | Notes |
|---|---|---|
| basic | `GoBasicType` (`GoBasicKind`) | typed kinds + untyped bool/int/rune/float/complex/string/nil; `byte`/`rune` are `uint8`/`int32` |
| composite | `GoArrayType` (length null when not constant), `GoSliceType`, `GoPointerType`, `GoMapType`, `GoChanType` (dir), `GoTupleType` | |
| struct | `GoStructType` + `GoField` (embedded flag, tag, declaration pointer, package path for unexported identity) | |
| signature | `GoSignatureType` (`GoParam`, variadic, type params, receiver) | result type = value / tuple |
| interface | `GoInterfaceType` (methods, embedded) | `allMethods` and `typeTerms` (intersection of unions) computed lazily with recursion guards |
| named | `GoNamedType` (declaration `GoTypeSpec`, type args, origin) | underlying and methods resolved lazily through `GoTypeSource`; equality = declaration + type args |
| type param | `GoTypeParamType` (declaration, index) | `bound`, `terms`, `coreType` (single underlying type of all terms) |
| union | `GoUnionType` (`GoTerm` with `~`) | only inside constraint interfaces |
| unknown | `GoUnknownType` | absorbs everything; never throws |

`GoTypePredicates`: `identical`, `assignable` (spec rules; untyped constants by kind, no overflow
checks), `convertible` (loose), `comparable`, `implements` (method sets + type-set membership),
`missingMethods`, `defaultType`. `GoLookup.lookupFieldOrMethod` is the `go/types` BFS (depth,
ambiguity, pointer auto-deref, promoted fields, interface methods, unexported visibility by
package path); `GoLookup.methodSet` applies the `T` / `*T` receiver rules through embedding.
`GoTypeRenderer` prints gopls-style strings (`map[string]*T`, `func(a int) error`,
`List[string]`, `struct{X int}`).

`GoConstant` models constant values (big integers, decimal floats, complex, strings, bools) with
Go literal parsing (`0x`, `0o`, `0b`, legacy octal, `_`, hex floats, runes, escapes) and the
binary/unary operators used by `iota` tables and array lengths.

### byte/rune alias rendering

`GoBasicType.BYTE` and `RUNE` are separate instances with kind `UINT8`/`INT32` and the names `byte`/`rune`.
They exist only to keep the source spelling for rendering (hover, completion, parameter info, diagnostics),
like gopls. Everything semantic compares kinds: `equals`/`hashCode` ignore the name, so identity, assignability
and cache keys cannot tell them apart. Never compare basic types with `===`. A type declared `uint8` or
`int32` renders as such.

## Scopes (`semantic.scope`)

`GoScopes.resolveName(place, name)` walks outward from the reference using stub parents when
available (`stubAwareParent`): block (declaration before use; a local `type` is visible inside
its own declaration), `case`/`comm` clauses (type-switch binding, `recv` vars), `if`/`for`/`switch`
init statements, `range` variables (visible in the body, not in the range expression), function
literal and declaration signatures (receiver, params, results, type params), receiver type
parameters (`func (l *List[U]) M()`: `U` resolves to the receiver's identifier and is typed as
the type's parameter), type spec type parameters, file scope (imports: named, package name,
dot, blank), package scope (all files of the package per build context, through `GoFile` stub
accessors), dot imports, universe (`$GOROOT/src/builtin/builtin.go`). `init` is not referable;
`_` never declares. Labels live in a separate function-level space (`resolveLabel`).

`GoPackageModel` turns the project model's `GoPackage` into a `PackageScope` (declarations by
name, methods by receiver type name) merged from per-file lists (`GoPackageModel.fileDeclarations`)
and cached on the package's own stamp (see Caching); for in-memory files
it falls back to sibling files with the same package clause. `GoUniverse` lists the predeclared
names and locates `builtin.go`.

## References (`semantic.resolve`)

Core only knows that reference expressions, type references, label refs and import specs are
reference sites; `GoReferenceProvider` (core interface) is implemented by
`GoReferenceProviderImpl` (semantic) and registered as an application service. References:
`GoValueReference` (`x`, `pkg.X`, `v.f`), `GoTypeReference`, `GoLabelReference`,
`GoImportReference` (path -> package directory), `GoFieldKeyReference` (struct literal keys,
including promoted fields and nested literals with elided types). All are poly-variant, cached
by `ResolveCache`; the underlying `GoResolver` results are cached per element on the Go trackers.

Qualified names: `pkg.X` resolves the qualifier to an import first (`Result.Member`), `C.x` to the
cgo sentinel (`Result.Cgo`), otherwise the qualifier is typed and the selector looked up
(`Result.Selection`). Method expressions `T.M` / `(*T).M` are typed as functions with the receiver
as first parameter.

## Expression typing (`semantic.infer`)

`GoTypeBuilder` builds types from type PSI and declarations (named types cached per spec, type
parameters per definition, signatures per function); it is stub-first (variadic flag, array
length and channel direction, tags and `~` come from stubs when the AST is not loaded).
`GoExpressionTyper.typeOf(expr)` covers literals (untyped), identifiers, selectors, calls
(multi-value, builtins, conversions, explicit instantiation, a minimal argument-based inference
for direct type-parameter uses), composite literals (`[...]T` lengths, elided nested types),
function literals, index/slice/instantiation, type assertions, unary/binary operators (untyped
kind promotion, comparisons -> untyped bool, shifts), `var`/`:=` declarations (tuple
destructuring, `v, ok` forms), `range` (array, slice, string, map, chan, int, func iterators),
type-switch bindings (per case clause), method values and expressions. `constantOf(expr)` and
`constantValueOf(def)` evaluate constants with implicit `iota` repetition.

## Non-physical file copies

Completion and intentions run on copies of a file without a directory. Code that needs the package,
directory or module of a `GoFile` goes through `GoPsiUtil.originalFile`/`originalVirtualFile`
(`GoPackageModel`, `GoSemanticService.packageOf`); the copy itself is still resolved and typed directly.

## Caching

`semantic.cache.GoTrackers`, no `PsiModificationTracker.MODIFICATION_COUNT` anywhere. Values
computed at one element (expression types, constants, callee signatures, resolve results, type
nodes, block statement lists) go through `semantic.cache.GoBodyCache.cached(element, key)`; other
callers use `dependencies(element)` (body or package dependencies by position),
`packageDependencies(element)` or `bodyDependencies(file)` (whole-file values, e.g. the
diagnostics list).

- Out-of-block changes (outside function bodies) are tracked per package directory. A project
  package (in project content, not under `vendor`) gets a stamp from one global sequence; its
  tracker (`forPackage(dir)`) reports the newest stamp among the package and the project packages
  it imports transitively (direct imports cached per package stamp; the closure recomputed only
  after some project out-of-block change). An edit in package A thus invalidates A and its
  importers, not unrelated packages.
- Library packages (GOROOT, module cache, vendor, anything outside content) share one tracker,
  `library`, bumped by an out-of-block edit in any library file. Library values depend only on
  it, the project model and project roots, never on project edits.
- Direct imports of a package (the edges of the closure) are cached per package stamp and read
  from `GoFileImportsIndex` (`FileBasedIndex.getFileData`: the import paths of each file, lexer
  only, no stub or AST), resolved with `GoPackageModel.resolveImport(path, VirtualFile)`. Files with
  an unsaved document or a loaded AST read `GoFile.imports` instead (the index may lag behind the
  document); in dumb mode (`IndexNotReadyException`) every file is read through PSI.
- **Names versus meaning.** `PackageScope` (names by file, methods by receiver name) depends on
  `ownPackageDependencies(element)`: the package's own stamp (not below the file-set stamp), the
  project model and roots, not the import closure; library packages depend on `library`. An edit
  in an imported package therefore keeps the importer's scope. The scope merges per-file lists
  (`GoPackageModel.fileDeclarations(file)`, a `CachedValue` on the file depending on
  `fileDeclarationsDependencies(file)` = `forFileOutOfBlock(file)` + roots), so a rebuild after a
  top-level edit re-reads only the edited file's stub. `forFileOutOfBlock` is bumped by the same
  out-of-block events as the package stamp, and by a generic change without precise events.
  Anything that caches types or other meaning derived from a scope keeps `packageDependencies`
  (closure): `GoScopes.importName`, resolve results, `GoTypeBuilder` (methods of named types),
  `GoUniverse.declarations`; they look the scope up on each computation and never cache it.
- Every value depends on `library`, the project-model tracker and a roots tracker (`ModuleRootListener`).
  Adding, removing or renaming a Go file bumps its package; directory moves/deletions in content
  or libraries drop everything (`invalidateAll`).
- **Per-function bodies.** The unit is the outermost body (`GoPsiUtil.outermostBody`): the body of
  a top-level function or method; function literals belong to their enclosing function; a literal
  in package-level code (a `var` initializer) is its own unit. Each unit has one `CachedValue` on
  its body block holding a lazily filled store (one map per cache key, filled get-then-put because
  inference recurses into the same body; a value is stored only if no recursion was prevented
  while computing it, `RecursionManager.markStack`; null results are stored as a sentinel). The
  store depends on the body's tracker (`forBody(block)`, user data on the block), the file's body
  fallback stamp and `packageDependencies(file)`, not on the file tracker. The PSI listener bumps
  the tracker of the outermost body containing the event's parent; a change outside every body is
  out of block (package tracker), which also drops the bodies. The generic `childrenChanged` the
  platform sends for the file after the precise events bumps the fallback stamp (all bodies of the
  file) only when no precise event preceded it. A re-parse that replaces a body block is an
  out-of-block event and the new block starts empty. The store lives on the block (AST), not on the
  stub-based declaration, so it never keeps an unloaded AST reachable.
- Package-level elements keep one `CachedValue` per element under the same keys, depending on
  `packageDependencies`. `forFile(file)` is bumped by every change of the file.
- Lookup cost (every cache access needs the body): `outermostBody` walks AST nodes (no PSI
  `getParent`) to the nearest block and continues from a hint stored on that block (also every 32
  levels inside deep expression chains), valid until the next Go PSI change
  (`GoPsiUtil.treeStamp`). A store validated at the current tree stamp and project-model count is
  reused without re-checking its `CachedValue` (physical files only). `warmLeftSpine` skips the
  spine walk when the left operand is already cached.
- Known gap: a library package importing a project package (module cycle) is not invalidated by
  edits of that project package.

### Performance on huge generated files

Generated files in GOROOT exposed walks that were linear per lookup and so quadratic per file.
Each was found with JFR through the dev test `GorootSlowFilesCheckCorpusTest`:

```
./gradlew :go-psi-semantic:corpusTest --tests "*GorootSlowFilesCheckCorpusTest" -Dgopsi.check.files=default \
    "-Pgopsi.corpus.jvmArgs=-XX:StartFlightRecording=filename=check.jfr,settings=profile"
```

`default` checks the three files below. A comma-separated list of paths relative to
`GOROOT/src` (at most three) checks other files. The test prints the first and warm `check`
time per file. Without the property it does nothing, so the corpus gate is not slower.

| Hot spot | File | Fix |
|---|---|---|
| Per-name resolve walked every preceding statement of the block | `ssagen/simdAMD64intrinsics.go` (one function of thousands of statements) | `GoScopes` caches each block's or case clause's statement list (body store). Lists of 32 or more statements also get a name index; the visible prefix is found by binary search on statement offsets. |
| `switch` init statement and tag lookups scanned all case clauses | `ssa/rewriteAMD64.go` (switch of thousands of cases) | `GoPsiUtil` scans only the header, up to `{` |
| `GoFile` accessors with the AST loaded walked all top-level nodes on every call (`imports` per resolved name) | `rewriteAMD64.go` | `GoFile.topLevel` caches its AST results per element type, keyed on the file's modification stamp |
| `iotaOf` and implicit repetition walked back over the previous specs of a group | `ssa/opGen.go` (`const (...)` of 8421 specs) | `GoExpressionTyper` computes each group's layout (iota, source spec) in one pass, cached on the declaration; `repeatedConstSpec(spec)` is public |

`GorootCheckCorpusTest`, same session, before -> after: `simdAMD64intrinsics.go` went from
7216 ms to under 0.9 s, `rewriteAMD64.go` from 6851 ms to 2.8-3.6 s, and the whole gate from
140 s to 91 s. Diagnostics are unchanged (13).

`opGen.go` stays at about 10 s. `GoChecker.previousSpecWithValues` repeats the same quadratic
walk. Replacing it with `typer.repeatedConstSpec(spec)?.takeIf { it.expressionList.isNotEmpty() }`
was measured in this branch (the warm check fell from 6.1 s to 1.1 s) and then reverted, because
`semantic.check` belongs to another branch.

Under the light test project, the first touch of a package also builds stubs for all its files
by parsing them, because GOROOT is not indexed there. For the `ssa` package that is about
25 MB of source. Further first-touch costs are that parse (`amd64/galign.go`, about 3 s) and
the AST load of the file itself.

## Tests

- `GoResolveTest`: marker fixtures under `testData/resolve/<group>` (`/*def*/`, `/*ref*/`,
  `/*ref:Name*/`, `/*no ref*/`, `/*ref GOROOT:path*/`).
- `GoTypeOfTest`: `expr /*T: type*/` goldens under `testData/types/exprs`.
- `GoConstantTest`, `GoTypePredicatesTest`: constant evaluation and pure type-model tests.
- `GoResolveAstLoadingTest`: cross-file resolution with `AstLoadingFilter` + file loading
  assertions (no AST of the declaring file is loaded).
- `GoTypesTestdataTest`: harness over `testData/types/goroot/check` (copies of
  `$GOROOT/src/internal/types/testdata/check`), counts unresolved references and `ERROR` sites.
- Corpus gates (`:go-psi-semantic:corpusTest`): `GorootResolveCorpusTest` and
  `GomodcacheResolveCorpusTest` resolve every reference of every buildable non-test file; metrics
  in `testData/metrics/goroot-src-resolve.json` and `gomodcache-golang-org-x-resolve.json`.

## Corpus results and unresolved classes

`GorootResolveCorpusTest` (host context linux/amd64 + cgo, non-test files, `testdata` skipped,
cgo files skipped) and `GomodcacheResolveCorpusTest` (golang.org/x) report unresolved
references by class; see `testData/metrics/*-resolve.json` for the accepted numbers. Classes:

- `import.missing-dependency` / `package-member.missing-dependency`: the module is not in the
  local module cache (golang.org/x modules depend on modules never downloaded here). Not a
  resolver defect.
- `selector.unknown-qualifier-type`: the qualifier's type is unknown, almost always a cascade
  from a missing dependency or from generic inference that Phase 5b will cover (unification
  through nested types, methods on inferred instantiations).
- `selector.missing-member`: the qualifier is typed but the member is not found; remaining cases
  are generic-instantiation and cgo (`C.` types flowing into fields) related.
- `type` / `unqualified`: a few hundred, concentrated in files whose declarations come from
  oversized generated files; the corpus task raises `idea.max.intellisense.filesize` to 20 MB so
  that `cmd/compile/internal/ssa/opGen.go` gets PSI. In the IDE the platform's default limit
  (2.5 MB) applies and such files show as plain text.
- Cyclic evaluations hit `RecursionManager` prevention and yield unknown for that evaluation;
  the corpus tests disable the platform's test-only assertions for this.

## Generic type inference (Phase 5b, `semantic.infer.GoUnifier` / `GoInference`)

A port of go/types `infer.go` + `unify.go`:

- **Unification** (`GoUnifier`): binds the type parameters of a call (and of generic function
  values passed as arguments) so that parameter and argument types become identical. Inexact
  mode (go/types "assign" mode): a defined type unifies with the underlying type of an
  unnamed one, channel directions are ignored, two top-level interfaces (same origin or not)
  unify through their methods (interface inference), nested types unify exactly. Untyped
  constants never bind a parameter during unification.
- **Order** (`GoInference.infer`): typed arguments; then constraint type inference to a fixed
  point (a bound parameter's core type is unified with the bound type, including channel core
  types with compatible directions; an unbound parameter whose constraint has a single
  non-tilde term, or a core type while only constants were supplied, is bound to it; method
  requirements of the constraint unify with the bound type's methods); then untyped constants
  (largest kind, defaulted); then substitution to a fixed point with cycle detection.
  Partially instantiated signatures carry `GoSignatureType.partialSubst` so that the remaining
  parameters' constraints are substituted correctly.
- `GoExpressionTyper.calleeSignature(call)` is the entry point (cached per call): the
  instantiated signature, or null for builtins and conversions. `unsafe.Sizeof/Alignof/
  Offsetof/Slice/SliceData/String/StringData/Add` are typed specially.
- **Type sets**: `GoInterfaceType.typeTerms` (intersection of unions over embedded
  interfaces), `isComparableConstraint` (`comparable` is a marked empty interface),
  `GoTypeParamType.coreType` / `singleExactTerm`, `GoTypePredicates.satisfies` (spec
  "Satisfying a type constraint": implements, or comparable + the rest) and
  `satisfactionFailure` (go/types wording: `missing in`, `missing method`, `method m has pointer
  receiver`, `empty type set`). `comparable(type, strict)` distinguishes strictly comparable
  types from interfaces.
- Coverage (`testData/types/inference/a.go`, 66 goldens): direct, nested (slices, maps,
  pointers, channels, functions, instantiated named types), function-typed arguments incl.
  generic function values (`MapSlice(ints, Identity[int])`), explicit and partial instantiation,
  variadic + spread, constraint inference (`S ~[]E`), method calls on inferred instantiations,
  stdlib `slices`/`maps`/`iter`/`cmp`/`sync`/`atomic`.

## Diagnostics (`GoSemanticService.check(file)`, `semantic.check.GoChecker`)

A conservative port of go/types checks with go/types wording; every check fires only when the
involved types are fully known (`GoTypePredicates.isKnown`), so missing dependencies never
cascade. Operand descriptions follow `operand.String` (`x (variable of int type MyInt)`,
`1 (untyped int constant)`, `c (constant 991 of type float32)`, `P{} (value of struct type P)`,
`x (variable of type T constrained by any)`, `f() (no value)`); expressions are re-printed
from tokens without comments with literal bodies elided (`func(x int) int {...}`). Named types
of other packages are qualified (`big.Float`). Diagnostics that strictly contain another one
are dropped (go/types marks operands invalid after the first error).

`check(file)` is incremental per function body (`semantic.check.GoIncrementalChecker`):

- **Package-level pass** (`GoChecker.checkPackageLevel`): one walk over everything outside
  function bodies (declarations, signatures, type specs, const/var specs and initializers,
  package-level redeclarations, receivers, `init` signatures). It does not enter bodies and
  returns the outermost bodies in document order. Cached on the file with
  `GoTrackers.fileOutOfBlockDependencies` (package dependencies + the file's body fallback
  stamp): edits inside bodies keep it.
- **Init cycles** (`GoChecker.checkInitCycleUnit`) follow function bodies of the file and
  function literals in initializers, so they are cached separately with the trackers of the
  bodies the walk read.
- **Body pass** (`GoChecker.checkBody`) per outermost body (`GoPsiUtil.outermostBody`: a
  top-level function or method body with its function literals, or a function literal in
  package-level code, the same unit as `GoBodyCache`): the function's missing return, every check
  inside the body, unused variables and labels, unused values, and the containment filter among
  the body's own diagnostics. The result (diagnostics with ranges relative to the body start,
  imports used) lives in the body's `GoBodyCache` store, so an edit in one function recomputes
  only that function's result.
- **Assembly** on every call: package-level diagnostics (cached ranges are relative to their
  top-level element), init cycles, body results shifted to the current body offsets, unused
  imports from the union of the imports used by the package level and all bodies. The
  containment filter runs once more across parts: package-level diagnostics against everything,
  body diagnostics against package-level ones. A body pass reports only inside its body or on
  its own signature (parameter redeclarations) and the package-level pass never inside a body,
  so the result equals the single-walk checker `GoChecker.checkMonolithic()` (kept as the
  reference; `-Dgopsi.check.monolithic=true` makes `check(file)` use it).
  `GoIncrementalCheckTest` compares both over `testData/check`, the go/types testdata,
  `net/http/server.go` and `go/types/*.go`, and after edits.

Classes (`GoDiagnostic.code`): `undefined`, `undefined-member` (with go/types `lookupError`
hints: `but does have field x` / `unexported` / `cannot refer to unexported`), `unexported`,
`unused-import`, `unused-variable` (`declared and not used: x`; `x++` and `x += 1` count as
uses, `x = 1` does not; variables of erroneous `:=` statements are marked used), `unused-label`,
`redeclared` (blocks, signatures, package scope across files, `repeated on left side of :=`,
`label L already declared`, `cannot declare main - must be func`), `assignability` /
`representability` (`cannot use ... as T value in <context>`, `(overflows)`, `(truncated)`,
`does not implement I (missing method m | wrong type for method m | method m has pointer
receiver)`, `cannot assign X to Y (in TP)` for type parameters; contexts: assignment, variable
declaration, constant declaration, return statement, argument to f, struct literal, array or
slice literal, map literal, map index, send), `assignment-mismatch` (`initVars` wording for
`var`/`:=`: `extra init expr`, `missing init expr for x`, `multiple-value f() ... in single-value
context`; `assignVars` wording for `=`), `no-value`, `multiple-value`, `call-arity` (`not enough /
too many arguments in call to f`, `have (...)` / `want (...)`), `spread`, `cannot-infer`,
`inference` (`type Y of y does not match inferred type X for T`), `constraint`, `type-args`
(`not enough type arguments for type T: have 1, want 2`, `got N type arguments but want M` at the first
extra argument, `invalid operation: T[int] (T is not a generic type)`, also in composite literal types
`T[A]{}`), `generic-no-instantiation`
(`cannot use generic function f without instantiation` for `var x = f`, `x := f`, `_ = f`, non-function targets, operands
and expression statements; `cannot use generic type T without instantiation` for `new(T)` and `T.m`; in type positions,
receivers and composite literals with the parameter list, `cannot use generic type List[T any] without instantiation`),
`misplaced-type-param` (`cannot use a type parameter as RHS in type declaration`, `term cannot be a type parameter`, `type in
term ~A cannot be a type parameter`), `misplaced-constraint` (`cannot use type comparable outside a type constraint: interface
is (or embeds) comparable`, `... interface contains type constraints` in variable, parameter, result, field and element
types and `new`), `map-key` (`invalid map key type T (missing comparable constraint)`, `invalid map key type []int`), `conversion` (incl. constant representability and
tag-insensitive struct identity), `operator` (mismatched / undefined operators, shifts,
`division by zero`, `negative shift count`, `cannot take address`, `cannot indirect`,
receive/send on wrong channel kinds, `++` on non-numeric), `overflow` (`constant shift
overflow`: untyped integer results above 512 bits), `index` (`cannot index`, `cannot slice`,
`must be integer`, constant out-of-bounds), `literal-index`, `struct-literal`, `map-literal`,
`unknown-field`, `composite-literal`, `type-assertion` (`is not an interface`, `impossible type
assertion`), `type-switch`, `mismatched-types` (switch cases), `condition`, `range`,
`defer-go`, `break-continue`, `label`, `not-constant`, `not-a-type`, `not-expression`,
`blank-value`, `builtin-arg` / `builtin-arity` (per builtin rules, `invalid use of ...`),
`non-function`, `ambiguous-selector`, `method-expression`, `untyped-nil`, `no-new-variables`.
Phase 5c added `missing-return`, `duplicate-case` / `duplicate-default`, `fallthrough`,
`select-case`, `for-post`, `recursive-type`, `init-cycle`, `init-signature`, `iota`,
`unassignable` (`cannot assign to x (neither addressable nor a map index expression)`),
`receiver` (`invalid receiver type T (cannot be a pointer | an interface | unsafe.Pointer)`,
`cannot define new methods on non-local type int`), `embedded-field`, `pointer-method`
(`cannot call pointer method p on T`), `field-selector`, `short-var`.

Builtins, constants, comparisons and declarations (0.2.x, `GoChecker.checkElementMore` and the
builtin helpers): `clear` / `close` / `copy` / `delete` / `append` follow go/types `typeset` /
`sliceElem` over type parameters (`cannot clear x: argument must be (or constrained by) map or
slice`, `cannot close non-channel`, `invalid copy: mismatched slice element types E and F in x`,
`arguments x and y have different element types E and byte`, `maps of m must have identical key
types`, `invalid append: argument must be a slice; have 1st function result (value of type int)`);
`min`/`max` of untyped constants of different kinds; `use of untyped nil in argument to new`; `use of
package p not in selector` (call arguments); `const-type` (`invalid constant type T`); `array-length`
(`invalid array length -1 (untyped int constant)`, `array length f() (value of type int) must be
constant`, `array length 1.5 (untyped float constant) must be integer`; lengths computed from
`unsafe` sizes are skipped); `overflow` for integer literals beyond 512 bits (`constant overflow`) and
`constant bitwise complement overflow`; untyped constants converted to their default type in `var x =`,
`x :=` and `_ =` (`cannot use 1 << 100 (untyped int constant ...) as int value in variable declaration
(overflows)`); implicitly repeated typed constants (`constant 256 overflows byte`); `1 % 1.0` as a float
operation; comparisons of a non-empty interface with an untyped number (`mismatched types I and
untyped int`) and of an incomparable type parameter (`incomparable types in type set`, `empty type
set`); `return-scope` (`result parameter a not in scope at return`); `x.m undefined (type *T is pointer
to type parameter, not type parameter)`; `math.Pi (untyped float constant 3.14159) is not a type` for
qualified variable, field and parameter types; `a redeclared` for struct fields (embedded fields by
type name); `method T.m already declared` through alias receivers; `invalid receiver type A` for an alias
of an unnamed type; `func main must have no arguments and no return values` (package main), `func
main must have no type parameters`, `func init must have a body`. Lookup passes the "reached through
several paths" mark to embedded types (go/types `consolidateMultiples`, ambiguous selectors at any depth)
and tells fields of `type S7 S6` apart from those of `S6`. Float constants render as `%.6g`
(`3.14159`).

Not implemented (documented per line in `testData/types/goroot/allowlist.txt` with the
expected message): interface-vs-concrete comparisons (`slice can only be compared to nil`),
invalid recursive types through expressions (a selector `t3.p` or an array length `[len(T{})]`),
assignability between invalid instantiations, the go/types test builtins `assert`/`trace`, and
parser-level errors go/parser reports but this grammar recovers from (`expected type argument list`,
`expected type`; `interface method must have no type parameters` is reported by the parser). Left out
on purpose: `complex(1<<s, 0)` in a typed declaration (the typer keeps the result untyped), a local
variable named `iota` in a constant declaration, array lengths that turn invalid only in an implicitly
repeated spec (`len([1 - iota]int{})`), `string(1 << s)` (the count may be a constant the plugin cannot
fold), `missing function body` for functions other than `init` (assembly).

### Constants, shifts and conversions (Phase 5c)

- Untyped integer constants are limited to 512 bits (`UNTYPED_INT_PRECISION`, go/types
  `prec`); larger results report `constant <op> overflow` / `constant shift overflow`; constant
  shift counts above 1023 report `invalid shift count`. Typed constant results must be
  representable (`constant 256 overflows byte`, `truncated`), including `-x`/`^x` and repeated
  `iota` specs.
- Non-constant shifts of untyped constants follow go/types `updateExprType`: the constant takes
  its context type (`untypedContextType`: assignment/declaration/return/argument/conversion/index
  /composite element/comparison operand), which must be an integer type (`shifted operand 1.0
  (type float32) must be integer`) that represents the constant (`overflows`, `truncated to`).
  A non-constant untyped shift count is converted to `uint`.
- Builtins fold constants for `len`/`cap` of arrays, `real`/`imag`/`complex`/`min`/`max` of
  constants and `unsafe.Sizeof`/`Alignof`/`Offsetof` (gc sizes, amd64); their results are not
  constant when the operand's size depends on a type parameter (`hasVarSize`).
- Conversions involving type parameters iterate the specific types of the type sets (nested
  constraint interfaces flattened) and report the first failing pair as a cause: `cannot convert
  x (variable of type X constrained by Foo) to type T: cannot convert Foo (in X) to type Far (in
  T)`; a constant converted to a type parameter must be representable by every specific type and
  is not a constant. The pointer conversion rule applies to unnamed pointer types only.
- Core types (`coreTypeOrCause`) give go/types causes for `make`, `range` and slicing over type
  parameters (`[]int and chan int have different underlying types`, `channels ... have
  different element types | conflicting directions`, `no core type`); ordered operators on type
  parameters need every specific type ordered.

### Control flow (Phase 5c, `semantic.check.GoTerminating`)

Spec "Terminating statements" (go/types `isTerminating`/`hasBreak`): `return`, `goto`, `panic`
calls (resolved to the builtin), blocks, `if` with terminating both branches, `for` without
condition/range and without a `break` targeting it, `switch`/`type switch`/`select` with a
default (switch) whose every clause terminates (or falls through) and no targeting `break`,
labeled statements. A function, method or function literal with results whose body is not
terminating reports `missing return` at the closing brace (`GoMissingReturnInspection` in
go-psi-ide, ERROR). Also: `fallthrough` placement, `select` case shape, `for` post statements,
duplicate defaults, blank labels in `break`/`continue`/`goto`, `go`/`defer` of conversions and
of builtins whose result is discarded.

### Declarations (Phase 5c)

- Invalid recursive types (`validCycle`): a type that contains itself without indirection
  through aliases, arrays and struct fields, reported once at the first spec of the cycle with
  the `X refers to Y` chain.
- Initialization cycles: package-level variables and constants whose initializers depend on
  themselves directly or through package-level functions of the same file (function bodies are
  followed), reported once per cycle at its first member.
- Lookup consolidates identical embedded types at the same depth (go/types
  `consolidateMultiples`), so a member reached through two embedding paths is ambiguous.
- Every reported problem is reported once at its source; `GoErrorSiteTestBase` fails on two
  identical diagnostics (the go-psi-ide cache no longer deduplicates).

### Harness

`GoTypesTestdataTest` runs `check(file)` over 50 files copied from
`$GOROOT/src/internal/types/testdata/{check,spec,fixedbugs}` (`SOURCES.txt`) with the go/types
`check_test.go` protocol: each `/* ERROR "substr" */` or `/* ERRORx "re" */` annotation must be
matched by a diagnostic on the same line, and every diagnostic must match an annotation on its
line (otherwise it is a false positive). Files whose first line carries `-lang`/`-goexperiment`
flags are skipped; `/* ERROR */` inside a `//` comment is not an annotation (as in go/types).
`allowlist.txt` lists lines with known-unsupported sites (and tolerates divergent diagnostics on
those lines); the test prints per-file percentages and stale entries, and fails when coverage
drops below `MIN_COVERAGE_PERCENT` (94; after the generics, builtins/constants and Go 1.27 work: 1753/1804 sites, 0 false positives;
Phase 5c: 91%, Phase 5b: 55%). The allowlist is regenerated from the report's "expected ERROR" / "no error
expected" lines. `GoCheckTest` runs the same protocol over hand-written fixtures in
`testData/check/` with no allowlist (undefined, unused, assignability, calls, operators,
literals, generics, statements, declarations, typeparams, controlflow, instantiation, satisfaction,
inferunknown, and a realistic
`clean.go` that must produce no diagnostics).

Corpus gate: `GorootCheckCorpusTest` runs `check(file)` over every buildable non-test file of
GOROOT/src; every diagnostic is a false positive, counted by class in
`testData/metrics/goroot-src-check.json` (may only decrease).

## Semantic tails (0.0.9)

- **Renamed inference** (go/types `renameTParams`): when an argument of a generic call mentions
  the callee's own type parameters (a generic function calling itself, `pdqsortCmpFunc(data, ...)`,
  `intersects(y, x)`), `GoInference.infer` runs on fresh copies made by
  `GoTypeParamType.renamed` and maps the result back. Copies differ from the declared parameter
  by `generation`; `identical` and `equals` compare it, bounds are rewritten to the copies.
- Constraint inference unifies a bound that is a foreign type parameter through that parameter's
  core type (`Grow[S](nil, n)` inside `slices.Concat`, where `S ~[]E` belongs to the caller).
- A receiver type-parameter name (`EI` in `func (d *T[EI, E])`) is a type expression, so `EI(x)` is
  a conversion yielding `EI`.
- Checker additions: union term limits (more than 100 terms, syntactically or after expanding
  embedded interfaces) and overlapping non-interface terms; built-in functions used as values;
  comparisons report at the first operand that is not comparable/ordered; 3-index slices report
  missing middle and final indices at their colons; `identicalIgnoreTags` (conversions) compares
  nested named types by identity.
- Results: GOROOT check gate 13 -> 0 false positives (`cannot-infer` 11 -> 0, `assignability` 2 -> 0),
  golang.org/x sample (`testCheckSampledGolangOrgX`, 111 files) 0 `cannot-infer`; go/types testdata
  1651 -> 1672 of 1804 sites (92.7%), 0 false positives, floor 92%. `cannot-infer` is reported by
  default in the IDE.

## Generics gaps

- Assignability follows go/types `assignableTo` for type parameters: a value of a *named* type is
  not assignable to a type parameter (and a type parameter not to a named type) even when every
  specific type would accept it; untyped values (also `nil`) go through every specific type first, so
  `nil` is not assignable to a method-only constraint and a constant not to `any`. The bidirectional
  channel rule uses spec "named types". The `: cannot assign X to Y (in TP)` cause is added only when
  the other side is unnamed.
- Conversions: integer/float and complex types do not convert into each other (`cannot convert float32
  (in X) to type complex64 (in T)`); typed numeric constants still convert by value.
- Constraint satisfaction: `satisfies` keeps the type terms of an embedded `interface{ comparable;
  ~int | ~string }`; a defined non-interface constraint (`[CC Chan]`) is a single-term type set;
  type arguments of composite literal types are checked (count and constraints); parameters that
  occur only in constraints are bound by core type unification before satisfaction (`chan<- int does
  not satisfy chan int | <-chan int`); a partial instantiation `f[A]` checks the explicit arguments
  (`S (type int) does not satisfy interface{~[]T}`).
- Inference: an untyped `nil` argument does not bind a type parameter (`in call to f, cannot infer
  A`). `cannot infer T` is also reported when some arguments are unknown, but only when no argument can
  bind T: T occurs in no parameter type, its constraint has no core type or single term, no other
  unbound parameter's constraint mentions it, it is the first unbound parameter, and every unknown
  argument consists of names that resolve (go/types skips inference after an invalid argument).
  `does not match` is still reported only when all argument types are known.
- Declarations: `type T[P any] P` is reported and its underlying type is invalid (no follow-up
  receiver errors); invalid recursive types follow embedded interface elements (`type I interface{ I }`,
  `interface{ foo9[A] }`; unions are not followed, as in go/types `validType`); the cycle walk stops at
  declarations of other packages. Duplicate receiver type parameters (`func (T[E, E]) m()`) are
  redeclarations.
- Partial instantiation `f[A]` follows go/types `infer` (wording checked against the 1.27.1 compiler):
  core type unification or the constraint's methods for each explicit argument (`A (type int) does not
  satisfy Stringer (missing method String)`, prefixed `in call to f[A], ` in a call); in a call whose
  remaining parameters are inferred and whose arguments fit, the type set is verified as well.
- Address and assignment: string elements (also through a type parameter with a string term) are not
  addressable; `&x[i]` on a type-parameter-typed map index is reported.

## Go 1.27

- Generic methods: `GoTypeBuilder.methodOf` gives the method signature its own type parameters, so calls
  infer them like generic functions (`l.Apply(func(int) string {...})` is `List[string]`), `l.Apply[T]` and
  method expressions `List[int].Apply[string]` instantiate them (a method expression keeps the method's type
  parameters). The checker reports `cannot use generic function l.Apply without instantiation` (go/types
  says "function" for methods too), `got 2 type arguments but want 1` and `in call to l.Two, cannot infer B`.
- Function type inference in assignment contexts: a generic function value assigned, passed, returned,
  sent or stored (slice, map, struct field) into a function type is accepted (`checkAssignable` does not check a generic signature against a function-type target; the target keeps its declared type); no
  diagnostics on valid code (`testData/check/go127.go`).
- Promoted field keys in struct literals: `GoResolver.resolveFieldKey` and the checker accept promoted
  fields at any depth; the checker reports `invalid implicit pointer indirection to reach Baz` (an embedded
  pointer on the way), `cannot specify promoted field Baz and enclosing embedded field Mid` / `cannot
  specify embedded field Bar and enclosed promoted field Baz` (whichever key comes second), `duplicate
  field name Baz in struct literal`; dotted keys stay `invalid field name Bar.Baz in struct literal`.
- Not here: the language-version errors (`generic method requires go1.27 or later`, `use of promoted
  field Bar.Baz in struct literal of type Foo requires go1.27 or later`): the checker has no per-file
  language version.

## Known gaps

- Diagnostics listed above as not implemented.
- `unsafe` sizes assume gc on amd64 regardless of GOARCH: the project model knows GOARCH
  (`GoToolchainInfo.goarch`), but the sizes are folded by the typer (`infer.GoSizes`) into cached
  constants; array-length checks skip lengths computed from `unsafe` sizes because of it.
- Initialization cycles through functions of other files of the package are not followed.
- Inference failures with unknown argument types: `does not match` is not reported, `cannot infer T`
  only when no argument could bind T (see "Generics gaps").
- Remaining go/types testdata allowlist (60 sites, 49 lines): invalid recursive types through
  expressions (issue39634), follow-up errors of invalid instantiations (issue50929 `does not match`,
  issue51232), interface comparison causes beyond slices/maps/funcs, parser-level errors our grammar
  recovers from (`expected type`, `expected type argument list`), the testdata `assert` builtin, and
  the cases left out on purpose (see "Not implemented").
- `C.xxx` members are a sentinel; cgo files are skipped by the corpus gates.

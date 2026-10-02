# Grammar notes: ambiguities and how GoParserUtil resolves them

Source of truth: go/parser (`src/go/parser/parser.go`, release-branch.go1.27) and
`src/cmd/compile/internal/syntax/parser.go`. Every external rule in `GoParserUtil` names the
go/parser function it ports. Keep this file in sync with `Go.bnf`.

## A. Automatic semicolon insertion (lexer)

Spec: https://go.dev/ref/spec#Semicolons. Implemented in `Go.flex`, not in the parser.
Verified against go/scanner on all of GOROOT/src (`GorootLexerDiffCorpusTest`, 0 mismatches).

- State `MAYBE_SEMICOLON` is entered after: IDENTIFIER, INT/FLOAT/IMAG/CHAR/STRING/RAW_STRING,
  `break continue fallthrough return`, `++ -- ) ] }`.
- In that state a `\n` produces `SEMICOLON_SYNTHETIC` whose token text is exactly that `\n`.
  IntelliJ lexers cannot emit empty tokens, and go/scanner reports the inserted semicolon at the
  newline offset with literal `"\n"`, so offsets match 1:1. `\r` is ordinary whitespace (as in
  go/scanner), so CRLF files behave the same.
- At EOF no token is emitted; the parser's `semi` rule accepts `<<eof>>`.
- Spaces, tabs, `\r`, line comments and block comments keep the state, so the newline after a
  trailing comment still inserts the semicolon.
- Known divergence (allowlisted, 5 places in GOROOT/src): go/scanner puts the semicolon at the
  first newline *inside* a multi-line block comment that follows an eligible token; a token
  cannot be split, so we insert it at the next newline after the comment. If a token follows the
  comment on the same line (`x /*\n*/ y`), no semicolon is inserted.
- Other go/scanner behaviours mirrored: `\f` is BAD_CHARACTER; a BOM is whitespace only at
  offset 0; BAD_CHARACTER keeps the `MAYBE_SEMICOLON` state; a line comment ends only at `\n`
  and includes a trailing `\r`; unterminated string and rune literals end before `\n`, and a
  lone `\` before `\n` or EOF is part of the literal; unterminated block comments run to EOF.
- Parser rule: `semi ::= ';' | SEMICOLON_SYNTHETIC | <<eof>> | &(')' | '}')`, i.e. the
  semicolon is optional before `)` and `}` (spec).

## B. Composite literal vs block in control headers

go/parser: `exprLev` (`< 0` in control clause header, `>= 0` in expression). Implemented in
`GoParserUtil` as a counter in builder user data:

- `<<controlHeader p>>` parses `if`/`switch`/`for` headers with `exprLev = -1` and restores it.
- `<<nested p>>` increments the level inside `(...)`, index brackets, literal values and argument
  lists, so `if x == (T{1}) {` and `for _, v := range []T{{1}} {` work. Function literal bodies
  are lazy (section N) and parsed in their own builder starting at `exprLev = 0`, like go/parser's
  `parseFuncTypeOrLit` (`exprLev++` from at least `-1`), so `if f(func() { x := T{} }) {` works.
- `CompositeLit ::= LiteralTypeExpr LiteralValue` where a named type (`T`, `pkg.T`, `T[int]`) is
  accepted only when `<<compositeLitAllowed>>` (`exprLev >= 0`); struct/array/slice/map literal
  types are always accepted (go/parser `parsePrimaryExpr` case LBRACE, `isLiteralType`).
- The named-type guess uses an unpinned `TypeArguments` variant so it can back out (`m[k]{`).

## C. `[` after a type name: array vs type parameters

go/parser: `parseTypeSpec` + `extractName` + `isTypeElem`. Implemented as the predicate
`<<isTypeParams>>` in `TypeSpec ::= IDENTIFIER (<<isTypeParams>> TypeParameters)? '='? SpecType`.
The predicate inspects the tokens after `[ IDENT` without consuming anything:

| After `[P` | Result |
|---|---|
| `]` | array (`[P]int`) |
| `,` or `[` | type parameters |
| IDENT, `~`, `struct`, `func`, `interface`, `map`, `chan`, `<-chan` | type parameters |
| `*` followed by a type element (`*[]int`, `*struct{}`, `*~T`, `*<-chan T`) | type parameters |
| `*` operand followed by `,`, or by a union whose later term is a type element | type parameters |
| `(` whose content starts with a type element, or `(E)` followed by `,` | type parameters |
| anything else (`*C`, `*C` then `| D`, `(E)`, `.`, `+`, `|`) | array length expression |

Test files: `testData/parser/cases/TypeParamsVsArray.go` (gofmt-clean) and
`TypeParamsParens.go` (parenthesised forms). Note that `type A[P (E)] struct{}` is an **array**
of length `P(E)` in go/parser; only a comma or a type element makes it generic.

## D. Struct field or parameter: `name [` (array field vs embedded instantiated type)

go/parser: `parseArrayFieldOrTypeInstance`. Implemented as `<<typeAfterName>>`, used in
`FieldDeclaration`, `ParameterDeclaration` and `Receiver` after the name list: at `[`, `[]` is a
slice type; otherwise the bracket content is scanned at depth 0: a comma means instantiation
(`T[P1, P2]`), else the token after `]` decides: a type start (IDENT, `*`, `[`, `(`, `func`,
`struct`, `interface`, `map`, `chan`, `<-`) means an array type of the named field
(`f [n]E`), anything else means an embedded or unnamed instantiated type (`T[P]`). The
predicate is needed because `ArrayOrSliceType` pins on `[` and would otherwise not back out of
the named alternative.

## E. Parameter lists: names vs types

go/parser: `parseParameterList`. Implemented by ordered alternatives with backtracking:
`ParameterDeclaration ::= ParamDefinitionList '...'? <<typeAfterName>> Type | '...'? Type`.
For `(a, b int, c string)` the first alternative groups `a, b` with `int`; for `(a, b)` it fails
(no type follows) and each name is re-parsed as a type, which reproduces the "all names are
types" rule. Type parameter lists use `TypeParameterDeclaration ::= TypeParamDefinition (','
TypeParamDefinition)* ConstraintElem` with `ConstraintElem ::= ConstraintTerm ('|'
ConstraintTerm)*` and `ConstraintTerm ::= '~'? Type`. go/parser's "missing parameter
type/name" error for mixed named and unnamed parameters is left to the semantic layer.

### E.1 Variadic parameters (go/parser parseParameterList, parseResult)

`GoParserUtil.variadic` consumes an optional `...` before a parameter type and wraps it in an
error element when it is not allowed: in a result list (the enclosing `(` directly follows the
`)` of the parameter list, found by a backward raw-token scan), when another parameter follows
(`isLastParameter` forward scan), or when several names share the type (`a, b ...int`,
`namesBeforeVariadic` backward scan over `IDENT (',' IDENT)*`; a chain that does not reach `(`
ends in the previous declaration's type). Interface method specs never take type parameters
(`noTypeParameters`, go/parser parseMethodSpec), also in Go 1.27.

### E.2 Const specs without values

`ConstSpecTail ::= Type ('=' ExpressionList)? | '=' ExpressionList`: go/parser accepts
`const x T` (no values); the checker reports `missing init expr for x` (Phase 5b).

## F. Index vs instantiation vs slice

go/parser: `parseIndexOrSliceOrInstance`. `IndexOrSliceExpr ::= Expression '[' <<nested
IndexBody>> ']'` with `<<isSliceBody>>` (a depth-0 `:` before `]`) choosing between
`SliceBody ::= ExpressionOrType? ':' ExpressionOrType? (':' ExpressionOrType?)?` and
`IndexListBody ::= ExpressionOrType (',' ExpressionOrType)* ','?`. Since Phase 5c the 3-index
form parses without its middle and final index (`a[0::]`, `a[::]`) and the checker reports
`middle index required in 3-index slice` / `final index required in 3-index slice` (go/parser
reports them as syntax errors; the tree stays intact either way). Elements may be types
(`f[[]int]`, `make([]T, n)`), so the semantic layer decides whether `x[...]` is an index, a
slice or an instantiation.

Unary `~x` outside a constraint (Phase 5c): `UnaryOp` includes `'~'` so `~x` parses as a unary
expression; the checker reports `cannot use ~ outside of interface or type constraint` (go/parser
parses it the same way and leaves the error to go/types).

## G. Simple statements and headers

go/parser: `parseSimpleStmt(mode)`, `parseIfHeader`, `parseSwitchStmt`, `parseForStmt`. The
left-hand expression list is parsed once; `left` rules add the suffix:
`SimpleStatement ::= ShortVarDeclaration | LeftHandExprList (AssignmentStatement |
SendStatement | IncDecStatement)?`. Header forms are chosen by token lookahead scans over
balanced brackets (`GoParserUtil.scanDepthZero`, capped at 1000 tokens):

- `<<hasInitStatement>>`: a depth-0 `;` before the block, as in `if x := f(); x {`.
- `<<isRangeClause>>`: a depth-0 `range`; `RangeClause ::= (VarDefinitionList ':=' |
  LeftHandExprList '=')? range Expression`; also `for range ch` and `for i := range 10`.
- `<<isTypeSwitch>>`: a depth-0 `.(type)`; `TypeSwitchGuard ::= (VarDefinition ':=')?
  Expression '.' '(' 'type' ')'`.
- `<<isSendStatement>>`: in `select` cases, a depth-0 binary `<-` (preceded by an operand, so
  `<-c <- d` sends on `<-c`) before `:`, `=` or `:=` gives `SendStatement`, else `RecvStatement`.
- `<<isKeyedElement>>`: in literal values, a depth-0 `:` before `,` or `}` gives `Key ':' Value`.
- Labels: `LabeledStatement ::= &(IDENTIFIER ':') LabelDefinition ':' Statement?`.

Expression statements are `SimpleStatement(LeftHandExprList(Expression))`; `IncDecStatement`
and `SendStatement` wrap the `LeftHandExprList` (GoLand's shapes, one parse of the expression).

## H. Method receivers and type parameters

- `Receiver ::= '(' (IDENTIFIER <<typeAfterName>> Type | Type) ','? ')'` covers `(r *R[T, U])`,
  `(R[_])` and `(_ T)`.
- `MethodDeclaration ::= func Receiver IDENTIFIER TypeParameters? Signature Block?` and
  `MethodSpec ::= IDENTIFIER TypeParameters? Signature`: generic methods (Go 1.27) are accepted
  syntactically; the semantic layer reports them for older language versions.

## I. Operand rules ported from go/parser

- Unary `*`: `*[N]T`, `**[N]T`, `*struct{...}` and `*<-chan T` are pointer types, not
  dereferences (`parseOperand` falls back to `tryIdentOrType`), so `(*[2]byte)(p)` parses as a
  conversion of a parenthesised type.
- Unary `<-`: `<-chan T` is a channel type, except when the channel type is immediately
  converted: `<-chan<- int(nil)` is a receive from `(chan<- int)(nil)` (`parseUnaryExpr` case
  ARROW). `(<-chan<-chan<- int)` with a leftover arrow is an error, as in go/parser.
- Types are valid operands where go/parser allows them: parentheses `(T)`, arguments
  `make([]T, n)` and index brackets `f[T]` use `ExpressionOrType ::= Expression | Type`.
- Conversions with a literal type (`[]byte(s)`, `map[K]V(m)`, `chan int(c)`, `func()(f)`,
  `struct{}(x)`, `interface{}(x)`) are `ConversionExpr`; `T(x)` and `(*T)(x)` are `CallExpr`
  and are classified by the semantic layer.
- Go 1.26 / 1.27 forms: `new(expr)` with a value argument and self-referential constraints
  are already valid syntax; generic methods are accepted (section H); nested selector keys in
  struct literals parse as `Key ::= Expression`.

## J. Error recovery

- Loops with recovery use the `!<predicate> Rule semi {pin=1 recoverWhile=...}` idiom: the
  predicate pins the wrapper, so a failing element reports an error, recovery skips junk until
  the stop set, and the loop continues.
- Stop sets contain every token that may legitimately follow the element (Grammar-Kit reports
  an error and skips otherwise): the top level stops at declaration keywords; spec lists (const,
  var, type, import, struct fields, interface elements) stop at terminators, closers and the
  start of the next spec; statements stop at terminators, `}`, `case`/`default` and every
  statement-start token.
- A `func IDENT` inside a block ends the block (it can only start a top-level declaration),
  so an unclosed function body does not swallow the next declaration.
- `FunctionType ::= func &'(' Signature {pin=2}` so `func name` in a type position backs out.
- Pins inside speculative alternatives are avoided (`TypeArgumentsNoPin`,
  `<<typeAfterName>>`): Grammar-Kit does not backtrack a pinned sub-rule.
- A missing `package` clause produces an empty error element and parsing continues.
- Recovery goldens: `testData/parser/recovery/*.go`; each has a `func ok() {}` after the broken
  code that must parse cleanly.

### J.1 Unclosed blocks (fuzz finding)

`StatementWithSemi` also stops at `<<columnZeroDeclaration>>`: a `var`/`const`/`type`/`import`/
`func` keyword whose first character is at column 0. For `func` this always ends the block. For
the other keywords the block ends only when no column-0 `}` appears before the next column-0
`func` (or EOF): valid but unformatted code with statements at column 0 (`cases/UnformattedBlock.go`)
keeps parsing, while a block left open by a missing `}` ends with a local error and the
following declarations parse normally. Metric:
`testData/metrics/goroot-src-fuzz.json` `localityViolations` (7 -> 0).

Since function bodies are lazy (section N) these stops are decided by the brace scan of
`GoParserUtil.lazyBlock` on the whole file; inside a body's own parse `columnZeroDeclaration`
never stops at `const`/`type`/`var`/`import` (the scan already decided it does not end there).

## K. Comments and directives

- Doc comments bind to the following declaration: `GoParserUtil.DOC_COMMENT_BINDER` (a
  `WhitespacesAndCommentsBinder` registered through Grammar-Kit `hooks=[leftBinder=...]`)
  includes the comment lines immediately before a declaration, spec, field, method spec, import
  list or package clause when no blank line separates them. Trailing comments on the same line
  stay outside (a semicolon token separates them anyway).
- `//go:build`, `// +build`, `//go:embed`, `//go:generate`, `//go:linkname`, `//export`
  are ordinary line comments in the lexer; the PSI will expose them via `GoFile.directives()`.
- The cgo preamble is the block comment immediately preceding `import "C"`; it is bound to the
  `ImportList`.

## L. Grammar-Kit pitfalls met while writing Go.bnf

- A bare keyword that matches a rule name case-insensitively (`type` vs the rule `Type`)
  resolves to the rule; quote such keywords: `'type'`.
- `extends=Expression` must cover every expression rule including fake bases (`BinaryExpr`),
  or Grammar-Kit silently falls back to plain left recursion (exponential).
- Prefix (unary) rules are pinned after the operator; alternatives that must be tried instead
  (`*[N]T`, `<-chan T`) are excluded in the operator rule with predicates.
- `recoverWhile` runs after successful rules too: anything not in the stop set is reported.
- The composite element of rule `Type` is named `TYPE`, which clashes with a token named
  `TYPE`; the keyword token is `TYPE_`.

## M. Stub policy (Phase 3)

PSI wiring is done with attributes only; rule names and nesting are unchanged.

- Global: `elementTypeFactory=GoElementTypeFactory.stubFactory` (every composite type; stub types
  for the rules below, plain `GoElementType` otherwise), `extends=GoCompositeElementImpl`,
  `implements=GoCompositeElement`. Stubbed rules get `stubClass` + a stub-based `mixin`
  (`GoStubbedElementImpl<S>`, `GoNamedElementImpl<S>` or a concrete `Go*Mixin`); a `mixin`
  overrides the impl superclass chosen by `extends(...)=Statement` (declarations still implement
  `GoStatement`). All type rules share `GoTypeStub` (`stubClass(".*Type|TypeList")`); only `Type`
  carries the mixin, the others inherit it through `extends`. `SpecType` always collapses (no node).
- Stubbed element types (debug name = `GoTypes` field = external id suffix, `go.<NAME>`):
  `PACKAGE_CLAUSE`, `IMPORT_SPEC`, `FUNCTION_DECLARATION`, `METHOD_DECLARATION`, `RECEIVER`,
  `SIGNATURE`, `PARAMETERS`, `RESULT`, `PARAMETER_DECLARATION`, `PARAM_DEFINITION`, `TYPE_SPEC`,
  `TYPE_PARAMETERS`, `TYPE_PARAMETER_DECLARATION`, `TYPE_PARAM_DEFINITION`, `CONSTRAINT_ELEM`,
  `CONSTRAINT_TERM`, `VAR_DECLARATION`, `VAR_SPEC`, `VAR_DEFINITION`, `CONST_DECLARATION`,
  `CONST_SPEC`, `CONST_DEFINITION`, `FIELD_DECLARATION`, `FIELD_DEFINITION`,
  `ANONYMOUS_FIELD_DEFINITION`, `TAG`, `METHOD_SPEC`, `TYPE_REFERENCE_EXPRESSION`,
  `TYPE_ARGUMENTS` and the type nodes `TYPE`, `PAR_TYPE`, `ARRAY_OR_SLICE_TYPE`, `POINTER_TYPE`,
  `FUNCTION_TYPE`, `MAP_TYPE`, `CHANNEL_TYPE`, `STRUCT_TYPE`, `INTERFACE_TYPE`, `TYPE_LIST`.
  `LabelDefinition` is a named element but never stubbed (labels only occur in bodies).
- `shouldCreateStub` (`GoStubPolicy`): declaration roots (package clause, import specs,
  func/method declarations, type specs, var/const declarations) when not inside a `Block`; every
  other stub type only when its direct parent node is stubbed. So nothing in function bodies or
  function literals is stubbed, and types inside expressions (composite literal types,
  conversions, function literal signatures) are not either.
- The stub builder (`GoStubBuilder.skipChildProcessingWhenBuildingStubs`) descends only into stub
  element types and the transparent containers `IMPORT_LIST`, `IMPORT_DECLARATION`,
  `TYPE_DECLARATION`; the platform uses the same filter when binding stubs to a loaded AST.
- Expressions are stored as text in the enclosing stub: var/const initializers in
  `GoVarSpecStub.values`/`GoConstSpecStub.values` (plus `iota` = index of the spec in its group),
  array lengths in `GoTypeStub.detail` (`...` for `[...]T`, `null` for slices), channel direction
  in `GoTypeStub.detail`, qualified type names in `GoTypeReferenceExpressionStub`, tags as raw
  literal text.
- Gotcha: the stub element types are constructed inside the static initializer of the generated
  `GoTypes` interface, so their constructors and initializers must not read `GoTypes` fields
  (token fields are still `null` at that point); read them lazily in methods.

## N. Lazy, reparseable function bodies

Bodies of function and method declarations and of function literals are collapsed into one
`BLOCK` node by the outer parse and parsed when first accessed; an edit inside a body re-parses
only that body. Stub building and indexing never parse bodies (they are not stubbed, section M).

- Element type: `GoTypes.BLOCK` is `GoLazyBlockElementType` (`IReparseableElementType` +
  `ILightLazyParseableElementType`, created by `GoElementTypeFactory`). The PSI class stays
  `GoBlock`/`GoBlockImpl`. Collapsed bodies are `LazyParseableElement`s; nested blocks (`if`, `for`,
  `{}` statements) are ordinary composite nodes of the same type created while a body is parsed.
- Grammar: `FunctionDeclaration`/`MethodDeclaration ::= ... Signature FunctionBody?` with
  `private FunctionBody ::= <<lazyBlock Block>> | Block` (the `| Block` alternative never matches;
  it makes Grammar-Kit generate `getBlock()`), `FunctionLit ::= func Signature <<lazyBlock Block>>`.
  `Block` has `extraRoot=true`: the lazy parse calls `GoParser.parse(BLOCK, builder)`, whose root
  marker collapses with the `Block` marker (`_COLLAPSE_`).
- Extent (`GoParserUtil.lazyBlock`): brace matching on the tokens from `{`. It also stops where the
  eager parse of an unclosed block stopped (section J/J.1): before a `func IDENT`, before a
  column-0 declaration keyword for which `columnZeroDeclaration` holds (evaluated at every such
  token, not only at statement starts), or at EOF. An unclosed body leaves its trailing semicolons
  to the enclosing rule, and its missing `}` counts as an error at the stop token
  (`ErrorState.currentFrame.errorReportedAt`), so the enclosing `semi` does not report a second one.
- Body parse: a fresh `PsiBuilder` over the body text (fresh `exprLev = 0`, `bodyChunk` flag for
  `columnZeroDeclaration`). Tokens are re-lexed (no `reuseCollapsedTokens`: unexpanded bodies keep
  no token arrays). The light variant (`parseContents(LighterLazyParseableNode)`) lets the platform
  diff an expanded body node by node when it compares a re-parsed tree with the old one (a full
  re-parse would otherwise replace a changed body as a whole and lose the precise PSI events).
- Reparse (`isReparseable`), all of:
  1. the node is the body of a top-level function or method whose `func` keyword starts a line, or
     of a function literal inside such a declaration that is not in a lookahead context: not under
     `COMM_CASE`, `LITERAL_VALUE`, `INDEX_OR_SLICE_EXPR`, `ARRAY_OR_SLICE_TYPE`, `PARAMETERS`,
     `TYPE_PARAMETERS`, `FIELD_DECLARATION`, `RECEIVER`, `TYPE_SPEC`, and not in the header of an
     `if`/`for`/`switch` (`hasInitStatement`, `isRangeClause`, `isTypeSwitch`, `isKeyedElement`,
     `isSliceBody`, `isSendStatement`, `typeAfterName`, `isTypeParams` scan at most `SCAN_LIMIT`
     tokens, so their result could change with the literal's length). Other literal bodies, nested
     blocks and package-level literals are not roots; the walk continues to the enclosing body or
     ends in a full re-parse. The column-0 `func` keeps the body out of reach of the column-0 scan
     of an earlier unclosed body (it stops at a column-0 `func`).
  2. the new text, lexed from the initial state (as after `{` anywhere), is `{` ... `}` with the
     first `{` matched by the last `}` ending the text: no stray `}` closing early, no missing `}`,
     no unterminated raw string or block comment swallowing the final `}`, and no `func IDENT` or
     column-0 declaration keyword (where the outer scan could stop). A `}` always leaves the lexer in
     its semicolon-insertion state, so the tokens after the body are unchanged.
  Then the new body text is exactly what a full parse of the new file would collapse, and its parse
  is the same function of that text. The platform merges the new body into the old one
  (`BlockSupportImpl.mergeTrees` -> `DiffTree`): the body node and untouched nodes keep their
  identity and PSI events are fine-grained inside the body (`GoTrackers` attributes them to the
  body, `GoBodyCache` stores keyed on the body block survive).
- Recovery differences to the eager parse (goldens `recovery/MissingClosingBrace`,
  `recovery/UnclosedBlockBeforeDecls`): the missing `}` of an unclosed body is reported at the end of
  the body text (`';', SEMICOLON_SYNTHETIC or '}' expected` without `got 'func'`), and the last
  inserted semicolon stays outside the body. A body closed early by a stray inner `}` (e.g. a missing
  `}` of a composite literal) keeps the rest of its brace-matched text as an error element inside
  the body instead of leaking it to the top level.
- Tests: `GoLazyBodyTest` (laziness, stub building and indexing parse no body, re-parse roots,
  identity, fallbacks for unbalanced braces, raw strings, comments, `func IDENT`, column-0
  declarations), `GoLazyBodyRandomEditTest` (randomized edits over GOROOT files, re-parsed tree ==
  fresh parse), `GoBodyCacheTest.testIncrementalBodyReparseIsAttributedToThatBody`.

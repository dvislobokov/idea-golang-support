# Error codes of go/types: plugin coverage

Codes with examples: 134. By code (best example): ok 129, miss 3, no-site 2. By example: ok 140, miss 6, no-site 2.

Without an example in codes.go: Test, MismatchedPkgName, BadImportPath, InvalidTypeCycle, InvalidInitSig, InvalidMainDecl, UnexportedLitField, BadDecl, UnsupportedFeature, InvalidMethodTypeParams, TooNew.

| code | example | verdict | go/types | plugin | text |
|---|---|---|---|---|---|
| AmbiguousSelector | AmbiguousSelector_1 | ok | ambiguous selector x.i | ERROR: ambiguous selector x.i | 1.0 |
| BadDotDotDotSyntax | BadDotDotDotSyntax_1 | ok | invalid use of [...] array (outside a composite literal) | ERROR: invalid use of [...] array (outside a composite literal) | 1.0 |
| BadOffsetofSyntax | BadOffsetofSyntax_1 | ok | invalid argument: x is not a selector expression | ERROR: invalid argument: x (variable of type int) is not a selector expression | 0.81 |
| BadRecv | BadRecv_1 | ok | method has no receiver | ERROR: <type> or IDENTIFIER expected, got ')' | 0.17 |
| BadTypeKeyword | BadTypeKeyword_1 | ok | use of .(type) outside type switch | ERROR: 'type' unexpected | 0.39 |
| BlankIfaceMethod | BlankIfaceMethod_1 | ok | methods must have a unique non-blank name | ERROR: methods must have a unique non-blank name | 1.0 |
| BrokenImport | BrokenImport_1 | ok | could not import amissingpackage (invalid package name: "") | ERROR: cannot find package "amissingpackage" | 0.48 |
| CannotInferTypeArgs | CannotInferTypeArgs_1 | ok | in call to f, cannot infer T (declared at C:\Users\dvislobokov\idea-golang-support\build\compare\codes\ex\CannotInferTypeArgs_1\example.go:3 | ERROR: in call to f, cannot infer T | 0.33 |
| DivByZero | DivByZero_1 | ok | invalid operation: division by zero | ERROR: invalid operation: division by zero | 1.0 |
| DuplicateCase | DuplicateCase_1 | ok | duplicate case 1 (constant of type int) in expression switch | ERROR: duplicate case 1 in expression switch | 0.76 |
| DuplicateDecl | DuplicateDecl_1 | ok | x redeclared in this block | ERROR: x redeclared in this block | 1.0 |
| DuplicateDefault | DuplicateDefault_1 | ok | multiple defaults (first at C:\Users\dvislobokov\idea-golang-support\build\compare\codes\ex\DuplicateDefault_1\example.go:7:2) | ERROR: multiple defaults in switch | 0.3 |
| DuplicateFieldAndMethod | DuplicateFieldAndMethod_1 | ok | field and method with the same name m | ERROR: field and method with the same name m | 1.0 |
| DuplicateLabel | DuplicateLabel_1 | ok | label L already declared | ERROR: label L already declared | 1.0 |
| DuplicateLitField | DuplicateLitField_1 | ok | duplicate field name i in struct literal | ERROR: duplicate field name i in struct literal | 1.0 |
| DuplicateLitKey | DuplicateLitKey_1 | ok | duplicate index 0 in array or slice literal | ERROR: duplicate index 0 in array or slice literal | 1.0 |
| DuplicateLitKey | DuplicateLitKey_2 | ok | duplicate key "a" in map literal | ERROR: duplicate key "a" in map literal | 1.0 |
| DuplicateMethod | DuplicateMethod_1 | ok | method T.m already declared at C:\Users\dvislobokov\idea-golang-support\build\compare\codes\ex\DuplicateMethod_1\example.go:4:10 | ERROR: method T.m already declared | 0.35 |
| ImpossibleAssert | ImpossibleAssert_1 | ok | impossible type assertion: x.(T) 	T does not implement I (method m has pointer receiver) | ERROR: impossible type assertion: x.(T); T does not implement I (method m has pointer receiver) | 0.99 |
| IncomparableMapKey | IncomparableMapKey_1 | ok | invalid map key type T | ERROR: invalid map key type T | 1.0 |
| IncompatibleAssign | IncompatibleAssign_1 | ok | cannot use x (variable of type []int) as int value in variable declaration | ERROR: cannot use x (variable of type []int) as int value in variable declaration | 1.0 |
| InvalidAppend | InvalidAppend_1 | ok | invalid append: argument must be a slice; have 1 (untyped int constant) | ERROR: invalid append: argument must be a slice; have 1 (untyped int constant) | 1.0 |
| InvalidArrayLen | InvalidArrayLen_1 | ok | invalid array length n | ERROR: invalid array length n | 1.0 |
| InvalidAssert | InvalidAssert_1 | ok | invalid operation: x (variable of type int) is not an interface | ERROR: invalid operation: x (variable of type int) is not an interface | 1.0 |
| InvalidBlank | InvalidBlank_1 | ok | cannot use _ as value or type | ERROR: cannot use _ as value | 0.84 |
| InvalidCall | InvalidCall_1 | ok | invalid operation: cannot call x (variable of type string): string is not a function | ERROR: invalid operation: cannot call x (variable of type string): string is not a function | 1.0 |
| InvalidCap | InvalidCap_1 | ok | invalid argument: s (variable of type int) for built-in cap | ERROR: invalid argument: s (variable of type int) for built-in cap | 1.0 |
| InvalidChanAssign | InvalidChanAssign_1 | ok | cannot use x (variable of chan type T1) as T2 value in variable declaration | ERROR: cannot use x (variable of chan type T1) as T2 value in variable declaration | 1.0 |
| InvalidClear | InvalidClear_1 | ok | invalid argument: cannot clear x (variable of type int): argument must be (or constrained by) map or slice | ERROR: invalid argument: cannot clear x (variable of type int): argument must be (or constrained by) map or slice | 1.0 |
| InvalidClose | InvalidClose_1 | ok | invalid operation: cannot close non-channel x (variable of type int) | ERROR: invalid operation: cannot close non-channel x (variable of type int) | 1.0 |
| InvalidComplex | InvalidComplex_1 | ok | invalid operation: complex(float32(1), float64(2)) (mismatched types float32 and float64) | ERROR: invalid operation: complex(float32(1), float64(2)) (mismatched types float32 and float64) | 1.0 |
| InvalidCond | InvalidCond_1 | ok | non-boolean condition in if statement | ERROR: non-boolean condition in if statement | 1.0 |
| InvalidConstInit | InvalidConstInit_1 | ok | x (variable of type int) is not constant | ERROR: x (variable of type int) is not constant | 1.0 |
| InvalidConstType | InvalidConstType_1 | ok | invalid constant type *int | ERROR: invalid constant type *int | 1.0 |
| InvalidConstVal | InvalidConstVal_1 | ok | cannot convert "hello" (untyped string constant) to type uint | ERROR: cannot convert "hello" (untyped string constant) to type uint | 1.0 |
| InvalidConversion | InvalidConversion_1 | ok | cannot convert x (variable of type float64) to type string | ERROR: cannot convert x (variable of type float64) to type string | 1.0 |
| InvalidCopy | InvalidCopy_1 | ok | invalid copy: arguments x (variable of type []int) and y (variable of type []int64) have different element types int and int64 | ERROR: invalid copy: arguments x (variable of type []int) and y (variable of type []int64) have different element types int and int64 | 1.0 |
| InvalidDeclCycle | InvalidDeclCycle_1 | ok | invalid recursive type: S refers to itself | ERROR: invalid recursive type: S refers to itself | 1.0 |
| InvalidDefer | InvalidDefer_1 | ok | defer requires function call, not conversion int32(i) (value of type int32) | ERROR: defer requires function call, not conversion | 0.74 |
| InvalidDelete | InvalidDelete_1 | ok | invalid argument: m (variable of type string) is not a map | ERROR: invalid argument: m (variable of type string) is not a map | 1.0 |
| InvalidDotDotDot | InvalidDotDotDot_1 | ok | invalid operation: invalid use of ... with built-in len | ERROR: invalid operation: invalid use of ... with built-in len | 1.0 |
| InvalidExprSwitch | InvalidExprSwitch_1 | ok | cannot switch on a (variable of type struct{_ func()}) (struct{_ func()} is not comparable) | ERROR: cannot switch on a (variable of type struct{_ func()}) (struct{_ func()} is not comparable) | 1.0 |
| InvalidGo | InvalidGo_1 | ok | go requires function call, not conversion int32(i) (value of type int32) | ERROR: go requires function call, not conversion | 0.73 |
| InvalidIfaceAssign | InvalidIfaceAssign_1 | ok | cannot use T(1) (constant 1 of int type T) as I value in variable declaration: T does not implement I (missing method f) | ERROR: cannot use T(1) (constant 1 of type T) as I value in variable declaration: T does not implement I (missing method f) | 0.98 |
| InvalidImag | InvalidImag_1 | ok | invalid argument: argument has type int, expected complex type | ERROR: invalid argument: argument has type int, expected complex type | 1.0 |
| InvalidIndex | InvalidIndex_1 | ok | invalid argument: index 5 out of bounds [0:3] | ERROR: invalid argument: index 5 out of bounds [0:3] | 1.0 |
| InvalidIndex | InvalidIndex_2 | ok | invalid argument: index -1 (constant of type int) must not be negative | ERROR: invalid argument: index -1 (constant of type int) must not be negative | 1.0 |
| InvalidIndex | InvalidIndex_3 | ok | invalid argument: index i (variable of type string) must be integer | ERROR: invalid argument: i (variable of type string) must be integer | 0.95 |
| InvalidIndirection | InvalidIndirection_1 | ok | invalid operation: cannot indirect x (variable of type int) | ERROR: invalid operation: cannot indirect x (variable of type int) | 1.0 |
| InvalidInitCycle | InvalidInitCycle_1 | ok | initialization cycle for x | ERROR: initialization cycle for x; x refers to x | 0.78 |
| InvalidInitDecl | InvalidInitDecl_1 | ok | cannot declare init - must be func | ERROR: cannot declare init - must be func | 1.0 |
| InvalidInitDecl | InvalidInitDecl_2 | ok | func init must have no arguments and no return values | ERROR: func init must have no arguments and no return values | 1.0 |
| InvalidIota | InvalidIota_1 | ok | cannot use iota outside constant declaration | ERROR: cannot use iota outside constant declaration | 1.0 |
| InvalidIterVar | InvalidIterVar_1 | ok | range over c (variable of type chan int) permits only one iteration variable | ERROR: range over c (variable of type chan int) permits only one iteration variable | 1.0 |
| InvalidLen | InvalidLen_1 | ok | invalid argument: s (variable of type int) for built-in len | ERROR: invalid argument: s (variable of type int) for built-in len | 1.0 |
| InvalidLitField | InvalidLitField_1 | ok | invalid field name 1 in struct literal | ERROR: invalid field name 1 in struct literal | 1.0 |
| InvalidLitIndex | InvalidLitIndex_1 | ok | index i must be integer constant | ERROR: index i must be integer constant | 1.0 |
| InvalidLit | InvalidLit_1 | ok | invalid composite literal type P | ERROR: invalid composite literal type P | 1.0 |
| InvalidMake | InvalidMake_1 | ok | invalid argument: cannot make int: type must be slice, map, or channel | ERROR: invalid argument: cannot make int: type must be slice, map, or channel | 1.0 |
| InvalidMethodExpr | InvalidMethodExpr_1 | ok | invalid method expression T.m (needs pointer receiver (*T).m) | ERROR: invalid method expression T.m (needs pointer receiver (*T).m) | 1.0 |
| InvalidMinMaxOperand | InvalidMinMaxOperand_1 | ok | invalid argument: true (untyped bool constant) cannot be ordered | ERROR: invalid argument: true (untyped bool constant) cannot be ordered | 1.0 |
| InvalidMinMaxOperand | InvalidMinMaxOperand_2 | ok | invalid argument: s (variable of type []byte) cannot be ordered | ERROR: invalid argument: s (variable of type []byte) cannot be ordered | 1.0 |
| InvalidOffsetof | InvalidOffsetof_1 | ok | invalid argument: field f is embedded via a pointer in S | ERROR: invalid argument: field f is embedded via a pointer in s (variable of struct type S) | 0.8 |
| InvalidOffsetof | InvalidOffsetof_2 | ok | invalid argument: s.m is a method value | ERROR: invalid argument: s.m is a method value | 1.0 |
| InvalidPkgUse | InvalidPkgUse_1 | ok | use of package fmt not in selector | ERROR: use of package fmt not in selector | 1.0 |
| InvalidPostDecl | InvalidPostDecl_1 | ok | cannot declare in post statement | ERROR: cannot declare in post statement of for loop | 0.84 |
| InvalidPtrEmbed | InvalidPtrEmbed_1 | ok | embedded field type cannot be a pointer | ERROR: embedded field type cannot be a pointer | 1.0 |
| InvalidRangeExpr | InvalidRangeExpr_1 | ok | cannot range over f (variable of type float64) | ERROR: cannot range over f (variable of type float64) | 1.0 |
| InvalidReal | InvalidReal_1 | ok | invalid argument: argument has type int, expected complex type | ERROR: invalid argument: argument has type int, expected complex type | 1.0 |
| InvalidReceive | InvalidReceive_1 | ok | invalid operation: cannot receive from non-channel int x (variable of type int) | ERROR: invalid operation: cannot receive from non-channel x (variable of type int) | 0.97 |
| InvalidRecv | InvalidRecv_1 | ok | invalid receiver type **T | ERROR: invalid receiver type **T (cannot be a pointer) | 0.69 |
| InvalidSelectCase | InvalidSelectCase_1 | ok | select case must be send or receive (possibly with assignment) | ERROR: select case must be send or receive (possibly with assignment) | 1.0 |
| InvalidSend | InvalidSend_1 | ok | invalid operation: cannot send to non-channel int x (variable of type int) | ERROR: invalid operation: cannot send to non-channel x (variable of type int) | 0.97 |
| InvalidShiftCount | InvalidShiftCount_1 | ok | invalid operation: shift count x (variable of type string) must be integer | ERROR: invalid operation: shift count x (variable of type string) must be integer | 1.0 |
| InvalidShiftOperand | InvalidShiftOperand_1 | ok | invalid operation: shifted operand s (variable of type string) must be integer | ERROR: invalid operation: shifted operand s (variable of type string) must be integer | 1.0 |
| InvalidSliceExpr | InvalidSliceExpr_1 | ok | invalid operation: 3-index slice of string | ERROR: invalid operation: 3-index slice of string | 1.0 |
| InvalidStructLit | InvalidStructLit_1 | ok | too many values in struct literal of type struct{i int; j int} | ERROR: too many values in struct literal of type struct{i int; j int} | 1.0 |
| InvalidTypeSwitch | InvalidTypeSwitch_1 | ok | i (variable of type int) is not an interface | ERROR: i (variable of type int) is not an interface | 1.0 |
| InvalidUnion | InvalidUnion_1 | ok | cannot use interface{m()} in union (interface{m()} contains methods) | ERROR: cannot use interface{m()} in union (interface{m()} contains methods) | 1.0 |
| InvalidUnsafeAdd | InvalidUnsafeAdd_1 | ok | invalid argument: length float64(1) (constant 1 of type float64) must be integer | ERROR: invalid argument: float64(1) (constant 1 of type float64) must be integer | 0.95 |
| InvalidUnsafeSliceData | InvalidUnsafeSliceData_1 | ok | invalid argument: x (variable of type int) is not a slice | ERROR: invalid argument: x (variable of type int) is not a slice | 1.0 |
| InvalidUnsafeSlice | InvalidUnsafeSlice_1 | ok | invalid argument: x (variable of type int) is not a pointer | ERROR: invalid argument: x (variable of type int) is not a pointer | 1.0 |
| InvalidUnsafeSlice | InvalidUnsafeSlice_2 | ok | invalid argument: length float64(1) (constant 1 of type float64) must be integer | ERROR: invalid argument: float64(1) (constant 1 of type float64) must be integer | 0.95 |
| InvalidUnsafeSlice | InvalidUnsafeSlice_3 | ok | invalid argument: length -1 (constant of type int) must not be negative | ERROR: invalid argument: index -1 (constant of type int) must not be negative | 0.94 |
| InvalidUnsafeString | InvalidUnsafeString_1 | ok | invalid argument: length -1 (constant of type int) must not be negative | ERROR: invalid argument: index -1 (constant of type int) must not be negative | 0.94 |
| JumpIntoBlock | JumpIntoBlock_1 | ok | goto L jumps into block | ERROR: goto L jumps into block | 1.0 |
| JumpOverDecl | JumpOverDecl_1 | ok | goto L jumps over variable declaration at line 5 | ERROR: goto L jumps over variable declaration at line 5 | 1.0 |
| MismatchedTypes | MismatchedTypes_1 | ok | invalid operation: a - b (mismatched types string and int) | ERROR: invalid operation: a - b (mismatched types string and int) | 1.0 |
| MisplacedBreak | MisplacedBreak_1 | ok | break not in for, switch, or select statement | ERROR: break is not in a loop, switch, or select | 0.77 |
| MisplacedConstraintIface | MisplacedConstraintIface_1 | ok | cannot use type I outside a type constraint: interface contains type constraints | ERROR: cannot use type I outside a type constraint: interface contains type constraints | 1.0 |
| MisplacedContinue | MisplacedContinue_1 | ok | continue not in for statement | ERROR: continue is not in a loop | 0.63 |
| MisplacedFallthrough | MisplacedFallthrough_1 | ok | cannot fallthrough in type switch | ERROR: cannot fallthrough in type switch | 1.0 |
| MisplacedLabel | MisplacedLabel_1 | ok | invalid break label L | ERROR: invalid break label L | 1.0 |
| MisplacedTypeParam | MisplacedTypeParam_1 | ok | cannot use a type parameter as RHS in type declaration | ERROR: cannot use a type parameter as RHS in type declaration | 1.0 |
| MisplacedTypeParam | MisplacedTypeParam_2 | ok | embedded field type cannot be a (pointer to a) type parameter | ERROR: embedded field type cannot be a (pointer to a) type parameter | 1.0 |
| MissingFieldOrMethod | MissingFieldOrMethod_1 | ok | T{}.f undefined (type T has no field or method f) | ERROR: T{}.f undefined (type T has no field or method f) | 1.0 |
| MissingInitBody | MissingInitBody_1 | ok | func init must have a body | ERROR: func init must have a body | 1.0 |
| MissingLitField | MissingLitField_1 | ok | unknown field j in struct literal of type struct{i int} | ERROR: unknown field j in struct literal of type struct{i int} | 1.0 |
| MissingLitKey | MissingLitKey_1 | ok | missing key in map literal | ERROR: missing key in map literal | 1.0 |
| MissingReturn | MissingReturn_1 | ok | missing return | ERROR: missing return | 1.0 |
| MixedStructLit | MixedStructLit_1 | ok | mixture of field:value and value elements in struct literal | ERROR: mixture of field:value and value elements in struct literal | 1.0 |
| MultiValAssignOp | MultiValAssignOp_1 | ok | assignment operation += requires single-valued expressions | ERROR: assignment operation += requires single-valued expressions | 1.0 |
| NoNewVar | NoNewVar_1 | ok | no new variables on left side of := | ERROR: no new variables on left side of := | 1.0 |
| NonIndexableOperand | NonIndexableOperand_1 | ok | cannot index x (variable of type int) | ERROR: invalid operation: cannot index x (variable of type int) | 0.8 |
| NonNumericIncDec | NonNumericIncDec_1 | ok | invalid operation: c++ (non-numeric type string) | ERROR: invalid operation: c++ (non-numeric type string) | 1.0 |
| NonSliceableOperand | NonSliceableOperand_1 | ok | cannot slice unaddressable value [...]int{…} (value of type [3]int) | ERROR: cannot slice [...]int{...} (value of type [3]int) | 0.83 |
| NonSliceableOperand | NonSliceableOperand_2 | ok | cannot slice 1 (untyped int constant) | ERROR: cannot slice 1 (untyped int constant) | 1.0 |
| NonVariadicDotDotDot | NonVariadicDotDotDot_1 | ok | cannot use ... in call to non-variadic printArgs | ERROR: have (...) but function is not variadic: cannot use ... in call to non-variadic printArgs | 0.7 |
| NotAGenericType | NotAGenericType_1 | ok | invalid operation: T[int] (T is not a generic type) | ERROR: invalid operation: T[int] (T is not a generic type) | 1.0 |
| NotAType | NotAType_1 | ok | S (package-level variable) is not a type | ERROR: S (package-level variable) is not a type | 1.0 |
| NotAnExpr | NotAnExpr_1 | ok | T (type) is not an expression | ERROR: T (type) is not used | 0.69 |
| NumericOverflow | NumericOverflow_1 | ok | cannot use 1000 (untyped int constant) as int8 value in variable declaration (overflows) | ERROR: cannot use 1000 (untyped int constant) as int8 value in variable declaration (overflows) | 1.0 |
| OutOfScopeResult | OutOfScopeResult_1 | ok | result parameter i not in scope at return | ERROR: result parameter i not in scope at return | 1.0 |
| OversizeArrayLit | OversizeArrayLit_1 | ok | index 2 is out of bounds (>= 2) | ERROR: index 2 out of bounds [0:2] | 0.79 |
| RepeatedDecl | RepeatedDecl_1 | ok | y repeated on left side of := | ERROR: y repeated on left side of := | 1.0 |
| SwappedMakeArgs | SwappedMakeArgs_1 | ok | invalid argument: length and capacity swapped | ERROR: invalid argument: length and capacity swapped | 1.0 |
| SwappedSliceIndices | SwappedSliceIndices_1 | ok | invalid slice indices: 1 < 2 | ERROR: invalid slice indices: 1 < 2 | 1.0 |
| TooManyValues | TooManyValues_1 | ok | multiple-value ReturnTwo() (value of type (int, int)) in single-value context | ERROR: multiple-value ReturnTwo() (value of type (int, int)) in single-value context | 1.0 |
| TruncatedFloat | TruncatedFloat_1 | ok | cannot use 98.6 (untyped float constant) as int value in variable declaration (truncated) | ERROR: cannot use 98.6 (untyped float constant) as int value in variable declaration (truncated) | 1.0 |
| UnaddressableFieldAssign | UnaddressableFieldAssign_1 | ok | cannot assign to struct field m["foo"].i in map | ERROR: cannot assign to struct field m["foo"].i in map | 1.0 |
| UnaddressableOperand | UnaddressableOperand_1 | ok | invalid operation: cannot take address of 1 (untyped int constant) | ERROR: invalid operation: cannot take address of 1 (untyped int constant) | 1.0 |
| UnassignableOperand | UnassignableOperand_1 | ok | cannot assign to c (neither addressable nor a map index expression) | ERROR: cannot assign to c (neither addressable nor a map index expression) | 1.0 |
| UncalledBuiltin | UncalledBuiltin_1 | ok | copy (built-in) must be called | ERROR: copy (built-in) must be called | 1.0 |
| UndeclaredImportedName | UndeclaredImportedName_1 | ok | undefined: types.NotAnActualIdentifier | ERROR: undefined: types.NotAnActualIdentifier | 1.0 |
| UndeclaredLabel | UndeclaredLabel_1 | ok | label L not declared | ERROR: label L not declared | 1.0 |
| UndeclaredName | UndeclaredName_1 | ok | undefined: T | ERROR: undefined: T | 1.0 |
| UndefinedOp | UndefinedOp_1 | ok | invalid operation: operator - not defined on "a" (untyped string constant) | ERROR: invalid operation: operator - not defined on "b" (untyped string constant) | 0.99 |
| UnexportedName | UnexportedName_1 | ok | name flag not exported by package reflect | ERROR: name flag not exported by package reflect | 1.0 |
| UntypedLit | UntypedLit_1 | ok | missing type in composite literal | ERROR: invalid composite literal element type struct{i int}: missing type | 0.42 |
| UntypedNilUse | UntypedNilUse_1 | ok | use of untyped nil in variable declaration | ERROR: use of untyped nil in variable declaration | 1.0 |
| UnusedExpr | UnusedExpr_1 | ok | i * i (value of type int) is not used | ERROR: i * i (value of type int) is not used | 1.0 |
| UnusedImport | UnusedImport_1 | ok | "fmt" imported and not used | ERROR: "fmt" imported and not used | 1.0 |
| UnusedLabel | UnusedLabel_1 | ok | label L declared and not used | ERROR: label L declared and not used | 1.0 |
| UnusedResults | UnusedResults_1 | ok | defer discards result of len(a) (value of type int) | ERROR: defer discards result of len(a) | 0.76 |
| UnusedVar | UnusedVar_1 | ok | declared and not used: x | ERROR: declared and not used: x | 1.0 |
| WrongArgCount | WrongArgCount_1 | ok | not enough arguments in call to f 	have () 	want (int) | ERROR: not enough arguments in call to f; have (); want (int) | 0.98 |
| WrongAssignCount | WrongAssignCount_1 | ok | extra init expr 2 | ERROR: extra init expr 2 | 1.0 |
| WrongResultCount | WrongResultCount_1 | ok | too many return values 	have (number, number) 	want (int) | ERROR: too many return values; have (number, number); want (int) | 0.98 |
| WrongTypeArgCount | WrongTypeArgCount_1 | ok | too many type arguments for type T: have 2, want 1 | ERROR: too many type arguments for type T: have 2, want 1 | 1.0 |
| WrongTypeArgCount | WrongTypeArgCount_2 | ok | cannot use generic function f without instantiation | ERROR: cannot use generic function f without instantiation | 1.0 |
| InvalidDeclCycle | InvalidDeclCycle_2 | miss | invalid recursive type: T refers to itself |  |  |
| InvalidInstanceCycle | InvalidInstanceCycle_1 | miss | instantiation cycle: | 3 WARNING: Unused function 'f' |  |
| InvalidUnsafeSlice | InvalidUnsafeSlice_4 | miss | invalid argument: length uint64(1) << 63 (constant 9223372036854775808 of type uint64) overflows int |  |  |
| InvalidUntypedConversion | InvalidUntypedConversion_1 | miss | cannot convert 1024 (untyped int constant) to type T | 3 WARNING: Unused function 'f' |  |
| TypeTooLarge | TypeTooLarge_1 | miss | a (variable of type [2147483648]E) is too large |  |  |
| TypeTooLarge | TypeTooLarge_2 | miss | s (variable of type struct{_ [2147483648]E; x int}) is too large |  |  |
| BlankPkgName | BlankPkgName_1 | no-site | no error | 1 ERROR: invalid package name _ |  |
| ImportCRenamed | ImportCRenamed_1 | no-site | no error | 3 ERROR: cannot rename import "C" |  |

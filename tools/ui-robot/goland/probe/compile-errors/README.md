# Compile-error probes

Probe files for live checks of compiler / go command errors in the IDE (the plugin's sandbox and GoLand). They are copied with the rest
of `tools/ui-robot/goland/probe/` into `internal/probe/` of the playground module (`example.com/playground`), so import paths start with
`example.com/playground/internal/probe/compile-errors/`. A `// want: <message>` comment stands right above the line that gets the error;
a case that is deliberately not reported has a `SKIPPED.md` with the reason.

## builtins-consts

Builtin, constant, comparison and declaration checks of `go-psi-semantic` (`GoChecker`): one case per file, `// want:` above
the erroneous line; `*.skipped.go` are deliberately not reported (the reason is in the file). `main/` is its own package main.

- `builtins-consts/append-multi-value.go` — append with a multi-value first argument that is not a slice
- `builtins-consts/array-length-negative.go` — negative constant array length
- `builtins-consts/array-length-not-constant.go` — array length from a function call
- `builtins-consts/array-length-not-integer.go` — array length 1.5
- `builtins-consts/array-length-overflow.go` — array length beyond int
- `builtins-consts/array-length-unsafe.skipped.go` — not reported: array lengths computed from unsafe sizes (sizes are folded for gc/amd64 only)
- `builtins-consts/array-length-variable.go` — array length that is a variable
- `builtins-consts/blank-assign-default-overflow.go` — untyped constant assigned to _ overflows its default type
- `builtins-consts/clear-type-param.go` — clear of a type parameter without map/slice core
- `builtins-consts/close-type-param-non-channel.go` — close of a type parameter constrained by any
- `builtins-consts/close-type-param-receive-only.go` — close of a type parameter whose type set has a receive-only channel
- `builtins-consts/compare-interface-untyped.go` — non-empty interface compared with an untyped number
- `builtins-consts/compare-struct-with-slice.go` — struct with a slice field compared with ==
- `builtins-consts/compare-type-param-incomparable.go` — == on a type parameter constrained by any
- `builtins-consts/complex-shift-declaration.skipped.go` — not reported: complex() of a non-constant untyped shift in a typed declaration
- `builtins-consts/const-complement-overflow.go` — ^x of a 512-bit untyped constant
- `builtins-consts/const-invalid-type.go` — typed constant of a non-basic type
- `builtins-consts/const-literal-overflow.go` — integer literal beyond 512 bits
- `builtins-consts/const-repeated-overflow.go` — implicitly repeated typed constant that overflows
- `builtins-consts/const-untyped-float-remainder.go` — % of untyped int and untyped float constants
- `builtins-consts/copy-destination-not-slice.go` — copy into a string constant (type-parameter source)
- `builtins-consts/copy-different-element-types.go` — copy between []myByte and a ~[]byte type parameter
- `builtins-consts/copy-mismatched-slice-elements.go` — copy into a type parameter whose slices differ in element type
- `builtins-consts/copy-string-source.go` — copy from a ~string type parameter into []int
- `builtins-consts/delete-key-types.go` — delete on maps with different key types
- `builtins-consts/delete-type-param-not-map.go` — delete on a type parameter constrained by any
- `builtins-consts/embedded-field-redeclared.go` — the same type embedded twice (T and *T)
- `builtins-consts/init-missing-body.go` — func init without a body
- `builtins-consts/iota-local-variable.skipped.go` — not reported: a local variable named iota used in a constant declaration
- `builtins-consts/main/main-signature.go` — func main with arguments and results (package main)
- `builtins-consts/max-mismatched-untyped.go` — max of an untyped number and an untyped string
- `builtins-consts/method-redeclared-alias.go` — method declared on a type and again through an alias
- `builtins-consts/missing-function-body.skipped.go` — not reported: functions without a body other than init
- `builtins-consts/new-package-name.go` — new of a package name
- `builtins-consts/new-untyped-nil.go` — new(nil)
- `builtins-consts/pointer-to-type-param-selector.go` — method selected through a pointer to a type parameter
- `builtins-consts/qualified-not-a-type.go` — qualified constant used as a type
- `builtins-consts/receiver-alias-unnamed.go` — method on an alias of an unnamed array type
- `builtins-consts/repeated-array-length.skipped.go` — not reported: array length that turns negative in an implicitly repeated constant
- `builtins-consts/return-result-not-in-scope.go` — bare return with a shadowed named result
- `builtins-consts/selector-ambiguous-defined-type.go` — field promoted from S6 and from type S7 S6
- `builtins-consts/selector-ambiguous-depth.go` — field reached through the same embedded type on two paths, three levels deep
- `builtins-consts/short-var-default-overflow.go` — x := 1 << 100
- `builtins-consts/string-shift-conversion.skipped.go` — not reported: non-constant shift of an untyped constant converted to string
- `builtins-consts/struct-field-redeclared.go` — two struct fields with one name
- `builtins-consts/var-default-overflow.go` — var x = 1 << 100

## generics

Generic instantiation, constraint satisfaction and type-parameter checks of the semantic checker (go-psi-semantic `GoChecker`).
One case per file, `package probe`; `// want:` is the exact go/types message above the erroneous line; `*.skipped.go` are cases the
plugin deliberately does not report (the reason is in the file). Cases that need unknown argument types (a missing module) are covered
by `testData/check/inferunknown.go` only: such a probe cannot compile.

- `generics/assign_cause_in_type_param.go` — unnamed value not assignable to every specific type
- `generics/assign_const_any.go` — constant assigned to a type parameter without specific types
- `generics/assign_named_to_type_param.go` — value of a named type assigned to a type parameter
- `generics/assign_nil_type_param.go` — nil assigned to a type parameter with a method-only constraint
- `generics/assign_string_index_type_param.go` — assignment to an element of a string type parameter
- `generics/assign_type_param_to_named.go` — type parameter value assigned to a named type
- `generics/cannot_infer_nil.go` — untyped nil does not infer a type parameter
- `generics/cannot_infer_partial.skipped.go` — not reported: partial instantiation that leaves a parameter uninferable
- `generics/comparable_outside_constraint.go` — comparable used as an ordinary type
- `generics/constraint_outside_constraint.go` — interface with type terms used as a parameter type
- `generics/convert_float_complex.go` — float variable converted to a complex type
- `generics/convert_float_complex_type_param.go` — conversion between float and complex specific types
- `generics/generic_func_no_instantiation.go` — generic function used as a value without instantiation
- `generics/generic_func_to_any.go` — generic function assigned to a non-function type
- `generics/generic_type_composite.go` — composite literal of a generic type without type arguments
- `generics/generic_type_method_expr.go` — method expression on a generic type without type arguments
- `generics/generic_type_new.go` — new() of a generic type without type arguments
- `generics/generic_type_no_instantiation.go` — generic type in a variable type without type arguments
- `generics/generic_type_receiver.go` — method receiver naming a generic type without type parameters
- `generics/go127_embedded_key_after_promoted.go` — Go 1.27 embedded field given after one of its promoted fields
- `generics/go127_generic_method_no_instantiation.go` — Go 1.27 generic method used as a value without instantiation
- `generics/go127_generic_method_type_args.go` — Go 1.27 generic method with too many type arguments
- `generics/go127_promoted_key_after_embedded.go` — Go 1.27 promoted key given together with its embedded field
- `generics/go127_promoted_key_pointer.go` — Go 1.27 promoted key reached through an embedded pointer
- `generics/interface_method_type_params.go` — type parameters on an interface method (reported by the parser)
- `generics/invalid_recursive_generic_interface.go` — generic interface embedding its own instantiation
- `generics/invalid_recursive_interface.go` — interface embedding itself
- `generics/map_key_slice.go` — map keyed by a slice type
- `generics/map_key_type_param.go` — map keyed by a type parameter without comparable
- `generics/nil_argument_type_param.go` — nil passed to a comparable type parameter with methods
- `generics/not_a_generic_type.go` — type arguments on a type without type parameters
- `generics/receiver_type_param_redeclared.go` — receiver type parameter declared twice
- `generics/recursive_array_length.skipped.go` — not reported: cycle through an array length expression
- `generics/recursive_selector.skipped.go` — not reported: cycle through a selector expression
- `generics/satisfy_chan_direction.go` — channel direction outside a constraint with a core type
- `generics/satisfy_comparable_terms.go` — comparable type parameter against a comparable-with-terms constraint
- `generics/satisfy_composite_literal.go` — type parameter argument outside the constraint type set
- `generics/satisfy_partial_core_type.go` — partial instantiation against a core type
- `generics/satisfy_partial_method.go` — partial instantiation missing a constraint method
- `generics/satisfy_partial_method_value.go` — partial instantiation without a call missing a constraint method
- `generics/satisfy_partial_type_set.go` — partial instantiation in a call outside the constraint type set
- `generics/take_address_map_index_type_param.go` — address of a map index through a type parameter
- `generics/term_type_param.go` — type parameter as a union term
- `generics/tilde_term_type_param.go` — type parameter in a ~ term
- `generics/type_args_composite_literal.go` — too few type arguments in a composite literal type
- `generics/type_param_rhs.go` — lone type parameter as the right-hand side of a type declaration

## package-level

Errors that need several files or packages; each case is a directory of its own small packages (stdlib only).

- `importcycle/` — `a` imports `b`, `b` imports `a`: `import cycle not allowed` on both imports (plugin: `GoImportCycle`, text `import cycle: …/a → …/b → …/a`).
- `nomain/` — `package main` without `func main`: `function main is undeclared in the main package` on the package clause (`GoMissingMainFunction`).
- `pkgname/` — `x.go` is `package pkgname`, `y.go` is `package other`: `found packages pkgname (x.go) and other (y.go) in <dir>` on `y.go`'s clause (`GoMultiplePackages`).
- `buildtags/` — `//go:build linux` and `//go:build windows` files declare the same `Name` and `open`: no error in any file, also with `name_windows.go` open on linux (`GoDuplicateDeclaration`).
- `initcycle/` — `var x = f()` in `a.go`, `f` in `b.go` reads `x`: `initialization cycle for x` (plugin text continues `; x refers to f; f refers to x`; `GoInitializationCycle`).
- `goversion/` — own `go.mod` with `go 1.17`: type parameters, `clear`, `min`, range over int and over a function, each with go/types' `… requires go1.N or later (-lang was set to go1.17; check go.mod)` (`GoLanguageVersion`).

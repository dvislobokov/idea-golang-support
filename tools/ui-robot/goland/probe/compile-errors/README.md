# Compile-error probes

Go files copied into the playground and compared with GoLand; one section per area.

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
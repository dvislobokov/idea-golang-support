package probe

var bcShiftS uint

// Not reported: the typer gives complex(1<<s, 0) the type untyped complex instead of complex128
// (go/types converts both non-constant untyped arguments to float64); the shift error itself is reported.
// want: cannot use complex(1 << bcShiftS, 0) (value of type complex128) as int value in variable declaration
var bcComplex int = complex(1<<bcShiftS, 0)

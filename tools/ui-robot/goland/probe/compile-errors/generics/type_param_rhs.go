package probe

// want: cannot use a type parameter as RHS in type declaration
type tpLone[P any] P

package probe

type ngInt int

// want: invalid operation: ngInt[int] (ngInt is not a generic type)
var ngValue ngInt[int]

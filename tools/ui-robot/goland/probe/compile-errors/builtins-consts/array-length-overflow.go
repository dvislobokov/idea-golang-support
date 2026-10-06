package probe

// want: invalid array length 1 << 64 (untyped int constant 18446744073709551616)
var bcArrBig [1 << 64]int

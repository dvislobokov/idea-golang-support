package probe

// want: cannot use 1 << 100 (untyped int constant 1267650600228229401496703205376) as int value in variable declaration (overflows)
var bcVar = 1 << 100

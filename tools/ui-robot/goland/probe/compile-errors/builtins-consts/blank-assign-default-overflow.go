package probe

func bcBlank() {
	// want: cannot use 1 << 100 (untyped int constant 1267650600228229401496703205376) as int value in assignment to _ identifier (overflows)
	_ = 1 << 100
}

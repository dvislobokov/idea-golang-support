package probe

func bcShort() {
	// want: cannot use 1 << 100 (untyped int constant 1267650600228229401496703205376) as int value in assignment (overflows)
	x := 1 << 100
	_ = x
}

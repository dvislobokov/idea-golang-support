package probe

func bcMax() {
	// want: invalid argument: mismatched types untyped int (previous argument) and untyped string (type of "x")
	_ = max(1, "x")
}

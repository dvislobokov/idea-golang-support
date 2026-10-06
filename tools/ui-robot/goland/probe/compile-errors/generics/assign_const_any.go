package probe

func caUse[T any]() {
	// want: cannot use 1 (untyped int constant) as T value in variable declaration
	var x T = 1
	_ = x
}

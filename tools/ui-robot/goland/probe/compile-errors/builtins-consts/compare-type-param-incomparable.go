package probe

func bcCmpT[T any](x, y T) bool {
	// want: invalid operation: x == y (incomparable types in type set)
	return x == y
}

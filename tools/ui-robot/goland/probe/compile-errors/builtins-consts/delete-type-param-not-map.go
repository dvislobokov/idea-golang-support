package probe

func bcDeleteAny[T any](m T) {
	// want: invalid argument: m (variable of type T constrained by any) is not a map
	delete(m, "k")
}

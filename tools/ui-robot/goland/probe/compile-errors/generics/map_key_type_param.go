package probe

// want: invalid map key type T (missing comparable constraint)
func mkKeys[T any](m map[T]int) {}

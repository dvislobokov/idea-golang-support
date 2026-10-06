package probe

func bcCloseAny[T any](ch T) {
	// want: invalid operation: cannot close non-channel ch (variable of type T constrained by any)
	close(ch)
}

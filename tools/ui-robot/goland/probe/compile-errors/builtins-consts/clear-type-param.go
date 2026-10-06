package probe

func bcClear[T any](x T) {
	// want: invalid argument: cannot clear x (variable of type T constrained by any): argument must be (or constrained by) map or slice
	clear(x)
}

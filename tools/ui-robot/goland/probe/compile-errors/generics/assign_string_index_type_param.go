package probe

func siSet[T []byte | string](x T) {
	// want: cannot assign to x[0] (neither addressable nor a map index expression)
	x[0] = 0
}

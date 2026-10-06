package probe

func taAddr[M ~map[int]E, E any](m M, i int) {
	// want: invalid operation: cannot take address of m[i] (map index expression of type E constrained by any)
	_ = &m[i]
}

package probe

func spApp[S interface{ ~[]T }, T any](s S, e T) S { return append(s, e) }

func spUse() {
	// want: S (type int) does not satisfy interface{~[]T}
	_ = spApp[int]
}

package probe

type gcPair[K comparable, V any] struct {
	k K
	v V
}

func gcUse() {
	// want: cannot use generic type gcPair[K comparable, V any] without instantiation
	_ = gcPair{}
}

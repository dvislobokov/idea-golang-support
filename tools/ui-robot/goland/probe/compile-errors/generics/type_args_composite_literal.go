package probe

type taPair[K comparable, V any] struct {
	k K
	v V
}

func taUse() {
	// want: not enough type arguments for type taPair: have 1, want 2
	_ = taPair[string]{}
}

package probe

type gnBox[T any] struct{ v T }

func gnUse() {
	// want: cannot use generic type gnBox without instantiation
	_ = new(gnBox)
}

package probe

type grBox[X any] struct{ x X }

// want: cannot use generic type grBox[X any] without instantiation
func (grBox) m() {}

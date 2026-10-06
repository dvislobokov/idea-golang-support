package probe

type gmList[E any] []E

func (l gmList[E]) Apply[F any](f func(E) F) gmList[F] { return nil }

func gmNoInstUse(l gmList[int]) {
	// want: cannot use generic function l.Apply without instantiation
	_ = l.Apply
}

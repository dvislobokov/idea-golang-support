package probe

type gaList[E any] []E

func (l gaList[E]) Apply[F any](f func(E) F) gaList[F] { return nil }

func gaTypeArgsUse(l gaList[int]) {
	// want: got 2 type arguments but want 1
	_ = l.Apply[int, int]
}

package generics

func Map[A, B any](xs []A, f func(A) B) []B { return nil }

func f() {
	_ = Map(nil, nil)
}

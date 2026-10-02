package generics

func Map[A, B any](xs []A, f func(A) B) []B { return nil }

func f() {
	_ = Map(nil, nil<error descr="in call to Map, cannot infer A">)</error>
}

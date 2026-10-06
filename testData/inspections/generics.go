package generics

type Number interface{ ~int | ~float64 }

func Sum[T Number](xs ...T) T { var s T; return s }

func Map[A, B any](xs []A, f func(A) B) []B { return nil }

type Pair[K comparable, V any] struct {
	Key K
	Val V
}

type List[T any] struct{ items []T }

func f() {
	_ = Sum<error descr="string does not satisfy Number (string missing in ~int | ~float64)">("a")</error>
	_ = Map[int, string, <error descr="got 3 type arguments but want 2">bool</error>](nil, nil)
	var q <error descr="not enough type arguments for type Pair: have 1, want 2">Pair</error>[string]
	_ = q
	var l <error descr="cannot use generic type List[T any] without instantiation">List</error>
	_ = l
	_ = Map(nil, nil<error descr="in call to Map, cannot infer A">)</error>
}

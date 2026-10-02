package generics

type Number interface{ ~int | ~float64 }

type Stringer interface{ String() string }

func Sum[T Number](xs ...T) T { var s T; return s }

func Map[A, B any](xs []A, f func(A) B) []B { return nil }

func Keys[K comparable, V any](m map[K]V) []K { return nil }

func Show[T Stringer](x T) string { return x.String() }

type Pair[K comparable, V any] struct {
	Key K
	Val V
}

type List[T any] struct{ items []T }

func (l *List[T]) Push(v T) {}

type S struct{}

func (S) String() string { return "" }

func f() {
	_ = Sum(1, 2)
	_ = Sum(1.5, 2)
	_ = Sum("a") // ERROR "string does not satisfy Number (string missing in ~int | ~float64)"
	_ = Sum[string]("a") // ERROR "string does not satisfy Number (string missing in ~int | ~float64)"
	_ = Map([]int{1}, func(i int) string { return "" })
	_ = Map[int, string, bool](nil, nil) // ERROR "got 3 type arguments but Map has 2 type parameters"
	_ = Keys(map[string]int{})
	_ = Keys(map[[]int]int{}) // ERROR "[]int does not satisfy comparable"
	_ = Show(S{})
	_ = Show(1) // ERROR "int does not satisfy Stringer (missing method String)"
	var p Pair[string, int]
	_ = p
	var q Pair[string] // ERROR "not enough type arguments for type Pair: have 1, want 2"
	_ = q
	var r Pair[string, int, bool] // ERROR "too many type arguments for type Pair: have 3, want 2"
	_ = r
	var l List // ERROR "cannot use generic type List without instantiation"
	_ = l
	var l2 List[int]
	l2.Push(1)
	l2.Push("a") // ERROR `cannot use "a" (untyped string constant) as int value in argument to l2.Push`
	_ = Map(nil, nil) // ERROR "in call to Map, cannot infer A"
	var bad Pair[[]int, int] // ERROR "[]int does not satisfy comparable"
	_ = bad
}

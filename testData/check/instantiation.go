package instantiation

type List[T any] []T

type Pair[K comparable, V any] struct {
	k K
	v V
}

func (l List[T]) Len() int { return len(l) }

func (p *Pair[K, V]) Key() K { return p.k }

func Reverse[T any](s []T) []T { return s }

func Apply(f func([]int) []int) {}

type Lone[P any] P // ERROR "cannot use a type parameter as RHS in type declaration"

type Paren[P any] (P) // ERROR "cannot use a type parameter as RHS in type declaration"

type Gen[X any] int

func (Gen /* ERROR "cannot use generic type Gen[X any] without instantiation" */) m() {}

func (g Gen[X]) n() {}

func (p *Pair[E, E /* ERROR "E redeclared in this block" */]) dup() {}

var _ = Reverse // ERROR "cannot use generic function Reverse without instantiation"

var _ any = Reverse // ERROR "cannot use generic function Reverse without instantiation"

var _ func([]int) []int = Reverse

var _ = Reverse[int]

func values() {
	r := Reverse // ERROR "cannot use generic function Reverse without instantiation"
	_ = r
	_ = Reverse // ERROR "cannot use generic function Reverse without instantiation"
	_ = Reverse /* ERROR "cannot use generic function Reverse without instantiation" */ == nil
	Reverse // ERROR "cannot use generic function Reverse without instantiation"
	Apply(Reverse)
	_ = Reverse([]int{1})
	_ = Gen /* ERROR "cannot use generic type Gen without instantiation" */ .n
	_ = Gen[int].n
	_ = new(List /* ERROR "cannot use generic type List without instantiation" */)
	_ = new(List[int])
	_ = new(comparable /* ERROR "cannot use type comparable outside a type constraint: interface is (or embeds) comparable" */)
}

type Number interface{ ~int | ~float64 }

type Cmp interface {
	comparable
	String() string
}

var _ comparable // ERROR "cannot use type comparable outside a type constraint: interface is (or embeds) comparable"

var _ Number // ERROR "cannot use type Number outside a type constraint: interface contains type constraints"

var _ []Cmp // ERROR "cannot use type Cmp outside a type constraint: interface is (or embeds) comparable"

func param(x Number /* ERROR "cannot use type Number outside a type constraint: interface contains type constraints" */) {}

type field struct {
	n map[string]Number // ERROR "cannot use type Number outside a type constraint: interface contains type constraints"
}

func constrained[T Number, C Cmp](x T, c C) {}

type Tilde[A any] interface{ ~A /* ERROR "type in term ~A cannot be a type parameter" */ }

type Term[A any] interface{ A /* ERROR "term cannot be a type parameter" */ | int }

type Self /* ERROR "invalid recursive type: Self refers to itself" */ interface{ Self }

type SelfGen /* ERROR "invalid recursive type: SelfGen[A] refers to itself" */ [A any] interface{ SelfGen[A] }

type myInt int

var _ myInt /* ERROR "invalid operation: myInt[int] (myInt is not a generic type)" */ [int]

var _ = myInt /* ERROR "invalid operation: myInt[int, string] (myInt is not a generic type)" */ [int, string](1)

type CycA /* ERROR "invalid recursive type CycA\n\tCycA refers to CycB\n\tCycB refers to CycA" */ interface{ CycB }

type CycB interface{ CycA }

type Fine interface{ m() Fine }

type Named[T any] interface{ Get() T }

type UseNamed[T any] interface{ Named[T] }

type Triple[A, B, C any] struct{ a A }

func lits[P any]() {
	_ = Pair[string, int]{}
	_ = Pair[string]{} // ERROR "not enough type arguments for type Pair: have 1, want 2"
	_ = Triple[int, int]{} // ERROR "not enough type arguments for type Triple: have 2, want 3"
	_ = Pair[[]int, int]{} // ERROR "[]int does not satisfy comparable"
	_ = Pair /* ERROR "cannot use generic type Pair[K comparable, V any] without instantiation" */ {}
	_ = Triple /* ERROR "cannot use generic type Triple[A, B, C any] without instantiation" */ {}
	_ = []Pair[string, int]{{}}
}

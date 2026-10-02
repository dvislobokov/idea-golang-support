// Stub coverage: type parameters, constraints, instantiated types, generic methods and aliases.
package generics

type Number interface {
	~int | ~int64 | ~float64
}

type List[T any] struct {
	items []T
	next  *List[T]
}

type Pair[K comparable, V any] struct {
	Key   K
	Value V
}

type Alias[T any] = List[T]

type Arr [N]int

const N = 4

func Map[T, U any](xs []T, f func(T) U) []U {
	out := make([]U, 0, len(xs))
	for _, x := range xs {
		type local struct{ v U }
		out = append(out, f(x))
	}
	return out
}

func Sum[T Number](xs ...T) (total T) {
	for _, x := range xs {
		total += x
	}
	return
}

func (l *List[T]) Push(v T) *List[T] { return &List[T]{items: append(l.items, v)} }

func (p Pair[K, V]) Swap() Pair[K, V] { return p }

var Default = Pair[string, int]{Key: "a", Value: 1}

package p

type Number interface {
	~int | ~int64 | ~float64
}

type List[T any] struct {
	items []T
}

type Pair[K comparable, V any] struct {
	Key   K
	Value V
}

func Map[T, U any](s []T, f func(T) U) []U {
	r := make([]U, 0, len(s))
	for _, v := range s {
		r = append(r, f(v))
	}
	return r
}

func Sum[T Number](xs ...T) (s T) {
	for _, x := range xs {
		s += x
	}
	return
}

var l = List[int]{items: []int{1}}
var p = Pair[string, int]{Key: "a", Value: 1}
var _ = Map[int, string]

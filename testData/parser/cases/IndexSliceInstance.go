// Exercises index, slice and instantiation expressions (GRAMMAR.md section F):
// 2- and 3-index slices, single and multiple type arguments, nested forms. Type-checks.
package cases

type Pair[K comparable, V any] struct {
	k K
	v V
}

func Id[T any](x T) T { return x }

func Map2[A, B any](a A, b B) (A, B) { return a, b }

func Zero[T any]() (z T) { return }

func exprs(a []int, s string, m map[string][]int, arr [5]int) {
	_ = a[0]
	_ = a[1:2]
	_ = a[:2]
	_ = a[1:]
	_ = a[:]
	_ = a[1:2:3]
	_ = a[:2:3]
	_ = s[1:]
	_ = m["k"][0]
	_ = m["k"][1:2]
	_ = arr[:]
	_ = a[len(a)-1]
	_ = a[a[0]:a[1]]
	_ = Id[int](1)
	_ = Id[[]int](a)
	_ = Id[map[string][]int](m)
	_ = Id[Pair[string, int]]
	_, _ = Map2[int, string](1, "x")
	_ = Zero[Pair[string, Pair[int, []string]]]()
	_ = Pair[string, int]{k: "a", v: 1}
	_ = []Pair[int, int]{{1, 2}}
	f := Id[func(int) int]
	_ = f
	_ = [][]int{a}[0][1:2]
}

package go127

type List[E any] []E

func (l List[E]) Apply[F any](f func(E) F) List[F] {
	r := make(List[F], len(l))
	for i, x := range l {
		r[i] = f(x)
	}
	return r
}

func (l List[E]) First[D any](def D) D { return def }

func (l *List[E]) Fold[A any](init A, f func(A, E) A) A { return init }

type Pair[K comparable, V any] struct {
	k K
	v V
}

func (p Pair[K, V]) Swap[W any](w W) Pair[K, W] { return Pair[K, W]{p.k, w} }

func Map[T, U any](s []T, f func(T) U) []U { return nil }

func methods() {
	l := List[int]{1, 2}
	s := l.Apply(func(x int) string { return "" })
	_ = s /*T: List[string]*/
	_ = l.Apply[bool](nil) /*T: List[bool]*/
	_ = l.First("none") /*T: string*/
	_ = l.First(1.5) /*T: float64*/
	_ = l.Fold(0, func(a int, e int) int { return a + e }) /*T: int*/
	p := Pair[string, int]{}
	_ = p.Swap(true) /*T: Pair[string, bool]*/
	v := l.Apply[float64]
	_ = v /*T: func(f func(int) float64) List[float64]*/
	e := List[int].Apply[string]
	_ = e /*T: func(List[int], f func(int) string) List[string]*/
}

func contexts() {
	var f func([]int, func(int) string) []string = Map
	_ = f /*T: func([]int, func(int) string) []string*/
	var h func([]byte, func(byte) rune) []rune
	h = Map
	_ = h /*T: func([]byte, func(byte) rune) []rune*/
	g := []func([]int, func(int) bool) []bool{Map}
	_ = g[0] /*T: func([]int, func(int) bool) []bool*/
}

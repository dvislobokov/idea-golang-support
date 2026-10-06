package go127

// Generic methods (Go 1.27).
type List[E any] []E

func (l List[E]) Apply[F any](f func(E) F) List[F] {
	r := make(List[F], len(l))
	for i, x := range l {
		r[i] = f(x)
	}
	return r
}

func (l List[E]) First[D any](def D) any {
	if len(l) == 0 {
		return def
	}
	return l[0]
}

func useMethods() {
	l := List[int]{1, 2}
	s := l.Apply(func(x int) string { return "" })
	var _ List[string] = s
	_ = l.Apply[string](func(x int) string { return "" })
	v := l.First("none")
	_ = v
}

// Function type inference in assignment contexts (Go 1.27).
func Map[T, U any](s []T, f func(T) U) []U { return nil }

func useInference() {
	var f func([]int, func(int) string) []string = Map
	_ = f
	g := []func([]int, func(int) bool) []bool{Map}
	_ = g
	var h func([]byte, func(byte) rune) []rune
	h = Map
	_ = h
}

// Promoted field keys in struct literals (Go 1.27).
type Bar struct{ Baz int }
type Foo struct {
	Bar
	Name string
}

func useKeys() {
	_ = Foo{Baz: 1, Name: "x"}
}

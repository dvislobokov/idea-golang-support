package satisfaction

type Chan chan int

type Basic int

type Iface interface{ m() }

func assign[TP0 any, TP1 ~chan int, TP2 ~chan int | ~chan byte, TI Iface](c chan int, C Chan, x1 TP1, x2 TP2, xi TI) {
	var _ TP1 = c
	var _ TP1 = C // ERROR "cannot use C (variable of chan type Chan) as TP1 value in variable declaration"
	var _ TP0 = c // ERROR "cannot use c (variable of type chan int) as TP0 value in variable declaration"
	var _ TP2 = c // ERROR "cannot use c (variable of type chan int) as TP2 value in variable declaration: cannot assign chan int to chan byte (in TP2)"
	c = x1
	C = x1 // ERROR "cannot use x1 (variable of type TP1 constrained by ~chan int) as Chan value in assignment"
	xi = nil // ERROR "cannot use nil as TI value in assignment"
	var _ TP0 = 1 // ERROR "cannot use 1 (untyped int constant) as TP0 value in variable declaration"
}

func ptrs[P *int | *string](p P) {
	p = nil
	_ = p
}

func eql[T comparable](x, y T) bool { return x == y }

func callNil[Y interface {
	comparable
	m()
}](y Y) {
	eql(y, nil /* ERROR "cannot use nil as Y value in argument to eql" */)
}

func g[_ interface{ interface{ comparable; ~int | ~string } }]() {}

func inst[P comparable, Q interface{ comparable; ~int | ~string }]() {
	_ = g[Q]
	_ = g[P /* ERROR "P does not satisfy interface{interface{comparable; ~int | ~string}}" */]
}

type T1[P interface{ ~uint }] struct{}

func lit[P any]() {
	_ = T1[P /* ERROR "P does not satisfy interface{~uint}" */]{}
}

type Float interface{ ~float32 | ~float64 }

type Complex interface{ ~complex64 | ~complex128 }

func conv[X, T Float | Complex](x X) T {
	return T(x /* ERROR "cannot convert x (variable of type X constrained by Float | Complex) to type T: cannot convert float32 (in X) to type complex64 (in T)" */)
}

type Uint interface{ ~uint }

type T2[U Uint] struct{ s U }

func newT2[U any]() T2[U /* ERROR "U does not satisfy Uint" */] {
	return T2[U /* ERROR "U does not satisfy Uint" */]{}
}

func named[CC Chan, BB Basic](c chan int, C Chan) {
	var _ CC = C // ERROR "cannot use C (variable of chan type Chan) as CC value in variable declaration"
	var _ CC = c
	var _ BB = 1
}

func keys[T any, C comparable, I interface{ ~int | ~string }](map[T /* ERROR "invalid map key type T (missing comparable constraint)" */]int, map[C]int, map[I]int, map[any]int) {
}

var _ map[[]int /* ERROR "invalid map key type []int" */]int

func addr[M ~map[int]E, S ~[]E, E any](m M, s S, i int) {
	_ = &m /* ERROR "invalid operation: cannot take address of m[i] (map index expression of type E constrained by any)" */ [i]
	_ = &s[i]
}

func strs[T string, U []byte | string, V []byte](t T, u U, v V) {
	t /* ERROR "cannot assign to t[0] (neither addressable nor a map index expression)" */ [0] = 0
	u /* ERROR "cannot assign to u[0] (neither addressable nor a map index expression)" */ [0] = 0
	v[0] = 0
	var s string
	s /* ERROR "cannot assign to s[0] (neither addressable nor a map index expression)" */ [0] = 0
	_ = s
}

func one[A any](A) {}

func infer() {
	one(nil /* ERROR "in call to one, cannot infer A" */)
	one(1)
}

func f1[T any, C chan T | <-chan T](ch C) {}

func chans(a chan int, b <-chan int, c chan<- int) {
	f1(a)
	f1(b)
	f1 /* ERROR "chan<- int does not satisfy chan int | <-chan int (chan<- int missing in chan int | <-chan int)" */ (c)
}

func app[S interface{ ~[]T }, T any](s S, e T) S { return s }

type Stringer interface{ String() string }

func show[A Stringer, B any](a A, b B) {}

func partial() {
	_ = app[int /* ERROR "S (type int) does not satisfy interface{~[]T}" */]
	_ = app[[]int]
	_ = app[[]int, int]
	show[int /* ERROR "in call to show[int], A (type int) does not satisfy Stringer (missing method String)" */](1, "x")
	_ = show[int /* ERROR "A (type int) does not satisfy Stringer (missing method String)" */]
	_ = app[int /* ERROR "in call to app[int], S (type int) does not satisfy interface{~[]T}" */](1, 2)
	num[string /* ERROR "string does not satisfy Number (string missing in ~int | ~float64)" */]("a", 1)
}

type Number interface{ ~int | ~float64 }

func num[A Number, B any](a A, b B) {}

func convOk(f float32) {
	const c float32 = 1
	_ = complex64(c)
	_ = complex64(f /* ERROR "cannot convert f (variable of type float32) to type complex64" */)
}

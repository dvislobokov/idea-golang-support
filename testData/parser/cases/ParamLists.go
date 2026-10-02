// Exercises parameter and type parameter lists (GRAMMAR.md section E): bare types,
// grouped names, variadics, results, and constraints with ~ and |. Type-checks.
package cases

type Number interface {
	~int | ~int64 | ~float64
}

type (
	F1 func(int, string)
	F2 func(a, b int, c ...string)
	F3 func(...int)
	F4 func(x int, _ string) (n int, err error)
	F5 func([]int, map[string]int) (int, error)
	F6 func(func(int) bool, ...func())
)

func P1(int, string)                      {}
func P2(a, b int, c ...string)            {}
func P3(_ int, _ ...bool)                 {}
func P4() (a, b int, err error)           { return }
func P5(f func(a, b int) int) int         { return f(1, 2) }
func P6(ch <-chan int, out chan<- string) {}

func T1[T any](x T) T                           { return x }
func T2[K comparable, V any](m map[K]V) int     { return len(m) }
func T3[T ~int | ~string](x T) T                { return x }
func T4[T Number](xs ...T) (sum T)              { return }
func T5[S ~[]E, E any](s S) E                   { return s[0] }
func T6[T, U any](t T, u U) (T, U)              { return t, u }
func T7[T interface{ ~int | ~uint }, U *T](u U) {}
func T8[P *int | *string](p P)                  {}

type TP1[T any, U ~[]T] struct{}
type TP2[A, B ~int | ~string, C any] struct{}

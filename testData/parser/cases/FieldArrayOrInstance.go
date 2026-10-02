// Exercises "name [" in struct fields: array/slice field versus embedded instantiated
// type (GRAMMAR.md section D, parseArrayFieldOrTypeInstance). Type-checks.
package cases

const N = 3

type G[T any] struct{ v T }

type H[K comparable, V any] map[K]V

type Fields struct {
	a [N]int
	b []int
	c [N + 1]string
	d [2][3]byte
	e [len("ab")]bool
}

type Embedded struct {
	G[int]
	H[string, int]
	x [N]G[int]
	y []H[string, bool]
}

type Mixed[T any] struct {
	G[T]
	items [N]T
	list  []G[T]
	m     map[string][]G[T]
}

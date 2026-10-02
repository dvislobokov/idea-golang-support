// Exercises method receivers (GRAMMAR.md section H): value, pointer, blank and
// unnamed receivers, receivers with type parameters (including `_`). Type-checks.
package cases

type R struct{}

type G[T any] struct{ v T }

type M[K comparable, V any] map[K]V

func (R) A()              {}
func (r R) B()            {}
func (r *R) C()           {}
func (*R) D()             {}
func (_ R) E()            {}
func (_ *R) F()           {}
func (r R) G(a, b int)    {}
func (R) H() (int, error) { return 0, nil }

func (g G[T]) Get() T      { return g.v }
func (g *G[T]) Set(v T)    { g.v = v }
func (G[_]) Len() int      { return 0 }
func (g *G[_]) Reset()     {}
func (G[U]) Other(u U) U   { return u }
func (m M[K, V]) Len() int { return len(m) }
func (m M[K, _]) Has(k K) bool {
	_, ok := m[k]
	return ok
}
func (m *M[_, V]) Clear() {}
func (M[_, _]) Nop()      {}

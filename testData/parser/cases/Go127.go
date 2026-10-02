// Exercises Go 1.26 and Go 1.27 syntax, one declaration per release note item.
//
// Go 1.26 release notes, "Changes to the language":
//   - new(expr): the operand of the built-in new may be an expression, not only a type
//     (newInt, newString, newStruct, newBool, pkgPtr).
//   - Self-referential type parameter constraints: a generic type may refer to itself in
//     its own type parameter list (Adder, Ordered2, Node).
//
// Go 1.27 release notes, "Changes to the language":
//   - A method declaration may declare type parameters (List.Map, List.Fold, Box.Apply,
//     Pair.Swap, Ptr.Set, aliasRecv.Run), including value, pointer and parenthesized receivers,
//     constraints with type sets, and calls with explicit and inferred type arguments
//     (useGenericMethods), method values and method expressions' instantiation.
//   - Function type inference applies in all assignment contexts involving functions
//     (inferenceContexts).
//   - A key in a struct composite literal may be any valid field selector, so promoted
//     fields can be used directly as keys (promotedKeys). A dotted key such as p.x is still
//     rejected by the type checker and is therefore not included.
//
// Valid Go 1.27: type-checks with `go vet` under `go 1.27`.
package cases

import "fmt"

// Go 1.26: new(expr).
type point struct{ x, y int }

var pkgPtr = new(42)

func newExprs(i int, s string) {
	newInt := new(i + 1)
	newString := new(s + "!")
	newStruct := new(point{1, 2})
	newBool := new(i > 0)
	newConst := new(3.5)
	newCall := new(len(s))
	newArr := new([2]int{i, i})
	_, _, _, _, _, _, _ = newInt, newString, newStruct, newBool, newConst, newCall, newArr
}

// Go 1.26: self-referential constraints.
type Adder[A Adder[A]] interface {
	Add(A) A
}

type Ordered2[T Ordered2[T]] interface {
	Less(T) bool
}

type Node[N Node[N]] interface {
	Next() N
	comparable
}

type Num int

func (n Num) Add(m Num) Num { return n + m }

func sum[A Adder[A]](xs ...A) A {
	var zero A
	for _, x := range xs {
		zero = zero.Add(x)
	}
	return zero
}

// Go 1.27: generic methods.
type List[E any] []E

func (l List[E]) Map[R any](f func(E) R) List[R] {
	out := make(List[R], 0, len(l))
	for _, e := range l {
		out = append(out, f(e))
	}
	return out
}

func (l List[E]) Fold[A any](init A, f func(A, E) A) A {
	for _, e := range l {
		init = f(init, e)
	}
	return init
}

func (l List[E]) Collect[K comparable, V any](key func(E) K, val func(E) V) map[K]V {
	m := make(map[K]V)
	for _, e := range l {
		m[key(e)] = val(e)
	}
	return m
}

type Box[T any] struct{ v T }

func (b *Box[T]) Apply[U any](f func(T) U) *Box[U] {
	return &Box[U]{f(b.v)}
}

func (b Box[_]) Describe[X fmt.Stringer](x X) string { return x.String() }

type Pair[A, B any] struct {
	a A
	b B
}

func (p Pair[A, B]) Swap[C ~int | ~string](c C) (Pair[B, A], C) {
	return Pair[B, A]{p.b, p.a}, c
}

func (p *(Pair[A, B])) Reset[Z any]() Z {
	var z Z
	return z
}

type Ptr struct{}

func (*Ptr) Set[T any](v T) {}

func (Ptr) _[_ any]() {}

type aliasRecv = *Ptr

func (r aliasRecv) Run[T any, U ~[]T](u U) int { return len(u) }

type Strong string

func (s Strong) String() string { return string(s) }

func useGenericMethods() {
	l := List[int]{1, 2, 3}
	strs := l.Map(func(i int) string { return fmt.Sprint(i) }) // inferred
	strs = l.Map[string](func(i int) string { return "" })     // explicit
	total := l.Fold[int](0, func(a, e int) int { return a + e })
	total = l.Fold(0, func(a, e int) int { return a + e })
	_ = l.Collect[int, string](func(i int) int { return i }, func(i int) string { return "" })
	_, _ = strs, total

	b := &Box[int]{1}
	_ = b.Apply(func(i int) string { return "" }).Apply(func(s string) int { return len(s) })
	_ = b.Describe(Strong("s"))
	_ = b.Describe[Strong]("s")

	p := Pair[int, string]{1, "a"}
	swapped, c := p.Swap(7)
	swapped, c = p.Swap[int](7)
	_, _ = swapped, c
	_ = p.Reset[bool]()

	var pt Ptr
	pt.Set(1)
	pt.Set[string]("x")
	(&pt).Set[[]int](nil)
	_ = pt.Run[int]([]int{1})

	// method values with explicit instantiation
	f := l.Map[float64]
	g := (*Box[int]).Apply[string]
	h := List[int].Fold[string]
	_, _, _ = f, g, h

	// chained and parenthesized calls
	_ = List[string]{"a"}.Map(func(s string) int { return len(s) }).Fold(0, func(a, e int) int { return a + e })
	_ = (l.Map[int])(func(i int) int { return i })
}

// Go 1.27: function type inference in all assignment contexts.
func ident[T any](t T) T { return t }

func pair[A, B any](a A, b B) (A, B) { return a, b }

type handler struct {
	on func(int) int
}

func inferenceContexts() {
	var f func(int) int = ident
	f = ident
	var fs = []func(string) string{ident, ident}
	m := map[string]func(bool) bool{"k": ident}
	h := handler{on: ident}
	var g func(int, string) (int, string) = pair
	ch := make(chan func(float64) float64, 1)
	ch <- ident
	call(ident)
	_ = func() func(int) int { return ident }
	_, _, _, _, _, _ = f, fs, m, h, g, ch
}

func call(f func(int) int) {}

// Go 1.27: struct literal keys may be promoted fields.
type Object struct{ name, color string }

type Point3D struct {
	Object
	x, y, z float64
}

type Line struct {
	Object
	p, q Point3D
}

func promotedKeys() {
	_ = Line{name: "diagonal", q: Point3D{x: 1, y: 1, z: 1}}
	_ = Line{p: Point3D{}, q: Point3D{name: "n", color: "c", x: 1}}
	_ = Line{q: Point3D{Object: Object{}}}
	_ = &Line{color: "red"}
	_ = []Point3D{{name: "a"}, {color: "b", z: 2}}
	_ = map[string]Point3D{"k": {name: "v"}}
}

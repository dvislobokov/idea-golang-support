package go127errors

type List[E any] []E

func (l List[E]) Apply[F any](f func(E) F) List[F] { return nil }

func (l List[E]) Two[A, B any](a A) B { var b B; return b }

func methods() {
	l := List[int]{}
	_ = l.Apply /* ERROR "cannot use generic function l.Apply without instantiation" */
	_ = l.Apply[int, int /* ERROR "got 2 type arguments but want 1" */]
	_ = List[int].Apply /* ERROR "cannot use generic function List[int].Apply without instantiation" */
	_ = List[int].Apply[string]
	_ = l.Two(1 /* ERROR "in call to l.Two, cannot infer B" */)
	var g func(func(int) string) List[string] = l.Apply
	_ = g
}

type Bar struct{ Baz int }

type Mid struct{ Bar }

type Foo struct {
	Mid
	Name string
}

type PFoo struct {
	*Bar
	Name string
}

func keys() {
	_ = Foo{Baz: 1, Name: "x"}
	_ = PFoo{Baz /* ERROR "invalid implicit pointer indirection to reach Baz" */ : 1}
	_ = Foo{Mid: Mid{}, Baz /* ERROR "cannot specify promoted field Baz and enclosing embedded field Mid" */ : 1}
	_ = Foo{Baz: 1, Bar /* ERROR "cannot specify embedded field Bar and enclosed promoted field Baz" */ : Bar{}}
	_ = Foo{Baz: 1, Baz /* ERROR "duplicate field name Baz in struct literal" */ : 2}
	_ = Foo{Bar.Baz /* ERROR "invalid field name Bar.Baz in struct literal" */ : 2}
}

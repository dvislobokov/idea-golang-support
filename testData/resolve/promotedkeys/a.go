package promotedkeys

type Inner struct{ /*def*/ Deep int }

type Bar struct {
	Inner
	/*def*/ Baz int
}

type Mid struct{ Bar }

type Foo struct {
	Mid
	/*def*/ Name string
}

type List[E any] []E

func (l List[E]) /*def*/ Apply[F any](f func(E) F) List[F] { return nil }

// Go 1.27: promoted fields (one or more embedding levels) as struct literal keys.
var one = Bar{/*ref*/ Deep: 1}

var two = Foo{/*ref*/ Baz: 1, /*ref*/ Name: "x"}

var three = Foo{/*ref*/ Deep: 2}

var nested = []Foo{{/*ref*/ Baz: 3}}

// Go 1.27: generic methods resolve from calls, instantiations and method expressions.
func use() {
	l := List[int]{}
	_ = l./*ref*/ Apply(func(int) string { return "" })
	_ = l./*ref*/ Apply[bool]
	_ = List[int]./*ref*/ Apply[string]
}

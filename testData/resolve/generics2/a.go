package generics2

type /*def*/ Box[T any] struct{ /*def*/ V T }

func (b Box[T]) /*def*/ Get() T    { return b.V }
func (b *Box[T]) /*def*/ Set(v T)  { b.V = v }
func /*def*/ Wrap[T any](v T) Box[T]     { return Box[T]{v} }
func /*def*/ WrapPtr[T any](v T) *Box[T] { return &Box[T]{v} }

type /*def*/ Base struct{ /*def*/ ID int }

func (b *Base) /*def*/ Describe() string { return "" }

type /*def*/ Mid struct{ *Base }
type /*def*/ Top struct {
	Mid
	/*def*/ Name string
}

type /*def*/ Pair[K comparable, V any] struct {
	/*def*/ Key K
	/*def*/ Val V
}

func /*def*/ MakePair[K comparable, V any](k K, v V) Pair[K, V] { return Pair[K, V]{k, v} }

func /*def*/ Apply[T any](f func(T) T, x T) T { return f(x) }

func use() {
	w := /*ref*/ Wrap(1)
	_ = w./*ref*/ Get()
	_ = w./*ref*/ V
	p := /*ref*/ WrapPtr("a")
	p./*ref*/ Set("b")
	_ = p./*ref*/ Get()
	mp := /*ref*/ MakePair("k", 1)
	_ = mp./*ref*/ Key
	_ = mp./*ref*/ Val
	_ = /*ref*/ Apply(func(b Box[int]) Box[int] { return b }, w)./*ref*/ V
	var t Top
	_ = t./*ref*/ ID
	_ = t./*ref*/ Describe()
	_ = t.Mid./*ref*/ ID
	_ = t./*ref*/ Name
	pt := &t
	_ = pt./*ref*/ ID
	_ = pt./*ref*/ Describe()
	ps := []*Top{pt}
	_ = ps[0]./*ref*/ Name
	_ = Pair[string, int]{/*ref*/ Key: "a", /*ref*/ Val: 1}
}

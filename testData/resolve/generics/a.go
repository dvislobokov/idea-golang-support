package generics

type /*def*/ Number interface{ ~int | ~float64 }

type /*def*/ List[/*def*/ T any] struct{ /*def*/ items []T }

func (l *List[/*def:U*/ U]) /*def*/ Push(v /*ref:U*/ U) { l./*ref*/ items = append(l.items, v) }

func (l *List[T]) /*def*/ Len() int { return len(l./*ref*/ items) }

func /*def*/ Map[/*def:A*/ A, /*def:B*/ B any](xs []/*ref:A*/ A, f func(/*ref:A*/ A) /*ref:B*/ B) []/*ref:B*/ B { return nil }

func /*def*/ Sum[/*def:N*/ N /*ref*/ Number](xs ...N) N { var s /*ref:N*/ N; return s }

type /*def*/ Pair[K comparable, V any] struct {
	/*def*/ Key K
	/*def*/ Val V
}

func use() {
	var l /*ref*/ List[int]
	l./*ref*/ Push(1)
	_ = l./*ref*/ Len()
	_ = /*ref*/ Map[int, string](nil, nil)
	_ = /*ref*/ Sum(1, 2)
	p := Pair[string, int]{/*ref*/ Key: "a", /*ref*/ Val: 1}
	_ = p./*ref*/ Key
	q := &Pair[int, bool]{}
	_ = q./*ref*/ Val
}

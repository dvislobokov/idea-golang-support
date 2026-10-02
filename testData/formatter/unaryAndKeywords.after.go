package p

type T struct{ v int }

func f(ok bool, p *T, xs []int) (*T, bool, int) {
	q := &T{v: 1}
	n := len(xs)
	_ = *p
	if !ok {
		return q, !ok, -n
	}
	return p, ok, +n
}

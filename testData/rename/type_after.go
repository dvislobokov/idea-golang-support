package r

type Vec struct{ X int }

func (p *Vec) Move() {}

func (Vec) Zero() Vec { return Vec{} }

func use(v any) {
	var p *Vec = &Vec{X: 1}
	p.Move()
	_ = Vec(*p)
	_, _ = v.(Vec)
	var _ []map[string]Vec
}

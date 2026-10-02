package r

type Po<caret>int struct{ X int }

func (p *Point) Move() {}

func (Point) Zero() Point { return Point{} }

func use(v any) {
	var p *Point = &Point{X: 1}
	p.Move()
	_ = Point(*p)
	_, _ = v.(Point)
	var _ []map[string]Point
}

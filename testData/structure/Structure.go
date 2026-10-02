package shapes

import "fmt"

const (
	Pi, E = 3.14, 2.71
	limit int = 10
)

var registry = map[string]Shape{}

var (
	count int
	Name  string
)

// Shape is a shape.
type Shape interface {
	fmt.Stringer
	Area() float64
	Scale(
		factor float64,
	) Shape
}

type Point struct {
	X, Y int
	*Base
	label string `json:"label"`
}

type Celsius float64

type List[T any] []T

func (p *Point) Move(dx, dy int) {
	var local int
	_ = local
}

func (p Point) String() string { return "" }

func NewPoint(x, y int) *Point {
	return &Point{X: x, Y: y}
}

func Map[K comparable, V any](m map[K]V, f func(V) V) (result map[K]V, err error) {
	return nil, nil
}

func (b *Base) Reset() {}

func helper() {}

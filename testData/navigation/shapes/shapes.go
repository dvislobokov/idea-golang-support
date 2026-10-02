package shapes

import "fmt"

// Shape is implemented by Circle (value receivers) and Square (pointer receivers).
type Shape interface {
	Area() float64
	Perimeter() float64
}

// Named has a single method that Circle, Square and Partial all have.
type Named interface {
	Name() string
}

type Circle struct {
	R float64
}

func (c Circle) Area() float64      { return 3 * c.R * c.R }
func (c Circle) Perimeter() float64 { return 6 * c.R }
func (c Circle) Name() string       { return "circle" }
func (c Circle) String() string     { return fmt.Sprint(c.R) }

type Square struct {
	Side float64
}

func (s *Square) Area() float64      { return s.Side * s.Side }
func (s *Square) Perimeter() float64 { return 4 * s.Side }
func (s *Square) Name() string       { return "square" }

// Partial has Area but no Perimeter: it does not implement Shape.
type Partial struct{}

func (Partial) Area() float64 { return 0 }
func (Partial) Name() string  { return "partial" }

// Wrong has Perimeter with another signature.
type Wrong struct{}

func (Wrong) Area() float64        { return 0 }
func (Wrong) Perimeter(x int) bool { return false }

func Total(shapes []Shape) float64 {
	var sum float64
	for _, s := range shapes {
		sum += s.Area()
	}
	return sum
}

package kinds

import (
	"fmt"
	"math/rand/v2"
)

type Point struct {
	X, Y int
	Base
}

type Base struct{ ID int }

type Mover interface {
	Move(dx int)
}

func (p *Point) Move(dx int) { p.X += dx }

func NewPoint(x int) *Point {
	return &Point{X: x, Y: 0}
}

func helper() int { return rand.IntN(10) }

func use() {
	p := NewPoint(1)
	p.Move(2)
	var m Mover = p
	m.Move(3)
	f := p.Move
	f(4)
	p.X = p.Y
	p.Y++
	_ = Point(*p)
	q := Point{X: 1}
	_ = q.X
	_ = q.ID
	fmt.Println(p, helper(), rand.IntN(3))
outer:
	for i := 0; i < 3; i++ {
		for {
			if i > 1 {
				break outer
			}
			continue outer
		}
	}
	goto outer
}

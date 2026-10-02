package semantic

import (
	"fmt"
	str "strings"
)

const Limit = 10

var counter int

type Point[T any] struct {
	X T
}

type Shape interface {
	Area() float64
}

func (p *Point[T]) Get() T {
	return p.X
}

func helper(n int) int {
	return n + Limit
}

func run(s Shape) {
	local := helper(len("ab"))
	counter = local
	var b str.Builder
	b.WriteString("x")
	p := Point[int]{X: 1}
	_ = p.Get()
	_ = s.Area()
	if local > 0 && true {
		fmt.Println(b.String(), nil)
	}
Loop:
	for {
		break Loop
	}
}

package main

import (
	"errors"
	"fmt"
	"strings"

	"example.com/simple/util"
)

// Shape is something with an area and a name.
type Shape interface {
	Area() float64
	Name() string
}

// Rect is an axis-aligned rectangle.
type Rect struct {
	Width, Height float64
}

// Area returns the area of the rectangle.
func (r Rect) Area() float64 {
	return r.Width * r.Height
}

// Name returns the name of the shape.
func (r Rect) Name() string {
	return "rect"
}

// Color is a palette entry.
type Color int

const (
	Red Color = iota
	Green
	Blue
)

// Max returns the larger of a and b.
func Max[T ~int | ~float64 | ~string](a, b T) T {
	if a > b {
		return a
	}
	return b
}

func describe(s Shape) string {
	return fmt.Sprintf("%s: %.1f", s.Name(), s.Area())
}

func parse(input string) ([]string, error) {
	if input == "" {
		return nil, errors.New("empty input")
	}
	return strings.Split(input, ","), nil
}

func main() {
	shapes := []Shape{Rect{Width: 2, Height: 3}}
	for _, s := range shapes {
		fmt.Println(describe(s))
	}
	parts, err := parse("a,b,c")
	if err != nil {
		fmt.Println(err)
		return
	}
	total := len(parts)
	fmt.Println(strings.Join(parts, "+"), total, Max(Red, Blue), Green, util.Greet("go"))
	fmt.Printf("%d colors\n", Blue+1)
}

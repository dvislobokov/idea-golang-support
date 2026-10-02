// Package doc is a fixture for documentation rendering.
//
// # Overview
//
// It has a [Point] type and a list:
//   - first item
//   - second item
//
// and code:
//
//	p := doc.Point{X: 1}
//	p.Move(2)
//
// See https://go.dev/doc/comment and [the spec]; escape <b>tags</b> & such.
//
// [the spec]: https://go.dev/ref/spec
package doc

import "io"

// Pi is an untyped float constant.
const Pi = 3.14

// Typed constants.
const (
	// Max is typed.
	Max int64 = 1 << 10
	Min       = -Max // Min is derived.
)

// Default is the default point.
var Default = Point{X: 1}

// Point is a 2D point.
// Use ``quotes'' freely.
type Point struct {
	// X is the horizontal coordinate.
	X int
	Y int // Y is vertical.
	io.Reader
}

// Move moves the point by dx.
func (p *Point) Move(dx int) { p.X += dx }

// String formats the point.
func (p Point) String() string { return "" }

func (p Point) unexported() {}

// Shape is an interface.
type Shape interface {
	// Area returns the area.
	Area() float64
}

// Map applies f to every element of s.
func Map[T, U any](s []T, f func(T) U) []U { return nil }

// Sum returns the sum.
//
//go:noinline
func Sum(xs ...int) (total int, err error) { return 0, nil }

func use(n int) {
	count := n
	_ = count
}

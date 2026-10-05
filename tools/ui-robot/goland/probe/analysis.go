// Package probe is the GoLand analysis probe file (docs/goland-analysis): every construct the reference IDE may color, mark or complete.
// Lines "// PROBE:<name>" are anchors: the line right after each one stays empty, probes type there and restore the file.
package probe

import (
	_ "embed"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
)

//go:generate stringer -type=Level

//go:embed analysis.go
var selfSource string

// Level is a severity with iota constants.
type Level int

const (
	Debug Level = iota
	Info
	Warn
)

// MaxItems is an exported constant; minItems is not.
const MaxItems = 10

const minItems = 1

var ErrNotFound = errors.New("probe: not found")

// Shape is implemented implicitly by Circle (value receiver) and Square (pointer receiver).
type Shape interface {
	Area() float64
	Name() string
}

// Base is embedded into Circle.
type Base struct {
	ID      int
	created time.Time
}

// Describe is promoted to Circle through the embedded Base.
func (b Base) Describe() string { return fmt.Sprintf("#%d", b.ID) }

// Circle implements Shape with value receivers.
type Circle struct {
	Base
	Radius float64 `json:"radius,omitempty"`
	Label  string  `json:"label" yaml:"label"`
}

func (c Circle) Area() float64 { return 3.14 * c.Radius * c.Radius }

func (c Circle) Name() string { return c.Label }

// Square implements Shape with pointer receivers.
type Square struct {
	Side float64 `json:"side"`
}

func (s *Square) Area() float64 { return s.Side * s.Side }

func (s *Square) Name() string { return "square" }

// Number is a type-set constraint.
type Number interface {
	~int | ~float64
}

// Sum is a generic function over Number.
func Sum[T Number](xs []T) T {
	var total T
	for _, x := range xs {
		total += x
	}
	return total
}

// Set is a generic type with a comparable key.
type Set[K comparable] struct {
	items map[K]struct{}
}

func (s *Set[K]) Add(k K) {
	if s.items == nil {
		s.items = make(map[K]struct{})
	}
	s.items[k] = struct{}{}
}

func (s *Set[K]) Len() int { return len(s.items) }

// Factorial is recursive.
func Factorial(n int) int {
	if n <= 1 {
		return 1
	}
	return n * Factorial(n-1)
}

func counter() func() int {
	count := 0
	return func() int {
		count++
		return count
	}
}

func produce(n int) <-chan int {
	ch := make(chan int, n)
	go func() {
		defer close(ch)
		for i := 0; i < n; i++ {
			ch <- i
		}
	}()
	return ch
}

func classify(x any) string {
	switch v := x.(type) {
	case int:
		return fmt.Sprintf("int %d", v)
	case string:
		return "string " + v
	case Shape:
		return v.Name()
	default:
		return "other"
	}
}

func find(grid [][]int, target int) (int, int, error) {
Outer:
	for i, row := range grid {
		for j, v := range row {
			if v < 0 {
				break Outer
			}
			if v == target {
				return i, j, nil
			}
		}
	}
	return -1, -1, ErrNotFound
}

func load(name string) (string, error) {
	if name == "" {
		return "", ErrNotFound
	}
	return strings.ToUpper(name), nil
}

// Analysis gathers formatting, regexp, time layouts, shadowing and reassignment.
func Analysis(name string, timeout time.Duration) (string, error) {
	value, err := load(name)
	if err != nil {
		return "", fmt.Errorf("load %s: %w", name, err)
	}
	if value != "" {
		value, err := load(value + "x")
		if err != nil {
			return "", err
		}
		_ = value
	}
	total := 0
	total = total + len(value)
	re := regexp.MustCompile(`^[a-z]+\d{2,}$`)
	stamp := time.Now().Format("2006-01-02 15:04:05")
	msg := fmt.Sprintf("%d %s %v %q", total, stamp, re.MatchString(value), value)
	select {
	case n := <-produce(3):
		msg += fmt.Sprint(n)
	case <-time.After(timeout):
		msg += "timeout"
	}
	next := counter()
	msg += fmt.Sprint(next(), Factorial(3), Sum([]int{1, 2}), classify(Circle{}), minItems)
	var set Set[string]
	set.Add(msg)
	shapes := []Shape{Circle{Radius: 1}, &Square{Side: 2}}
	for _, s := range shapes {
		msg += s.Name()
	}
	if _, _, err := find([][]int{{1}}, 1); err != nil {
		return "", err
	}
	_ = selfSource
	return msg, nil
}

// Probes holds the empty anchor lines for completion and typing probes.
func Probes(c Circle, sq *Square, shapes []Shape, names []string, ch chan int, err error, lvl Level) error {
	var total int
	// PROBE:body

	// PROBE:body2

	_ = total
	return nil
}

// Returns is a function with several results for return probes.
func Returns(name string) (*Circle, int, error) {
	// PROBE:ret

	return nil, 0, nil
}

// PROBE:top

// Holder is a struct for literal and tag probes.
type Holder struct {
	Name    string
	Count   int
	Enabled bool
	// PROBE:field

}

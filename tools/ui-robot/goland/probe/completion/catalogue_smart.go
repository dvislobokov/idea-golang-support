// Package completion holds the live probes of catalogue completion (smart rows of the expected type, implementations of an interface).
// Each "// caret:" line tells where to put the caret, which completion to call and the rows expected (GoLand dump probes 12, 13, 14, 24).
package completion

import "strings"

type Shape interface{ Area() float64 }

type Circle struct{ R float64 }

type Square struct{ S float64 }

func (c Circle) Area() float64  { return c.R * c.R * 3 }
func (s *Square) Area() float64 { return s.S * s.S }

func Factorial(n int) int {
	if n < 2 {
		return 1
	}
	return n * Factorial(n-1)
}

func smartInt() {
	var total int
	// caret: after "total = " on the next line, Smart (Ctrl+Shift+Space): Factorial, total, then strings.Compare / strings.Count / strings.Index… (path "strings", ≤ 10), then utf8.RuneLen… of packages not imported (import added on pick)
	total = 0
	_ = strings.ToUpper("")
	_ = total
}

func smartSend(ch chan int) {
	// caret: after "ch <- " on the next line, Smart: Factorial, strings.Count… ; pick strings.Count -> "ch <- strings.Count(<caret>)"
	ch <- 0
}

func smartReturn() string {
	// caret: after "return " on the next line, Smart: strings.ToUpper / strings.Repeat / strings.Join… (no int rows)
	return ""
}

func smartInterface() {
	var c Circle
	var sq Square
	// caret: after "var s Shape = " on the next line, Smart: c, Circle{}, &Square{}, &sq, nil (no Square{}, no sq)
	var s Shape = nil
	_, _, _ = c, sq, s
}

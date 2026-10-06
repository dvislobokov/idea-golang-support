package builtinsconsts

import (
	"math"
	"unsafe"
)

// Builtins over type sets (go/types typeset / underIs / sliceElem).

func clearAny[T any](x T) {
	clear(x) // ERROR "invalid argument: cannot clear x (variable of type T constrained by any): argument must be (or constrained by) map or slice"
}

func clearInt(x int) {
	clear(x) // ERROR "invalid argument: cannot clear x (variable of type int): argument must be (or constrained by) map or slice"
}

func clearOK[T ~map[int]string | ~[]byte](x T) { clear(x) }

func closeAny[T any](ch T) {
	close(ch) // ERROR "invalid operation: cannot close non-channel ch (variable of type T constrained by any)"
}

func closeRecv[T chan int | <-chan int](ch T) {
	close(ch) // ERROR "invalid operation: cannot close receive-only channel ch"
}

func closeOK[T chan int | chan<- int](ch T) { close(ch) }

type myByte byte

func copies[T ~[]byte](x, y T) {
	copy(x, "foo")
	copy(x, y)
	var b []myByte
	copy(b, y) // ERROR "invalid copy: arguments b (variable of type []myByte) and y (variable of type T constrained by ~[]byte) have different element types myByte and byte"
	copy("foo", y) // ERROR "invalid copy: argument must be a slice; have \"foo\" (untyped string constant)"
}

func copyMixed[T ~[]int | ~[]string](x T, y []int) {
	copy(x, y) // ERROR "invalid copy: mismatched slice element types int and string in x"
}

func copyString[T ~string](x []int, y T) {
	copy(x, y) // ERROR "have different element types int and byte"
}

type M3 interface{ map[string]int | map[rune]int }

type M1 interface{ map[string]int | map[string]float64 }

func deletes[T M3, U M1](m T, u U, n int) {
	delete(m, "k") // ERROR "invalid argument: maps of m (variable of type T constrained by M3) must have identical key types"
	delete(n, 1)   // ERROR "invalid argument: n (variable of type int) is not a map"
	delete(u, "k")
}

func pair() (int, string) { return 0, "" }

func slices() ([]int, int) { return nil, 0 }

func others() {
	_ = append(pair()) // ERROR "invalid append: argument must be a slice; have 1st function result (value of type int)"
	_ = append(slices())
	_ = max(1, "x")   // ERROR "invalid argument: mismatched types untyped int (previous argument) and untyped string (type of \"x\")"
	_ = min(1, 2.5)
	_ = new(nil)    // ERROR "use of untyped nil in argument to new"
	_ = new(unsafe) // ERROR "use of package unsafe not in selector"
}

// Constants.

const _ []int = nil // ERROR "invalid constant type []int"

const _ = 1 % 1.0 // ERROR "invalid operation: operator % not defined on 1 (untyped float constant)"

const big = 1 << 100

var _ = big // ERROR "cannot use big (untyped int constant 1267650600228229401496703205376) as int value in variable declaration (overflows)"

func defaults() {
	x := big // ERROR "cannot use big (untyped int constant 1267650600228229401496703205376) as int value in assignment (overflows)"
	_ = x
	_ = big // ERROR "cannot use big (untyped int constant 1267650600228229401496703205376) as int value in assignment to _ identifier (overflows)"
	y := big >> 90
	_ = y
}

const maxU = (1<<256 - 1) * (1<<256 + 1)

const _ = ^maxU // ERROR "constant bitwise complement overflow"

const _ = ^(maxU - 1)

const _ = 1000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000 // ERROR "constant overflow"

const (
	b0 = byte(iota + 254)
	b1
	b2 // ERROR "constant 256 overflows byte"
)

var n = 3

var (
	_ [-1]int    // ERROR "invalid array length -1 (untyped int constant)"
	_ [n]int     // ERROR "invalid array length n"
	_ [1.5]int   // ERROR "array length 1.5 (untyped float constant) must be integer"
	_ [1 << 64]int // ERROR "invalid array length 1 << 64 (untyped int constant 18446744073709551616)"
	_ [len("ab")]int
	_ [2.0]int
	_ [unsafe.Sizeof(n)]byte
	_ [five()]int // ERROR "array length five() (value of type int) must be constant"
)

func five() int { return 5 }

// Comparisons.

type I interface{ m() int }

type withSlice struct{ s []int }

func compare[T any, C comparable](i I, x, y T, c C, e interface{}, a, b withSlice) {
	_ = i == 0 // ERROR "invalid operation: i == 0 (mismatched types I and untyped int)"
	_ = x == y // ERROR "invalid operation: x == y (incomparable types in type set)"
	_ = c == c
	_ = e == 1
	_ = a == b // ERROR "invalid operation: a == b (struct containing []int cannot be compared)"
	_ = e == a // ERROR "invalid operation: e == a (struct containing []int cannot be compared)"
}

// Declarations.

func results() (a, b int) {
	{
		type a int
		return // ERROR "result parameter a not in scope at return"
	}
}

func resultsOK() (a, b int) {
	{
		c := 1
		_ = c
		return
	}
}

// `:=` in the outermost block reuses the named results (same scope as the parameters): no shadowing.
func resultsReused(f func() (int, int)) (n int, err error) {
	m, err := f()
	n = m
	return
}

func resultsReusedLit() (n int, err error) {
	g := func() (k int, err error) {
		m, err := 1, error(nil)
		k = m
		return
	}
	n, err = g()
	{
		n, err := g()
		_, _ = n, err
		return // ERROR "result parameter n not in scope at return"
	}
}

type T0 struct{}

type A0 = T0

func (T0) m1() {}

func (A0) m1() {} // ERROR "method T0.m1 already declared"

func (A0) m2() {}

type S struct {
	a int
	a string // ERROR "a redeclared"
	_ int
	_ int
}

var _ math.Pi // ERROR "math.Pi (untyped float constant 3.14159) is not a type"

func ptr[T interface{ m() }](x *T) {
	x.m() // ERROR "x.m undefined (type *T is pointer to type parameter, not type parameter)"
}

func init() // ERROR "func init must have a body"

type A10 = [10]int

func (A10) m() {} // ERROR "invalid receiver type A10"

type (
	E1 struct{ X int }
	E2 struct{ E1 }
	E3 struct{ E2 }
	E4 struct{ E2 }
	E5 struct {
		E3
		E4
	}
)

var _ = E5{}.X // ERROR "ambiguous selector E5{}.X"

type (
	F6 struct{ X int }
	F7 F6
	F5 struct {
		F6
		F7
	}
)

var _ = F5{}.X // ERROR "ambiguous selector F5{}.X"

// Package p is a folding test.
package p

import <fold text='(...)'>(
	"fmt"
	"os"
)</fold>

import "strings"

<fold text='//...'>// first
// second
// third</fold>
const <fold text='(...)'>(
	A = 1
	B = 2
)</fold>

var <fold text='(...)'>(
	x int
)</fold>

type <fold text='(...)'>(
	Point struct <fold text='{...}'>{
		X, Y int
	}</fold>
	Reader interface <fold text='{...}'>{
		Read(p []byte) (int, error)
	}</fold>
)</fold>

type Empty struct{}

<fold text='/*...*/'>/*
Block comment
*/</fold>
func f() <fold text='{...}'>{
	g := func() <fold text='{...}'>{
		fmt.Println(os.Args, strings.ToUpper("x"))
	}</fold>
	g()
	p := Point<fold text='{...}'>{
		X: 1,
		Y: 2,
	}</fold>
	_ = p
	if x > 0 {
		x--
	}
	// two
	// lines
}</fold>

func short() { return }

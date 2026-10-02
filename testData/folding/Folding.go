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
	if x > 0 <fold text='{...}'>{
		x--
		for i := 0; i < 2; i++ <fold text='{...}'>{
			x += i
		}</fold>
	}</fold> else <fold text='{...}'>{
		x++
	}</fold>
	switch x <fold text='{...}'>{
	case 1:
		x--
	}</fold>
	select <fold text='{...}'>{
	default:
	}</fold>
	if x < 0 { x = 0 }
	// one line alone does not fold
	_ = p
	<fold text='//...'>// two
	// lines</fold>
}</fold>

// A lone comment line above a blank line does not fold.

<fold text='//...'>// Doc comment of short: two lines fold above a declaration too.
// Second line of the doc comment.</fold>
func short() { return }

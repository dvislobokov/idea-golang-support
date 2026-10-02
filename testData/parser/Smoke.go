package main

import "fmt"

// Point is a pair.
type Point struct {
	X, Y int
}

func main() {
	p := Point{1, 2}
	fmt.Println(p.X + p.Y)
}

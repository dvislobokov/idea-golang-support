// Stub coverage: named and embedded fields, tags, pointer embedding, nested struct types.
package structs

import "sync"

type Point struct {
	X, Y int `json:"x"`
	Label string
}

type Node struct {
	*Point
	sync.Mutex
	Pair[int, string]
	children [2]*Node
	meta     struct {
		Tags []string `yaml:"tags"`
	}
	ignored [...]int
}

type Pair[A, B any] struct {
	First  A
	Second B
}

func (n *Node) Area() int {
	type hidden struct{ inner int }
	return n.X * n.Y
}

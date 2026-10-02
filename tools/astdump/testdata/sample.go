package sample

import "fmt"

// Point is a pair.
type Point struct{ X, Y int } // trailing

/* Map applies f. */
func Map[T, U any](xs []T, f func(T) U) []U {
	var out []U
	for _, x := range xs {
		out = append(out, f(x))
	}
	return out
}

func main() {
	if p := (Point{1, 2}); p.X > 0 {
		fmt.Println(p.X+1, "a\n")
	}
}

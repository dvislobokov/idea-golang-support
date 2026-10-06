package goversion

// want: type parameter requires go1.18 or later (-lang was set to go1.17; check go.mod)
func Map[T, U any](s []T, f func(T) U) []U { return nil }

func Builtins(m map[string]int, a, b int) int {
	// want: clear requires go1.21 or later (-lang was set to go1.17; check go.mod)
	clear(m)
	// want: built-in min requires go1.21 or later (-lang was set to go1.17; check go.mod)
	return min(a, b)
}

func Ranges(n int, seq func(yield func(int) bool)) {
	// want: cannot range over n (variable of type int): requires go1.22 or later (-lang was set to go1.17; check go.mod)
	for range n {
	}
	// want: cannot range over seq (variable of type func(yield func(int) bool)): requires go1.23 or later (-lang was set to go1.17; check go.mod)
	for range seq {
	}
}

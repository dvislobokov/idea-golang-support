// Exercises simple statements (GRAMMAR.md section G): labels, send, inc/dec, every
// assignment operator, all range forms, type switch guards with and without binding.
package cases

func simple(ch chan int, seq func(func(int) bool), v any) {
	x, y := 1, 2
	x, y = y, x
	x++
	y--
	ch <- x
	x += 1
	x -= 1
	x *= 2
	x /= 2
	x %= 3
	x &= 1
	x |= 1
	x ^= 1
	x <<= 1
	x >>= 1
	x &^= 1
outer:
	for range ch {
		break outer
	}
	for i := range 10 {
		_ = i
	}
	for x := range seq {
		_ = x
	}
	for i, c := range "ab" {
		_, _ = i, c
	}
	for _, c := range []int{1} {
		_ = c
	}
	for x = range ch {
	}
	for x, y = range []int{} {
	}
	switch v.(type) {
	case int, string:
	}
	switch t := v.(type) {
	case nil:
		_ = t
	}
	switch x := 1; t := v.(type) {
	default:
		_, _ = x, t
	}
}

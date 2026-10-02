// Exercises composite literals in if/for/switch headers (GRAMMAR.md section B).
// Parenthesised and nested forms that must parse; type-checks.
package cases

type T struct{ a, b int }

func eq(x, y T) bool { return x == y }

func headers(x T, xs []T, m map[string]T) {
	if x == (T{1, 2}) {
	}
	if eq(T{}, T{a: 1}) {
	}
	if v := (T{1, 2}); v.a > 0 {
	}
	if xs[0] == (T{}) || len([]T{{1, 2}}) > 0 {
	}
	for _, v := range []T{{1, 2}, {3, 4}} {
		_ = v
	}
	for k := range map[string]T{"a": {1, 2}} {
		_ = k
	}
	for i := 0; i < len([]int{1, 2}); i++ {
	}
	switch (T{1, 2}) {
	case T{}:
	}
	switch v := (struct{ n int }{1}); v.n {
	case 1:
	}
	if m["k"] == (T{}) {
	}
	if f := func() T { return T{1, 2} }; f().a > 0 {
	}
}

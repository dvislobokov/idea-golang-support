// Exercises automatic semicolon insertion edge cases (GRAMMAR.md section A): method
// chains with trailing dots, multi-line composite literals with trailing commas, `return`
// followed by a newline, block comments with newlines, explicit semicolons. Type-checks.
package cases

import "strings"

type B struct{ s []string }

func (b *B) Add(s string) *B { b.s = append(b.s, s); return b }

func f() {
	_ = strings.NewReplacer("a", "b").
		Replace("abc")
	_ = (&B{}).
		Add("x").
		Add("y")
	_ = []int{
		1,
		2,
	}
	_ = map[string]struct{ a int }{
		"k": {
			a: 1,
		},
	}
	g(1,
		2,
	)
	if true {
		return
	}
	x := 1 /* multi
	line */
	_ = x
	for i := 0; i < 2; i++ {
		_ = i
	}
	{
	}
	return
}

func g(a, b int) {}

func h() int { return 1 }
func k()     {}

// Valid but unformatted code: statements and declarations at column 0 inside a block must
// still parse (GRAMMAR.md J.1 only ends a block when no column-0 "}" follows).
package cases

func f() int {
var x = 1
const c = 2
type t struct{ a int }
for i := 0; i < c; i++ {
x += i
}
return x
}

func g() {}

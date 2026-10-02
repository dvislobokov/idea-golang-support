package unresolved

import "fmt"

type T struct{ x int }

func (T) M() {}

func f() {
	_ = <error descr="undefined: undefinedName">undefinedName</error>
	_ = fmt.<error descr="undefined: fmt.Nope">Nope</error>
	_ = fmt.<error descr="name newPrinter not exported by package fmt">newPrinter</error>
	var t T
	t.<error descr="t.N undefined (type T has no field or method N)">N</error>()
	_ = t.<error descr="t.y undefined (type T has no field or method y)">y</error>
	var u <error descr="undefined: Undefined">Undefined</error>
	_ = u
	_ = T{<error descr="unknown field y in struct literal of type T">y</error>: 1}
	fmt.Println(t)
}

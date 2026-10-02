package undefined

import (
	"fmt"
	"strings"
)

type T struct{ x int }

func (T) M() {}

func f() {
	_ = undefinedName // ERROR "undefined: undefinedName"
	_ = fmt.Sprintf("%d", 1)
	_ = fmt.Nope // ERROR "undefined: fmt.Nope"
	_ = fmt.newPrinter // ERROR "name newPrinter not exported by package fmt"
	var t T
	_ = t.x
	_ = t.y // ERROR "t.y undefined (type T has no field or method y)"
	t.M()
	t.N() // ERROR "t.N undefined (type T has no field or method N)"
	var p *T
	_ = p.x
	var u Undefined // ERROR "undefined: Undefined"
	_ = u
	var s strings.Builder
	s.WriteString("a")
	_ = strings.NoSuch // ERROR "undefined: strings.NoSuch"
	_ = T{y: 1} // ERROR "unknown field y in struct literal of type T"
	_ = T{x: 1}
	_ = _ // ERROR "cannot use _ as value"
}

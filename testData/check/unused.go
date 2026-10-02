package unused

import (
	"fmt"
	"os" // ERROR `"os" imported and not used`
	str "strings" // ERROR `"strings" imported as str and not used`
	_ "embed"
)

func use(x int) {}

func f(a int) (r int) {
	var x int // ERROR "declared and not used: x"
	y := 1 // ERROR "declared and not used: y"
	y = 2
	z := 3
	z++
	_ = z
	w := 4
	use(w)
	var v int
	v = 1
	_ = v
	for i := range 3 { // ERROR "declared and not used: i"
	}
	for j := range 3 {
		use(j)
	}
	if k := a; true { // ERROR "declared and not used: k"
	}
	func() {
		q := 1 // ERROR "declared and not used: q"
	}()
	var e error
	switch t := e.(type) { // ERROR "t declared and not used"
	case nil:
	}
	switch u := e.(type) {
	case nil:
		_ = u
	}
	fmt.Println(a)
L: // ERROR "label L declared and not used"
	for {
		break
	}
M:
	for {
		continue M
	}
	return
}

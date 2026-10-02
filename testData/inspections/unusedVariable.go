package unusedvariable

func use(int) {}

func f(a int) {
	var <warning descr="declared and not used: x">x</warning> int
	<warning descr="declared and not used: y">y</warning> := 1
	y = 2
	z := 3
	z++
	for <warning descr="declared and not used: i">i</warning> := range 3 {
	}
	if <warning descr="declared and not used: k">k</warning> := a; true {
	}
	var e error
	switch <warning descr="t declared and not used">t</warning> := e.(type) {
	case nil:
	}
	w := 4
	use(w)
}

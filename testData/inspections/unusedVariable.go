package unusedvariable

func use(int) {}

func f(a int) {
	var <error descr="declared and not used: x">x</error> int
	<error descr="declared and not used: y">y</error> := 1
	y = 2
	z := 3
	z++
	for <error descr="declared and not used: i">i</error> := range 3 {
	}
	if <error descr="declared and not used: k">k</error> := a; true {
	}
	var e error
	switch <error descr="t declared and not used">t</error> := e.(type) {
	case nil:
	}
	w := 4
	use(w)
}

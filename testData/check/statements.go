package statements

func two() (int, string) { return 0, "" }

func f() {
	var a, b = 1, 2
	var c, d = 1 // ERROR "assignment mismatch: 2 variables but 1 value"
	var e = 1, 2 // ERROR "extra init expr 2"
	var g, h = two()
	var i, j, k = two() // ERROR "assignment mismatch: 3 variables but two returns 2 values"
	x, y := two()
	z := two() // ERROR "multiple-value two() (value of type (int, string)) in single-value context"
	m := map[string]int{}
	v, ok := m["a"]
	_, _, _, _, _, _, _, _, _, _, _, _, _, _ = a, b, c, d, e, g, h, i, j, k, x, y, v, ok
	_, _ = z, z
	a, b = b, a
	a, b = 1 // ERROR "assignment mismatch: 2 variables but 1 value"
	a = 1, 2 // ERROR "assignment mismatch: 1 variable but 2 values"
	q := nil // ERROR "use of untyped nil in assignment"
	_ = q
	a := 1 // ERROR "no new variables on left side of :="
	a, n := 1, 2
	_ = n
	if a { // ERROR "non-boolean condition in if statement"
	}
	for a { // ERROR "non-boolean condition in for statement"
	}
	for range 10 {
	}
	for i := range "abc" {
		_ = i
	}
	for i, r := range "abc" {
		_, _ = i, r
	}
	for range a {
	}
	for i, j, k := range []int{} { // ERROR "range over []int{} (value of type []int) permits only two iteration variables"
		_, _, _ = i, j, k
	}
	var f64 float64
	for range f64 { // ERROR "cannot range over f64 (variable of type float64)"
	}
	ch := make(chan int)
	for i, j := range ch { // ERROR "range over ch (variable of type chan int) permits only one iteration variable"
		_, _ = i, j
	}
	seq := func(yield func(int) bool) {}
	for i := range seq {
		_ = i
	}
	var any1 any
	switch t := any1.(type) {
	case int:
		_ = t
	}
	switch n := a; n {
	case "x": // ERROR `invalid case "x" in switch on n (mismatched types untyped string and int)`
	}
	switch a {
	case 1, 2:
	}
	var s string
	switch s {
	case 1: // ERROR "invalid case 1 in switch on s (mismatched types untyped int and string)"
	}
	_ = any1.(int)
	_ = a.(int) // ERROR "invalid operation: a (variable of type int) is not an interface"
	a // ERROR "a (variable of type int) is not used"
	1 // ERROR "1 (untyped int constant) is not used"
	a + 1 // ERROR "a + 1 (value of type int) is not used"
	len(s) // ERROR "len(s) (value of type int) is not used"
	two()
	break // ERROR "break is not in a loop, switch, or select"
	continue // ERROR "continue is not in a loop"
	for {
		break
		continue
	}
	switch {
	case true:
		break
		continue // ERROR "continue is not in a loop"
	}
L:
	for {
		continue L
		break L
	}
	{
		var dup int
		var dup string // ERROR "dup redeclared in this block"
		_, _ = dup, dup
	}
}

func dupParam(p int) {
	var p string // ERROR "p redeclared in this block"
	_ = p
}

func noRet() (n int, err error) {
	return
}

var pkgDup int

var pkgDup string // ERROR "pkgDup redeclared in this block"

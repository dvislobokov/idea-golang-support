package calls

func f(a int, b string) {}

func v(a int, rest ...string) {}

func two() (int, string) { return 0, "" }

func none() {}

type T struct{ x int }

func (t T) M(n int) int { return n }

func g() {
	f(1, "a")
	f(1) // ERROR "not enough arguments in call to f"
	f(1, "a", 2) // ERROR "too many arguments in call to f"
	f("a" /* ERROR `cannot use "a" (untyped string constant) as int value in argument to f` */, 1 /* ERROR "cannot use 1 (untyped int constant) as string value in argument to f" */)
	f(two())
	v(1)
	v(1, "a", "b")
	v(1, []string{"a"}...)
	v(1, 2) // ERROR "cannot use 2 (untyped int constant) as string value in argument to v"
	f(1, []string{"a"}...) // ERROR "cannot use ... in call to non-variadic f"
	var x int
	x() // ERROR "invalid operation: cannot call x (variable of type int): int is not a function"
	var t T
	_ = t.M(1)
	_ = t.M() // ERROR "not enough arguments in call to t.M"
	_ = T.M(t, 1)
	_ = none() // ERROR "none() (no value) used as value"
	var n int = two() // ERROR "multiple-value two() (value of type (int, string)) in single-value context"
	_ = n
	_ = len(1) // ERROR "invalid argument: 1 (untyped int constant) for built-in len"
	_ = len("abc")
	var s []int
	s = append(s, "x") // ERROR `cannot use "x" (untyped string constant) as int value in argument to append`
	s = append(s, 1, 2)
	m := map[string]int{}
	delete(m, 1) // ERROR "cannot use 1 (untyped int constant) as string value in argument to delete"
	delete(m, "a")
	_ = make([]int) // ERROR "expects 2 or 3 arguments; found 1"
	_ = make([]int, 2, 3)
	_ = make(map[string]int)
	_ = make(int) // ERROR "cannot make int: type must be slice, map, or channel"
	_ = new(int)
	_ = int("a") // ERROR `cannot convert "a" (untyped string constant) to type int`
	_ = string(65)
	_ = float64(x)
	_ = []byte("s")
	_ = int(1.5) // ERROR "cannot convert 1.5 (untyped float constant) to type int (truncated)"
	_ = int8(300) // ERROR "cannot convert 300 (untyped int constant) to type int8 (overflows)"
	_ = T(x) // ERROR "cannot convert x (variable of type int) to type T"
	close(m) // ERROR "invalid operation: cannot close non-channel m (variable of type map[string]int)"
	defer f(1, "a")
	defer x // ERROR "expression in defer must be function call"
	go none()
}

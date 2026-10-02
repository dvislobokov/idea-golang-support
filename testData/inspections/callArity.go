package callarity

func f(a int, b string) {}

func two() (int, string) { return 0, "" }

func none() {}

func g() int {
	f(1<error descr="not enough arguments in call to f; have (number); want (int, string)">)</error>
	f(1, "a", <error descr="too many arguments in call to f; have (number, string, number); want (int, string)">2</error>)
	f<error descr="have (...) but function is not variadic: cannot use ... in call to non-variadic f">(1, []string{"a"}...)</error>
	_ = <error descr="none() (no value) used as value">none()</error>
	var n int = <error descr="multiple-value two() (value of type (int, string)) in single-value context">two()</error>
	_ = n
	var a, b, c = <error descr="assignment mismatch: 3 variables but two returns 2 values">two()</error>
	_, _, _ = a, b, c
	return <error descr="too many return values; have (number, number); want (int)">1</error>, 2
}

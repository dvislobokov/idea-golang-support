package checker

func f() {
	var x int
	<error descr="invalid operation: cannot call x (variable of type int): int is not a function">x()</error>
	_ = 1 / <error descr="invalid operation: division by zero">0</error>
	_ = len(<error descr="invalid argument: 1 (untyped int constant) for built-in len">1</error>)
	if <error descr="non-boolean condition in if statement">x</error> {
	}
	<error descr="x == 1 (untyped bool value) is not used">x == 1</error>
	for {
		break
	}
	<error descr="continue is not in a loop">continue</error>
}

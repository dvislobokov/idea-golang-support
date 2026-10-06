package probe

func bcIota() {
	iota := 123
	// Not reported: inside a const spec the typer folds the name iota as the predeclared constant even when
	// a local variable shadows it.
	// want: iota (variable of type int) is not constant
	const x = iota
	_ = x
}

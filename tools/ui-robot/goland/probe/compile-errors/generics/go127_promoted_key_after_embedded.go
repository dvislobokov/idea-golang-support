package probe

type paBar struct{ Baz int }

type paFoo struct{ paBar }

func paUse() {
	// want: cannot specify promoted field Baz and enclosing embedded field paBar
	_ = paFoo{paBar: paBar{}, Baz: 1}
}
